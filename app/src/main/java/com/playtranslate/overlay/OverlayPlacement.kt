package com.playtranslate.overlay

import android.view.WindowManager

/**
 * How a window added through [OverlayHost.addOverlayWindow] is placed on
 * its display, and so what the host does for it when the display's
 * [OverlayEnvironment] changes. Every add states one: it is the census of
 * what each window keeps clear of and what re-places it.
 */
sealed interface OverlayPlacement {

    /** Covers the whole display (MATCH_PARENT) and draws in capture pixels,
     *  clearing nothing: the translation overlays, the app's boxes, the
     *  region indicator and drag view, the OCR debug overlay. The host pins
     *  it to the display's size and re-pins it when that changes. */
    data object CaptureSpace : OverlayPlacement

    /** Sized to the display by its owner, or MATCH_PARENT (then pinned like
     *  [CaptureSpace]); its CONTENT keeps clear of bars and cutout through
     *  the insets its own window is dispatched, which the platform re-sends
     *  on every change on both backends and below R: the capture sheet, the
     *  workspace, an alert's scrim, the word lens. The host does not run
     *  anything for it besides the pin. */
    data object Content : OverlayPlacement

    /**
     * Positioned from the environment: the host runs [layout] against the
     * current environment before the window is added, and again whenever
     * the environment the window was last laid out with is no longer the
     * display's, or someone it derives from moved
     * ([OverlayHost.invalidateLayout]). [layout] writes the window's params
     * (the host commits them when they changed) and may also place views
     * inside the window.
     *
     * A layout must be a function of the environment and of the live state
     * of the windows it derives from, read when it runs: the host repeats
     * the pass while anything moved, so a window laid out before the one it
     * derives from is corrected on the next pass. A layout whose work is
     * view-level (which the host's params comparison cannot see) compares
     * its inputs with the last ones it applied and does nothing when they
     * are equal: passes run on events unrelated to it.
     *
     * The window's root view must not set its own OnApplyWindowInsetsListener:
     * the host sets one to hear the bars change (below the bars) and passes
     * the dispatch on to the view's [android.view.View.onApplyWindowInsets],
     * which a view that needs the insets overrides instead.
     */
    class Placed(
        val layout: (OverlayEnvironment, WindowManager.LayoutParams) -> Unit,
    ) : OverlayPlacement

    /** Never moves and keeps the platform's own layout, as every
     *  fixed-size window had before the display-origin contract: the 1x1
     *  touch sentinel, and the WordLookupPopup's overlay add (dead: nothing
     *  shows that popup in overlay mode). */
    data object Fixed : OverlayPlacement
}
