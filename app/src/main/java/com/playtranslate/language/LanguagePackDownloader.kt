package com.playtranslate.language

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * Progress phases emitted by [LanguagePackStore.install].
 *
 * [Downloading] is emitted continuously as bytes arrive. [Verifying] and
 * [Extracting] are emitted once each, when the post-download validation and
 * unzip steps start. UI consumers typically show a determinate progress bar
 * for [Downloading] and an indeterminate spinner for the other two (they're
 * fast; the zip is already downloaded).
 */
sealed interface DownloadProgress {
    data class Downloading(val bytesReceived: Long, val totalBytes: Long) : DownloadProgress
    data object Verifying : DownloadProgress
    data object Extracting : DownloadProgress
}

/**
 * Terminal result from [LanguagePackStore.install]. [Failed.reason] is a
 * human-readable string suitable for logging and UI display; [Failed.cause]
 * is the underlying exception if any (null for logic failures like a SHA
 * mismatch).
 */
sealed interface InstallResult {
    data object Success : InstallResult
    data object Cancelled : InstallResult
    data class Failed(val reason: String, val cause: Throwable? = null) : InstallResult
}

/**
 * HTTP download of a language pack zip with byte-progress reporting.
 *
 * Uses a fresh [OkHttpClient] per downloader instance (matching the pattern
 * in the translation backends — no shared client singleton exists in the
 * codebase). Timeouts are tuned for a 10-20 MB CDN-backed download:
 * 15s connect, 60s read.
 *
 * **Resumable downloads:** If [destination] already exists with a non-zero
 * size on entry, the downloader sends `Range: bytes=<existing>-` to resume
 * from that offset. This is essential for the 2.49 GB TranslateGemma model
 * (where every connection blip would otherwise restart from byte 0). Servers
 * that don't honor `Range` (200 OK) cause the partial file to be truncated
 * and re-downloaded from scratch.
 *
 * The contract is intentionally pinned-URL-friendly: we do NOT send
 * `If-Range`, on the assumption that the [url] points at byte-stable content
 * (HuggingFace `resolve/main/<file>` URLs are git-LFS-hashed and immutable;
 * GitHub release-asset URLs are immutable per release tag). Callers that
 * point at mutable URLs should NOT rely on resume — pass a fresh empty file
 * or always delete before retry.
 */
class LanguagePackDownloader(
    private val httpClient: OkHttpClient = defaultClient(),
) {

    /**
     * Streams [url] into [destination], calling [onProgress] for every chunk.
     * Resumes from `destination.length()` if the file already exists and the
     * server returns 206 Partial Content. Throws on HTTP error or transport
     * failure; the caller is responsible for translating exceptions into
     * [InstallResult.Failed].
     */
    suspend fun download(
        url: String,
        destination: File,
        onProgress: (DownloadProgress.Downloading) -> Unit,
    ) = withContext(Dispatchers.IO) {
        destination.parentFile?.mkdirs()

        val resumeFrom = if (destination.exists()) destination.length() else 0L
        val builder = Request.Builder().url(url)
        if (resumeFrom > 0) {
            builder.header("Range", "bytes=$resumeFrom-")
        }
        val call = httpClient.newCall(builder.build())

        // Cancel the HTTP call if the coroutine is cancelled
        val job = coroutineContext[kotlinx.coroutines.Job]
        job?.invokeOnCompletion { if (it != null) call.cancel() }

        call.execute().use { response ->
            when (response.code) {
                200 -> {
                    // Server ignored Range (or we sent none). Truncate any partial file
                    // and start over from byte 0.
                    val body = response.body
                    val total = body.contentLength().coerceAtLeast(0L)
                    streamToFile(body, destination, append = false, startBytes = 0L, totalBytes = total, onProgress)
                }
                206 -> {
                    // Partial Content. Validate Content-Range and append.
                    val body = response.body
                    val cr = response.header("Content-Range")
                        ?: error("206 with no Content-Range for $url")
                    val total = parseContentRangeTotal(cr)
                        ?: (body.contentLength().coerceAtLeast(0L) + resumeFrom)
                    streamToFile(body, destination, append = true, startBytes = resumeFrom, totalBytes = total, onProgress)
                }
                416 -> {
                    // Range Not Satisfiable — usually means the partial file is already
                    // at-or-past the full content length. Trust the local file size and
                    // emit a final progress so the caller can verify with SHA-256.
                    val len = destination.length()
                    onProgress(DownloadProgress.Downloading(len, len))
                }
                else -> error("HTTP ${response.code} for $url")
            }
        }
    }

    private suspend fun streamToFile(
        body: okhttp3.ResponseBody,
        destination: File,
        append: Boolean,
        startBytes: Long,
        totalBytes: Long,
        onProgress: (DownloadProgress.Downloading) -> Unit,
    ) {
        var received = startBytes
        body.byteStream().use { input ->
            java.io.FileOutputStream(destination, append).buffered().use { output ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    coroutineContext.ensureActive()
                    val n = input.read(buf)
                    if (n <= 0) break
                    output.write(buf, 0, n)
                    received += n
                    onProgress(DownloadProgress.Downloading(received, totalBytes))
                }
            }
        }
    }

    /** Parse the total-size component of a `Content-Range: bytes 100-199/2500` header.
     *  Returns null for `*` (unknown) or malformed input. */
    private fun parseContentRangeTotal(header: String): Long? {
        // "bytes 100-199/2500" or "bytes 100-199/*"
        val slash = header.lastIndexOf('/')
        if (slash < 0 || slash == header.length - 1) return null
        val totalStr = header.substring(slash + 1).trim()
        return totalStr.toLongOrNull()  // "*" → null, which is fine
    }

    companion object {
        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            // Prefer IPv4 over IPv6. Many consumer networks advertise a
            // routable IPv6 address but can't actually reach AWS CloudFront
            // (where huggingface.co + the Xet LFS CDN live) over v6 — the
            // SYN goes out, no reply, the socket sits in SYN_SENT until
            // connectTimeout fires (15 s), and OkHttp's happy-eyeballs
            // fallback to v4 only kicks in after the v6 attempt's head-start
            // window. Observed on the AYN Thor + a residential router that
            // ping6 huggingface.co loses 100% of packets while ping over v4
            // is healthy. Returning v4 first sidesteps the 15-s-per-route
            // cycle: v4 wins happy eyeballs immediately.
            //
            // Doesn't disable v6 — falls back to it when no v4 is available,
            // so a v6-only network (or a v4-blocked corporate egress) still
            // works without code changes.
            .dns(Ipv4PreferredDns)
            .build()

        /**
         * [Dns] implementation that returns IPv4 addresses before IPv6 for
         * any hostname, leaning on [Dns.SYSTEM] for the actual resolution.
         * See [defaultClient] for the rationale.
         */
        private val Ipv4PreferredDns: Dns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                val all = Dns.SYSTEM.lookup(hostname)
                // sortedBy boolean: false (v4) comes before true (v6),
                // preserving the original order within each group.
                return all.sortedBy { it !is Inet4Address }
            }
        }
    }
}
