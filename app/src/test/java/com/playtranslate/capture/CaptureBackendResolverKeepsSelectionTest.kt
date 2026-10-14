package com.playtranslate.capture

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.Display
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.PlayTranslateAccessibilityService
import com.playtranslate.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowSettings

/**
 * Pins that a backend swap never rewrites [Prefs.captureDisplayIds].
 * MediaProjection collapses a non-default selection at READ time only
 * ([CaptureBackend.capturableTargets]), and so does accessibility for a
 * display that is not connected (2026-10-14); the selection itself survives
 * a round trip through MediaProjection and comes back intact under
 * accessibility. This matters because an OEM switching the accessibility
 * service off arrives as exactly this swap, with some frequency on some
 * devices, and must not touch a display the user picked. A write-at-swap
 * variant was built and pulled on 2026-09-02 for that reason.
 */
@RunWith(RobolectricTestRunner::class)
class CaptureBackendResolverKeepsSelectionTest {

    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val prefs: Prefs get() = Prefs(ctx)
    private val a11yComponent: String =
        ComponentName(ctx, PlayTranslateAccessibilityService::class.java).flattenToString()

    private fun resolveIntoAccessibility() {
        Settings.Secure.putString(
            ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, a11yComponent,
        )
        CaptureBackendResolver.reresolve(ctx)
        assertTrue(CaptureBackendResolver.active().requiresAccessibilityService)
    }

    private fun resolveIntoMediaProjection() {
        Settings.Secure.putString(
            ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "",
        )
        ShadowSettings.setCanDrawOverlays(true)
        CaptureBackendResolver.reresolve(ctx)
        assertFalse(CaptureBackendResolver.active().requiresAccessibilityService)
    }

    @Test
    fun pickedSecondScreen_survivesMediaProjectionRoundTrip() {
        resolveIntoAccessibility()
        prefs.captureDisplayIds = setOf(4)
        resolveIntoMediaProjection()
        assertEquals("stored selection untouched while on MediaProjection", setOf(4), prefs.captureDisplayIds)
        assertEquals(
            "read-time collapse is what MediaProjection acts on",
            setOf(Display.DEFAULT_DISPLAY),
            CaptureBackendResolver.active().capturableTargets(ctx, prefs.captureDisplayIds),
        )
        resolveIntoAccessibility()
        assertEquals(setOf(4), prefs.captureDisplayIds)
        assertEquals(
            "accessibility collapses at read time too while display 4 is away (2026-10-14), and rewrites nothing",
            setOf(Display.DEFAULT_DISPLAY),
            CaptureBackendResolver.active().capturableTargets(ctx, prefs.captureDisplayIds),
        )
        assertEquals(setOf(4), prefs.captureDisplayIds)
    }

    @Test
    fun pickedMultiDisplay_survivesMediaProjectionRoundTrip() {
        resolveIntoAccessibility()
        prefs.captureDisplayIds = linkedSetOf(4, Display.DEFAULT_DISPLAY)
        resolveIntoMediaProjection()
        assertEquals(linkedSetOf(4, Display.DEFAULT_DISPLAY), prefs.captureDisplayIds)
        resolveIntoAccessibility()
        assertEquals(linkedSetOf(4, Display.DEFAULT_DISPLAY), prefs.captureDisplayIds)
    }
}
