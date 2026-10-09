package com.playtranslate.ui

import com.playtranslate.language.DefinitionResolver
import com.playtranslate.language.PreloadResult
import com.playtranslate.language.SourceLangId
import com.playtranslate.language.SourceLanguageEngine
import com.playtranslate.language.SourceLanguageProfile
import com.playtranslate.language.SourceLanguageProfiles
import com.playtranslate.language.TargetGlossLookup
import com.playtranslate.language.TargetSense
import com.playtranslate.language.TokenSpan
import com.playtranslate.model.DictionaryEntry
import com.playtranslate.model.DictionaryResponse
import com.playtranslate.model.Headword
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [collectSecondaryKeys]: which secondary sections a looked-up word gets,
 * in which order, and which of them would machine-translate. Plain JUnit
 * over a fake engine and a fake gloss database; nothing is translated.
 */
class SecondaryKeysTest {

    /** Answers [lookup] from [responses] by (lookup form, reading) and
     *  records every call. */
    private class FakeEngine(
        private val responses: Map<Pair<String, String?>, DictionaryResponse>,
    ) : SourceLanguageEngine {
        override val profile: SourceLanguageProfile = SourceLanguageProfiles[SourceLangId.JA]
        val lookups = mutableListOf<Pair<String, String?>>()
        override suspend fun preload(): PreloadResult = PreloadResult.Success
        override suspend fun tokenize(text: String) = emptyList<TokenSpan>()
        override suspend fun lookup(word: String, reading: String?): DictionaryResponse? {
            lookups += word to reading
            return responses[word to reading]
        }
        override fun close() {}
    }

    /** A target gloss pack holding a sense for each "$sourceLang:$written" in [hits]. */
    private class FakeTargetGlossDb(private val hits: Set<String>) : TargetGlossLookup {
        override fun lookup(sourceLang: String, written: String, reading: String?): List<TargetSense>? =
            if ("$sourceLang:$written" in hits) listOf(TargetSense(0, emptyList(), listOf("gloss"), "test")) else null
    }

    private fun entry(written: String, reading: String, packId: Long?, slug: String = written) = DictionaryEntry(
        slug = slug, packId = packId, isCommon = null, tags = emptyList(), jlpt = emptyList(),
        headwords = listOf(Headword(written = written, reading = reading)), senses = emptyList(),
    )

    private fun response(vararg entries: DictionaryEntry) = DictionaryResponse(entries.toList())

    // The looked-up word: 弾ける read はじける, as tapped in 弾けた.
    private val hajikeru = entry("弾ける", "はじける", 1419380L)

    // Its candidates, each under the span its section resolves with.
    private val kiSpan = TokenSpan("気", "気", "き")
    private val maSpan = TokenSpan("間", "間", "ま")
    private val hikuSpan = TokenSpan("弾けた", "弾く", "ひく")
    private val hajikuSpan = TokenSpan("弾けた", "弾く", "はじく")
    private val ki = entry("気", "き", 2L)
    private val ma = entry("間", "ま", 3L)
    private val hiku = entry("弾く", "ひく", 1419370L)
    private val hajiku = entry("弾く", "はじく", 1419360L)

    private val baseResponses: Map<Pair<String, String?>, DictionaryResponse> = mapOf(
        ("気" to "き") to response(ki),
        ("間" to "ま") to response(ma),
        ("弾く" to "ひく") to response(hiku),
        ("弾く" to "はじく") to response(hajiku),
    )

    private fun collect(
        engine: FakeEngine,
        targetLang: String = "en",
        glossDb: TargetGlossLookup? = null,
        primaryEntries: List<DictionaryEntry> = listOf(hajikeru),
        primaryWord: String = "弾ける",
        phraseKey: String? = null,
        members: List<TokenSpan> = emptyList(),
        alternatives: List<TokenSpan> = emptyList(),
    ): SecondaryKeys = runBlocking {
        collectSecondaryKeys(
            engine, DefinitionResolver(engine, glossDb, null, targetLang, null),
            primaryEntries, primaryWord, phraseKey, members, alternatives,
        )
    }

    private fun spansOf(keys: List<SecondaryKey>) = keys.map { it.kind to it.span }

    @Test
    fun `a member landing on a primary entry's pack id is dropped`() {
        val sameEntry = TokenSpan("弾け", "弾け", "はじけ")
        val engine = FakeEngine(baseResponses + (("弾け" to "はじけ") to response(entry("弾け", "はじけ", 1419380L))))
        val keys = collect(engine, members = listOf(sameEntry, kiSpan))
        assertEquals(listOf(SecondaryKind.MEMBER to kiSpan), spansOf(keys.all))
    }

    @Test
    fun `a member landing on a primary entry's slug is dropped`() {
        val sameSlug = TokenSpan("爆ける", "爆ける", "はじける")
        val engine = FakeEngine(
            baseResponses + (("爆ける" to "はじける") to response(entry("爆ける", "はじける", 9L, slug = "弾ける"))),
        )
        val keys = collect(engine, members = listOf(sameSlug, kiSpan))
        assertEquals(listOf(SecondaryKind.MEMBER to kiSpan), spansOf(keys.all))
    }

    @Test
    fun `a member displaying the looked-up word is dropped`() {
        val sameWord = TokenSpan("弾", "弾", null)
        val engine = FakeEngine(
            baseResponses + (("弾" to null) to response(entry("弾ける", "はじける", 9L, slug = "other"))),
        )
        val keys = collect(engine, members = listOf(sameWord, kiSpan))
        assertEquals(listOf(SecondaryKind.MEMBER to kiSpan), spansOf(keys.all))
    }

    @Test
    fun `members displaying the same word collapse to the first`() {
        val kiAgain = TokenSpan("気", "気", null)
        val engine = FakeEngine(baseResponses + (("気" to null) to response(entry("気", "け", 4L))))
        val keys = collect(engine, members = listOf(kiSpan, kiAgain, maSpan))
        assertEquals(listOf(SecondaryKind.MEMBER to kiSpan, SecondaryKind.MEMBER to maSpan), spansOf(keys.all))
    }

    @Test
    fun `two homograph alternatives with different pack ids are both kept`() {
        val keys = collect(FakeEngine(baseResponses), alternatives = listOf(hikuSpan, hajikuSpan))
        assertEquals(
            listOf(SecondaryKind.ALTERNATIVE to hikuSpan, SecondaryKind.ALTERNATIVE to hajikuSpan),
            spansOf(keys.all),
        )
    }

    @Test
    fun `a homograph of the looked-up word sharing its headword and slug is an alternative`() {
        // 弾く tapped and read ひく: its homograph 弾く read はじく stays,
        // while an alternative landing on the word's own entry does not.
        val tapped = TokenSpan("弾く", "弾く", "はじく")
        val own = TokenSpan("弾く", "弾く", "ひく")
        val keys = collect(
            FakeEngine(baseResponses),
            primaryEntries = listOf(hiku), primaryWord = "弾く",
            alternatives = listOf(own, tapped),
        )
        assertEquals(listOf(SecondaryKind.ALTERNATIVE to tapped), spansOf(keys.all))
    }

    @Test
    fun `a candidate whose lookup lands no entry is dropped`() {
        val missing = TokenSpan("無", "無", null)
        val empty = TokenSpan("空", "空", null)
        val engine = FakeEngine(baseResponses + (("空" to null) to response()))
        val keys = collect(engine, members = listOf(missing, empty, kiSpan), alternatives = listOf(missing, hikuSpan))
        assertEquals(
            listOf(SecondaryKind.MEMBER to kiSpan, SecondaryKind.ALTERNATIVE to hikuSpan),
            spansOf(keys.all),
        )
    }

    @Test
    fun `a phrase is the only secondary and members and alternatives are not looked up`() {
        val engine = FakeEngine(baseResponses + (("一番うまく" to null) to response(entry("一番うまく", "いちばんうまく", 5L))))
        val keys = collect(
            engine, phraseKey = "一番うまく", members = listOf(kiSpan), alternatives = listOf(hikuSpan),
        )
        assertEquals(
            listOf(SecondaryKind.PHRASE to TokenSpan("一番うまく", "一番うまく")),
            spansOf(keys.all),
        )
        assertEquals(listOf("一番うまく" to null), engine.lookups)
    }

    @Test
    fun `a phrase landing on the looked-up word's entry is dropped`() {
        val engine = FakeEngine(baseResponses + (("弾けるよ" to null) to response(hajikeru)))
        val keys = collect(engine, phraseKey = "弾けるよ", members = listOf(kiSpan))
        assertEquals(emptyList<SecondaryKey>(), keys.all)
        assertEquals(listOf("弾けるよ" to null), engine.lookups)
    }

    @Test
    fun `a word without an entry still gets its alternatives`() {
        val keys = collect(
            FakeEngine(baseResponses),
            primaryEntries = emptyList(), primaryWord = "弾けた",
            alternatives = listOf(hikuSpan, hajikuSpan),
        )
        assertEquals(
            listOf(SecondaryKind.ALTERNATIVE to hikuSpan, SecondaryKind.ALTERNATIVE to hajikuSpan),
            spansOf(keys.all),
        )
    }

    @Test
    fun `keys split by whether they would machine-translate, each list in section order`() {
        val members = listOf(kiSpan, maSpan)
        val alternatives = listOf(hikuSpan, hajikuSpan)
        val sectionOrder = listOf(
            SecondaryKind.MEMBER to kiSpan, SecondaryKind.MEMBER to maSpan,
            SecondaryKind.ALTERNATIVE to hikuSpan, SecondaryKind.ALTERNATIVE to hajikuSpan,
        )

        // An English target never machine-translates.
        val english = collect(FakeEngine(baseResponses), "en", FakeTargetGlossDb(emptySet()), members = members, alternatives = alternatives)
        assertEquals(sectionOrder, spansOf(english.eager))
        assertEquals(emptyList<SecondaryKey>(), english.pending)

        // German: a native gloss serves 間 and 弾く, none serves 気.
        val german = collect(
            FakeEngine(baseResponses), "de", FakeTargetGlossDb(setOf("ja:間", "ja:弾く")),
            members = members, alternatives = alternatives,
        )
        assertEquals(sectionOrder.drop(1), spansOf(german.eager))
        assertEquals(listOf(SecondaryKind.MEMBER to kiSpan), spansOf(german.pending))
        assertEquals(sectionOrder, spansOf(german.all))
        assertEquals(listOf(true, false, false, false), german.all.map { it.needsMt })

        // No gloss pack for the target: everything would machine-translate.
        val noPack = collect(FakeEngine(baseResponses), "de", null, members = members, alternatives = alternatives)
        assertEquals(emptyList<SecondaryKey>(), noPack.eager)
        assertEquals(sectionOrder, spansOf(noPack.pending))

        val phraseEngine = FakeEngine(baseResponses + (("一番うまく" to null) to response(entry("一番うまく", "いちばんうまく", 5L))))
        val phrase = collect(phraseEngine, "de", null, phraseKey = "一番うまく")
        assertEquals(listOf(SecondaryKind.PHRASE to TokenSpan("一番うまく", "一番うまく")), spansOf(phrase.pending))
        assertEquals(emptyList<SecondaryKey>(), phrase.eager)
    }

    @Test
    fun `each key carries the response its lookup returned`() {
        val keys = collect(FakeEngine(baseResponses), members = listOf(kiSpan), alternatives = listOf(hajikuSpan))
        assertEquals(listOf(response(ki), response(hajiku)), keys.all.map { it.response })
    }
}
