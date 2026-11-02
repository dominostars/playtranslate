package com.playtranslate.ui

import android.content.Context
import android.graphics.Rect
import android.os.Looper
import android.view.Display
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.OcrManager
import com.playtranslate.OneShotOverlayData
import com.playtranslate.OverlayMode
import com.playtranslate.Prefs
import com.playtranslate.language.SourceLangId
import com.playtranslate.language.TokenSpan
import com.playtranslate.overlay.OverlayHost
import com.playtranslate.overlay.OwnWindows
import com.playtranslate.overlayThemedContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * Where a capture's Definitions words land on the layer ([CaptureOverlayLayer]):
 * the frame fills the layer, as it fills the chips view, so a word sits where
 * the frame puts it on the layer's own size, a slanted line keeping its
 * slant, whatever the display metrics say (they leave out the system bars
 * that the frame and the layer both cover). A layer not laid out yet holds
 * the words pending until its first layout.
 *
 * The line is [TapLookupGeometryTest]'s slanted one: "ab" at 30 degrees,
 * centre (200, 100) of the frame, 100 by 20 in its own frame, one token per
 * character.
 */
@RunWith(RobolectricTestRunner::class)
class CaptureLayerDefinitionsGeometryTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val host = OverlayHost(ctx, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
    private val wm = OwnWindows.manager(ctx)!!
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val layer = CaptureOverlayLayer(
        overlayThemedContext(ctx), scope, Prefs(ctx),
        CaptureOverlayLayer.LookupHost(ctx, wm, host, Display.DEFAULT_DISPLAY),
    ).also { l ->
        l.tokenizeLine = { text, _ -> text.map { TokenSpan(it.toString(), it.toString()) } }
    }
    private val parent = FrameLayout(ctx).apply {
        addView(layer, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    private val lineBounds = Rect(150, 70, 250, 130)
    private val slanted = OcrManager.OcrGroup(
        "ab", lineBounds,
        lines = listOf(
            OcrManager.LineBox(
                "ab", lineBounds, groupIndex = 0,
                angleDeg = 30f, orientedWidth = 100f, orientedHeight = 20f,
            ),
        ),
    )

    @After fun tearDown() {
        layer.release()
        scope.cancel()
        host.removeAll()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
    }

    private fun data(frameW: Int, frameH: Int) = OneShotOverlayData(
        boxes = listOf(TextBox("", lineBounds)),
        cropLeft = 0, cropTop = 0, screenshotW = frameW, screenshotH = frameH,
        cropWidth = frameW, cropHeight = frameH,
        text = listOf(lineBounds),
        groups = listOf(slanted),
    )

    private fun bind(frameW: Int, frameH: Int) =
        layer.bind(data(frameW, frameH), SourceLangId.JA, OverlayMode.DEFINITIONS, null, 0L)

    private fun layOut(w: Int, h: Int) {
        parent.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        parent.layout(0, 0, w, h)
    }

    private fun settle() {
        val deadline = System.currentTimeMillis() + 3000
        while (layer.pending && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun <T> Any.field(name: String): T {
        @Suppress("UNCHECKED_CAST")
        return javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this) as T
    }

    private fun boxes(): List<TapLookupGeometry.WordBox> =
        layer.field<WordLookupSession>("words").field("boxes")

    @Test fun `the words wait for the layer's size, then sit where the frame puts them on it, slant and all`() {
        val metrics = ctx.resources.displayMetrics
        assertNotEquals("the display metrics are not the layer's size here", 1280, metrics.widthPixels)
        bind(1280, 720)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("pending until the layer has a size", layer.pending)
        assertFalse(layer.hasBoxes)

        layOut(1280, 720)
        settle()

        val b = boxes()
        assertEquals(listOf("a", "b"), b.map { it.surface })
        assertEquals(30f, b[0].angleDeg)
        assertEquals(200f, b[0].pivotX, 0.5f)
        assertEquals(100f, b[0].pivotY, 0.5f)
    }

    @Test fun `a frame smaller than the layer scales onto it, slant and all`() {
        bind(640, 360)
        layOut(1280, 720)
        settle()

        val b = boxes()
        assertEquals(30f, b[0].angleDeg)
        assertEquals(400f, b[0].pivotX, 0.5f)
        assertEquals(200f, b[0].pivotY, 0.5f)
    }

    @Test fun `a layer already laid out boxes the words at once`() {
        layOut(1280, 720)
        bind(1280, 720)
        settle()

        assertTrue(layer.hasBoxes)
        assertEquals(30f, boxes()[0].angleDeg)
    }
}
