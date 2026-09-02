package com.playtranslate.capture

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.Display
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.PlayTranslateAccessibilityService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowSettings

/**
 * Pins the two decisions of the first-launch display seed
 * ([DisplaySelectionSeed]): it may not run under the resolver's
 * pre-onboarding accessibility default (no service behind it, identity
 * shim), and under MediaProjection it stores the default display whatever
 * was detected. Backend state is driven the way production drives it: the
 * accessibility Secure setting plus the overlay permission, through
 * [CaptureBackendResolver.reresolve]. The resolver flag is process-global,
 * so each test resolves its own state first.
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
        assertEquals(setOf(4), DisplaySelectionSeed.seedFor(backend, 4))
    }

    @Test
    fun mediaProjection_seedsDefaultDisplayWhateverWasDetected() {
        setAccessibilityEnabled(false)
        ShadowSettings.setCanDrawOverlays(true)
        CaptureBackendResolver.reresolve(ctx)
        val backend = CaptureBackendResolver.active()
        assertFalse(backend.requiresAccessibilityService)
        assertTrue(DisplaySelectionSeed.isBackendPermissionBacked(ctx, backend))
        assertEquals(setOf(Display.DEFAULT_DISPLAY), DisplaySelectionSeed.seedFor(backend, 4))
    }
}
