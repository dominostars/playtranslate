package com.playtranslate.ui

import android.app.Activity
import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
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
 *
 * And targets drawn inside a view ([NavAction.target], the rows of a
 * definitions document), on an expanded sheet:
 *  - two targets in one view are two items, each ringed at its own rect
 *    (never the view's) and moved between like any other items, across
 *    kinds too (an ordinary button above them, and back);
 *  - A on a target activates that target, once, and never clicks the view;
 *  - the ring follows the target's live rect, and hides once it stops
 *    showing;
 *  - the cursor is matched by equality, so actions collected anew at every
 *    keypress (new but equal instances) keep it where it is;
 *  - a revalidation keeps a target that is still there, wherever it moved,
 *    and retargets to the nearest item once it is gone;
 *  - a popover closing puts the cursor back on its anchor view's own item,
 *    not on a target drawn inside that view.
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

        /** An open popover's rows; null while none is open. */
        var popoverRows: List<NavAction>? = null

        /** The sheet's actions, collected anew at each call, as the real host
         *  collects them at each keypress. */
        var actions: () -> List<NavAction> = { emptyList() }

        override val isEditing = false
        override val isPopoverOpen: Boolean get() = popoverRows != null
        override fun popoverNavActions(): List<NavAction>? = popoverRows
        override val inSliver: Boolean get() = slivered
        override fun onControllerBack() { backCalls++ }
        override fun expandFromSliver() { expandCalls++; onExpand?.invoke() }
        override fun navActions(): List<NavAction> = actions()
        override fun handleRect(out: Rect) = false
        override fun collapseToSliver() = Unit
        /** Where each UP off the top asked the host to leave from. */
        val leaves = mutableListOf<Rect>()
        /** Whether the host takes the controller off the sheet. */
        var takesLeave = true
        override fun leaveSheetUp(fromOnScreen: Rect): Boolean {
            leaves += Rect(fromOnScreen)
            return takesLeave
        }
        override fun sliverRect(out: Rect): Boolean {
            if (!slivered || !stripAtRest) return false
            out.set(strip)
            return true
        }
        override fun resizeBy(dyPx: Int) = Unit
        override fun commitResize() = Unit
        /** The sheet's source words, while expanded. */
        var words: List<Rect> = emptyList()
        override fun wordCount() = words.size
        override fun wordRect(index: Int, out: Rect): Boolean {
            out.set(words.getOrNull(index) ?: return false)
            return true
        }
        override fun wordRunIsRtl() = false
        override fun activateWord(index: Int) = Unit
        /** The scroll viewport; unset, the words' sheet has one and a parked
         *  sheet has none. */
        var viewport: Rect? = null
        override fun scrollViewportOnScreen(out: Rect): Boolean {
            out.set(viewport ?: if (words.isEmpty()) return false else Rect(0, 0, 1080, 1200))
            return true
        }
        override fun scrollBy(dy: Int) = Unit
        /** What the nav last asked to bring into view. */
        var ensured: Rect? = null
        override fun ensureVisible(itemOnScreen: Rect) {
            ensured = Rect(itemOnScreen)
        }
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

    // ── UP off the sheet's top ───────────────────────────────────────────

    /** An expanded sheet with one row of two words: the cursor's first
     *  press lands on the first word. */
    private fun expandedWithWords(): Pair<FakeHost, CaptureSheetControllerNav> {
        val host = FakeHost().apply {
            slivered = false
            words = listOf(Rect(100, 600, 200, 640), Rect(220, 600, 320, 640))
        }
        val nav = CaptureSheetControllerNav(ctx, host)
        press(nav, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(host.words[0], host.ring)
        return host to nav
    }

    @Test
    fun upWithNothingAbove_asksTheHostToLeave_andDropsTheCursorWhenItDoes() {
        val (host, nav) = expandedWithWords()
        press(nav, KeyEvent.KEYCODE_DPAD_UP)
        assertEquals("from the cursor's item", listOf(host.words[0]), host.leaves)
        assertNull(host.ring)
    }

    @Test
    fun upWithNothingAbove_keepsTheCursorWhenTheHostDeclines() {
        val (host, nav) = expandedWithWords()
        host.takesLeave = false
        press(nav, KeyEvent.KEYCODE_DPAD_UP)
        assertEquals(1, host.leaves.size)
        assertEquals(host.words[0], host.ring)
    }

    @Test
    fun otherDirectionsWithNothingThatWay_neverLeaveTheSheet() {
        val (host, nav) = expandedWithWords()
        press(nav, KeyEvent.KEYCODE_DPAD_DOWN)
        press(nav, KeyEvent.KEYCODE_DPAD_LEFT)
        press(nav, KeyEvent.KEYCODE_DPAD_RIGHT)
        press(nav, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(emptyList<Rect>(), host.leaves)
        assertEquals("the run's end, where the cursor stays", host.words[1], host.ring)
    }

    // ── Targets inside a view (a definitions document's rows) ────────────

    class Host : Activity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_PlayTranslate)
            super.onCreate(savedInstanceState)
        }
    }

    /** Where a fake target is drawn, whether it shows, and how often it was
     *  activated: one state behind every equal instance, as a document's row
     *  is one state behind the actions collected for it at each keypress. */
    private class Spot(var rect: Rect) {
        var shown = true
        var activations = 0
    }

    /** A target inside a view, equal to another exactly when both stand for
     *  the same [Spot], as a [DocNavTarget] is for the same row and action. */
    private data class FakeTarget(val spot: Spot) : NavTarget {
        override fun rectOnScreen(out: Rect): Boolean {
            if (!spot.shown) return false
            out.set(spot.rect)
            return true
        }

        override fun activate() {
            spot.activations++
        }
    }

    /** A target that needs more of the screen than itself to be seen
     *  ([NavTarget.revealRectOnScreen]): [reveal], as a document's target
     *  under a pinned word head needs the head's room above it. */
    private data class CoveredTarget(val spot: Spot, val reveal: Rect) : NavTarget {
        override fun rectOnScreen(out: Rect): Boolean {
            out.set(spot.rect)
            return true
        }

        override fun revealRectOnScreen(out: Rect): Boolean {
            out.set(reveal)
            return true
        }

        override fun activate() {
            spot.activations++
        }
    }

    /** A target that names what takes its place when a press of it removes
     *  it ([NavTarget.successor]): [next]'s target, as a document's ✕ of an
     *  explain answer names the row's explain button. */
    private data class LeavingTarget(val spot: Spot, val next: Spot) : NavTarget {
        override fun rectOnScreen(out: Rect): Boolean {
            if (!spot.shown) return false
            out.set(spot.rect)
            return true
        }

        override fun activate() {
            spot.activations++
        }

        override fun successor(): NavTarget = FakeTarget(next)
    }

    /**
     * An expanded sheet's content at fixed places on a laid-out window: an
     * ordinary [button], below it a [doc] view that draws targets (three
     * spots inside it, top to bottom, [first], [second], [third]; clicks on
     * the view itself counted in [docClicks]), and a popover [row] off to
     * the side. Nothing is collected until the test sets [FakeHost.actions].
     */
    private inner class Board {
        val activity: Activity = Robolectric.buildActivity(Host::class.java).setup().get()
        private val root = FrameLayout(activity)
        var docClicks = 0
        val button: View = place(100, 100, 200, 50)
        val doc: View = place(0, 200, 1000, 600).apply { setOnClickListener { docClicks++ } }
        val row: View = place(600, 900, 300, 50)
        val first: Spot
        val second: Spot
        val third: Spot
        val host = FakeHost().apply {
            slivered = false
            viewport = Rect(0, 0, 1000, 1000)
        }
        val nav: CaptureSheetControllerNav

        init {
            activity.setContentView(root)
            root.measure(
                View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
            )
            root.layout(0, 0, 1000, 1000)
            // The spots lie inside the document view, wherever the window
            // put it on screen.
            val d = rectOf(doc)
            first = Spot(Rect(d.left + 50, d.top + 50, d.right - 50, d.top + 100))
            second = Spot(Rect(d.left + 50, d.top + 150, d.right - 50, d.top + 200))
            third = Spot(Rect(d.left + 50, d.top + 250, d.right - 50, d.top + 300))
            nav = CaptureSheetControllerNav(activity, host)
        }

        private fun place(l: Int, t: Int, w: Int, h: Int) = View(activity).also { v ->
            root.addView(v, FrameLayout.LayoutParams(w, h, Gravity.TOP or Gravity.LEFT).apply {
                leftMargin = l
                topMargin = t
            })
        }

        /** [spot] as the host collects it: a new action and a new target,
         *  equal to the ones collected before. */
        fun inDoc(spot: Spot) = NavAction(doc, target = FakeTarget(spot))
    }

    private fun rectOf(v: View): Rect {
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        return Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
    }

    @Test
    fun `two targets inside one view are two items, each ringed at its own rect and never the view's`() {
        val b = Board()
        b.host.actions = { listOf(b.inDoc(b.first), b.inDoc(b.second)) }
        press(b.nav, KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("the first press rings the top target", b.first.rect, b.host.ring)
        press(b.nav, KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("DOWN reaches the second target, in the same view", b.second.rect, b.host.ring)
        assertNotEquals("never the view's own rect", rectOf(b.doc), b.host.ring)
        assertEquals("ringed within the scroll viewport", Rect(0, 0, 1000, 1000), b.host.ringClip)
    }

    @Test
    fun `moving onto a target brings what it needs into view, and the ring is on the target itself`() {
        val b = Board()
        val reveal = Rect(b.second.rect).apply { top -= 40 }
        b.host.actions = { listOf(b.inDoc(b.first), NavAction(b.doc, target = CoveredTarget(b.second, reveal))) }
        press(b.nav, KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("an ordinary target needs only itself", b.first.rect, b.host.ensured)
        press(b.nav, KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("the room the target needs", reveal, b.host.ensured)
        assertEquals("ringed at its own rect", b.second.rect, b.host.ring)
    }

    @Test
    fun `A on a target activates that target once and never clicks the view`() {
        val b = Board()
        b.host.actions = { listOf(b.inDoc(b.first), b.inDoc(b.second)) }
        press(b.nav, KeyEvent.KEYCODE_DPAD_DOWN)
        press(b.nav, KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("precondition: the cursor is on the second target", b.second.rect, b.host.ring)
        press(b.nav, KeyEvent.KEYCODE_BUTTON_A)
        assertEquals("the target under the cursor, once", 1, b.second.activations)
        assertEquals("not the other target", 0, b.first.activations)
        assertEquals("and no click on the view that draws them", 0, b.docClicks)
    }

    @Test
    fun `the ring follows the target's live rect, and hides once the target stops showing`() {
        val b = Board()
        b.host.actions = { listOf(b.inDoc(b.first), b.inDoc(b.second)) }
        press(b.nav, KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals(b.first.rect, b.host.ring)
        val moved = Rect(b.first.rect).apply { offset(0, 37) }
        b.first.rect = moved
        b.nav.syncRing()   // the pre-draw hook
        assertEquals("the ring is where the target is now", moved, b.host.ring)
        b.first.shown = false
        b.nav.syncRing()
        assertNull("a target that is not showing has no ring", b.host.ring)
    }

    @Test
    fun `actions collected anew at each keypress keep the cursor, matched by equality`() {
        val b = Board()
        b.host.actions = { listOf(b.inDoc(b.first), b.inDoc(b.second), b.inDoc(b.third)) }
        val once = b.host.navActions()
        val again = b.host.navActions()
        assertNotSame("precondition: each collection makes new instances", once[1].target, again[1].target)
        assertEquals("precondition: equal ones", once[1], again[1])
        press(b.nav, KeyEvent.KEYCODE_DPAD_DOWN)
        press(b.nav, KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals(b.second.rect, b.host.ring)
        press(b.nav, KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("DOWN moved on from the second target, it did not restart from the top", b.third.rect, b.host.ring)
    }

    @Test
    fun `a revalidation keeps a target that is still there, and retargets to the nearest item once it is gone`() {
        val b = Board()
        var withSecond = true
        b.host.actions = {
            listOf(NavAction(b.button), b.inDoc(b.first)) + if (withSecond) listOf(b.inDoc(b.second)) else emptyList()
        }
        press(b.nav, KeyEvent.KEYCODE_DPAD_DOWN)
        press(b.nav, KeyEvent.KEYCODE_DPAD_DOWN)
        press(b.nav, KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("precondition: the cursor is on the second target", b.second.rect, b.host.ring)

        // Still there, moved far from where the ring was: the cursor stays on
        // it (the first target, nearest the old ring, would be a retarget).
        b.second.rect = Rect(b.second.rect).apply { offset(0, 300) }
        b.nav.revalidateCursor()
        b.nav.syncRing()
        assertEquals("kept, and ringed where it is now", b.second.rect, b.host.ring)

        // Gone: the nearest item to where the ring was, which is the first
        // target and not the button listed before it.
        withSecond = false
        b.nav.revalidateCursor()
        assertEquals(b.first.rect, b.host.ring)
    }

    @Test
    fun `a target that is gone is followed to the one it named, ahead of the nearest`() {
        val b = Board()
        // Top to bottom: the target the last one names, a nearer neighbour,
        // and the one that will go (an answer's ✕ under its row's buttons).
        var withThird = true
        b.host.actions = {
            listOf(b.inDoc(b.first), b.inDoc(b.second)) +
                if (withThird) listOf(NavAction(b.doc, target = LeavingTarget(b.third, next = b.first))) else emptyList()
        }
        repeat(3) { press(b.nav, KeyEvent.KEYCODE_DPAD_DOWN) }
        assertEquals("precondition: the cursor is on the third target", b.third.rect, b.host.ring)

        withThird = false
        b.nav.revalidateCursor()
        b.nav.syncRing()

        assertEquals("on the target it named, though the second is nearer", b.first.rect, b.host.ring)
        press(b.nav, KeyEvent.KEYCODE_BUTTON_A)
        assertEquals("and A is that target's", 1, b.first.activations)
        assertEquals(0, b.second.activations)
    }

    @Test
    fun `a target that named one that is not there either is retargeted to the nearest, as any other`() {
        val b = Board()
        var withThird = true
        b.host.actions = {
            // The named target (the first spot) is not among the candidates.
            listOf(NavAction(b.button), b.inDoc(b.second)) +
                if (withThird) listOf(NavAction(b.doc, target = LeavingTarget(b.third, next = b.first))) else emptyList()
        }
        repeat(3) { press(b.nav, KeyEvent.KEYCODE_DPAD_DOWN) }
        assertEquals("precondition: the cursor is on the third target", b.third.rect, b.host.ring)

        withThird = false
        b.nav.revalidateCursor()
        b.nav.syncRing()

        assertEquals(b.second.rect, b.host.ring)
    }

    @Test
    fun `a popover closing returns the cursor to its anchor view's own item, not a target inside that view`() {
        val b = Board()
        // The target is collected before the view's own action, so the first
        // action on the anchor view is the target.
        b.host.actions = { listOf(b.inDoc(b.first), NavAction(b.doc)) }
        b.host.popoverRows = listOf(NavAction(b.row))
        press(b.nav, KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("precondition: the cursor is on the popover's row", rectOf(b.row), b.host.ring)
        b.host.popoverRows = null
        b.nav.onPopoverChanged(open = false, anchor = b.doc)
        assertEquals("the anchor view's own rect", rectOf(b.doc), b.host.ring)
        press(b.nav, KeyEvent.KEYCODE_BUTTON_A)
        assertEquals("the cursor is the view's: A clicks it", 1, b.docClicks)
        assertEquals("and activates no target", 0, b.first.activations)
    }

    @Test
    fun `spatial moves cross kinds, from a target up to the button above it and back down`() {
        val b = Board()
        b.host.actions = { listOf(NavAction(b.button), b.inDoc(b.first), b.inDoc(b.second)) }
        press(b.nav, KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("the first press rings the button, topmost", rectOf(b.button), b.host.ring)
        press(b.nav, KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("DOWN from the button reaches the first target", b.first.rect, b.host.ring)
        press(b.nav, KeyEvent.KEYCODE_DPAD_UP)
        assertEquals("UP from the target reaches the button", rectOf(b.button), b.host.ring)
        press(b.nav, KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals(b.first.rect, b.host.ring)
    }
}
