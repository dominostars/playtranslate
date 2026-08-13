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
 * behavior are host-agnostic; only attachment, removal, and the focus/IME
 * plumbing (the in-place edit, controller navigation) differ.
 */
interface SheetHost {
    /** Attach the sheet's full-screen [root]. Called once, from show().
     *  [focusable] creates the window already holding key focus — controller
     *  navigation — instead of flipping it on asynchronously afterward. */
    fun attach(root: View, screenW: Int, screenH: Int, focusable: Boolean = false)

    /** Remove [root]; must tolerate a root that never attached. */
    fun detach(root: View)

    /** Focus + IME policy. [focusable] = the window takes key/motion input at
     *  all (the in-place edit OR controller navigation); [wantsIme] = that
     *  focus is for text entry, so the IME should come up — false holds key
     *  focus WITHOUT ever becoming the IME target. Window hosting flips window
     *  flags (overlay windows default non-focusable); activity hosting is a
     *  no-op — the activity window is already focusable and owns its own
     *  softInputMode. */
    fun setFocusPolicy(root: View, focusable: Boolean, wantsIme: Boolean)
}

/** The over-game host: a full-screen overlay window whose type is stamped by
 *  [OverlayHost] (accessibility vs MediaProjection backend). */
class WindowSheetHost(
    private val wm: WindowManager,
    private val displayId: Int,
    private val overlayHost: OverlayHost,
) : SheetHost {

    private var params: WindowManager.LayoutParams? = null

    override fun attach(root: View, screenW: Int, screenH: Int, focusable: Boolean) {
        val lp = WindowManager.LayoutParams(
            screenW, screenH,
            0, // type stamped by OverlayHost
            0, // flags applied below
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
        applyPolicy(lp, focusable = focusable, wantsIme = false)
        params = lp
        overlayHost.addOverlayWindow(root, wm, lp, displayId)
    }

    override fun detach(root: View) {
        try {
            overlayHost.removeOverlayWindow(root)
        } catch (_: Exception) {
        }
    }

    override fun setFocusPolicy(root: View, focusable: Boolean, wantsIme: Boolean) {
        val lp = params ?: return
        applyPolicy(lp, focusable, wantsIme)
        try {
            wm.updateViewLayout(root, lp)
        } catch (_: Exception) {
        }
    }

    private fun applyPolicy(lp: WindowManager.LayoutParams, focusable: Boolean, wantsIme: Boolean) {
        // HARDWARE_ACCELERATED is only read at addView (OverlayHost stamps
        // it there); kept in the base set so this wholesale rewrite doesn't
        // leave params lying about the window's actual pipeline.
        var flags = WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        if (!focusable) {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        } else if (!wantsIme) {
            // Key focus without the IME: controller navigation holds the
            // window's input focus for keys + stick motion, but the window
            // must never become the IME target — else any later focus churn
            // could raise a keyboard over the game.
            flags = flags or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
        }
        lp.flags = flags
        lp.softInputMode = if (wantsIme) {
            // ALWAYS_VISIBLE, not STATE_VISIBLE: the window only becomes focusable
            // asynchronously via updateViewLayout, and ALWAYS_VISIBLE makes
            // the system raise the IME the instant the window actually gains focus.
            // STATE_VISIBLE wasn't reliably re-evaluated on that focus transition, so
            // the keyboard only appeared once the user tapped into the field.
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        } else {
            WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
        }
    }
}

/** The in-app host: the sheet root becomes a child of [parent] (a full-screen
 *  FrameLayout in the hosting activity). */
class ActivitySheetHost(private val parent: ViewGroup) : SheetHost {

    override fun attach(root: View, screenW: Int, screenH: Int, focusable: Boolean) {
        parent.addView(
            root,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        // A window root receives insets on window attach; a child added
        // mid-tree does NOT get a dispatch of its own. Without this the
        // sheet's nav-bar buffer (and the sliver's rest position above the
        // gesture pill) reads 0 and the sliver parks flush with the screen
        // bottom.
        root.requestApplyInsets()
    }

    override fun detach(root: View) {
        parent.removeView(root)
    }

    override fun setFocusPolicy(root: View, focusable: Boolean, wantsIme: Boolean) {
        // Activity windows are always focusable; the IME rides the activity's
        // own softInputMode and the sheet's existing ime-inset lift.
    }
}
