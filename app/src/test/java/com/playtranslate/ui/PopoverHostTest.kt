package com.playtranslate.ui

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import com.playtranslate.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * [PopoverHost], the one in-window popover of a results surface: it adds a
 * scrim and a card to its host and removes both on dismiss; one popover at a
 * time; its views go UNDER the `below` view (the sheet's focus ring); a
 * scrim tap dismisses; placement uses absolute TOP|LEFT offsets, identical
 * under RTL; a popover whose anchor stops being shown closes itself; the
 * open notification waits for the card's first layout while close is
 * synchronous; native focus moves into the card, stays trapped there, and
 * returns to the anchor; release refuses later shows.
 */
@RunWith(RobolectricTestRunner::class)
class PopoverHostTest {

    class Host : Activity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_PlayTranslate)
            super.onCreate(savedInstanceState)
        }
    }

    /** A card holding [rowCount] focusable rows. */
    private class FakeContent(private val cardW: Int = 200, private val rowCount: Int = 2) : PopoverContent {
        var dismissed = 0
        val rows = mutableListOf<View>()
        override fun createView(ctx: Context, host: PopoverHost): View =
            android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                repeat(rowCount) {
                    val r = View(ctx).apply {
                        isFocusable = true
                        isFocusableInTouchMode = true
                        isClickable = true
                    }
                    rows += r
                    addView(r, android.widget.LinearLayout.LayoutParams(cardW, 48))
                }
            }
        override fun cardWidth(ctx: Context, natural: Int) = cardW
        override fun navActions() = rows.map { NavAction(it) }
        override fun onDismissed() { dismissed++ }
    }

    private lateinit var root: FrameLayout
    private lateinit var anchor: View
    private lateinit var ring: View
    private lateinit var popovers: PopoverHost

    @Before
    fun setUp() {
        val activity = Robolectric.buildActivity(Host::class.java).setup().get()
        root = FrameLayout(activity)
        anchor = View(activity).apply { isFocusable = true; isFocusableInTouchMode = true }
        ring = View(activity)
        // Absolute LEFT, so the anchor itself doesn't move under RTL.
        root.addView(anchor, FrameLayout.LayoutParams(40, 40, Gravity.TOP or Gravity.LEFT).apply {
            leftMargin = 300
            topMargin = 600
        })
        root.addView(ring, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        activity.setContentView(root, FrameLayout.LayoutParams(1000, 1500))
        layout()
        popovers = PopoverHost(root, below = ring)
    }

    private fun layout() {
        root.measure(
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1500, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, 1000, 1500)
    }

    /** One frame: layout, then the pre-draw pass. */
    private fun frame() {
        layout()
        root.viewTreeObserver.dispatchOnPreDraw()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun `show adds a scrim and a card under the ring, dismiss removes both`() {
        val content = FakeContent()
        popovers.show(content, anchor)
        assertTrue(popovers.isShowing)
        assertSame(content, popovers.content)
        assertSame(anchor, popovers.anchor)
        assertEquals(4, root.childCount)
        assertSame("the ring stays on top", ring, root.getChildAt(3))
        assertTrue(popovers.dismiss())
        assertFalse(popovers.isShowing)
        assertEquals(2, root.childCount)
        assertEquals(1, content.dismissed)
        assertFalse("nothing left to dismiss", popovers.dismiss())
    }

    @Test
    fun `one popover at a time`() {
        val first = FakeContent()
        val second = FakeContent()
        popovers.show(first, anchor)
        popovers.show(second, anchor)
        assertEquals(1, first.dismissed)
        assertSame(second, popovers.content)
        assertEquals(4, root.childCount)
        popovers.toggle(second, anchor)
        assertFalse("toggle closes what shows", popovers.isShowing)
    }

    @Test
    fun `a scrim tap dismisses`() {
        popovers.show(FakeContent(), anchor)
        root.getChildAt(1).performClick()
        assertFalse(popovers.isShowing)
    }

    @Test
    fun `placement is absolute and identical under RTL`() {
        popovers.show(FakeContent(), anchor)
        val box = root.getChildAt(2)
        val ltrX = box.x
        val ltrY = box.y
        assertEquals(Gravity.TOP or Gravity.LEFT, (box.layoutParams as FrameLayout.LayoutParams).gravity)
        // Centred on the anchor (x 300..340): 320 - 100.
        assertEquals(220f, ltrX)
        assertTrue("above the anchor, which has room", ltrY + box.layoutParams.height <= 600)
        popovers.dismiss()
        root.layoutDirection = View.LAYOUT_DIRECTION_RTL
        layout()
        popovers.show(FakeContent(), anchor)
        val rtl = root.getChildAt(2)
        assertEquals(Gravity.TOP or Gravity.LEFT, (rtl.layoutParams as FrameLayout.LayoutParams).gravity)
        assertEquals(ltrX, rtl.x)
        assertEquals(ltrY, rtl.y)
    }

    @Test
    fun `a card taller than its host is capped to fit inside it`() {
        // 40 rows of 48 = 1920, in a 1500-tall host.
        popovers.show(FakeContent(rowCount = 40), anchor)
        val box = root.getChildAt(2)
        val h = box.layoutParams.height
        assertTrue("capped: $h", h <= root.height)
        assertTrue("fully inside: y=${box.y} h=$h", box.y >= 0f && box.y + h <= root.height)
    }

    @Test
    fun `open and close are announced at once, laid-out after the first layout`() {
        val events = mutableListOf<String>()
        popovers.addListener(object : PopoverHost.Listener {
            override fun onPopoverChanged(open: Boolean, content: PopoverContent, anchor: View) {
                assertSame(this@PopoverHostTest.anchor, anchor)
                assertEquals("the state already agrees", open, popovers.isShowing)
                events += if (open) "open" else "close"
            }

            override fun onPopoverLaidOut(content: PopoverContent, anchor: View) {
                events += "laid out"
            }
        })
        popovers.show(FakeContent(), anchor)
        assertEquals(listOf("open"), events)
        frame()
        assertEquals(listOf("open", "laid out"), events)
        popovers.dismiss()
        assertEquals(listOf("open", "laid out", "close"), events)
    }

    @Test
    fun `the card follows its anchor, on the side it opened on`() {
        popovers.show(FakeContent(), anchor)
        frame()
        val box = root.getChildAt(2)
        val arrow = (box as android.view.ViewGroup).getChildAt(1)
        val x0 = box.x
        val y0 = box.y
        val lp = anchor.layoutParams as FrameLayout.LayoutParams
        lp.topMargin = 700
        lp.leftMargin = 500
        anchor.layoutParams = lp
        frame()
        assertEquals("moved down with it, still above", y0 + 100f, box.y)
        assertEquals(x0 + 200f, box.x)
        // Near the edge the card clamps and the arrow slides to stay on the
        // anchor's centre (anchor 950..990).
        lp.leftMargin = 950
        anchor.layoutParams = lp
        frame()
        assertEquals(1000f - 200f, box.x)
        assertEquals(970f, box.x + arrow.translationX + arrow.width / 2f)
    }

    @Test
    fun `an INVISIBLE anchor keeps its popover, a GONE one closes it`() {
        popovers.show(FakeContent(), anchor)
        frame()
        // The in-app page hides its results for a frame or two per re-fit.
        anchor.visibility = View.INVISIBLE
        frame()
        idle()
        assertTrue(popovers.isShowing)
        anchor.visibility = View.GONE
        frame()
        idle()
        assertFalse(popovers.isShowing)
    }

    @Test
    fun `no anchor on screen, no popover`() {
        anchor.visibility = View.GONE
        popovers.show(FakeContent(), anchor)
        assertFalse(popovers.isShowing)
        assertEquals(2, root.childCount)
    }

    @Test
    fun `a popover whose anchor stops being shown closes itself`() {
        popovers.show(FakeContent(), anchor)
        frame()
        assertTrue(popovers.isShowing)
        anchor.visibility = View.GONE
        frame()
        assertTrue("posted, not mid-draw", popovers.isShowing)
        idle()
        assertFalse(popovers.isShowing)
    }

    @Test
    fun `a stale posted close never takes a newer popover`() {
        popovers.show(FakeContent(), anchor)
        anchor.visibility = View.GONE
        frame()
        anchor.visibility = View.VISIBLE
        val newer = FakeContent()
        popovers.show(newer, anchor)
        idle()
        assertSame(newer, popovers.content)
    }

    @Test
    fun `native focus moves in, stays trapped, and returns to the anchor`() {
        assertTrue(anchor.requestFocus())
        val content = FakeContent()
        popovers.show(content, anchor)
        frame()
        assertTrue("the first row took focus", content.rows[0].isFocused)
        assertSame(content.rows[1], content.rows[0].focusSearch(View.FOCUS_DOWN))
        assertSame("no walking off the last row", content.rows[1], content.rows[1].focusSearch(View.FOCUS_DOWN))
        popovers.dismiss()
        assertTrue(anchor.isFocused)
    }

    @Test
    fun `release closes and refuses later shows`() {
        popovers.show(FakeContent(), anchor)
        popovers.release()
        assertFalse(popovers.isShowing)
        popovers.show(FakeContent(), anchor)
        assertFalse(popovers.isShowing)
        assertNull(popovers.navActions())
    }

    @Test
    fun `nav targets come from the showing content`() {
        assertNull(popovers.navActions())
        val content = FakeContent()
        popovers.show(content, anchor)
        assertEquals(content.rows, popovers.navActions()?.map { it.view })
    }
}
