package com.playtranslate.ui

import com.playtranslate.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [mediaShortfallRes] is the single place every send surface reads "what
 * didn't make it onto the card" from, so its precedence is pinned here.
 * A dropped screenshot has to be named even when audio dropped in the
 * same send (one AnkiDroid media-store failure usually takes both, and
 * the silently-missing image is exactly the report this resolver exists
 * for), and a clean send has to stay silent so the review sheets don't
 * toast over their own dismissal.
 */
class AnkiMediaShortfallTest {

    @Test fun `a complete card reports nothing`() {
        assertNull(AnkiSendResult.Success().mediaShortfallRes())
    }

    @Test fun `a dropped screenshot is named`() {
        assertEquals(
            R.string.anki_added_no_screenshot,
            AnkiSendResult.Success(imageDropped = true).mediaShortfallRes(),
        )
    }

    @Test fun `dropped audio keeps its own message`() {
        assertEquals(
            R.string.anki_added_no_audio,
            AnkiSendResult.Success(audioDropped = true).mediaShortfallRes(),
        )
        assertEquals(
            R.string.anki_added_no_audio,
            AnkiSendResult.Success(wordAudioDropped = true).mediaShortfallRes(),
        )
    }

    @Test fun `losing screenshot and audio together names both`() {
        assertEquals(
            R.string.anki_added_no_screenshot_or_audio,
            AnkiSendResult.Success(imageDropped = true, audioDropped = true)
                .mediaShortfallRes(),
        )
        assertEquals(
            R.string.anki_added_no_screenshot_or_audio,
            AnkiSendResult.Success(imageDropped = true, wordAudioDropped = true)
                .mediaShortfallRes(),
        )
    }
}
