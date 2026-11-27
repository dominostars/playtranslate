package com.playtranslate.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
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
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.text.InputType
import android.text.Layout
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.annotation.RequiresApi
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
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import com.google.android.material.card.MaterialCardView
import com.playtranslate.AnkiManager
import com.playtranslate.CaptureService
import com.playtranslate.capture.CaptureBackendResolver
import com.playtranslate.CaptureSession
import com.playtranslate.CaptureState
import com.playtranslate.OneShotOverlayData
import com.playtranslate.PlayTranslateApplication
import com.playtranslate.deferredCompletionNeeded
import com.playtranslate.fillOneShotOverlayData
import com.playtranslate.refillOneShotOverlayData
import com.playtranslate.fillsBoxesOf
import com.playtranslate.OverlayMode
import com.playtranslate.OverlaySurface
import com.playtranslate.Prefs
import com.playtranslate.language.SourceLanguageProfiles
import com.playtranslate.R
import com.playtranslate.RegionEntry
import com.playtranslate.language.OcrBackend
import com.playtranslate.language.SourceLangId
import com.playtranslate.ocr.registry.OcrModelManager
import com.playtranslate.ocr.registry.selectionToken
import com.playtranslate.model.TextSegments
import com.playtranslate.model.OcrProvenance
import com.playtranslate.model.PendingTranslation
import com.playtranslate.model.TranslationResult
import com.playtranslate.overlay.OverlayEnvironment
import com.playtranslate.overlay.OverlayHost
import com.playtranslate.overlay.Sides
import com.playtranslate.overlayThemedContext
import com.playtranslate.themeColor
import com.playtranslate.translation.TranslationBackend
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.ceil
import com.playtranslate.ask.AskSession
import com.playtranslate.ask.CarriedAnswer
import com.playtranslate.ask.Explanation

/**
 * The over-game capture result panel: a bottom-anchored sheet that grows to
 * fit its result (up to half the screen, further by a drag) and shows the
 * results sections in the shared [ResultSectionsLayout]: the source section
 * beside the target section on a wide screen, stacked on a narrow one. It is
 * drawn over the game without leaving it.
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
 * The sections and their binders are the in-app results page's own
 * ([TranslationSectionBinder], [WordRowsBinder]), so they render and behave
 * exactly as they do there, the Words list included. The page's Clear row
 * is not here.
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

    /** The sides the one-or-two-columns decision reserves: what this
     *  sheet's window insets can take, the bars counted shown or not
     *  ([OverlayEnvironment.fittedReserved]), on the display the sheet's own
     *  context belongs to (an activity-hosted sheet may be on another screen
     *  than the display id it was given). Tests substitute a bar. */
    internal var sideReserveReader: (Context) -> Sides = { context ->
        OverlayEnvironment.read(
            context,
            belowBars = overlayHost.windowType == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        )?.fittedReserved ?: Sides.NONE
    }
    private val density = ctx.resources.displayMetrics.density
    private val cornerRadiusPx = ctx.resources.getDimension(R.dimen.pt_radius) * CORNER_RADIUS_MULT
    private val shadowHeightPx = (cornerRadiusPx + density * SHADOW_BLUR_DP * 2.5f).toInt()
    private val prefs = Prefs(ctx)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val touchSlop = ViewConfiguration.get(ctx).scaledTouchSlop

    /** Why the sheet closed, for the host's bookkeeping of what the capture
     *  took from the game screen: the USER closed it (a tap outside, the
     *  swipe, the B ladder's last rung, a hotkey's second press, the
     *  one-tap send's exit), a PAGE it opened took the screen (detail, Anki,
     *  settings, a picker), its session was CANCELLED from outside the
     *  host's own paths (a region change or a capture of the app's), or the
     *  SYSTEM ended it (the host's own teardown). */
    enum class DismissReason { USER, PAGE, CANCELLED, SYSTEM }

    /** Invoked once, on any dismissal path, with why. */
    var onDismiss: ((DismissReason) -> Unit)? = null

    private var dismissReason = DismissReason.SYSTEM

    /** The region this capture was limited to, set by the over-game
     *  controller before [show]. A custom region makes the edge indicator
     *  outline that region (its glow fading outward from it) instead of the
     *  display edges; the full-screen region keeps the display's glow
     *  without its stroke; null (the camera and image-import hosts) keeps
     *  the display's stroke and glow. Fractions of the display, like the
     *  capture crop. */
    var captureRegion: RegionEntry? = null

    /** Invoked when a page opened from this sheet takes the screen (a word's
     *  detail, the sentence's Anki editor), carrying the currently-bound
     *  result and the Ask section's finished answer about it, if any (shelved
     *  or on show, as it was left), so the controller can stash both and
     *  re-show this sheet with them when the user backs out of the page.
     *  Null → the sheet just dismisses. */
    var onNavigateToDetail: ((TranslationResult, CarriedAnswer?) -> Unit)? = null

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
     *  [overlayMode] is what the boxes paint over the frame (the surface's own
     *  [OverlayMode], None for nothing), read coerced to what the source
     *  language offers and written the moment a mode is picked
     *  ([selectOverlayMode]); [offeredOverlayModes] is what the mode control
     *  offers (the pill, or the host's own: [hostOwnsModeControl]), empty for
     *  a host with no frame to paint over. [startPosture] is where the user left the
     *  panel: parked in the sliver, or the height they dragged it to (see
     *  [CaptureResultGeometry]'s posture encoding) — read when a flow starts
     *  and written on dismissal ("it opens how you left it"). Default: the
     *  over-game capture prefs; the camera and import substitute their own. */
    interface PresentationPrefs {
        var overlayMode: OverlayMode
        val offeredOverlayModes: List<OverlayMode>
        var startPosture: Float
    }

    /** A mode this one capture opens in, over the remembered one, until
     *  the user picks: the tap-to-lookup gesture and hotkey capture in
     *  Definitions without pinning every later capture to it. Set by the
     *  host before [show]; a sheet shown with one starts parked
     *  ([postureFromOverride]). */
    var overlayModeOverride: OverlayMode? = null

    /** This sheet was shown with an [overlayModeOverride], so it starts
     *  parked whatever the remembered posture: tap-to-lookup is a capture in
     *  Definitions with the sheet collapsed, the words the point and the
     *  translation waiting for the panel (a parked capture defers it). Its
     *  posture is the override's, not the user's, so [recordPosture] leaves
     *  the remembered one as it was, and a plain capture after it opens as
     *  before. Fixed at [show] for the sheet's life. */
    private var postureFromOverride = false

    var presentationPrefs: PresentationPrefs = object : PresentationPrefs {
        override var overlayMode: OverlayMode
            get() = overlayModeOverride ?: prefs.overlayModeFor(OverlaySurface.CAPTURE)
            set(value) {
                // A pick is the user's: it is remembered, and the override ends.
                overlayModeOverride = null
                prefs.captureOverlayMode = value
            }
        override val offeredOverlayModes: List<OverlayMode>
            get() = OverlayMode.offered(
                OverlaySurface.CAPTURE, SourceLanguageProfiles[prefs.sourceLangId].hintTextKind,
            )
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
     *  capture's History rows. Hosts with their own GroupTranslator (Process-
     *  Text) substitute it. Returning null means "no
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

    /** Whether the translation's credit has its gear here, which opens the
     *  translation service picker ([TranslationPicker]). False for a host
     *  whose picture's boxes are painted from translations of its own (the
     *  camera and import reviews): a pick would change the sheet's
     *  translation and leave those boxes saying the old one. */
    var translationPickerOffered = true
        set(value) {
            field = value
            binder?.translationPickerOffered = value
        }

    /** The run of the result on show through a service the user picked
     *  ([retranslateWith]) and its job; null with none. Beside the result,
     *  never in it: [lastResult] stays whole while the run is in flight, so
     *  a stash taken meanwhile (a page opened from the sheet) holds the
     *  translation that was on show, not a half-made result. Whatever
     *  replaces what is on show ends it ([endPick]). */
    private var pickRun: PickRun? = null
    private var pickJob: Job? = null

    /** How a pick is translated; [ChosenServiceTranslation.run] but in a
     *  test. */
    internal var chosenTranslation:
        suspend (Context, TranslationBackend, TranslationResult) -> ChosenServiceTranslation.Outcome =
        ChosenServiceTranslation::run

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

    /** Overlay boxes for the currently-bound result: the latest
     *  [CaptureState.Translating]'s (skeletons, filling in as the translations
     *  arrive), then the translated boxes from [CaptureState.Done], filled
     *  again when the result is translated again (the deferred completion,
     *  a service the user picked for it: [retranslateWith]). Null otherwise
     *  (stash re-show, no-text, post-edit); null with no status in flight
     *  either leaves the mode pill down ([updateModeControl]). */
    private var overlayData: OneShotOverlayData? = null

    /** The in-place boxes, rendered INSIDE this window (a root child just
     *  above the edge ring, or just above the panel while it is parked — see
     *  [chipsOverSheet]). Same window ⇒ the window stays touchable, so
     *  MediaProjection's QTI clamp never dims the boxes, their place against
     *  the sheet is a child-order fact rather than a window race, and
     *  teardown is the window's own. Created on first show, then faded/re-fed
     *  via [TranslationOverlayView.setBoxes] (skeleton → translated). */
    /** Paints the result over the frame in the chosen mode (the over-game
     *  host; the camera and import paint through [boxPresenter]). A root
     *  child placed by [syncChipsOrder]. */
    private val layer = CaptureOverlayLayer(
        ctx, scope, prefs,
        lookupHost = CaptureOverlayLayer.LookupHost(rawCtx, wm, overlayHost, displayId),
    )

    /** The overlay-mode control, a root child above everything. */
    private val modePill = OverlayModePill(ctx)

    /** The host has a mode control of its own (the camera and import tools'
     *  Overlays row, which picks through [selectOverlayMode]), so this sheet
     *  never brings its pill up: the tools' own buttons hold the corner the
     *  pill is pinned to. Set by the host before [show]. */
    var hostOwnsModeControl = false

    /** Whether a capture this sheet follows is one Auto Copy to Clipboard
     *  copies ([copyOriginalWhenRead]): a capture of the screen, which
     *  the sheet over the game shows, and the Camera tool's snapshot. False
     *  for the hosts whose text is not a capture's: an imported file's
     *  pages and select-to-translate. Set by the host before [observe]. */
    var copiesCaptureOriginal = true

    /** Definitions' dimming ([CaptureOverlayLayer.scrimHolder]): a root child
     *  beneath the panel at all times, while the boxes rise above a parked
     *  strip ([syncChipsOrder]), so a card never dims the strip. */
    private val scrimHolder = FrameLayout(ctx)

    /** A touch sequence that began on the mode pill: the root's own rules
     *  stand down until it ends. */
    private var pillTouch = false

    /** The host's presenter has boxes up (the camera and import reviews). */
    private var presenterShown = false

    /** Where the boxes sit against the sheet: above it while the sheet rests
     *  in its sliver, beneath it otherwise. The parked strip is exactly the
     *  bottom band a bottom-anchored dialogue's translation lands in, so a
     *  box that reaches into it is drawn over the strip rather than cut off
     *  by it; a box that would draw over the hint row moves the row into the
     *  widest stretch the boxes leave uncovered, or drops it to its glyph
     *  ([placeSliverHint]), and a strip covered end to end hides even that
     *  but not the tap (the root claims
     *  every touch in the band before any child sees it, and the edge ring
     *  still says an overlay is up). The moment the sheet
     *  starts growing the boxes go back beneath it, so a sheet being read
     *  never has a box over its content. Driven from [applyCollapseCrossfade]
     *  — the parked look and this order are one state, decided in one
     *  place for every height path (see [syncChipsOrder]). */
    private var chipsOverSheet = false

    /** True from the moment the panel starts collapsing to its bottom-edge
     *  sliver until it starts expanding back. Root touch handling swaps to
     *  sliver rules while set. Purely the PANEL's position — the on-screen
     *  boxes are the independent [PresentationPrefs.overlayMode] axis. */
    private var sliverMode = false

    /** Whether the on-screen boxes are currently up (via [showChips]): the
     *  live counterpart of the persisted toggle, so a box fill and Done know to
     *  promote in place ([updateChips]) vs present fresh, and hide paths can no-op. */
    /** Boxes are painted over the frame right now: the host's presenter's,
     *  or the layer's. Derived, never mirrored, so a refused or faded paint
     *  can't leave a stale claim. */
    private val boxesShown: Boolean
        get() = if (boxPresenter != null) presenterShown else layer.hasBoxes

    /** The layer is still building its boxes (readings land off the main
     *  thread); a parked sheet stands on this until they do. */
    private val boxesPending: Boolean
        get() = boxPresenter == null && layer.pending

    /** The parked strip's hint, sliver-only (see the body's addView): a
     *  touch glyph and "Tap for more options", the unit the collapse
     *  crossfade drives ([applyCollapseCrossfade]). Laid out centred and
     *  slid by translationX to wherever [placeSliverHint] puts it beside
     *  the on-frame boxes. It reads the same while the capture is still
     *  working: the mode pill's spinner is that sign ([updateModeControl]),
     *  and a tap here opens the status ([userExpandFromSliver]). */
    private val sliverHint = TextView(ctx)

    /** Whether the hint shows its text or only its leading glyph:
     *  [placeSliverHint]'s verdict, false when a box would draw over the
     *  centred hint and the widest stretch of the parked strip the boxes
     *  leave uncovered can't seat the text. Read by [updateSliverHint]. */
    private var hintTextShown = true

    /** What [updateSliverHint] last applied ([hintTextShown]; null before
     *  the first), so the re-placements that follow every chip layout
     *  don't re-set the same text and relayout the hint for nothing. */
    private var appliedHintTextShown: Boolean? = null

    /** The hint's horizontal slide between placements. A plain
     *  ValueAnimator: the collapse crossfade cancels the hint's
     *  ViewPropertyAnimator every frame, which must not kill a slide. */
    private var hintSlideAnimator: ValueAnimator? = null
    private val rootLoc = IntArray(2)

    /** The status in [statusText] is a phase still in flight (false for a
     *  terminal status, and while a result is bound). One fact for its
     *  three readers: the edge indicator's working state, set from here;
     *  the mode pill's spinner ([updateModeControl]); and whether a tap out
     *  of the park is a look at progress ([userExpandFromSliver]). */
    private var statusInFlight = false
        set(value) {
            field = value
            edgeIndicator.working = value
        }

    /** Set once the USER has stated a posture for this sheet — a tap-expand, a
     *  drag out of the park, a stick resize. It stands down the automatic
     *  re-park at [CaptureState.Translating], which otherwise re-reads the
     *  PREF and would slam the sheet shut the moment OCR lands, undoing the
     *  tap that was made to watch that very work. Cleared by a deliberate
     *  re-park, so parking the sheet again hands control back to the pref.
     *
     *  Deliberately NOT set inside [expandFromSliver]: that path is also the
     *  automatic recovery for "boxes were expected but refused", which is the
     *  sheet rescuing itself, not the user choosing anything. */
    private var userStatedPosture = false

    /** True while the only reason the sheet is out of its park is a tap during
     *  a LOADING phase (a peek at progress, not a posture), or its return
     *  from a page without its boxes, which brings it up on its own
     *  ([showWithResult]). [recordPosture] keeps writing the collapsed posture
     *  through it, so watching one capture work, or coming back from a page,
     *  doesn't silently retire a collapsed default. Any real posture gesture
     *  afterwards (drag, resize, re-park) clears it and records normally. */
    private var loadingPeekExpand = false

    /** Where the slide in [heightAnimator] ends; meaningful only while it
     *  runs. [settle] lets a slide already on its way there run on. */
    private var settleTargetPx = 0

    /** The panel height a park (the animated slide down to the sliver) set
     *  out from, while that slide runs; null otherwise. The Anki pill fades
     *  over the whole slide ([applyCollapseCrossfade]). */
    private var parkFromPx: Int? = null

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
    /** The sections' layout, the scroll's content (see [buildContent]). */
    private val sections = ResultSectionsLayout(ctx)
    /** The floating Anki pill at the sheet's bottom end, over the scroll
     *  ([syncAnkiPill] says when it shows). Inflated with the layout's own
     *  gravity and margins, which put it inside the body's padding, above
     *  the navigation bar's buffer. */
    private val ankiPill = inflater().inflate(R.layout.anki_pill, body, false) as FrameLayout
    /** The pill's resting alpha (the layout's), which the park scales. */
    private val ankiPillAlpha = ankiPill.alpha
    private val handle = HandleView(ctx)
    // The "an overlay is up" signal for as long as this sheet is up, sliver
    // included (see [EdgeIndicator]): a frame around the display edges
    // (bottom-most child of the root, beneath the on-frame boxes) and a lit
    // rail on the sheet's top edge (just beneath the panel, over the edge
    // shadow, positioned by [syncSheetEdge]). Shown while the
    // [Prefs.edgeIndicatorEnabled] setting is on, observed live for the
    // sheet's lifetime from [show], which also pulses it once as the sheet
    // appears (a later collapse to the sliver does not re-flash).
    private val edgeIndicator = EdgeIndicator(ctx, ctx.themeColor(R.attr.ptAccent), cornerRadiusPx)
    // A soft drop shadow cast above the sheet's top edge. The blur is BAKED ONCE
    // into [shadowBitmap]; the view only blits it and is repositioned via
    // translationY as the sheet grows/slides — never re-blurred (see [bakeEdgeShadow]).
    private val edgeShadow = EdgeShadowView(ctx)
    // The controller cursor's accent ring, drawn at ROOT level (over the panel,
    // and over any popover: [popovers] inserts its views beneath it) so it can
    // outline buttons, word spans and a ⋯ menu's rows alike without fighting
    // any child clipping.
    private val focusRing = FocusRingView(ctx)
    private var shadowBitmap: Bitmap? = null
    // The shadow (and the edge rail) track the sheet through a single pre-draw hook (see [syncSheetEdge])
    // rather than per-mover wiring — so no drag/animation path can move the sheet
    // and leave the shadow behind.
    private var edgeSync: ViewTreeObserver.OnPreDrawListener? = null
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
     *  pre-draw hook re-invalidates the body when it drifts (see syncSheetEdge). */
    private var lastFrostOff = Int.MIN_VALUE

    private var binder: TranslationSectionBinder? = null

    /** The sheet's one in-window popover: the text-size picker, or a section
     *  header's ⋯ menu. Hosted in [root] — the sheet's own full-screen
     *  window — so it can float above the sheet's top edge over the game
     *  while staying an IN-WINDOW child (a sibling overlay window would dim
     *  under MediaProjection's QTI clamp and eat the panel's taps), and kept
     *  under [focusRing] so the controller ring can outline the menu's rows.
     *  Every "is a popover up" rule here (touch, B, nav) asks it. */
    private val popovers = PopoverHost(root, below = focusRing)

    /** Whether Original and Translation sit side by side. Decided once, in
     *  [show] (see there for why once), and held by the sections' layout,
     *  which arranges the two and their hidden states from it. Everything
     *  here that differs by arrangement reads it back from the layout. */
    private val paired: Boolean
        get() = sections.fixedArrangement == ResultSectionsLayout.Arrangement.PAIRED

    // Auto-height + text fit. Every part of the content's height is read
    // LIVE each fit (the headers, each card's inset and in-card overhead,
    // which includes the target's note row, and the texts at max size) and
    // summed; the laid-out content's own height is never read, because the
    // fill ([applyCardFill]) stretches the cards to the panel. Plus the one
    // slide that moves the height ([settle]).
    private var heightAnimator: ValueAnimator? = null
    // Height that shows all content at max text size: the drag-resize ceiling
    // ([dragCeilingPx]), so the user can't grow the panel into empty space
    // beyond what the content needs.
    private var maxNeededHeightPx = Int.MAX_VALUE
    // The auto-size ceiling: 50% of the screen by default, but the user's dragged
    // height once they resize — so a re-fit (furigana, translation arriving) keeps
    // their chosen height instead of snapping back down to 50%.
    private var autoMaxPx = Int.MAX_VALUE

    // ── Where the sheet rests, and what moves it ─────────────────────────
    // Where the sheet rests is a function of its state ([restingHeightPx]):
    // parked, the sliver; open, its reading height ([readingHeightPx]: the
    // content's fit, or the height the user let it go at), raised to what a
    // status on show measures. Anything may change that state at any time:
    // the capture parks the sheet or brings it up, a result or a status
    // lands, the content's need changes, the user lets go of it. One mover
    // takes the height there ([settle]), and it moves nothing while the user
    // holds the sheet ([heightHeld]): a slide under way stops under the
    // finger, and when they let go the sheet settles once, from the state of
    // that moment, which their own release has written last. A gesture
    // keeps the rules it began under ([CaptureResultRoot]), so a park
    // written under a drag does not end the drag. A fit or a park that moved
    // the height under a drag is what sprang a collapsing sheet back up.

    /** The open sheet's resting height, a status's floor aside: the
     *  content's fit ([autoSizeAndFit]), the height the user let the sheet
     *  go at ([commitHeight]), just enough for a status coming out of the
     *  park ([expandFromSliverForStatus]), or the loading floor it opens
     *  at. A park leaves it as it is, so the sheet comes back to it, and
     *  the fit keeps it current while parked. */
    private var readingHeightPx = 0

    /** A finger is on the sheet's window: from a gesture's DOWN to its UP or
     *  CANCEL, as the root sees them ([CaptureResultRoot]), which it does
     *  for every gesture, those the sliver's rules swallow included. */
    private var touchHeld = false

    /** The user holds the sheet's height: a finger on its window, or the
     *  stick resizing it ([stickResizing]). */
    private val heightHeld: Boolean get() = touchHeld || stickResizing

    /** The sheet has somewhere to settle once the user lets go: what it
     *  rests by changed while they held it, or a slide was under way when
     *  they took hold. */
    private var settleOwed = false

    /** How tall a drag may pull the sheet: what its content needs, and
     *  never lower than where the sheet is, so content that needs less
     *  than the finger holds stops the drag instead of pulling the sheet
     *  out from under it (the release's fit brings it down,
     *  [commitHeight]). Nothing is kept from before the drag: the need may
     *  not have been measured yet when it began. */
    private val dragCeilingPx: Int get() = maxOf(maxNeededHeightPx, panelHeightPx)

    // Tap-a-word → definition lens (the shared presenter): display + speak +
    // the open-detail tap and the Anki button, entry or not, shared with the
    // drag flow via SourceLensActions. Built with the binder in show().
    private var sourceLens: SourceTextLens? = null

    // In-place edit (the panel window goes focusable so the IME shows over the game).
    private var lastResult: TranslationResult? = null
    /** The word lookup of the source text on show, both tiers
     *  ([WordLookupPipeline]): its analysis gives the source text its tap
     *  spans, and its rows fill the Words card while that is shown. */
    private val wordLookups = WordLookupPipeline(scope)
    private var wordRows: WordRowsBinder? = null
    private var lookupsRenderer: WordLookupsRenderer? = null

    /** The Ask section's question and answer about the result on show
     *  ([AskSession], bound to its sentence in [bindResult]), and its
     *  binder. */
    private val askSession = AskSession(scope)
    private var askBinder: AskSectionBinder? = null

    /** Where this sheet's alerts show: an overlay window of its own, or the
     *  hosting activity ([ttsAlertTarget], set before [show]). */
    private val alertTarget: TtsAlertTarget by lazy {
        ttsAlertTarget ?: TtsAlertTarget.Overlay(ctx, overlayHost, wm, displayId)
    }

    /** Completes when the sheet's entrance has ended. The Words card's page
     *  waits for it: the first WebView of a process blocks the main thread
     *  while it loads, which must not land inside the slide-in. */
    private val entranceSettled = CompletableDeferred<Unit>()
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
        val hintColor = ctx.themeColor(R.attr.ptTextHint)
        // A touch_app glyph leads the text — the collapsed sheet's gesture
        // is a TAP, and the up-arrows that used to flank this said the
        // opposite. RELATIVE (start-side) so it leads in RTL too, tinted
        // with the text and sized to its line — the 24dp intrinsic would
        // dwarf 11sp text.
        val touchAppHintIcon = ctx.getDrawable(R.drawable.ic_touch_app)?.mutate()?.apply {
            setTint(hintColor)
            setBounds(0, 0, dp(HINT_GLYPH_DP), dp(HINT_GLYPH_DP))
        }
        sliverHint.apply {
            setTextColor(hintColor)
            textSize = 11f
            isSingleLine = true
            // Bounded in show(): a long locale at a large font size must
            // trail off rather than run out of the sheet.
            ellipsize = android.text.TextUtils.TruncateAt.END
            setCompoundDrawablesRelative(touchAppHintIcon, null, null, null)
            visibility = View.GONE
            alpha = 0f
        }
        updateSliverHint()
        scroll.apply {
            isFillViewport = true
            visibility = View.GONE
            // This view stays unpadded — the content buffer lives INSIDE
            // the sections' layout (buildContent) and the nav-bar buffer on the body —
            // which pins the fade band exactly to the viewport bottom with
            // nothing able to draw past it. (A scroll-level padding +
            // clipToPadding=false variant let content render below the fade
            // line: the hovering-gradient artifact of 2026-07-14.)
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(dp(24))
            addView(
                sections,
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
                    setColor(
                        Color.argb(
                            FrostBackdrop.WASH_ALPHA,
                            Color.red(bg), Color.green(bg), Color.blue(bg),
                        ),
                    )
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
            addView(ankiPill)
            addView(
                statusText,
                FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER),
            )
            addView(editContainer, FrameLayout.LayoutParams(MATCH, MATCH))
            // Sliver-only hint: lives in the sheet strip the collapse
            // leaves visible ([SLIVER_SHEET_DP] is sized to fit it), fading in
            // as the sections fade out. Non-clickable — taps fall through to
            // the root's sliver rules (tap = expand).
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
        // The edge ring first → beneath everything else here: the on-frame
        // boxes ([showChips] inserts them right above it, and [syncChipsOrder]
        // lifts them above the panel only while it is parked) and the panel,
        // so the bloom never tints a chip or the sheet.
        root.addView(edgeIndicator.ring, FrameLayout.LayoutParams(MATCH, MATCH))
        // Definitions' dimming over the ring and beneath everything else, the
        // panel included, whatever the sheet's posture.
        root.addView(scrimHolder, FrameLayout.LayoutParams(MATCH, MATCH))
        layer.scrimHolder = scrimHolder
        // The boxes right above the dimming; [syncChipsOrder] lifts them above
        // the panel while it is parked.
        root.addView(layer, FrameLayout.LayoutParams(MATCH, MATCH))
        layer.onBoxesLaidOut = { placeSliverHint() }
        layer.onBoxesSettled = { onBoxesSettled() }
        // A Definitions card that ended by its own means (a page it opened,
        // a lost window) ends the sheet as the user would: the boxes never
        // sit over what the card opened.
        layer.onSessionEnded = { if (!dismissed && !animatingOut) animateOutAndDismiss() }
        // L1 / R1 on a Definitions card that holds the controller step the
        // mode pill as on the sheet; the card goes with Definitions.
        layer.onShoulderKey = { toRight -> stepOverlayMode(toRight) }
        // Outside gestures go to the layer first (a word of Definitions, a
        // card to close); a host with a frame of its own replaces this.
        outsideLookupRouter = { ev -> layer.onTouch(ev) }
        // Shadow next → behind the panel, so the opaque sheet covers all but the
        // soft fade cast above its top edge.
        root.addView(edgeShadow, FrameLayout.LayoutParams(MATCH, shadowHeightPx, Gravity.TOP))
        // The rail over the shadow (its glow lights the shadow's dark halo,
        // which gives the accent contrast on a bright scene) and beneath the
        // panel, which covers everything below the sheet edge.
        root.addView(
            edgeIndicator.rail,
            FrameLayout.LayoutParams(MATCH, edgeIndicator.rail.heightPx, Gravity.TOP),
        )
        root.addView(panel, FrameLayout.LayoutParams(MATCH, 0, Gravity.BOTTOM))
        // After the panel so the ring draws over the sheet; a plain non-clickable
        // View, so in-panel touches fall through it to the panel below.
        root.addView(focusRing, FrameLayout.LayoutParams(MATCH, MATCH))
        // The mode pill last: above everything, the parked boxes and the ring
        // included, at the top edge where neither the panel nor a
        // bottom-anchored dialogue reaches. The status-bar inset is added
        // when the insets arrive.
        root.addView(modePill, OverlayModePill.hostLayoutParams(ctx))
        modePill.onSelect = { selectOverlayMode(it) }
        modePill.onCollapsedChanged = { prefs.captureOverlayPillCollapsed = it }
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
        postureFromOverride = overlayModeOverride != null
        // Bound the sliver hint now that the display width is known: it is
        // WRAP and centered, so nothing else would stop a long locale's
        // text from running out past the sheet's edges. The bound is the
        // whole hint's, its glyph included ([fullHintRowWidthPx] measures
        // to the same one).
        sliverHint.maxWidth =
            (screenW - dp(SLIVER_HINT_SIDE_PAD_DP * 2)).coerceAtLeast(dp(48))
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
        readingHeightPx = panelHeightPx
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
            backdropSmall = FrostBackdrop.blur(it)
            backdropDst.set(0, 0, screenW, screenH)
            body.invalidate()
        }
        // Park below the bottom edge; the entrance animation (below) raises it.
        panel.translationY = panelHeightPx.toFloat()

        // The width the sections share is the screen less the sides the
        // body buffers (the inset listener below): a side navigation bar
        // this window is dispatched (below the bars on the MediaProjection
        // backend; on Android 11 the accessibility overlay too, which lies
        // above them, since that version sends every bar to every window)
        // and the display cutout. The decision is made once, here, and the
        // bar can come and go under a non-focusable sheet, so it reserves
        // the bar's inset whether or not the bar is showing
        // ([OverlayEnvironment.fittedReserved], as the error pills do): a display whose
        // landscape width lies within a bar's width above the breakpoint
        // keeps one column rather than two that a later bar would squeeze
        // (Codex reviews, 2026-10-11: a 640 dp display with a 48 dp side
        // bar chose two columns for 592 dp, and re-deciding mid-session
        // would rebuild the bound panel).
        val side = sideReserveReader(ctx)
        val sideBySide = CaptureResultGeometry.shouldUseSideBySide(
            screenW - side.left - side.right, dp(1),
            (CaptureResultGeometry.SIDE_BY_SIDE_FALLBACK_SECTION_DP * density).toInt(),
        )
        buildContent(paired = sideBySide)

        val b = TranslationSectionBinder(panel, ctx, prefs, scope, alertTarget, popovers)
        b.setupSectionButtons(onEdit = { startInPlaceEdit() })
        // Anki is the floating pill: a tap opens the review, a hold sends
        // the card at once (and closes the sheet where its host allows a
        // gesture to, see oneTapSentenceFromOverlay).
        PillAnkiButton(
            ankiPill,
            onTap = { openSentenceAnkiReview() },
            onHold = { oneTapSentenceFromOverlay() },
        )
        b.onSectionVisibilityChanged = {
            refitWhenLaidOut()
            // An answer on show says whether it may give a hidden translation away.
            askBinder?.syncSpoilerWarning()
            // Eye reveal on a deferred result: run the skipped translation now.
            maybeCompleteDeferred()
        }
        // Furigana changes the source's rendered height (async on / sync off) — re-fit.
        b.onSourceTextHeightChanged = { if (!dismissed) autoSizeAndFit() }
        val cardAlpha = if (opaqueBackgroundBoost) CARD_FILL_ALPHA_NO_IMAGE else CARD_FILL_ALPHA
        b.setCardFillAlpha(cardAlpha)
        for (card in listOf(R.id.cardAsk, R.id.cardWords)) {
            sections.findViewById<MaterialCardView>(card)
                ?.setCardBackgroundColor(sectionCardFill(ctx, cardAlpha))
        }
        b.onChooseOcr = {
            val r = lastResult
            val p = r?.ocrProvenance
            val sp = r?.screenshotPath
            if (p != null && sp != null) showOcrPicker(p, sp)
        }
        b.translationPickerOffered = translationPickerOffered
        b.onChooseTranslation = { lastResult?.let { showTranslationPicker(it) } }
        b.onChooseLanguage = { isSource -> changeLanguage(isSource) }
        val fontPicker = FontSizeRangePopover(ctx, prefs).apply {
            onRangeChanged = { refitForFontRange() }
        }
        b.onChooseFontSize = { anchor -> popovers.toggle(fontPicker, anchor) }
        // A controller cursor follows a popover: into a ⋯ menu's rows once
        // they have laid out, back onto what opened it as it closes.
        popovers.addListener(object : PopoverHost.Listener {
            override fun onPopoverChanged(open: Boolean, content: PopoverContent, anchor: View) {
                if (!open) nav?.onPopoverChanged(open = false, anchor = anchor)
            }

            override fun onPopoverLaidOut(content: PopoverContent, anchor: View) {
                nav?.onPopoverChanged(open = true, anchor = anchor)
            }
        })
        binder = b
        val lens = SourceTextLens(
            ctx, wm, displayId,
            // Null host = the lens's activity-window mode (see
            // [wordLensInActivity]); the camera panel's wm IS its Activity's
            // WindowManager, so the lens window attaches to the activity
            // token without any overlay permission.
            overlayHost = if (wordLensInActivity) null else overlayHost,
            scope = scope,
            ttsAlertTarget = alertTarget,
            text = BoundSourceText(b),
            screenSize = { Point(screenW, screenH) },
            // The open works entry-or-not — it opens the sentence view — so
            // every section opens.
            opensWithoutEntry = true,
            onAskNeedsLlm = { showAskNeedsLlm() },
            wireActions = { magnifier, resolvedAt -> wireLensActions(magnifier, resolvedAt) },
        )
        sourceLens = lens
        // The Words card, and the word lookup that feeds it and the source
        // text's tap spans: the page's own binder, pipeline and renderer.
        val words = WordRowsBinder(
            panel, ctx, prefs,
            object : WordRowsBinder.Host {
                override val isAlive: Boolean get() = !dismissed
                override val scope: CoroutineScope get() = this@CaptureResultOverlay.scope
                override val ttsAlertTarget: TtsAlertTarget get() = alertTarget
                override fun onWordTapped(row: RowState, explanation: Explanation?) = openWordRow(row, explanation)
                override fun onAskNeedsLlm() = showAskNeedsLlm()
            },
            // The card as it is seen: its translucent fill over the sheet's
            // own colour, which stands in for the frosted game behind it (a
            // few percent of what shows through). A pinned word head's band
            // is drawn in it.
            cardColor = ColorUtils.compositeColors(sectionCardFill(ctx, cardAlpha), ctx.themeColor(R.attr.ptBg)),
        )
        // The card asks for its rows whenever it is shown and may need them;
        // the pipeline no-ops unless an analysis is waiting for its rows.
        words.onRowsWanted = { wordLookups.requestRows(ctx.applicationContext) }
        words.onHeightChanged = { refitWhenLaidOut() }
        words.attachCollectors(scope)
        wordRows = words
        val renderer = WordLookupsRenderer(
            words, lens,
            displayedSourceText = { b.displayedSourceText() },
            scope = { scope },
            isAlive = { !dismissed },
            awaitEnterSettled = { entranceSettled.await() },
            // New ranges: the controller cursor's word index is stale.
            onSpanRangesChanged = { nav?.onWordSpansChanged() },
        )
        lookupsRenderer = renderer
        wordLookups.onRowsSettled = { lastResult?.let(::writeSentenceCache) }
        scope.launch { wordLookups.state.collect { renderer.render(it) } }
        // The Ask section: the page's own binder over this sheet's session.
        val ask = AskSectionBinder(
            panel, ctx, prefs, askSession, popovers,
            object : AskSectionBinder.Host {
                override fun onLlmNeeded() = showAskNeedsLlm()
                override fun onFieldFocusChanged(focused: Boolean) = onAskFieldFocusChanged(focused)
                override fun onHeightChanged() = refitWhenLaidOut()
                // On this sheet's own alert surface, as its other alerts.
                override fun showAlert(configure: (OverlayAlert.Builder) -> Unit) =
                    showAlertOn(alertTarget) { configure(this) }
                override fun openTranslationServices() = this@CaptureResultOverlay.openTranslationServices()
            },
        )
        askBinder = ask
        scope.launch { askSession.state.collect { ask.render(it) } }
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
            // A side navigation bar (a phone moves three-button navigation to
            // a side in landscape) and a side cutout buffer the content's
            // sides the same way; see [ContentInsets].
            val clear = ContentInsets.of(insets)
            val navInset = clear.bottom
            val sideLeft = clear.left
            val sideRight = clear.right
            val imeLift = clear.imeLift
            // The mode pill sits under the status bar, not behind it.
            modePill.placeUnder(clear.top)
            if (navInset != bottomInsetPx || sideLeft != body.paddingLeft || sideRight != body.paddingRight) {
                bottomInsetPx = navInset
                body.setPadding(sideLeft, body.paddingTop, sideRight, navInset)
                // The buffer is part of every height the sheet rests at: the
                // sliver follows it at once, a status's floor is taken again,
                // and the content's fit is, parked too, once the new paddings
                // have laid out (a side bar re-wraps the text): a parked sheet
                // comes back to that reading height.
                when {
                    sliverMode -> {
                        settle(snap = true)
                        placeSliverHint()   // the strip's band moved with the buffer
                        refitWhenLaidOut()
                    }
                    resultOnShow -> refitWhenLaidOut()
                    else -> settle(snap = true)
                }
            }
            val panelLp = panel.layoutParams as FrameLayout.LayoutParams
            if (panelLp.bottomMargin != imeLift) {
                panelLp.bottomMargin = imeLift
                panel.requestLayout()
                // The keyboard went away under the Ask question (its own
                // hide button, the system's Back): typing there is over.
                // The source edit is an editor with its own way out; the
                // question is a field that would merely look idle, with the
                // sheet still in its typing state behind it (no swipe-away,
                // no taps passed on to the game).
                if (imeLift == 0 && typingIn == TypingIn.ASK_QUESTION) askBinder?.clearFieldFocus()
            }
            insets
        }
        // Controller navigation arms only when the over-game flow opted in AND
        // a nav-capable device is actually attached (gamepad, or a hardware
        // keyboard whose Escape/arrows/Enter mirror B/dpad/A) — evaluated
        // once, here: no device listener, so a device plugged in later gets
        // nav on the NEXT sheet. The window is created already-focusable (no
        // async flag flip to race), which also lands the OverlayHost
        // focusable-overlay paper trail on add.
        val navActive = controllerNavEnabled && hasNavInputDevice(ctx)
        // Definitions holds the keys from the first frame, while the read
        // runs too ([holdsKeysForDefinitions]).
        sheetHost.attach(root, screenW, screenH, focusable = navActive || holdsKeysForDefinitions)
        // The edge indicator follows its setting for the sheet's lifetime
        // (observe seeds the current value now and fires on every change; the
        // collector dies with [scope] in dismiss): the camera flow's OCR
        // download deep link lands on that setting's screen with the review
        // still up, so a flip has to reach a showing sheet.
        scope.launch {
            prefs.observe(Prefs.KEY_EDGE_INDICATOR_ENABLED).collect {
                edgeIndicator.isVisible = prefs.edgeIndicatorEnabled
            }
        }
        // A capture limited to a custom region outlines that region, and a
        // full-screen one leaves the display its glow without the stroke;
        // the root covers the display, so the crop's fractions map straight
        // to its pixels.
        edgeIndicator.setCaptureRegion(captureRegion, screenW, screenH)
        edgeIndicator.fadeIn()
        // One flash as the sheet appears (played once the window is shown;
        // an activity host attaches synchronously and plays it now).
        edgeIndicator.pulse()
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
        edgeSync = ViewTreeObserver.OnPreDrawListener { syncSheetEdge(); nav?.syncRing(); true }.also {
            root.viewTreeObserver.addOnPreDrawListener(it)
        }
        // Ease in from the bottom — a plain decelerate, no overshoot/bounce.
        panel.animate()
            .translationY(0f)
            .setDuration(ENTER_DURATION_MS)
            .setInterpolator(DecelerateInterpolator())
            // The entrance has ended, or another animation of the panel cut
            // it short, which ends it too: what waited for it may run
            // ([entranceSettled]). One-shot, since a listener stays on the
            // view's animator for every later animation.
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    panel.animate().setListener(null)
                    entranceSettled.complete(Unit)
                }
            })
            .start()
    }

    /** A section's card was shown, hidden or resized, or the insets moved
     *  the content's edges: grow/shrink the panel to the new content once
     *  that has laid out. Two frames: the sections' layout re-arranges
     *  itself from the cards' visibility (a hidden section keeps its header;
     *  in a pair the other card takes the full width, and its text re-wraps
     *  there), and that must lay out before we measure, else we'd size to
     *  the stale layout. Same settle window before the controller cursor
     *  re-targets: what was under it may have gone. */
    private fun refitWhenLaidOut() {
        sections.post {
            sections.post {
                if (dismissed) return@post
                autoSizeAndFit()
                nav?.revalidateCursor()
            }
        }
    }

    /** Reads the capture's original aloud when [state] is the one that
     *  brings it ([captureOriginalToRead]) and the user asked for that
     *  ([TranslationSectionBinder.autoPlayOriginal]). A sheet in
     *  tap-to-lookup's mode ([overlayModeOverride]) reads nothing. Called
     *  last in a state's handling: the bind before it closes a word lens
     *  on another text, and taking boxes down ends a word lookup, and each
     *  of those stops whatever is playing. */
    private fun autoPlayOriginal(previous: CaptureState?, state: CaptureState) {
        captureOriginalToRead(previous, state, overlayModeOverride)?.let { binder?.autoPlayOriginal(it) }
    }

    /** Collect the one-shot capture session and drive the panel. The collector is
     *  cancelled on dismiss (no Activity lifecycle to auto-cancel it). */
    fun observe(session: CaptureSession) {
        sessionJob?.cancel()
        captureSession?.cancel()
        captureSession = session
        // Its original is copied when the capture reads it, where this
        // host's captures are copied. A sheet in tap-to-lookup's mode
        // ([overlayModeOverride]) copies nothing.
        if (copiesCaptureOriginal) scope.copyOriginalWhenRead(ctx, session) { overlayModeOverride }
        sessionJob = scope.launch {
            session.stateWithPrevious.collect { (previous, state) ->
                when (state) {
                    is CaptureState.InProgress -> setStatus(state.message, loading = true)
                    // OCR done: show the source now with a "Translating…" placeholder
                    // (blank translatedText renders it); Done fills it in + re-fits.
                    is CaptureState.Translating -> {
                        // More of this capture's boxes translated: swap them in
                        // place and nothing else. The source, the panel's posture
                        // and the toggle stay as the first Translating set them;
                        // the boxes are kept current while hidden too, so a
                        // toggle-on paints the latest.
                        if (state.fillsBoxesOf(previous)) {
                            overlayData = state.overlayData
                            state.overlayData?.let { fillBoxes(it) }
                            return@collect
                        }
                        // The capture's boxes exist from OCR time (skeletons, some
                        // possibly translated already): expose them BEFORE the
                        // bind so the header's boxes toggle is offered while the
                        // translation is still running (later fills and the Done
                        // promotion swap them in place either way).
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
                        // Boxes are their own axis: they go up the moment OCR
                        // lands, whatever the panel is doing (a re-run with boxes
                        // already showing swaps them in place). (A fit while
                        // parked moves nothing: it keeps the drag ceiling and
                        // the reading height current.)
                        state.overlayData?.let { paintBoxes(it) }
                        // Collapsed-start may park the panel (or keep show()'s
                        // park) only while the sliver has something to stand on:
                        // boxes painted or still building (readings land off
                        // the main thread; [onBoxesSettled] re-judges then), or
                        // the user's persisted no-boxes posture. When boxes were
                        // EXPECTED but reality refused them — the overlay
                        // surface is live mode's, or OCR produced nothing
                        // paintable — a sliver would leave NOTHING readable, so
                        // the panel comes (or stays) up instead.
                        //
                        // ...and only while the user hasn't overruled it. This
                        // test re-reads the PREF, so without that term a tap
                        // during the loading arc would be undone by the very
                        // work it was made to watch: OCR lands, and the sheet
                        // the user just opened slams shut in the same frame.
                        //
                        // Under a finger this moves nothing: the gesture keeps
                        // the rules it began under, and its release, written
                        // last, decides where the sheet rests ([settle]).
                        val startsCollapsed = CaptureResultGeometry
                            .isCollapsedPosture(effectiveStartPosture())
                        if (startsCollapsed && sliverJustified() && !userStatedPosture) {
                            collapseToSliver()
                        } else if (sliverMode && !sliverJustified()) {
                            expandFromSliver()
                        }
                        // bindResult rendered the control before the paint
                        // attempt above — re-render now that boxesShown has
                        // settled (same main-thread dispatch, so no flash).
                        updateModeControl()
                        autoPlayOriginal(previous, state)
                    }
                    is CaptureState.Done -> {
                        overlayData = state.overlayData
                        val data = state.overlayData
                        if (data == null) {
                            // Nothing came back: skeletons must not pulse
                            // forever, and a sliver waiting on boxes has
                            // nothing left to show — bring the panel back.
                            hideChips()
                            if (sliverMode && presentationPrefs.overlayMode != OverlayMode.NONE) {
                                expandFromSliver()
                            }
                        } else {
                            // Promote showing skeletons in place; first paint
                            // otherwise (e.g. the mode was picked after OCR, or
                            // the boxes surface was busy at Translating).
                            paintBoxes(data)
                            // Boxes expected but refused (the surface got
                            // claimed since Translating, or every translation
                            // came back blank): a sliver with nothing behind it
                            // is unreadable — bring the panel back.
                            if (sliverMode && !sliverJustified()) expandFromSliver()
                        }
                        bindResult(state.result)
                        // The frame's path and time reach a Definitions card's
                        // pages from here.
                        layer.setFrame(state.result.screenshotPath, state.result.createdAtMs)
                        autoPlayOriginal(previous, state)
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
                    CaptureState.Cancelled -> {
                        // The host's own endings dismiss the sheet before its
                        // session's cancel can land, so this one came from
                        // outside them.
                        dismissReason = DismissReason.CANCELLED
                        dismiss()
                    }
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
     *  is honored only while the overlay mode paints something: in None,
     *  starting parked in the sliver lands a capture with nothing readable on
     *  screen at all — no panel, no boxes — so the sliver is dropped and the
     *  sheet opens at the plain fit-to-content default, as if no posture had
     *  ever been recorded. A remembered HEIGHT always stands: it still shows
     *  the text. (Boxes that were expected but refused at paint time are the
     *  separate, live [sliverJustified] check in [observe].) */
    private fun effectiveStartPosture(): Float {
        // A capture tap-to-lookup opened starts parked ([postureFromOverride]).
        val posture = if (postureFromOverride) CaptureResultGeometry.COLLAPSED_POSTURE
            else presentationPrefs.startPosture
        val blindStart = CaptureResultGeometry.isCollapsedPosture(posture) &&
            presentationPrefs.overlayMode == OverlayMode.NONE
        return if (blindStart) CaptureResultGeometry.NO_POSTURE else posture
    }

    /** Whether the flow is about to open parked in the sliver, so a capture
     *  whose boxes are not translations can wait with its translation until
     *  the panel comes up (the host passes it to the capture as
     *  `deferWhileParked`). */
    fun willOpenParked(): Boolean =
        CaptureResultGeometry.isCollapsedPosture(effectiveStartPosture())

    /** What this sheet's capture may defer, asked by the host after [show]
     *  and passed to the capture as `allowDeferTranslation` and
     *  `deferWhileParked`. */
    data class CaptureDeferral(val allow: Boolean, val whileParked: Boolean)

    /** The deferral for this sheet's mode, its override included: a capture
     *  whose boxes are not translation chips may skip the backend call while
     *  the translation section is hidden ([CaptureDeferral.allow]), and also
     *  while it opens parked, since nothing shows the translation until the
     *  panel comes up and leaving the park completes it
     *  ([CaptureDeferral.whileParked]). Chips need their text, so a
     *  Translations capture defers nothing. */
    fun captureDeferral(): CaptureDeferral {
        val allow = overlayMode != OverlayMode.TRANSLATION
        return CaptureDeferral(allow, allow && willOpenParked())
    }

    /** Whether a parked sheet has something to stand on: the user's own
     *  no-boxes park, or boxes painted or still building. */
    private fun sliverJustified(): Boolean =
        presentationPrefs.overlayMode == OverlayMode.NONE || boxesShown || boxesPending

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
        if (!resultOnShow) return
        // The override's park is not the user's posture ([postureFromOverride]).
        if (postureFromOverride) return
        presentationPrefs.startPosture = CaptureResultGeometry.postureFor(
            autoMaxPx,
            screenH,
            // A sheet that is only open because the user looked in on the
            // loading arc still records as PARKED: watching one capture work
            // is not a change of mind about where the sheet lives, and letting
            // it write an expanded posture would retire a collapsed default
            // behind a single tap. Any real height gesture afterwards clears
            // the peek and records normally (see [loadingPeekExpand]).
            collapsed = sliverMode || loadingPeekExpand,
        )
    }

    /** Re-show entry point for the controller's stash-and-rebind path: set up the
     *  window exactly like [show], then bind a previously-captured result directly
     *  (no capture session) — used when the user backs out of the detail screen.
     *  The result comes back alone: its boxes belong to the frame, the screen and
     *  the capture they were made in, so the stash keeps none, and the sheet
     *  never comes back parked (a strip with nothing over the game). That
     *  opening is the sheet's, not the user's: closing it keeps a parked
     *  posture ([loadingPeekExpand]).
     *  [backdrop] re-supplies the clean capture for the frosted sheet fill — a
     *  re-show is a NEW overlay (the stash keeps no bitmap), so without it the
     *  sheet returns frost-less from every detail / workspace round trip. */
    fun showWithResult(
        screenW: Int,
        screenH: Int,
        result: TranslationResult,
        backdrop: Bitmap? = null,
        /** The Ask section's answer about [result] from the sheet the page
         *  took off the screen, to show again, or to keep out of sight if
         *  it was shelved there. */
        askAnswer: CarriedAnswer? = null,
    ) {
        show(screenW, screenH, backdrop)
        bindResult(result)
        askAnswer?.let {
            askSession.restore(askSentence(result.originalText, result.langContext), it.answer, it.shelved)
        }
        if (sliverMode) {
            expandFromSliver()
            loadingPeekExpand = true
        }
    }

    fun dismiss() {
        if (dismissed) return
        dismissed = true
        // "It opens how you left it" — see recordPosture for what qualifies.
        recordPosture()
        heightAnimator?.cancel()
        dismissWordLens()
        popovers.release()
        sessionJob?.cancel()
        // Cancel the service-side one-shot job too (not just our collector), so
        // OCR/translation doesn't keep running headless after the panel is gone.
        captureSession?.cancel()
        captureSession = null
        binder?.release()
        wordRows?.release()
        layer.release()
        nav?.release()
        nav = null
        edgeSync?.let { root.viewTreeObserver.removeOnPreDrawListener(it) }
        edgeSync = null
        // Orphaned-UP guard: a key that went down on this focused window is
        // still held (the Anki one-tap long-press fires at the timeout, and
        // its no-permission / no-deck fallback dismisses mid-press) — the
        // window must outlive its UI as an invisible key sink until the
        // release lands here, or the UP would be delivered unmatched to
        // whatever window is focused next. See [WindowKeyPairGuard].
        if (root.keyGuard.hasPendingDown && sheetHost.beginKeySink(root)) {
            root.keyGuard.beginLinger { sheetHost.detach(root) }
        } else {
            sheetHost.detach(root)
        }
        shadowBitmap?.recycle()
        shadowBitmap = null
        backdropSmall?.recycle()
        backdropSmall = null
        scope.cancel()
        onDismiss?.invoke(dismissReason)
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
        // Every animated exit is the user's own close.
        dismissReason = DismissReason.USER
        dismissWordLens()
        popovers.dismiss()
        nav?.clearCursor()   // no ring riding the exit slide
        edgeIndicator.fadeOut(EXIT_DURATION_MS)
        // The mode pill goes with the sheet: faded out, a bar closing to
        // its disc on the way ([updateModeControl] leaves it gone).
        modePill.leave(EXIT_DURATION_MS)
        panel.animate()
            .translationY(panelHeightPx.toFloat())
            .setDuration(EXIT_DURATION_MS)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction { dismiss() }
            .start()
    }

    /** The sheet goes because a page it opened takes the screen. */
    private fun dismissForPage() {
        dismissReason = DismissReason.PAGE
        dismiss()
    }

    /** The host tears the sheet down (a newer capture, a live start, its own
     *  teardown): whatever exit was under way, this is the system's. */
    fun dismissBySystem() {
        dismissReason = DismissReason.SYSTEM
        dismiss()
    }

    // ── Sliver state (result shown as on-screen boxes) ───────────────────

    /** Paint [data] in the chosen mode: a first paint, or a swap in place of
     *  boxes already up (the skeleton to translated promotion as a fill or
     *  Done lands). The host's presenter paints for the camera and import
     *  reviews (it reads the mode itself, Definitions' word boxes
     *  included); the layer paints for the over-game sheet, where live mode
     *  owns the surface and refuses it. */
    private fun paintBoxes(data: OneShotOverlayData) {
        val mode = presentationPrefs.overlayMode
        boxPresenter?.let { presenter ->
            if (mode == OverlayMode.NONE) {
                if (presenterShown) {
                    presenterShown = false
                    presenter.hide()
                }
            } else if (presenterShown) {
                presenter.update(data)
            } else {
                presenterShown = presenter.show(data)
            }
            return
        }
        if (CaptureService.instance?.isLive == true) {
            layer.clear()
            return
        }
        val sourceLangId = lastResult?.ocrProvenance?.sourceLangId ?: prefs.sourceLangId
        layer.bind(
            data, sourceLangId, mode,
            screenshotPath = lastResult?.screenshotPath, capturedAtMs = lastResult?.createdAtMs ?: 0L,
        )
    }

    /** The mode the sheet paints in now, override included. */
    val overlayMode: OverlayMode get() = presentationPrefs.overlayMode

    /** More of the same capture translated: swap the boxes in place and
     *  nothing else. Boxes that are not up stay down (a presenter that never
     *  showed, a layer live mode refused). */
    private fun fillBoxes(data: OneShotOverlayData) {
        val presenter = boxPresenter
        if (presenter != null) {
            if (presenterShown) presenter.update(data)
            return
        }
        if (CaptureService.instance?.isLive == true) return
        layer.update(data)
    }

    /** A reading build landed. Nothing drawn under a park the user did not
     *  state leaves the sliver with nothing to stand on: the panel comes up. */
    private fun onBoxesSettled() {
        if (dismissed) return
        if (!boxesShown && sliverMode && !userStatedPosture) expandFromSliver()
        placeSliverHint()
        updateModeControl()
    }

    /** The root child the boxes sit directly above for the current
     *  [chipsOverSheet]: the panel while parked, else Definitions' dimming,
     *  itself just over the edge ring (so the bloom never tints a box, the
     *  dimming never covers one, and the shadow, rail and panel cover them). */
    private fun chipsAnchor(): View = if (chipsOverSheet) panel else scrimHolder

    /** Put the boxes where [chipsOverSheet] says, if they aren't there
     *  already. A move is a same-window child re-add — no window traffic —
     *  and the focus ring and the mode pill, added after the panel, stay
     *  above them either way. Idempotent, so the per-frame height funnel can
     *  call it freely. */
    private fun syncChipsOrder() {
        val anchor = chipsAnchor()
        if (root.indexOfChild(layer) == root.indexOfChild(anchor) + 1) return
        root.removeView(layer)
        root.addView(layer, root.indexOfChild(anchor) + 1)
    }

    /** Take the boxes down: the host's presenter's, or the layer's, which
     *  fade and keep their view for a cheap re-show. */
    private fun hideChips() {
        val presenter = boxPresenter
        if (presenter != null) {
            presenterShown = false
            presenter.hide()
        } else {
            layer.clear()
        }
        placeSliverHint()   // nothing covers the strip any more: the row re-centres
    }

    /** The user's pick of a mode, from the mode pill (a tap, or the shoulder
     *  buttons: [stepOverlayMode]) or from the host's own control
     *  ([hostOwnsModeControl]): paint the bound result in [mode] and
     *  remember it ([showMode]). */
    fun selectOverlayMode(mode: OverlayMode) {
        if (presentationPrefs.overlayMode == mode) return
        presentationPrefs.overlayMode = mode
        showMode(mode)
    }

    /** The tap-to-lookup gesture or hotkey over a sheet already up in another
     *  mode: paint this capture in [mode] without remembering it (the pill
     *  shows it selected; a pick there is what remembers). */
    fun applyOverlayModeOverride(mode: OverlayMode) {
        if (presentationPrefs.overlayMode == mode) return
        overlayModeOverride = mode
        showMode(mode)
    }

    /** None brought the sheet out of its park ([showMode]) and the user has
     *  set no height since: a switch to a mode that draws parks it again.
     *  Ended by any park, the user's or that one ([park]), and by a height
     *  the user lets the sheet go at ([commitHeight]); every height the
     *  user sets ends in one of the two. A park the switch could not make
     *  (nothing drawn to stand on, a field being typed in) leaves it for the
     *  next switch. Not a posture: the sheet closed meanwhile records itself
     *  open, as it always has ([recordPosture]). */
    private var noneHoldsOpen = false

    /** Paint the bound result in [mode], the sheet's mode now, picked or by
     *  an override. Definitions holds the keys and the other modes give
     *  them back. A switch to Translations on a deferred result runs the
     *  skipped translation, so the skeletons fill instead of pulsing
     *  forever. A switch to None while parked leaves nothing on screen but
     *  the strip, so the panel comes up, and None holds it there
     *  ([noneHoldsOpen]) until a switch to a mode with something drawn, or
     *  building, to stand on parks it again. */
    private fun showMode(mode: OverlayMode) {
        val data = overlayData
        if (data != null) paintBoxes(data) else hideChips()
        applyWindowFocusPolicy()
        if (mode == OverlayMode.NONE) {
            if (sliverMode) {
                expandFromSliver()
                noneHoldsOpen = true
            }
        } else {
            if (mode == OverlayMode.TRANSLATION) maybeCompleteDeferred()
            if (noneHoldsOpen && sliverJustified()) collapseToSliver()
        }
        updateModeControl()
    }

    /** L1 ([toRight] false) or R1: the mode pill's segment on that side of
     *  the selected one, wrapping at the ends, picked as a tap on it picks
     *  it ([OverlayModePill.step]), which leaves the pill collapsed or
     *  expanded as it was. What floats over the capture closes first: a
     *  popover, the word lens (which hands the press here while it holds
     *  the controller). A Definitions card, which hands it here too, goes
     *  with Definitions itself, which every step leaves: the layer releases
     *  its words, the card's window lingering until the release
     *  ([MagnifierLens.onShoulderKey]). Nothing while a field is typed in
     *  (the keys are the field's), or while the pill takes no tap (the
     *  capture still reading, the pill on its way out, nothing offered), so
     *  a press that steps nothing closes nothing either. */
    private fun stepOverlayMode(toRight: Boolean) {
        if (dismissed || animatingOut || typingIn != TypingIn.NOTHING || !modePill.canStep) return
        popovers.dismiss()
        dismissWordLens()
        modePill.step(toRight)
    }

    /** The one exit from the park: every exit drops the sliver cursor, and
     *  a deferred translation the park was waiting on runs now
     *  ([maybeCompleteDeferred] reads [sliverMode]). Nothing outside it. */
    private fun leaveSliver() {
        if (!sliverMode) return
        sliverMode = false
        nav?.clearCursor()
        maybeCompleteDeferred()
    }

    /** A DOWN inside the mode pill's bounds (root coordinates). */
    private fun hitsModePill(ev: MotionEvent): Boolean =
        modePill.visibility == View.VISIBLE &&
            ev.x >= modePill.left && ev.x < modePill.right &&
            ev.y >= modePill.top && ev.y < modePill.bottom

    /** Collapse the sheet to its bottom-edge sliver: the capture's own park,
     *  and A on the grabber. Height-based (the sheet's bottom edge retracts,
     *  like the grow-to-fit in reverse) so the sliver drag below is the plain
     *  resize gesture with a lower floor; the content↔hint crossfade rides
     *  the height itself (see [applyCollapseCrossfade]). Purely the panel:
     *  the on-frame boxes are the toggle's independent axis. The user comes
     *  back via a tap (auto-expand) or a drag on the sliver zone. */
    private fun collapseToSliver() {
        if (dismissed || animatingOut || sliverMode) return
        if (typingIn != TypingIn.NOTHING) return
        park()
    }

    /** The sheet rests in its sliver from now: [collapseToSliver], or a drag
     *  or the stick let go in the collapse band or back into the sliver it
     *  came from. Parking hands the wheel back to the pref: whatever the user
     *  stated before, the sliver is what they state now (the capture's own
     *  park runs only while nothing was stated, where this changes nothing).
     *  Parked, nothing holds the sheet open for None any more
     *  ([noneHoldsOpen]). The reading height stays as it is, for the sheet
     *  to come back to. */
    private fun park() {
        if (!sliverMode) {
            sliverMode = true
            dismissWordLens()
            // The sections they act on are about to fade out under the collapse.
            popovers.dismiss()
            nav?.clearCursor()   // the cursor's targets are fading out
        }
        userStatedPosture = false
        loadingPeekExpand = false
        noneHoldsOpen = false
        settle()
    }

    /** Out of the park, back to the reading height, which the content's fit
     *  kept current while parked; the crossfade brings the sections back as
     *  the height climbs, the text already at the size it lands at. A tap on
     *  the sliver, and the sheet's own rescue when its park has nothing to
     *  stand on. */
    private fun expandFromSliver() {
        if (dismissed || animatingOut || !sliverMode) return
        leaveSliver()
        settle()
    }

    /** Out of the park for a STATUS phase — a loading re-run (collapsed page
     *  flip, settings refresh) or a Failed/NoText landing while parked. The
     *  sheet comes up just enough to read a status: its reading height drops
     *  to the loading floor, NOT the previous scene's fit (up to the drag
     *  ceiling) [expandFromSliver] goes back to, which drew as a mostly-empty
     *  sheet ballooning open over a one-line "Recognizing text…" on every
     *  collapsed page flip. The status's own floor is part of where the sheet
     *  rests ([restingHeightPx]), whichever status lands and in either order:
     *  landscape's 20% floor is shorter than the status block. */
    private fun expandFromSliverForStatus() {
        if (dismissed || animatingOut || !sliverMode) return
        leaveSliver()
        readingHeightPx = CaptureResultGeometry.minPanelHeight(screenH)
        settle()
    }

    /** The USER pulling the sheet out of its park: the sliver tap and the
     *  controller's mirror of it. Distinct from [expandFromSliver], which is
     *  ALSO the sheet's automatic rescue when boxes were expected but refused
     *  — that is the sheet saving itself, not the user choosing anything, and
     *  only a real gesture may stand down the re-park at Translating. It
     *  states the posture even when the capture brought the sheet up during
     *  the tap.
     *
     *  A tap during a LOADING phase expands to status height rather than the
     *  reading height: on a re-run while parked (the camera's region change
     *  drives its loading status straight through the sliver) that is the
     *  PREVIOUS scene's fit, and restoring it balloons a mostly-empty sheet
     *  open over a one-line "Recognizing text…" — the fault
     *  [expandFromSliverForStatus] exists for. */
    private fun userExpandFromSliver() {
        if (dismissed || animatingOut) return
        userStatedPosture = true
        // Two different questions, deliberately keyed to two different facts.
        // HEIGHT: any status at all takes the status-sized expansion — the
        // ballooning fault is about the sheet's size against a one-line block,
        // whatever that block says. PEEK: only a status still in flight is a
        // look at progress; anything else is the user opening the sheet to
        // read it, which is a posture like any other.
        loadingPeekExpand = statusInFlight
        if (statusText.visibility == View.VISIBLE) {
            expandFromSliverForStatus()
        } else {
            expandFromSliver()
        }
    }

    /** The sliver drag has passed touch slop: the user is pulling the sheet
     *  edge to a height of their choosing, so the drag owns the height from
     *  here (the touch's hold stopped any slide at its DOWN; the crossfade
     *  follows the dragged height). */
    private fun beginSliverDrag() {
        nav?.clearCursor()   // a pull out of the park drops the sliver cursor
    }

    /** Per-frame sliver drag: the resize math with the floor lowered to the
     *  sliver itself (the standard floor is the commit threshold, not a clamp,
     *  so the sheet must be able to sit below it mid-drag). Downward has nowhere
     *  to go — the sliver IS the bottom of the sheet's travel. */
    private fun updateSliverDrag(dy: Float) {
        val h = CaptureResultGeometry.clampPanelHeight(
            sliverHeightPx() + dy.toInt(), screenH, minFraction = 0f,
        )
            .coerceAtMost(dragCeilingPx)
            .coerceAtLeast(sliverHeightPx())
        setPanelHeight(h)
        if (h >= CaptureResultGeometry.minPanelHeight(screenH)) reFitText()
    }

    /** Release of a sliver drag. Past the threshold (the normal resize floor)
     *  the panel is committed exactly where the drag left it; under it, the
     *  sheet settles back into the sliver: pulled and let fall back, the user
     *  kept it parked, so the pref takes the wheel again. Either way the
     *  user's word, whatever the capture wrote during the drag. */
    private fun endSliverDrag() {
        if (dismissed) return
        if (panelHeightPx >= CaptureResultGeometry.minPanelHeight(screenH)) commitHeight() else park()
    }

    /** Dismiss everything from the sliver: the boxes start fading immediately
     *  (not after the slide), the sliver slides off, and [dismiss] records the
     *  on-screen exit preference because [sliverMode] is still set. */
    private fun dismissFromSliver() {
        hideChips()
        animateOutAndDismiss()
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
     *  through (drags, the settle's slide, the collapsed start, inset
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
        // The Anki pill goes with the sections, and on a park sooner: pinned to
        // the body's bottom, it would stay in full view while the slide takes
        // the sections away under the falling top edge, so a park fades it
        // over the whole slide ([parkFromPx]). Every other height change (a
        // drag, an expand) fades it with the sections through the band.
        // Parked, no touch reaches it (the root takes every touch on the
        // strip) and the controller skips it ([navHost]).
        val parkFade = parkFromPx?.let { from ->
            if (from <= sliver) 0f else ((h - sliver).toFloat() / (from - sliver)).coerceIn(0f, 1f)
        } ?: 1f
        ankiPill.alpha = ankiPillAlpha * minOf(f, parkFade)
        // Entering the collapse band: place the hint against the boxes while
        // it is still gone, so it fades in where it belongs instead of
        // sliding there.
        if (sliverHint.visibility == View.GONE && f < 1f) placeSliverHint()
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
        // The on-frame boxes draw over the strip only once the sheet has
        // fully parked (the collapse's last frame, a collapsed start, an
        // inset re-park) and drop back beneath it on the first frame it
        // grows — a tap-expand, the sliver drag, the stick — so the boxes
        // are never over content that is fading in.
        chipsOverSheet = f <= 0f
        syncChipsOrder()
    }

    /** The parked strip's hint for [hintTextShown]: the touch glyph with
     *  "Tap for more options", or the glyph alone where the boxes leave the
     *  text no room. */
    private fun updateSliverHint() {
        if (appliedHintTextShown == hintTextShown) return
        appliedHintTextShown = hintTextShown
        sliverHint.text =
            if (hintTextShown) ctx.getText(R.string.capture_sliver_expand_hint) else ""
        // The gap to the text goes with the text, so the glyph alone is
        // exactly the glyph and centres as one.
        sliverHint.compoundDrawablePadding = if (hintTextShown) dp(GLYPH_GAP_DP) else 0
    }

    /** Re-place the hint for the strip as it is once parked: left centred
     *  while the on-frame boxes stay clear of it, else centred in the
     *  widest stretch they leave uncovered, with its text or its glyph
     *  alone ([CaptureResultGeometry.placeParkedHint]). Runs after every
     *  chip layout, when the boxes hide and as the hint first shows, so it
     *  is right before it fades in and slides if the boxes change under
     *  it. Before [show] there is no geometry: the text is applied and
     *  that's all. */
    private fun placeSliverHint() {
        if (screenW <= 0) {
            updateSliverHint()
            return
        }
        val placement = CaptureResultGeometry.placeParkedHint(
            coveredSpans = coveredHintSpans(),
            screenWidthPx = screenW,
            fullRowWidthPx = fullHintRowWidthPx(),
            glyphWidthPx = dp(HINT_GLYPH_DP),
            edgePadPx = dp(SLIVER_HINT_SIDE_PAD_DP),
            clearancePx = dp(HINT_CLEARANCE_DP),
        )
        hintTextShown = placement.textFits
        updateSliverHint()
        slideHintTo(placement.centerX - screenW / 2f)
    }

    /** The full hint's width as it draws: the leading glyph, its gap and
     *  the text, the whole of it held to the hint's bound (show()'s
     *  maxWidth, which the glyph and gap count toward; the text trails off
     *  there) — measured off the paint, not the live view, so a placement
     *  pass never touches the text it is deciding about. */
    private fun fullHintRowWidthPx(): Int {
        val text = ctx.getString(R.string.capture_sliver_expand_hint)
        val textW = ceil(sliverHint.paint.measureText(text)).toInt()
        return (dp(HINT_GLYPH_DP) + dp(GLYPH_GAP_DP) + textW).coerceAtMost(sliverHint.maxWidth)
    }

    /** Horizontal extents (root px, half-open) of the on-frame boxes reaching
     *  into the strip the parked sheet shows — its top edge down to the
     *  nav-bar buffer — taken from the chips' DRAWN footprints, which is
     *  what covers the strip (a chip can grow past its OCR box). Empty while
     *  the boxes are hidden, and for hosts that paint boxes through their
     *  own presenter, whose geometry is not this window's. */
    private fun coveredHintSpans(): List<IntRange> {
        if (boxPresenter != null || !boxesShown) return emptyList()
        val stripTop = screenH - sliverHeightPx() + dp(HANDLE_HEIGHT_DP)
        val stripBottom = screenH - bottomInsetPx
        root.getLocationOnScreen(rootLoc)
        return layer.drawnBoxRects().mapNotNull { r ->
            r.offset(-rootLoc[0], -rootLoc[1])
            if (r.bottom <= stripTop || r.top >= stripBottom || r.right <= r.left) null
            else r.left until r.right
        }
    }

    /** Slide the hint to [tx] (translationX from its centred layout):
     *  animated while it is on screen, set outright while it is gone or
     *  faded out (and with animations off), so the next park finds it in
     *  place. */
    private fun slideHintTo(tx: Float) {
        hintSlideAnimator?.cancel()
        hintSlideAnimator = null
        if (sliverHint.translationX == tx) return
        if (sliverHint.visibility != View.VISIBLE || sliverHint.alpha <= 0f ||
            !ValueAnimator.areAnimatorsEnabled()
        ) {
            sliverHint.translationX = tx
            return
        }
        hintSlideAnimator = ValueAnimator.ofFloat(sliverHint.translationX, tx).apply {
            duration = SLIVER_FADE_MS
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { sliverHint.translationX = it.animatedValue as Float }
            start()
        }
    }

    /** The mode pill is up from the capture's first status. While a phase
     *  is still in flight ([statusInFlight]) it is the disc with a spinner,
     *  the one sign of work a parked sheet shows beside the edge glow; with
     *  a result that has boxes to paint (skeletons count: a mid-translation
     *  switch shows the same pulsing placeholders) it is the control, as
     *  the user left it, collapsed or expanded; with neither (no text, a
     *  failure, a result that came back without its boxes) it fades out.
     *  It offers the modes the host offers that the layer can paint, the
     *  persisted mode selected. It floats above the panel, so it is
     *  reachable while parked, which is the whole reason it exists.
     *  Idempotent: the pill rebuilds only on a change, so every refresh may
     *  call this. Not during the exit, which has faded it out already
     *  ([animateOutAndDismiss]): a state that lands under the slide must
     *  not bring it back. Never in a host with a control of its own
     *  ([hostOwnsModeControl]). */
    private fun updateModeControl() {
        if (animatingOut || hostOwnsModeControl) return
        val hint = SourceLanguageProfiles[prefs.sourceLangId].hintTextKind
        val offered = if (overlayData == null && !statusInFlight) {
            emptyList()
        } else {
            presentationPrefs.offeredOverlayModes.filter { boxPresenter != null || it in layer.supportedModes }
        }
        modePill.renderModes(
            offered, hint,
            selected = presentationPrefs.overlayMode,
            collapsed = prefs.captureOverlayPillCollapsed,
            loading = statusInFlight,
        )
    }

    // ── State rendering ──────────────────────────────────────────────────

    private fun setStatus(
        message: String,
        ocrProvenance: OcrProvenance? = null,
        screenshotPath: String? = null,
        /** This status is a phase still in flight, not a terminal outcome
         *  ([statusInFlight]). */
        loading: Boolean = false,
    ) {
        // A status takes over the panel (the scroll with the source text
        // hides below) — any word lens is now floating over vanished text
        // with a stale action context, and the text's word lookup has
        // nothing left to show (the next result runs its own).
        dismissWordLens()
        wordLookups.reset()
        askSession.bind(null)
        // And a service picked for the result that was on show has nothing
        // to land on: [lastResult] survives a status, so without this a pick
        // would come in over the status of a re-read begun meanwhile.
        endPick()
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
        syncAnkiPill()
        // A status means no shown result — whatever boxes the session produced
        // earlier (e.g. skeletons before a translation failure) are off the table.
        overlayData = null
        // The crossfade fades statusText to nothing while parked, and during
        // OCR there are no boxes yet either: the mode pill's spinner is the
        // sign of the work then. A terminal status takes the pill away: it
        // expands out of the park anyway, and a spinner beside "no text
        // found" would claim work that has stopped.
        statusInFlight = loading
        updateModeControl()
        // The proportional loading floor (20% of screen height) is SHORTER than
        // the status block itself on a landscape screen — "Recognizing text"
        // rendered clipped to invisibility in horizontal mode. What the status
        // measures is part of where the open sheet rests ([restingHeightPx]),
        // taken at once at rest; bindResult's own fit takes over from there.
        // Never a parked sheet's: a camera region re-run drives the loading
        // status through a parked sliver (boxes stay the presentation).
        settle(snap = true)
    }

    /** The panel height the CURRENT status text needs to render unclipped. */
    private fun statusFloorPx(): Int {
        statusText.measure(
            View.MeasureSpec.makeMeasureSpec(screenW, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        return statusText.measuredHeight + dp(HANDLE_HEIGHT_DP) + topInsetPx + bottomInsetPx
    }

    private fun bindResult(result: TranslationResult) {
        val b = binder ?: return
        // New SOURCE text replaces what an open word lens is anchored on —
        // dismiss it, or its lazily-read action context (sentence /
        // screenshot from [lastResult]) would cross captures on the next
        // chip tap. The Translating→Done promotion keeps the same source
        // text and deliberately keeps the lens: after this guard, the lazy
        // read can only ever see a same-source result, which is exactly
        // what lets a lens opened mid-translation pick up the finished
        // translation for its Anki context.
        if (sourceLens?.isShowing == true && lastResult?.originalText != result.originalText) {
            dismissWordLens()
        }
        // Another result on show than the one a picked service is
        // translating: that run is over.
        if (pickRun?.result !== result) endPick()
        lastResult = result
        // The Ask section's question is about this sentence: binding
        // another one ends the question in hand, the same one keeps it.
        askSession.bind(askSentence(result.originalText, result.langContext))
        nav?.clearCursor()   // fresh content: word indices + layout are stale
        statusText.visibility = View.GONE
        scroll.visibility = View.VISIBLE
        syncAnkiPill()
        // The loading arc ends here — at Translating, where the skeleton boxes
        // take over as the on-screen sign of work in flight, and the mode
        // pill's spinner gives way to the control.
        statusInFlight = false
        updateModeControl()
        // also paints furigana (bindResult → bindSource)
        b.bindResult(result, picking = pickRun?.result === result)
        wordRows?.applyWordsVisibility()
        b.tvOriginal.onTapAtOffset = { offset -> onSourceTapped(offset) }
        // The source's word lookup: its analysis gives the taps their spans
        // and the Words card asks it for rows. A re-bind of the text already
        // being looked up (the Translating→Done promotion, a deferred
        // translation landing) keeps the lookup it has; restarting would
        // drop the spans and flash the word list back to loading.
        if (!wordLookups.covers(result.originalText)) {
            wordLookups.start(result.originalText, ctx.applicationContext)
        }
        writeSentenceCache(result)
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
        val needed = deferredCompletionNeeded(
            sectionHidden = prefs.hideTranslationSection,
            parked = sliverMode,
            mode = presentationPrefs.overlayMode,
        )
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
                if (filled != null) paintBoxes(filled) else hideChips()
                updateModeControl()
            } finally {
                // Only the run that still owns the generation releases the
                // marker — a superseding launch already stamped its own.
                if (gen == deferredGeneration) deferredInFlight = null
            }
        }
    }

    /** Keep [LastSentenceCache] in step with the result on show, by the
     *  lookup's pairing rule ([WordLookupPipeline.writeSentenceCache]): its
     *  translation as soon as there is one, its words once their rows have
     *  settled, which happens only while the Words card asks for them. The
     *  lens's Anki card and open-detail tap read the cache, and fetch the
     *  words themselves when it has none, as they do on the results pages. */
    private fun writeSentenceCache(result: TranslationResult) = wordLookups.writeSentenceCache(
        result.originalText, result.translatedText, result.backendDisplayName,
    )

    /** A result is on show, not a status: [lastResult] survives a status,
     *  which hides the scroll. What the fit sizes the sheet to. */
    private val resultOnShow: Boolean
        get() = lastResult != null && scroll.visibility == View.VISIBLE

    /** The content's rule for the reading height: measure what the result on
     *  show needs at max text size (the same StaticLayout basis as the fit,
     *  so they agree) and rest the open sheet at it, floored at the minimum
     *  and capped at [autoMaxPx] (50% of the screen, or the height the user
     *  chose), the text scaling with the slide. Nothing while a status is on
     *  show: there is no text to fit, and the result it replaced must not
     *  size the sheet. Parked it moves nothing ([settle]) and still measures:
     *  the sliver drag's ceiling is the need, and the sheet comes out of the
     *  park to the reading height this keeps. */
    private fun autoSizeAndFit() {
        val b = binder ?: return
        if (!resultOnShow) return
        if (body.height <= 0 || sections.width <= 0) return
        val neededHeight = neededPanelHeight(b)
        maxNeededHeightPx = neededHeight.coerceAtLeast(CaptureResultGeometry.minPanelHeight(screenH))
        readingHeightPx = CaptureResultGeometry.autoPanelHeight(neededHeight, screenH, autoMaxPx)
        settle(textChanged = true)
    }

    /** Where the sheet rests: parked, the sliver; open, its reading height,
     *  raised to what a status on show measures. The floor only ever raises
     *  it, so a status in an open sheet keeps its height, and one landing as
     *  the sheet comes up (a tap's expand to a taller reading height) does
     *  not pull it down; and it is never a parked sheet's. */
    private fun restingHeightPx(): Int = when {
        sliverMode -> sliverHeightPx()
        statusText.visibility == View.VISIBLE -> maxOf(readingHeightPx, statusFloorPx())
        else -> readingHeightPx
    }

    /** The one mover of the sheet's height: to where it rests, from where it
     *  is. While the user holds the height it moves nothing (a change of the
     *  text still fits it to the height the sheet has, which a result
     *  landing mid-drag needs at once) and runs once they let go
     *  ([endHold]). [snap] lands a sheet at rest at once rather than sliding
     *  it there (a status that would draw clipped meanwhile, the sliver
     *  following the system bar); a slide under way is retargeted either
     *  way. [textChanged]: the content changed, so a slide that carries the
     *  text starts again with its new sizes; any other slide already on its
     *  way to where the sheet rests runs on. */
    private fun settle(snap: Boolean = false, textChanged: Boolean = false) {
        if (dismissed || animatingOut) return
        if (heightHeld) {
            settleOwed = true
            if (textChanged) fitTextToHeight()
            return
        }
        val target = restingHeightPx()
        val running = heightAnimator?.isRunning == true
        if (running && target == settleTargetPx && !(textChanged && textRidesTo(target))) return
        heightAnimator?.cancel()
        if (target == panelHeightPx || (snap && !running)) {
            if (target != panelHeightPx) setPanelHeight(target)
            fitTextToHeight()
            return
        }
        slideTo(target)
    }

    /** A slide to [target] carries the result's text: a result on show, laid
     *  out, landing at a reading height. */
    private fun textRidesTo(target: Int): Boolean =
        binder != null && resultOnShow && sections.width > 0 &&
            target >= CaptureResultGeometry.minPanelHeight(screenH)

    /** Slide the height from where it is to [target]. Between reading heights
     *  the text scales with it, interpolated (as a float, so it scales
     *  smoothly instead of stepping) between its fitted sizes at both ends;
     *  out of the park it takes the size it lands at before it shows; into
     *  the park, or under a status, it stays as it is: the sections are
     *  faded for every frame where a size would matter, and fitting to
     *  sliver heights computes degenerate sizes. */
    private fun slideTo(target: Int) {
        val b = binder
        val start = panelHeightPx
        val withText = b != null && textRidesTo(target)
        val scales = withText && start >= CaptureResultGeometry.minPanelHeight(screenH)
        var sizeAt: ((Float) -> Unit)? = null
        if (b != null && withText) {
            val (srcEnd, tgtEnd) = fitSizes(b, contentHeight(target))
            if (scales) {
                val (srcStart, tgtStart) = fitSizes(b, contentHeight(start))
                applyCardFill(b, contentHeight(start))
                b.setSizes(srcStart, tgtStart)
                sizeAt = { f ->
                    applyCardFill(b, contentHeight(panelHeightPx))
                    b.setSizes(srcStart + (srcEnd - srcStart) * f, tgtStart + (tgtEnd - tgtStart) * f)
                }
            } else {
                applyCardFill(b, contentHeight(target))
                b.setSizes(srcEnd, tgtEnd)
            }
        }
        settleTargetPx = target
        // A park records where it set out from, for the Anki pill's fade. Set
        // after the cancel in [settle], whose end listener clears the last
        // slide's.
        parkFromPx = start.takeIf { target == sliverHeightPx() && it > target }
        heightAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (scales) HEIGHT_DURATION_MS else SLIVER_DURATION_MS
            interpolator = if (scales) DecelerateInterpolator() else AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                if (dismissed) {
                    anim.cancel()
                    return@addUpdateListener
                }
                val f = anim.animatedValue as Float
                setPanelHeight((start + (target - start) * f).toInt())
                sizeAt?.invoke(f)
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }
                override fun onAnimationEnd(animation: Animator) {
                    // The slide is over, run out or cancelled: the pill
                    // follows the sections again.
                    parkFromPx = null
                    if (!cancelled && !dismissed) updateModeControl()
                }
            })
            start()
        }
    }

    /** Fit the result's text to the height the sheet has, a reading height
     *  (inside the collapse band the sections are faded and a fit there
     *  degenerate). */
    private fun fitTextToHeight() {
        if (resultOnShow && panelHeightPx >= CaptureResultGeometry.minPanelHeight(screenH)) reFitText()
    }

    /** [heightHeld] may have changed from [wasHeld]: a touch began or ended,
     *  or the stick began or committed a resize. */
    private fun holdChanged(wasHeld: Boolean) {
        val held = heightHeld
        if (held == wasHeld) return
        if (held) beginHold() else endHold()
    }

    /** The user takes hold of the height: a slide under way stops under
     *  them, to settle again when they let go. */
    private fun beginHold() {
        val running = heightAnimator?.takeIf { it.isRunning } ?: return
        running.cancel()
        settleOwed = true
    }

    /** The user let go of the height (a finger's UP or CANCEL, after the
     *  gesture's own end has written where they left the sheet; a stick
     *  resize's commit, likewise): the sheet settles once, from the state of
     *  this moment, if anything it rests by changed meanwhile or a slide was
     *  stopped. */
    private fun endHold() {
        if (!settleOwed) return
        settleOwed = false
        settle()
    }

    /** The user let the sheet go at a height of their choosing (a drag or the
     *  stick, from the sliver or not): it lives there now, out of the park if
     *  the capture parked it meanwhile. It stands down the capture's re-park,
     *  supersedes a loading peek and ends None's hold ([noneHoldsOpen]); the
     *  height becomes the auto-size
     *  ceiling ([committedCeiling]) and the reading height, and the content's
     *  fit then applies, which leaves the sheet where it is unless the
     *  content needs less (or a status more) than that. */
    private fun commitHeight() {
        leaveSliver()
        userStatedPosture = true
        loadingPeekExpand = false
        noneHoldsOpen = false
        autoMaxPx = committedCeiling()
        readingHeightPx = panelHeightPx
        autoSizeAndFit()
        settle()
        updateModeControl()
    }

    /** Panel height that shows all content at max text size. Caller must have
     *  confirmed the content is laid out. Summed from the content's PARTS and
     *  never read from its laid-out height: the fill ([applyCardFill])
     *  stretches the cards to the panel, so the layout's own height says how
     *  tall the panel is, not what the content needs. */
    private fun neededPanelHeight(b: TranslationSectionBinder): Int =
        sections.paddingTop + topBlockNeed(b) + belowNeed() + cardBufferPx() +
            dp(HANDLE_HEIGHT_DP) + topInsetPx + bottomInsetPx

    /** The space the sheet keeps below its last counted card, down to the
     *  panel's bottom. The sections' bottom padding is larger (the Anki
     *  pill's clearance, [buildContent]), and what it adds past this sits
     *  below the fold, like the header rows of hidden sections: scroll room
     *  at the end, no height and no smaller cards. */
    private fun cardBufferPx(): Int =
        dp(if (paired) SIDE_BY_SIDE_BOTTOM_BUFFER_DP else STACKED_BOTTOM_BUFFER_DP)

    /** The sections beneath Original and Translation, top to bottom, each
     *  as its header and its card. A section this sheet was not given is a
     *  pair of nulls. */
    private fun sectionsBelow(): List<Pair<View?, View?>> = listOf(
        sections.findViewById<View>(R.id.askHeaderRow) to sections.findViewById<View>(R.id.cardAsk),
        sections.findViewById<View>(R.id.wordsHeaderRow) to sections.findViewById<View>(R.id.cardWords),
    )

    /** Whether any section beneath the top two shows its card. */
    private fun cardShownBelow(): Boolean =
        sectionsBelow().any { (_, card) -> card != null && card.visibility != View.GONE }

    /** What the sections beneath the top two add to the height the panel
     *  sizes itself to: every header and card down to the LAST card that is
     *  shown. A hidden section is its header row alone, and header rows with
     *  no card after them are not counted: they sit just past the fold, so
     *  a sheet whose lower sections are all hidden is as tall as its two
     *  texts need and no taller. The cards' heights are their laid-out ones
     *  (nothing here stretches them). */
    private fun belowNeed(): Int {
        var counted = 0
        var headersSinceLastCard = 0
        for ((header, card) in sectionsBelow()) {
            if (header == null || header.visibility == View.GONE) continue
            headersSinceLastCard += header.height
            if (card != null && card.visibility != View.GONE) {
                counted += headersSinceLastCard + card.height
                headersSinceLastCard = 0
            }
        }
        return counted
    }

    /** A section's card with its text at max size: the card frame's inset,
     *  the in-card overhead (padding + the note row, read live) and the
     *  text. A section hidden by its eye has no card, so hiding it actually
     *  shrinks the panel. */
    private fun sourceCardNeed(b: TranslationSectionBinder): Int =
        if (!b.isOriginalShown) 0
        else b.sourceCardInset() + b.sourceContentOverhead() + b.sourceTextHeightAtMax()

    private fun targetCardNeed(b: TranslationSectionBinder): Int =
        if (!b.isTranslationShown) 0
        else b.targetCardInset() + b.targetContentOverhead() + b.targetTextHeightAtMax()

    /** The height both headers take: one row when paired (a hidden section
     *  keeps its header cell in it), one row each when stacked. */
    private fun headersHeight(b: TranslationSectionBinder): Int =
        if (paired) maxOf(b.sourceHeaderHeight(), b.targetHeaderHeight())
        else b.sourceHeaderHeight() + b.targetHeaderHeight()

    /** The Original and Translation sections at max text size: their headers
     *  over the taller card when paired, over both cards when stacked. */
    private fun topBlockNeed(b: TranslationSectionBinder): Int =
        headersHeight(b) +
            if (paired) maxOf(sourceCardNeed(b), targetCardNeed(b))
            else sourceCardNeed(b) + targetCardNeed(b)

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
        if (body.height <= 0 || sections.width <= 0) return
        maxNeededHeightPx = neededPanelHeight(b)
            .coerceAtLeast(CaptureResultGeometry.minPanelHeight(screenH))
        reFitText()
    }

    /** The height each of the two cards is given in a body [bodyH] tall, as
     *  (source, target), so the two sections fill it exactly: no gap below,
     *  and each text grows/shrinks to fill its card. Paired, each card takes
     *  the body beneath the header row. Stacked, the two split what the
     *  headers leave in proportion to each section's content at max size; a
     *  hidden section claims none of it, so the visible one fills it. */
    private fun cardHeights(b: TranslationSectionBinder, bodyH: Int): Pair<Int, Int> {
        val available =
            (bodyH - sections.paddingTop - cardBufferPx() - headersHeight(b)).coerceAtLeast(0)
        if (paired) return Pair(available, available)
        val srcRef = sourceCardNeed(b)
        val tgtRef = targetCardNeed(b)
        val total = (srcRef + tgtRef).coerceAtLeast(1)
        return Pair(available * srcRef / total, available * tgtRef / total)
    }

    /** Fill each card to a MINIMUM height ([cardHeights]) so its background is
     *  sized by the view, not the text — yet a card whose content runs slightly
     *  tall grows + scrolls instead of clipping. Only while nothing beneath
     *  the two shows a card. */
    private fun applyCardFill(b: TranslationSectionBinder, bodyH: Int) {
        // With a card shown beneath them the two texts' cards keep their own
        // height and that section follows directly; stretching them to the
        // body would push it under the fold of every sheet. The floor is
        // written back to 0, not left: a card keeps the last minimum it was
        // given.
        if (cardShownBelow()) {
            b.setCardMinHeights(0, 0)
            return
        }
        val (src, tgt) = cardHeights(b, bodyH)
        b.setCardMinHeights(src, tgt)
    }

    /** Fitted (source, target) text sizes for a body height — each text fills its
     *  card's inner height (the card the fill above sized). */
    private fun fitSizes(b: TranslationSectionBinder, bodyH: Int): Pair<Float, Float> {
        val (srcCard, tgtCard) = cardHeights(b, bodyH)
        return Pair(
            b.sourceSizeFor((srcCard - b.sourceCardInset() - b.sourceContentOverhead()).coerceAtLeast(1)),
            b.targetSizeFor((tgtCard - b.targetCardInset() - b.targetContentOverhead()).coerceAtLeast(1)),
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
        // scroll GONE and the sections unmeasured (a grabber drag is live the
        // whole time OCR runs). There is nothing to fit: the headers have no
        // height yet, so every card height would be computed against a body
        // with no headers in it. Same laid-out guard autoSizeAndFit uses.
        if (sections.width <= 0) return
        val bodyH = contentHeight(panelHeightPx)
        applyCardFill(b, bodyH)
        val (src, tgt) = fitSizes(b, bodyH)
        b.setSizes(src, tgt)
    }

    /** Resolve the tapped word and show a display+speak lens over the game,
     *  anchored on the tapped line (the Anki button and the open-detail follow
     *  the resolved entry). [fromController] (the cursor's A press)
     *  pre-selects the word's row so the next A opens the detail screen; a
     *  touch tap leaves the lens unselected until its first controller
     *  input. */
    private fun onSourceTapped(offset: Int, fromController: Boolean = false) {
        if (!wordLensEnabled) return
        sourceLens?.onTapAtOffset(offset, fromController)
    }

    /** Open-detail tap + a row's Anki button — same actions as the drag flow. The
     *  resolved unit is this tap's word/entry; sentence + screenshot come
     *  from the current capture result, read lazily at chip-tap time (the
     *  bind guard in [bindResult] keeps that read same-source). Also the
     *  shoulder buttons' hook, over the game ([stepOverlayMode]). */
    private fun wireLensActions(lens: MagnifierLens, resolvedAt: SourceWordLookup.ResolvedAt) {
        val resolved = resolvedAt.word
        val secondaries = resolvedAt.secondaries()
        // L1 / R1 on the lens while it holds the controller step the mode
        // pill as on the sheet, the lens closing first. Over the game only:
        // an in-activity host has no pill the shoulder buttons drive.
        if (!hostOwnsModeControl && !wordLensInActivity) {
            lens.onShoulderKey = { toRight -> stepOverlayMode(toRight) }
        }
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
            onLaunchedActivity = ::onWordPageLaunched,
            tagDetailReturn = !wordLensInActivity,
            showAnkiNotInstalled = if (wordLensInActivity) showAnkiNotInstalled else null,
            route = wordPageRoute(),
            // Secondary-section drill-in (containing phrase, member words
            // or alternative entries): same open-sentence route, with the
            // secondary unit as the word context.
            currentSecondary = if (secondaries.isEmpty()) null else { i ->
                secondaries.getOrNull(i)?.let { s ->
                    LensActionContext(
                        s.word,
                        s.reading,
                        s.surface,
                        s.entry,
                        lastResult?.originalText,
                        lastResult?.screenshotPath,
                        audioAnchorMs = lastResult?.createdAtMs?.takeIf { it > 0 },
                        entries = s.entries,
                        lookupForm = s.lookupForm,
                        lookupReading = s.lookupReading,
                    )
                }
            },
        ) {
            LensActionContext(
                resolved.word,
                resolved.reading,
                resolved.surface,
                resolved.entry,
                lastResult?.originalText,
                lastResult?.screenshotPath,
                audioAnchorMs = lastResult?.createdAtMs?.takeIf { it > 0 },
                entries = resolved.entries,
                lookupForm = resolved.lookupForm,
                lookupReading = resolved.lookupReading,
            )
        }
    }

    /** What the sheet does once a word's page has been launched from it,
     *  by the lens or by a Words row. */
    private fun onWordPageLaunched(kind: SourceLensActions.LaunchKind) {
        when (kind) {
            SourceLensActions.LaunchKind.Anki ->
                if (dismissOnActivityLaunch) dismissForPage()
            SourceLensActions.LaunchKind.Detail -> {
                val r = lastResult
                val nav = onNavigateToDetail
                if (r != null && nav != null) nav(r, finishedAnswer())
                else if (dismissOnActivityLaunch) dismissForPage()
            }
        }
    }

    /** How a word's page is reached from this sheet: a fresh workspace over
     *  the game, or never for an in-activity host, whose pages are
     *  Activity launches. */
    private fun wordPageRoute(): WorkspaceRoute =
        if (wordLensInActivity) WorkspaceRoute.None else WorkspaceRoute.OpenNew(displayId)

    /** A Words row's tap: open that word's detail, by the path and with the
     *  sheet's same reaction as the lens's open tap ([SentenceDetailOpener],
     *  [onWordPageLaunched]). [explanation] is the answer that stood in the
     *  row, for the word page, which shows it and starts its Anki card's
     *  note with it ([LensActionContext.explanation]). */
    private fun openWordRow(row: RowState, explanation: Explanation?) {
        val result = lastResult ?: return
        dismissWordLens()
        SentenceDetailOpener(
            ctx.applicationContext, displayId,
            route = wordPageRoute(),
            tagDetailReturn = !wordLensInActivity,
            onLaunched = { onWordPageLaunched(SourceLensActions.LaunchKind.Detail) },
        ).open(
            LensActionContext(
                word = row.displayWord,
                reading = row.reading.ifEmpty { null },
                surface = row.surface.ifEmpty { null },
                entry = null,
                sentence = result.originalText,
                screenshotPath = result.screenshotPath,
                audioAnchorMs = result.createdAtMs.takeIf { it > 0 },
                lookupForm = row.lookupForm,
                lookupReading = row.lookupReading,
                explanation = explanation,
            ),
        )
    }

    private fun dismissWordLens() {
        sourceLens?.dismiss()
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
            onReOcr = { reOcr(prov, path, deviceOnly = true) },
            onDownload = { backend -> launchOcrSettings(backend, prov.sourceLangId); dismissForPage() },
            cloud = OcrPicker.CloudChoice(
                onReOcr = { reOcr(prov, path, deviceOnly = false) },
                // The cloud page, the way the download page opens: on the
                // foreground display, this sheet gone since the app comes up.
                onOpenPage = { launchOnForegroundDisplay(Intent(ctx.applicationContext, PtCloudSettingsActivity::class.java)); dismissForPage() },
            ),
        ).showAsOverlay()
    }

    /** Re-run the capture pipeline on the pinned screenshot with the just-made
     *  pick, driving this sheet through the normal loading stages (and re-emitting
     *  the no-text status + gear if it still finds nothing) via [observe].
     *  [deviceOnly] is the pick's statement to the reader router: true for a
     *  device engine, false for PlayTranslate Cloud's entry
     *  ([CaptureService.processScreenshot]). */
    private fun reOcr(prov: OcrProvenance, path: String, deviceOnly: Boolean) {
        val svc = CaptureService.instance ?: return
        scope.launch {
            val bmp = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(path) } ?: return@launch
            if (!dismissed) observe(svc.processScreenshot(
                com.playtranslate.capture.CapturedFrame(
                    bmp, includesSystemUi = prov.frameIncludesSystemUi ?: true,
                    includesOwnOverlays = prov.frameIncludesOwnOverlays ?: false,
                    geometry = prov.frameGeometry,
                ),
                prov.displayId, prov.region, prov.sourceLangId,
                deviceOnly = deviceOnly,
            ))
        }
    }

    /** Deep-link to the OCR settings screen to download [backend]'s pack, on the
     *  foreground display (mirrors [openSentenceAnkiReview]). The caller dismisses
     *  this sheet since the app comes to the foreground. */
    private fun launchOcrSettings(backend: OcrBackend, id: SourceLangId) {
        launchOnForegroundDisplay(
            CaptureOverlaySettingsActivity.downloadIntent(ctx.applicationContext, id, backend.selectionToken),
        )
    }

    /** Starts [intent] as a new task on the foreground display (else this
     *  sheet's): the one way an activity opens from this over-game sheet, for
     *  the OCR download page and the PlayTranslate Cloud page alike. */
    private fun launchOnForegroundDisplay(intent: Intent) = launchOnForegroundDisplay(ctx, intent, displayId)

    /** Change the source ([isSource]) or target language. Shared by the language section
     *  headers and the tappable language name in the no-text status. The bound result is
     *  stale in the new language either way, so this sheet dismisses first (no stash /
     *  re-show — the user re-captures on return, exactly the Activity path's semantics).
     *  Single-screen: the picker opens as a floating-workspace page over the game.
     *  Fallback (dual-screen, no controller instance): null the stale Settings delegate
     *  and launch [LanguageSetupActivity] on the foreground display. */
    private fun changeLanguage(isSource: Boolean) {
        chooseLanguage?.let { it(isSource); return }
        // Read before dismiss(): the picker workspace frosts over the same
        // capture this sheet was frosted with.
        val frostPath = lastResult?.screenshotPath
        dismissForPage()
        val opened = CaptureBackendResolver.activeOverlayUi?.openWorkspace(displayId, frostPath) {
            if (isSource) SourceListPage() else TargetListPage()
        } == true
        if (opened) return
        LanguageSetupActivity.selectionDelegate = null
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
        if (result.originalText.isBlank()) return
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
        // Single-screen with the permission held: the editor opens as a
        // floating-workspace page over the game (the sheet stashes-for-reshow,
        // so a USER dismissal of the workspace, or a completed save, brings it
        // back — the detail round-trip's semantics). The Activity fallback
        // tears the sheet down under window hosting — it'd otherwise sit over
        // the review/trampoline; in-app hosting keeps it (the host activity
        // goes behind the review and restores as left).
        val inWorkspace = presentSentenceAnkiReview(
            ctx, displayId, WorkspaceRoute.OpenNew(displayId), sentenceAnkiArgs(result),
        )
        if (inWorkspace) {
            val nav = onNavigateToDetail
            if (nav != null) nav(result, finishedAnswer()) else if (dismissOnActivityLaunch) dismissForPage()
        } else if (dismissOnActivityLaunch) {
            dismissForPage()
        }
    }

    /** The bound result as a sentence-card payload, with the words as ONE
     *  locked snapshot (see [SentenceAnkiArgs]). */
    private fun sentenceAnkiArgs(result: TranslationResult) = SentenceAnkiArgs(
        original = result.originalText,
        translation = result.translatedText,
        screenshotPath = result.screenshotPath,
        sourceLangId = prefs.sourceLangId,
        pendingTranslation = result.pendingTranslation,
        audioAnchorMs = result.createdAtMs.takeIf { it > 0 },
        words = LastSentenceCache.snapshotFor(result.originalText),
        notes = sentenceCardNote(finishedAnswer()?.answer, wordRows?.explainedWords().orEmpty()),
    )

    // Long-press = headless one-tap send of the captured sentence. The send
    // runs on the process-lived scope (launchSentenceOneTap), which is what
    // lets the gesture ALSO close the sheet (see the exit at the end): the
    // point of a one-tap is not having to look at the sheet again, and over
    // the game it is a guest window sitting on the user's screen while a
    // send they can't influence runs headless.
    private fun oneTapSentenceFromOverlay() {
        val result = lastResult ?: return
        if (result.originalText.isBlank()) return
        val app = ctx.applicationContext
        val ankiManager = AnkiManager(app)
        if (!ankiManager.isAnkiDroidInstalled() || !ankiManager.hasPermission() || prefs.ankiDeckId < 0L) {
            // No headless path available → fall back to the review.
            openSentenceAnkiReview()
            return
        }
        launchSentenceOneTap(app, sentenceAnkiArgs(result)) {
            // Reopening the review is the mapping recovery — but only while
            // the panel is still up. Once it is going away, stealing the
            // game's screen with an activity the user didn't ask for is worse
            // than the dispatcher's explanatory NeedsMapping toast (already
            // shown); match the fragment paths' degraded contract instead.
            // [animatingOut] counts as gone: the exit slide runs for
            // EXIT_DURATION_MS before [dismissed] flips, and a send that
            // resolves inside that window is the SAME situation — for the
            // gesture below (which always starts the exit before the send can
            // finish) it is the only reading that makes the outcome
            // deterministic rather than a race with the slide.
            if (!dismissed && !animatingOut) openSentenceAnkiReview()
        }
        // The gesture's own exit: the card is on its way, so get off the
        // user's screen. Gated on [dismissOnGesture] rather than run
        // unconditionally — a host that refuses gesture dismissal refuses it
        // because dismissing costs something it can't rebuild (the camera /
        // import review would discard its frozen snapshot), and a headless
        // send is no reason to spend that. Animated, like every other
        // gesture dismissal of this sheet.
        if (dismissOnGesture) animateOutAndDismiss()
    }

    /** Edit the source in place: flip the panel window focusable, show an inline
     *  EditText over the game, and bring the IME up — no app switch. (IME over an
     *  overlay window is OEM-variable; this path needs on-device validation.) */
    private fun startInPlaceEdit() {
        if (editContainer.visibility == View.VISIBLE) return
        val current = lastResult?.originalText ?: binder?.displayedSourceText() ?: return
        dismissWordLens()
        // The editor covers the sections the popovers act on.
        popovers.dismiss()
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
        returnFocusToRoot()
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
        // Nor may a service picked for the old source land its translation
        // of it over the edit's.
        endPick()
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
            // Nor is it the capture's groups: the edited text is translated,
            // and translated again, as one text.
            groupTexts = null,
            // The edit re-translates the NEW source itself (below) — a surviving
            // pending would let a later reveal clobber it with the OLD source's
            // translation.
            pendingTranslation = null,
        )
        lastResult = edited
        // The edited text is another sentence: a question about the old one
        // ends here, not when the re-translation lands.
        askSession.bind(askSentence(edited.originalText, edited.langContext))
        // The capture's per-group boxes translate the OLD source — the edit
        // orphans them, so drop the data AND any boxes already painted from it.
        overlayData = null
        hideChips()
        updateModeControl()
        val gen = ++editGeneration
        b.bindSource(edited.segments)   // sets text + paints furigana
        b.setTargetTranslatingPlaceholder()
        wordLookups.start(newText, ctx.applicationContext)
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
        returnFocusToRoot()
        applyWindowFocusPolicy()
    }

    /** Which of the sheet's two text fields holds the keyboard. */
    private enum class TypingIn { NOTHING, SOURCE_EDIT, ASK_QUESTION }

    /** Where the user is typing, derived from the two fields themselves:
     *  the in-place source edit while its editor is up, the Ask section's
     *  question while that field has focus. Everything that behaves
     *  differently while a keyboard is up reads this one answer: the
     *  window's focus policy, the controller's stand-down, the resize floor
     *  and the park (a sliver would strand the keyboard), the swipe-away,
     *  the outside-tap router and B. The question's typing ends with its
     *  focus: at a send, at B, when the field goes out of sight, and when
     *  the keyboard is closed under it (the insets listener in [show]). */
    private val typingIn: TypingIn
        get() = when {
            editContainer.visibility == View.VISIBLE -> TypingIn.SOURCE_EDIT
            askBinder?.fieldHasFocus == true -> TypingIn.ASK_QUESTION
            else -> TypingIn.NOTHING
        }

    /** Single owner of the sheet window's focus/IME flags. Focus is wanted
     *  while a field is being typed in (for the IME) OR while controller nav
     *  is live (for keys + stick motion); the IME only ever for a field:
     *  this is what keeps [commitEdit] from dropping controller focus. */
    private fun applyWindowFocusPolicy() {
        val typing = typingIn != TypingIn.NOTHING
        sheetHost.setFocusPolicy(
            root, focusable = typing || nav != null || holdsKeysForDefinitions, wantsIme = typing,
        )
        // Every change of [typingIn] runs this, so the pill, which turns on
        // it too, is re-read here.
        syncAnkiPill()
    }

    /** The floating Anki pill shows while the results do (a status hides
     *  the scroll, and the pill with it) and while nothing is being typed:
     *  the source editor covers the sections, and the pill's elevation would
     *  draw it over the editor and its Done button and take their taps; the
     *  Ask question's field can sit under it. */
    private fun syncAnkiPill() {
        ankiPill.isVisible = scroll.visibility == View.VISIBLE && typingIn == TypingIn.NOTHING
    }

    /** The Ask section's question field took or lost focus: give the window
     *  the keyboard, or take it back. A field in this window cannot raise
     *  the keyboard by itself: the window is not focusable until the policy
     *  says so, and that lands asynchronously, so the show is posted behind
     *  it (as [startInPlaceEdit] does). */
    private fun onAskFieldFocusChanged(focused: Boolean) {
        applyWindowFocusPolicy()
        if (focused) {
            nav?.clearCursor()   // the keys are the field's now
            root.post {
                val field = root.findFocus() ?: return@post
                if (typingIn != TypingIn.ASK_QUESTION) return@post
                ctx.getSystemService(InputMethodManager::class.java)
                    ?.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
            }
        } else {
            returnFocusToRoot()
        }
    }

    /** A field is done: view focus goes back to the root, where a
     *  controller's keys are read. Only with a controller, which is when the
     *  root can hold it ([show]). A root that cannot passes the request down
     *  to the first view that can take focus from a touch, and that is the
     *  Ask question's field: typing would begin there, keyboard and all,
     *  the moment another field was left, or begin again the moment it
     *  ended. Without a controller a field that is done simply has no
     *  focus (it lost it by its own clearFocus, or by going out of sight). */
    private fun returnFocusToRoot() {
        if (nav != null) root.requestFocus()
    }

    /** The user reached for an Ask question with no LLM set up: say so, on
     *  this sheet's own alert surface, with the way to Translation services
     *  (the app's page takes the screen, so an over-game sheet goes, like
     *  its other pages; an in-activity host's stays behind it). */
    private fun showAskNeedsLlm() {
        showAlertOn(alertTarget) { askNeedsLlm(ctx) { openTranslationServices() } }
    }

    /** Translation services, from this sheet: the app's page takes the
     *  screen, so an over-game sheet goes, like its other pages; an
     *  in-activity host's stays behind it. */
    private fun openTranslationServices() {
        launchOnForegroundDisplay(Intent(ctx.applicationContext, TranslationServicesActivity::class.java))
        if (dismissOnActivityLaunch) dismissForPage()
    }

    // ── A service picked for the result on show ──────────────────────────

    /** The translation service picker for [result] ([TranslationPicker]),
     *  on this sheet's own alert surface. A pick runs that result, and only
     *  it, through the service ([retranslateWith]). */
    private fun showTranslationPicker(result: TranslationResult) {
        showAlertOn(alertTarget) {
            TranslationPicker.populate(
                this, ctx, result.langContext, result.backendDisplayName,
                onPick = { backend -> retranslateWith(backend, result) },
                onOpenServices = { openTranslationServices() },
            )
        }
    }

    /**
     * Run [on], the result the picker was opened for, through [backend],
     * the service the user picked in it ([ChosenServiceTranslation]): the
     * page's rule ([TranslationResultViewModel.retranslateWith]) in this
     * sheet's own funnel. A no-op when [on] is no longer on show.
     *
     * [lastResult] is not touched while it runs: the translation stays on
     * show, dimmed, under a "Translating…" credit
     * ([TranslationSectionBinder.bindTargetReady]). It lands in one bind:
     * the translation, the service's name as the credit, no note, and the
     * capture's boxes filled again group by group and swapped in place,
     * those that are up ([fillBoxes]; with no boxes to refill, a sheet
     * re-shown from its result alone, they stay as they are). A failure
     * leaves what is on show and says so in an alert.
     *
     * Whatever takes the result's place meanwhile ends the run ([endPick]),
     * so it lands only while it is still the sheet's.
     */
    private fun retranslateWith(backend: TranslationBackend, on: TranslationResult) {
        if (dismissed || lastResult !== on || !resultOnShow) return
        val b = binder ?: return
        endPick()
        val run = PickRun(on, backend.displayName)
        pickRun = run
        b.bindTargetReady(on, picking = true)
        pickJob = scope.launch {
            val outcome = chosenTranslation(ctx.applicationContext, backend, on)
            // An ended run was cancelled with its job; this is for a
            // translation that came back all the same.
            if (dismissed || pickRun !== run) return@launch
            pickRun = null
            pickJob = null
            when (outcome) {
                is ChosenServiceTranslation.Outcome.Translated -> {
                    // Fill the boxes BEFORE the rebind, as the deferred
                    // completion does, so the mode control reads them.
                    val refilled = refillOneShotOverlayData(overlayData, outcome.perUnit)
                    if (refilled != null) overlayData = refilled
                    bindResult(
                        on.copy(
                            translatedText = outcome.text,
                            note = null,
                            backendDisplayName = outcome.backendDisplayName,
                            // A new translation derived from this result:
                            // nothing put off is left to land over it.
                            pendingTranslation = null,
                        ),
                    )
                    if (refilled != null) fillBoxes(refilled)
                    updateModeControl()
                }
                is ChosenServiceTranslation.Outcome.Failed -> {
                    b.bindTargetReady(on, picking = false)
                    showAlertOn(alertTarget) { ChosenServiceTranslation.failureAlert(this, ctx, outcome) }
                }
            }
        }
    }

    /** End a picked service's run, if one is going: what it was translating
     *  is no longer what the sheet shows (another result, a status, an edit
     *  of the source). Its translation is dropped; the bind that follows
     *  draws the card afresh. */
    private fun endPick() {
        pickJob?.cancel()
        pickJob = null
        pickRun = null
    }

    /** The Ask section's finished answer about the result on show, for the
     *  hand-off to a page ([onNavigateToDetail]) and a card's note; null
     *  with none, or with a question still in flight. A shelved answer is
     *  one too: out of sight, it still stands. */
    private fun finishedAnswer(): CarriedAnswer? = askSession.carried()

    /** The sheet is in Definitions, so its window holds key focus, Back
     *  closing a card and then the sheet without a controller and the D-pad
     *  driving the words while parked. Read from the sheet's own mode, not
     *  what the layer last painted, so the keys are the sheet's from the
     *  first frame (the read still running) and go back to the game the
     *  moment another mode is picked, with or without data. Never in the
     *  camera and import hosts, which draw Definitions themselves, for
     *  touch only: their words have no cursor for the D-pad to drive. */
    private val holdsKeysForDefinitions: Boolean
        get() = boxPresenter == null && presentationPrefs.overlayMode == OverlayMode.DEFINITIONS

    /** The controller's B / system back, in sheet-modal precedence order —
     *  the mirror of [CaptureResultRoot.dispatchTouchEvent]'s ladder. The
     *  lens and Definitions card branches are normally unreachable (an
     *  interactive lens window sits above us and holds focus, handling B
     *  itself); they cover the lens's no-controller non-focusable mode. */
    private fun onControllerBack() {
        when {
            popovers.isShowing -> popovers.dismiss()
            typingIn == TypingIn.SOURCE_EDIT -> cancelEdit()
            // Out of the question field, the keyboard going with its focus.
            typingIn == TypingIn.ASK_QUESTION -> askBinder?.clearFieldFocus()
            sourceLens?.isShowing == true -> dismissWordLens()
            // A Definitions card is the lens rung's twin over the game.
            layer.hasCard -> layer.closeCard()
            // One B, ringed or not: the sliver cursor is not a rung of this
            // ladder (see CaptureSheetControllerNav.handleKey).
            sliverMode -> dismissFromSliver()
            else -> animateOutAndDismiss()
        }
    }

    /** The sheet's side of the controller-navigation seam ([nav] drives it). */
    private val navHost = object : CaptureSheetNavHost {
        override val isEditing: Boolean get() = typingIn != TypingIn.NOTHING
        override val isPopoverOpen: Boolean get() = popovers.isShowing
        override fun popoverNavActions(): List<NavAction>? = popovers.navActions()
        override val inSliver: Boolean get() = sliverMode

        override fun onControllerBack() = this@CaptureResultOverlay.onControllerBack()
        // The controller's mirror of the sliver TAP — a user gesture, so it
        // takes the same posture-stating path (not the bare expand, which is
        // also the automatic boxes-refused rescue).
        override fun expandFromSliver() = userExpandFromSliver()

        override fun navActions(): List<NavAction> {
            // In visual order, top to bottom: the two texts' headers, then
            // the Ask section's header and controls, then the Words header
            // and its rows' targets, then the Anki pill at the bottom. A
            // hidden section's restore eye is its header's own (the header
            // stays), so each binder's list holds it.
            return binder?.navigableActions().orEmpty() +
                askBinder?.navigableActions().orEmpty() +
                wordRows?.navigableActions().orEmpty() +
                ankiPillNavActions()
        }

        /** The Anki pill while it shows on the expanded sheet. A is a hold
         *  on it, as a touch is: held, it sends the card at once. It fades
         *  through the collapse band with the sections
         *  ([applyCollapseCrossfade]), and a faded pill is no target, as
         *  for the handle. Its bottom margin keeps it clear of
         *  [ensureVisible]'s pad, so ringing it never scrolls. */
        private fun ankiPillNavActions(): List<NavAction> =
            if (sliverMode || !ankiPill.isShown || scroll.alpha < 1f) emptyList()
            else listOf(NavAction(ankiPill, holdActivates = true))

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

        // Up off the sheet's top in Definitions: the words over the game are
        // the controller's, as they are while the sheet is parked. The sheet
        // parks with the words' cursor on the word nearest above its edge
        // (the touch rules' edge, above which a word is the word's), the
        // cursor's column first; with no word up there the cursor stays.
        // Never in the camera and import hosts, whose words are touch's.
        private val leaveLoc = IntArray(2)
        override fun leaveSheetUp(fromOnScreen: Rect): Boolean {
            if (!holdsKeysForDefinitions) return false
            root.getLocationOnScreen(leaveLoc)
            val edge = (leaveLoc[1] + panel.top + panel.translationY).toInt()
            val word = SheetNavGeometry.nextInDirection(
                SheetNavGeometry.NavRect(fromOnScreen.left, edge, fromOnScreen.right, edge),
                layer.drawnBoxRects().map { SheetNavGeometry.NavRect(it.left, it.top, it.right, it.bottom) },
                SheetNavGeometry.Dir.UP,
            ) ?: return false
            this@CaptureResultOverlay.collapseToSliver()
            layer.focusWord(word)
            return true
        }

        private val sliverLoc = IntArray(2)
        override fun sliverRect(out: Rect): Boolean {
            // Parked and at rest only: the hint fades in through the collapse
            // band (applyCollapseCrossfade), and a half-faded hint is not yet a
            // target — the pill's gate, mirrored. The ring outlines the hint,
            // the strip's one drawn element, the way the pill is ringed
            // at its drawn bounds rather than its full-width strip.
            if (!sliverMode || !sliverHint.isShown || sliverHint.alpha < 1f) return false
            if (sliverHint.width <= 0 || sliverHint.height <= 0) return false
            sliverHint.getLocationOnScreen(sliverLoc)
            out.set(
                sliverLoc[0], sliverLoc[1],
                sliverLoc[0] + sliverHint.width, sliverLoc[1] + sliverHint.height,
            )
            return true
        }

        override fun resizeBy(dyPx: Int) = stickResizeBy(dyPx)

        override fun commitResize() = commitStickResize()

        override fun wordCount(): Int =
            if (binder?.tvOriginal?.isShown == true) sourceLens?.wordSpans?.size ?: 0 else 0

        override fun wordRect(index: Int, out: Rect): Boolean {
            val lens = sourceLens ?: return false
            val span = lens.wordSpans.getOrNull(index) ?: return false
            return lens.wordRectOnScreen(span.range, out)
        }

        override fun wordRunIsRtl(): Boolean {
            val layout = binder?.tvOriginal?.layout ?: return false
            return layout.getParagraphDirection(0) == Layout.DIR_RIGHT_TO_LEFT
        }

        override fun activateWord(index: Int) {
            val span = sourceLens?.wordSpans?.getOrNull(index) ?: return
            onSourceTapped(span.range.first, fromController = true)
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
            val dy = SheetNavGeometry.revealScrollDelta(itemOnScreen.top, itemOnScreen.bottom, vp.top, vp.bottom, dp(12))
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

    /** Put the sections into their layout and fix its arrangement for this
     *  sheet's life. The layout arranges them (and a hidden section's header
     *  cell) from there on; the sheet only tightens what a sheet rising from
     *  the bottom wants tighter than a page: the side gutters, the headers'
     *  top padding and the buffer below the cards. */
    private fun buildContent(paired: Boolean) {
        sections.removeAllViews()
        sections.fixedArrangement =
            if (paired) ResultSectionsLayout.Arrangement.PAIRED
            else ResultSectionsLayout.Arrangement.STACKED
        val hPad = dp(SECTION_H_PAD_DP)
        // Bottom padding = the Anki pill's clearance, so content scrolled to
        // the end clears the pill. The height and the cards' fill count only
        // the buffer below the cards ([cardBufferPx]): the rest of the
        // padding sits past the fold with the hidden sections' header rows.
        sections.setPadding(
            hPad, 0, hPad, ctx.resources.getDimensionPixelSize(R.dimen.pt_anki_pill_clearance),
        )
        inflater().inflate(R.layout.results_sections, sections, true)
        // One extra dp over the shared pad for a header in the top row. When
        // stacked the source header is the second row and rides 1dp tighter
        // than the shared pad: there is no divider between the stacked
        // sections, the boundary is carried by the cards themselves.
        setHeaderTop(sections.findViewById(R.id.targetHeaderRow), HEADER_TOP_DP + 1)
        setHeaderTop(
            sections.findViewById(R.id.sourceHeaderRow),
            if (paired) HEADER_TOP_DP + 1 else HEADER_TOP_DP - 1,
        )
        // The sections beneath are each a row of their own, like the top row.
        setHeaderTop(sections.findViewById(R.id.askHeaderRow), HEADER_TOP_DP + 1)
        setHeaderTop(sections.findViewById(R.id.wordsHeaderRow), HEADER_TOP_DP + 1)
    }

    /** Set a section [header]'s top padding (the shared layout uses pt_group_gap;
     *  the panel tightens it per mode so the title sits closer to the top edge). */
    private fun setHeaderTop(header: View?, topDp: Int) {
        header ?: return
        header.setPadding(header.paddingLeft, dp(topDp), header.paddingRight, header.paddingBottom)
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
        val floor = if (typingIn != TypingIn.NOTHING) {
            CaptureResultGeometry.minPanelHeight(screenH)
        } else {
            sliverHeightPx()
        }
        setPanelHeight(
            CaptureResultGeometry.clampPanelHeight(
                resizeStartHeight + dy, screenH, minFraction = 0f,
            )
                .coerceAtMost(dragCeilingPx)
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
        when {
            // Released inside the collapse band: the classic floor is the
            // commit threshold (same as the sliver drag's), so the sheet
            // parks in the sliver; a later tap-expand returns to the reading
            // height.
            panelHeightPx < CaptureResultGeometry.minPanelHeight(screenH) -> park()
            // Only a drag that MOVED the sheet states a posture. A grab that
            // went nowhere — a tap on the grabber, or a pull against a sheet
            // already at its content ceiling — leaves the remembered one alone
            // instead of quietly overwriting it with wherever this result sat.
            panelHeightPx != resizeStartHeight -> commitHeight()
        }
    }

    // ── Stick resize (the left stick's virtual grabber drag) ─────────────
    // Delta-driven mirror of the touch resize: [stickResizeBy] is updateResize
    // per frame, [commitStickResize] is the finger-up (endResize / the
    // sliver drag's endSliverDrag, by origin). Driven by the nav controller's
    // frame loop while the left stick is deflected.

    private var stickResizing = false
    private var stickResizeFromSliver = false
    private var stickResizeStartHeight = 0

    private fun stickResizeBy(dyPx: Int) {
        if (dismissed || animatingOut) return
        if (!stickResizing) {
            val was = heightHeld
            stickResizing = true
            holdChanged(was)
            stickResizeFromSliver = sliverMode
            stickResizeStartHeight = panelHeightPx
        }
        // The stick pulling the sheet UP out of its park drops the sliver
        // cursor (the touch drag's beginSliverDrag). Per frame, and only for
        // an upward drive: a downward push at the sliver moves nothing and
        // leaves the ring standing.
        if (sliverMode && dyPx > 0) nav?.clearCursor()
        // Same clamps as updateResize: [floor, 90%], capped at the content's
        // max-needed height; the in-place edit keeps the classic floor (the
        // nav loop is suspended while editing anyway — belt and braces).
        val floor = if (typingIn != TypingIn.NOTHING) {
            CaptureResultGeometry.minPanelHeight(screenH)
        } else {
            sliverHeightPx()
        }
        setPanelHeight(
            CaptureResultGeometry.clampPanelHeight(
                panelHeightPx + dyPx, screenH, minFraction = 0f,
            )
                .coerceAtMost(dragCeilingPx)
                .coerceAtLeast(floor),
        )
        if (panelHeightPx >= CaptureResultGeometry.minPanelHeight(screenH)) reFitText()
    }

    private fun commitStickResize() {
        if (!stickResizing) return
        if (!dismissed && !animatingOut) {
            val min = CaptureResultGeometry.minPanelHeight(screenH)
            when {
                // Mirror endSliverDrag: past the classic floor the stick
                // pulled the sheet out of its park; under it, it settles back
                // into the sliver.
                stickResizeFromSliver -> if (panelHeightPx >= min) commitHeight() else park()
                // Mirror endResize's park (never while editing — the IME
                // would strand over a sliver, the same guard the touch floor
                // encodes). Unlike the touch path, this commit can fire with
                // the popover up (the frame loop's modal suspend lands here);
                // the park closes it rather than leave it floating over the
                // sliver.
                panelHeightPx < min && typingIn == TypingIn.NOTHING -> park()
                panelHeightPx != stickResizeStartHeight -> commitHeight()
            }
        }
        // The hold ends after the posture is written.
        stickResizing = false
        holdChanged(wasHeld = true)
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
        dismissOnGesture && typingIn == TypingIn.NOTHING

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
    private fun syncSheetEdge() {
        // Land the bitmap's contour line (y=cornerRadius, the sheet's straight
        // bottom edge) exactly on the sheet's bottom; the corner arcs above it sit
        // behind the rounded sheet, the cast blur below shows.
        // The sheet's visual top sits below the transparent grabber strip.
        val sheetTop = panel.top + panel.translationY + dp(HANDLE_HEIGHT_DP)
        // The bitmap's contour line (its silhouette's straight top edge) sits at
        // shadowHeight - cornerRadius; land it exactly on the sheet's top.
        edgeShadow.translationY = sheetTop - shadowHeightPx + cornerRadiusPx
        // The edge rail hugs the same edge.
        edgeIndicator.setSheetTop(sheetTop)
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

    // ── Custom views ─────────────────────────────────────────────────────

    /** Blits the pre-baked [shadowBitmap] (never re-blurs); positioned via
     *  [syncSheetEdge]'s translationY. */
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
     *  dismisses. (Rotation dismisses too, via the controller's display listener.)
     *  Its text fields' Cut / Copy / Paste bar is [OverlayWindowRoot]'s. */
    private inner class CaptureResultRoot(c: Context) : OverlayWindowRoot(c) {
        // Sliver gesture state: a DOWN on the sliver zone becomes either a tap
        // (auto-expand to the reading height) or a drag (the user pulls the
        // sheet edge themselves; see endSliverDrag for the commit threshold).
        private var sliverTouch = false
        private var sliverDragging = false
        private var sliverDownRawY = 0f

        /** True while an outside gesture is being streamed to
         *  [outsideLookupRouter] (claimed at its DOWN). */
        private var routingOutside = false

        /** The rules this gesture runs under, the parked sheet's or the open
         *  one's, fixed at its DOWN: a park or an expand the capture writes
         *  meanwhile changes where the sheet rests, not what the gesture
         *  under the finger is, so a drag still ends where its release says. */
        private var sliverGesture = false

        /** This gesture's DOWN went to the open sheet's own views (its texts
         *  and sections, a popover on it), not to its grab band or past its
         *  edge: the user using it ([dispatchTouchEvent]). */
        private var contentGesture = false

        /** Hand [ev] to the views under it; a DOWN they take makes this a
         *  [contentGesture]. */
        private fun toContent(ev: MotionEvent): Boolean {
            val taken = super.dispatchTouchEvent(ev)
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) contentGesture = taken
            return taken
        }

        /** Offer an outside-zone DOWN to the router. True = claimed; the
         *  rest of the gesture streams to it via [routeOutsideFollowUp]. */
        private fun tryRouteOutsideDown(ev: MotionEvent): Boolean {
            val router = outsideLookupRouter ?: return false
            if (typingIn != TypingIn.NOTHING) return false
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

        /** Orphaned-UP guard: pairs tracked here, and the dismissal seam
         *  ([dismiss]) lingers this window as an invisible key sink while a
         *  press is still in flight. */
        val keyGuard = WindowKeyPairGuard()

        /** The platform Back callback while attached ([registerBackCallback]). */
        private var backCallback: Any? = null

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) registerBackCallback()
        }

        override fun onDetachedFromWindow() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) unregisterBackCallback()
            super.onDetachedFromWindow()
        }

        /** Back as the platform delivers it to an app given callback-based
         *  back (target 36 on Android 16), where the key [dispatchKeyEvent]
         *  reads never arrives: the press does what the key does there. The
         *  sheet takes Back while the controller nav is live, which takes it
         *  in every state, or while it holds the keys for Definitions, and
         *  either way runs [onControllerBack]; otherwise a press does
         *  nothing, as the key does. The two paths are exclusive by the
         *  platform's mode, so a press runs once. As [CaptureOverlayWindow]
         *  does for the two-screen window. */
        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        private fun registerBackCallback() {
            val dispatcher = findOnBackInvokedDispatcher() ?: return
            val callback = OnBackInvokedCallback {
                if (!keyGuard.isLingering && (nav != null || holdsKeysForDefinitions)) onControllerBack()
            }
            dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
            backCallback = callback
        }

        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        private fun unregisterBackCallback() {
            val callback = backCallback as? OnBackInvokedCallback ?: return
            backCallback = null
            findOnBackInvokedDispatcher()?.unregisterOnBackInvokedCallback(callback)
        }

        /** Controller keys, live only while the window took focus at show()
         *  ([nav] non-null). Before super so nav sees keys first — its own
         *  edit/popover awareness decides what falls through to children. */
        /** Back fires on the UP of a DOWN this window saw ([backDownSeen]),
         *  so a press from before the sheet appeared cannot close it; with a
         *  controller attached the nav does this itself. */
        private var backDownSeen = false

        override fun dispatchKeyEvent(ev: KeyEvent): Boolean {
            if (keyGuard.isLingering) return keyGuard.lingerKey(ev)
            keyGuard.track(ev)
            // L1 / R1 step the mode pill in every state this window holds the
            // keys in, at the first DOWN of a press; its repeats and its
            // release are consumed with it.
            ControllerKeys.shoulderStep(ev.keyCode)?.let { toRight ->
                if (ev.action == KeyEvent.ACTION_DOWN && ev.repeatCount == 0) stepOverlayMode(toRight)
                return true
            }
            // Definitions while parked: the D-pad and A drive the words (the
            // boxes are all that is on screen); expanded, the nav owns them.
            if (sliverMode && layer.onKey(ev)) return true
            if (nav == null && holdsKeysForDefinitions && ControllerKeys.isBack(ev.keyCode)) {
                when (ev.action) {
                    KeyEvent.ACTION_DOWN -> if (ev.repeatCount == 0) backDownSeen = true
                    KeyEvent.ACTION_UP -> if (backDownSeen) {
                        backDownSeen = false
                        onControllerBack()
                    }
                }
                return true
            }
            return nav?.handleKey(ev) == true || super.dispatchKeyEvent(ev)
        }

        /** Left-stick scroll. Non-pointer generic motion reaches the focused
         *  window's focused view — this root, which holds view focus while nav
         *  is live (the in-place edit moves it to the EditText, and nav bails
         *  there anyway). */
        override fun onGenericMotionEvent(ev: MotionEvent): Boolean =
            nav?.handleGenericMotion(ev) == true || super.onGenericMotionEvent(ev)

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            val action = ev.actionMasked
            val handled = routeTouch(ev)
            // A gesture this root takes holds the sheet's height from its
            // DOWN to its UP or CANCEL ([heightHeld]). One nothing here took
            // is no hold: a host that adds the root as a child view
            // ([ActivitySheetHost]) sends the rest of it elsewhere, and its
            // end would never come.
            if (action == MotionEvent.ACTION_DOWN && handled) {
                val was = heightHeld
                touchHeld = true
                holdChanged(was)
            }
            // The user was using the open sheet (a tap, a scroll, a field),
            // so it stays open: a park the capture wrote meanwhile does not
            // land under what they just did. A gesture the system cancels
            // states nothing.
            if (action == MotionEvent.ACTION_UP && contentGesture) leaveSliver()
            // The hold ends after the gesture is handled, so the gesture's
            // own end has written where the user left the sheet before the
            // sheet settles.
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                val was = heightHeld
                touchHeld = false
                holdChanged(was)
            }
            return handled
        }

        private fun routeTouch(ev: MotionEvent): Boolean {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                sliverGesture = sliverMode
                contentGesture = false
            }
            // A popover (the text-size picker, a ⋯ menu) is a child of this
            // root, and it can sit ABOVE the sheet's top edge — right where the
            // rules below claim every DOWN for the resize grab or an
            // outside-tap dismiss. Stand them all down while one is open and
            // let normal child dispatch run: the popover's own scrim (drawn
            // over everything but its card) handles outside taps by
            // dismissing it.
            if (popovers.isShowing) return toContent(ev)
            // A sequence that began on the mode pill is the pill's, whatever
            // the rules below would claim: it is a child above everything.
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) pillTouch = hitsModePill(ev)
            if (pillTouch) {
                if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                    pillTouch = false
                }
                return super.dispatchTouchEvent(ev)
            }
            if (routeOutsideFollowUp(ev)) return true
            // A word of Definitions the user can see is the word's, ahead of
            // the sheet's own rules: above the sheet's edge, where the grab
            // band reaches past it (expanded) or the sliver's tap-or-drag
            // band does (parked), and on the parked strip itself while the
            // words are drawn over it. The bands keep their gestures where no
            // word is.
            if (ev.actionMasked == MotionEvent.ACTION_DOWN &&
                (chipsOverSheet || ev.y < panel.top + panel.translationY) &&
                layer.wordAt(ev.x, ev.y) && tryRouteOutsideDown(ev)
            ) {
                return true
            }
            // An outside touch is a gesture of its own, under the posture of
            // its moment.
            val sliverRules = if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) sliverMode else sliverGesture
            if (sliverRules) {
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
                        // (A word of Definitions under the finger was the
                        // word's, above.)
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
                            ev.actionMasked == MotionEvent.ACTION_UP -> userExpandFromSliver()
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
            return toContent(ev)
        }
    }

    /** The visible bottom sheet. While its content is at the top (nothing
     *  scrolled off above), a long vertical down-drag on the body dismisses
     *  (swipe-to-dismiss): at the top a downward drag has nothing to scroll,
     *  so it cannot be mistaken for one. Scrolled anywhere else the drag
     *  scrolls, and an upward drag always does. (The gate was once "the
     *  content does not scroll at all", but the sections below the fold make
     *  every sheet scroll.) Drags starting on the grabber strip are left to
     *  the resize listener, which only ever resizes: the sheet bottoms out
     *  at the sliver, never off. */
    private inner class BottomSheetPanel(c: Context) : LinearLayout(c) {
        private var downX = 0f
        private var downY = 0f
        private var downRawY = 0f
        private var dragging = false
        private var dragTracker: VelocityTracker? = null

        private fun contentAtTop() = !scroll.canScrollVertically(-1)

        private fun inHandle(y: Float) = y <= dp(HANDLE_HEIGHT_DP)

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.x; downY = ev.y; downRawY = ev.rawY; dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (dragging) return true
                    if (inHandle(downY) || !contentAtTop()) return false
                    val dy = ev.y - downY
                    val dx = ev.x - downX
                    // Downward only: an upward drag is the content's scroll.
                    if (dy > touchSlop && dy > abs(dx)) {
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
        /** The sliver hint's leading glyph box, the touch_app icon's. Sized
         *  to the 11sp line rather than the 24dp intrinsic, so it cannot
         *  outgrow [SLIVER_SHEET_DP]. */
        const val HINT_GLYPH_DP = 16
        /** Breathing room kept either side of the sliver hint when its
         *  text is bounded (see show()'s maxWidth), and the keep-out from the
         *  screen edges when [placeSliverHint] moves it. */
        const val SLIVER_HINT_SIDE_PAD_DP = 20
        /** Room the hint keeps between itself and an on-frame box on each
         *  side before its text counts as fitting the stretch between boxes. */
        const val HINT_CLEARANCE_DP = 12
        /** Gap between the hint's leading glyph and its text. */
        const val GLYPH_GAP_DP = 4
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
        /** Text-card fill opacity (0–1) — very slightly translucent so the frost
         *  shows faintly behind the text too. The sheet fill's own opacity is
         *  the shared [FrostBackdrop.WASH_ALPHA]. */
        const val CARD_FILL_ALPHA = 0.8f
        /** [opaqueBackgroundBoost] variants — barely transparent for hosts
         *  with no frosted backdrop behind the sheet (the un-blurred app
         *  behind is distracting at the normal translucency). */
        const val SHEET_ALPHA_NO_IMAGE = 247
        const val CARD_FILL_ALPHA_NO_IMAGE = 0.96f
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
