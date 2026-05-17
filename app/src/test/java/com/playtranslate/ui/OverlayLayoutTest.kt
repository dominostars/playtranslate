package com.playtranslate.ui

import android.graphics.Rect
import android.graphics.RectF
import com.playtranslate.language.TextOrientation
import org.junit.Assert.assertEquals
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
        isFurigana: Boolean = false,
        orientation: TextOrientation = TextOrientation.HORIZONTAL,
    ) = TextBox(
        translatedText = "x",
        bounds = bounds,
        isFurigana = isFurigana,
        orientation = orientation,
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
        )
        assertEquals(RectF(94f, 94f, 206f, 156f), rects[0])
    }

    @Test
    fun resolve_furigana_isNotPadded() {
        val rects = OverlayLayout.resolveScreenRects(
            listOf(box(Rect(100, 100, 200, 150), isFurigana = true)),
            cropLeft = 0, cropTop = 0,
            screenshotW = 1000, screenshotH = 1000,
            displayW = 1000, displayH = 1000,
            density = 1f,
        )
        assertEquals(RectF(100f, 100f, 200f, 150f), rects[0])
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
        )
        assertEquals(RectF(0f, 0f, 56f, 56f), rects[0])
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
        )
        assertEquals(RectF(100f, 100f, 200f, 200f), rects[0])
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
        )
        // Overlap 180..200 → split at mid 190.
        assertEquals(RectF(100f, 100f, 300f, 190f), rects[0])
        assertEquals(RectF(100f, 190f, 300f, 280f), rects[1])
    }

    @Test
    fun resolve_verticalBoxes_horizontalOverlapSplitAtMidpoint() {
        val rects = OverlayLayout.resolveScreenRects(
            listOf(
                box(Rect(100, 100, 200, 400), orientation = TextOrientation.VERTICAL),
                box(Rect(180, 100, 280, 400), orientation = TextOrientation.VERTICAL),
            ),
            cropLeft = 0, cropTop = 0,
            screenshotW = 1000, screenshotH = 1000,
            displayW = 1000, displayH = 1000,
            density = 0f,
        )
        // Overlap 180..200 → split at mid 190.
        assertEquals(RectF(100f, 100f, 190f, 400f), rects[0])
        assertEquals(RectF(190f, 100f, 280f, 400f), rects[1])
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
        )
        assertEquals(RectF(100f, 100f, 300f, 200f), rects[0])
        assertEquals(RectF(100f, 180f, 300f, 280f), rects[1])
    }
}
