package com.playtranslate.ui

import com.playtranslate.WindowBounds
import com.playtranslate.overlay.Sides
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Where the detached window's card goes, as pure geometry: its rest place
 * from the saved bounds, a move, a resize from either bottom corner, the
 * lift over a keyboard, which of its two states a width is (the results
 * page, or the one capture button), and when a finger is at a side for the
 * dock gesture. Pixels in, pixels out; the view only applies the result.
 *
 * The card keeps inside its [Room]: the display less the room every system
 * bar takes, shown or not, and the cutout
 * ([com.playtranslate.overlay.OverlayEnvironment.barRoom]). A bar the game
 * shows later therefore never lands on the window or moves it.
 */
internal object DetachedWindowGeometry {

    /** From this width the window shows the results page; narrower, it is
     *  the capture button. */
    const val MIN_PAGE_WIDTH_DP = 150f

    /** The least height with the page showing: the bar and a strip of the
     *  page under it. Less than the button's, whose icon and two-line label
     *  need theirs. */
    const val MIN_PAGE_HEIGHT_DP = 73f
    const val MIN_BUTTON_WIDTH_DP = 96f
    const val MIN_BUTTON_HEIGHT_DP = 78f

    const val DEFAULT_WIDTH_DP = 420f
    const val DEFAULT_HEIGHT_DP = 680f
    const val DEFAULT_MARGIN_DP = 16f

    /** How near a side of the room the finger must be for the dock gesture. */
    const val DOCK_ZONE_DP = 16f

    /** A drag inside the page moves the window only when its sideways travel
     *  is at least this many times its vertical travel. */
    const val SIDEWAYS_RATIO = 2.5f

    /** The card's rect on the display. */
    data class Frame(val x: Int, val y: Int, val w: Int, val h: Int) {
        val right: Int get() = x + w
        val bottom: Int get() = y + h
    }

    /** The part of the display the card may use. */
    data class Room(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = (right - left).coerceAtLeast(0)
        val height: Int get() = (bottom - top).coerceAtLeast(0)
    }

    enum class Corner { BOTTOM_LEFT, BOTTOM_RIGHT }

    enum class Side { LEFT, RIGHT }

    fun room(displayW: Int, displayH: Int, barRoom: Sides): Room =
        Room(barRoom.left, barRoom.top, displayW - barRoom.right, displayH - barRoom.bottom)

    private fun px(dp: Float, density: Float): Int = (dp * density).roundToInt()

    /** Whether a card [widthPx] wide shows the results page (else the
     *  capture button). */
    fun isPage(widthPx: Int, density: Float): Boolean = widthPx >= px(MIN_PAGE_WIDTH_DP, density)

    /** The least height a card [widthPx] wide may have: its state's. */
    private fun minHeight(widthPx: Int, density: Float): Int =
        px(if (isPage(widthPx, density)) MIN_PAGE_HEIGHT_DP else MIN_BUTTON_HEIGHT_DP, density)

    /** [value] within [min]..[max], and [max] when the room is smaller than
     *  the least size (the card never leaves the room to keep a minimum). */
    private fun within(value: Int, min: Int, max: Int): Int =
        if (max <= min) max.coerceAtLeast(0) else value.coerceIn(min, max)

    /** [frame] made valid for [room]: no smaller than its state allows, no
     *  larger than the room, and wholly inside it. */
    fun fit(frame: Frame, room: Room, density: Float): Frame {
        val w = within(frame.w, px(MIN_BUTTON_WIDTH_DP, density), room.width)
        val h = within(frame.h, minHeight(w, density), room.height)
        return Frame(
            x = within(frame.x, room.left, room.right - w),
            y = within(frame.y, room.top, room.bottom - h),
            w = w,
            h = h,
        )
    }

    /** Where the window goes the first time on a display: the default size,
     *  or what fits, at the top end of the room. */
    fun default(room: Room, density: Float): Frame {
        val margin = px(DEFAULT_MARGIN_DP, density)
        val w = px(DEFAULT_WIDTH_DP, density).coerceAtMost(room.width - 2 * margin)
        val h = px(DEFAULT_HEIGHT_DP, density).coerceAtMost(room.height - 2 * margin)
        return fit(Frame(room.right - margin - w, room.top + margin, w, h), room, density)
    }

    /** The frame saved [bounds] stand for in [room]. */
    fun fromBounds(bounds: WindowBounds, room: Room, density: Float): Frame {
        val sized = fit(Frame(room.left, room.top, px(bounds.widthDp, density), px(bounds.heightDp, density)), room, density)
        val x = room.left + (bounds.xFraction.coerceIn(0f, 1f) * (room.width - sized.w)).roundToInt()
        val y = room.top + (bounds.yFraction.coerceIn(0f, 1f) * (room.height - sized.h)).roundToInt()
        return fit(sized.copy(x = x, y = y), room, density)
    }

    /** [frame] as bounds to save: the inverse of [fromBounds]. A card as
     *  wide (or tall) as the room has no place along that axis; it saves 0. */
    fun toBounds(frame: Frame, room: Room, density: Float): WindowBounds {
        val spanX = room.width - frame.w
        val spanY = room.height - frame.h
        return WindowBounds(
            xFraction = if (spanX > 0) ((frame.x - room.left).toFloat() / spanX).coerceIn(0f, 1f) else 0f,
            yFraction = if (spanY > 0) ((frame.y - room.top).toFloat() / spanY).coerceIn(0f, 1f) else 0f,
            widthDp = frame.w / density,
            heightDp = frame.h / density,
        )
    }

    /** [start] moved by the finger's travel, its size kept. */
    fun moved(start: Frame, dx: Int, dy: Int, room: Room, density: Float): Frame =
        fit(start.copy(x = start.x + dx, y = start.y + dy), room, density)

    /**
     * [start] resized from [corner] by the finger's travel. The bottom-right
     * corner moves the right and bottom edges; the bottom-left moves the
     * left and bottom edges, the right edge staying where it was. The top
     * edge never moves.
     */
    fun resized(start: Frame, corner: Corner, dx: Int, dy: Int, room: Room, density: Float): Frame {
        val minW = px(MIN_BUTTON_WIDTH_DP, density)
        val w = when (corner) {
            Corner.BOTTOM_RIGHT -> within(start.w + dx, minW, room.right - start.x)
            Corner.BOTTOM_LEFT -> within(start.w - dx, minW, start.right - room.left)
        }
        val h = within(start.h + dy, minHeight(w, density), room.bottom - start.y)
        val x = when (corner) {
            Corner.BOTTOM_RIGHT -> start.x
            Corner.BOTTOM_LEFT -> start.right - w
        }
        return Frame(x, start.y, w, h)
    }

    /** [frame] raised by [lift] to clear a keyboard, never above the room. */
    fun lifted(frame: Frame, lift: Int, room: Room): Frame =
        frame.copy(y = (frame.y - lift.coerceAtLeast(0)).coerceAtLeast(room.top).coerceAtMost(frame.y))

    /** The side of the room the finger is at, for the dock gesture: within
     *  [DOCK_ZONE_DP] of it. Null anywhere else. */
    fun dockSide(fingerX: Float, room: Room, density: Float): Side? {
        val zone = px(DOCK_ZONE_DP, density)
        return when {
            fingerX <= room.left + zone -> Side.LEFT
            fingerX >= room.right - zone -> Side.RIGHT
            else -> null
        }
    }

    /** Whether a drag that has travelled ([dx], [dy]) inside the page is "a
     *  very clear left or right": past twice the touch [slop] sideways, with
     *  at least [SIDEWAYS_RATIO] times as much sideways travel as vertical. */
    fun isClearSideways(dx: Float, dy: Float, slop: Int): Boolean =
        abs(dx) > 2 * slop && abs(dx) >= SIDEWAYS_RATIO * abs(dy)
}
