package com.playtranslate

import android.graphics.Rect
import android.text.TextPaint
import com.playtranslate.language.HintTextAnnotation
import com.playtranslate.ocr.core.AngleFrame
import com.playtranslate.ocr.core.DeskewGeometry
import com.playtranslate.ui.TextBox

/**
 * Furigana placement for a SLANTED line — the rotated sibling of the upright
 * arithmetic in [OverlayToolkit.buildFuriganaBoxesForGroup], kept separate so
 * the θ==0 path stays byte-identical (branch, don't unify).
 *
 * Everything happens in the line's deskewed frame (u along the baseline from
 * the AABB center, v across it):
 *  - each annotation's base span becomes a u-extent — from its symbol cells
 *    (upright rects riding the baseline, the shared slanted-char shape) when
 *    present, else from paint char-width weights along the oriented width (the
 *    element mapper is skipped: element boxes are AABBs of slanted words);
 *  - the ruby band sits ABOVE the baseline strip: v ∈ [−oh/2 − fh, −oh/2],
 *    fh = 0.75·oh — the in-frame analogue of "above the text";
 *  - spans whose RENDERED text would collide merge along u (the reading is
 *    often wider than its base — same policy as the upright merge);
 *  - each merged span realizes as a [TextBox] carrying the line's angle with
 *    the band's oriented dims, bounds = the band's EXACT screen AABB
 *    ([DeskewGeometry.screenAabbOf] — center ±0.5px + containment).
 */
internal object FuriganaSlantPlacement {

    fun build(
        line: OcrManager.LineBox,
        annotations: List<HintTextAnnotation>,
        furiganaPaint: TextPaint,
    ): List<TextBox> {
        val theta = line.angleDeg
        if (theta == 0f || line.text.isEmpty()) return emptyList()
        val rad = Math.toRadians(theta.toDouble())
        val c = kotlin.math.cos(rad).toFloat()
        val s = kotlin.math.sin(rad).toFloat()
        val cx = line.bounds.exactCenterX()
        val cy = line.bounds.exactCenterY()
        val ow = line.orientedWidth
        val oh = line.orientedHeight
        if (ow <= 0f || oh <= 0f) return emptyList()

        fun uOf(x: Float, y: Float): Float = (x - cx) * c + (y - cy) * s

        // Annotation spans → u-extents.
        data class Span(val text: String, val u1: Float, val u2: Float)
        val spans = mutableListOf<Span>()
        if (line.symbols.isNotEmpty()) {
            for (ann in annotations) {
                val matching = line.symbols.filter { it.charOffset in ann.baseStart until ann.baseEnd }
                if (matching.isEmpty()) continue
                val u1 = matching.minOf {
                    uOf(it.bounds.exactCenterX(), it.bounds.exactCenterY()) - it.bounds.width() / 2f
                }
                val u2 = matching.maxOf {
                    uOf(it.bounds.exactCenterX(), it.bounds.exactCenterY()) + it.bounds.width() / 2f
                }
                if (u2 > u1) spans += Span(ann.hintText, u1, u2)
            }
        } else {
            val charWidths = FloatArray(line.text.length).also { furiganaPaint.getTextWidths(line.text, it) }
            val total = charWidths.sum()
            if (total <= 0f) return emptyList()
            for (ann in annotations) {
                val lW = (0 until ann.baseStart.coerceIn(0, charWidths.size))
                    .sumOf { charWidths[it].toDouble() }.toFloat()
                val rW = (0 until ann.baseEnd.coerceIn(0, charWidths.size))
                    .sumOf { charWidths[it].toDouble() }.toFloat()
                val u1 = -ow / 2f + lW / total * ow
                val u2 = -ow / 2f + rW / total * ow
                if (u2 > u1) spans += Span(ann.hintText, u1, u2)
            }
        }
        if (spans.isEmpty()) return emptyList()

        val fh = (oh * 0.75f).coerceAtLeast(1f)

        // Merge along u on rendered extents (upright merge policy, one axis).
        fun renderedEnd(sp: Span): Float {
            val sizePx = (fh * 0.7f).coerceAtLeast(4f)
            val saved = furiganaPaint.textSize
            furiganaPaint.textSize = sizePx
            val w = furiganaPaint.measureText(sp.text)
            furiganaPaint.textSize = saved
            return maxOf(sp.u2, sp.u1 + w)
        }

        val sorted = spans.sortedBy { it.u1 }
        val merged = mutableListOf<Span>()
        var cur = sorted[0]
        var curEnd = renderedEnd(cur)
        for (i in 1 until sorted.size) {
            val next = sorted[i]
            if (next.u1 < curEnd) {
                cur = Span(cur.text + next.text, cur.u1, maxOf(cur.u2, next.u2))
                curEnd = renderedEnd(cur)
            } else {
                merged += cur
                cur = next
                curEnd = renderedEnd(cur)
            }
        }
        merged += cur

        // Realize each span: the band's in-frame rect rotated out to its exact
        // screen AABB, carried with the line's angle + the band's true dims.
        val frame = AngleFrame(theta, line.bounds.centerX(), line.bounds.centerY())
        val ax = frame.anchorX
        val ay = frame.anchorY
        val vTop = -oh / 2f - fh
        val vBottom = -oh / 2f
        return merged.map { sp ->
            val inFrame = Rect(
                DeskewGeometry.roundHalfUp(ax + sp.u1),
                DeskewGeometry.roundHalfUp(ay + vTop),
                DeskewGeometry.roundHalfUp(ax + sp.u2),
                DeskewGeometry.roundHalfUp(ay + vBottom),
            )
            TextBox(
                translatedText = sp.text,
                bounds = DeskewGeometry.screenAabbOf(inFrame, frame),
                lineCount = 1,
                isFurigana = true,
                angleDeg = theta,
                orientedWidth = inFrame.width().toFloat(),
                orientedHeight = inFrame.height().toFloat(),
            )
        }
    }
}
