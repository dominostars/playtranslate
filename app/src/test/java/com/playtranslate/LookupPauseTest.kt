package com.playtranslate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The record of what an over-game lookup surface paused ([LookupPause]),
 * against a recording stand-in for the region indicator and auto-translate:
 * a close restores both, a teardown forgets both, a page over the game
 * forgets only the restart, and a surface that takes over from another
 * owes what that one owed, so nothing restarts in between.
 */
class LookupPauseTest {

    private var indicatorShowing = true
    private var live = false
    private val calls = mutableListOf<String>()

    private fun pause() = LookupPause(
        hideIndicator = {
            val was = indicatorShowing
            indicatorShowing = false
            calls += "hide"
            was
        },
        restoreIndicator = {
            indicatorShowing = true
            calls += "restore"
        },
        liveRunning = { live },
        stopLive = {
            live = false
            calls += "stop"
        },
        startLive = {
            live = true
            calls += "start"
        },
    )

    @Test fun `begin hides the indicator and stops a running session, and settle restores both`() {
        live = true
        val p = pause()

        p.begin()
        assertEquals(listOf("hide", "stop"), calls)
        assertTrue(p.pausedLive)
        assertTrue(p.hidIndicator)

        p.settle(resumeLive = true)
        assertEquals(listOf("hide", "stop", "restore", "start"), calls)
        assertFalse(p.pausedLive)
        assertFalse(p.hidIndicator)
    }

    @Test fun `a settle without the resume restores the indicator and leaves auto-translate stopped`() {
        live = true
        val p = pause()
        p.begin()
        calls.clear()

        p.settle(resumeLive = false)

        assertEquals(listOf("restore"), calls)
        assertFalse(live)
        assertFalse("nothing is owed afterwards", p.pausedLive)
    }

    @Test fun `with auto-translate off nothing is stopped and nothing restarts`() {
        val p = pause()
        p.begin()
        p.settle(resumeLive = true)
        assertEquals(listOf("hide", "restore"), calls)
    }

    @Test fun `a begin that may not pause live leaves a running session alone`() {
        live = true
        val p = pause()
        p.begin(pauseLive = false)
        p.settle(resumeLive = true)
        assertEquals(listOf("hide", "restore"), calls)
        assertTrue(live)
    }

    @Test fun `an indicator already hidden is not restored by this record`() {
        indicatorShowing = false
        val p = pause()
        p.begin()
        p.settle(resumeLive = true)
        assertEquals(listOf("hide"), calls)
    }

    @Test fun `drop forgets both without acting`() {
        live = true
        val p = pause()
        p.begin()
        calls.clear()

        p.drop()
        p.settle(resumeLive = true)

        assertEquals(emptyList<String>(), calls)
    }

    @Test fun `cancelResume forgets only the restart`() {
        live = true
        val p = pause()
        p.begin()
        calls.clear()

        p.cancelResume()
        p.settle(resumeLive = true)

        assertEquals(listOf("restore"), calls)
    }

    @Test fun `a hand-over moves what was owed, and the giver owes nothing`() {
        live = true
        val giver = pause()
        val taker = pause()
        giver.begin()
        calls.clear()

        giver.handTo(taker)
        giver.settle(resumeLive = true)
        assertEquals("the giver restores nothing", emptyList<String>(), calls)

        // The taker's own begin finds the session stopped and the indicator
        // hidden, and keeps what it took over.
        taker.begin()
        assertTrue(taker.pausedLive)
        assertTrue(taker.hidIndicator)
        taker.settle(resumeLive = true)
        assertEquals(listOf("hide", "restore", "start"), calls)
    }

    @Test fun `a hand-over adds to what the taker already owes`() {
        val giver = pause()
        val taker = pause()
        // The taker hid the indicator with nothing running; the giver then
        // stopped a session the indicator was already hidden for.
        taker.begin()
        live = true
        giver.begin()
        assertFalse(taker.pausedLive)
        assertFalse(giver.hidIndicator)

        giver.handTo(taker)

        assertTrue("the giver's restart", taker.pausedLive)
        assertTrue("its own indicator", taker.hidIndicator)
    }

    @Test fun `a hand-over to itself keeps what it owes`() {
        live = true
        val p = pause()
        p.begin()
        p.handTo(p)
        assertTrue(p.pausedLive)
        assertTrue(p.hidIndicator)
    }
}
