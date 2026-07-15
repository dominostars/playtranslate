package com.playtranslate.capture

import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * The live-start status window: one centered chip spanning the whole startup
 * — stream-kind probe (checker + "Initializing…" label), then OCR engine
 * warm-up (label only) — so the user sees a single continuous affordance
 * instead of a probe window that vanishes right before the longest wait (the
 * multi-second engine load slow devices read as "live mode is broken").
 *
 * Owned by [com.playtranslate.CaptureService]; [StreamKindProbe.measure]
 * only DRIVES the pattern through the [StreamKindProbe.ProbeSurface] seam,
 * it never adds or removes this window. Like the ephemeral probe window, the
 * chip is deliberately NOT registered with the OverlayHost (a registered
 * window can be alpha-blanked by a concurrent clean capture, faking a CLEAN
 * verdict) and is TOUCHABLE while the pattern shows (a pass-through window's
 * composited opacity is clamped by the untrusted-touch rules — ~84% measured
 * — enough to flunk the color match). Unlike the probe window it may live
 * several seconds, so [onVerdictSettled] flips it to FLAG_NOT_TOUCHABLE the
 * moment the pattern retires: only the ~1.4s probe phase may eat taps.
 *
 * The chip must never be OCR'd: the service removes it before the first
 * live cycle is allowed to capture (the first-cycle gate), on stopLive, on a
 * superseding start, and via a hard-cap timer as a leak guard.
 */
internal class StartupChip private constructor(
    private val wm: WindowManager,
    private val view: StreamKindProbe.ProbeView,
    private val params: WindowManager.LayoutParams,
) : StreamKindProbe.ProbeSurface {

    override var patternAddedSeq = 0L
        private set
    override var drawCountAtArm = 0
        private set

    private var armed = false

    /** True once [remove] ran — the chip is single-use. */
    var isRemoved = false
        private set

    override fun armPattern(controller: MediaProjectionController): String? {
        if (isRemoved) return "startup chip already removed"
        if (!view.showPattern) return "startup chip pattern already retired"
        if (armed) {
            // A retry within the same window lifetime: the add-time anchors
            // are stale — round 1 would read a latched pre-pattern frame, or
            // a draw that happened long ago, as evidence (the false-CLEAN
            // shape the anchors exist to prevent). Re-anchor both and force
            // real pixel damage, the mechanism rounds > 1 already trust.
            // No window blink: the phase toggles in place.
            drawCountAtArm = view.drawCount
            patternAddedSeq = controller.deliverySeqNow
            view.swap = !view.swap
            view.invalidate()
        }
        armed = true
        return null
    }

    override suspend fun awaitPatternLaidOut(): Boolean = StreamKindProbe.awaitLaidOut(view)

    override val patternScreenRect: Rect
        get() {
            // Laid-out location is ground truth (immune to gravity/inset
            // surprises); only the checker grid — the label to its right must
            // never contribute cells to the verdict.
            val loc = IntArray(2)
            view.getLocationOnScreen(loc)
            return Rect(
                loc[0], loc[1],
                loc[0] + StreamKindProbe.SIZE_PX, loc[1] + StreamKindProbe.SIZE_PX,
            )
        }

    override var patternSwap: Boolean
        get() = view.swap
        set(value) { view.swap = value }

    override val patternDrawCount: Int get() = view.drawCount

    override fun invalidatePattern() {
        view.invalidate()
    }

    /** The stream-kind verdict settled (whatever it settled to): retire the
     *  checker — label-only from here — and stop consuming taps. The
     *  untrusted-touch opacity exemption only matters while the pattern is
     *  being measured; a label may render clamped, but must not eat the
     *  center of the screen for the seconds warm-up can take. */
    fun onVerdictSettled() {
        if (isRemoved) return
        view.showPattern = false
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        try {
            wm.updateViewLayout(view, params)
        } catch (_: Exception) {
        }
    }

    /** Hide/re-show without removing — used around the UNKNOWN stream-kind
     *  prompt, where an "Initializing…" chip floating over a question would
     *  wrongly say no action is needed. */
    fun setVisible(visible: Boolean) {
        if (isRemoved) return
        view.visibility = if (visible) View.VISIBLE else View.GONE
    }

    /** Remove the window. Idempotent — callable from any of the removal
     *  sites (first-cycle gate, stopLive, superseding start, hard cap). */
    fun remove() {
        if (isRemoved) return
        isRemoved = true
        try {
            wm.removeViewImmediate(view)
        } catch (_: Exception) {
        }
    }

    companion object {
        /**
         * Build and add the chip on the projected display (MediaProjection
         * only ever mirrors [android.view.Display.DEFAULT_DISPLAY]). Returns
         * null when the overlay host / display / WindowManager is missing or
         * the add fails — startup proceeds without a chip; feedback is never
         * allowed to block the feature it narrates.
         *
         * [withPattern] false builds the label-only variant (no probe this
         * start — engine warm-up is the only wait), which never needs the
         * touch-consuming exemption and is born FLAG_NOT_TOUCHABLE.
         */
        fun show(controller: MediaProjectionController, withPattern: Boolean): StartupChip? {
            val host = CaptureBackendResolver.active().overlayHost ?: return null
            // The HOST's context — accessibility overlay window types can
            // only be added from the accessibility service's own context.
            val displayContext = host.displayContextFor(controller.projectedDisplayId)
                ?: return null
            val wm = displayContext.getSystemService(WindowManager::class.java) ?: return null
            val view = StreamKindProbe.ProbeView(displayContext).apply {
                showPattern = withPattern
            }
            // Same window shape as the ephemeral probe (see its params for
            // the touchable + center + inset rationale), minus touchability
            // when there is no pattern to protect.
            val baseFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, StreamKindProbe.SIZE_PX,
                host.windowType,
                if (withPattern) baseFlags
                else baseFlags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.CENTER
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) fitInsetsTypes = 0
            }
            val chip = StartupChip(wm, view, params)
            // Anchor BEFORE the window exists — the add's own composition is
            // round-1 freshness evidence (the ephemeral probe's seqAtAdd,
            // transferred faithfully).
            chip.patternAddedSeq = controller.deliverySeqNow
            return try {
                wm.addView(view, params)
                chip
            } catch (_: Exception) {
                null
            }
        }
    }
}
