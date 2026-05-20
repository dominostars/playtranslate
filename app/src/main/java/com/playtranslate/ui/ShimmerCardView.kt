package com.playtranslate.ui

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import com.google.android.material.card.MaterialCardView

/**
 * A [MaterialCardView] that can play a one-shot "light sweep" shimmer over
 * its content, clipped to the card's corner radius. Used by the power status
 * card to draw the eye when capture is off.
 *
 * The sweep is drawn in [dispatchDraw] AFTER the children so it sits on top
 * of the icon block, title, switch, etc. — same effect as [ShimmerButton]'s
 * post-`onDraw` overlay. Sweep animation lives in [ShimmerSweep].
 */
class ShimmerCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.materialCardViewStyle,
) : MaterialCardView(context, attrs, defStyleAttr) {

    private val sweep = ShimmerSweep(this)

    /** Play one shimmer sweep. */
    fun shimmer() = sweep.start()

    override fun onDetachedFromWindow() {
        sweep.cancel()
        super.onDetachedFromWindow()
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        sweep.draw(canvas, radius)
    }
}
