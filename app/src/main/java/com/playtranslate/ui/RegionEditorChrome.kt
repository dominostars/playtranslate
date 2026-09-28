package com.playtranslate.ui

import android.view.Gravity

/**
 * Where the region editors put their chrome: the instruction pill and the
 * ✕/🗑/✓ bar, stacked on one edge with the pill outermost. Shared by the
 * floating-icon editor ([com.playtranslate.RegionOverlayController.showRegionEditor])
 * and the camera / Import file crop editor
 * ([com.playtranslate.camera.CameraRegionUi.showEditor]).
 */
object RegionEditorChrome {

    enum class Edge(val gravity: Int) { TOP(Gravity.TOP), BOTTOM(Gravity.BOTTOM) }

    /** The pill's distance from its edge. */
    private const val PILL_MARGIN_DP = 16

    /** The space between the pill and the bar. */
    private const val STACK_GAP_DP = 8

    fun pillOffsetPx(dp: Float): Int = (PILL_MARGIN_DP * dp).toInt()

    /** The bar's distance from its edge: past the pill. */
    fun barOffsetPx(pillHeightPx: Int, dp: Float): Int =
        pillOffsetPx(dp) + pillHeightPx + (STACK_GAP_DP * dp).toInt()

    /** How far the whole stack reaches in from its edge. */
    fun stackDepthPx(pillHeightPx: Int, barHeightPx: Int, dp: Float): Int =
        barOffsetPx(pillHeightPx, dp) + barHeightPx

    /**
     * The chrome's edge, decided when the editor opens (from the region it
     * opens on) and again at each drag's end (from the box as it now stands).
     * Top, unless the [region] (its top..bottom as fractions of the editor's
     * height; null when the editor opens with none) reaches under the chrome
     * on the top edge and not under it on the bottom edge: then bottom. A
     * region under both keeps the top. [topChromeEnd] is where the chrome
     * would end on the top edge and [bottomChromeStart] where it would begin
     * on the bottom edge, as fractions of the same height.
     */
    fun edgeFor(
        region: ClosedFloatingPointRange<Float>?,
        topChromeEnd: Float,
        bottomChromeStart: Float,
    ): Edge {
        if (region == null) return Edge.TOP
        val underTop = region.start < topChromeEnd
        val underBottom = region.endInclusive > bottomChromeStart
        return if (underTop && !underBottom) Edge.BOTTOM else Edge.TOP
    }
}
