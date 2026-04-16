package com.playtranslate.language

import android.content.Context
import android.icu.text.BreakIterator
import com.playtranslate.model.DictionaryResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tartarus.snowball.ext.EnglishStemmer
import java.util.Locale

/**
 * English source-language engine. Combines three off-the-shelf parts:
 *
 *  - **Tokenization**: ICU [BreakIterator] word-break iteration with
 *    [Locale.ENGLISH]. Correctly segments punctuation, contractions
 *    ("don't" → "don" + "'t"), and multi-byte characters.
 *  - **Stemming**: Lucene's Snowball Porter ([EnglishStemmer]). Maps
 *    "running" → "run", "houses" → "hous" (the Porter algorithm is
 *    aggressive and doesn't handle irregular verbs like "ran" → "run" —
 *    that's a dictionary-lookup concern, not a stemming one).
 *  - **Dictionary**: [LatinDictionaryManager] queries the downloaded
 *    English pack with surface-first and stem-fallback semantics.
 *
 * Tokenizer and stemmer are both stateful and not thread-safe, so both
 * operations are guarded by per-instance `synchronized` blocks. Contention
 * is negligible in practice (~1-5 OCR cycles per second).
 */
class LatinEngine(appContext: Context) : SourceLanguageEngine {

    override val profile: SourceLanguageProfile = SourceLanguageProfiles[SourceLangId.EN]

    private val dict: LatinDictionaryManager = LatinDictionaryManager.get(appContext)
    private val breakIterator: BreakIterator = BreakIterator.getWordInstance(Locale.ENGLISH)
    private val stemmer: EnglishStemmer = EnglishStemmer()
    private val stemmerLock = Any()
    private val iteratorLock = Any()

    override suspend fun preload() {
        dict.preload()
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
            val stem = stemOf(slice)
            result += TokenSpan(surface = slice, lookupForm = stem, reading = null)
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

    private fun stemOf(word: String): String = synchronized(stemmerLock) {
        stemmer.current = word.lowercase()
        stemmer.stem()
        stemmer.current
    }

    private fun isLookupWorthy(token: String): Boolean {
        if (token.isBlank()) return false
        if (!token.any { it.isLetter() }) return false  // pure punctuation / numeric
        if (token.length < 2) return false                // "a", "I" — too short
        return true
    }
}
