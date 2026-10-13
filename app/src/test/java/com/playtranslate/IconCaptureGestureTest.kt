package com.playtranslate

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.Display
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.capture.CaptureLifecycle
import com.playtranslate.overlay.OverlayHost
import com.playtranslate.ui.FloatingIconMenu
import com.playtranslate.ui.FloatingOverlayIcon
import com.playtranslate.ui.TranslationResultActivity
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
 * The floating icon's capture gesture ("Capture screen"), the hold's default
 * and a tap candidate, driven through a real icon's callbacks as its touch
 * handling fires them. It is the quick menu's Capture button without the
 * menu: a one-shot of this display's current region, after stopping a
 * running auto-translate, and it makes capture the menu's primary. A hold
 * captures once, at the threshold; its lift captures nothing more; a
 * slide into a drag cancels the one-shot on this screen, as it cancels
 * the hold-to-preview's, and leaves the stopped session stopped once the
 * lens closes.
 *
 * With the app in front on the other screen the one-shot is a message to
 * MainActivity (ACTION_REGION_CAPTURE), as a stop is, so each capture
 * counts there; MainActivity never runs here, so the session a stop was
 * sent for is still running when the hold's drag starts. On one screen
 * the panel goes up over the display from this process.
 */
@RunWith(RobolectricTestRunner::class)
class IconCaptureGestureTest {

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

    /** A created CaptureService on the default display, auto-translate off. */
    private fun captureService(): CaptureService {
        val built = Robolectric.buildService(CaptureService::class.java).create()
        service = built
        val svc = built.get()
        svc.gameDisplayIds = setOf(Display.DEFAULT_DISPLAY)
        return svc
    }

    /** Auto-translate running in [flavor] on the default display. */
    private fun running(flavor: OverlayFlavor): StandInLiveMode {
        val svc = service?.get() ?: captureService()
        val mode = StandInLiveMode(flavor)
        val modes = CaptureService::class.java.getDeclaredField("liveModes")
            .apply { isAccessible = true }.get(svc)
        MutableMap::class.java.getMethod("put", Any::class.java, Any::class.java)
            .invoke(modes, Display.DEFAULT_DISPLAY, mode)
        assertTrue(svc.isLive)
        return mode
    }

    /** The app in front on a second screen: captures and stops go through it. */
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

    /** The activities started since the last call, in the order they were
     *  started (nextStartedActivity hands out the most recent first). */
    private fun started(): List<Intent> {
        val app = shadowOf(ctx as Application)
        return generateSequence { app.nextStartedActivity }.toList().asReversed()
    }

    private fun startedActions(): List<String?> = started().map { it.action }

    /** The display the panel went up over, once it has; -1 for none. */
    private fun panelDisplay(): Int? =
        OverlayUiController::class.java.getDeclaredField("captureDisplayId")
            .apply { isAccessible = true }.get(controller) as Int?

    private fun panel(): Any? =
        OverlayUiController::class.java.getDeclaredField("captureResultOverlay")
            .apply { isAccessible = true }.get(controller)

    @Test fun `a hold captures once at the threshold, and its lift captures nothing more`() {
        appInFront()
        prefs.iconHoldAction = HoldAction.CAPTURE_SCREEN
        val icon = icon()

        icon.onHoldStart!!.invoke()
        val sent = started()
        assertEquals(listOf<String?>(MainActivity.ACTION_REGION_CAPTURE), sent.map { it.action })
        assertEquals(
            "this display's region",
            Display.DEFAULT_DISPLAY, sent.single().getIntExtra(MainActivity.EXTRA_TARGET_DISPLAY_ID, -1),
        )
        assertEquals("capture is the menu's primary", true, preferredPrimaryField.get(null))
        assertNull(quickMenu())

        icon.onHoldEnd!!.invoke()
        assertEquals(emptyList<String?>(), startedActions())
    }

    // The hold ships bound to the capture: an icon with nothing stored
    // captures on a hold.
    @Test fun `with no binding stored a hold captures`() {
        appInFront()
        val icon = icon()

        icon.onHoldStart!!.invoke()

        assertEquals(listOf<String?>(MainActivity.ACTION_REGION_CAPTURE), startedActions())
        icon.onHoldEnd!!.invoke()
        assertEquals(emptyList<String?>(), startedActions())
    }

    @Test fun `a tap runs the same capture`() {
        appInFront()
        prefs.iconTapAction = TapAction.CAPTURE_SCREEN
        val icon = icon()

        icon.onTap!!.invoke()

        assertEquals(listOf<String?>(MainActivity.ACTION_REGION_CAPTURE), startedActions())
        assertEquals(true, preferredPrimaryField.get(null))
        assertNull(quickMenu())
    }

    @Test fun `on one screen a hold stops the running session and puts the panel up over this display`() {
        prefs.iconHoldAction = HoldAction.CAPTURE_SCREEN
        val icon = icon()
        val mode = running(OverlayFlavor.TRANSLATION)

        icon.onHoldStart!!.invoke()
        settle()

        assertEquals("stopped here, not through the app", 1, mode.stops)
        assertFalse(service!!.get().isLive)
        assertEquals("the panel went up over the default display", Display.DEFAULT_DISPLAY, panelDisplay())
        assertEquals(
            "no full-screen result page, no message to the app",
            emptyList<String>(),
            started().map { it.component?.className ?: it.action }
                .filter { it == TranslationResultActivity::class.java.name || it == MainActivity.ACTION_REGION_CAPTURE },
        )
        icon.onHoldEnd!!.invoke()
        assertEquals(1, mode.stops)
    }

    // A slide into a drag cancels the one-shot, whether its panel is already
    // up or its shot is still in flight: the lens is about to open where the
    // panel would come up. The hold-to-preview's one-shot goes the same way.
    @Test fun `on one screen a slide into a drag cancels the hold's one-shot, up or in flight`() {
        prefs.iconHoldAction = HoldAction.CAPTURE_SCREEN
        val icon = icon()
        captureService()

        // The panel is up by the time the finger slides.
        icon.onHoldStart!!.invoke()
        settle()
        assertEquals(Display.DEFAULT_DISPLAY, panelDisplay())
        assertNotNull(panel())
        icon.onHoldCancel!!.invoke()
        icon.onDragStart!!.invoke()
        settle()
        assertNull("the panel is gone", panel())
        assertEquals(-1, panelDisplay())
        closeLens()

        // The shot is still in flight when the finger slides.
        icon.onHoldStart!!.invoke()
        icon.onHoldCancel!!.invoke()
        icon.onDragStart!!.invoke()
        settle()
        assertNull("the shot was dropped", panel())
        assertEquals(-1, panelDisplay())
        assertEquals(
            "no full-screen result page either",
            emptyList<String>(),
            started().mapNotNull { it.component?.className }
                .filter { it == TranslationResultActivity::class.java.name },
        )
    }

    // Codex review, 2026-10-13: with no CaptureService (the icon outlives
    // it; a gesture's own start is asynchronous) the one-shot falls back to
    // the full-screen result page, launched once its shot is in hand, and
    // the slide used to leave that launch untouched: the page opened under
    // the lens and stayed after it closed.
    @Test fun `with no service a slide into a drag drops the hold's fallback result page, and a lift keeps it`() {
        prefs.iconHoldAction = HoldAction.CAPTURE_SCREEN
        val icon = icon()
        assertNull(CaptureService.instance)

        // The shot is in flight when the finger slides.
        icon.onHoldStart!!.invoke()
        icon.onHoldCancel!!.invoke()
        icon.onDragStart!!.invoke()
        settle()
        assertEquals("no result page", emptyList<String>(), resultPages())
        closeLens()

        // A hold that lifts lets the page open, as before.
        icon.onHoldStart!!.invoke()
        icon.onHoldEnd!!.invoke()
        settle()
        assertEquals(1, resultPages().size)
    }

    /** The full-screen result pages started since the last call. */
    private fun resultPages(): List<String> =
        started().mapNotNull { it.component?.className }
            .filter { it == TranslationResultActivity::class.java.name }

    // The stop is a message to the app, so the drag a slide turns the hold
    // into finds the session still running; it must not pause it and start
    // it again when the lens closes, over the capture's result. The capture
    // itself went to the app and is the app's to show.
    @Test fun `a hold's capture that slides into a drag leaves the stopped session stopped after the lens closes`() {
        appInFront()
        prefs.iconHoldAction = HoldAction.CAPTURE_SCREEN
        val icon = icon()
        val mode = running(OverlayFlavor.TRANSLATION)

        icon.onHoldStart!!.invoke()
        assertEquals(
            "stopped first, then captured",
            listOf<String?>(MainActivity.ACTION_STOP_LIVE, MainActivity.ACTION_REGION_CAPTURE),
            startedActions(),
        )
        assertEquals("the app stops it, not the icon", 0, mode.stops)
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
        prefs.iconHoldAction = HoldAction.CAPTURE_SCREEN
        val icon = icon()
        running(OverlayFlavor.TRANSLATION)

        icon.onHoldStart!!.invoke()
        icon.onHoldEnd!!.invoke()
        assertEquals(
            listOf<String?>(MainActivity.ACTION_STOP_LIVE, MainActivity.ACTION_REGION_CAPTURE),
            startedActions(),
        )
        icon.onDragStart!!.invoke()
        assertEquals(listOf<String?>(MainActivity.ACTION_STOP_LIVE), startedActions())
        closeLens()
        assertEquals(listOf<String?>(MainActivity.ACTION_START_LIVE), startedActions())
    }
}
