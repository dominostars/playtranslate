package com.playtranslate.cloud

import android.graphics.Bitmap
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * One frame ready to upload. [scale] is uploaded pixels per bitmap pixel
 * (1 when the bitmap was sent at its own size); the server's boxes are in
 * uploaded pixels, and [PtCloudResponseMapper.toOcrResult] divides them by
 * it. [width] and [height] are the uploaded size.
 */
class EncodedFrame(
    val bytes: ByteArray,
    val mediaType: String,
    val scale: Float,
    val width: Int,
    val height: Int,
)

/**
 * Encodes a captured bitmap for `POST /v1/frame`: scaled down so its long
 * side is at most the configured cap ([PtCloudConfig.uploadLongSideCap], read
 * per capture by the caller), then WebP lossy at quality 90, the format the
 * server's accuracy measurements were taken with. The size of the result is
 * the caller's to log; the server refuses a frame over 2 MiB with 413, and
 * that cap is server configuration, not checked here.
 */
object FrameEncoder {

    const val MEDIA_TYPE_WEBP = "image/webp"

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
     * [bitmap] as an [EncodedFrame], on [Dispatchers.Default]. The caller
     * keeps [bitmap] alive until this returns and still owns it after: only
     * the scaled copy made here is recycled.
     */
    suspend fun encode(bitmap: Bitmap, longSideCap: Int, quality: Int = 90): EncodedFrame =
        withContext(Dispatchers.Default) {
            val w = bitmap.width
            val h = bitmap.height
            val (sw, sh) = scaledSize(w, h, longSideCap)
            val scaled = if (sw == w && sh == h) bitmap else Bitmap.createScaledBitmap(bitmap, sw, sh, true)
            val bytes = try {
                ByteArrayOutputStream().use { out ->
                    scaled.compress(webpLossy(), quality, out)
                    out.toByteArray()
                }
            } finally {
                if (scaled !== bitmap) scaled.recycle()
            }
            EncodedFrame(bytes, MEDIA_TYPE_WEBP, scale = sw.toFloat() / w, width = sw, height = sh)
        }

    /** Lossy WebP: its own constant from API 30; on API 29 the old `WEBP`
     *  constant, which is lossy at any quality below 100. */
    private fun webpLossy(): Bitmap.CompressFormat =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY
        else Bitmap.CompressFormat.WEBP
}
