package com.playtranslate.ui

import android.view.Gravity
import com.playtranslate.overlay.Sides

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

    /** Where the floating-icon editor's two chrome windows go. [gravity] is
     *  their shared TOP or BOTTOM | CENTER_HORIZONTAL, [x] the gravity's
     *  horizontal offset, [barY] and [pillY] each window's distance from that
     *  edge. */
    data class Placement(val gravity: Int, val x: Int, val barY: Int, val pillY: Int)

    /**
     * The floating-icon editor's chrome on a [screenW]x[screenH] display,
     * kept clear of [covered] on each side (its windows are laid out in
     * display pixels): the stack sits inside the covered edge,
     * centred in the width the sides leave clear, on the edge [edgeFor]
     * picks for [region]. The region is in fractions of the whole display
     * (the drag view covers all of it), so the chrome's reach is measured in
     * the same space: from the display's edge, through the covered inset.
     */
    fun place(
        screenW: Int,
        screenH: Int,
        covered: Sides,
        pillHeightPx: Int,
        barHeightPx: Int,
        dp: Float,
        region: ClosedFloatingPointRange<Float>?,
    ): Placement {
        val depth = stackDepthPx(pillHeightPx, barHeightPx, dp)
        val h = screenH.toFloat().coerceAtLeast(1f)
        val edge = edgeFor(
            region,
            topChromeEnd = (covered.top + depth) / h,
            bottomChromeStart = (screenH - covered.bottom - depth) / h,
        )
        val inset = if (edge == Edge.TOP) covered.top else covered.bottom
        return Placement(
            gravity = edge.gravity or Gravity.CENTER_HORIZONTAL,
            x = (covered.left - covered.right) / 2,
            barY = barOffsetPx(pillHeightPx, dp) + inset,
            pillY = pillOffsetPx(dp) + inset,
        )
    }
}
