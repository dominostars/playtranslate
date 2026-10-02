package com.playtranslate

import com.playtranslate.capture.CapturedFrame
import com.playtranslate.ocr.ReaderAttribution
import com.playtranslate.ocr.ReaderTranslations
import com.playtranslate.ui.TextBox

/**
 * The presentation strategy for [ReconcilerLiveMode] — the ONLY thing that
 * differs between the live flavors. The mode owns sensing (delivery gate,
 * guards, reconciler, stability hold); a presenter owns what
 * a reconciled region *becomes*: a translation box, furigana annotations, or
 * a panel-only result.
 *
 * ## Anchors
 * The mode's cross-cycle state is a list of ANCHOR [TextBox]es: sourceText +
 * the OCR-region bounds the reconciler pairs against + a non-empty payload in
 * translatedText marking "presented" (empty = retry). For identity
 * presenters (translation, panel) the anchor IS the display box. A
 * non-identity presenter (furigana) returns synthetic, never-rendered
 * anchors at the group rect and maps them to annotation boxes via
 * [displayBoxesFor] — display geometry must not perturb reconciliation.
 */
interface LivePresenter {

    val flavor: OverlayFlavor

    /** False = panel-only: the mode never paints overlay windows (anchors
     *  still back hold-to-preview via [displayBoxesFor]), and the mode is
     *  stream-agnostic — a presenter that paints nothing cannot be hurt by a
     *  contaminated stream. True = overlay-painting: only valid on a CLEAN
     *  (task-scoped) stream; the cycle-start guard rebuilds if the verdict
     *  is reset mid-session (consent teardown). */
    val rendersOverlays: Boolean

    /**
     * Turn [work] (regions the reconciler wants (re)presented — RETRANSLATE
     * and NEW alike, post-hold) into their anchor boxes, 1:1 with [work].
     * May suspend (MT, tokenizer, the reader's translations); runs serially
     * inside the cycle, which is the loop's backpressure. [onPartial] may be
     * invoked any number of times with provisional anchors, each time every
     * anchor as known so far (skeletons, cache fills, the reader's
     * translations as they arrive), for a render pass before slow work
     * completes; the return value is final.
     *
     * [reader] is the frame's reader's translations keyed by position in
     * [work] ([readerForRegions]), null when the reader made none or no
     * position can take one. A presenter paints first and only then waits:
     * what arrived with the OCR ([ReaderTranslations.ready]) and the cache
     * fills go into its first [onPartial], so live mode's first paint never
     * waits on a server. It waits only when a position the cache has no
     * entry for can still take one of the reader's translations, since the
     * cache already answers every other position; while it waits, each later
     * translation repaints as it arrives, over a cache fill too.
     * [OverlayToolkit.translatePlaceholders] does the waiting, and uses a
     * position's text in place of the cache and the waterfall when the pair
     * still matches.
     */
    suspend fun present(
        work: List<ScanlineReconciler.Region>,
        frame: CapturedFrame,
        cropLeft: Int,
        cropTop: Int,
        onPartial: suspend (List<TextBox>) -> Unit,
        reader: ReaderTranslations? = null,
    ): List<TextBox>

    /** Display boxes for one anchor. Identity by default. */
    fun displayBoxesFor(anchor: TextBox): List<TextBox> = listOf(anchor)

    /** Anchors leaving the displayed set (REMOVE verdicts, resets) — drop any
     *  derived per-anchor state. */
    fun onAnchorsDropped(anchors: Collection<TextBox>) {}

    /** Applied-state emission (panel policy). [ocrResult] and the two frame
     *  facts ([frameIncludesSystemUi], [frameIncludesOwnOverlays]) support
     *  provenance for presenters whose panel result is the product — they
     *  persist with the cached screenshot so a re-OCR re-crops the same
     *  pixels and re-blacks our own chrome out of raw frames. Suspends, and
     *  runs serially inside the cycle like [present] — a presenter whose
     *  panel emission translates (furigana → MT) is part of the loop's
     *  backpressure, exactly as the legacy modes awaited their panel sends.
     *  [screenshotPath] performs a synchronous JPEG write of the cycle's
     *  frame — invoke it lazily, only on paths that actually emit (a
     *  presenter that ignores it, or gates on panel visibility, must not
     *  pay the write), and only during this call: the frame backing it is
     *  recycled when the cycle ends. [attribution] is who read the cycle's
     *  frame (the reading's [ReaderAttribution]), for the provenance's
     *  "Scanned by"; null derives it from [ocrResult]'s device engine
     *  ([com.playtranslate.ocr.DeviceFrameReader.attributionOf]). */
    suspend fun emitApplied(
        anchors: List<TextBox>,
        ocrResult: OcrManager.OcrResult?,
        frameIncludesSystemUi: Boolean,
        frameIncludesOwnOverlays: Boolean,
        screenshotPath: () -> String?,
        attribution: ReaderAttribution? = null,
    )

    /** The no-text outcome (panel/overlay policy). Deliberately frameless,
     *  unlike [emitApplied]: the panel's no-text status offers an OCR-switch
     *  gear, but while live mode is running the LOOP is what acts on a switch
     *  (a fresh look at the current screen), so no frame needs pinning here —
     *  see [CaptureService.emitLiveNoText]. [attribution] names who looked
     *  (the cycle's reading's); null names the device engine selected for the
     *  source language. */
    fun emitNoText(attribution: ReaderAttribution? = null)
}
