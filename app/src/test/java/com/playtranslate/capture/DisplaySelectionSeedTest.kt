package com.playtranslate.capture

import android.content.ComponentName
import android.content.Context
import android.hardware.display.DisplayManager
import android.provider.Settings
import android.view.Display
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.PlayTranslateAccessibilityService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.shadows.ShadowSettings

/**
 * Pins the two decisions of the first-launch display seed
 * ([DisplaySelectionSeed]): it may not run under the resolver's
 * pre-onboarding accessibility default (no service behind it, identity
 * shim), and under MediaProjection it stores the default display whatever
 * was detected. Backend state is driven the way production drives it: the
 * accessibility Secure setting plus the overlay permission, through
 * [CaptureBackendResolver.reresolve]. The resolver flag is process-global,
 * so each test resolves its own state first. The guess itself
 * ([DisplaySelectionSeed.detectGameDisplayId]) is pinned to the connected
 * public displays: a private display (this app's own projection display)
 * is never the game screen, and a second screen that is off still is.
 */
@RunWith(RobolectricTestRunner::class)
class DisplaySelectionSeedTest {

    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val a11yComponent: String =
        ComponentName(ctx, PlayTranslateAccessibilityService::class.java).flattenToString()

    private fun setAccessibilityEnabled(enabled: Boolean) {
        Settings.Secure.putString(
            ctx.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            if (enabled) a11yComponent else "",
        )
    }

    @Test
    fun preOnboarding_noPermissionGranted_doesNotSeed() {
        setAccessibilityEnabled(false)
        ShadowSettings.setCanDrawOverlays(false)
        CaptureBackendResolver.reresolve(ctx)
        val backend = CaptureBackendResolver.active()
        assertTrue("resolver default is the accessibility backend", backend.requiresAccessibilityService)
        assertFalse(
            "a seed taken here would store the raw detected display as if chosen",
            DisplaySelectionSeed.isBackendPermissionBacked(ctx, backend),
        )
    }

    @Test
    fun accessibilityEnabled_seedsTheDetectedDisplay() {
        setAccessibilityEnabled(true)
        CaptureBackendResolver.reresolve(ctx)
        val backend = CaptureBackendResolver.active()
        assertTrue(backend.requiresAccessibilityService)
        assertTrue(DisplaySelectionSeed.isBackendPermissionBacked(ctx, backend))
        val second = addDisplay()
        assertEquals(setOf(second), DisplaySelectionSeed.seedFor(ctx, backend, second))
    }

    @Test
    fun accessibilityEnabled_aDetectedDisplayThatIsGoneSeedsTheDefault() {
        // detectGameDisplayId never names one, but the shim is what stores.
        setAccessibilityEnabled(true)
        CaptureBackendResolver.reresolve(ctx)
        assertEquals(setOf(Display.DEFAULT_DISPLAY), DisplaySelectionSeed.seedFor(ctx, CaptureBackendResolver.active(), 7))
    }

    @Test
    fun mediaProjection_seedsDefaultDisplayWhateverWasDetected() {
        setAccessibilityEnabled(false)
        ShadowSettings.setCanDrawOverlays(true)
        CaptureBackendResolver.reresolve(ctx)
        val backend = CaptureBackendResolver.active()
        assertFalse(backend.requiresAccessibilityService)
        assertTrue(DisplaySelectionSeed.isBackendPermissionBacked(ctx, backend))
        assertEquals(setOf(Display.DEFAULT_DISPLAY), DisplaySelectionSeed.seedFor(ctx, backend, 4))
    }

    // ── The guess: which display the game is on ──────────────────────────

    private val dm: DisplayManager = ctx.getSystemService(DisplayManager::class.java)

    private fun addDisplay(): Int = ShadowDisplayManager.addDisplay("w640dp-h480dp")

    @Test
    fun detect_otherScreenOnAndPublic_isTheGameDisplay() {
        val second = addDisplay()
        assertEquals(second, DisplaySelectionSeed.detectGameDisplayId(dm, Display.DEFAULT_DISPLAY))
    }

    @Test
    fun detect_appOnTheOtherScreen_isTheFirst() {
        val second = addDisplay()
        assertEquals(Display.DEFAULT_DISPLAY, DisplaySelectionSeed.detectGameDisplayId(dm, second))
    }

    @Test
    fun detect_ownDisplayOnly_isNothing() {
        assertNull(DisplaySelectionSeed.detectGameDisplayId(dm, Display.DEFAULT_DISPLAY))
    }

    @Test
    fun detect_privateDisplay_isNotTheGameDisplay() {
        // This app's own MediaProjection VirtualDisplay as the S25 export
        // listed it: private, presentation, on. The one display the old
        // guess took from DisplayManager.getDisplays() and the picker never
        // shows (2026-10-14).
        val projection = addDisplay()
        shadowOf(dm.getDisplay(projection)).setFlags(Display.FLAG_PRIVATE or Display.FLAG_PRESENTATION)
        assertNull(DisplaySelectionSeed.detectGameDisplayId(dm, Display.DEFAULT_DISPLAY))
    }

    @Test
    fun detect_switchedOffSecondScreen_isStillTheGameDisplay() {
        // A one-time guess must not depend on the screen's state during
        // onboarding: seeding the app's own screen here would have stuck
        // until the user changed the selection (Codex adversarial review).
        val second = addDisplay()
        shadowOf(dm.getDisplay(second)).setState(Display.STATE_OFF)
        assertEquals(second, DisplaySelectionSeed.detectGameDisplayId(dm, Display.DEFAULT_DISPLAY))
    }
}
