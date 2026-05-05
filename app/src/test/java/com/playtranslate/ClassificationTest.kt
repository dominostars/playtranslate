package com.playtranslate

import android.graphics.Rect
import com.playtranslate.ui.TranslationOverlayView
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
    ) = TranslationOverlayView.TextBox(
        translatedText = "",
        bounds = bounds,
        sourceText = sourceText,
        dirty = dirty,
    )

    private fun ocrResult(
        vararg groups: Pair<String, Rect>,
        lineCounts: List<Int>? = null,
    ) = OcrManager.OcrResult(
        fullText = "",
        segments = emptyList(),
        groupTexts = groups.map { it.first },
        groupBounds = groups.map { it.second },
        groupLineCounts = lineCounts ?: groups.map { 1 },
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

    // ── classifyOcrResults: defensive index guards ───────────────────────

    @Test
    fun classify_groupBoundsShorterThanGroupTexts_skipsOverflow() {
        val ocr = OcrManager.OcrResult(
            fullText = "",
            segments = emptyList(),
            groupTexts = listOf("a", "b", "c"),
            groupBounds = listOf(Rect(0, 0, 100, 100)),  // Only one entry
            groupLineCounts = listOf(1),
        )
        val result = classifyOcrResults(
            ocrResult = ocr,
            boxes = emptyList(),
            ocrBitmapRects = emptyList(),
            coords = identityCoords,
        )
        assertEquals(1, result.farOcrGroups.size)
        assertEquals("a", result.farOcrGroups[0].text)
    }

    @Test
    fun classify_lineCountDefaultsToOneWhenMissing() {
        val ocr = OcrManager.OcrResult(
            fullText = "",
            segments = emptyList(),
            groupTexts = listOf("a", "b"),
            groupBounds = listOf(Rect(0, 0, 100, 100), Rect(0, 200, 100, 300)),
            groupLineCounts = listOf(3),  // Missing entry for index 1
        )
        val result = classifyOcrResults(
            ocrResult = ocr,
            boxes = emptyList(),
            ocrBitmapRects = emptyList(),
            coords = identityCoords,
        )
        assertEquals(2, result.farOcrGroups.size)
        assertEquals(3, result.farOcrGroups[0].lineCount)
        assertEquals(1, result.farOcrGroups[1].lineCount)  // defaulted
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
