package com.playtranslate

import android.graphics.Rect
import com.playtranslate.language.TextAlignment
import com.playtranslate.language.TextOrientation
import com.playtranslate.ocr.core.LayoutAnalyzer
import com.playtranslate.ui.TextBox
import kotlin.math.abs

/**
 * Text-space single-cycle reconciler — "Level 0". Ported from the `scanlines`
 * branch (built there for swept clean composites); on this branch it drives
 * [CleanStreamOverlayMode], whose API 34+ single-app MediaProjection stream
 * delivers frames that are clean by construction.
 *
 * The mode answers "what changed" in TEXT space, never in pixel space: every
 * cycle it OCRs a clean frame and hands the fresh groups plus the
 * currently-displayed boxes here. This class decides, per region, whether an
 * overlay is still valid, has changed, or has vanished, and which regions to
 * translate — all from a SINGLE read.
 *
 * ## Level 0: read the screen, translate the screen, update the screen
 * There is no confirmation gate, no pending state, no grace counters, and no
 * cross-cycle memory of any kind inside this class — it is a pure function of
 * (groups, boxes) and holds NOTHING between calls. The only cross-cycle memory
 * in the whole mode is the mode's currently-displayed box list, which is handed
 * back in as [boxes]. Every artifact self-heals within one cycle by
 * construction; that is accepted and intended (see the ledger below). (The
 * mode may additionally run a bounded typewriter hold over CHANGED verdicts —
 * see [StabilityHold] — which is deliberately OUTSIDE this class so the
 * reconciler stays a pure single-read function.)
 *
 * ## Verdicts (see [reconcile])
 *  - **KEEP** — a box pairs with a group whose text is not a significant change
 *    ([OverlayToolkit.isSignificantChange]) and the box already carries a
 *    translation. It passes through verbatim. The dominant steady-state verdict.
 *  - **RETRANSLATE** — a paired box is (re)translated at the group's region.
 *    Two paths lead here: the text differs (the region's content changed, old
 *    box dropped), or the text matches but the box's translation is empty (an
 *    earlier translation failed or returned blank — a plain retry). Either way
 *    the region is emitted in [Verdicts.toTranslate] on THIS read.
 *  - **REMOVE** — a box pairs with no group. It is removed immediately, on the
 *    first empty read. One empty read suffices; there is no grace counter.
 *  - **NEW** — a group pairs with no box. It is translated immediately, on the
 *    first sighting, emitted in [Verdicts.toTranslate].
 *
 * ## Accepted artifact ledger
 * Translating on the first read means transient reads reach the translator:
 *  - A typewriter reveal translates each partial string it is caught mid-frame
 *    ("H", "He", "Hel", …). Each partial self-corrects on the next cycle once
 *    the region's text differs again, and lands on the final translation once
 *    the text stops changing.
 *  - A one-cycle OCR miss (ML Kit hiccup) that drops a region for a single
 *    read removes its box; the box re-appears (re-translated) the moment the
 *    region is read again. The user sees a one-cycle blink.
 *  - A garbage read at a live region translates the garbage once, then
 *    self-heals on the next clean read (text differs → RETRANSLATE).
 * These are the deliberate cost of zero cross-cycle state: the machine can
 * never wedge, stall, or carry stale confirmation — it always reflects the last
 * read, and the last read is at most one cycle old.
 *
 * ## Two deliberate fuzziness layers
 * Neither the region geometry nor the OCR text is ever exact, so both matching
 * layers are intentionally tolerant:
 *  - **Region fuzz** — box↔group pairing is by [LayoutAnalyzer.wouldGroup] in
 *    [LayoutAnalyzer.GroupingMode.CROSS_FRAME_SAME_REGION] mode, NOT by rect
 *    equality. OCR bounds jitter a few pixels every frame (glyph mix,
 *    anti-aliasing); a same-region re-read must still pair with its box or the
 *    whole page would churn REMOVE+NEW on pixel noise.
 *  - **Character fuzz** — a paired box counts as unchanged when
 *    [OverlayToolkit.isSignificantChange] is false: a bag-of-characters
 *    tolerance that absorbs a swapped or dropped glyph or two, so a single OCR
 *    misread does not force a needless retranslation of otherwise-stable text.
 * Pairing is by GEOMETRY only — text is judged AFTER pairing, so a same-region
 * re-OCR whose text genuinely changed still pairs (→ RETRANSLATE) instead of
 * falling through to REMOVE + NEW.
 *
 * Pure Kotlin: the only platform type it touches is [android.graphics.Rect]
 * (via [LayoutAnalyzer.wouldGroup] geometry). It injects nothing, holds no
 * state, and is fully deterministic — data in, verdicts out — so it unit-tests
 * on the JVM like [Classification]. Stateless since Level 0, hence an `object`.
 */
object ScanlineReconciler {

    /** An UNCHANGED box is only re-emitted onto its group's fresh bounds when
     *  some edge has moved more than this many px. Below this is OCR jitter on
     *  static text — repositioning that would make boxes shiver every cycle. */
    private const val REPOSITION_HYSTERESIS_PX = 5

    /** The data the mode needs to (re)build one box: an OCR group reduced to
     *  the fields [OverlayToolkit.buildPlaceholderBoxes] and
     *  [OverlayToolkit.translatePlaceholders] consume. */
    data class Region(
        val text: String,
        val bounds: Rect,
        val lineCount: Int,
        val orientation: TextOrientation,
        val alignment: TextAlignment,
        /** The displayed box this region replaces: the paired box for a
         *  RETRANSLATE (changed text, or blank-translation retry), null for a
         *  NEW sighting. Lets [StabilityHold] scope its typewriter deferral to
         *  CHANGED verdicts only — NEW text must never wait. */
        val replacesBox: TextBox? = null,
    )

    /**
     * One cycle's outcome. The mode renders `keptBoxes + toTranslate` (kept
     * boxes verbatim, toTranslate as a placeholder skeleton then translated in
     * place) and hides the overlay when both are empty. [removals] and the four
     * counts are informational (stats line / panel sync). Invariants:
     * `keptBoxes.size == unchanged + held`, `removals.size == missing`,
     * `toTranslate.size == changed + added`, and `repositioned <= unchanged`.
     */
    data class Verdicts(
        /** KEEP boxes. Verbatim when static; carrying the group's fresh bounds
         *  when the region drifted (see [repositioned]) — the translation is
         *  never touched either way. HELD boxes (outside a scoped call's
         *  evidence — see [reconcile]'s scope param) also pass through here,
         *  always verbatim. */
        val keptBoxes: List<TextBox>,
        /** Regions to translate this cycle — RETRANSLATE (changed/retry) and
         *  NEW alike. Translated and rendered now. Changed entries precede
         *  added ones. */
        val toTranslate: List<Region>,
        /** Boxes removed this cycle (paired with no group). */
        val removals: List<TextBox>,
        val unchanged: Int,
        val changed: Int,
        val missing: Int,
        val added: Int,
        /** How many of [keptBoxes] were re-emitted onto their group's fresh
         *  bounds because the region drifted beyond [REPOSITION_HYSTERESIS_PX].
         *  A subset of [unchanged] (not a separate verdict); informational. */
        val repositioned: Int,
        /** Scoped calls only: boxes outside the evidence scope, passed through
         *  verbatim (never judged, never removed). Always 0 for full-frame
         *  calls. `keptBoxes.size == unchanged + held`. */
        val held: Int = 0,
    )

    /**
     * Reconcile [groups] (fresh OCR) against [boxes] (currently displayed).
     * [groups] bounds and [boxes] bounds must share one coordinate space (the
     * mode keeps both in OCR-crop space — placeholders are built from group
     * bounds). See the class doc for the verdict semantics. Holds no state: the
     * whole outcome is a function of these arguments.
     *
     * [scope] — the SCOPE CONTRACT ("scope ≡ exactly the pixels OCR saw").
     * null = full-frame evidence, current semantics. Non-null = the OCR input
     * covered only these rects: boxes NOT intersecting the scope are HELD —
     * passed through verbatim, never judged, never removed (absence of
     * out-of-scope evidence is not evidence of absence) — and groups outside
     * the scope are IGNORED (a cropped OCR cannot assert anything beyond its
     * crop). A box merely straddling the scope edge counts as in-scope.
     * The clean-stream mode always passes null (full frames); the machinery is
     * kept because it is pure, tested, and what any future cropped-read
     * optimization would need.
     */
    fun reconcile(
        groups: List<OcrManager.OcrGroup>,
        boxes: List<TextBox>,
        scope: List<Rect>? = null,
    ): Verdicts {
        val inScopeGroup = BooleanArray(groups.size) { gi ->
            scope == null || scope.any { r -> Rect.intersects(r, groups[gi].bounds) }
        }
        val inScopeBox = BooleanArray(boxes.size) { bi ->
            scope == null || scope.any { r -> Rect.intersects(r, boxes[bi].bounds) }
        }
        val groupClaimed = BooleanArray(groups.size)
        val boxGroup = arrayOfNulls<Int>(boxes.size)

        // Greedy pairing: each box claims its best-overlapping, still-unclaimed
        // group. Pair by GEOMETRY only (wouldGroup) — text is judged afterward
        // so a same-region re-OCR whose text changed still pairs (→ RETRANSLATE)
        // instead of falling through to REMOVE + NEW.
        for ((bi, box) in boxes.withIndex()) {
            if (!inScopeBox[bi]) continue // HELD below; never pairs
            var bestG = -1
            var bestOverlap = -1L
            var bestDist = Long.MAX_VALUE
            for ((gi, g) in groups.withIndex()) {
                if (groupClaimed[gi] || !inScopeGroup[gi]) continue
                if (!pairs(box.bounds, g.bounds, box.orientation, box.lineCount, g.lines.size)) continue
                val ov = overlapArea(box.bounds, g.bounds)
                val dist = centerDist2(box.bounds, g.bounds)
                if (ov > bestOverlap || (ov == bestOverlap && dist < bestDist)) {
                    bestOverlap = ov; bestDist = dist; bestG = gi
                }
            }
            if (bestG >= 0) {
                boxGroup[bi] = bestG
                groupClaimed[bestG] = true
            }
        }

        val kept = ArrayList<TextBox>()
        val toTranslate = ArrayList<Region>()
        val removals = ArrayList<TextBox>()
        var uCount = 0; var cCount = 0; var mCount = 0; var nCount = 0; var rCount = 0
        var hCount = 0

        fun regionOf(g: OcrManager.OcrGroup, replaces: TextBox? = null) = Region(
            text = g.text,
            bounds = g.bounds,
            lineCount = g.lines.size.coerceAtLeast(1),
            orientation = g.orientation,
            alignment = g.alignment,
            replacesBox = replaces,
        )

        // Displayed boxes → KEEP / RETRANSLATE / REMOVE.
        for ((bi, box) in boxes.withIndex()) {
            if (!inScopeBox[bi]) {
                // HELD — out of scope: verbatim pass-through, no judgment.
                kept.add(box); hCount++
                continue
            }
            val gi = boxGroup[bi]
            if (gi == null) {
                // REMOVE — the region is gone. One empty read suffices.
                removals.add(box); mCount++
            } else {
                val g = groups[gi]
                if (OverlayToolkit.isSignificantChange(g.text, box.sourceText)) {
                    // Text differs → retranslate the new text; old box dropped.
                    toTranslate.add(regionOf(g, replaces = box)); cCount++
                } else if (box.translatedText.isEmpty()) {
                    // Same text but the prior translation is blank (failed) —
                    // retry it. Bucketed with the changed retranslations.
                    toTranslate.add(regionOf(g, replaces = box)); cCount++
                } else {
                    // Same text, already translated → keep it. If the region has
                    // DRIFTED beyond OCR jitter (a scroll/pan) carry the box's
                    // existing translation onto the group's fresh bounds so the
                    // overlay tracks the moving text — no re-translate. Below the
                    // hysteresis it passes through verbatim so static text does
                    // not shiver. Either way it counts as unchanged; drift is
                    // tallied separately in [Verdicts.repositioned].
                    if (boundsDrifted(box.bounds, g.bounds)) {
                        kept.add(box.copy(bounds = g.bounds)); rCount++
                    } else {
                        kept.add(box)
                    }
                    uCount++
                }
            }
        }

        // Unclaimed groups → NEW → translate immediately.
        for ((gi, g) in groups.withIndex()) {
            if (groupClaimed[gi] || !inScopeGroup[gi]) continue
            toTranslate.add(regionOf(g)); nCount++
        }

        return Verdicts(
            keptBoxes = kept,
            toTranslate = toTranslate,
            removals = removals,
            unchanged = uCount,
            changed = cCount,
            missing = mCount,
            added = nCount,
            repositioned = rCount,
            held = hCount,
        )
    }

    /**
     * Decompose "the frame minus [covered]" into rects — the SCOPE for a
     * masked scan: OCR ran on a frame whose [covered] regions were blacked
     * out, so the evidence is exactly the complement. Handing this to
     * [reconcile]'s scope makes every displayed box HOLD (each is inside its
     * own blacked rect) while uncovered groups pair/NEW normally — one scope
     * contract, no asymmetric special case. Guillotine subtraction: each
     * covered rect splits every intersecting scope rect into ≤4 remainders.
     * Pure; JVM-tested. Unused by the clean-stream mode (full frames); kept
     * with the scope machinery.
     */
    internal fun uncoveredScope(frameW: Int, frameH: Int, covered: List<Rect>): List<Rect> =
        subtract(listOf(Rect(0, 0, frameW, frameH)), covered)

    /**
     * Guillotine subtraction of [covered] from [seeds] — the general scope
     * builder behind [uncoveredScope]: a scoped read's evidence is its
     * inflated trigger rects MINUS every region whose pixels were blacked
     * before OCR — those pixels are our own paint, and "scope ≡ exactly the
     * pixels OCR saw" means the true ones. Each covered rect splits every
     * intersecting seed into ≤4 remainders. Pure; JVM-tested.
     */
    internal fun subtract(seeds: List<Rect>, covered: List<Rect>): List<Rect> {
        var scope = seeds.filter { !it.isEmpty }.map { Rect(it) }.toMutableList()
        for (c in covered) {
            if (c.isEmpty) continue
            val next = mutableListOf<Rect>()
            for (r in scope) {
                if (!Rect.intersects(r, c)) {
                    next.add(r)
                    continue
                }
                // Top / bottom slabs, then left / right slivers of the middle band.
                if (c.top > r.top) next.add(Rect(r.left, r.top, r.right, c.top))
                if (c.bottom < r.bottom) next.add(Rect(r.left, c.bottom, r.right, r.bottom))
                val midTop = maxOf(r.top, c.top)
                val midBottom = minOf(r.bottom, c.bottom)
                if (c.left > r.left) next.add(Rect(r.left, midTop, c.left, midBottom))
                if (c.right < r.right) next.add(Rect(c.right, midTop, r.right, midBottom))
            }
            scope = next
            if (scope.isEmpty()) break
        }
        return scope
    }

    // ── Geometry helpers ─────────────────────────────────────────────────

    /** True when any edge of [a] and [b] differs by more than
     *  [REPOSITION_HYSTERESIS_PX] — i.e. the paired region moved far enough to
     *  be real drift rather than per-frame OCR jitter. */
    private fun boundsDrifted(a: Rect, b: Rect): Boolean =
        abs(a.left - b.left) > REPOSITION_HYSTERESIS_PX ||
            abs(a.top - b.top) > REPOSITION_HYSTERESIS_PX ||
            abs(a.right - b.right) > REPOSITION_HYSTERESIS_PX ||
            abs(a.bottom - b.bottom) > REPOSITION_HYSTERESIS_PX

    /** Do these two rects represent the same on-screen region? Same predicate
     *  [Classification] uses for cross-frame overlay matching. */
    private fun pairs(a: Rect, b: Rect, orientation: TextOrientation, aLn: Int, bLn: Int): Boolean =
        LayoutAnalyzer.wouldGroup(
            a, b, orientation,
            mode = LayoutAnalyzer.GroupingMode.CROSS_FRAME_SAME_REGION,
            aLineCount = aLn.coerceAtLeast(1),
            bLineCount = bLn.coerceAtLeast(1),
        )

    private fun overlapArea(a: Rect, b: Rect): Long {
        val ix = minOf(a.right, b.right) - maxOf(a.left, b.left)
        val iy = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        if (ix <= 0 || iy <= 0) return 0L
        return ix.toLong() * iy.toLong()
    }

    private fun centerDist2(a: Rect, b: Rect): Long {
        val dx = (a.centerX() - b.centerX()).toLong()
        val dy = (a.centerY() - b.centerY()).toLong()
        return dx * dx + dy * dy
    }
}
