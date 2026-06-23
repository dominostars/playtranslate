package com.playtranslate

import com.playtranslate.language.SourceLangId
import com.playtranslate.ui.AnkiCardOutputBuilder
import com.playtranslate.ui.SentenceAnkiContentFragment
import com.playtranslate.ui.SentenceAnkiHtmlBuilder
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
        val card = SentenceAnkiContentFragment.CardData(
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
}
