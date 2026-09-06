package com.playtranslate.ui

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import com.playtranslate.capturableDisplays
import com.playtranslate.capture.CaptureBackendResolver
import com.playtranslate.capture.CaptureLifecycle
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.playtranslate.AnkiManager
import com.playtranslate.BuildConfig
import com.playtranslate.CaptureService
import com.playtranslate.OcrManager
import com.playtranslate.OverlayMode
import com.playtranslate.PlayTranslateAccessibilityService
import com.playtranslate.PlayTranslateTileService
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.diagnostics.LogExporter
import com.playtranslate.language.HintTextKind
import com.playtranslate.language.SourceLangId
import com.playtranslate.language.SourceLanguageProfiles
import com.playtranslate.blendColors
import com.playtranslate.compositeOver
import com.playtranslate.themeColor
import com.playtranslate.translation.BackendId
import com.playtranslate.translation.BackendStatus
import com.playtranslate.translation.Cooldownable
import com.playtranslate.translation.BergamotBackend
import com.playtranslate.translation.MlKitBackend
import com.playtranslate.translation.StarRating
import com.playtranslate.translation.Tone
import com.playtranslate.translation.TranslationBackend
import com.playtranslate.translation.TranslationBackendRegistry
import com.playtranslate.translation.llm.OnDeviceLlmBackend
import com.playtranslate.translation.llm.OnDeviceLlmDownloader
import com.playtranslate.translation.llm.humanSize
import com.playtranslate.language.LanguagePackStore
import com.playtranslate.language.OcrBackend
import com.playtranslate.ocr.registry.OcrModelManager
import com.playtranslate.ocr.registry.OcrPackModelHelper
import com.playtranslate.ocr.registry.ocrLabel
import com.playtranslate.ocr.registry.selectionToken
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import com.playtranslate.tts.TtsEngine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.core.view.isVisible
import androidx.core.net.toUri
import androidx.core.view.isGone

/**
 * Wires the Offline translation backend rows (extracted verbatim from
 * the old monolithic SettingsRenderer). Field names mirror the renderer's so
 * the moved methods need no internal rewiring; the host
 * [TranslationServicesActivity] supplies a [Callbacks] that runs the offline-
 * model install flows, and drives refreshes from its own lifecycle.
 *
 * The Online card is instance-based and lives in [OnlineServicesController];
 * its status/cooldown rendering moved there together with the rows.
 */
class TranslationServicesBinder(
    private val root: View,
    private val prefs: Prefs,
    private val ctx: Context,
    private val lifecycleScope: CoroutineScope,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        fun startQwenMnnDownload()
        fun enableInstalledQwenMnn()
        fun showQwenMnnDisableDialog()
        fun startQwen35Mnn2bDownload()
        fun enableInstalledQwen35Mnn2b()
        fun showQwen35Mnn2bDisableDialog()
        fun startGemmaE2bMnnDownload()
        fun enableInstalledGemmaE2bMnn()
        fun showGemmaE2bMnnDisableDialog()
        fun startHyMtDownload()
        fun enableInstalledHyMt()
        fun showHyMtDisableDialog()
        fun startHyMt2Download()
        fun enableInstalledHyMt2()
        fun showHyMt2DisableDialog()
        fun startBergamotDownload()
        fun enableInstalledBergamot()
        fun showBergamotDisableDialog()
    }

    /** Wire all backend rows + kick off the initial status render. */
    fun bind() {
        setupTranslationServiceSection()
        refreshAllBackendStatuses()
    }

    private val rowBackendGemmaE2bMnn: View = root.findViewById(R.id.rowBackendGemmaE2bMnn)
    private val dividerBackendQwenMnn: View = root.findViewById(R.id.dividerBackendQwenMnn)
    private val rowBackendQwenMnn: View = root.findViewById(R.id.rowBackendQwenMnn)
    private val dividerBackendQwen35Mnn2b: View = root.findViewById(R.id.dividerBackendQwen35Mnn2b)
    private val rowBackendQwen35Mnn2b: View = root.findViewById(R.id.rowBackendQwen35Mnn2b)
    private val dividerBackendHyMt2: View = root.findViewById(R.id.dividerBackendHyMt2)
    private val rowBackendHyMt2: View = root.findViewById(R.id.rowBackendHyMt2)
    private val dividerBackendHyMt: View = root.findViewById(R.id.dividerBackendHyMt)
    private val rowBackendHyMt: View = root.findViewById(R.id.rowBackendHyMt)

    /** Set by [wireHyMtBackendRow] when the region gate hides Hunyuan-MT 1.5.
     *  A region-hidden row is treated as *absent* rather than merely invisible
     *  ([backendRowById] returns null for it), because the render pass would
     *  otherwise fight the wiring pass: now that the 1.5 catalog entry is
     *  deprecated, [renderOfflineBackendRow] sets `isGone = !installed` on
     *  every refresh, which would un-hide the row for the one user the region
     *  gate is actually for — someone who installed the model outside the
     *  restricted territory and then moved into it. The backend's runtime
     *  region AND-gate keeps it from being *used* there either way; this keeps
     *  the row from reappearing with a switch that can't do anything. */
    private var hyMtRegionHidden = false
    private val dividerBackendBergamot: View = root.findViewById(R.id.dividerBackendBergamot)
    private val rowBackendBergamot: View = root.findViewById(R.id.rowBackendBergamot)
    private val rowBackendMlkit: View = root.findViewById(R.id.rowBackendMlkit)

    /** Per-backend in-flight `refreshStatus` job, keyed by [BackendId]. Used
     *  to single-flight: a new render that triggers a refresh cancels any
     *  prior refresh for the same backend so a slow request can't overwrite
     *  the result of a faster, more recent one. */
    private val backendRefreshJobs: MutableMap<BackendId, Job> = mutableMapOf()

    private fun setupTranslationServiceSection() {
        // ML Kit row: bundled, always-on fallback. No switch, not tappable.
        // The C7 offline layout's `switchOfflineToggle` stays GONE (set by
        // renderOfflineBackendRow during refreshAllBackendStatuses); the
        // stat grid + downloaded-check icon are bound there too.
        wireMlKitBackendRow()

        wireGemmaE2bMnnBackendRow()
        wireQwenMnnBackendRow()
        wireQwen35Mnn2bBackendRow()
        wireHyMt2BackendRow()
        wireHyMtBackendRow()
        wireBergamotBackendRow()

        // Render every offline backend's row, kicking off async refreshes
        // for ones in Loading state — this fully binds the C7 row (title,
        // stat grid, icon, switch, warning sub-row).
        refreshAllBackendStatuses()
    }

    /** Refresh the MNN-Qwen row's switch + status icon from current pref +
     *  install state + busy state. Driven by the SP-listener observer
     *  after the Cancel branch of the disable dialog (which needs to
     *  revert the optimistic toggle) and after a successful download
     *  (which flips the pref to true). Delegates to the shared offline
     *  binder so the icon stays in sync with the switch. */
    fun refreshQwenMnnSwitch() {
        val backend = TranslationBackendRegistry.byId("qwen_mnn") ?: return
        updateOfflineStatusIconAndSwitch(rowBackendQwenMnn, backend)
    }

    /** Refresh the Qwen 3.5 2B row. Mirrors [refreshQwenMnnSwitch]. */
    fun refreshQwen35Mnn2bSwitch() {
        val backend = TranslationBackendRegistry.byId("qwen35_mnn_2b") ?: return
        updateOfflineStatusIconAndSwitch(rowBackendQwen35Mnn2b, backend)
    }

    fun refreshBergamotSwitch() {
        val backend = TranslationBackendRegistry.byId("bergamot") ?: return
        updateOfflineStatusIconAndSwitch(rowBackendBergamot, backend)
    }

    /** Refresh the Gemma E2B row. Mirrors [refreshQwenMnnSwitch]. */
    fun refreshGemmaE2bSwitch() {
        val backend = TranslationBackendRegistry.byId("gemma_e2b_mnn") ?: return
        updateOfflineStatusIconAndSwitch(rowBackendGemmaE2bMnn, backend)
    }

    /** Refresh the Hunyuan-MT 1.5 row. Mirrors [refreshQwenMnnSwitch]. */
    fun refreshHyMtSwitch() {
        val backend = TranslationBackendRegistry.byId("hymt_mnn") ?: return
        updateOfflineStatusIconAndSwitch(rowBackendHyMt, backend)
    }

    /** Refresh the Hy-MT2 row. Mirrors [refreshQwenMnnSwitch]. */
    fun refreshHyMt2Switch() {
        val backend = TranslationBackendRegistry.byId("hymt2_mnn") ?: return
        updateOfflineStatusIconAndSwitch(rowBackendHyMt2, backend)
    }

    /** Re-render every offline backend row and kick off an async
     *  [TranslationBackend.refreshStatus] for each. Called on initial
     *  bind, on Settings resume, and on relevant pref changes.
     *
     *  We render the cached state synchronously first (so the row shows
     *  the last known value immediately) and then trigger a background
     *  refresh that updates the row when fresh data arrives. Backends
     *  without async state (ML Kit) inherit the default no-op
     *  [refreshStatus] that returns the same status without I/O — so
     *  always-launching is essentially free for them. */
    fun refreshAllBackendStatuses() {
        for (backend in TranslationBackendRegistry.orderedBackends()) {
            if (!isOfflineRowBackend(backend)) continue
            val row = backendRowById(backend.id) ?: continue
            // C7 layout — single entry point owns title/grid/icon/switch/warning.
            renderOfflineBackendRow(row, backend)
            backendRefreshJobs[backend.id]?.cancel()
            backendRefreshJobs[backend.id] = lifecycleScope.launch {
                backend.refreshStatus()
                renderOfflineBackendRow(row, backend)
            }
        }
    }

    private fun backendRowById(id: BackendId): View? = when (id) {
        "gemma_e2b_mnn"   -> rowBackendGemmaE2bMnn
        "qwen_mnn"        -> rowBackendQwenMnn
        "qwen35_mnn_2b"   -> rowBackendQwen35Mnn2b
        "hymt_mnn"        -> if (hyMtRegionHidden) null else rowBackendHyMt
        "hymt2_mnn"       -> rowBackendHyMt2
        "bergamot"        -> rowBackendBergamot
        "mlkit"           -> rowBackendMlkit
        else              -> null
    }

    /** ML Kit row — bundled, always-on fallback. The C7 offline layout
     *  shows the title + stat grid + downloaded-check icon via
     *  [renderOfflineBackendRow]; the switch stays GONE (user-confirmed
     *  deviation from the C7 design) and the row is not tappable. */
    private fun wireMlKitBackendRow() {
        rowBackendMlkit.isClickable = false
        rowBackendMlkit.isFocusable = false
        rowBackendMlkit.setOnClickListener(null)
    }

    /** Attach the click handler for an offline on-device-LLM row. All three
     *  callers (Qwen, Gemma E2B, Hunyuan-MT) share the same three-state
     *  branch (enabled → disable dialog · installed-disabled → enable
     *  directly · not-installed → start download); each callback is supplied
     *  by the caller. Visual binding (title, stat grid, status icon, switch
     *  state, warning sub-row, hardware-incompat replacement) lives in
     *  [renderOfflineBackendRow] and runs via [refreshAllBackendStatuses];
     *  this method only owns the row's `onClickListener`. On
     *  hardware-incompatible devices the row is left inert (the renderer
     *  hides the switch and shows the incompat reason in place of the
     *  stat grid).
     *
     *  HyMt's region gate and Cancel-revert behavior are unchanged — the
     *  caller handles the region gate before invoking this, and the
     *  Cancel branch of [onDisable] is still responsible for calling
     *  [refreshQwenMnnSwitch] / [refreshGemmaE2bSwitch] / [refreshHyMtSwitch]
     *  to revert the optimistic switch flip. */
    private fun wireOfflineLlmRow(
        row: View,
        backendId: BackendId,
        isEnabled: () -> Boolean,
        onDisable: () -> Unit,
        onEnableInstalled: () -> Unit,
        onDownload: () -> Unit,
    ) {
        val backend = TranslationBackendRegistry.byId(backendId) as? OnDeviceLlmBackend
        // Only early-return when we have a definite hardware-incompat
        // signal. If the registry can't find the backend (unexpected — but
        // historically the wiring didn't depend on it), still attach the
        // tap handler; the install check inside the click handler falls
        // back to "not installed" → download path.
        if (backend != null && !backend.meetsHardwareRequirements()) {
            row.setOnClickListener(null)
            row.isClickable = false
            return
        }
        row.setOnClickListener {
            val switch = row.findViewById<MaterialSwitch>(R.id.switchOfflineToggle)
            val installed = backend?.isInstalled() == true
            if (isEnabled()) {
                switch.isChecked = false
                onDisable()
            } else if (installed) {
                switch.isChecked = true
                onEnableInstalled()
            } else {
                onDownload()
            }
        }
    }

    private fun wireGemmaE2bMnnBackendRow() = wireOfflineLlmRow(
        row = rowBackendGemmaE2bMnn,
        backendId = "gemma_e2b_mnn",
        isEnabled = { prefs.gemmaE2bEnabled },
        onDisable = callbacks::showGemmaE2bMnnDisableDialog,
        onEnableInstalled = callbacks::enableInstalledGemmaE2bMnn,
        onDownload = callbacks::startGemmaE2bMnnDownload,
    )

    /** Hy-MT2 wires like any other Apache-2.0 offline tier: no region gate
     *  and no legal attestation, both of which exist on the Hunyuan-MT 1.5
     *  path only because of that model's Territory-restricted licence. */
    private fun wireHyMt2BackendRow() = wireOfflineLlmRow(
        row = rowBackendHyMt2,
        backendId = "hymt2_mnn",
        isEnabled = { prefs.hyMt2Enabled },
        onDisable = callbacks::showHyMt2DisableDialog,
        onEnableInstalled = callbacks::enableInstalledHyMt2,
        onDownload = callbacks::startHyMt2Download,
    )

    /** Hunyuan-MT 1.5 has an extra **region gate** before the standard
     *  wiring: if [com.playtranslate.region.RegionPolicy.isHunyuanRestricted]
     *  reports true (any device-region signal indicates EU/UK/SK per the
     *  Tencent HY Community License Territory definition), the row + its
     *  preceding divider are hidden entirely so the user never sees the
     *  catalog row, never gets the legal-attestation dialog, and never
     *  downloads. The legal-attestation dialog inside the download flow
     *  is the second-line gate for cases where region signals don't catch
     *  it (default-open). */
    private fun wireHyMtBackendRow() {
        if (com.playtranslate.region.RegionPolicy.isHunyuanRestricted(ctx)) {
            rowBackendHyMt.isGone = true
            dividerBackendHyMt.isGone = true
            hyMtRegionHidden = true
            return
        }
        wireOfflineLlmRow(
            row = rowBackendHyMt,
            backendId = "hymt_mnn",
            isEnabled = { prefs.hyMtEnabled },
            onDisable = callbacks::showHyMtDisableDialog,
            // Legal-attestation dialog does NOT re-fire when enabling an
            // already-downloaded model — once hyMtLegalAccepted is true
            // it stays true, mirroring how Meta handles the Llama ToS.
            onEnableInstalled = callbacks::enableInstalledHyMt,
            onDownload = callbacks::startHyMtDownload,
        )
    }

    private fun wireQwenMnnBackendRow() = wireOfflineLlmRow(
        row = rowBackendQwenMnn,
        backendId = "qwen_mnn",
        isEnabled = { prefs.qwenMnnEnabled },
        onDisable = callbacks::showQwenMnnDisableDialog,
        onEnableInstalled = callbacks::enableInstalledQwenMnn,
        onDownload = callbacks::startQwenMnnDownload,
    )

    private fun wireQwen35Mnn2bBackendRow() = wireOfflineLlmRow(
        row = rowBackendQwen35Mnn2b,
        backendId = "qwen35_mnn_2b",
        isEnabled = { prefs.qwen35Mnn2bEnabled },
        onDisable = callbacks::showQwen35Mnn2bDisableDialog,
        onEnableInstalled = callbacks::enableInstalledQwen35Mnn2b,
        onDownload = callbacks::startQwen35Mnn2bDownload,
    )

    /** Bergamot's row can't reuse [wireOfflineLlmRow]: install state is
     *  **per-pair** (the model for the current source→target), not the global
     *  [OnDeviceLlmBackend.isInstalled]. On devices the native engine can't
     *  run — 32-bit, or arm64 under a binary translator (Houdini crashes the
     *  engine — see BinaryTranslation) — the row stays VISIBLE but inert:
     *  [renderOfflineBackendRow] swaps the stat grid + switch for a
     *  "Not supported on this device" reason line and un-clickables the row,
     *  the same treatment the LLM rows get for a failed hardware floor.
     *  Otherwise the same three-state tap branch — enabled+installed →
     *  disable dialog · installed → enable · else → download — but keyed off
     *  [offlineInstalled] for the current pair. */
    private fun wireBergamotBackendRow() {
        val bergamot = TranslationBackendRegistry.byId("bergamot") as? BergamotBackend
        if (bergamot == null) {
            rowBackendBergamot.isGone = true
            dividerBackendBergamot.isGone = true
            return
        }
        rowBackendBergamot.setOnClickListener {
            val switch = rowBackendBergamot.findViewById<MaterialSwitch>(R.id.switchOfflineToggle)
            val installed = offlineInstalled(bergamot)
            when {
                prefs.bergamotEnabled && installed -> {
                    switch.isChecked = false
                    callbacks.showBergamotDisableDialog()
                }
                installed -> {
                    switch.isChecked = true
                    callbacks.enableInstalledBergamot()
                }
                else -> callbacks.startBergamotDownload()
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Offline backend row (settings_row_backend_offline) — C7 redesign.
    // Header row with title + status icon + switch, then a 4-column stat
    // grid (Quality | Speed | RAM | Disk). Used by MlKit + every
    // OnDeviceLlmBackend; online instances render in OnlineServicesController.
    // ─────────────────────────────────────────────────────────────────────

    /** Backend IDs whose download is in flight; the row swaps its status
     *  icon to an indeterminate ProgressBar while present in this set.
     *  Mutated by [setBackendDownloading], called from [SettingsBottomSheet]
     *  at download job start/end. */
    private val offlineDownloadingIds = mutableSetOf<BackendId>()

    /** True iff [backend] uses the C7 `settings_row_backend_offline` layout.
     *  Today: any [OnDeviceLlmBackend] subclass plus [MlKitBackend]. */
    private fun isOfflineRowBackend(backend: TranslationBackend): Boolean =
        backend is OnDeviceLlmBackend || backend is MlKitBackend || backend is BergamotBackend

    /** Round the half-step [StarRating] (0.0–5.0) into the [1, 5] integer
     *  bucket used for the a11y label mapping (Bad / Okay / Good / Better).
     *  Half-up rounding: 1.0→1, 2.5→3, 3.5→4, 5.0→5. Clamped to [1, 5] so
     *  we never surface "0 stars" in the spoken description (the 4 existing
     *  offline backends never emit ratings below 1.0; the clamp is
     *  defense-in-depth for a future low-rated backend). The visible stars
     *  use [toHalfSteps5] for half-step resolution; this is only the label. */
    private fun StarRating.toIntStars5(): Int =
        (this + 0.5f).toInt().coerceIn(1, 5)

    /** Convert the [StarRating] to an integer count of half-steps in [0, 10]
     *  for visible rendering. 1.0→2, 2.5→5, 3.5→7, 5.0→10. Each star slot
     *  consumes 2 half-steps (one for the left half, one for the right);
     *  [bindStarCell] maps the count to filled/half/outline drawables. */
    private fun StarRating.toHalfSteps5(): Int =
        (this * 2f).toInt().coerceIn(0, 10)

    /** Map the 1–5 star count to the existing four-label scale for the
     *  composed row contentDescription. The label set has four buckets
     *  (Bad / Okay / Good / Better), so 4 and 5 stars both surface as
     *  "Better quality" — TalkBack still reads the full numeric form via
     *  the visible stars; this is just the prose adjective. */
    @StringRes private fun qualityLabelRes(stars5: Int): Int = when (stars5) {
        1 -> R.string.tr_service_quality_bad
        2 -> R.string.tr_service_quality_okay
        3 -> R.string.tr_service_quality_good
        else -> R.string.tr_service_quality_better
    }

    @StringRes private fun speedLabelRes(stars5: Int): Int = when (stars5) {
        1 -> R.string.tr_service_speed_very_slow
        2 -> R.string.tr_service_speed_slow
        3 -> R.string.tr_service_speed_okay
        else -> R.string.tr_service_speed_fast
    }

    /** Read the per-backend `*Enabled` pref. Null for ML Kit (no pref —
     *  it's the always-on fallback). */
    private fun enabledPrefFor(backendId: BackendId): Boolean? = when (backendId) {
        "qwen_mnn"       -> prefs.qwenMnnEnabled
        "qwen35_mnn_2b"  -> prefs.qwen35Mnn2bEnabled
        "gemma_e2b_mnn"  -> prefs.gemmaE2bEnabled
        "hymt_mnn"       -> prefs.hyMtEnabled
        "hymt2_mnn"      -> prefs.hyMt2Enabled
        "bergamot"       -> prefs.bergamotEnabled
        else             -> null
    }

    /** Install state for an offline row. ML Kit is bundled (always installed);
     *  Bergamot is **per-pair** (the model for the current source+target must be
     *  present); the on-device LLM tiers are global. Centralized here so the
     *  three render call sites stay agnostic to the per-pair distinction. */
    private fun offlineInstalled(backend: TranslationBackend): Boolean = when (backend) {
        is MlKitBackend       -> true
        is BergamotBackend    -> backend.manager.isInstalled(
            SourceLanguageProfiles[prefs.sourceLangId].translationCode, prefs.targetLang)
        is OnDeviceLlmBackend -> backend.isInstalled()
        else                  -> false
    }

    /** Whether Mozilla's Bergamot model set has a path (direct or English-pivot)
     *  for the current source→target. `supportsPair == false` means no model
     *  exists for this pair, so [renderOfflineBackendRow] shows the row inert
     *  with an unsupported-pair message instead of a download affordance that
     *  silently no-ops. Source is resolved through translationCode — matching
     *  setup/runtime — so e.g. Traditional Chinese (zh-Hant) → "zh". */
    private fun bergamotPairSupported(backend: BergamotBackend): Boolean =
        backend.manager.supportsPair(
            SourceLanguageProfiles[prefs.sourceLangId].translationCode, prefs.targetLang)

    /** The id'd divider that precedes an offline row (so the deprecation gate
     *  can hide it together with the row). Null for rows with no id'd preceding
     *  divider (Gemma — first row in the card; ML Kit — id-less divider). */
    private fun dividerForOfflineRow(backendId: BackendId): View? = when (backendId) {
        "qwen_mnn"       -> dividerBackendQwenMnn
        "qwen35_mnn_2b"  -> dividerBackendQwen35Mnn2b
        "hymt_mnn"       -> dividerBackendHyMt
        "hymt2_mnn"      -> dividerBackendHyMt2
        "bergamot"       -> dividerBackendBergamot
        else             -> null
    }

    /** Full visual bind for an offline backend row. Idempotent — called on
     *  initial bind, on Settings resume, and after
     *  [TranslationBackend.refreshStatus] returns. Owns the entire layout:
     *  title, stat grid (or hardware-incompat replacement), status icon
     *  (or busy ProgressBar), switch state, warning sub-row, and the
     *  composed row [View.setContentDescription]. */
    private fun renderOfflineBackendRow(row: View, backend: TranslationBackend) {
        val onDeviceLlm = backend as? OnDeviceLlmBackend

        // Deprecation gate (generic — driven by CatalogEntry.deprecated via
        // OnDeviceLlmBackend.isDeprecated). A deprecated model's row is shown
        // only while the model is fully installed, so nobody can start a fresh
        // download of a retired model. Re-evaluated on every refresh (this
        // method runs from refreshAllBackendStatuses), so deleting the model
        // while Settings is open hides the row and its preceding divider.
        val deprecated = onDeviceLlm?.isDeprecated() == true
        if (deprecated) {
            val installed = offlineInstalled(backend)
            row.isGone = !installed
            dividerForOfflineRow(backend.id)?.isGone = !installed
            if (!installed) return
        }

        val rowTitle =
            if (backend is BergamotBackend) ctx.getString(R.string.bergamot_row_title)
            else backend.displayName
        val title = row.findViewById<TextView>(R.id.tvOfflineTitle)
        val header = row.findViewById<View>(R.id.offlineHeaderRow)
        title.text = rowTitle
        // Warning-colored "⚠ DEPRECATED ⚠" badge on the title line (deprecated
        // + installed rows only; the not-installed case returned above).
        row.findViewById<TextView>(R.id.tvOfflineDeprecatedBadge).isVisible = deprecated
        // "LLM" chip on the title line — the offline half of the question
        // ServiceType.isLlm answers for the online rows. Bergamot and ML Kit
        // are classic NMT engines and get none. Set before the disabled branch
        // below returns: what a backend *is* doesn't depend on whether this
        // device can run it.
        val llmBadge = row.findViewById<TextView>(R.id.tvLlmBadge)
        llmBadge.isVisible = onDeviceLlm != null

        val isMlKit = backend is MlKitBackend

        val grid = row.findViewById<View>(R.id.cardStatGrid)
        val incompat = row.findViewById<TextView>(R.id.tvOfflineHardwareIncompat)
        val iconWrap = row.findViewById<View>(R.id.ivStatusIconWrap)
        val switch = row.findViewById<MaterialSwitch>(R.id.switchOfflineToggle)
        val warning = row.findViewById<TextView>(R.id.tvOfflineWarningLine)

        // "Visible but inert" branch — the row stays so the user sees what's
        // unavailable and why, but the stat grid + status icon + switch are
        // replaced by a single reason line. Three triggers:
        //   • on-device LLM whose device fails the hardware floor (arch / RAM)
        //   • Bergamot on a device the native engine can't run: 32-bit, or
        //     arm64 under a binary translator (Houdini SIGSEGVs the engine —
        //     see BinaryTranslation). Device-level, so it outranks the
        //     per-pair line below.
        //   • Bergamot when Mozilla ships no model for the current source→target
        //     pair — this is per-pair, so it's re-evaluated on every refresh and
        //     the row's interactivity is toggled here (not in the one-time
        //     wiring), so switching to a supported pair re-enables the row.
        val disabledReason: String? = when {
            onDeviceLlm != null && !onDeviceLlm.meetsHardwareRequirements() ->
                onDeviceLlm.hardwareIncompatibilityReason()
            backend is BergamotBackend && !backend.supportsNativeRuntime() ->
                ctx.getString(R.string.bergamot_device_unsupported)
            backend is BergamotBackend && !bergamotPairSupported(backend) ->
                ctx.getString(R.string.bergamot_pair_unsupported)
            else -> null
        }
        if (backend is BergamotBackend) row.isClickable = disabledReason == null
        if (disabledReason != null) {
            // Compact, recessed "disabled" presentation: collapse the header's
            // 48dp touch-target floor so the title pairs tightly with the reason
            // line, add symmetric vertical padding (the collapse otherwise
            // leaves the title flush against the row's top edge), and drop the
            // title to ptTextHint — the same recessed tone the online rows'
            // neutral status line uses — so the whole cell reads as a single
            // inactive group. (The reason line is ptTextHint via the layout.)
            header.minimumHeight = 0
            val vPad = row.paddingBottom   // 10dp from the layout
            row.setPadding(row.paddingLeft, vPad, row.paddingRight, vPad)
            title.setTextColor(ctx.themeColor(R.attr.ptTextHint))
            // Recede the LLM chip with the title. Left at its default
            // ptTextMuted it would outshine the ptTextHint title it sits
            // beside — the brightest thing in a row that is meant to read as
            // one inactive group.
            llmBadge.setTextColor(ctx.themeColor(R.attr.ptTextHint))
            grid.isGone = true
            incompat.text = disabledReason
            incompat.isVisible = true
            iconWrap.isGone = true
            switch.isGone = true
            warning.isGone = true
            row.contentDescription = "$rowTitle. $disabledReason"
            return
        }

        // Enabled presentation — restore the header floor, the layout's top
        // padding (0dp; the header's height supplies the top inset), and the
        // primary title + chip colors. The row View is recycled across
        // refreshes and backends, so an earlier disabled pass may have
        // collapsed/padded/muted them.
        header.minimumHeight =
            ctx.resources.getDimensionPixelSize(R.dimen.offline_row_header_min_height)
        row.setPadding(row.paddingLeft, 0, row.paddingRight, row.paddingBottom)
        title.setTextColor(ctx.themeColor(R.attr.ptText))
        llmBadge.setTextColor(ctx.themeColor(R.attr.ptTextMuted))
        grid.isVisible = true
        incompat.isGone = true
        iconWrap.isVisible = true

        val qualityStars5 = backend.qualityStars.toIntStars5()
        bindStarCell(row.findViewById(R.id.cellQuality),
            R.string.offline_backend_quality_label,
            backend.qualityStars.toHalfSteps5())
        val speedStars5 = backend.speedStars?.toIntStars5()
        bindStarCell(row.findViewById(R.id.cellSpeed),
            R.string.offline_backend_speed_label,
            backend.speedStars?.toHalfSteps5() ?: 0)

        val ramText = when {
            backend is BergamotBackend ->
                ctx.getString(R.string.offline_backend_bergamot_ram)
            else -> onDeviceLlm?.let { humanSize(ctx, it.availMemFloorBytes) }
                ?: ctx.getString(R.string.offline_backend_mlkit_ram)
        }
        // On-device LLM rows show base + on-disk mmap-cache size, tinted with a
        // warning color when the cache is present (it's an extra ~model-sized
        // copy that lives only while the model is in use).
        val diskFootprint = onDeviceLlm?.diskFootprint()
        val diskText = when {
            backend is BergamotBackend ->
                ctx.getString(R.string.offline_backend_bergamot_disk)
            diskFootprint != null -> diskFootprint.human
            else -> ctx.getString(R.string.offline_backend_mlkit_disk)
        }
        bindMonoCell(row.findViewById(R.id.cellRam),
            R.string.offline_backend_ram_label, ramText)
        bindMonoCell(row.findViewById(R.id.cellDisk),
            R.string.offline_backend_disk_label, diskText,
            warning = diskFootprint?.hasCache == true)

        updateOfflineStatusIconAndSwitch(row, backend)
        // ML Kit gets no switch — user-confirmed deviation from C7. GONE
        // (not INVISIBLE) so the status icon slides to the right edge
        // instead of leaving a switch-shaped gap to its right. The header
        // row's minHeight (48dp = MaterialSwitch's touch-target height)
        // keeps every offline row the same height regardless of whether a
        // switch is drawn.
        if (isMlKit) switch.isGone = true

        bindOfflineWarningLine(row, backend)
        row.contentDescription = composeOfflineRowA11y(
            backend, qualityStars5, speedStars5, ramText, diskText)
    }

    /** Render [halfSteps] (0–10) across the 5 star slots. Each slot consumes
     *  2 half-steps: if the rating reaches the slot's upper edge, render the
     *  filled drawable in ptText; if it lands on the lower edge, render the
     *  half drawable (its own two-tone colors take over — tint cleared);
     *  otherwise render the outline in ptTextDim. */
    private fun bindStarCell(cell: View, @StringRes labelRes: Int, halfSteps: Int) {
        cell.findViewById<TextView>(R.id.tvStatLabel).setText(labelRes)
        val filledTint = ColorStateList.valueOf(ctx.themeColor(R.attr.ptText))
        val emptyTint = ColorStateList.valueOf(ctx.themeColor(R.attr.ptTextDim))
        val starIds = intArrayOf(R.id.star1, R.id.star2, R.id.star3, R.id.star4, R.id.star5)
        for ((idx, id) in starIds.withIndex()) {
            val iv = cell.findViewById<ImageView>(id)
            val slotStart = idx * 2
            when {
                halfSteps >= slotStart + 2 -> {
                    iv.setImageResource(R.drawable.ic_offline_star_filled)
                    ImageViewCompat.setImageTintList(iv, filledTint)
                }
                halfSteps >= slotStart + 1 -> {
                    iv.setImageResource(R.drawable.ic_offline_star_half)
                    // Half-star drawable owns its own colors (ptText for the
                    // filled side, ptTextDim for the outline). Clear the tint
                    // so neither side is recolored to a single value.
                    ImageViewCompat.setImageTintList(iv, null)
                }
                else -> {
                    iv.setImageResource(R.drawable.ic_offline_star_outline)
                    ImageViewCompat.setImageTintList(iv, emptyTint)
                }
            }
        }
    }

    private fun bindMonoCell(cell: View, @StringRes labelRes: Int, value: String, warning: Boolean = false) {
        cell.findViewById<TextView>(R.id.tvStatLabel).setText(labelRes)
        cell.findViewById<TextView>(R.id.tvStatValue).apply {
            text = value
            // Reset to the default (?attr/ptText) when not warning — rows are
            // recycled, so a previously-warned cell must clear its tint.
            setTextColor(cell.context.themeColor(if (warning) R.attr.ptWarning else R.attr.ptText))
        }
    }

    /** Status icon (downloaded-check / cloud-down / busy spinner) plus the
     *  switch checked state, derived from install state + busy state +
     *  pref. Called by [renderOfflineBackendRow] for the full bind and by
     *  [setBackendDownloading] / refresh*Switch for targeted updates. */
    private fun updateOfflineStatusIconAndSwitch(row: View, backend: TranslationBackend) {
        val isMlKit = backend is MlKitBackend
        val installed = offlineInstalled(backend)
        val downloading = backend.id in offlineDownloadingIds

        val icon = row.findViewById<ImageView>(R.id.ivStatusIcon)
        val progress = row.findViewById<ProgressBar>(R.id.pbStatusDownloading)
        if (downloading) {
            icon.isGone = true
            progress.isVisible = true
        } else {
            progress.isGone = true
            icon.setImageResource(
                if (installed) R.drawable.ic_status_downloaded
                else R.drawable.ic_status_cloud_down
            )
            // Downloaded badge: the drawable owns its own colors (accent
            // disc + card-colored check), so clear the tint that the
            // layout applies for the cloud-down case. Cloud-down stays
            // muted via setImageTintList.
            ImageViewCompat.setImageTintList(
                icon,
                if (installed) null
                else ColorStateList.valueOf(ctx.themeColor(R.attr.ptTextMuted))
            )
            icon.contentDescription = ctx.getString(
                if (installed) R.string.offline_backend_downloaded_cd
                else R.string.offline_backend_not_downloaded_cd
            )
            icon.isVisible = true
        }

        if (!isMlKit) {
            val switch = row.findViewById<MaterialSwitch>(R.id.switchOfflineToggle)
            val enabledPref = enabledPrefFor(backend.id) ?: false
            switch.isChecked = installed && enabledPref
            switch.isVisible = true
        }
    }

    private fun bindOfflineWarningLine(row: View, backend: TranslationBackend) {
        val tv = row.findViewById<TextView>(R.id.tvOfflineWarningLine)
        val status = backend.status
        if (status is BackendStatus.Info && status.tone == Tone.Warning) {
            tv.text = status.resolve(ctx)
            tv.isVisible = true
        } else {
            tv.isGone = true
        }
    }

    private fun composeOfflineRowA11y(
        backend: TranslationBackend,
        qualityStars5: Int,
        speedStars5: Int?,
        ramText: String,
        diskText: String,
    ): String {
        val isMlKit = backend is MlKitBackend
        val installed = offlineInstalled(backend)
        val downloadedLabel = ctx.getString(
            if (installed) R.string.offline_backend_downloaded_cd
            else R.string.offline_backend_not_downloaded_cd
        )
        // ML Kit has no enabled pref — treat as always-enabled fallback.
        val enabledPref = enabledPrefFor(backend.id) ?: isMlKit
        val enabledLabel = ctx.getString(
            if (enabledPref && installed) R.string.offline_backend_enabled_cd
            else R.string.offline_backend_disabled_cd
        )
        val quality = ctx.getString(qualityLabelRes(qualityStars5))
        val speed = speedStars5?.let { ctx.getString(speedLabelRes(it)) }
        return if (speed != null) {
            ctx.getString(R.string.offline_backend_row_a11y_fmt,
                backend.displayName, quality, speed, ramText, diskText,
                downloadedLabel, enabledLabel)
        } else {
            ctx.getString(R.string.offline_backend_row_a11y_no_speed_fmt,
                backend.displayName, quality, ramText, diskText,
                downloadedLabel, enabledLabel)
        }
    }

    /** Called by [SettingsBottomSheet] at download job start/end. While the
     *  ID is in [offlineDownloadingIds], the row's status icon renders as
     *  an indeterminate spinner; otherwise it falls back to the
     *  downloaded-check / cloud-down vector based on install state. */
    fun setBackendDownloading(backendId: BackendId, downloading: Boolean) {
        val changed = if (downloading) offlineDownloadingIds.add(backendId)
                      else offlineDownloadingIds.remove(backendId)
        if (!changed) return
        val row = backendRowById(backendId) ?: return
        val backend = TranslationBackendRegistry.byId(backendId) ?: return
        updateOfflineStatusIconAndSwitch(row, backend)
    }

}
