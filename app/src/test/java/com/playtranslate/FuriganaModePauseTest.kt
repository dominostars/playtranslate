package com.playtranslate

import android.content.Context
import android.graphics.Bitmap
import android.os.Looper
import android.view.Display
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.capture.CapturedFrame
import com.playtranslate.capture.LiveCaptureSource
import kotlinx.coroutines.CoroutineScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import java.time.Duration

/**
 * [FuriganaMode]'s frame loop never starts into a pause (a hold preview, the
 * rescue alert, the floating menu, the region editor) and never gives up on
 * one: a start, or a restart after game input, waits the pause out and runs
 * when it ends, whoever ends it and whether or not they refresh the mode.
 * Codex review, 2026-09-27: a Furigana mode built by a swap on one display
 * while the other display's menu held live mode paused skipped its loop,
 * and the menu closing without a refresh (the hide confirmation, cancelled)
 * left auto-translate running with no loop. A refresh during the wait
 * replaces it, and a stop cancels it.
 */
@RunWith(RobolectricTestRunner::class)
class FuriganaModePauseTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private var service: ServiceController<CaptureService>? = null
    private lateinit var svc: CaptureService
    private val source = LoopRecorder()
    private lateinit var mode: FuriganaMode

    /** A frame loop that only records being started and stopped. */
    private class LoopRecorder : LiveCaptureSource {
        var starts = 0
        private val running = mutableSetOf<Int>()
        override val minCaptureIntervalMs = 500L
        override val hasAnyLoop: Boolean get() = running.isNotEmpty()
        override suspend fun requestClean(displayId: Int, maskOwnWindows: Boolean): CapturedFrame? = null
        override suspend fun requestRaw(
            displayId: Int,
            onCaptured: (() -> Unit)?,
            maskOwnWindows: Boolean,
        ): CapturedFrame? = null
        override fun saveToCache(bitmap: Bitmap, displayId: Int): String? = null
        override fun destroy() {}
        override fun startLoop(
            displayId: Int,
            scope: CoroutineScope,
            onCleanFrame: (CapturedFrame) -> Unit,
            onRawFrame: (CapturedFrame) -> Unit,
        ) {
            starts++
            running += displayId
        }
        override fun requestCleanCapture(displayId: Int) {}
        override fun requestCleanCaptureAll() {}
        override fun stopLoop(displayId: Int) { running -= displayId }
        override fun stopAllLoops() { running.clear() }
        override fun isLoopRunning(displayId: Int): Boolean = displayId in running
    }

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Before fun setUp() {
        val built = Robolectric.buildService(CaptureService::class.java).create()
        service = built
        svc = built.get()
        mode = FuriganaMode(svc, Display.DEFAULT_DISPLAY, liveSource = { source })
    }

    @After fun tearDown() {
        mode.stop()
        service?.destroy()
        service = null
        ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun `a start during a pause waits it out and starts when the pause ends, with no refresh`() {
        svc.holdActive = true
        mode.start()
        idle(2_000)
        assertEquals("never into the pause", 0, source.starts)

        svc.holdActive = false
        idle(200)
        assertEquals(1, source.starts)
    }

    @Test fun `a restart after game input waits out a pause the same way`() {
        mode.start()
        idle(200)
        assertEquals(1, source.starts)

        mode.dismiss()
        svc.holdActive = true
        idle(Prefs(ctx).captureIntervalMs + 1_000)
        assertEquals("never into the pause", 1, source.starts)

        svc.holdActive = false
        idle(200)
        assertEquals(2, source.starts)
    }

    // A hold's end lifts the pause and refreshes in one go: the refresh
    // replaces the waiting start rather than adding a second loop.
    @Test fun `a refresh when the pause ends replaces the wait, one loop not two`() {
        svc.holdActive = true
        mode.start()
        idle(500)

        svc.holdActive = false
        mode.refresh()
        idle(2_000)
        assertEquals(1, source.starts)
    }

    @Test fun `a stop during the wait leaves no loop to start`() {
        svc.holdActive = true
        mode.start()
        idle(500)

        mode.stop()
        svc.holdActive = false
        idle(2_000)
        assertEquals(0, source.starts)
    }
}
