package com.playtranslate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [OverlayToolkit.echoesTranslation] — the clean-stream contamination
 * tripwire's predicate. The positive cases are VERBATIM field data from the
 * 2026-07-10 Moto G false-CLEAN session: OCR reads of our own rendered boxes
 * on a contaminated stream, garbled and merged with the text underneath.
 * The first tripwire version compared exact-ish and missed all of them.
 */
class EchoDetectionTest {

    @Test
    fun fieldGarble_mergedSourceAndTranslation_detected() {
        // Box translated "確認しました" → "I understand"; the contaminated
        // read merged both, garbling the leading I.
        assertTrue(OverlayToolkit.echoesTranslation("確認しましたhunderstandhd", "I understand"))
        // Second read of the same region, differently garbled.
        assertTrue(OverlayToolkit.echoesTranslation("催認しました/|understand", "I understand"))
        // "利用しない" → "I don't use", read with the original concatenated.
        assertTrue(OverlayToolkit.echoesTranslation("利用しないAdontuse>:>利用しない/1dontuse>", "I don't use"))
    }

    @Test
    fun ordinaryGameText_notEcho() {
        // The original Japanese under a box shares almost no characters with
        // its English translation — the true-clean-stream steady state.
        assertFalse(OverlayToolkit.echoesTranslation("確認しました", "I understand"))
        assertFalse(
            OverlayToolkit.echoesTranslation(
                "海外からのアクセスについて", "About access from overseas",
            )
        )
    }

    @Test
    fun shortTranslations_neverDiscriminate() {
        // Below the length floor nothing can be concluded — numbers, "OK".
        assertFalse(OverlayToolkit.echoesTranslation("OK", "OK"))
        assertFalse(OverlayToolkit.echoesTranslation("12,300", "12300"))
    }

    // ── StreamKindProbe hue classifier (same field session) ──────────────

    @Test
    fun probeHue_matchesAttenuatedPattern_rejectsContent() {
        // Verbatim mirror values from the 2026-07-10 Moto G session: the
        // pattern composited at ~84% intensity through the overlay-opacity
        // clamp. Absolute-tolerance matching flunked these; hue must not.
        val dimMagenta = 0xFFD60AD6.toInt()
        val dimGreen = 0xFF0AD60A.toInt()
        assertTrue(com.playtranslate.capture.StreamKindProbe.cellHueMatches(true, dimMagenta))
        assertTrue(com.playtranslate.capture.StreamKindProbe.cellHueMatches(false, dimGreen))
        // Phase-swapped expectations must fail — the checker's alternation is
        // what game content can't fake.
        assertFalse(com.playtranslate.capture.StreamKindProbe.cellHueMatches(false, dimMagenta))
        assertFalse(com.playtranslate.capture.StreamKindProbe.cellHueMatches(true, dimGreen))
        // Neutral content (white page, gray UI) matches neither hue.
        assertFalse(com.playtranslate.capture.StreamKindProbe.cellHueMatches(true, 0xFFFFFFFF.toInt()))
        assertFalse(com.playtranslate.capture.StreamKindProbe.cellHueMatches(false, 0xFF808080.toInt()))
        // Deep dim (~×0.2) still classifies.
        assertTrue(com.playtranslate.capture.StreamKindProbe.cellHueMatches(true, 0xFF330033.toInt()))
    }

    // ── StreamKindProbe verdict ledger (adversarial-review regression) ────

    private fun ledger() = com.playtranslate.capture.StreamKindProbe.Ledger()
    private val FOUND = com.playtranslate.capture.StreamKindProbe.Scan.FOUND
    private val ABSENT = com.playtranslate.capture.StreamKindProbe.Scan.ABSENT
    private val SILENT = com.playtranslate.capture.StreamKindProbe.Scan.NO_FRAMES

    @Test
    fun singleSilentPhase_canNeverSelectClean() {
        // The exact regression the adversarial review asked to pin: silence
        // must be sustained across repeated forced commits before it reads
        // as a task mirror.
        val l = ledger()
        org.junit.Assert.assertNull(l.observe(SILENT))
        org.junit.Assert.assertNull(l.observe(SILENT))
        assertEquals(com.playtranslate.capture.StreamKind.CLEAN, l.observe(SILENT))
    }

    @Test
    fun foundSettlesContaminatedImmediately() {
        assertEquals(com.playtranslate.capture.StreamKind.CONTAMINATED, ledger().observe(FOUND))
    }

    @Test
    fun absentPairSettlesClean() {
        val l = ledger()
        org.junit.Assert.assertNull(l.observe(ABSENT))
        assertEquals(com.playtranslate.capture.StreamKind.CLEAN, l.observe(ABSENT))
    }

    @Test
    fun interruptedSilence_mustReEarnItsRun() {
        val l = ledger()
        org.junit.Assert.assertNull(l.observe(SILENT))
        org.junit.Assert.assertNull(l.observe(SILENT))
        org.junit.Assert.assertNull(l.observe(ABSENT)) // a delivery arrived — streak resets
        org.junit.Assert.assertNull(l.observe(SILENT))
        org.junit.Assert.assertNull(l.observe(SILENT))
        assertEquals(
            "a full silent run on the final round still settles CLEAN",
            com.playtranslate.capture.StreamKind.CLEAN, l.observe(SILENT),
        )
    }

    @Test
    fun mixedEvidence_exhaustsUncachedToUnknown() {
        val l = ledger()
        org.junit.Assert.assertNull(l.observe(SILENT))
        org.junit.Assert.assertNull(l.observe(ABSENT))
        org.junit.Assert.assertNull(l.observe(SILENT))
        org.junit.Assert.assertNull(l.observe(ABSENT))
        org.junit.Assert.assertNull(l.observe(SILENT))
        assertEquals(
            "no coherent streak by the round cap is not a measurement — " +
                "UNKNOWN routes pinhole and re-probes next start",
            com.playtranslate.capture.StreamKind.UNKNOWN, l.observe(ABSENT),
        )
    }

    @Test
    fun captureFailure_abortsImmediately_neverClean() {
        val FAILED = com.playtranslate.capture.StreamKindProbe.Scan.FAILED
        // A dead capture layer aborts on sight...
        assertEquals(com.playtranslate.capture.StreamKind.UNKNOWN, ledger().observe(FAILED))
        // ...and can never be laundered into CLEAN by prior silent rounds —
        // the exact fail-open the adversarial review flagged (nulls counted
        // as task-mirror silence).
        val l = ledger()
        org.junit.Assert.assertNull(l.observe(SILENT))
        org.junit.Assert.assertNull(l.observe(SILENT))
        assertEquals(com.playtranslate.capture.StreamKind.UNKNOWN, l.observe(FAILED))
    }
}
