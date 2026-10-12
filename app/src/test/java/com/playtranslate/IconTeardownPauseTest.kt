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
 * What becomes of what a drag session paused (auto-translate, the region
 * indicator) when the icon is torn down mid-session is the host's decision,
 * made by why it is tearing the icon down: capture carrying on restores
 * them as the session's own end would; capture ending forgets them, since
 * whoever ends capture stops auto-translate itself, and a resume from the
 * teardown used to start it on the backend being torn down (Codex
 * adversarial review, 2026-10-11). The controller's teardown reports
 * nothing either way ([com.playtranslate.ui.DragLookupController.destroy]).
 *
 * With the app in front on a second screen the start and the stop are both
 * routed through MainActivity as intents, which is what this JVM can observe
 * of them.
 */
@RunWith(RobolectricTestRunner::class)
class IconTeardownPauseTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val prefs = Prefs(ctx)
    private val controller = OverlayUiController(
        ctx, OverlayHost(ctx, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY),
    )
    private var service: ServiceController<CaptureService>? = null

    private class StandInLiveMode(override val flavor: OverlayFlavor) : LiveMode {
        override fun start() {}
        override fun stop() {}
        override fun refresh() {}
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
        MainActivity.isInForeground = false
        controller.hideAll()
        CaptureLifecycle.setFloatingIconSuppressed(ctx, true)
        service?.destroy()
        service = null
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

    /** Auto-translate running on the default display. */
    private fun running() {
        val built = Robolectric.buildService(CaptureService::class.java).create()
        service = built
        val svc = built.get()
        svc.gameDisplayIds = setOf(Display.DEFAULT_DISPLAY)
        val modes = CaptureService::class.java.getDeclaredField("liveModes")
            .apply { isAccessible = true }.get(svc)
        MutableMap::class.java.getMethod("put", Any::class.java, Any::class.java)
            .invoke(modes, Display.DEFAULT_DISPLAY, StandInLiveMode(OverlayFlavor.FURIGANA))
        assertTrue(svc.isLive)
    }

    /** The app in front on a second screen: starts and stops go through it. */
    private fun appInFront() {
        ShadowDisplayManager.addDisplay("w640dp-h480dp")
        MainActivity.isInForeground = true
    }

    private fun startedActions(): List<String?> {
        val app = shadowOf(ctx as Application)
        return generateSequence { app.nextStartedActivity }.map { it.action }.toList()
    }

    /** A drag starts and pauses auto-translate for its lookup. */
    private fun dragPausesLive() {
        appInFront()
        running()
        prefs.iconDragAction = DragAction.LOOKUP_WORDS
        val icon = icon()

        icon.onDragStart!!.invoke()

        assertEquals(listOf<String?>(MainActivity.ACTION_STOP_LIVE), startedActions())
    }

    @Test fun `a teardown that ends capture forgets the drag's pause`() {
        dragPausesLive()

        controller.hideFloatingIcon("hideAll", endsCapture = true)
        settle()

        assertEquals("no start on the way out", emptyList<String?>(), startedActions())
    }

    @Test fun `a teardown while capture carries on restores what the drag paused`() {
        dragPausesLive()

        controller.hideFloatingIcon("task_removed", endsCapture = false)
        settle()

        assertEquals(listOf<String?>(MainActivity.ACTION_START_LIVE), startedActions())
    }
}
