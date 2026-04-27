package com.playtranslate.ui

import android.content.Context
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
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.DrawableCompat
import com.playtranslate.OverlayColors
import com.playtranslate.PlayTranslateAccessibilityService
import com.playtranslate.R

/**
 * Rounded-rect magnifier overlay shown while the floating icon is dragged
 * and persists post-release as the dictionary surface (replaces the
 * separate `WordLookupPopup` in the drag flow).
 *
 * Three render modes:
 *  - **ZOOM** (default during drag): zoomed screenshot + crosshair on the
 *    right, optional accent left panel with word + reading.
 *  - **DEFINITIONS_DRAG**: dwell-triggered preview during drag — left panel
 *    stays, right side becomes the popup-formatted definitions panel.
 *  - **DEFINITIONS_STICKY**: post-release interactive lens — same visuals
 *    as DEFINITIONS_DRAG, but the window flags flip to focusable +
 *    touchable + outside-watch so tap-outside / joystick / Open-button
 *    interactions work. Set via [makeInteractive].
 *
 * Position: centered over the finger during drag; sticky lens stays where
 * the drag ended. Above the finger by default; flips below if the lens
 * would overrun the top of the screen.
 */
class MagnifierLens(
    private val ctx: Context,
    private val wm: WindowManager,
) {
    /** Definitions payload for the right panel. Mirrors the fields the popup
     *  shows in its middle column. `senses` is reused from `WordLookupPopup`
     *  so the controller can pass the same display rows it builds today. */
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
    private val cornerR = dp(18f).toFloat()

    /** Distance in px between finger center and the near edge of the lens. */
    private val verticalMarginPx = dp(25f)
    private val zoom = 2f
    /** How far the lens may slide off the top of the screen before we flip
     *  it below the finger. Tolerating some clipping avoids a jarring flip
     *  the instant the lens touches the top edge. */
    private val topOverhangTolerancePx = lensH / 5

    private var lensView: LensView? = null
    private var params: WindowManager.LayoutParams? = null

    /** Fires once per actual window teardown — tap-outside in sticky mode,
     *  new drag start, [cancelDrag], or any [dismiss] caller. The drag
     *  controller wires this to its `onSettled` to resume live mode. */
    var onDismiss: (() -> Unit)? = null
    /** Fires when the Open button on the definitions panel is tapped. */
    var onOpenTap: (() -> Unit)? = null

    /** True when the lens is in sticky-definitions mode — focusable,
     *  touchable, watch-outside-touch active. Single source of truth
     *  driven by [makeInteractive] / [resetToZoom] / [dismiss]; the
     *  controller's `isPopupShowing` queries this directly so there's
     *  no parallel state to drift. */
    val isInteractive: Boolean get() = lensView?.isInteractive == true

    fun setBitmap(bitmap: Bitmap?) {
        lensView?.setSourceBitmap(bitmap)
    }

    /** Set the word + reading shown in the lens's left panel. Pass null
     *  for either to hide that line. The controller calls this from
     *  onDragMove with the surface text of the token under the finger. */
    fun setLabel(word: String?, reading: String?) {
        lensView?.setLabel(word, reading)
    }

    /** Switch the lens between ZOOM and DEFINITIONS rendering. Pass `data`
     *  to populate the right-side definitions panel and switch to
     *  DEFINITIONS mode; pass null to revert to ZOOM. The left-panel label
     *  is updated from `data.word/reading` when entering DEFINITIONS mode;
     *  ZOOM mode leaves whatever label the controller most recently set. */
    fun setDefinitions(data: LensDefinitionData?, label: String?) {
        lensView?.setDefinitions(data, label)
    }

    /** Promote the lens from drag-mode (NOT_FOCUSABLE | NOT_TOUCHABLE) to
     *  sticky-mode (focusable, touchable, outside-watch). After this call,
     *  taps outside the lens fire [onDismiss], the Open button accepts
     *  clicks, and joystick deflection past the dead-zone dismisses too. */
    fun makeInteractive() {
        val view = lensView ?: return
        val p = params ?: return
        // Wholesale flag rewrite: drop NOT_FOCUSABLE | NOT_TOUCHABLE, add
        // NOT_TOUCH_MODAL | WATCH_OUTSIDE_TOUCH. Keep LAYOUT_NO_LIMITS so
        // the existing top-edge overhang doesn't jump on transition.
        p.flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        try { wm.updateViewLayout(view, p) } catch (_: Exception) {}
        view.attachInteractiveListeners(onDismissRequest = { dismiss() })
    }

    /** Demote the lens window back to drag-mode flags + zoom rendering and
     *  clear any lingering sticky state. Used by the controller at the
     *  start of a new drag — preferred over `dismiss()`+`show()` because
     *  it keeps the existing window registered with the overlay registry,
     *  avoiding the race where a fresh lens at alpha=1 lands inside an
     *  in-flight clean-capture's prepare/take window and contaminates the
     *  screenshot. No-op if the lens window doesn't exist. */
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
    }

    /** Lens width tracks WordLookupPopup's `baseW` (no open-button column —
     *  the lens has no buttons on the right while zooming). The popup
     *  subtracts dp(24) for its root FrameLayout's 12dp+12dp horizontal
     *  padding before splitting; the lens has no such padding, so left-
     *  panel width is a flat 25% of lensW.
     *    lensW = min(screenW × 0.85, dp(360))
     *    leftW = lensW × 0.25
     *  Returns (lensW, leftPanelW) — both in pixels. */
    private fun lensDimensions(screenW: Int): Pair<Int, Int> {
        val lensW = (screenW * 0.85f).toInt().coerceAtMost(dp(360f))
        val leftW = (lensW * 0.25f).toInt()
        return lensW to leftW
    }

    /** Show the lens (creates the window on first call) or update its
     *  position. The window is anchored so the lens's center is over the
     *  finger — the crosshair lives at that center, so the visual focal
     *  point stays on the actual finger position. */
    fun show(fingerX: Int, fingerY: Int, screenW: Int, screenH: Int) {
        val (lensW, leftPanelW) = lensDimensions(screenW)
        ensureWindow(lensW, leftPanelW)
        val view = lensView ?: return
        val p = params ?: return

        val aboveY = fingerY - verticalMarginPx - lensH
        val flipped = aboveY < -topOverhangTolerancePx
        view.setSourcePoint(fingerX.toFloat(), fingerY.toFloat(), screenW, screenH)
        view.visibility = View.VISIBLE

        val x = (fingerX - lensW / 2).coerceIn(0, (screenW - lensW).coerceAtLeast(0))
        val y = if (!flipped) {
            aboveY
        } else {
            (fingerY + verticalMarginPx).coerceAtMost((screenH - lensH).coerceAtLeast(0))
        }
        if (p.x != x || p.y != y) {
            p.x = x
            p.y = y
            try { wm.updateViewLayout(view, p) } catch (_: Exception) {}
        } else {
            view.invalidate()
        }
    }

    /** Hide the lens without releasing the window. Cheap to call repeatedly. */
    fun hide() {
        lensView?.visibility = View.INVISIBLE
    }

    /** Tear down the window entirely. Fires [onDismiss] exactly once per
     *  teardown so a single code path serves tap-outside, new-drag,
     *  cancelDrag, and destroy paths. Subsequent calls are no-ops. */
    fun dismiss() {
        val view = lensView ?: return
        lensView = null
        params = null
        PlayTranslateAccessibilityService.removeOverlay(view, wm)
        onDismiss?.invoke()
    }

    private fun ensureWindow(lensW: Int, leftPanelW: Int) {
        if (lensView != null) return
        val view = LensView(
            ctx = ctx,
            lensW = lensW,
            lensH = lensH,
            leftPanelW = leftPanelW,
            cornerR = cornerR,
            zoom = zoom,
            onOpenTap = { onOpenTap?.invoke() },
        )
        val lp = WindowManager.LayoutParams(
            lensW, lensH,
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
        if (!PlayTranslateAccessibilityService.addOverlay(view, wm, lp)) return
        lensView = view
        params = lp
    }

    /**
     * The lens is a FrameLayout so the word/reading text and the definitions
     * rows can be hosted as real TextViews — letting the framework handle
     * SP sizing, autoSize, ellipsize, scrolling, and centering instead of
     * reimplementing them with paint / StaticLayout. The custom canvas work
     * (zoom, inset shadow, crosshair, border) lives in onDraw / draw around
     * the children, gated by [mode].
     */
    private class LensView(
        ctx: Context,
        private val lensW: Int,
        private val lensH: Int,
        leftPanelW: Int,
        private val cornerR: Float,
        private val zoom: Float,
        private val onOpenTap: () -> Unit,
    ) : FrameLayout(ctx) {
        private val density = ctx.resources.displayMetrics.density
        private fun dp(v: Float): Int = (density * v).toInt()
        private val borderPx = density * 2f
        private val rightPanelW = lensW - leftPanelW

        private enum class Mode { ZOOM, DEFINITIONS }
        private var mode: Mode = Mode.ZOOM

        // Overlay-context color resolution: themeColor(R.attr.pt*) is
        // unreliable from the accessibility service / floating-window
        // contexts because the Activity theme isn't applied. OverlayColors
        // reads the user's mode + accent directly from Prefs so the
        // definitions panel tracks the selected scheme.
        private val accentColor = OverlayColors.accent(ctx)
        private val accentOnColor = OverlayColors.accentOn(ctx)
        private val panelBgColor = OverlayColors.card(ctx)
        private val panelPrimaryText = OverlayColors.text(ctx)
        private val panelSecondaryText = OverlayColors.textMuted(ctx)
        private val panelBadgeBg = OverlayColors.surface(ctx)
        // Warn color stays semantic (no theme attr exposed via OverlayColors).
        private val panelWarnColor = Color.parseColor("#D4A017")

        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accentColor
            style = Paint.Style.STROKE
            strokeWidth = borderPx
        }
        // Painted under the zoom every frame so the parts of the right
        // panel that sample outside the source bitmap (finger near a
        // screen edge, or before the screenshot lands) read as solid
        // black instead of revealing the screen behind the lens window.
        private val backgroundPaint = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.FILL
        }
        private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        // Inset drop-shadow stroke just inside the border. The stroke is
        // drawn while the round-rect clip is active (see [draw]), so its
        // outer half is clipped to the lens shape and the inner half blurs
        // softly toward the content — a "lens-depth" effect that recesses
        // the zoom beneath the accent border. Skipped in DEFINITIONS mode
        // so the blur doesn't bleed under the panel's edges.
        private val insetShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(70, 0, 0, 0)
            style = Paint.Style.STROKE
            strokeWidth = density * 14f
            maskFilter = BlurMaskFilter(density * 8f, BlurMaskFilter.Blur.NORMAL)
        }
        private val insetShadowRect = RectF()
        private val insetShadowInset = density * 4f

        // Small accent-colored crosshair marking the focal point.
        private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accentColor
            style = Paint.Style.STROKE
            strokeWidth = density * 1.5f
            strokeCap = Paint.Cap.ROUND
        }
        private val crosshairHalfLen = density * 6f

        // Reading + word TextViews mirror the dictionary popup's left-column
        // properties exactly: SP sizes via TypedValue.COMPLEX_UNIT_SP, SP-
        // granularity autoSize on the word, framework gravity / ellipsize.
        // Colors swapped to on-accent (the lens panel is accent colored, not
        // the popup's #242424); reading uses ~75% alpha to recreate the
        // popup's #EFEFEF / #A0A0A0 contrast hierarchy.
        private val readingView = TextView(ctx).apply {
            setTextColor((accentOnColor and 0x00FFFFFF) or (0xC0 shl 24))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            visibility = GONE
        }
        private val wordView = TextView(ctx).apply {
            setTextColor(accentOnColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            maxLines = 1
            setAutoSizeTextTypeUniformWithConfiguration(
                10, 22, 1, TypedValue.COMPLEX_UNIT_SP
            )
        }
        // Left column hosts the labels and supplies the accent fill via its
        // background — the panel and labels appear/disappear together by
        // toggling its visibility, no separate canvas pass for the fill.
        private val leftCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(accentColor)
            addView(readingView)
            addView(wordView)
            visibility = GONE
            layoutParams = LayoutParams(
                leftPanelW,
                LayoutParams.MATCH_PARENT,
                Gravity.START or Gravity.CENTER_VERTICAL,
            )
        }

        // Definitions panel mirrors the popup's middle scrollable column +
        // divider + right Open button. Built once at init with empty
        // content; setDefinitions populates the meta/label/sense rows on
        // mode entry. Visibility toggled via setDefinitions.
        private val definitionsContent = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, dp(4f), 0)
        }
        private val definitionsScroll = ScrollView(ctx).apply {
            // Width=0 + weight=1 fills horizontally inside HORIZONTAL parent.
            // WRAP_CONTENT height + CENTER_VERTICAL gravity keeps short
            // definitions vertically centered in the panel; longer content
            // gets capped by the panel's bounded height and scrolls.
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f,
            ).apply { gravity = Gravity.CENTER_VERTICAL }
            isVerticalScrollBarEnabled = true
            isFillViewport = false
            addView(definitionsContent)
        }
        private val openButton = ImageView(ctx).apply {
            val drawable = AppCompatResources
                .getDrawable(ctx, R.drawable.ic_open_in_new)
                ?.mutate()
            if (drawable != null) {
                DrawableCompat.setTint(drawable, accentColor)
                setImageDrawable(drawable)
            }
            setPadding(dp(7f), dp(4f), dp(1f), dp(4f))
            layoutParams = LinearLayout.LayoutParams(
                dp(31f),
                LinearLayout.LayoutParams.MATCH_PARENT,
            ).apply { gravity = Gravity.CENTER_VERTICAL }
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setOnClickListener { onOpenTap() }
        }
        private val definitionsPanel = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(panelBgColor)
            setPadding(dp(8f), dp(8f), 0, dp(8f))
            visibility = GONE
            addView(definitionsScroll)
            addView(openButton)
            layoutParams = LayoutParams(
                rightPanelW,
                LayoutParams.MATCH_PARENT,
                Gravity.END or Gravity.CENTER_VERTICAL,
            )
        }

        private val clipPath = Path()
        private val srcRect = Rect()
        private val dstRect = RectF()
        private val borderRect = RectF()

        private var sourceBitmap: Bitmap? = null
        private var sourceX = 0f
        private var sourceY = 0f
        // Screen dimensions used as the in/out-screenshot boundary while
        // the bitmap hasn't landed yet. The boundary is the same as the
        // bitmap's own bounds once it arrives (screenshots are display-
        // sized), so the lens can mark off-screen regions black even
        // before OCR runs.
        private var sourceScreenW = 0
        private var sourceScreenH = 0

        init {
            // BlurMaskFilter (used by the inset shadow) is unreliable on
            // hardware-accelerated layers across devices; software layer
            // is the consistent path. Same convention as the region
            // indicator overlay elsewhere in the app.
            setLayerType(LAYER_TYPE_SOFTWARE, null)
            // FrameLayout sets willNotDraw based on background/foreground;
            // we paint zoom + shadow in onDraw, so we need it cleared.
            setWillNotDraw(false)
            // Pre-configure for sticky-mode focus + joystick events.
            // Listeners and requestFocus are attached only when
            // attachInteractiveListeners runs; setting the focusable bits
            // up front avoids needing to mutate them later.
            isFocusable = true
            isFocusableInTouchMode = true
            addView(leftCol)
            addView(definitionsPanel)
            clipPath.addRoundRect(
                0f, 0f, lensW.toFloat(), lensH.toFloat(),
                cornerR, cornerR, Path.Direction.CW,
            )
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

        fun setLabel(word: String?, reading: String?) {
            val w = word?.takeIf { it.isNotEmpty() }
            val r = reading?.takeIf { it.isNotEmpty() }
            wordView.text = w.orEmpty()
            readingView.text = r.orEmpty()
            readingView.visibility = if (r == null) GONE else VISIBLE
            leftCol.visibility = if (w == null) GONE else VISIBLE
        }

        fun setDefinitions(data: MagnifierLens.LensDefinitionData?, label: String?) {
            if (data == null) {
                if (mode == Mode.ZOOM) return
                mode = Mode.ZOOM
                definitionsPanel.visibility = GONE
                invalidate()
                return
            }
            mode = Mode.DEFINITIONS
            setLabel(data.word, data.reading)
            populateDefinitions(data, label)
            // Lens windows are reused across drags via [resetToZoom], so the
            // ScrollView keeps whatever scrollY the previous entry left
            // behind. Reset it so each new word opens from the top — the
            // "common" badge / freq stars / first senses must always be
            // visible at first show.
            definitionsScroll.scrollTo(0, 0)
            definitionsPanel.visibility = VISIBLE
            invalidate()
        }

        /** True when sticky-mode listeners are attached (set by
         *  [attachInteractiveListeners], cleared by
         *  [detachInteractiveListeners]). The lens is the single source
         *  of truth — `MagnifierLens.isInteractive` reads this. */
        var isInteractive: Boolean = false
            private set

        fun attachInteractiveListeners(onDismissRequest: () -> Unit) {
            // ACTION_OUTSIDE delivery requires the host window to have
            // FLAG_WATCH_OUTSIDE_TOUCH; calling dismiss() (not just the
            // callback) actually tears the window down — without it the
            // sticky lens stays up after tap-outside.
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                    onDismissRequest()
                    false
                } else false
            }
            // Joystick deflection past the dead-zone dismisses, mirroring
            // WordLookupPopup.kt:150-161. Requires window focus, hence
            // requestFocus below.
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
        }

        /** Reverse of [attachInteractiveListeners] — clears the touch /
         *  joystick listeners and drops focus. Used by [resetToZoom] when
         *  a new drag starts so the lens window can be reused without
         *  carrying sticky-mode wiring into ZOOM rendering. */
        fun detachInteractiveListeners() {
            setOnTouchListener(null)
            setOnGenericMotionListener(null)
            clearFocus()
            isInteractive = false
        }

        /** Rebuilds the meta / label / sense rows from [data] inside
         *  [definitionsContent]. Called every entry to DEFINITIONS mode —
         *  the row count is small (≤ a few sense lines) so rebuild cost is
         *  negligible; keeping it simple beats reusing pooled views. */
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

        // ZOOM-mode-only canvas painting. DEFINITIONS modes skip the zoom
        // (covered by the panel's #242424 bg + accent leftCol) and the
        // inset shadow (whose blur would otherwise bleed under the panel
        // edges and fringe the divider).
        override fun onDraw(canvas: Canvas) {
            if (mode != Mode.ZOOM) return
            val w = lensW.toFloat()
            val h = lensH.toFloat()

            drawZoom(canvas, w, h)

            insetShadowRect.set(
                insetShadowInset,
                insetShadowInset,
                w - insetShadowInset,
                h - insetShadowInset,
            )
            canvas.drawRoundRect(
                insetShadowRect,
                cornerR - insetShadowInset,
                cornerR - insetShadowInset,
                insetShadowPaint,
            )
        }

        /** Wraps the entire draw pass (background, onDraw, children) in
         *  the rounded-rect clip, then paints the crosshair (ZOOM mode
         *  only) and border on top of the restored canvas — they need to
         *  sit visually above the zoom and the left panel labels. */
        override fun draw(canvas: Canvas) {
            canvas.save()
            canvas.clipPath(clipPath)
            super.draw(canvas)
            canvas.restore()

            if (mode == Mode.ZOOM) {
                val crosshairCx = lensW / 2f
                val crosshairCy = lensH / 2f
                canvas.drawLine(
                    crosshairCx - crosshairHalfLen, crosshairCy,
                    crosshairCx + crosshairHalfLen, crosshairCy, crosshairPaint,
                )
                canvas.drawLine(
                    crosshairCx, crosshairCy - crosshairHalfLen,
                    crosshairCx, crosshairCy + crosshairHalfLen, crosshairPaint,
                )
            }

            val inset = borderPx / 2f
            borderRect.set(inset, inset, lensW - inset, lensH - inset)
            canvas.drawRoundRect(
                borderRect,
                cornerR - inset, cornerR - inset,
                borderPaint,
            )
        }

        /** Renders the zoom (with black underlay for off-screenshot regions)
         *  across the full lens dimensions. Source is centered on the
         *  finger position; dst is the full lens. */
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
