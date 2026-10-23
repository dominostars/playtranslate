package com.playtranslate.language

import com.playtranslate.model.DictionaryEntry
import com.playtranslate.model.DictionaryResponse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [memberWordsFor], the one rule that turns a looked-up unit into the
 * engine's member policy: the entry's own expression class, a spaced word
 * as an expression by form, and no entry at all as the word class, the
 * engine's structural reading deciding whether an unlisted unit is a phrase.
 * Pure JVM over a recording engine.
 */
class MemberWordsForTest {

    private class RecordingEngine : SourceLanguageEngine {
        override val profile: SourceLanguageProfile = SourceLanguageProfiles[SourceLangId.JA]
        var headword: String? = null
        var expressionClass: Boolean? = null
        var headwordReading: String? = null
        override suspend fun preload(): PreloadResult = PreloadResult.Success
        override suspend fun tokenize(text: String) = emptyList<TokenSpan>()
        override suspend fun lookup(word: String, reading: String?): DictionaryResponse? = null
        override suspend fun memberWordsOf(
            headword: String,
            expressionClass: Boolean,
            headwordReading: String?,
        ): List<TokenSpan> {
            this.headword = headword
            this.expressionClass = expressionClass
            this.headwordReading = headwordReading
            return emptyList()
        }
        override fun close() {}
    }

    private fun entry(isExpression: Boolean) = DictionaryEntry(
        slug = "x", isCommon = null, tags = emptyList(), jlpt = emptyList(),
        headwords = emptyList(), senses = emptyList(), isExpression = isExpression,
    )

    private fun classFor(word: String, reading: String?, entry: DictionaryEntry?): Pair<Boolean?, RecordingEngine> {
        val engine = RecordingEngine()
        runBlocking { engine.memberWordsFor(word, reading, entry) }
        return engine.expressionClass to engine
    }

    @Test
    fun `no entry passes the word class, and the word and reading through`() {
        // The engine's own grammar detection promotes an unlisted PHRASE
        // (星と深淵を目指せ, standalone と and を) to the loose gate; an
        // unlisted COMPOUND keeps the completeness check, so forcing the
        // phrase class here would only skip that check (Codex, 2026-10-22).
        val (cls, engine) = classFor("星と深淵を目指せ", "ほしとしんえんをめざせ", entry = null)
        assertEquals(false, cls)
        assertEquals("星と深淵を目指せ", engine.headword)
        assertEquals("ほしとしんえんをめざせ", engine.headwordReading)
    }

    @Test
    fun `an entry's expression flag is the class`() {
        assertEquals(true, classFor("瞬く間に", "またたくまに", entry(isExpression = true)).first)
        assertEquals(false, classFor("放送番組", "ほうそうばんぐみ", entry(isExpression = false)).first)
    }

    @Test
    fun `a spaced word is an expression by form whatever its entry says`() {
        assertEquals(true, classFor("a great deal", null, entry(isExpression = false)).first)
    }
}
