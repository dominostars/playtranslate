package com.playtranslate.language

import android.content.Context
import android.icu.text.BreakIterator
import com.playtranslate.model.DictionaryResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tartarus.snowball.SnowballProgram
import org.tartarus.snowball.ext.ArabicStemmer
import org.tartarus.snowball.ext.CatalanStemmer
import org.tartarus.snowball.ext.DanishStemmer
import org.tartarus.snowball.ext.DutchStemmer
import org.tartarus.snowball.ext.EnglishStemmer
import org.tartarus.snowball.ext.FinnishStemmer
import org.tartarus.snowball.ext.FrenchStemmer
import org.tartarus.snowball.ext.GermanStemmer
import org.tartarus.snowball.ext.HungarianStemmer
import org.tartarus.snowball.ext.ItalianStemmer
import org.tartarus.snowball.ext.NorwegianStemmer
import org.tartarus.snowball.ext.PortugueseStemmer
import org.tartarus.snowball.ext.RomanianStemmer
import org.tartarus.snowball.ext.RussianStemmer
import org.tartarus.snowball.ext.SpanishStemmer
import org.tartarus.snowball.ext.SwedishStemmer
import org.tartarus.snowball.ext.TurkishStemmer
import java.text.Normalizer
import java.util.Locale

/**
 * Source-language engine for whitespace-segmented languages with a Snowball
 * stemmer and a Wiktionary pack — the Latin set plus Cyrillic (Russian). The
 * machinery keys off [langId], not script. Combines three off-the-shelf parts:
 *
 *  - **Tokenization**: ICU [BreakIterator] with the language's [Locale].
 *  - **Stemming**: Lucene's Snowball stemmer for the language. Nullable —
 *    isolating languages (Vietnamese, Indonesian) have no Snowball stemmer
 *    because there is no inflection to strip; [stemOf] falls back to the
 *    lowercased surface for them.
 *  - **Dictionary**: [WiktionaryDictionaryManager] queries the downloaded
 *    pack with surface-first and stem-fallback semantics.
 *
 * [longestPhraseAt] adds tap-time multi-word expression matching over the
 * same pack (plus imported Yomitan dicts): the packs index six-figure counts
 * of space-joined headwords ("a great deal", "il y a", "máy tính") that
 * one-token-per-word [tokenize] output can never query. Tap surfaces probe it
 * with the tapped word's offset and show the returned phrase's entry
 * ALONGSIDE the word's own — the tap target and popup identity stay the
 * single word. Deliberately NOT folded into [tokenize]: greedy stream-level
 * fusing over-fuses English-class text (the packs list function-word bigrams
 * like "on the"/"of a" that are data-indistinguishable from gold ones like
 * "have to"), and a curated exclusion list proved unmaintainable — showing
 * the phrase as an extra popup section instead bounds any marginal hit to
 * one ignorable row while the tapped word's definition is always present.
 *
 * Tokenizer and stemmer are both stateful and not thread-safe, so both
 * operations are guarded by per-instance `synchronized` blocks.
 */
class LatinEngine(
    private val appContext: Context,
    private val langId: SourceLangId = SourceLangId.EN,
) : SourceLanguageEngine {

    override val profile: SourceLanguageProfile = SourceLanguageProfiles[langId]

    private val dict: WiktionaryDictionaryManager = WiktionaryDictionaryManager.get(appContext, langId)
    private val yomitan = YomitanEnrichment(appContext, langId.yomitanConsumingLang())
    private val locale: Locale = langId.locale
    private val breakIterator: BreakIterator = BreakIterator.getWordInstance(locale)
    private val stemmer: SnowballProgram? = stemmerFor(langId)
    private val stemmerLock = Any()
    private val iteratorLock = Any()

    override suspend fun preload(): PreloadResult {
        if (!LanguagePackStore.isInstalled(appContext, langId)) {
            return PreloadResult.PackMissing
        }
        if (dict.preload() == null) {
            return PreloadResult.PackCorrupt("${langId.code} dict.sqlite failed to open")
        }
        return PreloadResult.Success
    }

    override suspend fun tokenize(text: String): List<TokenSpan> = withContext(Dispatchers.Default) {
        val result = mutableListOf<TokenSpan>()
        val tokenSpans = mutableListOf<String>()

        synchronized(iteratorLock) {
            breakIterator.setText(text)
            var start = breakIterator.first()
            var end = breakIterator.next()
            while (end != BreakIterator.DONE) {
                val slice = text.substring(start, end)
                if (isLookupWorthy(slice)) tokenSpans += slice
                start = end
                end = breakIterator.next()
            }
        }

        for (slice in tokenSpans) {
            // lookupForm = surface (not stem). WiktionaryDictionaryManager handles
            // surface-first + stem-fallback internally. Emitting the stem as
            // lookupForm would double-stem and miss dictionary entries.
            result += TokenSpan(surface = slice, lookupForm = slice, reading = null)
        }
        result
    }

    override suspend fun searchPrefix(query: String, limit: Int): List<TokenSpan> =
        dict.searchPrefix(normalizeForLookup(query), limit)
            .map { TokenSpan(surface = it, lookupForm = it, reading = null) }

    override suspend fun longestPhraseAt(text: String, offset: Int): String? {
        // Window collection needs the shared (stateful) BreakIterator; the
        // dictionary gates suspend, so they run outside the lock.
        val window = synchronized(iteratorLock) {
            phraseWindow(text, offset, breakIterator, MAX_PHRASE_WINDOW_WORDS)
        } ?: return null
        val words = window.words
        if (words.size < 2) return null
        // Candidates: every n-gram that CONTAINS the tapped word — "open the
        // door" must be reachable from a tap on "door" just as from "open".
        // Longest first; at equal length the leftmost start wins (tap "up"
        // in "give up on it": "give up" beats "up on" — a pinned tie-break,
        // no data signal distinguishes them). The descending-length /
        // ascending-start loop order IS that ordering, so no sort. Forms are
        // engine-normalized like [lookup]'s own key (AR undiacritization /
        // HI NFC — identity elsewhere); joining word slices with one space
        // collapses any run of source whitespace to the packs' space-joined
        // headword shape.
        val candidates: List<Pair<Int, String>> = buildList {
            for (n in minOf(words.size, MAX_PHRASE_WINDOW_WORDS) downTo 2) {
                for (a in maxOf(0, window.anchorIndex - n + 1)..
                    minOf(window.anchorIndex, words.size - n)) {
                    add(n to normalizeForLookup(
                        words.subList(a, a + n).joinToString(" ") { text.substring(it) },
                    ))
                }
            }
        }
        // Pack gate: only windows the build could have kept (MAX_HEADWORD_WORDS).
        val packKnown = dict.phrasesExist(
            candidates.mapNotNullTo(mutableSetOf()) { (n, c) ->
                c.takeIf { n <= MAX_PACK_PHRASE_WORDS }
            },
        )
        // Imported-dictionary gate for pack misses — Yomitan dicts list longer
        // expressions and store original-case headwords, so each candidate is
        // offered in both the as-written and locale-lowercased forms (the same
        // pair [lookup]'s imported chain will try).
        val oracleKnown = if (candidates.any { (n, c) -> n > MAX_PACK_PHRASE_WORDS || c !in packKnown }) {
            yomitan.phraseOracle()?.invoke(
                buildSet {
                    for ((_, c) in candidates) {
                        add(c)
                        add(c.lowercase(locale))
                    }
                },
            ).orEmpty()
        } else {
            emptySet()
        }
        return candidates.firstOrNull { (n, c) ->
            (n <= MAX_PACK_PHRASE_WORDS && c in packKnown) ||
                c in oracleKnown || c.lowercase(locale) in oracleKnown
        }?.second
    }

    override suspend fun lookup(word: String, reading: String?): DictionaryResponse? {
        val w = normalizeForLookup(word)
        val lower = w.lowercase(locale)
        // Phrase keys skip real stemming: Snowball is single-word machinery,
        // and a stemmed join could equal an UNRELATED entry's stem row —
        // surfacing a wrong entry mislabeled [stem]. Inflected phrase surfaces
        // resolve through the pack's position-2 alias rows ("gave up") instead.
        val stem = if (w.any(Char::isWhitespace)) lower else stemOf(w)
        // Arabic gets a folded lookup key (casual/variant spellings) as a
        // fallback the dictionary tries after surface and before stem.
        val folded = if (langId == SourceLangId.AR) ArabicFold.fold(w) else null
        return yomitan.applyTo(
            dict.lookup(surface = w, stemmed = stem, folded = folded),
            w, reading = null,
            fallbackForms = importedTermFallbacks(w, lower, stem, folded),
        )
    }

    override fun close() {
        dict.close()
    }

    /** Returns the stem for [word], or the lowercased surface when the
     *  language has no Snowball stemmer. Lowercasing runs under the
     *  language's [locale] so Turkish `IŞIK` → `ışık` (not `işik`).
     *  Callers of [WiktionaryDictionaryManager.lookup] already short-circuit
     *  when `stemmed == surface`, so no extra guard is needed downstream. */
    private fun stemOf(word: String): String {
        val lower = word.lowercase(locale)
        val s = stemmer ?: return lower
        return synchronized(stemmerLock) {
            s.setCurrent(lower)
            s.stem()
            s.current
        }
    }

    /** Arabic source text is matched against undiacritized headwords — NFKC +
     *  tashkeel/tatweel stripped, but letter identities PRESERVED (NO alef/ya/taa
     *  fold; see [ArabicNormalize] — the normalized form doubles as the displayed
     *  lemma). The pack is built with the identical normalization. Casual
     *  letter-variant spellings are handled separately by the folded fallback in
     *  [lookup] ([ArabicFold]). No-op for other languages. */
    private fun normalizeForLookup(word: String): String = when (langId) {
        SourceLangId.AR -> ArabicNormalize.normalize(word)
        // Devanagari: canonical NFC composes nukta sequences (ड़ etc.) so OCR and
        // pack forms match. Divergence-free (the pack applies the same NFC); NOT
        // IndicNormalizer folding (deferred with the Lucene stemmer).
        SourceLangId.HI -> Normalizer.normalize(word, Normalizer.Form.NFC)
        else -> word
    }

    private fun isLookupWorthy(token: String): Boolean {
        if (token.isBlank()) return false
        if (!token.any { it.isLetter() }) return false
        if (token.length < 2) return false
        return true
    }

    companion object {
        /** Longest phrase candidate [longestPhraseAt] considers, in words.
         *  Imported Yomitan dictionaries carry expressions past the pack's
         *  cap ("as far as I know"); beyond ~5 words, English-class
         *  headwords are proverbs a tap lookup shouldn't swallow. The window
         *  spans up to this many words on EACH side of the tapped word
         *  (candidates contain the tap, so a longer reach is unreachable). */
        internal const val MAX_PHRASE_WINDOW_WORDS = 5

        /** Mirror of the pack build's MAX_HEADWORD_WORDS
         *  (scripts/wiktionary_filters.py) — longer candidates skip the pack
         *  gate because the build guarantees they cannot exist there. */
        internal const val MAX_PACK_PHRASE_WORDS = 3

        /** [phraseWindow]'s result: the whitespace-adjacent [words] around
         *  the tapped word, with [anchorIndex] pointing at the tapped word
         *  itself. */
        internal data class PhraseWindow(
            val words: List<IntRange>,
            val anchorIndex: Int,
        )

        /**
         * Word-range window for phrase matching: the word containing
         * [offset], plus up to [maxWords] − 1 words of context on each side
         * — all chained only across whitespace-ONLY separators. A comma,
         * hyphen, or digit run breaks the chain, because pack phrases are
         * space-joined ("great, deal" must never gate "great deal"). Unlike
         * [tokenize]'s isLookupWorthy filter this keeps single-letter words:
         * phrases start with and contain them ("a great deal", "il y a").
         * Left context is collected so a phrase is reachable from a tap on
         * ANY member word ("open the door" from "door"). Null = [offset]
         * doesn't land in a word; a singleton window = no phrase possible
         * there.
         *
         * Caller must hold the lock guarding [iterator] (stateful, not
         * thread-safe). Internal + iterator-injected so tests can drive it
         * with a fresh instance.
         */
        internal fun phraseWindow(
            text: String,
            offset: Int,
            iterator: BreakIterator,
            maxWords: Int,
        ): PhraseWindow? {
            if (offset < 0 || offset >= text.length) return null
            iterator.setText(text)
            // Single forward scan. [chain] holds the current run of
            // whitespace-adjacent words; it resets on every adjacency break
            // until the word containing [offset] is found (so it then holds
            // that word's contiguous left context), and the scan stops on the
            // first break after it. Pre-anchor the chain is trimmed to the
            // longest left context ever kept, so a long text can't grow it.
            var chain = mutableListOf<IntRange>()
            var anchor = -1
            var start = iterator.first()
            var end = iterator.next()
            while (end != BreakIterator.DONE) {
                val range = start until end
                if (range.any { text[it].isLetter() }) {
                    val gap = if (chain.isEmpty()) "" else text.substring(chain.last().last + 1, start)
                    val adjacent = gap.isNotEmpty() && gap.all(Char::isWhitespace)
                    if (!adjacent) {
                        if (anchor >= 0) break              // forward chain ended past the tapped word
                        if (offset < start) return null     // tapped a gap the chain can't cross
                        chain = mutableListOf()             // adjacency broke before it: restart
                    }
                    chain += range
                    if (anchor < 0) {
                        if (offset in range) {
                            anchor = chain.size - 1
                        } else if (offset < end) {
                            return null                     // passed the offset without a word hit
                        } else if (chain.size > maxWords - 1) {
                            chain.removeAt(0)               // keep only reachable left context
                        }
                    } else if (chain.size - anchor >= maxWords) {
                        break                               // forward cap reached
                    }
                } else if (anchor < 0 && offset in range) {
                    return null                             // offset on whitespace/punctuation
                }
                start = end
                end = iterator.next()
            }
            if (anchor < 0) return null
            return PhraseWindow(chain.toList(), anchor)
        }

        /** Imported-term Yomitan lookup keys to try after the direct
         *  (original-case) surface [w] — which already matches dictionaries
         *  that store capitalized headwords (e.g. German nouns). In order,
         *  deduped against [w] and one another:
         *   - [lower]: the locale-lowercased surface. [WiktionaryDictionaryManager]
         *     queries this FIRST, so a sentence-initial capital still resolves a
         *     lowercase lemma (English-style dicts store lowercase) the built-in
         *     pack would find — without it, enrichment silently misses common
         *     capitalized words.
         *   - [stem]: the Snowball stem (when distinct from [w] and [lower]).
         *   - [folded]: the Arabic casual/variant-spelling fold.
         *  Pure + internal so the ordering is unit-testable without a pack. */
        internal fun importedTermFallbacks(
            w: String,
            lower: String,
            stem: String,
            folded: String?,
        ): List<String> = listOfNotNull(
            lower.takeIf { it != w },
            stem.takeIf { it != w && it != lower },
            folded,
        )

        /** Returns a fresh Snowball stemmer instance, or null for isolating
         *  languages with no useful stemming rules. English is the default
         *  catch-all only for unknown IDs — callers should route through
         *  the explicit branches. */
        private fun stemmerFor(id: SourceLangId): SnowballProgram? = when (id) {
            SourceLangId.EN -> EnglishStemmer()
            SourceLangId.ES -> SpanishStemmer()
            SourceLangId.FR -> FrenchStemmer()
            SourceLangId.DE -> GermanStemmer()
            SourceLangId.IT -> ItalianStemmer()
            SourceLangId.PT -> PortugueseStemmer()
            SourceLangId.NL -> DutchStemmer()
            SourceLangId.TR -> TurkishStemmer()
            SourceLangId.SV -> SwedishStemmer()
            SourceLangId.DA -> DanishStemmer()
            SourceLangId.NO -> NorwegianStemmer()
            SourceLangId.FI -> FinnishStemmer()
            SourceLangId.HU -> HungarianStemmer()
            SourceLangId.RO -> RomanianStemmer()
            SourceLangId.CA -> CatalanStemmer()
            SourceLangId.RU -> RussianStemmer()
            // Arabic: Snowball light stemmer (clitic/affix stripping + internal
            // normalization). Heavy morphology (broken plurals, weak verbs) ships
            // as position-2 alias rows in the pack, not handled here.
            SourceLangId.AR -> ArabicStemmer()
            // Vietnamese and Indonesian have no Snowball stemmer. Vietnamese
            // is fully isolating (no inflection to strip). Indonesian has
            // prefix morphology (ber-, me-, di-, ter-) that Snowball doesn't
            // model; surface-only lookup is an acceptable first pass.
            // Hindi defers the Lucene HindiStemmer (v1 = surface + form_of aliases).
            // Polish has NO Snowball stemmer at all; its inflection ships as
            // position-2 PoliMorf alias rows in the pack (build-time), so runtime
            // stemming is intentionally null. See docs/polish-source-language-plan.md.
            SourceLangId.VI, SourceLangId.ID, SourceLangId.HI, SourceLangId.PL -> null
            // Should never happen — CJK ids and Thai never reach LatinEngine
            // (each has a dedicated engine). Listed to satisfy the exhaustive when.
            SourceLangId.JA, SourceLangId.ZH, SourceLangId.ZH_HANT, SourceLangId.KO,
            SourceLangId.TH -> null
        }
    }
}
