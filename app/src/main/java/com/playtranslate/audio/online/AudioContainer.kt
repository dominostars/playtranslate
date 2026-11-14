package com.playtranslate.audio.online

/**
 * The audio file formats a voice service's reply is taken in, each with the
 * extension its clip is stored under.
 *
 * The format is read from the reply's first bytes, never from its
 * `Content-Type`: OpenAI answers `/v1/audio/speech` as
 * `application/octet-stream` whatever it was asked for. And the extension
 * matters past playback: [com.playtranslate.audio.AudioCache] names a clip's
 * file by the extension its key ends in, [com.playtranslate.PtFileProvider]
 * derives the file's MIME type from that name, and AnkiDroid stores the card's
 * media under the extension of that MIME type. A clip stored under a wrong
 * extension is a card whose audio some Anki clients will not play.
 *
 * The four formats are the ones that path is known to carry. A reply that is
 * none of them ([sniff] returns null: raw PCM, a JSON error body, audio
 * wrapped in JSON, a raw AAC stream) is a failure, not a clip.
 */
enum class AudioContainer(val ext: String) {
    MP3("mp3"),
    WAV("wav"),
    OGG("ogg"),
    M4A("m4a");

    companion object {
        /** The format [bytes] start as, or null when they are none of the four. */
        fun sniff(bytes: ByteArray): AudioContainer? = when {
            bytes.hasAscii(0, "RIFF") && bytes.hasAscii(8, "WAVE") -> WAV
            bytes.hasAscii(0, "OggS") -> OGG
            bytes.hasAscii(4, "ftyp") -> M4A
            bytes.hasAscii(0, "ID3") -> MP3
            bytes.startsWithMpegAudioFrame() -> MP3
            else -> null
        }

        private fun ByteArray.hasAscii(offset: Int, tag: String): Boolean {
            if (size < offset + tag.length) return false
            for (i in tag.indices) if (this[offset + i] != tag[i].code.toByte()) return false
            return true
        }

        /**
         * An MPEG audio frame header: eleven sync bits, then a version, a
         * layer, a bitrate and a sample rate that are none of the reserved
         * values. The layer test is the one that tells an MP3 from a raw AAC
         * (ADTS) stream, whose header has the same sync bits and a layer
         * field of 00.
         */
        private fun ByteArray.startsWithMpegAudioFrame(): Boolean {
            if (size < 3) return false
            val b0 = this[0].toInt() and 0xFF
            val b1 = this[1].toInt() and 0xFF
            val b2 = this[2].toInt() and 0xFF
            val sync = b0 == 0xFF && (b1 and 0xE0) == 0xE0
            val version = (b1 shr 3) and 0x03
            val layer = (b1 shr 1) and 0x03
            val bitrate = (b2 shr 4) and 0x0F
            val sampleRate = (b2 shr 2) and 0x03
            return sync && version != 1 && layer != 0 && bitrate != 0x0F && sampleRate != 3
        }
    }
}
