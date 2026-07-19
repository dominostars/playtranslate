package com.playtranslate.camera

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the pure zoom/pan math both frozen review surfaces run on
 * ([ReviewZoom]): the per-tool ceiling policy, focal-stable scaling, the
 * cover/center pan clamp, and the identity-at-fit invariant every transform
 * consumer's regression story depends on.
 */
class ReviewZoomTest {

    /** Portrait 1080x2340 screen. */
    private fun importZoom(auW: Int, auH: Int) =
        ReviewZoom(ReviewZoom.CeilingPolicy.IMPORT).apply { configure(auW, auH, 1080, 2340) }

    private fun cameraZoom(auW: Int, auH: Int) =
        ReviewZoom(ReviewZoom.CeilingPolicy.CAMERA).apply { configure(auW, auH, 1080, 2340) }

    // ── Ceiling policy ──────────────────────────────────────────────────

    @Test fun importCeilingReachesNativeForDownscaledContent() {
        // A4-ish document page: FIT scale = 1080/2000 = 0.54 → ceiling 1/0.54.
        val z = importZoom(2000, 2800)
        assertEquals(2000f / 1080f, z.ceiling, 1e-3f)
        assertTrue(z.zoomEnabled)
    }

    @Test fun importCeilingCollapsesForAtNativeContent() {
        // Portrait image at exactly screen size: fit scale 1 → native ceiling 1.
        val atNative = importZoom(1080, 2340)
        assertEquals(1f, atNative.ceiling)
        assertFalse(atNative.zoomEnabled)
        // Slightly-downscaled content under the collapse threshold also
        // disables (a 1.1x range would only flip gesture modes).
        val nearNative = importZoom(1188, 2574) // fit scale ~0.909 → native ~1.10
        assertEquals(1f, nearNative.ceiling)
        assertFalse(nearNative.zoomEnabled)
    }

    @Test fun cameraCeilingIsFlooredAtPhotoMin() {
        // Camera frame displays at/above native under FILL (cover) — the
        // native ceiling would be <= 1; the photographic floor applies.
        val z = cameraZoom(1080, 1920)
        assertEquals(ReviewZoom.PHOTO_MIN, z.ceiling)
        assertTrue(z.zoomEnabled)
    }

    @Test fun degenerateDimensionsDisableZoom() {
        val z = ReviewZoom(ReviewZoom.CeilingPolicy.IMPORT).apply { configure(0, 0, 1080, 2340) }
        assertFalse(z.zoomEnabled)
        assertTrue(z.isAtFit)
    }

    // ── Focal-stable scaling ────────────────────────────────────────────

    @Test fun contentUnderTheFocalPointStaysUnderItPerAxis() {
        // Focal stability is PER-AXIS: it holds on an axis in the cover
        // branch (content overflows the viewport there) and yields to
        // centering on a still-letterboxed axis — you cannot pan an axis
        // narrower than the viewport.
        val z = importZoom(2000, 2800) // fit 0.54: content 1080x1512 in 1080x2340
        val fx = 700f
        val fy = 900f
        z.scaleBy(1.5f, fx, fy)
        // X covers at 1.5x (1620 > 1080): focal-stable.
        assertEquals(fx, z.zoom * fx + z.panX, 0.5f)
        // Y still letterboxed at 1.5x (2268 < 2340): centered, not focal.
        val e = fitEdges(2000, 2800, 1080f, 2340f)
        assertEquals(2340f / 2f - z.zoom * (e[1] + e[3]) / 2f, z.panY, 0.5f)
        // Once BOTH axes cover (2.0x: 3024 > 2340), a further scale is
        // focal-stable on both.
        z.scaleBy(2.0f / 1.5f, fx, fy)
        val px = (fx - z.panX) / z.zoom
        val py = (fy - z.panY) / z.zoom
        z.scaleBy(1.2f, fx, fy)
        assertEquals(fx, z.zoom * px + z.panX, 0.5f)
        assertEquals(fy, z.zoom * py + z.panY, 0.5f)
    }

    @Test fun zoomIsBoundedByTheCeiling() {
        val z = importZoom(2000, 2800)
        z.scaleBy(100f, 540f, 1170f)
        assertEquals(z.ceiling, z.zoom, 1e-4f)
        z.scaleBy(0.0001f, 540f, 1170f)
        assertEquals(1f, z.zoom)
    }

    @Test fun scalingBackToFitSnapsToExactIdentity() {
        val z = importZoom(2000, 2800)
        z.scaleBy(1.7f, 200f, 300f)
        z.panBy(80f, -40f)
        z.scaleBy(0.1f, 900f, 2000f) // collapses to fit
        assertTrue(z.isAtFit)
        assertEquals(1f, z.zoom)
        assertEquals(0f, z.panX)
        assertEquals(0f, z.panY)
        val h = z.toHomographyRow()
        assertTrue(h.contentEquals(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)))
    }

    // ── Pan clamp ───────────────────────────────────────────────────────

    /** Fit-space content edges for the letterboxed import case. */
    private fun fitEdges(auW: Int, auH: Int, viewW: Float, viewH: Float): FloatArray {
        val s = minOf(viewW / auW, viewH / auH)
        val left = (viewW - auW * s) / 2f
        val top = (viewH - auH * s) / 2f
        return floatArrayOf(left, top, left + auW * s, top + auH * s)
    }

    @Test fun fitModeKeepsNarrowAxisCenteredWhileWideAxisPans() {
        // Wide landscape image on a portrait screen: at modest zoom the
        // x-axis overflows (pannable) while the y-axis is still narrower
        // than the viewport (stays centered).
        val z = importZoom(4000, 1500) // fit scale 0.27, content 1080x405
        z.scaleBy(2f, 540f, 1170f)
        val e = fitEdges(4000, 1500, 1080f, 2340f)
        // Try to drag far in both axes.
        z.panBy(-10_000f, -10_000f)
        // X (cover branch): right edge may not pull inside the viewport.
        assertEquals(1080f - z.zoom * e[2], z.panX, 0.5f)
        // Y (center branch): the vertical band stays centered regardless.
        assertEquals(2340f / 2f - z.zoom * (e[1] + e[3]) / 2f, z.panY, 0.5f)
        z.panBy(10_000f, 10_000f)
        // X clamped at the other side: left edge at the viewport edge.
        assertEquals(-z.zoom * e[0], z.panX, 0.5f)
    }

    @Test fun fillModeNeverExposesAGapAndAllowsReachingCroppedEdges() {
        // Camera FILL: 16:9 frame cover-cropped on the tall screen — content
        // overflows horizontally at rest; zoomed, panning may reveal the
        // cropped edges but never a gap past them.
        val z = cameraZoom(1080, 1920)
        z.scaleBy(1.5f, 540f, 1170f)
        val s = maxOf(1080f / 1080f, 2340f / 1920f) // cover scale
        val left = (1080f - 1080f * s) / 2f
        val top = (2340f - 1920f * s) / 2f
        val right = left + 1080f * s
        val bottom = top + 1920f * s
        z.panBy(100_000f, 100_000f)
        // Content's left/top edge lands exactly at the viewport edge, not inside.
        assertEquals(-z.zoom * left, z.panX, 0.5f)
        assertEquals(-z.zoom * top, z.panY, 0.5f)
        z.panBy(-200_000f, -200_000f)
        assertEquals(1080f - z.zoom * right, z.panX, 0.5f)
        assertEquals(2340f - z.zoom * bottom, z.panY, 0.5f)
    }

    @Test fun panAtFitIsIgnored() {
        val z = importZoom(2000, 2800)
        z.panBy(500f, 500f)
        assertTrue(z.isAtFit)
        assertEquals(0f, z.panX)
        assertEquals(0f, z.panY)
    }

    @Test fun configureResetsState() {
        val z = importZoom(2000, 2800)
        z.scaleBy(1.8f, 100f, 100f)
        assertFalse(z.isAtFit)
        z.configure(2000, 2800, 1080, 2340)
        assertTrue(z.isAtFit)
        assertTrue(abs(z.panX) < 1e-6 && abs(z.panY) < 1e-6)
    }
}
