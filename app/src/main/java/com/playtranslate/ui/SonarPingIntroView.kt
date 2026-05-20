package com.playtranslate.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.view.animation.Interpolator
import android.view.animation.PathInterpolator
import com.playtranslate.R

/**
 * One-shot "sonar ping" intro animation that draws the user's eye to the
 * floating overlay icon the first time it appears on screen per capture
 * session. See `design_handoff_sonar_ping/` for the full spec.
 *
 * Plays in a dedicated overlay window installed alongside (and on top of)
 * the real [FloatingOverlayIcon]. While the intro runs, the underlying icon
 * is held at `alpha = 0` so the carrier here is the only thing the user
 * sees; the final pose of the animation matches the icon's docked
 * `compactMode` rendering exactly, so the handoff frame has no seam.
 *
 * Timeline (all times in ms from animation start; total 2560 ms):
 *
 *  | Phase            | Window           | Effect                              |
 *  | ---------------- | ---------------- | ----------------------------------- |
 *  | Ring 1           | 20  – 900        | White ring expands 1.28× → 3.6×, opacity 0.9 → 0, border 2.5 → 0.5 dp |
 *  | Ring 2           | 180 – 1140       | Second ring 1.28× → 3.8×, opacity 0.7 → 0           |
 *  | Spring pop-in    | 0   – 720        | Carrier scales 0.40 → 1.40 → 1.20 → 1.286 with opacity 0 → 1, sitting 80 dp inboard of the dock edge |
 *  | Hold             | 720 – 2000       | Carrier dwells at 1.286× so the user reads the icon |
 *  | Slide / bounce   | 2000 – 2560      | Carrier slides to the dock edge with an overshoot squash + rebound; crossfades from app-icon art to the compact nub between 2160 – 2320 |
 *  | Settled          | 2560+            | Same pose as [FloatingOverlayIcon]'s compact dock   |
 *
 *  Sonar rings are listed first because they now fire concurrently with the
 *  carrier's fade-in / spring (rather than after, as the original spec had
 *  them) — the rings emanate from where the icon is arriving.
 *
 * The view is symmetric for [FloatingOverlayIcon.Edge.LEFT] and
 * [Edge.RIGHT] — the same keyframes are mirrored horizontally for LEFT.
 */
class SonarPingIntroView(
    context: Context,
    private val edge: FloatingOverlayIcon.Edge,
) : View(context) {

    /** Optional completion callback. Fired once on the main thread when the
     *  full intro finishes (after the carrier settles at the dock pose). The
     *  caller is responsible for removing this view's overlay window and
     *  restoring the underlying [FloatingOverlayIcon] to `alpha = 1`. */
    var onAnimationEnd: (() -> Unit)? = null

    private val density = resources.displayMetrics.density
    /** Radius of the floating icon's carrier circle (matches
     *  [FloatingOverlayIcon.circleSizePx] / 2 = 28dp at scale 1). */
    private val circleRadiusPx = 28f * density

    /** Anchor centre in view-local coords. The anchor sits 28 dp inboard of
     *  the dock edge — for RIGHT, that's (width - 28dp); for LEFT, 28 dp.
     *  Resolved lazily in onDraw because width/height aren't known until
     *  the view is laid out. */
    private val anchorInsetPx: Float get() = circleRadiusPx
    private val anchorCenterY: Float get() = height / 2f

    // ── Paints ───────────────────────────────────────────────────────────

    private val appIconBitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val launcherBitmap: Bitmap = BitmapFactory.decodeResource(
        resources, R.mipmap.ic_launcher_img
    )
    /** Circle clip used while drawing the launcher art so the oversized
     *  bitmap (1.5× — see [APP_ICON_FILL_RATIO]) gets cropped to a perfect
     *  circle. Mirrors OverlayAlert's outline-clipped frame approach. */
    private val appIconClip = Path()

    /** Nub fill — matches [FloatingOverlayIcon]'s `defaultCircleColor`. */
    private val nubFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC000000")
    }
    /** Nub border — matches [FloatingOverlayIcon]'s borderPaint (1.5 dp
     *  stroke, soft grey at 40% alpha). */
    private val nubBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#66888888")
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    /** Nub arrow fill — solid white, matches [FloatingOverlayIcon]. */
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
    }
    private val arrowPath = Path()

    /** Sonar ring stroke. Per-ring opacity + width re-applied each frame. */
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
    }

    // ── Animation state ──────────────────────────────────────────────────

    /** Decel curve for the ring expand+fade phase (spec: cubic-bezier
     *  (0.2, 0.6, 0.2, 1)). */
    private val ringEasing = PathInterpolator(0.2f, 0.6f, 0.2f, 1f)

    private var progress = 0f
    private var animator: ValueAnimator? = null

    /** Kick off the animation. Idempotent — calling again restarts from t=0. */
    fun start() {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = TOTAL_DURATION_MS
            // Linear at the animator level; the keyframe tables encode the
            // easing per phase.
            interpolator = null
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    onAnimationEnd?.invoke()
                }
            })
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    // ── Drawing ──────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        val timeMs = progress * TOTAL_DURATION_MS
        val mirror = edge == FloatingOverlayIcon.Edge.LEFT
        val anchorX = if (mirror) anchorInsetPx else (width - anchorInsetPx)
        val anchorY = anchorCenterY
        val sign = if (mirror) -1f else 1f

        // Rings are anchored at the carrier's ENTRY position
        // (translate(-80dp, 0) from the anchor) and stay put as the carrier
        // moves on to dock. They're already faded out by the time the
        // travel phase starts so they never "follow".
        val ringCenterX = anchorX + sign * (-ENTRY_OFFSET_DP * density)
        drawRing(canvas, ringCenterX, anchorY, timeMs, ring2 = false)
        drawRing(canvas, ringCenterX, anchorY, timeMs, ring2 = true)

        // Carrier.
        val carrier = carrierAt(timeMs)
        val carrierX = anchorX + sign * carrier.translateXDp * density
        val carrierOpacity = carrierOpacityAt(timeMs)
        if (carrierOpacity <= 0f) return

        canvas.save()
        canvas.translate(carrierX, anchorY)
        canvas.scale(carrier.scaleX, carrier.scaleY)

        // The renderer cross-fades the app-icon art into the compact nub
        // mid-travel — both compositions are drawn centred at (0, 0) in the
        // scaled local space.
        val appAlpha = appIconOpacityAt(timeMs) * carrierOpacity
        val nubAlpha = nubOpacityAt(timeMs) * carrierOpacity
        if (appAlpha > 0f) drawAppIcon(canvas, appAlpha)
        if (nubAlpha > 0f) drawNub(canvas, nubAlpha, mirror)

        canvas.restore()
    }

    /** Draws the launcher art centred at (0, 0), clipped to the carrier
     *  circle. Matches OverlayAlert's app-icon presentation: the launcher
     *  bitmap is drawn at [APP_ICON_FILL_RATIO] × the circle diameter
     *  (oversize, to push the adaptive-icon padding past the circle edge)
     *  and the clip crops it to a perfect circle. The launcher's own
     *  background colour fills the circle — no separate paint needed. */
    private fun drawAppIcon(canvas: Canvas, opacity: Float) {
        val alpha = (opacity * 255f).toInt().coerceIn(0, 255)
        appIconBitmapPaint.alpha = alpha

        appIconClip.reset()
        appIconClip.addCircle(0f, 0f, circleRadiusPx, Path.Direction.CW)
        val saved = canvas.save()
        canvas.clipPath(appIconClip)

        val side = APP_ICON_FILL_RATIO * 2f * circleRadiusPx
        val half = side / 2f
        val rect = RectF(-half, -half, half, half)
        canvas.drawBitmap(launcherBitmap, null, rect, appIconBitmapPaint)

        canvas.restoreToCount(saved)
    }

    /** Draws the compact nub (1/4-visible black circle with a small arrow)
     *  centred at (0, 0). Mirrors [FloatingOverlayIcon]'s compact rendering
     *  but here the carrier-translation has already positioned the circle
     *  so it spills off the screen edge; the arrow is offset towards the
     *  visible slice. */
    private fun drawNub(canvas: Canvas, opacity: Float, mirror: Boolean) {
        val alpha = (opacity * 255f).toInt().coerceIn(0, 255)
        nubFillPaint.alpha = alpha
        nubBorderPaint.alpha = (alpha * (102f / 255f)).toInt().coerceIn(0, 255)
        canvas.drawCircle(0f, 0f, circleRadiusPx, nubFillPaint)
        canvas.drawCircle(0f, 0f, circleRadiusPx, nubBorderPaint)

        // Arrow sits towards the visible slice — for RIGHT-edge dock that
        // means LEFT of the circle's centre; for LEFT-edge dock the mirror.
        val arrowOffset = ARROW_NUDGE_RATIO * circleRadiusPx
        val arrowCx = if (mirror) arrowOffset else -arrowOffset
        val arrowSize = circleRadiusPx * ARROW_SIZE_RATIO
        arrowPaint.alpha = alpha
        drawEdgeArrow(canvas, arrowCx, 0f, arrowSize, mirror)
    }

    /** Triangle arrow pointing toward the screen interior — mirrors
     *  [FloatingOverlayIcon.drawEdgeArrow]. */
    private fun drawEdgeArrow(canvas: Canvas, cx: Float, cy: Float, size: Float, mirror: Boolean) {
        val hw = size * 0.5f
        val hh = size * 0.7f
        arrowPath.reset()
        if (mirror) {
            // LEFT edge → arrow points RIGHT.
            arrowPath.moveTo(cx + hw, cy)
            arrowPath.lineTo(cx - hw * 0.3f, cy - hh)
            arrowPath.lineTo(cx - hw * 0.3f, cy + hh)
        } else {
            // RIGHT edge → arrow points LEFT.
            arrowPath.moveTo(cx - hw, cy)
            arrowPath.lineTo(cx + hw * 0.3f, cy - hh)
            arrowPath.lineTo(cx + hw * 0.3f, cy + hh)
        }
        arrowPath.close()
        canvas.drawPath(arrowPath, arrowPaint)
    }

    /** Draws one of the two sonar rings. The two differ only in start
     *  delay, peak opacity, and end scale — collapsed here to a couple of
     *  conditionals. */
    private fun drawRing(
        canvas: Canvas,
        cx: Float, cy: Float,
        timeMs: Float,
        ring2: Boolean,
    ) {
        val startMs = if (ring2) RING2_START_MS else RING1_START_MS
        val peakMs = if (ring2) RING2_PEAK_MS else RING1_PEAK_MS
        val endMs = if (ring2) RING2_END_MS else RING1_END_MS
        val peakOpacity = if (ring2) RING2_PEAK_OPACITY else RING1_PEAK_OPACITY
        val endScale = if (ring2) RING2_END_SCALE else RING1_END_SCALE

        if (timeMs < startMs || timeMs > endMs) return

        val scale: Float
        val opacity: Float
        val borderDp: Float
        if (timeMs < peakMs) {
            // Fade-in phase: scale 0.6 → 1.28, opacity 0 → peak. Linear.
            val k = (timeMs - startMs) / (peakMs - startMs)
            scale = lerp(RING_START_SCALE, RING_PEAK_SCALE, k)
            opacity = lerp(0f, peakOpacity, k)
            borderDp = RING_START_BORDER_DP
        } else {
            // Expand + fade phase: peak scale → end scale, opacity peak → 0,
            // border 2.5 → 0.5 dp. Easing applied here (the demo's cubic-
            // bezier(0.2, 0.6, 0.2, 1)).
            val rawK = (timeMs - peakMs) / (endMs - peakMs)
            val k = ringEasing.getInterpolation(rawK.coerceIn(0f, 1f))
            scale = lerp(RING_PEAK_SCALE, endScale, k)
            opacity = lerp(peakOpacity, 0f, k)
            borderDp = lerp(RING_START_BORDER_DP, RING_END_BORDER_DP, k)
        }

        if (opacity <= 0f) return
        ringPaint.alpha = (opacity * 255f * RING_BASE_ALPHA).toInt().coerceIn(0, 255)
        ringPaint.strokeWidth = borderDp * density
        canvas.drawCircle(cx, cy, circleRadiusPx * scale, ringPaint)
    }

    // ── Carrier keyframes ────────────────────────────────────────────────
    //
    // translateXDp values are SIGNED: positive = away-from-anchor (i.e.
    // toward the dock), negative = inboard. The LEFT-edge variant mirrors
    // this by multiplying by -1 in onDraw (the `sign` variable).

    private data class CarrierState(
        val translateXDp: Float, val scaleX: Float, val scaleY: Float,
    )

    private fun carrierAt(timeMs: Float): CarrierState {
        val frames = CARRIER_KEYFRAMES
        // Before first keyframe — pin to the first.
        if (timeMs <= frames[0].timeMs) {
            val f = frames[0]
            return CarrierState(f.translateXDp, f.scaleX, f.scaleY)
        }
        // After last — pin to the last (the docked pose).
        if (timeMs >= frames.last().timeMs) {
            val f = frames.last()
            return CarrierState(f.translateXDp, f.scaleX, f.scaleY)
        }
        for (i in 1 until frames.size) {
            val next = frames[i]
            if (timeMs <= next.timeMs) {
                val prev = frames[i - 1]
                val k = (timeMs - prev.timeMs) / (next.timeMs - prev.timeMs)
                // Per-segment interpolator lets the travel segment ease in
                // independently of the bounce/squash/rebound, which already
                // encode their dynamics in their keyframe positions.
                val easedK = next.easeIn?.getInterpolation(k.coerceIn(0f, 1f)) ?: k
                return CarrierState(
                    lerp(prev.translateXDp, next.translateXDp, easedK),
                    lerp(prev.scaleX, next.scaleX, easedK),
                    lerp(prev.scaleY, next.scaleY, easedK),
                )
            }
        }
        val f = frames.last()
        return CarrierState(f.translateXDp, f.scaleX, f.scaleY)
    }

    private fun carrierOpacityAt(timeMs: Float): Float =
        if (timeMs <= CARRIER_FADE_IN_END_MS) (timeMs / CARRIER_FADE_IN_END_MS) else 1f

    /** App-icon art is fully opaque until the crossfade window, then fades
     *  to 0 over [CROSSFADE_DURATION_MS]. */
    private fun appIconOpacityAt(timeMs: Float): Float = when {
        timeMs <= CROSSFADE_START_MS -> 1f
        timeMs >= CROSSFADE_END_MS -> 0f
        else -> 1f - (timeMs - CROSSFADE_START_MS) / CROSSFADE_DURATION_MS
    }

    /** Nub is hidden until the crossfade window, then fades in. */
    private fun nubOpacityAt(timeMs: Float): Float = when {
        timeMs <= CROSSFADE_START_MS -> 0f
        timeMs >= CROSSFADE_END_MS -> 1f
        else -> (timeMs - CROSSFADE_START_MS) / CROSSFADE_DURATION_MS
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    private data class CarrierKeyframe(
        val timeMs: Float,
        val translateXDp: Float,
        val scaleX: Float,
        val scaleY: Float,
        /** Easing applied to the segment ENDING at this keyframe. null →
         *  linear, which is the default the demo HTML uses for everything
         *  except the explicit ease-in travel into the wall. */
        val easeIn: Interpolator? = null,
    )

    companion object {
        /** Full intro duration. */
        const val TOTAL_DURATION_MS = 2560L

        /** Entry offset — carrier pops in 80 dp inboard of the dock edge,
         *  per the spec. */
        private const val ENTRY_OFFSET_DP = 80f
        /** Carrier's scale at the entry settle (= ~72 dp diameter from a
         *  56 dp circle). */
        private const val ENTRY_SETTLE_SCALE = 1.286f
        /** Dock translate (75% of the carrier width past the anchor); 75%
         *  of 56 dp = 42 dp. */
        private const val DOCK_TRANSLATE_DP = 0.75f * 56f
        /** Oversize ratio for the launcher bitmap inside the carrier circle.
         *  Matches OverlayAlert's `circleSize * 1.5` factor — the launcher
         *  PNG has adaptive-icon padding around its foreground content, so
         *  we have to scale up past the circle to make the visible art
         *  fill it. The clip then crops back to the circle. */
        private const val APP_ICON_FILL_RATIO = 1.5f

        /** Arrow horizontal offset from the carrier centre — matches the
         *  `arrowNudge` factor used by [FloatingOverlayIcon] (r * 0.65). */
        private const val ARROW_NUDGE_RATIO = 0.65f
        /** Arrow size as a ratio of the carrier radius — matches
         *  [FloatingOverlayIcon]'s 0.22. */
        private const val ARROW_SIZE_RATIO = 0.22f

        /** Crossfade between the launcher art and the compact nub. */
        private const val CROSSFADE_START_MS = 2160f
        private const val CROSSFADE_END_MS = 2320f
        private const val CROSSFADE_DURATION_MS = CROSSFADE_END_MS - CROSSFADE_START_MS

        /** Carrier opacity fade-in completes at 240 ms (in step with the
         *  spring's first overshoot). */
        private const val CARRIER_FADE_IN_END_MS = 240f

        // Sonar timing — shifted 300ms earlier than the original design
        // HTML (Ring 1 was 320–1200, Ring 2 was 480–1440). The rings now
        // start while the carrier is still fading in / springing, so they
        // emanate from where the icon is *arriving* rather than after it
        // has settled. Inter-ring offset (160 ms) and individual phase
        // durations (80 ms fade-in, 800/880 ms expand-and-fade) are
        // unchanged from the spec.

        /** Ring 1 timing & end scale. */
        private const val RING1_START_MS = 20f
        private const val RING1_PEAK_MS = 100f
        private const val RING1_END_MS = 900f
        private const val RING1_PEAK_OPACITY = 0.9f
        private const val RING1_END_SCALE = 3.6f

        /** Ring 2 timing & end scale — slightly larger & dimmer. */
        private const val RING2_START_MS = 180f
        private const val RING2_PEAK_MS = 260f
        private const val RING2_END_MS = 1140f
        private const val RING2_PEAK_OPACITY = 0.7f
        private const val RING2_END_SCALE = 3.8f

        /** Shared ring geometry. */
        private const val RING_START_SCALE = 0.6f
        private const val RING_PEAK_SCALE = 1.28f
        private const val RING_START_BORDER_DP = 2.5f
        private const val RING_END_BORDER_DP = 0.5f
        /** Multiplier so the per-ring opacity peaks at 0.85 × 1 (matches
         *  the spec's `rgba(255, 255, 255, 0.85)` stroke colour). */
        private const val RING_BASE_ALPHA = 0.85f

        /** Material-standard ease-in curve (cubic-bezier(0.4, 0, 1, 1)) —
         *  starts slow, accelerates into the end. Applied to the travel
         *  segment so the icon eases off its hover and accelerates into
         *  the wall before the squash. */
        private val EASE_IN: Interpolator = PathInterpolator(0.4f, 0f, 1f, 1f)

        /** Carrier keyframes, exact values from the design HTML. */
        private val CARRIER_KEYFRAMES = listOf(
            // Spring pop-in
            CarrierKeyframe(0f,    -ENTRY_OFFSET_DP, 0.40f, 0.40f),
            CarrierKeyframe(240f,  -ENTRY_OFFSET_DP, 1.40f, 1.40f),
            CarrierKeyframe(480f,  -ENTRY_OFFSET_DP, 1.20f, 1.20f),
            CarrierKeyframe(720f,  -ENTRY_OFFSET_DP, ENTRY_SETTLE_SCALE, ENTRY_SETTLE_SCALE),
            // Hold
            CarrierKeyframe(2000f, -ENTRY_OFFSET_DP, ENTRY_SETTLE_SCALE, ENTRY_SETTLE_SCALE),
            // Travel — eases in from the hover so the icon starts the slide
            // slowly and picks up speed before slamming into the wall.
            CarrierKeyframe(2240f,  0.40f * 56f, 1.05f, 1.05f, easeIn = EASE_IN),
            // Squash into the wall (horizontal squash, vertical stretch).
            CarrierKeyframe(2400f,  0.88f * 56f, 0.86f, 1.12f),
            // Rebound (slight overshoot the other way).
            CarrierKeyframe(2500f,  0.70f * 56f, 1.06f, 0.94f),
            // Settled at the dock pose.
            CarrierKeyframe(2560f,  DOCK_TRANSLATE_DP, 1.0f, 1.0f),
        )
    }
}
