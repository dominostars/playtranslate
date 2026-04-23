package com.playtranslate.language

import android.content.Context
import android.icu.text.BreakIterator
import com.playtranslate.model.DictionaryResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tartarus.snowball.SnowballProgram
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
import org.tartarus.snowball.ext.SpanishStemmer
import org.tartarus.snowball.ext.SwedishStemmer
import org.tartarus.snowball.ext.TurkishStemmer
import java.util.Locale

/**
 * Source-language engine for Latin-script languages. Combines three
 * off-the-shelf parts:
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

    override suspend fun lookup(word: String, reading: String?): DictionaryResponse? {
        val stem = stemOf(word)
        return dict.lookup(surface = word, stemmed = stem)
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

    private fun isLookupWorthy(token: String): Boolean {
        if (token.isBlank()) return false
        if (!token.any { it.isLetter() }) return false
        if (token.length < 2) return false
        return true
    }

    companion object {
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
            // Vietnamese and Indonesian have no Snowball stemmer. Vietnamese
            // is fully isolating (no inflection to strip). Indonesian has
            // prefix morphology (ber-, me-, di-, ter-) that Snowball doesn't
            // model; surface-only lookup is an acceptable first pass.
            SourceLangId.VI, SourceLangId.ID -> null
            // Should never happen — CJK ids never reach LatinEngine.
            SourceLangId.JA, SourceLangId.ZH, SourceLangId.ZH_HANT, SourceLangId.KO -> null
        }
    }
}
