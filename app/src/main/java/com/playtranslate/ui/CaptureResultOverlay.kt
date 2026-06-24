package com.playtranslate.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import com.playtranslate.CaptureSession
import com.playtranslate.CaptureState
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.model.TranslationResult
import com.playtranslate.overlay.OverlayHost
import com.playtranslate.overlayThemedContext
import com.playtranslate.themeColor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * The over-game capture result panel: a top-anchored sheet (default 40% of the
 * screen) showing the source section on the left and the target section on the
 * right (stacked when too narrow), drawn over the game without leaving it.
 *
 * Built fresh on [OverlayHost] (the shared window primitive). Mirrors the three
 * load-bearing patterns from [MagnifierLens]: a FIXED full-screen window whose
 * visible child is resized via layout — never the window, which would flash at
 * the gravity anchor — and a transparent full-screen root that catches off-panel
 * taps to dismiss. The drag-handle resize and the swipe/fling-up dismiss are
 * net-new (the lens grows programmatically and has neither).
 *
 * Sections come from the shared [TranslationSectionBinder], so they render and
 * behave exactly like the in-app results page. The words section, Clear, and the
 * Anki pill are intentionally excluded.
 */
@SuppressLint("ClickableViewAccessibility")
class CaptureResultOverlay(
    rawCtx: Context,
    private val wm: WindowManager,
    private val displayId: Int,
    private val overlayHost: OverlayHost,
) {
    private val ctx = overlayThemedContext(rawCtx)
    private val density = ctx.resources.displayMetrics.density
    private val prefs = Prefs(ctx)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val touchSlop = ViewConfiguration.get(ctx).scaledTouchSlop

    /** Invoked once, on any dismissal path. */
    var onDismiss: (() -> Unit)? = null

    private var sessionJob: Job? = null
    private var dismissed = false

    private var screenW = 0
    private var screenH = 0
    private var panelHeightPx = 0

    private val root = CaptureResultRoot(ctx)
    private val panel = TopSheetPanel(ctx)
    private val body = FrameLayout(ctx)
    private val statusText = TextView(ctx)
    private val scroll = NestedScrollView(ctx)
    private val contentRow = LinearLayout(ctx)
    private val handle = HandleView(ctx)

    private var binder: TranslationSectionBinder? = null

    init {
        statusText.apply {
            gravity = Gravity.CENTER
            setTextColor(ctx.themeColor(R.attr.ptTextHint))
            textSize = 18f
            val pad = dp(24)
            setPadding(pad, pad, pad, pad)
        }
        scroll.apply {
            isFillViewport = true
            visibility = View.GONE
            addView(
                contentRow,
                FrameLayout.LayoutParams(MATCH, WRAP),
            )
        }
        body.apply {
            addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(
                statusText,
                FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER),
            )
        }
        panel.apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ctx.themeColor(R.attr.ptBg))
            addView(body, LinearLayout.LayoutParams(MATCH, 0, 1f))
            addView(handle, LinearLayout.LayoutParams(MATCH, dp(HANDLE_HEIGHT_DP)))
        }
        root.addView(panel, FrameLayout.LayoutParams(MATCH, 0, Gravity.TOP))
        wireResizeHandle()
    }

    // ── Public API ───────────────────────────────────────────────────────

    /** Size + add the window, lay out the sections responsively, and show the
     *  initial status placeholder. Call once. */
    fun show(screenW: Int, screenH: Int) {
        if (dismissed) return
        this.screenW = screenW
        this.screenH = screenH
        panelHeightPx = CaptureResultGeometry.defaultPanelHeight(screenH)
        (panel.layoutParams as FrameLayout.LayoutParams).height = panelHeightPx

        val sideBySide = CaptureResultGeometry.shouldUseSideBySide(
            screenW, dp(1), (CaptureResultGeometry.SIDE_BY_SIDE_FALLBACK_SECTION_DP * density).toInt(),
        )
        buildContent(sideBySide)

        val b = TranslationSectionBinder(
            panel, ctx, prefs, scope,
            TtsAlertTarget.Overlay(ctx, overlayHost, wm, displayId),
        )
        b.setupSectionButtons(onEdit = { startInPlaceEdit() })
        binder = b

        val lp = WindowManager.LayoutParams(
            screenW, screenH,
            0, // type stamped by OverlayHost
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
        overlayHost.addOverlayWindow(root, wm, lp, displayId)
    }

    /** Collect the one-shot capture session and drive the panel. The collector is
     *  cancelled on dismiss (no Activity lifecycle to auto-cancel it). */
    fun observe(session: CaptureSession) {
        sessionJob?.cancel()
        sessionJob = scope.launch {
            session.state.collect { state ->
                when (state) {
                    is CaptureState.InProgress -> setStatus(state.message)
                    is CaptureState.Done -> bindResult(state.result)
                    is CaptureState.NoText -> setStatus(state.message)
                    is CaptureState.Failed -> setStatus(state.message)
                    CaptureState.Cancelled -> dismiss()
                }
            }
        }
    }

    fun dismiss() {
        if (dismissed) return
        dismissed = true
        sessionJob?.cancel()
        binder?.release()
        try { overlayHost.removeOverlayWindow(root) } catch (_: Exception) {}
        scope.cancel()
        onDismiss?.invoke()
    }

    // ── State rendering ──────────────────────────────────────────────────

    private fun setStatus(message: String) {
        statusText.text = message
        statusText.visibility = View.VISIBLE
        scroll.visibility = View.GONE
    }

    private fun bindResult(result: TranslationResult) {
        val b = binder ?: return
        statusText.visibility = View.GONE
        scroll.visibility = View.VISIBLE
        b.bindResult(result)
        b.applyFurigana()
        scroll.post {
            if (dismissed) return@post
            val h = body.height.takeIf { it > 0 } ?: return@post
            // Side-by-side: each column owns ~the body height; stacked: split it.
            val target = if (contentRow.orientation == LinearLayout.HORIZONTAL) h else h / 2
            b.fitText(translationTargetPx = target, sourceTargetPx = target)
        }
    }

    /** Filled in by the in-place edit step. */
    private fun startInPlaceEdit() {
        // Wired in the edit step: flips the panel focusable + inline EditText.
    }

    // ── Responsive content ───────────────────────────────────────────────

    private fun buildContent(sideBySide: Boolean) {
        contentRow.removeAllViews()
        val hPad = dp(SECTION_H_PAD_DP)
        if (sideBySide) {
            contentRow.orientation = LinearLayout.HORIZONTAL
            contentRow.setPadding(hPad, 0, hPad, 0)
            val sourceCol = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            val targetCol = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            inflater().inflate(R.layout.section_source, sourceCol, true)
            inflater().inflate(R.layout.section_target, targetCol, true)
            contentRow.addView(sourceCol, LinearLayout.LayoutParams(0, WRAP, 1f))
            contentRow.addView(verticalDivider())
            contentRow.addView(targetCol, LinearLayout.LayoutParams(0, WRAP, 1f))
        } else {
            contentRow.orientation = LinearLayout.VERTICAL
            contentRow.setPadding(hPad, 0, hPad, dp(24))
            inflater().inflate(R.layout.section_source, contentRow, true)
            contentRow.addView(horizontalDivider())
            inflater().inflate(R.layout.section_target, contentRow, true)
        }
    }

    private fun verticalDivider(): View = View(ctx).apply {
        setBackgroundColor(ctx.themeColor(R.attr.ptDivider))
        layoutParams = LinearLayout.LayoutParams(dp(1), MATCH).apply {
            marginStart = dp(8)
            marginEnd = dp(8)
            topMargin = dp(8)
            bottomMargin = dp(8)
        }
    }

    private fun horizontalDivider(): View = View(ctx).apply {
        setBackgroundColor(ctx.themeColor(R.attr.ptDivider))
        layoutParams = LinearLayout.LayoutParams(MATCH, dp(1)).apply {
            topMargin = dp(8)
            bottomMargin = dp(8)
        }
    }

    private fun inflater() = android.view.LayoutInflater.from(ctx)

    // ── Resize handle (drag to grow/shrink; up-fling dismisses) ──────────

    private var resizeStartRawY = 0f
    private var resizeStartHeight = 0
    private var resizeTracker: VelocityTracker? = null

    private fun wireResizeHandle() {
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    resizeStartRawY = e.rawY
                    resizeStartHeight = panelHeightPx
                    resizeTracker = VelocityTracker.obtain().also { it.addMovement(e) }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    resizeTracker?.addMovement(e)
                    val dy = (e.rawY - resizeStartRawY).toInt()
                    setPanelHeight(CaptureResultGeometry.clampPanelHeight(resizeStartHeight + dy, screenH))
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val vy = resizeTracker?.let {
                        it.addMovement(e); it.computeCurrentVelocity(1000); it.yVelocity
                    } ?: 0f
                    resizeTracker?.recycle()
                    resizeTracker = null
                    if (vy < -FLING_DISMISS_VEL) dismiss()
                    true
                }
                else -> false
            }
        }
    }

    private fun setPanelHeight(px: Int) {
        panelHeightPx = px
        (panel.layoutParams as FrameLayout.LayoutParams).height = px
        panel.requestLayout()
    }

    // ── Custom views ─────────────────────────────────────────────────────

    /** Full-screen transparent host. A DOWN below the panel (the visible game
     *  area) dismisses and is consumed so it doesn't leak to the game. Rotation
     *  dismisses (v1) — the responsive tree differs per orientation. */
    private inner class CaptureResultRoot(c: Context) : FrameLayout(c) {
        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                val panelBottom = panel.bottom + panel.translationY
                if (ev.y >= panelBottom) {
                    dismiss()
                    return true
                }
            }
            if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                dismiss()
                return true
            }
            return super.dispatchTouchEvent(ev)
        }

        override fun onConfigurationChanged(newConfig: Configuration?) {
            super.onConfigurationChanged(newConfig)
            dismiss()
        }
    }

    /** The visible top sheet. When the content fits (no inner scroll), a vertical
     *  up-drag/fling on the body dismisses (swipe-to-dismiss). When the content
     *  is scrollable it scrolls instead, and dismissal is via the handle fling or
     *  a tap outside — the top-sheet rule that keeps scroll vs dismiss
     *  unambiguous. Drags starting on the handle are left to the resize listener. */
    private inner class TopSheetPanel(c: Context) : LinearLayout(c) {
        private var downX = 0f
        private var downY = 0f
        private var downRawY = 0f
        private var dragging = false
        private var dragTracker: VelocityTracker? = null

        private fun contentScrollable() =
            scroll.canScrollVertically(1) || scroll.canScrollVertically(-1)

        private fun inHandle(y: Float) = y >= body.bottom

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.x; downY = ev.y; downRawY = ev.rawY; dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (dragging) return true
                    if (inHandle(downY) || contentScrollable()) return false
                    val dy = ev.y - downY
                    val dx = ev.x - downX
                    if (abs(dy) > touchSlop && abs(dy) > abs(dx)) {
                        dragging = true
                        dragTracker = VelocityTracker.obtain().also { it.addMovement(ev) }
                        return true
                    }
                }
            }
            return dragging
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging) return false
                    dragTracker?.addMovement(ev)
                    // Up only — this is a dismiss gesture, not a reposition.
                    translationY = minOf(0f, ev.rawY - downRawY)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!dragging) return false
                    val vy = dragTracker?.let {
                        it.addMovement(ev); it.computeCurrentVelocity(1000); it.yVelocity
                    } ?: 0f
                    dragTracker?.recycle(); dragTracker = null
                    dragging = false
                    if (CaptureResultGeometry.shouldDismissFromDrag(
                            translationY, vy, DISMISS_DISTANCE_DP * density, FLING_DISMISS_VEL,
                        )
                    ) {
                        animate().translationY(-(panelHeightPx.toFloat()))
                            .setDuration(150).withEndAction { dismiss() }.start()
                    } else {
                        animate().translationY(0f).setDuration(150).start()
                    }
                }
            }
            return dragging || super.onTouchEvent(ev)
        }
    }

    /** A centered grab-pill at the bottom of the panel. */
    private inner class HandleView(c: Context) : View(c) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ctx.themeColor(R.attr.ptDivider)
        }
        private val rect = RectF()
        init { setWillNotDraw(false) }
        override fun onDraw(canvas: Canvas) {
            val w = dp(36).toFloat()
            val h = dp(4).toFloat()
            val left = (width - w) / 2f
            val top = (height - h) / 2f
            rect.set(left, top, left + w, top + h)
            canvas.drawRoundRect(rect, h / 2f, h / 2f, paint)
        }
    }

    private fun dp(v: Int): Int = (v * density).toInt()

    private companion object {
        const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
        const val HANDLE_HEIGHT_DP = 20
        const val SECTION_H_PAD_DP = 12
        const val DISMISS_DISTANCE_DP = 64f
        /** px/s; a deliberate up-fling (a notch above FloatingOverlayIcon's 600
         *  for a small icon, since the panel wants intent). */
        const val FLING_DISMISS_VEL = 1000f
    }
}
