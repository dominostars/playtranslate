package com.playtranslate.overlay

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
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
import androidx.annotation.RequiresApi
import androidx.core.view.doOnLayout
import com.playtranslate.DrawRateProbe
import com.playtranslate.displaySizePx
import com.playtranslate.displayWindowMetrics
import com.playtranslate.overlay.OwnWindows

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
     *  own state. Compared by identity. */
    class OverlayHandle(
        val view: View,
        val wm: WindowManager,
        val params: WindowManager.LayoutParams,
        val displayId: Int,
        val placement: OverlayPlacement,
        /** True when the window asked for MATCH_PARENT and [addOverlayWindow]
         *  pinned it to the display's pixel size, so a size change must
         *  re-pin it ([refresh]). */
        val pinned: Boolean,
    ) {
        /** The environment this window was last laid out against. Per
         *  window, not per display: a window added while the display is
         *  mid-change (the Thor's ~1 s natural-portrait blip, an icon
         *  re-raised every live cycle) records what it saw, and the next
         *  refresh finds it different and lays it out again. */
        var laidOutWith: OverlayEnvironment? = null

        /** The environment moves this window: it is pinned to the display's
         *  size, or the host places it. */
        val followsEnvironment: Boolean
            get() = pinned || placement is OverlayPlacement.Placed
    }

    private val overlayWindows = mutableListOf<OverlayHandle>()

    /** Per-display 1×1 touch sentinels — see [addTouchSentinel]. */
    private val touchSentinels = mutableMapOf<Int, View>()

    /** Per display, the clean captures in flight, from [prepareForCleanCapture]
     *  to [restoreAfterCapture]. A window added meanwhile starts blanked and
     *  comes back with the newest of them, so it can't reach a frame stamped
     *  free of our overlays (Codex 2026-09-27: a translation-error pill
     *  reported mid-capture could). Every capture site restores in a
     *  `finally`, so an entry never outlives its capture. */
    private val capturesInFlight = mutableMapOf<Int, MutableList<OverlayState>>()

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
        internal val displayId: Int,
        /** Mutable so a window added while this capture is in flight joins
         *  its restore ([addOverlayWindow]). */
        internal val saved: MutableList<SavedHandle>,
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
     * One exception, for the length of a clean capture of [displayId]: a
     * window added then goes up blanked and returns to its alpha with the
     * capture's restore ([capturesInFlight]).
     */
    fun addOverlayWindow(
        view: View,
        wm: WindowManager,
        params: WindowManager.LayoutParams,
        displayId: Int,
        placement: OverlayPlacement,
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
        applyOverlayLayoutDefaults(params, placement)
        // The display as it is now: a pinned window takes its size, a Placed
        // window is laid out against it before it exists, so it appears in
        // place.
        val env = if (fullScreen || placement is OverlayPlacement.Placed) environment(displayId) else null
        // MATCH_PARENT sizing delegates to the platform, and that delegation is
        // measurably broken on both sides of the R boundary: below R it resolves
        // to the inset content area (short of the panel; overlay/capture scale
        // drifts off 1.0), and on R+ multi-display the first relayout after
        // addView can re-measure the frame against ANOTHER display's config
        // (Thor 2026-07-05: menu window 1920x1080 -> 1240x1080 nine ms after a
        // gear tap; 1240 = the app display's width). Pin every full-screen
        // overlay to its own display's explicit size so overlay == display by
        // construction on every API level. WRAP_CONTENT widths go through the
        // same delegation (Thor 2026-09-26: the translation-error pills' wrap
        // window came out 1240px wide on the 1920px screen), so a window whose
        // content can be wider than the narrowest display pins its own width
        // (TranslationErrorPills does). Cost of pinning: the window no longer
        // follows a rotation by itself; [refresh] re-pins it.
        val pinned = fullScreen && env != null
        if (pinned) pin(params, env!!)
        if (env != null && placement is OverlayPlacement.Placed) placement.layout(env, params)
        // A Placed window that is dispatched the bars (below them; on Android
        // 11 every window) hears the app beneath show or hide one through its
        // own insets: every source change is dispatched to it, whatever its
        // frame (InsetsController.onStateChanged ->
        // ViewRootImpl.notifyInsetsChanged -> dispatchApplyInsets,
        // android-16.0.0_r1). The signal exists exactly while a window that
        // needs it does. The view's own onApplyWindowInsets still runs.
        if (placement is OverlayPlacement.Placed && hearsBars) {
            view.setOnApplyWindowInsetsListener { v, insets ->
                onBarsHeard(displayId, insets)
                v.onApplyWindowInsets(insets)
            }
        }
        // A focusable window becomes the system-bar control target on focus,
        // and its default requested state is "bars visible" — which yanks the
        // nav pill (and status bar) back over an immersive game the moment a
        // popup opens. Read the game's current bar state BEFORE this window
        // exists, and arm the same request on it right after addView so the
        // request is already recorded when focus lands.
        val focusable = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE == 0
        val hiddenBars = if (focusable) hiddenSystemBarsOnDisplay(displayId) else null
        // A clean capture of this display is in flight: go up blanked and
        // come back with it, or this window lands in its frame.
        val capture = capturesInFlight[displayId]?.lastOrNull()
        val requestedAlpha = params.alpha
        if (capture != null) params.alpha = 0f
        return try {
            wm.addView(view, params)
            if (focusable) mirrorSystemBars(view, hiddenBars)
            val handle = OverlayHandle(view, wm, params, displayId, placement, pinned)
            handle.laidOutWith = env
            overlayWindows += handle
            capture?.saved?.add(SavedHandle(handle, requestedAlpha))
            logOverlayGeometry(view, params, displayId, fullScreen)
            logFocusableOverlay("add", view, params, displayId)
            DrawRateProbe.attach(view, "${view.javaClass.simpleName}@d$displayId")
            // This window saw a display its neighbours were not laid out
            // against (a change whose own event is still on its way): bring
            // them to the same environment now.
            if (env != null && overlayWindows.any {
                    it.displayId == displayId && it !== handle && it.followsEnvironment && it.laidOutWith != env
                }
            ) {
                refresh(displayId, "add", force = false)
            }
            true
        } catch (e: Exception) {
            params.alpha = requestedAlpha
            Log.w(TAG, "addOverlayWindow failed: ${e.message}")
            false
        }
    }

    /** Pin [params] to [env]'s pixel size, replacing MATCH_PARENT (see
     *  [addOverlayWindow] for why platform sizing can't be trusted). The size
     *  comes from the window's OWN display's environment, never a hardcoded
     *  display. */
    private fun pin(params: WindowManager.LayoutParams, env: OverlayEnvironment) {
        params.width = env.geometry.width
        params.height = env.geometry.height
        params.gravity = Gravity.TOP or Gravity.START
    }

    /** True for a host whose windows are dispatched the system bars and so
     *  hear them change through their insets: below the bars
     *  (MediaProjection) from R, and on Android 11 the accessibility
     *  backend's too ([OverlayEnvironment.hearsBars]). Below R there are no
     *  per-type insets to place against. */
    private val hearsBars: Boolean
        get() = OverlayEnvironment.hearsBars(
            belowBars = windowType == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        )

    /** The display read behind [environment]; tests substitute one. */
    internal var environmentReader: (displayId: Int) -> OverlayEnvironment? = { displayId ->
        context.getSystemService(DisplayManager::class.java)?.getDisplay(displayId)?.let { display ->
            OverlayEnvironment.read(
                context.createDisplayContext(display),
                display,
                belowBars = windowType == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            )
        }
    }

    /** Per display, the last environment read for real (not
     *  [OverlayEnvironment.provisional]). The display's, not its windows':
     *  kept across [removeAll]. */
    private val lastRead = mutableMapOf<Int, OverlayEnvironment>()

    /**
     * [displayId]'s environment as it is now, read fresh, or null for a
     * display that went away. For a decision taken once at one event; a
     * placement that must follow the display registers as
     * [OverlayPlacement.Placed] instead.
     *
     * A failed read ([OverlayEnvironment.provisional]) tells nothing new:
     * the display's last real read stands in for it while the display's
     * geometry is the same, so a bar heard, an icon released or a window
     * added during a failure moves and resizes nothing and places the new
     * window clear of the bars. A failed read still knows its geometry: the
     * rotation comes from the display itself, which never fails, and the
     * size is the one the display read before at that rotation and panel
     * size ([com.playtranslate.DisplayWindowContexts.knownSize]), so the
     * same geometry here means what it means there. Across a rotation or a
     * resize the stand-in is all there is, and a window must still go
     * somewhere: it is laid out against that, and the next real read lays it
     * out again.
     *
     * Accepted: nothing retries a failed read. A bar shown or hidden during a
     * failure is heard once, its refresh reads the stand-in, and the change
     * is applied at the display's next refresh (a display change, a window
     * moved or added, another bar change). Reads fail in practice only on
     * Android 11, when rotations outpace garbage collection (DisplaySize.kt),
     * and that version signals neither a freed window context nor one that
     * has caught up, so a retry could only be a timer.
     */
    fun environment(displayId: Int): OverlayEnvironment? {
        val env = environmentReader(displayId)
        if (env == null) {
            lastRead.remove(displayId)
            return null
        }
        if (!env.provisional) {
            lastRead[displayId] = env
            return env
        }
        val last = lastRead[displayId]?.takeIf { it.geometry == env.geometry }
        Log.w(
            TAG,
            "env[$displayId] read failed: " +
                if (last != null) "the last real read stands in" else "no real read of ${env.geometry}, placing without insets",
        )
        return last ?: env
    }

    /** A pass is under way ([refresh]); a refresh asked for meanwhile runs
     *  after it. */
    private var refreshing = false
    /** Refreshes asked for during a pass, per display: force if any asked. */
    private val refreshAfterPass = linkedMapOf<Int, Boolean>()
    /** Displays with a bars refresh posted and not yet run. */
    private val barsRefreshPosted = mutableSetOf<Int>()
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** Per display, which bars the Placed windows last heard hidden
     *  ([hiddenBarsMask]). */
    private val barsHeard = mutableMapOf<Int, Int>()

    /**
     * A Placed window on [displayId] was dispatched [insets]. A window is
     * re-dispatched its insets whenever its own frame changes as well
     * (InsetsController.onFrameChanged, android-16.0.0_r1), which is every
     * frame of the icon's drag and glide, so only a change in which bars are
     * shown refreshes the display; several windows hear one change, and one
     * refresh per display runs after them. Not heard here: a bar that
     * changes size without showing or hiding (switching navigation modes
     * with our windows up), which the next display change or add applies.
     */
    private fun onBarsHeard(displayId: Int, insets: WindowInsets) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val hidden = hiddenBarsMask(insets)
        if (barsHeard.put(displayId, hidden) == hidden) return
        if (!barsRefreshPosted.add(displayId)) return
        mainHandler.post {
            barsRefreshPosted.remove(displayId)
            refresh(displayId, "bars", force = false)
        }
    }

    /** Lay out every Placed window on [displayId] again against the display
     *  as it is now, whatever each was laid out with: a window others derive
     *  from moved or changed (the icon at a gesture's or glide's end, the
     *  region editor's box at a drag's end, the error pills' height). */
    fun invalidateLayout(displayId: Int) = refresh(displayId, "invalidate", force = true)

    /** The display [displayId] changed (rotation, resize, or an event with
     *  nothing behind it, e.g. a refresh-rate switch): every window on it
     *  follows. Forced, so a window that recorded the display mid-change is
     *  laid out again even when the change has already reverted. */
    fun onDisplayChanged(displayId: Int) = refresh(displayId, "display", force = true)

    /**
     * Bring every window on [displayId] to the display's environment as it is
     * now: re-pin each pinned window whose size differs, and run each
     * [OverlayPlacement.Placed] layout whose window was laid out with
     * another environment, or every one with [force]. A window's params are
     * committed only when the pass changed them, so a pass that moves
     * nothing relayouts nothing. Windows that derive from another window
     * read its live params; when a pass moved anything another pass follows,
     * so the order windows were added in never matters (at most
     * [MAX_LAYOUT_PASSES]). A refresh asked for during a pass runs after it.
     * Main thread.
     */
    fun refresh(displayId: Int, reason: String, force: Boolean) {
        if (refreshing) {
            refreshAfterPass[displayId] = (refreshAfterPass[displayId] ?: false) || force
            return
        }
        refreshing = true
        try {
            runPasses(displayId, reason, force)
            var rounds = 0
            while (refreshAfterPass.isNotEmpty() && rounds++ < MAX_LAYOUT_PASSES) {
                val (id, f) = refreshAfterPass.entries.first().toPair()
                refreshAfterPass.remove(id)
                runPasses(id, "$reason+nested", f)
            }
            if (refreshAfterPass.isNotEmpty()) {
                Log.w(TAG, "env: refreshes still asked for after $MAX_LAYOUT_PASSES rounds ($reason)")
                refreshAfterPass.clear()
            }
        } finally {
            refreshing = false
        }
    }

    private fun runPasses(displayId: Int, reason: String, force: Boolean) {
        // Nothing on the display follows it (every display event reaches
        // both backends' hosts): no read.
        if (overlayWindows.none { it.displayId == displayId && it.followsEnvironment }) return
        val env = environment(displayId) ?: return
        var passForce = force
        for (pass in 1..MAX_LAYOUT_PASSES) {
            val commits = layoutPass(displayId, env, passForce)
            if (commits == 0) return
            Log.i(
                TAG,
                "env[$displayId] reason=$reason pass=$pass rot=${env.geometry.rotation} " +
                    "size=${env.geometry.width}x${env.geometry.height} covered=${env.covered} " +
                    "barRoom=${env.barRoom} commits=$commits" + if (env.provisional) " provisional" else "",
            )
            passForce = true
        }
        Log.w(TAG, "env[$displayId] did not settle in $MAX_LAYOUT_PASSES passes ($reason)")
    }

    /** One pass over [displayId]'s windows; returns how many were committed. */
    private fun layoutPass(displayId: Int, env: OverlayEnvironment, force: Boolean): Int {
        var commits = 0
        for (handle in overlayWindows.toList()) {
            if (handle.displayId != displayId || !handle.followsEnvironment) continue
            // Removed by an earlier window's layout in this pass.
            if (overlayWindows.none { it === handle }) continue
            try {
                val before = WindowManager.LayoutParams().apply { copyFrom(handle.params) }
                if (handle.pinned) pin(handle.params, env)
                val placement = handle.placement
                if (placement is OverlayPlacement.Placed && (force || handle.laidOutWith != env)) {
                    placement.layout(env, handle.params)
                }
                handle.laidOutWith = env
                // copyFrom reports what differs (x, y, size, gravity, flags,
                // alpha...): the params the layout changed, nothing else.
                if (before.copyFrom(handle.params) != 0) {
                    handle.wm.updateViewLayout(handle.view, handle.params)
                    commits++
                }
            } catch (e: Exception) {
                // The relayout itself is scheduled, never reported here; the
                // one throw is a view the manager no longer holds, a handle
                // that outlived its window.
                Log.w(TAG, "layout on display $displayId: ${handle.view.javaClass.simpleName}: ${e.message}")
            }
        }
        return commits
    }

    /**
     * Which system bars (status/navigation) the app under our overlays
     * currently has hidden on [displayId], as a [WindowInsets.Type] mask —
     * read from the DISPLAY's insets state through a window-metrics query
     * ([displayWindowMetrics]), never from one of our own windows' insets.
     * Null when unknowable: pre-R (per-type visibility only exists in the R
     * insets model, and the legacy approximation reads a NO_LIMITS window's
     * zero insets as "hidden", which would blanket-hide bars the game never
     * hid), or the metrics query failed. Safe to call in the same frame a
     * window was added: nothing here waits on a traversal.
     *
     * Why not a registered window's [View.getRootWindowInsets] (the read
     * this replaced): from Android 12 a window is dispatched only the insets
     * sources of the windows ABOVE it in Z order (WindowState.mAboveInsetsState,
     * filled by InsetsStateController.updateAboveInsetsState's top-down walk,
     * which seeds the state with cutout + gesture types only and adds each
     * window's provided sources after visiting it). TYPE_ACCESSIBILITY_OVERLAY
     * (layer 31) sits above the status bar (15) and navigation bar (24), so on
     * the accessibility backend the bar sources are absent from every window
     * of ours, an absent type reads as not visible, and the mask came back
     * 0x3 whatever the game requested — right by coincidence over an
     * immersive game, and hiding both bars over everything else (the Thor
     * "status bar disappears on capture" report, 2026-09-06). The
     * window-metrics path is InsetsPolicy.getInsetsForWindowMetrics server
     * side: the display's raw state with transiently-shown bars masked as
     * hidden, i.e. exactly the request of whichever window holds the bar
     * control. A display with no bar of a type (the Thor's second screen has
     * no status bar) reads that type as hidden, and hiding a bar that does
     * not exist is a no-op.
     *
     * Only meaningful as a picture of the GAME's state while no focusable
     * overlay of ours holds the bar control — but every focusable overlay we
     * add mirrors the state it observed (see [addOverlayWindow] /
     * [mirrorSystemBars]), so inductively the global state under any stack of
     * our windows still equals the game's own request.
     */
    fun hiddenSystemBarsOnDisplay(displayId: Int): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val insets = displayContextFor(displayId)?.displayWindowMetrics()?.windowInsets
            ?: return null
        return hiddenBarsMask(insets)
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
        val wm = OwnWindows.manager(displayContext) ?: return
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
        if (addOverlayWindow(view, wm, params, displayId, OverlayPlacement.Fixed)) {
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
        val state = OverlayState(
            displayId,
            saved,
            blankedAnything = saved.isNotEmpty(),
            uncompositedGhost = WindowChurnGate.hasUncompositedGhostOn(displayId),
        )
        capturesInFlight.getOrPut(displayId) { mutableListOf() } += state
        return state
    }

    /** Restores blanked overlays to the alpha they had before
     *  [prepareForCleanCapture] blanked them, and those added while the
     *  capture was in flight to the alpha they were added with. Most
     *  overlays were at α=1.0 and come back there; the MediaProjection
     *  live-pinhole window is the only current exception (returns to the
     *  system obscuring cap). Safe to call twice (the capture sites restore
     *  early and again in a `finally`). */
    fun restoreAfterCapture(state: OverlayState) {
        capturesInFlight[state.displayId]?.let { inFlight ->
            inFlight.remove(state)
            if (inFlight.isEmpty()) capturesInFlight.remove(state.displayId)
        }
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
        barsHeard.clear()
        for (h in handles) {
            WindowChurnGate.removeWindow(h.view, h.wm, h.params, h.displayId)
        }
    }

    companion object {
        private const val TAG = "OverlayHost"

        /** Passes a [refresh] runs while windows keep moving. Derivations go
         *  one level deep (the spinner, sonar intro and menu from the icon;
         *  the no-text pill from the error pills), so two passes settle and a
         *  third confirms; a fourth that still moves something is a layout
         *  that never settles, logged. */
        private const val MAX_LAYOUT_PASSES = 4

        /** Per-type mask of the system bars currently hidden, read from
         *  [view]'s last-received window insets. Private on purpose: valid
         *  ONLY for a window that sits BELOW the system bars in Z order — an
         *  Activity's decor, via [hiddenSystemBarsOfActivity] — because from
         *  Android 12 a window's insets carry only the sources of the windows
         *  above it (see [hiddenSystemBarsOnDisplay]); read from one of our
         *  overlays it answers "both hidden" whatever the game does. [view]
         *  must be attached AND have traversed once: between addView and the
         *  first traversal a root has no attach info and reports null insets
         *  (there is no parent fallback, unlike
         *  [View.getWindowInsetsController]). Null pre-R; see
         *  [hiddenSystemBarsOnDisplay] for why pre-R stays null. */
        private fun hiddenSystemBars(view: View): Int? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
            val insets = view.rootWindowInsets ?: return null
            return hiddenBarsMask(insets)
        }

        /** [insets] → [WindowInsets.Type] mask of the bar types that are not
         *  visible. A type with no source in [insets] reads as not visible
         *  ([WindowInsets.isVisible] is false for an absent type): right for
         *  a display that has no such bar, and the trap [hiddenSystemBars]
         *  documents for a window layered above the bars. */
        @RequiresApi(Build.VERSION_CODES.R)
        fun hiddenBarsMask(insets: WindowInsets): Int {
            var hidden = 0
            if (!insets.isVisible(WindowInsets.Type.statusBars())) {
                hidden = hidden or WindowInsets.Type.statusBars()
            }
            if (!insets.isVisible(WindowInsets.Type.navigationBars())) {
                hidden = hidden or WindowInsets.Type.navigationBars()
            }
            return hidden
        }

        /** The bars the Activity hosting [ctx] keeps hidden — the mirror
         *  source for a window attached to an activity token (the in-app
         *  lens and dictionary popup), read from the activity's decor, which
         *  has been attached far longer than any window we add. [ctx] is
         *  resolved through the ContextWrapper chain, not a bare cast: the
         *  activity-hosted capture sheet hands its lens a themed wrapper
         *  (CaptureResultOverlay wraps its context in overlayThemedContext),
         *  and `as? Activity` on that is null — read as "no activity, arm
         *  nothing" (a Codex find). Null when no Activity hosts [ctx], or
         *  pre-R. */
        fun hiddenSystemBarsOfActivity(ctx: Context): Int? =
            hostingActivity(ctx)?.window?.decorView?.let { hiddenSystemBars(it) }

        /** The Activity beneath [ctx]'s wrapper chain, if any. */
        fun hostingActivity(ctx: Context): Activity? {
            var c: Context? = ctx
            while (c != null) {
                if (c is Activity) return c
                c = (c as? ContextWrapper)?.baseContext
            }
            return null
        }

        /**
         * Arm [view]'s window with the same system-bar visibility the app
         * beneath maintains ([hiddenTypes], from [hiddenSystemBarsOnDisplay] /
         * [hiddenSystemBarsOfActivity]) so the window can take input focus —
         * and with it the bar control — without flashing the nav pill or
         * status bar over an immersive game. Call on an ATTACHED window,
         * BEFORE the focus grant lands (right after addView, or before the
         * updateViewLayout that clears FLAG_NOT_FOCUSABLE): requested
         * visibility is per-window state the system applies when the window
         * becomes the control target.
         *
         * [hiddenTypes] == null (state unknowable) leaves the window's
         * default request, bars visible. Pre-R that is policy and silent;
         * on R+ it is a miss and is logged, because the device-pass probes
         * grep this tag and a silent no-op is exactly the failure they
         * cannot see. Bars the app shows are explicitly show()n so a re-arm
         * can clear a stale hide.
         */
        fun mirrorSystemBars(view: View, hiddenTypes: Int?) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
            if (hiddenTypes == null) {
                Log.w(
                    TAG,
                    "[BarMirror] ${view.javaClass.simpleName} unknowable, default request stands"
                )
                return
            }
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

        /** What [applyOverlayLayoutDefaults] does to a window, decided from
         *  its size request, its placement and the API level, as plain
         *  values so the decision is testable at any level. */
        internal data class LayoutDefaults(
            /** The window's origin is the display's: nothing the platform
             *  fits moves it, and the cutout is entered. */
            val displayFrame: Boolean,
            val noLimits: Boolean,
        )

        internal fun layoutDefaults(fullScreen: Boolean, placement: OverlayPlacement, sdk: Int): LayoutDefaults =
            LayoutDefaults(
                displayFrame = fullScreen ||
                    (sdk >= Build.VERSION_CODES.R && placement !is OverlayPlacement.Fixed),
                noLimits = fullScreen,
            )

        /**
         * The layout contract a window added through [addOverlayWindow] gets
         * from its [placement].
         *
         * A window the host places or sizes (every placement but
         * [OverlayPlacement.Fixed], from R) is laid out in display pixels: its
         * origin is the display's own origin, and nothing the platform fits
         * moves it. Every such surface computes its position in display pixels
         * (the capture's pixels for the translation overlays and the sheet,
         * the environment's insets for the icon's dock and the pills), so the
         * window's parent frame must BE the display. By default it is not: a
         * window's parent frame is the display inset by the bars in its
         * `fitInsetsTypes`, and a window with `FLAG_LAYOUT_IN_SCREEN` still
         * fits a VISIBLE navigation bar
         * (`ViewRootImpl.adjustLayoutParamsForCompatibility` strips only the
         * status bar; `WindowLayout.computeFrames` places x/y against the
         * inset parent frame and uses `FLAG_LAYOUT_NO_LIMITS` only to widen
         * the final clamp; android-16.0.0_r1). The bars' sources reach a
         * window only when it lies BELOW them in Z, so the shift happened on
         * the MediaProjection backend alone: on the Moto G with three-button
         * navigation on the left (rotation 270) every fixed-size window of
         * ours began 84 px to the right, which put a right-docked icon off the
         * display and a left-docked one, drawn as if flush, almost whole
         * beside the buttons, while the accessibility backend never moved.
         * `fitInsetsTypes = 0` ends that; what a window keeps clear of comes
         * from [OverlayEnvironment] (a Placed window) or its own insets (a
         * Content window). The cutout mode (`..._ALWAYS` from API 30,
         * `..._SHORT_EDGES` on 29) lets the window cover the display cutout
         * for the same reason.
         *
         * A MATCH_PARENT window also gets `FLAG_LAYOUT_NO_LIMITS`; its SIZE
         * is pinned to explicit display pixels in [addOverlayWindow]
         * (platform MATCH_PARENT sizing is untrustworthy, see there).
         *
         * Below R everything is as it was before the contract: there is no
         * `fitInsetsTypes` and no environment to place against, so only the
         * full-screen windows get the display frame (`FLAG_LAYOUT_IN_SCREEN`
         * as the pre-30 spelling of the display origin), and a fixed-size
         * window keeps the platform's layout, which keeps it clear of the
         * bars there. A [OverlayPlacement.Fixed] window keeps the platform's
         * layout on every level.
         *
         * Idempotent. Honors callers that explicitly set a non-DEFAULT cutout
         * mode: only the DEFAULT case is upgraded.
         */
        fun applyOverlayLayoutDefaults(params: WindowManager.LayoutParams, placement: OverlayPlacement) {
            val fullScreen =
                params.width == WindowManager.LayoutParams.MATCH_PARENT &&
                    params.height == WindowManager.LayoutParams.MATCH_PARENT
            val defaults = layoutDefaults(fullScreen, placement, Build.VERSION.SDK_INT)
            if (!defaults.displayFrame) return
            if (defaults.noLimits) {
                params.flags = params.flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                params.fitInsetsTypes = 0
            } else {
                // Pre-30 has no fitInsetsTypes, and only a full-screen window
                // gets here. FLAG_LAYOUT_IN_SCREEN pins the window ORIGIN to
                // the screen top-left (under the status bar); [addOverlayWindow]
                // pins an explicit full-display SIZE. Together (origin 0,0 +
                // size == capture) the overlay matches the capture bitmap so
                // OCR boxes align.
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
