package com.playtranslate

import android.content.Context
import androidx.annotation.AttrRes
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat

/** Resolves a theme colour attribute to an ARGB int. */
fun Context.themeColor(@AttrRes attr: Int): Int {
    val a = obtainStyledAttributes(intArrayOf(attr))
    val color = a.getColor(0, 0)
    a.recycle()
    return color
}

/** Returns the correct full-screen dialog theme for the user's selected palette. */
fun fullScreenDialogTheme(context: Context): Int = when (Prefs(context).themeIndex) {
    1    -> R.style.Theme_PlayTranslate_White_FullScreenDialog
    2    -> R.style.Theme_PlayTranslate_Rainbow_FullScreenDialog
    3    -> R.style.Theme_PlayTranslate_Purple_FullScreenDialog
    else -> R.style.Theme_PlayTranslate_FullScreenDialog
}

/**
 * Resolves a color for use in overlay contexts (accessibility service, floating windows)
 * where the Activity theme isn't available. Looks up the user's theme from [Prefs]
 * and returns the matching color resource.
 */
object OverlayColors {
    private fun isDark(ctx: Context) = Prefs(ctx).themeIndex.let { it == 0 || it == 3 }

    private fun accentRes(ctx: Context): Int = when (Prefs(ctx).themeIndex) {
        2    -> R.color.pt_accent_coral
        3    -> R.color.pt_accent_purple
        else -> R.color.pt_accent_teal
    }

    fun accent(ctx: Context): Int = ContextCompat.getColor(ctx, accentRes(ctx))
    fun accentOn(ctx: Context): Int = ContextCompat.getColor(ctx,
        if (isDark(ctx)) R.color.pt_dark_text_on_accent else R.color.pt_light_text_on_accent)
    fun bg(ctx: Context): Int = ContextCompat.getColor(ctx,
        if (isDark(ctx)) R.color.pt_dark_bg else R.color.pt_light_bg)
    fun surface(ctx: Context): Int = ContextCompat.getColor(ctx,
        if (isDark(ctx)) R.color.pt_dark_surface else R.color.pt_light_surface)
    fun card(ctx: Context): Int = ContextCompat.getColor(ctx,
        if (isDark(ctx)) R.color.pt_dark_card else R.color.pt_light_card)
    fun text(ctx: Context): Int = ContextCompat.getColor(ctx,
        if (isDark(ctx)) R.color.pt_dark_text else R.color.pt_light_text)
    fun textMuted(ctx: Context): Int = ContextCompat.getColor(ctx,
        if (isDark(ctx)) R.color.pt_dark_text_muted else R.color.pt_light_text_muted)
    fun divider(ctx: Context): Int = ContextCompat.getColor(ctx,
        if (isDark(ctx)) R.color.pt_dark_divider else R.color.pt_light_divider)
    fun danger(ctx: Context): Int = ContextCompat.getColor(ctx,
        if (isDark(ctx)) R.color.pt_dark_danger else R.color.pt_light_danger)
}
