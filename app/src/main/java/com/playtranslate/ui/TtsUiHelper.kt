package com.playtranslate.ui

import android.app.Activity
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
 * Where a TTS alert should be presented. The drag-lookup lens runs as an
 * accessibility overlay; the in-app lens (e.g. the translation result screen)
 * runs inside an Activity. [context] also serves engine and Prefs lookups.
 */
sealed interface TtsAlertTarget {
    val context: Context

    /** An accessibility-overlay surface (the drag-lookup lens). */
    data class Overlay(
        override val context: Context,
        val wm: WindowManager,
        val displayId: Int,
    ) : TtsAlertTarget

    /** An Activity-hosted surface (the in-app lens). */
    data class InActivity(val activity: Activity) : TtsAlertTarget {
        override val context: Context get() = activity
    }
}

/**
 * "No Text-to-Speech" alert, shown when no TTS engine is active.
 *
 * Offers both recovery paths, because the app can't tell them apart: a
 * disabled engine exposes no bindable service, so "no active engine" looks
 * identical whether an engine is installed-but-disabled or not installed at
 * all.
 *  - Get Google TTS — install an engine when the device has none.
 *  - Open TTS settings — enable an engine that is installed but disabled.
 *
 * [onActionSelected] runs when the user picks either action (not on cancel) —
 * the caller uses it to dismiss the lookup surface, since both actions send
 * the user out to another app.
 */
fun showTtsNoEngineDialog(target: TtsAlertTarget, onActionSelected: () -> Unit) {
    val themed = overlayThemedContext(target.context)
    showTtsAlert(target) {
        hideIcon()
        setTitle("No Text-to-Speech")
        setMessage(
            "No text-to-speech engine is available to read words aloud. " +
                "Install one — or, if your device already has an engine, " +
                "enable it in Android's settings."
        )
        addButton(
            "Get Google TTS",
            themed.themeColor(R.attr.ptAccent),
            themed.themeColor(R.attr.ptAccentOn),
        ) {
            openPlayStore(themed, GOOGLE_TTS_PACKAGE)
            onActionSelected()
        }
        addButton(
            "Open TTS settings",
            themed.themeColor(R.attr.ptDivider),
            themed.themeColor(R.attr.ptAccent),
        ) {
            openTtsSettings(themed)
            onActionSelected()
        }
        addCancelButton("Not now")
    }
}

/**
 * "Language not supported" alert — a TTS engine is active but has no voice
 * for [lang]. [engineLabel] names the active engine when known.
 */
fun showTtsLanguageUnsupportedDialog(
    target: TtsAlertTarget,
    lang: SourceLangId,
    engineLabel: String?,
) {
    val langName = lang.displayName()
    val message = if (engineLabel != null) {
        "$engineLabel is the active engine, but doesn't support $langName."
    } else {
        "The active text-to-speech engine doesn't support $langName."
    }
    val themed = overlayThemedContext(target.context)
    showTtsAlert(target) {
        hideIcon()
        setTitle("Language Not Supported")
        setMessage(message)
        addButton(
            "OK",
            themed.themeColor(R.attr.ptAccent),
            themed.themeColor(R.attr.ptAccentOn),
        ) { }
    }
}

/** Build an [OverlayAlert] for [target], apply [configure], and show it on
 *  the matching surface — an accessibility overlay or an Activity. */
private fun showTtsAlert(
    target: TtsAlertTarget,
    configure: OverlayAlert.Builder.() -> Unit,
) {
    when (target) {
        is TtsAlertTarget.Overlay ->
            OverlayAlert.Builder(target.context, target.wm, target.displayId)
                .apply(configure)
                .show()
        is TtsAlertTarget.InActivity ->
            OverlayAlert.Builder(target.activity)
                .apply(configure)
                .showInActivity(target.activity)
    }
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
