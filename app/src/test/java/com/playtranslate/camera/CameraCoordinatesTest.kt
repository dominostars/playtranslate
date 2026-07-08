package com.playtranslate.camera

import android.graphics.Rect
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for [CameraCoordinates] — the FILL_CENTER AnalysisUpright→View
 * mapping. Runs under Robolectric for [android.graphics.Rect].
 */
@RunWith(RobolectricTestRunner::class)
class CameraCoordinatesTest {

    private fun assertRectNear(expected: Rect, actual: Rect, tolPx: Int = 1) {
        assertTrue(
            "expected ~$expected got $actual",
            abs(expected.left - actual.left) <= tolPx &&
                abs(expected.top - actual.top) <= tolPx &&
                abs(expected.right - actual.right) <= tolPx &&
                abs(expected.bottom - actual.bottom) <= tolPx,
        )
    }

    @Test
    fun identityWhenSameSize() {
        val c = CameraCoordinates(1080, 1920, 1080, 1920)
        assertEquals(1f, c.scale)
        assertEquals(0f, c.offsetX)
        assertEquals(0f, c.offsetY)
        val r = Rect(100, 200, 300, 400)
        assertEquals(r, c.auToView(r))
    }

    @Test
    fun sameAspectScalesUniformly() {
        // 2× view of the same 9:16 aspect: pure scale, no crop.
        val c = CameraCoordinates(1080, 1920, 2160, 3840)
        assertEquals(2f, c.scale)
        assertEquals(0f, c.offsetX)
        assertEquals(0f, c.offsetY)
        assertEquals(Rect(200, 400, 600, 800), c.auToView(Rect(100, 200, 300, 400)))
    }

    @Test
    fun tallerViewCropsHorizontally() {
        // 9:16 frame on a 9:19.5-ish screen: scale is driven by height and
        // the frame is cropped left/right, so offsetX < 0 and offsetY == 0.
        val c = CameraCoordinates(1080, 1920, 1080, 2340)
        assertEquals(2340f / 1920f, c.scale)
        assertTrue(c.offsetX < 0f)
        assertEquals(0f, c.offsetY)
        // The AU frame's horizontal center must land at the view's center.
        val centered = c.auToView(Rect(540, 0, 540, 1920))
        assertEquals(540, centered.left)
        assertEquals(2340, centered.bottom)
    }

    @Test
    fun widerViewCropsVertically() {
        val c = CameraCoordinates(1080, 1920, 1440, 1920)
        assertEquals(1440f / 1080f, c.scale)
        assertEquals(0f, c.offsetX.coerceAtLeast(0f))
        assertTrue(c.offsetY < 0f)
        val centered = c.auToView(Rect(0, 960, 1080, 960))
        assertEquals(960, centered.top)
        assertEquals(1440, centered.right)
    }

    @Test
    fun roundTripWithinOnePixel() {
        val c = CameraCoordinates(1080, 1920, 1080, 2340)
        val rects = listOf(
            Rect(0, 0, 1080, 1920),
            Rect(100, 250, 620, 400),
            Rect(37, 1800, 900, 1919),
        )
        for (r in rects) {
            assertRectNear(r, c.viewToAu(c.auToView(r)))
        }
    }
}
