package com.playtranslate.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.playtranslate.Prefs
import com.playtranslate.ui.FloatingOverlayIcon

/**
 * The game-audio recorder's permission gates as every DISPLAY surface reads
 * them: the Settings audio row, the floating menu's "Record audio" bar, the
 * floating icon's edge glyph, and the icon-placement consent prompt. One
 * definition, so the surfaces cannot disagree about what "recording" means
 * (before this object the Settings row and the menu bar each carried their
 * own copy of the predicate).
 *
 * Everything here is keyed on gate INPUTS — consent held, mic granted — never
 * on [GameAudioRecorder.running]: teardown keeps `hasConsent` truthful
 * push-style, reconcile is the retry loop for transient start failures, and
 * the deliberate card-flow pause must not flap a display (accepted tradeoff
 * from the capture-controls redesign). The recorder's own run gate adds
 * session-active and that pause on top of these; see its kdoc.
 *
 * [MediaProjectionController] is passed explicitly rather than read from
 * `CaptureService.instance`, and callers pass the IfInitialized accessor: an
 * unrealized controller holds no consent, and no surface should force-init one
 * just to render.
 */
object GameAudioGate {

    /** RECORD_AUDIO is granted. Revocable under a still-on feature pref
     *  (manual revoke, permission auto-reset, backup restore). */
    fun micGranted(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** Screen-record consent held AND the mic granted: the recorder runs
     *  whenever the capture session is active and no card flow is foreground. */
    fun armed(ctx: Context, controller: MediaProjectionController?): Boolean =
        controller?.hasConsent == true && micGranted(ctx)

    /**
     * The feature is on and consent is the ONLY missing gate — the one repair
     * an overlay surface can perform. The mic gate is part of the predicate,
     * not the action: a runtime-permission grant needs an Activity, so when
     * the mic is the missing piece an overlay offers nothing (a surface must
     * not offer a verb it cannot perform) and Settings' repair row, which CAN
     * run that flow, is the recovery path. Show-predicate of the floating
     * menu's "Record audio" bar and of the icon-placement prompt.
     */
    fun consentMissingButFixable(ctx: Context, controller: MediaProjectionController?): Boolean =
        Prefs(ctx).recordGameAudio && controller?.hasConsent != true && micGranted(ctx)

    /**
     * [consentMissingButFixable] AND the capture session is active
     * ([CaptureLifecycle.isSessionActive]): consent is the one gate left
     * between the user and a running recorder (the card-flow pause aside).
     * The two proactive asks — a fresh icon placement and the feature-on
     * toggle ([com.playtranslate.CaptureService.setRecordGameAudio]) — key on
     * this rather than on [consentMissingButFixable] alone, so neither ever
     * prompts for a session that isn't running: on the MediaProjection
     * backend a grant there would double as Turn On. (On the accessibility
     * backend an installed icon already implies an active session, so for the
     * placement prompt the two read the same.)
     */
    fun wouldRunGivenConsent(ctx: Context, controller: MediaProjectionController?): Boolean =
        consentMissingButFixable(ctx, controller) && CaptureLifecycle.isSessionActive(ctx)

    /**
     * The floating icon's edge glyph — the arrow's weight as the session's
     * state light. Outlined when a MediaProjection token the session needs is
     * missing, filled otherwise:
     *  - MediaProjection backend: the token IS the capture backend, so no
     *    consent (never granted, or lost to a status-bar-chip stop or a lock
     *    auto-stop) reads outlined whatever the audio gates say — the next
     *    capture re-prompts, and Settings' power cell says "on standby".
     *  - Accessibility backend: screen capture needs no token, so it matters
     *    only while the Anki game-audio feature is on; then outlined while not
     *    [armed] (consent OR mic missing — either way the audio the user
     *    switched on is not being captured), filled once it is. Feature off
     *    reads filled: nothing is missing.
     * User decision 2026-09-05, superseding the first cut (a plain chevron
     * outside the accessibility-plus-audio case).
     */
    fun iconGlyph(
        ctx: Context,
        controller: MediaProjectionController?,
        backend: CaptureBackend,
    ): FloatingOverlayIcon.Glyph {
        val outlined =
            if (!backend.requiresAccessibilityService) controller?.hasConsent != true
            else Prefs(ctx).recordGameAudio && !armed(ctx, controller)
        return if (outlined) FloatingOverlayIcon.Glyph.ARROW_OUTLINED
        else FloatingOverlayIcon.Glyph.ARROW_FILLED
    }
}
