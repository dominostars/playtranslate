package com.playtranslate.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * The house frosted-glass backdrop: the captured screenshot blurred ONCE
 * (a cheap downscale + separable box blur), drawn at full-screen scale under
 * a translucent ptBg wash and clipped to the surface's rounded outline.
 * Static — no live re-blur. Extracted from the capture sheet so the floating
 * workspace shares one set of dials; both surfaces draw the result
 * themselves (the sheet under a moving panel, the workspace under its card).
 */
internal object FrostBackdrop {
    /** Downscale before the box blur (cheapness; the blur does the smoothing
     *  now, so this no longer needs to be aggressive). */
    const val DOWNSCALE = 6

    /** Box-blur radius (in downscaled px) — the main blur dial. */
    const val BLUR_RADIUS = 5

    /** Surface-fill opacity over the frost (255 = opaque). Lower → more of
     *  the frosted backdrop shows through the tint. The capture sheet's
     *  dial: a glanceable result panel wears the frost openly. */
    const val WASH_ALPHA = 205

    /** The workspace card's dial — deliberately firmer than the sheet's:
     *  it hosts dense reading/editing pages (definitions, the Anki editors),
     *  where the same translucency read as too see-through. The frost stays
     *  visible but recedes to a tint. */
    const val WORKSPACE_WASH_ALPHA = 232

    /** The blur: downscale for cheapness, then a separable box blur (3 passes ≈
     *  Gaussian) over the small bitmap so it reads as a smooth frost instead of
     *  visible low-res pixels — a plain downscale+upscale aliases (the grid shows
     *  through). Small bitmap → sub-millisecond. Reads [src] synchronously so the
     *  caller can recycle it right after. */
    fun blur(src: Bitmap): Bitmap? {
        if (src.isRecycled || src.width <= 0 || src.height <= 0) return null
        val w = (src.width / DOWNSCALE).coerceAtLeast(1)
        val h = (src.height / DOWNSCALE).coerceAtLeast(1)
        val small = try {
            Bitmap.createScaledBitmap(src, w, h, true)
        } catch (_: Exception) {
            return null
        }
        val blurred = boxBlur(small, BLUR_RADIUS, passes = 3)
        if (blurred !== small) small.recycle()
        return blurred
    }

    /** Path-based variant for a caller holding no decoded bitmap (the
     *  workspace): decodes SAMPLED down near the frost's own resolution — the
     *  full screenshot never inflates — then rescales to exactly
     *  screen/[DOWNSCALE] so the blur radius means the same thing it does on
     *  the sheet's full-bitmap path. Call off the main thread. Null on any
     *  decode failure — the caller keeps its flat fill. */
    fun decodeAndBlur(path: String, screenW: Int, screenH: Int): Bitmap? {
        if (screenW <= 0 || screenH <= 0) return null
        val targetW = (screenW / DOWNSCALE).coerceAtLeast(1)
        val targetH = (screenH / DOWNSCALE).coerceAtLeast(1)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        // Largest power-of-2 sample that still decodes at or above the frost
        // resolution — typically 4 for a same-screen screenshot.
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetW &&
            bounds.outHeight / (sample * 2) >= targetH
        ) {
            sample *= 2
        }
        val decoded = BitmapFactory.decodeFile(
            path,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null
        val small = try {
            Bitmap.createScaledBitmap(decoded, targetW, targetH, true)
        } catch (_: Exception) {
            decoded.recycle()
            return null
        }
        if (small !== decoded) decoded.recycle()
        val blurred = boxBlur(small, BLUR_RADIUS, passes = 3)
        if (blurred !== small) small.recycle()
        return blurred
    }

    /** Separable box blur over a small ARGB bitmap, [passes] times (3 ≈ Gaussian). */
    private fun boxBlur(src: Bitmap, radius: Int, passes: Int): Bitmap {
        val w = src.width
        val h = src.height
        if (radius < 1 || w < 2 || h < 2) return src
        val a = IntArray(w * h)
        val b = IntArray(w * h)
        src.getPixels(a, 0, w, 0, 0, w, h)
        repeat(passes) {
            boxBlurAxis(a, b, w, h, radius, horizontal = true)
            boxBlurAxis(b, a, w, h, radius, horizontal = false)
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setPixels(a, 0, w, 0, 0, w, h)
        }
    }

    /** One running-window box-blur pass along one axis (edges clamp). */
    private fun boxBlurAxis(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val len = if (horizontal) w else h
        val step = if (horizontal) 1 else w
        val div = 2 * r + 1
        for (line in 0 until lines) {
            val base = if (horizontal) line * w else line
            var sa = 0; var sr = 0; var sg = 0; var sb = 0
            for (i in -r..r) {
                val c = src[base + i.coerceIn(0, len - 1) * step]
                sa += (c ushr 24) and 0xff; sr += (c ushr 16) and 0xff
                sg += (c ushr 8) and 0xff; sb += c and 0xff
            }
            for (j in 0 until len) {
                dst[base + j * step] =
                    ((sa / div) shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val co = src[base + (j - r).coerceIn(0, len - 1) * step]
                val ci = src[base + (j + r + 1).coerceIn(0, len - 1) * step]
                sa += ((ci ushr 24) and 0xff) - ((co ushr 24) and 0xff)
                sr += ((ci ushr 16) and 0xff) - ((co ushr 16) and 0xff)
                sg += ((ci ushr 8) and 0xff) - ((co ushr 8) and 0xff)
                sb += (ci and 0xff) - (co and 0xff)
            }
        }
    }
}
