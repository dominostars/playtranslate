package com.playtranslate.camera.render

import android.content.Context
import android.graphics.Rect
import com.playtranslate.ui.OverlayRenderConfig
import com.playtranslate.ui.TextBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [OverlayRasterizer] renders at `renderScale` view px per AU px and maps the
 * rasters back to AU space, so every geometry field the layout reads has to
 * ride that scale. The resolver maps [TextBox.drawBounds] (not `bounds`) and
 * sizes slanted chips from the oriented dims; a scale copy that missed them
 * drew every chip at 1/s of its source.
 *
 * Slanted chips are pinned through [scaledForRender] rather than a rasterize
 * run: Robolectric's legacy graphics reports a child's pivot as (0, 0), so the
 * rasterizer's rotate-about-pivot footprint isn't meaningful on the JVM.
 */
@RunWith(RobolectricTestRunner::class)
class OverlayRasterizerTest {

    private val ctx: Context = RuntimeEnvironment.getApplication()

    private fun rasterizer() = OverlayRasterizer(ctx, OverlayRenderConfig.DEFAULT)

    private fun assertCovers(outer: Rect, inner: Rect) {
        assertTrue("$outer must cover $inner", outer.contains(inner))
    }

    @Test
    fun uprightChip_atScale2_coversItsSource() {
        val box = TextBox(translatedText = "Hello there", bounds = Rect(100, 100, 300, 140))
        val regions = rasterizer().rasterize(listOf(box), 640, 480, renderScale = 2f)
        assertEquals(1, regions.size)
        assertCovers(regions[0].auRect, box.bounds)
    }

    @Test
    fun drawBoundsExtension_atScale2_isCovered() {
        // A base line whose filtered furigana was folded in: the chip must
        // cover the reading's pixels above the matched rect too.
        val box = TextBox(
            translatedText = "Hello there",
            bounds = Rect(100, 120, 300, 160),
            drawBounds = Rect(100, 100, 300, 160),
        )
        val regions = rasterizer().rasterize(listOf(box), 640, 480, renderScale = 2f)
        assertEquals(1, regions.size)
        assertCovers(regions[0].auRect, box.drawBounds)
    }

    @Test
    fun rasterReuse_needsTheSameRenderSettings() {
        // Room to spare at any size, so the rect is the same under every
        // setting below and only the settings differ.
        val box = TextBox(translatedText = "Hi", bounds = Rect(100, 100, 500, 200))
        val first = OverlayRasterizer(ctx, OverlayRenderConfig.DEFAULT).rasterize(listOf(box), 640, 480)
        val same = OverlayRasterizer(ctx, OverlayRenderConfig.DEFAULT)
            .rasterize(listOf(box), 640, 480, previous = first)
        assertSame("same settings: the previous bitmap is reused", first[0].bitmap, same[0].bitmap)
        for (changed in listOf(OverlayRenderConfig(minTextSp = 12), OverlayRenderConfig(verticalGrowEnabled = true))) {
            val redrawn = OverlayRasterizer(ctx, changed).rasterize(listOf(box), 640, 480, previous = first)
            assertEquals("$changed: same rect", first[0].auRect, redrawn[0].auRect)
            assertNotSame("$changed: drawn again", first[0].bitmap, redrawn[0].bitmap)
            assertEquals(changed, redrawn[0].renderConfig)
        }
    }

    @Test
    fun scaledForRender_scalesEveryGeometryField() {
        val box = TextBox(
            translatedText = "Hi",
            bounds = Rect(10, 20, 110, 60),
            drawBounds = Rect(10, 5, 110, 60),
            angleDeg = 12f,
            orientedWidth = 90f,
            orientedHeight = 30f,
            minWidthPx = 40,
        )
        val scaled = scaledForRender(box, 2f)
        assertEquals(Rect(20, 40, 220, 120), scaled.bounds)
        assertEquals(Rect(20, 10, 220, 120), scaled.drawBounds)
        assertEquals(180f, scaled.orientedWidth, 0f)
        assertEquals(60f, scaled.orientedHeight, 0f)
        assertEquals(80, scaled.minWidthPx)
        assertEquals("the angle is scale-invariant", 12f, scaled.angleDeg, 0f)
    }

    @Test
    fun scaledForRender_scalesEveryRectField() {
        // A Rect added to TextBox later is geometry the resolver may map, so
        // the render copy has to scale it too.
        val box = TextBox(
            translatedText = "Hi",
            bounds = Rect(10, 20, 110, 60),
            drawBounds = Rect(10, 5, 110, 60),
        )
        val scaled = scaledForRender(box, 2f)
        val rectFields = TextBox::class.java.declaredFields.filter { it.type == Rect::class.java }
        assertTrue(rectFields.size >= 2)
        for (field in rectFields) {
            field.isAccessible = true
            val before = field.get(box) as Rect
            assertEquals(
                field.name,
                Rect(before.left * 2, before.top * 2, before.right * 2, before.bottom * 2),
                field.get(scaled),
            )
        }
    }
}
