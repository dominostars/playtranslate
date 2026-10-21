package com.playtranslate.ui

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [ScreenshotCrop] is the one shape a crop takes between the editor, the
 * preview, the saved args and the send: ordered, inside the image, never
 * empty and never the whole image (that is no crop), and its pixel rect
 * takes the outer pixels the fractions touch so a cut never loses a sliver
 * the user included. Robolectric for [Rect].
 */
@RunWith(RobolectricTestRunner::class)
class ScreenshotCropTest {

    @Test fun `of orders and clamps the edges`() {
        val c = ScreenshotCrop.of(left = 0.9f, top = 1.2f, right = 0.1f, bottom = 0.4f)!!
        assertEquals(0.1f, c.left, 0f)
        assertEquals(0.9f, c.right, 0f)
        assertEquals(0.4f, c.top, 0f)
        assertEquals(1f, c.bottom, 0f)
    }

    @Test fun `an empty box and the whole image are no crop`() {
        assertNull(ScreenshotCrop.of(0.5f, 0.2f, 0.5f, 0.8f))
        assertNull(ScreenshotCrop.of(0.2f, 0.5f, 0.8f, 0.5f))
        assertNull(ScreenshotCrop.of(0f, 0f, 1f, 1f))
        assertNull(ScreenshotCrop.of(-1f, -1f, 2f, 2f))
    }

    @Test fun `the pixel rect takes the outer pixels the fractions touch, inside the image, one at least`() {
        assertEquals(
            Rect(250, 50, 751, 450),
            ScreenshotCrop.of(0.25f, 0.1f, 0.751f, 0.9f)!!.toPixelRect(1000, 500),
        )
        // A hairline still cuts a pixel.
        assertEquals(Rect(5, 5, 6, 6), ScreenshotCrop.of(0.5f, 0.5f, 0.5001f, 0.5001f)!!.toPixelRect(10, 10))
        // At the far edge the rect stops at the image.
        assertEquals(Rect(9, 0, 10, 1), ScreenshotCrop.of(0.999f, 0f, 1f, 0.001f)!!.toPixelRect(10, 10))
    }

    @Test fun `the array round trip keeps the crop, and a bad array is none`() {
        val c = ScreenshotCrop.of(0.1f, 0.2f, 0.9f, 0.8f)!!
        assertEquals(c, ScreenshotCrop.fromFloatArray(c.toFloatArray()))
        assertNull(ScreenshotCrop.fromFloatArray(null))
        assertNull(ScreenshotCrop.fromFloatArray(floatArrayOf(0.1f, 0.2f)))
        assertNull(ScreenshotCrop.fromFloatArray(floatArrayOf(0f, 0f, 1f, 1f)))
    }
}
