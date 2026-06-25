package com.playtranslate.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
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
    private val cornerRadiusPx = ctx.resources.getDimension(R.dimen.pt_radius) * CORNER_RADIUS_MULT
    private val prefs = Prefs(ctx)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val touchSlop = ViewConfiguration.get(ctx).scaledTouchSlop

    /** Invoked once, on any dismissal path. */
    var onDismiss: (() -> Unit)? = null

    private var sessionJob: Job? = null
    /** The active service one-shot session (OCR + translate). Held so dismissal
     *  cancels the headless service work, not just our UI collector. */
    private var captureSession: CaptureSession? = null
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

    // Side-by-side column collapse: hiding a section shrinks it to a button-wide
    // strip (rotated label) and the other section fills the freed width.
    private var isSideBySide = false
    private var sourceColumn: SectionColumn? = null
    private var targetColumn: SectionColumn? = null

    // Auto-height + text fit. The card frame's inset (rounded-corner overlap +
    // stroke) is constant, so it's measured once while the cards are still wrap;
    // the header + in-card overhead (which includes the target's note row) are
    // read LIVE each fit. Plus the load-time grow-to-fit animator.
    private var sourceCardInsetPx = 0
    private var targetCardInsetPx = 0
    // Stacked only: the non-card vertical chrome (both headers + divider + bottom
    // padding), measured once while the cards still wrap.
    private var stackedNonCardPx = 0
    private var cardInsetMeasured = false
    private var heightAnimator: ValueAnimator? = null
    // Height that shows all content at max text size — the drag-resize ceiling, so
    // the user can't grow the panel into empty space beyond what the content needs.
    private var maxNeededHeightPx = Int.MAX_VALUE

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
            // The visible sheet: a ptBg fill + a thin ptDivider boundary (the same
            // color as the section divider), clipped to a square-top / rounded-bottom
            // outline so the content is cropped to the panel's rounded edges. The
            // sheet lives on the body, not the panel, so the handle bar below stays
            // transparent — and the pill (a sibling of the body) isn't clipped away.
            // A uniformly-rounded sheet whose TOP (corners + stroke) is lifted above
            // the view by the corner radius — so, like the clip outline, only the
            // bottom corners + sides show and there's no boundary line across the top.
            background = InsetDrawable(
                GradientDrawable().apply {
                    setColor(ctx.themeColor(R.attr.ptBg))
                    cornerRadius = cornerRadiusPx
                    setStroke(dp(1), ctx.themeColor(R.attr.ptDivider))
                },
                0, -cornerRadiusPx.toInt(), 0, 0,
            )
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    // Push the rounded rect's top above the view so only the bottom
                    // two corners round (the top edge is flush with the screen).
                    outline.setRoundRect(
                        0, -cornerRadiusPx.toInt(), view.width, view.height, cornerRadiusPx,
                    )
                }
            }
            clipToOutline = true
            addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(
                statusText,
                FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER),
            )
            addView(editContainer, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        panel.apply {
            orientation = LinearLayout.VERTICAL
            addView(body, LinearLayout.LayoutParams(MATCH, 0, 1f))
            addView(handle, LinearLayout.LayoutParams(MATCH, dp(HANDLE_HEIGHT_DP)))
        }
        root.addView(panel, FrameLayout.LayoutParams(MATCH, 0, Gravity.TOP))
    }

    // ── Public API ───────────────────────────────────────────────────────

    /** Size + add the window, lay out the sections responsively, and show the
     *  initial status placeholder. Call once. */
    fun show(screenW: Int, screenH: Int) {
        if (dismissed) return
        this.screenW = screenW
        this.screenH = screenH
        // Load at the minimum (drag-resize floor) height; grow to fit on Done.
        panelHeightPx = CaptureResultGeometry.minPanelHeight(screenH)
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
        b.onSectionVisibilityChanged = {
            applySideBySideCollapse()
            // A section was hidden/shown — grow/shrink the panel to the new content.
            // Two frames: the collapse AND the other column's re-widen must settle
            // before we measure, else we'd size to the stale pre-collapse layout.
            contentRow.post {
                contentRow.post { if (!dismissed && lastResult != null) autoSizeAndFit() }
            }
        }
        // Furigana changes the source's rendered height (async on / sync off) — re-fit.
        b.onSourceTextHeightChanged = { if (!dismissed) autoSizeAndFit() }
        binder = b
        // Reflect any persisted hide prefs (shared with the results page) up front.
        applySideBySideCollapse()

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
        // Ease in from the top — a plain decelerate, no overshoot/bounce.
        panel.animate()
            .translationY(0f)
            .setDuration(ENTER_DURATION_MS)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    /** Collect the one-shot capture session and drive the panel. The collector is
     *  cancelled on dismiss (no Activity lifecycle to auto-cancel it). */
    fun observe(session: CaptureSession) {
        sessionJob?.cancel()
        captureSession?.cancel()
        captureSession = session
        sessionJob = scope.launch {
            session.state.collect { state ->
                when (state) {
                    is CaptureState.InProgress -> setStatus(state.message)
                    // OCR done: show the source now with a "Translating…" placeholder
                    // (blank translatedText renders it); Done fills it in + re-fits.
                    is CaptureState.Translating -> bindResult(
                        TranslationResult(
                            originalText = state.originalText,
                            segments = state.segments,
                            translatedText = "",
                            timestamp = "",
                        ),
                    )
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
        heightAnimator?.cancel()
        dismissWordLens()
        sessionJob?.cancel()
        // Cancel the service-side one-shot job too (not just our collector), so
        // OCR/translation doesn't keep running headless after the panel is gone.
        captureSession?.cancel()
        captureSession = null
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
        // Two frames: the first lays out the freshly-bound content (incl. the
        // translation note row); the second measures + animates against it, so the
        // note's height is reliably counted.
        scroll.post {
            scroll.post {
                if (dismissed) return@post
                autoSizeAndFit()
            }
        }
    }

    /** On the first layout of a fresh result: measure the natural content height
     *  at max text size (the same StaticLayout basis as the fit, so they agree)
     *  and animate the panel to fit it (capped at 50% of screen, floored at min),
     *  smoothly scaling the text alongside. */
    private fun autoSizeAndFit() {
        val b = binder ?: return
        if (body.height <= 0 || contentRow.width <= 0) return
        measureCardInset(b)
        // A section hidden via the eye contributes only its collapsed strip (the
        // side-by-side column shrinks to a button-wide strip) or nothing (stacked),
        // NOT its full text height — so hiding it actually shrinks the panel.
        val naturalContent = if (isSideBySide) {
            val srcNeed = if (prefs.hideOriginalSection) (sourceColumn?.collapsed?.height ?: 0)
                else sideChromeSource(b) + b.sourceTextHeightAtMax()
            val tgtNeed = if (prefs.hideTranslationSection) (targetColumn?.collapsed?.height ?: 0)
                else sideChromeTarget(b) + b.targetTextHeightAtMax()
            maxOf(srcNeed, tgtNeed) + dp(SIDE_BY_SIDE_BOTTOM_BUFFER_DP)
        } else {
            stackedChrome(b) +
                (if (prefs.hideOriginalSection) 0 else b.sourceTextHeightAtMax()) +
                (if (prefs.hideTranslationSection) 0 else b.targetTextHeightAtMax())
        }
        val neededHeight = naturalContent + dp(HANDLE_HEIGHT_DP)
        maxNeededHeightPx = neededHeight.coerceAtLeast(CaptureResultGeometry.minPanelHeight(screenH))
        val target = CaptureResultGeometry.autoPanelHeight(neededHeight, screenH)
        animatePanelHeight(target)
    }

    /** The card frame's inset is constant — measure it ONCE while the cards are
     *  still wrap (after [applyCardFill] pins explicit heights, card − content no
     *  longer reads the inset). The header + content overhead are read live. */
    /** The card frame's inset is constant — measure it ONCE while the cards are
     *  still wrap (after [applyCardFill] pins min-heights, card − content no longer
     *  reads the inset). Same for the stacked non-card chrome. The header + content
     *  overhead are read live. */
    private fun measureCardInset(b: TranslationSectionBinder) {
        if (cardInsetMeasured) return
        sourceCardInsetPx = b.sourceCardInset()
        targetCardInsetPx = b.targetCardInset()
        if (!isSideBySide) {
            stackedNonCardPx = (contentRow.height - b.sourceCardHeight() - b.targetCardHeight())
                .coerceAtLeast(0)
        }
        cardInsetMeasured = true
    }

    // Per-section chrome (the non-text height): header + in-card overhead (padding +
    // the target's note row, read live) + the once-measured card frame inset.
    private fun sideChromeSource(b: TranslationSectionBinder): Int =
        b.sourceHeaderHeight() + b.sourceContentOverhead() + sourceCardInsetPx
    private fun sideChromeTarget(b: TranslationSectionBinder): Int =
        b.targetHeaderHeight() + b.targetContentOverhead() + targetCardInsetPx

    /** Stacked non-text chrome, summed from STABLE parts (the min-height the fill
     *  sets would otherwise inflate a `contentRow.height − text` reading). */
    private fun stackedChrome(b: TranslationSectionBinder): Int =
        stackedNonCardPx + sourceCardInsetPx + targetCardInsetPx +
            b.sourceContentOverhead() + b.targetContentOverhead()

    /** Stacked: split the card area between the two cards in proportion to each
     *  section's content-at-max, so both fill the panel (no gap below) and each
     *  text grows/shrinks to fill its card. */
    private fun stackedCardHeights(b: TranslationSectionBinder, bodyH: Int): Pair<Int, Int> {
        val available = (bodyH - stackedNonCardPx).coerceAtLeast(0)
        // A hidden section claims none of the height, so the visible one fills it.
        val srcRef = if (prefs.hideOriginalSection) 0
            else sourceCardInsetPx + b.sourceContentOverhead() + b.sourceTextHeightAtMax()
        val tgtRef = if (prefs.hideTranslationSection) 0
            else targetCardInsetPx + b.targetContentOverhead() + b.targetTextHeightAtMax()
        val total = (srcRef + tgtRef).coerceAtLeast(1)
        return Pair(available * srcRef / total, available * tgtRef / total)
    }

    /** Fill each card to a MINIMUM height so its background is sized by the view,
     *  not the text — yet a card whose content runs slightly tall grows + scrolls
     *  instead of clipping. Side-by-side: each fills its column (minus header +
     *  bottom buffer). Stacked: the two split the height proportionally. */
    private fun applyCardFill(b: TranslationSectionBinder, bodyH: Int) {
        if (isSideBySide) {
            val buffer = dp(SIDE_BY_SIDE_BOTTOM_BUFFER_DP)
            b.setCardMinHeights(
                (bodyH - b.sourceHeaderHeight() - buffer).coerceAtLeast(0),
                (bodyH - b.targetHeaderHeight() - buffer).coerceAtLeast(0),
            )
        } else {
            val (src, tgt) = stackedCardHeights(b, bodyH)
            b.setCardMinHeights(src, tgt)
        }
    }

    /** Fitted (source, target) text sizes for a body height — each text fills its
     *  card's inner height (the card the fill above sized). */
    private fun fitSizes(b: TranslationSectionBinder, bodyH: Int): Pair<Float, Float> =
        if (isSideBySide) {
            val buffer = dp(SIDE_BY_SIDE_BOTTOM_BUFFER_DP)
            Pair(
                b.sourceSizeFor((bodyH - sideChromeSource(b) - buffer).coerceAtLeast(1)),
                b.targetSizeFor((bodyH - sideChromeTarget(b) - buffer).coerceAtLeast(1)),
            )
        } else {
            val (srcCard, tgtCard) = stackedCardHeights(b, bodyH)
            Pair(
                b.sourceSizeFor((srcCard - sourceCardInsetPx - b.sourceContentOverhead()).coerceAtLeast(1)),
                b.targetSizeFor((tgtCard - targetCardInsetPx - b.targetContentOverhead()).coerceAtLeast(1)),
            )
        }

    /** Size the text to the current panel height (continuous). Called per drag frame. */
    private fun reFitText() {
        val b = binder ?: return
        val bodyH = (panelHeightPx - dp(HANDLE_HEIGHT_DP)).coerceAtLeast(0)
        applyCardFill(b, bodyH)
        val (src, tgt) = fitSizes(b, bodyH)
        b.setSizes(src, tgt)
    }

    /** Grow/shrink the panel to [target], interpolating BOTH the height and the
     *  text size (as a float, between the fitted start/end sizes) so the text
     *  scales smoothly instead of stepping. */
    private fun animatePanelHeight(target: Int) {
        heightAnimator?.cancel()
        val b = binder ?: return
        val startH = panelHeightPx
        val (srcStart, tgtStart) = fitSizes(b, (startH - dp(HANDLE_HEIGHT_DP)).coerceAtLeast(0))
        val (srcEnd, tgtEnd) = fitSizes(b, (target - dp(HANDLE_HEIGHT_DP)).coerceAtLeast(0))
        applyCardFill(b, (startH - dp(HANDLE_HEIGHT_DP)).coerceAtLeast(0))
        b.setSizes(srcStart, tgtStart)
        if (startH == target) return
        heightAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = HEIGHT_DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                if (dismissed) {
                    anim.cancel()
                    return@addUpdateListener
                }
                val f = anim.animatedValue as Float
                val h = (startH + (target - startH) * f).toInt()
                setPanelHeight(h)
                applyCardFill(b, (h - dp(HANDLE_HEIGHT_DP)).coerceAtLeast(0))
                b.setSizes(srcStart + (srcEnd - srcStart) * f, tgtStart + (tgtEnd - tgtStart) * f)
            }
            start()
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
        // The edit replaces the source, so the original capture's pending
        // translation (of the OLD source) is now stale — stop collecting it and
        // cancel the service job so a late Done can't revert this edit. (Our own
        // translateOnce below is still gated by editGeneration.)
        sessionJob?.cancel()
        captureSession?.cancel()
        captureSession = null
        sessionJob = null
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
        isSideBySide = sideBySide
        sourceColumn = null
        targetColumn = null
        val hPad = dp(SECTION_H_PAD_DP)
        if (sideBySide) {
            contentRow.orientation = LinearLayout.HORIZONTAL
            // Bottom padding = the buffer below the filled cards.
            contentRow.setPadding(hPad, 0, hPad, dp(SIDE_BY_SIDE_BOTTOM_BUFFER_DP))
            val source = buildColumn(R.layout.section_source, isSource = true)
            val target = buildColumn(R.layout.section_target, isSource = false)
            sourceColumn = source
            targetColumn = target
            contentRow.addView(source.col, LinearLayout.LayoutParams(0, WRAP, 1f))
            contentRow.addView(verticalDivider())
            contentRow.addView(target.col, LinearLayout.LayoutParams(0, WRAP, 1f))
        } else {
            contentRow.orientation = LinearLayout.VERTICAL
            contentRow.setPadding(hPad, 0, hPad, dp(STACKED_BOTTOM_BUFFER_DP))
            inflater().inflate(R.layout.section_source, contentRow, true)
            setHeaderTop(contentRow.getChildAt(0), HEADER_TOP_DP)
            contentRow.addView(horizontalDivider())
            val targetHeaderIndex = contentRow.childCount
            inflater().inflate(R.layout.section_target, contentRow, true)
            setHeaderTop(contentRow.getChildAt(targetHeaderIndex), HEADER_TOP_DP)
        }
    }

    /** A side-by-side column: the inflated section ([expanded]) plus a hidden
     *  collapsed strip, toggled by [applySideBySideCollapse]. */
    private fun buildColumn(layoutRes: Int, isSource: Boolean): SectionColumn {
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val expanded = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        inflater().inflate(layoutRes, expanded, true)
        setHeaderTop(expanded.getChildAt(0), HEADER_TOP_DP)
        // The card stays wrap here; its height is pinned explicitly each frame by
        // [applyCardFill] (deterministic — a weight/fillViewport chain doesn't
        // reliably shrink the card during a drag).
        val label = VerticalLabel(ctx)
        val collapsed = buildCollapsedStrip(isSource, label)
        col.addView(expanded, LinearLayout.LayoutParams(MATCH, WRAP))
        col.addView(collapsed, LinearLayout.LayoutParams(WRAP, WRAP))
        return SectionColumn(col, expanded, collapsed, label)
    }

    /** The strip shown when a side-by-side section is hidden: an eye button to
     *  restore it, with the section's language name rotated vertically beneath. */
    private fun buildCollapsedStrip(isSource: Boolean, label: VerticalLabel): View {
        val eye = ImageButton(ctx).apply {
            setImageResource(R.drawable.ic_visibility_off)
            val tv = TypedValue()
            ctx.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
            setBackgroundResource(tv.resourceId)
            setColorFilter(ctx.themeColor(R.attr.ptTextMuted))
            contentDescription = ctx.getString(
                if (isSource) R.string.cd_toggle_original_visibility
                else R.string.cd_toggle_translation_visibility,
            )
            setOnClickListener {
                if (isSource) binder?.toggleOriginalHidden() else binder?.toggleTranslationHidden()
            }
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
            addView(eye, LinearLayout.LayoutParams(dp(36), dp(32)).apply { topMargin = dp(8) })
            addView(label, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(8) })
        }
    }

    /** Collapse/expand the side-by-side columns to match the section hide prefs:
     *  a hidden section shrinks to a button-wide strip and the other fills the
     *  freed width. No-op in stacked mode (the binder's card GONE already reflows
     *  there). Source is always the left column and target the right, so
     *  collapsing in place keeps each on its side of the screen. */
    private fun applySideBySideCollapse() {
        if (!isSideBySide) return
        applyColumnState(sourceColumn, prefs.hideOriginalSection, binder?.sourceSectionLabel())
        applyColumnState(targetColumn, prefs.hideTranslationSection, binder?.targetSectionLabel())
        // The re-fit to the new column widths is driven by the caller's
        // autoSizeAndFit (on a section toggle) or the initial bind — not here, so an
        // early collapse (before a result) can't pin card min-heights and poison the
        // one-shot card-inset measurement.
    }

    private fun applyColumnState(column: SectionColumn?, hidden: Boolean, label: String?) {
        val c = column ?: return
        c.expanded.visibility = if (hidden) View.GONE else View.VISIBLE
        c.collapsed.visibility = if (hidden) View.VISIBLE else View.GONE
        if (hidden && label != null) c.label.label = label
        (c.col.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
            if (hidden) {
                lp.width = LinearLayout.LayoutParams.WRAP_CONTENT
                lp.weight = 0f
            } else {
                lp.width = 0
                lp.weight = 1f
            }
            c.col.layoutParams = lp
        }
    }

    private class SectionColumn(
        val col: LinearLayout,
        val expanded: View,
        val collapsed: View,
        val label: VerticalLabel,
    )

    /** Draws [label] rotated 90° (reads bottom-to-top) and measures with swapped
     *  dimensions, so a hidden section's name fits a button-wide strip — a plain
     *  rotated TextView would still claim its full horizontal width in layout. */
    private inner class VerticalLabel(c: Context) : View(c) {
        // Match the section header (style Text.PT.GroupHeader): 11sp,
        // sans-serif-medium, all-caps, 0.12 letter spacing, ptTextMuted.
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ctx.themeColor(R.attr.ptTextMuted)
            textSize = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, 11f, ctx.resources.displayMetrics,
            )
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            letterSpacing = 0.12f
        }
        var label: String = ""
            set(value) { field = value.uppercase(); requestLayout(); invalidate() }
        init { setWillNotDraw(false) }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val fm = textPaint.fontMetrics
            val textH = fm.descent - fm.ascent
            val textW = textPaint.measureText(label)
            setMeasuredDimension(
                View.resolveSize(kotlin.math.ceil(textH.toDouble()).toInt() + paddingLeft + paddingRight, widthMeasureSpec),
                View.resolveSize(kotlin.math.ceil(textW.toDouble()).toInt() + paddingTop + paddingBottom, heightMeasureSpec),
            )
        }

        override fun onDraw(canvas: Canvas) {
            val fm = textPaint.fontMetrics
            val textW = textPaint.measureText(label)
            canvas.save()
            canvas.translate(0f, height.toFloat())
            canvas.rotate(-90f)
            val baseline = width / 2f - (fm.ascent + fm.descent) / 2f
            canvas.drawText(label, (height - textW) / 2f, baseline, textPaint)
            canvas.restore()
        }
    }

    /** Set a section [header]'s top padding (the shared layout uses pt_group_gap;
     *  the panel tightens it per mode so the title sits closer to the top edge). */
    private fun setHeaderTop(header: View?, topDp: Int) {
        header ?: return
        header.setPadding(header.paddingLeft, dp(topDp), header.paddingRight, header.paddingBottom)
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

    // ── Resize (drag the handle / the band just below it to grow-shrink) ──
    // Driven by CaptureResultRoot so the touch zone can extend below the panel's
    // visible bottom edge (the handle itself can't receive touches past the
    // panel's bounds).

    private var resizing = false
    private var resizeStartRawY = 0f
    private var resizeStartHeight = 0
    private var resizeTracker: VelocityTracker? = null

    private fun beginResize(rawY: Float) {
        heightAnimator?.cancel() // the user takes over from the auto-grow
        resizing = true
        resizeStartRawY = rawY
        resizeStartHeight = panelHeightPx
        resizeTracker = VelocityTracker.obtain()
    }

    private fun updateResize(e: MotionEvent) {
        resizeTracker?.addMovement(e)
        val dy = (e.rawY - resizeStartRawY).toInt()
        // Clamp to [min, 90%], then cap at the content's max-needed height so the
        // drag can't grow the panel into empty space beyond the content.
        setPanelHeight(
            CaptureResultGeometry.clampPanelHeight(resizeStartHeight + dy, screenH)
                .coerceAtMost(maxNeededHeightPx),
        )
        reFitText()
    }

    private fun endResize(e: MotionEvent) {
        val vy = resizeTracker?.let {
            it.addMovement(e); it.computeCurrentVelocity(1000); it.yVelocity
        } ?: 0f
        resizeTracker?.recycle()
        resizeTracker = null
        resizing = false
        // A fast up-fling on the handle dismisses (slides out).
        if (vy < -FLING_DISMISS_VEL) animateOutAndDismiss()
    }

    private fun setPanelHeight(px: Int) {
        panelHeightPx = px
        (panel.layoutParams as FrameLayout.LayoutParams).height = px
        panel.requestLayout()
    }

    // ── Custom views ─────────────────────────────────────────────────────

    /** Full-screen transparent host. Owns the resize gesture (so its touch zone
     *  can reach below the panel's visible bottom — a child can't receive touches
     *  past the parent's bounds) and the tap-outside dismiss. A DOWN in the resize
     *  band drags the panel height; a DOWN below that band is on the game and
     *  dismisses. (Rotation dismisses too, via the controller's display listener.) */
    private inner class CaptureResultRoot(c: Context) : FrameLayout(c) {
        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val panelBottom = panel.bottom + panel.translationY
                    // Resize zone = the handle strip + EXTRA_DRAG_BELOW_DP below the
                    // panel, so the grab target extends past the sheet's edge.
                    val resizeTop = panelBottom - dp(HANDLE_HEIGHT_DP)
                    val resizeBottom = panelBottom + dp(EXTRA_DRAG_BELOW_DP)
                    if (ev.y >= resizeTop && ev.y <= resizeBottom) {
                        beginResize(ev.rawY)
                        return true
                    }
                    if (ev.y > resizeBottom) {
                        animateOutAndDismiss()
                        return true
                    }
                }
                MotionEvent.ACTION_MOVE -> if (resizing) { updateResize(ev); return true }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    if (resizing) { endResize(ev); return true }
                MotionEvent.ACTION_OUTSIDE -> { animateOutAndDismiss(); return true }
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
            color = ctx.themeColor(R.attr.ptTextMuted)
        }
        private val rect = RectF()
        init { setWillNotDraw(false) }
        override fun onDraw(canvas: Canvas) {
            val w = dp(40).toFloat()
            val h = dp(5).toFloat()
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
        /** How far below the panel's bottom edge the resize grab zone extends. */
        const val EXTRA_DRAG_BELOW_DP = 26
        const val SECTION_H_PAD_DP = 12
        /** Space below the filled side-by-side cards (to the panel's bottom). */
        const val SIDE_BY_SIDE_BOTTOM_BUFFER_DP = 12
        /** Space below the stacked content (to the panel's bottom). */
        const val STACKED_BOTTOM_BUFFER_DP = 10
        /** Top padding applied to EVERY panel section header (the shared layout uses
         *  pt_group_gap = 20dp). One value so all four headers — source/target,
         *  side-by-side/stacked — render the same height. */
        const val HEADER_TOP_DP = 6
        /** Corner radius as a multiple of pt_radius — between the original 1x and
         *  the 2x briefly tried. */
        const val CORNER_RADIUS_MULT = 1.5f
        const val DISMISS_DISTANCE_DP = 64f
        /** px/s; a deliberate up-fling (a notch above FloatingOverlayIcon's 600
         *  for a small icon, since the panel wants intent). */
        const val FLING_DISMISS_VEL = 1000f
        const val ENTER_DURATION_MS = 280L
        const val EXIT_DURATION_MS = 200L
        const val HEIGHT_DURATION_MS = 240L
    }
}
