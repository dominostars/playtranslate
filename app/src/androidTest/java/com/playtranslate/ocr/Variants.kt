package com.playtranslate.ocr

import com.playtranslate.InvertMode
import com.playtranslate.OcrPreprocessingRecipe

/**
 * Catalog of preprocessing recipes the golden-set sweep evaluates. Add or
 * remove entries here without rebuilding the production app — this file lives
 * only in the `androidTest` sourceset.
 *
 * Each entry has a short name used as a column in the report and as the
 * recipe argument to [com.playtranslate.OcrManager.recognise]. The current
 * catalog is shaped by the investigation that produced the harness:
 *
 *  * The original bug was `lin2.0+invert` confidently dropping a kanji that
 *    `raw` and `sigk10` preserved on a single dialogue screen. We needed to
 *    test if a softer linear factor or a sigmoid contrast curve would fix
 *    it without breaking other screens.
 *  * `InvertMode.ALWAYS`/`NEVER` variants are ablations — we know the
 *    auto-invert detector misfires on some letterboxed light-background
 *    screens, and we wanted to measure how much the auto detector earns
 *    its keep vs pinning invert on/off.
 */
internal object Variants {

    data class NamedRecipe(val name: String, val recipe: OcrPreprocessingRecipe)

    val all: List<NamedRecipe> = listOf(
        // Baseline: no preprocessing. ML Kit reading the raw screen capture
        // directly. Useful as a floor — if a preprocessing recipe is worse
        // than raw on a screen, preprocessing is hurting more than helping.
        NamedRecipe("raw", OcrPreprocessingRecipe.Raw),

        // Production recipe (matches OcrPreprocessingRecipe.Default).
        // Sigmoid k=7 with auto-invert — chosen after the harness measured it
        // producing ~40% fewer translation-affecting failures than the prior
        // lin2.0 on this golden set.
        NamedRecipe("sigk7-auto", OcrPreprocessingRecipe.SigmoidContrast(7f, InvertMode.AUTO)),

        // Previous production. Kept in the sweep so we can detect if a
        // future change accidentally regresses to its profile.
        NamedRecipe("lin2.0-auto", OcrPreprocessingRecipe.LinearContrast(2.0f, InvertMode.AUTO)),

        // Other sigmoid steepness. k=10 is near-binary but with a smooth
        // midpoint transition (no hard 0/255 clamping that linear contrast
        // applies). Comparison point for k=7.
        NamedRecipe("sigk10-auto", OcrPreprocessingRecipe.SigmoidContrast(10f, InvertMode.AUTO)),

        // Invert ablations on sigk10. Test whether pinning invert on or off
        // beats the auto-detector (which we have evidence misfires on
        // letterboxed light-background screens).
        NamedRecipe("sigk10-always", OcrPreprocessingRecipe.SigmoidContrast(10f, InvertMode.ALWAYS)),
        NamedRecipe("sigk10-never", OcrPreprocessingRecipe.SigmoidContrast(10f, InvertMode.NEVER)),

        // Linear without invert — isolates the contribution of the auto-invert
        // step from the linear contrast in the previous-production recipe.
        NamedRecipe("lin2.0-never", OcrPreprocessingRecipe.LinearContrast(2.0f, InvertMode.NEVER)),

        // Softer linear contrast. The diagnostic-pass sweep on the original
        // bug screen showed factors 1.1–1.9 all preserved the kanji that
        // 2.0 dropped, so 1.5 is a representative "softer" point.
        NamedRecipe("lin1.5-auto", OcrPreprocessingRecipe.LinearContrast(1.5f, InvertMode.AUTO)),
    )
}
