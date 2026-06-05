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
        selectedBackend: (SourceLangId) -> OcrBackend?,
        installedPacks: Set<String>,
    ): Plan {
        val required = installedLangs.flatMapTo(HashSet()) { selectedBackend(it)?.packKeys.orEmpty() }
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

    /** Pure runtime-compatibility gate (no I/O, so JVM-testable): an MNN-backed OCR
     *  engine (Meiki/Paddle) runs only on the arm64 MNN native runtime, so it is
     *  unavailable on a 32-bit process even when its pack is shippable. The app
     *  ships an armeabi-v7a slice (installs on 32-bit) but `:mnn` is arm64-only, so
     *  without this gate setup could default/download Meiki/Paddle and Settings
     *  could select them, only for engine creation to fail and silently drop to ML
     *  Kit — after burning the pack download. [mnnAvailable] mirrors
     *  `OnDeviceLlmBackend.supportsRequiredAbi()` ([android.os.Process.is64Bit]);
     *  pass it explicitly in tests. */
    fun isRuntimeCompatible(
        backend: OcrBackend,
        mnnAvailable: Boolean = android.os.Process.is64Bit(),
    ): Boolean = !backend.requiresMnn || mnnAvailable

    /** A backend is offerable iff its native runtime is compatible AND every pack it
     *  needs has a shippable catalog entry. ML Kit (no packs) is always available; a
     *  recognizer pack not yet authored/hosted (e.g. `paddle-rec-latin`) makes its
     *  backend unavailable until it ships; an MNN engine on a 32-bit device is
     *  unavailable (see [isRuntimeCompatible]). Gating here is the single chokepoint:
     *  [availableBackends] (the picker), [setDefaultBackendIfUnset], and
     *  [selectedBackend] all flow through it, and the [selectedBackend] fallback is
     *  always the pack-less ML Kit floor — so a 32-bit device never downloads or
     *  selects an engine it can't load. */
    fun isBackendAvailable(ctx: Context, backend: OcrBackend): Boolean =
        isRuntimeCompatible(backend) && backend.packKeys.all { OcrPackModelHelper(it).isShippable(ctx) }

    /** [id]'s priority list filtered to currently-deliverable engines (always keeps
     *  the ML Kit floor). */
    fun availableBackends(ctx: Context, id: SourceLangId): List<OcrBackend> =
        SourceLanguageProfiles[id].ocrBackends.filter { isBackendAvailable(ctx, it) }

    /** Chosen backend for [id]: the stored selection if still deliverable, else the
     *  ML Kit floor, else — for a no-floor language (Cyrillic) — its single deliverable
     *  recognizer. NULL only when nothing is deliverable on this device (a no-floor
     *  language on a 32-bit process). See [resolveSelectedBackend] for the rule. */
    fun selectedBackend(ctx: Context, id: SourceLangId): OcrBackend? =
        resolveSelectedBackend(
            available = availableBackends(ctx, id),
            token = Prefs(ctx).ocrBackendToken(id),
            mlKitFloor = SourceLanguageProfiles[id].mlKitFloor,
        )

    /** PURE selection rule (JVM-testable): the [token]'s backend if it's in
     *  [available], else [mlKitFloor], else the top [available] backend. The final
     *  fallback keeps the OCR token NON-load-bearing for no-floor languages (Cyrillic,
     *  where [mlKitFloor] is null): a missing/stale token resolves to the same backend
     *  the installed pack already represents, so [currentPlan] never orphans an
     *  installed no-floor pack and [engineForSelected] never drops to the empty engine
     *  over a bookkeeping gap. Returns null only when [available] is empty and there is
     *  no floor (a no-floor language with no deliverable recognizer on this device). */
    fun resolveSelectedBackend(
        available: List<OcrBackend>,
        token: String?,
        mlKitFloor: OcrBackend?,
    ): OcrBackend? =
        available.firstOrNull { it.selectionToken == token } ?: mlKitFloor ?: available.firstOrNull()

    /** True iff [id] has an ML Kit OCR recognizer that needs no download — the
     *  always-available floor. False for scripts ML Kit can't read (Cyrillic),
     *  whose only OCR is a downloadable MNN pack. PURE (profile-only). */
    fun hasMlKitFloor(id: SourceLangId): Boolean = SourceLanguageProfiles[id].mlKitFloor != null

    /** True iff [id]'s mandatory OCR is satisfied. A floored language always is
     *  (ML Kit needs no pack); a no-floor language (Russian) is iff the backend it
     *  would actually resolve to is deliverable on this device AND its packs are on
     *  disk — see [requiredOcrReady]. */
    fun isRequiredOcrInstalled(ctx: Context, id: SourceLangId): Boolean =
        requiredOcrReady(
            hasFloor = hasMlKitFloor(id),
            selected = selectedBackend(ctx, id),
            isInstalled = { OcrPackModelHelper(it).isInstalled(ctx) },
        )

    /** PURE readiness rule (JVM-testable): a floored language is always OCR-ready;
     *  a no-floor language is ready iff [selected] (the backend [resolveSelectedBackend]
     *  picks — non-null only when runtime-compatible + shippable on this device) has
     *  all its packs on disk. Tying readiness to the RESOLVED backend, not raw profile
     *  packKeys, guarantees `isFullyInstalled ⇒ engineForSelected loads a real engine`:
     *  a no-floor language can't read as installed on a device that can't run its only
     *  recognizer (the arm64-only Cyrillic pack on a 32-bit process). */
    fun requiredOcrReady(
        hasFloor: Boolean,
        selected: OcrBackend?,
        isInstalled: (String) -> Boolean,
    ): Boolean {
        if (hasFloor) return true
        val backend = selected ?: return false
        return backend.packKeys.isNotEmpty() && backend.packKeys.all(isInstalled)
    }

    /** Concept-A completeness: [id]'s dictionary pack is present AND its required
     *  OCR is available. Drives the language-selection trash-can and the blocking
     *  OCR download at selection — a no-floor language (Russian) without its
     *  recognizer pack reads as "not installed" (no trash; re-select to download). */
    fun isFullyInstalled(ctx: Context, id: SourceLangId): Boolean =
        LanguagePackStore.isInstalled(ctx, id) && isRequiredOcrInstalled(ctx, id)

    /** True iff [id] cannot OCR on THIS device: no ML Kit floor AND no
     *  runtime-compatible recognizer (e.g. Russian on a 32-bit device, where the
     *  arm64-only Cyrillic MNN pack can't run). Drives the disabled source row. */
    fun isOcrUnavailableOnDevice(ctx: Context, id: SourceLangId): Boolean =
        !hasMlKitFloor(id) && availableBackends(ctx, id).isEmpty()

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
        val needed = selectedBackend(ctx, id)?.packKeys?.filter { !OcrPackModelHelper(it).isInstalled(ctx) }.orEmpty()
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
     *
     *  MUST be called ONLY from the launch-time reclaim (MainActivity, after pack
     *  upgrades/migrations settle) — never from an interactive engine-switch or
     *  language-delete flow. Rationale: a sweep only targets NON-required packs,
     *  and a live capture only resolves the REQUIRED pack for its language, so the
     *  two sets are normally disjoint. They overlap only when an interactive
     *  selection change flips a pack required→orphan WHILE a capture is mid-resolve
     *  of it — the [engineForSelected] isInstalled-check → bridge-session-create
     *  window that [isLoaded] cannot yet see. At launch the required set is stable
     *  and no capture/download is in flight, so the sets stay disjoint.
     *
     *  [isLoaded] stays as defense-in-depth: a pack backing an already-created live
     *  session is never deleted even if this is somehow called off-quiescence. */
    fun sweepOrphans(
        ctx: Context,
        isLoaded: (packKey: String) -> Boolean = { MeikiBridge.isLoaded(it) || PaddleOcrBridge.isLoaded(it) },
    ) {
        for (key in currentPlan(ctx).toDelete) {
            if (!isLoaded(key)) helper(key).delete(ctx)
        }
    }

    /** Delete a SINGLE OCR pack interactively (the Settings OCR trash), outside
     *  the launch-time [sweepOrphans] pass. Safe off-quiescence ONLY because the
     *  caller offers the trash exclusively on a backend the current source
     *  language has NOT selected — never the pack a live capture is resolving (a
     *  capture only ever resolves the *selected* backend, and a language's own
     *  backends never share a pack). Any session still cached for [packKey] is
     *  therefore a stale one from a prior selection: close just it — so we never
     *  unlink files under a live mmap — then delete, leaving every other
     *  language's live session intact.
     *
     *  Other languages that selected [packKey] keep their choice; the missing
     *  pack re-downloads through the normal source-switch path
     *  (downloadDefaultForSource) the next time one becomes the source. */
    fun deleteOcrPack(ctx: Context, packKey: String) {
        MeikiBridge.close(packKey)
        PaddleOcrBridge.close(packKey)
        helper(packKey).delete(ctx)
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
