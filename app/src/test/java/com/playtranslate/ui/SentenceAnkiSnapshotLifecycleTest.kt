package com.playtranslate.ui

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import com.playtranslate.R
import com.playtranslate.capture.GameAudioSnapshot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Pins the game-audio snapshot's file-lifetime invariant: the fragment may
 * delete its snapshot only on provably-final teardown (onDestroyView with no
 * saved instance state). A teardown WITH state saved — the review activity
 * destroyed while stopped behind the trim editor / audio picker, memory
 * pressure, don't-keep-activities — must keep the file: the just-saved
 * bundle references it and the restored fragment re-owns it. The original
 * isChangingConfigurations guard deleted the file on exactly that path
 * (the deleted-snapshot-on-restore bug, adversarial-review finding).
 */
@RunWith(RobolectricTestRunner::class)
class SentenceAnkiSnapshotLifecycleTest {

    /** Hosts the real fragment under the app theme (pt* attrs resolve) —
     *  set before super.onCreate so saved-state restores inflate with it
     *  too, since restore re-attaches fragments inside super.onCreate. */
    class Host : FragmentActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_PlayTranslate)
            super.onCreate(savedInstanceState)
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
            .findFragmentByTag(TAG) as SentenceAnkiContentFragment
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

    private fun addFragment(activity: FragmentActivity): SentenceAnkiContentFragment {
        val fragment = SentenceAnkiContentFragment.newInstance(
            "テスト文です。", "A test sentence.", emptyList(), screenshotPath = null,
        )
        activity.supportFragmentManager.beginTransaction()
            .add(android.R.id.content, fragment, TAG)
            .commitNow()
        return fragment
    }

    /** A snapshot with a real PCM payload per [GameAudioSnapshot.isUsable]
     *  (> WAV header), in the real snapshot dir. */
    private fun writeSnapshot(activity: FragmentActivity, name: String): File {
        val dir = GameAudioSnapshot.dir(activity).apply { mkdirs() }
        return File(dir, "snap-test-$name.wav").apply { writeBytes(ByteArray(4096)) }
    }

    // The field is deliberately private prod API; tests inject the ownership
    // state directly rather than widening it. It lives on the extracted
    // [SentenceAnkiContentView], reached through the fragment shell's
    // (equally private) content handle.
    private fun setSnapshotField(fragment: SentenceAnkiContentFragment, file: File) {
        snapshotField().set(contentOf(fragment), file)
    }

    private fun getSnapshotField(fragment: SentenceAnkiContentFragment): File? =
        snapshotField().get(contentOf(fragment)) as File?

    private fun contentOf(fragment: SentenceAnkiContentFragment): SentenceAnkiContentView =
        SentenceAnkiContentFragment::class.java
            .getDeclaredField("content")
            .apply { isAccessible = true }
            .get(fragment) as SentenceAnkiContentView

    private fun snapshotField() =
        SentenceAnkiContentView::class.java
            .getDeclaredField("gameAudioSnapshotFile")
            .apply { isAccessible = true }

    private companion object {
        const val TAG = "snapshot-lifecycle-test"
    }
}
