package com.playtranslate.translation

import android.content.Context
import android.util.Log
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.playtranslate.Prefs
import com.playtranslate.language.LanguagePackStore
import com.playtranslate.language.SourceLangId
import com.playtranslate.language.SourceLanguageProfiles
import com.playtranslate.language.TranslationManagerProvider
import com.playtranslate.translation.bergamot.BergamotModelManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "OfflineReclaim"

/**
 * Reclaims offline translation model files — ML Kit ([com.playtranslate.translation.MlKitBackend])
 * and Bergamot — for languages the app no longer needs.
 *
 * A model is "needed" iff its language is an endpoint of something installed or
 * selected. ML Kit keys models per language (one JA model serves ja→en, en→ja,
 * and every ja↔X via the English pivot), so the question is set membership, not
 * a pairing enumeration. English is never reclaimed: it is the universal pivot.
 *
 * Two entry points:
 *  - [reclaimMlKitIfUnneeded] — called when the user deletes a language pack, to
 *    drop that language's ML Kit model once it is unused in every role. Bergamot
 *    is already deleted by the pack-delete handler (via [BergamotModelManager]).
 *  - [sweepOrphans] — a launch-time pass that reclaims ML Kit AND Bergamot models
 *    orphaned by any path (past deletions, corruption recovery, FORCE upgrades).
 *
 * Best-effort: every deletion failure is swallowed and logged, never propagated
 * into the launch / delete flows. The decision layer ([sourceCodes]…[bergamotOrphans])
 * is pure and unit-tested; the IO shell gathers inputs and performs deletes.
 */
object OfflineModelReclaimer {

    /** The four resolved inputs the needed-set is derived from. */
    data class InstallState(
        val installedSourceIds: Set<SourceLangId>,
        val installedTargetCodes: Set<String>,
        val selectedSourceId: SourceLangId,
        val selectedTargetCode: String,
    )

    // ---- pure decision layer (no Context / GMS) ----

    /** Installed + selected source languages as canonical ML Kit codes. Both ZH
     *  and ZH_HANT carry `translationCode == CHINESE`, so they collapse to "zh". */
    fun sourceCodes(s: InstallState): Set<String> =
        (s.installedSourceIds + s.selectedSourceId)
            .map { SourceLanguageProfiles[it].translationCode }
            .toSet()

    /** Installed + selected target languages (already ML Kit codes). */
    fun targetCodes(s: InstallState): Set<String> =
        s.installedTargetCodes + s.selectedTargetCode

    /** ML Kit languages whose model must be kept. English is always included. */
    fun neededMlKit(s: InstallState): Set<String> =
        sourceCodes(s) + targetCodes(s) + TranslateLanguage.ENGLISH

    /** Bergamot directions whose model must be kept (xx↔en hops only; the
     *  English-pivot rule needs no other directions). */
    fun neededBergamotDirs(s: InstallState): Set<String> {
        val src = sourceCodes(s).filter { it != "en" }.map { "$it-en" }
        val tgt = targetCodes(s).filter { it != "en" }.map { "en-$it" }
        return (src + tgt).toSet()
    }

    fun mlKitOrphans(downloaded: Set<String>, needed: Set<String>): Set<String> =
        downloaded - needed

    fun bergamotOrphans(onDisk: Set<String>, neededDirs: Set<String>): Set<String> =
        onDisk - neededDirs

    // ---- IO shell ----

    /** The only Context / filesystem reads. Source codes are normalized through
     *  `translationCode` (never the raw stored code), so zh-Hant never leaks. */
    suspend fun gatherInstallState(ctx: Context): InstallState {
        val installedSources = SourceLangId.entries
            .filter { LanguagePackStore.isInstalled(ctx, it) }
            .toSet()
        val installedTargets = TranslateLanguage.getAllLanguages()
            .filter { LanguagePackStore.isTargetInstalled(ctx, it) }
            .toSet()
        val prefs = Prefs(ctx)
        return InstallState(
            installedSourceIds = installedSources,
            installedTargetCodes = installedTargets,
            selectedSourceId = prefs.sourceLangId,
            selectedTargetCode = prefs.targetLang,
        )
    }

    /** User-delete path: drop [mlkitCode]'s ML Kit model when it is no longer an
     *  endpoint of anything installed/selected. No-op for English and for
     *  still-needed languages. */
    suspend fun reclaimMlKitIfUnneeded(ctx: Context, mlkitCode: String) {
        val needed = neededMlKit(gatherInstallState(ctx))
        if (mlkitCode in needed) return
        deleteMlKitModel(mlkitCode)
    }

    /** Launch-time pass: reclaim every orphaned ML Kit and Bergamot model. */
    suspend fun sweepOrphans(ctx: Context) {
        val state = gatherInstallState(ctx)

        val mlKit = mlKitOrphans(downloadedMlKitCodes(), neededMlKit(state))
        for (code in mlKit) deleteMlKitModel(code)

        val bergamot = BergamotModelManager(ctx)
        val staleDirs = bergamotOrphans(bergamot.installedDirections(), neededBergamotDirs(state))
        if (staleDirs.isNotEmpty()) {
            Log.i(TAG, "Reclaiming orphaned Bergamot directions: $staleDirs")
            bergamot.deleteDirections(staleDirs)
        }
    }

    private suspend fun deleteMlKitModel(mlkitCode: String) {
        // Close any warm Translator holding this model before deleting the file.
        TranslationManagerProvider.evictLanguage(mlkitCode)
        try {
            val model = TranslateRemoteModel.Builder(mlkitCode).build()
            suspendCancellableCoroutine<Unit> { cont ->
                RemoteModelManager.getInstance().deleteDownloadedModel(model)
                    .addOnSuccessListener { cont.resume(Unit) }
                    .addOnFailureListener { e -> cont.resumeWithException(e) }
            }
            Log.i(TAG, "Reclaimed ML Kit model: $mlkitCode")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete ML Kit model $mlkitCode; leaving it in place", e)
        }
    }

    /**
     * Concept-B readiness: is offline TRANSLATION available for the active pair
     * ([sourceId] → [targetLang]) without a network? True when source == target,
     * when Bergamot's required direction(s) are installed (English-pivot aware),
     * or when every ML Kit language model the pair needs ({source, target, en})
     * is already downloaded. Drives the Settings "Download offline models" cell;
     * the app still works ONLINE when this is false. Suspend — ML Kit's
     * downloaded-model state lives behind a GMS round-trip.
     */
    suspend fun isOfflineTranslationReady(
        ctx: Context,
        sourceId: SourceLangId,
        targetLang: String,
    ): Boolean {
        val src = SourceLanguageProfiles[sourceId].translationCode
        if (src == targetLang) return true
        if (BergamotModelManager(ctx).isInstalled(src, targetLang)) return true
        val needed = setOf(src, targetLang, TranslateLanguage.ENGLISH)
        val downloaded = downloadedMlKitCodes()
        return needed.all { it in downloaded }
    }

    private suspend fun downloadedMlKitCodes(): Set<String> =
        try {
            suspendCancellableCoroutine<Set<TranslateRemoteModel>> { cont ->
                RemoteModelManager.getInstance()
                    .getDownloadedModels(TranslateRemoteModel::class.java)
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { e -> cont.resumeWithException(e) }
            }.map { it.language }.toSet()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not list downloaded ML Kit models; skipping ML Kit sweep", e)
            emptySet()
        }
}
