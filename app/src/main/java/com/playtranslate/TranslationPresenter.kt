package com.playtranslate

import com.playtranslate.capture.CapturedFrame
import com.playtranslate.ui.TextBox

/**
 * [LivePresenter] for live TRANSLATION: regions become color-sampled
 * translation boxes (skeleton first, cached translations instantly, the
 * rest machine-translated in-cycle). Anchors ARE the display boxes. Panel
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
        val partial = placeholders.mapIndexed { i, ph ->
            service.getCachedTranslation(texts[i])
                ?.let { ph.copy(translatedText = it.text, backendDisplayName = it.backendDisplayName) }
                ?: ph
        }
        if (partial.none { it.translatedText.isEmpty() }) return partial
        // Skeletons/cache fills render immediately; MT fills in place after.
        onPartial(partial)
        return OverlayToolkit.translatePlaceholders(service, placeholders, texts)
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
    ) {
        if (!service.appPanelVisible()) return
        service.emitPanelResult(
            displayId,
            OverlayToolkit.panelTexts(OverlayToolkit.panelReadingOrder(anchors, ocrResult)),
            screenshotPath,
            ocrProvenance = ocrResult?.let {
                service.panelOcrProvenance(
                    it, displayId, frameIncludesSystemUi, frameIncludesOwnOverlays,
                )
            },
            backendDisplayName = OverlayToolkit.panelBackendLabel(anchors),
        )
    }

    override fun emitNoText() {
        service.handleNoTextDetected(displayId)
    }
}
