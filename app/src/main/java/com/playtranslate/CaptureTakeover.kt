package com.playtranslate

/**
 * What takes the game screen from a capture over it, from outside the
 * capture's own controls, and what each one does to that capture: the one
 * table every such path reads ([OverlayUiController.endCaptures]). Each row
 * states what the sheet's pause record owes ([debt]), which phases of a
 * capture it ends ([phases]: one still on its way, one showing, or both),
 * whether only a capture in Definitions ends ([definitionsOnly]) and whether
 * a two-screen window showing goes too ([endsWindow]; where it does not,
 * the row names the rule that takes it down). A new way to take the game
 * screen is a new row, and it cannot compile without saying all four.
 *
 * A capture on its way is one screen's sheet before its screenshot is in,
 * or two screens' capture the app is still reading: a row ends it as it
 * ends one showing, so the sheet never comes up and the app's result stays
 * on its page ([OverlayUiController.presentCapture] refuses it).
 *
 * Not here: a capture's own endings, which are the sheet's
 * ([com.playtranslate.ui.CaptureResultOverlay.DismissReason]: the user's
 * close, a page it opened, a cancel from outside, the system's), and the
 * pages it opens itself; a new capture, which takes the record over instead
 * of ending it; and capture's own end, which drops it
 * ([OverlayUiController.hideFloatingIcon]).
 */
internal enum class CaptureTakeover(
    val debt: CaptureDebt,
    val phases: CapturePhases,
    val definitionsOnly: Boolean,
    val endsWindow: Boolean,
) {
    /** The translation-error pill's gear: it stops auto-translate on purpose,
     *  and the settings page it opens would sit under an overlay left up. */
    ERROR_PILL_GEAR(
        debt = CaptureDebt.NO_RESTART, phases = CapturePhases.ALL, definitionsOnly = false, endsWindow = true,
    ),

    /** The quick menu opens over the game: it pauses auto-translate itself
     *  and offers Auto, and it would sit over a capture anyway; the icon's
     *  tap is a button press, which ends the two-screen window. */
    QUICK_MENU(
        debt = CaptureDebt.NO_RESTART, phases = CapturePhases.ALL, definitionsOnly = false, endsWindow = true,
    ),

    /** The game-language picker opens: a capture ends as the user's close
     *  ends it, as main's picker closed tap-to-lookup, and a pick then
     *  restarts auto-translate in the new language ([LANGUAGE_CHANGE]). The
     *  quick menu's Language row finds none: the menu ended them when it
     *  opened. */
    LANGUAGE_PICKER(
        debt = CaptureDebt.RESTART, phases = CapturePhases.ALL, definitionsOnly = false, endsWindow = true,
    ),

    /** The game language changed, anywhere
     *  ([CaptureService.onSourceLanguageChanged]): a capture belongs to the
     *  language it was read in (its readings and word boxes were built in
     *  it, while a Definitions card looks its words up in the language set
     *  now), so every capture ends and gives back what it stopped, in the
     *  new language, as on a turn of the sheet's display. The window goes
     *  too; the app's page keeps its result, and
     *  [OverlayUiController.presentCapture] refuses to put it back up. */
    LANGUAGE_CHANGE(
        debt = CaptureDebt.RESTART, phases = CapturePhases.ALL, definitionsOnly = false, endsWindow = true,
    ),

    /** A hold-to-preview starts on the capture's display (the icon's hold,
     *  the app's hold button and the hold hotkeys alike, through
     *  [CaptureService]'s beginHoldPreview): a capture in Definitions ends
     *  first, on its way or showing, as the user's close ends it, since the
     *  preview would paint under its dimming and its window holds the keys,
     *  as main's hotkeys closed tap-to-lookup first. The sheet's record
     *  restarts an auto-translate it stopped, which the preview then pauses
     *  as a hold pauses a running session; the window's close restarts
     *  nothing, the app's button does. A capture in another mode stands
     *  under a preview, as it always has. */
    HOLD_PREVIEW(
        debt = CaptureDebt.RESTART, phases = CapturePhases.ALL, definitionsOnly = true, endsWindow = true,
    ),

    /** Auto-translate starts ([CaptureService.startLive]): the session
     *  starting is the restart the capture owed, and a capture on its way is
     *  cancelled before it can come up over the session (the service also
     *  cancels the app's read). The window goes on the app's own edge
     *  (MainActivity's onLiveModeChanged). */
    LIVE_START(
        debt = CaptureDebt.NO_RESTART, phases = CapturePhases.ALL, definitionsOnly = false, endsWindow = false,
    ),

    /** With the app in front on another screen, a capture for the game
     *  display starts ([OverlayUiController.beginAppCapture]) or the app's
     *  page puts one up ([OverlayUiController.presentCapture]): a sheet from
     *  a capture taken while the app was behind goes, whatever this capture
     *  brings back, and the user is in the app, so its record settles without
     *  the restart; an earlier capture of the app's on its way is replaced.
     *  The window is the app's to replace. */
    APP_CAPTURE(
        debt = CaptureDebt.NO_RESTART, phases = CapturePhases.ALL, definitionsOnly = false, endsWindow = false,
    ),

    /** The sheet's display turned or changed size: its boxes are the old
     *  layout's, and the user did nothing, so the game screen goes back to
     *  what the capture stopped. A sheet on its way checks its own geometry
     *  when its screenshot is in, and two screens' capture on its way and its
     *  window keep the baselines of their own (a turn while the app reads
     *  was accepted on 2026-11-02). */
    DISPLAY_TURN(
        debt = CaptureDebt.RESTART, phases = CapturePhases.SHOWN, definitionsOnly = false, endsWindow = false,
    ),

    /** The capture's display went away: whatever was on it goes, and the
     *  game screen goes back to what the capture stopped. */
    DISPLAY_GONE(
        debt = CaptureDebt.RESTART, phases = CapturePhases.ALL, definitionsOnly = false, endsWindow = true,
    ),

    /** A hold that started a capture slid into a drag: the one-shot is
     *  cancelled, as the hold-to-preview's is, since its result would come
     *  up under the lens, and the drag's own record takes the screen next
     *  and leaves a session the hold stopped stopped, so the capture's debt
     *  is forgotten, not handed on. The window goes too: its result already
     *  landed, or a tap-to-lookup hold switched it in place. */
    HOLD_INTO_DRAG(
        debt = CaptureDebt.DROP, phases = CapturePhases.ALL, definitionsOnly = false, endsWindow = true,
    ),

    /** A drag lookup starts on the capture's display: a capture still on its
     *  way would come up over the lens, so it never does, and the lens takes
     *  over what it paused, as a capture takes over a lens's. A capture
     *  already showing stays under the lens, as it always has. */
    LENS_START(
        debt = CaptureDebt.HAND_OVER, phases = CapturePhases.ON_ITS_WAY, definitionsOnly = false, endsWindow = false,
    ),
}

/** What a capture's pause record ([LookupPause]) does when something takes
 *  the game screen from the capture. */
internal enum class CaptureDebt {
    /** It settles and restarts an auto-translate the capture stopped, as the
     *  user's own close does ([LookupPause.settle] resuming). */
    RESTART,

    /** It settles without the restart: what comes next stops auto-translate
     *  anyway, or the user is somewhere else now ([LookupPause.settle] not
     *  resuming). */
    NO_RESTART,

    /** It is forgotten without acting: the surface that takes the screen
     *  holds a record of its own ([LookupPause.drop]). */
    DROP,

    /** The surface that takes the screen takes it over and owes it
     *  ([LookupPause.handTo]), so nothing restarts in between. */
    HAND_OVER,
}

/** Which phases of a capture a [CaptureTakeover] ends. */
internal enum class CapturePhases(val onItsWay: Boolean, val shown: Boolean) {
    /** On its way and showing alike. */
    ALL(onItsWay = true, shown = true),

    /** Only a capture whose presentation is up. */
    SHOWN(onItsWay = false, shown = true),

    /** Only a capture still on its way. */
    ON_ITS_WAY(onItsWay = true, shown = false),
}
