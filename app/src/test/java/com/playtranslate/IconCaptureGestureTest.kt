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
    private fun panelDisplay(): Int = controller.captureDisplayIdForTest

    private fun panel(): Any? = controller.captureSheetForTest

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
    // it again when the lens closes. The capture itself went to the app,
    // which keeps it on its page: it never comes up over the game (below).
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

        assertEquals("no restart, and nothing else sent", emptyList<String?>(), startedActions())
    }

    /** Boxes of a capture as the app's page puts them up when it lands. */
    private fun landed() = OneShotOverlayData(
        boxes = listOf(com.playtranslate.ui.TextBox("hi", android.graphics.Rect(100, 100, 300, 140))),
        cropLeft = 0, cropTop = 0, screenshotW = 1280, screenshotH = 720,
        cropWidth = 1280, cropHeight = 720,
        text = listOf(android.graphics.Rect(100, 100, 300, 140)),
    )

    /** The capture [id] lands on the app's page, which puts it up over the
     *  game by itself: true when it went up. */
    private fun lands(id: Long): Boolean =
        controller.presentCapture(Display.DEFAULT_DISPLAY, landed(), null, captureId = id) {}

    /** The id the last capture sent to the app carries. */
    private fun sentCaptureId(intents: List<Intent>): Long =
        intents.single { it.action == MainActivity.ACTION_REGION_CAPTURE }
            .getLongExtra(MainActivity.EXTRA_REGION_CAPTURE_ID, -1L)
            .also { assertTrue("the capture carries an id", it > 0L) }

    // On two screens a capture's result goes up over the game by itself, so
    // one the hold sent to the app would land over the lens the slide opens:
    // the slide ends it on the game display, whatever its mode, and sends
    // the app nothing; its result stays on the app's page.
    @Test fun `with the app in front a hold's capture or tap-to-lookup that slides into a drag never comes up over the game`() {
        appInFront()
        captureService()
        val icon = icon()
        for (action in listOf(HoldAction.CAPTURE_SCREEN, HoldAction.TAP_TO_LOOKUP)) {
            prefs.iconHoldAction = action
            icon.onHoldStart!!.invoke()
            val id = sentCaptureId(started())
            assertTrue("$action: on its way", controller.appCaptureOnItsWayForTest(id))
            icon.onHoldCancel!!.invoke()
            icon.onDragStart!!.invoke()
            closeLens()

            assertEquals("$action: nothing more sent", emptyList<String?>(), startedActions())
            assertFalse("$action: refused when it lands", lands(id))
            assertFalse(controller.capturePresentationShowing)
        }
    }

    // A hold that lifts keeps its capture: its result comes up when it lands.
    @Test fun `a hold that lifts keeps the capture it sent`() {
        appInFront()
        captureService()
        prefs.iconHoldAction = HoldAction.CAPTURE_SCREEN
        val icon = icon()

        icon.onHoldStart!!.invoke()
        icon.onHoldEnd!!.invoke()
        val id = sentCaptureId(started())

        assertTrue(lands(id))
        assertTrue(controller.capturePresentationShowing)
        assertFalse("landed, it is the window now", controller.appCaptureOnItsWayForTest(id))
    }

    // A message to an app no longer in front would bring it forward: the
    // slide ends the capture on the game display without one.
    @Test fun `a hold whose app went behind before the slide sends nothing and still ends its capture`() {
        appInFront()
        captureService()
        prefs.iconHoldAction = HoldAction.CAPTURE_SCREEN
        val icon = icon()

        icon.onHoldStart!!.invoke()
        val id = sentCaptureId(started())
        MainActivity.isInForeground = false
        icon.onHoldCancel!!.invoke()

        assertEquals(emptyList<String?>(), startedActions())
        assertFalse(controller.appCaptureOnItsWayForTest(id))
    }

    // On one screen the capture is the sheet's, cancelled here: nothing goes
    // to the app.
    @Test fun `on one screen a hold's slide into a drag sends the app nothing`() {
        captureService()
        prefs.iconHoldAction = HoldAction.CAPTURE_SCREEN
        val icon = icon()

        icon.onHoldStart!!.invoke()
        settle()
        icon.onHoldCancel!!.invoke()

        assertEquals(emptyList<String?>(), startedActions())
    }

    // Each capture sent to the app is a capture of its own: a newer one
    // replaces an older still on its way, whose result then stays on the page.
    @Test fun `each capture sent to the app has its own id, and a newer one replaces an older`() {
        appInFront()
        captureService()
        prefs.iconTapAction = TapAction.CAPTURE_SCREEN
        val icon = icon()

        icon.onTap!!.invoke()
        val first = sentCaptureId(started())
        icon.onTap!!.invoke()
        val second = sentCaptureId(started())

        assertTrue(first != second)
        assertFalse(lands(first))
        assertTrue(lands(second))
    }

    // A drag lookup that starts while the app reads a capture for this game
    // display takes the screen from it (CaptureTakeover.LENS_START): its
    // result stays on the page. A capture already up stays under the lens.
    @Test fun `with the app in front a drag that starts while a capture is on its way keeps it off the game`() {
        appInFront()
        captureService()
        prefs.iconTapAction = TapAction.CAPTURE_SCREEN
        val icon = icon()

        icon.onTap!!.invoke()
        val id = sentCaptureId(started())
        icon.onDragStart!!.invoke()

        assertFalse(controller.appCaptureOnItsWayForTest(id))
        assertFalse("refused when it lands", lands(id))
        closeLens()
    }

    @Test fun `with the app in front a drag leaves a capture already up over the game`() {
        appInFront()
        captureService()
        prefs.iconTapAction = TapAction.CAPTURE_SCREEN
        val icon = icon()
        icon.onTap!!.invoke()
        assertTrue(lands(sentCaptureId(started())))

        icon.onDragStart!!.invoke()

        assertTrue("it stays under the lens, as it always has", controller.capturePresentationShowing)
        closeLens()
    }

    // On one screen the capture still taking its screenshot never comes up
    // over the lens, and the lens takes over what it stopped: the lens's
    // close gives auto-translate back, as the capture's close would have.
    @Test fun `on one screen a drag that starts while a capture is on its way ends it, and the lens gives back what it stopped`() {
        prefs.iconTapAction = TapAction.CAPTURE_SCREEN
        val icon = icon()
        val mode = running(OverlayFlavor.TRANSLATION)
        icon.onTap!!.invoke()
        assertEquals("the capture stopped it", 1, mode.stops)
        assertEquals("on its way", Display.DEFAULT_DISPLAY, panelDisplay())
        assertNull(panel())

        icon.onDragStart!!.invoke()
        settle()

        assertNull("never comes up over the lens", panel())
        assertEquals(-1, panelDisplay())
        assertFalse(service!!.get().isLive || service!!.get().isLiveStartPending)
        closeLens()
        assertTrue(
            "the lens gives it back",
            service!!.get().isLive || service!!.get().isLiveStartPending,
        )
    }

    /** The default display's drag lens up and sticky, as a drag leaves it,
     *  with what that drag paused (with the app in front, a stop sent to
     *  it). */
    private fun lensUp(): com.playtranslate.ui.DragLookupController {
        val handles = OverlayUiController::class.java.getDeclaredField("iconHandles")
            .apply { isAccessible = true }.get(controller) as Map<*, *>
        val handle = handles[Display.DEFAULT_DISPLAY]!!
        fun <T> of(name: String): T {
            @Suppress("UNCHECKED_CAST")
            return handle.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(handle) as T
        }
        of<LookupPause>("dragPause").begin(pauseLive = true)
        val lens = of<com.playtranslate.ui.DragLookupController>("dragController")
        val magnifier = com.playtranslate.ui.DragLookupController::class.java.getDeclaredField("magnifier")
            .apply { isAccessible = true }.get(lens) as com.playtranslate.ui.MagnifierLens
        val screen = ctx.displaySizePx()
        assertTrue(magnifier.show(150, 300, screen.x, screen.y, anchorHeight = 30))
        assertTrue(magnifier.makeInteractive())
        assertTrue(lens.isPopupShowing)
        return lens
    }

    @Test fun `with the app in front tap-to-lookup over an open drag lens closes it without the restart its drag owed`() {
        appInFront()
        prefs.iconTapAction = TapAction.TAP_TO_LOOKUP
        val icon = icon()
        running(OverlayFlavor.TRANSLATION)
        val lens = lensUp()
        assertEquals("the drag's stop went to the app", listOf<String?>(MainActivity.ACTION_STOP_LIVE), startedActions())

        icon.onTap!!.invoke()

        assertFalse("the lens does not stay under the capture's window", lens.isPopupShowing)
        val actions = startedActions()
        assertTrue(actions.toString(), MainActivity.ACTION_REGION_CAPTURE in actions)
        assertFalse("nothing restarts auto-translate under the capture", MainActivity.ACTION_START_LIVE in actions)
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
