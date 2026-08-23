package com.playtranslate

import com.playtranslate.language.SourceLangId
import com.playtranslate.ui.AnkiCardOutputBuilder
import com.playtranslate.ui.PtNoteBuilder
import com.playtranslate.ui.SentenceAnkiContentView
import com.playtranslate.ui.SentenceAnkiHtmlBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Guards the attribution placement (adversarial-review finding): a Commons
 * clip's CC credit must travel on the audio field it belongs to, not only the
 * sentence-audio field — otherwise a card type that maps only word audio ships
 * CC audio with no credit. Covers both the sentence card's per-word audio and
 * the standalone word card's headword audio.
 */
@RunWith(RobolectricTestRunner::class)
class AnkiCardWordAudioCreditTest {

    @Test fun word_audio_field_carries_its_credit() {
        val card = SentenceAnkiContentView.CardData(
            source = "cat",
            target = "gato",
            words = listOf(SentenceAnkiHtmlBuilder.WordEntry("cat", "", "gato", 0)),
            selectedWords = setOf("cat"),
            screenshotPath = null,
            sourceLangId = SourceLangId.EN,
            targetWordAudioWords = setOf("cat"),
        )

        val out = AnkiCardOutputBuilder.forSentence(
            cardData = card,
            imageFilename = null,
            wordAudioFilenames = mapOf("cat" to "cat.ogg"),
            wordAudioCredit = "Jane (CC BY-SA 4.0), via Wikimedia Commons",
        )

        assertTrue("word audio sound tag present", out.wordAudio.contains("[sound:cat.ogg]"))
        assertTrue("credit travels with word audio", out.wordAudio.contains("Jane"))
    }

    @Test fun word_card_audio_field_carries_commons_credit() {
        val out = AnkiCardOutputBuilder.forWord(
            word = "cat",
            reading = "",
            pos = "noun",
            definitionHtml = "<div>feline</div>",
            freqScore = 0,
            pitch = emptyList(),
            frequencies = emptyList(),
            imageFilename = null,
            sourceLangId = SourceLangId.EN,
            audioFilename = "cat.ogg",
            audioCredit = "Jane (CC BY-SA 4.0), via Wikimedia Commons",
        )

        assertTrue("word audio sound tag present", out.wordAudio.contains("[sound:cat.ogg]"))
        assertTrue("credit travels with word audio", out.wordAudio.contains("Jane"))
    }

    // The default PlayTranslate models invert the placement: their
    // templates own the rendering, so the credit lives in its own
    // AudioCredit field and the audio field stays a bare [sound:] tag.

    @Test fun pt_word_note_splits_credit_from_the_audio_field() {
        val note = PtNoteBuilder.forWord(
            word = "cat", reading = "", pos = "noun",
            definitionHtml = "<div>feline</div>", examplesHtml = "",
            freqScore = 0, pitch = emptyList(), frequencies = emptyList(),
            imageFilename = null, audioFilename = "cat.ogg",
            audioCredit = "Jane (CC BY-SA 4.0), via Wikimedia Commons",
        )
        assertEquals("[sound:cat.ogg]", note.wordAudio)
        assertTrue("credit lands in AudioCredit", note.audioCredit.contains("Jane"))
    }

    @Test fun pt_sentence_note_splits_credit_from_the_audio_field() {
        val card = SentenceAnkiContentView.CardData(
            source = "cat",
            target = "gato",
            words = listOf(SentenceAnkiHtmlBuilder.WordEntry("cat", "", "gato", 0)),
            selectedWords = setOf("cat"),
            screenshotPath = null,
            sourceLangId = SourceLangId.EN,
            targetWordAudioWords = setOf("cat"),
        )
        val note = PtNoteBuilder.forSentence(
            cardData = card,
            imageFilename = null,
            audioFilename = "sentence.ogg",
            wordAudioFilenames = mapOf("cat" to "cat.ogg"),
            audioCredit = "Jane (CC BY-SA 4.0), via Wikimedia Commons",
        )
        assertEquals("[sound:sentence.ogg]", note.sentenceAudio)
        assertTrue("credit lands in AudioCredit", note.audioCredit.contains("Jane"))
        assertTrue("per-word audio rides the words table",
            note.wordsTable.contains("[sound:cat.ogg]"))
    }
}
