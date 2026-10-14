package com.playtranslate

import android.content.ComponentName
import android.content.Context
import android.os.Looper
import android.provider.Settings
import android.view.Display
import android.view.View
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.capture.CaptureBackendResolver
import com.playtranslate.capture.CaptureLifecycle
import com.playtranslate.overlay.OverlayHost
import com.playtranslate.ui.SonarPingIntroView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.shadows.ShadowLog
import java.time.Duration

/**
 * [OverlayUiController.reconcileFloatingIcons] with a saved selection that
 * names no connected display (a disconnected second screen, a display id
 * that died with the projection that created it): the icon goes on the
 * default display ONCE, and every later reconcile leaves it alone. Until
 * 2026-10-14 the accessibility shim passed the selection through as stored,
 * the sweep read it while the install read the fallback, and each reconcile
 * removed the fallback's icon as unselected and placed it again as a fresh
 * appearance, intro and all; a Galaxy S25 reporting a display change every
 * few seconds, each reconciled by MainActivity's display listener, showed
 * the icon popping inboard and sliding back to the edge for as long as the
 * app was open. The shim now resolves a dead selection to the default
 * display ([com.playtranslate.capture.AccessibilityCaptureBackendTest]);
 * this pins the icon's end of it, and the reconcile's own one-set rule as
 * the belt. On [ErrorPillsFollowCaptureDisplaysTest]'s footing. A display disconnecting
 * from under its icon is not staged: Robolectric keeps the window, whose
 * next traversal reads the removed display's info and fails the looper.
 * The sweep of an icon no longer wanted is the same line whichever way the
 * selection and the displays moved, and the last test takes it the other
 * way round.
 */
@RunWith(RobolectricTestRunner::class)
class ReconcileStaleDisplaySelectionTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val prefs = Prefs(ctx)
    private val controller = OverlayUiController(
        ctx, OverlayHost(ctx, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY),
    )
    private val first = Display.DEFAULT_DISPLAY

    /** A display id nothing is connected on. */
    private val dead = 7

    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))

    private fun reconcile(times: Int = 1) = repeat(times) {
        controller.reconcileFloatingIcons()
        settle()
    }

    private fun addDisplay(): Int = ShadowDisplayManager.addDisplay("w640dp-h480dp")

    @Suppress("UNCHECKED_CAST")
    private fun handles(): Map<Int, Any> =
        OverlayUiController::class.java.getDeclaredField("iconHandles")
            .apply { isAccessible = true }.get(controller) as Map<Int, Any>

    private fun icon(displayId: Int): View? {
        val handle = handles()[displayId] ?: return null
        return handle.javaClass.getDeclaredField("icon").apply { isAccessible = true }.get(handle) as View
    }

    /** Icons the reconcile's sweep removed, from the controller's own log. */
    private fun sweeps(): Int =
        ShadowLog.getLogsForTag("OverlayUiController").count { "reconcile_remove" in it.msg }

    /** Intro windows in the process's window list: one per fresh appearance,
     *  each kept at least the churn gate's quiet gap after it ends. */
    private fun intros(): Int {
        val global = Class.forName("android.view.WindowManagerGlobal").getMethod("getInstance").invoke(null)
        val views = global.javaClass.getDeclaredField("mViews").apply { isAccessible = true }.get(global) as List<*>
        return views.count { it is SonarPingIntroView }
    }

    @Before fun setUp() {
        ShadowLog.clear()
        Settings.Secure.putString(
            ctx.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ComponentName(ctx, PlayTranslateAccessibilityService::class.java).flattenToString(),
        )
        CaptureBackendResolver.reresolve(ctx)
        assertTrue(CaptureBackendResolver.active().requiresAccessibilityService)
        CaptureLifecycle.setFloatingIconSuppressed(ctx, false)
    }

    @After fun tearDown() {
        controller.hideAll()
        CaptureLifecycle.setFloatingIconSuppressed(ctx, true)
        // Let the churn gate finish every removal inside this test.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
    }

    @Test fun `a selection naming no connected display puts the icon on the fallback once and leaves it there`() {
        prefs.captureDisplayIds = setOf(dead)

        reconcile()
        val placed = icon(first)
        assertNotNull("the fallback display carries the icon", placed)
        assertEquals(setOf(first), handles().keys)

        reconcile(times = 5)   // the S25's display events, MainActivity reconciling on each

        assertSame("the same icon: never swept, never placed again", placed, icon(first))
        assertEquals(0, sweeps())
        assertTrue("one appearance, one intro at most", intros() <= 1)
    }

    @Test fun `a connected selected display beside a dead one carries the icon, and keeps it`() {
        val second = addDisplay()
        prefs.captureDisplayIds = setOf(second, dead)
        reconcile()
        val placed = icon(second)
        assertNotNull(placed)
        assertEquals("no fallback while a selected display is connected", setOf(second), handles().keys)

        reconcile(times = 3)
        assertSame(placed, icon(second))
        assertEquals(0, sweeps())
    }

    @Test fun `the fallback icon gives way to a selection naming a connected display`() {
        prefs.captureDisplayIds = setOf(dead)
        reconcile()
        assertEquals(setOf(first), handles().keys)

        val second = addDisplay()
        prefs.captureDisplayIds = setOf(second)   // the picker
        reconcile()

        assertEquals(setOf(second), handles().keys)
        assertEquals("the fallback's icon", 1, sweeps())
    }
}
