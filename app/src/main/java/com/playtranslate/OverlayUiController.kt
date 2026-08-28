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
import com.playtranslate.language.HintTextKind
import com.playtranslate.language.SourceLangId
import com.playtranslate.language.SourceLanguageProfiles
import com.playtranslate.language.stackableTargetScript
import com.playtranslate.language.targetSupportsVerticalText
import com.playtranslate.ocr.mangaocr.MangaOcrProvisioning
import com.playtranslate.ocr.registry.OcrModelManager
import com.playtranslate.ocr.registry.ocrLabel
import com.playtranslate.ocr.registry.selectionToken
import com.playtranslate.overlay.OverlayHost
import com.playtranslate.ui.CaptureOverlaySettingsActivity
import com.playtranslate.ui.DimController
import com.playtranslate.ui.DragLookupController
import com.playtranslate.ui.FloatingIconMenu
import com.playtranslate.ui.LanguageSetupActivity
import com.playtranslate.ui.OcrPicker
import com.playtranslate.ui.FloatingOverlayIcon
import com.playtranslate.ui.MagnifierLens
import com.playtranslate.ui.OverlayAlert
import com.playtranslate.ui.OverlayWorkspace
import com.playtranslate.ui.SourceListPage
import com.playtranslate.ui.WorkspaceHost
import com.playtranslate.ui.WorkspacePage
import com.playtranslate.ui.SonarPingIntroView
import com.playtranslate.ui.TextBox
import com.playtranslate.ui.TranslationOverlayView
import com.playtranslate.ui.WordLookupPopup
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
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

    /** The over-game capture-result panel while showing (single-screen). Single
     *  instance — a new capture replaces it. */
    private var captureResultOverlay: com.playtranslate.ui.CaptureResultOverlay? = null

    /** The in-flight capture-setup coroutine (it suspends in requestClean before
     *  the panel exists, so dismissing the panel alone can't stop it), plus a
     *  monotonic generation. Every dismiss bumps the generation and cancels the
     *  job, so a newer capture — or any teardown — supersedes an older capture
     *  still mid-requestClean instead of both racing the shared service. */
    private var captureJob: Job? = null
    private var captureGeneration = 0

    /** Display + geometry (size AND rotation) the SHOWING result panel was built for — set
     *  when a panel is shown, cleared on dismiss. [dismissCaptureResultPanelIfReconfigured]
     *  uses them to drop the panel when ITS display reconfigures (a 180° flip changes
     *  rotation, so it's caught), while ignoring events from other displays. An IN-FLIGHT
     *  capture does NOT use these — it validates its own local geometry snapshot after
     *  requestClean — so onDisplayChanged never has to coordinate with a not-yet-shown
     *  capture. That decoupling is what keeps the two paths race-free by construction. */
    private var captureDisplayId = -1
    private var captureGeometry: DisplayGeometry? = null

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
        val stashedAtMs: Long,
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
            // The in-app boxes' window dies with its display; drop our handle
            // (and notify the app's toggle) rather than holding a zombie.
            if (displayId == appBoxesDisplayId) hideAppBoxes()
            reconcileFloatingIcons()
        }
        override fun onDisplayChanged(displayId: Int) {
            if (!isActiveController) return
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
            // Same stale-mapping rule for the in-app result's on-screen boxes.
            dropReconfiguredAppBoxes(displayId)
            // Order matters: icon reposition first so it picks up the new
            // screen dimensions; the intro + open menu reposition then read the
            // icon's freshly-updated centre Y to anchor themselves relative to it.
            repositionIconForDisplay(displayId)
            repositionSonarIntroForDisplay(displayId)
            repositionFloatingMenuForDisplay(displayId)
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
    )

    // ── Floating icon registry ───────────────────────────────────────────

    /** Per-display floating-icon bundle — the icon, its WindowManager, the
     *  drag-lookup controller, and the live-pause clear-flag closure all
     *  lifecycle together. */
    private data class FloatingIconHandle(
        val icon: FloatingOverlayIcon,
        val wm: WindowManager,
        val dragController: DragLookupController,
        val clearLivePauseFlag: () -> Unit,
    )

    private val iconHandles: MutableMap<Int, FloatingIconHandle> = mutableMapOf()

    // ── Sonar-ping intro ─────────────────────────────────────────────────
    //
    // Attention animation that plays whenever a fresh floating icon lands in
    // a window (see design_handoff_sonar_ping/). Fires for every install —
    // capture activation, the QS tile flipping showOverlayIcon on, a new
    // display being added, etc. Does NOT fire for rotation
    // (repositionIconForDisplay updates the existing icon's params in place)
    // or for z-order re-raises (bringFloatingIconsToFront removes-and-re-
    // adds the same icon view without going through install).
    //
    // Keyed by displayId so simultaneous intros on multi-display setups don't
    // overwrite each other's overlay-window references and leave one
    // orphaned.

    private val activeSonarIntros: MutableMap<Int, SonarPingIntroView> = mutableMapOf()

    /** True iff at least one floating icon is currently registered. */
    val hasAnyFloatingIcon: Boolean get() = iconHandles.isNotEmpty()

    fun setIconsLoading(loading: Boolean) {
        iconHandles.values.forEach { it.icon.showLoading = loading }
    }

    fun setIconsDegraded(degraded: Boolean) {
        iconHandles.values.forEach { it.icon.degraded = degraded }
    }

    fun setIconsLiveMode(liveMode: Boolean) {
        iconHandles.values.forEach { it.icon.liveMode = liveMode }
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
    /** Display the open [floatingMenu] is anchored on, so a rotation of that
     *  display can re-anchor it ([repositionFloatingMenuForDisplay]). */
    private var floatingMenuDisplayId: Int? = null

    private var pillView: View? = null
    private val pillHandler = Handler(Looper.getMainLooper())

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
        val wm = ctx.getSystemService(WindowManager::class.java) ?: return
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
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
            y = (40 * dp).toInt()
        }

        overlayHost.addOverlayWindow(view, wm, params, display.displayId)
        pillView = view

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
        verticalTextTarget: Boolean = false,
        verticalTextStackable: Boolean = false,
        verticalGrowEnabled: Boolean = false,
        authoritativeBounds: Boolean = false,
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
        // verticalTextTarget all match. The first two differing means the
        // window flags (FLAG_NOT_TOUCHABLE), params.alpha, mask alpha, or
        // tap-to-dismiss listener would need to change — those are fixed at
        // construction and at addOverlayWindow time. verticalTextTarget is also
        // a constructor val (it selects the per-box render path) and derives
        // from the user-mutable target-language pref, so a mid-session target
        // switch must force a fresh view rather than silently reuse the stale
        // render mode. The same applies to verticalTextStackable (target script)
        // and verticalGrowEnabled (grow pref) — both are ctor vals that select the
        // render path. In the current call flow none of these transitions hit this
        // reuse path (beginHoldPreview/endHoldPreview teardown ensures a fresh
        // create, and the grow toggle restarts live mode), but the guard prevents a
        // future caller from silently inheriting a stale view.
        val existing = translationOverlayHandles[displayId]
        if (existing != null && existing.pinholeMode == pinholeMode && existing.oneShot == oneShot &&
            existing.verticalTextTarget == verticalTextTarget &&
            existing.verticalTextStackable == verticalTextStackable &&
            existing.verticalGrowEnabled == verticalGrowEnabled
        ) {
            existing.setBoxes(
                boxes, cropLeft, cropTop, screenshotW, screenshotH, authoritativeBounds,
            )
            return
        }
        hideTranslationOverlayForDisplay(displayId)

        val displayCtx = context.createDisplayContext(display)
        val themedCtx = android.view.ContextThemeWrapper(displayCtx, android.R.style.Theme_DeviceDefault)
        val wm = displayCtx.getSystemService(WindowManager::class.java) ?: return

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
            verticalTextTarget = verticalTextTarget,
            verticalTextStackable = verticalTextStackable,
            verticalGrowEnabled = verticalGrowEnabled,
            onDismiss = if (mainTouchable) {
                { CaptureService.instance?.dismissLiveOverlay(displayId) }
            } else null,
        ).apply {
            setBoxes(boxes, cropLeft, cropTop, screenshotW, screenshotH)
        }
        val mainParams = buildOverlayLayoutParams(
            touchable = mainTouchable,
            windowAlpha = mainWindowAlpha,
        )
        if (!overlayHost.addOverlayWindow(mainView, wm, mainParams, displayId)) return
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

    // ── In-app result "show on screen" boxes (dual-screen) ───────────────

    /** The in-app results page's one-shot boxes: on a dual-screen device the
     *  result panel lives in MainActivity, so — unlike the single-screen
     *  capture panel, which hosts its boxes as its own bottom-most child —
     *  these need a standalone full-screen window over the captured game
     *  display. Deliberately NOT in [translationOverlayHandles]: live cycles
     *  must never adopt or replace this window, and its teardown must notify
     *  the app so the header toggle can unselect. */
    private var appBoxesView: TranslationOverlayView? = null
    private var appBoxesDisplayId = -1

    /** The showing window's dismissed-notification, invoked exactly once at
     *  the OWNERSHIP transition — [hideAppBoxes] disowns-and-notifies
     *  synchronously; the view's detach listener is only a backstop for
     *  removal paths that bypass it (the [OverlayHost.removeAll] sweep,
     *  display death). Keyed to ownership, not view lifetime, because
     *  [OverlayHost.removeOverlayWindow]'s removeView detaches ASYNC: a
     *  replaced/hidden window's late detach must not clear the toggle state
     *  of a NEWER window shown in the meantime. */
    private var appBoxesOnDismissed: (() -> Unit)? = null

    /** Whether the in-app boxes window is currently owned (shown). THE truth
     *  the in-app header toggle renders from — the fragment derives its
     *  selected state from this query instead of mirroring it through the
     *  onDismissed notifications, so a late/missing/duplicate notification
     *  can only delay a refresh, never render a wrong state. Flips
     *  synchronously with ownership: set before [showAppBoxes] returns,
     *  cleared at the top of [hideAppBoxes] (not at the async detach). */
    val appBoxesShowing: Boolean
        get() = appBoxesView != null

    /**
     * Paint a one-shot result's boxes over [displayId] for the in-app results
     * page. The window is touchable — a tap anywhere on it dismisses (the
     * boxes are an ephemeral presentation, and touchable windows are exempt
     * from the MediaProjection QTI visual clamp). Registered through
     * [overlayHost], so clean captures blank it like every other overlay.
     *
     * [onDismissed] fires exactly once, when the controller disowns THIS
     * window — any path: the tap, [hideAppBoxes], a display change,
     * [hideAll]'s sweep — so the caller's toggle state can track paint
     * reality. Deliberate teardowns notify synchronously; only ownership-
     * bypassing removals notify from the (async) detach backstop.
     *
     * Returns false when the surface can't be claimed (live mode owns the
     * game screen, display gone, addView failure); the caller must treat
     * that as "not shown".
     */
    fun showAppBoxes(
        displayId: Int,
        data: OneShotOverlayData,
        onDismissed: () -> Unit,
    ): Boolean {
        hideAppBoxes()
        if (CaptureService.instance?.isLive == true) return false
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = dm.getDisplay(displayId) ?: return false
        val displayCtx = context.createDisplayContext(display)
        val wm = displayCtx.getSystemService(WindowManager::class.java) ?: return false
        val prefs = Prefs(context)
        val view = TranslationOverlayView(
            android.view.ContextThemeWrapper(displayCtx, android.R.style.Theme_DeviceDefault),
            oneShot = true,
            verticalTextTarget = targetSupportsVerticalText(prefs.targetLang),
            verticalTextStackable = stackableTargetScript(prefs.targetLang),
            verticalGrowEnabled = prefs.verticalTextGrow,
            onDismiss = { hideAppBoxes() },
        ).apply {
            setBoxes(data.boxes, data.cropLeft, data.cropTop, data.screenshotW, data.screenshotH)
        }
        // Backstop notification for removal paths that bypass hideAppBoxes
        // (the overlayHost.removeAll sweep, display death): if this view still
        // OWNS the slot when it detaches, no deliberate teardown ran — disown
        // and notify here. The identity guard is load-bearing: removeView
        // detaches async, so a replaced/hidden window's late detach lands
        // after a newer window may already own the slot, and firing
        // unconditionally would clear the NEW window's toggle state while its
        // touchable overlay still blocks the game. Every deliberate teardown
        // instead notifies synchronously in hideAppBoxes, which nulls the
        // field first — so this guard also prevents a double fire.
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) {
                if (appBoxesView === view) {
                    appBoxesView = null
                    appBoxesDisplayId = -1
                    appBoxesOnDismissed = null
                    onDismissed()
                }
            }
        })
        val params = buildOverlayLayoutParams(touchable = true, windowAlpha = 1.0f)
        if (!overlayHost.addOverlayWindow(view, wm, params, displayId)) return false
        appBoxesView = view
        appBoxesDisplayId = displayId
        appBoxesOnDismissed = onDismissed
        // Keep the floating icon's tap target above the touchable window,
        // matching the one-shot cell in showTranslationOverlay.
        bringFloatingIconsToFront(displayId)
        return true
    }

    /** Swap the app boxes in place — the skeleton → translated promotion when
     *  a Done lands while they're up. No-op when they aren't. */
    fun updateAppBoxes(data: OneShotOverlayData) {
        appBoxesView?.setBoxes(
            data.boxes, data.cropLeft, data.cropTop, data.screenshotW, data.screenshotH,
        )
    }

    /** Tear the app boxes down. Idempotent. Disowns the window and fires its
     *  onDismissed SYNCHRONOUSLY — not from the async detach — so the
     *  caller's toggle state updates before any subsequent show can race the
     *  old window's removeView (see [appBoxesOnDismissed]). */
    fun hideAppBoxes() {
        val view = appBoxesView ?: return
        val onDismissed = appBoxesOnDismissed
        appBoxesView = null
        appBoxesDisplayId = -1
        appBoxesOnDismissed = null
        overlayHost.removeOverlayWindow(view)
        onDismissed?.invoke()
    }

    /** Drop the app boxes when their display's geometry changed — same
     *  rationale as [dropResizedTranslationOverlay]: the view's cached
     *  dimensions drive the OCR→screen mapping, so a stale size mispositions
     *  every box. Unlike the live overlay there's no cycle to rebuild them,
     *  so they just dismiss (the result they belong to was captured against
     *  the old geometry anyway). */
    private fun dropReconfiguredAppBoxes(displayId: Int) {
        if (displayId != appBoxesDisplayId) return
        val view = appBoxesView ?: return
        val display = (context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
            ?.getDisplay(displayId) ?: run { hideAppBoxes(); return }
        val size = getDisplaySize(display)
        if (size.x != view.width || size.y != view.height) hideAppBoxes()
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
     * install icons for newly-selected, currently-connected displays.
     *
     * [showIntro] is false only for the projection-loss reinstall
     * ([com.playtranslate.capture.MediaProjectionController.onProjectionLost]):
     * there the icon window was just swept by hideAll and comes straight back,
     * so to the user it never left — replaying the sonar intro reads as a
     * spurious flash (field report 2026-07-11). Every genuinely fresh
     * appearance (Turn On, backend swap, display added) keeps the intro.
     */
    fun reconcileFloatingIcons(showIntro: Boolean = true) {
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
            hideFloatingIcon("pref_disabled")
            return
        }
        if (!isMediaProjection && CaptureLifecycle.floatingIconSuppressed) {
            // Accessibility backend, but the user hasn't opened the app (or
            // pressed Turn On) this process lifetime — either the process
            // just came up from boot, or they chose "Hide for Now". Every
            // resurrect path (service reconnect, display hot-plug, backend
            // reresolve) lands here, so the icon genuinely stays away until
            // MainActivity's onResume lifts the suppression.
            hideFloatingIcon("suppressed_until_app_open")
            return
        }
        if (!canShowControls()) {
            // MediaProjection backend the user hasn't turned on. The gate is
            // deliberately the activation flag, not consent: a revoked
            // projection keeps the controls up — the next capture-requiring
            // action re-prompts (see onProjectionLost).
            hideFloatingIcon("controls_gated")
            return
        }
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        // Resolve the saved selection through the backend shim — MediaProjection
        // collapses a stale non-default selection to its fallback display, so
        // the icon never lands on a screen it can't drive. Empty only when
        // the backend has no fallback either (accessibility with every saved
        // display unreachable); the downstream "all unreachable" branch picks
        // up in that case.
        val target = CaptureBackendResolver.active().capturableTargets(prefs.captureDisplayIds)

        // Snapshot before mutating — tear down icons no longer needed.
        val staleIds = iconHandles.keys.filter { id ->
            id !in target || dm.getDisplay(id) == null
        }
        for (id in staleIds) hideFloatingIconForDisplay(id, "reconcile_remove")

        // If the user has nothing selected OR every selected display is
        // unreachable, fall back to the legacy single-display heuristic so the
        // app always has at least one icon while it's "configured."
        if (target.none { dm.getDisplay(it) != null }) {
            val display = findIconDisplay(prefs) ?: return
            if (display.displayId !in iconHandles) {
                installFloatingIconForDisplay(display, prefs, showIntro)
            }
            return
        }

        for (id in target) {
            if (id in iconHandles) continue
            val display = dm.getDisplay(id) ?: continue
            installFloatingIconForDisplay(display, prefs, showIntro)
        }
    }

    /** Reposition an icon after its display changed (e.g. rotation). */
    fun repositionIconForDisplay(displayId: Int) {
        val handle = iconHandles[displayId] ?: return
        val p = handle.icon.params ?: return
        val pos = Prefs(context).iconPositionForDisplay(displayId)
        handle.icon.setPosition(pos.edge, pos.fraction)
        try { handle.wm.updateViewLayout(handle.icon, p) } catch (_: Exception) {}
    }

    private fun installFloatingIconForDisplay(
        display: Display,
        prefs: Prefs,
        showIntro: Boolean = true,
    ) {
        val displayId = display.displayId
        // Idempotent: if an icon was already there for this display, tear it
        // down first so the closures and registry stay coherent.
        hideFloatingIconForDisplay(displayId, "recreating")

        val displayCtx = context.createDisplayContext(display)
        val wm = displayCtx.getSystemService(WindowManager::class.java) ?: return

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
        icon.onTap = {
            showFloatingMenu(display, icon)
        }

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
        // Track whether live mode / region overlay were active when drag started
        var liveWasPausedForPopup = false
        var overlayHiddenForDrag = false
        val clearLivePauseFlag: () -> Unit = { liveWasPausedForPopup = false }

        fun restoreRegionOverlay() {
            if (overlayHiddenForDrag) {
                overlayHiddenForDrag = false
                regionController.restoreIndicatorAfterDrag()
            }
        }

        fun resumeLiveMode() {
            if (liveWasPausedForPopup) {
                liveWasPausedForPopup = false
                startLiveRouted()
            }
        }

        controller.onSettled = {
            restoreRegionOverlay()
            resumeLiveMode()
        }
        icon.onDragStart = {
            // Hide region preview so the user can see game text while dragging
            if (regionController.hideIndicatorForDrag()) {
                overlayHiddenForDrag = true
            }
            // Pause live mode while dragging for definitions
            if (CaptureService.instance?.isLive == true) {
                liveWasPausedForPopup = true
                stopLiveRouted()
            }
            controller.onDragStart()
        }
        icon.onDragMove = { rawX, rawY -> controller.onDragMove(rawX, rawY) }
        icon.onDragEnd = { controller.onDragEnd() }
        icon.onDragCancel = { controller.cancelDrag() }
        icon.onHoldCancel = { CaptureService.instance?.holdCancel() }
        icon.onHoldStart  = { CaptureService.instance?.holdStart(displayId) }
        icon.onHoldEnd    = { CaptureService.instance?.holdEnd() }
        icon.onAnyTouch   = {
            CaptureService.instance?.lastInteractedDisplayId = displayId
            DimController.notifyInteraction()
        }
        if (overlayHost.addOverlayWindow(icon, wm, params, displayId)) {
            // Set position after addView from this display's saved slot.
            val pos = prefs.iconPositionForDisplay(displayId)
            icon.setPosition(pos.edge, pos.fraction)
            try { wm.updateViewLayout(icon, params) } catch (_: Exception) {}
            iconHandles[displayId] = FloatingIconHandle(
                icon = icon,
                wm = wm,
                dragController = controller,
                clearLivePauseFlag = clearLivePauseFlag,
            )
            // Fresh icon → sonar-ping intro. This is the only "icon added to
            // a window" path (rotation reuses the existing icon's params;
            // bring-to-front re-stacks without going through install), so
            // gating the intro here gives us exactly the firing model the
            // design asked for: every fresh appearance, never a routine
            // re-layout. The one non-appearance that reaches this path is the
            // projection-loss sweep-and-reinstall, which passes
            // showIntro=false (see reconcileFloatingIcons).
            if (showIntro) showSonarIntro(icon, displayId, displayCtx, pos)
        } else {
            controller.destroy()
        }

        CaptureService.instance?.updateForegroundState()
        CaptureService.instance?.syncIconState()
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
        val iconParams = icon.params ?: return
        val edge = if (pos.edge == FloatingOverlayIcon.Edge.LEFT.ordinal)
            FloatingOverlayIcon.Edge.LEFT
        else
            FloatingOverlayIcon.Edge.RIGHT

        val density = displayCtx.resources.displayMetrics.density
        val windowWidth = (SONAR_INTRO_WIDTH_DP * density).toInt()
        val windowHeight = (SONAR_INTRO_HEIGHT_DP * density).toInt()
        val screenSize = displayCtx.displaySizePx()
        // Centre the intro window vertically on the icon's centre Y; the
        // anchor inside the view is at view-x = (width − 28dp) for RIGHT or
        // 28dp for LEFT, which lines the carrier up exactly with the icon's
        // docked compact-mode position on screen.
        val iconCenterY = iconParams.y + icon.viewSizePx / 2
        val windowX = when (edge) {
            FloatingOverlayIcon.Edge.RIGHT -> screenSize.x - windowWidth
            FloatingOverlayIcon.Edge.LEFT -> 0
        }
        val windowY = iconCenterY - windowHeight / 2

        // Stop any earlier intro that's still playing on the same display
        // (e.g. very rapid toggle-off → toggle-on). The new install gets a
        // fresh intro from t=0.
        tearDownSonarIntroForDisplay(displayId)

        val intro = SonarPingIntroView(displayCtx, edge, icon)
        // Touchable (no FLAG_NOT_TOUCHABLE): a touch on the intro resolves
        // the animation early and the gesture is forwarded to the icon —
        // a tap opens the floating menu, a hold or drag starts the
        // magnifying search. See SonarPingIntroView.onTouchEvent.
        val params = WindowManager.LayoutParams(
            windowWidth, windowHeight,
            overlayHost.windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = windowX
            y = windowY
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

        if (overlayHost.addOverlayWindow(intro, wm, params, displayId)) {
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

    /** Re-anchor an in-flight sonar intro after a display change (rotation,
     *  resize). Recomputes x/y from the new screen dimensions and the
     *  icon's freshly-updated centre Y, then pushes a layout update onto
     *  the existing intro window — no view rebuild, animation continues
     *  uninterrupted from wherever it was. No-op when no intro is showing
     *  on [displayId] or the underlying icon handle is gone. */
    private fun repositionSonarIntroForDisplay(displayId: Int) {
        val intro = activeSonarIntros[displayId] ?: return
        val handle = iconHandles[displayId] ?: return
        val params = intro.layoutParams as? WindowManager.LayoutParams ?: return
        val iconParams = handle.icon.params ?: return

        val displayCtx = intro.context
        val density = displayCtx.resources.displayMetrics.density
        val windowWidth = (SONAR_INTRO_WIDTH_DP * density).toInt()
        val windowHeight = (SONAR_INTRO_HEIGHT_DP * density).toInt()
        val screenSize = displayCtx.displaySizePx()
        val iconCenterY = iconParams.y + handle.icon.viewSizePx / 2

        val newX = when (intro.edge) {
            FloatingOverlayIcon.Edge.RIGHT -> screenSize.x - windowWidth
            FloatingOverlayIcon.Edge.LEFT -> 0
        }
        val newY = iconCenterY - windowHeight / 2
        if (params.x == newX && params.y == newY) return
        params.x = newX
        params.y = newY
        try { handle.wm.updateViewLayout(intro, params) } catch (_: Exception) {}
    }

    /** Re-anchor an open floating menu after its display changed (rotation /
     *  resize) so its card tracks the icon's freshly-repositioned edge slot
     *  against the new screen dimensions. Mirrors [repositionIconForDisplay] /
     *  [repositionSonarIntroForDisplay]: must run *after* the icon reposition
     *  so it reads the icon's updated params, and reads the screen size through
     *  [getDisplaySize], whose per-call window context returns the post-rotation
     *  bounds (a cached one intermittently reports the previous orientation
     *  here). The menu's full-screen host re-lays-out its gravity-anchored
     *  children + region preview itself; only the absolutely-placed card needs
     *  this. No-op unless the menu is open on [displayId]. */
    private fun repositionFloatingMenuForDisplay(displayId: Int) {
        val menu = floatingMenu ?: return
        if (floatingMenuDisplayId != displayId) return
        val icon = iconHandles[displayId]?.icon ?: return
        val display = (context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
            ?.getDisplay(displayId) ?: return
        val screenSize = getDisplaySize(display)
        // Below R the menu's full-screen window was pinned to an explicit size
        // that doesn't follow rotation; re-pin it to the new display size before
        // re-anchoring the card, or a right-edge margin computed from the new
        // bounds can land off the still-old-sized surface (no-op on R+).
        overlayHost.resizeFullScreenOverlayForDisplay(menu, displayId)
        val p = icon.params
        val iconCx = (p?.x ?: 0) + icon.viewSizePx / 2
        val iconCy = (p?.y ?: 0) + icon.viewSizePx / 2
        menu.positionNearIcon(
            iconCx, iconCy, icon.currentEdge, screenSize.x, screenSize.y,
            animateEntrance = false,
        )
    }

    /**
     * Remove and re-add floating icons so they draw above newly added
     * overlays. Pass [displayId] = null to bring every icon forward.
     */
    fun bringFloatingIconsToFront(displayId: Int? = null) {
        val targets: List<Map.Entry<Int, FloatingIconHandle>> = if (displayId != null) {
            listOfNotNull(iconHandles.entries.firstOrNull { it.key == displayId })
        } else {
            iconHandles.entries.toList()
        }
        for ((id, handle) in targets) {
            // Never remove+re-add an icon the user is currently touching:
            // destroying its window mid-gesture drops the touch stream, so the
            // finger-lift never fires onHoldEnd/onDragEnd and the held overlay
            // is never torn down. The re-raise resumes on the next overlay
            // update once the gesture ends.
            if (handle.icon.hasActiveGesture) continue
            val params = handle.icon.params ?: continue
            overlayHost.removeOverlayWindow(handle.icon)
            overlayHost.addOverlayWindow(handle.icon, handle.wm, params, id)
        }
    }

    private fun hideFloatingIconForDisplay(displayId: Int, reason: String) {
        // Kill any in-flight sonar intro on this display first — the intro
        // is a sibling overlay that doesn't track the icon's lifecycle, so
        // we have to remove it explicitly.
        tearDownSonarIntroForDisplay(displayId)
        val handle = iconHandles.remove(displayId) ?: return
        Log.i(TAG, "hideFloatingIcon[$displayId]: $reason")
        handle.dragController.destroy()
        handle.icon.destroy()
        overlayHost.removeOverlayWindow(handle.icon)

        CaptureService.instance?.updateForegroundState()
        CaptureService.instance?.syncIconState()
    }

    /** Tear down every floating icon. */
    fun hideFloatingIcon(reason: String = "unspecified") {
        if (iconHandles.isEmpty()) return
        Log.i(TAG, "hideFloatingIcon (all): $reason")
        val ids = iconHandles.keys.toList()
        for (id in ids) hideFloatingIconForDisplay(id, reason)
    }

    /** "Hide for Now": tear the icons down AND suppress them for the rest of
     *  the process lifetime, so reconcile triggers (display hot-plug, service
     *  reconnect) can't resurrect them before the next app open — the
     *  behavior the confirm dialog promises. Plain [hideFloatingIcon] is the
     *  transient teardown for every other reason. */
    private fun hideFloatingIconUntilAppOpen(reason: String) {
        CaptureLifecycle.setFloatingIconSuppressed(context, true)
        hideFloatingIcon(reason)
    }

    /** Called by DragLookupController.openSentenceInApp before dismissing the
     *  magnifier so the dismiss-chain's resumeLiveMode is a no-op when the
     *  detail view will cover the live-mode surface. */
    fun cancelLivePauseObligation() {
        if (effectivelySingleScreen()) {
            iconHandles.values.forEach { it.clearLivePauseFlag.invoke() }
        }
    }

    /**
     * Returns the floating icon's bounding rect in screen coordinates for
     * [displayId], or null if no icon is showing on that display.
     */
    fun getFloatingIconRect(displayId: Int): android.graphics.Rect? {
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
    }

    private fun showFloatingMenu(display: Display, icon: FloatingOverlayIcon) {
        dismissFloatingMenu()
        val wm = context.createDisplayContext(display).getSystemService(WindowManager::class.java) ?: return
        val screenSize = getDisplaySize(display)
        val themeRes = baseActivityTheme(context)
        val themedCtx = android.view.ContextThemeWrapper(context.createDisplayContext(display), themeRes)
        applyAccentOverlay(themedCtx.theme, context)
        val menu = FloatingIconMenu(themedCtx)
        menu.isSingleScreen = Prefs.isSingleScreen(context)
        menu.exitFlow = CaptureLifecycle.hasActivateControl(context)

        // Suppress live captures while menu is open.
        CaptureService.instance?.holdActive = true
        hideTranslationOverlay()
        dismissCaptureResultOverlay()
        // The icon tap is a button press — the in-app boxes' dismissal
        // contract (any interaction dismisses the ephemeral presentation),
        // and the menu would sit over them anyway.
        hideAppBoxes()

        val prefs = Prefs(context)
        val hintKind = SourceLanguageProfiles[prefs.sourceLangId].hintTextKind
        menu.hintModeLabel = hintModeLabelFor(prefs.overlayMode, hintKind)
        menu.isLiveMode = CaptureService.instance?.isLive == true
        menu.captureHighlighted = captureIsPreferredPrimary

        // Expanded settings-panel table: data + row actions.
        val languageName = prefs.sourceLangId.displayName()
        val ocrName = OcrModelManager.selectedBackend(context, prefs.sourceLangId)?.ocrLabel(context) ?: "ML Kit"
        val overlayValue = if (hintKind != HintTextKind.NONE)
            overlayModeLabel(prefs.overlayMode, hintKind) else null
        menu.onSelectLanguage = {
            // Mirror CaptureResultOverlay.changeLanguage(true): open the source
            // picker — in the floating workspace over the game where available,
            // else the Activity (dual-screen, where full pages are the correct
            // presentation on the app display).
            dismissFloatingMenu()
            if (!openWorkspace(display.displayId) { SourceListPage() }) {
                LanguageSetupActivity.selectionDelegate = null
                launchOnOverlayDisplay(
                    Intent(context.applicationContext, LanguageSetupActivity::class.java)
                        .putExtra(LanguageSetupActivity.EXTRA_MODE, LanguageSetupActivity.MODE_SOURCE),
                    display.displayId,
                )
            }
        }
        menu.onSelectOcr = {
            // Show the picker over the still-open menu so its holdActive keeps
            // live capture paused. The menu dismisses only if a different engine
            // is chosen (see showOcrPicker); Cancel/same-engine returns here.
            showOcrPicker(display, prefs.sourceLangId)
        }
        // MangaOCR quick toggle: only when the pack is installed and the source
        // language can use it (JA + MNN abi) — mirrors the settings cell's gates.
        // Flips the pref in place via the one write path (no dialog, pack kept).
        val mangaOcrStateLabel = { on: Boolean ->
            context.getString(
                if (on) R.string.capture_lifecycle_state_on else R.string.capture_lifecycle_state_off
            )
        }
        val mangaOcrValue = if (
            prefs.sourceLangId == SourceLangId.JA &&
            OcrModelManager.isMnnAvailable() &&
            MangaOcrProvisioning.helper().isInstalled(context)
        ) mangaOcrStateLabel(prefs.useMangaOcr) else null
        menu.onToggleMangaOcr = {
            val on = !Prefs(context).useMangaOcr
            MangaOcrProvisioning.setEnabled(context, on, scope)
            menu.setMangaOcrValue(mangaOcrStateLabel(on))
        }
        menu.onCycleOverlayMode = {
            val modes = availableOverlayModes(prefs.sourceLangId)
            val next = modes[(modes.indexOf(prefs.overlayMode) + 1) % modes.size]
            prefs.overlayMode = next
            menu.setOverlayModeValue(overlayModeLabel(next, hintKind))
            // Keep the auto-translate button's hint label in sync (Auto Furigana
            // / Auto Pinyin / Auto Translate); it's hidden while the panel is
            // open and shows the new label on collapse.
            menu.hintModeLabel = hintModeLabelFor(next, hintKind)
        }
        menu.onOpenApp = {
            dismissFloatingMenu()
            sendMainActivityIntent(MainActivity.ACTION_OPEN_SETTINGS)
        }
        menu.setPanelData(languageName, ocrName, overlayValue, mangaOcrValue)
        menu.degradedWarningKind =
            CaptureService.instance?.degradationState?.value
                ?: com.playtranslate.ui.DegradedWarningKind.None
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
            // Auto-translate becomes the most-recently-used primary.
            captureIsPreferredPrimary = false
            dismissFloatingMenu()
            if (CaptureService.instance?.isLive == true) {
                stopLiveRouted()
            } else {
                if (Prefs.shouldUseInAppOnlyMode(context)) {
                    sendMainActivityIntent(MainActivity.ACTION_START_LIVE)
                } else {
                    startLiveRouted()
                }
            }
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
        // prompts itself). IfInitialized: an unrealized controller holds no
        // consent; don't force-init it just to render a button. The
        // RECORD_AUDIO gate is part of the predicate, not the action: an
        // overlay can't walk the user through a runtime-permission grant, so
        // when the mic is the missing piece this surface offers nothing and
        // the Settings row (which CAN run that flow) is the recovery path —
        // a surface must not offer a verb it can't perform.
        menu.showRecordAudio = prefs.recordGameAudio &&
            CaptureService.instance?.mediaProjectionControllerIfInitialized?.hasConsent != true &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.RECORD_AUDIO
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        menu.onRecordAudio = {
            dismissFloatingMenu()
            startAudioCaptureConsent()
        }

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

        overlayHost.addOverlayWindow(menu, wm, params, display.displayId)
        floatingMenu = menu
        floatingMenuDisplayId = display.displayId

        val p = icon.params
        val iconCx = (p?.x ?: 0) + icon.viewSizePx / 2
        val iconCy = (p?.y ?: 0) + icon.viewSizePx / 2
        menu.positionNearIcon(iconCx, iconCy, icon.currentEdge, screenSize.x, screenSize.y)
    }

    /** Overlay modes selectable for [id]: just Translation, unless the language
     *  carries a reading hint (Furigana/Pinyin), which adds the hint mode. */
    private fun availableOverlayModes(id: SourceLangId): List<OverlayMode> =
        if (SourceLanguageProfiles[id].hintTextKind == HintTextKind.NONE)
            listOf(OverlayMode.TRANSLATION)
        else listOf(OverlayMode.TRANSLATION, OverlayMode.FURIGANA)

    /** User-facing name for an overlay [mode]; the reading-hint mode reads
     *  "Pinyin" for Pinyin languages and "Furigana" otherwise. */
    private fun overlayModeLabel(mode: OverlayMode, hintKind: HintTextKind): String = when (mode) {
        OverlayMode.TRANSLATION -> context.getString(R.string.overlay_mode_option_translation)
        OverlayMode.FURIGANA -> context.getString(
            if (hintKind == HintTextKind.PINYIN) R.string.overlay_mode_option_pinyin
            else R.string.overlay_mode_option_furigana
        )
    }

    /** Reading-hint label for the auto-translate button ("Auto Furigana" /
     *  "Auto Pinyin") while the hint overlay mode is active; null otherwise (the
     *  button reads "Auto Translate"). */
    private fun hintModeLabelFor(mode: OverlayMode, hintKind: HintTextKind): String? =
        if (mode == OverlayMode.FURIGANA && hintKind != HintTextKind.NONE) {
            when (hintKind) { HintTextKind.PINYIN -> "Pinyin"; else -> "Furigana" }
        } else null

    /** Open the "Choose OCR tool" OverlayAlert for [id] on [display], stacked
     *  over the still-open floating menu so its holdActive keeps live capture
     *  paused. There's no live result to re-OCR here, so a different-engine pick
     *  just dismisses the menu (clearing holdActive so live resumes with the new
     *  engine, persisted by OcrPicker); Cancel or re-picking the current engine
     *  leaves the menu open underneath. */
    private fun showOcrPicker(display: Display, id: SourceLangId) {
        val displayCtx = context.createDisplayContext(display)
        val wm = displayCtx.getSystemService(WindowManager::class.java) ?: return
        val themed = overlayThemedContext(displayCtx)
        OcrPicker.populate(
            OverlayAlert.Builder(themed, overlayHost, wm, display.displayId),
            themed,
            id,
            OcrModelManager.selectedBackend(context, id)?.selectionToken ?: "",
            onReOcr = { dismissFloatingMenu() },
            onDownload = { backend ->
                dismissFloatingMenu()
                launchOnOverlayDisplay(
                    CaptureOverlaySettingsActivity.downloadIntent(
                        context.applicationContext, id, backend.selectionToken
                    ),
                    display.displayId,
                )
            },
        ).showAsOverlay()
    }

    /** Launch [intent] as a NEW_TASK activity on the foreground display (else
     *  [fallbackDisplayId]) — mirrors the result overlay's language/OCR deep-links. */
    private fun launchOnOverlayDisplay(intent: Intent, fallbackDisplayId: Int) {
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
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
        // Remove the menu's surface synchronously: a capture started right after
        // (e.g. live mode's first clean frame on "auto translate") must not catch
        // the menu's lingering dim/"Drag finger" hint in the shot.
        floatingMenu?.let { overlayHost.removeOverlayWindow(it, immediate = true) }
        floatingMenu = null
        floatingMenuDisplayId = null
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
    private fun startAudioCaptureConsent() {
        val svc = CaptureService.instance
        if (svc != null) {
            svc.serviceScope.launch { svc.mediaProjectionController.ensureConsent() }
        } else {
            androidx.core.content.ContextCompat.startForegroundService(
                context.applicationContext,
                Intent(context.applicationContext, CaptureService::class.java)
                    .setAction(CaptureService.ACTION_MP_ACTIVATE),
            )
        }
    }

    private fun showHideConfirmAlert(display: Display) {
        val displayCtx = context.createDisplayContext(display)
        val overlayWm = displayCtx.getSystemService(WindowManager::class.java) ?: return
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
    private fun effectivelySingleScreen(): Boolean =
        Prefs.isSingleScreen(context) || !MainActivity.isInForeground

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
     *
     * Start/stop reuse [onToggleLive]'s exact single- vs dual-screen /
     * InAppOnly routing. The in-place switch sets [Prefs.overlayMode] and
     * reconciles: [CaptureService.setLiveDisplays]'s flavor-mismatch detector
     * rebuilds each per-display mode instance for the new overlay flavor.
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
                prefs.overlayMode = mode
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
                    svc.reconcileLiveModes("hotkey_mode_switch")
                }
            }
        } else {
            // Start in this hotkey's mode. The pref write is committed before
            // start/route reads it (same-process, synchronous read-after-write).
            prefs.overlayMode = mode
            if (Prefs.shouldUseInAppOnlyMode(context)) {
                sendMainActivityIntent(MainActivity.ACTION_START_LIVE)
            } else {
                startLiveRouted()
            }
        }
    }

    /**
     * "Capture screen" hotkey action — a toggle. If a capture-result overlay
     * from a prior press is still on screen, dismiss it and stop; the *next*
     * press then runs a fresh capture. Otherwise run the one-shot immediately.
     * Called on the main thread via the active overlay UI, matching
     * [toggleAutoMode]'s routing contract.
     *
     * The predicate is exactly [captureResultOverlay] because that panel window
     * is what a one-shot shows on a single-screen device (its on-game boxes are
     * its own bottom-most child, torn down with it) — precisely the "translation
     * overlay" a capture leaves up. A running auto session's live overlay is a
     * different surface with its own tap hotkey, so it is deliberately not
     * treated as a capture result here; a capture press during live stops live
     * and captures, same as the floating menu's Capture button.
     */
    fun toggleCaptureScreenForDisplay(displayId: Int) {
        if (captureResultOverlay != null) {
            animateOutCaptureResultOverlay()
            return
        }
        captureCurrentRegionForDisplay(displayId)
    }

    /**
     * Animate the capture-result panel out (slide-up), matching a tap-outside /
     * swipe dismissal, rather than the instant teardown of
     * [dismissCaptureResultOverlay] (which the menu-open / supersede / teardown
     * paths use because they need the window gone synchronously). The overlay's
     * own onDismiss nulls [captureResultOverlay] at the end of the animation
     * (exactly as a gesture dismiss does); a re-press mid-animation is a no-op
     * (the overlay guards its in-flight exit).
     */
    private fun animateOutCaptureResultOverlay() {
        captureResultOverlay?.animateOutAndDismiss()
    }

    /**
     * Run a one-shot capture of [displayId]'s current region (full screen unless
     * one is set) — the action shared by the floating menu's Capture button and
     * the capture-screen hotkey. [handleRegionSelection] routes single- vs
     * dual-screen and configures the override itself; a running auto session is
     * stopped first so this is a clean one-shot, not a live refresh. Marks
     * capture the most-recently-used primary (styling for the next menu open).
     */
    private fun captureCurrentRegionForDisplay(displayId: Int) {
        captureIsPreferredPrimary = true
        val region = CaptureService.instance?.activeRegionForDisplay(displayId)
            ?: CaptureService.DEFAULT_REGION
        if (CaptureService.instance?.isLive == true) {
            stopLiveRouted()
        }
        handleRegionSelection(displayId, region)
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
     * Routes a drag-selected region to the appropriate activity. Effectively
     * single screen (or app backgrounded): capture now and hand the path to
     * TranslationResultActivity. Otherwise: send ACTION_REGION_CAPTURE.
     */
    private fun handleRegionSelection(displayId: Int, region: RegionEntry) {
        if (effectivelySingleScreen()) {
            showCaptureResultOverlay(displayId, region)
        } else {
            val intent = Intent(context, MainActivity::class.java).apply {
                action = MainActivity.ACTION_REGION_CAPTURE
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(MainActivity.EXTRA_TOP_FRAC, region.top)
                putExtra(MainActivity.EXTRA_BOTTOM_FRAC, region.bottom)
                putExtra(MainActivity.EXTRA_LEFT_FRAC, region.left)
                putExtra(MainActivity.EXTRA_RIGHT_FRAC, region.right)
                putExtra(MainActivity.EXTRA_TARGET_DISPLAY_ID, displayId)
            }
            context.startActivity(intent)
        }
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
     * the game for [TranslationResultActivity]. Dismisses any existing panel
     * first (single-instance + screenshot-blanking guard — a second capture must
     * remove the old panel before the next requestClean blanks it into the shot),
     * shows the new panel only AFTER the clean capture returns (so it's never in
     * the shot), then feeds the one-shot pipeline into it. Falls back to the
     * Activity if the capture-service handle isn't available.
     */
    private fun showCaptureResultOverlay(displayId: Int, region: RegionEntry) {
        dismissCaptureResultOverlay()
        val svc = CaptureService.instance ?: run {
            launchResultActivity(displayId, region)
            return
        }
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = dm.getDisplay(displayId) ?: run {
            launchResultActivity(displayId, region)
            return
        }
        val displayCtx = context.createDisplayContext(display)
        val wm = displayCtx.getSystemService(WindowManager::class.java) ?: run {
            launchResultActivity(displayId, region)
            return
        }
        val size = getDisplaySize(display)
        // The geometry this shot is taken against, kept LOCAL to the coroutine: the capture
        // validates itself against it after requestClean (below), so it never depends on an
        // onDisplayChanged event — or its timing — to cancel a stale capture. The shared
        // captureGeometry field is set only once a panel is actually shown.
        val launchGeometry = DisplayGeometry(size.x, size.y, display.rotation)
        val gen = captureGeneration
        captureJob = scope.launch {
            // The frame carries its capture-time facts (CapturedFrame).
            val frame = CaptureBackendResolver.active().captureSource?.requestClean(displayId)
            val bitmap = frame?.bitmap
            // Single validation gate at the ONLY pre-panel suspension point. Bail if EITHER:
            //  • a newer capture / teardown superseded us (generation bumped), or
            //  • the captured display reconfigured under us (live geometry != the launch
            //    snapshot) — a rotation/resize mid-capture would size the panel + OCR mapping
            //    to stale geometry; a gone display reads null (!= snapshot) → also bails.
            // Self-contained, so correctness holds regardless of when — or whether —
            // onDisplayChanged fires. That decoupling is what removes the rotate-then-tap race.
            val curGeometry = displayGeometry(displayId)
            if (gen != captureGeneration || curGeometry != launchGeometry) {
                bitmap?.recycle()
                return@launch
            }
            // Committed: record what the SHOWN panel is built for, so a later reconfiguration
            // of its display dismisses it (dismissCaptureResultPanelIfReconfigured). Register
            // the panel only AFTER the clean capture, so its window isn't blanked into the shot.
            captureDisplayId = displayId
            captureGeometry = launchGeometry
            val overlay = com.playtranslate.ui.CaptureResultOverlay(displayCtx, wm, displayId, overlayHost)
            overlay.onDismiss = { if (captureResultOverlay === overlay) captureResultOverlay = null }
            overlay.onNavigateToDetail = { result -> stashCaptureOverlayForReshow(displayId, result) }
            // Over-game sheet: B/dpad/stick drive it while a controller is attached.
            overlay.controllerNavEnabled = true
            captureResultOverlay = overlay
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
            // Defer the translation only while the on-frame boxes are OFF for
            // this flow: with boxes intended, translated chips must go up with
            // the result. (The panel's funnel completes a deferred result if
            // the user flips boxes on later.) Posture needs no term here —
            // effectiveStartPosture drops a collapsed posture when boxes are
            // off, so "collapsed sliver waiting on a deferred translation"
            // can't happen.
            val allowDefer = !Prefs(context).captureBoxesEnabled
            val session = if (frame != null)
                svc.processScreenshot(frame, displayId, allowDeferTranslation = allowDefer)
                else svc.captureOnce(displayId, allowDeferTranslation = allowDefer)
            overlay.observe(session)
        }
    }

    /** Pre-overlay fallback: launch [TranslationResultActivity]. Used only when
     *  the capture-service handle is briefly unavailable. */
    private fun launchResultActivity(displayId: Int, region: RegionEntry) {
        scope.launch {
            val frame = CaptureBackendResolver.active().captureSource?.requestClean(displayId)
            val intent = Intent(context, com.playtranslate.ui.TranslationResultActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(com.playtranslate.ui.TranslationResultActivity.EXTRA_TOP_FRAC, region.top)
                putExtra(com.playtranslate.ui.TranslationResultActivity.EXTRA_BOTTOM_FRAC, region.bottom)
                putExtra(com.playtranslate.ui.TranslationResultActivity.EXTRA_LEFT_FRAC, region.left)
                putExtra(com.playtranslate.ui.TranslationResultActivity.EXTRA_RIGHT_FRAC, region.right)
                putExtra(com.playtranslate.ui.TranslationResultActivity.EXTRA_TARGET_DISPLAY_ID, displayId)
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

    /** Remove the over-game result panel if showing. Idempotent. The overlay
     *  cancels its own session observer + scope on dismiss. */
    fun dismissCaptureResultOverlay() {
        // Bump first so any in-flight setup coroutine that already passed
        // requestClean fails its generation check, then cancel the suspended one.
        captureGeneration++
        captureJob?.cancel()
        captureJob = null
        captureResultOverlay?.dismiss()
        captureResultOverlay = null
        captureDisplayId = -1
        captureGeometry = null
        // Any teardown invalidates a pending re-show. The stash path re-sets it
        // immediately AFTER calling this, so its own teardown doesn't lose it.
        pendingReshow = null
    }

    /** The display geometry a capture/panel is laid out against — size AND rotation. A
     *  180° flip keeps the pixel size but changes rotation, so comparing this (not size
     *  alone) still invalidates a stale panel; a refresh-rate/HDR event keeps both, so it
     *  doesn't churn one. */
    private data class DisplayGeometry(val width: Int, val height: Int, val rotation: Int)

    /** Size + rotation of [displayId], or null if it can't be resolved. */
    private fun displayGeometry(displayId: Int): DisplayGeometry? {
        val display = (context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
            ?.getDisplay(displayId) ?: return null
        val size = getDisplaySize(display)
        return DisplayGeometry(size.x, size.y, display.rotation)
    }

    /** Dismiss a SHOWING capture panel when its own display's geometry (size or rotation)
     *  changed — the panel was laid out + OCR-mapped against that geometry, so a 180° flip
     *  (rotation change, same pixels) invalidates it too. Scoped to the panel's display, so a
     *  late event from a secondary display leaves it alone. Deliberately does NOT cancel an
     *  in-flight capture (`captureResultOverlay == null` while one is setting up): that
     *  self-validates its geometry after requestClean, so nothing here can race it. */
    private fun dismissCaptureResultPanelIfReconfigured(changedDisplayId: Int) {
        if (captureResultOverlay == null) return
        if (changedDisplayId != captureDisplayId) return
        val built = captureGeometry ?: return
        val now = displayGeometry(changedDisplayId) ?: return
        if (now != built) {
            dismissCaptureResultOverlay()
        }
    }

    /** Stash the overlay's current result and tear the live panel down when its
     *  word lens opens the in-app detail screen, so a user-initiated back from that
     *  screen ([onCaptureDetailBackPressed]) can re-show the panel. */
    fun stashCaptureOverlayForReshow(displayId: Int, result: com.playtranslate.model.TranslationResult) {
        dismissCaptureResultOverlay()
        pendingReshow = PendingReshow(displayId, result, android.os.SystemClock.elapsedRealtime())
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
        reshowCaptureOverlay(displayId, pending.result)
    }

    /** Re-create the over-game panel and bind a stashed result (no re-capture). */
    private fun reshowCaptureOverlay(displayId: Int, result: com.playtranslate.model.TranslationResult) {
        dismissCaptureResultOverlay()
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = dm.getDisplay(displayId) ?: return
        val displayCtx = context.createDisplayContext(display)
        val wm = displayCtx.getSystemService(WindowManager::class.java) ?: return
        val size = getDisplaySize(display)
        captureDisplayId = displayId
        captureGeometry = DisplayGeometry(size.x, size.y, display.rotation)
        val overlay = com.playtranslate.ui.CaptureResultOverlay(displayCtx, wm, displayId, overlayHost)
        overlay.onDismiss = { if (captureResultOverlay === overlay) captureResultOverlay = null }
        overlay.onNavigateToDetail = { r -> stashCaptureOverlayForReshow(displayId, r) }
        // Same controller-nav opt-in as the fresh-capture path, or the sheet
        // would come back keyless after a detail round-trip.
        overlay.controllerNavEnabled = true
        captureResultOverlay = overlay
        // The frosted backdrop is rebuilt from the result's clean capture
        // (the same image the fresh path blurred): decoded here, blurred by
        // show into its small copy, recycled immediately after.
        val backdrop = result.screenshotPath?.let {
            runCatching { android.graphics.BitmapFactory.decodeFile(it) }.getOrNull()
        }
        overlay.showWithResult(size.x, size.y, result, backdrop)
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
        val wm = displayCtx.getSystemService(WindowManager::class.java) ?: return false
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
        val now = displayGeometry(changedDisplayId) ?: return
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
        val primaryId = backend.capturableTargets(prefs.captureDisplayIds).firstOrNull()
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
        dismissCaptureResultOverlay()
        dismissWorkspace()
        hideTranslationOverlay()
        hideAppBoxes()
        regionController.hideAll()
        dismissFloatingMenu()
        hideFloatingIcon("hideAll")
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
