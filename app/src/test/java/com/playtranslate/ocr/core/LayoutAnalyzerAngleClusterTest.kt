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

    // ── The unified shell: unmeasured admission + uniform 0° clustering ──

    /** An UNMEASURED region ([OcrBox.angleUnmeasured] — producer withheld the
     *  angle): an upright AABB placed at the screen position of in-frame
     *  offset ([u],[v]) from ([cx],[cy]) in a [deg] frame. */
    private fun unmeasuredAt(
        text: String, deg: Float, cx: Float, cy: Float,
        u: Float, v: Float, w: Int, h: Int,
    ): RecognizedRegion {
        val r = Math.toRadians(deg.toDouble())
        val c = cos(r).toFloat()
        val s = sin(r).toFloat()
        val x = cx + u * c - v * s
        val y = cy + u * s + v * c
        val rect = Rect((x - w / 2).toInt(), (y - h / 2).toInt(), (x + w / 2).toInt(), (y + h / 2).toInt())
        val box = OcrBox.upright(rect).copy(angleUnmeasured = true)
        return RecognizedRegion(
            text = text,
            box = box,
            orientation = TextOrientation.HORIZONTAL,
            lines = listOf(RecognizedLine(text, box, TextOrientation.HORIZONTAL)),
            origin = RegionOrigin.LINE,
        )
    }

    private fun upright(text: String, r: Rect): RecognizedRegion {
        val box = OcrBox.upright(r)
        return RecognizedRegion(
            text = text,
            box = box,
            orientation = TextOrientation.HORIZONTAL,
            lines = listOf(RecognizedLine(text, box, TextOrientation.HORIZONTAL)),
            origin = RegionOrigin.LINE,
        )
    }

    @Test
    fun unmeasuredShortLine_rejoinsItsSlantedParagraph() {
        // The mixed-length failure the unified shell exists for: the
        // excursion gate systematically withholds SHORT lines' angles while
        // their neighbors carry theirs. The short third row of this stack is
        // admitted by position and confirmed by grouping with a measured
        // member — one paragraph, text in reading order, frame angle carried.
        val lines = stack(listOf("first line here", "second line here"), -12f, 640f, 300f)
        val shortLine = unmeasuredAt("end", -12f, 640f, 300f, u = -60f, v = 100f, w = 80, h = 40)
        val groups = analyze(lines + shortLine)
        assertEquals(1, groups.size)
        assertEquals("first line here second line here end", groups.single().text)
        assertEquals(-12f, groups.single().angleDeg, 0f)
    }

    @Test
    fun unmeasuredRegion_offTheSlantPath_groupsUpright() {
        // Admission is positional and confirmation is by grouping: a stray
        // unmeasured region far from the slanted stack must neither join it
        // nor render slanted — it falls to the upright pool.
        val lines = stack(listOf("first line", "second line"), -12f, 640f, 300f)
        val stray = unmeasuredAt("stray", -12f, 640f, 300f, u = 0f, v = 500f, w = 80, h = 40)
        val groups = analyze(lines + stray)
        assertEquals(2, groups.size)
        val strayGroup = groups.single { it.text == "stray" }
        assertEquals(0f, strayGroup.angleDeg, 0f)
    }

    @Test
    fun lightSlant_absorbedByALongerUprightMass_groupsUpright() {
        // Uniformity: 0° is just another angle. A 3.5° medium line within the
        // cap of a LONGER upright mass joins the 0-cluster (θ̄ = the longest
        // member's angle; its residual excursion sits under the split
        // tolerance) and groups upright — the same designed absorption any
        // 10°/13.5° pair gets, no special case at zero.
        val a = upright("one long upright line", Rect(100, 100, 1100, 140))
        val b = upright("two long upright line", Rect(100, 160, 1100, 200))
        // A left-aligned continuation row: 400px at 3.5° — long enough for
        // the producer to carry the angle (excursion ≈ 12px > 6), short
        // enough for the clusterer to absorb it (12px < 0.35·oh = 14).
        val light = slantedRegion("lightly leaning", 400f, 40f, 3.5f, 300f, 240f)
        val groups = analyze(listOf(a, b, light))
        assertEquals(1, groups.size)
        assertEquals(0f, groups.single().angleDeg, 0f)
        assertTrue(groups.single().text.contains("lightly leaning"))
    }

    @Test
    fun absorbedUprightMass_isNeverLost() {
        // The unified fence's region-loss regression (tilted-photo seed): a
        // LONGER 3.7° headline wins θ̄ over a shorter measured-0 mass whose
        // per-member excursion sits inside the absorption tolerance, so the
        // whole mass rides the slanted cluster's framed run. Absorbed
        // measured-0 members are MEASURED — their groups must be confirmed
        // and emitted, whatever the strategy's in-frame partition; counting
        // "measured" via isRotated silently discarded every all-absorbed
        // group, and the pool (which only recovers UNMEASURED regions) never
        // saw them again. Line count is conserved, full stop.
        val headline = slantedRegion("a very long tilted headline line", 900f, 40f, 3.7f, 600f, 100f)
        val mass = (0 until 6).map { k ->
            upright("row $k", Rect(100, 200 + k * 60, 500, 240 + k * 60))
        }
        val groups = analyze(listOf(headline) + mass)
        assertEquals(7, groups.sumOf { it.lines.size })
        assertTrue(groups.any { it.text.contains("headline") })
    }

    @Test
    fun letterlessAbsorbedRegion_fallsBackToItsNeighbors_neverDrops() {
        // The FF-VI numeric-row regression: a SHORT letterless row ("185/615")
        // absorbs into a slant cluster while its letter-bearing neighbor row
        // exiles to the pool. The framed strategy's source filter drops the
        // letterless-only group — the region must fall back to the pool and
        // group with its neighbor there, exactly as the pre-partition
        // pipeline would have, never silently vanish.
        val headline = slantedRegion("a very long tilted headline line", 900f, 40f, 3.7f, 600f, 100f)
        val label = upright("HP", Rect(100, 300, 180, 340))
        val numbers = upright("185/615", Rect(200, 300, 380, 340))
        val groups = analyze(listOf(headline, label, numbers))
        assertEquals(3, groups.sumOf { it.lines.size })
        assertTrue(groups.any { it.text.contains("185/615") })
    }

    @Test
    fun letterlessSlantedLine_survivesThroughItsLetterNeighbors() {
        // The FF-VI stitched-garble regression: a letterless SLANTED line
        // (numeric/CJK-garble misread) must meet the source filter at GROUP
        // level like upright garble always has — falling back to the pool
        // and living through its letter-bearing neighbor — never being
        // pre-dropped for the crime of carrying an angle.
        // The shell's obligation is DELIVERY: the letterless slanted line
        // must reach the pool run alongside its neighbors (not be pre-dropped
        // for carrying an angle). Whether it then merges is the strategy's
        // text-aware business — so pin the delivery with a group-everything
        // stub; production strategies demonstrably keep such rows via their
        // letter neighbors (the base FF-VI runs).
        val groupAll = object : GroupingStrategy {
            override val name = "group-all"
            override fun group(
                regions: List<RecognizedRegion>,
                ctx: GroupingContext,
            ): List<ProposedGroup> = listOf(ProposedGroup(regions))
        }
        val a = upright("one long upright line", Rect(100, 100, 1100, 140))
        val b = upright("two long upright line", Rect(100, 160, 1100, 200))
        val garble = slantedRegion("149 88", 400f, 40f, 3.5f, 300f, 240f)
        val groups = analyze(listOf(a, b, garble), strategy = groupAll)
        assertEquals(3, groups.sumOf { it.lines.size })
        assertTrue(groups.single().text.contains("149 88"))
    }

    @Test
    fun unmeasuredRegion_rejectedByOneCluster_confirmedByAnother_emitsExactlyOnce() {
        // The exactly-once invariant across clusters (outside-review finding):
        // an unmeasured region admitted to cluster A, rejected there
        // (unconfirmed), then admitted and CONFIRMED by cluster B must emit
        // only in B's group — the early fallback must not also route it
        // through the upright pool. The picky strategy rejects any run
        // without M2, so A rejects the candidate and B accepts it.
        val picky = object : GroupingStrategy {
            override val name = "picky"
            override fun group(
                regions: List<RecognizedRegion>,
                ctx: GroupingContext,
            ): List<ProposedGroup> =
                if (regions.any { it.text == "M2" }) listOf(ProposedGroup(regions))
                else regions.map { ProposedGroup(listOf(it)) }
        }
        val m1 = slantedRegion("M1", 260f, 40f, 5f, 300f, 300f)
        val m2 = slantedRegion("M2", 260f, 40f, 13f, 700f, 390f)
        val uBox = OcrBox.upright(Rect(460, 290, 540, 330)).copy(angleUnmeasured = true)
        val u = RecognizedRegion(
            text = "U", box = uBox, orientation = TextOrientation.HORIZONTAL,
            lines = listOf(RecognizedLine("U", uBox, TextOrientation.HORIZONTAL)),
            origin = RegionOrigin.LINE,
        )
        // Preconditions: U is admissible to BOTH frames (else the scenario is
        // vacuous and the test must be rebuilt, not skipped).
        val frameA = AngleFrame(5f, 300, 300)
        val frameB = AngleFrame(13f, 700, 390)
        assertTrue(DeskewGeometry.admitUnmeasured(uBox, frameA, listOf(m1.box)))
        assertTrue(DeskewGeometry.admitUnmeasured(uBox, frameB, listOf(m2.box)))

        val groups = analyze(listOf(m1, m2, u), strategy = picky)
        val uCount = groups.sumOf { g -> g.lines.count { it.text == "U" } }
        assertEquals("U must emit exactly once across all groups", 1, uCount)
        assertEquals(3, groups.sumOf { it.lines.size })
    }
}
