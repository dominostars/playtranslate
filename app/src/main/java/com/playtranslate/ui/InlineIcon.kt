package com.playtranslate.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ImageSpan
import androidx.annotation.AttrRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.withTranslation
import com.playtranslate.R
import com.playtranslate.themeColor

/** An [ImageSpan] anchored to the BASELINE's font metrics, drawn [dyPx]
 *  higher than the font bottom so an inline icon optically centers with the
 *  surrounding text. Deliberately not anchored to the framework's line-bottom
 *  (super.draw's `bottom`): on a WRAPPED line that includes line spacing,
 *  which drags the icon visibly low — the Yomitan outdated-row subtitle wraps
 *  to two lines and showed exactly that. Baseline + font metrics render
 *  identically on one line and many. */
private class OffsetImageSpan(drawable: Drawable, private val dyPx: Int) :
    ImageSpan(drawable, ALIGN_BOTTOM) {
    override fun draw(
        canvas: Canvas,
        text: CharSequence?,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint,
    ) {
        // Icon bottom at (baseline + font bottom - lift): matches the old
        // single-line ALIGN_BOTTOM placement, independent of line spacing.
        val transY = y + paint.fontMetricsInt.bottom - drawable.bounds.bottom - dyPx
        canvas.withTranslation(x = x, y = transY.toFloat()) {
            drawable.draw(this)
        }
    }
}

/** The one inline-icon tuning: [iconRes] at 14dp tinted [tint], lifted
 *  1.5dp above ALIGN_BOTTOM to optically center (at 2dp the icons read
 *  slightly high, so 1.5dp drops them ~0.5dp). Sized for the 12 to 13sp
 *  muted subtitle and helper text that carries these icons. */
private fun inlineIconSpan(
    ctx: Context,
    @DrawableRes iconRes: Int,
    @AttrRes tint: Int,
): ImageSpan? {
    val density = ctx.resources.displayMetrics.density
    val px = (14 * density).toInt()
    val drawable = ContextCompat.getDrawable(ctx, iconRes)?.mutate() ?: return null
    drawable.setTint(ctx.themeColor(tint))
    drawable.setBounds(0, 0, px, px)
    return OffsetImageSpan(drawable, (1.5f * density).toInt())
}

/** Appends an optically-centered inline icon tinted [tint] to [sb] — the
 *  settings summary digests and warning subtitles (e.g. the Yomitan
 *  outdated rows). */
internal fun appendInlineIcon(
    ctx: Context,
    sb: SpannableStringBuilder,
    @DrawableRes iconRes: Int,
    @AttrRes tint: Int = R.attr.ptTextMuted,
) {
    val span = inlineIconSpan(ctx, iconRes, tint) ?: return
    val start = sb.length
    sb.append(" ")
    sb.setSpan(span, start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
}

/** The string [resId] with its `%1$s` placeholder drawn as the [iconRes]
 *  inline icon, for a localized sentence that names a control by its glyph
 *  ("Tap [eye] to exclude it"): each locale carries the placeholder where
 *  its grammar puts the icon, and this fills it. The placeholder is U+FFFC,
 *  the object replacement character an [ImageSpan] is defined to draw over,
 *  so it is found again after formatting and never collides with text. */
internal fun inlineIconString(
    ctx: Context,
    @StringRes resId: Int,
    @DrawableRes iconRes: Int,
    @AttrRes tint: Int = R.attr.ptTextMuted,
): CharSequence {
    val marker = "￼"
    val text = ctx.getString(resId, marker)
    val at = text.indexOf(marker)
    val span = if (at < 0) null else inlineIconSpan(ctx, iconRes, tint)
    if (span == null) return text.replace(marker, "")
    return SpannableString(text).apply {
        setSpan(span, at, at + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}
