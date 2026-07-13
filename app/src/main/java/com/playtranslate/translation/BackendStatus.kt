package com.playtranslate.translation

import android.content.Context
import androidx.annotation.StringRes

/**
 * Status snapshot rendered as a backend's secondary subtitle line in
 * the Settings → Translation Service section.
 *
 * Backends produce these via [TranslationBackend.status] and
 * [TranslationBackend.refreshStatus]; the Settings renderer styles
 * them by [Tone] and italic flag. Adding a new backend is "return one
 * of these" — no per-backend code in the Settings layer beyond a
 * row-id mapping.
 *
 * **No variant carries rendered text**, and that is load-bearing rather than
 * fastidious. [Info] holds a string resource and the arguments to format it
 * with; [Quota] and [Account] hold bare state. The renderer does all of the
 * wording, because it is the only party that holds a Context: the online
 * backends take providers, not a Context, by design. When [Info] took a
 * String, every online status line was hardcoded English and stayed English in
 * all twelve locales, and nothing in the type stopped the next one from being
 * written the same way. Now nothing else is expressible.
 */
sealed class BackendStatus {
    /** Hide the status line entirely. */
    data object Hidden : BackendStatus()

    /** A refresh is in flight. The renderer shows a generic
     *  "Checking…" italic muted line; backends don't supply text for
     *  this state. */
    data object Loading : BackendStatus()

    /** A one-line status, as the resource that words it and the arguments to
     *  format it with. The backend picks the line and the tone; the UI maps
     *  tone to color, applies italic for transients (e.g. offline/network
     *  errors), and calls [resolve]. */
    data class Info(
        @StringRes val textRes: Int,
        val formatArgs: List<Any> = emptyList(),
        val tone: Tone = Tone.Neutral,
        val italic: Boolean = false,
    ) : BackendStatus() {
        /** The line, in the user's language. A member rather than something
         *  each renderer does for itself, so the two of them — the online
         *  service cells and the offline rows' warning line — can't format the
         *  same status differently.
         *
         *  The empty case takes the arg-less overload deliberately. The vararg
         *  one runs String.format even when handed no arguments, which turns a
         *  bare "%" in an otherwise plain status line into an
         *  UnknownFormatConversionException at render time. */
        fun resolve(context: Context): String =
            if (formatArgs.isEmpty()) context.getString(textRes)
            else context.getString(textRes, *formatArgs.toTypedArray())
    }

    /** Structured quota snapshot. The renderer formats as
     *  "12,345 / 500,000 chars" plus " · resets Jun 15" when
     *  [resetEpochMs] is non-null (Pro plans only). */
    data class Quota(
        val used: Long,
        val limit: Long,
        val resetEpochMs: Long?,
    ) : BackendStatus()

    /** What the service costs to get started with — shown while it holds no
     *  key, and permanently for one that never needs a key. Reads the
     *  requirement off [ServiceType.account], so the add-service picker's
     *  subtitle and this line always agree.
     *
     *  Deliberately tone-less. The key-less state is configuration, not
     *  alarm — it was Tone.Warning historically, and it made a freshly-added
     *  service look broken. The renderer pins it Neutral, so no backend can
     *  reintroduce that by passing a tone. */
    data class Account(val requirement: AccountRequirement) : BackendStatus()
}

/** Visual tone for an [BackendStatus.Info] line and for the colored
 *  spans of the backend row's line-1 subtitle. The renderer maps:
 *  - [Neutral] → `?attr/ptTextHint`
 *  - [Warning] → `?attr/ptWarning`
 *  - [Danger]  → `?attr/ptDanger`
 *  - [Accent]  → `?attr/ptAccent`
 */
enum class Tone { Neutral, Warning, Danger, Accent }
