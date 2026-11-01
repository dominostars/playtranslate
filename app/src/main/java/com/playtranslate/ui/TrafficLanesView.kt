package com.playtranslate.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.AnimationUtils
import androidx.core.graphics.ColorUtils
import com.playtranslate.R
import com.playtranslate.themeColor
import kotlin.math.roundToInt

/**
 * The traffic under the PlayTranslate Cloud page's hero, the cloud logo
 * (activity_pt_cloud_settings.xml): three 1dp lanes in the accent at 22%,
 * with two packets per lane sliding along them in the full accent. Two of
 * these share the row, each from its outer edge to the row's centre, with
 * the cloud centred over them, so the lanes and their packets run on behind
 * it: seen through the PNG's transparent parts, hidden by the cloud itself.
 * Lanes 0 and 2 run toward this view's right edge, the centre, and lane 1
 * away from it; the right one is this view mirrored (scaleX -1), so on
 * screen the outer lanes flow into the cloud from both sides and the middle
 * one out of it.
 *
 * The geometry is the design's 120 x 84dp box, centred vertically, its right
 * end the design's 6dp short of the cloud ([coveredEndPx]); beyond that end
 * the lanes and the traffic run on to the centre. Toward the outer edge a
 * narrower view clips the box's left part and a wider one runs the lanes and
 * their traffic on to its edge (the packets keep their spacing, so none
 * appears mid-lane). Lanes and packets are drawn through one horizontal
 * gradient, transparent at the outer edge and full from 55% of the way to
 * the box's end, so the traffic fades out toward the screen's edge.
 *
 * Motion follows [EdgeRingView]'s rule: a frame per vsync while shown
 * ([onVisibilityAggregated]: attached, visible, window visible) and the
 * system animator scale is not 0; otherwise the still frame at 0.6 s.
 */
class TrafficLanesView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private class Lane(val yDp: Float, val periodMs: Long, val towardCloud: Boolean)

    private val density = resources.displayMetrics.density
    private val accent = context.themeColor(R.attr.ptAccent)

    /** The width at this view's right end from the box's end to the
     *  centre: half the cloud, and the design's gap before it. */
    private val coveredEndPx = resources.getDimension(R.dimen.pt_cloud_hero_width) / 2f + CLOUD_GAP_DP * density

    private val laneHalfPx = LANE_DP / 2f * density
    private val packetWidthPx = PACKET_W_DP * density
    private val packetHalfPx = PACKET_H_DP / 2f * density
    private val packetRadiusPx = PACKET_RADIUS_DP * density
    private val lanePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { alpha = (LANE_ALPHA * 255f).roundToInt() }
    private val packetPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val packet = RectF()

    /** Effective visibility, from [onVisibilityAggregated]. */
    private var shown = false

    /** The clock's zero: the frame time when the view attached. */
    private var attachedAtMs = 0L

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attachedAtMs = AnimationUtils.currentAnimationTimeMillis()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        shown = isVisible
        // Shown again: a draw, which asks for the frames after it.
        if (isVisible) invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // In this view's own coordinates, so the mirrored copy fades toward
        // its outer edge too; full from 55% of the way to the box's end, and
        // on under the cloud. The paints' alphas scale it.
        val fade = LinearGradient(
            0f, 0f, (w - coveredEndPx) * FADE_END, 0f,
            ColorUtils.setAlphaComponent(accent, 0), accent, Shader.TileMode.CLAMP,
        )
        lanePaint.shader = fade
        packetPaint.shader = fade
    }

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0) return
        val moving = shown && ValueAnimator.areAnimatorsEnabled()
        val t = if (moving) drawingTime - attachedAtMs else STILL_MS
        // Packets enter and leave past the view's edges: cut them there.
        canvas.clipRect(0, 0, width, height)
        val w = width.toFloat()
        val boxEnd = w - coveredEndPx
        val boxTop = (height - DESIGN_H_DP * density) / 2f
        // The view's edges in the box's dp: the left one 0 when 120dp lie
        // beside the cloud, negative when more do; the right one under it.
        val leftDp = DESIGN_W_DP - boxEnd / density
        val rightDp = DESIGN_W_DP + coveredEndPx / density
        for (lane in LANES) {
            val y = boxTop + lane.yDp * density
            canvas.drawRect(0f, y - laneHalfPx, w, y + laneHalfPx, lanePaint)
        }
        for ((i, lane) in LANES.withIndex()) {
            val y = boxTop + lane.yDp * density
            val p = (t + i * LANE_STAGGER_MS).mod(lane.periodMs).toFloat() / lane.periodMs
            // The handoff's packet 0 (its left edge, in the box's dp); packet
            // 1 is half a travel from it, and the train goes on that way.
            val lead = -PACKET_W_DP + TRAVEL_DP * (if (lane.towardCloud) p else 1f - p)
            var x = lead
            while (x + PACKET_W_DP > leftDp) x -= SPACING_DP
            x += SPACING_DP
            while (x < rightDp) {
                val left = boxEnd + (x - DESIGN_W_DP) * density
                packet.set(left, y - packetHalfPx, left + packetWidthPx, y + packetHalfPx)
                canvas.drawRoundRect(packet, packetRadiusPx, packetRadiusPx, packetPaint)
                x += SPACING_DP
            }
        }
        if (moving) postInvalidateOnAnimation()
    }

    private companion object {
        /** The design's box, dp. Its right end is the design's gap short of
         *  the cloud. */
        const val DESIGN_W_DP = 120f
        const val DESIGN_H_DP = 84f

        /** The design's gap between the box's end and the cloud's edge. */
        const val CLOUD_GAP_DP = 6f

        const val LANE_DP = 1f
        const val LANE_ALPHA = 0.22f
        const val PACKET_W_DP = 12f
        const val PACKET_H_DP = 3f
        const val PACKET_RADIUS_DP = 1.5f

        /** A period's travel: a packet's left edge from just off the box's
         *  left end (-12dp) to just past its right one (120dp). */
        const val TRAVEL_DP = DESIGN_W_DP + PACKET_W_DP

        /** Two packets a lane, half a period apart: one every half travel. */
        const val SPACING_DP = TRAVEL_DP / 2f

        /** Lane i runs i times this far ahead, so the lanes do not tick in
         *  unison. */
        const val LANE_STAGGER_MS = 400L

        /** Where the fade reaches full strength, as a fraction of the way
         *  from the view's outer edge to the box's end. */
        const val FADE_END = 0.55f

        /** The frame drawn with animations off. */
        const val STILL_MS = 600L

        private val LANES = listOf(
            Lane(yDp = 24f, periodMs = 1_700L, towardCloud = true),
            Lane(yDp = 42f, periodMs = 2_300L, towardCloud = false),
            Lane(yDp = 60f, periodMs = 1_400L, towardCloud = true),
        )
    }
}
