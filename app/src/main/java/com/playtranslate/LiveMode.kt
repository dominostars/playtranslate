package com.playtranslate

import com.playtranslate.ui.TranslationOverlayView

/**
 * Cached overlay state for hold-to-preview and dedup re-show.
 * Includes both the overlay boxes and their positioning context.
 */
data class CachedOverlayState(
    val boxes: List<TranslationOverlayView.TextBox>,
    val cropLeft: Int,
    val cropTop: Int,
    val screenshotW: Int,
    val screenshotH: Int
)

/**
 * Stable identity for "what kind of live mode is this." Replaces ad-hoc
 * `is FuriganaMode` / `is InAppOnlyMode` checks scattered through
 * CaptureService — the mutator that owns liveModes uses this to decide
 * whether a per-display mode instance still matches the user's current
 * Prefs (a mismatch triggers stop+restart for that display).
 *
 * Kept separate from [OverlayMode] (the user-facing pref enum) because
 * IN_APP_ONLY isn't a user-selectable overlay: it's derived from
 * `Prefs.shouldUseInAppOnlyMode` and only applies when the user has a
 * single display selected.
 */
enum class OverlayFlavor { TRANSLATION, FURIGANA, IN_APP_ONLY }

/**
 * Interface for live capture modes. Each mode owns its detection loop,
 * caching strategy, and all mutable state. CaptureService dispatches
 * to the active mode and handles service-level concerns (lifecycle,
 * hold gestures, one-shot capture).
 */
interface LiveMode {
    /** What kind of live mode this is. Used by CaptureService's mutator
     *  to detect when an existing instance no longer matches the user's
     *  current Prefs (and thus needs a stop+restart, not just a refresh). */
    val flavor: OverlayFlavor

    /** Start the mode's capture/detection loop. */
    fun start()

    /** Stop the mode, cancel all jobs, release all resources.
     *
     *  May be called even when [start] was never called. The
     *  [CaptureService.setLiveDisplays] re-entry path can populate
     *  [CaptureService.liveModes] with new instances, then trigger
     *  [CaptureService.updateForegroundState] which can route through
     *  [CaptureService.stopLive] before those instances ever start —
     *  every instance in the map gets [stop], even ones that haven't
     *  had [start] yet. Implementations must be null-safe in resource
     *  teardown (cancel-on-null jobs, recycle-if-not-null bitmaps,
     *  etc.) so a stop-without-start doesn't crash. */
    fun stop()

    /** Refresh: clear state and re-capture (e.g., user pressed Reload). */
    fun refresh()

    /** Current cached overlay state for hold-to-preview. Null if nothing cached. */
    fun getCachedState(): CachedOverlayState?
}
