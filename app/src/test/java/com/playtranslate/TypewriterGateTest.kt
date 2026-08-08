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
 * (scoping, caps, sweep, slow-OCR self-disable) and covers the gate's own
 * behavior: boundary release, prefix dispatch, settle-based region arming
 * with origin-corner anchoring, the pinhole far-group adapter, and the
 * Thai legacy fallback.
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

    private fun far(
        text: String,
        bounds: Rect = r,
        paired: Boolean = false,
        orientation: TextOrientation = TextOrientation.HORIZONTAL,
        lineCount: Int = 1,
    ) = FarGroup(
        text = text, bounds = bounds, lineCount = lineCount,
        orientation = orientation, paired = paired,
    )

    private fun TypewriterGate.reconcile(
        v: ScanlineReconciler.Verdicts,
        captureAtMs: Long,
        nowMs: Long,
        lang: String = "ja",
        rtl: Boolean = false,
        prefix: Boolean = true,
    ) = filterVerdicts(v, lang, rtl, captureAtMs, nowMs, allowPartialPrefix = prefix)

    private fun TypewriterGate.fars(
        groups: List<FarGroup>,
        captureAtMs: Long,
        nowMs: Long,
        lang: String = "ja",
        rtl: Boolean = false,
    ) = filterFarGroups(groups, lang, rtl, captureAtMs, nowMs)

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
        var out = gate.fars(listOf(far("こんにちは、")), 0, 200)
        assertEquals(1, out.dispatch.size)
        // Fuller read with an interior boundary: whole-read gating only —
        // a prefix box over still-typing text would flash out.
        out = gate.fars(listOf(far("こんにちは、今日は晴れだ。それから")), 500, 700)
        assertTrue(out.dispatch.isEmpty())
        assertEquals(1, out.held)
        // Boundary-final read releases whole.
        out = gate.fars(listOf(far("こんにちは、今日は晴れだ。それから帰ろう。")), 1000, 1200)
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
        var out = gate.fars(listOf(far("こんにち")), 0, 200)
        assertEquals(1, out.dispatch.size)
        // Cycle B: fuller text arrives as an UNPAIRED far group. The region
        // memory recognizes the growth and holds it.
        out = gate.fars(listOf(far("こんにちは、旅の")), 1000, 1200)
        assertTrue(out.dispatch.isEmpty())
        assertEquals(1, out.held)
        assertNotNull(out.nextDeadlineMs)
    }

    @Test
    fun pairedFarGroup_bypassesTheGate() {
        val gate = TypewriterGate()
        gate.fars(listOf(far("こんにち")), 0, 200)
        // Evolving text, but paired: the content-match placement promise
        // must never break.
        val out = gate.fars(listOf(far("こんにちは、旅の", paired = true)), 1000, 1200)
        assertEquals(1, out.dispatch.size)
        assertEquals(0, out.held)
    }

    @Test
    fun emptyBatch_sweepsUnaffirmedHolds() {
        val gate = TypewriterGate()
        gate.fars(listOf(far("こんにち")), 0, 200)
        var out = gate.fars(listOf(far("こんにちは、旅の")), 500, 700)
        assertEquals(1, out.held)
        assertEquals(0, out.swept)
        // Next full look shows nothing at the region (garble evaporated /
        // region gone): the hold is swept — REPORTED, so the mode can
        // resolve a suppressed no-text signal — and the deadline clears.
        out = gate.fars(emptyList(), 1000, 1200)
        assertEquals(1, out.swept)
        assertNull(out.nextDeadlineMs)
    }

    @Test
    fun sweepEmptyBatch_closesHoldsAndClearsDeadline() {
        val gate = TypewriterGate()
        // The no-text early return is still a full look: holds must sweep
        // there too, or a stale expired deadline pins pacing at the floor
        // forever on a textless screen.
        gate.fars(listOf(far("こんにち")), 0, 200)
        val held = gate.fars(listOf(far("こんにちは、旅の")), 500, 700)
        assertEquals(1, held.held)
        assertNull(gate.sweepEmptyBatch(1200))
        // The hold is gone: fresh text at the region is Level 0 again.
        val out = gate.fars(listOf(far("まったく別の文章です")), 2000, 2200)
        assertEquals(1, out.dispatch.size)
    }

    // ── Arming: settled growth chains, origin-anchored ────────────────────

    private fun armViaReveal(gate: TypewriterGate, t0: Long): Long {
        // A reveal observed growing and settling arms the region. Seed
        // (Level 0 dispatch), grow (held), complete at a boundary.
        gate.fars(listOf(far("こんにち")), t0, t0 + 200)
        gate.fars(listOf(far("こんにちは、旅の")), t0 + 1000, t0 + 1200)
        val out = gate.fars(listOf(far("こんにちは、旅の人よ。")), t0 + 2000, t0 + 2200)
        assertEquals("arming reveal completes", 1, out.dispatch.size)
        return t0 + 2200
    }

    @Test
    fun punctlessSettledChain_armsRegion_thenGatesNextMessage() {
        val gate = TypewriterGate()
        // The field case (2026-07-22): dialogue WITHOUT terminal
        // punctuation. Message 1: fragment dispatches (the learning cost),
        // growth held, settles by agreement — and that settle ARMS.
        gate.fars(listOf(far("こんにち")), 0, 200)
        gate.fars(listOf(far("こんにちは、旅の人")), 1000, 1200)
        var out = gate.fars(listOf(far("こんにちは、旅の人")), 2000, 2200)
        assertEquals("punct-less settle releases", 1, out.dispatch.size)
        // Message 2: the first fragment is HELD — no punct needed anywhere.
        out = gate.fars(listOf(far("それでは、始め")), 3000, 3200)
        assertTrue("message-2 fragment suppressed by settle-based arming",
            out.dispatch.isEmpty())
        assertEquals(1, out.held)
        // Growth continues punct-less, then settles by agreement.
        out = gate.fars(listOf(far("それでは、始めようではないか")), 4000, 4200)
        assertEquals(1, out.held)
        out = gate.fars(listOf(far("それでは、始めようではないか")), 5000, 5200)
        assertEquals(listOf("それでは、始めようではないか"), out.dispatch.map { it.text })
    }

    @Test
    fun garbleRevertChain_doesNotArm() {
        val gate = TypewriterGate()
        gate.fars(listOf(far("Inventory")), 0, 200, lang = "en")
        // Garble reads as prefix-growth → hold opens.
        var out = gate.fars(listOf(far("Inventory ¦lem→")), 1000, 1200, lang = "en")
        assertEquals(1, out.held)
        // Garble evaporates: the revert is a PARTIAL VIEW of the garbled
        // read — and the correct text is already displayed, so holding
        // changes nothing on screen. No dispatch, no arm.
        out = gate.fars(listOf(far("Inventory")), 2000, 2200, lang = "en")
        assertTrue(out.dispatch.isEmpty())
        assertEquals(1, out.held)
        // The cap bounds the hold: the stable revert flushes ≤2s in.
        out = gate.fars(listOf(far("Inventory")), 3200, 3400, lang = "en")
        assertEquals(1, out.dispatch.size)
        // Fresh punct-less text dispatches immediately — nothing armed.
        out = gate.fars(listOf(far("Equipment list")), 4000, 4200, lang = "en")
        assertEquals(1, out.dispatch.size)
        assertEquals(0, out.held)
    }

    // ── Partial views: split/merge and occlusion reads of known text ──────

    @Test
    fun splitMergeLoop_partialViewsSuppressed_mergedReadReleases() {
        val gate = TypewriterGate()
        // The グラウス fixture (2026-07-22 field log): a 3-line block whose
        // grouping flips between one merged group and head/tail splits.
        val block = Rect(500, 800, 1240, 1000)
        val headRect = Rect(500, 800, 1240, 860)
        val tailRect = Rect(500, 870, 1240, 930)
        val full = "北の、グラウス山にモンスターが住みついて"
        val head = "北の、グラウス山に"
        val tail = "モンスターが住みついて"
        // Full read dispatches (Level 0).
        var out = gate.fars(listOf(far(full, bounds = block)), 0, 200)
        assertEquals(1, out.dispatch.size)
        // Split read: the head is a partial view (view-hold), and the tail
        // must NOT mint a second region — it is a sibling view of the same
        // block. Nothing dispatches; the old field behavior was a break
        // dispatch (top-line flash) plus a level0-new (stray tail box).
        out = gate.fars(
            listOf(far(head, bounds = headRect), far(tail, bounds = tailRect)),
            1000, 1200,
        )
        assertTrue("split pieces suppressed", out.dispatch.isEmpty())
        assertEquals(2, out.held)
        // Merged read agrees with the known text → releases through normal
        // agreement. The loop is dead.
        out = gate.fars(listOf(far(full, bounds = block)), 2000, 2200)
        assertEquals(listOf(full), out.dispatch.map { it.text })
        assertNull(out.nextDeadlineMs)
        assertTrue("split head counted as view-hold", gate.stats.viewHolds > 0)
        assertTrue("split tail counted as sibling view", gate.stats.siblingViews > 0)
    }

    @Test
    fun repeatDialogue_reReveal_growingViewsNeverFlush() {
        val gate = TypewriterGate()
        // The どうにか field fixture (2026-07-22 18:29 log): re-triggering a
        // KNOWN message re-types it — every mid-reveal read is a growing
        // subset of the stored text. Floor-paced reads cross the 1s view
        // cap mid-reveal; the old behavior shrink-cap-flushed the 19-char
        // partial (log c117/c126). Growing views slide the cap instead.
        val full = "どうにか、ラクしてハッピーになる手は、ないかな―"
        gate.fars(listOf(far(full)), 0, 200)
        var out = gate.fars(listOf(far("どうにか、")), 1000, 1100)
        assertTrue(out.dispatch.isEmpty())
        out = gate.fars(listOf(far("どうにか、ラクして、")), 1500, 1600)
        assertTrue(out.dispatch.isEmpty())
        // Past the original 1s anchor — a growing view must NOT flush.
        out = gate.fars(listOf(far("どうにか、ラクしてハッピーになる手")), 2100, 2200)
        assertTrue("growing view past the cap must not flush", out.dispatch.isEmpty())
        // Full text lands → agreement with the known text → one dispatch.
        out = gate.fars(listOf(far(full)), 2600, 2700)
        assertEquals(listOf(full), out.dispatch.map { it.text })
    }

    @Test
    fun genuineShrink_viewHoldFlushesAtShortCap() {
        val gate = TypewriterGate()
        gate.fars(listOf(far("はい、そうですよねわかりました")), 0, 200)
        // The message really changed to a contained shorter text: view-held
        // first (indistinguishable from a split read)...
        var out = gate.fars(listOf(far("わかりました")), 1000, 1100)
        assertTrue(out.dispatch.isEmpty())
        assertEquals(1, out.held)
        // ...then the short cap flushes it — bounded staleness, ~1s.
        out = gate.fars(listOf(far("わかりました")), 2200, 2300)
        assertEquals(listOf("わかりました"), out.dispatch.map { it.text })
    }

    @Test
    fun punctuationRunVariance_foldedForComparison() {
        val gate = TypewriterGate()
        // The へんじがない field fixture (2026-07-22): the ellipsis run OCRs
        // with a different glyph inventory every read (・・・・ / ・・. / ・・…)
        // and the dash flips ー/―. Raw comparison saw every read as a real
        // change — Level 0 churn, no chains, no arming.
        gate.fars(listOf(far("へんじがない・・・・ただのカカー")), 0, 200)
        // Folded, the fuller read is growth (tail-trim absorbs the ragged
        // edge) → hold.
        var out = gate.fars(listOf(far("へんじがない・・.ただのカカシのようだ―")), 1000, 1200)
        assertTrue(out.dispatch.isEmpty())
        assertEquals(1, out.held)
        // Agreement releases and ARMS.
        out = gate.fars(listOf(far("へんじがない・・.ただのカカシのようだ―")), 2000, 2200)
        assertEquals(1, out.dispatch.size)
        // Re-trigger (the user keeps talking to the scarecrow): the fresh
        // partial is a folded VIEW of the known text → suppressed.
        out = gate.fars(listOf(far("へんじがない・・…ただのカー")), 3000, 3200)
        assertTrue(out.dispatch.isEmpty())
        assertEquals(1, out.held)
    }

    @Test
    fun multiLineRead_matchesItsChain_notTheWidestStaleMemory() {
        val gate = TypewriterGate()
        // The その調子 field fixture (2026-07-22): the previous message left
        // two one-line memories; the new message's multi-line read overlaps
        // BOTH, and the stale line-2 memory has the bigger intersection.
        // Pure best-overlap matched the stranger — relation-aware matching
        // must pick the chain the read actually continues.
        gate.fars(
            listOf(
                far("カカシにまで話しかけるとは、", bounds = Rect(522, 812, 1303, 872)),
                far("見上げた心がけだ・.", bounds = Rect(553, 879, 1082, 938)),
            ),
            0, 200,
        )
        // New message's fragment claims the line-1 chain (Level 0, unarmed).
        var out = gate.fars(listOf(far("その調子で、", bounds = Rect(548, 812, 822, 869))), 1000, 1200)
        assertEquals(1, out.dispatch.size)
        // The full 3-line read: bigger overlap with the stale line-2 memory,
        // but an exact prefix relation with the fragment's chain — growth,
        // held (the field log dispatched this as `advance`).
        val full = "その調子で、これからも―いろんな物に、話しかけたり調べたりすると、"
        out = gate.fars(listOf(far(full, bounds = Rect(546, 813, 1239, 1005))), 2000, 2200)
        assertTrue("growth recognized despite overlap preferring the stale memory",
            out.dispatch.isEmpty())
        assertEquals(1, out.held)
        // Agreement releases the full text.
        out = gate.fars(listOf(far(full, bounds = Rect(546, 813, 1239, 1005))), 3000, 3200)
        assertEquals(listOf(full), out.dispatch.map { it.text })
    }

    // ── Flow-adjacency: split pieces defer on the active reveal ───────────

    @Test
    fun freshRegion_flowAfterActiveHold_deferredAndReleasedInOrder() {
        val gate = TypewriterGate()
        val top = Rect(500, 800, 1240, 860)
        val below = Rect(500, 865, 1240, 925)
        gate.fars(listOf(far("こんにち", bounds = top)), 0, 200)
        gate.fars(listOf(far("こんにちは、旅の", bounds = top)), 1000, 1200) // grow-hold
        // The reveal reaches line 2, which grouping split off: fresh text
        // directly flow-after the active hold must not Level-0 past it.
        var out = gate.fars(
            listOf(
                far("こんにちは、旅の人よ、聞け", bounds = top),
                far("まったく別の続きの内容", bounds = below),
            ),
            1500, 1700,
        )
        assertTrue("split bottom deferred with the top", out.dispatch.isEmpty())
        assertEquals(2, out.held)
        // Both settle → both release in reading order, one batch.
        out = gate.fars(
            listOf(
                far("こんにちは、旅の人よ、聞け", bounds = top),
                far("まったく別の続きの内容", bounds = below),
            ),
            2000, 2200,
        )
        assertEquals(listOf("こんにちは、旅の人よ、聞け", "まったく別の続きの内容"),
            out.dispatch.map { it.text })
    }

    @Test
    fun battleMenu_insideOldMessageArea_noActiveReveal_dispatchesImmediately() {
        val gate = TypewriterGate()
        val top = Rect(500, 800, 1240, 860)
        // Full reveal settles and releases (grace stamps at release).
        gate.fars(listOf(far("こんにち", bounds = top)), 0, 200)
        gate.fars(listOf(far("こんにちは、旅の", bounds = top)), 1000, 1200)
        gate.fars(listOf(far("こんにちは、旅の", bounds = top)), 1500, 1700) // agree-release
        // Seconds later a battle menu renders inside the old message area
        // (away from the armed origin corner): no hold, grace expired —
        // zero deferral. The Stage-A fatal flaw, pinned.
        val out = gate.fars(
            listOf(far("たたかう", bounds = Rect(700, 825, 1000, 855))), 4000, 4200,
        )
        assertEquals(1, out.dispatch.size)
        assertEquals(0, out.held)
    }

    @Test
    fun nameplate_flowBeforeActiveHold_exempt() {
        val gate = TypewriterGate()
        val top = Rect(500, 800, 1240, 860)
        gate.fars(listOf(far("こんにち", bounds = top)), 0, 200)
        gate.fars(listOf(far("こんにちは、旅の", bounds = top)), 1000, 1200) // hold open
        // A nameplate ABOVE the reveal is flow-before — dispatches on sight.
        val out = gate.fars(
            listOf(far("むらびと", bounds = Rect(500, 730, 800, 790))), 1500, 1700,
        )
        assertEquals(1, out.dispatch.size)
        assertEquals(0, out.held)
    }

    @Test
    fun releaseGrace_coversTheDetectionMissRace() {
        val gate = TypewriterGate()
        val top = Rect(500, 800, 1240, 860)
        gate.fars(listOf(far("こんにち", bounds = top)), 0, 200)
        gate.fars(listOf(far("こんにちは、旅の", bounds = top)), 1000, 1200)
        gate.fars(listOf(far("こんにちは、旅の", bounds = top)), 1500, 1700) // top releases
        // Detection missed the nascent bottom line for a cycle; it appears
        // just AFTER the top released — the grace still defers it.
        var out = gate.fars(
            listOf(far("おくれてきた続きの行", bounds = Rect(500, 865, 1240, 925))), 2100, 2300,
        )
        assertEquals("grace defers the late bottom line", 1, out.held)
        // It settles and releases normally.
        out = gate.fars(
            listOf(far("おくれてきた続きの行", bounds = Rect(500, 865, 1240, 925))), 2600, 2800,
        )
        assertEquals(1, out.dispatch.size)
    }

    @Test
    fun vertical_flowAfterIsLeftward() {
        val gate = TypewriterGate()
        val rightCol = Rect(900, 100, 1000, 600)
        fun col(text: String, bounds: Rect) =
            far(text, bounds = bounds, orientation = TextOrientation.VERTICAL)
        gate.fars(listOf(col("こん", rightCol)), 0, 200)
        gate.fars(listOf(col("こんにちは、旅の", rightCol)), 1000, 1200) // hold open
        // Next columns split off to the LEFT (flow-after) → deferred. The
        // held column keeps growing in the same batches so its hold stays
        // affirmed and open.
        var out = gate.fars(
            listOf(
                col("こんにちは、旅の人よ聞くが", rightCol),
                col("別のつづきの列", Rect(760, 100, 880, 600)),
            ),
            1500, 1700,
        )
        assertTrue(out.dispatch.isEmpty())
        assertEquals(2, out.held)
        // Text to the RIGHT is flow-before (ruby/label side) → exempt even
        // with the hold still open.
        out = gate.fars(
            listOf(
                col("こんにちは、旅の人よ聞くがいいだろう", rightCol),
                col("ラベル", Rect(1010, 100, 1100, 600)),
            ),
            1900, 2100,
        )
        assertEquals(listOf("ラベル"), out.dispatch.map { it.text })
    }

    // ── Same-content bands: garble must not break an active chain ────────

    @Test
    fun garbleBand_wedgedHold_recoversViaAgreement() {
        val gate = TypewriterGate()
        // Field c9-c13: the hold opened on a GARBLED full read; the correct
        // reads could neither agree (too different) nor break (subset-ish)
        // — wedged until a break dispatched a partial. The garble band
        // adopts the newer read; its identical repeat releases clean.
        gate.fars(listOf(far("こんにち")), 0, 200)
        gate.fars(listOf(far("こんにちは、旅の人よ聞くがいい")), 1000, 1200) // garbled full
        var out = gate.fars(listOf(far("こんにちは、旅の人に聞くがよさ")), 1500, 1700)
        assertTrue("band adopts the variant, no dispatch", out.dispatch.isEmpty())
        assertEquals(1, out.held)
        out = gate.fars(listOf(far("こんにちは、旅の人に聞くがよさ")), 2000, 2200)
        assertEquals(listOf("こんにちは、旅の人に聞くがよさ"), out.dispatch.map { it.text })
        assertEquals("no break was recorded", 0, gate.stats.breaks)
    }

    @Test
    fun growBand_garbledPrefixGrowth_staysHeld() {
        val gate = TypewriterGate()
        // Field c33: a growth read whose garbled PREFIX fails the strict
        // evolving check must not dispatch as a break — it is the same
        // reveal, garbled.
        gate.fars(listOf(far("ABCDEFGH")), 0, 200, lang = "en")
        gate.fars(listOf(far("ABCDEFGHIJKL")), 1000, 1200, lang = "en")
        var out = gate.fars(listOf(far("AXCDEFGHIJKLMNOP")), 1500, 1700, lang = "en")
        assertTrue("garbled growth held, not broken", out.dispatch.isEmpty())
        assertEquals(1, out.held)
        assertEquals(0, gate.stats.breaks)
        // A clean boundary-final read releases the chain.
        out = gate.fars(listOf(far("AXCDEFGHIJKLMNOPQRS.")), 2000, 2200, lang = "en")
        assertEquals(1, out.dispatch.size)
    }

    @Test
    fun deepTailGarble_growthStillRecognized() {
        val gate = TypewriterGate()
        // Field c101-c102 (22:18): the first catch's rasterizing edge
        // hallucinated THREE glyphs (、Nッ) — beyond the shared 2-char tail
        // trim — and the cleaner growth read broke the chain and dispatched
        // a partial. The deep-garble second chance keeps it held.
        gate.fars(listOf(far("どうにか")), 0, 200)
        gate.fars(listOf(far("どうにか、ラクして、Nッ")), 1000, 1200) // garbled edge
        var out = gate.fars(listOf(far("どうにか、ラクしてハッピーになる手")), 1500, 1700)
        assertTrue("garbled-edge growth held, not broken", out.dispatch.isEmpty())
        assertEquals(1, out.held)
        assertEquals(0, gate.stats.breaks)
        out = gate.fars(listOf(far("どうにか、ラクしてハッピーになる手")), 2000, 2200)
        assertEquals(1, out.dispatch.size)
    }

    @Test
    fun shrunkWithinTolerance_heldAsViewNotReplaced() {
        val gate = TypewriterGate()
        // The ウールオル specimen (2026-07-22 23:49): the sign's partial is
        // exactly Δ=3 (街道。) short of the full text — inside the replace
        // path's absolute tolerance — and replace-looped partials forever.
        gate.fars(listOf(far("この先、ウールオル街道。")), 0, 200)
        var out = gate.fars(listOf(far("この先、ウールオル")), 1000, 1200)
        assertTrue("shrunk read is a view, not a re-show", out.dispatch.isEmpty())
        assertEquals(1, out.held)
        // The full read releases by agreement one read later.
        out = gate.fars(listOf(far("この先、ウールオル街道。")), 1500, 1700)
        assertEquals(listOf("この先、ウールオル街道。"), out.dispatch.map { it.text })
    }

    @Test
    fun grownWithinTolerance_stillReplacesInstantly() {
        val gate = TypewriterGate()
        // The good direction is preserved: the FULL text arriving over a
        // displayed partial (Δ=3 grown) replaces on sight.
        gate.fars(listOf(far("この先、ウールオル")), 0, 200)
        val out = gate.fars(listOf(far("この先、ウールオル街道。")), 1000, 1200)
        assertEquals(listOf("この先、ウールオル街道。"), out.dispatch.map { it.text })
        assertEquals(0, out.held)
    }

    @Test
    fun releasedHold_appearsOnceInProbeSnapshot() {
        val gate = TypewriterGate()
        gate.fars(listOf(far("こんにち")), 0, 200)
        gate.fars(listOf(far("こんにちは、旅の")), 1000, 1200) // grow-open
        assertEquals(1, gate.quietProbeSnapshot().count { !it.released })
        // Agreement release: the snapshot's entry is flagged released so
        // the probe can run the final endpoint comparison.
        gate.fars(listOf(far("こんにちは、旅の")), 1500, 1700)
        val snap = gate.quietProbeSnapshot()
        assertEquals(1, snap.size)
        assertTrue(snap[0].released)
        // Next batch: gone.
        gate.fars(emptyList(), 2000, 2200)
        assertTrue(gate.quietProbeSnapshot().isEmpty())
    }

    // ── Contained split pieces and release ordering ───────────────────────

    @Test
    fun mergedChainRect_containsSplitPiece_stillDefers() {
        val gate = TypewriterGate()
        // Field c267: the held chain's rect had MERGED both lines, so the
        // split-off line 2 sat INSIDE it — the old gap band read that as
        // flow-before and let it Level-0 past the hold.
        val twoLines = Rect(500, 800, 1240, 940)
        gate.fars(listOf(far("「やれやれ……これが全部", bounds = twoLines, lineCount = 2)), 0, 200)
        gate.fars(
            listOf(far("「やれやれ……これが全部わしの", bounds = twoLines, lineCount = 2)),
            1000, 1200,
        ) // grow-hold
        val out = gate.fars(
            listOf(far("これが全部わしのもの", bounds = Rect(554, 881, 1100, 935))), 1500, 1700,
        )
        assertTrue("contained split piece deferred", out.dispatch.isEmpty())
        assertEquals(1, out.held)
    }

    @Test
    fun chainRelease_waitsForFlowBeforeHold() {
        val gate = TypewriterGate()
        val top = Rect(500, 800, 1240, 860)
        val below = Rect(500, 865, 1240, 925)
        // Line 2's chain reaches its boundary while line 1 still holds —
        // field c269 released it out of order. Now the release waits.
        gate.fars(listOf(far("うえのぎょう", bounds = top)), 0, 200)
        gate.fars(listOf(far("うえのぎょうがもっとつづく", bounds = top)), 1000, 1200) // line-1 hold
        gate.fars(listOf(far("したのぎょう", bounds = below)), 1400, 1600) // deferred (adjacent)
        var out = gate.fars(
            listOf(
                far("うえのぎょうがもっとつづくながくなる", bounds = top), // still growing
                far("したのぎょうおわり。", bounds = below), // boundary-final
            ),
            1800, 2000,
        )
        assertTrue("line 2's boundary release waits for line 1", out.dispatch.isEmpty())
        assertEquals(2, out.held)
        // Line 1 settles → both release, reading order preserved.
        out = gate.fars(
            listOf(
                far("うえのぎょうがもっとつづくながくなる", bounds = top),
                far("したのぎょうおわり。", bounds = below),
            ),
            2300, 2500,
        )
        assertEquals(listOf("うえのぎょうがもっとつづくながくなる", "したのぎょうおわり。"),
            out.dispatch.map { it.text })
    }

    @Test
    fun replaceReshow_flowAfterActiveHold_deferred() {
        val gate = TypewriterGate()
        val top = Rect(500, 800, 1240, 860)
        val below = Rect(500, 865, 1240, 925)
        // A repeat viewing: line 2's text is already remembered, so its
        // re-read is an identical REPLACE — which used to slip past the
        // deferral while line 1 was still being typed.
        gate.fars(listOf(far("したのぎょうのないよう", bounds = below)), 0, 200)
        gate.fars(listOf(far("うえのぎょ", bounds = top)), 6000, 6200)
        gate.fars(listOf(far("うえのぎょうがもっとつづく", bounds = top)), 7000, 7200) // hold
        var out = gate.fars(
            listOf(
                far("うえのぎょうがもっとつづくながくなる", bounds = top),
                far("したのぎょうのないよう", bounds = below), // identical re-show
            ),
            7500, 7700,
        )
        assertTrue("identical re-show deferred behind the reveal", out.dispatch.isEmpty())
        assertEquals(2, out.held)
        // Line 1 settles → both release in order.
        out = gate.fars(
            listOf(
                far("うえのぎょうがもっとつづくながくなる", bounds = top),
                far("したのぎょうのないよう", bounds = below),
            ),
            8000, 8200,
        )
        assertEquals(listOf("うえのぎょうがもっとつづくながくなる", "したのぎょうのないよう"),
            out.dispatch.map { it.text })
    }

    // ── Thrash breaker: unknown pathologies fail open to Level 0 ──────────

    @Test
    fun thrashBreaker_tripsOnRepeatedBreaks_failsOpenToLevelZero() {
        val gate = TypewriterGate()
        gate.fars(listOf(far("ABCDEFGH")), 0, 200, lang = "en")
        // A pathological stream: growth holds broken by disjoint text,
        // over and over — the shape of every un-enumerated loop.
        gate.fars(listOf(far("ABCDEFGHIJKLM")), 1000, 1200, lang = "en")   // hold
        gate.fars(listOf(far("QWERTY UIOP")), 2000, 2200, lang = "en")    // break 1
        gate.fars(listOf(far("QWERTY UIOPZXCVB")), 3000, 3200, lang = "en") // hold
        gate.fars(listOf(far("ABCDEFGH")), 4000, 4200, lang = "en")       // break 2
        gate.fars(listOf(far("ABCDEFGHIJKLM")), 5000, 5200, lang = "en")  // hold
        var out = gate.fars(listOf(far("QWERTY UIOP")), 6000, 6200, lang = "en") // break 3 → trip
        assertEquals(1, out.dispatch.size)
        assertEquals(1, gate.stats.breakerTrips)
        // Tripped: evolving text that would normally hold now dispatches on
        // sight — bounded baseline churn, never a novel failure mode.
        out = gate.fars(listOf(far("QWERTY UIOPASDFG")), 7000, 7200, lang = "en")
        assertEquals("breaker fails open to Level 0", 1, out.dispatch.size)
        assertEquals(0, out.held)
    }

    @Test
    fun capFlushedGrowthChain_arms() {
        val gate = TypewriterGate()
        gate.fars(listOf(far("ABCDEFGH")), 0, 200, lang = "en")
        gate.fars(listOf(far("ABCDEFGHIJKLM")), 1000, 1200, lang = "en")
        // Still growing at the cap → flush — a slow reveal is still a
        // settled-enough observation.
        var out = gate.fars(listOf(far("ABCDEFGHIJKLMNOPQRST")), 3200, 3400, lang = "en")
        assertEquals(1, out.dispatch.size)
        // The region is armed: a punct-less advance is held.
        out = gate.fars(listOf(far("XYZ QRS")), 4000, 4100, lang = "en")
        assertEquals(1, out.held)
    }

    @Test
    fun armedRegion_nextMessageFragment_neverDisplays() {
        val gate = TypewriterGate()
        val t = armViaReveal(gate, 0)
        // Message 2 starts typing: a mid-sentence ADVANCE in an armed
        // region is sentence-gated from its FIRST read.
        var out = gate.fars(listOf(far("それでは、始め")), t + 1000, t + 1200)
        assertTrue("message-2 first fragment suppressed", out.dispatch.isEmpty())
        assertEquals(1, out.held)
        // Completion read releases itself.
        out = gate.fars(listOf(far("それでは、始めよう。")), t + 2000, t + 2200)
        assertEquals(listOf("それでは、始めよう。"), out.dispatch.map { it.text })
    }

    @Test
    fun armedRegion_instantPunctFinalMessage_zeroPenalty() {
        val gate = TypewriterGate()
        val t = armViaReveal(gate, 0)
        // An instant, complete message in an armed region dispatches on the
        // read that discovered it.
        val out = gate.fars(listOf(far("戦闘開始だ。")), t + 1000, t + 1200)
        assertEquals(1, out.dispatch.size)
        assertEquals(0, out.held)
    }

    @Test
    fun armedRegion_punctlessInstant_waitsOneAgreementRead_cappedAtInterval() {
        val gate = TypewriterGate()
        val t = armViaReveal(gate, 0)
        // Punct-less instant text (the priced residual): held with the
        // SHORT armed cap — the shipped-cadence law.
        var out = gate.fars(listOf(far("はい")), t + 1000, t + 1100)
        assertTrue(out.dispatch.isEmpty())
        assertEquals(t + 1000 + TypewriterGate.ARMED_NEW_MAX_MS, out.nextDeadlineMs)
        // Next read agrees → released.
        out = gate.fars(listOf(far("はい")), t + 2000, t + 2100)
        assertEquals(1, out.dispatch.size)
        assertNull(out.nextDeadlineMs)
    }

    @Test
    fun unarmedFirstMessage_fragmentStillTranslates_baselineNotRegressed() {
        val gate = TypewriterGate()
        // Message 1 of a session: no evidence yet → Level 0 first response,
        // exactly today's behavior.
        val out = gate.fars(listOf(far("私は彼を殺し")), 0, 200)
        assertEquals(1, out.dispatch.size)
    }

    @Test
    fun clearHolds_keepsArming_fullClearDropsIt() {
        val gate = TypewriterGate()
        val t = armViaReveal(gate, 0)
        // The pinhole input path clears holds per dismiss — arming survives.
        gate.clearHolds()
        var out = gate.fars(listOf(far("それでは、始め")), t + 1000, t + 1200)
        assertEquals("armed gating survives a dismiss", 1, out.held)
        // A coordinate-voiding reset drops everything.
        gate.clear()
        out = gate.fars(listOf(far("それでは、始め")), t + 2000, t + 2200)
        assertEquals(0, out.held)
        assertEquals(1, out.dispatch.size)
    }

    // ── Dead-lineage drop: a hold on a NEW message erases the old box ─────

    @Test
    fun armedAdvance_dropsDeadBoxImmediately_insteadOfHoldingIt() {
        val gate = TypewriterGate()
        // Arm via a revealed-and-settled chain on the reconciler path.
        gate.reconcile(verdicts(region("こんにち")), 0, 200)
        val frag = box("こんにち")
        var out = gate.reconcile(verdicts(region("こんにちは、旅の", frag)), 1000, 1200)
        assertEquals("growth holds keep their fragment box", listOf(frag), out.heldBoxes)
        assertTrue(out.dropNow.isEmpty())
        out = gate.reconcile(verdicts(region("こんにちは、旅の人よ。", frag)), 2000, 2200)
        assertEquals(1, out.toTranslate.size) // boundary release + arm
        // Message 2 starts typing over the displayed message-1 box: the
        // armed hold opens AND the dead box is erased now — not rendered
        // through the hold as "nothing happened".
        val shown = box("こんにちは、旅の人よ。")
        out = gate.reconcile(verdicts(region("それでは、始め", shown)), 3000, 3200)
        assertTrue(out.toTranslate.isEmpty())
        assertTrue("dead lineage must not render through the hold", out.heldBoxes.isEmpty())
        assertEquals(listOf(shown), out.dropNow)
        assertEquals(3000L + TypewriterGate.ARMED_NEW_MAX_MS, out.nextDeadlineMs)
        // Completion read releases. The dropped box left the display, so
        // the release arrives with no replacesBox — nothing to double-drop.
        out = gate.reconcile(verdicts(region("それでは、始めよう。")), 4000, 4200)
        assertEquals(listOf("それでは、始めよう。"), out.toTranslate.map { it.text })
        assertTrue(out.dropNow.isEmpty())
    }

    @Test
    fun revealAdjacentAdvance_dropsDeadBox() {
        val gate = TypewriterGate()
        val top = Rect(500, 800, 1240, 860)
        val below = Rect(500, 865, 1240, 925)
        gate.reconcile(verdicts(region("こんにち", bounds = top)), 0, 200)
        val topFrag = box("こんにち")
        gate.reconcile(verdicts(region("こんにちは、旅の", topFrag, bounds = top)), 1000, 1200)
        // The reveal reaches a split-off line 2 whose region still displays
        // the PREVIOUS message's line 2: deferred with the top — and the
        // dead line-2 box is erased rather than held.
        val staleLine2 = box("古い二行目のメッセージ")
        val out = gate.reconcile(
            verdicts(
                region("こんにちは、旅の人よ、聞け", topFrag, bounds = top),
                region("つづきの新しい二行目", staleLine2, bounds = below),
            ),
            1500, 1700,
        )
        assertTrue(out.toTranslate.isEmpty())
        assertEquals(
            "same-chain fragment stays, dead line-2 does not",
            listOf(topFrag), out.heldBoxes,
        )
        assertEquals(listOf(staleLine2), out.dropNow)
    }

    @Test
    fun viewHold_keepsDisplayedBox_noDrop() {
        val gate = TypewriterGate()
        gate.reconcile(verdicts(region("Inventory Item List Warp")), 0, 300, lang = "en")
        // A split/occluded SHORT read of the known text with its box still
        // displayed: view-hold — the box shows the right translation and
        // must stay up.
        val shown = box("Inventory Item List Warp")
        val out = gate.reconcile(verdicts(region("Inventory Item", shown)), 1000, 1300, lang = "en")
        assertTrue(out.toTranslate.isEmpty())
        assertEquals(listOf(shown), out.heldBoxes)
        assertTrue(out.dropNow.isEmpty())
    }

    // ── Origin-corner anchoring ───────────────────────────────────────────

    @Test
    fun armedGating_requiresOriginCorner() {
        val gate = TypewriterGate()
        armViaReveal(gate, 0) // armed origin = (0,0), tol = 0.6 × 60px = 36
        // A name-plate-like element INSIDE the armed area: overlaps the
        // region, but its start corner is nowhere near the origin — must
        // NOT be held.
        var out = gate.fars(listOf(far("リーダー", bounds = Rect(150, 10, 390, 55))), 3000, 3200)
        assertEquals("overlapping non-origin text dispatches immediately", 1, out.dispatch.size)
        assertEquals(0, out.held)
        // The learned anchor survives that pass-through chain: a fragment
        // AT the origin is still armed-gated.
        out = gate.fars(listOf(far("それでは、始め", bounds = r)), 4000, 4200)
        assertEquals(1, out.held)
    }

    @Test
    fun rtlOrigin_isTopRight() {
        val gate = TypewriterGate()
        // RTL reveal: lines grow leftward from a fixed top-RIGHT origin.
        gate.fars(listOf(far("مرحبا", bounds = Rect(250, 0, 400, 60))), 0, 200, lang = "ar", rtl = true)
        gate.fars(listOf(far("مرحبا بك أيها", bounds = Rect(150, 0, 400, 60))), 1000, 1200, lang = "ar", rtl = true)
        var out = gate.fars(listOf(far("مرحبا بك أيها", bounds = Rect(150, 0, 400, 60))), 2000, 2200, lang = "ar", rtl = true)
        assertEquals(1, out.dispatch.size) // settle → armed at (400, 0)
        // Right-aligned short message shares the origin → armed-held.
        out = gate.fars(listOf(far("نعم", bounds = Rect(320, 0, 400, 60))), 3000, 3200, lang = "ar", rtl = true)
        assertEquals(1, out.held)
    }

    @Test
    fun verticalOrigin_isTopRight() {
        val gate = TypewriterGate()
        // Vertical columns run right-to-left: origin = top-right; the box
        // grows LEFT as columns are added.
        gate.fars(listOf(far("こん", bounds = Rect(340, 0, 400, 200), orientation = TextOrientation.VERTICAL)), 0, 200)
        gate.fars(listOf(far("こんにちは、旅", bounds = Rect(280, 0, 400, 200), orientation = TextOrientation.VERTICAL)), 1000, 1200)
        var out = gate.fars(listOf(far("こんにちは、旅", bounds = Rect(280, 0, 400, 200), orientation = TextOrientation.VERTICAL)), 2000, 2200)
        assertEquals(1, out.dispatch.size) // settle → armed at (400, 0)
        // A short vertical message sharing the top-right origin is gated.
        out = gate.fars(listOf(far("はい", bounds = Rect(360, 0, 400, 120), orientation = TextOrientation.VERTICAL)), 3000, 3200)
        assertEquals(1, out.held)
    }

    // ── Armed memory lifetime ─────────────────────────────────────────────

    @Test
    fun armedEntry_survivesQuietStretch_thenLapsesAndEvicts() {
        val gate = TypewriterGate()
        val t = armViaReveal(gate, 0)
        // A long quiet stretch (cutscene) — far past MEMORY_TTL. An
        // unarmed entry would evict on this sweep; armed is exempt.
        gate.fars(emptyList(), t + 60_000, t + 60_000)
        var out = gate.fars(listOf(far("それでは、始め")), t + 70_000, t + 70_200)
        assertEquals("arming survived the quiet stretch", 1, out.held)
        // Let arming lapse un-refreshed; normal eviction then applies.
        val lapsed = t + TypewriterGate.ARM_TTL_MS + 10_000
        gate.fars(emptyList(), lapsed, lapsed)
        out = gate.fars(listOf(far("それでは、始め")), lapsed + 1_000, lapsed + 1_200)
        assertEquals("lapsed region is back to Level 0", 1, out.dispatch.size)
    }

    @Test
    fun observedGrowth_refreshesArming() {
        val gate = TypewriterGate()
        val t = armViaReveal(gate, 0) // armed until ~t + ARM_TTL
        // A later reveal's growth refreshes the TTL...
        gate.fars(listOf(far("次の話だ")), t + 100_000, t + 100_200)
        gate.fars(listOf(far("次の話だがもう遅い")), t + 101_000, t + 101_200)
        gate.fars(listOf(far("次の話だがもう遅い")), t + 102_000, t + 102_200)
        // ...so past the ORIGINAL expiry the region is still armed.
        val out = gate.fars(
            listOf(far("まだ続くのか")),
            t + TypewriterGate.ARM_TTL_MS + 50_000,
            t + TypewriterGate.ARM_TTL_MS + 50_200,
        )
        assertEquals(1, out.held)
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

    // ── Thai: no boundary convention → legacy hold, no arming ─────────────

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
        // Settles by agreement — which arms in punct-using scripts, but th
        // stays legacy.
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
        gate.fars(listOf(far("こんにち", bounds = Rect(0, 0, 400, 60))), 0, 200)
        // The fuller read's rect grew a line taller — the chain-start rect
        // sits inside it, so it still matches (held as growth).
        var out = gate.fars(listOf(far("こんにちは、旅の", bounds = Rect(0, 0, 400, 130))), 500, 700)
        assertEquals(1, out.held)
        // A disjoint region is fresh — Level 0 dispatch.
        out = gate.fars(listOf(far("メニュー項目", bounds = Rect(500, 500, 900, 560))), 1000, 1200)
        assertEquals(1, out.dispatch.size)
    }

    @Test
    fun touchRegions_keepsSteadyStateMemoryAlive() {
        val gate = TypewriterGate()
        // Unarmed memory (a single dispatched read — no growth observed).
        gate.fars(listOf(far("こんにち")), 0, 200)
        // Long steady display: touches stand in for KEEP cycles.
        gate.touchRegions(listOf(r), 40_000)
        // A later batch runs the eviction sweep. Untouched, lastSeen would
        // be ~0 and the entry TTL-evicted here; the touch reset it.
        gate.fars(emptyList(), 50_000, 50_000)
        // The remembered dispatch still anchors growth detection.
        val out = gate.fars(listOf(far("こんにちは、旅の")), 60_000, 60_200)
        assertEquals("touched memory survived; growth recognized", 1, out.held)
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

    // ── Slanted reads: the origin corner is the ORIENTED corner ─────────

    @Test
    fun startCorner_slantedRead_resolvesTheOrientedCorner() {
        // 300×48 strip at −20°, AABB centered (200, 150). Char #1 renders at
        // the oriented top-left rotated about the center — cos(−20)=0.9397,
        // sin(−20)=−0.342: (200 − 150·0.9397 − 24·0.342, 150 + 150·0.342 −
        // 24·0.9397) ≈ (51, 179) — nowhere near the AABB's (51, 76).
        val bounds = Rect(51, 76, 349, 224)
        val (x, y) = TypewriterGate.startCorner(
            bounds, TextOrientation.HORIZONTAL, rtl = false,
            angleDeg = -20f, orientedW = 300f, orientedH = 48f,
        )
        assertEquals(51.0, x.toDouble(), 2.0)
        assertEquals(179.0, y.toDouble(), 2.0)
        // RTL starts at the oriented top-RIGHT — inside the AABB (333, 76),
        // not the AABB corner (349, 76).
        val (rx, ry) = TypewriterGate.startCorner(
            bounds, TextOrientation.HORIZONTAL, rtl = true,
            angleDeg = -20f, orientedW = 300f, orientedH = 48f,
        )
        assertEquals(333.0, rx.toDouble(), 2.0)
        assertEquals(76.0, ry.toDouble(), 3.0)
        // Upright reads keep the plain AABB corner bit-for-bit.
        assertEquals(
            bounds.left to bounds.top,
            TypewriterGate.startCorner(bounds, TextOrientation.HORIZONTAL, rtl = false),
        )
    }
}
