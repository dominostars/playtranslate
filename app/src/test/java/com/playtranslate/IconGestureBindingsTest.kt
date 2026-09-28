package com.playtranslate

import com.playtranslate.language.HintTextKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [IconGestureBindings]: the defaults the icon ships with; which candidates
 * each source language offers, and what a binding to one it doesn't offer
 * reads as ([IconGestureBindings.resolve]); the swap's title per reading
 * hint; and the quick-menu reachability check the picker page's leave guard
 * reads, over every hold × tap cell (drag has no menu candidate).
 */
class IconGestureBindingsTest {

    private val hintKinds = HintTextKind.entries
    private val withHint = HintTextKind.entries - HintTextKind.NONE

    @Test fun `defaults are lookup, hold-to-preview and tap-to-menu`() {
        assertEquals(DragAction.LOOKUP_WORDS, DragAction.DEFAULT)
        assertEquals(HoldAction.SHOW_TRANSLATIONS, HoldAction.DEFAULT)
        assertEquals(TapAction.OPEN_QUICK_MENU, TapAction.DEFAULT)
        for (hint in hintKinds) {
            assertTrue(bindings(HoldAction.DEFAULT, TapAction.DEFAULT, hint).quickMenuReachable)
        }
    }

    // resolve() falls back to the default, so a default the language didn't
    // offer would read as a binding the picker has no row for.
    @Test fun `every gesture's default is offered on every language`() {
        for (hint in hintKinds) {
            assertTrue(DragAction.DEFAULT.isOfferedOn(hint))
            assertTrue(HoldAction.DEFAULT.isOfferedOn(hint))
            assertTrue(TapAction.DEFAULT.isOfferedOn(hint))
        }
    }

    @Test fun `only a language with a reading hint offers the swap`() {
        for (hint in hintKinds) {
            val expected = hint != HintTextKind.NONE
            assertEquals("hold on $hint", expected, HoldAction.SWAP_OVERLAY_MODE.isOfferedOn(hint))
            assertEquals("tap on $hint", expected, TapAction.SWAP_OVERLAY_MODE.isOfferedOn(hint))
        }
    }

    @Test fun `every other action is offered on every language`() {
        for (hint in hintKinds) {
            DragAction.entries.forEach { assertTrue("$it on $hint", it.isOfferedOn(hint)) }
            (HoldAction.entries - HoldAction.SWAP_OVERLAY_MODE)
                .forEach { assertTrue("$it on $hint", it.isOfferedOn(hint)) }
            (TapAction.entries - TapAction.SWAP_OVERLAY_MODE)
                .forEach { assertTrue("$it on $hint", it.isOfferedOn(hint)) }
        }
    }

    @Test fun `a swap binding acts as its gesture's default without a reading hint`() {
        val b = bindings(HoldAction.SWAP_OVERLAY_MODE, TapAction.SWAP_OVERLAY_MODE, HintTextKind.NONE)
        assertEquals(HoldAction.DEFAULT, b.hold)
        assertEquals(TapAction.DEFAULT, b.tap)
        assertEquals(HintTextKind.NONE, b.hint)
    }

    @Test fun `a swap binding stands on every language with a reading hint`() {
        for (hint in withHint) {
            val b = bindings(HoldAction.SWAP_OVERLAY_MODE, TapAction.SWAP_OVERLAY_MODE, hint)
            assertEquals(HoldAction.SWAP_OVERLAY_MODE, b.hold)
            assertEquals(TapAction.SWAP_OVERLAY_MODE, b.tap)
            assertEquals(hint, b.hint)
        }
    }

    @Test fun `every other binding stands on every language`() {
        for (hint in hintKinds) {
            for (hold in HoldAction.entries - HoldAction.SWAP_OVERLAY_MODE) {
                for (tap in TapAction.entries - TapAction.SWAP_OVERLAY_MODE) {
                    val b = bindings(hold, tap, hint)
                    assertEquals(hold, b.hold)
                    assertEquals(tap, b.tap)
                    assertEquals(DragAction.LOOKUP_WORDS, b.drag)
                }
            }
        }
    }

    @Test fun `the swap's title names pinyin on a Pinyin language and furigana on the others`() {
        for (swap in listOf<IconAction>(HoldAction.SWAP_OVERLAY_MODE, TapAction.SWAP_OVERLAY_MODE)) {
            assertEquals(R.string.icon_action_swap_pinyin, swap.titleRes(HintTextKind.PINYIN))
            assertEquals(R.string.icon_action_swap_furigana, swap.titleRes(HintTextKind.FURIGANA))
            assertEquals(R.string.icon_action_swap_furigana, swap.titleRes(HintTextKind.HARAKAT))
        }
    }

    @Test fun `every other title is the same on every language`() {
        for (hint in hintKinds) {
            assertEquals(R.string.icon_action_lookup_words, DragAction.LOOKUP_WORDS.titleRes(hint))
            assertEquals(R.string.icon_action_show_translations, HoldAction.SHOW_TRANSLATIONS.titleRes(hint))
            assertEquals(R.string.icon_action_open_quick_menu, HoldAction.OPEN_QUICK_MENU.titleRes(hint))
            assertEquals(R.string.icon_action_open_quick_menu, TapAction.OPEN_QUICK_MENU.titleRes(hint))
            assertEquals(R.string.icon_action_capture_screen, TapAction.CAPTURE_SCREEN.titleRes(hint))
            assertEquals(
                R.string.icon_action_toggle_auto_translate,
                HoldAction.TOGGLE_AUTO_TRANSLATE.titleRes(hint),
            )
            assertEquals(
                R.string.icon_action_toggle_auto_translate,
                TapAction.TOGGLE_AUTO_TRANSLATE.titleRes(hint),
            )
        }
    }

    @Test fun `menu on tap alone is reachable`() {
        assertTrue(bindings(HoldAction.SHOW_TRANSLATIONS, TapAction.OPEN_QUICK_MENU).quickMenuReachable)
        assertTrue(bindings(HoldAction.SWAP_OVERLAY_MODE, TapAction.OPEN_QUICK_MENU).quickMenuReachable)
    }

    @Test fun `menu on hold alone is reachable`() {
        assertTrue(bindings(HoldAction.OPEN_QUICK_MENU, TapAction.CAPTURE_SCREEN).quickMenuReachable)
        assertTrue(bindings(HoldAction.OPEN_QUICK_MENU, TapAction.SWAP_OVERLAY_MODE).quickMenuReachable)
    }

    @Test fun `menu on both is reachable`() {
        assertTrue(bindings(HoldAction.OPEN_QUICK_MENU, TapAction.OPEN_QUICK_MENU).quickMenuReachable)
    }

    @Test fun `menu on neither is unreachable`() {
        for (hold in HoldAction.entries - HoldAction.OPEN_QUICK_MENU) {
            for (tap in TapAction.entries - TapAction.OPEN_QUICK_MENU) {
                assertFalse("$hold + $tap", bindings(hold, tap).quickMenuReachable)
            }
        }
    }

    // The guard judges the bindings as they act on the current language: a
    // tap swap there is the tap's default, the menu.
    @Test fun `a tap swap opens the menu on a language without a reading hint`() {
        assertTrue(
            bindings(HoldAction.SHOW_TRANSLATIONS, TapAction.SWAP_OVERLAY_MODE, HintTextKind.NONE)
                .quickMenuReachable
        )
        assertFalse(
            bindings(HoldAction.SWAP_OVERLAY_MODE, TapAction.CAPTURE_SCREEN, HintTextKind.NONE)
                .quickMenuReachable
        )
    }

    private fun bindings(hold: HoldAction, tap: TapAction, hint: HintTextKind = HintTextKind.FURIGANA) =
        IconGestureBindings.resolve(DragAction.LOOKUP_WORDS, hold, tap, hint)
}
