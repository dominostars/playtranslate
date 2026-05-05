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
import com.playtranslate.blendColors

/**
 * Rounded-rect magnifier overlay shown while the floating icon is dragged
 * and persists post-release as the dictionary surface (replaces the
 * separate `WordLookupPopup` in the drag flow).
 *
 * Render modes:
 *  - **ZOOM** (default during drag): zoomed screenshot + crosshair on the
 *    right, optional accent left panel with word + reading.
 *  - **LOADING**: post-release placeholder shown while the dictionary
 *    lookup is in flight — same panel chrome as DEFINITIONS but with a
 *    spinner + "Looking up…" text in the right side. Set via [setLoading].
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
    /** Display id this lens lives on; used so it gets blanked during clean
     *  captures of the right display rather than leaking into the screenshot. */
    private val displayId: Int,
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
    /** Triangular pointer drawn between the lens body and the finger when
     *  the lens is in sticky-definitions mode. Matches WordLookupPopup's
     *  arrow proportions so the two surfaces feel like the same family. */
    private val arrowSizePx = dp(10f)
    /** Total window height. The lens body always occupies [lensH]; the
     *  remaining [arrowSizePx] is reserved for the sticky-mode arrow above
     *  or below the body. The window height is fixed across modes — only
     *  the arrow's visibility changes — so the window doesn't have to be
     *  resized when transitioning into / out of sticky mode. */
    private val totalH = lensH + arrowSizePx
    private val zoom = 2f
    /** How far the lens may slide off the top of the screen before we flip
     *  it below the finger. Tolerating some clipping avoids a jarring flip
     *  the instant the lens touches the top edge. */
    private val topOverhangTolerancePx = lensH / 5

    private var lensView: LensView? = null
    private var params: WindowManager.LayoutParams? = null

    /** Most recent finger x from [show]. Used by [makeInteractive] to
     *  align the sticky-mode arrow horizontally with the finger position
     *  the user released at. The arrow's direction is driven by the
     *  LensView's own flipped state, set in [show] via setLensFlipped. */
    private var lastFingerX = 0

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

    /** Switch the lens to LOADING rendering — same panel chrome as
     *  DEFINITIONS but the right side shows an indeterminate spinner and
     *  "Looking up…" text instead of dictionary rows. Used after the user
     *  releases on a word that wasn't dwell-cached, so they see immediate
     *  visual confirmation that a lookup is running rather than staring at
     *  the unchanged zoom for the lookup gap. The left-panel label is
     *  updated from `word/reading` so the lens already shows the term we
     *  are resolving. */
    fun setLoading(word: String?, reading: String?) {
        lensView?.setLoading(word, reading)
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
        // Sticky-mode arrow points at the finger position from the most
        // recent show() call. Clamp x so the triangle stays inside the
        // lens horizontally (matches WordLookupPopup's arrowRelX clamp).
        val arrowRelX = (lastFingerX - p.x).coerceIn(arrowSizePx, p.width - arrowSizePx)
        view.setArrowVisible(true, arrowRelX)
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
        view.setArrowVisible(false, 0)
    }

    /** Lens width tracks WordLookupPopup's `baseW` (no open-button column —
     *  the lens has no buttons on the right while zooming). The popup
     *  subtracts dp(24) for its root FrameLayout's 12dp+12dp horizontal
     *  padding before splitting; the lens has no such padding, so left-
     *  panel width is a flat 29% of lensW.
     *    lensW = min(screenW × 0.85, dp(380))
     *    leftW = lensW × 0.29
     *  Returns (lensW, leftPanelW) — both in pixels. */
    private fun lensDimensions(screenW: Int): Pair<Int, Int> {
        val lensW = (screenW * 0.85f).toInt().coerceAtMost(dp(380f))
        val leftW = (lensW * 0.29f).toInt()
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
        view.setLensFlipped(flipped)
        view.visibility = View.VISIBLE

        // Snapshot for sticky-mode arrow alignment in [makeInteractive].
        lastFingerX = fingerX

        val x = (fingerX - lensW / 2).coerceIn(0, (screenW - lensW).coerceAtLeast(0))
        // The lens body's visual top is unchanged from the pre-arrow design.
        // When the lens is flipped (body below finger), the window itself
        // starts arrowSizePx above the body so the arrow has room to render
        // in the gap between the finger and the body.
        val lensBodyY = if (!flipped) {
            aboveY
        } else {
            (fingerY + verticalMarginPx).coerceAtMost((screenH - lensH).coerceAtLeast(0))
        }
        val y = if (!flipped) lensBodyY else lensBodyY - arrowSizePx
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
            arrowSizePx = arrowSizePx,
            onOpenTap = { onOpenTap?.invoke() },
        )
        // Window is sized for the lens body PLUS the arrow strip. The arrow
        // is invisible during zoom / drag-definitions modes and only paints
        // when the lens transitions to sticky via [makeInteractive], so the
        // extra height is purely transparent reserved space until then.
        val lp = WindowManager.LayoutParams(
            lensW, totalH,
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
        private val leftPanelW: Int,
        private val cornerR: Float,
        private val zoom: Float,
        private val arrowSizePx: Int,
        private val onOpenTap: () -> Unit,
    ) : FrameLayout(ctx) {
        private val density = ctx.resources.displayMetrics.density
        private fun dp(v: Float): Int = (density * v).toInt()
        private val borderPx = density * 2f
        private val rightPanelW = lensW - leftPanelW

        private enum class Mode { ZOOM, DEFINITIONS, LOADING }
        private var mode: Mode = Mode.ZOOM

        // The view is sized lensW × (lensH + arrowSizePx). The lens body
        // occupies a [lensH]-tall band whose top y depends on whether the
        // lens is flipped: 0 when above the finger (default), arrowSizePx
        // when below. The arrow strip lives on the opposite side.
        private var lensFlipped = false
        private val bodyTopOffset: Int get() = if (lensFlipped) arrowSizePx else 0
        // Sticky-mode arrow state. arrowOffsetX is the x of the triangle's
        // tip / center within the view, set by the controller after release.
        private var arrowVisible = false
        private var arrowOffsetX = 0

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

        // Border uses a darkened accent (70% accent + 30% black) so it reads
        // as a defined edge rather than a glowing outline against the lens
        // chrome. blendColors ignores alpha, so the accent is opaque here.
        private val borderColor = blendColors(accentColor, Color.BLACK, 0.7f)
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = borderColor
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

        // Arrow fills with the same darkened accent the border uses, so the
        // triangle reads as a continuation of the lens chrome rather than a
        // separate panel-colored chip hanging off the bottom.
        private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = borderColor
            style = Paint.Style.FILL
        }
        private val arrowPath = Path()

        // Inset drop-shadow stroke just inside the border. The stroke is
        // drawn while the round-rect clip is active (see [draw]), so its
        // outer half is clipped to the lens shape and the inner half blurs
        // softly toward the content — a "lens-depth" effect that recesses
        // the zoom beneath the accent border. Skipped in DEFINITIONS mode
        // so the blur doesn't bleed under the panel's edges.
        private val insetShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(45, 0, 0, 0)
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
        private val wordHPaddingPx = dp(4f)
        private val wordView = TextView(ctx).apply {
            setTextColor(accentOnColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            maxLines = 1
            // ellipsize handles the case where even minSp doesn't fit
            // leftPanelW — without it the word would clip mid-glyph.
            ellipsize = TextUtils.TruncateAt.END
            setPadding(wordHPaddingPx, 0, wordHPaddingPx, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        // Paint mirroring wordView's typeface/size knobs, used to compute
        // the largest fitting text size manually. Replaces TextView's
        // built-in autosize, which caches state across setText calls in the
        // reused lens window — once a long word shrunk the view, shorter
        // words later never grew back. Manual sizing is stateless: every
        // call measures from scratch against the fixed leftPanelW.
        private val wordSizingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.DEFAULT_BOLD
        }
        private val wordMaxSp = 22f
        private val wordMinSp = 10f
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
            // Height is fixed at lensH (not MATCH_PARENT) because the host
            // view is taller than the lens body — the extra arrowSizePx
            // strip is reserved for the sticky-mode arrow. Gravity flips
            // to BOTTOM via setLensFlipped when the lens is below finger.
            layoutParams = LayoutParams(
                leftPanelW,
                lensH,
                Gravity.START or Gravity.TOP,
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
            // Column width drops 2dp (31→29) and marginEnd grows 2dp (2→4)
            // so the button slides further from the right edge without
            // taking width from the weighted definitions scroll on its left.
            layoutParams = LinearLayout.LayoutParams(
                dp(29f),
                LinearLayout.LayoutParams.MATCH_PARENT,
            ).apply {
                gravity = Gravity.CENTER_VERTICAL
                marginEnd = dp(4f)
            }
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setOnClickListener { fireOpenTap() }
        }
        private val definitionsPanel = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(panelBgColor)
            setPadding(dp(8f), dp(8f), 0, dp(8f))
            visibility = GONE
            addView(definitionsScroll)
            addView(openButton)
            // See leftCol — fixed lensH height with TOP/BOTTOM gravity so
            // the panel covers exactly the lens body region inside the
            // taller host view.
            layoutParams = LayoutParams(
                rightPanelW,
                lensH,
                Gravity.END or Gravity.TOP,
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
            rebuildClipPath()
        }

        /** Rebuilds [clipPath] to mark off the lens body's rounded-rect
         *  region. Called from init and again whenever [lensFlipped]
         *  changes (which moves the body's vertical band within the host
         *  view). Drawing in [draw] / [onDraw] still happens inside this
         *  clip so the zoom and chrome stay confined to the body region;
         *  the arrow paints after the clip is restored. */
        private fun rebuildClipPath() {
            clipPath.reset()
            val top = bodyTopOffset.toFloat()
            clipPath.addRoundRect(
                0f, top, lensW.toFloat(), top + lensH.toFloat(),
                cornerR, cornerR, Path.Direction.CW,
            )
        }

        /** Single source of truth for "user tapped to open the detail
         *  view." Both the open button's onClick and the lens-wide
         *  gesture detector route through here, debounced so a tap that
         *  lands on the open button can't fire twice (button click +
         *  bubbled-up gesture detection on the same UP). */
        private var lastOpenTapMs = 0L
        private fun fireOpenTap() {
            val now = SystemClock.uptimeMillis()
            if (now - lastOpenTapMs < 300L) return
            lastOpenTapMs = now
            onOpenTap()
        }

        // Tap-anywhere-to-open detector. SimpleOnGestureListener.
        // onSingleTapUp fires only when the gesture is a confirmed single
        // tap — DOWN/UP without movement past the system touch slop — so
        // scrolling the definitions ScrollView (movement past slop) won't
        // fire it. The detector observes events from dispatchTouchEvent
        // (see below) without consuming them, so the ScrollView still
        // gets to scroll and the open button still gets its ripple. We
        // intentionally don't override onDown(true): SimpleOnGestureListener's
        // onDown=false is fine for SingleTapUp delivery; returning true
        // would only matter for double-tap / long-press.
        private val tapDetector = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                fireOpenTap()
                return false
            }
        })

        /** Observe (don't consume) every touch when the lens is in
         *  sticky-definitions mode so a tap anywhere inside the lens
         *  fires the same action as the open button. ZOOM-mode dispatch
         *  is unchanged — the lens window has FLAG_NOT_TOUCHABLE during
         *  drag, so events don't reach this method anyway.
         *
         *  Touches whose DOWN lands in the arrow strip (transparent,
         *  outside the rounded-rect body) skip the tap detector entirely
         *  for the rest of that gesture — the arrow is a visual pointer,
         *  not a tap target. Gating on DOWN (rather than per-event) keeps
         *  the GestureDetector's state machine consistent, so a stray UP
         *  inside the body can't fire a tap from a gesture that started
         *  on the arrow. */
        private var tapGestureActive = false
        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            val handled = super.dispatchTouchEvent(ev)
            if (isInteractive) {
                if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                    tapGestureActive = isWithinBody(ev.y)
                }
                if (tapGestureActive) tapDetector.onTouchEvent(ev)
            }
            return handled
        }

        private fun isWithinBody(y: Float): Boolean {
            val top = bodyTopOffset
            return y >= top && y < top + lensH
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

        /** Flip the lens body to the bottom of the host view (when the
         *  finger is near the top of the screen) or back to the top. The
         *  child panels (leftCol / definitionsPanel) get re-pinned with
         *  TOP/BOTTOM gravity so they continue to cover the body region,
         *  and [clipPath] follows. No-op when state is unchanged so that
         *  the per-frame [show] caller can spam this without thrashing
         *  layout. */
        fun setLensFlipped(flipped: Boolean) {
            if (lensFlipped == flipped) return
            lensFlipped = flipped
            val verticalGravity = if (flipped) Gravity.BOTTOM else Gravity.TOP
            leftCol.layoutParams = LayoutParams(
                leftPanelW, lensH, Gravity.START or verticalGravity,
            )
            definitionsPanel.layoutParams = LayoutParams(
                rightPanelW, lensH, Gravity.END or verticalGravity,
            )
            rebuildClipPath()
            invalidate()
        }

        /** Toggle the sticky-mode arrow on/off. [offsetX] is the x of the
         *  arrow's tip (= triangle center) within the host view, set to
         *  align with the finger position the user released at. */
        fun setArrowVisible(visible: Boolean, offsetX: Int) {
            if (arrowVisible == visible && arrowOffsetX == offsetX) return
            arrowVisible = visible
            arrowOffsetX = offsetX
            invalidate()
        }

        fun setLabel(word: String?, reading: String?) {
            val w = word?.takeIf { it.isNotEmpty() }
            val r = reading?.takeIf { it.isNotEmpty() }
            wordView.text = w.orEmpty()
            wordView.setTextSize(TypedValue.COMPLEX_UNIT_SP, fitWordSize(w.orEmpty()))
            readingView.text = r.orEmpty()
            readingView.visibility = if (r == null) GONE else VISIBLE
            leftCol.visibility = if (w == null) GONE else VISIBLE
        }

        /** Largest integer sp in `wordMinSp..wordMaxSp` whose rendered width
         *  fits `leftPanelW`. Binary search over the sp range — stateless
         *  across word changes (the previous Android-autosize approach
         *  cached state across setText and never grew back). If nothing in
         *  the range fits, returns `wordMinSp` and lets the TextView's
         *  ellipsize handle the overflow. */
        private fun fitWordSize(text: String): Float {
            if (text.isEmpty()) return wordMaxSp
            val availablePx = (leftPanelW - 2 * wordHPaddingPx).toFloat()
            if (availablePx <= 0f) return wordMaxSp
            var lo = wordMinSp.toInt()
            var hi = wordMaxSp.toInt()
            var best = lo
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                wordSizingPaint.textSize = mid * density
                if (wordSizingPaint.measureText(text) <= availablePx) {
                    best = mid
                    lo = mid + 1
                } else {
                    hi = mid - 1
                }
            }
            return best.toFloat()
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

        fun setLoading(word: String?, reading: String?) {
            mode = Mode.LOADING
            setLabel(word, reading)
            populateLoading()
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

        /** Build a single horizontally-laid-out row with an indeterminate
         *  spinner and "Looking up…" label. The row sits inside the same
         *  weight=1, gravity=CENTER_VERTICAL ScrollView the definitions
         *  use, so a single short row is visually centered in the panel
         *  the way short-definition entries already are. */
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

        // ZOOM-mode-only canvas painting. DEFINITIONS modes skip the zoom
        // (covered by the panel's #242424 bg + accent leftCol) and the
        // inset shadow (whose blur would otherwise bleed under the panel
        // edges and fringe the divider).
        override fun onDraw(canvas: Canvas) {
            if (mode != Mode.ZOOM) return
            val w = lensW.toFloat()
            val h = lensH.toFloat()

            // Translate so drawZoom's (0..lensW, 0..lensH) coordinates land
            // on the lens body's actual position within the host view —
            // the host is now lensH + arrowSizePx tall, and the body sits
            // either at the top (default) or shifted down by arrowSizePx
            // (flipped). The clipPath active during super.draw matches.
            canvas.save()
            canvas.translate(0f, bodyTopOffset.toFloat())
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
            canvas.restore()
        }

        /** Wraps the entire draw pass (background, onDraw, children) in
         *  the rounded-rect clip, then paints the crosshair (ZOOM mode
         *  only) and border on top of the restored canvas — they need to
         *  sit visually above the zoom and the left panel labels.
         *
         *  Sticky-mode arrow paints AFTER the clip is restored and AFTER
         *  the border so it visually attaches to the lens body's edge.
         *  The arrow lives in the lensW × arrowSizePx strip outside the
         *  clipped lens body region, on the side opposite [bodyTopOffset]. */
        override fun draw(canvas: Canvas) {
            canvas.save()
            canvas.clipPath(clipPath)
            super.draw(canvas)
            canvas.restore()

            if (mode == Mode.ZOOM) {
                val crosshairCx = lensW / 2f
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

            val inset = borderPx / 2f
            val bodyTop = bodyTopOffset.toFloat()
            borderRect.set(
                inset, bodyTop + inset,
                lensW - inset, bodyTop + lensH - inset,
            )
            canvas.drawRoundRect(
                borderRect,
                cornerR - inset, cornerR - inset,
                borderPaint,
            )

            if (arrowVisible) drawArrow(canvas)
        }

        /** Paint the sticky-mode triangular pointer between the lens body
         *  and the finger position the user released at. Triangle base
         *  sits flush against the lens body edge so the shape reads as a
         *  contiguous extension of the definitions panel; tip points
         *  toward the finger.
         *
         *  When the lens is above the finger (default), the arrow is in
         *  the bottom strip pointing down. When flipped (lens below
         *  finger), the arrow is in the top strip pointing up. */
        private fun drawArrow(canvas: Canvas) {
            arrowPath.reset()
            val cx = arrowOffsetX.toFloat()
            val halfBase = arrowSizePx.toFloat()
            if (lensFlipped) {
                // Arrow strip at y in [0, arrowSizePx]; tip at y = 0.
                val baseY = arrowSizePx.toFloat()
                arrowPath.moveTo(cx - halfBase, baseY)
                arrowPath.lineTo(cx + halfBase, baseY)
                arrowPath.lineTo(cx, 0f)
            } else {
                // Arrow strip at y in [lensH, lensH + arrowSizePx]; tip
                // at y = lensH + arrowSizePx.
                val baseY = lensH.toFloat()
                val tipY = (lensH + arrowSizePx).toFloat()
                arrowPath.moveTo(cx - halfBase, baseY)
                arrowPath.lineTo(cx + halfBase, baseY)
                arrowPath.lineTo(cx, tipY)
            }
            arrowPath.close()
            canvas.drawPath(arrowPath, arrowPaint)
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
