package com.playtranslate.camera

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View

/**
 * What a touch on the camera's live preview means. A TAP on a word of the
 * live Definitions outlines is the word's ([onWord]): one finger, down and
 * up in the same place, however long it rested (the platform's own tap,
 * with its long-press off). Every other release is the camera's focus
 * point, as it always was ([onFocus]): a tap off every word, and also a
 * drag or a several-finger gesture that happens to end over a word, which
 * must not take a snapshot nobody asked for.
 *
 * Main thread only.
 */
class PreviewTaps(
    context: Context,
    /** The word of the live outlines a touch at view ([x], [y]) picks, or
     *  null with none (and in every mode and state that has no outlines). */
    private val wordAt: (x: Float, y: Float) -> CameraSession.LiveWord?,
    /** A word was tapped, at screen ([rawX], [rawY]), the finger having
     *  come down at [downUptimeMs] (the impact a snapshot's frame choice is
     *  anchored on). */
    private val onWord: (word: CameraSession.LiveWord, rawX: Float, rawY: Float, downUptimeMs: Long) -> Unit,
    /** A release that is not a word's, at view ([x], [y]). */
    private val onFocus: (x: Float, y: Float) -> Unit,
) : View.OnTouchListener {

    private val taps = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val word = wordAt(e.x, e.y) ?: return false
                onWord(word, e.rawX, e.rawY, e.downTime)
                return true
            }
        },
    ).apply {
        // A finger that rests on a word before lifting still tapped it.
        setIsLongpressEnabled(false)
    }

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        val wordTookIt = taps.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            if (!wordTookIt) onFocus(event.x, event.y)
            v.performClick()
        }
        return true
    }
}
