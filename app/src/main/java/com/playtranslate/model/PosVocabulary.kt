package com.playtranslate.model

/**
 * Canonical part-of-speech vocabulary for the **universal grammatical
 * categories** (Noun, Verb, Adjective, …) that we localize. It does two jobs
 * from one table:
 *
 *  - [parse] turns a stored `pos` field into whole English tokens, fixing the
 *    comma-split bug: some POS values contain commas (e.g.
 *    "expressions (phrases, clauses, etc.)"), so it splits on comma and
 *    re-merges the known comma-bearing values so they survive intact. (Packs
 *    stay comma-delimited; this is the permanent fix, no pack-format change.)
 *  - [canonical] maps a whole token — whether it's the raw verbose JMdict
 *    entity name, the abbreviated form, or the lowercase Wiktionary token — to
 *    a stable [PosCode], so the render layer can localize it. Apostrophe and
 *    case variants are normalized away.
 *
 * Deliberately partial: Japanese conjugation/inflection classes (Ichidan /
 * Godan-* / Suru / Kuru, transitive/intransitive, i-/na-/pre-noun adjective)
 * and the archaic classical forms are **not** in [ALIASES] — [canonical]
 * returns null for them and the render layer falls back to the raw English
 * token (textbook-recognizable; localizing that jargon is net-negative).
 *
 * Upstream source of these strings: the JMdict DTD + `scripts/build_jmdict.py`
 * (`POS_ABBREV`), `scripts/build_target_pack.py`, and
 * `scripts/wiktionary_filters.py` (`CONTENT_POS`). When those change, the
 * coverage test (PosVocabularyTest) is the tripwire.
 */
object PosVocabulary {

    /** Universal POS we localize. Anything outside this set renders English. */
    enum class PosCode {
        NOUN, PRONOUN, VERB, ADJECTIVE, ADVERB, PARTICLE, CONJUNCTION,
        INTERJECTION, PREPOSITION, POSTPOSITION, DETERMINER, ARTICLE,
        PREFIX, SUFFIX, COUNTER, NUMERAL,
        EXPRESSION, PHRASE, PROVERB, ABBREVIATION, CONTRACTION, AUXILIARY,
    }

    /**
     * POS values that contain an internal comma. The stored `pos` field joins
     * multiple POS with ',', so [parse] must re-merge the fragments these
     * shatter into. Exact stored forms (machine-generated, stable). The last
     * two only occur if a future pack switches to the abbreviated POS_ABBREV
     * forms (today the build's quote bug keeps verb classes verbose); included
     * so they still render whole. Ordered most-fragments-first so the longest
     * match wins.
     */
    private val COMMA_TOKENS = listOf(
        "expressions (phrases, clauses, etc.)",
        "expression (phrase, clause, etc.)",
        "irregular ru verb, plain form ends with -ri",
        "Godan verb (u, irr.)",
        "Godan verb (ru, irr.)",
    ).sortedByDescending { it.count { c -> c == ',' } }

    /** Each comma-token pre-split into its trimmed comma fragments, paired with
     *  the original whole token, for the re-merge in [parse]. */
    private val COMMA_FRAGMENTS: List<Pair<List<String>, String>> =
        COMMA_TOKENS.map { tok -> tok.split(',').map { it.trim() } to tok }

    /**
     * Whole token (any known form) → canonical code. Keys are [normalize]d.
     * Each universal code lists its raw JMdict entity name(s), abbreviated
     * form, and Wiktionary token as applicable.
     */
    private val ALIASES: Map<String, PosCode> = buildMap {
        fun put(code: PosCode, vararg forms: String) {
            for (f in forms) put(normalize(f), code)
        }
        put(PosCode.NOUN, "noun", "noun (common) (futsuumeishi)")
        put(PosCode.PRONOUN, "pronoun", "pron")
        // Generic verb/adjective come from Wiktionary; JMdict's classed
        // verbs/adjectives are intentionally left to the English fallback.
        put(PosCode.VERB, "verb")
        put(PosCode.ADJECTIVE, "adj")
        put(PosCode.ADVERB, "adverb", "adverb (fukushi)", "adv")
        put(PosCode.PARTICLE, "particle")
        put(PosCode.CONJUNCTION, "conjunction", "conj")
        put(PosCode.INTERJECTION, "interjection", "interjection (kandoushi)", "intj")
        put(PosCode.PREPOSITION, "prep")
        // det / article / postp entered CONTENT_POS in the 2026-08 coverage
        // fix (en "every", fr/es possessives+articles, hi postpositions), so
        // rebuilt packs store these Wiktionary tokens.
        put(PosCode.POSTPOSITION, "postp", "postposition")
        put(PosCode.DETERMINER, "det", "determiner")
        put(PosCode.ARTICLE, "article")
        put(PosCode.PREFIX, "prefix")
        put(PosCode.SUFFIX, "suffix")
        put(PosCode.COUNTER, "counter")
        put(PosCode.NUMERAL, "numeric", "num")
        put(
            PosCode.EXPRESSION,
            "expression",
            "expression (phrase, clause, etc.)",
            "expressions (phrases, clauses, etc.)",
        )
        put(PosCode.PHRASE, "phrase", "prep_phrase")
        put(PosCode.PROVERB, "proverb")
        put(PosCode.ABBREVIATION, "abbrev")
        put(PosCode.CONTRACTION, "contraction")
        put(
            PosCode.AUXILIARY,
            "auxiliary", "auxiliary verb", "auxiliary adjective",
            "aux. verb", "aux. adjective",
        )
    }

    /** The full set of input forms the table covers — used by the coverage test. */
    val knownForms: Set<String> get() = ALIASES.keys

    /**
     * Split a stored `pos` field into whole English tokens: split on comma,
     * then re-merge any fragment run that reconstructs a known comma-bearing
     * token (see [COMMA_FRAGMENTS]) so values like
     * "expressions (phrases, clauses, etc.)" survive intact.
     */
    fun parse(raw: String): List<String> {
        val s = raw.trim()
        if (s.isEmpty()) return emptyList()
        val parts = s.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val out = ArrayList<String>(parts.size)
        var i = 0
        while (i < parts.size) {
            val merged = COMMA_FRAGMENTS.firstOrNull { (frags, _) ->
                i + frags.size <= parts.size && parts.subList(i, i + frags.size) == frags
            }
            if (merged != null) {
                out.add(merged.second)
                i += merged.first.size
            } else {
                out.add(parts[i])
                i++
            }
        }
        return out
    }

    /** Canonical code for a whole token, or null to render the raw English. */
    fun canonical(token: String): PosCode? = ALIASES[normalize(token)]

    private fun normalize(token: String): String =
        token.trim().lowercase()
            .replace('`', '\'')
            .replace('´', '\'')   // acute accent
            .replace('’', '\'')   // right single quote
}

/** True when any sense marks this entry EXPRESSION-class (JMdict exp /
 *  phrase / proverb) rather than an ordinary word or compound. The gate
 *  deciding whether a no-whitespace fused span's member words are offered:
 *  気になる [exp,v5r] yes, 図書館 [n] no — Sudachi's short units plus the
 *  re-glob fuse BOTH, and only the entry's own POS separates them. */
fun DictionaryEntry.isExpressionEntry(): Boolean = senses.any { sense ->
    sense.partsOfSpeech.any {
        when (PosVocabulary.canonical(it)) {
            PosVocabulary.PosCode.EXPRESSION,
            PosVocabulary.PosCode.PHRASE,
            PosVocabulary.PosCode.PROVERB -> true
            else -> false
        }
    }
}
