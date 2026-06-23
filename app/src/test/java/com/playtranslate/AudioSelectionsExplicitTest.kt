package com.playtranslate

import com.playtranslate.audio.Attribution
import com.playtranslate.audio.AudioSelection
import com.playtranslate.audio.AudioSelections
import com.playtranslate.audio.sources.WikimediaCommonsAudioSource
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression test for the adversarial-review finding: an explicit Wikimedia pick
 * must reach `source.toFile` with its URL (and attribution) intact, otherwise an
 * uncached selection can't be fetched and the send path silently substitutes a
 * different clip. [AudioSelections.explicitCandidate] is exactly the candidate
 * handed to the source, so this asserts the locator + attribution survive.
 */
class AudioSelectionsExplicitTest {

    @Test fun explicitCandidate_preserves_locator_and_attribution() {
        val attr = Attribution("Jane Doe", "CC BY-SA 4.0", "Wikimedia Commons", "https://commons/desc")
        val sel = AudioSelection.Explicit(
            sourceId = WikimediaCommonsAudioSource.ID,
            key = "LL-Q150-Jane-chat.wav",
            locator = "https://upload.wikimedia.org/LL-Q150-Jane-chat.wav",
            attribution = attr,
        )

        val candidate = AudioSelections.explicitCandidate(sel)

        assertEquals(WikimediaCommonsAudioSource.ID, candidate.sourceId)
        assertEquals("LL-Q150-Jane-chat.wav", candidate.key)
        // The URL must survive — without it, WikimediaCommonsAudioSource.toFile
        // returns null for an uncached pick (the original bug).
        assertEquals("https://upload.wikimedia.org/LL-Q150-Jane-chat.wav", candidate.locator)
        assertEquals("Jane Doe", candidate.attribution?.author)
        assertEquals("CC BY-SA 4.0", candidate.attribution?.license)
    }
}
