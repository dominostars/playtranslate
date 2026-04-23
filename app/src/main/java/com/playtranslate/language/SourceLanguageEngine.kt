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
 * Outcome of [SourceLanguageEngine.preload]. Callers that care about the
 * distinction (the language-setup flow, and the MainActivity bootstrap)
 * inspect this to recover gracefully from partially-broken packs:
 *  - [Success]: every underlying resource (dict DB, tokenizer library) is
 *    warmed and ready for [tokenize]/[lookup] calls.
 *  - [PackMissing]: the expected pack files aren't on disk. Caller should
 *    route the user through the download flow.
 *  - [PackCorrupt]: the pack was present but an **on-disk integrity check**
 *    failed — dict.sqlite can't open, schema check fails, etc. Confirmed
 *    pack-level issue. Caller should uninstall + re-prompt.
 *  - [TokenizerInitFailed]: the pack is on disk, integrity checks passed,
 *    but a tokenizer library threw while warming up. Not necessarily pack
 *    corruption — could be OOM mid-deserialization, transient resource
 *    pressure, or a runtime issue unrelated to file contents. Caller
 *    should NOT auto-delete; log and let the next user interaction retry.
 *
 * The tokenize/lookup methods themselves return empty/null on the same
 * underlying failure modes, so non-preload callers don't need to switch on
 * this — they just see no results. PreloadResult exists so the explicit
 * warm-up path can route pack corruption into user-facing recovery UX
 * without punishing transient init failures with destructive deletion.
 */
sealed interface PreloadResult {
    data object Success : PreloadResult
    data object PackMissing : PreloadResult
    data class PackCorrupt(val reason: String) : PreloadResult
    data class TokenizerInitFailed(val reason: String) : PreloadResult
}

/**
 * Stateful runtime for one source language — wraps its tokenizer, dictionary,
 * and any morphology. One instance per active source language, cached in
 * [SourceLanguageEngines] for the lifetime of the process.
 */
interface SourceLanguageEngine {
    val profile: SourceLanguageProfile

    /** Open dictionary DB, warm tokenizer. Safe to call repeatedly. */
    suspend fun preload(): PreloadResult

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

    /**
     * Evicts every cached engine whose [SourceLangId.packId] equals [packId].
     * Used by pack uninstall so sibling variants that share an on-disk pack
     * (e.g. ZH and ZH_HANT) both lose their warm engine — otherwise the
     * sibling would keep serving tokenizer/dict state from the just-deleted
     * directory until the next process restart.
     */
    fun releaseForPack(packId: SourceLangId) {
        val victims = cache.keys.filter { it.packId == packId }
        for (id in victims) cache.remove(id)?.close()
    }

    private fun create(app: Context, id: SourceLangId): SourceLanguageEngine = when (id) {
        SourceLangId.JA -> JapaneseEngine(app)
        SourceLangId.ZH -> ChineseEngine(app, SourceLangId.ZH)
        SourceLangId.ZH_HANT -> ChineseEngine(app, SourceLangId.ZH_HANT)
        SourceLangId.KO -> KoreanEngine(app)
        // Everything else is Latin-script via LatinEngine.
        SourceLangId.EN, SourceLangId.ES, SourceLangId.FR, SourceLangId.DE,
        SourceLangId.IT, SourceLangId.PT, SourceLangId.NL, SourceLangId.TR,
        SourceLangId.VI, SourceLangId.ID, SourceLangId.SV, SourceLangId.DA,
        SourceLangId.NO, SourceLangId.FI, SourceLangId.HU, SourceLangId.RO,
        SourceLangId.CA -> LatinEngine(app, id)
    }
}
