package com.playtranslate.ocr.paddle

import android.graphics.PointF
import com.playtranslate.language.TextOrientation
import com.playtranslate.ocr.core.DetectedRegion
import com.playtranslate.ocr.core.OcrBox
import com.playtranslate.ocr.core.OcrCapabilities
import com.playtranslate.ocr.core.OcrImage
import com.playtranslate.ocr.core.OcrOrientationSupport
import com.playtranslate.ocr.core.TextDetector

/**
 * [TextDetector] over PaddleOCR DBNet. Emits one [DetectedRegion] per detected
 * text region, carrying the 4-point deskew [DetectedRegion.quad] (for
 * [PaddleRecognizer]'s perspective warp) and an axis-aligned box, in
 * ORIGINAL-bitmap coords. `selfPreprocesses = true` (PaddleOCR does its own
 * normalization — the pipeline passes the original bitmap); `threadSafe = false`
 * (single MNN session). The shared [PaddleOcrSession] is owned/closed by
 * [PaddleOcrBridge], so close() here is a no-op.
 */
class PaddleDetector(private val session: PaddleOcrSession) : TextDetector {

    override val capabilities = OcrCapabilities(
        orientation = OcrOrientationSupport.BOTH,
        emitsCharBoxes = false,
        emitsElementBoxes = false,
        wholeRegionInput = false,
        threadSafe = false,
        selfPreprocesses = true,
        emitsSubLineBoxes = true,
    )

    override suspend fun detect(image: OcrImage): List<DetectedRegion> =
        session.detect(image.bitmap).map { box ->
            val aabb = box.aabb
            DetectedRegion(
                box = OcrBox.upright(aabb),
                quad = box.points.map { PointF(it.x.toFloat(), it.y.toFloat()) },
                orientation = if (aabb.height() > aabb.width() * 1.5) TextOrientation.VERTICAL
                else TextOrientation.HORIZONTAL,
                confidence = -1f,
            )
        }

    override fun close() { /* PaddleOcrSession lifecycle owned by PaddleOcrBridge */ }
}
