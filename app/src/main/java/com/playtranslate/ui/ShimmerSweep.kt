package com.playtranslate.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator

/**
 * Plays a one-shot "light sweep" highlight band across [host]. Shared by
 * [ShimmerButton] (button face) and [ShimmerLinearLayout] (row surface) so
 * the sweep animation and clip-path are defined in exactly one place.
 *
 * Hosts wire it in three places:
 *  - construct one instance per host (passing `this`)
 *  - call [start] to play (e.g. on a timer)
 *  - call [cancel] in `onDetachedFromWindow`
 *  - call [draw] from `onDraw` / `dispatchDraw`, passing the host's rounded-
 *    rect corner radius so the sweep clips to the host's silhouette
 */
internal class ShimmerSweep(private val host: View) {

    /** -1 while idle; 0..1 sweep progress while a shimmer plays. */
    private var progress = -1f
    private var animator: ValueAnimator? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clipPath = Path()

    /** Play one sweep. A sweep already in flight is restarted. */
    fun start() {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SWEEP_DURATION_MS
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                host.invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    progress = -1f
                    host.invalidate()
                }
            })
            start()
        }
    }

    fun cancel() {
        animator?.cancel()
        animator = null
    }

    /** Draw the current sweep frame clipped to a rounded-rect with corner
     *  radius [cornerRadius] px. A negative radius is treated as fully
     *  rounded (height / 2), matching MaterialButton's pill behaviour. */
    fun draw(canvas: Canvas, cornerRadius: Float) {
        val p = progress
        if (p < 0f) return
        val w = host.width.toFloat()
        val h = host.height.toFloat()
        if (w <= 0f || h <= 0f) return

        // A highlight band ~40% of the width travels from just off the left
        // edge to just off the right edge. The band is tilted [TILT_DEG]
        // clockwise so it reads as a slanted glint rather than a flat wipe.
        //
        // Tilt math: the gradient axis is rotated about the band's centre
        // point (centreX, h/2). With a non-zero tilt the band's top + bottom
        // ends sweep horizontally at different times, so we widen the travel
        // by an edge buffer (h/2 * tan(tilt)) on each side — without it, the
        // trailing corner of the band would still be visible when p == 1.
        val theta = Math.toRadians(TILT_DEG.toDouble())
        val cosT = Math.cos(theta).toFloat()
        val sinT = Math.sin(theta).toFloat()
        val edgeBuffer = h * 0.5f * (sinT / cosT)
        val band = w * BAND_FRACTION
        val centreX = -(band + edgeBuffer) + p * (w + 2f * (band + edgeBuffer))
        val centreY = h * 0.5f
        val dx = band * cosT
        val dy = band * sinT
        paint.shader = LinearGradient(
            centreX - dx, centreY - dy,
            centreX + dx, centreY + dy,
            intArrayOf(Color.TRANSPARENT, HIGHLIGHT, Color.TRANSPARENT),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP,
        )
        val r = if (cornerRadius < 0f) h / 2f else cornerRadius
        clipPath.reset()
        clipPath.addRoundRect(0f, 0f, w, h, r, r, Path.Direction.CW)
        val saved = canvas.save()
        canvas.clipPath(clipPath)
        canvas.drawRect(0f, 0f, w, h, paint)
        canvas.restoreToCount(saved)
    }

    private companion object {
        const val SWEEP_DURATION_MS = 850L
        const val BAND_FRACTION = 0.42f
        /** Translucent white — a soft glint over the host's fill. */
        const val HIGHLIGHT = 0x59FFFFFF
        /** Band tilt — positive = top-of-band leans right ("/" shape). */
        const val TILT_DEG = 30f
    }
}
