package com.playtranslate

import android.graphics.Rect
import android.text.TextPaint
import com.playtranslate.language.HintTextAnnotation
import com.playtranslate.language.TextOrientation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for [FuriganaSlantPlacement] — the deskewed-frame ruby placement
 * for slanted lines. The θ==0 contract is structural (the upright arithmetic in
 * [OverlayToolkit.buildFuriganaBoxesForGroup] is untouched and this object
 * returns nothing for upright lines); these tests pin the rotated geometry:
 * band offset, merge-along-u, and the exact-AABB realization.
 */
@RunWith(RobolectricTestRunner::class)
class FuriganaSlantPlacementTest {

    private val theta = -20f
    private val cos = 0.93969
    private val sin = -0.34202

    /** A −20° line (300×48 strip, AABB centered 200,150) with baseline cells
     *  at u = −100, 0, +100 — the shared slanted-char shape. */
    private fun line(): OcrManager.LineBox {
        fun cell(u: Double, offset: Int, ch: String): OcrManager.SymbolBox {
            val cx = 200 + u * cos
            val cy = 150 + u * sin
            return OcrManager.SymbolBox(
                ch, Rect((cx - 20).toInt(), (cy - 24).toInt(), (cx + 20).toInt(), (cy + 24).toInt()), offset,
            )
        }
        return OcrManager.LineBox(
            text = "私はい",
            bounds = Rect(51, 76, 349, 224),
            groupIndex = 0,
            symbols = listOf(cell(-100.0, 0, "私"), cell(0.0, 1, "は"), cell(100.0, 2, "い")),
            orientation = TextOrientation.HORIZONTAL,
            angleDeg = theta,
            orientedWidth = 300f,
            orientedHeight = 48f,
        )
    }

    @Test
    fun bandRidesAboveTheBaseline_atTheRotatedOffset() {
        val boxes = FuriganaSlantPlacement.build(
            line(), listOf(HintTextAnnotation(0, 1, "わたし")), TextPaint(),
        )
        val ruby = boxes.single()
        assertEquals(theta, ruby.angleDeg, 0f)
        // The band center sits at in-frame (u≈−100, v=−oh/2−fh/2=−42) rotated
        // out about (200,150): the AABB center must land there ±1.5px.
        val expX = 200 + (-100.0) * cos - (-42.0) * sin
        val expY = 150 + (-100.0) * sin + (-42.0) * cos
        assertEquals(expX.toFloat(), ruby.bounds.exactCenterX(), 1.5f)
        assertEquals(expY.toFloat(), ruby.bounds.exactCenterY(), 1.5f)
        // Band dims: the base cell's u-extent × 0.75·oh.
        assertEquals(40f, ruby.orientedWidth, 1.5f)
        assertEquals(36f, ruby.orientedHeight, 1.5f)
    }

    @Test
    fun collidingSpans_mergeAlongTheBaseline() {
        // Cells wide enough that adjacent spans' u-extents overlap (Robolectric
        // measures text as zero-width, so the geometric overlap is what this
        // pins — the rendered-extent term only widens it in production): the
        // two bands must merge into ONE chip spanning both, not two
        // overlapping ones.
        fun wideCell(u: Double, offset: Int, ch: String): OcrManager.SymbolBox {
            val cx = 200 + u * cos
            val cy = 150 + u * sin
            return OcrManager.SymbolBox(
                ch, Rect((cx - 55).toInt(), (cy - 24).toInt(), (cx + 55).toInt(), (cy + 24).toInt()), offset,
            )
        }
        val wide = line().copy(
            symbols = listOf(wideCell(-100.0, 0, "私"), wideCell(0.0, 1, "は"), wideCell(100.0, 2, "い")),
        )
        val boxes = FuriganaSlantPlacement.build(
            wide,
            listOf(
                HintTextAnnotation(0, 2, "わたしは"),
                HintTextAnnotation(2, 3, "い"),
            ),
            TextPaint(),
        )
        assertEquals(1, boxes.size)
        assertEquals("わたしはい", boxes.single().translatedText)
        assertEquals(theta, boxes.single().angleDeg, 0f)
    }

    @Test
    fun boundsAreTheExactAabbOfTheOrientedBand() {
        val ruby = FuriganaSlantPlacement.build(
            line(), listOf(HintTextAnnotation(0, 3, "よみ")), TextPaint(),
        ).single()
        // Invariant 1: AABB dims == closed-form w·|cos| + h·|sin| (ceil ±1).
        val w = ruby.orientedWidth
        val h = ruby.orientedHeight
        val expW = w * cos.toFloat().let { kotlin.math.abs(it) } + h * kotlin.math.abs(sin.toFloat())
        val expH = w * kotlin.math.abs(sin.toFloat()) + h * cos.toFloat().let { kotlin.math.abs(it) }
        assertTrue(kotlin.math.abs(ruby.bounds.width() - expW) <= 1.5f)
        assertTrue(kotlin.math.abs(ruby.bounds.height() - expH) <= 1.5f)
    }

    @Test
    fun uprightLine_yieldsNothingHere() {
        val upright = line().copy(angleDeg = 0f, orientedWidth = 0f, orientedHeight = 0f)
        assertTrue(
            FuriganaSlantPlacement.build(upright, listOf(HintTextAnnotation(0, 1, "よ")), TextPaint()).isEmpty(),
        )
    }
}
