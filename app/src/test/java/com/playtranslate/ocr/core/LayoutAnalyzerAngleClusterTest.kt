package com.playtranslate.ocr.core

import android.graphics.Rect
import com.playtranslate.language.TextOrientation
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the MULTI-MEMBER angle-cluster path in [LayoutAnalyzer.analyze]: same-
 * angle slanted lines group as one paragraph via the deskewed frame — with the
 * text joined in true reading order (the screen-space kernel scrambles slanted
 * stacks), the ORIGINAL line instances escaping (never the deskewed synthetic
 * copies), and group bounds honoring the renderer premise (exact AABB of the
 * oriented union, center-pinned).
 */
@RunWith(RobolectricTestRunner::class)
class LayoutAnalyzerAngleClusterTest {

    /** A slanted line: oriented [w]×[h] at [deg], centered at ([cx],[cy]). */
    private fun slantedRegion(text: String, w: Float, h: Float, deg: Float, cx: Float, cy: Float): RecognizedRegion {
        val r = Math.toRadians(deg.toDouble())
        val c = cos(r).toFloat(); val s = sin(r).toFloat()
        val corners = listOf(
            -w / 2 to -h / 2, w / 2 to -h / 2, w / 2 to h / 2, -w / 2 to h / 2,
        ).map { (x, y) -> (cx + x * c - y * s) to (cy + x * s + y * c) }
        val aabb = Rect(
            DeskewGeometry.roundHalfUp(corners.minOf { it.first }),
            DeskewGeometry.roundHalfUp(corners.minOf { it.second }),
            DeskewGeometry.roundHalfUp(corners.maxOf { it.first }),
            DeskewGeometry.roundHalfUp(corners.maxOf { it.second }),
        )
        val box = OcrBox(aabb, w, h, deg)
        return RecognizedRegion(
            text = text,
            box = box,
            orientation = TextOrientation.HORIZONTAL,
            lines = listOf(RecognizedLine(text, box, TextOrientation.HORIZONTAL)),
            origin = RegionOrigin.LINE,
        )
    }

    /** A [count]-line stack at [deg]: line k offset k·[pitch] along the
     *  baseline's perpendicular from ([cx0],[cy0]). Reading order = k order. */
    private fun stack(
        texts: List<String>, deg: Float, cx0: Float, cy0: Float,
        w: Float = 200f, h: Float = 40f, pitch: Float = 50f,
    ): List<RecognizedRegion> {
        val r = Math.toRadians(deg.toDouble())
        val pdx = (-sin(r)).toFloat(); val pdy = cos(r).toFloat()
        return texts.mapIndexed { k, t ->
            slantedRegion(t, w, h, deg, cx0 + k * pitch * pdx, cy0 + k * pitch * pdy)
        }
    }

    private fun analyze(regions: List<RecognizedRegion>, strategy: GroupingStrategy? = null) =
        LayoutAnalyzer.analyze(
            regions, sourceLang = "en", screenshotWidthInRegionSpace = 0f, strategy = strategy,
        )

    @Test
    fun slantedStack_groupsAsOne_withUnscrambledText() {
        // Deliberately shuffled input order — reading order must come from the
        // deskewed geometry, not input order or screen-space banding.
        val lines = stack(listOf("first", "second", "third"), -12f, 640f, 300f)
        val groups = analyze(listOf(lines[2], lines[0], lines[1]))
        assertEquals(1, groups.size)
        val g = groups.single()
        assertEquals("first second third", g.text)
        assertEquals(-12f, g.angleDeg, 0f)
        // In-frame union ≈ 200 wide × (40 + 2·50) tall, ± rounding.
        assertEquals(200f, g.orientedWidth, 2f)
        assertEquals(140f, g.orientedHeight, 2f)
    }

    @Test
    fun groupLines_areTheOriginalInstances() {
        val lines = stack(listOf("alpha", "beta"), 20f, 500f, 400f)
        val inputLineInstances = lines.flatMap { it.lines }
        val groups = analyze(lines)
        assertEquals(1, groups.size)
        for (l in groups.single().lines) {
            assertTrue(
                "group line must be an ORIGINAL instance, not a deskewed copy",
                inputLineInstances.any { it === l },
            )
        }
    }

    @Test
    fun distantAngles_neverMerge() {
        val stackA = stack(listOf("one", "two"), -12f, 400f, 300f)
        val banner = slantedRegion("sale", 200f, 40f, 30f, 1200f, 700f)
        val groups = analyze(stackA + banner)
        assertEquals(2, groups.size)
        assertEquals(setOf(-12f, 30f), groups.map { it.angleDeg }.toSet())
    }

    @Test
    fun kernel_splitsFarApartSubStacks_bothKeepTheta() {
        // Two 2-line stacks at the same angle, far apart across the baseline:
        // one cluster, one frame, but the kernel separates them in-frame.
        val a = stack(listOf("top one", "top two"), -12f, 500f, 200f)
        val b = stack(listOf("bottom one", "bottom two"), -12f, 500f, 900f)
        val groups = analyze(a + b)
        assertEquals(2, groups.size)
        for (g in groups) assertEquals(-12f, g.angleDeg, 0f)
        assertEquals(
            setOf("top one top two", "bottom one bottom two"),
            groups.map { it.text }.toSet(),
        )
    }

    @Test
    fun framedBounds_honorTheRendererPremise() {
        val lines = stack(listOf("first", "second", "third"), -12f, 640f, 300f)
        val g = analyze(lines).single()
        // Center: the stack's middle line center (symmetric stack) ± rounding.
        val r = Math.toRadians(-12.0)
        val midX = 640f + 1 * 50f * (-sin(r)).toFloat()
        val midY = 300f + 1 * 50f * cos(r).toFloat()
        assertEquals(midX, g.bounds.exactCenterX(), 1.5f)
        assertEquals(midY, g.bounds.exactCenterY(), 1.5f)
        // Containment: every member's screen AABB sits inside the group AABB.
        for (line in lines) {
            val b = line.box.bounds
            assertTrue(
                "member $b outside group ${g.bounds}",
                b.left >= g.bounds.left - 2 && b.top >= g.bounds.top - 2 &&
                    b.right <= g.bounds.right + 2 && b.bottom <= g.bounds.bottom + 2,
            )
        }
    }

    @Test
    fun framedRun_receivesTheRealScreenWidth() {
        // Deskew is an isometry: in-frame extents are real px, so the framed
        // strategy run gets the SAME width the shell got — an angled-row menu
        // must be splittable exactly like an upright one.
        var seenWidth = -1f
        val recordingStrategy = object : GroupingStrategy {
            override val name = "recording-stub"
            override fun group(regions: List<RecognizedRegion>, ctx: GroupingContext): List<ProposedGroup> {
                seenWidth = ctx.screenshotWidthInRegionSpace
                return listOf(ProposedGroup(regions))
            }
        }
        val lines = stack(listOf("alpha", "beta"), 20f, 500f, 400f)
        LayoutAnalyzer.analyze(
            lines, sourceLang = "en", screenshotWidthInRegionSpace = 1920f, strategy = recordingStrategy,
        )
        assertEquals(1920f, seenWidth, 0f)
    }

    @Test
    fun framedPins_clampTheUnionInFrame() {
        // A framed strategy's pins are frame-space u-values; the assembly must
        // clamp the in-frame union to them before rotating back — the angled
        // analogue of a list row pinned to its column edges.
        val pinningStrategy = object : GroupingStrategy {
            override val name = "pinning-stub"
            override fun group(regions: List<RecognizedRegion>, ctx: GroupingContext): List<ProposedGroup> {
                val union = regions.map { it.box.bounds }.reduce { a, b -> Rect(a).apply { union(b) } }
                // Pin 60px wider than the natural union on each side.
                return listOf(ProposedGroup(regions, union.left - 60, union.right + 60))
            }
        }
        val lines = stack(listOf("alpha", "beta"), 20f, 500f, 400f)
        val unpinned = analyze(lines).single()
        val pinned = LayoutAnalyzer.analyze(
            lines, sourceLang = "en", screenshotWidthInRegionSpace = 0f, strategy = pinningStrategy,
        ).single()
        assertEquals(20f, pinned.angleDeg, 0f)
        assertEquals("pins widen the reading-axis extent by 120px", unpinned.orientedWidth + 120f, pinned.orientedWidth, 2f)
        assertEquals("cross-axis extent unchanged", unpinned.orientedHeight, pinned.orientedHeight, 2f)
    }

    @Test
    fun foreignStrategy_returningCopies_fallsBackToSingletons() {
        val copyingStrategy = object : GroupingStrategy {
            override val name = "copying-stub"
            override fun group(regions: List<RecognizedRegion>, ctx: GroupingContext): List<ProposedGroup> =
                listOf(ProposedGroup(regions.map { it.copy() }))
        }
        val lines = stack(listOf("alpha", "beta"), 20f, 500f, 400f)
        val groups = analyze(lines, strategy = copyingStrategy)
        // The cluster path can't swap unknown instances back → v1 singletons,
        // each carrying its own slant.
        assertEquals(2, groups.size)
        for (g in groups) {
            assertEquals(20f, g.angleDeg, 0f)
            assertEquals(1, g.lines.size)
        }
    }
}
