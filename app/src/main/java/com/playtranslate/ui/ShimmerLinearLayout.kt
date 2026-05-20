package com.playtranslate.ui

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.widget.LinearLayout

/**
 * A [LinearLayout] that can play a one-shot "light sweep" shimmer over its
 * children, clipped to the layout's own bounds (NOT the parent card's). Used
 * for the power "cell" sitting at the top of the unified Settings top card,
 * where the sweep must stay on that one row instead of sweeping across the
 * sibling on-screen-controls rows.
 *
 * Sweep animation lives in [ShimmerSweep] (shared with [ShimmerButton]).
 */
class ShimmerLinearLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    private val sweep = ShimmerSweep(this)

    /** Play one shimmer sweep. */
    fun shimmer() = sweep.start()

    override fun onDetachedFromWindow() {
        sweep.cancel()
        super.onDetachedFromWindow()
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        // Clip to rectangular bounds — the parent MaterialCardView handles
        // the outer corner rounding for the whole top section, so this row
        // is a flat rectangle within it.
        sweep.draw(canvas, 0f)
    }
}
