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

    init {
        // Be explicitly clickable so the base View fires exactly ONE performClick
        // per tap (its ACTION_UP handler) and exposes the TalkBack click action.
        // The gestureDetector below must NOT also call performClick — doing both
        // double-fired onTapAtOffset (one synchronous gesture click + one posted
        // View click), which double-resolved the tapped word.
        isClickable = true
    }

    // Set by [onSingleTapUp] on every touch tap; consumed by the base View's
    // single [performClick] so the touch path lands on the correct offset.
    // Defaults to 0 so a TalkBack double-tap — which fires performClick without
    // running onSingleTapUp — opens at the start of the text.
    private var pendingTapOffset: Int = 0

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        // Records the tap offset only; the click is fired by the base View.
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            pendingTapOffset = offsetAt(e.x, e.y)
            return true
        }
    })

    fun setSegments(segments: List<TextSegment>) {
        text = segments.joinToString("") { it.text }
        highlightColor = 0x00000000
    }

    // Taps are turned into a single click by the base View's ACTION_UP handling
    // (this view is clickable); [gestureDetector] only records the tap offset.
    // Lint can't see performClick on the View's posted-click path, hence the
    // suppression.
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
