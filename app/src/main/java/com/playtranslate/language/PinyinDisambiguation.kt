package com.playtranslate.language

import java.text.Normalizer

/**
 * One ambiguous (≥2 CC-CEDICT readings) Chinese token occurrence in a
 * sentence: its [surface], its candidate readings in lead order
 * ([candidates] `.first()` is the default, the reading an unhinted lookup
 * leads with), the per-hanzi HanLP pinyin at this position ([context], null
 * when it can't be resolved), and the per-hanzi pinyin HanLP gives the word
 * with nothing around it ([standalone]; null when not known, and when the
 * default is only the pack's line order, where that standing reading is the
 * better opinion and counts as context).
 */
internal data class HeteronymOccurrence(
    val surface: String,
    val candidates: List<String>,
    val context: List<String>?,
    val standalone: List<String>? = null,
)

/**
 * Picks the contextually-correct reading for a Chinese surface from its
 * CC-CEDICT candidate readings, using HanLP's phrase-aware per-hanzi pinyin
 * as the disambiguation signal.
 *
 * CC-CEDICT lists one reading per entry, so a surface with several entries
 * (东西 dōngxī "east-west" / dōngxi "thing"; 地 dì / de) exposes multiple
 * candidate readings, the default first ([ChineseEntryGroups]). The display
 * historically took the first one regardless of context. This object
 * instead scores each candidate against HanLP's contextual pinyin for the
 * same span and picks the best match, falling back to the default when
 * nothing aligns, so it is never worse than the prior behavior.
 *
 * Pure Kotlin (no HanLP / Android) so the scoring is JVM-unit-testable; the
 * engine supplies the candidate list and the context syllables.
 */
internal object PinyinDisambiguator {

    private val WHITESPACE = Regex("\\s+")
    private val COMBINING = Regex("\\p{M}+")

    /**
     * @param candidates CC-CEDICT readings for the surface — tone-marked and
     *   space-separated (e.g. "dōng xī"), in lead order (first = the default,
     *   the reading an unhinted lookup leads with).
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
            // compared, so they stay eligible only as the default fallback.
            if (syllables.size != context.size) continue
            var score = 0
            for (k in syllables.indices) {
                // Tone-exact match dominates (1000); a toneless match adds a
                // tiebreak point so same-base/different-tone candidates still
                // beat a total miss.
                if (normToned(syllables[k]) == ctxToned[k]) score += 1000
                if (normBare(syllables[k]) == ctxBare[k]) score += 1
            }
            // Strict `>` keeps the earlier candidate, the default first, on ties.
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
     * correction can NEVER make a repeated heteronym worse than the default.
     *
     * A surface is overridden ONLY when every one of its occurrences resolves
     * to the SAME contextual reading and that reading differs from the
     * default ([HeteronymOccurrence.candidates] `.first()`). If two
     * occurrences disagree (还 huán … 还 hái) or any occurrence can't be
     * resolved, the surface is suppressed entirely — all its occurrences fall
     * back to the default.
     *
     * An occurrence whose [HeteronymOccurrence.context] equals its
     * [HeteronymOccurrence.standalone] reading resolves to the default.
     * HanLP reads a sentence by longest match over its own pinyin table, so
     * a word no longer phrase covers gets the reading HanLP gives it
     * anywhere: 东西 is dōng xī and 结果 jiē guǒ in every sentence. That
     * standing reading is a second opinion on the default, a poorer one
     * than the default's own source ([ChineseReadingDefaults]), and says
     * nothing about this sentence; only a reading the surrounding text
     * changed is context.
     *
     * Per-surface, not per-occurrence: this is the single-path tradeoff — we
     * forgo correcting a genuinely split-reading surface rather than risk
     * pasting one occurrence's reading onto another.
     */
    fun resolveOverrides(occurrences: List<HeteronymOccurrence>): Map<String, String> {
        val agreed = HashMap<String, String>()
        val defaults = HashMap<String, String>()
        val disqualified = HashSet<String>()
        for (occ in occurrences) {
            if (occ.candidates.size < 2) continue
            defaults.putIfAbsent(occ.surface, occ.candidates.first())
            if (occ.surface in disqualified) continue
            val context = occ.context
            if (context == null) {                 // can't confirm agreement → suppress
                disqualified += occ.surface
                agreed -= occ.surface
                continue
            }
            val chosen = if (context == occ.standalone) occ.candidates.first() else choose(occ.candidates, context)
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
            // default the existing reading already matches.
            if (reading != defaults[surface]) out[surface] = reading
        }
        return out
    }

    /** Case/ü-normalized form for the tone-exact comparison. Delegates to the
     *  single canonical normalizer in [PinyinFormatter] so the disambiguator
     *  and the target-gloss reading match share one ü/locale policy. */
    private fun normToned(syllable: String): String =
        PinyinFormatter.canonicalReading(syllable)

    /** [normToned] with tone diacritics stripped (ǎ → a). ü collapses to u —
     *  acceptable for a coarse tiebreak. */
    private fun normBare(syllable: String): String =
        Normalizer.normalize(normToned(syllable), Normalizer.Form.NFD)
            .replace(COMBINING, "")
}
