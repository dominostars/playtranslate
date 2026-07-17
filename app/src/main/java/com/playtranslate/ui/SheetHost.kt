package com.playtranslate.ui

import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import com.playtranslate.overlay.OverlayHost

/**
 * Where a [CaptureResultOverlay] sheet lives: an overlay WINDOW over another
 * app (the floating-icon capture flow) or a plain child view inside an
 * activity (the camera tool's snapshot panel). The sheet's view tree and
 * behavior are host-agnostic; only attachment, removal, and the in-place
 * edit's focus/IME plumbing differ.
 */
interface SheetHost {
    /** Attach the sheet's full-screen [root]. Called once, from show(). */
    fun attach(root: View, screenW: Int, screenH: Int)

    /** Remove [root]; must tolerate a root that never attached. */
    fun detach(root: View)

    /** Focus + IME policy for the in-place edit: window hosting must flip
     *  window flags (overlay windows are created non-focusable); activity
     *  hosting is a no-op — the activity window is already focusable and
     *  owns its own softInputMode. */
    fun setFocusable(root: View, focusable: Boolean)
}

/** The over-game host: a full-screen overlay window whose type is stamped by
 *  [OverlayHost] (accessibility vs MediaProjection backend). */
class WindowSheetHost(
    private val wm: WindowManager,
    private val displayId: Int,
    private val overlayHost: OverlayHost,
) : SheetHost {

    private var params: WindowManager.LayoutParams? = null

    override fun attach(root: View, screenW: Int, screenH: Int) {
        val lp = WindowManager.LayoutParams(
            screenW, screenH,
            0, // type stamped by OverlayHost
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
        params = lp
        overlayHost.addOverlayWindow(root, wm, lp, displayId)
    }

    override fun detach(root: View) {
        try {
            overlayHost.removeOverlayWindow(root)
        } catch (_: Exception) {
        }
    }

    override fun setFocusable(root: View, focusable: Boolean) {
        val lp = params ?: return
        lp.flags = if (focusable) {
            lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        lp.softInputMode = if (focusable) {
            // ALWAYS_VISIBLE, not STATE_VISIBLE: the window only becomes focusable
            // asynchronously via the updateViewLayout below, and ALWAYS_VISIBLE makes
            // the system raise the IME the instant the window actually gains focus.
            // STATE_VISIBLE wasn't reliably re-evaluated on that focus transition, so
            // the keyboard only appeared once the user tapped into the field.
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        } else {
            WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
        }
        try {
            wm.updateViewLayout(root, lp)
        } catch (_: Exception) {
        }
    }
}

/** The in-app host: the sheet root becomes a child of [parent] (a full-screen
 *  FrameLayout in the hosting activity). */
class ActivitySheetHost(private val parent: ViewGroup) : SheetHost {

    override fun attach(root: View, screenW: Int, screenH: Int) {
        parent.addView(
            root,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    override fun detach(root: View) {
        parent.removeView(root)
    }

    override fun setFocusable(root: View, focusable: Boolean) {
        // Activity windows are always focusable; the IME rides the activity's
        // own softInputMode and the sheet's existing ime-inset lift.
    }
}
