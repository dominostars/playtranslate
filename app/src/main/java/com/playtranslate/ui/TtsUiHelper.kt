package com.playtranslate.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import com.playtranslate.R
import com.playtranslate.language.SourceLangId
import com.playtranslate.overlayThemedContext
import com.playtranslate.themeColor

private const val TAG = "TtsUiHelper"
private const val GOOGLE_TTS_PACKAGE = "com.google.android.tts"

/**
 * "No Text-to-Speech" alert, shown when no TTS engine is active.
 *
 * Offers both recovery paths, because the app can't tell them apart: a
 * disabled engine exposes no bindable service, so "no active engine" looks
 * identical whether an engine is installed-but-disabled or not installed at
 * all.
 *  - Get Google TTS — install an engine when the device has none (the system
 *    TTS settings screen itself has no way to add one).
 *  - Open TTS settings — enable an engine that is installed but disabled.
 *
 * [onActionSelected] runs when the user picks either action (not on cancel) —
 * the caller uses it to dismiss the lookup surface, since both actions send
 * the user out to another app.
 *
 * Accessibility-overlay variant — for the drag-lookup lens.
 */
fun showTtsNoEngineDialog(
    context: Context,
    wm: WindowManager,
    displayId: Int,
    onActionSelected: () -> Unit,
) {
    val themed = overlayThemedContext(context)
    OverlayAlert.Builder(context, wm, displayId)
        .hideIcon()
        .setTitle("No Text-to-Speech")
        .setMessage(
            "No text-to-speech engine is available to read words aloud. " +
                "Install one — or, if your device already has an engine, " +
                "enable it in Android's settings."
        )
        .addButton(
            "Get Google TTS",
            themed.themeColor(R.attr.ptAccent),
            themed.themeColor(R.attr.ptAccentOn),
        ) {
            openPlayStore(themed, GOOGLE_TTS_PACKAGE)
            onActionSelected()
        }
        .addButton(
            "Open TTS settings",
            themed.themeColor(R.attr.ptDivider),
            themed.themeColor(R.attr.ptAccent),
        ) {
            openTtsSettings(themed)
            onActionSelected()
        }
        .addCancelButton("Not now")
        .show()
}

/**
 * "Language not supported" alert — a TTS engine is active but has no voice
 * for [lang]. [engineLabel] names the active engine when known.
 */
fun showTtsLanguageUnsupportedDialog(
    context: Context,
    wm: WindowManager,
    displayId: Int,
    lang: SourceLangId,
    engineLabel: String?,
) {
    val langName = lang.displayName()
    val message = if (engineLabel != null) {
        "$engineLabel is the active engine, but doesn't support $langName."
    } else {
        "The active text-to-speech engine doesn't support $langName."
    }
    val themed = overlayThemedContext(context)
    OverlayAlert.Builder(context, wm, displayId)
        .hideIcon()
        .setTitle("Language Not Supported")
        .setMessage(message)
        .addButton(
            "OK",
            themed.themeColor(R.attr.ptAccent),
            themed.themeColor(R.attr.ptAccentOn),
        ) { }
        .show()
}

/** Open the system Text-to-speech settings screen, falling back to the
 *  top-level Settings app if the device exposes no dedicated screen. */
private fun openTtsSettings(context: Context) {
    val candidates = listOf(
        Intent("com.android.settings.TTS_SETTINGS"),
        Intent(Settings.ACTION_SETTINGS),
    )
    for (intent in candidates) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
            return
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "settings screen not available: ${intent.action}", e)
        }
    }
}

/** Open the Play Store listing for [packageName]. */
private fun openPlayStore(context: Context, packageName: String) {
    val intent = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("https://play.google.com/store/apps/details?id=$packageName"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "no handler for Play Store link", e)
    }
}
