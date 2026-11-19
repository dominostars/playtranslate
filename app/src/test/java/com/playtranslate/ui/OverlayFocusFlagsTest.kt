package com.playtranslate.ui

import android.view.WindowManager.LayoutParams
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [applyOverlayFocus]: the focus half of an overlay window's flags, the one
 * rule the full-screen sheets and the detached window share. Every cell of
 * (focusable, wantsIme), and that the flags it does not own stay as they
 * were, whichever state the params came from.
 */
@RunWith(RobolectricTestRunner::class)
class OverlayFocusFlagsTest {

    private val focusBits = LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_ALT_FOCUSABLE_IM
    private val others = LayoutParams.FLAG_NOT_TOUCH_MODAL or LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        LayoutParams.FLAG_NOT_TOUCHABLE

    private fun params(flags: Int) = LayoutParams().apply { this.flags = flags }

    /** Applied to params starting in each of the three focus states. */
    private fun applied(focusable: Boolean, wantsIme: Boolean): List<LayoutParams> =
        listOf(
            others or LayoutParams.FLAG_NOT_FOCUSABLE,
            others or LayoutParams.FLAG_ALT_FOCUSABLE_IM,
            others,
        ).map { start -> params(start).apply { applyOverlayFocus(focusable, wantsIme) } }

    @Test
    fun `a window that takes no keys is not focusable and hides the keyboard`() {
        for (lp in applied(focusable = false, wantsIme = false)) {
            assertEquals(LayoutParams.FLAG_NOT_FOCUSABLE, lp.flags and focusBits)
            assertEquals(others, lp.flags and focusBits.inv())
            assertEquals(LayoutParams.SOFT_INPUT_STATE_HIDDEN, lp.softInputMode)
        }
    }

    @Test
    fun `key focus without typing never makes the window the keyboard's target`() {
        for (lp in applied(focusable = true, wantsIme = false)) {
            assertEquals(LayoutParams.FLAG_ALT_FOCUSABLE_IM, lp.flags and focusBits)
            assertEquals(others, lp.flags and focusBits.inv())
            assertEquals(LayoutParams.SOFT_INPUT_STATE_HIDDEN, lp.softInputMode)
        }
    }

    @Test
    fun `typing takes plain focus and raises the keyboard`() {
        for (lp in applied(focusable = true, wantsIme = true)) {
            assertEquals(0, lp.flags and focusBits)
            assertEquals(others, lp.flags and focusBits.inv())
            assertEquals(
                LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
                lp.softInputMode,
            )
        }
    }
}
