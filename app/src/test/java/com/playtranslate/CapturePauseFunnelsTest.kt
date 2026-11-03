package com.playtranslate

import android.content.ComponentName
import android.content.Context
import android.os.Looper
import android.provider.Settings
import android.view.Display
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.capture.CaptureBackendResolver
import com.playtranslate.capture.CaptureLifecycle
import com.playtranslate.model.TextSegment
import com.playtranslate.model.TranslationResult
import com.playtranslate.ui.DragLookupController
import com.playtranslate.ui.FloatingOverlayIcon
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
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
import org.robolectric.shadows.ShadowDisplayManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Duration

/**
 * The over-game capture sheet's pause record: a capture on one screen stops
 * a running auto-translate and the user's own close of the sheet restarts
 * it; every other way the sheet ends disposes of that debt as its funnel
 * says (a page cancels it, a new capture takes it over, a live start and
 * the pill's gear and the menu settle it without a restart, capture's end
 * drops it, a hold sliding into a drag forgets it, tap-to-lookup's hold as
 * the capture's, a turn of the display and a change of the game language
 * give the screen back).
 *
 * On [TapToLookupHotkeyTest]'s footing: the accessibility service is
 * created and never connected and resolved as the active backend, so its
 * controller is the one the service's seams reach; the capture service
 * runs, with a stand-in live mode when a session is wanted. One screen, so
 * the sheet goes up over this display and stops and starts are direct; a
 * Translation start waits on screen-record consent, which is how a restart
 * shows here ([CaptureService.isLiveStartPending]).
 */
@RunWith(RobolectricTestRunner::class)
class CapturePauseFunnelsTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val prefs = Prefs(ctx)
    private var service: ServiceController<CaptureService>? = null
    private var a11y: ServiceController<PlayTranslateAccessibilityService>? = null

    private class StandInLiveMode(override val flavor: OverlayFlavor) : LiveMode {
        var stops = 0
        override fun start() {}
        override fun stop() { stops++ }
        override fun refresh() {}
        override fun getCachedState(): CachedOverlayState? = null
    }

    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))

    private fun clearPrefs() {
        ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun controller(): OverlayUiController = a11y!!.get().overlayUiController
    private fun svc(): CaptureService = service!!.get()

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(name: String): T =
        OverlayUiController::class.java.getDeclaredField(name).apply { isAccessible = true }.get(controller()) as T

    private fun panel(): Any? = field("captureResultOverlay")
    private fun record(): LookupPause? = field("capturePause")

    @Before fun setUp() {
        clearPrefs()
        Settings.Secure.putString(
            ctx.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ComponentName(ctx, PlayTranslateAccessibilityService::class.java).flattenToString(),
        )
        CaptureBackendResolver.reresolve(ctx)
        CaptureLifecycle.setFloatingIconSuppressed(ctx, false)
        prefs.captureDisplayIds = setOf(Display.DEFAULT_DISPLAY)
        prefs.sourceLang = "en"
        prefs.overlayMode = OverlayMode.TRANSLATION
        MainActivity.isInForeground = false
        val built = Robolectric.buildService(PlayTranslateAccessibilityService::class.java).create()
        a11y = built
        PlayTranslateAccessibilityService.instance = built.get()
        val svc = Robolectric.buildService(CaptureService::class.java).create()
        service = svc
        svc.get().gameDisplayIds = setOf(Display.DEFAULT_DISPLAY)
        assertTrue(CaptureBackendResolver.activeOverlayUi === built.get().overlayUiController)
        settle()
    }

    @After fun tearDown() {
        service?.get()?.stopLive()
        service?.destroy()
        service = null
        a11y?.get()?.overlayUiController?.hideAll()
        a11y?.get()?.overlayUiController?.destroy()
        PlayTranslateAccessibilityService.instance = null
        a11y = null
        MainActivity.isInForeground = false
        CaptureLifecycle.setFloatingIconSuppressed(ctx, true)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        clearPrefs()
    }

    /** Auto-translate running in Translation on the default display. */
    private fun running(): StandInLiveMode {
        val mode = StandInLiveMode(OverlayFlavor.TRANSLATION)
        val modes = CaptureService::class.java.getDeclaredField("liveModes")
            .apply { isAccessible = true }.get(svc())
        MutableMap::class.java.getMethod("put", Any::class.java, Any::class.java)
            .invoke(modes, Display.DEFAULT_DISPLAY, mode)
        assertTrue(svc().isLive)
        return mode
    }

    /** Auto-translate is running or on its way (a Translation start waits
     *  on consent here). */
    private fun liveOrStarting(): Boolean = svc().isLive || svc().isLiveStartPending

    /** The capture hotkey: a one-shot of this display, the sheet up. */
    private fun capture() {
        controller().toggleCaptureScreenForDisplay(Display.DEFAULT_DISPLAY)
        settle()
        assertNotNull("the sheet went up", panel())
    }

    /** The hotkey's second press: the user's own close, after the slide. */
    private fun closeByUser() {
        controller().toggleCaptureScreenForDisplay(Display.DEFAULT_DISPLAY)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
        assertNull("the sheet is gone", panel())
    }

    private fun captureAgain() {
        OverlayUiController::class.java
            .getDeclaredMethod("captureCurrentRegionForDisplay", Int::class.java, OverlayMode::class.java)
            .apply { isAccessible = true }.invoke(controller(), Display.DEFAULT_DISPLAY, null)
        settle()
        assertNotNull(panel())
    }

    private fun result() = TranslationResult(
        originalText = "hello", segments = listOf(TextSegment("hello")), translatedText = "hi",
        timestamp = "", langContext = prefs.langContext(),
    )

    /** Install the default display's icon and return it. */
    private fun icon(): FloatingOverlayIcon {
        controller().reconcileFloatingIcons(freshAppearance = false)
        settle()
        val handles = field<Map<*, *>>("iconHandles")
        val handle = handles[Display.DEFAULT_DISPLAY]
        assertNotNull("an icon on the default display", handle)
        return handle!!.javaClass.getDeclaredField("icon").apply { isAccessible = true }
            .get(handle) as FloatingOverlayIcon
    }

    private fun closeLens() {
        val handles = field<Map<*, *>>("iconHandles")
        val handle = handles[Display.DEFAULT_DISPLAY]!!
        val lens = handle.javaClass.getDeclaredField("dragController")
            .apply { isAccessible = true }.get(handle) as DragLookupController
        lens.onSettled!!.invoke()
    }

    /** Restarts the drag lens's own record asked for ([lensUp]). */
    private var dragRestarts = 0

    /** The default display's drag lens up and sticky, as a drag leaves it,
     *  with what that drag paused; a restart its record asks for is counted
     *  in [dragRestarts] instead of run. */
    private fun lensUp(): DragLookupController {
        val handle = field<Map<*, *>>("iconHandles")[Display.DEFAULT_DISPLAY]!!
        fun <T> of(name: String): T {
            @Suppress("UNCHECKED_CAST")
            return handle.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(handle) as T
        }
        val pause = of<LookupPause>("dragPause")
        LookupPause::class.java.getDeclaredField("startLive").apply { isAccessible = true }
            .set(pause, { dragRestarts++; Unit })
        pause.begin(pauseLive = true)
        val lens = of<DragLookupController>("dragController")
        val magnifier = DragLookupController::class.java.getDeclaredField("magnifier")
            .apply { isAccessible = true }.get(lens) as com.playtranslate.ui.MagnifierLens
        val screen = ctx.displaySizePx()
        assertTrue(magnifier.show(150, 300, screen.x, screen.y, anchorHeight = 30))
        assertTrue(magnifier.makeInteractive())
        assertTrue(lens.isPopupShowing)
        return lens
    }

    @Test fun `tap-to-lookup over an open drag lens closes it and takes over what its drag paused`() {
        icon()
        val live = running()
        val lens = lensUp()
        assertEquals("the drag paused it", 1, live.stops)

        controller().toggleTapToLookupForDisplay(Display.DEFAULT_DISPLAY)
        settle()

        assertFalse("the lens does not stay under the sheet", lens.isPopupShowing)
        assertNotNull(panel())
        assertTrue("the sheet owes the drag's restart", record()!!.pausedLive)
        assertEquals("closing the lens asked for no restart", 0, dragRestarts)
        assertFalse(liveOrStarting())
        closeByUser()
        assertTrue("the user's close gives it back", liveOrStarting())
    }

    @Test fun `tap-to-lookup from another display captures there and leaves this sheet's mode alone`() {
        val other = ShadowDisplayManager.addDisplay("w640dp-h480dp")
        capture()
        val sheet = panel() as com.playtranslate.ui.CaptureResultOverlay
        assertEquals(OverlayMode.TRANSLATION, sheet.overlayMode)

        OverlayUiController::class.java.getDeclaredMethod("captureInDefinitions", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(controller(), other)
        settle()

        assertEquals("not switched in place", OverlayMode.TRANSLATION, sheet.overlayMode)
        assertNotSame("the capture on the other display replaced it", sheet, panel())
    }

    @Test fun `the tap-to-lookup hotkey switches a sheet on another display in place`() {
        val other = ShadowDisplayManager.addDisplay("w640dp-h480dp")
        OverlayUiController::class.java
            .getDeclaredMethod("captureCurrentRegionForDisplay", Int::class.java, OverlayMode::class.java)
            .apply { isAccessible = true }.invoke(controller(), other, null)
        settle()
        val sheet = panel() as com.playtranslate.ui.CaptureResultOverlay
        assertEquals("the sheet is on the other display", other, field<Int>("captureDisplayId"))

        // The hotkey's display is the primary one; the sheet it toggles is
        // the one up, wherever it is.
        controller().toggleTapToLookupForDisplay(Display.DEFAULT_DISPLAY)
        settle()

        assertSame("the same sheet, not a capture of the primary display", sheet, panel())
        assertEquals(OverlayMode.DEFINITIONS, sheet.overlayMode)
    }

    @Test fun `a sheet whose capture is cancelled from outside settles its debt without a restart`() {
        running()
        capture()
        assertTrue(record()!!.pausedLive)
        val sheet = panel() as com.playtranslate.ui.CaptureResultOverlay

        // A region change in the app, or the app's own capture, cancels the
        // sheet's session; the sheet ends itself.
        sheet.observe(CaptureSession(MutableStateFlow<CaptureState>(CaptureState.Cancelled), Job()))
        settle()

        assertNull(panel())
        assertNull("settled, not left for a later capture", record())
        assertFalse("no restart", liveOrStarting())
        // The next capture owes nothing, so its close restarts nothing.
        capture()
        assertFalse(record()!!.pausedLive)
        closeByUser()
        assertFalse(liveOrStarting())
    }

    @Test fun `a capture stops a running auto-translate and the user's close restarts it`() {
        val live = running()
        capture()
        assertEquals(1, live.stops)
        assertFalse(svc().isLive)
        assertTrue("the sheet owes the restart", record()!!.pausedLive)

        closeByUser()

        assertNull("settled, the record is gone", record())
        assertTrue("auto-translate is back, or on its way", liveOrStarting())
    }

    @Test fun `a capture while nothing runs owes nothing`() {
        capture()
        assertFalse(record()!!.pausedLive)
        closeByUser()
        assertFalse(liveOrStarting())
    }

    @Test fun `a page opened from the sheet cancels the restart, and the re-shown sheet owes nothing`() {
        running()
        capture()
        controller().stashCaptureOverlayForReshow(Display.DEFAULT_DISPLAY, result(), CaptureService.DEFAULT_REGION)
        settle()
        assertNull(panel())
        assertNull("cancelled with the page", record())
        assertFalse(liveOrStarting())

        controller().onCaptureDetailBackPressed(Display.DEFAULT_DISPLAY)
        settle()
        assertNotNull("re-shown", panel())
        closeByUser()
        assertFalse("no restart after the page", liveOrStarting())
    }

    @Test fun `a page the sheet itself opens forgoes the restart`() {
        running()
        capture()
        // The sheet's own page paths (the OCR picker's download, the cloud
        // page, the language header, the lens's Anki) all exit through this.
        val sheet = panel()!!
        sheet.javaClass.getDeclaredMethod("dismissForPage").apply { isAccessible = true }.invoke(sheet)
        settle()
        assertNull(panel())
        assertNull(record())
        assertFalse(liveOrStarting())
    }

    @Test fun `a new capture takes the debt over and the close restarts once`() {
        val live = running()
        capture()
        captureAgain()
        assertEquals("stopped once", 1, live.stops)
        assertTrue(record()!!.pausedLive)

        closeByUser()
        assertTrue(liveOrStarting())
    }

    @Test fun `a live start settles the debt and takes the sheet down`() {
        running()
        capture()
        svc().startLive()
        settle()
        assertNull(panel())
        assertNull(record())
        svc().stopLive()
        assertFalse("nothing left to restart it", liveOrStarting())
    }

    @Test fun `the error pill's gear leaves auto-translate stopped`() {
        running()
        capture()
        OverlayUiController::class.java.getDeclaredMethod("openServiceSettingsFromPill", String::class.java, Int::class.java)
            .apply { isAccessible = true }.invoke(controller(), "deepl", Display.DEFAULT_DISPLAY)
        settle()
        assertNull(panel())
        assertNull(record())
        assertFalse(liveOrStarting())
    }

    @Test fun `the end of capture drops the debt`() {
        running()
        capture()
        controller().hideAll()
        settle()
        assertNull(panel())
        assertNull(record())
        assertFalse(liveOrStarting())
    }

    @Test fun `a page the word lens opens cancels the restart`() {
        running()
        capture()
        controller().cancelLivePauseObligation()
        closeByUser()
        assertFalse(liveOrStarting())
    }

    @Test fun `a hold's capture that slides into a drag forgets the debt`() {
        prefs.iconHoldAction = HoldAction.CAPTURE_SCREEN
        val icon = icon()
        val live = running()

        icon.onHoldStart!!.invoke()
        settle()
        assertNotNull(panel())
        assertEquals(1, live.stops)
        icon.onHoldCancel!!.invoke()
        icon.onDragStart!!.invoke()
        settle()
        assertNull("the sheet is gone", panel())
        assertNull("its debt is forgotten", record())
        closeLens()
        settle()
        assertFalse("the lens leaves a session the hold stopped stopped", liveOrStarting())
    }

    @Test fun `a hold's tap-to-lookup that slides into a drag takes its sheet down and forgets the debt`() {
        prefs.iconHoldAction = HoldAction.TAP_TO_LOOKUP
        val icon = icon()
        val live = running()

        icon.onHoldStart!!.invoke()
        settle()
        assertEquals(OverlayMode.DEFINITIONS, (panel() as com.playtranslate.ui.CaptureResultOverlay).overlayMode)
        assertEquals(1, live.stops)
        icon.onHoldCancel!!.invoke()
        icon.onDragStart!!.invoke()
        settle()
        assertNull("the sheet is gone", panel())
        assertNull("its debt is forgotten", record())
        closeLens()
        settle()
        assertFalse("the lens leaves a session the hold stopped stopped", liveOrStarting())
    }

    @Test fun `the quick menu opening settles the debt without a restart`() {
        prefs.iconTapAction = TapAction.OPEN_QUICK_MENU
        val icon = icon()
        running()
        capture()

        icon.onTap!!.invoke()
        settle()

        assertNull(panel())
        assertNull(record())
        assertFalse(liveOrStarting())
    }

    @Test fun `a turn of the sheet's display gives the screen back`() {
        controller().attach()
        running()
        capture()

        ShadowDisplayManager.changeDisplay(Display.DEFAULT_DISPLAY, "+land")
        settle()

        assertNull(panel())
        assertNull(record())
        assertTrue(liveOrStarting())
    }

    @Test fun `a change of the game language closes the sheet and gives the screen back`() {
        running()
        capture()

        prefs.sourceLang = "es"
        settle()

        assertNull(panel())
        assertNull(record())
        assertTrue("auto-translate is back, or on its way", liveOrStarting())
    }

    @Test fun `a sheet waiting on a page's Back stays gone after a change of the game language`() {
        running()
        capture()
        controller().stashCaptureOverlayForReshow(Display.DEFAULT_DISPLAY, result(), CaptureService.DEFAULT_REGION)
        settle()

        prefs.sourceLang = "es"
        settle()
        controller().onCaptureDetailBackPressed(Display.DEFAULT_DISPLAY)
        settle()

        assertNull("not re-shown", panel())
        assertFalse("the page cancelled the restart", liveOrStarting())
    }
}
