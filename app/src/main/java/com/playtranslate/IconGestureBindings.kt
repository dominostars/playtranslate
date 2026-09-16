package com.playtranslate

import androidx.annotation.StringRes

/**
 * What the floating icon's gestures do.
 *
 * Each gesture has its own enum of candidates, so a binding can only ever
 * name an action that gesture can perform, and the icon's dispatch in
 * [OverlayUiController] is an exhaustive `when` per gesture: a candidate
 * added here cannot compile until it is wired there. This shared face is
 * what the Settings surfaces render, the "On the floating icon" cell and
 * the picker page it opens: a title per candidate. "Open the quick menu" is
 * offered on two gestures and so is a constant in two enums; they share one
 * string, and [IconGestureBindings.quickMenuReachable] is the one place that
 * treats them as the same thing.
 *
 * Stored per gesture by enum name; see [Prefs.iconGestureBindings].
 */
sealed interface IconAction {
    @get:StringRes val titleRes: Int
}

/** Drag the icon across the game screen. The move, end and cancel legs of
 *  the gesture route to the lookup lens unconditionally while this is the
 *  only candidate; the start leg's `when` is the compile-time reminder. */
enum class DragAction(@StringRes override val titleRes: Int) : IconAction {
    /** The magnifier lens: hover over a word for its definition. */
    LOOKUP_WORDS(R.string.icon_action_lookup_words);

    companion object {
        val DEFAULT = LOOKUP_WORDS
    }
}

/** Press the icon without moving. Fires at the hold threshold; the lift, or
 *  a slide past the drag threshold, ends it. */
enum class HoldAction(@StringRes override val titleRes: Int) : IconAction {
    /** Hold-to-preview: a one-shot translation of this display while held
     *  (in live mode, a peek at the game under the overlay). */
    SHOW_TRANSLATIONS(R.string.icon_action_show_translations),
    /** Open the floating menu at the hold threshold; the lift does nothing. */
    OPEN_QUICK_MENU(R.string.icon_action_open_quick_menu);

    companion object {
        val DEFAULT = SHOW_TRANSLATIONS
    }
}

/** A short tap on the icon. */
enum class TapAction(@StringRes override val titleRes: Int) : IconAction {
    OPEN_QUICK_MENU(R.string.icon_action_open_quick_menu),
    /** The quick menu's Capture button without the menu: a one-shot capture
     *  of this display's current region, replacing any showing result. */
    CAPTURE_SCREEN(R.string.icon_action_capture_screen);

    companion object {
        val DEFAULT = OPEN_QUICK_MENU
    }
}

/** The three bindings as one snapshot: what the Settings cell and the
 *  picker page render, and what the leave guard checks. */
data class IconGestureBindings(
    val drag: DragAction,
    val hold: HoldAction,
    val tap: TapAction,
) {
    /** True when some gesture opens the quick menu. The picker page warns
     *  before it is left in a state where none does: the menu is the icon's
     *  only route to Turn Off / Hide, regions, auto-translate and the app.
     *  It warns rather than blocks; the in-app Settings remain. */
    val quickMenuReachable: Boolean
        get() = hold == HoldAction.OPEN_QUICK_MENU || tap == TapAction.OPEN_QUICK_MENU

    companion object {
        val DEFAULT = IconGestureBindings(DragAction.DEFAULT, HoldAction.DEFAULT, TapAction.DEFAULT)
    }
}
