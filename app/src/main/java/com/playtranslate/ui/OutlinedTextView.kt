package com.playtranslate.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.TextUtils
import android.widget.TextView
import com.playtranslate.BuildConfig
import kotlin.math.max
import kotlin.math.min

/**
 * TextView that draws a stroke outline behind the text for readability
 * without a background: a STROKE pass of the layout, then the normal FILL
 * pass on top.
 *
 * The stroke pass draws the view's own [getLayout] directly instead of going
 * through `super.onDraw`. That detail is load-bearing: TextView.onDraw
 * re-stamps the view-level text color onto [getPaint] at the start of every
 * draw, so a stroke color written to `paint.color` before a super.onDraw
 * call never survives. Both historical workarounds for that re-stamp
 * regressed:
 *
 *  - `setTextColor()` around the stroke pass invalidates unconditionally,
 *    and an invalidate issued from inside a draw schedules the next frame —
 *    every visible outlined box then redraws its whole window at the display
 *    refresh rate forever (measured: 118–120 draws/s on a completely static
 *    screen).
 *  - A Paint-level PorterDuffColorFilter (SRC_IN) recolor pushed the stroke
 *    color down into HWUI's per-glyph-run caching, which applies text color
 *    filters inconsistently across cached runs — individual lines
 *    intermittently drew their stroke in the fill color instead, reading as
 *    bold text (2026-07-09 regression, confirmed by three-way on-device
 *    bisection).
 *
 * Drawing the layout directly sidesteps the re-stamp entirely, so the stroke
 * color rides plain `paint.color` — the same GPU-visible op stream (stroked
 * glyph runs under an ordinary paint color) the original setTextColor code
 * produced for years, and the same pattern [VerticalTextView] has always
 * used without artifacts. Everything mutated during the pass is inert
 * Paint/Canvas state, so no invalidate can originate from the draw.
 *
 * ## Stroke/fill parity
 *
 * The stroke pass must land on exactly the pixels the fill pass will cover.
 * TextView.onDraw positions the layout at
 * `(compoundPaddingLeft, extendedPaddingTop + verticalGravityOffset)` and
 * clips to the padding box (non-scrolled form, expanded by any shadow-layer
 * extents) — both blocks byte-identical in AOSP from API 29 (minSdk) through
 * 33. The stroke pass reproduces them from public accessors only:
 * [getCompoundPaddingLeft] is the literal X TextView uses, and
 * [getTotalPaddingTop] is the SDK accessor defined as
 * `extendedPaddingTop + verticalOffset`. Horizontal gravity, alignment, and
 * RTL need no mirroring at all: they are resolved *inside* [android.text.Layout]
 * (per-line alignment), and both passes draw the same Layout object — the
 * only horizontal canvas translate TextView ever applies is the marquee
 * path.
 *
 * The three TextView states whose draw-time offsets/clip this reproduction
 * does NOT cover — scrolling, marquee ellipsis, and a hint (whose layout
 * substitutes for empty text in the vertical-offset math) — are excluded by
 * contract and enforced by [checkStrokeContract] in debug builds, rather
 * than assumed from call-site inspection.
 *
 * The clip is load-bearing containment, not cosmetics: the overlay container
 * sets `clipChildren = false`, and when a translation is taller than its box
 * even at the autosize floor, StaticLayout draws every overflow line —
 * TextView's clip is the only thing truncating them. Unclipped stroke ink
 * outside the child rect would be unmasked from OCR and outside pinhole
 * tracking (phantom re-reads, add/remove churn). Two of its details matter:
 * the bottom keeps the full view height when the layout exactly fills the
 * inner box — that is the ordinary case for every WRAP_CONTENT child (the
 * furigana path), where measurement makes `layout.height == vspace` and a
 * padding-edge clip would shave descender strokes — and the shadow-layer
 * expansion must be mirrored because the furigana path's transparent
 * `setShadowLayer` exists solely to widen this clip by the stroke width.
 *
 * Extends platform [TextView], deliberately not AppCompatTextView: these
 * views are constructed from service/overlay contexts that carry no
 * AppCompat theme, and the AppCompat widget logs a theme-check error on
 * every construction — ~14 lines/s during live mode, enough to evict the
 * logcat ring buffer that the in-app diagnostics export reads from. Nothing
 * AppCompat-specific is used here; autosize is applied by the parent via
 * [androidx.core.widget.TextViewCompat], which routes to the platform
 * implementation on this app's minSdk.
 */
internal class OutlinedTextView(context: Context) : TextView(context) {

    var outlineColor: Int = Color.argb(220, 34, 34, 34)
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    var outlineWidth: Float = 0f

    /** Rejects, in debug builds, the TextView states the stroke pass's
     *  offset/clip reproduction deliberately does not cover (see class
     *  KDoc). Release builds skip the check: none of these states is
     *  reachable from this app's call sites, and a wrong-but-drawn outline
     *  beats a crash in the field. */
    private fun checkStrokeContract() {
        check(scrollX == 0 && scrollY == 0) {
            "OutlinedTextView stroke pass does not support scrolling"
        }
        check(ellipsize != TextUtils.TruncateAt.MARQUEE) {
            "OutlinedTextView stroke pass does not support marquee"
        }
        check(hint == null) {
            "OutlinedTextView stroke pass does not support hints"
        }
    }

    override fun onDraw(canvas: Canvas) {
        // getLayout() is null only before the first measure pass; skip the
        // stroke for that (never observed) frame rather than force a layout.
        val textLayout = layout
        if (outlineWidth > 0f && textLayout != null) {
            if (BuildConfig.DEBUG) checkStrokeContract()
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = outlineWidth
            paint.strokeJoin = Paint.Join.ROUND
            paint.color = outlineColor
            canvas.save()
            // TextView.onDraw's clip, in its non-scrolled form. Bottom keeps
            // the full height when the layout exactly fills the inner box
            // (TextView's `scrollY == maxScrollY` case at scrollY 0) — the
            // ordinary case for WRAP_CONTENT children, not a corner case —
            // and an overflowing layout truncates at the bottom padding
            // edge, matching the fill pass.
            var clipLeft = compoundPaddingLeft.toFloat()
            var clipTop = 0f
            var clipRight = (width - compoundPaddingRight).toFloat()
            val vspace = height - compoundPaddingTop - compoundPaddingBottom
            var clipBottom = height.toFloat() -
                (if (textLayout.height == vspace) 0 else extendedPaddingBottom)
            val shadowRadius = paint.shadowLayerRadius
            if (shadowRadius != 0f) {
                clipLeft += min(0f, paint.shadowLayerDx - shadowRadius)
                clipRight += max(0f, paint.shadowLayerDx + shadowRadius)
                clipTop += min(0f, paint.shadowLayerDy - shadowRadius)
                clipBottom += max(0f, paint.shadowLayerDy + shadowRadius)
            }
            canvas.clipRect(clipLeft, clipTop, clipRight, clipBottom)
            canvas.translate(compoundPaddingLeft.toFloat(), totalPaddingTop.toFloat())
            try {
                textLayout.draw(canvas)
            } finally {
                canvas.restore()
                // Restore FILL even if the stroke pass throws — leaked stroke
                // style would corrupt the fill pass and every later frame.
                // The color needs no restore: super.onDraw re-stamps the view
                // color before it draws.
                paint.style = Paint.Style.FILL
            }
        }
        super.onDraw(canvas)
    }
}
