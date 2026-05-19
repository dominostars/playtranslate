package com.playtranslate

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.playtranslate.overlay.OverlayHost
import com.playtranslate.ui.RegionDragView

/**
 * Owns the game-screen region overlays: the region picker preview, the brief
 * capture-region indicator, the region drag overlay, and the single-screen
 * region editor.
 *
 * Extracted from [OverlayUiController] so that controller stays focused on the
 * floating icon, menu and translation overlays. Backend-specific concerns reach
 * this class only through its constructor lambdas.
 *
 * Main-thread only.
 */
class RegionOverlayController(
    private val context: Context,
    private val overlayHost: OverlayHost,
    private val anyIconInDragMode: () -> Boolean,
    private val bringIconToFront: (displayId: Int) -> Unit,
    private val hideTranslationOverlay: () -> Unit,
    private val onRegionSelected: (displayId: Int, region: RegionEntry) -> Unit,
) {

    private var dragView: RegionDragView? = null

    /** True when the region drag editor overlay is showing. */
    val isRegionEditorActive: Boolean get() = dragView != null

    private var regionEditorBar: View? = null
    private var regionEditorLabel: View? = null

    private var regionIndicatorView: View? = null
    private var regionIndicatorPersistent = false
    private var regionIndicatorDisplayId: Int = -1
    private var regionIndicatorUpdater: ((RegionEntry) -> Unit)? = null
    private val regionIndicatorHandler = Handler(Looper.getMainLooper())

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
        bringIconToFront(display.displayId)
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
            }
            // Label drop shadow — a blurred round-rect drawn behind the pill.
            // Deliberately not Paint.setShadowLayer: that is ignored for
            // non-text draws on a hardware-accelerated canvas, and this view
            // must NOT be software-layered — a full-display software layer
            // exceeds the device's layer-size cap, after which the view
            // silently isn't drawn at all. BlurMaskFilter renders fine on the
            // hardware canvas (minSdk 30).
            private val labelShadowPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.argb(140, 0, 0, 0)
                style = android.graphics.Paint.Style.FILL
                maskFilter = android.graphics.BlurMaskFilter(7.8f * dp, android.graphics.BlurMaskFilter.Blur.NORMAL)
            }
            private val labelShadowDy = 2.6f * dp
            private val labelPadH = 10f * dp
            private val labelPadV = 4f * dp
            private val labelRadius = 6f * dp
            private val labelMargin = 8f * dp

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

                // Pill drop shadow, then the pill background on top
                canvas.drawRoundRect(
                    cx - pillW / 2, labelTop + labelShadowDy,
                    cx + pillW / 2, labelBottom + labelShadowDy,
                    labelRadius, labelRadius, labelShadowPaint
                )
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
            overlayHost.windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        overlayHost.addOverlayWindow(view, wm, params, display.displayId)
        regionIndicatorView = view
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
        regionIndicatorPersistent = false
        regionIndicatorDisplayId = -1
        regionIndicatorUpdater = null
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
            overlayHost.windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        overlayHost.addOverlayWindow(view, wm, params, display.displayId)
        dragView = view
    }

    fun hideRegionDragOverlay() {
        dragView?.let { overlayHost.removeOverlayWindow(it) }
        dragView = null
    }

    fun getDragRegion(): RegionEntry {
        val v = dragView ?: return RegionEntry("", 0.25f, 0.75f, 0.25f, 0.75f)
        return RegionEntry("", v.topFraction, v.bottomFraction, v.leftFraction, v.rightFraction)
    }

    // ── Single-screen region editor ──────────────────────────────────────

    fun showRegionEditor(display: Display) {
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
                    onRegionSelected(display.displayId, drawnRegion)
                }
            }
        }

        bar.addView(cancelBtn)
        bar.addView(useBtn)

        val barParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayHost.windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (32 * dp).toInt()
        }

        overlayHost.addOverlayWindow(bar, wm, barParams, display.displayId)
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
        val screenW = context.createDisplayContext(display).displaySizePx().x
        val maxLabelW = screenW - (32 * dp).toInt()
        label.setSingleLine(true)
        label.measure(
            View.MeasureSpec.makeMeasureSpec(maxLabelW, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val labelParams = WindowManager.LayoutParams(
            label.measuredWidth,
            label.measuredHeight,
            overlayHost.windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (16 * dp).toInt()
        }
        overlayHost.addOverlayWindow(label, wm, labelParams, display.displayId)
        regionEditorLabel = label
    }

    fun hideRegionEditor() {
        hideRegionDragOverlay()
        regionEditorBar?.let { overlayHost.removeOverlayWindow(it) }
        regionEditorBar = null
        regionEditorLabel?.let { overlayHost.removeOverlayWindow(it) }
        regionEditorLabel = null
        CaptureService.instance?.holdActive = false
    }

    // ── Lifecycle ────────────────────────────────────────────────────────

    /** Hide the region indicator for a drag-lookup gesture, if one is visible.
     *  Returns true when it hid one — caller restores via [restoreIndicatorAfterDrag]. */
    fun hideIndicatorForDrag(): Boolean {
        val v = regionIndicatorView ?: return false
        if (v.visibility != View.VISIBLE) return false
        v.visibility = View.INVISIBLE
        return true
    }

    /** Restore an indicator hidden by [hideIndicatorForDrag]. */
    fun restoreIndicatorAfterDrag() { regionIndicatorView?.visibility = View.VISIBLE }

    /** Tear down every region overlay. */
    fun hideAll() {
        hideRegionOverlay()
        hideRegionIndicator(force = true)
        hideRegionEditor()
        hideRegionDragOverlay()
    }

    /** Full teardown — [hideAll] plus handler cleanup. */
    fun destroy() {
        hideAll()
        regionIndicatorHandler.removeCallbacksAndMessages(null)
    }
}
