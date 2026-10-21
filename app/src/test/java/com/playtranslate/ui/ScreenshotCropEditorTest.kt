package com.playtranslate.ui

import android.app.Activity
import android.content.Context
import android.graphics.RectF
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * [ScreenshotCropEditor] fits the screenshot into its stage and confines
 * the drag box to it, so the fractions it reports are the image's; it
 * seeds the box from the crop that stands (and then offers 🗑), writes ✓
 * and 🗑 to the state and leaves it alone on ✕, closes exactly once, and
 * re-fits the frame when the stage is resized (the box keeps its
 * fractions). The screenshot is a real PNG (the decode reads its size).
 */
@RunWith(RobolectricTestRunner::class)
class ScreenshotCropEditorTest {

    class Host : Activity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_PlayTranslate)
            super.onCreate(savedInstanceState)
        }
    }

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After fun tearDown() = scope.cancel()

    /** A 200 x 100 screenshot in the capture cache. */
    private fun screenshot(): File {
        val f = File(ctx.cacheDir, "screenshots/capture-d0.png")
        f.parentFile!!.mkdirs()
        ImageIO.write(BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB), "png", f)
        return f
    }

    private fun stateFor(crop: ScreenshotCrop?): AnkiScreenshotState {
        val args = Bundle().apply {
            putString(AnkiScreenshotState.ARG_SCREENSHOT_PATH, screenshot().absolutePath)
        }
        return AnkiScreenshotState.open(ctx, args)!!.also { it.setCrop(crop) }
    }

    private class Shown(val editor: ScreenshotCropEditor, val host: FrameLayout) {
        var closed = 0
        /** The stage's children once showing: the image, the drag box, the chrome layer. */
        val drag: RegionDragView get() = editor.view.getChildAt(1) as RegionDragView
        val bar: ViewGroup get() = editor.controls.getChildAt(0) as ViewGroup
        fun layOut(w: Int, h: Int) {
            host.measure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
            )
            host.layout(0, 0, w, h)
        }
        fun box(): RectF = RectF().also(drag::boxInView)
    }

    private fun show(state: AnkiScreenshotState): Shown {
        val activity = Robolectric.buildActivity(Host::class.java).setup().get()
        val host = FrameLayout(activity)
        lateinit var shown: Shown
        val editor = ScreenshotCropEditor(activity, scope, state) { shown.closed++ }
        shown = Shown(editor, host)
        host.addView(editor.view, FrameLayout.LayoutParams(-1, -1))
        shown.layOut(1000, 2000)
        // The decode runs on IO; the editor shows on the main thread after it.
        val deadline = System.currentTimeMillis() + 5_000
        while (editor.view.childCount < 3) {
            shadowOf(Looper.getMainLooper()).idle()
            check(System.currentTimeMillis() < deadline) { "the editor never showed" }
            Thread.sleep(10)
        }
        shadowOf(Looper.getMainLooper()).idle()
        return shown
    }

    private val crop = ScreenshotCrop.of(0.1f, 0.2f, 0.9f, 0.8f)!!

    @Test fun `the screenshot is fitted and the box seeded from the crop lives in it`() {
        val s = show(stateFor(crop))
        // 200 x 100 fitted into 1000 x 2000: 1000 x 500 at y 750.
        val box = s.box()
        assertEquals(100f, box.left, 1e-3f)
        assertEquals(750f + 0.2f * 500f, box.top, 1e-3f)
        assertEquals(900f, box.right, 1e-3f)
        assertEquals(750f + 0.8f * 500f, box.bottom, 1e-3f)
        assertEquals("✕ 🗑 ✓", 3, s.bar.childCount)
    }

    @Test fun `without a crop the box is the default and there is no trash`() {
        val s = show(stateFor(null))
        assertEquals(0.25f, s.drag.topFraction, 0f)
        assertEquals(0.75f, s.drag.rightFraction, 0f)
        assertEquals("✕ ✓", 2, s.bar.childCount)
    }

    @Test fun `confirm writes the box to the state and closes once`() {
        val state = stateFor(null)
        val s = show(state)
        s.drag.setRegion(top = 0.3f, bottom = 0.6f, left = 0.2f, right = 0.7f)
        val confirm = s.bar.getChildAt(1)
        confirm.performClick()
        assertEquals(ScreenshotCrop.of(0.2f, 0.3f, 0.7f, 0.6f), state.attachment!!.crop)
        assertEquals(1, s.closed)
        confirm.performClick()
        assertEquals(1, s.closed)
    }

    @Test fun `a box over the whole screenshot confirms as no crop`() {
        val state = stateFor(crop)
        val s = show(state)
        s.drag.setRegion(top = 0f, bottom = 1f, left = 0f, right = 1f)
        s.bar.getChildAt(2).performClick()
        assertNull(state.attachment!!.crop)
        assertEquals(1, s.closed)
    }

    @Test fun `trash clears the crop and cancel keeps it`() {
        val cleared = stateFor(crop)
        val t = show(cleared)
        t.bar.getChildAt(1).performClick()
        assertNull(cleared.attachment!!.crop)
        assertEquals(1, t.closed)

        val kept = stateFor(crop)
        val c = show(kept)
        c.drag.setRegion(top = 0.3f, bottom = 0.6f, left = 0.2f, right = 0.7f)
        c.bar.getChildAt(0).performClick()
        assertEquals(crop, kept.attachment!!.crop)
        assertEquals(1, c.closed)
    }

    @Test fun `a resized stage re-fits the frame and the box keeps its fractions`() {
        val s = show(stateFor(crop))
        s.layOut(1000, 1000)
        // 1000 x 500 at y 250 now.
        val box = s.box()
        assertEquals(250f + 0.2f * 500f, box.top, 1e-3f)
        assertEquals(0.2f, s.drag.topFraction, 0f)
        assertEquals(100f, box.left, 1e-3f)
    }
}
