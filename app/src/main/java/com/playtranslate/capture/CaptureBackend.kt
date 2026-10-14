package com.playtranslate.capture

import android.content.Context
import com.playtranslate.OverlayUiController
import com.playtranslate.overlay.OverlayHost

/**
 * A complete screen-capture + overlay-hosting backend. The app reaches the
 * active backend through [CaptureBackendResolver]; consumers ask the backend
 * for a [captureSource] / [overlayHost] / [overlayUi] and never branch on
 * which backend it actually is.
 *
 * [supportsLiveMode] is what `CaptureService.startLive()` checks to decide
 * whether to surface an error, so live-mode callers stay oblivious to the
 * backend.
 */
interface CaptureBackend {
    /** Screen-capture source, or null while the backend isn't ready — e.g.
     *  the accessibility service hasn't connected, or MediaProjection consent
     *  hasn't been granted. Callers null-handle exactly as they did when they
     *  reached for `instance?.screenshotManager` directly. */
    val captureSource: CaptureSource?

    /** [captureSource] as a [LiveCaptureSource] when this backend can drive
     *  the continuous frame loop, else null. Live-mode drivers route capture
     *  through this instead of reaching for the accessibility service. */
    val liveCaptureSource: LiveCaptureSource?
        get() = captureSource as? LiveCaptureSource

    /** Whether [captureSource]'s `requestClean` can capture right now. The
     *  accessibility backend always can; the MediaProjection backend can only
     *  once screen-record consent is held. Capture itself never prompts —
     *  [ensureCaptureReady] is the one consent entry point — so before consent
     *  a capture fails closed. Passive callers (e.g. the Settings display-
     *  picker thumbnail) gate on this to skip a capture that would no-op. */
    val canCaptureWithoutPrompting: Boolean

    /**
     * Secure whatever this backend needs before a sustained capture session
     * can run — for MediaProjection, the screen-record consent token,
     * prompting the user when it isn't held. The accessibility backend needs
     * nothing and returns true immediately.
     *
     * `CaptureService.startLive()` awaits this up front so the consent dialog
     * resolves BEFORE the capture loop and its 1×1 outside-touch sentinel are
     * built: a dialog launched mid-loop has its Cancel tap caught by that
     * sentinel as game input, restarting the loop and re-prompting in an
     * unbreakable cycle. Returns whether capture may now proceed.
     */
    suspend fun ensureCaptureReady(): Boolean = true

    /** Overlay-window host for this backend, or null while not ready. */
    val overlayHost: OverlayHost?

    /** Game-screen overlay UI (floating icon, menu, translation overlay,
     *  region UI) for this backend, or null while not ready. */
    val overlayUi: OverlayUiController?

    /** Whether this backend can drive the continuous capture loop that live
     *  mode depends on. */
    val supportsLiveMode: Boolean

    /** Whether this backend depends on the accessibility service being
     *  connected. The MediaProjection backend does not — it watches
     *  outside-touch through its overlay host, forgoing only key-event
     *  monitoring (which would need the service). */
    val requiresAccessibilityService: Boolean

    /**
     * True iff this backend can capture [displayId] now. A capability
     * question — what the backend's *type* can reach — not a permission
     * question (see [canCaptureWithoutPrompting] for that): the accessibility
     * service can mirror any connected, non-private display
     * ([com.playtranslate.isReachableDisplay], read through [context]);
     * MediaProjection can only mirror the default display. The display's
     * power state is not part of the answer: a selected display that is off
     * is still the selection, and the capture paths pause on it.
     *
     * Call sites do the intersection with the user's selection through
     * [capturableTargets] — keeping that logic out of the backend means an
     * empty intersection (the `stopLive()` signal, or a stale selection that
     * doesn't overlap with this backend's reach) doesn't get accidentally
     * re-inflated into a non-empty set, which would silently override `stop`.
     */
    fun canCapture(context: Context, displayId: Int): Boolean

    /**
     * The display [capturableTargets] substitutes when a saved selection
     * names nothing this backend can capture: the default display, on both
     * backends. MediaProjection can mirror nothing else, so a selection that
     * doesn't overlap with it is stale by definition (typically an
     * accessibility-mode artifact). The accessibility backend had no
     * fallback until 2026-10-14, on the reasoning that it can capture any
     * display, so an empty intersection meant every display was off or
     * displaced, not stale; that held only while [canCapture] was true for
     * every id and the live callers skipped the off displays before
     * resolving. Now [canCapture] asks whether the display is connected, an
     * empty intersection means the selection names nothing that exists (a
     * disconnected second screen, a display id that died with the session
     * that created it), and the one display every device has is where the
     * icon and the capture go until it comes back.
     */
    val fallbackDisplay: Int

    /**
     * The capturable subset of [saved] — `saved.filter { canCapture(it) }` —
     * with [fallbackDisplay] substituted when that filter yields nothing. An
     * empty [saved] resolves to nothing: there is no selection to fall back
     * from, and a caller that has filtered a selection down to nothing
     * (every selected display off) must not have it re-inflated to a display
     * that was never selected; the live callers pass the selection
     * unfiltered and `CaptureService.setLiveDisplays` skips the off
     * displays after this.
     *
     * The standard "given the user's saved selection, what should we
     * actually act on?" shim. Every surface that turns
     * `Prefs.captureDisplayIds` into the displays it operates on — live
     * start, the floating icons, the one-shot fan-out, the region dropdown,
     * the region picker, the first-launch seed, the log export — goes through
     * this, so a selection the backend cannot act on collapses to its
     * fallback instead of silently no-op-ing the call site: a stale
     * accessibility-mode selection on MediaProjection (`{1}` from before
     * the backend switched), or, on the accessibility backend, a selection
     * naming a display that no longer exists (a Galaxy S25 whose stored id
     * had died with the projection that created it, 2026-10-14: the icon
     * fell back on its own while live start and the fan-out one-shot kept
     * the dead display and captured nothing). The selection itself is never
     * rewritten: when its display reconnects it is captured again.
     */
    fun capturableTargets(context: Context, saved: Set<Int>): Set<Int> {
        if (saved.isEmpty()) return emptySet()
        val filtered = saved.filterTo(linkedSetOf()) { canCapture(context, it) }
        if (filtered.isNotEmpty()) return filtered
        return setOf(fallbackDisplay)
    }

    /**
     * Watch for user interaction with the game screen on [displayId], running
     * [onGameInput] on each event. The accessibility backend reports gamepad
     * keys and outside-touch; MediaProjection reports outside-touch only.
     */
    fun startInputMonitoring(displayId: Int, onGameInput: () -> Unit)

    /** Stop the [startInputMonitoring] watch for [displayId]. */
    fun stopInputMonitoring(displayId: Int)

    /**
     * Stop every input watch across all displays — the `stopLive()` teardown
     * fan-out. The default drops all touch sentinels via [overlayHost]; the
     * accessibility backend overrides this to also clear its key-event and
     * touch tracking state.
     */
    fun stopAllInputMonitoring() {
        overlayHost?.removeAllTouchSentinels()
    }
}
