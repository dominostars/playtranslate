package com.playtranslate.overlay

import android.content.Context
import android.os.Looper
import android.view.Display
import android.view.View
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * A window added while a clean capture of its display is in flight goes up
 * blanked and comes back with that capture's restore, so it can't reach a
 * frame stamped free of our overlays (Codex 2026-09-27: a translation-error
 * pill reported mid-capture could). Windows on other displays, and windows
 * added once the capture restored, go up as given.
 */
@RunWith(RobolectricTestRunner::class)
class OverlayHostCaptureTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val host = OverlayHost(ctx, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
    private val wm = OwnWindows.manager(ctx)!!
    private val display = Display.DEFAULT_DISPLAY

    private fun add(alpha: Float = 1f, displayId: Int = display): WindowManager.LayoutParams {
        val params = WindowManager.LayoutParams(
            100, 100, 0,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, android.graphics.PixelFormat.TRANSLUCENT,
        ).apply { this.alpha = alpha }
        host.addOverlayWindow(View(ctx), wm, params, displayId)
        return params
    }

    @After fun tearDown() {
        host.removeAll()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
    }

    @Test fun `a window added during a clean capture goes up blanked and comes back with it`() {
        val before = add()
        val capture = host.prepareForCleanCapture(display)
        val during = add()
        val dimmed = add(alpha = 0.5f)

        assertEquals(0f, before.alpha)
        assertEquals("added mid-capture: kept out of the frame", 0f, during.alpha)
        assertEquals(0f, dimmed.alpha)

        host.restoreAfterCapture(capture)

        assertEquals(1f, before.alpha)
        assertEquals(1f, during.alpha)
        assertEquals("back at the alpha it was added with", 0.5f, dimmed.alpha)
    }

    @Test fun `once the capture restored, a window goes up as given, even if the restore runs again`() {
        val capture = host.prepareForCleanCapture(display)
        host.restoreAfterCapture(capture)   // the fast path, as the screenshot lands

        val after = add()
        assertEquals("the capture is over: nothing holds it back", 1f, after.alpha)

        host.restoreAfterCapture(capture)   // the finally
        assertEquals(1f, after.alpha)
    }

    @Test fun `a capture of another display leaves this one's new windows alone`() {
        val capture = host.prepareForCleanCapture(display + 1)
        val here = add()
        assertEquals(1f, here.alpha)
        host.restoreAfterCapture(capture)
    }
}
