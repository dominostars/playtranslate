package com.playtranslate

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import com.playtranslate.ui.TextBox
import com.playtranslate.ui.TranslationOverlayView
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Pins [PinholeFill]'s geometry against the DRAWN chip (Codex adversarial
 * finding): the blackout must cover the rendered footprint — padded, carved,
 * at the drawn angle — not the stored source dims, and must still leave the
 * AABB's corner triangles unpainted (live game pixels).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PinholeFillTest {

    private val game = Color.GREEN
    private val bg = Color.RED

    /** The S13 render shape: source 200×40 at 30°, drawn chip 212×52 (6px
     *  deskewed-frame padding at density 1), centered on (200, 200). */
    private fun slantedFixture(): Triple<TextBox, Rect, TranslationOverlayView.ChildFootprint> {
        val box = TextBox(
            translatedText = "x",
            bounds = Rect(103, 132, 297, 268),
            angleDeg = 30f,
            orientedWidth = 200f,
            orientedHeight = 40f,
            bgColor = bg,
        )
        // Exact AABB of the 212×52 chip at 30°: 210×151 centered (200, 200).
        val drawnAabb = Rect(95, 124, 305, 276)
        val footprint = TranslationOverlayView.ChildFootprint(drawnAabb, 30f, 212f, 52f)
        return Triple(box, drawnAabb, footprint)
    }

    private fun canvasOf(): Bitmap = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888).apply {
        eraseColor(game)
    }

    /** Rotate a chip-local (u, v) offset out to screen about (200, 200). */
    private fun at(u: Float, v: Float): Pair<Int, Int> {
        val c = 0.86603f
        val s = 0.5f
        return (200 + u * c - v * s).toInt() to (200 + u * s + v * c).toInt()
    }

    @Test
    fun paddedFringe_isFilled_andAabbCornersAreNot() {
        val (box, aabb, footprint) = slantedFixture()
        val bmp = canvasOf()
        PinholeFill.fillOverlayRegions(bmp, listOf(box), listOf(aabb), listOf(footprint))

        // Chip center: filled.
        assertEquals("center", bg, bmp.getPixel(200, 200))
        // The padding fringe — inside the DRAWN 212×52 chip but OUTSIDE the
        // source 200×40 dims. The old stored-dims fill left this un-blacked.
        val (fx, fy) = at(103f, 0f)
        assertEquals("padding fringe along the baseline", bg, bmp.getPixel(fx, fy))
        val (gx, gy) = at(0f, 24f)
        assertEquals("padding fringe across the baseline", bg, bmp.getPixel(gx, gy))
        // The AABB corner triangles are live game pixels — never painted.
        assertEquals("AABB top-left corner", game, bmp.getPixel(aabb.left + 2, aabb.top + 2))
        assertEquals("AABB bottom-right corner", game, bmp.getPixel(aabb.right - 3, aabb.bottom - 3))
    }

    @Test
    fun carvedChip_fillsTheCarvedDims_notTheSourceDims() {
        // A carve shrank the drawn chip to 212×30 (its facing edge retreated):
        // the fill must NOT paint the source-height band beyond the carved
        // chip — those pixels show live game content again.
        val (box, _, _) = slantedFixture()
        // Exact AABB of 212×30 at 30°: 199×136 centered (200, 200).
        val aabb = Rect(101, 132, 300, 268)
        val footprint = TranslationOverlayView.ChildFootprint(aabb, 30f, 212f, 30f)
        val bmp = canvasOf()
        PinholeFill.fillOverlayRegions(bmp, listOf(box), listOf(aabb), listOf(footprint))

        assertEquals("center", bg, bmp.getPixel(200, 200))
        // v = 24 was inside the SOURCE dims (oh/2 = 20 + aa) but is outside
        // the carved chip (15 + aa = 18): must stay game pixels.
        val (px, py) = at(0f, 24f)
        assertEquals("beyond the carved edge", game, bmp.getPixel(px, py))
    }

    @Test
    fun uprightBox_fillsItsRectPlusBuffer_unchanged() {
        val box = TextBox(
            translatedText = "x",
            bounds = Rect(50, 50, 150, 90),
            bgColor = bg,
        )
        val rect = Rect(50, 50, 150, 90)
        val bmp = canvasOf()
        PinholeFill.fillOverlayRegions(
            bmp, listOf(box), listOf(rect),
            listOf(TranslationOverlayView.ChildFootprint(rect, 0f, 100f, 40f)),
        )
        assertEquals(bg, bmp.getPixel(100, 70))
        assertEquals("3px AA buffer", bg, bmp.getPixel(48, 70))
        assertEquals(game, bmp.getPixel(45, 70))
    }
}
