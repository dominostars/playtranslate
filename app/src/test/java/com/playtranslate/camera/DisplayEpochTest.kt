package com.playtranslate.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the display-epoch protocol: a newer publication source invalidates
 *  every older tail; a tail stays valid only until the next source. */
class DisplayEpochTest {

    @Test
    fun advanceInvalidatesEveryOlderCapture() {
        val e = DisplayEpoch()
        val launchCapture = e.current()
        val acquireTail = e.advance() // anchor install takes ownership
        assertTrue(e.isCurrent(acquireTail))
        assertFalse("launch-time capture must be stale after install", e.isCurrent(launchCapture))

        val reflavorTail = e.advance() // mode change supersedes the acquire tail
        assertFalse("acquire tail must not publish after a re-flavor", e.isCurrent(acquireTail))
        assertTrue(e.isCurrent(reflavorTail))
    }

    @Test
    fun captureWithoutAdvanceTracksTheCurrentOwner() {
        val e = DisplayEpoch()
        val owner = e.advance()
        // Observers (re-raster) capture the current owner without taking over.
        assertEquals(owner, e.current())
        assertTrue(e.isCurrent(e.current()))
        e.advance()
        assertFalse(e.isCurrent(owner))
    }
}
