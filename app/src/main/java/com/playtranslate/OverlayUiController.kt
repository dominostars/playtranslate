package com.playtranslate

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.input.InputManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.playtranslate.capture.CaptureBackendResolver
import com.playtranslate.capture.CaptureLifecycle
import com.playtranslate.capture.GameAudioGate
import com.playtranslate.cloud.PtCloudService
import com.playtranslate.ocr.FrameReaders
import com.playtranslate.net.NetworkConnectivity
import com.playtranslate.language.HintTextKind
import com.playtranslate.language.LanguagePackStore
import com.playtranslate.language.SourceLangId
import com.playtranslate.language.SourceLanguageProfiles
import com.playtranslate.ocr.mangaocr.MangaOcrProvisioning
import com.playtranslate.ocr.registry.OcrModelManager
import com.playtranslate.ocr.registry.ocrLabel
import com.playtranslate.ocr.registry.selectionToken
import com.playtranslate.overlay.OverlayEnvironment
import com.playtranslate.overlay.OverlayHost
import com.playtranslate.overlay.OwnActivities
import com.playtranslate.overlay.OverlayPlacement
import com.playtranslate.ui.CaptureOverlaySettingsActivity
import com.playtranslate.ui.CaptureOverlayWindow
import com.playtranslate.ui.DimController
import com.playtranslate.ui.DetachedPage
import com.playtranslate.ui.DetachedWindow
import com.playtranslate.ui.DetachedWindowGeometry
import com.playtranslate.ui.DragLookupController
import com.playtranslate.ui.IconDock
import com.playtranslate.ui.FloatingIconMenu
import com.playtranslate.ui.QuickSetting
import com.playtranslate.ui.IconDiscFootprint
import com.playtranslate.ui.GrowthLimits
import com.playtranslate.ui.LanguageSetupActivity
import com.playtranslate.ui.OcrPicker
import com.playtranslate.ui.FloatingOverlayIcon
import com.playtranslate.ui.MagnifierLens
import com.playtranslate.ui.OverlayAlert
import com.playtranslate.ui.OverlayRenderConfig
import com.playtranslate.ui.OverlayWorkspace
import com.playtranslate.ui.PtCloudSettingsActivity
import com.playtranslate.ui.SourceListPage
import com.playtranslate.ui.WorkspaceHost
import com.playtranslate.ui.WorkspacePage
import com.playtranslate.ui.SonarPingIntroView
import com.playtranslate.ui.TextBox
import com.playtranslate.ui.twoScreenMode
import com.playtranslate.ui.TranslationErrorPills
import com.playtranslate.ui.TranslationOverlayView
import com.playtranslate.ui.TranslationServicesActivity
import com.playtranslate.ui.WordLookupPopup
import com.playtranslate.overlay.OwnWindows
import com.playtranslate.translation.TranslationError
import com.playtranslate.translation.TranslationErrorKey
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private const val TAG = "OverlayUiController"

/** Sonar-ping intro overlay window size — sized to cover the carrier's
 *  travel range plus the two expanding rings (ring 2 ends at scale 3.8 ×
 *  56dp = 213dp diameter centred on the entry pose, which sits 108dp from
 *  the dock edge). Width 320dp comfortably contains entry → dock with the
 *  outer ring; height 280dp the vertical ring extent. */
private const val SONAR_INTRO_WIDTH_DP = 320
private const val SONAR_INTRO_HEIGHT_DP = 280

/**
 * Owns every game-screen overlay the app draws: the floating icon + menu, the
 * one-shot translation overlay, and the no-text pill. The region indicator /
 * picker / editor are owned by [RegionOverlayController] and reached here
 * through [regionController] (this class exposes thin delegators for them).
 *
 * Extracted from PlayTranslateAccessibilityService so the same UI can be hosted
 * by either capture backend. Backend-specific concerns are confined to the two
 * constructor parameters: [context] (the accessibility service, or
 * CaptureService in MediaProjection mode) and [overlayHost] (which stamps the
 * window type — TYPE_ACCESSIBILITY_OVERLAY vs TYPE_APPLICATION_OVERLAY). The
 * accessibility service and CaptureService each own one instance; callers reach
 * the active one via `CaptureBackendResolver.activeOverlayUi`.
 *
 * Main-thread only.
 */
class OverlayUiController(
    private val context: Context,
    private val overlayHost: OverlayHost,
    /** Gate for whether the floating controls may be shown right now. The
     *  MediaProjection controller withholds them until screen-record consent
     *  is granted — they can't drive a capture without it, and that consent
     *  doesn't survive a process restart. The accessibility controller is
     *  always ready, so it keeps the default. */
    private val canShowControls: () -> Boolean = { true },
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())

    /**
     * One screen's capture over the game, from the moment it starts until it
     * ends: the display it captures, the mode it asked for, what it took from
     * the game screen and, once its screenshot is in, its sheet. One record
     * whatever the phase, so a rule that ends a capture ([endCaptures]) sees
     * one still on its way as it sees one showing, and a capture's setup
     * stands down once it is no longer the record ([sheetCapture]).
     */
    private class SheetCapture(
        val displayId: Int,
        /** The mode it asked to open in ahead of the remembered one
         *  (tap-to-lookup's Definitions); null for the remembered one. */
        val modeOverride: OverlayMode?,
        /** What it took from the game screen (a running auto-translate, the
         *  region indicator); null once disposed of, and for a sheet that
         *  came back on a page's Back, which owes nothing. */
        var pause: LookupPause?,
    ) {
        /** The setup still running: the clean shot and the sheet's build, or
         *  the full-screen page's launch where no sheet can go up. It
         *  suspends in requestClean before the sheet exists, so ending the
         *  capture cancels it as well as the sheet. */
        var setup: Job? = null

        /** Its sheet, once the screenshot is in; null while on its way. */
        var sheet: com.playtranslate.ui.CaptureResultOverlay? = null

        /** The display's size and rotation the sheet was built for
         *  ([dismissCaptureResultPanelIfReconfigured]); a capture on its way
         *  checks its own after the screenshot instead, so no display event
         *  has to coordinate with one not yet shown. */
        var geometry: DisplayGeometry? = null
    }

    /** One screen's capture over the game ([SheetCapture]); null with none. A
     *  new capture replaces it. */
    private var sheetCapture: SheetCapture? = null

    /** The mode [capture] paints in: its sheet's own once up (a pick on its
     *  pill moves it), else the one it will open in. */
    private fun modeOf(capture: SheetCapture): OverlayMode =
        capture.sheet?.overlayMode
            ?: capture.modeOverride
            ?: Prefs(context).overlayModeFor(OverlaySurface.CAPTURE)

    /**
     * Two screens' capture for the game display on its way: the app is
     * reading it, and its result will put the window up by itself when it
     * lands ([presentCapture], by [id]), unless something takes the game
     * screen first ([endCaptures]), which ends this record and leaves the
     * result on the app's page. Started with the capture ([beginAppCapture]);
     * over once its presentation goes up.
     */
    private class AppCapture(
        val id: Long,
        val displayId: Int,
        /** As [SheetCapture.modeOverride]. */
        val modeOverride: OverlayMode?,
    )

    /** Two screens' capture on its way ([AppCapture]); null with none. */
    private var appCapture: AppCapture? = null

    // Read by tests.
    /** One screen's capture's sheet while it shows; null on its way or with none. */
    internal val captureSheetForTest: com.playtranslate.ui.CaptureResultOverlay? get() = sheetCapture?.sheet
    /** What one screen's capture over the game took from it; null with none. */
    internal val capturePauseForTest: LookupPause? get() = sheetCapture?.pause
    /** The display one screen's capture over the game is on, from its start; -1 with none. */
    internal val captureDisplayIdForTest: Int get() = sheetCapture?.displayId ?: -1
    /** Whether two screens' capture [id] is still on its way to the game display. */
    internal fun appCaptureOnItsWayForTest(id: Long): Boolean = appCapture?.id == id

    /** [displayId]'s detached window, if it is up. */
    internal fun detachedWindowForTest(displayId: Int): DetachedWindow? = windowHandles[displayId]

    /** [displayId]'s detached page, if it has one (the window up or not). */
    internal fun detachedPageForTest(displayId: Int): DetachedPage? = detachedPages[displayId]

    /** The display of a capture into a detached window still on its way, or -1. */
    internal val windowCaptureDisplayIdForTest: Int get() = windowCapture?.displayId ?: -1

    /** A capture result stashed when the overlay's word lens opened the in-app
     *  detail screen, so a user-initiated back from that screen re-shows the panel.
     *  Discarded by ANY teardown (new capture, menu, hideAll, destroy) and after
     *  [reshowStalenessMs], so it never re-shows unexpectedly. */
    private var pendingReshow: PendingReshow? = null
    /** Max age of a [pendingReshow] before a return is treated as too stale to re-show. */
    private val reshowStalenessMs = 30_000L

    private data class PendingReshow(
        val displayId: Int,
        val result: com.playtranslate.model.TranslationResult,
        /** The region the result was captured with, so the re-shown panel
         *  outlines the same region. */
        val region: RegionEntry,
        val stashedAtMs: Long,
        /** The Ask section's finished answer about [result], if it had one:
         *  it comes back with the result. A question still in flight is not
         *  kept (its request ended with the sheet). */
        val askAnswer: com.playtranslate.ask.AskState.Answered? = null,
    )

    /** The floating workspace while showing (single-screen). Single instance —
     *  a new open replaces it. Geometry tracked like the capture panel's, so a
     *  display reconfiguration dismisses it ([dismissWorkspaceIfReconfigured]). */
    private var workspace: OverlayWorkspace? = null
    private var workspaceDisplayId = -1
    private var workspaceGeometry: DisplayGeometry? = null

    /** Listens for display changes (rotation, hot-plug, foldable state) so
     *  icons can be repositioned against the new dimensions and the icon
     *  registry can be reconciled against the new set of available displays.
     *
     *  This used to live exclusively on
     *  [PlayTranslateAccessibilityService.displayListener] and on
     *  [CaptureService]'s live-mode-gated listener, but on the
     *  MediaProjection backend neither one is reliably active when the
     *  floating icon is showing without live mode running (a11y service
     *  isn't connected; CaptureService.displayListener is only registered
     *  while live mode runs). Result: a rotation in MP mode left the icon
     *  pinned to its pre-rotation x/y and ending up off the new edge.
     *
     *  Hosting the listener here keeps rotation/hot-plug handling in
     *  lockstep with icon visibility — the controller exists for as long
     *  as a backend can show icons. The a11y service's listener is left
     *  intact (its onDisplayChanged call duplicates this one, harmlessly);
     *  this controller's listener fills the gap on the MP backend. */
    private val displayListener = object : DisplayManager.DisplayListener {
        // Both capture backends keep an OverlayUiController alive for the
        // CaptureService's lifetime, but only the active backend's should
        // react to display events. A backend swap (CaptureBackendResolver.
        // reresolve) does not unregister the outgoing controller's listener,
        // so without this guard a display add/remove would let the inactive
        // controller resurrect its floating icons in the wrong window type.
        private val isActiveController: Boolean
            get() = CaptureBackendResolver.activeOverlayUi === this@OverlayUiController

        override fun onDisplayAdded(displayId: Int) {
            if (isActiveController) reconcileFloatingIcons()
        }
        override fun onDisplayRemoved(displayId: Int) {
            if (!isActiveController) return
            // A capture's sheet or window died with its display: drop our
            // handles rather than hold zombies (notifying the app's page),
            // and give the game screen back.
            endCaptures(CaptureTakeover.DISPLAY_GONE, setOf(displayId))
            reconcileFloatingIcons()
        }
        override fun onDisplayChanged(displayId: Int) {
            if (isActiveController) {
                // Dismiss a SHOWING result panel when ITS display's geometry (size or rotation)
                // changed — it's laid out + OCR-mapped against that geometry. An in-flight capture
                // is deliberately NOT touched here (it self-validates after requestClean), so a
                // spurious / cross-display event can't cancel a just-tapped capture — that was the
                // rotate-then-tap no-op. 180° flips change rotation, so a stale panel still drops.
                dismissCaptureResultPanelIfReconfigured(displayId)
                // Same stale-layout rule for the floating workspace.
                dismissWorkspaceIfReconfigured(displayId)
                // A rotation / resize invalidates a per-box translation overlay
                // group — its cached displayW/displayH drive the OCR→screen
                // mapping. Drop it on a size change; the next capture cycle
                // rebuilds the group at the new dimensions.
                dropResizedTranslationOverlay(displayId)
                // The capture's two-screen window: the panel's rule, size or
                // rotation.
                dropReconfiguredCaptureWindow(displayId)
            }
            // Every window still up on the display follows it: the host
            // re-pins the full-screen ones and lays out every placed one (the
            // icon, its spinner and sonar intro, an open menu, the region
            // editor's chrome, the pills) against the display as it is now.
            // Not gated: it touches only the windows this host holds, and a
            // host with none does nothing.
            // A move or resize of the detached window ends first where the
            // display is really another shape: its frame belongs to the old
            // geometry, and a window a gesture owns is not laid out. A
            // report with nothing behind it (a refresh-rate switch) leaves
            // the gesture alone ([DetachedWindow.onDisplayChanged]).
            windowHandles[displayId]?.onDisplayChanged()
            overlayHost.onDisplayChanged(displayId)
            if (isActiveController) {
                // A drag session ends: its lens window, screenshot and finger
                // coordinates belong to the old geometry.
                iconHandles[displayId]?.dragController?.onDisplayChanged()
            }
        }
    }

    /** Wire up listeners that depend on a usable host context.
     *
     *  The a11y backend constructs us as a non-lazy field on
     *  [PlayTranslateAccessibilityService], so our `init { }` block runs
     *  inside the service's no-arg constructor — *before*
     *  [Service.attachBaseContext] fires. At that point the service's
     *  `mBase` is null, so any context-touching call (including
     *  `getApplicationContext()`) NPEs. The MP backend constructs us
     *  lazily, so this constraint doesn't bind there, but we share the
     *  same lifecycle pattern for symmetry.
     *
     *  Idempotent in practice because each host calls it exactly once per
     *  controller instance — a11y from [onServiceConnected], MP from the
     *  lazy `OverlayUiController(...).also { it.attach() }` block. */
    fun attach() {
        (context.applicationContext.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
            ?.registerDisplayListener(displayListener, handler)
    }

    private val regionController = RegionOverlayController(
        context, overlayHost,
        anyIconInDragMode = { anyIconInDragMode() },
        bringIconToFront = { id -> bringFloatingIconsToFront(id) },
        hideTranslationOverlay = { hideTranslationOverlay() },
        onRegionSelected = { id, region -> handleRegionSelection(id, region) },
        onRegionCleared = { id -> clearRegionForDisplay(id) },
        // The region editor needs the whole game in view: the icon stands
        // in for a detached window while it is open ([shownFormOn]).
        onRegionEditorChanged = { applyControlsForms() },
    )

    // ── Floating icon registry ───────────────────────────────────────────

    /** Per-display floating-icon bundle — the icon, its WindowManager, the
     *  drag-lookup controller, and what its drag sessions paused all
     *  lifecycle together. */
    private data class FloatingIconHandle(
        val icon: FloatingOverlayIcon,
        val wm: WindowManager,
        val dragController: DragLookupController,
        /** What this display's drag sessions paused (live mode, the region
         *  indicator): settled when a session's lens settles or the icon goes
         *  while capture carries on, dropped when the icon goes because
         *  capture ends (a resumed live start would land on a backend on its
         *  way out), its restart cancelled when a page covers the game. */
        val dragPause: LookupPause,
        /** Builds a fresh, fully wired icon VIEW for this display — closes
         *  over the display's collaborators (drag controller, session state)
         *  inside [installFloatingIconForDisplay], so install and the
         *  z-order re-raise share one wiring and cannot drift. The re-raise
         *  REPLACES the icon view with a fresh one instead of re-adding the
         *  same view, because a same-view re-add forces the gate to destroy
         *  the old surface synchronously beside the new add
         *  ([com.playtranslate.overlay.WindowChurnGate.flushPendingFor]) —
         *  the release-adjacent-to-create shape the gate exists to prevent. */
        val makeIcon: () -> FloatingOverlayIcon,
    )

    private val iconHandles: MutableMap<Int, FloatingIconHandle> = mutableMapOf()

    /** Per-display detached windows: the controls' other form
     *  ([ControlsForm.WINDOW]). Once a reconcile has settled a display holds
     *  an icon or a window, never both ([reconcileFloatingIcons]). */
    private val windowHandles: MutableMap<Int, DetachedWindow> = mutableMapOf()

    /** Per-display results page of the detached window. It outlives the
     *  window's view: the icon standing in for the window takes the window
     *  down and leaves the page, with its result, for the window that comes
     *  back. A page belongs to a display whose controls are saved as the
     *  window and are allowed on screen; anything else drops it
     *  ([dropDetachedPage]). */
    private val detachedPages: MutableMap<Int, DetachedPage> = mutableMapOf()

    /** A capture into a detached window on its way: its clean frame is not
     *  in yet, so its page has not changed. One at a time, as the service
     *  holds one capture job. */
    private class WindowCapture(val displayId: Int) {
        var setup: Job? = null
    }

    private var windowCapture: WindowCapture? = null

    /** The displays that hold the floating controls now, as the icon or as
     *  the detached window, as a snapshot: what the
     *  log export prints beside the resolved capture targets
     *  ([com.playtranslate.diagnostics.DisplayDiag]), since the two differ
     *  whenever [reconcileFloatingIcons] returns before installing (the
     *  preference off, the boot or Hide-for-Now suppression, MediaProjection
     *  not turned on). [iconHandles] is main-thread confined and the export
     *  reads off it; a read that races a mutation fails, which the export
     *  tolerates by leaving the block out (Codex review, 2026-10-14). */
    val floatingIconDisplayIds: Set<Int> get() = (iconHandles.keys + windowHandles.keys).toSet()

    // ── Sonar-ping intro ─────────────────────────────────────────────────
    //
    // Attention animation that plays whenever a fresh floating icon lands in
    // a window (see design_handoff_sonar_ping/). Fires for every install —
    // capture activation, the QS tile flipping showOverlayIcon on, a new
    // display being added, etc. Does NOT fire for rotation
    // (the host lays the existing icon out again in place)
    // or for z-order re-raises (bringFloatingIconsToFront swaps in a fresh
    // icon view via the handle's makeIcon, without going through install).
    //
    // Keyed by displayId so simultaneous intros on multi-display setups don't
    // overwrite each other's overlay-window references and leave one
    // orphaned.

    private val activeSonarIntros: MutableMap<Int, SonarPingIntroView> = mutableMapOf()

    /** True iff the floating controls are on screen on some display, as the
     *  icon or as the detached window. This is what "PlayTranslate can be
     *  reached over the game" reads everywhere it is asked: the hotkeys'
     *  gate, the service's foreground state, the session restores and the
     *  kill notice. None of them may ask for the icon alone, or they would
     *  all read "off" with a window up. */
    val hasControlsOnScreen: Boolean get() = iconHandles.isNotEmpty() || windowHandles.isNotEmpty()

    fun setIconsLoading(loading: Boolean) {
        iconHandles.values.forEach { it.icon.showLoading = loading }
    }

    fun setIconsDegraded(degraded: Boolean) {
        iconHandles.values.forEach { it.icon.degraded = degraded }
    }

    /** Auto-translate is running or not: every control says so, the icon by
     *  its colour and the detached window by its Auto button, and each
     *  window's page follows the session that is its own ([syncLivePages]).
     *  The service calls it at every start and stop, whenever controls go
     *  up or come down, and after a running session changed where it shows
     *  ([CaptureService.syncIconState]). */
    fun setIconsLiveMode(liveMode: Boolean) {
        iconHandles.values.forEach { it.icon.liveMode = liveMode }
        windowHandles.values.forEach { it.autoRunning = liveMode }
        syncLivePages()
    }

    /**
     * Each detached page follows the running session whose results are its
     * window's ([LiveSurface.WINDOW_PAGE], and the session captures that
     * page's display), and follows none otherwise
     * ([DetachedPage.followLive], which does nothing when told what it
     * already does). Run wherever the answer can change: a session starting
     * or stopping, controls going up or down and a session's surface
     * swapped ([setIconsLiveMode]), and the controls' own pass
     * ([applyControlsForms]). A page goes
     * on following while the icon stands in for its window: the session is
     * still the window's, waiting for it.
     */
    private fun syncLivePages() {
        val svc = CaptureService.instance
            ?.takeIf { it.isLive && it.liveSurface() == LiveSurface.WINDOW_PAGE }
        for ((id, page) in detachedPages) page.followLive(svc?.takeIf { it.isLiveOn(id) })
    }

    fun setIconsGlyph(glyph: FloatingOverlayIcon.Glyph) {
        iconHandles.values.forEach { it.icon.glyph = glyph }
    }

    fun anyIconInDragMode(): Boolean = iconHandles.values.any { it.icon.inDragMode }

    fun dismissAllDragLookupPopups() {
        iconHandles.values.forEach { it.dragController.dismiss() }
    }

    val isAnyDragLookupPopupShowing: Boolean
        get() = iconHandles.values.any { it.dragController.isPopupShowing }

    /** True while any drag-lookup lens HOLDS WINDOW FOCUS for controller
     *  navigation (interactive + a controller was attached when it went
     *  sticky). The a11y key filter reads this per key event: its "game input
     *  clears the lookup" rule predates the lens being drivable, and must
     *  stand down for the nav keys that now drive it. */
    val isAnyDragLookupConsumingController: Boolean
        get() = iconHandles.values.any { it.dragController.isPopupConsumingController }

    // ── Translation overlay registry ─────────────────────────────────────

    /**
     * One display's translation overlay: a single full-screen
     * [TranslationOverlayView] window. Boxes that pinhole detection flags
     * as changed are removed and re-OCR'd on the next cycle (no smooth-
     * transition buffer — see [docs/dirty-overlay-archived-design.md] for
     * the prior two-window design and why it was retired).
     */
    private val translationOverlayHandles: MutableMap<Int, TranslationOverlayView> = mutableMapOf()

    /** Translation overlay view for [displayId], or null. */
    fun translationOverlayForDisplay(displayId: Int): TranslationOverlayView? =
        translationOverlayHandles[displayId]

    /** True iff [displayId] currently has a translation overlay. */
    fun hasTranslationOverlay(displayId: Int): Boolean =
        translationOverlayHandles.containsKey(displayId)

    /** True iff any display has a translation overlay registered. */
    val hasAnyTranslationOverlay: Boolean
        get() = translationOverlayHandles.isNotEmpty()

    /** Screen rects of the overlay's text-box children on [displayId]
     *  — pinhole detection samples these for change detection. */
    fun boxScreenRects(displayId: Int): List<Rect> =
        translationOverlayHandles[displayId]?.getChildScreenRects() ?: emptyList()

    /** Drawn footprints of the overlay's text-box children on [displayId]
     *  (rect + rotation + laid-out dims) — the outside gate's exclusion
     *  channel. Same child order as [boxScreenRects]. */
    fun boxFootprints(displayId: Int): List<com.playtranslate.ui.TranslationOverlayView.ChildFootprint> =
        translationOverlayHandles[displayId]?.getChildFootprints() ?: emptyList()

    /** Display size cached on the overlay window (the view's dimensions
     *  match the display because the window is MATCH_PARENT). Returns null
     *  when no overlay is registered. */
    fun translationOverlayDisplaySize(displayId: Int): Point? {
        val view = translationOverlayHandles[displayId] ?: return null
        if (view.width <= 0 || view.height <= 0) return null
        return Point(view.width, view.height)
    }

    /** True once the overlay (and its children) on [displayId] is laid
     *  out — pinhole detection must defer its offscreen render until then. */
    fun areTranslationBoxesLaidOut(displayId: Int): Boolean =
        translationOverlayHandles[displayId]?.areChildrenLaidOut() == true

    /**
     * Render the overlay's content (no pinholes) to a bitmap — the
     * "overlay_rendered" reference [PinholeOverlayMode.checkPinholes] needs.
     * The caller owns the returned bitmap.
     */
    fun renderTranslationOverlayOffscreen(displayId: Int): Bitmap? =
        translationOverlayHandles[displayId]?.renderToOffscreen()

    // ── Backend-aware overlay configuration helpers ─────────────────────

    /**
     * The window α we request for the visible main + dirty overlays. On the
     * MediaProjection backend with `FLAG_NOT_TOUCHABLE` (live pinhole mode),
     * the QTI BSP visually clamps any layer at α > 0.8; voluntarily landing
     * at-or-below the system threshold bypasses the clamp deterministically
     * and also keeps the AOSP untrusted-touch rule from blocking touches
     * passing through to the underlying app (the rule's check is `> cap`,
     * per-window). Elsewhere — accessibility (cap-exempt window type), or
     * MP one-shot (touchable, BSP-exempt) — we use full opacity.
     *
     * A small epsilon is subtracted so we land strictly below the
     * threshold even after any IPC float-rounding.
     */
    private fun systemMaxObscuringOpacityForBackend(): Float {
        val backendNeedsClamp = overlayHost.windowType ==
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        if (!backendNeedsClamp) return 1.0f
        val raw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(InputManager::class.java)
                ?.maximumObscuringOpacityForTouch ?: 0.8f
        } else {
            // API 30 fallback: untrusted-touch protection landed in API 31,
            // and the QTI BSP visual clamp is a vendor patch added against
            // that AOSP rule — but we can't query its presence on API 30.
            // Default to the AOSP default (0.8) as the safer choice; if the
            // pt_api30 verification shows API-30 devices don't clamp in
            // practice, this can be raised to 1.0 later.
            0.8f
        }
        return (raw - 0.001f).coerceAtLeast(0.5f)
    }

    /**
     * Mask alpha byte that, paired with [windowAlpha] on the overlay window,
     * gives an effective pinhole α of 0.5 — the value
     * [PinholeOverlayMode.checkPinholes] assumes in its
     * `predicted = (cleanRef + overlay) / 2` math. Throws if windowAlpha is
     * below the floor where the mask grid would degrade pinhole signal
     * past the SPLATTER_THRESHOLD; callers should fall back to a non-pinhole
     * configuration in that case.
     */
    private fun pinholeMaskAlphaForWindowAlpha(windowAlpha: Float): Int {
        if (windowAlpha >= 1f) return PinholeCalibration.MASK_ALPHA
        require(windowAlpha >= 0.7f) {
            "windowAlpha=$windowAlpha below 0.7 floor — pinhole detection unreliable"
        }
        return ((1f - 0.5f / windowAlpha) * 255f).roundToInt().coerceIn(0, 255)
    }

    // ── Overlay-specific mutable state ───────────────────────────────────

    private var floatingMenu: FloatingIconMenu? = null
    /** Display the open [floatingMenu] is on. */
    private var floatingMenuDisplayId: Int? = null
    /** Keeps the open [floatingMenu]'s PlayTranslate Cloud strip current
     *  ([followCloudStatus]); null while no menu is open or its strip is not
     *  shown. Set beside [floatingMenu] and cancelled where that is cleared,
     *  [dismissFloatingMenu], so it lives exactly as long as the menu. */
    private var floatingMenuCloudJob: Job? = null

    private var pillView: View? = null
    /** Display the no-text pill is on. */
    private var pillDisplayId: Int? = null
    private val pillHandler = Handler(Looper.getMainLooper())

    /** The translation-error pills. See [showTranslationErrorPill]. */
    private val errorPills = TranslationErrorPills(overlayHost).also {
        it.onWithdrawn = com.playtranslate.translation.TranslationErrors::onPillsWithdrawn
        it.onSettings = ::onPillSettingsTapped
    }

    /** The gear's "Discard card?" confirm while it is up
     *  ([onPillSettingsTapped]). */
    private var discardCardConfirm: OverlayAlert? = null

    // ── Region overlays (delegated to RegionOverlayController) ───────────

    /** True when the region drag editor overlay is showing. */
    val isRegionEditorActive: Boolean get() = regionController.isRegionEditorActive

    fun showRegionOverlay(display: Display, region: RegionEntry) =
        regionController.showRegionOverlay(display, region)

    fun updateRegionOverlay(region: RegionEntry) =
        regionController.updateRegionOverlay(region)

    fun hideRegionOverlay() = regionController.hideRegionOverlay()

    fun showRegionIndicator(
        display: Display,
        region: RegionEntry,
        persistent: Boolean = false
    ) = regionController.showRegionIndicator(display, region, persistent)

    fun hideRegionIndicator(force: Boolean = false) =
        regionController.hideRegionIndicator(force)

    fun showRegionDragOverlay(
        display: Display,
        initRegion: RegionEntry = RegionEntry("", 0.25f, 0.75f, 0.25f, 0.75f),
        onRegionChanged: (RegionEntry) -> Unit
    ) = regionController.showRegionDragOverlay(display, initRegion, onRegionChanged)

    fun hideRegionDragOverlay() = regionController.hideRegionDragOverlay()

    fun getDragRegion(): RegionEntry = regionController.getDragRegion()

    fun hideRegionEditor() = regionController.hideRegionEditor()

    // ── No-text pill toast ────────────────────────────────────────────────

    /**
     * Shows a brief pill-shaped overlay near the top of the game display with
     * the app icon and [message]. Auto-dismisses with a fade-out.
     */
    fun showNoTextPill(display: Display, message: String) {
        hideNoTextPill()

        val ctx = context.createDisplayContext(display)
        val wm = OwnWindows.manager(ctx) ?: return
        val dp = ctx.resources.displayMetrics.density
        val icon = ctx.packageManager.getApplicationIcon(ctx.applicationInfo)

        val iconSizePx = (20 * dp).toInt()
        val padH = (14 * dp).toInt()
        val padV = (10 * dp).toInt()
        val iconTextGap = (8 * dp).toInt()
        val cornerRadius = 24 * dp

        val view = object : View(ctx) {
            private val bgPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.argb(210, 30, 30, 30)
            }
            private val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
                textSize = 13 * dp
                typeface = android.graphics.Typeface.DEFAULT
            }

            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val textW = textPaint.measureText(message).toInt()
                val w = padH + iconSizePx + iconTextGap + textW + padH
                val h = padV + maxOf(iconSizePx, (textPaint.descent() - textPaint.ascent()).toInt()) + padV
                setMeasuredDimension(w, h)
            }

            override fun onDraw(canvas: android.graphics.Canvas) {
                val w = width.toFloat()
                val h = height.toFloat()
                canvas.drawRoundRect(0f, 0f, w, h, cornerRadius, cornerRadius, bgPaint)

                val iconTop = ((h - iconSizePx) / 2f).toInt()
                icon.setBounds(padH, iconTop, padH + iconSizePx, iconTop + iconSizePx)
                icon.draw(canvas)

                val textX = (padH + iconSizePx + iconTextGap).toFloat()
                val textY = (h - textPaint.descent() - textPaint.ascent()) / 2f
                canvas.drawText(message, textX, textY, textPaint)
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayHost.windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        )
        val displayId = display.displayId
        var placed = false
        overlayHost.addOverlayWindow(
            view, wm, params, displayId,
            OverlayPlacement.Placed { env, p ->
                // Below a stack whose size is not known yet the pill keeps
                // where it is; only its first placement has nowhere to keep.
                if (placed && errorPills.stackPendingOn(displayId)) return@Placed
                placeNoTextPill(displayId, dp, env, p)
                placed = true
            },
        )
        pillView = view
        pillDisplayId = displayId

        // Brief display, then fade out
        pillHandler.postDelayed({
            view.animate()
                .alpha(0f)
                .setDuration(500L)
                .withEndAction { hideNoTextPill() }
                .start()
        }, 1500L)
    }

    fun hideNoTextPill() {
        pillHandler.removeCallbacksAndMessages(null)
        val view = pillView
        if (view != null) {
            view.animate().cancel()
            overlayHost.removeOverlayWindow(view)
        }
        pillView = null
        pillDisplayId = null
    }

    /** The no-text pill's layout ([OverlayPlacement.Placed]), run by the host
     *  whenever it lays the display out: centred in the pills' clear span, on
     *  the pills' line ([TranslationErrorPills.topLinePx]: the error pills
     *  and this one hang from the same line, 2026-10-11), or just
     *  below the translation-error pills when they are up on this display,
     *  not on top of them. The stack's first layout and every change of its
     *  height lay the display out again, so the two never overlap whatever
     *  order they came in (an error and a failed capture land in the same
     *  frame). */
    private fun placeNoTextPill(displayId: Int, density: Float, env: OverlayEnvironment, p: WindowManager.LayoutParams) {
        p.gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
        p.x = TranslationErrorPills.centreOffsetPx(env)
        p.y = errorPills.belowStackY(displayId) ?: TranslationErrorPills.topLinePx(env, density)
    }

    // ── Translation-error pills ──────────────────────────────────────────

    /**
     * Put [error]'s pill up; true when it is on screen afterwards (the
     * tracker counts the error as shown only then). A pill shows whenever
     * there's an error, whatever is in front, and × closes it
     * (2026-09-27). It goes to the display the stack is already on, else the
     * primary game display (the one the user last interacted with), else
     * the first configured capture display. A display that leaves the
     * capture set takes its pills with it ([reconcileFloatingIcons]).
     */
    fun showTranslationErrorPill(error: TranslationError): Boolean {
        val displayId = errorPills.displayId
            ?: CaptureService.instance?.primaryGameDisplayId()
            ?: CaptureBackendResolver.active().capturableTargets(context, Prefs(context).captureDisplayIds).firstOrNull()
            ?: Display.DEFAULT_DISPLAY
        val display = (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
            .getDisplay(displayId) ?: return false
        return errorPills.show(context.createDisplayContext(display), displayId, error)
    }

    /** Make the pill of [error]'s owner say [error] if it has one up; see
     *  [com.playtranslate.translation.TranslationErrorPresenter.update]. */
    fun updateTranslationErrorPill(error: TranslationError) {
        errorPills.update(error)
    }

    /** Take down the pills of [keys]' owners (their service works again). */
    fun hideTranslationErrorPills(keys: Collection<TranslationErrorKey>) {
        errorPills.hide(keys.map { it.owner }.distinct())
    }

    /**
     * The gear on [owner]'s pill on [displayId]. With an Anki card editor
     * open in the workspace, going to the settings closes it, and its card
     * with it (the workspace keeps no drafts), so the gear asks first, over
     * the editor (2026-09-28): "Go to settings" goes on, and Cancel
     * leaves everything as it was, the pill included. A tap while the
     * confirm is up adds no second one.
     */
    private fun onPillSettingsTapped(owner: String, displayId: Int) {
        val ws = workspace
        if (ws == null || !ws.holdsCardEditor) {
            openServiceSettingsFromPill(owner, displayId)
            return
        }
        if (discardCardConfirm?.isShowing == true) return
        discardCardConfirm = ws.showAlert { themed ->
            setTitle(themed.getString(R.string.translation_error_discard_card_title))
            setMessage(themed.getString(R.string.translation_error_discard_card_message))
            // The app's destructive confirm style (the prompt editor's
            // Discard, History's Clear).
            addButton(
                themed.getString(R.string.translation_error_discard_card_confirm),
                themed.themeColor(R.attr.ptDanger),
                themed.themeColor(R.attr.ptAccentOn),
            ) {
                discardCardConfirm = null
                openServiceSettingsFromPill(owner, displayId)
            }
            addCancelButton(themed.getString(R.string.btn_cancel)) { discardCardConfirm = null }
        }
    }

    /**
     * Go on from the gear on [owner]'s pill on [displayId]
     * (2026-09-28): take the pill down, stop auto-translate, have the tracker
     * show the owner's next failure again, and open the owner's settings
     * page, where the user can fix the service or switch to another: the
     * Translation services page, or PlayTranslate Cloud's own page for its
     * pill, where its switch, key and status are (its row on the services
     * page, debug builds only, opens the same page).
     * Auto-translate stops, as the app's own pauseLiveMode stops it, rather
     * than pausing to resume by itself: the user starts it again back in the
     * game, and that start is the next try. It stops first, here and now,
     * whatever is in front (Codex adversarial 2026-09-28): the tracker's
     * cutoff must fall after the stop, and a start still pending (the
     * consent dialog, the stream probe) must not begin later, which only
     * [CaptureService.stopLive] prevents. The icon's gestures and the quick
     * menu route their stops through the app when it is in front on another
     * screen; that stop is this same call, a message later, and the app
     * ignores it while a start is still pending. A session the word lens
     * paused for its lookup is stopped too:
     * the lens restarts it when it closes, so the gear cancels that restart
     * before it closes the lens below (Codex adversarial 2026-09-28). The
     * lens can be open under the pill (a pill that goes up while the lens is
     * open sits above it, and a tap on the pill never reaches the lens); a
     * lens above the pill takes the tap itself, since its window catches
     * every tap off its card, so the gear runs only once that lens is gone.
     *
     * The page opens where our app is in front, else on the pill's display,
     * as the quick menu's links to an activity do ([launchOnOverlayDisplay]),
     * and SINGLE_TOP: a pill shows over our own screens too, and a tap while
     * the page is already on top opens no second copy. Our full-screen
     * surfaces over the game go first: an overlay window sits above every
     * activity, so one left up would cover the page. The quick menu's, the
     * capture panel's and the workspace's own links to an activity close
     * them the same way.
     */
    private fun openServiceSettingsFromPill(owner: String, displayId: Int) {
        errorPills.hide(listOf(owner))
        CaptureService.instance?.let { svc ->
            if (svc.isLive || svc.isLiveStartPending) svc.stopLive()
        }
        cancelLensResumes()
        com.playtranslate.translation.TranslationErrors.onPillSettingsOpened(owner)
        dismissFloatingMenu()
        endCaptures(CaptureTakeover.ERROR_PILL_GEAR)
        dismissWorkspace()
        if (regionController.isRegionEditorActive) regionController.hideRegionEditor()
        dismissAllDragLookupPopups()
        val page = if (owner == PtCloudService.ID) PtCloudSettingsActivity::class.java
            else TranslationServicesActivity::class.java
        launchOnOverlayDisplay(
            Intent(context.applicationContext, page)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            displayId,
        )
    }

    /** Capture ended here (Turn Off, MediaProjection not turned on, or this
     *  backend torn down: [hideFloatingIcon] with endsCapture): the pills
     *  come down, and the errors they showed are forgotten, so the next
     *  session shows any that still hold once (2026-09-26).
     *  Idempotent. */
    private fun endErrorPillSession() {
        errorPills.removeAll()
        com.playtranslate.translation.TranslationErrors.onCaptureSessionEnded()
    }

    /**
     * Screen rects of this app's own chrome on [displayId] that a raw frame
     * of that display can contain: the floating icon, or the detached
     * window that is the controls' other form, the transient pill
     * ([showNoTextPill]: no text, a failure, "Translating from …") and the
     * translation-error pills. The OCR sites black these out of frames
     * stamped `includesOwnOverlays`, and the pinhole mode leaves them out of
     * its change gate and its pinhole sampling. Read live, main thread.
     *
     * The window's rect is what lets an auto-translate whose results show
     * in its page read raw frames of the whole screen, with the window
     * never blanked for a read ([LiveSurface.WINDOW_PAGE]): the page's own
     * text, the last result among it, is not read back as the game's. The
     * rect is where the window stands when the OCR starts, a moment after
     * the frame was taken, so that loop takes no frame while the window is
     * moving ([windowBusy]). What the window opens over the game (a menu,
     * the lens, a page, an alert) has no rect here: the loop waits while
     * one is up.
     */
    fun ownChromeRects(displayId: Int): List<Rect> =
        listOfNotNull(
            getFloatingIconRect(displayId),
            windowHandles[displayId]?.screenRect(),
            noTextPillRect(displayId),
        ) + errorPills.screenRects(displayId)

    /**
     * An auto-translate whose results show in [displayId]'s detached
     * window ([LiveSurface.WINDOW_PAGE]) waits: something other than the
     * game and the window standing still is on that display, or the window
     * is not there.
     *  - The icon is standing in for the window (one of our own screens in
     *    front, the region editor): the session is still the window's and
     *    goes on when it is back. Reading meanwhile would read our own
     *    screen, blacked out of the frame, as a screen with nothing on it;
     *    a blackout that lasts is reported as an app that blocks capture.
     *  - The user is working in something opened over the game
     *    ([OverlayHost.hasContentWindowOn]: the window's menu or text-size
     *    picker, the word lens, a word's page, an Anki editor, a language
     *    picker, an alert). Its text is not the game's, and the result it
     *    is about must not be replaced under it.
     *  - The window is moving or being typed in ([DetachedWindow.busy]).
     *
     * On single-app recording none of these is in a frame; the wait is the
     * same there, so what the user sees does not depend on how the screen
     * is shared. The loop polls this, as it polls a hold
     * ([CaptureService.livePausedOn]).
     */
    fun windowBusy(displayId: Int): Boolean {
        val window = windowHandles[displayId] ?: return true
        return window.busy || overlayHost.hasContentWindowOn(displayId)
    }

    /** Every display's floating icon as it stands painted right now
     *  ([FloatingOverlayIcon.paintedDisc]), for a card's frame to be read
     *  against ([com.playtranslate.ui.GamePicture]); an icon a gesture or a
     *  glide owns is left out. Main thread. */
    fun floatingIconDiscs(): List<IconDiscFootprint> =
        iconHandles.values.mapNotNull { it.icon.paintedDisc() }

    /** The transient pill's rect on [displayId] while it is up there and laid
     *  out. "Translating from …" goes up as the restarted session takes its
     *  first frames, and a pill's message is real words. */
    private fun noTextPillRect(displayId: Int): Rect? {
        if (pillDisplayId != displayId) return null
        val view = pillView ?: return null
        if (!view.isLaidOut || view.width <= 0 || view.height <= 0) return null
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        return Rect(loc[0], loc[1], loc[0] + view.width, loc[1] + view.height)
    }

    // ── Translation overlay ──────────────────────────────────────────────

    /**
     * Show (or update) the translation/furigana overlay for [display]. Both
     * are now served from a single full-screen [TranslationOverlayView]; the
     * legacy per-box-window path was retired so the BufferQueue + composition
     * cost no longer scales with detected-box count.
     *
     * Touchability matrix — touchable only in the (one-shot + MediaProjection)
     * cell, non-touchable everywhere else:
     *
     *   - Live pinhole translation (pinhole=true): always non-touchable. On
     *     MP, α = system obscuring cap with a compensated mask alpha so the
     *     pinhole 50/50 blend math is preserved. On accessibility, α=1.0
     *     and default mask alpha.
     *   - Live furigana (pinhole=false, oneShot=false): always non-touchable
     *     so taps pass through to the game. α=1.0 on both backends — the
     *     MP BSP visual clamp does *not* engage for furigana because there's
     *     no per-pixel mask whose blend ratio matters; the clamp just dims
     *     uniformly, which is acceptable for the outlined-text rendering.
     *   - One-shot (oneShot=true) + MediaProjection: **touchable**, α=1.0.
     *     Touchable layers are exempt from the QTI BSP clamp, and an active-
     *     hold gesture means the user's finger is already committed to a
     *     trigger; capturing extra touches is acceptable. A tap-to-dismiss
     *     listener is attached as a backup release path.
     *   - One-shot + Accessibility: non-touchable, α=1.0 (cap-exempt by
     *     window type — no BSP/AOSP rule applies).
     *
     * `oneShot` distinguishes a hold-triggered overlay from a continuously-
     * running live overlay. `pinholeMode` alone is insufficient because both
     * live furigana and one-shot translation use pinholeMode=false but need
     * opposite touchability.
     */
    fun showTranslationOverlay(
        display: Display,
        boxes: List<TextBox>,
        cropLeft: Int, cropTop: Int,
        screenshotW: Int, screenshotH: Int,
        pinholeMode: Boolean = false,
        oneShot: Boolean = false,
        renderConfig: OverlayRenderConfig = OverlayRenderConfig.DEFAULT,
        authoritativeBounds: Boolean = false,
        growthLimits: GrowthLimits = GrowthLimits.NONE,
    ) {
        // Overlay is appearing — dismiss the loading spinner across all icons.
        // Through the service rather than straight at the icons: the service
        // holds the state of record, and [syncIconState] re-pushes it whenever
        // an icon is installed. Clearing only the views here would leave that
        // record armed, so the next sync would resurrect a spinner for a hold
        // that already delivered.
        CaptureService.instance?.setHoldLoading(false)

        val displayId = display.displayId
        // Reuse the existing view only if its pinhole mode, oneShot flag, AND
        // render config all match. The first two differing means the window
        // flags (FLAG_NOT_TOUCHABLE), params.alpha, mask alpha, or
        // tap-to-dismiss listener would need to change — those are fixed at
        // construction and at addOverlayWindow time. The render config is also
        // a constructor val (it selects the per-box render path and layout)
        // and derives from user-mutable prefs (target language, grow, minimum
        // text size), so a mid-session change must force a fresh view rather
        // than silently reuse the stale layout; comparing it whole means a
        // setting added to it is guarded without touching this line. In the
        // current call flow none of these transitions hit this reuse path
        // (beginHoldPreview/endHoldPreview teardown ensures a fresh create,
        // and the settings rows stop live mode), but the guard prevents a
        // future caller from silently inheriting a stale view.
        val existing = translationOverlayHandles[displayId]
        if (existing != null && existing.pinholeMode == pinholeMode && existing.oneShot == oneShot &&
            existing.renderConfig == renderConfig
        ) {
            existing.setBoxes(
                boxes, cropLeft, cropTop, screenshotW, screenshotH, authoritativeBounds,
                growthLimits = growthLimits,
            )
            return
        }
        hideTranslationOverlayForDisplay(displayId)

        val displayCtx = context.createDisplayContext(display)
        val themedCtx = android.view.ContextThemeWrapper(displayCtx, android.R.style.Theme_DeviceDefault)
        val wm = OwnWindows.manager(displayCtx) ?: return

        val isMediaProjection = overlayHost.windowType ==
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val mainTouchable = oneShot && isMediaProjection
        // Any LIVE mode on MP (pinhole OR furigana) needs α ≤ the system
        // obscuring cap so touches pass through to the game and so the QTI
        // BSP visual clamp doesn't engage. Only the (oneShot + MP) cell is
        // exempt — its window is touchable, which both bypasses the BSP
        // clamp and consumes touches itself.
        val mainWindowAlpha = if (!oneShot && isMediaProjection) {
            systemMaxObscuringOpacityForBackend()
        } else {
            1.0f
        }
        // Pinhole math only relevant when pinholeMode AND α < 1. Furigana on
        // MP also gets α < 1 from the rule above but doesn't use the mask
        // (pinholeMode is false → onSizeChanged skips the allocation).
        //
        // If a user/OEM has driven the system obscuring cap below 0.7, the
        // compensated mask alpha can't satisfy SPLATTER_THRESHOLD anymore
        // (per [pinholeMaskAlphaForWindowAlpha]'s floor). Rather than crash,
        // fall back to the default mask alpha; pinhole detection on that
        // device will be unreliable (potential oscillation on stable text)
        // but live mode still renders. A future improvement could disable
        // pinhole-classification entirely below the floor and run with
        // unconditional re-OCR — for now the soft fall-back avoids a hard
        // crash path.
        val mainMaskAlpha = when {
            !pinholeMode || mainWindowAlpha >= 1f -> PinholeCalibration.MASK_ALPHA
            mainWindowAlpha >= 0.7f -> pinholeMaskAlphaForWindowAlpha(mainWindowAlpha)
            else -> {
                Log.w(
                    TAG,
                    "obscuring-opacity cap=$mainWindowAlpha below 0.7 floor — " +
                        "using default mask alpha; pinhole detection may misfire on this device",
                )
                PinholeCalibration.MASK_ALPHA
            }
        }
        // Boost overlay-vs-text contrast on the configurations where the
        // hosting window's composited α is below 1.0 (MP non-touchable
        // live mode — the BSP clamps to ~0.8 and our cap-aware code
        // matches that). One-shot MP stays at α=1.0 (touchable), so no
        // boost; accessibility stays at α=1.0, no boost.
        val mainBoostContrast = mainWindowAlpha < 1f

        // One-shot MP cell only — the full-screen touchable window captures
        // every touch in its frame. The normal teardown path is the hold
        // release (icon/hotkey/button); the view's own onTouchEvent below
        // is a backup so any tap that lands on the overlay (e.g. a second
        // finger during hold, or a tap that races the hold-release
        // callback) still dismisses instead of being silently swallowed.
        // ACTION_DOWN is what dismisses; super.onTouchEvent leaves the
        // window in its touchable state.
        val mainView = TranslationOverlayView(
            themedCtx,
            pinholeMode = pinholeMode,
            maskAlpha = mainMaskAlpha,
            oneShot = oneShot,
            boostContrast = mainBoostContrast,
            renderConfig = renderConfig,
            onDismiss = if (mainTouchable) {
                { CaptureService.instance?.dismissLiveOverlay(displayId) }
            } else null,
        ).apply {
            setBoxes(boxes, cropLeft, cropTop, screenshotW, screenshotH, growthLimits = growthLimits)
        }
        val mainParams = buildOverlayLayoutParams(
            touchable = mainTouchable,
            windowAlpha = mainWindowAlpha,
        )
        if (!overlayHost.addOverlayWindow(mainView, wm, mainParams, displayId, OverlayPlacement.CaptureSpace)) return
        translationOverlayHandles[displayId] = mainView

        // Re-raise the floating icon above any touchable overlay so its
        // tap target stays accessible (one-shot MP cell). Unconditional —
        // cheap, and matches the previous per-box code's behavior.
        bringFloatingIconsToFront(displayId)
    }

    private fun buildOverlayLayoutParams(
        touchable: Boolean,
        windowAlpha: Float,
    ): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        if (!touchable) {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayHost.windowType,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            windowAnimations = 0
            alpha = windowAlpha
        }
    }

    /** Tear down a single display's translation overlay. Idempotent. */
    fun hideTranslationOverlayForDisplay(displayId: Int) {
        val view = translationOverlayHandles.remove(displayId) ?: return
        overlayHost.removeOverlayWindow(view)
    }

    /** Tear down translation overlays across every display. */
    fun hideTranslationOverlay() {
        if (translationOverlayHandles.isEmpty()) return
        val ids = translationOverlayHandles.keys.toList()
        for (id in ids) hideTranslationOverlayForDisplay(id)
    }

    /** Drop the translation overlay handle for [displayId] when the display
     *  has been resized / rotated since the windows were built. The main
     *  view's cached dimensions (width × height of the MATCH_PARENT window)
     *  drive the OCR→screen mapping inside [TranslationOverlayView]; a
     *  stale size mispositions every box. Removing the handle lets the
     *  next capture cycle rebuild at the current dimensions. No-op when
     *  the size is unchanged, so non-resize onDisplayChanged events
     *  (refresh rate, HDR) don't churn a live overlay. */
    private fun dropResizedTranslationOverlay(displayId: Int) {
        val view = translationOverlayHandles[displayId] ?: return
        val display = (context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
            ?.getDisplay(displayId) ?: return
        val size = getDisplaySize(display)
        if (size.x != view.width || size.y != view.height) {
            hideTranslationOverlayForDisplay(displayId)
        }
    }

    // ── The capture's presentation on the game display (dual-screen) ──────

    /** The capture's presentation when the app's results page is the panel:
     *  on a dual-screen device the result lives in MainActivity, so, unlike
     *  the single-screen sheet, which hosts its boxes as its own child, the
     *  game display gets a window of its own ([CaptureOverlayWindow]: the
     *  layer and the mode pill). Deliberately NOT in
     *  [translationOverlayHandles]: live cycles must never adopt or replace
     *  it, and its teardown must notify the app so its Show on screen
     *  refreshes. */
    private var captureWindow: CaptureOverlayWindow? = null
    private var captureWindowDisplayId = -1
    private var captureWindowWm: WindowManager? = null
    private var captureWindowParams: WindowManager.LayoutParams? = null
    /** The geometry (size and rotation) of [captureWindowDisplayId] when the
     *  window went up: its boxes are mapped onto that layout, so the window
     *  ends when the display's differs ([dropReconfiguredCaptureWindow]).
     *  Taken when the window goes up, not when its frame was taken: a turn
     *  while the frame is being read, before the window exists, is not
     *  seen. */
    private var captureWindowGeometry: DisplayGeometry? = null

    /** [flags] with the window's focus for its mode: focusable in
     *  Definitions, for Back and the controller cursor, with
     *  ALT_FOCUSABLE_IM so it never becomes an IME target, as the capture
     *  sheet does; not focusable otherwise, so keys stay the game's (and
     *  ALT_FOCUSABLE_IM cleared, since beside NOT_FOCUSABLE it means the
     *  opposite). */
    private fun captureWindowFocusFlags(flags: Int, wantsFocus: Boolean): Int =
        if (wantsFocus) {
            (flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()) or
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
        } else {
            (flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) and
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM.inv()
        }

    /** The window's mode changed: its focus follows
     *  ([captureWindowFocusFlags]). The flags change on the params the window
     *  was added with, which the overlay host stamped and pinned to the
     *  display's size; fresh params would undo the pin. */
    private fun refreshCaptureWindowFocus() {
        val window = captureWindow ?: return
        val wm = captureWindowWm ?: return
        val params = captureWindowParams ?: return
        val flags = captureWindowFocusFlags(params.flags, window.wantsFocus)
        if (flags == params.flags) return
        params.flags = flags
        try { wm.updateViewLayout(window, params) } catch (_: Exception) {}
        if (window.wantsFocus) window.requestFocus()
    }

    /** The showing window's dismissed-notification, invoked exactly once at
     *  the OWNERSHIP transition: [dismissCapturePresentation] disowns and
     *  notifies synchronously; the view's detach listener is only a backstop
     *  for removal paths that bypass it (the [OverlayHost.removeAll] sweep,
     *  display death). Keyed to ownership, not view lifetime, because
     *  [OverlayHost.removeOverlayWindow]'s removeView detaches ASYNC: a
     *  replaced window's late detach must not clear the state of a NEWER
     *  window shown in the meantime. */
    private var captureWindowOnDismissed: (() -> Unit)? = null

    /** The showing window's mode-changed notification
     *  ([switchCaptureWindowMode]): the page re-reads the window's mode from
     *  [capturePresentationMode] on this poke (its Show on screen, and the
     *  deferred translation chips in Translations need). */
    private var captureWindowOnModeChanged: (() -> Unit)? = null

    /** Whether the capture's window is owned (shown): THE truth the results
     *  page renders from, never a mirror. Flips synchronously with
     *  ownership: set before [presentCapture] returns, cleared at the top of
     *  [dismissCapturePresentation] (not at the async detach). */
    val capturePresentationShowing: Boolean
        get() = captureWindow != null

    /** The mode the showing window paints in; null with none. */
    val capturePresentationMode: OverlayMode?
        get() = captureWindow?.mode

    /**
     * Present a capture's [data] (read in [sourceLangId]) over [displayId]
     * for the app's results page, in [modeOverride] when the capture asked
     * for one (tap-to-lookup's Definitions), which is never written to the
     * setting, else in the capture's overlay mode as two screens read it
     * ([twoScreenMode]: a remembered None reads as auto-translate's mode,
     * Show on screen being what keeps the overlay off there). Nothing is
     * created for an override of None, nothing when the mode has nothing to draw from the
     * data (translations whose every box came back blank), nothing for a
     * capture read in a language other than the game's now
     * ([CaptureTakeover.LANGUAGE_CHANGE]), and nothing for one whose frame
     * was taken in another layout of the display than its own now
     * ([OneShotOverlayData.frameGeometry]). With [captureId] the page puts up
     * the capture of that id as it lands ([beginAppCapture]), and nothing
     * when something has taken the game screen since it started
     * ([endCaptures]); without, the user asked (the page's Show on screen),
     * and nothing is checked. The game screen
     * is the capture's now: a running auto-translate stops first, as a
     * capture stops it; the paint is never refused for it and no pref is
     * written. [screenshotPath] and [capturedAtMs] are the frame the
     * capture read, which a Definitions card's pages cite; a result that
     * lands later brings them ([updateCapture]). [onDismissed] fires once
     * when the window goes, by any path; [onModeChanged] whenever it
     * re-flavors in place, whoever switched it ([switchCaptureWindowMode]).
     * False = not shown.
     */
    fun presentCapture(
        displayId: Int,
        data: OneShotOverlayData,
        sourceLangId: SourceLangId?,
        screenshotPath: String? = null,
        capturedAtMs: Long = 0L,
        modeOverride: OverlayMode? = null,
        captureId: Long? = null,
        onModeChanged: () -> Unit = {},
        onDismissed: () -> Unit,
    ): Boolean {
        val prefs = Prefs(context)
        // The page keeps a result through a change of the game language,
        // which ended its window; its Show on screen would otherwise put it
        // back up, with cards that look its words up in the new language.
        if (sourceLangId != null && sourceLangId != prefs.sourceLangId) return false
        // The display turned or changed size since the frame was asked for:
        // its boxes may belong to the old layout and would sit over other
        // text (in Definitions a tap would open another word), whether the
        // capture lands after the turn or the page's Show on screen asks again
        // after the turn took its window down (dropReconfiguredCaptureWindow). The
        // result stays on the page, and the display turning back lets it up.
        val frameGeometry = data.frameGeometry
        if (frameGeometry != null && frameGeometry != context.displayGeometryOf(displayId)) return false
        if (captureId != null) {
            // Something took the game screen while it was read (or a newer
            // capture replaced it): the result stays on the page.
            if (appCapture?.id != captureId) return false
            // It lands now: from here the window is what is up.
            appCapture = null
        }
        dismissCapturePresentation()
        // A sheet from a one-screen capture (the app came to the front on a
        // second screen since): the app's page is the panel now.
        endCaptures(CaptureTakeover.APP_CAPTURE)
        val mode = twoScreenMode(
            modeOverride, prefs.overlayModeFor(OverlaySurface.CAPTURE), prefs.overlayModeFor(OverlaySurface.LIVE),
        )
        if (mode == OverlayMode.NONE) return false
        CaptureService.instance?.let { svc ->
            if (svc.isLive || svc.isLiveStartPending) svc.stopLive()
        }
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = dm.getDisplay(displayId) ?: return false
        val geometry = context.displayGeometryOf(displayId) ?: return false
        val displayCtx = context.createDisplayContext(display)
        val wm = OwnWindows.manager(displayCtx) ?: return false
        val window = CaptureOverlayWindow(
            displayCtx, wm, overlayHost, displayId, scope, prefs,
            onDismissRequested = { dismissCapturePresentation() },
            onNothingToShow = { dismissCapturePresentation() },
            onModePicked = { pickCaptureMode(it) },
        )
        window.bind(data, sourceLangId, mode, screenshotPath, capturedAtMs)
        if (window.showsNothing) {
            window.release()
            return false
        }
        // Backstop notification for removal paths that bypass
        // dismissCapturePresentation (the overlayHost.removeAll sweep, display
        // death): if this window still OWNS the slot when it detaches, no
        // deliberate teardown ran, so disown and notify here. The identity
        // guard is load-bearing: removeView detaches async, so a replaced
        // window's late detach lands after a newer window may already own
        // the slot, and firing unconditionally would clear the NEW window's
        // state while its touchable overlay still blocks the game.
        window.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) {
                if (captureWindow === window) {
                    captureWindow = null
                    captureWindowDisplayId = -1
                    captureWindowWm = null
                    captureWindowParams = null
                    captureWindowGeometry = null
                    captureWindowOnDismissed = null
                    captureWindowOnModeChanged = null
                    window.release()
                    onDismissed()
                }
            }
        })
        val params = buildOverlayLayoutParams(touchable = true, windowAlpha = 1.0f)
        params.flags = captureWindowFocusFlags(params.flags, window.wantsFocus)
        if (!overlayHost.addOverlayWindow(window, wm, params, displayId, OverlayPlacement.CaptureSpace)) {
            window.release()
            return false
        }
        captureWindow = window
        captureWindowWm = wm
        captureWindowParams = params
        captureWindowDisplayId = displayId
        captureWindowGeometry = geometry
        captureWindowOnDismissed = onDismissed
        captureWindowOnModeChanged = onModeChanged
        // Keep the floating icon's tap target above the touchable window,
        // matching the one-shot cell in showTranslationOverlay.
        bringFloatingIconsToFront(displayId)
        return true
    }

    /** More of the same capture translated: the window fills in place (the
     *  layer replaces its view itself when a render setting changed). A fill
     *  that leaves nothing to draw takes the window down. No-op with none. */
    fun updateCapture(data: OneShotOverlayData, screenshotPath: String? = null, capturedAtMs: Long = 0L) {
        val window = captureWindow ?: return
        window.update(data)
        if (screenshotPath != null) window.setFrame(screenshotPath, capturedAtMs)
        if (window.showsNothing) dismissCapturePresentation()
    }

    /** The window's mode pill picked [mode]. A mode it paints is written,
     *  as a pick on the one-screen sheet's pill writes it, and the window
     *  re-flavors in place ([switchCaptureWindowMode]). None is two screens'
     *  "nothing over the game", which is Show on screen off: the switch goes
     *  off, so later captures stay down too, and the window goes; the
     *  remembered mode stays, the one Show on screen turned back on puts up,
     *  and the one-screen sheet's own. */
    private fun pickCaptureMode(mode: OverlayMode) {
        val prefs = Prefs(context)
        if (mode == OverlayMode.NONE) {
            prefs.dualScreenShowOnScreen = false
            dismissCapturePresentation()
            return
        }
        prefs.captureOverlayMode = mode
        switchCaptureWindowMode(mode)
    }

    /** Re-flavor the showing window to [mode] in place, for the pill's pick
     *  of a mode it paints and for tap-to-lookup's switch alike: its key
     *  focus follows the mode, a mode with nothing to draw from the capture
     *  takes it down, and otherwise the page is told, since a switch it did
     *  not make can need the deferred translation (chips in Translations).
     *  No-op with none up. */
    private fun switchCaptureWindowMode(mode: OverlayMode) {
        val window = captureWindow ?: return
        window.setMode(mode)
        refreshCaptureWindowFocus()
        if (window.showsNothing) dismissCapturePresentation() else captureWindowOnModeChanged?.invoke()
    }

    /** Take the capture's window down. Idempotent. Disowns the window and
     *  fires its onDismissed SYNCHRONOUSLY, not from the async detach, so the
     *  page's state updates before any subsequent present can race the old
     *  window's removeView (see [captureWindowOnDismissed]). */
    fun dismissCapturePresentation() {
        val window = captureWindow ?: return
        val onDismissed = captureWindowOnDismissed
        val wm = captureWindowWm
        val params = captureWindowParams
        captureWindow = null
        captureWindowDisplayId = -1
        captureWindowWm = null
        captureWindowParams = null
        captureWindowGeometry = null
        captureWindowOnDismissed = null
        captureWindowOnModeChanged = null
        window.release()
        if (wm != null && params != null) {
            window.remove(wm, params) { overlayHost.removeOverlayWindow(window) }
        } else {
            overlayHost.removeOverlayWindow(window)
        }
        onDismissed?.invoke()
    }

    /** Drop the capture's window when its display's geometry (size or
     *  rotation) is no longer the one it went up in
     *  ([captureWindowGeometry]), the capture sheet's rule
     *  ([dismissCaptureResultPanelIfReconfigured]): its boxes are mapped
     *  onto that layout, so a turn, a 180 degree flip that keeps the size,
     *  or a resize leaves every box, and in Definitions every word a tap
     *  opens, over other text. A display that went away reads null and
     *  drops the window too. There is no cycle to rebuild it, so it just
     *  dismisses. */
    private fun dropReconfiguredCaptureWindow(displayId: Int) {
        if (displayId != captureWindowDisplayId || captureWindow == null) return
        if (context.displayGeometryOf(displayId) != captureWindowGeometry) dismissCapturePresentation()
    }

    /** Remove specific boxes from the overlay on [displayId] without
     *  rebuilding the entire view. */
    fun removeOverlayBoxes(
        toRemove: List<TextBox>,
        displayId: Int = CaptureService.instance?.primaryGameDisplayId()
            ?: android.view.Display.DEFAULT_DISPLAY,
    ) {
        translationOverlayHandles[displayId]?.removeBoxesByContent(toRemove)
    }

    // ── Floating icon ────────────────────────────────────────────────────

    /**
     * Reconcile the floating icons against [Prefs.captureDisplayIds]: tear down
     * icons whose display is no longer selected or has been disconnected, and
     * install icons for newly-selected, currently-connected displays. When
     * the selection names no connected display at all, the one icon goes on
     * the fallback display ([findIconDisplay]) and stays there. Idempotent:
     * a reconcile that finds the icons where they belong touches nothing,
     * which every caller relies on, since a display event, a resume, a
     * service reconnect and a language change all reconcile.
     *
     * [freshAppearance] is false only for the projection-loss reinstall
     * ([com.playtranslate.capture.MediaProjectionController.onProjectionLost]):
     * there the icon window was just swept by hideAll and comes straight back,
     * so to the user it never left. Two things key on it: the sonar intro
     * (replaying it there reads as a spurious flash, field report 2026-07-11)
     * and the game-audio consent prompt ([promptGameAudioConsentIfMissing] —
     * that path is how a status-bar-chip stop lands, and re-asking there would
     * nag right after a deliberate no). Every genuinely fresh appearance (app
     * open after boot, Turn On, backend swap, display added, Hide for Now and
     * back) gets the intro.
     *
     * [userSummoned] is true only from the two places a USER brings the icon
     * up — MainActivity.onResume (app open) and
     * [CaptureLifecycle.activateAccessibility] (Settings' Turn On, the Quick
     * Settings tile), the same two sites that lift
     * [CaptureLifecycle.floatingIconSuppressed] — and it is what lets a fresh
     * placement ask for the game-audio consent: a summon is a user interaction
     * the platform credits for the service start and the dialog launch, where
     * a passive reinstall (service reconnect, display hot-plug, backend swap,
     * language change) is not, and would also drop a system dialog over live
     * gameplay. Passive paths leave the outlined arrow as the signal.
     */
    fun reconcileFloatingIcons(freshAppearance: Boolean = true, userSummoned: Boolean = false) {
        val prefs = Prefs(context)
        // The "show the floating icon" preference gates the icon only on the
        // accessibility backend. MediaProjection has no in-app toggle for it
        // — single-screen never did, and dual-screen deliberately doesn't
        // either (the Game Screen Controls row in Settings is a11y-only) — so
        // the icon always shows there while the backend is activated (the
        // canShowControls gate below).
        val isMediaProjection =
            !CaptureBackendResolver.active().requiresAccessibilityService
        if (!isMediaProjection && !prefs.showOverlayIcon) {
            hideFloatingIcon("pref_disabled", endsCapture = true)
            return
        }
        if (!isMediaProjection && CaptureLifecycle.floatingIconSuppressed) {
            // Accessibility backend, but the user hasn't opened the app (or
            // pressed Turn On) this process lifetime — either the process
            // just came up from boot, or they chose "Hide for Now". Every
            // resurrect path (service reconnect, display hot-plug, backend
            // reresolve) lands here, so the icon genuinely stays away until
            // MainActivity's onResume lifts the suppression.
            hideFloatingIcon("suppressed_until_app_open", endsCapture = false)
            return
        }
        if (!canShowControls()) {
            // MediaProjection backend the user hasn't turned on. The gate is
            // deliberately the activation flag, not consent: a revoked
            // projection keeps the controls up — the next capture-requiring
            // action re-prompts (see onProjectionLost).
            hideFloatingIcon("controls_gated", endsCapture = true)
            return
        }
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        // Resolve the saved selection through the backend shim: the
        // selection's displays the backend can capture now, else its
        // fallback display (MediaProjection: the default display, the only
        // one it mirrors; accessibility: the selection's connected,
        // non-private displays, else the default display). The resolution
        // live start and the one-shot fan-out act on, so the icon is where
        // the capture is.
        val target = CaptureBackendResolver.active().capturableTargets(context, prefs.captureDisplayIds)

        // The displays the icons belong on, decided ONCE and read by both the
        // sweep below and the install after it. The shim answers with
        // connected displays (or the fallback) since 2026-10-14; the
        // connected filter and the legacy single-display fallback here are a
        // belt for a backend that ever does not, so the sweep and the install
        // can never disagree again. Until that date the accessibility shim
        // passed a selection through as stored, the sweep removed the
        // fallback's icon as "not selected" and the install put it back as a
        // fresh appearance, intro and all, on every reconcile; a Galaxy S25
        // reporting a display change every few seconds, each reconciled by
        // MainActivity's display listener, showed the icon popping inboard
        // and sliding back to the edge for as long as the app was open
        // (field report, 3.4.0).
        val connected = target.filterTo(linkedSetOf()) { dm.getDisplay(it) != null }
        val wanted: Set<Int> =
            if (connected.isNotEmpty()) connected
            else setOfNotNull(findIconDisplay(prefs)?.displayId)

        // Snapshot before mutating — tear down icons on displays not wanted
        // (deselected, disconnected, or the fallback once a selected display
        // is connected).
        val staleIds = (iconHandles.keys + windowHandles.keys).filter { it !in wanted }
        for (id in staleIds) hideControlsForDisplay(id, "reconcile_remove", endsCapture = false)

        // Each wanted display shows its form ([showFormOn]).
        var installedAny = false
        for (id in wanted) {
            val display = dm.getDisplay(id) ?: continue
            if (connected.isEmpty() && id !in iconHandles && id !in windowHandles) {
                Log.i(
                    TAG,
                    "reconcile: selection ${prefs.captureDisplayIds} names no connected display; " +
                        "controls on fallback display $id",
                )
            }
            if (showFormOn(display, prefs, freshAppearance)) installedAny = true
        }
        // Docked, or no longer a capture display, a display's page goes.
        dropPagesNoLongerOwed(prefs, wanted)
        // The translation-error pills live on a captured display: one that
        // left the capture set (deselected, or gone) takes them with it, and
        // each shows again at its next failure where capture is.
        errorPills.displayId?.let { id ->
            if ((id !in target && id !in iconHandles && id !in windowHandles) || dm.getDisplay(id) == null) {
                errorPills.withdrawFrom(id)
            }
        }
        // Once per reconcile, not per display, and only when controls (the
        // icon, or the window where that is the saved form) actually landed
        // as a fresh appearance the user asked for.
        if (installedAny && freshAppearance && userSummoned && !isMediaProjection) onFreshAccessibilityPlacement()
    }

    /**
     * User-summoned fresh accessibility-backend placement: arm or fire the
     * game-audio consent prompt. The prompt needs the capture service alive —
     * it asks through the SERVICE's own coroutine scope, never by starting
     * one: a cold app open installs this icon before the bind that creates
     * the service lands, and the tile summon cold-starts the service itself
     * ([CaptureLifecycle.activateAccessibility]) with the icon landing before
     * onCreate; a service start from HERE would be a second, uncredited one —
     * the API 31+ ForegroundServiceStartNotAllowedException crash class
     * (Codex adversarial finding 2026-09-05; the 2026-07-15 field crash in
     * CaptureService.updateForegroundState was the same class). With no
     * service the prompt is parked in [placementPromptPending] and fired by
     * [CaptureService]'s onCreate through [firePendingPlacementPrompt].
     */
    private fun onFreshAccessibilityPlacement() {
        val svc = CaptureService.instance
        if (svc == null) {
            placementPromptPending = true
            return
        }
        promptGameAudioConsentIfMissing(svc)
    }

    /** Armed by a fresh placement that found no service; consumed once by
     *  [firePendingPlacementPrompt], dropped when the last icon goes away
     *  (a parked prompt must not outlive the appearance that armed it). */
    private var placementPromptPending = false

    /** Called by [CaptureService] once it exists (onCreate, accessibility
     *  backend): fires the prompt a fresh placement parked, if any. */
    fun firePendingPlacementPrompt(svc: CaptureService) {
        if (!placementPromptPending) return
        placementPromptPending = false
        promptGameAudioConsentIfMissing(svc)
    }

    /**
     * Placement prompt: the controls (the floating icon, or the window where
     * that is the saved form) have just made a fresh appearance on
     * the accessibility backend and the Anki game-audio feature is on, but
     * the MediaProjection consent the recorder rides on is not held — ask for
     * it now. The recorder never prompts
     * ([com.playtranslate.capture.GameAudioRecorder]), and on this backend
     * nothing else asks until a live-mode start borrows the stream, so before
     * this the user had to find the menu's "Record audio" bar or Settings'
     * repair row to get the audio they had switched on. Predicate:
     * [GameAudioGate.wouldRunGivenConsent] — the bar's own predicate plus
     * session-active, which an installed accessibility icon already implies.
     * The mic gate is part of it, because an overlay cannot walk a
     * runtime-permission flow — a missing mic leaves the icon's outlined
     * arrow as the signal and Settings as the repair.
     *
     * Launch context: only user summons reach here ([reconcileFloatingIcons]'s
     * userSummoned — app open after boot, Hide for Now and back, Settings'
     * Turn On, the Quick Settings tile). The dialog is then an activity
     * started from the service on the heels of that interaction, the same
     * shape as the MediaProjection tile path (ACTION_MP_ACTIVATE →
     * activateMediaProjection → ensureConsent) and the live-start borrow, and
     * it rides the same background-activity-start allowances (a visible
     * activity for the app-open case; SYSTEM_ALERT_WINDOW or the bound
     * accessibility service for the tile). Passive reinstalls (service
     * reconnect, display hot-plug, backend swap) never ask — no credited
     * interaction, and a system dialog over live gameplay — and leave the
     * outlined arrow as the signal and the menu's "Record audio" bar as the
     * tap-to-fix. (A first cut gated on a resumed PlayTranslate activity
     * instead; that skipped the tile summon, field report 2026-09-05.) A
     * decline is not remembered; the next summoned fresh appearance asks
     * again, matching the live-start borrow's 2026-07-07 decision. Runs once
     * per reconcile (the controller's consent gate would dedupe concurrent
     * requests anyway) and never on the projection-loss reinstall — see
     * [reconcileFloatingIcons].
     */
    private fun promptGameAudioConsentIfMissing(svc: CaptureService) {
        if (!GameAudioGate.wouldRunGivenConsent(context, svc.mediaProjectionControllerIfInitialized)) return
        Log.i(TAG, "summoned controls placement with game audio unarmed: requesting screen-record consent")
        svc.requestAudioCaptureConsent()
    }

    /** Returns whether the icon window was actually added. */
    private fun installFloatingIconForDisplay(
        display: Display,
        prefs: Prefs,
        freshAppearance: Boolean = true,
    ): Boolean {
        val displayId = display.displayId
        // Idempotent: if an icon was already there for this display, tear it
        // down first so the closures and registry stay coherent.
        hideFloatingIconForDisplay(displayId, "recreating", endsCapture = false)

        val displayCtx = context.createDisplayContext(display)
        val wm = OwnWindows.manager(displayCtx) ?: return false

        // Drag-to-lookup: independent controller per display so the popup +
        // magnifier render against the correct display context.
        val popup = WordLookupPopup(displayCtx, wm, displayId, overlayHost)
        val magnifier = MagnifierLens(displayCtx, wm, displayId, overlayHost)
        val controller = DragLookupController(
            context = context,
            displayId = displayId,
            popup = popup,
            magnifier = magnifier,
            overlayHost = overlayHost,
        )
        // What this display's drag sessions paused (live mode, the region
        // indicator), restored when a session's lens settles.
        val dragPause = newLookupPause()
        controller.onSettled = { dragPause.settle(resumeLive = true) }

        // One builder for every icon VIEW this display ever shows — the
        // install below and bringFloatingIconsToFront's replace both use it,
        // so the wiring cannot drift between them. Fresh view + fresh params
        // per call: a view attaches only once, and the z-order re-raise
        // replaces the window instead of re-adding it (see
        // [FloatingIconHandle.makeIcon] for why). The session state the
        // callbacks mutate (dragPause) and the controller are captured from
        // THIS install scope, shared by every icon generation, so
        // controller.onSettled always settles the same record the current
        // icon's onDragStart began.
        val makeWiredIcon: () -> FloatingOverlayIcon = {
            val icon = FloatingOverlayIcon(displayCtx).apply {
                this.wm = wm
                this.displayId = displayId
                this.overlayHost = this@OverlayUiController.overlayHost
            }
            val params = WindowManager.LayoutParams(
                icon.viewSizePx, icon.viewSizePx,
                overlayHost.windowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.START
            }
            icon.params = params

            // Drag-end persists the icon's snap position to this display's slot.
            icon.onPositionChanged = { edge, fraction ->
                prefs.setIconPositionForDisplay(displayId, IconPosition(edge, fraction))
            }
            // Gesture → action, from the "On the floating icon" bindings
            // (IconGestureBindings.kt). Read at each gesture rather than at
            // install, so a change on the picker page, or of the source
            // language (a swap binding acts as its gesture's default on a
            // language without a reading hint), applies to the next touch of
            // this same icon; each `when` is exhaustive over its gesture's
            // candidates, so a new one cannot compile until it is wired here.
            // Every gesture's action needs CaptureService, and on the
            // accessibility backend this icon outlives it: between a kill
            // and the service's sticky restart, or after a refused restore
            // start, there is no instance and the actions used to be silent
            // no-ops (field report 2026-08-03). A gesture is a user
            // interaction, so request the start here, plainly. The start is
            // asynchronous (the instance exists only after the service's
            // onCreate, a later main-thread message), so THIS gesture still
            // dispatches against a null instance and is lost; the next one
            // finds the service. In the normal restore path the service was
            // started with the icon, milliseconds earlier, and no gesture
            // meets that window.
            fun ensureService() {
                if (CaptureService.instance == null) {
                    CaptureLifecycle.startCaptureServicePlain(context, "icon gesture")
                }
            }
            icon.onTap = {
                ensureService()
                when (prefs.iconTapAction) {
                    TapAction.OPEN_QUICK_MENU -> showFloatingMenu(display)
                    // The quick menu's Capture button without the menu: a
                    // fresh one-shot every tap, replacing any showing result.
                    TapAction.CAPTURE_SCREEN -> captureCurrentRegionForDisplay(displayId)
                    TapAction.TOGGLE_AUTO_TRANSLATE -> toggleLiveFromOverlay()
                    TapAction.SWAP_OVERLAY_MODE -> swapOverlayModeOrStartLive()
                    TapAction.CHANGE_GAME_LANGUAGE -> changeGameLanguage(display)
                    TapAction.TAP_TO_LOOKUP -> captureInDefinitions(displayId)
                }
            }
            // True from a hold whose toggle, or whose capture, stopped
            // auto-translate until that gesture ends: at the lift
            // (onHoldEnd), or at the drag a slide turns it into (onDragStart,
            // right after onHoldCancel in the same move). That drag leaves
            // live mode alone. With the app in front on the other screen the
            // stop is a message to MainActivity, so the drag can still find
            // the session running; pausing it would resume it when the lens
            // closes, undoing the stop.
            var holdStoppedLive = false
            // A drag's action is latched at its start, as a hold's is, so
            // its other legs go to the action that began. Pulling the
            // window out needs somewhere the window can show: where it
            // cannot, the drag is the lens.
            var draggedAction = DragAction.DEFAULT
            icon.onDragStart = {
                ensureService()
                draggedAction = prefs.iconDragAction.takeUnless {
                    it == DragAction.PULL_OUT_WINDOW && !offersDetach(displayId)
                } ?: DragAction.DEFAULT
                // Either drag hides the region preview and pauses live mode
                // while it lasts (the lens to show game text, the window
                // because auto-translate would read it), unless this drag's
                // hold just stopped it. A capture still on its way here
                // would come up over what the drag shows: it never does,
                // and the drag takes over what it paused.
                val stoppedByHold = holdStoppedLive
                holdStoppedLive = false
                endCaptures(CaptureTakeover.LENS_START, setOf(displayId), heir = dragPause)
                dragPause.begin(pauseLive = !stoppedByHold)
                when (draggedAction) {
                    DragAction.LOOKUP_WORDS -> controller.onDragStart()
                    // The window comes up with the first step, which says
                    // where the finger is ([pullTo]).
                    DragAction.PULL_OUT_WINDOW -> Unit
                }
            }
            icon.onDragMove = { rawX, rawY ->
                when (draggedAction) {
                    DragAction.LOOKUP_WORDS -> controller.onDragMove(rawX, rawY)
                    DragAction.PULL_OUT_WINDOW -> pullTo(display, rawX, rawY)
                }
            }
            icon.onDragEnd = {
                when (draggedAction) {
                    DragAction.LOOKUP_WORDS -> controller.onDragEnd()
                    DragAction.PULL_OUT_WINDOW -> {
                        endPull(displayId)
                        // Straight to its slot: the icon never left it.
                        true
                    }
                }
            }
            icon.onDragCancel = {
                when (draggedAction) {
                    DragAction.LOOKUP_WORDS -> controller.cancelDrag()
                    DragAction.PULL_OUT_WINDOW -> cancelPull(displayId)
                }
            }
            // The icon's drag visuals (window at the finger, ring) come up in
            // the same step as the lens, never before the drag's clean
            // capture is in hand: see FloatingOverlayIcon.dragVisualsRevealed.
            // Rewired per icon generation so the newest icon is the target.
            controller.onLensRevealed = { rawX, rawY -> icon.revealDragVisuals(rawX, rawY) }
            // A hold's action is latched at its start so the end and cancel
            // legs unwind the action that actually began, whatever the pref
            // says by then (dual-screen: the picker page can be open on the
            // other display). A lift that arrives with no start latched (the
            // hold runnable starved by a busy main thread; see
            // FloatingOverlayIcon.onTouchEvent) reads the current binding, as
            // the pre-binding wiring always ended a hold on lift.
            var heldAction: HoldAction? = null
            // True from a hold whose game-language change opened the picker
            // in the workspace until that gesture ends; a slide into a drag
            // closes it, as it closes the quick menu.
            var holdOpenedPicker = false
            icon.onHoldStart = {
                ensureService()
                val action = prefs.iconHoldAction
                heldAction = action
                when (action) {
                    // The quick menu's Capture button at the threshold, as
                    // the tap's: a fresh one-shot, replacing any showing
                    // result. It stops a running auto-translate first, which
                    // the drag a slide turns this hold into must not resume.
                    HoldAction.CAPTURE_SCREEN -> holdStoppedLive = captureCurrentRegionForDisplay(displayId)
                    HoldAction.SHOW_TRANSLATIONS -> CaptureService.instance?.holdStart(displayId)
                    HoldAction.OPEN_QUICK_MENU -> showFloatingMenu(display)
                    HoldAction.TOGGLE_AUTO_TRANSLATE -> holdStoppedLive = toggleLiveFromOverlay()
                    HoldAction.SWAP_OVERLAY_MODE -> swapOverlayModeOrStartLive()
                    HoldAction.CHANGE_GAME_LANGUAGE -> holdOpenedPicker = changeGameLanguage(display)
                    HoldAction.TAP_TO_LOOKUP -> holdStoppedLive = captureInDefinitions(displayId)
                }
            }
            icon.onHoldEnd = {
                when (heldAction ?: prefs.iconHoldAction) {
                    HoldAction.SHOW_TRANSLATIONS -> CaptureService.instance?.holdEnd()
                    // The menu opened at the hold threshold and stays up; the
                    // lift ends the gesture, not the menu. Likewise the
                    // capture, the toggle, the swap and the game-language
                    // change (its switch, or its picker) and tap-to-lookup
                    // (its boxes stay up for taps) happened at the
                    // threshold. A lift with no start latched (the starved
                    // hold above) therefore does none of them.
                    HoldAction.CAPTURE_SCREEN, HoldAction.OPEN_QUICK_MENU,
                    HoldAction.TOGGLE_AUTO_TRANSLATE, HoldAction.SWAP_OVERLAY_MODE,
                    HoldAction.CHANGE_GAME_LANGUAGE, HoldAction.TAP_TO_LOOKUP -> Unit
                }
                heldAction = null
                holdStoppedLive = false
                holdOpenedPicker = false
            }
            icon.onHoldCancel = {
                when (heldAction ?: prefs.iconHoldAction) {
                    HoldAction.SHOW_TRANSLATIONS -> CaptureService.instance?.holdCancel()
                    // The finger slid past the drag threshold with the menu up:
                    // the drag lens is about to open, and the menu's full-screen
                    // window would stay up alongside it (the icon owns this
                    // touch stream, so the menu's tap-outside dismissal never
                    // saw it). Close the menu first.
                    HoldAction.OPEN_QUICK_MENU -> dismissFloatingMenu()
                    // The one-shot this hold began ends, as the
                    // hold-to-preview's does, on its way or showing, on one
                    // screen or two (the app's result then stays on its
                    // page), and the auto-translate it stopped stays stopped
                    // (holdStoppedLive), as after the toggle's stop.
                    // Tap-to-lookup is a capture in Definitions, so the same.
                    HoldAction.CAPTURE_SCREEN, HoldAction.TAP_TO_LOOKUP ->
                        endCaptures(CaptureTakeover.HOLD_INTO_DRAG)
                    // The toggle and the swap stand: the drag that follows
                    // pauses a running auto-translate and resumes it
                    // afterwards, in the mode the swap left, as any drag does,
                    // and leaves a stopped one stopped, including one the
                    // toggle's stop hasn't reached yet (holdStoppedLive).
                    HoldAction.TOGGLE_AUTO_TRANSLATE, HoldAction.SWAP_OVERLAY_MODE -> Unit
                    // A switch of the game language stands, as the swap does.
                    // A picker this hold opened over the game closes, as the
                    // menu does: its full-screen window would stay up under
                    // the lens.
                    HoldAction.CHANGE_GAME_LANGUAGE -> if (holdOpenedPicker) dismissWorkspace()
                }
                heldAction = null
                holdOpenedPicker = false
            }
            icon.onAnyTouch   = {
                CaptureService.instance?.lastInteractedDisplayId = displayId
                DimController.notifyInteraction()
            }
            icon
        }

        val icon = makeWiredIcon()
        val params = icon.params ?: return false
        // This display's saved slot: the host places the window on it before
        // the window exists, and again whenever the display changes.
        val pos = prefs.iconPositionForDisplay(displayId)
        icon.setSlot(pos.edge, pos.fraction)
        val added = overlayHost.addOverlayWindow(
            icon, wm, params, displayId, OverlayPlacement.Placed(icon::layoutAtRest),
        )
        if (added) {
            iconHandles[displayId] = FloatingIconHandle(
                icon = icon,
                wm = wm,
                dragController = controller,
                dragPause = dragPause,
                makeIcon = makeWiredIcon,
            )
            // An icon is on screen: the persisted session record says so
            // (SessionMarker). This and the last removal in
            // hideFloatingIconForDisplay are its only writers in normal
            // operation; the record is the observable, never a derived flag.
            com.playtranslate.capture.SessionMarker.markOn(context)
            // The error pills keep priority over the icon (2026-09-27).
            errorPills.raise(displayId)
            // Fresh icon → sonar-ping intro. This is the only "icon added to
            // a window" path that means a fresh APPEARANCE (rotation reuses
            // the existing icon's params; bring-to-front swaps views for
            // z-order without going through install), so
            // gating the intro here gives us exactly the firing model the
            // design asked for: every fresh appearance, never a routine
            // re-layout. The one non-appearance that reaches this path is the
            // projection-loss sweep-and-reinstall, which passes
            // freshAppearance=false (see reconcileFloatingIcons).
            if (freshAppearance) showSonarIntro(icon, displayId, displayCtx, pos)
        } else {
            controller.destroy()
        }

        CaptureService.instance?.updateForegroundState()
        CaptureService.instance?.syncIconState()
        return added
    }

    /** Install the sonar-ping intro overlay on top of the just-added
     *  floating icon, hide the icon (alpha 0) for the duration, and restore
     *  it when the animation ends. Animation details live in
     *  [SonarPingIntroView]. */
    private fun showSonarIntro(
        icon: FloatingOverlayIcon,
        displayId: Int,
        displayCtx: Context,
        pos: IconPosition,
    ) {
        val wm = icon.wm ?: return
        val edge = if (pos.edge == FloatingOverlayIcon.Edge.LEFT.ordinal)
            FloatingOverlayIcon.Edge.LEFT
        else
            FloatingOverlayIcon.Edge.RIGHT

        val density = displayCtx.resources.displayMetrics.density
        val windowWidth = (SONAR_INTRO_WIDTH_DP * density).toInt()
        val windowHeight = (SONAR_INTRO_HEIGHT_DP * density).toInt()

        // Stop any earlier intro that's still playing on the same display
        // (e.g. very rapid toggle-off → toggle-on). The new install gets a
        // fresh intro from t=0.
        tearDownSonarIntroForDisplay(displayId)

        val intro = SonarPingIntroView(displayCtx, edge, icon)
        // Touchable (no FLAG_NOT_TOUCHABLE): a touch on the intro resolves
        // the animation early, and a touch on the carrier itself forwards
        // its gesture to the icon — a tap opens the floating menu, a hold
        // or drag starts the magnifying search. The window is far bigger
        // than that target (it holds the rings); the view hit-tests the
        // carrier rather than treating the whole ring field as a button,
        // and it stays touchable because a pass-through overlay is
        // opacity-capped on the MediaProjection backend. See
        // SonarPingIntroView.onTouchEvent.
        val params = WindowManager.LayoutParams(
            windowWidth, windowHeight,
            overlayHost.windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        // Hide the underlying icon while the intro plays. The intro's final
        // pose draws the same compact nub at the same screen position, so
        // restoring alpha at the end produces no visible seam.
        icon.alpha = 0f

        // The intro removes its own overlay window when it finishes —
        // whether the animation ran its course or a touch resolved it
        // early. The map slot is only nulled if it still points at THIS
        // intro (a newer one for the same display would have replaced it).
        intro.onFinished = {
            icon.alpha = 1f
            overlayHost.removeOverlayWindow(intro)
            if (activeSonarIntros[displayId] === intro) {
                activeSonarIntros.remove(displayId)
            }
        }

        val placement = OverlayPlacement.Placed { _, p -> placeSonarIntro(displayId, edge, p) }
        if (overlayHost.addOverlayWindow(intro, wm, params, displayId, placement)) {
            activeSonarIntros[displayId] = intro
            intro.start()
        } else {
            Log.w(TAG, "Sonar intro overlay add failed; skipping animation")
            icon.alpha = 1f
        }
    }

    /** Tear down the intro overlay if it's currently playing on [displayId].
     *  Called from [hideFloatingIconForDisplay] so a capture-off mid-intro
     *  doesn't leave a dangling overlay. Suppresses the completion callback
     *  so it doesn't try to remove the already-removed window. */
    private fun tearDownSonarIntroForDisplay(displayId: Int) {
        val intro = activeSonarIntros.remove(displayId) ?: return
        intro.onFinished = null
        overlayHost.removeOverlayWindow(intro)
    }

    /** The sonar intro's layout ([OverlayPlacement.Placed]), run by the host
     *  whenever it lays the display out: the window centred vertically on
     *  the icon's centre and, horizontally, on its dock line (its window's
     *  centre: the display edge, or inboard of what covers the icon there,
     *  [FloatingOverlayIcon.layoutAtRest]); the anchor inside the view is at
     *  view-x = (width - 28dp) for RIGHT or 28dp for LEFT, which lines the
     *  carrier up exactly with the icon's docked compact-mode position. Read
     *  from the display's current icon, so it follows an icon re-raised
     *  under it. The y is not clamped: the carrier must sit on the icon. */
    private fun placeSonarIntro(displayId: Int, edge: FloatingOverlayIcon.Edge, p: WindowManager.LayoutParams) {
        val icon = iconHandles[displayId]?.icon ?: return
        val iconParams = icon.params ?: return
        val iconDockX = iconParams.x + icon.viewSizePx / 2
        val iconCenterY = iconParams.y + icon.viewSizePx / 2
        p.x = when (edge) {
            FloatingOverlayIcon.Edge.RIGHT -> iconDockX - p.width
            FloatingOverlayIcon.Edge.LEFT -> iconDockX
        }
        p.y = iconCenterY - p.height / 2
    }

    /** The floating menu's layout ([OverlayPlacement.Placed]), run by the
     *  host whenever it lays the display out: the card beside the display's
     *  current icon, inside what the environment leaves clear
     *  ([FloatingIconMenu.placeNear], which does nothing when none of that
     *  moved). */
    private fun placeFloatingMenu(menu: FloatingIconMenu, displayId: Int, env: OverlayEnvironment) {
        val icon = iconHandles[displayId]?.icon ?: return
        val p = icon.params ?: return
        menu.placeNear(
            p.x + icon.viewSizePx / 2, p.y + icon.viewSizePx / 2, icon.currentEdge,
            env.geometry.width, env.geometry.height, env.covered,
        )
    }

    /**
     * Raise floating icons above newly added overlays by REPLACING each with
     * a freshly built view ([FloatingIconHandle.makeIcon]) rather than
     * removing and re-adding the same view. A same-view re-add would force
     * [com.playtranslate.overlay.WindowChurnGate.flushPendingFor] to destroy
     * the old surface synchronously beside this call site's adds — the
     * release-adjacent-to-create shape behind the Thor firmware
     * system_server crash. With a fresh view the old window retires through
     * the gate's quiet gap like every other removal, and the no-release-
     * near-create invariant holds with no exceptions.
     *
     * Replacement is REST-STATE only — guaranteed by the gesture guard
     * below — so the carry-over is exactly: persisted snap position (written
     * at every drag end) plus the four status flags. Session state
     * (drag/live pause bookkeeping) lives in the install scope shared by
     * every icon generation, not on the view.
     *
     * Pass [displayId] = null to bring every icon forward.
     */
    fun bringFloatingIconsToFront(displayId: Int? = null) {
        val targets: List<Pair<Int, FloatingIconHandle>> = if (displayId != null) {
            listOfNotNull(iconHandles[displayId]?.let { displayId to it })
        } else {
            iconHandles.map { it.key to it.value }
        }
        for ((id, handle) in targets) {
            // Never replace an icon the user is currently touching: killing
            // its window mid-gesture drops the touch stream, so the
            // finger-lift never fires onHoldEnd/onDragEnd and the held overlay
            // is never torn down. The re-raise resumes on the next overlay
            // update once the gesture ends.
            if (handle.icon.hasActiveGesture) continue
            val old = handle.icon
            // A blanked icon (window alpha 0) belongs to an in-flight clean
            // capture. Skip it: the icon stays one layer low until the next
            // overlay show re-raises it. (This skip predates OverlayHost
            // adding a window mid-capture blanked and restoring it with the
            // capture, which would now make a replacement safe; it is kept
            // as it was.)
            val oldParams = old.params
            if (oldParams == null || oldParams.alpha == 0f) continue
            val fresh = handle.makeIcon()
            val params = fresh.params ?: continue
            // The persisted slot, which every snap writes at its release: the
            // host places the fresh window on it before it exists.
            val pos = Prefs(context).iconPositionForDisplay(id)
            fresh.setSlot(pos.edge, pos.fraction)
            // On add failure keep the old icon: still functional, just below
            // the new overlay — matching the old path's failure behavior.
            if (!overlayHost.addOverlayWindow(
                    fresh, handle.wm, params, id, OverlayPlacement.Placed(fresh::layoutAtRest),
                )
            ) continue
            fresh.showLoading = old.showLoading
            fresh.degraded = old.degraded
            fresh.liveMode = old.liveMode
            fresh.glyph = old.glyph
            iconHandles[id] = handle.copy(icon = fresh)
            // The spinner is a SEPARATE window the old icon owns and tears
            // down on its own removal path — which the gate now defers. Clear
            // the flag (after the copy above) so the setter retires the old
            // spinner window through the gate NOW (defused, instantly
            // invisible) instead of a stale twin lingering beside the fresh
            // icon's spinner until the old icon's deferred detach.
            old.showLoading = false
            // Add-then-remove: worst case is one frame of two identical icons
            // at the same spot; remove-first would flash a frame of none. The
            // gate defuses the old window in this same call (invisible,
            // untouchable), so the ghost can't eat the fresh icon's taps.
            old.destroy()
            overlayHost.removeOverlayWindow(old)
            // The error pills keep priority over the icon (2026-09-27).
            errorPills.raise(id)
        }
    }

    /** Tear down one display's icon. [endsCapture] decides what becomes of
     *  what an in-flight drag session paused: capture carrying on (a
     *  recreate, a display gone, the task swiped away) restores live mode
     *  and the region indicator as the session's own end would; capture
     *  ending (Turn Off, a backend swap, projection lost) forgets them,
     *  since whoever ends capture stops live mode itself and a resume from
     *  here would start it on the backend being torn down. The controller's
     *  teardown reports nothing ([DragLookupController.destroy]); this is
     *  where the decision lives. */
    private fun hideFloatingIconForDisplay(displayId: Int, reason: String, endsCapture: Boolean) {
        // Kill any in-flight sonar intro on this display first — the intro
        // is a sibling overlay that doesn't track the icon's lifecycle, so
        // we have to remove it explicitly.
        tearDownSonarIntroForDisplay(displayId)
        val handle = iconHandles.remove(displayId) ?: return
        Log.i(TAG, "hideFloatingIcon[$displayId]: $reason")
        // A window its drag was pulling out goes with it: a window taken
        // down mid-gesture never hears the finger lift.
        dropPull(displayId)
        handle.dragController.destroy()
        if (endsCapture) handle.dragPause.drop() else handle.dragPause.settle(resumeLive = true)
        handle.icon.destroy()
        overlayHost.removeOverlayWindow(handle.icon)
        onControlsRemoved()
    }

    /** One display's controls went, icon or window. With none left on any
     *  display, whatever removed the last (Turn Off, Hide for Now, a Recents
     *  swipe, a backend swap, a graceful service end) ends what the persisted
     *  session record stands for; a kill never reaches here, which is exactly
     *  how the record outlives one. A swap of forms never passes through
     *  here with none left: the new form is up before the old one goes
     *  ([reconcileFloatingIcons]). */
    private fun onControlsRemoved() {
        if (!hasControlsOnScreen) {
            // A parked placement prompt belongs to the appearance that armed it.
            placementPromptPending = false
            com.playtranslate.capture.SessionMarker.markOff(context)
        }
        CaptureService.instance?.updateForegroundState()
        CaptureService.instance?.syncIconState()
    }

    // ── The detached window ──────────────────────────────────────────────

    /**
     * The form [displayId]'s controls show now ([shownControlsForm]): the
     * saved form, with the icon standing in for the window wherever the
     * window cannot be. [showFormOn] makes it so: for every capture display
     * from [reconcileFloatingIcons], and for the displays with controls up
     * whenever one of the inputs changes ([applyControlsForms]): the saved
     * form ([detachControls], [dockControls], [dockWindowsForLiveStart]),
     * one of our own screens coming or going ([OwnActivities]; MainActivity's
     * resume also reconciles) and the region editor opening or closing
     * ([regionController]).
     */
    private fun shownFormOn(displayId: Int, prefs: Prefs): ControlsForm = shownControlsForm(
        saved = prefs.detachedStateForDisplay(displayId).form,
        effectivelyOneScreen = effectivelySingleScreen(),
        ownActivityInFront = OwnActivities.anyOn(displayId),
        regionEditorOpen = regionController.regionEditorDisplayId == displayId,
    )

    /**
     * Make [display] show its form ([shownFormOn]). A display already
     * showing it is left alone, so a pass that changes nothing never
     * rebuilds a window or lifts it over a sheet or a page opened from it. A
     * change of form puts the new one up before the old one goes, so the
     * display is never without controls in between: the session record and
     * the service's foreground state read [hasControlsOnScreen] and would
     * otherwise see "off" halfway through a swap. Returns true when controls
     * went up where none were, in either form (what [reconcileFloatingIcons]
     * counts as a fresh placement: a window that comes straight up is as
     * much the user's summon as an icon is). A change of form is not one,
     * and an icon that takes a window's place gets no intro.
     *
     * With [animate] a change of form is shown as one thing becoming the
     * other: the window flies out of the icon's place, or into it
     * ([DetachedWindow.show], [DetachedWindow.remove]). Only where the user
     * asked for the change by name (Detach, Minimize, the dock gesture):
     * the icon standing in for the window, and a window docked because an
     * auto-translate it cannot hold is starting ([dockWindowsForLiveStart]),
     * change at once.
     */
    private fun showFormOn(
        display: Display,
        prefs: Prefs,
        freshAppearance: Boolean,
        animate: Boolean = false,
    ): Boolean {
        val id = display.displayId
        when (shownFormOn(id, prefs)) {
            ControlsForm.WINDOW -> {
                if (id in windowHandles) return false
                val replacesIcon = id in iconHandles
                // Where the icon is now, before it goes.
                val from = if (animate) getFloatingIconRect(id) else null
                if (!installDetachedWindowForDisplay(display, prefs, from)) return false
                // The icon goes. A session its lens stopped and owes a
                // restart comes back in the window's page, where that is
                // where a session shows now. Where it is not (more than one
                // captured display) the record is dropped (endsCapture): no
                // overlay tier runs with a window up, so nothing may
                // restart one from here.
                val windowTakesSession =
                    CaptureService.instance?.liveSurface() == LiveSurface.WINDOW_PAGE
                hideFloatingIconForDisplay(id, "swap_to_window", endsCapture = !windowTakesSession)
                return !replacesIcon
            }
            ControlsForm.ICON -> {
                if (id in iconHandles) return false
                val replacesWindow = id in windowHandles
                if (!installFloatingIconForDisplay(display, prefs, freshAppearance && !replacesWindow)) return false
                if (replacesWindow) {
                    // Where the icon that just went up is.
                    val to = if (animate) getFloatingIconRect(id) else null
                    removeDetachedWindowForDisplay(id, "swap_to_icon", to)
                }
                return !replacesWindow
            }
        }
    }

    /**
     * One of the form's inputs changed (the saved form, one of our own
     * screens coming or going, the region editor): every display with
     * controls up shows its form now. It only swaps what is up. Whether a
     * display has controls at all, and their first appearance with its
     * intro, stay [reconcileFloatingIcons]'s alone, so this never brings an
     * icon up ahead of the reconcile that would have introduced it.
     * [animate]: see [showFormOn].
     */
    fun applyControlsForms(animate: Boolean = false) {
        val prefs = Prefs(context)
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        for (id in (iconHandles.keys + windowHandles.keys).toList()) {
            val display = dm.getDisplay(id) ?: continue
            showFormOn(display, prefs, freshAppearance = false, animate = animate)
        }
        dropPagesNoLongerOwed(prefs)
        // The saved form also decides where a running auto-translate shows
        // ([liveSurface]): the session follows it in place, and the pages
        // follow the session. The surface's other input, the app coming
        // forward or going behind, is synced where it changes
        // ([MainActivity.isInForeground]'s setter): the flag flips after
        // the pass the app's start runs here, and the pass its resume runs
        // is [reconcileFloatingIcons], which does not sync.
        syncLiveSurface()
    }

    /**
     * An input of where auto-translate shows changed ([liveSurface]): a
     * running session swaps its tier in place where its surface changed
     * ([CaptureService.onLiveSurfaceChanged]; nothing where it did not),
     * then every page follows the session that is now its own
     * ([syncLivePages]). The controls' own changes of form call it between
     * saving the form and swapping the controls ([detachControls],
     * [dockControls]), so each control goes up to a session already of its
     * kind: the service's "can anyone still see this session" check
     * ([CaptureService.updateForegroundState]) runs as controls come and
     * go, and it reads a results-only session that is no longer the
     * window's as the app's page's, which stops when the app is not in
     * front.
     */
    private fun syncLiveSurface() {
        CaptureService.instance?.onLiveSurfaceChanged()
        syncLivePages()
    }

    /**
     * The pages no display is owed any more go. A page stays for a display
     * whose controls are still saved as the window (the icon may be standing
     * in for it), and for one whose window is still up whatever is saved: a
     * window the icon could not take the place of ([showFormOn] leaves it
     * up, so the display keeps its controls) still shows that page, and
     * released under it the page would be a dead one in a live window.
     * [wanted]: the displays that have controls at all, where the caller
     * has just decided that ([reconcileFloatingIcons]).
     */
    private fun dropPagesNoLongerOwed(prefs: Prefs, wanted: Set<Int>? = null) {
        detachedPages.keys
            .filter { it !in windowHandles }
            .filter {
                (wanted != null && it !in wanted) ||
                    prefs.detachedStateForDisplay(it).form != ControlsForm.WINDOW
            }
            .forEach { dropDetachedPage(it) }
    }

    /** A capture of [displayId] wrote the display's capture image, whoever
     *  it was for ([CaptureService.captureSaveToCache]): the detached
     *  window's page no longer shows a result of an earlier capture of it
     *  ([DetachedPage.onCaptureImageReplaced]). [byCaptureJob]: the
     *  writer is the service's one capture job. */
    fun onCaptureImageSaved(displayId: Int, byCaptureJob: Boolean) {
        detachedPages[displayId]?.onCaptureImageReplaced(byCaptureJob)
    }

    /** [displayId]'s capture region changed, whoever changed it
     *  ([CaptureService]'s region funnel): the window's Capture names the
     *  screen or the region, on its bar and on its capture button. A
     *  capture still on its way into the window is dropped, as the funnel
     *  drops one the service already has: it was asked for with the region
     *  as it stood, and landing afterwards it would put that region back
     *  over the one just set ([captureIntoWindow], which has let go of the
     *  capture before it applies a region itself). */
    fun onCaptureRegionChanged(displayId: Int) {
        if (windowCapture?.displayId == displayId) cancelWindowCapture()
        windowHandles[displayId]?.refreshActions()
    }

    /** Put [display]'s detached window up, flying out of [from] when it is
     *  given. Returns whether it was added. */
    private fun installDetachedWindowForDisplay(display: Display, prefs: Prefs, from: Rect? = null): Boolean {
        val displayId = display.displayId
        val displayCtx = context.createDisplayContext(display)
        val wm = OwnWindows.manager(displayCtx) ?: return false
        val window = DetachedWindow(displayCtx, wm, displayId, overlayHost, prefs, windowHost)
        if (!window.show(from)) return false
        // The display's page: the one a window before this left, or a new
        // one at its idle prompt.
        val page = detachedPages.getOrPut(displayId) { DetachedPage(displayCtx, wm, displayId, overlayHost) }
        window.attachPage(page)
        adoptDetachedWindow(displayId, window)
        return true
    }

    /** [window] is [displayId]'s controls from here. */
    private fun adoptDetachedWindow(displayId: Int, window: DetachedWindow) {
        windowHandles[displayId] = window
        Log.i(TAG, "showDetachedWindow[$displayId]")
        // Controls are on screen: the persisted session record says so, as
        // an icon's install does.
        com.playtranslate.capture.SessionMarker.markOn(context)
        // The error pills keep priority over the controls.
        errorPills.raise(displayId)
        CaptureService.instance?.updateForegroundState()
        CaptureService.instance?.syncIconState()
    }

    // ── The window pulled out of the icon ────────────────────────────────

    /** A drag of [displayId]'s icon that is pulling the window out of it
     *  ([DragAction.PULL_OUT_WINDOW]): the window, which shows as the icon
     *  or as itself as the finger goes, and its page. Neither is the
     *  display's until the finger lifts with the window out ([endPull]):
     *  until then the saved form is the icon and the icon is the controls. */
    private class Pull(val displayId: Int, val window: DetachedWindow, val page: DetachedPage)

    private var pull: Pull? = null

    /** A step of a pulling drag: the first puts the window up, as the icon
     *  it comes out of; the rest move it. */
    private fun pullTo(display: Display, rawX: Float, rawY: Float) {
        val current = pull
        if (current?.displayId == display.displayId) {
            current.window.pullTo(rawX, rawY)
            return
        }
        // One pull at a time: one left behind on another display goes.
        current?.let { dropPull(it.displayId) }
        beginPull(display, rawX, rawY)
    }

    private fun beginPull(display: Display, rawX: Float, rawY: Float) {
        val id = display.displayId
        val icon = iconHandles[id]?.icon ?: return
        val iconAt = getFloatingIconRect(id) ?: return
        val displayCtx = context.createDisplayContext(display)
        val wm = OwnWindows.manager(displayCtx) ?: return
        val window = DetachedWindow(displayCtx, wm, id, overlayHost, Prefs(context), windowHost)
        val side =
            if (icon.currentEdge == FloatingOverlayIcon.Edge.LEFT) DetachedWindowGeometry.Side.LEFT
            else DetachedWindowGeometry.Side.RIGHT
        if (!window.showPulled(iconAt, side, rawX, rawY)) return
        val page = DetachedPage(displayCtx, wm, id, overlayHost)
        window.attachPage(page)
        pull = Pull(id, window, page)
    }

    /**
     * The pulling finger lifted. With the window out, this is Detach
     * ([detachControls]) by another road: the window and its page become
     * the display's, the saved form is the window and the icon goes. An
     * auto-translate the drag stopped (every drag of the icon stops one and
     * owes its restart, [LookupPause]) comes back in the window's page: the
     * restart is settled only once the form is saved and the window and its
     * page are the display's, so the session that starts reads the window
     * as its surface and its first state has a page to land in; the icon,
     * taken down after, has nothing left to settle. Where the window cannot
     * take a session (more than one captured display) it stays stopped for
     * good, as before. Back in the icon, the window goes and the icon takes
     * the slot the finger left it at, with what the drag paused resumed.
     */
    private fun endPull(displayId: Int) {
        val p = pull?.takeIf { it.displayId == displayId }
        val handle = iconHandles[displayId]
        if (p == null || handle == null) {
            dropPull(displayId)
            handle?.dragPause?.settle(resumeLive = true)
            return
        }
        pull = null
        val docked = p.window.pullEnd()
        if (docked != null) {
            p.window.remove()
            p.page.release()
            val slot = iconSlotAt(displayId, docked.first, docked.second)
            Prefs(context).setIconPositionForDisplay(displayId, slot)
            handle.icon.setSlot(slot.edge, slot.fraction)
            handle.dragPause.settle(resumeLive = true)
            return
        }
        Prefs(context).setControlsForm(displayId, ControlsForm.WINDOW)
        val svc = CaptureService.instance
        val windowTakesSession = svc?.liveSurface() == LiveSurface.WINDOW_PAGE
        if (!windowTakesSession) {
            cancelLensResumes()
            if (svc != null && (svc.isLive || svc.isLiveStartPending)) stopLiveRouted()
        }
        detachedPages.put(displayId, p.page)?.release()
        adoptDetachedWindow(displayId, p.window)
        if (windowTakesSession) handle.dragPause.settle(resumeLive = true)
        hideFloatingIconForDisplay(displayId, "swap_to_window", endsCapture = true)
        syncLiveSurface()
    }

    /** The system took the pulling drag away: the window goes, the icon
     *  stays where it was. */
    private fun cancelPull(displayId: Int) {
        dropPull(displayId)
        iconHandles[displayId]?.dragPause?.settle(resumeLive = true)
    }

    /** Take down a window still being pulled out on [displayId], and its
     *  page with it. */
    private fun dropPull(displayId: Int) {
        val p = pull?.takeIf { it.displayId == displayId } ?: return
        pull = null
        p.window.remove()
        p.page.release()
    }

    /** The icon's slot at [side] for a finger at height [fingerY]: the
     *  icon's own slot arithmetic, so it lands where its own drag would
     *  have put it for a release at that height. */
    private fun iconSlotAt(displayId: Int, side: DetachedWindowGeometry.Side, fingerY: Int): IconPosition {
        val env = overlayHost.environment(displayId)
        val edge =
            if (side == DetachedWindowGeometry.Side.LEFT) FloatingOverlayIcon.Edge.LEFT
            else FloatingOverlayIcon.Edge.RIGHT
        val fraction = if (env == null) IconPosition.DEFAULT.fraction else IconDock.fraction(
            fingerY, env.geometry.height, env.covered,
            (IconDock.GESTURE_INSET_DP * context.resources.displayMetrics.density).toInt(),
        )
        return IconPosition(edge.ordinal, fraction)
    }

    /** Take [displayId]'s window down, flying into [to] when it is given.
     *  Either way the window is no longer this display's from here. */
    private fun removeDetachedWindowForDisplay(displayId: Int, reason: String, to: Rect? = null) {
        val window = windowHandles.remove(displayId) ?: return
        Log.i(TAG, "hideDetachedWindow[$displayId]: $reason")
        window.remove(to)
        onControlsRemoved()
    }

    /** Tear down one display's controls, whichever form is up. */
    private fun hideControlsForDisplay(displayId: Int, reason: String, endsCapture: Boolean) {
        hideFloatingIconForDisplay(displayId, reason, endsCapture)
        removeDetachedWindowForDisplay(displayId, reason)
        dropDetachedPage(displayId)
    }

    /** [displayId]'s page is done: a capture into it still on its way never
     *  lands, and its view-model, lens and Words page are released. */
    private fun dropDetachedPage(displayId: Int) {
        if (windowCapture?.displayId == displayId) cancelWindowCapture()
        detachedPages.remove(displayId)?.release()
    }

    private fun cancelWindowCapture() {
        windowCapture?.setup?.cancel()
        windowCapture = null
    }

    /**
     * A capture of [displayId] for its detached window's page. The sheet's
     * sequence ([showCaptureResultOverlay]) with the page in the sheet's
     * place: it takes the game screen, takes the clean frame, checks that it
     * is still the capture and that the display is as it was, configures the
     * service if it never was (a service the tile or a restore started has
     * no configuration, and a capture on one fails), applies the region, and
     * only then hands the session to the page. Until the frame is in the page
     * does not change: a repaint of ours just before a capture can be served
     * as its clean frame on whole-screen MediaProjection. The window itself
     * is taken out of the frame by the host's blanking, like the icon.
     *
     * With no service the press is lost and the next one finds it, as for
     * the icon's gestures ([windowHost] started it).
     */
    private fun captureIntoWindow(displayId: Int, region: RegionEntry, page: DetachedPage) {
        endCaptures(CaptureTakeover.WINDOW_CAPTURE)
        iconHandles.values.forEach { it.dragPause.settle(resumeLive = false) }
        dismissAllDragLookupPopups()
        val svc = CaptureService.instance ?: return
        val launchGeometry = context.displayGeometryOf(displayId) ?: return
        val capture = WindowCapture(displayId)
        windowCapture = capture
        capture.setup = scope.launch {
            FrameReaders.warm(Prefs(context).sourceLangId, deviceOnly = false)
            val frame = CaptureBackendResolver.active().captureSource?.requestClean(displayId)
            val current = windowCapture === capture && detachedPages[displayId] === page
            if (!current || context.displayGeometryOf(displayId) != launchGeometry) {
                frame?.bitmap?.recycle()
                if (windowCapture === capture) windowCapture = null
                return@launch
            }
            windowCapture = null
            // Not configureSaved: it resets region overrides to the saved
            // selection and tells every results page to go idle, on every
            // capture.
            svc.configureSavedIfNeeded(displayId)
            if (svc.activeRegionForDisplay(displayId) != region) svc.configureOverride(displayId, region)
            // The frame is in: mark the region that was captured, as a
            // capture from the app's page does on two screens (captureOnce
            // flashes its own; a whole-screen region has no mark). After the
            // frame, so the mark is never in it.
            if (frame != null) svc.flashRegionIndicator(displayId)
            // The page paints no boxes, so a hidden translation section can
            // put the backend call off (TranslationResultActivity's rule).
            // The service consumes and recycles the frame.
            page.follow(
                if (frame != null) svc.processScreenshot(frame, displayId, allowDeferTranslation = true)
                else svc.captureOnce(displayId, allowDeferTranslation = true),
            )
        }
    }

    /**
     * The icon on [displayId] becomes the window, from the quick settings'
     * Detach row. A session running or starting goes on, its results in the
     * window's page from here and nothing over the game
     * ([LiveSurface.WINDOW_PAGE]; the swap is [syncLiveSurface], the
     * pending start reads the form when it lands).
     *
     * Where the window cannot take the session (more than one captured
     * display) the old rule stands: no overlay tier runs with a window up,
     * so the session stops first and nothing a lens paused restarts it
     * ([dockWindowsForLiveStart] is the other half).
     *
     * A window the system refuses ([showFormOn] then leaves the icon up) is
     * a Detach that did not happen: the saved form goes back to the icon
     * and the session with it. Left saved as the window, the form would
     * keep the session the window's ([CaptureService.liveSurface] reads the
     * saved form) with no window to show it, waiting on one for as long as
     * the system went on refusing it ([windowBusy]), and saying nothing.
     * Only where the window should be up: an icon standing in for it is no
     * refusal (the row is not offered there, [offersDetach]).
     */
    private fun detachControls(displayId: Int) {
        val prefs = Prefs(context)
        prefs.setControlsForm(displayId, ControlsForm.WINDOW)
        val svc = CaptureService.instance
        if (svc != null && svc.liveSurface() != LiveSurface.WINDOW_PAGE) {
            cancelLensResumes()
            if (svc.isLive || svc.isLiveStartPending) stopLiveRouted()
        }
        syncLiveSurface()
        applyControlsForms(animate = true)
        if (displayId !in windowHandles && shownFormOn(displayId, prefs) == ControlsForm.WINDOW) {
            Log.w(TAG, "detachControls[$displayId]: the window was refused; the controls stay the icon")
            prefs.setControlsForm(displayId, ControlsForm.ICON)
            syncLiveSurface()
        }
    }

    /**
     * The window on [displayId] becomes the icon again: the bar's Minimize
     * ([slot] null, the icon returns to its saved slot) or the dock gesture,
     * which names the slot the finger was at. A session running in the
     * window goes on from the icon, as boxes over the game
     * ([syncLiveSurface]).
     */
    private fun dockControls(displayId: Int, slot: IconPosition?) {
        val prefs = Prefs(context)
        slot?.let { prefs.setIconPositionForDisplay(displayId, it) }
        prefs.setControlsForm(displayId, ControlsForm.ICON)
        syncLiveSurface()
        applyControlsForms(animate = true)
    }

    /**
     * Auto-translate is starting ([CaptureService.startLive], the one
     * function every start passes) somewhere other than in a window's page
     * (more than one captured display, or the app in front on a second
     * screen): every display whose controls were left as the window goes
     * back to the icon, whether the window is up or the icon is standing in
     * for it, and stays there. Auto-translate then runs as it does from the
     * icon; nothing brings the window back by itself. A start that fails
     * afterwards (consent refused) leaves the icon too. A session whose
     * results go to the window's page ([LiveSurface.WINDOW_PAGE]) never
     * comes here.
     */
    fun dockWindowsForLiveStart() {
        val prefs = Prefs(context)
        val ids = prefs.displaysWithWindowForm()
        if (ids.isEmpty()) return
        for (id in ids) prefs.setControlsForm(id, ControlsForm.ICON)
        applyControlsForms()
    }

    /** Whether [displayId]'s icon offers Detach: the window could show
     *  there now. That alone covers an icon standing in for the window: it
     *  stands in exactly where the window cannot show. */
    private fun offersDetach(displayId: Int): Boolean =
        shownControlsForm(
            ControlsForm.WINDOW,
            effectivelyOneScreen = effectivelySingleScreen(),
            ownActivityInFront = OwnActivities.anyOn(displayId),
            regionEditorOpen = regionController.regionEditorDisplayId == displayId,
        ) == ControlsForm.WINDOW

    /** What the detached window's controls do: the icon menu's actions. */
    private val windowHost = object : DetachedWindow.Host {
        private fun display(displayId: Int): Display? =
            (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(displayId)

        /** The icon's gestures' own start of a service that is not up: the
         *  start is asynchronous, so this press is lost and the next one
         *  finds the service (see the icon's ensureService). */
        private fun ensureService() {
            if (CaptureService.instance == null) {
                CaptureLifecycle.startCaptureServicePlain(context, "window control")
            }
        }

        override fun onCapture(displayId: Int) {
            ensureService()
            captureCurrentRegionForDisplay(displayId)
        }

        override fun onToggleAuto(displayId: Int) {
            ensureService()
            toggleLiveFromOverlay()
        }

        override fun onEditRegion(displayId: Int) {
            display(displayId)?.let { regionController.showRegionEditor(it) }
        }

        override fun onMinimize(displayId: Int) = dockControls(displayId, slot = null)

        override fun onExit(displayId: Int) {
            display(displayId)?.let { showHideConfirmAlert(it) }
        }

        override fun onDocked(displayId: Int, side: DetachedWindowGeometry.Side, fingerY: Int) =
            dockControls(displayId, iconSlotAt(displayId, side, fingerY))

        override fun onTouched(displayId: Int) {
            CaptureService.instance?.lastInteractedDisplayId = displayId
            DimController.notifyInteraction()
        }

        override fun quickSettings(displayId: Int, leave: () -> Unit): List<QuickSetting> =
            display(displayId)?.let { quickSettingsFor(it, leave) } ?: emptyList()

        override fun regionIsFullScreen(displayId: Int): Boolean =
            CaptureService.instance?.activeRegionForDisplay(displayId)?.isFullScreen ?: true

        override fun exitTurnsOff(): Boolean = CaptureLifecycle.hasActivateControl(context)
    }

    /** Tear down every floating icon. [endsCapture] says whether capture
     *  itself is ending (Turn Off, MediaProjection not turned on, this
     *  backend's teardown), which ends the translation-error pills' session
     *  too, icons or not. The icons also go while capture carries on (Hide
     *  for Now, a reconcile while they're suppressed, the task swiped away),
     *  and then the pills stay (2026-09-27). */
    fun hideFloatingIcon(reason: String, endsCapture: Boolean) {
        if (endsCapture) {
            endErrorPillSession()
            // Capture is ending: what the capture over the game took is
            // forgotten, as the icons' drag sessions' is below.
            sheetCapture?.let { payDebt(it, CaptureDebt.DROP) }
        } else {
            // The icons go while capture carries on (Hide for Now, the task
            // swiped away): a sheet up stays, but with no icon and the app
            // behind, a restart it owed would be torn straight down again
            // (updateForegroundState), after a possible consent prompt.
            sheetCapture?.pause?.cancelResume()
        }
        if (!hasControlsOnScreen) return
        Log.i(TAG, "hideFloatingIcon (all): $reason")
        val ids = (iconHandles.keys + windowHandles.keys).toList()
        for (id in ids) hideControlsForDisplay(id, reason, endsCapture)
    }

    /** "Hide for Now": tear the icons down AND suppress them for the rest of
     *  the process lifetime, so reconcile triggers (display hot-plug, service
     *  reconnect) can't resurrect them before the next app open — the
     *  behavior the confirm dialog promises. Plain [hideFloatingIcon] is the
     *  transient teardown for every other reason. */
    private fun hideFloatingIconUntilAppOpen(reason: String) {
        CaptureLifecycle.setFloatingIconSuppressed(context, true)
        hideFloatingIcon(reason, endsCapture = false)
    }

    /** Called by DragLookupController.openSentenceInApp before dismissing the
     *  magnifier so the dismiss-chain's resumeLiveMode is a no-op when the
     *  detail view will cover the live-mode surface. */
    fun cancelLivePauseObligation() {
        if (effectivelySingleScreen()) cancelLensResumes()
    }

    /** Every word lens that paused auto-translate for its lookup forgets to
     *  restart it when it closes. */
    private fun cancelLensResumes() {
        iconHandles.values.forEach { it.dragPause.cancelResume() }
        sheetCapture?.pause?.cancelResume()
    }

    /**
     * Returns the floating icon's bounding rect in screen coordinates for
     * [displayId], or null if no icon is showing on that display. Private:
     * frame consumers read [ownChromeRects], which also carries the error
     * pills, so none of them can handle the icon and forget the pills.
     */
    private fun getFloatingIconRect(displayId: Int): android.graphics.Rect? {
        val icon = iconHandles[displayId]?.icon ?: return null
        val p = icon.params ?: return null
        return android.graphics.Rect(p.x, p.y, p.x + icon.viewSizePx, p.y + icon.viewSizePx)
    }

    // ── Floating icon menu ───────────────────────────────────────────────

    private companion object {
        /** The floating menu's most-recently-used primary, shared across both
         *  backend controller instances and reset to the auto-translate default
         *  on process start. true = the capture button carries the strong
         *  accent, false = auto-translate. Set when the user taps either. */
        var captureIsPreferredPrimary = false

        /** The id of the last capture sent to the app
         *  ([MainActivity.EXTRA_REGION_CAPTURE_ID]), shared across both
         *  backend controller instances so no two captures share one. */
        var lastAppCaptureId = 0L
    }

    private fun showFloatingMenu(display: Display) {
        dismissFloatingMenu()
        val wm = OwnWindows.manager(context.createDisplayContext(display)) ?: return
        val themeRes = baseActivityTheme(context)
        val themedCtx = android.view.ContextThemeWrapper(context.createDisplayContext(display), themeRes)
        applyAccentOverlay(themedCtx.theme, context)
        val prefs = Prefs(context)
        // PlayTranslate Cloud's strip, while its OCR switch is on (never
        // outside debug builds): the strip answers whether the next capture
        // would be read there, which the translation switch has no say over.
        val cloudStrip = PtCloudService.config.ocrEnabled
        val menu = FloatingIconMenu(themedCtx, showCloudHeader = cloudStrip)
        menu.isSingleScreen = Prefs.isSingleScreen(context)
        menu.exitFlow = CaptureLifecycle.hasActivateControl(context)

        // Suppress live captures while menu is open.
        CaptureService.instance?.holdActive = true
        hideTranslationOverlay()
        endCaptures(CaptureTakeover.QUICK_MENU)

        val hintKind = SourceLanguageProfiles[prefs.sourceLangId].hintTextKind
        menu.hintModeLabel = hintModeLabelFor(prefs.overlayMode, hintKind)
        menu.isLiveMode = CaptureService.instance?.isLive == true
        menu.captureHighlighted = captureIsPreferredPrimary

        // Expanded settings-panel table: the quick settings' rows.
        menu.setPanelRows(
            quickSettingsFor(
                display,
                leave = { dismissFloatingMenu() },
                // Keep the auto-translate button's hint label in sync (Auto
                // Furigana / Auto Pinyin / Auto Translate); it's hidden while
                // the panel is open and shows the new label on collapse.
                onOverlayModeChanged = { next -> menu.hintModeLabel = hintModeLabelFor(next, hintKind) },
                offersDetach = offersDetach(display.displayId),
            )
        )
        menu.setDegradedWarning(
            CaptureService.instance?.degradationState?.value
                ?: com.playtranslate.ui.DegradedWarningKind.None,
            CaptureService.instance?.currentDegradedCooldown(),
        )
        menu.onHideIcon = {
            dismissFloatingMenu()
            PlayTranslateAccessibilityService.disable(context, "menu_turn_off")
        }
        menu.onHideTemporary = {
            dismissFloatingMenu()
            hideFloatingIconUntilAppOpen("menu_hide_temporary")
        }
        menu.onCloseRequested = {
            dismissFloatingMenu()
            showHideConfirmAlert(display)
        }
        menu.onDismiss = {
            val needsRefresh = floatingMenu != null && CaptureService.instance?.isLive == true
            dismissFloatingMenu()
            if (needsRefresh) {
                CaptureService.instance?.refreshLiveOverlay()
            }
        }
        menu.onToggleLive = {
            dismissFloatingMenu()
            toggleLiveFromOverlay()
        }
        menu.activeRegion = CaptureService.instance?.activeRegionForDisplay(display.displayId)
        menu.onRegionSelected = { region ->
            dismissFloatingMenu()
            CaptureService.instance?.configureOverride(display.displayId, region)
            if (CaptureService.instance?.isLive == true) {
                hideTranslationOverlay()
                CaptureService.instance?.refreshLiveOverlay()
            } else {
                if (effectivelySingleScreen()) {
                    handleRegionSelection(display.displayId, region)
                } else {
                    sendMainActivityIntent(MainActivity.ACTION_REGION_CAPTURE, display.displayId)
                }
            }
        }
        menu.onClearRegion = {
            clearRegionForDisplay(display.displayId)
        }
        menu.onCaptureRegion = {
            dismissFloatingMenu()
            if (effectivelySingleScreen()) {
                regionController.showRegionEditor(display)
            } else {
                sendMainActivityIntent(MainActivity.ACTION_ADD_CUSTOM_REGION, display.displayId)
            }
        }
        // One-shot capture of the *current* region (full screen unless one is
        // set) — the same action the "Tap to capture screen" hotkey runs
        // ([captureCurrentRegionForDisplay]).
        menu.onTranslateOnce = {
            dismissFloatingMenu()
            captureCurrentRegionForDisplay(display.displayId)
        }

        // "Record audio" — the in-game twin of Settings' audio repair row:
        // shown only when game-audio recording is enabled but the
        // MediaProjection consent it rides on is not held (the recorder never
        // prompts itself) and the mic is granted — the one repair an overlay
        // can perform ([GameAudioGate.consentMissingButFixable] carries the
        // why: the mic gate is part of the predicate, not the action).
        menu.showRecordAudio = GameAudioGate.consentMissingButFixable(
            context, CaptureService.instance?.mediaProjectionControllerIfInitialized,
        )
        menu.onRecordAudio = {
            dismissFloatingMenu()
            CaptureService.launchAudioCaptureConsent(context)
        }

        // Its glyph is set before the window exists and follows the network
        // while the menu is up.
        if (cloudStrip) floatingMenuCloudJob = followCloudStatus(menu)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayHost.windowType,
            // Non-focusable: a focusable overlay becomes the window the system
            // reads system-bar visibility from, dropping the game's immersive
            // state and popping the nav pill. The menu is touch-only (no key
            // or joystick handling) so it never needed focus.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        // The card goes beside the icon now and whenever the host lays the
        // display out again (a rotation, a bar the game shows or hides, the
        // icon coming to rest somewhere else).
        val displayId = display.displayId
        overlayHost.addOverlayWindow(
            menu, wm, params, displayId,
            OverlayPlacement.Placed { env, _ -> placeFloatingMenu(menu, displayId, env) },
        )
        floatingMenu = menu
        floatingMenuDisplayId = displayId
    }

    /**
     * Draws [menu]'s PlayTranslate Cloud strip ([drawCloudStatus]) now and
     * again at every change of the device's network and every restore
     * ([NetworkConnectivity.state]), until the returned job is cancelled
     * ([dismissFloatingMenu]).
     *
     * Started undispatched, so the first value is drawn before the menu's
     * window is added. When the network comes back, the "Connection failed"
     * cooldown it ended is already gone by the time the new value arrives
     * here ([NetworkConnectivity.state] is published after the registry's
     * fan-out, [com.playtranslate.translation.TranslationBackendRegistry.onConnectivityRestored],
     * has reached the cloud's one cooldown through its registry backend),
     * including on a
     * switch between two networks the OS has not validated, whose state does
     * not change. Nothing but the network redraws the strip while the menu
     * is up: a cooldown running out, or the switch or the key changed on the
     * other screen, shows the next time the menu opens.
     */
    private fun followCloudStatus(menu: FloatingIconMenu): Job =
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            NetworkConnectivity.state.collect { drawCloudStatus(menu, it.internet) }
        }

    /** Shows on [menu]'s PlayTranslate Cloud strip whether the next capture
     *  would go to the cloud ([PtCloudService.wouldTakeNextCapture]) with the
     *  device's network in [internet], reading the switch, the key, the game
     *  language and the cooldown afresh: the service's settings
     *  ([PtCloudService.config]), the ones the router reads. */
    private fun drawCloudStatus(menu: FloatingIconMenu, internet: NetworkConnectivity.InternetState) {
        menu.cloudAvailable = PtCloudService.wouldTakeNextCapture(
            PtCloudService.config, Prefs(context).sourceLangId, internet = internet,
        )
    }

    /** Overlay modes auto-translate offers for [id]: just Translation, unless
     *  the language carries a reading hint (Furigana/Pinyin), which adds the
     *  hint mode ([OverlayMode.offered]). */
    private fun availableOverlayModes(id: SourceLangId): List<OverlayMode> =
        OverlayMode.offered(OverlaySurface.LIVE, SourceLanguageProfiles[id].hintTextKind)

    /** The overlay mode after [prefs]'s current one among those its source
     *  language offers: the other one on a language with a reading hint,
     *  Translation again on one without. The floating menu's Overlays row
     *  and the icon's swap both switch to it. */
    private fun nextOverlayMode(prefs: Prefs): OverlayMode {
        val modes = availableOverlayModes(prefs.sourceLangId)
        return modes[(modes.indexOf(prefs.overlayMode) + 1) % modes.size]
    }

    /** User-facing name for an overlay [mode]. */
    private fun overlayModeLabel(mode: OverlayMode, hintKind: HintTextKind): String =
        context.getString(mode.labelRes(hintKind))

    /** Reading-hint label for the auto-translate button ("Auto Furigana" /
     *  "Auto Pinyin") while the hint overlay mode is active; null otherwise (the
     *  button reads "Auto Translate"). */
    private fun hintModeLabelFor(mode: OverlayMode, hintKind: HintTextKind): String? =
        if (mode == OverlayMode.FURIGANA && hintKind != HintTextKind.NONE) {
            when (hintKind) { HintTextKind.PINYIN -> "Pinyin"; else -> "Furigana" }
        } else null

    /**
     * The quick settings' rows for [display]: the panel behind the quick
     * menu's gear and the detached window's Settings popover list the same
     * rows with the same actions, built here once. [leave] closes the host's
     * own panel where a row hands over to another surface (a picker page, a
     * download page, the app); [onOverlayModeChanged] is the host's own
     * follow-up to the Overlays row.
     */
    private fun quickSettingsFor(
        display: Display,
        leave: () -> Unit,
        onOverlayModeChanged: (OverlayMode) -> Unit = {},
        offersDetach: Boolean = false,
    ): List<QuickSetting> {
        val prefs = Prefs(context)
        val hintKind = SourceLanguageProfiles[prefs.sourceLangId].hintTextKind
        val rows = mutableListOf<QuickSetting>()
        rows += QuickSetting(
            QuickSetting.Id.LANGUAGE,
            context.getString(R.string.floating_menu_panel_language),
            prefs.sourceLangId.displayName(),
        ) {
            leave()
            openGameLanguagePicker(display.displayId)
        }
        // The reader captures go to: PlayTranslate Cloud while its OCR switch
        // is on (the selection; the menu's strip says whether the next capture
        // would reach it), else the selected device engine.
        val ocrName =
            if (PtCloudService.config.ocrEnabled) PtCloudService.DISPLAY_NAME
            else OcrModelManager.selectedBackend(context, prefs.sourceLangId)?.ocrLabel(context) ?: "ML Kit"
        rows += QuickSetting(
            QuickSetting.Id.OCR, context.getString(R.string.floating_menu_panel_ocr), ocrName,
        ) {
            // Show the picker over the still-open panel. Live capture stays
            // paused under both: by the icon menu's holdActive, and for a
            // session in the detached window by its wait for whatever is
            // open over the game ([windowBusy]: the window's menu, then the
            // picker's alert). The host leaves only if a different engine is
            // chosen (see showOcrPicker); Cancel/same-engine returns to it.
            showOcrPicker(display, prefs.sourceLangId, leave)
        }
        // MangaOCR quick toggle: only when the pack is installed and the source
        // language can use it (JA + MNN abi) — mirrors the settings cell's gates.
        // Flips the pref in place via the one write path (no dialog, pack kept).
        val mangaOcrStateLabel = { on: Boolean ->
            context.getString(
                if (on) R.string.capture_lifecycle_state_on else R.string.capture_lifecycle_state_off
            )
        }
        if (
            prefs.sourceLangId == SourceLangId.JA &&
            OcrModelManager.isMnnAvailable() &&
            MangaOcrProvisioning.helper().isInstalled(context)
        ) {
            rows += QuickSetting(
                QuickSetting.Id.MANGA_OCR,
                context.getString(R.string.settings_ocr_use_manga_title),
                mangaOcrStateLabel(prefs.useMangaOcr),
            ) { row ->
                val on = !Prefs(context).useMangaOcr
                MangaOcrProvisioning.setEnabled(context, on, scope)
                row.value = mangaOcrStateLabel(on)
            }
        }
        // Overlays row: the current overlay mode as the value; tapping cycles
        // the available modes. Shown only when the language offers more than one.
        if (hintKind != HintTextKind.NONE) {
            rows += QuickSetting(
                QuickSetting.Id.OVERLAYS,
                context.getString(R.string.floating_menu_panel_overlays),
                overlayModeLabel(prefs.overlayMode, hintKind),
            ) { row ->
                val next = nextOverlayMode(prefs)
                // A running session switches with the row, as it does for the
                // icon's swap and the tap hotkeys, so the saved mode is always the
                // one on screen: those decide from it. The rebuild happens while
                // the menu keeps live mode paused, so nothing captures until the
                // menu closes, whichever way it closes.
                val svc = CaptureService.instance
                if (svc?.isLive == true) {
                    switchLiveOverlayMode(svc, next, "menu_overlays_row")
                } else {
                    prefs.overlayMode = next
                }
                row.value = overlayModeLabel(next, hintKind)
                onOverlayModeChanged(next)
            }
        }
        rows += QuickSetting(
            QuickSetting.Id.OPEN_APP,
            context.getString(R.string.floating_menu_panel_open_app, context.getString(R.string.app_name)),
            icon = R.drawable.ic_open_in_new,
        ) {
            leave()
            sendMainActivityIntent(MainActivity.ACTION_OPEN_SETTINGS)
        }
        // The icon's way into its other form ([detachControls]).
        if (offersDetach) {
            rows += QuickSetting(
                QuickSetting.Id.DETACH,
                context.getString(R.string.floating_menu_panel_detach),
                icon = R.drawable.ic_window_detach,
            ) {
                leave()
                detachControls(display.displayId)
            }
        }
        return rows
    }

    /** Open the "Choose OCR tool" OverlayAlert for [id] on [display], stacked
     *  over the still-open quick settings (live capture is paused under them:
     *  the floating menu's holdActive, or [windowBusy] for a session in the
     *  detached window, whose menu lists the same rows; [leave] closes that
     *  host). There's no live result
     *  to re-OCR here, so a different pick
     *  dismisses the menu (clearing holdActive) and refreshes live mode, as a
     *  tap outside the menu does: the pick is persisted by OcrPicker (an
     *  engine's token, or PlayTranslate Cloud's OCR switch, whose own gesture
     *  tail refreshes too), and a running tier must look again with it now
     *  rather than when its change gate next sees the pixels move (the
     *  furigana tier reads only on pixel change; Codex, 2026-10-16). The
     *  highlighted entry is the SELECTION, since there is no result here: the
     *  cloud while its OCR switch is on (whether the next capture would reach
     *  it is the strip's question), else the selected engine. Cancel or
     *  re-picking the highlighted entry leaves the menu open underneath. */
    private fun showOcrPicker(display: Display, id: SourceLangId, leave: () -> Unit) {
        val displayCtx = context.createDisplayContext(display)
        val wm = OwnWindows.manager(displayCtx) ?: return
        val themed = overlayThemedContext(displayCtx)
        val applied =
            if (PtCloudService.config.ocrEnabled) PtCloudService.ID
            else OcrModelManager.selectedBackend(context, id)?.selectionToken ?: ""
        val reread: () -> Unit = {
            leave()
            CaptureService.instance?.refreshLiveOverlay()
        }
        OcrPicker.populate(
            OverlayAlert.Builder(themed, overlayHost, wm, display.displayId),
            themed,
            id,
            applied,
            onReOcr = reread,
            onDownload = { backend ->
                leave()
                launchOnOverlayDisplay(
                    CaptureOverlaySettingsActivity.downloadIntent(
                        context.applicationContext, id, backend.selectionToken
                    ),
                    display.displayId,
                )
            },
            cloud = OcrPicker.CloudChoice(
                onReOcr = reread,
                onOpenPage = {
                    leave()
                    launchOnOverlayDisplay(
                        Intent(context.applicationContext, PtCloudSettingsActivity::class.java),
                        display.displayId,
                    )
                },
            ),
        ).showAsOverlay()
    }

    /** Launch [intent] as a NEW_TASK activity on the foreground display (else
     *  [fallbackDisplayId]) — mirrors the result overlay's language/OCR deep-links. */
    private fun launchOnOverlayDisplay(intent: Intent, fallbackDisplayId: Int) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val target = PlayTranslateApplication.foregroundDisplayId() ?: fallbackDisplayId
        val opts = android.app.ActivityOptions.makeBasic().setLaunchDisplayId(target).toBundle()
        context.applicationContext.startActivity(intent, opts)
    }

    /** Dismiss the floating menu. Most callers leave [clearHoldActive] at
     *  the default — the menu's open state held [CaptureService.holdActive]
     *  true to suppress live captures during the user's interaction, and
     *  closing the menu should clear it. The hold-for-translation entry
     *  points pass `false` because they're about to set holdActive = true
     *  themselves and don't want a momentary false → true cycle that could
     *  wake a live-mode cycle between frames. */
    fun dismissFloatingMenu(clearHoldActive: Boolean = true) {
        val wasShowing = floatingMenu != null
        // A capture started right after (e.g. live mode's first clean frame on
        // "auto translate") must not catch the menu's lingering dim/"Drag
        // finger" hint in the shot. Guaranteed structurally: the removal's
        // WindowChurnGate ghost arms OverlayState.uncompositedGhost until its
        // alpha-0 composites, so that capture waits instead of fast-pathing.
        // (immediate is vestigial — kept for the paper trail.)
        floatingMenu?.let { overlayHost.removeOverlayWindow(it, immediate = true) }
        floatingMenu = null
        floatingMenuDisplayId = null
        floatingMenuCloudJob?.cancel()
        floatingMenuCloudJob = null
        if (wasShowing && clearHoldActive) {
            CaptureService.instance?.holdActive = false
        }
    }

    /** Request the MediaProjection consent game-audio recording rides on —
     *  the floating menu's "Record audio" tap. Plain ensureConsent is the
     *  whole job: the grant push-point
     *  ([com.playtranslate.capture.MediaProjectionController.onConsentResult])
     *  reconciles the recorder, and on the accessibility backend it never
     *  writes MP lifecycle state. Falls back to the tile's ACTION_MP_ACTIVATE
     *  service route when CaptureService isn't running (accessibility
     *  backend, service not yet started) — activateMediaProjection degrades
     *  to exactly ensureConsent + reconcile there, and the intent brings the
     *  service up first. */
    private fun showHideConfirmAlert(display: Display) {
        val displayCtx = context.createDisplayContext(display)
        val overlayWm = OwnWindows.manager(displayCtx) ?: return
        val themed = overlayThemedContext(displayCtx)
        val accentColor = themed.themeColor(R.attr.ptAccent)
        val dividerColor = themed.themeColor(R.attr.ptDivider)
        val dangerColor = themed.themeColor(R.attr.ptDanger)
        val appName = context.getString(R.string.app_name)

        val builder = OverlayAlert.Builder(
            displayCtx, overlayHost, overlayWm, display.displayId,
        ).showIcon()

        // MediaProjection mode, or single-screen: the floating icon's
        // "Turn Off" turns PlayTranslate off (turned back on from the app), so
        // the confirm matches the Settings Turn On / Turn Off button. Only
        // accessibility + dual-screen keeps the older hide-for-now wording
        // (no per-display lifecycle to toggle, so the user only has "hide
        // until next launch" vs "disable accessibility entirely"). The
        // "Minimize Icon" option that previously shrank the floating icon is
        // gone — the icon is always minimised now.
        val exitFlow = CaptureLifecycle.hasActivateControl(context)

        if (exitFlow) {
            builder.setTitle(context.getString(R.string.overlay_turn_off_title, appName))
                .setMessage(context.getString(R.string.overlay_turn_off_message, appName))
                .addButton(context.getString(R.string.capture_lifecycle_stop), dividerColor, dangerColor) {
                    CaptureLifecycle.deactivate(context)
                }
                .addCancelButton()
        } else {
            builder.setTitle(context.getString(R.string.overlay_hide_controls_title, appName))
                .setMessage(context.getString(R.string.overlay_hide_controls_message, appName))
                .addButton(context.getString(R.string.overlay_hide_for_now), accentColor) {
                    hideFloatingIconUntilAppOpen("confirm_hide_for_now")
                }
                .addButton(context.getString(R.string.capture_lifecycle_stop), dividerColor, dangerColor) {
                    PlayTranslateAccessibilityService.disable(context, "confirm_turn_off_multi")
                }
                .addCancelButton()
        }

        builder.showAsOverlay()
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    /**
     * Start/stop live mode directly without bringing MainActivity to the
     * foreground. Used on single-screen devices.
     */
    private fun toggleLiveDirect(start: Boolean) {
        val svc = CaptureService.instance ?: return
        if (start) {
            val hadPopup = isAnyDragLookupPopupShowing
            dismissAllDragLookupPopups()
            if (!svc.isConfigured) {
                val prefs = Prefs(context)
                svc.configureSaved(displayIds = prefs.captureDisplayIds)
            }
            if (hadPopup) {
                handler.postDelayed({ svc.startLive() }, 100)
            } else {
                svc.startLive()
            }
        } else {
            svc.stopLive()
        }
    }

    /** True when live/region actions should run directly on this device
     *  rather than route through MainActivity — single-screen, or the app is
     *  not foregrounded (so there is no in-app surface to hand off to). */
    private fun effectivelySingleScreen(): Boolean = Prefs.effectivelySingleScreen(context)

    /** Start live mode — directly when [effectivelySingleScreen], else routed
     *  through MainActivity. */
    private fun startLiveRouted() {
        if (effectivelySingleScreen()) toggleLiveDirect(true)
        else sendMainActivityIntent(MainActivity.ACTION_START_LIVE)
    }

    /** Stop live mode — directly when [effectivelySingleScreen], else routed
     *  through MainActivity. */
    private fun stopLiveRouted() {
        if (effectivelySingleScreen()) toggleLiveDirect(false)
        else sendMainActivityIntent(MainActivity.ACTION_STOP_LIVE)
    }

    /**
     * Toggle the auto/live session for [mode] from a tap hotkey. Each tap
     * hotkey owns its overlay mode, so the semantics are per-mode (matching
     * the user's "Tap to start/stop Auto …" rows):
     *  - live already running in [mode] → stop (turn that auto mode off);
     *  - live running in the *other* mode → switch to [mode] in place,
     *    keeping the session running;
     *  - not live → start the session in [mode].
     * A capture's sheet up over a stopped session is no exception: the tap
     * starts live, which ends the capture and settles its record
     * ([CaptureTakeover.LIVE_START]).
     *
     * Start/stop reuse [toggleLiveFromOverlay]'s exact single- vs dual-screen /
     * InAppOnly routing ([startLiveFromOverlay], [stopLiveRouted]); the
     * in-place switch is [switchLiveOverlayMode].
     */
    fun toggleAutoMode(mode: OverlayMode) {
        val prefs = Prefs(context)
        val svc = CaptureService.instance
        // Auto-translate becomes the most-recently-used primary, mirroring the
        // floating menu's Auto button.
        captureIsPreferredPrimary = false
        if (svc?.isLive == true) {
            if (prefs.overlayMode == mode) {
                stopLiveRouted()
            } else {
                switchLiveOverlayMode(svc, mode, "hotkey_mode_switch")
            }
        } else {
            // Start in this hotkey's mode. The pref write is committed before
            // start/route reads it (same-process, synchronous read-after-write).
            prefs.overlayMode = mode
            startLiveFromOverlay()
        }
    }

    /**
     * Start auto-translate if it's off, stop it if it runs: the floating
     * menu's Auto button, and the icon's toggle gesture
     * ([HoldAction.TOGGLE_AUTO_TRANSLATE], [TapAction.TOGGLE_AUTO_TRANSLATE]),
     * which is that button without the menu. A start is in the overlay mode
     * already selected ([startLiveFromOverlay]). Returns true when it
     * stopped auto-translate or asked MainActivity to.
     */
    private fun toggleLiveFromOverlay(): Boolean {
        // Auto-translate becomes the most-recently-used primary.
        captureIsPreferredPrimary = false
        return if (CaptureService.instance?.isLive == true) {
            stopLiveRouted()
            true
        } else {
            startLiveFromOverlay()
            false
        }
    }

    /**
     * The icon's swap gesture ([HoldAction.SWAP_OVERLAY_MODE],
     * [TapAction.SWAP_OVERLAY_MODE]): with auto-translate running, switch it
     * in place to the other overlay mode (Translation ⇄ Furigana / Pinyin),
     * as a tap hotkey for that mode does; with it off, start it in the mode
     * already selected. Bound only on a source language with a reading hint (elsewhere the
     * gesture acts as its default, [IconGestureBindings.resolve]), so there
     * is another mode to switch to.
     */
    private fun swapOverlayModeOrStartLive() {
        // Auto-translate becomes the most-recently-used primary, mirroring the
        // floating menu's Auto button.
        captureIsPreferredPrimary = false
        val svc = CaptureService.instance
        if (svc?.isLive == true) {
            switchLiveOverlayMode(svc, nextOverlayMode(Prefs(context)), "icon_swap")
        } else {
            startLiveFromOverlay()
        }
    }

    /**
     * The icon's "Change game language" gesture
     * ([HoldAction.CHANGE_GAME_LANGUAGE], [TapAction.CHANGE_GAME_LANGUAGE]),
     * and the hotkey of that name ([changeGameLanguageForDisplay]).
     * With exactly two languages downloaded, the current one among them,
     * switch to the other and say so in the transient pill on [display];
     * otherwise (one, three or more, or two without the current one) open
     * the picker, as the quick menu's Language row does (2026-09-28).
     * The downloaded languages are the picker's Suggested rows
     * ([OcrModelManager.fullyInstalledSources]), so a Chinese download
     * counts as two and switches between Simplified and Traditional.
     *
     * The picker opens too when the other language's pack needs a forced
     * upgrade (a Japanese pack from before Sudachi, which the user may put
     * off while it isn't the game language): it is on disk, so it counts,
     * but it can't run, and picking it in the picker re-downloads it (Codex
     * adversarial, 2026-09-28). A pack that is on disk but won't open is
     * not checked here: installs are hash-verified and swapped in whole,
     * and the load after the switch removes such a pack, as the launch
     * does for the game language's own.
     *
     * The switch is the pref write: what a change of the game language
     * does to the rest of the app, a running auto-translate restarted in it
     * and a capture up on the game screen ended included, is
     * CaptureService's, as for every change made anywhere
     * ([CaptureService.onSourceLanguageChanged]). Returns true when the
     * picker opened in the floating workspace, which a hold's slide into a
     * drag closes.
     */
    private fun changeGameLanguage(display: Display): Boolean {
        val prefs = Prefs(context)
        val current = prefs.sourceLangId
        val downloaded = OcrModelManager.fullyInstalledSources(context)
        fun picker(): Boolean = openGameLanguagePicker(display.displayId)
        if (downloaded.size != 2 || current !in downloaded) return picker()
        val other = downloaded.first { it != current }
        if (LanguagePackStore.isForcedUpgrade(context, other)) return picker()
        prefs.sourceLang = other.code
        showNoTextPill(
            display,
            context.getString(R.string.icon_action_translating_from, other.displayName()),
        )
        return false
    }

    /**
     * The "Change game language" hotkey: the icon's gesture
     * ([changeGameLanguage]) on [displayId], the primary game display. Called
     * on the main thread via the active overlay UI, as
     * [toggleCaptureScreenForDisplay] is. A hotkey has no hold to slide into a
     * drag, so whether its picker opened in the workspace doesn't matter.
     *
     * Unlike the gesture, a hotkey can fire with the quick menu open (the
     * menu covers the icon), so the menu closes first, as it does for its own
     * Language row: left open, it would sit under the picker showing the old
     * language and keep live capture paused ([CaptureService.holdActive]).
     */
    fun changeGameLanguageForDisplay(displayId: Int) {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = dm.getDisplay(displayId) ?: return
        dismissFloatingMenu()
        changeGameLanguage(display)
    }

    /**
     * Open the game-language picker for [displayId]: in the floating
     * workspace over the game where available, else the Activity
     * (dual-screen with the app in front, where full pages are the right
     * presentation on the app's display), as the capture panel's language
     * header does. The quick menu's Language row and "Change game language"
     * (the icon's gesture and the hotkey) open it. A capture showing over
     * the game ends first ([CaptureTakeover.LANGUAGE_PICKER]). Returns true
     * when it opened in the workspace.
     */
    private fun openGameLanguagePicker(displayId: Int): Boolean {
        endCaptures(CaptureTakeover.LANGUAGE_PICKER)
        if (openWorkspace(displayId) { SourceListPage() }) return true
        LanguageSetupActivity.selectionDelegate = null
        launchOnOverlayDisplay(
            Intent(context.applicationContext, LanguageSetupActivity::class.java)
                .putExtra(LanguageSetupActivity.EXTRA_MODE, LanguageSetupActivity.MODE_SOURCE),
            displayId,
        )
        return false
    }

    /**
     * Switch the running auto-translate session to overlay [mode] in place,
     * for the overlay controls that change the mode mid-session (the floating
     * menu's Overlays row, the tap hotkeys, the icon's swap), so the saved
     * mode stays the one running; in the app, the long-press menu restarts
     * the session, and the Capture and overlay page stops it. (A change of
     * the game language, made anywhere, restarts it too:
     * [CaptureService.onSourceLanguageChanged].) The session goes on here:
     * no stop and start, so its History live card and LLM context carry
     * over, which a restart would replace. The pref write is the switch;
     * [CaptureService.reconcileLiveModes] then rebuilds each per-display mode
     * instance through [CaptureService.setLiveDisplays]'s flavor-mismatch
     * detector. [reason] tags the reconcile's log line.
     */
    private fun switchLiveOverlayMode(svc: CaptureService, mode: OverlayMode, reason: String) {
        Prefs(context).overlayMode = mode
        if (svc.isInAppOnly) {
            // In-App Only has no game-screen overlay to swap, and its
            // result panel renders the same regardless of overlay mode,
            // so reconcileLiveModes (which rebuilds only on a flavor
            // change) deliberately no-ops for it. The mode still governs
            // hold one-shots and any later game-overlay surface, so the
            // pref write above is the real switch; refresh the running
            // poll cycle so it isn't left sitting on a stale result.
            svc.refreshLiveOverlay()
        } else {
            // Game-overlay surface: rebuild the per-display mode instance
            // for the new flavor (Furigana ⇄ Translation).
            svc.reconcileLiveModes(reason)
        }
    }

    /** Start auto-translate from an overlay control (the floating menu's
     *  Auto button, the detached window's, a tap hotkey, the icon's toggle
     *  and swap) in the overlay mode already selected. A session whose
     *  results show in the app's page ([LiveSurface.APP_PAGE]) is
     *  MainActivity's to start ([MainActivity.ACTION_START_LIVE]), which
     *  brings the app forward; any other goes through [startLiveRouted],
     *  the detached window's among them: with the app behind on a second
     *  screen and a window up, the window's page is where it shows, and the
     *  app stays behind. With no service yet nothing starts either way (the
     *  press is lost and the next finds it), so the old pref-only rule
     *  answers there. */
    private fun startLiveFromOverlay() {
        val surface = CaptureService.instance?.liveSurface()
        val inAppPage =
            if (surface != null) surface == LiveSurface.APP_PAGE
            else Prefs.shouldUseInAppOnlyMode(context)
        if (inAppPage) {
            sendMainActivityIntent(MainActivity.ACTION_START_LIVE)
        } else {
            startLiveRouted()
        }
    }

    /** A pause record for an over-game lookup surface ([LookupPause]): it
     *  hides the region indicator and pauses or restarts auto-translate
     *  through this controller's routing (MainActivity's when it is in front
     *  on another screen). */
    // ── What the capture over the game took from it ──────────────────────

    /** The pause record of one screen's capture ([SheetCapture.pause]): a
     *  capture stops a running auto-translate (and hides the region
     *  indicator) while it holds the game screen, and the user's own close of
     *  its sheet gives both back. A new capture takes the standing one over
     *  ([LookupPause.handTo]), a page the sheet opens cancels the restart,
     *  whatever else takes the game screen pays what its row of
     *  [CaptureTakeover] says ([endCaptures]), and capture's end drops it.
     *  Two screens have no sheet and no record: the app's results page is
     *  the panel there, and its button restarts live. */
    private fun newCapturePause() = LookupPause(
        hideIndicator = { regionController.hideIndicatorForDrag() },
        restoreIndicator = { regionController.restoreIndicatorAfterDrag() },
        // A start still pending (its consent dialog, its stream probe) is
        // the user's running session too: the capture stops it and owes it.
        liveRunning = { CaptureService.instance?.let { it.isLive || it.isLiveStartPending } == true },
        stopLive = { stopLiveRouted() },
        startLive = { startLiveRouted() },
    )

    /** [capture]'s pause record pays [debt]: the indicator comes back and
     *  auto-translate restarts as the debt says, it is forgotten, or [heir]
     *  takes it over. Detached before it acts, so a restart's own teardown
     *  of the capture finds nothing to pay. From outside this controller a
     *  capture ends only through [endCaptures]. */
    private fun payDebt(capture: SheetCapture, debt: CaptureDebt, heir: LookupPause? = null) {
        val pause = capture.pause ?: return
        capture.pause = null
        when (debt) {
            CaptureDebt.RESTART -> pause.settle(resumeLive = true)
            CaptureDebt.NO_RESTART -> pause.settle(resumeLive = false)
            CaptureDebt.DROP -> pause.drop()
            CaptureDebt.HAND_OVER -> pause.handTo(checkNotNull(heir) { "a hand-over names the record that takes it" })
        }
    }

    /** [capture] is over: it stops being the record (so a setup still
     *  running stands down), the setup is cancelled, and its sheet goes as
     *  the system's ending. A sheet waiting on a page's Back is dropped,
     *  with a capture or without one (any teardown invalidates a re-show;
     *  the stash re-sets it after). What it took is the caller's to pay
     *  ([payDebt]). Idempotent. */
    private fun endSheetCapture(capture: SheetCapture?) {
        pendingReshow = null
        capture ?: return
        if (sheetCapture === capture) sheetCapture = null
        capture.setup?.cancel()
        capture.setup = null
        capture.sheet?.dismissBySystem()
    }

    /** The sheet [overlay] closed for [reason]. The user's own close hands
     *  the game screen back; a page cancels the restart; so does a session
     *  cancelled from outside the host's paths (a region change or a capture
     *  of the app's, with the app in front), which nothing that follows owes
     *  anything for. The system's endings come from a funnel that already
     *  ended the capture and paid what it took ([endCaptures], a new capture,
     *  a teardown), so they find it gone. */
    private fun onCaptureSheetDismissed(
        overlay: com.playtranslate.ui.CaptureResultOverlay,
        reason: com.playtranslate.ui.CaptureResultOverlay.DismissReason,
    ) {
        val capture = sheetCapture?.takeIf { it.sheet === overlay } ?: return
        sheetCapture = null
        when (reason) {
            com.playtranslate.ui.CaptureResultOverlay.DismissReason.USER -> payDebt(capture, CaptureDebt.RESTART)
            com.playtranslate.ui.CaptureResultOverlay.DismissReason.PAGE,
            com.playtranslate.ui.CaptureResultOverlay.DismissReason.CANCELLED -> payDebt(capture, CaptureDebt.NO_RESTART)
            com.playtranslate.ui.CaptureResultOverlay.DismissReason.SYSTEM -> Unit
        }
    }

    private fun newLookupPause() = LookupPause(
        hideIndicator = { regionController.hideIndicatorForDrag() },
        restoreIndicator = { regionController.restoreIndicatorAfterDrag() },
        liveRunning = { CaptureService.instance?.isLive == true },
        stopLive = { stopLiveRouted() },
        startLive = { startLiveRouted() },
    )

    /**
     * "Enable tap-to-lookup", the icon's Tap and Hold action and the hotkey
     * of that name: a capture of [displayId] in Definitions, by a per-capture
     * override that remembers nothing (a pick on the pill is what remembers).
     * A press with nothing up on [displayId] captures it; with a presentation
     * up there in Definitions it is the user's close, as the capture hotkey's
     * second press is; with one up there in another mode it switches that
     * presentation to Definitions in place. A presentation on another display
     * is not the one pressed over: the press captures [displayId], which
     * replaces it as any capture does, as it replaces one still on its way.
     * Returns true when it stopped auto-translate, as a capture's start
     * reports it ([handleRegionSelection]); a hold's drag reads it.
     */
    private fun captureInDefinitions(displayId: Int): Boolean {
        sheetCapture?.takeIf { it.displayId == displayId }?.sheet?.let { sheet ->
            if (sheet.overlayMode == OverlayMode.DEFINITIONS) animateOutCaptureResultOverlay()
            else sheet.applyOverlayModeOverride(OverlayMode.DEFINITIONS)
            return false
        }
        captureWindow?.takeIf { captureWindowDisplayId == displayId }?.let { window ->
            if (window.mode == OverlayMode.DEFINITIONS) dismissCapturePresentation()
            else switchCaptureWindowMode(OverlayMode.DEFINITIONS)
            return false
        }
        return captureCurrentRegionForDisplay(displayId, modeOverride = OverlayMode.DEFINITIONS)
    }

    /** The "Enable tap-to-lookup" hotkey ([captureInDefinitions]). It has no
     *  display of its own: a presentation up anywhere is the one it toggles,
     *  as the capture hotkey's second press closes the sheet wherever it is;
     *  with none up it captures [displayId], the primary game display.
     *  Called on the main thread via the active overlay UI. */
    fun toggleTapToLookupForDisplay(displayId: Int) {
        val shownSheet = sheetCapture?.takeIf { it.sheet != null }
        val target = when {
            shownSheet != null -> shownSheet.displayId
            captureWindow != null -> captureWindowDisplayId
            else -> displayId
        }
        captureInDefinitions(target)
    }

    /**
     * "Capture screen" hotkey action — a toggle. If a capture-result overlay
     * from a prior press is still on screen, dismiss it and stop; the *next*
     * press then runs a fresh capture. Otherwise run the one-shot immediately.
     * Called on the main thread via the active overlay UI, matching
     * [toggleAutoMode]'s routing contract.
     *
     * The predicate is exactly a capture's sheet being up, because that panel
     * window is what a one-shot shows on a single-screen device (its on-game
     * boxes are its own bottom-most child, torn down with it) — precisely the
     * "translation overlay" a capture leaves up; a press while one is still
     * on its way starts a capture that replaces it. A running auto session's
     * live overlay is a
     * different surface with its own tap hotkey, so it is deliberately not
     * treated as a capture result here; a capture press during live stops live
     * and captures, same as the floating menu's Capture button.
     */
    fun toggleCaptureScreenForDisplay(displayId: Int) {
        if (sheetCapture?.sheet != null) {
            animateOutCaptureResultOverlay()
            return
        }
        captureCurrentRegionForDisplay(displayId)
    }

    /**
     * Animate the capture-result panel out (slide-up), matching a tap-outside /
     * swipe dismissal, rather than the instant teardown of [endSheetCapture]
     * (which the menu-open / supersede / teardown paths use because they need
     * the window gone synchronously). The overlay's own onDismiss ends the
     * capture at the end of the animation (exactly as a gesture dismiss
     * does); a re-press mid-animation is a no-op (the overlay guards its
     * in-flight exit).
     */
    private fun animateOutCaptureResultOverlay() {
        sheetCapture?.sheet?.animateOutAndDismiss()
    }

    /**
     * Run a one-shot capture of [displayId]'s current region (full screen unless
     * one is set) — the action shared by the floating menu's Capture button,
     * the capture-screen hotkey and the icon's capture gesture
     * ([HoldAction.CAPTURE_SCREEN], [TapAction.CAPTURE_SCREEN]).
     * [handleRegionSelection] routes single- vs dual-screen and configures the
     * override itself; a running auto session is stopped first so this is a
     * clean one-shot, not a live refresh. Marks capture the most-recently-used
     * primary (styling for the next menu open). Returns true when it stopped
     * auto-translate, or asked MainActivity to, as [toggleLiveFromOverlay]
     * does: a hold's drag reads it.
     */
    private fun captureCurrentRegionForDisplay(displayId: Int, modeOverride: OverlayMode? = null): Boolean {
        captureIsPreferredPrimary = true
        val region = CaptureService.instance?.activeRegionForDisplay(displayId)
            ?: CaptureService.DEFAULT_REGION
        return handleRegionSelection(displayId, region, modeOverride)
    }

    private fun sendMainActivityIntent(action: String, targetDisplayId: Int? = null) {
        val intent = Intent(context, MainActivity::class.java).apply {
            this.action = action
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (targetDisplayId != null) {
                putExtra(MainActivity.EXTRA_TARGET_DISPLAY_ID, targetDisplayId)
            }
        }
        context.startActivity(intent)
    }

    /**
     * A capture for the game display [displayId] starts on two screens, in
     * [modeOverride] or the remembered mode: the app will read it and its
     * result put the window up by itself, so from now on it is the capture
     * over the game ([appCapture]), and a row of [CaptureTakeover] that ends
     * captures on their way ends it too. It takes the game screen first as
     * any capture does: a sheet left from a capture taken while the app was
     * behind goes, an earlier capture of the app's on its way is replaced
     * ([CaptureTakeover.APP_CAPTURE]), and a drag lens left open closes, its
     * record settling without the restart it would owe, since a capture
     * leaves auto-translate stopped here (the app's button restarts it).
     * The icon's and hotkeys' captures start here before they are sent
     * ([handleRegionSelection], the id riding the intent); the app's own
     * captures (its Translate button, a re-read, a region capture that came
     * without an id) start here as it starts them. Returns the capture's id,
     * by which the page puts its result up ([presentCapture]).
     */
    fun beginAppCapture(displayId: Int, modeOverride: OverlayMode?): Long {
        endCaptures(CaptureTakeover.APP_CAPTURE)
        iconHandles.values.forEach { it.dragPause.settle(resumeLive = false) }
        dismissAllDragLookupPopups()
        val capture = AppCapture(++lastAppCaptureId, displayId, modeOverride)
        appCapture = capture
        return capture.id
    }

    /**
     * Routes a region to the surface that shows its capture, stopping a
     * running auto-translate first so this is a clean one-shot, not a live
     * refresh. Effectively single screen (or app backgrounded): the over-game
     * sheet, whose pause record owes the restart ([SheetCapture.pause]: the
     * user's own close of the sheet gives the game screen back). Otherwise:
     * ACTION_REGION_CAPTURE to the app, which owns the result and whose
     * button restarts live, the capture starting here before it is sent
     * ([beginAppCapture]). Either way the quick menu closes (a hotkey can
     * capture with it open; the menu's own buttons close it first), as
     * tap-to-lookup's own start closed it, and so does a drag lens left
     * open, as that start closed it before tap-to-lookup became a capture.
     * Returns true when auto-translate was running (or starting) and was
     * stopped, or asked to stop: a hold's drag reads it.
     */
    private fun handleRegionSelection(
        displayId: Int,
        region: RegionEntry,
        modeOverride: OverlayMode? = null,
    ): Boolean {
        val wasLive = CaptureService.instance?.let { it.isLive || it.isLiveStartPending } == true
        // Left up, the menu would sit over the capture, still holding live
        // capture paused.
        dismissFloatingMenu()
        // A display whose controls are the detached window showing its page
        // reads the capture there. Not a capture that asked for a mode of
        // its own (tap-to-lookup's Definitions): the window draws nothing
        // over the game, so that one keeps the sheet. Narrowed to its
        // capture button the window is a capture button like the icon's,
        // and its captures take the sheet too. An auto-translate running
        // in the page (or still starting) stops first, as a capture stops
        // one on two screens, where the app's page is the results page:
        // the page shows this capture from here, and the bar's Auto starts
        // a session again. Nothing restarts it by itself.
        if (modeOverride == null) {
            val page = detachedPages[displayId]
            if (page != null && windowHandles[displayId]?.showsPage == true) {
                if (wasLive) stopLiveRouted()
                captureIntoWindow(displayId, region, page)
                return wasLive
            }
        }
        if (effectivelySingleScreen()) {
            // The capture takes over what the surfaces it replaces paused
            // (the capture before it, the icons' drag lenses), so nothing
            // restarts in between; then it stops auto-translate and hides
            // the region indicator for itself.
            val pause = newCapturePause()
            sheetCapture?.pause?.handTo(pause)
            iconHandles.values.forEach { it.dragPause.handTo(pause) }
            pause.begin(pauseLive = true)
            // A drag lens left open would sit under the sheet and come back
            // over the game, with its own older scene, when the sheet goes.
            // Its record is the capture's now, so closing it restarts nothing.
            dismissAllDragLookupPopups()
            showCaptureResultOverlay(displayId, region, modeOverride, pause)
            return wasLive
        }
        val id = beginAppCapture(displayId, modeOverride)
        // A capture takes the game screen: auto-translate stays stopped,
        // as a capture leaves it; the app's button restarts it.
        if (wasLive) stopLiveRouted()
        val intent = Intent(context, MainActivity::class.java).apply {
            action = MainActivity.ACTION_REGION_CAPTURE
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(MainActivity.EXTRA_TOP_FRAC, region.top)
            putExtra(MainActivity.EXTRA_BOTTOM_FRAC, region.bottom)
            putExtra(MainActivity.EXTRA_LEFT_FRAC, region.left)
            putExtra(MainActivity.EXTRA_RIGHT_FRAC, region.right)
            putExtra(MainActivity.EXTRA_TARGET_DISPLAY_ID, displayId)
            putExtra(MainActivity.EXTRA_REGION_CAPTURE_ID, id)
            if (modeOverride != null) putExtra(MainActivity.EXTRA_OVERLAY_MODE_OVERRIDE, modeOverride.name)
        }
        context.startActivity(intent)
        return wasLive
    }

    /**
     * Resets [displayId] to the full-screen default region: rewrites the
     * persisted selection, drops any runtime override, and refreshes the
     * in-app region label. Shared by the floating menu's clear-region action
     * and the region editor's trash button.
     */
    private fun clearRegionForDisplay(displayId: Int) {
        val prefs = Prefs(context)
        prefs.setSelectedRegionIdForDisplay(displayId, Prefs.DEFAULT_REGION_LIST[0].id)
        val svc = CaptureService.instance
        if (svc != null && svc.isConfigured) {
            svc.clearOverride(displayId)
        }
        if (MainActivity.isInForeground) {
            sendMainActivityIntent(MainActivity.ACTION_REFRESH_REGION_LABEL)
        }
    }

    /**
     * Single-screen capture: show the over-game result panel instead of leaving
     * the game for [TranslationResultActivity]. Ends the capture before it
     * first (single-instance + screenshot-blanking guard — a second capture
     * must remove the old panel before the next requestClean blanks it into
     * the shot; what it took was handed to [pause] already), and is the
     * capture over the game from here ([sheetCapture]), on its way until the
     * clean capture returns: the new panel shows only AFTER it (so it's never
     * in the shot), then the one-shot pipeline feeds into it. Falls back to
     * the Activity if the capture-service handle isn't available.
     */
    private fun showCaptureResultOverlay(
        displayId: Int,
        region: RegionEntry,
        modeOverride: OverlayMode?,
        pause: LookupPause,
    ) {
        endSheetCapture(sheetCapture)
        // As it replaces any capture before it: one the app was reading
        // (the app went behind since) never comes up over this one, and one
        // on its way into a detached window never lands.
        appCapture = null
        cancelWindowCapture()
        val capture = SheetCapture(displayId, modeOverride, pause)
        sheetCapture = capture
        val svc = CaptureService.instance ?: run {
            launchResultActivity(capture, region)
            return
        }
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = dm.getDisplay(displayId) ?: run {
            launchResultActivity(capture, region)
            return
        }
        val displayCtx = context.createDisplayContext(display)
        val wm = OwnWindows.manager(displayCtx) ?: run {
            launchResultActivity(capture, region)
            return
        }
        val size = getDisplaySize(display)
        // The geometry this shot is taken against, kept LOCAL to the coroutine: the capture
        // validates itself against it after requestClean (below), so it never depends on an
        // onDisplayChanged event — or its timing — to cancel a stale capture. The record's
        // geometry is set only once a panel is actually shown.
        val launchGeometry = DisplayGeometry(size.x, size.y, display.rotation)
        capture.setup = scope.launch {
            // Warm PlayTranslate Cloud for processScreenshot's read below: a
            // fresh capture, so it reads in the source language from the same
            // pref and never device-only.
            FrameReaders.warm(Prefs(context).sourceLangId, deviceOnly = false)
            // The frame carries its capture-time facts (CapturedFrame).
            val frame = CaptureBackendResolver.active().captureSource?.requestClean(displayId)
            val bitmap = frame?.bitmap
            // Single validation gate at the ONLY pre-panel suspension point. Bail if EITHER:
            //  • it is no longer the capture over the game (a newer capture or a takeover
            //    ended it, and paid what it took), or
            //  • the captured display reconfigured under us (live geometry != the launch
            //    snapshot) — a rotation/resize mid-capture would size the panel + OCR mapping
            //    to stale geometry; a gone display reads null (!= snapshot) → also bails.
            // Self-contained, so correctness holds regardless of when — or whether —
            // onDisplayChanged fires. That decoupling is what removes the rotate-then-tap race.
            val curGeometry = context.displayGeometryOf(displayId)
            if (sheetCapture !== capture || curGeometry != launchGeometry) {
                bitmap?.recycle()
                // Reconfigured under us with no sheet to show: the game screen
                // goes back now.
                if (sheetCapture === capture) {
                    sheetCapture = null
                    payDebt(capture, CaptureDebt.RESTART)
                }
                return@launch
            }
            // Committed: record what the SHOWN panel is built for, so a later reconfiguration
            // of its display dismisses it (dismissCaptureResultPanelIfReconfigured). Register
            // the panel only AFTER the clean capture, so its window isn't blanked into the shot.
            capture.geometry = launchGeometry
            val overlay = com.playtranslate.ui.CaptureResultOverlay(displayCtx, wm, displayId, overlayHost)
            overlay.onDismiss = { reason -> onCaptureSheetDismissed(overlay, reason) }
            overlay.onNavigateToDetail = { result, answer ->
                stashCaptureOverlayForReshow(displayId, result, region, answer)
            }
            // Over-game sheet: B/dpad/stick drive it while a controller is attached.
            overlay.controllerNavEnabled = true
            overlay.captureRegion = region
            overlay.overlayModeOverride = modeOverride
            capture.sheet = overlay
            // Pass the clean shot for the frosted backdrop — show() downscales it
            // synchronously here, before processScreenshot (below) recycles it.
            overlay.show(size.x, size.y, bitmap)
            // Same configure → process sequence as
            // TranslationResultActivity.onServiceReady, off this controller's scope.
            svc.configureSaved(
                displayIds = Prefs(context).captureDisplayIds,
                primaryDisplayId = displayId,
            )
            svc.configureOverride(displayId, region)
            // Do NOT recycle the frame — processScreenshot consumes it async on
            // the service scope and recycles it itself.
            // What the translation may wait for is the sheet's to say: its
            // mode, the capture's own override included (tap-to-lookup's
            // Definitions, which also opens it parked), and its posture.
            val deferral = overlay.captureDeferral()
            val session = if (frame != null)
                svc.processScreenshot(
                    frame, displayId,
                    allowDeferTranslation = deferral.allow, deferWhileParked = deferral.whileParked,
                )
                else svc.captureOnce(
                    displayId, allowDeferTranslation = deferral.allow, deferWhileParked = deferral.whileParked,
                )
            overlay.observe(session)
        }
    }

    /**
     * The one-shot's fallback when no over-game panel can be shown (no
     * CaptureService yet, a gone display, no window manager): the
     * full-screen result page, launched once a clean shot is in hand. It is
     * [capture]'s setup, on its way as the panel's shot is, so a capture
     * ended while the shot is in flight drops it instead of letting the page
     * open later: the icon's capture hold sliding into a drag (the page would
     * open under the lens), a menu opening, or a newer capture superseding it
     * (two pages used to open). Once the page opens, the capture is the
     * page's and over here.
     * The no-service case is a real state, not only the moment before the
     * service's sticky restart: after a restore whose start was refused the
     * icon stays up with no service until the restart or the next app open,
     * and a gesture's own start is asynchronous, so that gesture's one-shot
     * takes this path.
     */
    private fun launchResultActivity(capture: SheetCapture, region: RegionEntry) {
        val displayId = capture.displayId
        // A page, not a sheet: the game screen is the page's now, so the
        // capture's record settles without its restart.
        payDebt(capture, CaptureDebt.NO_RESTART)
        capture.setup = scope.launch {
            val frame = CaptureBackendResolver.active().captureSource?.requestClean(displayId)
            if (sheetCapture !== capture) {
                frame?.recycle()
                return@launch
            }
            sheetCapture = null
            val intent = Intent(context, com.playtranslate.ui.TranslationResultActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(com.playtranslate.ui.TranslationResultActivity.EXTRA_TOP_FRAC, region.top)
                putExtra(com.playtranslate.ui.TranslationResultActivity.EXTRA_BOTTOM_FRAC, region.bottom)
                putExtra(com.playtranslate.ui.TranslationResultActivity.EXTRA_LEFT_FRAC, region.left)
                putExtra(com.playtranslate.ui.TranslationResultActivity.EXTRA_RIGHT_FRAC, region.right)
                putExtra(com.playtranslate.ui.TranslationResultActivity.EXTRA_TARGET_DISPLAY_ID, displayId)
                // A page has no Definitions to open in, but it is told the
                // capture was tap-to-lookup's: such a capture's original is
                // neither read aloud nor copied by itself.
                capture.modeOverride?.let {
                    putExtra(com.playtranslate.ui.TranslationResultActivity.EXTRA_OVERLAY_MODE_OVERRIDE, it.name)
                }
            }
            if (frame != null) {
                val path = savePreCapturedScreenshot(frame.bitmap)
                intent.putExtra(com.playtranslate.ui.TranslationResultActivity.EXTRA_SCREENSHOT_PATH, path)
                // The frame's geometry fact rides the intent next to the
                // path it describes — a single-app frame has no status bar,
                // and the activity must not crop one off.
                intent.putExtra(
                    com.playtranslate.ui.TranslationResultActivity.EXTRA_SCREENSHOT_INCLUDES_SYSTEM_UI,
                    frame.includesSystemUi,
                )
                frame.recycle()
            }
            val opts = android.app.ActivityOptions.makeBasic()
                .setLaunchDisplayId(displayId)
                .toBundle()
            context.startActivity(intent, opts)
        }
    }

    /**
     * [takeover] takes the game screen, and the captures over it end as its
     * row of [CaptureTakeover] says, in the phases the row reaches: one
     * screen's capture ([sheetCapture]) on its way or showing ends (its setup
     * is cancelled, its sheet goes) and then pays what the row says it owes
     * ([CaptureTakeover.debt]; a hand-over goes to [heir], the record of the
     * surface taking over); two screens' capture on its way ([appCapture])
     * ends, so its result stays on the app's page; a two-screen window
     * showing goes where the row ends it. The capture is ended before it
     * pays, so a restart's own takeover finds nothing left. A row for
     * Definitions alone leaves a capture in another mode up. With
     * [displayIds], only a capture on one of those displays ends (a
     * hold-to-preview's, a lens's, a turned or gone display's); null,
     * wherever it is. A sheet waiting on a page's Back is dropped with the
     * capture it belongs to, and by a row that ends every capture everywhere
     * even with none up.
     */
    internal fun endCaptures(
        takeover: CaptureTakeover,
        displayIds: Set<Int>? = null,
        heir: LookupPause? = null,
    ) {
        fun ends(shown: Boolean, displayId: Int, mode: OverlayMode): Boolean =
            (if (shown) takeover.phases.shown else takeover.phases.onItsWay) &&
                (displayIds == null || displayId in displayIds) &&
                (!takeover.definitionsOnly || mode == OverlayMode.DEFINITIONS)
        val sheet = sheetCapture
        if (sheet != null && ends(sheet.sheet != null, sheet.displayId, modeOf(sheet))) {
            endSheetCapture(sheet)
            payDebt(sheet, takeover.debt, heir)
        }
        if (displayIds == null && takeover.phases == CapturePhases.ALL && !takeover.definitionsOnly) {
            pendingReshow = null
        }
        appCapture?.let { app ->
            val prefs = Prefs(context)
            val mode = twoScreenMode(
                app.modeOverride, prefs.overlayModeFor(OverlaySurface.CAPTURE), prefs.overlayModeFor(OverlaySurface.LIVE),
            )
            if (ends(shown = false, app.displayId, mode)) appCapture = null
        }
        val window = captureWindow
        if (window != null && takeover.endsWindow && ends(shown = true, captureWindowDisplayId, window.mode)) {
            dismissCapturePresentation()
        }
        // A capture into a detached window ends only while on its way: once
        // its frame is in it is the page's result, and nothing here ends a
        // page (its own rules do: a change of language, the controls going).
        // It is never in Definitions.
        windowCapture?.let { capture ->
            if (ends(shown = false, capture.displayId, OverlayMode.TRANSLATION)) cancelWindowCapture()
        }
    }

    /** Dismiss a SHOWING capture panel when its own display's geometry (size or rotation)
     *  changed — the panel was laid out + OCR-mapped against that geometry, so a 180° flip
     *  (rotation change, same pixels) invalidates it too. Scoped to the panel's display, so a
     *  late event from a secondary display leaves it alone. Deliberately does NOT cancel an
     *  in-flight capture (no sheet yet, [CaptureTakeover.DISPLAY_TURN] reaches the shown
     *  phase only): that self-validates its geometry after requestClean, so nothing here can
     *  race it. */
    private fun dismissCaptureResultPanelIfReconfigured(changedDisplayId: Int) {
        val capture = sheetCapture ?: return
        if (capture.sheet == null) return
        if (changedDisplayId != capture.displayId) return
        val built = capture.geometry ?: return
        val now = context.displayGeometryOf(changedDisplayId) ?: return
        if (now != built) endCaptures(CaptureTakeover.DISPLAY_TURN, setOf(changedDisplayId))
    }

    /** Stash the overlay's current result and tear the live panel down when its
     *  word lens opens the in-app detail screen, so a user-initiated back from that
     *  screen ([onCaptureDetailBackPressed]) can re-show the panel. */
    fun stashCaptureOverlayForReshow(
        displayId: Int,
        result: com.playtranslate.model.TranslationResult,
        region: RegionEntry,
        askAnswer: com.playtranslate.ask.AskState.Answered? = null,
    ) {
        // A page takes the screen: the restart is forgone, and the sheet
        // that comes back on Back owes nothing.
        val capture = sheetCapture
        endSheetCapture(capture)
        capture?.let { payDebt(it, CaptureDebt.NO_RESTART) }
        // After the capture's end, which drops any earlier one.
        pendingReshow = PendingReshow(
            displayId, result, region, android.os.SystemClock.elapsedRealtime(), askAnswer,
        )
    }

    /** Called by [com.playtranslate.ui.TranslationResultActivity] when the user backs
     *  out of a detail screen opened from the capture overlay. Re-shows the panel only
     *  if a matching, non-stale stash is still live; anything else (a newer capture, a
     *  teardown, too much elapsed time, a mismatched display) leaves it dismissed. */
    fun onCaptureDetailBackPressed(displayId: Int) {
        val pending = pendingReshow ?: return
        pendingReshow = null
        if (pending.displayId != displayId) return
        if (android.os.SystemClock.elapsedRealtime() - pending.stashedAtMs > reshowStalenessMs) return
        reshowCaptureOverlay(displayId, pending.result, pending.region, pending.askAnswer)
    }

    /** Re-create the over-game panel and bind a stashed result (no re-capture). */
    private fun reshowCaptureOverlay(
        displayId: Int,
        result: com.playtranslate.model.TranslationResult,
        region: RegionEntry,
        askAnswer: com.playtranslate.ask.AskState.Answered?,
    ) {
        endSheetCapture(sheetCapture)
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = dm.getDisplay(displayId) ?: return
        val displayCtx = context.createDisplayContext(display)
        val wm = OwnWindows.manager(displayCtx) ?: return
        val size = getDisplaySize(display)
        // Shown at once, and owing nothing: the page that took it settled
        // what the capture took.
        val capture = SheetCapture(displayId, modeOverride = null, pause = null)
        capture.geometry = DisplayGeometry(size.x, size.y, display.rotation)
        val overlay = com.playtranslate.ui.CaptureResultOverlay(displayCtx, wm, displayId, overlayHost)
        overlay.onDismiss = { reason -> onCaptureSheetDismissed(overlay, reason) }
        overlay.onNavigateToDetail = { r, answer ->
            stashCaptureOverlayForReshow(displayId, r, region, answer)
        }
        // Same controller-nav opt-in as the fresh-capture path, or the sheet
        // would come back keyless after a detail round-trip.
        overlay.controllerNavEnabled = true
        overlay.captureRegion = region
        capture.sheet = overlay
        sheetCapture = capture
        // The frosted backdrop is rebuilt from the result's clean capture
        // (the same image the fresh path blurred): decoded here, blurred by
        // show into its small copy, recycled immediately after.
        val backdrop = result.screenshotPath?.let {
            runCatching { android.graphics.BitmapFactory.decodeFile(it) }.getOrNull()
        }
        overlay.showWithResult(size.x, size.y, result, backdrop, askAnswer)
        backdrop?.recycle()
    }

    /**
     * Open the floating workspace ([OverlayWorkspace]) on [displayId] with
     * [page] as its root page. The over-game funnel for flows that otherwise
     * escape to full-screen Activities: returns false — and does NOTHING —
     * when the workspace presentation isn't the right route, so the caller
     * runs its existing Activity launch unchanged.
     *
     * Gated on [effectivelySingleScreen] — the SAME routing rule as the
     * over-game capture sheet, NOT bare [Prefs.isSingleScreen]: a dual-screen
     * handheld (the Thor) reports 2+ capturable displays and is never
     * "single-screen", yet the game still owns the screen whenever
     * MainActivity is backgrounded — exactly when over-game presentation is
     * wanted. Only the true coordinator posture (dual-screen AND the app
     * foregrounded on its own viewport) falls back to the Activity routes.
     *
     * A USER dismissal of the workspace re-shows a stashed capture sheet via
     * [onCaptureDetailBackPressed] (a no-op unless the opening flow stashed
     * one). Programmatic teardown ([dismissWorkspace]) never re-shows: it
     * clears the tracking field first, so the workspace's dismiss callback
     * sees a mismatch and stands down.
     */
    fun openWorkspace(
        displayId: Int,
        /** Launch screenshot for the card's frosted ground; null (a flow with
         *  no capture, e.g. the icon menu's language picker) keeps flat ptBg. */
        screenshotPath: String? = null,
        page: (WorkspaceHost) -> WorkspacePage,
    ): Boolean {
        if (!effectivelySingleScreen()) return false
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return false
        val display = dm.getDisplay(displayId) ?: return false
        val displayCtx = context.createDisplayContext(display)
        val wm = OwnWindows.manager(displayCtx) ?: return false
        dismissWorkspace()
        val size = getDisplaySize(display)
        val ws = OverlayWorkspace(displayCtx, wm, displayId, overlayHost)
        ws.onDismiss = {
            if (workspace === ws) {
                workspace = null
                workspaceDisplayId = -1
                workspaceGeometry = null
                onCaptureDetailBackPressed(displayId)
            }
        }
        workspace = ws
        workspaceDisplayId = displayId
        workspaceGeometry = DisplayGeometry(size.x, size.y, display.rotation)
        ws.show(size.x, size.y, screenshotPath, page)
        return true
    }

    /** Programmatic workspace teardown (hideAll, display reconfiguration).
     *  Clears the tracking field BEFORE dismissing so the workspace's
     *  onDismiss sees a mismatch — no capture-sheet re-show fires from a
     *  teardown the user didn't perform. Idempotent. */
    fun dismissWorkspace() {
        val ws = workspace ?: return
        workspace = null
        workspaceDisplayId = -1
        workspaceGeometry = null
        ws.dismiss()
    }

    /** Dismiss a showing workspace when its own display's geometry (size or
     *  rotation) changed — its window is pinned to explicit pixels and its
     *  pages were laid out against that geometry. Mirrors
     *  [dismissCaptureResultPanelIfReconfigured]. */
    private fun dismissWorkspaceIfReconfigured(changedDisplayId: Int) {
        if (workspace == null) return
        if (changedDisplayId != workspaceDisplayId) return
        val built = workspaceGeometry ?: return
        val now = context.displayGeometryOf(changedDisplayId) ?: return
        if (now != built) {
            dismissWorkspace()
        }
    }

    /** Saves a pre-captured screenshot to the cache for TranslationResultActivity. */
    private fun savePreCapturedScreenshot(bitmap: Bitmap): String? {
        return try {
            val dir = java.io.File(context.cacheDir, "screenshots").apply { mkdirs() }
            val file = java.io.File(dir, "precapture.jpg")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "savePreCapturedScreenshot failed: ${e.message}")
            null
        }
    }

    /** Fallback display for [reconcileFloatingIcons] when the saved selection
     *  resolves to no reachable display. Routes through the backend shim so a
     *  stale selection that isn't capturable (e.g., `{1}` on MediaProjection)
     *  doesn't put the icon on an uncapturable display — the shim collapses
     *  to the backend's fallback first. */
    private fun findIconDisplay(prefs: Prefs): Display? {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val displays = dm.displays
        if (displays.size <= 1) return displays.firstOrNull()
        val backend = CaptureBackendResolver.active()
        val primaryId = backend.capturableTargets(context, prefs.captureDisplayIds).firstOrNull()
            ?: Display.DEFAULT_DISPLAY
        return dm.getDisplay(primaryId) ?: displays.firstOrNull()
    }

    /** Full pixel size of [display]. */
    private fun getDisplaySize(display: Display): Point =
        context.createDisplayContext(display).displaySizePx()

    /** Hide every overlay this controller owns, keeping the controller
     *  reusable (scope intact). Used when the active backend is swapped
     *  and from [com.playtranslate.capture.MediaProjectionController.onProjectionLost]
     *  on screen-record revoke / system-stop.
     *
     *  The structured per-feature teardown runs first so per-overlay
     *  state (iconHandles, region/menu/popup refs) stays coherent and
     *  callbacks fire — then [OverlayHost.removeAll] sweeps every
     *  remaining tracked window as a backstop. Any overlay that was
     *  registered through [overlayHost] but missed by the structured
     *  chain (in-flight popup that hasn't reached its destroy listener,
     *  a sibling lookalike opened on a different code path, etc.) goes
     *  with that sweep. Without the sweep, a fragment can survive a
     *  projection-loss event when the user was mid-interaction with
     *  the magnifier / popup / a menu the structured destroy chain
     *  hadn't reached yet. */
    fun hideAll() {
        // Capture is ending: what the capture over the game took is
        // forgotten, as the icons' drag sessions' is below, and two screens'
        // capture on its way never comes up.
        val capture = sheetCapture
        endSheetCapture(capture)
        capture?.let { payDebt(it, CaptureDebt.DROP) }
        appCapture = null
        dismissWorkspace()
        hideTranslationOverlay()
        dismissCapturePresentation()
        regionController.hideAll()
        dismissFloatingMenu()
        hideFloatingIcon("hideAll", endsCapture = true)
        overlayHost.removeAll()
        Log.i(TAG, "hideAll: structured teardown + overlayHost.removeAll() sweep done")
    }

    /** Full teardown — [hideAll] plus scope/handler cancellation. For host
     *  death (accessibility-service unbind). */
    fun destroy() {
        // Mirrors [attach] — same applicationContext hop so we hit the
        // exact same DisplayManager instance for symmetric register /
        // unregister. Safe to call even if attach() was skipped: the
        // framework no-ops an unregister of a listener it doesn't have.
        (context.applicationContext.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
            ?.unregisterDisplayListener(displayListener)
        hideAll()
        regionController.destroy()
        pillHandler.removeCallbacksAndMessages(null)
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
    }
}
