package com.playtranslate

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.annotation.AttrRes
import androidx.core.view.WindowCompat
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

/** Activity-level edge-to-edge: SystemBarStyle keyed to our in-app theme
 *  (not the system uiMode the `auto()` variant keys off), plus appearance
 *  flags. Call AFTER [applyTheme] and BEFORE `super.onCreate()`.
 *
 *  `SystemBarStyle.dark` ⇒ light icons; `.light` ⇒ dark icons (the API names
 *  by theme palette, not by icon color). */
fun applyEdgeToEdge(activity: ComponentActivity) {
    val dark = isEffectivelyDark(activity)
    val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
                else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
    activity.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    applySystemBarAppearance(activity.window, activity)
}

/** Dialog/fragment edge-to-edge: turn off decor-fits-system-windows and set
 *  appearance flags. Call in `onStart`. Idempotent — safe to re-call from
 *  [com.playtranslate.ui.SettingsBottomSheet.reinflateContent] after a theme
 *  toggle. */
fun applyDialogEdgeToEdge(window: Window, context: Context) {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    applySystemBarAppearance(window, context)
}

/** Bare appearance-flag update — refresh status- and navigation-bar icon
 *  tint to match the current in-app theme. The in-place re-themer in
 *  `SettingsBottomSheet.reinflateContent` calls this when the user toggles
 *  theme inside dialog-mode Settings. */
fun applySystemBarAppearance(window: Window, context: Context) {
    val light = !isEffectivelyDark(context)
    val controller = WindowCompat.getInsetsController(window, window.decorView)
    controller.isAppearanceLightStatusBars = light
    controller.isAppearanceLightNavigationBars = light
}

/** Apply the user's accent overlay onto an arbitrary theme — used by dialog
 *  fragments (after the framework constructs their themed context) and by
 *  the accessibility service's [android.view.ContextThemeWrapper]. */
fun applyAccentOverlay(theme: Resources.Theme, context: Context) {
    theme.applyStyle(Prefs(context).accent.overlay, true)
}

/**
 * Wraps [context] in a [android.view.ContextThemeWrapper] with the resolved
 * light/dark base theme and the user's accent overlay applied. Use this for
 * overlay surfaces (accessibility-service windows, floating widgets) so
 * `themeColor(R.attr.pt*)` lookups resolve correctly the same way they do
 * inside the main Activity. Idempotent: wrapping an already-themed context
 * just rewraps and re-applies the overlay.
 */
fun overlayThemedContext(context: Context): Context {
    val wrapper = android.view.ContextThemeWrapper(context, baseActivityTheme(context))
    applyAccentOverlay(wrapper.theme, context)
    return wrapper
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

