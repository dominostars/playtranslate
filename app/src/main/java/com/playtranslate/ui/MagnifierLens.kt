package com.playtranslate.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.Choreographer
import android.view.GestureDetector
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewTreeObserver
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.DrawableCompat
import com.playtranslate.dictionary.Deinflector
import com.playtranslate.overlay.OverlayHost
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.isEffectivelyDark
import com.playtranslate.overlayThemedContext
import com.playtranslate.themeColor
import androidx.core.view.isVisible
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withClip
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * Floating magnifier lens shown while the user drags on a JP/ZH/Latin token
 * and persists post-release as the dictionary surface.
 *
 * Visual structure (matches design handoff in
 * /Users/giladgurantz/playtranslate/design_handoff_magnifying_lens):
 *  - A rounded card (16dp radius, BWI_PANEL #15181B, 1dp white-16% border)
 *    holds the zoom / loading / definitions body.
 *  - A coral pill (accent-themed) overhangs the card's top edge by half its
 *    height. It carries the word, a hairline divider, the reading, and a
 *    trailing chevron that signals "tap to drill into Details."
 *  - Two "calm" chip buttons (32dp visible disk, 48dp hit halo) flank the
 *    pill — Speak on the left, Anki on the right. They are no-ops in this
 *    commit; the wiring will land in a follow-up.
 *  - A 10dp triangular arrow drops from the card's bottom edge (or rises
 *    from the top when the lens flips below the finger).
 *
 * Render modes:
 *  - **ZOOM** (drag): zoomed pixels + crosshair fill the card body. The pill
 *    keeps showing the currently-detected token. Chips hidden.
 *  - **LOADING**: spinner + "Looking up…" in the body while OCR/lookup
 *    resolve. Pill shows the word being resolved. Chips hidden.
 *  - **DEFINITIONS** (drag-preview AND sticky): the body shows the
 *    dictionary view (existing rows). Pill shows the looked-up word.
 *    Chips appear only once the lens becomes interactive via
 *    [makeInteractive] — that signals the post-release sticky state where
 *    quick actions are appropriate.
 *
 * Tap routing:
 *  - In sticky mode, tapping anywhere on the card OR the pill fires
 *    [onOpenTap] (opens the detail page). The chevron is purely a visual
 *    cue.
 *  - Split mode ([setSplitDefinitions] — a tapped unit with a related
 *    unit: word + containing phrase on the space-delimited surfaces,
 *    fused expression + member word on JA): the body is two sections,
 *    the longer unit on top, each its OWN tap target ([onOpenTap] for
 *    the tapped/primary unit, [onSecondaryOpenTap] for the related one)
 *    with its own chevron; the card-wide open detector stands down over
 *    the body. The pill still fires [onOpenTap] — it carries the tapped
 *    unit's identity.
 *  - Chip taps are absorbed (no-op) — they do NOT fall through to the
 *    card-wide open handler.
 *  - The arrow strip and the (currently hidden) pill chrome region outside
 *    the card are non-interactive.
 */
/** Text-size factor the lens passes to [WordDefinitionsView]. The shared
 *  renderer's base sizes are tuned for the full-width result cell; the lens
 *  is a small floating card, so it renders the same body scaled down to keep
 *  its compact footprint (definitions land near the lens's former ~13sp). */
private const val LENS_DEFINITIONS_SCALE = 0.8f

/** One section of the lens's split (phrase + word) definitions body — see
 *  [MagnifierLens.setSplitDefinitions]. [opens] renders the section's
 *  trailing chevron and makes the whole section a tap target for its
 *  open-detail callback; pass false on surfaces (or entries) with nothing
 *  to open into. */
data class LensSection(
    val data: WordDefinitionData,
    val label: String?,
    val opens: Boolean,
)

/**
 * Clamped card-body height for the post-release grow-to-fit. The card grows
 * away from the anchored (arrow/finger) edge — UP when not flipped, DOWN when
 * flipped — but never past the safe screen edge: the outer chrome (pill/chip
 * [overhang]) must stay within [[safeTop], [safeBottom]]. Never shrinks below
 * [baseCardH]; a card already pinned against its edge can't grow and stays at
 * base. The window itself is pre-sized to full height, so this returns only
 * the card height — no window placement (the card grows inside a fixed window).
 *
 * Pure (no Android deps) so the clamp math is unit-testable.
 *
 * @param anchoredEdgeY screen Y of the anchored card edge — card BOTTOM when
 *   not flipped, card TOP when flipped.
 */
internal fun computeGrownCardHeight(
    flipped: Boolean,
    anchoredEdgeY: Int,
    desiredCardH: Int,
    baseCardH: Int,
    safeTop: Int,
    safeBottom: Int,
    overhang: Int,
): Int {
    val maxCardH = if (!flipped) {
        anchoredEdgeY - overhang - safeTop          // card top − overhang ≥ safeTop
    } else {
        safeBottom - overhang - anchoredEdgeY        // card bottom + overhang ≤ safeBottom
    }
    return desiredCardH.coerceIn(baseCardH, maxCardH.coerceAtLeast(baseCardH))
}

/**
 * Widest the pill capsule may render inside a [viewW]-wide lens host.
 *
 * The chips are seated off the pill's own edges ([computeChipLaneMargins]), so
 * every pixel the pill takes past this cap pushes them a pixel further past the
 * host's frame — where the FrameLayout clips them away, visible disk and touch
 * target together. At exactly this width the two chips land flush with the
 * host's left and right edges: the resting placement they are laid out at
 * before the reveal slides them out from under the pill.
 *
 * @param chipLane horizontal space one chip needs beside the pill — its
 *   hit-halo pad, its visible disk, and the resting gap to the pill's edge.
 */
internal fun computeMaxPillWidth(viewW: Int, chipLane: Int): Int =
    (viewW - 2 * chipLane).coerceAtLeast(0)

/**
 * Left and right margins that seat the chips one [chipLane] outside a
 * [pillWidth]-wide pill centered in a [viewW]-wide host. Both come out ≥ 0
 * exactly when [pillWidth] is within [computeMaxPillWidth].
 */
internal fun computeChipLaneMargins(viewW: Int, pillWidth: Int, chipLane: Int): Pair<Int, Int> {
    val pillLeft = (viewW - pillWidth) / 2
    val pillRight = pillLeft + pillWidth
    return (pillLeft - chipLane) to (viewW - pillRight - chipLane)
}

/**
 * Split what [maxPillWidth] leaves after [fixedChromeWidth] — paddings,
 * divider, gaps, chevron — between the pill's headline and its reading, given
 * the [readingWidth] the reading wants at its settled text size.
 *
 * The reading takes only what it needs, but never more than half the budget:
 * the headline is the word being looked up, and a long reading must not starve
 * it. Both numbers are handed to their views as maxWidths, so their sum plus
 * the chrome is what bounds the capsule's WRAP_CONTENT width — and that bound
 * is what keeps the chip lanes intact.
 */
internal fun computePillTextAllotment(
    maxPillWidth: Int,
    fixedChromeWidth: Int,
    readingWidth: Int,
): Pair<Int, Int> {
    val budget = (maxPillWidth - fixedChromeWidth).coerceAtLeast(0)
    val word = (budget - readingWidth.coerceAtLeast(0)).coerceAtLeast(budget - budget / 2)
    return word to (budget - word)
}

class MagnifierLens(
    internal val rawCtx: Context,
    internal val wm: WindowManager,
    private val displayId: Int,
    private val overlayHost: OverlayHost? = null,
    /** When false, the post-release Anki chip stays hidden — used by the
     *  over-game capture panel, whose lens is display + speak only. */
    private val showAnkiChip: Boolean = true,
) {
    private val density = rawCtx.resources.displayMetrics.density
    private fun dp(v: Float) = (v * density).toInt()

    private val lensH = dp(120f)
    /** Card has rounded corners; the body, pill, and chips overlay it. */
    private val cardCornerR = dp(16f).toFloat()

    /** Distance in px between finger center and the near edge of the lens body. */
    private val verticalMarginPx = dp(25f)
    /** Triangular pointer drawn between the lens body and the finger when
     *  the lens is in sticky-definitions mode. Matches WordLookupPopup's
     *  arrow proportions so the two surfaces feel like the same family. */
    private val arrowSizePx = dp(10f)
    /** Pill is 40dp tall, centered on the card's top edge so half overhangs
     *  above the card. */
    private val pillHeightPx = dp(40f)
    private val chipVisDiameterPx = dp(36f)
    private val chipHitSizePx = dp(52f)
    /** Distance from the chip hit-button edge to the visible disk edge. */
    private val chipHaloPadPx = (chipHitSizePx - chipVisDiameterPx) / 2
    /** Visible chip disk insets 4dp from the card horizontal edge. The host
     *  view is wider than the card by exactly this amount on each side so
     *  the chip's hit halo can extend the full 48dp without clipping. */
    private val chipHaloXPx = dp(4f)
    /** Pixels reserved above (or below, when flipped) the card so the chip's
     *  48dp hit halo can render fully. The chip is vertically centered on
     *  the card's edge — half its height (24dp) overhangs the card. */
    private val pillChipOverhangPx = chipHitSizePx / 2

    private val zoom = 2f
    /** Tolerance for the lens overrunning the top of the screen before we
     *  flip it below the finger; matches the original feel. */
    private val topOverhangTolerancePx = lensH / 5
    /** Extra slack on the flip threshold: the lens flips below the finger
     *  32dp earlier — while the finger is still 32dp lower on the screen
     *  than the geometric fit alone would require. */
    private val flipBiasPx = dp(32f)
    /** Gap kept between the lens's outer chrome edge and the nearest safe
     *  screen edge when the card grows to fit its content. */
    private val growBufferPx = dp(8f)

    private var lensView: LensView? = null
    /** Full-screen host that owns the window; [lensView] is its single child,
     *  slid horizontally inside it. The window is the whole display so a tap
     *  anywhere off the card is caught and consumed (dismiss without leaking
     *  the tap to the app/game beneath). */
    private var lensRoot: LensRoot? = null
    private var params: WindowManager.LayoutParams? = null
    /** Snapshot of (isDark, accentColorRes) at the time the cached
     *  [lensView] was built. Compared against the live prefs on every
     *  [show]: when the user changes the theme the cached window is no
     *  longer correct, so we tear it down silently and let
     *  [ensureWindow] rebuild against fresh attrs. */
    private var cachedThemeKey: Pair<Boolean, Int>? = null

    private fun currentThemeKey(): Pair<Boolean, Int> =
        isEffectivelyDark(rawCtx) to Prefs(rawCtx).accent.color

    /** Most recent finger x from [show]. Used by [makeInteractive] to align
     *  the sticky-mode arrow horizontally with the release position. */
    private var lastFingerX = 0

    // --- Post-release grow-to-fit state (captured at [show], consumed by
    //     [fitHeightToContent]). ----------------------------------------
    /** Current logical card-body height, mirrored from the view. Reset to
     *  [lensH] on every [show]; grown by [fitHeightToContent] after release. */
    private var lensCardHeight = lensH
    /** Flip state of the last [show] — fixes the grow direction. */
    private var lastFlipped = false
    /** Screen height from the last [show], for the bottom safe-edge clamp. */
    private var lastScreenH = 0
    /** Screen Y of the anchored card edge (the arrow/finger edge that stays
     *  put as the card grows): card BOTTOM when not flipped, card TOP when
     *  flipped. */
    private var anchoredEdgeScreenY = 0
    /** Horizontal offset of the lens column inside the full-screen root, and
     *  the card width — captured at [show] for [makeInteractive]'s arrow. */
    private var lastLensX = 0
    private var lastCardW = 0
    /** Drives the animated card grow on release; cancelled on re-fit / teardown. */
    private var heightAnimator: ValueAnimator? = null

    /** Fires once per actual window teardown (tap-outside in sticky mode,
     *  new drag start, [dismiss] caller). */
    var onDismiss: (() -> Unit)? = null
    /** Fires when the card or pill is tapped in sticky mode. In split mode
     *  ([setSplitDefinitions]) this is the PRIMARY section's open action —
     *  the pill and the primary section both fire it. */
    var onOpenTap: (() -> Unit)? = null
    /** Fires when one of the split body's SECONDARY sections is tapped in
     *  sticky mode, with its index into the [setSplitDefinitions]
     *  secondaries list — the containing phrase on the space-delimited
     *  surfaces, a member word on JA. Never fires outside split mode. */
    var onSecondaryOpenTap: ((Int) -> Unit)? = null
    /** Fires when the right chip (Anki) is tapped in sticky mode. */
    var onAnkiTap: (() -> Unit)? = null
    /** Fires when the right chip (Anki) is long-pressed in sticky mode.
     *  Used by the one-tap feature to open the editable review sheet
     *  while a short tap performs the headless send. */
    var onAnkiLongPress: (() -> Unit)? = null
    /** Fires when the left chip (Speak) is tapped in sticky mode. */
    var onSpeakTap: (() -> Unit)? = null

    /** True when there's no [overlayHost] — the lens attaches directly to the
     *  activity window (TYPE_APPLICATION_PANEL) instead of going through a
     *  backend overlay host. Used by the in-app tap-a-word surface. */
    private val useActivityWindow: Boolean get() = overlayHost == null

    val isInteractive: Boolean get() = lensView?.isInteractive == true

    /** True while the sticky lens HOLDS WINDOW FOCUS for controller input —
     *  [makeInteractive] found a controller, so A/B/dpad/stick are driving the
     *  lens, not the game. The a11y key filter's "game input clears the
     *  lookup" rule must stand down for those keys while this is set. */
    val isConsumingController: Boolean get() = isInteractive && tookControllerFocus
    private var tookControllerFocus = false

    /** Zoom source, held here as well as on the view: setBitmap can arrive
     *  while the lens is hidden (the camera scene flow attaches the frame
     *  BEFORE its deferred reveal creates the view), and a view-only write
     *  would silently drop it — the zoom then renders as a black card. The
     *  drag flow clears this via setBitmap(null) before every recycle, so
     *  it can never dangle a recycled bitmap into the next show(). */
    private var sourceBitmapForShow: Bitmap? = null

    fun setBitmap(bitmap: Bitmap?) {
        sourceBitmapForShow = bitmap
        lensView?.setSourceBitmap(bitmap)
    }

    /** Update the word + reading on the pill. Pass null for either to hide. */
    fun setLabel(word: String?, reading: String?) {
        lensView?.setLabel(word, reading)
    }

    fun setDefinitions(data: WordDefinitionData?, label: String?) {
        lensView?.setDefinitions(data, label)
        // Re-fit when an already-interactive lens gets new content (the Anki
        // deck-badge back-fill re-binds after makeInteractive). Pre-release
        // and dwell-preview binds aren't interactive, so they stay at base
        // height until makeInteractive runs the first fit.
        if (isInteractive) fitHeightToContent()
    }

    /** Split definitions body: the [primary] section plus one or more
     *  [secondaries], dividers between, each headed by its own headword and
     *  (when it opens) a trailing chevron. [primary] is the TAPPED unit —
     *  the pill keeps its identity and [onOpenTap] is its open action;
     *  each secondary routes to [onSecondaryOpenTap] with its list index.
     *  [secondariesOnTop] places the LONGER unit above: true on the
     *  space-delimited surfaces (tapped word + containing phrase above it),
     *  false on JA (tapped fused expression on top + its member words
     *  below, in expression order). Sections bind flat instantly; any
     *  section whose data carries a structured Yomitan payload upgrades to
     *  its own styled (WebView) renderer once painted, exactly like the
     *  single-unit body. */
    fun setSplitDefinitions(
        primary: LensSection,
        secondaries: List<LensSection>,
        secondariesOnTop: Boolean = true,
    ) {
        lensView?.setSplitDefinitions(primary, secondaries, secondariesOnTop)
        if (isInteractive) fitHeightToContent()
    }

    fun setLoading(word: String?, reading: String?) {
        lensView?.setLoading(word, reading)
    }

    /** Show the spinner alone in the Placeholder pill (no label) while
     *  the drag-start OCR job runs. Pass false once OCR completes
     *  (success, "no text", exception, or cancellation) to animate back
     *  to the magnifying-glass icon + "Find a word" prompt. No-op when
     *  the pill is showing a word. */
    fun setPillLoading(loading: Boolean) {
        lensView?.setPillLoading(loading)
    }

    /** Show or hide the loading spinner on the Speak chip. */
    fun setSpeakChipLoading(loading: Boolean) {
        lensView?.setSpeakChipLoading(loading)
    }

    fun makeInteractive() {
        val view = lensView ?: return
        val root = lensRoot ?: return
        val p = params ?: return
        // The interactive card needs window focus only for key/stick
        // navigation (dpad + A over the pill/chips, stick scroll, B/Escape
        // dismiss). With no gamepad or hardware keyboard attached there is
        // nothing to navigate WITH, so stay non-focusable: focus taking is
        // never free (it steals key input from the app beneath). Touch and
        // outside-touch dismissal work either way — neither needs focus.
        var flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        tookControllerFocus = hasNavInputDevice(rawCtx)
        if (!tookControllerFocus) {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        } else {
            // Focus makes this window the system-bar control target, whose
            // default request would show the nav pill over an immersive game.
            // The window has been attached non-focusable since show(), so its
            // own insets still reflect the game's bar state — mirror it, armed
            // before the flag flip so the request rides the focus grant.
            OverlayHost.mirrorSystemBars(root, OverlayHost.hiddenSystemBars(root))
        }
        // The window is already full-screen (set in show()); this flag change
        // is flags-only — no size or position change, so it can't trigger the
        // resize flash. The card grows purely inside the fixed window below.
        p.flags = flags
        try { wm.updateViewLayout(root, p) } catch (_: Exception) {}
        // Sticky now: the root catches off-card taps to dismiss (and consumes
        // them so they don't reach the app/game behind), and — while focusable
        // for the controller — the B button dismisses the same way.
        root.interactive = true
        root.onOffCardTap = { dismiss() }
        root.onDismissKey = { dismiss() }
        view.attachInteractiveListeners(onDismissRequest = { dismiss() })
        // Arrow x stays inside the card region (in lens-local coords) — clamped
        // so the triangle's tip lands over the card, not the chip-halo padding.
        val arrowRelX = (lastFingerX - lastLensX).coerceIn(
            chipHaloXPx + arrowSizePx,
            chipHaloXPx + lastCardW - arrowSizePx,
        )
        view.setArrowVisible(true, arrowRelX)
        // Release commit: grow the card to fit the dictionary, animated inside
        // the fixed window (definitions were bound just above).
        fitHeightToContent()
    }

    /** Controller-opened lens: ring the pill from the start, so the next A
     *  drills straight into the detail screen. A touch-opened lens selects on
     *  the first controller input instead (the sheet's first-press rule). */
    fun focusPillForController() {
        lensView?.focusPill()
    }

    /** Grow the card to fit newly-bound definitions on an ALREADY-interactive
     *  lens — the Anki deck-badge re-bind after [makeInteractive]. Grows the
     *  card inside the fixed full-height window; no-op for dwell-preview binds
     *  (not yet interactive), so the card stays at base height until release. */
    fun fitHeightToContent() {
        val view = lensView ?: return
        if (!view.isInteractive) return
        val target = grownCardHeight(view)
        if (target <= lensCardHeight) return
        animateCardGrowInside(view, target)
    }

    /** Clamped card height for the currently bound definitions. */
    private fun grownCardHeight(view: LensView): Int {
        val (safeTop, safeBottom) = safeVerticalBounds(view)
        return computeGrownCardHeight(
            flipped = lastFlipped,
            anchoredEdgeY = anchoredEdgeScreenY,
            desiredCardH = view.desiredCardHeightForContent(),
            baseCardH = lensH,
            safeTop = safeTop,
            safeBottom = safeBottom,
            overhang = pillChipOverhangPx,
        )
    }

    /** Visible vertical bounds for the grow clamp: system-bar + display-cutout
     *  insets shrink the raw screen, plus [growBufferPx]. Uses the public
     *  AndroidX inset API (API 29+); falls back to the raw screen edges when
     *  insets aren't available yet. */
    private fun safeVerticalBounds(view: View): Pair<Int, Int> {
        val insets = ViewCompat.getRootWindowInsets(view)?.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        val top = (insets?.top ?: 0) + growBufferPx
        val bottom = lastScreenH - (insets?.bottom ?: 0) - growBufferPx
        return top to bottom
    }

    /** Animate the card body from [lensCardHeight] to [target] INSIDE the fixed
     *  full-height window — never a window resize (which would flash the overlay
     *  at its gravity anchor). Not flipped: the card is bottom-anchored (its top
     *  rides up as it grows) so the arrow stays on the word; flipped: the card
     *  top is fixed and it grows downward.
     *
     *  The children are laid out ONCE at the end-state ([LensView.beginCardGrow])
     *  and each animated frame is draw-only ([LensView.setGrowFrame]) — a
     *  per-frame relayout re-measures the whole dictionary subtree, which on
     *  long entries stalls the main thread mid-grow. */
    private fun animateCardGrowInside(view: LensView, target: Int) {
        heightAnimator?.cancel()
        applyCardFrame(view, lensCardHeight)
        val finalTop = if (!lastFlipped) anchoredEdgeScreenY - target else anchoredEdgeScreenY
        view.beginCardGrow(target, finalTop)
        heightAnimator = ValueAnimator.ofInt(lensCardHeight, target).apply {
            duration = 180L
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { anim ->
                val h = anim.animatedValue as Int
                lensCardHeight = h
                val top = if (!lastFlipped) anchoredEdgeScreenY - h else anchoredEdgeScreenY
                view.setGrowFrame(h, top)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    view.endCardGrow()
                }
            })
            start()
        }
    }

    /** Place the card of height [h] inside the fixed window via its screen-space
     *  top: bottom-anchored when not flipped (top = anchoredEdge − h, so the
     *  arrow stays on the word), top-anchored (fixed top, grows down) flipped. */
    private fun applyCardFrame(view: LensView, h: Int) {
        val cardTop = if (!lastFlipped) anchoredEdgeScreenY - h else anchoredEdgeScreenY
        view.setCardGeometry(h, cardTop)
    }

    /** Window flags for the lens in its non-interactive (zoom) state. On the
     *  MediaProjection backend the window must stay touchable: a non-touchable
     *  TYPE_APPLICATION_OVERLAY is opacity-capped by the anti-tapjacking rule
     *  and renders washed out. The drag gesture is owned by the floating
     *  icon's window, so a touchable lens window does not steal it. */
    private fun zoomWindowFlags(): Int {
        // HARDWARE_ACCELERATED: only read at addView time (OverlayHost also
        // stamps it centrally), carried here so the wholesale flag rewrites
        // in resetToZoom/makeInteractive keep params honest — and so the
        // non-host (in-app activity window) path gets it too. The styled
        // definitions panel hosts a WebView, which needs the HW pipeline.
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        if (overlayHost?.windowType != WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY) {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        return flags
    }

    fun resetToZoom() {
        val view = lensView ?: return
        val root = lensRoot ?: return
        val p = params ?: return
        // Back to the base-height zoom card for the next drag.
        heightAnimator?.cancel()
        lensCardHeight = lensH
        view.setCardHeight(lensH)
        // No longer sticky: stop catching off-card taps. Window stays full-
        // screen (never resized); just revert the flags. The following show()
        // repositions the base card for the new drag.
        root.interactive = false
        root.onOffCardTap = null
        p.flags = zoomWindowFlags()
        try { wm.updateViewLayout(root, p) } catch (_: Exception) {}
        view.detachInteractiveListeners()
        view.setDefinitions(null, null)
        view.setLabel(null, null)
        view.setSourceBitmap(null)
        view.setArrowVisible(false, 0)
    }

    /** Card width — the visible rounded panel — under the existing
     *  responsive rule: `min(screenW × 0.85, 380dp)`. The host view is
     *  wider by 2 × [chipHaloXPx] so the chip hit halos can render. */
    private fun cardWidth(screenW: Int): Int =
        (screenW * 0.85f).toInt().coerceAtMost(dp(380f))

    /**
     * Position and show the lens.
     *
     * [fingerY] is the anchor's TOP edge in screen coords. [anchorHeight] is
     * the anchor's height — for the drag flow the anchor is a finger point so
     * 0 is right, but for tap-on-a-word callers the anchor is a line of text
     * with a real height, and the lens needs that to land cleanly below the
     * line in the flipped case (otherwise "below" means below the line's top,
     * which lands on the line itself).
     *
     * Flip decision: prefer above when the lens fits with [flipBiasPx] of
     * headroom to spare, fall back to below otherwise. In drag mode we
     * tolerate a small overhang past the screen top because the user's
     * finger is on-screen and they can see what happens; in activity-window
     * mode there's a status bar to worry about, so we require the lens
     * window — including the pill/chip overhang — to fully fit before
     * staying above.
     */
    fun show(
        fingerX: Int,
        fingerY: Int,
        screenW: Int,
        screenH: Int,
        anchorHeight: Int = 0,
    ) {
        // If the user changed the theme (mode or accent) between drags,
        // the cached LensView still carries the old colors. Tear it down
        // silently so ensureWindow rebuilds with the new attrs. Silent =
        // does NOT fire onDismiss (the user didn't dismiss; we're just
        // reconfiguring under the hood).
        val themeKey = currentThemeKey()
        if (lensView != null && cachedThemeKey != themeKey) {
            removeOverlayInternal()
        }
        cachedThemeKey = themeKey

        val cardW = cardWidth(screenW)
        val viewW = cardW + 2 * chipHaloXPx
        ensureWindow(cardW, viewW, screenW, screenH)
        val view = lensView ?: return
        val root = lensRoot ?: return

        val aboveY = fingerY - verticalMarginPx - lensH
        // Activity-window callers (tap-on-word in the result screen) need the
        // pill/chip overhang to fully clear the screen top — there's a status
        // bar in the way. Drag callers can tolerate a small overhang past the
        // screen top (the user is actively pointing at the screen and the
        // chip's exact position isn't load-bearing).
        val flipThreshold =
            if (useActivityWindow) pillChipOverhangPx else -topOverhangTolerancePx
        val flipped = aboveY < flipThreshold + flipBiasPx
        view.setSourcePoint(fingerX.toFloat(), fingerY.toFloat(), screenW, screenH)
        view.setLensFlipped(flipped)
        root.isVisible = true

        lastFingerX = fingerX
        lastFlipped = flipped
        lastScreenH = screenH
        // A fresh show is always the base-height zoom card; undo any grow
        // left over from a prior sticky lens being reused for a new drag.
        heightAnimator?.cancel()
        lensCardHeight = lensH

        // The card's TOP in screen coords. The window spans the full screen at
        // y = 0, so within it the card top sits at this same Y (card-top override).
        val lensBodyY = if (!flipped) {
            aboveY
        } else {
            (fingerY + anchorHeight + verticalMarginPx)
                .coerceAtMost((screenH - lensH).coerceAtLeast(0))
        }
        // Anchored card edge (the arrow/finger edge that stays put when the
        // card grows on release): card bottom above the finger, card top when
        // flipped below it.
        anchoredEdgeScreenY = if (!flipped) lensBodyY + lensH else lensBodyY
        view.setCardGeometry(lensH, lensBodyY)

        // Slide the lens column to follow the finger horizontally; the window
        // itself is fixed full-screen (never moves or resizes), so this is a
        // pure view translation inside the root — no updateViewLayout, no flash.
        val lensX = (fingerX - viewW / 2).coerceIn(0, (screenW - viewW).coerceAtLeast(0))
        lastLensX = lensX
        lastCardW = cardW
        root.setLensX(lensX)
        view.invalidate()
    }

    fun hide() {
        lensRoot?.visibility = View.INVISIBLE
    }

    fun dismiss() {
        if (lensView == null) return
        removeOverlayInternal()
        onDismiss?.invoke()
    }

    /** Remove the overlay + reset state WITHOUT firing onDismiss. Used by
     *  the dismiss() user path (which then fires the callback) and by
     *  the theme-change rebuild in [show] (which must not look like a
     *  user-initiated dismissal). */
    private fun removeOverlayInternal() {
        val root = lensRoot ?: return
        // Full interactive teardown BEFORE the window goes — this is the one
        // removal path every dismissal funnels through, and the stick-scroll
        // Choreographer callback re-posts itself: without this, dismissing
        // mid-deflection leaves it running per-frame against the detached
        // view (holding it) forever. resetToZoom() detaches separately; a
        // second detach here is idempotent.
        lensView?.detachInteractiveListeners()
        // The styled renderer's native WebView must be destroy()ed, not
        // just dropped with the window: a removed-but-undestroyed WebView
        // holds renderer resources and its context graph until GC, so
        // repeated lens open/dismiss churn accumulates the most expensive
        // object in the app (Codex adversarial catch).
        lensView?.releaseStyledView()
        heightAnimator?.cancel()
        heightAnimator = null
        lensCardHeight = lensH
        lensView = null
        lensRoot = null
        val p = params
        params = null
        val remove: () -> Unit = {
            if (overlayHost != null) {
                overlayHost.removeOverlayWindow(root)
            } else {
                try { wm.removeView(root) } catch (_: Exception) {}
            }
        }
        // Orphaned-UP guard: a key that went down on this focused window is
        // still held (the Anki one-tap long-press fires at the timeout and
        // dismisses mid-press) — the window must outlive its UI as an
        // invisible, untouchable key sink until the release lands here, or
        // the UP would be delivered unmatched to whatever window is focused
        // next (the game, in the drag flow). See [WindowKeyPairGuard].
        if (root.keyGuard.hasPendingDown && p != null) {
            root.visibility = View.INVISIBLE
            p.flags = p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            try { wm.updateViewLayout(root, p) } catch (_: Exception) {}
            root.keyGuard.beginLinger(remove)
        } else {
            remove()
        }
    }

    private fun ensureWindow(cardW: Int, viewW: Int, screenW: Int, windowH: Int) {
        if (lensView != null) return
        // Build the themed context fresh on each window construction so
        // it reflects the user's current mode + accent. Caching this at
        // MagnifierLens construction is what caused the lens to ignore
        // theme changes — the floating-icon menu sidesteps the same
        // issue by being reconstructed every time it's shown.
        val themedCtx = overlayThemedContext(rawCtx)
        val view = LensView(
            ctx = themedCtx,
            cardW = cardW,
            viewW = viewW,
            lensH = lensH,
            chipHaloXPx = chipHaloXPx,
            pillChipOverhangPx = pillChipOverhangPx,
            cardCornerR = cardCornerR,
            zoom = zoom,
            arrowSizePx = arrowSizePx,
            pillHeightPx = pillHeightPx,
            chipVisDiameterPx = chipVisDiameterPx,
            chipHitSizePx = chipHitSizePx,
            chipHaloPadPx = chipHaloPadPx,
            density = density,
            onOpenTap = { onOpenTap?.invoke() },
            onSecondaryOpenTap = { i -> onSecondaryOpenTap?.invoke(i) },
            onAnkiTap = { onAnkiTap?.invoke() },
            onAnkiLongPress = { onAnkiLongPress?.invoke() },
            onSpeakTap = { onSpeakTap?.invoke() },
            showAnkiChip = showAnkiChip,
            onStyledHeightChanged = { fitHeightToContent() },
        )
        val root = LensRoot(themedCtx, view, viewW)
        val windowType = if (useActivityWindow)
            WindowManager.LayoutParams.TYPE_APPLICATION_PANEL
        else
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        // Full-screen window, never moved or resized: the card is positioned and
        // grown INSIDE it (resizing a live overlay flashes it at its gravity
        // anchor), and a tap anywhere off the card is caught + consumed so it
        // dismisses without leaking to the app/game beneath. The card follows
        // the finger via [LensRoot]'s internal lens translation, not the window.
        val lp = WindowManager.LayoutParams(
            screenW, windowH,
            windowType,
            zoomWindowFlags(),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
        val attached = if (overlayHost != null) {
            overlayHost.addOverlayWindow(root, wm, lp, displayId)
        } else {
            try { wm.addView(root, lp); true } catch (_: Exception) { false }
        }
        if (!attached) return
        lensView = view
        lensRoot = root
        params = lp
        // A bitmap attached before this view existed (deferred-reveal scene
        // flow) applies now; null is a harmless no-op-equivalent.
        view.setSourceBitmap(sourceBitmapForShow)
    }

    /**
     * Full-screen, transparent host that owns the lens window. [lensView] (the
     * card-width view that actually paints) is its only child, slid
     * horizontally inside it to follow the finger. The window spans the whole
     * display for two reasons: the card can grow on release without ever
     * resizing the window (resizing a live overlay flashes it at its gravity
     * anchor on some devices), and a tap anywhere off the card is caught and
     * consumed — dismissing the lens without leaking that tap to the app/game
     * behind it.
     */
    private class LensRoot(
        ctx: Context,
        val lens: LensView,
        private val lensW: Int,
    ) : FrameLayout(ctx) {
        /** True once the lens is sticky; off-card taps dismiss only then. */
        var interactive = false
        /** Invoked when a sticky-mode DOWN lands left/right of the lens column.
         *  (Taps inside the column but off the chrome band are dismissed by
         *  [lens] itself; both are consumed by this full-screen window.) */
        var onOffCardTap: (() -> Unit)? = null
        /** Controller B / back while the sticky lens holds window focus (it
         *  goes focusable when a controller is attached — [makeInteractive]).
         *  A separate hook from [onOffCardTap]: same dismissal, different
         *  intent. */
        var onDismissKey: (() -> Unit)? = null

        /** True between a consumed B DOWN and its UP, so dismissal fires only
         *  for a press that began on this window. */
        private var backDownSeen = false

        /** Orphaned-UP guard: pairs tracked here, and the dismissal seam
         *  ([removeOverlayInternal]) lingers this window as an invisible key
         *  sink while a press is still in flight. */
        val keyGuard = WindowKeyPairGuard()

        override fun dispatchKeyEvent(ev: KeyEvent): Boolean {
            if (keyGuard.isLingering) return keyGuard.lingerKey(ev)
            keyGuard.track(ev)
            if (interactive && ControllerKeys.isBack(ev.keyCode)) {
                // Fire on UP, not DOWN: dismissing removes this FOCUSED window,
                // and acting on the DOWN would orphan the UP into whatever sits
                // beneath — the game itself in the drag-lookup flow. Release-
                // to-dismiss is also the console idiom. The down-seen gate
                // keeps a B held from before the lens appeared from dismissing
                // it on release.
                when (ev.action) {
                    KeyEvent.ACTION_DOWN -> if (ev.repeatCount == 0) backDownSeen = true
                    KeyEvent.ACTION_UP -> if (backDownSeen) {
                        backDownSeen = false
                        onDismissKey?.invoke()
                    }
                }
                return true
            }
            if (interactive && lens.handleNavKey(ev)) return true
            return super.dispatchKeyEvent(ev)
        }

        init {
            // Transparent: only [lens] paints. The rest of the screen-sized
            // root is an invisible touch catcher.
            background = null
            // Absolute LEFT, not relative START: this is a root WindowManager
            // window, so ViewRootImpl stamps the configuration's layout
            // direction on it and an Arabic system locale turns it RTL — where
            // START resolves to RIGHT and lays the column out at
            // `viewW - lensW` before [setLensX]'s translationX is even added.
            // That also desynchronises [dispatchTouchEvent], which hit-tests
            // the column as `lens.translationX ..+ lensW` on the assumption
            // that lens.left is 0.
            addView(
                lens,
                LayoutParams(lensW, LayoutParams.MATCH_PARENT, Gravity.TOP or Gravity.LEFT),
            )
        }

        /** Horizontal offset of the lens column within the full-screen root. */
        fun setLensX(x: Int) {
            lens.translationX = x.toFloat()
        }

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (interactive && ev.actionMasked == MotionEvent.ACTION_DOWN) {
                val left = lens.translationX
                if (ev.x < left || ev.x >= left + lensW) {
                    onOffCardTap?.invoke()
                    return true   // consume: the tap dismisses; it does not pass through
                }
            }
            return super.dispatchTouchEvent(ev)
        }
    }

    /**
     * Card-width view for the redesigned lens. Sits inside [LensRoot] and is
     * slid horizontally to follow the finger; vertically the card is placed at
     * the screen Y given by [bodyTopOffset] and grows there. The pill/chip halo
     * overhangs one card edge and the arrow strip the other (top/bottom swap
     * with the flip state).
     *
     * Card panel (background + border) is painted on canvas in [draw] so we
     * can clip the zoomed pixels and the inset shadow to the rounded-rect
     * shape. Pill + chips are real child views overlaid on top.
     */
    private class LensView(
        ctx: Context,
        private val cardW: Int,
        private val viewW: Int,
        private val lensH: Int,
        private val chipHaloXPx: Int,
        private val pillChipOverhangPx: Int,
        private val cardCornerR: Float,
        private val zoom: Float,
        private val arrowSizePx: Int,
        private val pillHeightPx: Int,
        private val chipVisDiameterPx: Int,
        private val chipHitSizePx: Int,
        private val chipHaloPadPx: Int,
        private val density: Float,
        private val onOpenTap: () -> Unit,
        private val onSecondaryOpenTap: (Int) -> Unit,
        private val onAnkiTap: () -> Unit,
        private val onAnkiLongPress: () -> Unit,
        private val onSpeakTap: () -> Unit,
        private val showAnkiChip: Boolean,
        /** Styled body reported a (new) painted height — the owner re-runs
         *  its card-height fit (LensView is not an inner class). */
        private val onStyledHeightChanged: () -> Unit,
    ) : FrameLayout(ctx) {
        private fun dp(v: Float): Int = (density * v).toInt()
        /** sp → px through the display metrics, so the pill's fit measures text
         *  the way [TextView.setTextSize] will render it. [density] alone
         *  ignores the user's font scale and under-measures every string on a
         *  large-text device — which lets the fitted pill overrun its cap. */
        private fun spPx(v: Float): Float =
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)
        /** Replace the alpha byte of [color] with [alpha] (0..255). Used to
         *  layer the spec's design alphas onto themed RGB tokens — e.g.
         *  the card border is the theme's primary-text color at 16%. */
        private fun withAlpha(color: Int, alpha: Int): Int =
            (color and 0x00FFFFFF) or ((alpha and 0xFF) shl 24)
        private val cardBorderPx = density * 1f

        private enum class Mode { ZOOM, DEFINITIONS, LOADING }
        private var mode: Mode = Mode.ZOOM

        private var lensFlipped = false
        /** Current card-body height. Defaults to [lensH] (the zoom card and
         *  every drag/dwell surface stay at the base height); the controller
         *  grows it via [setCardHeight] after release so the dictionary can
         *  show more rows than the base 120dp allows. The grown card never
         *  appears in ZOOM mode, so the ZOOM-only chrome (inset shadow, clip
         *  path, zoom blit, crosshair) intentionally stays pinned to [lensH]. */
        private var cardHeightPx: Int = lensH
        /** Override for the card's top edge within the view. Non-null only
         *  while the card grows inside a pre-sized window (the not-flipped
         *  case, where the card is bottom-anchored, so its top must ride up
         *  as it grows rather than sitting at the fixed pill/chip overhang).
         *  Null restores the flip-default. */
        private var bodyTopOverride: Int? = null
        /** Y of the card's top edge within the view. */
        private val bodyTopOffset: Int
            get() = bodyTopOverride ?: if (lensFlipped) arrowSizePx else pillChipOverhangPx
        private val cardBottomInView: Int get() = bodyTopOffset + cardHeightPx
        /** Y of the line the pill is centered on — the card edge opposite
         *  the arrow (top when not flipped, bottom when flipped). */
        private val pillAnchorY: Int
            get() = if (lensFlipped) cardBottomInView else bodyTopOffset
        private val cardLeftInView: Int get() = chipHaloXPx
        private val cardRightInView: Int get() = chipHaloXPx + cardW
        /** Vertical extent of the lens chrome (card body + the pill/chip
         *  overhang and arrow on its two edges) within the full-height window.
         *  Taps outside this band — the transparent area above/below the lens —
         *  dismiss in sticky mode. */
        private val contentTopInView: Int
            get() = bodyTopOffset - if (lensFlipped) arrowSizePx else pillChipOverhangPx
        private val contentBottomInView: Int
            get() = cardBottomInView + if (lensFlipped) pillChipOverhangPx else arrowSizePx

        private var arrowVisible = false
        private var arrowOffsetX = 0

        // Every color comes from the theme stack (resolved through the
        // ContextThemeWrapper [MagnifierLens] built around the raw service
        // context). The design's hex tokens — BWI_PANEL #15181B, the
        // white-16% border, the brand #8B3F2D arrow — are the dark theme
        // pt_* palette in disguise; using R.attr.pt* keeps the lens
        // tracking the user's mode + accent instead of pinning to coral.
        private val accentColor = ctx.themeColor(R.attr.ptAccent)
        private val accentOnColor = ctx.themeColor(R.attr.ptAccentOn)
        private val cardBgColor = ctx.themeColor(R.attr.ptSurface)
        private val cardBorderColor = ctx.themeColor(R.attr.ptOutline)
        // Sticky-mode arrow's fill matches the card panel so the
        // triangle reads as a contiguous extension of the card. The
        // two slanted edges are stroked with the same card border so
        // the outline wraps the arrow continuously with the panel.
        // The base is left unstroked — it sits on the card edge and
        // the card border carries through underneath.
        private val chipBgColor = withAlpha(ctx.themeColor(R.attr.ptSurface), 240)  // 0.94
        private val chipBorderColor = withAlpha(ctx.themeColor(R.attr.ptText), 56)  // 0.22
        private val chipIconColor = withAlpha(ctx.themeColor(R.attr.ptText), 209)  // 0.82
        // Pill ink alphas mirror the spec (1.0 / 0.22 / 0.72 / 0.5) applied
        // over the accent-paired ink color so a non-coral accent still
        // gets readable ink on its pill.
        private val pillInkColor = accentOnColor
        private val pillInkDivider = withAlpha(accentOnColor, 0x38)
        private val pillInkReading = withAlpha(accentOnColor, 0xB8)
        private val panelPrimaryText = ctx.themeColor(R.attr.ptText)
        private val panelSecondaryText = ctx.themeColor(R.attr.ptTextMuted)
        // Badge uses ptCard (one step lighter than the lens panel's
        // ptSurface) so it visibly separates from the body behind it.
        private val panelBadgeBg = ctx.themeColor(R.attr.ptCard)
        private val panelWarnColor = ctx.themeColor(R.attr.ptWarning)

        private val cardBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = cardBgColor
            style = Paint.Style.FILL
        }
        private val cardBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = cardBorderColor
            style = Paint.Style.STROKE
            strokeWidth = cardBorderPx
        }
        private val backgroundPaint = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.FILL
        }
        private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val arrowFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = cardBgColor
            style = Paint.Style.FILL
        }
        private val arrowPath = Path()
        private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accentColor
            style = Paint.Style.STROKE
            strokeWidth = density * 1.5f
            strokeCap = Paint.Cap.ROUND
        }
        private val crosshairHalfLen = density * 6f

        /** Soft inner shadow that recesses the zoom under the card border.
         *  Pre-rendered into a software-allocated ARGB bitmap because the
         *  BlurMaskFilter only produces its blur on a software canvas — by
         *  baking the shadow once here, the host view can stay on the
         *  default (hardware-accelerated) layer pipeline, which was the
         *  source of the lightly-translucent ghost that previously
         *  followed the lens during drag. */
        private val insetShadowBitmap: Bitmap = run {
            val bitmap = createBitmap(cardW, lensH, Bitmap.Config.ARGB_8888)
            val bmCanvas = Canvas(bitmap)
            val shadowClip = Path().apply {
                addRoundRect(
                    0f, 0f, cardW.toFloat(), lensH.toFloat(),
                    cardCornerR, cardCornerR, Path.Direction.CW,
                )
            }
            bmCanvas.clipPath(shadowClip)
            val inset = density * 4f
            val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(45, 0, 0, 0)
                style = Paint.Style.STROKE
                strokeWidth = density * 14f
                maskFilter = BlurMaskFilter(density * 8f, BlurMaskFilter.Blur.NORMAL)
            }
            bmCanvas.drawRoundRect(
                inset, inset, cardW - inset, lensH - inset,
                cardCornerR - inset, cardCornerR - inset,
                shadowPaint,
            )
            bitmap
        }

        // -----------------------------------------------------------------
        // Pill: word | reading > (chevron)
        // -----------------------------------------------------------------
        private val pillPaddingLead = dp(18f)
        private val pillPaddingTrail = dp(14f)
        private val pillGap = dp(12f)
        private val pillDividerWidth = dp(2f)
        private val pillDividerHeight = dp(22f)
        private val pillChevronSize = dp(13f)
        private val pillChevronMarginStart = dp(4f)
        private val pillPlaceholderIconSize = dp(22f)
        private val pillPlaceholderGap = dp(8f)
        private val pillPlaceholderText = "Find a word"
        private val pillWordSp = 24f
        private val pillReadingMaxSp = 14f
        private val pillReadingMinSp = 11f
        /** Placeholder icon shown when the finger isn't over a token. */
        private val pillPlaceholderIconView = ImageView(ctx).apply {
            val d = AppCompatResources.getDrawable(ctx, R.drawable.ic_lens_search)?.mutate()
            if (d != null) {
                DrawableCompat.setTint(d, pillInkColor)
                setImageDrawable(d)
            }
            val params = LinearLayout.LayoutParams(pillPlaceholderIconSize, pillPlaceholderIconSize)
            // Pull the placeholder icon + label 8dp left of the leading
            // padding so the icon's left edge optically aligns with the
            // word-state's leading edge. The pill turns off
            // clipToPadding so this negative inset is honored (otherwise
            // the icon would be cut off at the padding boundary).
            params.marginStart = -dp(8f)
            params.marginEnd = pillPlaceholderGap
            layoutParams = params
            visibility = GONE
        }
        /** Spinner shown alone in the pill while the drag-start OCR job
         *  runs — no accompanying label. The icon's negative leading inset
         *  (for optical alignment with the Word state's leading edge) is
         *  intentionally omitted: the spinner stands alone, so the pill's
         *  natural padding centers it cleanly. */
        private val pillPlaceholderSpinnerView = ProgressBar(ctx).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(pillInkColor)
            layoutParams = LinearLayout.LayoutParams(pillPlaceholderIconSize, pillPlaceholderIconSize)
            visibility = GONE
        }
        /** Placeholder label paired with [pillPlaceholderIconView]. */
        private val pillPlaceholderTextView = TextView(ctx).apply {
            text = pillPlaceholderText
            setTextColor(pillInkColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, pillReadingMaxSp)
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            visibility = GONE
        }
        private val pillWordView = TextView(ctx).apply {
            setTextColor(pillInkColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, pillWordSp)
            typeface = Typeface.DEFAULT_BOLD
            // CSS letter-spacing 0.3 at 24px ≈ 0.0125 em.
            letterSpacing = 0.0125f
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        private val pillDividerView = View(ctx).apply {
            setBackgroundColor(pillInkDivider)
            val params = LinearLayout.LayoutParams(pillDividerWidth, pillDividerHeight)
            params.marginStart = pillGap
            params.marginEnd = pillGap
            layoutParams = params
        }
        private val pillReadingView = TextView(ctx).apply {
            setTextColor(pillInkReading)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, pillReadingMaxSp)
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        private val pillChevronView = ImageView(ctx).apply {
            val d = AppCompatResources.getDrawable(ctx, R.drawable.ic_lens_chevron)?.mutate()
            if (d != null) {
                DrawableCompat.setTint(d, pillInkColor)
                setImageDrawable(d)
            }
            val params = LinearLayout.LayoutParams(pillChevronSize, pillChevronSize)
            params.marginStart = pillChevronMarginStart
            layoutParams = params
        }
        private val pillView = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pillPaddingLead, 0, pillPaddingTrail, 0)
            // Allow the placeholder icon's negative marginStart to draw
            // into the leading padding area instead of being clipped at
            // the padding boundary.
            clipToPadding = false
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(accentColor)
                // Hairline outline matching the card border so the pill
                // reads as part of the same family.
                setStroke(density.toInt().coerceAtLeast(1), cardBorderColor)
                // 99dp in spec; equivalent to "fully rounded capsule" — i.e.
                // corner radius >= half the pill height.
                cornerRadius = pillHeightPx / 2f
            }
            // Placeholder children added first so they pack to the leading
            // edge when shown; they're GONE in word state, leaving the
            // word/divider/reading/chevron to take their place.
            addView(pillPlaceholderIconView)
            addView(pillPlaceholderSpinnerView)
            addView(pillPlaceholderTextView)
            addView(pillWordView)
            addView(pillDividerView)
            addView(pillReadingView)
            addView(pillChevronView)
            // Pill is hidden until [setLabel] applies a state (placeholder
            // or word) on the controller's first label call after show().
            visibility = GONE
        }
        // Manual sizing for the reading: shrink 1sp at a time down to 11sp
        // so a long reading still fits inside the pill, rather than
        // ellipsizing or pushing the chevron off the pill. Word stays at
        // 24sp because the spec considers the word the headline.
        private val pillWordSizingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.0125f
        }
        private val pillReadingSizingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.DEFAULT_BOLD
        }

        // -----------------------------------------------------------------
        // Chips: Speak (left) and Anki (right) route through the lens's
        // [onSpeakTap] / [onAnkiTap] callbacks — wired in [DragLookupController].
        // -----------------------------------------------------------------
        /** Gap between the chip's visible disk and the pill's outer edge
         *  in the resting (post-reveal) layout. */
        private val chipPillGapPx = dp(14f)
        /** Horizontal space one chip claims beside the pill: the outer half of
         *  its hit halo, its visible disk, and the gap to the pill. */
        private val chipLanePx = chipHaloPadPx + chipVisDiameterPx + chipPillGapPx
        /** Hard cap on the pill's width — what's left of the host view once
         *  both chip lanes are reserved. [fitPillText] sizes and bounds the
         *  pill's text against it, which is what keeps [revealChips] able to
         *  seat both chips inside the frame however long the word is. */
        private val pillMaxWidthPx = computeMaxPillWidth(viewW, chipLanePx)

        // Speak chip's loading spinner — swapped in for the icon while a TTS
        // request is in flight (see [setSpeakChipLoading]).
        private val leftChipSpinner = ProgressBar(
            context, null, android.R.attr.progressBarStyleSmall,
        ).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(chipIconColor)
            layoutParams = LayoutParams(dp(16f), dp(16f), Gravity.CENTER)
            visibility = GONE
        }
        private val leftChipIcon = makeChipIcon(R.drawable.ic_lens_speak)
        private val leftChip = makeChip(
            { onSpeakTap() },
            null,
            leftChipIcon, leftChipSpinner,
        )
        // Anki chip: tap fires onAnkiTap (which one-tap or sheet-open),
        // long-press always fires onAnkiLongPress (which opens the
        // editable review sheet) — wired to whichever callback the
        // surrounding flow supplies.
        private val rightChip = makeChip(
            { onAnkiTap() },
            { onAnkiLongPress() },
            makeChipIcon(R.drawable.ic_card_stack_add),
        )

        /** Build a chip: a clickable disk with [content] views centered on it.
         *  Content (icon, optional spinner) is supplied so the caller can keep
         *  references for state swaps. [onLongClick], when non-null, fires
         *  on long-press and consumes the gesture (returns true). */
        private fun makeChip(
            onClick: () -> Unit,
            onLongClick: (() -> Unit)? = null,
            vararg content: View,
        ): FrameLayout {
            val chip = FrameLayout(context).apply {
                isClickable = true
                setOnClickListener { onClick() }
                if (onLongClick != null) {
                    setOnLongClickListener {
                        onLongClick()
                        true
                    }
                }
                visibility = GONE
            }
            val disk = View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(chipBgColor)
                    setStroke(density.toInt().coerceAtLeast(1), chipBorderColor)
                }
                layoutParams = LayoutParams(
                    chipVisDiameterPx, chipVisDiameterPx,
                    Gravity.CENTER,
                )
            }
            chip.addView(disk)
            content.forEach { chip.addView(it) }
            return chip
        }

        private fun makeChipIcon(iconRes: Int): ImageView = ImageView(context).apply {
            val d = AppCompatResources.getDrawable(context, iconRes)?.mutate()
            if (d != null) {
                DrawableCompat.setTint(d, chipIconColor)
                setImageDrawable(d)
            }
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            layoutParams = LayoutParams(dp(16f), dp(16f), Gravity.CENTER)
        }

        /** Swap the Speak chip between its icon and a loading spinner, shown
         *  while a TTS request is in flight. */
        fun setSpeakChipLoading(loading: Boolean) {
            leftChipIcon.visibility = if (loading) GONE else VISIBLE
            leftChipSpinner.visibility = if (loading) VISIBLE else GONE
        }

        // -----------------------------------------------------------------
        // Body: definitions ScrollView, occupies the full card area.
        //   - Top padding clears the pill so the first row doesn't start
        //     under it. With `clipToPadding = false`, scrolling reveals
        //     the content sliding under the pill (the pill is drawn on
        //     top of the ScrollView in the FrameLayout z-order).
        //   - Horizontal padding sits on the inner `definitionsContent`
        //     so the scrolling surface itself extends to the card's
        //     left and right edges, while the rows stay inset.
        // -----------------------------------------------------------------
        private val bodyHPaddingPx = dp(18f)
        private val bodyPillSidePadPx = dp(26f)
        private val bodyOuterSidePadPx = dp(12f)
        /** Tiny buffer between the scroll view and the card's inner
         *  edges on all four sides, so the rounded corners don't graze
         *  the scrollbar / content edges, and scrolled glyphs clip
         *  short of the 1dp card border instead of overdrawing it. */
        private val bodyEdgeBufferPx = dp(2f)
        private val definitionsContent = WordDefinitionsView(ctx).apply {
            // Asymmetric horizontal padding: -6dp on the left, +2dp on
            // the right, so the text sits optically centered against the
            // right-side scrollbar gutter.
            setPadding(bodyHPaddingPx - dp(6f), 0, bodyHPaddingPx + dp(2f), 0)
            // This panel is itself ptSurface — the view's default ptSurface
            // chip fill would vanish into it.
            metaChipFill = panelBadgeBg
            // No-entry words keep the lens up (instead of dismissing) with
            // this placeholder body; the pill still shows the word. Covers
            // both the drag lens and the in-app tap-word lens.
            emptyPlaceholder = ctx.getString(R.string.word_detail_no_definitions)
        }
        /** Split (phrase + word) body — built per bind by
         *  [setSplitDefinitions], visibility-swapped against the flat and
         *  styled renderers. */
        private val splitContent = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = GONE
        }
        /** True while [splitContent] is the visible body. Drives the
         *  height fit, the tap-eligibility carve-out (sections own their
         *  taps), and the controller-nav candidate list. */
        private var splitActive = false
        /** The split body's tappable section roots in VISUAL order (top →
         *  bottom) — the nav walk's candidates and activation targets
         *  (activation is the section's own click, so the wiring lives in
         *  one place). Non-opening sections aren't listed. */
        private var splitSectionViews: List<View> = emptyList()

        /** The scroll's single child: the flat renderer plus the split
         *  container plus (lazily) the styled WebView renderer,
         *  visibility-swapped per bind. Keeping the ScrollView as the ONE
         *  scroller preserves the stick-scroll repeater and the
         *  clip/translation grow animation unchanged — the styled view is
         *  always sized to its full content height and never scrolls itself
         *  ([YomitanDefinitionsView]'s contract). */
        private val bodyContainer = FrameLayout(ctx).apply {
            addView(definitionsContent)
            addView(
                splitContent,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
            )
        }
        private val definitionsScroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = true
            isFillViewport = false
            // clipToPadding stays false so scrolled rows fill the body
            // edge to edge rather than stopping at the padding strip.
            // Text is kept off the card border a different way: the
            // scroll view's layout bounds are inset by [bodyEdgeBufferPx]
            // on every side (see [updateChromeLayout]), so glyphs clip a
            // hair short of the 1dp border instead of overdrawing it and
            // leaving white pixel fragments along the bottom edge.
            clipToPadding = false
            // Default pad: pill is at the top, so the bigger pad is on top.
            setPadding(0, bodyPillSidePadPx, 0, bodyOuterSidePadPx)
            addView(bodyContainer)
            visibility = GONE
        }

        // ── Styled (WebView) definitions state ────────────────────────
        private var styledView: YomitanDefinitionsView? = null
        /** Construction failed once (no WebView provider) — don't retry
         *  per bind. */
        private var styledUnavailable = false
        /** A styled bind is in flight: flat content is showing and the
         *  page hasn't reported its painted height yet. */
        private var pendingStyledSwap = false
        /** The styled view is the visible body (drives height fitting). */
        var styledActive = false
            private set
        private var styledContentHeight = 0

        // ── Split-section styled (WebView) renderers ──────────────────
        /** Pool of per-SECTION styled renderers for the split body, handed
         *  out in section order by [obtainSplitStyledView] and reused across
         *  binds — WebView construction is the expensive part, content swaps
         *  are cheap, and the drag lens rebinds on every dwell. Detached
         *  from their section columns between binds; destroyed only with the
         *  window ([releaseStyledView]) or on renderer death. Distinct from
         *  [styledView] (the single-unit body's renderer) so the two modes
         *  can't fight over one instance's height reports. */
        private val splitStyledPool = mutableListOf<YomitanDefinitionsView>()
        /** Next pool slot to hand out during the current split build. */
        private var splitStyledCursor = 0
        /** Bind generation for the split styled closures, bumped at every
         *  [hideSplitBody]: a pooled view's late height report from a
         *  superseded bind must not resurrect detached section views or
         *  re-fit the card ([YomitanDefinitionsView]'s own render-seq guard
         *  covers reports superseded by a NEW setContent, but not reports
         *  from content that was simply torn down). */
        private var splitBindSeq = 0

        /** A styled renderer for the next split section: reuse the pool in
         *  order, growing it on demand. Null when styled rendering is
         *  unavailable (no WebView provider / a renderer died) — the
         *  section then simply stays flat. */
        private fun obtainSplitStyledView(): YomitanDefinitionsView? {
            if (styledUnavailable) return null
            splitStyledPool.getOrNull(splitStyledCursor)?.let { pooled ->
                splitStyledCursor++
                (pooled.parent as? ViewGroup)?.removeView(pooled)
                return pooled
            }
            val v = YomitanDefinitionsView(context, lensStyledTokens())
            if (!v.isUsable()) {
                styledUnavailable = true
                return null
            }
            // Symmetric insets, matching the split sections' flat bodies
            // (not the single body's scrollbar-gutter asymmetry).
            v.setPadding(bodyHPaddingPx - dp(6f), 0, bodyHPaddingPx - dp(6f), 0)
            splitStyledPool.add(v)
            splitStyledCursor++
            return v
        }

        private fun lensStyledTokens() = DefinitionsDocument.Tokens(
            text = context.themeColor(R.attr.ptText),
            textMuted = context.themeColor(R.attr.ptTextMuted),
            textHint = context.themeColor(R.attr.ptTextHint),
            accent = accentColor,
            panel = context.themeColor(R.attr.ptSurface),
            baseFontSizePx = 16.5f * LENS_DEFINITIONS_SCALE,
        )

        private fun ensureStyledView(): YomitanDefinitionsView? {
            styledView?.let { return it }
            if (styledUnavailable) return null
            val v = YomitanDefinitionsView(context, lensStyledTokens())
            if (!v.isUsable()) {
                styledUnavailable = true
                return null
            }
            // Same optical inset as the flat renderer.
            v.setPadding(bodyHPaddingPx - dp(6f), 0, bodyHPaddingPx + dp(2f), 0)
            // INVISIBLE at 1px, never GONE: a GONE view is never laid out,
            // so the page's viewport would stay zero-width and the first
            // swap would measure content wrapped at every character — the
            // enormous bogus height that then sticks. INVISIBLE keeps the
            // WebView laid out at the container's real width (JS still
            // runs; only drawing is skipped) while contributing 1px to the
            // container until the first real height arrives.
            v.visibility = INVISIBLE
            v.onContentHeight = { h -> onStyledContentHeight(h) }
            v.onRendererGone = { onStyledRendererGone() }
            // Non-link taps on the page open the detail view — the page's
            // own hit testing decides, replacing the tap detector over this
            // area (see [isTapEligible]). Same debounce as the detector
            // path, so a gesture crossing both regions can't double-fire.
            v.onBodyTap = { fireOpenTap() }
            bodyContainer.addView(v, LayoutParams(LayoutParams.MATCH_PARENT, 1))
            styledView = v
            return v
        }

        /** Painted-height report from the page: size the styled view, and
         *  if this bind was waiting to swap, reveal it now — the flat
         *  content showed instantly and the styled render replaces it only
         *  once it actually has pixels (no blank-panel gap). */
        private fun onStyledContentHeight(h: Int) {
            val sv = styledView ?: return
            if (h <= 0) {
                pendingStyledSwap = false
                return
            }
            // Neither pending nor live: the bind this report belongs to was
            // superseded WITHOUT a new setContent on this view (a split or
            // loading rebind — [showFlatBody] parked it at 1px), so the
            // component's render-generation guard can't catch it. Sizing the
            // INVISIBLE view here would silently stretch the scroll range
            // under whatever body is now showing.
            if (!pendingStyledSwap && !styledActive) return
            styledContentHeight = h
            sv.layoutParams = (sv.layoutParams as? LayoutParams
                ?: LayoutParams(LayoutParams.MATCH_PARENT, h)).apply { height = h }
            if (pendingStyledSwap) {
                pendingStyledSwap = false
                styledActive = true
                sv.visibility = VISIBLE
                definitionsContent.visibility = GONE
            }
            if (styledActive) onStyledHeightChanged()
        }

        /** Full teardown of the styled renderer, for the window-removal
         *  funnel ([removeOverlayInternal]). Safe to call with no styled
         *  view; idempotent. */
        fun releaseStyledView() {
            styledView?.let {
                it.destroy()
                bodyContainer.removeView(it)
            }
            styledView = null
            styledActive = false
            pendingStyledSwap = false
            splitStyledPool.forEach {
                it.destroy()
                (it.parent as? ViewGroup)?.removeView(it)
            }
            splitStyledPool.clear()
        }

        /** Render process death: drop the instance and stay flat — the
         *  flat content of the current bind is already in the tree. */
        private fun onStyledRendererGone() {
            styledView?.let { bodyContainer.removeView(it) }
            styledView = null
            styledUnavailable = true
            styledActive = false
            pendingStyledSwap = false
            definitionsContent.visibility = VISIBLE
        }

        /** Back to the flat renderer as the visible body (loading, ZOOM,
         *  or a bind with no styled payload). The styled view drops to
         *  INVISIBLE at 1px — not GONE (it must stay laid out at real
         *  width for the next swap's measurement), and not its old height
         *  (an INVISIBLE view still counts toward the container's
         *  wrap-content measure; a stale tall view would leave the scroll
         *  full of blank space under the flat content). The split body is
         *  fully torn down — unlike the styled view it has no deferred
         *  measurement to keep warm, and stale section views must not stay
         *  controller-nav candidates. */
        private fun showFlatBody() {
            styledActive = false
            pendingStyledSwap = false
            styledView?.let { sv ->
                sv.visibility = INVISIBLE
                (sv.layoutParams as? LayoutParams)?.let { lp ->
                    if (lp.height != 1) {
                        lp.height = 1
                        sv.layoutParams = lp
                    }
                }
            }
            hideSplitBody()
            definitionsContent.visibility = VISIBLE
        }

        private fun hideSplitBody() {
            splitActive = false
            splitBindSeq++
            applySectionTint(null)
            splitSectionViews = emptyList()
            // Pool views come OUT of the dying section columns (they're
            // reused next split bind — never destroyed here); the columns
            // themselves are discarded wholesale.
            splitStyledPool.forEach { (it.parent as? ViewGroup)?.removeView(it) }
            splitStyledCursor = 0
            splitContent.removeAllViews()
            splitContent.visibility = GONE
        }

        private val clipPath = Path()
        private val srcRect = Rect()
        private val dstRect = RectF()
        private val cardRect = RectF()
        private val cardStrokeRect = RectF()

        private var sourceBitmap: Bitmap? = null
        private var sourceX = 0f
        private var sourceY = 0f
        private var sourceScreenW = 0
        private var sourceScreenH = 0

        /** Controller cursor's ring — same renderer as the capture sheet's. */
        private val focusRing = FocusRingView(ctx)

        init {
            setWillNotDraw(false)
            isFocusable = true
            isFocusableInTouchMode = true
            // Disable the default focus highlight — the framework otherwise
            // paints a translucent rectangle over the entire focusable view
            // when it gains focus (e.g. when [attachInteractiveListeners]
            // calls requestFocus), which reads as a screen-shaped darkening
            // over the lens area.
            defaultFocusHighlightEnabled = false
            // No background — only the rounded card region painted in
            // onDraw should be opaque. Explicit to defend against any
            // default selector that a Material-themed context might apply
            // to focusable views.
            background = null
            addView(definitionsScroll)
            addView(leftChip)
            addView(rightChip)
            addView(pillView)
            // Topmost; a plain non-clickable View, so lens touches fall
            // through it to the pill/chips/card beneath.
            addView(focusRing, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            rebuildClipPath()
            updateChromeLayout()
        }

        /** Rounded-rect path that clips the card body (zoom pixels + inset
         *  shadow) to the card's shape. Called from init and whenever the
         *  flip state changes. */
        private fun rebuildClipPath() {
            clipPath.reset()
            val top = bodyTopOffset.toFloat()
            val left = cardLeftInView.toFloat()
            clipPath.addRoundRect(
                left, top, left + cardW.toFloat(), top + lensH.toFloat(),
                cardCornerR, cardCornerR, Path.Direction.CW,
            )
        }

        /** Recompute layout params for the definitions scroll, pill, and
         *  chips based on [lensFlipped]. The pill sits on the card edge
         *  opposite the arrow; the chips share its vertical center; the
         *  scroll's larger top/bottom pad always faces the pill so the
         *  first content row clears it. */
        private fun updateChromeLayout() {
            // Scroll view occupies the card region inset by
            // [bodyEdgeBufferPx] on every side — the inset keeps
            // scrolled glyphs off the 1dp card border. Its top/bottom
            // padding flips with the lens so the larger pad (which
            // clears the pill) always faces the pill side.
            val scrollTopPad = if (lensFlipped) bodyOuterSidePadPx else bodyPillSidePadPx
            val scrollBottomPad = if (lensFlipped) bodyPillSidePadPx else bodyOuterSidePadPx
            definitionsScroll.setPadding(0, scrollTopPad, 0, scrollBottomPad)
            // Absolute LEFT + leftMargin throughout this method (never
            // START/marginStart): every offset here is a view-pixel coordinate
            // shared with the Canvas geometry — [cardLeftInView], the clip
            // path, the crosshair — and canvas coordinates do not mirror. Under
            // an RTL system locale the relative spelling would slide the placed
            // chrome off the card the clip path still paints in place.
            definitionsScroll.layoutParams = LayoutParams(
                cardW - 2 * bodyEdgeBufferPx, cardHeightPx - 2 * bodyEdgeBufferPx,
                Gravity.LEFT or Gravity.TOP,
            ).apply {
                leftMargin = chipHaloXPx + bodyEdgeBufferPx
                topMargin = bodyTopOffset + bodyEdgeBufferPx
            }

            // Pill — centered on pillAnchorY (= card top or card bottom).
            val pillTopMargin = pillAnchorY - pillHeightPx / 2
            pillView.layoutParams = LayoutParams(
                LayoutParams.WRAP_CONTENT, pillHeightPx,
                Gravity.CENTER_HORIZONTAL or Gravity.TOP,
            ).apply { topMargin = pillTopMargin }

            // Chips — vertically centered on the pill's vertical center
            // (which is the card edge the pill is anchored to). Same
            // formula in both flip states.
            val chipTopMargin = pillAnchorY - chipHitSizePx / 2
            leftChip.layoutParams = LayoutParams(
                chipHitSizePx, chipHitSizePx,
                Gravity.LEFT or Gravity.TOP,
            ).apply { topMargin = chipTopMargin }
            rightChip.layoutParams = LayoutParams(
                chipHitSizePx, chipHitSizePx,
                Gravity.RIGHT or Gravity.TOP,
            ).apply { topMargin = chipTopMargin }
        }

        /** Apply only the [cardHeightPx]-dependent geometry: the scroll
         *  viewport height and the pill/chip vertical anchor. Mutates the
         *  existing layout params in place rather than replacing them, so a
         *  concurrent pill-WIDTH tween ([animatePillStateTransition], which
         *  runs on the same release) isn't clobbered back to WRAP_CONTENT.
         *  The scroll's width/margins and the pill's width/gravity were set
         *  by the full [updateChromeLayout] (init + flip) and don't change
         *  with height; only the scroll height, and — when flipped — the
         *  pill/chip top margins, move. */
        private fun applyCardHeightGeometry(
            layoutHeightPx: Int = cardHeightPx,
            layoutTopPx: Int = bodyTopOffset,
        ) {
            // Scroll viewport tracks the card body: height = card height (less
            // the edge inset); top rides the card top (which moves when the
            // card is bottom-anchored mid-grow). The grow path passes the END
            // state here once and animates draw-only (see [beginCardGrow]).
            (definitionsScroll.layoutParams as? LayoutParams)?.let {
                it.height = layoutHeightPx - 2 * bodyEdgeBufferPx
                it.topMargin = layoutTopPx + bodyEdgeBufferPx
                definitionsScroll.layoutParams = it
            }
            val pillAnchor = if (lensFlipped) layoutTopPx + layoutHeightPx else layoutTopPx
            (pillView.layoutParams as? LayoutParams)?.let {
                it.topMargin = pillAnchor - pillHeightPx / 2
                pillView.layoutParams = it
            }
            val chipTopMargin = pillAnchor - chipHitSizePx / 2
            (leftChip.layoutParams as? LayoutParams)?.let {
                it.topMargin = chipTopMargin
                leftChip.layoutParams = it
            }
            (rightChip.layoutParams as? LayoutParams)?.let {
                it.topMargin = chipTopMargin
                rightChip.layoutParams = it
            }
        }

        /** Resize the card body and optionally reposition its top within the
         *  window. The controller grows the card inside a window it pre-sized
         *  to the final bounds, so no per-frame window resize is needed (which
         *  would flash the overlay at its gravity anchor). [topOverride]
         *  non-null bottom-anchors the card (the not-flipped grow, where the
         *  card top rides up as it grows); null restores the flip-default.
         *  The card fill, border, and arrow read [cardHeightPx]/[bodyTopOffset]
         *  on the next draw, so an [invalidate] follows the relayout. */
        fun setCardGeometry(heightPx: Int, topOverride: Int?) {
            // A direct geometry set supersedes any in-flight grow's pinned
            // end-state layout — reset the draw-only ride first.
            if (growLayoutTarget != null) clearGrowDrawState()
            if (heightPx == cardHeightPx && topOverride == bodyTopOverride) return
            cardHeightPx = heightPx
            bodyTopOverride = topOverride
            applyCardHeightGeometry()
            // The card now moves within the fixed full-height window (its top
            // shifts as the finger moves and as it grows), so the zoom clip
            // must follow it — it's keyed off bodyTopOffset.
            rebuildClipPath()
            invalidate()
        }

        /** Non-null while the release grow animates: (final card height, final
         *  card top). [beginCardGrow] lays the scroll/pill/chips out ONCE at
         *  that end-state; each frame is then draw-only — the card chrome reads
         *  [cardHeightPx]/[bodyTopOverride] at draw time, and the children ride
         *  translationY + clipBounds ([setGrowFrame]) instead of re-measuring
         *  the dictionary subtree per frame. */
        private var growLayoutTarget: Pair<Int, Int>? = null

        /** Start a draw-only grow toward [finalHeightPx]/[finalTopPx]: one
         *  layout traversal at the end-state, then [setGrowFrame] per frame. */
        fun beginCardGrow(finalHeightPx: Int, finalTopPx: Int) {
            growLayoutTarget = finalHeightPx to finalTopPx
            applyCardHeightGeometry(finalHeightPx, finalTopPx)
        }

        /** One grow-animation frame: update the drawn card rect and slide the
         *  end-state-laid-out children to match via translation + clip. Falls
         *  back to a full [setCardGeometry] if no grow is pinned. */
        fun setGrowFrame(heightPx: Int, topPx: Int) {
            val (finalH, finalTop) = growLayoutTarget ?: run {
                setCardGeometry(heightPx, topPx)
                return
            }
            cardHeightPx = heightPx
            bodyTopOverride = topPx
            // Content rides the card top (laid out at the final top, shifted
            // back to the animated top), clipped to the animated card body.
            val scrollShift = (topPx - finalTop).toFloat()
            definitionsScroll.translationY = scrollShift
            definitionsScroll.clipBounds = Rect(
                0, 0, definitionsScroll.width,
                (heightPx - 2 * bodyEdgeBufferPx).coerceAtLeast(0),
            )
            // Pill + chips are anchored to the card edge opposite the arrow —
            // top when not flipped (rides with the content), bottom when
            // flipped (rides the growing bottom edge).
            val pillShift = if (lensFlipped) (heightPx - finalH).toFloat() else scrollShift
            pillView.translationY = pillShift
            leftChip.translationY = pillShift
            rightChip.translationY = pillShift
            rebuildClipPath()
            invalidate()
        }

        /** Settle a finished (or cancelled) grow: snap the drawn card rect to
         *  the pinned end-state and drop the draw-only ride. */
        fun endCardGrow() {
            val (finalH, finalTop) = growLayoutTarget ?: return
            cardHeightPx = finalH
            bodyTopOverride = finalTop
            clearGrowDrawState()
            rebuildClipPath()
            invalidate()
        }

        private fun clearGrowDrawState() {
            growLayoutTarget = null
            definitionsScroll.translationY = 0f
            definitionsScroll.clipBounds = null
            pillView.translationY = 0f
            leftChip.translationY = 0f
            rightChip.translationY = 0f
        }

        /** Resize the card body, restoring the flip-default top position. */
        fun setCardHeight(px: Int) = setCardGeometry(px, null)

        /** Card-body height that would show the full bound definitions
         *  without scrolling: the content's natural height at the scroll's
         *  content width, plus the scroll's pill-side + outer vertical pads
         *  (their sum is flip-invariant) and the 2px edge inset on top and
         *  bottom. Measured against the currently bound [definitionsContent];
         *  call only after [setDefinitions] has bound a word. */
        fun desiredCardHeightForContent(): Int {
            val chrome = bodyPillSidePadPx + bodyOuterSidePadPx + 2 * bodyEdgeBufferPx
            if (splitActive) {
                splitContent.measure(
                    MeasureSpec.makeMeasureSpec(cardW - 2 * bodyEdgeBufferPx, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                )
                return splitContent.measuredHeight + chrome
            }
            // Styled body: a WebView can't report a synchronous measured
            // height — use the page's own painted-height report (which
            // re-runs [MagnifierLens.fitHeightToContent] whenever it lands,
            // so the card converges even though the number arrives late).
            if (styledActive && styledContentHeight > 0) {
                return styledContentHeight + chrome
            }
            definitionsContent.measure(
                MeasureSpec.makeMeasureSpec(cardW - 2 * bodyEdgeBufferPx, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            )
            return definitionsContent.measuredHeight + chrome
        }

        /** Debounce so the open handlers can't fire twice from a single
         *  gesture that crosses the open detector and any other receiver —
         *  shared between the word and phrase opens (one gesture, one open). */
        private var lastOpenTapMs = 0L
        private fun fireOpenTap() {
            val now = SystemClock.uptimeMillis()
            if (now - lastOpenTapMs < 300L) return
            lastOpenTapMs = now
            onOpenTap()
        }

        private fun fireSecondaryOpenTap(index: Int) {
            val now = SystemClock.uptimeMillis()
            if (now - lastOpenTapMs < 300L) return
            lastOpenTapMs = now
            onSecondaryOpenTap(index)
        }

        private val tapDetector = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                fireOpenTap()
                return false
            }
        })

        /** True when the current gesture's DOWN landed on a tap-eligible
         *  region (card body OR pill, NOT on chips OR arrow strip). Gated
         *  on DOWN so a stray UP elsewhere can't fire the open handler. */
        private var tapGestureActive = false
        /** Dismiss callback wired by [attachInteractiveListeners]; used when a
         *  sticky-mode tap lands off the lens chrome (the transparent area of
         *  the full-height window above/below the card). */
        private var dismissRequest: (() -> Unit)? = null
        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            val handled = super.dispatchTouchEvent(ev)
            if (isInteractive) {
                if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                    // The window spans the full screen height; a DOWN outside
                    // the chrome band is an "off the lens" tap → dismiss
                    // (mirrors the ACTION_OUTSIDE dismissal beyond the edges).
                    if (ev.y < contentTopInView || ev.y >= contentBottomInView) {
                        tapGestureActive = false
                        dismissRequest?.invoke()
                        return handled
                    }
                    tapGestureActive = isTapEligible(ev.x, ev.y)
                }
                if (tapGestureActive) {
                    tapDetector.onTouchEvent(ev)
                    // If no child claimed the gesture, claim it here so the parent
                    // keeps delivering MOVE/UP to the tap detector. A tap on the
                    // pill (or chrome overhang) over the transparent area BELOW the
                    // card — the flipped case, where the pill sits at the card's
                    // bottom edge with no scroll view beneath it — leaves [handled]
                    // false; without claiming it, the DOWN isn't consumed, the
                    // parent never delivers the UP, and onSingleTapUp (hence the
                    // open-detail tap) never fires. When a child DID consume (the
                    // card-body scroll, e.g. the not-flipped pill overlapping it),
                    // defer to it so scrolling still works — the detector runs in
                    // parallel and only fires on a real tap.
                    if (!handled) return true
                }
            }
            return handled
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            // The lens window was just torn down. Cancel any in-flight
            // pill-width animation so its end-listener can't fire a
            // layout call on the now-detached view.
            pillAnimator?.cancel()
            pillAnimator = null
        }

        private fun isTapEligible(x: Float, y: Float): Boolean {
            // Arrow strip: opposite side from the pill/chip chrome.
            val arrowTop = if (lensFlipped) 0f else cardBottomInView.toFloat()
            val arrowBottom = arrowTop + arrowSizePx
            if (y >= arrowTop && y < arrowBottom) return false
            if (isInChipBounds(leftChip, x, y)) return false
            if (isInChipBounds(rightChip, x, y)) return false
            // The styled definitions area belongs to the WebView: the page
            // reports what a tap actually hit — a link navigates and hands
            // off to the browser, anything else comes back as a body tap
            // (wired to [fireOpenTap] in [ensureStyledView]). Running the
            // detector here too would race that handoff: the open tap
            // lands first, tears the lens down, and destroys the WebView
            // before the click ever processes.
            if (isInStyledView(x, y)) return false
            if (isInSplitBody(x, y)) return false
            return true
        }

        /** In split mode the definitions area belongs to the section views —
         *  each section is its own tap target and the space between (the
         *  divider band) deliberately inert — so the card-wide open detector
         *  must stand down there. The pill overhangs the scroll frame and
         *  keeps the card-wide open (it carries the WORD's identity). */
        private fun isInSplitBody(x: Float, y: Float): Boolean {
            if (!splitActive) return false
            if (x >= pillView.left && x < pillView.right &&
                y >= pillView.top && y < pillView.bottom
            ) {
                return false
            }
            return x >= definitionsScroll.left && x < definitionsScroll.right &&
                y >= definitionsScroll.top && y < definitionsScroll.bottom
        }

        /** Whether a LensView-space point lands on the styled view (visible
         *  and active only). getLocationInWindow on both ends absorbs the
         *  definitions scroller's current scroll offset. */
        private fun isInStyledView(x: Float, y: Float): Boolean {
            val sv = styledView ?: return false
            if (!styledActive || sv.visibility != VISIBLE) return false
            val svLoc = IntArray(2).also { sv.getLocationInWindow(it) }
            val myLoc = IntArray(2).also { getLocationInWindow(it) }
            val lx = x + myLoc[0] - svLoc[0]
            val ly = y + myLoc[1] - svLoc[1]
            return lx >= 0 && lx < sv.width && ly >= 0 && ly < sv.height
        }

        private fun isInChipBounds(chip: View, x: Float, y: Float): Boolean {
            if (chip.visibility != VISIBLE) return false
            return x >= chip.left && x < chip.right && y >= chip.top && y < chip.bottom
        }

        fun setSourceBitmap(bitmap: Bitmap?) {
            sourceBitmap = bitmap
            invalidate()
        }

        fun setSourcePoint(x: Float, y: Float, screenW: Int, screenH: Int) {
            sourceX = x
            sourceY = y
            sourceScreenW = screenW
            sourceScreenH = screenH
            invalidate()
        }

        fun setLensFlipped(flipped: Boolean) {
            if (lensFlipped == flipped) return
            lensFlipped = flipped
            updateChromeLayout()
            rebuildClipPath()
            invalidate()
        }

        fun setArrowVisible(visible: Boolean, offsetX: Int) {
            if (arrowVisible == visible && arrowOffsetX == offsetX) return
            arrowVisible = visible
            arrowOffsetX = offsetX
            invalidate()
        }

        private enum class PillState { None, Placeholder, Word }
        private var pillState: PillState = PillState.None
        /** Last (word, reading) the pill was rendered with. Used so that
         *  a redundant setLabel call (same content) is a no-op, while
         *  any actual content change — across words OR across state —
         *  drives the width animation. */
        private var pillWord: String = ""
        private var pillReading: String = ""
        private var pillPitch: List<Int> = emptyList()
        private var pillAnimator: ValueAnimator? = null
        /** Decorates the Placeholder pill: when true, the magnifying-glass
         *  icon + "Find a word" prompt are hidden and
         *  [pillPlaceholderSpinnerView] is shown alone. Set by
         *  [setPillLoading] from [DragLookupController] for the duration
         *  of the drag-start OCR job. Word state ignores the flag. */
        private var pillLoading: Boolean = false

        fun setLabel(word: String?, reading: String?, pitch: List<Int>? = null) {
            val w = word?.takeIf { it.isNotEmpty() }
            val r = reading?.takeIf { it.isNotEmpty() }
            // pitch == null → the caller doesn't KNOW (drag tracking, the
            // loading skin, deck refreshes): preserve the accent the last
            // definitions lookup established while the word is unchanged,
            // reset on word change. A non-null list (definitions paths) is
            // authoritative — including empty, which clears. Without this,
            // interleaved tracking/loading calls strip the accent whenever
            // they happen to land after setDefinitions.
            val knownPitch = pitch ?: if (w.orEmpty() == pillWord) pillPitch else emptyList()
            // Kana-only words have no separate reading (the kana IS the
            // word); reuse it in the reading slot so the accent contour has
            // a home — mirrors WordResultCell. The all-kana guard is
            // load-bearing: the contour maps morae onto whatever string it
            // draws over, so it must never cover kanji.
            val pitchKana = r ?: w?.takeIf { knownPitch.isNotEmpty() && it.all(Deinflector::isKana) }
            val newPitch = if (pitchKana != null) knownPitch else emptyList()
            val newState = if (w == null) PillState.Placeholder else PillState.Word
            val newWord = w.orEmpty()
            val newReading = pitchKana.orEmpty()
            // Kana-only: the reading just repeats the kana title. Draw the accent
            // on the WORD itself (below) and hide the separate reading slot.
            val kanaOnly = newReading.isNotEmpty() && newReading == newWord
            val showReading = pitchKana != null && newState == PillState.Word && !kanaOnly

            if (newState == pillState && newWord == pillWord && newReading == pillReading &&
                newPitch == pillPitch
            ) {
                return
            }

            val prevState = pillState
            pillState = newState
            pillWord = newWord
            pillReading = newReading
            pillPitch = newPitch

            // Push the new content into the views before measuring the
            // pill's natural width — the new word's text width is what
            // we're animating toward.
            if (kanaOnly) {
                // Accent rides on the word itself. Two things the hidden reading
                // slot used to handle: (1) fit the word + its [n] suffix to the
                // card budget so a long kana word can't overflow/ellipsize; (2)
                // a top pad shifts it down so the overline clears the capsule top
                // (the contour draws ~0.16×size above the text). Top-only, since
                // symmetric padding would push a 24sp word past the 40dp pill.
                val suffix = if (newPitch.isNotEmpty())
                    newPitch.joinToString("·") { "[$it]" } else ""
                if (newPitch.isNotEmpty()) {
                    pillWordView.text = buildPitchAnnotatedReading(newWord, newPitch)
                } else {
                    pillWordView.text = newWord
                }
                fitPillText(
                    word = if (suffix.isEmpty()) newWord else "$newWord $suffix",
                    reading = "",
                )
                val pad = if (newPitch.isNotEmpty())
                    (pillWordView.textSize * 0.22f).toInt() else 0
                pillWordView.setPadding(0, pad, 0, 0)
            } else {
                // Reset the headline to full size + no padding — a prior kana-only
                // label may have shrunk / padded it.
                pillWordView.setTextSize(TypedValue.COMPLEX_UNIT_SP, pillWordSp)
                pillWordView.setPadding(0, 0, 0, 0)
                pillWordView.text = newWord
                if (newPitch.isEmpty()) {
                    pillReadingView.text = newReading
                    fitPillText(newWord, newReading)
                    pillReadingView.setPadding(0, 0, 0, 0)
                } else {
                    pillReadingView.text = buildPitchAnnotatedReading(newReading, newPitch)
                    fitPillText(
                        newWord,
                        newReading + " " + newPitch.joinToString("·") { "[$it]" },
                    )
                    // Symmetric padding: headroom for the overline above,
                    // mirrored below so the glyphs stay centered in the fixed-
                    // height pill. Scales with the post-fit text size.
                    val pad = (pillReadingView.textSize * 0.30f).toInt()
                    pillReadingView.setPadding(0, pad, 0, pad)
                }
            }

            if (prevState == PillState.None) {
                // First state assignment after show()/teardown rebuild —
                // just snap, no animation.
                applyPillStateVisibility(newState, showReading)
                pillView.visibility = VISIBLE
            } else {
                animatePillStateTransition(newState, showReading)
            }
        }

        /** Toggle child visibility for the requested state without
         *  touching the pill's own width or animator. The Placeholder
         *  state has two skins keyed off [pillLoading]: the magnifying-
         *  glass icon + "Find a word" prompt at rest, or the spinner
         *  alone (no label) while drag-start OCR is in flight. */
        private fun applyPillStateVisibility(state: PillState, showReading: Boolean) {
            val placeholderVisible = state == PillState.Placeholder
            val wordVisible = state == PillState.Word
            val loadingSkin = placeholderVisible && pillLoading
            val idlePlaceholder = placeholderVisible && !pillLoading
            pillPlaceholderIconView.visibility = if (idlePlaceholder) VISIBLE else GONE
            pillPlaceholderSpinnerView.visibility = if (loadingSkin) VISIBLE else GONE
            pillPlaceholderTextView.visibility = if (idlePlaceholder) VISIBLE else GONE
            pillWordView.visibility = if (wordVisible) VISIBLE else GONE
            pillDividerView.visibility = if (wordVisible && showReading) VISIBLE else GONE
            pillReadingView.visibility = if (wordVisible && showReading) VISIBLE else GONE
            pillChevronView.visibility = if (wordVisible) VISIBLE else GONE
        }

        /** Animate the pill's width from its current measured width to the
         *  natural width of [newState]'s children. Children are switched
         *  to the new state's visibility immediately; the pill's clip
         *  reveals (or hides) them as it grows (or shrinks). */
        private fun animatePillStateTransition(newState: PillState, showReading: Boolean) {
            pillAnimator?.cancel()
            val oldWidth = if (pillView.width > 0) pillView.width else measurePillNaturalWidth()
            applyPillStateVisibility(newState, showReading)
            val newWidth = measurePillNaturalWidth()
            pillView.visibility = VISIBLE
            val params = pillView.layoutParams
            params.width = oldWidth
            pillView.layoutParams = params
            pillAnimator = ValueAnimator.ofInt(oldWidth, newWidth).apply {
                duration = 180L
                interpolator = android.view.animation.DecelerateInterpolator()
                addUpdateListener { anim ->
                    val w = anim.animatedValue as Int
                    val lp = pillView.layoutParams
                    lp.width = w
                    pillView.layoutParams = lp
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        // Restore WRAP_CONTENT so subsequent content
                        // changes within the same state can resize the
                        // pill naturally.
                        val lp = pillView.layoutParams
                        lp.width = LinearLayout.LayoutParams.WRAP_CONTENT
                        pillView.layoutParams = lp
                    }
                })
                start()
            }
        }

        /** Measure the pill's natural (WRAP_CONTENT) width with the
         *  current child visibilities + content, height pinned at the
         *  fixed pill height. */
        private fun measurePillNaturalWidth(): Int {
            pillView.measure(
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                MeasureSpec.makeMeasureSpec(pillHeightPx, MeasureSpec.EXACTLY),
            )
            return pillView.measuredWidth
        }

        /**
         * Size and bound the pill's headline and reading so the capsule can
         * never outgrow [pillMaxWidthPx] — the width past which [revealChips]
         * has nowhere left to seat the chips but off the frame.
         *
         * [word] and [reading] are the strings the two slots will draw, pitch
         * `[n]` suffix included (measured at full size though it renders at
         * 0.75×, which errs roomy). Pass an empty [reading] for the states with
         * no reading slot: the kana-only pill, where the headline carries the
         * accent itself, and words that have no reading at all.
         *
         * The reading yields first, shrinking 14→11sp against a full-size
         * headline — the long-standing rule, so a pill that fits today is
         * untouched. The headline then yields against what the reading settled
         * at, shrinking 24sp down to the same 11sp floor rather than
         * ellipsizing away the identity of the word. Whatever neither step can
         * save is ellipsized by the maxWidths, which is also what makes the
         * capsule's natural width bounded by construction rather than by
         * measurement luck.
         *
         * Stateless across calls: every call starts its search from the max
         * sizes, so a short label returns to full size on its own.
         */
        private fun fitPillText(word: String, reading: String) {
            val showReading = reading.isNotEmpty()
            val fixedChrome = pillPaddingLead + pillPaddingTrail +
                pillChevronSize + pillChevronMarginStart +
                if (showReading) pillDividerWidth + 2 * pillGap else 0
            val textBudget = (pillMaxWidthPx - fixedChrome).coerceAtLeast(0).toFloat()

            pillWordSizingPaint.textSize = spPx(pillWordSp)
            val readingRoom = textBudget - pillWordSizingPaint.measureText(word)
            var readingSp = pillReadingMaxSp
            if (showReading) {
                while (readingSp > pillReadingMinSp) {
                    pillReadingSizingPaint.textSize = spPx(readingSp)
                    if (pillReadingSizingPaint.measureText(reading) <= readingRoom) break
                    readingSp -= 1f
                }
                pillReadingSizingPaint.textSize = spPx(readingSp)
            }
            val readingWidth =
                if (showReading) pillReadingSizingPaint.measureText(reading) else 0f
            val (wordAllot, readingAllot) = computePillTextAllotment(
                maxPillWidth = pillMaxWidthPx,
                fixedChromeWidth = fixedChrome,
                readingWidth = readingWidth.roundToInt(),
            )

            var wordSp = pillWordSp
            while (wordSp > pillReadingMinSp) {
                pillWordSizingPaint.textSize = spPx(wordSp)
                if (pillWordSizingPaint.measureText(word) <= wordAllot) break
                wordSp -= 1f
            }
            pillWordView.setTextSize(TypedValue.COMPLEX_UNIT_SP, wordSp)
            pillWordView.maxWidth = wordAllot
            pillReadingView.setTextSize(TypedValue.COMPLEX_UNIT_SP, readingSp)
            pillReadingView.maxWidth = readingAllot
        }

        fun setDefinitions(data: WordDefinitionData?, label: String?) {
            if (data == null) {
                if (mode == Mode.ZOOM) return
                mode = Mode.ZOOM
                showFlatBody()
                definitionsScroll.visibility = GONE
                leftChip.visibility = GONE
                rightChip.visibility = GONE
                invalidate()
                return
            }
            mode = Mode.DEFINITIONS
            setLabel(data.word, data.reading, data.pitch)
            // Drag lens keeps its compact layout — no misc line. The detail
            // sheet reached on tap-through still shows misc (it re-resolves).
            // The flat renderer binds UNCONDITIONALLY: it is the instant
            // content of every lookup and the standing fallback the styled
            // path degrades to (renderer death, empty render).
            definitionsContent.bind(data, label, LENS_DEFINITIONS_SCALE, showMisc = false)
            showFlatBody()
            val styledData = data.styled
            val sv = if (styledData != null && styledData.structured.isNotEmpty()) {
                ensureStyledView()
            } else {
                null
            }
            if (sv != null) {
                // Styled upgrade: swap over the flat content when the page
                // reports painted height (see [onStyledContentHeight]).
                pendingStyledSwap = true
                sv.setContent(
                    DefinitionsDocument.contentHtml(
                        data,
                        styledData!!.structured,
                        localizePos = { context.localizePos(it) },
                        showMisc = false,
                        metaChips = styledMetaChips(context, data),
                        label = label,
                    ),
                    styledData.dictStyles,
                    styledData.sourceLanguage,
                )
            }
            definitionsScroll.scrollTo(0, 0)
            definitionsScroll.visibility = VISIBLE
            // Chip visibility is owned by [attachInteractiveListeners] —
            // they only appear once the lens becomes sticky.
            invalidate()
        }

        /** Split body — see [MagnifierLens.setSplitDefinitions]. The pill
         *  keeps the PRIMARY (tapped) unit's identity; each section carries
         *  its own headword header (the pill can't name them all) and, when
         *  it opens, a trailing chevron + full-section tap target.
         *  [secondariesOnTop] picks the visual order (the longer unit
         *  renders on top on every surface). Every section binds flat
         *  instantly and upgrades to its OWN styled renderer when its data
         *  carries a structured payload ([attachSectionStyled]) — one
         *  WebView per section, because a single shared page's body-tap
         *  report couldn't attribute a tap to a section. */
        fun setSplitDefinitions(
            primary: LensSection,
            secondaries: List<LensSection>,
            secondariesOnTop: Boolean,
        ) {
            mode = Mode.DEFINITIONS
            setLabel(primary.data.word, primary.data.reading, primary.data.pitch)
            // Styled steps aside; any prior split is torn down for rebuild.
            showFlatBody()
            definitionsContent.visibility = GONE
            // The primary section rendering FIRST (the JA shape: expression
            // on top) drops its header — the pill directly above already
            // carries the same headword + reading, and repeating them costs
            // a row. A bottom-rendered primary (Latin: word under the
            // phrase) keeps its header for scannability.
            val ordered: List<Triple<LensSection, () -> Unit, Boolean>> = buildList {
                val secs = secondaries.mapIndexed { i, s ->
                    Triple(s, { fireSecondaryOpenTap(i) }, true)
                }
                if (secondariesOnTop) {
                    addAll(secs)
                    add(Triple(primary, ::fireOpenTap, true))
                } else {
                    add(Triple(primary, ::fireOpenTap, false))
                    addAll(secs)
                }
            }
            val views = mutableListOf<View>()
            ordered.forEachIndexed { i, (section, fire, showHeader) ->
                if (i > 0) splitContent.addView(buildSplitDivider())
                addSplitSection(section, fire, showHeader)?.let { views += it }
            }
            splitSectionViews = views
            splitActive = true
            splitContent.visibility = VISIBLE
            definitionsScroll.scrollTo(0, 0)
            definitionsScroll.visibility = VISIBLE
            invalidate()
        }

        /** Builds one split section — headword header (name + reading side
         *  by side, chevron alone at the trailing edge when it opens) over
         *  the flat definitions body — into [splitContent]. [showHeader]
         *  false skips the header entirely (the top-rendered primary: the
         *  pill already names it). Returns the section root when it's a tap
         *  target ([LensSection.opens]), null otherwise. [fire] is the
         *  section's (already-debounced) open action. */
        private fun addSplitSection(
            section: LensSection,
            fire: () -> Unit,
            showHeader: Boolean,
        ): View? {
            val col = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
            }
            if (showHeader) {
                val header = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    // Symmetric insets for the split sections — the single
                    // view's scrollbar-gutter asymmetry (+8dp on the right)
                    // read as a lopsided frame here.
                    setPadding(bodyHPaddingPx - dp(6f), 0, bodyHPaddingPx - dp(6f), 0)
                }
                // Name and reading sit ADJACENT ([Name] [Reading]); the
                // stretch lives in the spacer after them, so only the
                // chevron is pushed to the trailing edge.
                header.addView(
                    TextView(context).apply {
                        text = section.data.word
                        setTextColor(panelPrimaryText)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    },
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ),
                )
                section.data.reading?.let { reading ->
                    header.addView(
                        TextView(context).apply {
                            text = reading
                            setTextColor(panelSecondaryText)
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                            maxLines = 1
                            ellipsize = TextUtils.TruncateAt.END
                        },
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        ).apply { marginStart = dp(8f) },
                    )
                }
                if (section.opens) {
                    header.addView(
                        View(context),
                        LinearLayout.LayoutParams(0, 1, 1f),
                    )
                    header.addView(
                        buildSectionChevron(),
                        LinearLayout.LayoutParams(pillChevronSize, pillChevronSize)
                            .apply { marginStart = dp(8f) },
                    )
                }
                col.addView(
                    header,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { bottomMargin = dp(2f) },
                )
            }
            val body = WordDefinitionsView(context).apply {
                setPadding(bodyHPaddingPx - dp(6f), 0, bodyHPaddingPx - dp(6f), 0)
                metaChipFill = panelBadgeBg
                emptyPlaceholder = context.getString(R.string.word_detail_no_definitions)
                bind(section.data, section.label, LENS_DEFINITIONS_SCALE, showMisc = false)
            }
            // Flat and (maybe) styled renderers share one holder, so the
            // styled swap-in is a visibility flip INSIDE the section —
            // invisible to the column layout around it.
            val holder = FrameLayout(context).apply {
                addView(body, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            }
            attachSectionStyled(holder, body, section, fire)
            if (!showHeader && section.opens) {
                // A headerless section keeps its open affordance: the
                // chevron floats over the body's top-right at the CONTENT
                // edge — the same column every header chevron ends at —
                // level with the first row, no header row, no vertical
                // cost. (A maximal-width first gloss line could reach under
                // it; the usual first row is the left-packed meta chips.)
                // Absolute RIGHT, matching the lens's canvas-aligned layout
                // discipline. Added LAST so it stays painted over a styled
                // swap-in.
                holder.addView(
                    buildSectionChevron(),
                    LayoutParams(
                        pillChevronSize, pillChevronSize,
                        Gravity.TOP or Gravity.RIGHT,
                    ).apply {
                        topMargin = dp(4f)
                        rightMargin = bodyHPaddingPx - dp(6f)
                    },
                )
            }
            col.addView(
                holder,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            if (section.opens) {
                col.isClickable = true
                col.setOnClickListener { fire() }
            }
            splitContent.addView(
                col,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            return col.takeIf { section.opens }
        }

        /** The section's styled (WebView) upgrade, mirroring the single-unit
         *  body's contract: the flat [body] renders instantly and stands as
         *  the fallback; when the section's data carries a structured
         *  payload, a pooled renderer binds behind it — INVISIBLE at 1px,
         *  never GONE, for the same laid-out-at-real-width reasons as
         *  [ensureStyledView] — and swaps over the flat view only once the
         *  page reports painted height. Body taps report through the page's
         *  own hit testing ([YomitanDefinitionsView.onBodyTap]) into the
         *  SECTION's (already-debounced) open action — per-section views are
         *  exactly what makes tap attribution possible here, where one
         *  shared page couldn't say which section a tap landed in — and
         *  link taps stay live, handing off to the browser as in the
         *  single-unit body. */
        private fun attachSectionStyled(
            holder: FrameLayout,
            body: WordDefinitionsView,
            section: LensSection,
            fire: () -> Unit,
        ) {
            val styledData = section.data.styled ?: return
            if (styledData.structured.isEmpty()) return
            val sv = obtainSplitStyledView() ?: return
            val bindSeq = splitBindSeq
            sv.visibility = INVISIBLE
            // A non-opening section's body must stay inert — and a pooled
            // view must never keep a previous bind's handler.
            sv.onBodyTap = if (section.opens) {
                { fire() }
            } else {
                null
            }
            sv.onContentHeight = { h ->
                // Superseded-bind reports must not touch the live tree; the
                // splitActive check additionally covers the window between
                // this bind and a non-split rebind that hasn't detached yet.
                if (h > 0 && bindSeq == splitBindSeq && splitActive) {
                    sv.layoutParams = (sv.layoutParams as? LayoutParams
                        ?: LayoutParams(LayoutParams.MATCH_PARENT, h)).apply { height = h }
                    if (sv.visibility != VISIBLE) {
                        sv.visibility = VISIBLE
                        body.visibility = GONE
                    }
                    onStyledHeightChanged()
                }
            }
            sv.onRendererGone = {
                // The component destroyed itself; drop it from the pool (its
                // siblings will report their own deaths) and never build
                // another — flat is the standing fallback.
                splitStyledPool.remove(sv)
                (sv.parent as? ViewGroup)?.removeView(sv)
                styledUnavailable = true
                if (bindSeq == splitBindSeq) body.visibility = VISIBLE
            }
            holder.addView(sv, LayoutParams(LayoutParams.MATCH_PARENT, 1))
            sv.setContent(
                DefinitionsDocument.contentHtml(
                    section.data,
                    styledData.structured,
                    localizePos = { context.localizePos(it) },
                    showMisc = false,
                    metaChips = styledMetaChips(context, section.data),
                    label = section.label,
                ),
                styledData.dictStyles,
                styledData.sourceLanguage,
            )
        }

        private fun buildSectionChevron(): ImageView = ImageView(context).apply {
            val d = AppCompatResources.getDrawable(context, R.drawable.ic_lens_chevron)?.mutate()
            if (d != null) {
                DrawableCompat.setTint(d, panelSecondaryText)
                setImageDrawable(d)
            }
        }

        /** Hairline between the split sections — card-border color, inset to
         *  the text column. */
        private fun buildSplitDivider(): View = View(context).apply {
            setBackgroundColor(cardBorderColor)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (density * 1f).toInt().coerceAtLeast(1),
            ).apply {
                topMargin = dp(9f)
                bottomMargin = dp(9f)
                marginStart = bodyHPaddingPx - dp(6f)
                marginEnd = bodyHPaddingPx - dp(6f)
            }
        }

        fun setLoading(word: String?, reading: String?) {
            mode = Mode.LOADING
            setLabel(word, reading)
            showFlatBody()
            populateLoading()
            definitionsScroll.scrollTo(0, 0)
            definitionsScroll.visibility = VISIBLE
            invalidate()
        }

        /** Toggle the spinner skin on the Placeholder pill. Loading is a
         *  decoration of [PillState.Placeholder] — when the pill is
         *  currently showing a word the flag is stored but has no visual
         *  effect; the next transition back to Placeholder will honor it.
         *
         *  Within-Placeholder skin swaps are always width-animated (same
         *  machinery as state changes via [setLabel]). This matters in
         *  both directions:
         *   - Loading off: the narrow spinner pill smoothly grows into
         *     "Find a word" (or, if pretokenize lands the user's line
         *     mid-animation, [setLabel] cancels and redirects to the
         *     Word-state width).
         *   - Loading on: in the re-drag path, [resetToZoom] has just
         *     animated Word→Placeholder toward the wide idle width;
         *     animating again here cancels that tween and redirects to
         *     the narrow spinner-only width so the spinner doesn't sit
         *     in an oversized pill for the OCR duration.
         *
         *  The very first drag activates while pillState is still None
         *  and this early-returns — the spinner shows as part of the
         *  initial setLabel snap. */
        fun setPillLoading(loading: Boolean) {
            if (pillLoading == loading) return
            pillLoading = loading
            if (pillState != PillState.Placeholder) return
            animatePillStateTransition(PillState.Placeholder, showReading = false)
        }

        var isInteractive: Boolean = false
            private set

        fun attachInteractiveListeners(onDismissRequest: () -> Unit) {
            dismissRequest = onDismissRequest
            // ACTION_OUTSIDE delivers the system's "user touched outside the
            // window" notification; this listener doesn't observe clicks on
            // the lens itself, so there's no click semantic for
            // View.performClick to mirror. TalkBack users dismiss the lens
            // via the back gesture.
            @android.annotation.SuppressLint("ClickableViewAccessibility")
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                    onDismissRequest()
                    false
                } else false
            }
            // Right stick scrolls the definitions — the sheet's scroll hand
            // (the left stick was the original driver, and before that a
            // nudge-to-dismiss; B carries dismissal now, and a dictionary
            // long enough to scroll is exactly when a stick flick must not
            // close it).
            setOnGenericMotionListener { _, event -> handleNavMotion(event) }
            navPreDraw = ViewTreeObserver.OnPreDrawListener { syncNavRing(); true }.also {
                viewTreeObserver.addOnPreDrawListener(it)
            }
            requestFocus()
            isInteractive = true
            // Chips become visible only in the sticky state (per the
            // design — they're quick actions for a settled lookup, not
            // drag-time UI). Reveal is deferred until the pill's
            // width animation (placeholder → word) completes, so the
            // chips' "under-pill" start position is actually under the
            // pill at its final word-state width.
            if (mode == Mode.DEFINITIONS) scheduleChipReveal()
        }

        fun detachInteractiveListeners() {
            setOnTouchListener(null)
            setOnGenericMotionListener(null)
            navPreDraw?.let { viewTreeObserver.removeOnPreDrawListener(it) }
            navPreDraw = null
            stopNavScroll()
            keyPressView?.let { ConfirmKeyPress.cancel(it) }
            keyPressView = null
            navCursor = null
            navConsumedDown.clear()
            applySectionTint(null)
            focusRing.setTarget(null, null)
            dismissRequest = null
            clearFocus()
            isInteractive = false
            leftChip.animate().cancel()
            rightChip.animate().cancel()
            leftChip.translationX = 0f
            rightChip.translationX = 0f
            leftChip.visibility = GONE
            rightChip.visibility = GONE
        }

        // ── Controller nav: cursor over the pill + chips, stick scroll ────

        private var navCursor: View? = null
        private var navPreDraw: ViewTreeObserver.OnPreDrawListener? = null
        /** Keycodes whose DOWN we consumed, so the matching UP is too — and
         *  so the A that OPENED the lens (pressed on the sheet, released over
         *  this freshly-focused window) can't instantly activate the pill. */
        private val navConsumedDown = mutableSetOf<Int>()
        private val navItemLoc = IntArray(2)
        private val navSelfLoc = IntArray(2)
        private val navTmp = Rect()

        /** Auto-select for a controller-opened lens: the pill rings from the
         *  start, so the very next A drills into the detail screen. */
        fun focusPill() {
            navCursor = pillView
            syncNavRing()
        }

        private fun navChromeRow(): List<View> =
            listOf(leftChip, pillView, rightChip).filter { it.isShown }

        private fun navSections(): List<View> =
            splitSectionViews.filter { it.isShown }

        /** Item rect in THIS view's coordinates. Chips ring their 32dp visible
         *  disk, not the 48dp hit halo. */
        private fun navRectInView(v: View, out: Rect): Boolean {
            if (!v.isShown || v.width <= 0 || v.height <= 0) return false
            v.getLocationOnScreen(navItemLoc)
            getLocationOnScreen(navSelfLoc)
            val l = navItemLoc[0] - navSelfLoc[0]
            val t = navItemLoc[1] - navSelfLoc[1]
            out.set(l, t, l + v.width, t + v.height)
            if (v === leftChip || v === rightChip) {
                val inset = (chipHitSizePx - chipVisDiameterPx) / 2
                out.inset(inset, inset)
            }
            return true
        }

        /** The split section currently carrying the cursor tint, so the
         *  per-preDraw [syncNavRing] only mutates backgrounds on an actual
         *  cursor change. */
        private var tintedSection: View? = null

        /** Move the cursor tint to [target] (null clears). Sections render
         *  selection as a soft accent fill (10% alpha) instead of the
         *  boundary ring — a full-width definitions cell wants a
         *  background, not chrome. The inset is a LayerDrawable layer inset
         *  with padding pinned to zero, NOT an InsetDrawable: a background's
         *  reported padding becomes the VIEW's padding on setBackground,
         *  which shifted the section's text on selection. */
        private fun applySectionTint(target: View?) {
            if (tintedSection === target) return
            tintedSection?.background = null
            tintedSection = target
            target?.background = LayerDrawable(
                arrayOf(
                    GradientDrawable().apply {
                        setColor(withAlpha(accentColor, 0x1A))
                        cornerRadius = dp(10f).toFloat()
                    },
                ),
            ).apply {
                setLayerInset(0, dp(8f), dp(2f), dp(8f), dp(2f))
                setPadding(0, 0, 0, 0)
            }
        }

        private fun syncNavRing() {
            val cur = navCursor
            val curSection = cur?.takeIf { it in splitSectionViews && it.isShown }
            applySectionTint(curSection)
            if (curSection != null) {
                focusRing.setTarget(null, null)
                return
            }
            if (cur == null || !navRectInView(cur, navTmp)) {
                focusRing.setTarget(null, null)
                return
            }
            focusRing.setTarget(navTmp, null)
        }

        /** Dpad + A for the sticky lens (B stays in [LensRoot]). On the Anki
         *  chip the A is delivered as a real press on the chip
         *  ([ConfirmKeyPress]), so a held A runs the chip's own long-press
         *  (the headless one-tap) at the long-press timeout, exactly as a
         *  held finger does; an early release is the chip's click (the
         *  review sheet). Everything else activates on the consumed UP.
         *  Any of these — the timeout-fired one-tap included — may dismiss
         *  this focused window mid-press: the dismissal seam lingers the
         *  window as an invisible key sink until every tracked press
         *  releases ([WindowKeyPairGuard] on [LensRoot]), so a still-held
         *  key's UP cannot orphan into the app beneath. */
        fun handleNavKey(ev: KeyEvent): Boolean {
            if (!isInteractive) return false
            if (ev.action == KeyEvent.ACTION_UP) {
                if (!navConsumedDown.remove(ev.keyCode)) return false
                if (ControllerKeys.isActivate(ev.keyCode)) {
                    val held = keyPressView
                    if (held != null) {
                        keyPressView = null
                        ConfirmKeyPress.up(held, ev)
                    } else {
                        val cur = navCursor
                        // First A selects the pill; only a second activates.
                        if (cur == null || !cur.isShown) focusPill() else activateNav(cur)
                    }
                }
                return true
            }
            if (ev.action != KeyEvent.ACTION_DOWN) return false
            val dir = ControllerKeys.direction(ev.keyCode)
            if (dir == null && !ControllerKeys.isActivate(ev.keyCode)) return false
            if (dir != null) {
                // Cursor leaving the chip mid-press: cancel the view press
                // (no click, no pending long-press). The ref is deliberately
                // KEPT — the eventual A-UP then routes to the now-unpressed
                // chip and no-ops, instead of activating the moved-to item.
                keyPressView?.let { ConfirmKeyPress.cancel(it) }
                val cur = navCursor
                if (cur == null || !cur.isShown) focusPill() else moveNav(cur, dir)
            } else if (ev.repeatCount == 0 && keyPressView == null &&
                navCursor === rightChip && rightChip.isShown
            ) {
                keyPressView = rightChip
                ConfirmKeyPress.down(rightChip, ev)
            }
            navConsumedDown.add(ev.keyCode)
            return true
        }

        /** The view holding an in-flight forwarded key press (the Anki chip —
         *  the lens's one hold-capable item). See [ConfirmKeyPress]. */
        private var keyPressView: View? = null

        /** Explicit nav graph instead of spatial scoring. The lens is two
         *  strips: the chrome row (Speak chip · pill · Anki chip) along one
         *  card edge, and the split body's sections stacked on the body
         *  side. Spatial scoring breaks on this layout — a section is a
         *  card-wide rect centered like the pill, so once the body has
         *  scrolled (unclipped rects, center rounding) a section can beat a
         *  chip on a LEFT/RIGHT move with a near-zero axis distance.
         *  LEFT/RIGHT walk the chrome row only; the vertical axis moves
         *  between the row and the sections (direction flip-aware: the
         *  body is below the chrome when the lens sits above the word,
         *  above it when flipped). */
        private fun moveNav(cur: View, dir: SheetNavGeometry.Dir) {
            val chrome = navChromeRow()
            val sections = navSections()
            if (cur !in chrome && cur !in sections) {
                focusPill()
                return
            }
            val towardBody =
                if (lensFlipped) SheetNavGeometry.Dir.UP else SheetNavGeometry.Dir.DOWN
            val towardChrome =
                if (lensFlipped) SheetNavGeometry.Dir.DOWN else SheetNavGeometry.Dir.UP
            // Walk order into/through the body follows the physical axis:
            // chrome-on-top enters at the stack's top (phrase) and walks
            // down; a flipped lens (chrome at the bottom) enters at the
            // stack's bottom (word) and walks up.
            val bodyWalk = if (lensFlipped) sections.reversed() else sections
            val next: View = when {
                cur in chrome &&
                    (dir == SheetNavGeometry.Dir.LEFT || dir == SheetNavGeometry.Dir.RIGHT) -> {
                    val i = chrome.indexOf(cur)
                    chrome.getOrNull(if (dir == SheetNavGeometry.Dir.LEFT) i - 1 else i + 1)
                }
                cur in chrome && dir == towardBody -> bodyWalk.firstOrNull()
                cur in sections && dir == towardBody ->
                    bodyWalk.getOrNull(bodyWalk.indexOf(cur) + 1)
                cur in sections && dir == towardChrome ->
                    bodyWalk.getOrNull(bodyWalk.indexOf(cur) - 1)
                        ?: pillView.takeIf { it.isShown }
                else -> null
            } ?: return
            navCursor = next
            // A selected definitions section reads top-aligned: scroll its
            // top to where the first row rests at scroll 0 (clear of the
            // pill band — section tops within splitContent ARE resting
            // scroll offsets, see the scroll's padding derivation). Stepping
            // back out to the chrome restores the resting scroll.
            if (next in sections) {
                definitionsScroll.smoothScrollTo(0, next.top)
            } else if (cur in sections) {
                definitionsScroll.smoothScrollTo(0, 0)
            }
            syncNavRing()
        }

        private fun activateNav(v: View) {
            when {
                v === pillView -> fireOpenTap()
                v === leftChip -> onSpeakTap()
                v === rightChip -> onAnkiTap()
                // Sections carry their own (debounced) open action as their
                // click listener — one wiring for touch and controller.
                v in splitSectionViews -> v.performClick()
            }
        }

        // Stick scroll of the definitions body — the sheet's repeater pattern
        // (dt-clamped, shared speed/dead-zone dials), minus the modal checks
        // the lens doesn't have. Stopped by [detachInteractiveListeners],
        // which [removeOverlayInternal] runs on every dismissal (the repeater
        // re-posts itself, so an un-detached removal would leak the view).

        private var navScrollActive = false
        private var navScrollY = 0f
        private var navScrollDead = CaptureSheetControllerNav.STICK_DEAD_ZONE
        private var navScrollRepeating = false
        private var navScrollLastNs = 0L
        private val navScrollFrame = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (!navScrollRepeating) return
                val dt = ((frameTimeNanos - navScrollLastNs) / 1e9f).coerceIn(0f, 0.05f)
                navScrollLastNs = frameTimeNanos
                val mag = ((abs(navScrollY) - navScrollDead) / (1f - navScrollDead)).coerceIn(0f, 1f)
                definitionsScroll.scrollBy(
                    0,
                    (sign(navScrollY) * mag *
                        CaptureSheetControllerNav.STICK_MAX_DP_PER_SEC * density * dt).roundToInt(),
                )
                Choreographer.getInstance().postFrameCallback(this)
            }
        }

        private fun handleNavMotion(event: MotionEvent): Boolean {
            if (!isInteractive) return false
            if (event.actionMasked != MotionEvent.ACTION_MOVE) return false
            if (event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK) {
                return false
            }
            // Hat-driven dpads must keep synthesizing DPAD keys — leave their
            // MOVEs unconsumed (same guard as the sheet).
            if (event.getAxisValue(MotionEvent.AXIS_HAT_X) != 0f ||
                event.getAxisValue(MotionEvent.AXIS_HAT_Y) != 0f
            ) {
                return false
            }
            // The scroll rides the RIGHT stick's vertical axis, as the
            // sheet's does: Z/RZ under the standard mapping, RX/RY on a
            // controller that declares no RZ range.
            val device = event.device
            val ryAxis =
                if (device?.getMotionRange(MotionEvent.AXIS_RZ, event.source) != null) {
                    MotionEvent.AXIS_RZ
                } else {
                    MotionEvent.AXIS_RY
                }
            val flat = device?.getMotionRange(ryAxis, event.source)?.flat ?: 0f
            navScrollDead = maxOf(flat, CaptureSheetControllerNav.STICK_DEAD_ZONE)
            val ry = event.getAxisValue(ryAxis)
            if (abs(ry) <= navScrollDead) {
                val was = navScrollActive
                navScrollActive = false
                stopNavScroll()
                // Consume the centering event of a deflection we owned — and
                // any left-stick push, which drives nothing here now but is
                // still claimed: X/Y are the axes ViewRootImpl synthesizes
                // DPAD keys from, and the cursor walks by hat/dpad alone.
                val leftDead = maxOf(
                    device?.getMotionRange(MotionEvent.AXIS_Y, event.source)?.flat ?: 0f,
                    CaptureSheetControllerNav.STICK_DEAD_ZONE,
                )
                return was ||
                    abs(event.getAxisValue(MotionEvent.AXIS_Y)) > leftDead ||
                    abs(event.getAxisValue(MotionEvent.AXIS_X)) > leftDead
            }
            navScrollActive = true
            navScrollY = ry
            if (!navScrollRepeating) {
                navScrollRepeating = true
                navScrollLastNs = System.nanoTime()
                Choreographer.getInstance().postFrameCallback(navScrollFrame)
            }
            return true
        }

        private fun stopNavScroll() {
            if (!navScrollRepeating) return
            navScrollRepeating = false
            Choreographer.getInstance().removeFrameCallback(navScrollFrame)
        }

        /** Run [revealChips] now if no pill width animation is in flight;
         *  otherwise wait for the pill animator to end and run it then.
         *  This ensures the chips' "under-pill" start position is at the
         *  pill's settled word-state width, not at a mid-animation width
         *  (which would leave the chips visibly exposed). */
        private fun scheduleChipReveal() {
            val anim = pillAnimator
            if (anim == null || !anim.isRunning) {
                revealChips()
                return
            }
            anim.addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    animation.removeListener(this)
                    if (isInteractive) revealChips()
                }
            })
        }

        /** Lay the chips at their final positions — visible disks
         *  [chipPillGapPx] away from the pill's left and right edges —
         *  then translate them back inward so they sit under the pill,
         *  set visibility, and animate translationX back to 0 so they
         *  slide out from under the pill to their resting places. */
        private fun revealChips() {
            // [fitPillText] bounds the capsule at [pillMaxWidthPx], so both
            // lanes come out ≥ 0 however long the word is — a wide pill walks
            // the chips out to the host's edges and stops there, instead of
            // walking them past the frame that clips them.
            val (leftLaneMargin, rightLaneMargin) = computeChipLaneMargins(
                viewW = viewW,
                pillWidth = measurePillNaturalWidth(),
                chipLane = chipLanePx,
            )
            // The vertical anchor is OWNED by applyCardHeightGeometry — keep
            // whatever it last wrote. Recomputing from the live pillAnchorY
            // here reads the ANIMATED card top when the reveal was deferred
            // past a pill tween that ends mid-grow (the loading→definitions
            // path: setLoading's pill tween is still running at
            // makeInteractive), and endCardGrow only clears the draw-only
            // ride — the mid-grow margin then strands the chips inside the
            // grown card. The existing margin is always right: the grow's
            // end-state (written at beginCardGrow) or the settled anchor.
            val chipTopMargin = (leftChip.layoutParams as? LayoutParams)?.topMargin
                ?: (pillAnchorY - chipHitSizePx / 2)

            // Absolute LEFT/RIGHT + left/rightMargin: the lane margins are
            // view-pixel coordinates measured off [viewW], so pairing them with
            // relative START/END margins would mirror the pair under an RTL
            // locale and swap the two chips — prev/next would read backwards.
            leftChip.layoutParams = LayoutParams(
                chipHitSizePx, chipHitSizePx,
                Gravity.LEFT or Gravity.TOP,
            ).apply {
                leftMargin = leftLaneMargin
                topMargin = chipTopMargin
            }
            rightChip.layoutParams = LayoutParams(
                chipHitSizePx, chipHitSizePx,
                Gravity.RIGHT or Gravity.TOP,
            ).apply {
                rightMargin = rightLaneMargin
                topMargin = chipTopMargin
            }

            leftChip.animate().cancel()
            rightChip.animate().cancel()
            // Initial offset accounts for both the chip's visible
            // diameter AND the resting gap, so the disk is fully tucked
            // under the pill at t=0 regardless of the gap.
            val initialOffset = (chipVisDiameterPx + chipPillGapPx).toFloat()
            leftChip.translationX = initialOffset
            rightChip.translationX = -initialOffset
            leftChip.visibility = VISIBLE
            rightChip.visibility = if (showAnkiChip) VISIBLE else GONE
            val interp = android.view.animation.DecelerateInterpolator()
            leftChip.animate().translationX(0f).setDuration(220L).setInterpolator(interp).start()
            rightChip.animate().translationX(0f).setDuration(220L).setInterpolator(interp).start()
        }

        private fun populateLoading() {
            val ctx = context
            definitionsContent.removeAllViews()
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val spinnerSize = dp(16f)
            row.addView(ProgressBar(ctx).apply {
                isIndeterminate = true
                indeterminateTintList = ColorStateList.valueOf(accentColor)
                layoutParams = LinearLayout.LayoutParams(spinnerSize, spinnerSize)
            })
            row.addView(TextView(ctx).apply {
                text = ctx.getString(R.string.lens_loading)
                setTextColor(panelSecondaryText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(dp(8f), 0, 0, 0)
            })
            definitionsContent.addView(row)
        }

        /** Card chrome: bg fill, zoomed pixels (ZOOM only, clipped to the
         *  rounded shape), card border. Drawn here — BEFORE [dispatchDraw]
         *  — so the pill (a child) can overhang the card and visually sit
         *  on top of the border at the top edge. The crosshair and arrow
         *  paint AFTER children in [draw] for the opposite reason. */
        override fun onDraw(canvas: Canvas) {
            cardRect.set(
                cardLeftInView.toFloat(), bodyTopOffset.toFloat(),
                cardRightInView.toFloat(), cardBottomInView.toFloat(),
            )
            canvas.drawRoundRect(cardRect, cardCornerR, cardCornerR, cardBgPaint)

            if (mode == Mode.ZOOM) {
                canvas.withClip(clipPath) {
                    translate(cardLeftInView.toFloat(), bodyTopOffset.toFloat())
                    drawZoom(this, cardW.toFloat(), lensH.toFloat())
                }
                // The inset shadow bitmap is already pre-clipped to the
                // rounded card shape; transparent pixels outside the
                // rounded edges composite as transparent.
                canvas.drawBitmap(
                    insetShadowBitmap,
                    cardLeftInView.toFloat(),
                    bodyTopOffset.toFloat(),
                    null,
                )
            }

            val inset = cardBorderPx / 2f
            val bodyTop = bodyTopOffset.toFloat()
            cardStrokeRect.set(
                cardLeftInView + inset, bodyTop + inset,
                cardRightInView - inset, bodyTop + cardHeightPx - inset,
            )
            canvas.drawRoundRect(
                cardStrokeRect,
                cardCornerR - inset, cardCornerR - inset,
                cardBorderPaint,
            )
        }

        /** Crosshair (ZOOM only) and sticky-mode arrow paint AFTER children
         *  so they layer above the pill overhang at the same y. (The pill
         *  and the crosshair don't overlap in practice — pill at the card
         *  edge, crosshair at the card center — but the ordering keeps the
         *  visual rule simple.) */
        override fun draw(canvas: Canvas) {
            super.draw(canvas)

            if (mode == Mode.ZOOM) {
                val crosshairCx = cardLeftInView + cardW / 2f
                val crosshairCy = bodyTopOffset + lensH / 2f
                canvas.drawLine(
                    crosshairCx - crosshairHalfLen, crosshairCy,
                    crosshairCx + crosshairHalfLen, crosshairCy, crosshairPaint,
                )
                canvas.drawLine(
                    crosshairCx, crosshairCy - crosshairHalfLen,
                    crosshairCx, crosshairCy + crosshairHalfLen, crosshairPaint,
                )
            }

            if (arrowVisible) drawArrow(canvas)
        }

        private fun drawArrow(canvas: Canvas) {
            val cx = arrowOffsetX.toFloat()
            val halfBase = arrowSizePx.toFloat()
            val baseY: Float
            val tipY: Float
            if (lensFlipped) {
                // Arrow rises from the card top toward the finger above it.
                baseY = bodyTopOffset.toFloat()
                tipY = baseY - arrowSizePx
            } else {
                baseY = cardBottomInView.toFloat()
                tipY = baseY + arrowSizePx
            }
            arrowPath.reset()
            arrowPath.moveTo(cx - halfBase, baseY)
            arrowPath.lineTo(cx + halfBase, baseY)
            arrowPath.lineTo(cx, tipY)
            arrowPath.close()
            canvas.drawPath(arrowPath, arrowFillPaint)

            // Stroke only the two slanted edges; the base sits on the
            // card edge and the card border draws through underneath.
            arrowPath.reset()
            arrowPath.moveTo(cx - halfBase, baseY)
            arrowPath.lineTo(cx, tipY)
            arrowPath.lineTo(cx + halfBase, baseY)
            canvas.drawPath(arrowPath, cardBorderPaint)
        }

        private fun drawZoom(canvas: Canvas, w: Float, h: Float) {
            val bitmap = sourceBitmap
            val boundsW = if (bitmap != null && !bitmap.isRecycled) bitmap.width else sourceScreenW
            val boundsH = if (bitmap != null && !bitmap.isRecycled) bitmap.height else sourceScreenH

            val srcW = (w / zoom).toInt().coerceAtLeast(1)
            val srcH = (h / zoom).toInt().coerceAtLeast(1)
            val cx = sourceX.toInt()
            val cy = sourceY.toInt()
            val srcLeft = cx - srcW / 2
            val srcTop = cy - srcH / 2
            val srcRight = srcLeft + srcW
            val srcBottom = srcTop + srcH

            val cSrcLeft = srcLeft.coerceAtLeast(0)
            val cSrcTop = srcTop.coerceAtLeast(0)
            val cSrcRight = srcRight.coerceAtMost(boundsW)
            val cSrcBottom = srcBottom.coerceAtMost(boundsH)

            val haveInSlice = cSrcLeft < cSrcRight && cSrcTop < cSrcBottom
            if (haveInSlice) {
                val srcWf = srcW.toFloat()
                val srcHf = srcH.toFloat()
                val dstInLeft = w * (cSrcLeft - srcLeft) / srcWf
                val dstInTop = h * (cSrcTop - srcTop) / srcHf
                val dstInRight = w * (cSrcRight - srcLeft) / srcWf
                val dstInBottom = h * (cSrcBottom - srcTop) / srcHf

                if (dstInTop > 0f) canvas.drawRect(0f, 0f, w, dstInTop, backgroundPaint)
                if (dstInBottom < h) canvas.drawRect(0f, dstInBottom, w, h, backgroundPaint)
                if (dstInLeft > 0f) canvas.drawRect(0f, dstInTop, dstInLeft, dstInBottom, backgroundPaint)
                if (dstInRight < w) canvas.drawRect(dstInRight, dstInTop, w, dstInBottom, backgroundPaint)

                if (bitmap != null && !bitmap.isRecycled) {
                    srcRect.set(cSrcLeft, cSrcTop, cSrcRight, cSrcBottom)
                    dstRect.set(dstInLeft, dstInTop, dstInRight, dstInBottom)
                    canvas.drawBitmap(bitmap, srcRect, dstRect, bitmapPaint)
                }
            } else {
                canvas.drawRect(0f, 0f, w, h, backgroundPaint)
            }
        }
    }
}
