package com.playtranslate

import android.app.Application
import android.content.Context
import android.os.Looper
import android.view.Display
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.capture.CaptureLifecycle
import com.playtranslate.overlay.OverlayHost
import com.playtranslate.ui.FloatingOverlayIcon
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.shadows.ShadowDisplayManager
import java.time.Duration

/**
 * The floating icon's swap gesture ("Swap between translation and
 * furigana", pinyin on a Chinese source), driven through a real icon's tap
 * and hold callbacks as its touch handling fires them. While auto-translate
 * runs, the swap switches it to the other overlay mode in place, the tap
 * hotkeys' switch rather than a stop and start (Gilad, 2026-09-27); while
 * it's off, the swap starts it in the mode already selected; on a source
 * language without a reading hint, a stored swap acts as its gesture's
 * default.
 *
 * "Running" is a [CaptureService] with a stand-in [LiveMode] in its mode
 * map. No accessibility service is connected in this JVM, so the switch's
 * reconcile can't build the real mode for the new flavor and returns before
 * stopping anything (setLiveDisplays' accessibility check): what the tests
 * see of the switch is the pref write, and that nothing stopped the session,
 * which a stop and start would have.
 */
@RunWith(RobolectricTestRunner::class)
class IconSwapGestureTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val prefs = Prefs(ctx)
    private val controller = OverlayUiController(
        ctx, OverlayHost(ctx, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY),
    )
    private var service: ServiceController<CaptureService>? = null

    private class StandInLiveMode(override val flavor: OverlayFlavor) : LiveMode {
        var stops = 0
        var refreshes = 0
        override fun start() {}
        override fun stop() { stops++ }
        override fun refresh() { refreshes++ }
        override fun getCachedState(): CachedOverlayState? = null
    }

    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))

    private fun clearPrefs() {
        ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Before fun setUp() {
        clearPrefs()
        CaptureLifecycle.setFloatingIconSuppressed(ctx, false)
        prefs.captureDisplayIds = setOf(Display.DEFAULT_DISPLAY)
    }

    @After fun tearDown() {
        controller.hideAll()
        CaptureLifecycle.setFloatingIconSuppressed(ctx, true)
        service?.destroy()
        service = null
        // Let the churn gate finish every removal inside this test.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        clearPrefs()
    }

    /** Install the default display's icon and return it. */
    private fun icon(): FloatingOverlayIcon {
        controller.reconcileFloatingIcons(freshAppearance = false)
        settle()
        val handles = OverlayUiController::class.java.getDeclaredField("iconHandles")
            .apply { isAccessible = true }.get(controller) as Map<*, *>
        val handle = handles[Display.DEFAULT_DISPLAY]
        assertNotNull("an icon on the default display", handle)
        return handle!!.javaClass.getDeclaredField("icon").apply { isAccessible = true }
            .get(handle) as FloatingOverlayIcon
    }

    /** Auto-translate running in [flavor] on the default display. */
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

    private fun quickMenuShowing(): Boolean =
        OverlayUiController::class.java.getDeclaredField("floatingMenu")
            .apply { isAccessible = true }.get(controller) != null

    private fun startedActions(): List<String?> {
        val app = shadowOf(ctx as Application)
        return generateSequence { app.nextStartedActivity }.map { it.action }.toList()
    }

    @Test fun `a tap while auto-translate runs switches it to the other mode in place, and back`() {
        prefs.iconTapAction = TapAction.SWAP_OVERLAY_MODE
        val icon = icon()
        val mode = running(OverlayFlavor.TRANSLATION)

        icon.onTap!!.invoke()
        assertEquals(OverlayMode.FURIGANA, prefs.overlayMode)
        icon.onTap!!.invoke()
        assertEquals(OverlayMode.TRANSLATION, prefs.overlayMode)

        assertEquals("the session was never stopped", 0, mode.stops)
        assertTrue(service!!.get().isLive)
        assertFalse(quickMenuShowing())
        assertEquals(emptyList<String?>(), startedActions())
    }

    @Test fun `on a Chinese source the tap switches to the pinyin overlay`() {
        prefs.sourceLang = "zh"
        prefs.iconTapAction = TapAction.SWAP_OVERLAY_MODE
        val icon = icon()
        val mode = running(OverlayFlavor.TRANSLATION)

        icon.onTap!!.invoke()

        assertEquals(OverlayMode.FURIGANA, prefs.overlayMode)
        assertEquals(0, mode.stops)
    }

    @Test fun `a hold swaps once at the threshold, and neither its lift nor a slide into a drag swaps back`() {
        prefs.iconHoldAction = HoldAction.SWAP_OVERLAY_MODE
        val icon = icon()
        val mode = running(OverlayFlavor.TRANSLATION)

        icon.onHoldStart!!.invoke()
        assertEquals(OverlayMode.FURIGANA, prefs.overlayMode)
        icon.onHoldEnd!!.invoke()
        assertEquals(OverlayMode.FURIGANA, prefs.overlayMode)

        icon.onHoldStart!!.invoke()
        assertEquals(OverlayMode.TRANSLATION, prefs.overlayMode)
        icon.onHoldCancel!!.invoke()
        assertEquals(OverlayMode.TRANSLATION, prefs.overlayMode)

        assertEquals(0, mode.stops)
        assertFalse(quickMenuShowing())
    }

    // In-App Only draws nothing over the game, so the switch is the pref
    // write plus a fresh poll cycle, as a tap hotkey's switch is.
    @Test fun `an in-app-only session takes the switch as a refresh`() {
        prefs.iconTapAction = TapAction.SWAP_OVERLAY_MODE
        val icon = icon()
        val mode = running(OverlayFlavor.IN_APP_ONLY)

        icon.onTap!!.invoke()

        assertEquals(OverlayMode.FURIGANA, prefs.overlayMode)
        assertEquals(1, mode.refreshes)
        assertEquals(0, mode.stops)
    }

    // What this JVM can see of a start is In-App Only's: MainActivity starts
    // it (a second viewport, overlays hidden, one capture display). The
    // single-screen start calls the service directly, the same helper the
    // floating menu's Auto button and the tap hotkeys use.
    @Test fun `with auto-translate off a tap or a hold starts it in the mode already selected`() {
        ShadowDisplayManager.addDisplay("w640dp-h480dp")
        prefs.hideGameOverlays = true
        prefs.overlayMode = OverlayMode.FURIGANA
        prefs.iconTapAction = TapAction.SWAP_OVERLAY_MODE
        prefs.iconHoldAction = HoldAction.SWAP_OVERLAY_MODE
        val icon = icon()

        icon.onTap!!.invoke()
        assertEquals(listOf<String?>(MainActivity.ACTION_START_LIVE), startedActions())
        assertEquals(OverlayMode.FURIGANA, prefs.overlayMode)

        icon.onHoldStart!!.invoke()
        icon.onHoldEnd!!.invoke()
        assertEquals(listOf<String?>(MainActivity.ACTION_START_LIVE), startedActions())
        assertEquals(OverlayMode.FURIGANA, prefs.overlayMode)
        assertFalse(quickMenuShowing())
    }

    @Test fun `without a reading hint a stored tap swap opens the quick menu`() {
        prefs.sourceLang = "en"
        prefs.iconTapAction = TapAction.SWAP_OVERLAY_MODE
        val icon = icon()

        icon.onTap!!.invoke()

        assertTrue(quickMenuShowing())
        assertEquals(OverlayMode.TRANSLATION, prefs.overlayMode)
        assertEquals(emptyList<String?>(), startedActions())
    }

    // The hold's default is the capture: a one-shot, after stopping the
    // running session on this screen; no swap, and no hold-to-preview peek.
    @Test fun `without a reading hint a stored hold swap captures the screen`() {
        prefs.sourceLang = "en"
        prefs.iconHoldAction = HoldAction.SWAP_OVERLAY_MODE
        val icon = icon()
        val mode = running(OverlayFlavor.TRANSLATION)
        val svc = service!!.get()

        icon.onHoldStart!!.invoke()
        assertEquals("the session stopped for the one-shot", 1, mode.stops)
        assertFalse(svc.isLive)
        assertFalse("no peek", svc.holdActive)
        assertEquals("no swap", OverlayMode.TRANSLATION, prefs.overlayMode)

        icon.onHoldEnd!!.invoke()
        assertFalse(svc.holdActive)
        assertEquals(OverlayMode.TRANSLATION, prefs.overlayMode)
        assertEquals(1, mode.stops)
    }
}
