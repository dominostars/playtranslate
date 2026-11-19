package com.playtranslate

import android.app.Activity
import android.content.Context
import android.os.Looper
import android.view.Display
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.capture.CaptureLifecycle
import com.playtranslate.language.ChineseScriptVariant
import com.playtranslate.language.SourceLangId
import com.playtranslate.model.TextSegment
import com.playtranslate.model.TranslationLangContext
import com.playtranslate.model.TranslationResult
import com.playtranslate.overlay.OwnActivities
import com.playtranslate.testutil.testEnvironment
import com.playtranslate.ui.DetachedPage
import com.playtranslate.ui.DetachedWindowGeometry
import com.playtranslate.ui.ResultState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
 * Where a capture goes with a detached window on the display, and what
 * becomes of the window's page: every row of the routing rule
 * ([OverlayUiController.handleRegionSelection]), a capture into the window
 * against the captures it replaces and the ones that replace it, and the
 * events that keep the page, reset it or drop it.
 *
 * The accessibility service hosts the active controller (created, never
 * connected: no capture source, so a capture's frame is null and the service
 * reads nothing, ending the session at once). That is enough here: the test
 * is who was handed the session, the page or a sheet.
 */
@RunWith(RobolectricTestRunner::class)
class DetachedCaptureRoutingTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val prefs = Prefs(ctx)
    private val d0 = Display.DEFAULT_DISPLAY
    private val g = DetachedWindowGeometry
    private val room = g.room(1280, 800, com.playtranslate.overlay.Sides.NONE)
    private lateinit var controller: OverlayUiController
    private var service: ServiceController<CaptureService>? = null

    private fun clearPrefs() {
        ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))

    @Before fun setUp() {
        clearPrefs()
        CaptureLifecycle.setFloatingIconSuppressed(ctx, false)
        prefs.captureDisplayIds = setOf(d0)
        prefs.overlayMode = OverlayMode.FURIGANA
        OwnActivities.displayIdProbe = { d0 }
        val a11y = Robolectric.buildService(PlayTranslateAccessibilityService::class.java).create().get()
        PlayTranslateAccessibilityService.instance = a11y
        a11y.overlayHost.environmentReader = { testEnvironment(1280, 800, belowBars = false) }
        controller = a11y.overlayUiController
    }

    @After fun tearDown() {
        service?.destroy()
        service = null
        controller.hideAll()
        PlayTranslateAccessibilityService.instance = null
        CaptureLifecycle.setFloatingIconSuppressed(ctx, true)
        // Let the churn gate finish every removal inside this test.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        clearPrefs()
    }

    private fun captureService(): CaptureService {
        val built = Robolectric.buildService(CaptureService::class.java).create()
        service = built
        return built.get().also { it.gameDisplayIds = setOf(d0) }
    }

    /** The window up on the default display, as the page (the default
     *  place) or narrowed to its capture button. */
    private fun windowUp(button: Boolean = false) {
        if (button) {
            prefs.setWindowBounds(d0, landscape = true, bounds = g.toBounds(DetachedWindowGeometry.Frame(400, 200, 120, 120), room, 1f))
        }
        prefs.setControlsForm(d0, ControlsForm.WINDOW)
        controller.reconcileFloatingIcons(freshAppearance = false)
        settle()
        assertNotNull(controller.detachedWindowForTest(d0))
        assertEquals(!button, controller.detachedWindowForTest(d0)!!.showsPage)
    }

    private fun page(): DetachedPage = controller.detachedPageForTest(d0)!!

    private val idle get() = ResultState.Status(
        ctx.getString(R.string.detached_status_idle),
        hint = ctx.getString(R.string.detached_status_idle_hint),
    )

    private fun tap(id: Int) {
        controller.detachedWindowForTest(d0)!!.root.findViewById<View>(id).performClick()
    }

    /** A capture as tap-to-lookup starts one: in a mode of its own. */
    private fun tapToLookup() {
        OverlayUiController::class.java
            .getDeclaredMethod("captureCurrentRegionForDisplay", Int::class.java, OverlayMode::class.java)
            .apply { isAccessible = true }.invoke(controller, d0, OverlayMode.DEFINITIONS)
    }

    private fun result(original: String = "こんにちは") = TranslationResult(
        originalText = original,
        segments = original.map { TextSegment(it.toString()) },
        translatedText = "hello",
        timestamp = "00:00:00",
        langContext = TranslationLangContext(SourceLangId.JA, "en", ChineseScriptVariant.SIMPLIFIED),
    )

    // ── The routing rule ─────────────────────────────────────────────────

    @Test fun `a capture with the window showing its page goes to the page, and no sheet starts`() {
        captureService()
        windowUp()
        assertEquals(idle, page().vm.result.value)

        tap(R.id.detachedBarCapture)

        assertEquals("on its way to the window", d0, controller.windowCaptureDisplayIdForTest)
        assertEquals("no sheet capture", -1, controller.captureDisplayIdForTest)
        assertEquals("the page has not changed before the frame is in", idle, page().vm.result.value)

        settle()
        assertEquals("handed over", -1, controller.windowCaptureDisplayIdForTest)
        assertNotEquals("the page followed the capture", idle, page().vm.result.value)
        assertNull("still no sheet", controller.captureSheetForTest)
    }

    @Test fun `a capture that asked for a mode of its own keeps the sheet`() {
        captureService()
        windowUp()

        tapToLookup()

        assertEquals("the sheet's capture", d0, controller.captureDisplayIdForTest)
        assertEquals(-1, controller.windowCaptureDisplayIdForTest)
        settle()
        assertEquals("the page is left alone", idle, page().vm.result.value)
    }

    @Test fun `narrowed to the capture button the window's captures take the sheet`() {
        captureService()
        windowUp(button = true)

        tap(R.id.detachedCaptureButton)

        assertEquals(d0, controller.captureDisplayIdForTest)
        assertEquals(-1, controller.windowCaptureDisplayIdForTest)
        settle()
        assertEquals(idle, page().vm.result.value)
    }

    @Test fun `with the icon a capture takes the sheet, as before`() {
        captureService()
        controller.reconcileFloatingIcons(freshAppearance = false)
        settle()

        controller.toggleCaptureScreenForDisplay(d0)

        assertEquals(d0, controller.captureDisplayIdForTest)
        assertEquals(-1, controller.windowCaptureDisplayIdForTest)
    }

    @Test fun `the capture hotkey captures into the page on every press`() {
        captureService()
        windowUp()

        controller.toggleCaptureScreenForDisplay(d0)
        assertEquals(d0, controller.windowCaptureDisplayIdForTest)
        settle()
        val first = page().vm.result.value
        assertNotEquals(idle, first)

        // A second press is a second capture, not a dismissal: only a sheet
        // is closed by one.
        controller.toggleCaptureScreenForDisplay(d0)
        assertEquals(d0, controller.windowCaptureDisplayIdForTest)
        assertEquals(-1, controller.captureDisplayIdForTest)
    }

    // ── The capture's own steps ──────────────────────────────────────────

    @Test fun `a capture into the window configures a service that never was`() {
        val svc = captureService()
        assertFalse(svc.isConfigured)
        windowUp()

        tap(R.id.detachedBarCapture)
        settle()

        assertTrue(svc.isConfigured)
    }

    @Test fun `with no service the press starts one and changes nothing`() {
        windowUp()

        tap(R.id.detachedBarCapture)
        settle()

        assertEquals(-1, controller.windowCaptureDisplayIdForTest)
        assertEquals(idle, page().vm.result.value)
        assertNotNull("the service was asked for", shadowOf(ctx as android.app.Application).peekNextStartedService())
    }

    // ── Against other captures ───────────────────────────────────────────

    @Test fun `a capture into the window ends a sheet capture`() {
        captureService()
        windowUp()
        tapToLookup()
        assertEquals(d0, controller.captureDisplayIdForTest)

        tap(R.id.detachedBarCapture)

        assertEquals("the sheet's capture is over", -1, controller.captureDisplayIdForTest)
        assertEquals(d0, controller.windowCaptureDisplayIdForTest)
    }

    @Test fun `a sheet capture replaces one still on its way into the window`() {
        captureService()
        windowUp()
        tap(R.id.detachedBarCapture)
        assertEquals(d0, controller.windowCaptureDisplayIdForTest)

        tapToLookup()

        assertEquals(-1, controller.windowCaptureDisplayIdForTest)
        settle()
        assertEquals("it never lands in the page", idle, page().vm.result.value)
    }

    @Test fun `a second capture into the window replaces the first on its way`() {
        captureService()
        windowUp()
        tap(R.id.detachedBarCapture)

        tap(R.id.detachedBarCapture)

        assertEquals(d0, controller.windowCaptureDisplayIdForTest)
        settle()
        assertEquals(-1, controller.windowCaptureDisplayIdForTest)
        assertNotEquals(idle, page().vm.result.value)
    }

    @Test fun `auto-translate starting drops a capture on its way with the page`() {
        val svc = captureService()
        windowUp()
        tap(R.id.detachedBarCapture)

        svc.startLive()
        settle()

        assertEquals(-1, controller.windowCaptureDisplayIdForTest)
        assertNull("docked: the page is gone", controller.detachedPageForTest(d0))
        assertNull(controller.detachedWindowForTest(d0))
    }

    @Test fun `a takeover that ends captures on their way ends one into the window, and the page keeps what it showed`() {
        captureService()
        windowUp()
        tap(R.id.detachedBarCapture)
        assertEquals(d0, controller.windowCaptureDisplayIdForTest)

        controller.endCaptures(CaptureTakeover.LANGUAGE_PICKER)

        assertEquals(-1, controller.windowCaptureDisplayIdForTest)
        settle()
        assertEquals(idle, page().vm.result.value)
    }

    @Test fun `a takeover of shown captures, of Definitions only, or of another display leaves it on its way`() {
        captureService()
        windowUp()
        tap(R.id.detachedBarCapture)

        controller.endCaptures(CaptureTakeover.DISPLAY_TURN, setOf(d0))
        assertEquals("shown captures only", d0, controller.windowCaptureDisplayIdForTest)
        controller.endCaptures(CaptureTakeover.HOLD_PREVIEW, setOf(d0))
        assertEquals("Definitions only", d0, controller.windowCaptureDisplayIdForTest)
        controller.endCaptures(CaptureTakeover.LENS_START, setOf(d0 + 5))
        assertEquals("another display's", d0, controller.windowCaptureDisplayIdForTest)

        settle()
        assertNotEquals("and it lands", idle, page().vm.result.value)
    }

    @Test fun `a display that turned while the frame was taken drops the capture`() {
        captureService()
        windowUp()
        tap(R.id.detachedBarCapture)

        val display = (ctx.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager).getDisplay(d0)
        shadowOf(display).setRotation(android.view.Surface.ROTATION_180)
        settle()

        assertEquals(-1, controller.windowCaptureDisplayIdForTest)
        assertEquals("nothing landed in the page", idle, page().vm.result.value)
    }

    // ── The page across the form's changes ───────────────────────────────

    @Test fun `a capture of the display that wrote its image takes the page's older result down, and another display's does not`() {
        windowUp()
        val page = page()
        page.vm.displayResult(result(), ctx)
        val shown = page.vm.result.value

        // Another screen's capture is not this page's.
        controller.onCaptureImageSaved(d0 + 1, byCaptureJob = true)
        assertEquals(shown, page.vm.result.value)

        // The sheet's, the app's or a hold-to-preview's capture of this one.
        controller.onCaptureImageSaved(d0, byCaptureJob = true)
        assertEquals(idle, page.vm.result.value)
    }

    @Test fun `the page and its result survive the icon standing in`() {
        windowUp()
        val page = page()
        page.vm.displayResult(result(), ctx)
        val shown = page.vm.result.value

        val screen = Robolectric.buildActivity(Activity::class.java).create().start()
        settle()
        assertNull(controller.detachedWindowForTest(d0))
        assertSame("the page is kept", page, controller.detachedPageForTest(d0))

        screen.stop().destroy()
        settle()
        assertNotNull(controller.detachedWindowForTest(d0))
        assertSame(page, controller.detachedPageForTest(d0))
        assertEquals(shown, page.vm.result.value)
        assertSame("the window shows that page", page.view.parent, controller.detachedWindowForTest(d0)!!.root.findViewById(R.id.detachedPageSlot))
    }

    @Test fun `docking drops the page`() {
        windowUp()

        tap(R.id.detachedBarMinimize)
        settle()

        assertNull(controller.detachedPageForTest(d0))
    }

    @Test fun `hiding the controls drops the page`() {
        windowUp()

        controller.hideAll()
        settle()

        assertNull(controller.detachedPageForTest(d0))
    }

    @Test fun `a display that stops being a capture display loses its window and page`() {
        windowUp()

        prefs.showOverlayIcon = false
        controller.reconcileFloatingIcons(freshAppearance = false)
        settle()

        assertNull(controller.detachedWindowForTest(d0))
        assertNull(controller.detachedPageForTest(d0))
    }
}
