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
 * Guards the attribution placement (adversarial-review finding): a per-word
 * Commons clip's CC credit must travel on the WORD_AUDIO field, not only the
 * sentence-audio field — otherwise a card type that maps only word audio ships
 * CC audio with no credit.
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
}
