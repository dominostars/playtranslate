package com.playtranslate.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import java.text.BreakIterator
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Pure (View-free) geometry for packing upright glyph cells into a vertical
 * text box: how big each cell is, how many rows per column, how many columns,
 * and the per-axis step between cell centres.
 *
 * Extracted from [VerticalTextView] so the packing math is unit-testable
 * without a live View — the same split [OverlayLayout] uses.
 */
internal object VerticalTextLayout {

    /** Default vertical gap factor between stacked cell centres (× cell size). */
    const val LINE_SPACING = 1.05f
    /** Default horizontal gap factor between column centres (× cell size). */
    const val COL_SPACING = 1.15f

    /**
     * Result of packing N cells into a W×H area. [cols] is the number of
     * columns actually drawn — equal to the columns needed whenever the text
     * fits, capped to the width in the forced-min overflow case (trailing cells
     * dropped, never drawn outside the box), and `0` when the box is too narrow
     * for even one column. The caller draws exactly `rows * cols` cells.
     */
    data class Layout(
        val cellSize: Float,
        val rows: Int,
        val cols: Int,
        val rowStep: Float,
        val colStep: Float,
    )

    /**
     * Largest cell size in `[minPx, maxPx]` that packs [graphemeCount] upright
     * cells into the padded area, stacked top-to-bottom and wrapping into
     * additional (right-to-left) columns.
     *
     * Height is satisfied by construction (rows are derived from the available
     * height), so the only free constraint is width — and the columns needed
     * to show every cell grow monotonically with cell size, so a binary search
     * on the width-fit predicate is valid. The upper bound is additionally
     * capped so a single cell never exceeds the box height.
     *
     * If even `minPx` cannot show every cell within the width, the size stays
     * at `minPx` and [Layout.cols] is capped to what the width holds — the
     * trailing (last-read) cells are dropped rather than drawn outside the box;
     * a box too narrow for even one column yields `cols == 0` (nothing but the
     * background renders). That invariant (rendered overlay never exceeds the
     * child bounds) is
     * load-bearing: pinhole detection and OCR masking key off the child's
     * layout rect, so overlay pixels drawn outside it would go untracked
     * (stale-pixel false changes) and unmasked (OCR re-reading its own text).
     *
     * The dropped tail is silent by design — an accepted limitation, not an
     * oversight. It is very rare (multi-column wrap already uses the full box
     * width, and 6sp cells are tiny) and mirrors the horizontal path's own
     * degradation at the same 6sp floor. An overflow marker (vertical ellipsis)
     * and box expansion were both considered and intentionally declined
     * (2026-06-06): expansion is inconsistent with the shrink-to-fit horizontal
     * boxes and would overrun neighbours/screen edges.
     */
    fun compute(
        graphemeCount: Int,
        width: Float, height: Float,
        pad: Float,
        minPx: Float, maxPx: Float,
        lineSpacing: Float = LINE_SPACING,
        colSpacing: Float = COL_SPACING,
    ): Layout {
        val g = graphemeCount.coerceAtLeast(1)
        val availW = (width - 2f * pad).coerceAtLeast(1f)
        val availH = (height - 2f * pad).coerceAtLeast(1f)
        // Never let one cell be taller than the column; keeps short text in a
        // short-but-wide box from ballooning to maxPx and overflowing vertically.
        val effMax = maxPx.coerceAtMost(availH / lineSpacing).coerceAtLeast(minPx)

        fun rowsAt(s: Float) = floor(availH / (s * lineSpacing)).toInt().coerceAtLeast(1)
        // Columns needed to show ALL cells at size s (uncapped) — drives the fit
        // search. widthNeeded = neededCols * colStep is non-decreasing in s.
        fun neededCols(s: Float) = ceil(g.toDouble() / rowsAt(s)).toInt().coerceAtLeast(1)
        fun fitsWidth(s: Float) = neededCols(s) * (s * colSpacing) <= availW

        val size = when {
            !fitsWidth(minPx) -> minPx          // can't show all even at min → truncate at min
            fitsWidth(effMax) -> effMax
            else -> {
                var lo = minPx   // known to fit
                var hi = effMax  // known not to fit
                repeat(40) {
                    val mid = (lo + hi) / 2f
                    if (fitsWidth(mid)) lo = mid else hi = mid
                }
                lo
            }
        }

        val rowStep = size * lineSpacing
        val rows = rowsAt(size)
        val colStep = size * colSpacing
        // Drawn columns: what's needed, but never more than the width holds.
        // Equal to neededCols whenever the text fits; smaller in the forced-min
        // overflow case (trailing cells dropped); and 0 when the box is too
        // narrow for even one column (availW < colStep) — intentionally NOT
        // coerced up to 1, so a degenerate sub-cell-width box renders only its
        // background instead of a glyph spilling past the tracked child bounds.
        val maxCols = floor(availW / colStep).toInt()
        val cols = ceil(g.toDouble() / rows).toInt().coerceAtMost(maxCols)
        return Layout(size, rows, cols, rowStep, colStep)
    }

    /**
     * Split [s] into grapheme clusters (so surrogate pairs / combining marks
     * stay intact — better than `String.toList()`), dropping stray newlines.
     * Spaces are kept: a blank cell is correct word spacing for vertical Korean.
     */
    fun splitGraphemes(s: String): List<String> {
        val cleaned = s.replace("\r\n", "\n").replace("\n", "")
        if (cleaned.isEmpty()) return emptyList()
        val it = BreakIterator.getCharacterInstance()
        it.setText(cleaned)
        val out = ArrayList<String>()
        var start = it.first()
        var end = it.next()
        while (end != BreakIterator.DONE) {
            out.add(cleaned.substring(start, end))
            start = end
            end = it.next()
        }
        return out
    }
}

/**
 * Renders [text] as upright, top-to-bottom, right-to-left columns (CJK
 * tategaki) within its own bounds. Used by [TranslationOverlayView] for
 * vertical OCR boxes when the target language is written vertically
 * ([com.playtranslate.language.targetSupportsVerticalText]).
 *
 * Android has no vertical writing-mode, so glyphs are measured and painted
 * individually (the per-glyph loop is also the hook for Phase-2 polish:
 * UAX#50 glyph orientation and tate-chu-yoko).
 *
 * Like [OutlinedTextView], this view draws **no background** — the caller sets
 * one via [setBackgroundColor]. [View.draw] paints that background before
 * [onDraw], so it composites under the glyphs and is captured by
 * [TranslationOverlayView.renderToOffscreen]'s `drawChild` (required for the
 * pinhole change-detection reference to hold).
 */
internal class VerticalTextView(context: Context) : View(context) {

    var text: String = ""
        set(value) {
            field = value
            cells = VerticalTextLayout.splitGraphemes(value)
            // A canvas-drawing View exposes no semantic text; feed TalkBack.
            contentDescription = value
            invalidate()
        }

    var textColor: Int = Color.WHITE
    var outlineColor: Int = Color.BLACK
    var outlineWidth: Float = 0f

    private var cells: List<String> = emptyList()

    private val density = context.resources.displayMetrics.density
    /** Inset between the filled background edge and the glyph cells (matches
     *  the horizontal path's `textMargin`). */
    private val pad = 3f * density
    private val minTextPx =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 6f, context.resources.displayMetrics)
    private val maxTextPx =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 200f, context.resources.displayMetrics)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (cells.isEmpty() || width <= 0 || height <= 0) return

        val layout = VerticalTextLayout.compute(
            graphemeCount = cells.size,
            width = width.toFloat(), height = height.toFloat(),
            pad = pad, minPx = minTextPx, maxPx = maxTextPx,
        )

        paint.textSize = layout.cellSize
        val fm = paint.fontMetrics
        // Baseline that vertically centres the glyph within its cell.
        val baselineOffset = -(fm.ascent + fm.descent) / 2f
        val rightEdge = width - pad
        val topEdge = pad

        fun cx(index: Int) = rightEdge - (index / layout.rows + 0.5f) * layout.colStep
        fun cy(index: Int) = topEdge + (index % layout.rows + 0.5f) * layout.rowStep + baselineOffset

        // Draw only the cells the layout placed. compute() caps cols to the
        // width, so any cells beyond rows*cols would land outside the box —
        // drawing them would desync pinhole/OCR geometry (see class KDoc).
        val drawCount = minOf(cells.size, layout.rows * layout.cols)

        // Stroke (outline) pass first, then fill, so fills sit atop the stroke.
        if (outlineWidth > 0f) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = outlineWidth
            paint.strokeJoin = Paint.Join.ROUND
            paint.color = outlineColor
            for (i in 0 until drawCount) canvas.drawText(cells[i], cx(i), cy(i), paint)
        }
        paint.style = Paint.Style.FILL
        paint.color = textColor
        for (i in 0 until drawCount) canvas.drawText(cells[i], cx(i), cy(i), paint)
    }
}
