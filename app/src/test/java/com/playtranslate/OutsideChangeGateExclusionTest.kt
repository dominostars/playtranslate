package com.playtranslate

import android.graphics.Rect
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins [OutsideChangeGate.Exclusion]'s footprint-shaped containment: a rotated
 * chip excludes only its oriented footprint, so the AABB corner triangles stay
 * sampled by the outside gate — they are exactly the pixels the pinhole
 * detector's blend model skips (no overlay drawn there), and the gate is their
 * only change watcher. Geometry mirrors the SOURCE_ANGLE render contract: the
 * chip is orientedW×orientedH rotated clockwise-positive about the rect center.
 */
@RunWith(RobolectricTestRunner::class)
class OutsideChangeGateExclusionTest {

    /** AABB of a 200×40 rect at +30° about (500,500): 193.2×134.6. */
    private val aabb = Rect(403, 433, 597, 567)
    private val angled = OutsideChangeGate.Exclusion(aabb, angleDeg = 30f, orientedW = 200f, orientedH = 40f)

    @Test
    fun uprightExclusion_isPlainRectContains() {
        val plain = OutsideChangeGate.Exclusion(Rect(100, 100, 200, 150))
        assertTrue(plain.contains(150, 125))
        assertFalse(plain.contains(250, 125))
        // Angle 0 with oriented dims still uses the rect — upright chips fill
        // their whole rect, corners included.
        val uprightWithDims = OutsideChangeGate.Exclusion(
            Rect(100, 100, 200, 150), angleDeg = 0f, orientedW = 80f, orientedH = 30f,
        )
        assertTrue(uprightWithDims.contains(101, 101))
    }

    @Test
    fun angledExclusion_centerAndAxisPointsInside() {
        assertTrue(angled.contains(500, 500))
        // 80px along the +30° reading axis (clockwise-positive, y-down):
        // inside the 200-long footprint.
        assertTrue(angled.contains(569, 540))
    }

    @Test
    fun angledExclusion_aabbCornersStaySampled() {
        // All four AABB corners lie outside the rotated footprint — the whole
        // point of footprint-shaped exclusion.
        assertFalse(angled.contains(404, 434))
        assertFalse(angled.contains(596, 434))
        assertFalse(angled.contains(404, 566))
        assertFalse(angled.contains(596, 566))
    }

    @Test
    fun angledExclusion_mirrorOfAxisPointIsOutside() {
        // Mirror of (569,540) across the horizontal: same AABB position class,
        // but ~69px off the reading axis — well past the 20px half-height. A
        // sign error in the un-rotation would accept it.
        assertFalse(angled.contains(569, 460))
    }
}
