package com.playtranslate.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.playtranslate.R
import com.playtranslate.model.FrequencyTag
import com.playtranslate.themeColor

/**
 * Builders for the badge/meta-row chips shared by [WordDefinitionsView]
 * (the lens + result-cell meta row) and [WordDetailBottomSheet]'s header.
 * The two surfaces deliberately keep distinct looks — sizes, paddings, and
 * star treatment differ by design — so each builder takes the surface's
 * style values rather than imposing one; what lives here is the chip
 * structure, so a chip added or changed in one surface can't silently
 * drift from the other. The Anki deck pill has its own shared builder in
 * [AnkiDeckBadge].
 */
object BadgeChips {

    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    /**
     * The ink candidates [freqChipColors] picks between for a label on an
     * accent fill. Read as raw colours, NOT through the theme: which one
     * wins is a property of the accent swatch, so a light accent takes the
     * near-black ink in light theme too (see [OnAccentInk]).
     */
    fun onAccentInk(ctx: Context): OnAccentInk = OnAccentInk(
        dark = ContextCompat.getColor(ctx, R.color.pt_dark_text_on_accent),
        light = ContextCompat.getColor(ctx, R.color.pt_light_text_on_accent),
    )

    /** The accent "Common" pill ([R.drawable.bg_word_common_pill] —
     *  ptAccentTint fill, fully rounded). Callers attach layoutParams. */
    fun commonPill(
        ctx: Context,
        textSizeSp: Float,
        horizontalPadPx: Int,
        verticalPadPx: Int,
    ): TextView = TextView(ctx).apply {
        text = ctx.getString(R.string.word_detail_common)
        setTextColor(ctx.themeColor(R.attr.ptAccent))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
        typeface = medium
        setBackgroundResource(R.drawable.bg_word_common_pill)
        setPadding(horizontalPadPx, verticalPadPx, horizontalPadPx, verticalPadPx)
    }

    /** The meta row's compact star treatment: only the filled stars, in one
     *  muted run ("★★★"), no empty slots. */
    fun filledStars(ctx: Context, count: Int, textSizeSp: Float, color: Int): TextView =
        TextView(ctx).apply {
            text = "★".repeat(count.coerceAtMost(5))
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
        }

    /** The detail header's five-slot star treatment: filled stars (U+2605)
     *  in ptAccent, outline stars (U+2606) in ptOutline. */
    fun starSlots(ctx: Context, filled: Int, textSizeSp: Float): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val accent = ctx.themeColor(R.attr.ptAccent)
        val outline = ctx.themeColor(R.attr.ptOutline)
        val gap = (ctx.resources.displayMetrics.density).toInt()
        for (i in 0 until 5) {
            val isFilled = i < filled
            row.addView(TextView(ctx).apply {
                text = if (isFilled) "★" else "☆"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
                setTextColor(if (isFilled) accent else outline)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).also { it.marginEnd = gap }
            })
        }
        return row
    }

    /**
     * One imported frequency dictionary's chip: "JPDB: 1234". [textColor]
     * and the fill behind [background] are ONE decision — resolve them
     * together with [freqChipColors] rather than per call site; each
     * surface still owns the chip's shape and metrics. The source name is
     * never ellipsized — long names wrap with their row's FlowLayout.
     */
    fun freqChip(
        ctx: Context,
        tag: FrequencyTag,
        textColor: Int,
        background: Drawable,
        textSizeSp: Float,
        horizontalPadPx: Int,
        verticalPadPx: Int,
    ): TextView = TextView(ctx).apply {
        // Not a string resource: both halves are dictionary data, and the
        // colon join is notation rather than localizable copy.
        text = "${tag.source}: ${tag.display}"
        setTextColor(textColor)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
        typeface = medium
        this.background = background
        gravity = Gravity.CENTER_VERTICAL
        setPadding(horizontalPadPx, verticalPadPx, horizontalPadPx, verticalPadPx)
    }
}
