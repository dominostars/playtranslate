package com.playtranslate.cloud

import android.graphics.Bitmap
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * One frame ready to upload, its [bytes] a JPEG ([FrameEncoder]). [scale] is
 * uploaded pixels per bitmap pixel (1 when the bitmap was sent at its own
 * size); the server's boxes are in uploaded pixels, and
 * [PtCloudResponseMapper.toOcrResult] divides them by it. It is the LONG
 * side's ratio, which the scaling makes exact (the long side becomes the
 * cap, [FrameEncoder.scaledSize]); the short side is rounded to a whole
 * pixel, so its own ratio differs from this one by under half an uploaded
 * pixel at its far edge, within the truncation the mapper applies to every
 * box. The width's ratio, taken before, carried that rounding along the
 * long side of a portrait frame instead: twenty pixels at the bottom of a
 * 201 by 4000 import, whose width rounds from 96.48 to 96 (Codex review,
 * 2026-10-22). [width] and [height] are the uploaded size. [encodeMs] is
 * how long the scale and the compress took, for the clock line.
 */
class EncodedFrame(
    val bytes: ByteArray,
    val scale: Float,
    val width: Int,
    val height: Int,
    val encodeMs: Long,
)

/**
 * Encodes a captured bitmap for `POST /v1/frame`: scaled down so its long
 * side is at most the configured cap ([PtCloudConfig.uploadLongSideCap], read
 * per capture by the caller), then compressed as a JPEG at [QUALITY], which
 * [PtCloudClient] uploads as the `frame` part named [FILENAME] and typed
 * [MEDIA_TYPE]. The size of the result is the caller's to log; the server
 * refuses a frame over 2 MiB with 413, and that cap is server configuration,
 * not checked here.
 *
 * JPEG at quality 85 is the server's recommended upload (docs/api.md,
 * "Request"): Android's encoder writes baseline 4:2:0, which the server
 * measured as no less accurate than WebP q90 while encoding many times
 * faster.
 */
object FrameEncoder {

    const val MEDIA_TYPE = "image/jpeg"
    const val FILENAME = "frame.jpg"
    const val QUALITY = 85

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
     * the scaled copy made here is recycled. [EncodedFrame.encodeMs] covers
     * the scale and the compress, not the hop onto the dispatcher.
     */
    suspend fun encode(bitmap: Bitmap, longSideCap: Int): EncodedFrame =
        withContext(Dispatchers.Default) {
            val startMs = SystemClock.elapsedRealtime()
            val w = bitmap.width
            val h = bitmap.height
            val (sw, sh) = scaledSize(w, h, longSideCap)
            val scaled = if (sw == w && sh == h) bitmap else Bitmap.createScaledBitmap(bitmap, sw, sh, true)
            val bytes = try {
                ByteArrayOutputStream().use { out ->
                    scaled.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
                    out.toByteArray()
                }
            } finally {
                if (scaled !== bitmap) scaled.recycle()
            }
            EncodedFrame(
                // The long side's ratio: exact, where the short side rounds.
                bytes, scale = maxOf(sw, sh).toFloat() / maxOf(w, h), width = sw, height = sh,
                encodeMs = SystemClock.elapsedRealtime() - startMs,
            )
        }
}
