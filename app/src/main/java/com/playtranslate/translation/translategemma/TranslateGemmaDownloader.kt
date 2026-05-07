package com.playtranslate.translation.translategemma

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.StatFs
import android.util.Log
import com.playtranslate.language.DownloadProgress
import com.playtranslate.language.LanguagePackDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * Drives a manifest-backed download of the TranslateGemma GGUF.
 *
 * Pipeline:
 *  1. Pre-flight checks (RAM / free storage / metered) — surfaced as [Outcome.Refused].
 *  2. Streamed download via [LanguagePackDownloader] (resumable, so the partial file
 *     persists across cancel-by-dismiss; only [cancel] explicitly deletes it).
 *  3. Streaming SHA-256 over the file. On hash mismatch: delete and report failure.
 *  4. On success: flip `prefs.translateGemmaEnabled = true` is the caller's job —
 *     we just emit [Outcome.Success].
 *
 * The downloader is stateless apart from the URL/file paths derived from the catalog
 * entry; all UI state lives in the caller (the SettingsBottomSheet that hosts the
 * progress dialog).
 */
class TranslateGemmaDownloader(
    private val context: Context,
    private val httpDownloader: LanguagePackDownloader = LanguagePackDownloader(),
) {

    sealed interface Progress {
        data class Downloading(val received: Long, val total: Long) : Progress
        data object Verifying : Progress
    }

    sealed interface Outcome {
        data object Success : Outcome
        data class Refused(val reason: String) : Outcome
        data class Failed(val reason: String, val cause: Throwable? = null) : Outcome
        /** Caller cancelled mid-flight. Partial file may still be on disk;
         *  caller decides whether to keep it (for resume) or delete it. */
        data object Cancelled : Outcome
    }

    /**
     * Run the full download → verify pipeline. Honors coroutine cancellation;
     * the underlying [LanguagePackDownloader] cancels the OkHttp call when the
     * surrounding coroutine is cancelled.
     */
    suspend fun run(
        onProgress: (Progress) -> Unit,
    ): Outcome = withContext(Dispatchers.IO) {
        val entry = TranslateGemmaModel.catalogEntry(context)
            ?: return@withContext Outcome.Failed("Catalog entry missing for engine-translategemma")
        val url = entry.url
            ?: return@withContext Outcome.Failed("Catalog URL missing for engine-translategemma")
        val expectedSize = entry.size
        val expectedSha = entry.sha256
            ?: return@withContext Outcome.Failed("Catalog SHA256 missing for engine-translategemma")

        // -- Pre-flights -----------------------------------------------------------
        preflightRam()?.let { return@withContext Outcome.Refused(it) }
        preflightStorage(expectedSize)?.let { return@withContext Outcome.Refused(it) }
        // Metered-network is a *warning* the caller should surface BEFORE calling run().
        // We don't gate here — the caller is responsible for prompting the user.

        val destination = TranslateGemmaModel.file(context)
        Log.i(TAG, "Starting download: $url -> ${destination.absolutePath} (expected $expectedSize bytes)")

        // -- Download --------------------------------------------------------------
        try {
            httpDownloader.download(url, destination) { p ->
                onProgress(Progress.Downloading(p.bytesReceived, if (p.totalBytes > 0) p.totalBytes else expectedSize))
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            return@withContext Outcome.Cancelled
        } catch (e: Exception) {
            // Don't delete the partial file — resume on next attempt.
            Log.w(TAG, "Download interrupted: ${e.message}")
            return@withContext Outcome.Failed("Download interrupted: ${e.message ?: e.javaClass.simpleName}", e)
        }

        // -- Verify ----------------------------------------------------------------
        coroutineContext.ensureActive()
        onProgress(Progress.Verifying)

        val actualSize = destination.length()
        if (actualSize != expectedSize) {
            destination.delete()
            return@withContext Outcome.Failed("Size mismatch (got $actualSize, expected $expectedSize)")
        }

        val actualSha = computeSha256(destination)
        if (!actualSha.equals(expectedSha, ignoreCase = true)) {
            destination.delete()
            return@withContext Outcome.Failed("SHA-256 mismatch (got $actualSha, expected $expectedSha)")
        }

        Log.i(TAG, "Download + verify succeeded: ${destination.absolutePath}")
        Outcome.Success
    }

    /** Delete any partial file. Use on explicit user cancel (not on transient failure). */
    fun deletePartial() {
        val f = TranslateGemmaModel.file(context)
        if (f.exists()) {
            val ok = f.delete()
            Log.i(TAG, "Deleted partial file: ${f.absolutePath} ok=$ok")
        }
    }

    // -- Pre-flight helpers --------------------------------------------------------

    private fun preflightRam(): String? {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        if (mi.totalMem < TOTAL_MEM_FLOOR_BYTES) {
            return "Insufficient RAM (${mi.totalMem / 1_000_000_000} GB total, need 6 GB)"
        }
        return null
    }

    private fun preflightStorage(expectedSize: Long): String? {
        val dir = context.noBackupFilesDir
        val sf = StatFs(dir.absolutePath)
        val free = sf.availableBytes
        // 5% headroom for filesystem overhead and existing partial files.
        val needed = (expectedSize * 105 / 100).coerceAtLeast(expectedSize + 100_000_000L)
        if (free < needed) {
            return "Need ${humanSize(needed)} free, only ${humanSize(free)} available"
        }
        return null
    }

    /** Returns true if the active default network is metered (cellular, hotspot, etc). */
    fun isCurrentNetworkMetered(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val active = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(active) ?: return false
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private suspend fun computeSha256(file: File): String = withContext(Dispatchers.IO) {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                coroutineContext.ensureActive()
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "TranslateGemmaDownloader"
        // Permanent device gate. Below 6 GB total RAM, the model would run too close
        // to the OOM-killer threshold. Note: separate from the *availMem* gate at
        // load-time which surfaces transient pressure to fall through to ML Kit.
        private const val TOTAL_MEM_FLOOR_BYTES = 6_000_000_000L
    }
}
