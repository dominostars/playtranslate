package com.playtranslate

import com.playtranslate.capture.CapturedFrame
import com.playtranslate.model.TextSegments
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
            TextBox(
                "", r.bounds,
                lineCount = r.lineCount,
                sourceText = r.text,
                orientation = r.orientation,
                alignment = r.alignment,
            )
        }
        return OverlayToolkit.translatePlaceholders(service, placeholders, texts)
    }

    /** Last emitted (original, translated) — reposition-only cycles re-enter
     *  with identical text, and the panel must not churn a fresh timestamp
     *  for pure scroll drift (the deleted mode's whole-text dedup suppressed
     *  this; review note). */
    private var lastEmitted: Pair<String, String>? = null

    override fun emitApplied(
        anchors: List<TextBox>,
        ocrResult: OcrManager.OcrResult?,
        frameIncludesSystemUi: Boolean,
        screenshotPath: String?,
    ) {
        val ordered = OverlayToolkit.panelReadingOrder(anchors, ocrResult)
        val originalText = ordered.filter { it.sourceText.isNotEmpty() }
            .joinToString("\n") { it.sourceText }
        val translatedText = ordered.filter { it.translatedText.isNotEmpty() }
            .joinToString("\n\n") { it.translatedText }
        val segments = TextSegments.ofLines(ordered.map { it.sourceText })
        if (originalText to translatedText == lastEmitted) return
        lastEmitted = originalText to translatedText
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())

        service.emitResult(TranslationResult(
            originalText = originalText,
            segments = segments,
            translatedText = translatedText,
            timestamp = timestamp,
            screenshotPath = screenshotPath,
            ocrProvenance = ocrResult?.let {
                service.panelOcrProvenance(it, displayId, frameIncludesSystemUi)
            },
            langContext = Prefs(service).langContext(),
        ))
    }

    override fun emitNoText() {
        lastEmitted = null
        service.emitLiveNoText()
    }
}
