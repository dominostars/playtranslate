package com.playtranslate.dictionary

import com.playtranslate.language.AnnotatedSpan
import com.playtranslate.language.EntryRef
import com.playtranslate.language.InflectionTag
import com.playtranslate.language.ReadingOverride
import com.playtranslate.language.ReadingPart
import com.playtranslate.language.SentenceAnnotation
import com.playtranslate.language.SourceLangId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * [SentenceAnnotator.withReading]: a sentence card's own reading of its
 * word's span. The one anchored span on the override's surface becomes the
 * card's word, read its reading, with ruby split as the annotator splits a
 * span it reads whole; the entry ref and pitch of the word the analysis chose
 * go; the lookup fields stay; nothing else moves, a surface held twice keeps
 * its analysis, and the shared analysis itself is never modified.
 */
class SentenceAnnotatorReadingOverrideTest {

    private val hajiketa = AnnotatedSpan(
        start = 4, end = 7, surface = "弾けた",
        lookupForm = "弾ける", word = "弾ける", entryRef = EntryRef.Pack(1419380L),
        lookupHint = "はじけ", reading = "はじけた",
        furigana = listOf(ReadingPart("弾", "はじ"), ReadingPart("け", null), ReadingPart("た", null)),
        tokenReading = "はじけた", inflections = listOf(InflectionTag.TA),
    )

    private fun analysis(vararg spans: AnnotatedSpan) =
        SentenceAnnotation(spans.joinToString("") { it.surface }, SourceLangId.JA, 0, spans.toList())

    private val piano = AnnotatedSpan(
        0, 3, "ピアノ", lookupForm = "ピアノ", word = "ピアノ", reading = "ぴあの",
        furigana = listOf(ReadingPart("ピアノ", null)),
    )
    private val ga = AnnotatedSpan(3, 4, "が", furigana = listOf(ReadingPart("が", null)))

    @Test
    fun `the card's word takes its span, read its reading`() {
        val before = analysis(piano, ga, hajiketa)
        val snapshot = before.copy()
        val after = SentenceAnnotator.withReading(before, ReadingOverride("弾けた", "弾く", "ひけた"))

        assertEquals(
            hajiketa.copy(
                word = "弾く", entryRef = null, reading = "ひけた",
                furigana = listOf(ReadingPart("弾", "ひ"), ReadingPart("け", null), ReadingPart("た", null)),
            ),
            after.spans[2],
        )
        assertEquals(listOf(piano, ga), after.spans.take(2))
        assertEquals(snapshot, before)
    }

    @Test
    fun `a homograph's span drops the accent of the reading it no longer has`() {
        val hou = AnnotatedSpan(
            0, 1, "方", lookupForm = "方", word = "方", entryRef = EntryRef.Pack(1L),
            reading = "ほう", furigana = listOf(ReadingPart("方", "ほう")), pitch = listOf(1),
        )
        val after = SentenceAnnotator.withReading(analysis(hou), ReadingOverride("方", "方", "かた"))
        assertEquals(
            hou.copy(entryRef = null, reading = "かた", furigana = listOf(ReadingPart("方", "かた")), pitch = emptyList()),
            after.spans.single(),
        )
    }

    @Test
    fun `a surface the sentence holds twice keeps its analysis, read the same or not`() {
        // 上手な方と下手な方 reads かた, then ほう: the override names no
        // occurrence, so neither may take the card's reading.
        val kata = AnnotatedSpan(
            3, 4, "方", lookupForm = "方", word = "方", reading = "かた",
            furigana = listOf(ReadingPart("方", "かた")),
        )
        val hou = kata.copy(start = 8, end = 9, reading = "ほう", furigana = listOf(ReadingPart("方", "ほう")))
        val readOtherwise = SentenceAnnotation("上手な方と下手な方", SourceLangId.JA, 0, listOf(kata, hou))
        assertSame(readOtherwise, SentenceAnnotator.withReading(readOtherwise, ReadingOverride("方", "方", "ほう")))

        val twice = analysis(piano, ga, hajiketa, hajiketa.copy(start = 7, end = 10))
        assertSame(twice, SentenceAnnotator.withReading(twice, ReadingOverride("弾けた", "弾く", "ひけた")))
    }

    private val totemo = AnnotatedSpan(0, 3, "とても", reading = "とても", furigana = listOf(ReadingPart("とても", null)))
    private val kiNiNaru = AnnotatedSpan(
        3, 7, "気になる", lookupForm = "気になる", word = "気になる", entryRef = EntryRef.Pack(1L),
        reading = "きになる", furigana = listOf(ReadingPart("気", "き"), ReadingPart("になる", null)),
        tokenReading = "きになる",
    )

    @Test
    fun `a member inside one larger span is split out of it, readings kept`() {
        val before = analysis(totemo, kiNiNaru)
        val after = SentenceAnnotator.withReading(before, ReadingOverride("気", "気", "き"))

        assertEquals(
            listOf(
                totemo,
                AnnotatedSpan(
                    3, 4, "気", lookupForm = "気", word = "気", reading = "き",
                    furigana = listOf(ReadingPart("気", "き")), tokenReading = "き",
                ),
                AnnotatedSpan(
                    4, 7, "になる", reading = "になる",
                    furigana = listOf(ReadingPart("になる", null)), tokenReading = "になる",
                ),
            ),
            after.spans,
        )
        // The sentence reads as it did.
        assertEquals(before.spans.joinToString("") { it.reading!! }, after.spans.joinToString("") { it.reading!! })
    }

    @Test
    fun `a member keeps the reading the unit gave it, not its own`() {
        // 瞬く間に reads またたくまに: 間 is ま here, whatever the card's 間 reads.
        val matatakuMaNi = AnnotatedSpan(
            0, 4, "瞬く間に", lookupForm = "瞬く間に", word = "瞬く間に", reading = "またたくまに",
            furigana = listOf(
                ReadingPart("瞬", "またた"), ReadingPart("く", null), ReadingPart("間", "ま"), ReadingPart("に", null),
            ),
        )
        val after = SentenceAnnotator.withReading(analysis(matatakuMaNi), ReadingOverride("間", "間", "あいだ"))
        assertEquals(listOf("瞬く", "間", "に"), after.spans.map { it.surface })
        assertEquals(listOf("またたく", "ま", "に"), after.spans.map { it.reading })
        assertEquals(listOf(null, "間", null), after.spans.map { it.word })
    }

    @Test
    fun `a cut may fall inside kana`() {
        val kakeru = AnnotatedSpan(
            0, 5, "手をかける", word = "手をかける", reading = "てをかける",
            furigana = listOf(ReadingPart("手", "て"), ReadingPart("をかける", null)),
        )
        val after = SentenceAnnotator.withReading(analysis(kakeru), ReadingOverride("かける", "かける", "かける"))
        assertEquals(listOf("手を", "かける"), after.spans.map { it.surface })
        assertEquals(listOf(null, "かける"), after.spans.map { it.word })
    }

    @Test
    fun `no split where it cannot be told or cannot be cut`() {
        // 元気が気になる: 気 is in two spans, the first of which could be cut
        // at it, so only the count can refuse.
        val genki = AnnotatedSpan(
            0, 2, "元気", word = "元気", reading = "げんき",
            furigana = listOf(ReadingPart("元", "げん"), ReadingPart("気", "き")),
        )
        val ga = AnnotatedSpan(2, 3, "が", furigana = listOf(ReadingPart("が", null)))
        val twoHolders = analysis(genki, ga, kiNiNaru.copy(start = 3, end = 7))
        assertSame(twoHolders, SentenceAnnotator.withReading(twoHolders, ReadingOverride("気", "気", "き")))

        // 大 inside 大人 read おとな: the reading belongs to the whole part,
        // with or without a span reading to check the pieces against (one
        // unread token leaves the span without one).
        val otona = AnnotatedSpan(0, 2, "大人", word = "大人", reading = "おとな", furigana = listOf(ReadingPart("大人", "おとな")))
        val whole = analysis(otona)
        assertSame(whole, SentenceAnnotator.withReading(whole, ReadingOverride("大", "大", "おお")))
        val unread = analysis(
            AnnotatedSpan(
                0, 3, "大人Ω", word = "大人Ω",
                furigana = listOf(ReadingPart("大人", "おとな"), ReadingPart("Ω", null)),
            ),
        )
        assertSame(unread, SentenceAnnotator.withReading(unread, ReadingOverride("大", "大", "おお")))

        // The text twice inside the one span.
        val hitoToHito = AnnotatedSpan(
            0, 3, "人と人", word = "人と人", reading = "ひととひと",
            furigana = listOf(ReadingPart("人", "ひと"), ReadingPart("と", null), ReadingPart("人", "ひと")),
        )
        val twice = analysis(hitoToHito)
        assertSame(twice, SentenceAnnotator.withReading(twice, ReadingOverride("人", "人", "ひと")))

        // A reading that is not its parts' readings joined.
        val odd = analysis(kiNiNaru.copy(start = 0, end = 4, reading = "けになる"))
        assertSame(odd, SentenceAnnotator.withReading(odd, ReadingOverride("気", "気", "き")))
    }

    @Test
    fun `a kana reading of its own surface stays plain`() {
        val itta = AnnotatedSpan(
            0, 3, "いった", lookupForm = "言う", word = "言う", reading = "いった",
            furigana = listOf(ReadingPart("いった", null)),
        )
        val after = SentenceAnnotator.withReading(analysis(itta), ReadingOverride("いった", "行く", "いった"))
        assertEquals(listOf(ReadingPart("いった", null)), after.spans.single().furigana)
        assertEquals("行く", after.spans.single().word)
    }

    @Test
    fun `no anchored span on the surface leaves the analysis itself`() {
        val before = analysis(piano, ga, hajiketa)
        assertSame(before, SentenceAnnotator.withReading(before, ReadingOverride("弾く", "弾く", "ひく")))

        // An offsetless span (lexical tier) is never re-read.
        val offsetless = SentenceAnnotation(
            "弾けた", SourceLangId.JA, 0, listOf(hajiketa.copy(start = -1, end = -1)),
        )
        assertSame(offsetless, SentenceAnnotator.withReading(offsetless, ReadingOverride("弾けた", "弾く", "ひけた")))
    }
}
