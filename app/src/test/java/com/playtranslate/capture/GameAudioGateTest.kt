package com.playtranslate.capture

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.CaptureService
import com.playtranslate.PlayTranslateAccessibilityService
import com.playtranslate.Prefs
import com.playtranslate.ui.FloatingOverlayIcon.Glyph
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.shadows.ShadowSettings

/**
 * Pins [GameAudioGate] — the one definition of "is game audio armed" that
 * the Settings audio row, the floating menu's repair bar, the floating icon's
 * glyph and the icon-placement prompt all read. Backend state is driven the
 * way production drives it (the accessibility Secure setting plus the overlay
 * permission, through [CaptureBackendResolver.reresolve]); consent is injected
 * through [MediaProjectionController.onConsentResult] as the teardown tests
 * do. The recorder is kept dormant — the capture session inactive
 * (single-screen accessibility with the icon pref off) or the mic denied — so
 * no test reaches the AudioRecord path.
 */
@RunWith(RobolectricTestRunner::class)
class GameAudioGateTest {

    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private var service: ServiceController<CaptureService>? = null
    private val a11yComponent: String =
        ComponentName(ctx, PlayTranslateAccessibilityService::class.java).flattenToString()

    private fun useAccessibilityBackend() {
        Settings.Secure.putString(
            ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, a11yComponent,
        )
        CaptureBackendResolver.reresolve(ctx)
        assertTrue(CaptureBackendResolver.active().requiresAccessibilityService)
        // Single-screen accessibility: the session is active only while the
        // icon pref is on — off keeps the recorder's run gate shut here.
        Prefs(ctx).showOverlayIcon = false
    }

    private fun useMediaProjectionBackend() {
        Settings.Secure.putString(
            ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "",
        )
        ShadowSettings.setCanDrawOverlays(true)
        CaptureBackendResolver.reresolve(ctx)
        assertFalse(CaptureBackendResolver.active().requiresAccessibilityService)
    }

    private fun setMic(granted: Boolean) {
        val app = shadowOf(ctx as Application)
        if (granted) app.grantPermissions(Manifest.permission.RECORD_AUDIO)
        else app.denyPermissions(Manifest.permission.RECORD_AUDIO)
    }

    private fun buildService(): CaptureService {
        val c = Robolectric.buildService(CaptureService::class.java).create()
        service = c
        return c.get()
    }

    private fun grantConsent(svc: CaptureService): MediaProjectionController =
        svc.mediaProjectionController.also {
            it.onConsentResult(Activity.RESULT_OK, Intent())
            assertTrue(it.hasConsent)
        }

    @After
    fun tearDown() {
        service?.destroy()
        service = null
    }

    @Test
    fun accessibility_featureOn_consentAndMic_isArmedAndFilled() {
        useAccessibilityBackend()
        setMic(true)
        val svc = buildService()
        CaptureService.setRecordGameAudio(ctx, true)
        val mp = grantConsent(svc)
        assertTrue(GameAudioGate.armed(ctx, mp))
        assertFalse(GameAudioGate.consentMissingButFixable(ctx, mp))
        assertEquals(
            Glyph.ARROW_FILLED,
            GameAudioGate.iconGlyph(ctx, mp, CaptureBackendResolver.active()),
        )
    }

    @Test
    fun accessibility_featureOn_noConsent_isOutlinedAndFixableFromAnOverlay() {
        useAccessibilityBackend()
        setMic(true)
        val svc = buildService()
        CaptureService.setRecordGameAudio(ctx, true)
        val mp = svc.mediaProjectionController
        assertFalse(mp.hasConsent)
        assertFalse(GameAudioGate.armed(ctx, mp))
        assertTrue(
            "consent is the only missing gate: the menu bar shows and placement prompts",
            GameAudioGate.consentMissingButFixable(ctx, mp),
        )
        assertEquals(
            Glyph.ARROW_OUTLINED,
            GameAudioGate.iconGlyph(ctx, mp, CaptureBackendResolver.active()),
        )
    }

    @Test
    fun accessibility_featureOn_micMissing_isOutlinedButNotFixableFromAnOverlay() {
        useAccessibilityBackend()
        setMic(false)
        val svc = buildService()
        CaptureService.setRecordGameAudio(ctx, true)
        val mp = grantConsent(svc)
        assertFalse(GameAudioGate.armed(ctx, mp))
        assertFalse(
            "an overlay cannot walk a runtime-permission flow — Settings is the repair",
            GameAudioGate.consentMissingButFixable(ctx, mp),
        )
        assertEquals(
            "the glyph still signals the unarmed state",
            Glyph.ARROW_OUTLINED,
            GameAudioGate.iconGlyph(ctx, mp, CaptureBackendResolver.active()),
        )
    }

    @Test
    fun accessibility_featureOff_isFilledWithOrWithoutConsent() {
        useAccessibilityBackend()
        setMic(true)
        val svc = buildService()
        val mp = svc.mediaProjectionController
        assertFalse(mp.hasConsent)
        assertEquals(
            "screen capture needs no token here and audio is off: nothing is missing",
            Glyph.ARROW_FILLED,
            GameAudioGate.iconGlyph(ctx, mp, CaptureBackendResolver.active()),
        )
        grantConsent(svc)
        assertFalse(GameAudioGate.consentMissingButFixable(ctx, mp))
        assertEquals(
            Glyph.ARROW_FILLED,
            GameAudioGate.iconGlyph(ctx, mp, CaptureBackendResolver.active()),
        )
    }

    @Test
    fun mediaProjection_followsConsentNotTheAudioGates() {
        useMediaProjectionBackend()
        setMic(false)
        val svc = buildService()
        CaptureService.setRecordGameAudio(ctx, true)
        val mp = svc.mediaProjectionController
        assertEquals(
            "no token on the backend that IS the token: outlined",
            Glyph.ARROW_OUTLINED,
            GameAudioGate.iconGlyph(ctx, mp, CaptureBackendResolver.active()),
        )
        grantConsent(svc)
        // Same audio inputs under accessibility read ARROW_OUTLINED (mic
        // missing): here the token decides and the audio gates do not.
        assertFalse(GameAudioGate.armed(ctx, mp))
        assertEquals(
            Glyph.ARROW_FILLED,
            GameAudioGate.iconGlyph(ctx, mp, CaptureBackendResolver.active()),
        )
    }

    @Test
    fun mediaProjection_noConsent_isOutlinedEvenWithAudioOff() {
        useMediaProjectionBackend()
        setMic(true)
        val svc = buildService()
        assertEquals(
            Glyph.ARROW_OUTLINED,
            GameAudioGate.iconGlyph(ctx, svc.mediaProjectionController, CaptureBackendResolver.active()),
        )
    }

    @Test
    fun wouldRunGivenConsent_alsoNeedsAnActiveSession() {
        useAccessibilityBackend() // icon pref off ⇒ single-screen a11y session inactive
        setMic(true)
        val svc = buildService()
        CaptureService.setRecordGameAudio(ctx, true)
        val mp = svc.mediaProjectionController
        assertTrue(GameAudioGate.consentMissingButFixable(ctx, mp))
        assertFalse(
            "session inactive: a grant would arm nothing",
            GameAudioGate.wouldRunGivenConsent(ctx, mp),
        )
        Prefs(ctx).showOverlayIcon = true
        assertTrue(GameAudioGate.wouldRunGivenConsent(ctx, mp))
    }

    @Test
    fun featureOn_withConsentTheLastGate_launchesTheConsentDialog() {
        useAccessibilityBackend()
        Prefs(ctx).showOverlayIcon = true // session active
        setMic(true)
        buildService()
        CaptureService.setRecordGameAudio(ctx, true)
        shadowOf(Looper.getMainLooper()).idle()
        val started = shadowOf(ctx as Application).nextStartedActivity
        assertNotNull("feature-on must ask for the consent audio rides on", started)
        assertEquals(MediaProjectionConsentActivity::class.java.name, started.component?.className)
    }

    @Test
    fun featureOn_sessionInactive_doesNotPrompt() {
        useAccessibilityBackend() // icon pref off ⇒ inactive
        setMic(true)
        buildService()
        CaptureService.setRecordGameAudio(ctx, true)
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(shadowOf(ctx as Application).nextStartedActivity)
    }

    @Test
    fun featureOn_micMissing_doesNotPrompt() {
        useAccessibilityBackend()
        Prefs(ctx).showOverlayIcon = true
        setMic(false)
        buildService()
        CaptureService.setRecordGameAudio(ctx, true)
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(
            "an overlay-free service ask still can't fix a missing mic; Settings owns that",
            shadowOf(ctx as Application).nextStartedActivity,
        )
    }

    @Test
    fun featureOn_noServiceAlive_coldStartsItForTheUserInitiatedAsk() {
        // The toggle runs inside an activity, so the cold start is a legal
        // foreground-service start there; the passive placement path never
        // takes this branch (it parks the prompt for CaptureService.onCreate).
        useAccessibilityBackend()
        Prefs(ctx).showOverlayIcon = true
        setMic(true)
        assertNull(CaptureService.instance)
        CaptureService.setRecordGameAudio(ctx, true)
        val started = shadowOf(ctx as Application).nextStartedService
        assertNotNull("no service alive: the ask must go out through the cold-start path", started)
        assertEquals(CaptureService.ACTION_MP_ACTIVATE, started.action)
        assertEquals(CaptureService::class.java.name, started.component?.className)
    }

    @Test
    fun unrealizedController_readsAsNoConsent() {
        useAccessibilityBackend()
        setMic(true)
        CaptureService.setRecordGameAudio(ctx, true)
        assertFalse(GameAudioGate.armed(ctx, null))
        assertTrue(GameAudioGate.consentMissingButFixable(ctx, null))
        assertEquals(
            Glyph.ARROW_OUTLINED,
            GameAudioGate.iconGlyph(ctx, null, CaptureBackendResolver.active()),
        )
    }
}
