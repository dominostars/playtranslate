package com.playtranslate.ocr.core

import java.io.Closeable

/**
 * Recognizes the text inside one already-detected region. The other half of a
 * [com.playtranslate.ocr.composites.DetectThenRecognize] composite. Examples:
 * PaddleOCR CRNN/SVTR (line-level), manga-ocr (whole-region).
 *
 * The composite owns the loop over detected regions (and the cancellation
 * checkpoint between them), so this is per-region. Returns null when the crop
 * yields no usable text (empty/garbage), which the composite filters out.
 *
 * If [OcrCapabilities.wholeRegionInput] is true, the composite first clusters
 * detector boxes into whole-bubble [DetectedRegion]s (via [LayoutAnalyzer]) and
 * the returned [RecognizedRegion] should carry [RegionOrigin.WHOLE_REGION];
 * otherwise it recognizes one line per detected region ([RegionOrigin.LINE]).
 */
interface TextRecognizer : Closeable {
    val capabilities: OcrCapabilities

    suspend fun recognize(image: OcrImage, region: DetectedRegion): RecognizedRegion?
}
