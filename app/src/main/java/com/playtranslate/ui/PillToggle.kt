package com.playtranslate.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
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
 */
fun <T> buildPillToggle(
    container: FrameLayout,
    options: List<Pair<String, T>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    container.removeAllViews()
    val ctx = container.context
    val dp = ctx.resources.displayMetrics.density
    val trackRadius = 10 * dp
    val pillRadius = 8 * dp
    val trackPad = (3 * dp).toInt()
    val pillH = (32 * dp).toInt()

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
            setPadding((14 * dp).toInt(), 0, (14 * dp).toInt(), 0)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            isClickable = true
            isFocusable = true
        }
        pills.add(pill)
        pillRow.addView(pill)
    }

    container.addView(track)

    pillRow.post {
        if (pills.isEmpty()) return@post
        val pillW = pills[0].width
        indicator.layoutParams = FrameLayout.LayoutParams(pillW, pillH)
        indicator.translationX = (pillW * selectedIdx).toFloat()
        indicator.requestLayout()
    }

    pills.forEachIndexed { idx, pill ->
        pill.setOnClickListener {
            if (idx == currentIdx) return@setOnClickListener
            currentIdx = idx

            val pillW = pills[0].width
            indicator.animate()
                .translationX((pillW * idx).toFloat())
                .setDuration(200)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()

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
}
