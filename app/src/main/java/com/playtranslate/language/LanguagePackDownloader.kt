package com.playtranslate.language

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

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
    data class Failed(val reason: String, val cause: Throwable? = null) : InstallResult
}

/**
 * HTTP download of a language pack zip with byte-progress reporting.
 *
 * Uses a fresh [OkHttpClient] per downloader instance (matching the pattern
 * in `DeepLTranslator` and `LingvaTranslator` — no shared client singleton
 * exists in the codebase). Timeouts are tuned for a 10-20 MB CDN-backed
 * download: 15s connect, 60s read.
 */
class LanguagePackDownloader(
    private val httpClient: OkHttpClient = defaultClient(),
) {

    /**
     * Streams [url] into [destination], calling [onProgress] for every chunk.
     * Throws on HTTP error or transport failure; the caller is responsible
     * for translating exceptions into [InstallResult.Failed].
     */
    suspend fun download(
        url: String,
        destination: File,
        onProgress: (DownloadProgress.Downloading) -> Unit,
    ) = withContext(Dispatchers.IO) {
        destination.parentFile?.mkdirs()
        val request = Request.Builder().url(url).build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("HTTP ${response.code} for $url")
            }
            val body = response.body ?: error("null response body for $url")
            val total = body.contentLength().coerceAtLeast(0L)
            var received = 0L
            body.byteStream().use { input ->
                destination.outputStream().buffered().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        received += n
                        onProgress(DownloadProgress.Downloading(received, total))
                    }
                }
            }
        }
    }

    companion object {
        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
