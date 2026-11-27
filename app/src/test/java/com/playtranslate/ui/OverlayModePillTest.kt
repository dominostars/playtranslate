package com.playtranslate.ui

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.OverlayMode
import com.playtranslate.R
import com.playtranslate.overlayThemedContext
import com.playtranslate.themeColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * [OverlayModePill]: the segments and the X while expanded, the mode's glyph
 * while collapsed, the two callbacks, an idempotent render, and the morph
 * between the two states: one capsule, the disc the bar's height, its end
 * edge where the host pins it. While the capture reads, the disc with a
 * spinner whatever the user left; and the fade as it comes up and as it
 * goes, its bar closing on the way out. The shoulder buttons' step: the
 * segment on that side picked as a tap picks it, wrapping, mirrored right
 * to left, collapsed or expanded left as it was, and none while the pill
 * takes no tap.
 */
@RunWith(RobolectricTestRunner::class)
class OverlayModePillTest {

    private val ctx: Context = overlayThemedContext(ApplicationProvider.getApplicationContext())
    private val options = listOf(
        "Translation" to OverlayMode.TRANSLATION,
        "Furigana" to OverlayMode.FURIGANA,
        "None" to OverlayMode.NONE,
    )
    private val picked = mutableListOf<OverlayMode>()
    private val collapses = mutableListOf<Boolean>()

    private fun pill() = OverlayModePill(ctx).also {
        it.onSelect = { m -> picked += m }
        it.onCollapsedChanged = { c -> collapses += c }
    }

    private fun descendants(v: View): List<View> =
        if (v is ViewGroup) listOf(v) + (0 until v.childCount).flatMap { descendants(v.getChildAt(it)) } else listOf(v)

    private fun segments(p: OverlayModePill): List<TextView> =
        descendants(p).filterIsInstance<TextView>().filter { it.isClickable }

    private fun closeButton(p: OverlayModePill): ImageView? =
        descendants(p).filterIsInstance<ImageView>()
            .firstOrNull { it.contentDescription == ctx.getString(R.string.capture_overlay_pill_collapse_cd) }

    /** The bar: the segments and the X. */
    private fun bar(p: OverlayModePill): View = closeButton(p)!!.parent as View

    /** The disc's glyph: the one ImageView that is not the X. */
    private fun disc(p: OverlayModePill): ImageView =
        descendants(p).filterIsInstance<ImageView>().single { it !== closeButton(p) }

    /** The disc's sign that the capture is still reading. */
    private fun spinner(p: OverlayModePill): ProgressBar =
        descendants(p).filterIsInstance<ProgressBar>().single()

    /** [v] is drawn and takes touches: it and every ancestor below [p]
     *  visible, none faded out. */
    private fun shown(v: View, p: OverlayModePill): Boolean =
        generateSequence(v) { it.parent as? View }.takeWhile { it !== p }
            .all { it.visibility == View.VISIBLE && it.alpha > 0f }

    private fun idleFor(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    /** Seeks the running morph to its midpoint. Robolectric runs every
     *  animation frame the moment it is posted, so an idle drains the morph
     *  whole; and with the Choreographer paused the animator's frame time
     *  never moves. The seek is the one way to stand inside it. */
    private fun seekMidMorph(p: OverlayModePill) = seekMorph(p, 0.5f)

    private fun seekMorph(p: OverlayModePill, fraction: Float) {
        val morph = OverlayModePill::class.java.getDeclaredField("morph")
            .apply { isAccessible = true }.get(p) as ValueAnimator
        assertTrue("a morph is running", morph.isStarted)
        morph.setCurrentFraction(fraction)
    }

    /** The pill as the sheet hosts it: wrapped, pinned to the top end of a
     *  parent. [layout] runs the traversal a window would. */
    private inner class Hosted(rtl: Boolean = false) {
        val pill = pill()
        private val parent = FrameLayout(ctx).apply {
            layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
            addView(
                pill,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.END,
                ),
            )
        }

        fun layout() {
            parent.measure(
                MeasureSpec.makeMeasureSpec(PARENT_W, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(PARENT_H, MeasureSpec.EXACTLY),
            )
            parent.layout(0, 0, PARENT_W, PARENT_H)
        }
    }

    class Host : Activity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_PlayTranslate)
            super.onCreate(savedInstanceState)
        }
    }

    /** A real tap at [v]'s centre, dispatched from [root] down, so whatever
     *  sits on top there takes it, as a finger would; then the morph it
     *  starts runs out. */
    private fun tapAt(root: View, v: View) {
        var x = v.width / 2f
        var y = v.height / 2f
        var cur = v
        while (cur !== root) {
            x += cur.left
            y += cur.top
            cur = cur.parent as View
        }
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val ev = MotionEvent.obtain(0L, 0L, action, x, y, 0)
            root.dispatchTouchEvent(ev)
            ev.recycle()
        }
        idleFor(MORPH_RUN_OUT_MS)
    }

    @Test fun `expanded shows one segment per option and the X`() {
        val p = pill()
        p.render(options, OverlayMode.TRANSLATION, collapsed = false)
        assertEquals(View.VISIBLE, p.visibility)
        assertEquals(listOf("Translation", "Furigana", "None"), segments(p).map { it.text.toString() })
        assertTrue(segments(p).all { shown(it, p) })
        assertTrue(shown(closeButton(p)!!, p))
        assertEquals("the disc takes no touch, focus or reading behind the bar", View.INVISIBLE, disc(p).visibility)
    }

    @Test fun `a segment tap reports the mode and keeps the control expanded`() {
        val p = pill()
        p.render(options, OverlayMode.TRANSLATION, collapsed = false)
        segments(p)[2].performClick()
        assertEquals(listOf(OverlayMode.NONE), picked)
        assertFalse(p.collapsed)
        assertEquals(3, segments(p).size)
        assertTrue(segments(p).all { shown(it, p) })
        assertTrue(collapses.isEmpty())
    }

    @Test fun `the X collapses to the selected mode's glyph and says so`() {
        val p = pill()
        p.render(options, OverlayMode.FURIGANA, collapsed = false)
        closeButton(p)!!.performClick()
        assertTrue("the state flips at the tap", p.collapsed)
        assertEquals(listOf(true), collapses)
        idleFor(MORPH_RUN_OUT_MS)
        assertTrue(segments(p).none { shown(it, p) })
        assertEquals("the bar takes no touch, focus or reading", View.INVISIBLE, bar(p).visibility)
        assertTrue(shown(disc(p), p))
        assertEquals(
            ctx.getString(R.string.capture_overlay_pill_collapsed_cd, "Furigana"),
            disc(p).contentDescription,
        )
    }

    @Test fun `a tap on the disc expands again`() {
        val p = pill()
        p.render(options, OverlayMode.NONE, collapsed = true)
        assertTrue(p.collapsed)
        assertTrue(shown(disc(p), p))
        assertTrue(segments(p).none { shown(it, p) })
        assertEquals(View.INVISIBLE, bar(p).visibility)
        disc(p).performClick()
        assertFalse(p.collapsed)
        assertEquals(listOf(false), collapses)
        idleFor(MORPH_RUN_OUT_MS)
        assertEquals(3, segments(p).size)
        assertTrue(segments(p).all { shown(it, p) })
        assertEquals(View.INVISIBLE, disc(p).visibility)
    }

    @Test fun `a pick while expanded is the glyph the disc shows`() {
        val p = pill()
        p.render(options, OverlayMode.TRANSLATION, collapsed = false)
        segments(p)[1].performClick()
        closeButton(p)!!.performClick()
        idleFor(MORPH_RUN_OUT_MS)
        assertEquals(
            ctx.getString(R.string.capture_overlay_pill_collapsed_cd, "Furigana"),
            disc(p).contentDescription,
        )
    }

    @Test fun `the same render keeps the view, a change rebuilds it`() {
        val p = pill()
        p.render(options, OverlayMode.TRANSLATION, collapsed = false)
        val first = p.getChildAt(0)
        p.render(options, OverlayMode.TRANSLATION, collapsed = false)
        assertSame(first, p.getChildAt(0))
        p.render(options, OverlayMode.NONE, collapsed = false)
        assertNotSame(first, p.getChildAt(0))
    }

    @Test fun `no options fades the control out, then it holds nothing`() {
        val p = pill()
        p.render(options, OverlayMode.TRANSLATION, collapsed = false)
        idleFor(MORPH_RUN_OUT_MS)
        p.render(emptyList(), OverlayMode.TRANSLATION, collapsed = false)
        assertEquals("up while it fades", View.VISIBLE, p.visibility)
        idleFor(MORPH_RUN_OUT_MS)
        assertEquals(View.GONE, p.visibility)
        assertEquals(0, p.childCount)
    }

    @Test fun `every mode has a glyph, translation the translate symbol`() {
        for (mode in OverlayMode.entries) {
            assertTrue(mode.name, OverlayModePill.glyphOf(mode) != 0)
        }
        assertEquals(R.drawable.ic_translate, OverlayModePill.glyphOf(OverlayMode.TRANSLATION))
    }

    // ── the capsule ──────────────────────────────────────────────────────

    @Test fun `the disc is the bar's height, round, and set by a render with no morph`() {
        val h = Hosted()
        h.pill.render(options, OverlayMode.TRANSLATION, collapsed = false)
        h.layout()
        val barH = h.pill.height
        val barW = h.pill.width
        assertTrue("a bar wider than it is tall", barW > barH)
        val capsule = h.pill.background as GradientDrawable
        assertEquals("a full pill", barH / 2f, capsule.cornerRadius, 0.01f)

        // The host's remembered state on a new capture: no clock advance.
        h.pill.render(options, OverlayMode.TRANSLATION, collapsed = true)
        h.layout()
        assertEquals("as tall as the bar", barH, h.pill.height)
        assertEquals("the bar's own height, measured whole", bar(h.pill).measuredHeight, h.pill.height)
        assertEquals("as wide as it is tall", barH, h.pill.width)
        assertEquals("a circle", barH / 2f, capsule.cornerRadius, 0.01f)
    }

    /** [v]'s bounds in [ancestor]'s coordinates. */
    private fun boundsIn(v: View, ancestor: View): Rect {
        var x = 0
        var y = 0
        var cur = v
        while (cur !== ancestor) {
            x += cur.left
            y += cur.top
            cur = cur.parent as View
        }
        return Rect(x, y, x + v.width, y + v.height)
    }

    @Test fun `the track and the X sit concentric in the capsule's round ends, either direction`() {
        for (rtl in listOf(false, true)) {
            val h = Hosted(rtl)
            h.pill.render(options, OverlayMode.FURIGANA, collapsed = false)
            h.layout()
            val p = h.pill
            assertEquals("a full pill ($rtl)", p.height / 2f, (p.background as GradientDrawable).cornerRadius, 0.01f)
            val track = (bar(p) as ViewGroup).getChildAt(0).let { (it as ViewGroup).getChildAt(0) }
            val highlight = (track as ViewGroup).getChildAt(0)
            assertEquals("the track's ends", track.height / 2f, (track.background as GradientDrawable).cornerRadius, 0.01f)
            assertEquals("the highlight's ends", highlight.height / 2f, (highlight.background as GradientDrawable).cornerRadius, 0.01f)
            // Full pills one inset apart on every side share their ends' centres.
            val t = boundsIn(track, p)
            val x = boundsIn(closeButton(p)!!, p)
            val inset = t.top
            assertTrue("an inset at all", inset > 0)
            assertEquals("the track's bottom inset ($rtl)", inset, p.height - t.bottom)
            assertEquals("the track's start inset ($rtl)", inset, if (rtl) p.width - t.right else t.left)
            assertEquals("the X's top inset ($rtl)", inset, x.top)
            assertEquals("the X's bottom inset ($rtl)", inset, p.height - x.bottom)
            assertEquals("the X's end inset ($rtl)", inset, if (rtl) x.left else p.width - x.right)
        }
    }

    @Test fun `the X shrinks the bar into the disc at the end edge`() {
        val h = Hosted()
        h.pill.render(options, OverlayMode.TRANSLATION, collapsed = false)
        h.layout()
        val barW = h.pill.width
        val barH = h.pill.height
        val end = h.pill.right
        closeButton(h.pill)!!.performClick()
        h.layout()
        assertEquals("the tap starts from the bar, no jump", barW, h.pill.width)

        seekMidMorph(h.pill)
        h.layout()
        assertTrue("between the bar and the disc: ${h.pill.width}", h.pill.width in (barH + 1) until barW)
        assertEquals("the end edge stays put", end, h.pill.right)
        assertEquals(barH, h.pill.height)
        assertEquals("the bar is clipped, not squeezed", barW, bar(h.pill).width)
        assertEquals("the bar ends at the end edge", h.pill.width, bar(h.pill).right)
        assertEquals("the glyph comes in where the X was, at the end edge", h.pill.width, disc(h.pill).right)
        assertFalse("never both contents at once", shown(bar(h.pill), h.pill) && shown(disc(h.pill), h.pill))

        idleFor(MORPH_RUN_OUT_MS)
        h.layout()
        assertEquals(barH, h.pill.width)
        assertEquals(end, h.pill.right)
        assertTrue(shown(disc(h.pill), h.pill))
        assertFalse(shown(bar(h.pill), h.pill))
    }

    @Test fun `the disc's tap grows the bar out of it at the end edge`() {
        val h = Hosted()
        h.pill.render(options, OverlayMode.TRANSLATION, collapsed = false)
        h.layout()
        val barW = h.pill.width
        h.pill.render(options, OverlayMode.TRANSLATION, collapsed = true)
        h.layout()
        val barH = h.pill.height
        val end = h.pill.right
        disc(h.pill).performClick()
        h.layout()
        assertEquals("the tap starts from the disc, no jump", barH, h.pill.width)

        seekMidMorph(h.pill)
        h.layout()
        assertTrue("between the disc and the bar: ${h.pill.width}", h.pill.width in (barH + 1) until barW)
        assertEquals("the end edge stays put", end, h.pill.right)
        assertEquals("the bar is clipped, not squeezed", barW, bar(h.pill).width)
        assertFalse("never both contents at once", shown(bar(h.pill), h.pill) && shown(disc(h.pill), h.pill))

        idleFor(MORPH_RUN_OUT_MS)
        h.layout()
        assertEquals(barW, h.pill.width)
        assertEquals(end, h.pill.right)
        assertTrue(segments(h.pill).all { shown(it, h.pill) })
        assertFalse(shown(disc(h.pill), h.pill))
    }

    @Test fun `right to left, the end edge is the left one`() {
        val h = Hosted(rtl = true)
        h.pill.render(options, OverlayMode.TRANSLATION, collapsed = false)
        h.layout()
        val barW = h.pill.width
        val barH = h.pill.height
        val end = h.pill.left
        assertEquals("pinned to the parent's left", 0, end)
        closeButton(h.pill)!!.performClick()

        seekMidMorph(h.pill)
        h.layout()
        assertTrue("mid-morph: ${h.pill.width}", h.pill.width in (barH + 1) until barW)
        assertEquals("the end edge stays put", end, h.pill.left)
        assertEquals("the bar is clipped, not squeezed", barW, bar(h.pill).width)
        assertEquals("the bar starts at the end edge", 0, bar(h.pill).left)
        assertEquals("the glyph comes in where the X was, at the end edge", 0, disc(h.pill).left)

        idleFor(MORPH_RUN_OUT_MS)
        h.layout()
        assertEquals(barH, h.pill.width)
        assertEquals(end, h.pill.left)
        assertEquals("the disc at the end edge", 0, disc(h.pill).left)
    }

    @Test fun `the X and the disc take a real tap where they sit`() {
        val activity = Robolectric.buildActivity(Host::class.java).setup().get()
        val p = pill()
        val root = FrameLayout(activity).apply {
            addView(
                p,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.END,
                ),
            )
        }
        activity.setContentView(root)
        p.render(options, OverlayMode.TRANSLATION, collapsed = false)
        idleFor(MORPH_RUN_OUT_MS)
        tapAt(root, closeButton(p)!!)
        assertTrue("the X takes it, not a faded-out disc over it", p.collapsed)
        tapAt(root, disc(p))
        assertFalse("the disc takes it", p.collapsed)
        assertEquals(listOf(true, false), collapses)
    }

    // ── while the capture reads ──────────────────────────────────────────

    @Test fun `while the capture reads the pill is the disc with a spinner, whatever the user left`() {
        for (left in listOf(false, true)) {
            val h = Hosted()
            h.pill.render(options, OverlayMode.TRANSLATION, collapsed = left, loading = true)
            h.layout()
            val p = h.pill
            assertEquals("a disc (collapsed $left)", p.height, p.width)
            assertTrue(shown(spinner(p), p))
            assertFalse("no glyph under the spinner", shown(disc(p), p))
            assertFalse(shown(bar(p), p))
            assertEquals("what the user left is kept", left, p.collapsed)
            val s = boundsIn(spinner(p), p)
            assertTrue("a spinner of some size", s.width() > 0 && s.width() < p.width)
            assertTrue("in the middle of the disc", kotlin.math.abs(s.left - (p.width - s.right)) <= 1)
            assertTrue("in the middle of the disc", kotlin.math.abs(s.top - (p.height - s.bottom)) <= 1)
        }
        assertTrue(collapses.isEmpty())
    }

    @Test fun `the read ending opens the bar the user left expanded, the spinner fading out of the disc`() {
        val h = Hosted()
        val p = h.pill
        p.render(options, OverlayMode.TRANSLATION, collapsed = false, loading = true)
        h.layout()
        val discW = p.width
        val end = p.right
        p.render(options, OverlayMode.TRANSLATION, collapsed = false, loading = false)
        h.layout()
        assertEquals("it opens from the disc, no jump", discW, p.width)
        var last = discW
        for (f in listOf(0.1f, 0.3f, 0.5f, 0.7f, 0.9f)) {
            seekMorph(p, f)
            h.layout()
            assertTrue("growing at $f: ${p.width}", p.width > last)
            last = p.width
            assertEquals("the end edge stays put", end, p.right)
            assertFalse("the glyph never flashes on the way ($f)", shown(disc(p), p))
            assertFalse("never both contents at once ($f)", shown(bar(p), p) && shown(spinner(p), p))
        }
        idleFor(MORPH_RUN_OUT_MS)
        h.layout()
        assertTrue(p.width > last)
        assertTrue(segments(p).all { shown(it, p) })
        assertEquals("nothing spins once the control is up", View.INVISIBLE, spinner(p).visibility)
        assertEquals(View.INVISIBLE, disc(p).visibility)
        assertTrue("opening it was not the user's doing", collapses.isEmpty())
    }

    @Test fun `the read ending in a disc the user left collapsed swaps the spinner for the glyph`() {
        val h = Hosted()
        val p = h.pill
        p.render(options, OverlayMode.FURIGANA, collapsed = true, loading = true)
        h.layout()
        val discW = p.width
        p.render(options, OverlayMode.FURIGANA, collapsed = true, loading = false)
        for (f in listOf(0.1f, 0.3f, 0.5f, 0.7f, 0.9f)) {
            seekMorph(p, f)
            h.layout()
            assertEquals("a disc throughout ($f)", discW, p.width)
            assertFalse("never both contents at once ($f)", shown(disc(p), p) && shown(spinner(p), p))
        }
        idleFor(MORPH_RUN_OUT_MS)
        h.layout()
        assertEquals(discW, p.width)
        assertTrue(shown(disc(p), p))
        assertEquals("nothing spins once the control is up", View.INVISIBLE, spinner(p).visibility)
        assertEquals(
            ctx.getString(R.string.capture_overlay_pill_collapsed_cd, "Furigana"),
            disc(p).contentDescription,
        )
        // The disc is the control again.
        disc(p).performClick()
        assertFalse(p.collapsed)
        assertEquals(listOf(false), collapses)
    }

    @Test fun `a read that starts again closes the bar to the spinner and keeps what the user left`() {
        val h = Hosted()
        val p = h.pill
        p.render(options, OverlayMode.TRANSLATION, collapsed = false)
        idleFor(MORPH_RUN_OUT_MS)
        h.layout()
        val barW = p.width
        val barH = p.height
        val end = p.right
        p.render(options, OverlayMode.TRANSLATION, collapsed = false, loading = true)
        h.layout()
        assertEquals("it closes from the bar, no jump", barW, p.width)
        for (f in listOf(0.1f, 0.3f, 0.5f, 0.7f, 0.9f)) {
            seekMorph(p, f)
            h.layout()
            assertTrue("between the bar and the disc at $f: ${p.width}", p.width in (barH + 1) until barW)
            assertEquals("the end edge stays put", end, p.right)
            assertFalse("the glyph never flashes on the way ($f)", shown(disc(p), p))
        }
        idleFor(MORPH_RUN_OUT_MS)
        h.layout()
        assertEquals(barH, p.width)
        assertTrue(shown(spinner(p), p))
        assertFalse(shown(bar(p), p))
        assertFalse("still expanded for when the read ends", p.collapsed)
        assertTrue(collapses.isEmpty())
    }

    @Test fun `no tap changes the pill while the capture reads`() {
        val left = pill()
        left.render(options, OverlayMode.TRANSLATION, collapsed = true, loading = true)
        disc(left).performClick()
        assertTrue(left.collapsed)
        val open = pill()
        open.render(options, OverlayMode.TRANSLATION, collapsed = false, loading = true)
        closeButton(open)!!.performClick()
        assertFalse(open.collapsed)
        assertTrue(collapses.isEmpty())
    }

    /** One touch event at [v]'s centre, dispatched from [root] down. */
    private fun touch(root: View, v: View, action: Int) {
        val at = boundsIn(v, root)
        val ev = MotionEvent.obtain(0L, 0L, action, at.exactCenterX(), at.exactCenterY(), 0)
        root.dispatchTouchEvent(ev)
        ev.recycle()
    }

    /** The pill in a window, expanded and at rest, with its root. */
    private fun shownInWindow(): Pair<OverlayModePill, View> {
        val activity = Robolectric.buildActivity(Host::class.java).setup().get()
        val p = pill()
        val root = FrameLayout(activity).apply {
            addView(
                p,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.END,
                ),
            )
        }
        activity.setContentView(root)
        p.render(options, OverlayMode.TRANSLATION, collapsed = false)
        idleFor(MORPH_RUN_OUT_MS)
        return p to root
    }

    @Test fun `a segment tapped as the bar closes for a read picks nothing and moves no highlight`() {
        val (p, root) = shownInWindow()
        // A read starts again: the bar is still whole on the morph's first frame.
        p.render(options, OverlayMode.TRANSLATION, collapsed = false, loading = true)
        assertTrue("the bar is still drawn", shown(segments(p)[1], p))
        tapAt(root, segments(p)[1])
        assertTrue("no mode picked", picked.isEmpty())

        // The read ends: the same segment is still there to pick, which a
        // highlight already moved onto it would refuse.
        p.render(options, OverlayMode.TRANSLATION, collapsed = false, loading = false)
        idleFor(MORPH_RUN_OUT_MS)
        tapAt(root, segments(p)[1])
        assertEquals(listOf(OverlayMode.FURIGANA), picked)
    }

    @Test fun `a tap already down on a segment when a read starts ends as nothing`() {
        val (p, root) = shownInWindow()
        val furigana = segments(p)[1]
        touch(root, furigana, MotionEvent.ACTION_DOWN)
        assertTrue("the segment has the touch", furigana.isPressed)
        p.render(options, OverlayMode.TRANSLATION, collapsed = false, loading = true)
        touch(root, furigana, MotionEvent.ACTION_UP)
        idleFor(MORPH_RUN_OUT_MS)
        assertTrue("no mode picked", picked.isEmpty())
        assertFalse("the segment was let go", furigana.isPressed)
    }

    @Test fun `a tap on the spinner disc reaches nothing under the pill`() {
        val (p, root) = shownInWindow()
        p.render(options, OverlayMode.TRANSLATION, collapsed = false, loading = true)
        idleFor(MORPH_RUN_OUT_MS)
        val down = boundsIn(p, root).let { MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, it.exactCenterX(), it.exactCenterY(), 0) }
        assertTrue("the pill takes it", root.dispatchTouchEvent(down))
        down.recycle()
        assertTrue(picked.isEmpty())
        assertTrue(collapses.isEmpty())
        assertFalse(p.collapsed)
    }

    // ── coming up and going ──────────────────────────────────────────────

    @Test fun `the pill fades in as it comes up`() {
        val p = pill()
        assertEquals("nothing before a render", View.GONE, p.visibility)
        p.render(options, OverlayMode.TRANSLATION, collapsed = false)
        assertEquals(View.VISIBLE, p.visibility)
        assertEquals("from nothing", 0f, p.alpha, 0f)
        idleFor(MORPH_RUN_OUT_MS)
        assertEquals(1f, p.alpha, 0f)
    }

    @Test fun `leaving fades the pill out and closes its bar to the disc, with nothing reported`() {
        val h = Hosted()
        val p = h.pill
        p.render(options, OverlayMode.TRANSLATION, collapsed = false)
        idleFor(MORPH_RUN_OUT_MS)
        h.layout()
        val barW = p.width
        val barH = p.height
        val end = p.right
        p.leave(200L)
        h.layout()
        assertEquals("it closes from the bar, no jump", barW, p.width)
        assertEquals("up while it fades", View.VISIBLE, p.visibility)
        seekMidMorph(p)
        h.layout()
        assertTrue("between the bar and the disc: ${p.width}", p.width in (barH + 1) until barW)
        assertEquals("the end edge stays put", end, p.right)

        idleFor(MORPH_RUN_OUT_MS)
        assertEquals(View.GONE, p.visibility)
        assertEquals("nothing held", 0, p.childCount)
        assertFalse("what the user left is not touched", p.collapsed)
        assertTrue(collapses.isEmpty())

        // The same pill comes back as the user left it.
        p.render(options, OverlayMode.TRANSLATION, collapsed = false)
        idleFor(MORPH_RUN_OUT_MS)
        h.layout()
        assertEquals(View.VISIBLE, p.visibility)
        assertEquals(1f, p.alpha, 0f)
        assertEquals(barW, p.width)
    }

    @Test fun `a pill on its way out takes no tap`() {
        val activity = Robolectric.buildActivity(Host::class.java).setup().get()
        val p = pill()
        val root = FrameLayout(activity).apply {
            addView(
                p,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.END,
                ),
            )
        }
        activity.setContentView(root)
        p.render(options, OverlayMode.TRANSLATION, collapsed = false)
        idleFor(MORPH_RUN_OUT_MS)
        val furigana = segments(p)[1]
        p.leave()
        tapAt(root, furigana)
        assertTrue("no mode picked", picked.isEmpty())
        assertTrue(collapses.isEmpty())
    }

    @Test fun `a render with something to show brings back a pill on its way out`() {
        val p = pill()
        p.render(options, OverlayMode.TRANSLATION, collapsed = false)
        idleFor(MORPH_RUN_OUT_MS)
        // No text, then a read again before the fade has ended.
        p.render(emptyList(), OverlayMode.TRANSLATION, collapsed = false)
        p.render(options, OverlayMode.TRANSLATION, collapsed = false, loading = true)
        idleFor(MORPH_RUN_OUT_MS)
        assertEquals(View.VISIBLE, p.visibility)
        assertEquals(1f, p.alpha, 0f)
        assertTrue("the fade cut short dropped nothing", shown(spinner(p), p))
    }

    // ── the shoulder buttons' step ───────────────────────────────────────

    @Test fun `a step picks the segment on that side as a tap does, and a render of the pick rebuilds nothing`() {
        val p = pill()
        p.render(options, OverlayMode.FURIGANA, collapsed = false)
        val bar = p.getChildAt(0)
        p.step(toRight = true)
        assertEquals(listOf(OverlayMode.NONE), picked)
        assertEquals("the picked segment is the highlighted one", ctx.themeColor(R.attr.ptSurface), segments(p)[2].currentTextColor)
        assertEquals(ctx.themeColor(R.attr.ptTextMuted), segments(p)[1].currentTextColor)
        // The host renders the pick it was told of, as the sheet does.
        p.render(options, OverlayMode.NONE, collapsed = false)
        assertSame("the step kept the pill's selection, so nothing is rebuilt", bar, p.getChildAt(0))
        p.step(toRight = false)
        assertEquals(listOf(OverlayMode.NONE, OverlayMode.FURIGANA), picked)
        assertFalse(p.collapsed)
        assertTrue(collapses.isEmpty())
        assertTrue(segments(p).all { shown(it, p) })
    }

    @Test fun `steps wrap at both ends`() {
        val p = pill()
        p.render(options, OverlayMode.NONE, collapsed = false)
        p.step(toRight = true)
        p.step(toRight = false)
        assertEquals(listOf(OverlayMode.TRANSLATION, OverlayMode.NONE), picked)
    }

    @Test fun `right to left, the segment to the right is the one before`() {
        val h = Hosted(rtl = true)
        h.pill.render(options, OverlayMode.FURIGANA, collapsed = false)
        h.layout()
        h.pill.step(toRight = true)
        assertEquals(listOf(OverlayMode.TRANSLATION), picked)
        h.pill.step(toRight = false)
        assertEquals(listOf(OverlayMode.TRANSLATION, OverlayMode.FURIGANA), picked)
    }

    @Test fun `a step on the disc takes the new mode's glyph and stays a disc`() {
        val p = pill()
        p.render(options, OverlayMode.TRANSLATION, collapsed = true)
        p.step(toRight = true)
        assertEquals(listOf(OverlayMode.FURIGANA), picked)
        assertTrue(p.collapsed)
        assertTrue(collapses.isEmpty())
        idleFor(MORPH_RUN_OUT_MS)
        assertTrue(shown(disc(p), p))
        assertEquals(View.INVISIBLE, bar(p).visibility)
        assertEquals(
            ctx.getString(R.string.capture_overlay_pill_collapsed_cd, "Furigana"),
            disc(p).contentDescription,
        )
    }

    @Test fun `no step before a render, while the capture reads, while the pill leaves, or with one mode`() {
        val p = pill()
        assertFalse("nothing rendered", p.canStep)
        p.step(toRight = true)
        p.render(options, OverlayMode.TRANSLATION, collapsed = false, loading = true)
        assertFalse("reading", p.canStep)
        p.step(toRight = true)
        p.render(options, OverlayMode.TRANSLATION, collapsed = false)
        assertTrue(p.canStep)
        p.leave()
        assertFalse("leaving", p.canStep)
        p.step(toRight = true)
        val single = pill()
        single.render(listOf("Translation" to OverlayMode.TRANSLATION), OverlayMode.TRANSLATION, collapsed = false)
        assertFalse("one mode", single.canStep)
        single.step(toRight = true)
        assertTrue(picked.isEmpty())
    }

    private companion object {
        const val PARENT_W = 1000
        const val PARENT_H = 400
        /** Past the morph's end. */
        const val MORPH_RUN_OUT_MS = 1000L
    }
}
