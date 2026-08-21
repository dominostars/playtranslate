package com.playtranslate.language

import android.content.Context
import com.playtranslate.model.CharacterDetail
import com.playtranslate.model.DictionaryResponse
import java.util.concurrent.ConcurrentHashMap

/**
 * A lookup-worthy token from OCR'd source text.
 * - [surface]: as it appears in the source text (e.g. "使わない")
 * - [lookupForm]: the dictionary base form (e.g. "使う"). Equal to [surface]
 *   for languages that don't conjugate (Chinese) or where lemmatization is
 *   trivial.
 * - [reading]: a pronunciation hint (hiragana for Japanese). Null for most
 *   languages.
 * - [inflections]: the conjugation the [surface] expresses (Japanese only;
 *   e.g. 言わせて → [Causative, Te-form]). Empty for uninflected words and for
 *   engines that don't analyze inflection.
 */
data class TokenSpan(
    val surface: String,
    val lookupForm: String,
    val reading: String? = null,
    val inflections: List<InflectionTag> = emptyList(),
)

/**
 * One multi-word dictionary expression located in a text
 * ([SourceLanguageEngine.phrasesIn]): [range] is its char range there,
 * [surface] the verbatim slice at that range, [lookupForm] the
 * space-joined, engine-normalized key ready for
 * [SourceLanguageEngine.lookup].
 */
data class PhraseOccurrence(
    val range: IntRange,
    val surface: String,
    val lookupForm: String,
)

/**
 * A hint-text annotation positioned over a range of the source text (e.g.
 * furigana over kanji). Phase 1 only produces these from [JapaneseEngine].
 */
data class HintTextAnnotation(
    val baseStart: Int,
    val baseEnd: Int,
    val hintText: String,
    /** Pitch-accent downstep for the WORD this annotation covers — set only
     *  when the annotation spans the whole, uninflected word (partial ruby
     *  can't carry a word-level contour; lemma pitch on inflected forms
     *  would be wrong). Renderers without pitch support ignore it. */
    val pitchDownstep: Int? = null,
)

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

    /**
     * Ranked prefix-completion candidates for a partial [query] — the
     * dictionary-search path used by the standalone lookup screen when the
     * user has typed a single (possibly unfinished) word. Each result is a
     * candidate [TokenSpan] whose [TokenSpan.lookupForm] (+ optional
     * [TokenSpan.reading]) feeds straight back into [lookup] to materialize
     * the full entry, so the search layer never duplicates entry-building.
     *
     * Ranking contract: an exact match on [query] sorts above pure-prefix
     * matches; within each bucket, more frequent entries come first. Results
     * are capped at [limit].
     *
     * Defaulted to empty so engines opt in; an engine without a prefix-search
     * implementation simply returns no completions (the screen still works in
     * its segmentation mode).
     */
    suspend fun searchPrefix(query: String, limit: Int = 20): List<TokenSpan> = emptyList()

    /** Full dictionary lookup. [reading] is a narrowing hint (JA hiragana). */
    suspend fun lookup(word: String, reading: String? = null): DictionaryResponse?

    /**
     * The longest multi-word dictionary expression CONTAINING the word that
     * contains [offset] in [text], returned as a lookup key for [lookup] —
     * null when no known expression spans that word. Word-tap surfaces call
     * this so a tap on ANY member word — "great" OR "deal" in "a great
     * deal", "door" in "open the door" — surfaces the expression alongside
     * the word itself (the popup shows both; the tap target stays the
     * single word).
     *
     * Defaulted to null: engines whose segmentation already resolves
     * multi-token units (JA re-glob, ZH/TH dictionary segmentation) have
     * nothing to add at tap time. [LatinEngine] overrides it for the
     * space-delimited languages, whose one-token-per-word [tokenize] can't
     * reach the packs' multi-word headwords.
     */
    suspend fun longestPhraseAt(text: String, offset: Int): String? = null

    /**
     * Every known multi-word dictionary expression in [text], as
     * non-overlapping occurrences in text order — the sentence-level
     * counterpart of [longestPhraseAt], used to surface idiomatic phrases
     * as their own rows in the words list (and, through it, the Anki
     * sentence card). Overlaps resolve by longest-first claiming with a
     * leftmost tie-break — the same pinned no-data-signal ordering
     * [longestPhraseAt] uses.
     *
     * Defaulted to empty: engines whose segmentation already emits
     * multi-word units as single tokens (JA re-glob, ZH/TH dictionary
     * segmentation) have nothing to add. [LatinEngine] overrides it for
     * the space-delimited languages.
     */
    suspend fun phrasesIn(text: String): List<PhraseOccurrence> = emptyList()

    /**
     * The member words of the multi-word unit [headword] — each a
     * [TokenSpan] whose lookupForm feeds the shared row/section pipelines.
     * Drives BOTH member surfaces: the popup's member sections under a
     * fused unit, and the detail page's Words section. Empty when
     * [headword] isn't a multi-member unit or no member qualifies.
     *
     * [expressionClass] is the caller's POS verdict on the entry
     * ([com.playtranslate.model.isExpressionEntry]; spaced headwords count
     * as expressions by form). Expressions get the loose per-member gate
     * (気になる → 気 — one char, load-bearing). Non-expression fused
     * entries — transparent compounds like 放送番組 and 国内向け — get
     * members only when EVERY unit is a ≥2-char kanji-bearing word:
     * partial decompositions mislead (図書館 → 図書 alone implies 館 is
     * nothing, and single characters are the kanji-breakdown section's
     * job), so one disqualified unit turns the whole offer off.
     *
     * Callers must pass the DISPLAYED headword form: for JA `uk` entries
     * the kanji variant (かも知れない) would let 知れ through the
     * kanji-member gate the displayed kana form (かもしれない) correctly
     * fails. Default: whitespace split, the space-delimited languages'
     * behavior ([whitespaceMemberWords]), which ignores [expressionClass];
     * [JapaneseEngine] overrides with a member-level re-glob (members come
     * out at dictionary-word granularity, kanji-bearing only).
     */
    suspend fun memberWordsOf(
        headword: String,
        expressionClass: Boolean = true,
    ): List<TokenSpan> = whitespaceMemberWords(headword)

    /** Character-level lookup. JA returns [com.playtranslate.model.KanjiDetail];
     *  ZH returns [com.playtranslate.model.HanziDetail]. Other engines return null.
     *
     *  [targetLang] selects which language's meanings to return when the pack
     *  carries multiple (KANJIDIC2 ships en/fr/es/pt). Implementations fall
     *  back to English when the requested language isn't available and set
     *  [com.playtranslate.model.CharacterDetail.meaningsLang] to what they
     *  actually returned, so the caller can decide whether to machine-translate.
     */
    suspend fun lookupCharacter(literal: Char, targetLang: String = "en"): CharacterDetail? = null

    /**
     * The single authoritative analysis of [text] — the source every
     * reading-bearing surface projects from (see [SentenceAnnotation] and
     * docs/sentence-annotation-refactor.md). FULL-tier engines (JA, ZH)
     * override with dictionary-resolved, text-tiled annotations; this
     * default is the LEXICAL/PLAIN tier: spans from [tokenize] positioned
     * in [text] by a forward-cursor search (every engine's tokenizer emits
     * verbatim slices in text order, so the walk is exact, and duplicate
     * surfaces resolve to successive occurrences). Offsets matter here: the
     * drag lens hit-tests lexical spans by [AnnotatedSpan.start] and drops
     * offsetless ones. Spans need not tile — whitespace/punctuation gaps
     * stay uncovered — and a surface the search can't locate keeps the
     * offsetless sentinel without derailing the spans after it. One plain
     * span when tokenization yields nothing.
     */
    suspend fun annotate(
        text: String,
        depth: AnnotationDepth = AnnotationDepth.FULL,
    ): SentenceAnnotation {
        if (text.isEmpty()) return SentenceAnnotation(text, profile.id, 0, emptyList())
        var pos = 0
        val spans = tokenize(text).map {
            val at = text.indexOf(it.surface, pos)
            if (at >= 0) pos = at + it.surface.length
            AnnotatedSpan(
                start = at, end = if (at < 0) -1 else at + it.surface.length,
                surface = it.surface,
                lookupForm = it.lookupForm, reading = it.reading,
                inflections = it.inflections,
            )
        }
        return if (spans.isEmpty()) SentenceAnnotation.plain(text, profile.id)
        else SentenceAnnotation(text, profile.id, 0, spans)
    }

    /** Hint-text annotations (JA furigana / ZH pinyin). `suspend` like the other
     *  tokenizer-backed calls: implementations tokenize off the main thread, so
     *  callers must invoke it from a coroutine rather than blocking the UI. */
    suspend fun annotateForHintText(text: String): List<HintTextAnnotation> = emptyList()

    /** The text to feed a TTS engine to speak [text] aloud. Default returns
     *  [text] unchanged. Japanese overrides it with a kana rendering so the
     *  system engine doesn't re-guess compound readings (初夏 → はつか). Stays
     *  identity for languages whose "reading" isn't speakable kana — Chinese
     *  readings are pinyin, Korean/Latin surfaces are already phonetic. */
    suspend fun spokenForm(text: String): String = text

    fun close()
}

private val MEMBER_SPLIT_WHITESPACE = Regex("\\s+")

/** [SourceLanguageEngine.memberWordsOf]'s default: whitespace split of a
 *  space-delimited multi-word headword ("a great deal" → a/great/deal),
 *  letter-bearing words only, deduped; empty below two members (a
 *  single-word headword has no Words section). Top-level + internal so the
 *  contract is unit-testable without an engine instance. */
internal fun whitespaceMemberWords(headword: String): List<TokenSpan> =
    headword.split(MEMBER_SPLIT_WHITESPACE)
        .filter { w -> w.any(Char::isLetter) }
        .distinct()
        .takeIf { it.size >= 2 }
        .orEmpty()
        .map { TokenSpan(surface = it, lookupForm = it) }

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
        // Latin set + Russian (Cyrillic) share the whitespace/Snowball/Wiktionary
        // engine — it keys off the language id, not the script.
        SourceLangId.EN, SourceLangId.ES, SourceLangId.FR, SourceLangId.DE,
        SourceLangId.IT, SourceLangId.PT, SourceLangId.NL, SourceLangId.TR,
        SourceLangId.VI, SourceLangId.ID, SourceLangId.SV, SourceLangId.DA,
        SourceLangId.NO, SourceLangId.FI, SourceLangId.HU, SourceLangId.RO,
        SourceLangId.CA, SourceLangId.RU, SourceLangId.AR, SourceLangId.HI,
        SourceLangId.PL -> LatinEngine(app, id)
        // Thai: no whitespace, isolating. Dedicated engine over a dictionary
        // maximal-matcher (newmm port); lookup via WiktionaryDictionaryManager.
        SourceLangId.TH -> ThaiEngine(app)
    }
}
