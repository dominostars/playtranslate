package com.playtranslate.audio.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [AudioContainer.sniff]: a voice service's reply named by its first bytes,
 * and a reply that is not one of the four formats named as none.
 */
class AudioContainerTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
    private fun ascii(text: String, pad: Int = 16) = text.toByteArray(Charsets.US_ASCII) + ByteArray(pad)

    @Test fun `an MP3 is known by its ID3 tag or by its first frame`() {
        assertEquals(AudioContainer.MP3, AudioContainer.sniff(ascii("ID3\u0004")))
        // MPEG-1 Layer III, 128 kbps, 44.1 kHz: what a tagless MP3 starts with.
        assertEquals(AudioContainer.MP3, AudioContainer.sniff(bytes(0xFF, 0xFB, 0x90, 0x64)))
        // MPEG-2 Layer III, 24 kHz: the low sample rates services send for speech.
        assertEquals(AudioContainer.MP3, AudioContainer.sniff(bytes(0xFF, 0xF3, 0x64, 0xC4)))
    }

    @Test fun `a WAV needs both its RIFF and its WAVE tags`() {
        assertEquals(AudioContainer.WAV, AudioContainer.sniff(ascii("RIFF").copyOf(8) + ascii("WAVEfmt ")))
        assertNull(AudioContainer.sniff(ascii("RIFF").copyOf(8) + ascii("AVI LIST")))
        assertNull(AudioContainer.sniff(ascii("RIFF", pad = 0)))
    }

    @Test fun `an Ogg stream and an MP4 audio file are known by their tags`() {
        assertEquals(AudioContainer.OGG, AudioContainer.sniff(ascii("OggS")))
        assertEquals(AudioContainer.M4A, AudioContainer.sniff(bytes(0, 0, 0, 0x1C) + ascii("ftypM4A ")))
    }

    @Test fun `a raw AAC stream is not taken for an MP3`() {
        // ADTS: the same eleven sync bits as an MPEG audio frame, layer 00.
        assertNull(AudioContainer.sniff(bytes(0xFF, 0xF1, 0x50, 0x80)))
        assertNull(AudioContainer.sniff(bytes(0xFF, 0xF9, 0x50, 0x80)))
    }

    @Test fun `a frame header with a reserved field is not an MP3`() {
        assertNull("reserved version", AudioContainer.sniff(bytes(0xFF, 0xEB, 0x90, 0x64)))
        assertNull("bad bitrate", AudioContainer.sniff(bytes(0xFF, 0xFB, 0xF0, 0x64)))
        assertNull("reserved sample rate", AudioContainer.sniff(bytes(0xFF, 0xFB, 0x9C, 0x64)))
    }

    @Test fun `an error body, raw samples and an empty reply are no format`() {
        assertNull(AudioContainer.sniff("""{"error":{"message":"Incorrect API key"}}""".toByteArray()))
        assertNull(AudioContainer.sniff("""{"audio":"SUQzBAAAAAAA"}""".toByteArray()))
        assertNull(AudioContainer.sniff(bytes(0x00, 0x01, 0xFE, 0xFF, 0x02, 0x00)))
        assertNull(AudioContainer.sniff(ByteArray(0)))
        assertNull(AudioContainer.sniff(bytes(0xFF, 0xFB)))
    }
}
