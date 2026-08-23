package com.playtranslate.ui

import android.content.Context
import android.graphics.Rect
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View

/** The workspace's side of the controller-navigation seam —
 *  [OverlayWorkspace] implements it. All rects are SCREEN coordinates. */
interface WorkspaceNavHost {
    /** An in-window modal (alert/progress) is up: nav keys are swallowed
     *  without acting (B dismisses the modal via [onControllerBack]). */
    val isModalUp: Boolean

    /** A page EditText owns the IME: dpad moves its text cursor, Enter is
     *  its own — only B (edit-cancel) is handled here. */
    val isEditing: Boolean

    /** B / system back, in workspace precedence order — the host owns the
     *  ladder (modal → page.onBack → pop → dismiss). */
    fun onControllerBack()

    /** The topmost page's currently-reachable actions. */
    fun navActions(): List<NavAction>

    /** The topmost page's scroll viewport, or false when it has none. */
    fun scrollViewportOnScreen(out: Rect): Boolean
    fun scrollBy(dy: Int)
    fun ensureVisible(itemOnScreen: Rect)

    /** Paint (or hide, null) the focus ring. Screen-space; the host converts. */
    fun setRing(itemOnScreen: Rect?, clipOnScreen: Rect?)
}

/**
 * Gamepad/keyboard navigation for the floating [OverlayWorkspace]: B/back
 * runs the host's precedence ladder, dpad (and the left stick, via
 * ViewRootImpl's synthetic DPAD keys — [StickScrollDrive] deliberately leaves
 * X/Y motion unconsumed) moves a VIRTUAL cursor across the page's actions, A
 * activates (hold-capable targets get a real view press via
 * [ConfirmKeyPress]), and the right stick scrolls the page.
 *
 * A deliberately reduced sibling of [CaptureSheetControllerNav] — no words,
 * no grabber, no sliver, no resize — sharing its primitives
 * ([SheetNavGeometry], [ControllerKeys], [ConfirmKeyPress], [FocusRingView])
 * and its two load-bearing invariants: back fires on the consumed UP (an
 * eaten DOWN must never orphan its UP into the game), and an action fired
 * from here may freely dismiss the window mid-press because the dismissal
 * seam lingers the window as a key sink ([WindowKeyPairGuard]).
 */
class WorkspaceControllerNav(
    ctx: Context,
    private val host: WorkspaceNavHost,
) {
    private var cursorView: View? = null

    /** Keycodes whose DOWN we consumed, so the matching UP is consumed too. */
    private val consumedDown = mutableSetOf<Int>()

    private val stick = StickScrollDrive(
        ctx,
        onStep = host::scrollBy,
        suspendWhen = { host.isModalUp || host.isEditing },
    )

    // ── Keys ─────────────────────────────────────────────────────────────

    fun handleKey(ev: KeyEvent): Boolean {
        if (ev.action == KeyEvent.ACTION_UP) {
            if (!consumedDown.remove(ev.keyCode)) return false
            when {
                ControllerKeys.isBack(ev.keyCode) -> host.onControllerBack()
                ControllerKeys.isActivate(ev.keyCode) -> onActivateUp(ev)
            }
            return true
        }
        if (ev.action != KeyEvent.ACTION_DOWN) return false
        val keyCode = ev.keyCode

        // Back works in every state but FIRES ON UP (armed here): dismissal
        // can remove this focused window, and acting on the DOWN would orphan
        // the UP into the game — firing on the consumed UP completes the pair
        // by construction. Arming only the FIRST down keeps a B held from
        // before the workspace appeared from dismissing on release.
        if (ControllerKeys.isBack(keyCode)) {
            if (ev.repeatCount == 0) consumedDown.add(keyCode)
            return true
        }
        // The editor owns everything else — dpad moves its text cursor, Enter
        // inserts. (Back above is the edit-cancel.)
        if (host.isEditing) return false

        val dir = ControllerKeys.direction(keyCode)
        val activate = ControllerKeys.isActivate(keyCode)
        if (dir == null && !activate) return false

        when {
            // Modal up: swallow nav keys so nothing beneath reacts, act on
            // none of them (B above dismisses the modal).
            host.isModalUp -> Unit
            dir != null -> {
                cancelHold()
                if (cursorView == null) selectFirst() else moveCursor(dir)
            }
            // First A selects; only a second activates.
            ev.repeatCount == 0 -> if (cursorView == null) selectFirst() else onActivateDown(ev)
        }
        consumedDown.add(keyCode)
        return true
    }

    fun handleGenericMotion(ev: MotionEvent): Boolean = stick.handleGenericMotion(ev)

    private fun onActivateDown(ev: KeyEvent) {
        val cur = cursorView ?: run { selectFirst(); return }
        val action = host.navActions().firstOrNull { it.view === cur }
        if (action == null || !cur.isShown) {
            selectFirst()
            return
        }
        if (action.holdActivates) {
            if (keyPressView == null) {
                keyPressView = cur
                ConfirmKeyPress.down(cur, ev)
            }
        } else {
            cur.performClick()
        }
    }

    // ── Hold-capable targets: A is a real press on the view ──────────────

    private var keyPressView: View? = null

    private fun onActivateUp(ev: KeyEvent) {
        val held = keyPressView ?: return
        keyPressView = null
        ConfirmKeyPress.up(held, ev)
    }

    private fun cancelHold() {
        val held = keyPressView ?: return
        keyPressView = null
        ConfirmKeyPress.cancel(held)
    }

    // ── Cursor ───────────────────────────────────────────────────────────

    private val tmpLoc = IntArray(2)
    private val tmpRect = Rect()
    private val clipRect = Rect()
    /** Where the ring last was, for [revalidateCursor]'s nearest retarget. */
    private val lastItemRect = Rect()

    private fun viewRectOnScreen(v: View, out: Rect): Boolean {
        if (!v.isShown || v.width <= 0 || v.height <= 0) return false
        v.getLocationOnScreen(tmpLoc)
        out.set(tmpLoc[0], tmpLoc[1], tmpLoc[0] + v.width, tmpLoc[1] + v.height)
        return true
    }

    /** Collected lazily per keypress — no registry to go stale. */
    private fun candidates(): List<Pair<View, Rect>> {
        val out = ArrayList<Pair<View, Rect>>()
        for (a in host.navActions()) {
            val r = Rect()
            if (viewRectOnScreen(a.view, r)) out.add(a.view to r)
        }
        return out
    }

    private fun setCursor(view: View, rectOnScreen: Rect) {
        cursorView = view
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
        val cur = cursorView ?: return
        val cands = candidates()
        val curIdx = cands.indexOfFirst { it.first === cur }
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

    /** Per-frame from the workspace's pre-draw hook: re-read the item's live
     *  rect (scroll, layout, entrance all move it), re-clip to the viewport. */
    fun syncRing() {
        val cur = cursorView ?: run {
            host.setRing(null, null)
            return
        }
        if (!viewRectOnScreen(cur, tmpRect)) {
            host.setRing(null, null)
            return
        }
        val clip = if (host.scrollViewportOnScreen(clipRect)) clipRect else null
        lastItemRect.set(tmpRect)
        host.setRing(tmpRect, clip)
    }

    /** The cursor is stale (page push/pop): drop it. */
    fun clearCursor() {
        cancelHold()
        cursorView = null
        host.setRing(null, null)
    }

    /** After a layout-changing activation: if the cursor's item left the
     *  screen, retarget to whatever now sits nearest where the ring last was. */
    fun revalidateCursor() {
        val cur = cursorView ?: return
        if (cur.isShown && host.navActions().any { it.view === cur }) return
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

    /** Teardown (dismissal): stop timers, hide the ring. */
    fun release() {
        cancelHold()
        stick.release()
        cursorView = null
        host.setRing(null, null)
    }

    private fun Rect.toNavRect() = SheetNavGeometry.NavRect(left, top, right, bottom)
}
