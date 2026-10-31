package com.playtranslate.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.animation.LinearInterpolator
import androidx.appcompat.widget.AppCompatImageView
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/**
 * An image view whose glyph can breathe a soft glow of its own colour (its
 * image tint): the Settings hub's lit chip (design_handoff_cloud_chip_admitted,
 * option 4b; [GlyphGlow] is the design's CSS). With [glowing] off it draws
 * exactly as an ImageView.
 *
 * At the breath's peak the glow reaches about 25dp from the glyph's centre,
 * past the 36dp chip it sits in, so the view is laid out larger than its
 * glyph (settings_row_hub.xml: 56dp, with 17dp padding around the 22dp
 * glyph) and draws the glow inside its own bounds; the cell lets it overhang
 * the chip (clipChildren and clipToPadding off on the cell's inner row).
 *
 * The glow is baked per glyph and size ([GlyphGlow.Levels]: the exact glow
 * at evenly spaced points of the breath, each baked the first time a frame
 * needs it) and each frame interpolates the two nearest into one alpha
 * bitmap, drawn under the glyph in the tint.
 * Settings loads the icon as a new Drawable on every render, so a changed
 * Drawable is re-rasterized and compared with the baked glyph before
 * anything is baked again.
 *
 * Motion follows [EdgeRingView]'s rule: it breathes only while [glowing],
 * shown ([onVisibilityAggregated]: attached, visible, window visible) and
 * the system animator scale is not 0; otherwise a glowing view holds the
 * rest glow (keyframe A), which is the design's still glow with animations
 * off.
 */
class GlowImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : AppCompatImageView(context, attrs, defStyleAttr) {

    /** Breathe a glow of the glyph in its tint colour. Turning it off drops
     *  the bake, so an unlit cell holds no glow memory. */
    var glowing: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (!value) dropBake()
            syncMotion()
            invalidate()
        }

    /** Effective visibility, from [onVisibilityAggregated]. */
    private var shown = false
    private var breathe: ValueAnimator? = null

    /** The breath's eased progress: 0 at rest (keyframe A), 1 at the peak (B). */
    private var progress = 0f

    /** The glow levels for one rasterized glyph at this view's size. */
    private class Bake(val source: Drawable, val mask: Bitmap, val levels: GlyphGlow.Levels)

    private var bake: Bake? = null
    /** The frame drawn: [GlyphGlow.Levels.frame] at [frameProgress]. */
    private var frame: Bitmap? = null
    private var frameBytes: ByteArray? = null
    private var frameProgress = Float.NaN
    private val glowPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        shown = isVisible
        syncMotion()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        dropBake()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        dropBake()
    }

    /** Start or stop the breath to match [glowing], [shown] and the system
     *  animator scale. Idempotent: a running breath is left running. */
    private fun syncMotion() {
        val breathing = glowing && shown && ValueAnimator.areAnimatorsEnabled()
        if (breathing == (breathe != null)) return
        if (breathing) {
            breathe = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = GlyphGlow.CYCLE_MS
                // Linear: the design eases each half of the breath on its
                // own, which GlyphGlow.progress does.
                interpolator = LinearInterpolator()
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener {
                    progress = GlyphGlow.progress(it.animatedFraction)
                    invalidate()
                }
                start()
            }
        } else {
            breathe?.cancel()
            breathe = null
            progress = 0f
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (glowing) drawGlow(canvas)
        super.onDraw(canvas)
    }

    private fun drawGlow(canvas: Canvas) {
        val color = imageTintList?.getColorForState(drawableState, Color.TRANSPARENT) ?: return
        val glyph = drawable ?: return
        if (width <= 0 || height <= 0) return
        val levels = levelsFor(glyph)
        val f = frame ?: Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8).also {
            frame = it
            frameBytes = ByteArray(it.byteCount)
            frameProgress = Float.NaN
        }
        if (progress != frameProgress) {
            val bytes = frameBytes ?: return
            levels.frame(progress, bytes)
            f.copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
            frameProgress = progress
        }
        glowPaint.color = color
        canvas.drawBitmap(f, 0f, 0f, glowPaint)
    }

    /** The bake for [glyph] at this size: the current one while [glyph]
     *  rasterizes to the same mask (a re-bind loads the same icon as a new
     *  Drawable), a fresh one otherwise. */
    private fun levelsFor(glyph: Drawable): GlyphGlow.Levels {
        val current = bake
        if (current != null && current.source === glyph) return current.levels
        val mask = rasterize(glyph)
        val next = if (current != null && current.mask.sameAs(mask)) {
            Bake(glyph, current.mask, current.levels)
        } else {
            frameProgress = Float.NaN
            Bake(glyph, mask, GlyphGlow.Levels(mask, resources.displayMetrics.density))
        }
        bake = next
        return next.levels
    }

    /** The glyph's alpha exactly where ImageView draws it: inside the
     *  padding, through the scale type's matrix. */
    private fun rasterize(glyph: Drawable): Bitmap {
        val mask = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
        val c = Canvas(mask)
        c.translate(paddingLeft.toFloat(), paddingTop.toFloat())
        c.concat(imageMatrix)
        glyph.draw(c)
        return mask
    }

    private fun dropBake() {
        bake = null
        frame = null
        frameBytes = null
        frameProgress = Float.NaN
    }
}

/**
 * The lit chip's breathing glyph glow, as the design's CSS defines it:
 *
 *     @keyframes ptGlyphGlow {
 *       0%, 100% { filter: drop-shadow(0 0 2px color-mix(in srgb, accent 55%, transparent)) }
 *       50%      { filter: drop-shadow(0 0 7px accent) drop-shadow(0 0 1px accent) }
 *     }
 *     animation: ptGlyphGlow 3.2s cubic-bezier(.4, 0, .2, 1) infinite
 *
 * read the way a browser renders it:
 *  - a drop-shadow's blur length IS the Gaussian's standard deviation
 *    (Filter Effects 1; box-shadow's blur radius is twice it), so the halo
 *    breathes between sigma 2dp and sigma 7dp;
 *  - filter functions chain: the second drop-shadow shadows the first one's
 *    output, the glyph AND its halo, so at the peak it roughly doubles the
 *    halo's density rather than adding a 1dp rim;
 *  - lists of unequal length interpolate after padding the shorter with the
 *    missing function's initial value (no blur, transparent), so the second
 *    shadow grows from nothing to sigma 1dp at full strength;
 *  - the timing function applies per keyframe interval, so peak to rest
 *    eases with the same curve as rest to peak, not its time reverse (which
 *    a REVERSE-mode animator would play).
 *
 * One deliberate difference from the HTML in Chromium: there, the way back
 * from the peak fades to no glow at all and the rest glow pops back at each
 * cycle start, a quirk of interpolating toward the color-mix() colour (the
 * same keyframes with rgba(accent, .55) return smoothly to the rest glow).
 * The handoff's spec (symmetric keyframes, auto-reverse) is the smooth one,
 * and that is what this draws.
 */
internal object GlyphGlow {
    /** One breath, rest to peak and back. */
    const val CYCLE_MS = 3200L

    /** Evenly spaced points of the breath baked per glyph; a frame between
     *  two is their linear interpolation. */
    private const val LEVELS = 5

    private const val HALO_REST_SIGMA_DP = 2f
    private const val HALO_PEAK_SIGMA_DP = 7f
    private const val HALO_REST_ALPHA = 0.55f
    private const val EDGE_PEAK_SIGMA_DP = 1f

    /** BlurMaskFilter cannot blur less: hwui turns its radius r into
     *  sigma 0.57735 * r + 0.5 (Blur::convertRadiusToSigma), and r must be
     *  positive. Fainter shadows than this are drawn unblurred. */
    private const val MIN_SIGMA_PX = 0.5f
    private const val SIGMA_PER_RADIUS = 0.57735f

    /** cubic-bezier(.4, 0, .2, 1), table-driven (no Path). */
    private val EASING = FastOutSlowInInterpolator()

    /** The two drop-shadows at one point of the breath: the halo (the first
     *  function) and the edge (the second, which shadows glyph plus halo).
     *  Sigmas in dp, alphas 0..1. */
    private data class Shadows(
        val haloSigmaDp: Float,
        val haloAlpha: Float,
        val edgeSigmaDp: Float,
        val edgeAlpha: Float,
    )

    /** Eased progress, 0 at rest (keyframe A) to 1 at the peak (B), at
     *  [cycleFraction] (0..1) of the cycle. */
    fun progress(cycleFraction: Float): Float {
        val f = cycleFraction.coerceIn(0f, 1f)
        return if (f < 0.5f) {
            EASING.getInterpolation(f * 2f)
        } else {
            1f - EASING.getInterpolation(f * 2f - 1f)
        }
    }

    /** The drop-shadows at progress [t]: keyframe A at 0, B at 1. */
    private fun shadows(t: Float): Shadows {
        val p = t.coerceIn(0f, 1f)
        return Shadows(
            haloSigmaDp = HALO_REST_SIGMA_DP + (HALO_PEAK_SIGMA_DP - HALO_REST_SIGMA_DP) * p,
            haloAlpha = HALO_REST_ALPHA + (1f - HALO_REST_ALPHA) * p,
            edgeSigmaDp = EDGE_PEAK_SIGMA_DP * p,
            edgeAlpha = p,
        )
    }

    /** The BlurMaskFilter radius whose blur has [sigmaPx]. */
    private fun blurRadiusPx(sigmaPx: Float): Float = (sigmaPx - MIN_SIGMA_PX) / SIGMA_PER_RADIUS

    /**
     * The glow alone at progress [t] for the glyph alpha [mask], as a new
     * ALPHA_8 bitmap of the mask's size: the halo over the edge shadow,
     * which is what the filter adds under the glyph (drawn over it by the
     * view, it completes the filter's output). On a software canvas, the
     * only place BlurMaskFilter blurs a bitmap's alpha.
     */
    private fun bake(mask: Bitmap, density: Float, t: Float): Bitmap {
        val s = shadows(t)
        val halo = blank(mask)
        drawShadow(Canvas(halo), mask, s.haloSigmaDp * density, s.haloAlpha)
        // The first drop-shadow's output, which the second one shadows: the
        // glyph over its halo.
        val first = halo.copy(Bitmap.Config.ALPHA_8, true)
        Canvas(first).drawBitmap(mask, 0f, 0f, null)
        val glow = blank(mask)
        val c = Canvas(glow)
        drawShadow(c, first, s.edgeSigmaDp * density, s.edgeAlpha)
        c.drawBitmap(halo, 0f, 0f, null)
        halo.recycle()
        first.recycle()
        return glow
    }

    private fun blank(like: Bitmap): Bitmap =
        Bitmap.createBitmap(like.width, like.height, Bitmap.Config.ALPHA_8)

    /** Draw [source]'s alpha, blurred to [sigmaPx], at [alpha] into [canvas]. */
    private fun drawShadow(canvas: Canvas, source: Bitmap, sigmaPx: Float, alpha: Float) {
        if (alpha <= 0f) return
        val paint = Paint().apply { this.alpha = (alpha * 255f).roundToInt() }
        if (sigmaPx <= MIN_SIGMA_PX) {
            canvas.drawBitmap(source, 0f, 0f, paint)
            return
        }
        val offset = IntArray(2)
        val blur = Paint().apply {
            maskFilter = BlurMaskFilter(blurRadiusPx(sigmaPx), BlurMaskFilter.Blur.NORMAL)
        }
        val blurred = source.extractAlpha(blur, offset)
        canvas.drawBitmap(blurred, offset[0].toFloat(), offset[1].toFloat(), paint)
        blurred.recycle()
    }

    /**
     * The glow for one glyph mask at [LEVELS] evenly spaced points of the
     * breath, kept as raw ALPHA_8 pixels, each baked the first time a frame
     * needs it (a first draw bakes one level, not all); [frame] fills a
     * bitmap of the mask's size (made the same way, so the same row layout)
     * with any point between them.
     */
    class Levels(private val mask: Bitmap, private val density: Float) {
        private val pixels = arrayOfNulls<ByteArray>(LEVELS)

        private fun level(k: Int): ByteArray = pixels[k] ?: run {
            val glow = bake(mask, density, k / (LEVELS - 1f))
            ByteArray(glow.byteCount).also {
                glow.copyPixelsToBuffer(ByteBuffer.wrap(it))
                glow.recycle()
                pixels[k] = it
            }
        }

        /** Write the glow at progress [t] into [out]: the linear
         *  interpolation of the two nearest levels. */
        fun frame(t: Float, out: ByteArray) {
            val pos = t.coerceIn(0f, 1f) * (LEVELS - 1)
            val k = pos.toInt().coerceAtMost(LEVELS - 2)
            val w = ((pos - k) * 256f).roundToInt()
            val a = level(k)
            if (w == 0) {
                a.copyInto(out)
                return
            }
            val b = level(k + 1)
            for (i in out.indices) {
                val va = a[i].toInt() and 0xFF
                val vb = b[i].toInt() and 0xFF
                out[i] = ((va * (256 - w) + vb * w + 128) shr 8).toByte()
            }
        }
    }
}
