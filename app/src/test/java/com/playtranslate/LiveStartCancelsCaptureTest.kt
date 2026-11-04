package com.playtranslate

import android.content.Context
import android.os.Looper
import android.view.Display
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.capture.CaptureLifecycle
import com.playtranslate.ui.FloatingOverlayIcon
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
 * A live start claims the game surface (Codex review, 2026-09-27): a
 * one-shot capture still in flight (on its way, its screenshot not in) is
 * cancelled, so it can't put its result panel up over the session; a stop
 * leaves it alone. The floating menu does this as it opens; the
 * icon's toggle starts auto-translate without the menu, which is how the
 * capture used to survive. [CaptureService.startLive] now does it for every
 * start.
 *
 * The accessibility service is the active backend's host, so its overlay
 * controller is the one the start reaches, and the capture is one that
 * controller started and has not set up yet. The service is created, never connected: it has
 * no capture source, so the started mode captures nothing. The start runs
 * in Furigana, which on this backend doesn't ask for screen-record consent
 * first, so the session is up when the start returns.
 */
@RunWith(RobolectricTestRunner::class)
class LiveStartCancelsCaptureTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val prefs = Prefs(ctx)
    private var service: ServiceController<CaptureService>? = null
    private lateinit var controller: OverlayUiController

    private fun clearPrefs() {
        ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Before fun setUp() {
        clearPrefs()
        CaptureLifecycle.setFloatingIconSuppressed(ctx, false)
        prefs.captureDisplayIds = setOf(Display.DEFAULT_DISPLAY)
        prefs.overlayMode = OverlayMode.FURIGANA
        prefs.iconTapAction = TapAction.TOGGLE_AUTO_TRANSLATE

        val a11y = Robolectric.buildService(PlayTranslateAccessibilityService::class.java).create()
        PlayTranslateAccessibilityService.instance = a11y.get()
        controller = a11y.get().overlayUiController
        controller.reconcileFloatingIcons(freshAppearance = false)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))

        val built = Robolectric.buildService(CaptureService::class.java).create()
        service = built
        built.get().gameDisplayIds = setOf(Display.DEFAULT_DISPLAY)
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

    private fun field(name: String) =
        OverlayUiController::class.java.getDeclaredField(name).apply { isAccessible = true }

    private fun icon(): FloatingOverlayIcon {
        val handle = (field("iconHandles").get(controller) as Map<*, *>)[Display.DEFAULT_DISPLAY]
        assertNotNull("an icon on the default display", handle)
        return handle!!.javaClass.getDeclaredField("icon").apply { isAccessible = true }
            .get(handle) as FloatingOverlayIcon
    }

    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))

    /** A capture of the default display on its way: started, its
     *  screenshot not in yet, so no sheet. */
    private fun captureOnItsWay() {
        OverlayUiController::class.java
            .getDeclaredMethod("captureCurrentRegionForDisplay", Int::class.java, OverlayMode::class.java)
            .apply { isAccessible = true }.invoke(controller, Display.DEFAULT_DISPLAY, null)
        assertEquals("on its way", Display.DEFAULT_DISPLAY, controller.captureDisplayIdForTest)
        assertNull("no sheet yet", controller.captureSheetForTest)
    }

    @Test fun `the icon's toggle cancels a capture still in flight as it starts auto-translate`() {
        captureOnItsWay()

        icon().onTap!!.invoke()
        settle()

        assertTrue("the start went ahead", service!!.get().isLive)
        assertEquals("the capture is over", -1, controller.captureDisplayIdForTest)
        assertNull("its sheet never comes up over the session", controller.captureSheetForTest)
    }

    // The service's start is shared: the app's own start (MainActivity)
    // calls it too.
    @Test fun `a start from the app cancels it too`() {
        captureOnItsWay()

        service!!.get().startLive()
        settle()

        assertEquals(-1, controller.captureDisplayIdForTest)
        assertNull(controller.captureSheetForTest)
    }

    @Test fun `stopping auto-translate leaves a capture alone`() {
        // Off when the capture starts, so its own stop is not the one here;
        // then running, as a stand-in, for the toggle to stop.
        captureOnItsWay()
        val modes = CaptureService::class.java.getDeclaredField("liveModes")
            .apply { isAccessible = true }.get(service!!.get())
        MutableMap::class.java.getMethod("put", Any::class.java, Any::class.java)
            .invoke(modes, Display.DEFAULT_DISPLAY, object : LiveMode {
                override val flavor = OverlayFlavor.FURIGANA
                override fun start() {}
                override fun stop() {}
                override fun refresh() {}
                override fun getCachedState(): CachedOverlayState? = null
            })
        assertTrue(service!!.get().isLive)

        icon().onTap!!.invoke()
        settle()

        assertFalse(service!!.get().isLive)
        assertEquals(Display.DEFAULT_DISPLAY, controller.captureDisplayIdForTest)
        assertNotNull("its sheet comes up", controller.captureSheetForTest)
    }
}
