package com.playtranslate.ocr.paddle

import android.graphics.Bitmap
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

/**
 * [TextRecognizer] over PaddleOCR's CRNN/SVTR. Perspective-warps each
 * [DetectedRegion]'s quad (via [PaddleOcrSession.recognizeQuad], preserving
 * deskew) and recognizes it. Line-level — no char/element boxes (so drag-lookup
 * + furigana fall back to proportional positioning). Caches the bitmap->Mat
 * conversion across a frame's regions ([com.playtranslate.ocr.composites.DetectThenRecognize]
 * feeds one image's regions sequentially). `threadSafe = false` (single MNN
 * session, shared with [PaddleDetector] and serialized by the composite's mutex).
 */
class PaddleRecognizer(private val session: PaddleOcrSession) : TextRecognizer {

    override val capabilities = OcrCapabilities(
        orientation = OcrOrientationSupport.BOTH,
        emitsCharBoxes = false,
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
        val line = RecognizedLine(text = r.text, box = region.box, orientation = region.orientation)
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
}
