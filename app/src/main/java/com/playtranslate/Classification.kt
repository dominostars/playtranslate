package com.playtranslate

import android.graphics.Rect
import com.playtranslate.language.TextAlignment
import com.playtranslate.language.TextOrientation
import com.playtranslate.ui.TranslationOverlayView

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
)

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
 */
data class ClassificationResult(
    val contentMatchRemovals: Set<Int>,
    val staleOverlayIndices: Set<Int>,
    val farOcrGroups: List<FarGroup>,
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
 *      the OCR position.
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
    boxes: List<TranslationOverlayView.TextBox>,
    ocrBitmapRects: List<Rect>,
    coords: FrameCoordinates,
): ClassificationResult {
    val staleOverlayIndices = mutableSetOf<Int>()
    val contentMatchRemovals = mutableSetOf<Int>()
    val farOcrGroups = mutableListOf<FarGroup>()

    for (ocrIdx in ocrResult.groupTexts.indices) {
        if (ocrIdx >= ocrResult.groupBounds.size) continue
        val ocrText = ocrResult.groupTexts[ocrIdx]
        val ocrBound = ocrResult.groupBounds[ocrIdx]
        val ocrH = ocrBound.height()

        // 1. Content match: same source text + similar size → position update.
        var contentMatched = false
        for ((boxIdx, box) in boxes.withIndex()) {
            if (box.dirty) continue
            if (boxIdx in contentMatchRemovals) continue
            if (box.sourceText.isNotEmpty() &&
                !OverlayToolkit.isSignificantChange(ocrText, box.sourceText)) {
                val boxH = box.bounds.height()
                val maxH = maxOf(ocrH, boxH)
                if (maxH > 0 && kotlin.math.abs(ocrH - boxH) < maxH * 0.5) {
                    contentMatchRemovals.add(boxIdx)
                    val lc = ocrResult.groupLineCounts.getOrElse(ocrIdx) { 1 }
                    val orient = ocrResult.groupOrientations.getOrElse(ocrIdx) { TextOrientation.HORIZONTAL }
                    val align = ocrResult.groupAlignments.getOrElse(ocrIdx) { TextAlignment.LEFT }
                    farOcrGroups.add(FarGroup(ocrText, ocrBound, lc, orient, align))
                    contentMatched = true
                    break
                }
            }
        }
        if (contentMatched) continue

        // 2. Proximity check: near existing overlay → stale.
        val ocrFullRect = coords.ocrToBitmap(ocrBound)
        var nearExisting = false
        val orient = ocrResult.groupOrientations.getOrElse(ocrIdx) { TextOrientation.HORIZONTAL }
        val ocrLineCount = ocrResult.groupLineCounts.getOrElse(ocrIdx) { 1 }
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
            // placeholder via the far path).
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
            val matched = OcrManager.wouldGroup(
                boxRect, ocrFullRect, orient,
                mode = OcrManager.Companion.GroupingMode.CROSS_FRAME_SAME_REGION,
                aLineCount = aLn,
                bLineCount = bLn,
            )
            if (OcrManager.instance.debugLogGroupingEnabled) {
                val decision = OcrManager.groupDecision(
                    boxRect, ocrFullRect, orient,
                    mode = OcrManager.Companion.GroupingMode.CROSS_FRAME_SAME_REGION,
                    aLineCount = aLn,
                    bLineCount = bLn,
                )
                val verdict = if (matched) "MATCH" else "MISS"
                val boxSnippet = boxes[boxIdx].sourceText.take(24).replace('\n', ' ')
                val ocrSnippet = ocrText.take(24).replace('\n', ' ')
                android.util.Log.d(
                    "DetectionLog",
                    "[xf:${orient.name[0]}] $verdict box[$boxIdx]=${OcrManager.rectStr(boxRect)} " +
                        "\"$boxSnippet\" ocr[$ocrIdx]=${OcrManager.rectStr(ocrFullRect)} " +
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
            val align = ocrResult.groupAlignments.getOrElse(ocrIdx) { TextAlignment.LEFT }
            val coalesceIdx = farOcrGroups.indexOfFirst { existing ->
                // Hard-skip cross-orientation candidates. Unlike the
                // proximity path (which falls back to raw heights on
                // orientation mismatch and merely risks a recoverable
                // stale-mark), a successful coalesce permanently merges
                // two rects into a single FarGroup with one orientation
                // field — half the merged content would then render
                // along the wrong axis. Coalesce is the call site where
                // the orientation choice has rendering side-effects, so
                // the more conservative gate is appropriate here.
                if (existing.orientation != orient) return@indexOfFirst false
                val existingBitmapRect = coords.ocrToBitmap(existing.bounds)
                OcrManager.wouldGroup(
                    existingBitmapRect, ocrFullRect, existing.orientation,
                    aLineCount = existing.lineCount,
                    bLineCount = lc,
                )
            }
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
                )
            } else {
                farOcrGroups.add(FarGroup(ocrText, ocrBound, lc, orient, align))
            }
        }
    }

    return ClassificationResult(
        contentMatchRemovals = contentMatchRemovals,
        staleOverlayIndices = staleOverlayIndices,
        farOcrGroups = farOcrGroups,
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
    boxes: List<TranslationOverlayView.TextBox>,
    ocrBitmapRects: List<Rect>,
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
                if (OcrManager.wouldGroup(ocrBitmapRects[removeIdx], ocrBitmapRects[i], orient)) {
                    cascadedRemovals.add(i)
                    expanded = true
                    break
                }
            }
        }
    }
    return cascadedRemovals
}
