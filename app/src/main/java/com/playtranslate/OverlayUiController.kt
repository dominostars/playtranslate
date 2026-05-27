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
import com.playtranslate.language.SourceLanguageProfiles
import com.playtranslate.overlay.OverlayHost
import com.playtranslate.ui.DimController
import com.playtranslate.ui.DragLookupController
import com.playtranslate.ui.FloatingIconMenu
import com.playtranslate.ui.FloatingOverlayIcon
import com.playtranslate.ui.MagnifierLens
import com.playtranslate.ui.OverlayAlert
import com.playtranslate.ui.SonarPingIntroView
import com.playtranslate.ui.TextBox
import com.playtranslate.ui.TranslationOverlayView
import com.playtranslate.ui.WordLookupPopup
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
            if (isActiveController) reconcileFloatingIcons()
        }
        override fun onDisplayChanged(displayId: Int) {
            if (!isActiveController) return
            // A rotation / resize invalidates a per-box translation overlay
            // group — its cached displayW/displayH drive the OCR→screen
            // mapping. Drop it on a size change; the next capture cycle
            // rebuilds the group at the new dimensions.
            dropResizedTranslationOverlay(displayId)
            // Order matters: icon reposition first so it picks up the new
            // screen dimensions; the intro reposition then reads the icon's
            // freshly-updated centre Y to position itself relative to it.
            repositionIconForDisplay(displayId)
            repositionSonarIntroForDisplay(displayId)
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

    // ── Translation overlay registry ─────────────────────────────────────

    /**
     * One display's translation overlay state: a `main` window that holds
     * the visible (clean) boxes, and (in pinhole live mode only) a `dirty`
     * companion window that holds boxes flagged for re-OCR.
     * [PinholeOverlayMode] hides the dirty window's view-alpha to 0 before
     * a screenshot so the OCR pipeline sees clean game pixels under those
     * boxes, then restores it after capture. The two-window split is
     * structural: clean and dirty never share a Surface, so every "what's
     * currently visible on main?" accessor automatically yields the clean
     * subset without explicit filtering.
     *
     * Furigana live mode and one-shot translation never use the dirty
     * companion, so we skip its allocation entirely in those modes
     * ([dirty] is null). That avoids the full-screen mask bitmap, the
     * extra Surface, and the dirty window's contribution to AOSP's
     * untrusted-touch combined-obscuring opacity check.
     *
     * For pinhole live mode where dirty does exist, the controller toggles
     * dirty's *window* α between 0 (when no dirty content is parked) and
     * [intendedAlpha] (when content is parked). This keeps the AOSP
     * combined-obscuring composite (main + dirty) at exactly main's α in
     * the steady state — touches pass through to the game — and only
     * crosses the threshold during the brief window (~250ms) when dirty
     * actually has content. Per-cycle hide-during-capture rides on top via
     * dirty's *view* α toggle, which [PinholeOverlayMode] drives directly.
     */
    private data class TranslationOverlayHandle(
        val main: TranslationOverlayView,
        val dirty: TranslationOverlayView?,
        /** WindowManager + params kept so [setDirtyBoxes] can mutate dirty's
         *  window α. Null when [dirty] is null. */
        val dirtyWm: WindowManager?,
        val dirtyParams: WindowManager.LayoutParams?,
        /** Window α that dirty should have when it carries content. Stored
         *  so [setDirtyBoxes] doesn't have to recompute the matrix to bump
         *  the window α back up after an empty→non-empty transition. */
        val intendedDirtyAlpha: Float,
    )

    private val translationOverlayHandles: MutableMap<Int, TranslationOverlayHandle> = mutableMapOf()

    /** Main translation overlay view for [displayId], or null. */
    fun translationOverlayForDisplay(displayId: Int): TranslationOverlayView? =
        translationOverlayHandles[displayId]?.main

    /** Persistent dirty companion overlay for [displayId], or null.
     *  [PinholeOverlayMode] parks dirty boxes here and toggles its view-alpha
     *  per cycle to hide their pixels during raw capture. */
    fun dirtyOverlayForDisplay(displayId: Int): TranslationOverlayView? =
        translationOverlayHandles[displayId]?.dirty

    /** True iff [displayId] currently has a translation overlay. */
    fun hasTranslationOverlay(displayId: Int): Boolean =
        translationOverlayHandles.containsKey(displayId)

    /** True iff any display has a translation overlay registered. */
    val hasAnyTranslationOverlay: Boolean
        get() = translationOverlayHandles.isNotEmpty()

    /** Screen rects of the main overlay's text-box children on [displayId]
     *  — pinhole detection samples these for change detection. Dirty boxes
     *  live on a separate view, so no clean/dirty filtering is needed
     *  here. */
    fun boxScreenRects(displayId: Int): List<Rect> =
        translationOverlayHandles[displayId]?.main?.getChildScreenRects() ?: emptyList()

    /** Display size cached on the main overlay window (the view's dimensions
     *  match the display because the window is MATCH_PARENT). Returns null
     *  when no overlay is registered. */
    fun translationOverlayDisplaySize(displayId: Int): Point? {
        val view = translationOverlayHandles[displayId]?.main ?: return null
        if (view.width <= 0 || view.height <= 0) return null
        return Point(view.width, view.height)
    }

    /** True once the main overlay (and its children) on [displayId] is laid
     *  out — pinhole detection must defer its offscreen render until then. */
    fun areTranslationBoxesLaidOut(displayId: Int): Boolean =
        translationOverlayHandles[displayId]?.main?.areChildrenLaidOut() == true

    /**
     * Render the main overlay's content (no pinholes) to a bitmap — the
     * "overlay_rendered" reference [PinholeOverlayMode.checkPinholes] needs.
     * The caller owns the returned bitmap. Dirty boxes are on a separate
     * window so they are automatically excluded.
     */
    fun renderTranslationOverlayOffscreen(displayId: Int): Bitmap? =
        translationOverlayHandles[displayId]?.main?.renderToOffscreen()

    /**
     * Park dirty boxes on the dirty companion window for [displayId]. Calls
     * `dirty.setBoxes(...)` and atomically toggles the dirty window's α
     * between 0 (empty) and the configured `intendedDirtyAlpha` (content)
     * via `updateViewLayout`, so empty-dirty steady state doesn't contribute
     * to AOSP's combined-obscuring check.
     *
     * No-op when the handle has no dirty companion (furigana / one-shot).
     */
    fun setDirtyBoxes(
        displayId: Int,
        boxes: List<TextBox>,
        cropLeft: Int, cropTop: Int,
        screenshotW: Int, screenshotH: Int,
    ) {
        val handle = translationOverlayHandles[displayId] ?: return
        val dirty = handle.dirty ?: return
        val params = handle.dirtyParams
        val wm = handle.dirtyWm
        val targetAlpha = if (boxes.isEmpty()) 0f else handle.intendedDirtyAlpha
        if (params != null && wm != null && params.alpha != targetAlpha) {
            params.alpha = targetAlpha
            try { wm.updateViewLayout(dirty, params) } catch (_: Exception) {}
        }
        dirty.setBoxes(boxes, cropLeft, cropTop, screenshotW, screenshotH)
    }

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
    ) {
        // Overlay is appearing — dismiss loading spinner across all icons.
        setIconsLoading(false)

        val displayId = display.displayId
        // Reuse the existing main view only if BOTH its pinhole mode AND its
        // oneShot flag match. Either differing means the window flags
        // (FLAG_NOT_TOUCHABLE), params.alpha, mask alpha, or tap-to-dismiss
        // listener would need to change — those are fixed at construction
        // and at addOverlayWindow time, so the safe move is to tear down
        // and recreate. In the current call flow neither transition hits
        // this reuse path (beginHoldPreview/endHoldPreview teardown ensures
        // a fresh create), but this guard prevents a future caller from
        // silently inheriting stale touchability.
        val existing = translationOverlayHandles[displayId]?.main
        if (existing != null && existing.pinholeMode == pinholeMode && existing.oneShot == oneShot) {
            existing.setBoxes(boxes, cropLeft, cropTop, screenshotW, screenshotH)
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

        val mainView = TranslationOverlayView(
            themedCtx,
            pinholeMode = pinholeMode,
            maskAlpha = mainMaskAlpha,
            oneShot = oneShot,
            boostContrast = mainBoostContrast,
        ).apply {
            setBoxes(boxes, cropLeft, cropTop, screenshotW, screenshotH)
        }
        if (mainTouchable) {
            // One-shot MP cell only — the full-screen touchable window
            // captures every touch in its frame. The normal teardown path is
            // the hold release (icon/hotkey/button); this listener is a
            // backup so any tap that lands on the overlay (e.g. a second
            // finger during hold, or a tap that races the hold-release
            // callback) still dismisses instead of being silently swallowed.
            // Returning false leaves the window in its touchable state; we
            // only act on ACTION_DOWN.
            mainView.setOnTouchListener { _, event ->
                if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                    CaptureService.instance?.dismissLiveOverlay(displayId)
                }
                false
            }
        }
        val mainParams = buildOverlayLayoutParams(
            touchable = mainTouchable,
            windowAlpha = mainWindowAlpha,
        )
        if (!overlayHost.addOverlayWindow(mainView, wm, mainParams, displayId)) return

        // Dirty companion window — only created for pinhole live mode. The
        // companion's purpose is to host transient boxes that need re-OCR
        // (PinholeOverlayMode parks them, hides via view-alpha during
        // capture). Furigana live mode and one-shot translation never park
        // anything here, so the surface + mask bitmap + obscuring-opacity
        // contribution would all be pure waste.
        //
        // Window α starts at 0 so the empty dirty window doesn't contribute
        // to AOSP's combined-obscuring check against main on MP. [setDirtyBoxes]
        // bumps α to [mainWindowAlpha] when content is parked and drops it
        // back to 0 when emptied.
        var dirtyView: TranslationOverlayView? = null
        var dirtyParams: WindowManager.LayoutParams? = null
        if (pinholeMode) {
            val dv = TranslationOverlayView(
                themedCtx,
                pinholeMode = true,
                maskAlpha = mainMaskAlpha,
                boostContrast = mainBoostContrast,
            )
            val dp = buildOverlayLayoutParams(
                touchable = false,
                windowAlpha = 0f,
            )
            if (!overlayHost.addOverlayWindow(dv, wm, dp, displayId)) {
                // Roll back main so we don't leak half a handle.
                overlayHost.removeOverlayWindow(mainView)
                return
            }
            dirtyView = dv
            dirtyParams = dp
        }
        translationOverlayHandles[displayId] = TranslationOverlayHandle(
            main = mainView,
            dirty = dirtyView,
            dirtyWm = if (dirtyView != null) wm else null,
            dirtyParams = dirtyParams,
            intendedDirtyAlpha = mainWindowAlpha,
        )

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

    /** Tear down a single display's translation + (optional) dirty overlay.
     *  Idempotent. */
    fun hideTranslationOverlayForDisplay(displayId: Int) {
        val handle = translationOverlayHandles.remove(displayId) ?: return
        overlayHost.removeOverlayWindow(handle.main)
        handle.dirty?.let { overlayHost.removeOverlayWindow(it) }
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
        val view = translationOverlayHandles[displayId]?.main ?: return
        val display = (context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
            ?.getDisplay(displayId) ?: return
        val size = getDisplaySize(display)
        if (size.x != view.width || size.y != view.height) {
            hideTranslationOverlayForDisplay(displayId)
        }
    }

    /** Remove specific boxes from the main overlay on [displayId] without
     *  rebuilding the entire view. */
    fun removeOverlayBoxes(
        toRemove: List<TextBox>,
        displayId: Int = CaptureService.instance?.primaryGameDisplayId()
            ?: android.view.Display.DEFAULT_DISPLAY,
    ) {
        translationOverlayHandles[displayId]?.main?.removeBoxesByContent(toRemove)
    }

    // ── Floating icon ────────────────────────────────────────────────────

    /**
     * Reconcile the floating icons against [Prefs.captureDisplayIds]: tear down
     * icons whose display is no longer selected or has been disconnected, and
     * install icons for newly-selected, currently-connected displays.
     */
    fun reconcileFloatingIcons() {
        val prefs = Prefs(context)
        // The "show the floating icon" preference gates the icon only on the
        // accessibility backend. MediaProjection has no in-app toggle for it
        // — single-screen never did, and dual-screen deliberately doesn't
        // either (the Game Screen Controls row in Settings is a11y-only) — so
        // the icon always shows there while capture is active (consent is the
        // only gate, checked below).
        val isMediaProjection =
            !CaptureBackendResolver.active().requiresAccessibilityService
        if (!isMediaProjection && !prefs.showOverlayIcon) {
            hideFloatingIcon("pref_disabled")
            return
        }
        if (!canShowControls()) {
            // MediaProjection backend without screen-record consent — the
            // floating controls can't drive a capture, so withhold them.
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
                installFloatingIconForDisplay(display, prefs)
            }
            return
        }

        for (id in target) {
            if (id in iconHandles) continue
            val display = dm.getDisplay(id) ?: continue
            installFloatingIconForDisplay(display, prefs)
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

    private fun installFloatingIconForDisplay(display: Display, prefs: Prefs) {
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
            // re-layout.
            showSonarIntro(icon, displayId, displayCtx, pos)
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

        val prefs = Prefs(context)
        val hintKind = SourceLanguageProfiles[prefs.sourceLangId].hintTextKind
        menu.hintModeLabel = if (prefs.overlayMode == OverlayMode.FURIGANA && hintKind != HintTextKind.NONE) {
            when (hintKind) { HintTextKind.PINYIN -> "Pinyin"; else -> "Furigana" }
        } else null
        menu.isLiveMode = CaptureService.instance?.isLive == true
        menu.degradedWarningKind =
            CaptureService.instance?.degradationState?.value
                ?: com.playtranslate.ui.DegradedWarningKind.None
        menu.onHideIcon = {
            dismissFloatingMenu()
            PlayTranslateAccessibilityService.disable(context, "menu_turn_off")
        }
        menu.onHideTemporary = {
            dismissFloatingMenu()
            hideFloatingIcon("menu_hide_temporary")
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
            val prefs = Prefs(context)
            prefs.setSelectedRegionIdForDisplay(display.displayId, Prefs.DEFAULT_REGION_LIST[0].id)
            val svc = CaptureService.instance
            if (svc != null && svc.isConfigured) {
                svc.clearOverride(display.displayId)
            }
            if (MainActivity.isInForeground) {
                sendMainActivityIntent(MainActivity.ACTION_REFRESH_REGION_LABEL)
            }
        }
        menu.onCaptureRegion = {
            dismissFloatingMenu()
            if (effectivelySingleScreen()) {
                regionController.showRegionEditor(display)
            } else {
                sendMainActivityIntent(MainActivity.ACTION_ADD_CUSTOM_REGION, display.displayId)
            }
        }
        menu.onSettings = {
            dismissFloatingMenu()
            sendMainActivityIntent(MainActivity.ACTION_OPEN_SETTINGS)
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

        val p = icon.params
        val iconCx = (p?.x ?: 0) + icon.viewSizePx / 2
        val iconCy = (p?.y ?: 0) + icon.viewSizePx / 2
        menu.positionNearIcon(iconCx, iconCy, icon.currentEdge, screenSize.x, screenSize.y)
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
        floatingMenu?.let { overlayHost.removeOverlayWindow(it) }
        floatingMenu = null
        if (wasShowing && clearHoldActive) {
            CaptureService.instance?.holdActive = false
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
        )

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
            builder.setTitle("Turn Off $appName?")
                .setMessage("Turn back on in $appName app")
                .addButton("Turn Off", dividerColor, dangerColor) {
                    CaptureLifecycle.deactivate(context)
                }
                .addCancelButton()
        } else {
            builder.setTitle("Hide $appName game screen controls?")
                .setMessage("“Hide for Now” brings it back next time you open $appName. “Turn Off” disables it until re-enabled in settings.")
                .addButton("Hide for Now", accentColor) {
                    hideFloatingIcon("confirm_hide_for_now")
                }
                .addButton("Turn Off", dividerColor, dangerColor) {
                    PlayTranslateAccessibilityService.disable(context, "confirm_turn_off_multi")
                }
                .addCancelButton()
        }

        builder.show()
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
            scope.launch {
                val bitmap = CaptureBackendResolver.active().captureSource?.requestClean(displayId)
                val intent = Intent(context, com.playtranslate.ui.TranslationResultActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(com.playtranslate.ui.TranslationResultActivity.EXTRA_TOP_FRAC, region.top)
                    putExtra(com.playtranslate.ui.TranslationResultActivity.EXTRA_BOTTOM_FRAC, region.bottom)
                    putExtra(com.playtranslate.ui.TranslationResultActivity.EXTRA_LEFT_FRAC, region.left)
                    putExtra(com.playtranslate.ui.TranslationResultActivity.EXTRA_RIGHT_FRAC, region.right)
                    putExtra(com.playtranslate.ui.TranslationResultActivity.EXTRA_TARGET_DISPLAY_ID, displayId)
                }
                if (bitmap != null) {
                    val path = savePreCapturedScreenshot(bitmap)
                    bitmap.recycle()
                    intent.putExtra(com.playtranslate.ui.TranslationResultActivity.EXTRA_SCREENSHOT_PATH, path)
                }
                val opts = android.app.ActivityOptions.makeBasic()
                    .setLaunchDisplayId(displayId)
                    .toBundle()
                context.startActivity(intent, opts)
            }
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
        hideTranslationOverlay()
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
