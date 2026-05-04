package com.playtranslate

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.playtranslate.ui.DimController
import com.playtranslate.ui.OverlayAlert
import com.playtranslate.ui.DragLookupController
import com.playtranslate.language.HintTextKind
import com.playtranslate.language.SourceLanguageProfiles
import com.playtranslate.ui.FloatingIconMenu
import com.playtranslate.ui.FloatingOverlayIcon
import com.playtranslate.ui.MagnifierLens
import com.playtranslate.ui.OcrDebugOverlayView
import com.playtranslate.ui.RegionDragView
import com.playtranslate.ui.TranslationOverlayView
import com.playtranslate.ui.WordLookupPopup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Minimal AccessibilityService whose only purpose is to call
 * [takeScreenshot] on a specific display.
 *
 * WHY AN ACCESSIBILITY SERVICE?
 * ─────────────────────────────
 * MediaProjection captures the display the requesting Activity runs on.
 * Launching a bridge Activity on the game display caused the whole app
 * to move there. AccessibilityService.takeScreenshot(displayId) captures
 * any display by ID with no UI, no focus change, and no app relocation.
 *
 * SETUP (one-time)
 * ─────────────────
 * Settings → Accessibility → Installed apps → PlayTranslate → Enable
 * The app detects the enabled state via [isEnabled].
 */
class PlayTranslateAccessibilityService : AccessibilityService() {

    private var dragView: RegionDragView? = null
    private var dragWm: WindowManager? = null
    /** True when the region drag editor overlay is showing. */
    val isRegionEditorActive: Boolean get() = dragView != null
    /**
     * Per-display floating-icon bundle. The icon, its WindowManager, the
     * drag-lookup controller, and the live-pause clear-flag closure all
     * lifecycle together — they're co-installed in [installFloatingIconForDisplay]
     * and co-torn-down in [hideFloatingIconForDisplay]. Bundling enforces the
     * "all four exist together or none do" invariant — previously maintained
     * by convention across four parallel maps.
     *
     * The pre-multi-display single-icon setter used to fan out to
     * [CaptureService.updateForegroundState] + [CaptureService.syncIconState]
     * on every install/teardown; those calls now live inside the install and
     * remove functions so per-display add/remove still triggers them.
     *
     * `clearLivePauseFlag` closes over the install-time `liveWasPausedForPopup`
     * local in [installFloatingIconForDisplay]. Invoked via
     * [cancelLivePauseObligation] so the chained `resumeLiveMode` in the
     * magnifier-dismiss callback becomes a no-op in single-screen — the user
     * re-enables live mode manually after closing the detail view.
     */
    private data class FloatingIconHandle(
        val icon: FloatingOverlayIcon,
        val wm: WindowManager,
        val dragController: DragLookupController,
        val clearLivePauseFlag: () -> Unit,
    )

    private val iconHandles: MutableMap<Int, FloatingIconHandle> = mutableMapOf()

    /** True iff at least one floating icon is currently registered. */
    val hasAnyFloatingIcon: Boolean get() = iconHandles.isNotEmpty()

    /** Set [FloatingOverlayIcon.showLoading] across every registered icon.
     *  P2 propagates this globally; P5's one-shot fan-out scopes it to the
     *  display whose icon initiated the hold. */
    fun setIconsLoading(loading: Boolean) {
        iconHandles.values.forEach { it.icon.showLoading = loading }
    }

    /** Set [FloatingOverlayIcon.degraded] across every registered icon. */
    fun setIconsDegraded(degraded: Boolean) {
        iconHandles.values.forEach { it.icon.degraded = degraded }
    }

    /** Set [FloatingOverlayIcon.liveMode] across every registered icon. */
    fun setIconsLiveMode(liveMode: Boolean) {
        iconHandles.values.forEach { it.icon.liveMode = liveMode }
    }

    /** Returns true when any floating icon is mid-drag. */
    fun anyIconInDragMode(): Boolean = iconHandles.values.any { it.icon.inDragMode }

    /** Dismiss the drag-lookup popup on every display (only one is realistically
     *  active since the user has one pointer). */
    fun dismissAllDragLookupPopups() {
        iconHandles.values.forEach { it.dragController.dismiss() }
    }

    /** True if any drag-lookup popup is showing across all displays. */
    val isAnyDragLookupPopupShowing: Boolean
        get() = iconHandles.values.any { it.dragController.isPopupShowing }
    private var floatingMenu: FloatingIconMenu? = null
    private var floatingMenuWm: WindowManager? = null
    private var debugOverlayView: OcrDebugOverlayView? = null
    private var debugOverlayWm: WindowManager? = null
    /**
     * Per-display live translation overlay pair. The dirty overlay is the
     * main overlay's persistent companion (always present, paints empty when
     * not dirty); they're co-created in [showTranslationOverlay] and
     * co-torn-down in [hideTranslationOverlayForDisplay]. Bundling enforces
     * the "exist together or not at all" invariant — previously maintained
     * by convention across four parallel maps.
     */
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
    /** Per-display touch sentinels. With multiple selected displays each
     *  hosting a floating icon, each display needs its own sentinel — the
     *  pre-P5 single-instance setup left only the first display's touches
     *  detectable, so a touch on display B never reached
     *  [lastInteractedDisplayId] tracking and B's hotkey routing was broken. */
    private val touchSentinels: MutableMap<Int, View> = mutableMapOf()
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
    private val debugOcrManager get() = OcrManager.instance
    private val debugHandler = Handler(Looper.getMainLooper())
    private var debugRunning = false
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Centralized screenshot manager — all takeScreenshot calls go through here. */
    var screenshotManager: ScreenshotManager? = null
        private set

    /** Repositions floating icons when display properties change (e.g. rotation). */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            val handle = iconHandles[displayId] ?: return
            val p = handle.icon.params ?: return
            val prefs = Prefs(this@PlayTranslateAccessibilityService)
            val pos = prefs.iconPositionForDisplay(displayId)
            handle.icon.setPosition(pos.edge, pos.fraction)
            try { handle.wm.updateViewLayout(handle.icon, p) } catch (_: Exception) {}
        }
    }

    /** Stops live mode when the screen turns off. */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) {
                CaptureService.instance?.let { if (it.isLive) it.stopLive() }
                hideTranslationOverlay()
            }
        }
    }

    override fun onServiceConnected() {
        Log.i(TAG, "onServiceConnected")
        instance = this
        screenshotManager = ScreenshotManager(this)
        serviceInfo = serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
            .registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        reconcileFloatingIcons()
        registerHotkeyCallbacks()
    }

    /** Wire hotkey callbacks to CaptureService. Safe to call multiple times. */
    fun registerHotkeyCallbacks() {
        val svc = CaptureService.instance ?: return
        onHotkeyActivated = { mode -> svc.hotkeyHoldStart(mode) }
        onHotkeyReleased = { svc.hotkeyHoldEnd() }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.w(TAG, "onUnbind: tearing down overlays and cancelling scope", Throwable("onUnbind callsite"))
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
            .unregisterDisplayListener(displayListener)
        stopInputMonitoring()
        stopDebugOcrLoop()
        hideTranslationOverlay()
        hideRegionOverlay()
        hideRegionIndicator(force = true)
        hideRegionEditor()
        hideRegionDragOverlay()
        dismissFloatingMenu()
        hideFloatingIcon("unbind")
        screenshotManager?.destroy()
        screenshotManager = null
        serviceScope.cancel()
        instance = null
        return super.onUnbind(intent)
    }

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

    // ── Overlay window registry ──────────────────────────────────────────
    //
    // Every accessibility-overlay window the service owns goes through
    // [addOverlayWindow] / [removeOverlayWindow]. [prepareForCleanCapture]
    // walks the registry and blanks each window via window-level alpha so
    // none of them appear in the captured frame. This replaces a previous
    // patchwork of per-overlay flags + View.alpha tweaks that left newly
    // added windows (e.g. the magnifier) silently in the screenshot.

    /** Registered overlay window. Stored handle keeps the wm + params so
     *  blanking can flip [WindowManager.LayoutParams.alpha] and call
     *  [WindowManager.updateViewLayout] without each call site managing
     *  its own state. */
    data class OverlayHandle(
        val view: View,
        val wm: WindowManager,
        val params: WindowManager.LayoutParams,
        val displayId: Int,
    )

    /** Main-thread only — every WindowManager mutation happens on Main. */
    private val overlayWindows = mutableListOf<OverlayHandle>()

    /**
     * Add a window via [WindowManager.addView] AND register it for
     * clean-capture blanking. Returns true on success.
     *
     * Honors whatever [WindowManager.LayoutParams.alpha] is on [params].
     * In particular, [bringFloatingIconsToFront] removes and re-adds the
     * icon with the same params object — if a clean capture has the icon
     * blanked at alpha=0, the re-added window stays invisible until the
     * capture's restore fires. Forcing alpha=1 here would flash the icon
     * mid-capture and contaminate the bitmap.
     */
    fun addOverlayWindow(
        view: View,
        wm: WindowManager,
        params: WindowManager.LayoutParams,
        displayId: Int,
    ): Boolean {
        return try {
            wm.addView(view, params)
            overlayWindows += OverlayHandle(view, wm, params, displayId)
            true
        } catch (e: Exception) {
            Log.w(TAG, "addOverlayWindow failed: ${e.message}")
            false
        }
    }

    /** Unregister and call [WindowManager.removeView]. Returns true if the
     *  view was registered (and thus removed). Returns false if the view was
     *  never registered — the static [removeOverlay] uses this to fall back
     *  to a direct removeView for windows that were added before the service
     *  connected. */
    fun removeOverlayWindow(view: View): Boolean {
        val handle = overlayWindows.firstOrNull { it.view === view } ?: return false
        overlayWindows -= handle
        try { handle.wm.removeView(view) } catch (_: Exception) {}
        return true
    }

    /** Opaque snapshot returned by [prepareForCleanCapture]. */
    class OverlayState internal constructor(
        internal val saved: List<OverlayHandle>
    )

    /**
     * Hide every registered overlay on [displayId] so it doesn't appear
     * in a screenshot of that display. Overlays on other displays are left
     * alone — blanking them would flicker every cycle when N displays are
     * being captured in turn.
     *
     * Uses [WindowManager.LayoutParams.alpha] (window-level, applied by
     * SurfaceFlinger during composition) rather than [View.alpha] (applied
     * during view drawing, which can lag a frame behind). Combined with
     * the 2-vsync wait in [ScreenshotManager.requestClean], this reliably
     * composites the overlay-free frame before capture.
     *
     * Skips handles already at alpha=0 — they belong to a concurrent
     * in-flight capture that hasn't restored yet. Including them would
     * let our restore re-show overlays that another capture still needs
     * hidden. The try/finally in [ScreenshotManager.requestClean] is what
     * guarantees restore on cancellation/exception, so we don't need
     * self-healing here.
     */
    fun prepareForCleanCapture(displayId: Int): OverlayState {
        val saved = mutableListOf<OverlayHandle>()
        for (handle in overlayWindows) {
            if (handle.displayId != displayId) continue
            if (handle.params.alpha == 0f) continue
            handle.params.alpha = 0f
            try {
                handle.wm.updateViewLayout(handle.view, handle.params)
                saved += handle
            } catch (_: Exception) {
                handle.params.alpha = 1f
            }
        }
        return OverlayState(saved)
    }

    /** Restores blanked overlays. The "intended visible alpha" is always 1
     *  in this app — no overlay uses a non-1 alpha intentionally — so we
     *  can write 1 unconditionally and recover from missed prior restores. */
    fun restoreAfterCapture(state: OverlayState) {
        for (handle in state.saved) {
            handle.params.alpha = 1f
            try { handle.wm.updateViewLayout(handle.view, handle.params) } catch (_: Exception) {}
        }
    }

    // ── Capture region indicator (brief flash) ───────────────────────────

    /**
     * Briefly flashes the capture region on the game display with a white
     * border and "Capturing (label)" text. Auto-dismisses after [durationMs]
     * with a fade-out. Call [hideRegionIndicator] to force-remove instantly
     * (e.g. before taking another screenshot).
     */
    fun showRegionIndicator(
        display: Display,
        region: RegionEntry,
        persistent: Boolean = false
    ) {
        hideRegionIndicator(force = true)

        // Skip for full-screen regions
        if (region.isFullScreen) return

        val ctx = createDisplayContext(display)
        val wm = ctx.getSystemService(WindowManager::class.java) ?: return
        val dp = ctx.resources.displayMetrics.density
        val displayLabel = region.label

        val accentColor = OverlayColors.accent(this)
        val bgColor = OverlayColors.bg(this)

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
        addOverlayWindow(view, wm, params, display.displayId)
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
            removeOverlayWindow(view)
        }
        regionIndicatorView = null
        regionIndicatorWm = null
        regionIndicatorPersistent = false
        regionIndicatorDisplayId = -1
        regionIndicatorUpdater = null
    }

    // ── No-text pill toast ────────────────────────────────────────────────

    private var pillView: View? = null
    private var pillWm: WindowManager? = null
    private val pillHandler = Handler(Looper.getMainLooper())

    /**
     * Shows a brief pill-shaped overlay near the top of the game display
     * with the app icon and [message]. Auto-dismisses with a fade-out.
     */
    fun showNoTextPill(display: Display, message: String) {
        hideNoTextPill()

        val ctx = createDisplayContext(display)
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

        addOverlayWindow(view, wm, params, display.displayId)
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
            removeOverlayWindow(view)
        }
        pillView = null
        pillWm = null
    }

    fun showRegionDragOverlay(
        display: Display,
        initRegion: RegionEntry = RegionEntry("", 0.25f, 0.75f, 0.25f, 0.75f),
        onRegionChanged: (RegionEntry) -> Unit
    ) {
        hideRegionDragOverlay()
        val wm = createDisplayContext(display).getSystemService(WindowManager::class.java) ?: return
        val view = RegionDragView(this).apply {
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
        addOverlayWindow(view, wm, params, display.displayId)
        dragWm = wm
        dragView = view
    }

    fun hideRegionDragOverlay() {
        dragView?.let { removeOverlayWindow(it) }
        dragView = null
        dragWm = null
    }

    fun getDragRegion(): RegionEntry {
        val v = dragView ?: return RegionEntry("", 0.25f, 0.75f, 0.25f, 0.75f)
        return RegionEntry("", v.topFraction, v.bottomFraction, v.leftFraction, v.rightFraction)
    }

    // ── Self-contained OCR debug overlay ─────────────────────────────────

    private val DEBUG_INTERVAL_MS = 2000L

    /**
     * Starts a self-contained loop: capture → OCR → draw bounding boxes.
     * Completely independent of the translation pipeline.
     */
    fun startDebugOcrLoop() {
        if (debugRunning) return
        debugRunning = true
        scheduleDebugCapture()
    }

    fun stopDebugOcrLoop() {
        debugRunning = false
        debugHandler.removeCallbacksAndMessages(null)
        hideDebugOverlay()
    }

    private fun scheduleDebugCapture() {
        if (!debugRunning) return
        debugHandler.postDelayed({ runDebugCapture() }, DEBUG_INTERVAL_MS)
    }

    private fun runDebugCapture() {
        if (!debugRunning) return
        val prefs = Prefs(this)
        val displayId = prefs.captureDisplayIds.firstOrNull()
            ?: android.view.Display.DEFAULT_DISPLAY
        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = dm.getDisplay(displayId) ?: run { scheduleDebugCapture(); return }

        serviceScope.launch {
            val bitmap = screenshotManager?.requestClean(displayId)
            if (bitmap == null || !debugRunning) {
                bitmap?.recycle()
                scheduleDebugCapture()
                return@launch
            }
            val screenshotW = bitmap.width
            val screenshotH = bitmap.height

            val ocr = debugOcrManager
            val result = try {
                kotlinx.coroutines.withContext(Dispatchers.Default) {
                    ocr.recognise(bitmap, SourceLanguageProfiles[prefs.sourceLangId].translationCode, collectDebugBoxes = true)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Debug OCR failed: ${e.message}")
                null
            } finally {
                bitmap.recycle()
            }

            val boxes = result?.debugBoxes
            if (boxes != null && debugRunning) {
                showDebugOverlay(display, boxes, 0, 0, screenshotW, screenshotH)
            } else {
                hideDebugOverlay()
            }
            scheduleDebugCapture()
        }
    }

    private fun showDebugOverlay(
        display: Display,
        boxes: OcrManager.OcrDebugBoxes,
        cropLeft: Int, cropTop: Int,
        screenshotW: Int, screenshotH: Int
    ) {
        hideDebugOverlay()
        val wm = createDisplayContext(display).getSystemService(WindowManager::class.java) ?: return
        val view = OcrDebugOverlayView(this).apply {
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
        )
        addOverlayWindow(view, wm, params, display.displayId)
        debugOverlayWm = wm
        debugOverlayView = view
    }

    fun hideDebugOverlay() {
        debugOverlayView?.let { removeOverlayWindow(it) }
        debugOverlayView = null
        debugOverlayWm = null
    }

    // ── Live translation overlay ─────────────────────────────────────────

    fun showTranslationOverlay(
        display: Display,
        boxes: List<TranslationOverlayView.TextBox>,
        cropLeft: Int, cropTop: Int,
        screenshotW: Int, screenshotH: Int,
        pinholeMode: Boolean = false
    ) {
        // Overlay is appearing — dismiss loading spinner across all icons.
        setIconsLoading(false)

        val displayId = display.displayId
        // Reuse existing view only if it's on the same display AND was
        // constructed with the same pinhole mode; otherwise recreate so the
        // view's pinhole-related bakes (child bg opacity, dispatchDraw mask
        // punching) match what the caller expects.
        val existing = translationOverlayHandles[displayId]?.main
        if (existing != null && existing.pinholeMode == pinholeMode) {
            existing.setBoxes(boxes, cropLeft, cropTop, screenshotW, screenshotH)
            return
        }
        // Pinhole flavor mismatch (or no overlay yet) — tear down just this
        // display's pair and rebuild. Other displays' overlays are untouched.
        hideTranslationOverlayForDisplay(displayId)
        val displayCtx = createDisplayContext(display)
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
        addOverlayWindow(view, wm, params, displayId)

        // Create persistent dirty overlay window (always present, empty when not dirty).
        // Shares the same wm instance as the main overlay.
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
        addOverlayWindow(dirtyView, wm, dirtyParams, displayId)

        translationOverlayHandles[displayId] = TranslationOverlayHandle(view, dirtyView)
    }

    /**
     * Tear down a single display's translation + dirty overlay pair.
     * Idempotent — no-op when nothing is registered for [displayId].
     */
    fun hideTranslationOverlayForDisplay(displayId: Int) {
        val handle = translationOverlayHandles.remove(displayId) ?: return
        removeOverlayWindow(handle.main)
        removeOverlayWindow(handle.dirty)
    }

    /**
     * Tear down translation overlays across every display. Most existing
     * callers want this "global hide" semantic (pause / state recovery /
     * stop-live). P4's per-display modes will migrate to per-display
     * hides where they should only clear their own overlay.
     */
    fun hideTranslationOverlay() {
        if (translationOverlayHandles.isEmpty()) return
        val ids = translationOverlayHandles.keys.toList()
        for (id in ids) hideTranslationOverlayForDisplay(id)
    }

    /**
     * Remove specific overlay boxes from [displayId]'s overlay without
     * rebuilding the entire view. Defaults to the primary display so
     * existing single-display callers (e.g. FuriganaMode) keep working;
     * P4's per-display modes will pass their own [displayId].
     */
    fun removeOverlayBoxes(
        toRemove: List<TranslationOverlayView.TextBox>,
        displayId: Int = CaptureService.instance?.primaryGameDisplayId()
            ?: android.view.Display.DEFAULT_DISPLAY,
    ) {
        translationOverlayHandles[displayId]?.main?.removeBoxesByContent(toRemove)
    }

    // ── Floating overlay icon ─────────────────────────────────────────────

    // ── Input monitoring for live mode ──────────────────────────────────

    /**
     * Per-display input callbacks. Each LiveMode registers a callback for its
     * own displayId; the dispatch is per-source:
     *  - Touch (sentinel) is display-bound — only the touched display's
     *    callback fires (see [fireOnGameInputForDisplay]).
     *  - Gamepad / D-pad is NOT display-bound — controller focus is
     *    independent of touch focus, so a button press on a controller paired
     *    to display A must reach A's callback even if the user just touched
     *    display B for an unrelated reason. Fan-out is correct here (see
     *    [fireOnGameInput]).
     */
    private val onGameInputs: MutableMap<Int, () -> Unit> = mutableMapOf()
    private var lastKeyEventTime = 0L
    private var buttonHeld = false
    private var touchActive = false
    private val TOUCH_HOLD_TIMEOUT_MS = 2000L
    private val touchTimeoutRunnable = Runnable { touchActive = false }

    /** Fan an input event out to every registered listener. Used by the
     *  gamepad/D-pad path in [onKeyEvent], where the input source isn't
     *  bound to a specific display. */
    private fun fireOnGameInput() {
        if (onGameInputs.isEmpty()) return
        onGameInputs.values.forEach { it.invoke() }
    }

    /** Dispatch an input event to a single display's listener. Used by the
     *  touch sentinel path, where the touched display is unambiguous and
     *  invalidating other displays' overlays would cause spurious flicker. */
    private fun fireOnGameInputForDisplay(displayId: Int) {
        onGameInputs[displayId]?.invoke()
    }

    /**
     * True while any input source is actively being used (button held,
     * touch down, or joystick held). CaptureService checks this to avoid
     * showing the overlay during active interaction.
     */
    val isInputActive: Boolean
        get() = buttonHeld || touchActive

    /**
     * Start monitoring gamepad buttons and screen touches on [displayId].
     * The [callback] fires on the main thread for every detected input;
     * multiple displays can have callbacks registered concurrently and all
     * will fire on each input event (see [fireOnGameInput]).
     */
    fun startInputMonitoring(displayId: Int, callback: () -> Unit) {
        onGameInputs[displayId] = callback
        lastKeyEventTime = 0L
        buttonHeld = false
        touchActive = false
        addTouchSentinel(displayId)
    }

    /** Stop monitoring input for a single display. Tears down THIS display's
     *  touch sentinel; global state (buttonHeld, touchActive) only resets
     *  when the last listener goes away. */
    fun stopInputMonitoring(displayId: Int) {
        onGameInputs.remove(displayId)
        removeTouchSentinelForDisplay(displayId)
        if (onGameInputs.isEmpty()) {
            buttonHeld = false
            touchActive = false
            debugHandler.removeCallbacks(touchTimeoutRunnable)
        }
    }

    /** Stop input monitoring across every display (e.g. on stopLive). */
    fun stopInputMonitoring() {
        onGameInputs.clear()
        buttonHeld = false
        touchActive = false
        debugHandler.removeCallbacks(touchTimeoutRunnable)
        removeTouchSentinel()
    }

    // ── Touch sentinel ──────────────────────────────────────────────────

    /**
     * A 1×1 transparent overlay on the game display. With FLAG_WATCH_OUTSIDE_TOUCH
     * every touch elsewhere on the screen delivers ACTION_OUTSIDE without consuming
     * the event, so the game still receives normal input.
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun addTouchSentinel(displayId: Int) {
        if (displayId in touchSentinels) return
        val dm = getSystemService(DisplayManager::class.java)
        val display = dm.getDisplay(displayId) ?: return
        val ctx = createDisplayContext(display)
        val wm = ctx.getSystemService(WindowManager::class.java) ?: return
        val view = View(ctx).apply {
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                    // Mark touch as active. We can't detect touch-up from
                    // the sentinel, so use a timeout to assume lift.
                    touchActive = true
                    // Track which display the user just touched so hotkey
                    // routing lands on the right place (P5).
                    CaptureService.instance?.lastInteractedDisplayId = displayId
                    debugHandler.removeCallbacks(touchTimeoutRunnable)
                    debugHandler.postDelayed(touchTimeoutRunnable, TOUCH_HOLD_TIMEOUT_MS)
                    // Display-bound dispatch — only the touched display's
                    // overlay should be invalidated. Other displays' overlays
                    // stay put; their own scene-change detection covers any
                    // independent updates.
                    fireOnGameInputForDisplay(displayId)
                }
                false
            }
        }
        val params = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        )
        if (addOverlayWindow(view, wm, params, displayId)) {
            touchSentinels[displayId] = view
        }
    }

    /** Remove a single display's touch sentinel. */
    private fun removeTouchSentinelForDisplay(displayId: Int) {
        val view = touchSentinels.remove(displayId) ?: return
        removeOverlayWindow(view)
    }

    /** Remove every touch sentinel. */
    private fun removeTouchSentinel() {
        if (touchSentinels.isEmpty()) return
        val ids = touchSentinels.keys.toList()
        for (id in ids) removeTouchSentinelForDisplay(id)
    }


    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {
        Log.w(TAG, "onInterrupt")
    }

    /** Temporary listener for key event capture (e.g., hotkey setup dialog). Takes priority over normal handling. */
    var onKeyEventListener: ((KeyEvent) -> Boolean)? = null

    /**
     * True when the user has some visible indication the app is listening:
     * either the floating icon is on screen or MainActivity is foregrounded.
     * Used to gate hotkey activation so a user who has hidden the icon and
     * backgrounded the app doesn't get "ghost" hotkey triggers with no
     * feedback. Differs from the foreground-notification rule
     * ([CaptureService.updateForegroundState]) which intentionally omits
     * `foregrounded` — the notification is redundant while the app is on
     * screen, but hotkeys obviously must still work then.
     */
    fun isUserReachable(): Boolean =
        hasAnyFloatingIcon || MainActivity.isInForeground

    // ── Hotkey combo detection ──────────────────────────────────────────

    /**
     * Window to wait on a "shadowed" combo (one that is a proper subset of
     * another configured combo) before firing it. Prevents chord presses
     * like A+B from being misread as just A when A arrives a few ms before
     * B. Humans pressing two buttons simultaneously land within ~20-40ms of
     * each other; 60ms is comfortably above that and still below the point
     * at which players typically feel input latency.
     */
    private val HOTKEY_COMBO_WINDOW_MS = 60L

    private val heldKeyCodes = mutableSetOf<Int>()
    private var activeHotkeyMode: OverlayMode? = null
    private var pendingActivationMode: OverlayMode? = null

    /** Callback when a hotkey combo becomes fully held. */
    var onHotkeyActivated: ((OverlayMode) -> Unit)? = null
    /** Callback when the active hotkey combo is released. */
    var onHotkeyReleased: (() -> Unit)? = null

    private val pendingActivationRunnable = Runnable {
        val mode = pendingActivationMode ?: return@Runnable
        pendingActivationMode = null
        // Re-check reachability: the gate may have closed during the
        // deferral window (user backgrounded the app while mid-chord).
        if (!isUserReachable()) {
            android.util.Log.d("HotkeyDbg", "DEFERRED cancelled (gate closed): $mode")
            return@Runnable
        }
        activeHotkeyMode = mode
        android.util.Log.d("HotkeyDbg", "ACTIVATED (deferred): $mode")
        onHotkeyActivated?.invoke(mode)
    }

    private fun parseCombo(stored: String): Set<Int> {
        if (stored.isBlank()) return emptySet()
        return stored.split("+").mapNotNull { it.toIntOrNull() }.toSet()
    }

    private fun checkHotkeyCombos() {
        val prefs = Prefs(this)
        val combos = listOf(
            HotkeyCombo(parseCombo(prefs.hotkeyTranslation), OverlayMode.TRANSLATION),
            HotkeyCombo(parseCombo(prefs.hotkeyFurigana), OverlayMode.FURIGANA),
        ).filter { it.keys.isNotEmpty() }

        val state = HotkeyState(activeHotkeyMode, pendingActivationMode)
        val action = decideHotkeyAction(
            held = heldKeyCodes,
            state = state,
            combos = combos,
            reachable = isUserReachable(),
        )

        android.util.Log.d(
            "HotkeyDbg",
            "checkCombos: held=$heldKeyCodes combos=$combos state=$state → $action"
        )

        when (action) {
            is HotkeyAction.NoChange -> Unit

            is HotkeyAction.ActivateNow -> {
                debugHandler.removeCallbacks(pendingActivationRunnable)
                pendingActivationMode = null
                activeHotkeyMode = action.mode
                android.util.Log.d("HotkeyDbg", "ACTIVATED: ${action.mode}")
                onHotkeyActivated?.invoke(action.mode)
            }

            is HotkeyAction.DeferActivation -> {
                debugHandler.removeCallbacks(pendingActivationRunnable)
                pendingActivationMode = action.mode
                android.util.Log.d(
                    "HotkeyDbg",
                    "DEFERRED: ${action.mode} (waiting ${HOTKEY_COMBO_WINDOW_MS}ms for possible superset)"
                )
                debugHandler.postDelayed(pendingActivationRunnable, HOTKEY_COMBO_WINDOW_MS)
            }

            HotkeyAction.Release -> {
                val released = activeHotkeyMode
                activeHotkeyMode = null
                android.util.Log.d("HotkeyDbg", "RELEASED: $released")
                onHotkeyReleased?.invoke()
            }

            HotkeyAction.ClearPending -> {
                debugHandler.removeCallbacks(pendingActivationRunnable)
                val cleared = pendingActivationMode
                pendingActivationMode = null
                android.util.Log.d("HotkeyDbg", "PENDING CLEARED: $cleared")
            }
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        android.util.Log.d("HotkeyDbg", "onKeyEvent: keyCode=${event.keyCode} action=${event.action} source=0x${event.source.toString(16)}")

        // If a key event listener is active (e.g., hotkey setup), let it handle first
        onKeyEventListener?.let { listener ->
            if (listener(event)) return true
        }

        val src = event.source
        val isGameInput = src and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD
            || src and InputDevice.SOURCE_DPAD == InputDevice.SOURCE_DPAD
            || KeyEvent.isGamepadButton(event.keyCode)
        if (isGameInput) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    lastKeyEventTime = System.currentTimeMillis()
                    buttonHeld = true
                    heldKeyCodes.add(event.keyCode)
                    if (isAnyDragLookupPopupShowing) {
                        dismissAllDragLookupPopups()
                    }
                    fireOnGameInput()
                    checkHotkeyCombos()
                }
                KeyEvent.ACTION_UP -> {
                    buttonHeld = false
                    heldKeyCodes.remove(event.keyCode)
                    lastKeyEventTime = System.currentTimeMillis()
                    fireOnGameInput()
                    checkHotkeyCombos()
                }
            }
        }
        return false // pass through to the game
    }

    /**
     * Single entry point for all icon visibility updates.
     * Called from: onServiceConnected, settings toggle, capture display change.
     *
     * Shows, hides, or relocates the icon based on current state.
     */
    /**
     * Reconcile the floating icons against [Prefs.captureDisplayIds]:
     *  - tear down icons whose display is no longer selected or has been
     *    disconnected
     *  - install icons for newly-selected, currently-connected displays
     *
     * Replaces P1's [ensureFloatingIcon] (which only knew about a single
     * primary display). Called on service connect, settings change, and
     * display hot-plug.
     */
    fun reconcileFloatingIcons() {
        val prefs = Prefs(this)
        if (!prefs.showOverlayIcon) {
            hideFloatingIcon("pref_disabled")
            return
        }
        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val target = prefs.captureDisplayIds

        // Snapshot before mutating — tear down icons no longer needed.
        val staleIds = iconHandles.keys.filter { id ->
            id !in target || dm.getDisplay(id) == null
        }
        for (id in staleIds) hideFloatingIconForDisplay(id, "reconcile_remove")

        // If the user has nothing selected (e.g. transient migration state)
        // fall back to the legacy single-display heuristic so the app always
        // has at least one icon while it's "configured."
        if (target.isEmpty()) {
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

    /** Backwards-compat alias. */
    @Deprecated(
        "Use reconcileFloatingIcons() — single-display semantics are gone.",
        ReplaceWith("reconcileFloatingIcons()")
    )
    fun ensureFloatingIcon() = reconcileFloatingIcons()

    private fun installFloatingIconForDisplay(display: Display, prefs: Prefs) {
        val displayId = display.displayId
        // Idempotent: if an icon was already there for this display, tear it
        // down first so the closures and registry stay coherent.
        hideFloatingIconForDisplay(displayId, "recreating")

        val displayCtx = createDisplayContext(display)
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
        val popup = WordLookupPopup(displayCtx, wm, displayId)
        val magnifier = MagnifierLens(displayCtx, wm, displayId)
        val controller = DragLookupController(
            displayId = displayId,
            popup = popup,
            magnifier = magnifier
        )
        // Track whether live mode / region overlay were active when drag started
        var liveWasPausedForPopup = false
        var overlayHiddenForDrag = false
        // Wire the service-level cancel hook to this closure's flag.
        // openSentenceInApp calls cancelLivePauseObligation() to make the
        // chained resumeLiveMode in onSettled a no-op when the user is
        // launching a covering detail view (single-screen). Stored as a
        // local for now; bundled into the FloatingIconHandle on success.
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
                val effectivelySingleScreen = Prefs.isSingleScreen(this) || !MainActivity.isInForeground
                if (effectivelySingleScreen) {
                    toggleLiveDirect(true)
                } else {
                    sendMainActivityIntent(MainActivity.ACTION_START_LIVE)
                }
            }
        }

        // The controller fires onSettled from every "drag flow done, no popup
        // pending" path: drag end with no OCR / no hit / async lookup miss,
        // popup dismissed post-drag, and gesture cancel. The restore lambdas
        // are idempotent (gated on overlayHiddenForDrag / liveWasPausedForPopup).
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
                val effectivelySingleScreen = Prefs.isSingleScreen(this) || !MainActivity.isInForeground
                if (effectivelySingleScreen) {
                    toggleLiveDirect(false)
                } else {
                    sendMainActivityIntent(MainActivity.ACTION_STOP_LIVE)
                }
            }
            // Always take a fresh screenshot — ScreenshotManager handles
            // rate limiting, and the cached file could be stale.
            controller.onDragStart()
        }
        icon.onDragMove = { rawX, rawY -> controller.onDragMove(rawX, rawY) }
        icon.onDragEnd = { controller.onDragEnd() }
        icon.onDragCancel = { controller.cancelDrag() }
        icon.onHoldCancel = { CaptureService.instance?.holdCancel() }
        // Pass the displayId through so P5 can route the one-shot to the
        // tapped icon's display. P2's holdStart still treats this as global
        // pause; the parameter just gets recorded.
        icon.onHoldStart  = { CaptureService.instance?.holdStart(displayId) }
        icon.onHoldEnd    = { CaptureService.instance?.holdEnd() }
        icon.onAnyTouch   = {
            // Track which display the user is interacting with so hotkey
            // one-shots route to the right place when more than one display
            // is selected (P5 wires this further into OneShotManager).
            CaptureService.instance?.lastInteractedDisplayId = displayId
            DimController.notifyInteraction()
        }
        if (addOverlayWindow(icon, wm, params, displayId)) {
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
            // Window install failed. Nothing to roll back from any map —
            // we never inserted a handle. Just destroy the controller we
            // built locally.
            controller.destroy()
        }

        // The old floatingIcon setter used to fire these on every install/
        // teardown; we keep that contract by triggering them explicitly.
        CaptureService.instance?.updateForegroundState()
        CaptureService.instance?.syncIconState()
    }

    /**
     * Remove and re-add floating icons so they draw above newly added
     * overlays. Pass [displayId] = null (default) to bring every icon
     * forward; pass an id to bring just one. Icons in drag mode are skipped
     * — re-adding mid-drag breaks the touch event sequence.
     */
    fun bringFloatingIconsToFront(displayId: Int? = null) {
        // The remove + re-add pair re-stacks the icon above newly-added overlays.
        // The handle in [iconHandles] is intentionally NOT mutated across this
        // call — view, wm, controller, and closure all stay the same instances.
        val targets: List<Map.Entry<Int, FloatingIconHandle>> = if (displayId != null) {
            listOfNotNull(iconHandles.entries.firstOrNull { it.key == displayId })
        } else {
            iconHandles.entries.toList()
        }
        for ((id, handle) in targets) {
            if (handle.icon.inDragMode) continue
            val params = handle.icon.params ?: continue
            removeOverlayWindow(handle.icon)
            addOverlayWindow(handle.icon, handle.wm, params, id)
        }
    }

    /** Tear down a single display's floating icon and its drag-lookup
     *  controller. Idempotent — no-op when no icon is registered for
     *  [displayId]. */
    private fun hideFloatingIconForDisplay(displayId: Int, reason: String) {
        val handle = iconHandles.remove(displayId) ?: return
        Log.i(TAG, "hideFloatingIcon[$displayId]: $reason")
        handle.dragController.destroy()
        handle.icon.destroy()
        removeOverlayWindow(handle.icon)

        CaptureService.instance?.updateForegroundState()
        CaptureService.instance?.syncIconState()
    }

    /** Tear down every floating icon. Used by callers that want to remove
     *  the icons entirely (task removed, user disabled the icon pref). */
    fun hideFloatingIcon(reason: String = "unspecified") {
        if (iconHandles.isEmpty()) return
        Log.i(TAG, "hideFloatingIcon (all): $reason")
        // Snapshot to a list since hideFloatingIconForDisplay mutates the map.
        val ids = iconHandles.keys.toList()
        for (id in ids) hideFloatingIconForDisplay(id, reason)
    }

    /** Called by [com.playtranslate.ui.DragLookupController.openSentenceInApp]
     *  before dismissing the magnifier so the dismiss-chain's resumeLiveMode
     *  is a no-op when the detail view will cover the live-mode surface.
     *  The "covering" predicate matches the service's own
     *  [effectivelySingleScreen]-style routing used elsewhere — single-screen
     *  OR dual-screen with MainActivity backgrounded both result in TRA
     *  taking over the user's view. Dual-screen with the in-app panel
     *  visible leaves auto-resume in place since TRA lands on the panel
     *  side and live captures continue on the game side. The user re-enables
     *  live mode manually via the floating-icon menu after closing the
     *  detail view.
     *
     *  Iterates all per-display flags — only one is realistically active at
     *  any time (one pointer means one drag), but invoking all is harmless
     *  and avoids a stale-id race. */
    fun cancelLivePauseObligation() {
        val effectivelySingleScreen = Prefs.isSingleScreen(this) || !MainActivity.isInForeground
        if (effectivelySingleScreen) {
            iconHandles.values.forEach { it.clearLivePauseFlag.invoke() }
        }
    }

    // ── Floating icon menu ────────────────────────────────────────────────

    private fun showFloatingMenu(display: Display, icon: FloatingOverlayIcon) {
        dismissFloatingMenu()
        val wm = createDisplayContext(display).getSystemService(WindowManager::class.java) ?: return
        val screenSize = getDisplaySize(display)
        val themeRes = baseActivityTheme(this)
        val themedCtx = android.view.ContextThemeWrapper(createDisplayContext(display), themeRes)
        applyAccentOverlay(themedCtx.theme, this)
        val menu = FloatingIconMenu(themedCtx)
        menu.isSingleScreen = Prefs.isSingleScreen(this)

        // Suppress live captures while menu is open — the menu darkens
        // the screen and we don't want overlays appearing behind it.
        CaptureService.instance?.holdActive = true
        hideTranslationOverlay()

        val prefs = Prefs(this)
        val hintKind = SourceLanguageProfiles[prefs.sourceLangId].hintTextKind
        menu.hintModeLabel = if (prefs.overlayMode == OverlayMode.FURIGANA && hintKind != HintTextKind.NONE) {
            when (hintKind) { HintTextKind.PINYIN -> "Pinyin"; else -> "Furigana" }
        } else null
        menu.isLiveMode = CaptureService.instance?.isLive == true
        menu.showDegradedWarning = CaptureService.instance?.degradedState?.value == true
        menu.onHideIcon = {
            dismissFloatingMenu()
            Prefs(this).showOverlayIcon = false
            hideFloatingIcon("menu_turn_off")
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
            // Only refresh if the menu is still showing — other handlers
            // (onRegionSelected, onToggleLive, etc.) call dismissFloatingMenu
            // first, so floatingMenu would already be null.
            val needsRefresh = floatingMenu != null && CaptureService.instance?.isLive == true
            dismissFloatingMenu()
            if (needsRefresh) {
                CaptureService.instance?.refreshLiveOverlay()
            }
        }
        menu.onToggleLive = {
            dismissFloatingMenu()
            if (CaptureService.instance?.isLive == true) {
                // Stopping: same logic regardless of mode
                val effectivelySingleScreen = Prefs.isSingleScreen(this) || !MainActivity.isInForeground
                if (effectivelySingleScreen) {
                    toggleLiveDirect(false)
                } else {
                    sendMainActivityIntent(MainActivity.ACTION_STOP_LIVE)
                }
            } else {
                // Starting
                if (Prefs.shouldUseInAppOnlyMode(this)) {
                    // Dual screen + hide overlays + single display selected:
                    // bring app to foreground for In-App Only.
                    sendMainActivityIntent(MainActivity.ACTION_START_LIVE)
                } else {
                    val effectivelySingleScreen = Prefs.isSingleScreen(this) || !MainActivity.isInForeground
                    if (effectivelySingleScreen) {
                        toggleLiveDirect(true)
                    } else {
                        sendMainActivityIntent(MainActivity.ACTION_START_LIVE)
                    }
                }
            }
        }
        // The floating menu is anchored to a specific display; show that
        // display's region in the picker and route override writes to it.
        menu.activeRegion = CaptureService.instance?.activeRegionForDisplay(display.displayId)
        menu.onRegionSelected = { region ->
            dismissFloatingMenu()
            CaptureService.instance?.configureOverride(display.displayId, region)
            if (CaptureService.instance?.isLive == true) {
                hideTranslationOverlay()
                CaptureService.instance?.refreshLiveOverlay()
            } else {
                val effectivelySingleScreen = Prefs.isSingleScreen(this) || !MainActivity.isInForeground
                if (effectivelySingleScreen) {
                    handleRegionSelection(display.displayId, region)
                } else {
                    sendMainActivityIntent(MainActivity.ACTION_REGION_CAPTURE)
                }
            }
        }
        menu.onClearRegion = {
            // Reset this display's selected region to full screen.
            val prefs = Prefs(this)
            prefs.setSelectedRegionIdForDisplay(display.displayId, Prefs.DEFAULT_REGION_LIST[0].id)
            val svc = CaptureService.instance
            if (svc != null && svc.isConfigured) {
                // Drop any runtime override too — clearOverride re-resolves
                // the region from Prefs (now full screen) and refreshes
                // live mode for this display.
                svc.clearOverride(display.displayId)
            }
            if (MainActivity.isInForeground) {
                sendMainActivityIntent(MainActivity.ACTION_REFRESH_REGION_LABEL)
            }
        }
        menu.onCaptureRegion = {
            dismissFloatingMenu()
            val effectivelySingleScreen = Prefs.isSingleScreen(this) || !MainActivity.isInForeground
            if (effectivelySingleScreen) {
                showRegionEditor(display)
            } else {
                sendMainActivityIntent(MainActivity.ACTION_ADD_CUSTOM_REGION)
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

        addOverlayWindow(menu, wm, params, display.displayId)
        floatingMenuWm = wm
        floatingMenu = menu

        // Compute icon center in screen coords
        val p = icon.params
        val iconCx = (p?.x ?: 0) + icon.viewSizePx / 2
        val iconCy = (p?.y ?: 0) + icon.viewSizePx / 2
        menu.positionNearIcon(iconCx, iconCy, icon.currentEdge, screenSize.x, screenSize.y)
    }

    private fun dismissFloatingMenu() {
        val wasShowing = floatingMenu != null
        floatingMenu?.let { removeOverlayWindow(it) }
        floatingMenu = null
        floatingMenuWm = null
        if (wasShowing) {
            CaptureService.instance?.holdActive = false
        }
    }

    private fun showHideConfirmAlert(display: android.view.Display) {
        val displayCtx = createDisplayContext(display)
        val overlayWm = displayCtx.getSystemService(WindowManager::class.java) ?: return
        val appName = getString(R.string.app_name)
        val prefs = Prefs(this)
        val alreadyCompact = prefs.compactOverlayIcon

        val builder = OverlayAlert.Builder(displayCtx, overlayWm, display.displayId)

        if (!Prefs.hasMultipleDisplays(this)) {
            builder.setTitle("Disable $appName?")
                .setMessage("Re-enable in $appName app")
            if (!alreadyCompact) {
                builder.addButton("Minimize Icon", OverlayColors.accent(this)) {
                    prefs.compactOverlayIcon = true
                    hideFloatingIcon("confirm_minimize_single")
                    reconcileFloatingIcons()
                }
            }
            builder.addButton("Turn Off", OverlayColors.divider(this), OverlayColors.danger(this)) {
                    prefs.showOverlayIcon = false
                    hideFloatingIcon("confirm_turn_off_single")
                }
                .addCancelButton()
        } else {
            builder.setTitle("Hide $appName game screen controls?")
            if (!alreadyCompact) {
                builder.setMessage("\u201CMinimize Icon\u201D shrinks the floating icon. \u201CTurn Off\u201D disables it until re-enabled in settings.")
                    .addButton("Minimize Icon", OverlayColors.accent(this)) {
                        prefs.compactOverlayIcon = true
                        hideFloatingIcon("confirm_minimize_multi")
                        reconcileFloatingIcons()
                    }
            } else {
                builder.setMessage("\u201CHide for Now\u201D brings it back next time you open $appName. \u201CTurn Off\u201D disables it until re-enabled in settings.")
                    .addButton("Hide for Now", OverlayColors.accent(this)) {
                        hideFloatingIcon("confirm_hide_for_now")
                    }
            }
            builder.addButton("Turn Off", OverlayColors.divider(this), OverlayColors.danger(this)) {
                    prefs.showOverlayIcon = false
                    hideFloatingIcon("confirm_turn_off_multi")
                }
                .addCancelButton()
        }

        builder.show()
    }

    // ── Single-screen region editor ─────────────────────────────────────

    /**
     * Shows the RegionDragView pre-populated with the current capture region
     * plus a small floating bar with Use/Cancel buttons. Used on single-screen
     * devices (or when the app is backgrounded) where we can't show
     * AddCustomRegionSheet on a separate screen.
     */
    private fun showRegionEditor(display: Display) {
        hideRegionEditor()
        // Suppress live captures while editing
        CaptureService.instance?.holdActive = true
        hideTranslationOverlay()
        hideRegionOverlay()

        // Pre-populate with this display's current active region (or
        // default if full-screen). The region editor is anchored to the
        // display [showRegionEditor] was opened on.
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

        // Build floating Use / Cancel button bar
        val ctx = createDisplayContext(display)
        val wm = ctx.getSystemService(WindowManager::class.java) ?: return
        val dp = ctx.resources.displayMetrics.density
        val btnSize = (48 * dp).toInt()
        val barPad = (12 * dp).toInt()
        val gap = (16 * dp).toInt()

        val surfaceColor = OverlayColors.surface(this)
        val cardColor = OverlayColors.card(this)
        val dividerColor = OverlayColors.divider(this)
        val accentColorBtn = OverlayColors.accent(this)
        val accentOnColor = OverlayColors.accentOn(this)
        val textColor = OverlayColors.text(this)
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

        // Cancel button (X)
        val cancelBtn = android.widget.TextView(ctx).apply {
            text = "\u2715"
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

        // Use button (checkmark)
        val useBtn = android.widget.TextView(ctx).apply {
            text = "\u2713"
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

        addOverlayWindow(bar, wm, barParams, display.displayId)
        regionEditorBarWm = wm
        regionEditorBar = bar

        // Instruction label at top center
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
        addOverlayWindow(label, wm, labelParams, display.displayId)
        regionEditorLabelWm = wm
        regionEditorLabel = label
    }

    private fun hideRegionEditor() {
        hideRegionDragOverlay()
        regionEditorBar?.let { removeOverlayWindow(it) }
        regionEditorBar = null
        regionEditorBarWm = null
        regionEditorLabel?.let { removeOverlayWindow(it) }
        regionEditorLabel = null
        regionEditorLabelWm = null
        CaptureService.instance?.holdActive = false
    }

    /**
     * Returns the floating icon's bounding rect in screen coordinates for
     * [displayId], or null if no icon is showing on that display. Used by
     * CaptureService to black out the icon area before OCR so it doesn't
     * interfere with text recognition.
     */
    fun getFloatingIconRect(displayId: Int): android.graphics.Rect? {
        val icon = iconHandles[displayId]?.icon ?: return null
        val p = icon.params ?: return null
        return android.graphics.Rect(p.x, p.y, p.x + icon.viewSizePx, p.y + icon.viewSizePx)
    }

    /**
     * Start/stop live mode directly without bringing MainActivity to the
     * foreground. Used on single-screen devices where showing the Activity
     * would cover the game.
     */
    private fun toggleLiveDirect(start: Boolean) {
        val svc = CaptureService.instance ?: return
        if (start) {
            // Live state is set by CaptureService.startLive() via LiveData
            // Dismiss any definition popup when entering live mode.
            val hadPopup = isAnyDragLookupPopupShowing
            dismissAllDragLookupPopups()
            if (!svc.isConfigured) {
                val prefs = Prefs(this)
                svc.configureSaved(displayIds = prefs.captureDisplayIds)
            }
            // Delay start if a popup was just dismissed so the compositor
            // has time to remove it before the first screenshot.
            if (hadPopup) {
                debugHandler.postDelayed({ svc.startLive() }, 100)
            } else {
                svc.startLive()
            }
        } else {
            svc.stopLive()
        }
    }

    private fun sendMainActivityIntent(action: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            this.action = action
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
    }

    /**
     * Routes a drag-selected region to the appropriate activity:
     * - If effectively single screen (or app not in foreground), captures the
     *   screenshot NOW (while the game is still visible) and passes the path
     *   to TranslationResultActivity so it can OCR the saved image.
     * - Otherwise, sends ACTION_REGION_CAPTURE to MainActivity (which is on a
     *   different display and doesn't cover the game).
     */
    private fun handleRegionSelection(displayId: Int, region: RegionEntry) {
        val effectivelySingleScreen = Prefs.isSingleScreen(this) || !MainActivity.isInForeground
        if (effectivelySingleScreen) {
            serviceScope.launch {
                val bitmap = screenshotManager?.requestClean(displayId)
                val intent = Intent(this@PlayTranslateAccessibilityService, com.playtranslate.ui.TranslationResultActivity::class.java).apply {
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
                startActivity(intent)
            }
        } else {
            val intent = Intent(this, MainActivity::class.java).apply {
                action = MainActivity.ACTION_REGION_CAPTURE
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(MainActivity.EXTRA_TOP_FRAC, region.top)
                putExtra(MainActivity.EXTRA_BOTTOM_FRAC, region.bottom)
                putExtra(MainActivity.EXTRA_LEFT_FRAC, region.left)
                putExtra(MainActivity.EXTRA_RIGHT_FRAC, region.right)
            }
            startActivity(intent)
        }
    }

    /**
     * Saves a pre-captured screenshot to the app's cache directory so it can
     * be loaded by TranslationResultActivity on single-screen devices.
     */
    private fun savePreCapturedScreenshot(bitmap: Bitmap): String? {
        return try {
            val dir = java.io.File(cacheDir, "screenshots").apply { mkdirs() }
            val file = java.io.File(dir, "precapture.jpg")
            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "savePreCapturedScreenshot failed: ${e.message}")
            null
        }
    }

    /** Returns the capture display (game screen), or the only display on single-screen.
     *  Used by [reconcileFloatingIcons] as a fallback when [Prefs.captureDisplayIds]
     *  is unexpectedly empty. */
    private fun findIconDisplay(prefs: Prefs): Display? {
        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
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

    // ── Screenshot (legacy, kept for callers not yet migrated) ──────────
    // All new code should use screenshotManager.requestClean/requestRaw.

    companion object {
        private const val TAG = "PlayTranslateA11y"

        /** Non-null while the service is connected (i.e. user has it enabled).
         *  `@Volatile` because the field is written from the main thread
         *  (`onServiceConnected` / `onUnbind`) and read from many others —
         *  drag controllers, hotkey callbacks, ML Kit worker threads. Without
         *  the visibility barrier a stale non-null read could survive a
         *  service teardown. Mirrors [CaptureService.instance]'s annotation. */
        @Volatile
        var instance: PlayTranslateAccessibilityService? = null

        val isEnabled: Boolean get() = instance != null

        /**
         * Static convenience for overlay owners that don't have a service
         * reference handy (MagnifierLens, WordLookupPopup, etc.). When the
         * service isn't connected the window is added without registration —
         * it just won't participate in clean-capture blanking.
         *
         * [displayId] must be the display the window will appear on so
         * [prepareForCleanCapture] can scope its blanking. There is no
         * default — silently falling back to [Display.DEFAULT_DISPLAY] for
         * a window actually shown on a secondary display would leak the
         * window into clean screenshots of that display.
         */
        fun addOverlay(
            view: View,
            wm: WindowManager,
            params: WindowManager.LayoutParams,
            displayId: Int,
        ): Boolean {
            instance?.let { return it.addOverlayWindow(view, wm, params, displayId) }
            return try { wm.addView(view, params); true } catch (_: Exception) { false }
        }

        fun removeOverlay(view: View, wm: WindowManager) {
            // If the service is connected and the view is in the registry,
            // removeOverlayWindow handles both unregister + removeView. If
            // the view was added via the no-service fallback path of
            // [addOverlay] (service connected later), it's not in the
            // registry — fall through to a direct removeView so the window
            // doesn't leak.
            if (instance?.removeOverlayWindow(view) == true) return
            try { wm.removeView(view) } catch (_: Exception) {}
        }
    }
}
