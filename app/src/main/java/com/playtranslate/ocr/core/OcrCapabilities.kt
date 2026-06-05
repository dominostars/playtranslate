package com.playtranslate.ocr.core

/** Which text orientations a component can recognize. */
enum class OcrOrientationSupport { HORIZONTAL_ONLY, VERTICAL_ONLY, BOTH }

/**
 * Static description of what an [OcrEngine] / [TextDetector] / [TextRecognizer]
 * can do and how it must be driven. Composites ([com.playtranslate.ocr.composites])
 * compute their capabilities from their children **per composite type** — never
 * a blanket AND — because, e.g., a future `RefineText` emits char boxes from its
 * refiner even though its base lacks them, while a `FallbackChain` must take the
 * conservative intersection.
 *
 * The capability is the conservative *envelope* an engine advertises; an
 * individual [RecognizedRegion] carries ground truth (its own `chars`/`angleDeg`).
 * Downstream (drag-lookup, furigana) reads these to degrade gracefully — e.g.
 * [emitsCharBoxes] == false → proportional fallback.
 */
data class OcrCapabilities(
    val orientation: OcrOrientationSupport,
    /** Emits per-character boxes ([CharBox]). False → proportional drag/furigana fallback. */
    val emitsCharBoxes: Boolean,
    /** Emits per-element boxes ([ElementBox]). False → segments fall back to line level. */
    val emitsElementBoxes: Boolean,
    /** A recognizer that wants whole bubble crops (the detector boxes get clustered first). */
    val wholeRegionInput: Boolean,
    /** Safe to invoke concurrently. False (e.g. a single MNN session) → the pipeline serializes this subtree. */
    val threadSafe: Boolean,
    /** Does its own preprocessing; the pipeline must NOT apply an OcrPreprocessingRecipe and must pass the original bitmap. */
    val selfPreprocesses: Boolean,
    /** Detector emits sub-line (e.g. per-word) boxes that must be assembled into
     *  lines before recognition (PaddleOCR DBNet on spaced scripts). Read only by
     *  [com.playtranslate.ocr.composites.DetectThenRecognize], which runs
     *  LineAssembler when set. */
    val emitsSubLineBoxes: Boolean,
)
