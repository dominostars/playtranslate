package com.playtranslate.ocr.core

import com.playtranslate.language.OcrBackend
import java.io.Closeable

/**
 * The single universal OCR contract: turn an image into recognized regions.
 *
 * This is the Composite pattern's component interface. BOTH a single-model leaf
 * (e.g. `MlKitOcr` — one model that detects+recognizes) AND a detector+recognizer
 * composite (`DetectThenRecognize`, i.e. "PaddleOCR") implement it via the SAME
 * interface (not inheritance). Callers — the `OcrPipeline` — only ever talk to
 * `OcrEngine`, so adding/swapping an engine is constructing a different tree.
 *
 * Contract:
 *  - Returns [RecognizedRegion]s in ORIGINAL input-bitmap coordinates, PRE-layout
 *    (paragraph grouping is the pipeline's shared [LayoutAnalyzer] stage, run
 *    after this call). Composites produce regions; the pipeline owns layout.
 *  - [close] releases native resources and walks the whole tree depth-first.
 *    Closing a non-thread-safe subtree (an MNN session) MUST serialize against
 *    in-flight [recognize] calls — see the composite implementations.
 */
interface OcrEngine : Closeable {
    val capabilities: OcrCapabilities

    suspend fun recognize(image: OcrImage): List<RecognizedRegion>
}

/**
 * An [OcrEngine] resolved for a source language, paired with the [backend] that
 * produced it. The registry's `engineFor` is the ONE place the real
 * selected → ML-Kit-floor → empty-engine fallback is observable, so it reports
 * which backend actually ran here instead of letting callers re-derive it (which
 * would drift from the resolution rule). [backend] is null only for the no-OCR
 * empty engine; callers map it to a user-facing label / provenance.
 */
data class ResolvedOcr(val engine: OcrEngine, val backend: OcrBackend?)
