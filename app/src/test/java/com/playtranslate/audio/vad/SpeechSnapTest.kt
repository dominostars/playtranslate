package com.playtranslate.audio.vad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SpeechSnap]'s pure logic: hysteresis segmentation with merge/drop/pad,
 * and the anchored pick. Frame size 32 ms to match [SileroVad.FRAME_MS];
 * probability arrays are built from run-length specs for readability.
 */
class SpeechSnapTest {

    private val frameMs = 32L

    /** probs from (count, value) runs. */
    private fun probs(vararg runs: Pair<Int, Float>): FloatArray {
        val out = ArrayList<Float>()
        for ((n, v) in runs) repeat(n) { out.add(v) }
        return out.toFloatArray()
    }

    private fun totalMs(p: FloatArray) = p.size * frameMs

    @Test
    fun singleClearLineIsOneSegmentWithPadding() {
        // 1 s quiet, 2 s speech, 1 s quiet.
        val p = probs(31 to 0.05f, 63 to 0.9f, 31 to 0.05f)
        val segs = SpeechSnap.segments(p, frameMs, totalMs(p))
        assertEquals(1, segs.size)
        // Pad extends both edges by PAD_MS.
        assertEquals(31 * frameMs - SpeechSnap.PAD_MS, segs[0].startMs)
        assertEquals((31 + 63) * frameMs + SpeechSnap.PAD_MS, segs[0].endMs)
    }

    @Test
    fun shortBlipIsDropped() {
        // A 3-frame (96 ms) stinger: below MIN_SPEECH_MS.
        val p = probs(31 to 0.05f, 3 to 0.95f, 31 to 0.05f)
        assertTrue(SpeechSnap.segments(p, frameMs, totalMs(p)).isEmpty())
    }

    @Test
    fun breathPauseInsideOneLineMerges() {
        // speech - 200 ms dip - speech: gap < MIN_GAP_MS ⇒ one segment.
        val p = probs(31 to 0.9f, 6 to 0.1f, 31 to 0.9f)
        assertEquals(1, SpeechSnap.segments(p, frameMs, totalMs(p)).size)
    }

    @Test
    fun realGapBetweenLinesDoesNotMerge() {
        // 1 s of quiet between lines > MIN_GAP_MS ⇒ two segments.
        val p = probs(31 to 0.9f, 31 to 0.1f, 31 to 0.9f)
        assertEquals(2, SpeechSnap.segments(p, frameMs, totalMs(p)).size)
    }

    @Test
    fun hysteresisHoldsThroughMidBandProbs() {
        // Enter at 0.9, then hover between EXIT and ENTER — stays speech.
        val p = probs(15 to 0.9f, 31 to 0.4f, 15 to 0.9f, 15 to 0.05f)
        assertEquals(1, SpeechSnap.segments(p, frameMs, totalMs(p)).size)
    }

    @Test
    fun trailingSpeechClosesAtTotal() {
        val p = probs(31 to 0.05f, 31 to 0.9f)
        val segs = SpeechSnap.segments(p, frameMs, totalMs(p))
        assertEquals(totalMs(p), segs.last().endMs)
    }

    // ── pick ──

    private fun seg(a: Long, b: Long) = SpeechSnap.Segment(a, b)

    @Test
    fun overlapBeatsEverything() {
        val s = listOf(seg(0, 2_000), seg(4_000, 8_000), seg(10_000, 12_000))
        assertEquals(seg(4_000, 8_000), SpeechSnap.pick(s, anchorMs = 5_000))
    }

    @Test
    fun lastLineEndingBeforeAnchorWins() {
        val s = listOf(seg(0, 2_000), seg(5_000, 8_000))
        assertEquals(seg(5_000, 8_000), SpeechSnap.pick(s, anchorMs = 20_000))
    }

    @Test
    fun earlierSegmentBeatsLaterAtDoubleCost() {
        // Before: 3 s away. After: 2 s away but doubled ⇒ 4 s. Before wins.
        val s = listOf(seg(0, 7_000), seg(12_000, 15_000))
        assertEquals(seg(0, 7_000), SpeechSnap.pick(s, anchorMs = 10_000))
        // After: 1 s away, doubled ⇒ 2 s < 3 s before. After wins.
        assertEquals(seg(11_000, 15_000), SpeechSnap.pick(listOf(seg(0, 7_000), seg(11_000, 15_000)), 10_000))
    }

    @Test
    fun emptySegmentsPickNull() {
        assertNull(SpeechSnap.pick(emptyList(), 1_000))
        assertNull(SpeechSnap.snap(emptyList(), 1_000))
    }

    @Test
    fun overlongMergeIsEndAlignedAndCapped() {
        val s = listOf(seg(0, 40_000))
        val snapped = SpeechSnap.snap(s, anchorMs = 39_000)!!
        assertEquals(40_000L, snapped.endMs)
        assertEquals(40_000L - SpeechSnap.MAX_LINE_MS, snapped.startMs)
    }

    // ── merge (background-scan accumulation) ──

    @Test
    fun mergeCoalescesTouchingBlockBoundarySegments() {
        // One line split by a block boundary at 30 s: pads clamped, pieces
        // touch exactly. Merged view = one segment.
        val a = listOf(seg(28_000, 30_000))
        val b = listOf(seg(30_000, 31_500))
        assertEquals(listOf(seg(28_000, 31_500)), SpeechSnap.merge(a, b))
    }

    @Test
    fun mergeCoalescesOverlapAndKeepsDisjoint() {
        val a = listOf(seg(0, 2_000), seg(10_000, 12_000))
        val b = listOf(seg(1_500, 3_000), seg(20_000, 21_000))
        assertEquals(
            listOf(seg(0, 3_000), seg(10_000, 12_000), seg(20_000, 21_000)),
            SpeechSnap.merge(a, b),
        )
    }

    @Test
    fun mergeAbsorbsContainedSegmentsAndHandlesEmpty() {
        val a = listOf(seg(5_000, 9_000))
        val b = listOf(seg(6_000, 7_000))
        assertEquals(listOf(seg(5_000, 9_000)), SpeechSnap.merge(a, b))
        assertEquals(a, SpeechSnap.merge(a, emptyList()))
        assertEquals(a, SpeechSnap.merge(emptyList(), a))
        assertEquals(emptyList<SpeechSnap.Segment>(), SpeechSnap.merge(emptyList(), emptyList()))
    }
}
