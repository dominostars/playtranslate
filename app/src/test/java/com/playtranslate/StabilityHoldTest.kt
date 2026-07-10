package com.playtranslate

import android.graphics.Rect
import com.playtranslate.language.TextAlignment
import com.playtranslate.language.TextOrientation
import com.playtranslate.ui.TextBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Trace tests for [StabilityHold] — the capture-anchored typewriter hold.
 * Each test drives the hold with hand-built [ScanlineReconciler.Verdicts]
 * cycles and explicit clocks, the way [CleanStreamOverlayMode] does per read.
 *
 * Clock convention in these traces: `capture(t)` is the frame's capture
 * uptime; evaluation happens `ocrMs` later (`now = capture + ocrMs`).
 * Runs under Robolectric for [android.graphics.Rect] (same convention as
 * [ScanlineReconcilerTest]).
 */
@RunWith(RobolectricTestRunner::class)
class StabilityHoldTest {

    private val r = Rect(0, 0, 400, 60)

    private fun box(sourceText: String, translated: String = "T") = TextBox(
        translatedText = translated,
        bounds = r,
        sourceText = sourceText,
        lineCount = 1,
    )

    private fun region(text: String, replaces: TextBox? = null) = ScanlineReconciler.Region(
        text = text,
        bounds = r,
        lineCount = 1,
        orientation = TextOrientation.HORIZONTAL,
        alignment = TextAlignment.LEFT,
        replacesBox = replaces,
    )

    private fun verdicts(vararg toTranslate: ScanlineReconciler.Region) =
        ScanlineReconciler.Verdicts(
            keptBoxes = emptyList(),
            toTranslate = toTranslate.toList(),
            removals = emptyList(),
            unchanged = 0,
            changed = toTranslate.count { it.replacesBox != null },
            missing = 0,
            added = toTranslate.count { it.replacesBox == null },
            repositioned = 0,
        )

    // ── Scoping: what the hold must never touch ───────────────────────────

    @Test
    fun newText_passesThrough_neverHeld() {
        val hold = StabilityHold()
        val out = hold.filter(verdicts(region("Hello")), captureAtMs = 0, nowMs = 500)
        assertEquals(listOf("Hello"), out.toTranslate.map { it.text })
        assertTrue(out.heldBoxes.isEmpty())
        assertNull(out.nextDeadlineMs)
    }

    @Test
    fun blankRetry_sameText_passesThrough() {
        val hold = StabilityHold()
        val failed = box("Retry", translated = "")
        val out = hold.filter(verdicts(region("Retry", failed)), captureAtMs = 0, nowMs = 500)
        assertEquals("a retry of stable text is not a typewriter", 1, out.toTranslate.size)
        assertTrue(out.heldBoxes.isEmpty())
    }

    @Test
    fun realChange_dialogueAdvance_translatesImmediately() {
        val hold = StabilityHold()
        val old = box("こんにちは、旅の人。")
        val out = hold.filter(
            verdicts(region("それでは、始めよう。", old)), captureAtMs = 0, nowMs = 500,
        )
        assertEquals("a genuinely different line is Level 0 — translate now",
            1, out.toTranslate.size)
        assertTrue(out.heldBoxes.isEmpty())
    }

    // ── The typewriter trace (fast device) ────────────────────────────────

    @Test
    fun typewriterReveal_holdsPartials_releasesOnTwoStableReads() {
        val hold = StabilityHold()
        val shown = box("こんにち") // the partial that got boxed at first sight
        // Each reveal step adds >3 chars so it clears isSignificantChange's
        // bag tolerance — sub-tolerance growth never reaches the hold at all
        // (the reconciler KEEPs it).
        // Read 1 (capture t=1000, OCR 300ms): text grew — evolving → hold.
        var out = hold.filter(
            verdicts(region("こんにちは、旅の人", shown)), captureAtMs = 1000, nowMs = 1300,
        )
        assertTrue("mid-reveal partial is deferred", out.toTranslate.isEmpty())
        assertEquals(listOf(shown), out.heldBoxes)
        assertEquals("deadline = open capture + cap",
            1000L + StabilityHold.HOLD_MAX_MS, out.nextDeadlineMs)
        // Read 2 (t=1500): grew again — pending replaced, still held.
        out = hold.filter(
            verdicts(region("こんにちは、旅の人よ、聞くがいい。", shown)),
            captureAtMs = 1500, nowMs = 1800,
        )
        assertTrue(out.toTranslate.isEmpty())
        assertEquals(listOf(shown), out.heldBoxes)
        // Read 3 (t=2000): same text — stable #2 → release the settled line.
        out = hold.filter(
            verdicts(region("こんにちは、旅の人よ、聞くがいい。", shown)),
            captureAtMs = 2000, nowMs = 2300,
        )
        assertEquals(listOf("こんにちは、旅の人よ、聞くがいい。"), out.toTranslate.map { it.text })
        assertTrue(out.heldBoxes.isEmpty())
        assertNull("no open holds after release", out.nextDeadlineMs)
    }

    @Test
    fun stillEvolvingAtCap_flushesNewestText() {
        val hold = StabilityHold()
        val shown = box("ABCDEFGH")
        // Opens at capture t=0 (fast pipeline).
        var out = hold.filter(verdicts(region("ABCDEFGHIJKLM", shown)), 0, 400)
        assertTrue(out.toTranslate.isEmpty())
        // Keeps growing past the cap (evaluation at 2.4s > 2s cap). The growth
        // (+7 chars) is a significant change from pending AND an extension of
        // it — still evolving, but the cap flushes the newest text.
        out = hold.filter(verdicts(region("ABCDEFGHIJKLMNOPQRST", shown)), 2000, 2400)
        assertEquals("cap flushes the newest text even though still evolving",
            listOf("ABCDEFGHIJKLMNOPQRST"), out.toTranslate.map { it.text })
        assertTrue(out.heldBoxes.isEmpty())
    }

    @Test
    fun advanceDuringReveal_releasesImmediately() {
        val hold = StabilityHold()
        val shown = box("The merchant said")
        var out = hold.filter(verdicts(region("The merchant said hello to", shown)), 0, 300)
        assertTrue(out.toTranslate.isEmpty())
        // User advances mid-reveal: completely different line — translate NOW.
        out = hold.filter(verdicts(region("Chapter Two", shown)), 700, 1000)
        assertEquals(listOf("Chapter Two"), out.toTranslate.map { it.text })
        assertTrue(out.heldBoxes.isEmpty())
        assertNull(out.nextDeadlineMs)
    }

    // ── Slow-OCR self-disable (the Moto G ruling) ─────────────────────────

    @Test
    fun slowDevice_capAlreadyExpiredAtOpening_opensAndReleases() {
        val hold = StabilityHold()
        val shown = box("こんにち")
        // The frame was captured at t=0; OCR took 5s → evaluation at t=5000.
        // Age (5000) ≥ cap (2000) before the hold could even open.
        val out = hold.filter(
            verdicts(region("こんにちは、旅の人。", shown)), captureAtMs = 0, nowMs = 5000,
        )
        assertEquals("slow pipeline: translate on sight, zero added latency",
            listOf("こんにちは、旅の人。"), out.toTranslate.map { it.text })
        assertTrue(out.heldBoxes.isEmpty())
        assertNull(out.nextDeadlineMs)
    }

    // ── Garble / re-affirmation sweep ─────────────────────────────────────

    @Test
    fun garbleChange_thenKeepCycle_holdSweptNotLeaked() {
        val hold = StabilityHold()
        val shown = box("Inventory")
        // One garbled read looks like a significant extension → hold opens.
        // (+5 garbage chars: past isSignificantChange's tolerance, but the
        // aligned prefix still matches — the evolving signature.)
        var out = hold.filter(verdicts(region("Inventory ¦lem", shown)), 0, 300)
        assertTrue(out.toTranslate.isEmpty())
        assertEquals(1, out.heldBoxes.size)
        // Next cycle the garble evaporates: the box is KEEP, so its region is
        // NOT in toTranslate — the un-affirmed hold must be swept.
        out = hold.filter(verdicts(), 500, 800)
        assertNull("hold cleared when not re-affirmed", out.nextDeadlineMs)
        assertTrue(out.heldBoxes.isEmpty())
        // A fresh garble later starts a FRESH hold (stableReads reset), not a
        // resumed one — the second agreeing read releases, proving the state
        // was rebuilt rather than leaked.
        out = hold.filter(verdicts(region("Inventory ¦lem", shown)), 1000, 1300)
        assertTrue(out.toTranslate.isEmpty())
        out = hold.filter(verdicts(region("Inventory ¦lem", shown)), 1500, 1800)
        assertEquals(1, out.toTranslate.size)
    }

    // ── Lever ─────────────────────────────────────────────────────────────

    @Test
    fun disabled_isPureLevelZero() {
        // Compile-time lever: when off, everything passes through untouched.
        // (Guard so the suite still passes if the lever is flipped for an A/B.)
        if (!StabilityHold.STABILITY_HOLD) {
            val hold = StabilityHold()
            val shown = box("ABC")
            val out = hold.filter(verdicts(region("ABCDE", shown)), 0, 300)
            assertEquals(1, out.toTranslate.size)
            assertTrue(out.heldBoxes.isEmpty())
        } else {
            // Lever is ON in this build: sanity-check the held path instead.
            val hold = StabilityHold()
            val shown = box("ABCDEFGH")
            val out = hold.filter(verdicts(region("ABCDEFGHIJKLM", shown)), 0, 300)
            assertTrue(out.toTranslate.isEmpty())
            assertNotNull(out.nextDeadlineMs)
        }
    }
}
