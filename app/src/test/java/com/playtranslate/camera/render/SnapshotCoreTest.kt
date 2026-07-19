package com.playtranslate.camera.render

import android.graphics.Rect
import com.playtranslate.OcrManager
import com.playtranslate.language.OcrBackend
import com.playtranslate.language.TextOrientation
import com.playtranslate.ocr.core.DetectedRegion
import com.playtranslate.ocr.core.OcrBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the shared snapshot-pipeline semantics ([SnapshotCore]) both the
 * camera tool and the import tool run on: the confidence/edge gates, the
 * group-level region filter, the pre-recognition region projection, and the
 * panel-text alignment rule.
 */
@RunWith(RobolectricTestRunner::class)
class SnapshotCoreTest {

    private fun line(conf: Float, bounds: Rect = Rect(100, 100, 300, 140)) =
        OcrManager.LineBox(text = "line", bounds = bounds, groupIndex = 0, confidence = conf)

    private fun group(
        text: String = "text",
        bounds: Rect = Rect(100, 100, 300, 160),
        confs: List<Float> = listOf(-1f),
        orientation: TextOrientation = TextOrientation.HORIZONTAL,
    ) = OcrManager.OcrGroup(
        text = text,
        bounds = bounds,
        orientation = orientation,
        lines = confs.map { line(it) },
    )

    private fun result(
        groups: List<OcrManager.OcrGroup>,
        backend: OcrBackend? = OcrBackend.Meiki("meiki-ja"),
    ) = OcrManager.OcrResult(
        fullText = groups.joinToString("\n\n") { it.text },
        segments = emptyList(),
        groups = groups,
        engineBackend = backend,
    )

    // ── usableGroups ────────────────────────────────────────────────────

    @Test
    fun confidenceGateDropsLowKeepsUnknown() {
        val ocr = result(
            listOf(
                group(text = "garbage", confs = listOf(0.2f)),
                group(text = "unknown", confs = listOf(-1f)),
                group(text = "good", confs = listOf(0.9f)),
            ),
        )
        val kept = SnapshotCore.usableGroups(ocr, 1000, 1000, translating = true)
        assertEquals(listOf("unknown", "good"), kept.map { it.text })
    }

    @Test
    fun confidenceThresholdIsPerEngineFamily() {
        val borderline = listOf(group(text = "borderline", confs = listOf(0.55f)))
        // 0.55 passes the default 0.5 threshold...
        assertEquals(
            1,
            SnapshotCore.usableGroups(
                result(borderline), 1000, 1000, translating = false,
            ).size,
        )
        // ...but fails ML Kit's 0.6.
        assertEquals(
            0,
            SnapshotCore.usableGroups(
                result(borderline, backend = OcrBackend.MLKitLatin), 1000, 1000, translating = false,
            ).size,
        )
    }

    @Test
    fun edgeGateAppliesOnlyWhenTranslatingAndNotSkipped() {
        val clipped = group(text = "clipped", bounds = Rect(0, 100, 200, 160))
        val ocr = result(listOf(clipped))
        assertEquals(0, SnapshotCore.usableGroups(ocr, 1000, 1000, translating = true).size)
        // Same-language reading keeps clipped lines (honest output).
        assertEquals(1, SnapshotCore.usableGroups(ocr, 1000, 1000, translating = false).size)
        // Snapshots read the whole frame, clipped lines included.
        assertEquals(
            1,
            SnapshotCore.usableGroups(ocr, 1000, 1000, translating = true, skipEdgeGate = true).size,
        )
    }

    @Test
    fun verticalGroupsGateOnTheirReadingAxis() {
        // Vertical text clipped top/bottom is gated; the same bounds clipped
        // only horizontally are not.
        val topClipped = group(
            text = "vertical",
            bounds = Rect(400, 0, 460, 500),
            orientation = TextOrientation.VERTICAL,
        )
        assertEquals(
            0,
            SnapshotCore.usableGroups(result(listOf(topClipped)), 1000, 1000, translating = true).size,
        )
        val horizontalSameBounds = group(text = "horizontal", bounds = Rect(400, 0, 460, 500))
        assertEquals(
            1,
            SnapshotCore.usableGroups(
                result(listOf(horizontalSameBounds)), 1000, 1000, translating = true,
            ).size,
        )
    }

    @Test
    fun blankGroupsAlwaysDrop() {
        val ocr = result(listOf(group(text = "  "), group(text = "kept")))
        assertEquals(
            listOf("kept"),
            SnapshotCore.usableGroups(ocr, 1000, 1000, translating = false).map { it.text },
        )
    }

    // ── regionCenterFilter ──────────────────────────────────────────────

    @Test
    fun regionCenterFilterKeepsCenterInsideGroups() {
        val inside = group(text = "inside", bounds = Rect(100, 100, 200, 160))
        val straddling = group(text = "straddling", bounds = Rect(180, 100, 420, 160))
        val outside = group(text = "outside", bounds = Rect(500, 500, 700, 560))
        val region = Rect(50, 50, 320, 300)
        val kept = SnapshotCore.regionCenterFilter(listOf(inside, straddling, outside), region)
        // Center-inside keeps whole paragraphs: "straddling"'s center (300)
        // is inside even though its right edge is not.
        assertEquals(listOf("inside", "straddling"), kept.map { it.text })
        // Null region = whole frame.
        assertEquals(
            3,
            SnapshotCore.regionCenterFilter(listOf(inside, straddling, outside), null).size,
        )
    }

    // ── regionPreFilter (pre-recognition, processed-image space) ────────

    private fun detected(bounds: Rect) = DetectedRegion(
        box = OcrBox(
            bounds = bounds,
            orientedWidth = bounds.width().toFloat(),
            orientedHeight = bounds.height().toFloat(),
        ),
    )

    @Test
    fun preFilterProjectsTheRegionIntoTheProcessedSpace() {
        // Region drawn in a 1000x1000 AU frame; the filter runs on a 2x
        // processed image. A detection at (500,500) in processed space sits
        // at (250,250) in AU space — inside the AU-space region — and must
        // be kept; comparing spaces raw would have dropped it.
        val filter = SnapshotCore.regionPreFilter(
            dropEdgeClipped = false,
            clipTo = Rect(200, 200, 300, 300),
            clipFrameW = 1000,
            clipFrameH = 1000,
        )
        val inRegion = detected(Rect(480, 480, 520, 520))
        val outRegion = detected(Rect(100, 100, 140, 140))
        val kept = filter.filter(listOf(inRegion, outRegion), 2000, 2000)
        assertEquals(listOf(inRegion), kept)
    }

    @Test
    fun preFilterOrdersCenterOut() {
        val filter = SnapshotCore.regionPreFilter(dropEdgeClipped = false)
        val center = detected(Rect(480, 480, 520, 520))
        val corner = detected(Rect(0, 0, 60, 40))
        val mid = detected(Rect(300, 300, 380, 340))
        val ordered = filter.filter(listOf(corner, mid, center), 1000, 1000)
        assertEquals(listOf(center, mid, corner), ordered)
    }

    @Test
    fun preFilterEdgeGateDropsEdgeClippedDetections() {
        val filter = SnapshotCore.regionPreFilter(dropEdgeClipped = true)
        val clipped = detected(Rect(0, 400, 200, 440))
        val clean = detected(Rect(400, 400, 600, 440))
        assertEquals(listOf(clean), filter.filter(listOf(clipped, clean), 1000, 1000))
        // Snapshot form (no edge gate) keeps both.
        val noGate = SnapshotCore.regionPreFilter(dropEdgeClipped = false)
        assertEquals(2, noGate.filter(listOf(clipped, clean), 1000, 1000).size)
    }

    // ── panelTextFor ────────────────────────────────────────────────────

    @Test
    fun panelTextUsesRecognizerOutputWhenNothingWasGated() {
        val groups = listOf(group(text = "a"), group(text = "b"))
        val ocr = result(groups).copy(fullText = "recognizer's own text")
        val (text, _) = SnapshotCore.panelTextFor(ocr, groups)
        assertEquals("recognizer's own text", text)
    }

    @Test
    fun panelTextRebuildsFromGatedGroupsOnAnyDrop() {
        val kept = group(text = "kept paragraph")
        val ocr = result(listOf(kept, group(text = "dropped"))).copy(fullText = "both paragraphs")
        val (text, segments) = SnapshotCore.panelTextFor(ocr, listOf(kept))
        assertEquals("kept paragraph", text)
        assertTrue(segments.isNotEmpty())
    }
}
