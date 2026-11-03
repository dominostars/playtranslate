package com.playtranslate.ui

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
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
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.OverlayMode
import com.playtranslate.R
import com.playtranslate.overlayThemedContext
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
 * edge where the host pins it.
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
    private fun seekMidMorph(p: OverlayModePill) {
        val morph = OverlayModePill::class.java.getDeclaredField("morph")
            .apply { isAccessible = true }.get(p) as ValueAnimator
        assertTrue("a tap runs a morph", morph.isStarted)
        morph.setCurrentFraction(0.5f)
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

    @Test fun `no options hides the control`() {
        val p = pill()
        p.render(options, OverlayMode.TRANSLATION, collapsed = false)
        p.render(emptyList(), OverlayMode.TRANSLATION, collapsed = false)
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
        assertEquals("the bar's own corners", OverlayModePill.ROW_RADIUS_DP * ctx.resources.displayMetrics.density, capsule.cornerRadius, 0.01f)

        // The host's remembered state on a new capture: no clock advance.
        h.pill.render(options, OverlayMode.TRANSLATION, collapsed = true)
        h.layout()
        assertEquals("as tall as the bar", barH, h.pill.height)
        assertEquals("the bar's own height, measured whole", bar(h.pill).measuredHeight, h.pill.height)
        assertEquals("as wide as it is tall", barH, h.pill.width)
        assertEquals("a circle", barH / 2f, capsule.cornerRadius, 0.01f)
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

    private companion object {
        const val PARENT_W = 1000
        const val PARENT_H = 400
        /** Past the morph's end. */
        const val MORPH_RUN_OUT_MS = 1000L
    }
}
