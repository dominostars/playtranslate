package com.playtranslate.ui

import android.content.Context
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
 * each axis at [RegionDragView.MIN_EXTENT]. Its clamps once assumed the box
 * was already that big: the top edge's upper bound was bottom - MIN_EXTENT,
 * below the lower bound, zero, for a box whose bottom sat in the top 5% of
 * the screen, and Kotlin's coerceIn throws on a crossed range. A 3.3.0 crash
 * (Galaxy S23+, Android 16, 2026-10-07) seeded the editor with the icon
 * menu's drag-to-select box, 42 px wide at the left edge, and the first drag
 * of that box threw from the top-left corner's left clamp.
 *
 * Two rules follow. A seed is kept as it comes, not grown to MIN_EXTENT: the
 * camera's crop editor confirms an untouched box back as a change, so growth
 * on open would rerun its review. And of two opposite handles both within
 * reach, the finger is on the nearer one: a sliver has every touch within
 * reach of both, and a fixed order gave its right handle to the left edge,
 * which the minimum-size rule then held against the screen, so nothing
 * could widen it.
 *
 * Phone density: the touch zone is 52 dp, 146 px on the S23+ (2.8x). At
 * Robolectric's default 1x it is 52 px, and a 117 px minimum-size box is
 * then out of reach of both edges at once, so the handle cases would not
 * tell the nearest-edge rule from the old fixed order.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "xxhdpi")
class RegionDragViewSeedTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

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
        v.setRegion(top = 0f, bottom = SLIVER, left = 0.2f, right = 0.8f)
        v.drag(fromX = W * 0.5f, fromY = 10f, toX = W * 0.5f, toY = 40f)
        v.assertOnScreen()
        assertEquals(0f, v.topFraction, EPS)
        assertEquals(SLIVER, v.bottomFraction, EPS)
        v.drag(fromX = W * 0.5f, fromY = H * SLIVER, toX = W * 0.5f, toY = 400f)
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
        assertEquals(0.51f - RegionDragView.MIN_EXTENT, v.topFraction, EPS)
    }

    @Test fun `dragging an edge onto its opposite stops at the minimum size`() {
        val a = view()
        a.setRegion(top = 0.4f, bottom = 0.6f, left = 0.2f, right = 0.8f)
        a.drag(fromX = W * 0.5f, fromY = H * 0.4f, toX = W * 0.5f, toY = H * 0.99f)
        a.assertOnScreen()
        assertEquals(0.6f - RegionDragView.MIN_EXTENT, a.topFraction, EPS)
        val b = view()
        b.setRegion(top = 0.4f, bottom = 0.6f, left = 0.2f, right = 0.8f)
        b.drag(fromX = W * 0.5f, fromY = H * 0.6f, toX = W * 0.5f, toY = 0f)
        b.assertOnScreen()
        assertEquals(0.4f + RegionDragView.MIN_EXTENT, b.bottomFraction, EPS)
    }

    @Test fun `a minimum-size box gives each handle to its own edge`() {
        // 117 px tall: every touch on it is within reach of both edges.
        val v = view()
        v.setRegion(top = 0.55f, bottom = 0.6f, left = 0.2f, right = 0.8f)
        v.drag(fromX = W * 0.5f, fromY = H * 0.6f, toX = W * 0.5f, toY = H * 0.8f)
        v.assertOnScreen()
        assertEquals(0.55f, v.topFraction, EPS)
        assertEquals(0.8f, v.bottomFraction, EPS)
    }

    private companion object {
        const val W = 1080
        const val H = 2340
        const val EPS = 1e-5f
        /** The 3.3.0 seed's right edge: 42 px of 1080. */
        const val SLIVER = 0.03930664f
    }
}
