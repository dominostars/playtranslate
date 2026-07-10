package com.playtranslate.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [StreamKindProbe]'s pure core: the cell detectors ([StreamKindProbe.readCells])
 * and the verdict [StreamKindProbe.Ledger]. The probe is the SOLE stream-kind
 * classifier — there are no runtime backstops — so this suite pins the two
 * properties that keep it honest:
 *
 *  1. **Transform invariance.** The detector matrix runs the same drawn
 *     pattern through the pipelines that broke hue-only detection on paper
 *     (2026-07-10 review): grayscale (bedtime modes), inversion
 *     (accessibility), uniform dim (overlay opacity clamps, field-measured).
 *     The luma parity gap must survive them all.
 *  2. **Evidence discipline.** CLEAN needs sustained proof; a checker only
 *     counts as OURS when its sign flips in lockstep with the commanded
 *     phase swap; failures never launder into silence.
 */
class StreamKindProbeTest {

    // ── Pattern fixtures ──────────────────────────────────────────────────

    private val YELLOW = 0xFFFFFF00.toInt()
    private val BLUE = 0xFF0000FF.toInt()

    /** 6×6 cell pixels as the ProbeView draws them for [swap], row-major,
     *  optionally pushed through a per-pixel transform. */
    private fun pattern(swap: Boolean, transform: (Int) -> Int = { it }): IntArray =
        IntArray(36) { i ->
            val isA = ((i / 6 + i % 6) % 2 == 0) != swap
            transform(if (isA) YELLOW else BLUE)
        }

    private fun gray(pixel: Int): Int {
        val l = StreamKindProbe.lumaOf(pixel)
        return (0xFF shl 24) or (l shl 16) or (l shl 8) or l
    }

    private fun invert(pixel: Int): Int {
        val r = 255 - ((pixel shr 16) and 0xFF)
        val g = 255 - ((pixel shr 8) and 0xFF)
        val b = 255 - (pixel and 0xFF)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun dim(pixel: Int, factor: Float): Int {
        val r = (((pixel shr 16) and 0xFF) * factor).toInt()
        val g = (((pixel shr 8) and 0xFF) * factor).toInt()
        val b = ((pixel and 0xFF) * factor).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    // ── Detector matrix ───────────────────────────────────────────────────

    @Test
    fun pureColors_bothDetectorsFire() {
        val reading = StreamKindProbe.readCells(pattern(swap = false), swap = false)
        assertEquals("every cell hue-correct", 36, reading.hueMatched)
        assertEquals("yellow(236) on even parity vs blue(18)", 218, reading.lumaSeparation)
        // Phase B: hue still perfect against its own phase; luma sign flips.
        val b = StreamKindProbe.readCells(pattern(swap = true), swap = true)
        assertEquals(36, b.hueMatched)
        assertEquals(-218, b.lumaSeparation)
    }

    @Test
    fun wrongPhaseExpectation_hueRejects_lumaSignExposesIt() {
        // A stale composition (old phase) scores zero on hue — and its luma
        // sign is the OPPOSITE of the fresh phase's, which is exactly the
        // information the ledger's flip test consumes.
        val stale = StreamKindProbe.readCells(pattern(swap = false), swap = true)
        assertEquals(0, stale.hueMatched)
        assertEquals(218, stale.lumaSeparation)
    }

    @Test
    fun grayscalePipeline_hueBlind_lumaGapIntact() {
        // Bedtime-mode shape: color stripped to luma before capture. Hue
        // detection is fully blind — the pre-rebuild false-CLEAN generator.
        val reading = StreamKindProbe.readCells(pattern(swap = false, ::gray), swap = false)
        assertEquals("gray cells dominate no hue", 0, reading.hueMatched)
        assertEquals("luma gap survives verbatim", 218, reading.lumaSeparation)
        assertTrue(
            kotlin.math.abs(reading.lumaSeparation) >= StreamKindProbe.LUMA_SEPARATION_MIN
        )
    }

    @Test
    fun inversionPipeline_lumaGapNegated_neverAbsent() {
        // Accessibility inversion: luma mirrors, so the gap NEGATES. The
        // detector must still report a strong checker (sign-agnostic
        // magnitude) — reading this as absence was the bug the sign-agnostic
        // flip test exists to prevent.
        val a = StreamKindProbe.readCells(
            pattern(swap = false) { gray(invert(it)) }, swap = false,
        )
        assertEquals(-218, a.lumaSeparation)
        val b = StreamKindProbe.readCells(
            pattern(swap = true) { gray(invert(it)) }, swap = true,
        )
        assertEquals("alternation survives inversion", 218, b.lumaSeparation)
    }

    @Test
    fun fieldDim_bothDetectorsStillFire() {
        // The Moto G's measured ~84% overlay-opacity clamp (2026-07-10).
        val reading = StreamKindProbe.readCells(
            pattern(swap = false) { dim(it, 0.84f) }, swap = false,
        )
        assertEquals(36, reading.hueMatched)
        assertTrue(
            "clamped gap (${reading.lumaSeparation}) still clears the floor",
            kotlin.math.abs(reading.lumaSeparation) >= StreamKindProbe.LUMA_SEPARATION_MIN,
        )
    }

    @Test
    fun neutralContent_bothDetectorsQuiet() {
        val flat = StreamKindProbe.readCells(IntArray(36) { 0xFF808080.toInt() }, swap = false)
        assertEquals(0, flat.hueMatched)
        assertEquals(0, flat.lumaSeparation)
        // A horizontal luminance edge (top half bright, bottom dark) is NOT
        // a checker: parity means interleave both halves equally.
        val edge = StreamKindProbe.readCells(
            IntArray(36) { i -> if (i < 18) 0xFFF0F0F0.toInt() else 0xFF101010.toInt() },
            swap = false,
        )
        assertEquals(0, edge.lumaSeparation)
    }

    @Test
    fun probeHue_matchesAttenuatedPattern_rejectsContent() {
        // The ×0.84 overlay-opacity clamp was field-measured on the Moto G
        // (2026-07-10, prior palette); these are the clamped yellow/blue.
        val dimYellow = 0xFFD6D60A.toInt()
        val dimBlue = 0xFF0A0AD6.toInt()
        assertTrue(StreamKindProbe.cellHueMatches(true, dimYellow))
        assertTrue(StreamKindProbe.cellHueMatches(false, dimBlue))
        assertFalse(StreamKindProbe.cellHueMatches(false, dimYellow))
        assertFalse(StreamKindProbe.cellHueMatches(true, dimBlue))
        assertFalse(StreamKindProbe.cellHueMatches(true, 0xFFFFFFFF.toInt()))
        assertFalse(StreamKindProbe.cellHueMatches(false, 0xFF808080.toInt()))
        // Deep dim (~×0.17) still classifies on both hues.
        assertTrue(StreamKindProbe.cellHueMatches(true, 0xFF2B2B00.toInt()))
        assertTrue(StreamKindProbe.cellHueMatches(false, 0xFF00002B.toInt()))
    }

    // ── Verdict ledger ────────────────────────────────────────────────────

    private fun ledger() = StreamKindProbe.Ledger()
    private val FOUND = StreamKindProbe.Scan.FOUND
    private val POS = StreamKindProbe.Scan.CHECKER_POS
    private val NEG = StreamKindProbe.Scan.CHECKER_NEG
    private val ABSENT = StreamKindProbe.Scan.ABSENT
    private val SILENT = StreamKindProbe.Scan.NO_FRAMES
    private val FAILED = StreamKindProbe.Scan.FAILED

    @Test
    fun foundSettlesContaminatedImmediately() {
        assertEquals(StreamKind.CONTAMINATED, ledger().observe(FOUND))
    }

    @Test
    fun checkerFlippingWithSwap_isOurs_contaminated() {
        // Grayscale pipeline: hue never fires; our pattern reads as a strong
        // luma checker whose sign flips every round (we swap every round).
        val l = ledger()
        assertNull(l.observe(NEG))
        assertEquals(StreamKind.CONTAMINATED, l.observe(POS))
        // Inverted pipeline: same story, opposite initial sign.
        val inv = ledger()
        assertNull(inv.observe(POS))
        assertEquals(StreamKind.CONTAMINATED, inv.observe(NEG))
    }

    @Test
    fun staticChecker_sameSignAcrossSwaps_isEnvironment_clean() {
        // Game content that happens to checker at the probe rect cannot
        // follow our swaps: constant sign = our pattern provably not there.
        val l = ledger()
        assertNull(l.observe(POS))
        assertNull(l.observe(POS))
        assertEquals(StreamKind.CLEAN, l.observe(POS))
    }

    @Test
    fun flipConfirmedEvenAfterAbsentRound() {
        val l = ledger()
        assertNull(l.observe(ABSENT))
        assertNull(l.observe(POS))
        assertEquals(
            "flip across consecutive checker rounds settles regardless of history",
            StreamKind.CONTAMINATED, l.observe(NEG),
        )
    }

    @Test
    fun loneCheckerSighting_neutralizesAbsenceRun() {
        // One checker sighting might be ours becoming visible — it must not
        // let a prior ABSENT run keep counting toward CLEAN.
        val l = ledger()
        assertNull(l.observe(ABSENT))
        assertNull(l.observe(POS))
        assertNull(l.observe(ABSENT))
        assertEquals(StreamKind.CLEAN, l.observe(ABSENT))
    }

    @Test
    fun nonConsecutiveCheckers_neverConfirm_exhaustToUnknown() {
        val l = ledger()
        assertNull(l.observe(POS))
        assertNull(l.observe(ABSENT))
        assertNull(l.observe(NEG))
        assertNull(l.observe(ABSENT))
        assertNull(l.observe(POS))
        assertEquals(
            "checker sightings separated by other evidence are not a flip",
            StreamKind.UNKNOWN, l.observe(ABSENT),
        )
    }

    @Test
    fun absentPairSettlesClean() {
        val l = ledger()
        assertNull(l.observe(ABSENT))
        assertEquals(StreamKind.CLEAN, l.observe(ABSENT))
    }

    @Test
    fun singleSilentPhase_canNeverSelectClean() {
        val l = ledger()
        assertNull(l.observe(SILENT))
        assertNull(l.observe(SILENT))
        assertEquals(StreamKind.CLEAN, l.observe(SILENT))
    }

    @Test
    fun interruptedSilence_mustReEarnItsRun() {
        val l = ledger()
        assertNull(l.observe(SILENT))
        assertNull(l.observe(SILENT))
        assertNull(l.observe(ABSENT)) // a delivery arrived — streak resets
        assertNull(l.observe(SILENT))
        assertNull(l.observe(SILENT))
        assertEquals(
            "a full silent run on the final round still settles CLEAN",
            StreamKind.CLEAN, l.observe(SILENT),
        )
    }

    @Test
    fun checkerSightingResetsSilenceToo() {
        val l = ledger()
        assertNull(l.observe(SILENT))
        assertNull(l.observe(SILENT))
        assertNull(l.observe(POS)) // a readable frame arrived — not silence
        assertNull(l.observe(SILENT))
        assertNull(l.observe(SILENT))
        assertEquals(StreamKind.CLEAN, l.observe(SILENT))
    }

    @Test
    fun mixedEvidence_exhaustsUncachedToUnknown() {
        val l = ledger()
        assertNull(l.observe(SILENT))
        assertNull(l.observe(ABSENT))
        assertNull(l.observe(SILENT))
        assertNull(l.observe(ABSENT))
        assertNull(l.observe(SILENT))
        assertEquals(StreamKind.UNKNOWN, l.observe(ABSENT))
    }

    @Test
    fun captureFailure_abortsImmediately_neverClean() {
        assertEquals(StreamKind.UNKNOWN, ledger().observe(FAILED))
        val l = ledger()
        assertNull(l.observe(SILENT))
        assertNull(l.observe(SILENT))
        assertEquals(StreamKind.UNKNOWN, l.observe(FAILED))
    }
}
