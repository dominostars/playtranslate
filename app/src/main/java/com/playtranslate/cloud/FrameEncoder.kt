package com.playtranslate.cloud

import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * The image format a frame is uploaded in: what [FrameEncoder.encode]
 * compresses to, and the `frame` part's media type and filename
 * ([PtCloudClient]). [wireName] is what
 * [com.playtranslate.Prefs.ptCloudUploadFormat] stores and what the clock
 * line prints.
 *
 * JPEG at quality 85 is the server's recommended upload (docs/api.md,
 * "Request"): Android's encoder writes baseline 4:2:0, which the server
 * measured as no less accurate than WebP q90 while encoding many times
 * faster. WebP q90 stays selectable so the two can be compared from the
 * device; an origin older than JPEG uploads refuses a JPEG with 415.
 */
enum class FrameFormat(val mediaType: String, val quality: Int, val filename: String, val wireName: String) {
    JPEG("image/jpeg", 85, "frame.jpg", "jpeg"),
    WEBP("image/webp", 90, "frame.webp", "webp");

    companion object {
        /** The format stored as [name], or null for a name this build does
         *  not know. */
        fun fromWireName(name: String): FrameFormat? = entries.firstOrNull { it.wireName == name }
    }
}

/**
 * One frame ready to upload. [scale] is uploaded pixels per bitmap pixel
 * (1 when the bitmap was sent at its own size); the server's boxes are in
 * uploaded pixels, and [PtCloudResponseMapper.toOcrResult] divides them by
 * it. [width] and [height] are the uploaded size. [encodeMs] is how long the
 * scale and the compress took, for the clock line.
 */
class EncodedFrame(
    val bytes: ByteArray,
    val format: FrameFormat,
    val scale: Float,
    val width: Int,
    val height: Int,
    val encodeMs: Long,
) {
    /** The `frame` part's media type, the [format]'s. */
    val mediaType: String get() = format.mediaType
}

/**
 * Encodes a captured bitmap for `POST /v1/frame`: scaled down so its long
 * side is at most the configured cap ([PtCloudConfig.uploadLongSideCap], read
 * per capture by the caller), then compressed in the [FrameFormat] the caller
 * passes ([PtCloudConfig.uploadFormat], also read per capture) at that
 * format's quality. The size of the result is the caller's to log; the
 * server refuses a frame over 2 MiB with 413, and that cap is server
 * configuration, not checked here.
 */
object FrameEncoder {

    /** The upload size for a [w] x [h] bitmap under [cap]: unchanged when
     *  the long side fits, else the long side becomes [cap] and the short
     *  side keeps the aspect ratio (rounded, at least 1). */
    fun scaledSize(w: Int, h: Int, cap: Int): Pair<Int, Int> {
        val long = maxOf(w, h)
        if (long <= cap) return w to h
        fun shrink(side: Int) = ((side.toLong() * cap + long / 2) / long).toInt().coerceAtLeast(1)
        return if (w >= h) cap to shrink(h) else shrink(w) to cap
    }

    /**
     * [bitmap] as an [EncodedFrame] in [format], on [Dispatchers.Default].
     * The caller keeps [bitmap] alive until this returns and still owns it
     * after: only the scaled copy made here is recycled. [EncodedFrame.encodeMs]
     * covers the scale and the compress, not the hop onto the dispatcher.
     */
    suspend fun encode(bitmap: Bitmap, longSideCap: Int, format: FrameFormat): EncodedFrame =
        withContext(Dispatchers.Default) {
            val startMs = SystemClock.elapsedRealtime()
            val w = bitmap.width
            val h = bitmap.height
            val (sw, sh) = scaledSize(w, h, longSideCap)
            val scaled = if (sw == w && sh == h) bitmap else Bitmap.createScaledBitmap(bitmap, sw, sh, true)
            val bytes = try {
                ByteArrayOutputStream().use { out ->
                    scaled.compress(format.compressFormat(), format.quality, out)
                    out.toByteArray()
                }
            } finally {
                if (scaled !== bitmap) scaled.recycle()
            }
            EncodedFrame(
                bytes, format, scale = sw.toFloat() / w, width = sw, height = sh,
                encodeMs = SystemClock.elapsedRealtime() - startMs,
            )
        }

    private fun FrameFormat.compressFormat(): Bitmap.CompressFormat = when (this) {
        FrameFormat.JPEG -> Bitmap.CompressFormat.JPEG
        FrameFormat.WEBP -> webpLossy()
    }

    /** Lossy WebP: its own constant from API 30; on API 29 the old `WEBP`
     *  constant, which is lossy at any quality below 100. */
    private fun webpLossy(): Bitmap.CompressFormat =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY
        else Bitmap.CompressFormat.WEBP
}
