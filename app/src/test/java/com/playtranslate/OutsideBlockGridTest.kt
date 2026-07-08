package com.playtranslate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [OutsideBlockGrid] under the speed-first rules (2026-07-08):
 * fire on the FIRST still-and-differing look (no confirmation wait), hold
 * only while a block is mid-motion (with a wake claim when it also differs),
 * and report volatile blocks so the caller disables gate skipping entirely
 * on screens with persistent animation. Block 0 is the subject throughout.
 */
class OutsideBlockGridTest {

    private fun grid() = OutsideBlockGrid().apply { configure(0, 0, 64, 64) }

    /** One run: each entry feeds [SAMPLES] samples into a block —
     *  (blockIndex, perSampleLuma, changedSampleCount). */
    private fun run(
        g: OutsideBlockGrid,
        vararg blocks: Triple<Int, Int, Int>,
    ): OutsideBlockGrid.Verdict {
        g.beginRun()
        for ((b, luma, changed) in blocks) {
            repeat(SAMPLES) { i -> g.accumulate(b, luma, i < changed) }
        }
        g.finishRun(g.lastVerdict)
        return g.lastVerdict
    }

    @Test
    fun `still differing block fires on its first look`() {
        val g = grid()
        run(g, Triple(0, 100, 0)) // seed
        val v = run(g, Triple(0, 100, 5))
        assertTrue(v.fired)
        assertEquals(1, v.firedBlocks)
        assertFalse(v.pendingSettle)
    }

    @Test
    fun `seeding look never fires even when differing`() {
        val g = grid()
        val v = run(g, Triple(0, 100, 5))
        assertFalse(v.fired)
        assertFalse(v.pendingSettle)
    }

    @Test
    fun `mid-motion holds fire but claims a wake, then fires when still`() {
        val g = grid()
        run(g, Triple(0, 100, 0))
        // Sum jumps AND differs: a change mid-animation, or a single-frame
        // change that may settle into delivery silence.
        val moving = run(g, Triple(0, 200, 5))
        assertFalse(moving.fired)
        assertTrue(moving.pendingSettle)
        assertEquals(1, moving.movingBlocks)
        // First still look fires — no confirmation wait.
        val still = run(g, Triple(0, 200, 5))
        assertTrue(still.fired)
        assertFalse(still.pendingSettle)
    }

    @Test
    fun `persistent animation goes volatile, never fires, and is reported`() {
        val g = grid()
        run(g, Triple(0, 100, 0))
        var sawVolatile = false
        for (i in 0 until 12) {
            val luma = if (i % 2 == 0) 200 else 100
            val v = run(g, Triple(0, luma, 5))
            assertFalse("run $i must not fire", v.fired)
            if (v.volatileBlocks > 0) sawVolatile = true
        }
        assertTrue("EMA should cross the volatile bar", sawVolatile)
        // While volatile, even a stable differing look neither fires nor
        // claims a wake — the CALLER sees volatileBlocks > 0 and disables
        // skipping, so the screen runs full-cadence OCR instead.
        val v = run(g, Triple(0, 100, 5))
        assertFalse(v.fired)
        assertFalse(v.pendingSettle)
        assertTrue(v.volatileBlocks > 0)
    }

    @Test
    fun `volatile block recovers after sustained stillness and fires again`() {
        val g = grid()
        run(g, Triple(0, 100, 0))
        for (i in 0 until 12) {
            run(g, Triple(0, if (i % 2 == 0) 200 else 100, 5))
        }
        var recovered = false
        for (i in 0 until 12) {
            val v = run(g, Triple(0, 100, 0))
            if (v.volatileBlocks == 0) { recovered = true; break }
        }
        assertTrue("EMA should decay back under the bar", recovered)
        // First still differing look after recovery fires immediately.
        assertTrue(run(g, Triple(0, 100, 5)).fired)
    }

    @Test
    fun `motion sum survives a fully-excluded run`() {
        val g = grid()
        run(g, Triple(0, 100, 0))
        run(g, Triple(0, 200, 5)) // moving + differs → wake claim
        // Block entirely under a box next run (no samples) — state holds.
        val covered = run(g, Triple(1, 80, 0))
        assertFalse(covered.fired)
        // Samples return at the same sum → still + differs → fire.
        assertTrue(run(g, Triple(0, 200, 5)).fired)
    }

    @Test
    fun `reset drops state and reseeds`() {
        val g = grid()
        run(g, Triple(0, 100, 0))
        run(g, Triple(0, 200, 5))
        g.reset()
        val v = run(g, Triple(0, 100, 5)) // seeding look
        assertFalse(v.fired)
        // Next still look fires normally.
        assertTrue(run(g, Triple(0, 100, 5)).fired)
    }

    @Test
    fun `independent blocks do not couple`() {
        val g = grid()
        run(g, Triple(0, 100, 0), Triple(1, 50, 0))
        // Block 1 animates; block 0 carries a settled change.
        val v = run(g, Triple(0, 100, 5), Triple(1, 150, 5))
        assertTrue(v.fired)
        assertEquals(1, v.firedBlocks) // only block 0
        assertEquals(1, v.movingBlocks) // only block 1
    }

    private companion object {
        const val SAMPLES = 10
    }
}
