package com.playtranslate.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
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
 * Timeline (all times in ms from animation start; total 3560 ms):
 *
 *  | Phase             | Window (ms)      | Effect                              |
 *  | ----------------- | ---------------- | ----------------------------------- |
 *  | Sonar rings ×4    | 0    – 3000      | Four evenly-placed white rings, 750ms apart. Each ramps in (1.28× scale, full opacity, 15dp border), then expands + fades to 3.7× / opacity 0 / 0.5dp border over 670ms. |
 *  | Spring pop-in     | 0    – 720       | Carrier scales 0.40 → 1.40 → 1.20 → 1.286 with opacity 0 → 1, sitting 80dp inboard of the dock edge |
 *  | Undulation 1      | 720  – 1860      | Carrier eases 1.286 → 1.40 → 1.286 (sine-like via paired ease-out + ease-in) |
 *  | Undulation 2      | 1860 – 3000      | Same shape — ends EXACTLY at 3000 ms so the next frame is the start of travel, no still hold between |
 *  | Slide / bounce    | 3000 – 3560      | Carrier eases into the wall, squashes, rebounds, and settles; crossfades from app-icon art to the compact nub between 3160 – 3320 |
 *  | Settled           | 3560+            | Same pose as [FloatingOverlayIcon]'s compact dock   |
 *
 *  Touches: the intro window is touchable and absorbs every gesture in its
 *  footprint without acting on it (see [onTouchEvent]). The underlying
 *  [FloatingOverlayIcon] sits below in z-order, so any tap on the visible
 *  carrier is consumed here and the icon's click handlers never fire while
 *  the intro is playing.
 *
 * The view is symmetric for [FloatingOverlayIcon.Edge.LEFT] and
 * [Edge.RIGHT] — the same keyframes are mirrored horizontally for LEFT.
 */
class SonarPingIntroView(
    context: Context,
    /** Screen edge the carrier docks against. Read by
     *  [com.playtranslate.OverlayUiController] when the display rotates so
     *  the intro window's x/y can be recomputed against the new screen
     *  dimensions without rebuilding the view. */
    val edge: FloatingOverlayIcon.Edge,
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
    /** Sonar ring border — drawn UNDERNEATH the white stroke at the same
     *  radius with [RING_BORDER_PIPE_DP] of extra width on each side, so
     *  it shows as a thin dark piping on both the inside and outside of
     *  the white ring. Same alpha curve as the white ring. */
    private val ringBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#555555")
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

    /** Absorbs every touch in the intro window's bounds without acting on
     *  it. The intro window is layered above the (alpha-0) FloatingOverlayIcon
     *  in z-order, so taps on the visible carrier (or anywhere else in this
     *  window's footprint) get consumed here and don't reach the icon's
     *  click handlers — the icon is "touchable but inert" for the duration
     *  of the intro. */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = true

    // ── Drawing ──────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        val timeMs = progress * TOTAL_DURATION_MS
        val mirror = edge == FloatingOverlayIcon.Edge.LEFT
        val anchorX = if (mirror) anchorInsetPx else (width - anchorInsetPx)
        val anchorY = anchorCenterY
        val sign = if (mirror) -1f else 1f

        // Rings are anchored at the carrier's ENTRY position
        // (translate(-80dp, 0) from the anchor) and stay put as the carrier
        // moves on to dock. They're all faded out by the time the travel
        // phase starts so they never "follow".
        val ringCenterX = anchorX + sign * (-ENTRY_OFFSET_DP * density)
        for (spec in RING_SPECS) {
            drawRing(canvas, ringCenterX, anchorY, timeMs, spec)
        }

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

    /** Draws one sonar ring per its [spec]. Quiet outside the spec's
     *  start–end window; in-window, ramps in (linear, 0 → peak opacity,
     *  0.6× → 1.28× scale, 2.5dp border) then expands and fades out on
     *  the decel bezier (cubic-bezier(0.2, 0.6, 0.2, 1)) to the spec's
     *  end scale, opacity 0, border 0.5dp. */
    private fun drawRing(
        canvas: Canvas,
        cx: Float, cy: Float,
        timeMs: Float,
        spec: RingSpec,
    ) {
        if (timeMs < spec.startMs || timeMs > spec.endMs) return

        val scale: Float
        val opacity: Float
        val borderDp: Float
        if (timeMs < spec.peakMs) {
            val k = (timeMs - spec.startMs) / (spec.peakMs - spec.startMs)
            scale = lerp(RING_START_SCALE, RING_PEAK_SCALE, k)
            opacity = lerp(0f, spec.peakOpacity, k)
            borderDp = RING_START_BORDER_DP
        } else {
            val rawK = (timeMs - spec.peakMs) / (spec.endMs - spec.peakMs)
            val k = ringEasing.getInterpolation(rawK.coerceIn(0f, 1f))
            scale = lerp(RING_PEAK_SCALE, spec.endScale, k)
            opacity = lerp(spec.peakOpacity, 0f, k)
            borderDp = lerp(RING_START_BORDER_DP, RING_END_BORDER_DP, k)
        }

        if (opacity <= 0f) return
        val alpha = (opacity * 255f).toInt().coerceIn(0, 255)
        val radius = circleRadiusPx * scale
        // Border first — wider stroke, drawn at the same radius, so 1dp of
        // dark grey peeks out on each side of the white ring once the
        // narrower white stroke overdraws the middle.
        ringBorderPaint.alpha = alpha
        ringBorderPaint.strokeWidth = (borderDp + 2f * RING_BORDER_PIPE_DP) * density
        canvas.drawCircle(cx, cy, radius, ringBorderPaint)
        // White ring on top.
        ringPaint.alpha = alpha
        ringPaint.strokeWidth = borderDp * density
        canvas.drawCircle(cx, cy, radius, ringPaint)
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

    /** One sonar ring's lifecycle. [startMs]/[peakMs]/[endMs] in animation-
     *  start ms; [peakOpacity] = max alpha at the ping moment (used
     *  directly as the stroke alpha, no further scaling); [endScale] = max
     *  scale relative to the carrier radius. */
    private data class RingSpec(
        val startMs: Float,
        val peakMs: Float,
        val endMs: Float,
        val peakOpacity: Float,
        val endScale: Float,
    )

    companion object {
        /** Full intro duration. Sized so the sonar phase (0–3000 ms) and
         *  the carrier's travel-to-dock (3000–3560 ms) don't overlap — the
         *  icon waits for the last ring to finish before moving home. */
        const val TOTAL_DURATION_MS = 3560L

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

        /** Crossfade between the launcher art and the compact nub —
         *  positioned inside the travel phase (3000–3560 ms) at the same
         *  relative offset the original spec used (160 ms into travel,
         *  ending 320 ms in), so the swap happens mid-bounce. */
        private const val CROSSFADE_START_MS = 3160f
        private const val CROSSFADE_END_MS = 3320f
        private const val CROSSFADE_DURATION_MS = CROSSFADE_END_MS - CROSSFADE_START_MS

        /** Carrier opacity fade-in completes at 240 ms (in step with the
         *  spring's first overshoot). */
        private const val CARRIER_FADE_IN_END_MS = 240f

        // Sonar timing — four evenly-placed rings spanning a 3-second
        // window (0–3000ms). 750ms spacing between starts; each ring's
        // lifetime is also 750ms so they tile without dead air. Each ring
        // ramps in for 80ms (fade-in to peak), then expands + fades over
        // 670ms (compressed from the original 800ms so the lifetime fits
        // the spacing). Peak opacity is fully opaque (1.0) so the ring
        // reads as a hard line at the moment of the ping, then fades to 0
        // as it expands.
        private val RING_SPECS: List<RingSpec> = run {
            val spacing = 750f
            val lifetime = 750f
            val fadeInMs = 80f
            val opacity = 1.0f
            val endScale = 3.7f
            List(4) { i ->
                val start = i * spacing
                RingSpec(
                    startMs = start,
                    peakMs = start + fadeInMs,
                    endMs = start + lifetime,
                    peakOpacity = opacity,
                    endScale = endScale,
                )
            }
        }

        /** Shared ring geometry. Start border is 15dp — at the smallest
         *  scale the stroke is wider than the ring's inner hole, so the
         *  ping reads as a nearly-solid disc that then opens up into an
         *  expanding ring as it scales and the stroke tapers to 0.5dp. */
        private const val RING_START_SCALE = 0.6f
        private const val RING_PEAK_SCALE = 1.28f
        private const val RING_START_BORDER_DP = 15f
        private const val RING_END_BORDER_DP = 0.5f
        /** Dark-grey piping on each side of the white ring stroke. The
         *  border stroke uses [borderDp] + 2 × this so 1dp of grey peeks
         *  out at both the inner and outer edge of the white. */
        private const val RING_BORDER_PIPE_DP = 1f

        /** Material-standard ease-in curve (cubic-bezier(0.4, 0, 1, 1)) —
         *  starts slow, accelerates into the end. Applied to the travel
         *  segment so the icon eases off its hover and accelerates into
         *  the wall before the squash. */
        private val EASE_IN: Interpolator = PathInterpolator(0.4f, 0f, 1f, 1f)
        /** Material-standard ease-out curve (cubic-bezier(0, 0, 0.2, 1)) —
         *  starts fast, decelerates as it approaches the end. Paired with
         *  EASE_IN on the undulation's down-segment to give the up-down
         *  arc a sine-like silhouette without extra keyframes. */
        private val EASE_OUT: Interpolator = PathInterpolator(0f, 0f, 0.2f, 1f)

        /** Peak scale of the slow undulation that runs between the spring
         *  pop-in and the travel-to-dock. Matches the spring's overshoot
         *  peak (1.40) for a consistent "swell" height, but reached over
         *  ~740ms rather than the spring's ~240ms. */
        private const val UNDULATION_PEAK_SCALE = 1.40f

        /** Carrier keyframes:
         *
         *    0 –  720 ms  spring pop-in (spec'd 4-keyframe overshoot)
         *  720 – 1860 ms  undulation 1 (1.286 → 1.40 → 1.286, ease-out
         *                 then ease-in, ~sine half-cycle each way)
         * 1860 – 3000 ms  undulation 2 (same shape)
         * 3000 – 3560 ms  travel + squash + rebound + settle. The travel
         *                 segment eases in from the second undulation's
         *                 return, so the icon starts the slide slowly
         *                 right as the last sonar finishes.
         *
         * The two undulations END precisely at 3000 ms — there's no still
         * hold between them and the travel; the second cycle's return-to-
         * rest is the same frame the travel starts moving.
         */
        private val CARRIER_KEYFRAMES = listOf(
            // Spring pop-in
            CarrierKeyframe(0f,    -ENTRY_OFFSET_DP, 0.40f, 0.40f),
            CarrierKeyframe(240f,  -ENTRY_OFFSET_DP, 1.40f, 1.40f),
            CarrierKeyframe(480f,  -ENTRY_OFFSET_DP, 1.20f, 1.20f),
            CarrierKeyframe(720f,  -ENTRY_OFFSET_DP, ENTRY_SETTLE_SCALE, ENTRY_SETTLE_SCALE),
            // Undulation 1 — up (ease-out) to peak, then down (ease-in).
            CarrierKeyframe(1290f, -ENTRY_OFFSET_DP, UNDULATION_PEAK_SCALE, UNDULATION_PEAK_SCALE, easeIn = EASE_OUT),
            CarrierKeyframe(1860f, -ENTRY_OFFSET_DP, ENTRY_SETTLE_SCALE, ENTRY_SETTLE_SCALE, easeIn = EASE_IN),
            // Undulation 2 — same shape; the return frame at 3000 ms is the
            // same frame travel starts, so the icon flows straight from
            // breathing into sliding without a still beat between.
            CarrierKeyframe(2430f, -ENTRY_OFFSET_DP, UNDULATION_PEAK_SCALE, UNDULATION_PEAK_SCALE, easeIn = EASE_OUT),
            CarrierKeyframe(3000f, -ENTRY_OFFSET_DP, ENTRY_SETTLE_SCALE, ENTRY_SETTLE_SCALE, easeIn = EASE_IN),
            // Travel — eases in so the icon picks up speed before slamming
            // into the wall.
            CarrierKeyframe(3240f,  0.40f * 56f, 1.05f, 1.05f, easeIn = EASE_IN),
            // Squash into the wall (horizontal squash, vertical stretch).
            CarrierKeyframe(3400f,  0.88f * 56f, 0.86f, 1.12f),
            // Rebound (slight overshoot the other way).
            CarrierKeyframe(3500f,  0.70f * 56f, 1.06f, 0.94f),
            // Settled at the dock pose.
            CarrierKeyframe(3560f,  DOCK_TRANSLATE_DP, 1.0f, 1.0f),
        )
    }
}
