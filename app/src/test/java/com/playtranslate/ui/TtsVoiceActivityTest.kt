package com.playtranslate.ui

import android.content.Context
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.materialswitch.MaterialSwitch
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.audio.RecordingPlayer
import com.playtranslate.audio.localaudio.LocalAudioDb
import com.playtranslate.audio.localaudio.LocalAudioFixture
import com.playtranslate.audio.online.OnlineTtsChoice
import com.playtranslate.audio.online.OnlineTtsClient
import com.playtranslate.audio.online.OnlineTtsConfig
import com.playtranslate.audio.online.OnlineTtsException
import com.playtranslate.audio.online.OnlineTtsPreset
import com.playtranslate.audio.online.OnlineTtsService
import com.playtranslate.language.SourceLangId
import com.playtranslate.security.SecretCodec
import com.playtranslate.translation.BackendFailure
import com.playtranslate.translation.BackendFailureKind
import com.playtranslate.translation.CannedReply
import com.playtranslate.translation.SeenRequest
import com.playtranslate.translation.recordingClient
import com.playtranslate.tts.VoiceChoice
import com.playtranslate.tts.voiceSampleText
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
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
import org.robolectric.shadows.ShadowToast
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * [TtsVoiceActivity]'s voice service group and the one tick across both
 * groups: the row that connects a service, the service's voices by name, the
 * stored service voice drawn whatever the list does, a tap asking the
 * service for a sample, Save writing the page's own choice, and the tick
 * after the service's settings page removed the service. And the page's
 * title, and where the Imported recordings group stands on it; what the
 * group does is ImportedRecordingsSectionTest's.
 */
@RunWith(RobolectricTestRunner::class)
class TtsVoiceActivityTest {

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
    private val seen: MutableList<SeenRequest> = Collections.synchronizedList(mutableListOf())
    private var controller: ActivityController<TtsVoiceActivity>? = null

    private val mp3 = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 4, 0, 0, 0, 0, 0, 0)
    private val alpha = OnlineTtsChoice("jf_alpha", "Alpha")
    private val beta = OnlineTtsChoice("jf_beta", "Beta")

    /** What the fake service answers its voice list with; a status that is
     *  not 200 is a failure. Speech requests always get audio. */
    @Volatile private var voicesStatus = 200

    /** When set, what the voice list call is answered with, as a success:
     *  a reply that is not the list the app asked for. */
    @Volatile private var voicesBody: String? = null

    /** When set, the voice list is not answered until it is counted down:
     *  the page's state before any answer, held still. */
    @Volatile private var voicesGate: CountDownLatch? = null

    /** When set, a speech request is not answered until it is counted
     *  down: a sample being fetched, held still. */
    @Volatile private var speechGate: CountDownLatch? = null

    /** When set, what a speech request is answered with in place of
     *  audio: a service that will not speak. */
    @Volatile private var speechReply: CannedReply? = null

    @Before fun setUp() {
        sp().edit().clear().commit()
        File(ctx.cacheDir, "audioCache").deleteRecursively()
        LocalAudioFixture.clear(ctx)
        prefs = Prefs(ctx, FakeCodec())
        OnlineTtsService.prefsFor = { prefs }
        OnlineTtsService.client = OnlineTtsClient(
            recordingClient(seen) { request ->
                if (request.request.method == "GET") {
                    voicesGate?.await(5, TimeUnit.SECONDS)
                    val body = voicesBody
                    if (body != null) {
                        CannedReply(200, body)
                    } else if (voicesStatus == 200) {
                        // Each voice under both presets' id fields, so one
                        // fake serves a service of either kind.
                        CannedReply(
                            200,
                            """{"voices":[{"id":"jf_alpha","voice_id":"jf_alpha","name":"Alpha"},""" +
                                """{"id":"jf_beta","voice_id":"jf_beta","name":"Beta"}]}""",
                        )
                    } else {
                        CannedReply(voicesStatus, """{"error":"no"}""")
                    }
                } else {
                    speechGate?.await(5, TimeUnit.SECONDS)
                    speechReply ?: CannedReply(200, "", bytes = mp3)
                }
            },
        )
    }

    @After fun tearDown() {
        controller?.let { runCatching { it.pause().stop().destroy() } }
        RecordingPlayer.decoder = savedDecoder
        OnlineTtsService.client = savedClient
        OnlineTtsService.prefsFor = { Prefs(it) }
        sp().edit().clear().commit()
        File(ctx.cacheDir, "audioCache").deleteRecursively()
        LocalAudioFixture.clear(ctx)
    }

    private fun connect(config: OnlineTtsConfig = OnlineTtsConfig(model = OnlineTtsChoice("gpt-4o-mini-tts"))) {
        assertTrue(OnlineTtsService.save(prefs, config, "sk-SECRET"))
    }

    /** A service with no hand-listed voices: its list call is the only
     *  source of its voices, so the call's failure is what the page shows. */
    private val listOnly = OnlineTtsConfig(OnlineTtsPreset.ELEVENLABS, model = OnlineTtsChoice("m"))

    private fun open(lang: SourceLangId = SourceLangId.JA): TtsVoiceActivity =
        Robolectric.buildActivity(TtsVoiceActivity::class.java, TtsVoiceActivity.intent(ctx, lang))
            .also { controller = it }.setup().get()

    private fun pumpUntil(what: String, done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!done()) {
            assertTrue("timed out waiting for $what", System.currentTimeMillis() < deadline)
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    /** One drawn row: its title, its subtitle when shown, whether ticked. */
    private data class Row(val view: View, val title: String, val subtitle: String?, val ticked: Boolean)

    private fun rows(page: TtsVoiceActivity, groupId: Int): List<Row> {
        val group = page.findViewById<LinearLayout>(groupId)
        return (0 until group.childCount).map { group.getChildAt(it) }
            .filter { it.findViewById<TextView>(R.id.tvRowTitle) != null }
            .map { view ->
                val subtitle = view.findViewById<TextView>(R.id.tvRowSubtitle)
                Row(
                    view,
                    view.findViewById<TextView>(R.id.tvRowTitle).text.toString(),
                    subtitle.text.toString().takeIf { subtitle.visibility == View.VISIBLE },
                    view.findViewById<View>(R.id.ivCheck)?.visibility == View.VISIBLE,
                )
            }
    }

    private fun recordings(page: TtsVoiceActivity) = rows(page, R.id.recordingRows)
    private fun service(page: TtsVoiceActivity) = rows(page, R.id.serviceRows)
    private fun device(page: TtsVoiceActivity) = rows(page, R.id.voiceRows)
    private fun TtsVoiceActivity.pressSave() = findViewById<View>(R.id.btnSave).performClick()

    private fun openWithVoices(): TtsVoiceActivity {
        val page = open()
        pumpUntil("the service's voices") { service(page).any { it.title == "Beta" } }
        return page
    }

    // ── The service group ────────────────────────────────────────────────

    @Test fun `with no service the group is the row that connects one, and nothing is asked`() {
        val page = open()
        assertEquals(listOf("Connect a voice service"), service(page).map { it.title })
        assertEquals("Use an online AI voice with your own API key", service(page).single().subtitle)
        assertTrue(seen.isEmpty())
    }

    @Test fun `a connected service lists its voices by name, then its settings row`() {
        connect()
        val page = openWithVoices()
        assertEquals(listOf("Alpha", "Beta", "Service settings"), service(page).map { it.title })
        assertEquals(
            "where the requests go and the model that speaks",
            "api.openai.com · gpt-4o-mini-tts",
            service(page).last().subtitle,
        )
        assertEquals("https://api.openai.com/v1/audio/voices", seen.single().request.url.toString())
        assertFalse(service(page).any { it.ticked })
    }

    @Test fun `the language's stored service voice opens ticked`() {
        connect()
        prefs.setVoiceChoice(SourceLangId.JA, VoiceChoice.Online(beta))
        val page = openWithVoices()
        assertEquals(listOf("Beta"), service(page).filter { it.ticked }.map { it.title })
        assertFalse("one tick across both groups", device(page).any { it.ticked })
    }

    @Test fun `another language's service voice is not this page's`() {
        connect()
        prefs.setVoiceChoice(SourceLangId.ZH, VoiceChoice.Online(beta))
        val page = openWithVoices()
        assertFalse(service(page).any { it.ticked })
    }

    @Test fun `while the list loads and when it fails, the stored voice is still drawn and ticked`() {
        connect(listOnly)
        prefs.setVoiceChoice(SourceLangId.JA, VoiceChoice.Online(beta))
        voicesStatus = 401
        val gate = CountDownLatch(1).also { voicesGate = it }
        val page = open()
        assertEquals("before any answer", listOf("Beta", "Loading…", "Service settings"), service(page).map { it.title })
        assertTrue(service(page).first().ticked)
        gate.countDown()
        pumpUntil("the failure") { service(page).any { it.title == "Couldn't load" } }
        assertEquals(listOf("Beta", "Couldn't load", "Service settings"), service(page).map { it.title })
        assertTrue(service(page).first().ticked)
        assertEquals(
            "the reason, then the service's own words",
            "The service refused the API key.\n\n{\"error\":\"no\"}",
            service(page)[1].subtitle,
        )
    }

    @Test fun `a list call answered with something that is no list says so, with what was answered`() {
        connect(listOnly)
        voicesBody = """{"object":"list","data":[]}"""
        val page = open()
        pumpUntil("the failure") { service(page).any { it.title == "Couldn't load" } }
        assertEquals(
            "The service's answer was not a list the app can read.\n\n{\"object\":\"list\",\"data\":[]}",
            service(page).single { it.title == "Couldn't load" }.subtitle,
        )
    }

    @Test fun `a service with built-in voices lists them whatever it answers the list call`() {
        // The OpenAI-compatible preset on a service that has no call that
        // lists voices and answers the request with a success that is no
        // voice list.
        connect()
        voicesBody = """{"object":"list","data":[],"has_more":false}"""
        val page = open()
        pumpUntil("the built-in voices") { service(page).any { it.title == "coral" } }
        assertEquals(
            OnlineTtsPreset.OPENAI.spec.builtInVoices + "Service settings",
            service(page).map { it.title },
        )
        assertFalse(service(page).any { it.title == "Couldn't load" })

        service(page).single { it.title == "coral" }.view.performClick()
        pumpUntil("the sample request") { seen.any { it.request.method == "POST" } }
        assertEquals("coral", seen.single { it.request.method == "POST" }.json["voice"]!!.jsonPrimitive.content)
    }

    @Test fun `a model that takes fewer built-in voices is listed only those`() {
        // What an older OpenAI speech model refuses with an HTTP 400 is not
        // offered for it.
        connect(OnlineTtsConfig(model = OnlineTtsChoice("tts-1")))
        voicesStatus = 405
        val page = open()
        pumpUntil("the built-in voices") { service(page).any { it.title == "shimmer" } }
        val listed = service(page).map { it.title }
        assertEquals(
            listOf("alloy", "ash", "coral", "echo", "fable", "onyx", "nova", "sage", "shimmer", "Service settings"),
            listed,
        )
        assertFalse(listed.contains("marin"))
    }

    @Test fun `a failed list is asked for again by tapping it`() {
        connect(listOnly)
        voicesStatus = 500
        val page = open()
        pumpUntil("the failure") { service(page).any { it.title == "Couldn't load" } }
        voicesStatus = 200
        service(page).single { it.title == "Couldn't load" }.view.performClick()
        pumpUntil("the list") { service(page).any { it.title == "Alpha" } }
        assertEquals(listOf("Alpha", "Beta", "Service settings"), service(page).map { it.title })
    }

    @Test fun `a stored voice the list no longer holds is drawn first, ticked`() {
        connect()
        prefs.setVoiceChoice(SourceLangId.JA, VoiceChoice.Online(OnlineTtsChoice("gone", "Gone")))
        val page = openWithVoices()
        assertEquals(listOf("Gone", "Alpha", "Beta", "Service settings"), service(page).map { it.title })
        assertTrue(service(page).first().ticked)
    }

    // ── Choosing, hearing, saving ────────────────────────────────────────

    @Test fun `tapping a service voice ticks it alone and asks the service for the sample in it`() {
        connect()
        val page = openWithVoices()
        service(page).single { it.title == "Alpha" }.view.performClick()
        assertEquals(listOf("Alpha"), service(page).filter { it.ticked }.map { it.title })
        assertFalse(device(page).any { it.ticked })
        pumpUntil("the sample request") { seen.any { it.request.method == "POST" } }
        val sample = seen.single { it.request.method == "POST" }
        assertEquals("jf_alpha", sample.json["voice"]!!.jsonPrimitive.content)
        assertEquals(
            "the language's sample sentence, as written",
            voiceSampleText(SourceLangId.JA),
            sample.json["input"]!!.jsonPrimitive.content,
        )
        assertEquals("nothing is stored by a tap", VoiceChoice.Device(null), prefs.voiceChoice(SourceLangId.JA))
    }

    private fun sampleClips(): List<File> =
        File(ctx.cacheDir, "audioCache/online_tts").listFiles().orEmpty().filter { it.isFile }

    /**
     * Taps the voice "Alpha" twice, with [decoded] as what its sample
     * decodes to, and returns how many times the service was asked to
     * speak. Each tap is waited out: to the end of its decode, and to the
     * cache holding the sample or not, as [clipKept] says it will.
     */
    private fun speechRequestsForTwoTaps(decoded: RecordingPlayer.Decoded, clipKept: Boolean): Int {
        val decodes = AtomicInteger()
        RecordingPlayer.decoder = { _, _ -> decoded.also { decodes.incrementAndGet() } }
        connect()
        val page = openWithVoices()
        for (tap in 1..2) {
            service(page).single { it.title == "Alpha" }.view.performClick()
            pumpUntil("tap $tap's sample decoded, and then ${if (clipKept) "kept" else "dropped"}") {
                decodes.get() == tap && sampleClips().isNotEmpty() == clipKept
            }
        }
        return seen.count { it.request.method == "POST" }
    }

    /** Taps the voice "Alpha" with [decoded] as what its sample decodes
     *  to, waits out the decode, and gives what the page then says. */
    private fun toastAfterASample(decoded: RecordingPlayer.Decoded): String? {
        ShadowToast.reset()
        val decodes = AtomicInteger()
        RecordingPlayer.decoder = { _, _ -> decoded.also { decodes.incrementAndGet() } }
        connect()
        val page = openWithVoices()
        service(page).single { it.title == "Alpha" }.view.performClick()
        pumpUntil("the sample's decode") { decodes.get() == 1 }
        // The play ends just after its decode; a toast, if any, follows it
        // on the main thread.
        val until = System.currentTimeMillis() + 400
        while (System.currentTimeMillis() < until && ShadowToast.getLatestToast() == null) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        return ShadowToast.getTextOfLatestToast()
    }

    @Test fun `a sample the service sent that would not play says so`() {
        assertEquals("Couldn't play this audio", toastAfterASample(RecordingPlayer.Decoded.Unreadable))
    }

    @Test fun `a sample that plays says nothing`() {
        val second = ByteArray(2 * 16_000)
        assertNull(toastAfterASample(RecordingPlayer.Decoded.Audio(second, sampleRate = 16_000, channelCount = 1)))
    }

    @Test fun `a sample still being fetched when the page is left is cut, with nothing cached, played or said`() {
        ShadowToast.reset()
        val decodes = AtomicInteger()
        RecordingPlayer.decoder = { _, _ -> RecordingPlayer.Decoded.Unreadable.also { decodes.incrementAndGet() } }
        connect()
        val page = openWithVoices()
        val answer = CountDownLatch(1).also { speechGate = it }
        service(page).single { it.title == "Alpha" }.view.performClick()
        pumpUntil("the sample request") { seen.any { it.request.method == "POST" } }

        // The user goes to another page (the service's settings, say), and
        // the service answers only then.
        controller!!.pause()
        answer.countDown()
        // Time for the answer to be cached, played and found unplayable,
        // were the sample still alive.
        val until = System.currentTimeMillis() + 600
        while (System.currentTimeMillis() < until) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertEquals("nothing was played", 0, decodes.get())
        assertTrue("nothing was cached", sampleClips().isEmpty())
        assertNull("nothing was said over the other page", ShadowToast.getLatestToast())

        // Back on the page, a tap samples as before.
        controller!!.resume()
        service(page).single { it.title == "Alpha" }.view.performClick()
        pumpUntil("the sample after coming back") { decodes.get() == 1 }
    }

    // ── A sample the service refuses ─────────────────────────────────────

    private fun descendants(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is android.view.ViewGroup) for (i in 0 until root.childCount) yieldAll(descendants(root.getChildAt(i)))
    }

    /** Every text on the page's window, an alert's among them. */
    private fun TtsVoiceActivity.texts(): List<String> =
        descendants(window.decorView).filterIsInstance<TextView>().map { it.text.toString() }.toList()

    /** Taps the voice "Alpha" on a service that answers its sample with
     *  [reply], and gives the message of the alert that follows. */
    private fun alertAfterASample(reply: CannedReply): String {
        speechReply = reply
        // A model whose name is not its id, as a service's list gives one.
        connect(OnlineTtsConfig(model = OnlineTtsChoice("gpt-4o-mini-tts", "GPT-4o mini TTS")))
        val page = openWithVoices()
        service(page).single { it.title == "Alpha" }.view.performClick()
        pumpUntil("the alert") { "The voice service did not answer" in page.texts() }
        return page.texts().single { it.startsWith("The service ") }
    }

    @Test fun `a sample the service refuses says which voice and which model the request named`() {
        assertEquals(
            "The service refused the request (HTTP 400).\n\n" +
                "The request named the voice Alpha and the model GPT-4o mini TTS. If the service does not have " +
                "one of them, choose another voice here, or another model in Service settings.\n\n" +
                """{"error":{"message":"Bad Request"}}""",
            alertAfterASample(CannedReply(400, """{"error":{"message":"Bad Request"}}""")),
        )
    }

    @Test fun `a failure that is no refusal of the request says nothing of the voice or the model`() {
        assertEquals(
            "The service refused the API key.\n\n" + """{"error":{"message":"Incorrect API key provided"}}""",
            alertAfterASample(CannedReply(401, """{"error":{"message":"Incorrect API key provided"}}""")),
        )
    }

    @Test fun `what a refusal says it asked for is the names, and only of a request that was made and names a model`() {
        val refused = OnlineTtsException(BackendFailure(BackendFailureKind.REJECTED, 404), "no such thing")
        // By the names the pages show, not the ids the request sends.
        val named = voiceServiceFailureMessage(ctx, refused, asked = SpeechAsked("Rachel", "Eleven Flash v2.5"))
        assertTrue(named, named.contains("the voice Rachel and the model Eleven Flash v2.5."))
        // A request that names no model: the line would have half to say.
        assertEquals(
            "The service refused the request (HTTP 404).\n\nno such thing",
            voiceServiceFailureMessage(ctx, refused, asked = SpeechAsked("Rachel", null)),
        )
        // No request was made at all: nothing was asked.
        val unsent = OnlineTtsException(BackendFailure(BackendFailureKind.REJECTED))
        assertEquals(
            "The request could not be made. Check the service's URL.",
            voiceServiceFailureMessage(ctx, unsent, asked = SpeechAsked("Rachel", "Eleven Flash v2.5")),
        )
        // A list's failure asked for no voice.
        assertEquals(
            "The service refused the request (HTTP 404).\n\nno such thing",
            voiceServiceFailureMessage(ctx, refused, ofList = true),
        )
    }

    @Test fun `a sample whose clip cannot be kept says it could not play, and the page lives`() {
        ShadowToast.reset()
        connect()
        val page = openWithVoices()
        // No room for the clip: where the service's clips are kept is not
        // a folder, so the write of it fails.
        File(ctx.cacheDir, "audioCache").mkdirs()
        File(ctx.cacheDir, "audioCache/online_tts").writeText("in the way")

        service(page).single { it.title == "Alpha" }.view.performClick()
        pumpUntil("what the page says") { ShadowToast.getLatestToast() != null }
        assertEquals("Couldn't play this audio", ShadowToast.getTextOfLatestToast())
        assertFalse(page.isFinishing)
        assertEquals(listOf("Alpha"), service(page).filter { it.ticked }.map { it.title })
    }

    @Test fun `a sample the player cannot be set up for says it could not play`() {
        // A rate no audio track takes.
        val odd = RecordingPlayer.Decoded.Audio(ByteArray(2_000), sampleRate = 1_000, channelCount = 1)
        assertEquals("Couldn't play this audio", toastAfterASample(odd))
    }

    @Test fun `a sample that turns out to be no audio is asked of the service again at the next tap`() {
        assertEquals(2, speechRequestsForTwoTaps(RecordingPlayer.Decoded.Unreadable, clipKept = false))
    }

    @Test fun `a sample the player only declines is not asked for again`() {
        assertEquals(1, speechRequestsForTwoTaps(RecordingPlayer.Decoded.Declined, clipKept = true))
    }

    @Test fun `Save stores the ticked service voice for this language, with no device voice under it`() {
        connect()
        prefs.setVoiceChoice(SourceLangId.JA, VoiceChoice.Device("ja-jp-x-jab-local"))
        val page = openWithVoices()
        service(page).single { it.title == "Beta" }.view.performClick()
        page.pressSave()
        assertTrue(page.isFinishing)
        assertEquals(VoiceChoice.Online(beta), prefs.voiceChoice(SourceLangId.JA))
        assertFalse(sp().contains("tts_voice_ja"))
        assertEquals(VoiceChoice.Device(null), prefs.voiceChoice(SourceLangId.ZH))
    }

    @Test fun `leaving without Save stores nothing`() {
        connect()
        val page = openWithVoices()
        service(page).single { it.title == "Beta" }.view.performClick()
        page.onBackPressedDispatcher.onBackPressed()
        assertEquals(VoiceChoice.Device(null), prefs.voiceChoice(SourceLangId.JA))
    }

    @Test fun `ticking the device's default and saving ends the service voice`() {
        connect()
        prefs.setVoiceChoice(SourceLangId.JA, VoiceChoice.Online(beta))
        val page = openWithVoices()
        // A service voice is ticked, so the device group has no tick; its
        // Default row is drawn once the page has redrawn for a tap.
        service(page).single { it.title == "Alpha" }.view.performClick()
        device(page).single { it.title == "Default" }.view.performClick()
        assertFalse(service(page).any { it.ticked })
        assertEquals(listOf("Default"), device(page).filter { it.ticked }.map { it.title })
        page.pressSave()
        assertEquals(VoiceChoice.Device(null), prefs.voiceChoice(SourceLangId.JA))
        assertFalse(sp().contains("online_voice_ja"))
    }

    @Test fun `a recreated page keeps a tick that was not saved`() {
        connect()
        val page = openWithVoices()
        service(page).single { it.title == "Beta" }.view.performClick()
        controller!!.recreate()
        val again = controller!!.get()
        pumpUntil("the service's voices") { service(again).any { it.title == "Alpha" } }
        assertEquals(listOf("Beta"), service(again).filter { it.ticked }.map { it.title })
        assertEquals(VoiceChoice.Device(null), prefs.voiceChoice(SourceLangId.JA))
    }

    // ── Back from the service's settings ─────────────────────────────────

    @Test fun `after the service was removed the tick is gone and Save cannot write its voice back`() {
        connect()
        prefs.setVoiceChoice(SourceLangId.JA, VoiceChoice.Online(beta))
        val page = openWithVoices()
        service(page).single { it.title == "Service settings" }.view.performClick()
        assertEquals(
            OnlineTtsServiceActivity::class.java.name,
            shadowOf(page).nextStartedActivity.component!!.className,
        )
        // What the service page's Remove does, while this page waits under it.
        controller!!.pause()
        OnlineTtsService.remove(ctx)
        controller!!.resume()

        assertEquals(listOf("Connect a voice service"), service(page).map { it.title })
        page.pressSave()
        assertEquals(VoiceChoice.Device(null), prefs.voiceChoice(SourceLangId.JA))
        assertFalse(sp().contains("online_voice_ja"))
    }

    @Test fun `after the same service was saved again an unsaved tick stays`() {
        connect()
        val page = openWithVoices()
        service(page).single { it.title == "Beta" }.view.performClick()
        service(page).single { it.title == "Service settings" }.view.performClick()
        controller!!.pause()
        OnlineTtsService.save(prefs, OnlineTtsService.config(prefs)!!.copy(instructions = "Slowly."), "sk-ANOTHER")
        controller!!.resume()
        pumpUntil("the reloaded list") { service(page).any { it.title == "Alpha" } }
        assertEquals(listOf("Beta"), service(page).filter { it.ticked }.map { it.title })
    }

    /** The service's settings opened from [page] and saved there on
     *  [model], while the page waits under it; then the page's list as
     *  reloaded on the way back. */
    private fun saveModelOnServicePage(page: TtsVoiceActivity, model: String) {
        service(page).single { it.title == "Service settings" }.view.performClick()
        controller!!.pause()
        assertTrue(
            OnlineTtsService.save(prefs, OnlineTtsService.config(prefs)!!.copy(model = OnlineTtsChoice(model)), "sk-SECRET"),
        )
        controller!!.resume()
        pumpUntil("the reloaded list") { service(page).any { it.title == "shimmer" } }
    }

    @Test fun `after the model became one that refuses the stored voice the tick is the device's, and Save cannot write the voice back`() {
        connect()
        // OpenAI's kind of service: no call that lists voices, so the
        // built-in ones its model takes are what is listed.
        voicesStatus = 405
        prefs.setVoiceChoice(SourceLangId.JA, VoiceChoice.Online(OnlineTtsChoice("marin")))
        val page = open()
        pumpUntil("the built-in voices") { service(page).any { it.title == "shimmer" } }
        assertEquals(listOf("marin"), service(page).filter { it.ticked }.map { it.title })

        // The service page's Save, agreed to there, has already sent the
        // stored voice back to the device's.
        saveModelOnServicePage(page, "tts-1")
        assertFalse("a voice the model refuses is not drawn", service(page).any { it.title == "marin" })
        assertFalse(service(page).any { it.ticked })
        assertEquals(listOf("Default"), device(page).filter { it.ticked }.map { it.title })
        page.pressSave()
        assertEquals(VoiceChoice.Device(null), prefs.voiceChoice(SourceLangId.JA))
        assertFalse(sp().contains("online_voice_ja"))
    }

    @Test fun `an unsaved tick the saved model refuses goes back to the stored voice, and one it takes stays`() {
        connect()
        voicesStatus = 405
        prefs.setVoiceChoice(SourceLangId.JA, VoiceChoice.Online(OnlineTtsChoice("coral")))
        val page = open()
        pumpUntil("the built-in voices") { service(page).any { it.title == "shimmer" } }
        service(page).single { it.title == "cedar" }.view.performClick()
        saveModelOnServicePage(page, "tts-1-hd")
        assertEquals(listOf("coral"), service(page).filter { it.ticked }.map { it.title })
        assertFalse(service(page).any { it.title == "cedar" })

        service(page).single { it.title == "sage" }.view.performClick()
        saveModelOnServicePage(page, "tts-1")
        assertEquals("a tick the model takes is the user's", listOf("sage"), service(page).filter { it.ticked }.map { it.title })
        assertEquals("and still unsaved", VoiceChoice.Online(OnlineTtsChoice("coral")), prefs.voiceChoice(SourceLangId.JA))
    }

    @Test fun `a service connected from here shows its voices on the way back`() {
        val page = open()
        service(page).single().view.performClick()
        controller!!.pause()
        connect()
        controller!!.resume()
        pumpUntil("the service's voices") { service(page).any { it.title == "Beta" } }
        assertEquals(listOf("Alpha", "Beta", "Service settings"), service(page).map { it.title })
    }

    // ── The page's title, and the Imported recordings group ──────────────

    @Test fun `the page is titled as the settings row that opens it`() {
        val page = open()
        assertEquals("Text-to-speech", page.findViewById<MaterialToolbar>(R.id.toolbar).title.toString())
    }

    @Test fun `the Imported recordings group is the last on the page, under the device's voices`() {
        val page = open()
        val header = page.findViewById<View>(R.id.headerRecordings)
        val card = page.findViewById<View>(R.id.cardRecordings)
        assertEquals("Imported recordings", header.findViewById<TextView>(R.id.tvGroupTitle).text.toString())
        assertEquals(View.VISIBLE, header.visibility)
        assertEquals(View.VISIBLE, card.visibility)
        val column = card.parent as LinearLayout
        assertEquals("nothing is under it", column.childCount - 1, column.indexOfChild(card))
        assertEquals("its header is right above it", column.childCount - 2, column.indexOfChild(header))
        val voicesCard = page.findViewById<View>(R.id.voiceRows).parent as View
        assertTrue("under the device's voices", column.indexOfChild(voicesCard) < column.indexOfChild(header))
        assertEquals(listOf("Load android.db file"), recordings(page).map { it.title })
        assertFalse("it is no voice and takes no tick", recordings(page).single().ticked)
    }

    @Test fun `the group is on the page of every language, with or without a file`() {
        val chinese = open(SourceLangId.ZH)
        assertEquals(View.VISIBLE, chinese.findViewById<View>(R.id.cardRecordings).visibility)
        assertEquals(listOf("Load android.db file"), recordings(chinese).map { it.title })
        controller!!.pause().stop().destroy()

        LocalAudioFixture.install(ctx)
        val english = open(SourceLangId.EN)
        assertEquals(View.VISIBLE, english.findViewById<View>(R.id.cardRecordings).visibility)
        assertEquals(listOf("android.db file"), recordings(english).map { it.title })
    }

    // ── Play automatically ───────────────────────────────────────────────

    private fun TtsVoiceActivity.autoPlayRow(id: Int): View = findViewById(id)

    private fun TtsVoiceActivity.autoPlayOn(id: Int): Boolean =
        autoPlayRow(id).findViewById<MaterialSwitch>(R.id.switchRowToggle).isChecked

    @Test fun `the two Play automatically switches are off until flipped, and each is saved by its own tap with no Save`() {
        val page = open()
        assertEquals(
            ctx.getString(R.string.tts_auto_play_words_title),
            page.autoPlayRow(R.id.rowAutoPlayWords).findViewById<TextView>(R.id.tvRowTitle).text.toString(),
        )
        assertEquals(
            ctx.getString(R.string.tts_auto_play_original_title),
            page.autoPlayRow(R.id.rowAutoPlayOriginal).findViewById<TextView>(R.id.tvRowTitle).text.toString(),
        )
        assertFalse("words: off on a new install", page.autoPlayOn(R.id.rowAutoPlayWords))
        assertFalse("original text: off on a new install", page.autoPlayOn(R.id.rowAutoPlayOriginal))
        assertFalse(prefs.autoPlayWords)
        assertFalse(prefs.autoPlayOriginal)

        page.autoPlayRow(R.id.rowAutoPlayWords).performClick()
        assertTrue("saved by the tap, not by Save", prefs.autoPlayWords)
        assertFalse("each row is its own setting", prefs.autoPlayOriginal)

        page.autoPlayRow(R.id.rowAutoPlayOriginal).performClick()
        assertTrue(prefs.autoPlayOriginal)
        assertTrue(prefs.autoPlayWords)

        // Left without Save, the page shows what was saved when it is opened again.
        page.finish()
        val again = open()
        assertTrue(again.autoPlayOn(R.id.rowAutoPlayWords))
        assertTrue(again.autoPlayOn(R.id.rowAutoPlayOriginal))

        again.autoPlayRow(R.id.rowAutoPlayWords).performClick()
        assertFalse("off is saved the same way", prefs.autoPlayWords)
        assertTrue(prefs.autoPlayOriginal)
    }
}
