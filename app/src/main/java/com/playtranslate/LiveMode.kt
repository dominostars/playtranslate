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
 * Interface for live capture modes. Each mode owns its detection loop,
 * caching strategy, and all mutable state. CaptureService dispatches
 * to the active mode and handles service-level concerns (lifecycle,
 * hold gestures, one-shot capture).
 */
interface LiveMode {
    /** Start the mode's capture/detection loop. */
    fun start()

    /** Stop the mode, cancel all jobs, release all resources. */
    fun stop()

    /** Refresh: clear state and re-capture (e.g., user pressed Reload). */
    fun refresh()

    /** Current cached overlay state for hold-to-preview. Null if nothing cached. */
    fun getCachedState(): CachedOverlayState?
}
