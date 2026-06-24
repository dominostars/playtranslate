package com.playtranslate.ui

/**
 * Pure, Android-free geometry for [CaptureResultOverlay] — the over-game capture
 * panel. Isolated here so the height clamp, the side-by-side breakpoint, and the
 * fling-dismiss decision can be unit-tested without a view or a window.
 */
object CaptureResultGeometry {

    /** Default panel height as a fraction of the display height (top sheet). */
    const val DEFAULT_HEIGHT_FRACTION = 0.40f
    /** Smallest the panel may shrink to (fraction of display height). */
    const val MIN_HEIGHT_FRACTION = 0.20f
    /** Largest the panel may grow to (fraction of display height) — never the
     *  whole screen, so a tap-outside band always remains below it. */
    const val MAX_HEIGHT_FRACTION = 0.90f

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

    /** Default panel height (px) for [screenHeightPx]. */
    fun defaultPanelHeight(screenHeightPx: Int): Int =
        clampPanelHeight((screenHeightPx * DEFAULT_HEIGHT_FRACTION).toInt(), screenHeightPx)

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
     * On release of an upward panel drag, decide whether to dismiss: true if the
     * panel was dragged up past [dismissDistancePx], OR flung up faster than
     * [flingVelThreshold]. [translationY] is negative when dragged up and
     * [velocityY] is negative when flinging up (Android's y-axis points down).
     */
    fun shouldDismissFromDrag(
        translationY: Float,
        velocityY: Float,
        dismissDistancePx: Float,
        flingVelThreshold: Float,
    ): Boolean {
        val draggedFarEnough = translationY <= -dismissDistancePx
        val flungUpFast = velocityY <= -flingVelThreshold
        return draggedFarEnough || flungUpFast
    }
}
