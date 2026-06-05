package com.playtranslate.ocr.meiki

import com.playtranslate.language.TextOrientation
import com.playtranslate.ocr.core.DetectedRegion
import com.playtranslate.ocr.core.OcrBox
import com.playtranslate.ocr.core.OcrCapabilities
import com.playtranslate.ocr.core.OcrImage
import com.playtranslate.ocr.core.OcrOrientationSupport
import com.playtranslate.ocr.core.TextDetector

/**
 * [TextDetector] over Meiki's D-FINE text-region detector. Emits one
 * [DetectedRegion] per region — an axis-aligned box in ORIGINAL-bitmap coords,
 * with orientation by aspect (tall ⇒ VERTICAL). No deskew [DetectedRegion.quad]
 * (Meiki produces AABBs, not min-area-rect polygons); the recognizer crops the
 * upright box. `selfPreprocesses = true` (Meiki normalizes internally — pipeline
 * passes the original bitmap); `threadSafe = false` (single MNN session, serialized
 * by the composite). The shared [MeikiSession] is owned/closed by [MeikiBridge].
 */
class MeikiDetector(private val session: MeikiSession) : TextDetector {

    override val capabilities = OcrCapabilities(
        orientation = OcrOrientationSupport.BOTH,
        emitsCharBoxes = false,
        emitsElementBoxes = false,
        wholeRegionInput = false,
        threadSafe = false,
        selfPreprocesses = true,
        emitsSubLineBoxes = false,
    )

    override suspend fun detect(image: OcrImage): List<DetectedRegion> =
        session.detect(image.bitmap).map { db ->
            DetectedRegion(
                box = OcrBox.upright(db.rect),
                quad = null,
                orientation = if (db.rect.height() > db.rect.width() * 1.3) TextOrientation.VERTICAL
                else TextOrientation.HORIZONTAL,
                confidence = db.score,
            )
        }

    override fun close() { /* MeikiSession lifecycle owned by MeikiBridge */ }
}
