package com.playtranslate.ui

import android.app.Activity
import android.os.Looper
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.playtranslate.R
import com.playtranslate.audio.AudioRequest
import com.playtranslate.audio.FakeSpeech
import com.playtranslate.audio.PlayOutcome
import com.playtranslate.language.SourceLangId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

/**
 * The Original header's read-aloud ([OriginalSpeaker]) as the tap drives it
 * ([OriginalSpeaker.toggle]) and as the app drives it by itself for a new
 * capture ([OriginalSpeaker.play]).
 *
 * Pins: `play` reads the text it is given, not the text on show, and lights
 * the header while it reads; a second `play` replaces the first and the
 * header stays lit for the one now reading, also when the replaced read's
 * work returns late; a read that ends at once, without
 * ever waiting, leaves the header unlit; `toggle` during a read the app
 * started stops it; a voice that cannot speak raises no alert on `play` and
 * raises the tap's alert on `toggle`.
 *
 * The voice is [FakeSpeech]: a read stays in flight while its hold is open.
 */
@RunWith(RobolectricTestRunner::class)
class OriginalSpeakerTest {

    class Host : AppCompatActivity() {
        override fun onCreate(savedInstanceState: android.os.Bundle?) {
            setTheme(R.style.Theme_PlayTranslate)
            super.onCreate(savedInstanceState)
        }
    }

    private lateinit var controller: ActivityController<Host>
    private lateinit var activity: Activity
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val speech = FakeSpeech()

    /** Every lit and unlit the speaker reported, in order. */
    private val lit = mutableListOf<Boolean>()

    /** What the header's tap would read: the text on show. */
    private var shown: String? = "画面の文"

    private lateinit var speaker: OriginalSpeaker

    @Before fun setUp() {
        controller = Robolectric.buildActivity(Host::class.java).setup()
        activity = controller.get()
        speech.install()
        speaker = OriginalSpeaker(scope, TtsAlertTarget.InActivity(activity), { lit += it }) {
            shown?.let { OriginalSpeaker.Request(it, SourceLangId.JA) }
        }
    }

    @After fun tearDown() {
        speaker.release()
        scope.cancel()
        FakeSpeech.uninstall()
        controller.pause().stop().destroy()
        idle()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun sentence(text: String) = AudioRequest.sentence(text, SourceLangId.JA)

    /** The titles of the alerts up over the activity. */
    private fun alertTitles(): List<String> {
        idle()
        val titles = mutableListOf<String>()
        fun walk(v: android.view.View) {
            if (v is TextView) titles += v.text.toString()
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(activity.window.decorView)
        return titles
    }

    @Test fun `play reads the text it is given and lights the header while it reads`() {
        val reading = CompletableDeferred<Unit>()
        speech.hold = reading
        speaker.play(OriginalSpeaker.Request("新しい文", SourceLangId.JA))
        assertEquals("the given text, not the one on show", listOf(sentence("新しい文")), speech.played)
        assertEquals(listOf(true), lit)

        reading.complete(Unit)
        idle()
        assertEquals("unlit once the read is over", listOf(true, false), lit)
    }

    @Test fun `a second play replaces the first and the header stays lit for it`() {
        val first = CompletableDeferred<Unit>()
        speech.hold = first
        speaker.play(OriginalSpeaker.Request("一つ目", SourceLangId.JA))
        val second = CompletableDeferred<Unit>()
        speech.hold = second
        speaker.play(OriginalSpeaker.Request("二つ目", SourceLangId.JA))
        idle()
        assertEquals(listOf(sentence("一つ目"), sentence("二つ目")), speech.played)
        assertTrue("lit for the read now in flight: $lit", lit.last())

        second.complete(Unit)
        idle()
        assertFalse("and unlit when that one ends: $lit", lit.last())
    }

    @Test fun `a replaced read that ends late leaves the header lit for the one now reading`() {
        // The first read's work does not end at its cancel, as a clip being
        // fetched off the main thread does not.
        val first = CompletableDeferred<Unit>()
        speech.hold = first
        speech.holdThroughCancel = true
        speaker.play(OriginalSpeaker.Request("一つ目", SourceLangId.JA))
        val second = CompletableDeferred<Unit>()
        speech.hold = second
        speaker.play(OriginalSpeaker.Request("二つ目", SourceLangId.JA))
        idle()
        assertTrue("precondition: lit for the second", lit.last())

        first.complete(Unit)
        idle()
        assertTrue("the replaced read's end is not the header's: $lit", lit.last())

        second.complete(Unit)
        idle()
        assertFalse("unlit when the read on show ends: $lit", lit.last())
    }

    @Test fun `a read that ends at once leaves the header unlit`() {
        speaker.play(OriginalSpeaker.Request("すぐ終わる", SourceLangId.JA))
        idle()
        assertEquals(listOf(sentence("すぐ終わる")), speech.played)
        assertEquals(listOf(true, false), lit)
    }

    @Test fun `the header's tap during a read the app started stops it, and the next tap reads the text on show`() {
        speech.hold = CompletableDeferred()
        speaker.play(OriginalSpeaker.Request("新しい文", SourceLangId.JA))
        speaker.toggle()
        idle()
        assertEquals("the tap started nothing", 1, speech.played.size)
        assertEquals(listOf(true, false), lit)

        speaker.toggle()
        assertEquals(sentence("画面の文"), speech.played.last())
        assertTrue(lit.last())
    }

    @Test fun `a voice that cannot speak raises no alert for a read the app started, and the tap's alert for a tap`() {
        speech.outcome = PlayOutcome.TtsNoEngine
        val title = activity.getString(R.string.tts_no_engine_dialog_title)
        speaker.play(OriginalSpeaker.Request("新しい文", SourceLangId.JA))
        assertFalse("no alert by itself", title in alertTitles())

        speaker.toggle()
        assertTrue("the tap says why", title in alertTitles())
    }
}
