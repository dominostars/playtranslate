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
        for (boxIdx in boxes.indices) {
            if (boxIdx >= ocrBitmapRects.size) continue
            if (boxes[boxIdx].dirty) continue
            if (boxIdx in contentMatchRemovals) continue
            val orient = ocrResult.groupOrientations.getOrElse(ocrIdx) { TextOrientation.HORIZONTAL }
            if (OcrManager.wouldGroup(ocrBitmapRects[boxIdx], ocrFullRect, orient)) {
                nearExisting = true
                staleOverlayIndices.add(boxIdx)
            }
        }

        // 3. Far: brand-new text with no nearby existing overlay.
        //    But: if this group would naturally OCR-group with an
        //    already-queued far entry, coalesce into it instead of queueing
        //    a separate placeholder. This handles typewriter-style updates
        //    where OCR splits "Begin typewriter text end typewriter text"
        //    into two groups: the first content-matches the cached box and
        //    queues a same-position far placeholder; the second has no
        //    near-existing neighbor (the cached box was just removed via
        //    contentMatchRemovals and is skipped above), so it'd otherwise
        //    render as a separate fragment. Coalescing yields one merged
        //    placeholder spanning both, matching what a single un-split
        //    OCR group would have produced.
        //
        //    The proximity test reuses the same wouldGroup heuristic OCR
        //    uses for its own intra-frame grouping, applied to bitmap-space
        //    rects. Distant text (separate paragraphs / unrelated regions)
        //    fails wouldGroup and stays as separate far entries.
        if (!nearExisting) {
            val lc = ocrResult.groupLineCounts.getOrElse(ocrIdx) { 1 }
            val orient = ocrResult.groupOrientations.getOrElse(ocrIdx) { TextOrientation.HORIZONTAL }
            val align = ocrResult.groupAlignments.getOrElse(ocrIdx) { TextAlignment.LEFT }
            val coalesceIdx = farOcrGroups.indexOfFirst { existing ->
                val existingBitmapRect = coords.ocrToBitmap(existing.bounds)
                OcrManager.wouldGroup(existingBitmapRect, ocrFullRect, existing.orientation)
            }
            if (coalesceIdx >= 0) {
                val existing = farOcrGroups[coalesceIdx]
                val separator = if (existing.orientation == TextOrientation.VERTICAL) "\n" else " "
                // Two distinct OCR groups that classification stitched together
                // are not the same paragraph — drop to LEFT unless both already
                // agreed on CENTER, so we never falsely center a mixed merge.
                val mergedAlign = if (existing.alignment == align) align else TextAlignment.LEFT
                farOcrGroups[coalesceIdx] = FarGroup(
                    text = existing.text + separator + ocrText,
                    bounds = Rect(
                        minOf(existing.bounds.left, ocrBound.left),
                        minOf(existing.bounds.top, ocrBound.top),
                        maxOf(existing.bounds.right, ocrBound.right),
                        maxOf(existing.bounds.bottom, ocrBound.bottom),
                    ),
                    lineCount = existing.lineCount + lc,
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
