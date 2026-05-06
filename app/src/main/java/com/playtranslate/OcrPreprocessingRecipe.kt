package com.playtranslate

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint

/**
 * Whether OCR preprocessing should invert luminance for light-on-dark text.
 * [AUTO] consults [OcrManager.sampleIsDarkBackground] (production behavior);
 * [ALWAYS]/[NEVER] pin the choice for evaluation runs.
 */
enum class InvertMode { NEVER, ALWAYS, AUTO }

/**
 * A preprocessing strategy applied to the bitmap before it reaches ML Kit.
 *
 * Implementations return a NEW bitmap; [OcrManager.recognise] recycles it
 * after recognition completes. The [isDarkBackground] argument is the result
 * of the auto-invert sampler at capture time — recipes with [InvertMode.AUTO]
 * consult it, [InvertMode.ALWAYS]/[NEVER] ignore it.
 *
 * Production uses [Default]; see its kdoc for the current strategy.
 */
sealed interface OcrPreprocessingRecipe {
    fun apply(bitmap: Bitmap, isDarkBackground: Boolean): Bitmap

    /**
     * Production preprocessing: scale + grayscale + sigmoid (k=7) + auto-invert.
     *
     * Switched from the previous `LinearContrast(2.0f)` after the golden-set
     * harness measured sigmoid k=7 producing ~40% fewer translation-affecting
     * failures (Japanese-script substitutions + drops) across 16 screens —
     * lin2.0's hard-clamping at 0/255 was destroying anti-aliased edge
     * gradients on screens where ML Kit needed them, while sigmoid k=7's
     * smooth midpoint transition preserved them.
     */
    object Default : OcrPreprocessingRecipe {
        private val delegate = SigmoidContrast(7f, InvertMode.AUTO)
        override fun apply(bitmap: Bitmap, isDarkBackground: Boolean): Bitmap =
            delegate.apply(bitmap, isDarkBackground)
    }

    /** Scale + grayscale only — baseline with no contrast and no invert. */
    object Raw : OcrPreprocessingRecipe {
        override fun apply(bitmap: Bitmap, isDarkBackground: Boolean): Bitmap {
            val (scale, w, h) = upscaleParams(bitmap)
            val gray = ColorMatrix().apply { setSaturation(0f) }
            return renderWithMatrix(bitmap, scale, w, h, gray)
        }
    }

    /**
     * Scale + grayscale + linear contrast (`out = in * factor + translate` with
     * hard clamp to 0..255) + optional invert. Was the production recipe at
     * `factor=2.0` until sigmoid k=7 measured better on the golden set;
     * retained for benchmarking and as a fallback.
     */
    data class LinearContrast(
        val factor: Float,
        val invert: InvertMode = InvertMode.AUTO
    ) : OcrPreprocessingRecipe {
        override fun apply(bitmap: Bitmap, isDarkBackground: Boolean): Bitmap {
            val (scale, w, h) = upscaleParams(bitmap)
            val matrix = ColorMatrix().apply {
                setSaturation(0f)
                postConcat(buildLinearContrastMatrix(factor))
                if (shouldInvert(invert, isDarkBackground)) postConcat(INVERT_MATRIX)
            }
            return renderWithMatrix(bitmap, scale, w, h, matrix)
        }
    }

    /**
     * Scale + grayscale + sigmoid contrast (logistic curve, no hard clamp at
     * 0/255) + optional invert. Higher [k] → more aggressive midtone separation
     * (k≈10 is nearly binary but with smooth transition through 0.5).
     *
     * Sigmoid avoids the edge-gradient destruction that linear contrast at
     * factor 2.0 causes — see the `相`/`との相性` regression that drove this
     * abstraction's introduction.
     */
    data class SigmoidContrast(
        val k: Float,
        val invert: InvertMode = InvertMode.AUTO
    ) : OcrPreprocessingRecipe {
        override fun apply(bitmap: Bitmap, isDarkBackground: Boolean): Bitmap {
            val (scale, w, h) = upscaleParams(bitmap)
            val gray = ColorMatrix().apply { setSaturation(0f) }
            val out = renderWithMatrix(bitmap, scale, w, h, gray)
            val baseLut = buildSigmoidLut(k)
            val lut = if (shouldInvert(invert, isDarkBackground)) {
                IntArray(256) { 255 - baseLut[it] }
            } else baseLut
            applyGrayLut(out, lut)
            return out
        }
    }
}

// ── Shared helpers (also reusable from instrumented tests) ──────────────────

/** Minimum pixel count on the shorter side before upscaling stops. Matches the
 *  pre-refactor `OcrManager.TARGET_MIN_DIM`. */
internal const val OCR_TARGET_MIN_DIM = 1200

/** Total output size cap. Prevents OOM on narrow crops. */
private const val OCR_MAX_DIM = 3000

private val INVERT_MATRIX = ColorMatrix(floatArrayOf(
    -1f, 0f, 0f, 0f, 255f,
    0f, -1f, 0f, 0f, 255f,
    0f, 0f, -1f, 0f, 255f,
    0f, 0f, 0f, 1f, 0f
))

internal fun shouldInvert(mode: InvertMode, isDarkBackground: Boolean): Boolean =
    when (mode) {
        InvertMode.ALWAYS -> true
        InvertMode.NEVER -> false
        InvertMode.AUTO -> isDarkBackground
    }

/** Returns (scaleFactor, outWidth, outHeight) using the same logic the original
 *  `prepareForOcr` used. Upscale small crops to a 1200-px short side, capped
 *  at 3000 total or 3× source. */
internal fun upscaleParams(bitmap: Bitmap): Triple<Float, Int, Int> {
    val minDim = minOf(bitmap.width, bitmap.height)
    var scale = if (minDim < OCR_TARGET_MIN_DIM)
        (OCR_TARGET_MIN_DIM.toFloat() / minDim).coerceAtMost(3f)
    else 1f
    if (bitmap.width * scale > OCR_MAX_DIM || bitmap.height * scale > OCR_MAX_DIM) {
        scale = minOf(OCR_MAX_DIM.toFloat() / bitmap.width, OCR_MAX_DIM.toFloat() / bitmap.height)
    }
    return Triple(scale, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt())
}

internal fun renderWithMatrix(
    bitmap: Bitmap,
    scale: Float,
    outW: Int,
    outH: Int,
    matrix: ColorMatrix?
): Bitmap {
    val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
    val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        if (matrix != null) colorFilter = ColorMatrixColorFilter(matrix)
    }
    val canvas = Canvas(out)
    if (scale != 1f) canvas.scale(scale, scale)
    canvas.drawBitmap(bitmap, 0f, 0f, paint)
    return out
}

internal fun buildLinearContrastMatrix(factor: Float): ColorMatrix {
    val translate = (1f - factor) / 2f * 255f
    return ColorMatrix(floatArrayOf(
        factor, 0f, 0f, 0f, translate,
        0f, factor, 0f, 0f, translate,
        0f, 0f, factor, 0f, translate,
        0f, 0f, 0f, 1f, 0f
    ))
}

/** Builds a 256-entry LUT for a normalized sigmoid contrast curve with steepness [k].
 *  Output is renormalized so input range 0..255 spans output 0..255 exactly. */
internal fun buildSigmoidLut(k: Float): IntArray {
    val s0 = 1.0 / (1.0 + Math.exp(k * 0.5))
    val s1 = 1.0 / (1.0 + Math.exp(-k * 0.5))
    val range = s1 - s0
    return IntArray(256) { i ->
        val x = i / 255.0
        val s = 1.0 / (1.0 + Math.exp(-k * (x - 0.5)))
        ((s - s0) / range * 255.0).toInt().coerceIn(0, 255)
    }
}

/** Applies [lut] to every pixel of an already-grayscale [bitmap] in place. */
internal fun applyGrayLut(bitmap: Bitmap, lut: IntArray) {
    val w = bitmap.width
    val h = bitmap.height
    val pixels = IntArray(w * h)
    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
    for (i in pixels.indices) {
        val p = pixels[i]
        val a = (p ushr 24) and 0xff
        val v = lut[(p ushr 16) and 0xff]
        pixels[i] = (a shl 24) or (v shl 16) or (v shl 8) or v
    }
    bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
}
