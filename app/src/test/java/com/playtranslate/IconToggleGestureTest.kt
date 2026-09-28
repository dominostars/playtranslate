package com.playtranslate

import android.app.Application
import android.content.Context
import android.os.Looper
import android.view.Display
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.capture.CaptureLifecycle
import com.playtranslate.overlay.OverlayHost
import com.playtranslate.ui.FloatingIconMenu
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
import org.robolectric.shadows.ShadowDisplayManager
import java.time.Duration

/**
 * The floating icon's toggle gesture ("Start/stop auto translate"), driven
 * through a real icon's tap and hold callbacks as its touch handling fires
 * them. It is the quick menu's Auto button without the menu: it stops a
 * running auto-translate and starts a stopped one in the overlay mode
 * already selected, on every source language, and makes auto-translate the
 * menu's primary. The menu's Auto button runs the same helper, so its cell
 * is here too.
 *
 * "Running" is a [CaptureService] with a stand-in [LiveMode] in its mode
 * map; a stop on this device (one screen, or the app not in front) tears it
 * down directly. What this JVM can see of a start is In-App Only's:
 * MainActivity starts it (a second viewport, overlays hidden, one capture
 * display), as it does any start while the app is in front on the other
 * screen, where a stop goes through MainActivity too. MainActivity never
 * runs here, so a stop sent to it leaves the session running: the state a
 * hold's drag finds when it comes before the app has handled the stop.
 */
@RunWith(RobolectricTestRunner::class)
class IconToggleGestureTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val prefs = Prefs(ctx)
    private val controller = OverlayUiController(
        ctx, OverlayHost(ctx, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY),
    )
    private var service: ServiceController<CaptureService>? = null

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

    /** The floating menu's most-recently-used primary, process-wide. */
    private val preferredPrimaryField = OverlayUiController::class.java
        .getDeclaredField("captureIsPreferredPrimary").apply { isAccessible = true }

    @Before fun setUp() {
        clearPrefs()
        preferredPrimaryField.set(null, false)
        CaptureLifecycle.setFloatingIconSuppressed(ctx, false)
        prefs.captureDisplayIds = setOf(Display.DEFAULT_DISPLAY)
    }

    @After fun tearDown() {
        MainActivity.isInForeground = false
        controller.hideAll()
        CaptureLifecycle.setFloatingIconSuppressed(ctx, true)
        service?.destroy()
        service = null
        preferredPrimaryField.set(null, false)
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

    /** A second screen with game overlays hidden: In-App Only, whose start
     *  MainActivity runs. */
    private fun inAppOnly() {
        ShadowDisplayManager.addDisplay("w640dp-h480dp")
        prefs.hideGameOverlays = true
    }

    /** The app in front on a second screen: starts and stops go through it. */
    private fun appInFront() {
        ShadowDisplayManager.addDisplay("w640dp-h480dp")
        MainActivity.isInForeground = true
    }

    /** The lens closing after a drag: what its dismissal reports. */
    private fun closeLens() {
        val handles = OverlayUiController::class.java.getDeclaredField("iconHandles")
            .apply { isAccessible = true }.get(controller) as Map<*, *>
        val handle = handles[Display.DEFAULT_DISPLAY]!!
        val lens = handle.javaClass.getDeclaredField("dragController")
            .apply { isAccessible = true }.get(handle) as com.playtranslate.ui.DragLookupController
        lens.onSettled!!.invoke()
    }

    private fun quickMenu(): FloatingIconMenu? =
        OverlayUiController::class.java.getDeclaredField("floatingMenu")
            .apply { isAccessible = true }.get(controller) as FloatingIconMenu?

    private fun startedActions(): List<String?> {
        val app = shadowOf(ctx as Application)
        return generateSequence { app.nextStartedActivity }.map { it.action }.toList()
    }

    @Test fun `a tap stops a running session`() {
        prefs.overlayMode = OverlayMode.FURIGANA
        prefs.iconTapAction = TapAction.TOGGLE_AUTO_TRANSLATE
        val icon = icon()
        val mode = running(OverlayFlavor.FURIGANA)

        icon.onTap!!.invoke()

        assertEquals(1, mode.stops)
        assertFalse(service!!.get().isLive)
        assertEquals(OverlayMode.FURIGANA, prefs.overlayMode)
        assertNull(quickMenu())
        assertEquals(emptyList<String?>(), startedActions())
    }

    @Test fun `with auto-translate off a tap starts it in the mode already selected`() {
        inAppOnly()
        prefs.overlayMode = OverlayMode.FURIGANA
        prefs.iconTapAction = TapAction.TOGGLE_AUTO_TRANSLATE
        val icon = icon()

        icon.onTap!!.invoke()

        assertEquals(listOf<String?>(MainActivity.ACTION_START_LIVE), startedActions())
        assertEquals(OverlayMode.FURIGANA, prefs.overlayMode)
        assertNull(quickMenu())
    }

    @Test fun `a hold toggles once at the threshold, and neither its lift nor a slide into a drag toggles back`() {
        inAppOnly()
        prefs.iconHoldAction = HoldAction.TOGGLE_AUTO_TRANSLATE
        val icon = icon()
        val mode = running(OverlayFlavor.IN_APP_ONLY)
        val svc = service!!.get()

        icon.onHoldStart!!.invoke()
        assertEquals("the threshold stopped it", 1, mode.stops)
        assertFalse(svc.isLive)
        icon.onHoldEnd!!.invoke()
        assertFalse(svc.isLive)
        assertEquals("the lift started nothing", emptyList<String?>(), startedActions())

        icon.onHoldStart!!.invoke()
        assertEquals(
            "the threshold started it",
            listOf<String?>(MainActivity.ACTION_START_LIVE), startedActions(),
        )
        icon.onHoldCancel!!.invoke()
        assertEquals("the slide stopped nothing", emptyList<String?>(), startedActions())
        assertEquals(1, mode.stops)
        assertNull(quickMenu())
    }

    // Unlike the swap, the toggle has nothing to do with the reading hint,
    // so a language without one keeps it rather than reading it as the
    // gesture's default.
    @Test fun `on a source without a reading hint the toggle still toggles`() {
        prefs.sourceLang = "en"
        prefs.iconTapAction = TapAction.TOGGLE_AUTO_TRANSLATE
        prefs.iconHoldAction = HoldAction.TOGGLE_AUTO_TRANSLATE
        val icon = icon()
        val mode = running(OverlayFlavor.TRANSLATION)

        icon.onTap!!.invoke()

        assertEquals(1, mode.stops)
        assertFalse(service!!.get().isLive)
        assertNull("the tap is not its default, the menu", quickMenu())
        assertEquals(TapAction.TOGGLE_AUTO_TRANSLATE, prefs.iconTapAction)
        assertEquals(HoldAction.TOGGLE_AUTO_TRANSLATE, prefs.iconHoldAction)
    }

    // The app in front on the other screen owns the session's start and
    // stop, as it does for the menu's Auto button.
    @Test fun `with the app in front on the other screen the stop goes through the app`() {
        appInFront()
        prefs.iconTapAction = TapAction.TOGGLE_AUTO_TRANSLATE
        val icon = icon()
        val mode = running(OverlayFlavor.TRANSLATION)

        icon.onTap!!.invoke()

        assertEquals(listOf<String?>(MainActivity.ACTION_STOP_LIVE), startedActions())
        assertEquals("the app stops it, not the icon", 0, mode.stops)
    }

    @Test fun `the toggle makes auto-translate the quick menu's primary`() {
        prefs.iconTapAction = TapAction.TOGGLE_AUTO_TRANSLATE
        prefs.iconHoldAction = HoldAction.OPEN_QUICK_MENU
        val icon = icon()
        running(OverlayFlavor.TRANSLATION)
        preferredPrimaryField.set(null, true)

        icon.onTap!!.invoke()
        icon.onHoldStart!!.invoke()

        val menu = quickMenu()
        assertNotNull("the hold opened the quick menu", menu)
        assertFalse("capture is no longer the primary", menu!!.captureHighlighted)
    }

    @Test fun `the quick menu's Auto button closes the menu and toggles the same way`() {
        inAppOnly()
        val icon = icon()
        val mode = running(OverlayFlavor.IN_APP_ONLY)
        preferredPrimaryField.set(null, true)

        icon.onTap!!.invoke()
        quickMenu()!!.onToggleLive!!.invoke()
        assertNull(quickMenu())
        assertEquals(1, mode.stops)
        assertFalse(service!!.get().isLive)
        assertEquals(false, preferredPrimaryField.get(null))

        icon.onTap!!.invoke()
        quickMenu()!!.onToggleLive!!.invoke()
        assertNull(quickMenu())
        assertEquals(listOf<String?>(MainActivity.ACTION_START_LIVE), startedActions())
    }

    // Codex review, 2026-09-27: the drag used to find the session still
    // running, pause it, and start it again when the lens closed.
    @Test fun `a hold's stop that slides into a drag stays stopped after the lens closes`() {
        appInFront()
        prefs.iconHoldAction = HoldAction.TOGGLE_AUTO_TRANSLATE
        val icon = icon()
        running(OverlayFlavor.TRANSLATION)

        icon.onHoldStart!!.invoke()
        assertEquals(listOf<String?>(MainActivity.ACTION_STOP_LIVE), startedActions())
        assertTrue("the app hasn't stopped it yet", service!!.get().isLive)
        icon.onHoldCancel!!.invoke()
        icon.onDragStart!!.invoke()
        closeLens()

        assertEquals(emptyList<String?>(), startedActions())
    }

    // The hold's mark lasts one gesture: the next drag pauses a running
    // session and resumes it when its lens closes, as a drag always has.
    @Test fun `the drag after a hold's gesture pauses and resumes a running session`() {
        appInFront()
        prefs.iconHoldAction = HoldAction.TOGGLE_AUTO_TRANSLATE
        val icon = icon()
        running(OverlayFlavor.TRANSLATION)

        // A hold that stops and lifts, then a plain drag.
        icon.onHoldStart!!.invoke()
        icon.onHoldEnd!!.invoke()
        assertEquals(listOf<String?>(MainActivity.ACTION_STOP_LIVE), startedActions())
        icon.onDragStart!!.invoke()
        assertEquals(listOf<String?>(MainActivity.ACTION_STOP_LIVE), startedActions())
        closeLens()
        assertEquals(listOf<String?>(MainActivity.ACTION_START_LIVE), startedActions())

        // A hold that stops and slides into its drag, then a plain drag.
        icon.onHoldStart!!.invoke()
        icon.onHoldCancel!!.invoke()
        icon.onDragStart!!.invoke()
        closeLens()
        assertEquals(listOf<String?>(MainActivity.ACTION_STOP_LIVE), startedActions())
        icon.onDragStart!!.invoke()
        assertEquals(listOf<String?>(MainActivity.ACTION_STOP_LIVE), startedActions())
        closeLens()
        assertEquals(listOf<String?>(MainActivity.ACTION_START_LIVE), startedActions())
    }

    // A hold that starts auto-translate marks nothing: its drag pauses the
    // session if it's already running and resumes it afterwards.
    @Test fun `a hold's start that slides into a drag is paused and resumed by it`() {
        prefs.iconHoldAction = HoldAction.TOGGLE_AUTO_TRANSLATE
        val icon = icon()
        appInFront()

        icon.onHoldStart!!.invoke()
        assertEquals(listOf<String?>(MainActivity.ACTION_START_LIVE), startedActions())
        // The app has started it by the time the finger slides.
        running(OverlayFlavor.TRANSLATION)
        icon.onHoldCancel!!.invoke()
        icon.onDragStart!!.invoke()
        assertEquals(listOf<String?>(MainActivity.ACTION_STOP_LIVE), startedActions())
        closeLens()
        assertEquals(listOf<String?>(MainActivity.ACTION_START_LIVE), startedActions())
    }
}
