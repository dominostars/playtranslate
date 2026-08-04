package com.playtranslate.ui

import android.content.Context
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/** A controller-reachable action in the sheet: the view, and whether the A
 *  button uses hold semantics — press-and-hold fires the view's long-press
 *  (the Anki one-tap add), release before the timeout clicks. Everything else
 *  activates on A press-down. */
data class NavAction(val view: View, val holdActivates: Boolean = false)

/** The sheet's side of the controller-navigation seam. [CaptureResultOverlay]
 *  implements it; a separate interface because the nav controller can't be an
 *  inner class in its own file. All rects are SCREEN coordinates. */
interface CaptureSheetNavHost {
    val isEditing: Boolean
    val isPopoverOpen: Boolean
    val inSliver: Boolean

    /** B / system back, in every sheet state — the host owns the precedence
     *  ladder (popover → edit → lens → sliver → dismiss). */
    fun onControllerBack()
    fun expandFromSliver()

    /** The currently-reachable buttons (header actions + collapsed-strip eyes). */
    fun navActions(): List<NavAction>

    /** Source-word run, in document order. Zero while the source is hidden. */
    fun wordCount(): Int
    fun wordRect(index: Int, out: Rect): Boolean
    fun wordRunIsRtl(): Boolean
    fun activateWord(index: Int)

    /** The scroll viewport, or false while no content is up (status phase). */
    fun scrollViewportOnScreen(out: Rect): Boolean
    fun scrollBy(dy: Int)
    fun ensureVisible(itemOnScreen: Rect)

    /** Paint (or hide, null) the focus ring. Screen-space; the host converts. */
    fun setRing(itemOnScreen: Rect?, clipOnScreen: Rect?)
}

/**
 * Gamepad navigation for the over-game capture sheet: B/back dismisses (the
 * host's ladder), dpad moves a VIRTUAL cursor across buttons and source words,
 * A activates, the left stick scrolls. Virtual — no Android view ever gains
 * focus — because words aren't views, the ring is custom anyway, and real
 * focus would paint stock highlights next to it. Created only while the sheet
 * window is focusable (controller attached); all input arrives through the
 * root's dispatchKeyEvent / onGenericMotionEvent.
 */
class CaptureSheetControllerNav(
    ctx: Context,
    private val host: CaptureSheetNavHost,
) {
    sealed interface Item {
        data class Button(val view: View, val holdActivates: Boolean) : Item
        data class Word(val index: Int) : Item
    }

    private val density = ctx.resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())

    private var cursor: Item? = null

    /** Keycodes whose DOWN we consumed, so the matching UP is consumed too —
     *  an unconsumed UP for an eaten DOWN is the classic stuck-key source for
     *  the app underneath. */
    private val consumedDown = mutableSetOf<Int>()

    // ── Keys ─────────────────────────────────────────────────────────────

    fun handleKey(ev: KeyEvent): Boolean {
        if (ev.action == KeyEvent.ACTION_UP) {
            if (!consumedDown.remove(ev.keyCode)) return false
            when {
                ControllerKeys.isBack(ev.keyCode) -> host.onControllerBack()
                ControllerKeys.isActivate(ev.keyCode) -> onActivateUp()
            }
            return true
        }
        if (ev.action != KeyEvent.ACTION_DOWN) return false
        val keyCode = ev.keyCode

        // Back works in every state, but FIRES ON UP (armed here): dismissal
        // can remove this focused window, and acting on the DOWN would orphan
        // the UP into the game whenever the press outlives the exit
        // animation's ~200ms grace — firing on the consumed UP completes the
        // pair by construction instead of by timing (same pattern as the
        // lens's dismiss). Arming only the FIRST down keeps a B held from
        // before the sheet appeared (e.g. a B-bound capture hotkey) from
        // dismissing on release: its auto-repeats are consumed but never arm.
        if (ControllerKeys.isBack(keyCode)) {
            if (ev.repeatCount == 0) consumedDown.add(keyCode)
            return true
        }
        // The editor owns everything else — dpad moves its text cursor, enter
        // inserts a newline. (Back above is the edit-cancel.)
        if (host.isEditing) return false

        val dir = ControllerKeys.direction(keyCode)
        val activate = ControllerKeys.isActivate(keyCode)
        if (dir == null && !activate) return false

        when {
            // Slivered: any navigation input re-expands (the tap-expand mirror).
            host.inSliver -> if (ev.repeatCount == 0) host.expandFromSliver()
            // Popover up: swallow nav keys so framework focus search can't
            // wander into a button and paint a stock highlight, but act on
            // none of them (B above closes the popover).
            host.isPopoverOpen -> Unit
            dir != null -> {
                cancelHold()
                if (cursor == null) selectFirst() else moveCursor(dir)
            }
            // First A selects; only a second activates.
            ev.repeatCount == 0 -> if (cursor == null) selectFirst() else onActivateDown()
        }
        consumedDown.add(keyCode)
        return true
    }

    /** INVARIANT: an action fired from here (A-DOWN) must not tear the sheet
     *  window down synchronously — the consumed A's UP would then orphan into
     *  the app beneath (the dispatcher delivers unmatched UPs; see
     *  InputState::trackKey). Holds today: every dismissing action (the Anki
     *  review) is hold-gated, so it fires from [onActivateUp] with its pair
     *  already consumed. Audit any new nav item's click path before it lands
     *  in the DOWN-activated set. */
    private fun onActivateDown() {
        when (val cur = cursor) {
            null -> selectFirst()
            is Item.Button -> {
                if (!cur.view.isShown) {
                    selectFirst()
                } else if (cur.holdActivates) {
                    beginHold(cur)
                } else {
                    cur.view.performClick()
                }
            }
            is Item.Word -> host.activateWord(cur.index)
        }
    }

    // ── Hold-A = the view's long-press (Anki one-tap) ────────────────────

    private var holdItem: Item.Button? = null
    private var holdLatched = false
    private val holdRunnable = Runnable {
        val item = holdItem ?: return@Runnable
        holdLatched = true
        // The ripple releasing is the "hold registered" cue — but only LATCH
        // here; the long-press ACTION fires from the consumed A-UP in
        // [onActivateUp]. Firing it mid-press would break the onActivateDown
        // invariant: the one-tap's no-permission / no-deck fallback opens the
        // review, which DISMISSES this focused window, orphaning the still-
        // pending A-up into the game.
        item.view.isPressed = false
    }

    private fun beginHold(item: Item.Button) {
        cancelHold()
        holdItem = item
        holdLatched = false
        // Ripple while held, mirroring a touch press.
        item.view.isPressed = true
        handler.postDelayed(holdRunnable, ViewConfiguration.getLongPressTimeout().toLong())
    }

    private fun onActivateUp() {
        val item = holdItem ?: return
        handler.removeCallbacks(holdRunnable)
        holdItem = null
        item.view.isPressed = false
        val latched = holdLatched
        holdLatched = false
        if (latched) item.view.performLongClick() else item.view.performClick()
    }

    private fun cancelHold() {
        val item = holdItem ?: return
        handler.removeCallbacks(holdRunnable)
        holdItem = null
        holdLatched = false
        item.view.isPressed = false
    }

    // ── Cursor ───────────────────────────────────────────────────────────

    private val tmpLoc = IntArray(2)
    private val tmpRect = Rect()
    /** Where the ring last was, for [revalidateCursor]'s nearest retarget. */
    private val lastItemRect = Rect()

    private fun viewRectOnScreen(v: View, out: Rect): Boolean {
        if (!v.isShown || v.width <= 0 || v.height <= 0) return false
        v.getLocationOnScreen(tmpLoc)
        out.set(tmpLoc[0], tmpLoc[1], tmpLoc[0] + v.width, tmpLoc[1] + v.height)
        return true
    }

    /** Collected lazily per keypress — no registry to go stale. */
    private fun candidates(): List<Pair<Item, Rect>> {
        val out = ArrayList<Pair<Item, Rect>>()
        for (a in host.navActions()) {
            val r = Rect()
            if (viewRectOnScreen(a.view, r)) out.add(Item.Button(a.view, a.holdActivates) to r)
        }
        for (i in 0 until host.wordCount()) {
            val r = Rect()
            if (host.wordRect(i, r)) out.add(Item.Word(i) to r)
        }
        return out
    }

    private fun setCursor(item: Item, rectOnScreen: Rect) {
        cursor = item
        lastItemRect.set(rectOnScreen)
        host.ensureVisible(rectOnScreen)
        syncRing()
    }

    private fun selectFirst() {
        val cands = candidates()
        val idx = SheetNavGeometry.firstItem(cands.map { it.second.toNavRect() }) ?: return
        setCursor(cands[idx].first, cands[idx].second)
    }

    private fun moveCursor(dir: SheetNavGeometry.Dir) {
        val cur = cursor ?: return
        // Inside the word run, left/right steps document order — CJK wrapping
        // makes visual left/right unreliable, and the spans are ground truth.
        // Running off either end falls through to spatial nav (so the cursor
        // can leave the card sideways).
        if (cur is Item.Word &&
            (dir == SheetNavGeometry.Dir.LEFT || dir == SheetNavGeometry.Dir.RIGHT)
        ) {
            val forward = (dir == SheetNavGeometry.Dir.RIGHT) != host.wordRunIsRtl()
            val next = cur.index + if (forward) 1 else -1
            if (next in 0 until host.wordCount() && host.wordRect(next, tmpRect)) {
                setCursor(Item.Word(next), tmpRect)
                return
            }
        }
        val cands = candidates()
        val curIdx = cands.indexOfFirst { it.first == cur }
        if (curIdx < 0) {
            // The cursor's item vanished (layout change) — restart from the top.
            selectFirst()
            return
        }
        val from = cands[curIdx].second.toNavRect()
        val target = SheetNavGeometry.nextInDirection(from, cands.map { it.second.toNavRect() }, dir)
            ?: return   // nothing that way: the cursor stays (no wrap-around)
        setCursor(cands[target].first, cands[target].second)
    }

    /** Per-frame from the sheet's pre-draw hook: re-read the item's live rect
     *  (scroll, resize, entrance/exit all move it) and re-clip to the viewport. */
    fun syncRing() {
        val cur = cursor ?: run {
            host.setRing(null, null)
            return
        }
        val ok = when (cur) {
            is Item.Button -> viewRectOnScreen(cur.view, tmpRect)
            is Item.Word -> host.wordRect(cur.index, tmpRect)
        }
        if (!ok || !host.scrollViewportOnScreen(clipRect)) {
            host.setRing(null, null)
            return
        }
        lastItemRect.set(tmpRect)
        host.setRing(tmpRect, clipRect)
    }

    private val clipRect = Rect()

    /** The cursor is stale (rebind, sliver park, edit open): drop it. */
    fun clearCursor() {
        cancelHold()
        cursor = null
        host.setRing(null, null)
    }

    /** Word spans were retokenized — a word cursor's index is meaningless. */
    fun onWordSpansChanged() {
        if (cursor is Item.Word) clearCursor()
    }

    /** After a layout-changing activation (a section eye toggle swaps a whole
     *  column for its collapsed strip): if the cursor's item left the screen,
     *  retarget to whatever now sits nearest where the ring last was — in the
     *  column-collapse case, the strip's eye. */
    fun revalidateCursor() {
        val cur = cursor ?: return
        val stillValid = when (cur) {
            is Item.Button -> cur.view.isShown
            is Item.Word -> cur.index < host.wordCount() && host.wordRect(cur.index, tmpRect)
        }
        if (stillValid) return
        val cands = candidates()
        if (cands.isEmpty()) {
            clearCursor()
            return
        }
        var best = 0
        var bestD = Long.MAX_VALUE
        for (i in cands.indices) {
            val r = cands[i].second
            val dx = (r.centerX() - lastItemRect.centerX()).toLong()
            val dy = (r.centerY() - lastItemRect.centerY()).toLong()
            val d = dx * dx + dy * dy
            if (d < bestD) {
                bestD = d
                best = i
            }
        }
        setCursor(cands[best].first, cands[best].second)
    }

    // ── Left stick → scroll ──────────────────────────────────────────────

    private var stickActive = false
    private var stickY = 0f
    private var stickDeadZone = STICK_DEAD_ZONE

    fun handleGenericMotion(ev: MotionEvent): Boolean {
        if (ev.actionMasked != MotionEvent.ACTION_MOVE) return false
        if (ev.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK) return false
        if (host.isEditing || host.isPopoverOpen) return false
        // Hat-driven dpad: leave the event UNCONSUMED so ViewRootImpl's
        // synthetic joystick handler still converts AXIS_HAT_* into the DPAD
        // key events handleKey navigates by. (Consuming every joystick MOVE
        // would kill the dpad on controllers that report it as a hat.)
        if (ev.getAxisValue(MotionEvent.AXIS_HAT_X) != 0f ||
            ev.getAxisValue(MotionEvent.AXIS_HAT_Y) != 0f
        ) {
            return false
        }
        val flat = ev.device?.getMotionRange(MotionEvent.AXIS_Y, ev.source)?.flat ?: 0f
        val dead = maxOf(flat, STICK_DEAD_ZONE)
        val x = ev.getAxisValue(MotionEvent.AXIS_X)
        val y = ev.getAxisValue(MotionEvent.AXIS_Y)
        if (abs(y) <= dead && abs(x) <= dead) {
            val wasActive = stickActive
            stickActive = false
            stopScrollRepeater()
            // Consume the centering event of a deflection we owned.
            return wasActive
        }
        stickActive = true
        stickDeadZone = dead
        stickY = y
        // The sliver has nothing to scroll, but still consume: an unconsumed
        // stick MOVE would synthesize phantom DPAD keys.
        if (!host.inSliver) startScrollRepeater()
        return true
    }

    private var repeaterRunning = false
    private var lastFrameNs = 0L
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!repeaterRunning) return
            // Modal self-check, HERE and not only in the event path: a modal
            // opening mid-deflection (editor, popover, sliver park) doesn't
            // stop this loop, and the centering MOVE that normally would is
            // swallowed by handleGenericMotion's modal bail — after which a
            // stick at rest emits no further events at all, leaving the stale
            // deflection scrolling forever. One check at the single point
            // every frame passes through covers every current + future modal.
            if (host.isEditing || host.isPopoverOpen || host.inSliver) {
                stickActive = false
                stopScrollRepeater()
                return
            }
            // Clamp a dropped-frame gap so a jank spike can't teleport the scroll.
            val dt = ((frameTimeNanos - lastFrameNs) / 1e9f).coerceIn(0f, 0.05f)
            lastFrameNs = frameTimeNanos
            val mag = ((abs(stickY) - stickDeadZone) / (1f - stickDeadZone)).coerceIn(0f, 1f)
            val dy = sign(stickY) * mag * MAX_SCROLL_DP_PER_SEC * density * dt
            host.scrollBy(dy.roundToInt())
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun startScrollRepeater() {
        if (repeaterRunning) return
        repeaterRunning = true
        lastFrameNs = System.nanoTime()
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun stopScrollRepeater() {
        if (!repeaterRunning) return
        repeaterRunning = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    /** Teardown (dismissal): stop timers, hide the ring. */
    fun release() {
        cancelHold()
        stopScrollRepeater()
        cursor = null
        host.setRing(null, null)
    }

    private fun Rect.toNavRect() = SheetNavGeometry.NavRect(left, top, right, bottom)

    private companion object {
        /** Per-axis stick dead zone (raised to the device's reported flat).
         *  NOT the lens's 0.25 squared-magnitude nudge threshold — that one is
         *  deliberately coarse (a dismissal flick); scrolling needs to engage
         *  at a comfortable deflection. */
        const val STICK_DEAD_ZONE = 0.20f

        /** Full-deflection scroll speed. */
        const val MAX_SCROLL_DP_PER_SEC = 1400f
    }
}
