package com.playtranslate

import android.graphics.Rect
import com.playtranslate.language.TextAlignment
import com.playtranslate.language.TextOrientation
import com.playtranslate.ui.TextBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for [ScanlineReconciler] — the text-space single-cycle verdict
 * machine for [ReconcilerLiveMode] ("Level 0"). Ported from the
 * `scanlines` branch, extended with [ScanlineReconciler.Region.replacesBox]
 * assertions (the field [TypewriterGate] keys its CHANGED-only scoping on).
 *
 * The reconciler holds no cross-cycle state, so every case is a single
 * `reconcile(groups, boxes)` call: fresh OCR groups plus the previously-displayed
 * boxes in, verdicts out. Translation is first-read (no confirmation gate), a
 * vanished region is removed on the first empty read (no grace), and a blank
 * prior translation is retried — exactly the four verdicts the mode applies.
 *
 * Runs under Robolectric so [android.graphics.Rect] geometry is available on
 * the JVM (same convention as [ClassificationTest]).
 */
@RunWith(RobolectricTestRunner::class)
class ScanlineReconcilerTest {

    private fun box(
        bounds: Rect,
        sourceText: String,
        translatedText: String = "T",
        lineCount: Int = 1,
        orientation: TextOrientation = TextOrientation.HORIZONTAL,
    ) = TextBox(
        translatedText = translatedText,
        bounds = bounds,
        sourceText = sourceText,
        lineCount = lineCount,
        orientation = orientation,
    )

    private fun grp(
        text: String,
        bounds: Rect,
        lineCount: Int = 1,
        orientation: TextOrientation = TextOrientation.HORIZONTAL,
        alignment: TextAlignment = TextAlignment.LEFT,
    ) = OcrManager.OcrGroup(
        text = text,
        bounds = bounds,
        orientation = orientation,
        alignment = alignment,
        lines = List(lineCount) { OcrManager.LineBox(text = text, bounds = bounds, groupIndex = 0) },
    )

    // ── Region fuzz: BOTH tolerance layers on a stable page ──────────────

    /**
     * The load-bearing fuzziness test. A static page is re-read with the two
     * perturbations OCR inflicts every frame at once — a ±10px bounds jitter on
     * every group (region fuzz: [ScanlineReconciler] pairs by geometry, not rect
     * equality) and one swapped character in one group (character fuzz:
     * [OverlayToolkit.isSignificantChange] tolerates a glyph or two). Neither may
     * dislodge a box: both must KEEP, and nothing may translate.
     *
     * The ±10px jitter is above REPOSITION_HYSTERESIS_PX (5), so the kept boxes
     * also carry the group's fresh bounds — they TRACK the drift (translation
     * preserved) rather than churn REMOVE/NEW, and are still counted unchanged.
     */
    @Test
    fun stablePage_regionAndCharacterJitter_allKeep_nothingTranslates() {
        val r0 = Rect(0, 0, 240, 50)
        val r1 = Rect(0, 120, 200, 170)
        val boxes = listOf(box(r0, "Continue"), box(r1, "Settings"))

        val jittered = listOf(
            grp("Continue", Rect(9, -8, 249, 42)),  // ±10px region jitter, same text
            grp("Sattings", Rect(-7, 130, 193, 180)), // ±10px jitter + one swapped char (e→a)
        )

        val v = ScanlineReconciler.reconcile(jittered, boxes)
        assertEquals("both boxes survive region + character jitter", 2, v.unchanged)
        assertTrue("nothing translates on a stable page", v.toTranslate.isEmpty())
        assertTrue("nothing removed", v.removals.isEmpty())
        // ±10px jitter > REPOSITION_HYSTERESIS_PX, so both kept boxes are
        // re-emitted onto their group's fresh bounds (translation preserved).
        assertEquals("both kept as unchanged", 2, v.keptBoxes.size)
        assertEquals("both repositioned onto the jittered bounds", 2, v.repositioned)
        assertEquals(
            "translations preserved through the reposition",
            listOf("T", "T"), v.keptBoxes.map { it.translatedText },
        )
        assertEquals(
            "each kept box carries its group's fresh bounds",
            jittered.map { it.bounds }, v.keptBoxes.map { it.bounds },
        )
    }

    // ── Repositioning: moving text tracks; static jitter stays put ───────

    /**
     * Same text, but the region moved more than REPOSITION_HYSTERESIS_PX (a
     * scroll/pan). The box is KEPT — translation preserved, no re-translate —
     * but re-emitted onto the group's fresh bounds so the overlay tracks it, and
     * the drift is tallied in [ScanlineReconciler.Verdicts.repositioned].
     */
    @Test
    fun sameText_boundsDriftedBeyondHysteresis_repositionsKeptBox() {
        val r = Rect(0, 0, 200, 50)
        val moved = Rect(10, 10, 210, 60) // +10px on every edge (> 5px hysteresis)
        val displayed = box(r, "Scroll", translatedText = "T")

        val v = ScanlineReconciler.reconcile(listOf(grp("Scroll", moved)), listOf(displayed))
        assertEquals("a move still counts as unchanged", 1, v.unchanged)
        assertEquals("the drift is repositioned", 1, v.repositioned)
        assertTrue("no re-translation for a mere move", v.toTranslate.isEmpty())
        assertTrue("nothing removed", v.removals.isEmpty())
        assertEquals("one kept box", 1, v.keptBoxes.size)
        assertEquals("kept box carries the NEW bounds", moved, v.keptBoxes[0].bounds)
        assertEquals("translation preserved", "T", v.keptBoxes[0].translatedText)
    }

    /**
     * Same text nudged within REPOSITION_HYSTERESIS_PX: pure OCR jitter, so the
     * box passes through VERBATIM (old bounds) and nothing is repositioned —
     * static text must not shiver.
     */
    @Test
    fun sameText_boundsWithinHysteresis_keepsVerbatim() {
        val r = Rect(0, 0, 200, 50)
        val nudged = Rect(3, 3, 203, 53) // +3px on every edge (<= 5px hysteresis)
        val displayed = box(r, "Static", translatedText = "T")

        val v = ScanlineReconciler.reconcile(listOf(grp("Static", nudged)), listOf(displayed))
        assertEquals(1, v.unchanged)
        assertEquals("sub-hysteresis jitter does not reposition", 0, v.repositioned)
        assertEquals(
            "passes through verbatim — same box, original bounds",
            listOf(displayed), v.keptBoxes,
        )
        assertEquals("bounds unchanged (verbatim)", r, v.keptBoxes[0].bounds)
        assertTrue(v.toTranslate.isEmpty())
    }

    // ── New text translates on the first read ────────────────────────────

    @Test
    fun newText_translatesOnFirstRead() {
        val r = Rect(0, 0, 200, 50)

        val v = ScanlineReconciler.reconcile(listOf(grp("Hello", r)), emptyList())
        assertEquals("a new region translates immediately, no confirmation gate", 1, v.toTranslate.size)
        assertEquals("Hello", v.toTranslate[0].text)
        assertEquals(1, v.added)
        assertEquals("NEW carries no replaced box — the hold must never touch it",
            null, v.toTranslate[0].replacesBox)
        assertTrue(v.keptBoxes.isEmpty())
        assertTrue(v.removals.isEmpty())
    }

    // ── Changed text at the same region ──────────────────────────────────

    @Test
    fun changedText_sameRegion_dropsOldBox_translatesNewOnFirstRead() {
        val r = Rect(0, 0, 200, 50)
        val old = box(r, "Alpha", translatedText = "tA")

        val v = ScanlineReconciler.reconcile(listOf(grp("Bravo", r)), listOf(old))
        assertEquals("changed verdict", 1, v.changed)
        assertEquals("the new text translates on the first (and only) read", 1, v.toTranslate.size)
        assertEquals("Bravo", v.toTranslate[0].text)
        assertEquals("CHANGED carries the box it replaces (the hold's scope key)",
            old, v.toTranslate[0].replacesBox)
        assertFalse("the stale box is dropped, not kept", v.keptBoxes.contains(old))
        assertTrue("a paired-but-changed box is dropped, not put in removals", v.removals.isEmpty())
    }

    // ── Vanished region removed on the first empty read ──────────────────

    @Test
    fun vanishedRegion_removedOnFirstEmptyRead_noGrace() {
        val r = Rect(0, 0, 200, 50)
        val displayed = box(r, "Gone", translatedText = "G")

        val v = ScanlineReconciler.reconcile(emptyList(), listOf(displayed))
        assertEquals("removed on the first empty read — no grace counter", listOf(displayed), v.removals)
        assertEquals(1, v.missing)
        assertTrue("not kept", v.keptBoxes.isEmpty())
        assertTrue(v.toTranslate.isEmpty())
    }

    // ── Failed-translation retry ─────────────────────────────────────────

    @Test
    fun emptyTranslation_sameTextReRead_retranslates() {
        val r = Rect(0, 0, 200, 50)
        // A prior cycle translated this region but the translator returned
        // blank — the box persists with an empty translatedText.
        val failed = box(r, "Retry", translatedText = "")

        val v = ScanlineReconciler.reconcile(listOf(grp("Retry", r)), listOf(failed))
        assertEquals("a blank prior translation is retried", 1, v.toTranslate.size)
        assertEquals("Retry", v.toTranslate[0].text)
        assertEquals("a retry also names its box — the hold passes retries through",
            failed, v.toTranslate[0].replacesBox)
        assertFalse("the blank box is not passed through as kept", v.keptBoxes.contains(failed))
        assertEquals("it did not pass through, so nothing is unchanged", 0, v.unchanged)
        assertTrue("not removed — the region is still on screen", v.removals.isEmpty())
    }

    // ── Pairing does not cross-claim ─────────────────────────────────────

    @Test
    fun twoAdjacentRegions_oneChanges_otherKept_noCrossClaim() {
        val r0 = Rect(0, 0, 200, 50)
        val r1 = Rect(0, 60, 200, 110) // adjacent — the block predicate would let
        //                                these grid-group, so best-overlap greed
        //                                is what must keep the pairing 1:1.
        val b0 = box(r0, "Alpha", translatedText = "tA")
        val b1 = box(r1, "Beta", translatedText = "tB")

        val v = ScanlineReconciler.reconcile(listOf(grp("Alpha", r0), grp("Gamma", r1)), listOf(b0, b1))
        assertEquals("the unchanged region is kept, not cross-claimed", listOf(b0), v.keptBoxes)
        assertEquals("only the changed region retranslates", 1, v.toTranslate.size)
        assertEquals("Gamma", v.toTranslate[0].text)
        assertEquals(1, v.unchanged)
        assertEquals(1, v.changed)
        assertTrue(v.removals.isEmpty())
    }

}
