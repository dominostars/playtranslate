package com.playtranslate.ocr.grouping

import com.playtranslate.ocr.core.DefaultGroupingStrategy
import com.playtranslate.ocr.core.GroupingRecipe
import com.playtranslate.ocr.core.GroupingStrategy

/**
 * The grouping-configuration catalog for [OcrGroupingHarnessTest] — the
 * `Variants.kt` pattern applied to grouping. Every entry runs against the SAME
 * recognition output per (seed, engine), so column deltas are attributable
 * purely to the grouping configuration. Grouping costs milliseconds; entries
 * are effectively free next to the OCR pass.
 *
 * Two kinds of entry:
 *  - **Value variants** — [DefaultGroupingStrategy] with a modified
 *    [GroupingRecipe] (`GroupingRecipe.Default.copy(...)`). Threshold history
 *    is data, so superseded configurations (e.g. a pre-raise gap value) can
 *    stay listed forever at no code cost.
 *  - **Logic variants** — any other [GroupingStrategy] implementation. Ports
 *    of foreign grouping mechanisms (EasyOCR bbox-grow, PaddleOCR's gap band,
 *    Tesseract-style fusion — specs in docs/ocr-paragraph-assembly-survey.md)
 *    are implemented HERE in androidTest source: they compile only into the
 *    test APK and never ship. Label ports honestly ("paddle-like"): they are
 *    reimplementations, and unit conventions differ per engine geometry.
 *
 * Names become the harness cfg suffix (`<engineToken>/<name>`). The report
 * maps each seed's production cell via its `# surface:` directive to
 * `docpitch-off` (screen) or `docpitch-on` (import) — keep those two entries
 * present under exactly those names.
 */
object GroupingVariants {

    class Variant(val name: String, val strategy: GroupingStrategy)

    val catalog: List<Variant> = listOf(
        Variant("docpitch-off", DefaultGroupingStrategy(GroupingRecipe.Default)),
        Variant(
            "docpitch-on",
            DefaultGroupingStrategy(GroupingRecipe.Default.copy(documentPitchPrior = true)),
        ),
    )
}
