package com.playtranslate.translationlog

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One JPEG per capture session, keyed by session id, beside the History DB
 * under [Context.noBackupFilesDir] — durable (unlike cacheDir, which the OS
 * may purge under it), out of Google Backup like the DB itself.
 *
 * Lifecycle contract: rows are the source of truth, and every row-removal
 * path in [TranslationHistoryStore] (per-row delete, FIFO prune, clear)
 * reclaims the now-orphaned images EAGERLY as part of the same operation —
 * so a deleted capture's pixels never outlive its row. [sweep] is only a
 * backstop for crash/race orphans (process death mid-copy, an image that
 * landed after its session's rows were already gone). A row whose image
 * never landed simply renders without a thumbnail. Writes are
 * temp-then-rename so a crash never installs a half-file.
 */
object HistoryImageStore {

    /** Longest-side cap, matching the import pipeline's decode bound
     *  ([com.playtranslate.imageimport.UprightImageDecoder.MAX_DIMENSION_PX]):
     *  re-open feeds these files back through that pipeline, so anything
     *  larger would be resampled away on read anyway. */
    private const val CAP_PX = 2560
    private const val JPEG_QUALITY = 88

    sealed interface Source {
        /** A JPEG our capture pipeline already wrote (screen paths). */
        data class FromPath(val path: String) : Source

        /** A live bitmap the caller owns and keeps unrecycled (camera's
         *  frozen keyframe). Never recycled here. */
        data class FromBitmap(val bitmap: Bitmap) : Source
    }

    private val dispatcher =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "HistoryImageStore").apply { isDaemon = true }
        }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    /** Bumped when an image lands on disk. Row inserts and the image save
     *  are independent async writes; the History screen collects this so a
     *  capture taken while History is visible gains its thumbnail/reopen
     *  behavior the moment the JPEG appears, not on the next unrelated
     *  reload. StateFlow conflates bursts. */
    private val _revision = kotlinx.coroutines.flow.MutableStateFlow(0L)
    val revision: kotlinx.coroutines.flow.StateFlow<Long> get() = _revision

    fun imageDir(ctx: Context): File =
        File(File(ctx.applicationContext.noBackupFilesDir, "translationlog"), "images")

    fun fileFor(ctx: Context, sessionId: String): File =
        File(imageDir(ctx), safeName(sessionId) + ".jpg")

    /** Fire-and-forget from any thread; all I/O on the store's own thread.
     *  Idempotent per session — the first landed image wins. */
    fun save(ctx: Context, sessionId: String, src: Source) {
        val appCtx = ctx.applicationContext
        scope.launch {
            try {
                val final = fileFor(appCtx, sessionId)
                if (final.exists()) return@launch
                final.parentFile?.mkdirs()
                val tmp = File(final.path + TMP_SUFFIX)
                val ok = when (src) {
                    is Source.FromPath -> writeFromPath(src.path, tmp)
                    is Source.FromBitmap -> writeFromBitmap(src.bitmap, tmp)
                }
                if (ok && tmp.renameTo(final)) {
                    _revision.value++
                } else {
                    tmp.delete()
                    Log.w(TAG, "image save failed for $sessionId")
                }
            } catch (e: Exception) {
                Log.w(TAG, "image save failed for $sessionId: ${e.message}")
            }
        }
    }

    /** Already at or under the cap (the usual case for screen frames) →
     *  lossless byte copy; oversized → sampled decode + re-encode. */
    private fun writeFromPath(path: String, tmp: File): Boolean {
        val srcFile = File(path)
        if (!srcFile.exists()) return false
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
        if (maxOf(bounds.outWidth, bounds.outHeight) <= CAP_PX) {
            srcFile.copyTo(tmp, overwrite = true)
            return true
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
        }
        val bmp = BitmapFactory.decodeFile(path, opts) ?: return false
        return try {
            compressTo(bmp, tmp)
        } finally {
            bmp.recycle()
        }
    }

    private fun writeFromBitmap(bitmap: Bitmap, tmp: File): Boolean {
        if (bitmap.isRecycled) return false
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= CAP_PX) return compressTo(bitmap, tmp)
        val scale = CAP_PX.toFloat() / longest
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true,
        )
        return try {
            compressTo(scaled, tmp)
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    private fun compressTo(bmp: Bitmap, tmp: File): Boolean =
        tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }

    private fun sampleSizeFor(w: Int, h: Int): Int {
        val longest = maxOf(w, h)
        var sample = 1
        while (longest / sample > CAP_PX) sample *= 2
        return sample
    }

    /** Delete images whose session has no surviving rows, plus any
     *  crash-orphaned temp files. [liveSessionIds] is
     *  [TranslationHistoryStore.distinctSessionIds]'s result. Files younger
     *  than the grace window are kept unconditionally: a capture in flight
     *  can land its image after the session query but before this runs, and
     *  a fresh image must never lose that race. */
    suspend fun sweep(ctx: Context, liveSessionIds: Set<String>): Unit = withContext(dispatcher) {
        val keep = liveSessionIds.mapTo(HashSet()) { safeName(it) + ".jpg" }
        val cutoff = System.currentTimeMillis() - SWEEP_GRACE_MS
        imageDir(ctx).listFiles()?.forEach { f ->
            if (f.lastModified() >= cutoff) return@forEach
            if (f.name.endsWith(TMP_SUFFIX) || f.name !in keep) f.delete()
        }
    }

    /** Fire-and-forget orphan reconcile (app start, History screen open):
     *  queries the store's surviving session ids itself. */
    fun sweepAsync(ctx: Context) {
        val appCtx = ctx.applicationContext
        scope.launch {
            runCatching {
                sweep(appCtx, TranslationHistoryStore.distinctSessionIds(appCtx))
            }.onFailure { Log.w(TAG, "sweep failed: ${it.message}") }
        }
    }

    /** Reclaim one session's image (and any half-written temp) the moment
     *  its last row is removed. On the store thread so it can't race a
     *  concurrent save of the same file. */
    suspend fun deleteSession(ctx: Context, sessionId: String): Unit = withContext(dispatcher) {
        val f = fileFor(ctx, sessionId)
        f.delete()
        File(f.path + TMP_SUFFIX).delete()
    }

    /** Clear-all integration: rows are gone, so every image is an orphan. */
    suspend fun clearAll(ctx: Context): Unit = withContext(dispatcher) {
        imageDir(ctx).listFiles()?.forEach { it.delete() }
    }

    /** Session ids are file keys; "cap:{uuid}" must survive the
     *  filesystem, so anything outside [A-Za-z0-9_-] flattens to '_'.
     *  UUIDs keep the mapping collision-free in practice. */
    private fun safeName(sessionId: String): String =
        sessionId.map { if (it.isLetterOrDigit() || it == '_' || it == '-') it else '_' }
            .joinToString("")

    private const val TMP_SUFFIX = ".tmp"
    private const val SWEEP_GRACE_MS = 60_000L
    private const val TAG = "HistoryImageStore"
}
