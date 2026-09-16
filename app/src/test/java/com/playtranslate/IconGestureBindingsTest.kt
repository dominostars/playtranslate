package com.playtranslate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [IconGestureBindings]: the defaults the icon ships with, and the
 * quick-menu reachability check the picker page's leave guard reads, over
 * every hold × tap cell (drag has no menu candidate).
 */
class IconGestureBindingsTest {

    @Test fun `defaults are lookup, hold-to-preview and tap-to-menu`() {
        val d = IconGestureBindings.DEFAULT
        assertEquals(DragAction.LOOKUP_WORDS, d.drag)
        assertEquals(HoldAction.SHOW_TRANSLATIONS, d.hold)
        assertEquals(TapAction.OPEN_QUICK_MENU, d.tap)
        assertTrue(d.quickMenuReachable)
    }

    @Test fun `menu on tap alone is reachable`() {
        assertTrue(bindings(HoldAction.SHOW_TRANSLATIONS, TapAction.OPEN_QUICK_MENU).quickMenuReachable)
    }

    @Test fun `menu on hold alone is reachable`() {
        assertTrue(bindings(HoldAction.OPEN_QUICK_MENU, TapAction.CAPTURE_SCREEN).quickMenuReachable)
    }

    @Test fun `menu on both is reachable`() {
        assertTrue(bindings(HoldAction.OPEN_QUICK_MENU, TapAction.OPEN_QUICK_MENU).quickMenuReachable)
    }

    @Test fun `menu on neither is unreachable`() {
        assertFalse(bindings(HoldAction.SHOW_TRANSLATIONS, TapAction.CAPTURE_SCREEN).quickMenuReachable)
    }

    private fun bindings(hold: HoldAction, tap: TapAction) =
        IconGestureBindings(DragAction.LOOKUP_WORDS, hold, tap)
}
