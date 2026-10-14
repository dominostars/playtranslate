package com.playtranslate

import android.content.ComponentName
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Looper
import android.provider.Settings
import android.view.Display
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.capture.AccessibilityCaptureBackend
import com.playtranslate.capture.CaptureBackendResolver
import com.playtranslate.capture.CaptureLifecycle
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import org.robolectric.shadows.ShadowLog
import java.time.Duration

/**
 * Live start and the one-shot fan-out under the accessibility backend with
 * a selection the shim must resolve: a selection naming no connected
 * display captures the default display (until 2026-10-14 it captured
 * nothing, silently: the start went through, no mode came up, `isLive`
 * stayed false), and a selected display that is merely off pauses capture
 * instead of handing it to the default display, on the start and on a
 * reconcile alike (the reconcile callers used to skip the off displays
 * BEFORE resolving, and an empty remainder would now resolve to the
 * fallback). Furigana mode, which needs no projection consent, so the start
 * is synchronous; on [LiveStartCancelsCaptureTest]'s footing.
 */
@RunWith(RobolectricTestRunner::class)
class LiveStartStaleSelectionTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val prefs = Prefs(ctx)
    private val dm: DisplayManager = ctx.getSystemService(DisplayManager::class.java)
    private var service: ServiceController<CaptureService>? = null
    private var a11y: ServiceController<PlayTranslateAccessibilityService>? = null
    private val first = Display.DEFAULT_DISPLAY

    /** A display id nothing is connected on. */
    private val dead = 7

    private fun clearPrefs() {
        ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))

    private fun svc(): CaptureService = service!!.get()

    @Suppress("UNCHECKED_CAST")
    private fun liveDisplays(): Set<Int> =
        (CaptureService::class.java.getDeclaredField("liveModes").apply { isAccessible = true }
            .get(svc()) as Map<Int, *>).keys

    private fun addDisplay(): Int = ShadowDisplayManager.addDisplay("w640dp-h480dp")

    /** The selection as the app holds it: the pref, the service's raw set,
     *  and the floating icons placed for it. The icons matter: a live
     *  session with no icon showing and the app in the background stops
     *  itself for want of a visible surface (updateForegroundState). */
    private fun select(vararg displayIds: Int) {
        prefs.captureDisplayIds = displayIds.toSet()
        svc().gameDisplayIds = displayIds.toSet()
        a11y!!.get().overlayUiController.reconcileFloatingIcons(freshAppearance = false)
        settle()
    }

    @Before fun setUp() {
        clearPrefs()
        // The resolver flag is process-global: a class that ran before this
        // one may have left MediaProjection active, whose live start waits
        // for a consent prompt. Resolve into accessibility first.
        Settings.Secure.putString(
            ctx.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ComponentName(ctx, PlayTranslateAccessibilityService::class.java).flattenToString(),
        )
        CaptureBackendResolver.reresolve(ctx)
        assertTrue(CaptureBackendResolver.active().requiresAccessibilityService)
        CaptureLifecycle.setFloatingIconSuppressed(ctx, false)
        prefs.overlayMode = OverlayMode.FURIGANA
        val built = Robolectric.buildService(PlayTranslateAccessibilityService::class.java).create()
        a11y = built
        PlayTranslateAccessibilityService.instance = built.get()
        service = Robolectric.buildService(CaptureService::class.java).create()
    }

    @After fun tearDown() {
        svc().stopLive()
        service?.destroy()
        service = null
        a11y?.get()?.overlayUiController?.hideAll()
        PlayTranslateAccessibilityService.instance = null
        CaptureLifecycle.setFloatingIconSuppressed(ctx, true)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        clearPrefs()
    }

    @Test fun `a selection naming no connected display starts live capture on the default display`() {
        select(dead)

        svc().startLive()
        settle()

        assertTrue("live mode came up", svc().isLive)
        assertEquals(setOf(first), liveDisplays())
        assertEquals("the selection is not rewritten", setOf(dead), prefs.captureDisplayIds)
    }

    @Test fun `a selected display that is off pauses live capture instead of moving it`() {
        val second = addDisplay()
        shadowOf(dm.getDisplay(second)).setState(Display.STATE_OFF)
        select(second)

        svc().startLive()
        settle()

        assertFalse("nothing to capture until the selected display is on", svc().isLive)
        assertEquals(emptySet<Int>(), liveDisplays())
    }

    @Test fun `a selected display turning off under a running session pauses it, not moves it`() {
        val second = addDisplay()
        select(second)
        svc().startLive()
        settle()
        assertEquals(setOf(second), liveDisplays())

        shadowOf(dm.getDisplay(second)).setState(Display.STATE_OFF)
        svc().reconcileLiveModes("test: display off")
        settle()

        assertFalse("paused, and not on the default display", svc().isLive)
        assertEquals(emptySet<Int>(), liveDisplays())
    }

    @Test fun `a display event on the fallback display reaches the running session`() {
        // Live on the default display from a dead selection: the service's
        // live-time listener used to gate on the raw selection alone, so a
        // rotation of the display live mode actually ran on reached neither
        // the mode's rotation pass nor the reconcile.
        select(dead)
        svc().startLive()
        settle()
        assertEquals(setOf(first), liveDisplays())
        ShadowLog.clear()

        ShadowDisplayManager.changeDisplay(first, "+land")
        settle()

        val serviceLines = ShadowLog.getLogsForTag("CaptureService").map { it.msg }
        assertTrue(serviceLines.toString(), serviceLines.any { it.startsWith("displayListener.onDisplayChanged($first)") })
        assertTrue(serviceLines.toString(), serviceLines.any { it.startsWith("reconcileLiveModes(displayChanged($first") })
        assertTrue("the session carries on where it was", svc().isLive)
        assertEquals(setOf(first), liveDisplays())
    }

    // ── A display unplugging under a running session ───────────────────
    // Robolectric cannot stage the removal itself with a window on the
    // display (the window's traversal fails the looper), so the decision
    // the removal branch takes is pinned on its own, against the real
    // backend predicate and real displays.

    private fun afterUnplug(pruned: Set<Int>): Set<Int>? =
        CaptureService.liveSelectionAfterUnplug(ctx, AccessibilityCaptureBackend, pruned)

    @Test fun `an unplug that leaves nothing stops the session`() {
        assertNull(afterUnplug(emptySet()))
    }

    @Test fun `an unplug that leaves only a dead id stops the session too, instead of silently or on the fallback`() {
        assertNull(afterUnplug(setOf(dead)))
    }

    @Test fun `an unplug that leaves only this app's private projection display stops the session`() {
        // The id exists, so a bare existence check would have kept the
        // session alive and the resolver would have moved it to the
        // fallback, the app's own screen on a dual-screen device.
        val projection = addDisplay()
        shadowOf(dm.getDisplay(projection)).setFlags(Display.FLAG_PRIVATE or Display.FLAG_PRESENTATION)
        assertNull(afterUnplug(setOf(projection)))
    }

    @Test fun `an unplug that leaves a capturable display keeps the remainder unfiltered for the resolver`() {
        val third = addDisplay()
        assertEquals(linkedSetOf(dead, third), afterUnplug(linkedSetOf(dead, third)))
    }

    @Test fun `the one-shot fan-out resolves a dead selection to the default display`() {
        select(dead)
        assertEquals(setOf(first), svc().oneShotFanoutDisplayIds())
    }
}
