package com.playtranslate.ui

import kotlin.math.roundToInt

/**
 * Pure, Android-free geometry for [CaptureResultOverlay] — the over-game capture
 * panel. Isolated here so the height clamp, the side-by-side breakpoint, the
 * remembered posture, and the swipe-away decision can be unit-tested without a
 * view or a window.
 */
object CaptureResultGeometry {

    /** Smallest the panel may shrink to (fraction of display height); also the
     *  height the panel loads at before it grows to fit the result. */
    const val MIN_HEIGHT_FRACTION = 0.20f
    /** Largest the panel may grow to (fraction of display height) — never the
     *  whole screen, so a tap-outside band always remains below it. */
    const val MAX_HEIGHT_FRACTION = 0.90f
    /** Largest fraction the panel auto-grows to when a result lands (it can still
     *  be dragged taller, up to [MAX_HEIGHT_FRACTION]). */
    const val MAX_AUTO_HEIGHT_FRACTION = 0.50f

    /**
     * Fallback minimum width (dp) one section needs to render side-by-side when
     * the source header can't be measured. The source header alone packs five
     * 36dp buttons + four 16dp gaps = 244dp before the label, so this sits above
     * that hard floor to leave the title room. Prefer the measured header min
     * width at the call site; this is the guard for when it's unavailable.
     */
    const val SIDE_BY_SIDE_FALLBACK_SECTION_DP = 300

    /**
     * Clamp a desired panel height (px) to [[minFraction], [maxFraction]] of
     * [screenHeightPx]. Used for the initial 40% height and every resize drag.
     */
    fun clampPanelHeight(
        desiredPx: Int,
        screenHeightPx: Int,
        minFraction: Float = MIN_HEIGHT_FRACTION,
        maxFraction: Float = MAX_HEIGHT_FRACTION,
    ): Int {
        if (screenHeightPx <= 0) return desiredPx.coerceAtLeast(0)
        val minPx = (screenHeightPx * minFraction).toInt()
        val maxPx = (screenHeightPx * maxFraction).toInt()
        return desiredPx.coerceIn(minPx, maxPx.coerceAtLeast(minPx))
    }

    /** Smallest panel height (px) — the drag-resize floor; used while loading. */
    fun minPanelHeight(screenHeightPx: Int): Int =
        (screenHeightPx * MIN_HEIGHT_FRACTION).toInt()

    /** Tallest panel height (px) — the drag-resize ceiling, and the auto-size
     *  ceiling for a sheet the user pulled all the way up to its content. */
    fun maxPanelHeight(screenHeightPx: Int): Int =
        (screenHeightPx * MAX_HEIGHT_FRACTION).toInt()

    /** Default auto-size ceiling — [MAX_AUTO_HEIGHT_FRACTION] of the display. */
    fun autoMaxHeight(screenHeightPx: Int): Int =
        (screenHeightPx * MAX_AUTO_HEIGHT_FRACTION).toInt()

    /** Panel height that shows [contentHeightPx] of content, floored at
     *  [MIN_HEIGHT_FRACTION] of the screen and capped at [maxPx] — normally
     *  [autoMaxHeight], but the user's dragged height once they've resized, so a
     *  re-fit neither shrinks below nor auto-grows past it. Drives the grow-to-fit
     *  animation. */
    fun autoPanelHeight(contentHeightPx: Int, screenHeightPx: Int, maxPx: Int): Int {
        if (screenHeightPx <= 0) return contentHeightPx.coerceAtLeast(0)
        val floor = (screenHeightPx * MIN_HEIGHT_FRACTION).toInt()
        return contentHeightPx.coerceIn(floor, maxPx.coerceAtLeast(floor))
    }

    // ── Remembered posture ("it opens how you left it") ─────────────────
    // One persisted float per flow: the auto-size CEILING the user's last drag
    // left behind, as a fraction of the display — deliberately not the height
    // the sheet happened to settle at, so a session that never touched the
    // sheet can't ratchet the next one down to whatever result was on screen.
    // Two sentinels sit outside the fraction range: nothing recorded yet, and
    // parked in the collapsed sliver.

    /** Nothing recorded (the default): auto-size to [MAX_AUTO_HEIGHT_FRACTION]. */
    const val NO_POSTURE = 0f

    /** Left parked in the collapsed sliver — the next flow starts there. */
    const val COLLAPSED_POSTURE = -1f

    /** True when [posture] parks the sheet in its collapsed sliver. */
    fun isCollapsedPosture(posture: Float): Boolean = posture < 0f

    /**
     * The auto-size ceiling [posture] implies on a [screenHeightPx] display:
     * the remembered height, or the default [autoMaxHeight] when nothing was
     * recorded — the collapsed sliver included, since its reading height was
     * never chosen. A ceiling only ever caps: the sheet still grows to no more
     * than the content needs (see [autoPanelHeight]).
     */
    fun postureCeiling(posture: Float, screenHeightPx: Int): Int {
        if (posture <= NO_POSTURE) return autoMaxHeight(screenHeightPx)
        // ROUND, don't truncate: px → fraction → px runs on every open/dismiss
        // cycle, and float error lands the product a hair either side of the
        // original pixel. Truncating would take the low side every time and walk
        // the remembered height down a pixel per capture.
        return (screenHeightPx * posture).roundToInt()
            .coerceIn(minPanelHeight(screenHeightPx), maxPanelHeight(screenHeightPx))
    }

    /**
     * The posture to persist for a sheet whose auto-size ceiling is [ceilingPx]
     * — or [COLLAPSED_POSTURE] when it rests in the sliver, whose own height is
     * a fixed strip rather than a fraction of any particular display.
     */
    fun postureFor(ceilingPx: Int, screenHeightPx: Int, collapsed: Boolean): Float {
        if (collapsed) return COLLAPSED_POSTURE
        if (screenHeightPx <= 0) return NO_POSTURE
        return (ceilingPx.toFloat() / screenHeightPx)
            .coerceIn(MIN_HEIGHT_FRACTION, MAX_HEIGHT_FRACTION)
    }

    /**
     * True when the panel is wide enough to show source | divider | target
     * side-by-side: each column gets `(panelWidthPx - dividerPx) / 2`, which must
     * be at least [perSectionMinPx]. Below that, the caller stacks them
     * vertically in one scroll view.
     */
    fun shouldUseSideBySide(panelWidthPx: Int, dividerPx: Int, perSectionMinPx: Int): Boolean {
        if (panelWidthPx <= 0) return false
        val perSection = (panelWidthPx - dividerPx) / 2
        return perSection >= perSectionMinPx
    }

    /**
     * On release of a panel drag, decide whether to throw the sheet away. Both
     * inputs are measured in the AWAY direction — how far the sheet has been
     * pushed toward the edge it exits by, and how fast it was moving there — so
     * the caller mirrors them for a top sheet and passes them straight through
     * for a bottom one.
     *
     * Dismiss when the sheet was pushed past [dismissDistancePx], or thrown
     * faster than [flingVelThreshold] AFTER clearing [flingDistancePx]. Speed
     * alone deliberately does not qualify: a quick drag down to minimize the
     * sheet is fast at release too, and reading that as a swipe-away dismissed
     * panels the user meant to keep.
     */
    fun shouldDismissFromDrag(
        draggedAwayPx: Float,
        velocityAwayPx: Float,
        dismissDistancePx: Float,
        flingDistancePx: Float,
        flingVelThreshold: Float,
    ): Boolean {
        if (draggedAwayPx <= 0f) return false
        if (draggedAwayPx >= dismissDistancePx) return true
        return velocityAwayPx >= flingVelThreshold && draggedAwayPx >= flingDistancePx
    }
}
