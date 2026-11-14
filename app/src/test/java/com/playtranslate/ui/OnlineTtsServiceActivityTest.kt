package com.playtranslate.ui

import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.audio.online.OnlineTtsChoice
import com.playtranslate.audio.online.OnlineTtsClient
import com.playtranslate.audio.online.OnlineTtsConfig
import com.playtranslate.audio.online.OnlineTtsPreset
import com.playtranslate.audio.online.OnlineTtsService
import com.playtranslate.language.SourceLangId
import com.playtranslate.security.SecretCodec
import com.playtranslate.translation.CannedReply
import com.playtranslate.translation.SeenRequest
import com.playtranslate.translation.recordingClient
import com.playtranslate.translation.transportFailingClient
import com.playtranslate.tts.VoiceChoice
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * [OnlineTtsServiceActivity]: a row for each token the request text holds
 * and none for the others; a preset bringing its own URL and request; Save
 * storing the whole page or nothing; a model that refuses a language's voice
 * said before it is stored; the model list fetched with the page as typed;
 * Remove; and a recreate keeping what was typed.
 */
@RunWith(RobolectricTestRunner::class)
class OnlineTtsServiceActivityTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private fun sp() = ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE)

    private class FakeCodec(var encryptWorks: Boolean = true) : SecretCodec {
        override fun encrypt(context: String, plaintext: String): String? =
            if (encryptWorks) "enc:$context|$plaintext" else null
        override fun decrypt(context: String, stored: String): String? =
            stored.removePrefix("enc:$context|").takeIf { stored.startsWith("enc:$context|") }
    }

    private val codec = FakeCodec()
    private lateinit var prefs: Prefs
    private val savedClient = OnlineTtsService.client
    private val seen = mutableListOf<SeenRequest>()
    private var controller: ActivityController<OnlineTtsServiceActivity>? = null

    /** What the fake service answers every request with: its model list. */
    @Volatile private var modelsReply =
        CannedReply(200, """{"data":[{"id":"tts-1"},{"id":"gpt-4o-mini-tts"}]}""")

    /** When set, the service does not answer until it is counted down. */
    @Volatile private var gate: CountDownLatch? = null

    @Before fun setUp() {
        sp().edit().clear().commit()
        prefs = Prefs(ctx, codec)
        OnlineTtsService.prefsFor = { prefs }
        OnlineTtsService.client = OnlineTtsClient(
            recordingClient(seen) {
                gate?.await(5, TimeUnit.SECONDS)
                modelsReply
            },
        )
    }

    @After fun tearDown() {
        controller?.let { runCatching { it.pause().stop().destroy() } }
        OnlineTtsService.client = savedClient
        OnlineTtsService.prefsFor = { Prefs(it) }
        sp().edit().clear().commit()
    }

    private fun open(): OnlineTtsServiceActivity =
        Robolectric.buildActivity(OnlineTtsServiceActivity::class.java, OnlineTtsServiceActivity.intent(ctx))
            .also { controller = it }.setup().get()

    private fun OnlineTtsServiceActivity.text(id: Int) = findViewById<EditText>(id).text.toString()
    private fun OnlineTtsServiceActivity.type(id: Int, value: String) = findViewById<EditText>(id).setText(value)
    private fun OnlineTtsServiceActivity.shown(id: Int) = findViewById<View>(id).visibility == View.VISIBLE
    private fun OnlineTtsServiceActivity.rowValue(id: Int) =
        findViewById<View>(id).findViewById<TextView>(R.id.tvRowValue).text.toString()
    private fun OnlineTtsServiceActivity.modelTitles(): List<String> {
        val rows = findViewById<LinearLayout>(R.id.modelRows)
        return (0 until rows.childCount).mapNotNull { rows.getChildAt(it).findViewById<TextView>(R.id.tvRowTitle) }
            .map { it.text.toString() }
    }

    /** Runs the main looper until [done], for work that hops through IO. */
    private fun pumpUntil(what: String, done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!done()) {
            assertTrue("timed out waiting for $what", System.currentTimeMillis() < deadline)
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    // ── A first open ─────────────────────────────────────────────────────

    @Test fun `with no service the page opens on the first preset, with nothing to remove`() {
        val page = open()
        val spec = OnlineTtsPreset.OPENAI.spec
        assertEquals(spec.url, page.text(R.id.etVoiceServiceUrl))
        assertEquals(spec.request, page.text(R.id.etVoiceServiceRequest))
        assertEquals(
            "the box is for the user's own voice ids: no built-in ones are put in it",
            "",
            page.text(R.id.etVoiceServiceManualVoices),
        )
        assertEquals("", page.text(R.id.etVoiceServiceKey))
        assertEquals("OpenAI-compatible", page.rowValue(R.id.rowService))
        assertEquals("Choose", page.rowValue(R.id.rowModel))
        assertFalse(page.shown(R.id.cardVoiceServiceRemove))
    }

    @Test fun `a connected service opens as stored, its key shown, and can be removed`() {
        OnlineTtsService.save(
            prefs,
            OnlineTtsConfig(
                OnlineTtsPreset.ELEVENLABS, model = OnlineTtsChoice("eleven_multilingual_v2", "Multilingual v2"),
            ),
            "sk-SECRET",
        )
        val page = open()
        assertEquals(OnlineTtsPreset.ELEVENLABS.spec.url, page.text(R.id.etVoiceServiceUrl))
        assertEquals("sk-SECRET", page.text(R.id.etVoiceServiceKey))
        assertEquals("ElevenLabs-compatible", page.rowValue(R.id.rowService))
        assertEquals("Multilingual v2", page.rowValue(R.id.rowModel))
        assertTrue(page.shown(R.id.cardVoiceServiceRemove))
    }

    // ── A row for each token, none for the others ────────────────────────

    @Test fun `the rows are the tokens the request text holds`() {
        val page = open()
        assertTrue(page.shown(R.id.sectionVoiceServiceKey))
        assertTrue(page.shown(R.id.sectionVoiceServiceModel))
        assertTrue(page.shown(R.id.sectionVoiceServiceInstructions))

        page.type(R.id.etVoiceServiceRequest, "{\"input\":\"{text}\",\"voice\":\"{voice}\"}")
        assertFalse(page.shown(R.id.sectionVoiceServiceKey))
        assertFalse(page.shown(R.id.sectionVoiceServiceModel))
        assertFalse(page.shown(R.id.sectionVoiceServiceInstructions))

        page.type(R.id.etVoiceServiceUrl, "https://tts.example.com/{model}/speak?key={key}")
        assertTrue("a token in the URL counts", page.shown(R.id.sectionVoiceServiceKey))
        assertTrue(page.shown(R.id.sectionVoiceServiceModel))
    }

    @Test fun `the ElevenLabs preset has no instructions row`() {
        val page = open()
        page.applyPreset(OnlineTtsPreset.ELEVENLABS)
        assertFalse(page.shown(R.id.sectionVoiceServiceInstructions))
        assertTrue(page.shown(R.id.sectionVoiceServiceKey))
        assertTrue(page.shown(R.id.sectionVoiceServiceModel))
    }

    // ── A preset ─────────────────────────────────────────────────────────

    @Test fun `another kind of service brings its URL and request, drops the model and the key, keeps the instructions`() {
        val page = open()
        page.type(R.id.etVoiceServiceKey, "sk-SECRET")
        page.type(R.id.etVoiceServiceInstructions, "Slowly.")
        page.type(R.id.etVoiceServiceOtherModel, "tts-1")
        page.findViewById<View>(R.id.btnVoiceServiceUseModel).performClick()
        assertEquals("tts-1", page.rowValue(R.id.rowModel))

        page.applyPreset(OnlineTtsPreset.ELEVENLABS)
        val spec = OnlineTtsPreset.ELEVENLABS.spec
        assertEquals(spec.url, page.text(R.id.etVoiceServiceUrl))
        assertEquals(spec.request, page.text(R.id.etVoiceServiceRequest))
        assertEquals("", page.text(R.id.etVoiceServiceManualVoices))
        assertEquals("Choose", page.rowValue(R.id.rowModel))
        assertEquals("ElevenLabs-compatible", page.rowValue(R.id.rowService))
        assertEquals("a key belongs to the service it was typed for", "", page.text(R.id.etVoiceServiceKey))
        assertEquals("Slowly.", page.text(R.id.etVoiceServiceInstructions))
    }

    @Test fun `after another kind of service is chosen, Save does not send it the stored service's key`() {
        OnlineTtsService.save(prefs, OnlineTtsConfig(model = OnlineTtsChoice("tts-1")), "sk-STORED")
        val page = open()
        page.applyPreset(OnlineTtsPreset.ELEVENLABS)
        page.saveAndWait()
        val asked = seen.single().request
        assertEquals("api.elevenlabs.io", asked.url.host)
        assertNull("no key was typed for this service, so none is sent", asked.header("xi-api-key"))
        assertFalse(asked.headers.toString().contains("sk-STORED"))
        assertFalse(asked.url.toString().contains("sk-STORED"))
    }

    @Test fun `leaving without Save after another kind was chosen keeps the stored service, and the page opens on it with its key`() {
        val stored = OnlineTtsConfig(model = OnlineTtsChoice("tts-1"))
        OnlineTtsService.save(prefs, stored, "sk-STORED")
        val page = open()
        page.applyPreset(OnlineTtsPreset.ELEVENLABS)
        assertEquals("", page.text(R.id.etVoiceServiceKey))
        page.onBackPressedDispatcher.onBackPressed()
        assertTrue(page.isFinishing)
        controller!!.pause().stop().destroy()
        controller = null

        val connected = OnlineTtsService.connection(prefs)!!
        assertEquals(OnlineTtsPreset.OPENAI, connected.config.preset)
        assertEquals("sk-STORED", connected.key)

        val again = open()
        assertEquals("OpenAI-compatible", again.rowValue(R.id.rowService))
        assertEquals("tts-1", again.rowValue(R.id.rowModel))
        assertEquals("sk-STORED", again.text(R.id.etVoiceServiceKey))
    }

    @Test fun `a recreated page does not put the stored key back in a field another kind of service emptied`() {
        OnlineTtsService.save(prefs, OnlineTtsConfig(model = OnlineTtsChoice("tts-1")), "sk-STORED")
        open().applyPreset(OnlineTtsPreset.ELEVENLABS)
        controller!!.recreate()
        val again = controller!!.get()
        assertEquals("ElevenLabs-compatible", again.rowValue(R.id.rowService))
        assertEquals("", again.text(R.id.etVoiceServiceKey))
    }

    // ── Save ─────────────────────────────────────────────────────────────

    private fun OnlineTtsServiceActivity.pressSave() =
        findViewById<View>(R.id.btnVoiceServiceSave).performClick()

    private fun OnlineTtsServiceActivity.saveEnabled() =
        findViewById<View>(R.id.btnVoiceServiceSave).isEnabled

    /** Presses Save and waits for its check of the service to end: the page
     *  closed, or Save usable again. */
    private fun OnlineTtsServiceActivity.saveAndWait() {
        pressSave()
        pumpUntil("Save's check of the service") { isFinishing || saveEnabled() }
    }

    private fun OnlineTtsServiceActivity.typeModel(id: String) {
        type(R.id.etVoiceServiceOtherModel, id)
        findViewById<View>(R.id.btnVoiceServiceUseModel).performClick()
    }

    @Test fun `Save asks the service for its models at the URL and with the key as typed`() {
        val page = open()
        page.type(R.id.etVoiceServiceUrl, "http://192.168.1.5:8880/v1/audio/speech")
        page.type(R.id.etVoiceServiceKey, "  sk-TYPED ")
        page.saveAndWait()
        val sent = seen.single().request
        assertEquals("GET", sent.method)
        assertEquals("http://192.168.1.5:8880/v1/models", sent.url.toString())
        assertEquals("Bearer sk-TYPED", sent.header("Authorization"))
    }

    @Test fun `Save picks the model for a user who chose none, the one the preset prefers`() {
        val page = open()
        page.type(R.id.etVoiceServiceKey, "sk-SECRET")
        assertEquals("Choose", page.rowValue(R.id.rowModel))
        page.saveAndWait()
        assertTrue(page.isFinishing)
        assertEquals(
            "the one model that reads instructions, ahead of the older one listed before it",
            OnlineTtsChoice("gpt-4o-mini-tts"),
            OnlineTtsService.config(prefs)!!.model,
        )
        assertEquals("sk-SECRET", prefs.onlineTtsApiKey)
    }

    @Test fun `among everything an account lists, the speech model the preset knows is the one picked`() {
        modelsReply = CannedReply(
            200, """{"data":[{"id":"gpt-4o"},{"id":"whisper-1"},{"id":"tts-1-hd"},{"id":"tts-1"},{"id":"text-embedding-3-small"}]}""",
        )
        val page = open()
        page.saveAndWait()
        assertEquals(OnlineTtsChoice("tts-1"), OnlineTtsService.config(prefs)!!.model)
    }

    @Test fun `the ElevenLabs preset picks its fast model, by the name the service gives it`() {
        modelsReply = CannedReply(
            200,
            """[{"model_id":"eleven_multilingual_v2","name":"Eleven Multilingual v2","can_do_text_to_speech":true},
                {"model_id":"eleven_flash_v2_5","name":"Eleven Flash v2.5","can_do_text_to_speech":true},
                {"model_id":"eleven_english_sts_v2","name":"Speech to speech","can_do_text_to_speech":false}]""",
        )
        val page = open()
        page.applyPreset(OnlineTtsPreset.ELEVENLABS)
        page.type(R.id.etVoiceServiceKey, "sk-SECRET")
        page.saveAndWait()
        assertEquals(
            OnlineTtsChoice("eleven_flash_v2_5", "Eleven Flash v2.5"),
            OnlineTtsService.config(prefs)!!.model,
        )
        assertEquals("https://api.elevenlabs.io/v1/models", seen.single().request.url.toString())
    }

    @Test fun `a server that lists one model gets that one`() {
        modelsReply = CannedReply(200, """{"data":[{"id":"kokoro"}]}""")
        val page = open()
        page.saveAndWait()
        assertEquals(OnlineTtsChoice("kokoro"), OnlineTtsService.config(prefs)!!.model)
    }

    @Test fun `several models and none the preset knows are shown for the user to pick, and nothing is stored`() {
        modelsReply = CannedReply(200, """{"data":[{"id":"alpha"},{"id":"beta"}]}""")
        val page = open()
        page.saveAndWait()
        assertFalse(page.isFinishing)
        assertNull("no model is guessed", OnlineTtsService.config(prefs))
        assertTrue(page.shown(R.id.modelPanel))
        assertEquals(listOf("alpha", "beta"), page.modelTitles())

        page.findViewById<LinearLayout>(R.id.modelRows).getChildAt(2).performClick()
        assertEquals("beta", page.rowValue(R.id.rowModel))
        page.saveAndWait()
        assertTrue(page.isFinishing)
        assertEquals(OnlineTtsChoice("beta"), OnlineTtsService.config(prefs)!!.model)
    }

    @Test fun `a model the user chose is never replaced, listed or not`() {
        // Listed, though not the one the preset would pick: stored as chosen.
        val page = open()
        page.typeModel("tts-1")
        page.saveAndWait()
        assertEquals(OnlineTtsChoice("tts-1"), OnlineTtsService.config(prefs)!!.model)

        // Not listed: stored as typed too, once the user has said so.
        sp().edit().clear().commit()
        val other = open()
        other.typeModel("a-model-the-list-does-not-hold")
        other.saveAndWait()
        assertNull("not without being asked", OnlineTtsService.config(prefs))
        other.pressAlertButton("Save anyway")
        assertTrue(other.isFinishing)
        assertEquals(OnlineTtsChoice("a-model-the-list-does-not-hold"), OnlineTtsService.config(prefs)!!.model)
    }

    // ── A model the service does not list ────────────────────────────────

    private val notListedTitle = "The service does not list this model"

    @Test fun `a model the service's list does not hold is said before anything is stored, and Cancel leaves the page to choose another`() {
        val page = open()
        page.type(R.id.etVoiceServiceKey, "sk-SECRET")
        page.typeModel("tts-1-hd")
        page.saveAndWait()
        assertFalse(page.isFinishing)
        assertNull(OnlineTtsService.config(prefs))
        assertTrue(notListedTitle in page.texts())
        assertTrue(
            ("The service's list of models does not include tts-1-hd. If you save, speech requests may be " +
                "refused. To choose a model the service lists, cancel and tap Model.") in page.texts(),
        )

        page.pressAlertButton("Cancel")
        assertFalse(page.isFinishing)
        assertNull(OnlineTtsService.config(prefs))
        // A model the service does list is saved with no question.
        page.typeModel("tts-1")
        page.saveAndWait()
        assertTrue(page.isFinishing)
        assertEquals(OnlineTtsChoice("tts-1"), OnlineTtsService.config(prefs)!!.model)
    }

    @Test fun `a model left on the page from another server is caught when only the URL was changed`() {
        // The saved service, with a model whose name is not its id.
        val saved = OnlineTtsConfig(model = OnlineTtsChoice("gpt-4o-mini-tts", "GPT-4o mini TTS"))
        OnlineTtsService.save(prefs, saved, "sk-SECRET")
        modelsReply = CannedReply(200, """{"data":[{"id":"kokoro"}]}""")
        val page = open()
        page.type(R.id.etVoiceServiceUrl, "http://192.168.1.5:8880/v1/audio/speech")
        assertEquals("the model row still reads the old model", "GPT-4o mini TTS", page.rowValue(R.id.rowModel))
        page.saveAndWait()

        assertFalse(page.isFinishing)
        assertTrue(notListedTitle in page.texts())
        assertTrue(page.texts().any { it.startsWith("The service's list of models does not include GPT-4o mini TTS.") })
        assertNull("the saved service is as it was", OnlineTtsService.config(prefs)!!.url)
    }

    @Test fun `a request that names no model is not asked about the one left on the page`() {
        val page = open()
        page.typeModel("a-model-the-list-does-not-hold")
        page.type(R.id.etVoiceServiceRequest, "Authorization: Bearer {key}\n\n{\"input\":\"{text}\",\"voice\":\"{voice}\"}")
        page.saveAndWait()
        assertTrue(page.isFinishing)
    }

    @Test fun `the question about the model comes first, the one about the voices it refuses after`() {
        connectWithNewestModelVoices()
        val page = open()
        page.typeModel("tts-1-hd")
        page.saveAndWait()
        assertTrue(notListedTitle in page.texts())
        assertFalse(voicesTitle in page.texts())

        page.pressAlertButton("Save anyway")
        assertFalse("the second question", page.isFinishing)
        assertTrue(voicesTitle in page.texts())
        assertEquals(OnlineTtsChoice("gpt-4o-mini-tts"), OnlineTtsService.config(prefs)!!.model)

        page.pressAlertButton("Save anyway")
        assertTrue(page.isFinishing)
        assertEquals(OnlineTtsChoice("tts-1-hd"), OnlineTtsService.config(prefs)!!.model)
        assertEquals(VoiceChoice.Device(null), prefs.voiceChoice(SourceLangId.JA))
    }

    @Test fun `a request that names no model needs none`() {
        val page = open()
        page.type(R.id.etVoiceServiceRequest, "Authorization: Bearer {key}\n\n{\"input\":\"{text}\",\"voice\":\"{voice}\"}")
        page.saveAndWait()
        assertTrue(page.isFinishing)
        assertNull(OnlineTtsService.config(prefs)!!.model)
    }

    @Test fun `a key the service refuses stores nothing and the page stays`() {
        modelsReply = CannedReply(401, """{"error":{"message":"Incorrect API key provided"}}""")
        val page = open()
        page.type(R.id.etVoiceServiceKey, "sk-WRONG")
        page.typeModel("tts-1")
        page.saveAndWait()
        assertFalse(page.isFinishing)
        assertNull(OnlineTtsService.config(prefs))
        assertEquals("", prefs.onlineTtsApiKey)
    }

    @Test fun `a service that cannot be reached stores nothing`() {
        OnlineTtsService.client = OnlineTtsClient(transportFailingClient { IOException("no route to host") })
        val page = open()
        page.typeModel("tts-1")
        page.saveAndWait()
        assertFalse(page.isFinishing)
        assertNull(OnlineTtsService.config(prefs))
    }

    @Test fun `a service in trouble stores nothing`() {
        for (status in listOf(429, 500, 503)) {
            modelsReply = CannedReply(status, "try later")
            val page = open()
            page.typeModel("tts-1")
            page.saveAndWait()
            assertFalse("status $status", page.isFinishing)
            assertNull("status $status", OnlineTtsService.config(prefs))
        }
    }

    @Test fun `a service with no model list is stored only once the user says to save unchecked`() {
        // No such call, or an answer that is no list: the key is neither
        // proven nor refused.
        for (reply in listOf(
            CannedReply(404, """{"detail":"Not Found"}"""),
            CannedReply(405, "Method Not Allowed"),
            CannedReply(200, "<html>ok</html>"),
            CannedReply(200, """{"data":[]}"""),
        )) {
            sp().edit().clear().commit()
            modelsReply = reply
            val page = open()
            page.typeModel("kokoro")
            page.saveAndWait()
            assertFalse("${reply.code}: not stored by Save itself", page.isFinishing)
            assertNull("${reply.code}", OnlineTtsService.config(prefs))

            page.saveUnchecked()
            assertTrue(page.isFinishing)
            assertEquals(OnlineTtsChoice("kokoro"), OnlineTtsService.config(prefs)!!.model)
        }
    }

    @Test fun `with no model list and no model there is nothing to save`() {
        modelsReply = CannedReply(404, """{"detail":"Not Found"}""")
        val page = open()
        page.saveAndWait()
        assertFalse(page.isFinishing)
        assertNull(OnlineTtsService.config(prefs))
        // Saving unchecked would still need the model the request names.
        page.saveUnchecked()
        assertFalse(page.isFinishing)
        assertNull(OnlineTtsService.config(prefs))
    }

    @Test fun `while the service is asked Save cannot be pressed again, and leaving stores nothing`() {
        val held = CountDownLatch(1).also { gate = it }
        val page = open()
        page.type(R.id.etVoiceServiceKey, "sk-SECRET")
        page.pressSave()
        assertFalse("the check is running", page.saveEnabled())
        assertTrue(page.shown(R.id.progressVoiceServiceSave))
        page.pressSave()

        page.onBackPressedDispatcher.onBackPressed()
        assertTrue(page.isFinishing)
        held.countDown()
        pumpUntil("the held answer") { seen.isNotEmpty() }
        repeat(20) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
        assertEquals("one request, not two", 1, seen.size)
        assertNull("leaving is not saving", OnlineTtsService.config(prefs))
    }

    /** A check held on the service, with [reply] as what it will answer;
     *  returns the page, on which Save was pressed, and the latch that
     *  lets the answer through. */
    private fun heldCheck(reply: CannedReply): Pair<OnlineTtsServiceActivity, CountDownLatch> {
        val held = CountDownLatch(1).also { gate = it }
        modelsReply = reply
        val page = open()
        page.type(R.id.etVoiceServiceKey, "sk-SECRET")
        page.typeModel("tts-1")
        page.pressSave()
        pumpUntil("the check's request") { seen.isNotEmpty() }
        return page to held
    }

    private fun pumpFor(ms: Long) {
        val until = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < until) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    @Test fun `a failure that answers a check left by Back is taken up by no page`() {
        val (page, held) = heldCheck(CannedReply(401, """{"error":{"message":"Incorrect API key provided"}}"""))
        page.onBackPressedDispatcher.onBackPressed()
        assertTrue(page.isFinishing)
        held.countDown()
        pumpFor(300)
        // Taken up, the failure would put Save back and raise its alert
        // over whatever page is in front by then.
        assertFalse("the closed page is as the check left it", page.saveEnabled())
        assertFalse("The voice service did not answer" in page.texts())
        assertNull(OnlineTtsService.config(prefs))
    }

    @Test fun `a check left by the X stores nothing either`() {
        val (page, held) = heldCheck(modelsReply)
        val bar = page.findViewById<ViewGroup>(R.id.toolbar)
        (0 until bar.childCount).map(bar::getChildAt).first { it is android.widget.ImageButton }.performClick()
        assertTrue(page.isFinishing)
        held.countDown()
        pumpFor(300)
        assertFalse(page.saveEnabled())
        assertNull("leaving is not saving", OnlineTtsService.config(prefs))
    }

    // ── A key that was not entered ───────────────────────────────────────

    private val noSuchList = CannedReply(404, """{"detail":"Not Found"}""")
    private val keylessRequest = "{\"model\":\"{model}\",\"voice\":\"{voice}\",\"input\":\"{text}\"}"

    /** The message of the one alert up that speaks of the list of models. */
    private fun OnlineTtsServiceActivity.noListMessage(): String =
        texts().single { it.contains("did not return a list of models") }

    @Test fun `with no key entered and no list from the service, Save says first that no key is entered`() {
        modelsReply = noSuchList
        val page = open()
        page.applyPreset(OnlineTtsPreset.ELEVENLABS)
        page.saveAndWait()
        assertEquals(
            "No API key is entered.\n\n" +
                "The service did not return a list of models. Tap Model and type the model's id.\n\n" +
                """{"detail":"Not Found"}""",
            page.noListMessage(),
        )
        assertNull(OnlineTtsService.config(prefs))
    }

    @Test fun `the question whether to save unchecked says so too`() {
        modelsReply = noSuchList
        val page = open()
        page.typeModel("kokoro")
        page.saveAndWait()
        assertEquals(
            "No API key is entered.\n\n" +
                "The service did not return a list of models, so the URL and the API key could not be checked.\n\n" +
                """{"detail":"Not Found"}""",
            page.noListMessage(),
        )
        // The line refuses nothing: a server that needs no key is saved.
        page.pressAlertButton("Save anyway")
        assertTrue(page.isFinishing)
        assertEquals(OnlineTtsChoice("kokoro"), OnlineTtsService.config(prefs)!!.model)
    }

    @Test fun `a key that was entered, or a request that sends none, is not said to be missing`() {
        modelsReply = noSuchList
        val keyed = open()
        keyed.type(R.id.etVoiceServiceKey, "sk-SECRET")
        keyed.typeModel("kokoro")
        keyed.saveAndWait()
        assertTrue(keyed.noListMessage(), keyed.noListMessage().startsWith("The service did not return a list of models"))
        controller!!.pause().stop().destroy()

        val keyless = open()
        keyless.type(R.id.etVoiceServiceRequest, keylessRequest)
        keyless.typeModel("kokoro")
        keyless.saveAndWait()
        assertTrue(
            keyless.noListMessage(),
            keyless.noListMessage().startsWith("The service did not return a list of models"),
        )
    }

    // ── A key kept outside the header lines ──────────────────────────────

    @Test fun `a service keyed in its URL is checked with its key and saved`() {
        OnlineTtsService.client = OnlineTtsClient(
            recordingClient(seen) { request ->
                if (request.request.url.queryParameter("api_key") == "sk-SECRET") modelsReply
                else CannedReply(401, """{"error":"API key required"}""")
            },
        )
        val page = open()
        page.type(R.id.etVoiceServiceUrl, "https://tts.example.com/v1/audio/speech?api_key={key}")
        page.type(R.id.etVoiceServiceRequest, keylessRequest)
        page.type(R.id.etVoiceServiceKey, "sk-SECRET")
        page.saveAndWait()
        assertTrue(page.isFinishing)
        assertEquals("https://tts.example.com/v1/models?api_key=sk-SECRET", seen.single().request.url.toString())
        assertEquals(OnlineTtsChoice("gpt-4o-mini-tts"), OnlineTtsService.config(prefs)!!.model)
        assertEquals("sk-SECRET", prefs.onlineTtsApiKey)
    }

    @Test fun `a key kept in the request's body, which a list request cannot carry, is said to be unchecked and not refused`() {
        modelsReply = CannedReply(401, """{"error":"API key required"}""")
        val page = open()
        page.type(
            R.id.etVoiceServiceRequest,
            "Content-Type: application/json\n\n{\"api_key\":\"{key}\",\"model\":\"{model}\",\"input\":\"{text}\"}",
        )
        page.type(R.id.etVoiceServiceKey, "sk-SECRET")
        page.typeModel("m")
        page.saveAndWait()
        assertFalse(page.isFinishing)
        assertTrue("The key could not be checked" in page.texts())
        assertFalse(page.texts().any { it.contains("refused the API key") })
        assertEquals(
            "The service did not return a list of models, so the URL and the API key could not be checked.\n\n" +
                """{"error":"API key required"}""",
            page.noListMessage(),
        )
        page.pressAlertButton("Save anyway")
        assertTrue(page.isFinishing)
        assertEquals("sk-SECRET", prefs.onlineTtsApiKey)
    }

    private fun OnlineTtsServiceActivity.modelListReason(): String =
        findViewById<LinearLayout>(R.id.modelRows).getChildAt(0)
            .findViewById<TextView>(R.id.tvRowSubtitle).text.toString()

    @Test fun `the Model list says the same of a key that was not entered, and nothing of one that was`() {
        OnlineTtsService.client = OnlineTtsClient(recordingClient { noSuchList })
        val page = open()
        page.findViewById<View>(R.id.rowModel).performClick()
        pumpUntil("the failure") { page.modelTitles() == listOf("Couldn't load") }
        assertTrue(
            page.modelListReason(),
            page.modelListReason().startsWith("No API key is entered.\n\nThe service refused the request (HTTP 404)."),
        )
        controller!!.pause().stop().destroy()

        val keyed = open()
        keyed.type(R.id.etVoiceServiceKey, "sk-SECRET")
        keyed.findViewById<View>(R.id.rowModel).performClick()
        pumpUntil("the failure") { keyed.modelTitles() == listOf("Couldn't load") }
        assertTrue(keyed.modelListReason(), keyed.modelListReason().startsWith("The service refused the request (HTTP 404)."))
    }

    @Test fun `a service that lists no model shows an empty Model list, as before`() {
        OnlineTtsService.client = OnlineTtsClient(recordingClient { CannedReply(200, """{"data":[]}""") })
        val page = open()
        page.findViewById<View>(R.id.rowModel).performClick()
        pumpUntil("the empty list") { page.modelTitles() == listOf("No results") }
    }

    @Test fun `what is wrong on the page itself is said before the service is asked`() {
        val page = open()
        page.type(R.id.etVoiceServiceUrl, "http://tts.example.com/v1/audio/speech")
        page.pressSave()
        assertTrue("no check was started", page.saveEnabled())
        assertFalse(page.isFinishing)
        assertTrue(seen.isEmpty())
        assertNull(OnlineTtsService.config(prefs))

        // A fault the service's model list would not trip over: a request
        // that never sends the text. The service is still not asked.
        val other = open()
        other.type(R.id.etVoiceServiceRequest, "Authorization: Bearer {key}\n\n{\"model\":\"{model}\"}")
        other.pressSave()
        assertTrue(other.saveEnabled())
        repeat(10) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
        assertTrue("nothing was sent for a page that could not be saved", seen.isEmpty())
        assertFalse(other.isFinishing)
        assertNull(OnlineTtsService.config(prefs))
    }

    @Test fun `Save stores the page, a preset's own texts as absent, the key only in its encrypted slot`() {
        val page = open()
        page.type(R.id.etVoiceServiceKey, "  sk-SECRET ")
        page.type(R.id.etVoiceServiceInstructions, " Slowly. ")
        page.saveAndWait()

        assertTrue(page.isFinishing)
        val stored = OnlineTtsService.config(prefs)!!
        assertEquals(OnlineTtsPreset.OPENAI, stored.preset)
        assertNull(stored.url)
        assertNull(stored.request)
        assertTrue(stored.manualVoices.isEmpty())
        assertEquals(OnlineTtsChoice("gpt-4o-mini-tts"), stored.model)
        assertEquals("Slowly.", stored.instructions)
        assertEquals("sk-SECRET", prefs.onlineTtsApiKey)
        assertEquals("enc:online_tts_api_key|sk-SECRET", sp().getString("online_tts_api_key", null))
        assertFalse(prefs.onlineTtsConfigJson!!.contains("sk-SECRET"))
    }

    @Test fun `Save keeps an edited URL and request`() {
        val page = open()
        // A key typed while the request still sent one, left in its (now
        // hidden) field when the request stopped sending it.
        page.type(R.id.etVoiceServiceKey, "sk-LEFTOVER")
        page.type(R.id.etVoiceServiceUrl, "http://192.168.1.5:8880/v1/audio/speech")
        page.type(R.id.etVoiceServiceRequest, "{\"model\":\"{model}\",\"voice\":\"{voice}\",\"input\":\"{text}\"}")
        page.type(R.id.etVoiceServiceManualVoices, "jf_alpha\n\n zf_xiaobei ")
        modelsReply = CannedReply(200, """{"data":[{"id":"kokoro"}]}""")
        page.typeModel("kokoro")
        page.saveAndWait()

        val stored = OnlineTtsService.config(prefs)!!
        assertEquals("http://192.168.1.5:8880/v1/audio/speech", stored.url)
        assertEquals("{\"model\":\"{model}\",\"voice\":\"{voice}\",\"input\":\"{text}\"}", stored.request)
        assertEquals(listOf("jf_alpha", "zf_xiaobei"), stored.manualVoices)
        assertEquals("a request with no key token stores no key", "", prefs.onlineTtsApiKey)
        assertNull("and sends none to the model list either", seen.single().request.header("Authorization"))
    }

    @Test fun `Save puts the keyboard away, whether it then saves or stays`() {
        val page = open()
        val imm = page.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val key = page.findViewById<EditText>(R.id.etVoiceServiceKey)

        // A press that cannot save (the URL): the keyboard must not stand
        // over the alert.
        page.type(R.id.etVoiceServiceUrl, "not a url")
        key.requestFocus()
        imm.showSoftInput(key, 0)
        assertTrue(shadowOf(imm).isSoftInputVisible)
        page.pressSave()
        assertFalse(page.isFinishing)
        assertFalse(shadowOf(imm).isSoftInputVisible)

        // And a press that goes on to ask the service and save.
        page.type(R.id.etVoiceServiceUrl, OnlineTtsPreset.OPENAI.spec.url)
        imm.showSoftInput(key, 0)
        assertTrue(shadowOf(imm).isSoftInputVisible)
        page.pressSave()
        assertFalse(shadowOf(imm).isSoftInputVisible)
        pumpUntil("the save") { page.isFinishing }
    }

    @Test fun `Save that cannot store the key stores nothing and stays`() {
        codec.encryptWorks = false
        val page = open()
        page.type(R.id.etVoiceServiceKey, "sk-SECRET")
        page.saveAndWait()
        assertFalse(page.isFinishing)
        assertNull(OnlineTtsService.config(prefs))
    }

    // ── A voice the model refuses ────────────────────────────────────────

    private fun descendants(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is ViewGroup) for (i in 0 until root.childCount) yieldAll(descendants(root.getChildAt(i)))
    }

    /** Every text drawn in the page's window, an alert's included. */
    private fun OnlineTtsServiceActivity.texts(): List<String> =
        descendants(window.decorView).filterIsInstance<TextView>().map { it.text.toString() }.toList()

    /** Presses the one button of that label: an alert's, the page having
     *  none of the labels asked for here. */
    private fun OnlineTtsServiceActivity.pressAlertButton(label: String) {
        descendants(window.decorView).filterIsInstance<Button>().single { it.text.toString() == label }.performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private val voicesTitle = "Some voices do not work with this model"
    private val marin = OnlineTtsChoice("marin")
    private val cedar = OnlineTtsChoice("cedar")
    private val coral = OnlineTtsChoice("coral")

    /** OpenAI's newest speech model connected, with two languages speaking
     *  in voices only it takes and one in a voice every model takes. */
    private fun connectWithNewestModelVoices() {
        OnlineTtsService.save(prefs, OnlineTtsConfig(model = OnlineTtsChoice("gpt-4o-mini-tts")), "sk-SECRET")
        prefs.setVoiceChoice(SourceLangId.JA, VoiceChoice.Online(marin))
        prefs.setVoiceChoice(SourceLangId.KO, VoiceChoice.Online(cedar))
        prefs.setVoiceChoice(SourceLangId.ZH_HANT, VoiceChoice.Online(coral))
    }

    @Test fun `a model that refuses chosen voices is said before anything is stored, and Save anyway sends their languages to the device's voice`() {
        connectWithNewestModelVoices()
        val page = open()
        page.typeModel("tts-1")
        page.saveAndWait()

        assertFalse(page.isFinishing)
        assertEquals("nothing is stored yet", OnlineTtsChoice("gpt-4o-mini-tts"), OnlineTtsService.config(prefs)!!.model)
        assertEquals(VoiceChoice.Online(marin), prefs.voiceChoice(SourceLangId.JA))
        val texts = page.texts()
        assertTrue(texts.toString(), voicesTitle in texts)
        assertTrue(
            texts.toString(),
            "The model tts-1 cannot use the voices below. If you save, each of these languages goes back to " +
                "the device's voice.\n\nJapanese · marin\nKorean · cedar" in texts,
        )

        page.pressAlertButton("Save anyway")
        assertTrue(page.isFinishing)
        assertEquals(OnlineTtsChoice("tts-1"), OnlineTtsService.config(prefs)!!.model)
        assertEquals(VoiceChoice.Device(null), prefs.voiceChoice(SourceLangId.JA))
        assertEquals(VoiceChoice.Device(null), prefs.voiceChoice(SourceLangId.KO))
        assertEquals("a voice the model takes stays", VoiceChoice.Online(coral), prefs.voiceChoice(SourceLangId.ZH_HANT))
    }

    @Test fun `Cancel on that question stores nothing, and with the model put back Save asks nothing`() {
        connectWithNewestModelVoices()
        val page = open()
        page.typeModel("tts-1")
        page.saveAndWait()
        page.pressAlertButton("Cancel")
        assertFalse(page.isFinishing)
        assertFalse(voicesTitle in page.texts())
        assertEquals(OnlineTtsChoice("gpt-4o-mini-tts"), OnlineTtsService.config(prefs)!!.model)
        assertEquals(VoiceChoice.Online(marin), prefs.voiceChoice(SourceLangId.JA))

        page.typeModel("gpt-4o-mini-tts")
        page.type(R.id.etVoiceServiceInstructions, "Slowly.")
        page.saveAndWait()
        assertTrue(page.isFinishing)
        assertEquals("Slowly.", OnlineTtsService.config(prefs)!!.instructions)
        assertEquals(VoiceChoice.Online(marin), prefs.voiceChoice(SourceLangId.JA))
        assertEquals(VoiceChoice.Online(cedar), prefs.voiceChoice(SourceLangId.KO))
    }

    @Test fun `a model that takes every chosen voice is saved with no question`() {
        OnlineTtsService.save(prefs, OnlineTtsConfig(model = OnlineTtsChoice("gpt-4o-mini-tts")), "sk-SECRET")
        prefs.setVoiceChoice(SourceLangId.JA, VoiceChoice.Online(coral))
        val page = open()
        page.typeModel("tts-1")
        page.saveAndWait()
        assertTrue(page.isFinishing)
        assertEquals(OnlineTtsChoice("tts-1"), OnlineTtsService.config(prefs)!!.model)
        assertEquals(VoiceChoice.Online(coral), prefs.voiceChoice(SourceLangId.JA))
    }

    @Test fun `a service whose key could not be checked is asked about first, then the voices, and stored only after both`() {
        connectWithNewestModelVoices()
        modelsReply = CannedReply(404, """{"detail":"Not Found"}""")
        val page = open()
        page.typeModel("tts-1")
        page.saveAndWait()
        assertTrue("The key could not be checked" in page.texts())
        assertFalse(voicesTitle in page.texts())

        page.pressAlertButton("Save anyway")
        assertFalse("the second question", page.isFinishing)
        assertTrue(voicesTitle in page.texts())
        assertFalse("The key could not be checked" in page.texts())
        assertEquals(OnlineTtsChoice("gpt-4o-mini-tts"), OnlineTtsService.config(prefs)!!.model)
        assertEquals(VoiceChoice.Online(marin), prefs.voiceChoice(SourceLangId.JA))

        page.pressAlertButton("Save anyway")
        assertTrue(page.isFinishing)
        assertEquals(OnlineTtsChoice("tts-1"), OnlineTtsService.config(prefs)!!.model)
        assertEquals(VoiceChoice.Device(null), prefs.voiceChoice(SourceLangId.JA))
    }

    // ── Remove ───────────────────────────────────────────────────────────

    @Test fun `removing the service clears it, its key and every language's voice of it`() {
        OnlineTtsService.save(prefs, OnlineTtsConfig(model = OnlineTtsChoice("tts-1")), "sk-SECRET")
        prefs.setVoiceChoice(SourceLangId.JA, VoiceChoice.Online(OnlineTtsChoice("coral")))
        val page = open()
        page.removeService()
        assertTrue(page.isFinishing)
        assertNull(OnlineTtsService.config(prefs))
        assertEquals("", prefs.onlineTtsApiKey)
        assertEquals(VoiceChoice.Device(null), prefs.voiceChoice(SourceLangId.JA))
    }

    // ── The model list ───────────────────────────────────────────────────

    @Test fun `the model list is asked with the URL and key as typed, and a pick fills the row`() {
        val page = open()
        page.type(R.id.etVoiceServiceUrl, "http://192.168.1.5:8880/v1/audio/speech")
        page.type(R.id.etVoiceServiceKey, "sk-TYPED")
        page.findViewById<View>(R.id.rowModel).performClick()
        assertTrue(page.shown(R.id.modelPanel))
        assertFalse("the form and its Save are under the list", page.shown(R.id.voiceServiceSaveBar))
        pumpUntil("the model list") { page.modelTitles() == listOf("tts-1", "gpt-4o-mini-tts") }
        val sent = seen.single().request
        assertEquals("http://192.168.1.5:8880/v1/models", sent.url.toString())
        assertEquals("Bearer sk-TYPED", sent.header("Authorization"))
        assertNull("nothing is stored by listing", OnlineTtsService.config(prefs))

        page.findViewById<LinearLayout>(R.id.modelRows).getChildAt(0).performClick()
        assertFalse(page.shown(R.id.modelPanel))
        assertTrue(page.shown(R.id.voiceServiceSaveBar))
        assertEquals("tts-1", page.rowValue(R.id.rowModel))
    }

    @Test fun `a model list that fails says so, with the reason`() {
        OnlineTtsService.client = OnlineTtsClient(
            recordingClient { CannedReply(401, """{"error":{"message":"Incorrect API key provided"}}""") },
        )
        val page = open()
        page.findViewById<View>(R.id.rowModel).performClick()
        pumpUntil("the failure") { page.modelTitles() == listOf("Couldn't load") }
        val reason = page.findViewById<LinearLayout>(R.id.modelRows).getChildAt(0)
            .findViewById<TextView>(R.id.tvRowSubtitle).text.toString()
        assertTrue(reason, reason.startsWith("The service refused the API key."))
        assertTrue(reason, reason.contains("Incorrect API key provided"))
    }

    @Test fun `Back closes the model list before it closes the page`() {
        val page = open()
        page.findViewById<View>(R.id.rowModel).performClick()
        assertTrue(page.shown(R.id.modelPanel))
        page.onBackPressedDispatcher.onBackPressed()
        assertFalse(page.shown(R.id.modelPanel))
        assertFalse(page.isFinishing)
    }

    // ── A recreate ───────────────────────────────────────────────────────

    @Test fun `a recreated page keeps what was typed, the service kind and the model`() {
        val page = open()
        page.applyPreset(OnlineTtsPreset.ELEVENLABS)
        page.type(R.id.etVoiceServiceKey, "sk-SECRET")
        page.type(R.id.etVoiceServiceRequest, "xi-api-key: {key}\n\n{\"text\":\"{text}\"}")
        page.type(R.id.etVoiceServiceOtherModel, "eleven_v3")
        // The model row is hidden by the edited request; a typed model
        // still belongs to the page.
        page.findViewById<View>(R.id.btnVoiceServiceUseModel).performClick()

        controller!!.recreate()
        val again = controller!!.get()
        assertEquals("sk-SECRET", again.text(R.id.etVoiceServiceKey))
        assertEquals("xi-api-key: {key}\n\n{\"text\":\"{text}\"}", again.text(R.id.etVoiceServiceRequest))
        assertEquals("ElevenLabs-compatible", again.rowValue(R.id.rowService))
        assertEquals("eleven_v3", again.rowValue(R.id.rowModel))
        assertFalse("the rows follow the restored text", again.shown(R.id.sectionVoiceServiceModel))
        assertTrue(again.shown(R.id.sectionVoiceServiceKey))
    }
}
