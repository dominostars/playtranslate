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

    override suspend fun lookup(word: String, reading: String?): DictionaryResponse? {
        val w = normalizeForLookup(word)
        val lower = w.lowercase(locale)
        val stem = stemOf(w)
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
