package com.playtranslate.ocr.paddle

import android.graphics.Rect
import com.playtranslate.ocr.core.AngleFrame
import com.playtranslate.ocr.core.CharBox
import com.playtranslate.ocr.core.DeskewGeometry
import com.playtranslate.ocr.core.OcrBox

/**
 * Turn a recognized region's CTC firing fractions ([PaddleOcrSession.DecodedChar])
 * into per-character [CharBox]es, so PaddleOCR feeds the precise furigana/drag tier
 * (`line.symbols`) instead of the proportional fallback — the same payoff
 * [com.playtranslate.ocr.meiki.MeikiRecognizer] gets from a dedicated char model.
 *
 * **Geometry:** upright regions distribute the fractions across the AABB —
 * bit-identical to the pre-angle tier. A rotated region walks them along its
 * BASELINE (`u ∈ [−ow/2, +ow/2]` rotated about the AABB center) and emits
 * upright cells centered on the baseline points — ML Kit's existing shape for
 * slanted text, so every char-tier consumer sees one representation.
 *
 * **Axis:** keyed off [stripVertical] — warpCrop's actual rotation decision, carried on
 * the [PaddleOcrSession.CropResult] — NOT the region's orientation label. The firing
 * fractions run along the strip's reading axis, which is what rotation determines, so
 * this can't transpose the boxes against the strip the recognizer read. A rotated
 * region is HORIZONTAL by the producer invariant, so its strip is never vertical.
 */
internal fun synthesizeCharBoxes(
    decoded: List<PaddleOcrSession.DecodedChar>,
    box: OcrBox,
    stripVertical: Boolean,
): List<CharBox> {
    if (decoded.isEmpty()) return emptyList()
    val n = decoded.size
    val c = FloatArray(n) { decoded[it].firingFraction }

    // Cell boundaries in [0,1] along the reading axis: interior = neighbour midpoints;
    // the two ends mirror the adjacent half-cell so a box hugs its glyph rather than the
    // strip's padded edge. (Firing fractions are strictly increasing — one emission per
    // timestep, in timestep order — so each cell is non-empty and ordered.)
    val bound = FloatArray(n + 1)
    for (i in 1 until n) bound[i] = (c[i - 1] + c[i]) / 2f
    if (n == 1) {
        bound[0] = 0f; bound[1] = 1f
    } else {
        bound[0] = (2f * c[0] - bound[1]).coerceIn(0f, bound[1])
        bound[n] = (2f * c[n - 1] - bound[n - 1]).coerceIn(bound[n - 1], 1f)
    }

    if (box.isRotated) {
        val frame = AngleFrame(box.angleDeg, box.bounds.centerX(), box.bounds.centerY())
        val cx = box.bounds.exactCenterX()
        val cy = box.bounds.exactCenterY()
        val h = DeskewGeometry.roundHalfUp(box.orientedHeight).coerceAtLeast(1)
        return decoded.mapIndexed { i, d ->
            val uLo = (bound[i] - 0.5f) * box.orientedWidth
            val uHi = (bound[i + 1] - 0.5f) * box.orientedWidth
            val uc = (uLo + uHi) / 2f
            val bx = DeskewGeometry.rotateX(cx + uc, cy, cx, cy, frame.cosT, frame.sinT)
            val by = DeskewGeometry.rotateY(cx + uc, cy, cx, cy, frame.cosT, frame.sinT)
            val w = DeskewGeometry.roundHalfUp(uHi - uLo).coerceAtLeast(1)
            val l = DeskewGeometry.roundHalfUp(bx - w / 2f)
            val t = DeskewGeometry.roundHalfUp(by - h / 2f)
            CharBox(text = d.text, box = OcrBox.upright(Rect(l, t, l + w, t + h)), charOffset = d.charOffset)
        }
    }

    val bounds = box.bounds
    val span = if (stripVertical) bounds.height() else bounds.width()
    val origin = if (stripVertical) bounds.top else bounds.left
    return decoded.mapIndexed { i, d ->
        val lo = origin + (bound[i] * span).toInt()
        val hi = origin + (bound[i + 1] * span).toInt()
        val rect = if (stripVertical) {
            Rect(bounds.left, lo, bounds.right, hi)
        } else {
            Rect(lo, bounds.top, hi, bounds.bottom)
        }
        CharBox(text = d.text, box = OcrBox.upright(rect), charOffset = d.charOffset)
    }
}
