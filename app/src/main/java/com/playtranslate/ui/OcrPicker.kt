package com.playtranslate.ui

import android.content.Context
import android.graphics.Color
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.ImageSpan
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.language.OcrBackend
import com.playtranslate.language.SourceLangId
import com.playtranslate.ocr.registry.OcrModelManager
import com.playtranslate.ocr.registry.isDownloaded
import com.playtranslate.ocr.registry.ocrLabel
import com.playtranslate.ocr.registry.selectionToken
import com.playtranslate.themeColor

/**
 * Populates an [OverlayAlert.Builder] as the "Choose OCR tool" picker for source
 * language [id]: one button per available OCR backend — the one APPLIED to the
 * displayed result ([appliedToken]) in the accent style, the others in the
 * divider/cancel style, any not-yet-downloaded one with a download icon —
 * followed by Cancel.
 *
 * Shared by every result surface (the over-game [CaptureResultOverlay] and the
 * in-app [TranslationResultFragment]) so the styling, ordering, and switch/
 * download routing can't drift. The CALLER constructs the right Builder
 * (overlay-window vs. activity) and calls `.show()` / `.showAsOverlay()` on the
 * returned builder.
 *
 * [appliedToken] is the engine that produced the on-screen result (from its
 * [com.playtranslate.model.OcrProvenance]), NOT the current global OCR preference.
 * Highlighting the applied engine keeps the picker consistent with the "Scanned
 * by …" label and avoids a dead-end where the global selection has drifted (e.g.
 * changed in Settings): tapping any engine other than the applied one re-OCRs,
 * even if it happens to match the global preference.
 *
 * Tap routing:
 *  - not downloaded            → [onDownload] (the surface deep-links to Settings
 *    and starts the download; the selection is persisted there, only on success).
 *  - downloaded & applied      → no-op (already the result's engine; just dismiss).
 *  - downloaded, not applied   → persist the new token HERE (so the re-OCR resolves
 *    the new engine from Prefs), then [onReOcr].
 */
object OcrPicker {
    fun populate(
        builder: OverlayAlert.Builder,
        ctx: Context,
        id: SourceLangId,
        appliedToken: String,
        onReOcr: () -> Unit,
        onDownload: (OcrBackend) -> Unit,
    ): OverlayAlert.Builder {
        builder.hideIcon()
            .setTitle(ctx.getString(R.string.ocr_picker_title))
            .setMessage(ctx.getString(R.string.ocr_picker_message))
        for (backend in OcrModelManager.availableBackends(ctx, id)) {
            val applied = backend.selectionToken == appliedToken
            val downloaded = backend.isDownloaded(ctx)
            val bg = if (applied) ctx.themeColor(R.attr.ptAccent) else ctx.themeColor(R.attr.ptDivider)
            val fg = if (applied) ctx.themeColor(R.attr.ptAccentOn) else ctx.themeColor(R.attr.ptText)
            val icon = if (!downloaded) R.drawable.ic_download else null
            builder.addButton(backend.ocrLabel, bg, fg, icon) {
                when {
                    !downloaded -> onDownload(backend)
                    applied -> { /* already the result's engine — just dismiss */ }
                    else -> {
                        Prefs(ctx).setOcrBackendToken(id, backend.selectionToken)
                        onReOcr()
                    }
                }
            }
        }
        return builder.addCancelButton()
    }
}

/** Private 1-char sentinels the no-text message wraps the source-language name in (via
 *  [markNoTextLanguage]) so [setNoTextStatus] can recover the EXACT span to make tappable —
 *  robust against locale word order or a region label that contains the language name, which a
 *  substring search would mismatch. Invisible Unicode bidi isolates: no stripping needed
 *  (zero-width), and they correctly isolate the embedded name for RTL. */
private const val NO_TEXT_LANG_OPEN = "⁨"   // FIRST STRONG ISOLATE (FSI)
private const val NO_TEXT_LANG_CLOSE = "⁩"  // POP DIRECTIONAL ISOLATE (PDI)

/** Wrap [languageName] in the sentinels [setNoTextStatus] uses to locate the language span in
 *  the formatted "No <lang> text detected …" message. Pass the result as the %1$s arg. */
fun markNoTextLanguage(languageName: String): String =
    "$NO_TEXT_LANG_OPEN$languageName$NO_TEXT_LANG_CLOSE"

/** Render a "No <lang> text detected …" status in [message] with up to two independent,
 *  precisely-tappable affordances — both [ClickableSpan]s driven by one [LinkMovementMethod],
 *  so each has its own tap region and neither steals the other's taps:
 *   - the source-language name (located by the [markNoTextLanguage] sentinels the message was
 *     built with) is accent-colored and tappable → [onLanguageTap] (opens the source picker);
 *   - when [showGear] is true a settings gear is appended, tappable → [onGearTap] (opens the
 *     OCR picker). Sized + tinted in code (intrinsic 24dp is too big next to status text, and
 *     the overlay's non-AppCompat inflater drops app:tint), so it matches on both surfaces.
 *  Safe for any status: a message without the sentinels and no gear is plain text with the
 *  movement method cleared. */
fun TextView.setNoTextStatus(
    message: String,
    showGear: Boolean,
    onLanguageTap: () -> Unit,
    onGearTap: () -> Unit,
) {
    val builder = SpannableStringBuilder(message)
    // The language name sits between the (zero-width) sentinels; make exactly that tappable.
    val open = message.indexOf(NO_TEXT_LANG_OPEN)
    val close = if (open >= 0) message.indexOf(NO_TEXT_LANG_CLOSE, open + 1) else -1
    val hasLang = open >= 0 && close > open + 1
    if (hasLang) {
        val accent = context.themeColor(R.attr.ptAccent)
        builder.setSpan(object : ClickableSpan() {
            override fun onClick(widget: View) { onLanguageTap() }
            override fun updateDrawState(ds: TextPaint) {
                ds.color = accent
                ds.isUnderlineText = false
            }
        }, open + 1, close, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    if (showGear) {
        val sizePx = (18 * resources.displayMetrics.density).toInt()
        val gear = ContextCompat.getDrawable(context, R.drawable.ic_settings)?.mutate()?.apply {
            setBounds(0, 0, sizePx, sizePx)
            setTint(context.themeColor(R.attr.ptTextHint))
        }
        if (gear != null) {
            builder.append("  ")
            val gearAt = builder.length
            builder.append("￼")  // object-replacement placeholder the ImageSpan draws over
            builder.setSpan(
                ImageSpan(gear, ImageSpan.ALIGN_CENTER), gearAt, gearAt + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            builder.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) { onGearTap() }
                override fun updateDrawState(ds: TextPaint) { ds.isUnderlineText = false } // gear keeps its own tint
            }, gearAt, gearAt + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
    text = builder
    movementMethod = if (hasLang || showGear) LinkMovementMethod.getInstance() else null
    highlightColor = Color.TRANSPARENT
}
