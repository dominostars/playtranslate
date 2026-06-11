package com.playtranslate.dictionary

/**
 * Shared primitives for the dictionary-search prefix scan, used by every
 * source-language pack manager (JMdict, CC-CEDICT, Wiktionary). The packs all
 * share the `headword` / `reading` schema with a default-collation
 * `idx_*_text` index, so the same indexed range-scan trick works across all of
 * them — only the ranking column differs (per-row `rank_score` for JA vs.
 * joined `entry.freq_score` elsewhere).
 */

/** Added to a candidate's score when its headword/reading equals the query
 *  exactly (vs. merely being a prefix), pinning exact matches to the top of a
 *  prefix search. Far larger than any `rank_score` (millions scale) or the
 *  0-100 `freq_score`, so it dominates regardless of frequency. */
internal const val PREFIX_EXACT_BONUS = 1_000_000_000L

/**
 * The exclusive upper bound of the prefix range `[prefix, …)` — the smallest
 * string that does NOT start with [prefix] — so an indexed
 * `text >= prefix AND text < bound` scan returns exactly the rows beginning
 * with [prefix]. Increments the last Unicode code point (skipping the
 * surrogate gap). Returns null when no clean bound exists (empty input, or the
 * last code point is the maximum), signalling the caller to fall back to an
 * exact-only query.
 *
 * Relies on SQLite's default BINARY collation, whose UTF-8 byte order matches
 * Unicode code-point order — true for all our packs, whose `idx_*_text`
 * indexes are created without an explicit COLLATE.
 */
internal fun prefixUpperBound(prefix: String): String? {
    if (prefix.isEmpty()) return null
    val lastCp = prefix.codePointBefore(prefix.length)
    if (lastCp >= Character.MAX_CODE_POINT) return null
    val head = prefix.substring(0, prefix.length - Character.charCount(lastCp))
    val nextCp = (lastCp + 1).let { if (it in 0xD800..0xDFFF) 0xE000 else it }
    return head + String(Character.toChars(nextCp))
}
