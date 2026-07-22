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
 * Trace tests for [TypewriterGate] — sentence-gated typewriter dispatch.
 * Each test drives the gate with hand-built cycles and explicit clocks,
 * the way the live modes do per read. Ports the StabilityHold trace suite
 * (scoping, caps, sweep, slow-OCR self-disable) and adds the gate's new
 * behavior: boundary release, prefix dispatch, region arming, the pinhole
 * far-group adapter, and the Thai legacy fallback.
 *
 * Clock convention: `capture(t)` is the frame's capture uptime; evaluation
 * happens later (`now = capture + ocrMs`). Runs under Robolectric for
 * [android.graphics.Rect].
 */
@RunWith(RobolectricTestRunner::class)
class TypewriterGateTest {

    private val r = Rect(0, 0, 400, 60)

    private fun box(sourceText: String, translated: String = "T") = TextBox(
        translatedText = translated,
        bounds = r,
        sourceText = sourceText,
        lineCount = 1,
    )

    private fun region(
        text: String,
        replaces: TextBox? = null,
        bounds: Rect = r,
    ) = ScanlineReconciler.Region(
        text = text,
        bounds = bounds,
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

    private fun far(text: String, bounds: Rect = r, paired: Boolean = false) =
        FarGroup(text = text, bounds = bounds, lineCount = 1, paired = paired)

    private fun TypewriterGate.reconcile(
        v: ScanlineReconciler.Verdicts,
        captureAtMs: Long,
        nowMs: Long,
        lang: String = "ja",
        prefix: Boolean = true,
    ) = filterVerdicts(v, lang, captureAtMs, nowMs, allowPartialPrefix = prefix)

    // ── Scoping: what the gate must never touch (StabilityHold parity) ────

    @Test
    fun newText_unarmedRegion_passesThrough() {
        val gate = TypewriterGate()
        val out = gate.reconcile(verdicts(region("Hello")), 0, 500, lang = "en")
        assertEquals(listOf("Hello"), out.toTranslate.map { it.text })
        assertTrue(out.heldBoxes.isEmpty())
        assertNull(out.nextDeadlineMs)
    }

    @Test
    fun blankRetry_sameText_passesThrough() {
        val gate = TypewriterGate()
        val failed = box("Retry", translated = "")
        val out = gate.reconcile(verdicts(region("Retry", failed)), 0, 500, lang = "en")
        assertEquals("a retry of stable text is not a typewriter", 1, out.toTranslate.size)
        assertTrue(out.heldBoxes.isEmpty())
    }

    @Test
    fun realChange_dialogueAdvance_unarmed_translatesImmediately() {
        val gate = TypewriterGate()
        val old = box("こんにちは、旅の人。")
        val out = gate.reconcile(verdicts(region("それでは、始めよう", old)), 0, 500)
        assertEquals("an unarmed advance is Level 0 — translate now",
            1, out.toTranslate.size)
        assertTrue(out.heldBoxes.isEmpty())
    }

    // ── Boundary release: the zero-latency exit ───────────────────────────

    @Test
    fun boundaryFinalRead_releasesItself_noConfirmingRead() {
        val gate = TypewriterGate()
        val shown = box("こんにち")
        // Mid-reveal, punct-less → held.
        var out = gate.reconcile(verdicts(region("こんにちは、旅の人", shown)), 1000, 1300)
        assertTrue(out.toTranslate.isEmpty())
        assertEquals(listOf(shown), out.heldBoxes)
        assertEquals(1000L + TypewriterGate.HOLD_MAX_MS, out.nextDeadlineMs)
        // Completion read ends with 。 — dispatches on THIS read, no
        // agreement wait, no cap. The confirmation tax is gone.
        out = gate.reconcile(
            verdicts(region("こんにちは、旅の人よ、聞くがいい。", shown)), 1500, 1800,
        )
        assertEquals(listOf("こんにちは、旅の人よ、聞くがいい。"), out.toTranslate.map { it.text })
        assertTrue(out.heldBoxes.isEmpty())
        assertNull(out.nextDeadlineMs)
    }

    @Test
    fun punctlessEnding_releasesOnTwoStableReads() {
        val gate = TypewriterGate()
        val shown = box("こんにち")
        var out = gate.reconcile(verdicts(region("こんにちは、旅の人", shown)), 1000, 1300)
        assertTrue(out.toTranslate.isEmpty())
        // Grew again, still no terminal → still held.
        out = gate.reconcile(verdicts(region("こんにちは、旅の人よ聞くがいい", shown)), 1500, 1800)
        assertTrue(out.toTranslate.isEmpty())
        assertEquals(listOf(shown), out.heldBoxes)
        // Same text again — agreement releases (GSM parity).
        out = gate.reconcile(verdicts(region("こんにちは、旅の人よ聞くがいい", shown)), 2000, 2300)
        assertEquals(listOf("こんにちは、旅の人よ聞くがいい"), out.toTranslate.map { it.text })
        assertNull(out.nextDeadlineMs)
    }

    @Test
    fun stillEvolvingAtCap_flushesNewestText() {
        val gate = TypewriterGate()
        val shown = box("ABCDEFGH")
        var out = gate.reconcile(verdicts(region("ABCDEFGHIJKLM", shown)), 0, 400, lang = "en")
        assertTrue(out.toTranslate.isEmpty())
        out = gate.reconcile(verdicts(region("ABCDEFGHIJKLMNOPQRST", shown)), 2000, 2400, lang = "en")
        assertEquals("cap flushes the newest text even though still evolving",
            listOf("ABCDEFGHIJKLMNOPQRST"), out.toTranslate.map { it.text })
        assertTrue(out.heldBoxes.isEmpty())
    }

    @Test
    fun advanceDuringReveal_releasesImmediately() {
        val gate = TypewriterGate()
        val shown = box("The merchant said")
        var out = gate.reconcile(verdicts(region("The merchant said hello to", shown)), 0, 300, lang = "en")
        assertTrue(out.toTranslate.isEmpty())
        out = gate.reconcile(verdicts(region("Chapter Two", shown)), 700, 1000, lang = "en")
        assertEquals(listOf("Chapter Two"), out.toTranslate.map { it.text })
        assertTrue(out.heldBoxes.isEmpty())
        assertNull(out.nextDeadlineMs)
    }

    // ── Prefix dispatch (reconciler TRANSLATION mode only) ────────────────

    @Test
    fun interiorBoundary_dispatchesSentencePrefix_holdsTail() {
        val gate = TypewriterGate()
        val shown = box("こんにちは、")
        // The read contains a completed sentence plus a ragged tail: the
        // sentence dispatches (in-place upgrade), the tail stays held.
        var out = gate.reconcile(
            verdicts(region("こんにちは、今日は晴れだ。それから", shown)), 0, 300,
        )
        assertEquals(listOf("こんにちは、今日は晴れだ。"), out.toTranslate.map { it.text })
        assertNotNull("tail still held", out.nextDeadlineMs)
        // Full text completes → whole read dispatches, hold closes.
        val prefixBox = box("こんにちは、今日は晴れだ。")
        out = gate.reconcile(
            verdicts(region("こんにちは、今日は晴れだ。それから帰ろう。", prefixBox)), 500, 800,
        )
        assertEquals(listOf("こんにちは、今日は晴れだ。それから帰ろう。"), out.toTranslate.map { it.text })
        assertNull(out.nextDeadlineMs)
    }

    @Test
    fun pinholeMode_neverDispatchesPrefixes() {
        val gate = TypewriterGate()
        // Seed region memory the pinhole way: a dispatched partial.
        var out = gate.filterFarGroups(listOf(far("こんにちは、")), "ja", 0, 200)
        assertEquals(1, out.dispatch.size)
        // Fuller read with an interior boundary: whole-read gating only —
        // a prefix box over still-typing text would flash out.
        out = gate.filterFarGroups(listOf(far("こんにちは、今日は晴れだ。それから")), "ja", 500, 700)
        assertTrue(out.dispatch.isEmpty())
        assertEquals(1, out.held)
        // Boundary-final read releases whole.
        out = gate.filterFarGroups(
            listOf(far("こんにちは、今日は晴れだ。それから帰ろう。")), "ja", 1000, 1200,
        )
        assertEquals(1, out.dispatch.size)
        assertEquals(0, out.held)
        assertNull(out.nextDeadlineMs)
    }

    // ── Pinhole adapter: region memory bridges the removed box ────────────

    @Test
    fun farGroups_evolvingAgainstLastDispatch_heldWithoutPairedBox() {
        val gate = TypewriterGate()
        // Cycle A: partial placed (Level 0 first response, box then dies to
        // pinhole detection — the gate never sees the removal).
        var out = gate.filterFarGroups(listOf(far("こんにち")), "ja", 0, 200)
        assertEquals(1, out.dispatch.size)
        // Cycle B: fuller text arrives as an UNPAIRED far group. The region
        // memory recognizes the growth and holds it.
        out = gate.filterFarGroups(listOf(far("こんにちは、旅の")), "ja", 1000, 1200)
        assertTrue(out.dispatch.isEmpty())
        assertEquals(1, out.held)
        assertNotNull(out.nextDeadlineMs)
    }

    @Test
    fun pairedFarGroup_bypassesTheGate() {
        val gate = TypewriterGate()
        gate.filterFarGroups(listOf(far("こんにち")), "ja", 0, 200)
        // Evolving text, but paired: the content-match placement promise
        // must never break.
        val out = gate.filterFarGroups(
            listOf(far("こんにちは、旅の", paired = true)), "ja", 1000, 1200,
        )
        assertEquals(1, out.dispatch.size)
        assertEquals(0, out.held)
    }

    @Test
    fun emptyBatch_sweepsUnaffirmedHolds() {
        val gate = TypewriterGate()
        gate.filterFarGroups(listOf(far("こんにち")), "ja", 0, 200)
        var out = gate.filterFarGroups(listOf(far("こんにちは、旅の")), "ja", 500, 700)
        assertEquals(1, out.held)
        // Next full look shows nothing at the region (garble evaporated /
        // region gone): the hold is swept, deadline gone.
        out = gate.filterFarGroups(emptyList(), "ja", 1000, 1200)
        assertNull(out.nextDeadlineMs)
    }

    // ── Arming ────────────────────────────────────────────────────────────

    private fun armViaReveal(gate: TypewriterGate, t0: Long): Long {
        // A reveal observed growing and completing at a boundary arms the
        // region. Seed (Level 0 dispatch), grow (held), complete (boundary
        // release + arm).
        gate.filterFarGroups(listOf(far("こんにち")), "ja", t0, t0 + 200)
        gate.filterFarGroups(listOf(far("こんにちは、旅の")), "ja", t0 + 1000, t0 + 1200)
        val out = gate.filterFarGroups(listOf(far("こんにちは、旅の人よ。")), "ja", t0 + 2000, t0 + 2200)
        assertEquals("arming reveal completes", 1, out.dispatch.size)
        return t0 + 2200
    }

    @Test
    fun armedRegion_nextMessageFragment_neverDisplays() {
        val gate = TypewriterGate()
        val t = armViaReveal(gate, 0)
        // Message 2 starts typing: a mid-sentence ADVANCE in an armed
        // region is sentence-gated from its FIRST read — the fragment
        // that used to translate is held.
        var out = gate.filterFarGroups(listOf(far("それでは、始め")), "ja", t + 1000, t + 1200)
        assertTrue("message-2 first fragment suppressed", out.dispatch.isEmpty())
        assertEquals(1, out.held)
        // Completion read releases itself.
        out = gate.filterFarGroups(listOf(far("それでは、始めよう。")), "ja", t + 2000, t + 2200)
        assertEquals(listOf("それでは、始めよう。"), out.dispatch.map { it.text })
    }

    @Test
    fun armedRegion_instantPunctFinalMessage_zeroPenalty() {
        val gate = TypewriterGate()
        val t = armViaReveal(gate, 0)
        // An instant, complete message in an armed region dispatches on the
        // read that discovered it.
        val out = gate.filterFarGroups(listOf(far("戦闘開始だ。")), "ja", t + 1000, t + 1200)
        assertEquals(1, out.dispatch.size)
        assertEquals(0, out.held)
    }

    @Test
    fun armedRegion_punctlessInstant_waitsOneAgreementRead_cappedAtInterval() {
        val gate = TypewriterGate()
        val t = armViaReveal(gate, 0)
        // Punct-less instant text (the priced residual): held with the
        // SHORT armed cap — the shipped-cadence law.
        var out = gate.filterFarGroups(listOf(far("はい")), "ja", t + 1000, t + 1100)
        assertTrue(out.dispatch.isEmpty())
        assertEquals(t + 1000 + TypewriterGate.ARMED_NEW_MAX_MS, out.nextDeadlineMs)
        // Next read agrees → released.
        out = gate.filterFarGroups(listOf(far("はい")), "ja", t + 2000, t + 2100)
        assertEquals(1, out.dispatch.size)
        assertNull(out.nextDeadlineMs)
    }

    @Test
    fun unarmedFirstMessage_fragmentStillTranslates_baselineNotRegressed() {
        val gate = TypewriterGate()
        // Message 1 of a session: no evidence yet → Level 0 first response,
        // exactly today's behavior.
        val out = gate.filterFarGroups(listOf(far("私は彼を殺し")), "ja", 0, 200)
        assertEquals(1, out.dispatch.size)
    }

    @Test
    fun clearHolds_keepsArming_fullClearDropsIt() {
        val gate = TypewriterGate()
        val t = armViaReveal(gate, 0)
        // The pinhole input path clears holds per dismiss — arming survives.
        gate.clearHolds()
        var out = gate.filterFarGroups(listOf(far("それでは、始め")), "ja", t + 1000, t + 1200)
        assertEquals("armed gating survives a dismiss", 1, out.held)
        // A coordinate-voiding reset drops everything.
        gate.clear()
        out = gate.filterFarGroups(listOf(far("それでは、始め")), "ja", t + 2000, t + 2200)
        assertEquals(0, out.held)
        assertEquals(1, out.dispatch.size)
    }

    // ── Slow-OCR self-disable (the anchoring rule) ────────────────────────

    @Test
    fun slowDevice_capAlreadyExpiredAtOpening_opensAndReleases() {
        val gate = TypewriterGate()
        val shown = box("こんにち")
        // Punct-less growth; OCR took 5s → cap expired before the hold
        // could open. Translate on sight, zero added latency.
        val out = gate.reconcile(verdicts(region("こんにちは、旅の人さ", shown)), 0, 5000)
        assertEquals(listOf("こんにちは、旅の人さ"), out.toTranslate.map { it.text })
        assertTrue(out.heldBoxes.isEmpty())
        assertNull(out.nextDeadlineMs)
    }

    // ── Garble / re-affirmation sweep (StabilityHold parity) ──────────────

    @Test
    fun garbleChange_thenKeepCycle_holdSweptNotLeaked() {
        val gate = TypewriterGate()
        val shown = box("Inventory")
        var out = gate.reconcile(verdicts(region("Inventory ¦lem", shown)), 0, 300, lang = "en")
        assertTrue(out.toTranslate.isEmpty())
        assertEquals(1, out.heldBoxes.size)
        // Next cycle the garble evaporates (KEEP) — the hold is swept.
        out = gate.reconcile(verdicts(), 500, 800, lang = "en")
        assertNull("hold cleared when not re-affirmed", out.nextDeadlineMs)
        assertTrue(out.heldBoxes.isEmpty())
        // A fresh garble later starts a FRESH hold; its second agreeing
        // read releases — state rebuilt, not leaked.
        out = gate.reconcile(verdicts(region("Inventory ¦lem", shown)), 1000, 1300, lang = "en")
        assertTrue(out.toTranslate.isEmpty())
        out = gate.reconcile(verdicts(region("Inventory ¦lem", shown)), 1500, 1800, lang = "en")
        assertEquals(1, out.toTranslate.size)
    }

    // ── Thai: no boundary convention → legacy hold semantics ──────────────

    @Test
    fun thai_boundaryLookingText_stillNeedsAgreement() {
        val gate = TypewriterGate()
        val shown = box("สวัสดีค")
        // Ends with '.' but th has no boundary support — held.
        var out = gate.reconcile(
            verdicts(region("สวัสดีครับท่าน.", shown)), 0, 300, lang = "th",
        )
        assertTrue(out.toTranslate.isEmpty())
        assertEquals(1, out.heldBoxes.size)
        // Agreement releases, exactly the shipped hold.
        out = gate.reconcile(verdicts(region("สวัสดีครับท่าน.", shown)), 500, 800, lang = "th")
        assertEquals(1, out.toTranslate.size)
    }

    @Test
    fun thai_neverArms() {
        val gate = TypewriterGate()
        val shown = box("สวัสดีค")
        var out = gate.reconcile(verdicts(region("สวัสดีครับท่าน", shown)), 0, 300, lang = "th")
        assertTrue(out.toTranslate.isEmpty())
        out = gate.reconcile(verdicts(region("สวัสดีครับท่าน", shown)), 500, 800, lang = "th")
        assertEquals(1, out.toTranslate.size)
        // A new punct-less sighting at the region translates immediately —
        // nothing armed it.
        out = gate.reconcile(verdicts(region("ลาก่อน")), 1500, 1800, lang = "th")
        assertEquals(1, out.toTranslate.size)
    }

    // ── Region matching geometry ──────────────────────────────────────────

    @Test
    fun grownRect_matchesItsRegion_disjointRectDoesNot() {
        val gate = TypewriterGate()
        gate.filterFarGroups(listOf(far("こんにち", bounds = Rect(0, 0, 400, 60))), "ja", 0, 200)
        // The fuller read's rect grew a line taller — the earlier partial's
        // rect sits inside it, so it still matches (held as growth).
        var out = gate.filterFarGroups(
            listOf(far("こんにちは、旅の", bounds = Rect(0, 0, 400, 130))), "ja", 500, 700,
        )
        assertEquals(1, out.held)
        // A disjoint region is fresh — Level 0 dispatch.
        out = gate.filterFarGroups(
            listOf(far("メニュー項目", bounds = Rect(500, 500, 900, 560))), "ja", 1000, 1200,
        )
        assertEquals(1, out.dispatch.size)
    }

    @Test
    fun touchRegions_keepsSteadyStateMemoryAlive() {
        val gate = TypewriterGate()
        val t = armViaReveal(gate, 0)
        // Long steady display: the box is KEEP every cycle and never enters
        // the filter batch — touches stand in for those reads.
        gate.touchRegions(listOf(r), t + 40_000)
        // A later batch runs the eviction sweep. Untouched, the region's
        // lastSeen would be ~t and TTL-evicted here; the touch reset it.
        gate.filterFarGroups(emptyList(), "ja", t + 50_000, t + 50_000)
        // Arming must have survived via the touch.
        val out = gate.filterFarGroups(listOf(far("それでは、始め")), "ja", t + 80_000, t + 80_200)
        assertEquals("touched region memory survived; armed gating engaged", 1, out.held)
    }

    // ── Lever ─────────────────────────────────────────────────────────────

    @Test
    fun disabled_isPureLevelZero() {
        if (!TypewriterGate.ENABLED) {
            val gate = TypewriterGate()
            val shown = box("ABC")
            val out = gate.reconcile(verdicts(region("ABCDE", shown)), 0, 300, lang = "en")
            assertEquals(1, out.toTranslate.size)
            assertTrue(out.heldBoxes.isEmpty())
        } else {
            val gate = TypewriterGate()
            val shown = box("ABCDEFGH")
            val out = gate.reconcile(verdicts(region("ABCDEFGHIJKLM", shown)), 0, 300, lang = "en")
            assertTrue(out.toTranslate.isEmpty())
            assertNotNull(out.nextDeadlineMs)
        }
    }
}
