package com.playtranslate.ocr.meiki

import android.graphics.Rect
import com.playtranslate.language.TextOrientation
import com.playtranslate.ocr.core.OcrBox
import com.playtranslate.ocr.core.OrientedBoxGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The skew retry's geometry: the synthesized quad must round-trip through
 * [OrientedBoxGeometry.boxFor] (Meiki is the third angle producer under the
 * same policy ladder as ML Kit and Paddle — every gate rung must bind), and
 * the strip→frame char mapping must invert deskewAffine's convention.
 */
@RunWith(RobolectricTestRunner::class)
class MeikiSkewGeometryTest {

    @Test
    fun measuredSlantRoundTripsThroughBoxFor() {
        val quad = rotatedRectQuad(500f, 300f, 400f, 40f, 8f)
        val box = OrientedBoxGeometry.boxFor(
            quadAabb(quad), quad, TextOrientation.HORIZONTAL,
            maxSlantDeg = MeikiRecognizer.SKEW_MAX_SLANT_DEG, minSlantDeg = 3f,
        )
        assertTrue(box.isRotated)
        assertEquals(8f, box.angleDeg, 0.2f)
        assertEquals(400f, box.orientedWidth, 2f)
        assertEquals(40f, box.orientedHeight, 2f)
    }

    @Test
    fun subGateEstimateSnapsUpright() {
        val quad = rotatedRectQuad(500f, 300f, 400f, 40f, 2f)
        val box = OrientedBoxGeometry.boxFor(
            quadAabb(quad), quad, TextOrientation.HORIZONTAL,
            maxSlantDeg = MeikiRecognizer.SKEW_MAX_SLANT_DEG, minSlantDeg = 3f,
        )
        assertFalse(box.isRotated)
        assertFalse(box.angleUnmeasured)
    }

    @Test
    fun rollbackGateGovernsTheRetry() {
        // The debug rollback forces the legacy 10° gate; a 8° estimate must
        // then snap upright — the one-toggle rollback covers this producer too.
        val quad = rotatedRectQuad(500f, 300f, 400f, 40f, 8f)
        val box = OrientedBoxGeometry.boxFor(
            quadAabb(quad), quad, TextOrientation.HORIZONTAL,
            maxSlantDeg = MeikiRecognizer.SKEW_MAX_SLANT_DEG,
            minSlantDeg = OcrBox.ANGLE_LEGACY_GATE_DEG,
        )
        assertFalse(box.isRotated)
    }

    @Test
    fun subExcursionEstimateStaysUprightUnmeasured() {
        // 120 px at 4°: corner excursion 60·sin4° ≈ 4.2 px < 6 — visually
        // straight; the retry must decline (box comes back un-rotated).
        val quad = rotatedRectQuad(500f, 300f, 120f, 30f, 4f)
        val box = OrientedBoxGeometry.boxFor(
            quadAabb(quad), quad, TextOrientation.HORIZONTAL,
            maxSlantDeg = MeikiRecognizer.SKEW_MAX_SLANT_DEG, minSlantDeg = 3f,
        )
        assertFalse(box.isRotated)
        assertTrue(box.angleUnmeasured)
    }

    @Test
    fun pastBandEstimateStaysUpright() {
        val quad = rotatedRectQuad(500f, 300f, 400f, 40f, 18f)
        val box = OrientedBoxGeometry.boxFor(
            quadAabb(quad), quad, TextOrientation.HORIZONTAL,
            maxSlantDeg = MeikiRecognizer.SKEW_MAX_SLANT_DEG, minSlantDeg = 3f,
        )
        assertFalse(box.isRotated)
    }

    @Test
    fun stripRectMapsToOffsetAabbAtAngleZero() {
        val box = OcrBox(Rect(100, 200, 500, 240), 400f, 40f, 0f)
        val mapped = stripRectToFrameAabb(box, Rect(10, 5, 50, 35))
        assertEquals(Rect(110, 205, 150, 235), mapped)
    }

    @Test
    fun fullStripMapsBackToBoxBounds() {
        val quad = rotatedRectQuad(500f, 300f, 400f, 40f, 8f)
        val box = OrientedBoxGeometry.boxFor(
            quadAabb(quad), quad, TextOrientation.HORIZONTAL,
            maxSlantDeg = MeikiRecognizer.SKEW_MAX_SLANT_DEG, minSlantDeg = 3f,
        )
        val ow = box.orientedWidth.toInt()
        val oh = box.orientedHeight.toInt()
        val mapped = stripRectToFrameAabb(box, Rect(0, 0, ow, oh))
        // The whole strip's frame AABB is the box's own AABB (± rounding).
        assertTrue(kotlin.math.abs(mapped.left - box.bounds.left) <= 2)
        assertTrue(kotlin.math.abs(mapped.top - box.bounds.top) <= 2)
        assertTrue(kotlin.math.abs(mapped.right - box.bounds.right) <= 2)
        assertTrue(kotlin.math.abs(mapped.bottom - box.bounds.bottom) <= 2)
    }

    @Test
    fun stripCharKeepsReadingOrderUnderSlant() {
        // Two chars along the strip: after mapping, the left strip char must
        // stay left of the right one in frame coords (drag-lookup ordering).
        val quad = rotatedRectQuad(500f, 300f, 400f, 40f, 10f)
        val box = OrientedBoxGeometry.boxFor(
            quadAabb(quad), quad, TextOrientation.HORIZONTAL,
            maxSlantDeg = MeikiRecognizer.SKEW_MAX_SLANT_DEG, minSlantDeg = 3f,
        )
        val first = stripRectToFrameAabb(box, Rect(0, 0, 40, 40))
        val last = stripRectToFrameAabb(box, Rect(360, 0, 400, 40))
        assertTrue(first.centerX() < last.centerX())
        // Clockwise-positive slant: the later char sits LOWER on screen.
        assertTrue(first.centerY() < last.centerY())
    }
}
