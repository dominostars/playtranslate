package com.playtranslate.translation.llm

/**
 * Format a byte count as human-readable text. Decimal (10^9) units to match
 * how app stores and OS Settings display sizes.
 *
 * Lives in the shared `llm/` package so every on-device backend's
 * ModelHelper can format catalog sizes the same way without duplicating
 * the threshold table.
 */
fun humanSize(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> "%.2f GB".format(bytes / 1e9)
    bytes >= 1_000_000L     -> "%.0f MB".format(bytes / 1e6)
    bytes >= 1_000L         -> "%d KB".format(bytes / 1_000L)
    else                    -> "$bytes B"
}
