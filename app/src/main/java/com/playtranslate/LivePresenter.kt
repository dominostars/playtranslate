package com.playtranslate

import com.playtranslate.capture.CapturedFrame
import com.playtranslate.ui.TextBox

/**
 * The presentation strategy for [ReconcilerLiveMode] — the ONLY thing that
 * differs between the live flavors. The mode owns sensing (delivery gate,
 * guards, reconciler, stability hold, misroute nets); a presenter owns what
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

    /** False = panel-only: the mode never paints overlay windows; anchors
     *  still back hold-to-preview via [displayBoxesFor]. */
    val rendersOverlays: Boolean

    /** True = only valid on a CLEAN (task-scoped) stream: the mode demotes
     *  and rebuilds on any other kind, and the misroute nets (echo tripwire,
     *  thrash detector) arm. False = stream-agnostic — the presenter paints
     *  nothing, so contamination is irrelevant and the nets stay cold. */
    val requiresCleanStream: Boolean

    /**
     * Turn [work] (regions the reconciler wants (re)presented — RETRANSLATE
     * and NEW alike, post-hold) into their anchor boxes, 1:1 with [work].
     * May suspend (MT, tokenizer); runs serially inside the cycle — this is
     * the loop's backpressure. [onPartial] may be invoked once with
     * provisional anchors (skeletons / cache fills) for an early render pass
     * before slow work completes; the return value is final.
     */
    suspend fun present(
        work: List<ScanlineReconciler.Region>,
        frame: CapturedFrame,
        cropLeft: Int,
        cropTop: Int,
        onPartial: suspend (List<TextBox>) -> Unit,
    ): List<TextBox>

    /** Display boxes for one anchor. Identity by default. */
    fun displayBoxesFor(anchor: TextBox): List<TextBox> = listOf(anchor)

    /** Anchors leaving the displayed set (REMOVE verdicts, resets) — drop any
     *  derived per-anchor state. */
    fun onAnchorsDropped(anchors: Collection<TextBox>) {}

    /** Applied-state emission (panel policy). [ocrResult] and
     *  [frameIncludesSystemUi] support provenance for presenters whose panel
     *  result is the product. */
    fun emitApplied(
        anchors: List<TextBox>,
        ocrResult: OcrManager.OcrResult?,
        frameIncludesSystemUi: Boolean,
        screenshotPath: String?,
    )

    /** The no-text outcome (panel/overlay policy). */
    fun emitNoText()
}
