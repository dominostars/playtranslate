package com.playtranslate.language

import android.content.Context
import android.icu.text.BreakIterator
import com.playtranslate.model.DictionaryResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tartarus.snowball.SnowballProgram
import org.tartarus.snowball.ext.EnglishStemmer
import org.tartarus.snowball.ext.SpanishStemmer
import java.util.Locale

/**
 * Source-language engine for Latin-script languages (EN, ES, and future
 * FR/DE/IT/PT/NL). Combines three off-the-shelf parts:
 *
 *  - **Tokenization**: ICU [BreakIterator] with the language's [Locale].
 *  - **Stemming**: Lucene's Snowball stemmer for the language.
 *  - **Dictionary**: [LatinDictionaryManager] queries the downloaded pack
 *    with surface-first and stem-fallback semantics.
 *
 * Tokenizer and stemmer are both stateful and not thread-safe, so both
 * operations are guarded by per-instance `synchronized` blocks.
 */
class LatinEngine(
    appContext: Context,
    private val langId: SourceLangId = SourceLangId.EN,
) : SourceLanguageEngine {

    override val profile: SourceLanguageProfile = SourceLanguageProfiles[langId]

    private val dict: LatinDictionaryManager = LatinDictionaryManager.get(appContext, langId)
    private val breakIterator: BreakIterator = BreakIterator.getWordInstance(localeFor(langId))
    private val stemmer: SnowballProgram = stemmerFor(langId)
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
        stemmer.setCurrent(word.lowercase())
        stemmer.stem()
        stemmer.current
    }

    private fun isLookupWorthy(token: String): Boolean {
        if (token.isBlank()) return false
        if (!token.any { it.isLetter() }) return false
        if (token.length < 2) return false
        return true
    }

    companion object {
        private fun localeFor(id: SourceLangId): Locale = when (id) {
            SourceLangId.EN -> Locale.ENGLISH
            SourceLangId.ES -> Locale("es")
            else -> Locale(id.code)
        }

        private fun stemmerFor(id: SourceLangId): SnowballProgram = when (id) {
            SourceLangId.ES -> SpanishStemmer()
            else -> EnglishStemmer()
        }
    }
}
