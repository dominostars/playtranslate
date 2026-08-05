package com.playtranslate

import android.graphics.Rect
import com.playtranslate.capture.CapturedFrame
import com.playtranslate.language.SourceLanguageEngines
import com.playtranslate.ui.TextBox

/**
 * [LivePresenter] for live furigana/pinyin on a CLEAN stream: regions become
 * reading annotations positioned above (or beside, for vertical text) their
 * lines — the on-screen product is the source-language engine's annotator,
 * no machine translation (MT happens only in the panel emission, exactly as
 * the legacy tier does it — see [emitApplied]). The legacy [FuriganaMode]
 * keeps serving contaminated streams (≤ API 33 / whole-display), where its
 * cleanRef/patching machinery is still needed; this presenter exists
 * precisely because a task-scoped stream makes all of that unnecessary.
 *
 * ## Anchors vs display boxes
 * Annotations render OUTSIDE the OCR group's rect, so they must never be what
 * the reconciler pairs against ([LivePresenter] kdoc). The anchor is a
 * synthetic, never-rendered box at the GROUP rect whose payload is the joined
 * readings (non-empty even when a region has nothing to annotate —
 * [NO_ANNOTATIONS] — or an unannotated region would look like a failed retry
 * and re-parse every cycle). [displayBoxesFor] maps anchors to their
 * annotation boxes; reposition copies (scroll drift) lose map identity, so
 * lookup falls back to same-text/overlapping-bounds migration and OFFSETS the
 * annotations by the drift — annotations track scrolling text without a
 * re-parse.
 */
class FuriganaPresenter(
    private val service: CaptureService,
    private val displayId: Int,
) : LivePresenter {

    override val flavor: OverlayFlavor = OverlayFlavor.FURIGANA
    override val rendersOverlays: Boolean = true

    /** Anchor → annotation display boxes. Identity-keyed; see class kdoc for
     *  the reposition-copy migration. */
    private val display = java.util.IdentityHashMap<TextBox, List<TextBox>>()

    override suspend fun present(
        work: List<ScanlineReconciler.Region>,
        frame: CapturedFrame,
        cropLeft: Int,
        cropTop: Int,
        onPartial: suspend (List<TextBox>) -> Unit,
    ): List<TextBox> {
        val engine = SourceLanguageEngines.get(service, Prefs(service).sourceLangId)
        return work.map { region ->
            val group = region.group
            val boxes = if (group != null) {
                OverlayToolkit.buildFuriganaBoxesForGroup(
                    group, engine, service.furiganaPaint,
                    debugTiming = Prefs(service).debugLiveMode,
                )
            } else {
                emptyList()
            }
            val (cMin, cMean) = ReadingArbiter.scoreOf(group)
            val anchor = TextBox(
                translatedText = boxes.joinToString("") { it.translatedText }
                    .ifEmpty { NO_ANNOTATIONS },
                bounds = region.bounds,
                lineCount = region.lineCount,
                sourceText = region.text,
                orientation = region.orientation,
                alignment = region.alignment,
                sourceConfMin = cMin,
                sourceConfMean = cMean,
            )
            display[anchor] = boxes
            anchor
        }
    }

    override fun displayBoxesFor(anchor: TextBox): List<TextBox> {
        display[anchor]?.let { return it }
        // A repositioned KEEP is a copy — migrate the original's entry and
        // shift the annotations by the drift so they track the moving text.
        val orig = display.keys.firstOrNull {
            it.sourceText == anchor.sourceText && Rect.intersects(it.bounds, anchor.bounds)
        } ?: return emptyList()
        val dx = anchor.bounds.left - orig.bounds.left
        val dy = anchor.bounds.top - orig.bounds.top
        val moved = display.remove(orig)!!.map {
            it.copy(bounds = Rect(it.bounds).apply { offset(dx, dy) })
        }
        display[anchor] = moved
        return moved
    }

    override fun onAnchorsDropped(anchors: Collection<TextBox>) {
        anchors.forEach { display.remove(it) }
    }

    /** Panel parity with the legacy [FuriganaMode] (2026-07-10 review,
     *  finding 3 — decided for parity): the frame's OCR result goes to the
     *  panel for MT while the annotations overlay the game, so dual-screen
     *  furigana behaves identically on both tiers — tier routing is
     *  invisible to the user and must stay that way. The visibility gate
     *  runs HERE (before the screenshot JPEG is written) as well as inside
     *  [CaptureService.translateAndSendToPanel]; awaiting the MT keeps the
     *  legacy mode's serial backpressure. */
    override suspend fun emitApplied(
        anchors: List<TextBox>,
        ocrResult: OcrManager.OcrResult?,
        frameIncludesSystemUi: Boolean,
        frameIncludesOwnOverlays: Boolean,
        screenshotPath: () -> String?,
    ) {
        if (ocrResult == null || !service.appPanelVisible()) return
        service.translateAndSendToPanel(
            ocrResult, screenshotPath(), displayId, frameIncludesSystemUi,
            frameIncludesOwnOverlays,
        )
    }

    override fun emitNoText() {
        service.handleNoTextDetected(displayId)
    }

    private companion object {
        /** Payload marker for "parsed, nothing to annotate" — keeps the
         *  anchor's translatedText non-empty (empty = retry semantics). */
        const val NO_ANNOTATIONS = "·"
    }
}
