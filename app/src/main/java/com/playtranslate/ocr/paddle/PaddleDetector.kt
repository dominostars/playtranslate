package com.playtranslate.ocr.paddle

import android.graphics.PointF
import com.playtranslate.language.TextOrientation
import com.playtranslate.ocr.core.DetectedRegion
import com.playtranslate.ocr.core.OcrCapabilities
import com.playtranslate.ocr.core.OcrImage
import com.playtranslate.ocr.core.OcrOrientationSupport
import com.playtranslate.ocr.core.OrientedBoxGeometry
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
            val quad = box.points.map { PointF(it.x.toFloat(), it.y.toFloat()) }
            // The AABB label, unchanged from the pre-angle pipeline. Within the
            // supported ≤45° slant band it provably agrees with warpCrop
            // already: a quad whose long axis is ≤45° from horizontal has AABB
            // height ≤ width (h/w > 1 requires axis > 45°), so this aspect test
            // cannot say VERTICAL there.
            val orientation = if (aabb.height() > aabb.width() * 1.5) TextOrientation.VERTICAL
            else TextOrientation.HORIZONTAL
            DetectedRegion(
                // Angle band capped at 45°, NOT the helper's default: past 45°
                // DbNet's corner ordering rolls and warpCrop routes elongated
                // quads through its 90° column rotate, so reads there are
                // sign-dependent — claiming an angle would pin a confident chip
                // on an unreliable read. With the cap, everything past 45° is
                // bit-identical to the pre-angle pipeline.
                box = OrientedBoxGeometry.boxFor(aabb, quad, orientation, maxSlantDeg = 45f),
                quad = quad,
                orientation = orientation,
                confidence = -1f,
            )
        }

    override fun close() { /* PaddleOcrSession lifecycle owned by PaddleOcrBridge */ }
}
