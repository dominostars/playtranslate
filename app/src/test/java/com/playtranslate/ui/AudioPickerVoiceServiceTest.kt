package com.playtranslate.ui

import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.audio.AudioCache
import com.playtranslate.audio.AudioSelection
import com.playtranslate.audio.online.OnlineTtsChoice
import com.playtranslate.audio.online.OnlineTtsClient
import com.playtranslate.audio.online.OnlineTtsConfig
import com.playtranslate.audio.online.OnlineTtsService
import com.playtranslate.language.SourceLangId
import com.playtranslate.security.SecretCodec
import com.playtranslate.translation.CannedReply
import com.playtranslate.translation.SeenRequest
import com.playtranslate.translation.recordingClient
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

/**
 * The Anki audio picker with a voice service: no section until a service is
 * connected, then a section of the service's voices above the device's; a
 * tapped voice auditioned through the service and returned as the pick with
 * its name; and a failed audition said, not silent.
 */
@RunWith(RobolectricTestRunner::class)
class AudioPickerVoiceServiceTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private fun sp() = ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE)

    private class FakeCodec : SecretCodec {
        override fun encrypt(context: String, plaintext: String): String? = "enc:$context|$plaintext"
        override fun decrypt(context: String, stored: String): String? =
            stored.removePrefix("enc:$context|").takeIf { stored.startsWith("enc:$context|") }
    }

    private lateinit var prefs: Prefs
    private val savedClient = OnlineTtsService.client
    private val seen: MutableList<SeenRequest> = Collections.synchronizedList(mutableListOf())
    private var controller: ActivityController<AudioSourcePickerActivity>? = null
    private val mp3 = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 4, 0, 0, 0, 0, 0, 0)

    @Volatile private var speechStatus = 200

    @Before fun setUp() {
        sp().edit().clear().commit()
        File(ctx.cacheDir, "audioCache").deleteRecursively()
        prefs = Prefs(ctx, FakeCodec())
        OnlineTtsService.prefsFor = { prefs }
        OnlineTtsService.client = OnlineTtsClient(
            recordingClient(seen) { request ->
                when {
                    request.request.method == "GET" ->
                        CannedReply(200, """{"voices":[{"id":"jf_alpha","name":"Alpha"},{"id":"jf_beta","name":"Beta"}]}""")
                    speechStatus == 200 -> CannedReply(200, "", bytes = mp3)
                    else -> CannedReply(speechStatus, """{"error":"no"}""")
                }
            },
        )
        // The word has no Commons recording, remembered: the Commons section
        // asks the network for nothing.
        AudioCache(ctx).markNegative("wikimedia_commons", SourceLangId.JA, "行く")
    }

    @After fun tearDown() {
        controller?.let { runCatching { it.pause().stop().destroy() } }
        OnlineTtsService.client = savedClient
        OnlineTtsService.prefsFor = { Prefs(it) }
        sp().edit().clear().commit()
        File(ctx.cacheDir, "audioCache").deleteRecursively()
    }

    private fun connect() {
        assertTrue(OnlineTtsService.save(prefs, OnlineTtsConfig(model = OnlineTtsChoice("gpt-4o-mini-tts")), "sk-SECRET"))
    }

    private fun open(current: AudioSelection = AudioSelection.Auto): AudioSourcePickerActivity =
        Robolectric.buildActivity(
            AudioSourcePickerActivity::class.java,
            AudioSourcePickerActivity.intent(ctx, SourceLangId.JA, "行く", "いく", isWord = true, current = current),
        ).also { controller = it }.setup().get()

    private fun pumpUntil(what: String, done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!done()) {
            assertTrue("timed out waiting for $what", System.currentTimeMillis() < deadline)
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    private fun sections(page: AudioSourcePickerActivity) = page.findViewById<LinearLayout>(R.id.sourceSections)

    /** The section headers, top to bottom. */
    private fun headers(page: AudioSourcePickerActivity): List<String> {
        val all = sections(page)
        return (0 until all.childCount).mapNotNull { all.getChildAt(it).findViewById<TextView>(R.id.tvGroupTitle) }
            .map { it.text.toString() }
    }

    /** The rows of the section under [header]: the card after it. */
    private fun rowsUnder(page: AudioSourcePickerActivity, header: String): List<View> {
        val all = sections(page)
        val at = (0 until all.childCount).first {
            all.getChildAt(it).findViewById<TextView>(R.id.tvGroupTitle)?.text?.toString() == header
        }
        val card = all.getChildAt(at + 1) as ViewGroup
        val rows = card.getChildAt(0) as LinearLayout
        return (0 until rows.childCount).map { rows.getChildAt(it) }
            .filter { it.findViewById<TextView>(R.id.tvRowTitle) != null }
    }

    private fun View.title() = findViewById<TextView>(R.id.tvRowTitle).text.toString()
    private fun View.ticked() = findViewById<View>(R.id.ivCheck).visibility == View.VISIBLE

    @Test fun `with no service connected the picker has no voice service section and asks nothing`() {
        val page = open()
        assertFalse(headers(page).contains("Voice service"))
        assertTrue(headers(page).contains("Text-to-speech"))
        assertTrue(seen.isEmpty())
    }

    @Test fun `a connected service's voices are a section above the device's`() {
        connect()
        val page = open()
        val headers = headers(page)
        assertTrue(headers.toString(), headers.indexOf("Voice service") in 0 until headers.indexOf("Text-to-speech"))
        pumpUntil("the service's voices") { rowsUnder(page, "Voice service").map { it.title() } == listOf("Alpha", "Beta") }
        assertEquals("https://api.openai.com/v1/audio/voices", seen.single().request.url.toString())
    }

    @Test fun `a tapped voice is auditioned through the service and returned as the pick, with its name`() {
        connect()
        val page = open()
        pumpUntil("the service's voices") { rowsUnder(page, "Voice service").size == 2 }
        rowsUnder(page, "Voice service")[1].performClick()
        assertEquals(listOf(false, true), rowsUnder(page, "Voice service").map { it.ticked() })
        pumpUntil("the audition request") { seen.any { it.request.method == "POST" } }
        val audition = seen.single { it.request.method == "POST" }
        assertEquals("jf_beta", audition.json["voice"]!!.jsonPrimitive.content)
        assertEquals("the word's reading, as the card's audio will be", "いく", audition.json["input"]!!.jsonPrimitive.content)

        page.findViewById<View>(R.id.btnSave).performClick()
        val result = shadowOf(page).resultIntent
        assertEquals("online_tts", result.getStringExtra(AudioSourcePickerActivity.EXTRA_PICKED_SOURCE))
        assertEquals("jf_beta", result.getStringExtra(AudioSourcePickerActivity.EXTRA_PICKED_KEY))
        assertEquals("Beta", result.getStringExtra(AudioSourcePickerActivity.EXTRA_PICKED_LOCATOR))
    }

    @Test fun `a card's picked service voice opens ticked`() {
        connect()
        val page = open(AudioSelection.Explicit("online_tts", "jf_alpha", locator = "Alpha"))
        pumpUntil("the service's voices") { rowsUnder(page, "Voice service").size == 2 }
        assertEquals(listOf(true, false), rowsUnder(page, "Voice service").map { it.ticked() })
    }

    @Test fun `a voice the service cannot speak says so instead of playing nothing`() {
        connect()
        speechStatus = 401
        val page = open()
        pumpUntil("the service's voices") { rowsUnder(page, "Voice service").size == 2 }
        rowsUnder(page, "Voice service")[0].performClick()
        pumpUntil("the toast") { ShadowToast.getTextOfLatestToast() != null }
        assertEquals("Couldn't play this audio", ShadowToast.getTextOfLatestToast())
    }
}
