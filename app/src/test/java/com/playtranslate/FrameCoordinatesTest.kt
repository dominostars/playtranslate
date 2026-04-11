package com.playtranslate

import android.graphics.Rect
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for [FrameCoordinates].
 *
 * Runs under Robolectric so that [android.graphics.Rect] is available on the
 * JVM without a device. The tests cover the identity-scale short-circuit
 * (our only currently supported case), the non-identity arithmetic (a paper
 * guarantee for if MediaProjection is ever revived), the zero-dim sentinel,
 * OCR→bitmap offsetting, list batching, and constructor validation.
 */
@RunWith(RobolectricTestRunner::class)
class FrameCoordinatesTest {

    @Before
    fun reset() {
        FrameCoordinates.resetWarnedForTests()
    }

    @After
    fun cleanup() {
        FrameCoordinates.resetWarnedForTests()
    }

    @Test
    fun identityScale_returnsSameRectInstance() {
        val fc = FrameCoordinates(
            bitmapWidth = 1000, bitmapHeight = 500,
            viewWidth = 1000, viewHeight = 500,
            cropLeft = 0, cropTop = 0,
        )
        assertTrue("expected identity scale", fc.isIdentityScale)
        val r = Rect(10, 20, 30, 40)
        assertSame(
            "identity viewToBitmap must return the same reference to avoid allocation",
            r, fc.viewToBitmap(r)
        )
    }

    @Test
    fun zeroViewDims_treatedAsIdentity() {
        // Used on the first cycle before the overlay view is attached.
        val fc = FrameCoordinates(
            bitmapWidth = 1000, bitmapHeight = 500,
            viewWidth = 0, viewHeight = 0,
            cropLeft = 0, cropTop = 0,
        )
        assertTrue("zero view dims should be treated as identity", fc.isIdentityScale)
        val r = Rect(10, 20, 30, 40)
        assertSame(r, fc.viewToBitmap(r))
    }

    @Test
    fun nonIdentityScale_multipliesCorrectly() {
        // bitmap 2000x1000, view 1000x500 → scaleX/scaleY = 2.0
        val fc = FrameCoordinates(
            bitmapWidth = 2000, bitmapHeight = 1000,
            viewWidth = 1000, viewHeight = 500,
            cropLeft = 0, cropTop = 0,
        )
        assertFalse("expected non-identity scale", fc.isIdentityScale)
        assertEquals(2f, fc.scaleX, 0.001f)
        assertEquals(2f, fc.scaleY, 0.001f)

        val bitmapRect = fc.viewToBitmap(Rect(100, 200, 300, 400))
        assertEquals(Rect(200, 400, 600, 800), bitmapRect)
    }

    @Test
    fun nonIdentityScale_asymmetricAxes() {
        // Different x and y scales — catches copy-paste errors between the two
        val fc = FrameCoordinates(
            bitmapWidth = 2000, bitmapHeight = 1500,
            viewWidth = 1000, viewHeight = 500,
            cropLeft = 0, cropTop = 0,
        )
        assertFalse(fc.isIdentityScale)
        assertEquals(2f, fc.scaleX, 0.001f)
        assertEquals(3f, fc.scaleY, 0.001f)

        val bitmapRect = fc.viewToBitmap(Rect(10, 20, 30, 40))
        assertEquals(Rect(20, 60, 60, 120), bitmapRect)
    }

    @Test
    fun ocrToBitmap_addsCropOffsets() {
        val fc = FrameCoordinates(
            bitmapWidth = 1000, bitmapHeight = 500,
            viewWidth = 1000, viewHeight = 500,
            cropLeft = 10, cropTop = 20,
        )
        val bitmapRect = fc.ocrToBitmap(Rect(5, 5, 15, 15))
        assertEquals(Rect(15, 25, 25, 35), bitmapRect)
    }

    @Test
    fun ocrToBitmap_zeroOffsets_passesThrough() {
        val fc = FrameCoordinates(
            bitmapWidth = 1000, bitmapHeight = 500,
            viewWidth = 1000, viewHeight = 500,
            cropLeft = 0, cropTop = 0,
        )
        val bitmapRect = fc.ocrToBitmap(Rect(100, 200, 300, 400))
        assertEquals(Rect(100, 200, 300, 400), bitmapRect)
    }

    @Test
    fun viewListToBitmap_identityPreservesReferences() {
        val fc = FrameCoordinates(
            bitmapWidth = 1000, bitmapHeight = 500,
            viewWidth = 1000, viewHeight = 500,
            cropLeft = 0, cropTop = 0,
        )
        val r1 = Rect(1, 2, 3, 4)
        val r2 = Rect(5, 6, 7, 8)
        val out = fc.viewListToBitmap(listOf(r1, r2))
        assertEquals(2, out.size)
        assertSame("identity should keep instance r1", r1, out[0])
        assertSame("identity should keep instance r2", r2, out[1])
    }

    @Test
    fun viewListToBitmap_nonIdentityAllocatesEachEntry() {
        val fc = FrameCoordinates(
            bitmapWidth = 2000, bitmapHeight = 1000,
            viewWidth = 1000, viewHeight = 500,
            cropLeft = 0, cropTop = 0,
        )
        val r = Rect(10, 20, 30, 40)
        val out = fc.viewListToBitmap(listOf(r))
        assertEquals(1, out.size)
        // Non-identity MUST NOT return the same instance (scaling creates a new Rect)
        assertEquals(Rect(20, 40, 60, 80), out[0])
    }

    @Test
    fun emptyListBatch_returnsEmpty() {
        val fc = FrameCoordinates(
            bitmapWidth = 1000, bitmapHeight = 500,
            viewWidth = 1000, viewHeight = 500,
            cropLeft = 0, cropTop = 0,
        )
        assertEquals(emptyList<Rect>(), fc.viewListToBitmap(emptyList()))
    }

    @Test
    fun requirePositiveBitmapDims_zeroWidth() {
        try {
            FrameCoordinates(
                bitmapWidth = 0, bitmapHeight = 500,
                viewWidth = 1000, viewHeight = 500,
                cropLeft = 0, cropTop = 0,
            )
            fail("expected IllegalArgumentException for bitmapWidth=0")
        } catch (_: IllegalArgumentException) {
            // ok
        }
    }

    @Test
    fun requirePositiveBitmapDims_zeroHeight() {
        try {
            FrameCoordinates(
                bitmapWidth = 1000, bitmapHeight = 0,
                viewWidth = 1000, viewHeight = 500,
                cropLeft = 0, cropTop = 0,
            )
            fail("expected IllegalArgumentException for bitmapHeight=0")
        } catch (_: IllegalArgumentException) {
            // ok
        }
    }
}
