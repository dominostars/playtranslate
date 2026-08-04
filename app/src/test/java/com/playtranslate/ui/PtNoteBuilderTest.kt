package com.playtranslate.ui

import com.playtranslate.language.SourceLangId
import com.playtranslate.model.FrequencyTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the field VALUES the default note types are filled with — the
 * data the card templates (and the pitch JS's preconditions) consume.
 * Rendering itself is device-validated; what's testable here is that
 * each canonical field carries the right shape for its template slot.
 *
 * JA sentence-furigana bracket correctness is covered by
 * [SentenceAnkiHtmlBuilderTest] with an injected tokenizer (Sudachi
 * needs a pack dict unavailable in JVM tests); the ZH path is
 * word-list-driven and testable here directly.
 */
@RunWith(RobolectricTestRunner::class)
class PtNoteBuilderTest {

    private fun word(
        word: String = "辞書",
        reading: String = "じしょ",
        pitch: List<Int> = listOf(1),
        frequencies: List<FrequencyTag> = emptyList(),
        freqScore: Int = 0,
        definitionHtml: String = "<div class=\"gl-sense\">dictionary</div>",
        examplesHtml: String = "",
        imageFilename: String? = null,
        audioFilename: String? = null,
        audioCredit: String? = null,
    ) = PtNoteBuilder.forWord(
        word = word, reading = reading, pos = "noun",
        definitionHtml = definitionHtml, examplesHtml = examplesHtml,
        freqScore = freqScore, pitch = pitch, frequencies = frequencies,
        imageFilename = imageFilename, audioFilename = audioFilename,
        audioCredit = audioCredit,
    )

    private fun sentenceCard(
        source: String = "the cat sat",
        target: String = "el gato",
        words: List<SentenceAnkiHtmlBuilder.WordEntry> = listOf(
            SentenceAnkiHtmlBuilder.WordEntry("cat", "", "gato"),
        ),
        selected: Set<String> = setOf("cat"),
        lang: SourceLangId = SourceLangId.EN,
    ) = SentenceAnkiContentFragment.CardData(
        source = source, target = target, words = words,
        selectedWords = selected, screenshotPath = null, sourceLangId = lang,
    )

    // ── word note ────────────────────────────────────────────────────────

    @Test fun `JA word carries raw downsteps and separate reading`() {
        val note = word(pitch = listOf(0, 2))
        assertEquals("0,2", note.pitchPosition)
        assertEquals("じしょ", note.reading)
        assertEquals("辞書", note.expression)
    }

    @Test fun `frequency field is the chips row`() {
        val note = word(
            freqScore = 2,
            frequencies = listOf(FrequencyTag("JPDB", "1234", null, 1234.0)),
        )
        // v002: stars + per-dictionary chips for the template's .pt-meta
        // flex row — NOT the Lapis-pinned <ul> the structured path keeps.
        assertTrue(note.frequency.startsWith("<span class=\"gl-stars\">"))
        assertTrue(note.frequency.contains("★★"))
        assertTrue(note.frequency.contains("<span class=\"gl-chip\">JPDB: 1234</span>"))
        assertTrue(!note.frequency.contains("<ul>"))
    }

    @Test fun `non-JA word leaves pitch and reading empty`() {
        val note = word(word = "cat", reading = "", pitch = emptyList())
        assertEquals("", note.pitchPosition)
        assertEquals("", note.reading)
    }

    /** Kana-only entries collapse the kana into the headword: Reading
     *  is empty but pitch exists — the template JS's fallback (draw
     *  over the all-kana Expression) depends on exactly this shape. */
    @Test fun `kana-only word with pitch ships empty reading and all-kana expression`() {
        val note = word(word = "ねこ", reading = "", pitch = listOf(1))
        assertEquals("", note.reading)
        assertEquals("1", note.pitchPosition)
        assertEquals("ねこ", note.expression)
    }

    /** Kanji headword with a missing reading: pitch still ships, but
     *  the JS must NOT draw (its all-kana guard) — pinned here as the
     *  data precondition, on-device as behavior. */
    @Test fun `kanji word with missing reading still ships pitch`() {
        val note = word(word = "猫", reading = "", pitch = listOf(1))
        assertEquals("", note.reading)
        assertEquals("1", note.pitchPosition)
    }

    @Test fun `definition and examples pass through verbatim`() {
        val note = word(
            definitionHtml = "<div class=\"gl-sense\">D</div>",
            examplesHtml = "<div class=\"gl-section\">More examples</div>",
        )
        assertEquals("<div class=\"gl-sense\">D</div>", note.definition)
        assertEquals("<div class=\"gl-section\">More examples</div>", note.examples)
    }

    @Test fun `credit is escaped with newlines as br`() {
        val note = word(audioCredit = "Jane <3\nvia Commons")
        assertEquals("Jane &lt;3<br>via Commons", note.audioCredit)
    }

    // ── sentence note ────────────────────────────────────────────────────

    @Test fun `sentence field bolds the highlighted word`() {
        val note = PtNoteBuilder.forSentence(
            sentenceCard(), imageFilename = null, audioFilename = null,
            wordAudioFilenames = emptyMap(), audioCredit = null,
        )
        assertEquals("the <b>cat</b> sat", note.sentence)
    }

    @Test fun `non-JA sentence leaves furigana empty for the template fallback`() {
        val note = PtNoteBuilder.forSentence(
            sentenceCard(), imageFilename = null, audioFilename = null,
            wordAudioFilenames = emptyMap(), audioCredit = null,
        )
        assertEquals("", note.sentenceFurigana)
    }

    @Test fun `ZH sentence carries bracket furigana`() {
        val note = PtNoteBuilder.forSentence(
            sentenceCard(
                source = "今天",
                words = listOf(SentenceAnkiHtmlBuilder.WordEntry("今天", "jīn tiān", "today")),
                selected = emptySet(),
                lang = SourceLangId.ZH,
            ),
            annotation = com.playtranslate.language.SentenceAnnotation(
                text = "今天", lang = SourceLangId.ZH, importGeneration = 0,
                spans = listOf(com.playtranslate.language.AnnotatedSpan(
                    start = 0, end = 2, surface = "今天", lookupForm = "今天",
                )),
            ),
            imageFilename = null, audioFilename = null,
            wordAudioFilenames = emptyMap(), audioCredit = null,
        )
        // The PT path wraps each word for the template's tap-to-scroll
        // (data-pt-w keys the sentence word to its cell); pitch-less ZH
        // words carry the key alone. Brackets inside are unchanged.
        assertEquals(
            "<span data-pt-w=\"今天\">今[jīn]<wbr>天[tiān]</span>",
            note.sentenceFurigana,
        )
    }

    @Test fun `target word is the first highlighted word or empty`() {
        val with = PtNoteBuilder.forSentence(
            sentenceCard(), imageFilename = null, audioFilename = null,
            wordAudioFilenames = emptyMap(), audioCredit = null,
        )
        assertEquals("cat", with.targetWord)
        val without = PtNoteBuilder.forSentence(
            sentenceCard(selected = emptySet()), imageFilename = null,
            audioFilename = null, wordAudioFilenames = emptyMap(), audioCredit = null,
        )
        assertEquals("", without.targetWord)
    }

    @Test fun `words table carries per-word sound tags and baked pitch`() {
        val note = PtNoteBuilder.forSentence(
            sentenceCard(
                source = "ねこがいる",
                words = listOf(
                    SentenceAnkiHtmlBuilder.WordEntry("ねこ", "ねこ", "cat", pitch = listOf(1)),
                ),
                selected = setOf("ねこ"),
                lang = SourceLangId.JA,
            ),
            imageFilename = null, audioFilename = null,
            wordAudioFilenames = mapOf("ねこ" to "neko.ogg"), audioCredit = null,
        )
        assertTrue(note.wordsTable.contains("[sound:neko.ogg]"))
        assertTrue("baked pitch markup expected", note.wordsTable.contains("pa-m"))
    }

    @Test fun `translation is escaped with newlines as br`() {
        val note = PtNoteBuilder.forSentence(
            sentenceCard(target = "a<script>\nb"), imageFilename = null,
            audioFilename = null, wordAudioFilenames = emptyMap(), audioCredit = null,
        )
        assertEquals("a&lt;script&gt;<br>b", note.translation)
    }

    @Test fun `picture filename is escaped inside the img tag`() {
        val note = PtNoteBuilder.forSentence(
            sentenceCard(), imageFilename = "a\"b.jpg", audioFilename = null,
            wordAudioFilenames = emptyMap(), audioCredit = null,
        )
        assertFalse(note.picture.contains("src=\"a\"b.jpg\""))
        assertTrue(note.picture.contains("a&quot;b.jpg"))
    }

    @Test fun `sentence audio is the bare sound tag`() {
        val note = PtNoteBuilder.forSentence(
            sentenceCard(), imageFilename = null, audioFilename = "s.ogg",
            wordAudioFilenames = emptyMap(), audioCredit = null,
        )
        assertEquals("[sound:s.ogg]", note.sentenceAudio)
    }
}
