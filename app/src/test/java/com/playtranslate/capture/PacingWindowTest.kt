package com.playtranslate.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The capture loops' self-expiring fast-poll window: opens on poke,
 *  expires on its own, never shrinks, and is per-display. */
class PacingWindowTest {

    @Test fun `window opens on poke and expires on its own`() {
        val w = PacingWindow()
        assertFalse(w.floorActive(0, 1_000L))
        w.poke(0, 1_000L, 3_000L)
        assertTrue(w.floorActive(0, 1_001L))
        assertTrue(w.floorActive(0, 3_999L))
        assertFalse("TTL expiry needs no un-poke", w.floorActive(0, 4_000L))
    }

    @Test fun `a later shorter poke never shrinks an open window`() {
        val w = PacingWindow()
        w.poke(0, 1_000L, 3_000L)
        w.poke(0, 1_500L, 100L)
        assertTrue("longer window must survive", w.floorActive(0, 3_500L))
    }

    @Test fun `windows are per-display`() {
        val w = PacingWindow()
        w.poke(0, 1_000L, 3_000L)
        assertTrue(w.floorActive(0, 2_000L))
        assertFalse("display 1 must not inherit display 0's reveal", w.floorActive(1, 2_000L))
    }
}
