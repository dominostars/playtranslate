package com.playtranslate.ui

import com.playtranslate.OverlayMode

/** What the results page does on the game display as it renders a state
 *  ([onScreenStep]). */
internal enum class OnScreenStep {
    /** Nothing: a window up stays as it is. */
    NONE,

    /** Whatever is up goes. */
    HIDE,

    /** The capture's window up is filled in place with the new boxes. */
    FILL,

    /** Whatever is up goes, and the new capture goes up. */
    PRESENT,
}

/**
 * What the results page does on the game display (two screens) when it
 * renders a state whose boxes are [next] after one whose boxes were
 * [previous] ([TranslationResultViewModel.renderedOnScreenBoxes]).
 *
 * A state without boxes (Clear, a capture's status, a live, drag or edit
 * result) takes down whatever is up. More of the capture rendered last
 * ([isSameCapture]) fills its window in place while it is up ([shownNow]),
 * and puts nothing back once it is down: the user's tap, a takeover or the
 * app's onStop took it down, and a page recreated over the same result
 * replays it as the capture it already was. Boxes of another capture are
 * that capture arriving, whatever the page rendered in between: it replaces
 * whatever is up, by going up itself where the game display is another
 * screen ([showsOnScreen]) when it goes up as it lands ([goesUpAsItLands]:
 * a mode it asked for itself, or Show on screen on, [showOnScreen]), else
 * by taking the old one down. Its mode is [twoScreenMode]'s, which the
 * controller reads as it puts it up; whether the game screen is still the
 * capture's is the controller's to say then too
 * ([com.playtranslate.OverlayUiController.presentCapture], by
 * [OnScreenBoxes.captureId]).
 */
internal fun onScreenStep(
    previous: OnScreenBoxes?,
    next: OnScreenBoxes?,
    shownNow: Boolean,
    showsOnScreen: Boolean,
    showOnScreen: Boolean,
): OnScreenStep = when {
    next == null -> OnScreenStep.HIDE
    isSameCapture(previous, next) -> if (shownNow) OnScreenStep.FILL else OnScreenStep.NONE
    showsOnScreen && goesUpAsItLands(next.modeOverride, showOnScreen) -> OnScreenStep.PRESENT
    else -> OnScreenStep.HIDE
}

/**
 * Whether a capture's overlay goes up on the game display of two screens by
 * itself as the capture lands: when the capture asked for a mode of its own
 * ([override], tap-to-lookup's Definitions, which shows its word boxes
 * whatever Show on screen says; None asks for nothing), else when Show on
 * screen is on ([showOnScreen], [com.playtranslate.Prefs.dualScreenShowOnScreen]).
 */
internal fun goesUpAsItLands(override: OverlayMode?, showOnScreen: Boolean): Boolean =
    override != OverlayMode.NONE && (override != null || showOnScreen)

/**
 * The mode a capture's overlay paints in on the game display of two
 * screens: the one the capture asked for itself ([override]), else the
 * remembered one ([remembered]), else, for a remembered None,
 * auto-translate's ([autoTranslateMode], Translation or the reading hint),
 * the mode the old boxes switch's "on" meant. On two screens None is not a
 * mode the overlay can be in: Show on screen is what keeps it off, and
 * None in its pill turns that off. A remembered None comes from one screen,
 * where it paints nothing beside the sheet, and is left for it.
 */
internal fun twoScreenMode(override: OverlayMode?, remembered: OverlayMode, autoTranslateMode: OverlayMode): OverlayMode =
    override ?: remembered.takeUnless { it == OverlayMode.NONE } ?: autoTranslateMode

/**
 * Whether [next] is more of the capture whose boxes the page rendered last
 * ([previous]): its boxes filling in as translations land, the deferred
 * translation's fill, or the same result replayed. Told by the capture's id
 * ([OnScreenBoxes.captureId]: one for each capture over the game, never
 * reused in the process), not by what the page rendered in between, since a
 * page can take up a capture after its status: a re-read the app starts as it
 * stops is followed from where it got to when the app comes back, and the
 * page goes from the old capture's boxes straight to the new one's. Boxes
 * with no id on either side (one screen, or no overlay to start the capture
 * on, so no window either) fall back to that order: boxes that follow boxes
 * are the same capture, a capture's first state having none.
 */
internal fun isSameCapture(previous: OnScreenBoxes?, next: OnScreenBoxes): Boolean =
    previous != null && previous.captureId == next.captureId

/**
 * Whether the results page offers Show on screen for a capture (two
 * screens): only with a capture's result on the page, whose boxes
 * ([boxes]) are what it puts on the game display, only where the game
 * display is another screen ([showsOnScreen]), and not while auto-translate
 * runs ([liveRunning]), which owns the game screen: putting a capture up
 * would stop it. Not before a capture, not while one is being read (its
 * status carries no boxes), and not over a result without boxes (live
 * mode's, an edit's). Auto-translate's own meaning of the button (the
 * hide-game-overlays setting) is the page's other branch.
 */
internal fun offersShowOnScreen(showsOnScreen: Boolean, boxes: OnScreenBoxes?, liveRunning: Boolean): Boolean =
    showsOnScreen && boxes != null && !liveRunning
