package com.playtranslate.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.appcompat.widget.AppCompatTextView

/** TextView that draws a stroke outline behind the text for readability without a background. */
internal class OutlinedTextView(context: Context) : AppCompatTextView(context) {
    var outlineColor: Int = Color.argb(220, 34, 34, 34)
    var outlineWidth: Float = 0f

    /** True while [onDraw] swaps text colors for the stroke pass.
     *
     *  TextView.setTextColor() invalidates unconditionally, and an invalidate
     *  issued from inside a draw schedules another frame — so without this
     *  guard every draw begets the next, and any window showing an outlined
     *  box redraws at the display refresh rate forever (measured: 118–120
     *  draws/s on a completely static screen, which also kept the
     *  MediaProjection mirror reporting full-rate "changes" while nothing
     *  changed). setTextColor is still the right mechanism for the swap
     *  itself: TextView re-applies its own current color to the paint during
     *  draw, so writing paint.color directly has no effect here. */
    private var inOutlineDraw = false

    override fun invalidate() {
        if (inOutlineDraw) return
        super.invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (outlineWidth > 0f) {
            inOutlineDraw = true
            try {
                val savedColor = currentTextColor
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = outlineWidth
                paint.strokeJoin = Paint.Join.ROUND
                setTextColor(outlineColor)
                super.onDraw(canvas)
                paint.style = Paint.Style.FILL
                setTextColor(savedColor)
            } finally {
                inOutlineDraw = false
            }
        }
        super.onDraw(canvas)
    }
}
