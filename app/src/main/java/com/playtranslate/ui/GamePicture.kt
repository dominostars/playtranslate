package com.playtranslate.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.util.Log
import com.playtranslate.capture.CaptureBackendResolver
import java.io.File
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The game picture in a captured frame: the frame less the black bars a
 * game whose shape is not the screen's is drawn between (a 4:3 game on a
 * 16:9 screen sits centred with black at each side). Read from the frame's
 * own pixels when a card is made, never at capture: the capture path is
 * every tool's, and the card is one use of its file. A card's starting
 * crop ([AnkiScreenshotState]) and a one-tap card's cut
 * ([AnkiScreenshotPin.pinForSend]) are its two readers.
 *
 * The rules, estimates tuned on no data:
 *  - a row or column is bar only if every pixel looked at is black, at most
 *    [BLACK_LEVEL] on each channel;
 *  - bars come in pairs: each axis is cut by the shallower of its two
 *    sides, so black on one side alone cuts nothing;
 *  - a pair under [MIN_BAR_FRACTION] of its axis is no bar;
 *  - a result that keeps under [MIN_KEPT_FRACTION] of either axis, or whose
 *    shape is outside [MIN_ASPECT]..[MAX_ASPECT], is refused;
 *  - the floating icon, which a clean capture holds at some times and not
 *    at others (measured on the Thor, 2026-11-16), is passed over only where
 *    its rim is found in the pixels, and only over the slice it paints
 *    ([IconDiscFootprint]): skipping its window on its position alone could
 *    cut game pixels that lie under it. A frame of another size than the
 *    icon's display, or taken in another rotation, fails the rim test and
 *    keeps the icon's pixels, which then end the bar on their side.
 *
 * Known limits: a centred picture on a black screen is cut to that picture;
 * bars that are not black are not found; game content showing through the
 * icon's fill is cut with it; this app's pills are game; a status bar in
 * the frame ends the top bar at its glyphs, so that axis is left whole.
 *
 * Three rules follow GameSentenceMiner (github.com/bpwhelan/GameSentenceMiner,
 * GPL-3.0; `find_black_bars` in GameSentenceMiner/util/media/ffmpeg.py, read
 * at its commit of 2026-11-15): the black level of 16, refusing a result that
 * keeps too little of the frame, and refusing an unlikely shape, whose
 * bounds here are set loosely around the two ends of its aspect-ratio list
 * (9:16 and 32:9). No code was copied.
 */
object GamePicture {
    private const val TAG = "GamePicture"

    /** A pixel whose channels are each at most this is black. */
    const val BLACK_LEVEL = 16
    /** A pair of bars under this fraction of its axis is no bar. */
    const val MIN_BAR_FRACTION = 0.02f
    /** A result keeping under this fraction of either axis is refused. */
    const val MIN_KEPT_FRACTION = 0.2f
    /** The shapes a result may have, width over height. */
    const val MIN_ASPECT = 0.4f
    const val MAX_ASPECT = 3.6f

    /** The share of an icon's sampled rim points that have to be lit for the
     *  icon to count as in the frame (44 of 44 were lit in a frame that held
     *  it, 0 of 44 in one that did not), and how many of them must lie inside
     *  the frame at all. */
    private const val RIM_LIT_SHARE = 0.9f
    private const val RIM_MIN_POINTS = 8
    /** The rim is sampled along its visible arc less this margin at each end
     *  (degrees), where the dock line's clip thins it, one point per step. */
    private const val RIM_MARGIN_DEG = 10f
    private const val RIM_STEP_DEG = 2.5f

    /** A frame's pixels (ARGB ints), read a line at a time. */
    interface Pixels {
        val width: Int
        val height: Int
        /** Row [y]'s [width] pixels into [out]. */
        fun row(y: Int, out: IntArray)
        /** Column [x]'s [height] pixels into [out]. */
        fun column(x: Int, out: IntArray)
        fun pixel(x: Int, y: Int): Int
    }

    private class BitmapPixels(private val bitmap: Bitmap) : Pixels {
        override val width: Int get() = bitmap.width
        override val height: Int get() = bitmap.height
        override fun row(y: Int, out: IntArray) = bitmap.getPixels(out, 0, width, 0, y, width, 1)
        override fun column(x: Int, out: IntArray) = bitmap.getPixels(out, 0, 1, x, 0, 1, height)
        override fun pixel(x: Int, y: Int): Int = bitmap.getPixel(x, y)
    }

    fun isBlack(argb: Int): Boolean =
        (argb shr 16 and 0xFF) <= BLACK_LEVEL &&
            (argb shr 8 and 0xFF) <= BLACK_LEVEL &&
            (argb and 0xFF) <= BLACK_LEVEL

    /**
     * The game picture in [pixels], in its pixels, or null where there is
     * none to cut to: no bars, a frame that is all black, or a result the
     * rules refuse. [icons] are the floating icons as they stand on their
     * displays ([floatingIcons]); one whose display is not the frame's size
     * is not looked for.
     */
    fun find(pixels: Pixels, icons: List<IconDiscFootprint> = emptyList()): Rect? {
        val w = pixels.width
        val h = pixels.height
        if (w <= 0 || h <= 0) return null
        val passed = icons
            .filter { it.displayWidth == w && it.displayHeight == h }
            .map { Crescent(it) }
            .filter { it.rimFound(pixels) }
        fun passedOver(x: Int, y: Int) = passed.any { it.contains(x, y) }
        val rowBuf = IntArray(w)
        val colBuf = IntArray(h)
        fun rowIsBar(y: Int): Boolean {
            pixels.row(y, rowBuf)
            for (x in 0 until w) if (!isBlack(rowBuf[x]) && !passedOver(x, y)) return false
            return true
        }
        fun columnIsBar(x: Int): Boolean {
            pixels.column(x, colBuf)
            for (y in 0 until h) if (!isBlack(colBuf[y]) && !passedOver(x, y)) return false
            return true
        }
        var top = 0
        while (top < h && rowIsBar(top)) top++
        // All black: nothing to cut to.
        if (top == h) return null
        var bottom = 0
        while (bottom < h - top && rowIsBar(h - 1 - bottom)) bottom++
        var left = 0
        while (left < w && columnIsBar(left)) left++
        var right = 0
        while (right < w - left && columnIsBar(w - 1 - right)) right++

        var vertical = min(top, bottom)
        var horizontal = min(left, right)
        if (vertical < MIN_BAR_FRACTION * h) vertical = 0
        if (horizontal < MIN_BAR_FRACTION * w) horizontal = 0
        if (vertical == 0 && horizontal == 0) return null
        val picture = Rect(horizontal, vertical, w - horizontal, h - vertical)
        if (picture.width() < MIN_KEPT_FRACTION * w || picture.height() < MIN_KEPT_FRACTION * h) return null
        val aspect = picture.width().toFloat() / picture.height()
        if (aspect < MIN_ASPECT || aspect > MAX_ASPECT) return null
        return picture
    }

    fun find(bitmap: Bitmap, icons: List<IconDiscFootprint> = emptyList()): Rect? =
        find(BitmapPixels(bitmap), icons)

    /** [file] decoded whole and read, as the crop that is exactly the picture
     *  found ([ScreenshotCrop.ofPixels]); null where there is none, where the
     *  file cannot be decoded, or when memory runs out. Call off the main
     *  thread: the decode is a full frame. */
    fun cropOf(file: File, icons: List<IconDiscFootprint>): ScreenshotCrop? {
        val bitmap = try {
            BitmapFactory.decodeFile(file.absolutePath)
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "out of memory decoding the frame; no starting crop")
            null
        } ?: return null
        try {
            return find(bitmap, icons)?.let { ScreenshotCrop.ofPixels(it, bitmap.width, bitmap.height) }
        } finally {
            bitmap.recycle()
        }
    }

    /** The floating icons as they stand on their displays when the card is
     *  made ([com.playtranslate.OverlayUiController.floatingIconDiscs]); empty
     *  with no capture backend up. Main thread, where the icons live. */
    fun floatingIcons(): List<IconDiscFootprint> =
        CaptureBackendResolver.activeOverlayUi?.floatingIconDiscs().orEmpty()

    /** One icon's painted slice in a frame of its display's size: the disc cut
     *  at the dock line, widened by its rim and a pixel of anti-aliasing, and
     *  by the orbit either way. */
    private class Crescent(private val f: IconDiscFootprint) {
        private val reach = f.radius + f.strokePx / 2f + 1f
        private val drift = f.driftPx.toFloat()

        fun contains(x: Int, y: Int): Boolean {
            val px = x + 0.5f
            val py = y + 0.5f
            val offScreenSide = if (f.screenIsLeftOfDock) px >= f.dockX else px <= f.dockX
            if (offScreenSide) return false
            val dy = py - f.cy
            // The nearest orbit position: a vertical segment of ±drift at cx.
            val ddy = dy - dy.coerceIn(-drift, drift)
            val ddx = px - f.cx
            return ddx * ddx + ddy * ddy <= reach * reach
        }

        /** Whether the rim is lit in [pixels] along the visible arc: sampled
         *  at the rim's own radius, each sample allowed the orbit's drift
         *  up or down. */
        fun rimFound(pixels: Pixels): Boolean {
            val toward = if (f.screenIsLeftOfDock) Math.PI.toFloat() else 0f
            val cut = abs((f.dockX - f.cx) / f.radius).coerceAtMost(1f)
            val half = Math.toDegrees(acos(cut).toDouble()).toFloat() - RIM_MARGIN_DEG
            if (half <= 0f) return false
            var inFrame = 0
            var lit = 0
            var t = -half
            while (t <= half + 1e-3f) {
                val a = toward + Math.toRadians(t.toDouble()).toFloat()
                val x = floor(f.cx + f.radius * cos(a)).toInt()
                val y = floor(f.cy + f.radius * sin(a)).toInt()
                if (x in 0 until pixels.width) {
                    val y0 = max(0, y - f.driftPx)
                    val y1 = min(pixels.height - 1, y + f.driftPx)
                    if (y0 <= y1) {
                        inFrame++
                        for (yy in y0..y1) {
                            if (!isBlack(pixels.pixel(x, yy))) {
                                lit++
                                break
                            }
                        }
                    }
                }
                t += RIM_STEP_DEG
            }
            return inFrame >= RIM_MIN_POINTS && lit >= ceil(RIM_LIT_SHARE * inFrame).toInt()
        }
    }
}
