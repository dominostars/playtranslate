package com.playtranslate.ui

import android.content.Context
import android.graphics.Rect
import android.view.KeyEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The parked sheet's controller contract in [CaptureSheetControllerNav]:
 *  - A on the sliver RINGS the strip; only a second A expands (a held A's
 *    auto-repeats do neither, so a hold can't ring-then-expand by itself);
 *  - B while ringed dismisses on that ONE press (the ring is not a rung of
 *    the back ladder);
 *  - dpad while ringed expands outright, as it always did;
 *  - a press during the park animation, before the strip's hint has faded
 *    in, keeps the cursor and paints the ring once the host has a rect.
 * The host's side — dropping the cursor on every exit from the park — lives
 * in CaptureResultOverlay and is mirrored here by the fake's expand hook.
 */
@RunWith(RobolectricTestRunner::class)
class CaptureSheetControllerNavTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private class FakeHost : CaptureSheetNavHost {
        var slivered = true
        /** False while the strip's hint is still fading in (park animation). */
        var stripAtRest = true
        val strip = Rect(400, 1180, 680, 1200)
        var expandCalls = 0
        var backCalls = 0
        var ring: Rect? = null
        var ringClip: Rect? = null
        /** The real host clears the nav cursor inside its expand; tests that
         *  need that mirror set it. */
        var onExpand: (() -> Unit)? = null

        override val isEditing = false
        override val isPopoverOpen = false
        override val inSliver: Boolean get() = slivered
        override fun onControllerBack() { backCalls++ }
        override fun expandFromSliver() { expandCalls++; onExpand?.invoke() }
        override fun navActions(): List<NavAction> = emptyList()
        override fun handleRect(out: Rect) = false
        override fun collapseToSliver() = Unit
        override fun sliverRect(out: Rect): Boolean {
            if (!slivered || !stripAtRest) return false
            out.set(strip)
            return true
        }
        override fun resizeBy(dyPx: Int) = Unit
        override fun commitResize() = Unit
        override fun wordCount() = 0
        override fun wordRect(index: Int, out: Rect) = false
        override fun wordRunIsRtl() = false
        override fun activateWord(index: Int) = Unit
        override fun scrollViewportOnScreen(out: Rect) = false
        override fun scrollBy(dy: Int) = Unit
        override fun ensureVisible(itemOnScreen: Rect) = Unit
        override fun setRing(itemOnScreen: Rect?, clipOnScreen: Rect?) {
            ring = itemOnScreen?.let(::Rect)
            ringClip = clipOnScreen?.let(::Rect)
        }
    }

    private fun press(nav: CaptureSheetControllerNav, code: Int) {
        assertEquals(true, nav.handleKey(KeyEvent(KeyEvent.ACTION_DOWN, code)))
        assertEquals(true, nav.handleKey(KeyEvent(KeyEvent.ACTION_UP, code)))
    }

    private fun repeatDown(nav: CaptureSheetControllerNav, code: Int, repeat: Int) {
        nav.handleKey(KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, code, repeat))
    }

    @Test
    fun firstA_ringsTheStrip_withoutExpanding() {
        val host = FakeHost()
        val nav = CaptureSheetControllerNav(ctx, host)
        press(nav, KeyEvent.KEYCODE_BUTTON_A)
        assertEquals(0, host.expandCalls)
        assertEquals(host.strip, host.ring)
        assertNull("the strip rings unclipped, like the pill", host.ringClip)
    }

    @Test
    fun secondA_expands() {
        val host = FakeHost()
        val nav = CaptureSheetControllerNav(ctx, host)
        host.onExpand = { nav.clearCursor() }
        press(nav, KeyEvent.KEYCODE_BUTTON_A)
        press(nav, KeyEvent.KEYCODE_BUTTON_A)
        assertEquals(1, host.expandCalls)
        assertNull(host.ring)
        // Once the host has dropped the cursor (as its expand does), the next
        // A on a still-parked sheet rings again rather than expanding.
        press(nav, KeyEvent.KEYCODE_BUTTON_A)
        assertEquals(1, host.expandCalls)
        assertNotNull(host.ring)
    }

    @Test
    fun heldA_autoRepeats_neitherRingNorExpand() {
        val host = FakeHost()
        val nav = CaptureSheetControllerNav(ctx, host)
        nav.handleKey(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A))
        repeatDown(nav, KeyEvent.KEYCODE_BUTTON_A, 1)
        repeatDown(nav, KeyEvent.KEYCODE_BUTTON_A, 2)
        nav.handleKey(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_A))
        assertEquals(0, host.expandCalls)
        assertNotNull("the first down rang the strip", host.ring)
    }

    @Test
    fun bWhileRinged_dismissesOnOnePress() {
        val host = FakeHost()
        val nav = CaptureSheetControllerNav(ctx, host)
        press(nav, KeyEvent.KEYCODE_BUTTON_A)
        press(nav, KeyEvent.KEYCODE_BUTTON_B)
        assertEquals(1, host.backCalls)
        assertEquals(0, host.expandCalls)
    }

    @Test
    fun dpadWhileRinged_expands() {
        val host = FakeHost()
        val nav = CaptureSheetControllerNav(ctx, host)
        host.onExpand = { nav.clearCursor() }
        press(nav, KeyEvent.KEYCODE_BUTTON_A)
        press(nav, KeyEvent.KEYCODE_DPAD_UP)
        assertEquals(1, host.expandCalls)
        assertNull(host.ring)
    }

    @Test
    fun aDuringParkAnimation_keepsCursor_ringsOnceStripLands() {
        val host = FakeHost().apply { stripAtRest = false }
        val nav = CaptureSheetControllerNav(ctx, host)
        press(nav, KeyEvent.KEYCODE_BUTTON_A)
        assertNull("no rect yet, so no ring yet", host.ring)
        host.stripAtRest = true
        nav.syncRing()   // the pre-draw hook
        assertEquals(host.strip, host.ring)
        press(nav, KeyEvent.KEYCODE_BUTTON_A)
        assertEquals("the cursor survived the fade-in", 1, host.expandCalls)
    }

    @Test
    fun keyboardEnter_isTheSameTwoStep() {
        val host = FakeHost()
        val nav = CaptureSheetControllerNav(ctx, host)
        press(nav, KeyEvent.KEYCODE_ENTER)
        assertEquals(0, host.expandCalls)
        assertNotNull(host.ring)
        press(nav, KeyEvent.KEYCODE_ENTER)
        assertEquals(1, host.expandCalls)
    }
}
