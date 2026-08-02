package com.playtranslate.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [RingClock] mapping under the gap generators the recorder feeds it:
 * continuous writes, silence-drop splices, pause/resume, ring aging,
 * and the drift backstop. Uses a 1 kHz "sample rate" so frames and ms
 * read 1:1 in the assertions (capacity 60_000 frames = 60 s ring).
 */
class RingClockTest {

    private val rate = 1_000
    private val capacity = 60_000

    private fun clock() = RingClock(rate, capacity)

    /** Write [chunks] consecutive 100 ms chunks starting at frame position
     *  [startFrame], the first chunk's read returning at wall [startWallMs] +
     *  100. Returns the frame position after the writes. */
    private fun RingClock.writeRun(startFrame: Long, startWallMs: Long, chunks: Int): Long {
        var frame = startFrame
        var wallEnd = startWallMs + 100
        repeat(chunks) {
            beforeWrite(frame, 100, wallEnd)
            frame += 100
            wallEnd += 100
        }
        return frame
    }

    @Test
    fun continuousRunInterpolatesByFrame() {
        val c = clock()
        c.markGap()
        c.writeRun(startFrame = 0, startWallMs = 10_000, chunks = 50) // 5 s of audio
        assertEquals(0L, c.frameFor(10_000))
        assertEquals(2_345L, c.frameFor(12_345))
        // Past the last write: projects beyond the data; caller clamps.
        assertEquals(7_000L, c.frameFor(17_000))
    }

    @Test
    fun beforeTrackedAudioIsNull() {
        val c = clock()
        c.markGap()
        c.writeRun(startFrame = 0, startWallMs = 10_000, chunks = 10)
        assertNull(c.frameFor(9_999))
        assertNull(clock().frameFor(10_000)) // no anchors at all
    }

    @Test
    fun silenceDropSplicesAndWallTimeInGapClampsToSeam() {
        val c = clock()
        c.markGap()
        // 2 s of audio, then 8 s of wall time dropped by the gate, then more
        // audio: ring frames 2000.. resume at wall 20_000.
        var frame = c.writeRun(startFrame = 0, startWallMs = 10_000, chunks = 20)
        c.markGap()
        frame = c.writeRun(startFrame = frame, startWallMs = 20_000, chunks = 20)
        // Before the gap: 1:1.
        assertEquals(1_500L, c.frameFor(11_500))
        // Inside the gap: clamps to the splice seam (frame 2000).
        assertEquals(2_000L, c.frameFor(15_000))
        // After the gap: resumes at the seam.
        assertEquals(2_000L, c.frameFor(20_000))
        assertEquals(3_100L, c.frameFor(21_100))
    }

    @Test
    fun pauseResumeReanchors() {
        val c = clock()
        c.markGap()
        val frame = c.writeRun(startFrame = 0, startWallMs = 0, chunks = 10)
        // Reader stopped (card flow) for five minutes; ring kept.
        c.markGap()
        c.writeRun(startFrame = frame, startWallMs = 300_000, chunks = 10)
        assertEquals(500L, c.frameFor(500))
        assertEquals(1_000L, c.frameFor(150_000)) // in the pause: seam
        assertEquals(1_500L, c.frameFor(300_500))
    }

    @Test
    fun driftBackstopReanchorsWithoutMarkGap() {
        val c = clock()
        c.markGap()
        var frame = c.writeRun(startFrame = 0, startWallMs = 0, chunks = 10)
        // Un-marked 5 s stall (no markGap call): the next chunk's measured
        // wall start disagrees with the projection by 5 s > threshold.
        c.beforeWrite(frame, 100, 6_100)
        frame += 100
        // The re-anchor pins frame 1000 to wall 6_000.
        assertEquals(1_050L, c.frameFor(6_050))
        // Wall times in the stall clamp to the seam.
        assertEquals(1_000L, c.frameFor(3_000))
    }

    @Test
    fun smallJitterDoesNotReanchor() {
        val c = clock()
        c.markGap()
        var frame = c.writeRun(startFrame = 0, startWallMs = 0, chunks = 10)
        // 300 ms of read-return jitter: within the backstop threshold, so the
        // original anchor still governs and mapping stays frame-based.
        c.beforeWrite(frame, 100, 1_400)
        frame += 100
        assertEquals(1_050L, c.frameFor(1_050))
    }

    @Test
    fun agedOutAnchorsArePrunedButSpanningSegmentSurvives() {
        val c = clock()
        c.markGap()
        // One continuous 120 s run on a 60 s ring: the single anchor's
        // segment spans into the window and must survive pruning.
        c.writeRun(startFrame = 0, startWallMs = 0, chunks = 1_200)
        assertEquals(90_000L, c.frameFor(90_000))
        // Now a splice + 60 s more: the first anchor's segment is fully
        // overwritten once the second segment covers the whole ring.
        c.markGap()
        c.writeRun(startFrame = 120_000, startWallMs = 200_000, chunks = 700)
        assertNull(c.frameFor(50_000))
        assertEquals(120_000L + 30_000, c.frameFor(230_000))
    }

    @Test
    fun resetForgetsEverything() {
        val c = clock()
        c.markGap()
        c.writeRun(startFrame = 0, startWallMs = 0, chunks = 10)
        c.reset()
        assertNull(c.frameFor(500))
        // And the first write after reset re-anchors at frame 0.
        c.writeRun(startFrame = 0, startWallMs = 9_000, chunks = 10)
        assertEquals(300L, c.frameFor(9_300))
    }
}
