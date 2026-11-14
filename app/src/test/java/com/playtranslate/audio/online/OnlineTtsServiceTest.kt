package com.playtranslate.audio.online

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.Prefs
import com.playtranslate.audio.AudioCache
import com.playtranslate.language.SourceLangId
import com.playtranslate.security.SecretCodec
import com.playtranslate.translation.BackendFailureKind
import com.playtranslate.translation.CannedReply
import com.playtranslate.translation.SeenRequest
import com.playtranslate.translation.recordingClient
import com.playtranslate.translation.transportFailingClient
import com.playtranslate.tts.VoiceChoice
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * [OnlineTtsService] and the voice choice it shares [Prefs] with: the
 * connected service stored whole or not at all, a language's voice as one
 * choice under two keys that never both exist, service voices forgotten when
 * the service becomes another, and the voice list a service does or does not
 * have.
 */
@RunWith(RobolectricTestRunner::class)
class OnlineTtsServiceTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private fun sp() = ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE)

    private class FakeCodec(var encryptWorks: Boolean = true) : SecretCodec {
        /** Run inside the next decryption: a key being read, held still. */
        @Volatile var duringDecrypt: (() -> Unit)? = null
        @Volatile var decryptions = 0
        override fun encrypt(context: String, plaintext: String): String? =
            if (encryptWorks) "enc:$context|$plaintext" else null
        override fun decrypt(context: String, stored: String): String? {
            decryptions++
            duringDecrypt?.also { duringDecrypt = null }?.invoke()
            return stored.removePrefix("enc:$context|").takeIf { stored.startsWith("enc:$context|") }
        }
    }

    private val codec = FakeCodec()
    private lateinit var prefs: Prefs
    private val savedClient = OnlineTtsService.client

    private val openAi = OnlineTtsConfig(model = OnlineTtsChoice("gpt-4o-mini-tts"))
    private val rachel = OnlineTtsChoice("21m00Tcm4TlvDq8ikWAM", "Rachel")

    @Before fun setUp() {
        sp().edit().clear().commit()
        File(ctx.cacheDir, "audioCache").deleteRecursively()
        prefs = Prefs(ctx, codec)
        OnlineTtsService.prefsFor = { prefs }
    }

    @After fun tearDown() {
        OnlineTtsService.client = savedClient
        OnlineTtsService.prefsFor = { Prefs(it) }
        sp().edit().clear().commit()
        File(ctx.cacheDir, "audioCache").deleteRecursively()
    }

    // ── Saving the service ───────────────────────────────────────────────

    @Test fun `no stored config is no service`() {
        assertNull(OnlineTtsService.config(prefs))
    }

    @Test fun `a saved service reads back, its key encrypted and outside its config`() {
        assertTrue(OnlineTtsService.save(prefs, openAi.copy(instructions = "Slowly."), "  sk-SECRET  "))
        val stored = OnlineTtsService.config(prefs)!!
        assertEquals(OnlineTtsPreset.OPENAI, stored.preset)
        assertEquals("gpt-4o-mini-tts", stored.model?.id)
        assertEquals("Slowly.", stored.instructions)
        assertEquals("the key is trimmed", "sk-SECRET", prefs.onlineTtsApiKey)
        assertFalse(prefs.onlineTtsConfigJson!!.contains("sk-SECRET"))
        assertEquals("enc:online_tts_api_key|sk-SECRET", sp().getString("online_tts_api_key", null))
    }

    @Test fun `a preset's own texts are stored as absent`() {
        val spec = OnlineTtsPreset.OPENAI.spec
        OnlineTtsService.save(prefs, openAi.copy(url = spec.url, request = spec.request + "\n"), "k")
        val stored = OnlineTtsService.config(prefs)!!
        assertNull(stored.url)
        assertNull(stored.request)
        assertFalse(prefs.onlineTtsConfigJson!!.contains("api.openai.com"))
    }

    @Test fun `a key that cannot be stored saves nothing`() {
        codec.encryptWorks = false
        assertFalse(OnlineTtsService.save(prefs, openAi, "sk-SECRET"))
        assertNull(OnlineTtsService.config(prefs))
        assertEquals("", prefs.onlineTtsApiKey)
    }

    @Test fun `a keyless service saves with an empty key`() {
        val kokoro = openAi.copy(url = "http://192.168.1.5:8880/v1/audio/speech")
        assertTrue(OnlineTtsService.save(prefs, kokoro, ""))
        assertEquals("http://192.168.1.5:8880/v1/audio/speech", OnlineTtsService.config(prefs)!!.effectiveUrl)
    }

    // ── One voice choice per language ────────────────────────────────────

    @Test fun `a language's voice starts as the device's default and takes a device voice`() {
        assertEquals(VoiceChoice.Device(null), prefs.voiceChoice(SourceLangId.JA))
        prefs.setVoiceChoice(SourceLangId.JA, VoiceChoice.Device("ja-jp-x-jab-local"))
        assertEquals(VoiceChoice.Device("ja-jp-x-jab-local"), prefs.voiceChoice(SourceLangId.JA))
        assertEquals("ja-jp-x-jab-local", prefs.ttsVoiceName(SourceLangId.JA))
    }

    @Test fun `a service voice replaces the device voice, and a device voice replaces it back`() {
        OnlineTtsService.save(prefs, openAi, "k")
        prefs.setVoiceChoice(SourceLangId.JA, VoiceChoice.Device("ja-jp-x-jab-local"))

        prefs.setVoiceChoice(SourceLangId.JA, VoiceChoice.Online(rachel))
        assertEquals(VoiceChoice.Online(rachel), prefs.voiceChoice(SourceLangId.JA))
        assertNull("no device voice is left under it", prefs.ttsVoiceName(SourceLangId.JA))
        assertFalse(sp().contains("tts_voice_ja"))

        prefs.setVoiceChoice(SourceLangId.JA, VoiceChoice.Device(null))
        assertEquals(VoiceChoice.Device(null), prefs.voiceChoice(SourceLangId.JA))
        assertFalse(sp().contains("online_voice_ja"))
    }

    @Test fun `each language has its own voice`() {
        OnlineTtsService.save(prefs, openAi, "k")
        prefs.setVoiceChoice(SourceLangId.JA, VoiceChoice.Online(rachel))
        assertEquals(VoiceChoice.Device(null), prefs.voiceChoice(SourceLangId.ZH))
        assertEquals(VoiceChoice.Online(rachel), prefs.voiceChoice(SourceLangId.JA))
    }

    @Test fun `a service voice counts only while a service is connected`() {
        prefs.setVoiceChoice(SourceLangId.JA, VoiceChoice.Online(rachel))
        assertEquals(VoiceChoice.Device(null), prefs.voiceChoice(SourceLangId.JA))
        OnlineTtsService.save(prefs, openAi, "k")
        assertEquals(VoiceChoice.Online(rachel), prefs.voiceChoice(SourceLangId.JA))
    }

    // ── A voice id belongs to its service ────────────────────────────────

    private fun pickForTwoLanguages() {
        prefs.setVoiceChoice(SourceLangId.JA, VoiceChoice.Online(rachel))
        prefs.setVoiceChoice(SourceLangId.ZH_HANT, VoiceChoice.Online(OnlineTtsChoice("coral")))
    }

    @Test fun `the same service saved again keeps every language's voice`() {
        OnlineTtsService.save(prefs, openAi, "k")
        pickForTwoLanguages()
        OnlineTtsService.save(
            prefs,
            openAi.copy(model = OnlineTtsChoice("tts-1"), instructions = "Whisper.", request = "{\"input\":\"{text}\"}"),
            "another-key",
        )
        assertEquals(VoiceChoice.Online(rachel), prefs.voiceChoice(SourceLangId.JA))
        assertEquals(VoiceChoice.Online(OnlineTtsChoice("coral")), prefs.voiceChoice(SourceLangId.ZH_HANT))
    }

    @Test fun `another request shape forgets every language's service voice`() {
        OnlineTtsService.save(prefs, openAi, "k")
        pickForTwoLanguages()
        OnlineTtsService.save(prefs, OnlineTtsConfig(OnlineTtsPreset.ELEVENLABS, model = OnlineTtsChoice("m")), "k")
        assertEquals(VoiceChoice.Device(null), prefs.voiceChoice(SourceLangId.JA))
        assertEquals(VoiceChoice.Device(null), prefs.voiceChoice(SourceLangId.ZH_HANT))
        assertFalse(sp().contains("online_voice_ja"))
        assertFalse(sp().contains("online_voice_zh-Hant"))
    }

    @Test fun `another host forgets every language's service voice`() {
        OnlineTtsService.save(prefs, openAi, "k")
        pickForTwoLanguages()
        OnlineTtsService.save(prefs, openAi.copy(url = "http://192.168.1.5:8880/v1/audio/speech"), "")
        assertEquals(VoiceChoice.Device(null), prefs.voiceChoice(SourceLangId.JA))
        assertEquals(VoiceChoice.Device(null), prefs.voiceChoice(SourceLangId.ZH_HANT))
    }

    @Test fun `removing the service clears its config, its key and every language's voice`() {
        OnlineTtsService.save(prefs, openAi, "sk-SECRET")
        pickForTwoLanguages()
        OnlineTtsService.remove(ctx)
        assertNull(OnlineTtsService.config(prefs))
        assertNull(prefs.onlineTtsConfigJson)
        assertEquals("", prefs.onlineTtsApiKey)
        assertEquals("", sp().getString("online_tts_api_key", null))
        assertFalse(sp().contains("online_voice_ja"))
        // A service connected later does not bring the old voices back.
        OnlineTtsService.save(prefs, openAi, "k")
        assertEquals(VoiceChoice.Device(null), prefs.voiceChoice(SourceLangId.JA))
    }

    @Test fun `removing the service deletes the clips it spoke, and no other source's`() = runBlocking {
        OnlineTtsService.save(prefs, openAi, "sk-SECRET")
        val cache = AudioCache(ctx)
        val spoken = cache.putClip("online_tts", "a-line.mp3", byteArrayOf(1), null)
        val recording = cache.putClip("wikimedia_commons", "Ja-行く.ogg", byteArrayOf(1), null)
        OnlineTtsService.remove(ctx)
        assertFalse(spoken.exists())
        assertNull(cache.getClip("online_tts", "a-line.mp3"))
        assertTrue(recording.exists())
    }

    // ── The service is one record ────────────────────────────────────────

    private val elevenLabs = OnlineTtsConfig(OnlineTtsPreset.ELEVENLABS, model = OnlineTtsChoice("eleven_flash_v2_5"))

    /** The stored service as one look at it finds it. */
    private data class Stored(
        val preset: OnlineTtsPreset?,
        val model: String?,
        val key: String,
        val serviceVoices: Set<SourceLangId>,
    )

    /**
     * Every state of the stored service that could be read while [change]
     * was written: one look per stored value that changed, taken as the
     * write lands. A change made in one write shows one state, the new
     * one; made in several, the states between them too.
     */
    private fun statesWhile(change: () -> Unit): Set<Stored> {
        val states = mutableSetOf<Stored>()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            val config = OnlineTtsService.config(prefs)
            states += Stored(
                config?.preset,
                config?.model?.id,
                prefs.onlineTtsApiKey,
                SourceLangId.entries.filter { sp().contains("online_voice_${it.code}") }.toSet(),
            )
        }
        val stored = sp()
        stored.registerOnSharedPreferenceChangeListener(listener)
        try {
            change()
        } finally {
            stored.unregisterOnSharedPreferenceChangeListener(listener)
        }
        assertTrue("the write was seen", states.isNotEmpty())
        return states
    }

    @Test fun `the connection is the stored service with its key, and nothing while no service is stored`() {
        assertNull(OnlineTtsService.connection(prefs))
        OnlineTtsService.save(prefs, openAi, "sk-SECRET")
        val connected = OnlineTtsService.connection(prefs)!!
        assertEquals(OnlineTtsPreset.OPENAI, connected.config.preset)
        assertEquals("sk-SECRET", connected.key)

        OnlineTtsService.remove(ctx)
        assertNull(OnlineTtsService.connection(prefs))
        // A stored config the app cannot read is no service either.
        sp().edit().putString("online_tts_config", "not a config").commit()
        assertNull(OnlineTtsService.connection(prefs))
    }

    @Test fun `another service is saved in one write, its key never beside the old service or the old voices`() {
        OnlineTtsService.save(prefs, openAi, "sk-OLD")
        pickForTwoLanguages()
        val states = statesWhile { assertTrue(OnlineTtsService.save(prefs, elevenLabs, "xi-NEW")) }
        assertEquals(
            setOf(Stored(OnlineTtsPreset.ELEVENLABS, "eleven_flash_v2_5", "xi-NEW", emptySet())),
            states,
        )
    }

    @Test fun `a model that refuses a voice is saved in one write with that voice's end`() {
        OnlineTtsService.save(prefs, openAi, "sk-OLD")
        prefs.setVoiceChoice(SourceLangId.JA, VoiceChoice.Online(OnlineTtsChoice("marin")))
        prefs.setVoiceChoice(SourceLangId.ZH_HANT, VoiceChoice.Online(OnlineTtsChoice("coral")))
        val states = statesWhile {
            assertTrue(OnlineTtsService.save(prefs, openAi.copy(model = OnlineTtsChoice("tts-1")), "sk-NEW"))
        }
        assertEquals(
            setOf(Stored(OnlineTtsPreset.OPENAI, "tts-1", "sk-NEW", setOf(SourceLangId.ZH_HANT))),
            states,
        )
    }

    @Test fun `removing is one write`() {
        OnlineTtsService.save(prefs, openAi, "sk-OLD")
        pickForTwoLanguages()
        val states = statesWhile { OnlineTtsService.remove(ctx) }
        assertEquals(setOf(Stored(null, null, "", emptySet())), states)
    }

    @Test fun `a key that cannot be stored leaves the connected service, its key and its voices as they were`() {
        OnlineTtsService.save(prefs, openAi, "sk-OLD")
        pickForTwoLanguages()
        codec.encryptWorks = false
        assertFalse(OnlineTtsService.save(prefs, elevenLabs, "xi-NEW"))
        val connected = OnlineTtsService.connection(prefs)!!
        assertEquals(OnlineTtsPreset.OPENAI, connected.config.preset)
        assertEquals("sk-OLD", connected.key)
        assertEquals(VoiceChoice.Online(rachel), prefs.voiceChoice(SourceLangId.JA))
    }

    @Test fun `a save drops the decryption kept of the key it replaces`() {
        OnlineTtsService.save(prefs, openAi, "sk-SECRET")
        OnlineTtsService.connection(prefs)
        OnlineTtsService.connection(prefs)
        assertEquals("a key read twice is decrypted once", 1, codec.decryptions)
        // The same key again: this codec gives it the same ciphertext, so
        // only the save's own drop makes the next read decrypt.
        OnlineTtsService.save(prefs, openAi, "sk-SECRET")
        assertEquals("sk-SECRET", OnlineTtsService.connection(prefs)!!.key)
        assertEquals(2, codec.decryptions)
    }

    @Test fun `a save waits for a read of the service and its key that is under way`() {
        OnlineTtsService.save(prefs, openAi, "sk-OLD")
        val saved = CountDownLatch(1)
        var landedInsideTheRead: Boolean? = null
        // The read has the config and is fetching the key. Another service
        // is saved now, from another thread, and given time to land.
        codec.duringDecrypt = {
            thread {
                OnlineTtsService.save(prefs, elevenLabs, "xi-NEW")
                saved.countDown()
            }
            landedInsideTheRead = saved.await(300, TimeUnit.MILLISECONDS)
        }
        val read = OnlineTtsService.connection(prefs)!!
        assertEquals(false, landedInsideTheRead)
        assertEquals(OnlineTtsPreset.OPENAI, read.config.preset)
        assertEquals("sk-OLD", read.key)

        assertTrue("it lands once the read is done", saved.await(5, TimeUnit.SECONDS))
        val after = OnlineTtsService.connection(prefs)!!
        assertEquals(OnlineTtsPreset.ELEVENLABS, after.config.preset)
        assertEquals("xi-NEW", after.key)
    }

    // ── A voice the model refuses ────────────────────────────────────────

    private val marin = OnlineTtsChoice("marin")
    private val coral = OnlineTtsChoice("coral")
    private val older = openAi.copy(model = OnlineTtsChoice("tts-1"))

    @Test fun `a model that refuses a language's voice sends that language to the device's voice, and no other`() {
        OnlineTtsService.save(prefs, openAi, "k")
        prefs.setVoiceChoice(SourceLangId.JA, VoiceChoice.Online(marin))
        prefs.setVoiceChoice(SourceLangId.ZH_HANT, VoiceChoice.Online(coral))
        prefs.setVoiceChoice(SourceLangId.KO, VoiceChoice.Online(rachel))
        prefs.setVoiceChoice(SourceLangId.ZH, VoiceChoice.Device("zh-cn-x-local"))

        assertEquals(
            "what the page is told before it saves",
            mapOf(SourceLangId.JA to marin),
            OnlineTtsService.voicesNotTaken(prefs, older),
        )
        assertEquals("asking changes nothing", VoiceChoice.Online(marin), prefs.voiceChoice(SourceLangId.JA))

        assertTrue(OnlineTtsService.save(prefs, older, "k"))
        assertEquals(VoiceChoice.Device(null), prefs.voiceChoice(SourceLangId.JA))
        assertFalse(sp().contains("online_voice_ja"))
        assertEquals("one of the nine the model takes", VoiceChoice.Online(coral), prefs.voiceChoice(SourceLangId.ZH_HANT))
        assertEquals("a voice the app holds no data about", VoiceChoice.Online(rachel), prefs.voiceChoice(SourceLangId.KO))
        assertEquals(VoiceChoice.Device("zh-cn-x-local"), prefs.voiceChoice(SourceLangId.ZH))
        assertTrue("nothing is left to send back", OnlineTtsService.voicesNotTaken(prefs, older).isEmpty())
    }

    @Test fun `every language whose voice the model refuses is named, each with its voice`() {
        OnlineTtsService.save(prefs, openAi, "k")
        prefs.setVoiceChoice(SourceLangId.JA, VoiceChoice.Online(marin))
        prefs.setVoiceChoice(SourceLangId.KO, VoiceChoice.Online(OnlineTtsChoice("cedar")))
        val hd = openAi.copy(model = OnlineTtsChoice("tts-1-hd"))
        assertEquals(
            mapOf(SourceLangId.JA to marin, SourceLangId.KO to OnlineTtsChoice("cedar")),
            OnlineTtsService.voicesNotTaken(prefs, hd),
        )
        OnlineTtsService.save(prefs, hd, "k")
        assertEquals(VoiceChoice.Device(null), prefs.voiceChoice(SourceLangId.JA))
        assertEquals(VoiceChoice.Device(null), prefs.voiceChoice(SourceLangId.KO))
    }

    @Test fun `a config that refuses none of the chosen voices sends no language back`() {
        OnlineTtsService.save(prefs, openAi, "k")
        prefs.setVoiceChoice(SourceLangId.JA, VoiceChoice.Online(marin))
        val configs = listOf(
            "the same model" to openAi.copy(instructions = "Slowly."),
            "a model the preset's table does not name" to openAi.copy(model = OnlineTtsChoice("some-later-model")),
            "no model" to openAi.copy(model = null),
            "the voice typed in by the user" to older.copy(manualVoices = listOf(" marin ")),
        )
        for ((what, config) in configs) {
            assertTrue(what, OnlineTtsService.voicesNotTaken(prefs, config).isEmpty())
            OnlineTtsService.save(prefs, config, "k")
            assertEquals(what, VoiceChoice.Online(marin), prefs.voiceChoice(SourceLangId.JA))
        }
    }

    @Test fun `another service is not asked about voices it forgets anyway, nor is a first one`() {
        assertTrue("no service connected", OnlineTtsService.voicesNotTaken(prefs, older).isEmpty())
        OnlineTtsService.save(prefs, openAi, "k")
        prefs.setVoiceChoice(SourceLangId.JA, VoiceChoice.Online(marin))
        val elsewhere = older.copy(url = "http://192.168.1.5:8880/v1/audio/speech")
        assertTrue(OnlineTtsService.voicesNotTaken(prefs, elsewhere).isEmpty())
    }

    @Test fun `a voice left chosen under a model that refuses it is found on the next save, whatever that save changes`() {
        // The state a build without this rule could leave behind.
        OnlineTtsService.save(prefs, older, "k")
        prefs.setVoiceChoice(SourceLangId.JA, VoiceChoice.Online(marin))
        val again = older.copy(instructions = "Slowly.")
        assertEquals(mapOf(SourceLangId.JA to marin), OnlineTtsService.voicesNotTaken(prefs, again))
        OnlineTtsService.save(prefs, again, "k")
        assertEquals(VoiceChoice.Device(null), prefs.voiceChoice(SourceLangId.JA))
    }

    @Test fun `a save that stores nothing sends no language back`() {
        OnlineTtsService.save(prefs, openAi, "k")
        prefs.setVoiceChoice(SourceLangId.JA, VoiceChoice.Online(marin))
        codec.encryptWorks = false
        assertFalse(OnlineTtsService.save(prefs, older, "another-key"))
        assertEquals(VoiceChoice.Online(marin), prefs.voiceChoice(SourceLangId.JA))
        assertEquals("gpt-4o-mini-tts", OnlineTtsService.config(prefs)!!.model?.id)
    }

    // ── The service's lists ──────────────────────────────────────────────

    @Test fun `the voices are the service's own list, asked with the key`() = runBlocking {
        val seen = mutableListOf<SeenRequest>()
        OnlineTtsService.client = OnlineTtsClient(
            recordingClient(seen) { CannedReply(200, """{"voices":[{"id":"jf_alpha","name":"Alpha"}]}""") },
        )
        val voices = OnlineTtsService.voices(openAi, " sk-SECRET ")
        assertEquals(listOf(OnlineTtsChoice("jf_alpha", "Alpha")), voices)
        assertEquals("Bearer sk-SECRET", seen.single().request.header("Authorization"))
    }

    @Test fun `hand-listed voices are offered whenever the service's own list gives none`() = runBlocking {
        val builtIn = OnlineTtsPreset.OPENAI.spec.builtInVoices.map { OnlineTtsChoice(it) }
        // Every way of not getting a list. OpenAI has built-in voices and no
        // call that lists them, and what it answers a list request is not
        // documented: a status, a list object with no voices field, an
        // empty list. Whichever it is, its voices can be picked.
        val answers = listOf(
            CannedReply(404, "Not Found"),
            CannedReply(405, """{"error":{"message":"Not allowed to GET"}}"""),
            CannedReply(401, """{"error":{"message":"Incorrect API key provided"}}"""),
            CannedReply(500, "oops"),
            CannedReply(200, """{"object":"list","data":[],"has_more":false}"""),
            CannedReply(200, """{"voices":[]}"""),
            CannedReply(200, "<html>ok</html>"),
        )
        for (answer in answers) {
            OnlineTtsService.client = OnlineTtsClient(recordingClient { answer })
            assertEquals("answer ${answer.code} ${answer.body}", builtIn, OnlineTtsService.voices(openAi, "k"))
        }
        // The user's own typed ids follow the built-in ones.
        OnlineTtsService.client = OnlineTtsClient(recordingClient { CannedReply(404, "Not Found") })
        assertEquals(
            builtIn + OnlineTtsChoice("jf_alpha"),
            OnlineTtsService.voices(openAi.copy(manualVoices = listOf("jf_alpha")), "k"),
        )
        // And a model that takes fewer of the built-in voices is offered
        // only those: the rest it would refuse.
        val older = OnlineTtsService.voices(openAi.copy(model = OnlineTtsChoice("tts-1")), "k").map { it.id }
        assertEquals(9, older.size)
        assertFalse(older.contains("marin"))
        assertTrue(older.containsAll(listOf("sage", "shimmer")))
    }

    @Test fun `the service's own voices are used alone when it lists any`() = runBlocking {
        OnlineTtsService.client = OnlineTtsClient(
            recordingClient { CannedReply(200, """{"voices":[{"id":"jf_alpha","name":"Alpha"}]}""") },
        )
        // The OpenAI preset's hand-listed names are not mixed into a
        // self-hosted server's own list.
        assertEquals(listOf(OnlineTtsChoice("jf_alpha", "Alpha")), OnlineTtsService.voices(openAi, "k"))
    }

    @Test fun `with no hand-listed voices a list that fails is the failure, and an empty one is empty`() {
        val none = OnlineTtsConfig(OnlineTtsPreset.ELEVENLABS, model = OnlineTtsChoice("m"))
        assertTrue(none.handListedVoices.isEmpty())
        for ((code, kind) in listOf(
            401 to BackendFailureKind.AUTH, 403 to BackendFailureKind.AUTH,
            404 to BackendFailureKind.REJECTED, 500 to BackendFailureKind.SERVER_ERROR,
        )) {
            OnlineTtsService.client = OnlineTtsClient(recordingClient { CannedReply(code, """{"error":"no"}""") })
            try {
                runBlocking { OnlineTtsService.voices(none, "wrong") }
                fail("status $code is a failure")
            } catch (e: OnlineTtsException) {
                assertEquals(kind, e.failure.kind)
                assertEquals("the service's words travel with it", """{"error":"no"}""", e.detail)
            }
        }
        OnlineTtsService.client = OnlineTtsClient(recordingClient { CannedReply(200, """{"object":"list","data":[]}""") })
        try {
            runBlocking { OnlineTtsService.voices(none, "k") }
            fail("a reply that is no list is a failure")
        } catch (e: OnlineTtsException) {
            assertEquals(BackendFailureKind.BAD_RESPONSE, e.failure.kind)
        }
        OnlineTtsService.client = OnlineTtsClient(recordingClient { CannedReply(200, """{"voices":[]}""") })
        assertEquals(emptyList<OnlineTtsChoice>(), runBlocking { OnlineTtsService.voices(none, "k") })
    }

    @Test fun `the models are the service's list, and a missing list is a failure`() {
        OnlineTtsService.client = OnlineTtsClient(
            recordingClient { CannedReply(200, """{"data":[{"id":"tts-1"},{"id":"gpt-4o-mini-tts"}]}""") },
        )
        assertEquals(
            listOf(OnlineTtsChoice("tts-1"), OnlineTtsChoice("gpt-4o-mini-tts")),
            runBlocking { OnlineTtsService.models(openAi, "k") },
        )
        OnlineTtsService.client = OnlineTtsClient(recordingClient { CannedReply(404, "") })
        try {
            runBlocking { OnlineTtsService.models(openAi, "k") }
            fail("a model list has no hand-listed stand-in")
        } catch (e: OnlineTtsException) {
            assertEquals(404, e.failure.httpCode)
        }
    }

    // ── What asking for the models says about the URL and the key ────────

    private fun answerTo(reply: CannedReply): OnlineTtsService.ModelsAnswer {
        OnlineTtsService.client = OnlineTtsClient(recordingClient { reply })
        return runBlocking { OnlineTtsService.modelsAnswer(openAi, "k") }
    }

    private fun refusalOf(reply: CannedReply): OnlineTtsException = try {
        answerTo(reply)
        throw AssertionError("status ${reply.code} is not an answer to save on")
    } catch (e: OnlineTtsException) {
        e
    }

    // ── A key a list request cannot carry ────────────────────────────────

    /** A request that keeps its key in the body: a list request, which has
     *  no body, goes without it. */
    private val bodyKeyed = openAi.copy(
        request = "Content-Type: application/json\n\n" +
            "{\"api_key\":\"{key}\",\"model\":\"{model}\",\"voice\":\"{voice}\",\"input\":\"{text}\"}",
    )
    private val unauthorized = CannedReply(401, """{"error":"API key required"}""")

    @Test fun `a 401 to a list request that could not carry the key is the request refused, not the key`() {
        OnlineTtsService.client = OnlineTtsClient(recordingClient { unauthorized })
        val answer = runBlocking { OnlineTtsService.modelsAnswer(bodyKeyed, "sk-SECRET") }
        val said = (answer as OnlineTtsService.ModelsAnswer.NoList).said!!
        assertEquals(BackendFailureKind.REJECTED, said.failure.kind)
        assertEquals(401, said.failure.httpCode)
        assertEquals("the service's words are kept", """{"error":"API key required"}""", said.detail)

        // The voice list is asked the same way and says the same.
        try {
            runBlocking { OnlineTtsService.voices(bodyKeyed.copy(model = OnlineTtsChoice("m")), "sk-SECRET") }
            // The preset's built-in voices stand in for a list that failed.
        } catch (e: OnlineTtsException) {
            fail("hand-listed voices stand in: $e")
        }
        val listOnly = OnlineTtsConfig(
            OnlineTtsPreset.ELEVENLABS,
            request = "Content-Type: application/json\n\n{\"xi_api_key\":\"{key}\",\"text\":\"{text}\"}",
        )
        try {
            runBlocking { OnlineTtsService.voices(listOnly, "sk-SECRET") }
            fail("no list and no hand-listed voice")
        } catch (e: OnlineTtsException) {
            assertEquals(BackendFailureKind.REJECTED, e.failure.kind)
            assertEquals(401, e.failure.httpCode)
        }
    }

    @Test fun `a 401 is still the key refused when the key was sent, and when none was entered`() {
        OnlineTtsService.client = OnlineTtsClient(recordingClient { unauthorized })
        for ((what, config, key) in listOf(
            Triple("sent in a header", openAi, "sk-WRONG"),
            Triple("sent in the URL", bodyKeyed.copy(url = "https://tts.example.com/v1/audio/speech?k={key}"), "sk-WRONG"),
            Triple("none entered", bodyKeyed, "  "),
        )) {
            try {
                runBlocking { OnlineTtsService.modelsAnswer(config, key) }
                fail(what)
            } catch (e: OnlineTtsException) {
                assertEquals(what, BackendFailureKind.AUTH, e.failure.kind)
            }
        }
    }

    @Test fun `a service that takes its key in the URL is asked for its lists with it`() {
        val seen = mutableListOf<SeenRequest>()
        OnlineTtsService.client = OnlineTtsClient(
            recordingClient(seen) { request ->
                if (request.request.url.queryParameter("api_key") == "sk-SECRET") {
                    CannedReply(200, """{"data":[{"id":"tts-1"}]}""")
                } else {
                    unauthorized
                }
            },
        )
        val urlKeyed = openAi.copy(
            url = "https://tts.example.com/v1/audio/speech?api_key={key}",
            request = "Content-Type: application/json\n\n{\"model\":\"{model}\",\"voice\":\"{voice}\",\"input\":\"{text}\"}",
        )
        val answer = runBlocking { OnlineTtsService.modelsAnswer(urlKeyed, " sk-SECRET ") }
        assertEquals(listOf("tts-1"), (answer as OnlineTtsService.ModelsAnswer.Listed).models.map { it.id })
        assertEquals("https://tts.example.com/v1/models?api_key=sk-SECRET", seen.single().request.url.toString())
    }

    @Test fun `a service that lists its models has proven the URL and the key`() {
        val answer = answerTo(CannedReply(200, """{"data":[{"id":"tts-1"},{"id":"gpt-4o-mini-tts"}]}"""))
        assertEquals(
            listOf(OnlineTtsChoice("tts-1"), OnlineTtsChoice("gpt-4o-mini-tts")),
            (answer as OnlineTtsService.ModelsAnswer.Listed).models,
        )
    }

    @Test fun `a key the service refuses, and a service that cannot answer, are no answer to save on`() {
        assertEquals(BackendFailureKind.AUTH, refusalOf(CannedReply(401, "no")).failure.kind)
        assertEquals(BackendFailureKind.AUTH, refusalOf(CannedReply(403, "no")).failure.kind)
        assertEquals(BackendFailureKind.RATE_LIMITED, refusalOf(CannedReply(429, "slow down")).failure.kind)
        assertEquals(BackendFailureKind.SERVER_ERROR, refusalOf(CannedReply(500, "oops")).failure.kind)
        assertEquals(BackendFailureKind.SERVER_ERROR, refusalOf(CannedReply(503, "oops")).failure.kind)
        OnlineTtsService.client = OnlineTtsClient(transportFailingClient { java.io.IOException("no route") })
        try {
            runBlocking { OnlineTtsService.modelsAnswer(openAi, "k") }
            fail("an unreachable service is not an answer")
        } catch (e: OnlineTtsException) {
            assertEquals(BackendFailureKind.UNREACHABLE, e.failure.kind)
        }
    }

    @Test fun `any other answer that is not a list leaves the key unchecked, with what was said`() {
        // Not guessed from the status: ElevenLabs answers this call 404
        // when it is sent no key at all, and a self-hosted server with no
        // such call may answer anything.
        for (reply in listOf(
            CannedReply(404, """{"detail":{"message":"Workspace 1anonymous1 not found."}}"""),
            CannedReply(405, "Method Not Allowed"),
            CannedReply(400, "Bad Request"),
            CannedReply(501, "Not Implemented"),
            CannedReply(200, "<html>ok</html>"),
        )) {
            val answer = answerTo(reply)
            assertTrue("status ${reply.code}", answer is OnlineTtsService.ModelsAnswer.NoList)
            assertEquals(reply.body, (answer as OnlineTtsService.ModelsAnswer.NoList).said!!.detail)
        }
        assertTrue(answerTo(CannedReply(200, """{"data":[]}""")) is OnlineTtsService.ModelsAnswer.NoList)
    }

    @Test fun `the connected service's voices need a connected service`() {
        try {
            runBlocking {
                OnlineTtsService.prefsFor = { prefs }
                OnlineTtsService.voices(ctx)
            }
            fail("no service is connected")
        } catch (e: OnlineTtsException) {
            assertEquals(BackendFailureKind.REJECTED, e.failure.kind)
        } finally {
            OnlineTtsService.prefsFor = { Prefs(it) }
        }
    }
}
