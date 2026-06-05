package com.playtranslate.language

import android.content.Context
import android.util.Log
import com.playtranslate.ocr.registry.OcrModelManager
import com.playtranslate.preloadMlKitFallbackModels
import com.playtranslate.translation.bergamot.BergamotWarmup
import kotlin.coroutines.cancellation.CancellationException

private const val TAG = "ActivePairPriming"

/**
 * Best-effort provisioning of the offline assets a *selected* (source, target)
 * pair needs beyond its dictionary/gloss packs: the offline translation
 * model(s) and the source's default OCR recognizer.
 *
 * Shared by [com.playtranslate.ui.LanguageSetupActivity]'s source-selection
 * flow and [PackUpgradeOrchestrator]'s post-upgrade priming so the two cannot
 * disagree on what "priming the active pair" means. That disagreement was a
 * real bug: a forced pack upgrade re-provisioned translation but silently
 * skipped OCR because the orchestrator hand-listed only the translation step.
 *
 * Both steps are best-effort and idempotent. Translation prefers Bergamot (the
 * default offline tier) and falls back to ML Kit for unsupported pairs or
 * download failures; OCR downloads the source's default recognizer and leaves
 * ML Kit as the floor on failure. Neither blocks the flow — the dictionary and
 * online backends don't depend on them. Cancellation propagates.
 *
 * Deliberately does NOT touch dictionary/gloss packs or warm the source engine.
 * Those have call-site-specific lifecycles (install/swap with handle eviction,
 * preload with corrupt-pack rollback) and stay where they are.
 *
 * @param sourceId the selected source language (drives both the OCR recognizer
 *   and, via its [SourceLanguageProfiles] translation code, the translation
 *   models — e.g. Traditional Chinese resolves to "zh" for the Simplified-only
 *   Bergamot/ML Kit models).
 * @param ocrRequired when true, the source's OCR recognizer is mandatory (a
 *   no-floor language like Russian has no ML Kit fallback): if the pack isn't
 *   installed after the best-effort download, throw so the caller aborts the
 *   selection. Default false keeps OCR best-effort (the pack-upgrade path).
 * @return whether the offline translation path is ready (Bergamot loaded, or
 *   all ML Kit fallback models present). Callers surface this how they like —
 *   a toast in setup; logged-only during a pack upgrade.
 */
suspend fun primeActivePair(
    ctx: Context,
    sourceId: SourceLangId,
    targetLang: String,
    onWarmup: (index: Int, count: Int, received: Long, total: Long) -> Unit = { _, _, _, _ -> },
    onOcr: (received: Long, total: Long) -> Unit = { _, _ -> },
    ocrRequired: Boolean = false,
): Boolean {
    val app = ctx.applicationContext
    val translationCode = SourceLanguageProfiles[sourceId].translationCode

    // Translation models: Bergamot first, ML Kit fallback. Skips already-
    // installed Bergamot directions internally; a smoke test still loads the
    // native engine to prove the pair actually runs before suppressing ML Kit.
    val translationReady =
        if (BergamotWarmup.ensureForPair(app, translationCode, targetLang, onWarmup)) true
        else preloadMlKitFallbackModels(translationCode, targetLang)

    // Source OCR recognizer: best-effort. A network failure leaves ML Kit as
    // the OCR floor and must not abort the flow; a user cancel propagates.
    //
    // Accepted tradeoff (not a bug): if this download is cancelled or the app
    // is backgrounded mid-fetch, the pack stays absent and engineForSelected
    // resolves to the ML Kit floor until the source is next selected — which
    // re-runs this and re-fetches. The launch reclaim sweeps OCR orphans but
    // does NOT re-download (only OcrModelManager.sweepOrphans is wired at
    // launch, not applyDownloads), so recovery is on re-selection, not at
    // launch. The persisted backend token is not the lever here: selectedBackend
    // resolves to the same default with or without it, and the fallback is
    // driven solely by the missing pack. Graceful + recoverable, narrow trigger
    // (the active source's pack must already be missing/stale when cancelled),
    // and pre-existing in the source-selection flow. Reviewed + accepted.
    try {
        OcrModelManager.downloadDefaultForSource(app, sourceId, onOcr)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "default OCR download failed for ${sourceId.code}; using ML Kit floor", e)
    }

    // For a no-floor source (Russian/Cyrillic) OCR is mandatory — there is no ML
    // Kit fallback. downloadDefaultForSource is best-effort (it logs failures
    // rather than throwing), so enforce the requirement as a post-condition: if
    // the pack still isn't installed, throw so the caller's catch surfaces an
    // error and does NOT persist the selection. The dictionary stays installed;
    // isFullyInstalled then reads "not installed" until a retry lands the pack.
    if (ocrRequired && !OcrModelManager.isRequiredOcrInstalled(app, sourceId)) {
        throw IllegalStateException(
            "required OCR pack missing for ${sourceId.code} after download attempt"
        )
    }

    return translationReady
}
