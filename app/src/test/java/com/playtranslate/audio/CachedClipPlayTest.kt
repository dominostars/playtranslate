package com.playtranslate.audio

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.audio.sources.WikimediaCommonsAudioSource
import com.playtranslate.language.SourceLangId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * A cached clip that turns out to be no audio: [RecordingPlayer.playCached]
 * drops it, so its source is asked again, where [RecordingPlayer.play]
 * leaves any other file alone. What decoding gives is set here
 * ([RecordingPlayer.decoder]); a JVM has no media codecs.
 */
@RunWith(RobolectricTestRunner::class)
class CachedClipPlayTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val savedDecoder = RecordingPlayer.decoder
    private val attribution = Attribution("Jane", "CC BY", "Wikimedia Commons", "https://x")

    @Before fun setUp() {
        File(ctx.cacheDir, "audioCache").deleteRecursively()
    }

    @After fun tearDown() {
        RecordingPlayer.decoder = savedDecoder
        File(ctx.cacheDir, "audioCache").deleteRecursively()
    }

    /** Runs [block] off the main thread while the main looper is pumped:
     *  the player stops what is playing on the main thread first. */
    private fun <T> offMain(block: suspend () -> T): T {
        val done = AtomicReference<Result<T>?>()
        thread { done.set(runCatching { runBlocking { block() } }) }
        val deadline = System.currentTimeMillis() + 5_000
        while (done.get() == null) {
            assertTrue("timed out waiting for the play", System.currentTimeMillis() < deadline)
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        return done.get()!!.getOrThrow()
    }

    private fun cached(key: String = "Ja-行く.ogg"): File =
        runBlocking { AudioCache(ctx).putClip(WikimediaCommonsAudioSource.ID, key, byteArrayOf(1, 2, 3), attribution) }

    private fun stillCached(key: String = "Ja-行く.ogg"): Boolean =
        runBlocking { AudioCache(ctx).getClip(WikimediaCommonsAudioSource.ID, key) != null }

    @Test fun `a cached clip that does not decode is dropped, with its attribution`() {
        RecordingPlayer.decoder = { _, _ -> RecordingPlayer.Decoded.Unreadable }
        val clip = cached()
        val outcome = offMain { RecordingPlayer.playCached(ctx, clip, awaitCompletion = true, onStart = null) }
        assertEquals(PlayOutcome.Failed(recoverable = true), outcome)
        assertFalse(stillCached())
        assertNull(runBlocking { AudioCache(ctx).readAttribution(WikimediaCommonsAudioSource.ID, "Ja-行く.ogg") })
    }

    @Test fun `only the clip that did not decode is dropped`() {
        val other = cached("Ja-見る.ogg")
        val clip = cached()
        RecordingPlayer.decoder = { file, _ ->
            if (file == clip) RecordingPlayer.Decoded.Unreadable else RecordingPlayer.Decoded.Declined
        }
        offMain { RecordingPlayer.playCached(ctx, clip, awaitCompletion = true, onStart = null) }
        assertFalse(stillCached())
        assertTrue(other.exists())
        assertTrue(stillCached("Ja-見る.ogg"))
    }

    @Test fun `a cached clip the player declines stays`() {
        RecordingPlayer.decoder = { _, _ -> RecordingPlayer.Decoded.Declined }
        val clip = cached()
        val outcome = offMain { RecordingPlayer.playCached(ctx, clip, awaitCompletion = true, onStart = null) }
        assertEquals(PlayOutcome.Failed(recoverable = true), outcome)
        assertTrue(stillCached())
        assertNotNull(runBlocking { AudioCache(ctx).readAttribution(WikimediaCommonsAudioSource.ID, "Ja-行く.ogg") })
    }

    @Test fun `a decoder that throws is a failed play, and the clip stays`() {
        RecordingPlayer.decoder = { _, _ -> error("boom") }
        val clip = cached()
        val outcome = offMain { RecordingPlayer.playCached(ctx, clip, awaitCompletion = true, onStart = null) }
        assertEquals(PlayOutcome.Failed(recoverable = true), outcome)
        assertTrue(stillCached())
    }

    @Test fun `a file played as any other is never deleted, whatever decoding gives`() {
        // A game-audio preview, a file of the user's own: not this player's
        // to delete.
        RecordingPlayer.decoder = { _, _ -> RecordingPlayer.Decoded.Unreadable }
        val own = File.createTempFile("preview", ".wav").apply { writeBytes(byteArrayOf(1, 2, 3)); deleteOnExit() }
        val outcome = offMain { RecordingPlayer.play(ctx, own, awaitCompletion = true, onStart = null) }
        assertEquals(PlayOutcome.Failed(recoverable = true), outcome)
        assertTrue(own.exists())
    }

    @Test fun `a Commons recording that does not decode is dropped when played, and one only declined stays`() {
        val req = AudioRequest.word("行く", "いく", SourceLangId.JA)
        val candidate = AudioCandidate(
            sourceId = WikimediaCommonsAudioSource.ID,
            key = "Ja-行く.ogg",
            title = CandidateLabel.Text("Ja-行く.ogg"),
            attribution = attribution,
        )
        cached()
        RecordingPlayer.decoder = { _, _ -> RecordingPlayer.Decoded.Declined }
        offMain { WikimediaCommonsAudioSource.play(ctx, candidate, req, awaitCompletion = true, onStart = null) }
        assertTrue(stillCached())

        RecordingPlayer.decoder = { _, _ -> RecordingPlayer.Decoded.Unreadable }
        val outcome =
            offMain { WikimediaCommonsAudioSource.play(ctx, candidate, req, awaitCompletion = true, onStart = null) }
        assertEquals(PlayOutcome.Failed(recoverable = true), outcome)
        assertFalse(stillCached())
    }
}
