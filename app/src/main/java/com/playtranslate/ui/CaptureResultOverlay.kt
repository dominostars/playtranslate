package com.playtranslate.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowInsets
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
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
import android.widget.Toast
import androidx.core.widget.NestedScrollView
import com.playtranslate.AnkiManager
import com.playtranslate.CaptureService
import com.playtranslate.CaptureSession
import com.playtranslate.CaptureState
import com.playtranslate.OneShotOverlayData
import com.playtranslate.PlayTranslateApplication
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.language.OcrBackend
import com.playtranslate.language.SourceLangId
import com.playtranslate.language.SourceLanguageEngines
import com.playtranslate.language.stackableTargetScript
import com.playtranslate.language.targetSupportsVerticalText
import com.playtranslate.ocr.registry.OcrModelManager
import com.playtranslate.ocr.registry.selectionToken
import com.playtranslate.model.TextSegments
import com.playtranslate.model.OcrProvenance
import com.playtranslate.model.TranslationResult
import com.playtranslate.overlay.OverlayHost
import com.playtranslate.overlayThemedContext
import com.playtranslate.themeColor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * The over-game capture result panel: a bottom-anchored sheet (default 40% of
 * the screen) showing the source section on the left and the target section on
 * the right (stacked when too narrow), drawn over the game without leaving it.
 * The grabber floats in a transparent strip above the sheet's top edge; content
 * buffers above the navigation bar the way a top sheet buffers under the status
 * bar (the fill still reaches the screen edge behind the system bar).
 *
 * Built fresh on [OverlayHost] (the shared window primitive). Mirrors the three
 * load-bearing patterns from [MagnifierLens]: a FIXED full-screen window whose
 * visible child is resized via layout — never the window, which would flash at
 * the gravity anchor — and a transparent full-screen root that catches off-panel
 * taps to dismiss. The drag-handle resize and the swipe/fling-down dismiss are
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
    private val shadowHeightPx = (cornerRadiusPx + density * SHADOW_BLUR_DP * 2.5f).toInt()
    private val prefs = Prefs(ctx)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val touchSlop = ViewConfiguration.get(ctx).scaledTouchSlop

    /** Invoked once, on any dismissal path. */
    var onDismiss: (() -> Unit)? = null

    /** Invoked when the word lens opens the in-app detail screen, carrying the
     *  currently-bound result so the controller can stash it and re-show this sheet
     *  when the user backs out of the detail screen. Null → the lens just dismisses. */
    var onNavigateToDetail: ((TranslationResult) -> Unit)? = null

    /** Overlay boxes for the currently-bound result: skeletons from
     *  [CaptureState.Translating] while an auto-collapse is showing placeholders,
     *  then the translated boxes from [CaptureState.Done]. Null otherwise (stash
     *  re-show, no-text, post-edit) — null keeps the switch pill hidden. */
    private var overlayData: OneShotOverlayData? = null

    /** The in-place boxes, rendered INSIDE this window (bottom-most root child).
     *  Same window ⇒ the window stays touchable, so MediaProjection's QTI clamp
     *  never dims the boxes, they can never draw over the sliver, and teardown
     *  is the window's own. Created on first show, then faded/re-fed via
     *  [TranslationOverlayView.setBoxes] (skeleton → translated). */
    private var chipsView: TranslationOverlayView? = null

    /** True from the moment the panel starts collapsing to its bottom-edge
     *  sliver (on-screen boxes showing) until it starts expanding back. Root
     *  touch handling swaps to sliver rules while set. */
    private var sliverMode = false

    /** The panel height when the sliver collapse started, so a tap-expand can
     *  return to it (the sliver itself parks the height at [sliverHeightPx]). */
    private var preSliverHeightPx = 0

    private var sessionJob: Job? = null
    /** The active service one-shot session (OCR + translate). Held so dismissal
     *  cancels the headless service work, not just our UI collector. */
    private var captureSession: CaptureSession? = null
    private var dismissed = false
    private var animatingOut = false

    private var screenW = 0
    private var screenH = 0
    private var panelHeightPx = 0
    // Navigation-bar buffer at the body's bottom, mirroring how a top sheet
    // handles the status bar: the sheet FILL still reaches the screen edge
    // behind the bar; only the content (and the sliver's visible strip) sits
    // above it. 0 while the bars are hidden (immersive game). Written by the
    // inset listener in [show], which also handles the API 29 fallback.
    private var bottomInsetPx = 0
    // Status-bar-height buffer reserved at the body's top so the section headers
    // clear the system status bar. Applied as body top padding (the sheet fill
    // still spans to the screen top behind it) and folded into every panel↔content
    // height conversion via [contentHeight]. 0 below API 30 (inset unreadable).
    private var topInsetPx = 0

    private val root = CaptureResultRoot(ctx)
    private val panel = BottomSheetPanel(ctx)
    private val body = BodyView(ctx)
    private val statusText = TextView(ctx)
    // Bottom-only fading edge: the stock two-sided fade also darkens the strip
    // under the section headers once scrolled, which reads as a misplaced inner
    // shadow against the sheet's baked edge shadow.
    private val scroll = object : NestedScrollView(ctx) {
        override fun getTopFadingEdgeStrength(): Float = 0f
    }
    private val contentRow = LinearLayout(ctx)
    private val handle = HandleView(ctx)
    // The panel ↔ on-screen-boxes switch. A ROOT child (not a panel child) so
    // its sheet-edge-straddling position stays tappable; see the addView note.
    private val showOnScreenPill = TextView(ctx)
    // A soft drop shadow cast above the sheet's top edge. The blur is BAKED ONCE
    // into [shadowBitmap]; the view only blits it and is repositioned via
    // translationY as the sheet grows/slides — never re-blurred (see [bakeEdgeShadow]).
    private val edgeShadow = EdgeShadowView(ctx)
    private var shadowBitmap: Bitmap? = null
    // The shadow tracks the sheet through a single pre-draw hook (see [syncShadow])
    // rather than per-mover wiring — so no drag/animation path can move the sheet
    // and leave the shadow behind.
    private var shadowSync: ViewTreeObserver.OnPreDrawListener? = null
    // Frosted backdrop: the captured screenshot blurred ONCE (a cheap downscale),
    // drawn at full-screen scale UNDER the translucent sheet fill and clipped to
    // the rounded body. Static — no live re-blur.
    private var backdropSmall: Bitmap? = null
    private val backdropPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val backdropDst = Rect()

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
    // The auto-size ceiling: 50% of the screen by default, but the user's dragged
    // height once they resize — so a re-fit (furigana, translation arriving) keeps
    // their chosen height instead of snapping back down to 50%.
    private var autoMaxPx = Int.MAX_VALUE

    // Tap-a-word → definition lens: display + speak + (when a dict entry matches)
    // the open-detail tap and Anki chip, shared with the drag flow via SourceLensActions.
    private var wordSpans: List<Triple<IntRange, String, String>> = emptyList()
    private var wordLens: MagnifierLens? = null
    private var wordSpeakChip: LensSpeakChip? = null

    // In-place edit (the panel window goes focusable so the IME shows over the game).
    private var windowParams: WindowManager.LayoutParams? = null
    private var lastResult: TranslationResult? = null
    /** Last original text written into [LastSentenceCache] from here, so a re-bind
     *  of the same sentence (furigana toggle, re-fit) doesn't re-fire the lookups. */
    private var lastCachedSentence: String? = null
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
            // This view stays unpadded — the content buffer lives INSIDE
            // contentRow (buildContent) and the nav-bar buffer on the body —
            // which pins the fade band exactly to the viewport bottom with
            // nothing able to draw past it. (A scroll-level padding +
            // clipToPadding=false variant let content render below the fade
            // line: the hovering-gradient artifact of 2026-07-14.)
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(dp(24))
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
                    // Slightly translucent sheet fill — the game shows faintly
                    // through (stroke stays opaque). SHEET_ALPHA is the dial.
                    val bg = ctx.themeColor(R.attr.ptBg)
                    setColor(Color.argb(SHEET_ALPHA, Color.red(bg), Color.green(bg), Color.blue(bg)))
                    cornerRadius = cornerRadiusPx
                    setStroke(dp(1), ctx.themeColor(R.attr.ptDivider))
                },
                // BOTTOM-SHEET EXPERIMENT: the rounded BOTTOM is pushed off-view
                // so only the top corners + edge show (mirror of the shipped
                // top-sheet, whose top was lifted).
                0, 0, 0, -cornerRadiusPx.toInt(),
            )
            // The InsetDrawable's negative inset is a DRAWING trick (push the
            // rounded bottom + stroke off-screen). Applied as a background it ALSO
            // reports that inset as negative PADDING, which silently inflated
            // the scroll's content area by the corner radius. Pin layout padding
            // to zero so only the drawing is affected (show() sets the real
            // grabber padding).
            setPadding(0, 0, 0, 0)
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    // Extend the rounded rect's bottom below the view so only the
                    // top two corners round (the bottom edge sits on the screen edge).
                    outline.setRoundRect(
                        0, 0, view.width, view.height + cornerRadiusPx.toInt(), cornerRadiusPx,
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
        showOnScreenPill.apply {
            text = ctx.getString(R.string.capture_show_on_screen)
            textSize = 12f
            setTextColor(ctx.themeColor(R.attr.ptTextMuted))
            gravity = Gravity.CENTER
            // Floating chrome (a mini-FAB), not sheet frame: OPAQUE fill,
            // because content near the sheet's top edge may scroll UNDER it and
            // a translucent pill over moving text reads as noise. The "shadow"
            // is two baked halo rings peeking below the body — deliberately NOT
            // elevation: a render-thread cast shadow lands wherever the light
            // model says, right where the sheet's own baked edge shadow lives
            // (same no-live-shadows rule as bakeEdgeShadow).
            val pillRadius = dp(SWITCH_PILL_HEIGHT_DP) / 2f
            fun halo(alpha: Int, radius: Float) = GradientDrawable().apply {
                setColor(Color.argb(alpha, 0, 0, 0))
                cornerRadius = radius
            }
            background = LayerDrawable(
                arrayOf(
                    halo(0x22, pillRadius),
                    halo(0x22, pillRadius - dp(1)),
                    GradientDrawable().apply {
                        setColor(ctx.themeColor(R.attr.ptBg))
                        cornerRadius = pillRadius
                        setStroke(dp(1), ctx.themeColor(R.attr.ptDivider))
                    },
                ),
            ).apply {
                setLayerInset(0, 0, dp(2), 0, 0)
                setLayerInset(1, dp(1), dp(3), dp(1), dp(1))
                setLayerInset(2, 0, 0, 0, dp(3))
            }
            setPadding(dp(12), 0, dp(12), dp(3))
            visibility = View.GONE
            setOnClickListener { collapseToSliver() }
        }
        panel.apply {
            orientation = LinearLayout.VERTICAL
            // The grabber floats OUTSIDE the sheet, in a transparent strip above
            // its top edge — the mirror of the top sheet's below-panel pill.
            addView(handle, LinearLayout.LayoutParams(MATCH, dp(HANDLE_HEIGHT_DP)))
            addView(body, LinearLayout.LayoutParams(MATCH, 0, 1f))
        }
        // Shadow first → behind the panel, so the opaque sheet covers all but the
        // soft fade cast above its top edge.
        root.addView(edgeShadow, FrameLayout.LayoutParams(MATCH, shadowHeightPx, Gravity.TOP))
        root.addView(panel, FrameLayout.LayoutParams(MATCH, 0, Gravity.BOTTOM))
        // The pill rides the ROOT, not the panel: it straddles the sheet's TOP
        // edge, and the half outside the panel's bounds would be visible but
        // untouchable as a panel child (parents hit-test children by their own
        // bounds). [syncShadow] glues it to the live sheet edge every frame, the
        // same way the shadow tracks.
        root.addView(
            showOnScreenPill,
            FrameLayout.LayoutParams(WRAP, dp(SWITCH_PILL_HEIGHT_DP), Gravity.TOP or Gravity.END).apply {
                marginEnd = dp(16)
            },
        )
    }

    // ── Public API ───────────────────────────────────────────────────────

    /** Size + add the window, lay out the sections responsively, and show the
     *  initial status placeholder. Call once. [backdrop] (the clean capture, if
     *  available) is blurred once into the frosted sheet fill — the caller may
     *  recycle it afterward (we keep only a downscaled copy). */
    fun show(screenW: Int, screenH: Int, backdrop: Bitmap? = null) {
        if (dismissed) return
        this.screenW = screenW
        this.screenH = screenH
        // Inset the body's content below the status bar (the sheet fill, drawn on
        // the full body bounds, still reaches the screen top). Explicit side-zeros
        // keep overriding the InsetDrawable's reported negative top padding.
        // Bottom sheet: no status-bar inset (the sheet's top edge is mid-screen).
        // topInsetPx is the content's gap below the sheet's top edge; the height
        // formulas (contentHeight, autoSizeAndFit, sliverHeightPx) reserve
        // HANDLE_HEIGHT_DP (the grabber strip above the sheet) + topInsetPx, so
        // they agree with the strip + this padding.
        topInsetPx = dp(10)
        body.setPadding(0, topInsetPx, 0, 0)
        autoMaxPx = CaptureResultGeometry.autoMaxHeight(screenH)
        // Load at the minimum (drag-resize floor) height; grow to fit on Done.
        panelHeightPx = CaptureResultGeometry.minPanelHeight(screenH)
        (panel.layoutParams as FrameLayout.LayoutParams).height = panelHeightPx
        shadowBitmap = bakeEdgeShadow(screenW)
        edgeShadow.invalidate()
        backdrop?.let {
            backdropSmall = blurBackdrop(it)
            backdropDst.set(0, 0, screenW, screenH)
            body.invalidate()
        }
        // Park below the bottom edge; the entrance animation (below) raises it.
        panel.translationY = panelHeightPx.toFloat()

        val sideBySide = CaptureResultGeometry.shouldUseSideBySide(
            screenW, dp(1), (CaptureResultGeometry.SIDE_BY_SIDE_FALLBACK_SECTION_DP * density).toInt(),
        )
        buildContent(sideBySide)

        val b = TranslationSectionBinder(
            panel, ctx, prefs, scope,
            TtsAlertTarget.Overlay(ctx, overlayHost, wm, displayId),
        )
        b.setupSectionButtons(
            onEdit = { startInPlaceEdit() },
            onAddToAnki = { openSentenceAnkiReview() },
            onAnkiOneTap = { oneTapSentenceFromOverlay() },
        )
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
        b.setCardFillAlpha(CARD_FILL_ALPHA)
        b.onChooseOcr = {
            val r = lastResult
            val p = r?.ocrProvenance
            val sp = r?.screenshotPath
            if (p != null && sp != null) showOcrPicker(p, sp)
        }
        b.onChooseLanguage = { isSource -> changeLanguage(isSource) }
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
        // Bottom sheet: buffer the CONTENT above the navigation bar while it's
        // visible, the same way a top sheet buffers under the status bar — the
        // sheet fill keeps reaching the screen edge behind the bar, so nothing
        // gets cut off and nothing floats. The IME is different: it's far taller
        // than the panel's buffers can absorb, so it LIFTS the whole sheet via a
        // bottom margin instead (FLAG_LAYOUT_NO_LIMITS makes the window ignore
        // ADJUST_RESIZE, so the in-place edit would otherwise type under the
        // keyboard). Listener-driven: both collapse to 0 when hidden.
        // Best-effort: transient reveals in sticky immersive may not dispatch a
        // visibility change to other windows on every OEM.
        root.setOnApplyWindowInsetsListener { _, insets ->
            val navInset: Int
            val imeLift: Int
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val nav = insets.getInsets(WindowInsets.Type.navigationBars()).bottom
                navInset = if (insets.isVisible(WindowInsets.Type.navigationBars())) nav else 0
                val ime = insets.getInsets(WindowInsets.Type.ime()).bottom
                imeLift = if (insets.isVisible(WindowInsets.Type.ime())) ime else 0
            } else {
                // API 29: the deprecated insets are the only signal.
                // stableInsetBottom is the nav bar's reserved height
                // (IME-independent); systemWindowInsetBottom is what's currently
                // consumed — 0 with bars hidden, the nav height with bars up,
                // the IME height while typing. min() isolates the visible nav
                // bar; anything past stable is the IME.
                @Suppress("DEPRECATION")
                val current = insets.systemWindowInsetBottom
                @Suppress("DEPRECATION")
                val stable = insets.stableInsetBottom
                navInset = minOf(current, stable)
                imeLift = if (current > stable) current else 0
            }
            if (navInset != bottomInsetPx) {
                bottomInsetPx = navInset
                body.setPadding(
                    body.paddingLeft, body.paddingTop, body.paddingRight, navInset,
                )
                // The buffer participates in every height formula — re-park
                // the sliver / re-fit the content to the new inner height.
                if (sliverMode) {
                    setPanelHeight(sliverHeightPx())
                } else if (lastResult != null) {
                    autoSizeAndFit()
                }
            }
            val panelLp = panel.layoutParams as FrameLayout.LayoutParams
            if (panelLp.bottomMargin != imeLift) {
                panelLp.bottomMargin = imeLift
                panel.requestLayout()
            }
            insets
        }
        overlayHost.addOverlayWindow(root, wm, lp, displayId)
        // ONE place that keeps the drop shadow glued to the sheet: a pre-draw hook
        // re-reads the panel's live position every frame, so the shadow follows
        // through any move (handle drag, body swipe/fling, resize, entrance/exit)
        // with no per-mover wiring to forget.
        shadowSync = ViewTreeObserver.OnPreDrawListener { syncShadow(); true }.also {
            root.viewTreeObserver.addOnPreDrawListener(it)
        }
        // Ease in from the bottom — a plain decelerate, no overshoot/bounce.
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
                    is CaptureState.Translating -> {
                        bindResult(
                            TranslationResult(
                                originalText = state.originalText,
                                segments = state.segments,
                                translatedText = "",
                                timestamp = "",
                                ocrProvenance = state.ocrProvenance,
                                langContext = prefs.langContext(),
                            ),
                        )
                        // Chips-preferred: collapse the moment OCR lands and show the
                        // skeleton boxes over the game while the translation runs —
                        // the panel must not grow again until the user asks for it.
                        // (The first bind's synchronous fit no-ops pre-layout and the
                        // posted ones are sliver-guarded, so nothing re-expands.)
                        if (prefs.captureResultOnScreenPreferred && state.overlayData != null) {
                            overlayData = state.overlayData
                            collapseToSliver()
                        }
                    }
                    is CaptureState.Done -> {
                        overlayData = state.overlayData
                        if (sliverMode) {
                            // Promote the skeletons in place; if the translation
                            // produced nothing paintable, fall back to the panel
                            // rather than leave placeholders pulsing forever.
                            val data = state.overlayData
                            if (data != null) updateChips(data) else expandFromSliver()
                        }
                        bindResult(state.result)
                    }
                    is CaptureState.NoText -> setStatus(state.message, state.ocrProvenance, state.screenshotPath)
                    is CaptureState.Failed -> {
                        // A translation failure after a skeleton collapse must bring
                        // the panel back — the status is unreadable in a sliver.
                        if (sliverMode) expandFromSliver()
                        setStatus(state.message)
                    }
                    CaptureState.Cancelled -> dismiss()
                }
            }
        }
    }

    /** Re-show entry point for the controller's stash-and-rebind path: set up the
     *  window exactly like [show], then bind a previously-captured result directly
     *  (no capture session) — used when the user backs out of the detail screen. */
    fun showWithResult(screenW: Int, screenH: Int, result: TranslationResult) {
        show(screenW, screenH)
        bindResult(result)
    }

    fun dismiss() {
        if (dismissed) return
        dismissed = true
        // "It opens how you left it": remember which presentation this result was
        // dismissed from — but only while a result is actually being PRESENTED.
        // lastResult alone is not enough: a Translating placeholder sets it, and a
        // translation failure then swaps to a status via setStatus() without
        // clearing it — recording there would let a failed capture silently flip
        // the preference. Every status path hides the scroll; both real
        // presentations (expanded panel, sliver) keep it visible.
        if (lastResult != null && scroll.visibility == View.VISIBLE) {
            prefs.captureResultOnScreenPreferred = sliverMode
        }
        heightAnimator?.cancel()
        dismissWordLens()
        sessionJob?.cancel()
        // Cancel the service-side one-shot job too (not just our collector), so
        // OCR/translation doesn't keep running headless after the panel is gone.
        captureSession?.cancel()
        captureSession = null
        binder?.release()
        shadowSync?.let { root.viewTreeObserver.removeOnPreDrawListener(it) }
        shadowSync = null
        try { overlayHost.removeOverlayWindow(root) } catch (_: Exception) {}
        shadowBitmap?.recycle()
        shadowBitmap = null
        backdropSmall?.recycle()
        backdropSmall = null
        scope.cancel()
        onDismiss?.invoke()
    }

    /** Slide the panel up off the top edge, then remove it — so tap-outside and
     *  swipe/fling-down dismissals animate out instead of vanishing. Other paths
     *  (Cancelled, supersede, teardown) call [dismiss] directly for immediate
     *  removal. */
    private fun animateOutAndDismiss() {
        if (dismissed || animatingOut) return
        animatingOut = true
        dismissWordLens()
        panel.animate()
            .translationY(panelHeightPx.toFloat())
            .setDuration(EXIT_DURATION_MS)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction { dismiss() }
            .start()
    }

    // ── Sliver state (result shown as on-screen boxes) ───────────────────

    /** Paint [data]'s boxes over the game (bottom-most child of this window).
     *  False when the overlay surface isn't ours to draw on (live mode). */
    private fun showChips(data: OneShotOverlayData): Boolean {
        if (CaptureService.instance?.isLive == true) return false
        val v = chipsView ?: TranslationOverlayView(
            android.view.ContextThemeWrapper(ctx, android.R.style.Theme_DeviceDefault),
            oneShot = true,
            verticalTextTarget = targetSupportsVerticalText(prefs.targetLang),
            verticalTextStackable = stackableTargetScript(prefs.targetLang),
            verticalGrowEnabled = prefs.verticalTextGrow,
        ).also {
            chipsView = it
            root.addView(it, 0, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        v.animate().cancel()
        v.alpha = 1f
        v.setBoxes(data.boxes, data.cropLeft, data.cropTop, data.screenshotW, data.screenshotH)
        return true
    }

    /** Swap the boxes in place — the skeleton → translated promotion when Done
     *  lands while slivered. No-op unless the boxes are up. */
    private fun updateChips(data: OneShotOverlayData) {
        val v = chipsView ?: return
        if (v.alpha == 0f) return
        v.setBoxes(data.boxes, data.cropLeft, data.cropTop, data.screenshotW, data.screenshotH)
    }

    /** Fade the boxes out. The view stays attached (alpha 0) for cheap re-shows;
     *  it dies with the window. */
    private fun hideChips() {
        val v = chipsView ?: return
        v.animate().alpha(0f).setDuration(SLIVER_FADE_MS).start()
    }

    /** Collapse the sheet to a bottom-edge sliver and paint the result's boxes in
     *  place over the game. Height-based (the sheet's bottom edge retracts, like
     *  the grow-to-fit in reverse) so the sliver drag below is the plain resize
     *  gesture with a lower floor. The user comes back via a tap (auto-expand)
     *  or a drag on the sliver zone, or dismisses everything by tapping
     *  anywhere else. */
    private fun collapseToSliver() {
        if (dismissed || animatingOut || sliverMode) return
        if (editContainer.visibility == View.VISIBLE) return
        val data = overlayData ?: return
        if (!showChips(data)) return
        sliverMode = true
        showOnScreenPill.visibility = View.GONE
        dismissWordLens()
        preSliverHeightPx = panelHeightPx
        // The sections are about to be a 12dp strip — fade them out rather than
        // showing a clipped line of text in the sliver.
        scroll.animate().alpha(0f).setDuration(SLIVER_FADE_MS).start()
        animateSliverHeight(sliverHeightPx())
    }

    /** A tap on the sliver: grow the sheet back to its pre-collapse height and
     *  fade the sections back in; the on-screen boxes fade out as it returns. */
    private fun expandFromSliver() {
        if (dismissed || animatingOut || !sliverMode) return
        sliverMode = false
        hideChips()
        scroll.animate().alpha(1f).setDuration(SLIVER_FADE_MS).start()
        val target = preSliverHeightPx.coerceAtLeast(CaptureResultGeometry.minPanelHeight(screenH))
        animateSliverHeight(target) {
            updatePillVisibility()
            // Re-run the fit the sliver suppressed (an auto-collapse lands before
            // the Done grow-to-fit ever ran, so the height may still be the
            // loading floor). Skipped when a status replaced the content (the
            // Failed-while-slivered recovery) — there's no text to fit.
            if (lastResult != null && scroll.visibility == View.VISIBLE) autoSizeAndFit()
        }
    }

    /** The sliver drag has passed touch slop: the user is pulling the sheet edge
     *  to a height of their choosing, so the drag owns the height from here.
     *  The boxes leave as soon as the panel starts coming back — a slow drag
     *  shouldn't read as both presentations at once. */
    private fun beginSliverDrag() {
        heightAnimator?.cancel()
        hideChips()
        scroll.animate().alpha(1f).setDuration(SLIVER_FADE_MS).start()
    }

    /** Per-frame sliver drag: the resize math with the floor lowered to the
     *  sliver itself (the standard floor is the commit threshold, not a clamp,
     *  so the sheet must be able to sit below it mid-drag). */
    private fun updateSliverDrag(dy: Float) {
        val h = CaptureResultGeometry.clampPanelHeight(
            sliverHeightPx() + dy.toInt(), screenH, minFraction = 0f,
        )
            .coerceAtMost(maxNeededHeightPx)
            .coerceAtLeast(sliverHeightPx())
        setPanelHeight(h)
        if (h >= CaptureResultGeometry.minPanelHeight(screenH)) reFitText()
    }

    /** Release of a sliver drag. Past the threshold (the normal resize floor)
     *  the panel is committed exactly where the drag left it; under it, the
     *  sheet settles back into the sliver and the boxes return. */
    private fun endSliverDrag() {
        if (dismissed) return
        if (panelHeightPx >= CaptureResultGeometry.minPanelHeight(screenH)) {
            sliverMode = false
            // Adopt the dragged height like endResize does, so re-fits keep it.
            autoMaxPx = panelHeightPx
            reFitText()
            updatePillVisibility()
        } else {
            val data = overlayData
            if (data != null && showChips(data)) {
                scroll.animate().alpha(0f).setDuration(SLIVER_FADE_MS).start()
                animateSliverHeight(sliverHeightPx())
            } else {
                // The overlay surface got claimed mid-drag (live mode) — expand
                // rather than strand a sliver with nothing behind it.
                expandFromSliver()
            }
        }
    }

    /** Dismiss everything from the sliver: the boxes start fading immediately
     *  (not after the slide), the sliver slides off, and [dismiss] records the
     *  on-screen exit preference because [sliverMode] is still set. */
    private fun dismissFromSliver() {
        hideChips()
        animateOutAndDismiss()
    }

    /** Plain height slide for the sliver transitions. Unlike [animatePanelHeight]
     *  it leaves the text sizes alone: the sections are faded (or fading) for
     *  every frame where an intermediate fit would matter, fitting to sliver
     *  heights computes degenerate sizes, and the expand path re-runs the real
     *  fit when it lands. */
    private fun animateSliverHeight(target: Int, onEnd: (() -> Unit)? = null) {
        heightAnimator?.cancel()
        heightAnimator = ValueAnimator.ofInt(panelHeightPx, target).apply {
            duration = SLIVER_DURATION_MS
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                if (dismissed) {
                    anim.cancel()
                    return@addUpdateListener
                }
                setPanelHeight(anim.animatedValue as Int)
            }
            if (onEnd != null) {
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    private var cancelled = false
                    override fun onAnimationCancel(animation: android.animation.Animator) {
                        cancelled = true
                    }
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        if (!cancelled && !dismissed) onEnd()
                    }
                })
            }
            start()
        }
    }

    /** Visible height of the collapsed sheet: the grabber strip + a strip of the
     *  sheet's top (with its content gap), raised by the nav-bar buffer so the
     *  sliver isn't buried under the system bar when it's present. */
    private fun sliverHeightPx(): Int =
        topInsetPx + dp(SLIVER_SHEET_DP + HANDLE_HEIGHT_DP) + bottomInsetPx

    /** The switch pill shows only when there is something to switch TO (overlay
     *  boxes for the bound result) and the panel is expanded. */
    private fun updatePillVisibility() {
        showOnScreenPill.visibility =
            if (!sliverMode && overlayData != null) View.VISIBLE
            else View.GONE
    }

    // ── State rendering ──────────────────────────────────────────────────

    private fun setStatus(message: String, ocrProvenance: OcrProvenance? = null, screenshotPath: String? = null) {
        // No-text status affordances, each its own tappable span (so tapping one can't
        // trigger the other): the source-language name is accent-colored → source picker
        // (same as the source header); the gear → OCR picker, shown only when a pinned
        // screenshot is on hand to re-OCR AND there's >1 OCR tool for the language.
        val showGear = ocrProvenance != null && screenshotPath != null &&
            OcrModelManager.availableBackends(ctx, ocrProvenance.sourceLangId).size > 1
        statusText.setNoTextStatus(
            message,
            showGear,
            onLanguageTap = { changeLanguage(isSource = true) },
            onGearTap = { if (ocrProvenance != null && screenshotPath != null) showOcrPicker(ocrProvenance, screenshotPath) },
        )
        statusText.visibility = View.VISIBLE
        scroll.visibility = View.GONE
        // A status means no shown result — whatever boxes the session produced
        // earlier (e.g. skeletons before a translation failure) are off the table.
        overlayData = null
        showOnScreenPill.visibility = View.GONE
    }

    private fun bindResult(result: TranslationResult) {
        val b = binder ?: return
        lastResult = result
        populateSentenceCache(result)
        statusText.visibility = View.GONE
        scroll.visibility = View.VISIBLE
        updatePillVisibility()
        b.bindResult(result)   // also paints furigana (bindResult → bindSource)
        // Tap-a-word → definition: tokenize the source so taps resolve to spans.
        // Readings refine on tap via the resolver, so an empty lookupToReading
        // (no full word-list pipeline here) only loses the rare homograph hint.
        b.tvOriginal.onTapAtOffset = { offset -> onSourceTapped(offset) }
        refreshWordSpans(result.originalText)
        // Fit the text to the current panel NOW, before this frame draws. The first
        // (status→content) bind hasn't laid the scroll out yet, so this no-ops via
        // autoSizeAndFit's own width guard and the posts below do the work. But on a
        // re-bind onto an already-laid-out panel — the Translating→Done promotion —
        // the freshly-set text would otherwise be drawn at the OUTGOING size for a
        // frame and then snap: the short "Translating…" placeholder fills its
        // (source-driven) card at the 24sp ceiling, so the longer real translation
        // flashes at 24sp before the posted fit snaps it down to its ~16sp fit.
        // Fitting synchronously draws it at the right size on the first frame; the
        // posts still re-measure the now-laid-out note row and drive grow-to-fit.
        autoSizeAndFit()
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

    /** Mirror the Activity flow's LastSentenceCache write so the lens's Anki card
     *  + open-detail tap carry the sentence translation + per-word results. */
    private fun populateSentenceCache(result: TranslationResult) {
        val original = result.originalText
        if (original.isBlank() || result.translatedText.isBlank()) return
        if (original == lastCachedSentence) return
        lastCachedSentence = original
        scope.launch {
            LastSentenceCache.setFromTranslationResult(
                original = original,
                translation = result.translatedText,
                translationSource = result.backendDisplayName,
                wordResults = null,
                surfaceForms = null,
                wordEnrichment = null,
            )
            // awaitOrStartWordLookups only writes the word maps when original ==
            // the cache's current sentence, so the translation above is preserved.
            try {
                LastSentenceCache.awaitOrStartWordLookups(ctx.applicationContext, original)
            } catch (_: Exception) {}
        }
    }

    /** On the first layout of a fresh result: measure the natural content height
     *  at max text size (the same StaticLayout basis as the fit, so they agree)
     *  and animate the panel to fit it (capped at 50% of screen, floored at min),
     *  smoothly scaling the text alongside. */
    private fun autoSizeAndFit() {
        // Slivered: the sections are faded out and the height is parked; the
        // expand path re-runs this when the panel comes back.
        if (sliverMode) return
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
        val neededHeight = naturalContent + dp(HANDLE_HEIGHT_DP) + topInsetPx + bottomInsetPx
        maxNeededHeightPx = neededHeight.coerceAtLeast(CaptureResultGeometry.minPanelHeight(screenH))
        val target = CaptureResultGeometry.autoPanelHeight(neededHeight, screenH, autoMaxPx)
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
     *  sets would otherwise inflate a `contentRow.height − text` reading). A hidden
     *  section's card is GONE (its inset + content overhead — padding + the note row —
     *  no longer render), so those drop out just like its text does; only its header
     *  survives, kept in [stackedNonCardPx]. Without this the panel reserves a dead
     *  card-chrome strip for the hidden section and grows as if it were still shown. */
    private fun stackedChrome(b: TranslationSectionBinder): Int {
        var chrome = stackedNonCardPx
        if (!prefs.hideOriginalSection) chrome += sourceCardInsetPx + b.sourceContentOverhead()
        if (!prefs.hideTranslationSection) chrome += targetCardInsetPx + b.targetContentOverhead()
        return chrome
    }

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

    /** Height available to the sections for a given panel height: minus the handle
     *  bar AND the status-bar buffer padding at the body's top. The single conversion
     *  from panel height to the [applyCardFill] / [fitSizes] content height, so the
     *  top inset stays in sync everywhere the panel grows or is dragged. */
    private fun contentHeight(panelPx: Int): Int =
        (panelPx - dp(HANDLE_HEIGHT_DP) - topInsetPx - bottomInsetPx).coerceAtLeast(0)

    /** Size the text to the current panel height (continuous). Called per drag frame. */
    private fun reFitText() {
        val b = binder ?: return
        val bodyH = contentHeight(panelHeightPx)
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
        val (srcStart, tgtStart) = fitSizes(b, contentHeight(startH))
        val (srcEnd, tgtEnd) = fitSizes(b, contentHeight(target))
        applyCardFill(b, contentHeight(startH))
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
                applyCardFill(b, contentHeight(h))
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
                // The offset just past the word can land on the NEXT line (the word
                // ends a wrapped line) — getPrimaryHorizontal then returns that line's
                // start (~0), collapsing the center to mid-screen and throwing off the
                // lens/arrow for right-edge words. Fall back to the line's right edge.
                val endOffset = span.first.last + 1
                val xEnd = if (layout.getLineForOffset(endOffset) == lineStart) {
                    layout.getPrimaryHorizontal(endOffset)
                } else {
                    layout.getLineRight(lineStart)
                }
                val wordCenterX = ((xStart + xEnd) / 2).toInt() + tv.paddingLeft
                val lineTop = layout.getLineTop(lineStart) - tv.scrollY + tv.paddingTop
                val lineH = layout.getLineBottom(lineStart) - layout.getLineTop(lineStart)
                val loc = IntArray(2)
                tv.getLocationOnScreen(loc)
                val screenX = loc[0] + wordCenterX
                val anchorY = loc[1] + lineTop
                dismissWordLens()
                val lens = MagnifierLens(ctx, wm, displayId, overlayHost, showAnkiChip = resolved.entry != null)
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
                // Open-detail tap + Anki chip — same actions as the drag flow. The captured
                // [resolved] is this tap's word/entry; sentence + screenshot come from the
                // current capture result.
                SourceLensActions(
                    ctx.applicationContext, displayId, overlayHost, lens,
                    // Anki review pushes a full screen → tear the sheet down. Open-detail
                    // stashes this result so the controller can re-show the sheet when the
                    // user backs out of the detail screen (falls back to dismiss when no
                    // controller / no bound result).
                    onLaunchedActivity = { kind ->
                        when (kind) {
                            SourceLensActions.LaunchKind.Anki -> dismiss()
                            SourceLensActions.LaunchKind.Detail -> {
                                val r = lastResult
                                val nav = onNavigateToDetail
                                if (r != null && nav != null) nav(r) else dismiss()
                            }
                        }
                    },
                    tagDetailReturn = true,
                ) {
                    LensActionContext(
                        resolved.word,
                        resolved.reading,
                        resolved.entry,
                        lastResult?.originalText,
                        lastResult?.screenshotPath,
                    )
                }
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

    // ── OCR tool switcher ────────────────────────────────────────────────

    /** Open the "Choose OCR tool" picker as an overlay window for the capture pinned
     *  by [prov] + [path] — a Ready result's source row, or a "no text detected"
     *  status. Switching to a downloaded engine re-OCRs that capture in place; a
     *  not-downloaded one deep-links to the OCR settings screen (and tears this sheet
     *  down). */
    private fun showOcrPicker(prov: OcrProvenance, path: String) {
        OcrPicker.populate(
            OverlayAlert.Builder(ctx, overlayHost, wm, displayId),
            ctx,
            prov.sourceLangId,
            prov.engineToken,
            onReOcr = { reOcr(prov, path) },
            onDownload = { backend -> launchOcrSettings(backend, prov.sourceLangId); dismiss() },
        ).showAsOverlay()
    }

    /** Re-run the capture pipeline on the pinned screenshot with the just-selected
     *  engine, driving this sheet through the normal loading stages (and re-emitting
     *  the no-text status + gear if it still finds nothing) via [observe]. */
    private fun reOcr(prov: OcrProvenance, path: String) {
        val svc = CaptureService.instance ?: return
        scope.launch {
            val bmp = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(path) } ?: return@launch
            if (!dismissed) observe(svc.processScreenshot(
                com.playtranslate.capture.CapturedFrame(
                    bmp, includesSystemUi = prov.frameIncludesSystemUi ?: true,
                ),
                prov.displayId, prov.region, prov.sourceLangId,
            ))
        }
    }

    /** Deep-link to the OCR settings screen to download [backend]'s pack, on the
     *  foreground display (mirrors [openSentenceAnkiReview]). The caller dismisses
     *  this sheet since the app comes to the foreground. */
    private fun launchOcrSettings(backend: OcrBackend, id: SourceLangId) {
        val app = ctx.applicationContext
        val intent = CaptureOverlaySettingsActivity.downloadIntent(app, id, backend.selectionToken).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val targetDisplay = PlayTranslateApplication.foregroundDisplayId() ?: displayId
        val opts = android.app.ActivityOptions.makeBasic().setLaunchDisplayId(targetDisplay).toBundle()
        app.startActivity(intent, opts)
    }

    /** Change the source ([isSource]) or target language: null the stale Settings delegate,
     *  dismiss this overlay, and open the picker on the foreground display. Shared by the
     *  language section headers and the tappable language name in the no-text status. The
     *  user re-captures to see it in the new language. */
    private fun changeLanguage(isSource: Boolean) {
        LanguageSetupActivity.selectionDelegate = null
        dismiss()
        launchLanguageSetup(
            if (isSource) LanguageSetupActivity.MODE_SOURCE else LanguageSetupActivity.MODE_TARGET,
        )
    }

    /** Open the language picker on the foreground display (mirrors [launchOcrSettings]).
     *  The caller dismisses this overlay first; the user re-captures on return. */
    private fun launchLanguageSetup(mode: String) {
        val app = ctx.applicationContext
        val intent = Intent(app, LanguageSetupActivity::class.java)
            .putExtra(LanguageSetupActivity.EXTRA_MODE, mode)
            .apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
        val targetDisplay = PlayTranslateApplication.foregroundDisplayId() ?: displayId
        val opts = android.app.ActivityOptions.makeBasic().setLaunchDisplayId(targetDisplay).toBundle()
        app.startActivity(intent, opts)
    }

    // Card-level "add to Anki" for the whole captured sentence — the overlay is a
    // window, so it launches an Activity-hosted review (the results page uses a
    // DialogFragment it can't). Mirrors the results page's onAnkiClicked.
    // The overlay always uses the SENTENCE review (no single-word→word-sheet branch
    // like the results page) — accepted simplification.
    private fun openSentenceAnkiReview() {
        val result = lastResult ?: return
        val sentence = result.originalText
        if (sentence.isBlank()) return
        val app = ctx.applicationContext
        // Gate like the word-lens path (SourceLensActions): AnkiDroid must be
        // installed, and the runtime permission is requested by the
        // AnkiPermissionActivity trampoline — the overlay is a window with no
        // result launcher of its own. Without this gate a user with no AnkiDroid
        // / no permission would reach a dead review sheet (no-op deck loader; the
        // save can only fail later).
        if (!AnkiManager(app).isAnkiDroidInstalled()) {
            showAnkiNotInstalledDialog(ctx, overlayHost, wm, displayId)
            return
        }
        val cached = LastSentenceCache.takeIf { it.original == sentence }
        val words = cached?.wordResults?.takeIf { it.isNotEmpty() }
        SentenceAnkiReviewActivity.finishCurrentIfAny()
        AnkiPermissionActivity.finishCurrentIfAny()
        val intent = Intent(app, AnkiPermissionActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
            putExtra(AnkiPermissionActivity.EXTRA_FORWARD_TARGET, AnkiPermissionActivity.TARGET_SENTENCE)
            putExtra(SentenceAnkiReviewActivity.EXTRA_SENTENCE, sentence)
            putExtra(SentenceAnkiReviewActivity.EXTRA_TRANSLATION, result.translatedText)
            result.screenshotPath?.let { putExtra(SentenceAnkiReviewActivity.EXTRA_SCREENSHOT_PATH, it) }
            putExtra(SentenceAnkiReviewActivity.EXTRA_SOURCE_LANG, prefs.sourceLangId.code)
            words?.let { wr ->
                val keys = wr.keys.toTypedArray()
                putExtra(SentenceAnkiReviewActivity.EXTRA_WORDS, keys)
                putExtra(SentenceAnkiReviewActivity.EXTRA_READINGS, wr.values.map { it.first }.toTypedArray())
                putExtra(SentenceAnkiReviewActivity.EXTRA_MEANINGS, wr.values.map { it.second }.toTypedArray())
                putExtra(SentenceAnkiReviewActivity.EXTRA_FREQ_SCORES, wr.values.map { it.third }.toIntArray())
                // Carry surfaces (parallel to keys) + pitch/frequency enrichment
                // from the SAME `cached` snapshot, atomically — so the review
                // can't pair these words with another sentence's enrichment that
                // a later capture wrote to the global cache while the permission
                // trampoline was up.
                putExtra(SentenceAnkiReviewActivity.EXTRA_SURFACES,
                    keys.map { cached?.surfaceForms?.get(it) ?: "" }.toTypedArray())
                putExtra(SentenceAnkiReviewActivity.EXTRA_ENRICHMENT,
                    HashMap(cached?.wordEnrichment.orEmpty()))
            }
        }
        val targetDisplay = PlayTranslateApplication.foregroundDisplayId() ?: displayId
        val opts = android.app.ActivityOptions.makeBasic().setLaunchDisplayId(targetDisplay).toBundle()
        app.startActivity(intent, opts)
        dismiss()   // tear the sheet down — it'd otherwise sit over the review/trampoline
    }

    // Long-press = headless one-tap send of the captured sentence. Runs on a
    // process-lived scope so dismissing the sheet can't cancel the card.
    private fun oneTapSentenceFromOverlay() {
        val result = lastResult ?: return
        val sentence = result.originalText
        if (sentence.isBlank()) return
        val app = ctx.applicationContext
        val ankiManager = AnkiManager(app)
        if (!ankiManager.isAnkiDroidInstalled() || !ankiManager.hasPermission() || prefs.ankiDeckId < 0L) {
            // No headless path available → fall back to the review.
            openSentenceAnkiReview()
            return
        }
        val cached = LastSentenceCache.takeIf { it.original == sentence }
        val words = cached?.wordResults?.takeIf { it.isNotEmpty() }
        val payload = words?.let {
            LastSentenceCache.WordsPayload(it, cached.surfaceForms.orEmpty(), cached.wordEnrichment.orEmpty())
        }
        val translation = result.translatedText.takeIf { it.isNotEmpty() }
        val langId = prefs.sourceLangId
        android.widget.Toast.makeText(app, R.string.anki_adding_in_progress, android.widget.Toast.LENGTH_SHORT).show()
        ankiSendScope.launch {
            val sendResult = app.oneTapSendSentence(
                original = sentence, translation = translation, wordsPayload = payload,
                screenshotPath = result.screenshotPath, sourceLangId = langId,
            )
            when (sendResult) {
                is AnkiSendResult.Success -> android.widget.Toast.makeText(app,
                    if (sendResult.audioDropped || sendResult.wordAudioDropped) R.string.anki_added_no_audio else R.string.anki_added_success,
                    android.widget.Toast.LENGTH_SHORT).show()
                is AnkiSendResult.Failed -> android.widget.Toast.makeText(app, sendResult.messageRes, android.widget.Toast.LENGTH_LONG).show()
                is AnkiSendResult.NeedsMapping -> openSentenceAnkiReview()
            }
        }
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
        editText.requestFocus()        // view-focus first, so the IME targets this field
        setWindowFocusable(true)
        // Flipping the window focusable runs through wm.updateViewLayout, which is
        // async — the window is NOT focusable yet in this frame, so an immediate
        // showSoftInput no-ops (that was the bug: the IME only appeared after a tap).
        // STATE_ALWAYS_VISIBLE (set above) is the primary trigger when focus lands;
        // this posted call is the explicit nudge that runs after the relayout, guarded
        // in case the edit was committed/cancelled before it fires.
        editText.post {
            if (editContainer.visibility != View.VISIBLE) return@post
            ctx.getSystemService(InputMethodManager::class.java)
                ?.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
        }
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
            // The source is no longer the OCR output — drop provenance so the
            // "Scanned by …" row + gear hide and a re-OCR can't clobber the edit.
            ocrProvenance = null,
        )
        lastResult = edited
        // The capture's per-group boxes translate the OLD source — the edit
        // orphans them, so drop the on-screen presentation for this result.
        overlayData = null
        updatePillVisibility()
        val gen = ++editGeneration
        b.bindSource(edited.segments)   // sets text + paints furigana
        b.setTargetTranslatingPlaceholder()
        refreshWordSpans(newText)
        scope.launch {
            // translateOnce can throw (no usable backend / all backends fail) and
            // the service can be null — either way we MUST still land on a terminal
            // state, because the original capture session was just cancelled above.
            // Otherwise the panel is stranded on "Translating…" forever. Mirror the
            // Activity edit path: fall back to a "—" placeholder. (Re-throw
            // CancellationException so dismissal stays silent.)
            val gt = try {
                CaptureService.instance?.translateOnce(newText)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (dismissed || gen != editGeneration) return@launch
            bindResult(
                if (gt != null) edited.copy(
                    translatedText = gt.text,
                    note = gt.note,
                    backendDisplayName = gt.backendDisplayName,
                ) else edited.copy(translatedText = "—"),
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
            // ALWAYS_VISIBLE, not STATE_VISIBLE: the window only becomes focusable
            // asynchronously via the updateViewLayout below, and ALWAYS_VISIBLE makes
            // the system raise the IME the instant the window actually gains focus.
            // STATE_VISIBLE wasn't reliably re-evaluated on that focus transition, so
            // the keyboard only appeared once the user tapped into the field.
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or
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
        // Bottom sheet: dragging the grabber UP grows the panel.
        val dy = (resizeStartRawY - e.rawY).toInt()
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
        // A fast down-fling on the grabber dismisses (slides out the bottom).
        if (vy > FLING_DISMISS_VEL) {
            animateOutAndDismiss()
        } else {
            // Adopt the user's dragged height as the auto-size ceiling, so a later
            // re-fit (furigana toggle, translation arriving) keeps this height
            // instead of snapping back down to the default 50%.
            autoMaxPx = panelHeightPx
        }
    }

    private fun setPanelHeight(px: Int) {
        panelHeightPx = px
        (panel.layoutParams as FrameLayout.LayoutParams).height = px
        panel.requestLayout()
    }

    /** Glue the pre-baked drop shadow to the sheet's bottom edge by re-reading the
     *  panel's LIVE position + height. Driven once per frame by the pre-draw hook
     *  registered in [show], so it tracks the sheet through ANY move — handle drag,
     *  body swipe/fling, resize, entrance/exit — with no per-mover wiring to miss.
     *  Cheap + idempotent: a single translationY, a no-op when nothing moved. */
    private fun syncShadow() {
        // Land the bitmap's contour line (y=cornerRadius, the sheet's straight
        // bottom edge) exactly on the sheet's bottom; the corner arcs above it sit
        // behind the rounded sheet, the cast blur below shows.
        // The sheet's visual top sits below the transparent grabber strip.
        val sheetTop = panel.top + panel.translationY + dp(HANDLE_HEIGHT_DP)
        // The bitmap's contour line (its silhouette's straight top edge) sits at
        // shadowHeight - cornerRadius; land it exactly on the sheet's top.
        edgeShadow.translationY = sheetTop - shadowHeightPx + cornerRadiusPx
        // The switch pill rides the same hook: centered on the sheet's TOP edge,
        // half over the fill and half over the game above.
        showOnScreenPill.translationY = sheetTop - showOnScreenPill.height / 2f
    }

    /** Bake the sheet's bottom-edge drop shadow ONCE into a software bitmap —
     *  BlurMaskFilter only blurs on a software canvas, so doing it here keeps the
     *  host on the hardware layer (same trick as MagnifierLens.insetShadowBitmap).
     *  The rounded-rect "sheet" extends far up off the bitmap, so only its bottom
     *  edge + corners cast their blur DOWNWARD into the [shadowHeightPx]-tall strip. */
    private fun bakeEdgeShadow(width: Int): Bitmap {
        val w = width.coerceAtLeast(1)
        val h = shadowHeightPx.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(SHADOW_ALPHA, 0, 0, 0)
            // OUTER → blur only OUTSIDE the shape, so the shadow hugs the rounded
            // bottom contour (curving at the corners) rather than a flat line.
            maskFilter = BlurMaskFilter(density * SHADOW_BLUR_DP, BlurMaskFilter.Blur.OUTER)
        }
        // Sheet silhouette: straight top edge at y = h - cornerRadius so the
        // corner arcs sit INSIDE the bitmap; the body extends far down off it.
        // The OUTER blur then casts UPWARD into the strip above the sheet.
        c.drawRoundRect(
            0f, h - cornerRadiusPx, w.toFloat(), h.toFloat() * 3f,
            cornerRadiusPx, cornerRadiusPx, paint,
        )
        return bitmap
    }

    /** The blur: downscale for cheapness, then a separable box blur (3 passes ≈
     *  Gaussian) over the small bitmap so it reads as a smooth frost instead of
     *  visible low-res pixels — a plain downscale+upscale aliases (the grid shows
     *  through). Small bitmap → sub-millisecond. Reads [src] synchronously so the
     *  caller can recycle it right after. */
    private fun blurBackdrop(src: Bitmap): Bitmap? {
        if (src.isRecycled || src.width <= 0 || src.height <= 0) return null
        val w = (src.width / BACKDROP_DOWNSCALE).coerceAtLeast(1)
        val h = (src.height / BACKDROP_DOWNSCALE).coerceAtLeast(1)
        val small = try {
            Bitmap.createScaledBitmap(src, w, h, true)
        } catch (_: Exception) {
            return null
        }
        val blurred = boxBlur(small, BACKDROP_BLUR_RADIUS, passes = 3)
        if (blurred !== small) small.recycle()
        return blurred
    }

    /** Separable box blur over a small ARGB bitmap, [passes] times (3 ≈ Gaussian). */
    private fun boxBlur(src: Bitmap, radius: Int, passes: Int): Bitmap {
        val w = src.width
        val h = src.height
        if (radius < 1 || w < 2 || h < 2) return src
        val a = IntArray(w * h)
        val b = IntArray(w * h)
        src.getPixels(a, 0, w, 0, 0, w, h)
        repeat(passes) {
            boxBlurAxis(a, b, w, h, radius, horizontal = true)
            boxBlurAxis(b, a, w, h, radius, horizontal = false)
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setPixels(a, 0, w, 0, 0, w, h)
        }
    }

    /** One running-window box-blur pass along one axis (edges clamp). */
    private fun boxBlurAxis(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val len = if (horizontal) w else h
        val step = if (horizontal) 1 else w
        val div = 2 * r + 1
        for (line in 0 until lines) {
            val base = if (horizontal) line * w else line
            var sa = 0; var sr = 0; var sg = 0; var sb = 0
            for (i in -r..r) {
                val c = src[base + i.coerceIn(0, len - 1) * step]
                sa += (c ushr 24) and 0xff; sr += (c ushr 16) and 0xff
                sg += (c ushr 8) and 0xff; sb += c and 0xff
            }
            for (j in 0 until len) {
                dst[base + j * step] =
                    ((sa / div) shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val co = src[base + (j - r).coerceIn(0, len - 1) * step]
                val ci = src[base + (j + r + 1).coerceIn(0, len - 1) * step]
                sa += ((ci ushr 24) and 0xff) - ((co ushr 24) and 0xff)
                sr += ((ci ushr 16) and 0xff) - ((co ushr 16) and 0xff)
                sg += ((ci ushr 8) and 0xff) - ((co ushr 8) and 0xff)
                sb += (ci and 0xff) - (co and 0xff)
            }
        }
    }

    // ── Custom views ─────────────────────────────────────────────────────

    /** Blits the pre-baked [shadowBitmap] (never re-blurs); positioned via
     *  [syncShadow]'s translationY. */
    private inner class EdgeShadowView(c: Context) : View(c) {
        // A backgroundless View has WILL_NOT_DRAW set → onDraw is skipped. Clear it
        // (same as HandleView) or the baked shadow never blits.
        init { setWillNotDraw(false) }
        override fun onDraw(canvas: Canvas) {
            shadowBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        }
    }

    /** The sheet surface. Draws the frosted backdrop FIRST (before super → under the
     *  translucent fill background and the content), scaled to full screen so the
     *  body shows the captured frame's top strip 1:1; clipToOutline rounds it. */
    private inner class BodyView(c: Context) : FrameLayout(c) {
        override fun draw(canvas: Canvas) {
            backdropSmall?.let {
                // Align the fullscreen-scaled frost with the screen region the
                // body actually covers — it no longer sits at y = 0, and it
                // moves per frame during entrance/exit/resize.
                val yOff = (panel.top + panel.translationY + top).toInt()
                backdropDst.set(0, -yOff, screenW, screenH - yOff)
                canvas.drawBitmap(it, null, backdropDst, backdropPaint)
            }
            super.draw(canvas)
        }
    }

    /** Full-screen transparent host. Owns the resize gesture (so its touch zone
     *  can reach below the panel's visible bottom — a child can't receive touches
     *  past the parent's bounds) and the tap-outside dismiss. A DOWN in the resize
     *  band drags the panel height; a DOWN below that band is on the game and
     *  dismisses. (Rotation dismisses too, via the controller's display listener.) */
    private inner class CaptureResultRoot(c: Context) : FrameLayout(c) {
        // Sliver gesture state: a DOWN on the sliver zone becomes either a tap
        // (auto-expand to the pre-collapse height) or a drag (the user pulls the
        // sheet edge themselves; see endSliverDrag for the commit threshold).
        private var sliverTouch = false
        private var sliverDragging = false
        private var sliverDownRawY = 0f

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (sliverMode) {
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        val panelTop = panel.top + panel.translationY
                        // A touch on the sliver (plus the same above-edge band the
                        // resize grab uses) starts a tap-or-drag; anywhere else
                        // dismisses boxes and sliver together.
                        if (ev.y >= panelTop - dp(EXTRA_GRAB_PAST_EDGE_DP)) {
                            sliverTouch = true
                            sliverDragging = false
                            sliverDownRawY = ev.rawY
                        } else {
                            dismissFromSliver()
                        }
                    }
                    MotionEvent.ACTION_MOVE -> if (sliverTouch) {
                        // Bottom sheet: dragging UP from the sliver expands.
                        val dy = sliverDownRawY - ev.rawY
                        if (!sliverDragging && dy > touchSlop) {
                            sliverDragging = true
                            beginSliverDrag()
                        }
                        if (sliverDragging) updateSliverDrag(dy)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (sliverTouch) {
                        sliverTouch = false
                        when {
                            sliverDragging -> endSliverDrag()
                            ev.actionMasked == MotionEvent.ACTION_UP -> expandFromSliver()
                        }
                        sliverDragging = false
                    }
                    MotionEvent.ACTION_OUTSIDE -> dismissFromSliver()
                }
                // The sliver has no inner interactions — consume everything so
                // no child ever sees a gesture that started under sliver rules.
                return true
            }
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // The switch pill straddles the resize band — route touches on
                    // it to the pill (a root child) instead of starting a resize.
                    if (showOnScreenPill.visibility == View.VISIBLE && pillHit(ev)) {
                        return super.dispatchTouchEvent(ev)
                    }
                    val panelTop = panel.top + panel.translationY
                    // Resize zone = the grabber strip inside the sheet's top +
                    // EXTRA_GRAB_PAST_EDGE_DP above it, past the sheet's edge.
                    val resizeTop = panelTop - dp(EXTRA_GRAB_PAST_EDGE_DP)
                    val resizeBottom = panelTop + dp(HANDLE_HEIGHT_DP)
                    if (ev.y >= resizeTop && ev.y <= resizeBottom) {
                        beginResize(ev.rawY)
                        return true
                    }
                    // Above the sheet, or in the nav-bar gap below it when the
                    // sheet is lifted — both are "outside" and dismiss.
                    if (ev.y < resizeTop || ev.y > panel.bottom + panel.translationY) {
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

        /** Whether [ev] lands on the switch pill (with a small slop halo). The
         *  pill is positioned by translationY off a TOP|END layout slot, so
         *  hit-test its translated on-screen rect, not its layout bounds. */
        private fun pillHit(ev: MotionEvent): Boolean {
            val slop = dp(8)
            val px = showOnScreenPill.left.toFloat()
            val py = showOnScreenPill.top + showOnScreenPill.translationY
            return ev.x >= px - slop && ev.x <= px + showOnScreenPill.width + slop &&
                ev.y >= py - slop && ev.y <= py + showOnScreenPill.height + slop
        }
    }

    /** The visible bottom sheet. When the content fits (no inner scroll), a
     *  vertical down-drag/fling on the body dismisses (swipe-to-dismiss). When
     *  the content is scrollable it scrolls instead, and dismissal is via the
     *  grabber fling or a tap outside — the sheet rule that keeps scroll vs
     *  dismiss unambiguous. Drags starting on the grabber strip are left to the
     *  resize listener. */
    private inner class BottomSheetPanel(c: Context) : LinearLayout(c) {
        private var downX = 0f
        private var downY = 0f
        private var downRawY = 0f
        private var dragging = false
        private var dragTracker: VelocityTracker? = null

        private fun contentScrollable() =
            scroll.canScrollVertically(1) || scroll.canScrollVertically(-1)

        private fun inHandle(y: Float) = y <= dp(HANDLE_HEIGHT_DP)

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
                    // Down only — this is a dismiss gesture, not a reposition.
                    translationY = maxOf(0f, ev.rawY - downRawY)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!dragging) return false
                    val vy = dragTracker?.let {
                        it.addMovement(ev); it.computeCurrentVelocity(1000); it.yVelocity
                    } ?: 0f
                    dragTracker?.recycle(); dragTracker = null
                    dragging = false
                    // The predicate is written for the top sheet (negative = away);
                    // mirror both inputs rather than fork the geometry helper.
                    if (CaptureResultGeometry.shouldDismissFromDrag(
                            -translationY, -vy, DISMISS_DISTANCE_DP * density, FLING_DISMISS_VEL,
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

    /** A centered grab-pill in the transparent strip above the sheet. */
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
        /** Fire-and-forget one-tap Anki sends run here, NOT on [scope] — the overlay
         *  cancels [scope] on dismiss, which would silently kill an in-flight send
         *  (no card, no result toast). Process-lived; mirrors SourceLensActions.sendScope.
         *  The toasts target the app context, so they still fire after the panel is gone. */
        private val ankiSendScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

        const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
        const val HANDLE_HEIGHT_DP = 20
        /** Height of the "Show on screen" switch pill; it sits centered on the
         *  sheet's bottom edge, half over the fill and half over the handle strip. */
        const val SWITCH_PILL_HEIGHT_DP = 30
        /** Sheet-fill strip left visible (below the grabber strip) when the
         *  panel collapses to its sliver state. */
        const val SLIVER_SHEET_DP = 12
        /** Duration of the collapse-to-sliver / expand-from-sliver slide. */
        const val SLIVER_DURATION_MS = 220L
        /** Duration of the section fade that rides the sliver transitions. */
        const val SLIVER_FADE_MS = 150L
        /** How far past the sheet's edge the resize grab zone extends. */
        const val EXTRA_GRAB_PAST_EDGE_DP = 26
        /** Blurry drop shadow above the sheet's rounded top edge (baked once).
         *  The OUTER blur lands the cast edge at ~half this alpha (visible peak
         *  ~100/255). Tune these two for darker/softer. */
        const val SHADOW_BLUR_DP = 11f
        const val SHADOW_ALPHA = 200
        /** Sheet fill opacity (255 = opaque). Lower → more of the frosted backdrop
         *  shows through the tint. */
        const val SHEET_ALPHA = 205
        /** Text-card fill opacity (0–1) — very slightly translucent so the frost
         *  shows faintly behind the text too. */
        const val CARD_FILL_ALPHA = 0.8f
        /** Backdrop downscale before the box blur (cheapness; the blur does the
         *  smoothing now, so this no longer needs to be aggressive). */
        const val BACKDROP_DOWNSCALE = 6
        /** Box-blur radius (in downscaled px) — the main blur dial. */
        const val BACKDROP_BLUR_RADIUS = 5
        const val SECTION_H_PAD_DP = 12
        /** Space below the filled side-by-side cards (to the panel's bottom). */
        const val SIDE_BY_SIDE_BOTTOM_BUFFER_DP = 6
        /** Space below the stacked content (to the panel's bottom). */
        const val STACKED_BOTTOM_BUFFER_DP = 4
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
