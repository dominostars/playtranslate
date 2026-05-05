package com.playtranslate

import android.app.Notification
import android.app.NotificationChannel
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import android.app.NotificationManager
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.annotation.MainThread
import androidx.core.app.NotificationCompat
import com.playtranslate.model.TranslationResult
import com.google.mlkit.nl.translate.TranslateLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.hardware.display.DisplayManager
import com.playtranslate.dictionary.DictionaryManager
import com.playtranslate.language.SourceLanguageProfiles
import com.playtranslate.ui.TranslationOverlayView

private const val TAG = "CaptureService"
private const val NOTIF_ID = 1001
private const val CHANNEL_ID = "playtranslate_capture"

/**
 * Foreground service that owns the OCR + translation pipeline.
 *
 * Translation priority:
 *  1. DeepL   — if an API key is configured in Settings
 *  2. Lingva  — free Google Translate proxy, no key required (default)
 *  3. ML Kit  — offline fallback when both online options are unavailable
 *
 * Notes are shown inline with the result only when ML Kit is used.
 */
class CaptureService : Service() {

    // ── Binder ────────────────────────────────────────────────────────────

    inner class LocalBinder : Binder() {
        fun getService(): CaptureService = this@CaptureService
    }

    private val binder = LocalBinder()
    override fun onBind(intent: Intent): IBinder = binder

    // ── Coroutines ────────────────────────────────────────────────────────

    internal val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // ── Region session ───────────────────────────────────────────────────
    //
    // All state tied to a specific capture region lives here. On region
    // change the old session is cancelled and replaced atomically — no
    // field-by-field reset needed.

    // ── Pipeline ──────────────────────────────────────────────────────────

    /** TextPaint for measuring relative character widths (furigana positioning). */
    internal val furiganaPaint by lazy {
        TextPaint().apply {
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            textSize = 100f  // arbitrary — only relative proportions matter
        }
    }

    internal val ocrManager get() = OcrManager.instance
    private var translationManager: TranslationManager? = null  // ML Kit offline fallback
    private var deeplTranslator: DeepLTranslator?  = null       // optional, key required
    private var lingvaTranslator: LingvaTranslator? = null      // always present after configure()

    /**
     * The set of displays the user has selected to translate. P1 introduces
     * this as the source of truth; downstream phases (P4) wire per-display
     * loops and modes off of it. Per-display state in CaptureService
     * (region, status bar, OCR pipeline) all key off the displayId the
     * caller passes — there is no implicit "primary" inside the capture
     * pipeline. The in-app UI's notion of "current display" is tracked
     * separately via [primaryGameDisplayId] / [lastInteractedDisplayId].
     */
    internal var gameDisplayIds: Set<Int> = emptySet()

    /**
     * The last display whose floating icon (or touch sentinel) received
     * user input. Used by [primaryGameDisplayId] to pick the "intent"
     * display for hotkey one-shots and the in-app panel UI when more
     * than one display is selected. Null until the user touches anything.
     *
     * Setter refreshes [activeRegionLiveData] so the in-app region label
     * tracks whatever display the user is currently focused on.
     */
    internal var lastInteractedDisplayId: Int? = null
        set(value) {
            if (field == value) return
            field = value
            recalcActiveRegionLiveData()
        }

    /**
     * Best-effort "primary" display for actions that need a single target
     * (volume-button hotkey one-shot, in-app result panel, and the
     * region label / Translate button text in the in-app UI). Prefers
     * the last-interacted display so the user's recent intent wins;
     * falls back to the first id in [gameDisplayIds] (insertion order
     * is stable thanks to LinkedHashSet); finally [Display.DEFAULT_DISPLAY]
     * if the set is empty.
     */
    fun primaryGameDisplayId(): Int =
        lastInteractedDisplayId
            ?: gameDisplayIds.firstOrNull()
            ?: android.view.Display.DEFAULT_DISPLAY
    /** Always returns the current source-language translation code from Prefs.
     *  Single source of truth for the language pair — callers don't need to
     *  notify the service when prefs change; [ensureLanguageManagersFor]
     *  picks up drift at each capture entry point. */
    internal val sourceLang: String
        get() = SourceLanguageProfiles[Prefs(this).sourceLangId].translationCode
    /** Tracks whether [configureSaved] has populated capture-time state
     *  (displayIds). Keeping this distinct from manager presence means
     *  a translation-only path that constructs translators via
     *  [ensureLanguageManagersFor] doesn't cause [isConfigured]
     *  to report ready-for-capture when displays haven't actually
     *  been set. */
    private var hasCaptureStateConfigured: Boolean = false

    /**
     * Per-display capture-region overrides. A floating-icon menu region
     * pick or a one-shot drag-defined region writes to this map keyed by
     * the display the gesture targeted. [activeRegionForDisplay] consults
     * this map first, then falls back to the persisted per-display
     * selection (and ultimately to a full-screen region).
     */
    private val overrideRegions: MutableMap<Int, RegionEntry> = mutableMapOf()

    /** True when [displayId] currently has an override region applied. */
    fun isOverrideForDisplay(displayId: Int): Boolean = displayId in overrideRegions

    /**
     * Resolve the active region for [displayId]: override map first, then
     * persisted per-display selection from Prefs ([Prefs.selectedRegionIdForDisplay]),
     * finally a full-screen fallback. Modes call this every cycle so a mid-
     * session region change picks up without a configureSaved round-trip.
     */
    fun activeRegionForDisplay(displayId: Int): RegionEntry {
        overrideRegions[displayId]?.let { return it }
        val prefs = Prefs(this)
        val regionId = prefs.selectedRegionIdForDisplay(displayId)
        if (regionId.isNotEmpty()) {
            prefs.getRegionList().firstOrNull { it.id == regionId }?.let { return it }
        }
        return DEFAULT_REGION
    }

    /**
     * Observable region for the in-app panel UI (button label, etc.).
     * Tracks the *primary* display's active region — the user expects the
     * UI to describe whatever display they last interacted with.
     * Updated by [recalcActiveRegionLiveData] from setters that change the
     * primary id or the primary's region.
     */
    val activeRegionLiveData = MutableLiveData(DEFAULT_REGION)

    /**
     * Backwards-compat accessor for legacy single-display callers in the
     * in-app UI. Returns the primary display's active region — the same
     * value the LiveData tracks. Per-display logic in modes / one-shot
     * should call [activeRegionForDisplay] directly with their own id.
     */
    val activeRegion: RegionEntry get() = activeRegionForDisplay(primaryGameDisplayId())

    /** Re-evaluate the primary's active region and emit it on the LiveData
     *  if it changed. Cheap to call; safe to invoke from any setter that
     *  could affect what the primary's region resolves to. */
    private fun recalcActiveRegionLiveData() {
        val current = activeRegionForDisplay(primaryGameDisplayId())
        if (activeRegionLiveData.value != current) {
            activeRegionLiveData.value = current
        }
    }

    // ── Outbound event streams ────────────────────────────────────────────
    //
    // One-shot captures use [CaptureSession] returned from
    // [captureOnce] / [processScreenshot]. Everything else (live mode,
    // hold-to-preview, service-level "Idle" on config change) flows
    // through [panelState]. The activity observes both — the one-shot
    // session takes precedence while one is active because its
    // emissions land in the same VM after [panelState]'s sticky replay
    // has been deduped by the VM.

    /** Background panel state — the latest state any non-one-shot
     *  producer (live mode, hold-to-preview) has emitted. Sticky
     *  (StateFlow) so a STOP→START reattach delivers the current
     *  value to a re-subscribed observer; the VM identity-dedupes
     *  service-emitted results separately from locally-emitted ones,
     *  so the replay can't displace a drag-sentence local result
     *  the VM is now showing.
     *
     *  [PanelState.Idle] is the initial / cleared state; consumers
     *  treat it as "no signal" rather than "show Idle UI" so a
     *  sticky Idle replay doesn't reset the VM on every reattach.
     *  Transient "Idle" UI signals (config change, region swap)
     *  go through [statusUpdates] instead. */
    private val _panelState = MutableStateFlow<PanelState>(PanelState.Idle)
    val panelState: StateFlow<PanelState> = _panelState.asStateFlow()

    /** Transient service-level status signals — used by [configureSaved]
     *  and [resetConfiguration] to ask the activity to flip its panel
     *  to "Idle" when a region/config change invalidates the current
     *  display. SharedFlow with replay = 0 so the signal fires once;
     *  late subscribers don't see it (which is intentional — a stale
     *  "Idle" shouldn't override a later valid result on STOP→START
     *  reattach). */
    private val _statusUpdates = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val statusUpdates: SharedFlow<String> = _statusUpdates.asSharedFlow()

    /** Hold-to-preview loading state. StateFlow because consumers (the
     *  floating icon's loading indicator) need the current value, not a
     *  stream of transitions. */
    private val _holdLoading = MutableStateFlow(false)
    val holdLoading: StateFlow<Boolean> = _holdLoading.asStateFlow()

    // ── Internal emit helpers (callable from sibling capture modes) ──────

    internal fun emitResult(result: TranslationResult) {
        _panelState.value = PanelState.Result(result)
    }
    internal fun emitError(message: String) {
        _panelState.value = PanelState.Error(message)
    }
    internal fun emitLiveNoText() {
        _panelState.value = PanelState.Searching
    }
    internal fun emitHoldLoading(loading: Boolean) { _holdLoading.value = loading }

    /** Observable degraded translation state (ML Kit fallback). */
    val degradedState = MutableLiveData(false)
    val translationDegraded: Boolean get() = degradedState.value == true

    private val mainHandler = Handler(Looper.getMainLooper())

    internal fun setDegraded(degraded: Boolean) {
        if (translationDegraded == degraded) return
        degradedState.postValue(degraded)
        // Post to main thread: setDegraded is called from background coroutines,
        // and syncIconState sets View properties. Posting also ensures postValue's
        // update has been applied before we read degradedState.value.
        mainHandler.post { syncIconState() }
    }

    /** Push current service state to every floating icon. Called automatically
     *  by [setLiveDisplays] (on the empty↔non-empty transition), [setDegraded],
     *  and when icons are installed or torn down (from
     *  PlayTranslateAccessibilityService.installFloatingIconForDisplay /
     *  hideFloatingIconForDisplay). */
    fun syncIconState() {
        val a11y = PlayTranslateAccessibilityService.instance ?: return
        a11y.setIconsLiveMode(isLive)
        a11y.setIconsDegraded(translationDegraded)
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate")
        instance = this
        createNotificationChannel()

        // Register hotkey callbacks (whichever service started first)
        PlayTranslateAccessibilityService.instance?.registerHotkeyCallbacks()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand action=${intent?.action}")
        // Android requires startForeground() within 5s of startForegroundService()
        startForeground(NOTIF_ID, buildNotification())
        // Immediately evaluate — may stopForeground if no game-screen presence yet
        updateForegroundState()
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.w(TAG, "onTaskRemoved")
        super.onTaskRemoved(rootIntent)
        PlayTranslateAccessibilityService.instance?.hideFloatingIcon("task_removed")
    }

    override fun onDestroy() {
        Log.w(TAG, "onDestroy")
        instance = null
        stopLive()
        serviceScope.cancel()
        translationManager?.close()
        deeplTranslator?.close()
        lingvaTranslator?.close()
        // Outbound event flows hold no Activity references; collectors
        // attach with their own lifecycle scope and detach naturally.
        // No callback nulling needed here anymore.
        PlayTranslateAccessibilityService.instance?.onHotkeyActivated = null
        PlayTranslateAccessibilityService.instance?.onHotkeyReleased = null
        super.onDestroy()
    }

    // ── Public API ────────────────────────────────────────────────────────

    /** Apply a temporary override region to [displayId]. Does not change
     *  language/engines. Persisted region selection on Prefs is unchanged —
     *  this is a transient runtime override (e.g. a one-shot drag-defined
     *  region) that masks the persisted choice until [clearOverride]
     *  (or a fresh [configureSaved]) clears it. */
    fun configureOverride(displayId: Int, region: RegionEntry) {
        overrideRegions[displayId] = region
        afterRegionChange(setOf(displayId))
    }

    /** Clear the override for [displayId] and signal a region change so
     *  live mode caches and the in-app region label re-resolve from Prefs.
     *  Always fires [afterRegionChange] — the floating-menu clear-region
     *  path rewrites Prefs *before* calling this, so side effects must run
     *  even when there was no runtime override to drop, otherwise the
     *  cleared display's overlay/cleanRef/region label stay pinned to the
     *  prior region. */
    fun clearOverride(displayId: Int) {
        overrideRegions.remove(displayId)
        afterRegionChange(setOf(displayId))
    }

    /** Clear every per-display region override. Used by [configureSaved]
     *  to reset the runtime to a clean "use the persisted selection"
     *  state across all displays. */
    private fun clearAllOverrides() {
        if (overrideRegions.isEmpty()) return
        val cleared = overrideRegions.keys.toSet()
        overrideRegions.clear()
        afterRegionChange(cleared)
    }

    /** Side effects shared by every region-changing entry point: hide
     *  any region indicator + active translation overlay so they don't
     *  show stale state, cancel in-flight one-shots, refresh live mode
     *  if it was running (so the new region takes effect on the next
     *  cycle). [changedDisplayIds] identifies the displays whose regions
     *  changed — only those displays get their overlay hidden, their
     *  live mode refreshed, and their region indicator flashed. Other
     *  displays' caches are still valid and shouldn't be invalidated
     *  (avoids a wasteful re-OCR cycle and a brief stale-box flash, and
     *  prevents PinholeOverlayMode from grabbing its own still-visible
     *  overlay pixels in the next raw capture because hide and refresh
     *  scopes are now aligned). */
    private fun afterRegionChange(changedDisplayIds: Set<Int>) {
        recalcActiveRegionLiveData()
        val a11y = PlayTranslateAccessibilityService.instance
        a11y?.hideRegionIndicator()
        for (id in changedDisplayIds) a11y?.hideTranslationOverlayForDisplay(id)
        oneShotCaptureJob?.cancel()
        oneShotManager.cancel()
        if (isLive) {
            // Region change — only the changed displays' modes need to
            // pick up the new region on their next OCR cycle via
            // [activeRegionForDisplay]. refresh() clears their cached
            // state (cachedBoxes, cleanRef, dedup) so the next cycle
            // reads the new region instead of replaying stale dedup/cache
            // values.
            for (id in changedDisplayIds) liveModes[id]?.refresh()
            for (id in changedDisplayIds) flashRegionIndicator(id)
        }
    }

    /** Configure capture-time state (the set of displays). Region for each
     *  display is resolved per-call from [Prefs.selectedRegionIdForDisplay]
     *  — there is no longer a "the saved region" on the service. Any
     *  outstanding per-display overrides are cleared (a fresh configure
     *  treats the persisted Prefs as the source of truth). */
    fun configureSaved(
        displayIds: Set<Int>,
        primaryDisplayId: Int = displayIds.firstOrNull() ?: 0,
    ) {
        // Hide overlays for displays leaving the selection — the wasLive
        // path tears them down via setLiveDisplays(emptySet)→mode.stop, but
        // the not-live path has no other cleanup, so a residual override
        // overlay on a now-deselected display would otherwise stay
        // painted on a screen the app no longer captures.
        val removedIds = gameDisplayIds - displayIds
        val a11y = PlayTranslateAccessibilityService.instance
        for (id in removedIds) a11y?.hideTranslationOverlayForDisplay(id)

        gameDisplayIds   = displayIds
        // Track the user's intent for the primary so the in-app UI focuses
        // on it. Keeps lastInteractedDisplayId fresh for the new selection.
        if (primaryDisplayId in displayIds) {
            lastInteractedDisplayId = primaryDisplayId
        }
        overrideRegions.clear()
        hasCaptureStateConfigured = true
        ensureLanguageManagersFor(snapshotTranslationTarget())
        _statusUpdates.tryEmit(getString(R.string.status_idle))
        // Treat saved-region reconfig as a region change for the running
        // pipeline: refreshes live modes' cached boxes/cleanRef/dedup so
        // the next cycle reads the new region instead of replaying stale
        // state, cancels in-flight one-shots tied to the prior region,
        // and clears each display's overlay. Symmetric with
        // configureOverride / clearOverride, both of which already do
        // this. Pass the full display set because configureSaved is the
        // fan-out path — every selected display's region selection in
        // Prefs may have been rewritten by the caller before this call.
        // Region indicator should only flash on still-selected displays,
        // so removed ids stay out of the changed set.
        afterRegionChange(displayIds)
    }

    /** Single-display convenience for un-migrated callers. Resolves to
     *  the multi-display path with the supplied id treated as primary. */
    fun configureSaved(displayId: Int) {
        configureSaved(
            displayIds = Prefs(this).captureDisplayIds.ifEmpty { setOf(displayId) },
            primaryDisplayId = displayId,
        )
    }

    /** Immutable snapshot of the translation pair + DeepL key at the moment
     *  a translation request enters the service. Threaded through every
     *  downstream call so that a concurrent [Prefs] change mid-batch can't
     *  poison a cache entry (translated under the new pair but keyed under
     *  the old): both the key and the translator selection derive from the
     *  *same* target value. */
    private data class TranslationTarget(
        val source: String,
        val target: String,
        val deeplKey: String,
    )

    /** Capture a [TranslationTarget] from current [Prefs]. Called once at
     *  the outermost layer of each translation entry point; downstream calls
     *  thread the captured value rather than re-reading Prefs, so mid-batch
     *  changes can't create inconsistency between key-derivation and
     *  translator selection. */
    private fun snapshotTranslationTarget(): TranslationTarget {
        val prefs = Prefs(this)
        return TranslationTarget(
            source = SourceLanguageProfiles[prefs.sourceLangId].translationCode,
            target = prefs.targetLang,
            deeplKey = prefs.deeplApiKey,
        )
    }

    /** Reconcile the shared translator fields with [target]. Called at the
     *  top of every translation call so drift from a pref-change in another
     *  code path (onboarding, Settings) is picked up automatically.
     *
     *  Delegates backend-toggle cache invalidation to [translationCache.reconcilePreferredBackend].
     *  Pair changes are handled by the cache key itself — no explicit clear
     *  needed; same-pair re-ensures preserve the "unchanged UI labels stay
     *  cached" benefit that drives live mode. */
    private fun ensureLanguageManagersFor(target: TranslationTarget) {
        translationCache.reconcilePreferredBackend(
            if (target.deeplKey.isNotBlank()) "deepl" else "lingva"
        )

        // DeepL — only when a key is configured
        if (target.deeplKey.isNotBlank()) {
            val same = deeplTranslator?.let {
                it.apiKey == target.deeplKey &&
                it.sourceLang == target.source &&
                it.targetLang == target.target
            } ?: false
            if (!same) {
                deeplTranslator?.close()
                deeplTranslator = DeepLTranslator(target.deeplKey, target.source, target.target)
            }
        } else if (deeplTranslator != null) {
            deeplTranslator?.close()
            deeplTranslator = null
        }

        // Lingva — always present, recreate only when language pair changes
        val lingvaNeedsUpdate = lingvaTranslator?.let {
            it.sourceLang != target.source || it.targetLang != target.target
        } ?: true
        if (lingvaNeedsUpdate) {
            lingvaTranslator?.close()
            lingvaTranslator = LingvaTranslator(target.source, target.target)
        }

        // ML Kit — offline fallback; download silently in the background.
        val existing = translationManager
        val needsNewManager = existing == null ||
                existing.sourceLang != target.source ||
                existing.targetLang != target.target
        if (needsNewManager) {
            existing?.close()
            val newManager = TranslationManager(target.source, target.target)
            translationManager = newManager
            serviceScope.launch {
                try {
                    newManager.ensureModelReady()
                } catch (e: Exception) {
                    Log.w(TAG, "ML Kit model download failed (offline fallback unavailable): ${e.message}")
                }
            }
        }
    }

    /** Start a one-shot capture cycle on [displayId]. Caller observes the
     *  returned [CaptureSession]'s [CaptureSession.state] for
     *  progress/result. Cancels any prior one-shot session. */
    fun captureOnce(displayId: Int = primaryGameDisplayId()): CaptureSession {
        oneShotCaptureJob?.cancel()
        val state = MutableStateFlow<CaptureState>(
            CaptureState.InProgress(getString(R.string.status_capturing))
        )
        val job = serviceScope.launch { runCaptureCycle(displayId, state) }
        attachCancellationTerminal(job, state)
        oneShotCaptureJob = job
        return CaptureSession(state.asStateFlow(), job)
    }

    /**
     * Processes a pre-captured screenshot bitmap instead of taking a new one.
     * Used when the screenshot must be taken before an activity appears on screen
     * (e.g. single-screen region capture from the floating menu).
     */
    fun processScreenshot(raw: Bitmap, displayId: Int = primaryGameDisplayId()): CaptureSession {
        oneShotCaptureJob?.cancel()
        val state = MutableStateFlow<CaptureState>(
            CaptureState.InProgress(getString(R.string.status_capturing))
        )
        val job = serviceScope.launch { runProcessCycle(raw, displayId, state) }
        attachCancellationTerminal(job, state)
        oneShotCaptureJob = job
        return CaptureSession(state.asStateFlow(), job)
    }

    // ── Cancellation correctness for one-shot sessions ────────────────────
    //
    // Cancellation must always end up at [CaptureState.Cancelled] — never
    // at [CaptureState.Failed] (would surface a cryptic error flash) and
    // never stuck at [CaptureState.InProgress] (would replay stale
    // "Capturing" status on STOP→START). Four complementary safeguards
    // achieve this; they each handle a different scenario, and removing
    // any one of them silently re-introduces a class of regression.
    //
    //   A. Pipeline-level CancellationException re-throw
    //      ([runCaptureOcrTranslate], [runProcessCycle]).
    //      Their broad `catch (Exception)` blocks would otherwise swallow
    //      cancellation and convert it to [PipelineOutcome.Failed] /
    //      [CaptureState.Failed] with a runtime message like
    //      "StandaloneCoroutine was cancelled". A leading
    //      `catch (CancellationException) { throw e }` lets cancellation
    //      reach the launched coroutine's completion.
    //
    //   B. Translator-fallback CancellationException re-throw
    //      ([translate]'s DeepL and Lingva blocks).
    //      Without these, a cancelled capture would waterfall through
    //      Lingva → ML Kit doing wasted fallback work that the cancelled
    //      caller can never deliver.
    //
    //   C. Structured fan-out via coroutineScope
    //      ([translateGroupsSeparately]).
    //      Per-group async translations are children of a coroutineScope
    //      inside the calling capture job, NOT of the long-lived
    //      serviceScope. Cancelling the capture job cancels the children
    //      structurally so they don't keep mutating translationCache /
    //      degradedState after the session has been marked terminal.
    //
    //   D. invokeOnCompletion safety net
    //      ([attachCancellationTerminal] below).
    //      For the cancel-before-dispatch case (job cancelled while the
    //      launched coroutine is still queued), no exception is ever
    //      thrown and the pipeline body never runs — so layers A–C have
    //      nothing to do. The Job.invokeOnCompletion hook still fires
    //      and writes [CaptureState.Cancelled] explicitly.
    //
    // Activity collectors complete the picture by treating Cancelled as
    // silent — MainActivity clears its session reference, TranslationResultActivity
    // calls finish(). The combined effect: every one-shot session
    // transitions to exactly one of Done / NoText / Failed / Cancelled
    // before its observer detaches, with no flashes or stuck states.
    //
    // If you add a new `catch (Exception)` block anywhere on the capture
    // hot path, prefix it with `catch (CancellationException) { throw e }`
    // — the test `TranslationResultViewModelDedupTest` doesn't exercise
    // this path (the full pipeline is too Android-heavy for unit tests),
    // so a regression here won't fail any current automated check.

    /** Layer D from "Cancellation correctness" above: write
     *  [CaptureState.Cancelled] when [job] completes with a
     *  CancellationException and [state] is still InProgress. The
     *  InProgress check is defensive against a hypothetical race
     *  between cancellation and the pipeline's own terminal write —
     *  in practice the pipeline writes terminal states only after
     *  awaiting through suspension points where cancellation would
     *  have already propagated. */
    private fun attachCancellationTerminal(
        job: Job,
        state: MutableStateFlow<CaptureState>,
    ) {
        job.invokeOnCompletion { cause ->
            if (cause is CancellationException && state.value is CaptureState.InProgress) {
                state.value = CaptureState.Cancelled
            }
        }
    }

    /** One-shot capture from a pre-captured bitmap: walks [state]
     *  through Capturing → OCR → Translating → final Done/NoText/Failed.
     *  Owned by the [CaptureSession] returned from [processScreenshot]. */
    private suspend fun runProcessCycle(
        raw: Bitmap,
        displayId: Int,
        state: MutableStateFlow<CaptureState>,
    ) {
        if (!isConfigured) {
            state.value = CaptureState.Failed("Not configured — tap Translate to set up")
            raw.recycle()
            return
        }
        var bitmap: Bitmap = raw
        try {
            state.value = CaptureState.InProgress(getString(R.string.status_capturing))
            val mgr = PlayTranslateAccessibilityService.instance?.screenshotManager
            val screenshotPath = mgr?.saveToCache(raw, displayId)

            val region = activeRegionForDisplay(displayId)
            val statusBarHeight = getStatusBarHeightForDisplay(displayId)
            val top    = maxOf((raw.height * region.top).toInt(), statusBarHeight)
            val left   = (raw.width  * region.left).toInt()
            val bottom = (raw.height * region.bottom).toInt()
            val right  = (raw.width  * region.right).toInt()
            bitmap = cropBitmap(raw, top, bottom, left, right)

            // blackoutFloatingIcon may recycle its input (immutable path),
            // so `bitmap` may be stale after this call. A nested try/finally
            // on ocrBitmap keeps its cleanup local and survives OCR exceptions
            // without the outer finally having to follow ownership transfers.
            val ocrBitmap = blackoutFloatingIcon(bitmap, displayId, left, top)
            val ocrResult = try {
                state.value = CaptureState.InProgress(getString(R.string.status_ocr))
                ocrManager.recognise(ocrBitmap, sourceLang, screenshotWidth = raw.width)
            } finally {
                if (!ocrBitmap.isRecycled) ocrBitmap.recycle()
            }

            if (ocrResult == null) {
                state.value = CaptureState.NoText(noTextMessage(displayId))
                return
            }

            state.value = CaptureState.InProgress(getString(R.string.status_translating))
            val (translated, note) = translateGroups(ocrResult.groupTexts)

            val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            state.value = CaptureState.Done(
                TranslationResult(
                    originalText   = ocrResult.fullText,
                    segments       = ocrResult.segments,
                    translatedText = translated,
                    timestamp      = timestamp,
                    screenshotPath = screenshotPath,
                    note           = note
                )
            )
        } catch (e: CancellationException) {
            // Let cancellation propagate; invokeOnCompletion writes Cancelled.
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Process cycle failed: ${e.message}", e)
            state.value = CaptureState.Failed(e.message ?: "Unknown error")
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    // ── Live mode ─────────────────────────────────────────────────────────
    //
    // Interaction-driven: capture once, show overlay, then wait for user
    // input. On input → hide overlay + start debounce timer. When the
    // timer expires (no further input) → capture again.

    /** Backing field for [liveModeState]. Written ONLY by [setLiveDisplays]
     *  on the empty↔non-empty boolean transition. */
    private val _liveModeState = MutableLiveData(false)

    /** Observable live mode state. Observers receive boolean transitions.
     *  Read-only externally; the only writer is [setLiveDisplays]. */
    val liveModeState: LiveData<Boolean> get() = _liveModeState

    /** True iff any per-display [LiveMode] is currently running.
     *  Ground truth derived from [liveModes]; [liveModeState] mirrors
     *  it for observers. Reading this between [setLiveDisplays]'s map
     *  mutation and its LiveData write is consistent because both
     *  happen on the main thread inside the mutator with no
     *  intervening yield points. */
    val isLive: Boolean get() = liveModes.isNotEmpty()

    /**
     * Per-display live-mode instances. All entries share the same
     * [Prefs.overlayMode] — multi-display doesn't expose per-display mode
     * selection in the UI (per-display instances are an implementation
     * detail for state isolation, since each display owns its own
     * cachedBoxes / cleanRef state).
     *
     * **Mutated EXCLUSIVELY by [setLiveDisplays].** Direct writes from
     * anywhere else risk breaking the `liveModes ↔ screenshotManager.loops
     * ↔ onGameInputs ↔ touchSentinels` invariant that previously had to be
     * maintained by convention across multiple call sites; the visibility
     * narrowing here is what enforces "one mutator" at compile time.
     */
    private val liveModes: MutableMap<Int, LiveMode> = mutableMapOf()

    private val oneShotManager = OneShotManager(this)
    private var oneShotCaptureJob: Job? = null

    /**
     * Listens for capture displays going away (external monitor unplugged,
     * virtual display destroyed) or transitioning to STATE_OFF (foldable
     * folded with the inactive panel selected). Per-display modes pause +
     * resume rather than tearing down all of live mode unless the entire
     * selection has gone offline.
     */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            // Restore a previously-pruned display from the user's persisted
            // selection. onDisplayRemoved subtracts the unplugged id from
            // [gameDisplayIds]; without this, a re-plugged display stayed
            // permanently excluded from reconciliation until something else
            // forced configureSaved to run again. Gated on isLive so a
            // force-stop (all displays disconnected → stopLive) doesn't
            // auto-restart on reconnect — that path still requires an
            // explicit user start.
            if (!isLive) return
            val persisted = Prefs(this@CaptureService).captureDisplayIds
            if (displayId !in persisted) return
            if (displayId !in gameDisplayIds) {
                gameDisplayIds = gameDisplayIds + displayId
            }
            reconcileLiveModes("displayAdded($displayId)")
        }
        override fun onDisplayChanged(displayId: Int) {
            if (displayId !in gameDisplayIds) return
            val st = getSystemService(DisplayManager::class.java)
                ?.getDisplay(displayId)?.state
            Log.d(TAG, "displayListener.onDisplayChanged($displayId) state=$st")
            reconcileLiveModes("displayChanged($displayId state=$st)")
        }
        override fun onDisplayRemoved(displayId: Int) {
            if (!isLive) return
            if (displayId !in gameDisplayIds) return
            // Drop the disconnected display from the active set first so the
            // setLiveDisplays() / stopLive() that follow see the pruned state.
            val pruned = gameDisplayIds - displayId
            gameDisplayIds = pruned
            if (pruned.isEmpty()) {
                Log.w(TAG, "All capture displays disconnected, stopping live mode")
                // stopLive() routes through setLiveDisplays(emptySet()) and
                // runs the full teardown (setDegraded, belt-and-suspenders
                // cleanup). Toast fires AFTER the teardown so the user-visible
                // message lines up with the actual stopped state.
                stopLive()
                Toast.makeText(
                    this@CaptureService,
                    "Capture display disconnected. Live mode stopped.",
                    Toast.LENGTH_LONG
                ).show()
                return
            }
            if (displayId == lastInteractedDisplayId) {
                // The display the user was focused on went away — pick
                // a still-connected one as the new primary so the in-app
                // panel UI and hotkey routing keep working.
                Log.w(TAG, "Primary capture display $displayId disconnected; switching primary to ${pruned.first()}")
                lastInteractedDisplayId = pruned.first()
            }
            setLiveDisplays(pruned.filterNot { shouldSkipDisplay(it) }.toSet())
        }
    }

    fun startLive() {
        oneShotCaptureJob?.cancel()
        // Reset the panel to Searching so the activity sees an
        // immediate transition into live mode (rather than a stale
        // result lingering until the first cycle lands).
        _panelState.value = PanelState.Searching

        val prefs = Prefs(this)

        // Compute target = user-selected displays minus those we should
        // skip right now (STATE_OFF, or hosting PlayTranslate's own UI
        // under multi-select). InAppOnly's "force {first}" rule is
        // applied inside [setLiveDisplays] so callers don't need to know.
        val activeIds = gameDisplayIds.ifEmpty { setOf(primaryGameDisplayId()) }
        val target = activeIds.filterNot { shouldSkipDisplay(it) }.toSet()
        Log.d(TAG, "startLive: activeIds=$activeIds target=$target prefs.overlayMode=${prefs.overlayMode}")
        setLiveDisplays(target)
    }

    /**
     * What [OverlayFlavor] a fresh live-mode instance should use right now,
     * given current Prefs. Single source of truth for the mode-class gating
     * that used to live inline in [startLive] and the (now-removed)
     * `installLiveModeForDisplay` helper.
     *
     * Intentionally does not take a [displayId] — flavor is uniform across
     * the active display set. The "should this display run InAppOnly?"
     * question is handled by [Prefs.shouldUseInAppOnlyMode], which
     * already gates on `captureDisplayIds.size <= 1`. Callers that need
     * the per-display target collapsing (force `{primary}` for InAppOnly)
     * do so when computing the target set passed to the mutator.
     */
    private fun desiredFlavor(prefs: Prefs = Prefs(this)): OverlayFlavor {
        if (Prefs.shouldUseInAppOnlyMode(this)) return OverlayFlavor.IN_APP_ONLY
        return when (prefs.overlayMode) {
            OverlayMode.FURIGANA -> OverlayFlavor.FURIGANA
            OverlayMode.TRANSLATION -> OverlayFlavor.TRANSLATION
        }
    }

    /**
     * The single mutator for [liveModes] and its derived state. Every
     * structural change to "what's running" — start, stop, reconcile,
     * multi-window swap, region change, hot-plug — flows through here.
     *
     * Diff semantics:
     *  - id in current but not in target → stop and remove.
     *  - id in both, but the running instance's flavor doesn't match
     *    [desiredFlavor] → stop, recreate (rebuild).
     *  - id in target but not in current → construct, add.
     *
     * IN_APP_ONLY is single-display by design — when it's the desired
     * flavor and [target] is non-empty, [target] is collapsed to its
     * first id. Callers don't need to know.
     *
     * Step ordering at the LiveData transition is deliberate (matches the
     * pre-refactor contract at the original startLive lines 787-793):
     *  1. stop removed/rebuilt modes (each owns its own loop+input+
     *     sentinel teardown via its [LiveMode.stop]),
     *  2. populate [liveModes] with new instances (DO NOT start yet),
     *  3. fire [liveModeState] / [updateForegroundState] / [syncIconState]
     *     IF the boolean transitioned, so observers reading
     *     `holdBehavior` / `isInAppOnly` see the consistent populated map
     *     and the right flavor mix synchronously,
     *  4. start the new modes,
     *  5. flash the region indicator on each newly-installed display.
     *
     * The display listener is registered on empty→non-empty and
     * unregistered on non-empty→empty (it's only useful while live).
     *
     * Returns true if any structural change happened (add/stop/rebuild).
     * [onMultiWindowChanged] uses the return to decide whether to fall
     * through to a refresh-only pass for clean-ref invalidation.
     */
    @MainThread
    private fun setLiveDisplays(target: Set<Int>): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "setLiveDisplays must run on the main thread " +
                "(got ${Thread.currentThread().name})"
        }
        val prefs = Prefs(this)
        val flavor = desiredFlavor(prefs)

        // IN_APP_ONLY is a single-display path by design. Collapse the
        // target so callers don't have to know about the rule.
        val actualTarget = if (flavor == OverlayFlavor.IN_APP_ONLY && target.isNotEmpty()) {
            setOf(target.first())
        } else target

        // Snapshot — diff sets are computed against an immutable copy so
        // subsequent mutation of [liveModes] can't perturb them.
        val snapshot: Map<Int, LiveMode> = liveModes.toMap()
        val toStop = snapshot.keys - actualTarget
        val toRebuild = (snapshot.keys intersect actualTarget)
            .filter { snapshot.getValue(it).flavor != flavor }
            .toSet()
        val toAdd = actualTarget - snapshot.keys

        val structuralChange = toStop.isNotEmpty() || toRebuild.isNotEmpty() || toAdd.isNotEmpty()
        if (!structuralChange) return false

        // Construct new instances first so that an a11y-missing failure
        // aborts before we tear down the existing modes (overlay flavors
        // need PlayTranslateAccessibilityService.instance; InAppOnly does
        // not). Matches the original startLive guard at lines 763-772.
        val a11y = PlayTranslateAccessibilityService.instance
        val needsA11y = flavor != OverlayFlavor.IN_APP_ONLY &&
            (toRebuild.isNotEmpty() || toAdd.isNotEmpty())
        if (needsA11y && a11y == null) {
            Log.w(TAG, "setLiveDisplays: accessibility service not connected; cannot start $flavor. Aborting.")
            return false
        }

        val newInstances: Map<Int, LiveMode> = (toAdd + toRebuild).associateWith { id ->
            when (flavor) {
                OverlayFlavor.IN_APP_ONLY -> InAppOnlyMode(this, id)
                OverlayFlavor.FURIGANA -> FuriganaMode(this, a11y!!, id)
                OverlayFlavor.TRANSLATION -> PinholeOverlayMode(this, a11y!!, id)
            }
        }

        // 1. Stop removed AND rebuilt modes.
        for (id in toStop + toRebuild) {
            liveModes.remove(id)?.stop()
        }

        // 2. Populate map with new instances BEFORE the LiveData write.
        for ((id, mode) in newInstances) {
            liveModes[id] = mode
        }

        // 3. LiveData / foreground / icon state — only when the boolean flips.
        val wasLive = _liveModeState.value == true
        val willBeLive = liveModes.isNotEmpty()
        if (wasLive != willBeLive) {
            _liveModeState.value = willBeLive
            updateForegroundState()
            syncIconState()
            // Display listener tracks the empty↔non-empty transition only.
            val dm = getSystemService(DisplayManager::class.java)
            if (willBeLive) {
                // Defensive double-unregister: harmless if not registered.
                dm?.unregisterDisplayListener(displayListener)
                dm?.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
            } else {
                dm?.unregisterDisplayListener(displayListener)
            }
        }

        // 4. Start newly-installed modes AFTER state observers see the populated map.
        //    Re-entry guard: updateForegroundState (called above) can call
        //    stopLive() if there's no visible surface, which routes through
        //    setLiveDisplays(emptySet()) and clears liveModes. In that case our
        //    newInstances are no longer in the map; don't start them — that
        //    would leak an untracked, running LiveMode. Identity check is fine
        //    because LiveMode subclasses don't override equals.
        for ((id, mode) in newInstances) {
            if (liveModes[id] === mode) {
                mode.start()
                flashRegionIndicator(id)
            }
        }

        return true
    }

    /**
     * Skip OCR / capture on [displayId] when the display is powered down
     * or PlayTranslate's own MainActivity is foregrounded on it AND we
     * have other displays selected. Single-display setups always keep
     * capturing — the existing single-screen routing handles the
     * "app on game display" case via [Prefs.shouldUseInAppOnlyMode].
     *
     * State check uses [Display.STATE_ON] equality. Other states pause
     * to be safe; the foldable use case (STATE_OFF) and the doze states
     * are all "not actively rendered to user".
     */
    private fun shouldSkipDisplay(displayId: Int): Boolean {
        val display = getSystemService(DisplayManager::class.java)
            ?.getDisplay(displayId)
        if (display == null) {
            Log.d(TAG, "shouldSkipDisplay($displayId): null display → skip")
            return true
        }
        if (display.state != android.view.Display.STATE_ON) {
            Log.d(TAG, "shouldSkipDisplay($displayId): state=${display.state} (STATE_ON=${android.view.Display.STATE_ON}) → skip")
            return true
        }
        if (gameDisplayIds.size > 1
            && MainActivity.foregroundDisplayId == displayId) {
            // foregroundDisplayId is null whenever none of our activities
            // is resumed, so the AND with a non-null displayId already
            // gates this branch on "some PlayTranslate activity is on top
            // of this display". Includes TranslationResultActivity /
            // LanguageSetupActivity / etc., not just MainActivity — any
            // of our UI on a capture display means OCR there is wasted.
            Log.d(TAG, "shouldSkipDisplay($displayId): app foregrounded on it (size=${gameDisplayIds.size}) → skip")
            return true
        }
        return false
    }

    /**
     * Bring [liveModes] in line with [shouldSkipDisplay] across the
     * selection. Called from the display listener (display state change),
     * [MainActivity.isInForeground] / [MainActivity.foregroundDisplayId]
     * setters (foreground change), and any other point that changes the
     * skip predicate. InAppOnlyMode (single-display by design) is left
     * alone — its resume path is a full [startLive] / [stopLive] cycle.
     *
     * [reason] threads the call site for diagnostic logs.
     */
    fun reconcileLiveModes(reason: String = "?") {
        if (!isLive) {
            Log.v(TAG, "reconcileLiveModes($reason): !isLive, no-op")
            return
        }
        if (liveModes.values.any { it.flavor == OverlayFlavor.IN_APP_ONLY }) {
            Log.v(TAG, "reconcileLiveModes($reason): InAppOnly active, no-op")
            return
        }
        val target = gameDisplayIds.filterNot { shouldSkipDisplay(it) }.toSet()
        Log.d(TAG, "reconcileLiveModes($reason): gameDisplayIds=$gameDisplayIds liveModes=${liveModes.keys} → target=$target")
        setLiveDisplays(target)
    }

    /** True when any active live mode is In-App Only. By design all modes
     *  share the same prefs.overlayMode + useInAppOnly gating, so this is
     *  effectively "are we in InAppOnly mode" — but checking via [Any] avoids
     *  silent assumptions if that invariant ever shifts. */
    val isInAppOnly: Boolean
        get() = isLive && liveModes.values.any { it.flavor == OverlayFlavor.IN_APP_ONLY }

    /**
     * Describes what a hold gesture will do in the current state. Mirrors the
     * branching in [holdStart] + [OneShotManager.createProcessor] so the UI
     * subtext can describe the actual behavior. Used by
     * [MainActivity.updateRegionButton].
     */
    enum class HoldBehavior {
        /** Live translation overlay is visible; hold peeks through. */
        HIDE_TRANSLATIONS,
        /** Live furigana overlay is visible; hold forces a translation one-shot. */
        SHOW_TRANSLATIONS_OVER_FURIGANA,
        /** Default: hold shows a translation one-shot (auto mode = translation). */
        SHOW_TRANSLATIONS,
        /** Default: hold shows a furigana one-shot (auto mode = furigana). */
        SHOW_FURIGANA,
    }

    val holdBehavior: HoldBehavior
        get() {
            // All per-display modes share the same prefs.overlayMode, so any
            // single mode's flavor is representative of the active mix.
            val isFurigana = liveModes.values.any { it.flavor == OverlayFlavor.FURIGANA }
            // Visible translation overlay → hold peeks through it
            if (isLive && !isFurigana && !isInAppOnly) {
                return HoldBehavior.HIDE_TRANSLATIONS
            }
            // Visible furigana overlay → hold forces a translation one-shot
            if (isLive && isFurigana) {
                return HoldBehavior.SHOW_TRANSLATIONS_OVER_FURIGANA
            }
            // Not live, or InAppOnly live → hold runs a one-shot in the
            // user's currently-selected overlay mode
            return when (Prefs(this).overlayMode) {
                OverlayMode.FURIGANA -> HoldBehavior.SHOW_FURIGANA
                else -> HoldBehavior.SHOW_TRANSLATIONS
            }
        }

    /**
     * Called from MainActivity.onMultiWindowModeChanged after the multi-window
     * companion var has been updated. The viewport-level predicate
     * [Prefs.isSingleScreen] re-evaluates on every call, so UI routing fixes
     * itself automatically — but the live-mode class selection is sticky,
     * computed once at live-start time. A running Pinhole/Furigana session
     * entering split-screen with `hideGameOverlays` enabled wants
     * InAppOnlyMode instead; a running InAppOnlyMode exiting to fullscreen
     * wants an overlay mode.
     *
     * [setLiveDisplays] handles the mode-class swap automatically via its
     * flavor-mismatch detector (running flavor != [desiredFlavor] → rebuild).
     * If no structural change happens (no add/remove/rebuild), fall through
     * to a refresh() pass to clear each mode's clean-reference bitmap, which
     * would otherwise flicker through scene-change recovery on its own as
     * the viewport contents change underneath the running cycle.
     *
     * Note: the InAppOnly trigger uses [Prefs.shouldUseInAppOnlyMode], which
     * gates on `captureDisplayIds.size <= 1`. The pre-refactor implementation
     * gated on `gameDisplayIds.size == 1` instead, which differed from the
     * canonical pref check after a hot-plug (gameDisplayIds is mutated by
     * the display listener; captureDisplayIds is the persisted user
     * selection). The new behavior aligns with [MainActivity]'s UI predicate
     * so runtime and UI never disagree about whether InAppOnly should be
     * in play.
     */
    fun onMultiWindowChanged() {
        if (!isLive) return
        val activeIds = gameDisplayIds.ifEmpty { setOf(primaryGameDisplayId()) }
        val target = activeIds.filterNot { shouldSkipDisplay(it) }.toSet()
        val structuralChanged = setLiveDisplays(target)
        if (!structuralChanged) {
            Log.d(TAG, "onMultiWindowChanged: no structural change, refreshing ${liveModes.size} mode(s)")
            liveModes.values.forEach { it.refresh() }
        } else {
            Log.d(TAG, "onMultiWindowChanged: structural change applied (target=$target)")
        }
    }

    fun stopLive() {
        Log.i(TAG, "stopLive() called (isLive=$isLive, modes=${liveModes.keys})", Throwable("stopLive caller"))
        // setLiveDisplays(emptySet()) handles: stop every running mode (each
        // tears down its own loop+input+sentinel+overlay), unregister the
        // display listener on the non-empty→empty transition, fire LiveData
        // false, run updateForegroundState/syncIconState.
        setLiveDisplays(emptySet())
        setDegraded(false)
        // Belt-and-suspenders fan-out — each LiveMode.stop() should already
        // have torn down its own loop / input / overlay, but historically these
        // calls have caught misbehaving modes that left state behind.
        // TODO(P1): with setLiveDisplays(emptySet()) guaranteeing per-mode
        //   teardown via the canonical mutator, this fan-out should be removable.
        PlayTranslateAccessibilityService.instance?.screenshotManager?.stopAllLoops()
        PlayTranslateAccessibilityService.instance?.stopInputMonitoring()
        PlayTranslateAccessibilityService.instance?.hideTranslationOverlay()
        // Don't reset _panelState here — let the last live result
        // linger so a STOP→START reattach still shows it. The VM's
        // identity dedup keeps the replay from re-running lookups.
    }

    // ── Unified loop handlers ─────────────────────────────────────────────

    /** Trigger a fresh capture cycle in every active live mode (e.g. after
     *  hold-release). With per-display modes, all of them refresh together
     *  since hold pause is global. */
    fun refreshLiveOverlay() {
        if (!isLive) return
        Log.d(TAG, "REFRESH: refreshLiveOverlay called for ${liveModes.size} mode(s)")
        liveModes.values.forEach { it.refresh() }
    }

    /** One-shot: capture, OCR, translate, show overlay (not live mode). */

    /** True while a hold gesture or modal UI is active — suppresses overlay display in live mode. */
    var holdActive = false

    /**
     * Common hold-to-preview begin sequence used by both the in-app button
     * and the gamepad hotkey. Gates live display via [holdActive], stops the
     * live capture loop, destroys the existing overlay view so the one-shot
     * can render cleanly, and launches the one-shot.
     *
     * [holdActive] doubles as the pause signal for [PinholeOverlayMode] (its
     * cycle polls the flag directly). [ScreenshotManager.stopLoop] is needed
     * for modes that drive capture through the loop (Furigana) — Pinhole
     * calls `requestRaw` directly and would otherwise keep screenshotting.
     * Hiding the existing overlay view up front is what prevents its visible
     * content (e.g. furigana boxes) from being swapped in-place to shimmer
     * placeholders during the one-shot, and also prevents the live loop's
     * hide/restore cycle from racing with the one-shot's own clean capture.
     */
    private fun beginHoldPreview(mode: OverlayMode?, displayId: Int) {
        beginHoldPreview(mode, setOf(displayId), displayId)
    }

    /** Multi-display variant — used by the in-app translate button hold and
     *  the hotkey hold, both of which target every selected non-foreground
     *  display. The floating-icon path still uses the single-display
     *  overload so its hold stays scoped to the icon's own display. */
    private fun beginHoldPreview(
        mode: OverlayMode?,
        displayIds: Set<Int>,
        panelDisplayId: Int,
    ) {
        holdActive = true
        if (isLive) {
            // Hold pause is global — stop every per-display loop and hide
            // every translation overlay. Each fan-out cycle then paints its
            // own result on its own display.
            PlayTranslateAccessibilityService.instance
                ?.screenshotManager?.stopAllLoops()
            PlayTranslateAccessibilityService.instance
                ?.hideTranslationOverlay()
        }
        oneShotManager.runHoldOverlay(
            forceMode = mode,
            displayIds = displayIds,
            panelDisplayId = panelDisplayId,
        )
    }

    /** Selected capture displays minus the ones we shouldn't capture
     *  right now. Used by the global one-shot triggers (hotkey, in-app
     *  translate button) so a hold runs on every screen the user is
     *  actually looking at game content on. Reuses [shouldSkipDisplay]
     *  so STATE_OFF (folded panel etc.) and the app-foregrounded
     *  display are both filtered out — same predicate live mode uses
     *  for reconciliation, kept identical so a one-shot can never
     *  target a display live mode wouldn't.
     *
     *  Returns empty when multi-display + every selected display is
     *  skip-eligible (e.g. PlayTranslate foregrounded on display A
     *  while display B is folded). Callers no-op rather than
     *  fall-through to capturing the foreground display, which would
     *  OCR the app's own UI and publish a garbage panel result.
     *
     *  Single-display always returns the only display — the skip
     *  predicate's foreground branch is gated on `size > 1`, so the
     *  only way the filter can empty here is STATE_OFF, in which case
     *  captureScreen will return null and the cycle exits cleanly.
     *  Returning the display rather than empty preserves "the gesture
     *  always tries to do something on a single-display setup."
     *  Iteration order follows [gameDisplayIds] insertion order. */
    internal fun oneShotFanoutDisplayIds(): Set<Int> {
        val all = gameDisplayIds.ifEmpty { setOf(primaryGameDisplayId()) }
        val filtered = all.filter { !shouldSkipDisplay(it) }
        if (filtered.isNotEmpty()) return filtered.toSet()
        if (all.size == 1) return all
        return emptySet()
    }

    /** Picks the display that drives the in-app result panel during a
     *  fan-out one-shot. Prefers [primaryGameDisplayId] when it's in the
     *  fan-out target set (preserves the user's most recent intent), else
     *  the first target. */
    internal fun oneShotPanelDisplayId(targets: Set<Int>): Int {
        val primary = primaryGameDisplayId()
        return if (primary in targets) primary
        else targets.firstOrNull() ?: primary
    }

    /**
     * Common hold-to-preview end sequence. Cancels the one-shot (which hides
     * its overlay), clears [holdActive], and refreshes the live mode so it
     * resumes from a clean state. Safe to call in the pinhole-peek case
     * where no one-shot was launched — cancel on a null job is a no-op.
     */
    private fun endHoldPreview() {
        oneShotManager.cancel()
        holdActive = false
        if (isLive) {
            // Refresh every per-display mode — hold paused them all globally.
            liveModes.values.forEach { it.refresh() }
        }
    }

    /**
     * Single-display hold gesture for the floating icon — the icon's
     * onHoldStart passes its own [displayId] so the one-shot only runs on
     * that screen, even on multi-display setups where global triggers
     * (hotkey, in-app button) would fan out.
     */
    fun holdStart(displayId: Int) {
        lastInteractedDisplayId = displayId
        // Pinhole / translation-overlay live modes: "peek" through the
        // overlay at the game underneath, without running a one-shot.
        // PinholeOverlayMode's cycle polls [holdActive] and pauses itself.
        val isFurigana = liveModes.values.any { it.flavor == OverlayFlavor.FURIGANA }
        if (isLive && !isFurigana && !isInAppOnly) {
            holdActive = true
            PlayTranslateAccessibilityService.instance?.hideTranslationOverlay()
            return
        }
        _holdLoading.value = true
        val forced = if (isLive && isFurigana) {
            OverlayMode.TRANSLATION
        } else {
            null
        }
        beginHoldPreview(forced, displayId)
    }

    /**
     * Multi-display hold gesture used by the in-app translate button. Fans
     * the one-shot out to every selected display except whichever one the
     * activity is currently foregrounded on (the user is looking at game
     * content on the OTHER screens). The panel-bound result comes from the
     * primary non-foreground display so concurrent cycles don't race the
     * panel.
     */
    fun holdStartFanout() {
        val isFurigana = liveModes.values.any { it.flavor == OverlayFlavor.FURIGANA }
        if (isLive && !isFurigana && !isInAppOnly) {
            // Live translation overlay peek — hide so user can see game
            // underneath. Pure UI gesture, doesn't depend on fanout
            // targets (fires even when multi-display + everything
            // skip-eligible would otherwise no-op the capture path).
            holdActive = true
            PlayTranslateAccessibilityService.instance?.hideTranslationOverlay()
            return
        }
        val targets = oneShotFanoutDisplayIds()
        if (targets.isEmpty()) return
        val panelTarget = oneShotPanelDisplayId(targets)
        lastInteractedDisplayId = panelTarget
        _holdLoading.value = true
        val forced = if (isLive && isFurigana) {
            OverlayMode.TRANSLATION
        } else {
            null
        }
        beginHoldPreview(forced, targets, panelTarget)
    }

    /** End a hold-to-preview gesture (in-app translate button). */
    fun holdEnd() {
        _holdLoading.value = false
        endHoldPreview()
    }

    /**
     * Cancel a hold gesture (e.g. user started dragging on the floating icon).
     * Delegates to [endHoldPreview] so any in-flight one-shot (furigana live,
     * in-app-only live, or not-live mode) is cancelled before it can repaint
     * an overlay the user already dismissed, and so live mode is refreshed
     * back to its normal render cycle.
     */
    fun holdCancel() {
        _holdLoading.value = false
        endHoldPreview()
    }

    // ── Hotkey hold ─────────────────────────────────────────────────────

    private var hotkeyActive = false

    /** Begin a hotkey hold-to-preview with a forced overlay mode. Like the
     *  in-app translate button, the hotkey is a "global" trigger — it fans
     *  the one-shot out to every selected non-foreground display. The
     *  panel-bound result comes from the primary so concurrent cycles
     *  don't race the panel. */
    fun hotkeyHoldStart(mode: OverlayMode) {
        DetectionLog.log("Hotkey START: $mode (live=$isLive)")
        Log.d("HotkeyDbg", "hotkeyHoldStart: mode=$mode isConfigured=$isConfigured isLive=$isLive")
        if (hotkeyActive) return
        hotkeyActive = true
        val targets = oneShotFanoutDisplayIds()
        // No fan-out target (multi-display + every selected display is
        // skip-eligible). hotkeyActive is still set so the matching
        // hotkeyHoldEnd unwinds cleanly via its own gate.
        if (targets.isEmpty()) return
        val panelTarget = oneShotPanelDisplayId(targets)
        beginHoldPreview(mode, targets, panelTarget)
    }

    /** End a hotkey hold-to-preview. */
    fun hotkeyHoldEnd() {
        if (!hotkeyActive) return
        hotkeyActive = false
        DetectionLog.log("Hotkey END (live=$isLive)")
        endHoldPreview()
    }

    /**
     * Briefly flash the capture region indicator on the game display.
     * Called after a screenshot is captured so the indicator doesn't
     * appear in the screenshot.
     */
    /** "No source-language text on $displayId in $region" message. */
    internal fun noTextMessage(displayId: Int): String {
        val langName = java.util.Locale(sourceLang).getDisplayLanguage(java.util.Locale.ENGLISH)
            .replaceFirstChar { it.uppercase(java.util.Locale.ENGLISH) }
        return getString(R.string.status_no_text, langName, activeRegionForDisplay(displayId).label)
    }

    /** Flash the region indicator on [displayId] using that display's
     *  active region. Called by per-display modes after their own captures. */
    internal fun flashRegionIndicator(displayId: Int) {
        val a11y = PlayTranslateAccessibilityService.instance ?: return
        val dm = getSystemService(DisplayManager::class.java)
        val display = dm.getDisplay(displayId) ?: return
        a11y.showRegionIndicator(display, activeRegionForDisplay(displayId))
    }

    /** Run the shared OCR pipeline on a frame captured from [displayId].
     *  Every per-display parameter (active region, status bar height, icon
     *  rect to black out) is resolved for [displayId] — the pipeline has no
     *  notion of a "primary" display. Caller still owns [raw]. */
    internal suspend fun runOcr(raw: Bitmap, displayId: Int): OverlayToolkit.OcrPipelineResult? {
        return OverlayToolkit.runOcrPipeline(
            raw,
            activeRegionForDisplay(displayId),
            sourceLang,
            ocrManager,
            getStatusBarHeightForDisplay(displayId),
            PlayTranslateAccessibilityService.instance?.getFloatingIconRect(displayId),
            Prefs(this).compactOverlayIcon
        )
    }

    /**
     * Translate OCR groups and send the result to the in-app panel.
     * Returns per-group translations (for callers that also need them for overlay building).
     * Returns null if skipped (panel not visible and forceShow=false).
     */
    internal suspend fun translateAndSendToPanel(
        ocrResult: OcrManager.OcrResult,
        screenshotPath: String?,
        forceShow: Boolean = false
    ): List<Pair<String, String?>>? {
        if (!forceShow) {
            val appPanelVisible = !Prefs.isSingleScreen(this) && MainActivity.isInForeground
            if (!appPanelVisible) return null
        }
        val perGroup = translateGroupsSeparately(ocrResult.groupTexts)
        val translated = perGroup.joinToString("\n\n") { it.first }
        val note = perGroup.mapNotNull { it.second }.firstOrNull()
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        emitResult(
            com.playtranslate.model.TranslationResult(
                originalText   = ocrResult.fullText,
                segments       = ocrResult.segments,
                translatedText = translated,
                timestamp      = timestamp,
                screenshotPath = screenshotPath,
                note           = note
            )
        )
        return perGroup
    }

    /** Called by a per-display LiveMode when its OCR pass finds no source-
     *  language text on [displayId]: clears that display's overlay pair
     *  and notifies the in-app panel. The panel emit is intentionally
     *  global — there's a single panel and a single result/no-text state
     *  for it. The overlay teardown is per-display so a no-text outcome
     *  on display B doesn't take display A's still-valid overlay with it. */
    internal fun handleNoTextDetected(displayId: Int) {
        PlayTranslateAccessibilityService.instance?.hideTranslationOverlayForDisplay(displayId)
        emitLiveNoText()
    }

    /** Remove specific overlay boxes without rebuilding the entire view.
     *  [displayId] defaults to [primaryGameDisplayId] for legacy callers. */
    internal fun removeOverlayBoxes(
        toRemove: List<TranslationOverlayView.TextBox>,
        displayId: Int = primaryGameDisplayId(),
    ) {
        PlayTranslateAccessibilityService.instance?.removeOverlayBoxes(toRemove, displayId)
    }

    /**
     * Show a live translation overlay on [displayId] (defaults to
     * [primaryGameDisplayId] for legacy single-display callers; per-display
     * modes pass their own displayId).
     */
    internal fun showLiveOverlay(
        boxes: List<TranslationOverlayView.TextBox>,
        cropLeft: Int, cropTop: Int,
        screenshotW: Int, screenshotH: Int,
        force: Boolean = false,
        pinholeMode: Boolean = false,
        displayId: Int = primaryGameDisplayId(),
    ) {
        if (!force && holdActive) { Log.w("FuriganaDbg", "showLiveOverlay BLOCKED: holdActive=true"); return }
        val a11y = PlayTranslateAccessibilityService.instance
        if (a11y == null) { Log.w("FuriganaDbg", "showLiveOverlay BLOCKED: a11y=null"); return }
        val dm = getSystemService(DisplayManager::class.java)
        val display = dm.getDisplay(displayId)
        if (display == null) { Log.w("FuriganaDbg", "showLiveOverlay BLOCKED: display=null for id=$displayId"); return }
        Log.d("FuriganaDbg", "showLiveOverlay: ${boxes.size} boxes, crop=($cropLeft,$cropTop), screen=${screenshotW}x$screenshotH on display $displayId")
        a11y.showTranslationOverlay(display, boxes, cropLeft, cropTop, screenshotW, screenshotH, pinholeMode)
    }

    /**
     * Captures a clean screenshot via [ScreenshotManager].
     */
    internal suspend fun captureScreen(displayId: Int): Bitmap? {
        val mgr = PlayTranslateAccessibilityService.instance?.screenshotManager
        return mgr?.requestClean(displayId)
    }

    /**
     * @param preCaptured If non-null, use this bitmap instead of taking a new
     *   screenshot. Used by scene detection which already has a clean frame.
     */
    companion object {

        /** Process-scoped reference for in-process callers (e.g. DragLookupController). */
        @Volatile
        var instance: CaptureService? = null
            private set

        /** Empty-id, full-screen region used as the initial saved/active value
         *  before [configureSaved] runs and as the defensive fallback in
         *  [activeRegion]. Centralized so the literal isn't duplicated. */
        val DEFAULT_REGION = RegionEntry("", 0f, 1f)
    }

    /**
     * Blacks out the floating icon area so OCR doesn't read the icon's text.
     * Returns a mutable bitmap with the icon area painted black. If the icon
     * is not in the crop area, returns the original bitmap unchanged.
     *
     * @param bitmap   The (possibly immutable) cropped bitmap.
     * @param cropLeft Left offset of the crop in full-screen coordinates.
     * @param cropTop  Top offset of the crop in full-screen coordinates.
     */
    /** Black out [displayId]'s floating icon area in [bitmap] so OCR doesn't
     *  pick up the icon glyph as text. The icon rect is resolved per-display
     *  — passing the wrong displayId here is what was producing garbled OCR
     *  on multi-display before this refactor (the secondary's icon stayed
     *  visible to OCR; the primary's icon coords got applied to the wrong
     *  bitmap). */
    private fun blackoutFloatingIcon(
        bitmap: Bitmap,
        displayId: Int,
        cropLeft: Int = 0,
        cropTop: Int = 0,
    ): Bitmap =
        OverlayToolkit.blackoutFloatingIcon(bitmap, cropLeft, cropTop,
            PlayTranslateAccessibilityService.instance?.getFloatingIconRect(displayId),
            Prefs(this).compactOverlayIcon)

    fun resetConfiguration() {
        translationManager?.close()
        translationManager = null
        deeplTranslator  = null
        lingvaTranslator = null
        gameDisplayIds = emptySet()
        overrideRegions.clear()
        hasCaptureStateConfigured = false
        _statusUpdates.tryEmit(getString(R.string.status_idle))
    }

    /** True iff [configureSaved] has run (display + region set). Explicitly
     *  decoupled from translator presence so a translation-only path that
     *  constructs translators on-demand via [ensureLanguageManagersFor]
     *  doesn't trick callers into skipping display/region setup. */
    val isConfigured: Boolean get() = hasCaptureStateConfigured

    // ── Capture cycle ─────────────────────────────────────────────────────

    /** Full output from the shared capture pipeline, including overlay-ready data. */
    internal class PipelineResult(
        val result: TranslationResult,
        val groupBounds: List<android.graphics.Rect>,
        val groupTranslations: List<String>,
        val cropLeft: Int, val cropTop: Int,
        val screenshotW: Int, val screenshotH: Int,
        val ocrResult: OcrManager.OcrResult? = null
    )

    /** Outcome of [runCaptureOcrTranslate]. Callers translate to their
     *  own surface (one-shot writes a [CaptureState] on the session;
     *  live mode emits to its own flows). The pipeline doesn't
     *  side-effect any service-global flow on its own anymore. */
    internal sealed class PipelineOutcome {
        data class Success(val pipeline: PipelineResult) : PipelineOutcome()
        object NoText : PipelineOutcome()
        data class Failed(val message: String) : PipelineOutcome()
    }

    /**
     * Core capture → crop → OCR → translate pipeline shared by one-shot
     * and all live modes. Returns a [PipelineOutcome]; callers decide
     * how to surface success/no-text/failure on their own channel.
     */
    internal suspend fun runCaptureOcrTranslate(
        displayId: Int,
        onScreenshotTaken: (() -> Unit)? = null,
    ): PipelineOutcome {
        val raw: Bitmap = captureScreen(displayId)
            ?: return PipelineOutcome.Failed(
                "Screenshot failed for display $displayId. Try a different display in Settings."
            )
        onScreenshotTaken?.invoke()
        var bitmap: Bitmap? = raw
        try {
            val screenshotPath = PlayTranslateAccessibilityService.instance
                ?.screenshotManager?.saveToCache(raw, displayId)

            val region = activeRegionForDisplay(displayId)
            val statusBarHeight = getStatusBarHeightForDisplay(displayId)
            val top    = maxOf((raw.height * region.top).toInt(), statusBarHeight)
            val left   = (raw.width  * region.left).toInt()
            val bottom = (raw.height * region.bottom).toInt()
            val right  = (raw.width  * region.right).toInt()
            bitmap = cropBitmap(raw, top, bottom, left, right)

            // See runProcessCycle for the ownership rationale behind the
            // nested try/finally.
            val ocrBitmap = blackoutFloatingIcon(bitmap, displayId, left, top)
            val ocrResult = try {
                ocrManager.recognise(ocrBitmap, sourceLang, screenshotWidth = raw.width)
            } finally {
                if (!ocrBitmap.isRecycled) ocrBitmap.recycle()
            }

            if (ocrResult == null) return PipelineOutcome.NoText

            val perGroup = translateGroupsSeparately(ocrResult.groupTexts)
            val translated = perGroup.joinToString("\n\n") { it.first }
            val note = perGroup.mapNotNull { it.second }.firstOrNull()
            val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

            return PipelineOutcome.Success(
                PipelineResult(
                    result = TranslationResult(
                        originalText   = ocrResult.fullText,
                        segments       = ocrResult.segments,
                        translatedText = translated,
                        timestamp      = timestamp,
                        screenshotPath = screenshotPath,
                        note           = note
                    ),
                    groupBounds = ocrResult.groupBounds,
                    groupTranslations = perGroup.map { it.first },
                    cropLeft = left, cropTop = top,
                    screenshotW = raw.width, screenshotH = raw.height,
                    ocrResult = ocrResult
                )
            )
        } catch (e: CancellationException) {
            // Don't swallow cancellation — let it propagate so the
            // launched coroutine completes with cancellation, and the
            // session's invokeOnCompletion writes CaptureState.Cancelled
            // instead of surfacing it as a user-visible Failed.
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Capture cycle failed: ${e.message}", e)
            return PipelineOutcome.Failed(e.message ?: "Unknown error")
        } finally {
            bitmap?.let { if (!it.isRecycled) it.recycle() }
        }
    }

    /** One-shot capture: walks [state] through Capturing → final
     *  Done/NoText/Failed. Activities own the [state] flow via the
     *  [CaptureSession] returned from [captureOnce]. */
    private suspend fun runCaptureCycle(displayId: Int, state: MutableStateFlow<CaptureState>) {
        if (!isConfigured) {
            state.value = CaptureState.Failed("Not configured — tap Translate to set up")
            return
        }
        state.value = CaptureState.InProgress(getString(R.string.status_capturing))
        val outcome = runCaptureOcrTranslate(
            displayId = displayId,
            onScreenshotTaken = { flashRegionIndicator(displayId) },
        )
        state.value = when (outcome) {
            is PipelineOutcome.Success -> CaptureState.Done(outcome.pipeline.result)
            PipelineOutcome.NoText -> CaptureState.NoText(noTextMessage(displayId))
            is PipelineOutcome.Failed -> CaptureState.Failed(outcome.message)
        }
    }

    /** Cache of past translations. Keyed by (text, source, target) so
     *  cross-pair stale reads are impossible; cleared on backend toggle
     *  via [TranslationCache.reconcilePreferredBackend] called from
     *  [ensureLanguageManagersFor]. */
    private val translationCache = TranslationCache()

    private fun cacheKey(text: String, target: TranslationTarget): TranslationCache.Key =
        TranslationCache.Key(text, target.source, target.target)

    /** Synchronous cache lookup for previously translated text. Returns null
     *  if not cached for the current pair. */
    fun getCachedTranslation(sourceText: String): String? =
        translationCache[cacheKey(sourceText, snapshotTranslationTarget())]?.first

    /**
     * Translates each group in parallel, using cached results for groups
     * whose original text hasn't changed. Only cache misses hit the network.
     *
     * The [TranslationTarget] is snapshotted once at entry and threaded to
     * every downstream call so key-derivation and translator selection agree
     * even if another code path mutates Prefs mid-batch.
     *
     * Cache write policy: online backend results (DeepL and Lingva) are
     * cached; ML Kit fallback is not (signalled by a non-null note — only
     * the ML Kit branch sets one). ML Kit is skipped so online services can
     * reclaim the slot when they recover.
     */
    internal suspend fun translateGroupsSeparately(groupTexts: List<String>): List<Pair<String, String?>> {
        val target = snapshotTranslationTarget()
        val keys = groupTexts.map { cacheKey(it, target) }
        val uncached = keys.withIndex()
            .filter { (_, key) -> key !in translationCache }

        val freshByKey: Map<TranslationCache.Key, Pair<String, String?>> = if (uncached.isNotEmpty()) {
            // Fan out under coroutineScope so the per-group translations
            // are children of the calling capture job. When that job is
            // cancelled (live-mode start, replacement one-shot, etc.) the
            // children cancel with it instead of continuing on
            // serviceScope and writing to translationCache / degradedState
            // after the session has already been marked Cancelled.
            val results = coroutineScope {
                uncached.map { (_, key) ->
                    async { translate(key.text, target) }
                }.awaitAll()
            }

            uncached.zip(results).forEach { (indexedKey, result) ->
                if (result.second == null) {
                    translationCache[indexedKey.value] = result
                }
            }

            uncached.map { it.value }.zip(results).toMap()
        } else emptyMap()

        return keys.map { key ->
            translationCache[key]
                ?: freshByKey[key]
                ?: Pair("", null)
        }
    }

    private suspend fun translateGroups(groupTexts: List<String>): Pair<String, String?> {
        val results = translateGroupsSeparately(groupTexts)
        val translated = results.joinToString("\n\n") { it.first }
        val note = results.mapNotNull { it.second }.firstOrNull()
        return Pair(translated, note)
    }

    /** On-demand translation for a single text string (used by edit overlay, drag-sentence, etc.). */
    suspend fun translateOnce(text: String): Pair<String, String?> =
        translate(text, snapshotTranslationTarget())

    /**
     * Translation waterfall: DeepL → Lingva → ML Kit.
     * Returns the translated text and an optional inline note.
     * A note is only shown when ML Kit is used (lower quality); callers
     * use `note == null` as the "cacheable" signal.
     */
    private suspend fun translate(text: String, target: TranslationTarget): Pair<String, String?> {
        // Single choke point for self-healing: every translation path in the
        // service flows through here (captureOnce, processScreenshot, live
        // mode's translateGroupsSeparately, translateOnce for drag-sentence /
        // sentence-mode / edit-overlay). Ensuring at the call site — rather
        // than at each public entry point — means a future caller can't
        // accidentally bypass the self-heal and translate with stale
        // managers. The check itself is O(1) field comparisons against
        // [target], so calling it per group-translation is essentially free.
        ensureLanguageManagersFor(target)

        // Capture local references to all three translators so a concurrent
        // ensureFor() in another call can't null out or replace a translator
        // we're about to use. The entire waterfall operates on the snapshot
        // taken at this point — no re-reads of the shared fields below.
        val deepl = deeplTranslator
        val lingva = lingvaTranslator
        val mlKit = translationManager

        // 1. DeepL (if key is set)
        if (deepl != null) {
            try {
                return Pair(deepl.translate(text), null).also { setDegraded(false) }
            } catch (e: CancellationException) {
                // Don't fall back through Lingva/ML Kit just because the
                // caller cancelled — propagate so the capture job's
                // cancellation reaches its terminal Cancelled state.
                throw e
            } catch (e: DeepLQuotaExceededException) {
                Log.w(TAG, "DeepL quota exceeded, trying Lingva")
            } catch (e: DeepLAuthException) {
                Log.w(TAG, "DeepL auth failed, trying Lingva")
            } catch (e: Exception) {
                Log.w(TAG, "DeepL failed (${e.message}), trying Lingva")
            }
        }

        // 2. Lingva (always attempted)
        if (lingva != null) {
            try {
                return Pair(lingva.translate(text), null).also { setDegraded(false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Lingva failed (${e.message}), falling back to ML Kit")
            }
        }

        // 3. ML Kit offline fallback — the non-null note signals "don't
        //    cache" to [translateGroupsSeparately] so online services can
        //    reclaim the entry on recovery.
        val note = if (isNetworkAvailable())
            getString(R.string.note_mlkit_service_unavailable)
        else
            getString(R.string.note_mlkit_no_internet)
        return Pair(mlKitTranslate(text, mlKit), note).also { setDegraded(true) }
    }

    private suspend fun mlKitTranslate(text: String, manager: TranslationManager?): String {
        val tm = manager ?: throw IllegalStateException("No offline translation model available")
        tm.ensureModelReady()
        return tm.translate(text)
    }

    /**
     * Returns the status bar height in pixels for [displayId], or 0 if there is no
     * status bar or it cannot be determined.
     */
    internal fun getStatusBarHeightForDisplay(displayId: Int): Int {
        val dm = getSystemService(android.hardware.display.DisplayManager::class.java) ?: return 0
        val display = dm.getDisplay(displayId) ?: return 0
        return try {
            val displayContext = createDisplayContext(display)
            val wm = displayContext.getSystemService(android.view.WindowManager::class.java) ?: return 0
            wm.currentWindowMetrics.windowInsets
                .getInsets(android.view.WindowInsets.Type.statusBars()).top
        } catch (_: Exception) { 0 }
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(android.net.ConnectivityManager::class.java)
        return cm?.activeNetwork != null
    }

    /**
     * Returns [raw] unchanged if it already matches the crop bounds, otherwise
     * creates a cropped copy and recycles [raw]. This avoids duplicating the
     * same conditional-crop block in both the one-shot and live capture paths.
     */
    private fun cropBitmap(raw: Bitmap, top: Int, bottom: Int, left: Int, right: Int): Bitmap {
        val needsCrop = top > 0 || left > 0 || bottom < raw.height || right < raw.width
        if (!needsCrop) return raw
        val cropped = Bitmap.createBitmap(
            raw, left, top,
            (right - left).coerceAtLeast(1),
            (bottom - top).coerceAtLeast(1)
        )
        raw.recycle()
        return cropped
    }


    // ── Notification ──────────────────────────────────────────────────────

    /**
     * Evaluate whether the service needs foreground status and whether
     * live mode should keep running based on current game-screen presence.
     *
     * Triggered automatically by:
     *  - [setLiveDisplays] when the empty↔non-empty boolean transitions
     *  - PlayTranslateAccessibilityService.installFloatingIconForDisplay /
     *    hideFloatingIconForDisplay (every per-display add/remove)
     */
    fun updateForegroundState() {
        val iconShowing = PlayTranslateAccessibilityService.instance?.hasAnyFloatingIcon == true

        // Stop live mode if the user can no longer see or manage it.
        if (isLive) {
            val shouldStop = if (isInAppOnly) {
                // In-App Only: results only visible while app is in foreground
                !MainActivity.isInForeground
            } else {
                // Overlay modes: stop if no control surface at all (no icon, no app)
                !iconShowing && !MainActivity.isInForeground
            }
            Log.v(TAG, "updateForegroundState: isLive=true iconShowing=$iconShowing isInForeground=${MainActivity.isInForeground} isInAppOnly=$isInAppOnly shouldStop=$shouldStop")
            if (shouldStop) {
                Log.w(TAG, "updateForegroundState: stopping live (no visible surface)")
                stopLive()
                // stopLive() routes through setLiveDisplays(emptySet()), which
                // flips the LiveData and re-enters this method via syncIconState
                // / updateForegroundState — at which point isLive is false and
                // we fall through to the stopForeground branch below.
                return
            }
        }

        if (iconShowing || isLive) {
            startForeground(NOTIF_ID, buildNotification())
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_MIN
        ).apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }
}
