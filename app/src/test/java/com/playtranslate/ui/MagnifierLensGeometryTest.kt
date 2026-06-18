package com.playtranslate.ui

import org.junit.Assert.assertEquals
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
