package com.playtranslate.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.animation.AccelerateDecelerateInterpolator
import com.google.android.material.button.MaterialButton

/**
 * A [MaterialButton] that can play a one-shot "light sweep" shimmer — a soft
 * highlight band travelling across the face of the button — to draw the eye.
 *
 * Used by the Turn On / Turn Off capture control, shimmered on a timer.
 * The sweep is clipped to the button's rounded-rect shape and reads the
 * current width/height every frame, so it stays correct while the button is
 * restyled or alpha-/scale-animated by the scroll cross-fade.
 */
class ShimmerButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.materialButtonStyle,
) : MaterialButton(context, attrs, defStyleAttr) {

    /** -1 while idle; 0..1 sweep progress while a shimmer plays. */
    private var sweepProgress = -1f
    private var sweepAnimator: ValueAnimator? = null

    private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clipPath = Path()

    /** Play one shimmer sweep. A sweep already in flight is restarted. */
    fun shimmer() {
        sweepAnimator?.cancel()
        sweepAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SWEEP_DURATION_MS
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                sweepProgress = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    sweepProgress = -1f
                    invalidate()
                }
            })
            start()
        }
    }

    override fun onDetachedFromWindow() {
        sweepAnimator?.cancel()
        sweepAnimator = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas) // text + icon
        val progress = sweepProgress
        if (progress < 0f) return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // A highlight band ~40% of the width travels from just off the left
        // edge to just off the right edge.
        val band = w * BAND_FRACTION
        val centre = -band + progress * (w + 2f * band)
        sweepPaint.shader = LinearGradient(
            centre - band, 0f, centre + band, 0f,
            intArrayOf(Color.TRANSPARENT, HIGHLIGHT, Color.TRANSPARENT),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP,
        )
        val radius = cornerRadius.toFloat().let { if (it < 0f) h / 2f else it }
        clipPath.reset()
        clipPath.addRoundRect(0f, 0f, w, h, radius, radius, Path.Direction.CW)
        val saved = canvas.save()
        canvas.clipPath(clipPath)
        canvas.drawRect(0f, 0f, w, h, sweepPaint)
        canvas.restoreToCount(saved)
    }

    private companion object {
        const val SWEEP_DURATION_MS = 850L
        const val BAND_FRACTION = 0.42f
        /** Translucent white — a soft glint over the button's fill. */
        const val HIGHLIGHT = 0x59FFFFFF
    }
}
