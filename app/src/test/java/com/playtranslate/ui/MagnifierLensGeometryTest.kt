package com.playtranslate.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [computeGrownCardHeight] — the pure clamp math behind the
 * magnifier lens's post-release grow-to-fit. No Android types involved, so it
 * runs as a plain JVM test.
 *
 * Conventions (matching [MagnifierLens]'s real constants): overhang = 26,
 * base card = 120. `anchoredEdgeY` is the card edge that stays put: card
 * BOTTOM (not flipped) or card TOP (flipped).
 */
class MagnifierLensGeometryTest {

    private val overhang = 26
    private val base = 120

    private fun grow(
        flipped: Boolean,
        anchoredEdgeY: Int,
        desiredCardH: Int,
        safeTop: Int = 0,
        safeBottom: Int = 2000,
    ) = computeGrownCardHeight(
        flipped = flipped,
        anchoredEdgeY = anchoredEdgeY,
        desiredCardH = desiredCardH,
        baseCardH = base,
        safeTop = safeTop,
        safeBottom = safeBottom,
        overhang = overhang,
    )

    @Test
    fun notFlipped_contentFits_growsToDesired() {
        // maxCardH = 1000 - 26 - 100 = 874; desired 300 fits.
        assertEquals(300, grow(flipped = false, anchoredEdgeY = 1000, desiredCardH = 300, safeTop = 100))
    }

    @Test
    fun notFlipped_contentTooTall_clampsToSafeTop() {
        // maxCardH = 1000 - 26 - 100 = 874.
        assertEquals(874, grow(flipped = false, anchoredEdgeY = 1000, desiredCardH = 2000, safeTop = 100))
    }

    @Test
    fun flipped_contentFits_growsToDesired() {
        // maxCardH = 2000 - 26 - 200 = 1774; desired 400 fits.
        assertEquals(400, grow(flipped = true, anchoredEdgeY = 200, desiredCardH = 400, safeBottom = 2000))
    }

    @Test
    fun flipped_contentTooTall_clampsToSafeBottom() {
        // maxCardH = 2000 - 26 - 200 = 1774.
        assertEquals(1774, grow(flipped = true, anchoredEdgeY = 200, desiredCardH = 3000, safeBottom = 2000))
    }

    @Test
    fun shortContent_flooredToBase_neverShrinks() {
        assertEquals(base, grow(flipped = false, anchoredEdgeY = 1000, desiredCardH = 80, safeTop = 100))
    }

    @Test
    fun pinnedNearEdge_maxBelowBase_staysAtBase() {
        // Geometric maxCardH = 200 - 26 - 100 = 74 (< base): no room, keep base.
        assertEquals(base, grow(flipped = false, anchoredEdgeY = 200, desiredCardH = 500, safeTop = 100))
    }
}

/**
 * The pill's width cap and the chip lanes it exists to protect.
 *
 * The chips are laid out off the pill's own edges, so the pill's width decides
 * where they land: past a certain width they are seated outside the lens host
 * and the FrameLayout clips them away — visible disk and touch target both.
 * [computeMaxPillWidth] is where the pill has to stop for that never to happen,
 * and [computePillTextAllotment] is how the word and reading are squeezed into
 * it.
 *
 * Numbers below are [MagnifierLens]'s at density 1: a 380 card in a 388 host
 * (4 of chip-halo inset per side), and a 58 chip lane = 8 halo pad + 36 visible
 * disk + 14 gap to the pill.
 */
class MagnifierLensPillWidthTest {

    private val viewW = 388
    private val chipLane = 58
    private val cap = computeMaxPillWidth(viewW, chipLane)

    /** Pill chrome with a reading shown: 18 + 14 padding, 2 divider, 2 × 12
     *  gaps, 13 chevron + 4 margin. */
    private val chrome = 75

    @Test
    fun capLeavesExactlyOneLanePerSide() {
        assertEquals(388 - 2 * 58, cap)
    }

    /** The resting placement the chips are first laid out at is flush with the
     *  host's two edges — so a pill at exactly the cap asks for the very same
     *  margins the pre-reveal layout uses. */
    @Test
    fun pillAtCap_seatsChipsFlushWithTheFrame() {
        assertEquals(0 to 0, computeChipLaneMargins(viewW, cap, chipLane))
    }

    @Test
    fun shortPill_seatsChipsWellInsideTheFrame() {
        // 200-wide pill: 94 of slack per side, less the 58 lane.
        assertEquals(36 to 36, computeChipLaneMargins(viewW, 200, chipLane))
    }

    /** The regression: an unbounded pill (a long word measured at WRAP_CONTENT
     *  ran to the host's full width and beyond) seats both chips at negative
     *  margins — off the frame, where they are clipped. */
    @Test
    fun pillPastCap_wouldSeatChipsOffTheFrame() {
        val (left, right) = computeChipLaneMargins(viewW, viewW, chipLane)
        assertTrue("left chip pushed off the frame: $left", left < 0)
        assertTrue("right chip pushed off the frame: $right", right < 0)
    }

    /** Every width the capped pill can actually take — including the odd ones,
     *  where the halved slack truncates toward the left chip. */
    @Test
    fun everyWidthWithinCap_keepsBothChipsOnTheFrame() {
        for (w in 0..cap) {
            val (left, right) = computeChipLaneMargins(viewW, w, chipLane)
            assertTrue("pill $w seats the left chip at $left", left >= 0)
            assertTrue("pill $w seats the right chip at $right", right >= 0)
        }
    }

    @Test
    fun shortReading_takesWhatItNeeds_headlineGetsTheRest() {
        val (word, reading) = computePillTextAllotment(cap, chrome, readingWidth = 60)
        assertEquals(60, reading)
        assertEquals(cap - chrome - 60, word)
    }

    @Test
    fun longReading_cannotStarveTheHeadline() {
        // A reading that wants the whole budget is held to half of it.
        val budget = cap - chrome
        val (word, reading) = computePillTextAllotment(cap, chrome, readingWidth = budget * 2)
        assertEquals(budget - budget / 2, word)
        assertEquals(budget / 2, reading)
    }

    @Test
    fun noReading_headlineTakesTheWholeBudget() {
        // Kana-only and reading-less pills: no divider/gaps in the chrome either.
        val chromeNoReading = 49
        val (word, reading) = computePillTextAllotment(cap, chromeNoReading, readingWidth = 0)
        assertEquals(cap - chromeNoReading, word)
        assertEquals(0, reading)
    }

    /** The bound the whole fix rests on: the two allotments become the two
     *  views' maxWidths, so chrome + word + reading is what the capsule can
     *  measure to — and it never exceeds the cap, whatever the reading asked
     *  for. */
    @Test
    fun allotmentsPlusChrome_neverExceedTheCap() {
        for (readingWidth in 0..(cap * 2) step 7) {
            val (word, reading) = computePillTextAllotment(cap, chrome, readingWidth)
            assertTrue("negative headline allotment at reading $readingWidth", word >= 0)
            assertTrue("negative reading allotment at reading $readingWidth", reading >= 0)
            assertEquals(cap, chrome + word + reading)
        }
    }

    /** A host too narrow to hold both lanes yields no pill at all rather than a
     *  negative one — the arithmetic stays sane on a tiny display. */
    @Test
    fun hostNarrowerThanTwoLanes_capsAtZero() {
        assertEquals(0, computeMaxPillWidth(80, chipLane))
        assertEquals(0 to 0, computePillTextAllotment(0, chrome, readingWidth = 40))
    }
}
