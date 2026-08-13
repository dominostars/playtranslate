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
        conf: Float = -1f,
    ) = TextBox(
        translatedText = translatedText,
        bounds = bounds,
        sourceText = sourceText,
        lineCount = lineCount,
        orientation = orientation,
        sourceConfMin = conf,
        sourceConfMean = conf,
    )

    private fun grp(
        text: String,
        bounds: Rect,
        lineCount: Int = 1,
        orientation: TextOrientation = TextOrientation.HORIZONTAL,
        alignment: TextAlignment = TextAlignment.LEFT,
        conf: Float = -1f,
    ) = OcrManager.OcrGroup(
        text = text,
        bounds = bounds,
        orientation = orientation,
        alignment = alignment,
        lines = List(lineCount) {
            OcrManager.LineBox(text = text, bounds = bounds, groupIndex = 0, confidence = conf)
        },
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

    // ── Reading arbitration: a fuzz-same pair is a quality contest ────────

    @Test
    fun fuzzSameHigherConfidenceRead_upgradesInsteadOfKeeping() {
        // H↔X is a bag diff of 2 — inside the identity fuzz, so historically
        // a permanent KEEP: the garbled first read stayed canonical forever.
        // With a decisively better-scored fresh read it must retranslate.
        val r = Rect(0, 0, 400, 60)
        val b = box(r, "ABCDEFGH", conf = 0.5f)
        val v = ScanlineReconciler.reconcile(
            listOf(grp("ABCDEFGX", r, conf = 0.9f)), listOf(b), sourceLang = "en",
        )
        assertEquals(1, v.changed)
        assertEquals(1, v.upgraded)
        assertEquals("ABCDEFGX", v.toTranslate.single().text)
        assertEquals(b, v.toTranslate.single().replacesBox)
        assertTrue(v.keptBoxes.isEmpty())
    }

    @Test
    fun fuzzSameLowerConfidenceRead_keepsIncumbent() {
        val r = Rect(0, 0, 400, 60)
        val b = box(r, "ABCDEFGH", conf = 0.9f)
        val v = ScanlineReconciler.reconcile(
            listOf(grp("ABCDEFGX", r, conf = 0.5f)), listOf(b), sourceLang = "en",
        )
        assertEquals(listOf(b), v.keptBoxes)
        assertEquals(0, v.upgraded)
        assertTrue(v.toTranslate.isEmpty())
    }

    @Test
    fun fuzzSameNoSignals_statusQuoKeep() {
        // Unknown confidence on both sides, both texts clean: no evidence,
        // no behavior change — the deterministic-engine steady state.
        val r = Rect(0, 0, 400, 60)
        val b = box(r, "ABCDEFGH")
        val v = ScanlineReconciler.reconcile(
            listOf(grp("ABCDEFGX", r)), listOf(b), sourceLang = "en",
        )
        assertEquals(listOf(b), v.keptBoxes)
        assertEquals(0, v.upgraded)
    }

    @Test
    fun junkIncumbent_upgradesOnCleanRead_withoutConfidence() {
        // The junk tier alone: a substitution-garbled canonical (¦ for t)
        // loses to a clean re-read even with no confidence signal at all.
        val r = Rect(0, 0, 400, 60)
        val b = box(r, "Inven¦ory")
        val v = ScanlineReconciler.reconcile(
            listOf(grp("Inventory", r)), listOf(b), sourceLang = "en",
        )
        assertEquals(1, v.upgraded)
        assertEquals("Inventory", v.toTranslate.single().text)
    }

    @Test
    fun noSourceLang_arbitrationJunkTierInert() {
        // Legacy call shape (no language): junk cannot be judged, unknown
        // confidence proves nothing → byte-identical to old behavior.
        val r = Rect(0, 0, 400, 60)
        val b = box(r, "Inven¦ory")
        val v = ScanlineReconciler.reconcile(listOf(grp("Inventory", r)), listOf(b))
        assertEquals(listOf(b), v.keptBoxes)
        assertEquals(0, v.upgraded)
    }

    // ── Evidence ratchet: identical re-reads refresh the stored score ─────
    // Trace-style (multi-cycle, same instances flowing back in — as the
    // mode's cachedBoxes does): the stale-confidence class is invisible to
    // single-call decision tests (adversarial-review finding).

    @Test
    fun staleConfidenceRegression_identicalRereadsArmTheIncumbent() {
        // Cycle 1: clean text minted at LOW confidence (fade-in read).
        val r = Rect(0, 0, 400, 60)
        val b = box(r, "ABCDEFGH", conf = 0.55f)
        // Cycle 2: identical re-read at HIGH confidence — a silent KEEP,
        // but it must ratchet the stored score.
        var v = ScanlineReconciler.reconcile(
            listOf(grp("ABCDEFGH", r, conf = 0.9f)), listOf(b), sourceLang = "en",
        )
        assertEquals(listOf(b), v.keptBoxes)
        assertEquals(0.9f, b.sourceConfMin, 1e-6f)
        // Cycle 3: medium-confidence fuzz-same garble. Against the stale
        // 0.55 it would have WON (the no-ship bug); against the ratcheted
        // 0.9 it loses and the correct canonical survives.
        v = ScanlineReconciler.reconcile(
            listOf(grp("ABCDEFGX", r, conf = 0.7f)), listOf(b), sourceLang = "en",
        )
        assertEquals(listOf(b), v.keptBoxes)
        assertEquals(0, v.upgraded)
        assertTrue(v.toTranslate.isEmpty())
    }

    @Test
    fun ratchetIsMonotone_lowRereadCannotReopenTheWindow() {
        val r = Rect(0, 0, 400, 60)
        val b = box(r, "ABCDEFGH", conf = 0.9f)
        // A shimmer-degraded identical re-read must not LOWER the score...
        ScanlineReconciler.reconcile(
            listOf(grp("ABCDEFGH", r, conf = 0.5f)), listOf(b), sourceLang = "en",
        )
        assertEquals(0.9f, b.sourceConfMin, 1e-6f)
        // ...so the medium garble still loses afterwards.
        val v = ScanlineReconciler.reconcile(
            listOf(grp("ABCDEFGX", r, conf = 0.7f)), listOf(b), sourceLang = "en",
        )
        assertEquals(0, v.upgraded)
    }

    @Test
    fun ratchetUnknownReread_doesNotEraseAKnownScore() {
        val r = Rect(0, 0, 400, 60)
        val b = box(r, "ABCDEFGH", conf = 0.9f)
        ScanlineReconciler.reconcile(
            listOf(grp("ABCDEFGH", r)), listOf(b), sourceLang = "en",
        )
        assertEquals(0.9f, b.sourceConfMin, 1e-6f)
    }

    @Test
    fun ratchetIsBestSingleRead_notAChimeraOfComponents() {
        // Stored (0.8, 0.85). A re-read at (0.8, 0.80) is lexicographically
        // worse — BOTH components must stay, not just the min.
        val r = Rect(0, 0, 400, 60)
        val b = TextBox(
            translatedText = "T", bounds = r, sourceText = "ABCDEFGH",
            sourceConfMin = 0.8f, sourceConfMean = 0.85f,
        )
        b.ratchetSourceConf(0.8f, 0.80f)
        assertEquals(0.85f, b.sourceConfMean, 1e-6f)
        // Min-tie with a better mean upgrades the pair as a pair.
        b.ratchetSourceConf(0.8f, 0.95f)
        assertEquals(0.8f, b.sourceConfMin, 1e-6f)
        assertEquals(0.95f, b.sourceConfMean, 1e-6f)
    }

    @Test
    fun sameTextJitter_capturesExtremesWithLevels_preRatchet() {
        // Two same-text boxes: one re-reads below stored, one above. The
        // aggregate must carry both extremes with the PRE-ratchet stored
        // level they occurred at (the margin's-eye view).
        val rA = Rect(0, 0, 400, 60)
        val rB = Rect(0, 200, 400, 260)
        val a = box(rA, "ABCDEFGH", conf = 0.86f)
        val b = box(rB, "IJKLMNOP", conf = 0.56f)
        val v = ScanlineReconciler.reconcile(
            listOf(grp("ABCDEFGH", rA, conf = 0.81f), grp("IJKLMNOP", rB, conf = 0.59f)),
            listOf(a, b), sourceLang = "en",
        )
        val jit = v.sameTextJitter!!
        assertEquals(2, jit.count)
        assertEquals(-0.05f, jit.loDelta, 1e-5f)
        assertEquals(0.86f, jit.loAtMin, 1e-6f)
        assertEquals(0.03f, jit.hiDelta, 1e-5f)
        assertEquals(0.56f, jit.hiAtMin, 1e-6f)
        // The ratchet still ran after sampling: b improved, a held.
        assertEquals(0.59f, b.sourceConfMin, 1e-6f)
        assertEquals(0.86f, a.sourceConfMin, 1e-6f)
    }

    @Test
    fun sameTextJitter_unknownScoresAndDifferingTextsExcluded() {
        val rA = Rect(0, 0, 400, 60)
        val rB = Rect(0, 200, 400, 260)
        // A: same text but unknown fresh score — first-evidence, not jitter.
        val a = box(rA, "ABCDEFGH", conf = 0.8f)
        // B: fuzz-same but DIFFERING text — arbitration's business, not jitter.
        val b = box(rB, "IJKLMNOP", conf = 0.8f)
        val v = ScanlineReconciler.reconcile(
            listOf(grp("ABCDEFGH", rA), grp("IJKLMNOX", rB, conf = 0.7f)),
            listOf(a, b), sourceLang = "en",
        )
        assertEquals(null, v.sameTextJitter)
    }

    @Test
    fun ratchetSurvivesRepositionCopy() {
        // Identical text that also drifted: the reposition copy must carry
        // the freshly-ratcheted score (mutate-then-copy ordering).
        val r = Rect(0, 0, 400, 60)
        val moved = Rect(0, 40, 400, 100)
        val b = box(r, "ABCDEFGH", conf = 0.55f)
        val v = ScanlineReconciler.reconcile(
            listOf(grp("ABCDEFGH", moved, conf = 0.9f)), listOf(b), sourceLang = "en",
        )
        assertEquals(1, v.repositioned)
        assertEquals(moved, v.keptBoxes.single().bounds)
        assertEquals(0.9f, v.keptBoxes.single().sourceConfMin, 1e-6f)
    }

    // ── Slant refresh: angle drift the bounds hysteresis can miss ────────

    @Test
    fun slantDrift_beyondAngleHysteresis_repositionsWithFreshSlant() {
        val b = box(Rect(100, 100, 300, 180), "SALE")
            .copy(angleDeg = 12f, orientedWidth = 200f, orientedHeight = 40f)
        // Bounds within the 5px reposition hysteresis — bounds alone won't fire.
        val g = grp("SALE", Rect(101, 100, 301, 181))
            .copy(angleDeg = 16f, orientedWidth = 198f, orientedHeight = 40f)
        val v = ScanlineReconciler.reconcile(listOf(g), listOf(b))
        assertEquals(1, v.keptBoxes.size)
        assertEquals(1, v.repositioned)
        assertEquals(16f, v.keptBoxes.single().angleDeg, 0f)
        assertEquals(198f, v.keptBoxes.single().orientedWidth, 0f)
        assertEquals("translation preserved through the slant refresh", "T", v.keptBoxes.single().translatedText)
        assertEquals(g.bounds, v.keptBoxes.single().bounds)
    }

    @Test
    fun slantJitter_withinAngleHysteresis_keepsBoxVerbatim() {
        val b = box(Rect(100, 100, 300, 180), "SALE")
            .copy(angleDeg = 12f, orientedWidth = 200f, orientedHeight = 40f)
        val g = grp("SALE", Rect(101, 100, 301, 181))
            .copy(angleDeg = 13.5f, orientedWidth = 200f, orientedHeight = 40f)
        val v = ScanlineReconciler.reconcile(listOf(g), listOf(b))
        assertEquals(1, v.keptBoxes.size)
        assertEquals("sub-hysteresis angle jitter must not reposition", 0, v.repositioned)
        assertEquals(12f, v.keptBoxes.single().angleDeg, 0f)
    }

    @Test
    fun snapBoundaryHover_shortLineFlips_staySticky() {
        // A SHORT line hovering across the producer's snap threshold: reads
        // alternate angle 0 / 10.4 with identical bounds. The drawn chip's
        // corners move ~2·r·sin(δ/2) ≈ 4.5px at this size — inside the px
        // hysteresis — so the born state stays sticky and the overlay never
        // flaps. Stickiness is now a LENGTH property, not a mode special-case.
        val bounds = Rect(100, 100, 140, 130)
        val upright = box(bounds, "GO")
        val slantedRead = grp("GO", bounds)
            .copy(angleDeg = 10.4f, orientedWidth = 40f, orientedHeight = 30f)
        val v1 = ScanlineReconciler.reconcile(listOf(slantedRead), listOf(upright))
        assertEquals(0, v1.repositioned)
        assertEquals(0f, v1.keptBoxes.single().angleDeg, 0f)

        val rotated = upright.copy(angleDeg = 10.4f, orientedWidth = 40f, orientedHeight = 30f)
        val uprightRead = grp("GO", bounds)
        val v2 = ScanlineReconciler.reconcile(listOf(uprightRead), listOf(rotated))
        assertEquals(0, v2.repositioned)
        assertEquals(10.4f, v2.keptBoxes.single().angleDeg, 0f)
    }

    @Test
    fun snapBoundaryHover_longLineGainsAngle_refresh() {
        // Gaining an angle on a LONG banner is a real visual event: corners
        // sweep ~37px at this length, far past the px hysteresis, so the kept
        // box refreshes onto the fresh read's geometry — a long chip drawn 10°
        // off its text is a misrender, not jitter.
        val bounds = Rect(100, 100, 500, 180)
        val upright = box(bounds, "GRAND OPENING SALE")
        val slantedRead = grp("GRAND OPENING SALE", bounds)
            .copy(angleDeg = 10.4f, orientedWidth = 400f, orientedHeight = 80f)
        val v1 = ScanlineReconciler.reconcile(listOf(slantedRead), listOf(upright))
        assertEquals(1, v1.repositioned)
        assertEquals(10.4f, v1.keptBoxes.single().angleDeg, 0f)
        assertEquals("translation preserved through the refresh", "T", v1.keptBoxes.single().translatedText)
    }

    @Test
    fun slantHysteresis_uprightReadOnAngledBox_holdsTheAngle() {
        // The REVERSE flip — an upright read of the same text on a box that
        // carries a measured angle — is NOT symmetric evidence: gaining an
        // angle took a measurement, the producer gates, and a confidence win;
        // "upright" only means the producer's retry didn't fire this cycle.
        // On animating pages acceptance flips cycle to cycle and the chip
        // flickered angled↔upright (Thor, P3R activity screen), so the angle
        // is HELD while the text lives. (Supersedes the earlier rule that
        // refreshed long-line flips in both directions.)
        val bounds = Rect(100, 100, 500, 180)
        val rotated = box(bounds, "GRAND OPENING SALE")
            .copy(angleDeg = 10.4f, orientedWidth = 400f, orientedHeight = 80f)
        val uprightRead = grp("GRAND OPENING SALE", bounds)
        val v = ScanlineReconciler.reconcile(listOf(uprightRead), listOf(rotated))
        assertEquals("no reposition on an angle-only flicker", 0, v.repositioned)
        assertEquals(10.4f, v.keptBoxes.single().angleDeg, 0f)
    }

    @Test
    fun slantHysteresis_heldAngleRidesFreshBoundsOnDrift() {
        // Bounds drift past hysteresis WITH an upright read: the box tracks
        // the moving text (fresh bounds) but keeps its measured angle and
        // oriented dims — the drift is evidence of motion, not of uprightness.
        val rotated = box(Rect(100, 100, 500, 180), "GRAND OPENING SALE")
            .copy(angleDeg = 10.4f, orientedWidth = 400f, orientedHeight = 80f)
        val moved = grp("GRAND OPENING SALE", Rect(120, 112, 520, 192))
        val v = ScanlineReconciler.reconcile(listOf(moved), listOf(rotated))
        assertEquals(1, v.repositioned)
        val kept = v.keptBoxes.single()
        assertEquals(Rect(120, 112, 520, 192), kept.bounds)
        assertEquals(10.4f, kept.angleDeg, 0f)
        assertEquals(400f, kept.orientedWidth, 0f)
        assertEquals(80f, kept.orientedHeight, 0f)
    }

    @Test
    fun slantHysteresis_sizeChangeReleasesTheHold() {
        // Release valve 1: the upright read arrives with materially different
        // SIZE — a re-layout, not jitter. Holding the old angle would also
        // keep stale oriented dims on fresh bounds (adversarial-review
        // finding), so the hold yields to the fresh upright geometry.
        val rotated = box(Rect(100, 100, 500, 180), "GRAND OPENING SALE")
            .copy(angleDeg = 10.4f, orientedWidth = 400f, orientedHeight = 80f)
        val relaidOut = grp("GRAND OPENING SALE", Rect(100, 100, 560, 180))
        val v = ScanlineReconciler.reconcile(listOf(relaidOut), listOf(rotated))
        val kept = v.keptBoxes.single()
        assertEquals(0f, kept.angleDeg, 0f)
        assertEquals(Rect(100, 100, 560, 180), kept.bounds)
    }

    @Test
    fun slantHysteresis_persistentUprightStreakReleasesTheHold() {
        // Release valve 2: five CONSECUTIVE upright reads = a genuine upright
        // transition; the hold releases instead of sticking for the box's
        // lifetime. (The flicker the hold exists for alternates angled and
        // upright, so it never builds the streak — next test.)
        val bounds = Rect(100, 100, 500, 180)
        var boxNow = box(bounds, "GRAND OPENING SALE")
            .copy(angleDeg = 10.4f, orientedWidth = 400f, orientedHeight = 80f)
        val uprightRead = grp("GRAND OPENING SALE", bounds)
        repeat(4) { i ->
            val v = ScanlineReconciler.reconcile(listOf(uprightRead), listOf(boxNow))
            boxNow = v.keptBoxes.single()
            assertEquals("read ${i + 1} still holds", 10.4f, boxNow.angleDeg, 0f)
        }
        val v5 = ScanlineReconciler.reconcile(listOf(uprightRead), listOf(boxNow))
        assertEquals("fifth consecutive upright read releases", 0f, v5.keptBoxes.single().angleDeg, 0f)
    }

    @Test
    fun slantHysteresis_alternatingReadsNeverBuildTheStreak() {
        // The actual flicker pattern: angled and upright reads alternate.
        // The streak resets on every measured angle, so the hold survives
        // indefinitely — which is the point.
        val bounds = Rect(100, 100, 500, 180)
        var boxNow = box(bounds, "GRAND OPENING SALE")
            .copy(angleDeg = 10.4f, orientedWidth = 400f, orientedHeight = 80f)
        val uprightRead = grp("GRAND OPENING SALE", bounds)
        val angledRead = grp("GRAND OPENING SALE", bounds)
            .copy(angleDeg = 10.4f, orientedWidth = 400f, orientedHeight = 80f)
        repeat(8) { i ->
            val read = if (i % 2 == 0) uprightRead else angledRead
            val v = ScanlineReconciler.reconcile(listOf(read), listOf(boxNow))
            boxNow = v.keptBoxes.single()
            assertEquals("cycle ${i + 1} keeps the angle", 10.4f, boxNow.angleDeg, 0f)
        }
    }

    @Test
    fun slantHysteresis_freshMeasuredAngle_stillUpdates() {
        // θ→θ′ with a MEASURED fresh angle is positive evidence and must keep
        // refreshing (the hold applies only to θ→0): 12° → 20° on a long chip
        // sweeps corners far past hysteresis.
        val rotated = box(Rect(100, 100, 500, 180), "GRAND OPENING SALE")
            .copy(angleDeg = 12f, orientedWidth = 400f, orientedHeight = 80f)
        val steeper = grp("GRAND OPENING SALE", Rect(100, 100, 500, 180))
            .copy(angleDeg = 20f, orientedWidth = 400f, orientedHeight = 80f)
        val v = ScanlineReconciler.reconcile(listOf(steeper), listOf(rotated))
        assertEquals(1, v.repositioned)
        assertEquals(20f, v.keptBoxes.single().angleDeg, 0f)
    }
}
