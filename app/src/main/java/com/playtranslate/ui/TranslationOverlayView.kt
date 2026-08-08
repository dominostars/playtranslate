package com.playtranslate.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import androidx.core.view.doOnLayout
import androidx.core.view.isEmpty
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import com.playtranslate.OcrManager
import com.playtranslate.PinholeCalibration
import com.playtranslate.R
import com.playtranslate.language.TextAlignment
import com.playtranslate.language.TextOrientation
import androidx.core.graphics.createBitmap
import java.text.BreakIterator

/**
 * The substrings of [text] between consecutive legal line-break opportunities, per
 * [BreakIterator.getLineInstance] — the runs the layout can't split across lines. Trailing
 * break-whitespace is stripped and blank runs dropped. Script-aware: words for whitespace
 * languages, single characters for CJK, dictionary segments for Thai — so a no-space sentence is
 * many small runs, never one giant token. Used to size text so an unbreakable run never overflows
 * its line. `internal` for unit testing.
 */
internal fun lineBreakRuns(text: String): List<String> {
    if (text.isBlank()) return emptyList()
    val iter = BreakIterator.getLineInstance()
    iter.setText(text)
    val runs = ArrayList<String>()
    var start = iter.first()
    var end = iter.next()
    while (end != BreakIterator.DONE) {
        val run = text.substring(start, end).trimEnd()
        if (run.isNotEmpty()) runs.add(run)
        start = end
        end = iter.next()
    }
    return runs
}

/**
 * Transparent overlay that positions auto-sizing TextViews inside bounding
 * boxes on the game screen during live mode. Each box corresponds to an OCR
 * text group and is filled with a semi-transparent background so the
 * translated text is readable over game graphics. Font size auto-scales
 * via Android's built-in [TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration].
 *
 * When boxes have empty [TextBox.translatedText], skeleton placeholder lines
 * are shown with a pulsing animation until text arrives via a subsequent
 * [setBoxes] call.
 *
 * Handles both translation boxes (with bg fill + auto-sized text) and
 * furigana (outlined text with no fill); the branch is per-box via
 * [TextBox.isFurigana].
 *
 * @param pinholeMode Fixed at construction. When true, [dispatchDraw] punches
 *   pinhole holes through all children and [rebuildChildren] forces translation-
 *   box child backgrounds to full opacity so [PinholeOverlayMode]'s change-
 *   detection math (`predicted = clean_ref * 0.5 + overlay_rendered * 0.5` at
 *   pinhole pixels) holds. Pinhole mode cannot be toggled on an existing
 *   view — creating a new view is required, which matches the lifecycle
 *   anyway since each live-mode class tears down and recreates the overlay
 *   on start/stop.
 * @param maskAlpha The per-pixel alpha byte written at pinhole positions in
 *   the DST_OUT mask bitmap. Defaults to [PinholeCalibration.MASK_ALPHA]
 *   (0x80 → 50% blend at pinhole positions when window α=1.0). On the
 *   MediaProjection backend, where the window must run at reduced α to
 *   satisfy the QTI BSP visual clamp and AOSP touch-passthrough rule,
 *   `OverlayUiController` passes a compensated value computed from the
 *   window α so the *effective* pinhole α is still 0.5 — preserving the
 *   `checkPinholes` math without changing the detection thresholds.
 */
class TranslationOverlayView(
    context: Context,
    val pinholeMode: Boolean = false,
    private val maskAlpha: Int = PinholeCalibration.MASK_ALPHA,
    /** Marks this view as the touchable one-shot variant (MediaProjection
     *  only). Tracked here so [OverlayUiController.showTranslationOverlay]'s
     *  reuse check can refuse to reuse a non-touchable live overlay for a
     *  touchable one-shot (or vice versa) — the window flags + tap listener
     *  are fixed at construction. */
    val oneShot: Boolean = false,
    /** Compensate for the MediaProjection BSP alpha clamp: when the
     *  hosting window's effective α is fixed at ~0.8 regardless of what we
     *  request, push the sampled bgColor away from the sampled textColor
     *  along the luminance axis so the text retains readable contrast
     *  against the composited overlay. False on accessibility (where α=1.0
     *  actually renders) and in any cell where the overlay composites at
     *  full opacity. */
    private val boostContrast: Boolean = false,
    /** True when the session's TARGET language is written vertically (ja/zh/ko
     *  — see [com.playtranslate.language.targetSupportsVerticalText]); vertical
     *  OCR boxes then render as upright top-to-bottom RTL columns
     *  ([VerticalTextView]) instead of a 90°-rotated horizontal line. Public so
     *  [OverlayUiController]'s view-reuse guard can compare it — it derives from
     *  a user-mutable pref, so a target switch must force a fresh view (a ctor
     *  val can't be refreshed by [setBoxes]). */
    val verticalTextTarget: Boolean = false,
    /** True when the session's TARGET script can render as upright stacked cells
     *  (Latin/Cyrillic/Greek + CJK — see
     *  [com.playtranslate.language.stackableTargetScript]). Gates STACK_UPRIGHT for
     *  non-CJK targets. Public so [OverlayUiController]'s reuse guard can compare it;
     *  derives from the target-language pref, so a switch must force a fresh view. */
    val verticalTextStackable: Boolean = false,
    /** The grow-narrow-vertical-boxes pref ([com.playtranslate.Prefs.verticalTextGrow]).
     *  When off, a narrow non-stackable box rotates in place instead of growing. Public
     *  for the reuse guard; the settings toggle restarts live mode so a flip re-creates
     *  the view. */
    val verticalGrowEnabled: Boolean = false,
    /** When non-null, this overlay handles its own dismissal. ACTION_DOWN
     *  touches dismiss immediately (race-safe against the hold-release
     *  callback and second-finger taps during a hold), and TalkBack's
     *  performClick dispatches to the same path so the overlay is
     *  reachable with the screen reader on. */
    private val onDismiss: (() -> Unit)? = null,
) : FrameLayout(context) {

    // Dismisses on ACTION_DOWN (race-safe — see the [onDismiss] kdoc).
    // That's deliberately not a "click" (DOWN→UP without movement), so
    // there's nothing to route through performClick from onTouchEvent; the
    // performClick override below is the TalkBack entry point and goes
    // through the same handleDismiss().
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (onDismiss != null && event.actionMasked == MotionEvent.ACTION_DOWN) {
            handleDismiss()
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        handleDismiss()
        return true
    }

    private fun handleDismiss() {
        onDismiss?.invoke()
    }

    init {
        clipChildren = false
        clipToPadding = false
        // Every child of this view is placed in CAPTURE-BITMAP pixels mapped to
        // the display — a physical coordinate space that must never mirror. Left
        // to inherit, it would: ViewRootImpl stamps the window configuration's
        // layout direction onto any root whose own direction is INHERIT
        // (performTraversals), so an Arabic system locale silently turns this
        // FrameLayout RTL. FrameLayout's DEFAULT_CHILD_GRAVITY is TOP|START,
        // which resolves to RIGHT there and computes childLeft as
        // `parentRight - width` — DISCARDING leftMargin, while topMargin (no
        // relative bit) still applies. Result: every chip collapses onto the
        // right screen edge at its correct height, and the translationX-placed
        // children (furigana, ROTATE, and the slanted SOURCE_ANGLE chips) are
        // pushed off-screen past it.
        //
        // Pinning the container is deliberate over per-site absolute gravity:
        // this is a pure geometry surface with no localized chrome, so one pin
        // covers every render mode, including ones added later. Text direction
        // is NOT affected — it resolves independently from content
        // (TEXT_DIRECTION_FIRST_STRONG), so an Arabic translation still lays out
        // as an RTL paragraph and stays right-aligned inside its chip.
        layoutDirection = LAYOUT_DIRECTION_LTR
    }

    private val dp = context.resources.displayMetrics.density

    private val minTextSizeSp = 6
    private val maxTextSizeSp = 200
    /** Capped autosize ceiling (sp) for GROW boxes — keeps the translation a small, centred
     *  block in the tall on-source background instead of ballooning to fill the column height. */
    private val growMaxTextSizeSp = 18
    /** Small inset so text doesn't touch the edges of the background. */
    private val textMargin = (3f * dp).toInt()
    /** Bold paint at the legibility floor, used to measure each vertical box's minimum legible
     *  horizontal width (reused across boxes; see [computeMinWidthPx]). */
    private val minWidthPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, VerticalTextLayout.MIN_WIDTH_SP, context.resources.displayMetrics
        )
    }
    private val skeletonBarHeight = (8f * dp).toInt()
    private val skeletonCornerRadius = 3f * dp

    private var boxes: List<TextBox> = emptyList()
    private var cropOffsetX = 0
    private var cropOffsetY = 0
    private var screenshotW = 1
    private var screenshotH = 1

    private val skeletonBars = mutableListOf<View>()
    private var shimmerAnimator: ValueAnimator? = null

    /** Cached full-view pinhole mask bitmap. Created on size change, recycled on detach. */
    private var pinholeMaskBitmap: Bitmap? = null

    private val dstOutPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    }

    fun setBoxes(
        boxes: List<TextBox>,
        cropLeft: Int, cropTop: Int,
        screenshotW: Int, screenshotH: Int,
        authoritativeBounds: Boolean = false,
    ) {
        // Skip everything if content is identical (avoids flash on false-positive recaptures)
        if (this.boxes == boxes && cropOffsetX == cropLeft && cropOffsetY == cropTop
            && this.screenshotW == screenshotW && this.screenshotH == screenshotH) return

        val cropSame = cropOffsetX == cropLeft && cropOffsetY == cropTop
            && this.screenshotW == screenshotW && this.screenshotH == screenshotH

        // Fuzzy-match fast path: text/source/orientation/bounds all match
        // within tolerance, so the rendered children are still valid in
        // place. But metadata that boxesMatchFuzzy intentionally ignores —
        // [TextBox.bgColor], [TextBox.textColor], [TextBox.lineCount] — may
        // have changed. Update the stored boxes list either way (so external
        // queries see current metadata), and rebuild only when a visual
        // field actually shifted. The old `dirty`-staleness motivation is
        // gone since dirty boxes live on a separate companion view; this
        // path's boxes list contains only clean boxes.
        // The fuzzy fast path exists for callers whose bounds JITTER per
        // re-OCR (pinhole, one-shot) — without it boxes shiver. A caller
        // with authoritative bounds (the reconciler tier: 5px hysteresis
        // already applied upstream, stable baseline) must NOT take it:
        // fuzzy-matching here while updating the stored list lets rendered
        // children lag an unbounded distance behind a slow drift, because
        // each cycle's delta is compared against the already-advanced list
        // (field bug 2026-07-10: furigana annotations frozen during small
        // pans, jumping only on large ones).
        if (!authoritativeBounds && cropSame && OverlayLayout.boxesMatchFuzzy(this.boxes, boxes)) {
            val visualChanged = this.boxes.size == boxes.size &&
                this.boxes.zip(boxes).any { (a, b) ->
                    a.bgColor != b.bgColor ||
                        a.textColor != b.textColor ||
                        a.lineCount != b.lineCount
                }
            this.boxes = boxes
            if (visualChanged && width > 0 && height > 0) {
                rebuildChildren()
            }
            return
        }

        this.boxes = boxes
        cropOffsetX = cropLeft
        cropOffsetY = cropTop
        this.screenshotW = screenshotW
        this.screenshotH = screenshotH
        if (width > 0 && height > 0) {
            rebuildChildren()
        }
    }

    /** Remove specific boxes by content match (text + bounds). Removes only the
     *  corresponding child views — surviving children stay in place with no rebuild. */
    fun removeBoxesByContent(toRemove: List<TextBox>) {
        if (toRemove.isEmpty()) return
        fun matches(a: TextBox, b: TextBox) = a.translatedText == b.translatedText && a.bounds == b.bounds
        for (i in (childCount - 1) downTo 0) {
            if (i < boxes.size && toRemove.any { matches(boxes[i], it) }) {
                removeViewAt(i)
            }
        }
        boxes = boxes.filter { box -> !toRemove.any { matches(box, it) } }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Only the pinhole-detector path consumes this bitmap; in furigana
        // and one-shot mode dispatchDraw early-returns before touching it.
        // Avoid the full-screen ARGB allocation (display W × H × 4 bytes —
        // tens of MB on phones, per overlay) for those modes.
        pinholeMaskBitmap?.recycle()
        pinholeMaskBitmap = if (pinholeMode && w > 0 && h > 0) createPinholeMask(w, h) else null
        post { rebuildChildren() }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        shimmerAnimator?.cancel()
        shimmerAnimator = null
        pinholeMaskBitmap?.recycle()
        pinholeMaskBitmap = null
    }

    override fun dispatchDraw(canvas: Canvas) {
        val mask = pinholeMaskBitmap
        if (!pinholeMode || mask == null) {
            super.dispatchDraw(canvas)
            return
        }
        val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        super.dispatchDraw(canvas)
        canvas.drawBitmap(mask, 0f, 0f, dstOutPaint)
        canvas.restoreToCount(layer)
    }

    private fun rebuildChildren() {
        shimmerAnimator?.cancel()
        shimmerAnimator = null
        skeletonBars.clear()
        removeAllViews()
        if (boxes.isEmpty()) return

        // Measure each vertical box's min legible horizontal width at the display density.
        // Gated by rebuildChildren's content/size-change cadence (never per-frame) and cheaper
        // than the autosize pass each child already runs; horizontal boxes skip it.
        val measured = boxes.map { box ->
            if (box.orientation == TextOrientation.VERTICAL && box.translatedText.isNotEmpty())
                box.copy(minWidthPx = computeMinWidthPx(box.translatedText))
            else box
        }

        val resolved = OverlayLayout.resolveScreenRects(
            measured, cropOffsetX, cropOffsetY, screenshotW, screenshotH, width, height, dp,
            targetIsVerticalScript = verticalTextTarget,
            targetStackable = verticalTextStackable,
            growEnabled = verticalGrowEnabled,
        )

        val hasPlaceholders = boxes.any { it.translatedText.isEmpty() }

        if (OcrManager.instance.debugLogGroupingEnabled) logLayoutDecisions(measured, resolved)

        // THE one rotated-pin mechanism (SOURCE_ANGLE chips + slanted ruby):
        // lay out at the oriented dims, rotate about the child's center (the
        // default pivot), center-pin on the given screen point. Shared so the
        // two rotation consumers can't drift apart.
        fun applyRotatedPin(child: View, cx: Float, cy: Float, w: Int, h: Int, angleDeg: Float) {
            child.rotation = angleDeg
            child.translationX = cx - w / 2f
            child.translationY = cy - h / 2f
        }

        // OCR-bitmap → screen scale, hoisted ABOVE the furigana branch: a
        // bare `scaleX` there would silently resolve to the View's own
        // scaleX property (1f) and size slanted ruby in bitmap pixels on
        // scaled displays (Codex review finding).
        val scaleX = width.toFloat() / screenshotW
        val scaleY = height.toFloat() / screenshotH

        measured.zip(resolved).forEach { (box, resolvedBox) ->
            val rect = resolvedBox.rect
            val mode = resolvedBox.mode
            if (box.isFurigana) {
                if (box.angleDeg != 0f) {
                    // Slanted ruby: fixed screen-scaled oriented dims, text
                    // bottom-pinned INSIDE the band via gravity (the in-frame
                    // analogue of the upright path's bottom pin), rotated
                    // about the center like SOURCE_ANGLE — bounds are the
                    // band's exact AABB, so the center pin lands the band on
                    // its baseline offset.
                    val fw = (box.orientedWidth * scaleX).toInt().coerceAtLeast(1)
                    val fh = (box.orientedHeight * scaleY).toInt().coerceAtLeast(1)
                    val textSizePx = (fh * 0.7f).coerceAtLeast(4f)
                    val strokeW = 3f * dp
                    val child = OutlinedTextView(context).apply {
                        text = box.translatedText
                        setTextColor(Color.WHITE)
                        outlineColor = Color.BLACK
                        outlineWidth = strokeW
                        typeface = Typeface.DEFAULT_BOLD
                        includeFontPadding = false
                        setShadowLayer(strokeW, 0f, 0f, Color.TRANSPARENT)
                        setTextSize(TypedValue.COMPLEX_UNIT_PX, textSizePx)
                        gravity = Gravity.BOTTOM or Gravity.START
                    }
                    child.setTag(R.id.tag_bg_color, Color.BLACK)
                    addView(child, LayoutParams(fw, fh))
                    applyRotatedPin(child, rect.centerX(), rect.centerY(), fw, fh, box.angleDeg)
                    return@forEach
                }
                val isVerticalFurigana = box.orientation == TextOrientation.VERTICAL
                // Vertical furigana: size from box width; horizontal: from box height
                val textSizePx = if (isVerticalFurigana) {
                    (rect.width() * 0.7f).coerceAtLeast(4f)
                } else {
                    (rect.height() * 0.7f).coerceAtLeast(4f)
                }
                val strokeW = 3f * dp
                val strokePad = (strokeW / 2f + 0.5f).toInt()
                // Vertical: stack characters top-to-bottom with newlines
                val displayText = if (isVerticalFurigana) {
                    box.translatedText.toList().joinToString("\n")
                } else {
                    box.translatedText
                }
                val child = OutlinedTextView(context).apply {
                    text = displayText
                    setTextColor(Color.WHITE)
                    outlineColor = Color.BLACK
                    outlineWidth = strokeW
                    typeface = Typeface.DEFAULT_BOLD
                    includeFontPadding = false
                    setShadowLayer(strokeW, 0f, 0f, Color.TRANSPARENT)
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, textSizePx)
                    if (isVerticalFurigana) {
                        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                        setPadding(strokePad, 0, strokePad, 0)
                        setLineSpacing(0f, 0.8f)
                    } else {
                        setPadding(strokePad, strokePad, strokePad, strokePad)
                    }
                }
                child.setTag(R.id.tag_bg_color, Color.BLACK)
                addView(child, LayoutParams(
                    LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT
                ))
                // Position after measurement but before draw — no (0,0) flash
                child.doOnLayout {
                    if (isVerticalFurigana) {
                        // Vertical: align to top of OCR box, no Y offset
                        child.translationX = rect.left - strokePad
                        child.translationY = rect.top
                    } else {
                        child.translationX = rect.left - strokePad
                        child.translationY = (rect.bottom - child.measuredHeight).coerceAtLeast(0f)
                    }
                }
            } else {
                val rectW = rect.width().toInt().coerceAtLeast(1)
                val rectH = rect.height().toInt().coerceAtLeast(1)

                // Shared background fill for every non-skeleton child. Pinholes
                // need opaque bg (pinholes handle transparency); without
                // pinholes, native alpha (~224 = 88% opaque). When
                // [boostContrast] is on (MP backend where the BSP clamps window
                // α to ~0.8), the composited box only ends up at ~80% over the
                // game, so we push the sampled bgColor *away from* the sampled
                // textColor's luminance to preserve readability. Pinhole
                // detection math is invariant under bg colour changes because
                // overlay_rendered reflects whatever colour we actually drew —
                // so this fill is load-bearing on the vertical path too: a
                // transparent box would break renderToOffscreen's reference and
                // oscillate REMOVE.
                val fillColor = when {
                    boostContrast -> pushBgAwayFromText(box.bgColor, box.textColor)
                    pinholeMode -> box.bgColor or 0xFF000000.toInt()
                    else -> box.bgColor
                }

                // GROW boxes cap their font so the translation stays a small, centred block in the
                // tall on-source background rather than ballooning to fill the height. On top of
                // that, cap every box so the font can't grow past the point where the longest word
                // fits on one line — otherwise autosize (which maximizes against the box height)
                // breaks single words across rows in a tall, narrow column. ROTATE wraps along the
                // tall side, so its wrap width is rectH.
                val baseMax = if (mode == RenderMode.GROW_HORIZONTAL) growMaxTextSizeSp else maxTextSizeSp
                // SOURCE_ANGLE lays out at the resolved chip's dims (the
                // pre-rotation frame) — the carve may have moved/shrunk it —
                // so its wrap axis is the chip width, the same idea as ROTATE
                // wrapping along its tall side. Defensive fallback: a missing
                // chip payload derives the dims from the box, the pre-carve
                // behavior.
                val chip = resolvedBox.chip
                val angledW = (chip?.width?.toInt() ?: (box.orientedWidth * scaleX).toInt()).coerceAtLeast(1)
                val angledH = (chip?.height?.toInt() ?: (box.orientedHeight * scaleY).toInt()).coerceAtLeast(1)
                val wrapWidthPx = when (mode) {
                    RenderMode.ROTATE -> rectH
                    RenderMode.SOURCE_ANGLE -> angledW
                    else -> rectW
                }
                val autoMax = unbreakableFitMaxSp(box.translatedText, wrapWidthPx, baseMax)

                val child: View = when {
                    box.translatedText.isEmpty() -> {
                        // Skeleton bars follow the SOURCE orientation: a vertical OCR box shows
                        // vertical column-stripes (matching the text being covered) even when its
                        // translation will land horizontally once it arrives. A SOURCE_ANGLE
                        // skeleton builds at the oriented dims — the placement branch below
                        // rotates it onto the slanted source like the text child.
                        val verticalSkeleton = box.orientation == TextOrientation.VERTICAL
                        val sw = if (mode == RenderMode.SOURCE_ANGLE) angledW else rectW
                        val sh = if (mode == RenderMode.SOURCE_ANGLE) angledH else rectH
                        buildSkeletonView(sw, sh, box.lineCount, box.bgColor, box.textColor, box.alignment, verticalSkeleton)
                    }
                    mode == RenderMode.STACK_UPRIGHT -> VerticalTextView(context).apply {
                        text = box.translatedText
                        textColor = box.textColor
                        outlineColor = box.textColor xor 0x00FFFFFF  // invert RGB, keep alpha
                        outlineWidth = 1f * dp
                        setBackgroundColor(fillColor)
                    }
                    else -> OutlinedTextView(context).apply {
                        text = box.translatedText
                        setTextColor(box.textColor)
                        outlineColor = box.textColor xor 0x00FFFFFF  // invert RGB, keep alpha
                        outlineWidth = 1f * dp
                        typeface = Typeface.DEFAULT_BOLD
                        // GROW centres the block in its tall background; otherwise keep the
                        // source group's alignment (vertical boxes classify LEFT in OcrManager,
                        // and on the ROTATE path the inner alignment maps to screen-horizontal
                        // after the 90° rotation).
                        gravity = if (mode == RenderMode.GROW_HORIZONTAL || box.alignment == TextAlignment.CENTER)
                            Gravity.CENTER
                        else
                            Gravity.CENTER_VERTICAL
                        setPadding(textMargin, textMargin, textMargin, textMargin)
                        setBackgroundColor(fillColor)
                        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                            this, minTextSizeSp, autoMax, 1, TypedValue.COMPLEX_UNIT_SP
                        )
                    }
                }

                child.setTag(R.id.tag_bg_color, box.bgColor)

                if (mode == RenderMode.ROTATE) {
                    // Narrow vertical box with grow off / non-stackable script: lay out with
                    // swapped dimensions (width=rectH, height=rectW) so auto-sizing picks a
                    // readable font, then rotate 90° CW so text reads top-to-bottom in the
                    // original narrow footprint. The visual center aligns with the box center
                    // (after rotation the rectH×rectW layout visually becomes rectW×rectH).
                    val lp = LayoutParams(rectH, rectW)
                    addView(child, lp)
                    child.rotation = 90f
                    child.translationX = rect.centerX() - rectH / 2f
                    child.translationY = rect.centerY() - rectW / 2f
                } else if (mode == RenderMode.SOURCE_ANGLE) {
                    // Slanted source: lay out at the chip's dims, rotate about the
                    // child's center (the default pivot) by the chip's angle, and pin
                    // on the chip's center — the resolved payload carries the carve's
                    // outcome, so autosize, skeletons, and placement all follow it.
                    // Exact only while scaleX == scaleY; unequal scales would need a
                    // shear no single View rotation can express (both derive from the
                    // same display, so they agree in every production caller).
                    if (scaleX != 0f && kotlin.math.abs(scaleX - scaleY) > 0.01f * scaleX) {
                        android.util.Log.w(
                            "DetectionLog",
                            "[layout] SOURCE_ANGLE under non-uniform scale ($scaleX vs $scaleY) — chip angle is approximate",
                        )
                    }
                    addView(child, LayoutParams(angledW, angledH))
                    if (chip != null) {
                        applyRotatedPin(child, chip.centerX, chip.centerY, angledW, angledH, chip.angleDeg)
                    } else {
                        // Defensive: no payload — pre-carve pin on the unpadded
                        // mapped bounds center at the box's own angle.
                        val src = OverlayLayout.mapRect(box.bounds, cropOffsetX, cropOffsetY, scaleX, scaleY)
                        applyRotatedPin(child, src.centerX(), src.centerY(), angledW, angledH, box.angleDeg)
                    }
                } else {
                    // STACK_UPRIGHT / HORIZONTAL_IN_PLACE / GROW_HORIZONTAL / LEGACY_HORIZONTAL
                    // and skeletons: fill the (possibly grown) box footprint at its rect.
                    val lp = LayoutParams(rectW, rectH).apply {
                        leftMargin = rect.left.toInt()
                        topMargin = rect.top.toInt()
                    }
                    addView(child, lp)
                }
            }
        }

        if (hasPlaceholders) startShimmer()
    }

    /** Minimum on-screen width (px) for a legible horizontal line of [text]: the widest run that
     *  can't be split across lines, measured at the legibility floor, plus the inner text margins.
     *  See [widestUnbreakableRunPx]; the resolver's ½-screen clamp bounds the result. */
    private fun computeMinWidthPx(text: String): Int =
        kotlin.math.ceil(widestUnbreakableRunPx(text).toDouble()).toInt() + 2 * textMargin

    /** Floor-font width (px) of the widest run of [text] that can't be broken across lines — the
     *  widest of [lineBreakRuns], which respects script line-break rules (spaces for Latin,
     *  between-character for CJK, dictionary segmentation for Thai). 0 when [text] has no run. */
    private fun widestUnbreakableRunPx(text: String): Float =
        lineBreakRuns(text).maxOfOrNull { minWidthPaint.measureText(it) } ?: 0f

    /** Largest autosize ceiling (sp) that still fits the widest unbreakable run of [text] on one
     *  line in a [layoutWidthPx]-wide view, capped at [ceilingSp] and floored just above
     *  [minTextSizeSp]. Autosize maximizes the font subject to the box *height*, with no rule
     *  against an unbreakable run overflowing its line — so in a tall, narrow box it enlarges text
     *  until a run is force-broken mid-unit. Clamping the ceiling here prevents that. It keys off
     *  the widest *unbreakable* run, not the whole string, so no-space scripts (CJK/Thai) — whose
     *  runs are tiny — stay effectively uncapped and wrap normally; it only ever lowers the
     *  ceiling, so genuinely wide boxes are unaffected. */
    private fun unbreakableFitMaxSp(text: String, layoutWidthPx: Int, ceilingSp: Int): Int {
        val widthAtFloor = widestUnbreakableRunPx(text)
        if (widthAtFloor <= 0f) return ceilingSp
        val avail = (layoutWidthPx - 2 * textMargin).coerceAtLeast(1)
        val fit = kotlin.math.floor(VerticalTextLayout.MIN_WIDTH_SP * avail / widthAtFloor).toInt()
        return fit.coerceIn(minTextSizeSp + 1, ceilingSp)
    }

    /** Debug dump of the resolved layout — each box's [RenderMode] + final rect, plus an
     *  explicit line for every pair of rendered boxes that still intersect. Gated on the
     *  "Log grouping decisions" toggle and emitted on the same `DetectionLog` tag as the OCR
     *  grouping log, so one capture shows which two overlays collide and in which silo (e.g.
     *  a HORIZONTAL_IN_PLACE box overlapping a STACK_UPRIGHT one — a cross-footprint pair the
     *  per-class overlap passes don't resolve). */
    private fun logLayoutDecisions(boxes: List<TextBox>, resolved: List<ResolvedBox>) {
        fun rs(r: android.graphics.RectF) =
            "(${r.left.toInt()},${r.top.toInt()},${r.right.toInt()},${r.bottom.toInt()})"
        boxes.forEachIndexed { i, box ->
            if (box.isFurigana) return@forEachIndexed
            val src = box.sourceText.take(16).replace('\n', ' ')
            val tr = box.translatedText.take(16).replace('\n', ' ')
            android.util.Log.d(
                "DetectionLog",
                "[layout] box[$i] ${resolved[i].mode} ${box.orientation.name[0]} " +
                    "minW=${box.minWidthPx} ang=${box.angleDeg} " +
                    "\"$src\"->\"$tr\" rect=${rs(resolved[i].rect)}",
            )
        }
        val idx = boxes.indices.filter { !boxes[it].isFurigana }
        for (a in idx.indices) for (b in a + 1 until idx.size) {
            val i = idx[a]; val j = idx[b]
            if (android.graphics.RectF.intersects(resolved[i].rect, resolved[j].rect)) {
                android.util.Log.d(
                    "DetectionLog",
                    "[layout] OVERLAP box[$i](${resolved[i].mode},\"${boxes[i].sourceText.take(8)}\") " +
                        "∩ box[$j](${resolved[j].mode},\"${boxes[j].sourceText.take(8)}\")",
                )
            }
        }
    }

    /** Builds a skeleton placeholder with [lineCount] bars evenly spaced within the box.
     *  When [isVertical] is true, bars are drawn as vertical column-stripes in
     *  right-to-left reading order (first column at the right, last at the left),
     *  anchored to the top of the box. Horizontal alignment is ignored for
     *  vertical boxes since [OcrManager] always classifies them as LEFT.
     *  When [alignment] is [TextAlignment.CENTER] (horizontal only), bars are
     *  horizontally centered within the box so the short last-row bar visually
     *  reflects center-aligned source text rather than dropping to the left edge. */
    private fun buildSkeletonView(
        boxW: Int, boxH: Int, lineCount: Int, bgColor: Int, barColor: Int,
        alignment: TextAlignment = TextAlignment.LEFT,
        isVertical: Boolean = false,
    ): View {
        val container = FrameLayout(context).apply {
            setBackgroundColor(bgColor)
        }

        val sideMargin = textMargin * 2

        if (isVertical) {
            val availH = boxH - sideMargin * 2

            for (col in 0 until lineCount) {
                val heightFraction = if (col == lineCount - 1 && lineCount > 1) 0.6f else 0.85f
                val barH = (availH * heightFraction).toInt().coerceAtLeast(1)

                // Reading order: col 0 (first) is rightmost; col N-1 (last) is leftmost.
                val slot = lineCount - 1 - col
                val centerX = boxW * (slot + 1) / (lineCount + 1)
                val barLeft = centerX - skeletonBarHeight / 2

                val bar = View(context).apply {
                    background = GradientDrawable().apply {
                        setColor(barColor)
                        cornerRadius = skeletonCornerRadius
                    }
                }
                skeletonBars.add(bar)
                val barLp = LayoutParams(skeletonBarHeight, barH).apply {
                    leftMargin = barLeft
                    topMargin = sideMargin
                }
                container.addView(bar, barLp)
            }
        } else {
            val availW = boxW - sideMargin * 2

            for (line in 0 until lineCount) {
                val widthFraction = if (line == lineCount - 1 && lineCount > 1) 0.6f else 0.85f
                val barW = (availW * widthFraction).toInt().coerceAtLeast(1)

                // Evenly distribute: bar centers at boxH*(i+1)/(N+1)
                val centerY = boxH * (line + 1) / (lineCount + 1)
                val barTop = centerY - skeletonBarHeight / 2

                val bar = View(context).apply {
                    background = GradientDrawable().apply {
                        setColor(barColor)
                        cornerRadius = skeletonCornerRadius
                    }
                }
                skeletonBars.add(bar)
                val barLeft = if (alignment == TextAlignment.CENTER) {
                    ((boxW - barW) / 2).coerceAtLeast(sideMargin)
                } else {
                    sideMargin
                }
                val barLp = LayoutParams(barW, skeletonBarHeight).apply {
                    leftMargin = barLeft
                    topMargin = barTop
                }
                container.addView(bar, barLp)
            }
        }

        return container
    }

    /**
     * Build a full-view pinhole mask. Pinhole positions have alpha
     * [maskAlpha], all other pixels are fully transparent. Drawn with DST_OUT
     * in [dispatchDraw] to punch partially-transparent holes through all
     * children.
     *
     * See [PinholeCalibration] for why the default mask alpha and spacing
     * are tightly coupled to `PinholeOverlayMode.checkPinholes` — editing
     * them without re-tuning the detection thresholds silently breaks
     * pinhole detection. The compensated-α path used on MediaProjection
     * adjusts the per-pixel mask alpha so the *effective* pinhole α is
     * still 0.5 after the window α multiplies in (see KDoc on [maskAlpha]).
     *
     * **Scale note:** The mask is generated at VIEW resolution, with
     * pinhole positions on a fixed [PinholeCalibration.PINHOLE_SPACING]-
     * pixel grid in view coordinates. Pinhole detection assumes the mask
     * spacing is also valid in screenshot-bitmap coordinates, which
     * requires view dims == screenshot dims (identity scale). At
     * non-identity scale the sparse mask pattern is smeared by bitmap
     * downsampling and the `predicted = (ref + overlay) / 2` math no
     * longer holds. See [com.playtranslate.FrameCoordinates] KDoc for the
     * full explanation.
     */
    private fun createPinholeMask(w: Int, h: Int): Bitmap {
        val spacing = PinholeCalibration.PINHOLE_SPACING
        val maskPixel = maskAlpha shl 24
        val pixels = IntArray(w * h) // all 0 = fully transparent
        for (y in 0 until h) {
            val rowGroup = (y / spacing) % 2
            val xOffset = if (rowGroup == 0) 0 else spacing / 2
            if (y % spacing != 0) continue
            var x = xOffset
            while (x < w) {
                pixels[y * w + x] = maskPixel
                x += spacing
            }
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    /**
     * Screen rects of the text-box children. Uses `getLocationOnScreen` for
     * pixel-perfect positioning — no computed approximations. For rotated
     * children (vertical text overlays), uses the visual bounds from the
     * transformation matrix rather than the layout width/height. Call after
     * layout completes (doOnLayout).
     *
     * No clean/dirty filter is needed here: this view only ever holds clean
     * boxes (the dirty companion is a separate window owned by the
     * controller). [PinholeOverlayMode] indexes the returned list against
     * its own clean-only `cachedBoxes` directly.
     */
    fun getChildScreenRects(): List<Rect> = getChildFootprints().map { it.rect }

    /** One rendered child's DRAWN footprint: the screen-space AABB plus the
     *  laid-out dims + rotation that produced it. Upright children report
     *  angle 0 with their layout dims. */
    data class ChildFootprint(
        val rect: Rect,
        val angleDeg: Float,
        val drawnW: Float,
        val drawnH: Float,
    )

    /**
     * The drawn footprint of every rendered child, in child order (same
     * filter and order as [getChildScreenRects] — that list is now a
     * projection of this one). The pinhole gate's exclusion consumes this
     * channel: the DRAWN truth, instead of pairing rendered rects with
     * stored box geometry whose padding/carving can diverge. A ROTATE-mode
     * child reports its 90° rotation with its swapped layout dims — exact,
     * like every other entry.
     */
    fun getChildFootprints(): List<ChildFootprint> {
        val out = mutableListOf<ChildFootprint>()
        val location = IntArray(2)
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.getTag(R.id.tag_bg_color) == null) continue
            if (child.rotation != 0f) {
                // Rotated child: visual bounds via the hit rect, which
                // accounts for rotation/translation transforms.
                val hitRect = android.graphics.Rect()
                child.getHitRect(hitRect)
                // getHitRect returns parent-relative coords; offset to screen
                getLocationOnScreen(location)
                hitRect.offset(location[0], location[1])
                out += ChildFootprint(hitRect, child.rotation, child.width.toFloat(), child.height.toFloat())
            } else {
                child.getLocationOnScreen(location)
                out += ChildFootprint(
                    Rect(location[0], location[1], location[0] + child.width, location[1] + child.height),
                    0f, child.width.toFloat(), child.height.toFloat(),
                )
            }
        }
        return out
    }

    /**
     * True iff this view AND every child currently has measured dimensions
     * and no layout pass is pending. Callers (PinholeOverlayMode) poll this
     * to defer [renderToOffscreen] until the freshly-rebuilt children have
     * actually been laid out — without this gate, pinhole detection captures
     * an empty/stale overlay bitmap on the cycle right after a teardown,
     * then over-flags every box for REMOVE on the following cycle.
     */
    fun areChildrenLaidOut(): Boolean {
        if (width <= 0 || height <= 0) return false
        if (isLayoutRequested) return false
        if (isEmpty()) return boxes.isEmpty()
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.width <= 0 || c.height <= 0) return false
            if (c.isLayoutRequested) return false
        }
        return true
    }

    /**
     * Render the overlay to an offscreen bitmap WITHOUT pinholes.
     * Returns the exact pixel-for-pixel content of the overlay (bg + text +
     * outlines), at the view's current dimensions. Used for pinhole change
     * detection — provides the overlay_rendered term in:
     *   predicted = clean_ref * 0.5 + overlay_rendered * 0.5
     * Call after layout completes.
     *
     * **Scale assumption:** the output is at **view dimensions**. Pinhole
     * detection assumes view dims == screenshot dims (identity scale).
     * See [com.playtranslate.FrameCoordinates] KDoc and
     * [com.playtranslate.PinholeOverlayMode.checkPinholes] for why
     * non-identity scale is not a supported configuration; the live modes
     * fail-closed at non-identity before calling this.
     */
    fun renderToOffscreen(): Bitmap? {
        if (width <= 0 || height <= 0) return null
        val bitmap = createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        // Draw each child directly. This deliberately sidesteps our own
        // [dispatchDraw] override (which would punch pinhole holes when
        // [pinholeMode] is true) so the resulting bitmap is the "what the
        // overlay would look like without holes" reference used by
        // [PinholeOverlayMode.checkPinholes]. This view only ever holds
        // clean boxes — dirty boxes live on the dedicated dirty companion
        // window owned by the controller — so no clean/dirty filtering is
        // needed here. This view never uses custom z-order, disappearing-
        // child animations, or `getChildDrawingOrder`, so iterating in
        // child index order is equivalent to `super.dispatchDraw` minus
        // the mask.
        val drawingTime = drawingTime
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.isVisible) {
                drawChild(canvas, child, drawingTime)
            }
        }
        return bitmap
    }

    /** Push [bgColor]'s luminance away from [textColor]'s, keeping bgColor's
     *  hue. Light text gets a darker bg; dark text gets a lighter bg.
     *  Returned bg is always fully opaque — pinhole mode requires it. */
    private fun pushBgAwayFromText(bgColor: Int, textColor: Int): Int {
        val textLum = androidx.core.graphics.ColorUtils.calculateLuminance(textColor)
        val r = Color.red(bgColor)
        val g = Color.green(bgColor)
        val b = Color.blue(bgColor)
        // Strength of the shift. Tuned empirically: enough to recover
        // contrast after the ~0.8 BSP clamp, not so much that the box
        // diverges visually from the surrounding game palette.
        val k = 0.55f
        val (nr, ng, nb) = if (textLum >= 0.5) {
            // Light text → darken bg toward black.
            Triple(
                (r * (1f - k)).toInt(),
                (g * (1f - k)).toInt(),
                (b * (1f - k)).toInt(),
            )
        } else {
            // Dark text → lighten bg toward white.
            Triple(
                (r + (255 - r) * k).toInt(),
                (g + (255 - g) * k).toInt(),
                (b + (255 - b) * k).toInt(),
            )
        }
        return Color.argb(0xFF, nr.coerceIn(0, 255), ng.coerceIn(0, 255), nb.coerceIn(0, 255))
    }

    private fun startShimmer() {
        shimmerAnimator = ValueAnimator.ofFloat(0.8f, 0.3f).apply {
            duration = 800
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                val a = anim.animatedValue as Float
                for (bar in skeletonBars) {
                    bar.alpha = a
                }
            }
            start()
        }
    }
}
