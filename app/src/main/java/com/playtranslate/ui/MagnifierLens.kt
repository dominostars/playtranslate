package com.playtranslate.ui

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
import com.playtranslate.OverlayColors
import com.playtranslate.PlayTranslateAccessibilityService
import com.playtranslate.R

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
class MagnifierLens(
    private val ctx: Context,
    private val wm: WindowManager,
    private val displayId: Int,
) {
    /** Definitions payload for the lens body. Mirrors the popup's fields. */
    data class LensDefinitionData(
        val word: String,
        val reading: String?,
        val senses: List<WordLookupPopup.SenseDisplay>,
        val freqScore: Int,
        val isCommon: Boolean,
    )

    private val density = ctx.resources.displayMetrics.density
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
    private val chipVisDiameterPx = dp(32f)
    private val chipHitSizePx = dp(48f)
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

    private var lensView: LensView? = null
    private var params: WindowManager.LayoutParams? = null

    /** Most recent finger x from [show]. Used by [makeInteractive] to align
     *  the sticky-mode arrow horizontally with the release position. */
    private var lastFingerX = 0

    /** Fires once per actual window teardown (tap-outside in sticky mode,
     *  new drag start, [dismiss] caller). */
    var onDismiss: (() -> Unit)? = null
    /** Fires when the card or pill is tapped in sticky mode. */
    var onOpenTap: (() -> Unit)? = null

    val isInteractive: Boolean get() = lensView?.isInteractive == true

    fun setBitmap(bitmap: Bitmap?) {
        lensView?.setSourceBitmap(bitmap)
    }

    /** Update the word + reading on the pill. Pass null for either to hide. */
    fun setLabel(word: String?, reading: String?) {
        lensView?.setLabel(word, reading)
    }

    fun setDefinitions(data: LensDefinitionData?, label: String?) {
        lensView?.setDefinitions(data, label)
    }

    fun setLoading(word: String?, reading: String?) {
        lensView?.setLoading(word, reading)
    }

    fun makeInteractive() {
        val view = lensView ?: return
        val p = params ?: return
        p.flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
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

    fun resetToZoom() {
        val view = lensView ?: return
        val p = params ?: return
        p.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
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

    fun show(fingerX: Int, fingerY: Int, screenW: Int, screenH: Int) {
        val cardW = cardWidth(screenW)
        val viewW = cardW + 2 * chipHaloXPx
        ensureWindow(cardW, viewW)
        val view = lensView ?: return
        val p = params ?: return

        val aboveY = fingerY - verticalMarginPx - lensH
        val flipped = aboveY < -topOverhangTolerancePx
        view.setSourcePoint(fingerX.toFloat(), fingerY.toFloat(), screenW, screenH)
        view.setLensFlipped(flipped)
        view.visibility = View.VISIBLE

        lastFingerX = fingerX

        // The card is centered inside the view (chipHaloX margin on each
        // side). Centering the view on the finger lands the card center on
        // the finger too.
        val x = (fingerX - viewW / 2).coerceIn(0, (screenW - viewW).coerceAtLeast(0))
        val bodyTopOffsetInView = if (!flipped) pillChipOverhangPx else arrowSizePx
        val lensBodyY = if (!flipped) {
            aboveY
        } else {
            (fingerY + verticalMarginPx).coerceAtMost((screenH - lensH).coerceAtLeast(0))
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
        val view = lensView ?: return
        lensView = null
        params = null
        PlayTranslateAccessibilityService.removeOverlay(view, wm)
        onDismiss?.invoke()
    }

    private fun ensureWindow(cardW: Int, viewW: Int) {
        if (lensView != null) return
        val view = LensView(
            ctx = ctx,
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
            density = density,
            onOpenTap = { onOpenTap?.invoke() },
        )
        val lp = WindowManager.LayoutParams(
            viewW, totalH,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
        if (!PlayTranslateAccessibilityService.addOverlay(view, wm, lp, displayId)) return
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
        private val density: Float,
        private val onOpenTap: () -> Unit,
    ) : FrameLayout(ctx) {
        private fun dp(v: Float): Int = (density * v).toInt()
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

        // Pill background tracks the user's selected accent so the lens
        // respects theming. The dark card chrome (BWI_PANEL #15181B, the
        // 1dp white-16% border, the brand arrow color #8B3F2D) is fixed
        // per the hifi spec — these are coral-paired chrome colors, not
        // user-tunable theme tokens.
        private val accentColor = OverlayColors.accent(ctx)
        private val accentOnColor = OverlayColors.accentOn(ctx)
        private val cardBgColor = Color.parseColor("#15181B")
        private val cardBorderColor = Color.argb(41, 255, 255, 255)
        private val arrowColor = Color.parseColor("#8B3F2D")
        private val chipBgColor = Color.argb(217, 21, 24, 27)
        private val chipBorderColor = Color.argb(56, 255, 255, 255)
        private val chipIconColor = Color.argb(209, 245, 235, 225)
        // Pill ink alphas mirror the spec (1.0 / 0.22 / 0.72 / 0.5) applied
        // over [accentOnColor]. accentOn is the app's contrast-paired text
        // color for the selected accent — using it (rather than the spec's
        // hardcoded #1a0c08) means a non-coral accent still gets readable
        // ink on its pill.
        private val pillInkColor = accentOnColor
        private val pillInkDivider = (accentOnColor and 0x00FFFFFF) or (0x38 shl 24)
        private val pillInkReading = (accentOnColor and 0x00FFFFFF) or (0xB8 shl 24)
        private val pillInkChevron = (accentOnColor and 0x00FFFFFF) or (0x80 shl 24)
        // Body content uses the existing overlay tokens (unchanged from
        // the prior left-panel layout) so the dictionary rows match the
        // rest of the app.
        private val panelPrimaryText = OverlayColors.text(ctx)
        private val panelSecondaryText = OverlayColors.textMuted(ctx)
        private val panelBadgeBg = OverlayColors.surface(ctx)
        private val panelWarnColor = Color.parseColor("#D4A017")

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
        private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = arrowColor
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
            val bitmap = Bitmap.createBitmap(cardW, lensH, Bitmap.Config.ARGB_8888)
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
        private val pillDividerWidth = density.toInt().coerceAtLeast(1)
        private val pillDividerHeight = dp(22f)
        private val pillChevronSize = dp(13f)
        private val pillChevronMarginStart = dp(4f)
        private val pillWordSp = 24f
        private val pillReadingMaxSp = 14f
        private val pillReadingMinSp = 11f
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
            typeface = Typeface.DEFAULT
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        private val pillChevronView = ImageView(ctx).apply {
            val d = AppCompatResources.getDrawable(ctx, R.drawable.ic_lens_chevron)?.mutate()
            if (d != null) {
                DrawableCompat.setTint(d, pillInkChevron)
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
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(accentColor)
                // 99dp in spec; equivalent to "fully rounded capsule" — i.e.
                // corner radius >= half the pill height.
                cornerRadius = pillHeightPx / 2f
            }
            addView(pillWordView)
            addView(pillDividerView)
            addView(pillReadingView)
            addView(pillChevronView)
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
        private val pillReadingSizingPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        // -----------------------------------------------------------------
        // Chips: Speak (left), Anki (right). No-op for this commit.
        // -----------------------------------------------------------------
        private val leftChip = makeChip(R.drawable.ic_lens_speak)
        private val rightChip = makeChip(R.drawable.ic_card_stack)

        private fun makeChip(iconRes: Int): FrameLayout {
            val chip = FrameLayout(context).apply {
                isClickable = true
                setOnClickListener { /* no-op — wired in a follow-up commit */ }
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
            val icon = ImageView(context).apply {
                val d = AppCompatResources.getDrawable(context, iconRes)?.mutate()
                if (d != null) {
                    DrawableCompat.setTint(d, chipIconColor)
                    setImageDrawable(d)
                }
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                layoutParams = LayoutParams(dp(16f), dp(16f), Gravity.CENTER)
            }
            chip.addView(disk)
            chip.addView(icon)
            return chip
        }

        // -----------------------------------------------------------------
        // Body: existing definitions ScrollView, full card width.
        // -----------------------------------------------------------------
        private val definitionsContent = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }
        private val definitionsScroll = ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.CENTER_VERTICAL }
            isVerticalScrollBarEnabled = true
            isFillViewport = false
            addView(definitionsContent)
        }
        private val bodyPaddingTopUnflipped = dp(30f)
        private val bodyPaddingTopFlipped = dp(12f)
        private val bodyPaddingBottomUnflipped = dp(12f)
        private val bodyPaddingBottomFlipped = dp(30f)
        private val bodyPaddingHorizontal = dp(18f)
        /** Card body container. Holds the definitions scroll; padding-top is
         *  30dp by default so the pill overhanging from above doesn't
         *  collide with the meta row. Flips when the lens flips. */
        private val bodyPanel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                bodyPaddingHorizontal, bodyPaddingTopUnflipped,
                bodyPaddingHorizontal, bodyPaddingBottomUnflipped,
            )
            addView(definitionsScroll)
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
            addView(bodyPanel)
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

        /** Recompute layout params for the body panel, pill, and chips based
         *  on [lensFlipped]. The pill sits on the card edge opposite the
         *  arrow, and the chips top-align (or bottom-align when flipped)
         *  with the pill so they share its vertical band. */
        private fun updateChromeLayout() {
            // Body panel — full card width, lensH tall, positioned at
            // bodyTopOffset within the view. Padding flips so the pad that
            // clears the pill is always on the pill side.
            val topPad = if (lensFlipped) bodyPaddingTopFlipped else bodyPaddingTopUnflipped
            val bottomPad = if (lensFlipped) bodyPaddingBottomFlipped else bodyPaddingBottomUnflipped
            bodyPanel.setPadding(bodyPaddingHorizontal, topPad, bodyPaddingHorizontal, bottomPad)
            bodyPanel.layoutParams = LayoutParams(
                cardW, lensH,
                Gravity.START or Gravity.TOP,
            ).apply {
                marginStart = chipHaloXPx
                topMargin = bodyTopOffset
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

        fun setLabel(word: String?, reading: String?) {
            val w = word?.takeIf { it.isNotEmpty() }
            val r = reading?.takeIf { it.isNotEmpty() }
            pillWordView.text = w.orEmpty()
            pillReadingView.text = r.orEmpty()
            val showReading = r != null
            pillDividerView.visibility = if (showReading) VISIBLE else GONE
            pillReadingView.visibility = if (showReading) VISIBLE else GONE
            fitPillReadingSize(w.orEmpty(), r.orEmpty())
            pillView.visibility = if (w == null) GONE else VISIBLE
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
            // visual budget, so cap at cardW - 2 × bodyPaddingHorizontal to
            // leave breathing room on each side; the chip's visible disks
            // sit ~36dp inside the card edge on each side anyway, so the
            // pill comfortably owns the middle.
            val available = (cardW - 2 * bodyPaddingHorizontal).toFloat()
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

        fun setDefinitions(data: MagnifierLens.LensDefinitionData?, label: String?) {
            if (data == null) {
                if (mode == Mode.ZOOM) return
                mode = Mode.ZOOM
                bodyPanel.visibility = GONE
                leftChip.visibility = GONE
                rightChip.visibility = GONE
                invalidate()
                return
            }
            mode = Mode.DEFINITIONS
            setLabel(data.word, data.reading)
            populateDefinitions(data, label)
            definitionsScroll.scrollTo(0, 0)
            bodyPanel.visibility = VISIBLE
            // Chip visibility is owned by [attachInteractiveListeners] —
            // they only appear once the lens becomes sticky.
            invalidate()
        }

        fun setLoading(word: String?, reading: String?) {
            mode = Mode.LOADING
            setLabel(word, reading)
            populateLoading()
            definitionsScroll.scrollTo(0, 0)
            bodyPanel.visibility = VISIBLE
            invalidate()
        }

        var isInteractive: Boolean = false
            private set

        fun attachInteractiveListeners(onDismissRequest: () -> Unit) {
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
            // drag-time UI).
            if (mode == Mode.DEFINITIONS) {
                leftChip.visibility = VISIBLE
                rightChip.visibility = VISIBLE
            }
        }

        fun detachInteractiveListeners() {
            setOnTouchListener(null)
            setOnGenericMotionListener(null)
            clearFocus()
            isInteractive = false
            leftChip.visibility = GONE
            rightChip.visibility = GONE
        }

        private fun populateDefinitions(
            data: MagnifierLens.LensDefinitionData,
            label: String?,
        ) {
            val ctx = context
            definitionsContent.removeAllViews()

            if (data.isCommon || data.freqScore > 0) {
                val metaRow = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, 0, 0, dp(4f))
                }
                if (data.isCommon) {
                    metaRow.addView(TextView(ctx).apply {
                        text = "common"
                        setTextColor(panelSecondaryText)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
                        typeface = Typeface.DEFAULT_BOLD
                        setPadding(dp(5f), dp(1f), dp(5f), dp(1f))
                        background = GradientDrawable().apply {
                            setColor(panelBadgeBg)
                            cornerRadius = density * 4f
                        }
                    })
                }
                if (data.freqScore > 0) {
                    metaRow.addView(TextView(ctx).apply {
                        text = "★".repeat(data.freqScore.coerceAtMost(5))
                        setTextColor(panelSecondaryText)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                        if (data.isCommon) setPadding(dp(6f), 0, 0, 0)
                    })
                }
                definitionsContent.addView(metaRow)
            }

            if (label != null) {
                definitionsContent.addView(TextView(ctx).apply {
                    text = label
                    setTextColor(panelWarnColor)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(0, 0, 0, dp(4f))
                })
            }

            data.senses.forEachIndexed { i, sense ->
                if (sense.pos.isNotBlank()) {
                    definitionsContent.addView(TextView(ctx).apply {
                        text = sense.pos
                        setTextColor(panelSecondaryText)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                        typeface = Typeface.DEFAULT_BOLD
                        if (i > 0) setPadding(0, dp(6f), 0, 0)
                    })
                }
                definitionsContent.addView(TextView(ctx).apply {
                    text = "${i + 1}. ${sense.definition}"
                    setTextColor(panelPrimaryText)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                })
            }
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
                canvas.save()
                canvas.clipPath(clipPath)
                canvas.translate(cardLeftInView.toFloat(), bodyTopOffset.toFloat())
                drawZoom(canvas, cardW.toFloat(), lensH.toFloat())
                canvas.restore()
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
            arrowPath.reset()
            val cx = arrowOffsetX.toFloat()
            val halfBase = arrowSizePx.toFloat()
            if (lensFlipped) {
                val baseY = arrowSizePx.toFloat()
                arrowPath.moveTo(cx - halfBase, baseY)
                arrowPath.lineTo(cx + halfBase, baseY)
                arrowPath.lineTo(cx, 0f)
            } else {
                val baseY = cardBottomInView.toFloat()
                val tipY = baseY + arrowSizePx
                arrowPath.moveTo(cx - halfBase, baseY)
                arrowPath.lineTo(cx + halfBase, baseY)
                arrowPath.lineTo(cx, tipY)
            }
            arrowPath.close()
            canvas.drawPath(arrowPath, arrowPaint)
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
