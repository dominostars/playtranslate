package com.playtranslate.ocr.core

import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [DeskewGeometry] contract tests: the rounding policy, the frame round-trips,
 * the screen-AABB properties every angled-box consumer pins on (center ±0.5px,
 * footprint containment), the footprint motion metric, and the clusterer's
 * deterministic spec.
 */
@RunWith(RobolectricTestRunner::class)
class DeskewGeometryTest {

    private fun frame(deg: Float, ax: Int = 500, ay: Int = 400) = AngleFrame(deg, ax, ay)

    private fun rotatedBox(w: Float, h: Float, deg: Float, cx: Float, cy: Float): OcrBox {
        val r = Math.toRadians(deg.toDouble())
        val c = cos(r).toFloat(); val s = sin(r).toFloat()
        val corners = listOf(
            -w / 2 to -h / 2, w / 2 to -h / 2, w / 2 to h / 2, -w / 2 to h / 2,
        ).map { (x, y) -> (cx + x * c - y * s) to (cy + x * s + y * c) }
        val aabb = Rect(
            DeskewGeometry.roundHalfUp(corners.minOf { it.first }),
            DeskewGeometry.roundHalfUp(corners.minOf { it.second }),
            DeskewGeometry.roundHalfUp(corners.maxOf { it.first }),
            DeskewGeometry.roundHalfUp(corners.maxOf { it.second }),
        )
        return OcrBox(aabb, w, h, deg)
    }

    // ── rounding policy ──────────────────────────────────────────────────

    @Test
    fun `roundHalfUp table including negatives`() {
        val cases = mapOf(
            2.4f to 2, 2.5f to 3, 2.6f to 3,
            -2.4f to -2, -2.5f to -2, -2.6f to -3,
            0.0f to 0, -0.5f to 0,
        )
        for ((x, want) in cases) {
            assertEquals("roundHalfUp($x)", want, DeskewGeometry.roundHalfUp(x))
        }
    }

    // ── frame round-trips ────────────────────────────────────────────────

    @Test
    fun `toFrame then rotate is the identity`() {
        val f = frame(27f)
        val ax = f.anchorX.toFloat(); val ay = f.anchorY.toFloat()
        for ((px, py) in listOf(123f to 456f, -50f to 900f, 500f to 400f)) {
            val u = ax + DeskewGeometry.toFrameU(px, py, ax, ay, f.cosT, f.sinT)
            val v = ay + DeskewGeometry.toFrameV(px, py, ax, ay, f.cosT, f.sinT)
            assertEquals(px, DeskewGeometry.rotateX(u, v, ax, ay, f.cosT, f.sinT), 0.01f)
            assertEquals(py, DeskewGeometry.rotateY(u, v, ax, ay, f.cosT, f.sinT), 0.01f)
        }
    }

    // ── deskew ───────────────────────────────────────────────────────────

    @Test
    fun `deskew dims are the rounded oriented dims regardless of position`() {
        val f = frame(-12f)
        for (cx in floatArrayOf(100.0f, 100.3f, 100.5f, 100.7f, 777.2f)) {
            val box = rotatedBox(203.4f, 41.6f, -12f, cx, 300f)
            val r = DeskewGeometry.deskew(box, f)
            assertEquals(203, r.width())
            assertEquals(42, r.height())
        }
    }

    @Test
    fun `deskew under a different anchor is a pure translation`() {
        val box = rotatedBox(200f, 40f, -12f, 640f, 360f)
        val a = DeskewGeometry.deskew(box, frame(-12f, 0, 0))
        val b = DeskewGeometry.deskew(box, frame(-12f, 500, 400))
        assertEquals(a.width(), b.width())
        assertEquals(a.height(), b.height())
        // Same translation applies to every box in the frame — spot-check with
        // a second box: the deltas must match to within rounding (±1px).
        val box2 = rotatedBox(120f, 30f, -12f, 200f, 700f)
        val a2 = DeskewGeometry.deskew(box2, frame(-12f, 0, 0))
        val b2 = DeskewGeometry.deskew(box2, frame(-12f, 500, 400))
        assertTrue(abs((a.left - b.left) - (a2.left - b2.left)) <= 1)
        assertTrue(abs((a.top - b.top) - (a2.top - b2.top)) <= 1)
    }

    // ── screenAabbOf: the two consumer-pinned properties ─────────────────

    @Test
    fun `screenAabbOf contains all footprint corners and centers within half a pixel`() {
        for (deg in floatArrayOf(-45f, -30f, -12f, 12f, 30f, 45f, 60f)) {
            val f = frame(deg)
            val inFrame = Rect(300, 350, 520, 430)  // 220×80 union in-frame
            val aabb = DeskewGeometry.screenAabbOf(inFrame, f)

            val ax = f.anchorX.toFloat(); val ay = f.anchorY.toFloat()
            val cx = DeskewGeometry.rotateX(inFrame.exactCenterX(), inFrame.exactCenterY(), ax, ay, f.cosT, f.sinT)
            val cy = DeskewGeometry.rotateY(inFrame.exactCenterX(), inFrame.exactCenterY(), ax, ay, f.cosT, f.sinT)
            assertEquals("center x at $deg", cx, aabb.exactCenterX(), 0.51f)
            assertEquals("center y at $deg", cy, aabb.exactCenterY(), 0.51f)

            for ((ux, uy) in listOf(
                inFrame.left to inFrame.top, inFrame.right to inFrame.top,
                inFrame.right to inFrame.bottom, inFrame.left to inFrame.bottom,
            )) {
                val px = DeskewGeometry.rotateX(ux.toFloat(), uy.toFloat(), ax, ay, f.cosT, f.sinT)
                val py = DeskewGeometry.rotateY(ux.toFloat(), uy.toFloat(), ax, ay, f.cosT, f.sinT)
                assertTrue(
                    "corner ($px,$py) outside AABB $aabb at $deg",
                    px >= aabb.left - 0.51f && px <= aabb.right + 0.51f &&
                        py >= aabb.top - 0.51f && py <= aabb.bottom + 0.51f,
                )
            }
        }
    }

    // ── footprintCornerDelta ─────────────────────────────────────────────

    @Test
    fun `identical footprints score zero and translations score the distance`() {
        val a = rotatedBox(200f, 40f, -12f, 400f, 300f)
        assertEquals(
            0f,
            DeskewGeometry.footprintCornerDelta(
                a.bounds, a.angleDeg, a.orientedWidth, a.orientedHeight,
                a.bounds, a.angleDeg, a.orientedWidth, a.orientedHeight,
            ),
            0.01f,
        )
        val moved = Rect(a.bounds).apply { offset(6, 8) }
        assertEquals(
            10f,
            DeskewGeometry.footprintCornerDelta(
                a.bounds, a.angleDeg, a.orientedWidth, a.orientedHeight,
                moved, a.angleDeg, a.orientedWidth, a.orientedHeight,
            ),
            0.6f,
        )
    }

    @Test
    fun `zero to nonzero flip scores by corner radius - long lines move, short lines barely`() {
        // Pure rotation about a shared center scores 2·r·sin(δ/2), r = corner radius.
        val longU = OcrBox.upright(Rect(0, 0, 400, 40))
        val longR = OcrBox(Rect(0, 0, 400, 40), 400f, 40f, 3f)
        val expectLong = (2 * hypot(200f, 20f) * sin(Math.toRadians(1.5)).toFloat())
        assertEquals(
            expectLong,
            DeskewGeometry.footprintCornerDelta(
                longU.bounds, 0f, 0f, 0f,
                longR.bounds, longR.angleDeg, longR.orientedWidth, longR.orientedHeight,
            ),
            0.3f,
        )
        assertTrue("long-line flip must exceed the 5px reposition hysteresis", expectLong > 5f)

        val shortU = OcrBox.upright(Rect(0, 0, 100, 40))
        val d = DeskewGeometry.footprintCornerDelta(
            shortU.bounds, 0f, 0f, 0f,
            shortU.bounds, 3f, 100f, 40f,
        )
        assertTrue("short-line flip stays under 5px, got $d", d < 5f)
    }

    // ── clusterByAngle ───────────────────────────────────────────────────

    @Test
    fun `seed-anchored runs cluster and distant angles split`() {
        val angles = listOf(-11f, -12f, -13f, 30f)
        val lengths = listOf(200f, 300f, 180f, 150f)
        val heights = listOf(40f, 40f, 40f, 40f)
        val clusters = DeskewGeometry.clusterByAngle(angles, lengths, heights, capDeg = 4f)
        assertEquals(2, clusters.size)
        assertEquals(listOf(0, 1, 2), clusters[0].memberIndices)
        assertEquals("theta-bar is the longest member's angle", -12f, clusters[0].angleDeg, 0f)
        assertEquals(listOf(3), clusters[1].memberIndices)
        assertEquals(30f, clusters[1].angleDeg, 0f)
    }

    @Test
    fun `length-aware check splits a long member with residual excursion`() {
        // Longest member (idx 1, 2000px) sets theta-bar = -10. Member 0 is also
        // long (1800px) at -14: excursion = 900·sin(4°) ≈ 62.8 > 0.35·40 = 14 →
        // splits out. Member 2 is short (80px) at -13: excursion ≈ 4.2 < 14 → stays.
        val angles = listOf(-14f, -10f, -13f)
        val lengths = listOf(1800f, 2000f, 80f)
        val heights = listOf(40f, 40f, 40f)
        val clusters = DeskewGeometry.clusterByAngle(angles, lengths, heights, capDeg = 4f)
        assertEquals(2, clusters.size)
        assertEquals(listOf(0), clusters[0].memberIndices)
        assertEquals(-14f, clusters[0].angleDeg, 0f)
        assertEquals(listOf(1, 2), clusters[1].memberIndices)
        assertEquals(-10f, clusters[1].angleDeg, 0f)
    }

    @Test
    fun `deterministic under input order and ties`() {
        val anglesA = listOf(20f, 20f, 21f)
        val lengths = listOf(100f, 100f, 90f)
        val heights = listOf(30f, 30f, 30f)
        val a = DeskewGeometry.clusterByAngle(anglesA, lengths, heights, 4f)
        assertEquals(1, a.size)
        assertEquals("tie on length → lowest input index wins theta-bar", 20f, a[0].angleDeg, 0f)
        assertEquals(listOf(0, 1, 2), a[0].memberIndices)
    }
}
