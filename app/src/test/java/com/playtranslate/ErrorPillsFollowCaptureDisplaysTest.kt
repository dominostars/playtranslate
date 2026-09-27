package com.playtranslate

import android.app.Activity
import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.capture.CaptureLifecycle
import com.playtranslate.overlay.OverlayHost
import com.playtranslate.translation.BackendFailure
import com.playtranslate.translation.BackendFailureKind
import com.playtranslate.translation.OnlineAttempt
import com.playtranslate.translation.TranslationBackendRegistry
import com.playtranslate.translation.TranslationError
import com.playtranslate.translation.TranslationErrorKey
import com.playtranslate.net.NetworkConnectivity.InternetState
import com.playtranslate.translation.TranslationErrorPresenter
import com.playtranslate.translation.TranslationErrorTracker
import com.playtranslate.translation.TranslationErrors
import com.playtranslate.ui.TranslationErrorPills
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/**
 * The translation-error pills and the capture session, with real floating
 * icons on two displays (the accessibility backend): a display deselected
 * (Codex native 2026-09-27: the stack stayed there, and every later pill
 * went there too), Hide for Now (capture carries on, and so do the pills),
 * capture turned off, the icon going up again (the pills keep priority
 * over it), and a pill showing whatever is in front.
 */
@RunWith(RobolectricTestRunner::class)
class ErrorPillsFollowCaptureDisplaysTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val controller = OverlayUiController(
        ctx, OverlayHost(ctx, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY),
    )
    private val errorPills = OverlayUiController::class.java.getDeclaredField("errorPills")
        .apply { isAccessible = true }.get(controller) as TranslationErrorPills
    private val first = android.view.Display.DEFAULT_DISPLAY
    private val second = ShadowDisplayManager.addDisplay("w640dp-h480dp")
    private val withdrawn = mutableListOf<List<String>>()

    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))

    private fun captureOn(vararg displayIds: Int) {
        Prefs(ctx).captureDisplayIds = displayIds.toSet()
        controller.reconcileFloatingIcons(freshAppearance = false)
        settle()
    }

    /** The floating menu's "Hide for Now". */
    private fun hideForNow() {
        OverlayUiController::class.java.getDeclaredMethod("hideFloatingIconUntilAppOpen", String::class.java)
            .apply { isAccessible = true }.invoke(controller, "test")
        settle()
    }

    private fun icon(displayId: Int): View? {
        val handles = OverlayUiController::class.java.getDeclaredField("iconHandles")
            .apply { isAccessible = true }.get(controller) as Map<*, *>
        val handle = handles[displayId] ?: return null
        return handle.javaClass.getDeclaredField("icon").apply { isAccessible = true }.get(handle) as View
    }

    /** A window's place in the stacking order: windows of one type stack
     *  in the order they were added, the last on top. */
    private fun zOrder(view: View): Int {
        val global = Class.forName("android.view.WindowManagerGlobal").getMethod("getInstance").invoke(null)
        val views = global.javaClass.getDeclaredField("mViews").apply { isAccessible = true }.get(global) as List<*>
        return views.indexOf(view).also { assertTrue("${view.javaClass.simpleName} is in a window", it >= 0) }
    }

    @Before fun setUp() {
        CaptureLifecycle.setFloatingIconSuppressed(ctx, false)
        // Record what the controller's own handler is told, and still tell it.
        val tellTracker = errorPills.onWithdrawn
        assertNotNull("the controller tells the tracker", tellTracker)
        errorPills.onWithdrawn = { owners -> withdrawn += owners; tellTracker?.invoke(owners) }
        captureOn(first, second)
    }

    @After fun tearDown() {
        controller.hideAll()
        TranslationErrors.resetForTest()
        CaptureLifecycle.setFloatingIconSuppressed(ctx, true)
        // Let the churn gate finish every removal inside this test.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
    }

    @Test fun `a display leaving capture takes its pills, and the next pill goes where capture is`() {
        assertTrue(controller.showTranslationErrorPill(TranslationError.Connection))
        assertEquals(first, errorPills.displayId)

        captureOn(second)

        assertNull("the stack left with its display", errorPills.stackRoot)
        assertEquals(listOf(listOf(TranslationErrorKey.CONNECTION_OWNER)), withdrawn)
        assertTrue(controller.showTranslationErrorPill(TranslationError.Connection))
        assertEquals("a captured display, never the one that left", second, errorPills.displayId)
    }

    @Test fun `a withdrawn pill's error shows again at its next failure`() {
        // A tracker wired as the app wires its own (fed by the registry's
        // listener, told of withdrawals by the controller); its presenter
        // counts, and puts the pill on the controller's stack.
        val shownByTracker = mutableListOf<TranslationError>()
        val tracker = TranslationErrorTracker(
            now = SystemClock::elapsedRealtime,
            internet = { InternetState.VALIDATED },
        )
        tracker.presenter = object : TranslationErrorPresenter {
            override fun show(error: TranslationError): Boolean {
                shownByTracker += error
                return errorPills.show(ctx, first, error)
            }
            override fun update(error: TranslationError) {}
            override fun hide(keys: Collection<TranslationErrorKey>) {}
        }
        TranslationErrors.install(tracker)
        fun claudeOutOfCredits() {
            ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
            val attempt = OnlineAttempt(
                "claude", "Claude", BackendFailure(BackendFailureKind.BILLING),
                SystemClock.elapsedRealtime(), reachedServer = true,
            )
            TranslationBackendRegistry.onlineAttemptListener!!.invoke(listOf(attempt))
            settle()
        }
        claudeOutOfCredits()
        claudeOutOfCredits()
        assertEquals("once per outage", 1, shownByTracker.size)

        captureOn(second)
        claudeOutOfCredits()

        assertEquals("nobody closed it: it shows again", 2, shownByTracker.size)
    }

    @Test fun `another display leaving leaves the stack alone`() {
        assertTrue(controller.showTranslationErrorPill(TranslationError.Connection))

        captureOn(first)

        assertEquals(first, errorPills.displayId)
        assertTrue(withdrawn.isEmpty())
    }

    @Test fun `turning capture off ends the session instead of withdrawing`() {
        assertTrue(controller.showTranslationErrorPill(TranslationError.Connection))

        controller.hideFloatingIcon("test", endsCapture = true)

        assertNull(errorPills.stackRoot)
        assertTrue("a session end resets the tracker; nothing to withdraw", withdrawn.isEmpty())
    }

    // ── Hide for Now: capture carries on, and so do the pills ──────────

    @Test fun `Hide for Now keeps the pills, and a new one still shows`() {
        assertTrue(controller.showTranslationErrorPill(TranslationError.Connection))

        hideForNow()

        assertEquals(first, errorPills.displayId)
        assertTrue(withdrawn.isEmpty())
        val claude = TranslationError.Service("claude", "Claude", BackendFailure(BackendFailureKind.BILLING))
        assertTrue("no icon, capture still on", controller.showTranslationErrorPill(claude))
        assertEquals(2, errorPills.stackRoot!!.childCount)
    }

    @Test fun `a reconcile while the icons are hidden for now leaves the pills`() {
        assertTrue(controller.showTranslationErrorPill(TranslationError.Connection))
        hideForNow()

        controller.reconcileFloatingIcons()   // a display hot-plug, a service reconnect
        settle()

        assertEquals(first, errorPills.displayId)
        assertTrue(withdrawn.isEmpty())
    }

    @Test fun `turning capture off while the icons are hidden still ends the session`() {
        assertTrue(controller.showTranslationErrorPill(TranslationError.Connection))
        hideForNow()

        controller.hideFloatingIcon("test", endsCapture = true)

        assertNull(errorPills.stackRoot)
    }

    // ── the pills keep priority over the icon ──────────────────────────

    @Test fun `icons coming back after Hide for Now go under the pills`() {
        assertTrue(controller.showTranslationErrorPill(TranslationError.Connection))
        hideForNow()

        CaptureLifecycle.setFloatingIconSuppressed(ctx, false)   // the app opened
        captureOn(first, second)

        assertTrue(zOrder(errorPills.stackRoot!!) > zOrder(icon(first)!!))
        assertTrue(withdrawn.isEmpty())
    }

    @Test fun `the icon going up again keeps the pills above it`() {
        // The icon is re-raised after every one-shot overlay and the in-app
        // boxes, so its tap target clears them; the pills must stay on top.
        assertTrue(controller.showTranslationErrorPill(TranslationError.Connection))

        controller.bringFloatingIconsToFront(first)
        settle()

        assertTrue(zOrder(errorPills.stackRoot!!) > zOrder(icon(first)!!))
        assertEquals(1, errorPills.stackRoot!!.childCount)
        assertTrue(withdrawn.isEmpty())
    }

    // ── a pill shows whenever there's an error (2026-09-27) ────────────

    @Test fun `a pill shows while the app is in front on the other screen`() {
        MainActivity.isInForeground = true
        try {
            assertTrue(controller.showTranslationErrorPill(TranslationError.Connection))
        } finally {
            MainActivity.isInForeground = false
        }
    }

    @Test fun `a pill shows over one of our activities, and stays when another comes up`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            assertTrue(controller.showTranslationErrorPill(TranslationError.Connection))
            assertEquals(first, errorPills.displayId)
        } finally {
            activity.pause().stop().destroy()
        }
        val another = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            assertEquals("still up", first, errorPills.displayId)
            assertTrue(withdrawn.isEmpty())
        } finally {
            another.pause().stop().destroy()
        }
    }

    @Test fun `a pill shows with no floating icon at all`() {
        controller.hideFloatingIcon("test", endsCapture = false)
        assertTrue(controller.showTranslationErrorPill(TranslationError.Connection))
    }
}
