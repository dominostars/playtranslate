package com.playtranslate.ui

import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import com.playtranslate.overlay.OverlayHost

/**
 * Where a full-screen overlay panel lives: an overlay WINDOW over another
 * app (the floating-icon capture flow) or a plain child view inside an
 * activity (the camera tool's snapshot panel). The panel's view tree and
 * behavior are host-agnostic; only attachment, removal, and the focus/IME
 * plumbing (in-place edits, controller navigation) differ. Consumers:
 * [CaptureResultOverlay] (the capture sheet) and [OverlayWorkspace] (the
 * floating workspace).
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

    /** Convert the dismissed sheet into an invisible key sink so it can
     *  outlive its UI while a controller key is still held: hidden content,
     *  untouchable (touches fall through to the app beneath), focusability
     *  untouched — held keys keep landing here until released (the
     *  orphaned-UP guard, [WindowKeyPairGuard]). Returns false when the host
     *  has no window of its own to linger (activity hosting): the activity
     *  window survives the sheet, so pending keys cannot orphan and the
     *  caller detaches immediately. */
    fun beginKeySink(root: View): Boolean

    /** Temporarily park the window while a helper Activity runs above the
     *  game (the workspace's audio picker): hidden, untouchable, and
     *  non-focusable so the activity owns the screen and its input. Unpark
     *  restores visibility and touchability; the caller re-applies its own
     *  focus policy after ([setFocusPolicy]). Activity hosting is a no-op —
     *  a launched activity naturally covers the host activity. */
    fun setParked(root: View, parked: Boolean) {}
}

/** The over-game host: a full-screen overlay window whose type is stamped by
 *  [OverlayHost] (accessibility vs MediaProjection backend). */
class WindowSheetHost(
    private val wm: WindowManager,
    private val displayId: Int,
    private val overlayHost: OverlayHost,
) : SheetHost {

    private var params: WindowManager.LayoutParams? = null

    /** System bars the game had hidden when the sheet attached
     *  ([OverlayHost.hiddenSystemBars] mask). Latched ONCE, at attach, and
     *  re-armed on every later flip to focusable — a fresh read at flip time
     *  would be poisoned in the unpark flow, where the helper Activity that
     *  parked us (bars visible) is still on top when the caller restores our
     *  focus policy. Attach always happens directly over the game, so the
     *  attach-time read is the clean one. */
    private var gameHiddenBars: Int? = null

    override fun attach(root: View, screenW: Int, screenH: Int, focusable: Boolean) {
        // Read before our own window exists: once a focusable sheet holds the
        // bar control, the observable state is our request, not the game's.
        gameHiddenBars = overlayHost.hiddenSystemBarsOnDisplay(displayId)
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
        // addOverlayWindow itself mirrors the bars onto an attach-time
        // focusable window; later flips re-arm from the latch below.
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
        if (focusable) {
            // Attach found no window to read from (first overlay up on this
            // display): fall back to our own root's insets — we're currently
            // non-focusable, so they still reflect the game's state.
            if (gameHiddenBars == null) gameHiddenBars = OverlayHost.hiddenSystemBars(root)
            // Arm before the flag change so the request is already recorded
            // when the focus grant makes this window the bar control target.
            OverlayHost.mirrorSystemBars(root, gameHiddenBars)
        }
        applyPolicy(lp, focusable, wantsIme)
        try {
            wm.updateViewLayout(root, lp)
        } catch (_: Exception) {
        }
    }

    override fun beginKeySink(root: View): Boolean {
        val lp = params ?: return false
        root.visibility = View.INVISIBLE
        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return try {
            wm.updateViewLayout(root, lp)
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun setParked(root: View, parked: Boolean) {
        val lp = params ?: return
        root.visibility = if (parked) View.INVISIBLE else View.VISIBLE
        lp.flags = if (parked) {
            lp.flags or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        } else {
            // Touchability back on; focus flags are the caller's to restore
            // via setFocusPolicy (which rewrites the whole flag set anyway).
            lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
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

    // The activity window outlives the sheet child — held keys keep landing
    // in the activity, so there is nothing to linger.
    override fun beginKeySink(root: View): Boolean = false
}
