package com.playtranslate.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.core.view.doOnLayout
import com.playtranslate.DrawRateProbe
import com.playtranslate.displaySizePx

/**
 * Owns every overlay window the app paints on a game display — the registry,
 * add/remove, and the clean-capture blanking that hides those windows from
 * screenshots.
 *
 * Extracted from PlayTranslateAccessibilityService so the same machinery backs
 * either capture backend. [windowType] is the only thing that differs: it is
 * `TYPE_ACCESSIBILITY_OVERLAY` when the accessibility service hosts the
 * windows, `TYPE_APPLICATION_OVERLAY` when MediaProjection mode does. Every
 * window added through [addOverlayWindow] is stamped with [windowType], so call
 * sites never pick a backend themselves.
 *
 * Main-thread only — every WindowManager mutation happens on Main.
 */
class OverlayHost(
    private val context: Context,
    val windowType: Int,
) {

    /** Registered overlay window. The stored handle keeps the wm + params so
     *  blanking can flip [WindowManager.LayoutParams.alpha] and call
     *  [WindowManager.updateViewLayout] without each call site managing its
     *  own state. */
    data class OverlayHandle(
        val view: View,
        val wm: WindowManager,
        val params: WindowManager.LayoutParams,
        val displayId: Int,
    )

    private val overlayWindows = mutableListOf<OverlayHandle>()

    /** Per-display 1×1 touch sentinels — see [addTouchSentinel]. */
    private val touchSentinels = mutableMapOf<Int, View>()

    /** One handle's snapshot for restore: the handle plus the alpha it was
     *  at before [prepareForCleanCapture] blanked it. Stored because not
     *  every overlay runs at α=1 — the MediaProjection live-pinhole window
     *  sits at the system obscuring cap (~0.79) with a compensated pinhole
     *  mask tuned to that exact alpha; resetting it to 1.0 on restore would
     *  trip the QTI BSP visual clamp and break the 50/50 blend math. */
    internal data class SavedHandle(
        val handle: OverlayHandle,
        val originalAlpha: Float,
    )

    /** Opaque snapshot returned by [prepareForCleanCapture]. The two flags
     *  are deliberately separate because their freshness-wait PROOFS differ
     *  and a consumer must know which one it holds. */
    class OverlayState internal constructor(
        internal val saved: List<SavedHandle>,
        /** This prepare blanked at least one visible window. The blank's own
         *  repaint is guaranteed to come AND lands after any anchor taken
         *  before the prepare call, so a wait predicated on that anchor
         *  terminates. */
        val blankedAnything: Boolean,
        /** A just-removed window is a defused-but-not-yet-composited
         *  [WindowChurnGate] ghost on this display — visible when defused,
         *  unregistered, surface still live until its alpha-0 commits. Its
         *  repaint is guaranteed to have been PRODUCED (visible → invisible
         *  is a content change) but may have been DELIVERED before any
         *  anchor a consumer takes now, so an anchored wait can starve on a
         *  static screen and needs a serve-current fallback. Ghosts that
         *  were already blanked when removed never set this: they composite
         *  nothing and are already absent from every frame. When both flags
         *  are false, no frame can contain this backend's overlays and no
         *  repaint of ours is coming — waiting would only burn budget. */
        val uncompositedGhost: Boolean,
    )

    /**
     * Add a window via [WindowManager.addView] AND register it for
     * clean-capture blanking. The window's type is forced to [windowType], so
     * the caller's params need not pick a backend. Returns true on success.
     *
     * Honors whatever [WindowManager.LayoutParams.alpha] is on [params] —
     * never forces alpha=1. Historically load-bearing for the icon
     * bring-to-front's same-params re-add (a capture-blanked icon had to
     * come back at alpha 0); that path now replaces its view with fresh
     * params, but the contract stays: a caller's pre-set alpha is a
     * deliberate statement about capture interplay, not a bug to correct.
     */
    fun addOverlayWindow(
        view: View,
        wm: WindowManager,
        params: WindowManager.LayoutParams,
        displayId: Int,
    ): Boolean {
        // Belt: a re-add of a view with a pending gated destroy would make
        // the addView below throw "view already attached", so flush it first.
        // No current caller re-adds a removed view (the icon re-raise
        // replaces its view instead — the flush's synchronous destroy is
        // release-adjacent to this add, the shape the gate prevents), so
        // this should never fire; it exists so a future same-view re-add
        // degrades to the old risk instead of crashing the add.
        WindowChurnGate.flushPendingFor(view)
        params.type = windowType
        // Service-added windows are software-rendered by default (the
        // manifest's hardwareAccelerated only covers activity windows).
        // Hardware acceleration is required for a WebView child
        // ([com.playtranslate.ui.YomitanDefinitionsView] in the lens/popup)
        // and harmless for canvas surfaces — the lens already pre-bakes its
        // one BlurMaskFilter into a bitmap for exactly this pipeline. The
        // flag is read at addView time only, so later wholesale
        // params.flags rewrites (resetToZoom, SheetHost.applyPolicy) can't
        // revoke it.
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        val fullScreen = params.width == WindowManager.LayoutParams.MATCH_PARENT &&
            params.height == WindowManager.LayoutParams.MATCH_PARENT
        applyFullScreenOverlayDefaults(params)
        // MATCH_PARENT sizing delegates to the platform, and that delegation is
        // measurably broken on both sides of the R boundary: below R it resolves
        // to the inset content area (short of the panel; overlay/capture scale
        // drifts off 1.0), and on R+ multi-display the first relayout after
        // addView can re-measure the frame against ANOTHER display's config
        // (Thor 2026-07-05: menu window 1920x1080 -> 1240x1080 nine ms after a
        // gear tap; 1240 = the app display's width). Pin every full-screen
        // overlay to its own display's explicit size so overlay == display by
        // construction on every API level.
        if (fullScreen) pinFullScreenSize(params, displayId)
        // A focusable window becomes the system-bar control target on focus,
        // and its default requested state is "bars visible" — which yanks the
        // nav pill (and status bar) back over an immersive game the moment a
        // popup opens. Read the game's current bar state BEFORE this window
        // exists, and arm the same request on it right after addView so the
        // request is already recorded when focus lands.
        val focusable = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE == 0
        val hiddenBars = if (focusable) hiddenSystemBarsOnDisplay(displayId) else null
        return try {
            wm.addView(view, params)
            WindowChurnGate.noteWindowAdded()
            if (focusable) mirrorSystemBars(view, hiddenBars)
            overlayWindows += OverlayHandle(view, wm, params, displayId)
            logOverlayGeometry(view, params, displayId, fullScreen)
            logFocusableOverlay("add", view, params, displayId)
            DrawRateProbe.attach(view, "${view.javaClass.simpleName}@d$displayId")
            true
        } catch (e: Exception) {
            Log.w(TAG, "addOverlayWindow failed: ${e.message}")
            false
        }
    }

    /** Pin [params] to [displayId]'s current pixel size, replacing
     *  MATCH_PARENT (see [addOverlayWindow] for why platform sizing can't be
     *  trusted). The size comes from the window's OWN display — resolved by
     *  [displayId], never a hardcoded display — via the window-context query.
     *  Cost of pinning: the window no longer follows a rotation by itself;
     *  [resizeFullScreenOverlayForDisplay] re-pins from the display-change
     *  path. Returns true if a size was pinned. */
    private fun pinFullScreenSize(
        params: WindowManager.LayoutParams,
        displayId: Int,
    ): Boolean {
        val display = context.getSystemService(DisplayManager::class.java)
            ?.getDisplay(displayId) ?: return false
        val size = context.createDisplayContext(display).displaySizePx()
        if (size.x <= 0 || size.y <= 0) return false
        params.width = size.x
        params.height = size.y
        params.gravity = Gravity.TOP or Gravity.START
        return true
    }

    /** Re-pin an open full-screen overlay's window to [displayId]'s current
     *  size after a display change. [addOverlayWindow] froze the window to an
     *  explicit pixel size that does NOT follow the display through a
     *  rotation, so a portrait→landscape turn would leave it at the old width
     *  — and anything re-anchored against the new bounds (e.g. a right-edge
     *  floating menu) could land off the still-old-sized surface. Driven from
     *  the display-change listener, so a size pinned during a transient
     *  reconfiguration (the ~1s natural-portrait blip on Thor) is corrected by
     *  the next event through the same path that introduced it. The view must
     *  have been added as a full-screen overlay. */
    fun resizeFullScreenOverlayForDisplay(view: View, displayId: Int) {
        val handle = overlayWindows.firstOrNull { it.view === view } ?: return
        if (!pinFullScreenSize(handle.params, displayId)) return
        try { handle.wm.updateViewLayout(view, handle.params) } catch (_: Exception) {}
    }

    /**
     * Which system bars (status/navigation) the app under our overlays
     * currently has hidden, as a [WindowInsets.Type] mask — read from the
     * last insets dispatch any registered window on [displayId] received.
     * Null when unknowable: no attached window to read from, or pre-R
     * (per-type visibility only exists in the R insets model, and the
     * legacy approximation reads a NO_LIMITS window's zero insets as
     * "hidden", which would blanket-hide bars the game never hid).
     *
     * Only meaningful as a picture of the GAME's state while no focusable
     * overlay of ours holds the bar control — but every focusable overlay we
     * add mirrors the state it observed (see [addOverlayWindow] /
     * [mirrorSystemBars]), so inductively the global state under any stack of
     * our windows still equals the game's own request.
     */
    fun hiddenSystemBarsOnDisplay(displayId: Int): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        for (handle in overlayWindows) {
            if (handle.displayId != displayId) continue
            return hiddenSystemBars(handle.view) ?: continue
        }
        return null
    }

    /**
     * Log only when a focusable overlay is added/removed — every other overlay
     * the app paints (icon, sentinel, box overlays, lens-zoom) is
     * NOT_FOCUSABLE and shouldn't intercept input. The rare focusable ones
     * (WordLookupPopup, interactive MagnifierLens) DO intercept key + motion
     * events while up, and we want a paper trail when investigating "the
     * controller stopped working" reports.
     */
    private fun logFocusableOverlay(
        kind: String,
        view: View,
        params: WindowManager.LayoutParams,
        displayId: Int,
    ) {
        if (params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0) return
        val name = view.javaClass.simpleName.ifBlank { "anon-View@${view.hashCode().toString(16)}" }
        Log.i(TAG, "[FocusableOverlay $kind] $name displayId=$displayId flags=0x${params.flags.toString(16)}")
    }

    // One-shot diagnostic: verifies whether MATCH_PARENT overlay windows
    // actually cover the full display — a view origin != display origin or
    // view dims != display dims silently miscalibrates OCR-box overlays.
    // Grep with: adb logcat -s OverlayHost | grep Geometry
    private fun logOverlayGeometry(
        view: View,
        params: WindowManager.LayoutParams,
        displayId: Int,
        fullScreen: Boolean,
    ) {
        // [fullScreen] is the caller's MATCH_PARENT intent, captured *before*
        // addOverlayWindow may pin an explicit size on API < 30.
        if (!fullScreen) return
        view.doOnLayout {
            val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(displayId)
            val ds = if (display != null)
                context.createDisplayContext(display).displaySizePx()
            else Point()
            val loc = IntArray(2)
            view.getLocationOnScreen(loc)
            val name = view.javaClass.simpleName.ifBlank { "anon-View@${view.hashCode().toString(16)}" }
            val flagBits = listOfNotNull(
                "NO_LIMITS".takeIf { params.flags and WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS != 0 },
                "IN_SCREEN".takeIf { params.flags and WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN != 0 },
                "NOT_TOUCHABLE".takeIf { params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0 },
            ).joinToString("|").ifEmpty { "none" }
            val matches = view.width == ds.x && view.height == ds.y && loc[0] == 0 && loc[1] == 0
            Log.i(
                TAG,
                "[Geometry] $name displayId=$displayId display=${ds.x}x${ds.y} " +
                    "view=${view.width}x${view.height} origin=(${loc[0]},${loc[1]}) " +
                    "matchesDisplay=$matches flags=$flagBits"
            )
        }
    }

    /** A display-scoped Context derived from THIS host's context — the only
     *  context that can add windows of this host's [windowType]
     *  (TYPE_ACCESSIBILITY_OVERLAY is tied to the accessibility service's
     *  context). For probe-class windows that are deliberately NOT registered
     *  through [addOverlayWindow]: an unregistered window is invisible to
     *  [prepareForCleanCapture]'s blanking, which is exactly what a capture
     *  probe needs — a concurrent clean capture must not be able to blank the
     *  probe mid-measurement. Callers own the addView/removeView lifecycle. */
    fun displayContextFor(displayId: Int): Context? {
        val display = context.getSystemService(DisplayManager::class.java)
            ?.getDisplay(displayId) ?: return null
        return context.createDisplayContext(display)
    }

    /** Unregister and remove. Returns true if the view was registered (and
     *  thus removed). Returns false if the view was never registered — callers
     *  that fall back to a direct removeView for windows added before a host
     *  existed rely on this.
     *
     *  Removal is routed through [WindowChurnGate]: the window is defused
     *  synchronously (window alpha 0 — invisible to eyes AND captures by the
     *  same SurfaceFlinger mechanism clean-capture blanking trusts —
     *  untouchable, unfocusable) and its surface destroyed in the next
     *  add-quiet gap, keeping our surface releases away from our surface
     *  creations (the Thor firmware crash — see the gate's docs).
     *
     *  [immediate] used to mean [WindowManager.removeViewImmediate] so a
     *  clean capture started right after couldn't race the leftover visible
     *  surface (the floating menu's dim/hint in live mode's first frame).
     *  That guarantee is now structural instead: a defused ghost arms
     *  [OverlayState.awaitRepaint] until its alpha-0 has provably composited
     *  ([WindowChurnGate.hasUncompositedGhostOn]), so the capture paths wait
     *  for a post-defuse frame instead of relying on synchronous teardown.
     *  Both variants defuse identically; the flag is vestigial. */
    fun removeOverlayWindow(view: View, immediate: Boolean = false): Boolean {
        val handle = overlayWindows.firstOrNull { it.view === view } ?: return false
        overlayWindows -= handle
        // Log BEFORE the gate: its defuse sets NOT_FOCUSABLE on these params,
        // which would make every removal read as non-focusable and silence
        // the focusable paper trail.
        logFocusableOverlay("remove", view, handle.params, handle.displayId)
        WindowChurnGate.removeWindow(view, handle.wm, handle.params, handle.displayId)
        return true
    }

    /**
     * Add a 1×1 transparent watcher window on [displayId]. With
     * FLAG_WATCH_OUTSIDE_TOUCH it receives an ACTION_OUTSIDE — running
     * [onOutsideTouch] — for every touch elsewhere on the display, without
     * consuming the event, so the game still gets normal input. The window
     * carries [windowType], so this works on either backend. Idempotent per
     * display.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun addTouchSentinel(displayId: Int, onOutsideTouch: () -> Unit) {
        if (displayId in touchSentinels) return
        val display = context.getSystemService(DisplayManager::class.java)
            ?.getDisplay(displayId) ?: return
        val displayContext = context.createDisplayContext(display)
        val wm = displayContext.getSystemService(WindowManager::class.java) ?: return
        val view = View(displayContext)
        view.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) onOutsideTouch()
            false
        }
        val params = WindowManager.LayoutParams(
            1, 1,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        )
        if (addOverlayWindow(view, wm, params, displayId)) {
            touchSentinels[displayId] = view
        }
    }

    /** Remove [displayId]'s touch sentinel, if one is registered. */
    fun removeTouchSentinel(displayId: Int) {
        val view = touchSentinels.remove(displayId) ?: return
        removeOverlayWindow(view)
    }

    /** Remove every touch sentinel — e.g. when live mode stops entirely. */
    fun removeAllTouchSentinels() {
        for (view in touchSentinels.values.toList()) removeOverlayWindow(view)
        touchSentinels.clear()
    }

    /**
     * Hide every registered overlay on [displayId] so it doesn't appear in a
     * screenshot of that display. Overlays on other displays are left alone —
     * blanking them would flicker every cycle when N displays are captured in
     * turn.
     *
     * Uses [WindowManager.LayoutParams.alpha] (window-level, applied by
     * SurfaceFlinger during composition) rather than [View.alpha] (applied
     * during view drawing, which can lag a frame behind). Combined with the
     * 2-vsync wait in the capture path, this reliably composites the
     * overlay-free frame before capture.
     *
     * Skips handles already at alpha=0 — they belong to a concurrent in-flight
     * capture that hasn't restored yet. Including them would let our restore
     * re-show overlays another capture still needs hidden.
     */
    fun prepareForCleanCapture(displayId: Int): OverlayState {
        val saved = mutableListOf<SavedHandle>()
        for (handle in overlayWindows) {
            if (handle.displayId != displayId) continue
            if (handle.params.alpha == 0f) continue
            val originalAlpha = handle.params.alpha
            handle.params.alpha = 0f
            try {
                handle.wm.updateViewLayout(handle.view, handle.params)
                saved += SavedHandle(handle, originalAlpha)
            } catch (_: Exception) {
                // Roll back the params mutation so the in-memory state still
                // reflects what's on screen.
                handle.params.alpha = originalAlpha
            }
        }
        return OverlayState(
            saved,
            blankedAnything = saved.isNotEmpty(),
            uncompositedGhost = WindowChurnGate.hasUncompositedGhostOn(displayId),
        )
    }

    /** Restores blanked overlays to the alpha they had before
     *  [prepareForCleanCapture] blanked them. Most overlays were at α=1.0
     *  and come back there; the MediaProjection live-pinhole window is the
     *  only current exception (returns to the system obscuring cap). */
    fun restoreAfterCapture(state: OverlayState) {
        for (saved in state.saved) {
            // A window removed between prepare and restore is a defused ghost
            // lingering in [WindowChurnGate] — still ATTACHED, so the
            // updateViewLayout below would succeed and resurrect a dead
            // overlay at full alpha. Don't touch its SCREEN state — but its
            // BOOKKEEPING must still be restored (the old code did this
            // implicitly: the params write preceded the try, so a detached
            // window's throw still left the object corrected). Two writes,
            // both required: the in-memory params.alpha, and the gate ghost's
            // own restore-snapshot — defused mid-blank, it captured our 0,
            // and the deferred destroy writes it back into this same params
            // object LAST, so correcting only the object here would be
            // clobbered. Without both, a later re-add reusing the params
            // object (icon bring-to-front) attaches invisible.
            // Matched by VIEW, not handle identity: a same-view re-add
            // registers a fresh handle whose window would still need its
            // blanked alpha restored on screen below. (No current caller
            // re-adds a removed view — the icon re-raise replaces instead —
            // but the guard must stay correct if one appears.)
            if (overlayWindows.none { it.view === saved.handle.view }) {
                saved.handle.params.alpha = saved.originalAlpha
                WindowChurnGate.correctSnapshotAlpha(saved.handle.view, saved.originalAlpha)
                continue
            }
            saved.handle.params.alpha = saved.originalAlpha
            try {
                saved.handle.wm.updateViewLayout(saved.handle.view, saved.handle.params)
            } catch (_: Exception) {}
        }
    }

    /** Remove and unregister every window. Used on host teardown — service
     *  unbind, or a backend swap. Idempotent. */
    fun removeAll() {
        val handles = overlayWindows.toList()
        overlayWindows.clear()
        touchSentinels.clear()
        for (h in handles) {
            WindowChurnGate.removeWindow(h.view, h.wm, h.params, h.displayId)
        }
    }

    companion object {
        private const val TAG = "OverlayHost"

        /**
         * Defensive defaults for full-display (MATCH_PARENT × MATCH_PARENT)
         * overlays — they must cover the whole display so OCR-box coordinates,
         * which are in capture-bitmap pixels, map 1:1 onto the overlay.
         *
         * The SIZE itself is pinned to explicit display pixels in
         * [addOverlayWindow] (platform MATCH_PARENT sizing is untrustworthy —
         * see there). These flags handle the POSITION/inset side: without
         * `fitInsetsTypes = 0` a non-focusable TYPE_APPLICATION_OVERLAY (the
         * translation overlay) is laid out inside the system-bar insets —
         * offset from the capture — which shifts every box.
         * `FLAG_LAYOUT_NO_LIMITS` alone does not prevent that; the cutout mode
         * (`..._ALWAYS` from API 30, `..._SHORT_EDGES` on 29) covers the display
         * cutout.
         *
         * Idempotent. Honors callers that explicitly set a non-DEFAULT cutout
         * mode — only the DEFAULT case is upgraded.
         */
        /** Per-type mask of the system bars currently hidden, read from
         *  [view]'s last-received window insets. [view] must be attached.
         *  Null pre-R or before the first insets dispatch. See
         *  [hiddenSystemBarsOnDisplay] for why pre-R stays null. */
        fun hiddenSystemBars(view: View): Int? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
            val insets = view.rootWindowInsets ?: return null
            var hidden = 0
            if (!insets.isVisible(WindowInsets.Type.statusBars())) {
                hidden = hidden or WindowInsets.Type.statusBars()
            }
            if (!insets.isVisible(WindowInsets.Type.navigationBars())) {
                hidden = hidden or WindowInsets.Type.navigationBars()
            }
            return hidden
        }

        /**
         * Arm [view]'s window with the same system-bar visibility the app
         * beneath maintains ([hiddenTypes], from [hiddenSystemBars] /
         * [hiddenSystemBarsOnDisplay]) so the window can take input focus —
         * and with it the bar control — without flashing the nav pill or
         * status bar over an immersive game. Call on an ATTACHED window,
         * BEFORE the focus grant lands (right after addView, or before the
         * updateViewLayout that clears FLAG_NOT_FOCUSABLE): requested
         * visibility is per-window state the system applies when the window
         * becomes the control target.
         *
         * [hiddenTypes] == null (state unknowable) leaves the window's
         * default request — today's behavior. Bars the app shows are
         * explicitly show()n so a re-arm can clear a stale hide.
         */
        fun mirrorSystemBars(view: View, hiddenTypes: Int?) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || hiddenTypes == null) return
            val controller = view.windowInsetsController ?: return
            val allBars = WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars()
            val showTypes = allBars and hiddenTypes.inv()
            if (hiddenTypes != 0) {
                // The game's own semantics: hidden bars stay swipeable, and a
                // swipe reveals them transiently rather than re-showing them.
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(hiddenTypes)
            }
            if (showTypes != 0) controller.show(showTypes)
            Log.i(
                TAG,
                "[BarMirror] ${view.javaClass.simpleName} hidden=0x${hiddenTypes.toString(16)}"
            )
        }

        fun applyFullScreenOverlayDefaults(params: WindowManager.LayoutParams) {
            val fullScreen =
                params.width == WindowManager.LayoutParams.MATCH_PARENT &&
                    params.height == WindowManager.LayoutParams.MATCH_PARENT
            if (!fullScreen) return
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // fitInsetsTypes=0 keeps the window origin out of the
                // system-bar insets; the size is pinned in addOverlayWindow.
                params.fitInsetsTypes = 0
            } else {
                // Pre-30 has no fitInsetsTypes. FLAG_LAYOUT_IN_SCREEN pins the
                // window ORIGIN to the screen top-left (under the status bar);
                // [addOverlayWindow] pins an explicit full-display SIZE. Together
                // (origin 0,0 + size == capture) the overlay matches the capture
                // bitmap so OCR boxes align.
                @Suppress("DEPRECATION")
                params.flags = params.flags or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            }
            if (params.layoutInDisplayCutoutMode ==
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
            ) {
                // ALWAYS (extend into the cutout on ALL edges) is API 30+. On 29 — our
                // minSdk — it isn't a mode the window manager recognises, so assigning it
                // there left the window on DEFAULT: no cutout coverage at all, silently
                // breaking the very 1:1 overlay/capture mapping this method exists to
                // guarantee. SHORT_EDGES is the pre-30 spelling of the same intent, and
                // it's sufficient in practice: Android only permits cutouts on the short
                // edges of a display, so "short edges" and "all edges" pick out the same
                // region on any device that actually has one.
                params.layoutInDisplayCutoutMode =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    } else {
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    }
            }
        }
    }
}
