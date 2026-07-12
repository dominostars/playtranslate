package com.playtranslate.capture

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Pins the snapshot store's two ownership guarantees: allocation can never
 * alias two card flows onto one path (same-millisecond card opens), and the
 * orphan sweep only trusts age as proof of orphaning for files no live flow
 * claims — the [GameAudioSnapshot.active] file is exempt regardless of age.
 */
@RunWith(RobolectricTestRunner::class)
class GameAudioSnapshotTest {

    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun tearDown() {
        GameAudioSnapshot.active = null
        GameAudioSnapshot.dir(ctx).deleteRecursively()
    }

    @Test
    fun newFile_neverAliasesTwoFlows() {
        // Back-to-back allocations land in the same millisecond; a
        // timestamp-named scheme hands both flows the same path.
        val a = GameAudioSnapshot.newFile(ctx)
        val b = GameAudioSnapshot.newFile(ctx)
        assertNotEquals(a, b)
        // Exclusive creation: the path is claimed on allocation, not first write.
        assertTrue(a.exists() && b.exists())
        // A freshly allocated file is not yet a usable snapshot (no payload).
        assertFalse(GameAudioSnapshot.isUsable(a))
    }

    @Test
    fun sweep_reapsTrueOrphans_butNeverTheActiveFlow() {
        val dir = GameAudioSnapshot.dir(ctx).apply { mkdirs() }
        val staleAge = System.currentTimeMillis() - 25L * 60 * 60 * 1000

        val orphan = File(dir, "snap-orphan.wav").apply {
            writeBytes(ByteArray(100)); setLastModified(staleAge)
        }
        val staleButLive = File(dir, "snap-live.wav").apply {
            writeBytes(ByteArray(100)); setLastModified(staleAge)
        }
        val fresh = File(dir, "snap-fresh.wav").apply { writeBytes(ByteArray(100)) }
        GameAudioSnapshot.active = staleButLive

        GameAudioSnapshot.sweepOrphans(ctx)

        assertFalse("stale unclaimed file must be reaped", orphan.exists())
        assertTrue("the active flow's file must survive any age", staleButLive.exists())
        assertTrue("fresh files must survive", fresh.exists())
    }
}
