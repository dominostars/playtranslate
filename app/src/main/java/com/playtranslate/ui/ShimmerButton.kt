package com.playtranslate.ui

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import com.google.android.material.button.MaterialButton

/**
 * A [MaterialButton] that can play a one-shot "light sweep" shimmer — a soft
 * highlight band travelling across the face of the button — to draw the eye.
 *
 * Used by the nav-bar Turn On / Turn Off capture control, shimmered on a
 * timer. The sweep clips to the button's rounded-rect shape and reads the
 * current width/height every frame, so it stays correct while the button is
 * restyled or alpha-/scale-animated by the scroll cross-fade.
 *
 * Sweep animation lives in [ShimmerSweep] (shared with [ShimmerCardView]).
 */
class ShimmerButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.materialButtonStyle,
) : MaterialButton(context, attrs, defStyleAttr) {

    private val sweep = ShimmerSweep(this)

    /** Play one shimmer sweep. */
    fun shimmer() = sweep.start()

    override fun onDetachedFromWindow() {
        sweep.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas) // text + icon
        sweep.draw(canvas, cornerRadius.toFloat())
    }
}
