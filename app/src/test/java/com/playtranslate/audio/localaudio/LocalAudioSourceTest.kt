package com.playtranslate.audio.localaudio

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.audio.AudioCandidate
import com.playtranslate.audio.AudioRequest
import com.playtranslate.audio.AudioSelection
import com.playtranslate.audio.AudioSelections
import com.playtranslate.audio.AudioSource
import com.playtranslate.audio.AudioSourceRegistry
import com.playtranslate.audio.CandidateLabel
import com.playtranslate.audio.CardAudio
import com.playtranslate.audio.PlayOutcome
import com.playtranslate.audio.PronunciationPlayer
import com.playtranslate.audio.RecordingPlayer
import com.playtranslate.audio.sources.LocalAudioSource
import com.playtranslate.language.SourceLangId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * [LocalAudioSource]: nothing until a file is imported; then the recordings
 * of a word, of whatever language, by source, ahead of every voice; a
 * recording as a file a card can carry; and a word with none left to the
 * voice.
 */
@RunWith(RobolectricTestRunner::class)
class LocalAudioSourceTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val savedDecoder = RecordingPlayer.decoder
    private val savedTranscoder = CardAudio.transcoder

    private val taberu = AudioRequest.word("食べる", "たべる", SourceLangId.JA)
    private val unrecorded = AudioRequest.word("飲む", "のむ", SourceLangId.JA)

    @Before fun setUp() {
        LocalAudioFixture.clear(ctx)
        File(ctx.cacheDir, "audioCache").deleteRecursively()
    }

    @After fun tearDown() {
        RecordingPlayer.decoder = savedDecoder
        CardAudio.transcoder = savedTranscoder
        LocalAudioFixture.clear(ctx)
        File(ctx.cacheDir, "audioCache").deleteRecursively()
    }

    /** A voice standing after local audio in the order: it offers a clip
     *  for anything and counts what it is asked. */
    private class Voice(private val file: File? = null) : AudioSource {
        var plays = 0
        override val id = "tts"
        override fun label(ctx: Context) = id
        override val toggleable = false
        override val remote = false
        override fun isEnabled(ctx: Context) = true
        override fun setEnabled(ctx: Context, on: Boolean) {}
        override suspend fun candidates(ctx: Context, req: AudioRequest) = emptyList<AudioCandidate>()
        override suspend fun defaultCandidate(ctx: Context, req: AudioRequest) =
            AudioCandidate(id, "voice", CandidateLabel.Text("voice"))
        override suspend fun play(
            ctx: Context, candidate: AudioCandidate, req: AudioRequest,
            awaitCompletion: Boolean, onStart: (() -> Unit)?,
        ): PlayOutcome {
            plays++
            return PlayOutcome.Played
        }
        override suspend fun toFile(ctx: Context, candidate: AudioCandidate, req: AudioRequest): File? = file
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

    private val audio = RecordingPlayer.Decoded.Audio(ByteArray(2 * 16_000), sampleRate = 16_000, channelCount = 1)

    // ── Imported or not ──────────────────────────────────────────────────

    @Test fun `with no file it is in neither the default order nor the picker, and offers nothing`() = runBlocking {
        assertFalse(LocalAudioSource.isSetUp(ctx))
        assertFalse(LocalAudioSource.isEnabled(ctx))
        assertFalse(AudioSourceRegistry.enabledInOrder(ctx).any { it.id == LocalAudioSource.ID })
        assertTrue(LocalAudioSource.candidates(ctx, taberu).isEmpty())
        assertNull(LocalAudioSource.defaultCandidate(ctx, taberu))
    }

    @Test fun `with a file it leads the default order, and stands above Commons and the voices in the picker`() {
        LocalAudioFixture.install(ctx)
        assertTrue(LocalAudioSource.isSetUp(ctx))
        assertEquals("local_audio", AudioSourceRegistry.enabledInOrder(ctx).first().id)
        assertEquals(
            listOf("game_audio", "local_audio", "wikimedia_commons", "online_tts", "tts"),
            AudioSourceRegistry.all().map { it.id },
        )
        assertFalse("there is no switch for it", LocalAudioSource.toggleable)
        assertFalse("a tap does not wait on it as on the network", LocalAudioSource.remote)
    }

    // ── What it serves ───────────────────────────────────────────────────

    @Test fun `it serves a word of any language, and no sentence`() = runBlocking {
        LocalAudioFixture.install(ctx)
        assertTrue(LocalAudioSource.serves(taberu))
        assertTrue(LocalAudioSource.serves(AudioRequest.word("日本", null, SourceLangId.ZH)))
        assertTrue(LocalAudioSource.serves(AudioRequest.word("dog", null, SourceLangId.EN)))
        assertFalse(LocalAudioSource.serves(AudioRequest.sentence("食べる", SourceLangId.JA)))
        // The default walk asks every enabled source without asking what it
        // serves: a sentence is offered nothing, though a word is written so.
        assertNull(LocalAudioSource.defaultCandidate(ctx, AudioRequest.sentence("食べる", SourceLangId.JA)))
        assertTrue(LocalAudioSource.candidates(ctx, AudioRequest.sentence("食べる", SourceLangId.JA)).isEmpty())
    }

    @Test fun `one file serves every language, by the written word alone`() = runBlocking {
        LocalAudioFixture.install(ctx)
        // The file knows no language: a word another language writes the
        // same way is found. (One file for every language is the ruling;
        // this is its known cost.)
        assertEquals(2, LocalAudioSource.candidates(ctx, AudioRequest.word("日本", null, SourceLangId.ZH)).size)
        // A reading still has to agree where both sides have one, and a
        // reading in another language's own notation agrees with no kana:
        // only a recording tied to no reading crosses over.
        assertTrue(LocalAudioSource.candidates(ctx, AudioRequest.word("日本", "rìběn", SourceLangId.ZH)).isEmpty())
        assertEquals(
            listOf("Forvo"),
            LocalAudioSource.candidates(ctx, AudioRequest.word("食べる", "chī", SourceLangId.ZH)).map { it.title.resolve(ctx) },
        )
    }

    // ── The recordings of a word ─────────────────────────────────────────

    @Test fun `a word's recordings are named by source, labelled as the source labels them, best first`() = runBlocking {
        LocalAudioFixture.install(ctx)
        val candidates = LocalAudioSource.candidates(ctx, taberu)
        assertEquals(listOf("NHK", "Shinmeikai", "Forvo"), candidates.map { it.title.resolve(ctx) })
        assertEquals(
            listOf("タベ＼ル", null, "strawberrybrown"),
            candidates.map { it.subtitle?.resolve(ctx) },
        )
        assertEquals(
            listOf("nhk16/audio/taberu.opus", "shinmeikai8/media/taberu.mp3", "forvo/strawberrybrown/食べる.mp3"),
            candidates.map { it.key },
        )
        assertTrue(candidates.all { it.sourceId == "local_audio" })
        assertEquals("the first is what plays by default", candidates.first(), LocalAudioSource.defaultCandidate(ctx, taberu))
    }

    @Test fun `a recording with no label of its own is labelled with its speaker`() = runBlocking {
        LocalAudioFixture.install(
            ctx,
            listOf(LocalAudioFixture.Recording("食べる", null, "forvo", "a.mp3", speaker = "akitomo")),
        )
        assertEquals("akitomo", LocalAudioSource.candidates(ctx, taberu).single().subtitle?.resolve(ctx))
    }

    @Test fun `the reading picks the recording of a word that has several`() = runBlocking {
        LocalAudioFixture.install(ctx)
        val nihon = LocalAudioSource.candidates(ctx, AudioRequest.word("日本", "にほん", SourceLangId.JA))
        assertEquals(listOf("nhk16/audio/nihon.opus"), nihon.map { it.key })
        val nippon = LocalAudioSource.candidates(ctx, AudioRequest.word("日本", "にっぽん", SourceLangId.JA))
        assertEquals(listOf("nhk16/audio/nippon.opus"), nippon.map { it.key })
        assertEquals(
            "with no reading known, any recording of the written form",
            2,
            LocalAudioSource.candidates(ctx, AudioRequest.word("日本", null, SourceLangId.JA)).size,
        )
    }

    @Test fun `a source the app has no name for is shown under its id`() = runBlocking {
        LocalAudioFixture.install(ctx)
        val candidate = LocalAudioSource.candidates(ctx, AudioRequest.word("犬", "いぬ", SourceLangId.JA)).single()
        assertEquals("my_recordings", candidate.title.resolve(ctx))
    }

    @Test fun `a word with no recording is offered nothing`() = runBlocking {
        LocalAudioFixture.install(ctx)
        assertNull(LocalAudioSource.defaultCandidate(ctx, unrecorded))
        assertNull("a reading alone finds nothing", LocalAudioSource.defaultCandidate(ctx, AudioRequest.word("たべる", null, SourceLangId.JA)))
    }

    // ── A recording as a file ────────────────────────────────────────────

    @Test fun `a recording becomes a file in the audio cache under its clip's extension`() = runBlocking {
        LocalAudioFixture.install(ctx)
        val candidates = LocalAudioSource.candidates(ctx, taberu)
        val opus = LocalAudioSource.toFile(ctx, candidates[0], taberu)!!
        val mp3 = LocalAudioSource.toFile(ctx, candidates[1], taberu)!!
        assertEquals(File(ctx.cacheDir, "audioCache/local_audio"), opus.parentFile)
        assertEquals("opus", opus.extension)
        assertEquals("mp3", mp3.extension)
        assertArrayEquals("nhk16/audio/taberu.opus".toByteArray(), opus.readBytes())
        assertArrayEquals("shinmeikai8/media/taberu.mp3".toByteArray(), mp3.readBytes())
        assertEquals("asked again, it is the same file", opus, LocalAudioSource.toFile(ctx, candidates[0], taberu))
    }

    @Test fun `a recording already kept is served from the cache, without the file being read again`() = runBlocking {
        val file = LocalAudioFixture.install(ctx)
        val candidate = LocalAudioSource.defaultCandidate(ctx, taberu)!!
        val first = LocalAudioSource.toFile(ctx, candidate, taberu)!!
        // The clip changed in the file, from outside: a second read of the
        // file would bring back other bytes.
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("UPDATE android SET data = x'00FF00' WHERE source = 'nhk16'")
        }
        val second = LocalAudioSource.toFile(ctx, candidate, taberu)!!
        assertEquals(first, second)
        assertArrayEquals("nhk16/audio/taberu.opus".toByteArray(), second.readBytes())
    }

    @Test fun `once the file is removed a pick of one of its recordings is missing, kept before or not`() = runBlocking {
        LocalAudioFixture.install(ctx)
        val kept = AudioSelection.Explicit(LocalAudioSource.ID, "shinmeikai8/media/taberu.mp3")
        val neverKept = AudioSelection.Explicit(LocalAudioSource.ID, "forvo/strawberrybrown/食べる.mp3")
        assertTrue(AudioSelections.toFile(ctx, kept, taberu) != null)
        LocalAudioStore.remove(ctx)
        assertNull(AudioSelections.toFile(ctx, kept, taberu))
        assertNull(AudioSelections.toFile(ctx, neverKept, taberu))
    }

    /** A pick's id must lead to this source before a test resolves a pick
     *  through the registry: an id the registry does not know goes to the
     *  device voice, which waits for an engine a JVM test does not have. */
    private fun assertPicksResolveHere() =
        assertEquals(LocalAudioSource, AudioSourceRegistry.sourceFor(LocalAudioSource.ID))

    @Test fun `a pick is found again from its key alone, and is never another recording`() = runBlocking {
        assertPicksResolveHere()
        LocalAudioFixture.install(ctx)
        val pick = AudioSelection.Explicit(LocalAudioSource.ID, "shinmeikai8/media/taberu.mp3")
        val resolved = AudioSelections.toFile(ctx, pick, taberu)!!
        assertArrayEquals("shinmeikai8/media/taberu.mp3".toByteArray(), resolved.file.readBytes())
        assertFalse("a cached clip is not thrown away after the send", resolved.ephemeral)
        assertNull(resolved.attribution)

        assertNull(
            "a recording the file does not hold is missing, not replaced",
            AudioSelections.toFile(ctx, AudioSelection.Explicit(LocalAudioSource.ID, "nhk16/audio/gone.opus"), taberu),
        )
        assertNull(LocalAudioSource.toFile(ctx, AudioCandidate(LocalAudioSource.ID, "not a key", CandidateLabel.Text("x")), taberu))
    }

    @Test fun `removing the file takes a recording's conversion for cards with it`() = runBlocking {
        LocalAudioFixture.install(ctx)
        CardAudio.transcoder = { clip, _ -> "m4a of ".toByteArray() + clip.readBytes() }
        val card = AudioSelections.toFile(
            ctx, AudioSelection.Explicit(LocalAudioSource.ID, "nhk16/audio/taberu.opus"), taberu,
        )!!.file
        assertEquals("m4a", card.extension)
        LocalAudioStore.remove(ctx)
        assertFalse(card.exists())
    }

    @Test fun `a picked recording's pill reads its source`() = runBlocking {
        assertPicksResolveHere()
        assertEquals(
            "NHK",
            LocalAudioSource.selectionLabel(ctx, AudioSelection.Explicit(LocalAudioSource.ID, "nhk16/audio/taberu.opus"), SourceLangId.JA),
        )
        assertEquals(
            "my_recordings",
            LocalAudioSource.selectionLabel(ctx, AudioSelection.Explicit(LocalAudioSource.ID, "my_recordings/inu.ogg"), SourceLangId.JA),
        )
        assertEquals(
            "android.db file",
            LocalAudioSource.selectionLabel(ctx, AudioSelection.Explicit(LocalAudioSource.ID, "not a key"), SourceLangId.JA),
        )
        assertEquals(
            "and the pill finds the source by the pick's id",
            "NHK",
            AudioSelections.label(ctx, AudioSelection.Explicit("local_audio", "nhk16/audio/taberu.opus"), SourceLangId.JA),
        )
    }

    // ── Ahead of the voice, and the voice behind it ──────────────────────

    @Test fun `a word with a recording is played from it and the voice is not asked`() {
        LocalAudioFixture.install(ctx)
        val decodedFile = AtomicReference<File?>()
        RecordingPlayer.decoder = { file, _ -> decodedFile.set(file); audio }
        val voice = Voice()
        val outcome = offMain { PronunciationPlayer.play(ctx, taberu, sources = listOf(LocalAudioSource, voice)) }
        assertEquals(PlayOutcome.Played, outcome)
        assertEquals(0, voice.plays)
        assertEquals("opus", decodedFile.get()!!.extension)
        assertArrayEquals("nhk16/audio/taberu.opus".toByteArray(), decodedFile.get()!!.readBytes())
    }

    @Test fun `a word with no recording is spoken by the voice`() {
        LocalAudioFixture.install(ctx)
        RecordingPlayer.decoder = { _, _ -> audio }
        val voice = Voice()
        val outcome = offMain { PronunciationPlayer.play(ctx, unrecorded, sources = listOf(LocalAudioSource, voice)) }
        assertEquals(PlayOutcome.Played, outcome)
        assertEquals(1, voice.plays)
    }

    @Test fun `a recording that will not play is left to the voice`() {
        LocalAudioFixture.install(ctx)
        RecordingPlayer.decoder = { _, _ -> RecordingPlayer.Decoded.Unreadable }
        val voice = Voice()
        val outcome = offMain { PronunciationPlayer.play(ctx, taberu, sources = listOf(LocalAudioSource, voice)) }
        assertEquals(PlayOutcome.Played, outcome)
        assertEquals(1, voice.plays)
    }

    @Test fun `a recording whose clip the file does not hold is left to the voice, on a tap and on a card`() {
        // A row in the word list and nothing behind it.
        LocalAudioFixture.install(
            ctx,
            listOf(LocalAudioFixture.Recording("食べる", "タベル", "nhk16", "gone.opus", data = ByteArray(0))),
        )
        RecordingPlayer.decoder = { _, _ -> audio }
        val spoken = File.createTempFile("voice", ".wav").apply { writeBytes(byteArrayOf(1, 2, 3)); deleteOnExit() }
        val voice = Voice(spoken)
        val outcome = offMain { PronunciationPlayer.play(ctx, taberu, sources = listOf(LocalAudioSource, voice)) }
        assertEquals(PlayOutcome.Played, outcome)
        assertEquals(1, voice.plays)
        val card = runBlocking {
            AudioSelections.toFile(ctx, AudioSelection.Auto, taberu, enabledInOrder = { listOf(LocalAudioSource, voice) })
        }!!
        assertEquals(spoken, card.file)
    }

    @Test fun `a word sent with spaces around it is looked up without them`() = runBlocking {
        LocalAudioFixture.install(ctx)
        assertEquals(3, LocalAudioSource.candidates(ctx, AudioRequest.word(" 食べる ", "たべる", SourceLangId.JA)).size)
    }

    @Test fun `a card takes the recording, and a word with none takes the voice's clip unmarked`() = runBlocking {
        LocalAudioFixture.install(ctx)
        val spoken = File.createTempFile("voice", ".wav").apply { writeBytes(byteArrayOf(1, 2, 3)); deleteOnExit() }
        val order: (Context) -> List<AudioSource> = { listOf(LocalAudioSource, Voice(spoken)) }

        // The first recording is Opus, which not every Anki client plays:
        // the card carries its conversion.
        CardAudio.transcoder = { clip, _ -> "m4a of ".toByteArray() + clip.readBytes() }
        val recorded = AudioSelections.toFile(ctx, AudioSelection.Auto, taberu, enabledInOrder = order)!!
        assertEquals("m4a", recorded.file.extension)
        assertEquals(File(ctx.cacheDir, "audioCache/local_audio"), recorded.file.parentFile)
        assertArrayEquals("m4a of nhk16/audio/taberu.opus".toByteArray(), recorded.file.readBytes())
        assertFalse(recorded.ephemeral)
        assertFalse(recorded.fellBack)

        val voiced = AudioSelections.toFile(ctx, AudioSelection.Auto, unrecorded, enabledInOrder = order)!!
        assertEquals(spoken, voiced.file)
        assertFalse("no recording is no failure: the voice is not a fallback", voiced.fellBack)
    }
}
