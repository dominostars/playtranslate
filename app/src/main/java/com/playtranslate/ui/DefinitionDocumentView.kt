package com.playtranslate.ui

import android.content.Context
import android.graphics.Rect
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ScrollView
import androidx.annotation.VisibleForTesting
import androidx.core.widget.NestedScrollView
import kotlin.math.min

/**
 * The view of a [DefinitionDocument]: a frame as tall as the page reports
 * its rows to be, showing one [DefinitionShell]'s WebView. The host's
 * ScrollView scrolls the frame, as it does every native row around it, and
 * that scroll is the only one a finger drives.
 *
 * The page follows it in lockstep, so that the page's top edge is always
 * the top of what is visible of the frame: that is where a word's head pins
 * (a CSS sticky) while its row's definitions scroll under it. At every draw,
 * with `s` the frame's own px scrolled off above its visible part, the
 * WebView scrolls its page by `s` and the pane that holds it scrolls its
 * content back by the same amount ([lineUp]): the two cancel, so the rows
 * stay where the host put them. Both land in the frame they are drawn in:
 * the pane does it in its `computeScroll`, which the framework calls after
 * the host's own scroll step (a fling takes its step in its draw) and
 * before the WebView draws. Until the page has laid out rows for its shell
 * ([pageLaidOut]) nothing is lined up: an empty page has nowhere to scroll.
 *
 * The WebView is never taller than the host's viewport (min of the frame
 * and the viewport, [pageHeight]). A WebView that reaches past its window
 * takes the host's scroll into its visual viewport instead of its page
 * (measured on WebView 156), and a page as tall as the viewport, lined up
 * with its top, ends at its bottom. The viewport is the nearest
 * ScrollView or NestedScrollView around the frame, the scroller every
 * definitions surface hosts its page in; with none, the WebView is as tall
 * as the frame. Its height is set in layout only: in the pane's own layout,
 * and in the viewport's when the viewport alone changed size.
 *
 * The pane, not the frame, carries the scroll: the frame's own coordinates
 * stay the document's (its visible rect, its focus rect, a rect a host or
 * the document asks it to bring on screen), while a rect the page asks for
 * from inside the WebView ([View.requestRectangleOnScreen], which walks
 * scroll and not translation) still lands where the page shows it.
 *
 * It is the ONE focus stop of its document. The WebView inside never takes
 * focus (see [DefinitionShell.create]), so on an Activity page the D-pad's
 * keys arrive here and [keys] walks the document's rows with them, handing
 * focus on at the first and last. A focused frame as tall as a whole word
 * list would make the host scroll to its top, so the focus rect it reports
 * is the row the cursor is on ([getFocusedRect]), and it asks for no reveal
 * of its own on focus.
 *
 * The document's behaviour is the document's; this class forwards it, and
 * keeps the page lined up with what is visible of it.
 */
internal class DefinitionDocumentView(context: Context) : FrameLayout(context) {

    /** The document's side of focus and keys. */
    interface Keys {
        /** A key came down while this view has focus. True when the
         *  document used it; false lets focus move on. */
        fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean

        /** Focus arrived (moving in [direction], a `View.FOCUS_*` value) or left. */
        fun onFocus(gained: Boolean, direction: Int)

        /** The cursor's rect in this view's coordinates; false for none. */
        fun cursorRect(out: Rect): Boolean

        /** The document's targets for a virtual cursor (the workspace's). */
        fun navActions(): List<NavAction>
    }

    var keys: Keys? = null

    /** Holds the page and carries the line-up's scroll (class doc). */
    private val pane = Pane(context)

    /** The host's scroller around the frame while attached; null for none. */
    private var viewport: ViewGroup? = null

    /** The last viewport height that was more than nothing, for a viewport
     *  that is gone for now (a GONE scroller keeps the cap it had). 0 for
     *  none yet. */
    private var lastViewportHeight = 0

    /** Whether the page has laid out rows for the shell it shows. */
    private var lined = false

    private val visible = Rect()

    init {
        isFocusable = true
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        // The page draws the cursor on the row it is on; the platform's
        // highlight would wash the whole list.
        defaultFocusHighlightEnabled = false
        setRevealOnFocusHint(false)
        addView(pane, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /** Shows [shell]'s page in this frame, in place of any other, unscrolled
     *  until its page has laid out rows ([pageLaidOut]). */
    fun show(shell: DefinitionShell) = showPage(shell.webView)

    /** Shows [page] as a shell's WebView would be shown, for a test of the
     *  line-up and the cap with a page that is no WebView. */
    @VisibleForTesting
    fun showPageForTest(page: View) = showPage(page)

    private fun showPage(page: View) {
        pane.removeAllViews()
        (page.parent as? ViewGroup)?.removeView(page)
        lined = false
        pane.scrollTo(0, 0)
        pane.addView(page, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /** The page has laid out its rows (a layout report): it can take the
     *  scroll now, and its room for it may have changed. */
    fun pageLaidOut() {
        lined = true
        pane.invalidate()
    }

    /** The WebView this frame shows, for a test. */
    @get:VisibleForTesting
    val pageView: View? get() = pane.getChildAt(0)

    fun navActions(): List<NavAction> = keys?.navActions().orEmpty()

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        keys?.onKeyDown(keyCode, event) == true || super.onKeyDown(keyCode, event)

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        keys?.onFocus(gainFocus, direction)
    }

    override fun getFocusedRect(r: Rect) {
        if (keys?.cursorRect(r) != true) super.getFocusedRect(r)
    }

    // ── The host it lines up with ────────────────────────────────────────

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewport = scrollerAround(this)?.also { it.addOnLayoutChangeListener(viewportLaidOut) }
        viewTreeObserver.addOnScrollChangedListener(redraw)
        viewTreeObserver.addOnGlobalLayoutListener(redrawAfterLayout)
    }

    override fun onDetachedFromWindow() {
        viewport?.removeOnLayoutChangeListener(viewportLaidOut)
        viewport = null
        viewTreeObserver.removeOnScrollChangedListener(redraw)
        viewTreeObserver.removeOnGlobalLayoutListener(redrawAfterLayout)
        super.onDetachedFromWindow()
    }

    /** Any scroll in the window: the host's (in a fling one frame late,
     *  which is why the line-up itself reads the scroll at draw time) or the
     *  page's own. */
    private val redraw = ViewTreeObserver.OnScrollChangedListener { pane.invalidate() }

    /** A layout can move the frame with no scroll (a card above it grew). */
    private val redrawAfterLayout = ViewTreeObserver.OnGlobalLayoutListener { pane.invalidate() }

    /** The viewport changed size with no layout of the frame (a sheet that
     *  grows or shrinks around a list whose own height is the same): the
     *  page takes its new height in this same pass. */
    private val viewportLaidOut = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        pane.fitPage(relayout = false)
        pane.invalidate()
    }

    /** The page's height for a frame [frameHeight] tall: no taller than the
     *  viewport's visible height (its padding left out where it clips to
     *  it), nor than the frame. */
    private fun pageHeight(frameHeight: Int): Int {
        val vp = viewport
        val vh = when {
            vp == null -> 0
            vp.clipToPadding -> vp.height - vp.paddingTop - vp.paddingBottom
            else -> vp.height
        }
        if (vh > 0) lastViewportHeight = vh
        return if (lastViewportHeight > 0) min(frameHeight, lastViewportHeight) else frameHeight
    }

    /** Scrolls the page to the top of what is visible of the frame, and the
     *  pane back by the scroll the page took (class doc). The pane's scroll
     *  follows the page's actual scroll, so the rows never move even when
     *  the page holds a scroll back. Nothing visible: as it was. */
    private fun lineUp() {
        val page = pane.getChildAt(0) ?: return
        if (lined && isShown && getLocalVisibleRect(visible)) {
            val s = visible.top
            if (page.scrollY != s) {
                page.scrollTo(0, s)
                // Drawn in this pass: scrollTo alone redraws on the next frame.
                page.invalidate()
            }
        }
        val offset = -page.scrollY
        if (pane.scrollY != offset) pane.scrollTo(0, offset)
    }

    /** The frame's one child: the page's holder, as large as the frame, its
     *  page [pageHeight] tall at its top and drawn wherever its scroll puts
     *  it. */
    private inner class Pane(context: Context) : FrameLayout(context) {

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = getDefaultSize(suggestedMinimumWidth, widthMeasureSpec)
            val h = getDefaultSize(suggestedMinimumHeight, heightMeasureSpec)
            setMeasuredDimension(w, h)
            getChildAt(0)?.measure(exactly(w), exactly(pageHeight(h)))
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            fitPage(relayout = true)
        }

        /** Gives the page its size for the pane as it is now: measured
         *  again where that changed, and laid out when [relayout] (the
         *  pane's own layout) or its size changed. */
        fun fitPage(relayout: Boolean) {
            val page = getChildAt(0) ?: return
            val w = width
            val h = pageHeight(height)
            val resized = page.width != w || page.height != h
            if (page.measuredWidth != w || page.measuredHeight != h) page.measure(exactly(w), exactly(h))
            if (relayout || resized) page.layout(0, 0, w, h)
        }

        override fun computeScroll() {
            super.computeScroll()
            lineUp()
        }
    }

    private companion object {
        fun exactly(px: Int): Int = MeasureSpec.makeMeasureSpec(px.coerceAtLeast(0), MeasureSpec.EXACTLY)

        /** The nearest vertical scroller around [view]: the viewport its
         *  document is lined up with. */
        fun scrollerAround(view: View): ViewGroup? {
            var p = view.parent
            while (p is View) {
                if (p is ScrollView || p is NestedScrollView) return p as ViewGroup
                p = p.parent
            }
            return null
        }
    }
}
