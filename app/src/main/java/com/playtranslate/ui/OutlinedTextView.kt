package com.playtranslate.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.widget.TextView

/**
 * TextView that draws a stroke outline behind the text for readability
 * without a background: a STROKE pass of the layout, then the normal FILL
 * pass on top.
 *
 * The two passes need different paint configs within a single frame, so the
 * flip itself must live in [onDraw] — but everything mutated there is plain
 * [Paint] state, which is inert (a Paint holds no view reference, so the swap
 * cannot trigger an invalidate). The stroke COLOR specifically goes through a
 * Paint-level color filter rather than `setTextColor()`: TextView re-stamps
 * its view-level color onto the paint at the start of every draw (which is
 * why `paint.color` writes don't work here), and `setTextColor()` invalidates
 * unconditionally — an invalidate from inside a draw schedules the next
 * frame, and every visible outlined box then redraws its whole window at the
 * display refresh rate forever (measured: 118–120 draws/s on a completely
 * static screen). The filter survives the re-stamp and touches no view state.
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
            strokeFilter = PorterDuffColorFilter(value, PorterDuff.Mode.SRC_IN)
            invalidate()
        }

    var outlineWidth: Float = 0f

    /** Recolors the stroke pass to [outlineColor]. SRC_IN keeps the glyph
     *  coverage (and the fill color's alpha) as the mask, replacing only the
     *  color — identical output to the old setTextColor swap for the opaque
     *  text colors the overlay uses. Rebuilt in the [outlineColor] setter,
     *  never during draw. */
    private var strokeFilter = PorterDuffColorFilter(outlineColor, PorterDuff.Mode.SRC_IN)

    override fun onDraw(canvas: Canvas) {
        if (outlineWidth > 0f) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = outlineWidth
            paint.strokeJoin = Paint.Join.ROUND
            paint.colorFilter = strokeFilter
            try {
                super.onDraw(canvas)
            } finally {
                // Restore FILL + no filter even if the stroke pass throws —
                // leaked stroke state would corrupt the fill pass, TextView's
                // own measurement, and every later frame.
                paint.colorFilter = null
                paint.style = Paint.Style.FILL
            }
        }
        super.onDraw(canvas)
    }
}
