package com.playtranslate.ocr.paddle

import android.graphics.Bitmap
import android.util.Log
import com.playtranslate.BuildConfig
import com.playtranslate.ocr.core.DetectedRegion
import com.playtranslate.ocr.core.OcrCapabilities
import com.playtranslate.ocr.core.OcrImage
import com.playtranslate.ocr.core.OcrOrientationSupport
import com.playtranslate.ocr.core.RecognizedLine
import com.playtranslate.ocr.core.RecognizedRegion
import com.playtranslate.ocr.core.RegionOrigin
import com.playtranslate.ocr.core.TextRecognizer
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Point
import java.util.Locale

/**
 * [TextRecognizer] over PaddleOCR's CRNN/SVTR. Perspective-warps each
 * [DetectedRegion]'s quad (via [PaddleOcrSession.recognizeQuad], preserving
 * deskew) and recognizes it. Synthesizes per-character boxes from the CTC firing
 * positions ([synthesizeCharBoxes]) so drag-lookup + furigana place precisely
 * instead of falling back to proportional; still no element (word) boxes. Caches the bitmap->Mat
 * conversion across a frame's regions ([com.playtranslate.ocr.composites.DetectThenRecognize]
 * feeds one image's regions sequentially). `threadSafe = false` (single MNN
 * session, shared with [PaddleDetector] and serialized by the composite's mutex).
 *
 * Reads below [DROP_SCORE] are dropped here, engine-local, the way PaddleOCR's own
 * `predict_system` applies `drop_score` — not in the shared normalizer, whose other
 * engines report confidence on different scales (Meiki garbage sits at 0.32-0.45).
 */
class PaddleRecognizer(private val session: PaddleOcrSession) : TextRecognizer {

    override val capabilities = OcrCapabilities(
        orientation = OcrOrientationSupport.BOTH,
        emitsCharBoxes = true,
        emitsElementBoxes = false,
        wholeRegionInput = false,
        threadSafe = false,
        selfPreprocesses = true,
        emitsSubLineBoxes = false,
    )

    private var cachedBitmap: Bitmap? = null
    private var cachedMat: Mat? = null

    override suspend fun recognize(image: OcrImage, region: DetectedRegion): RecognizedRegion? {
        val quad = region.quad ?: return null
        if (quad.size != 4) return null
        val pts = Array(4) { Point(quad[it].x.toDouble(), quad[it].y.toDouble()) }
        val r = session.recognizeQuad(matFor(image.bitmap), pts) ?: return null
        if (r.text.isBlank()) return null
        if (!passesDropScore(r.confidence)) {
            if (BuildConfig.DEBUG) {
                // Same tag as the composite's per-region trace so the calibration
                // census keeps seeing what the gate removes (text is user content).
                Log.d("OcrConf", "drop rec=%.2f det=%s n=%d \"%s\"".format(
                    Locale.US, r.confidence, if (region.confidence < 0f) "-" else "%.2f".format(Locale.US, region.confidence),
                    r.text.count { !it.isWhitespace() }, r.text.take(40)))
            }
            return null
        }
        // CTC firing positions → per-char boxes. Axis = r.stripVertical (warpCrop's own
        // rotation). Upright regions distribute across the AABB; rotated regions walk
        // the baseline and emit upright cells riding it (the shared slanted-char
        // representation) — see [synthesizeCharBoxes].
        val chars = synthesizeCharBoxes(r.chars, region.box, r.stripVertical)
        val line = RecognizedLine(
            text = r.text, box = region.box, orientation = region.orientation, chars = chars,
        )
        return RecognizedRegion(
            text = r.text,
            box = region.box,
            orientation = region.orientation,
            confidence = r.confidence,
            lines = listOf(line),
            origin = RegionOrigin.LINE,
        )
    }

    /** RGBA Mat for [bitmap], reused while the same bitmap's regions are processed. */
    private fun matFor(bitmap: Bitmap): Mat {
        cachedMat?.let { if (bitmap === cachedBitmap) return it }
        cachedMat?.release()
        val m = Mat().also { Utils.bitmapToMat(bitmap, it) }
        cachedBitmap = bitmap
        cachedMat = m
        return m
    }

    override fun close() {
        cachedMat?.release()
        cachedMat = null
        cachedBitmap = null
    }

    companion object {
        /**
         * Minimum recognizer confidence (mean max-prob over emitted CTC timesteps,
         * the statistic PaddleOCR's CTCLabelDecode reports) for a read to survive.
         * PaddleOCR's `drop_score` default. Calibrated on Thor 2026-09-08, Fast tier,
         * JA: DBNet fires on clusters of game sprites and the recognizer reads them
         * as 1-7 chars in boxes hundreds of px tall — 16 such regions scored 0.08 to
         * 0.48; real words and sentences (3+ chars) scored 0.86 to 1.00, dialogue
         * 0.93+. Every 3+-char read under 0.75 was itself a misread of menu UI. Known
         * cost, all in status menus: short stat labels and numbers (HP 0.48, "13"
         * 0.25); TP/LV sat at 0.57-0.59 and survive. The detector's own box score
         * does NOT separate (junk 0.60-0.95, median 0.75 vs legit 0.77), which is
         * why the gate is on recognition, not detection. The DEBUG `OcrConf` trace
         * in DetectThenRecognize + the drop line above keep this re-checkable.
         */
        const val DROP_SCORE = 0.5f

        /** The gate: keep iff [confidence] >= [DROP_SCORE]. Unit-pinned. */
        internal fun passesDropScore(confidence: Float): Boolean = confidence >= DROP_SCORE
    }
}
