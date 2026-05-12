package com.playtranslate

import android.graphics.Bitmap
import android.graphics.ColorMatrix

/**
 * OCR preprocessing tuned for Latin-script languages with diacritics.
 *
 * Compared to [OcrPreprocessingRecipe.Default] (sigmoid k=7, 1200-px upscale,
 * no sharpen), this recipe:
 *
 *  * Upscales to a 1400-px short side (cap 4× / 3500 total) — more pixels per
 *    tone mark for the Latin recognizer to discriminate e.g. dấu hỏi / dấu ngã.
 *  * Uses a gentler sigmoid (k=4) so anti-aliased gradients on thin accent
 *    strokes survive instead of being pushed toward 0/255.
 *  * Applies a soft 3×3 unsharp-mask sharpen after contrast to keep diacritic
 *    edges distinct from background noise.
 *
 * Routed only for Vietnamese, French, Spanish, Portuguese, Turkish, Romanian
 * and Indonesian — see [selectOcrRecipe]. English is intentionally excluded:
 * normal English text has no diacritics, and the sharpen pass can overshoot
 * on pixel-art game fonts.
 *
 * This recipe has not yet been measured against a Latin-screen golden set —
 * the rationale above is mechanism-only. Treat accuracy claims as predictions
 * until the harness gains Latin seeds.
 */
object DiacriticsOcrPreprocessing : OcrPreprocessingRecipe {

    private const val TARGET_MIN_DIM = 1400
    private const val MAX_DIM = 3500
    private const val MAX_UPSCALE = 4f
    private const val SIGMOID_K = 4f

    override fun apply(bitmap: Bitmap, isDarkBackground: Boolean): Bitmap {
        val (scale, w, h) = scaledDims(bitmap)
        val gray = ColorMatrix().apply { setSaturation(0f) }
        val out = renderWithMatrix(bitmap, scale, w, h, gray)

        val baseLut = buildSigmoidLut(SIGMOID_K)
        val lut = if (shouldInvert(InvertMode.AUTO, isDarkBackground)) {
            IntArray(256) { 255 - baseLut[it] }
        } else baseLut
        applyGrayLut(out, lut)

        sharpenGrayscaleInPlace(out)
        return out
    }

    private fun scaledDims(bitmap: Bitmap): Triple<Float, Int, Int> {
        val minDim = minOf(bitmap.width, bitmap.height)
        var scale = if (minDim < TARGET_MIN_DIM)
            (TARGET_MIN_DIM.toFloat() / minDim).coerceAtMost(MAX_UPSCALE)
        else 1f
        if (bitmap.width * scale > MAX_DIM || bitmap.height * scale > MAX_DIM) {
            scale = minOf(
                MAX_DIM.toFloat() / bitmap.width,
                MAX_DIM.toFloat() / bitmap.height,
            )
        }
        return Triple(scale, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt())
    }

    /**
     * 3×3 soft unsharp-mask convolution applied in place. Kernel:
     *
     * ```
     *   0   -0.5   0
     *  -0.5  3   -0.5
     *   0   -0.5   0
     * ```
     *
     * Equivalent to `v = (6·centre − ∑neighbours) / 2`. Half the strength of
     * a textbook 5-centre unsharp mask so thin Latin strokes get edge boost
     * without halo overshoot. Border rows/columns pass through unchanged.
     *
     * Memory: uses a 3-row sliding window so peak heap is the bitmap itself
     * plus ~4·w·4 bytes of scratch, instead of a second full-frame copy. Safe
     * to mutate in place because we read row y+1 into the buffer before
     * writing the convolved row y back to the bitmap.
     */
    private fun sharpenGrayscaleInPlace(bitmap: Bitmap) {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 3 || h < 3) return

        val rows = arrayOf(IntArray(w), IntArray(w), IntArray(w))
        val output = IntArray(w)
        bitmap.getPixels(rows[0], 0, w, 0, 0, w, 1)
        bitmap.getPixels(rows[1], 0, w, 0, 1, w, 1)

        for (y in 1 until h - 1) {
            val above = rows[(y - 1) % 3]
            val center = rows[y % 3]
            val below = rows[(y + 1) % 3]
            bitmap.getPixels(below, 0, w, 0, y + 1, w, 1)

            output[0] = center[0]
            for (x in 1 until w - 1) {
                val c = center[x] and 0xff
                val t = above[x] and 0xff
                val b = below[x] and 0xff
                val l = center[x - 1] and 0xff
                val r = center[x + 1] and 0xff
                val v = ((6 * c - t - b - l - r) / 2).coerceIn(0, 255)
                output[x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
            output[w - 1] = center[w - 1]
            bitmap.setPixels(output, 0, w, 0, y, w, 1)
        }
    }
}

/** ISO 639-1 codes whose typical text contains diacritics worth preserving via
 *  [DiacriticsOcrPreprocessing]. English is intentionally excluded — see the
 *  recipe's kdoc. */
private val DIACRITIC_LANGS = setOf("vi", "fr", "es", "pt", "tr", "ro", "id")

/** Selects the preprocessing recipe for a given source language code.
 *  Defaults to [OcrPreprocessingRecipe.Default] (sigmoid k=7) for anything
 *  outside [DIACRITIC_LANGS]. */
fun selectOcrRecipe(sourceLang: String): OcrPreprocessingRecipe =
    if (sourceLang in DIACRITIC_LANGS) DiacriticsOcrPreprocessing
    else OcrPreprocessingRecipe.Default
