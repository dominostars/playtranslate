package com.playtranslate.ui

/**
 * Which of a results section header's actions stay inline beside its
 * language name, and which fold into the ⋯ overflow menu. Pure, so the rule
 * is pinned on the JVM ([HeaderFitTest]) and [SectionHeaderRow] only applies
 * it.
 *
 * The row reads `[label] [a0] [a1] … [⋯] [pinned…]`: the foldable actions in
 * priority order, then ⋯ while anything is folded, then the pinned actions
 * (the section's eye), which never fold. One [plan] `gap` separates every
 * pair of neighbours, the label included.
 *
 * The rule, in the user's words: fit as many actions as we can; when they
 * don't all fit, the last one that fits gives its slot to ⋯. What stays
 * inline is always a PREFIX of the list (strict order: an action that
 * doesn't fit folds, and so does everything after it, even a narrower one),
 * and the label keeps its full width while any configuration still fits
 * beside it. Only when none does is the label cut, to the narrowest
 * configuration.
 *
 * Consequences the tests pin: with equal-width icons, ⋯ always takes at
 * least two actions (it costs what the last fitting action would have), and
 * a single foldable no wider than ⋯ never gets one; a wider row never folds
 * more or cuts the label shorter; the rule has no direction (RTL mirrors the
 * row, not the plan).
 */
object HeaderFit {

    data class Plan(
        /** How many of the foldable actions stay inline: always the first
         *  [inlineCount] of them. */
        val inlineCount: Int,
        /** Whether ⋯ shows: exactly when some foldable action folded. */
        val showMore: Boolean,
        /** The width the label may take: its natural width unless no
         *  configuration fits beside it. */
        val labelWidth: Int,
    )

    /**
     * @param contentWidth the row's width inside its padding, or null when
     *   the row is measured UNSPECIFIED (nothing to fit against: all inline).
     * @param labelNatural the label's single-line width.
     * @param gap the space between neighbours, label to first item included.
     * @param foldable widths of the AVAILABLE foldable actions, in priority
     *   order (unavailable actions take no part).
     * @param pinned widths of the available pinned actions.
     * @param moreWidth the ⋯ button's width.
     */
    fun plan(
        contentWidth: Int?,
        labelNatural: Int,
        gap: Int,
        foldable: List<Int>,
        pinned: List<Int>,
        moreWidth: Int,
    ): Plan {
        val n = foldable.size
        if (contentWidth == null) return Plan(n, showMore = false, labelWidth = labelNatural)

        // Width of the action side for [k] inline foldables (k == n: no ⋯),
        // counting the gap that separates it from the label.
        fun actionsWidth(k: Int): Int {
            var sum = 0
            var count = 0
            for (i in 0 until k) { sum += foldable[i]; count++ }
            if (k < n) { sum += moreWidth; count++ }
            for (w in pinned) { sum += w; count++ }
            return if (count == 0) 0 else sum + gap * count
        }

        for (k in n downTo 0) {
            if (labelNatural + actionsWidth(k) <= contentWidth) {
                return Plan(k, showMore = k < n, labelWidth = labelNatural)
            }
        }
        // Nothing fits beside the whole name: take the narrowest action side
        // (ties to more inline) and give the label what's left.
        var bestK = n
        var bestW = actionsWidth(n)
        for (k in n - 1 downTo 0) {
            val w = actionsWidth(k)
            if (w < bestW) {
                bestK = k
                bestW = w
            }
        }
        return Plan(
            bestK,
            showMore = bestK < n,
            labelWidth = (contentWidth - bestW).coerceIn(0, labelNatural),
        )
    }
}
