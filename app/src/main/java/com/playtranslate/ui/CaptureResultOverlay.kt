package com.playtranslate.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import com.playtranslate.CaptureService
import com.playtranslate.CaptureSession
import com.playtranslate.CaptureState
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.language.SourceLanguageEngines
import com.playtranslate.model.TextSegments
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
import kotlinx.coroutines.withContext
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
    private var animatingOut = false

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

    // Tap-a-word → definition (display + speak; the panel lens has no Anki/open).
    private var wordSpans: List<Triple<IntRange, String, String>> = emptyList()
    private var wordLens: MagnifierLens? = null
    private var wordSpeakChip: LensSpeakChip? = null

    // In-place edit (the panel window goes focusable so the IME shows over the game).
    private var windowParams: WindowManager.LayoutParams? = null
    private var lastResult: TranslationResult? = null
    /** Bumped per edit commit so an out-of-order translateOnce can't roll back a
     *  newer edit. */
    private var editGeneration = 0
    private val editContainer = LinearLayout(ctx)
    private val editText = EditText(ctx)

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
        editText.apply {
            setTextColor(ctx.themeColor(R.attr.ptText))
            textSize = 18f
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            val p = dp(12)
            setPadding(p, p, p, p)
        }
        val doneBtn = Button(ctx).apply {
            text = "✓"
            setOnClickListener { commitEdit() }
        }
        editContainer.apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ctx.themeColor(R.attr.ptBg))
            visibility = View.GONE
            addView(editText, LinearLayout.LayoutParams(MATCH, 0, 1f))
            addView(doneBtn, LinearLayout.LayoutParams(WRAP, WRAP).apply {
                gravity = Gravity.END
                marginEnd = dp(12)
                bottomMargin = dp(8)
            })
        }
        body.apply {
            addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(
                statusText,
                FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER),
            )
            addView(editContainer, FrameLayout.LayoutParams(MATCH, MATCH))
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
        // Park above the top edge; the entrance animation (below) drops it in.
        panel.translationY = -panelHeightPx.toFloat()

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
        windowParams = lp
        overlayHost.addOverlayWindow(root, wm, lp, displayId)
        // Bounce in from the top (cf. the floating icon's edge snap, but with a
        // short overshoot instead of a plain decelerate).
        panel.animate()
            .translationY(0f)
            .setDuration(ENTER_DURATION_MS)
            .setInterpolator(OvershootInterpolator(ENTER_OVERSHOOT))
            .start()
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
        dismissWordLens()
        sessionJob?.cancel()
        binder?.release()
        try { overlayHost.removeOverlayWindow(root) } catch (_: Exception) {}
        scope.cancel()
        onDismiss?.invoke()
    }

    /** Slide the panel up off the top edge, then remove it — so tap-outside and
     *  swipe/fling-up dismissals animate out instead of vanishing. Other paths
     *  (Cancelled, supersede, teardown) call [dismiss] directly for immediate
     *  removal. */
    private fun animateOutAndDismiss() {
        if (dismissed || animatingOut) return
        animatingOut = true
        dismissWordLens()
        panel.animate()
            .translationY(-(panelHeightPx.toFloat()))
            .setDuration(EXIT_DURATION_MS)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction { dismiss() }
            .start()
    }

    // ── State rendering ──────────────────────────────────────────────────

    private fun setStatus(message: String) {
        statusText.text = message
        statusText.visibility = View.VISIBLE
        scroll.visibility = View.GONE
    }

    private fun bindResult(result: TranslationResult) {
        val b = binder ?: return
        lastResult = result
        statusText.visibility = View.GONE
        scroll.visibility = View.VISIBLE
        b.bindResult(result)
        b.applyFurigana()
        // Tap-a-word → definition: tokenize the source so taps resolve to spans.
        // Readings refine on tap via the resolver, so an empty lookupToReading
        // (no full word-list pipeline here) only loses the rare homograph hint.
        b.tvOriginal.onTapAtOffset = { offset -> onSourceTapped(offset) }
        refreshWordSpans(result.originalText)
        scroll.post {
            if (dismissed) return@post
            val h = body.height.takeIf { it > 0 } ?: return@post
            // Side-by-side: each column owns ~the body height; stacked: split it.
            val target = if (contentRow.orientation == LinearLayout.HORIZONTAL) h else h / 2
            b.fitText(translationTargetPx = target, sourceTargetPx = target)
        }
    }

    /** (Re)tokenize the source so taps resolve to spans against the displayed
     *  text. Called for a fresh result and after an in-place edit. */
    private fun refreshWordSpans(originalText: String) {
        scope.launch {
            val engine = SourceLanguageEngines.get(ctx.applicationContext, prefs.sourceLangId)
            val tokens = withContext(Dispatchers.IO) { engine.tokenize(originalText) }
            if (dismissed) return@launch
            val b = binder ?: return@launch
            wordSpans = SourceWordLookup.computeSpans(b.displayedSourceText(), tokens, emptyMap())
        }
    }

    /** Resolve the tapped word and show a display+speak lens over the game,
     *  anchored on the tapped line (no Anki / open-detail — see [showAnkiChip]). */
    private fun onSourceTapped(offset: Int) {
        val span = wordSpans.firstOrNull { offset in it.first } ?: return
        val b = binder ?: return
        val tv = b.tvOriginal
        scope.launch {
            try {
                val resolved = SourceWordLookup.resolve(ctx.applicationContext, span.second, span.third)
                if (dismissed) return@launch
                val layout = tv.layout ?: return@launch
                val lineStart = layout.getLineForOffset(span.first.first)
                val xStart = layout.getPrimaryHorizontal(span.first.first)
                val xEnd = layout.getPrimaryHorizontal(span.first.last + 1)
                val wordCenterX = ((xStart + xEnd) / 2).toInt() + tv.paddingLeft
                val lineTop = layout.getLineTop(lineStart) - tv.scrollY + tv.paddingTop
                val lineH = layout.getLineBottom(lineStart) - layout.getLineTop(lineStart)
                val loc = IntArray(2)
                tv.getLocationOnScreen(loc)
                val screenX = loc[0] + wordCenterX
                val anchorY = loc[1] + lineTop
                dismissWordLens()
                val lens = MagnifierLens(ctx, wm, displayId, overlayHost, showAnkiChip = false)
                lens.onDismiss = {
                    binder?.setWordHighlight(null)
                    wordSpeakChip?.release()
                    wordSpeakChip = null
                    wordLens = null
                }
                wordLens = lens
                wordSpeakChip = LensSpeakChip(
                    lens, scope,
                    TtsAlertTarget.Overlay(ctx, overlayHost, wm, displayId),
                ) { LensSpeakChip.Request(resolved.word, prefs.sourceLangId, reading = resolved.reading) }
                b.setWordHighlight(span.first)
                lens.show(screenX, anchorY, screenW, screenH, anchorHeight = lineH)
                lens.setDefinitions(resolved.data, resolved.label)
                lens.makeInteractive()
            } catch (_: Exception) {}
        }
    }

    private fun dismissWordLens() {
        wordLens?.dismiss()
    }

    /** Edit the source in place: flip the panel window focusable, show an inline
     *  EditText over the game, and bring the IME up — no app switch. (IME over an
     *  overlay window is OEM-variable; this path needs on-device validation.) */
    private fun startInPlaceEdit() {
        if (editContainer.visibility == View.VISIBLE) return
        val current = lastResult?.originalText ?: binder?.displayedSourceText() ?: return
        dismissWordLens()
        editText.setText(current)
        editText.setSelection(editText.text.length)
        editContainer.visibility = View.VISIBLE
        setWindowFocusable(true)
        editText.requestFocus()
        ctx.getSystemService(InputMethodManager::class.java)
            ?.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
    }

    /** Commit the edit: hide the IME, restore the non-focusable window, then
     *  re-translate (text-only — no screenshot, so no clean-capture blanking) and
     *  re-render. translateOnce returns a GroupTranslation, not a session result,
     *  so the new target is composed here rather than flowing through observe(). */
    private fun commitEdit() {
        val newText = editText.text?.toString().orEmpty()
        ctx.getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(editText.windowToken, 0)
        editContainer.visibility = View.GONE
        setWindowFocusable(false)
        val prev = lastResult ?: return
        if (newText.isBlank() || newText == prev.originalText) return
        val b = binder ?: return
        // Commit the edit to state IMMEDIATELY (blank translation = retranslating)
        // so re-opening Edit reads the new text, then gate the async translation by
        // generation so an older translateOnce can't roll back a newer edit.
        val edited = prev.copy(
            originalText = newText,
            segments = TextSegments.ofText(newText),
            translatedText = "",
            note = null,
            backendDisplayName = null,
        )
        lastResult = edited
        val gen = ++editGeneration
        b.setSourceSegments(edited.segments)
        b.applyFurigana()
        b.setTargetTranslatingPlaceholder()
        refreshWordSpans(newText)
        scope.launch {
            val svc = CaptureService.instance ?: return@launch
            val gt = svc.translateOnce(newText)
            if (dismissed || gen != editGeneration) return@launch
            bindResult(
                edited.copy(
                    translatedText = gt.text,
                    note = gt.note,
                    backendDisplayName = gt.backendDisplayName,
                )
            )
        }
    }

    private fun setWindowFocusable(focusable: Boolean) {
        val lp = windowParams ?: return
        lp.flags = if (focusable) {
            lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        lp.softInputMode = if (focusable) {
            WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        } else {
            WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
        }
        try { wm.updateViewLayout(root, lp) } catch (_: Exception) {}
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
     *  area) dismisses and is consumed so it doesn't leak to the game.
     *  (Rotation dismisses too, driven by the controller's display listener.) */
    private inner class CaptureResultRoot(c: Context) : FrameLayout(c) {
        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                val panelBottom = panel.bottom + panel.translationY
                if (ev.y >= panelBottom) {
                    animateOutAndDismiss()
                    return true
                }
            }
            if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                animateOutAndDismiss()
                return true
            }
            return super.dispatchTouchEvent(ev)
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
                        animateOutAndDismiss()
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
        const val ENTER_DURATION_MS = 280L
        const val EXIT_DURATION_MS = 200L
        /** OvershootInterpolator tension — modest, for a short bounce-in. */
        const val ENTER_OVERSHOOT = 1.5f
    }
}
