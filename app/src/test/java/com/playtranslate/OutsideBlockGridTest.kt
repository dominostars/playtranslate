package com.playtranslate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [OutsideBlockGrid] — the settle/volatility state machine the
 * outside gate's firing decision runs through (audit A3). Runs are driven
 * with explicit block feeds; block 0 is the subject throughout.
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
    fun `settled change pends once then fires on the confirming run`() {
        val g = grid()
        run(g, Triple(0, 100, 0)) // seed
        val pendRun = run(g, Triple(0, 100, 5))
        assertFalse(pendRun.fired)
        assertTrue(pendRun.pendingSettle)
        val fireRun = run(g, Triple(0, 100, 5))
        assertTrue(fireRun.fired)
        assertEquals(1, fireRun.firedBlocks)
        assertFalse(fireRun.pendingSettle)
    }

    @Test
    fun `first look after seeding never fires even when differing`() {
        val g = grid()
        val v = run(g, Triple(0, 100, 5))
        assertFalse(v.fired)
        assertFalse(v.pendingSettle)
    }

    @Test
    fun `transient change is forgotten without firing`() {
        val g = grid()
        run(g, Triple(0, 100, 0))
        run(g, Triple(0, 100, 5)) // pends
        val v = run(g, Triple(0, 100, 0)) // reverted
        assertFalse(v.fired)
        assertFalse(v.pendingSettle)
        // And it must re-earn the two-look sequence afterwards.
        val pendAgain = run(g, Triple(0, 100, 5))
        assertFalse(pendAgain.fired)
        assertTrue(pendAgain.pendingSettle)
    }

    @Test
    fun `mid-transition waits for stillness but claims a wake`() {
        val g = grid()
        run(g, Triple(0, 100, 0))
        // Sum jumps AND differs: a change mid-animation (or a single-frame
        // change followed by silence) — no pend yet, but a follow-up wake.
        val moving = run(g, Triple(0, 200, 5))
        assertFalse(moving.fired)
        assertTrue(moving.pendingSettle)
        assertEquals(1, moving.movingBlocks)
        // Stillness look #1: pends.
        val still = run(g, Triple(0, 200, 5))
        assertFalse(still.fired)
        assertTrue(still.pendingSettle)
        // Stillness look #2: fires.
        assertTrue(run(g, Triple(0, 200, 5)).fired)
    }

    @Test
    fun `persistent animation goes volatile and never fires`() {
        val g = grid()
        run(g, Triple(0, 100, 0))
        var sawVolatile = false
        for (i in 0 until 12) {
            val luma = if (i % 2 == 0) 200 else 100
            val v = run(g, Triple(0, luma, 5))
            assertFalse("run $i must not fire", v.fired)
            if (v.volatileBlocks > 0) sawVolatile = true
        }
        assertTrue("EMA should have crossed the volatile bar", sawVolatile)
        // While volatile, even a stable differing look must not pend.
        val v = run(g, Triple(0, 100, 5))
        assertFalse(v.fired)
        assertFalse(v.pendingSettle)
    }

    @Test
    fun `volatile block recovers after sustained stillness`() {
        val g = grid()
        run(g, Triple(0, 100, 0))
        for (i in 0 until 12) {
            run(g, Triple(0, if (i % 2 == 0) 200 else 100, 5))
        }
        // Quiet, unchanged runs decay the EMA below the volatile bar.
        var recovered = false
        for (i in 0 until 12) {
            val v = run(g, Triple(0, 100, 0))
            if (v.volatileBlocks == 0) { recovered = true; break }
        }
        assertTrue("EMA should decay back under the bar", recovered)
        // A real settled change now goes pend → fire normally.
        assertFalse(run(g, Triple(0, 100, 5)).fired)
        assertTrue(run(g, Triple(0, 100, 5)).fired)
    }

    @Test
    fun `pending claim survives a fully-excluded run`() {
        val g = grid()
        run(g, Triple(0, 100, 0))
        run(g, Triple(0, 100, 5)) // pends
        // Next run the block is entirely under a box (no samples) — the
        // wake claim must hold so the confirming look still happens.
        val covered = run(g, Triple(1, 80, 0))
        assertFalse(covered.fired)
        assertTrue(covered.pendingSettle)
        // Samples return, still differing and still (sum unchanged) → fire.
        assertTrue(run(g, Triple(0, 100, 5)).fired)
    }

    @Test
    fun `reset drops pending state and reseeds`() {
        val g = grid()
        run(g, Triple(0, 100, 0))
        run(g, Triple(0, 100, 5)) // pends
        g.reset()
        val v = run(g, Triple(0, 100, 5)) // seeding look
        assertFalse(v.fired)
        assertFalse(v.pendingSettle)
    }

    @Test
    fun `independent blocks do not couple`() {
        val g = grid()
        run(g, Triple(0, 100, 0), Triple(1, 50, 0))
        // Block 1 animates; block 0 has a settled change.
        run(g, Triple(0, 100, 5), Triple(1, 150, 5))
        val v = run(g, Triple(0, 100, 5), Triple(1, 50, 5))
        assertTrue(v.fired)
        assertEquals(1, v.firedBlocks) // only block 0
    }

    private companion object {
        const val SAMPLES = 10
    }
}
