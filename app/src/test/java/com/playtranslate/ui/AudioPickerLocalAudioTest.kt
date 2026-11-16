package com.playtranslate.ui

import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.R
import com.playtranslate.audio.AudioCache
import com.playtranslate.audio.AudioSelection
import com.playtranslate.audio.RecordingPlayer
import com.playtranslate.audio.localaudio.LocalAudioDb
import com.playtranslate.audio.localaudio.LocalAudioFixture
import com.playtranslate.language.SourceLangId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
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
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * The Anki audio picker with an imported file of recordings: no section
 * until a file is loaded; then, for a word of any language, a section of
 * the word's recordings above Commons and the voices; a tapped recording
 * auditioned and returned as the pick; and no section for a sentence.
 */
@RunWith(RobolectricTestRunner::class)
class AudioPickerLocalAudioTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private fun sp() = ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE)
    private val savedDecoder = RecordingPlayer.decoder
    private var controller: ActivityController<AudioSourcePickerActivity>? = null

    @Before fun setUp() {
        sp().edit().clear().commit()
        File(ctx.cacheDir, "audioCache").deleteRecursively()
        LocalAudioFixture.clear(ctx)
        // The words have no Commons recording, remembered: the Commons
        // section asks the network for nothing.
        AudioCache(ctx).markNegative("wikimedia_commons", SourceLangId.JA, "食べる")
        AudioCache(ctx).markNegative("wikimedia_commons", SourceLangId.ZH, "日本")
    }

    @After fun tearDown() {
        controller?.let { runCatching { it.pause().stop().destroy() } }
        RecordingPlayer.decoder = savedDecoder
        LocalAudioFixture.clear(ctx)
        sp().edit().clear().commit()
        File(ctx.cacheDir, "audioCache").deleteRecursively()
    }

    private fun open(
        lang: SourceLangId = SourceLangId.JA,
        word: String = "食べる",
        reading: String? = "たべる",
        isWord: Boolean = true,
        current: AudioSelection = AudioSelection.Auto,
    ): AudioSourcePickerActivity =
        Robolectric.buildActivity(
            AudioSourcePickerActivity::class.java,
            AudioSourcePickerActivity.intent(ctx, lang, word, reading, isWord = isWord, current = current),
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

    /** The rows of the file's section: the card after its header. */
    private fun recordings(page: AudioSourcePickerActivity): List<View> {
        val all = sections(page)
        val at = (0 until all.childCount).first {
            all.getChildAt(it).findViewById<TextView>(R.id.tvGroupTitle)?.text?.toString() == "android.db file"
        }
        val card = all.getChildAt(at + 1) as ViewGroup
        val rows = card.getChildAt(0) as LinearLayout
        return (0 until rows.childCount).map { rows.getChildAt(it) }
            .filter { it.findViewById<TextView>(R.id.tvRowTitle) != null }
    }

    private fun View.title() = findViewById<TextView>(R.id.tvRowTitle).text.toString()
    private fun View.subtitle() = findViewById<TextView>(R.id.tvRowSubtitle)
        .let { if (it.visibility == View.VISIBLE) it.text.toString() else null }
    private fun View.ticked() = findViewById<View>(R.id.ivCheck).visibility == View.VISIBLE

    @Test fun `with no file loaded the picker has no section for one`() {
        val page = open()
        assertFalse(headers(page).contains("android.db file"))
        assertTrue(headers(page).contains("Text-to-speech"))
    }

    @Test fun `a word's recordings are a section above Commons and the voices`() {
        LocalAudioFixture.install(ctx)
        val page = open()
        val headers = headers(page)
        assertTrue(headers.toString(), headers.indexOf("android.db file") in 0 until headers.indexOf("Wikimedia Commons"))
        assertTrue(headers.toString(), headers.indexOf("android.db file") < headers.indexOf("Text-to-speech"))
        pumpUntil("the word's recordings") { recordings(page).map { it.title() } == listOf("NHK", "Shinmeikai", "Forvo") }
        assertEquals(listOf("タベ＼ル", null, "strawberrybrown"), recordings(page).map { it.subtitle() })
        assertTrue("nothing is picked until the user picks", recordings(page).none { it.ticked() })
    }

    @Test fun `a tapped recording is auditioned and returned as the pick`() {
        LocalAudioFixture.install(ctx)
        val played = AtomicReference<ByteArray?>()
        RecordingPlayer.decoder = { file, _ ->
            played.set(file.readBytes())
            RecordingPlayer.Decoded.Audio(ByteArray(2 * 16_000), sampleRate = 16_000, channelCount = 1)
        }
        val page = open()
        pumpUntil("the word's recordings") { recordings(page).size == 3 }
        recordings(page)[1].performClick()
        assertEquals(listOf(false, true, false), recordings(page).map { it.ticked() })
        pumpUntil("the audition") { played.get() != null }
        assertArrayEquals("shinmeikai8/media/taberu.mp3".toByteArray(), played.get())

        page.findViewById<View>(R.id.btnSave).performClick()
        val result = shadowOf(page).resultIntent
        assertEquals("local_audio", result.getStringExtra(AudioSourcePickerActivity.EXTRA_PICKED_SOURCE))
        assertEquals("shinmeikai8/media/taberu.mp3", result.getStringExtra(AudioSourcePickerActivity.EXTRA_PICKED_KEY))
    }

    @Test fun `a card's picked recording opens ticked`() {
        LocalAudioFixture.install(ctx)
        val page = open(current = AudioSelection.Explicit("local_audio", "forvo/strawberrybrown/食べる.mp3"))
        pumpUntil("the word's recordings") { recordings(page).size == 3 }
        assertEquals(listOf(false, false, true), recordings(page).map { it.ticked() })
    }

    @Test fun `a word with no recording has the section, saying there are none`() {
        LocalAudioFixture.install(ctx)
        AudioCache(ctx).markNegative("wikimedia_commons", SourceLangId.JA, "飲む")
        val page = open(word = "飲む", reading = "のむ")
        pumpUntil("the empty section") { recordings(page).map { it.title() } == listOf("No results") }
    }

    @Test fun `a word of another language has the section too, with the recordings of that written form`() {
        LocalAudioFixture.install(ctx)
        val page = open(lang = SourceLangId.ZH, word = "日本", reading = null)
        assertTrue(headers(page).contains("android.db file"))
        pumpUntil("the word's recordings") { recordings(page).map { it.title() } == listOf("NHK", "NHK") }
    }

    @Test fun `a sentence has no section for the file`() {
        LocalAudioFixture.install(ctx)
        val page = open(isWord = false)
        assertFalse(headers(page).contains("android.db file"))
    }
}
