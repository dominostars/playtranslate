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
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.text.InputType
import android.text.Layout
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
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
import com.playtranslate.fillOneShotOverlayData
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
import com.playtranslate.model.PendingTranslation
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
    /** Where the sheet lives — an overlay window by default (the floating-icon
     *  flow), or an activity view tree ([ActivitySheetHost]) for in-app hosts
     *  like the camera tool. */
    private val sheetHost: SheetHost = WindowSheetHost(wm, displayId, overlayHost),
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

    // ── Host behavior overrides (in-app hosting, e.g. the camera tool) ────
    // Everything defaults to the over-game behavior; an in-app host swaps
    // these for activity-local equivalents. Set before show().

    /** Presents the "show on screen" boxes somewhere other than the default
     *  in-window chips view (the camera renders its own warp overlays).
     *  [show] returning false falls back to the panel presentation. */
    interface BoxPresenter {
        fun show(data: OneShotOverlayData): Boolean
        fun update(data: OneShotOverlayData)
        fun hide()
    }

    var boxPresenter: BoxPresenter? = null

    /** The sheet's two persisted presentation axes, independent per host flow.
     *  [boxesEnabled] is the header toggle's state — whether results paint
     *  their boxes over the frame — written the moment the toggle is tapped.
     *  [startPosture] is where the user left the panel: parked in the sliver,
     *  or the height they dragged it to (see [CaptureResultGeometry]'s posture
     *  encoding) — read when a flow starts and written on dismissal ("it opens
     *  how you left it"). Default: the over-game capture prefs; the camera
     *  substitutes its own pair. */
    interface PresentationPrefs {
        var boxesEnabled: Boolean
        var startPosture: Float
    }

    var presentationPrefs: PresentationPrefs = object : PresentationPrefs {
        override var boxesEnabled: Boolean
            get() = prefs.captureBoxesEnabled
            set(value) {
                prefs.captureBoxesEnabled = value
            }
        override var startPosture: Float
            get() = prefs.capturePanelPosture
            set(value) {
                prefs.capturePanelPosture = value
            }
    }

    /** One re-translation outcome for the in-place edit — the subset of the
     *  service's GroupTranslation the panel binds. */
    data class PanelTranslation(val text: String, val note: String?, val backendDisplayName: String?)

    /** In-place-edit re-translation. Default (null): the capture service's
     *  translateOnce. */
    var retranslate: (suspend (String) -> PanelTranslation?)? = null

    /** Deferred-translation completion: the bound result skipped MT because the
     *  translation section was hidden ([TranslationResult.pendingTranslation])
     *  and a consumer now needs it. Default (null): the capture service's
     *  [CaptureService.completeDeferredTranslation], which also attaches the
     *  capture's History rows. Hosts on the CameraTranslator stack (Process-
     *  Text) substitute their own translator. Returning null means "no
     *  translator available" — the pending stays set and the next trigger
     *  retries; a non-null return is terminal for the pending. */
    var completeDeferred: (suspend (PendingTranslation) -> List<PanelTranslation>?)? = null

    /** Bumped per deferred-completion launch so a stale run can't rebind over
     *  a newer trigger's result (mirror of the edit path's editGeneration). */
    private var deferredGeneration = 0

    /** The pending the in-flight completion launch is working on. A repeat
     *  trigger for the SAME pending (eye reveal + show-on-screen back to
     *  back) must not launch a second backend batch — the duplicate's History
     *  attach would find the rows already filled and fresh-insert spurious
     *  ones. A DIFFERENT pending (newer result) is not blocked; it supersedes
     *  the old run via the generation bump. Cleared in the launch's finally
     *  (generation-checked) so a kept-pending failure can retry later. */
    private var deferredInFlight: PendingTranslation? = null

    /** The OCR-engine affordance (a Ready result's gear + the no-text
     *  status). Default (null): the overlay-window picker + in-place
     *  service re-OCR. */
    var chooseOcr: ((OcrProvenance, String) -> Unit)? = null

    /** The language headers' tap action. Default (null): dismiss this sheet
     *  and open the language picker — the user re-captures on return. A host
     *  that still holds its input across the round trip (the PROCESS_TEXT
     *  flow keeps the selected string) overrides this to launch the picker
     *  WITHOUT tearing the sheet down, then re-drives [observe] on return. */
    var chooseLanguage: ((isSource: Boolean) -> Unit)? = null

    /** TTS "no engine" alert host for the speak buttons. Default (null): an
     *  overlay window on this sheet's display. */
    var ttsAlertTarget: TtsAlertTarget? = null

    /** The source-tap word lens spawns its own overlay window, which an
     *  in-app host has no business creating; false disables the tap. */
    var wordLensEnabled: Boolean = true

    /** Host the tap-a-word lens as an ACTIVITY window (TYPE_APPLICATION_PANEL
     *  through [wm], which must then be an Activity's WindowManager) instead
     *  of an overlay window — the in-app hosting the camera snapshot panel
     *  uses. Also reroutes the lens actions' Anki-not-installed dialog
     *  through [showAnkiNotInstalled] and drops the capture-overlay detail
     *  return tag (there is no over-game overlay to signal back to). */
    var wordLensInActivity: Boolean = false

    /** Card-level Anki "not installed" dialog. Default (null): the
     *  overlay-window dialog. */
    var showAnkiNotInstalled: (() -> Unit)? = null

    /** Whether launching a full-screen activity (the Anki review) tears the
     *  sheet down. True for the over-game window — it would otherwise sit
     *  ABOVE the launched activity. False for in-app hosts: their activity
     *  naturally goes behind the launch, and backing out of it should find
     *  the sheet (and the camera's frozen frame) exactly as left. */
    var dismissOnActivityLaunch: Boolean = true

    /** Routes outside-the-panel gestures (both presentations) to a host-side
     *  consumer instead of the dismiss/ignore defaults — the camera hands
     *  them to its frozen-frame word lookup. Receives the full DOWN..UP
     *  stream; returning false on the DOWN declines the gesture (nothing
     *  under the finger) and the default outside behavior applies. Never
     *  consulted for sliver-band, grabber, or in-panel touches. */
    var outsideLookupRouter: ((android.view.MotionEvent) -> Boolean)? = null

    /** Whether gestures dismiss the sheet: tap-outside, swipe/fling-down,
     *  and the sliver's tap-away. True for the over-game window (the sheet
     *  is a guest over the game; every exit ramp matters). False for the
     *  camera host, where dismissal means leaving the frozen snapshot — that
     *  exit is the explicit X only; gated gestures settle back (drag clamps
     *  at the resize floor, outside taps are consumed and ignored). */
    var dismissOnGesture: Boolean = true

    /** Controller (dpad / stick / A / B) navigation of this sheet. Requires an
     *  overlay-window host: the window takes input focus while the sheet is up
     *  — INCLUDING the sliver — which takes the controller away from the game.
     *  Off by default (against this block's over-game-default convention,
     *  deliberately: stealing the game's controller must be an explicit act);
     *  the over-game flow opts in, and only a connected controller at show()
     *  time actually arms it. In-activity hosts keep their Activity's own
     *  back/focus handling. */
    var controllerNavEnabled: Boolean = false

    /** Live only while [controllerNavEnabled] found a controller at show(). */
    private var nav: CaptureSheetControllerNav? = null

    /** Firms up the sheet + text-card fills for hosts with no screenshot
     *  behind the panel: the translucency is tuned for the frosted backdrop,
     *  and un-blurred live app content bleeding through reads as distracting
     *  haze. Goes near-opaque ([SHEET_ALPHA_NO_IMAGE] /
     *  [CARD_FILL_ALPHA_NO_IMAGE]) — barely transparent. Set before [show]. */
    var opaqueBackgroundBoost: Boolean = false

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
     *  sliver until it starts expanding back. Root touch handling swaps to
     *  sliver rules while set. Purely the PANEL's position — the on-screen
     *  boxes are the independent [PresentationPrefs.boxesEnabled] axis. */
    private var sliverMode = false

    /** Whether the on-screen boxes are currently up (via [showChips]) — the
     *  live counterpart of the persisted toggle, so Done knows to promote
     *  in place ([updateChips]) vs present fresh, and hide paths can no-op. */
    private var boxesShown = false

    /** Sliver-only "drag up for more options" hint (see the body's addView). */
    private val sliverHint = TextView(ctx)

    /** The panel height when the sliver collapse started, so a tap-expand can
     *  return to it (the sliver itself parks the height at [sliverHeightPx]). */
    private var preSliverHeightPx = 0

    /** End target of the height animation currently in [heightAnimator];
     *  meaningful only while it runs, stamped by the two starters
     *  ([animateSliverHeight] / [animatePanelHeight]). Lets [applyStatusFloor]
     *  decide whether an in-flight animation already lands at/above a status's
     *  floor or must be retargeted — the ANIMATOR owns the height per-frame,
     *  so a plain floor write during flight is overwritten and useless. */
    private var heightAnimatorTargetPx = 0

    /** One-shot: after a bind with the translation section hidden, the next
     *  layouts park the scroll just past its collapsed header (see hiddenTopPx).
     *  Cleared once the scroll lands so later user scrolling is untouched. */
    private var pendingHiddenTopScroll = false

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
    // A soft drop shadow cast above the sheet's top edge. The blur is BAKED ONCE
    // into [shadowBitmap]; the view only blits it and is repositioned via
    // translationY as the sheet grows/slides — never re-blurred (see [bakeEdgeShadow]).
    private val edgeShadow = EdgeShadowView(ctx)
    // The controller cursor's accent ring, drawn at ROOT level (over the panel,
    // under the font popover's late-added scrim) so it can outline buttons and
    // word spans alike without fighting any child clipping.
    private val focusRing = FocusRingView(ctx)
    private var shadowBitmap: Bitmap? = null
    // The shadow tracks the sheet through a single pre-draw hook (see [syncShadow])
    // rather than per-mover wiring — so no drag/animation path can move the sheet
    // and leave the shadow behind.
    private var shadowSync: ViewTreeObserver.OnPreDrawListener? = null
    // Frosted backdrop: the captured screenshot blurred ONCE (a cheap downscale),
    // drawn at full-screen scale UNDER the translucent sheet fill and clipped to
    // the rounded body. Static — no live re-blur.
    private var backdropSmall: Bitmap? = null

    /** The sheet fill inside [body]'s InsetDrawable — held as a field so
     *  [show] can firm it up for no-backdrop hosts ([opaqueBackgroundBoost]). */
    private val sheetFill = GradientDrawable()
    private val backdropPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val backdropDst = Rect()
    /** Frost offset the body's display list was last recorded with — the
     *  pre-draw hook re-invalidates the body when it drifts (see syncShadow). */
    private var lastFrostOff = Int.MIN_VALUE

    private var binder: TranslationSectionBinder? = null

    /** Text-size range picker. Hosted in [root] — the sheet's own full-screen
     *  window — so it can float above the sheet's top edge over the game while
     *  staying an IN-WINDOW child (a sibling overlay window would dim under
     *  MediaProjection's QTI clamp and eat the panel's taps). */
    private var fontPopover: FontSizeRangePopover? = null

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
        sliverHint.apply {
            setText(R.string.capture_sliver_expand_hint)
            val hintColor = ctx.themeColor(R.attr.ptTextHint)
            setTextColor(hintColor)
            textSize = 11f
            isSingleLine = true
            // A touch_app glyph leads the text — the collapsed sheet's gesture
            // is a TAP, and the up-arrows that used to flank this said the
            // opposite. RELATIVE (start-side) so it leads in RTL too, tinted
            // with the text and sized to its line — the 24dp intrinsic would
            // dwarf 11sp text.
            val iconPx = dp(16)
            val icon = ctx.getDrawable(R.drawable.ic_touch_app)?.mutate()?.apply {
                setTint(hintColor)
                setBounds(0, 0, iconPx, iconPx)
            }
            setCompoundDrawablesRelative(icon, null, null, null)
            compoundDrawablePadding = dp(4)
            visibility = View.GONE
            alpha = 0f
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
                sheetFill.apply {
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
            // Sliver-only "drag up" hint: lives in the sheet strip the
            // collapse leaves visible ([SLIVER_SHEET_DP] is sized to fit it),
            // fading in as the sections fade out. Non-clickable — taps fall
            // through to the root's sliver rules (tap = expand).
            addView(
                sliverHint,
                FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
                    .apply { topMargin = dp(3) },
            )
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
        // After the panel so the ring draws over the sheet; a plain non-clickable
        // View, so in-panel touches fall through it to the panel below.
        root.addView(focusRing, FrameLayout.LayoutParams(MATCH, MATCH))
        // One-shot after each bind: park the scroll just past the hidden
        // translation section's collapsed header (see hiddenTopPx). Layout-
        // driven because the needed scroll range only exists once the grow
        // animation's card fill has laid out.
        scroll.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (!pendingHiddenTopScroll) return@addOnLayoutChangeListener
            // Stable-state recheck: a pref flip or layout-mode change while
            // waiting cancels the parking rather than leaving it pending.
            if (isSideBySide || !prefs.hideTranslationSection) {
                pendingHiddenTopScroll = false
                return@addOnLayoutChangeListener
            }
            // Await measurement: 0 means the target header hasn't laid out yet
            // (fresh overlay, showWithResult) — not "nothing to park".
            if (hiddenTopPx() <= 0) return@addOnLayoutChangeListener
            // Settle 3dp shy of fully off-screen — parking flush clipped the
            // source header's first pixels.
            val target = hiddenTopPx() - dp(3)
            scroll.scrollTo(0, target)
            if (scroll.scrollY >= target) pendingHiddenTopScroll = false
        }
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
        // Bottom sheet: no status-bar inset (the sheet's top edge is mid-screen);
        // a hairline of breathing room above the content, the rest carried by
        // the section headers' own top padding. The height formulas reserve
        // HANDLE_HEIGHT_DP (the grabber strip above the sheet) + topInsetPx,
        // so they stay in agreement.
        topInsetPx = dp(1)
        body.setPadding(0, topInsetPx, 0, 0)
        // "It opens how you left it": the remembered posture seeds the auto-size
        // ceiling, so the grow-to-fit below stops at the height the user last
        // dragged the sheet to instead of the default 50% — and, since it is
        // only a CEILING, still stops short of it when the result needs less.
        val startPosture = effectiveStartPosture()
        autoMaxPx = CaptureResultGeometry.postureCeiling(startPosture, screenH)
        // Load at the minimum (drag-resize floor) height; grow to fit on Done.
        // A flow last dismissed from the collapsed sliver starts parked THERE
        // instead — results land as boxes while the panel waits at the bottom
        // edge until pulled up — but only while there ARE boxes to land in
        // (see effectiveStartPosture). setPanelHeight's crossfade sets the
        // parked visuals (content transparent, hint up) from frame one.
        panelHeightPx = CaptureResultGeometry.minPanelHeight(screenH)
        (panel.layoutParams as FrameLayout.LayoutParams).height = panelHeightPx
        if (CaptureResultGeometry.isCollapsedPosture(startPosture)) {
            sliverMode = true
            setPanelHeight(sliverHeightPx())
        }
        shadowBitmap = bakeEdgeShadow(screenW)
        edgeShadow.invalidate()
        // A no-backdrop host asked for firmer fills — recolor the sheet fill
        // built at init (the flag lands after construction, before show()).
        if (opaqueBackgroundBoost) {
            val bg = ctx.themeColor(R.attr.ptBg)
            sheetFill.setColor(
                Color.argb(SHEET_ALPHA_NO_IMAGE, Color.red(bg), Color.green(bg), Color.blue(bg)),
            )
        }
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
            ttsAlertTarget ?: TtsAlertTarget.Overlay(ctx, overlayHost, wm, displayId),
        )
        b.setupSectionButtons(
            onEdit = { startInPlaceEdit() },
            onAddToAnki = { openSentenceAnkiReview() },
            onAnkiOneTap = { oneTapSentenceFromOverlay() },
        )
        // Overlay-only: the target header's show-on-screen toggle flips the
        // on-frame boxes — accent while ON — without moving the panel.
        b.setShowOnScreenAction { toggleBoxes() }
        b.onSectionVisibilityChanged = {
            applySideBySideCollapse()
            // Stacked hidden-translation parks the collapsed header above the
            // scroll fold; bindResult arms the park for fresh results, and a
            // LIVE eye toggle must arm it too — every height formula subtracts
            // hiddenTopPx on the assumption the header is scrolled off, so an
            // unparked toggle left the panel (and its drag ceiling) exactly
            // one header short: the source text clipped at full drag. The
            // un-hide direction unwinds the park, else the just-revealed
            // translation section starts half-scrolled-off.
            if (!isSideBySide && prefs.hideTranslationSection) {
                pendingHiddenTopScroll = true
            } else if (scroll.scrollY != 0) {
                scroll.scrollTo(0, 0)
            }
            // A section was hidden/shown — grow/shrink the panel to the new content.
            // Two frames: the collapse AND the other column's re-widen must settle
            // before we measure, else we'd size to the stale pre-collapse layout.
            // Same settle window before the controller cursor re-targets: its
            // activated eye may have swapped a whole column for its strip.
            contentRow.post {
                contentRow.post {
                    if (dismissed) return@post
                    if (lastResult != null) autoSizeAndFit()
                    nav?.revalidateCursor()
                }
            }
            // Eye reveal on a deferred result: run the skipped translation now.
            maybeCompleteDeferred()
        }
        // Furigana changes the source's rendered height (async on / sync off) — re-fit.
        b.onSourceTextHeightChanged = { if (!dismissed) autoSizeAndFit() }
        b.setCardFillAlpha(if (opaqueBackgroundBoost) CARD_FILL_ALPHA_NO_IMAGE else CARD_FILL_ALPHA)
        b.onChooseOcr = {
            val r = lastResult
            val p = r?.ocrProvenance
            val sp = r?.screenshotPath
            if (p != null && sp != null) showOcrPicker(p, sp)
        }
        b.onChooseLanguage = { isSource -> changeLanguage(isSource) }
        fontPopover = FontSizeRangePopover(ctx, root, prefs).apply {
            onRangeChanged = { refitForFontRange() }
        }
        b.onChooseFontSize = { fontPopover?.toggle(b.fontSizeAnchor) }
        binder = b
        // Reflect any persisted hide prefs (shared with the results page) up front.
        applySideBySideCollapse()

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
        // Controller navigation arms only when the over-game flow opted in AND a
        // controller is actually attached — evaluated once, here: no device
        // listener, so a controller plugged in later gets nav on the NEXT sheet.
        // The window is created already-focusable (no async flag flip to race),
        // which also lands the OverlayHost focusable-overlay paper trail on add.
        val navActive = controllerNavEnabled && hasGameController(ctx)
        sheetHost.attach(root, screenW, screenH, focusable = navActive)
        if (navActive) {
            // The root holds VIEW focus so the framework's restoreDefaultFocus
            // can't wander into an ImageButton on window-focus gain and paint a
            // stock highlight next to our ring — and suppress the full-root
            // focus rectangle, same as MagnifierLens's interactive card.
            root.isFocusable = true
            root.isFocusableInTouchMode = true
            root.defaultFocusHighlightEnabled = false
            root.requestFocus()
            nav = CaptureSheetControllerNav(ctx, navHost)
        }
        // ONE place that keeps the drop shadow glued to the sheet: a pre-draw hook
        // re-reads the panel's live position every frame, so the shadow follows
        // through any move (handle drag, body swipe/fling, resize, entrance/exit)
        // with no per-mover wiring to forget. The controller focus ring rides the
        // same hook for the same reason.
        shadowSync = ViewTreeObserver.OnPreDrawListener { syncShadow(); nav?.syncRing(); true }.also {
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
                        // Skeleton boxes exist from OCR time — expose them BEFORE the
                        // bind so the header's boxes toggle is offered while the
                        // translation is still running (the Done promotion fills
                        // them in place either way).
                        overlayData = state.overlayData
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
                        // Boxes are their own axis: skeletons go up the moment OCR
                        // lands, whatever the panel is doing (a re-run with boxes
                        // already showing swaps them in place). (Fits while
                        // slivered run measure-only — they set the drag ceiling +
                        // card chrome but never animate the height.)
                        if (presentationPrefs.boxesEnabled) {
                            state.overlayData?.let {
                                if (boxesShown) updateChips(it) else showChips(it)
                            }
                        }
                        // Collapsed-start may park the panel (or keep show()'s
                        // park) only while the sliver has something to stand on:
                        // boxes actually painted, or the user's persisted
                        // boxes-off posture. When boxes were EXPECTED but reality
                        // refused them — the overlay surface is live mode's, or
                        // OCR produced nothing paintable — a sliver would leave
                        // NOTHING readable, so the panel comes (or stays) up
                        // instead: the old success-gated collapse, split across
                        // the two axes.
                        val sliverJustified =
                            boxesShown || !presentationPrefs.boxesEnabled
                        val startsCollapsed = CaptureResultGeometry
                            .isCollapsedPosture(effectiveStartPosture())
                        if (startsCollapsed && sliverJustified) {
                            collapseToSliver()
                        } else if (sliverMode && !sliverJustified) {
                            expandFromSliver()
                        }
                        // bindResult rendered the toggle before the paint
                        // attempt above — re-render now that boxesShown has
                        // settled (same main-thread dispatch, so no flash).
                        updateShowOnScreenAction()
                    }
                    is CaptureState.Done -> {
                        overlayData = state.overlayData
                        val data = state.overlayData
                        if (data == null) {
                            // Nothing paintable came back: skeletons must not
                            // pulse forever, and a sliver waiting on boxes has
                            // nothing left to show — bring the panel back.
                            hideChips()
                            if (sliverMode && presentationPrefs.boxesEnabled) {
                                expandFromSliver()
                            }
                        } else if (presentationPrefs.boxesEnabled) {
                            // Promote showing skeletons in place; first paint
                            // otherwise (e.g. the toggle was flipped on after
                            // OCR, or the boxes surface was busy at Translating).
                            if (boxesShown) updateChips(data) else showChips(data)
                            // Boxes expected but refused (the surface got
                            // claimed since Translating): a sliver with nothing
                            // behind it is unreadable — bring the panel back.
                            if (sliverMode && !boxesShown) expandFromSliver()
                        }
                        bindResult(state.result)
                    }
                    is CaptureState.NoText -> {
                        // A re-run (OCR gear, camera region change) can land
                        // NoText while boxes are up — they'd be a stale scene
                        // over "no text", and a status is unreadable in a
                        // sliver: take the boxes down and bring the panel up
                        // to status height.
                        hideChips()
                        if (sliverMode) expandFromSliverForStatus()
                        setStatus(state.message, state.ocrProvenance, state.screenshotPath)
                    }
                    is CaptureState.Failed -> {
                        // A translation failure with skeletons up must not leave
                        // them pulsing forever, and the status is unreadable in
                        // a sliver — same recovery as NoText.
                        hideChips()
                        if (sliverMode) expandFromSliverForStatus()
                        setStatus(state.message)
                    }
                    CaptureState.Cancelled -> dismiss()
                }
            }
        }
    }

    /** A read-settings refresh (source language / OCR engine changed)
     *  restarts the capture flow from its loading state: leave the sliver
     *  (a status is unreadable there) and take the on-screen boxes down —
     *  everything they show is invalidated. Same recovery pair as
     *  Failed/NoText. Region re-runs deliberately do NOT call this: their
     *  boxes stay up and swap in place. A collapsed-start flow re-parks via
     *  the re-run's Translating handler, with fresh skeletons when the boxes
     *  toggle is on — the full first-snapshot arc.
     *
     *  [preservePosture] (the paged import's CACHED page flips): the new
     *  scene publishes straight to Done with no loading phase worth
     *  reading, so a parked sliver stays parked instead of bouncing
     *  open-and-closed. */
    fun prepareForSettingsRefresh(preservePosture: Boolean = false) {
        hideChips()
        // Status height, not the reading height: the expansion only exists to
        // show the loading arc, and the Translating handler re-parks a
        // collapsed posture — restoring the previous scene's fitted height
        // here ballooned the sheet open on every collapsed page flip.
        if (sliverMode && !preservePosture) expandFromSliverForStatus()
    }

    /** Persist the LIVE panel posture. Page switches in the paged import
     *  are posture boundaries exactly like dismissals ("it opens how you
     *  left it") — but the panel survives them, so the dismissal-time
     *  record never runs. */
    fun persistPosture() = recordPosture()

    /** The remembered posture a flow should actually open at. A COLLAPSED one
     *  is honored only while the boxes toggle has somewhere to put the result:
     *  with boxes off, starting parked in the sliver lands a capture with
     *  nothing readable on screen at all — no panel, no boxes — so the sliver
     *  is dropped and the sheet opens at the plain fit-to-content default, as
     *  if no posture had ever been recorded. A remembered HEIGHT always stands:
     *  it still shows the text. (Boxes that were expected but refused at paint
     *  time are the separate, live sliverJustified check in [observe].) */
    private fun effectiveStartPosture(): Float {
        val posture = presentationPrefs.startPosture
        val blindStart = CaptureResultGeometry.isCollapsedPosture(posture) &&
            !presentationPrefs.boxesEnabled
        return if (blindStart) CaptureResultGeometry.NO_POSTURE else posture
    }

    /** Record where the user is leaving the panel: the sliver, or the auto-size
     *  ceiling their last drag committed — the DEFAULT ceiling when they never
     *  dragged, which is the point of recording the ceiling rather than the
     *  live height: an untouched session would otherwise ratchet the next one
     *  down to whatever result happened to be on screen.
     *
     *  Only while a result is actually being PRESENTED. lastResult alone is not
     *  enough: a Translating placeholder sets it, and a translation failure then
     *  swaps to a status via setStatus() without clearing it — recording there
     *  would let a failed capture silently flip the start posture. Every status
     *  path hides the scroll; both real states (expanded panel, sliver) keep it
     *  visible. The boxes toggle is NOT recorded here — it persists itself the
     *  moment it's tapped. */
    private fun recordPosture() {
        if (lastResult == null || scroll.visibility != View.VISIBLE) return
        presentationPrefs.startPosture =
            CaptureResultGeometry.postureFor(autoMaxPx, screenH, collapsed = sliverMode)
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
        // "It opens how you left it" — see recordPosture for what qualifies.
        recordPosture()
        heightAnimator?.cancel()
        dismissWordLens()
        fontPopover?.dismiss()
        fontPopover = null
        sessionJob?.cancel()
        // Cancel the service-side one-shot job too (not just our collector), so
        // OCR/translation doesn't keep running headless after the panel is gone.
        captureSession?.cancel()
        captureSession = null
        binder?.release()
        nav?.release()
        nav = null
        shadowSync?.let { root.viewTreeObserver.removeOnPreDrawListener(it) }
        shadowSync = null
        sheetHost.detach(root)
        shadowBitmap?.recycle()
        shadowBitmap = null
        backdropSmall?.recycle()
        backdropSmall = null
        scope.cancel()
        onDismiss?.invoke()
    }

    /** Slide the panel up off the top edge, then remove it — so tap-outside and
     *  swipe/fling-down dismissals (and the capture-screen hotkey's toggle-off,
     *  via [OverlayUiController.animateOutCaptureResultOverlay]) animate out
     *  instead of vanishing. Idempotent while the exit is in flight. Other paths
     *  (Cancelled, supersede, teardown) call [dismiss] directly for immediate
     *  removal. */
    fun animateOutAndDismiss() {
        if (dismissed || animatingOut) return
        animatingOut = true
        dismissWordLens()
        fontPopover?.dismiss()
        nav?.clearCursor()   // no ring riding the exit slide
        panel.animate()
            .translationY(panelHeightPx.toFloat())
            .setDuration(EXIT_DURATION_MS)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction { dismiss() }
            .start()
    }

    // ── Sliver state (result shown as on-screen boxes) ───────────────────

    /** Paint [data]'s boxes over the game (bottom-most child of this window),
     *  or hand them to the host's [boxPresenter] (the camera's warp overlays).
     *  False when the surface isn't ours to draw on (live mode). */
    private fun showChips(data: OneShotOverlayData): Boolean {
        boxPresenter?.let {
            if (it.show(data)) {
                boxesShown = true
                return true
            }
            return false
        }
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
        boxesShown = true
        return true
    }

    /** Swap the boxes in place — the skeleton → translated promotion when Done
     *  lands while slivered. No-op unless the boxes are up. */
    private fun updateChips(data: OneShotOverlayData) {
        boxPresenter?.let {
            it.update(data)
            return
        }
        val v = chipsView ?: return
        if (v.alpha == 0f) return
        v.setBoxes(data.boxes, data.cropLeft, data.cropTop, data.screenshotW, data.screenshotH)
    }

    /** Fade the boxes out. The view stays attached (alpha 0) for cheap re-shows;
     *  it dies with the window. */
    private fun hideChips() {
        boxesShown = false
        boxPresenter?.let {
            it.hide()
            return
        }
        val v = chipsView ?: return
        v.animate().alpha(0f).setDuration(SLIVER_FADE_MS).start()
    }

    /** The header toggle: flip the on-frame boxes without moving the panel.
     *  Turning ON paints the bound result's boxes (skeletons while the
     *  translation is still running); the persisted state lands wherever
     *  reality landed — the overlay surface can refuse the paint (live mode
     *  owns it), and the accent state must never claim boxes that aren't
     *  there. */
    private fun toggleBoxes() {
        if (boxesShown) {
            presentationPrefs.boxesEnabled = false
            hideChips()
        } else {
            val data = overlayData ?: return
            presentationPrefs.boxesEnabled = showChips(data)
            // Boxes on a deferred result went up as skeletons — run the skipped
            // translation now so they fill instead of pulsing forever.
            maybeCompleteDeferred()
        }
        updateShowOnScreenAction()
    }

    /** Collapse the sheet to its bottom-edge sliver. Height-based (the sheet's
     *  bottom edge retracts, like the grow-to-fit in reverse) so the sliver
     *  drag below is the plain resize gesture with a lower floor; the
     *  content↔hint crossfade rides the height itself (see
     *  [applyCollapseCrossfade]). Purely the panel: the on-frame boxes are the
     *  toggle's independent axis. The user comes back via a tap (auto-expand)
     *  or a drag on the sliver zone. */
    private fun collapseToSliver() {
        if (dismissed || animatingOut || sliverMode) return
        if (editContainer.visibility == View.VISIBLE) return
        sliverMode = true
        dismissWordLens()
        // The sections it edits are about to fade out under the collapse.
        fontPopover?.dismiss()
        nav?.clearCursor()   // the cursor's targets are fading out
        preSliverHeightPx = panelHeightPx
        animateSliverHeight(sliverHeightPx())
    }

    /** A tap on the sliver: grow the sheet back to its pre-collapse height;
     *  the crossfade brings the sections back as the height climbs. */
    private fun expandFromSliver() {
        if (dismissed || animatingOut || !sliverMode) return
        sliverMode = false
        val target = preSliverHeightPx.coerceAtLeast(CaptureResultGeometry.minPanelHeight(screenH))
        animateSliverHeight(target) {
            updateShowOnScreenAction()
            // Re-run the fit the sliver suppressed (a collapsed-start lands
            // before the Done grow-to-fit ever ran, so the height may still be
            // the loading floor). Skipped when a status replaced the content
            // (the Failed-while-slivered recovery) — there's no text to fit.
            if (lastResult != null && scroll.visibility == View.VISIBLE) autoSizeAndFit()
        }
    }

    /** Expansion for a STATUS phase — a loading re-run (collapsed page flip,
     *  settings refresh) or a Failed/NoText landing while parked. The sheet
     *  comes up just enough to read a status: NOT the [preSliverHeightPx]
     *  reading height [expandFromSliver] restores — that's the PREVIOUS
     *  scene's fitted result (up to the drag ceiling), which drew as a
     *  mostly-empty sheet ballooning open over a one-line "Recognizing text…"
     *  on every collapsed page flip.
     *
     *  Targets the loading floor without knowing the status (on the loading
     *  path the message hasn't arrived yet). That's safe because the floor
     *  invariant doesn't live here: every [setStatus] RETARGETS an in-flight
     *  animation that would land below its measured need (see
     *  [applyStatusFloor]) — whichever status lands, first or a fast
     *  successor, the animation ends at/above it. */
    private fun expandFromSliverForStatus() {
        if (dismissed || animatingOut || !sliverMode) return
        sliverMode = false
        animateSliverHeight(CaptureResultGeometry.minPanelHeight(screenH)) {
            updateShowOnScreenAction()
        }
    }

    /** The sliver drag has passed touch slop: the user is pulling the sheet edge
     *  to a height of their choosing, so the drag owns the height from here
     *  (cancel any in-flight park/expand animation; the crossfade follows the
     *  dragged height). */
    private fun beginSliverDrag() {
        heightAnimator?.cancel()
    }

    /** Per-frame sliver drag: the resize math with the floor lowered to the
     *  sliver itself (the standard floor is the commit threshold, not a clamp,
     *  so the sheet must be able to sit below it mid-drag). Downward has nowhere
     *  to go — the sliver IS the bottom of the sheet's travel. */
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
     *  sheet settles back into the sliver. */
    private fun endSliverDrag() {
        if (dismissed) return
        if (panelHeightPx >= CaptureResultGeometry.minPanelHeight(screenH)) {
            sliverMode = false
            // Adopt the dragged height like endResize does, so re-fits keep it.
            autoMaxPx = committedCeiling()
            reFitText()
            updateShowOnScreenAction()
        } else {
            animateSliverHeight(sliverHeightPx())
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
        heightAnimatorTargetPx = target
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

    /** Height-driven content↔hint crossfade over the collapse band
     *  [sliverHeightPx, minPanelHeight): the sections (or status) fade out as
     *  the sheet approaches the sliver while the "drag up" hint fades in.
     *  Driven from [setPanelHeight] — the single point every height path flows
     *  through (drags, the park/expand animators, the collapsed start, inset
     *  re-parks) — so every path lands on identical visuals with no per-path
     *  fade wiring. Above the band it restores full content and retires the
     *  hint, making it a no-op for all normal resizing. */
    private fun applyCollapseCrossfade(h: Int) {
        val min = CaptureResultGeometry.minPanelHeight(screenH)
        val sliver = sliverHeightPx()
        val f = when {
            h >= min || min <= sliver -> 1f
            h <= sliver -> 0f
            else -> (h - sliver).toFloat() / (min - sliver).toFloat()
        }
        scroll.alpha = f
        statusText.alpha = f
        sliverHint.animate().cancel()
        sliverHint.alpha = 1f - f
        sliverHint.visibility = if (f >= 1f) View.GONE else View.VISIBLE
        // The pill goes with the content. Parked, it would sit inside the
        // system's bottom-edge home gesture band — a "grab me" invitation to
        // start a drag Android usually wins, taking the user out of the game
        // with it. The hint that fades in as it leaves says TAP instead, which
        // nothing contests. (The drag still works for anyone who reaches for
        // it, and the band it starts from reaches well above the gesture zone;
        // it just isn't advertised any more.)
        handle.alpha = f
    }

    /** The target header's boxes toggle is offered whenever there is something
     *  to paint — skeleton boxes count (a mid-translation flip shows the same
     *  pulsing placeholders). Deliberately NOT gated on sliverMode: the button
     *  is scroll content, so a collapse fades it out with the sections instead
     *  of yanking it to GONE, and it's unreachable while slivered anyway (the
     *  root consumes every touch). The accent state mirrors [boxesShown] — the
     *  boxes actually painted — NOT the persisted pref: the surface can refuse
     *  the paint (live mode), and the accent must never claim boxes that
     *  aren't there. The pref stays untouched as the standing intent; only a
     *  user tap re-lands it (see [toggleBoxes]). */
    private fun updateShowOnScreenAction() {
        binder?.setShowOnScreenAvailable(overlayData != null)
        binder?.setShowOnScreenToggled(boxesShown)
    }

    /** Extra content height beyond the visible body when the translation
     *  section (stacked mode's TOP section) is hidden via its eye: its collapsed
     *  header + the divider live ABOVE the scroll fold — the source card is
     *  sized as if they weren't there, and a fresh bind parks the scroll just
     *  below them (scrolling up still reveals the header to un-hide). */
    private fun hiddenTopPx(): Int {
        val b = binder ?: return 0
        if (isSideBySide || !prefs.hideTranslationSection) return 0
        val headerH = b.targetHeaderHeight()
        if (headerH <= 0) return 0
        // Just the header: the stacked layout has no divider, so nothing else
        // sits between the collapsed header and the source section — reserving
        // more here would show as phantom buffer under the hidden header.
        return headerH
    }

    /** The height the FIT machinery works against: the visible body plus the
     *  above-the-fold strip from [hiddenTopPx], so the visible sections fill
     *  the body exactly while the hidden header overflows into scroll range. */
    private fun fitBodyHeight(panelPx: Int): Int = contentHeight(panelPx) + hiddenTopPx()

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
        updateShowOnScreenAction()
        // The proportional loading floor (20% of screen height) is SHORTER than
        // the status block itself on a landscape screen — "Recognizing text"
        // rendered clipped to invisibility in horizontal mode. Floor the panel
        // at what the status actually measures; bindResult's own fit takes over
        // from there. NOT while slivered: a camera region re-run drives the
        // loading status through a parked sliver (boxes stay the presentation),
        // and growing the sheet here would yank it half-open.
        applyStatusFloor()
    }

    /** The panel height the CURRENT status text needs to render unclipped. */
    private fun statusFloorPx(): Int {
        statusText.measure(
            View.MeasureSpec.makeMeasureSpec(screenW, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        return statusText.measuredHeight + dp(HANDLE_HEIGHT_DP) + topInsetPx + bottomInsetPx
    }

    /** Enforce the floor invariant for the visible status: the panel must not
     *  END UP below what the status measures. Grow-only, and never while
     *  parked in the sliver (the region-re-run status deliberately rides it).
     *
     *  A height animation in flight owns panelHeightPx per-frame, so a plain
     *  write here would be overwritten — instead, an animation whose END
     *  target is below the need is RETARGETED from the current frame (the
     *  status expansion picked its target before this status existed — the
     *  fast InProgress→NoText succession). An animation already landing
     *  at/above the need keeps the height: it may be the user's tap-expand to
     *  a taller reading height, which must not be hijacked downward. Only the
     *  status expansion (or a status-visible user expand) can be running
     *  here: content-fit animations imply bindResult hid the status, and the
     *  sliver park/settle animations are excluded by the sliverMode guard. */
    private fun applyStatusFloor() {
        if (statusText.visibility != View.VISIBLE || sliverMode) return
        val needed = statusFloorPx()
        if (heightAnimator?.isRunning == true) {
            if (heightAnimatorTargetPx < needed) {
                animateSliverHeight(needed) { updateShowOnScreenAction() }
            }
        } else if (panelHeightPx < needed) {
            setPanelHeight(needed)
        }
    }

    private fun bindResult(result: TranslationResult) {
        val b = binder ?: return
        lastResult = result
        populateSentenceCache(result)
        nav?.clearCursor()   // fresh content: word indices + layout are stale
        statusText.visibility = View.GONE
        scroll.visibility = View.VISIBLE
        updateShowOnScreenAction()
        // Fresh result with the translation section hidden: park its collapsed
        // header above the scroll fold once the fit lays out (see hiddenTopPx).
        // From STABLE state, not hiddenTopPx() — that reads the header's
        // measured height, which is still 0 on a fresh overlay's first bind
        // (and showWithResult), and would silently disable the parking.
        pendingHiddenTopScroll = !isSideBySide && prefs.hideTranslationSection
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
        // Bind-while-needed covers the triggers no callback fires for: a Done
        // that landed with the section already visible (cross-surface pref flip
        // — the pref is global SharedPreferences and nothing listens for it),
        // and boxes toggled ON during the Translating window.
        maybeCompleteDeferred()
    }

    /** DEFERRED-TRANSLATION funnel. The bound result skipped MT because the
     *  translation section was hidden; run it the moment a consumer needs it:
     *  the section is (or just became) visible, or the on-frame boxes are in
     *  play. Generation-guarded like [commitEdit]'s re-translate; exactly one
     *  completion rebinds with [TranslationResult.pendingTranslation] cleared.
     *  No-ops for results without a pending, so it's safe on every bind. */
    private fun maybeCompleteDeferred() {
        val bound = lastResult ?: return
        val pending = bound.pendingTranslation ?: return
        val needed = !prefs.hideTranslationSection || boxesShown || presentationPrefs.boxesEnabled
        if (!needed) return
        // Same pending already completing (double trigger): one batch only.
        if (deferredInFlight == pending) return
        val gen = ++deferredGeneration
        deferredInFlight = pending
        scope.launch {
            try {
                val perGroup: List<PanelTranslation>? = try {
                    val custom = completeDeferred
                    if (custom != null) {
                        custom(pending)
                    } else {
                        CaptureService.instance?.completeDeferredTranslation(pending)?.map {
                            PanelTranslation(it.text, it.note, it.backendDisplayName)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    emptyList()   // attempted and failed → terminal "—" below
                }
                if (dismissed || gen != deferredGeneration) return@launch
                // The result moved on (edit commit, new session): this run is stale.
                val current = lastResult
                if (current?.pendingTranslation != pending) return@launch
                // Null = no translator available (service dead, no host hook): keep
                // the pending so the next trigger retries — distinct from a run that
                // failed, which must land terminal or the visible card would read
                // "Translating…" forever.
                if (perGroup == null) return@launch
                val joined = perGroup.joinToString("\n\n") { it.text }
                if (joined.isBlank()) {
                    bindResult(current.copy(translatedText = "—", pendingTranslation = null))
                    return@launch
                }
                // Fill the skeletons BEFORE the rebind so the show-on-screen pill
                // reads the filled data. Null-tolerant: the stash-reshow overlay
                // carries no overlayData at all.
                overlayData = fillOneShotOverlayData(overlayData, perGroup.map { it.text })
                val filled = overlayData
                bindResult(
                    current.copy(
                        translatedText = joined,
                        note = perGroup.mapNotNull { it.note }.firstOrNull(),
                        backendDisplayName = perGroup.mapNotNull { it.backendDisplayName }.firstOrNull(),
                        pendingTranslation = null,
                    )
                )
                // Promote any skeletons already up; nothing paintable → take them
                // down rather than leave them pulsing (Done's data == null recovery).
                if (boxesShown) {
                    if (filled != null) updateChips(filled) else hideChips()
                }
                updateShowOnScreenAction()
            } finally {
                // Only the run that still owns the generation releases the
                // marker — a superseding launch already stamped its own.
                if (gen == deferredGeneration) deferredInFlight = null
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
        val b = binder ?: return
        if (body.height <= 0 || contentRow.width <= 0) return
        measureCardInset(b)
        val neededHeight = neededPanelHeight(b)
        maxNeededHeightPx = neededHeight.coerceAtLeast(CaptureResultGeometry.minPanelHeight(screenH))
        // Slivered: measure-only — the height is parked and the sections are
        // faded, so growing the panel here would fight the collapse; the
        // expand paths re-run this. The measurements above must NOT wait for
        // that: in the overlays-first flow the panel binds and collapses
        // without ever fitting expanded, and the sliver DRAG consumes the
        // baselines directly — maxNeededHeightPx as its ceiling (MAX_VALUE
        // until measured = a panel draggable into empty space) and the card
        // chrome via reFitText (zeros = text fitted against phantom room).
        // Measuring here also happens while the cards are still wrap, which
        // the once-latched measureCardInset needs; the drag's applyCardFill
        // pins them and would poison a later first measure.
        if (sliverMode) return
        val target = CaptureResultGeometry.autoPanelHeight(neededHeight, screenH, autoMaxPx)
        animatePanelHeight(target)
    }

    /** Panel height that shows all content at max text size. Caller must have
     *  run [measureCardInset] and confirmed the content is laid out. */
    private fun neededPanelHeight(b: TranslationSectionBinder): Int {
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
        return naturalContent + dp(HANDLE_HEIGHT_DP) + topInsetPx + bottomInsetPx - hiddenTopPx()
    }

    /**
     * The text-size range changed. The sheet deliberately KEEPS its height and
     * re-fits the text inside it (the drag-resize path), rather than growing:
     * the picker is anchored to a header inside this sheet, and animating the
     * sheet on every step would slide that anchor out from under the finger.
     * The drag ceiling still tracks the new max, so a user who wants the extra
     * room can pull the sheet up themselves.
     */
    private fun refitForFontRange() {
        val b = binder ?: return
        if (body.height <= 0 || contentRow.width <= 0) return
        measureCardInset(b)
        maxNeededHeightPx = neededPanelHeight(b)
            .coerceAtLeast(CaptureResultGeometry.minPanelHeight(screenH))
        reFitText()
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
        // No content laid out yet — the pre-result status phase keeps the
        // scroll GONE and the cards unmeasured (a grabber drag is live the
        // whole time OCR runs). Fitting is meaningless there, and measuring
        // would LATCH all-zero card insets that the real bind's wrap-measure
        // could then never replace, undercounting every later fit and drag
        // ceiling. Same laid-out guard autoSizeAndFit uses.
        if (contentRow.width <= 0) return
        // Latch the wrap-measure before the fill below can pin the cards —
        // free once measured; load-bearing only if a drag outraces the
        // bind's posted fits to be the very first fit machinery to run.
        measureCardInset(b)
        val bodyH = fitBodyHeight(panelHeightPx)
        applyCardFill(b, bodyH)
        val (src, tgt) = fitSizes(b, bodyH)
        b.setSizes(src, tgt)
    }

    /** Grow/shrink the panel to [target], interpolating BOTH the height and the
     *  text size (as a float, between the fitted start/end sizes) so the text
     *  scales smoothly instead of stepping. */
    private fun animatePanelHeight(target: Int) {
        heightAnimator?.cancel()
        heightAnimatorTargetPx = target
        val b = binder ?: return
        val startH = panelHeightPx
        val (srcStart, tgtStart) = fitSizes(b, fitBodyHeight(startH))
        val (srcEnd, tgtEnd) = fitSizes(b, fitBodyHeight(target))
        applyCardFill(b, fitBodyHeight(startH))
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
                applyCardFill(b, fitBodyHeight(h))
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
            nav?.onWordSpansChanged()
        }
    }

    /** Resolve the tapped word and show a display+speak lens over the game,
     *  anchored on the tapped line (no Anki / open-detail — see [showAnkiChip]).
     *  [fromController] (the cursor's A press) pre-selects the lens pill so the
     *  next A opens the detail screen; a touch tap leaves the lens unselected
     *  until its first controller input. */
    private fun onSourceTapped(offset: Int, fromController: Boolean = false) {
        if (!wordLensEnabled) return
        val span = wordSpans.firstOrNull { offset in it.first } ?: return
        val b = binder ?: return
        scope.launch {
            try {
                val resolved = SourceWordLookup.resolve(ctx.applicationContext, span.second, span.third)
                if (dismissed) return@launch
                val wordRect = Rect()
                if (!wordRectOnScreen(span.first, wordRect)) return@launch
                val screenX = wordRect.centerX()
                val anchorY = wordRect.top
                val lineH = wordRect.height()
                dismissWordLens()
                // Null host = the lens's activity-window mode (see
                // [wordLensInActivity]); the camera panel's wm IS its
                // Activity's WindowManager, so the panel window attaches to
                // the activity token without any overlay permission.
                val lens = MagnifierLens(
                    ctx, wm, displayId,
                    overlayHost = if (wordLensInActivity) null else overlayHost,
                    showAnkiChip = resolved.entry != null,
                )
                lens.onDismiss = {
                    binder?.setWordHighlight(null)
                    wordSpeakChip?.release()
                    wordSpeakChip = null
                    wordLens = null
                }
                wordLens = lens
                wordSpeakChip = LensSpeakChip(
                    lens, scope,
                    ttsAlertTarget ?: TtsAlertTarget.Overlay(ctx, overlayHost, wm, displayId),
                ) { LensSpeakChip.Request(resolved.word, prefs.sourceLangId, reading = resolved.reading) }
                // Open-detail tap + Anki chip — same actions as the drag flow. The captured
                // [resolved] is this tap's word/entry; sentence + screenshot come from the
                // current capture result.
                SourceLensActions(
                    ctx.applicationContext, displayId, overlayHost, lens,
                    // Anki review pushes a full screen → tear the sheet down. Open-detail
                    // stashes this result so the controller can re-show the sheet when the
                    // user backs out of the detail screen (falls back to dismiss when no
                    // controller / no bound result). In-activity hosts keep the sheet
                    // through launches instead — the launched screen stacks ON TOP of the
                    // hosting activity and the sheet restores on back
                    // (dismissOnActivityLaunch, same contract as the sentence-level Anki
                    // flow).
                    onLaunchedActivity = { kind ->
                        when (kind) {
                            SourceLensActions.LaunchKind.Anki ->
                                if (dismissOnActivityLaunch) dismiss()
                            SourceLensActions.LaunchKind.Detail -> {
                                val r = lastResult
                                val nav = onNavigateToDetail
                                if (r != null && nav != null) nav(r)
                                else if (dismissOnActivityLaunch) dismiss()
                            }
                        }
                    },
                    tagDetailReturn = !wordLensInActivity,
                    showAnkiNotInstalled = if (wordLensInActivity) showAnkiNotInstalled else null,
                ) {
                    LensActionContext(
                        resolved.word,
                        resolved.reading,
                        resolved.entry,
                        lastResult?.originalText,
                        lastResult?.screenshotPath,
                        audioAnchorMs = lastResult?.createdAtMs?.takeIf { it > 0 },
                    )
                }
                b.setWordHighlight(span.first)
                lens.show(screenX, anchorY, screenW, screenH, anchorHeight = lineH)
                lens.setDefinitions(resolved.data, resolved.label)
                lens.makeInteractive()
                if (fromController) lens.focusPillForController()
            } catch (_: Exception) {}
        }
    }

    private fun dismissWordLens() {
        wordLens?.dismiss()
    }

    private val wordLocTmp = IntArray(2)

    /** Screen rect of [span]'s FIRST line box inside tvOriginal, or false while
     *  the text isn't laid out. The ONE word-geometry implementation, shared by
     *  the lens anchor ([onSourceTapped]) and the controller cursor's ring, so
     *  the two can never drift. A wrapped word rings/anchors on its first line. */
    private fun wordRectOnScreen(span: IntRange, out: Rect): Boolean {
        val tv = binder?.tvOriginal ?: return false
        if (!tv.isShown) return false
        val layout = tv.layout ?: return false
        val endOffset = span.last + 1
        if (span.first < 0 || endOffset > layout.text.length) return false
        val lineStart = layout.getLineForOffset(span.first)
        val xStart = layout.getPrimaryHorizontal(span.first)
        // The offset just past the word can land on the NEXT line (the word
        // ends a wrapped line) — getPrimaryHorizontal then returns that line's
        // start (~0), collapsing the box to mid-screen and throwing off the
        // lens/arrow for right-edge words. Fall back to the line's right edge.
        val xEnd = if (layout.getLineForOffset(endOffset) == lineStart) {
            layout.getPrimaryHorizontal(endOffset)
        } else {
            layout.getLineRight(lineStart)
        }
        // min/max, not start/end: an RTL run's primary horizontals arrive inverted.
        var left = minOf(xStart, xEnd).toInt() + tv.paddingLeft
        var right = maxOf(xStart, xEnd).toInt() + tv.paddingLeft
        if (right <= left) right = left + 1
        val top = layout.getLineTop(lineStart) - tv.scrollY + tv.paddingTop
        val bottom = layout.getLineBottom(lineStart) - tv.scrollY + tv.paddingTop
        if (bottom <= top) return false
        tv.getLocationOnScreen(wordLocTmp)
        out.set(
            wordLocTmp[0] + left, wordLocTmp[1] + top,
            wordLocTmp[0] + right, wordLocTmp[1] + bottom,
        )
        return true
    }

    // ── OCR tool switcher ────────────────────────────────────────────────

    /** Open the "Choose OCR tool" picker as an overlay window for the capture pinned
     *  by [prov] + [path] — a Ready result's source row, or a "no text detected"
     *  status. Switching to a downloaded engine re-OCRs that capture in place; a
     *  not-downloaded one deep-links to the OCR settings screen (and tears this sheet
     *  down). */
    private fun showOcrPicker(prov: OcrProvenance, path: String) {
        chooseOcr?.let {
            it(prov, path)
            return
        }
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
                    includesOwnOverlays = prov.frameIncludesOwnOverlays ?: false,
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
        chooseLanguage?.let { it(isSource); return }
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
            showAnkiNotInstalled?.invoke()
                ?: showAnkiNotInstalledDialog(ctx, overlayHost, wm, displayId)
            return
        }
        // One locked snapshot for every word extra below. NOT gated direct
        // field reads: the blank-meaning transport requires the meaning
        // slots and EXTRA_ENRICHMENT to come from the SAME maps, and the
        // singleton's fields can rotate between separate reads.
        val words = LastSentenceCache.snapshotFor(sentence)
        SentenceAnkiReviewActivity.finishCurrentIfAny()
        AnkiPermissionActivity.finishCurrentIfAny()
        val intent = Intent(app, AnkiPermissionActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
            putExtra(AnkiPermissionActivity.EXTRA_FORWARD_TARGET, AnkiPermissionActivity.TARGET_SENTENCE)
            putExtra(SentenceAnkiReviewActivity.EXTRA_SENTENCE, sentence)
            putExtra(SentenceAnkiReviewActivity.EXTRA_TRANSLATION, result.translatedText)
            // A deferred result's translation is blank — carry the pending so
            // the sheet's lazy fill runs the deferred COMPLETION (History rows
            // fill too). This panel dismisses on launch (its own funnel dies
            // with its scope), so the sheet is the completion's only carrier.
            result.pendingTranslation?.let {
                putExtra(SentenceAnkiReviewActivity.EXTRA_PENDING_TRANSLATION, it)
            }
            result.screenshotPath?.let { putExtra(SentenceAnkiReviewActivity.EXTRA_SCREENSHOT_PATH, it) }
            putExtra(SentenceAnkiReviewActivity.EXTRA_SOURCE_LANG, prefs.sourceLangId.code)
            // Game-audio ring anchor: when this capture happened, so the trim
            // view opens at the line's own moment instead of the buffer tail.
            if (result.createdAtMs > 0) {
                putExtra(SentenceAnkiReviewActivity.EXTRA_AUDIO_ANCHOR_MS, result.createdAtMs)
            }
            words?.let { snap ->
                val keys = snap.results.keys.toTypedArray()
                // Size-gated pair: normally senses ride EXTRA_ENRICHMENT and
                // sense-bearing meaning slots are blanked (definition text
                // crosses the binder once; meaningFromTransport re-derives on
                // the sheet's read side). An oversized senses payload ships
                // stripped enrichment + real flat meanings instead — see
                // transportPayloadFor. Safe ONLY because every extra reads
                // the same [snap]: a blank slot's senses are the senses that
                // cross.
                val transport = transportPayloadFor(keys, snap.results, snap.enrichment)
                putExtra(SentenceAnkiReviewActivity.EXTRA_WORDS, keys)
                putExtra(SentenceAnkiReviewActivity.EXTRA_READINGS,
                    snap.results.values.map { it.first }.toTypedArray())
                putExtra(SentenceAnkiReviewActivity.EXTRA_MEANINGS, transport.meanings)
                putExtra(SentenceAnkiReviewActivity.EXTRA_FREQ_SCORES,
                    snap.results.values.map { it.third }.toIntArray())
                putExtra(SentenceAnkiReviewActivity.EXTRA_SURFACES,
                    keys.map { snap.surfaces[it] ?: "" }.toTypedArray())
                putExtra(SentenceAnkiReviewActivity.EXTRA_ENRICHMENT, transport.enrichment)
            }
        }
        val targetDisplay = PlayTranslateApplication.foregroundDisplayId() ?: displayId
        val opts = android.app.ActivityOptions.makeBasic().setLaunchDisplayId(targetDisplay).toBundle()
        app.startActivity(intent, opts)
        // Window hosting: tear the sheet down — it'd otherwise sit over the
        // review/trampoline. In-app hosting keeps it; the host activity goes
        // behind the review and restores as left.
        if (dismissOnActivityLaunch) dismiss()
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
        // Locked snapshot — the hand-assembled payload from three separate
        // field reads could pair one sentence's words with another's
        // surfaces/enrichment across a mid-read rotation.
        val payload = LastSentenceCache.snapshotFor(sentence)
        val translation = result.translatedText.takeIf { it.isNotEmpty() }
        val langId = prefs.sourceLangId
        android.widget.Toast.makeText(app, R.string.anki_adding_in_progress, android.widget.Toast.LENGTH_SHORT).show()
        // Process-lived scope, NOT [scope] — the overlay cancels [scope] on
        // dismiss, which would silently kill an in-flight send (no card, no
        // result toast). The toasts target the app context, so they still
        // fire after the panel is gone.
        ankiOneTapSendScope.launch {
            val sendResult = app.oneTapSendSentence(
                original = sentence, translation = translation, wordsPayload = payload,
                screenshotPath = result.screenshotPath, sourceLangId = langId,
                // Deferred result: the lazy translate runs the deferred
                // COMPLETION (History rows fill too). This scope outlives the
                // panel, so the attach survives a dismissal mid-send.
                pendingTranslation = result.pendingTranslation,
            )
            when (sendResult) {
                // Reopening the review is the mapping recovery — but only
                // while the panel is still up. Once the user has dismissed
                // it, stealing the game's screen with an activity they
                // didn't ask for is worse than the dispatcher's explanatory
                // NeedsMapping toast (already shown); match the fragment
                // paths' degraded contract instead. Both this coroutine and
                // dismiss() run on Main, so the read doesn't race.
                is AnkiSendResult.NeedsMapping -> if (!dismissed) openSentenceAnkiReview()
                else -> oneTapResultToast(app, sendResult, CardMode.SENTENCE)
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
        // The editor covers the sections the popover sizes.
        fontPopover?.dismiss()
        nav?.clearCursor()             // the editor covers the cursor's targets
        editText.setText(current)
        editText.setSelection(editText.text.length)
        editContainer.visibility = View.VISIBLE
        editText.requestFocus()        // view-focus first, so the IME targets this field
        applyWindowFocusPolicy()
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
        root.requestFocus()            // pull view focus off editText
        applyWindowFocusPolicy()
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
            // The edit re-translates the NEW source itself (below) — a surviving
            // pending would let a later reveal clobber it with the OLD source's
            // translation.
            pendingTranslation = null,
        )
        lastResult = edited
        // The capture's per-group boxes translate the OLD source — the edit
        // orphans them, so drop the data AND any boxes already painted from it.
        overlayData = null
        hideChips()
        updateShowOnScreenAction()
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
            val gt: PanelTranslation? = try {
                val custom = retranslate
                if (custom != null) {
                    custom(newText)
                } else {
                    CaptureService.instance?.translateOnce(newText)?.let {
                        PanelTranslation(it.text, it.note, it.backendDisplayName)
                    }
                }
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

    /** Back out of the in-place edit without re-translating — the controller's
     *  B while the editor is open. No text restore is needed: [startInPlaceEdit]
     *  re-seeds [editText] from [lastResult] on every open. */
    private fun cancelEdit() {
        if (editContainer.visibility != View.VISIBLE) return
        ctx.getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(editText.windowToken, 0)
        editContainer.visibility = View.GONE
        root.requestFocus()            // pull view focus off editText
        applyWindowFocusPolicy()
    }

    /** Single owner of the sheet window's focus/IME flags. Focus is wanted
     *  while the in-place edit is open (for the IME) OR while controller nav
     *  is live (for keys + stick motion); the IME only ever for the edit —
     *  this is what keeps [commitEdit] from dropping controller focus. */
    private fun applyWindowFocusPolicy() {
        val editing = editContainer.visibility == View.VISIBLE
        sheetHost.setFocusPolicy(root, focusable = editing || nav != null, wantsIme = editing)
    }

    /** The controller's B / system back, in sheet-modal precedence order —
     *  the mirror of [CaptureResultRoot.dispatchTouchEvent]'s ladder. The
     *  lens branch is normally unreachable (an interactive lens window sits
     *  above us and holds focus, handling B itself); it covers the lens's
     *  no-controller non-focusable mode. */
    private fun onControllerBack() {
        when {
            fontPopover?.isShowing == true -> fontPopover?.dismiss()
            editContainer.visibility == View.VISIBLE -> cancelEdit()
            wordLens != null -> dismissWordLens()
            sliverMode -> dismissFromSliver()
            else -> animateOutAndDismiss()
        }
    }

    /** The sheet's side of the controller-navigation seam ([nav] drives it). */
    private val navHost = object : CaptureSheetNavHost {
        override val isEditing: Boolean get() = editContainer.visibility == View.VISIBLE
        override val isPopoverOpen: Boolean get() = fontPopover?.isShowing == true
        override val inSliver: Boolean get() = sliverMode

        override fun onControllerBack() = this@CaptureResultOverlay.onControllerBack()
        override fun expandFromSliver() = this@CaptureResultOverlay.expandFromSliver()

        override fun navActions(): List<NavAction> {
            val out = ArrayList<NavAction>()
            binder?.navigableActions()?.let(out::addAll)
            // The side-by-side collapsed strips' restore eyes — the only
            // controls a hidden column still shows. (Visibility is the nav's
            // own rect check; a shown column's strip is GONE.)
            sourceColumn?.let { out.add(NavAction(it.eye)) }
            targetColumn?.let { out.add(NavAction(it.eye)) }
            return out
        }

        private val handleLoc = IntArray(2)
        override fun handleRect(out: Rect): Boolean {
            // Expanded posture only: the pill fades out through the collapse
            // band (applyCollapseCrossfade), and a faded pill is not a target.
            if (sliverMode || !handle.isShown || handle.alpha < 1f) return false
            if (handle.width <= 0 || handle.height <= 0) return false
            handle.getLocationOnScreen(handleLoc)
            // Ring the DRAWN pill — 40×5dp centered in the strip plus its 1dp
            // ring (HandleView.onDraw) — not the full-width transparent strip.
            val pw = dp(42)
            val ph = dp(7)
            val cx = handleLoc[0] + handle.width / 2
            val cy = handleLoc[1] + handle.height / 2
            out.set(cx - pw / 2, cy - ph / 2, cx + pw / 2, cy + ph / 2)
            return true
        }

        override fun collapseToSliver() = this@CaptureResultOverlay.collapseToSliver()

        override fun resizeBy(dyPx: Int) = stickResizeBy(dyPx)

        override fun commitResize() = commitStickResize()

        override fun wordCount(): Int =
            if (binder?.tvOriginal?.isShown == true) wordSpans.size else 0

        override fun wordRect(index: Int, out: Rect): Boolean {
            val span = wordSpans.getOrNull(index) ?: return false
            return wordRectOnScreen(span.first, out)
        }

        override fun wordRunIsRtl(): Boolean {
            val layout = binder?.tvOriginal?.layout ?: return false
            return layout.getParagraphDirection(0) == Layout.DIR_RIGHT_TO_LEFT
        }

        override fun activateWord(index: Int) {
            val span = wordSpans.getOrNull(index) ?: return
            onSourceTapped(span.first.first, fromController = true)
        }

        private val scrollLoc = IntArray(2)
        override fun scrollViewportOnScreen(out: Rect): Boolean {
            if (!scroll.isShown || scroll.width <= 0 || scroll.height <= 0) return false
            scroll.getLocationOnScreen(scrollLoc)
            out.set(
                scrollLoc[0], scrollLoc[1],
                scrollLoc[0] + scroll.width, scrollLoc[1] + scroll.height,
            )
            return true
        }

        override fun scrollBy(dy: Int) {
            // NestedScrollView's scrollTo clamps to the content range.
            scroll.scrollBy(0, dy)
        }

        override fun ensureVisible(itemOnScreen: Rect) {
            val vp = Rect()
            if (!scrollViewportOnScreen(vp)) return
            val pad = dp(12)
            val dy = when {
                itemOnScreen.top < vp.top + pad -> itemOnScreen.top - (vp.top + pad)
                itemOnScreen.bottom > vp.bottom - pad -> itemOnScreen.bottom - (vp.bottom - pad)
                else -> 0
            }
            if (dy != 0) scroll.smoothScrollBy(0, dy)
        }

        private val ringItem = Rect()
        private val ringClip = Rect()
        private val rootLoc = IntArray(2)
        override fun setRing(itemOnScreen: Rect?, clipOnScreen: Rect?) {
            if (itemOnScreen == null) {
                focusRing.setTarget(null, null)
                return
            }
            // Screen → ring coords. The ring fills root, which normally sits at
            // (0,0), but subtract the live location rather than assume it.
            root.getLocationOnScreen(rootLoc)
            ringItem.set(itemOnScreen)
            ringItem.offset(-rootLoc[0], -rootLoc[1])
            if (clipOnScreen == null) {
                focusRing.setTarget(ringItem, null)
                return
            }
            ringClip.set(clipOnScreen)
            ringClip.offset(-rootLoc[0], -rootLoc[1])
            focusRing.setTarget(ringItem, ringClip)
        }
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
            // Translation ABOVE source: the order the in-app results page uses
            // (fragment_translation_result includes target before source), and
            // the reading order that fits a sheet rising from the bottom.
            inflater().inflate(R.layout.section_target, contentRow, true)
            // One extra dp over the shared pad for the stacked top section.
            setHeaderTop(contentRow.getChildAt(0), HEADER_TOP_DP + 1)
            val sourceHeaderIndex = contentRow.childCount
            inflater().inflate(R.layout.section_source, contentRow, true)
            // No divider between the stacked sections, and the source header
            // rides 1dp tighter than the shared pad — the section boundary is
            // carried by the cards themselves.
            setHeaderTop(contentRow.getChildAt(sourceHeaderIndex), HEADER_TOP_DP - 1)
        }
    }

    /** A side-by-side column: the inflated section ([expanded]) plus a hidden
     *  collapsed strip, toggled by [applySideBySideCollapse]. */
    private fun buildColumn(layoutRes: Int, isSource: Boolean): SectionColumn {
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val expanded = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        inflater().inflate(layoutRes, expanded, true)
        // One extra dp over the shared pad for the side-by-side headers.
        setHeaderTop(expanded.getChildAt(0), HEADER_TOP_DP + 1)
        // The card stays wrap here; its height is pinned explicitly each frame by
        // [applyCardFill] (deterministic — a weight/fillViewport chain doesn't
        // reliably shrink the card during a drag).
        val label = VerticalLabel(ctx)
        val (collapsed, eye) = buildCollapsedStrip(isSource, label)
        col.addView(expanded, LinearLayout.LayoutParams(MATCH, WRAP))
        col.addView(collapsed, LinearLayout.LayoutParams(WRAP, WRAP))
        return SectionColumn(col, expanded, collapsed, label, eye)
    }

    /** The strip shown when a side-by-side section is hidden: an eye button to
     *  restore it, with the section's language name rotated vertically beneath.
     *  Returns the strip and its eye (the controller cursor's target there). */
    private fun buildCollapsedStrip(isSource: Boolean, label: VerticalLabel): Pair<View, View> {
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
        val strip = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
            addView(eye, LinearLayout.LayoutParams(dp(36), dp(32)).apply { topMargin = dp(8) })
            addView(label, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(8) })
        }
        return strip to eye
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
        /** The collapsed strip's restore button — a controller nav target. */
        val eye: View,
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

    private fun inflater() = android.view.LayoutInflater.from(ctx)

    // ── Resize (drag the handle / the band just below it to grow-shrink) ──
    // Driven by CaptureResultRoot so the touch zone can extend below the panel's
    // visible bottom edge (the handle itself can't receive touches past the
    // panel's bounds).

    private var resizing = false
    private var resizeStartRawY = 0f
    private var resizeStartHeight = 0

    private fun beginResize(rawY: Float) {
        heightAnimator?.cancel() // the user takes over from the auto-grow
        resizing = true
        resizeStartRawY = rawY
        resizeStartHeight = panelHeightPx
    }

    private fun updateResize(e: MotionEvent) {
        // Bottom sheet: dragging the grabber UP grows the panel.
        val dy = (resizeStartRawY - e.rawY).toInt()
        // Clamp to [floor, 90%], then cap at the content's max-needed height so
        // the drag can't grow the panel into empty space beyond the content.
        // The floor is the SLIVER: the drag continues below the classic minimum
        // into the collapse band, crossfading the content for the sliver hint
        // on the way down (endResize commits or parks). The in-place edit keeps
        // the classic floor — collapsing mid-edit would strand the IME over a
        // sliver.
        val floor = if (editContainer.visibility == View.VISIBLE) {
            CaptureResultGeometry.minPanelHeight(screenH)
        } else {
            sliverHeightPx()
        }
        setPanelHeight(
            CaptureResultGeometry.clampPanelHeight(
                resizeStartHeight + dy, screenH, minFraction = 0f,
            )
                .coerceAtMost(maxNeededHeightPx)
                .coerceAtLeast(floor),
        )
        // No re-fit inside the collapse band — fitting to sliver heights
        // computes degenerate sizes, and the content is faded there anyway.
        if (panelHeightPx >= CaptureResultGeometry.minPanelHeight(screenH)) reFitText()
    }

    /** Release of a grabber drag. It never dismisses, at any speed: dragging
     *  the sheet down is how you MINIMIZE it, and the sliver is the bottom of
     *  its travel — the drag stops there. (A fling-out used to live here and
     *  read an ordinary quick minimize as a throw-away.) Dismissal is the tap
     *  outside, or the deliberate long swipe on the body. */
    private fun endResize() {
        resizing = false
        if (panelHeightPx < CaptureResultGeometry.minPanelHeight(screenH)) {
            // Released inside the collapse band: the classic floor is the
            // commit threshold (same as the sliver drag's), so the sheet
            // parks in the sliver; a later tap-expand returns to the
            // pre-drag height.
            sliverMode = true
            dismissWordLens()
            nav?.clearCursor()   // every sliver entry drops the cursor
            preSliverHeightPx = resizeStartHeight
            animateSliverHeight(sliverHeightPx())
        } else if (panelHeightPx != resizeStartHeight) {
            // Only a drag that MOVED the sheet states a posture. A grab that
            // went nowhere — a tap on the grabber, or a pull against a sheet
            // already at its content ceiling — leaves the remembered one alone
            // instead of quietly overwriting it with wherever this result sat.
            autoMaxPx = committedCeiling()
        }
    }

    // ── Stick resize (the right stick's virtual grabber drag) ────────────
    // Delta-driven mirror of the touch resize: [stickResizeBy] is updateResize
    // per frame, [commitStickResize] is the finger-up (endResize / the
    // sliver drag's endSliverDrag, by origin). Driven by the nav controller's
    // frame loop while the right stick is deflected.

    private var stickResizing = false
    private var stickResizeFromSliver = false
    private var stickResizeStartHeight = 0

    private fun stickResizeBy(dyPx: Int) {
        if (dismissed || animatingOut) return
        if (!stickResizing) {
            heightAnimator?.cancel() // the user takes over from the auto-grow
            stickResizing = true
            stickResizeFromSliver = sliverMode
            stickResizeStartHeight = panelHeightPx
        }
        // Same clamps as updateResize: [floor, 90%], capped at the content's
        // max-needed height; the in-place edit keeps the classic floor (the
        // nav loop is suspended while editing anyway — belt and braces).
        val floor = if (editContainer.visibility == View.VISIBLE) {
            CaptureResultGeometry.minPanelHeight(screenH)
        } else {
            sliverHeightPx()
        }
        setPanelHeight(
            CaptureResultGeometry.clampPanelHeight(
                panelHeightPx + dyPx, screenH, minFraction = 0f,
            )
                .coerceAtMost(maxNeededHeightPx)
                .coerceAtLeast(floor),
        )
        if (panelHeightPx >= CaptureResultGeometry.minPanelHeight(screenH)) reFitText()
    }

    private fun commitStickResize() {
        if (!stickResizing) return
        stickResizing = false
        if (dismissed || animatingOut) return
        val min = CaptureResultGeometry.minPanelHeight(screenH)
        if (stickResizeFromSliver) {
            // Mirror endSliverDrag: past the classic floor the drag pulled the
            // sheet out of its park; under it, settle back into the sliver.
            if (panelHeightPx >= min) {
                sliverMode = false
                autoMaxPx = committedCeiling()
                reFitText()
                updateShowOnScreenAction()
            } else {
                animateSliverHeight(sliverHeightPx())
            }
        } else if (panelHeightPx < min && editContainer.visibility != View.VISIBLE) {
            // Mirror endResize's park (never while editing — the IME would
            // strand over a sliver, the same guard the touch floor encodes).
            // Unlike the touch path, this commit can fire with the popover up
            // (the frame loop's modal suspend lands here) — close it like
            // collapseToSliver does rather than leave it floating over a park.
            sliverMode = true
            dismissWordLens()
            fontPopover?.dismiss()
            nav?.clearCursor()   // every sliver entry drops the cursor
            preSliverHeightPx = stickResizeStartHeight
            animateSliverHeight(sliverHeightPx())
        } else if (panelHeightPx != stickResizeStartHeight) {
            autoMaxPx = committedCeiling()
        }
    }

    // ── Swipe-away (the body drag that slides the sheet off) ─────────────
    // The ONE gesture that throws the sheet away — the grabber and sliver drags
    // resize, and bottom out at the sliver. Travel is the gate, not speed: the
    // sheet follows the finger the whole way, so what dismisses is what the user
    // can SEE they have done.

    /** Whether a downward gesture may throw the sheet away at all. False for
     *  the camera/import hosts (dismissal there is the explicit X) and while
     *  the in-place edit is open, where it would strand the IME. */
    private fun canSwipeAway(): Boolean =
        dismissOnGesture && editContainer.visibility != View.VISIBLE

    /** True when a released gesture threw the sheet away: it must be a real UP
     *  (a CANCEL is not a release), on a host that dismisses, and past
     *  [dismissDp] of travel — or past [flingDp] with a fling behind it. */
    private fun swipeAwayReleased(e: MotionEvent, vy: Float, dismissDp: Float, flingDp: Float): Boolean =
        e.actionMasked == MotionEvent.ACTION_UP && canSwipeAway() &&
            CaptureResultGeometry.shouldDismissFromDrag(
                panel.translationY, vy,
                dismissDp * density, flingDp * density, FLING_DISMISS_VEL,
            )

    /** The auto-size ceiling a committed drag leaves behind: the height the
     *  user released at — EXCEPT when they ran into the content clamp, which
     *  is not a height they chose but the tallest THIS result could go.
     *  Recording that would pin every later, longer result to this one's
     *  content height, so "as tall as it goes" is recorded as the full drag
     *  ceiling instead. */
    private fun committedCeiling(): Int =
        if (panelHeightPx >= maxNeededHeightPx) CaptureResultGeometry.maxPanelHeight(screenH)
        else panelHeightPx

    private fun setPanelHeight(px: Int) {
        panelHeightPx = px
        (panel.layoutParams as FrameLayout.LayoutParams).height = px
        panel.requestLayout()
        applyCollapseCrossfade(px)
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
        // The frost dst depends on the panel's LIVE position, but draw() only
        // re-executes on invalidation — translationY animates on the render
        // thread without re-recording, so a moved panel keeps the frost slice
        // recorded at its OLD position. The entrance recorded it parked
        // off-screen: every status panel rendered frost-less until the result
        // bind's relayout (the 2026-07-15 flat-transparency bug). Re-record
        // whenever the offset actually changes; a no-op when nothing moved.
        val frostOff = (panel.top + panel.translationY + body.top).toInt()
        if (frostOff != lastFrostOff) {
            lastFrostOff = frostOff
            body.invalidate()
        }
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

        /** True while an outside gesture is being streamed to
         *  [outsideLookupRouter] (claimed at its DOWN). */
        private var routingOutside = false

        /** Offer an outside-zone DOWN to the router. True = claimed; the
         *  rest of the gesture streams to it via [routeOutsideFollowUp]. */
        private fun tryRouteOutsideDown(ev: MotionEvent): Boolean {
            val router = outsideLookupRouter ?: return false
            if (editContainer.visibility == View.VISIBLE) return false
            if (router(ev)) {
                routingOutside = true
                return true
            }
            return false
        }

        /** Stream the rest of a claimed outside gesture; ends it on UP or
         *  CANCEL. Returns true when the event belonged to the router. */
        private fun routeOutsideFollowUp(ev: MotionEvent): Boolean {
            if (!routingOutside) return false
            outsideLookupRouter?.invoke(ev)
            if (ev.actionMasked == MotionEvent.ACTION_UP ||
                ev.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                routingOutside = false
            }
            return true
        }

        /** Controller keys, live only while the window took focus at show()
         *  ([nav] non-null). Before super so nav sees keys first — its own
         *  edit/popover awareness decides what falls through to children. */
        override fun dispatchKeyEvent(ev: KeyEvent): Boolean =
            nav?.handleKey(ev) == true || super.dispatchKeyEvent(ev)

        /** Left-stick scroll. Non-pointer generic motion reaches the focused
         *  window's focused view — this root, which holds view focus while nav
         *  is live (the in-place edit moves it to the EditText, and nav bails
         *  there anyway). */
        override fun onGenericMotionEvent(ev: MotionEvent): Boolean =
            nav?.handleGenericMotion(ev) == true || super.onGenericMotionEvent(ev)

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            // The text-size popover is a child of this root, and it can sit
            // ABOVE the sheet's top edge — right where the rules below claim
            // every DOWN for the resize grab or an outside-tap dismiss. Stand
            // them all down while it's open and let normal child dispatch run:
            // the popover's own scrim (drawn over everything but its card)
            // handles outside taps by dismissing itself.
            if (fontPopover?.isShowing == true) return super.dispatchTouchEvent(ev)
            if (routeOutsideFollowUp(ev)) return true
            if (sliverMode) {
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        val panelTop = panel.top + panel.translationY
                        // A touch on the sliver (plus the same above-edge band the
                        // resize grab uses) starts a tap-or-drag; anywhere else
                        // goes to the word-lookup router when it claims it,
                        // else dismisses boxes and sliver together. The tap is
                        // the advertised gesture here (see the hint) — this
                        // whole band is its target, which is why it can afford
                        // to reach so far past the sheet.
                        if (ev.y >= panelTop - dp(EXTRA_GRAB_PAST_EDGE_DP)) {
                            sliverTouch = true
                            sliverDragging = false
                            sliverDownRawY = ev.rawY
                        } else if (!tryRouteOutsideDown(ev) && dismissOnGesture) {
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
                    MotionEvent.ACTION_OUTSIDE -> if (dismissOnGesture) dismissFromSliver()
                }
                // The sliver has no inner interactions — consume everything so
                // no child ever sees a gesture that started under sliver rules.
                return true
            }
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val panelTop = panel.top + panel.translationY
                    // Resize zone = the grabber strip, EXTRA_GRAB_PAST_EDGE_DP
                    // above it (past the sheet's edge, over the game) and
                    // EXTRA_GRAB_INTO_SHEET_DP below it (into the sheet's own
                    // top padding, so the pill is grabbable from either side).
                    val resizeTop = panelTop - dp(EXTRA_GRAB_PAST_EDGE_DP)
                    val resizeBottom =
                        panelTop + dp(HANDLE_HEIGHT_DP + EXTRA_GRAB_INTO_SHEET_DP)
                    if (ev.y >= resizeTop && ev.y <= resizeBottom) {
                        beginResize(ev.rawY)
                        return true
                    }
                    // Above the sheet, or in the nav-bar gap below it when the
                    // sheet is lifted — both are "outside": the word-lookup
                    // router gets first claim, then dismiss. Consumed either
                    // way so an outside tap can't leak to the host.
                    if (ev.y < resizeTop || ev.y > panel.bottom + panel.translationY) {
                        if (!tryRouteOutsideDown(ev) && dismissOnGesture) animateOutAndDismiss()
                        return true
                    }
                }
                MotionEvent.ACTION_MOVE -> if (resizing) { updateResize(ev); return true }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    if (resizing) { endResize(); return true }
                MotionEvent.ACTION_OUTSIDE -> {
                    if (dismissOnGesture) animateOutAndDismiss()
                    return true
                }
            }
            return super.dispatchTouchEvent(ev)
        }
    }

    /** The visible bottom sheet. When the content fits (no inner scroll), a
     *  long vertical down-drag on the body dismisses (swipe-to-dismiss). When
     *  the content is scrollable it scrolls instead, and dismissal is a tap
     *  outside — the sheet rule that keeps scroll vs dismiss unambiguous. Drags
     *  starting on the grabber strip are left to the resize listener, which
     *  only ever resizes: the sheet bottoms out at the sliver, never off. */
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
                    // Down IS away for a bottom sheet, so both inputs pass
                    // straight through. The distance is the long one: this drag
                    // starts from rest anywhere on the body, and on content that
                    // doesn't scroll it is also what a scroll attempt looks like.
                    if (swipeAwayReleased(ev, vy, DISMISS_DISTANCE_DP, DISMISS_FLING_DISTANCE_DP)) {
                        animateOutAndDismiss()
                    } else {
                        animate().translationY(0f).setDuration(SPRING_BACK_MS).start()
                    }
                }
            }
            return dragging || super.onTouchEvent(ev)
        }
    }

    /** A centered grab-pill in the transparent strip above the sheet. Fades out
     *  with the content as the sheet parks (see [applyCollapseCrossfade]): down
     *  there it sits in the system's home-gesture band, where inviting a drag
     *  loses more often than it wins. */
    private inner class HandleView(c: Context) : View(c) {
        // Sheet-colored fill under a 1dp ptTextMuted ring: the pill floats over
        // raw game content, and the fill/ring pair opposes in both themes so
        // one of the two contrasts against any frame (ptDivider was tried for
        // the ring and sat too close to ptBg to read).
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ctx.themeColor(R.attr.ptBg)
        }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ctx.themeColor(R.attr.ptTextMuted)
        }
        private val rect = RectF()
        init { setWillNotDraw(false) }
        override fun onDraw(canvas: Canvas) {
            val w = dp(40).toFloat()
            val h = dp(5).toFloat()
            val border = density * 1f
            val left = (width - w) / 2f
            val top = (height - h) / 2f
            rect.set(left - border, top - border, left + w + border, top + h + border)
            canvas.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, borderPaint)
            rect.set(left, top, left + w, top + h)
            canvas.drawRoundRect(rect, h / 2f, h / 2f, paint)
        }
    }

    private fun dp(v: Int): Int = (v * density).toInt()

    private companion object {
        const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
        const val HANDLE_HEIGHT_DP = 20
        /** Sheet-fill strip left visible (below the grabber strip) when the
         *  panel collapses to its sliver state. */
        /** Visible sheet strip while slivered — sized to seat the 11sp
         *  "drag up for more options" hint with breathing room. This is
         *  OPAQUE: every dp covers a dp of game, and bottom-anchored dialogue
         *  is exactly what it covers. Not a knob for pill clearance. */
        const val SLIVER_SHEET_DP = 24
        /** Duration of the collapse-to-sliver / expand-from-sliver slide. */
        const val SLIVER_DURATION_MS = 220L
        /** Duration of the section fade that rides the sliver transitions. */
        const val SLIVER_FADE_MS = 150L
        /** How far past the sheet's edge the resize grab zone extends. */
        const val EXTRA_GRAB_PAST_EDGE_DP = 26
        /** ...and how far INTO the sheet it reaches below the grabber strip.
         *  Lands in the body's top padding + the section header's own top
         *  padding (~8dp of dead space), so it costs the header buttons a
         *  couple of dp and buys a much easier grab. */
        const val EXTRA_GRAB_INTO_SHEET_DP = 10
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
        /** [opaqueBackgroundBoost] variants — barely transparent for hosts
         *  with no frosted backdrop behind the sheet (the un-blurred app
         *  behind is distracting at the normal translucency). */
        const val SHEET_ALPHA_NO_IMAGE = 247
        const val CARD_FILL_ALPHA_NO_IMAGE = 0.96f
        /** Backdrop downscale before the box blur (cheapness; the blur does the
         *  smoothing now, so this no longer needs to be aggressive). */
        const val BACKDROP_DOWNSCALE = 6
        /** Box-blur radius (in downscaled px) — the main blur dial. */
        const val BACKDROP_BLUR_RADIUS = 5
        const val SECTION_H_PAD_DP = 12
        /** Space below the filled side-by-side cards (to the panel's bottom). */
        const val SIDE_BY_SIDE_BOTTOM_BUFFER_DP = 5
        /** Space below the stacked content (to the panel's bottom). */
        const val STACKED_BOTTOM_BUFFER_DP = 3
        /** Top padding applied to EVERY panel section header (the shared layout uses
         *  pt_group_gap = 20dp). One value so all four headers — source/target,
         *  side-by-side/stacked — render the same height. */
        const val HEADER_TOP_DP = 7
        /** Corner radius as a multiple of pt_radius — between the original 1x and
         *  the 2x briefly tried. */
        const val CORNER_RADIUS_MULT = 1.5f
        /** Body swipe-down: how far the sheet must travel to be thrown away.
         *  Long on purpose — the gesture starts from rest anywhere on the body,
         *  and on content too short to scroll it is indistinguishable from a
         *  scroll attempt until the distance separates them. */
        const val DISMISS_DISTANCE_DP = 100f
        /** ...and the travel a fling must ALSO carry before its speed counts. */
        const val DISMISS_FLING_DISTANCE_DP = 64f
        /** px/s; a deliberate throw. Distance is the primary gate — this only
         *  shortens a swipe already visibly on its way out, so it sits well
         *  above the speed an ordinary drag-to-minimize releases at. */
        const val FLING_DISMISS_VEL = 1600f
        /** Slide back from a swipe-away that didn't reach its threshold. */
        const val SPRING_BACK_MS = 150L
        const val ENTER_DURATION_MS = 280L
        const val EXIT_DURATION_MS = 200L
        const val HEIGHT_DURATION_MS = 240L
    }
}
