package com.playtranslate

import android.graphics.Rect
import com.playtranslate.language.TextOrientation
import com.playtranslate.ocr.core.DeskewGeometry
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
 *     latency. Covers punct-final dialogue in every punct-using script.
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
 * ## Region memory, chains, and arming
 * State is keyed by REGION (rect overlap, not box identity): pinhole
 * removes a changed box before its fuller text re-arrives as an unpaired
 * far group, and a reconciler NEW entry may repeat while its first
 * translation is in flight — box identity dies at exactly the moments this
 * gate must remember. Each memory tracks the current text CHAIN, whose
 * key rect is stamped at chain START (a new message lineage) and never
 * rebased mid-growth — the chain-start rect carries the reveal ORIGIN at
 * its direction-resolved start corner, however many lines the first catch
 * happened to include (text flows outward from the origin, so a late
 * 2-line catch still starts there).
 *
 * A region ARMS when a growth chain SETTLES — released by agreement, cap,
 * or boundary after growth was observed. Settling is the garble filter: a
 * garbled read can look like prefix-growth, but garble chains end with the
 * text REVERTING (a real-change dispatch), which never arms. Field
 * evidence (2026-07-22, Thor): most dialogue in the observed game ends
 * WITHOUT terminal punctuation, so the earlier boundary-evidence arming
 * never engaged — settle-based arming is what makes punct-less games work.
 * Armed regions gate fresh text from its FIRST read, killing the
 * message-2..N first fragment; the fragment on a fresh area's message 1 is
 * the one observation the learning inherently needs. Arming is anchored at
 * the arming chain's origin corner and applies only to text sharing that
 * corner (within ~[ORIGIN_TOL_FRAC] of a line extent) — a name plate or
 * choice prompt rendered INSIDE the dialogue area overlaps the region but
 * does not share the origin, and must not be held. Armed instant
 * punct-final text dispatches on sighting; armed punct-less instant text
 * waits at most one read, capped at [ARMED_NEW_MAX_MS] (the shipped-
 * cadence law: unarmed content never waits at all). Every observed growth
 * refreshes an armed region's TTL, so active dialogue never disarms;
 * armed entries are exempt from the memory TTL until arming lapses.
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
        bounds: Rect,
        orientation: TextOrientation,
        rtl: Boolean,
        lineCount: Int,
        /** Newest read of this region (held or dispatched). */
        var lastText: String,
        /** What the user currently sees for this region (last dispatch),
         *  null before the first dispatch. */
        var lastDispatched: String?,
        var lastSeenMs: Long,
        /** Founding read's slant trio (0 when upright) — resolves the exact
         *  origin corner + true line extent for slanted dialogue. */
        angleDeg: Float = 0f,
        orientedW: Float = 0f,
        orientedH: Float = 0f,
    ) {
        /** The current chain's START rect — stamped when a new text
         *  lineage begins, never rebased mid-growth (its start corner is
         *  the reveal origin). */
        var bounds: Rect = Rect(bounds)
        /** Direction-resolved start corner of [bounds] — the reveal
         *  origin. */
        var originX = 0
        var originY = 0
        /** Per-line extent of the chain-start rect (height per line for
         *  horizontal text, width per column for vertical) — the scale
         *  the origin tolerance is denominated in. */
        var chainLineExtentPx = 1

        /** A hold is open on this region. Explicit flag — the capture-time
         *  anchor is a plain clock value and 0 is a legitimate uptime. */
        var holdOpen = false
        /** Union of every read rect observed while the current hold is
         *  open — the quiet-probe region (and the eventual accelerator's):
         *  the frozen chain-start rect covers only the first fragment. */
        val readRectUnion = Rect()
        /** The hold observed growth in the CURRENT batch — pairs with the
         *  quiet probe's apron-miss detector (growth + quiet apron =
         *  under-coverage). Cleared each beginBatch. */
        var grewInBatch = false
        /** Orientation of the current chain (for apron direction). */
        var vertical = false
        /** Capture time (uptime) of the read that opened the current hold.
         *  Never re-anchored while the hold lives. */
        var holdOpenCaptureMs = 0L
        var stableReads = 0
        /** The open hold has observed growth (a genuine reveal) — caps at
         *  [HOLD_MAX_MS]; an armed first-sighting hold without growth yet
         *  caps at [ARMED_NEW_MAX_MS]. */
        var holdGrowth = false

        /** Armed until this uptime (0 = never armed). */
        var armedUntilMs = 0L

        /** Uptime of the last hold RELEASED BY DISPATCH (0 = never) — the
         *  release-grace anchor for flow-adjacency deferral. Sweep-closes
         *  (region vanished) deliberately do not stamp: grace covers "a
         *  reveal just settled here", not "evidence evaporated". */
        var lastReleaseMs = 0L

        /** Thrash breaker: recent break-class dispatch timestamps. A region
         *  whose reads keep flipping between contradictory lineages (the
         *  グラウス split/merge loop, or any un-enumerated pathology of the
         *  same shape) trips the breaker and falls open to Level 0 —
         *  bounded baseline churn instead of a novel failure mode. */
        val breakEventsMs = ArrayDeque<Long>()
        var bypassUntilMs = 0L

        /** Record a break-class dispatch; true when the trip threshold is
         *  reached within the window. */
        fun recordBreak(nowMs: Long): Boolean {
            while (breakEventsMs.isNotEmpty() &&
                nowMs - breakEventsMs.first() > BREAKER_WINDOW_MS
            ) breakEventsMs.removeFirst()
            breakEventsMs.addLast(nowMs)
            return breakEventsMs.size >= BREAKER_TRIP_COUNT
        }
        /** Origin corner of the chain that ARMED this region — the anchor
         *  fresh text must share to be armed-gated. Kept separately from
         *  [originX]/[originY] so later non-typewriter chains passing
         *  through the region cannot clobber the learned anchor. */
        var armedOriginX = 0
        var armedOriginY = 0
        var armedTolPx = 0

        init {
            startChain(bounds, orientation, rtl, lineCount, angleDeg, orientedW, orientedH)
        }

        fun capMs(): Long = if (holdGrowth) HOLD_MAX_MS else ARMED_NEW_MAX_MS

        fun openHold(captureAtMs: Long, growth: Boolean, bounds: Rect) {
            holdOpen = true
            holdOpenCaptureMs = captureAtMs
            holdGrowth = growth
            stableReads = 1
            readRectUnion.set(bounds)
        }

        /** The quiet-probe region: the read union padded along the flow
         *  direction to cover where the next glyph can physically land
         *  (~2 glyph extents on the reading axis, ~1.5 line extents on the
         *  cross axis, a small jitter margin elsewhere). */
        fun paddedUnion(): Rect {
            val ext = chainLineExtentPx
            val r = Rect(readRectUnion)
            val margin = ext / 4
            if (vertical) {
                // Columns grow leftward; glyphs advance downward.
                r.left -= ext * 2
                r.bottom += ext * 2
                r.right += margin
                r.top -= margin
            } else {
                r.right += ext * 2
                r.bottom += ext + ext / 2
                r.left -= margin
                r.top -= margin
            }
            return r
        }

        /** Newest partial-VIEW read while a hold is open — consecutive
         *  views that EXTEND each other are a re-reveal of known text
         *  (repeat dialogue), not a shrink settling. */
        var lastViewText = ""

        fun closeHold() {
            holdOpen = false
            stableReads = 0
            holdGrowth = false
            lastViewText = ""
        }

        /** A new text lineage begins at [rect]: stamp the chain key and its
         *  origin corner. */
        fun startChain(
            rect: Rect, orientation: TextOrientation, rtl: Boolean, lineCount: Int,
            angleDeg: Float = 0f, orientedW: Float = 0f, orientedH: Float = 0f,
        ) {
            bounds = Rect(rect)
            vertical = orientation == TextOrientation.VERTICAL
            val (x, y) = startCorner(rect, orientation, rtl, angleDeg, orientedW, orientedH)
            originX = x
            originY = y
            chainLineExtentPx = when {
                // Slanted (always horizontal): the AABB height runs 3–5× the
                // true line extent at typical dialogue aspects — the oriented
                // height is the real one.
                angleDeg != 0f && orientedH > 0f ->
                    (orientedH / lineCount.coerceAtLeast(1)).toInt()
                orientation == TextOrientation.VERTICAL -> rect.width() / lineCount.coerceAtLeast(1)
                else -> rect.height() / lineCount.coerceAtLeast(1)
            }.coerceAtLeast(1)
        }

        /** Does [rect]'s start corner sit on the ARMED origin (within the
         *  armed tolerance)? The discrimination that keeps overlapping
         *  non-dialogue elements (name plates, choice prompts) out of the
         *  armed gate. */
        fun armedCornerMatches(
            rect: Rect, orientation: TextOrientation, rtl: Boolean,
            angleDeg: Float = 0f, orientedW: Float = 0f, orientedH: Float = 0f,
        ): Boolean {
            val (x, y) = startCorner(rect, orientation, rtl, angleDeg, orientedW, orientedH)
            return kotlin.math.abs(x - armedOriginX) <= armedTolPx &&
                kotlin.math.abs(y - armedOriginY) <= armedTolPx
        }
    }

    private val regions = ArrayList<RegionMemory>()

    /** Regions matched by an entry in the current batch — holds not
     *  re-affirmed by a batch are closed in [endBatch] (the region's fate
     *  this read was KEEP/REMOVE/absent, exactly StabilityHold's sweep). */
    private val affirmed = HashSet<RegionMemory>()

    /** Out-flag from the last [evaluateEntry] call: the hold it returned
     *  opened on a NEW text lineage (message advance into a hold — the
     *  armed-fresh and reveal-adjacent sites), so a displayed box for the
     *  entry shows the PREVIOUS message and must be erased, not rendered
     *  through the hold. Meaningful only when that call returned null;
     *  every call resets it. Same-chain holds (growth, views, agreement,
     *  re-shows) never set it — their box is a prefix/translation of the
     *  text still typing, and [filterVerdicts] keeps it displayed. */
    private var heldNewLineage = false

    /** Per-decision debug tap (field diagnosis, 2026-07-22 inconsistency
     *  report): every dispatch/hold logs its reason + evidence so a field
     *  pass can ATTRIBUTE a fragment instead of us inferring mechanisms.
     *  Null (production, tests) = zero cost; the modes install a
     *  DetectionLog sink per debug cycle. Messages are built lazily. */
    var debugSink: ((String) -> Unit)? = null

    /** Always-on decision counters — the field-diagnosis channel for
     *  devices where the debug sink is off. The modes log [summary] into
     *  the ordinary app log (exported by diagnostics) periodically and at
     *  stop. A healthy session is dominated by boundary/agree releases;
     *  breaks and breaker trips dominating = a pathology worth a report. */
    class Stats {
        var level0 = 0; var advance = 0; var replace = 0; var boundary = 0
        var prefix = 0; var agree = 0; var capFlush = 0; var breaks = 0
        var shrinkCap = 0; var pass = 0; var selfDisable = 0; var bypass = 0
        var growHolds = 0; var armedHolds = 0; var viewHolds = 0
        var partialViews = 0; var siblingViews = 0; var revealAdjacent = 0
        var sameBand = 0; var lineageDrops = 0
        var arms = 0; var breakerTrips = 0

        fun summary(): String =
            "disp[l0=$level0 adv=$advance rep=$replace bnd=$boundary pfx=$prefix " +
                "agr=$agree cap=$capFlush brk=$breaks shr=$shrinkCap pas=$pass " +
                "sd=$selfDisable byp=$bypass] " +
                "hold[grow=$growHolds armed=$armedHolds view=$viewHolds " +
                "pv=$partialViews sib=$siblingViews radj=$revealAdjacent " +
                "band=$sameBand drop=$lineageDrops] arm=$arms trip=$breakerTrips"
    }

    val stats = Stats()

    /**
     * Is [part] a partial VIEW of [whole] — shorter, and bag-contained
     * within a small garble budget? The classification that keeps split /
     * occluded reads of known text from being mistaken for real changes
     * (the グラウス split-merge loop). Tolerance is tight (max(2, 15%))
     * because loose containment false-matches unrelated short text against
     * long char bags.
     */
    private fun bagSubset(part: String, whole: String): Boolean {
        if (part.isEmpty() || part.length >= whole.length) return false
        val fw = HashMap<Char, Int>()
        for (c in whole) fw[c] = (fw[c] ?: 0) + 1
        var excess = 0
        for (c in part) {
            val n = fw[c] ?: 0
            if (n > 0) fw[c] = n - 1 else excess++
        }
        return excess <= maxOf(2, (part.length * SUBSET_TOL_FRAC).toInt())
    }

    /** Ellipsis-family glyphs whose RUNS OCR with unstable inventories —
     *  the へんじがない・・・ field case read ・・・・ / ・・. / ・・… across
     *  passes, blowing every relation tolerance from punctuation alone. */
    private val ELLIPSIS_CLASS = "・‥….．"

    /** Long-dash family OCR substitutes freely (katakana ー vs ― bar vs
     *  minus/en/em) — one class for comparison. */
    private val DASH_CLASS = "ー―−–—"

    /**
     * COMPARISON-TIME folding for the gate's text relations — never for
     * dispatch, storage, or display, which stay raw. Runs (≥2) of
     * ellipsis-class glyphs collapse to one token, so their unstable OCR
     * counts stop eating the tolerances; dash-family glyphs unify. A lone
     * ・ (name interpunct) and a lone . (decimals) keep their identity.
     */
    private fun foldForCompare(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c in ELLIPSIS_CLASS -> {
                    var j = i + 1
                    while (j < s.length && s[j] in ELLIPSIS_CLASS) j++
                    if (j - i >= 2) sb.append('…') else sb.append(c)
                    i = j
                }
                c in DASH_CLASS -> {
                    sb.append('ー')
                    i++
                }
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        return sb.toString()
    }

    private inline fun d(msg: () -> String) {
        debugSink?.invoke(msg())
    }

    /** Compact text sample for decision lines: head…tail + length —
     *  enough to see brackets, garble, and growth deltas in the field. */
    private fun snip(s: String): String =
        if (s.length <= 14) "$s(${s.length})"
        else "${s.take(8)}…${s.takeLast(5)}(${s.length})"

    /** Bag-of-chars difference count — logging only (the decision path
     *  uses [OverlayToolkit.isSignificantChange]'s thresholds). */
    private fun bagDiff(a: String, b: String): Int {
        val fa = a.groupingBy { it }.eachCount()
        val fb = b.groupingBy { it }.eachCount()
        var diff = 0
        for (c in fa.keys + fb.keys) diff += kotlin.math.abs((fa[c] ?: 0) - (fb[c] ?: 0))
        return diff
    }

    // ── Reconciler adapter ────────────────────────────────────────────────

    /** [filterVerdicts]' outcome — same shape StabilityHold produced: the
     *  entries to translate this cycle (text possibly narrowed to a
     *  sentence-complete prefix), the boxes whose retranslation is deferred
     *  (render them verbatim alongside the kept boxes), the boxes to erase
     *  NOW ([dropNow] — dead lineages under an opening hold; see
     *  [heldNewLineage]), and the earliest open hold's cap deadline
     *  (uptime ms; null when no holds are open). */
    data class Outcome(
        val toTranslate: List<ScanlineReconciler.Region>,
        val heldBoxes: List<TextBox>,
        val dropNow: List<TextBox>,
        val nextDeadlineMs: Long?,
    )

    /**
     * Partition [verdicts]' toTranslate into dispatch-now vs held.
     * [captureAtMs] is the frame's capture uptime; [nowMs] the evaluation
     * time the caps are checked against. [sourceIsRtl] resolves horizontal
     * origin corners. [allowPartialPrefix] — see the class doc's per-mode
     * dispatch shape.
     */
    fun filterVerdicts(
        verdicts: ScanlineReconciler.Verdicts,
        translationCode: String,
        sourceIsRtl: Boolean,
        captureAtMs: Long,
        nowMs: Long,
        allowPartialPrefix: Boolean,
    ): Outcome {
        if (!ENABLED) {
            clear()
            return Outcome(verdicts.toTranslate, emptyList(), emptyList(), null)
        }
        beginBatch()
        val translate = ArrayList<ScanlineReconciler.Region>(verdicts.toTranslate.size)
        val heldBoxes = ArrayList<TextBox>()
        val dropNow = ArrayList<TextBox>()
        for (entry in verdicts.toTranslate) {
            val box = entry.replacesBox
            // Blank-translation retry / sub-tolerance drift: stable text,
            // pass through (StabilityHold parity).
            val isRetry = box != null &&
                !OverlayToolkit.isSignificantChange(entry.text, box.sourceText)
            val dispatchText = evaluateEntry(
                text = entry.text,
                bounds = entry.bounds,
                lineCount = entry.lineCount,
                orientation = entry.orientation,
                rtl = sourceIsRtl,
                displayed = box?.sourceText,
                passThrough = isRetry,
                translationCode = translationCode,
                captureAtMs = captureAtMs,
                nowMs = nowMs,
                allowPartialPrefix = allowPartialPrefix,
                angleDeg = entry.angleDeg,
                orientedW = entry.orientedWidth,
                orientedH = entry.orientedHeight,
            )
            when {
                dispatchText == null -> if (box != null) {
                    // A hold on a NEW lineage: the displayed box shows the
                    // PREVIOUS message — dead content the user would read as
                    // "nothing happened" for the whole hold. Erase it now;
                    // the release dispatch rebuilds the region. Same-chain
                    // holds keep their box: it shows a prefix/translation of
                    // the very text still typing, and dropping it would
                    // re-open the flash-out class.
                    if (heldNewLineage) {
                        stats.lineageDrops++
                        dropNow.add(box)
                    } else {
                        heldBoxes.add(box)
                    }
                }
                dispatchText == entry.text -> translate.add(entry)
                else -> translate.add(entry.copy(text = dispatchText))
            }
        }
        val deadline = endBatch(nowMs)
        return Outcome(translate, heldBoxes, dropNow, deadline)
    }

    // ── Pinhole adapter ───────────────────────────────────────────────────

    /** [filterFarGroups]' outcome: the far groups to place/translate this
     *  cycle, how many were held (held regions still count as "text
     *  present" for no-text signaling), how many holds were SWEPT this
     *  batch (ended without dispatch — the region's text vanished; a
     *  suppressed no-text signal must resolve on this event), and the
     *  earliest open cap. */
    data class FarOutcome(
        val dispatch: List<FarGroup>,
        val held: Int,
        val swept: Int,
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
        sourceIsRtl: Boolean,
        captureAtMs: Long,
        nowMs: Long,
    ): FarOutcome {
        if (!ENABLED) {
            clear()
            return FarOutcome(groups, 0, 0, null)
        }
        beginBatch()
        val dispatch = ArrayList<FarGroup>(groups.size)
        var held = 0
        for (g in groups) {
            val dispatchText = evaluateEntry(
                text = g.text,
                bounds = g.bounds,
                lineCount = g.lineCount,
                orientation = g.orientation,
                rtl = sourceIsRtl,
                displayed = null,
                passThrough = g.paired,
                translationCode = translationCode,
                captureAtMs = captureAtMs,
                nowMs = nowMs,
                allowPartialPrefix = false,
                angleDeg = g.angleDeg,
                orientedW = g.orientedWidth,
                orientedH = g.orientedHeight,
            )
            if (dispatchText == null) held++ else dispatch.add(g)
        }
        val deadline = endBatch(nowMs)
        return FarOutcome(dispatch, held, batchSwept, deadline)
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    /** Close open holds but KEEP region memory and arming. The pinhole
     *  input path dismisses per message (tap → dismiss → next message
     *  types) — a full clear there would disarm every region in exactly
     *  the flow arming exists for. */
    fun clearHolds() {
        for (m in regions) m.closeHold()
    }

    /** An EMPTY full-look batch: the read covered the screen and found no
     *  text at all. The once-per-full-look contract applies — un-affirmed
     *  holds sweep, memory TTLs run — and the returned deadline (null
     *  after the sweep) must replace the owner's stored one. The modes'
     *  early no-text returns skipped the batch entirely, leaving an
     *  expired deadline that bypassed the pixel skip and pinned pacing at
     *  the floor forever on a textless screen (Codex review follow-up,
     *  2026-07-23). */
    fun sweepEmptyBatch(nowMs: Long): Long? {
        if (!ENABLED) return null
        beginBatch()
        return endBatch(nowMs)
    }

    /** One hold's quiet-probe view: identity, the padded read union to
     *  sample, whether this batch observed growth, and whether the hold
     *  RELEASED this batch — released entries let the probe run the final
     *  endpoint comparison before dropping the track (agreement releases
     *  fire at the FIRST settled cycle, so post-batch open-holds-only
     *  snapshots were structurally blind to every streak). */
    data class QuietHoldProbe(
        val id: Int,
        val paddedBounds: Rect,
        val grew: Boolean,
        val released: Boolean = false,
    )

    /** Holds released by dispatch during the current batch (probe views
     *  captured at release time). Cleared each beginBatch. */
    private val batchReleased = ArrayList<QuietHoldProbe>()

    /** Holds SWEPT by the current batch's endBatch (un-affirmed — their
     *  region's text vanished without a dispatch). Batch-scoped. */
    private var batchSwept = 0

    /** Debug telemetry for the parked quiet-pixel accelerator
     *  ([TypewriterQuietProbe]): open holds plus this batch's releases.
     *  Read after a filter batch; no behavior depends on it. */
    fun quietProbeSnapshot(): List<QuietHoldProbe> {
        var out: MutableList<QuietHoldProbe>? = null
        for (m in regions) {
            if (!m.holdOpen) continue
            (out ?: ArrayList<QuietHoldProbe>().also { out = it })
                .add(QuietHoldProbe(System.identityHashCode(m), m.paddedUnion(), m.grewInBatch))
        }
        if (batchReleased.isEmpty()) return out ?: emptyList()
        return (out ?: ArrayList()).also { it.addAll(batchReleased) }
    }

    /** Keep-alive for regions whose fate this read was KEEP: a stable
     *  displayed box never enters the filter batch, and without a touch
     *  its region memory would evict after [MEMORY_TTL_MS] of the box just
     *  sitting there. Match-only — never creates, never re-anchors the
     *  chain. Call after the cycle's filter with the kept boxes' bounds. */
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
            best?.let { it.lastSeenMs = nowMs }
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
        lineCount: Int,
        orientation: TextOrientation,
        rtl: Boolean,
        displayed: String?,
        passThrough: Boolean,
        translationCode: String,
        captureAtMs: Long,
        nowMs: Long,
        allowPartialPrefix: Boolean,
        angleDeg: Float = 0f,
        orientedW: Float = 0f,
        orientedH: Float = 0f,
    ): String? {
        heldNewLineage = false
        val match = matchOrCreate(text, bounds, orientation, rtl, lineCount, nowMs, angleDeg, orientedW, orientedH)
        val mem = match.mem
        affirmed.add(mem)
        val boundaries = SentenceBoundary.supports(translationCode)
        // The user-visible reference: the paired box's text when the caller
        // knows it (reconciler), else the region's last dispatch (pinhole —
        // the box died before its fuller text re-arrived as a far group).
        val ref = displayed ?: mem.lastDispatched

        // All text relations run on FOLDED strings ([foldForCompare]) —
        // punctuation-run variance must not read as content change.
        val fText = foldForCompare(text)
        fun same(other: String) = !OverlayToolkit.isSignificantChange(fText, foldForCompare(other))
        fun grows(other: String): Boolean {
            val fo = foldForCompare(other)
            if (OverlayToolkit.isEvolvingText(fo, fText)) return true
            // Deep edge-garble second chance: mid-reveal reads garble the
            // LAST 3-4 glyphs routinely (field specimens: 、Nッ / カー /
            // 二枚), and the shared min(2, n/4) tail trim is too shallow —
            // the retained garbage poisons the prefix compare and the read
            // breaks the chain. Dropping 2 more chars before delegating
            // gives ~4 chars of tail tolerance, guarded against short
            // known-texts where that would over-trim into false growth.
            return fText.length > fo.length && fo.length >= 6 &&
                OverlayToolkit.isEvolvingText(fo.dropLast(2), fText)
        }
        fun viewOf(other: String) = bagSubset(fText, foldForCompare(other))

        // Decision-line context: sample + this read's start corner.
        fun ctx(): String {
            val (cx, cy) = startCorner(bounds, orientation, rtl, angleDeg, orientedW, orientedH)
            return "«${snip(text)}» @($cx,$cy)"
        }

        fun dispatch(t: String, why: () -> String): String {
            d { "DISPATCH ${why()} ${ctx()}" }
            if (mem.holdOpen) {
                mem.lastReleaseMs = nowMs
                batchReleased.add(
                    QuietHoldProbe(
                        System.identityHashCode(mem), mem.paddedUnion(),
                        mem.grewInBatch, released = true,
                    )
                )
            }
            mem.closeHold()
            mem.lastText = text
            mem.lastDispatched = t
            return t
        }

        fun held(why: () -> String): String? {
            d { "HOLD ${why()} ${ctx()}" }
            return null
        }

        // Arm on evidence: growth in this chain reached a completion
        // (boundary, prefix, settle, or cap flush). Anchored at the arming
        // chain's origin. Thai never arms — with no boundary convention
        // its armed holds would gain nothing over the plain evolving hold.
        fun arm() {
            if (!boundaries) return
            stats.arms++
            mem.armedUntilMs = nowMs + ARM_TTL_MS
            mem.armedOriginX = mem.originX
            mem.armedOriginY = mem.originY
            mem.armedTolPx = (mem.chainLineExtentPx * ORIGIN_TOL_FRAC).toInt()
                .coerceAtLeast(ORIGIN_TOL_MIN_PX)
            d { "ARM @(${mem.armedOriginX},${mem.armedOriginY}) tol=${mem.armedTolPx}" }
        }

        // Active dialogue must never disarm: any observed growth in an
        // armed region refreshes its TTL.
        fun refreshArm() {
            if (nowMs < mem.armedUntilMs) mem.armedUntilMs = nowMs + ARM_TTL_MS
        }

        // Break-class dispatches feed the thrash breaker.
        fun recordBreakClass() {
            if (mem.recordBreak(nowMs)) {
                mem.bypassUntilMs = nowMs + BREAKER_COOLDOWN_MS
                mem.closeHold()
                stats.breakerTrips++
                d { "BREAKER trip @(${mem.originX},${mem.originY})" }
            }
        }

        // A second piece of an already-claimed split block: pure view, no
        // state to run — the claiming entry owns this region's decision.
        if (match.siblingView) {
            stats.siblingViews++
            return held { "sibling-view" }
        }

        // Tripped breaker: this region's read stream proved pathological —
        // fail open to Level 0 (shipped pre-gate behavior) for the
        // cooldown. Memory stays coherent for afterwards.
        if (nowMs < mem.bypassUntilMs) {
            stats.bypass++
            mem.startChain(bounds, orientation, rtl, lineCount, angleDeg, orientedW, orientedH)
            return dispatch(text) { "breaker-bypass" }
        }

        if (passThrough) {
            stats.pass++
            mem.startChain(bounds, orientation, rtl, lineCount, angleDeg, orientedW, orientedH)
            return dispatch(text) { if (displayed != null) "pass-retry" else "pass-paired" }
        }

        // ── An open hold on this region ──────────────────────────────────
        if (mem.holdOpen) {
            mem.readRectUnion.union(bounds)
            val capExpired = nowMs - mem.holdOpenCaptureMs >= mem.capMs()

            // Release-ORDER guard: a chain flow-after a still-OPEN earlier
            // hold must not release ahead of it (field c269: line 2's
            // boundary fired while line 1 still held). Open holds only, no
            // grace — a flow-before chain released earlier in this same
            // batch clears the way immediately, so joint release works.
            // The own cap always escapes.
            fun orderBlocked(): Boolean = !capExpired &&
                revealAdjacentTo(mem, bounds, orientation, nowMs, includeGrace = false)

            if (same(mem.lastText)) {
                // Agreement read. Δ in the log discriminates true settles
                // (Δ=0 on deterministic engines) from the sub-tolerance
                // slow-reveal hole (Δ=1..3 growth read as agreement).
                mem.stableReads++
                val agreeDelta = if (debugSink != null) bagDiff(fText, foldForCompare(mem.lastText)) else 0
                if (mem.stableReads >= STABLE_READS || capExpired) {
                    if (orderBlocked()) {
                        mem.lastText = text
                        return held { "order-hold agree" }
                    }
                    // A growth chain that settled is a real reveal — the
                    // arming evidence (boundary or not; garble chains end
                    // via revert below, never here).
                    if (mem.holdGrowth) arm()
                    stats.agree++
                    return dispatch(text) {
                        "agree-release n=${mem.stableReads} grow=${mem.holdGrowth} " +
                            "cap=$capExpired Δ=$agreeDelta"
                    }
                }
                mem.lastText = text
                return held { "agree n=${mem.stableReads} Δ=$agreeDelta" }
            }
            if (grows(mem.lastText)) {
                // Still growing. Growth promotes an armed-new hold to a
                // genuine reveal — the cap widens to [HOLD_MAX_MS] (same
                // anchor), so re-check expiry against the new cap rather
                // than the pre-promotion value.
                mem.holdGrowth = true
                mem.grewInBatch = true
                mem.stableReads = 1
                mem.lastText = text
                refreshArm()
                if (SentenceBoundary.endsAtBoundary(text, translationCode)) {
                    if (orderBlocked()) return held { "order-hold boundary" }
                    arm()
                    stats.boundary++
                    return dispatch(text) { "boundary" }
                }
                if (allowPartialPrefix) {
                    val p = SentenceBoundary.terminalPrefix(text, translationCode)
                    if (p != null && (ref == null ||
                            OverlayToolkit.isEvolvingText(foldForCompare(ref), foldForCompare(p)))
                    ) {
                        if (orderBlocked()) return held { "order-hold prefix" }
                        // Sentence-complete prefix grew: upgrade the box
                        // in place, keep holding the ragged tail.
                        arm()
                        stats.prefix++
                        d { "DISPATCH prefix «${snip(p)}» ${ctx()}" }
                        mem.lastDispatched = p
                        return p
                    }
                }
                val growthCapExpired = nowMs - mem.holdOpenCaptureMs >= mem.capMs()
                if (growthCapExpired) {
                    // Cap flush of a growth chain: a slow reveal (or
                    // marquee) — still a settled-enough observation.
                    arm()
                    stats.capFlush++
                    return dispatch(text) { "cap-flush" }
                }
                return held { "grow-cont" }
            }
            // Same-content bands (the LogWriteGate same-region loose tier,
            // ported): within an ACTIVE chain, a read bag-close to the
            // known text is OCR jitter of the same content. Field cases:
            // a garbled-prefix growth read broke the strict relation and
            // dispatched a partial (c33); a hold opened on a garbled full
            // read wedged the correct reads in view-limbo (c9-c13).
            val fLast = foldForCompare(mem.lastText)
            val bandDiff = bagDiff(fText, fLast)
            val bandMax = maxOf(fText.length, fLast.length)
            if (bandMax > 0 && bandDiff <= bandMax * SAME_CONTENT_BAND_FRAC) {
                if (text.length > mem.lastText.length) {
                    // Growth whose garbled prefix failed the strict check.
                    mem.holdGrowth = true
                    mem.grewInBatch = true
                    mem.stableReads = 1
                    mem.lastText = text
                    refreshArm()
                    stats.sameBand++
                    return held { "grow-band Δ=$bandDiff" }
                }
                if (kotlin.math.abs(text.length - mem.lastText.length) <= maxOf(2, bandMax / 10)) {
                    // Same-length jitter: track the newer read; release
                    // comes from true agreement, boundary, or cap.
                    mem.stableReads = 1
                    mem.lastText = text
                    stats.sameBand++
                    return held { "garble-band Δ=$bandDiff" }
                }
                // Much shorter but bag-close: fall through — likely a
                // partial view.
            }
            if (viewOf(mem.lastText)) {
                // Partial VIEW of the chain's known text — a split read,
                // our own box occluding part of the block, or a RE-REVEAL
                // of a known message (repeat dialogue). Affirm without
                // touching the chain or the agreement counter: the fuller
                // reads carry the release.
                val viewGrew = mem.lastViewText.isNotEmpty() &&
                    OverlayToolkit.isEvolvingText(foldForCompare(mem.lastViewText), fText)
                if (viewGrew) {
                    // Growing views = known text re-typing itself. Slide
                    // the cap anchor: the shrink flush must measure how
                    // long a SHRUNKEN text sat stable, not the progress
                    // of a reveal — a mid-reveal flush is exactly the
                    // partial this gate exists to prevent. Bounded by
                    // construction: a view can only grow up to the known
                    // text, so this cannot defer forever.
                    mem.holdOpenCaptureMs = captureAtMs
                    mem.lastViewText = text
                    mem.grewInBatch = true
                    stats.partialViews++
                    return held { "view-grow of=«${snip(mem.lastText)}»" }
                }
                if (capExpired) {
                    // The subset sat stable through the cap — a genuine
                    // shrink-advance. Flush it.
                    stats.shrinkCap++
                    recordBreakClass()
                    return dispatch(text) { "shrink-cap" }
                }
                mem.lastViewText = text
                stats.partialViews++
                return held { "partial-view of=«${snip(mem.lastText)}»" }
            }
            // Real change mid-hold (advance/garble revert) — Level 0, new
            // lineage. Deliberately NOT arming evidence. The snips are the
            // relation-break evidence (the why lambda runs BEFORE dispatch
            // mutates lastText).
            stats.breaks++
            recordBreakClass()
            mem.startChain(bounds, orientation, rtl, lineCount, angleDeg, orientedW, orientedH)
            return dispatch(text) { "break last=«${snip(mem.lastText)}»" }
        }

        // ── No open hold ─────────────────────────────────────────────────
        // Armed gating requires the learned origin corner, not mere region
        // overlap: a name plate inside the dialogue area must not be held.
        val armedTtl = boundaries && nowMs < mem.armedUntilMs
        val armed = armedTtl && mem.armedCornerMatches(bounds, orientation, rtl, angleDeg, orientedW, orientedH)

        // Corner-miss evidence for dispatch reasons (lazy — built only
        // when a sink is installed).
        fun armedNote(): String {
            if (!armedTtl || armed) return ""
            val (cx, cy) = startCorner(bounds, orientation, rtl, angleDeg, orientedW, orientedH)
            return " corner-miss dx=${cx - mem.armedOriginX} dy=${cy - mem.armedOriginY} tol=${mem.armedTolPx}"
        }

        // Fresh text flow-after an ACTIVE reveal (or one released within
        // the grace) is the continuation of the message being typed — a
        // split-off bottom line must not Level-0 past the held top line.
        // Time-scoped by construction: the condition is evaluated against
        // open holds this cycle plus a sub-second grace, never remembered
        // geometry — a battle menu inside yesterday's message box owes
        // nothing.
        fun deferRevealAdjacent(): String? {
            if (armed) return null // armed gating owns armed regions
            if (!revealAdjacentTo(mem, bounds, orientation, nowMs, includeGrace = true)) return null
            if (nowMs - captureAtMs >= ARMED_NEW_MAX_MS) return null // slow-pipeline self-disable
            stats.revealAdjacent++
            mem.openHold(captureAtMs, growth = false, bounds)
            mem.lastText = text
            return "held"
        }

        // First sighting of this region (nothing displayed, nothing
        // remembered as dispatched).
        if (ref == null) {
            if (!armed) {
                if (deferRevealAdjacent() != null) return held { "reveal-adjacent" }
                stats.level0++
                return dispatch(text) {
                    "level0-new${armedNote()}${nearestArmNote(bounds, orientation, rtl, nowMs, angleDeg, orientedW, orientedH)}"
                }
            }
            return gateFreshText(mem, text, bounds, translationCode, captureAtMs, nowMs, allowPartialPrefix, ::dispatch, ::held)
        }

        // Stable vs what's shown: re-place / retry parity (pinhole flap
        // recovery rides the translation cache). An identical re-show
        // flow-after an ACTIVE reveal is the split piece of a repeat
        // viewing — the replace path must not slip past the fresh-text
        // deferral (field: known lines 2/3 re-placing above a held line 1).
        // LENGTH GUARD (ウールオル specimen): a read SHRUNK by ≥2 chars is
        // never a re-show even inside bag tolerance — a reveal whose
        // partial sits exactly Δ=3 short of the full text replace-looped
        // partials forever (3 = the absolute tolerance = 25% of a 12-char
        // sign). Shrunk reads fall through to the view path, which holds
        // them and lets the full read release by agreement one read later.
        if (same(ref) && ref.length - text.length < SHRINK_REPLACE_MIN) {
            if (revealAdjacentTo(mem, bounds, orientation, nowMs, includeGrace = true) &&
                nowMs - captureAtMs < ARMED_NEW_MAX_MS
            ) {
                stats.revealAdjacent++
                mem.openHold(captureAtMs, growth = false, bounds)
                mem.lastText = text
                return held { "reveal-adjacent replace" }
            }
            stats.replace++
            return dispatch(text) { "replace Δ=${bagDiff(fText, foldForCompare(ref))}" }
        }

        // Partial VIEW of the region's known text, with no hold open (the
        // split-head read after a full dispatch — the c102 class). Suppress
        // and open a view-hold: the fuller reads release it by agreement;
        // a genuine shrink-advance flushes at the short cap.
        if (viewOf(mem.lastText)) {
            if (nowMs - captureAtMs >= ARMED_NEW_MAX_MS) {
                stats.shrinkCap++
                return dispatch(text) { "shrink-self-disable" }
            }
            stats.viewHolds++
            mem.openHold(captureAtMs, growth = false, bounds)
            mem.lastViewText = text
            // lastText deliberately NOT updated: the hold's reference is
            // the fuller known text the view is a piece of.
            return held { "view-hold of=«${snip(mem.lastText)}»" }
        }

        if (grows(ref)) {
            // Typewriter growth against the displayed text — the chain
            // continues; its origin was stamped when the lineage began.
            mem.grewInBatch = true
            refreshArm()
            if (SentenceBoundary.endsAtBoundary(text, translationCode)) {
                arm()
                stats.boundary++
                return dispatch(text) { "boundary" }
            }
            if (allowPartialPrefix) {
                val p = SentenceBoundary.terminalPrefix(text, translationCode)
                if (p != null &&
                    OverlayToolkit.isEvolvingText(foldForCompare(ref), foldForCompare(p)) &&
                    OverlayToolkit.isSignificantChange(foldForCompare(p), foldForCompare(ref))
                ) {
                    arm()
                    stats.prefix++
                    d { "DISPATCH prefix «${snip(p)}» ${ctx()}" }
                    mem.openHold(captureAtMs, growth = true, bounds)
                    mem.lastText = text
                    mem.lastDispatched = p
                    return p
                }
            }
            // Slow-pipeline self-disable: the cap expired while this very
            // read was in flight — open-and-release (StabilityHold parity).
            if (nowMs - captureAtMs >= HOLD_MAX_MS) {
                stats.selfDisable++
                return dispatch(text) { "self-disable" }
            }
            stats.growHolds++
            mem.openHold(captureAtMs, growth = true, bounds)
            mem.lastText = text
            return held { "grow-open ref=«${snip(ref)}»" }
        }

        // Real content change (message advance) — a new lineage starts at
        // this read's rect. Unarmed: Level 0 (unless it sits flow-after an
        // active reveal). Armed: the new message is sentence-gated from
        // its first read. Should either branch HOLD, the displayed box (if
        // the caller has one) belongs to the lineage that just died — flag
        // it for [filterVerdicts]' dropNow. A downstream dispatch makes the
        // flag unread, so setting it once here covers both held sites.
        heldNewLineage = true
        mem.startChain(bounds, orientation, rtl, lineCount, angleDeg, orientedW, orientedH)
        if (!armed) {
            if (deferRevealAdjacent() != null) return held { "reveal-adjacent" }
            stats.advance++
            return dispatch(text) { "advance${armedNote()}" }
        }
        return gateFreshText(mem, text, bounds, translationCode, captureAtMs, nowMs, allowPartialPrefix, ::dispatch, ::held)
    }

    /** Is [bounds] directly flow-AFTER a region with an active reveal —
     *  an open hold, or one released by dispatch within
     *  [RELEASE_GRACE_MS] (detection often misses a nascent bottom line
     *  for a cycle, letting the top release first)? Flow-after = below
     *  the held chain for horizontal text, left of it for vertical
     *  (columns run right-to-left in the shipped matrix); the gap band is
     *  denominated in the holder's line extent, with reading-axis spans
     *  required to overlap. Nameplates and ruby sit flow-BEFORE and are
     *  structurally exempt. */
    private fun revealAdjacentTo(
        self: RegionMemory,
        bounds: Rect,
        orientation: TextOrientation,
        nowMs: Long,
        includeGrace: Boolean,
    ): Boolean {
        for (m in regions) {
            if (m === self) continue
            val active = m.holdOpen || (includeGrace &&
                m.lastReleaseMs != 0L && nowMs - m.lastReleaseMs < RELEASE_GRACE_MS)
            if (!active) continue
            val ext = m.chainLineExtentPx
            // Flow-after = starts past the holder's FIRST line and no more
            // than ~1.5 extents past its last — CONTAINED split pieces
            // count (a merged chain rect can span the very lines that later
            // split off; the やれやれ field case), flow-before starts do
            // not (nameplates, ruby).
            val inFlow: Boolean
            val overlap: Int
            val minSpan: Int
            if (orientation == TextOrientation.VERTICAL) {
                inFlow = bounds.right < m.bounds.right - ext / 2 &&
                    bounds.right >= m.bounds.left - (ext + ext / 2)
                overlap = minOf(m.bounds.bottom, bounds.bottom) - maxOf(m.bounds.top, bounds.top)
                minSpan = minOf(m.bounds.height(), bounds.height())
            } else {
                inFlow = bounds.top > m.bounds.top + ext / 2 &&
                    bounds.top <= m.bounds.bottom + (ext + ext / 2)
                overlap = minOf(m.bounds.right, bounds.right) - maxOf(m.bounds.left, bounds.left)
                minSpan = minOf(m.bounds.width(), bounds.width())
            }
            if (!inFlow) continue
            if (minSpan <= 0 || overlap < minSpan * SPAN_OVERLAP_FRAC) continue
            return true
        }
        return false
    }

    /** For an unmatched fresh-region dispatch while ANY armed region
     *  exists: how far is this read's corner from the nearest armed
     *  origin? Tail-fragment/split diagnosis — a fresh dispatch a few
     *  line-extents from an armed origin is a piece of that box's text. */
    private fun nearestArmNote(
        bounds: Rect,
        orientation: TextOrientation,
        rtl: Boolean,
        nowMs: Long,
        angleDeg: Float = 0f,
        orientedW: Float = 0f,
        orientedH: Float = 0f,
    ): String {
        if (debugSink == null) return ""
        val (cx, cy) = startCorner(bounds, orientation, rtl, angleDeg, orientedW, orientedH)
        var best: RegionMemory? = null
        var bestD = Long.MAX_VALUE
        for (m in regions) {
            if (nowMs >= m.armedUntilMs) continue
            val dx = (cx - m.armedOriginX).toLong()
            val dy = (cy - m.armedOriginY).toLong()
            val dd = dx * dx + dy * dy
            if (dd < bestD) {
                bestD = dd
                best = m
            }
        }
        val m = best ?: return ""
        return " nearestArm=(${cx - m.armedOriginX},${cy - m.armedOriginY})"
    }

    /** Armed-region policy for text with no growth evidence yet (first
     *  sighting, or a message advance in an armed region): boundary-final
     *  dispatches at once — instant complete messages pay nothing; anything
     *  else holds, capped at [ARMED_NEW_MAX_MS]. */
    private inline fun gateFreshText(
        mem: RegionMemory,
        text: String,
        bounds: Rect,
        translationCode: String,
        captureAtMs: Long,
        nowMs: Long,
        allowPartialPrefix: Boolean,
        dispatch: (String, () -> String) -> String,
        held: (() -> String) -> String?,
    ): String? {
        if (SentenceBoundary.endsAtBoundary(text, translationCode)) {
            stats.boundary++
            return dispatch(text) { "armed-boundary" }
        }
        if (allowPartialPrefix) {
            val p = SentenceBoundary.terminalPrefix(text, translationCode)
            if (p != null) {
                stats.prefix++
                d { "DISPATCH armed-prefix «${snip(p)}»" }
                mem.openHold(captureAtMs, growth = false, bounds)
                mem.lastText = text
                mem.lastDispatched = p
                return p
            }
        }
        // Armed-hold self-disable on slow pipelines, same anchoring rule as
        // the growth cap.
        if (nowMs - captureAtMs >= ARMED_NEW_MAX_MS) {
            stats.selfDisable++
            return dispatch(text) { "armed-self-disable" }
        }
        stats.armedHolds++
        mem.openHold(captureAtMs, growth = false, bounds)
        mem.lastText = text
        return held { "armed-hold" }
    }

    // ── Region memory plumbing ────────────────────────────────────────────

    private fun beginBatch() {
        affirmed.clear()
        batchReleased.clear()
        batchSwept = 0
        for (m in regions) m.grewInBatch = false
    }

    /** Sweep un-affirmed holds, evict stale memory, return the earliest
     *  open cap deadline. Armed entries are exempt from the TTL — the
     *  learning must survive quiet stretches (cutscenes between talks);
     *  once arming lapses un-refreshed, normal eviction applies. */
    private fun endBatch(nowMs: Long): Long? {
        var deadline: Long? = null
        val it = regions.iterator()
        while (it.hasNext()) {
            val m = it.next()
            if (m.holdOpen && m !in affirmed) {
                m.closeHold()
                batchSwept++
            }
            val armedNow = nowMs < m.armedUntilMs
            if (!armedNow && nowMs - m.lastSeenMs >= MEMORY_TTL_MS) {
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

    private class Match(val mem: RegionMemory, val siblingView: Boolean)

    /** Best-overlap region match: same region when the intersection covers
     *  ≥ [MATCH_MIN_OVERLAP] of the SMALLER rect — robust to a typewriter
     *  box growing in either direction (the chain-start rect and any catch
     *  from the same origin contain one another). A region already claimed
     *  this batch may still match as a SIBLING VIEW when [text] is
     *  bag-contained in its known text — two entries that are both pieces
     *  of one split block must not mint a second region (the グラウス
     *  tail-group class). When overlap finds nothing, ARMED entries get a
     *  second look by origin corner — the anchor survives geometry the
     *  overlap rule can't relate. Unmatched → fresh memory (LRU-capped,
     *  preferring to evict unarmed). */
    private fun matchOrCreate(
        text: String,
        bounds: Rect,
        orientation: TextOrientation,
        rtl: Boolean,
        lineCount: Int,
        nowMs: Long,
        angleDeg: Float = 0f,
        orientedW: Float = 0f,
        orientedH: Float = 0f,
    ): Match {
        val fText = foldForCompare(text)
        // Does this memory's known text RELATE to the read (agreement,
        // growth, or containment, on folded strings)? A relation-bearing
        // candidate outranks a bigger raw overlap: a multi-line read
        // spanning several stale one-line memories must match the chain it
        // actually continues, not the widest stranger under it (the
        // その調子 field case — geometry outvoted an exact prefix).
        fun relatedTo(m: RegionMemory): Boolean {
            if (m.lastText.isEmpty()) return false
            val fLast = foldForCompare(m.lastText)
            return !OverlayToolkit.isSignificantChange(fText, fLast) ||
                OverlayToolkit.isEvolvingText(fLast, fText) ||
                (fText.length > fLast.length && fLast.length >= 6 &&
                    OverlayToolkit.isEvolvingText(fLast.dropLast(2), fText)) ||
                bagSubset(fText, fLast)
        }
        var best: RegionMemory? = null
        var bestOverlap = 0L
        var bestRelated: RegionMemory? = null
        var bestRelatedOverlap = 0L
        var bestAffirmed: RegionMemory? = null
        var bestAffirmedOverlap = 0L
        for (m in regions) {
            val ov = overlapArea(m.bounds, bounds)
            if (ov <= 0L) continue
            val smaller = minOf(area(m.bounds), area(bounds)).coerceAtLeast(1L)
            if (ov.toFloat() / smaller < MATCH_MIN_OVERLAP) continue
            if (m in affirmed) {
                // One entry per region per batch — but a second piece of
                // the same split block is a view of it, not a new region.
                if (ov > bestAffirmedOverlap && bagSubset(fText, foldForCompare(m.lastText))) {
                    bestAffirmedOverlap = ov
                    bestAffirmed = m
                }
                continue
            }
            if (ov > bestOverlap) {
                bestOverlap = ov
                best = m
            }
            if (ov > bestRelatedOverlap && relatedTo(m)) {
                bestRelatedOverlap = ov
                bestRelated = m
            }
        }
        if (bestRelated != null) best = bestRelated
        if (best == null) {
            bestAffirmed?.let {
                it.lastSeenMs = nowMs
                return Match(it, siblingView = true)
            }
            for (m in regions) {
                if (m in affirmed) continue
                if (nowMs >= m.armedUntilMs) continue
                if (m.armedCornerMatches(bounds, orientation, rtl, angleDeg, orientedW, orientedH)) {
                    best = m
                    break
                }
            }
        }
        val m = best
        if (m != null) {
            m.lastSeenMs = nowMs
            return Match(m, siblingView = false)
        }
        if (regions.size >= MAX_REGIONS) {
            val victim = regions.filter { nowMs >= it.armedUntilMs }.minByOrNull { it.lastSeenMs }
                ?: regions.minByOrNull { it.lastSeenMs }
            victim?.let { regions.remove(it) }
        }
        val fresh = RegionMemory(
            bounds, orientation, rtl, lineCount,
            lastText = "", lastDispatched = null, lastSeenMs = nowMs,
            angleDeg = angleDeg, orientedW = orientedW, orientedH = orientedH,
        )
        regions.add(fresh)
        return Match(fresh, siblingView = false)
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

        /** How long arming lasts from its last evidence (each observed
         *  growth refreshes it). Generous on purpose: a region proven to
         *  typewrite stays known through cutscenes and exploration between
         *  talks — re-learning costs a visible fragment. */
        const val ARM_TTL_MS = 300_000L

        /** Unarmed region memory evicted after this long unseen. */
        const val MEMORY_TTL_MS = 45_000L

        /** Fraction of the smaller rect the overlap must cover to match. */
        const val MATCH_MIN_OVERLAP = 0.6f

        /** Origin-corner tolerance as a fraction of the arming chain's
         *  per-line extent. */
        const val ORIGIN_TOL_FRAC = 0.6f
        const val ORIGIN_TOL_MIN_PX = 12

        /** Garble budget for the partial-view containment test, as a
         *  fraction of the partial's length (floor 2 chars). Tight on
         *  purpose: loose containment false-matches unrelated short text
         *  against long char bags ("Chapter Two" ⊂ a 26-char English
         *  sentence at 3 chars of slack). */
        const val SUBSET_TOL_FRAC = 0.15f

        /** A read shorter than the displayed reference by this many chars
         *  is routed to the view path even inside bag tolerance — the
         *  replace path's absolute tolerance (≤3) is 25%+ of short sign
         *  text, and a reveal whose partial lands inside it replace-loops
         *  partials forever (ウールオル specimen, 2026-07-22). */
        const val SHRINK_REPLACE_MIN = 2

        /** Same-content band (in-hold only): a read within this bag-diff
         *  fraction of the chain's known text is OCR jitter or garbled
         *  growth of the SAME content, never a break — the LogWriteGate
         *  same-region loose tier, ported. Real advances differ far more;
         *  marquees sit in the band and cap-flush as before. */
        const val SAME_CONTENT_BAND_FRAC = 0.40f

        /** Flow-adjacency deferral: how long after a hold's dispatch-
         *  release its neighborhood still defers fresh flow-after text —
         *  covers detection missing a nascent bottom line for a cycle
         *  while the top line's agreement fires. Sub-second on purpose:
         *  the condition must die with the reveal. */
        const val RELEASE_GRACE_MS = 800L

        /** Reading-axis span overlap (fraction of the smaller span) for
         *  flow-adjacency. */
        const val SPAN_OVERLAP_FRAC = 0.25f

        /** Thrash breaker: this many break-class dispatches inside the
         *  window trips the region open to Level 0 for the cooldown.
         *  Bounds every un-enumerated read-stream pathology at baseline
         *  churn — the gate is never persistently worse than Level 0. */
        const val BREAKER_TRIP_COUNT = 3
        const val BREAKER_WINDOW_MS = 10_000L
        const val BREAKER_COOLDOWN_MS = 30_000L

        const val MAX_REGIONS = 64

        /** Direction-resolved text start corner: where char #1 of a
         *  message renders. Horizontal LTR → top-left; horizontal RTL →
         *  top-right; vertical → top-right (shipped vertical sources are
         *  ja/zh tategaki, whose columns run right-to-left — an enumerated
         *  fact of the language matrix, not an assumption). A slanted read
         *  (angleDeg != 0, always horizontal by the producer invariant)
         *  resolves the EXACT oriented corner — the oriented rect's start
         *  corner rotated about the AABB center; the plain AABB corner sits
         *  well off where char #1 renders, defeating the armed-origin
         *  discrimination. Coalesced far groups present angle 0 over union
         *  bounds and stay AABB-resolved (documented acceptance — a union
         *  carries no angle to resolve). */
        fun startCorner(
            rect: Rect, orientation: TextOrientation, rtl: Boolean,
            angleDeg: Float = 0f, orientedW: Float = 0f, orientedH: Float = 0f,
        ): Pair<Int, Int> {
            if (angleDeg != 0f && orientedW > 0f && orientedH > 0f) {
                val ux = if (rtl) orientedW / 2f else -orientedW / 2f
                val uy = -orientedH / 2f
                val rad = Math.toRadians(angleDeg.toDouble())
                val c = kotlin.math.cos(rad).toFloat()
                val s = kotlin.math.sin(rad).toFloat()
                val x = rect.exactCenterX() + ux * c - uy * s
                val y = rect.exactCenterY() + ux * s + uy * c
                return DeskewGeometry.roundHalfUp(x) to DeskewGeometry.roundHalfUp(y)
            }
            return when {
                orientation == TextOrientation.VERTICAL -> rect.right to rect.top
                rtl -> rect.right to rect.top
                else -> rect.left to rect.top
            }
        }
    }
}
