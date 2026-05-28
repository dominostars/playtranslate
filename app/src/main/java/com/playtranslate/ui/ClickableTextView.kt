package com.playtranslate.ui

import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import androidx.appcompat.widget.AppCompatTextView
import com.playtranslate.model.TextSegment

/**
 * A [TextView] that renders OCR text and fires [onTapAtOffset] with the character
 * position corresponding to where the user tapped, so the caller can open an editor
 * with the cursor pre-placed at the right spot.
 */
class ClickableTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatTextView(context, attrs, defStyleAttr) {

    var onTapAtOffset: ((charOffset: Int) -> Unit)? = null

    // Set by [onSingleTapUp] before it calls [performClick] so the
    // accessibility-click path and the touch path go through the same
    // performClick → onTapAtOffset chain with the correct offset. Defaults
    // to 0 so a TalkBack double-tap (which doesn't run onSingleTapUp)
    // opens the editor at the start of the text.
    private var pendingTapOffset: Int = 0

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            pendingTapOffset = offsetAt(e.x, e.y)
            performClick()
            return true
        }
    })

    fun setSegments(segments: List<TextSegment>) {
        text = segments.joinToString("") { it.text }
        highlightColor = 0x00000000
    }

    // Click detection runs through [gestureDetector]'s onSingleTapUp, which
    // calls performClick. Lint can't trace that path through the anonymous
    // SimpleOnGestureListener and so flags onTouchEvent for not calling
    // performClick directly.
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        super.onTouchEvent(event)
        return true
    }

    /** Single entry point for both touch-driven taps (offset set by
     *  [onSingleTapUp]) and TalkBack double-tap (offset stays at the 0
     *  default — screen-reader users can't pick an x/y but can navigate
     *  within the editor with TalkBack's text-editing gestures). */
    override fun performClick(): Boolean {
        super.performClick()
        onTapAtOffset?.invoke(pendingTapOffset)
        return true
    }

    private fun offsetAt(x: Float, y: Float): Int {
        val raw = text?.toString() ?: return 0
        val lyt = layout ?: return 0
        val tx = (x - totalPaddingLeft + scrollX).toInt()
        val ty = (y - totalPaddingTop + scrollY).toInt()
        val line = lyt.getLineForVertical(ty)
        return lyt.getOffsetForHorizontal(line, tx.toFloat())
            .coerceIn(0, (raw.length - 1).coerceAtLeast(0))
    }
}
