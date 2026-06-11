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
import android.hardware.input.InputManager
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
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
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withClip

/**
 * Floating magnifier lens shown while the user drags on a JP/ZH/Latin token
 * and persists post-release as the dictionary surface.
 *
 * Visual structure (matches design handoff in
 * ~/playtranslate/design_handoff_magnifying_lens):
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

class MagnifierLens(
    internal val rawCtx: Context,
    internal val wm: WindowManager,
    private val displayId: Int,
    private val overlayHost: OverlayHost? = null,
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

    /** Window's total height: card body + arrow strip + pill/chip overhang. */
    private val totalH = lensH + arrowSizePx + pillChipOverhangPx
    private val zoom = 2f
    /** Tolerance for the lens overrunning the top of the screen before we
     *  flip it below the finger; matches the original feel. */
    private val topOverhangTolerancePx = lensH / 5
    /** Extra slack on the flip threshold: the lens flips below the finger
     *  32dp earlier — while the finger is still 32dp lower on the screen
     *  than the geometric fit alone would require. */
    private val flipBiasPx = dp(32f)

    private var lensView: LensView? = null
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

    /** Fires once per actual window teardown (tap-outside in sticky mode,
     *  new drag start, [dismiss] caller). */
    var onDismiss: (() -> Unit)? = null
    /** Fires when the card or pill is tapped in sticky mode. */
    var onOpenTap: (() -> Unit)? = null
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

    fun setBitmap(bitmap: Bitmap?) {
        lensView?.setSourceBitmap(bitmap)
    }

    /** Update the word + reading on the pill. Pass null for either to hide. */
    fun setLabel(word: String?, reading: String?) {
        lensView?.setLabel(word, reading)
    }

    fun setDefinitions(data: WordDefinitionData?, label: String?) {
        lensView?.setDefinitions(data, label)
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
        val p = params ?: return
        // The interactive card needs window focus only to receive analog-
        // stick motion for stick-nudge dismissal. With no game controller
        // attached there is no stick, so stay non-focusable: that keeps the
        // window from becoming the system-bar owner and showing the nav
        // pill over an immersive game. Touch and outside-touch dismissal
        // work either way — neither needs focus.
        var flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        if (!hasGameController()) {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        p.flags = flags
        try { wm.updateViewLayout(view, p) } catch (_: Exception) {}
        view.attachInteractiveListeners(onDismissRequest = { dismiss() })
        // Arrow x stays inside the card region — clamped so the triangle's
        // tip lands somewhere over the card, not the chip-halo padding.
        val cardLeftInView = chipHaloXPx
        val cardW = p.width - 2 * chipHaloXPx
        val arrowRelX = (lastFingerX - p.x).coerceIn(
            cardLeftInView + arrowSizePx,
            cardLeftInView + cardW - arrowSizePx,
        )
        view.setArrowVisible(true, arrowRelX)
    }

    /** True when a physical game controller (gamepad / joystick) is
     *  connected — including a handheld's built-in controls, which report
     *  as a real SOURCE_GAMEPAD / SOURCE_JOYSTICK device. Gates whether the
     *  interactive card takes window focus; see [makeInteractive]. */
    private fun hasGameController(): Boolean {
        val inputManager = rawCtx.getSystemService(InputManager::class.java)
            ?: return false
        for (id in inputManager.inputDeviceIds) {
            val sources = inputManager.getInputDevice(id)?.sources ?: continue
            if (sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
                sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
            ) {
                return true
            }
        }
        return false
    }

    /** Window flags for the lens in its non-interactive (zoom) state. On the
     *  MediaProjection backend the window must stay touchable: a non-touchable
     *  TYPE_APPLICATION_OVERLAY is opacity-capped by the anti-tapjacking rule
     *  and renders washed out. The drag gesture is owned by the floating
     *  icon's window, so a touchable lens window does not steal it. */
    private fun zoomWindowFlags(): Int {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        if (overlayHost?.windowType != WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY) {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        return flags
    }

    fun resetToZoom() {
        val view = lensView ?: return
        val p = params ?: return
        p.flags = zoomWindowFlags()
        try { wm.updateViewLayout(view, p) } catch (_: Exception) {}
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
        ensureWindow(cardW, viewW)
        val view = lensView ?: return
        val p = params ?: return

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
        view.isVisible = true

        lastFingerX = fingerX

        // The card is centered inside the view (chipHaloX margin on each
        // side). Centering the view on the finger lands the card center on
        // the finger too.
        val x = (fingerX - viewW / 2).coerceIn(0, (screenW - viewW).coerceAtLeast(0))
        val bodyTopOffsetInView = if (!flipped) pillChipOverhangPx else arrowSizePx
        val lensBodyY = if (!flipped) {
            aboveY
        } else {
            (fingerY + anchorHeight + verticalMarginPx)
                .coerceAtMost((screenH - lensH).coerceAtLeast(0))
        }
        val y = lensBodyY - bodyTopOffsetInView
        if (p.x != x || p.y != y) {
            p.x = x
            p.y = y
            try { wm.updateViewLayout(view, p) } catch (_: Exception) {}
        } else {
            view.invalidate()
        }
    }

    fun hide() {
        lensView?.visibility = View.INVISIBLE
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
        val view = lensView ?: return
        lensView = null
        params = null
        if (overlayHost != null) {
            overlayHost.removeOverlayWindow(view)
        } else {
            try { wm.removeView(view) } catch (_: Exception) {}
        }
    }

    private fun ensureWindow(cardW: Int, viewW: Int) {
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
            onAnkiTap = { onAnkiTap?.invoke() },
            onAnkiLongPress = { onAnkiLongPress?.invoke() },
            onSpeakTap = { onSpeakTap?.invoke() },
        )
        val windowType = if (useActivityWindow)
            WindowManager.LayoutParams.TYPE_APPLICATION_PANEL
        else
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        val lp = WindowManager.LayoutParams(
            viewW, totalH,
            windowType,
            zoomWindowFlags(),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
        val attached = if (overlayHost != null) {
            overlayHost.addOverlayWindow(view, wm, lp, displayId)
        } else {
            try { wm.addView(view, lp); true } catch (_: Exception) { false }
        }
        if (!attached) return
        lensView = view
        params = lp
    }

    /**
     * Host view for the redesigned lens. Layout from top to bottom (in the
     * default, not-flipped, lens-above-finger case):
     *   [0,                              pillChipOverhangPx)      pill / chip halo overhang strip
     *   [pillChipOverhangPx,             pillChipOverhangPx+lensH) card body
     *   [pillChipOverhangPx+lensH,       totalH)                   arrow strip
     * When flipped, the arrow strip moves to the top and the pill/chip
     * overhang moves to the bottom; [bodyTopOffset] tracks the shift.
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
        private val onAnkiTap: () -> Unit,
        private val onAnkiLongPress: () -> Unit,
        private val onSpeakTap: () -> Unit,
    ) : FrameLayout(ctx) {
        private fun dp(v: Float): Int = (density * v).toInt()
        /** Replace the alpha byte of [color] with [alpha] (0..255). Used to
         *  layer the spec's design alphas onto themed RGB tokens — e.g.
         *  the card border is the theme's primary-text color at 16%. */
        private fun withAlpha(color: Int, alpha: Int): Int =
            (color and 0x00FFFFFF) or ((alpha and 0xFF) shl 24)
        private val cardBorderPx = density * 1f

        private enum class Mode { ZOOM, DEFINITIONS, LOADING }
        private var mode: Mode = Mode.ZOOM

        private var lensFlipped = false
        /** Y of the card's top edge within the view. */
        private val bodyTopOffset: Int
            get() = if (lensFlipped) arrowSizePx else pillChipOverhangPx
        private val cardBottomInView: Int get() = bodyTopOffset + lensH
        /** Y of the line the pill is centered on — the card edge opposite
         *  the arrow (top when not flipped, bottom when flipped). */
        private val pillAnchorY: Int
            get() = if (lensFlipped) cardBottomInView else bodyTopOffset
        private val cardLeftInView: Int get() = chipHaloXPx
        private val cardRightInView: Int get() = chipHaloXPx + cardW

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
            makeChipIcon(R.drawable.ic_card_stack),
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
            addView(definitionsContent)
            visibility = GONE
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
            definitionsScroll.layoutParams = LayoutParams(
                cardW - 2 * bodyEdgeBufferPx, lensH - 2 * bodyEdgeBufferPx,
                Gravity.START or Gravity.TOP,
            ).apply {
                marginStart = chipHaloXPx + bodyEdgeBufferPx
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
                Gravity.START or Gravity.TOP,
            ).apply { topMargin = chipTopMargin }
            rightChip.layoutParams = LayoutParams(
                chipHitSizePx, chipHitSizePx,
                Gravity.END or Gravity.TOP,
            ).apply { topMargin = chipTopMargin }
        }

        /** Debounce so the open handler can't fire twice from a single
         *  gesture that crosses the open detector and any other receiver. */
        private var lastOpenTapMs = 0L
        private fun fireOpenTap() {
            val now = SystemClock.uptimeMillis()
            if (now - lastOpenTapMs < 300L) return
            lastOpenTapMs = now
            onOpenTap()
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
        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            val handled = super.dispatchTouchEvent(ev)
            if (isInteractive) {
                if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                    tapGestureActive = isTapEligible(ev.x, ev.y)
                }
                if (tapGestureActive) tapDetector.onTouchEvent(ev)
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
            return true
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
            val showReading = pitchKana != null && newState == PillState.Word

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
            pillWordView.text = newWord
            if (newPitch.isEmpty()) {
                pillReadingView.text = newReading
                fitPillReadingSize(newWord, newReading)
                pillReadingView.setPadding(0, 0, 0, 0)
            } else {
                pillReadingView.text = buildPitchAnnotatedReading(newReading, newPitch)
                // Fit against reading + suffix; the suffix is measured at
                // full size though it renders at 0.75× — erring roomy.
                fitPillReadingSize(
                    newWord,
                    newReading + " " + newPitch.joinToString("·") { "[$it]" },
                )
                // Symmetric padding: headroom for the overline above,
                // mirrored below so the glyphs stay centered in the fixed-
                // height pill. Scales with the post-fit text size.
                val pad = (pillReadingView.textSize * 0.30f).toInt()
                pillReadingView.setPadding(0, pad, 0, pad)
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

        /** Shrink the reading text 1sp at a time down to 11sp until the
         *  whole pill fits inside the card width. Stateless across calls.
         *  Returning to max sp on short readings is automatic — every call
         *  starts the search from [pillReadingMaxSp]. */
        private fun fitPillReadingSize(word: String, reading: String) {
            pillReadingView.setTextSize(TypedValue.COMPLEX_UNIT_SP, pillReadingMaxSp)
            if (reading.isEmpty() || word.isEmpty()) return
            // The pill can grow to (at most) the inside-the-card-padding
            // width minus a safety margin. We don't have an authoritative
            // visual budget, so cap at cardW - 2 × bodyHPaddingPx to leave
            // breathing room on each side; the chip's visible disks sit
            // ~36dp inside the card edge on each side anyway, so the pill
            // comfortably owns the middle.
            val available = (cardW - 2 * bodyHPaddingPx).toFloat()
            pillWordSizingPaint.textSize = pillWordSp * density
            val wordWidth = pillWordSizingPaint.measureText(word)
            val fixed = wordWidth +
                pillDividerWidth.toFloat() +
                pillChevronSize.toFloat() +
                (pillGap * 2).toFloat() +
                pillChevronMarginStart.toFloat() +
                pillPaddingLead.toFloat() +
                pillPaddingTrail.toFloat()
            val readingAvailable = (available - fixed).coerceAtLeast(0f)
            var sp = pillReadingMaxSp
            while (sp > pillReadingMinSp) {
                pillReadingSizingPaint.textSize = sp * density
                if (pillReadingSizingPaint.measureText(reading) <= readingAvailable) break
                sp -= 1f
            }
            pillReadingView.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        }

        fun setDefinitions(data: WordDefinitionData?, label: String?) {
            if (data == null) {
                if (mode == Mode.ZOOM) return
                mode = Mode.ZOOM
                definitionsScroll.visibility = GONE
                leftChip.visibility = GONE
                rightChip.visibility = GONE
                invalidate()
                return
            }
            mode = Mode.DEFINITIONS
            setLabel(data.word, data.reading, data.pitch)
            definitionsContent.bind(data, label, LENS_DEFINITIONS_SCALE)
            definitionsScroll.scrollTo(0, 0)
            definitionsScroll.visibility = VISIBLE
            // Chip visibility is owned by [attachInteractiveListeners] —
            // they only appear once the lens becomes sticky.
            invalidate()
        }

        fun setLoading(word: String?, reading: String?) {
            mode = Mode.LOADING
            setLabel(word, reading)
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
            setOnGenericMotionListener { _, event ->
                if (event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
                    && event.action == MotionEvent.ACTION_MOVE
                ) {
                    val axisX = event.getAxisValue(MotionEvent.AXIS_X)
                    val axisY = event.getAxisValue(MotionEvent.AXIS_Y)
                    if (axisX * axisX + axisY * axisY > 0.25f) {
                        onDismissRequest()
                        true
                    } else false
                } else false
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
            clearFocus()
            isInteractive = false
            leftChip.animate().cancel()
            rightChip.animate().cancel()
            leftChip.translationX = 0f
            rightChip.translationX = 0f
            leftChip.visibility = GONE
            rightChip.visibility = GONE
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

        /** Gap between the chip's visible disk and the pill's outer edge
         *  in the resting (post-reveal) layout. */
        private val chipPillGapPx = dp(14f)

        /** Lay the chips at their final positions — visible disks
         *  [chipPillGapPx] away from the pill's left and right edges —
         *  then translate them back inward so they sit under the pill,
         *  set visibility, and animate translationX back to 0 so they
         *  slide out from under the pill to their resting places. */
        private fun revealChips() {
            val pillNaturalWidth = measurePillNaturalWidth()
            val pillLeft = (viewW - pillNaturalWidth) / 2
            val pillRight = pillLeft + pillNaturalWidth
            val chipTopMargin = pillAnchorY - chipHitSizePx / 2

            leftChip.layoutParams = LayoutParams(
                chipHitSizePx, chipHitSizePx,
                Gravity.START or Gravity.TOP,
            ).apply {
                marginStart = pillLeft - chipVisDiameterPx - chipHaloPadPx - chipPillGapPx
                topMargin = chipTopMargin
            }
            rightChip.layoutParams = LayoutParams(
                chipHitSizePx, chipHitSizePx,
                Gravity.END or Gravity.TOP,
            ).apply {
                marginEnd = (viewW - pillRight) - chipVisDiameterPx - chipHaloPadPx - chipPillGapPx
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
            rightChip.visibility = VISIBLE
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
                cardRightInView - inset, bodyTop + lensH - inset,
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
                baseY = arrowSizePx.toFloat()
                tipY = 0f
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
