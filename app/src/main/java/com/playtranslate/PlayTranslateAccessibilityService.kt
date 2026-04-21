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
    internal var floatingIcon: FloatingOverlayIcon? = null
        set(value) {
            field = value
            CaptureService.instance?.updateForegroundState()
            CaptureService.instance?.syncIconState()
        }
    private var floatingIconWm: WindowManager? = null
    private var floatingIconDisplayId: Int = -1
    var dragLookupController: DragLookupController? = null
        private set
    private var floatingMenu: FloatingIconMenu? = null
    private var floatingMenuWm: WindowManager? = null
    private var debugOverlayView: OcrDebugOverlayView? = null
    private var debugOverlayWm: WindowManager? = null
    internal var translationOverlayView: TranslationOverlayView? = null
    private var translationOverlayWm: WindowManager? = null
    private var translationOverlayDisplayId: Int = -1

    internal var dirtyOverlayView: TranslationOverlayView? = null
    private var dirtyOverlayWm: WindowManager? = null
    private var touchSentinelView: View? = null
    private var touchSentinelWm: WindowManager? = null
    private var regionEditorBar: View? = null
    private var regionEditorBarWm: WindowManager? = null
    private var regionEditorLabel: View? = null
    private var regionEditorLabelWm: WindowManager? = null
    private var regionIndicatorView: View? = null
    private var regionIndicatorWm: WindowManager? = null
    private var regionIndicatorPersistent = false
    private val regionIndicatorHandler = Handler(Looper.getMainLooper())
    private val debugOcrManager get() = OcrManager.instance
    private val debugHandler = Handler(Looper.getMainLooper())
    private var debugRunning = false
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Centralized screenshot manager — all takeScreenshot calls go through here. */
    var screenshotManager: ScreenshotManager? = null
        private set

    /** Repositions the floating icon when display properties change (e.g. rotation). */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != floatingIconDisplayId) return
            val icon = floatingIcon ?: return
            val p = icon.params ?: return
            val prefs = Prefs(this@PlayTranslateAccessibilityService)
            icon.setPosition(prefs.overlayIconEdge, prefs.overlayIconFraction)
            try { floatingIconWm?.updateViewLayout(icon, p) } catch (_: Exception) {}
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
        ensureFloatingIcon()
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

    /** Shows a persistent region indicator on the game display (reuses the indicator view). */
    fun showRegionOverlay(display: Display, region: RegionEntry) {
        if (floatingIcon?.inDragMode == true) return
        showRegionIndicator(display, region, persistent = true)
        // Re-add the floating icon so it draws above the full-screen dim overlay
        bringFloatingIconToFront()
    }

    fun updateRegionOverlay(region: RegionEntry) {
        // No incremental update — the indicator is recreated on next showRegionOverlay call
    }

    fun hideRegionOverlay() {
        hideRegionIndicator(force = true)
    }

    // ── Overlay state for ScreenshotManager ──────────────────────────────

    /** State returned by [prepareForCleanCapture], passed to [restoreAfterCapture]. */
    data class OverlayState(
        val hadTranslation: Boolean,
        val hadDebug: Boolean,
        val hadRegionIndicator: Boolean
    )

    /**
     * Hides overlays so they don't appear in a clean screenshot.
     * Returns the previous state so [restoreAfterCapture] can restore it.
     */
    fun prepareForCleanCapture(): OverlayState {
        val state = OverlayState(
            hadTranslation = translationOverlayView != null,
            hadDebug = debugOverlayView != null,
            hadRegionIndicator = regionIndicatorView != null
        )
        if (state.hadRegionIndicator) regionIndicatorView?.visibility = View.INVISIBLE
        if (state.hadDebug) debugOverlayView?.visibility = View.INVISIBLE
        if (state.hadTranslation) translationOverlayView?.visibility = View.INVISIBLE
        return state
    }

    /** Restores overlays that were hidden by [prepareForCleanCapture]. */
    fun restoreAfterCapture(state: OverlayState) {
        if (state.hadDebug) debugOverlayView?.visibility = View.VISIBLE
        if (state.hadTranslation) translationOverlayView?.visibility = View.VISIBLE
        if (state.hadRegionIndicator && floatingIcon?.inDragMode != true) {
            regionIndicatorView?.visibility = View.VISIBLE
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
            private val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = bgColor
                textSize = 12f * dp
                textAlign = android.graphics.Paint.Align.CENTER
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
            }
            private val labelBgPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = accentColor
                style = android.graphics.Paint.Style.FILL
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
                val l = w * region.left
                val t = h * region.top
                val r = w * region.right
                val b = h * region.bottom

                // Darkened area outside the capture region
                if (t > 0f) canvas.drawRect(0f, 0f, w, t, dimPaint)
                if (b < h) canvas.drawRect(0f, b, w, h, dimPaint)
                if (l > 0f) canvas.drawRect(0f, t, l, b, dimPaint)
                if (r < w) canvas.drawRect(r, t, w, b, dimPaint)

                // Soft accent glow — clipped to outside the region only
                canvas.save()
                canvas.clipRect(l, t, r, b, android.graphics.Region.Op.DIFFERENCE)
                val glowOffset = glowPaint.strokeWidth / 2f
                canvas.drawRect(l - glowOffset, t - glowOffset, r + glowOffset, b + glowOffset, glowPaint)
                canvas.restore()

                // Accent border outside the capture region
                val half = borderPaint.strokeWidth / 2f
                canvas.drawRect(l - half, t - half, r + half, b + half, borderPaint)

                // Label with accent background, centered above (or below) the region
                val cx = (l + r) / 2f
                val textW = textPaint.measureText(displayLabel)
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
                canvas.drawText(displayLabel, cx, textY, textPaint)
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
        wm.addView(view, params)
        regionIndicatorView = view
        regionIndicatorWm = wm
        regionIndicatorPersistent = persistent

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
            try { regionIndicatorWm?.removeView(view) } catch (_: Exception) {}
        }
        regionIndicatorView = null
        regionIndicatorWm = null
        regionIndicatorPersistent = false
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

        wm.addView(view, params)
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
            try { pillWm?.removeView(view) } catch (_: Exception) {}
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
        wm.addView(view, params)
        dragWm = wm
        dragView = view
    }

    fun hideRegionDragOverlay() {
        try { dragView?.let { dragWm?.removeView(it) } } catch (_: Exception) {}
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
        val displayId = prefs.captureDisplayId
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
        wm.addView(view, params)
        debugOverlayWm = wm
        debugOverlayView = view
    }

    fun hideDebugOverlay() {
        try { debugOverlayView?.let { debugOverlayWm?.removeView(it) } } catch (_: Exception) {}
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
        // Overlay is appearing — dismiss loading spinner
        floatingIcon?.showLoading = false

        // Reuse existing view only if it's on the same display AND was
        // constructed with the same pinhole mode; otherwise recreate so the
        // view's pinhole-related bakes (child bg opacity, dispatchDraw mask
        // punching) match what the caller expects.
        val existing = translationOverlayView
        if (existing != null
            && translationOverlayDisplayId == display.displayId
            && existing.pinholeMode == pinholeMode) {
            existing.setBoxes(boxes, cropLeft, cropTop, screenshotW, screenshotH)
            return
        }
        hideTranslationOverlay()
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
        wm.addView(view, params)
        translationOverlayWm = wm
        translationOverlayView = view
        translationOverlayDisplayId = display.displayId

        // Create persistent dirty overlay window (always present, empty when not dirty)
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
        wm.addView(dirtyView, dirtyParams)
        dirtyOverlayWm = wm
        dirtyOverlayView = dirtyView
    }

    fun hideTranslationOverlay() {
        try { translationOverlayView?.let { translationOverlayWm?.removeView(it) } } catch (_: Exception) {}
        translationOverlayView = null
        translationOverlayWm = null
        translationOverlayDisplayId = -1
        try { dirtyOverlayView?.let { dirtyOverlayWm?.removeView(it) } } catch (_: Exception) {}
        dirtyOverlayView = null
        dirtyOverlayWm = null
    }

    /** Remove specific overlay boxes without rebuilding the entire view. */
    fun removeOverlayBoxes(toRemove: List<TranslationOverlayView.TextBox>) {
        translationOverlayView?.removeBoxesByContent(toRemove)
    }

    // ── Floating overlay icon ─────────────────────────────────────────────

    // ── Input monitoring for live mode ──────────────────────────────────

    private var onGameInput: (() -> Unit)? = null
    private var lastKeyEventTime = 0L
    private var buttonHeld = false
    private var touchActive = false
    private val TOUCH_HOLD_TIMEOUT_MS = 2000L
    private val touchTimeoutRunnable = Runnable { touchActive = false }

    /**
     * True while any input source is actively being used (button held,
     * touch down, or joystick held). CaptureService checks this to avoid
     * showing the overlay during active interaction.
     */
    val isInputActive: Boolean
        get() = buttonHeld || touchActive

    /**
     * Start monitoring gamepad buttons and screen touches on [displayId].
     * If [joystick] is true, also adds a joystick sentinel that polls for
     * analog stick / d-pad movement (uses focus cycling — slight risk of
     * eating key events). On API 34+ with MediaProjection this is disabled
     * since timer-based polling handles everything without focus tricks.
     *
     * [callback] fires on the main thread for every detected input.
     */
    fun startInputMonitoring(displayId: Int, callback: () -> Unit) {
        onGameInput = callback
        lastKeyEventTime = 0L
        buttonHeld = false
        touchActive = false
        addTouchSentinel(displayId)
    }

    fun stopInputMonitoring() {
        onGameInput = null
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
        if (touchSentinelView != null) return
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
                    debugHandler.removeCallbacks(touchTimeoutRunnable)
                    debugHandler.postDelayed(touchTimeoutRunnable, TOUCH_HOLD_TIMEOUT_MS)
                    onGameInput?.invoke()
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
        wm.addView(view, params)
        touchSentinelView = view
        touchSentinelWm = wm
    }

    private fun removeTouchSentinel() {
        try { touchSentinelView?.let { touchSentinelWm?.removeView(it) } } catch (_: Exception) {}
        touchSentinelView = null
        touchSentinelWm = null
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
        floatingIcon != null || MainActivity.isInForeground

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
                    if (dragLookupController?.isPopupShowing == true) {
                        dragLookupController?.dismiss()
                    }
                    onGameInput?.invoke()
                    checkHotkeyCombos()
                }
                KeyEvent.ACTION_UP -> {
                    buttonHeld = false
                    heldKeyCodes.remove(event.keyCode)
                    lastKeyEventTime = System.currentTimeMillis()
                    onGameInput?.invoke()
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
    fun ensureFloatingIcon() {
        val prefs = Prefs(this)
        if (!prefs.showOverlayIcon) {
            hideFloatingIcon("pref_disabled")
            return
        }
        val display = findIconDisplay(prefs) ?: return
        if (floatingIcon != null && floatingIconDisplayId == display.displayId) return
        Log.d(TAG, "ensureFloatingIcon: showing on display ${display.displayId} (current=$floatingIconDisplayId, captureId=${prefs.captureDisplayId})")
        showFloatingIcon(display, prefs)
    }

    private fun showFloatingIcon(display: Display, prefs: Prefs) {
        hideFloatingIcon("recreating")
        val displayCtx = createDisplayContext(display)
        val wm = displayCtx.getSystemService(WindowManager::class.java) ?: return

        val icon = FloatingOverlayIcon(displayCtx).apply {
            this.wm = wm
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

        icon.onPositionChanged = { edge, fraction ->
            prefs.overlayIconEdge = edge
            prefs.overlayIconFraction = fraction
        }
        icon.onTap = {
            showFloatingMenu(display, icon)
        }

        // Drag-to-lookup: OCR + dictionary popup while dragging
        val popup = WordLookupPopup(displayCtx, wm)
        val controller = DragLookupController(
            displayId = display.displayId,
            popup = popup
        )
        // Track whether live mode / region overlay were active when drag started
        var liveWasPausedForPopup = false
        var overlayHiddenForDrag = false

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

        popup.onDismiss = {
            controller.onPopupDismissed()
            // Resume only if drag is finished (not mid-drag word change).
            // If the user is still dragging, resumption waits for onDragEnd.
            if (!icon.inDragMode) {
                restoreRegionOverlay()
                resumeLiveMode()
            }
        }
        // Called before transitioning to Anki review — prevents popup.onDismiss
        // from resuming live mode (the Anki view should handle that instead).
        controller.onTransitioningToAnki = {
            liveWasPausedForPopup = false
            overlayHiddenForDrag = false
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
        icon.onDragEnd = {
            val popupShowing = controller.onDragEnd()
            // If no popup visible on lift, restore immediately
            if (!popupShowing) {
                restoreRegionOverlay()
                resumeLiveMode()
            }
            popupShowing
        }
        icon.onHoldCancel = { CaptureService.instance?.holdCancel() }
        icon.onHoldStart  = { CaptureService.instance?.holdStart() }
        icon.onHoldEnd    = { CaptureService.instance?.holdEnd() }
        icon.onAnyTouch   = { DimController.notifyInteraction() }
        dragLookupController = controller

        try {
            wm.addView(icon, params)
            // Set position after addView so the icon can query its own window bounds
            icon.setPosition(prefs.overlayIconEdge, prefs.overlayIconFraction)
            wm.updateViewLayout(icon, params)
            floatingIconWm = wm
            floatingIcon = icon
            floatingIconDisplayId = display.displayId
        } catch (e: Exception) {
            Log.e(TAG, "showFloatingIcon: addView failed", e)
        }
    }

    /** Remove and re-add the floating icon so it draws above newly added overlays. */
    private fun bringFloatingIconToFront() {
        val icon = floatingIcon ?: return
        val wm = floatingIconWm ?: return
        // Never re-add the icon while it's being dragged — removing it
        // mid-drag breaks the touch event sequence and freezes the icon.
        if (icon.inDragMode) return
        try {
            wm.removeView(icon)
            wm.addView(icon, icon.params)
        } catch (_: Exception) {}
    }

    fun hideFloatingIcon(reason: String = "unspecified") {
        Log.i(TAG, "hideFloatingIcon: $reason")
        dragLookupController?.destroy()
        dragLookupController = null
        floatingIcon?.destroy()
        try { floatingIcon?.let { floatingIconWm?.removeView(it) } } catch (_: Exception) {}
        floatingIcon = null
        floatingIconWm = null
        floatingIconDisplayId = -1
    }

    // ── Floating icon menu ────────────────────────────────────────────────

    private fun showFloatingMenu(display: Display, icon: FloatingOverlayIcon) {
        dismissFloatingMenu()
        val wm = createDisplayContext(display).getSystemService(WindowManager::class.java) ?: return
        val screenSize = getDisplaySize(display)
        val themeRes = when (Prefs(this).themeIndex) {
            1    -> R.style.Theme_PlayTranslate_White
            2    -> R.style.Theme_PlayTranslate_Rainbow
            3    -> R.style.Theme_PlayTranslate_Purple
            else -> R.style.Theme_PlayTranslate
        }
        val themedCtx = android.view.ContextThemeWrapper(createDisplayContext(display), themeRes)
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
                val prefs = Prefs(this)
                val willBeInAppOnly = prefs.hideGameOverlays && !Prefs.isSingleScreen(this)
                if (willBeInAppOnly) {
                    // Dual screen + hide overlays: bring app to foreground for In-App Only
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
        menu.activeRegion = CaptureService.instance?.activeRegion
        menu.onRegionSelected = { region ->
            dismissFloatingMenu()
            CaptureService.instance?.configureOverride(region)
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
            // Reset to full screen
            val prefs = Prefs(this)
            prefs.selectedRegionId = Prefs.DEFAULT_REGION_LIST[0].id
            val svc = CaptureService.instance
            if (svc != null && svc.isConfigured) {
                val entry = Prefs.DEFAULT_REGION_LIST[0]
                svc.configureSaved(displayId = prefs.captureDisplayId, region = entry)
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

        wm.addView(menu, params)
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
        try { floatingMenu?.let { floatingMenuWm?.removeView(it) } } catch (_: Exception) {}
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

        val builder = OverlayAlert.Builder(displayCtx, overlayWm)

        if (!Prefs.hasMultipleDisplays(this)) {
            builder.setTitle("Disable $appName?")
                .setMessage("Re-enable in $appName app")
            if (!alreadyCompact) {
                builder.addButton("Minimize Icon", OverlayColors.accent(this)) {
                    prefs.compactOverlayIcon = true
                    hideFloatingIcon("confirm_minimize_single")
                    ensureFloatingIcon()
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
                        ensureFloatingIcon()
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

        // Pre-populate with current active region (or default if full-screen)
        val currentRegion = CaptureService.instance?.activeRegion
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
                CaptureService.instance?.configureOverride(drawnRegion)
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

        wm.addView(bar, barParams)
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
        wm.addView(label, labelParams)
        regionEditorLabelWm = wm
        regionEditorLabel = label
    }

    private fun hideRegionEditor() {
        hideRegionDragOverlay()
        try { regionEditorBar?.let { regionEditorBarWm?.removeView(it) } } catch (_: Exception) {}
        regionEditorBar = null
        regionEditorBarWm = null
        try { regionEditorLabel?.let { regionEditorLabelWm?.removeView(it) } } catch (_: Exception) {}
        regionEditorLabel = null
        regionEditorLabelWm = null
        CaptureService.instance?.holdActive = false
    }

    /**
     * Returns the floating icon's bounding rect in screen coordinates, or null
     * if the icon is not showing. Used by CaptureService to black out the icon
     * area before OCR so it doesn't interfere with text recognition.
     */
    fun getFloatingIconRect(): android.graphics.Rect? {
        val icon = floatingIcon ?: return null
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
            val hadPopup = dragLookupController?.isPopupShowing == true
            dragLookupController?.dismiss()
            if (!svc.isConfigured) {
                val prefs = Prefs(this)
                val entry = prefs.getSelectedRegion()
                svc.configureSaved(
                    displayId = prefs.captureDisplayId,
                    region    = entry
                )
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

    /** Returns the capture display (game screen), or the only display on single-screen. */
    private fun findIconDisplay(prefs: Prefs): Display? {
        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val displays = dm.displays
        if (displays.size <= 1) return displays.firstOrNull()
        return dm.getDisplay(prefs.captureDisplayId) ?: displays.firstOrNull()
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

        /** Non-null while the service is connected (i.e. user has it enabled). */
        var instance: PlayTranslateAccessibilityService? = null

        val isEnabled: Boolean get() = instance != null
    }
}
