package com.playtranslate.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.playtranslate.R
import com.playtranslate.themeColor

/**
 * Build a sliding-pill segmented control inside [container]. The active pill
 * fills with [R.attr.ptAccent] and animates between segments on tap; inactive
 * labels render in [R.attr.ptTextMuted] over an [R.attr.ptSurface] track.
 *
 * Same visual treatment as the Overlay Mode toggle in Settings — extracted so
 * RegionPickerSheet can reuse it without duplicating the layered FrameLayout
 * + sliding indicator + bold-on-active text logic.
 *
 * Labels shrink from 13sp down to 10sp before they ellipsize, and a control
 * of more than three segments pads them less, so four segments of localized
 * text fit a phone-width row; two- and three-segment controls whose labels
 * fit at 13sp render as they always did. Returns a [PillToggle] for a host
 * that has to freeze the segments; hosts that never do can ignore it.
 */
fun <T> buildPillToggle(
    container: FrameLayout,
    options: List<Pair<String, T>>,
    selected: T,
    onSelect: (T) -> Unit,
): PillToggle {
    container.removeAllViews()
    val ctx = container.context
    val dp = ctx.resources.displayMetrics.density
    val trackRadius = 10 * dp
    val pillRadius = 8 * dp
    val trackPad = (3 * dp).toInt()
    val pillH = (32 * dp).toInt()
    // Four segments on a phone leave each label about 45dp at the usual
    // padding; a narrower inset gives localized labels the room.
    val pillPad = ((if (options.size > 3) 8 else 14) * dp).toInt()

    val surfaceColor = ctx.themeColor(R.attr.ptSurface)
    val accentColor = ctx.themeColor(R.attr.ptAccent)
    val mutedColor = ctx.themeColor(R.attr.ptTextMuted)

    val selectedIdx = options.indexOfFirst { it.second == selected }.coerceAtLeast(0)

    val track = FrameLayout(ctx).apply {
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        )
        background = GradientDrawable().apply {
            setColor(surfaceColor)
            cornerRadius = trackRadius
        }
        setPadding(trackPad, trackPad, trackPad, trackPad)
    }

    val pillRow = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        )
    }

    val indicator = View(ctx).apply {
        background = GradientDrawable().apply {
            setColor(accentColor)
            cornerRadius = pillRadius
        }
        elevation = 2 * dp
    }
    track.addView(indicator)
    pillRow.elevation = 3 * dp
    track.addView(pillRow)

    val pills = mutableListOf<TextView>()
    var currentIdx = selectedIdx

    options.forEachIndexed { idx, (label, _) ->
        val isActive = idx == selectedIdx
        val pill = TextView(ctx).apply {
            text = label
            textSize = 13f
            typeface = Typeface.create(
                "sans-serif-medium",
                if (isActive) Typeface.BOLD else Typeface.NORMAL,
            )
            gravity = Gravity.CENTER
            setTextColor(if (isActive) surfaceColor else mutedColor)
            layoutParams = LinearLayout.LayoutParams(0, pillH, 1f)
            setPadding(pillPad, 0, pillPad, 0)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setAutoSizeTextTypeUniformWithConfiguration(10, 13, 1, TypedValue.COMPLEX_UNIT_SP)
            isClickable = true
            isFocusable = true
        }
        pills.add(pill)
        pillRow.addView(pill)
    }

    container.addView(track)

    val slider = SegmentIndicator(indicator, pillRow, pillH, selectedIdx)

    pills.forEachIndexed { idx, pill ->
        pill.setOnClickListener {
            if (idx == currentIdx) return@setOnClickListener
            currentIdx = idx

            slider.slideTo(idx)

            pills.forEachIndexed { i, p ->
                val active = i == idx
                p.setTextColor(if (active) surfaceColor else mutedColor)
                p.typeface = Typeface.create(
                    "sans-serif-medium",
                    if (active) Typeface.BOLD else Typeface.NORMAL,
                )
            }

            onSelect(options[idx].second)
        }
    }

    return PillToggle(pills)
}

/** The segments of one [buildPillToggle] control. */
class PillToggle internal constructor(private val segments: List<View>) {
    /** A disabled segment takes no taps; the indicator stays where it is. */
    fun setEnabled(enabled: Boolean) = segments.forEach { it.isEnabled = enabled }
}

/**
 * The accent indicator of a sliding segmented control ([buildPillToggle],
 * [buildAnkiModeToggle]): [indicator] sits in the track behind [row], both
 * at the track's start edge, and is kept the size of the selected segment
 * and under it after every layout of [row], so a resize that does not
 * rebuild the control (a rotation in an activity that handles its own
 * config changes) never strands it. Its offset comes from where the segment
 * was laid out, not from its index: under RTL the row puts segment 0 at the
 * right edge, which is also where the indicator rests.
 */
internal class SegmentIndicator(
    private val indicator: View,
    private val row: LinearLayout,
    private val height: Int,
    private var selected: Int,
) {
    /** A tap's slide is running; a layout pass meanwhile must not snap the
     *  indicator to its target mid-animation. */
    private var sliding = false

    init {
        // Zero wide until the first layout sizes it: a full-track accent
        // would flash for a frame otherwise.
        indicator.layoutParams = FrameLayout.LayoutParams(0, height, Gravity.TOP or Gravity.START)
        row.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> if (!sliding) snap() }
    }

    /** Slides the indicator to segment [idx] (a tap). */
    fun slideTo(idx: Int) {
        selected = idx
        val segment = row.getChildAt(idx) ?: return
        sliding = true
        indicator.animate()
            .translationX(offsetOf(segment))
            .setDuration(200)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .withEndAction {
                sliding = false
                snap()
            }
            .start()
    }

    /** Sizes the indicator to the selected segment and puts it under it.
     *  Writes only what changed: a new size relayouts, and this runs on
     *  every layout of [row]. */
    private fun snap() {
        val segment = row.getChildAt(selected) ?: return
        if (segment.width <= 0) return
        if (indicator.layoutParams.width != segment.width) {
            indicator.layoutParams = FrameLayout.LayoutParams(
                segment.width, height, Gravity.TOP or Gravity.START,
            )
        }
        val target = offsetOf(segment)
        if (indicator.translationX != target) indicator.translationX = target
    }

    /** The translationX that puts the indicator's start edge on
     *  [segment]'s. The indicator rests at the row's start edge, so under
     *  RTL it is right-aligned and reaches a segment by moving left. */
    private fun offsetOf(segment: View): Float =
        if (row.layoutDirection == View.LAYOUT_DIRECTION_RTL) (segment.right - row.width).toFloat()
        else segment.left.toFloat()
}
