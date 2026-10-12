package com.playtranslate.ui

import com.playtranslate.overlay.Sides

/**
 * Where the floating icon's window goes for a dock slot, as pure geometry.
 *
 * A slot is an edge plus a fraction of the vertical range, the form the
 * persisted position takes. The window is a [place]'s `viewSize` square whose
 * CENTRE sits on the dock line, so half of it lies past the line: off the
 * display, or over a bar the line is inboard of. [FloatingOverlayIcon] draws
 * its disc clipped at that line, so the visible part is the same edge slice
 * wherever the line is. The dock line is the display edge moved inboard by
 * that side's inset ([Sides]): a navigation bar the app beneath shows persistently,
 * or a display cutout. The top and bottom edges have dock lines of the same
 * kind, moved inboard by that side's inset (a status or navigation bar the
 * window lies below, a cutout), and the centre keeps `gestureInset` inside
 * them, so the whole disc clears a bar on that edge by the margin it keeps
 * from a bare edge. Until 2026-10-11 the bar and the clearance were taken as
 * alternatives (the larger of the two), which on a phone whose bar is as
 * tall as the clearance put the centre on the bar's edge and the disc's
 * lower half under the bar at the lowest slot (Codex adversarial review).
 */
object IconDock {

    /** Window origin (the top-left of the `viewSize` square). */
    data class Place(val x: Int, val y: Int)

    /** The centre's vertical range: `first` at the top, `last` at the bottom,
     *  each `gestureInset` inside that edge's dock line (the edge moved
     *  inboard by its inset). Collapses to a single value when the screen is
     *  shorter than the two clearances together. */
    fun verticalRange(screenH: Int, insets: Sides, gestureInset: Int): IntRange {
        val min = insets.top + gestureInset
        val max = screenH - insets.bottom - gestureInset
        return if (max < min) min..min else min..max
    }

    /** The centre y for [fraction] of the range, 0 at the top, 1 at the bottom.
     *  A fraction outside 0..1 (unset or corrupt) reads as the middle. */
    fun centreY(screenH: Int, insets: Sides, gestureInset: Int, fraction: Float): Int {
        val range = verticalRange(screenH, insets, gestureInset)
        val f = if (fraction in 0f..1f) fraction else 0.5f
        return (range.first + f * (range.last - range.first)).toInt().coerceIn(range)
    }

    /** The fraction a centre y lands on, the inverse of [centreY]; 0.5 when
     *  the range has collapsed. */
    fun fraction(centreY: Int, screenH: Int, insets: Sides, gestureInset: Int): Float {
        val range = verticalRange(screenH, insets, gestureInset)
        val span = range.last - range.first
        if (span <= 0) return 0.5f
        return ((centreY - range.first).toFloat() / span).coerceIn(0f, 1f)
    }

    /** The dock line's x for an edge: the display edge moved inboard by that
     *  side's inset. */
    fun dockX(screenW: Int, insets: Sides, edgeLeft: Boolean): Int =
        if (edgeLeft) insets.left else screenW - insets.right

    fun place(
        screenW: Int,
        screenH: Int,
        insets: Sides,
        edgeLeft: Boolean,
        fraction: Float,
        viewSize: Int,
        gestureInset: Int,
    ): Place {
        val half = viewSize / 2
        return Place(
            x = dockX(screenW, insets, edgeLeft) - half,
            y = centreY(screenH, insets, gestureInset, fraction) - half,
        )
    }
}
