package com.playtranslate.ui

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.abs
import kotlin.math.hypot

/**
 * [GamePicture.find] reads the game picture out of a frame's pixels by the
 * rules in its doc: black is at most 16 on each channel, a bar is a run of
 * all-black lines from an edge, bars come in pairs cut by the shallower
 * side, a pair under 2% of its axis is none, a result keeping under a fifth
 * of an axis or of an unlikely shape is refused, and the floating icon is
 * passed over only where its rim is found, over the slice it paints. Frames
 * are arrays here; the Bitmap reading has its own native-graphics test.
 */
@RunWith(RobolectricTestRunner::class)
class GamePictureTest {

    /** A frame, all black until painted on. */
    private class Frame(override val width: Int, override val height: Int) : GamePicture.Pixels {
        val px = IntArray(width * height) { BLACK }
        override fun row(y: Int, out: IntArray) = System.arraycopy(px, y * width, out, 0, width)
        override fun column(x: Int, out: IntArray) {
            for (y in 0 until height) out[y] = px[y * width + x]
        }
        override fun pixel(x: Int, y: Int): Int = px[y * width + x]
        fun fill(l: Int, t: Int, r: Int, b: Int, color: Int = GAME) {
            for (y in t until b) for (x in l until r) px[y * width + x] = color
        }
        operator fun set(x: Int, y: Int, color: Int) {
            px[y * width + x] = color
        }
    }

    /** A [w] x [h] frame with the game picture inside the given bars. */
    private fun frame(
        w: Int = 1920,
        h: Int = 1080,
        left: Int = 0,
        top: Int = 0,
        right: Int = left,
        bottom: Int = top,
        color: Int = GAME,
    ) = Frame(w, h).apply { fill(left, top, w - right, h - bottom, color) }

    /** The Thor's docked icon (a 183 px window, the disc's radius 64 px, its
     *  rim 3.4 px): on the right at (1829, 403) as measured, or on the left
     *  with its dock line on the display's left edge. */
    private fun thorIcon(
        edgeRight: Boolean = true,
        displayW: Int = 1920,
        displayH: Int = 1080,
        windowX: Int = if (edgeRight) 1829 else -92,
        windowY: Int = 403,
    ): IconDiscFootprint {
        val center = 91.5f
        val r = 64f
        return IconDiscFootprint(
            cx = windowX + if (edgeRight) center + r / 2 else center - r / 2,
            cy = windowY + center,
            radius = r,
            strokePx = 3.4f,
            dockX = windowX + center,
            screenIsLeftOfDock = edgeRight,
            driftPx = 4,
            displayWidth = displayW,
            displayHeight = displayH,
        )
    }

    /** Paint what the docked icon draws into [f]: its rim (grey over black)
     *  along the visible arc, [blur] px wider for anti-aliasing, and the arrow
     *  glyph inside, shifted [dy] by the orbit. The fill is black over black
     *  and paints nothing. */
    private fun paintIcon(f: Frame, icon: IconDiscFootprint, dy: Int = 0, blur: Float = 0f) {
        val x0 = (icon.cx - icon.radius - 3).toInt()
        val x1 = (icon.cx + icon.radius + 3).toInt()
        val y0 = (icon.cy - icon.radius - 3).toInt() + dy
        val y1 = (icon.cy + icon.radius + 3).toInt() + dy
        for (y in y0..y1) for (x in x0..x1) {
            if (x !in 0 until f.width || y !in 0 until f.height) continue
            val px = x + 0.5f
            val py = y + 0.5f
            val visible = if (icon.screenIsLeftOfDock) px < icon.dockX else px > icon.dockX
            if (!visible) continue
            val d = hypot(px - icon.cx, py - icon.cy - dy)
            if (abs(d - icon.radius) <= icon.strokePx / 2 + blur) f[x, y] = RIM
        }
        val tipX = (icon.cx + (if (icon.screenIsLeftOfDock) -1 else 1) * icon.radius * 0.72f).toInt()
        val tipY = (icon.cy + dy).toInt()
        for (y in tipY - 5..tipY + 5) for (x in tipX - 2..tipX + 2) {
            if (x in 0 until f.width && y in 0 until f.height) f[x, y] = GLYPH
        }
    }

    @Test fun `letterbox bars are cut`() {
        assertEquals(Rect(0, 140, 1920, 940), GamePicture.find(frame(top = 140)))
    }

    @Test fun `pillarbox bars are cut`() {
        assertEquals(Rect(240, 0, 1680, 1080), GamePicture.find(frame(left = 240)))
    }

    @Test fun `bars on both axes are cut`() {
        assertEquals(Rect(240, 140, 1680, 940), GamePicture.find(frame(left = 240, top = 140)))
    }

    @Test fun `a frame with no bars is no crop`() {
        assertNull(GamePicture.find(frame()))
    }

    @Test fun `black on one side alone cuts nothing, on each of the four sides`() {
        assertNull("top", GamePicture.find(frame(top = 140, bottom = 0)))
        assertNull("bottom", GamePicture.find(frame(top = 0, bottom = 140)))
        assertNull("left", GamePicture.find(frame(left = 240, right = 0)))
        assertNull("right", GamePicture.find(frame(left = 0, right = 240)))
    }

    @Test fun `an unequal pair is cut by its shallower side, on both sides`() {
        assertEquals(Rect(0, 100, 1920, 980), GamePicture.find(frame(top = 100, bottom = 120)))
        assertEquals(Rect(0, 100, 1920, 980), GamePicture.find(frame(top = 120, bottom = 100)))
        assertEquals(Rect(240, 0, 1680, 1080), GamePicture.find(frame(left = 260, right = 240)))
        assertEquals(Rect(240, 0, 1680, 1080), GamePicture.find(frame(left = 240, right = 260)))
    }

    @Test fun `a pair under two percent of its axis is no bar`() {
        assertNull(GamePicture.find(frame(top = 21)))
        assertEquals(Rect(0, 22, 1920, 1058), GamePicture.find(frame(top = 22)))
        assertNull(GamePicture.find(frame(left = 38)))
        assertEquals(Rect(39, 0, 1881, 1080), GamePicture.find(frame(left = 39)))
    }

    @Test fun `a channel at 16 is black and one at 17 is not`() {
        val dark = frame(top = 140)
        dark.fill(0, 0, 1920, 140, 0xFF101010.toInt())
        dark.fill(0, 940, 1920, 1080, 0xFF101010.toInt())
        assertEquals(Rect(0, 140, 1920, 940), GamePicture.find(dark))
        for (c in listOf(0xFF110000.toInt(), 0xFF001100.toInt(), 0xFF000011.toInt())) {
            val lit = frame(top = 140)
            lit.fill(0, 0, 1920, 140, c)
            lit.fill(0, 940, 1920, 1080, c)
            assertNull(Integer.toHexString(c), GamePicture.find(lit))
        }
    }

    @Test fun `a result keeping under a fifth of an axis is refused`() {
        // 2400 x 1000, so the shape passes: a 450 px picture (18.75%) is
        // refused, a 500 px one (20.8%) kept.
        assertNull(GamePicture.find(frame(w = 2400, h = 1000, left = 975)))
        assertEquals(Rect(950, 0, 1450, 1000), GamePicture.find(frame(w = 2400, h = 1000, left = 950)))
        // Half of both axes is kept; a sixth of both is refused.
        assertEquals(Rect(480, 270, 1440, 810), GamePicture.find(frame(left = 480, top = 270)))
        assertNull(GamePicture.find(frame(left = 800, top = 450)))
    }

    @Test fun `a shape outside 0_4 to 3_6 is refused`() {
        assertNull(GamePicture.find(frame(top = 290)))
        assertEquals(Rect(0, 270, 1920, 810), GamePicture.find(frame(top = 270)))
        assertNull(GamePicture.find(frame(left = 760)))
        assertEquals(Rect(735, 0, 1185, 1080), GamePicture.find(frame(left = 735)))
    }

    @Test fun `an all-black frame, and a frame of nothing, are no crop`() {
        assertNull(GamePicture.find(Frame(1920, 1080)))
        assertNull(GamePicture.find(Frame(0, 10)))
    }

    @Test fun `the icon in a bar ends the bar, unless its footprint is given and its rim is found`() {
        val icon = thorIcon()
        val f = frame(left = 240)
        paintIcon(f, icon)
        assertNull("the rim's pixels end the right bar", GamePicture.find(f))
        assertEquals(Rect(240, 0, 1680, 1080), GamePicture.find(f, listOf(icon)))
    }

    @Test fun `a footprint whose rim is not in the pixels passes nothing over`() {
        val icon = thorIcon()
        val f = frame(left = 240)
        // A game pixel where the icon's slice would be: nothing to skip it.
        f[1900, 494] = GAME
        assertNull(GamePicture.find(f, listOf(icon)))
    }

    @Test fun `only the painted slice is passed over`() {
        val icon = thorIcon()
        val f = frame(left = 240)
        paintIcon(f, icon)
        f[1800, 494] = GAME
        assertEquals(Rect(119, 0, 1801, 1080), GamePicture.find(f, listOf(icon)))
    }

    @Test fun `a footprint of another display size is not looked for`() {
        val icon = thorIcon(displayW = 1080, displayH = 1920)
        val f = frame(left = 240)
        paintIcon(f, icon)
        assertNull(GamePicture.find(f, listOf(icon)))
    }

    @Test fun `the rim is found through the orbit's drift either way`() {
        val icon = thorIcon()
        for (dy in listOf(-4, 4)) {
            val f = frame(left = 240)
            paintIcon(f, icon, dy)
            assertEquals("drift $dy", Rect(240, 0, 1680, 1080), GamePicture.find(f, listOf(icon)))
        }
    }

    @Test fun `a left-docked icon in the left bar is passed over the same way`() {
        val icon = thorIcon(edgeRight = false)
        val f = frame(left = 240)
        paintIcon(f, icon)
        assertNull(GamePicture.find(f))
        assertEquals(Rect(240, 0, 1680, 1080), GamePicture.find(f, listOf(icon)))
    }

    @Test fun `an icon docked inboard of a bar passes over only its screen side`() {
        // The MediaProjection backend docks below a shown navigation bar: the
        // dock line moves in, and the disc's far half lies inside the frame.
        val icon = thorIcon(windowX = 1829 - 84)
        val f = frame(left = 240)
        paintIcon(f, icon)
        // A game pixel under the disc's far half, past the dock line.
        f[1850, 494] = GAME
        assertEquals(Rect(69, 0, 1851, 1080), GamePicture.find(f, listOf(icon)))
    }

    @Test fun `a footprint mostly outside the frame is not looked for`() {
        // The disc's centre 40 px below the frame: five rim points inside.
        val icon = thorIcon(windowY = 1028)
        val f = frame(left = 240)
        paintIcon(f, icon)
        f[1905, 1078] = GAME
        assertNull(GamePicture.find(f, listOf(icon)))
    }

    @Test fun `the rim's anti-aliased edge is inside the slice`() {
        val icon = thorIcon()
        val f = frame(left = 240)
        paintIcon(f, icon, blur = 1f)
        assertEquals(Rect(240, 0, 1680, 1080), GamePicture.find(f, listOf(icon)))
    }

    private companion object {
        const val BLACK = 0xFF000000.toInt()
        const val GAME = 0xFF4080C0.toInt()
        /** The rim, #66888888 over black. */
        const val RIM = 0xFF363636.toInt()
        const val GLYPH = 0xFFC0C0C0.toInt()
    }
}
