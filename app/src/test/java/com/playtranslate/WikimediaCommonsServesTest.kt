package com.playtranslate

import com.playtranslate.audio.AudioRequest
import com.playtranslate.audio.sources.WikimediaCommonsAudioSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Commons holds word-level recordings only. [WikimediaCommonsAudioSource.serves]
 * is the declarative capability the picker reads to skip the Commons section
 * entirely for a sentence request — so a sentence selection never renders an
 * empty "No results" Commons row nor queries the source for nothing.
 */
class WikimediaCommonsServesTest {

    @Test fun serves_words_but_not_sentences() {
        assertTrue(WikimediaCommonsAudioSource.serves(AudioRequest.Kind.WORD))
        assertFalse(WikimediaCommonsAudioSource.serves(AudioRequest.Kind.SENTENCE))
    }
}
