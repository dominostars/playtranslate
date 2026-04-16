package com.playtranslate.language

import android.content.Context
import com.playtranslate.model.DictionaryResponse
import com.playtranslate.model.KanjiDetail
import java.util.concurrent.ConcurrentHashMap

/**
 * A lookup-worthy token from OCR'd source text.
 * - [surface]: as it appears in the source text (e.g. "使わない")
 * - [lookupForm]: the dictionary base form (e.g. "使う"). Equal to [surface]
 *   for languages that don't conjugate (Chinese) or where lemmatization is
 *   trivial.
 * - [reading]: a pronunciation hint (hiragana for Japanese). Null for most
 *   languages.
 */
data class TokenSpan(
    val surface: String,
    val lookupForm: String,
    val reading: String? = null,
)

/**
 * A hint-text annotation positioned over a range of the source text (e.g.
 * furigana over kanji). Phase 1 only produces these from [JapaneseEngine].
 */
data class HintTextAnnotation(
    val baseStart: Int,
    val baseEnd: Int,
    val hintText: String,
)

/**
 * Character-level dictionary lookup result. Phase 1 aliases to [KanjiDetail]
 * because JA is the only engine with character detail support. When Phase 4
 * (Chinese) adds per-character detail, this becomes a sealed interface with
 * `JapaneseKanji` and `ChineseHanzi` variants.
 */
typealias CharacterDetail = KanjiDetail

/**
 * Stateful runtime for one source language — wraps its tokenizer, dictionary,
 * and any morphology. One instance per active source language, cached in
 * [SourceLanguageEngines] for the lifetime of the process.
 */
interface SourceLanguageEngine {
    val profile: SourceLanguageProfile

    /** Open dictionary DB, warm tokenizer. Safe to call repeatedly. */
    suspend fun preload()

    /** Split text into dictionary-worthy tokens. */
    suspend fun tokenize(text: String): List<TokenSpan>

    /** Full dictionary lookup. [reading] is a narrowing hint (JA hiragana). */
    suspend fun lookup(word: String, reading: String? = null): DictionaryResponse?

    /** Character-level lookup. Only JA returns non-null in Phase 1. */
    suspend fun lookupCharacter(literal: Char): CharacterDetail? = null

    /** Hint-text annotations. Only JA returns non-empty (furigana) in Phase 1. */
    fun annotateForHintText(text: String): List<HintTextAnnotation> = emptyList()

    fun close()
}

/**
 * Process-scoped engine cache. Enforces application-context at the boundary so
 * callers can't accidentally leak an Activity through an engine reference.
 */
object SourceLanguageEngines {
    private val cache = ConcurrentHashMap<SourceLangId, SourceLanguageEngine>()

    fun get(ctx: Context, id: SourceLangId): SourceLanguageEngine {
        val app = ctx.applicationContext
        return cache.getOrPut(id) { create(app, id) }
    }

    /** Eviction hook used by later phases when the user switches source language. */
    fun release(id: SourceLangId) {
        cache.remove(id)?.close()
    }

    private fun create(app: Context, id: SourceLangId): SourceLanguageEngine = when (id) {
        SourceLangId.JA -> JapaneseEngine(app)
        SourceLangId.EN -> LatinEngine(app)
    }
}
