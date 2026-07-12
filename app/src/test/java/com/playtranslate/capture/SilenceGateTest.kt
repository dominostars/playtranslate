package com.playtranslate.capture

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the ring's silence-collapse contract: audible chunks always pass
 * whole, each contiguous near-silent stretch retains at most its 2 s budget
 * (sample-exact across chunk boundaries), and any audible chunk instantly
 * reopens the gate with a fresh budget for the next stretch.
 */
class SilenceGateTest {

    private val rate = 44_100
    private val chunk = rate / 10 // 100 ms, mirroring the reader loop

    @Test
    fun audibleChunksPassWhole() {
        val g = SilenceGate(rate)
        assertEquals(chunk, g.admit(1_000, chunk))
        // The floor itself counts as sound — silence is strictly below it.
        assertEquals(chunk, g.admit(SilenceGate.PEAK_FLOOR, chunk))
        assertEquals(0L, g.droppedFrames)
    }

    @Test
    fun sustainedQuietNonzeroAudioIsNeverDropped() {
        // The eats-quiet-speech regression (adversarial-review finding): a
        // long stretch of real audio at the lowest representable level —
        // deep in-game/emulator volume attenuation — must pass untouched.
        val g = SilenceGate(rate)
        var admitted = 0L
        repeat(600) { admitted += g.admit(1, chunk) } // 60 s at one LSB
        assertEquals(600L * chunk, admitted)
        assertEquals(0L, g.droppedFrames)
    }

    @Test
    fun silenceRetainsExactlyTheBudget() {
        val g = SilenceGate(rate)
        var admitted = 0
        repeat(40) { admitted += g.admit(0, chunk) } // 4 s of silence
        assertEquals(SilenceGate.MAX_SILENCE_SECONDS * rate, admitted)
        assertEquals(2L * rate, g.droppedFrames)
    }

    @Test
    fun budgetBoundaryIsSampleExact() {
        val g = SilenceGate(rate)
        // Odd chunk sizes that don't divide the budget evenly force a
        // partial admit at the boundary chunk.
        val odd = chunk + 7
        var admitted = 0
        repeat(30) { admitted += g.admit(0, odd) }
        assertEquals(SilenceGate.MAX_SILENCE_SECONDS * rate, admitted)
    }

    @Test
    fun soundReopensInstantlyWithFreshBudget() {
        val g = SilenceGate(rate)
        repeat(40) { g.admit(0, chunk) } // exhaust the first stretch
        // The onset chunk is admitted whole — a voice line is never clipped.
        assertEquals(chunk, g.admit(500, chunk))
        var admitted = 0
        repeat(40) { admitted += g.admit(0, chunk) } // next silent stretch
        assertEquals(SilenceGate.MAX_SILENCE_SECONDS * rate, admitted)
    }

    @Test
    fun onlyExactZeroIsSilence() {
        val g = SilenceGate(rate)
        repeat(40) { g.admit(0, chunk) }
        assertEquals(0, g.admit(0, chunk))
        // One LSB of amplitude is audio, not silence.
        assertEquals(chunk, g.admit(1, chunk))
    }
}
