package com.playtranslate.ocr.meiki

import android.graphics.Bitmap
import android.graphics.Rect
import com.playtranslate.language.TextOrientation
import com.playtranslate.ocr.core.CharBox
import com.playtranslate.ocr.core.DetectedRegion
import com.playtranslate.ocr.core.OcrBox
import com.playtranslate.ocr.core.OcrCapabilities
import com.playtranslate.ocr.core.OcrImage
import com.playtranslate.ocr.core.OcrOrientationSupport
import com.playtranslate.ocr.core.RecognizedLine
import com.playtranslate.ocr.core.RecognizedRegion
import com.playtranslate.ocr.core.RegionOrigin
import com.playtranslate.ocr.core.TextRecognizer
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

/**
 * [TextRecognizer] over Meiki's D-FINE character-detection recognizers (vertical
 * 32×480 / horizontal 960×32, chosen by [DetectedRegion.orientation]). Crops the
 * region's upright box from the frame, recognizes it, and — the payoff over
 * PaddleOCR — **emits per-character boxes** ([CharBox]), mapped from crop-local
 * back to ORIGINAL-bitmap coords, so drag-lookup + furigana place precisely
 * instead of falling back to proportional. Caches the bitmap→BGR Mat across a
 * frame's regions (the composite feeds them sequentially). `threadSafe = false`
 * (shared MNN session, serialized by the composite mutex).
 */
class MeikiRecognizer(private val session: MeikiSession) : TextRecognizer {

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
    private var cachedBgr: Mat? = null

    override suspend fun recognize(image: OcrImage, region: DetectedRegion): RecognizedRegion? {
        val r = region.box.bounds
        val bw = image.bitmap.width; val bh = image.bitmap.height
        val x1 = r.left.coerceIn(0, bw - 1)
        val y1 = r.top.coerceIn(0, bh - 1)
        val x2 = r.right.coerceIn(x1 + 1, bw)
        val y2 = r.bottom.coerceIn(y1 + 1, bh)
        if (x2 - x1 < 2 || y2 - y1 < 2) return null

        val sub = bgrFor(image.bitmap).submat(y1, y2, x1, x2)
        val res = try {
            session.recognize(sub, region.orientation == TextOrientation.VERTICAL)
        } finally {
            sub.release()
        }
        if (res.text.isBlank()) return null

        // Offset crop-local char boxes into original-bitmap coords.
        val chars = res.chars.map { c ->
            CharBox(
                text = c.text,
                box = OcrBox.upright(Rect(x1 + c.rect.left, y1 + c.rect.top, x1 + c.rect.right, y1 + c.rect.bottom)),
                charOffset = c.offset,
            )
        }
        val line = RecognizedLine(
            text = res.text, box = region.box, orientation = region.orientation, chars = chars,
        )
        return RecognizedRegion(
            text = res.text,
            box = region.box,
            orientation = region.orientation,
            confidence = res.confidence,
            lines = listOf(line),
            origin = RegionOrigin.LINE,
        )
    }

    /** RGBA→BGR Mat for [bitmap], reused while the same bitmap's regions process. */
    private fun bgrFor(bitmap: Bitmap): Mat {
        cachedBgr?.let { if (bitmap === cachedBitmap) return it }
        cachedBgr?.release()
        val rgba = Mat().also { Utils.bitmapToMat(bitmap, it) }
        val bgr = Mat()
        Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
        rgba.release()
        cachedBitmap = bitmap; cachedBgr = bgr
        return bgr
    }

    override fun close() {
        cachedBgr?.release(); cachedBgr = null; cachedBitmap = null
    }
}
