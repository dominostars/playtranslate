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
 * machine for [CleanStreamOverlayMode] ("Level 0"). Ported from the
 * `scanlines` branch, extended with [ScanlineReconciler.Region.replacesBox]
 * assertions (the field [StabilityHold] keys its CHANGED-only scoping on).
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

    // ── Scope contract (stream-sensor plan §6, Invariant 3) ──────────────

    /** A box outside the evidence scope pairs with nothing — but it must be
     *  HELD (verbatim keep), never REMOVED: absence of out-of-scope evidence
     *  is not evidence of absence. */
    @Test
    fun scopedCall_outOfScopeBox_heldNotRemoved() {
        val inBox = box(Rect(100, 100, 400, 150), sourceText = "IN")
        val outBox = box(Rect(100, 600, 400, 650), sourceText = "OUT")
        val g = grp("IN", Rect(100, 100, 400, 150))
        val v = ScanlineReconciler.reconcile(
            listOf(g), listOf(inBox, outBox),
            scope = listOf(Rect(0, 0, 1920, 300)),
        )
        assertEquals(0, v.missing)
        assertEquals(1, v.held)
        assertEquals(1, v.unchanged)
        assertEquals(2, v.keptBoxes.size)
        assertTrue("held box passes through verbatim", v.keptBoxes.contains(outBox))
        assertTrue(v.removals.isEmpty())
    }

    /** A group outside the scope is ignored — a cropped OCR cannot assert
     *  NEW text beyond its crop (stale garbage at crop edges must not spawn
     *  boxes). */
    @Test
    fun scopedCall_outOfScopeGroup_ignoredNotNew() {
        val gIn = grp("IN", Rect(100, 100, 400, 150))
        val gOut = grp("OUT", Rect(100, 600, 400, 650))
        val v = ScanlineReconciler.reconcile(
            listOf(gIn, gOut), emptyList(),
            scope = listOf(Rect(0, 0, 1920, 300)),
        )
        assertEquals(1, v.added)
        assertEquals(1, v.toTranslate.size)
        assertEquals("IN", v.toTranslate[0].text)
    }

    /** An out-of-scope group must not CLAIM an in-scope box's pairing slot,
     *  and an out-of-scope box must not claim an in-scope group. */
    @Test
    fun scopedCall_pairingConfinedToScope() {
        val boxIn = box(Rect(100, 100, 400, 150), sourceText = "ALPHA")
        val gIn = grp("ALPHA", Rect(102, 101, 401, 152))
        val gOut = grp("BETA", Rect(100, 600, 400, 650))
        val v = ScanlineReconciler.reconcile(
            listOf(gOut, gIn), listOf(boxIn),
            scope = listOf(Rect(0, 0, 1920, 300)),
        )
        assertEquals(1, v.unchanged)
        assertEquals(0, v.added)
        assertEquals(0, v.missing)
        assertEquals(0, v.held)
    }

    /** Null scope must be byte-identical to the pre-scope semantics: full
     *  judgment everywhere, held always zero. */
    @Test
    fun nullScope_fullFrameSemantics_heldZero() {
        val b = box(Rect(100, 600, 400, 650), sourceText = "GONE")
        val v = ScanlineReconciler.reconcile(emptyList(), listOf(b), scope = null)
        assertEquals(1, v.missing)
        assertEquals(0, v.held)
        assertEquals(listOf(b), v.removals)
    }

    // ── uncoveredScope (masked-scan scope decomposition, plan §9) ────────

    /** Every displayed (covered) box must be OUT of the produced scope, and
     *  uncovered probe points must be IN it. */
    @Test
    fun uncoveredScope_boxesOut_uncoveredIn() {
        val covered = listOf(Rect(100, 100, 500, 200), Rect(300, 600, 900, 700))
        val scope = ScanlineReconciler.uncoveredScope(1920, 1080, covered)
        for (c in covered) {
            assertTrue("covered rect $c leaked into scope",
                scope.none { Rect.intersects(it, c) })
        }
        val probes = listOf(Rect(0, 0, 10, 10), Rect(600, 150, 610, 160), Rect(100, 900, 110, 910))
        for (p in probes) {
            assertTrue("uncovered probe $p missing from scope",
                scope.any { Rect.intersects(it, p) })
        }
    }

    /** End-to-end masked-scan semantics: with scope = frame minus the boxes'
     *  blacked rects, every displayed box HOLDs (no MISSING despite pairing
     *  with nothing) and an uncovered group still surfaces as NEW. */
    @Test
    fun maskedScan_boxesHold_uncoveredNewSurfaces() {
        val b1 = box(Rect(100, 100, 500, 200), sourceText = "OLD1")
        val b2 = box(Rect(300, 600, 900, 700), sourceText = "OLD2")
        val menuText = grp("MENU", Rect(1200, 300, 1600, 360))
        val scope = ScanlineReconciler.uncoveredScope(
            1920, 1080, listOf(b1.bounds, b2.bounds),
        )
        val v = ScanlineReconciler.reconcile(listOf(menuText), listOf(b1, b2), scope = scope)
        assertEquals(0, v.missing)
        assertEquals(2, v.held)
        assertEquals(1, v.added)
        assertEquals("MENU", v.toTranslate.single().text)
        assertTrue(v.keptBoxes.containsAll(listOf(b1, b2)))
    }

    // ── subtract(): the scoped read's scope builder ──────────────────────

    /** Seeds minus covered: covered pixels never in the result, uncovered
     *  seed pixels always in it, nothing outside the seeds. */
    @Test
    fun subtract_seedsMinusCovered_exactComplementWithinSeeds() {
        val seeds = listOf(Rect(100, 100, 800, 300))
        val covered = listOf(Rect(90, 90, 810, 160), Rect(400, 200, 500, 260))
        val scope = ScanlineReconciler.subtract(seeds, covered)
        for (c in covered) {
            assertTrue("covered $c leaked into scope", scope.none { Rect.intersects(it, c) })
        }
        val inPoints = listOf(Rect(150, 200, 151, 201), Rect(700, 280, 701, 281))
        for (p in inPoints) {
            assertTrue("uncovered seed pixel $p missing", scope.any { Rect.intersects(it, p) })
        }
        assertTrue(
            "scope must stay within the seeds",
            scope.all { s -> seeds.any { it.contains(s) } },
        )
    }

    /** The name-tag-over-dialog case (Invariants 3/6): a scoped read of the
     *  dialog box whose scope inflation reaches a ≤8px neighbor. The
     *  neighbor's region was never swept — its pixels were blacked before
     *  OCR and subtracted from the scope — so it must HOLD, not read as
     *  MISSING, while the dialog itself is judged normally. */
    @Test
    fun scopedRead_unsweptNeighborInsideInflation_heldNotRemoved() {
        val nameTag = box(Rect(100, 92, 260, 128), sourceText = "NAME")
        val dialog = box(Rect(100, 134, 900, 260), sourceText = "OLDLINE")
        // Scope seed = dialog bounds +8px (reaches the name tag's bottom
        // edge); blacked = the name tag's rect +2px fringe.
        val seed = Rect(92, 126, 908, 268)
        val blacked = listOf(Rect(98, 90, 262, 130))
        val scope = ScanlineReconciler.subtract(listOf(seed), blacked)
        // OCR of the swept composite: the dialog's NEW text; nothing at the
        // name tag (its region was blacked).
        val newLine = grp("NEWLINE", Rect(102, 136, 898, 258))
        val v = ScanlineReconciler.reconcile(listOf(newLine), listOf(nameTag, dialog), scope = scope)
        assertEquals("name tag must HOLD", 1, v.held)
        assertEquals(0, v.missing)
        assertEquals("dialog change still judged", 1, v.changed)
        assertTrue(v.keptBoxes.contains(nameTag))
        assertEquals("NEWLINE", v.toTranslate.single().text)
    }
}
