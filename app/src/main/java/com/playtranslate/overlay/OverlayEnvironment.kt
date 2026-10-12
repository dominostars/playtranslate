package com.playtranslate.overlay

import android.content.Context
import android.os.Build
import android.view.Display
import android.view.Surface
import android.view.WindowInsets
import com.playtranslate.DisplayGeometry
import com.playtranslate.displaySizePx
import com.playtranslate.displayWindowMetrics

/** Per-side distances in pixels from the display's edges. */
data class Sides(val left: Int, val top: Int, val right: Int, val bottom: Int) {

    /** The larger of the two on each side: what keeps clear of both. */
    fun max(other: Sides): Sides = Sides(
        maxOf(left, other.left),
        maxOf(top, other.top),
        maxOf(right, other.right),
        maxOf(bottom, other.bottom),
    )

    companion object {
        val NONE = Sides(0, 0, 0, 0)
    }
}

/**
 * What one display offers a window of ours laid out in display pixels
 * ([OverlayHost.applyOverlayLayoutDefaults]: no inset fitting, the cutout
 * entered), read from the display's own state in one query and never from
 * one of our windows. An accessibility overlay lies above the system bars
 * and from Android 12 is never dispatched their sources (Android 11 sends
 * every source to every window), so only the display's state answers the
 * same way on both backends and every version.
 *
 * A surface reads it through a policy, never through the raw bars, because
 * the raw bars mean different things per backend: a window BELOW the bars
 * ([belowBars], the MediaProjection backend's TYPE_APPLICATION_OVERLAY) is
 * covered by a shown bar, while one above them (the accessibility backend)
 * covers the bar instead. Each policy says what it keeps clear of on each.
 *
 * Placement that must follow this goes through [OverlayHost]: a window
 * added as [OverlayPlacement.Placed] gets its layout run against the
 * current environment at its add and again whenever the environment it
 * was laid out with goes stale. Reading [read] directly is for a decision
 * taken once, at one event (the icon's snap at its release, the capture
 * sheet's columns at its build); anything read that way and kept is stale
 * at the next bar toggle or rotation.
 *
 * Below R there is no window-metrics query: every policy is [Sides.NONE]
 * and [insetsKnown] is false, which is what v3.3.1 placed against there
 * (below R the platform still fits our fixed-size windows). From R the query
 * can fail ([displayWindowMetrics]), and then the read is [provisional]: no
 * insets and the coarser read's size. From R the
 * platform no longer fits our windows, so that is not something to lay them
 * out against; [OverlayHost.environment] keeps the display's last real read
 * in its place.
 */
data class OverlayEnvironment(
    val geometry: DisplayGeometry,
    /** The backend's windows lie below the system bars in Z
     *  (TYPE_APPLICATION_OVERLAY): a shown bar covers them. */
    val belowBars: Boolean,
    /** The backend's windows are dispatched the bars: below them, and every
     *  window on Android 11, which sends every source to every window (the
     *  filtering to the windows above came in Android 12). */
    val hearsBars: Boolean,
    /** False when no insets could be read (below R, or a failed query). */
    val insetsKnown: Boolean,
    /** A stand-in for a query that failed from R on: no insets, and the size
     *  of the coarser read ([displaySizePx]'s fallback), which can be wrong
     *  (DisplaySize.kt). Never equal to a real read, so a window laid out
     *  against it is laid out again by the next real one. */
    val provisional: Boolean,
    private val cutout: Sides,
    /** Status and navigation bars the app beneath shows persistently now. A
     *  swipe-revealed (transient) bar reads as hidden in the display's state
     *  (InsetsPolicy.getInsetsForWindowMetrics), so it never counts. */
    private val shownBars: Sides,
    /** The same bars whether shown or not. */
    private val reservedBars: Sides,
) {

    /** What lies over a window of this backend now: the display cutout and,
     *  below the bars, the bars the app beneath shows. On the accessibility
     *  backend the bars lie under our windows and are not counted. For a
     *  placement that follows changes (the icon's dock, the menu card). */
    val covered: Sides get() = if (belowBars) cutout.max(shownBars) else cutout

    /** What the platform's own inset fit kept a fixed-size window of ours
     *  clear of before our windows were laid out in display pixels (v3.3.1):
     *  the cutout and every bar the window is dispatched that is showing.
     *  Below the bars that is [covered]; above them it is the cutout alone on
     *  Android 12 and up, and the shown bars too on Android 11, where the bars
     *  are dispatched to every window. For a surface whose clearance follows
     *  the released behaviour on each version (the region editor's chrome,
     *  which on Android 11 stayed off the navigation buttons). */
    val fitted: Sides get() = if (hearsBars) cutout.max(shownBars) else cutout

    /** [fitted] with the bars counted whether shown or not: the most a
     *  window's own insets can take from it, for a decision made once that a
     *  bar shown later must not invalidate (the capture sheet's one or two
     *  columns, whose body pads itself by its own window's insets,
     *  [com.playtranslate.ui.ContentInsets]). Keyed, as that padding is, on
     *  whether the window is dispatched the bars, not on whether they lie
     *  over it: on Android 11 the accessibility backend's sheet is dispatched
     *  a side bar it lies above, and pads by it. */
    val fittedReserved: Sides get() = if (hearsBars) cutout.max(reservedBars) else cutout

    /** The room every system bar takes, shown or not, and the cutout, on
     *  BOTH backends: the translation-error pills and the no-text pill keep
     *  out of it. Above the bars a pill drawn over one would hide it and
     *  take its touches; below them a bar the game reveals with a swipe would
     *  land on the pill. */
    val barRoom: Sides get() = cutout.max(reservedBars)

    companion object {

        /**
         * The environment of [display], read through [displayContext] (a
         * context of that display), or null when the display reports no size
         * (it went away). One window-metrics query gives the size
         * and every inset, so they always agree; the window context behind it
         * is fresh each time ([displayWindowMetrics]: a cached one reported
         * pre-rotation bounds).
         */
        fun read(displayContext: Context, display: Display?, belowBars: Boolean): OverlayEnvironment? {
            val rotation = display?.rotation ?: Surface.ROTATION_0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val metrics = displayContext.displayWindowMetrics()
                if (metrics != null) {
                    val bounds = metrics.bounds
                    if (bounds.width() <= 0 || bounds.height() <= 0) return null
                    val insets = metrics.windowInsets
                    val bars = WindowInsets.Type.systemBars()
                    val hears = hearsBars(belowBars)
                    return OverlayEnvironment(
                        DisplayGeometry(bounds.width(), bounds.height(), rotation),
                        belowBars,
                        hears,
                        insetsKnown = true,
                        provisional = false,
                        cutout = insets.getInsets(WindowInsets.Type.displayCutout()).toSides(),
                        // Where no window hears the bars, no policy reads which
                        // are shown, and nothing would follow them: kept, they
                        // would make every bar toggle look like a change of
                        // the environment that moves nothing.
                        shownBars = if (hears) insets.getInsets(bars).toSides() else Sides.NONE,
                        reservedBars = insets.getInsetsIgnoringVisibility(bars).toSides(),
                    )
                }
            }
            // Below R there is no window-metrics query: the size comes from
            // the coarser read and nothing is inset, which is what v3.3.1
            // placed against there. From R the query failed: the same, as a
            // stand-in ([provisional]).
            val size = displayContext.displaySizePx()
            if (size.x <= 0 || size.y <= 0) return null
            return OverlayEnvironment(
                DisplayGeometry(size.x, size.y, rotation),
                belowBars,
                hearsBars(belowBars),
                insetsKnown = false,
                provisional = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R,
                cutout = Sides.NONE,
                shownBars = Sides.NONE,
                reservedBars = Sides.NONE,
            )
        }

        /** The environment of the display [context] belongs to (a display
         *  context, an activity, or a wrapper of either): for a one-off
         *  decision by a surface that knows its context but not its display
         *  id (the capture sheet, which may be hosted by an activity on any
         *  screen). */
        fun read(context: Context, belowBars: Boolean): OverlayEnvironment? {
            val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try { context.display } catch (_: UnsupportedOperationException) { null }
            } else null
            return read(context, display, belowBars)
        }

        /** Whether a backend's windows are dispatched the system bars on this
         *  device: below the bars always, above them only on Android 11
         *  ([hearsBars]). */
        fun hearsBars(belowBars: Boolean, sdk: Int = Build.VERSION.SDK_INT): Boolean =
            sdk >= Build.VERSION_CODES.R && (belowBars || sdk == Build.VERSION_CODES.R)

        /** For tests and pure callers: an environment from explicit values. */
        fun of(
            geometry: DisplayGeometry,
            belowBars: Boolean,
            cutout: Sides = Sides.NONE,
            shownBars: Sides = Sides.NONE,
            reservedBars: Sides = shownBars,
            insetsKnown: Boolean = true,
            hearsBars: Boolean = belowBars,
            provisional: Boolean = false,
        ): OverlayEnvironment =
            OverlayEnvironment(geometry, belowBars, hearsBars, insetsKnown, provisional, cutout, shownBars, reservedBars)

        private fun android.graphics.Insets.toSides() = Sides(left, top, right, bottom)
    }
}
