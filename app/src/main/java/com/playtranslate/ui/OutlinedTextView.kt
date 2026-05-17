package com.playtranslate.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.widget.TextView

/** TextView that draws a stroke outline behind the text for readability without a background. */
internal class OutlinedTextView(context: Context) : TextView(context) {
    var outlineColor: Int = Color.argb(220, 34, 34, 34)
    var outlineWidth: Float = 0f

    override fun onDraw(canvas: Canvas) {
        if (outlineWidth > 0f) {
            val savedColor = currentTextColor
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = outlineWidth
            paint.strokeJoin = Paint.Join.ROUND
            setTextColor(outlineColor)
            super.onDraw(canvas)
            paint.style = Paint.Style.FILL
            setTextColor(savedColor)
        }
        super.onDraw(canvas)
    }
}
