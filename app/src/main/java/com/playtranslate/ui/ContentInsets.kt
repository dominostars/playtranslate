package com.playtranslate.ui

import android.os.Build
import android.view.WindowInsets
import com.playtranslate.overlay.Sides

/**
 * What a full-screen content window keeps its content clear of (the capture
 * sheet's body, the workspace's card), from the insets its OWN window is
 * dispatched: such a window spans the display edge to edge
 * ([com.playtranslate.overlay.OverlayPlacement.Content]), so its content
 * steps aside from what its own window is dispatched. The platform re-sends
 * these on every change, on both backends and below R. From Android 12 only
 * the sources of the windows above ours reach it: below the bars
 * (MediaProjection) a shown bar counts, above them (accessibility) only the
 * cutout does. Android 11 sends every source to every window, so there the
 * accessibility backend's window pads by the bars too (as the bottom did in
 * v3.3.1); a decision that must agree with this padding reads
 * [com.playtranslate.overlay.OverlayEnvironment.fittedReserved]. One
 * derivation for both windows, so the sheet and the workspace cannot drift
 * apart again.
 *
 * [top], [left], [right] and [bottom] are the bars that are showing and the
 * display cutout; [imeLift] the keyboard's height while it is up (the window
 * is FLAG_LAYOUT_NO_LIMITS, so the platform's ADJUST_RESIZE never applies and
 * the content lifts itself). The cutout counts on every side: Android puts
 * one only on a short edge, which is the bottom of a phone held upside down.
 * Below R only v3.3.1's arithmetic exists, plus the bottom cutout: the bottom
 * bar and the keyboard told apart by the stable insets, and the top; no
 * sides, since a window there keeps the platform's own layout.
 */
internal data class ContentInsets(
    val top: Int,
    val left: Int,
    val right: Int,
    val bottom: Int,
    val imeLift: Int,
) {
    companion object {

        fun of(insets: WindowInsets): ContentInsets =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val nav = insets.getInsets(WindowInsets.Type.navigationBars())
                val cutout = insets.getInsets(WindowInsets.Type.displayCutout())
                ofShown(
                    nav = Sides(nav.left, nav.top, nav.right, nav.bottom),
                    navShown = insets.isVisible(WindowInsets.Type.navigationBars()),
                    statusTop = insets.getInsets(WindowInsets.Type.statusBars()).top,
                    statusShown = insets.isVisible(WindowInsets.Type.statusBars()),
                    cutout = Sides(cutout.left, cutout.top, cutout.right, cutout.bottom),
                    ime = insets.getInsets(WindowInsets.Type.ime()).bottom,
                    imeShown = insets.isVisible(WindowInsets.Type.ime()),
                )
            } else {
                ofLegacyInsets(insets)
            }

        /** From R: per-type insets with their visibility. A phone moves
         *  three-button navigation to a side in landscape, so the sides take
         *  a shown navigation bar; the cutout obscures pixels even with the
         *  bars hidden, so it always counts, on every side. */
        fun ofShown(
            nav: Sides,
            navShown: Boolean,
            statusTop: Int,
            statusShown: Boolean,
            cutout: Sides,
            ime: Int,
            imeShown: Boolean,
        ): ContentInsets = ContentInsets(
            top = maxOf(if (statusShown) statusTop else 0, cutout.top),
            left = maxOf(if (navShown) nav.left else 0, cutout.left),
            right = maxOf(if (navShown) nav.right else 0, cutout.right),
            bottom = maxOf(if (navShown) nav.bottom else 0, cutout.bottom),
            imeLift = if (imeShown) ime else 0,
        )

        /** API 29: the deprecated insets are the only signal. The stable
         *  inset is the bar's reserved height (keyboard-independent); the
         *  system-window inset is what is consumed now: 0 with the bars
         *  hidden, the bar's height with them up, the keyboard's height while
         *  typing. Their minimum isolates the shown bar; anything past the
         *  stable inset is the keyboard. The top mirrors it with no keyboard
         *  term; both take the cutout on their edge. */
        fun ofLegacy(
            currentBottom: Int,
            stableBottom: Int,
            currentTop: Int,
            stableTop: Int,
            cutoutTop: Int,
            cutoutBottom: Int,
        ): ContentInsets = ContentInsets(
            top = maxOf(minOf(currentTop, stableTop), cutoutTop),
            left = 0,
            right = 0,
            bottom = maxOf(minOf(currentBottom, stableBottom), cutoutBottom),
            imeLift = if (currentBottom > stableBottom) currentBottom else 0,
        )

        @Suppress("DEPRECATION") // API 29 has no per-type insets to read instead.
        private fun ofLegacyInsets(insets: WindowInsets): ContentInsets = ofLegacy(
            currentBottom = insets.systemWindowInsetBottom,
            stableBottom = insets.stableInsetBottom,
            currentTop = insets.systemWindowInsetTop,
            stableTop = insets.stableInsetTop,
            cutoutTop = insets.displayCutout?.safeInsetTop ?: 0,
            cutoutBottom = insets.displayCutout?.safeInsetBottom ?: 0,
        )
    }
}
