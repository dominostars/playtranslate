package com.playtranslate.ui

import com.playtranslate.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [shortfallRes] is the single place every send surface reads "what
 * didn't make it onto the card" from, so its precedence is pinned here.
 * A dropped screenshot has to be named even when audio dropped in the
 * same send (one AnkiDroid media-store failure usually takes both, and
 * the silently-missing image is exactly the report this resolver exists
 * for), and a clean send has to stay silent so the review sheets don't
 * toast over their own dismissal.
 */
class AnkiMediaShortfallTest {

    @Test fun `a complete card reports nothing`() {
        assertNull(AnkiSendResult.Success().shortfallRes())
    }

    @Test fun `a dropped screenshot is named`() {
        assertEquals(
            R.string.anki_added_no_screenshot,
            AnkiSendResult.Success(imageDropped = true).shortfallRes(),
        )
    }

    @Test fun `dropped audio keeps its own message`() {
        assertEquals(
            R.string.anki_added_no_audio,
            AnkiSendResult.Success(audioDropped = true).shortfallRes(),
        )
        assertEquals(
            R.string.anki_added_no_audio,
            AnkiSendResult.Success(wordAudioDropped = true).shortfallRes(),
        )
    }

    @Test fun `losing screenshot and audio together names both`() {
        assertEquals(
            R.string.anki_added_no_screenshot_or_audio,
            AnkiSendResult.Success(imageDropped = true, audioDropped = true)
                .shortfallRes(),
        )
        assertEquals(
            R.string.anki_added_no_screenshot_or_audio,
            AnkiSendResult.Success(imageDropped = true, wordAudioDropped = true)
                .shortfallRes(),
        )
    }

    @Test fun `simplified definitions are named on an otherwise clean send`() {
        assertEquals(
            R.string.anki_added_simplified,
            AnkiSendResult.Success(definitionsSimplified = true).shortfallRes(),
        )
    }

    @Test fun `the dropped-screenshot verdict needs a request, no upload, and a field to hold it`() {
        // A crop the pin could not write arrives as a request with no file:
        // the same verdict as a failed upload.
        assertTrue(imageDroppedVerdict(requested = true, uploadedName = null, holdsPicture = true))
        assertTrue(imageDroppedVerdict(requested = true, uploadedName = "", holdsPicture = true))
        // A card type with no Picture field never reports a loss it could not show.
        assertFalse(imageDroppedVerdict(requested = true, uploadedName = null, holdsPicture = false))
        // Nothing attached, nothing lost; an upload that landed is no loss.
        assertFalse(imageDroppedVerdict(requested = false, uploadedName = null, holdsPicture = true))
        assertFalse(imageDroppedVerdict(requested = true, uploadedName = "shot.jpg", holdsPicture = true))
    }

    @Test fun `a media loss outranks the simplified note`() {
        // Missing media is data the card will never have; simplified
        // definitions still carry every sense as text.
        assertEquals(
            R.string.anki_added_no_screenshot,
            AnkiSendResult.Success(imageDropped = true, definitionsSimplified = true)
                .shortfallRes(),
        )
    }
}
