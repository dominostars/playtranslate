package com.playtranslate

import androidx.annotation.StringRes
import com.playtranslate.language.HintTextKind

/**
 * What the floating icon's gestures do.
 *
 * Each gesture has its own enum of candidates, so a binding can only ever
 * name an action that gesture can perform, and the icon's dispatch in
 * [OverlayUiController] is an exhaustive `when` per gesture: a candidate
 * added here cannot compile until it is wired there, nor until it has a
 * title and says which source languages offer it. This shared face is what
 * the Settings surfaces render, the "On the floating icon" cell and the
 * picker page it opens: a title per candidate, and whether the current
 * source language offers it. "Open the quick menu", the capture, the
 * auto-translate toggle, the swap and the game-language change are each
 * offered on two gestures and so are each a constant in two enums, sharing
 * their strings;
 * [IconGestureBindings.quickMenuReachable] is the one place that treats the
 * two menu constants as the same thing.
 *
 * Stored per gesture by enum name; see [Prefs.iconGestureBindings].
 */
sealed interface IconAction {
    /** This action's title on a source language whose reading hint is
     *  [hint]. Only the swap's depends on it. */
    @StringRes fun titleRes(hint: HintTextKind): Int

    /** Whether a source language whose reading hint is [hint] offers this
     *  action. Only the swap is ever not offered; a binding to it then acts
     *  as its gesture's default ([IconGestureBindings.resolve]). */
    fun isOfferedOn(hint: HintTextKind): Boolean
}

/**
 * "Swap between translation and furigana" (pinyin on a Chinese source),
 * offered on Hold and Tap. With auto-translate running it switches the
 * session to the other overlay mode in place; with auto-translate off it
 * starts it in the mode already selected (OverlayUiController's
 * swapOverlayModeOrStartLive). The other mode is the reading-hint overlay,
 * so only a source language with a reading hint offers the swap, and its
 * title names that hint the way [OverlayMode.labelRes] does: pinyin on a
 * Pinyin language, furigana on every other kind. These are the facts its
 * two constants share.
 */
private object SwapOverlayMode {
    fun isOfferedOn(hint: HintTextKind): Boolean = hint != HintTextKind.NONE

    @StringRes
    fun titleRes(hint: HintTextKind): Int =
        if (hint == HintTextKind.PINYIN) R.string.icon_action_swap_pinyin
        else R.string.icon_action_swap_furigana
}

/** Drag the icon across the game screen. The move, end and cancel legs of
 *  the gesture route to the lookup lens unconditionally while this is the
 *  only candidate; the start leg's `when` is the compile-time reminder. */
enum class DragAction : IconAction {
    /** The magnifier lens: hover over a word for its definition. */
    LOOKUP_WORDS;

    override fun titleRes(hint: HintTextKind): Int = when (this) {
        LOOKUP_WORDS -> R.string.icon_action_lookup_words
    }

    override fun isOfferedOn(hint: HintTextKind): Boolean = when (this) {
        LOOKUP_WORDS -> true
    }

    companion object {
        val DEFAULT = LOOKUP_WORDS
    }
}

/** Press the icon without moving. Fires at the hold threshold; the lift, or
 *  a slide past the drag threshold, ends it. */
enum class HoldAction : IconAction {
    /** The quick menu's Capture button at the hold threshold
     *  ([TapAction.CAPTURE_SCREEN]): a one-shot capture of this display's
     *  current region, replacing any showing result. The lift does
     *  nothing; a slide into a drag cancels the one-shot, as it cancels
     *  the hold-to-preview's. */
    CAPTURE_SCREEN,
    /** Hold-to-preview: a one-shot translation of this display while held
     *  (in live mode, a peek at the game under the overlay). */
    SHOW_TRANSLATIONS,
    /** Open the floating menu at the hold threshold; the lift does nothing. */
    OPEN_QUICK_MENU,
    /** The quick menu's Auto button at the hold threshold: start
     *  auto-translate if it's off, stop it if it runs. The lift does
     *  nothing. */
    TOGGLE_AUTO_TRANSLATE,
    /** The swap ([SwapOverlayMode]) at the hold threshold; the lift does
     *  nothing. */
    SWAP_OVERLAY_MODE,
    /** The game-language change ([TapAction.CHANGE_GAME_LANGUAGE]) at the
     *  hold threshold; the lift does nothing. */
    CHANGE_GAME_LANGUAGE;

    override fun titleRes(hint: HintTextKind): Int = when (this) {
        CAPTURE_SCREEN -> R.string.icon_action_capture_screen
        SHOW_TRANSLATIONS -> R.string.icon_action_show_translations
        OPEN_QUICK_MENU -> R.string.icon_action_open_quick_menu
        TOGGLE_AUTO_TRANSLATE -> R.string.icon_action_toggle_auto_translate
        SWAP_OVERLAY_MODE -> SwapOverlayMode.titleRes(hint)
        CHANGE_GAME_LANGUAGE -> R.string.icon_action_change_game_language
    }

    override fun isOfferedOn(hint: HintTextKind): Boolean = when (this) {
        CAPTURE_SCREEN, SHOW_TRANSLATIONS, OPEN_QUICK_MENU, TOGGLE_AUTO_TRANSLATE,
        CHANGE_GAME_LANGUAGE -> true
        SWAP_OVERLAY_MODE -> SwapOverlayMode.isOfferedOn(hint)
    }

    companion object {
        /** The capture, since 2026-10-13; hold-to-preview before it. A
         *  stored binding is kept; only an unset one reads as this. */
        val DEFAULT = CAPTURE_SCREEN
    }
}

/** A short tap on the icon. */
enum class TapAction : IconAction {
    OPEN_QUICK_MENU,
    /** The quick menu's Capture button without the menu: a one-shot capture
     *  of this display's current region, replacing any showing result. */
    CAPTURE_SCREEN,
    /** The quick menu's Auto button without the menu: start auto-translate
     *  if it's off, stop it if it runs. */
    TOGGLE_AUTO_TRANSLATE,
    /** The swap ([SwapOverlayMode]). */
    SWAP_OVERLAY_MODE,
    /** "Change game language": with exactly two languages downloaded, switch
     *  to the other one and say so in a pill; otherwise open the language
     *  picker, as the quick menu's Language row does (OverlayUiController's
     *  changeGameLanguage). Offered on every language. */
    CHANGE_GAME_LANGUAGE;

    override fun titleRes(hint: HintTextKind): Int = when (this) {
        OPEN_QUICK_MENU -> R.string.icon_action_open_quick_menu
        CAPTURE_SCREEN -> R.string.icon_action_capture_screen
        TOGGLE_AUTO_TRANSLATE -> R.string.icon_action_toggle_auto_translate
        SWAP_OVERLAY_MODE -> SwapOverlayMode.titleRes(hint)
        CHANGE_GAME_LANGUAGE -> R.string.icon_action_change_game_language
    }

    override fun isOfferedOn(hint: HintTextKind): Boolean = when (this) {
        OPEN_QUICK_MENU, CAPTURE_SCREEN, TOGGLE_AUTO_TRANSLATE, CHANGE_GAME_LANGUAGE -> true
        SWAP_OVERLAY_MODE -> SwapOverlayMode.isOfferedOn(hint)
    }

    companion object {
        val DEFAULT = OPEN_QUICK_MENU
    }
}

/** The three bindings as they act on the current source language, with
 *  that language's reading hint, as one snapshot: what the Settings cell
 *  and the picker page render (the hint decides which candidates a gesture
 *  offers, and names the swap), and what the leave guard checks. Built by
 *  [resolve]. */
data class IconGestureBindings(
    val drag: DragAction,
    val hold: HoldAction,
    val tap: TapAction,
    val hint: HintTextKind,
) {
    /** True when some gesture opens the quick menu. The picker page warns
     *  before it is left in a state where none does: the menu is the icon's
     *  only route to Turn Off / Hide, regions, auto-translate and the app.
     *  It warns rather than blocks; the in-app Settings remain. Judged on
     *  the current source language only: a stored swap reads as its
     *  gesture's default where it isn't offered, so a change of language
     *  can take the menu off the icon, or put it back, without a warning. */
    val quickMenuReachable: Boolean
        get() = hold == HoldAction.OPEN_QUICK_MENU || tap == TapAction.OPEN_QUICK_MENU

    companion object {
        /** The stored [drag], [hold] and [tap] as they act on a source
         *  language whose reading hint is [hint]: a binding to an action the
         *  language doesn't offer (the swap, without a reading hint) acts,
         *  and reads, as its gesture's default. Only the reading changes; the
         *  stored choice stays, so the swap is back once the source language
         *  has a hint again, as the Furigana and Pinyin hotkeys are (Gilad,
         *  2026-09-27). Every gesture's default is offered on every language
         *  (IconGestureBindingsTest), so the result is always one the
         *  language offers. */
        fun resolve(drag: DragAction, hold: HoldAction, tap: TapAction, hint: HintTextKind) =
            IconGestureBindings(
                drag = if (drag.isOfferedOn(hint)) drag else DragAction.DEFAULT,
                hold = if (hold.isOfferedOn(hint)) hold else HoldAction.DEFAULT,
                tap = if (tap.isOfferedOn(hint)) tap else TapAction.DEFAULT,
                hint = hint,
            )
    }
}
