package com.playtranslate.ui

import android.content.Context
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
