package com.playtranslate

import android.content.Context
import android.graphics.Rect
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.MotionEvent
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.language.SourceLangId
import com.playtranslate.overlay.OverlayHost
import com.playtranslate.ui.CaptureOverlayWindow
import com.playtranslate.ui.TextBox
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import java.time.Duration

/**
 * The capture's presentation on the game display when the app's results
 * page is the panel ([OverlayUiController.presentCapture] and its window,
 * [CaptureOverlayWindow]): nothing in None, the capture's mode otherwise;
 * a fill or a reading build that leaves nothing takes it down; the row's
 * pick re-flavors in place or takes it down; a running auto-translate stops
 * first; a tap on the game screen hands it back, a tap on the pill does not;
 * the dismissed-notification fires once per window.
 */
@RunWith(RobolectricTestRunner::class)
class CapturePresentationTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val prefs = Prefs(ctx)
    private val controller = OverlayUiController(
        ctx, OverlayHost(ctx, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY),
    )
    private var service: ServiceController<CaptureService>? = null
    private var dismissals = 0

    private class StandInLiveMode(override val flavor: OverlayFlavor) : LiveMode {
        var stops = 0
        override fun start() {}
        override fun stop() { stops++ }
        override fun refresh() {}
        override fun getCachedState(): CachedOverlayState? = null
    }

    private val rect = Rect(100, 100, 300, 140)
    private val group = OcrManager.OcrGroup(
        "hello", rect, lines = listOf(OcrManager.LineBox("hello", rect, groupIndex = 0)),
    )
    private val readingBox = TextBox("へろ", Rect(100, 90, 300, 100), isFurigana = true)

    private fun clearPrefs() {
        ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Before fun setUp() {
        clearPrefs()
        prefs.captureDisplayIds = setOf(Display.DEFAULT_DISPLAY)
    }

    @After fun tearDown() {
        controller.hideAll()
        service?.destroy()
        service = null
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        clearPrefs()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun data(vararg texts: String) = OneShotOverlayData(
        boxes = texts.map { TextBox(it, rect) },
        cropLeft = 0, cropTop = 0, screenshotW = 1280, screenshotH = 720,
        cropWidth = 1280, cropHeight = 720,
        text = listOf(rect),
        groups = listOf(group),
    )

    private fun present(mode: OverlayMode, data: OneShotOverlayData = data("hi")): Boolean {
        prefs.captureOverlayMode = mode
        val shown = controller.presentCapture(Display.DEFAULT_DISPLAY, data, SourceLangId.JA) { dismissals++ }
        idle()
        return shown
    }

    private fun window(): CaptureOverlayWindow? =
        OverlayUiController::class.java.getDeclaredField("captureWindow")
            .apply { isAccessible = true }.get(controller) as CaptureOverlayWindow?

    private fun settle() {
        val deadline = System.currentTimeMillis() + 3000
        while (window()?.layer?.pending == true && System.currentTimeMillis() < deadline) {
            idle()
            Thread.sleep(5)
        }
        idle()
    }

    /** A created CaptureService with auto-translate running in [flavor]. */
    private fun running(flavor: OverlayFlavor): StandInLiveMode {
        val built = Robolectric.buildService(CaptureService::class.java).create()
        service = built
        val svc = built.get()
        svc.gameDisplayIds = setOf(Display.DEFAULT_DISPLAY)
        val mode = StandInLiveMode(flavor)
        val modes = CaptureService::class.java.getDeclaredField("liveModes")
            .apply { isAccessible = true }.get(svc)
        MutableMap::class.java.getMethod("put", Any::class.java, Any::class.java)
            .invoke(modes, Display.DEFAULT_DISPLAY, mode)
        assertTrue(svc.isLive)
        return mode
    }

    @Test fun `none puts nothing on the game display`() {
        assertFalse(present(OverlayMode.NONE))
        assertFalse(controller.capturePresentationShowing)
        assertNull(controller.capturePresentationMode)
        assertEquals(0, dismissals)
    }

    @Test fun `none leaves a running auto-translate alone`() {
        val live = running(OverlayFlavor.TRANSLATION)
        assertFalse(present(OverlayMode.NONE))
        assertEquals("nothing took the game screen, so nothing stopped", 0, live.stops)
        assertTrue(service!!.get().isLive)
    }

    @Test fun `translations present the chips in the capture's mode`() {
        assertTrue(present(OverlayMode.TRANSLATION))
        assertTrue(controller.capturePresentationShowing)
        assertEquals(OverlayMode.TRANSLATION, controller.capturePresentationMode)
        assertTrue(window()!!.layer.hasBoxes)
    }

    @Test fun `data with no chips presents nothing in translations`() {
        assertFalse(present(OverlayMode.TRANSLATION, fillOneShotOverlayData(data(""), listOf(" "))!!))
        assertFalse(controller.capturePresentationShowing)
    }

    @Test fun `a fill that leaves nothing takes the window down once`() {
        assertTrue(present(OverlayMode.TRANSLATION, data("")))
        controller.updateCapture(fillOneShotOverlayData(data(""), listOf(" "))!!)
        idle()
        assertFalse(controller.capturePresentationShowing)
        assertEquals(1, dismissals)
    }

    @Test fun `none from the row takes the window down and writes the pref`() {
        assertTrue(present(OverlayMode.TRANSLATION))
        controller.setCaptureMode(OverlayMode.NONE)
        idle()
        assertFalse(controller.capturePresentationShowing)
        assertEquals(OverlayMode.NONE, prefs.captureOverlayMode)
        assertEquals(1, dismissals)
    }

    @Test fun `a reading pick re-flavors in place and keeps the window when readings land`() {
        assertTrue(present(OverlayMode.TRANSLATION))
        window()!!.layer.readingBoxes = { _, _ -> listOf(readingBox) }
        controller.setCaptureMode(OverlayMode.FURIGANA)
        settle()
        assertTrue(controller.capturePresentationShowing)
        assertEquals(OverlayMode.FURIGANA, controller.capturePresentationMode)
        assertEquals(OverlayMode.FURIGANA, prefs.captureOverlayMode)
        assertTrue(window()!!.layer.hasBoxes)
        assertEquals(0, dismissals)
    }

    @Test fun `readings that land empty take the window down`() {
        assertTrue(present(OverlayMode.TRANSLATION))
        window()!!.layer.readingBoxes = { _, _ -> emptyList() }
        controller.setCaptureMode(OverlayMode.FURIGANA)
        settle()
        assertFalse(controller.capturePresentationShowing)
        assertEquals(1, dismissals)
    }

    @Test fun `a pick with no window up only moves the pref`() {
        controller.setCaptureMode(OverlayMode.FURIGANA)
        assertEquals(OverlayMode.FURIGANA, prefs.captureOverlayMode)
        assertFalse(controller.capturePresentationShowing)
    }

    @Test fun `a running auto-translate stops before the capture takes the game screen`() {
        val live = running(OverlayFlavor.TRANSLATION)
        assertTrue(present(OverlayMode.TRANSLATION))
        assertEquals(1, live.stops)
        assertFalse(service!!.get().isLive)
        assertTrue(controller.capturePresentationShowing)
    }

    @Test fun `a tap on the game screen hands it back, a tap on the pill does not`() {
        assertTrue(present(OverlayMode.TRANSLATION))
        val w = window()!!
        w.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(1280, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(720, android.view.View.MeasureSpec.EXACTLY),
        )
        w.layout(0, 0, 1280, 720)
        val pill = w.getChildAt(1)
        assertTrue("the pill is laid out top-right", pill.right > 0 && pill.bottom > 0)
        val now = SystemClock.uptimeMillis()
        val onPill = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, pill.left + 4f, pill.top + 4f, 0)
        w.dispatchTouchEvent(onPill)
        w.dispatchTouchEvent(MotionEvent.obtain(now, now + 10, MotionEvent.ACTION_UP, pill.left + 4f, pill.top + 4f, 0))
        idle()
        assertTrue("the pill's own touch", controller.capturePresentationShowing)
        val onGame = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 640f, 600f, 0)
        w.dispatchTouchEvent(onGame)
        idle()
        assertFalse(controller.capturePresentationShowing)
        assertEquals(1, dismissals)
    }

    @Test fun `presenting again replaces the window and notifies the old one once`() {
        assertTrue(present(OverlayMode.TRANSLATION))
        assertTrue(present(OverlayMode.TRANSLATION))
        assertTrue(controller.capturePresentationShowing)
        assertEquals(1, dismissals)
        controller.dismissCapturePresentation()
        controller.dismissCapturePresentation()
        assertEquals("idempotent", 2, dismissals)
    }
}
