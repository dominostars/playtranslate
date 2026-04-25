package com.playtranslate

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
import androidx.annotation.AttrRes
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import com.playtranslate.ui.AccentColor
import com.playtranslate.ui.ThemeMode

/** Resolves a theme colour attribute to an ARGB int. */
fun Context.themeColor(@AttrRes attr: Int): Int {
    val a = obtainStyledAttributes(intArrayOf(attr))
    val color = a.getColor(0, 0)
    a.recycle()
    return color
}

/** True when the resolved theme should render as dark — `themeMode = DARK`,
 *  or `themeMode = SYSTEM` and the OS is in night mode. */
fun isEffectivelyDark(context: Context): Boolean {
    val mode = Prefs(context).themeMode
    return when (mode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> {
            val uiMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            uiMode == Configuration.UI_MODE_NIGHT_YES
        }
    }
}

/** Base palette theme (without an accent overlay). Use [applyTheme] when you
 *  want the activity to also pick up the user's accent. */
fun baseActivityTheme(context: Context): Int =
    if (isEffectivelyDark(context)) R.style.Theme_PlayTranslate
    else R.style.Theme_PlayTranslate_White

/** Activity theme + accent overlay. Call BEFORE `super.onCreate()` so the first
 *  inflation already resolves `?attr/pt*` against the right palette + accent. */
fun applyTheme(activity: Activity) {
    activity.setTheme(baseActivityTheme(activity))
    activity.theme.applyStyle(Prefs(activity).accent.overlay, true)
}

/** Apply the user's accent overlay onto an arbitrary theme — used by dialog
 *  fragments (after the framework constructs their themed context) and by
 *  the accessibility service's [android.view.ContextThemeWrapper]. */
fun applyAccentOverlay(theme: Resources.Theme, context: Context) {
    theme.applyStyle(Prefs(context).accent.overlay, true)
}

/** Returns the correct full-screen-dialog base theme for the user's current
 *  light/dark resolution. Callers must additionally call [applyAccentOverlay]
 *  on the dialog's theme in `onCreateDialog` to pick up the accent. */
fun fullScreenDialogTheme(context: Context): Int =
    if (isEffectivelyDark(context)) R.style.Theme_PlayTranslate_FullScreenDialog
    else R.style.Theme_PlayTranslate_White_FullScreenDialog

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
 * where the Activity theme isn't available. Looks up the user's mode + accent from
 * [Prefs] and returns the matching color resource.
 */
object OverlayColors {
    private fun isDark(ctx: Context) = isEffectivelyDark(ctx)

    private fun accentRes(ctx: Context): Int = Prefs(ctx).accent.color

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
