package com.playtranslate.ui

import android.content.Context
import android.graphics.Rect
import android.view.ActionMode
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.TextView
import androidx.annotation.VisibleForTesting
import com.playtranslate.overlay.CleanCaptureAware
import kotlin.math.hypot

/**
 * The root view of an overlay window that holds text fields: the capture
 * sheet's, the detached window's and the workspace's, each a FrameLayout
 * with touch and key rules of its own. What this gives them, once for the
 * three, is what a window's decor gives a text field everywhere else: its
 * Cut / Copy / Paste bar. Android draws that bar under a decor only (an
 * activity's, a dialog's), and a window added straight to the window manager
 * has none, so a field in one could select text and had no bar to act on it
 * with: no Paste at all, no Copy, no Cut. Here a text view asking for the bar
 * gets the app's ([OverlayTextToolbar]).
 *
 * A text view's ask only (an EditText's, a selectable TextView's): its items
 * and what they do are the platform TextView's, which leaves out Share and
 * the other apps' text actions in a window with no activity by itself
 * (TextView.canShare). Any other ask goes to the parents as before. A
 * WebView's selection, whose items (sharing, web search) have not been
 * checked in such a window, gets no bar, as it never has; so does an ask for
 * a primary action mode. And a root that is not its window's root, as a sheet
 * hosted in an activity is not, leaves the bar to the decor above it.
 *
 * And while the keyboard runs full screen, when Android refuses a field any
 * selection and so never asks for the bar, the root selects for it
 * ([OverlayTextSelection]): a long press reaches the root as the field's ask
 * for a context menu, which the root takes; a double tap it sees go by, and
 * takes once the field has had the lift.
 *
 * Every bar is a window of its own, attached to this one, and a clean
 * capture's blanking (which sets this window's alpha) leaves it as it is:
 * the root takes its bars down before one ([beforeCleanCapture]).
 */
open class OverlayWindowRoot(context: Context) : FrameLayout(context), CleanCaptureAware {

    /** The bars the root started that may still be up: each a window of its
     *  own, which a clean capture's blanking of this window does not reach
     *  ([beforeCleanCapture]). Pruned of finished ones at each start. */
    private val bars = mutableListOf<OverlayTextToolbar>()

    /** The selection the root made in one of its fields, while its bar is up. */
    private var selection: OverlayTextSelection? = null

    @get:VisibleForTesting
    internal val selectionForTest: OverlayTextSelection? get() = selection

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private val doubleTaps = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            private var downX = 0f
            private var downY = 0f

            override fun onDoubleTap(e: MotionEvent): Boolean {
                downX = e.x
                downY = e.y
                return false
            }

            override fun onDoubleTapEvent(e: MotionEvent): Boolean {
                if (e.actionMasked == MotionEvent.ACTION_UP && hypot(e.x - downX, e.y - downY) <= touchSlop) {
                    selectAtDoubleTap(e.x, e.y)
                }
                return false
            }
        },
    ).apply { setIsLongpressEnabled(false) }

    final override fun startActionModeForChild(
        originalView: View,
        callback: ActionMode.Callback,
        type: Int,
    ): ActionMode? {
        if (rootView !== this || type != ActionMode.TYPE_FLOATING || originalView !is TextView) {
            return super.startActionModeForChild(originalView, callback, type)
        }
        val bar = OverlayTextToolbar.start(originalView, callback) ?: return null
        bars.removeAll { it.isFinished }
        bars += bar
        return bar
    }

    /** A clean capture of this window's display is about to blank it: every
     *  bar still up comes down first, as if dismissed, its field's selection
     *  ending with it. Otherwise its window, which the blanking leaves as it
     *  is, would put "Cut Copy Paste" in the frame. */
    final override fun beforeCleanCapture() {
        val up = bars.toList()
        bars.clear()
        up.forEach { it.finish() }
    }

    /** A long press on a field Android refuses a selection: taken, so the
     *  field neither runs its own refused selection nor takes the lift as a
     *  tap that would move the cursor off the word. */
    final override fun showContextMenuForChild(originalView: View, x: Float, y: Float): Boolean {
        if (rootView !== this || originalView !is TextView || !OverlayTextSelection.refused(originalView)) {
            return super.showContextMenuForChild(originalView, x, y)
        }
        replaceSelection { OverlayTextSelection.atLongPress(originalView, x, y, ::selectionEnded) }
        return true
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val handled = super.dispatchTouchEvent(ev)
        // After the children: at a double tap's lift, the field has put its
        // cursor where the finger was by then, and the word replaces it.
        doubleTaps.onTouchEvent(ev)
        return handled
    }

    private fun selectAtDoubleTap(x: Float, y: Float) {
        if (rootView !== this) return
        val field = findFocus() as? TextView ?: return
        if (!OverlayTextSelection.refused(field)) return
        val shown = Rect()
        if (!field.getGlobalVisibleRect(shown) || !shown.contains(x.toInt(), y.toInt())) return
        val at = IntArray(2)
        field.getLocationInWindow(at)
        replaceSelection { OverlayTextSelection.atDoubleTap(field, x - at[0], y - at[1], ::selectionEnded) }
    }

    private inline fun replaceSelection(start: () -> OverlayTextSelection?) {
        selection?.finish()
        selection = start()
    }

    private fun selectionEnded(ended: OverlayTextSelection) {
        if (selection === ended) selection = null
    }
}
