package com.playtranslate.ui

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.view.Display
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.audio.AudioRequest
import com.playtranslate.audio.FakeSpeech
import com.playtranslate.audio.PlayOutcome
import com.playtranslate.language.SourceLangId
import kotlinx.coroutines.CompletableDeferred
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
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/**
 * A lens speaking its word by itself ([LensSpeech], [Prefs.autoPlayWords]),
 * on a real [MagnifierLens] whose rows are bound and which is then promoted
 * to sticky, as both of its owners do for each word.
 *
 * Pins: with the setting on, the lens turning sticky plays the looked-up
 * word once, with its reading, and that row draws its spinner while the
 * play is in flight; a play that returns at once, without ever waiting,
 * still leaves the row with no spinner; the looked-up word's row is the one
 * played when a related unit's row sits above it; a later bind on the
 * sticky lens ("Load more") plays nothing; with the setting off nothing
 * plays; a voice that cannot speak raises no alert and leaves the lens up,
 * where the tap on the same row's Speak button raises the alert and closes
 * the lens.
 *
 * The voice is [FakeSpeech].
 */
@RunWith(RobolectricTestRunner::class)
class LensAutoPlayTest {

    class Host : Activity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_PlayTranslate)
            super.onCreate(savedInstanceState)
        }
    }

    private lateinit var controller: ActivityController<Host>
    private lateinit var activity: Activity
    private lateinit var lens: MagnifierLens
    private val speech = FakeSpeech()

    private fun section(word: String, reading: String) = LensSection(
        WordDefinitionData(word, reading, emptyList(), freqScore = 0, isCommon = false),
        label = null,
        opens = true,
        anki = false,
    )

    private val primary = section("弾ける", "はじける")
    private val secondaries = listOf(section("弾く", "ひく"), section("弾く", "はじく"))

    private fun sp() = activity.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE)

    @Before fun setUp() {
        DefinitionShells.reset()
        controller = Robolectric.buildActivity(Host::class.java).setup()
        activity = controller.get()
        sp().edit().clear().commit()
        speech.install()
        lens = MagnifierLens(activity, activity.windowManager, Display.DEFAULT_DISPLAY)
        // As an owner wires it: each row speaks its own unit.
        LensSpeech(lens, TtsAlertTarget.InActivity(activity)) { secondary ->
            val unit = if (secondary == null) primary else secondaries.getOrNull(secondary)
            unit?.let { SpeakRequest(it.data.word, SourceLangId.JA, reading = it.data.reading) }
        }
        val dm = activity.resources.displayMetrics
        lens.show(dm.widthPixels / 2, dm.heightPixels * 3 / 4, dm.widthPixels, dm.heightPixels)
    }

    @After fun tearDown() {
        lens.dismiss()
        FakeSpeech.uninstall()
        sp().edit().clear().commit()
        controller.pause().stop().destroy()
        idle()
        DefinitionShells.reset()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun rows() = lens.shownRowsForTest().filterIsInstance<DefRow.Word>()

    private fun row(word: String, reading: String) = rows().single { it.data.word == word && it.data.reading == reading }

    private fun word(section: LensSection) = AudioRequest.word(section.data.word, section.data.reading, SourceLangId.JA)

    private fun textsOnScreen(): List<String> {
        idle()
        val texts = mutableListOf<String>()
        fun walk(v: View) {
            if (v is TextView) texts += v.text.toString()
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(activity.window.decorView)
        return texts
    }

    @Test fun `the lens turning sticky plays its word once, and the row draws its spinner while the play is in flight`() {
        Prefs(activity).autoPlayWords = true
        val playing = CompletableDeferred<Unit>()
        speech.hold = playing
        lens.setDefinitions(primary)
        assertTrue("a bind alone plays nothing", speech.played.isEmpty())

        assertTrue(lens.makeInteractive())
        assertEquals("the word, read as the lens shows it", listOf(word(primary)), speech.played)
        assertEquals(DefRow.Speak.LOADING, row("弾ける", "はじける").speak)

        playing.complete(Unit)
        idle()
        assertEquals(DefRow.Speak.IDLE, row("弾ける", "はじける").speak)
        assertEquals("once", 1, speech.played.size)
    }

    @Test fun `a play that returns at once leaves the row with no spinner`() {
        Prefs(activity).autoPlayWords = true
        lens.setDefinitions(primary)
        lens.makeInteractive()
        idle()
        assertEquals(listOf(word(primary)), speech.played)
        assertEquals(DefRow.Speak.IDLE, row("弾ける", "はじける").speak)
    }

    @Test fun `the looked-up word's row is the one played when a related unit sits above it`() {
        Prefs(activity).autoPlayWords = true
        speech.hold = CompletableDeferred()
        lens.setSplitDefinitions(primary, listOf(secondaries[0]), secondariesOnTop = true)
        assertEquals("precondition: the related unit's row is first", "弾く", rows().first().data.word)

        lens.makeInteractive()
        assertEquals(listOf(word(primary)), speech.played)
        assertEquals(DefRow.Speak.LOADING, row("弾ける", "はじける").speak)
        assertEquals(DefRow.Speak.IDLE, row("弾く", "ひく").speak)
    }

    @Test fun `a later bind on the sticky lens plays nothing`() {
        Prefs(activity).autoPlayWords = true
        lens.setSplitDefinitions(primary, listOf(secondaries[0]), secondariesOnTop = false, loadMore = LensLoadMore(1))
        lens.makeInteractive()
        idle()
        assertEquals(1, speech.played.size)

        // "Load more" brought the rest: the owner binds again, on the sticky lens.
        lens.setSplitDefinitions(primary, secondaries, secondariesOnTop = false)
        idle()
        assertEquals("the word is not said again", 1, speech.played.size)
    }

    @Test fun `with the setting off the lens plays nothing by itself`() {
        lens.setDefinitions(primary)
        lens.makeInteractive()
        idle()
        assertTrue(speech.played.isEmpty())
        assertEquals(DefRow.Speak.IDLE, row("弾ける", "はじける").speak)
    }

    @Test fun `a voice that cannot speak raises no alert by itself and leaves the lens up, and the tap on the row raises it`() {
        Prefs(activity).autoPlayWords = true
        speech.outcome = PlayOutcome.TtsNoEngine
        val title = activity.getString(R.string.tts_no_engine_dialog_title)
        lens.setDefinitions(primary)
        lens.makeInteractive()
        assertEquals("it did try", 1, speech.played.size)
        assertFalse("no alert on a lookup", title in textsOnScreen())
        assertTrue("the lens is still up", lens.isInteractive)

        ShadowSystemClock.advanceBy(Duration.ofMillis(400))
        lens.tapRowForTest(row("弾ける", "はじける").id, DefAction.SPEAK)
        assertTrue("the tap says why", title in textsOnScreen())
    }
}
