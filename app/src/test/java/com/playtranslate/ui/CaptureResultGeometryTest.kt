package com.playtranslate.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [CaptureResultGeometry] — the pure panel geometry. No Android,
 * no Robolectric. Pins the height clamp, the side-by-side breakpoint, and the
 * fling-dismiss decision so a tweak that would misplace or mis-dismiss the
 * over-game panel fails here first.
 */
class CaptureResultGeometryTest {

    // ─── clampPanelHeight ───────────────────────────────────────────────

    @Test fun `clampPanelHeight keeps a mid-range height untouched`() {
        // 40% of 2000 = 800, within [20%, 90%] = [400, 1800].
        assertEquals(800, CaptureResultGeometry.clampPanelHeight(800, 2000))
    }

    @Test fun `clampPanelHeight floors at the min fraction`() {
        assertEquals(400, CaptureResultGeometry.clampPanelHeight(100, 2000))
    }

    @Test fun `clampPanelHeight caps at the max fraction`() {
        assertEquals(1800, CaptureResultGeometry.clampPanelHeight(5000, 2000))
    }

    @Test fun `defaultPanelHeight is 40 percent of the screen`() {
        assertEquals(800, CaptureResultGeometry.defaultPanelHeight(2000))
    }

    // ─── autoPanelHeight ────────────────────────────────────────────────

    @Test fun `autoPanelHeight fits content that's under the cap`() {
        // content 600 within [20%, 50%] of 2000 = [400, 1000].
        assertEquals(600, CaptureResultGeometry.autoPanelHeight(600, 2000))
    }

    @Test fun `autoPanelHeight caps at 50 percent`() {
        assertEquals(1000, CaptureResultGeometry.autoPanelHeight(1800, 2000))
    }

    @Test fun `autoPanelHeight floors at 20 percent`() {
        assertEquals(400, CaptureResultGeometry.autoPanelHeight(100, 2000))
    }

    // ─── shouldUseSideBySide ────────────────────────────────────────────

    @Test fun `side-by-side when each column clears the per-section min`() {
        // (1200 - 4)/2 = 598 >= 300.
        assertTrue(CaptureResultGeometry.shouldUseSideBySide(1200, 4, 300))
    }

    @Test fun `stacked when a column would be below the per-section min`() {
        // (560 - 4)/2 = 278 < 300.
        assertFalse(CaptureResultGeometry.shouldUseSideBySide(560, 4, 300))
    }

    @Test fun `side-by-side boundary is inclusive`() {
        // (604 - 4)/2 = 300 == 300.
        assertTrue(CaptureResultGeometry.shouldUseSideBySide(604, 4, 300))
        // two px narrower → 299 < 300.
        assertFalse(CaptureResultGeometry.shouldUseSideBySide(602, 4, 300))
    }

    @Test fun `non-positive width never goes side-by-side`() {
        assertFalse(CaptureResultGeometry.shouldUseSideBySide(0, 4, 300))
    }

    // ─── shouldDismissFromDrag ──────────────────────────────────────────

    @Test fun `dismiss when dragged up past the distance threshold`() {
        assertTrue(CaptureResultGeometry.shouldDismissFromDrag(-200f, 0f, 150f, 1000f))
    }

    @Test fun `dismiss on a fast up-fling even with little distance`() {
        assertTrue(CaptureResultGeometry.shouldDismissFromDrag(-10f, -1500f, 150f, 1000f))
    }

    @Test fun `no dismiss below both thresholds`() {
        assertFalse(CaptureResultGeometry.shouldDismissFromDrag(-50f, -200f, 150f, 1000f))
    }

    @Test fun `downward drag never dismisses`() {
        assertFalse(CaptureResultGeometry.shouldDismissFromDrag(120f, 800f, 150f, 1000f))
    }
}
