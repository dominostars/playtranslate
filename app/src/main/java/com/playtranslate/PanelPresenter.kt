package com.playtranslate

import com.playtranslate.capture.CapturedFrame
import com.playtranslate.model.TranslationResult
import com.playtranslate.ui.TextBox

/**
 * [LivePresenter] for In-App-Only live mode: NO overlay windows — the in-app
 * panel is the product. Regions are translated (cache-first) into plain
 * anchor boxes that back hold-to-preview, and every applied cycle emits a
 * full [TranslationResult] WITH provenance (preserving the panel's re-OCR
 * gear, which the deleted InAppOnlyMode carried via its one-shot pipeline).
 *
 * Because nothing is painted, contamination is irrelevant
 * ([rendersOverlays] = false): this presenter runs on ANY stream kind
 * and BOTH backends. On the accessibility backend there is no delivery
 * signal, so the mode's gate never parks and the loop degrades to exactly
 * the interval polling the old mode did — minus its whole-text-nuke dedup
 * (the reconciler re-translates only regions that actually changed).
 */
class PanelPresenter(
    private val service: CaptureService,
    private val displayId: Int,
) : LivePresenter {

    override val flavor: OverlayFlavor = OverlayFlavor.IN_APP_ONLY
    override val rendersOverlays: Boolean = false

    override suspend fun present(
        work: List<ScanlineReconciler.Region>,
        frame: CapturedFrame,
        cropLeft: Int,
        cropTop: Int,
        onPartial: suspend (List<TextBox>) -> Unit,
    ): List<TextBox> {
        // No color sampling, no skeleton pass — nothing renders on screen.
        val texts = work.map { it.text }
        val placeholders = work.map { r ->
            val (cMin, cMean) = ReadingArbiter.scoreOf(r.group)
            TextBox(
                "", r.bounds,
                lineCount = r.lineCount,
                sourceText = r.text,
                orientation = r.orientation,
                alignment = r.alignment,
                angleDeg = r.angleDeg,
                orientedWidth = r.orientedWidth,
                orientedHeight = r.orientedHeight,
                sourceConfMin = cMin,
                sourceConfMean = cMean,
            )
        }
        return OverlayToolkit.translatePlaceholders(service, placeholders, texts)
    }

    /** Last emitted (original, translated, backend label) — reposition-only
     *  cycles re-enter with identical text, and the panel must not churn a
     *  fresh timestamp for pure scroll drift (the deleted mode's whole-text
     *  dedup suppressed this; review note). The backend label rides the key
     *  so the dedup covers the full emitted payload — an attribution change
     *  alone must re-emit, whatever future path produces one. ocrProvenance
     *  stays OUT deliberately: its region rects change on every reposition
     *  and would defeat the dedup's whole purpose (most-recent staleness
     *  accepted, same policy as the pinhole tier's panelProvenance). */
    private var lastEmitted: Triple<String, String, String?>? = null

    override suspend fun emitApplied(
        anchors: List<TextBox>,
        ocrResult: OcrManager.OcrResult?,
        frameIncludesSystemUi: Boolean,
        frameIncludesOwnOverlays: Boolean,
        screenshotPath: () -> String?,
    ) {
        val texts = OverlayToolkit.panelTexts(
            OverlayToolkit.panelReadingOrder(anchors, ocrResult),
        )
        val backendLabel = OverlayToolkit.panelBackendLabel(anchors)
        val key = Triple(texts.originalText, texts.translatedText, backendLabel)
        if (key == lastEmitted) return
        lastEmitted = key
        // Screenshot write only past the dedup — a reposition-only cycle
        // must not pay the JPEG.
        service.emitPanelResult(
            texts, screenshotPath(),
            ocrProvenance = ocrResult?.let {
                service.panelOcrProvenance(
                    it, displayId, frameIncludesSystemUi, frameIncludesOwnOverlays,
                )
            },
            backendDisplayName = backendLabel,
        )
    }

    override fun emitNoText() {
        lastEmitted = null
        service.emitLiveNoText(displayId)
    }
}
