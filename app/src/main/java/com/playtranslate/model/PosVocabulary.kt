package com.playtranslate.model

/**
 * Canonical part-of-speech vocabulary: the grammatical categories we show a
 * label for. It does two jobs from one table:
 *
 *  - [parse] turns a stored `pos` field into whole English tokens, fixing the
 *    comma-split bug: some POS values contain commas (e.g.
 *    "expressions (phrases, clauses, etc.)"), so it splits on comma and
 *    re-merges the known comma-bearing values so they survive intact. (Packs
 *    stay comma-delimited; this is the permanent fix, no pack-format change.)
 *  - [canonical] maps a whole token to a stable [PosCode], so the render
 *    layer can show that code's label (a string resource, so a localizable
 *    one). Apostrophe and case variants are normalized away.
 *
 * A pack stores a token in whichever form its builder left it, and the
 * packs on devices are not rebuilt when this table changes, so every form
 * is an alias here: JMdict's own description of the entity ("noun or
 * participle which takes the aux. verb suru"), the short form of the
 * builders' `POS_ABBREV` tables ("Suru verb"), and the lowercase Wiktionary
 * token. The Japanese pack mixes the first two: `POS_ABBREV` is keyed on
 * JMdict's wording, a key that no longer matches it (a backtick where
 * JMdict now writes an apostrophe, "expression" for "expressions") passes
 * the description through, and so does an entity the table never listed
 * (suru nouns, transitive and intransitive, no-adjectives). The target
 * packs' table is shorter still. Measured in the packVersion 4 ja pack:
 * 83 distinct tokens, all of them aliases here, on a fifth of whose senses
 * at least one was a description.
 *
 * The Japanese classes are grouped as a reader needs them, not one code per
 * JMdict entity: a Godan verb's ending is the last kana of the word beside
 * the label, and JMdict's 23 Nidan entities are one class. The grouping is
 * the one Jitendex draws its tags with (read out of its 2026-10-03 file),
 * but for the three auxiliaries, which were one code here before it.
 *
 * A token outside the table ([canonical] returns null) renders as stored.
 * Of JMdict's part-of-speech entities these have no alias, since no entry
 * carries them (the pack above, and Jitendex of 2026-10-03): adverbial and
 * temporal noun, proper noun, the 'kari' adjective and the Uru-class Godan
 * verb.
 *
 * Upstream source of these strings: the JMdict DTD + `scripts/build_jmdict.py`
 * (`POS_ABBREV`), `scripts/build_target_pack.py`, and
 * `scripts/wiktionary_filters.py` (`CONTENT_POS`). PosVocabularyTest pins
 * every token of the measured pack and every description of the DTD.
 */
object PosVocabulary {

    /** Every category with a label. Anything outside this set renders as
     *  stored. The second block is Japanese grammar (JMdict's classes). */
    enum class PosCode {
        NOUN, PRONOUN, VERB, ADJECTIVE, ADVERB, PARTICLE, CONJUNCTION,
        INTERJECTION, PREPOSITION, POSTPOSITION, DETERMINER, ARTICLE,
        PREFIX, SUFFIX, COUNTER, NUMERAL,
        EXPRESSION, PHRASE, PROVERB, ABBREVIATION, CONTRACTION, AUXILIARY,

        TRANSITIVE, INTRANSITIVE, COPULA, UNCLASSIFIED,
        SURU_VERB, SU_VERB, KURU_VERB, ZURU_VERB,
        ICHIDAN_VERB, ICHIDAN_VERB_SPECIAL,
        GODAN_VERB, GODAN_VERB_SPECIAL, GODAN_VERB_IRREGULAR,
        NIDAN_VERB, YODAN_VERB, NU_VERB, RI_VERB,
        I_ADJECTIVE, NA_ADJECTIVE, NO_ADJECTIVE, TARU_ADJECTIVE,
        KU_ADJECTIVE, SHIKU_ADJECTIVE, NARI_ADJECTIVE,
        PRENOMINAL, TO_ADVERB,
    }

    /**
     * POS values that contain an internal comma. The stored `pos` field joins
     * multiple POS with ',', so [parse] must re-merge the fragments these
     * shatter into. Exact stored forms (machine-generated, stable). Every
     * form with a comma that [ALIASES] has an alias for is listed, so
     * that no alias is a form [parse] cannot deliver: JMdict's four
     * descriptions that hold one, an older wording of the first, and two
     * `POS_ABBREV` short forms. No pack stores the two noun descriptions
     * today (both builders shorten them) nor the two short forms (their
     * keys spell the quote as a backtick, so the description passes
     * through). Ordered most-fragments-first so the longest match wins.
     */
    private val COMMA_TOKENS = listOf(
        "expressions (phrases, clauses, etc.)",
        "expression (phrase, clause, etc.)",
        "irregular ru verb, plain form ends with -ri",
        "noun, used as a prefix",
        "noun, used as a suffix",
        "Godan verb (u, irr.)",
        "Godan verb (ru, irr.)",
    ).sortedByDescending { it.count { c -> c == ',' } }

    /** Each comma-token pre-split into its trimmed comma fragments, paired with
     *  the original whole token, for the re-merge in [parse]. */
    private val COMMA_FRAGMENTS: List<Pair<List<String>, String>> =
        COMMA_TOKENS.map { tok -> tok.split(',').map { it.trim() } to tok }

    /**
     * Whole token (any known form) → canonical code. Keys are [normalize]d.
     * Each code lists JMdict's description(s) of its entities, the
     * `POS_ABBREV` short form(s), and the Wiktionary token as applicable.
     */
    private val ALIASES: Map<String, PosCode> = buildMap {
        fun put(code: PosCode, vararg forms: String) {
            for (f in forms) put(normalize(f), code)
        }
        put(PosCode.NOUN, "noun", "noun (common) (futsuumeishi)")
        put(PosCode.PRONOUN, "pronoun", "pron")
        // The generic verb and adjective come from Wiktionary; JMdict's
        // one generic verb is its "verb unspecified".
        put(PosCode.VERB, "verb", "verb unspecified")
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
        put(PosCode.PREFIX, "prefix", "noun, used as a prefix", "noun prefix")
        put(PosCode.SUFFIX, "suffix", "noun, used as a suffix", "noun suffix")
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

        // ── Japanese (JMdict's classes) ─────────────────────────────────
        put(PosCode.TRANSITIVE, "transitive verb")
        put(PosCode.INTRANSITIVE, "intransitive verb")
        put(PosCode.COPULA, "copula")
        put(PosCode.UNCLASSIFIED, "unclassified")
        put(
            PosCode.SURU_VERB,
            "noun or participle which takes the aux. verb suru",
            "suru verb - included", "suru verb",
            "suru verb - special class", "suru verb (special)",
        )
        put(
            PosCode.SU_VERB,
            "su verb - precursor to the modern suru",
            "suru verb - precursor to the above", "suru precursor",
        )
        put(PosCode.KURU_VERB, "kuru verb - special class", "kuru verb")
        put(
            PosCode.ZURU_VERB,
            "ichidan verb - zuru verb (alternative form of -jiru verbs)", "ichidan verb (zuru)",
        )
        put(PosCode.ICHIDAN_VERB, "ichidan verb")
        put(PosCode.ICHIDAN_VERB_SPECIAL, "ichidan verb - kureru special class", "ichidan verb (kureru)")
        for (ending in listOf("bu", "gu", "ku", "mu", "nu", "ru", "su", "tsu", "u")) {
            put(PosCode.GODAN_VERB, "godan verb with '$ending' ending", "godan verb ($ending)")
        }
        put(
            PosCode.GODAN_VERB_SPECIAL,
            "godan verb - -aru special class", "godan verb - aru special class", "godan verb (aru)",
            "godan verb - iku/yuku special class", "godan verb (iku)",
            "godan verb with 'u' ending (special class)", "godan verb (u, irr.)",
        )
        put(
            PosCode.GODAN_VERB_IRREGULAR,
            "godan verb with 'ru' ending (irregular verb)", "godan verb (ru, irr.)",
        )
        // JMdict names an upper and a lower class of Nidan verb per ending,
        // and two that fit neither pattern. A pairing JMdict lacks (an
        // upper class 'nu') is an alias nothing stores.
        for (ending in listOf("bu", "dzu", "gu", "hu/fu", "ku", "mu", "nu", "ru", "su", "tsu", "yu", "zu")) {
            put(
                PosCode.NIDAN_VERB,
                "nidan verb (upper class) with '$ending' ending (archaic)",
                "nidan verb (lower class) with '$ending' ending (archaic)",
            )
        }
        put(
            PosCode.NIDAN_VERB,
            "nidan verb with 'u' ending (archaic)",
            "nidan verb (lower class) with 'u' ending and 'we' conjugation (archaic)",
        )
        for (ending in listOf("bu", "gu", "hu/fu", "ku", "mu", "nu", "ru", "su", "tsu")) {
            put(PosCode.YODAN_VERB, "yodan verb with '$ending' ending (archaic)")
        }
        put(PosCode.NU_VERB, "irregular nu verb")
        put(PosCode.RI_VERB, "irregular ru verb, plain form ends with -ri")
        put(
            PosCode.I_ADJECTIVE,
            "adjective (keiyoushi)", "i-adjective",
            "adjective (keiyoushi) - yoi/ii class", "i-adjective (ii)",
        )
        put(PosCode.NA_ADJECTIVE, "adjectival nouns or quasi-adjectives (keiyodoshi)", "na-adjective")
        put(PosCode.NO_ADJECTIVE, "nouns which may take the genitive case particle 'no'")
        put(PosCode.TARU_ADJECTIVE, "'taru' adjective")
        put(PosCode.KU_ADJECTIVE, "'ku' adjective (archaic)")
        put(PosCode.SHIKU_ADJECTIVE, "'shiku' adjective (archaic)")
        put(PosCode.NARI_ADJECTIVE, "archaic/formal form of na-adjective")
        put(
            PosCode.PRENOMINAL,
            "noun or verb acting prenominally", "prenominal",
            "pre-noun adjectival (rentaishi)", "pre-noun adj.",
        )
        put(PosCode.TO_ADVERB, "adverb taking the 'to' particle", "adverb (to)")
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

    /** Canonical code for a whole token, or null to render the token as stored. */
    fun canonical(token: String): PosCode? = ALIASES[normalize(token)]

    private fun normalize(token: String): String =
        token.trim().lowercase()
            .replace('`', '\'')
            .replace('´', '\'')   // acute accent
            .replace('’', '\'')   // right single quote
}

/**
 * The expression-class verdict carried as [DictionaryEntry.isExpression]:
 * true when any sense's POS marks the entry EXPRESSION-class (JMdict exp /
 * phrase / proverb) rather than an ordinary word or compound. Evaluated
 * once, where the entry is built (DictionaryManager.buildEntry and the
 * Wiktionary / Chinese builders); the property's doc says why it is carried
 * instead of re-derived where it is read.
 *
 * The gate deciding whether a no-whitespace fused span's member words are
 * offered: 気になる [exp,v5r] yes, 図書館 [n] no — Sudachi's short units
 * plus the re-glob fuse BOTH, and only the entry's own POS separates them.
 * ANY sense, not the first one alone (where [kanaOnlyFrom] takes the first):
 * the class describes the join itself, so a headword listed as an expression
 * in any of its senses still owes the reader its member words.
 */
fun expressionFrom(senses: List<Sense>): Boolean = senses.any { isExpressionPos(it.partsOfSpeech) }

/** The expression-class test on ONE sense's parsed POS list — JMdict exp /
 *  phrase / proverb. Shared by [expressionFrom] (built entries) and the
 *  member re-glob's phrase exclusion, which reads raw sense rows
 *  (DictionaryManager.expressionClassForms) so the two can't drift. */
fun isExpressionPos(partsOfSpeech: List<String>): Boolean = partsOfSpeech.any {
    when (PosVocabulary.canonical(it)) {
        PosVocabulary.PosCode.EXPRESSION,
        PosVocabulary.PosCode.PHRASE,
        PosVocabulary.PosCode.PROVERB -> true
        else -> false
    }
}
