package com.playtranslate.ocr.grouping

import com.playtranslate.ocr.core.DefaultGroupingStrategy
import com.playtranslate.ocr.core.FlowGraphStrategy
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

    class Variant(
        val name: String,
        val strategy: GroupingStrategy,
        /** Shell slant-cluster admission cap, swept by the angle columns;
         *  every other column runs the production default. */
        val angleToleranceDeg: Float = com.playtranslate.ocr.core.DeskewGeometry.DEFAULT_CLUSTER_CAP_DEG,
    )

    val catalog: List<Variant> = listOf(
        Variant("docpitch-off", DefaultGroupingStrategy(GroupingRecipe.Default)),
        Variant(
            "docpitch-on",
            DefaultGroupingStrategy(GroupingRecipe.Default.copy(documentPitchPrior = true)),
        ),
        // FLOWGRAPH, now main source (com.playtranslate.ocr.core) — graduated
        // 2026-07-26 on the Thor pass results-1785050508550. Default
        // construction IS the shipping configuration, so this column tracks
        // what the app would do rather than a research setting.
        Variant("flowgraph", FlowGraphStrategy()),
        // The one open question left in it: the block punctuation census as a
        // fourth list path. Off in production because by kind it buys menu
        // points only (Thor: 50% -> 56%) and pays on the dominant untagged
        // slice, taking shredding 16 -> 21 and costing 2 of 5 Arabic seeds. Its
        // evidence is also the unreliable kind — absent on 39% of must-merge
        // blocks, and 5.6% unstable across engines, worst in zh/ja/en.
        Variant(
            "flowgraph-census",
            FlowGraphStrategy(listPunctCensus = true, name = "flowgraph-census"),
        ),
        // Retired columns and their answers, recorded so nobody re-runs them:
        //  - `flowgraph-vert` (ruby gate 1.65 + no vertical chain rhythm) WON
        //    and is now the default above, so the column is redundant.
        //  - the pre-vert baseline (gate 1.80, vertical rhythm on) read
        //    88%/46%/53% untagged/menu/comic on Thor against the winner's
        //    88%/50%/60%, at 17 shredded against 16.
        //  - `flowgraph-census2` (census + vert) is `flowgraph-census` above
        //    now that vert is the default.
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
        // Angle-tolerance sweep: production strategy at each slant-cluster
        // admission cap (default 4° rides the flowgraph column). Only frames
        // with ≥2 rotated regions can differ — upright frames take the
        // byte-identical fast path in every column.
        Variant("flowgraph-ang2", FlowGraphStrategy(), angleToleranceDeg = 2f),
        Variant("flowgraph-ang3", FlowGraphStrategy(), angleToleranceDeg = 3f),
        Variant("flowgraph-ang6", FlowGraphStrategy(), angleToleranceDeg = 6f),
        Variant("flowgraph-ang8", FlowGraphStrategy(), angleToleranceDeg = 8f),
    )
}
