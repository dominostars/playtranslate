package com.playtranslate.dictionary

/**
 * Tokenizer-agnostic Japanese morphological-analysis contract. The
 * dictionary / lookup / furigana code depends on this interface rather than on
 * a specific analyzer, so the analyzer (kuromoji, Sudachi, …) is swappable.
 * [SudachiJapaneseTokenizer] is the production implementation.
 */
interface JapaneseTokenizer {
    /** Split [text] into morphemes, with [JaToken.begin]/[JaToken.end] indexing
     *  into the ORIGINAL [text]. */
    fun analyze(text: String): List<JaToken>
}

/**
 * One analyzed morpheme.
 *
 * [begin]/[end] are offsets into the original input text — verified on-device
 * that Sudachi's begin/end are original-text offsets that tile the input, so
 * furigana placement can rely on them. [reading] is katakana (as the analyzer
 * emits it), or null when absent; callers convert to hiragana where needed.
 * [dictionaryForm] and [normalizedForm] fall back to [surface] when the
 * analyzer has none (e.g. OOV).
 */
data class JaToken(
    val surface: String,
    val begin: Int,
    val end: Int,
    val category: JaCategory,
    val dictionaryForm: String,
    val normalizedForm: String,
    val reading: String?,
    val isOov: Boolean,
    /** UniDic inflection form (活用形, e.g. 連用形-一般, 語幹-一般), or null
     *  for non-conjugating morphemes / analyzers that don't report it.
     *  語幹 marks an incomplete stem awaiting a derivational continuation
     *  (良さ before そう) — see the re-glob's lemma-variant guard. */
    val inflectionForm: String? = null,
    /** UniDic 接続助詞 (て/で/が/し/から/けど…) — the particles that continue
     *  a conjugation or clause mid-stream. Drives the re-glob's
     *  conjugation-cut veto: a phrase join may not start at one (がどう) or
     *  right after one (言って|たな). False for analyzers that don't report
     *  level-2 POS — the veto then fails open to plain category checks. */
    val isConjunctiveParticle: Boolean = false,
    /** Non-linguistic material: UniDic 補助記号 (、。！？「」…) and 空白.
     *  Postpositions cannot attach across these, so a 接続助詞 right after
     *  one is expression-initial (、ていうか) — the conjugation-cut veto
     *  needs the distinction because [JaCategory.OTHER] alone conflates
     *  punctuation with prefixes and denylisted suffixes. */
    val isPunctuation: Boolean = false,
)

/**
 * Content / function-word category, mapped from an analyzer's part-of-speech.
 * Centralizes classification that was previously duplicated and IPADIC-specific
 * across [DictionaryManager] and [Deinflector].
 *
 * UniDic differences the migration must honor: na-adjectives are `形状詞` (not
 * IPADIC's `形容動詞`), pronouns are split out as `代名詞` (IPADIC lumps them
 * under `名詞`), and suffixes are promoted to their own level-1 `接尾辞`
 * (IPADIC kept them under the derived class: `名詞,接尾` / `形容詞,接尾`).
 * [fromUniDic] maps Sudachi's `partOfSpeech()[0]`; the three-argument
 * overload additionally resolves `接尾辞` by its level-2 subtype and surface.
 */
enum class JaCategory {
    NOUN, PRONOUN, VERB, ADJ_I, ADJ_NA, ADVERB, INTERJECTION, CONJUNCTION,
    PRENOMINAL, PARTICLE, AUX, OTHER;

    /** Worth a dictionary lookup / tappable. Equivalent to the old
     *  DictionaryManager.isContentWord set (名詞・動詞・形容詞・形容動詞・副詞・
     *  感動詞・接続詞・連体詞) plus pronouns (代名詞), which UniDic separates. */
    val isContent: Boolean
        get() = this == NOUN || this == PRONOUN || this == VERB || this == ADJ_I ||
            this == ADJ_NA || this == ADVERB || this == INTERJECTION ||
            this == CONJUNCTION || this == PRENOMINAL

    /** Verb / i-adjective: conjugation pulls trailing auxiliary morphemes into
     *  the surface span (see DictionaryManager.tokenizeWithSurfaces). */
    val startsConjugation: Boolean
        get() = this == VERB || this == ADJ_I

    /** Particle / auxiliary verb — the trailing morphemes folded into a
     *  conjugating word's surface span. */
    val isConjugationGlue: Boolean
        get() = this == PARTICLE || this == AUX

    companion object {
        /**
         * Map a Sudachi / UniDic POS. Suffixes (`接尾辞`) take the category of
         * the class they derive, resolved from the level-2 subtype — EXCEPT
         * the closed honorific/pluralizer lemma family, which stays [OTHER].
         *
         * The rule was designed against a full census of the core lexicon's
         * 1,987 接尾辞 rows (Sudachi DictionaryPrinter dump, 2026-08-23):
         *  - `形容詞的` (38 kana lemmas: づらい/にくい/やすい/がたい/っぽい/くさい +
         *    colloquial variants) and `動詞的` (めく/ぶる/じみる/がる/ばむ…) and
         *    `形状詞的` (だらけ/がち/げ/風/過ぎ/放し…) contain NO noise members —
         *    every lemma is a dictionary word a learner may need. Unconditional.
         *  - `名詞的` (1,134 lemmas) is content EXCEPT honorifics and
         *    pluralizers ([NOISE_SUFFIX_LEMMAS]) — grammar-transparent, and as
         *    content they would put a words-panel row on nearly every line of
         *    name-bearing dialogue. Everything else (ぶり/かた/つき/毎/counters/
         *    まみれ/ずくめ/ぐるみ/がてら…) is admitted.
         *
         * The denylist keys on NORMALIZED form, not surface: the lexicon
         * carries ~40 stretched surface variants (さ〜ん、ちゃぁん、くーん…) that
         * all normalize to さん/ちゃん/様/君 — one lemma entry covers the cloud,
         * and kanji spellings (田中様/山田君/私達) are denied consistently.
         * A kanji gate was REJECTED twice over: gating on surface kanji killed
         * meaning-bearing kana suffixes (ぶり in 五年ぶり — Codex review find),
         * and gating on normalized-form kanji still kills the self-normalizing
         * kana lemmas (つき/がてら/ぐるみ/ぽっち). Single-kana members (さ/め/げ/つ)
         * need no handling here: DictionaryManager.isLookupWorthy already
         * blocks single-hiragana forms at span emission (ら alone would leak
         * via its kanji normalized form 等 — it is denylisted).
         *
         * Anything else (unknown subtype) → [OTHER].
         */
        fun fromUniDic(majorPos: String, subPos: String, normalizedForm: String): JaCategory {
            if (majorPos != "接尾辞") return fromUniDic(majorPos)
            if (normalizedForm in NOISE_SUFFIX_LEMMAS) return OTHER
            return when (subPos) {
                "形容詞的" -> ADJ_I
                "名詞的" -> NOUN
                "形状詞的" -> ADJ_NA
                "動詞的" -> VERB
                else -> OTHER
            }
        }

        /** Grammar-transparent suffix lemmas (UniDic NORMALIZED forms): the
         *  honorifics and pluralizers, the census's only noise family. Kept
         *  non-content so they take no tap target and no words-panel row.
         *  Common whole compounds (王様/神様/私たち) are JMdict entries and
         *  still fuse via the phrase re-glob. */
        private val NOISE_SUFFIX_LEMMAS = setOf(
            "さん", "ちゃん", "たん", "やん", "ちん", "様", "君", // honorifics
            "達", "等", "共",                                   // pluralizers
        )

        /** Map a Sudachi / UniDic major POS class (`partOfSpeech()[0]`) alone.
         *  `接尾辞` maps to [OTHER] here; use the three-argument overload to
         *  resolve suffixes by subtype. */
        fun fromUniDic(majorPos: String): JaCategory = when (majorPos) {
            "名詞" -> NOUN
            "代名詞" -> PRONOUN
            "動詞" -> VERB
            "形容詞" -> ADJ_I
            "形状詞" -> ADJ_NA
            "副詞" -> ADVERB
            "感動詞" -> INTERJECTION
            "接続詞" -> CONJUNCTION
            "連体詞" -> PRENOMINAL
            "助詞" -> PARTICLE
            "助動詞" -> AUX
            else -> OTHER
        }
    }
}
