package com.playtranslate

import android.graphics.Rect
import com.playtranslate.ui.TextBox

/**
 * Sentence-gated dispatch for evolving (typewriter) text — the successor to
 * `StabilityHold`, shared by BOTH live tiers ([ReconcilerLiveMode] via
 * [filterVerdicts], [PinholeOverlayMode] via [filterFarGroups]).
 *
 * ## The invariant
 * A mid-sentence prefix of a verb-final language is not an incomplete
 * translation — it is a WRONG one. So: once a region is known to be
 * evolving (its text extended a prior read), translate only sentence-
 * complete text. Three release signals, cheapest first:
 *
 *  1. **Boundary** ([SentenceBoundary]) — a read whose tail is a sentence
 *     terminal releases ITSELF: no confirming read, no timer, zero added
 *     latency. Covers the dominant dialogue case in every punct-using
 *     script.
 *  2. **Agreement** — two consecutive reads with the same text (GSM
 *     parity). The releasing read is one the cycle loop was going to do
 *     anyway; the only cost is latency, and only for punct-less endings.
 *  3. **Cap** — a wall-clock bound anchored at the CAPTURE time of the read
 *     that opened the hold. Marquees flush; and on a device whose single
 *     OCR pass exceeds the cap, holds expire before they can open —
 *     self-disabling into pure translate-on-sight, zero added latency on
 *     slow hardware by construction.
 *
 * ## Cost envelope (the design's whole point)
 * The gate adds ZERO reads and ZERO timers on every device class: it only
 * ever withholds or substitutes translation dispatches, and it never
 * dispatches at a read where Level 0 would not also have dispatched. Its
 * one scheduling need — a releasing read after a reveal that finishes into
 * a static screen — is served by exposing the earliest open cap deadline
 * ([Outcome.nextDeadlineMs] / [FarOutcome.nextDeadlineMs]) for the owner's
 * existing pacing/park plumbing.
 *
 * ## Region memory and arming
 * State is keyed by REGION (rect overlap, not box identity): pinhole
 * removes a changed box before its fuller text re-arrives as an unpaired
 * far group, and a reconciler NEW entry may repeat while its first
 * translation is in flight — box identity dies at exactly the moments this
 * gate must remember. A region whose evolving text reached a sentence
 * boundary gets ARMED for [ARM_TTL_MS]: subsequent messages there are
 * sentence-gated from their FIRST read, killing the message-2..N first
 * fragment. Arming is evidence-scoped twice over — it engages only where
 * growth was OBSERVED and only where the cheap boundary exit provably
 * exists — and an armed hold that turns out to be instant punct-less text
 * releases by agreement at the next read, capped at [ARMED_NEW_MAX_MS]
 * (the shipped-cadence law: unarmed content never waits at all).
 *
 * ## Per-mode dispatch shape
 * `allowPartialPrefix` (reconciler TRANSLATION flavor only): a growing
 * read containing an interior boundary dispatches its sentence-complete
 * prefix — the box upgrades in place at sentence granularity. Pinhole must
 * NOT do this: its boxes composite into the captured frame, so a prefix box
 * placed over still-typing text is detected as changed and flashed out
 * within a cycle (the recorded #1 disruption class). Pinhole holds until a
 * whole-read boundary / agreement / cap. Furigana and panel presenters
 * also dispatch whole reads only — their group-derived annotation geometry
 * must match the dispatched text.
 *
 * Pure Kotlin against (text, rects, clocks) — unit-tests on the JVM.
 */
class TypewriterGate {

    private class RegionMemory(
        var bounds: Rect,
        /** Newest read of this region (held or dispatched). */
        var lastText: String,
        /** What the user currently sees for this region (last dispatch),
         *  null before the first dispatch. */
        var lastDispatched: String?,
        var lastSeenMs: Long,
    ) {
        /** A hold is open on this region. Explicit flag — the capture-time
         *  anchor is a plain clock value and 0 is a legitimate uptime. */
        var holdOpen = false
        /** Capture time (uptime) of the read that opened the current hold.
         *  Never re-anchored while the hold lives. */
        var holdOpenCaptureMs = 0L
        var stableReads = 0
        /** The open hold has observed growth (a genuine reveal) — caps at
         *  [HOLD_MAX_MS]; an armed first-sighting hold without growth yet
         *  caps at [ARMED_NEW_MAX_MS]. */
        var holdGrowth = false
        /** Region observed growth reaching a sentence boundary; armed until
         *  this uptime. */
        var armedUntilMs = 0L

        fun capMs(): Long = if (holdGrowth) HOLD_MAX_MS else ARMED_NEW_MAX_MS

        fun openHold(captureAtMs: Long, growth: Boolean) {
            holdOpen = true
            holdOpenCaptureMs = captureAtMs
            holdGrowth = growth
            stableReads = 1
        }

        fun closeHold() {
            holdOpen = false
            stableReads = 0
            holdGrowth = false
        }
    }

    private val regions = ArrayList<RegionMemory>()

    /** Regions matched by an entry in the current batch — holds not
     *  re-affirmed by a batch are closed in [endBatch] (the region's fate
     *  this read was KEEP/REMOVE/absent, exactly StabilityHold's sweep). */
    private val affirmed = HashSet<RegionMemory>()

    // ── Reconciler adapter ────────────────────────────────────────────────

    /** [filterVerdicts]' outcome — same shape StabilityHold produced: the
     *  entries to translate this cycle (text possibly narrowed to a
     *  sentence-complete prefix), the boxes whose retranslation is deferred
     *  (render them verbatim alongside the kept boxes), and the earliest
     *  open hold's cap deadline (uptime ms; null when no holds are open). */
    data class Outcome(
        val toTranslate: List<ScanlineReconciler.Region>,
        val heldBoxes: List<TextBox>,
        val nextDeadlineMs: Long?,
    )

    /**
     * Partition [verdicts]' toTranslate into dispatch-now vs held.
     * [captureAtMs] is the frame's capture uptime; [nowMs] the evaluation
     * time the caps are checked against. [allowPartialPrefix] — see the
     * class doc's per-mode dispatch shape.
     */
    fun filterVerdicts(
        verdicts: ScanlineReconciler.Verdicts,
        translationCode: String,
        captureAtMs: Long,
        nowMs: Long,
        allowPartialPrefix: Boolean,
    ): Outcome {
        if (!ENABLED) {
            clear()
            return Outcome(verdicts.toTranslate, emptyList(), null)
        }
        beginBatch()
        val translate = ArrayList<ScanlineReconciler.Region>(verdicts.toTranslate.size)
        val heldBoxes = ArrayList<TextBox>()
        for (entry in verdicts.toTranslate) {
            val box = entry.replacesBox
            // Blank-translation retry / sub-tolerance drift: stable text,
            // pass through (StabilityHold parity).
            val isRetry = box != null &&
                !OverlayToolkit.isSignificantChange(entry.text, box.sourceText)
            val dispatchText = evaluateEntry(
                text = entry.text,
                bounds = entry.bounds,
                displayed = box?.sourceText,
                passThrough = isRetry,
                translationCode = translationCode,
                captureAtMs = captureAtMs,
                nowMs = nowMs,
                allowPartialPrefix = allowPartialPrefix,
            )
            when {
                dispatchText == null -> if (box != null) heldBoxes.add(box)
                dispatchText == entry.text -> translate.add(entry)
                else -> translate.add(entry.copy(text = dispatchText))
            }
        }
        val deadline = endBatch(nowMs)
        return Outcome(translate, heldBoxes, deadline)
    }

    // ── Pinhole adapter ───────────────────────────────────────────────────

    /** [filterFarGroups]' outcome: the far groups to place/translate this
     *  cycle, how many were held (held regions still count as "text
     *  present" for no-text signaling), and the earliest open cap. */
    data class FarOutcome(
        val dispatch: List<FarGroup>,
        val held: Int,
        val nextDeadlineMs: Long?,
    )

    /**
     * Gate the pinhole tier's step-12 far groups. Whole-read dispatch only
     * (no prefix substitution — see the class doc). `paired` groups bypass
     * the hold unconditionally: a content-match replacement carries a
     * placement promise ([FarGroup.paired]) this gate must never break.
     * Call once per full-look cycle — including with an empty list — so
     * un-affirmed holds sweep on the evidence of a read that no longer
     * shows their region.
     */
    fun filterFarGroups(
        groups: List<FarGroup>,
        translationCode: String,
        captureAtMs: Long,
        nowMs: Long,
    ): FarOutcome {
        if (!ENABLED) {
            clear()
            return FarOutcome(groups, 0, null)
        }
        beginBatch()
        val dispatch = ArrayList<FarGroup>(groups.size)
        var held = 0
        for (g in groups) {
            val dispatchText = evaluateEntry(
                text = g.text,
                bounds = g.bounds,
                displayed = null,
                passThrough = g.paired,
                translationCode = translationCode,
                captureAtMs = captureAtMs,
                nowMs = nowMs,
                allowPartialPrefix = false,
            )
            if (dispatchText == null) held++ else dispatch.add(g)
        }
        val deadline = endBatch(nowMs)
        return FarOutcome(dispatch, held, deadline)
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    /** Close open holds but KEEP region memory and arming. The pinhole
     *  input path dismisses per message (tap → dismiss → next message
     *  types) — a full clear there would disarm every region in exactly
     *  the flow arming exists for. */
    fun clearHolds() {
        for (m in regions) m.closeHold()
    }

    /** Keep-alive for regions whose fate this read was KEEP: a stable
     *  displayed box never enters the filter batch, and without a touch
     *  its region memory (and arming) would evict after [MEMORY_TTL_MS]
     *  of the box just sitting there. Match-only — never creates. Call
     *  after the cycle's filter with the kept boxes' bounds. */
    fun touchRegions(rects: List<Rect>, nowMs: Long) {
        for (r in rects) {
            var best: RegionMemory? = null
            var bestOverlap = 0L
            for (m in regions) {
                val ov = overlapArea(m.bounds, r)
                if (ov <= 0L) continue
                val smaller = minOf(area(m.bounds), area(r)).coerceAtLeast(1L)
                if (ov.toFloat() / smaller < MATCH_MIN_OVERLAP) continue
                if (ov > bestOverlap) {
                    bestOverlap = ov
                    best = m
                }
            }
            best?.let {
                it.bounds = Rect(r)
                it.lastSeenMs = nowMs
            }
        }
    }

    /** Drop everything — holds, memory, arming. For resets that void the
     *  coordinate space (rotation, crop/dim changes, mode stop, language
     *  change): remembered rects are meaningless afterwards. */
    fun clear() {
        regions.clear()
        affirmed.clear()
    }

    // ── Core decision ─────────────────────────────────────────────────────

    /**
     * Evaluate one region read. Returns the text to dispatch (possibly a
     * sentence-complete prefix of [text]), or null when the read is held.
     * Updates region memory; [endBatch] finishes the cycle's bookkeeping.
     */
    private fun evaluateEntry(
        text: String,
        bounds: Rect,
        displayed: String?,
        passThrough: Boolean,
        translationCode: String,
        captureAtMs: Long,
        nowMs: Long,
        allowPartialPrefix: Boolean,
    ): String? {
        val mem = matchOrCreate(bounds, nowMs)
        affirmed.add(mem)
        val boundaries = SentenceBoundary.supports(translationCode)
        // The user-visible reference: the paired box's text when the caller
        // knows it (reconciler), else the region's last dispatch (pinhole —
        // the box died before its fuller text re-arrived as a far group).
        val ref = displayed ?: mem.lastDispatched

        fun dispatch(t: String): String {
            mem.closeHold()
            mem.lastText = text
            mem.lastDispatched = t
            return t
        }

        fun arm() {
            if (boundaries) mem.armedUntilMs = nowMs + ARM_TTL_MS
        }

        if (passThrough) return dispatch(text)

        // ── An open hold on this region ──────────────────────────────────
        if (mem.holdOpen) {
            val capExpired = nowMs - mem.holdOpenCaptureMs >= mem.capMs()
            return when {
                !OverlayToolkit.isSignificantChange(text, mem.lastText) -> {
                    // Agreement read.
                    mem.stableReads++
                    if (mem.stableReads >= STABLE_READS || capExpired) {
                        if (mem.holdGrowth &&
                            SentenceBoundary.endsAtBoundary(text, translationCode)
                        ) arm()
                        dispatch(text)
                    } else {
                        mem.lastText = text
                        null
                    }
                }
                OverlayToolkit.isEvolvingText(mem.lastText, text) -> {
                    // Still growing. Growth promotes an armed-new hold to a
                    // genuine reveal — the cap widens to [HOLD_MAX_MS]
                    // (same anchor), so re-check expiry against the new cap
                    // rather than the pre-promotion value.
                    mem.holdGrowth = true
                    mem.stableReads = 1
                    mem.lastText = text
                    if (SentenceBoundary.endsAtBoundary(text, translationCode)) {
                        arm()
                        return dispatch(text)
                    }
                    if (allowPartialPrefix) {
                        val p = SentenceBoundary.terminalPrefix(text, translationCode)
                        if (p != null && (ref == null || OverlayToolkit.isEvolvingText(ref, p))) {
                            // Sentence-complete prefix grew: upgrade the box
                            // in place, keep holding the ragged tail.
                            arm()
                            mem.lastDispatched = p
                            return p
                        }
                    }
                    val growthCapExpired = nowMs - mem.holdOpenCaptureMs >= mem.capMs()
                    if (growthCapExpired) dispatch(text) else null
                }
                else -> dispatch(text) // real change mid-hold (advance) — Level 0
            }
        }

        // ── No open hold ─────────────────────────────────────────────────
        val armed = boundaries && nowMs < mem.armedUntilMs

        // First sighting of this region (nothing displayed, nothing
        // remembered as dispatched).
        if (ref == null) {
            if (!armed) return dispatch(text) // Level 0 first response
            return gateFreshText(mem, text, translationCode, captureAtMs, nowMs, allowPartialPrefix, ::dispatch)
        }

        // Stable vs what's shown: re-place / retry parity (pinhole flap
        // recovery rides the translation cache).
        if (!OverlayToolkit.isSignificantChange(text, ref)) return dispatch(text)

        if (OverlayToolkit.isEvolvingText(ref, text)) {
            // Typewriter growth against the displayed text.
            if (SentenceBoundary.endsAtBoundary(text, translationCode)) {
                arm()
                return dispatch(text)
            }
            if (allowPartialPrefix) {
                val p = SentenceBoundary.terminalPrefix(text, translationCode)
                if (p != null && OverlayToolkit.isEvolvingText(ref, p) &&
                    OverlayToolkit.isSignificantChange(p, ref)
                ) {
                    arm()
                    mem.openHold(captureAtMs, growth = true)
                    mem.lastText = text
                    mem.lastDispatched = p
                    return p
                }
            }
            // Slow-pipeline self-disable: the cap expired while this very
            // read was in flight — open-and-release (StabilityHold parity).
            if (nowMs - captureAtMs >= HOLD_MAX_MS) return dispatch(text)
            mem.openHold(captureAtMs, growth = true)
            mem.lastText = text
            return null
        }

        // Real content change (message advance). Unarmed: Level 0. Armed:
        // the new message is sentence-gated from its first read.
        if (!armed) return dispatch(text)
        return gateFreshText(mem, text, translationCode, captureAtMs, nowMs, allowPartialPrefix, ::dispatch)
    }

    /** Armed-region policy for text with no growth evidence yet (first
     *  sighting, or a message advance in an armed region): boundary-final
     *  dispatches at once — instant complete messages pay nothing; anything
     *  else holds, capped at [ARMED_NEW_MAX_MS]. */
    private inline fun gateFreshText(
        mem: RegionMemory,
        text: String,
        translationCode: String,
        captureAtMs: Long,
        nowMs: Long,
        allowPartialPrefix: Boolean,
        dispatch: (String) -> String,
    ): String? {
        if (SentenceBoundary.endsAtBoundary(text, translationCode)) return dispatch(text)
        if (allowPartialPrefix) {
            val p = SentenceBoundary.terminalPrefix(text, translationCode)
            if (p != null) {
                mem.openHold(captureAtMs, growth = false)
                mem.lastText = text
                mem.lastDispatched = p
                return p
            }
        }
        // Armed-hold self-disable on slow pipelines, same anchoring rule as
        // the growth cap.
        if (nowMs - captureAtMs >= ARMED_NEW_MAX_MS) return dispatch(text)
        mem.openHold(captureAtMs, growth = false)
        mem.lastText = text
        return null
    }

    // ── Region memory plumbing ────────────────────────────────────────────

    private fun beginBatch() {
        affirmed.clear()
    }

    /** Sweep un-affirmed holds, evict stale memory, return the earliest
     *  open cap deadline. */
    private fun endBatch(nowMs: Long): Long? {
        var deadline: Long? = null
        val it = regions.iterator()
        while (it.hasNext()) {
            val m = it.next()
            if (m.holdOpen && m !in affirmed) m.closeHold()
            if (nowMs - m.lastSeenMs >= MEMORY_TTL_MS) {
                it.remove()
                continue
            }
            if (m.holdOpen) {
                val cap = m.holdOpenCaptureMs + m.capMs()
                deadline = if (deadline == null) cap else minOf(deadline, cap)
            }
        }
        return deadline
    }

    /** Best-overlap region match: same region when the intersection covers
     *  ≥ [MATCH_MIN_OVERLAP] of the SMALLER rect — robust to a typewriter
     *  box growing (the earlier partial's rect sits inside the fuller one)
     *  and orientation-agnostic. Unmatched → fresh memory (LRU-capped). */
    private fun matchOrCreate(bounds: Rect, nowMs: Long): RegionMemory {
        var best: RegionMemory? = null
        var bestOverlap = 0L
        for (m in regions) {
            if (m in affirmed) continue // one entry per region per batch
            val ov = overlapArea(m.bounds, bounds)
            if (ov <= 0L) continue
            val smaller = minOf(area(m.bounds), area(bounds)).coerceAtLeast(1L)
            if (ov.toFloat() / smaller < MATCH_MIN_OVERLAP) continue
            if (ov > bestOverlap) {
                bestOverlap = ov
                best = m
            }
        }
        val m = best
        if (m != null) {
            m.bounds = Rect(bounds)
            m.lastSeenMs = nowMs
            return m
        }
        if (regions.size >= MAX_REGIONS) {
            regions.minByOrNull { it.lastSeenMs }?.let { regions.remove(it) }
        }
        val fresh = RegionMemory(Rect(bounds), lastText = "", lastDispatched = null, lastSeenMs = nowMs)
        regions.add(fresh)
        return fresh
    }

    private fun area(r: Rect): Long = r.width().toLong() * r.height().toLong()

    private fun overlapArea(a: Rect, b: Rect): Long {
        val ix = minOf(a.right, b.right) - maxOf(a.left, b.left)
        val iy = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        if (ix <= 0 || iy <= 0) return 0L
        return ix.toLong() * iy.toLong()
    }

    companion object {
        /** A/B lever: false = pure Level 0 (translate on sight). */
        const val ENABLED = true

        /** Consecutive agreeing reads that release a hold (GSM parity). */
        const val STABLE_READS = 2

        /** Wall-clock cap on a hold that has observed growth, anchored at
         *  the opening read's capture time (marquee backstop; slow-OCR
         *  self-disable — see the class doc). */
        const val HOLD_MAX_MS = 2_000L

        /** Cap on an armed hold with NO growth evidence yet (instant
         *  punct-less text in an armed region): the shipped-cadence law —
         *  such text waits at most one interval. */
        const val ARMED_NEW_MAX_MS = 1_000L

        /** How long a growth→boundary observation keeps a region armed. */
        const val ARM_TTL_MS = 120_000L

        /** Region memory evicted after this long unseen. */
        const val MEMORY_TTL_MS = 45_000L

        /** Fraction of the smaller rect the overlap must cover to match. */
        const val MATCH_MIN_OVERLAP = 0.6f

        const val MAX_REGIONS = 64
    }
}
