package com.playtranslate.audio

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.language.SourceLangId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * [CardAudio]: a card's clip goes on the card as it is when every Anki
 * client plays its type, and converted when not; and [AudioSelections.toFile],
 * the one place a card's audio is resolved, puts every source's clip
 * through it, a pick's as the default's. What converts is set here
 * ([CardAudio.transcoder]); a JVM has no media codecs.
 */
@RunWith(RobolectricTestRunner::class)
class CardAudioTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val savedTranscoder = CardAudio.transcoder
    private val req = AudioRequest.word("食べる", "たべる", SourceLangId.JA)

    /** Clips converted so far, by name. */
    private val converted = mutableListOf<String>()

    @Before fun setUp() {
        File(ctx.cacheDir, "audioCache").deleteRecursively()
        File(ctx.cacheDir, "card-audio-test").deleteRecursively()
        CardAudio.transcoder = { clip, _ ->
            converted += clip.name
            "m4a of ".toByteArray() + clip.readBytes()
        }
    }

    @After fun tearDown() {
        CardAudio.transcoder = savedTranscoder
        File(ctx.cacheDir, "audioCache").deleteRecursively()
        File(ctx.cacheDir, "card-audio-test").deleteRecursively()
    }

    private fun clip(name: String, bytes: ByteArray = name.toByteArray()): File =
        File(ctx.cacheDir, "card-audio-test/$name").apply { parentFile?.mkdirs(); writeBytes(bytes) }

    // ── Which clips a card carries as they are ───────────────────────────

    @Test fun `MP3, WAV and M4A play on every client, and Ogg, Opus and anything unknown do not`() {
        for (name in listOf("a.mp3", "a.MP3", "a.wav", "a.m4a")) {
            assertTrue(name, CardAudio.playsEverywhere(File(name)))
        }
        for (name in listOf("a.opus", "a.ogg", "a.oga", "a.flac", "a.webm", "a.audio", "noextension")) {
            assertFalse(name, CardAudio.playsEverywhere(File(name)))
        }
    }

    @Test fun `a clip every client plays goes on the card as it is, unconverted`() = runBlocking {
        for (name in listOf("word.mp3", "word.wav", "word.m4a")) {
            val file = clip(name)
            assertSame(file, CardAudio.forCard(ctx, "local_audio", file))
        }
        assertTrue(converted.isEmpty())
        assertFalse("nothing is written for a clip that needed nothing", File(ctx.cacheDir, "audioCache").exists())
    }

    // ── Conversion ───────────────────────────────────────────────────────

    @Test fun `an Opus clip is converted to M4A, kept in the cache beside its source's clips`() = runBlocking {
        val opus = clip("taberu.opus")
        val card = CardAudio.forCard(ctx, "local_audio", opus)!!
        assertEquals("m4a", card.extension)
        assertEquals(File(ctx.cacheDir, "audioCache/local_audio"), card.parentFile)
        assertArrayEquals("m4a of taberu.opus".toByteArray(), card.readBytes())
        assertArrayEquals("the clip it was made from is left as it was", "taberu.opus".toByteArray(), opus.readBytes())
    }

    @Test fun `a clip is converted once, and then served from the cache`() = runBlocking {
        val ogg = clip("iku.ogg")
        val first = CardAudio.forCard(ctx, "wikimedia_commons", ogg)
        val second = CardAudio.forCard(ctx, "wikimedia_commons", ogg)
        assertEquals(first, second)
        assertEquals(listOf("iku.ogg"), converted)
    }

    @Test fun `a conversion belongs to its clip's bytes, so two clips of one name are two conversions`() = runBlocking {
        val a = CardAudio.forCard(ctx, "local_audio", clip("a.opus", byteArrayOf(1)))!!
        val b = CardAudio.forCard(ctx, "local_audio", clip("b/a.opus", byteArrayOf(2)))!!
        assertTrue(a != b)
        assertArrayEquals("m4a of ".toByteArray() + byteArrayOf(1), a.readBytes())
        assertArrayEquals("m4a of ".toByteArray() + byteArrayOf(2), b.readBytes())
    }

    @Test fun `a clip that comes back changed at the same path is converted again`() = runBlocking {
        // As when the cache evicts a clip and its source fetches it again:
        // the same key, so the same path, and not the same audio.
        val cache = AudioCache(ctx)
        val original = cache.putClip("online_tts", "identity.ogg", "first answer".toByteArray(), null)
        val first = CardAudio.forCard(ctx, "online_tts", original)!!
        assertArrayEquals("m4a of first answer".toByteArray(), first.readBytes())

        original.delete()
        val again = cache.putClip("online_tts", "identity.ogg", "second answer".toByteArray(), null)
        assertEquals("the same place in the cache", original, again)
        val second = CardAudio.forCard(ctx, "online_tts", again)!!
        assertArrayEquals("the card carries the clip that is there now", "m4a of second answer".toByteArray(), second.readBytes())
    }

    @Test fun `the same audio in two files is one conversion`() = runBlocking {
        val a = CardAudio.forCard(ctx, "local_audio", clip("one.opus", "same audio".toByteArray()))
        val b = CardAudio.forCard(ctx, "local_audio", clip("elsewhere/two.opus", "same audio".toByteArray()))
        assertEquals(a, b)
        assertEquals("converted once", 1, converted.size)
    }

    @Test fun `one file resolved by two sources is converted into each source's own clips`() = runBlocking {
        val shared = clip("shared.opus")
        val a = CardAudio.forCard(ctx, "local_audio", shared)!!
        val b = CardAudio.forCard(ctx, "wikimedia_commons", shared)!!
        assertEquals(File(ctx.cacheDir, "audioCache/local_audio"), a.parentFile)
        assertEquals(File(ctx.cacheDir, "audioCache/wikimedia_commons"), b.parentFile)
    }

    @Test fun `a clip that cannot be converted is no clip for a card, and nothing is kept`() = runBlocking {
        CardAudio.transcoder = { _, _ -> null }
        assertNull(CardAudio.forCard(ctx, "local_audio", clip("a.opus")))
        CardAudio.transcoder = { _, _ -> ByteArray(0) }
        assertNull("an empty conversion is none", CardAudio.forCard(ctx, "local_audio", clip("a.opus")))
        assertTrue(File(ctx.cacheDir, "audioCache/local_audio").listFiles().orEmpty().none { it.extension == "m4a" })
    }

    @Test fun `removing a source's clips takes its converted clips with them`() = runBlocking {
        val card = CardAudio.forCard(ctx, "local_audio", clip("a.opus"))!!
        AudioCache(ctx).dropClips("local_audio")
        assertFalse(card.exists())
    }

    @Test fun `interleaved channels become one channel, each frame's samples averaged`() {
        fun pcm(vararg samples: Int): ByteArray =
            ByteArray(samples.size * 2).also { out ->
                samples.forEachIndexed { i, s -> out[2 * i] = (s and 0xFF).toByte(); out[2 * i + 1] = ((s shr 8) and 0xFF).toByte() }
            }
        assertArrayEquals(shortArrayOf(100, -200, 32767, -32768), CardAudio.mono(pcm(100, -200, 32767, -32768), 1))
        assertArrayEquals(shortArrayOf(150, -150, 0), CardAudio.mono(pcm(100, 200, -100, -200, 32767, -32767), 2))
        assertArrayEquals("a last frame that is not whole is left out", shortArrayOf(150), CardAudio.mono(pcm(100, 200, 300), 2))
        assertArrayEquals("no channel count is one channel", shortArrayOf(7), CardAudio.mono(pcm(7), 0))
        assertEquals(0, CardAudio.mono(ByteArray(0), 2).size)
    }

    // ── Every card clip goes through it ──────────────────────────────────

    /** A source that offers one clip, the file it is given. */
    private class Source(override val id: String, private val file: File?) : AudioSource {
        override fun label(ctx: Context) = id
        override val toggleable = false
        override val remote = false
        override fun isEnabled(ctx: Context) = true
        override fun setEnabled(ctx: Context, on: Boolean) {}
        override suspend fun candidates(ctx: Context, req: AudioRequest) = emptyList<AudioCandidate>()
        override suspend fun defaultCandidate(ctx: Context, req: AudioRequest) =
            AudioCandidate(id, "key-of-$id", CandidateLabel.Text(id), attribution = credit.takeIf { id == "wikimedia_commons" })
        override suspend fun play(
            ctx: Context, candidate: AudioCandidate, req: AudioRequest,
            awaitCompletion: Boolean, onStart: (() -> Unit)?,
        ): PlayOutcome = PlayOutcome.Played
        override suspend fun toFile(ctx: Context, candidate: AudioCandidate, req: AudioRequest): File? = file
    }

    private suspend fun auto(vararg sources: AudioSource) =
        AudioSelections.toFile(ctx, AudioSelection.Auto, req, enabledInOrder = { sources.toList() }, sourceFor = { sources.first() })

    private suspend fun pick(source: AudioSource, attribution: Attribution? = null) =
        AudioSelections.toFile(
            ctx, AudioSelection.Explicit(source.id, "picked", attribution = attribution), req,
            enabledInOrder = { emptyList() }, sourceFor = { source },
        )

    @Test fun `the default card audio is converted when its source's clip is one not every client plays`() = runBlocking {
        val resolved = auto(Source("local_audio", clip("taberu.opus")), Source("tts", clip("voice.wav")))!!
        assertEquals("m4a", resolved.file.extension)
        assertArrayEquals("m4a of taberu.opus".toByteArray(), resolved.file.readBytes())
        assertFalse("a cached clip is not thrown away after the send", resolved.ephemeral)
        assertFalse(resolved.fellBack)
    }

    /** A voice: one candidate key whatever it is asked to say, and a clip
     *  of its own for each thing said, as a voice service has. */
    private inner class Voice(override val id: String) : AudioSource {
        override fun label(ctx: Context) = id
        override val toggleable = false
        override val remote = false
        override fun isEnabled(ctx: Context) = true
        override fun setEnabled(ctx: Context, on: Boolean) {}
        override suspend fun candidates(ctx: Context, req: AudioRequest) = emptyList<AudioCandidate>()
        override suspend fun defaultCandidate(ctx: Context, req: AudioRequest) =
            AudioCandidate(id, "the-voice", CandidateLabel.Text("the voice"))
        override suspend fun play(
            ctx: Context, candidate: AudioCandidate, req: AudioRequest,
            awaitCompletion: Boolean, onStart: (() -> Unit)?,
        ): PlayOutcome = PlayOutcome.Played
        override suspend fun toFile(ctx: Context, candidate: AudioCandidate, req: AudioRequest): File =
            clip("spoken-${req.surface}.ogg", "ogg of ${req.surface}".toByteArray())
    }

    @Test fun `two things said in one voice are two conversions, each card carrying its own words`() = runBlocking {
        val voice = Voice("online_tts")
        suspend fun card(text: String) = AudioSelections.toFile(
            ctx, AudioSelection.Auto, AudioRequest.word(text, null, SourceLangId.JA),
            enabledInOrder = { listOf(voice) }, sourceFor = { voice },
        )!!.file
        val first = card("行く")
        val second = card("食べる")
        assertArrayEquals("m4a of ogg of 行く".toByteArray(), first.readBytes())
        assertArrayEquals("the voice is the same; the words are not", "m4a of ogg of 食べる".toByteArray(), second.readBytes())
        // And picked by hand, where the pick's key is the voice too.
        val picked = AudioSelections.toFile(
            ctx, AudioSelection.Explicit("online_tts", "the-voice"), AudioRequest.word("飲む", null, SourceLangId.JA),
            enabledInOrder = { emptyList() }, sourceFor = { voice },
        )!!.file
        assertArrayEquals("m4a of ogg of 飲む".toByteArray(), picked.readBytes())
    }

    @Test fun `the default card audio is the source's own clip when every client plays it`() = runBlocking {
        val mp3 = clip("taberu.mp3")
        assertEquals(mp3, auto(Source("local_audio", mp3), Source("tts", clip("voice.wav")))!!.file)
        assertTrue(converted.isEmpty())
    }

    @Test fun `a clip that cannot be converted is passed over for the next source's`() = runBlocking {
        CardAudio.transcoder = { _, _ -> null }
        val voice = clip("voice.wav")
        val resolved = auto(Source("local_audio", clip("taberu.opus")), Source("tts", voice))!!
        assertEquals("a card never carries a clip that may be silent where it is studied", voice, resolved.file)
        assertFalse("a recording that could not be used is not the voice service failing", resolved.fellBack)
    }

    @Test fun `a voice service's clip that cannot be converted is a fallback to the device voice, and says so`() = runBlocking {
        CardAudio.transcoder = { _, _ -> null }
        val resolved = auto(Source("online_tts", clip("spoken.ogg")), Source("tts", clip("voice.wav")))!!
        assertEquals("voice.wav", resolved.file.name)
        assertTrue(resolved.fellBack)
    }

    @Test fun `a picked recording is converted too, with its credit`() = runBlocking {
        val resolved = pick(Source("wikimedia_commons", clip("Ja-iku.ogg")), credit)!!
        assertEquals("m4a", resolved.file.extension)
        assertArrayEquals("m4a of Ja-iku.ogg".toByteArray(), resolved.file.readBytes())
        assertEquals(credit, resolved.attribution)
    }

    @Test fun `a picked recording that cannot be converted is reported missing, never replaced`() = runBlocking {
        CardAudio.transcoder = { _, _ -> null }
        assertNull(pick(Source("local_audio", clip("taberu.opus"))))
        assertNotNull("one every client plays is still the pick", pick(Source("local_audio", clip("taberu.mp3"))))
    }

    private companion object {
        val credit = Attribution("Jane", "CC BY", "Wikimedia Commons", "https://x")
    }
}
