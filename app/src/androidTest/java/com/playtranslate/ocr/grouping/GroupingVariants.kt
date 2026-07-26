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
        // Logic variant: a clean-room grouper (adjacency graph + per-chain
        // rhythm segmentation) sharing no decision code with the default. See
        // FlowGraphStrategy's kdoc for the measurements behind every rule.
        Variant("flowgraph", FlowGraphStrategy()),
        // The vertical/comic fixes WITHOUT the punctuation census — the cell of
        // the 2x2 no column had covered, and on host measurement the strongest
        // configuration: it keeps the base's untagged shredding, takes comic
        // 53% -> 66% and menu 58% -> 62%, and leaves Arabic at 5/5, where the
        // census costs 2 of those 5.
        Variant(
            "flowgraph-vert",
            FlowGraphStrategy(
                linkScaleCap = 1.52f,
                rhythmVertical = false,
                name = "flowgraph-vert",
            ),
        ),
        // Same strategy plus ONE change: the block punctuation census as a
        // fourth list path. Kept as its own column rather than folded in, so
        // the shipped `flowgraph` above stays byte-identical and the census
        // pays for itself in a flip table. Host prediction on
        // results-1785001951858, BY KIND: menu 58% -> 65%, and that is all it
        // buys — untagged 81% -> 80% with shredding 5 -> 7, and Arabic 5/5 ->
        // 3/5. Its old headline (choice 3/6 -> 6/6) was an artifact of
        // endsSentence not peeling quotes; the quoted-rows path now takes
        // choice to 6/6 in every column, for a stated reason.
        Variant(
            "flowgraph-census",
            FlowGraphStrategy(listPunctCensus = true, name = "flowgraph-census"),
        ),
        // The census column plus the two fixes for the vertical/comic slice the
        // first device run exposed as a REGRESSION (production and labelstack
        // both pass those seeds): a tighter ruby gate, and no chain-rhythm
        // evidence on vertical partitions. Host prediction on
        // results-1785001951858: comic 53% -> 66% and menu 65% -> 68% over
        // flowgraph-census, at untagged 80% -> 78% (shredding 7 -> 8).
        Variant(
            "flowgraph-census2",
            FlowGraphStrategy(
                listPunctCensus = true,
                linkScaleCap = 1.52f,
                rhythmVertical = false,
                name = "flowgraph-census2",
            ),
        ),
        // Logic variant: production grouping with the menu split replaced by a
        // punctuation profile over capture-wide label stacks. Isolates ONE
        // change — everything upstream of the split is the default's. Host
        // measurement behind it, and its enemy population, in
        // LabelStackStrategy's kdoc.
        Variant("labelstack", LabelStackStrategy()),
        // Surviving ablation: the evidence floor. Retired columns and their
        // answers, from results-1784971719353 (107 seeds), recorded so nobody
        // re-runs them:
        //  - `labelstack-nostacks` (backstop only, no capture-wide detection):
        //    +4 seeds vs production where full labelstack was +7, so the
        //    capture-wide half pays 3 of the 7. Question answered.
        //  - `nomenusplit` (both halves off = production with NO split at all):
        //    -4 seeds, i.e. production's menu split is worth +4 net and this
        //    strategy's replacement is worth +7. Baseline established once.
        // The floor stays a live column because it is the one knob the corpus
        // can still falsify: at 3 rows it scored +8 with ZERO regressions,
        // against the prediction that three punctuation-free RU card
        // descriptions would shred. That prediction was wrong on 107 seeds;
        // punctuation-free prose is the enemy population, so every corpus
        // growth is a fresh chance to be right. Also informs FlowGraph's
        // `listMinRows`.
        Variant("labelstack-rows3", LabelStackStrategy(minStackRows = 3)),
    )
}
