package com.playtranslate.ui

import com.playtranslate.model.FrequencyTag
import kotlin.math.roundToLong

/**
 * Pure formatters for the Yomitan-derived pitch/frequency Anki fields. No
 * Android dependencies (only [htmlEscape], itself pure) so every output shape
 * is unit-testable without Robolectric. Consumed by [AnkiCardOutputBuilder] to
 * fill [CardOutputs.pitchPosition], [CardOutputs.frequencyValues],
 * [CardOutputs.frequencyStylized], and [CardOutputs.frequencyHarmonic].
 *
 * The two list formats deliberately reproduce the *private* internals of
 * external note types, so each is comment-pinned to the source + version it
 * mirrors — if Lapis or JPMN changes its parsing/CSS the pin makes the
 * coupling greppable.
 */
internal object AnkiFrequencyFormat {

    /** ★ glyph rendering of a 0–5 pack score; mirrors
     *  [SentenceAnkiHtmlBuilder.starsString] but inlined to keep this object
     *  self-contained and Android-free. Empty when [freqScore] <= 0. */
    private fun stars(freqScore: Int): String =
        if (freqScore > 0) "★".repeat(freqScore) else ""

    /**
     * Harmonic mean of the positive numeric frequency values, rounded, or
     * `null` when there are none. Fills the frequency-sort field (Lapis
     * `FreqSort` / JPMN `FrequencySort`), matching Yomitan's
     * `{frequency-harmonic-rank}` marker.
     *
     * ASSUMES the Yomitan rank convention: each value is a rank where LOWER =
     * more frequent (1 = most common). That is what every sort-purposed
     * Yomitan frequency dictionary uses (JPDB, CC100, BCCWJ, VN, …) and what
     * the `…-harmonic-rank` marker assumes. A few dictionaries instead ship
     * raw occurrence counts (higher = more frequent, e.g. Innocent Corpus);
     * the term-meta v3 schema carries no rank-vs-count flag, so we cannot
     * distinguish them, and a count-based value inflates the rank (or, for a
     * count-only word, inverts the order) — exactly as it does in Yomitan
     * itself, where such dicts aren't used for sorting either. We follow the
     * dominant convention rather than guess a direction the data doesn't carry.
     *
     * Only positive values participate — a 0/negative would be a div-by-zero
     * or a nonsense rank. The 0–5 ★ score is deliberately NOT folded in: a
     * harmonic mean is dominated by its smallest term, so a single-digit
     * bucket among thousands-scale ranks would collapse the sort toward a
     * constant. One value per dictionary feeds this (see
     * `YomitanDataStore.frequencyFor`).
     */
    fun harmonicMean(values: List<Double>): Long? {
        val positive = values.filter { it > 0.0 }
        if (positive.isEmpty()) return null
        return (positive.size / positive.sumOf { 1.0 / it }).roundToLong()
    }

    /** Pitch downsteps as a comma-separated list (e.g. `0,2`), the
     *  AJT/Yomitan `{pitch-accent-positions}` form that both Lapis's
     *  `PitchPosition` and JPMN's `PAOverride` accept. Empty when no pitch. */
    fun pitchPositions(pitch: List<Int>): String = pitch.joinToString(",")

    /**
     * Lapis-style frequency list: a `<ul>` whose `<li>`s are the ★ rating
     * (when freqScore > 0) then `source: display` per dictionary. Emitted as a
     * pre-built `<ul>` — NOT comma-separated text — so a `display` carrying a
     * thousands separator can't corrupt Lapis's `formatFrequencyList`
     * split-on-`,`; Lapis renders an existing `<ul>` verbatim (its early-return
     * guard). Reproduces the `<ul><li>` shape Yomitan's `{frequencies}` marker
     * emits. Pinned to donkuri/lapis `src/back.html` @ v1.7.0. Empty string
     * when nothing to show, so the field stays blank.
     */
    fun frequencyValuesHtml(freqScore: Int, freqs: List<FrequencyTag>): String {
        val items = buildList {
            stars(freqScore).takeIf { it.isNotEmpty() }?.let { add(it) }
            freqs.forEach { add("${htmlEscape(it.source)}: ${htmlEscape(it.display)}") }
        }
        if (items.isEmpty()) return ""
        return items.joinToString("", prefix = "<ul>", postfix = "</ul>") { "<li>$it</li>" }
    }

    /**
     * PlayTranslate default-model (v002+) frequency row: the ★ rating in a
     * `.gl-stars` span, then one `.gl-chip` span per dictionary
     * (`source: display`, the same text [BadgeChips.freqChip] renders in
     * the lens). The word template's `.pt-meta` flex row owns layout,
     * gaps, and the secondary text colour — this emits only the chips.
     * Class-styled, so ONLY the default PT model (whose CSS defines the
     * classes) may consume it; the structured path keeps
     * [frequencyValuesHtml], whose `<ul>` shape is pinned to Lapis.
     * Empty string when nothing to show, so the field stays blank.
     */
    fun frequencyChipsHtml(freqScore: Int, freqs: List<FrequencyTag>): String {
        val sb = StringBuilder()
        stars(freqScore).takeIf { it.isNotEmpty() }?.let {
            sb.append("<span class=\"gl-stars\">").append(it).append("</span>")
        }
        freqs.forEach {
            sb.append("<span class=\"gl-chip\">")
                .append(htmlEscape("${it.source}: ${it.display}"))
                .append("</span>")
        }
        return sb.toString()
    }

    /**
     * JPMN-stylized frequency list: the note's own `frequencies__group`
     * structure, which JPMN's CSS targets without JS. A plain list or
     * Yomitan's `{frequencies}` markup displays incorrectly here (per the JPMN
     * importing docs), so we reproduce the exact structure — one group per
     * dictionary; `data-details` and the dictionary span both carry the source
     * name, the number span carries the display value. The ★ rating leads as a
     * dictionary-less group for parity with the Lapis list (drop the
     * `stars(...)` line if it reads oddly on real cards). Pinned to
     * Aquafina/arbyste jp-mining-note `yomichan_templates/bottom.txt` @
     * 0.11.0.6. Empty when nothing to show.
     */
    fun frequenciesStylizedJpmn(freqScore: Int, freqs: List<FrequencyTag>): String {
        val groups = buildList {
            stars(freqScore).takeIf { it.isNotEmpty() }?.let { add(group(number = it, dictionary = "")) }
            freqs.forEach { add(group(number = htmlEscape(it.display), dictionary = htmlEscape(it.source))) }
        }
        return groups.joinToString("")
    }

    private fun group(number: String, dictionary: String): String =
        "<div class=\"frequencies__group\" data-details=\"$dictionary\">" +
            "<div class=\"frequencies__number\">" +
            "<span class=\"frequencies__number-inner\">$number</span></div>" +
            "<div class=\"frequencies__dictionary\">" +
            "<span class=\"frequencies__dictionary-inner\">$dictionary</span></div>" +
            "</div>"
}
