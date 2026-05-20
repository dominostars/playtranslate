package com.playtranslate.capture

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
     * True iff this backend can structurally mirror [displayId]. A capability
     * question — what the backend's *type* can reach — not a permission
     * question (see [canCaptureWithoutPrompting] for that): the accessibility
     * service can mirror any connected display; MediaProjection can only
     * mirror the default display.
     *
     * Stateless. Call sites do the intersection with the user's selection
     * themselves — keeping that logic at the call site means an empty
     * intersection (the `stopLive()` signal, or a stale selection that
     * doesn't overlap with this backend's reach) doesn't get accidentally
     * re-inflated into a non-empty set, which would silently override `stop`.
     */
    fun canCapture(displayId: Int): Boolean

    /**
     * A last-resort display this backend can always reach, used by start-path
     * callers when a saved selection produces nothing capturable.
     * MediaProjection provides the default display (the only thing it can
     * mirror, so any selection that doesn't overlap with default is stale by
     * definition — typically an accessibility-mode artifact). The
     * accessibility backend returns null: it can capture any display, so an
     * empty intersection there means every display is currently off /
     * displaced, not stale — there's nothing sensible to fall back *to*.
     */
    val fallbackDisplay: Int? get() = null

    /**
     * The capturable subset of [saved] — `saved.filter { canCapture(it) }` —
     * with [fallbackDisplay] substituted when that filter yields nothing AND
     * a fallback exists. Returns empty only when there is neither a
     * capturable display in [saved] nor a fallback (the accessibility case
     * when every saved display is currently unreachable).
     *
     * The standard "given the user's saved selection, what should we
     * actually act on?" shim. Every UI surface that turns
     * `Prefs.captureDisplayIds` into the displays it operates on — live
     * start, floating-icon placement, the region dropdown, the region
     * picker, the first-seed — goes through this so a stale accessibility-
     * mode selection on MediaProjection (e.g., `{1}` from before the
     * backend switched) collapses to the backend's reachable display
     * instead of silently no-op-ing the call site.
     */
    fun capturableTargets(saved: Set<Int>): Set<Int> {
        val filtered = saved.filterTo(linkedSetOf()) { canCapture(it) }
        if (filtered.isNotEmpty()) return filtered
        return fallbackDisplay?.let { setOf(it) } ?: emptySet()
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
