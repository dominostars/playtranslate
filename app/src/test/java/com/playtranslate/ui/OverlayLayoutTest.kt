package com.playtranslate.ui

import android.graphics.Rect
import android.graphics.RectF
import com.playtranslate.language.TextOrientation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for [OverlayLayout] — the pure box-geometry helper extracted from
 * `TranslationOverlayView.rebuildChildren`.
 *
 * Runs under Robolectric so [android.graphics.Rect] / [android.graphics.RectF]
 * are available on the JVM without a device.
 */
@RunWith(RobolectricTestRunner::class)
class OverlayLayoutTest {

    private fun box(
        bounds: Rect,
        text: String = "x",
        isFurigana: Boolean = false,
        orientation: TextOrientation = TextOrientation.HORIZONTAL,
        minWidthPx: Int = 0,
    ) = TextBox(
        translatedText = text,
        bounds = bounds,
        isFurigana = isFurigana,
        orientation = orientation,
        minWidthPx = minWidthPx,
    )

    // ── mapRect ──────────────────────────────────────────────────────────

    @Test
    fun mapRect_identity_passesThrough() {
        assertEquals(
            RectF(10f, 20f, 30f, 40f),
            OverlayLayout.mapRect(Rect(10, 20, 30, 40), 0, 0, 1f, 1f),
        )
    }

    @Test
    fun mapRect_appliesCropThenScale() {
        // (coord + crop) * scale
        assertEquals(
            RectF(30f, 30f, 50f, 50f),
            OverlayLayout.mapRect(Rect(10, 10, 20, 20), 5, 5, 2f, 2f),
        )
    }

    // ── resolveScreenRects: mapping & padding ────────────────────────────

    @Test
    fun resolve_nonFurigana_isPaddedByDensity() {
        // density 1 → 6px padding around the mapped rect.
        val rects = OverlayLayout.resolveScreenRects(
            listOf(box(Rect(100, 100, 200, 150))),
            cropLeft = 0, cropTop = 0,
            screenshotW = 1000, screenshotH = 1000,
            displayW = 1000, displayH = 1000,
            density = 1f,
            targetIsVerticalScript = false,
        )
        assertEquals(RectF(94f, 94f, 206f, 156f), rects[0].rect)
    }

    @Test
    fun resolve_furigana_isNotPadded() {
        val rects = OverlayLayout.resolveScreenRects(
            listOf(box(Rect(100, 100, 200, 150), isFurigana = true)),
            cropLeft = 0, cropTop = 0,
            screenshotW = 1000, screenshotH = 1000,
            displayW = 1000, displayH = 1000,
            density = 1f,
            targetIsVerticalScript = false,
        )
        assertEquals(RectF(100f, 100f, 200f, 150f), rects[0].rect)
    }

    @Test
    fun resolve_padding_coercedToDisplayBounds() {
        // A box at the top-left corner: padding must not push it negative.
        val rects = OverlayLayout.resolveScreenRects(
            listOf(box(Rect(0, 0, 50, 50))),
            cropLeft = 0, cropTop = 0,
            screenshotW = 1000, screenshotH = 1000,
            displayW = 1000, displayH = 1000,
            density = 1f,
            targetIsVerticalScript = false,
        )
        assertEquals(RectF(0f, 0f, 56f, 56f), rects[0].rect)
    }

    @Test
    fun resolve_appliesScale() {
        // screenshot 500 → display 1000 = 2x scale; density 0 isolates scaling.
        val rects = OverlayLayout.resolveScreenRects(
            listOf(box(Rect(50, 50, 100, 100))),
            cropLeft = 0, cropTop = 0,
            screenshotW = 500, screenshotH = 500,
            displayW = 1000, displayH = 1000,
            density = 0f,
            targetIsVerticalScript = false,
        )
        assertEquals(RectF(100f, 100f, 200f, 200f), rects[0].rect)
    }

    // ── resolveScreenRects: overlap resolution ───────────────────────────

    @Test
    fun resolve_horizontalBoxes_stackedRows_splitAtMidlineInPaddingBand() {
        // Genuinely stacked rows: sources y-disjoint (gap 200..204), only the
        // padding bands overlap (density 1 → 6px pad: 206 vs 198). The midline
        // lands inside the inter-row gap, so the source clamp is a no-op and
        // the long-standing split behaviour is preserved.
        val rects = OverlayLayout.resolveScreenRects(
            listOf(
                box(Rect(100, 100, 300, 200)),
                box(Rect(100, 204, 300, 280)),
            ),
            cropLeft = 0, cropTop = 0,
            screenshotW = 1000, screenshotH = 1000,
            displayW = 1000, displayH = 1000,
            density = 1f,
            targetIsVerticalScript = false,
        )
        // Padded overlap 198..206 → split at mid 202, inside the 200..204 gap.
        assertEquals(202f, rects[0].rect.bottom)
        assertEquals(202f, rects[1].rect.top)
    }

    @Test
    fun resolve_horizontalBoxes_overlappingSources_neverCarvedInsideSourceText() {
        // Sources overlap on BOTH axes (upstream OCR jitter/dup) — no clean
        // split exists. The carve must clamp at the source edges: covering
        // source text outranks disjoint rendering (the uncovered sliver would
        // re-OCR as garbage and churn the overlay — 2026-07-10 traces).
        // density 0 → no padding, so rects == sources and the clamps fully
        // bind: both boxes keep their full source coverage.
        val rects = OverlayLayout.resolveScreenRects(
            listOf(
                box(Rect(100, 100, 300, 200)),
                box(Rect(100, 180, 300, 280)),
            ),
            cropLeft = 0, cropTop = 0,
            screenshotW = 1000, screenshotH = 1000,
            displayW = 1000, displayH = 1000,
            density = 0f,
            targetIsVerticalScript = false,
        )
        assertEquals(RectF(100f, 100f, 300f, 200f), rects[0].rect)
        assertEquals(RectF(100f, 180f, 300f, 280f), rects[1].rect)
    }

    @Test
    fun resolve_horizontalBoxes_sideBySide_splitOnXAxisKeepingFullCoverage() {
        // The overlay-shrink trace (2026-07-10): a typewriter tail placed
        // beside a 2-line box. Sources are x-disjoint (930 < 937) but the
        // 6px padding bands overlap in x, and the y-spans overlap heavily.
        // The old y-midline split carved the left box's bottom to ~908,
        // uncovering its entire second text row (y 908..938). The split must
        // instead run along x through the source gap.
        val left = Rect(524, 813, 930, 938)
        val right = Rect(937, 880, 1245, 939)
        val rects = OverlayLayout.resolveScreenRects(
            listOf(box(left), box(right)),
            cropLeft = 0, cropTop = 0,
            screenshotW = 2000, screenshotH = 2000,
            displayW = 2000, displayH = 2000,
            density = 1f,
            targetIsVerticalScript = false,
        )
        val l = rects[0].rect
        val r = rects[1].rect
        assertTrue("left box must keep covering its source", l.contains(RectF(left)))
        assertTrue("right box must keep covering its source", r.contains(RectF(right)))
        assertTrue("boxes must not overlap after the x split", l.right <= r.left)
        // Split runs through the source gap midline (930+937)/2.
        assertEquals(933.5f, l.right)
        assertEquals(933.5f, r.left)
    }

    @Test
    fun resolve_verticalBoxes_horizontalOverlapSplitAtMidpoint() {
        // CJK target (targetIsVerticalScript) → both boxes stack and keep the
        // vertical-footprint horizontal-overlap shrink.
        val rects = OverlayLayout.resolveScreenRects(
            listOf(
                box(Rect(100, 100, 200, 400), orientation = TextOrientation.VERTICAL),
                box(Rect(180, 100, 280, 400), orientation = TextOrientation.VERTICAL),
            ),
            cropLeft = 0, cropTop = 0,
            screenshotW = 1000, screenshotH = 1000,
            displayW = 1000, displayH = 1000,
            density = 0f,
            targetIsVerticalScript = true,
        )
        // Overlap 180..200 → split at mid 190.
        assertEquals(RectF(100f, 100f, 190f, 400f), rects[0].rect)
        assertEquals(RectF(190f, 100f, 280f, 400f), rects[1].rect)
        assertEquals(RenderMode.STACK_UPRIGHT, rects[0].mode)
    }

    @Test
    fun resolve_furiganaBoxes_areExemptFromOverlapResolution() {
        // Two overlapping furigana boxes must pass through unadjusted.
        val rects = OverlayLayout.resolveScreenRects(
            listOf(
                box(Rect(100, 100, 300, 200), isFurigana = true),
                box(Rect(100, 180, 300, 280), isFurigana = true),
            ),
            cropLeft = 0, cropTop = 0,
            screenshotW = 1000, screenshotH = 1000,
            displayW = 1000, displayH = 1000,
            density = 1f,
            targetIsVerticalScript = false,
        )
        assertEquals(RectF(100f, 100f, 300f, 200f), rects[0].rect)
        assertEquals(RectF(100f, 180f, 300f, 280f), rects[1].rect)
    }

    // ── resolveScreenRects: non-CJK vertical routing ─────────────────────

    @Test
    fun resolve_nonCjkVertical_wideBox_isHorizontalInPlace() {
        // A vertical box already wider than its translation's min width →
        // render horizontally in place; rect is just the mapped bounds.
        val rects = OverlayLayout.resolveScreenRects(
            listOf(box(Rect(100, 100, 400, 200), orientation = TextOrientation.VERTICAL, minWidthPx = 50)),
            cropLeft = 0, cropTop = 0,
            screenshotW = 1000, screenshotH = 1000,
            displayW = 1000, displayH = 1000,
            density = 0f,
            targetIsVerticalScript = false,
        )
        assertEquals(RenderMode.HORIZONTAL_IN_PLACE, rects[0].mode)
        assertEquals(RectF(100f, 100f, 400f, 200f), rects[0].rect)
    }

    @Test
    fun resolve_nonCjkVertical_narrowBox_growOff_rotates() {
        // Narrow, non-stackable target, grow off → 90° rotation in footprint.
        val rects = OverlayLayout.resolveScreenRects(
            listOf(box(Rect(100, 100, 150, 500), orientation = TextOrientation.VERTICAL, minWidthPx = 300)),
            cropLeft = 0, cropTop = 0,
            screenshotW = 1000, screenshotH = 1000,
            displayW = 1000, displayH = 1000,
            density = 0f,
            targetIsVerticalScript = false,
            targetStackable = false,
            growEnabled = false,
        )
        assertEquals(RenderMode.ROTATE, rects[0].mode)
    }

    @Test
    fun resolve_nonCjkVertical_shortToken_stacks() {
        // Narrow box, single short token, stackable script → STACK_UPRIGHT.
        val rects = OverlayLayout.resolveScreenRects(
            listOf(box(Rect(100, 100, 160, 460), text = "PLAY", orientation = TextOrientation.VERTICAL, minWidthPx = 300)),
            cropLeft = 0, cropTop = 0,
            screenshotW = 1000, screenshotH = 1000,
            displayW = 1000, displayH = 1000,
            density = 1f,
            targetIsVerticalScript = false,
            targetStackable = true,
        )
        assertEquals(RenderMode.STACK_UPRIGHT, rects[0].mode)
    }

    @Test
    fun resolve_growEnabled_isolatedNarrow_growsToMinWidth_onSource() {
        // Narrow box, multi-word (not stackable as one column), grow on →
        // GROW_HORIZONTAL grown symmetrically to min width, still covering source.
        val rects = OverlayLayout.resolveScreenRects(
            listOf(box(Rect(100, 100, 150, 500), text = "HELLO THERE", orientation = TextOrientation.VERTICAL, minWidthPx = 200)),
            cropLeft = 0, cropTop = 0,
            screenshotW = 1000, screenshotH = 1000,
            displayW = 1000, displayH = 1000,
            density = 0f,
            targetIsVerticalScript = false,
            targetStackable = true,
            growEnabled = true,
        )
        assertEquals(RenderMode.GROW_HORIZONTAL, rects[0].mode)
        assertEquals(200f, rects[0].rect.width(), 0.5f)
        // Still covers the original mapped bounds (100..150 at density 0).
        assertTrue(rects[0].rect.left <= 100f && rects[0].rect.right >= 150f)
    }

    @Test
    fun resolve_growEnabled_twoNeighbors_clampDisjoint_eachCoversSource() {
        // Two adjacent narrow vertical sources, grow on → both backgrounds grow but stay disjoint,
        // each still covering its own source. minWidthPx is small enough that both columns can
        // reach their target, so this isolates the mutual clamp-disjoint behaviour; the
        // can't-grow-enough → ROTATE fallback is covered by
        // resolve_growEnabled_wedgedColumnBetweenNeighbors_rotates.
        val rects = OverlayLayout.resolveScreenRects(
            listOf(
                box(Rect(100, 100, 150, 500), text = "ALPHA BETA", orientation = TextOrientation.VERTICAL, minWidthPx = 150),
                box(Rect(160, 100, 210, 500), text = "GAMMA DELTA", orientation = TextOrientation.VERTICAL, minWidthPx = 150),
            ),
            cropLeft = 0, cropTop = 0,
            screenshotW = 1000, screenshotH = 1000,
            displayW = 1000, displayH = 1000,
            density = 0f,
            targetIsVerticalScript = false,
            targetStackable = true,
            growEnabled = true,
        )
        assertEquals(RenderMode.GROW_HORIZONTAL, rects[0].mode)
        assertEquals(RenderMode.GROW_HORIZONTAL, rects[1].mode)
        val a = rects[0].rect
        val b = rects[1].rect
        // Disjoint horizontally (no overlap between the two grown backgrounds).
        assertTrue("expected disjoint, got $a / $b", a.right <= b.left || b.right <= a.left)
        // Each still covers its source bounds.
        assertTrue(a.left <= 100f && a.right >= 150f)
        assertTrue(b.left <= 160f && b.right >= 210f)
    }

    @Test
    fun resolve_growEnabled_preOverlappingNeighbors_pushedApart() {
        // Two close vertical regions whose PADDED bounds overlap (density 1 → 6px padding;
        // sources only 8px apart). They must be pushed apart to disjoint, each still covering
        // its unpadded source — the on-device overlap bug (growth alone wouldn't separate
        // already-overlapping boxes). minWidthPx is small enough that both still grow (rather
        // than hitting the wedged-box ROTATE fallback), keeping this a pure de-overlap test.
        val rects = OverlayLayout.resolveScreenRects(
            listOf(
                box(Rect(100, 100, 150, 500), text = "AA BB", orientation = TextOrientation.VERTICAL, minWidthPx = 150),
                box(Rect(158, 100, 210, 500), text = "CC DD", orientation = TextOrientation.VERTICAL, minWidthPx = 150),
            ),
            cropLeft = 0, cropTop = 0,
            screenshotW = 1000, screenshotH = 1000,
            displayW = 1000, displayH = 1000,
            density = 1f,
            targetIsVerticalScript = false,
            targetStackable = true,
            growEnabled = true,
        )
        assertEquals(RenderMode.GROW_HORIZONTAL, rects[0].mode)
        assertEquals(RenderMode.GROW_HORIZONTAL, rects[1].mode)
        val a = rects[0].rect
        val b = rects[1].rect
        assertTrue("expected disjoint after push-apart, got $a / $b", a.right <= b.left || b.right <= a.left)
        // Each still covers its unpadded OCR bounds.
        assertTrue(a.left <= 100f && a.right >= 150f)
        assertTrue(b.left <= 158f && b.right >= 210f)
    }

    @Test
    fun resolve_adjacentColumns_growAndInPlace_pushedApart() {
        // The on-device bug ("今夜は"/"tonight"): a wide vertical column (HORIZONTAL_IN_PLACE)
        // and a narrow vertical column (GROW) sit side by side with overlapping padded bounds.
        // They render differently but are still sibling columns — overlap must resolve by
        // orientation, not render footprint (the two used to land in different passes).
        val rects = OverlayLayout.resolveScreenRects(
            listOf(
                box(Rect(100, 100, 300, 600), text = "After spending the", orientation = TextOrientation.VERTICAL, minWidthPx = 100),
                box(Rect(310, 100, 360, 400), text = "to night", orientation = TextOrientation.VERTICAL, minWidthPx = 250),
            ),
            cropLeft = 0, cropTop = 0,
            screenshotW = 1000, screenshotH = 1000,
            displayW = 1000, displayH = 1000,
            density = 1f,
            targetIsVerticalScript = false,
            targetStackable = true,
            growEnabled = true,
        )
        assertEquals(RenderMode.HORIZONTAL_IN_PLACE, rects[0].mode)
        assertEquals(RenderMode.GROW_HORIZONTAL, rects[1].mode)
        val a = rects[0].rect
        val b = rects[1].rect
        assertTrue("expected disjoint, got $a / $b", a.right <= b.left || b.right <= a.left)
        assertTrue(a.left <= 100f && a.right >= 300f)   // wide column still covers its source
        assertTrue(b.left <= 310f && b.right >= 360f)   // grown column still covers its source
    }

    @Test
    fun resolve_columnShrunkBelowMinWidth_reclassifiedToGrow() {
        // Box A is wide enough pre-shrink (width 100 ≥ minWidth 98 → would be
        // HORIZONTAL_IN_PLACE), but the overlapping neighbour carves it to 95 < 98. Modes are
        // decided AFTER the shrink, so A reclassifies to GROW instead of rendering too-narrow
        // horizontal text. (Codex P2.)
        val rects = OverlayLayout.resolveScreenRects(
            listOf(
                box(Rect(100, 100, 200, 500), text = "ab cd", orientation = TextOrientation.VERTICAL, minWidthPx = 98),
                box(Rect(190, 100, 290, 500), text = "ef gh", orientation = TextOrientation.VERTICAL, minWidthPx = 98),
            ),
            cropLeft = 0, cropTop = 0,
            screenshotW = 1000, screenshotH = 1000,
            displayW = 1000, displayH = 1000,
            density = 0f,
            targetIsVerticalScript = false,
            targetStackable = true,
            growEnabled = true,
        )
        assertEquals(RenderMode.GROW_HORIZONTAL, rects[0].mode)
    }

    @Test
    fun resolve_growEnabled_wedgedColumnBetweenNeighbors_rotates() {
        // Three tightly packed narrow vertical columns, grow on. The middle column is hemmed in
        // on BOTH sides (neighbours sit right against it), so even claiming all the room it could
        // reach stays far below its target width → it falls back to ROTATE in its narrow footprint
        // instead of a cramped horizontal line. The outer columns each have open space on their
        // far side and still grow.
        val rects = OverlayLayout.resolveScreenRects(
            listOf(
                box(Rect(300, 100, 350, 500), text = "ALPHA BETA", orientation = TextOrientation.VERTICAL, minWidthPx = 300),
                box(Rect(356, 100, 406, 500), text = "GAMMA DELTA", orientation = TextOrientation.VERTICAL, minWidthPx = 300),
                box(Rect(412, 100, 462, 500), text = "EPSILON ZETA", orientation = TextOrientation.VERTICAL, minWidthPx = 300),
            ),
            cropLeft = 0, cropTop = 0,
            screenshotW = 1000, screenshotH = 1000,
            displayW = 1000, displayH = 1000,
            density = 0f,
            targetIsVerticalScript = false,
            targetStackable = true,
            growEnabled = true,
        )
        // Middle column rotates in place, keeping its narrow (ungrown) footprint.
        assertEquals(RenderMode.ROTATE, rects[1].mode)
        assertEquals(50f, rects[1].rect.width(), 0.5f)
        // Outer columns have far-side room and still grow.
        assertEquals(RenderMode.GROW_HORIZONTAL, rects[0].mode)
        assertEquals(RenderMode.GROW_HORIZONTAL, rects[2].mode)
    }

    @Test
    fun resolve_growEnabled_partialRoomAboveThreshold_growsClamped() {
        // A narrow vertical column wedged between two fixed wide columns, but with enough combined
        // room to reach >=70% of its target width → it stays GROW (clamped below target by the
        // neighbours) rather than rotating. Guards the threshold direction: partial-but-legible
        // growth is preferred over rotation.
        val rects = OverlayLayout.resolveScreenRects(
            listOf(
                box(Rect(200, 100, 330, 500), text = "left block", orientation = TextOrientation.VERTICAL, minWidthPx = 50),
                box(Rect(400, 100, 450, 500), text = "WEDGE WORD", orientation = TextOrientation.VERTICAL, minWidthPx = 300),
                box(Rect(560, 100, 700, 500), text = "right block", orientation = TextOrientation.VERTICAL, minWidthPx = 50),
            ),
            cropLeft = 0, cropTop = 0,
            screenshotW = 1000, screenshotH = 1000,
            displayW = 1000, displayH = 1000,
            density = 0f,
            targetIsVerticalScript = false,
            targetStackable = true,
            growEnabled = true,
        )
        assertEquals(RenderMode.GROW_HORIZONTAL, rects[1].mode)
        // Grew into all available room (50 + 70 left + 110 right = 230), clamped below target 300.
        assertEquals(230f, rects[1].rect.width(), 0.5f)
    }

    // ── stackViable ──────────────────────────────────────────────────────

    @Test
    fun stackViable_shortSingleToken_stackableScript_true() {
        assertTrue(OverlayLayout.stackViable("PLAY", RectF(0f, 0f, 60f, 460f), density = 1f, targetStackable = true))
    }

    @Test
    fun stackViable_multiWord_false() {
        // Internal whitespace → multi-word, reads poorly stacked → rejected.
        assertTrue(!OverlayLayout.stackViable("GAME OVER", RectF(0f, 0f, 60f, 460f), density = 1f, targetStackable = true))
    }

    @Test
    fun stackViable_nonStackableScript_false() {
        // e.g. Arabic/Thai target — connected/cluster shaping breaks as cells.
        assertTrue(!OverlayLayout.stackViable("PLAY", RectF(0f, 0f, 60f, 460f), density = 1f, targetStackable = false))
    }

    @Test
    fun stackViable_empty_false() {
        assertTrue(!OverlayLayout.stackViable("", RectF(0f, 0f, 60f, 460f), density = 1f, targetStackable = true))
    }

    @Test
    fun stackViable_longTokenNeedsMultipleColumns_false() {
        // A long single token that can't fit one legible column in a short box →
        // rejected (would wrap to multiple columns or truncate).
        assertTrue(!OverlayLayout.stackViable("ABCDEFGHIJKLMNOP", RectF(0f, 0f, 30f, 60f), density = 1f, targetStackable = true))
    }

    // ── lineBreakRuns (autosize word-fit cap segmentation) ───────────────

    @Test
    fun lineBreakRuns_latin_splitsIntoWords() {
        // Whitespace languages: runs are the words, with the trailing break-space stripped.
        assertEquals(listOf("Cut", "to", "size."), lineBreakRuns("Cut to size."))
    }

    @Test
    fun lineBreakRuns_noSpaceScript_splitsIntoSmallRuns() {
        // The Codex-flagged regression: CJK has no spaces but breaks between characters, so it must
        // NOT come back as one giant unbreakable token (which would make the autosize cap shrink
        // the whole sentence onto one tiny line). Expect many small runs, none the whole string.
        val runs = lineBreakRuns("これは長い文です")
        assertTrue("expected multiple runs, got $runs", runs.size > 1)
        assertTrue("no run should be the whole string, got $runs", runs.none { it == "これは長い文です" })
    }

    @Test
    fun lineBreakRuns_blank_isEmpty() {
        assertTrue(lineBreakRuns("   ").isEmpty())
    }

    // ── SOURCE_ANGLE: slanted source boxes ───────────────────────────────

    /** AABB of a 200×40 rect at 30°, with the oriented trio populated. */
    private fun slantBox(
        bounds: Rect = Rect(300, 300, 493, 435),
        angle: Float = 30f,
        text: String = "x",
        isFurigana: Boolean = false,
    ) = TextBox(
        translatedText = text,
        bounds = bounds,
        isFurigana = isFurigana,
        orientation = TextOrientation.HORIZONTAL,
        angleDeg = angle,
        orientedWidth = 200f,
        orientedHeight = 40f,
    )

    private fun resolve(boxes: List<TextBox>, grow: Boolean = false, stackable: Boolean = false) =
        OverlayLayout.resolveScreenRects(
            boxes,
            cropLeft = 0, cropTop = 0,
            screenshotW = 1000, screenshotH = 1000,
            displayW = 1000, displayH = 1000,
            density = 1f,
            targetIsVerticalScript = false,
            targetStackable = stackable,
            growEnabled = grow,
        )

    @Test
    fun slantedBox_resolvesSourceAngle_regardlessOfPrefs() {
        for (grow in listOf(false, true)) for (stackable in listOf(false, true)) {
            val r = resolve(listOf(slantBox()), grow = grow, stackable = stackable)
            assertEquals(RenderMode.SOURCE_ANGLE, r[0].mode)
        }
    }

    @Test
    fun slantedFurigana_staysLegacy() {
        val r = resolve(listOf(slantBox(isFurigana = true)))
        assertEquals(RenderMode.LEGACY_HORIZONTAL, r[0].mode)
    }

    @Test
    fun zeroAngleBox_resolvesExactlyAsBefore() {
        val r = resolve(listOf(box(Rect(300, 300, 493, 435))))
        assertEquals(RenderMode.LEGACY_HORIZONTAL, r[0].mode)
        assertEquals(RectF(294f, 294f, 499f, 441f), r[0].rect)
    }

    @Test
    fun slantedBox_sitsOutCarvePasses_bothDirections() {
        // An upright horizontal box overlapping the slanted one: neither is
        // carved — the slanted box keeps its padded AABB and the upright box
        // keeps its own (residual overlap is the accepted trade).
        val slanted = slantBox()
        val upright = box(Rect(250, 320, 480, 360))
        val r = resolve(listOf(slanted, upright))
        assertEquals(RectF(294f, 294f, 499f, 441f), r[0].rect)
        assertEquals(RectF(244f, 314f, 486f, 366f), r[1].rect)
    }

    @Test
    fun growCandidate_besideSlantedStraddler_clampsAndRotates() {
        // A narrow vertical GROW box whose row a slanted AABB straddles: the
        // straddler clamps both growth limits, so the box gains nothing and —
        // wedged far below its target width — falls back to ROTATE instead of
        // rendering a sliver-thin horizontal line.
        val growBox = box(
            Rect(400, 100, 450, 400),
            orientation = TextOrientation.VERTICAL,
            minWidthPx = 300,
        )
        val slanted = slantBox(bounds = Rect(100, 150, 700, 250), angle = 20f)
        val r = resolve(listOf(growBox, slanted), grow = true)
        assertEquals(RenderMode.ROTATE, r[0].mode)
        assertEquals("no growth into the straddler", RectF(394f, 94f, 456f, 406f), r[0].rect)

        // Control: without the slanted neighbour the same box does grow.
        val alone = resolve(listOf(growBox), grow = true)
        assertTrue("control must grow, got ${alone[0].rect}", alone[0].rect.width() > 62f)
    }

    @Test
    fun growCandidate_besideUprightStraddler_noLongerGrowsThroughIt() {
        // The pre-existing hole the straddler clamp closes: an UPRIGHT box
        // overlapping the grow box's row landed in neither side limit and was
        // grown straight through. Now it clamps both limits the same way.
        val growBox = box(
            Rect(400, 100, 450, 400),
            orientation = TextOrientation.VERTICAL,
            minWidthPx = 300,
        )
        val straddler = box(Rect(100, 150, 700, 250))
        val r = resolve(listOf(growBox, straddler), grow = true)
        assertEquals(RenderMode.ROTATE, r[0].mode)
        assertEquals("no growth through the upright straddler", RectF(394f, 94f, 456f, 406f), r[0].rect)
    }

    @Test
    fun boxesMatchFuzzy_angleChangeDefeatsFastPath() {
        // An upright↔slanted mode flip always defeats the fast path.
        val flat = box(Rect(300, 300, 493, 435))
        val slanted = slantBox(text = "x")
        assertTrue(!OverlayLayout.boxesMatchFuzzy(listOf(flat), listOf(slanted)))
        // Between two slanted reads, motion is corner displacement of the
        // drawn chip: angle jitter moves this 200×40 chip's corners ~1px —
        // matches (no rebuild churn on a stable banner)...
        val jittered = slanted.copy(angleDeg = 30.6f)
        assertTrue(OverlayLayout.boxesMatchFuzzy(listOf(slanted), listOf(jittered)))
        // ...while a real rotation sweeps them past the tolerance — rebuilds.
        val rotated = slanted.copy(angleDeg = 42f)
        assertTrue(!OverlayLayout.boxesMatchFuzzy(listOf(slanted), listOf(rotated)))
    }
}
