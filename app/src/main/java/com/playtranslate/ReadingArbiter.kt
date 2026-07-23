package com.playtranslate

import kotlin.math.abs

/**
 * Which of two OCR readings of the SAME region should be canonical?
 *
 * [OverlayToolkit.isSignificantChange] answers the IDENTITY question — "are
 * these two reads the same text, modulo OCR noise?" — but every consumer
 * historically conflated that with the ARBITRATION question, defaulting to
 * a positional accident (the reconciler kept the first read forever; the
 * typewriter gate's agreement release shipped the newest). This class is
 * the arbitration: given a pair the identity check already judged
 * fuzz-same, rank the two readings by evidence of read QUALITY, so a
 * garbled read can never become permanently canonical just because of when
 * it arrived. One incorrect character produces a materially wrong
 * translation, so "close enough to be the same text" must never imply
 * "no reason to prefer one over the other".
 *
 * ## Contract
 *  - Callers pre-judge identity; this class assumes the pair IS the same
 *    on-screen text and only ranks the readings.
 *  - [Preference.TIE] means "no quality evidence either way — apply the
 *    call site's status quo". That makes wiring this in zero-regression
 *    wherever signals are absent (unknown confidence, deterministic
 *    engines re-reading identical pixels).
 *  - A challenger must win by a MARGIN. Combined with call sites that
 *    re-stamp the winner's score AND ratchet the incumbent's score on
 *    identical re-reads ([com.playtranslate.ui.TextBox.ratchetSourceConf]
 *    — the stored score is the best read observed, not the minting read's
 *    accident), this bounds flip-flops: every score change is upward, so a
 *    region hill-climbs to the best reading any single frame produces and
 *    then stays there. Both halves are load-bearing — without the ratchet,
 *    a low-confidence clean birth stays beatable by medium-confidence
 *    garble forever (adversarial-review finding).
 *
 * ## Signal ladder (substitution-shaped pairs only)
 *  1. **Containment guard** — a pair where one read merely EXTENDS the
 *     other ([OverlayToolkit.isEvolvingText]) is typewriter growth /
 *     partial re-reveal, not a quality contest: TIE. At the reconciler
 *     that deliberately leaves sub-tolerance growth to today's behavior
 *     (adopting each 2-char reveal step would churn a translation per
 *     glyph); fuller-read policy belongs to the sites that own growth.
 *  2. **OCR confidence** — compare (min over lines, then mean) with
 *     [CONF_MARGIN] each. Min first because garble is line-local: one bad
 *     line in three is exactly what the aggregate must not launder. Mean
 *     breaks min-ties so (low,hi,hi) beats (hi,lo,lo). Skipped when either
 *     side is unknown (−1 — "no signal, never low").
 *  3. **Junk score** — fraction of non-space characters that are neither
 *     source-language characters, digits, nor common punctuation
 *     ([junkRatio]). The language-generic garble signal ("Inventory
 *     ¦lem→" loses to "Inventory" whatever the confidences say); decides
 *     only past [JUNK_MARGIN]. Skipped without a source language.
 *
 * Pure Kotlin (no platform types) — JVM-tested in ReadingArbiterTest.
 */
object ReadingArbiter {

    enum class Preference { INCUMBENT, CHALLENGER, TIE }

    /** One reading: its text and its line-confidence aggregate
     *  ((min, mean) over lines; −1/−1 = unknown). */
    data class Reading(
        val text: String,
        val confMin: Float = -1f,
        val confMean: Float = -1f,
    )

    /** Aggregate a group's per-line confidences to (min, mean). Unknown
     *  (−1) the moment any line lacks a signal — a partially-scored read
     *  must not beat an unscored one on the strength of its scored half. */
    fun scoreOf(group: OcrManager.OcrGroup?): Pair<Float, Float> {
        val lines = group?.lines ?: return -1f to -1f
        if (lines.isEmpty()) return -1f to -1f
        var min = Float.MAX_VALUE
        var sum = 0f
        for (l in lines) {
            if (l.confidence < 0f) return -1f to -1f
            min = minOf(min, l.confidence)
            sum += l.confidence
        }
        return min to sum / lines.size
    }

    /**
     * Rank [incumbent] against [challenger]. [sourceLang] (a translation
     * code, e.g. "ja") enables the junk tier; null skips it.
     */
    fun prefer(incumbent: Reading, challenger: Reading, sourceLang: String?): Preference {
        if (incumbent.text == challenger.text) return Preference.TIE
        // Containment guard — growth/shrink pairs are not quality contests.
        if (OverlayToolkit.isEvolvingText(incumbent.text, challenger.text) ||
            OverlayToolkit.isEvolvingText(challenger.text, incumbent.text)
        ) return Preference.TIE

        // Tier 1: confidence, (min, mean) lexicographic with margins.
        val iKnown = incumbent.confMin >= 0f && incumbent.confMean >= 0f
        val cKnown = challenger.confMin >= 0f && challenger.confMean >= 0f
        if (iKnown && cKnown) {
            if (challenger.confMin >= incumbent.confMin + CONF_MARGIN) return Preference.CHALLENGER
            if (incumbent.confMin >= challenger.confMin + CONF_MARGIN) return Preference.INCUMBENT
            if (challenger.confMean >= incumbent.confMean + CONF_MARGIN) return Preference.CHALLENGER
            if (incumbent.confMean >= challenger.confMean + CONF_MARGIN) return Preference.INCUMBENT
        }

        // Tier 2: junk score, when a source language is known.
        if (sourceLang != null) {
            val ij = junkRatio(incumbent.text, sourceLang)
            val cj = junkRatio(challenger.text, sourceLang)
            if (ij >= cj + JUNK_MARGIN) return Preference.CHALLENGER
            if (cj >= ij + JUNK_MARGIN) return Preference.INCUMBENT
        }
        return Preference.TIE
    }

    /** Fraction of non-whitespace chars that are junk for [sourceLang]:
     *  not source-language characters, not digits, not common punctuation.
     *  0.0 for empty/blank text. */
    fun junkRatio(text: String, sourceLang: String): Float {
        var total = 0
        var junk = 0
        for (c in text) {
            if (c.isWhitespace()) continue
            total++
            if (c.isDigit()) continue
            if (c in COMMON_PUNCT) continue
            if (OcrManager.isSourceLangChar(c, sourceLang)) continue
            junk++
        }
        if (total == 0) return 0f
        return junk.toFloat() / total
    }

    /** Confidence margin a challenger (or incumbent) must clear on either
     *  aggregate before the tier decides. Calibration knob — arbitration
     *  events log both scores, so field data can tighten or loosen it. */
    const val CONF_MARGIN = 0.10f

    /** Junk-ratio separation before the junk tier decides. */
    const val JUNK_MARGIN = 0.10f

    /** Punctuation and symbols legitimate in any source language's text —
     *  never counted as junk. Includes CJK punctuation universally (stray
     *  CJK punct in Latin garble is rare; garble shows up as stray symbols
     *  and wrong letters, which stay countable). Script letters are NEVER
     *  whitelisted here — a stray kana in French text must count. */
    private val COMMON_PUNCT =
        (".,!?;:'\"()[]{}<>-–—…·%&/+*=~@#$«»“”‘’" +
            "。、．，！？：；「」『』（）〈〉《》【】・～‥").toHashSet()

    /** Compact snip for arbitration debug lines. */
    fun snip(s: String): String =
        if (s.length <= 12) s else "${s.take(7)}…${s.takeLast(4)}"
}
