package com.playtranslate.language

import java.util.Locale

/**
 * Punctuation any of our supported MT targets might use to separate a
 * list. ASCII `,` / `;` cover Latin output; `、` (U+3001 IDEOGRAPHIC
 * COMMA) is the standard list separator for Japanese / Chinese / Korean,
 * `，` (U+FF0C FULLWIDTH COMMA) appears in some Chinese MT output, and
 * `；` (U+FF1B FULLWIDTH SEMICOLON) is the rarer fullwidth alternative.
 * Without the CJK glyphs, dedupe silently no-ops on JA/ZH/KO targets
 * because there's nothing to split on — we saw this in production: the
 * Chinese character 激 → Japanese rendered as
 * "刺激する、刺激する、刺激する、刺激する" because `,` never matched.
 */
private val MT_LIST_SEPARATORS = setOf(',', ';', '、', '，', '；')

/**
 * Collapses repeats from an MT'd list line. We feed character meanings
 * (e.g. "water, fluid, liquid") through ML Kit as a single comma-separated
 * string; the translator routinely maps multiple source synonyms onto one
 * target lemma ("água, água, líquido"), and worse on JA/ZH targets where
 * the entire list collapses to one lemma repeated four times.
 *
 * Splits on any character in [MT_LIST_SEPARATORS], trims each entry,
 * drops case-insensitive duplicates while preserving first-seen order,
 * and re-joins using the FIRST separator the input used (so JA output
 * stays in `、` and Latin output stays in `, `). If the input contains
 * no separator at all the function returns it unchanged — there's
 * nothing to dedupe and faking a comma split could mangle a single
 * gloss that happens to be a multi-word phrase.
 *
 * Locale-insensitive case folding (`Locale.ROOT`) is intentional — we're
 * comparing target-language tokens as opaque strings, not user-facing
 * text. Using the device locale would risk Turkish-style I/ı edge cases
 * if the target ever gained Turkish coverage.
 */
internal fun dedupeMtCsv(line: String): String {
    val firstSeparator = line.firstOrNull { it in MT_LIST_SEPARATORS }
        ?: return line  // No separators → nothing to dedupe

    val parts = mutableListOf<String>()
    val current = StringBuilder()
    for (ch in line) {
        if (ch in MT_LIST_SEPARATORS) {
            parts.add(current.toString().trim())
            current.setLength(0)
        } else {
            current.append(ch)
        }
    }
    parts.add(current.toString().trim())

    val seen = LinkedHashMap<String, String>()
    for (entry in parts) {
        if (entry.isEmpty()) continue
        val key = entry.lowercase(Locale.ROOT)
        seen.putIfAbsent(key, entry)
    }
    if (seen.isEmpty()) return line

    // ASCII separators read better with a trailing space ("water, fluid");
    // CJK fullwidth separators are written tight ("水、火"). Codepoint check
    // gives us the right convention without enumerating each glyph.
    val joinSep = if (firstSeparator.code <= 0x7F) "$firstSeparator " else firstSeparator.toString()
    return seen.values.joinToString(joinSep)
}
