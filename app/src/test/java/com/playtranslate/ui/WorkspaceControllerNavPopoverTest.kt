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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * [WorkspaceControllerNav] around a page popover (the Sentence tab's ⋯
 * menu): a cursor the popover put out of reach moves to the preferred
 * target (the menu's first row as it opens, ⋯ as it closes), a cursor still
 * in reach ignores the preference, and with the header gated while the menu
 * is open (OverlayWorkspace returns no header targets then) UP off the
 * top row goes nowhere.
 */
@RunWith(RobolectricTestRunner::class)
class WorkspaceControllerNavPopoverTest {

    class Host : Activity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_PlayTranslate)
            super.onCreate(savedInstanceState)
        }
    }

    private class FakeHost : WorkspaceNavHost {
        var page: List<NavAction> = emptyList()
        var header: List<NavAction> = emptyList()
        var ring: Rect? = null
        override val isModalUp = false
        override val isEditing = false
        override var isPopoverOpen = false
        override fun onControllerBack() = Unit
        override fun navActions(): List<NavAction> = page
        override fun headerNavActions(): List<NavAction> = if (isPopoverOpen) emptyList() else header
        override fun scrollViewportOnScreen(out: Rect) = false
        override fun scrollBy(dy: Int) = Unit
        override fun ensureVisible(itemOnScreen: Rect) = Unit
        override fun viewInScrollViewport(v: View) = false
        override fun setRing(itemOnScreen: Rect?, clipOnScreen: Rect?) {
            ring = itemOnScreen?.let(::Rect)
        }
    }

    private lateinit var host: FakeHost
    private lateinit var nav: WorkspaceControllerNav
    private lateinit var toggle: View
    private lateinit var label: View
    private lateinit var more: View
    private lateinit var row0: View
    private lateinit var row1: View

    @Before
    fun setUp() {
        val activity = Robolectric.buildActivity(Host::class.java).setup().get()
        val root = FrameLayout(activity)
        fun place(l: Int, t: Int) = View(activity).also {
            it.isClickable = true
            root.addView(it, FrameLayout.LayoutParams(100, 40, Gravity.TOP or Gravity.LEFT).apply {
                leftMargin = l
                topMargin = t
            })
        }
        toggle = place(100, 0)
        label = place(0, 100)
        more = place(300, 100)
        row0 = place(300, 200)
        row1 = place(300, 250)
        activity.setContentView(root)
        root.measure(
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, 1000, 1000)
        host = FakeHost().apply {
            page = listOf(NavAction(label), NavAction(more))
            header = listOf(NavAction(toggle))
        }
        nav = WorkspaceControllerNav(activity, host)
    }

    private fun press(code: Int) {
        nav.handleKey(KeyEvent(KeyEvent.ACTION_DOWN, code))
        nav.handleKey(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private fun rectOf(v: View): Rect {
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        return Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
    }

    private fun openMenu() {
        host.isPopoverOpen = true
        host.page = listOf(NavAction(row0), NavAction(row1))
    }

    private fun closeMenu() {
        host.isPopoverOpen = false
        host.page = listOf(NavAction(label), NavAction(more))
    }

    @Test
    fun `the cursor follows a menu in and back out onto more`() {
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(rectOf(more), host.ring)
        openMenu()
        nav.revalidateCursor(prefer = row0)
        assertEquals(rectOf(row0), host.ring)
        closeMenu()
        nav.revalidateCursor(prefer = more)
        assertEquals(rectOf(more), host.ring)
    }

    @Test
    fun `a cursor still in reach ignores the preference`() {
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals(rectOf(label), host.ring)
        nav.revalidateCursor(prefer = more)
        assertEquals(rectOf(label), host.ring)
    }

    @Test
    fun `with the header gated, up off the menu's top row goes nowhere`() {
        openMenu()
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals(rectOf(row0), host.ring)
        press(KeyEvent.KEYCODE_DPAD_UP)
        assertEquals(rectOf(row0), host.ring)
        closeMenu()
        nav.revalidateCursor(prefer = more)
        press(KeyEvent.KEYCODE_DPAD_UP)
        assertEquals("with the menu closed the header is reachable again", rectOf(toggle), host.ring)
    }
}
