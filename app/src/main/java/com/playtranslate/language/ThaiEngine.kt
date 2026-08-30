package com.playtranslate.language

import android.content.Context
import com.playtranslate.model.DictionaryResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Thai source-language engine. Thai is written without inter-word spaces and is
 * isolating (no inflection), so this is a thin façade over a dictionary
 * maximal-matcher ([MaximalMatchThaiSegmenter], a `newmm` port) for
 * tokenization, with lookups served by [WiktionaryDictionaryManager] exactly
 * like [KoreanEngine] (surface == lemma, no stemming, `stemmed = null`).
 *
 * The segmenter's wordlist (`words.txt` in the TH pack: union of the Wiktionary
 * headwords and the PyThaiNLP CC0 list) is built **lazily on first use**, so a
 * [tokenize] that races ahead of (or skips) [preload] can't NPE — it just sees
 * the trie built from whatever is on disk. [segmenterOverride] lets tests inject
 * a fixture segmenter without a pack.
 */
class ThaiEngine(
    private val appContext: Context,
    private val segmenterOverride: ThaiSegmenter? = null,
) : SourceLanguageEngine {

    override val profile: SourceLanguageProfile = SourceLanguageProfiles[SourceLangId.TH]

    private val dict: WiktionaryDictionaryManager =
        WiktionaryDictionaryManager.get(appContext, SourceLangId.TH)

    private val yomitan = YomitanEnrichment(appContext, SourceLangId.TH.yomitanConsumingLang())

    private val segmenterLazy = lazy { MaximalMatchThaiSegmenter(loadTrie()) }
    private val segmenter: ThaiSegmenter get() = segmenterOverride ?: segmenterLazy.value

    /** The lazily-built trie segmenter, for the short-text classifier (which
     *  counts segments without the TokenSpan/suspend wrapper of [tokenize]).
     *  Honors [segmenterOverride], so fixture tests exercise the same path. */
    internal val shortTextSegmenter: ThaiSegmenter get() = segmenter

    override suspend fun preload(): PreloadResult {
        if (!LanguagePackStore.isInstalled(appContext, SourceLangId.TH)) {
            return PreloadResult.PackMissing
        }
        if (dict.preload() == null) {
            return PreloadResult.PackCorrupt("TH dict.sqlite failed to open")
        }
        // Build the segmenter trie now, off the UI thread — it's lazy, so the
        // first tap would otherwise pay the file-read + trie-build cost.
        withContext(Dispatchers.Default) { if (segmenterOverride == null) segmenterLazy.value }
        return PreloadResult.Success
    }

    override suspend fun tokenize(text: String): List<TokenSpan> = withContext(Dispatchers.Default) {
        segmenter.segment(text)
            .filter(::isLookupWorthy)
            // surface == lookupForm: isolating, no lemmatization. The segmenter
            // emits verbatim substrings, so indexOf relocation downstream is
            // exact (mirrors KoreanEngine / ChineseEngine).
            .map { TokenSpan(surface = it, lookupForm = it, reading = null) }
    }

    override suspend fun searchPrefix(query: String, limit: Int): List<TokenSpan> =
        dict.searchPrefix(query, limit).map { TokenSpan(surface = it, lookupForm = it, reading = null) }

    override suspend fun lookup(word: String, reading: String?): DictionaryResponse? =
        yomitan.applyTo(
            // stemmed = null: isolating language, no stem fallback (KO pattern).
            dict.lookup(surface = word, stemmed = null),
            word, reading = null, fallbackForms = emptyList(),
        )

    override fun close() {
        dict.close()
    }

    /**
     * A segment is worth a dictionary lookup if it contains at least one Thai
     * letter. Deliberately NOT LatinEngine's `length < 2` floor — single-syllable
     * Thai words are common and valid. The non-Thai runs the segmenter emits
     * (spaces, punctuation, latin, numbers) are dropped here.
     */
    private fun isLookupWorthy(token: String): Boolean = token.any { it in THAI_RANGE }

    /** Build the segmenter trie from the pack's `words.txt`; empty if absent
     *  (segmenter then emits whole non-dictionary runs — degraded, not crashing). */
    private fun loadTrie(): ThaiWordTrie {
        val wordsFile = File(LanguagePackStore.dirFor(appContext, SourceLangId.TH), "words.txt")
        if (!wordsFile.isFile) return ThaiWordTrie.of(emptyList())
        return wordsFile.bufferedReader().useLines { lines ->
            ThaiWordTrie.of(
                lines.map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .toList(),
            )
        }
    }
}
