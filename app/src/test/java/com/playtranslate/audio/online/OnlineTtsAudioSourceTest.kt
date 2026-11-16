package com.playtranslate.audio.online

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.Prefs
import com.playtranslate.audio.AudioCandidate
import com.playtranslate.audio.AudioRequest
import com.playtranslate.audio.AudioSelection
import com.playtranslate.audio.AudioSourceRegistry
import com.playtranslate.audio.AudioSelections
import com.playtranslate.audio.CandidateLabel
import com.playtranslate.audio.CardAudio
import com.playtranslate.audio.PlayOutcome
import com.playtranslate.audio.RecordingPlayer
import com.playtranslate.audio.sources.OnlineTtsAudioSource
import com.playtranslate.language.SourceLangId
import com.playtranslate.security.SecretCodec
import com.playtranslate.translation.BackendFailureKind
import com.playtranslate.translation.CannedReply
import com.playtranslate.translation.SeenRequest
import com.playtranslate.translation.recordingClient
import com.playtranslate.tts.VoiceChoice
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * [OnlineTtsAudioSource]: out of the default order and the picker until a
 * service is connected; for a language whose voice is a device voice,
 * nothing offered and nothing sent; for a language whose voice is the
 * service's, that voice, asked of the service once and cached under the
 * format it answered in.
 */
@RunWith(RobolectricTestRunner::class)
class OnlineTtsAudioSourceTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private fun sp() = ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE)

    private class FakeCodec : SecretCodec {
        override fun encrypt(context: String, plaintext: String): String? = "enc:$context|$plaintext"
        override fun decrypt(context: String, stored: String): String? =
            stored.removePrefix("enc:$context|").takeIf { stored.startsWith("enc:$context|") }
    }

    private lateinit var prefs: Prefs
    private val savedClient = OnlineTtsService.client
    private val savedDecoder = RecordingPlayer.decoder
    private val savedSpokenText = OnlineTtsAudioSource.spokenText
    private val savedTranscoder = CardAudio.transcoder
    private val seen = mutableListOf<SeenRequest>()

    private val mp3 = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 4, 0, 0, 0, 0, 0, 0)
    private val wav = "RIFF\u0000\u0000\u0000\u0000WAVEfmt ".toByteArray(Charsets.ISO_8859_1)

    private val coral = OnlineTtsChoice("coral", "Coral")
    private val word = AudioRequest.word("行く", "いく", SourceLangId.JA)

    private fun connect(config: OnlineTtsConfig = OnlineTtsConfig(model = OnlineTtsChoice("gpt-4o-mini-tts"))) {
        assertTrue(OnlineTtsService.save(prefs, config, "sk-SECRET"))
    }

    private fun answering(reply: (SeenRequest) -> CannedReply) {
        OnlineTtsService.client = OnlineTtsClient(recordingClient(seen, reply))
    }

    @Before fun setUp() {
        sp().edit().clear().commit()
        File(ctx.cacheDir, "audioCache").deleteRecursively()
        prefs = Prefs(ctx, FakeCodec())
        OnlineTtsService.prefsFor = { prefs }
        answering { CannedReply(200, "", bytes = mp3) }
    }

    @After fun tearDown() {
        RecordingPlayer.decoder = savedDecoder
        OnlineTtsAudioSource.spokenText = savedSpokenText
        CardAudio.transcoder = savedTranscoder
        OnlineTtsService.client = savedClient
        OnlineTtsService.prefsFor = { Prefs(it) }
        sp().edit().clear().commit()
        File(ctx.cacheDir, "audioCache").deleteRecursively()
    }

    // ── Connected or not ─────────────────────────────────────────────────

    @Test fun `with no service connected it is in neither the default order nor the picker`() {
        assertFalse(OnlineTtsAudioSource.isSetUp(ctx))
        assertFalse(OnlineTtsAudioSource.isEnabled(ctx))
        assertFalse(AudioSourceRegistry.enabledInOrder(ctx).any { it.id == OnlineTtsAudioSource.ID })
    }

    @Test fun `once connected it stands in the default order ahead of the device voice`() {
        connect()
        assertTrue(OnlineTtsAudioSource.isSetUp(ctx))
        val order = AudioSourceRegistry.enabledInOrder(ctx).map { it.id }
        assertTrue(order.indexOf("online_tts") in 0 until order.indexOf("tts"))
        val all = AudioSourceRegistry.all().map { it.id }
        assertTrue("above the device's voices in the picker too", all.indexOf("online_tts") < all.indexOf("tts"))
    }

    // ── What it offers by default ────────────────────────────────────────

    @Test fun `a language whose voice is a device voice is offered nothing and asked nothing`() = runBlocking {
        connect()
        prefs.setVoiceChoice(SourceLangId.JA, VoiceChoice.Device("ja-jp-x-jab-local"))
        assertNull(OnlineTtsAudioSource.defaultCandidate(ctx, word))
        // The request's language decides, not the game's: a Japanese card
        // made while the game language is Chinese is spoken by Japanese's
        // voice, whatever Chinese's is.
        prefs.sourceLang = "zh"
        prefs.setVoiceChoice(SourceLangId.ZH, VoiceChoice.Online(coral))
        assertNull("another language's service voice is not this one's", OnlineTtsAudioSource.defaultCandidate(ctx, word))
        assertTrue(seen.isEmpty())
    }

    @Test fun `a language whose voice is the service's is offered that voice`() = runBlocking {
        connect()
        prefs.setVoiceChoice(SourceLangId.JA, VoiceChoice.Online(coral))
        val candidate = OnlineTtsAudioSource.defaultCandidate(ctx, word)!!
        assertEquals("online_tts", candidate.sourceId)
        assertEquals("coral", candidate.key)
        assertEquals("Coral", candidate.locator)
        assertEquals("Coral", candidate.title.resolve(ctx))
        assertTrue("offering is not asking", seen.isEmpty())
    }

    // ── A clip ───────────────────────────────────────────────────────────

    private fun candidate(voice: String = "coral") =
        AudioCandidate("online_tts", voice, CandidateLabel.Text(voice))

    @Test fun `a clip is asked with the voice, the spoken text, the slider's speed and the language`() = runBlocking {
        connect(OnlineTtsConfig(
            model = OnlineTtsChoice("gpt-4o-mini-tts"), instructions = "Slowly.",
            request = "Authorization: Bearer {key}\nX-Lang: {language}\n\n" +
                "{\"model\":\"{model}\",\"voice\":\"{voice}\",\"input\":\"{text}\",\"instructions\":\"{instructions}\",\"speed\":{speed}}",
        ))
        prefs.setTtsSpeechRate(1.5f)
        // The language sent is the request's own, not the game's.
        prefs.sourceLang = "zh"
        OnlineTtsAudioSource.toFile(ctx, candidate("nova"), word)
        val sent = seen.single()
        assertEquals("Bearer sk-SECRET", sent.request.header("Authorization"))
        assertEquals("ja", sent.request.header("X-Lang"))
        assertEquals("nova", sent.json["voice"]!!.jsonPrimitive.content)
        assertEquals("the reading, as the device voice is given it", "いく", sent.json["input"]!!.jsonPrimitive.content)
        assertEquals("gpt-4o-mini-tts", sent.json["model"]!!.jsonPrimitive.content)
        assertEquals("Slowly.", sent.json["instructions"]!!.jsonPrimitive.content)
        assertEquals("1.5", sent.json["speed"]!!.jsonPrimitive.content)
    }

    @Test fun `each preset's own request tells the service the request's language`() = runBlocking {
        val japanese = AudioRequest.word("日本語", null, SourceLangId.JA)
        val traditional = AudioRequest.word("日本語", null, SourceLangId.ZH_HANT)

        // OpenAI-shaped: no field for it, so it is named in the instructions.
        connect()
        OnlineTtsAudioSource.toFile(ctx, candidate(), japanese)
        OnlineTtsAudioSource.toFile(ctx, candidate(), traditional)
        assertEquals(2, seen.size)
        assertEquals("the word goes as written", "日本語", seen[0].json["input"]!!.jsonPrimitive.content)
        assertEquals("Speak in Japanese.", seen[0].json["instructions"]!!.jsonPrimitive.content.trim())
        assertEquals("Speak in Chinese.", seen[1].json["instructions"]!!.jsonPrimitive.content.trim())

        // ElevenLabs-shaped: its language field, as a two-letter code.
        seen.clear()
        connect(OnlineTtsConfig(OnlineTtsPreset.ELEVENLABS, model = OnlineTtsChoice("eleven_flash_v2_5")))
        OnlineTtsAudioSource.toFile(ctx, candidate("voiceid"), japanese)
        OnlineTtsAudioSource.toFile(ctx, candidate("voiceid"), traditional)
        assertEquals("ja", seen[0].json["language_code"]!!.jsonPrimitive.content)
        assertEquals("a script is no part of the code", "zh", seen[1].json["language_code"]!!.jsonPrimitive.content)
    }

    @Test fun `every game language has a two-letter code and an English name to be told by`() {
        for (lang in SourceLangId.entries) {
            val code = OnlineTtsAudioSource.languageCode(lang)
            val name = OnlineTtsAudioSource.languageName(lang)
            assertTrue("$lang code '$code'", code.matches(Regex("[a-z]{2}")))
            assertTrue("$lang name '$name'", name.matches(Regex("[A-Z][A-Za-z ]+")) && name != code)
        }
        assertEquals("Japanese", OnlineTtsAudioSource.languageName(SourceLangId.JA))
        assertEquals("French", OnlineTtsAudioSource.languageName(SourceLangId.FR))
        assertEquals("Chinese", OnlineTtsAudioSource.languageName(SourceLangId.ZH_HANT))
        assertEquals("zh", OnlineTtsAudioSource.languageCode(SourceLangId.ZH_HANT))
        // In English whatever language the device is set to: the name is
        // read by the service, inside an English sentence.
        val saved = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.JAPAN)
            assertEquals("Japanese", OnlineTtsAudioSource.languageName(SourceLangId.JA))
        } finally {
            java.util.Locale.setDefault(saved)
        }
    }

    @Test fun `the speed is 1 while the slider was never moved`() = runBlocking {
        connect()
        OnlineTtsAudioSource.toFile(ctx, candidate(), word)
        assertEquals("1.0", seen.single().json["speed"]!!.jsonPrimitive.content)
    }

    @Test fun `an ElevenLabs-shaped service is asked at the slider's speed, and never past what it takes`() = runBlocking {
        connect(OnlineTtsConfig(OnlineTtsPreset.ELEVENLABS, model = OnlineTtsChoice("eleven_flash_v2_5")))
        fun speedOf(sent: SeenRequest) = sent.json["voice_settings"]!!.jsonObject["speed"]!!.jsonPrimitive.content

        OnlineTtsAudioSource.toFile(ctx, candidate("voiceid"), word)
        assertEquals("the slider never moved", "1.0", speedOf(seen.single()))

        prefs.setTtsSpeechRate(1.1f)
        OnlineTtsAudioSource.toFile(ctx, candidate("voiceid"), word)
        assertEquals("a speed the service takes is a clip of its own", "1.1", speedOf(seen[1]))

        prefs.setTtsSpeechRate(2f)
        OnlineTtsAudioSource.toFile(ctx, candidate("voiceid"), word)
        assertEquals("the slider's end is asked for as the service's limit", "1.2", speedOf(seen[2]))

        prefs.setTtsSpeechRate(1.5f)
        val past = OnlineTtsAudioSource.toFile(ctx, candidate("voiceid"), word)!!
        prefs.setTtsSpeechRate(2f)
        assertEquals(past, OnlineTtsAudioSource.toFile(ctx, candidate("voiceid"), word))
        assertEquals("another speed past the limit is the clip already held, and asks nothing", 3, seen.size)
    }

    @Test fun `a clip is cached in the audio cache under the format its bytes are in`() = runBlocking {
        connect()
        val file = OnlineTtsAudioSource.toFile(ctx, candidate(), word)!!
        assertTrue(file.readBytes().contentEquals(mp3))
        assertEquals("mp3", file.extension)
        assertEquals(File(ctx.cacheDir, "audioCache/online_tts"), file.parentFile)
        assertFalse("the file's name gives away neither the text nor the key", file.name.contains("sk-SECRET"))
    }

    @Test fun `the same clip is asked of the service once, and a different one again`() = runBlocking {
        connect()
        val first = OnlineTtsAudioSource.toFile(ctx, candidate(), word)!!
        val again = OnlineTtsAudioSource.toFile(ctx, candidate(), word)!!
        assertEquals(first, again)
        assertEquals(1, seen.size)

        OnlineTtsAudioSource.toFile(ctx, candidate("nova"), word)
        OnlineTtsAudioSource.toFile(ctx, candidate(), AudioRequest.word("見る", "みる", SourceLangId.JA))
        prefs.setTtsSpeechRate(0.75f)
        OnlineTtsAudioSource.toFile(ctx, candidate(), word)
        assertEquals("another voice, another word and another speed are other clips", 4, seen.size)
    }

    @Test fun `a new key keeps the cached clips`() = runBlocking {
        connect()
        val first = OnlineTtsAudioSource.toFile(ctx, candidate(), word)!!
        assertTrue(OnlineTtsService.save(prefs, OnlineTtsService.config(prefs)!!, "sk-ANOTHER"))
        assertEquals(first, OnlineTtsAudioSource.toFile(ctx, candidate(), word))
        assertEquals(1, seen.size)
    }

    @Test fun `a clip answered in another format is stored as that format and found again`() = runBlocking {
        connect()
        answering { CannedReply(200, "", bytes = wav) }
        val file = OnlineTtsAudioSource.toFile(ctx, candidate(), word)!!
        assertEquals("wav", file.extension)
        assertEquals(file, OnlineTtsAudioSource.toFile(ctx, candidate(), word))
        assertEquals(1, seen.size)
    }

    // ── The service and its key, read together ───────────────────────────

    @Test fun `a service saved while a clip's text is prepared lends the request neither its address nor its key`() =
        runBlocking {
            connect()
            OnlineTtsAudioSource.spokenText = { _, req ->
                // Another service, saved while this request waits for its text.
                assertTrue(
                    OnlineTtsService.save(
                        prefs,
                        OnlineTtsConfig(OnlineTtsPreset.ELEVENLABS, model = OnlineTtsChoice("eleven_flash_v2_5")),
                        "xi-ANOTHER",
                    ),
                )
                req.surface
            }
            OnlineTtsAudioSource.clipFor(ctx, "coral", word)
            val sent = seen.single().request
            assertEquals("api.openai.com", sent.url.host)
            assertEquals("Bearer sk-SECRET", sent.header("Authorization"))
            assertNull(sent.header("xi-api-key"))
            assertEquals("the save itself took", "xi-ANOTHER", OnlineTtsService.connection(prefs)!!.key)
        }

    // ── A cached clip that is no audio ───────────────────────────────────

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

    private fun played(): PlayOutcome =
        offMain { OnlineTtsAudioSource.play(ctx, candidate(), word, awaitCompletion = true, onStart = null) }

    @Test fun `a clip that turns out to be no audio is dropped when played, and asked of the service again`() {
        connect()
        RecordingPlayer.decoder = { _, _ -> RecordingPlayer.Decoded.Unreadable }
        val clip = runBlocking { OnlineTtsAudioSource.toFile(ctx, candidate(), word)!! }

        assertEquals(PlayOutcome.Failed(recoverable = true), played())
        assertEquals("the play found the clip cached and asked nothing", 1, seen.size)
        assertFalse("the clip left the cache", clip.exists())

        runBlocking { OnlineTtsAudioSource.toFile(ctx, candidate(), word) }
        assertEquals("the next request asks the service again", 2, seen.size)
    }

    @Test fun `a clip the player only declines stays cached`() {
        connect()
        RecordingPlayer.decoder = { _, _ -> RecordingPlayer.Decoded.Declined }
        val clip = runBlocking { OnlineTtsAudioSource.toFile(ctx, candidate(), word)!! }

        assertEquals(PlayOutcome.Failed(recoverable = true), played())
        assertTrue(clip.exists())
        runBlocking { OnlineTtsAudioSource.toFile(ctx, candidate(), word) }
        assertEquals("it is not bought again", 1, seen.size)
    }

    // ── A failure ────────────────────────────────────────────────────────

    @Test fun `a failure is no file, a recoverable miss when played, and caches nothing`() = runBlocking {
        connect()
        answering { CannedReply(401, """{"error":{"message":"Incorrect API key provided"}}""") }
        assertNull(OnlineTtsAudioSource.toFile(ctx, candidate(), word))
        assertEquals(
            PlayOutcome.Failed(recoverable = true),
            OnlineTtsAudioSource.play(ctx, candidate(), word, awaitCompletion = true, onStart = null),
        )
        assertEquals("each attempt asked again", 2, seen.size)
        assertFalse(File(ctx.cacheDir, "audioCache/online_tts").listFiles().orEmpty().any { it.isFile })
    }

    @Test fun `the one path to a clip throws the failure with the service's words`() {
        connect()
        answering { CannedReply(401, """{"error":{"message":"Incorrect API key provided"}}""") }
        try {
            runBlocking { OnlineTtsAudioSource.clipFor(ctx, "coral", word) }
            fail("a refused key is a failure")
        } catch (e: OnlineTtsException) {
            assertEquals(BackendFailureKind.AUTH, e.failure.kind)
            assertEquals("""{"error":{"message":"Incorrect API key provided"}}""", e.detail)
        }
    }

    @Test fun `with no service connected there is no clip and no request`() = runBlocking {
        assertNull(OnlineTtsAudioSource.toFile(ctx, candidate(), word))
        assertTrue(seen.isEmpty())
    }

    // ── The picker ───────────────────────────────────────────────────────

    @Test fun `the picker's candidates are the service's voices, each carrying its name`() = runBlocking {
        connect()
        answering { CannedReply(200, """{"voices":[{"id":"jf_alpha","name":"Alpha"},{"id":"zf_xiaobei"}]}""") }
        val candidates = OnlineTtsAudioSource.candidates(ctx, word)
        assertEquals(listOf("jf_alpha", "zf_xiaobei"), candidates.map { it.key })
        assertEquals(listOf("Alpha", "zf_xiaobei"), candidates.map { it.locator })
        assertEquals(listOf("Alpha", "zf_xiaobei"), candidates.map { it.title.resolve(ctx) })
    }

    @Test fun `the picker's list is asked for with the stored key`() = runBlocking {
        connect()
        answering { CannedReply(200, """{"voices":[{"id":"jf_alpha","name":"Alpha"}]}""") }
        OnlineTtsAudioSource.candidates(ctx, word)
        assertEquals("Bearer sk-SECRET", seen.single().request.header("Authorization"))
    }

    @Test fun `a hand-picked voice's pill reads the name its pick carries`() = runBlocking {
        val pick = AudioSelection.Explicit("online_tts", "21m00Tcm4TlvDq8ikWAM", locator = "Rachel")
        assertEquals("Rachel", OnlineTtsAudioSource.selectionLabel(ctx, pick, SourceLangId.JA))
        assertNotEquals(
            "a pick with no name reads the source's, never the id",
            "21m00Tcm4TlvDq8ikWAM",
            OnlineTtsAudioSource.selectionLabel(ctx, pick.copy(locator = null), SourceLangId.JA),
        )
    }

    // ── On a card, when the service answers in a format not every client plays ──

    @Test fun `two cards spoken in one voice by a service that answers in Ogg each carry their own words`() = runBlocking {
        connect()
        prefs.setVoiceChoice(SourceLangId.JA, VoiceChoice.Online(coral))
        // The service's answer holds the text it was asked to speak.
        answering { request ->
            CannedReply(200, "", bytes = "OggS".toByteArray() + request.json["input"]!!.jsonPrimitive.content.toByteArray())
        }
        CardAudio.transcoder = { clip, _ -> "m4a of ".toByteArray() + clip.readBytes() }
        suspend fun card(request: AudioRequest) = AudioSelections.toFile(
            ctx, AudioSelection.Auto, request, enabledInOrder = { listOf(OnlineTtsAudioSource) },
        )!!.file

        val first = card(word)
        val second = card(AudioRequest.word("食べる", "たべる", SourceLangId.JA))
        assertEquals("m4a", first.extension)
        assertEquals("m4a of OggSいく", String(first.readBytes()))
        assertEquals("the voice is the same; the words are not", "m4a of OggSたべる", String(second.readBytes()))
        assertEquals("the first word again is its own conversion again, with nothing asked", first, card(word))
        assertEquals(2, seen.size)
        // A pick of the voice by hand resolves under the voice's id too.
        val picked = AudioSelections.toFile(
            ctx, AudioSelection.Explicit("online_tts", "coral", locator = "Coral"),
            AudioRequest.word("飲む", "のむ", SourceLangId.JA),
        )!!.file
        assertEquals("m4a of OggSのむ", String(picked.readBytes()))
    }
}
