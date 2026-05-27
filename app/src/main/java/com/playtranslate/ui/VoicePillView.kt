package com.playtranslate.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.playtranslate.R
import com.playtranslate.language.SourceLangId
import com.playtranslate.themeColor

/**
 * The voice pill that sits to the right of each audio preview chip on
 * the Anki review sheet. Mirrors [AnkiAudioPreviewChip]'s 44dp tap-cell
 * wrapping a smaller visible element — but pill-shaped (rounded
 * rectangle) instead of a circle, with a "Default"/"Voice N" label.
 *
 * Static styling: the pill is never tied to the include-switch state —
 * its stroke and text both use [R.attr.ptOutline], matching the cell
 * title and the chip's "switch off" colour so all three blend into the
 * surface uniformly.
 *
 * Background is a programmatic GradientDrawable so the stroke colour
 * can resolve a theme attr cleanly — same pattern as the chip's
 * `circleBg`.
 */
class VoicePillView(host: Fragment, @Suppress("unused") lang: SourceLangId) {
    private val ctx = host.requireContext()
    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private val activeColor = ctx.themeColor(R.attr.ptAccent)
    private val mutedColor = ctx.themeColor(R.attr.ptOutline)
    private val strokeWidthPx = dp(1).coerceAtLeast(1)

    private val bg = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        // Large radius degenerates to pill regardless of width.
        cornerRadius = 999f
        setColor(Color.TRANSPARENT)
        setStroke(strokeWidthPx, mutedColor)
    }

    private val label = TextView(ctx).apply {
        textSize = 12f
        setTextColor(mutedColor)
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        gravity = Gravity.CENTER
        setPadding(dp(10), 0, dp(10), 0)
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            dp(30),
            Gravity.START or Gravity.CENTER_VERTICAL,
        )
        background = bg
    }

    /** Tap cell — 44dp tall, wraps the smaller pill. Matches the
     *  AnkiAudioPreviewChip layout so the two share vertical centring
     *  and the row stays 44dp. Width is wrap_content; the pill grows
     *  with the label text. End margin gives the title TextView some
     *  breathing room — without it the title sits flush against the
     *  pill's right edge. */
    val view: FrameLayout = FrameLayout(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            dp(44),
        ).also { it.marginEnd = dp(12) }
        isClickable = true
        val ripple = TypedValue().also {
            ctx.theme.resolveAttribute(
                android.R.attr.selectableItemBackgroundBorderless, it, true,
            )
        }.resourceId
        setBackgroundResource(ripple)
        addView(label)
    }

    /** Mirror the include-switch state on the pill: accent stroke +
     *  text when on, muted when off — same colour rhythm as
     *  [AnkiAudioPreviewChip]'s ring + icon. */
    fun setActive(active: Boolean) {
        val color = if (active) activeColor else mutedColor
        bg.setStroke(strokeWidthPx, color)
        label.setTextColor(color)
    }

    /** Update the pill text. Call this from the host whenever the
     *  cell's voice changes (initial render + after a voice pick). */
    fun setLabel(text: String) {
        label.text = text
    }

    /** Wire the pill's tap → the host's launchVoicePicker(...). */
    fun setOnTap(onTap: () -> Unit) {
        view.setOnClickListener { onTap() }
    }
}
