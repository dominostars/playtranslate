package com.playtranslate.ui

import android.app.Activity
import android.graphics.Rect
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import com.playtranslate.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * [CaptureSheetControllerNav] with a popover open over the sheet: a ⋯
 * menu's rows are the ONLY candidates, ringed unclipped and never scrolled
 * to; a popover with no rows (the size slider) swallows dpad and A; a live
 * cursor follows a menu in as it opens and back onto ⋯ as it closes, while
 * no cursor stays no cursor; a cursor left under a popover is out of reach;
 * a row's hold runs through the view's own press, and a hold in flight ends
 * without a click when the popover goes.
 */
@RunWith(RobolectricTestRunner::class)
class CaptureSheetControllerNavPopoverTest {

    class Host : Activity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_PlayTranslate)
            super.onCreate(savedInstanceState)
        }
    }

    private class FakeHost : CaptureSheetNavHost {
        var buttons: List<NavAction> = emptyList()
        var popoverRows: List<NavAction>? = null
        var ring: Rect? = null
        var ringClip: Rect? = null
        var ensured = 0

        override val isEditing = false
        override val isPopoverOpen: Boolean get() = popoverRows != null
        override fun popoverNavActions(): List<NavAction>? = popoverRows
        override val inSliver = false
        override fun onControllerBack() = Unit
        override fun expandFromSliver() = Unit
        override fun navActions(): List<NavAction> = buttons
        override fun handleRect(out: Rect) = false
        override fun collapseToSliver() = Unit
        override fun sliverRect(out: Rect) = false
        override fun resizeBy(dyPx: Int) = Unit
        override fun commitResize() = Unit
        override fun wordCount() = 0
        override fun wordRect(index: Int, out: Rect) = false
        override fun wordRunIsRtl() = false
        override fun activateWord(index: Int) = Unit
        override fun scrollViewportOnScreen(out: Rect): Boolean {
            out.set(0, 0, 1000, 1000)
            return true
        }
        override fun scrollBy(dy: Int) = Unit
        override fun ensureVisible(itemOnScreen: Rect) { ensured++ }
        override fun setRing(itemOnScreen: Rect?, clipOnScreen: Rect?) {
            ring = itemOnScreen?.let(::Rect)
            ringClip = clipOnScreen?.let(::Rect)
        }
    }

    private lateinit var host: FakeHost
    private lateinit var nav: CaptureSheetControllerNav
    private lateinit var root: FrameLayout
    private lateinit var header: View
    private lateinit var more: View
    private lateinit var row0: View
    private lateinit var row1: View
    private val clicks = mutableListOf<View>()
    private val longClicks = mutableListOf<View>()

    @Before
    fun setUp() {
        val activity = Robolectric.buildActivity(Host::class.java).setup().get()
        root = FrameLayout(activity)
        fun place(l: Int, t: Int, w: Int, h: Int) = View(activity).also { v ->
            v.setOnClickListener { clicks += it }
            root.addView(v, FrameLayout.LayoutParams(w, h, Gravity.TOP or Gravity.LEFT).apply {
                leftMargin = l
                topMargin = t
            })
        }
        header = place(0, 0, 100, 50)
        more = place(200, 0, 100, 50)
        row0 = place(200, 100, 300, 50)
        row1 = place(200, 150, 300, 50).apply { setOnLongClickListener { longClicks += it; true } }
        activity.setContentView(root)
        root.measure(
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, 1000, 1000)
        host = FakeHost().apply { buttons = listOf(NavAction(header), NavAction(more)) }
        nav = CaptureSheetControllerNav(activity, host)
    }

    private val menu get() = listOf(NavAction(row0), NavAction(row1, holdActivates = true))

    private fun press(code: Int) {
        assertEquals(true, nav.handleKey(KeyEvent(KeyEvent.ACTION_DOWN, code)))
        assertEquals(true, nav.handleKey(KeyEvent(KeyEvent.ACTION_UP, code)))
    }

    private fun rectOf(v: View): Rect {
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        return Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
    }

    /** Ring ⋯ the way a controller user gets there: first press, then right. */
    private fun cursorOnMore() {
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(rectOf(more), host.ring)
    }

    @Test
    fun `an open menu's rows are the only targets, unclipped and never scrolled to`() {
        host.popoverRows = menu
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals(rectOf(row0), host.ring)
        assertNull("rows float outside the scroller", host.ringClip)
        assertEquals(0, host.ensured)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals(rectOf(row1), host.ring)
        press(KeyEvent.KEYCODE_DPAD_UP)
        press(KeyEvent.KEYCODE_DPAD_UP)
        assertEquals("the header buttons are out of reach", rectOf(row0), host.ring)
    }

    @Test
    fun `a popover with no rows swallows dpad and A`() {
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals(rectOf(header), host.ring)
        host.popoverRows = emptyList()
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        press(KeyEvent.KEYCODE_BUTTON_A)
        assertEquals(rectOf(header), host.ring)
        assertEquals(emptyList<View>(), clicks)
    }

    @Test
    fun `a live cursor follows a menu in and back out onto more`() {
        cursorOnMore()
        host.popoverRows = menu
        nav.onPopoverChanged(open = true, anchor = more)
        assertEquals(rectOf(row0), host.ring)
        host.popoverRows = null
        nav.onPopoverChanged(open = false, anchor = more)
        assertEquals(rectOf(more), host.ring)
        assertEquals("back among the sheet's items, clipped again", Rect(0, 0, 1000, 1000), host.ringClip)
    }

    @Test
    fun `no cursor stays no cursor when a menu opens`() {
        host.popoverRows = menu
        nav.onPopoverChanged(open = true, anchor = more)
        assertNull(host.ring)
    }

    @Test
    fun `a cursor left under a popover is out of reach`() {
        cursorOnMore()
        host.popoverRows = listOf(NavAction(row0))
        nav.revalidateCursor()
        assertEquals(rectOf(row0), host.ring)
    }

    @Test
    fun `a row's hold runs through the view's own press`() {
        host.popoverRows = menu
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        nav.handleKey(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A))
        assertEquals("a hold row doesn't fire on the press", emptyList<View>(), clicks)
        nav.handleKey(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_A))
        assertEquals("an early release clicks", listOf(row1), clicks)
    }

    @Test
    fun `a row below a capped menu's fold scrolls into view as the ring reaches it`() {
        val list = android.widget.LinearLayout(root.context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }
        val menuRows = List(3) { View(root.context).apply { isClickable = true } }
        for (r in menuRows) list.addView(r, android.widget.LinearLayout.LayoutParams(300, 50))
        val scroll = android.widget.ScrollView(root.context).apply { addView(list) }
        root.addView(scroll, FrameLayout.LayoutParams(300, 80, Gravity.TOP or Gravity.LEFT).apply {
            leftMargin = 600
            topMargin = 300
        })
        root.measure(
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, 1000, 1000)
        host.popoverRows = menuRows.map { NavAction(it) }
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals(0, scroll.scrollY)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("the third row's bottom meets the menu's", 150 - 80, scroll.scrollY)
        assertEquals(rectOf(menuRows[2]), host.ring)
        assertEquals("never the sheet's own scroll", 0, host.ensured)
    }

    @Test
    fun `a hold in flight ends without a click when the popover goes`() {
        host.popoverRows = menu
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        nav.handleKey(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A))
        host.popoverRows = null
        nav.onPopoverChanged(open = false, anchor = more)
        nav.handleKey(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_A))
        assertEquals(emptyList<View>(), clicks)
        assertEquals(emptyList<View>(), longClicks)
        assertEquals(false, row1.isPressed)
    }
}
