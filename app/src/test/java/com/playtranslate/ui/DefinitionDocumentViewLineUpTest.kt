package com.playtranslate.ui

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.widget.NestedScrollView
import com.playtranslate.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * [DefinitionDocumentView] keeps its page lined up with what the host shows
 * of it: with `s` the frame's px scrolled off above its visible part, the
 * page scrolls by `s` and the pane holding it scrolls back by as much, so
 * the page's top edge is the top of what is visible and its rows stay where
 * the host put them. The page is never taller than the host's viewport, nor
 * than the frame. The page here is a plain view that, like a WebView with
 * less room than it was asked for, can hold a scroll back.
 *
 * A software draw (all a JVM test has) runs every view's `computeScroll`,
 * invalidated or not, so the line-up is checked by drawing, and what makes
 * the frame draw again on a device (a scroll in the window, a layout, the
 * page's layout report) is checked on its own, by the frame being dirty.
 */
@RunWith(RobolectricTestRunner::class)
class DefinitionDocumentViewLineUpTest {

    class Host : Activity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_PlayTranslate)
            super.onCreate(savedInstanceState)
        }
    }

    /** A page that scrolls no further than [room], as a WebView whose page
     *  is shorter than the scroll asked of it. */
    private class Page(ctx: Context) : View(ctx) {
        var room = Int.MAX_VALUE
        override fun scrollTo(x: Int, y: Int) = super.scrollTo(x, y.coerceAtMost(room))
    }

    private lateinit var activity: Activity
    private lateinit var viewport: ViewGroup
    private lateinit var frame: DefinitionDocumentView
    private lateinit var page: Page

    private val pane: View get() = frame.getChildAt(0)

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** The scroller a host puts the frame in. */
    private enum class Scroller { SCROLL_VIEW, NESTED, NONE }

    /**
     * A host whose [scroller] is [viewportPx] tall, holding [abovePx] of
     * other content, the frame [framePx] tall, then [belowPx] more, the
     * frame showing a fresh page.
     */
    private fun host(
        scroller: Scroller = Scroller.SCROLL_VIEW,
        viewportPx: Int = 400,
        abovePx: Int = 100,
        framePx: Int = 1000,
        belowPx: Int = 100,
    ) {
        activity = Robolectric.buildActivity(Host::class.java).setup().get()
        val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        column.addView(View(activity), LinearLayout.LayoutParams(MATCH, abovePx))
        frame = DefinitionDocumentView(activity)
        column.addView(frame, LinearLayout.LayoutParams(MATCH, framePx))
        column.addView(View(activity), LinearLayout.LayoutParams(MATCH, belowPx))
        val root = FrameLayout(activity)
        val vp: ViewGroup? = when (scroller) {
            Scroller.SCROLL_VIEW -> ScrollView(activity)
            Scroller.NESTED -> NestedScrollView(activity)
            Scroller.NONE -> null
        }
        if (vp != null) {
            vp.addView(column, ViewGroup.LayoutParams(MATCH, WRAP))
            root.addView(vp, FrameLayout.LayoutParams(MATCH, viewportPx))
            viewport = vp
        } else {
            root.addView(column, FrameLayout.LayoutParams(MATCH, WRAP))
        }
        activity.setContentView(root)
        page = Page(activity)
        frame.showPageForTest(page)
        idle()
    }

    /** One software draw of the whole window: the pane's line-up runs. */
    private fun draw() {
        val root = activity.window.decorView
        val bmp = Bitmap.createBitmap(maxOf(1, root.width), maxOf(1, root.height), Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
    }

    /** Scrolls the host to [y] and draws, as a frame of a scroll does. */
    private fun scrollTo(y: Int) {
        viewport.scrollTo(0, y)
        draw()
    }

    private fun screenTop(v: View): Int = IntArray(2).also { v.getLocationOnScreen(it) }[1]

    // ── The cap ─────────────────────────────────────────────────────────

    @Test fun `the page is as tall as the viewport, or as the frame where that is less`() {
        host(framePx = 1000)
        assertEquals(400, page.height)
        assertEquals("the frame keeps the document's height", 1000, frame.height)
        host(framePx = 300)
        assertEquals(300, page.height)
    }

    @Test fun `the viewport changing height alone gives the page its new height in that layout`() {
        host()
        assertEquals(400, page.height)
        val frameLayouts = frame.height
        viewport.layoutParams = viewport.layoutParams.apply { height = 300 }
        idle()
        assertEquals("precondition: the frame itself is unchanged", frameLayouts, frame.height)
        assertEquals(300, page.height)
        viewport.layoutParams = viewport.layoutParams.apply { height = 450 }
        idle()
        assertEquals(450, page.height)
    }

    @Test fun `a viewport that clips to its padding leaves it out, one that does not keeps its whole height`() {
        host()
        viewport.setPadding(0, 20, 0, 30)
        viewport.clipToPadding = true
        idle()
        assertEquals(350, page.height)
        viewport.clipToPadding = false
        viewport.requestLayout()
        idle()
        assertEquals(400, page.height)
    }

    @Test fun `a viewport with no height keeps the height it gave the page`() {
        host()
        assertEquals(400, page.height)
        viewport.layoutParams = viewport.layoutParams.apply { height = 0 }
        idle()
        assertEquals(400, page.height)
    }

    @Test fun `with no scroller around it the page is as tall as the frame`() {
        host(Scroller.NONE, framePx = 300)
        assertEquals(300, page.height)
    }

    // ── The line-up ─────────────────────────────────────────────────────

    @Test fun `scrolled past the frame's top, the page scrolls by as much and its pane back by the same`() {
        for (scroller in listOf(Scroller.SCROLL_VIEW, Scroller.NESTED)) {
            host(scroller)
            val label = scroller.name
            assertEquals("$label: the page has the viewport's height", 400, page.height)
            frame.pageLaidOut()
            scrollTo(350)
            assertEquals(label, 250, page.scrollY)
            assertEquals(label, -250, pane.scrollY)
            assertEquals("$label: the page's top is the top of what is visible", screenTop(viewport), screenTop(page))
            scrollTo(600)
            assertEquals(label, 500, page.scrollY)
            assertEquals(label, -500, pane.scrollY)
            assertEquals(label, screenTop(viewport), screenTop(page))
        }
    }

    @Test fun `while the frame's top is on screen nothing scrolls`() {
        host()
        frame.pageLaidOut()
        scrollTo(60)
        assertEquals(0, page.scrollY)
        assertEquals(0, pane.scrollY)
        scrollTo(350)
        scrollTo(40)
        assertEquals(0, page.scrollY)
        assertEquals(0, pane.scrollY)
    }

    @Test fun `nothing is lined up before the page has laid out rows`() {
        host()
        scrollTo(350)
        assertEquals(0, page.scrollY)
        assertEquals(0, pane.scrollY)
        frame.pageLaidOut()
        draw()
        assertEquals(250, page.scrollY)
        assertEquals(-250, pane.scrollY)
    }

    @Test fun `a page that holds the scroll back keeps its rows where the host put them`() {
        host()
        page.room = 100
        frame.pageLaidOut()
        scrollTo(350)
        assertEquals(100, page.scrollY)
        assertEquals("the pane follows the scroll the page took", -100, pane.scrollY)
        page.room = Int.MAX_VALUE
        frame.pageLaidOut()
        draw()
        assertEquals(250, page.scrollY)
        assertEquals(-250, pane.scrollY)
    }

    @Test fun `a page that scrolled itself is asked back to the visible top`() {
        host()
        frame.pageLaidOut()
        scrollTo(350)
        page.scrollTo(0, 400)
        draw()
        assertEquals(250, page.scrollY)
        assertEquals(-250, pane.scrollY)
    }

    @Test fun `with nothing of it visible the frame keeps its line-up`() {
        host(belowPx = 1000)
        frame.pageLaidOut()
        scrollTo(350)
        scrollTo(1500)
        assertEquals(250, page.scrollY)
        assertEquals(-250, pane.scrollY)
    }

    @Test fun `a new page starts unscrolled and lines up once it has laid out rows`() {
        host()
        frame.pageLaidOut()
        scrollTo(350)
        val next = Page(activity)
        frame.showPageForTest(next)
        idle()
        draw()
        assertEquals(0, next.scrollY)
        assertEquals(0, pane.scrollY)
        assertEquals("the new page has the cap", 400, next.height)
        frame.pageLaidOut()
        draw()
        assertEquals(250, next.scrollY)
        assertEquals(-250, pane.scrollY)
    }

    // ── What makes it draw again ────────────────────────────────────────

    /** Drawn twice, so nothing the line-up itself changed is still pending. */
    private fun clean() {
        frame.pageLaidOut()
        draw()
        draw()
        assertFalse("precondition: the frame is clean", frame.isDirty)
    }

    @Test fun `a scroll anywhere in the window makes the pane draw again`() {
        host()
        clean()
        // The window tells its scroll listeners as a traversal begins.
        ViewTreeObserver::class.java.getDeclaredMethod("dispatchOnScrollChanged")
            .apply { isAccessible = true }
            .invoke(frame.viewTreeObserver)
        assertTrue(frame.isDirty)
    }

    @Test fun `a layout of the window makes the pane draw again`() {
        host()
        clean()
        frame.viewTreeObserver.dispatchOnGlobalLayout()
        assertTrue(frame.isDirty)
    }

    @Test fun `a layout report of the page makes the pane draw again`() {
        host()
        clean()
        frame.pageLaidOut()
        assertTrue(frame.isDirty)
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
