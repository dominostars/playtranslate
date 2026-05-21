package com.playtranslate

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.hardware.display.DisplayManager
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
import com.playtranslate.ui.BoxOverlayView
import com.playtranslate.ui.DimController
import com.playtranslate.ui.DragLookupController
import com.playtranslate.ui.FloatingIconMenu
import com.playtranslate.ui.FloatingOverlayIcon
import com.playtranslate.ui.MagnifierLens
import com.playtranslate.ui.OverlayAlert
import com.playtranslate.ui.OverlayLayout
import com.playtranslate.ui.SonarPingIntroView
import com.playtranslate.ui.TextBox
import com.playtranslate.ui.TranslationOverlayView
import com.playtranslate.ui.WordLookupPopup
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
     * Furigana overlays render on a single full-screen [TranslationOverlayView]
     * (one window per display). Translation overlays — live pinhole and
     * one-shot — render each box as its own [BoxOverlayView] window (see
     * [TranslationOverlayGroup]) so the windows can be individually touchable
     * on the MediaProjection backend and escape Android's anti-tapjacking
     * opacity cap. A display has one or the other, never both.
     */
    private val furiganaOverlays: MutableMap<Int, TranslationOverlayView> = mutableMapOf()

    /** One translation box rendered as its own overlay window. */
    private class BoxWindow(
        val view: BoxOverlayView,
        val params: WindowManager.LayoutParams,
        var box: TextBox,
    )

    /** All per-box windows backing one display's translation overlay. A box
     *  flagged [TextBox.dirty] is still a [BoxWindow]; pinhole detection blanks
     *  it (window alpha) before a raw capture rather than hosting it in a
     *  separate window. */
    private class TranslationOverlayGroup(
        val wm: WindowManager,
        val themedCtx: Context,
        val pinholeMode: Boolean,
        val displayW: Int,
        val displayH: Int,
        val boxWindows: MutableList<BoxWindow> = mutableListOf(),
        var cropLeft: Int = 0,
        var cropTop: Int = 0,
        var screenshotW: Int = 1,
        var screenshotH: Int = 1,
    )

    private val translationOverlayGroups: MutableMap<Int, TranslationOverlayGroup> = mutableMapOf()

    /** Furigana overlay view for [displayId], or null. */
    fun translationOverlayForDisplay(displayId: Int): TranslationOverlayView? =
        furiganaOverlays[displayId]

    /** True iff [displayId] has a per-box translation overlay group. */
    fun hasTranslationOverlay(displayId: Int): Boolean =
        translationOverlayGroups.containsKey(displayId)

    /** True iff any display has a translation or furigana overlay registered. */
    val hasAnyTranslationOverlay: Boolean
        get() = furiganaOverlays.isNotEmpty() || translationOverlayGroups.isNotEmpty()

    /** Screen rects of [displayId]'s clean (non-dirty) per-box translation
     *  windows, in box order — pinhole detection runs only on clean boxes. */
    fun boxScreenRects(displayId: Int): List<Rect> {
        val group = translationOverlayGroups[displayId] ?: return emptyList()
        val loc = IntArray(2)
        return group.boxWindows.filter { !it.box.dirty }.map { bw ->
            bw.view.getLocationOnScreen(loc)
            Rect(loc[0], loc[1], loc[0] + bw.view.width, loc[1] + bw.view.height)
        }
    }

    /** Display size of [displayId]'s translation overlay group, or null. */
    fun translationOverlayDisplaySize(displayId: Int): Point? {
        val group = translationOverlayGroups[displayId] ?: return null
        return Point(group.displayW, group.displayH)
    }

    /** True once every per-box window on [displayId] is laid out — pinhole
     *  detection must defer its offscreen render until then. */
    fun areTranslationBoxesLaidOut(displayId: Int): Boolean {
        val group = translationOverlayGroups[displayId] ?: return false
        if (group.boxWindows.isEmpty()) return true
        return group.boxWindows.all { it.view.isBoxLaidOut() }
    }

    /**
     * Composite every per-box window's rendered content (no pinholes) onto one
     * display-sized bitmap, each box at its on-screen position — the
     * "overlay_rendered" reference [PinholeOverlayMode.checkPinholes] needs.
     * The caller owns the returned bitmap.
     */
    fun renderTranslationOverlayOffscreen(displayId: Int): Bitmap? {
        val group = translationOverlayGroups[displayId] ?: return null
        if (group.displayW <= 0 || group.displayH <= 0) return null
        val bitmap = Bitmap.createBitmap(group.displayW, group.displayH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val loc = IntArray(2)
        for (bw in group.boxWindows) {
            if (bw.box.dirty) continue
            val boxBitmap = bw.view.renderToOffscreen() ?: continue
            bw.view.getLocationOnScreen(loc)
            canvas.drawBitmap(boxBitmap, loc[0].toFloat(), loc[1].toFloat(), null)
            boxBitmap.recycle()
        }
        return bitmap
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
     * Show (or update) the translation/furigana overlay for [display].
     * Furigana boxes render on a single full-screen view; translation boxes
     * render one [BoxOverlayView] window each.
     */
    fun showTranslationOverlay(
        display: Display,
        boxes: List<TextBox>,
        cropLeft: Int, cropTop: Int,
        screenshotW: Int, screenshotH: Int,
        pinholeMode: Boolean = false
    ) {
        // Overlay is appearing — dismiss loading spinner across all icons.
        setIconsLoading(false)

        val isFurigana = boxes.isNotEmpty() && boxes.all { it.isFurigana }
        if (isFurigana) {
            showFuriganaOverlay(display, boxes, cropLeft, cropTop, screenshotW, screenshotH)
        } else {
            showBoxOverlay(display, boxes, cropLeft, cropTop, screenshotW, screenshotH, pinholeMode)
        }
    }

    /** Furigana single-window path — one full-screen [TranslationOverlayView]. */
    private fun showFuriganaOverlay(
        display: Display,
        boxes: List<TextBox>,
        cropLeft: Int, cropTop: Int,
        screenshotW: Int, screenshotH: Int,
    ) {
        val displayId = display.displayId
        val existing = furiganaOverlays[displayId]
        if (existing != null) {
            existing.setBoxes(boxes, cropLeft, cropTop, screenshotW, screenshotH)
            return
        }
        hideTranslationOverlayForDisplay(displayId)
        val displayCtx = context.createDisplayContext(display)
        val themedCtx = android.view.ContextThemeWrapper(displayCtx, android.R.style.Theme_DeviceDefault)
        val wm = displayCtx.getSystemService(WindowManager::class.java) ?: return
        val view = TranslationOverlayView(themedCtx).apply {
            setBoxes(boxes, cropLeft, cropTop, screenshotW, screenshotH)
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayHost.windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { windowAnimations = 0 }
        if (overlayHost.addOverlayWindow(view, wm, params, displayId)) {
            furiganaOverlays[displayId] = view
        }
    }

    /** Per-box translation path — one [BoxOverlayView] window per box. */
    private fun showBoxOverlay(
        display: Display,
        boxes: List<TextBox>,
        cropLeft: Int, cropTop: Int,
        screenshotW: Int, screenshotH: Int,
        pinholeMode: Boolean,
    ) {
        val displayId = display.displayId
        val existing = translationOverlayGroups[displayId]
        if (existing != null && existing.pinholeMode == pinholeMode) {
            if (syncBoxWindows(existing, displayId, boxes, cropLeft, cropTop, screenshotW, screenshotH)) {
                bringFloatingIconsToFront(displayId)
            }
            return
        }
        hideTranslationOverlayForDisplay(displayId)
        val displayCtx = context.createDisplayContext(display)
        val themedCtx = android.view.ContextThemeWrapper(displayCtx, android.R.style.Theme_DeviceDefault)
        val wm = displayCtx.getSystemService(WindowManager::class.java) ?: return
        val size = getDisplaySize(display)
        val group = TranslationOverlayGroup(
            wm = wm, themedCtx = themedCtx, pinholeMode = pinholeMode,
            displayW = size.x, displayH = size.y,
        )
        translationOverlayGroups[displayId] = group
        syncBoxWindows(group, displayId, boxes, cropLeft, cropTop, screenshotW, screenshotH)
        bringFloatingIconsToFront(displayId)
    }

    /**
     * Reconcile [group]'s per-box windows against [boxes] in place: a window
     * whose box still matches (same source text, orientation, ~same bounds) is
     * reused — repositioned/retexted, never recreated — so a change to one box
     * leaves every other box's window untouched. New boxes get a window; gone
     * boxes lose theirs. Stable content (fuzzy-equal boxes, unchanged crop /
     * screenshot) is a no-op. Returns true if a new window was added, so the
     * caller can re-raise the floating icon above it.
     */
    private fun syncBoxWindows(
        group: TranslationOverlayGroup,
        displayId: Int,
        boxes: List<TextBox>,
        cropLeft: Int, cropTop: Int,
        screenshotW: Int, screenshotH: Int,
    ): Boolean {
        val current = group.boxWindows.map { it.box }
        val cropSame = group.cropLeft == cropLeft && group.cropTop == cropTop &&
            group.screenshotW == screenshotW && group.screenshotH == screenshotH
        if (cropSame && (current == boxes || OverlayLayout.boxesMatchFuzzy(current, boxes))) {
            // Visual content is stable — leave the windows. But refresh each
            // BoxWindow.box so metadata-only changes (the dirty flag) register.
            if (current != boxes && group.boxWindows.size == boxes.size) {
                group.boxWindows.forEachIndexed { i, bw -> bw.box = boxes[i] }
            }
            return false
        }
        group.cropLeft = cropLeft
        group.cropTop = cropTop
        group.screenshotW = screenshotW
        group.screenshotH = screenshotH

        val rects = if (group.displayW > 0 && group.displayH > 0) {
            OverlayLayout.resolveScreenRects(
                boxes, cropLeft, cropTop, screenshotW, screenshotH,
                group.displayW, group.displayH,
                group.themedCtx.resources.displayMetrics.density,
            )
        } else {
            emptyList()
        }

        // Per-box reconcile: reuse a window whose box still matches; create a
        // window for a genuinely new box; drop a window whose box is gone. A
        // change to one box must not churn the rest — that wholesale churn is
        // what made a single oscillating box reload the whole overlay.
        val unused = group.boxWindows.toMutableList()
        val next = ArrayList<BoxWindow>(boxes.size)
        var addedAny = false
        for (i in boxes.indices) {
            val box = boxes[i]
            val rect = rects.getOrNull(i) ?: continue
            val match = unused.firstOrNull { boxWindowMatches(it.box, box) }
            if (match != null) {
                unused.remove(match)
                reuseBoxWindow(group, match, box, rect)
                next.add(match)
            } else {
                val created = addBoxWindow(group, displayId, box, rect)
                if (created != null) {
                    next.add(created)
                    addedAny = true
                }
            }
        }
        for (bw in unused) overlayHost.removeOverlayWindow(bw.view)
        group.boxWindows.clear()
        group.boxWindows.addAll(next)
        return addedAny
    }

    /** Two boxes are "the same box" — a window reusable across a sync — when
     *  they share source text, orientation and furigana flag, and their OCR
     *  bounds agree within a tolerance (absorbs OCR jitter). */
    private fun boxWindowMatches(a: TextBox, b: TextBox): Boolean {
        if (a.isFurigana != b.isFurigana) return false
        if (a.orientation != b.orientation) return false
        if (a.sourceText != b.sourceText) return false
        val tol = 20
        return Math.abs(a.bounds.left - b.bounds.left) <= tol &&
            Math.abs(a.bounds.top - b.bounds.top) <= tol &&
            Math.abs(a.bounds.right - b.bounds.right) <= tol &&
            Math.abs(a.bounds.bottom - b.bounds.bottom) <= tol
    }

    /** Reuse an existing box window for [box]: re-render its content if the
     *  content changed, reposition/resize its window if the rect changed. */
    private fun reuseBoxWindow(
        group: TranslationOverlayGroup,
        bw: BoxWindow,
        box: TextBox,
        rect: RectF,
    ) {
        val contentChanged = bw.box.translatedText != box.translatedText ||
            bw.box.bgColor != box.bgColor ||
            bw.box.textColor != box.textColor ||
            bw.box.lineCount != box.lineCount
        bw.box = box
        val x = rect.left.toInt()
        val y = rect.top.toInt()
        val w = rect.width().toInt().coerceAtLeast(1)
        val h = rect.height().toInt().coerceAtLeast(1)
        val moved = bw.params.x != x || bw.params.y != y ||
            bw.params.width != w || bw.params.height != h
        if (contentChanged) bw.view.setBox(box)
        if (moved) {
            bw.params.x = x
            bw.params.y = y
            bw.params.width = w
            bw.params.height = h
            try { group.wm.updateViewLayout(bw.view, bw.params) } catch (_: Exception) {}
        }
    }

    /** Create and register one per-box overlay window. Returns null on failure. */
    private fun addBoxWindow(
        group: TranslationOverlayGroup,
        displayId: Int,
        box: TextBox,
        rect: RectF,
    ): BoxWindow? {
        val w = rect.width().toInt().coerceAtLeast(1)
        val h = rect.height().toInt().coerceAtLeast(1)
        val view = BoxOverlayView(group.themedCtx, pinholeMode = group.pinholeMode).apply {
            setBox(box)
            onTap = { CaptureService.instance?.dismissLiveOverlay(displayId) }
        }
        // MediaProjection box windows are touchable so they escape Android's
        // anti-tapjacking opacity cap; accessibility windows stay non-touchable
        // (passthrough preserved — cap-exempt by window type).
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        if (overlayHost.windowType != WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY) {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        val params = WindowManager.LayoutParams(
            w, h,
            overlayHost.windowType,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = rect.left.toInt()
            y = rect.top.toInt()
            fitInsetsTypes = 0
            windowAnimations = 0
        }
        return if (overlayHost.addOverlayWindow(view, group.wm, params, displayId)) {
            BoxWindow(view, params, box)
        } else {
            null
        }
    }

    /**
     * Remove every per-box window from [displayId]'s translation group but keep
     * the group (and its dirty view) alive — used when pinhole detection clears
     * stale clean boxes with no replacement coming.
     */
    fun clearTranslationBoxes(displayId: Int) {
        val group = translationOverlayGroups[displayId] ?: return
        for (bw in group.boxWindows) overlayHost.removeOverlayWindow(bw.view)
        group.boxWindows.clear()
    }

    /**
     * Blank (or restore) [displayId]'s dirty-flagged box windows via window
     * alpha. Pinhole detection hides dirty boxes from a raw capture so the
     * frame shows clean game pixels under them.
     */
    fun setDirtyBoxesHidden(displayId: Int, hidden: Boolean) {
        val group = translationOverlayGroups[displayId] ?: return
        val alpha = if (hidden) 0f else 1f
        for (bw in group.boxWindows) {
            if (!bw.box.dirty) continue
            if (bw.params.alpha == alpha) continue
            bw.params.alpha = alpha
            try { group.wm.updateViewLayout(bw.view, bw.params) } catch (_: Exception) {}
        }
    }

    /** Tear down a display's translation or furigana overlay. Idempotent. */
    fun hideTranslationOverlayForDisplay(displayId: Int) {
        furiganaOverlays.remove(displayId)?.let { overlayHost.removeOverlayWindow(it) }
        translationOverlayGroups.remove(displayId)?.let { group ->
            for (bw in group.boxWindows) overlayHost.removeOverlayWindow(bw.view)
        }
    }

    /** Tear down translation/furigana overlays across every display. */
    fun hideTranslationOverlay() {
        val ids = (furiganaOverlays.keys + translationOverlayGroups.keys).toSet().toList()
        for (id in ids) hideTranslationOverlayForDisplay(id)
    }

    /** Remove specific furigana boxes without rebuilding the whole overlay.
     *  Furigana-only — the per-box translation path reconciles via syncBoxWindows. */
    fun removeOverlayBoxes(
        toRemove: List<TextBox>,
        displayId: Int = CaptureService.instance?.primaryGameDisplayId()
            ?: android.view.Display.DEFAULT_DISPLAY,
    ) {
        furiganaOverlays[displayId]?.removeBoxesByContent(toRemove)
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

        val intro = SonarPingIntroView(displayCtx, edge)
        // Touchable (no FLAG_NOT_TOUCHABLE): the intro window absorbs taps
        // on the visible carrier so the underlying FloatingOverlayIcon
        // doesn't get tap-fired while the user thinks they're tapping the
        // animating intro. The view's onTouchEvent returns true, so taps
        // are consumed silently — no action, just "yes, I saw that".
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

        // Each intro removes its own overlay window on natural end — the
        // map slot is only nulled if it still points at THIS intro (a
        // newer one for the same display would have replaced it).
        intro.onAnimationEnd = {
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
        intro.onAnimationEnd = null
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
     *  reusable (scope intact). Used when the active backend is swapped. */
    fun hideAll() {
        hideTranslationOverlay()
        regionController.hideAll()
        dismissFloatingMenu()
        hideFloatingIcon("hideAll")
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
