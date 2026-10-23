package com.playtranslate.ui

import androidx.test.core.app.ApplicationProvider
import com.playtranslate.language.ChineseScriptVariant
import com.playtranslate.language.PreloadResult
import com.playtranslate.language.SourceLangId
import com.playtranslate.language.SourceLanguageEngine
import com.playtranslate.language.SourceLanguageProfile
import com.playtranslate.language.SourceLanguageProfiles
import com.playtranslate.language.TokenSpan
import com.playtranslate.model.DictionaryEntry
import com.playtranslate.model.DictionaryResponse
import com.playtranslate.model.Headword
import com.playtranslate.model.Sense
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [resolveWordRows]'s member pass: a token no dictionary lists is replaced,
 * in its place, by the rows of its member words, asked for under the
 * no-entry rule ([com.playtranslate.language.memberWordsFor]: the phrase
 * class); a member already a token is not repeated; a token with no members
 * still yields nothing; a resolved token asks for no members; only a
 * confirmed miss is split, never a lookup that threw or an entry nothing
 * could render; a split that throws costs its own token's member rows and
 * nothing else, and cancellation propagates. Robolectric
 * only for the application context the row pipeline's resolver takes; the
 * engine is a fake and the target is English, so no gloss pack or
 * translator is touched.
 */
@RunWith(RobolectricTestRunner::class)
class WordRowMembersTest {

    private class FakeEngine(
        private val responses: Map<String, DictionaryResponse>,
        private val members: Map<String, List<TokenSpan>>,
        /** Headwords whose split throws, and what it throws. */
        private val splitFailures: Map<String, Throwable> = emptyMap(),
        /** Words whose lookup throws, and what it throws. */
        private val lookupFailures: Map<String, Throwable> = emptyMap(),
    ) : SourceLanguageEngine {
        override val profile: SourceLanguageProfile = SourceLanguageProfiles[SourceLangId.JA]
        val memberCalls = mutableListOf<Triple<String, Boolean, String?>>()
        override suspend fun preload(): PreloadResult = PreloadResult.Success
        override suspend fun tokenize(text: String) = emptyList<TokenSpan>()
        override suspend fun lookup(word: String, reading: String?): DictionaryResponse? {
            lookupFailures[word]?.let { throw it }
            return responses[word]
        }
        override suspend fun memberWordsOf(
            headword: String,
            expressionClass: Boolean,
            headwordReading: String?,
        ): List<TokenSpan> {
            memberCalls += Triple(headword, expressionClass, headwordReading)
            splitFailures[headword]?.let { throw it }
            return members[headword].orEmpty()
        }
        override fun close() {}
    }

    private fun entry(written: String, reading: String, gloss: String) = DictionaryResponse(
        listOf(
            DictionaryEntry(
                slug = written, isCommon = null, tags = emptyList(), jlpt = emptyList(),
                headwords = listOf(Headword(written, reading)),
                senses = listOf(
                    Sense(
                        targetDefinitions = listOf(gloss), partsOfSpeech = emptyList(),
                        tags = emptyList(), restrictions = emptyList(), info = emptyList(),
                    ),
                ),
            ),
        ),
    )

    private val phrase = TokenSpan("星と深淵を目指せ", "星と深淵を目指せ", "ほしとしんえんをめざせ")
    private val hoshi = TokenSpan("星", "星", "ほし")
    private val shinen = TokenSpan("深淵", "深淵", "しんえん")
    private val mezasu = TokenSpan("目指せ", "目指す", "めざす")
    private val neko = TokenSpan("猫", "猫", "ねこ")

    private val responses = mapOf(
        "星" to entry("星", "ほし", "star"),
        "深淵" to entry("深淵", "しんえん", "abyss"),
        "目指す" to entry("目指す", "めざす", "to aim at"),
        "猫" to entry("猫", "ねこ", "cat"),
    )

    private fun engine(vararg members: Pair<String, List<TokenSpan>>) = FakeEngine(responses, members.toMap())

    private fun rows(engine: FakeEngine, tokens: List<TokenSpan>): LookupData = runBlocking {
        resolveWordRows(
            ApplicationProvider.getApplicationContext(),
            WordLookupContext(engine, "en", ChineseScriptVariant.SIMPLIFIED),
            tokens,
        )
    }

    @Test
    fun `an unlisted phrase lists its member words in its place`() {
        val engine = engine(phrase.lookupForm to listOf(hoshi, shinen, mezasu))
        val data = rows(engine, listOf(phrase, neko))
        assertEquals(listOf("星", "深淵", "目指す", "猫"), data.rows.map { it.displayWord })
        // Asked for under the no-entry rule (the word class; the engine's
        // grammar detection promotes a real phrase), with the phrase's own
        // reading.
        assertEquals(listOf(Triple("星と深淵を目指せ", false, "ほしとしんえんをめざせ")), engine.memberCalls)
        // A member row is an ordinary row: its surface inside the phrase,
        // its key, its reading mapped for furigana.
        val aim = data.rows.single { it.displayWord == "目指す" }
        assertEquals("目指せ", aim.surface)
        assertEquals("目指す", aim.lookupForm)
        assertEquals("ほし", data.lookupToReading["星"])
        assertEquals("めざす", data.lookupToReading["目指せ"])
    }

    @Test
    fun `a member that is already a token is not repeated`() {
        val engine = engine(phrase.lookupForm to listOf(hoshi, shinen, mezasu))
        val data = rows(engine, listOf(phrase, hoshi))
        assertEquals(listOf("深淵", "目指す", "星"), data.rows.map { it.displayWord })
    }

    @Test
    fun `two unlisted phrases sharing a member list it once`() {
        val other = TokenSpan("星の海", "星の海", "ほしのうみ")
        val umi = TokenSpan("海", "海", "うみ")
        val engine = FakeEngine(
            responses + ("海" to entry("海", "うみ", "sea")),
            mapOf(phrase.lookupForm to listOf(hoshi, shinen, mezasu), other.lookupForm to listOf(hoshi, umi)),
        )
        val data = rows(engine, listOf(phrase, other))
        assertEquals(listOf("星", "深淵", "目指す", "海"), data.rows.map { it.displayWord })
    }

    @Test
    fun `an unlisted word with no members yields no row`() {
        val engine = engine()
        val data = rows(engine, listOf(TokenSpan("ぬこ", "ぬこ", "ぬこ"), neko))
        assertEquals(listOf("猫"), data.rows.map { it.displayWord })
        assertEquals(listOf(Triple("ぬこ", false, "ぬこ")), engine.memberCalls)
    }

    @Test
    fun `a resolved token asks for no members`() {
        val engine = engine(neko.lookupForm to listOf(hoshi))
        val data = rows(engine, listOf(neko))
        assertEquals(listOf("猫"), data.rows.map { it.displayWord })
        assertEquals(emptyList<Triple<String, Boolean, String?>>(), engine.memberCalls)
    }

    @Test
    fun `a failing split costs its own token's member rows and nothing else`() {
        // The Codex review's cell: a pack query throwing inside one token's
        // split must not take the rows that already resolved with it.
        val engine = FakeEngine(
            responses, emptyMap(),
            splitFailures = mapOf(phrase.lookupForm to IllegalStateException("database closed")),
        )
        val data = rows(engine, listOf(phrase, neko))
        assertEquals(listOf("猫"), data.rows.map { it.displayWord })
    }

    @Test
    fun `cancellation inside a split propagates`() {
        val engine = FakeEngine(
            responses, emptyMap(),
            splitFailures = mapOf(phrase.lookupForm to CancellationException("cancelled")),
        )
        try {
            rows(engine, listOf(phrase, neko))
            fail("expected the cancellation to propagate")
        } catch (_: CancellationException) {
            // expected
        }
    }

    @Test
    fun `a lookup that throws is not a miss, so no row, no split, and the other rows kept`() {
        // The Codex review's cell: a failure says nothing about whether the
        // word is listed, so its parts must not appear in its place.
        val engine = FakeEngine(
            responses, mapOf(phrase.lookupForm to listOf(hoshi, shinen, mezasu)),
            lookupFailures = mapOf(phrase.lookupForm to IllegalStateException("database closed")),
        )
        val data = rows(engine, listOf(phrase, neko))
        assertEquals(listOf("猫"), data.rows.map { it.displayWord })
        assertEquals(emptyList<Triple<String, Boolean, String?>>(), engine.memberCalls)
    }

    @Test
    fun `an entry nothing can render is not a miss`() {
        // Listed, but with no definition in any tier: the word stays
        // row-less and is not split into parts it does not have.
        val bare = DictionaryResponse(
            listOf(
                DictionaryEntry(
                    slug = phrase.lookupForm, isCommon = null, tags = emptyList(), jlpt = emptyList(),
                    headwords = listOf(Headword(phrase.lookupForm, phrase.reading)), senses = emptyList(),
                ),
            ),
        )
        val engine = FakeEngine(
            responses + (phrase.lookupForm to bare),
            mapOf(phrase.lookupForm to listOf(hoshi, shinen, mezasu)),
        )
        val data = rows(engine, listOf(phrase, neko))
        assertEquals(listOf("猫"), data.rows.map { it.displayWord })
        assertEquals(emptyList<Triple<String, Boolean, String?>>(), engine.memberCalls)
    }
}
