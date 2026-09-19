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

    @Test fun `minPanelHeight is 20 percent of the screen`() {
        assertEquals(400, CaptureResultGeometry.minPanelHeight(2000))
    }

    // ─── autoPanelHeight ────────────────────────────────────────────────

    private val defaultCap = CaptureResultGeometry.autoMaxHeight(2000) // 50% = 1000

    @Test fun `autoPanelHeight fits content that's under the cap`() {
        // content 600 within [20%, 50%] of 2000 = [400, 1000].
        assertEquals(600, CaptureResultGeometry.autoPanelHeight(600, 2000, defaultCap))
    }

    @Test fun `autoPanelHeight caps at the default 50 percent`() {
        assertEquals(1000, CaptureResultGeometry.autoPanelHeight(1800, 2000, defaultCap))
    }

    @Test fun `autoPanelHeight floors at 20 percent`() {
        assertEquals(400, CaptureResultGeometry.autoPanelHeight(100, 2000, defaultCap))
    }

    @Test fun `autoPanelHeight caps at the user's dragged height when it exceeds 50 percent`() {
        // User dragged to 1300 (65%); a re-fit of 1800 content stays at 1300, not 1000.
        assertEquals(1300, CaptureResultGeometry.autoPanelHeight(1800, 2000, 1300))
        // ...but still shrinks to the content when it needs less than the user height.
        assertEquals(1200, CaptureResultGeometry.autoPanelHeight(1200, 2000, 1300))
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

    // ─── postureCeiling / postureFor ────────────────────────────────────

    @Test fun `no posture yet auto-sizes to the default 50 percent`() {
        assertEquals(
            1000,
            CaptureResultGeometry.postureCeiling(CaptureResultGeometry.NO_POSTURE, 2000),
        )
    }

    @Test fun `the collapsed sliver carries no reading height of its own`() {
        assertTrue(
            CaptureResultGeometry.isCollapsedPosture(CaptureResultGeometry.COLLAPSED_POSTURE),
        )
        assertFalse(CaptureResultGeometry.isCollapsedPosture(0.3f))
        // Collapsed → the default ceiling once the user pulls it back up.
        assertEquals(
            1000,
            CaptureResultGeometry.postureCeiling(CaptureResultGeometry.COLLAPSED_POSTURE, 2000),
        )
    }

    @Test fun `a remembered height becomes the ceiling`() {
        assertEquals(600, CaptureResultGeometry.postureCeiling(0.3f, 2000))
        // ...and it CAPS, never forces: content needing less still wins.
        assertEquals(450, CaptureResultGeometry.autoPanelHeight(450, 2000, 600))
        // Above it, the sheet stops at the remembered height.
        assertEquals(600, CaptureResultGeometry.autoPanelHeight(1500, 2000, 600))
    }

    @Test fun `a remembered height is clamped to the resize range`() {
        assertEquals(400, CaptureResultGeometry.postureCeiling(0.05f, 2000)) // floor 20%
        assertEquals(1800, CaptureResultGeometry.postureCeiling(0.99f, 2000)) // ceiling 90%
    }

    @Test fun `postureFor records the ceiling as a fraction, or the sliver`() {
        assertEquals(0.35f, CaptureResultGeometry.postureFor(700, 2000, collapsed = false), 1e-4f)
        assertEquals(
            CaptureResultGeometry.COLLAPSED_POSTURE,
            CaptureResultGeometry.postureFor(700, 2000, collapsed = true),
            0f,
        )
    }

    @Test fun `a recorded posture round-trips back to the same ceiling`() {
        val recorded = CaptureResultGeometry.postureFor(700, 2000, collapsed = false)
        assertEquals(700, CaptureResultGeometry.postureCeiling(recorded, 2000))
        // A different display keeps the proportion, not the pixels.
        assertEquals(350, CaptureResultGeometry.postureCeiling(recorded, 1000))
    }

    @Test fun `every remembered height survives its own round trip`() {
        // px → fraction → px runs once per open/dismiss cycle, so the map has to
        // be a fixed point at EVERY height, not just round ones: a version that
        // truncated the product lost a pixel per cycle at ~1 height in 8 (1080px
        // displays lose 224, 313, 350...) and walked the panel down over a
        // session's worth of captures.
        for (screenH in listOf(1080, 1920, 2340, 2400, 3120)) {
            for (ceiling in CaptureResultGeometry.minPanelHeight(screenH)..
                CaptureResultGeometry.maxPanelHeight(screenH)) {
                val recorded = CaptureResultGeometry.postureFor(ceiling, screenH, collapsed = false)
                assertEquals(
                    "$ceiling px of $screenH did not round-trip",
                    ceiling,
                    CaptureResultGeometry.postureCeiling(recorded, screenH),
                )
            }
        }
    }

    // ─── shouldDismissFromDrag ──────────────────────────────────────────
    // Inputs are AWAY-positive: how far the sheet was pushed toward the edge it
    // exits by, and how fast it was moving there.

    @Test fun `dismiss when pushed past the distance threshold`() {
        assertTrue(CaptureResultGeometry.shouldDismissFromDrag(200f, 0f, 150f, 60f, 1600f))
    }

    @Test fun `a fling dismisses only once it has carried some travel`() {
        assertTrue(CaptureResultGeometry.shouldDismissFromDrag(80f, 2000f, 150f, 60f, 1600f))
        // Speed with nothing behind it is a quick minimize, not a throw-away.
        assertFalse(CaptureResultGeometry.shouldDismissFromDrag(10f, 4000f, 150f, 60f, 1600f))
    }

    @Test fun `no dismiss below both thresholds`() {
        assertFalse(CaptureResultGeometry.shouldDismissFromDrag(80f, 900f, 150f, 60f, 1600f))
    }

    @Test fun `a sheet that never left its resting place never dismisses`() {
        assertFalse(CaptureResultGeometry.shouldDismissFromDrag(0f, 9000f, 150f, 60f, 1600f))
        assertFalse(CaptureResultGeometry.shouldDismissFromDrag(-120f, -800f, 150f, 60f, 1600f))
    }

    // ─── placeParkedHint ────────────────────────────────────────────────
    // A 1000px strip; the full row is 200px, the glyph 30px, 20px of
    // clearance beside a box unless a case says otherwise.

    private fun place(
        vararg covered: IntRange,
        screen: Int = 1000,
        row: Int = 200,
        glyph: Int = 30,
        edgePad: Int = 0,
        clearance: Int = 20,
    ) = CaptureResultGeometry.placeParkedHint(covered.toList(), screen, row, glyph, edgePad, clearance)

    @Test fun `placeParkedHint centres the full row when nothing covers the strip`() {
        assertEquals(CaptureResultGeometry.ParkedHintPlacement(500, true), place())
    }

    @Test fun `placeParkedHint leaves the row centred while the boxes stay clear of it`() {
        // The centred row plus clearance spans [380, 620): boxes outside it,
        // however much of the strip they take, never move the row — even
        // one ending exactly at the clearance line.
        assertEquals(CaptureResultGeometry.ParkedHintPlacement(500, true), place(0 until 300, 700 until 1000))
        assertEquals(CaptureResultGeometry.ParkedHintPlacement(500, true), place(620 until 1000))
        assertEquals(CaptureResultGeometry.ParkedHintPlacement(500, true), place(0 until 380))
    }

    @Test fun `placeParkedHint moves the row once a box reaches into its clearance`() {
        // [610, 700) overlaps the clearance band: the row leaves for the
        // widest clear stretch, [0, 610), centred at 305.
        assertEquals(CaptureResultGeometry.ParkedHintPlacement(305, true), place(610 until 700))
    }

    @Test fun `placeParkedHint centres the row in the widest uncovered stretch`() {
        // [0, 250) is 250 wide, [700, 1000) is 300: the row (200 + 2 × 20)
        // seats in the wider one, centred at 850.
        assertEquals(CaptureResultGeometry.ParkedHintPlacement(850, true), place(250 until 700))
    }

    @Test fun `placeParkedHint drops to the glyph when the widest stretch cannot seat the text`() {
        // 100px either side: the 240px the text needs fits nowhere, so the
        // glyph alone sits at the centre of the first of the two equal
        // stretches (both 100 wide, both 450 from the centre).
        assertEquals(CaptureResultGeometry.ParkedHintPlacement(50, false), place(100 until 900))
    }

    @Test fun `placeParkedHint keeps the glyph at the screen centre when the strip is covered end to end`() {
        assertEquals(CaptureResultGeometry.ParkedHintPlacement(500, false), place(0 until 1000))
        // Two boxes that between them cover everything read the same way.
        assertEquals(CaptureResultGeometry.ParkedHintPlacement(500, false), place(400 until 1000, 0 until 500))
    }

    @Test fun `placeParkedHint merges overlapping boxes before looking for a stretch`() {
        // Three boxes chain into [100, 800); the 200px tail seats a 150px row.
        assertEquals(
            CaptureResultGeometry.ParkedHintPlacement(900, true),
            place(550 until 800, 100 until 400, 300 until 600, row = 150),
        )
    }

    @Test fun `placeParkedHint breaks a tie by the stretch nearest the screen centre`() {
        // [200, 500) and [600, 900) are both 300 wide; the first's centre
        // (350) is nearer 500 than the second's (750).
        assertEquals(
            CaptureResultGeometry.ParkedHintPlacement(350, true),
            place(0 until 200, 500 until 600, 900 until 1000),
        )
    }

    @Test fun `placeParkedHint keeps the shown row inside the edge pad`() {
        // Usable [20, 980); the box leaves [960, 980), a 20px sliver whose
        // centre (970) would hang a 100px glyph past the pad: clamped to 930.
        assertEquals(
            CaptureResultGeometry.ParkedHintPlacement(930, false),
            place(0 until 960, glyph = 100, edgePad = 20),
        )
        // A box entirely outside the usable width covers nothing — and one
        // that reaches the row's area is measured against the usable width
        // when the row moves: the clear stretch is [400, 980), centred at 690.
        assertEquals(
            CaptureResultGeometry.ParkedHintPlacement(500, true),
            place(0 until 10, 990 until 1000, edgePad = 20),
        )
        assertEquals(
            CaptureResultGeometry.ParkedHintPlacement(690, true),
            place(0 until 400, edgePad = 20),
        )
    }

    @Test fun `placeParkedHint on a screen narrower than the shown row falls back to the centre`() {
        assertEquals(
            CaptureResultGeometry.ParkedHintPlacement(50, false),
            place(0 until 40, screen = 100, glyph = 120),
        )
    }
}
