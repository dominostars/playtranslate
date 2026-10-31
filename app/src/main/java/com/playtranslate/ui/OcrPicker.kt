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
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import com.playtranslate.BuildConfig
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.cloud.PtCloudService
import com.playtranslate.language.OcrBackend
import com.playtranslate.language.SourceLangId
import com.playtranslate.ocr.registry.OcrModelManager
import com.playtranslate.ocr.registry.isDownloaded
import com.playtranslate.ocr.registry.ocrLabel
import com.playtranslate.ocr.registry.selectionToken
import com.playtranslate.themeColor

/**
 * Populates an [OverlayAlert.Builder] as the "Choose OCR tool" picker for source
 * language [id]: PlayTranslate Cloud's entry first when the surface offers it
 * ([cloud], debug builds only), then one button per available OCR backend —
 * the one APPLIED to the displayed result ([appliedToken]) in the accent
 * style, the others in the divider/cancel style, any not-yet-downloaded one
 * with a download icon — followed by Cancel.
 *
 * Shared by every result surface (the over-game [CaptureResultOverlay], the
 * in-app [TranslationResultFragment], the floating menu's OCR row, and the
 * camera and import tools) so the styling, ordering, and switch/download
 * routing can't drift. The CALLER constructs the right Builder (overlay-window
 * vs. activity) and calls `.show()` / `.showAsOverlay()` on the returned
 * builder.
 *
 * [appliedToken] is the reader that produced the on-screen result (from its
 * [com.playtranslate.model.OcrProvenance]: an engine's token, or
 * [PtCloudService.ID] for a cloud read), NOT the current global OCR preference.
 * Highlighting the applied reader keeps the picker consistent with the "Scanned
 * by …" label and avoids a dead-end where the global selection has drifted (e.g.
 * changed in Settings): tapping any entry other than the applied one re-reads,
 * even if it happens to match the global preference. A surface with no result
 * (the floating menu) passes the selection: the cloud while its OCR switch is on,
 * else the selected engine.
 *
 * With [cloud] the picker is a radio over two settings at once, the per-language
 * engine token and the cloud's OCR switch ([PtCloudService.setOcrEnabled]),
 * because its one highlight is the only state it shows: picking anything means
 * "read with this from now on", and the switch's own gesture tail refreshes a
 * running live session. The Capture & overlay page is not a radio (its switch is
 * the state and its engine list the device engine), so the rule lives here
 * alone. The tap table, with [cloud]:
 *
 * | applied | OCR switch | tap | effect |
 * |---|---|---|---|
 * | cloud | on | Cloud | dismiss (no store, no reset: a tap on the current choice) |
 * | cloud | off (turned off elsewhere since the read) | Cloud | switch on, no re-read |
 * | cloud | on | engine X, downloaded | persist X, switch off, [onReOcr] (device) |
 * | cloud | on | engine X, not downloaded | switch off, [onDownload] (the download screen persists X on success) |
 * | device | off | Cloud, key saved | switch on, [CloudChoice.onReOcr] (through the router, warmed) |
 * | any | any | Cloud, no key | nothing switched, [CloudChoice.onOpenPage] (the page takes the key; the first key turns both switches on) |
 * | device (the cloud refused: cooldown, offline) | on | Cloud | switch on again, whose tail resets the cooldown, [CloudChoice.onReOcr] |
 * | device | on | the applied engine | switch off, no re-read (the off-ramp for a result the device read under the cloud) |
 * | device | off | the applied engine | dismiss |
 * | device | off | engine X, downloaded | persist X, [onReOcr] |
 *
 * An applied entry's tap reconciles the switch with the entry and never
 * re-reads. A device pick persists its token before flipping the switch, since
 * the switch's tail refreshes live mode, which must see the new token. Without
 * [cloud] (the camera and import tools, whose picks are scope-local and whose
 * frozen frames hold a device-only review) nothing about the switch is touched:
 *  - not downloaded            → [onDownload] (the surface deep-links to Settings
 *    and starts the download; the selection is persisted there, only on success).
 *  - downloaded & applied      → no-op (already the result's engine; just dismiss).
 *  - downloaded, not applied   → persist the new token HERE (so the re-OCR resolves
 *    the new engine from Prefs), then [onReOcr]. [applyToken] substitutes the
 *    persistence for surfaces with their own selection scope (the camera writes
 *    its per-flow token instead of the global one); null = the global write.
 */
object OcrPicker {

    /**
     * What a surface does for PlayTranslate Cloud's entry: [onReOcr] re-reads
     * through the reader router (a re-OCR stating no device-only, which the
     * router sends to the cloud and warms), [onOpenPage] opens the cloud page
     * the way the surface opens the OCR download page, for a pick while the
     * account is not admitted (no usable key saved, its trial or membership
     * ended, or the current terms not accepted). The switch itself is flipped
     * here, not by the surface.
     */
    class CloudChoice(
        val onReOcr: () -> Unit,
        val onOpenPage: () -> Unit,
    )

    fun populate(
        builder: OverlayAlert.Builder,
        ctx: Context,
        id: SourceLangId,
        appliedToken: String,
        onReOcr: () -> Unit,
        onDownload: (OcrBackend) -> Unit,
        applyToken: ((OcrBackend) -> Unit)? = null,
        cloud: CloudChoice? = null,
    ): OverlayAlert.Builder {
        builder.setTitle(ctx.getString(R.string.ocr_picker_title))
            .setMessage(ctx.getString(R.string.ocr_picker_message))
        // The one gate on the entry: a surface offers it, in a debug build
        // (the OCR switch reads false outside them, Prefs.ptCloudOcrEnabled).
        val cloudEntry = cloud?.takeIf { BuildConfig.DEBUG }
        if (cloudEntry != null) {
            val applied = appliedToken == PtCloudService.ID
            val bg = if (applied) ctx.themeColor(R.attr.ptAccent) else ctx.themeColor(R.attr.ptDivider)
            val fg = if (applied) ctx.themeColor(R.attr.ptAccentOn) else ctx.themeColor(R.attr.ptText)
            builder.addButton(PtCloudService.DISPLAY_NAME, bg, fg, R.drawable.ic_cloud) {
                // Not admitted (no usable key saved, an account whose trial
                // ended or whose membership is inactive, or one that has not
                // accepted the current terms): nothing is switched, as on
                // the settings rows; the cloud page is where the key goes and
                // Join and the terms are, and the answer that makes the
                // account admitted (the first key, an accepted check after
                // Join or after the terms) turns both switches on.
                if (!PtCloudService.config.admitted) {
                    cloudEntry.onOpenPage()
                    return@addButton
                }
                // The switch reconciled with the entry when there is something
                // to reconcile: the switch off, or a result the device read
                // under the cloud (a cooldown kept it there), whose tap means
                // "read this one with the cloud now" and whose gesture tail,
                // the cooldown reset, is the retry. Applied and already on is
                // a tap on the current choice, a dismiss like an engine's: no
                // store, no reset (a cooldown reset there would retry a
                // rate-limited or spent service for nothing; Codex review,
                // 2026-10-25).
                if (!applied || !PtCloudService.config.ocrEnabled) PtCloudService.setOcrEnabled(true)
                if (!applied) cloudEntry.onReOcr()
            }
        }
        // A device pick while the cloud reads: the switch goes off (the radio
        // rule), after the token write when there is one.
        fun deviceTurnsCloudOff() {
            if (cloudEntry != null && PtCloudService.config.ocrEnabled) PtCloudService.setOcrEnabled(false)
        }
        for (backend in OcrModelManager.availableBackends(ctx, id)) {
            val applied = backend.selectionToken == appliedToken
            val downloaded = backend.isDownloaded(ctx)
            val bg = if (applied) ctx.themeColor(R.attr.ptAccent) else ctx.themeColor(R.attr.ptDivider)
            val fg = if (applied) ctx.themeColor(R.attr.ptAccentOn) else ctx.themeColor(R.attr.ptText)
            val icon = if (!downloaded) R.drawable.ic_download else null
            builder.addButton(backend.ocrLabel(ctx), bg, fg, icon) {
                when {
                    !downloaded -> {
                        deviceTurnsCloudOff()
                        onDownload(backend)
                    }
                    applied -> deviceTurnsCloudOff() // already the result's engine: no re-read
                    else -> {
                        applyToken?.invoke(backend)
                            ?: Prefs(ctx).setOcrBackendToken(id, backend.selectionToken)
                        deviceTurnsCloudOff()
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

/** Build a no-text status message from [template], whose %1$s is [srcId]'s localized name
 *  wrapped in the [markNoTextLanguage] sentinels (further format args follow it). Every
 *  no-text producer (capture, camera snapshot, file import) routes through this so a flow
 *  can't forget the sentinels — without them [setNoTextStatus] silently degrades to plain
 *  text with no tappable language. */
fun noTextStatusMessage(
    ctx: Context,
    @StringRes template: Int,
    srcId: SourceLangId,
    vararg args: Any,
): String = ctx.getString(template, markNoTextLanguage(srcId.displayName()), *args)

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
