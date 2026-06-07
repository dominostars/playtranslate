package com.playtranslate.language

import java.text.Normalizer

/**
 * One ambiguous (≥2 CC-CEDICT readings) Chinese token occurrence in a
 * sentence: its [surface], its candidate readings in frequency order
 * ([candidates] `.first()` is the frequency default), and the per-hanzi HanLP
 * context pinyin ([context], null when it can't be resolved for this position).
 */
internal data class HeteronymOccurrence(
    val surface: String,
    val candidates: List<String>,
    val context: List<String>?,
)

/**
 * Picks the contextually-correct reading for a Chinese surface from its
 * CC-CEDICT candidate readings, using HanLP's phrase-aware per-hanzi pinyin
 * as the disambiguation signal.
 *
 * CC-CEDICT lists one reading per entry, so a surface with several entries
 * (东西 dōngxī "east-west" / dōngxi "thing"; 地 dì / de) exposes multiple
 * candidate readings ordered by frequency. The display historically took the
 * highest-frequency one regardless of context. This object instead scores
 * each candidate against HanLP's contextual pinyin for the same span and
 * picks the best match, falling back to frequency-first when nothing aligns —
 * so it is never worse than the prior behavior.
 *
 * Pure Kotlin (no HanLP / Android) so the scoring is JVM-unit-testable; the
 * engine supplies the candidate list and the context syllables.
 */
internal object PinyinDisambiguator {

    private val WHITESPACE = Regex("\\s+")
    private val COMBINING = Regex("\\p{M}+")

    /**
     * @param candidates CC-CEDICT readings for the surface — tone-marked and
     *   space-separated (e.g. "dōng xī"), in frequency order (first = most
     *   frequent, i.e. the prior default).
     * @param context HanLP's per-hanzi tone-marked pinyin for this span, one
     *   syllable per hanzi (e.g. ["dōng", "xi"]).
     * @return the chosen reading, returned verbatim from [candidates].
     */
    fun choose(candidates: List<String>, context: List<String>): String {
        if (candidates.size <= 1) return candidates.firstOrNull().orEmpty()
        if (context.isEmpty()) return candidates.first()

        val ctxToned = context.map(::normToned)
        val ctxBare = context.map(::normBare)
        var best = candidates.first()
        var bestScore = 0
        for (cand in candidates) {
            val syllables = cand.trim().split(WHITESPACE)
            // Only candidates that align 1:1 with the hanzi can be scored
            // per-position; others (érhuà, irregular entries) can't be
            // compared, so they stay eligible only as the freq-first fallback.
            if (syllables.size != context.size) continue
            var score = 0
            for (k in syllables.indices) {
                // Tone-exact match dominates (1000); a toneless match adds a
                // tiebreak point so same-base/different-tone candidates still
                // beat a total miss.
                if (normToned(syllables[k]) == ctxToned[k]) score += 1000
                if (normBare(syllables[k]) == ctxBare[k]) score += 1
            }
            // Strict `>` keeps the earlier (higher-frequency) candidate on ties.
            if (score > bestScore) {
                bestScore = score
                best = cand
            }
        }
        return if (bestScore > 0) best else candidates.first()
    }

    /**
     * Builds the `{surface → corrected reading}` override map from every
     * ambiguous occurrence in a sentence, with a conflict/duplicate guard so a
     * correction can NEVER make a repeated heteronym worse than the frequency
     * default.
     *
     * A surface is overridden ONLY when every one of its occurrences resolves
     * to the SAME contextual reading and that reading differs from the
     * frequency default ([HeteronymOccurrence.candidates] `.first()`). If two
     * occurrences disagree (还 huán … 还 hái) or any occurrence can't be
     * resolved, the surface is suppressed entirely — all its occurrences fall
     * back to the frequency default, i.e. exactly pre-feature behavior.
     *
     * Per-surface, not per-occurrence: this is the single-path tradeoff — we
     * forgo correcting a genuinely split-reading surface rather than risk
     * pasting one occurrence's reading onto another.
     */
    fun resolveOverrides(occurrences: List<HeteronymOccurrence>): Map<String, String> {
        val agreed = HashMap<String, String>()
        val freqDefault = HashMap<String, String>()
        val disqualified = HashSet<String>()
        for (occ in occurrences) {
            if (occ.candidates.size < 2) continue
            freqDefault.putIfAbsent(occ.surface, occ.candidates.first())
            if (occ.surface in disqualified) continue
            val context = occ.context
            if (context == null) {                 // can't confirm agreement → suppress
                disqualified += occ.surface
                agreed -= occ.surface
                continue
            }
            val chosen = choose(occ.candidates, context)
            val prev = agreed[occ.surface]
            if (prev == null) {
                agreed[occ.surface] = chosen
            } else if (prev != chosen) {           // occurrences disagree → suppress
                disqualified += occ.surface
                agreed -= occ.surface
            }
        }
        val out = LinkedHashMap<String, String>()
        for ((surface, reading) in agreed) {
            // Emit only genuine corrections; if context agreed with the
            // frequency default the existing reading already matches.
            if (reading != freqDefault[surface]) out[surface] = reading
        }
        return out
    }

    /** Lowercase + v/u: → ü so CC-CEDICT and HanLP spellings compare equal. */
    private fun normToned(syllable: String): String =
        syllable.lowercase().replace("u:", "ü").replace('v', 'ü')

    /** [normToned] with tone diacritics stripped (ǎ → a). ü collapses to u —
     *  acceptable for a coarse tiebreak. */
    private fun normBare(syllable: String): String =
        Normalizer.normalize(normToned(syllable), Normalizer.Form.NFD)
            .replace(COMBINING, "")
}
