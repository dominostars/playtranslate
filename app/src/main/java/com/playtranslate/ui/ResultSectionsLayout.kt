package com.playtranslate.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import com.playtranslate.R
import com.playtranslate.themeColor
import kotlin.math.max

/**
 * The results sections' one layout, on every surface that shows them (the
 * in-app results page, the floating workspace's Sentence tab, the over-game
 * capture sheet). Its direct children are each section's HEADER and CARD, as
 * the shared `<merge>` layouts produce them (section_source.xml,
 * section_target.xml, section_ask.xml, section_words.xml), found by id; it
 * lays out whichever of them it was given, then any other child full width
 * beneath, in child order (the page's Clear row).
 *
 * Two arrangements ([Arrangement]):
 *  - STACKED: Translation, Original, Ask, Words, each a header over its
 *    card, full width.
 *  - PAIRED: Original and Translation share the top (two header cells over
 *    two cards, a hairline between); Ask and Words follow beneath at full
 *    width. Only that pair ever shares a row.
 *
 * "The eye removes the card, never the header": a section is hidden exactly
 * when its card is GONE, which its binder writes, and this layout derives
 * everything else from that. A hidden section keeps its header row. In the
 * pair, a hidden section's header shrinks to a fixed cell and the other
 * card takes the full width under both headers; with both hidden the two
 * headers share the row and there is no card row
 * ([ResultSectionsGeometry.pairedRow]). A page that shows the pair with no
 * headers at all (the compact page) pairs the two cards alone while both
 * are shown, and stacks whichever is left when one is hidden.
 *
 * This layout is the ONE owner of which arrangement applies. A page leaves
 * [fixedArrangement] null and the arrangement follows the width on every
 * measure ([CaptureResultGeometry.shouldUseSideBySide] on the layout's full
 * width, padding included), so a rotation or a resized window re-arranges
 * the sections with no help from the host. The capture sheet decides once
 * when it is shown (its width's side insets come and go under it) and sets
 * [fixedArrangement]. Either way the fit code reads [arrangement] back from
 * here.
 *
 * Paired cards are given one height, the taller's (a card's own minimum
 * height counts, which is how the sheet stretches them to its body). Each
 * header is measured exactly once per pass, at its final width:
 * [SectionHeaderRow] plans its fold while it measures.
 */
class ResultSectionsLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ViewGroup(context, attrs) {

    enum class Arrangement { STACKED, PAIRED }

    /** The arrangement a host has decided for this layout's life, or null
     *  to follow the width (class doc). */
    var fixedArrangement: Arrangement? = null
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    /** The arrangement of the last measure pass. */
    var arrangement: Arrangement = Arrangement.STACKED
        private set

    /** Whether the top row's header starts at this layout's top edge, its
     *  own top padding given up: for a host that puts the sections straight
     *  under a bar of its own (the detached window), where that padding is a
     *  gap under the bar. The pair's two headers share the top row and both
     *  give theirs up. The headers further down keep their padding, which
     *  is their distance from the card above. */
    var flushTop: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    /** Whether a hairline rules each stacked section off from the one above
     *  it: for a host that shows the sections without their cards' frames
     *  (the detached window's compact page), where nothing else says where
     *  one ends and the next begins. A section starts at its header, or at
     *  its card when the header is gone. Stacked only. */
    var sectionRules: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    /** The y of each rule of the last measure ([sectionRules]). */
    private val rules = ArrayList<Int>()

    private val density = resources.displayMetrics.density
    private val dividerPx = max(1, (ResultSectionsGeometry.DIVIDER_DP * density).toInt())
    private val dividerMarginPx = (ResultSectionsGeometry.DIVIDER_MARGIN_DP * density).toInt()
    private val hiddenCellPx = (ResultSectionsGeometry.HIDDEN_HEADER_CELL_DP * density).toInt()
    private val minSectionPx =
        (CaptureResultGeometry.SIDE_BY_SIDE_FALLBACK_SECTION_DP * density).toInt()
    private val dividerPaint = Paint().apply { color = context.themeColor(R.attr.ptDivider) }

    /** Where the last measure put a child: its start edge from the padded
     *  start, and its top from this view's top. */
    private class Placement(val view: View, val start: Int, val top: Int)

    /** A hairline of the last measure, START-relative like [Placement]. */
    private class Divider(val start: Int, val top: Int, val bottom: Int)

    private val placements = ArrayList<Placement>()
    private val dividers = ArrayList<Divider>()

    init {
        // The pair's hairlines are drawn here, not by child views.
        setWillNotDraw(false)
    }

    override fun generateLayoutParams(attrs: AttributeSet?): LayoutParams =
        MarginLayoutParams(context, attrs)

    override fun generateLayoutParams(p: LayoutParams?): LayoutParams = MarginLayoutParams(p)

    override fun generateDefaultLayoutParams(): LayoutParams =
        MarginLayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)

    override fun checkLayoutParams(p: LayoutParams?): Boolean = p is MarginLayoutParams

    override fun shouldDelayChildPressedState(): Boolean = false

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val inner = (width - paddingLeft - paddingRight).coerceAtLeast(0)
        placements.clear()
        dividers.clear()
        rules.clear()

        val sourceHeader = shownChild(R.id.sourceHeaderRow)
        val targetHeader = shownChild(R.id.targetHeaderRow)
        val sourceCard = shownChild(R.id.cardOriginal)
        val targetCard = shownChild(R.id.cardTranslation)

        // Only a pair can be paired: a host that gave one of the two
        // sections gets it stacked whatever the width. A page that shows the
        // two without their headers (the compact page) pairs their cards,
        // while both are shown: with no header cell to stand for a hidden
        // one, the other simply takes the row.
        val headless = sourceHeader == null && targetHeader == null
        val canPair = (sourceHeader != null && targetHeader != null) ||
            (headless && sourceCard != null && targetCard != null)
        arrangement = when {
            !canPair -> Arrangement.STACKED
            else -> fixedArrangement
                ?: if (CaptureResultGeometry.shouldUseSideBySide(width, dividerPx, minSectionPx)) {
                    Arrangement.PAIRED
                } else {
                    Arrangement.STACKED
                }
        }

        val askHeader = shownChild(R.id.askHeaderRow)
        val askCard = shownChild(R.id.cardAsk)
        val wordsHeader = shownChild(R.id.wordsHeaderRow)
        val wordsCard = shownChild(R.id.cardWords)
        val paired = arrangement == Arrangement.PAIRED && sourceHeader != null && targetHeader != null
        val pairedCards = arrangement == Arrangement.PAIRED && headless

        // The top row begins above the top edge by its header's own top
        // padding when the host asked for a flush top ([flushTop]): the
        // padding is empty, so nothing drawn is cut.
        var y = paddingTop
        if (flushTop) {
            y -= if (paired) {
                minOf(sourceHeader!!.paddingTop, targetHeader!!.paddingTop)
            } else if (pairedCards) {
                0
            } else {
                // Stacked, the first child placed: a header. (A card that
                // came first has no padding to give: the cards are
                // CardViews, which hold none of their own.)
                listOfNotNull(
                    targetHeader, targetCard, sourceHeader, sourceCard,
                    askHeader, askCard, wordsHeader, wordsCard,
                ).firstOrNull()?.paddingTop ?: 0
            }
        }
        val top = y
        // A rule where a section starts under another ([sectionRules]).
        fun ruleAbove(header: View?, card: View?) {
            if (sectionRules && (header ?: card) != null && y > top) rules += y
        }
        if (paired) {
            y = measurePair(inner, y, sourceHeader!!, sourceCard, targetHeader!!, targetCard)
        } else if (pairedCards) {
            y = measureCardPair(inner, y, sourceCard!!, targetCard!!)
        } else {
            // Translation above Original: the stacked reading order.
            y = stack(targetHeader, inner, y)
            y = stack(targetCard, inner, y)
            ruleAbove(sourceHeader, sourceCard)
            y = stack(sourceHeader, inner, y)
            y = stack(sourceCard, inner, y)
        }
        ruleAbove(askHeader, askCard)
        y = stack(askHeader, inner, y)
        y = stack(askCard, inner, y)
        ruleAbove(wordsHeader, wordsCard)
        y = stack(wordsHeader, inner, y)
        y = stack(wordsCard, inner, y)
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.id !in SECTION_IDS) y = stack(child.takeIf { it.visibility != GONE }, inner, y)
        }

        setMeasuredDimension(width, resolveSize(y + paddingBottom, heightMeasureSpec))
    }

    /** The direct child with [id], unless it is absent or GONE. */
    private fun shownChild(id: Int): View? {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.id == id) return child.takeIf { it.visibility != GONE }
        }
        return null
    }

    /** Measure [child] across [inner] and place it at [y]; returns the y
     *  beneath it ([y] itself for no child). */
    private fun stack(child: View?, inner: Int, y: Int): Int {
        child ?: return y
        val lp = child.layoutParams as MarginLayoutParams
        measureAt(child, inner)
        placements += Placement(child, lp.marginStart, y + lp.topMargin)
        return y + lp.topMargin + child.measuredHeight + lp.bottomMargin
    }

    /** Measure [child] to fill a cell [cellWidth] wide, at the height its
     *  params ask for, or exactly [exactOuterHeight] when given. */
    private fun measureAt(child: View, cellWidth: Int, exactOuterHeight: Int = -1) {
        val lp = child.layoutParams as MarginLayoutParams
        val w = (cellWidth - lp.marginStart - lp.marginEnd).coerceAtLeast(0)
        val heightSpec =
            if (exactOuterHeight >= 0) {
                MeasureSpec.makeMeasureSpec(
                    (exactOuterHeight - lp.topMargin - lp.bottomMargin).coerceAtLeast(0),
                    MeasureSpec.EXACTLY,
                )
            } else {
                getChildMeasureSpec(
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), 0, lp.height,
                )
            }
        child.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), heightSpec)
    }

    private fun outerHeight(child: View): Int {
        val lp = child.layoutParams as MarginLayoutParams
        return lp.topMargin + child.measuredHeight + lp.bottomMargin
    }

    /** The paired top block from [top]: the header row, then the card row
     *  of whichever cards are shown. Returns the y beneath it. */
    private fun measurePair(
        inner: Int,
        top: Int,
        sourceHeader: View,
        sourceCard: View?,
        targetHeader: View,
        targetCard: View?,
    ): Int {
        val row = ResultSectionsGeometry.pairedRow(
            inner, dividerPx, dividerMarginPx, hiddenCellPx,
            sourceHidden = sourceCard == null, targetHidden = targetCard == null,
        )
        measureAt(sourceHeader, row.sourceHeader.width)
        measureAt(targetHeader, row.targetHeader.width)
        val headerHeight = max(outerHeight(sourceHeader), outerHeight(targetHeader))
        place(sourceHeader, row.sourceHeader, top)
        place(targetHeader, row.targetHeader, top)
        val cardTop = top + headerHeight

        var cardHeight = 0
        val sourceSpan = row.sourceCard
        val targetSpan = row.targetCard
        if (sourceCard != null && sourceSpan != null) {
            measureAt(sourceCard, sourceSpan.width)
            cardHeight = outerHeight(sourceCard)
        }
        if (targetCard != null && targetSpan != null) {
            measureAt(targetCard, targetSpan.width)
            cardHeight = max(cardHeight, outerHeight(targetCard))
        }
        // Two cards share one height: the shorter is measured again at the
        // taller's, so both fills end on one line.
        if (sourceCard != null && sourceSpan != null && targetCard != null && targetSpan != null) {
            if (outerHeight(sourceCard) < cardHeight) measureAt(sourceCard, sourceSpan.width, cardHeight)
            if (outerHeight(targetCard) < cardHeight) measureAt(targetCard, targetSpan.width, cardHeight)
        }
        if (sourceCard != null && sourceSpan != null) place(sourceCard, sourceSpan, cardTop)
        if (targetCard != null && targetSpan != null) place(targetCard, targetSpan, cardTop)

        // The hairline between the header cells spans the headers' content
        // (their paddings stay clear). While both cards are shown it runs on
        // down between them, as one line, to a margin above their bottom.
        val cardDivider = row.cardDividerStart
        val headerDividerTop = top + sourceHeader.paddingTop
        if (cardDivider != null) {
            dividers += Divider(row.headerDividerStart, headerDividerTop, cardTop)
            dividers += Divider(cardDivider, cardTop, cardTop + cardHeight - dividerMarginPx)
        } else {
            dividers += Divider(
                row.headerDividerStart, headerDividerTop, cardTop - sourceHeader.paddingBottom,
            )
        }
        return cardTop + cardHeight
    }

    /** The pair without headers, from [top]: the two cards side by side in
     *  the pair's own cells, one height, the hairline between them. Returns
     *  the y beneath. */
    private fun measureCardPair(inner: Int, top: Int, sourceCard: View, targetCard: View): Int {
        val row = ResultSectionsGeometry.pairedRow(
            inner, dividerPx, dividerMarginPx, hiddenCellPx, sourceHidden = false, targetHidden = false,
        )
        val sourceSpan = row.sourceCard ?: return top
        val targetSpan = row.targetCard ?: return top
        measureAt(sourceCard, sourceSpan.width)
        measureAt(targetCard, targetSpan.width)
        val cardHeight = max(outerHeight(sourceCard), outerHeight(targetCard))
        if (outerHeight(sourceCard) < cardHeight) measureAt(sourceCard, sourceSpan.width, cardHeight)
        if (outerHeight(targetCard) < cardHeight) measureAt(targetCard, targetSpan.width, cardHeight)
        place(sourceCard, sourceSpan, top)
        place(targetCard, targetSpan, top)
        row.cardDividerStart?.let {
            dividers += Divider(it, top + dividerMarginPx, top + cardHeight - dividerMarginPx)
        }
        return top + cardHeight
    }

    private fun place(child: View, span: ResultSectionsGeometry.Span, top: Int) {
        val lp = child.layoutParams as MarginLayoutParams
        placements += Placement(child, span.start + lp.marginStart, top + lp.topMargin)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        val width = r - l
        for (p in placements) {
            val w = p.view.measuredWidth
            val left = if (rtl) width - paddingRight - p.start - w else paddingLeft + p.start
            p.view.layout(left, p.top, left + w, p.top + p.view.measuredHeight)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        for (d in dividers) {
            if (d.bottom <= d.top) continue
            val left = if (rtl) width - paddingRight - d.start - dividerPx else paddingLeft + d.start
            canvas.drawRect(
                left.toFloat(), d.top.toFloat(), (left + dividerPx).toFloat(), d.bottom.toFloat(),
                dividerPaint,
            )
        }
        for (ruleY in rules) {
            canvas.drawRect(
                paddingLeft.toFloat(), ruleY.toFloat(),
                (width - paddingRight).toFloat(), (ruleY + dividerPx).toFloat(),
                dividerPaint,
            )
        }
    }

    /** The rules of the last measure, for a test. */
    internal fun rulesForTest(): List<Int> = rules.toList()

    private companion object {
        /** The sections' headers and cards, which this layout arranges by
         *  role; every other child stacks beneath them. */
        val SECTION_IDS = setOf(
            R.id.sourceHeaderRow, R.id.cardOriginal,
            R.id.targetHeaderRow, R.id.cardTranslation,
            R.id.askHeaderRow, R.id.cardAsk,
            R.id.wordsHeaderRow, R.id.cardWords,
        )
    }
}
