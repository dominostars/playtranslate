package com.playtranslate.language

import com.playtranslate.model.DictionaryResponse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The default (lexical-tier) [SourceLanguageEngine.annotate] positions its
 * spans in the text with a forward-cursor search. Regression guard for the
 * v3.1.0 drag-lens break: the default used to emit start == -1 on every
 * span, and [com.playtranslate.ui.DragLookupController]'s label cache drops
 * offsetless spans — so the lens never previewed a word for any engine
 * without an annotate override (Latin, Korean, Thai).
 */
class LexicalAnnotateOffsetsTest {

    /** Interface-default annotate over a scripted tokenize. */
    private class FakeEngine(private val tokens: (String) -> List<TokenSpan>) :
        SourceLanguageEngine {
        override val profile: SourceLanguageProfile =
            SourceLanguageProfiles.forCode(SourceLangId.EN.code)!!
        override suspend fun preload(): PreloadResult = PreloadResult.Success
        override suspend fun tokenize(text: String): List<TokenSpan> = tokens(text)
        override suspend fun lookup(word: String, reading: String?): DictionaryResponse? = null
        override fun close() {}
    }

    private fun wordTokens(text: String): List<TokenSpan> =
        text.split(" ").filter { it.isNotEmpty() }
            .map { TokenSpan(surface = it, lookupForm = it.lowercase()) }

    private fun annotate(text: String, tokens: (String) -> List<TokenSpan> = ::wordTokens) =
        runBlocking { FakeEngine(tokens).annotate(text) }

    @Test fun `spans carry true offsets and token fields`() {
        val ann = annotate("The cat sat")
        assertEquals(
            listOf(Triple(0, 3, "The"), Triple(4, 7, "cat"), Triple(8, 11, "sat")),
            ann.spans.map { Triple(it.start, it.end, it.surface) },
        )
        assertEquals("the", ann.spans[0].lookupForm)
    }

    @Test fun `duplicate surfaces resolve to successive occurrences`() {
        val ann = annotate("the cat the hat")
        assertEquals(listOf(0, 4, 8, 12), ann.spans.map { it.start })
    }

    @Test fun `cursor advances past a word containing a later token as substring`() {
        // "in" occurs inside "inside" at 0; the walk must land on offset 7.
        val ann = annotate("inside in")
        assertEquals(listOf(0, 7), ann.spans.map { it.start })
    }

    @Test fun `unlocatable surface stays offsetless without derailing later spans`() {
        val ann = annotate("AB CD") { listOf(TokenSpan("xx", "xx"), TokenSpan("CD", "cd")) }
        assertEquals(listOf(-1, 3), ann.spans.map { it.start })
        assertEquals(listOf(-1, 5), ann.spans.map { it.end })
    }

    @Test fun `empty tokenization degrades to one plain span`() {
        val ann = annotate("!!") { emptyList() }
        assertEquals(listOf(AnnotatedSpan(start = 0, end = 2, surface = "!!")), ann.spans)
    }

    @Test fun `empty text yields no spans`() {
        assertEquals(emptyList<AnnotatedSpan>(), annotate("").spans)
    }
}
