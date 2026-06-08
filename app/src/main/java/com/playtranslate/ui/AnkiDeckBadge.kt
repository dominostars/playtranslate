package com.playtranslate.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.DrawableCompat
import com.playtranslate.R

/**
 * Shared rendering for the "already in Anki" badge pill shown beside the
 * Common pill / star rating across the word-detail sheet, the lens, and the
 * translation-result words list. Each surface supplies its own colours and
 * background so the pill matches that surface's Common pill; the label text,
 * the leading [R.drawable.ic_card_stack] icon, and the accessibility text
 * are produced here so the three stay in lockstep.
 */
object AnkiDeckBadge {

    /**
     * Visible label for [deckNames]: the deck's leaf segment when the word
     * is in exactly one deck (`Japanese::Mining::Core 2k` → `Core 2k`), or
     * `"N Anki decks"` when it's in several. [deckNames] must be non-empty.
     */
    fun label(ctx: Context, deckNames: List<String>): String =
        if (deckNames.size == 1) deckNames[0].substringAfterLast("::")
        else ctx.getString(R.string.word_anki_in_decks, deckNames.size)

    /**
     * Builds a passive (non-clickable) pill: a [R.drawable.ic_card_stack]
     * icon followed by [label]. The icon is tinted to [textColor] and sized
     * to the text. [background] is consumed as-is (pass a fresh instance per
     * call). Returns null when [deckNames] is empty so callers can use the
     * result directly as an add-or-skip.
     */
    fun buildPill(
        ctx: Context,
        deckNames: List<String>,
        textColor: Int,
        background: Drawable,
        textSizeSp: Float,
        horizontalPadPx: Int,
        verticalPadPx: Int,
    ): TextView? {
        if (deckNames.isEmpty()) return null
        val text = label(ctx, deckNames)
        val density = ctx.resources.displayMetrics.density
        val iconPx = (textSizeSp * density).toInt().coerceAtLeast(1)
        val icon: Drawable? = AppCompatResources.getDrawable(ctx, R.drawable.ic_card_stack)
            ?.mutate()
            ?.also {
                DrawableCompat.setTint(it, textColor)
                it.setBounds(0, 0, iconPx, iconPx)
            }
        return TextView(ctx).apply {
            this.text = text
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            this.background = background
            gravity = Gravity.CENTER_VERTICAL
            setCompoundDrawablesRelative(icon, null, null, null)
            compoundDrawablePadding = (4 * density).toInt()
            setPadding(horizontalPadPx, verticalPadPx, horizontalPadPx, verticalPadPx)
            contentDescription = ctx.getString(R.string.word_anki_deck_badge_cd, text)
        }
    }
}
