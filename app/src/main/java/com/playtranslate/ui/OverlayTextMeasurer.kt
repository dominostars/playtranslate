package com.playtranslate.ui

import android.util.LruCache
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Pass 4's measurer for [TranslationOverlayView]: what a render child's
 * autosize will find, measured on [template], a text child built like the
 * render children (`TranslationOverlayView.configureTranslationText`) and
 * laid out by its own line breaker at the child's frame width. Its layout
 * reads the same fields (font, locale, spacing, break strategy) the child's
 * layout and its autosize test read, so the two can't disagree. Never
 * narrower than the widest unbreakable run ([lineBreakRuns]), so a certified
 * frame never breaks a word.
 *
 * Measurements are memoized in one bounded LRU ([maxEntries] entries) per
 * measurer, and a view keeps one for its life: live mode lays the same lines
 * out again on every rebuild, while a long session's stream of new lines
 * only ever evicts the least recently used. Main thread only, like every
 * caller.
 */
internal class OverlayTextMeasurer(
    private val template: TextView,
    /** [VerticalTextView]'s inset, px (its `pad`). */
    private val stackPadPx: Float,
    /** CJK targets stack in several columns; other scripts stay one column. */
    private val multiColumnStacks: Boolean,
    /** The memo's capacity, entries. */
    maxEntries: Int = 2048,
) : MinTextMeasurer {

    private val padW = template.compoundPaddingLeft + template.compoundPaddingRight

    init {
        // A TextView decides how to relayout on setText from its layout
        // params (TextView.checkForRelayout reads them, and a detached view
        // has none): wrap-content ones make it drop its layout and build a
        // fresh one at the next measure, which is what every call here wants.
        if (template.layoutParams == null) {
            template.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
    }

    private data class Key(val text: String, val px: Int, val wrap: Int)

    /** The one memo, LRU. Per (text, px, wrap): the cross extent (padding
     *  included), or [NO_FIT] where the wrap is shorter than the widest run.
     *  Under wrap [RUN_WRAP] (-1): the widest unbreakable run at (text, px),
     *  content px. No real key has a wrap below 1 ([footprint] returns null
     *  for a wrap extent <= 0 before any lookup), so the two never collide. */
    private val memo = LruCache<Key, Int>(maxEntries)

    private inline fun memoized(key: Key, compute: () -> Int): Int =
        memo.get(key) ?: compute().also { memo.put(key, it) }

    override fun footprint(box: TextBox, mode: RenderMode, px: Int, wrapExtent: Int): FitSize? {
        val text = box.translatedText
        if (text.isEmpty() || px <= 0 || wrapExtent <= 0) return null
        if (mode == RenderMode.STACK_UPRIGHT) return stack(text, px, wrapExtent)
        val cross = crossAt(text, px, wrapExtent)
        if (cross == NO_FIT) return null
        // ROTATE lays out tall-side-as-width, then turns 90°.
        return if (mode == RenderMode.ROTATE) FitSize(cross, wrapExtent) else FitSize(wrapExtent, cross)
    }

    /** [text] at [px] in a frame [wrap] px wide: the frame height it takes. */
    private fun crossAt(text: String, px: Int, wrap: Int): Int {
        return memoized(Key(text, px, wrap)) {
            if (wrap - padW < widestRun(text, px)) {
                NO_FIT
            } else {
                template.text = text
                template.setTextSize(TypedValue.COMPLEX_UNIT_PX, px.toFloat())
                template.measure(
                    View.MeasureSpec.makeMeasureSpec(wrap, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                )
                template.measuredHeight
            }
        }
    }

    /** The widest run [text] can't break across lines at [px], +1: measureText
     *  and the layout's own run width may round apart, and a wrap one px short
     *  would let the layout break the run. */
    private fun widestRun(text: String, px: Int): Int = memoized(Key(text, px, RUN_WRAP)) {
        template.setTextSize(TypedValue.COMPLEX_UNIT_PX, px.toFloat())
        val paint = template.paint
        ceil(lineBreakRuns(text).maxOfOrNull { paint.measureText(it) } ?: 0f).toInt() + 1
    }

    /** [VerticalTextView]'s packing at cell size [px] in a frame [height] px
     *  tall: the rows that fit ([VerticalTextLayout.compute]'s own floor), the
     *  columns they need, and the width that takes (a hair of slack so an
     *  exact product can't floor a column short). Null when the frame holds
     *  no row, or a single-column script would need a second column. */
    private fun stack(text: String, px: Int, height: Int): FitSize? {
        val glyphs = VerticalTextLayout.splitGraphemes(text).size
        if (glyphs == 0) return null
        val rowStep = px * VerticalTextLayout.LINE_SPACING
        val colStep = px * VerticalTextLayout.COL_SPACING
        val rows = floor((height - 2 * stackPadPx) / rowStep).toInt()
        if (rows < 1) return null
        val cols = (glyphs + rows - 1) / rows
        if (cols > 1 && !multiColumnStacks) return null
        val width = ceil(cols * colStep * (1f + STACK_SLACK) + STACK_SLACK + 2 * stackPadPx).toInt()
        return FitSize(width, height)
    }

    private companion object {
        const val NO_FIT = -1
        /** The wrap a run-width entry is stored under ([memo]). */
        const val RUN_WRAP = -1
        const val STACK_SLACK = 1e-4f
    }
}
