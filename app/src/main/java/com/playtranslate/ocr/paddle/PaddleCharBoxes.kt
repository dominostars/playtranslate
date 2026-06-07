package com.playtranslate.ocr.paddle

import android.graphics.Rect
import com.playtranslate.ocr.core.CharBox
import com.playtranslate.ocr.core.OcrBox

/**
 * Turn a recognized region's CTC firing fractions ([PaddleOcrSession.DecodedChar])
 * into per-character [CharBox]es, so PaddleOCR feeds the precise furigana/drag tier
 * (`line.symbols`) instead of the proportional fallback — the same payoff
 * [com.playtranslate.ocr.meiki.MeikiRecognizer] gets from a dedicated char model.
 *
 * **Tier-1 geometry:** distribute the fractions across the region's upright AABB
 * ([bounds]). Exact for upright text (≈ all game text); for genuinely slanted text the
 * spacing compresses (the AABB is wider than the deskewed strip), no worse than the
 * fallback it replaces. The cells tile [0,1] contiguously and monotonically so a
 * furigana span (first cell's left … last cell's right) covers exactly its base.
 *
 * **Axis:** keyed off [stripVertical] — warpCrop's actual rotation decision, carried on
 * the [PaddleOcrSession.CropResult] — NOT the region's orientation label. The firing
 * fractions run along the strip's reading axis, which is what rotation determines, so
 * this can't transpose the boxes against the strip the recognizer read.
 */
internal fun synthesizeCharBoxes(
    decoded: List<PaddleOcrSession.DecodedChar>,
    bounds: Rect,
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
