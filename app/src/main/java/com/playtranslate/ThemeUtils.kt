package com.playtranslate

import android.content.Context
import android.graphics.Color
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

/** Activity theme resource that matches the user's selected palette. Call
 *  [android.app.Activity.setTheme] with this BEFORE `super.onCreate()` so
 *  the first inflation already resolves `?attr/pt*` against the right
 *  palette — otherwise the activity launches with the manifest's default
 *  dark theme regardless of what the user picked. */
fun selectedActivityTheme(context: Context): Int = when (Prefs(context).themeIndex) {
    1    -> R.style.Theme_PlayTranslate_White
    2    -> R.style.Theme_PlayTranslate_Rainbow
    3    -> R.style.Theme_PlayTranslate_Purple
    else -> R.style.Theme_PlayTranslate
}

/** Linearly blends opaque color [a] into opaque color [b] at [ratio] of [a]
 *  (0..1). Ignores alpha — translucent inputs should be flattened first via
 *  [compositeOver] so we don't blend against raw RGB of a near-transparent
 *  hairline. */
fun blendColors(a: Int, b: Int, ratio: Float): Int {
    val inv = 1f - ratio
    return Color.rgb(
        (Color.red(a) * ratio + Color.red(b) * inv).toInt(),
        (Color.green(a) * ratio + Color.green(b) * inv).toInt(),
        (Color.blue(a) * ratio + Color.blue(b) * inv).toInt(),
    )
}

/** Composites translucent [fg] over opaque [bg] — returns the opaque color
 *  that will actually render where [fg] is painted on [bg]. Use before
 *  [blendColors] when one of the inputs is a low-alpha token like
 *  `ptDivider`. */
fun compositeOver(fg: Int, bg: Int): Int {
    val a = Color.alpha(fg) / 255f
    val inv = 1f - a
    return Color.rgb(
        (Color.red(fg) * a + Color.red(bg) * inv).toInt(),
        (Color.green(fg) * a + Color.green(bg) * inv).toInt(),
        (Color.blue(fg) * a + Color.blue(bg) * inv).toInt(),
    )
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
