package com.playtranslate.capture

import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.playtranslate.R
import com.playtranslate.overlay.WindowChurnGate
import com.playtranslate.overlayThemedContext
import com.playtranslate.themeColor

/**
 * The live-start status card: one centered window spanning the whole startup
 * — stream-kind probe, then OCR engine warm-up — so the user sees a single
 * continuous affordance instead of a probe window that vanishes right before
 * the longest wait (the multi-second engine load slow devices read as "live
 * mode is broken").
 *
 * Styled after [com.playtranslate.ui.OverlayAlert]'s card (ptSurface fill,
 * ptDivider hairline, 16dp radius) with the detection grid centered above
 * the label. When the verdict settles the grid's slot swaps to an
 * indeterminate spinner — same footprint, so the card never resizes — and
 * the spinner carries the warm-up phase. The no-probe variant is born in
 * the spinner state. Deliberately NO entrance animation: round 1 of the
 * probe counts the add's own composition as evidence, so the pattern must
 * render at full alpha from the very first frame.
 *
 * Owned by [com.playtranslate.LiveSessionFeedback]; [StreamKindProbe.measure]
 * only DRIVES the pattern through the [StreamKindProbe.ProbeSurface] seam,
 * it never adds or removes this window. Like the ephemeral probe window, the
 * card is deliberately NOT registered with the OverlayHost (a registered
 * window can be alpha-blanked by a concurrent clean capture, faking a CLEAN
 * verdict) and is TOUCHABLE — for its whole life, both variants: a
 * pass-through window's composited opacity is clamped by the
 * untrusted-touch rules (~84% measured — enough to flunk the probe's color
 * match, and enough that startup chrome reads as broken glass). The cost —
 * the small centered card eats its own taps during a loading phase — is
 * accepted; NOT_TOUCH_MODAL keeps every other touch flowing.
 *
 * The card spans the whole startup, including the first OCR pass: it is
 * removed when that pass COMPLETES (or on stopLive, a superseding start, or
 * the hard-cap leak guard). Its own text must still never be OCR'd — CLEAN
 * task mirrors never composite it, and on every other capture path the
 * pipeline excludes recognized groups intersecting [onScreenRect]
 * (LiveSessionFeedback.ocrExclusionRect).
 */
internal class StartupChip private constructor(
    private val wm: WindowManager,
    private val root: View,
    private val windowParams: WindowManager.LayoutParams,
    private val displayId: Int,
    private val pattern: StreamKindProbe.PatternView,
    private val spinner: ProgressBar,
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
        if (pattern.visibility != View.VISIBLE) return "startup chip pattern already retired"
        if (armed) {
            // A retry within the same window lifetime: the add-time anchors
            // are stale — round 1 would read a latched pre-pattern frame, or
            // a draw that happened long ago, as evidence (the false-CLEAN
            // shape the anchors exist to prevent). Re-anchor both and force
            // real pixel damage, the mechanism rounds > 1 already trust.
            // No window blink: the phase toggles in place.
            drawCountAtArm = pattern.drawCount
            patternAddedSeq = controller.deliverySeqNow
            pattern.swap = !pattern.swap
            pattern.invalidate()
        }
        armed = true
        return null
    }

    override suspend fun awaitPatternLaidOut(): Boolean = StreamKindProbe.awaitLaidOut(pattern)

    override val patternScreenRect: Rect
        get() {
            // The grid child's own laid-out location is ground truth (immune
            // to gravity/inset/padding surprises); only the checker — the
            // card around it must never contribute cells to the verdict.
            val loc = IntArray(2)
            pattern.getLocationOnScreen(loc)
            return Rect(
                loc[0], loc[1],
                loc[0] + StreamKindProbe.SIZE_PX, loc[1] + StreamKindProbe.SIZE_PX,
            )
        }

    override var patternSwap: Boolean
        get() = pattern.swap
        set(value) { pattern.swap = value }

    override val patternDrawCount: Int get() = pattern.drawCount

    override fun invalidatePattern() {
        pattern.invalidate()
    }

    /** The stream-kind verdict settled (whatever it settled to): the grid's
     *  slot swaps to the loading spinner — INVISIBLE keeps the grid's
     *  bounds, and the slot is fixed-size, so the card does not resize.
     *  The window stays TOUCHABLE for its whole life: a pass-through
     *  overlay's composited opacity is clamped to ~84% by the
     *  untrusted-touch rules, and startup chrome rendering translucent
     *  reads as broken. The cost — the small centered card eats its own
     *  taps during a loading phase — is accepted (2026-07-15 decision);
     *  NOT_TOUCH_MODAL keeps every other touch flowing. */
    fun onVerdictSettled() {
        if (isRemoved) return
        pattern.visibility = View.INVISIBLE
        spinner.visibility = View.VISIBLE
    }

    /** Hide/re-show without removing — used around the UNKNOWN stream-kind
     *  prompt, where an "Initializing…" card floating over a question would
     *  wrongly say no action is needed. */
    fun setVisible(visible: Boolean) {
        if (isRemoved) return
        root.visibility = if (visible) View.VISIBLE else View.GONE
    }

    /** The card's current on-screen bounds while it can appear in captured
     *  frames — null when removed, hidden, or not yet laid out (a zero-size
     *  rect excludes nothing, so pre-layout passes lose no text). Live per
     *  call: whatever moves the window moves the exclusion with it. */
    val onScreenRect: Rect?
        get() {
            if (isRemoved || root.visibility != View.VISIBLE) return null
            if (root.width == 0 || root.height == 0) return null
            val loc = IntArray(2)
            root.getLocationOnScreen(loc)
            return Rect(loc[0], loc[1], loc[0] + root.width, loc[1] + root.height)
        }

    /** Remove the window. Idempotent — callable from any of the removal
     *  sites (first-cycle gate, stopLive, superseding start, hard cap). */
    fun remove() {
        if (isRemoved) return
        isRemoved = true
        // Gated destroy (Thor firmware add/remove race — see WindowChurnGate).
        // The defused ghost is composited out (window alpha 0), so it can't
        // land in captures even though [onScreenRect] stops excluding it the
        // moment isRemoved flips.
        WindowChurnGate.removeWindow(root, wm, windowParams, displayId)
    }

    companion object {
        /**
         * Build and add the card on the projected display (MediaProjection
         * only ever mirrors [android.view.Display.DEFAULT_DISPLAY]). Returns
         * null when the overlay host / display / WindowManager is missing or
         * the add fails — startup proceeds without a chip; feedback is never
         * allowed to block the feature it narrates.
         *
         * [withPattern] false builds the spinner-from-birth variant (no
         * probe this start — engine warm-up is the only wait).
         */
        fun show(controller: MediaProjectionController, withPattern: Boolean): StartupChip? {
            val host = CaptureBackendResolver.active().overlayHost ?: return null
            // The HOST's context — accessibility overlay window types can
            // only be added from the accessibility service's own context.
            // Theme-wrapped so the OverlayAlert pt* tokens resolve.
            val displayContext = host.displayContextFor(controller.projectedDisplayId)
                ?.let { overlayThemedContext(it) }
                ?: return null
            val wm = displayContext.getSystemService(WindowManager::class.java) ?: return null
            val dp = displayContext.resources.displayMetrics.density

            val pattern = StreamKindProbe.PatternView(displayContext).apply {
                visibility = if (withPattern) View.VISIBLE else View.INVISIBLE
            }
            val spinner = ProgressBar(displayContext).apply {
                isIndeterminate = true
                visibility = if (withPattern) View.INVISIBLE else View.VISIBLE
            }
            // Fixed slot fitting both occupants: the grid is SIZE_PX raw
            // pixels (a scan artifact, not dp), the spinner 24dp. Whichever
            // shows, the slot — and therefore the card — keeps one size.
            val spinnerPx = (24 * dp).toInt()
            val slotPx = maxOf(StreamKindProbe.SIZE_PX, spinnerPx)
            val slot = FrameLayout(displayContext).apply {
                addView(
                    pattern,
                    FrameLayout.LayoutParams(
                        StreamKindProbe.SIZE_PX, StreamKindProbe.SIZE_PX, Gravity.CENTER,
                    ),
                )
                addView(spinner, FrameLayout.LayoutParams(spinnerPx, spinnerPx, Gravity.CENTER))
            }

            val label = TextView(displayContext).apply {
                text = displayContext.getString(R.string.startup_initializing_auto_mode)
                setTextColor(displayContext.themeColor(R.attr.ptText))
                textSize = 13f
                gravity = Gravity.CENTER
            }

            // OverlayAlert's card, scaled down for a passive status chip —
            // and with NO entrance animation (see class doc: round 1 counts
            // the add's own composition).
            val card = LinearLayout(displayContext).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                background = GradientDrawable().apply {
                    setColor(displayContext.themeColor(R.attr.ptSurface))
                    setStroke((1 * dp).toInt(), displayContext.themeColor(R.attr.ptDivider))
                    cornerRadius = 16 * dp
                }
                setPadding(
                    (20 * dp).toInt(), (14 * dp).toInt(),
                    (20 * dp).toInt(), (12 * dp).toInt(),
                )
                addView(
                    slot,
                    LinearLayout.LayoutParams(slotPx, slotPx).apply {
                        gravity = Gravity.CENTER_HORIZONTAL
                        bottomMargin = (10 * dp).toInt()
                    },
                )
                addView(
                    label,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { gravity = Gravity.CENTER_HORIZONTAL },
                )
            }

            // Same window contract as the ephemeral probe (touchable +
            // center + inset rationale documented there). Touchable for the
            // card's WHOLE life, both variants: pass-through overlays get
            // their composited opacity clamped to ~84% by the
            // untrusted-touch rules, and this card must render fully opaque
            // (see onVerdictSettled).
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                host.windowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.CENTER
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) fitInsetsTypes = 0
            }
            val chip = StartupChip(wm, card, params, controller.projectedDisplayId, pattern, spinner)
            // Anchor BEFORE the window exists — the add's own composition is
            // round-1 freshness evidence (the ephemeral probe's seqAtAdd,
            // transferred faithfully).
            chip.patternAddedSeq = controller.deliverySeqNow
            return try {
                wm.addView(card, params)
                WindowChurnGate.noteWindowAdded()
                chip
            } catch (_: Exception) {
                null
            }
        }
    }
}
