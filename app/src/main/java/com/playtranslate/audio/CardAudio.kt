package com.playtranslate.audio

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.playtranslate.language.PackIntegrity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A card's audio, in a format Anki plays wherever the card is studied.
 *
 * A source hands the card pipeline "a file", and a file that plays on this
 * device need not play on the user's other Anki clients: Ogg and Opus do
 * not play on AnkiMobile or AnkiWeb, and those are what recordings mostly
 * come as (an imported android.db file, Wikimedia Commons, some voice
 * services). No source can know where a card will be studied, so none
 * decides this: [AudioSelections.toFile], the one place a card's audio is
 * resolved, passes every clip through [forCard].
 *
 * A clip of a type every client plays goes on the card as it is. Any other
 * is decoded and written out again as AAC in an MP4 container, the format
 * the game-audio clips are already made in for the same reason. The
 * converted clip is kept in the audio cache among its source's clips, so a
 * clip is converted once and a source's removal takes its converted clips
 * with it.
 *
 * A conversion is made from a clip's bytes and from nothing else, so the
 * bytes are what it is kept under ([PackIntegrity.sha256Hex] of them). Not
 * the candidate's key: a recording's key names one recording, but a voice's
 * key names the voice, and the same voice speaks every word. Not the clip's
 * path either: a clip the cache evicted and its source fetched again comes
 * back at the same path, and need not come back the same (a voice service
 * renders the same words anew; a Commons file can be uploaded again).
 */
internal object CardAudio {

    private const val TAG = "PtAudio"

    /**
     * The file types that go on a card as they are. MP3 is what Anki's
     * manual recommends for every client; WAV (the device voice's) and M4A
     * (game audio's) are what this app's own cards have carried to
     * AnkiDroid and AnkiMobile. Decided by the extension, which is what
     * names the card's media file and what a player goes by.
     */
    private val PLAYS_EVERYWHERE = setOf("mp3", "wav", "m4a")

    private const val CONVERTED_SUFFIX = ".m4a"

    fun playsEverywhere(clip: File): Boolean = clip.extension.lowercase() in PLAYS_EVERYWHERE

    /** What turns a clip into the bytes of an M4A file, or null when it
     *  cannot. A seam for JVM tests, which have no media codecs. The second
     *  argument is a directory for the encoder's working file. */
    @Volatile
    @VisibleForTesting
    internal var transcoder: (File, File) -> ByteArray? = ::toM4a

    /**
     * [clip], a file [sourceId] resolved, as a file a card can carry:
     * [clip] itself when every client plays its type, else its conversion,
     * from the cache or made now.
     *
     * Null when the clip is of another type and cannot be converted (it
     * does not decode, or the device has no encoder for its sample rate).
     * The caller treats that as the source having given nothing: a card
     * never carries a clip that may be silent where it is studied.
     */
    suspend fun forCard(ctx: Context, sourceId: String, clip: File): File? {
        if (playsEverywhere(clip)) return clip
        val cache = AudioCache(ctx)
        val convertedKey = PackIntegrity.sha256Hex(clip) + CONVERTED_SUFFIX
        cache.getClip(sourceId, convertedKey)?.let { return it }
        val bytes = withContext(Dispatchers.IO) { transcoder(clip, ctx.cacheDir) }
        if (bytes == null || bytes.isEmpty()) {
            Log.w(TAG, "CardAudio: ${clip.name} of $sourceId could not be converted for a card")
            return null
        }
        return cache.putClip(sourceId, convertedKey, bytes, attribution = null)
    }

    private fun toM4a(clip: File, tempDir: File): ByteArray? {
        val audio = RecordingPlayer.decodeAsIs(clip) ?: return null
        val samples = mono(audio.data, audio.channelCount)
        if (samples.isEmpty()) return null
        return try {
            GameAudioClip.encodeM4a(samples, audio.sampleRate, tempDir)
        } catch (e: Exception) {
            // The encoder refusing the format (a sample rate AAC has no
            // profile for) or failing on it.
            Log.w(TAG, "CardAudio: encoding ${clip.name} failed", e)
            null
        }
    }

    /**
     * 16-bit little-endian PCM of [channels] interleaved channels as one
     * channel of samples: each frame's channels averaged. The encoder here
     * writes one channel, which is what a recording of one voice is.
     */
    @VisibleForTesting
    internal fun mono(pcm: ByteArray, channels: Int): ShortArray {
        val perFrame = channels.coerceAtLeast(1)
        val frames = pcm.size / 2 / perFrame
        return ShortArray(frames) { frame ->
            var sum = 0
            for (channel in 0 until perFrame) {
                val at = 2 * (frame * perFrame + channel)
                sum += (pcm[at + 1].toInt() shl 8) or (pcm[at].toInt() and 0xFF)
            }
            (sum / perFrame).toShort()
        }
    }
}
