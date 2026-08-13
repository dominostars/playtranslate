package com.playtranslate

import android.graphics.Rect
import com.playtranslate.language.TextAlignment
import com.playtranslate.language.TextOrientation
import com.playtranslate.ocr.core.LayoutAnalyzer
import com.playtranslate.ui.TextBox

/**
 * OCR-to-overlay classification for live pinhole mode.
 *
 * Extracted from [PinholeOverlayMode.runCycle] so the classification logic can
 * be unit-tested without a live capture pipeline. Pure data transformations
 * only — no bitmap work, no side effects, no platform dependencies beyond
 * [android.graphics.Rect].
 *
 * See [classifyOcrResults] for the contract and [ClassificationResult] for
 * the sets it produces, and [cascadeStaleRemovals] for the neighbor-expansion
 * pass run after classification.
 */

/**
 * An OCR group that needs a placeholder box rendered in the overlay layer —
 * either "new text" (no existing overlay is near it) or a content-match
 * replacement (it looks like an existing overlay's source text at a new
 * position, so we redraw at the OCR position).
 *
 * [bounds] is in OCR-crop space (the same space as
 * [OcrManager.OcrResult.groupBounds]). Callers that need bitmap-space rects
 * must still add the crop offsets downstream.
 */
data class FarGroup(
    val text: String,
    val bounds: Rect,
    val lineCount: Int,
    val orientation: TextOrientation = TextOrientation.HORIZONTAL,
    val alignment: TextAlignment = TextAlignment.LEFT,
    /** True iff this entry is a content-match replacement (classify step 1),
     *  possibly widened by coalesced continuations (step 3). The content
     *  match promised this text a placement at its new position, and
     *  [deferDyingBoxFragments] honors that promise unconditionally — a
     *  paired FAR is never deferred, even when its new position abuts a
     *  dying box (conversation close: the plate→prompt replacement lands
     *  beside the dying message box every time). */
    val paired: Boolean = false,
    /** Slant carried from the OCR group; oriented dims ride with it, 0 when
     *  upright. A coalesced merge is upright BY CONSTRUCTION: the coalesce
     *  gate refuses candidates when either side carries an angle (an AABB
     *  union of two groups is not a rotated rect), so these fields are only
     *  ever verbatim single-group values. */
    val angleDeg: Float = 0f,
    val orientedWidth: Float = 0f,
    val orientedHeight: Float = 0f,
)

/**
 * Vacancy memory for a content-match relocation: [bounds] (OCR-crop space,
 * same space as [TextBox.bounds] and OCR group bounds) held [text] until a
 * content match moved its box away, within the last
 * [PinholeCalibration.TOMBSTONE_LIFESPAN_LOOKS] full looks.
 *
 * Content match's "same text elsewhere = the text moved" inference silently
 * assumes text is unique on screen, and the OCR blackout makes the box's own
 * region unreadable, so the inference can never be checked pixel-side (the
 * pinholes false-KEEP on exactly the low-contrast background movers content
 * match was built for). The two worlds — one moving text vs two static
 * duplicates — are observationally identical within a single cycle; the
 * distinguishing evidence only exists ACROSS cycles: after relocating A→B,
 * the same text read AT A again is proof it never left. Without this memory
 * the relocation is symmetric and memoryless, so two duplicates ping-pong
 * one box between them forever (duplicate-text oscillation, 2026-07-30).
 *
 * So every distant relocation leaves a tombstone at the vacated rect, and a
 * fresh group matching a live tombstone (fuzzy-same text, tight-same rect —
 * see [PinholeCalibration.TOMBSTONE_MATCH_SLOP_PX] for why tight) is barred
 * from content-matching: it falls through to the far path and spawns its own
 * box. A true mover reads at some NEW position that matches no tombstone, so
 * the single-box-follows behavior is untouched. Aging/expiry lives with the
 * caller ([PinholeOverlayMode]); classification stays pure.
 */
data class Tombstone(
    val text: String,
    val bounds: Rect,
)

/** Same on-screen region within OCR re-read jitter: every edge within
 *  [PinholeCalibration.TOMBSTONE_MATCH_SLOP_PX]. Used both to match a fresh
 *  group against a tombstone and to decide whether a content match is a
 *  relocation (mints a tombstone) or an in-place update (mints nothing). */
private fun tombstoneSameRegion(a: Rect, b: Rect): Boolean =
    kotlin.math.abs(a.left - b.left) <= PinholeCalibration.TOMBSTONE_MATCH_SLOP_PX &&
        kotlin.math.abs(a.top - b.top) <= PinholeCalibration.TOMBSTONE_MATCH_SLOP_PX &&
        kotlin.math.abs(a.right - b.right) <= PinholeCalibration.TOMBSTONE_MATCH_SLOP_PX &&
        kotlin.math.abs(a.bottom - b.bottom) <= PinholeCalibration.TOMBSTONE_MATCH_SLOP_PX

/**
 * Dying-box fragment-deferral predicate: does [rect] abut (intersect after
 * inflating each dying rect by [inflatePx]) any box being pinhole-removed
 * this cycle?
 *
 * A fresh OCR group next to a box being removed this cycle is suspect by
 * construction: the group was read while the box still blinded the region
 * it borders, so it may be only the visible tail of the text the removal
 * is about to uncover (dialogue advance to a slightly longer message —
 * the fragment-box dance, 2026-07-10). Placing it strands a fragment over
 * the new message's end; deferring costs one floor-paced forced look,
 * after which the full region is uncovered and whatever is really there
 * places whole.
 *
 * Geometry deliberately CANNOT distinguish a continuation tail from an
 * independent neighbor — the discriminator is whether the box's removal
 * UNCOVERS its region this cycle (a box that survives protects its
 * neighbors' placements). Two removal families qualify:
 *
 *  - Pinhole REMOVEs — the under-box pixels changed. EVERY one counts,
 *    including boxes that also content-matched: the match only proves the
 *    box's text reappeared elsewhere, not that the removal uncovers
 *    background (taxi-prompt trace 2026-07-10 — the dying talk prompt's
 *    text re-matched the dialogue name plate, the dying set went empty,
 *    and a broken message fragment placed, churning the scene for five
 *    cycles).
 *  - Adjacency-stale + cascade removals (added 2026-07-16) — the box is
 *    dying precisely BECAUSE fresh adjacent text proved its region
 *    mid-update, so a bordering fragment is the same partial-read risk
 *    (グラウス trace 2026-07-12 c4: the partial typewriter box died stale
 *    via its same-row tail, and the freshly-revealed third row abutting
 *    it at 13px placed as a stranded solo box for a cycle).
 *
 * Content-match-ONLY removals do NOT qualify: they are routine position
 * updates (scrolling text content-matches every cycle), and deferring
 * fresh text entering beside them would starve it for as long as the
 * motion lasts. The content-match no-defer promise rides on the paired
 * replacement itself — [FarGroup.paired], honored by
 * [deferDyingBoxFragments]. [inflatePx] is tight
 * ([PinholeCalibration.FRAGMENT_DEFER_ABUT_PX]) — the old FAR-suppression
 * guard's 0.5W/1.5H inflation reached ~200px and starved legitimate menu
 * items near an unrelated dying box; abutment reaches only text that
 * borders the uncovered region itself. No state, no watch lists: the
 * condition evaporates with the dying box, so nothing can be deferred
 * twice.
 */
fun abutsAnyInflated(dying: List<Rect>, rect: Rect, inflatePx: Int): Boolean =
    dying.any { d ->
        val inflated = Rect(d).apply { inset(-inflatePx, -inflatePx) }
        Rect.intersects(inflated, rect)
    }

/**
 * Step-9b deferral filter: drop FAR groups that abut (per
 * [abutsAnyInflated]) any box dying this cycle — except paired
 * content-match replacements ([FarGroup.paired]), which place
 * unconditionally. Dropped groups are deferred, not lost: the removal
 * that triggered the drop already forces a floor-paced follow-up look
 * that re-OCRs the uncovered region and places whatever is really there,
 * complete.
 *
 * [dyingRects] must be the RENDERED (padded) rects of every box whose
 * removal uncovers its region this cycle: pinhole REMOVEs plus
 * stale/cascade removals, content-matched or not — see
 * [abutsAnyInflated]'s kdoc for the two families, why a content match
 * must not shrink the dying set, and why content-match-ONLY removals
 * stay out. [FarGroup.bounds] are OCR-crop space; [coords] maps them
 * into the dying rects' bitmap space.
 */
fun deferDyingBoxFragments(
    farGroups: List<FarGroup>,
    dyingRects: List<Rect>,
    coords: FrameCoordinates,
    inflatePx: Int,
): List<FarGroup> {
    if (dyingRects.isEmpty() || farGroups.isEmpty()) return farGroups
    return farGroups.filter { far ->
        far.paired ||
            !abutsAnyInflated(dyingRects, coords.ocrToBitmap(far.bounds), inflatePx)
    }
}

/**
 * Output of [classifyOcrResults].
 *
 * - [contentMatchRemovals] — indices into the input `boxes` list of cached
 *   overlays whose source text matches an OCR group and whose height is
 *   within 50% of the OCR group's height. These boxes should be removed and
 *   replaced by a placeholder at the OCR position (the replacement is queued
 *   into [farOcrGroups]).
 * - [staleOverlayIndices] — indices into the input `boxes` list of cached
 *   overlays that overlap (via [OcrManager.wouldGroup]) with an OCR group
 *   that isn't a content match. Expand via [cascadeStaleRemovals] before
 *   resolving the final removal set.
 * - [farOcrGroups] — OCR groups that need a new placeholder: either a
 *   content-match replacement (step 7a) or brand-new text with no nearby
 *   existing overlay (step 7c).
 * - [vacated] — tombstones minted this pass: one per content match whose new
 *   position is NOT a tight same-region match of the vacated box (a true
 *   relocation, not an in-place update). The caller ages these across looks
 *   and feeds the live set back in as `tombstones`.
 * - [tombstoneBlocks] — how many OCR groups a live tombstone barred from
 *   content-matching this pass (each fell through to proximity/far and
 *   spawned or staled normally). Telemetry for the transitions log — a
 *   nonzero count is the duplicate-spawn signature in a field trace.
 */
data class ClassificationResult(
    val contentMatchRemovals: Set<Int>,
    val staleOverlayIndices: Set<Int>,
    val farOcrGroups: List<FarGroup>,
    val vacated: List<Tombstone> = emptyList(),
    val tombstoneBlocks: Int = 0,
)

/**
 * Classify each OCR group against the currently-cached overlay boxes.
 *
 * For each OCR group in [ocrResult.groupTexts]:
 *
 *   1. **Content match** — walk `boxes` looking for a non-dirty, not-yet-
 *      matched box whose `sourceText` is NOT a significant change from the
 *      OCR text (per [OverlayToolkit.isSignificantChange]) AND whose height
 *      is within 50% of the OCR group's height. First match wins; the box
 *      is added to [ClassificationResult.contentMatchRemovals] and a fresh
 *      placeholder is queued into [ClassificationResult.farOcrGroups] at
 *      the OCR position. Barred entirely when the group matches a live
 *      [Tombstone] (see its kdoc — the group is a duplicate re-read at a
 *      just-vacated rect, not a move); a match at a genuinely new position
 *      mints a tombstone at the vacated rect into
 *      [ClassificationResult.vacated].
 *   2. **Proximity check** — if no content match, check every non-dirty,
 *      non-content-matched box: if its bitmap-space rect `wouldGroup` with
 *      the OCR group's bitmap-space rect, mark the box stale. A single OCR
 *      group can stale multiple boxes (they'll all be cascaded and removed).
 *   3. **Far** — if nothing overlapped, queue the OCR group as new text
 *      into [ClassificationResult.farOcrGroups].
 *
 * ## Coordinate spaces
 *
 * - `boxes[i].bounds` — OCR-crop space (set during an earlier capture,
 *   relative to the bitmap crop at that time).
 * - `ocrBitmapRects[i]` — bitmap space, derived from each box's OCR bounds
 *   via [FrameCoordinates.ocrToBitmap]. These are the *text* rects, not
 *   the rendered overlay rects: `TranslationOverlayView.rebuildChildren`
 *   inflates the rendered overlay by ~14 px boxPadding per side so the
 *   translation has visual breathing room, and using that padded rect
 *   here would falsely reach across genuine paragraph gaps and trigger
 *   wouldGroup against unrelated neighbors. Pinhole detection still uses
 *   the padded rendered rects (it samples actual on-screen pixels);
 *   classification reasons about text relationships and needs the
 *   unpadded rects so both sides of the wouldGroup compare in the same
 *   coordinate space as the OCR-derived `ocrFullRect`. Must correspond
 *   index-for-index with `boxes`. Indices past `ocrBitmapRects.size` are
 *   skipped (defensive against a mid-cycle size mismatch).
 * - `ocrResult.groupBounds[i]` — OCR-crop space, converted to bitmap space
 *   via `coords.ocrToBitmap(...)` for the `wouldGroup` comparison.
 * - `coords.cropLeft` / `coords.cropTop` should be the pipeline's crop
 *   offsets (produced alongside this OCR result), not the mode's cached
 *   instance fields, so that a mid-session statusBarHeight toggle doesn't
 *   compare rects from two different crop frames.
 */
fun classifyOcrResults(
    ocrResult: OcrManager.OcrResult,
    boxes: List<TextBox>,
    ocrBitmapRects: List<Rect>,
    coords: FrameCoordinates,
    rtl: Boolean = false,
    tombstones: List<Tombstone> = emptyList(),
): ClassificationResult {
    val staleOverlayIndices = mutableSetOf<Int>()
    val contentMatchRemovals = mutableSetOf<Int>()
    val farOcrGroups = mutableListOf<FarGroup>()
    val vacated = mutableListOf<Tombstone>()
    var tombstoneBlocks = 0
    // Indices in [farOcrGroups] that originated from a content-match
    // (i.e. paired FARs queued at step 1 below). The Far branch's
    // coalesce step at step 3 only considers these as eligible merge
    // targets — fresh FARs added later in the same loop are NOT
    // eligible. See the gate kdoc at step 3 for the reasoning.
    val pairedFarIndices = mutableSetOf<Int>()

    for ((ocrIdx, group) in ocrResult.groups.withIndex()) {
        val ocrText = group.text
        val ocrBound = group.bounds
        val ocrH = ocrBound.height()

        // 1. Content match: same source text + similar size → position update.
        //    Tombstone gate first: a group at a rect a same-text box was
        //    relocated AWAY from within the last couple of looks is proof
        //    the text never left that rect — a duplicate, not a move (see
        //    [Tombstone]). Barred from content-matching entirely; it falls
        //    through to proximity/far and spawns its own box.
        val tombstoned = tombstones.any { t ->
            !OverlayToolkit.isSignificantChange(ocrText, t.text) &&
                tombstoneSameRegion(t.bounds, ocrBound)
        }
        if (tombstoned) tombstoneBlocks++
        var contentMatched = false
        if (!tombstoned) for ((boxIdx, box) in boxes.withIndex()) {
            if (box.dirty) continue
            if (boxIdx in contentMatchRemovals) continue
            if (box.sourceText.isNotEmpty() &&
                !OverlayToolkit.isSignificantChange(ocrText, box.sourceText)) {
                val boxH = box.bounds.height()
                val maxH = maxOf(ocrH, boxH)
                if (maxH > 0 && kotlin.math.abs(ocrH - boxH) < maxH * 0.5) {
                    contentMatchRemovals.add(boxIdx)
                    val lc = group.lines.size
                    val orient = group.orientation
                    val align = group.alignment
                    // A relocation (new position, not an in-place update
                    // within re-read jitter) mints a tombstone at the
                    // vacated rect. Copied: Rect is mutable and the box
                    // is about to be removed.
                    if (!tombstoneSameRegion(box.bounds, ocrBound)) {
                        vacated.add(Tombstone(box.sourceText, Rect(box.bounds)))
                    }
                    // Record the about-to-be index so step 3's coalesce
                    // step can identify this FAR as the paired-from-
                    // content-match target a later fresh OCR fragment may
                    // legitimately stitch onto.
                    pairedFarIndices.add(farOcrGroups.size)
                    farOcrGroups.add(FarGroup(
                        ocrText, ocrBound, lc, orient, align, paired = true,
                        angleDeg = group.angleDeg,
                        orientedWidth = group.orientedWidth,
                        orientedHeight = group.orientedHeight,
                    ))
                    contentMatched = true
                    break
                }
            }
        }
        if (contentMatched) continue

        // 2. Proximity check: near existing overlay → stale.
        val ocrFullRect = coords.ocrToBitmap(ocrBound)
        var nearExisting = false
        val orient = group.orientation
        val ocrLineCount = group.lines.size
        for (boxIdx in boxes.indices) {
            if (boxIdx >= ocrBitmapRects.size) continue
            if (boxes[boxIdx].dirty) continue
            if (boxIdx in contentMatchRemovals) continue
            // CROSS_FRAME_SAME_REGION: comparing a rect from the prior overlay
            // state against a fresh OCR rect. Substantial overlap is evidence
            // the two represent the same on-screen region (typewriter reveal,
            // partial occlusion) even when heights diverge.
            //
            // Per-line normalization applies asymmetrically — only when the
            // fresh side has *more* lines than the cached side (paragraph
            // growth: cached single line + freshly-revealed multi-line
            // continuation). The reverse (cached multi-line + adjacent
            // fresh single-line) is geometrically identical to the legit
            // case but is far more likely to be unrelated text below the
            // cached paragraph than a one-line continuation of it. In
            // pinhole mode the cached region is filled with bg before OCR,
            // so fresh text relative to a cached paragraph is almost always
            // *new lines being revealed*, not fewer lines becoming
            // adjacent. Falling back to raw heights for the shrink
            // direction preserves pre-fix behavior there (the cached
            // translation stays, the new adjacent text gets its own
            // placeholder via the far path). Two legitimate shrink-
            // direction shapes are rescued by tightly-gated directional
            // probes below: a same-row continuation of the box's LAST
            // line (typewriter tail, campfire trace 2026-07-10 —
            // inlineContinuesLastLine) and a single next row revealed
            // directly BELOW the box (line-by-line typewriter, グラウス
            // trace 2026-07-12 — blockContinuesBelow). The whole-box path
            // stays de-normalized so every shape outside those two keeps
            // the pre-fix behavior.
            val boxRect = ocrBitmapRects[boxIdx]
            val boxLineCount = boxes[boxIdx].lineCount
            // Per-line normalization only applies when orientations agree
            // — TextBox.lineCount is in the wrap-axis sense (rows for
            // horizontal, columns for vertical), so feeding a vertical
            // cached column-count into wouldGroup's horizontal path (or
            // vice versa) would normalize along the wrong axis. Falling
            // back to raw heights is safe: cross-orientation rect shapes
            // are geometrically dissimilar enough that the size-ratio
            // gate rejects on raw dimensions almost always.
            //
            // Strict `>` is deliberate — equal line counts (2-line cached
            // ↔ 2-line fresh, e.g. an in-place paragraph update) take the
            // raw path, which gives looser vgap/align thresholds (raw
            // refH = full extent vs per-line refH = half) and absorbs
            // small bbox drift across cycles without splitting.
            val orientMatch = boxes[boxIdx].orientation == orient
            val growthDirection = orientMatch && ocrLineCount > boxLineCount
            val aLn = if (growthDirection) boxLineCount else 1
            val bLn = if (growthDirection) ocrLineCount else 1
            val wholeBoxMatched = LayoutAnalyzer.wouldGroup(
                boxRect, ocrFullRect, orient,
                mode = LayoutAnalyzer.GroupingMode.CROSS_FRAME_SAME_REGION,
                aLineCount = aLn,
                bLineCount = bLn,
                rtl = rtl,
            )
            // Shrink-direction rescues: the de-normalized raw-height path
            // above can never accept a single-line continuation of a
            // multi-line box (the fragment is compared against the whole
            // box's stacked height). Two directional probes re-test the
            // per-line geometry for the two continuation shapes typewriter
            // reveals actually produce — same-row tail (inline, forward
            // reading side only) and next row directly below (block,
            // downward only). Horizontal only; the vertical twins are
            // unobserved (deferred).
            val lastLineMatched = !wholeBoxMatched && orientMatch &&
                orient == TextOrientation.HORIZONTAL &&
                boxLineCount > 1 && ocrLineCount == 1 &&
                LayoutAnalyzer.inlineContinuesLastLine(boxRect, boxLineCount, ocrFullRect, rtl)
            val belowLineMatched = !wholeBoxMatched && !lastLineMatched && orientMatch &&
                orient == TextOrientation.HORIZONTAL &&
                boxLineCount > 1 && ocrLineCount == 1 &&
                LayoutAnalyzer.blockContinuesBelow(boxRect, boxLineCount, ocrFullRect, rtl)
            val matched = wholeBoxMatched || lastLineMatched || belowLineMatched
            if (OcrManager.instance.debugLogGroupingEnabled) {
                val decision = LayoutAnalyzer.groupDecision(
                    boxRect, ocrFullRect, orient,
                    mode = LayoutAnalyzer.GroupingMode.CROSS_FRAME_SAME_REGION,
                    aLineCount = aLn,
                    bLineCount = bLn,
                    rtl = rtl,
                )
                val verdict = when {
                    lastLineMatched -> "MATCH-lastline"
                    belowLineMatched -> "MATCH-belowline"
                    matched -> "MATCH"
                    else -> "MISS"
                }
                val boxSnippet = boxes[boxIdx].sourceText.take(24).replace('\n', ' ')
                val ocrSnippet = ocrText.take(24).replace('\n', ' ')
                android.util.Log.d(
                    "DetectionLog",
                    "[xf:${orient.name[0]}] $verdict box[$boxIdx]=${LayoutAnalyzer.rectStr(boxRect)} " +
                        "\"$boxSnippet\" ocr[$ocrIdx]=${LayoutAnalyzer.rectStr(ocrFullRect)} " +
                        "\"$ocrSnippet\" :: ${decision.reason}"
                )
            }
            if (matched) {
                nearExisting = true
                staleOverlayIndices.add(boxIdx)
            }
        }

        // 3. Far: brand-new text with no nearby existing overlay.
        //    Proximity-matched groups are intentionally NOT queued here
        //    — in pinhole mode the cached region is bg-filled before
        //    OCR, so a fresh OCR rect represents only the new content
        //    visible this cycle, not the full paragraph. Queuing that
        //    partial as a replacement would cache an incomplete
        //    placeholder; the next cycle would then bg-fill the partial
        //    region too, so OCR could never see the full content
        //    afterwards. Suppressing the enqueue (with the cached box
        //    staled) leaves the region unblocked next cycle, where OCR
        //    sees the full paragraph and within-frame grouping merges
        //    it into one placeholder. One blank-flicker cycle is the
        //    cost of converging to the correct merged translation.
        //
        //    But: if this group would naturally OCR-group with an
        //    already-queued far entry, coalesce into it instead of
        //    queueing a separate placeholder. This handles typewriter-
        //    style updates where OCR splits "Begin typewriter text end
        //    typewriter text" into two groups: the first content-matches
        //    the cached box and queues a same-position far placeholder;
        //    the second has no near-existing neighbor (the cached box
        //    was just removed via contentMatchRemovals and is skipped
        //    above), so it'd otherwise render as a separate fragment.
        //    Coalescing yields one merged placeholder spanning both,
        //    matching what a single un-split OCR group would have
        //    produced.
        //
        //    The proximity test reuses the same wouldGroup heuristic OCR
        //    uses for its own intra-frame grouping, applied to bitmap-
        //    space rects. Distant text (separate paragraphs / unrelated
        //    regions) fails wouldGroup and stays as separate far entries.
        if (!nearExisting) {
            val lc = ocrLineCount
            val align = group.alignment
            // Gate: only coalesce INTO a paired FAR (an entry queued by
            // content-match earlier in this loop). Fresh-FARs added by
            // an earlier Far-branch iteration are NOT eligible — they
            // represent OCR groups OCR's intra-pass already decided to
            // SPLIT, and re-running wouldGroup on whole-group rects +
            // per-line refH normalization would override that decision
            // (the epilepsy-warning bug). A cycle-global gate ("did
            // ANY content-match fire") wouldn't be enough: a totally
            // unrelated content-match elsewhere on screen would still
            // open the door for two genuinely-separate fresh paragraphs
            // to be merged via a fresh-FAR-to-fresh-FAR match. Iterating
            // pairedFarIndices directly (a LinkedHashSet, so insertion-
            // order = OCR pass order, matching the original
            // indexOfFirst-over-farOcrGroups iteration shape) makes the
            // eligibility per-candidate.
            //
            // After a merge, the FarGroup at coalesceIdx is replaced
            // by the merged result; its index stays in pairedFarIndices
            // because the merged group's identity is still "paired" —
            // a later fragment can stitch onto the expanded paired FAR.
            val coalesceIdx = pairedFarIndices.firstOrNull { idx ->
                val existing = farOcrGroups[idx]
                // Hard-skip cross-orientation candidates. Unlike the
                // proximity path (which falls back to raw heights on
                // orientation mismatch and merely risks a recoverable
                // stale-mark), a successful coalesce permanently merges
                // two rects into a single FarGroup with one orientation
                // field — half the merged content would then render
                // along the wrong axis. Coalesce is the call site where
                // the orientation choice has rendering side-effects, so
                // the more conservative gate is appropriate here.
                if (existing.orientation != orient) return@firstOrNull false
                // Hard-skip when either side is SLANTED: the merged FarGroup
                // carries one angle field and an AABB union of two rects is
                // not a rotated rect, so a coalesce would erase the slant and
                // render an upright chip over slanted source (Codex
                // adversarial finding — the last angle-blind seam once the
                // threshold drop made light slants common). The fragment
                // stays a standalone fresh FAR at its true angle instead; the
                // cost is the same one-cycle convergence the non-coalesce
                // path above already accepts.
                if (existing.angleDeg != 0f || group.angleDeg != 0f) return@firstOrNull false
                val existingBitmapRect = coords.ocrToBitmap(existing.bounds)
                LayoutAnalyzer.wouldGroup(
                    existingBitmapRect, ocrFullRect, existing.orientation,
                    aLineCount = existing.lineCount,
                    bLineCount = lc,
                    rtl = rtl,
                )
            } ?: -1
            if (coalesceIdx >= 0) {
                val existing = farOcrGroups[coalesceIdx]
                val separator = if (existing.orientation == TextOrientation.VERTICAL) "\n" else " "
                // Two distinct OCR groups that classification stitched together
                // are not the same paragraph — drop to LEFT unless both already
                // agreed on CENTER, so we never falsely center a mixed merge.
                val mergedAlign = if (existing.alignment == align) align else TextAlignment.LEFT
                // Inline merges (same row for horizontal, same column for
                // vertical) don't add a new line — they widen an existing
                // one. Mirror wouldGroup's sameLine/sameColumn check via
                // center-axis overlap. Summing unconditionally would
                // over-state lineCount, and downstream code (proximity
                // wouldGroup's per-line normalization, placeholder rendering)
                // would then under-scale per-line dimension and reject
                // legitimate continuations.
                val isInlineMerge = if (existing.orientation == TextOrientation.VERTICAL) {
                    val aCx = (existing.bounds.left + existing.bounds.right) / 2
                    val bCx = (ocrBound.left + ocrBound.right) / 2
                    bCx in existing.bounds.left..existing.bounds.right ||
                        aCx in ocrBound.left..ocrBound.right
                } else {
                    val aCy = (existing.bounds.top + existing.bounds.bottom) / 2
                    val bCy = (ocrBound.top + ocrBound.bottom) / 2
                    bCy in existing.bounds.top..existing.bounds.bottom ||
                        aCy in ocrBound.top..ocrBound.bottom
                }
                val mergedLineCount = if (isInlineMerge) {
                    maxOf(existing.lineCount, lc)
                } else {
                    existing.lineCount + lc
                }
                farOcrGroups[coalesceIdx] = FarGroup(
                    text = existing.text + separator + ocrText,
                    bounds = Rect(
                        minOf(existing.bounds.left, ocrBound.left),
                        minOf(existing.bounds.top, ocrBound.top),
                        maxOf(existing.bounds.right, ocrBound.right),
                        maxOf(existing.bounds.bottom, ocrBound.bottom),
                    ),
                    lineCount = mergedLineCount,
                    orientation = existing.orientation,
                    alignment = mergedAlign,
                    // The coalesce gate only admits paired targets, and the
                    // merged entry keeps the paired placement promise.
                    //
                    // KNOWN GAP (accepted 2026-07-10): the flag covers the
                    // whole merged rect, so a coalesced fresh component that
                    // is itself a partial read beside a pinhole-dying box
                    // would ride the exemption past step-9b deferral and
                    // could re-create placement churn. That needs a same-pass
                    // content-match AND this gate to accept the occluded
                    // fragment AND the occluder to die the same cycle — never
                    // observed (the taxi-prompt trace's gate rejected exactly
                    // this merge; typewriter merges are fully-visible reveals
                    // by construction). Do NOT fix by clearing the flag on
                    // merge: deferring a replacement whose source box was
                    // already removed re-creates the conversation-close
                    // uncovered-cycle bug for merged groups. If churn ever
                    // shows a coalesced-merge signature (far count drops via
                    // this branch in the same cycle as an abutting pinhole
                    // REMOVE), the fix is per-component rects tested
                    // individually in deferDyingBoxFragments.
                    paired = existing.paired,
                )
            } else {
                farOcrGroups.add(FarGroup(
                    ocrText, ocrBound, lc, orient, align,
                    angleDeg = group.angleDeg,
                    orientedWidth = group.orientedWidth,
                    orientedHeight = group.orientedHeight,
                ))
            }
        }
    }

    return ClassificationResult(
        contentMatchRemovals = contentMatchRemovals,
        staleOverlayIndices = staleOverlayIndices,
        farOcrGroups = farOcrGroups,
        vacated = vacated,
        tombstoneBlocks = tombstoneBlocks,
    )
}

/**
 * Expand a seed set of stale overlay indices to include any non-dirty
 * neighbors that [OcrManager.wouldGroup] with any already-stale box. Iterates
 * until no new neighbors are added.
 *
 * Two boxes are neighbors iff their bitmap-space rects would be grouped by
 * the same logic OCR uses to combine adjacent text into paragraphs (same-line
 * continuation, next line in block, etc.). [ocrBitmapRects] are the boxes'
 * OCR-derived (unpadded) bitmap rects — same coordinate space classification
 * uses for the proximity check, so cascade and stale agree about what
 * "neighbor" means and don't drift apart on rendering padding. `boxes` and
 * `ocrBitmapRects` must correspond index-for-index; indices past
 * `ocrBitmapRects.size` are skipped defensively.
 *
 * The returned set always contains every index in [initialStale].
 */
fun cascadeStaleRemovals(
    initialStale: Set<Int>,
    boxes: List<TextBox>,
    ocrBitmapRects: List<Rect>,
    rtl: Boolean = false,
): Set<Int> {
    val cascadedRemovals = initialStale.toMutableSet()
    if (cascadedRemovals.isEmpty()) return cascadedRemovals
    var expanded = true
    while (expanded) {
        expanded = false
        for (i in boxes.indices) {
            if (i in cascadedRemovals || boxes[i].dirty) continue
            if (i >= ocrBitmapRects.size) continue
            for (removeIdx in cascadedRemovals.toSet()) {
                if (removeIdx >= ocrBitmapRects.size) continue
                val orient = boxes[removeIdx].orientation
                // Deliberately *no* aLineCount / bLineCount here. Cascade
                // compares two cached-overlay rects that the within-frame
                // grouper already chose to keep separate. Per-line
                // normalization would make a stale multi-line box absorb
                // an unrelated single-line neighbor of the same font with
                // a small gap — a false positive with no replacement
                // evidence, since neither rect comes from fresh OCR.
                if (LayoutAnalyzer.wouldGroup(ocrBitmapRects[removeIdx], ocrBitmapRects[i], orient, rtl = rtl)) {
                    cascadedRemovals.add(i)
                    expanded = true
                    break
                }
            }
        }
    }
    return cascadedRemovals
}
