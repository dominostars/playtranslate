package com.playtranslate

/**
 * The form a display's floating controls take: the floating icon, or the
 * detached window that holds the icon menu's actions in a bar over the
 * results page. One or the other, never both. The user picks it ("Detach" in
 * the icon menu's quick settings; the window's Minimize or its dock gesture
 * back), and the pick is saved per display ([DetachedState]).
 */
enum class ControlsForm { ICON, WINDOW }

/**
 * Where the detached window rests on one display in one orientation: its
 * size in dp, and its place as fractions (0 at the start, 1 at the end) of
 * the room its size leaves inside the display's free area. A place kept as
 * fractions stays where the user left it relative to the edges when the free
 * area changes (a bar shown, another display size), which pixels would not.
 */
data class WindowBounds(
    val xFraction: Float,
    val yFraction: Float,
    val widthDp: Float,
    val heightDp: Float,
)

/** One display's saved controls: the form the user left them in, and where
 *  the window rested in each orientation (null until it has rested there). */
data class DetachedState(
    val form: ControlsForm = ControlsForm.ICON,
    val landscape: WindowBounds? = null,
    val portrait: WindowBounds? = null,
) {
    fun bounds(landscape: Boolean): WindowBounds? = if (landscape) this.landscape else portrait

    fun withBounds(landscape: Boolean, bounds: WindowBounds): DetachedState =
        if (landscape) copy(landscape = bounds) else copy(portrait = bounds)
}

/**
 * The form a display's controls show right now, from the form the user
 * [saved] and where the window can be. The window shows only where the
 * capture sheet would be used: [effectivelyOneScreen] (one viewport, or the
 * app behind), none of this app's own screens in front on that display
 * ([ownActivityInFront]), and the region editor closed ([regionEditorOpen]).
 * Anywhere else the icon stands in and the saved form stays as it is, so the
 * window comes back when the condition ends.
 *
 * Why each condition: an overlay window lies above every Activity, so a
 * large one would cover the app's own screens and the dialogs they raise
 * (permissions, the screen-share consent); with the app in front on a second
 * screen its page is already the results page, and its captures draw over
 * the game display; and the region editor needs the whole game in view.
 */
fun shownControlsForm(
    saved: ControlsForm,
    effectivelyOneScreen: Boolean,
    ownActivityInFront: Boolean,
    regionEditorOpen: Boolean,
): ControlsForm =
    if (saved == ControlsForm.WINDOW && effectivelyOneScreen && !ownActivityInFront && !regionEditorOpen) {
        ControlsForm.WINDOW
    } else {
        ControlsForm.ICON
    }

/**
 * Where auto-translate's results show. One answer, read by everything that
 * depends on it: the tier a session runs ([CaptureService.desiredFlavor]),
 * whether a start docks a window, what stops a session whose surface is gone
 * ([CaptureService.updateForegroundState]), how a start from the controls is
 * routed, which frames the results-only loop takes, and which page follows
 * the loop.
 */
enum class LiveSurface {
    /** Boxes over the game, from the icon. */
    OVERLAYS,

    /** The app's results page on a second screen, with nothing over the game
     *  ([Prefs.shouldUseInAppOnlyMode]). */
    APP_PAGE,

    /** The detached window's page, with nothing over the game. */
    WINDOW_PAGE,
}

/**
 * [LiveSurface] from its inputs. The window's page takes the results where a
 * session captures exactly one display ([targets], as a start resolves them),
 * that display's controls are saved as the window ([windowForm]), and the
 * window can be where the game is ([effectivelyOneScreen]: one viewport, or
 * the app behind). The saved form decides, not what shows at the moment: the
 * icon standing in for the window (one of our own screens in front, the
 * region editor) leaves the session the window's, waiting for it
 * ([OverlayUiController.windowBusy]).
 *
 * More than one captured display is never the window's: there is one stream
 * of results ([CaptureService.panelState]) and a page per display. Otherwise
 * [appPageOnly] is the two-screen rule, and the rest is the overlays.
 */
fun liveSurface(
    targets: Set<Int>,
    windowForm: (Int) -> Boolean,
    effectivelyOneScreen: Boolean,
    appPageOnly: Boolean,
): LiveSurface = when {
    effectivelyOneScreen && targets.size == 1 && windowForm(targets.single()) -> LiveSurface.WINDOW_PAGE
    appPageOnly -> LiveSurface.APP_PAGE
    else -> LiveSurface.OVERLAYS
}
