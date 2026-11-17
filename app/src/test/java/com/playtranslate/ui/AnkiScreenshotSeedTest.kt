package com.playtranslate.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * [AnkiScreenshotState.open]'s starting crop over a real capture file: the
 * pinned JPEG decoded and read by [GamePicture] off the main thread, the
 * crop set on the picture found, nothing set where the frame has no bars.
 * NATIVE graphics (Bitmap and Canvas only) so the decode is real; the
 * state's own rules are in AnkiScreenshotStateTest with a search handed in.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AnkiScreenshotSeedTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    /** The capture file: 400 x 200, a picture between 30 px black bars when
     *  [bars], else the picture alone. */
    private fun capture(bars: Boolean): Bundle {
        val bmp = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply {
            drawColor(if (bars) Color.BLACK else 0xFF4080C0.toInt())
            if (bars) drawRect(0f, 30f, 400f, 170f, Paint().apply { color = 0xFF4080C0.toInt() })
        }
        val f = File(ctx.cacheDir, "screenshots/capture-d0.jpg")
        f.parentFile!!.mkdirs()
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        return Bundle().apply { putString(AnkiScreenshotState.ARG_SCREENSHOT_PATH, f.absolutePath) }
    }

    private fun awaitDecision(state: AnkiScreenshotState) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!state.startingCropDecided) {
            shadowOf(Looper.getMainLooper()).idle()
            check(System.currentTimeMillis() < deadline) { "the starting crop was never decided" }
            Thread.sleep(5)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun `the crop starts on the game picture found in the pinned frame`() {
        val state = AnkiScreenshotState.open(ctx, capture(bars = true))!!
        awaitDecision(state)
        val r = state.attachment!!.crop!!.toPixelRect(400, 200)
        assertEquals(0, r.left)
        assertEquals(400, r.right)
        assertTrue("top ${r.top}", r.top in 29..31)
        assertTrue("bottom ${r.bottom}", r.bottom in 169..171)
    }

    @Test fun `a frame without bars starts with no crop`() {
        val state = AnkiScreenshotState.open(ctx, capture(bars = false))!!
        awaitDecision(state)
        assertNull(state.attachment!!.crop)
    }
}
