package com.playtranslate.ocr.core

import com.playtranslate.language.TextOrientation

/**
 * Tunable surface of [DefaultGroupingStrategy] — the same algorithm with
 * different values. Defaults REFERENCE the [LayoutAnalyzer] constants (single
 * source of truth), so `GroupingRecipe()` is always byte-identical to
 * production behavior.
 *
 * Contract: every field here is WIRED — it genuinely changes grouping when
 * varied. Constants not yet threaded (e.g. the bare-tier size cap, the base
 * gap multiplier, menu-split parameters) stay out of the recipe until their
 * use sites read it; a field that silently does nothing would make sweep
 * results lies. Lift more constants as sweeps need them.
 */
data class GroupingRecipe(
    /** Page-rhythm bootstrap evidence in the corroborated gap band (see
     *  [LayoutAnalyzer.documentPitch]). Production: on for declared documents
     *  (file import), off for game/live surfaces. */
    val documentPitchPrior: Boolean = false,
    /** Outer edge of the corroborated gap band, in refH/refW units. */
    val bandGapMultiplier: Float = LayoutAnalyzer.BAND_GAP_MULTIPLIER,
    /** Per-pair size-ratio cap for evidence-corroborated merges. */
    val sizeRatioCapCorroborated: Double = LayoutAnalyzer.SIZE_RATIO_CAP_CORROBORATED,
    /** Max candidate extent (vs group union) for the tail-shape scale waiver. */
    val pitchTailMaxExtentRatio: Float = LayoutAnalyzer.PITCH_TAIL_MAX_EXTENT_RATIO,
    /** Textual continuation cues as band corroboration (、/unclosed brackets/
     *  lowercase continuation). */
    val bandTextSeeding: Boolean = LayoutAnalyzer.BAND_TEXT_SEEDING_ENABLED,
) {
    companion object {
        val Default = GroupingRecipe()
    }
}

/**
 * Per-call inputs every grouping strategy receives — derived once by
 * [LayoutAnalyzer.analyze] from the source language and call site, identical
 * for all strategies so columns differ only by their grouping logic.
 */
data class GroupingContext(
    val sourceLang: String,
    /** Full screenshot width in the regions' coordinate space; 0 = unknown
     *  (the default strategy then skips its menu split). Angle-cluster frames
     *  ([LayoutAnalyzer.analyze]'s deskew path) receive the SAME value: deskew
     *  is an isometry, so in-frame extents are genuine pixel lengths and the
     *  width tests stay sound — slightly conservative at steep angles, where
     *  the true room along the baseline is the diagonal. */
    val screenshotWidthInRegionSpace: Float,
    val rtl: Boolean,
    val spacedScript: Boolean,
    val logDecisions: Boolean,
)

/**
 * A final proposed group of regions. [parentLeft]/[parentRight] optionally pin
 * the rendered bounds to a parent block's edges (the menu split sets them so
 * per-row overlays align to the menu's column).
 */
data class ProposedGroup(
    val regions: List<RecognizedRegion>,
    val parentLeft: Int? = null,
    val parentRight: Int? = null,
    /** Non-null for an angle-cluster group ([LayoutAnalyzer.analyze]'s deskew
     *  path): the shared frame its members were grouped in, driving deskewed
     *  reading order / alignment / oriented bounds in `buildLayoutGroup`.
     *  When [frame] is non-null, [parentLeft]/[parentRight] are FRAME-SPACE
     *  u-values (the strategy that produced them saw deskewed rects); the
     *  framed assembly consumes them in the same frame, clamping the in-frame
     *  union before the back-rotation. */
    val frame: AngleFrame? = null,
)

/**
 * A grouping algorithm — the decision middle of [LayoutAnalyzer.analyze]:
 * normalized regions in, final proposed groups out. The shell around it is
 * shared and fixed (upstream text normalization; downstream reading-order
 * join, bounds, orientation vote, alignment classification), so strategies
 * differ ONLY in how lines become groups and their outputs are directly
 * comparable.
 *
 * Implementations besides [DefaultGroupingStrategy] are research instruments:
 * they live in androidTest source (the grouping harness catalog), compile only
 * into the test APK, and graduate into main source deliberately if they win.
 * Ports of other tools' grouping (EasyOCR bbox-grow, PaddleOCR's 1.2h/3h
 * band, Tesseract-style fusion — see docs/ocr-paragraph-assembly-survey.md)
 * implement this interface; mind that their unit conventions differ (their
 * line height is detector-box height, ours is glyph-tight — constants do not
 * transfer 1:1 across engines).
 */
interface GroupingStrategy {
    /** Stable identifier — becomes part of the harness cfg string. */
    val name: String

    fun group(regions: List<RecognizedRegion>, ctx: GroupingContext): List<ProposedGroup>
}

/**
 * The production grouping algorithm, parameterized by [recipe]: orientation
 * partition → reading-order sort → [LayoutAnalyzer.groupBoxesOnePass] walk
 * (pairwise predicate + evidence layer) → source-script filter → menu split.
 * `DefaultGroupingStrategy()` is byte-identical to pre-seam production.
 */
class DefaultGroupingStrategy(
    private val recipe: GroupingRecipe = GroupingRecipe.Default,
) : GroupingStrategy {

    override val name: String = "default"

    override fun group(regions: List<RecognizedRegion>, ctx: GroupingContext): List<ProposedGroup> {
        val (vertical, horizontal) = regions.partition { it.orientation == TextOrientation.VERTICAL }
        val hGroups = LayoutAnalyzer.groupRegions(
            horizontal.sortedBy { it.box.bounds.top }, TextOrientation.HORIZONTAL, ctx, recipe,
        )
        val vGroups = LayoutAnalyzer.groupRegions(
            vertical.sortedByDescending { it.box.bounds.right }, TextOrientation.VERTICAL, ctx, recipe,
        )
        val rawGroups = (hGroups + vGroups).filter { group ->
            group.any { r -> r.text.any { LayoutAnalyzer.isSourceLangChar(it, ctx.sourceLang) } }
        }
        if (rawGroups.isEmpty()) return emptyList()
        return if (ctx.screenshotWidthInRegionSpace > 0f) {
            LayoutAnalyzer.splitMenuGroups(rawGroups, ctx.screenshotWidthInRegionSpace, ctx.logDecisions)
        } else {
            rawGroups.map { ProposedGroup(it) }
        }
    }
}
