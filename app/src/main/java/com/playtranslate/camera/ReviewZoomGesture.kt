package com.playtranslate.camera

import android.content.Context
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import kotlin.math.abs

/**
 * The frozen-review zoom/pan/lookup gesture arbiter. Wraps the sheet's
 * outside-touch stream (which delivers the FULL multi-touch stream once a
 * DOWN is claimed — including POINTER_DOWN/UP) and dispatches between the
 * existing word-lookup state machine and the new pinch-zoom:
 *
 *  - ceiling == 1 (at-native content) or off-origin host: pure passthrough
 *    to [CameraWordLookup] — a zero-diff path, no gesture state constructed;
 *  - at fit: DOWN/MOVE/UP forward to the lookup verbatim (tap = definition,
 *    hold/drag = magnifier — today's behavior); a second finger cancels the
 *    lookup and starts a pinch;
 *  - zoomed: single-finger drag pans (clamped), a slop-free release is a
 *    TAP-DEFINE — the cached DOWN and the real UP are forwarded
 *    back-to-back into the lookup, whose existing tap path produces the
 *    definition (synchronous delivery: the 160 ms hold-reveal never fires);
 *    pinches keep zooming. The drag-magnifier deliberately does not run
 *    while zoomed — zoom IS the magnification.
 *
 * No double-tap gesture by design: it would tax every definition tap with
 * the double-tap timeout.
 *
 * Main thread only.
 */
class ReviewZoomGesture(
    context: Context,
    val zoom: ReviewZoom,
    private val wordLookup: CameraWordLookup,
    /** Mirrors [CameraWordLookup]'s off-origin decline: multi-window
     *  geometry is unvalidatable — zoom is absent there, not wrong. */
    private val hostOrigin: () -> Pair<Int, Int>,
    /** The transform changed — fan out to the image/warp/indicator. */
    private val onChanged: () -> Unit,
    /** A gesture ended — the host may re-raster for crispness. */
    private val onSettled: () -> Unit,
) {
    private enum class Mode { NONE, PASSTHROUGH, PAN_OR_TAP }

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                zoom.scaleBy(detector.scaleFactor, detector.focusX, detector.focusY)
                onChanged()
                return true
            }
        },
    ).apply {
        // Quick scale is a double-tap-drag gesture — the double-tap family
        // is deliberately absent so definition taps stay latency-free.
        isQuickScaleEnabled = false
    }

    private var mode = Mode.NONE

    /** The word lookup claimed the forwarded at-fit DOWN (it may decline —
     *  no scene yet — while we still own the gesture for pinching). */
    private var lookupClaimed = false

    /** This gesture's DOWN, retained for the zoomed tap-define forward. */
    private var pendingDown: MotionEvent? = null

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var movedPastSlop = false
    private var pinched = false

    /** The sheet-routed outside gesture stream. Returns whether the DOWN is
     *  claimed; every later event of a claimed gesture arrives regardless. */
    fun onOutsideTouch(ev: MotionEvent): Boolean {
        if (!zoom.zoomEnabled) return wordLookup.onOutsideTouch(ev)
        val (ox, oy) = hostOrigin()
        if (ox != 0 || oy != 0) return wordLookup.onOutsideTouch(ev)

        scaleDetector.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                recyclePendingDown()
                pendingDown = MotionEvent.obtain(ev)
                downX = ev.rawX
                downY = ev.rawY
                lastX = ev.rawX
                lastY = ev.rawY
                movedPastSlop = false
                pinched = false
                if (zoom.isAtFit) {
                    mode = Mode.PASSTHROUGH
                    lookupClaimed = wordLookup.onOutsideTouch(ev)
                } else {
                    mode = Mode.PAN_OR_TAP
                    lookupClaimed = false
                }
                // Claim even when the lookup declined (no scene yet): the
                // pinch must work while OCR is still reading the image. The
                // sheet's alternative was consume-and-ignore anyway.
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                pinched = true
                if (lookupClaimed) {
                    // The magnifier/drag flow dies with the second finger —
                    // the pinch owns the gesture now.
                    wordLookup.dismiss()
                    lookupClaimed = false
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (scaleDetector.isInProgress || ev.pointerCount > 1) {
                    lastX = ev.rawX
                    lastY = ev.rawY
                    return true
                }
                when (mode) {
                    Mode.PASSTHROUGH -> if (lookupClaimed) wordLookup.onOutsideTouch(ev)
                    Mode.PAN_OR_TAP -> {
                        if (!movedPastSlop &&
                            (abs(ev.rawX - downX) > touchSlop || abs(ev.rawY - downY) > touchSlop)
                        ) {
                            movedPastSlop = true
                        }
                        // Post-pinch single-finger residue never pans — the
                        // gesture was a pinch (WaveformTrimView precedent).
                        if (movedPastSlop && !pinched) {
                            zoom.panBy(ev.rawX - lastX, ev.rawY - lastY)
                            onChanged()
                        }
                    }
                    Mode.NONE -> Unit
                }
                lastX = ev.rawX
                lastY = ev.rawY
                return true
            }
            MotionEvent.ACTION_UP -> {
                when {
                    mode == Mode.PASSTHROUGH && lookupClaimed ->
                        wordLookup.onOutsideTouch(ev)
                    mode == Mode.PAN_OR_TAP && !pinched && !movedPastSlop -> {
                        // Zoomed tap: define at the release point. Forward
                        // the cached DOWN then the real UP — delivered
                        // back-to-back, so the lookup's hold timer never
                        // fires and its existing tap path runs.
                        pendingDown?.let { down ->
                            if (wordLookup.onOutsideTouch(down)) {
                                wordLookup.onOutsideTouch(ev)
                            }
                        }
                    }
                }
                endGesture()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (mode == Mode.PASSTHROUGH && lookupClaimed) {
                    wordLookup.onOutsideTouch(ev)
                }
                endGesture()
                return true
            }
        }
        return true
    }

    /** Snap back to fit (crop entry, episode boundaries) and fan out. */
    fun resetToFit() {
        if (!zoom.isAtFit) {
            zoom.reset()
            onChanged()
        }
    }

    private fun endGesture() {
        mode = Mode.NONE
        lookupClaimed = false
        recyclePendingDown()
        onSettled()
    }

    private fun recyclePendingDown() {
        pendingDown?.recycle()
        pendingDown = null
    }
}
