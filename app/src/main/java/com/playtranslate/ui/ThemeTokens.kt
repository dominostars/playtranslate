package com.playtranslate.ui

import android.content.Context
import android.content.res.ColorStateList
import android.util.TypedValue
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.annotation.Px
import com.playtranslate.R

/**
 * Typed accessors for the Stage-1 semantic theme tokens.
 *
 * These resolve `?attr/ptBg`, `?attr/ptText`, etc. against the currently
 * applied theme on the supplied Context.
 */
object ThemeTokens {

    // ─── Surfaces ───────────────────────────────────────────────
    fun bg(ctx: Context): Int        = resolveColor(ctx, R.attr.ptBg)
    fun surface(ctx: Context): Int   = resolveColor(ctx, R.attr.ptSurface)
    fun card(ctx: Context): Int      = resolveColor(ctx, R.attr.ptCard)
    fun elevated(ctx: Context): Int  = resolveColor(ctx, R.attr.ptElevated)
    fun divider(ctx: Context): Int   = resolveColor(ctx, R.attr.ptDivider)
    fun outline(ctx: Context): Int   = resolveColor(ctx, R.attr.ptOutline)

    // ─── Text ───────────────────────────────────────────────────
    fun text(ctx: Context): Int            = resolveColor(ctx, R.attr.ptText)
    fun textMuted(ctx: Context): Int       = resolveColor(ctx, R.attr.ptTextMuted)
    fun textHint(ctx: Context): Int        = resolveColor(ctx, R.attr.ptTextHint)
    fun textTranslation(ctx: Context): Int = resolveColor(ctx, R.attr.ptTextTranslation)
    fun textLink(ctx: Context): Int        = resolveColor(ctx, R.attr.ptTextLink)

    // ─── Accent ─────────────────────────────────────────────────
    fun accent(ctx: Context): Int     = resolveColor(ctx, R.attr.ptAccent)
    fun accentTint(ctx: Context): Int = resolveColor(ctx, R.attr.ptAccentTint)
    fun accentOn(ctx: Context): Int   = resolveColor(ctx, R.attr.ptAccentOn)

    // ─── Status ─────────────────────────────────────────────────
    fun danger(ctx: Context): Int  = resolveColor(ctx, R.attr.ptDanger)
    fun warning(ctx: Context): Int = resolveColor(ctx, R.attr.ptWarning)

    // ─── Dimensions ─────────────────────────────────────────────
    @Px
    fun radiusPx(ctx: Context): Int = resolveDimensionPx(ctx, R.attr.ptRadius)

    // ─── ColorStateList helpers (for tint) ──────────────────────
    fun accentStateList(ctx: Context): ColorStateList =
        ColorStateList.valueOf(accent(ctx))

    fun textStateList(ctx: Context): ColorStateList =
        ColorStateList.valueOf(text(ctx))

    // ─── Internals ──────────────────────────────────────────────
    @ColorInt
    private fun resolveColor(ctx: Context, @AttrRes attr: Int): Int {
        val tv = TypedValue()
        if (!ctx.theme.resolveAttribute(attr, tv, true)) {
            error("Theme attr 0x${Integer.toHexString(attr)} not set on current theme")
        }
        return tv.data
    }

    @Px
    private fun resolveDimensionPx(ctx: Context, @AttrRes attr: Int): Int {
        val tv = TypedValue()
        if (!ctx.theme.resolveAttribute(attr, tv, true)) {
            error("Theme attr 0x${Integer.toHexString(attr)} not set on current theme")
        }
        return TypedValue.complexToDimensionPixelSize(tv.data, ctx.resources.displayMetrics)
    }
}
