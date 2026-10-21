package com.playtranslate.ui

import android.content.Context
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The editor keeps its box ordered and inside the screen, and a drag holds
 * each axis at [RegionDragView.MIN_EXTENT_DP], a physical size rather than a
 * share of the screen. Its clamps once assumed the box was already that big:
 * the top edge's upper bound was the bottom less the minimum, below the lower
 * bound, zero, for a box whose bottom sat within the minimum of the top of
 * the screen, and Kotlin's coerceIn throws on a crossed range. A 3.3.0 crash
 * (Galaxy S23+, Android 16, 2026-10-07) seeded the editor with the icon
 * menu's drag-to-select box, 42 px wide at the left edge, and the first drag
 * of that box threw from the top-left corner's left clamp.
 *
 * Two rules follow. A seed is kept as it comes, not grown to the minimum: the
 * camera's crop editor confirms an untouched box back as a change, so growth
 * on open would rerun its review. And of two opposite handles both within
 * reach, the finger is on the nearer one: a sliver has every touch within
 * reach of both, and a fixed order gave its right handle to the left edge,
 * which the minimum-size rule then held against the screen, so nothing
 * could widen it.
 *
 * Phone density: the touch zone is 52 dp, 146 px on the S23+ (2.8x). At
 * Robolectric's default 1x it is 52 px, and the handle case's 117 px box is
 * then out of reach of both edges at once, so the handle cases would not
 * tell the nearest-edge rule from the old fixed order.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "xxhdpi")
class RegionDragViewSeedTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private val density get() = ctx.resources.displayMetrics.density
    /** The floor as a fraction of each axis at this test's density. */
    private val minX get() = RegionDragView.MIN_EXTENT_DP * density / W
    private val minY get() = RegionDragView.MIN_EXTENT_DP * density / H

    private fun view(): RegionDragView = RegionDragView(ctx).also {
        it.measure(
            View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY),
        )
        it.layout(0, 0, W, H)
    }

    private fun RegionDragView.touch(action: Int, x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        val ev = MotionEvent.obtain(t, t, action, x, y, 0)
        try { onTouchEvent(ev) } finally { ev.recycle() }
    }

    private fun RegionDragView.drag(fromX: Float, fromY: Float, toX: Float, toY: Float) {
        touch(MotionEvent.ACTION_DOWN, fromX, fromY)
        touch(MotionEvent.ACTION_MOVE, toX, toY)
        touch(MotionEvent.ACTION_UP, toX, toY)
    }

    private fun RegionDragView.assertOnScreen() {
        assertTrue("top $topFraction", topFraction >= 0f)
        assertTrue("left $leftFraction", leftFraction >= 0f)
        assertTrue("bottom $bottomFraction", bottomFraction <= 1f)
        assertTrue("right $rightFraction", rightFraction <= 1f)
        assertTrue("top $topFraction over bottom $bottomFraction", topFraction <= bottomFraction)
        assertTrue("left $leftFraction over right $rightFraction", leftFraction <= rightFraction)
    }

    // ── The field case ──────────────────────────────────────────────────

    @Test fun `the sliver survives a drag of its top-left corner`() {
        val v = view()
        v.setRegion(top = 0.3f, bottom = 0.6f, left = 0f, right = SLIVER)
        // The retraced 3.3.0 frame: the TOP_LEFT target's left clamp,
        // RegionDragView.kt:265, with right - 0.05 as its upper bound.
        v.drag(fromX = 10f, fromY = H * 0.3f + 10f, toX = 30f, toY = H * 0.3f + 30f)
        v.assertOnScreen()
        assertEquals(0f, v.leftFraction, EPS)
        assertEquals(SLIVER, v.rightFraction, EPS)
    }

    @Test fun `the sliver survives a drag of its left edge`() {
        val v = view()
        v.setRegion(top = 0.3f, bottom = 0.6f, left = 0f, right = SLIVER)
        v.drag(fromX = 10f, fromY = H * 0.45f, toX = 30f, toY = H * 0.45f)
        v.assertOnScreen()
        assertEquals(0f, v.leftFraction, EPS)
    }

    @Test fun `the sliver widens from its right handle`() {
        val v = view()
        v.setRegion(top = 0.3f, bottom = 0.6f, left = 0f, right = SLIVER)
        // On the right handle, 42 px in: within reach of both edges, nearer
        // the right one.
        v.drag(fromX = W * SLIVER, fromY = H * 0.45f, toX = 300f, toY = H * 0.45f)
        v.assertOnScreen()
        assertEquals(0f, v.leftFraction, EPS)
        assertEquals(300f / W, v.rightFraction, EPS)
    }

    @Test fun `a sliver under the top edge survives a drag of its top edge and grows from its bottom handle`() {
        val v = view()
        v.setRegion(top = 0f, bottom = SLIVER_TALL, left = 0.2f, right = 0.8f)
        v.drag(fromX = W * 0.5f, fromY = 10f, toX = W * 0.5f, toY = 40f)
        v.assertOnScreen()
        assertEquals(0f, v.topFraction, EPS)
        assertEquals(SLIVER_TALL, v.bottomFraction, EPS)
        v.drag(fromX = W * 0.5f, fromY = H * SLIVER_TALL, toX = W * 0.5f, toY = 400f)
        v.assertOnScreen()
        assertEquals(400f / H, v.bottomFraction, EPS)
    }

    @Test fun `a box larger than the screen survives being moved`() {
        val v = view()
        v.setRegion(top = -0.02f, bottom = 1.02f, left = -0.1f, right = 1.1f)
        v.drag(fromX = W * 0.5f, fromY = H * 0.5f, toX = W * 0.5f + 40f, toY = H * 0.5f + 40f)
        v.assertOnScreen()
        assertEquals(0f, v.topFraction, EPS)
        assertEquals(1f, v.bottomFraction, EPS)
    }

    // ── Seeding ─────────────────────────────────────────────────────────

    @Test fun `a seeded box is ordered and clamped to the screen, and kept under the minimum size`() {
        val v = view()
        v.setRegion(top = 0.9f, bottom = 0.4f, left = 1.3f, right = 0.98f)
        assertEquals(0.4f, v.topFraction, 0f)
        assertEquals(0.9f, v.bottomFraction, 0f)
        assertEquals(0.98f, v.leftFraction, 0f)
        assertEquals(1f, v.rightFraction, 0f)
    }

    @Test fun `a tiny box is seeded as it is, so an untouched confirm hands it back unchanged`() {
        val v = view()
        v.setRegion(top = 0.5f, bottom = 0.51f, left = 0.5f, right = 0.505f)
        assertEquals(0.5f, v.topFraction, 0f)
        assertEquals(0.51f, v.bottomFraction, 0f)
        assertEquals(0.5f, v.leftFraction, 0f)
        assertEquals(0.505f, v.rightFraction, 0f)
    }

    @Test fun `a well-formed box is seeded unchanged`() {
        val v = view()
        v.setRegion(top = 0.25f, bottom = 0.75f, left = 0.1f, right = 0.9f)
        assertEquals(0.25f, v.topFraction, 0f)
        assertEquals(0.75f, v.bottomFraction, 0f)
        assertEquals(0.1f, v.leftFraction, 0f)
        assertEquals(0.9f, v.rightFraction, 0f)
    }

    // ── Dragging ────────────────────────────────────────────────────────

    @Test fun `the first drag of an edge grows a tiny box to the minimum size`() {
        val v = view()
        v.setRegion(top = 0.5f, bottom = 0.51f, left = 0.3f, right = 0.7f)
        v.drag(fromX = W * 0.5f, fromY = H * 0.5f, toX = W * 0.5f, toY = H * 0.5f + 1f)
        v.assertOnScreen()
        assertEquals(0.51f - minY, v.topFraction, EPS)
    }

    @Test fun `dragging an edge onto its opposite stops at the minimum size`() {
        val a = view()
        a.setRegion(top = 0.4f, bottom = 0.6f, left = 0.2f, right = 0.8f)
        a.drag(fromX = W * 0.5f, fromY = H * 0.4f, toX = W * 0.5f, toY = H * 0.99f)
        a.assertOnScreen()
        assertEquals(0.6f - minY, a.topFraction, EPS)
        val b = view()
        b.setRegion(top = 0.4f, bottom = 0.6f, left = 0.2f, right = 0.8f)
        b.drag(fromX = W * 0.5f, fromY = H * 0.6f, toX = W * 0.5f, toY = 0f)
        b.assertOnScreen()
        assertEquals(0.4f + minY, b.bottomFraction, EPS)
        val c = view()
        c.setRegion(top = 0.4f, bottom = 0.6f, left = 0.2f, right = 0.8f)
        c.drag(fromX = W * 0.8f, fromY = H * 0.5f, toX = 0f, toY = H * 0.5f)
        c.assertOnScreen()
        assertEquals(0.2f + minX, c.rightFraction, EPS)
    }

    @Test fun `the minimum size is the same number of pixels on both axes, not a share of each`() {
        // 24 dp at this density, 72 px, on a 1080 x 2340 screen: the old 5%
        // floor would have been 54 px wide and 117 px tall.
        val v = view()
        v.setRegion(top = 0.4f, bottom = 0.6f, left = 0.2f, right = 0.8f)
        v.drag(fromX = W * 0.5f, fromY = H * 0.6f, toX = W * 0.5f, toY = 0f)
        v.drag(fromX = W * 0.8f, fromY = H * 0.45f, toX = 0f, toY = H * 0.45f)
        assertEquals(72f, (v.bottomFraction - v.topFraction) * H, 0.01f)
        assertEquals(72f, (v.rightFraction - v.leftFraction) * W, 0.01f)
    }

    @Test fun `a box within reach of both edges gives each handle to its own edge`() {
        // 117 px tall: every touch on it is within reach of both edges.
        val v = view()
        v.setRegion(top = 0.55f, bottom = 0.6f, left = 0.2f, right = 0.8f)
        v.drag(fromX = W * 0.5f, fromY = H * 0.6f, toX = W * 0.5f, toY = H * 0.8f)
        v.assertOnScreen()
        assertEquals(0.55f, v.topFraction, EPS)
        assertEquals(0.8f, v.bottomFraction, EPS)
    }

    // ── The back gesture ────────────────────────────────────────────────

    @Test fun `only the box's left and right handle bands are excluded from the back gesture`() {
        val v = view()
        v.setRegion(top = 0.4f, bottom = 0.6f, left = 0.2f, right = 0.8f)
        val tz = (52f * density).toInt()
        val l = (W * 0.2f).toInt()
        val r = (W * 0.8f).toInt()
        val t = (H * 0.4f).toInt() - tz
        val b = (H * 0.6f).toInt() + tz
        assertEquals(
            listOf(android.graphics.Rect(l - tz, t, l + tz, b), android.graphics.Rect(r - tz, t, r + tz, b)),
            v.systemGestureExclusionRects,
        )
        // A drag moves the bands with the box; a band at the screen edge is clipped.
        v.drag(fromX = W * 0.2f, fromY = H * 0.5f, toX = 0f, toY = H * 0.5f)
        assertEquals(0f, v.leftFraction, EPS)
        assertEquals(android.graphics.Rect(0, t, tz, b), v.systemGestureExclusionRects[0])
        // A framed box's bands follow the frame.
        v.setContentBounds(RectF(300f, 0f, 780f, 2340f))
        v.setRegion(top = 0.4f, bottom = 0.6f, left = 0f, right = 1f)
        assertEquals(300 - tz, v.systemGestureExclusionRects[0].left)
        assertEquals(780 + tz, v.systemGestureExclusionRects[1].right)
    }

    // ── A frame inside the view (the Anki crop's fitted screenshot) ──────

    private fun framed(frame: RectF): RegionDragView = view().also { it.setContentBounds(frame) }

    @Test fun `a framed box is in fractions of the frame, and its top edge stops at the frame's top`() {
        // A 1080 x 540 image fitted mid-screen: letterbox above and below.
        val v = framed(RectF(0f, 900f, 1080f, 1440f))
        v.setRegion(top = 0.2f, bottom = 0.8f, left = 0.2f, right = 0.8f)
        val box = RectF().also(v::boxInView)
        assertEquals(900f + 0.2f * 540f, box.top, EPS)
        assertEquals(900f + 0.8f * 540f, box.bottom, EPS)
        assertEquals(0.2f * 1080f, box.left, EPS)
        // The top edge dragged up into the letterbox stops at the frame's top.
        v.drag(fromX = 540f, fromY = box.top, toX = 540f, toY = 100f)
        v.assertOnScreen()
        assertEquals(0f, v.topFraction, EPS)
        v.boxInView(box)
        assertEquals(900f, box.top, EPS)
    }

    @Test fun `a framed box moved into the letterbox stays inside the frame`() {
        val v = framed(RectF(0f, 900f, 1080f, 1440f))
        v.setRegion(top = 0.2f, bottom = 0.8f, left = 0.2f, right = 0.8f)
        // From the box's middle (clear of every handle's reach) far past the
        // frame's bottom.
        v.drag(fromX = 540f, fromY = 1170f, toX = 540f, toY = 2200f)
        v.assertOnScreen()
        assertEquals(0.4f, v.topFraction, EPS)
        assertEquals(1f, v.bottomFraction, EPS)
    }

    @Test fun `a framed handle reports fractions of the frame, and the minimum size is measured on it`() {
        // A 480 px wide image fitted mid-screen: letterbox left and right.
        val v = framed(RectF(300f, 0f, 780f, 2340f))
        v.setRegion(top = 0.4f, bottom = 0.6f, left = 0.2f, right = 0.8f)
        // The right edge sits at 300 + 0.8 x 480 = 684; to x = 540 is 0.5 of the frame.
        v.drag(fromX = 684f, fromY = 1170f, toX = 540f, toY = 1170f)
        assertEquals(0.5f, v.rightFraction, EPS)
        // Onto its opposite: held 72 px (24 dp) from the left edge, 0.15 of the 480 px frame.
        v.drag(fromX = 540f, fromY = 1170f, toX = 0f, toY = 1170f)
        assertEquals(0.2f + 72f / 480f, v.rightFraction, EPS)
    }

    @Test fun `a new frame moves the box and keeps its fractions`() {
        val v = framed(RectF(0f, 900f, 1080f, 1440f))
        v.setRegion(top = 0.2f, bottom = 0.8f, left = 0.2f, right = 0.8f)
        v.setContentBounds(RectF(300f, 0f, 780f, 2340f))
        assertEquals(0.2f, v.topFraction, 0f)
        assertEquals(0.8f, v.rightFraction, 0f)
        val box = RectF().also(v::boxInView)
        assertEquals(300f + 0.2f * 480f, box.left, EPS)
        assertEquals(0.2f * 2340f, box.top, EPS)
    }

    private companion object {
        const val W = 1080
        const val H = 2340
        const val EPS = 1e-5f
        /** The 3.3.0 seed's right edge: 42 px of 1080. */
        const val SLIVER = 0.03930664f
        /** The same 42 px as a height, under the 72 px minimum on that axis too. */
        const val SLIVER_TALL = 42f / 2340f
    }
}
