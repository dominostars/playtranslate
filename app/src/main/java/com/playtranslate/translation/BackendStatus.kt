package com.playtranslate.translation

/**
 * Status snapshot rendered as a backend's secondary subtitle line in
 * the Settings → Translation Service section.
 *
 * Backends produce these via [TranslationBackend.status] and
 * [TranslationBackend.refreshStatus]; the Settings renderer styles
 * them by [Tone] and italic flag. Adding a new backend is "return one
 * of these" — no per-backend code in the Settings layer beyond a
 * row-id mapping.
 */
sealed class BackendStatus {
    /** Hide the status line entirely. */
    data object Hidden : BackendStatus()

    /** A refresh is in flight. The renderer shows a generic
     *  "Checking…" italic muted line; backends don't supply text for
     *  this state. */
    data object Loading : BackendStatus()

    /** Free-form one-line status. The backend supplies the text and
     *  tone; the UI maps tone to color and applies italic for
     *  transients (e.g. offline/network errors). */
    data class Info(
        val text: String,
        val tone: Tone = Tone.Neutral,
        val italic: Boolean = false,
    ) : BackendStatus()

    /** Structured quota snapshot. The renderer formats as
     *  "12,345 / 500,000 chars" plus " · resets Jun 15" when
     *  [resetEpochMs] is non-null (Pro plans only). */
    data class Quota(
        val used: Long,
        val limit: Long,
        val resetEpochMs: Long?,
    ) : BackendStatus()
}

/** Visual tone for an [BackendStatus.Info] line. The renderer maps:
 *  - [Neutral] → `?attr/ptTextHint`
 *  - [Warning] → `?attr/ptWarning`
 *  - [Danger]  → `?attr/ptDanger`
 */
enum class Tone { Neutral, Warning, Danger }
