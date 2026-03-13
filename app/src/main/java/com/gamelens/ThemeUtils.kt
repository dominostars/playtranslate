package com.gamelens

import android.content.Context
import androidx.annotation.AttrRes

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
