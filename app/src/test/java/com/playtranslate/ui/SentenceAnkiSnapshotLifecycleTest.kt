package com.playtranslate.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.audio.AudioSelection
import com.playtranslate.capture.GameAudioSnapshot
import com.playtranslate.language.SourceLangId
import com.playtranslate.vocab.HiddenWordsStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File

/**
 * Pins the game-audio snapshot's file-lifetime invariant: a fragment host
 * may delete its snapshot only on provably-final teardown
 * ([isFinalMediaTeardown] — the exact clause the review sheets pass to
 * [SentenceAnkiContentView.release]). A teardown WITH state saved — the
 * review activity destroyed while stopped behind the trim editor / audio
 * picker, memory pressure, don't-keep-activities — must keep the file: the
 * just-saved bundle references it and the restored instance re-owns it. The
 * original isChangingConfigurations guard deleted the file on exactly that
 * path (the deleted-snapshot-on-restore bug, adversarial-review finding).
 *
 * The card content became the host-agnostic [SentenceAnkiContentView]
 * (overlay rehost step 3c/3d); [ContentHostFragment] below is the minimal
 * fragment shell exercising the SAME prod pieces the review sheets use:
 * buildInto/saveState/restore, and release(deleteSnapshotFile =
 * isFinalMediaTeardown()).
 *
 * The same restore also keeps each audio switch on its own setting: every
 * audio row is a settings_row_switch copy, so the sentence's switch and each
 * target word's share one view id, and restored by id the sentence switch
 * took the word switch's state and wrote it into the sentence-audio default
 * ([SwitchRowSaveStateTest] guards the layouts).
 */
@RunWith(RobolectricTestRunner::class)
class SentenceAnkiSnapshotLifecycleTest {

    /** Hosts the fragment under the app theme (pt* attrs resolve) —
     *  set before super.onCreate so saved-state restores inflate with it
     *  too, since restore re-attaches fragments inside super.onCreate. */
    class Host : FragmentActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_PlayTranslate)
            super.onCreate(savedInstanceState)
        }
    }

    /** Minimal fragment host for [SentenceAnkiContentView] — the review
     *  sheets' hosting pattern reduced to the pieces under test. Public
     *  no-arg class so FragmentManager can re-instantiate it on restore. */
    class ContentHostFragment : Fragment() {
        var content: SentenceAnkiContentView? = null

        override fun onCreateView(
            inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
        ): View = inflater.inflate(R.layout.fragment_sentence_anki_content, container, false)

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            val c = SentenceAnkiContentView(
                requireContext(),
                viewLifecycleOwner.lifecycleScope,
                arguments ?: SentenceAnkiContentView.buildArgs(
                    "テスト文です。", "A test sentence.", emptyList(), screenshotPath = null,
                ).also { arguments = it },
                object : SentenceAnkiContentView.Host {
                    override val isAlive: Boolean get() = isAdded
                    override fun openAudioPicker(
                        intent: Intent, onPicked: (AudioSelection) -> Unit,
                    ) = Unit
                },
            )
            content = c
            c.buildInto(view as LinearLayout, savedInstanceState)
        }

        override fun onSaveInstanceState(outState: Bundle) {
            super.onSaveInstanceState(outState)
            content?.saveState(outState)
        }

        override fun onDestroyView() {
            content?.release(deleteSnapshotFile = isFinalMediaTeardown())
            content = null
            super.onDestroyView()
        }
    }

    @After
    fun tearDown() {
        GameAudioSnapshot.active = null
    }

    @Test
    fun savedStateTeardown_keepsSnapshot_andRestoreReownsIt() {
        val controller = Robolectric.buildActivity(Host::class.java).setup()
        val activity = controller.get()
        val fragment = addFragment(activity)
        val snap = writeSnapshot(activity, "saved-state")
        setSnapshotField(fragment, snap)
        GameAudioSnapshot.active = snap

        val state = Bundle()
        controller.pause().saveInstanceState(state).stop().destroy()

        // The saved bundle references this file; deleting it here was the bug.
        assertTrue("snapshot must survive a saved-state teardown", snap.exists())

        val restored = Robolectric.buildActivity(Host::class.java).setup(state)
        val restoredFragment = restored.get().supportFragmentManager
            .findFragmentByTag(TAG) as ContentHostFragment
        assertEquals(snap, getSnapshotField(restoredFragment))
        assertEquals(snap, GameAudioSnapshot.active)

        // The restored instance's real finish reclaims the file.
        restored.get().finish()
        restored.pause().stop().destroy()
        assertFalse(snap.exists())
    }

    @Test
    fun finishFromStopped_deletesSnapshot() {
        val controller = Robolectric.buildActivity(Host::class.java).setup()
        val activity = controller.get()
        val fragment = addFragment(activity)
        val snap = writeSnapshot(activity, "finish")
        setSnapshotField(fragment, snap)
        GameAudioSnapshot.active = snap

        // Finish-from-stopped (back out, tracker.finishCurrentIfAny): the
        // FragmentManager reports isStateSaved for a merely-stopped host, so
        // only the isFinishing clause proves finality here — a finished
        // activity is never restored.
        activity.finish()
        controller.pause().stop().destroy()

        assertFalse("snapshot must be reclaimed when the activity finishes", snap.exists())
        assertNull(GameAudioSnapshot.active)
    }

    @Test
    fun savedStateRestore_keepsEachAudioSwitchOnItsOwnSetting(): Unit = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        HiddenWordsStore.resetForTest(ctx)
        val prefs = Prefs(ctx)
        prefs.ankiSentenceAudioEnabled = true
        prefs.ankiWordAudioEnabled = true
        try {
            val controller = Robolectric.buildActivity(Host::class.java).setup()
            val fragment = addFragment(
                controller.get(),
                SentenceAnkiContentView.buildArgs(
                    "猫が食べる。", "The cat eats.",
                    listOf(
                        SentenceAnkiHtmlBuilder.WordEntry("食べる", "たべる", "to eat", 3),
                        SentenceAnkiHtmlBuilder.WordEntry("猫", "ねこ", "cat", 5),
                    ),
                    screenshotPath = null, targetWord = "猫", sourceLangId = SourceLangId.JA,
                ),
            )
            settle(ctx)
            // Sentence audio stays on; the target word's audio goes off.
            audioSwitches(fragment).last().performClick()
            assertEquals(listOf(true, false), audioSwitches(fragment).map { it.isChecked })

            val state = Bundle()
            controller.pause().saveInstanceState(state).stop().destroy()
            val restored = Robolectric.buildActivity(Host::class.java).setup(state)
            settle(ctx)
            val restoredFragment = restored.get().supportFragmentManager
                .findFragmentByTag(TAG) as ContentHostFragment

            assertEquals(listOf(true, false), audioSwitches(restoredFragment).map { it.isChecked })
            assertTrue(restoredFragment.content!!.sentenceAudioEnabled)
            assertTrue(prefs.ankiSentenceAudioEnabled)
            assertFalse(prefs.ankiWordAudioEnabled)
            restored.pause().stop().destroy()
        } finally {
            shadowOf(Looper.getMainLooper()).idle()
            HiddenWordsStore.resetForTest(ctx)
            ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE)
                .edit().clear().commit()
        }
    }

    @Test
    fun resumedDismissal_deletesSnapshot() {
        val controller = Robolectric.buildActivity(Host::class.java).setup()
        val activity = controller.get()
        val fragment = addFragment(activity)
        val snap = writeSnapshot(activity, "dismiss")
        setSnapshotField(fragment, snap)
        GameAudioSnapshot.active = snap

        // The common flow end: the sheet dismissed while the host is resumed
        // — fragment removed with the manager neither stopped nor state-saved.
        activity.supportFragmentManager.beginTransaction().remove(fragment).commitNow()

        assertFalse("snapshot must be reclaimed on resumed dismissal", snap.exists())
        assertNull(GameAudioSnapshot.active)
    }

    private fun addFragment(activity: FragmentActivity, args: Bundle? = null): ContentHostFragment {
        val fragment = ContentHostFragment().apply { arguments = args }
        activity.supportFragmentManager.beginTransaction()
            .add(android.R.id.content, fragment, TAG)
            .commitNow()
        return fragment
    }

    /** The card's audio switches in order: the sentence's (Original group),
     *  then each target word's. */
    private fun audioSwitches(fragment: ContentHostFragment): List<CompoundButton> {
        fun walk(v: View): List<View> =
            listOf(v) + ((v as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { walk(g.getChildAt(it)) } } ?: emptyList())
        return walk(fragment.requireView()).filterIsInstance<CompoundButton>()
            .filter { it.id == R.id.switchRowToggle }
    }

    /** Main-thread work plus the hidden-words load the word rows trigger,
     *  whose revision bump rebuilds them. */
    private fun settle(ctx: Context) {
        repeat(3) {
            shadowOf(Looper.getMainLooper()).idle()
            runBlocking { HiddenWordsStore.loaded(ctx, SourceLangId.JA) }
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** A snapshot with a real PCM payload per [GameAudioSnapshot.isUsable]
     *  (> WAV header), in the real snapshot dir. */
    private fun writeSnapshot(activity: FragmentActivity, name: String): File {
        val dir = GameAudioSnapshot.dir(activity).apply { mkdirs() }
        return File(dir, "snap-test-$name.wav").apply { writeBytes(ByteArray(4096)) }
    }

    // The field is deliberately private prod API; tests inject the ownership
    // state directly rather than widening it.
    private fun setSnapshotField(fragment: ContentHostFragment, file: File) {
        snapshotField().set(fragment.content!!, file)
    }

    private fun getSnapshotField(fragment: ContentHostFragment): File? =
        snapshotField().get(fragment.content!!) as File?

    private fun snapshotField() =
        SentenceAnkiContentView::class.java
            .getDeclaredField("gameAudioSnapshotFile")
            .apply { isAccessible = true }

    private companion object {
        const val TAG = "snapshot-lifecycle-test"
    }
}
