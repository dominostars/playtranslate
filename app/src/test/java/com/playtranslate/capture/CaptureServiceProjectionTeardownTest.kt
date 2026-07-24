package com.playtranslate.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.CaptureService
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ServiceController

/**
 * Pins the projection-ownership teardown invariant: a realized
 * [MediaProjectionController] — not just a realized capture source — must see
 * its session stopped when the service dies or the user turns capture off.
 *
 * The game-audio recorder realizes the controller (and promotes held consent
 * into a live projection) without ever touching [MediaProjectionCaptureSource].
 * Before the fix these tests pin, both teardown paths gated the projection
 * release on the capture source's initialization state, so an audio-only
 * session's projection — and the system's capture indicator — survived until
 * process death.
 *
 * No real projection exists here (consent is injected via
 * [MediaProjectionController.onConsentResult]; getMediaProjection is never
 * called), so the observable is the controller's own teardown contract:
 * teardown fires the registered listeners and drops consent.
 */
@RunWith(RobolectricTestRunner::class)
class CaptureServiceProjectionTeardownTest {

    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private var service: ServiceController<CaptureService>? = null

    /** Build the service and reproduce the audio path's shape: realize the
     *  controller directly (as the recorder's lazy does) with consent held,
     *  and never touch the capture source. */
    private fun audioOnlyController(): MediaProjectionController {
        val controller = Robolectric.buildService(CaptureService::class.java).create()
        service = controller
        val mp = controller.get().mediaProjectionController
        mp.onConsentResult(Activity.RESULT_OK, Intent())
        assertTrue(mp.hasConsent)
        return mp
    }

    @After
    fun tearDown() {
        service?.destroy()
        service = null
    }

    @Test
    fun serviceDestroy_releasesControllerTheCaptureSourceNeverTouched() {
        val mp = audioOnlyController()
        var toreDown = false
        mp.addTeardownListener { toreDown = true }
        service?.destroy()
        service = null
        assertTrue(
            "onDestroy must release a consent-holding controller even when " +
                "the capture source was never initialized",
            toreDown,
        )
        assertFalse(mp.hasConsent)
    }

    @Test
    fun audioPrefDisable_releasesSessionEvenWhenRecorderNeverRan() {
        // The explicit feature-off ([CaptureService.setRecordGameAudio])
        // owns the audio-only release, and must fire it regardless of
        // recorder run state — a recorder already stopped by an earlier
        // gate (mic permission revoked, card-flow pause) must not leave
        // the session glowing the capture chip (adversarial-review
        // finding against the transition-gated variant).
        val mp = audioOnlyController()
        var toreDown = false
        mp.addTeardownListener { toreDown = true }
        CaptureService.setRecordGameAudio(ctx, false)
        assertTrue(
            "explicit audio-off must release the audio-only session " +
                "regardless of recorder run state",
            toreDown,
        )
        assertFalse(mp.hasConsent)
    }

    @Test
    fun consentGrant_survivesPrefOffReconcileTicks() {
        // The reconcile push-point fires on every seam — consent delivery
        // itself, and then the consent activity's own resume/pause. With
        // the game-audio pref off (the default) and audio never having
        // ridden this session, those ticks must not release the
        // just-granted consent out from under its real consumer: the
        // live-start stream borrow arrives exactly like this — granted,
        // not yet live. (The helper's line-48 assert already catches the
        // delivery-time tick; the explicit calls pin the lifecycle ones.)
        val mp = audioOnlyController()
        service?.get()?.reconcileGameAudio()
        service?.get()?.reconcileGameAudio()
        assertTrue(
            "a consent audio never rode must survive pref-off reconciles",
            mp.hasConsent,
        )
    }

    @Test
    fun accessibilityDeactivate_stopsBorrowedProjection() {
        val mp = audioOnlyController()
        var toreDown = false
        mp.addTeardownListener { toreDown = true }
        // The accessibility backend is active by default under Robolectric
        // (SDK 34, useMediaProjection never set), so this exercises
        // deactivate's accessibility branch — the one with no capture-source
        // teardown in front of it.
        CaptureLifecycle.deactivate(ctx)
        assertTrue(
            "Turn Off under the accessibility backend must stop a borrowed projection",
            toreDown,
        )
        assertFalse(mp.hasConsent)
    }
}
