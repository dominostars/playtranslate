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
import android.content.pm.ServiceInfo
import android.view.WindowManager
import android.os.Binder
import android.os.Build
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
import com.playtranslate.capture.CaptureBackendResolver
import com.playtranslate.capture.CaptureLifecycle
import com.playtranslate.capture.MediaProjectionCaptureSource
import com.playtranslate.capture.MediaProjectionController
import com.playtranslate.dictionary.DictionaryManager
import com.playtranslate.overlay.OverlayHost
import com.playtranslate.language.SourceLanguageProfiles
import com.playtranslate.translation.TranslationBackendRegistry
import com.playtranslate.ui.DegradedWarningKind
import com.playtranslate.ui.TextBox

private const val TAG = "CaptureService"
private const val NOTIF_ID = 1001
private const val CHANNEL_ID = "playtranslate_capture"

/**
 * Foreground service that owns the OCR + translation pipeline.
 *
 * Translation backends are owned by [TranslationBackendRegistry]
 * (registered at app start in [PlayTranslateApplication.onCreate]).
 * The default waterfall order is:
 *
 *  1. DeepL      — if an API key is configured in Settings
 *  2. Google gtx — free `translate.googleapis.com/translate_a/single` endpoint
 *  3. ML Kit     — offline fallback when both online options are unavailable
 *
 * Notes are shown inline with the result only when the chosen backend
 * is the degraded fallback (ML Kit today).
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
     *
     * On the MediaProjection backend this is always [Display.DEFAULT_DISPLAY] —
     * MediaProjection can only mirror that display, so it is the only one the
     * app can capture, OCR, or overlay there.
     */
    fun primaryGameDisplayId(): Int {
        if (!CaptureBackendResolver.active().requiresAccessibilityService) {
            return android.view.Display.DEFAULT_DISPLAY
        }
        return lastInteractedDisplayId
            ?: gameDisplayIds.firstOrNull()
            ?: android.view.Display.DEFAULT_DISPLAY
    }
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

    /** Observable translation-degradation state — one [DegradedWarningKind]
     *  drives every consumer:
     *   - floating icon *color* (yellow when [kind] != [DegradedWarningKind.None]
     *     and in live mode),
     *   - floating icon *menu pill label* (None hides, Offline / LowMemory
     *     pick their respective strings),
     *   - inline result note (CaptureService.translate selects the matching
     *     `R.string.note_*` based on the same enum).
     *  Set atomically by [setDegraded] from the translate site (so the
     *  whole translation outcome maps to one state value, not two
     *  independently-mutable bits). */
    val degradationState: MutableLiveData<DegradedWarningKind> =
        MutableLiveData(DegradedWarningKind.None)

    /** Convenience: any kind other than [DegradedWarningKind.None] counts
     *  as "translation degraded" for icon-color and legacy boolean APIs. */
    val translationDegraded: Boolean
        get() = degradationState.value != DegradedWarningKind.None

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Set the degradation kind. [DegradedWarningKind.None] is the reset
     *  state used by live-mode teardown, the source==target OCR-only
     *  bypass, and overlay close — anything that means "no warning
     *  applies right now." */
    internal fun setDegraded(kind: DegradedWarningKind) {
        if (degradationState.value == kind) return
        degradationState.postValue(kind)
        // Post to main thread: setDegraded is called from background coroutines,
        // and syncIconState sets View properties. Posting also ensures the
        // postValue update has been applied before syncIconState reads it.
        mainHandler.post { syncIconState() }
    }

    /** Sugar for "no warning" — used by reset paths that don't care to spell
     *  out [DegradedWarningKind.None] inline. */
    internal fun setDegraded(degraded: Boolean) {
        setDegraded(
            if (degraded) DegradedWarningKind.Offline
            else DegradedWarningKind.None
        )
    }

    /** Push current service state to every floating icon. Called automatically
     *  by [setLiveDisplays] (on the empty↔non-empty transition), [setDegraded],
     *  and when icons are installed or torn down (from
     *  PlayTranslateAccessibilityService.installFloatingIconForDisplay /
     *  hideFloatingIconForDisplay). */
    fun syncIconState() {
        val ui = CaptureBackendResolver.activeOverlayUi ?: return
        ui.setIconsLiveMode(isLive)
        ui.setIconsDegraded(translationDegraded)
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
        enterForeground()
        // Immediately evaluate — may stopForeground if no game-screen presence yet
        updateForegroundState()
        if (intent?.action == ACTION_MP_ACTIVATE) {
            // QS tile turn-on in MediaProjection mode — routed
            // through the service so it works even from a cold start.
            serviceScope.launch { CaptureLifecycle.activateMediaProjection() }
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.w(TAG, "onTaskRemoved")
        super.onTaskRemoved(rootIntent)
        CaptureBackendResolver.activeOverlayUi?.hideFloatingIcon("task_removed")
    }

    override fun onDestroy() {
        Log.w(TAG, "onDestroy")
        // Tear down live modes FIRST — while [instance] is still set, so
        // CaptureBackendResolver.activeOverlayUi can still resolve to this
        // service's MediaProjection overlay UI and the cleanup chain (each
        // LiveMode.stop, stopAllInputMonitoring, hideTranslationOverlay)
        // actually finds the overlays/sentinels to remove. Nulling [instance]
        // before this is what would leak the MP floating icon / translation
        // window / touch sentinels — the resolver would return null and the
        // chain would no-op.
        stopLive()
        // Hide the MediaProjection floating icon and any region UI — a
        // separate concern from live-mode overlays (which stopLive handled
        // above). Gated on whether the overlay UI was ever touched so an
        // accessibility-only session doesn't force-initialize it.
        if (mediaProjectionOverlayUiLazy.isInitialized()) {
            mediaProjectionOverlayUi.destroy()
        }
        // Release the MediaProjection session (projection / VirtualDisplay /
        // ImageReader) — nothing else releases those native resources. Same
        // lazy-gate pattern — accessibility-only sessions skip this entirely
        // instead of force-initializing the MP backend just to tear it down.
        if (mediaProjectionCaptureSourceLazy.isInitialized()) {
            mediaProjectionCaptureSource.destroy()
        }
        instance = null
        serviceScope.cancel()
        // The TranslationBackendRegistry is owned at app scope (built in
        // PlayTranslateApplication.onCreate) and outlives this service —
        // MainActivity may rebind, and tearing down backends here would
        // force every CaptureService re-creation to rebuild HTTP clients
        // and re-acquire ML Kit model handles. Registry teardown happens
        // implicitly at process death.
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
        val ui = CaptureBackendResolver.activeOverlayUi
        ui?.hideRegionIndicator()
        for (id in changedDisplayIds) ui?.hideTranslationOverlayForDisplay(id)
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
        val ui = CaptureBackendResolver.activeOverlayUi
        for (id in removedIds) ui?.hideTranslationOverlayForDisplay(id)

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

    /** Called at the top of every translation call to keep the cache's
     *  "preferred backend" identity in sync with current configuration.
     *  Backends themselves are owned by [TranslationBackendRegistry] and
     *  are pair-agnostic singletons — there is no per-pair instance
     *  churn to reconcile. Pair changes are handled by the cache key
     *  itself — no explicit clear needed. */
    private fun ensureLanguageManagersFor(target: TranslationTarget) {
        translationCache.reconcilePreferredBackend(
            TranslationBackendRegistry.preferredOnlineId(target.source, target.target)
        )
    }

    /** Public hook for callers (Settings UI today) to drive cache
     *  reconciliation eagerly when they know the user just changed
     *  backend preferences — e.g. flipped the DeepL toggle, saved a new
     *  DeepL key. Without this, an all-cached translate batch can serve
     *  stale entries because [translate] (where reconciliation lives) is
     *  never invoked. Cheap: a Map-clear on transition, no-op otherwise. */
    fun reconcileBackendPreference() {
        ensureLanguageManagersFor(snapshotTranslationTarget())
    }

    /** Force-drop every cached translation. Used when the *configuration*
     *  of an LLM backend changes without changing its id — switching the
     *  OpenAI model, base URL, or API key. [reconcileBackendPreference]
     *  can't catch those because the preferred backend id is unchanged. */
    fun clearTranslationCache() {
        translationCache.clear()
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
    //   B. Waterfall CancellationException re-throw
    //      ([TranslationBackendRegistry.translate]).
    //      Without it, a cancelled capture would waterfall through every
    //      backend doing wasted fallback work the cancelled caller can
    //      never deliver. The registry's catch arm explicitly re-throws.
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
            val screenshotPath = captureSaveToCache(raw, displayId)

            val region = activeRegionForDisplay(displayId)
            val statusBarHeight = getStatusBarHeightForDisplay(displayId)
            val top    = maxOf((raw.height * region.top).toInt(), statusBarHeight)
            val left   = (raw.width  * region.left).toInt()
            val bottom = (raw.height * region.bottom).toInt()
            val right  = (raw.width  * region.right).toInt()
            bitmap = cropBitmap(raw, top, bottom, left, right)

            // The floating icon is always rendered in compact mode (a small
            // edge-arrow), so the pre-OCR icon blackout is gone — we feed
            // OCR the crop directly. Cleanup of `bitmap` is the outer
            // finally's job.
            state.value = CaptureState.InProgress(getString(R.string.status_ocr))
            val ocrResult = ocrManager.recognise(bitmap, sourceLang, screenshotWidth = raw.width)
            if (BuildConfig.DEBUG && Prefs(this@CaptureService).debugSaveOcrSeed) {
                OcrSeedWriter.writeSeed(this@CaptureService, bitmap, ocrResult)
            }

            if (ocrResult == null) {
                state.value = CaptureState.NoText(noTextMessage(displayId))
                return
            }

            state.value = CaptureState.InProgress(getString(R.string.status_translating))
            val groupTranslation = translateGroups(ocrResult.groupTexts)

            val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            state.value = CaptureState.Done(
                TranslationResult(
                    originalText        = ocrResult.fullText,
                    segments            = ocrResult.segments,
                    translatedText      = groupTranslation.text,
                    timestamp           = timestamp,
                    screenshotPath      = screenshotPath,
                    note                = groupTranslation.note,
                    backendDisplayName  = groupTranslation.backendDisplayName,
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

    // ── MediaProjection backend state ─────────────────────────────────────
    //
    // Lazily created; untouched until the MediaProjection backend is the
    // active one. MediaProjectionCaptureBackend forwards to these, just as
    // the accessibility backend forwards to the accessibility service.

    /** Owns the MediaProjection session (consent, VirtualDisplay, ImageReader). */
    internal val mediaProjectionController by lazy { MediaProjectionController(this) }

    /** One-shot clean-capture source backed by [mediaProjectionController].
     *  Stored as an explicit [Lazy] so [onDestroy] can gate teardown on
     *  whether the source was ever touched (via [Lazy.isInitialized]) without
     *  force-initializing it through the property access itself. */
    private val mediaProjectionCaptureSourceLazy = lazy {
        MediaProjectionCaptureSource(mediaProjectionController)
    }
    internal val mediaProjectionCaptureSource: MediaProjectionCaptureSource
        by mediaProjectionCaptureSourceLazy

    /** Overlay-window host for MediaProjection mode (TYPE_APPLICATION_OVERLAY). */
    internal val mediaProjectionOverlayHost by lazy {
        OverlayHost(this, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
    }

    /** Game-screen overlay UI for MediaProjection mode. Its floating controls
     *  stay hidden until MediaProjection consent is granted — see
     *  [OverlayUiController]'s canShowControls gate. Stored as an explicit
     *  [Lazy] so [onDestroy] can gate teardown on whether the overlay UI was
     *  ever touched (via [Lazy.isInitialized]) — accessibility-only sessions
     *  never realize it and never need its teardown. */
    private val mediaProjectionOverlayUiLazy = lazy {
        OverlayUiController(this, mediaProjectionOverlayHost) {
            mediaProjectionController.hasConsent
        }.also { it.attach() }
    }
    internal val mediaProjectionOverlayUi: OverlayUiController
        by mediaProjectionOverlayUiLazy

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
        val backend = CaptureBackendResolver.active()
        if (!backend.supportsLiveMode) {
            val msg = getString(R.string.error_live_mode_unsupported_backend)
            emitError(msg)
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            return
        }
        oneShotCaptureJob?.cancel()

        // Secure capture readiness — the MediaProjection screen-record consent
        // token — BEFORE the live-mode loop and its touch sentinel exist. A
        // consent dialog launched from inside the running loop has its Cancel
        // tap caught by the 1×1 outside-touch sentinel as "game input", which
        // restarts the loop and re-launches the dialog in an unbreakable
        // cycle. canCaptureWithoutPrompting keeps the common already-ready
        // case (consent held, or the accessibility backend) fully synchronous.
        if (backend.canCaptureWithoutPrompting) {
            beginLiveCapture()
        } else {
            serviceScope.launch {
                if (backend.ensureCaptureReady()) {
                    beginLiveCapture()
                } else {
                    emitError(getString(R.string.error_screen_capture_denied))
                }
            }
        }
    }

    /**
     * The post-consent tail of [startLive]: compute the target display set
     * and hand off to [setLiveDisplays]. Split out so [startLive] can await
     * [CaptureBackend.ensureCaptureReady] first — on the MediaProjection
     * backend the consent dialog must fully resolve before any live-mode
     * window (and its touch sentinel) is built. Main thread only.
     */
    private fun beginLiveCapture() {
        // Reset the panel to Searching so the activity sees an
        // immediate transition into live mode (rather than a stale
        // result lingering until the first cycle lands).
        _panelState.value = PanelState.Searching

        val prefs = Prefs(this)
        val activeIds = gameDisplayIds.ifEmpty { setOf(primaryGameDisplayId()) }
        Log.d(TAG, "startLive: activeIds=$activeIds prefs.overlayMode=${prefs.overlayMode}")
        // setLiveDisplays handles capturableTargets + shouldSkipDisplay
        // resolution centrally — every caller (start, reconcile, multi-
        // window, the display listener) gets the same shim.
        setLiveDisplays(activeIds)
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

        // Resolve through the backend shim — capturable subset of [target]
        // with the backend's fallback display substituted when nothing in
        // [target] is capturable (stale-selection collapse). Then drop
        // displays currently off / app-occluded. This is THE canonical
        // resolution: every non-stop caller (start, reconcile, multi-window,
        // the display listener after a state change) gets the same result
        // without needing to remember to apply the shim itself. Stop bypasses
        // this path entirely via [tearDownAllLiveModes] — passing ∅ here
        // would resolve to the backend's fallback (MediaProjection always
        // reaches default), which is right for "selection filtered to
        // nothing" but wrong for "user pressed stop."
        val backend = CaptureBackendResolver.active()
        val capturable = backend.capturableTargets(target)
            .filterNot { shouldSkipDisplay(it) }
            .toSet()
        // IN_APP_ONLY is single-display by design — collapse to the primary
        // (capturable is a Set, so first() is order-dependent; prefer the primary).
        val actualTarget = if (flavor == OverlayFlavor.IN_APP_ONLY && capturable.isNotEmpty()) {
            val primary = primaryGameDisplayId()
            setOf(if (primary in capturable) primary else capturable.first())
        } else {
            capturable
        }

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

        // Construct new instances first so a missing-prerequisite failure
        // aborts before we tear down the existing modes. The overlay flavors
        // need the accessibility service ONLY on the accessibility backend,
        // where they capture through it; under MediaProjection capture routes
        // through CaptureBackendResolver. InAppOnly never needs it.
        val a11y = PlayTranslateAccessibilityService.instance
        val needsA11y = flavor != OverlayFlavor.IN_APP_ONLY &&
            CaptureBackendResolver.active().requiresAccessibilityService &&
            (toRebuild.isNotEmpty() || toAdd.isNotEmpty())
        if (needsA11y && a11y == null) {
            Log.w(TAG, "setLiveDisplays: accessibility service not connected; cannot start $flavor. Aborting.")
            return false
        }

        val newInstances: Map<Int, LiveMode> = (toAdd + toRebuild).associateWith { id ->
            when (flavor) {
                OverlayFlavor.IN_APP_ONLY -> InAppOnlyMode(this, id)
                OverlayFlavor.FURIGANA -> FuriganaMode(this, id)
                OverlayFlavor.TRANSLATION -> PinholeOverlayMode(this, id)
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
     * Stop every running live mode without going through [setLiveDisplays].
     * Mirrors the teardown branch of [setLiveDisplays] (stop modes, fire the
     * non-empty→empty state observers, unregister the display listener) but
     * skips the capturableTargets shim — which would substitute the
     * backend's fallback display for an empty input and keep MediaProjection
     * running on the default. That fallback is the right answer for
     * "selection filtered to nothing" at reconcile / multi-window / listener
     * call sites, and the wrong answer for "the user pressed stop."
     */
    @MainThread
    private fun tearDownAllLiveModes() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "tearDownAllLiveModes must run on the main thread " +
                "(got ${Thread.currentThread().name})"
        }
        if (liveModes.isEmpty()) return
        val ids = liveModes.keys.toList()
        val wasLive = _liveModeState.value == true
        for (id in ids) liveModes.remove(id)?.stop()
        if (wasLive) {
            _liveModeState.value = false
            updateForegroundState()
            syncIconState()
            getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(displayListener)
        }
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
        // Stop bypasses setLiveDisplays: we genuinely want zero live modes,
        // not "fall back to the backend's capturable default" — which is what
        // setLiveDisplays(emptySet()) now resolves to via the capturableTargets
        // shim (MediaProjection always reaches default). tearDownAllLiveModes
        // mirrors setLiveDisplays' teardown branch (stop modes, fire LiveData
        // false, unregister the display listener) without going through the
        // shim.
        tearDownAllLiveModes()
        setDegraded(false)
        // Belt-and-suspenders fan-out — each LiveMode.stop() should already
        // have torn down its own loop / input / overlay, but historically these
        // calls have caught misbehaving modes that left state behind.
        CaptureBackendResolver.active().liveCaptureSource?.stopAllLoops()
        CaptureBackendResolver.active().stopAllInputMonitoring()
        CaptureBackendResolver.activeOverlayUi?.hideTranslationOverlay()
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

    /**
     * Box-tap dismiss: clear [displayId]'s translation overlay and reset its
     * live-mode detection so the next capture re-baselines from a clean frame.
     * Falls back to hiding the overlay when no live mode owns the display
     * (e.g. a one-shot translation overlay).
     */
    fun dismissLiveOverlay(displayId: Int) {
        val mode = liveModes[displayId]
        if (mode != null) {
            mode.dismiss()
        } else {
            CaptureBackendResolver.activeOverlayUi?.hideTranslationOverlayForDisplay(displayId)
        }
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
     * cycle polls the flag directly). Stopping the backend's live capture
     * loop is needed for modes that drive capture through the loop
     * (Furigana) — Pinhole calls `requestRaw` directly and would otherwise
     * keep screenshotting.
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
            CaptureBackendResolver.active().liveCaptureSource?.stopAllLoops()
            CaptureBackendResolver.activeOverlayUi
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
     *  A genuine single-display setup ([gameDisplayIds] size <= 1) always
     *  returns the target — the skip predicate's foreground branch is gated
     *  on `gameDisplayIds.size > 1`, so the only way the filter can empty
     *  there is STATE_OFF, in which case captureScreen returns null and the
     *  cycle exits cleanly, preserving "the gesture always tries to do
     *  something on a single-display setup."
     *  Iteration order follows [gameDisplayIds] insertion order.
     *
     *  Routed through [CaptureBackend.capturableTargets] — the same shim
     *  live start and icon placement use — so a stale non-default
     *  selection carried over from an accessibility session collapses to
     *  the MediaProjection backend's only capturable display. Without it
     *  a fan-out one-shot would request a display MediaProjection can't
     *  mirror and silently get default-display pixels back. */
    internal fun oneShotFanoutDisplayIds(): Set<Int> {
        val all = CaptureBackendResolver.active()
            .capturableTargets(gameDisplayIds.ifEmpty { setOf(primaryGameDisplayId()) })
        val filtered = all.filter { !shouldSkipDisplay(it) }
        if (filtered.isNotEmpty()) return filtered.toSet()
        // Single-display exception is keyed on gameDisplayIds — the field
        // shouldSkipDisplay's foreground gate reads — NOT the collapsed `all`.
        // A stale multi-display selection that capturableTargets collapsed to
        // one fallback display is still multi-display: returning `all` here
        // would capture the foregrounded app UI instead of no-op'ing.
        if (gameDisplayIds.size <= 1) return all
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
        // If the floating menu is up, tear it down outright instead of
        // letting the capture path alpha-cycle it. Avoids a class of
        // post-capture layout glitches on the MediaProjection backend where
        // the restored menu re-appeared at wrong coords, and matches the
        // user's intent — they wanted a translation, not a half-broken
        // menu to come back. Pass clearHoldActive=false so the dismissal
        // doesn't flip holdActive off between here and the holdActive=true
        // we're about to set in the live-peek branch / beginHoldPreview.
        CaptureBackendResolver.activeOverlayUi
            ?.dismissFloatingMenu(clearHoldActive = false)
        // Pinhole / translation-overlay live modes: "peek" through the
        // overlay at the game underneath, without running a one-shot.
        // PinholeOverlayMode's cycle polls [holdActive] and pauses itself.
        val isFurigana = liveModes.values.any { it.flavor == OverlayFlavor.FURIGANA }
        if (isLive && !isFurigana && !isInAppOnly) {
            holdActive = true
            CaptureBackendResolver.activeOverlayUi?.hideTranslationOverlay()
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
        // Same rationale as holdStart: if the floating menu is up, tear it
        // down outright so prepareForCleanCapture has nothing to restore.
        CaptureBackendResolver.activeOverlayUi
            ?.dismissFloatingMenu(clearHoldActive = false)
        val isFurigana = liveModes.values.any { it.flavor == OverlayFlavor.FURIGANA }
        if (isLive && !isFurigana && !isInAppOnly) {
            // Live translation overlay peek — hide so user can see game
            // underneath. Pure UI gesture, doesn't depend on fanout
            // targets (fires even when multi-display + everything
            // skip-eligible would otherwise no-op the capture path).
            holdActive = true
            CaptureBackendResolver.activeOverlayUi?.hideTranslationOverlay()
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
        val ui = CaptureBackendResolver.activeOverlayUi ?: return
        val dm = getSystemService(DisplayManager::class.java)
        val display = dm.getDisplay(displayId) ?: return
        ui.showRegionIndicator(display, activeRegionForDisplay(displayId))
    }

    /** Run the shared OCR pipeline on a frame captured from [displayId].
     *  Every per-display parameter (active region, status bar height, icon
     *  rect to black out) is resolved for [displayId] — the pipeline has no
     *  notion of a "primary" display. Caller still owns [raw]. */
    internal suspend fun runOcr(raw: Bitmap, displayId: Int): OverlayToolkit.OcrPipelineResult? {
        val prefs = Prefs(this)
        val seedWriter: ((Bitmap, OcrManager.OcrResult?) -> Unit)? =
            if (BuildConfig.DEBUG && prefs.debugSaveOcrSeed) {
                { bitmap, result -> OcrSeedWriter.writeSeed(this, bitmap, result) }
            } else null
        return OverlayToolkit.runOcrPipeline(
            raw,
            activeRegionForDisplay(displayId),
            sourceLang,
            ocrManager,
            getStatusBarHeightForDisplay(displayId),
            seedWriter = seedWriter
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
    ): List<GroupTranslation>? {
        if (!forceShow) {
            val appPanelVisible = !Prefs.isSingleScreen(this) && MainActivity.isInForeground
            if (!appPanelVisible) return null
        }
        val perGroup = translateGroupsSeparately(ocrResult.groupTexts)
        val translated = perGroup.joinToString("\n\n") { it.text }
        val note = perGroup.mapNotNull { it.note }.firstOrNull()
        val backendDisplayName = perGroup.mapNotNull { it.backendDisplayName }.firstOrNull()
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        emitResult(
            com.playtranslate.model.TranslationResult(
                originalText       = ocrResult.fullText,
                segments           = ocrResult.segments,
                translatedText     = translated,
                timestamp          = timestamp,
                screenshotPath     = screenshotPath,
                note               = note,
                backendDisplayName = backendDisplayName,
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
        CaptureBackendResolver.activeOverlayUi?.hideTranslationOverlayForDisplay(displayId)
        emitLiveNoText()
    }

    /** Remove specific overlay boxes without rebuilding the entire view.
     *  [displayId] defaults to [primaryGameDisplayId] for legacy callers. */
    internal fun removeOverlayBoxes(
        toRemove: List<TextBox>,
        displayId: Int = primaryGameDisplayId(),
    ) {
        CaptureBackendResolver.activeOverlayUi?.removeOverlayBoxes(toRemove, displayId)
    }

    /**
     * Show a live translation overlay on [displayId] (defaults to
     * [primaryGameDisplayId] for legacy single-display callers; per-display
     * modes pass their own displayId).
     */
    internal fun showLiveOverlay(
        boxes: List<TextBox>,
        cropLeft: Int, cropTop: Int,
        screenshotW: Int, screenshotH: Int,
        force: Boolean = false,
        pinholeMode: Boolean = false,
        oneShot: Boolean = false,
        displayId: Int = primaryGameDisplayId(),
    ) {
        if (!force && holdActive) { Log.w("FuriganaDbg", "showLiveOverlay BLOCKED: holdActive=true"); return }
        val ui = CaptureBackendResolver.activeOverlayUi
        if (ui == null) { Log.w("FuriganaDbg", "showLiveOverlay BLOCKED: overlayUi=null"); return }
        val dm = getSystemService(DisplayManager::class.java)
        val display = dm.getDisplay(displayId)
        if (display == null) { Log.w("FuriganaDbg", "showLiveOverlay BLOCKED: display=null for id=$displayId"); return }
        Log.d("FuriganaDbg", "showLiveOverlay: ${boxes.size} boxes, crop=($cropLeft,$cropTop), screen=${screenshotW}x$screenshotH on display $displayId")
        ui.showTranslationOverlay(display, boxes, cropLeft, cropTop, screenshotW, screenshotH, pinholeMode, oneShot)
    }

    /** Capture a clean screenshot via the active capture backend. */
    internal suspend fun captureScreen(displayId: Int): Bitmap? =
        CaptureBackendResolver.active().captureSource?.requestClean(displayId)

    /** Persist [raw] to the screenshot cache via the active capture backend. */
    internal fun captureSaveToCache(raw: Bitmap, displayId: Int): String? =
        CaptureBackendResolver.active().captureSource?.saveToCache(raw, displayId)

    /**
     * @param preCaptured If non-null, use this bitmap instead of taking a new
     *   screenshot. Used by scene detection which already has a clean frame.
     */
    companion object {

        /** Process-scoped reference for in-process callers (e.g. DragLookupController). */
        @Volatile
        var instance: CaptureService? = null
            private set

        /** Action for an [onStartCommand] intent meaning "obtain MediaProjection
         *  consent and bring the controls up" — sent by the Quick Settings tile,
         *  which can't assume the service is already alive. */
        const val ACTION_MP_ACTIVATE = "com.playtranslate.action.MP_ACTIVATE"

        /** Empty-id, full-screen region used as the initial saved/active value
         *  before [configureSaved] runs and as the defensive fallback in
         *  [activeRegion]. Centralized so the literal isn't duplicated. */
        val DEFAULT_REGION = RegionEntry("", 0f, 1f)
    }

    fun resetConfiguration() {
        // Translation backends are owned by TranslationBackendRegistry at
        // app scope and are not reset here — they survive service teardown
        // and reconfigure cycles untouched.
        gameDisplayIds = emptySet()
        overrideRegions.clear()
        hasCaptureStateConfigured = false
        _statusUpdates.tryEmit(getString(R.string.status_idle))
    }

    /** True iff [configureSaved] has run (display + region set). Explicitly
     *  decoupled from translator availability — translation backends are
     *  always present via [TranslationBackendRegistry], so a
     *  translation-only path (e.g. one that doesn't capture) still has
     *  to advance through display/region setup before [isConfigured]
     *  flips. */
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
            val screenshotPath = captureSaveToCache(raw, displayId)

            val region = activeRegionForDisplay(displayId)
            val statusBarHeight = getStatusBarHeightForDisplay(displayId)
            val top    = maxOf((raw.height * region.top).toInt(), statusBarHeight)
            val left   = (raw.width  * region.left).toInt()
            val bottom = (raw.height * region.bottom).toInt()
            val right  = (raw.width  * region.right).toInt()
            bitmap = cropBitmap(raw, top, bottom, left, right)

            // No pre-OCR icon blackout — the floating icon is always rendered
            // in compact mode so it doesn't bleed into the OCR region.
            val ocrResult = ocrManager.recognise(bitmap, sourceLang, screenshotWidth = raw.width)
            if (BuildConfig.DEBUG && Prefs(this@CaptureService).debugSaveOcrSeed) {
                OcrSeedWriter.writeSeed(this@CaptureService, bitmap, ocrResult)
            }

            if (ocrResult == null) return PipelineOutcome.NoText

            val perGroup = translateGroupsSeparately(ocrResult.groupTexts)
            val translated = perGroup.joinToString("\n\n") { it.text }
            val note = perGroup.mapNotNull { it.note }.firstOrNull()
            val backendDisplayName = perGroup.mapNotNull { it.backendDisplayName }.firstOrNull()
            val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

            return PipelineOutcome.Success(
                PipelineResult(
                    result = TranslationResult(
                        originalText       = ocrResult.fullText,
                        segments           = ocrResult.segments,
                        translatedText     = translated,
                        timestamp          = timestamp,
                        screenshotPath     = screenshotPath,
                        note               = note,
                        backendDisplayName = backendDisplayName,
                    ),
                    groupBounds = ocrResult.groupBounds,
                    groupTranslations = perGroup.map { it.text },
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
     * Cache write policy: online backend results (DeepL and Lingva) and
     * fully-successful on-device LLM results (TG, Qwen) are cached. Two
     * categories are skipped:
     *   - ML Kit degraded fallback (signalled by a non-null note set in
     *     [translate]) — so online services can reclaim the slot on recovery.
     *   - On-device LLM displacement (signalled by a non-null
     *     [TranslateOutcome.displacedLlmId]) — when TG or Qwen threw a
     *     transient low-memory exception and the waterfall fell through to
     *     a lower-priority backend, the fallback's output shouldn't outlast
     *     the memory pressure window.
     */
    internal suspend fun translateGroupsSeparately(groupTexts: List<String>): List<GroupTranslation> {
        val target = snapshotTranslationTarget()

        // OCR-only bypass: when source and target language are the same,
        // skip translation entirely — OCR output is the final result.
        // This handles all paths: one-shot hold, live mode, and in-app panel.
        // Clear degraded state too — bypass means we aren't going through a
        // backend, so any stale "Offline"/"degraded" badge from a prior
        // fallback should drop.
        if (target.source == target.target) {
            setDegraded(false)
            return groupTexts.map { GroupTranslation(it, null, null) }
        }

        // Reconcile the cache's preferred-backend identity BEFORE the
        // cache lookup. If a fully-cached batch returns early, the
        // identity check inside translateBatch never runs, so a backend
        // toggle / cooldown enter-or-exit since the last call would
        // leave stale entries serving forever. Cheap (one Map clear on
        // transition, no-op otherwise).
        ensureLanguageManagersFor(target)

        val keys = groupTexts.map { cacheKey(it, target) }
        val uncached = keys.withIndex()
            .filter { (_, key) -> key !in translationCache }

        val freshByKey: Map<TranslationCache.Key, GroupTranslation> = if (uncached.isNotEmpty()) {
            // Single batched waterfall (one HTTP request per backend
            // pass when the backend implements BatchTranslator —
            // DeepL, Gemini, OpenAI, Lingva) instead of N parallel
            // single-text calls. Eliminates the rate-limit thrashing
            // we saw on free-tier Gemini where parallel pairs of
            // requests would each lose ~half to 429s. Non-batching
            // backends (ML Kit, on-device LLMs) keep their per-text
            // parallel fan-out inside the registry, so today's
            // structured-cancellation guarantee (children of the
            // calling capture job) is preserved end-to-end.
            val outcomes = translateBatch(uncached.map { it.value.text }, target)

            // Set the icon/menu state ONCE from the worst outcome in the
            // batch. Each child's `translate` no longer touches the global
            // state, so a clean group that happens to finish after a
            // displaced sibling can't clear the warning. A fully-cached
            // batch (outcomes empty) deliberately doesn't update state —
            // matches the pre-refactor behavior where cache hits left the
            // previous state in place.
            val aggregateKind = outcomes.maxByOrNull { it.kind.severity() }?.kind
                ?: DegradedWarningKind.None
            setDegraded(aggregateKind)

            // Re-reconcile AFTER translate too. A higher-priority backend
            // may have cooled down DURING translateBatch — preferredOnlineId
            // would now return the fallback id, but the pre-call reconcile
            // didn't see that change. Without this second pass, the new
            // fallback entry below would be cached under the old
            // (pre-cooldown) preferred-backend identity; if the user
            // doesn't translate again before the cooldown expires, identity
            // never flips and the lower-quality result pins forever. Cheap
            // (one Map clear on transition, no-op when no cooldown change).
            ensureLanguageManagersFor(target)

            uncached.zip(outcomes).forEach { (indexedKey, outcome) ->
                // Cache write policy: skip when an on-device LLM was displaced
                // by transient low memory (outcome.displacedLlmId != null).
                // Without this, a single low-memory moment freezes the
                // fallback's output in the cache, so the next call returns
                // the same lower-quality result even after memory recovers.
                // The existing note-based skip (ML Kit degraded fallback)
                // still applies in parallel.
                //
                // Online-backend cooldowns (rate-limit / quota / billing)
                // are handled at the cache-identity layer (the
                // ensureLanguageManagersFor call above ran twice — once
                // before lookup, once after translate — so any cooldown
                // entered during this batch is reflected in the identity
                // before these writes land).
                if (outcome.note == null && outcome.displacedLlmId == null) {
                    translationCache[indexedKey.value] = outcome.text to outcome.backendDisplayName
                }
            }

            uncached.map { it.value }.zip(
                outcomes.map { GroupTranslation(it.text, it.note, it.backendDisplayName) }
            ).toMap()
        } else emptyMap()

        return keys.map { key ->
            translationCache[key]?.let { (text, backendDisplayName) ->
                GroupTranslation(text, note = null, backendDisplayName = backendDisplayName)
            }
                ?: freshByKey[key]
                ?: GroupTranslation("", null, null)
        }
    }

    private suspend fun translateGroups(groupTexts: List<String>): GroupTranslation {
        val results = translateGroupsSeparately(groupTexts)
        val translated = results.joinToString("\n\n") { it.text }
        val note = results.mapNotNull { it.note }.firstOrNull()
        val backendDisplayName = results.mapNotNull { it.backendDisplayName }.firstOrNull()
        return GroupTranslation(translated, note, backendDisplayName)
    }

    /** On-demand translation for a single text string (used by edit overlay, drag-sentence, etc.). */
    internal suspend fun translateOnce(text: String): GroupTranslation {
        val outcome = translate(text, snapshotTranslationTarget())
        setDegraded(outcome.kind)
        return GroupTranslation(outcome.text, outcome.note, outcome.backendDisplayName)
    }

    /**
     * Run the translation waterfall and synthesise an inline note when
     * the chosen backend is the degraded fallback.
     *
     * The waterfall itself lives in [TranslationBackendRegistry.translate]
     * so it is testable on the JVM without dragging this service in.
     * This method is the single choke point that:
     *   - reconciles the cache's preferred-backend identity (mid-batch
     *     pref changes pick up here without a per-caller round trip),
     *   - turns the [com.playtranslate.translation.WaterfallResult] into
     *     the legacy `(text, note)` tuple consumed by callers,
     *   - drives [setDegraded] / the floating-icon "⚠ Offline" badge,
     *   - and propagates [CancellationException] so a cancelled capture
     *     reaches its terminal Cancelled state instead of stuck-in-flight.
     *
     * Note discipline: a non-null note is the "don't cache" signal in
     * [translateGroupsSeparately] — online backends can then reclaim the
     * cache slot on recovery.
     */
    /** Internal translation outcome. Carries the user-visible (text, note)
     *  pair plus the [WaterfallResult.displacedLlmId] cache-skip signal and
     *  the per-call [kind] used to aggregate degradation state across the
     *  groups in a batch. The signal is internal to this service — public
     *  methods ([translateOnce], [translateGroupsSeparately]) flatten back
     *  to `(text, note)` so callers outside the cache layer don't grow a
     *  dependency on the registry's displacement type. */
    private data class TranslateOutcome(
        val text: String,
        val note: String?,
        val kind: DegradedWarningKind,
        val displacedLlmId: com.playtranslate.translation.BackendId?,
        val backendDisplayName: String?,
    )

    /** Per-group translation triple returned by the batch / single paths.
     *  Carries the backend's display name so the results view can render
     *  "Translated by …" alongside the existing warning [note]. */
    internal data class GroupTranslation(
        val text: String,
        val note: String?,
        val backendDisplayName: String?,
    )

    /** Order DegradedWarningKind by severity so a batch's worst outcome
     *  drives the icon/menu state, regardless of completion order. */
    private fun DegradedWarningKind.severity(): Int = when (this) {
        DegradedWarningKind.None -> 0
        DegradedWarningKind.Offline -> 1
        DegradedWarningKind.LowMemory -> 2
    }

    private suspend fun translate(text: String, target: TranslationTarget): TranslateOutcome {
        // OCR-only bypass: when source and target language are the same, skip
        // translation entirely. This is the universal choke point — every
        // single-text translation path (translateOnce callers: edit overlay,
        // drag-sentence, sentence tab) flows through here. The earlier
        // bypass in translateGroupsSeparately is a redundant early-return
        // for the group/cache path.
        if (target.source == target.target) {
            return TranslateOutcome(text, null, DegradedWarningKind.None, null, null)
        }

        ensureLanguageManagersFor(target)
        val result = TranslationBackendRegistry.translate(text, target.source, target.target)
        return result.toOutcome()
    }

    /**
     * Batched counterpart to [translate]. The fan-out used to live in
     * [translateGroupsSeparately] as N parallel single-text [translate]
     * calls; the batch waterfall in [TranslationBackendRegistry.translateBatch]
     * replaces that fan-out with one HTTP request per backend pass
     * where the backend implements [com.playtranslate.translation.BatchTranslator].
     *
     * Must call [ensureLanguageManagersFor] here once before dispatch —
     * the per-text [translate] used to do that on every call, and the
     * cache's preferred-backend reconciliation rides on it. Skipping it
     * would let a backend toggled mid-session serve stale cache entries.
     */
    private suspend fun translateBatch(
        texts: List<String>,
        target: TranslationTarget,
    ): List<TranslateOutcome> {
        if (target.source == target.target) {
            return texts.map { TranslateOutcome(it, null, DegradedWarningKind.None, null, null) }
        }
        ensureLanguageManagersFor(target)
        val results = TranslationBackendRegistry.translateBatch(texts, target.source, target.target)
        return results.map { it.toOutcome() }
    }

    /** Map a [WaterfallResult] to a [TranslateOutcome] with the per-result
     *  kind + inline-note logic. Used by both the single-text [translate]
     *  and the batched [translateBatch] paths so the degraded-state
     *  semantics stay identical between them. */
    private fun com.playtranslate.translation.WaterfallResult.toOutcome(): TranslateOutcome {
        // Per-group kind. Displacement that bottomed out at ML Kit is the
        // LowMemory kind; ML Kit chosen for network/service reasons is
        // Offline. Displacement that stayed in the offline tier (Qwen
        // picked up after TG) is None — the result is high-quality offline
        // output, so we don't visually flag it; the Settings row's
        // live availMem check carries that signal on its own.
        //
        // We do NOT call setDegraded here — that would let one group's
        // outcome clobber a sibling group's worse outcome in a batched
        // translation. Aggregation happens once per batch in
        // [translateGroupsSeparately] / per call in [translateOnce].
        val kind = when {
            !this.isDegraded -> DegradedWarningKind.None
            this.displacedLlmId != null -> DegradedWarningKind.LowMemory
            else -> DegradedWarningKind.Offline
        }
        // The inline note adds one more bit of detail that the icon doesn't
        // need — when the cause is "Offline", distinguish network-not-
        // present from service-unavailable. Both surface as the Offline
        // pill on the floating icon.
        val note = when (kind) {
            DegradedWarningKind.None -> null
            DegradedWarningKind.LowMemory ->
                getString(R.string.note_low_memory_fallback)
            DegradedWarningKind.Offline ->
                if (isNetworkAvailable()) getString(R.string.note_mlkit_service_unavailable)
                else getString(R.string.note_mlkit_no_internet)
        }
        return TranslateOutcome(this.text, note, kind, this.displacedLlmId, this.backend.displayName)
    }

    /**
     * Returns the status bar height in pixels for [displayId], or 0 if there is no
     * status bar or it cannot be determined.
     */
    internal fun getStatusBarHeightForDisplay(displayId: Int): Int {
        val dm = getSystemService(android.hardware.display.DisplayManager::class.java) ?: return 0
        val display = dm.getDisplay(displayId) ?: return 0
        return try {
            createDisplayContext(display).statusBarHeightPx()
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
        val iconShowing = CaptureBackendResolver.activeOverlayUi?.hasAnyFloatingIcon == true

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

        // A held MediaProjection must stay backed by a running mediaProjection
        // foreground service (Android 14+). A one-shot capture
        // (MediaProjectionCaptureSource.requestClean) can acquire consent and
        // leave the projection warm with neither live mode nor a floating icon
        // up — iconShowing || isLive alone would then stopForeground() out
        // from under an active projection and the system would tear it down.
        if (iconShowing || isLive || mediaProjectionController.hasConsent) {
            enterForeground()
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    /** Promote the foreground service to include the mediaProjection type.
     *  MUST run before MediaProjectionManager.getMediaProjection() on API
     *  34+. Routes through [enterForeground], which derives the type from
     *  [MediaProjectionController.hasConsent] — the call carries the
     *  mediaProjection type whenever consent is held, and drops it back to
     *  SPECIAL_USE only once consent goes away. */
    internal fun ensureMediaProjectionForegroundType() {
        enterForeground()
    }

    /** startForeground with the correct service type(s).
     *
     *  Pre-34: foreground-service types are declarative-only — no per-type
     *  permission enforcement, no mediaProjection token rule — so the 2-arg
     *  call (which applies the manifest-declared type) is all that's needed.
     *  It is also the exact call v2.2.0 shipped, field-proven across OEMs at
     *  minSdk 30; the explicit 3-arg + specialUse-int form below is new on
     *  this branch, so pre-34 deliberately stays on the proven path.
     *
     *  API 34+: the type must be passed explicitly, and must include the
     *  mediaProjection type exactly when [MediaProjectionController.hasConsent]
     *  is true — single source of truth, no separate flag to drift out of
     *  sync with the consent token. The catch handles the platform rejecting
     *  the MP type by invalidating the consent that claimed it, so a
     *  subsequent ensureProjection short-circuits on null resultData (no
     *  doomed getMediaProjection) and the user re-prompts on the next
     *  capture attempt. */
    private fun enterForeground() {
        // Pre-34 has none of the FGS-type machinery below — one proven call.
        if (Build.VERSION.SDK_INT < 34) {
            startForeground(NOTIF_ID, buildNotification())
            return
        }
        if (mediaProjectionController.hasConsent) {
            try {
                startForeground(
                    NOTIF_ID, buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
                )
                return
            } catch (e: Exception) {
                // The mediaProjection FGS type is only valid while a live
                // screen-record token is held. The token can lapse out from
                // under us (single-use on API 34+, or the system stopping
                // the projection) and the platform then rejects this start
                // with a SecurityException. Invalidate the consent so
                // hasConsent reflects reality; the SPECIAL_USE fall-through
                // below still gets the service to the foreground.
                Log.w(TAG, "enterForeground: mediaProjection FGS type rejected, " +
                    "falling back to SPECIAL_USE — ${e.message}")
                mediaProjectionController.invalidateConsent()
            }
        }
        // SPECIAL_USE only — the no-consent (or rejected-MP-type) state.
        startForeground(
            NOTIF_ID, buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
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
