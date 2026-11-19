package com.playtranslate.ui

import com.playtranslate.overlay.Sides
import com.playtranslate.ui.DetachedWindowGeometry.Corner
import com.playtranslate.ui.DetachedWindowGeometry.Frame
import com.playtranslate.ui.DetachedWindowGeometry.Room
import com.playtranslate.ui.DetachedWindowGeometry.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DetachedWindowGeometry]: the window's rest place, a move, a resize from
 * each bottom corner, the keyboard lift, the page-or-button threshold, the
 * dock zone and the "very clear sideways" rule. Density 1 throughout, so a
 * dp constant reads as pixels; one case runs at density 2.
 */
class DetachedWindowGeometryTest {

    private val g = DetachedWindowGeometry

    /** A 1280 x 800 display with a 24 px status bar and a 48 px bar below. */
    private val room = g.room(1280, 800, Sides(0, 24, 0, 48))

    @Test
    fun `the room is the display less the bars' room`() {
        assertEquals(Room(0, 24, 1280, 752), room)
        assertEquals(Room(80, 0, 1200, 800), g.room(1280, 800, Sides(80, 0, 80, 0)))
    }

    // ── Page or button ───────────────────────────────────────────────────

    @Test
    fun `a card is the page from 150 dp wide and the button below it`() {
        assertFalse(g.isPage(149, 1f))
        assertTrue(g.isPage(150, 1f))
        assertFalse(g.isPage(299, 2f))
        assertTrue(g.isPage(300, 2f))
    }

    // ── fit ──────────────────────────────────────────────────────────────

    @Test
    fun `a frame is never smaller than its state allows`() {
        // Button: 96 x 78 at the least.
        assertEquals(Frame(100, 100, 96, 78), g.fit(Frame(100, 100, 50, 50), room, 1f))
        // Page: 73 tall at the least, the bar and a strip of the page.
        assertEquals(Frame(100, 100, 150, 73), g.fit(Frame(100, 100, 150, 40), room, 1f))
        // Just under the page width it is the button, which needs 78.
        assertEquals(Frame(100, 100, 149, 78), g.fit(Frame(100, 100, 149, 73), room, 1f))
    }

    @Test
    fun `a frame is never larger than the room`() {
        assertEquals(Frame(0, 24, 1280, 728), g.fit(Frame(-50, -50, 5000, 5000), room, 1f))
    }

    @Test
    fun `a frame is pulled inside the room on each side`() {
        val w = 400
        val h = 300
        assertEquals("left", Frame(0, 100, w, h), g.fit(Frame(-30, 100, w, h), room, 1f))
        assertEquals("top", Frame(100, 24, w, h), g.fit(Frame(100, 0, w, h), room, 1f))
        assertEquals("right", Frame(880, 100, w, h), g.fit(Frame(1000, 100, w, h), room, 1f))
        assertEquals("bottom", Frame(100, 452, w, h), g.fit(Frame(100, 700, w, h), room, 1f))
    }

    @Test
    fun `a room smaller than the least size is filled, not left`() {
        val tiny = Room(0, 0, 80, 60)
        assertEquals(Frame(0, 0, 80, 60), g.fit(Frame(10, 10, 200, 200), tiny, 1f))
    }

    // ── Default and saved bounds ─────────────────────────────────────────

    @Test
    fun `the first place is the default size at the top end, or what fits`() {
        assertEquals(Frame(844, 40, 420, 680), g.default(room, 1f))
        // A phone-sized room: the margins come off the size.
        assertEquals(Frame(16, 16, 328, 608), g.default(Room(0, 0, 360, 640), 1f))
    }

    @Test
    fun `saved bounds bring a frame back where it was`() {
        for (frame in listOf(
            Frame(844, 40, 420, 680),
            Frame(0, 24, 300, 220),
            Frame(500, 300, 120, 90),
            Frame(860, 452, 420, 300),
        )) {
            assertEquals(frame, g.fromBounds(g.toBounds(frame, room, 1f), room, 1f))
        }
    }

    @Test
    fun `a frame at an edge stays at that edge when the room changes`() {
        val atRightBottom = Frame(860, 452, 420, 300)
        val bounds = g.toBounds(atRightBottom, room, 1f)
        val narrower = Room(0, 0, 1000, 600)
        assertEquals(Frame(580, 300, 420, 300), g.fromBounds(bounds, narrower, 1f))
    }

    @Test
    fun `bounds are kept in dp, so a denser display gets more pixels`() {
        val bounds = g.toBounds(Frame(0, 24, 400, 300), room, 1f)
        val dense = g.room(2560, 1600, Sides(0, 48, 0, 96))
        assertEquals(Frame(0, 48, 800, 600), g.fromBounds(bounds, dense, 2f))
    }

    // ── Move ─────────────────────────────────────────────────────────────

    @Test
    fun `a move keeps the size and stops at each side of the room`() {
        val start = Frame(400, 300, 400, 300)
        assertEquals(Frame(450, 280, 400, 300), g.moved(start, 50, -20, room, 1f))
        assertEquals("left", Frame(0, 300, 400, 300), g.moved(start, -900, 0, room, 1f))
        assertEquals("top", Frame(400, 24, 400, 300), g.moved(start, 0, -900, room, 1f))
        assertEquals("right", Frame(880, 300, 400, 300), g.moved(start, 900, 0, room, 1f))
        assertEquals("bottom", Frame(400, 452, 400, 300), g.moved(start, 0, 900, room, 1f))
    }

    // ── Resize ───────────────────────────────────────────────────────────

    private val start = Frame(400, 200, 400, 300)

    @Test
    fun `the bottom right corner moves the right and bottom edges`() {
        assertEquals(Frame(400, 200, 460, 350), g.resized(start, Corner.BOTTOM_RIGHT, 60, 50, room, 1f))
        // Stops at the room's right and bottom.
        assertEquals(Frame(400, 200, 880, 552), g.resized(start, Corner.BOTTOM_RIGHT, 5000, 5000, room, 1f))
        // Stops at the least width, the left edge where it was.
        assertEquals(Frame(400, 200, 96, 300), g.resized(start, Corner.BOTTOM_RIGHT, -5000, 0, room, 1f))
    }

    @Test
    fun `the bottom left corner moves the left and bottom edges, the right edge staying`() {
        assertEquals(Frame(340, 200, 460, 350), g.resized(start, Corner.BOTTOM_LEFT, -60, 50, room, 1f))
        // Stops at the room's left.
        assertEquals(Frame(0, 200, 800, 300), g.resized(start, Corner.BOTTOM_LEFT, -5000, 0, room, 1f))
        // Stops at the least width, the right edge (800) where it was.
        assertEquals(Frame(704, 200, 96, 300), g.resized(start, Corner.BOTTOM_LEFT, 5000, 0, room, 1f))
    }

    @Test
    fun `the top edge never moves in a resize`() {
        for (corner in Corner.entries) {
            for ((dx, dy) in listOf(-500 to -500, 500 to 500, -500 to 500, 500 to -500)) {
                assertEquals("$corner $dx,$dy", 200, g.resized(start, corner, dx, dy, room, 1f).y)
            }
        }
    }

    @Test
    fun `the least height follows the state the width puts the card in`() {
        // Still a page (150 wide): down to 73.
        assertEquals(Frame(400, 200, 150, 73), g.resized(start, Corner.BOTTOM_RIGHT, -250, -5000, room, 1f))
        // A button (130 wide): no shorter than 78.
        assertEquals(Frame(400, 200, 130, 78), g.resized(start, Corner.BOTTOM_RIGHT, -270, -5000, room, 1f))
        assertEquals(Frame(670, 200, 130, 78), g.resized(start, Corner.BOTTOM_LEFT, 270, -5000, room, 1f))
    }

    // ── Keyboard lift ────────────────────────────────────────────────────

    @Test
    fun `the lift raises the frame, never above the room and never lowers it`() {
        val frame = Frame(400, 300, 400, 300)
        assertEquals(Frame(400, 200, 400, 300), g.lifted(frame, 100, room))
        assertEquals(Frame(400, 24, 400, 300), g.lifted(frame, 1000, room))
        assertEquals(frame, g.lifted(frame, 0, room))
        assertEquals(frame, g.lifted(frame, -50, room))
    }

    // ── Dock zone ────────────────────────────────────────────────────────

    @Test
    fun `the finger is at a side within 16 dp of the room's edge`() {
        assertEquals(Side.LEFT, g.dockSide(0f, room, 1f))
        assertEquals(Side.LEFT, g.dockSide(16f, room, 1f))
        assertNull(g.dockSide(17f, room, 1f))
        assertNull(g.dockSide(640f, room, 1f))
        assertNull(g.dockSide(1263f, room, 1f))
        assertEquals(Side.RIGHT, g.dockSide(1264f, room, 1f))
        assertEquals(Side.RIGHT, g.dockSide(1280f, room, 1f))
    }

    @Test
    fun `beside a side bar the dock zone starts at the bar`() {
        val beside = g.room(1280, 800, Sides(80, 0, 0, 0))
        assertEquals(Side.LEFT, g.dockSide(96f, beside, 1f))
        assertNull(g.dockSide(97f, beside, 1f))
    }

    // ── A very clear sideways drag ───────────────────────────────────────

    @Test
    fun `a sideways drag is clear past twice the slop and 2 and a half times its vertical travel`() {
        val slop = 8
        assertFalse("at twice the slop", g.isClearSideways(16f, 0f, slop))
        assertTrue("past twice the slop", g.isClearSideways(17f, 0f, slop))
        assertTrue("to the left", g.isClearSideways(-17f, 0f, slop))
        assertTrue("at the ratio", g.isClearSideways(50f, 20f, slop))
        assertTrue("at the ratio, upward", g.isClearSideways(-50f, -20f, slop))
        assertFalse("under the ratio", g.isClearSideways(50f, 21f, slop))
        assertFalse("a vertical drag", g.isClearSideways(0f, 200f, slop))
        assertFalse("a diagonal drag", g.isClearSideways(100f, 100f, slop))
    }
}
