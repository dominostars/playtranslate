package com.playtranslate.ocr.registry

import android.content.Context
import android.util.Log
import com.playtranslate.Prefs
import com.playtranslate.language.LanguagePackStore
import com.playtranslate.language.OcrBackend
import com.playtranslate.language.SourceLangId
import com.playtranslate.language.SourceLanguageProfiles
import com.playtranslate.ocr.core.OcrEngine
import com.playtranslate.ocr.meiki.MeikiBridge
import com.playtranslate.ocr.paddle.PaddleOcrBridge
import com.playtranslate.translation.llm.OnDeviceLlmDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

    private const val TAG = "OcrModelManager"

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

    /** App context pushed at startup (PlayTranslateApplication) so the registry +
     *  bridges resolve installed packs / Prefs without threading a Context through
     *  `recognise()`. */
    @Volatile var appContext: Context? = null

    /**
     * Production OCR engine for [sourceLang]: the user's chosen backend if its pack
     * is installed, else null → the registry's ML Kit floor. Selection only mutates
     * Prefs, so this resolves fresh each call (no stale cached engine); the native
     * session is owned + cached by the bridge (closed only at quiescent teardown).
     */
    fun engineForSelected(sourceLang: String): OcrEngine? {
        val ctx = appContext ?: return null
        val profile = SourceLanguageProfiles.forCode(sourceLang) ?: SourceLanguageProfiles[SourceLangId.JA]
        return when (val chosen = selectedBackend(ctx, profile.id)) {
            is OcrBackend.Meiki ->
                if (OcrPackModelHelper(chosen.packKey).isInstalled(ctx)) MeikiBridge.engine(ctx, chosen.packKey) else null
            is OcrBackend.Paddle ->
                if (OcrPackModelHelper(chosen.recPackKey).isInstalled(ctx)) PaddleOcrBridge.engine(ctx, chosen.recPackKey) else null
            else -> null // ML Kit floor — registry builds it
        }
    }

    /** Every OCR pack key the app knows about (single source of truth = the
     *  profiles' `ocrBackends`), so the on-disk universe never drifts from the keys. */
    val ALL_PACK_KEYS: Set<String> =
        SourceLangId.entries.flatMapTo(HashSet()) { id ->
            SourceLanguageProfiles.forCode(id.code)?.ocrBackends?.flatMap { it.packKeys } ?: emptyList()
        }

    private fun helper(packKey: String) = OcrPackModelHelper(packKey)

    /** A backend is offerable iff every pack it needs has a shippable catalog entry.
     *  ML Kit (no packs) is always available; a recognizer pack not yet authored/
     *  hosted (e.g. `paddle-rec-latin`) makes its backend unavailable until it ships,
     *  so we never surface an engine we can't deliver. */
    fun isBackendAvailable(ctx: Context, backend: OcrBackend): Boolean =
        backend.packKeys.all { OcrPackModelHelper(it).isShippable(ctx) }

    /** [id]'s priority list filtered to currently-deliverable engines (always keeps
     *  the ML Kit floor). */
    fun availableBackends(ctx: Context, id: SourceLangId): List<OcrBackend> =
        SourceLanguageProfiles[id].ocrBackends.filter { isBackendAvailable(ctx, it) }

    /** Chosen backend for [id]: the stored selection if still available + deliverable,
     *  else the ML Kit floor (so existing languages with no/stale choice stay on ML Kit). */
    fun selectedBackend(ctx: Context, id: SourceLangId): OcrBackend {
        val profile = SourceLanguageProfiles[id]
        val token = Prefs(ctx).ocrBackendToken(id)
        return availableBackends(ctx, id).firstOrNull { it.selectionToken == token } ?: profile.ocrBackend
    }

    private fun installedPacks(ctx: Context): Set<String> =
        ALL_PACK_KEYS.filterTo(HashSet()) { helper(it).isInstalled(ctx) }

    fun currentPlan(ctx: Context): Plan =
        plan(LanguagePackStore.installedCodes(ctx), { selectedBackend(ctx, it) }, installedPacks(ctx))

    /** Fetch one pack, best-effort. The downloader RETURNS its terminal state
     *  rather than throwing for failure/refusal (only cancellation propagates, via
     *  its withContext boundary rethrowing CancellationException) — so a discarded
     *  Outcome would let a failed/refused pack vanish silently into the ML Kit
     *  fallback. Log the reason here so an exported diagnostic log can explain why
     *  a pack didn't land. */
    private suspend fun downloadPack(
        ctx: Context,
        key: String,
        onProgress: (OnDeviceLlmDownloader.Progress) -> Unit,
    ) {
        val downloader = OnDeviceLlmDownloader(ctx.applicationContext, helper(key), totalMemFloorBytes = 0L)
        when (val outcome = downloader.run(onProgress)) {
            is OnDeviceLlmDownloader.Outcome.Failed ->
                Log.w(TAG, "OCR pack '$key' download failed: ${outcome.reason}", outcome.cause)
            is OnDeviceLlmDownloader.Outcome.Refused ->
                Log.w(TAG, "OCR pack '$key' download refused: ${outcome.reason}")
            // Success: nothing to log. Cancelled: shadowed by the downloader's
            // withContext rethrow on Job-cancel, so it's only reachable via a
            // non-Job cancellation (e.g. an inner timeout) — best-effort, ignore.
            OnDeviceLlmDownloader.Outcome.Success,
            OnDeviceLlmDownloader.Outcome.Cancelled -> Unit
        }
    }

    /** Download every pack in [plan].toDownload, best-effort (a failed pack stays
     *  absent → the registry falls back to ML Kit; [downloadPack] logs the reason).
     *  Suspend/IO. */
    suspend fun applyDownloads(
        ctx: Context,
        plan: Plan = currentPlan(ctx),
        onProgress: (packKey: String, p: OnDeviceLlmDownloader.Progress) -> Unit = { _, _ -> },
    ) {
        for (key in plan.toDownload) {
            downloadPack(ctx, key) { p -> onProgress(key, p) }
        }
    }

    /** Record [id]'s default OCR backend (top priority) IF the user hasn't chosen
     *  one. Cheap + synchronous; safe on any thread/network. Existing languages
     *  with a stored choice are untouched. */
    fun setDefaultBackendIfUnset(ctx: Context, id: SourceLangId) {
        val prefs = Prefs(ctx)
        if (prefs.ocrBackendToken(id) != null) return
        // Top deliverable engine. If only the ML Kit floor is available (the
        // language's recognizer pack isn't hosted yet), leave the choice unset so a
        // later visit can default it once a real engine ships.
        val best = availableBackends(ctx, id).firstOrNull() ?: return
        if (best.packKeys.isNotEmpty()) prefs.setOcrBackendToken(id, best.selectionToken)
    }

    /** Download [id]'s default OCR engine's pack(s) as a VISIBLE step folded into
     *  the language-setup download flow (its own progress view, like the source
     *  pack + offline translation models). Records the default first, then fetches
     *  each not-yet-installed pack, reporting byte progress via [onBytes]. No-op
     *  when the default resolves to ML Kit or the pack is already present.
     *
     *  Cancellation propagates: if the enclosing coroutine is cancelled the
     *  downloader's withContext boundary rethrows CancellationException, aborting
     *  setup like any other step. A network/verify failure or RAM/storage refusal
     *  does NOT throw — [downloadPack] logs it and leaves the ML Kit floor, so a
     *  failed OCR download never aborts adding the language. */
    suspend fun downloadDefaultForSource(
        ctx: Context,
        id: SourceLangId,
        onBytes: (received: Long, total: Long) -> Unit,
    ) {
        setDefaultBackendIfUnset(ctx, id)
        val needed = selectedBackend(ctx, id).packKeys.filter { !OcrPackModelHelper(it).isInstalled(ctx) }
        for (key in needed) {
            downloadPack(ctx, key) { p ->
                if (p is OnDeviceLlmDownloader.Progress.Downloading) onBytes(p.received, p.total)
            }
        }
    }

    /** Download [backend]'s not-yet-installed packs, best-effort (failures logged
     *  by [downloadPack], leaving the ML Kit floor), and report whether [backend]
     *  is fully installed afterward. Deliberately does NOT mutate Prefs and does
     *  NOT sweep: an engine SWITCH must persist the new selection — and only then
     *  sweep the packs the switch orphaned — AFTER this returns true, so a failed
     *  or cancelled download never reclaims the still-working previous engine's
     *  pack. Suspend/IO. */
    suspend fun installBackend(
        ctx: Context,
        backend: OcrBackend,
        onProgress: (packKey: String, p: OnDeviceLlmDownloader.Progress) -> Unit = { _, _ -> },
    ): Boolean = withContext(Dispatchers.IO) {
        val missing = backend.packKeys.filterTo(HashSet()) { !helper(it).isInstalled(ctx) }
        applyDownloads(ctx, Plan(backend.packKeys.toSet(), missing, emptySet()), onProgress)
        backend.packKeys.all { helper(it).isInstalled(ctx) }
    }

    /** Delete orphaned packs (installed − required) that aren't currently loaded.
     *  MUST run only at quiescence; [isLoaded] is wired to the bridges in Phase 3
     *  so a pack backing a live session is never deleted. */
    fun sweepOrphans(
        ctx: Context,
        isLoaded: (packKey: String) -> Boolean = { MeikiBridge.isLoaded(it) || PaddleOcrBridge.isLoaded(it) },
    ) {
        for (key in currentPlan(ctx).toDelete) {
            if (!isLoaded(key)) helper(key).delete(ctx)
        }
    }

    /** Quiescent teardown: close every bridge session + engine cache. Caller must
     *  guarantee no in-flight OCR (wired from OcrManager.releaseAll at TRIM_MEMORY). */
    fun closeAll() {
        MeikiBridge.close(); PaddleOcrBridge.close()
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

/** Human-facing engine name for the Settings OCR picker. Proper nouns — not
 *  translated. */
val OcrBackend.ocrLabel: String
    get() = when (this) {
        is OcrBackend.Meiki -> "Meiki"
        is OcrBackend.Paddle -> "PaddleOCR"
        is OcrBackend.Tesseract -> "Tesseract"
        else -> "ML Kit"
    }
