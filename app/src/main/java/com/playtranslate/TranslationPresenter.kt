package com.playtranslate

import com.playtranslate.capture.CapturedFrame
import com.playtranslate.ocr.DeviceFrameReader
import com.playtranslate.ocr.ReaderAttribution
import com.playtranslate.ocr.ReaderTranslations
import com.playtranslate.ui.TextBox

/**
 * [LivePresenter] for live TRANSLATION: regions become color-sampled
 * translation boxes (skeleton first, cached translations and a reader's that
 * arrived with the OCR instantly, a reader's later ones as they arrive while
 * a box the cache could not fill waits for them, the rest machine-translated
 * in-cycle). Anchors ARE the display boxes. Panel
 * emission mirrors the pinhole tier's: full displayed state, only while the
 * in-app panel is actually visible.
 */
class TranslationPresenter(
    private val service: CaptureService,
    private val displayId: Int,
) : LivePresenter {

    override val flavor: OverlayFlavor = OverlayFlavor.TRANSLATION
    override val rendersOverlays: Boolean = true

    override suspend fun present(
        work: List<ScanlineReconciler.Region>,
        frame: CapturedFrame,
        cropLeft: Int,
        cropTop: Int,
        onPartial: suspend (List<TextBox>) -> Unit,
        reader: ReaderTranslations?,
    ): List<TextBox> {
        val texts = work.map { it.text }
        val placeholders = OverlayToolkit.buildPlaceholderBoxes(
            texts,
            work.map { it.bounds },
            work.map { it.lineCount },
            frame.bitmap, cropLeft, cropTop,
            work.map { it.orientation },
            work.map { it.alignment },
            work.map { ReadingArbiter.scoreOf(it.group) },
            work.map { Triple(it.angleDeg, it.orientedWidth, it.orientedHeight) },
            drawBounds = work.map { it.group?.drawBounds ?: it.bounds },
        )
        // What arrived with the OCR wins over a cache entry
        // (translationsAtOnce): it is the fresher one.
        val partial = placeholders.filledFrom(service.translationsAtOnce(texts, reader))
        // What arrived with the OCR, the cache fills and the skeletons render
        // immediately, before any wait: the reader's later translations and
        // MT fill in place after. A cycle with no skeleton renders here too;
        // the cycle's final render of the same boxes is then a no-op in the
        // overlay view.
        onPartial(partial)
        // A cycle whose boxes the cache and the OCR's translations filled
        // entirely is final and waits for nothing, reader or not: the cache
        // already answers every box the reader could still name. Otherwise
        // translatePlaceholders waits for the reader only while a box the
        // cache has no entry for can still take one, and while it waits the
        // reader's translations replace cache fills too.
        if (partial.none { it.translatedText.isEmpty() }) return partial
        return OverlayToolkit.translatePlaceholders(
            service, placeholders, texts, reader, shown = partial, onProgress = onPartial,
        )
    }

    /** Full displayed state to the in-app panel — same shape as the pinhole
     *  tier's panel sync, gated HERE on the panel actually being visible;
     *  the service then drops a delivery identical to what the panel shows
     *  (every mutated cycle offers, and a reposition-only cycle is a
     *  mutation with unchanged text; [PanelEmissionDedup]). The screenshot
     *  write only runs past both: single-screen mode (the default) never
     *  pays it, and neither does a cycle that changes nothing the panel
     *  shows. */
    override suspend fun emitApplied(
        anchors: List<TextBox>,
        ocrResult: OcrManager.OcrResult?,
        frameIncludesSystemUi: Boolean,
        frameIncludesOwnOverlays: Boolean,
        screenshotPath: () -> String?,
        attribution: ReaderAttribution?,
    ) {
        if (!service.appPanelVisible()) return
        service.emitPanelResult(
            displayId,
            OverlayToolkit.panelTexts(OverlayToolkit.panelReadingOrder(anchors, ocrResult)),
            screenshotPath,
            ocrProvenance = ocrResult?.let {
                service.panelOcrProvenance(
                    attribution ?: DeviceFrameReader.attributionOf(it, service),
                    displayId, frameIncludesSystemUi, frameIncludesOwnOverlays,
                )
            },
            backendDisplayName = OverlayToolkit.panelBackendLabel(anchors),
        )
    }

    override fun emitNoText(attribution: ReaderAttribution?) {
        service.handleNoTextDetected(displayId, attribution)
    }
}
