package com.playtranslate.camera.render

import android.content.Context
import android.graphics.Rect
import android.graphics.RectF
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.camera.tracker.Homography
import com.playtranslate.overlayThemedContext
import com.playtranslate.ui.TapLookupGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * The live camera preview's Definitions outlines: each word's box becomes
 * one warpable raster ([rasterizeWordOutlines]), index for index with the
 * words, and a tap finds the word under it through the warp view's own
 * matrices ([WarpOverlayView.regionIndexAt]), wherever the tracker has
 * moved it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LiveWordOutlinesTest {

    private val ctx: Context = overlayThemedContext(ApplicationProvider.getApplicationContext())

    private fun word(left: Float, top: Float, right: Float, bottom: Float, surface: String = "w") =
        TapLookupGeometry.WordBox(0, 0, 0, surface, RectF(left, top, right, bottom))

    // ── rasters ──────────────────────────────────────────────────────────

    @Test fun `every word gets one raster, in order, covering its box and riding its line`() {
        val words = listOf(word(100f, 100f, 200f, 130f), word(210f, 100f, 300f, 130f), word(50f, 300f, 90f, 330f))
        val regions = rasterizeWordOutlines(ctx, words, trackKeys = listOf(0, 0, 1), renderScale = 2f)

        assertEquals(3, regions.size)
        for ((i, region) in regions.withIndex()) {
            val box = Rect().also { words[i].rect.roundOut(it) }
            assertTrue("${region.auRect} covers $box", region.auRect.contains(box))
            assertEquals("rendered at the asked scale", 2f, region.pixelsPerAu)
            assertEquals(region.auRect.width() * 2, region.bitmap.width)
            assertEquals(region.auRect.height() * 2, region.bitmap.height)
        }
        assertEquals(listOf(0, 0, 1), regions.map { it.trackKey })
    }

    @Test fun `the outline is drawn, and its middle is left clear`() {
        val regions = rasterizeWordOutlines(ctx, listOf(word(100f, 100f, 200f, 160f)), listOf(0), renderScale = 1f)
        val bmp = regions[0].bitmap
        val r = regions[0].auRect
        // Along the box's top edge, away from the rounded corners.
        val onEdge = bmp.getPixel(150 - r.left, 100 - r.top)
        val inside = bmp.getPixel(150 - r.left, 130 - r.top)
        assertTrue("the edge is stroked", android.graphics.Color.alpha(onEdge) > 0)
        assertEquals("the word itself is not covered", 0, android.graphics.Color.alpha(inside))
    }

    @Test fun `a box with no size still gets a raster, so the regions stay index for index with the words`() {
        val words = listOf(word(100f, 100f, 100f, 100f), word(210f, 100f, 300f, 130f))
        val regions = rasterizeWordOutlines(ctx, words, listOf(0, 0), renderScale = 1f)
        assertEquals(2, regions.size)
        assertTrue(regions[0].bitmap.width >= 1 && regions[0].bitmap.height >= 1)
    }

    @Test fun `no words, no rasters`() {
        assertTrue(rasterizeWordOutlines(ctx, emptyList(), emptyList(), 1f).isEmpty())
    }

    // ── the tap ──────────────────────────────────────────────────────────

    /** A warp view the size of its keyframe (so one AU pixel is one view
     *  pixel), showing [words]' outlines as the live preview installs
     *  them: one raster each, index for index. */
    private fun warp(vararg words: TapLookupGeometry.WordBox): WarpOverlayView {
        val view = WarpOverlayView(ctx)
        view.layout(0, 0, 800, 600)
        view.setRegions(rasterizeWordOutlines(ctx, words.toList(), words.map { 0 }, renderScale = 1f), 800, 600)
        view.applyHomography(Homography.IDENTITY)
        return view
    }

    private fun WarpOverlayView.pick(words: Array<TapLookupGeometry.WordBox>, x: Float, y: Float) =
        wordIndexAt(words.toList(), x, y, slopPx = 24f)

    @Test fun `a tap inside a word finds it, one just off it finds it within the buffer, one farther finds none`() {
        val words = arrayOf(word(100f, 100f, 200f, 130f), word(400f, 100f, 500f, 130f))
        val view = warp(*words)
        assertEquals(0, view.pick(words, 150f, 115f))
        assertEquals(1, view.pick(words, 450f, 115f))
        assertEquals("10 px under the first", 0, view.pick(words, 150f, 140f))
        assertNull("60 px under it", view.pick(words, 150f, 190f))
    }

    @Test fun `between two words the nearer one is found, and a word the tap is in beats a neighbour's buffer`() {
        val words = arrayOf(word(100f, 100f, 200f, 130f), word(210f, 100f, 300f, 130f))
        val view = warp(*words)
        assertEquals("4 px from the first, 6 from the second", 0, view.pick(words, 204f, 115f))
        assertEquals("6 px from the first, 4 from the second", 1, view.pick(words, 206f, 115f))
        assertEquals("inside the second, in the first's buffer", 1, view.pick(words, 212f, 115f))
    }

    @Test fun `a slanted word is met as it is drawn, not by the upright bitmap around it`() {
        // A long word turned 20 degrees clockwise about its middle: its left
        // end rises, its right end drops, and the bitmap that holds it is the
        // upright rectangle around both, about (100, 50) to (400, 190).
        val slanted = TapLookupGeometry.WordBox(
            0, 0, 0, "slanted", RectF(100f, 100f, 400f, 140f),
            angleDeg = 20f, pivotX = 250f, pivotY = 120f,
        )
        // An upright word in that rectangle's empty bottom-left corner, some
        // 60 px under the slanted word's own outline there.
        val under = word(100f, 150f, 180f, 185f, "under")
        val words = arrayOf(slanted, under)
        val view = warp(*words)

        assertTrue(
            "precondition: the point is inside the slanted word's bitmap",
            RectF(100f, 50f, 400f, 190f).contains(140f, 167f),
        )
        assertEquals("a tap squarely on the word underneath is that word's", 1, view.pick(words, 140f, 167f))
        assertEquals("on the slanted word's raised left end", 0, view.pick(words, 140f, 80f))
        assertEquals("on its dropped right end", 0, view.pick(words, 380f, 167f))
        assertNull("in the bitmap's empty top-right corner, far from both", view.pick(words, 380f, 62f))
    }

    @Test fun `the tap follows the tracker, a word being found where it is drawn now`() {
        val words = arrayOf(word(100f, 100f, 200f, 130f))
        val view = warp(*words)
        // The scene moved 300 px right and 200 down since the keyframe.
        view.applyHomography(doubleArrayOf(1.0, 0.0, 300.0, 0.0, 1.0, 200.0, 0.0, 0.0, 1.0))
        assertNull("no longer where the keyframe had it", view.pick(words, 150f, 115f))
        assertEquals(0, view.pick(words, 450f, 315f))
    }

    @Test fun `the buffer is the screen's, whatever size the tracker draws the scene at`() {
        val words = arrayOf(word(100f, 100f, 200f, 130f))
        val view = warp(*words)
        // The phone moved closer: the scene is drawn twice the size.
        view.applyHomography(doubleArrayOf(2.0, 0.0, 0.0, 0.0, 2.0, 0.0, 0.0, 0.0, 1.0))
        // The word is drawn at (200, 200) to (400, 260).
        assertEquals("20 px under it on screen", 0, view.pick(words, 300f, 280f))
        assertNull("30 px under it on screen", view.pick(words, 300f, 290f))
    }

    @Test fun `nothing is found while nothing is drawn`() {
        val words = arrayOf(word(100f, 100f, 200f, 130f))
        val view = warp(*words)
        view.applyHomography(null)
        assertNull("the tracker lost the scene", view.pick(words, 150f, 115f))
        view.clearRegions()
        assertNull(view.pick(words, 150f, 115f))
    }
}
