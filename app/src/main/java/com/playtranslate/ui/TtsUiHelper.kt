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
import com.playtranslate.overlay.OverlayHost
import com.playtranslate.overlayThemedContext
import com.playtranslate.themeColor
import androidx.core.net.toUri

private const val TAG = "TtsUiHelper"
private const val GOOGLE_TTS_PACKAGE = "com.google.android.tts"

/**
 * Where a TTS alert should be presented. The drag-lookup lens runs as a
 * capture overlay; the in-app lens (e.g. the translation result screen)
 * runs inside an Activity. [context] also serves engine and Prefs lookups.
 */
sealed interface TtsAlertTarget {
    val context: Context

    /** A capture-overlay surface (the drag-lookup lens). [overlayHost]
     *  carries the active backend's window type so the alert shows on
     *  MediaProjection as well as accessibility. */
    data class Overlay(
        override val context: Context,
        val overlayHost: OverlayHost,
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
        setTitle(themed.getString(R.string.tts_no_engine_dialog_title))
        setMessage(themed.getString(R.string.tts_no_engine_dialog_message))
        addButton(
            themed.getString(R.string.tts_no_engine_get_google),
            themed.themeColor(R.attr.ptAccent),
            themed.themeColor(R.attr.ptAccentOn),
        ) {
            openPlayStore(themed, GOOGLE_TTS_PACKAGE)
            onActionSelected()
        }
        addButton(
            themed.getString(R.string.tts_no_engine_open_settings),
            themed.themeColor(R.attr.ptDivider),
            themed.themeColor(R.attr.ptAccent),
        ) {
            openTtsSettings(themed)
            onActionSelected()
        }
        addCancelButton(themed.getString(R.string.btn_not_now))
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
    val themed = overlayThemedContext(target.context)
    val message = if (engineLabel != null) {
        themed.getString(R.string.tts_language_unsupported_with_engine_message, engineLabel, langName)
    } else {
        themed.getString(R.string.tts_language_unsupported_unknown_engine_message, langName)
    }
    showTtsAlert(target) {
        hideIcon()
        setTitle(themed.getString(R.string.tts_language_unsupported_dialog_title))
        setMessage(message)
        addButton(
            themed.getString(R.string.btn_ok),
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
            OverlayAlert.Builder(
                target.context, target.overlayHost, target.wm, target.displayId,
            ).apply(configure).showAsOverlay()
        is TtsAlertTarget.InActivity ->
            OverlayAlert.Builder(target.activity)
                .apply(configure)
                .show()
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
        "https://play.google.com/store/apps/details?id=$packageName".toUri(),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "no handler for Play Store link", e)
    }
}
