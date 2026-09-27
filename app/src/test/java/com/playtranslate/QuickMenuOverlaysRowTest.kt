package com.playtranslate

import android.content.Context
import android.os.Looper
import android.view.Display
import androidx.lifecycle.MutableLiveData
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.CaptureService.HoldBehavior
import com.playtranslate.capture.CaptureLifecycle
import com.playtranslate.ui.FloatingIconMenu
import com.playtranslate.ui.FloatingOverlayIcon
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * The floating menu's Overlays row while auto-translate runs (Codex reviews,
 * 2026-09-27). The row switches the running session in place, as the icon's
 * swap and the tap hotkeys do, so the saved mode is always the one on screen
 * and the swap flips what is actually showing. The rebuild happens while the
 * menu keeps live mode paused, so it draws nothing (no region flash) and
 * closing the menu, whichever way, does nothing more; a stop right after
 * (Pause) finds nothing left to undo. A rebuild outside a pause still
 * flashes the region.
 *
 * An accessibility service is present (created, never connected: it has no
 * capture source, so a rebuilt mode starts without capturing), so the
 * capture service's switch really rebuilds the session's mode instead of
 * declining. The session starts as a real Translation mode that was never
 * started (stopping it is safe by [LiveMode.stop]'s contract), on a custom
 * region, since only a custom region flashes. The running mode is read off
 * [CaptureService.holdBehavior]: a hold over a furigana overlay shows
 * translations, over a translation overlay it hides them.
 */
@RunWith(RobolectricTestRunner::class)
class QuickMenuOverlaysRowTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val prefs = Prefs(ctx)
    private var service: ServiceController<CaptureService>? = null
    private lateinit var controller: OverlayUiController
    private lateinit var icon: FloatingOverlayIcon
    private lateinit var svc: CaptureService
    private lateinit var translationMode: PinholeOverlayMode

    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))

    private fun clearPrefs() {
        ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Before fun setUp() {
        clearPrefs()
        CaptureLifecycle.setFloatingIconSuppressed(ctx, false)
        prefs.captureDisplayIds = setOf(Display.DEFAULT_DISPLAY)
        // Tap keeps opening the quick menu; Hold is the swap.
        prefs.iconHoldAction = HoldAction.SWAP_OVERLAY_MODE

        val a11y = Robolectric.buildService(PlayTranslateAccessibilityService::class.java).create()
        PlayTranslateAccessibilityService.instance = a11y.get()
        controller = a11y.get().overlayUiController
        controller.reconcileFloatingIcons(freshAppearance = false)
        settle()
        icon = iconOnDefaultDisplay()

        val built = Robolectric.buildService(CaptureService::class.java).create()
        service = built
        svc = built.get()
        svc.gameDisplayIds = setOf(Display.DEFAULT_DISPLAY)
        // A custom region, set before live mode so setting it flashes nothing.
        svc.configureOverride(Display.DEFAULT_DISPLAY, RegionEntry("", 0.1f, 0.6f, 0.1f, 0.9f))
        // Auto-translate running in Translation on the default display.
        translationMode = PinholeOverlayMode(svc, Display.DEFAULT_DISPLAY)
        val modes = CaptureService::class.java.getDeclaredField("liveModes")
            .apply { isAccessible = true }.get(svc)
        MutableMap::class.java.getMethod("put", Any::class.java, Any::class.java)
            .invoke(modes, Display.DEFAULT_DISPLAY, translationMode)
        val liveState = CaptureService::class.java.getDeclaredField("_liveModeState")
            .apply { isAccessible = true }.get(svc)
        MutableLiveData::class.java.getMethod("setValue", Any::class.java).invoke(liveState, true)
        assertEquals(HoldBehavior.HIDE_TRANSLATIONS, svc.holdBehavior)
        assertNull(regionFlash())
    }

    @After fun tearDown() {
        // Stops live mode: every mode's loop and input monitoring.
        service?.destroy()
        service = null
        controller.hideAll()
        PlayTranslateAccessibilityService.instance = null
        CaptureLifecycle.setFloatingIconSuppressed(ctx, true)
        // Let the churn gate finish every removal inside this test.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        clearPrefs()
    }

    private fun iconOnDefaultDisplay(): FloatingOverlayIcon {
        val handles = OverlayUiController::class.java.getDeclaredField("iconHandles")
            .apply { isAccessible = true }.get(controller) as Map<*, *>
        val handle = handles[Display.DEFAULT_DISPLAY]
        assertNotNull("an icon on the default display", handle)
        return handle!!.javaClass.getDeclaredField("icon").apply { isAccessible = true }
            .get(handle) as FloatingOverlayIcon
    }

    /** Open the quick menu with a tap on the icon. */
    private fun openQuickMenu(): FloatingIconMenu {
        icon.onTap!!.invoke()
        val menu = OverlayUiController::class.java.getDeclaredField("floatingMenu")
            .apply { isAccessible = true }.get(controller) as FloatingIconMenu?
        assertNotNull("the tap opened the quick menu", menu)
        return menu!!
    }

    private fun runningMode(): Any? =
        (CaptureService::class.java.getDeclaredField("liveModes")
            .apply { isAccessible = true }.get(svc) as Map<*, *>)[Display.DEFAULT_DISPLAY]

    /** The region indicator's window, while one is up. */
    private fun regionFlash(): Any? {
        val regions = OverlayUiController::class.java.getDeclaredField("regionController")
            .apply { isAccessible = true }.get(controller)
        return RegionOverlayController::class.java.getDeclaredField("regionIndicatorView")
            .apply { isAccessible = true }.get(regions)
    }

    @Test fun `the row switches the running session while the menu is up, and the swap then flips it`() {
        val menu = openQuickMenu()
        menu.onCycleOverlayMode!!.invoke()
        assertEquals(OverlayMode.FURIGANA, prefs.overlayMode)
        assertEquals(HoldBehavior.SHOW_TRANSLATIONS_OVER_FURIGANA, svc.holdBehavior)
        assertTrue("still paused behind the menu", svc.holdActive)
        assertNull("no region flash over the menu", regionFlash())

        // A tap outside the menu closes it.
        menu.onDismiss!!.invoke()
        assertEquals(HoldBehavior.SHOW_TRANSLATIONS_OVER_FURIGANA, svc.holdBehavior)
        assertFalse(svc.holdActive)

        icon.onHoldStart!!.invoke()
        icon.onHoldEnd!!.invoke()
        assertEquals(OverlayMode.TRANSLATION, prefs.overlayMode)
        assertEquals(HoldBehavior.HIDE_TRANSLATIONS, svc.holdBehavior)
    }

    @Test fun `closing the menu after the row rebuilds nothing more`() {
        val menu = openQuickMenu()
        menu.onCycleOverlayMode!!.invoke()
        val switched = runningMode()

        menu.onDismiss!!.invoke()
        assertSame(switched, runningMode())
    }

    // Codex: an Overlays change and then Pause used to rebuild the session
    // as the menu closed, flashing the region as auto-translate stopped.
    @Test fun `the row then Pause stops the session with no region flash`() {
        val menu = openQuickMenu()
        menu.onCycleOverlayMode!!.invoke()
        menu.onToggleLive!!.invoke()

        assertFalse(svc.isLive)
        assertNull(regionFlash())
    }

    @Test fun `a rebuild outside a pause still flashes the region`() {
        icon.onHoldStart!!.invoke()
        icon.onHoldEnd!!.invoke()
        assertEquals(HoldBehavior.SHOW_TRANSLATIONS_OVER_FURIGANA, svc.holdBehavior)
        assertNotNull(regionFlash())
    }

    @Test fun `with auto-translate off the row only saves the mode`() {
        svc.stopLive()
        val menu = openQuickMenu()
        menu.onCycleOverlayMode!!.invoke()
        assertEquals(OverlayMode.FURIGANA, prefs.overlayMode)
        assertFalse(svc.isLive)
    }
}
