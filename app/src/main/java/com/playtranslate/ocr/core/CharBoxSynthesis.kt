package com.playtranslate.ocr.core

import android.graphics.Rect

/**
 * Synthesize evenly-spaced per-character [CharBox]es for a recognizer that emits
 * text only (manga-ocr), so the precise furigana/drag tier (`line.chars`) gets a
 * usable proportional approximation instead of nothing — the vendor-neutral analogue
 * of [com.playtranslate.ocr.paddle.synthesizeCharBoxes], minus the CTC firing
 * fractions a CTC recognizer has.
 *
 * Tiles the line's upright bounds into [text].length equal, contiguous cells along
 * the reading axis ([vertical] → Y, else X). Contiguity (cell i's far edge == cell
 * i+1's near edge, via integer `span * i / n`) matters so a multi-char furigana span
 * (first cell's start … last cell's end) covers exactly its base glyphs. [charOffset]
 * is the index in [text]; callers pass already-space-stripped text so offsets are
 * contiguous and align 1:1 with the string. Accuracy is the documented
 * CJK-monospace approximation (exact spacing only for monospaced text).
 *
 * A rotated [box] tiles its BASELINE instead (`u ∈ [−ow/2, +ow/2]` about the AABB
 * center) and emits upright cells centered on the baseline points — the shared
 * slanted-char representation (ML Kit measures it; Paddle synthesizes it).
 */
internal fun synthesizeEvenCharBoxes(
    text: String,
    box: OcrBox,
    vertical: Boolean,
): List<CharBox> {
    val n = text.length
    if (n == 0) return emptyList()
    if (box.isRotated) {
        val frame = AngleFrame(box.angleDeg, box.bounds.centerX(), box.bounds.centerY())
        val cx = box.bounds.exactCenterX()
        val cy = box.bounds.exactCenterY()
        val h = DeskewGeometry.roundHalfUp(box.orientedHeight).coerceAtLeast(1)
        val ow = box.orientedWidth
        return (0 until n).map { i ->
            val uLo = -ow / 2f + ow * i / n
            val uHi = -ow / 2f + ow * (i + 1) / n
            val uc = (uLo + uHi) / 2f
            val bx = DeskewGeometry.rotateX(cx + uc, cy, cx, cy, frame.cosT, frame.sinT)
            val by = DeskewGeometry.rotateY(cx + uc, cy, cx, cy, frame.cosT, frame.sinT)
            val w = DeskewGeometry.roundHalfUp(uHi - uLo).coerceAtLeast(1)
            val l = DeskewGeometry.roundHalfUp(bx - w / 2f)
            val t = DeskewGeometry.roundHalfUp(by - h / 2f)
            CharBox(text = text[i].toString(), box = OcrBox.upright(Rect(l, t, l + w, t + h)), charOffset = i)
        }
    }
    val bounds = box.bounds
    val span = if (vertical) bounds.height() else bounds.width()
    val origin = if (vertical) bounds.top else bounds.left
    return (0 until n).map { i ->
        val lo = origin + span * i / n
        // Guarantee a non-zero cell even when the char count exceeds the line's pixel span
        // (a hallucinated over-long reading) — a zero-area box is un-tappable and gives
        // furigana a degenerate rect.
        val hi = maxOf(lo + 1, origin + span * (i + 1) / n)
        val rect = if (vertical) {
            Rect(bounds.left, lo, bounds.right, hi)
        } else {
            Rect(lo, bounds.top, hi, bounds.bottom)
        }
        CharBox(text = text[i].toString(), box = OcrBox.upright(rect), charOffset = i)
    }
}
