package com.playtranslate

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Pins [OverlayToolkit.blackoutFloatingIcon]'s ownership contract
 * (2026-07-16 adversarial finding): a caller-owned frame handed in with
 * `allowInPlace = false` must come back byte-identical, no matter how
 * mutable it is — both capture backends serve MUTABLE frames, and
 * downstream code reuses them as the untouched screen image (cache saves,
 * cleanRef baselines, pinhole checks). In-place drawing is legal only when
 * the call site declares ownership with `allowInPlace = true`.
 *
 * Runs under Robolectric with NATIVE graphics so [Bitmap]/[Canvas] really
 * rasterize on the JVM (the legacy shadows no-op drawRect and getPixel).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OverlayToolkitBlackoutTest {

    private fun whiteFrame(w: Int = 40, h: Int = 30): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }

    @Test
    fun `allowInPlace false leaves a mutable input unchanged and fills the copy`() {
        val frame = whiteFrame()
        val iconRect = Rect(0, 10, 8, 20)

        val out = OverlayToolkit.blackoutFloatingIcon(
            frame, 0, 0, iconRect, allowInPlace = false,
        )

        assertNotSame(frame, out)
        // Input frame untouched — every pixel still white.
        assertEquals(Color.WHITE, frame.getPixel(2, 15))
        // Output carries the fill inside the rect, and nothing outside it.
        assertEquals(Color.BLACK, out.getPixel(2, 15))
        assertEquals(Color.WHITE, out.getPixel(20, 15))
    }

    @Test
    fun `allowInPlace true draws into a mutable input`() {
        val frame = whiteFrame()
        val iconRect = Rect(0, 10, 8, 20)

        val out = OverlayToolkit.blackoutFloatingIcon(
            frame, 0, 0, iconRect, allowInPlace = true,
        )

        assertSame(frame, out)
        assertEquals(Color.BLACK, out.getPixel(2, 15))
        assertEquals(Color.WHITE, out.getPixel(20, 15))
    }

    @Test
    fun `crop offset maps the screen rect into bitmap space`() {
        val frame = whiteFrame()
        // Screen-space rect; the bitmap's (0,0) sits at screen (10, 5).
        val iconRect = Rect(10, 5, 18, 15)

        val out = OverlayToolkit.blackoutFloatingIcon(
            frame, 10, 5, iconRect, allowInPlace = false,
        )

        assertEquals(Color.BLACK, out.getPixel(0, 0))
        assertEquals(Color.BLACK, out.getPixel(7, 9))
        assertEquals(Color.WHITE, out.getPixel(8, 10))
    }

    @Test
    fun `rect fully outside the bitmap returns the input untouched`() {
        val frame = whiteFrame()
        // The icon lives on a screen region this crop doesn't cover.
        val iconRect = Rect(500, 500, 560, 560)

        val out = OverlayToolkit.blackoutFloatingIcon(
            frame, 0, 0, iconRect, allowInPlace = false,
        )

        assertSame(frame, out)
        assertEquals(Color.WHITE, frame.getPixel(2, 15))
    }

    @Test
    fun `rect overlapping the edge is clamped, not rejected`() {
        val frame = whiteFrame(w = 40, h = 30)
        // Half the icon hangs off the left edge — the docked-icon shape.
        val iconRect = Rect(-20, 8, 6, 22)

        val out = OverlayToolkit.blackoutFloatingIcon(
            frame, 0, 0, iconRect, allowInPlace = false,
        )

        assertNotSame(frame, out)
        assertEquals(Color.BLACK, out.getPixel(0, 15))
        assertEquals(Color.BLACK, out.getPixel(5, 15))
        assertEquals(Color.WHITE, out.getPixel(6, 15))
    }
}
