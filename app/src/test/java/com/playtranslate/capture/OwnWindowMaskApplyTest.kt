package com.playtranslate.capture

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Pins [OwnWindowMask.apply]'s pixel and ownership contract: it folds
 * [com.playtranslate.OverlayToolkit.blackoutFloatingIcon] over a rect list,
 * so it inherits that helper's rules (in-place only with ownership, never
 * recycles) and adds one of its own: once a copy exists, every later rect
 * draws into the copy, never into the caller's input.
 *
 * Native graphics so [Bitmap]/[Canvas] really rasterize on the JVM (see
 * OverlayToolkitBlackoutTest).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OwnWindowMaskApplyTest {

    private fun whiteFrame(w: Int = 60, h: Int = 40, mutable: Boolean = true): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        return if (mutable) bmp else bmp.copy(Bitmap.Config.ARGB_8888, false)
    }

    @Test
    fun `empty rect list returns the input itself, untouched`() {
        val frame = whiteFrame()
        val out = OwnWindowMask.apply(frame, emptyList(), allowInPlace = true)
        assertSame(frame, out)
        assertEquals(Color.WHITE, frame.getPixel(30, 20))
    }

    @Test
    fun `every rect is painted, in place, when the caller owns a mutable frame`() {
        val frame = whiteFrame()
        val rects = listOf(Rect(0, 0, 20, 40), Rect(40, 0, 60, 40))

        val out = OwnWindowMask.apply(frame, rects, allowInPlace = true)

        assertSame(frame, out)
        assertEquals(Color.BLACK, out.getPixel(10, 20))
        assertEquals(Color.BLACK, out.getPixel(50, 20))
        // The gap between the two rects is untouched.
        assertEquals(Color.WHITE, out.getPixel(30, 20))
    }

    @Test
    fun `immutable input yields one copy carrying every rect, input untouched and not recycled`() {
        val frame = whiteFrame(mutable = false)
        val rects = listOf(Rect(0, 0, 20, 40), Rect(40, 0, 60, 40))

        val out = OwnWindowMask.apply(frame, rects, allowInPlace = true)

        assertNotSame(frame, out)
        assertFalse(frame.isRecycled)
        assertEquals(Color.WHITE, frame.getPixel(10, 20))
        assertEquals(Color.WHITE, frame.getPixel(50, 20))
        // Both rects landed on the same copy — the second draw went into
        // the copy, not into a second copy of the immutable input.
        assertEquals(Color.BLACK, out.getPixel(10, 20))
        assertEquals(Color.BLACK, out.getPixel(50, 20))
        assertEquals(Color.WHITE, out.getPixel(30, 20))
    }

    @Test
    fun `allowInPlace false never draws into the caller's frame`() {
        val frame = whiteFrame()
        val out = OwnWindowMask.apply(frame, listOf(Rect(0, 0, 60, 40)), allowInPlace = false)

        assertNotSame(frame, out)
        assertEquals(Color.WHITE, frame.getPixel(30, 20))
        assertEquals(Color.BLACK, out.getPixel(30, 20))
    }

    @Test
    fun `rects overhanging the frame are clamped and rects missing it are ignored`() {
        val frame = whiteFrame()
        val rects = listOf(Rect(-10, -10, 10, 10), Rect(500, 500, 600, 600))

        val out = OwnWindowMask.apply(frame, rects, allowInPlace = true)

        assertSame(frame, out)
        assertEquals(Color.BLACK, out.getPixel(0, 0))
        assertEquals(Color.BLACK, out.getPixel(9, 9))
        assertEquals(Color.WHITE, out.getPixel(10, 10))
    }
}
