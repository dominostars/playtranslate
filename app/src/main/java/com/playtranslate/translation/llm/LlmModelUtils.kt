package com.playtranslate.translation.llm

import android.content.Context
import android.text.format.Formatter

/**
 * Format a byte count as human-readable text, localized to [ctx]'s locale —
 * both the number and the unit. A French user sees "1,2 Go" where an English
 * one sees "1.2 GB"; formatting only the number (the old behavior) left the
 * unit as an English literal and produced the mixed "1,23 GB".
 *
 * Delegates to [Formatter.formatShortFileSize], which uses decimal (10^9)
 * units — matching how app stores and OS Settings display sizes — and is what
 * the rest of the app (e.g. the Yomitan import screen) already uses. "Short"
 * means it rounds to ~3 significant digits: "1.2 GB", not "1.23 GB".
 *
 * Lives in the shared `llm/` package so every on-device backend's ModelHelper
 * can format catalog sizes the same way without duplicating the threshold
 * table. The [Context] is mandatory by design: there is deliberately no
 * locale-independent overload, because every size this app renders — dialogs,
 * progress rows, toasts, download-refusal reasons — is seen by a user and must
 * localize. Log-only sizes are rare enough to spell out at the call site.
 */
fun humanSize(ctx: Context, bytes: Long): String = Formatter.formatShortFileSize(ctx, bytes)
