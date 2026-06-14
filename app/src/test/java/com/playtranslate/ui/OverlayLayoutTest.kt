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
    fun resolve_horizontalBoxes_verticalOverlapSplitAtMidpoint() {
        // density 0 → no padding, isolating the overlap logic.
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
        // Overlap 180..200 → split at mid 190.
        assertEquals(RectF(100f, 100f, 300f, 190f), rects[0].rect)
        assertEquals(RectF(100f, 190f, 300f, 280f), rects[1].rect)
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
        // Two adjacent narrow vertical sources, grow on → backgrounds grow but
        // stay disjoint, and each still covers its own source.
        val rects = OverlayLayout.resolveScreenRects(
            listOf(
                box(Rect(100, 100, 150, 500), text = "ALPHA BETA", orientation = TextOrientation.VERTICAL, minWidthPx = 300),
                box(Rect(160, 100, 210, 500), text = "GAMMA DELTA", orientation = TextOrientation.VERTICAL, minWidthPx = 300),
            ),
            cropLeft = 0, cropTop = 0,
            screenshotW = 1000, screenshotH = 1000,
            displayW = 1000, displayH = 1000,
            density = 0f,
            targetIsVerticalScript = false,
            targetStackable = true,
            growEnabled = true,
        )
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
        // already-overlapping boxes).
        val rects = OverlayLayout.resolveScreenRects(
            listOf(
                box(Rect(100, 100, 150, 500), text = "AA BB", orientation = TextOrientation.VERTICAL, minWidthPx = 300),
                box(Rect(158, 100, 210, 500), text = "CC DD", orientation = TextOrientation.VERTICAL, minWidthPx = 300),
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
}
