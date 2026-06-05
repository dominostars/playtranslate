package com.playtranslate.ocr.mangaocr

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
import org.opencv.imgproc.Imgproc

/**
 * [TextRecognizer] over [MangaOcrSession]. Crops the detected region from the
 * frame and recognizes it. **Text only — no char/element boxes** (manga-ocr emits
 * a string), so drag-lookup + furigana use the proportional fallback. Pairs with
 * any detector via [com.playtranslate.ocr.composites.DetectThenRecognize]
 * (Meiki→Manga or Paddle→Manga, per the engine selector). Caches the bitmap→BGR
 * Mat across a frame's regions. `threadSafe = false` (shared MNN session,
 * serialized by the composite mutex).
 */
class MangaOcrRecognizer(private val session: MangaOcrSession) : TextRecognizer {

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
        val text = try { session.recognize(sub) } finally { sub.release() }
        if (text.isBlank()) return null

        val line = RecognizedLine(text = text, box = region.box, orientation = region.orientation)
        return RecognizedRegion(
            text = text,
            box = region.box,
            orientation = region.orientation,
            confidence = -1f,
            lines = listOf(line),
            origin = RegionOrigin.LINE,
        )
    }

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
