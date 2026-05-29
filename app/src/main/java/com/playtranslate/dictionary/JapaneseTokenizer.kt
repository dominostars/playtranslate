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
)

/**
 * Content / function-word category, mapped from an analyzer's part-of-speech.
 * Centralizes classification that was previously duplicated and IPADIC-specific
 * across [DictionaryManager] and [Deinflector].
 *
 * UniDic differences the migration must honor: na-adjectives are `形状詞` (not
 * IPADIC's `形容動詞`), and pronouns are split out as `代名詞` (IPADIC lumps
 * them under `名詞`). [fromUniDic] maps Sudachi's `partOfSpeech()[0]`.
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
        /** Map a Sudachi / UniDic major POS class (`partOfSpeech()[0]`). */
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
