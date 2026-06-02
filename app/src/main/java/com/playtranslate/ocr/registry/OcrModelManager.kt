package com.playtranslate.ocr.registry

import android.content.Context
import com.playtranslate.Prefs
import com.playtranslate.language.LanguagePackStore
import com.playtranslate.language.OcrBackend
import com.playtranslate.language.SourceLangId
import com.playtranslate.language.SourceLanguageProfiles
import com.playtranslate.translation.llm.OnDeviceLlmDownloader

/**
 * The OCR model-pack reconciler. The desired on-disk pack set is a PURE function
 * of (installed source languages × chosen backend); pack SHARING is expressed
 * solely by a shared catalog key (ja/zh/en all → "paddle-rec-cjk"), so deletion
 * safety is automatic set-math — no refcounts.
 *
 * Split per the lifecycle contract: [plan] is pure (JVM-testable); [applyDownloads]
 * is the suspend/IO effect (additive, safe to run anytime); [sweepOrphans] deletes
 * orphans and MUST run only at quiescence (no live session), wired in Phase 3.
 * Nothing here closes a live native session — selection only mutates Prefs, and the
 * registry resolves fresh on the next `engineFor`.
 */
object OcrModelManager {

    data class Plan(
        /** Packs every installed language's chosen backend needs. */
        val required: Set<String>,
        /** required − installed. */
        val toDownload: Set<String>,
        /** installed − required (orphans; swept only at quiescence). */
        val toDelete: Set<String>,
    )

    /** PURE: desired packs = ⋃ chosen backend's packKeys over installed languages. */
    fun plan(
        installedLangs: Set<SourceLangId>,
        selectedBackend: (SourceLangId) -> OcrBackend,
        installedPacks: Set<String>,
    ): Plan {
        val required = installedLangs.flatMapTo(HashSet()) { selectedBackend(it).packKeys }
        return Plan(required, required - installedPacks, installedPacks - required)
    }

    /** Every OCR pack key the app knows about (single source of truth = the
     *  profiles' `ocrBackends`), so the on-disk universe never drifts from the keys. */
    val ALL_PACK_KEYS: Set<String> =
        SourceLangId.entries.flatMapTo(HashSet()) { id ->
            SourceLanguageProfiles.forCode(id.code)?.ocrBackends?.flatMap { it.packKeys } ?: emptyList()
        }

    private fun helper(packKey: String) = OcrPackModelHelper(packKey)

    /** Chosen backend for [id]: the stored selection if still available, else the
     *  ML Kit floor (so existing languages with no stored choice stay on ML Kit). */
    fun selectedBackend(ctx: Context, id: SourceLangId): OcrBackend {
        val profile = SourceLanguageProfiles[id]
        val token = Prefs(ctx).ocrBackendToken(id)
        return profile.ocrBackends.firstOrNull { it.selectionToken == token } ?: profile.ocrBackend
    }

    private fun installedPacks(ctx: Context): Set<String> =
        ALL_PACK_KEYS.filterTo(HashSet()) { helper(it).isInstalled(ctx) }

    fun currentPlan(ctx: Context): Plan =
        plan(LanguagePackStore.installedCodes(ctx), { selectedBackend(ctx, it) }, installedPacks(ctx))

    /** Download every pack in [plan].toDownload, best-effort (a failed pack stays
     *  absent → the registry falls back to ML Kit). Suspend/IO. */
    suspend fun applyDownloads(
        ctx: Context,
        plan: Plan = currentPlan(ctx),
        onProgress: (packKey: String, p: OnDeviceLlmDownloader.Progress) -> Unit = { _, _ -> },
    ) {
        for (key in plan.toDownload) {
            OnDeviceLlmDownloader(ctx.applicationContext, helper(key), totalMemFloorBytes = 0L)
                .run { p -> onProgress(key, p) }
        }
    }

    /** Set [id]'s OCR backend to its top priority IF unset, then download it
     *  best-effort. Idempotent; called from the user-intent language-consolidation
     *  flow (Phase 3). Existing languages with a stored choice are untouched. */
    suspend fun ensureDefaultForSource(ctx: Context, id: SourceLangId) {
        val prefs = Prefs(ctx)
        if (prefs.ocrBackendToken(id) == null) {
            SourceLanguageProfiles[id].ocrBackends.firstOrNull()?.let {
                prefs.setOcrBackendToken(id, it.selectionToken)
            }
        }
        applyDownloads(ctx)
    }

    /** Delete orphaned packs (installed − required) that aren't currently loaded.
     *  MUST run only at quiescence; [isLoaded] is wired to the bridges in Phase 3
     *  so a pack backing a live session is never deleted. */
    fun sweepOrphans(ctx: Context, isLoaded: (packKey: String) -> Boolean = { false }) {
        for (key in currentPlan(ctx).toDelete) {
            if (!isLoaded(key)) helper(key).delete(ctx)
        }
    }
}

/** Coarse selection tag persisted in Prefs; unique within a language's
 *  `ocrBackends` (each language has at most one Meiki / Paddle / ML Kit entry). */
val OcrBackend.selectionToken: String
    get() = when (this) {
        is OcrBackend.Meiki -> "meiki"
        is OcrBackend.Paddle -> "paddle"
        else -> "mlkit"
    }
