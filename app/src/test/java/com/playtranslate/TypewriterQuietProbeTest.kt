package com.playtranslate

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** [TypewriterQuietProbe] — streak accounting, apron-miss detection, and
 *  the gate-side snapshot geometry it consumes. Telemetry only: these
 *  tests pin the measurement's soundness, since a wrong probe would
 *  green-light (or falsely kill) the parked accelerator. */
@RunWith(RobolectricTestRunner::class)
class TypewriterQuietProbeTest {

    private fun frame(): Bitmap =
        Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLACK)
        }

    private fun hold(id: Int, rect: Rect, grew: Boolean = false, released: Boolean = false) =
        TypewriterGate.QuietHoldProbe(id, rect, grew, released)

    @Test
    fun quietFrames_accumulateStreak_changeResets() {
        val probe = TypewriterQuietProbe()
        val bmp = frame()
        val r = Rect(10, 10, 200, 100)
        probe.sample(bmp, listOf(hold(1, r)), 0)      // baseline
        probe.sample(bmp, listOf(hold(1, r)), 500)    // quiet
        probe.sample(bmp, listOf(hold(1, r)), 1000)   // quiet
        assertEquals(2, probe.streakOf(1))
        // A glyph-sized change inside the rect (on the sampled grid:
        // even row, column ≡ left mod 8) resets the streak.
        bmp.setPixel(10 + 16, 10 + 4, Color.WHITE)
        probe.sample(bmp, listOf(hold(1, r)), 1500)
        assertEquals(0, probe.streakOf(1))
    }

    @Test
    fun growthWhileApronQuiet_flagsApronMiss() {
        val probe = TypewriterQuietProbe()
        val bmp = frame()
        val r = Rect(10, 10, 200, 100)
        probe.sample(bmp, listOf(hold(1, r)), 0)
        probe.sample(bmp, listOf(hold(1, r)), 500) // valid comparison exists
        // Text "grew" but nothing inside the sampled apron changed — the
        // glyphs landed outside it: the exact under-coverage the real
        // accelerator must never have.
        probe.sample(bmp, listOf(hold(1, r, grew = true)), 1000)
        assertEquals(1, probe.apronMisses)
        // Growth WITH visible change is normal — no miss.
        bmp.setPixel(10 + 24, 10 + 6, Color.WHITE)
        probe.sample(bmp, listOf(hold(1, r, grew = true)), 1500)
        assertEquals(1, probe.apronMisses)
    }

    @Test
    fun holdEnd_dropsTrack() {
        val probe = TypewriterQuietProbe()
        val bmp = frame()
        probe.sample(bmp, listOf(hold(1, Rect(10, 10, 200, 100))), 0)
        probe.sample(bmp, emptyList(), 500)
        assertNull(probe.streakOf(1))
    }

    @Test
    fun releasedHold_finalComparisonCountsInEndStreak() {
        val probe = TypewriterQuietProbe()
        val bmp = frame()
        val r = Rect(10, 10, 200, 100)
        // The blindness fix: agreement releases fire at the FIRST settled
        // cycle, so the releasing cycle's own comparison must count —
        // otherwise every END reads streak=0 (the 23:48 field session).
        probe.sample(bmp, listOf(hold(1, r)), 0)
        probe.sample(bmp, listOf(hold(1, r)), 500)                    // quiet → 1
        probe.sample(bmp, listOf(hold(1, r, released = true)), 1000)  // quiet → 2, END
        assertEquals(2, probe.lastEndStreak)
        assertNull(probe.streakOf(1))
    }

    @Test
    fun gateSnapshot_paddedUnionCoversReadRects_flowDirection() {
        val gate = TypewriterGate()
        val r1 = Rect(500, 800, 900, 860)
        val r2 = Rect(500, 800, 1100, 925)
        gate.filterFarGroups(
            listOf(FarGroup("こんにち", r1, 1)), "ja", false, 0, 200,
        )
        gate.filterFarGroups(
            listOf(FarGroup("こんにちは、旅の", r2, 2)), "ja", false, 1000, 1200,
        )
        val probes = gate.quietProbeSnapshot()
        assertEquals(1, probes.size)
        val p = probes[0]
        assertTrue("growth batch flagged", p.grew)
        assertTrue("padded union covers both reads",
            p.paddedBounds.contains(r1) && p.paddedBounds.contains(r2))
        // Flow apron: extends right and below the union (horizontal LTR).
        assertTrue(p.paddedBounds.right >= r2.right + 60)
        assertTrue(p.paddedBounds.bottom >= r2.bottom + 60)
        // The releasing batch exposes the hold once, flagged released;
        // the next batch drops it.
        gate.filterFarGroups(
            listOf(FarGroup("こんにちは、旅の", r2, 2)), "ja", false, 2000, 2200,
        )
        assertTrue(gate.quietProbeSnapshot().single().released)
        gate.filterFarGroups(emptyList(), "ja", false, 3000, 3200)
        assertTrue(gate.quietProbeSnapshot().isEmpty())
    }
}
