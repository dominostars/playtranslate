package com.playtranslate

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.playtranslate.capture.CaptureBackendResolver
import com.playtranslate.language.HintTextKind
import com.playtranslate.language.SourceLanguageProfiles
import com.playtranslate.overlay.OverlayHost
import com.playtranslate.ui.DimController
import com.playtranslate.ui.DragLookupController
import com.playtranslate.ui.FloatingIconMenu
import com.playtranslate.ui.FloatingOverlayIcon
import com.playtranslate.ui.MagnifierLens
import com.playtranslate.ui.OverlayAlert
import com.playtranslate.ui.RegionDragView
import com.playtranslate.ui.TextBox
import com.playtranslate.ui.TranslationOverlayView
import com.playtranslate.ui.WordLookupPopup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private const val TAG = "OverlayUiController"

/**
 * Owns every game-screen overlay the app draws: the floating icon + menu, the
 * one-shot translation overlay, the region indicator / picker / editor, and the
 * no-text pill.
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
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())

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

    // ── Live translation overlay registry ────────────────────────────────

    private data class TranslationOverlayHandle(
        val main: TranslationOverlayView,
        val dirty: TranslationOverlayView,
    )

    private val translationOverlayHandles: MutableMap<Int, TranslationOverlayHandle> = mutableMapOf()

    /** Live translation overlay for [displayId], or null. */
    fun translationOverlayForDisplay(displayId: Int): TranslationOverlayView? =
        translationOverlayHandles[displayId]?.main

    /** Persistent dirty overlay for [displayId], or null. */
    fun dirtyOverlayForDisplay(displayId: Int): TranslationOverlayView? =
        translationOverlayHandles[displayId]?.dirty

    /** True iff any display has a live translation overlay registered. */
    val hasAnyTranslationOverlay: Boolean get() = translationOverlayHandles.isNotEmpty()

    // ── Overlay-specific mutable state ───────────────────────────────────

    private var dragView: RegionDragView? = null
    private var dragWm: WindowManager? = null

    /** True when the region drag editor overlay is showing. */
    val isRegionEditorActive: Boolean get() = dragView != null

    private var floatingMenu: FloatingIconMenu? = null
    private var floatingMenuWm: WindowManager? = null

    private var regionEditorBar: View? = null
    private var regionEditorBarWm: WindowManager? = null
    private var regionEditorLabel: View? = null
    private var regionEditorLabelWm: WindowManager? = null

    private var regionIndicatorView: View? = null
    private var regionIndicatorWm: WindowManager? = null
    private var regionIndicatorPersistent = false
    private var regionIndicatorDisplayId: Int = -1
    private var regionIndicatorUpdater: ((RegionEntry) -> Unit)? = null
    private val regionIndicatorHandler = Handler(Looper.getMainLooper())

    private var pillView: View? = null
    private var pillWm: WindowManager? = null
    private val pillHandler = Handler(Looper.getMainLooper())

    // ── Region overlay (region picker preview) ───────────────────────────

    /**
     * Shows a persistent region indicator on the game display. When an
     * indicator is already up for the same display, the existing window is
     * reused and just redrawn — see [updateRegionOverlay] — so flipping
     * between regions in the picker doesn't tear down the surface and flash.
     */
    fun showRegionOverlay(display: Display, region: RegionEntry) {
        if (anyIconInDragMode()) return

        val canUpdateInPlace = !region.isFullScreen &&
            regionIndicatorPersistent &&
            regionIndicatorDisplayId == display.displayId &&
            regionIndicatorUpdater != null
        if (canUpdateInPlace) {
            updateRegionOverlay(region)
            return
        }

        showRegionIndicator(display, region, persistent = true)
        // Re-add this display's floating icon so it draws above the full-
        // screen dim overlay just placed on this display.
        bringFloatingIconsToFront(display.displayId)
    }

    /** Updates the existing persistent indicator's region in place. No-op if
     *  no persistent indicator is currently shown. */
    fun updateRegionOverlay(region: RegionEntry) {
        regionIndicatorUpdater?.invoke(region)
    }

    fun hideRegionOverlay() {
        hideRegionIndicator(force = true)
    }

    // ── Capture region indicator (brief flash) ───────────────────────────

    /**
     * Briefly flashes the capture region on the game display with a white
     * border and "Capturing (label)" text. Auto-dismisses with a fade-out.
     * Call [hideRegionIndicator] to force-remove instantly.
     */
    fun showRegionIndicator(
        display: Display,
        region: RegionEntry,
        persistent: Boolean = false
    ) {
        hideRegionIndicator(force = true)

        // Skip for full-screen regions
        if (region.isFullScreen) return

        val ctx = context.createDisplayContext(display)
        val wm = ctx.getSystemService(WindowManager::class.java) ?: return
        val dp = ctx.resources.displayMetrics.density
        val displayLabel = region.label

        val themed = overlayThemedContext(context)
        val accentColor = themed.themeColor(R.attr.ptAccent)
        val bgColor = themed.themeColor(R.attr.ptBg)

        val view = object : View(ctx) {
            // Mutable so the persistent indicator can swap regions in place without
            // tearing down the overlay window (which causes a 1-2 frame flash).
            var liveRegion: RegionEntry = region
            var liveLabel: String = displayLabel

            private val dimPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.argb(200, 0, 0, 0)
                style = android.graphics.Paint.Style.FILL
            }
            private val borderPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = accentColor
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 2f * dp
            }
            private val glowPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.argb(26,
                    android.graphics.Color.red(accentColor),
                    android.graphics.Color.green(accentColor),
                    android.graphics.Color.blue(accentColor))
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 12f * dp
                maskFilter = android.graphics.BlurMaskFilter(14f * dp, android.graphics.BlurMaskFilter.Blur.NORMAL)
            }
            private val regionShadowPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.argb(110, 0, 0, 0)
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 13f * dp
                maskFilter = android.graphics.BlurMaskFilter(13f * dp, android.graphics.BlurMaskFilter.Blur.NORMAL)
            }
            private val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = bgColor
                textSize = 12f * dp
                textAlign = android.graphics.Paint.Align.CENTER
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
            }
            private val labelBgPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = accentColor
                style = android.graphics.Paint.Style.FILL
                setShadowLayer(7.8f * dp, 0f, 2.6f * dp, android.graphics.Color.argb(140, 0, 0, 0))
            }
            private val labelPadH = 10f * dp
            private val labelPadV = 4f * dp
            private val labelRadius = 6f * dp
            private val labelMargin = 8f * dp

            init {
                // BlurMaskFilter requires software rendering
                setLayerType(LAYER_TYPE_SOFTWARE, null)
            }

            override fun onDraw(canvas: android.graphics.Canvas) {
                val w = width.toFloat()
                val h = height.toFloat()
                val l = w * liveRegion.left
                val t = h * liveRegion.top
                val r = w * liveRegion.right
                val b = h * liveRegion.bottom

                // Persistent indicator (region picker): darken outside the region.
                // Flash indicator: leave background untouched.
                if (persistent) {
                    if (t > 0f) canvas.drawRect(0f, 0f, w, t, dimPaint)
                    if (b < h) canvas.drawRect(0f, b, w, h, dimPaint)
                    if (l > 0f) canvas.drawRect(0f, t, l, b, dimPaint)
                    if (r < w) canvas.drawRect(r, t, w, b, dimPaint)
                }

                // Outside-only shadow + accent glow
                canvas.save()
                canvas.clipRect(l, t, r, b, android.graphics.Region.Op.DIFFERENCE)
                val shadowOffset = regionShadowPaint.strokeWidth / 2f
                canvas.drawRect(l - shadowOffset, t - shadowOffset, r + shadowOffset, b + shadowOffset, regionShadowPaint)
                val glowOffset = glowPaint.strokeWidth / 2f
                canvas.drawRect(l - glowOffset, t - glowOffset, r + glowOffset, b + glowOffset, glowPaint)
                canvas.restore()

                // Accent border outside the capture region
                val half = borderPaint.strokeWidth / 2f
                canvas.drawRect(l - half, t - half, r + half, b + half, borderPaint)

                // Label with accent background, centered above (or below) the region
                val cx = (l + r) / 2f
                val textW = textPaint.measureText(liveLabel)
                val textH = textPaint.descent() - textPaint.ascent()
                val pillW = textW + labelPadH * 2
                val pillH = textH + labelPadV * 2

                val aboveY = t - labelMargin - pillH
                val labelTop = if (aboveY >= 0) aboveY else b + labelMargin
                val labelBottom = labelTop + pillH

                // Pill background
                canvas.drawRoundRect(
                    cx - pillW / 2, labelTop, cx + pillW / 2, labelBottom,
                    labelRadius, labelRadius, labelBgPaint
                )

                // Label text
                val textY = labelTop + labelPadV - textPaint.ascent()
                canvas.drawText(liveLabel, cx, textY, textPaint)
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        overlayHost.addOverlayWindow(view, wm, params, display.displayId)
        regionIndicatorView = view
        regionIndicatorWm = wm
        regionIndicatorPersistent = persistent
        regionIndicatorDisplayId = display.displayId
        regionIndicatorUpdater = if (persistent) {
            { newRegion ->
                if (newRegion.isFullScreen) {
                    hideRegionIndicator(force = true)
                } else {
                    view.liveRegion = newRegion
                    view.liveLabel = newRegion.label
                    view.invalidate()
                }
            }
        } else null

        if (!persistent) {
            // Brief flash, then quick fade out
            regionIndicatorHandler.postDelayed({
                view.animate()
                    .alpha(0f)
                    .setDuration(600L)
                    .withEndAction { hideRegionIndicator(force = true) }
                    .start()
            }, 400L)  // 400ms solid + 600ms fade
        }
    }

    /**
     * Hides the region indicator. If [force] is false, persistent indicators
     * (from the region picker) are left alone — only flash indicators are cleared.
     */
    fun hideRegionIndicator(force: Boolean = false) {
        if (!force && regionIndicatorPersistent) return
        regionIndicatorHandler.removeCallbacksAndMessages(null)
        val view = regionIndicatorView
        if (view != null) {
            view.animate().cancel()
            view.visibility = View.INVISIBLE
            overlayHost.removeOverlayWindow(view)
        }
        regionIndicatorView = null
        regionIndicatorWm = null
        regionIndicatorPersistent = false
        regionIndicatorDisplayId = -1
        regionIndicatorUpdater = null
    }

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
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
            y = (40 * dp).toInt()
        }

        overlayHost.addOverlayWindow(view, wm, params, display.displayId)
        pillView = view
        pillWm = wm

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
        pillWm = null
    }

    // ── Region drag overlay ──────────────────────────────────────────────

    fun showRegionDragOverlay(
        display: Display,
        initRegion: RegionEntry = RegionEntry("", 0.25f, 0.75f, 0.25f, 0.75f),
        onRegionChanged: (RegionEntry) -> Unit
    ) {
        hideRegionDragOverlay()
        val wm = context.createDisplayContext(display).getSystemService(WindowManager::class.java) ?: return
        val view = RegionDragView(context.createDisplayContext(display)).apply {
            setRegion(initRegion.top, initRegion.bottom, initRegion.left, initRegion.right)
            this.onRegionChanged = onRegionChanged
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        overlayHost.addOverlayWindow(view, wm, params, display.displayId)
        dragWm = wm
        dragView = view
    }

    fun hideRegionDragOverlay() {
        dragView?.let { overlayHost.removeOverlayWindow(it) }
        dragView = null
        dragWm = null
    }

    fun getDragRegion(): RegionEntry {
        val v = dragView ?: return RegionEntry("", 0.25f, 0.75f, 0.25f, 0.75f)
        return RegionEntry("", v.topFraction, v.bottomFraction, v.leftFraction, v.rightFraction)
    }

    // ── Live translation overlay ─────────────────────────────────────────

    fun showTranslationOverlay(
        display: Display,
        boxes: List<TextBox>,
        cropLeft: Int, cropTop: Int,
        screenshotW: Int, screenshotH: Int,
        pinholeMode: Boolean = false
    ) {
        // Overlay is appearing — dismiss loading spinner across all icons.
        setIconsLoading(false)

        val displayId = display.displayId
        // Reuse existing view only if it's on the same display AND was
        // constructed with the same pinhole mode; otherwise recreate.
        val existing = translationOverlayHandles[displayId]?.main
        if (existing != null && existing.pinholeMode == pinholeMode) {
            existing.setBoxes(boxes, cropLeft, cropTop, screenshotW, screenshotH)
            return
        }
        hideTranslationOverlayForDisplay(displayId)
        val displayCtx = context.createDisplayContext(display)
        val themedCtx = android.view.ContextThemeWrapper(displayCtx, android.R.style.Theme_DeviceDefault)
        val wm = displayCtx.getSystemService(WindowManager::class.java) ?: return
        val view = TranslationOverlayView(themedCtx, pinholeMode = pinholeMode).apply {
            setBoxes(boxes, cropLeft, cropTop, screenshotW, screenshotH)
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { windowAnimations = 0 }
        overlayHost.addOverlayWindow(view, wm, params, displayId)

        // Create persistent dirty overlay window (always present, empty when not dirty).
        val dirtyView = TranslationOverlayView(themedCtx, pinholeMode = true)
        val dirtyParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { windowAnimations = 0 }
        overlayHost.addOverlayWindow(dirtyView, wm, dirtyParams, displayId)

        translationOverlayHandles[displayId] = TranslationOverlayHandle(view, dirtyView)
    }

    /** Tear down a single display's translation + dirty overlay pair.
     *  Idempotent. */
    fun hideTranslationOverlayForDisplay(displayId: Int) {
        val handle = translationOverlayHandles.remove(displayId) ?: return
        overlayHost.removeOverlayWindow(handle.main)
        overlayHost.removeOverlayWindow(handle.dirty)
    }

    /** Tear down translation overlays across every display. */
    fun hideTranslationOverlay() {
        if (translationOverlayHandles.isEmpty()) return
        val ids = translationOverlayHandles.keys.toList()
        for (id in ids) hideTranslationOverlayForDisplay(id)
    }

    /** Remove specific overlay boxes from [displayId]'s overlay without
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
        if (!prefs.showOverlayIcon) {
            hideFloatingIcon("pref_disabled")
            return
        }
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val target = prefs.captureDisplayIds

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
            compactMode = prefs.compactOverlayIcon
        }

        val params = WindowManager.LayoutParams(
            icon.viewSizePx, icon.viewSizePx,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
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
            magnifier = magnifier
        )
        // Track whether live mode / region overlay were active when drag started
        var liveWasPausedForPopup = false
        var overlayHiddenForDrag = false
        val clearLivePauseFlag: () -> Unit = { liveWasPausedForPopup = false }

        fun restoreRegionOverlay() {
            if (overlayHiddenForDrag) {
                overlayHiddenForDrag = false
                regionIndicatorView?.visibility = View.VISIBLE
            }
        }

        fun resumeLiveMode() {
            if (liveWasPausedForPopup) {
                liveWasPausedForPopup = false
                val effectivelySingleScreen = Prefs.isSingleScreen(context) || !MainActivity.isInForeground
                if (effectivelySingleScreen) {
                    toggleLiveDirect(true)
                } else {
                    sendMainActivityIntent(MainActivity.ACTION_START_LIVE)
                }
            }
        }

        controller.onSettled = {
            restoreRegionOverlay()
            resumeLiveMode()
        }
        icon.onDragStart = {
            // Hide region preview so the user can see game text while dragging
            if (regionIndicatorView?.visibility == View.VISIBLE) {
                overlayHiddenForDrag = true
                regionIndicatorView?.visibility = View.INVISIBLE
            }
            // Pause live mode while dragging for definitions
            if (CaptureService.instance?.isLive == true) {
                liveWasPausedForPopup = true
                val effectivelySingleScreen = Prefs.isSingleScreen(context) || !MainActivity.isInForeground
                if (effectivelySingleScreen) {
                    toggleLiveDirect(false)
                } else {
                    sendMainActivityIntent(MainActivity.ACTION_STOP_LIVE)
                }
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
        } else {
            controller.destroy()
        }

        CaptureService.instance?.updateForegroundState()
        CaptureService.instance?.syncIconState()
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
            if (handle.icon.inDragMode) continue
            val params = handle.icon.params ?: continue
            overlayHost.removeOverlayWindow(handle.icon)
            overlayHost.addOverlayWindow(handle.icon, handle.wm, params, id)
        }
    }

    private fun hideFloatingIconForDisplay(displayId: Int, reason: String) {
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
        val effectivelySingleScreen = Prefs.isSingleScreen(context) || !MainActivity.isInForeground
        if (effectivelySingleScreen) {
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
                val effectivelySingleScreen = Prefs.isSingleScreen(context) || !MainActivity.isInForeground
                if (effectivelySingleScreen) {
                    toggleLiveDirect(false)
                } else {
                    sendMainActivityIntent(MainActivity.ACTION_STOP_LIVE)
                }
            } else {
                if (Prefs.shouldUseInAppOnlyMode(context)) {
                    sendMainActivityIntent(MainActivity.ACTION_START_LIVE)
                } else {
                    val effectivelySingleScreen = Prefs.isSingleScreen(context) || !MainActivity.isInForeground
                    if (effectivelySingleScreen) {
                        toggleLiveDirect(true)
                    } else {
                        sendMainActivityIntent(MainActivity.ACTION_START_LIVE)
                    }
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
                val effectivelySingleScreen = Prefs.isSingleScreen(context) || !MainActivity.isInForeground
                if (effectivelySingleScreen) {
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
            val effectivelySingleScreen = Prefs.isSingleScreen(context) || !MainActivity.isInForeground
            if (effectivelySingleScreen) {
                showRegionEditor(display)
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
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        overlayHost.addOverlayWindow(menu, wm, params, display.displayId)
        floatingMenuWm = wm
        floatingMenu = menu

        val p = icon.params
        val iconCx = (p?.x ?: 0) + icon.viewSizePx / 2
        val iconCy = (p?.y ?: 0) + icon.viewSizePx / 2
        menu.positionNearIcon(iconCx, iconCy, icon.currentEdge, screenSize.x, screenSize.y)
    }

    fun dismissFloatingMenu() {
        val wasShowing = floatingMenu != null
        floatingMenu?.let { overlayHost.removeOverlayWindow(it) }
        floatingMenu = null
        floatingMenuWm = null
        if (wasShowing) {
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
        val prefs = Prefs(context)
        val alreadyCompact = prefs.compactOverlayIcon

        val builder = OverlayAlert.Builder(displayCtx, overlayWm, display.displayId)

        if (!Prefs.hasMultipleDisplays(context)) {
            builder.setTitle("Disable $appName?")
                .setMessage("Re-enable in $appName app")
            if (!alreadyCompact) {
                builder.addButton("Minimize Icon", accentColor) {
                    prefs.compactOverlayIcon = true
                    hideFloatingIcon("confirm_minimize_single")
                    reconcileFloatingIcons()
                }
            }
            builder.addButton("Turn Off", dividerColor, dangerColor) {
                    PlayTranslateAccessibilityService.disable(context, "confirm_turn_off_single")
                }
                .addCancelButton()
        } else {
            builder.setTitle("Hide $appName game screen controls?")
            if (!alreadyCompact) {
                builder.setMessage("“Minimize Icon” shrinks the floating icon. “Turn Off” disables it until re-enabled in settings.")
                    .addButton("Minimize Icon", accentColor) {
                        prefs.compactOverlayIcon = true
                        hideFloatingIcon("confirm_minimize_multi")
                        reconcileFloatingIcons()
                    }
            } else {
                builder.setMessage("“Hide for Now” brings it back next time you open $appName. “Turn Off” disables it until re-enabled in settings.")
                    .addButton("Hide for Now", accentColor) {
                        hideFloatingIcon("confirm_hide_for_now")
                    }
            }
            builder.addButton("Turn Off", dividerColor, dangerColor) {
                    PlayTranslateAccessibilityService.disable(context, "confirm_turn_off_multi")
                }
                .addCancelButton()
        }

        builder.show()
    }

    // ── Single-screen region editor ──────────────────────────────────────

    private fun showRegionEditor(display: Display) {
        hideRegionEditor()
        CaptureService.instance?.holdActive = true
        hideTranslationOverlay()
        hideRegionOverlay()

        val currentRegion = CaptureService.instance?.activeRegionForDisplay(display.displayId)
        val initRegion = if (currentRegion == null || currentRegion.isFullScreen)
            RegionEntry("", 0.25f, 0.75f, 0.25f, 0.75f) else currentRegion

        showRegionDragOverlay(display, initRegion) { _ -> }
        dragView?.onDragStart = {
            regionEditorBar?.visibility = View.INVISIBLE
            regionEditorLabel?.visibility = View.INVISIBLE
        }
        dragView?.onDragEnd = {
            regionEditorBar?.visibility = View.VISIBLE
            regionEditorLabel?.visibility = View.VISIBLE
        }

        val ctx = context.createDisplayContext(display)
        val wm = ctx.getSystemService(WindowManager::class.java) ?: return
        val dp = ctx.resources.displayMetrics.density
        val btnSize = (48 * dp).toInt()
        val barPad = (12 * dp).toInt()
        val gap = (16 * dp).toInt()

        val themed = overlayThemedContext(ctx)
        val surfaceColor = themed.themeColor(R.attr.ptSurface)
        val cardColor = themed.themeColor(R.attr.ptCard)
        val dividerColor = themed.themeColor(R.attr.ptDivider)
        val accentColorBtn = themed.themeColor(R.attr.ptAccent)
        val accentOnColor = themed.themeColor(R.attr.ptAccentOn)
        val textColor = themed.themeColor(R.attr.ptText)
        val surfaceAlpha = android.graphics.Color.argb(230,
            android.graphics.Color.red(surfaceColor),
            android.graphics.Color.green(surfaceColor),
            android.graphics.Color.blue(surfaceColor))
        val btnRadius = 16 * dp

        val bar = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(barPad * 2 - (9 * dp).toInt(), barPad, barPad * 2 - (9 * dp).toInt(), barPad)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(surfaceAlpha)
                setStroke((1 * dp).toInt(), dividerColor)
                cornerRadius = 22 * dp
            }
        }

        val cancelBtn = android.widget.TextView(ctx).apply {
            text = "✕"
            setTextColor(textColor)
            textSize = 22f
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(cardColor)
                cornerRadius = btnRadius
            }
            layoutParams = android.widget.LinearLayout.LayoutParams(btnSize, btnSize).apply {
                marginEnd = gap
            }
            setOnClickListener {
                hideRegionEditor()
                if (CaptureService.instance?.isLive == true) {
                    CaptureService.instance?.refreshLiveOverlay()
                }
            }
        }

        val useBtn = android.widget.TextView(ctx).apply {
            text = "✓"
            setTextColor(accentOnColor)
            textSize = 22f
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(accentColorBtn)
                cornerRadius = btnRadius
            }
            layoutParams = android.widget.LinearLayout.LayoutParams(btnSize, btnSize)
            setOnClickListener {
                val dv = dragView ?: return@setOnClickListener
                val drawnRegion = RegionEntry("Drawn Region", dv.topFraction, dv.bottomFraction, dv.leftFraction, dv.rightFraction)
                hideRegionEditor()
                CaptureService.instance?.configureOverride(display.displayId, drawnRegion)
                if (CaptureService.instance?.isLive == true) {
                    CaptureService.instance?.refreshLiveOverlay()
                } else {
                    handleRegionSelection(display.displayId, drawnRegion)
                }
            }
        }

        bar.addView(cancelBtn)
        bar.addView(useBtn)

        val barParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (32 * dp).toInt()
        }

        overlayHost.addOverlayWindow(bar, wm, barParams, display.displayId)
        regionEditorBarWm = wm
        regionEditorBar = bar

        val label = android.widget.TextView(ctx).apply {
            text = "Drag edges to restrict screen captures to this region"
            setTextColor(textColor)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (12 * dp).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(surfaceAlpha)
                setStroke((1 * dp).toInt(), dividerColor)
                cornerRadius = 100 * dp
            }
        }
        val screenW = getDisplaySize(display).x
        val maxLabelW = screenW - (32 * dp).toInt()
        label.setSingleLine(true)
        label.measure(
            View.MeasureSpec.makeMeasureSpec(maxLabelW, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val labelParams = WindowManager.LayoutParams(
            label.measuredWidth,
            label.measuredHeight,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (16 * dp).toInt()
        }
        overlayHost.addOverlayWindow(label, wm, labelParams, display.displayId)
        regionEditorLabelWm = wm
        regionEditorLabel = label
    }

    fun hideRegionEditor() {
        hideRegionDragOverlay()
        regionEditorBar?.let { overlayHost.removeOverlayWindow(it) }
        regionEditorBar = null
        regionEditorBarWm = null
        regionEditorLabel?.let { overlayHost.removeOverlayWindow(it) }
        regionEditorLabel = null
        regionEditorLabelWm = null
        CaptureService.instance?.holdActive = false
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
        val effectivelySingleScreen = Prefs.isSingleScreen(context) || !MainActivity.isInForeground
        if (effectivelySingleScreen) {
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

    /** Fallback display for [reconcileFloatingIcons] when the selection is empty. */
    private fun findIconDisplay(prefs: Prefs): Display? {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val displays = dm.displays
        if (displays.size <= 1) return displays.firstOrNull()
        val primaryId = prefs.captureDisplayIds.firstOrNull()
            ?: Display.DEFAULT_DISPLAY
        return dm.getDisplay(primaryId) ?: displays.firstOrNull()
    }

    @Suppress("DEPRECATION")
    private fun getDisplaySize(display: Display): Point {
        val size = Point()
        display.getRealSize(size)
        return size
    }

    /** Hide every overlay this controller owns, keeping the controller
     *  reusable (scope intact). Used when the active backend is swapped. */
    fun hideAll() {
        hideTranslationOverlay()
        hideRegionOverlay()
        hideRegionIndicator(force = true)
        hideRegionEditor()
        hideRegionDragOverlay()
        dismissFloatingMenu()
        hideFloatingIcon("hideAll")
    }

    /** Full teardown — [hideAll] plus scope/handler cancellation. For host
     *  death (accessibility-service unbind). */
    fun destroy() {
        hideAll()
        regionIndicatorHandler.removeCallbacksAndMessages(null)
        pillHandler.removeCallbacksAndMessages(null)
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
    }
}
