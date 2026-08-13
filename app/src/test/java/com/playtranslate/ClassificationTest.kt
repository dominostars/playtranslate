package com.playtranslate

import android.graphics.Rect
import com.playtranslate.language.TextOrientation
import com.playtranslate.ui.TextBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for the pure-function classification extracted from
 * [PinholeOverlayMode.runCycle].
 *
 * Tests cover:
 *  - [classifyOcrResults]: content match, proximity, far; dirty-box
 *    exclusion; first-match-wins; index guards for mid-cycle size drift.
 *  - Relocation tombstones: minting (distant vs in-place), duplicate
 *    blocking, mover pass-through, one-bounce oscillation convergence.
 *  - [cascadeStaleRemovals]: empty seed, isolated seed, adjacent chain,
 *    disconnected multi-seed, dirty skip, ocrBitmapRects overflow.
 *
 * Runs under Robolectric so [android.graphics.Rect] is available on the JVM.
 * All test rects use an identity [FrameCoordinates] with zero crop so
 * `coords.ocrToBitmap(r) == r` and proximity comparisons can use the same
 * coordinates as the ocrBitmapRects list.
 */
@RunWith(RobolectricTestRunner::class)
class ClassificationTest {

    // Identity scale, zero crop → ocrToBitmap is a no-op. Bitmap dims are
    // large enough that no test rect overflows them.
    private val identityCoords = FrameCoordinates(
        bitmapWidth = 10_000, bitmapHeight = 10_000,
        viewWidth = 10_000, viewHeight = 10_000,
        cropLeft = 0, cropTop = 0,
    )

    private fun box(
        bounds: Rect,
        sourceText: String = "",
        dirty: Boolean = false,
        lineCount: Int = 1,
        orientation: TextOrientation = TextOrientation.HORIZONTAL,
    ) = TextBox(
        translatedText = "",
        bounds = bounds,
        sourceText = sourceText,
        dirty = dirty,
        lineCount = lineCount,
        orientation = orientation,
    )

    private fun grp(
        text: String,
        bounds: Rect,
        lineCount: Int = 1,
        orientation: TextOrientation = TextOrientation.HORIZONTAL,
    ) = OcrManager.OcrGroup(
        text = text,
        bounds = bounds,
        orientation = orientation,
        lines = List(lineCount) { OcrManager.LineBox(text = text, bounds = bounds, groupIndex = 0) },
    )

    private fun ocrResult(
        vararg groups: Pair<String, Rect>,
        lineCounts: List<Int>? = null,
    ) = OcrManager.OcrResult(
        fullText = "",
        segments = emptyList(),
        groups = groups.mapIndexed { i, (text, bounds) -> grp(text, bounds, lineCounts?.getOrElse(i) { 1 } ?: 1) },
    )

    // ── classifyOcrResults: shape / empty-input cases ────────────────────

    @Test
    fun classify_emptyOcr_returnsEmptyResult() {
        val result = classifyOcrResults(
            ocrResult = ocrResult(),
            boxes = listOf(box(Rect(0, 0, 100, 100), sourceText = "abc")),
            ocrBitmapRects = listOf(Rect(0, 0, 100, 100)),
            coords = identityCoords,
        )
        assertTrue(result.contentMatchRemovals.isEmpty())
        assertTrue(result.staleOverlayIndices.isEmpty())
        assertTrue(result.farOcrGroups.isEmpty())
    }

    @Test
    fun classify_emptyBoxes_allOcrGroupsBecomeFar() {
        val result = classifyOcrResults(
            ocrResult = ocrResult(
                "hello" to Rect(0, 0, 100, 100),
                "world" to Rect(0, 200, 100, 300),
            ),
            boxes = emptyList(),
            ocrBitmapRects = emptyList(),
            coords = identityCoords,
        )
        assertTrue(result.contentMatchRemovals.isEmpty())
        assertTrue(result.staleOverlayIndices.isEmpty())
        assertEquals(2, result.farOcrGroups.size)
        assertEquals("hello", result.farOcrGroups[0].text)
        assertEquals("world", result.farOcrGroups[1].text)
    }

    // ── classifyOcrResults: content match ────────────────────────────────

    @Test
    fun classify_contentMatch_sameTextSameHeight_queuesReplacement() {
        val boxBounds = Rect(0, 0, 100, 100)
        val ocrBounds = Rect(500, 500, 600, 600)   // Elsewhere, height 100
        val result = classifyOcrResults(
            ocrResult = ocrResult("hello" to ocrBounds),
            boxes = listOf(box(boxBounds, sourceText = "hello")),
            ocrBitmapRects = listOf(boxBounds),
            coords = identityCoords,
        )
        assertEquals(setOf(0), result.contentMatchRemovals)
        assertTrue(result.staleOverlayIndices.isEmpty())
        assertEquals(1, result.farOcrGroups.size)
        assertEquals("hello", result.farOcrGroups[0].text)
        assertEquals(
            "content-match placeholder must use the OCR bounds, not the old box bounds",
            ocrBounds, result.farOcrGroups[0].bounds,
        )
    }

    @Test
    fun classify_contentMatch_heightDifferenceTooLarge_notMatched() {
        // box height 100, ocr height 300 → maxH 300, diff 200, 200 < 150 = false
        // → NOT a content match; falls through to proximity (overlap → stale).
        val boxBounds = Rect(0, 0, 100, 100)
        val ocrBounds = Rect(0, 0, 100, 300)
        val result = classifyOcrResults(
            ocrResult = ocrResult("hello" to ocrBounds),
            boxes = listOf(box(boxBounds, sourceText = "hello")),
            ocrBitmapRects = listOf(boxBounds),
            coords = identityCoords,
        )
        assertTrue(
            "must not content-match when heights differ > 50% of max",
            result.contentMatchRemovals.isEmpty(),
        )
        assertEquals(setOf(0), result.staleOverlayIndices)
    }

    @Test
    fun classify_contentMatch_significantlyDifferentText_notMatched() {
        val boxBounds = Rect(0, 0, 100, 100)
        val ocrBounds = Rect(0, 0, 100, 100)
        val result = classifyOcrResults(
            ocrResult = ocrResult("hello" to ocrBounds),
            boxes = listOf(box(boxBounds, sourceText = "totally different text")),
            ocrBitmapRects = listOf(boxBounds),
            coords = identityCoords,
        )
        assertTrue(result.contentMatchRemovals.isEmpty())
        // Box still overlaps the OCR group, so proximity stales it.
        assertEquals(setOf(0), result.staleOverlayIndices)
    }

    @Test
    fun classify_contentMatch_skipsDirtyBoxes() {
        val boxBounds = Rect(0, 0, 100, 100)
        val ocrBounds = Rect(5_000, 5_000, 5_100, 5_100)  // Far from any box
        val result = classifyOcrResults(
            ocrResult = ocrResult("hello" to ocrBounds),
            boxes = listOf(box(boxBounds, sourceText = "hello", dirty = true)),
            ocrBitmapRects = listOf(boxBounds),
            coords = identityCoords,
        )
        assertTrue(
            "dirty box must be excluded from content match",
            result.contentMatchRemovals.isEmpty(),
        )
        assertTrue(
            "dirty box must be excluded from proximity",
            result.staleOverlayIndices.isEmpty(),
        )
        // Falls through to far.
        assertEquals(1, result.farOcrGroups.size)
    }

    @Test
    fun classify_contentMatch_skipsBoxWithEmptySourceText() {
        // Boxes with empty sourceText (e.g. pure-translation placeholders)
        // must not be content-matched even if the OCR text is empty-like.
        val boxBounds = Rect(5_000, 5_000, 5_100, 5_100)
        val ocrBounds = Rect(0, 0, 100, 100)
        val result = classifyOcrResults(
            ocrResult = ocrResult("hello" to ocrBounds),
            boxes = listOf(box(boxBounds, sourceText = "")),
            ocrBitmapRects = listOf(boxBounds),
            coords = identityCoords,
        )
        assertTrue(result.contentMatchRemovals.isEmpty())
    }

    @Test
    fun classify_contentMatch_firstEligibleBoxWins() {
        // Two candidate boxes with the same sourceText — only the first
        // (lower index) should be matched.
        val boxBounds0 = Rect(0, 0, 100, 100)
        val boxBounds1 = Rect(200, 0, 300, 100)
        val ocrBounds = Rect(5_000, 5_000, 5_100, 5_100)  // Far from both
        val result = classifyOcrResults(
            ocrResult = ocrResult("hello" to ocrBounds),
            boxes = listOf(
                box(boxBounds0, sourceText = "hello"),
                box(boxBounds1, sourceText = "hello"),
            ),
            ocrBitmapRects = listOf(boxBounds0, boxBounds1),
            coords = identityCoords,
        )
        assertEquals(setOf(0), result.contentMatchRemovals)
        assertTrue(
            "second candidate must not be marked stale",
            result.staleOverlayIndices.isEmpty(),
        )
    }

    @Test
    fun classify_contentMatch_alreadyMatchedBoxSkippedForLaterGroups() {
        // One box, two OCR groups both named "hello". First group consumes
        // the box; second has no target and becomes far.
        val boxBounds = Rect(0, 0, 100, 100)
        val ocr0 = Rect(5_000, 5_000, 5_100, 5_100)
        val ocr1 = Rect(6_000, 6_000, 6_100, 6_100)
        val result = classifyOcrResults(
            ocrResult = ocrResult("hello" to ocr0, "hello" to ocr1),
            boxes = listOf(box(boxBounds, sourceText = "hello")),
            ocrBitmapRects = listOf(boxBounds),
            coords = identityCoords,
        )
        assertEquals(setOf(0), result.contentMatchRemovals)
        assertEquals(2, result.farOcrGroups.size)
        assertEquals(ocr0, result.farOcrGroups[0].bounds)
        assertEquals(ocr1, result.farOcrGroups[1].bounds)
    }

    // ── classifyOcrResults: relocation tombstones ────────────────────────

    @Test
    fun classify_distantContentMatch_mintsTombstoneAtVacatedBounds() {
        val boxBounds = Rect(0, 0, 100, 100)
        val ocrBounds = Rect(500, 500, 600, 600)
        val result = classifyOcrResults(
            ocrResult = ocrResult("hello" to ocrBounds),
            boxes = listOf(box(boxBounds, sourceText = "hello")),
            ocrBitmapRects = listOf(boxBounds),
            coords = identityCoords,
        )
        assertEquals(setOf(0), result.contentMatchRemovals)
        assertEquals(listOf(Tombstone("hello", boxBounds)), result.vacated)
        assertEquals(0, result.tombstoneBlocks)
    }

    @Test
    fun classify_samePositionContentMatch_doesNotMintTombstone() {
        // In-place update (bounds within re-read jitter of the box): the
        // content match fires but no tombstone is minted — nothing was
        // vacated. Otherwise every stable in-place re-match would seed
        // vacancy memory for a region that is still occupied.
        val boxBounds = Rect(0, 0, 100, 100)
        val ocrBounds = Rect(3, 2, 103, 102)   // 3px jitter, same region
        val result = classifyOcrResults(
            ocrResult = ocrResult("hello" to ocrBounds),
            boxes = listOf(box(boxBounds, sourceText = "hello")),
            ocrBitmapRects = listOf(boxBounds),
            coords = identityCoords,
        )
        assertEquals(setOf(0), result.contentMatchRemovals)
        assertTrue(result.vacated.isEmpty())
    }

    @Test
    fun classify_tombstone_blocksContentMatchAtVacatedRect_groupGoesFar() {
        // The duplicate-text oscillation, cycle 2: box relocated A→B last
        // look, the duplicate at A reads again (within jitter). The
        // tombstone bars the steal-back; the group spawns its own far box.
        val vacatedA = Rect(0, 0, 100, 100)
        val boxAtB = Rect(500, 500, 600, 600)
        val reReadAtA = Rect(4, 3, 103, 101)   // A, jittered a few px
        val result = classifyOcrResults(
            ocrResult = ocrResult("hello" to reReadAtA),
            boxes = listOf(box(boxAtB, sourceText = "hello")),
            ocrBitmapRects = listOf(boxAtB),
            coords = identityCoords,
            tombstones = listOf(Tombstone("hello", vacatedA)),
        )
        assertTrue(
            "tombstoned group must not steal the relocated box back",
            result.contentMatchRemovals.isEmpty(),
        )
        assertEquals(1, result.tombstoneBlocks)
        assertEquals(1, result.farOcrGroups.size)
        assertEquals(reReadAtA, result.farOcrGroups[0].bounds)
        assertFalse(
            "spawned duplicate is fresh text, not a paired replacement",
            result.farOcrGroups[0].paired,
        )
    }

    @Test
    fun classify_tombstone_groupAtNewPosition_stillRelocates() {
        // A genuine mover: tombstone at A, but the next read is at C — a
        // marquee step well past the match slop. Relocation must keep
        // working (the accumulation bug content match exists to prevent).
        val vacatedA = Rect(0, 0, 100, 100)
        val boxAtB = Rect(500, 500, 600, 600)
        val readAtC = Rect(0, 40, 100, 140)    // 40px on from A
        val result = classifyOcrResults(
            ocrResult = ocrResult("hello" to readAtC),
            boxes = listOf(box(boxAtB, sourceText = "hello")),
            ocrBitmapRects = listOf(boxAtB),
            coords = identityCoords,
            tombstones = listOf(Tombstone("hello", vacatedA)),
        )
        assertEquals(setOf(0), result.contentMatchRemovals)
        assertEquals(0, result.tombstoneBlocks)
    }

    @Test
    fun classify_tombstone_differentTextAtVacatedRect_doesNotBlock() {
        // The vacated rect now shows unrelated text of the same size —
        // that text's own content match elsewhere must not be barred.
        val vacatedA = Rect(0, 0, 100, 100)
        val boxAtB = Rect(500, 500, 600, 600)
        val reReadAtA = Rect(0, 0, 100, 100)
        val result = classifyOcrResults(
            ocrResult = ocrResult("goodbye" to reReadAtA),
            boxes = listOf(box(boxAtB, sourceText = "goodbye")),
            ocrBitmapRects = listOf(boxAtB),
            coords = identityCoords,
            tombstones = listOf(Tombstone("hello", vacatedA)),
        )
        assertEquals(setOf(0), result.contentMatchRemovals)
        assertEquals(0, result.tombstoneBlocks)
    }

    @Test
    fun classify_tombstone_oscillationConvergesInOneBounce() {
        // End-to-end loop closure: look 1 relocates A→B and mints the
        // tombstone; look 2 feeds it back and the re-read at A spawns
        // instead of stealing — both duplicates end up covered.
        val duplicateA = Rect(0, 0, 100, 100)
        val duplicateB = Rect(500, 500, 600, 600)

        // Look 1: box over A; B's copy becomes readable (A blacked out).
        val look1 = classifyOcrResults(
            ocrResult = ocrResult("hello" to duplicateB),
            boxes = listOf(box(duplicateA, sourceText = "hello")),
            ocrBitmapRects = listOf(duplicateA),
            coords = identityCoords,
        )
        assertEquals(setOf(0), look1.contentMatchRemovals)
        assertEquals(1, look1.vacated.size)

        // Look 2: box now over B (blacked out); A's copy reads again.
        val look2 = classifyOcrResults(
            ocrResult = ocrResult("hello" to duplicateA),
            boxes = listOf(box(duplicateB, sourceText = "hello")),
            ocrBitmapRects = listOf(duplicateB),
            coords = identityCoords,
            tombstones = look1.vacated,
        )
        assertTrue(look2.contentMatchRemovals.isEmpty())
        assertEquals(1, look2.farOcrGroups.size)
        assertEquals(duplicateA, look2.farOcrGroups[0].bounds)
        assertTrue(
            "the spawn is not a relocation — it must not mint a tombstone",
            look2.vacated.isEmpty(),
        )
    }

    // ── classifyOcrResults: proximity ────────────────────────────────────

    @Test
    fun classify_proximity_oneGroupStalesMultipleOverlappingBoxes() {
        // OCR rect spans two adjacent boxes — both go stale in one pass.
        val boxBounds0 = Rect(0, 0, 100, 100)
        val boxBounds1 = Rect(100, 0, 200, 100)
        val ocrBounds = Rect(50, 0, 150, 100)  // Overlaps both
        val result = classifyOcrResults(
            ocrResult = ocrResult("xxxxx" to ocrBounds),
            boxes = listOf(
                box(boxBounds0, sourceText = "old1"),
                box(boxBounds1, sourceText = "old2"),
            ),
            ocrBitmapRects = listOf(boxBounds0, boxBounds1),
            coords = identityCoords,
        )
        assertEquals(setOf(0, 1), result.staleOverlayIndices)
    }

    @Test
    fun classify_proximity_farAwayGroupBecomesFar() {
        val boxBounds = Rect(0, 0, 100, 100)
        val ocrBounds = Rect(5_000, 5_000, 5_100, 5_100)
        val result = classifyOcrResults(
            ocrResult = ocrResult("brand new" to ocrBounds),
            boxes = listOf(box(boxBounds, sourceText = "something else")),
            ocrBitmapRects = listOf(boxBounds),
            coords = identityCoords,
        )
        assertTrue(result.contentMatchRemovals.isEmpty())
        assertTrue(result.staleOverlayIndices.isEmpty())
        assertEquals(1, result.farOcrGroups.size)
        assertEquals("brand new", result.farOcrGroups[0].text)
    }

    @Test
    fun classify_proximity_skipsDirtyBoxes() {
        val boxBounds = Rect(0, 0, 100, 100)
        val ocrBounds = Rect(0, 0, 100, 100)  // Exact overlap
        val result = classifyOcrResults(
            ocrResult = ocrResult("xxxxx" to ocrBounds),
            boxes = listOf(box(boxBounds, sourceText = "old", dirty = true)),
            ocrBitmapRects = listOf(boxBounds),
            coords = identityCoords,
        )
        assertTrue(result.staleOverlayIndices.isEmpty())
        assertEquals(1, result.farOcrGroups.size)
    }

    @Test
    fun classify_proximity_skipsAlreadyContentMatchedBox() {
        // Box 0 is content-matched by OCR group 0. OCR group 1 overlaps
        // box 0 but must NOT mark it stale — we've already decided box 0
        // will be replaced at a new position.
        val boxBounds = Rect(0, 0, 100, 100)
        val result = classifyOcrResults(
            ocrResult = ocrResult(
                "hello" to Rect(5_000, 5_000, 5_100, 5_100),  // Content-match box 0
                "other" to Rect(0, 0, 100, 100),                // Overlaps box 0
            ),
            boxes = listOf(box(boxBounds, sourceText = "hello")),
            ocrBitmapRects = listOf(boxBounds),
            coords = identityCoords,
        )
        assertEquals(setOf(0), result.contentMatchRemovals)
        assertTrue(
            "content-matched box must be excluded from proximity check",
            result.staleOverlayIndices.isEmpty(),
        )
        assertEquals(2, result.farOcrGroups.size)
    }

    @Test
    fun classify_proximity_growthDirection_singleCachedMultiLineFresh_matchesAndSuppresses() {
        // Cached single-line "「あらすじ" then fresh 2-line continuation
        // revealed below. Per-line normalization applies (bLineCount >
        // aLineCount), the size-ratio cap that would otherwise reject
        // the pairing on stacked-line height falls away, and the cached
        // box is stale-marked. The fresh group is intentionally NOT
        // queued — in pinhole mode the cached region is bg-filled
        // before OCR, so the fresh rect is only the new lines visible
        // this cycle, not the full paragraph. Caching that partial as
        // a replacement would then bg-fill it next cycle and prevent
        // OCR from ever seeing the full paragraph. Suppression leaves
        // the region unblocked next cycle so within-frame grouping can
        // produce a single merged placeholder. See the "near existing
        // overlay" rationale in classifyOcrResults.
        val cachedBounds = Rect(0, 0, 200, 50)            // 1 line, h=50
        val freshBounds = Rect(0, 60, 200, 185)           // 2 lines, h=125
        val ocr = OcrManager.OcrResult(
            fullText = "",
            segments = emptyList(),
            groups = listOf(grp("continuation", freshBounds, lineCount = 2)),
        )
        val result = classifyOcrResults(
            ocrResult = ocr,
            boxes = listOf(box(cachedBounds, sourceText = "「あらすじ", lineCount = 1)),
            ocrBitmapRects = listOf(cachedBounds),
            coords = identityCoords,
        )
        assertEquals(
            "growth-direction paragraph reveal must stale the cached box",
            setOf(0), result.staleOverlayIndices,
        )
        assertTrue(
            "fresh group must be suppressed from far so next cycle can re-OCR the full paragraph",
            result.farOcrGroups.isEmpty(),
        )
    }

    @Test
    fun classify_belowProbe_scaleMismatchedLabelBelow_staysFar() {
        // The shrink direction's protected case, re-pinned post-2026-07-16:
        // a fresh single line below a cached multi-line box stales it ONLY
        // when the per-line geometry reads as the paragraph's next row
        // (blockContinuesBelow). A label in a clearly different type size
        // fails the per-line cap ((50-30)/30 = 0.67 > 0.50) and must stay
        // far, leaving the cached translation visible. (The pre-fix pin on
        // this fixture asserted that an EQUAL-height aligned line below
        // stays far too; the グラウス trace 2026-07-12 falsified that prior
        // for line-by-line typewriter dialogue, so that shape now
        // deliberately stales — see the belowLineContinuation tests.)
        val cachedBounds = Rect(0, 0, 200, 150)           // 3 lines, per-line h=50
        val freshBounds = Rect(0, 160, 200, 190)          // 1 line, h=30
        val ocr = OcrManager.OcrResult(
            fullText = "",
            segments = emptyList(),
            groups = listOf(grp("unrelated label", freshBounds, lineCount = 1)),
        )
        val result = classifyOcrResults(
            ocrResult = ocr,
            boxes = listOf(box(cachedBounds, sourceText = "three line dialogue", lineCount = 3)),
            ocrBitmapRects = listOf(cachedBounds),
            coords = identityCoords,
        )
        assertTrue(
            "scale-mismatched below-line must NOT stale the cached box",
            result.staleOverlayIndices.isEmpty(),
        )
        assertEquals(
            "fresh single-line must be queued as its own far placeholder",
            1, result.farOcrGroups.size,
        )
        assertEquals("unrelated label", result.farOcrGroups[0].text)
    }

    @Test
    fun classify_proximity_orientationMismatch_fallsBackToRawHeights() {
        // A cached vertical 1-column box (think single tall glyph)
        // paired with a fresh horizontal 3-row group whose per-row
        // height coincidentally matches the cached glyph height would
        // falsely match under per-line normalization — aH=50, bH=50,
        // ratio 0 — because TextBox.lineCount is in the wrap-axis
        // sense (columns for vertical) and would be interpreted as
        // rows by wouldGroup's horizontal path. The orientation-match
        // guard forces this case to raw heights (50 vs 150, ratio 2.0)
        // so the size-ratio cap correctly rejects.
        val cachedBounds = Rect(0, 0, 50, 50)              // Vertical 1-column, h=50
        val freshBounds = Rect(0, 60, 100, 210)            // Horizontal 3-row, h=150
        val ocr = OcrManager.OcrResult(
            fullText = "",
            segments = emptyList(),
            groups = listOf(grp("unrelated horiz text", freshBounds, lineCount = 3)),
        )
        val result = classifyOcrResults(
            ocrResult = ocr,
            boxes = listOf(box(
                cachedBounds, sourceText = "縦", lineCount = 1,
                orientation = TextOrientation.VERTICAL,
            )),
            ocrBitmapRects = listOf(cachedBounds),
            coords = identityCoords,
        )
        assertTrue(
            "cross-orientation proximity must not match via per-line normalization",
            result.staleOverlayIndices.isEmpty(),
        )
        assertEquals(
            "fresh group falls through to far placeholder",
            1, result.farOcrGroups.size,
        )
    }

    // ── classifyOcrResults: defensive index guards ───────────────────────

    // (Removed classify_groupBoundsShorterThanGroupTexts_skipsOverflow: the
    //  nested OcrGroup model makes a groupTexts/groupBounds length mismatch
    //  impossible to construct — the desync class that guard protected against
    //  no longer exists.)

    @Test
    fun classify_lineCountFlowsFromGroupLines() {
        val ocr = OcrManager.OcrResult(
            fullText = "",
            segments = emptyList(),
            groups = listOf(
                grp("a", Rect(0, 0, 100, 100), lineCount = 3),
                grp("b", Rect(0, 200, 100, 300), lineCount = 1),
            ),
        )
        val result = classifyOcrResults(
            ocrResult = ocr,
            boxes = emptyList(),
            ocrBitmapRects = emptyList(),
            coords = identityCoords,
        )
        assertEquals(2, result.farOcrGroups.size)
        assertEquals(3, result.farOcrGroups[0].lineCount)
        assertEquals(1, result.farOcrGroups[1].lineCount)
    }

    @Test
    fun classify_ocrBitmapRectsShorterThanBoxes_skipsOverflowInProximity() {
        // Defensive: if mid-cycle getChildScreenRects returns fewer entries
        // than cachedBoxes, the overflow indices must be silently skipped
        // for the proximity phase instead of throwing IndexOutOfBounds.
        val boxBounds0 = Rect(0, 0, 100, 100)
        val boxBounds1 = Rect(0, 200, 100, 300)
        val ocrBounds = Rect(0, 200, 100, 300)  // Would overlap box 1
        val result = classifyOcrResults(
            ocrResult = ocrResult("xxxxx" to ocrBounds),
            boxes = listOf(
                box(boxBounds0, sourceText = "a"),
                box(boxBounds1, sourceText = "b"),
            ),
            ocrBitmapRects = listOf(boxBounds0),  // Only one entry
            coords = identityCoords,
        )
        assertTrue(
            "box 1 has no bitmapRect, so proximity must silently skip it",
            result.staleOverlayIndices.isEmpty(),
        )
        assertEquals(1, result.farOcrGroups.size)
    }

    @Test
    fun classify_farCoalesce_orientationMismatch_doesNotMerge() {
        // Two OCR groups in one pass: a vertical 1-column group and an
        // adjacent horizontal 3-row group whose per-row width matches
        // the vertical column width. Per-line normalization without an
        // orientation guard would interpret the horizontal row count
        // as a vertical column count and falsely coalesce them,
        // producing a single FarGroup with VERTICAL orientation that
        // would render the horizontal content along the wrong axis.
        // The hard-skip in the coalesce predicate prevents this even
        // before wouldGroup runs.
        val verticalBounds = Rect(0, 0, 50, 200)        // 1-column, w=50
        val horizontalBounds = Rect(60, 0, 210, 150)    // 3-row, w=150
        val ocr = OcrManager.OcrResult(
            fullText = "",
            segments = emptyList(),
            groups = listOf(
                grp("縦", verticalBounds, lineCount = 1, orientation = TextOrientation.VERTICAL),
                grp("horiz lines", horizontalBounds, lineCount = 3, orientation = TextOrientation.HORIZONTAL),
            ),
        )
        val result = classifyOcrResults(
            ocrResult = ocr,
            boxes = emptyList(),
            ocrBitmapRects = emptyList(),
            coords = identityCoords,
        )
        assertEquals(
            "cross-orientation groups must not coalesce",
            2, result.farOcrGroups.size,
        )
        assertEquals(TextOrientation.VERTICAL, result.farOcrGroups[0].orientation)
        assertEquals(TextOrientation.HORIZONTAL, result.farOcrGroups[1].orientation)
    }

    // ── classifyOcrResults: far-group coalesce ───────────────────────────

    @Test
    fun classify_farCoalesce_inlineMergeThenBlock_lineCountTracksRowsNotMerges() {
        // Regression: an inline-coalesced FarGroup must not inflate
        // lineCount, otherwise the next block-coalesce attempt fails the
        // per-line size-ratio gate in wouldGroup. Reproduces typewriter-
        // style fragment splitting on one row followed by a real next-row
        // continuation in the same OCR pass.
        //
        // The cached box matching OCR group "A" is what opens the coalesce
        // gate (content-match queues a paired FAR); without a content-match
        // this cycle, the Far branch trusts OCR's grouping verbatim and
        // never attempts a re-merge. The lineCount-tracking invariant under
        // test only fires once that gate is open.
        //
        // Row 1 left half:  (0, 0, 100, 50)   — content-matches cached, queues paired FAR
        // Row 1 right half: (110, 0, 200, 50) — inline-coalesces with paired FAR
        // Row 2:            (0, 60, 200, 110) — block-coalesces onto row 1
        val cachedBox = box(Rect(0, 0, 100, 50), sourceText = "A")
        val result = classifyOcrResults(
            ocrResult = ocrResult(
                "A" to Rect(0, 0, 100, 50),
                "B" to Rect(110, 0, 200, 50),
                "C" to Rect(0, 60, 200, 110),
            ),
            boxes = listOf(cachedBox),
            ocrBitmapRects = listOf(cachedBox.bounds),
            coords = identityCoords,
        )
        assertEquals(setOf(0), result.contentMatchRemovals)
        assertEquals(1, result.farOcrGroups.size)
        assertEquals("A B C", result.farOcrGroups[0].text)
        assertEquals(2, result.farOcrGroups[0].lineCount)
        assertTrue(
            "a coalesced merge keeps the paired placement promise",
            result.farOcrGroups[0].paired,
        )
    }

    @Test
    fun classify_slantedPairedFar_refusesCoalesce_fragmentStandsAlone() {
        // The angle gate on the coalesce predicate: a merged FarGroup carries
        // one angle field and an AABB union is not a rotated rect, so a
        // slanted paired FAR must never absorb a fragment — the merge would
        // erase the slant and render an upright chip over slanted source.
        // The fragment stays a standalone fresh FAR (one-cycle convergence,
        // same cost the non-coalesce path documents).
        val cachedBox = box(Rect(0, 0, 100, 50), sourceText = "A")
        val slanted = OcrManager.OcrGroup(
            text = "A",
            bounds = Rect(0, 0, 100, 50),
            orientation = TextOrientation.HORIZONTAL,
            lines = listOf(OcrManager.LineBox(text = "A", bounds = Rect(0, 0, 100, 50), groupIndex = 0)),
            angleDeg = -12f,
            orientedWidth = 90f,
            orientedHeight = 30f,
        )
        val fragment = grp("B", Rect(110, 0, 200, 50))
        val result = classifyOcrResults(
            ocrResult = OcrManager.OcrResult(
                fullText = "", segments = emptyList(), groups = listOf(slanted, fragment),
            ),
            boxes = listOf(cachedBox),
            ocrBitmapRects = listOf(cachedBox.bounds),
            coords = identityCoords,
        )
        assertEquals(setOf(0), result.contentMatchRemovals)
        assertEquals("no coalesce across the angle gate", 2, result.farOcrGroups.size)
        assertEquals("the paired FAR keeps its slant", -12f, result.farOcrGroups[0].angleDeg, 0f)
        assertEquals("A", result.farOcrGroups[0].text)
        assertEquals("the fragment stands alone", "B", result.farOcrGroups[1].text)
        assertEquals(0f, result.farOcrGroups[1].angleDeg, 0f)
    }

    @Test
    fun classify_slantedFragment_refusesCoalesceOntoUprightPairedFar() {
        // The mirror direction: an upright paired FAR must not absorb a
        // SLANTED fragment either — the merge would flatten the fragment's
        // angle the same way.
        val cachedBox = box(Rect(0, 0, 100, 50), sourceText = "A")
        val slantedFragment = OcrManager.OcrGroup(
            text = "B",
            bounds = Rect(110, 0, 210, 60),
            orientation = TextOrientation.HORIZONTAL,
            lines = listOf(OcrManager.LineBox(text = "B", bounds = Rect(110, 0, 210, 60), groupIndex = 0)),
            angleDeg = -12f,
            orientedWidth = 95f,
            orientedHeight = 30f,
        )
        val result = classifyOcrResults(
            ocrResult = OcrManager.OcrResult(
                fullText = "", segments = emptyList(),
                groups = listOf(grp("A", Rect(0, 0, 100, 50)), slantedFragment),
            ),
            boxes = listOf(cachedBox),
            ocrBitmapRects = listOf(cachedBox.bounds),
            coords = identityCoords,
        )
        assertEquals("no coalesce across the angle gate", 2, result.farOcrGroups.size)
        assertEquals("the slanted fragment keeps its angle", -12f, result.farOcrGroups[1].angleDeg, 0f)
    }

    @Test
    fun classify_pairedFlag_setOnContentMatchReplacement_notOnFreshFar() {
        // The paired flag is what exempts a content-match replacement from
        // dying-box fragment deferral (see deferDyingBoxFragments): the
        // replacement must carry it, and brand-new text must not.
        val boxBounds = Rect(0, 0, 100, 100)
        val result = classifyOcrResults(
            ocrResult = ocrResult(
                "hello" to Rect(500, 500, 600, 600),           // content match, elsewhere
                "brand new" to Rect(3000, 3000, 3200, 3040),   // unrelated fresh text
            ),
            boxes = listOf(box(boxBounds, sourceText = "hello")),
            ocrBitmapRects = listOf(boxBounds),
            coords = identityCoords,
        )
        assertEquals(setOf(0), result.contentMatchRemovals)
        assertEquals(2, result.farOcrGroups.size)
        assertTrue("content-match replacement is paired", result.farOcrGroups[0].paired)
        assertFalse("fresh far is not paired", result.farOcrGroups[1].paired)
    }

    // ── classifyOcrResults: shrink-direction last-line continuation ──────

    @Test
    fun classify_lastLineContinuation_stalesMultiLineBox() {
        // Campfire trace 2026-07-10 c4: two-line cached box, single-line
        // typewriter tail 7px right of its last line. The whole-box raw
        // height comparison rejects (125 vs 59 → ratio 1.12 over the cap);
        // the last-line probe must stale the box so the merged sentence
        // places on the next cycle instead of stranding a split pair.
        val cached = box(
            Rect(524, 813, 930, 938),
            sourceText = "「キャンプではさ色んな事が、で",
            lineCount = 2,
        )
        val result = classifyOcrResults(
            ocrResult = ocrResult("きるんだよ・" to Rect(937, 880, 1245, 939)),
            boxes = listOf(cached),
            ocrBitmapRects = listOf(cached.bounds),
            coords = identityCoords,
        )
        assertEquals(setOf(0), result.staleOverlayIndices)
        assertTrue("fragment must be suppressed, not placed", result.farOcrGroups.isEmpty())
    }

    @Test
    fun classify_belowLineContinuation_alignedLineBelowParagraph_stalesBox() {
        // FLIPPED PIN (2026-07-16). This fixture — a fresh single line
        // directly below the cached paragraph, start-aligned, per-line
        // heights within the cap, tight gap — was pinned as stays-far
        // under the pre-fix asymmetry ("more likely unrelated text than a
        // continuation"). The グラウス trace 2026-07-12 falsified that
        // prior: line-by-line typewriter reveals produce exactly this
        // geometry, and refusing it stranded a sentence as a permanent
        // 2+1 split (the under-box reveal sat below the pinhole removal
        // bar, so nothing else could converge it). blockContinuesBelow
        // now stales the box; the fresh line is suppressed so the forced
        // follow-up look re-reads the whole uncovered region and the
        // same-pass grouper decides with full evidence. Priced trade: an
        // unrelated label matching ALL per-line conditions costs one
        // recoverable blank-and-regroup cycle.
        val cached = box(Rect(100, 100, 500, 200), sourceText = "two line paragraph", lineCount = 2)
        val result = classifyOcrResults(
            ocrResult = ocrResult("next revealed row" to Rect(100, 210, 400, 255)),
            boxes = listOf(cached),
            ocrBitmapRects = listOf(cached.bounds),
            coords = identityCoords,
        )
        assertEquals(
            "aligned same-scale below-line must stale the cached box",
            setOf(0), result.staleOverlayIndices,
        )
        assertTrue(
            "fresh line must be suppressed so next cycle re-OCRs the full paragraph",
            result.farOcrGroups.isEmpty(),
        )
    }

    @Test
    fun classify_lastLineProbe_distantColumn_staysFar() {
        // Same last-line height but a column gap away: dx 100 ≥ 1.5×lineH 75.
        // Two-column layouts must not stale each other — note the probe's dx
        // gate is TIGHTER than the whole-box inline branch's (which would
        // allow dx up to 150 here on raw refH).
        val cached = box(Rect(100, 100, 500, 200), sourceText = "left block", lineCount = 2)
        val result = classifyOcrResults(
            ocrResult = ocrResult("right column" to Rect(600, 155, 800, 195)),
            boxes = listOf(cached),
            ocrBitmapRects = listOf(cached.bounds),
            coords = identityCoords,
        )
        assertTrue(result.staleOverlayIndices.isEmpty())
        assertEquals(1, result.farOcrGroups.size)
    }

    @Test
    fun classify_lastLineProbe_fragmentOverlappingUnionWidth_staysFar() {
        // Ragged paragraph: long first line, short last line. The cached
        // union rect (100..600 wide) says nothing about the last line's
        // true extent, so a fragment INSIDE the union width on the
        // last-line band is not continuation evidence — the probe must
        // refuse x-overlap outright. (Chosen to overlap the union by only
        // 20px ≈ 11% of the fragment, below the 0.30 substantial-overlap
        // short-circuit, so this isolates the probe's overlap arm.)
        val cached = box(
            Rect(100, 100, 600, 200),
            sourceText = "long first line, short last",
            lineCount = 2,
        )
        val result = classifyOcrResults(
            ocrResult = ocrResult("unrelated hud" to Rect(580, 160, 760, 195)),
            boxes = listOf(cached),
            ocrBitmapRects = listOf(cached.bounds),
            coords = identityCoords,
        )
        assertTrue(result.staleOverlayIndices.isEmpty())
        assertEquals(1, result.farOcrGroups.size)
    }

    @Test
    fun classify_lastLineProbe_fragmentOnNonForwardSide_staysFar() {
        // LTR text reveals left→right, so a continuation can only extend
        // PAST the last line's end — a same-height fragment just LEFT of
        // the box (label, list bullet, another column) is a neighbor and
        // must not stale the overlay on geometry alone.
        val cached = box(Rect(300, 100, 700, 200), sourceText = "two line paragraph", lineCount = 2)
        val result = classifyOcrResults(
            ocrResult = ocrResult("left label" to Rect(200, 160, 280, 195)),
            boxes = listOf(cached),
            ocrBitmapRects = listOf(cached.bounds),
            coords = identityCoords,
        )
        assertTrue(result.staleOverlayIndices.isEmpty())
        assertEquals(1, result.farOcrGroups.size)
    }

    @Test
    fun classify_lastLineProbe_rtlMirror_matchesLeftRefusesRight() {
        // RTL mirror: the forward side flips. The same left-side fragment
        // that must stay far under LTR is the legitimate continuation side
        // for an RTL source, and the right side becomes the refused one.
        val cached = box(Rect(300, 100, 700, 200), sourceText = "rtl paragraph", lineCount = 2)
        val leftFragment = classifyOcrResults(
            ocrResult = ocrResult("تكملة" to Rect(200, 160, 280, 195)),
            boxes = listOf(cached),
            ocrBitmapRects = listOf(cached.bounds),
            coords = identityCoords,
            rtl = true,
        )
        assertEquals(setOf(0), leftFragment.staleOverlayIndices)
        assertTrue(leftFragment.farOcrGroups.isEmpty())

        val rightFragment = classifyOcrResults(
            ocrResult = ocrResult("جار" to Rect(720, 160, 800, 195)),
            boxes = listOf(cached),
            ocrBitmapRects = listOf(cached.bounds),
            coords = identityCoords,
            rtl = true,
        )
        assertTrue(rightFragment.staleOverlayIndices.isEmpty())
        assertEquals(1, rightFragment.farOcrGroups.size)
    }

    @Test
    fun classify_lastLineProbe_dissimilarHeightFragment_staysFar() {
        // A small badge/icon-text at last-line height right beside the box:
        // per-line heights 50 vs 20 → ratio 1.5 over the cross-frame cap.
        val cached = box(Rect(100, 100, 500, 200), sourceText = "left block", lineCount = 2)
        val result = classifyOcrResults(
            ocrResult = ocrResult("%" to Rect(505, 175, 560, 195)),
            boxes = listOf(cached),
            ocrBitmapRects = listOf(cached.bounds),
            coords = identityCoords,
        )
        assertTrue(result.staleOverlayIndices.isEmpty())
        assertEquals(1, result.farOcrGroups.size)
    }

    // ── classifyOcrResults: shrink-direction below-line continuation ─────
    //
    // Vectors from the グラウス trace (2026-07-12, DetectionLog 13:55 +
    // trace-1783889714647): a three-row typewriter dialogue whose box was
    // placed mid-reveal on rows 1+2, with row 3 appearing below it next
    // cycle. All rects are the trace's real OCR rects.

    @Test
    fun classify_belowLineContinuation_typewriterThirdRow_stalesBox() {
        // c14: cached box "今年は、作物のできも悪いしこのま" (2 lines,
        // 126px), third row "こせないかもしれんなぁ・・" 15px below it.
        // The whole-box raw comparison rejects on scale ((126-55)/55 =
        // 1.29 > 0.50) and the under-box reveal read only 2.0% pinholes,
        // so pre-fix this stranded a permanent 2+1 split. The below probe
        // must stale the box (per-line 63 vs 55 → 0.145; gap 15 < 56;
        // start Δ5 ≤ 31) and suppress the fresh row.
        val cached = box(
            Rect(550, 811, 1240, 937),
            sourceText = "今年は、作物のできも悪いしこのま",
            lineCount = 2,
        )
        val result = classifyOcrResults(
            ocrResult = ocrResult("こせないかもしれんなぁ・・" to Rect(555, 952, 1246, 1007)),
            boxes = listOf(cached),
            ocrBitmapRects = listOf(cached.bounds),
            coords = identityCoords,
        )
        assertEquals(setOf(0), result.staleOverlayIndices)
        assertTrue("third row must be suppressed, not placed", result.farOcrGroups.isEmpty())
    }

    @Test
    fun classify_belowProbe_misalignedPartialRevealBox_staysFarForDeferral() {
        // c4 of the same trace, full two-group replay: the cached box is
        // the PARTIAL reveal "北の、グラウス山にモン" whose rect is
        // narrower than the finished rows, so the third row misses the
        // below probe on alignment (start Δ38 > 31, center Δ72 > 31) and
        // stays far. The same-row tail 「いて」 still stales the box via
        // the last-line probe — which is what hands the far third row to
        // step-9b deferral (the box is stale-DYING; see
        // FragmentDeferralTest's stale-dying vectors for that half).
        val cached = box(
            Rect(521, 812, 1034, 936),
            sourceText = "北の、グラウス山にモン",
            lineCount = 2,
        )
        val result = classifyOcrResults(
            ocrResult = ocrResult(
                "いて" to Rect(1047, 886, 1153, 937),
                "うしを盗ってくんだ・" to Rect(559, 949, 1140, 1006),
            ),
            boxes = listOf(cached),
            ocrBitmapRects = listOf(cached.bounds),
            coords = identityCoords,
        )
        assertEquals(
            "same-row tail stales the partial box via the last-line probe",
            setOf(0), result.staleOverlayIndices,
        )
        assertEquals(
            "misaligned third row stays far (deferral, not staling, owns it)",
            1, result.farOcrGroups.size,
        )
        assertEquals("うしを盗ってくんだ・", result.farOcrGroups[0].text)
    }

    @Test
    fun classify_belowProbe_gapTooLarge_staysFar() {
        // The probe's gap ceiling is per-line (0.9 × 50 = 45), deliberately
        // TIGHTER than the de-normalized whole-box ceiling (0.9 × 150 =
        // 135) — a row-and-a-half of clearance below a paragraph is a
        // separate block, not its next line.
        val cached = box(Rect(0, 0, 200, 150), sourceText = "three lines", lineCount = 3)
        val result = classifyOcrResults(
            ocrResult = ocrResult("distant line" to Rect(0, 215, 200, 265)),
            boxes = listOf(cached),
            ocrBitmapRects = listOf(cached.bounds),
            coords = identityCoords,
        )
        assertTrue(result.staleOverlayIndices.isEmpty())
        assertEquals(1, result.farOcrGroups.size)
    }

    @Test
    fun classify_belowProbe_freshLineAboveBox_staysFar() {
        // Directional: reveals grow downward. A single line ABOVE a
        // multi-line box (speaker name plate, heading) must not stale it
        // even when aligned, close, and per-line height-compatible.
        val cached = box(Rect(100, 200, 500, 300), sourceText = "two line dialogue", lineCount = 2)
        val result = classifyOcrResults(
            ocrResult = ocrResult("name plate" to Rect(100, 150, 300, 195)),
            boxes = listOf(cached),
            ocrBitmapRects = listOf(cached.bounds),
            coords = identityCoords,
        )
        assertTrue(result.staleOverlayIndices.isEmpty())
        assertEquals(1, result.farOcrGroups.size)
    }

    @Test
    fun classify_belowProbe_multiLineFresh_staysFar() {
        // The probe is gated to single fresh lines (mirroring the last-line
        // probe): a multi-line fresh group below a taller cached box is an
        // unobserved shape and keeps the de-normalized whole-box verdict.
        val cached = box(Rect(0, 0, 200, 150), sourceText = "three lines", lineCount = 3)
        val ocr = OcrManager.OcrResult(
            fullText = "",
            segments = emptyList(),
            groups = listOf(grp("two fresh lines", Rect(0, 160, 200, 240), lineCount = 2)),
        )
        val result = classifyOcrResults(
            ocrResult = ocr,
            boxes = listOf(cached),
            ocrBitmapRects = listOf(cached.bounds),
            coords = identityCoords,
        )
        assertTrue(result.staleOverlayIndices.isEmpty())
        assertEquals(1, result.farOcrGroups.size)
    }

    @Test
    fun classify_belowProbe_rtlMirror_matchesRightAlignedRefusesLtr() {
        // RTL sources align continuations on the RIGHT edge. The same
        // ragged-left below-line that matches under rtl=true (right edges
        // 700 vs 702) must stay far under rtl=false, where neither the
        // left edges (Δ150) nor the centers (Δ76) align at the per-line
        // tolerance (25).
        val cached = box(Rect(300, 100, 700, 200), sourceText = "rtl paragraph", lineCount = 2)
        val rtlResult = classifyOcrResults(
            ocrResult = ocrResult("تكملة السطر" to Rect(450, 210, 702, 255)),
            boxes = listOf(cached),
            ocrBitmapRects = listOf(cached.bounds),
            coords = identityCoords,
            rtl = true,
        )
        assertEquals(setOf(0), rtlResult.staleOverlayIndices)
        assertTrue(rtlResult.farOcrGroups.isEmpty())

        val ltrResult = classifyOcrResults(
            ocrResult = ocrResult("ragged line" to Rect(450, 210, 702, 255)),
            boxes = listOf(cached),
            ocrBitmapRects = listOf(cached.bounds),
            coords = identityCoords,
            rtl = false,
        )
        assertTrue(ltrResult.staleOverlayIndices.isEmpty())
        assertEquals(1, ltrResult.farOcrGroups.size)
    }

    @Test
    fun classify_farCoalesce_noContentMatch_doesNotRemergeOcrSplits() {
        // Bug regression (epilepsy-warning screen, v2.2.0): with no cached
        // boxes (first live-mode cycle), content-match cannot fire, so the
        // coalesce gate stays closed and the Far branch trusts OCR's
        // within-frame SPLIT decision verbatim. Previously, the coalesce
        // ran unconditionally and re-merged genuinely-separate paragraphs
        // because its per-line refH normalization on whole-group rects
        // inflated the block-gap threshold past OCR's intra-pass value.
        //
        // Geometry mirrors the live capture that exposed the bug:
        //   group 0: single line at y=0..33   (height 33, lineCount 1)
        //   group 1: four lines  at y=62..215 (height 153, lineCount 4)
        // dy = 29; per-line refH would be max(33, 153/4=38) = 38; gated
        // wouldGroup's block threshold = 38 * 0.8 = 30, so 29 < 30 used
        // to flip SPLIT → MERGE. The gate now skips that re-evaluation
        // entirely when no paired FAR exists, leaving OCR's groups as-is.
        val result = classifyOcrResults(
            ocrResult = ocrResult(
                "single line above" to Rect(0, 0, 200, 33),
                "four-line paragraph below" to Rect(0, 62, 200, 215),
                lineCounts = listOf(1, 4),
            ),
            boxes = emptyList(),
            ocrBitmapRects = emptyList(),
            coords = identityCoords,
        )
        assertTrue(
            "no content-match → no paired FAR → coalesce gated off → no removals",
            result.contentMatchRemovals.isEmpty(),
        )
        assertEquals(
            "OCR's two-group split must survive classification when the coalesce gate is closed",
            2, result.farOcrGroups.size,
        )
        assertEquals("single line above", result.farOcrGroups[0].text)
        assertEquals("four-line paragraph below", result.farOcrGroups[1].text)
    }

    @Test
    fun classify_farCoalesce_unrelatedContentMatchElsewhere_doesNotEnableMergeOfFreshSplits() {
        // Tighter regression for the gate. The earlier "no content-match"
        // case is necessary but not sufficient — a global gate
        // (contentMatchRemovals.isNotEmpty()) would still let any unrelated
        // content-match elsewhere on screen re-open coalescing for fresh
        // fragments. The gate must be candidate-specific: only paired FARs
        // (queued by content-match) are eligible coalesce targets, and
        // fresh-FARs added by an earlier Far-branch iteration are NOT.
        //
        // Setup:
        //   cached "Score" at top-right (1500,50,1700,90)
        //   group 0: "Score" at the same position — content-matches the
        //            cached box and queues a paired FAR (top-right)
        //   group 1: a single-line paragraph at the center top
        //   group 2: a four-line paragraph just below group 1, with the
        //            exact epilepsy-warning geometry that the simple
        //            global gate previously re-merged
        // With a global gate, group 2 would coalesce with the fresh FAR
        // from group 1 (since the gate is open because of the score
        // content-match). With the candidate-specific gate, only the
        // paired top-right FAR is eligible — and it doesn't match
        // geometrically — so group 2 stays as its own FAR.
        val cachedScore = box(Rect(1500, 50, 1700, 90), sourceText = "Score")
        val result = classifyOcrResults(
            ocrResult = ocrResult(
                "Score" to Rect(1500, 50, 1700, 90),
                "single line above" to Rect(0, 400, 200, 433),
                "four-line paragraph below" to Rect(0, 462, 200, 615),
                lineCounts = listOf(1, 1, 4),
            ),
            boxes = listOf(cachedScore),
            ocrBitmapRects = listOf(cachedScore.bounds),
            coords = identityCoords,
        )
        assertEquals(
            "score content-matches its cached box",
            setOf(0), result.contentMatchRemovals,
        )
        assertEquals(
            "candidate-specific gate keeps unrelated fresh splits separate even when other content-match opens the cycle's coalesce path",
            3, result.farOcrGroups.size,
        )
        assertEquals("Score", result.farOcrGroups[0].text)
        assertEquals("single line above", result.farOcrGroups[1].text)
        assertEquals("four-line paragraph below", result.farOcrGroups[2].text)
    }

    // ── classifyOcrResults: mixed end-to-end ─────────────────────────────

    @Test
    fun classify_mixedScenario_contentAndStaleAndFar() {
        // Scene:
        //   box 0: "hello" at (0,0,100,100) — content-matches group 0
        //   box 1: "keep"  at (500,0,600,100) — untouched
        //   box 2: "old"   at (1000,0,1100,100) — proximity-stale via group 1
        val boxes = listOf(
            box(Rect(0, 0, 100, 100), sourceText = "hello"),
            box(Rect(500, 0, 600, 100), sourceText = "keep"),
            box(Rect(1000, 0, 1100, 100), sourceText = "old stale text"),
        )
        val result = classifyOcrResults(
            ocrResult = ocrResult(
                "hello" to Rect(5_000, 5_000, 5_100, 5_100),  // Content match → box 0
                "xxxxx" to Rect(1_050, 0, 1_150, 100),          // Overlaps box 2
                "brand new" to Rect(5_000, 0, 5_100, 100),      // Far from all
            ),
            boxes = boxes,
            ocrBitmapRects = boxes.map { it.bounds },
            coords = identityCoords,
        )
        assertEquals(setOf(0), result.contentMatchRemovals)
        assertEquals(setOf(2), result.staleOverlayIndices)
        assertFalse(
            "box 1 (keep) must not be removed or staled",
            result.contentMatchRemovals.contains(1) ||
                result.staleOverlayIndices.contains(1),
        )
        assertEquals(2, result.farOcrGroups.size)
        assertEquals("hello", result.farOcrGroups[0].text)
        assertEquals("brand new", result.farOcrGroups[1].text)
    }

    // ── cascadeStaleRemovals ────────────────────────────────────────────

    @Test
    fun cascade_emptyInitial_returnsEmpty() {
        val result = cascadeStaleRemovals(
            initialStale = emptySet(),
            boxes = listOf(box(Rect(0, 0, 100, 100))),
            ocrBitmapRects = listOf(Rect(0, 0, 100, 100)),
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun cascade_singleStale_noNeighbors_returnsSameSet() {
        val bounds0 = Rect(0, 0, 100, 100)
        val bounds1 = Rect(5_000, 5_000, 5_100, 5_100)  // Far away
        val result = cascadeStaleRemovals(
            initialStale = setOf(0),
            boxes = listOf(box(bounds0), box(bounds1)),
            ocrBitmapRects = listOf(bounds0, bounds1),
        )
        assertEquals(setOf(0), result)
    }

    @Test
    fun cascade_singleStale_withOverlappingNeighbor_pullsInNeighbor() {
        val bounds0 = Rect(0, 0, 100, 100)
        val bounds1 = Rect(50, 0, 150, 100)  // Overlaps bounds0
        val result = cascadeStaleRemovals(
            initialStale = setOf(0),
            boxes = listOf(box(bounds0), box(bounds1)),
            ocrBitmapRects = listOf(bounds0, bounds1),
        )
        assertEquals(setOf(0, 1), result)
    }

    @Test
    fun cascade_rtlArabic_raggedLeftSharedRight_pullsNeighborOnlyWhenRtl() {
        // Two stacked lines of an Arabic paragraph: shared RIGHT edge, ragged LEFT
        // edge (the short line starts far right). Cross-frame cascade must use the
        // same right-edge convention as within-frame grouping for RTL sources, or a
        // stale Arabic line won't drag its real neighbor and the overlay goes stale.
        val full = Rect(160, 0, 1760, 36)      // full-width line
        val short = Rect(1000, 40, 1762, 76)   // short line right below — right-aligned
        val boxes = listOf(box(full), box(short))
        val rects = listOf(full, short)
        assertEquals(
            "RTL: right-edge alignment pulls in the neighbor",
            setOf(0, 1),
            cascadeStaleRemovals(initialStale = setOf(0), boxes = boxes, ocrBitmapRects = rects, rtl = true),
        )
        assertEquals(
            "LTR: left-edge alignment leaves the ragged-left neighbor untouched",
            setOf(0),
            cascadeStaleRemovals(initialStale = setOf(0), boxes = boxes, ocrBitmapRects = rects, rtl = false),
        )
    }

    @Test
    fun cascade_chainOfThree_allPulledIn() {
        // 0 ↔ 1 (dx=100, refH*1.5=150, groups)
        // 1 ↔ 2 (dx=100, groups)
        // 0 ↔ 2 (dx=300 > 150, does NOT directly group)
        val bounds0 = Rect(0, 0, 100, 100)
        val bounds1 = Rect(200, 0, 300, 100)
        val bounds2 = Rect(400, 0, 500, 100)
        val result = cascadeStaleRemovals(
            initialStale = setOf(0),
            boxes = listOf(box(bounds0), box(bounds1), box(bounds2)),
            ocrBitmapRects = listOf(bounds0, bounds1, bounds2),
        )
        assertEquals(
            "cascade must reach the end of the chain via transitive neighbors",
            setOf(0, 1, 2), result,
        )
    }

    @Test
    fun cascade_skipsDirtyNeighbors() {
        val bounds0 = Rect(0, 0, 100, 100)
        val bounds1 = Rect(50, 0, 150, 100)  // Would overlap, but dirty
        val result = cascadeStaleRemovals(
            initialStale = setOf(0),
            boxes = listOf(box(bounds0), box(bounds1, dirty = true)),
            ocrBitmapRects = listOf(bounds0, bounds1),
        )
        assertEquals(setOf(0), result)
    }

    @Test
    fun cascade_ocrBitmapRectsOverflow_safelySkipped() {
        val bounds0 = Rect(0, 0, 100, 100)
        val bounds1 = Rect(50, 0, 150, 100)  // Would overlap bounds0
        val result = cascadeStaleRemovals(
            initialStale = setOf(0),
            boxes = listOf(box(bounds0), box(bounds1)),
            ocrBitmapRects = listOf(bounds0),  // Only one entry
        )
        assertEquals(
            "box 1 has no bitmapRect, cascade must silently skip it",
            setOf(0), result,
        )
    }

    @Test
    fun cascade_multiLineStaleAndSingleLineNeighbor_doesNotAbsorb() {
        // A stale 3-line cached box and an adjacent single-line cached
        // box of the same font with a small vertical gap must NOT
        // cascade. They were already kept apart by within-frame
        // grouping, so cascade has no fresh-OCR replacement evidence
        // and absorbing the neighbor would remove a still-valid
        // translation. The raw-height ratio (~2.0 here) is the
        // implicit guard — per-line normalization would tear it down.
        val staleBounds = Rect(0, 0, 200, 150)        // 3 lines, h=150
        val neighborBounds = Rect(0, 160, 200, 210)   // 1 line, h=50, dy=10
        val result = cascadeStaleRemovals(
            initialStale = setOf(0),
            boxes = listOf(
                box(staleBounds, lineCount = 3),
                box(neighborBounds, lineCount = 1),
            ),
            ocrBitmapRects = listOf(staleBounds, neighborBounds),
        )
        assertEquals(
            "multi-line stale must not absorb separate single-line neighbor",
            setOf(0), result,
        )
    }

    @Test
    fun cascade_multipleInitialStale_disjointNeighborhoods() {
        // 0 and 3 are initial; 0-1 neighbors, 3-4 neighbors; 2 isolated.
        val bounds0 = Rect(0, 0, 100, 100)
        val bounds1 = Rect(200, 0, 300, 100)
        val bounds2 = Rect(5_000, 5_000, 5_100, 5_100)
        val bounds3 = Rect(0, 2_000, 100, 2_100)
        val bounds4 = Rect(200, 2_000, 300, 2_100)
        val result = cascadeStaleRemovals(
            initialStale = setOf(0, 3),
            boxes = listOf(
                box(bounds0), box(bounds1), box(bounds2),
                box(bounds3), box(bounds4),
            ),
            ocrBitmapRects = listOf(bounds0, bounds1, bounds2, bounds3, bounds4),
        )
        assertEquals(setOf(0, 1, 3, 4), result)
        assertFalse("isolated box 2 must not be pulled in", 2 in result)
    }
}
