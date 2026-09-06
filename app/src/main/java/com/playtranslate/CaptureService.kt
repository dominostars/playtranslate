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
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.view.Display
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
import androidx.core.content.ContextCompat
import com.playtranslate.model.OcrProvenance
import com.playtranslate.model.PendingTranslation
import com.playtranslate.model.TextSegment
import com.playtranslate.model.TranslationResult
import com.playtranslate.ocr.registry.OcrModelManager
import com.playtranslate.ocr.registry.ocrLabel
import com.playtranslate.ocr.registry.selectionToken
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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.hardware.display.DisplayManager
import com.playtranslate.capture.CaptureBackendResolver
import com.playtranslate.capture.GameAudioGate
import com.playtranslate.capture.CaptureLifecycle
import com.playtranslate.capture.GameAudioRecorder
import com.playtranslate.capture.MediaProjectionCaptureBackend
import com.playtranslate.capture.MediaProjectionCaptureSource
import com.playtranslate.capture.MediaProjectionController
import com.playtranslate.capture.StreamKind
import com.playtranslate.dictionary.DictionaryManager
import com.playtranslate.overlay.OverlayHost
import com.playtranslate.language.ChineseScriptVariant
import com.playtranslate.language.SourceLangId
import com.playtranslate.language.SourceLanguageProfiles
import com.playtranslate.translation.ChineseScriptConverter
import com.playtranslate.language.targetSupportsVerticalText
import com.playtranslate.language.stackableTargetScript
import com.playtranslate.translation.TranslationBackendRegistry
import com.playtranslate.language.ShortTextTokenCounters
import com.playtranslate.language.isShortText
import com.playtranslate.translation.PartitionedResult
import com.playtranslate.translation.ShortTextOfflineRoute
import com.playtranslate.translation.dispatchPartitioned
import com.playtranslate.translation.shouldBypassForLlm
import com.playtranslate.ui.DegradedWarningKind
import com.playtranslate.ui.TextBox
import com.playtranslate.ui.noTextStatusMessage

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

    // ── Internal emit helpers (callable from sibling capture modes) ──────

    internal fun emitResult(result: TranslationResult) {
        _panelState.value = PanelState.Result(result)
        // Deliberately NO livePanelRecord.committed here: only screen-derived
        // deliveries ([translateAndSendToPanel]) commit. A deliberate flow
        // (re-translate, deferred history) emitting through this writer shows
        // text that is NOT the live screen — recording it would make the
        // screen's unchanged text look new and let the live loop stomp the
        // user's requested result one settled cycle later ([LivePanelRecord]).
    }
    internal fun emitError(message: String) {
        _panelState.value = PanelState.Error(message)
        livePanelRecord.clear()
    }
    /**
     * The panel's "a live cycle looked at [displayId] and found nothing"
     * state — the live-tier twin of the one-shot's [CaptureState.NoText],
     * built from the same message + provenance so the in-app page renders
     * one no-text status for both (tappable source language, OCR gear).
     *
     * Deliberately pins NO screenshot, unlike every one-shot no-text. The
     * gear exists so the user can switch OCR engine when "no results" is
     * really "the engine didn't see it", and while live mode is running the
     * LOOP is what acts on that switch: the engine resolves per OCR call
     * ([com.playtranslate.ocr.registry.OcrEngineRegistry.engineFor]), so the
     * next look already uses the new one, reading the screen as it is NOW.
     * A pinned frame would only buy a re-read of stale pixels — and on the
     * pinhole tier those pixels carry our own overlay boxes (its OCR runs on
     * a filled COPY, never on the honest frame), so re-OCRing them would
     * read our translations back as source text.
     */
    internal fun emitLiveNoText(displayId: Int) {
        val region = activeRegionForDisplay(displayId)
        _panelState.value = PanelState.NoText(
            noTextMessage(displayId, region),
            noTextProvenanceFor(displayId, region, Prefs(this).sourceLangId),
        )
        // The recorded text is no longer what the panel shows — identical
        // text REAPPEARING after a no-text gap must deliver again.
        livePanelRecord.clear()
    }
    /** Reset the sticky panel stream to [PanelState.Idle] so its replay can't re-show a
     *  stale result after the activity returns (e.g. from the language picker). Idle is a
     *  no-op for the panel collector, so this does NOT itself blank the on-screen result —
     *  the caller clears the VM separately (and can keep the result visible until then). */
    internal fun clearPanel() {
        _panelState.value = PanelState.Idle
        livePanelRecord.clear()
    }

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

    /** Whether a one-shot hold gesture is currently in flight, i.e. whether
     *  the floating icons should be wearing their loading spinner. State of
     *  record for that spinner — see [setHoldLoading]. */
    private var holdLoading = false

    /**
     * Arm / disarm the floating icons' hold spinner.
     *
     * Pushed straight at the icons rather than published on a flow the
     * activity collects. The spinner is a sub-window of an overlay icon the
     * service owns, and the gesture that arms it happens precisely when
     * MainActivity is STOPPED — the user is holding the icon over a
     * fullscreen game. A `repeatOnLifecycle(STARTED)` collector is cancelled
     * in exactly that state, so routing this through the activity meant the
     * spinner could only appear when the app itself was already on screen
     * (dual-screen), and never in the single-screen case it exists for. The
     * icons' live-mode and degraded colours have always been pushed from here
     * ([syncIconState]) for the same reason; only this one had drifted onto
     * the activity, when the service's outbound callbacks became flows.
     *
     * Main thread only — it ends in View mutation. Every caller already is:
     * the icon's touch handlers, MainActivity, and [serviceScope] (which is
     * Dispatchers.Main).
     */
    internal fun setHoldLoading(loading: Boolean) {
        if (holdLoading == loading) return
        holdLoading = loading
        CaptureBackendResolver.activeOverlayUi?.setIconsLoading(loading)
    }

    /** Push current service state to every floating icon. Called automatically
     *  by [setLiveDisplays] (on the empty↔non-empty transition), [setDegraded],
     *  when icons are installed or torn down (from
     *  OverlayUiController.installFloatingIconForDisplay /
     *  hideFloatingIconForDisplay), and by the game-audio reconcile
     *  ([reconcileGameAudio] / GameAudioRecorder.reconcile) — every seam that
     *  moves the audio gates the icon's glyph reads already funnels through
     *  that reconcile. Main thread only. */
    fun syncIconState() {
        val ui = CaptureBackendResolver.activeOverlayUi ?: return
        ui.setIconsLiveMode(isLive)
        ui.setIconsDegraded(translationDegraded)
        ui.setIconsLoading(holdLoading)
        ui.setIconsGlyph(
            GameAudioGate.iconGlyph(
                this, mediaProjectionControllerIfInitialized, CaptureBackendResolver.active(),
            ),
        )
    }

    // ── Debug: MediaProjection mirror probe (Step-0 "D1" verification) ────
    //
    //   adb shell am broadcast -a com.playtranslate.debug.MP_PROBE
    //
    // With accessibility live-mode overlays showing, obtains MediaProjection
    // consent and dumps ONE raw mirrored frame to files/pinhole_dumps/, to
    // answer whether the accessibility overlay window (and its pinhole mask)
    // appears in the MP mirror at all — the premise the MP-capture-with-a11y
    // plan stands on. Debug builds only; never registered in release.
    private val mpProbeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            serviceScope.launch {
                if (!mediaProjectionController.ensureConsent()) {
                    DetectionLog.log("MP probe: consent declined")
                    return@launch
                }
                DetectionLog.log(
                    "MP probe: streamKind=${mediaProjectionController.streamKind} " +
                        "contentSize=${mediaProjectionController.contentSize.value} " +
                        "visible=${mediaProjectionController.contentVisible.value}"
                )
                // maskOwnWindows = false: a diagnostic dump of the mirror
                // must show what the mirror actually carries.
                val bmp = mediaProjectionCaptureSource
                    .requestRaw(mediaProjectionController.projectedDisplayId, maskOwnWindows = false)
                if (bmp == null) {
                    DetectionLog.log("MP probe: capture failed")
                    return@launch
                }
                val path = withContext(Dispatchers.IO) {
                    runCatching {
                        val dir = File(getExternalFilesDir(null), "pinhole_dumps")
                        dir.mkdirs()
                        val f = File(dir, "mp_probe_${System.currentTimeMillis()}.png")
                        FileOutputStream(f).use { bmp.bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        f.absolutePath
                    }.getOrNull()
                }
                bmp.recycle()
                Log.i(TAG, "MP probe: saved ${path ?: "FAILED"}")
                DetectionLog.log("MP probe: ${path ?: "save failed"}")
            }
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate")
        instance = this
        createNotificationChannel()

        if (BuildConfig.DEBUG) {
            ContextCompat.registerReceiver(
                this, mpProbeReceiver,
                IntentFilter(ACTION_DEBUG_MP_PROBE),
                ContextCompat.RECEIVER_EXPORTED,
            )
        }

        // Register hotkey callbacks (whichever service started first)
        PlayTranslateAccessibilityService.instance?.registerHotkeyCallbacks()
        // The accessibility backend's floating icon can pre-date this service
        // — it is hosted by the accessibility service, and a cold app open
        // installs it before the bind that creates us lands — while its glyph
        // reads consent state only this service owns. Push it now rather than
        // leaving the default filled arrow up until the next state change. Accessibility-gated on purpose: the MediaProjection icon is
        // hosted by this service's own lazily-built overlay UI, which the sync
        // would otherwise force-initialize here.
        if (CaptureBackendResolver.active().requiresAccessibilityService) {
            syncIconState()
            // A fresh icon placement that found no service alive (that same
            // cold app open) parked its game-audio consent prompt rather than
            // cold-starting us from a passive path; fire it now that we exist.
            CaptureBackendResolver.activeOverlayUi?.firePendingPlacementPrompt(this)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand action=${intent?.action}")
        // Android requires startForeground() within 5s of startForegroundService()
        //
        // ACTION_MP_ACTIVATE entry: on a cold-start tile click, enterForeground
        // below is invoked synchronously with no live overlay window and no
        // held consent. enterForeground promotes to FOREGROUND_SERVICE_TYPE_
        // SPECIAL_USE (NOT mediaProjection — that type would assert consent
        // we don't yet have). API 35+ verification on emulator confirms this
        // succeeds under the tile-onclick tempAllowList grant; the
        // SPECIAL_USE → SPECIAL_USE|MEDIA_PROJECTION promotion happens later
        // in ensureMediaProjectionForegroundType once the user has granted.
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
        if (BuildConfig.DEBUG) runCatching { unregisterReceiver(mpProbeReceiver) }
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
        // Stop the game-audio recorder BEFORE the projection teardown below,
        // so its AudioRecord releases against a still-live projection.
        if (gameAudioRecorderLazy.isInitialized()) {
            gameAudioRecorder.destroy()
        }
        // Release the MediaProjection capture source (its poll loops; also the
        // session, via the controller destroy inside source.destroy()). Same
        // lazy-gate pattern — sessions that never captured skip this instead
        // of force-initializing the MP backend just to tear it down.
        if (mediaProjectionCaptureSourceLazy.isInitialized()) {
            mediaProjectionCaptureSource.destroy()
        }
        // The projection session itself is owned by the CONTROLLER, and the
        // source gate above is not proof it was released: the game-audio
        // recorder realizes the controller — and promotes held consent into a
        // live projection — without ever touching the capture source. Whoever
        // owns the projection dies with the service; nothing else releases
        // those native resources. Redundant after source.destroy() above —
        // teardown() no-ops on a dead session.
        mediaProjectionControllerIfInitialized?.destroy()
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
     *  treats the persisted Prefs as the source of truth). Callers pass
     *  [Prefs.captureDisplayIds] verbatim; [gameDisplayIds] keeps that raw
     *  selection, and only the region-change fan-out is resolved through
     *  the backend shim — see the comment below. */
    fun configureSaved(
        displayIds: Set<Int>,
        primaryDisplayId: Int = displayIds.firstOrNull() ?: 0,
    ) {
        // Resolve the region-change fan-out through the backend shim. The
        // raw prefs selection can name displays the active backend cannot
        // capture (a second-screen id while MediaProjection mirrors only the
        // default display). Live start already resolves via setLiveDisplays,
        // and the region picker writes its selection under the resolved id —
        // so fanning out over the raw ids sent afterRegionChange's
        // refresh/flash to a display whose region map has no entry
        // (full-screen fallback, which the indicator silently skips) instead
        // of the display the picker wrote and live mode actually captures.
        // Identity on the accessibility backend for any non-empty set.
        //
        // gameDisplayIds deliberately keeps the RAW selection: its
        // cardinality is load-bearing. shouldSkipDisplay's foreground gate
        // and oneShotFanoutDisplayIds' single-display exception both read
        // `gameDisplayIds.size` to tell a genuine single-display setup from
        // a stale multi-display selection that capturableTargets collapsed
        // to the MediaProjection fallback — storing the collapsed set would
        // disarm that guard and let a fan-out one-shot OCR the app's own
        // foregrounded UI (Codex review find).
        val resolvedIds = CaptureBackendResolver.active().capturableTargets(displayIds)

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
        val rememberedPrimary = lastInteractedDisplayId
        if (primaryDisplayId in displayIds) {
            lastInteractedDisplayId = primaryDisplayId
        } else if (rememberedPrimary != null && rememberedPrimary !in displayIds) {
            // The remembered primary fell out of the selection (display
            // deselected in settings). Re-home it onto a selected display —
            // the same repair onDisplayRemoved performs — because
            // primaryGameDisplayId() prefers this field WITHOUT a membership
            // check, so a stale value would keep routing hotkey one-shots
            // and the in-app panel to a display the service no longer
            // captures. firstOrNull: no caller passes an empty set today
            // (the Prefs getter never returns one), and null simply defers
            // to primaryGameDisplayId's own fallback chain.
            lastInteractedDisplayId = displayIds.firstOrNull()
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
        // this. Pass the full resolved set because configureSaved is the
        // fan-out path — every selected display's region selection in
        // Prefs may have been rewritten by the caller before this call.
        // Region indicator should only flash on still-selected displays,
        // so removed ids stay out of the changed set.
        afterRegionChange(resolvedIds)
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
        /** Chinese script choice; meaningful only when [target] == "zh". */
        val chineseVariant: ChineseScriptVariant,
        /** True when the OCR'd source is already Traditional (ZH_HANT) — so a
         *  same-language passthrough must NOT be re-run through a Simplified→
         *  Traditional pass. Only [source]/[target] feed the cache key, so these
         *  extra fields don't fragment the shared "zh" cache. */
        val sourceIsTraditional: Boolean,
        /** The resolved source-language id behind [source], for the short-text
         *  classifier's per-language segmenter. Snapshotted with the pair so a
         *  mid-batch source switch can't classify under one language and
         *  translate under another. */
        val sourceId: SourceLangId,
    ) {
        /** OpenCC converter for Traditional Chinese output, or null when no
         *  conversion applies (non-Chinese target, Simplified, or an already-
         *  Traditional source passthrough). */
        private val chineseConverter: ChineseScriptConverter?
            get() = ChineseScriptConverter.forTarget(target, chineseVariant, sourceIsTraditional)

        /** Convert a finished target-language string to the chosen Traditional
         *  variant. No-op for non-Chinese / Simplified targets. Applied strictly
         *  at read/return time — never before a cache write (the cache stores
         *  Simplified, shared across all variants). */
        fun localize(text: String): String = chineseConverter?.convert(text) ?: text
    }

    /** Capture a [TranslationTarget] from current [Prefs]. Called once at
     *  the outermost layer of each translation entry point; downstream calls
     *  thread the captured value rather than re-reading Prefs, so mid-batch
     *  changes can't create inconsistency between key-derivation and
     *  translator selection. */
    private fun snapshotTranslationTarget(sourceOverride: SourceLangId? = null): TranslationTarget {
        val prefs = Prefs(this)
        // [sourceOverride] lets a re-OCR translate in the language that actually
        // produced the result (its provenance), not the current pref — the user may
        // have switched source language since. Target/variant still come from prefs.
        val srcId = sourceOverride ?: prefs.sourceLangId
        return TranslationTarget(
            source = SourceLanguageProfiles[srcId].translationCode,
            target = prefs.targetLang,
            deeplKey = prefs.deeplApiKey,
            chineseVariant = prefs.targetChineseVariant,
            sourceIsTraditional = srcId == SourceLangId.ZH_HANT,
            sourceId = srcId,
        )
    }

    /** Called at the top of every translation call to keep the cache's
     *  ROUTING identity (preferred backend + short-text policy knobs, see
     *  [TranslationCache.routingIdentity]) in sync with current
     *  configuration. Backends themselves are owned by
     *  [TranslationBackendRegistry] and are pair-agnostic singletons —
     *  there is no per-pair instance churn to reconcile. Pair changes are
     *  handled by the cache key itself — no explicit clear needed. */
    private fun ensureLanguageManagersFor(target: TranslationTarget) {
        val prefs = Prefs(this)
        translationCache.reconcilePreferredBackend(
            TranslationCache.routingIdentity(
                preferredOnlineId =
                    TranslationBackendRegistry.preferredOnlineId(target.source, target.target),
                llmContextEnabled = prefs.llmContextEnabled,
                bergamotEnabled = prefs.bergamotEnabled,
                shortTextRoutingEnabled = prefs.debugShortTextRouting,
            )
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
     *  progress/result. Cancels any prior one-shot session.
     *  [allowDeferTranslation] is a per-call-site opt-in for the deferred
     *  path (skip MT while the translation section is hidden — see
     *  [PendingTranslation]); a surface that will paint translated boxes
     *  immediately must not opt in. */
    fun captureOnce(
        displayId: Int = primaryGameDisplayId(),
        allowDeferTranslation: Boolean = false,
    ): CaptureSession {
        oneShotCaptureJob?.cancel()
        val state = MutableStateFlow<CaptureState>(
            CaptureState.InProgress(getString(R.string.status_capturing))
        )
        val job = serviceScope.launch { runCaptureCycle(displayId, state, allowDeferTranslation) }
        attachCancellationTerminal(job, state)
        oneShotCaptureJob = job
        return CaptureSession(state.asStateFlow(), job)
    }

    /**
     * Processes a pre-captured screenshot bitmap instead of taking a new one.
     * Used when the screenshot must be taken before an activity appears on screen
     * (e.g. single-screen region capture from the floating menu).
     *
     * Re-OCR callers (override-carrying calls) keep [allowDeferTranslation]
     * false — deliberately: a re-OCR records no History rows (see the gate in
     * [runProcessCycle]), so a deferred re-OCR would have nothing to attach
     * its late translation to.
     */
    fun processScreenshot(
        frame: com.playtranslate.capture.CapturedFrame,
        displayId: Int = primaryGameDisplayId(),
        regionOverride: RegionEntry? = null,
        sourceLangIdOverride: SourceLangId? = null,
        allowDeferTranslation: Boolean = false,
    ): CaptureSession {
        oneShotCaptureJob?.cancel()
        val state = MutableStateFlow<CaptureState>(
            CaptureState.InProgress(getString(R.string.status_capturing))
        )
        val job = serviceScope.launch {
            runProcessCycle(frame, displayId, state, regionOverride, sourceLangIdOverride, allowDeferTranslation)
        }
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
     *  CancellationException while [state] is still NON-TERMINAL
     *  (InProgress / Translating — translation is the slow window, so
     *  cancellation here is routine, not hypothetical). Skipping terminal
     *  states stays defensive against a race with the pipeline's own
     *  terminal write. The decision lives in [cancelledStateOrNull] so the
     *  contract is unit-tested away from the Android-heavy pipeline. */
    private fun attachCancellationTerminal(
        job: Job,
        state: MutableStateFlow<CaptureState>,
    ) {
        job.invokeOnCompletion { cause ->
            cancelledStateOrNull(cause, state.value)?.let { state.value = it }
        }
    }

    /** One-shot capture from a pre-captured bitmap: walks [state]
     *  through Capturing → OCR → Translating → final Done/NoText/Failed.
     *  Owned by the [CaptureSession] returned from [processScreenshot]. */
    private suspend fun runProcessCycle(
        frame: com.playtranslate.capture.CapturedFrame,
        displayId: Int,
        state: MutableStateFlow<CaptureState>,
        regionOverride: RegionEntry? = null,
        sourceLangIdOverride: SourceLangId? = null,
        allowDeferTranslation: Boolean = false,
    ) {
        val raw = frame.bitmap
        val frameIncludesSystemUi = frame.includesSystemUi
        // The frame's capture moment as epoch ms (capturedAtMs is uptime;
        // one conversion at the same instant). Passed as the result's
        // createdAtMs so the game-audio anchor names the SHUTTER, not the
        // post-translation construction — MT latency (seconds to tens on
        // the slow tier) must not drift the trim seed.
        val capturedAtWallMs = System.currentTimeMillis() -
            (android.os.SystemClock.uptimeMillis() - frame.capturedAtMs)
        if (!isConfigured) {
            state.value = CaptureState.Failed("Not configured — tap Translate to set up")
            raw.recycle()
            return
        }
        var bitmap: Bitmap = raw
        var colorRef: Bitmap? = null
        try {
            state.value = CaptureState.InProgress(getString(R.string.status_capturing))
            val screenshotPath = captureSaveToCache(raw, displayId)

            // Source language + region come from current settings for a fresh capture,
            // or from the pinned overrides when re-OCR'ing an existing capture (so the
            // re-scan reads the SAME region/language that produced the result — only the
            // OCR engine changes). Snapshotted once, before OCR, so OCR + provenance
            // can't disagree even if settings change mid-cycle.
            val srcId = sourceLangIdOverride ?: Prefs(this@CaptureService).sourceLangId
            val region = regionOverride ?: activeRegionForDisplay(displayId)
            val statusBarHeight = statusBarHeightForFrame(displayId, frameIncludesSystemUi)
            val top    = maxOf((raw.height * region.top).toInt(), statusBarHeight)
            val left   = (raw.width  * region.left).toInt()
            val bottom = (raw.height * region.bottom).toInt()
            val right  = (raw.width  * region.right).toInt()
            val rawW = raw.width
            val rawH = raw.height
            // Snapshot the color-sampling reference now — cropBitmap recycles raw.
            colorRef = oneShotColorRef(raw)
            bitmap = cropBitmap(raw, top, bottom, left, right)

            // The frame is stamped as possibly containing our own overlay
            // windows (a re-OCR of a cached live raw frame) — black the
            // floating icon out before OCR reads its chevron as text.
            // In-place only when cropBitmap produced a fresh copy; a no-op
            // crop leaves `bitmap === raw`, and the frame must never be
            // drawn into (uniform rule — here the cache write and colorRef
            // snapshot already happened, but the safety must not depend on
            // that ordering). Cleanup of `bitmap` is the outer finally's job.
            if (frame.includesOwnOverlays) {
                val iconRect =
                    CaptureBackendResolver.activeOverlayUi?.getFloatingIconRect(displayId)
                if (iconRect != null) {
                    val blacked = OverlayToolkit.blackoutFloatingIcon(
                        bitmap, left, top, iconRect,
                        allowInPlace = bitmap !== raw,
                    )
                    if (blacked !== bitmap) {
                        bitmap.recycle()
                        bitmap = blacked
                    }
                }
            }
            state.value = CaptureState.InProgress(getString(R.string.status_ocr))
            val ocrResult = ocrManager.recognise(
                bitmap, SourceLanguageProfiles[srcId].translationCode, screenshotWidth = raw.width,
            )
            if (BuildConfig.DEBUG && Prefs(this@CaptureService).debugSaveOcrSeed) {
                OcrSeedWriter.writeSeed(this@CaptureService, bitmap, ocrResult)
            }

            if (ocrResult == null) {
                state.value = CaptureState.NoText(
                    noTextMessage(displayId),
                    noTextProvenanceFor(
                        displayId, region, srcId, frameIncludesSystemUi,
                        frame.includesOwnOverlays,
                    ),
                    screenshotPath,
                )
                return
            }

            // Reveal the page on OCR: show the source now, translate in the section.
            // The skeleton boxes ride along so a chips-preferred panel can collapse
            // NOW and show pulsing placeholders over the game while we translate.
            val skeletonData = buildOneShotOverlayData(ocrResult, colorRef, left, top, rawW, rawH)
            state.value = CaptureState.Translating(
                ocrResult.fullText, ocrResult.segments,
                ocrProvenanceFor(
                    ocrResult, displayId, region, srcId, frameIncludesSystemUi,
                    frame.includesOwnOverlays,
                ),
                overlayData = skeletonData,
            )
            // Pin translation to the SAME source language OCR used (srcId), not current
            // Prefs — for a re-OCR after a source-language change they'd otherwise disagree,
            // sending the recognized text through the wrong source/target cache key and
            // translator. No-op for a fresh capture, where srcId == Prefs.sourceLangId.
            // Per-group (same batch [translateGroups] wraps) so the on-screen overlay
            // boxes get their per-group texts; the panel text is the same join.
            // Recording target captured BEFORE the translate call — a
            // mid-flight language change must not relabel these rows
            // (srcId is already pinned above).
            val recordSrc = SourceLanguageProfiles[srcId].translationCode
            val recordTgt = Prefs(this@CaptureService).targetLang
            // Deferred path — same contract as runCaptureOcrTranslate. Re-OCR
            // callers never opt in (allowDeferTranslation stays false at their
            // call sites): their recording is gated off below, so a deferred
            // re-OCR would have nothing to attach its late translation to.
            val deferTranslation = allowDeferTranslation &&
                Prefs(this@CaptureService).hideTranslationSection &&
                recordSrc != recordTgt
            val perGroup = if (deferTranslation) null
                else translateGroupsSeparately(ocrResult.groups.map { it.text }, srcId)

            // Deliberate capture → recording backend, FRESH captures only:
            // override-carrying calls are re-OCRs of an already-recorded
            // capture (engine change from the results surface), and a
            // refinement must not mint a second session's worth of rows.
            // Deferred rows record with a null translation; the normal path
            // keeps skipping rows whose translation came back blank.
            // Logging eligibility snapshotted WITH the recording (see
            // runCaptureOcrTranslate) — a deferred completion may only write
            // what the user had opted into at THIS moment.
            val historyEligible = Prefs(this@CaptureService).translationHistoryEnabled
            val contextEligible = Prefs(this@CaptureService).llmContextEnabled
            var captureSessionId: String? = null
            if (regionOverride == null && sourceLangIdOverride == null) {
                val token = translationLogRecorder.beginCaptureSession()
                captureSessionId = token.sessionId.takeIf { historyEligible }
                ocrResult.groups.forEachIndexed { i, g ->
                    val tr = perGroup?.getOrNull(i)?.text.orEmpty()
                    if (deferTranslation || tr.isNotEmpty()) translationLogRecorder.onCaptureShown(
                        token, g.text, tr.takeIf { it.isNotEmpty() }, g.bounds, recordSrc, recordTgt,
                        com.playtranslate.translationlog.TranslationHistoryStore.PROVENANCE_ONE_SHOT,
                        perGroup?.getOrNull(i)?.backendDisplayName,
                        captureImage = screenshotPath?.let {
                            com.playtranslate.translationlog.HistoryImageStore.Source.FromPath(it)
                        },
                    )
                }
            }

            val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            state.value = CaptureState.Done(
                TranslationResult(
                    originalText        = ocrResult.fullText,
                    segments            = ocrResult.segments,
                    translatedText      = perGroup?.joinToString("\n\n") { it.text }.orEmpty(),
                    timestamp           = timestamp,
                    screenshotPath      = screenshotPath,
                    note                = perGroup?.mapNotNull { it.note }?.firstOrNull(),
                    backendDisplayName  = perGroup?.mapNotNull { it.backendDisplayName }?.firstOrNull(),
                    ocrProvenance       = ocrProvenanceFor(
                        ocrResult, displayId, region, srcId, frameIncludesSystemUi,
                        frame.includesOwnOverlays,
                    ),
                    pendingTranslation  = if (deferTranslation) PendingTranslation(
                        groupTexts = ocrResult.groups.map { it.text },
                        sourceLangId = srcId,
                        targetLang = recordTgt,
                        isCapture = true,
                        historySessionId = captureSessionId,
                        historyEligible = historyEligible,
                        contextEligible = contextEligible,
                    ) else null,
                    langContext         = Prefs(this@CaptureService).langContext(srcId),
                    createdAtMs         = capturedAtWallMs,
                ),
                // Deferred: keep the SKELETONS — the deferred completion fills
                // them when the translation finally runs.
                overlayData = if (perGroup != null)
                    fillOneShotOverlayData(skeletonData, perGroup.map { it.text })
                else skeletonData,
            )
        } catch (e: CancellationException) {
            // Let cancellation propagate; invokeOnCompletion writes Cancelled.
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Process cycle failed: ${e.message}", e)
            state.value = CaptureState.Failed(e.message ?: "Unknown error")
        } finally {
            colorRef?.recycle()
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    /** Build the [OcrProvenance] for a NO-TEXT result, where there is no [OcrResult]
     *  to read the engine from — attribute it to the engine that WAS selected (and
     *  ran, finding nothing) for [srcId]. Pins the engine, language, [region], and
     *  [displayId] so the "switch OCR tool" gear can re-OCR THIS exact capture. Null
     *  when no OCR backend is available (e.g. a no-floor language on a 32-bit device).
     *  The two frame facts are nullable for the live tiers, which pin no screenshot at
     *  all ([emitLiveNoText]) — with nothing to re-crop, there is nothing to describe. */
    private fun noTextProvenanceFor(
        displayId: Int,
        region: RegionEntry,
        srcId: SourceLangId,
        frameIncludesSystemUi: Boolean? = null,
        frameIncludesOwnOverlays: Boolean? = null,
    ): OcrProvenance? {
        val backend = OcrModelManager.selectedBackend(this, srcId) ?: return null
        return OcrProvenance(
            backend.ocrLabel(this), backend.selectionToken, displayId, srcId, region,
            frameIncludesSystemUi = frameIncludesSystemUi,
            frameIncludesOwnOverlays = frameIncludesOwnOverlays,
        )
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

    /** Single aggregate "a translation session is in progress" predicate: live
     *  mode, the region/menu one-shot ([oneShotCaptureJob]), OR the
     *  press-and-hold overlay ([oneShotManager]). The Yomitan auto-updater gates
     *  its DB-mutating apply on this so an update never disrupts an in-progress
     *  session. Defined once here so a future capture entry point can't silently
     *  re-open the gate. Read cross-thread (these fields mutate on Main) —
     *  best-effort by design; the ingest transaction's atomicity is the real
     *  safety net. */
    val isCapturing: Boolean
        get() = isLive || oneShotCaptureJob?.isActive == true || oneShotManager.hasActive()

    // ── MediaProjection backend state ─────────────────────────────────────
    //
    // Lazily created; untouched until the MediaProjection backend is the
    // active one. MediaProjectionCaptureBackend forwards to these, just as
    // the accessibility backend forwards to the accessibility service.

    /** Owns the MediaProjection session (consent, VirtualDisplay, ImageReader).
     *  Stored as an explicit [Lazy] because the capture source's initialization
     *  state is NOT a proxy for "a projection may exist": the game-audio
     *  recorder realizes the controller — and promotes held consent into a
     *  live projection — without ever touching
     *  [mediaProjectionCaptureSourceLazy]. Teardown paths ([onDestroy], the
     *  accessibility branch of
     *  [com.playtranslate.capture.CaptureLifecycle.deactivate]) gate on THIS
     *  lazy via [mediaProjectionControllerIfInitialized]. */
    private val mediaProjectionControllerLazy = lazy {
        MediaProjectionController(this).also { controller ->
            // Consent loss ⇒ the floating icon's arrow goes outlined. Bound to
            // the controller's own teardown notification (every consent-ending
            // path fires it) rather than to each caller's downstream
            // reconcile, so a path with no reconcile of its own
            // (invalidateConsent on an FGS-type rejection) still repaints.
            // Grants reach the icon through onConsentResult →
            // reconcileGameAudio → syncIconState.
            controller.addTeardownListener { syncIconState() }
        }
    }
    internal val mediaProjectionController: MediaProjectionController
        by mediaProjectionControllerLazy

    internal val mediaProjectionControllerIfInitialized: MediaProjectionController?
        get() = if (mediaProjectionControllerLazy.isInitialized()) mediaProjectionController else null

    /** Sticky MediaProjection-backend activation — the Turn On / Turn Off
     *  state [com.playtranslate.capture.CaptureLifecycle] reads, and (via
     *  canShowControls) the gate for the floating controls. Deliberately NOT
     *  the consent token: on API 34+ consent is single-use and dies with
     *  every projection loss (status-bar-chip revoke, Android 15 lock
     *  auto-stop), which must not read as "the user turned PlayTranslate
     *  off" — the controls stay up and the next capture-requiring action
     *  re-prompts (see [MediaProjectionController.onProjectionLost]).
     *
     *  Exactly three writers. SET on a consent grant landing while this
     *  backend is the active one —
     *  [MediaProjectionController.onConsentResult], the choke point all
     *  grants flow through, so the lazy in-app prompts count as Turn On
     *  too; a grant borrowed by an accessibility-backend live session does
     *  NOT count (see onConsentResult's kdoc). CLEARED by Turn Off
     *  ([com.playtranslate.capture.CaptureLifecycle.deactivate]) and by ANY
     *  backend swap
     *  ([com.playtranslate.capture.CaptureBackendResolver.reresolve]).
     *  Invariant, per-backend: on the MediaProjection backend,
     *  hasConsent ⇒ activated; the converse — activated without consent —
     *  is the deliberate post-revoke state.
     *  Runtime state: dies with the service, so a process restart still
     *  comes up inactive, exactly as the old consent-derived "active" did.
     *  @Volatile for parity with the consent fields this replaced as the
     *  "active" source ([MediaProjectionController]'s resultCode/resultData
     *  are @Volatile): reads and writes are not all main-confined —
     *  [com.playtranslate.capture.CaptureLifecycle.deactivate] is documented
     *  safe-from-any-context, and the QS tile / Settings poll
     *  [com.playtranslate.capture.CaptureLifecycle.isActive] on their own
     *  schedules. */
    @Volatile
    internal var mediaProjectionActivated = false

    /** One-shot clean-capture source backed by [mediaProjectionController].
     *  Stored as an explicit [Lazy] so [onDestroy] can gate teardown on
     *  whether the source was ever touched (via [Lazy.isInitialized]) without
     *  force-initializing it through the property access itself. */
    private val mediaProjectionCaptureSourceLazy = lazy {
        MediaProjectionCaptureSource(mediaProjectionController)
    }
    internal val mediaProjectionCaptureSource: MediaProjectionCaptureSource
        by mediaProjectionCaptureSourceLazy

    /** Rolling game-audio recording for Anki sentence cards (opt-in). Same
     *  explicit-[Lazy] pattern — [reconcileGameAudio] checks the pref before
     *  touching it, so users who never opt in never allocate an AudioRecord
     *  or the ~16 MB ring. */
    private val gameAudioRecorderLazy = lazy {
        GameAudioRecorder(this, mediaProjectionController)
    }
    internal val gameAudioRecorder: GameAudioRecorder by gameAudioRecorderLazy

    /** Re-evaluate whether the game-audio recorder should RUN — the single
     *  push-point entry the consent/activate/deactivate/backend-swap/settings
     *  and activity-lifecycle seams all call, mirroring [reconcileLiveModes].
     *  Pref-gated BEFORE the lazy so the recorder is never force-initialized
     *  just to be told to stop.
     *
     *  Run-state ONLY — session lifecycle (the audio-only release) belongs
     *  to [setRecordGameAudio], the explicit transition site. This
     *  push-point fires from every seam, including consent delivery itself
     *  ([MediaProjectionController.onConsentResult]) and the consent
     *  activity's own resume/pause, so a release here would burn a
     *  just-granted token — the live-start stream borrow arrives exactly
     *  there: granted, not yet live — in the same breath that granted it. */
    /** The live half of [launchAudioCaptureConsent]: ask for the consent with
     *  this service already running, so no foreground-service start is
     *  involved — the only entry the passive icon-placement prompt may use
     *  ([com.playtranslate.OverlayUiController.firePendingPlacementPrompt]).
     *  A grant reaches the recorder and the icon through
     *  [MediaProjectionController.onConsentResult] → [reconcileGameAudio]; a
     *  decline changes nothing. The dialog itself is an activity started from
     *  a service; the caller owns the launch-context question. */
    fun requestAudioCaptureConsent() {
        serviceScope.launch { mediaProjectionController.ensureConsent() }
    }

    fun reconcileGameAudio() {
        if (!Prefs(this).recordGameAudio) {
            if (gameAudioRecorderLazy.isInitialized()) gameAudioRecorder.stop("pref off")
            // Feature off ⇒ the icon's glyph is the plain chevron again; in
            // the on state the recorder's own reconcile pushes the sync.
            // Main-routed like GameAudioRecorder.reconcile, since this
            // push-point is documented safe from any context.
            if (Looper.myLooper() === Looper.getMainLooper()) syncIconState()
            else mainHandler.post { syncIconState() }
            return
        }
        gameAudioRecorder.reconcile()
    }

    /** Feature-off hygiene, reached only from [setRecordGameAudio] on
     *  disable: on the accessibility backend the MediaProjection session
     *  may exist only as audio's ride (screen capture is the service), and
     *  once the user explicitly turns the feature off a kept-alive session
     *  would glow the OS capture chip with no client drinking from it.
     *  Tear it down — burns the single-use consent token, so re-enabling
     *  re-prompts: state honesty over prompt avoidance, the same trade
     *  Turn Off makes. Never on the MediaProjection backend (the
     *  projection IS the capture backend), and not while live mode runs —
     *  its borrowed MP stream ([startLive]'s wantMpStreamConsent) may be
     *  riding the same session; that session then lives until Turn Off,
     *  the standard warm-token policy for every borrow. */
    private fun releaseAudioOnlyProjection() {
        if (!CaptureBackendResolver.active().requiresAccessibilityService) return
        if (isLive) return
        mediaProjectionControllerIfInitialized?.destroy()
    }

    /** Shared recording backend for Text History + LLM context (both prefs
     *  default off; the recorder no-ops per call while they stay off). Same
     *  explicit-[Lazy] idiom; taps init it on first commit, the session
     *  hooks and the context provider never force-init just to observe. */
    private val translationLogRecorderLazy = lazy {
        com.playtranslate.translationlog.TranslationLogRecorder(applicationContext)
    }
    internal val translationLogRecorder: com.playtranslate.translationlog.TranslationLogRecorder
        by translationLogRecorderLazy

    internal val translationLogRecorderIfInitialized: com.playtranslate.translationlog.TranslationLogRecorder?
        get() = if (translationLogRecorderLazy.isInitialized()) translationLogRecorder else null

    /** Overlay-window host for MediaProjection mode (TYPE_APPLICATION_OVERLAY). */
    internal val mediaProjectionOverlayHost by lazy {
        OverlayHost(this, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
    }

    /** Game-screen overlay UI for MediaProjection mode. Its floating controls
     *  stay hidden until the user activates the backend (Turn On / QS tile) —
     *  see [OverlayUiController]'s canShowControls gate, which reads
     *  [mediaProjectionActivated] rather than consent so the controls survive
     *  a projection revoke. Stored as an explicit
     *  [Lazy] so [onDestroy] can gate teardown on whether the overlay UI was
     *  ever touched (via [Lazy.isInitialized]) — accessibility-only sessions
     *  never realize it and never need its teardown. */
    private val mediaProjectionOverlayUiLazy = lazy {
        OverlayUiController(this, mediaProjectionOverlayHost) {
            mediaProjectionActivated
        }.also { it.attach() }
    }
    internal val mediaProjectionOverlayUi: OverlayUiController
        by mediaProjectionOverlayUiLazy

    /** Last-seen rotation per capture display — the display listener's
     *  rotation discriminator ([DisplayManager.DisplayListener.onDisplayChanged]
     *  fires for state, refresh-rate, and hot-plug reasons too; only a
     *  change in the reported rotation means the geometry flipped). Seeded
     *  at mode install in [setLiveDisplays] — a lazy seed inside the
     *  callback would record the already-rotated value on the first report
     *  and miss the session's first rotation. Entries leave with their
     *  modes; main-thread confined like [liveModes]. */
    private val lastDisplayRotation = mutableMapOf<Int, Int>()

    /**
     * Listens for capture displays going away (external monitor unplugged,
     * virtual display destroyed) or transitioning to STATE_OFF (foldable
     * folded with the inactive panel selected). Per-display modes pause +
     * resume rather than tearing down all of live mode unless the entire
     * selection has gone offline. Also detects rotation (via
     * [lastDisplayRotation]) and gives the display's live mode its
     * settle-and-rebuild pass ([LiveMode.onDisplayRotated]).
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
            val display = getSystemService(DisplayManager::class.java)
                ?.getDisplay(displayId)
            val st = display?.state
            Log.d(TAG, "displayListener.onDisplayChanged($displayId) state=$st")
            // Rotation: hide the mode's overlays NOW and re-look after the
            // settle window, instead of waiting for a post-rotation capture
            // to trip the reactive dims guard (stale boxes float through
            // the whole animation) or OCR-ing a mid-rotation frame. A
            // second flip before the settle expired lands here again and
            // simply restarts the wait.
            val rotation = display?.rotation
            if (rotation != null) {
                val prev = lastDisplayRotation.put(displayId, rotation)
                if (prev != null && prev != rotation) {
                    Log.i(TAG, "Display $displayId rotated ($prev → $rotation)")
                    liveModes[displayId]?.onDisplayRotated()
                }
            }
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

        // One feedback session per start — engine warm-up, startup chip,
        // first-cycle gate, busy tracking ([LiveSessionFeedback]). The
        // previous session dies atomically first, so nothing it scheduled
        // (timers, chip, in-flight pass tokens) can leak into this one.
        // Construction also starts the eager warm-up, in parallel with
        // everything below (consent dialog, probe, virtual-display setup).
        liveFeedback?.dispose()
        dismissSlowOcrPrompt()
        liveFeedback = LiveSessionFeedback(
            serviceScope, mediaProjectionController, sourceLang,
            onSlowPass = { slowDisplayId -> maybeShowSlowOcrPrompt(slowDisplayId) },
        )

        // Secure capture readiness — the MediaProjection screen-record consent
        // token — BEFORE the live-mode loop and its touch sentinel exist. A
        // consent dialog launched from inside the running loop has its Cancel
        // tap caught by the 1×1 outside-touch sentinel as "game input", which
        // restarts the loop and re-launches the dialog in an unbreakable
        // cycle. canCaptureWithoutPrompting keeps the common already-ready
        // case (consent held, or the accessibility backend) fully synchronous.
        //
        // TRANSLATION live mode additionally prefers the MediaProjection
        // stream for CAPTURE even under the accessibility backend — the
        // delivery-gated cycle runs on the mirror's frame signal — so attempt
        // that consent up front too, best-effort: on decline, live mode
        // proceeds on accessibility capture (CaptureBackendResolver
        // .liveCaptureSourceFor falls back on !hasConsent). Re-prompts on
        // every start that lacks a warm token by design (2026-07-07 decision:
        // no remembered decline). Overlay hosting and input monitoring stay
        // with the accessibility backend either way.
        val wantMpStreamConsent = backend.requiresAccessibilityService &&
            desiredFlavor() == OverlayFlavor.TRANSLATION &&
            !mediaProjectionController.hasConsent
        // The consent dialog suspends this start for however long the user
        // stares at it. If a stop lands meanwhile (display disconnect, QS
        // tile off, device sleep), resuming must NOT resurrect live mode
        // with a stale display set (review finding). Structured
        // concurrency, not a flag: the pending start is a Job that
        // stopLive() — and any newer start — cancels outright.
        // API 34+ lets the consent dialog scope the stream to a single app;
        // TRANSLATION live mode routes by what was actually granted (clean
        // task stream vs whole display), so the stream kind must be resolved
        // before the mode instances are constructed. UNKNOWN with consent
        // held means "not probed this session" — divert through the async
        // path even when capture itself is already ready.
        val needsStreamKind = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            mediaProjectionController.hasConsent &&
            mediaProjectionController.streamKind == StreamKind.UNKNOWN
        pendingLiveStart?.cancel()
        pendingLiveStart = null
        if (backend.canCaptureWithoutPrompting && !wantMpStreamConsent && !needsStreamKind) {
            liveFeedback?.armChipGrace()
            beginLiveCapture()
        } else {
            pendingLiveStart = serviceScope.launch {
                if (wantMpStreamConsent) {
                    // Result deliberately ignored — decline means the
                    // accessibility capture path, not an error.
                    MediaProjectionCaptureBackend.ensureCaptureReady()
                } else if (!backend.ensureCaptureReady()) {
                    emitError(getString(R.string.error_screen_capture_denied))
                    // No session this start — its feedback dies with it.
                    liveFeedback?.dispose()
                    liveFeedback = null
                    return@launch
                }
                // A cancel that raced the await lands here at the next
                // suspension boundary; ensureActive makes it explicit.
                ensureActive()
                // Consent may have just been granted above — re-check, then
                // resolve the stream kind (cached per session; instant when
                // already resolved or on API ≤ 33).
                // Classify EVERY live session that will read MP frames —
                // panel mode included. The verdict is not just overlay
                // routing: it decides frame SEMANTICS for every consumer
                // (the includesSystemUi stamp → status-bar crop, and the
                // geometry refusal). An unclassified single-app grant would
                // stamp task frames as full-display and crop game content
                // off panel OCR (round-13 finding).
                if (mediaProjectionController.hasConsent) {
                    // One continuous status chip spans the probe AND the
                    // engine warm-up already running in parallel. The checker
                    // variant only when a probe will actually run this start;
                    // resolveStreamKind draws its pattern into the chip
                    // instead of a self-owned window that would vanish right
                    // before the warm-up wait.
                    val willProbe =
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                            mediaProjectionController.streamKind == StreamKind.UNKNOWN
                    if (willProbe) liveFeedback?.showProbeChip()
                    val kind = mediaProjectionController.resolveStreamKind(
                        liveFeedback?.probeSurface
                    )
                    // Whatever it settled to, the pattern's job is done:
                    // swap the grid for the spinner.
                    liveFeedback?.onVerdictSettled()
                    ensureActive()
                    // A live session never RUNS on an unmeasured verdict
                    // (2026-07-10 decision, replacing the UNKNOWN-session
                    // handling): settle it — accessibility fallback, or ask
                    // the user — or abandon this start.
                    if (kind == StreamKind.UNKNOWN && mediaProjectionController.hasConsent) {
                        // The MP-only backend settles by ASKING — hide the
                        // chip for the dialog's duration (an "Initializing…"
                        // chip over a question wrongly says no action is
                        // needed). The accessibility backend settles
                        // silently; its chip stays up.
                        val asksUser = !backend.requiresAccessibilityService
                        if (asksUser) liveFeedback?.setChipVisible(false)
                        if (!settleUnknownStreamKind(backend)) {
                            // Start abandoned — no session to narrate.
                            liveFeedback?.dispose()
                            liveFeedback = null
                            return@launch
                        }
                        if (asksUser) liveFeedback?.setChipVisible(true)
                    }
                    ensureActive()
                }
                liveFeedback?.armChipGrace()
                beginLiveCapture()
            }
            // No completion-nulling: cancel() on a completed Job is a no-op,
            // so a stale reference here is harmless by construction.
        }
    }

    /** The startLive currently suspended in its capture-consent await, if
     *  any. Cancelled by [stopLive] and by a superseding [startLive] — a
     *  stop must never be undone by a stale start resuming. Main-thread
     *  confined like the rest of the live-mode mutators. */
    private var pendingLiveStart: Job? = null

    /** This start's feedback session — warm-up, chip, gate, busy tracking
     *  ([LiveSessionFeedback]). Created by [startLive], disposed by
     *  [stopLive], explicit start-aborts, and the next start. Main-confined
     *  like the rest of the live-mode mutators. */
    private var liveFeedback: LiveSessionFeedback? = null

    /** The pre-first-cycle gate, delegated to the session — joins the
     *  engine warm-up so cycle 1 never pays the lazy model load mid-pass
     *  ([LiveSessionFeedback.awaitFirstCycleClear]). Called by the modes'
     *  [LiveCycleEngine.firstCycleGate] and FuriganaMode's gated startLoop;
     *  runs in the CALLER's scope, so a mode stop cancels it like any
     *  parked cycle. No session → nothing to wait for. */
    internal suspend fun awaitFirstCycleClear() {
        liveFeedback?.awaitFirstCycleClear()
    }

    /** Bracket a live frame grab with the one-time startup-card blink —
     *  see [LiveSessionFeedback.blinkCardForFirstGrab]. Structured so a
     *  null session runs [grab] exactly once (an elvis on the result would
     *  double-grab whenever T is nullable and the grab returns null). */
    internal suspend fun <T> withFirstGrabCardBlink(
        displayId: Int,
        source: com.playtranslate.capture.LiveCaptureSource?,
        grab: suspend () -> T,
    ): T {
        val feedback = liveFeedback ?: return grab()
        return feedback.blinkCardForFirstGrab(displayId, source, grab)
    }

    // ── Slow-OCR rescue prompt ───────────────────────────────────────────

    /** The rescue alert currently on screen, if any. Dismissed on session
     *  boundaries (stopLive, a superseding start): the offer belongs to the
     *  session whose slow pass earned it, and a programmatic dismiss does
     *  NOT record an answer — the next slow session may offer again. */
    private var slowOcrAlert: com.playtranslate.ui.OverlayAlert? = null

    private fun dismissSlowOcrPrompt() {
        slowOcrAlert?.dismiss()
        slowOcrAlert = null
    }

    /**
     * A live OCR pass has been in flight past the slow threshold
     * ([LiveSessionFeedback.OCR_SLOW_PROMPT_MS]) — offer the one-tap switch
     * to the rescue engine ([OcrModelManager.slowOcrRescue]: the ML Kit
     * floor where one exists, the Paddle FAST tier for no-floor languages
     * like RU/AR/TH), at most once per language ever (either answer is
     * remembered; the OCR picker in Settings is the standing
     * change-your-mind path). Fires MID-pass, while the user is staring at
     * the wait — the moment the offer explains itself — and renders on
     * [slowDisplayId], the display whose capture is grinding (an alert on
     * a screen the user isn't watching would pause everything behind their
     * back; review finding). Gated out when: nothing faster exists (the
     * rescue engine is already what's selected — the slowness IS the fast
     * option), or an alert is already up. The startup card yields while
     * the alert shows and returns if the user keeps their engine.
     * [livePaused] engages only once the alert PROVABLY attached
     * ([OverlayAlert.isShowing]) — a failed window add must never freeze
     * cycles under an alert nobody can see.
     */
    private fun maybeShowSlowOcrPrompt(slowDisplayId: Int) {
        if (!isLive || slowOcrAlert != null) return
        val prefs = Prefs(this)
        val id = prefs.sourceLangId
        if (prefs.slowOcrPromptAnswered(id)) return
        val selected = OcrModelManager.selectedBackend(this, id) ?: return
        val rescue = OcrModelManager.slowOcrRescue(
            available = OcrModelManager.availableBackends(this, id),
            selected = selected,
            mlKitFloor = SourceLanguageProfiles[id].mlKitFloor,
        ) ?: return

        val host = CaptureBackendResolver.active().overlayHost ?: return
        val dm = getSystemService(DisplayManager::class.java) ?: return
        // The slow pass's display, falling back to the default if it
        // disconnected between the timer firing and now.
        val display = dm.getDisplay(slowDisplayId)
            ?: dm.getDisplay(Display.DEFAULT_DISPLAY) ?: return
        val displayCtx = createDisplayContext(display)
        val wm = displayCtx.getSystemService(WindowManager::class.java) ?: return
        val themed = overlayThemedContext(displayCtx)

        liveFeedback?.setChipVisible(false)
        val alert = com.playtranslate.ui.OverlayAlert
            .Builder(themed, host, wm, display.displayId)
            .setTitle(getString(R.string.slow_ocr_prompt_title))
            // The settings path is fed from the LIVE section labels, so the
            // breadcrumb follows any rename or re-translation of those
            // screens automatically.
            .setMessage(
                getString(
                    R.string.slow_ocr_prompt_message,
                    getString(R.string.settings_title),
                    getString(R.string.settings_cell_capture_overlay),
                    getString(R.string.settings_header_ocr),
                )
            )
            .addButton(
                getString(R.string.slow_ocr_prompt_switch),
                themed.themeColor(R.attr.ptAccent),
            ) {
                slowOcrAlert = null
                prefs.setSlowOcrPromptAnswered(id)
                prefs.setOcrBackendToken(id, rescue.selectionToken)
                DetectionLog.log("slow-OCR prompt: ${id.code} switched to ${rescue.selectionToken}")
                liveFeedback?.setChipVisible(true)
                // Cancel the still-running slow pass and re-run promptly on
                // the floor engine (the registry resolves per pass).
                refreshLiveOverlay()
            }
            .addCancelButton(getString(R.string.slow_ocr_prompt_keep)) { reason ->
                slowOcrAlert = null
                // Only an explicit user dismissal (button, scrim, back) is
                // a decision; anything else may offer again next session.
                if (reason == com.playtranslate.ui.DismissReason.USER) {
                    prefs.setSlowOcrPromptAnswered(id)
                }
                liveFeedback?.setChipVisible(true)
                // The alert paused cycles ([livePaused]); kick everything
                // back to life the same way hold-release does.
                refreshLiveOverlay()
            }
            .showAsOverlay()
        if (!alert.isShowing) {
            // Window add failed — no alert on any screen, so nothing may
            // pause. The once-per-session latch already fired; this session
            // just goes unrescued rather than frozen.
            DetectionLog.log("slow-OCR prompt failed to attach (display ${display.displayId})")
            liveFeedback?.setChipVisible(true)
            return
        }
        DetectionLog.log(
            "slow-OCR prompt shown for ${id.code} on display ${display.displayId} " +
                "(selected=${selected.selectionToken})"
        )
        slowOcrAlert = alert
    }

    /**
     * The stream kind could not be MEASURED for a session holding MP
     * consent — settle it terminally, so live mode never runs on a guess
     * (2026-07-10 decision, replacing the run-on-UNKNOWN handling whose
     * pinhole-on-task-stream cell flapped deterministically):
     *
     *  - **Accessibility backend**: the MP stream was only borrowed for
     *    delivery-gated capture; with its semantics unmeasured the borrow is
     *    unsafe, and a structurally correct alternative exists. Drop the
     *    grant (full teardown — the next start re-prompts, matching the
     *    no-remembered-decline consent design) and proceed on accessibility
     *    capture: [CaptureBackendResolver.liveCaptureSourceFor] falls back
     *    via !hasConsent, and the pinhole tier gets the whole-display
     *    screenshots it was built for. Silent by design — the user gets a
     *    fully working session; a dialog would be noise.
     *  - **MediaProjection-only backend**: the stream is the session's ONLY
     *    pixel source, so ask the user which scope they picked
     *    ([askStreamKindChoice] — they know; there is no API). Cancel — or
     *    an answer arriving after the consent died
     *    ([MediaProjectionController.assertStreamKind] discards it) — means
     *    no live session this start: tear the grant down so nothing
     *    half-exists, and the next start re-prompts from the system dialog,
     *    where the user can choose again knowing we may ask.
     *
     * Returns true when live mode should proceed, false to abandon this
     * start (the grant is already torn down; callers just return). Runs
     * inside [pendingLiveStart], so stopLive / a superseding start cancels
     * the dialog await like any other suspension in the start path.
     */
    private suspend fun settleUnknownStreamKind(
        backend: com.playtranslate.capture.CaptureBackend,
    ): Boolean {
        if (backend.requiresAccessibilityService) {
            DetectionLog.log(
                "MP stream kind unresolved — dropping grant; live capture falls back to accessibility"
            )
            mediaProjectionController.destroy()
            return true
        }
        val answer = askStreamKindChoice()
        val settled = answer?.let { mediaProjectionController.assertStreamKind(it) }
            ?: StreamKind.UNKNOWN
        if (settled == StreamKind.UNKNOWN) {
            DetectionLog.log("MP stream kind prompt cancelled — live start abandoned")
            mediaProjectionController.destroy()
            return false
        }
        return true
    }

    /**
     * The stream-kind question, asked ON SCREEN — the terminal fallback for
     * an MP-only session the probe could not classify (see
     * [settleUnknownStreamKind]). Suspends until the user answers; null =
     * cancelled (button, scrim tap, or back). Cancelling the calling job
     * dismisses the alert, so a stop/supersede mid-dialog leaves no orphan
     * window behind.
     */
    private suspend fun askStreamKindChoice(): StreamKind? {
        val host = CaptureBackendResolver.active().overlayHost ?: return null
        val display = getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY) ?: return null
        val displayCtx = createDisplayContext(display)
        val wm = displayCtx.getSystemService(WindowManager::class.java) ?: return null
        val themed = overlayThemedContext(displayCtx)
        return suspendCancellableCoroutine { cont ->
            fun answer(kind: StreamKind?) {
                if (cont.isActive) cont.resume(kind)
            }
            val alert = com.playtranslate.ui.OverlayAlert
                .Builder(themed, host, wm, Display.DEFAULT_DISPLAY)
                .setTitle(getString(R.string.stream_kind_prompt_title))
                .setMessage(getString(R.string.stream_kind_prompt_message))
                .addButton(
                    getString(R.string.stream_kind_share_one_app),
                    themed.themeColor(R.attr.ptAccent),
                ) { answer(StreamKind.CLEAN) }
                .addButton(
                    getString(R.string.stream_kind_share_entire_screen),
                    themed.themeColor(R.attr.ptAccent),
                ) { answer(StreamKind.CONTAMINATED) }
                .addCancelButton(getString(R.string.btn_cancel)) { answer(null) }
                .showAsOverlay()
            cont.invokeOnCancellation {
                Handler(Looper.getMainLooper()).post { alert.dismiss() }
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
        livePanelRecord.clear()

        val prefs = Prefs(this)
        val activeIds = gameDisplayIds.ifEmpty { setOf(primaryGameDisplayId()) }
        Log.d(TAG, "startLive: activeIds=$activeIds prefs.overlayMode=${prefs.overlayMode}")
        translationLogRecorderIfInitialized?.onLiveStarted()
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
     * The implementation class a fresh live-mode instance for [flavor] on
     * [displayId] should have right now. The overlay flavors fork on the
     * MediaProjection stream kind, scoped to the DEFAULT display only: the
     * CLEAN verdict describes exactly one stream — the default display's
     * ([MediaProjectionController.projectedDisplayId] is a constant) — while
     * a secondary display's only possible frame source is accessibility
     * screenshots, which DO contain our overlays, so it keeps the legacy
     * tier unconditionally (shipped behavior). That is not a stopgap:
     * MediaProjection cannot mirror a non-default display at all, and a
     * dual-screen emulator's second-screen Presentation window is outside
     * any task mirror — accessibility capture is the only path that can see
     * those pixels. [setLiveDisplays] compares running instances against
     * this, so a stream-kind change (new consent session, consent teardown)
     * rebuilds through the same diff that handles flavor changes.
     */
    private fun desiredModeClass(flavor: OverlayFlavor, displayId: Int): Class<out LiveMode> {
        val clean = displayId == Display.DEFAULT_DISPLAY &&
            mediaProjectionController.streamKind == StreamKind.CLEAN
        return when (flavor) {
            // Panel-only: the unified loop on ANY stream kind and backend — it
            // paints nothing, so contamination is irrelevant (design record §7).
            OverlayFlavor.IN_APP_ONLY -> ReconcilerLiveMode::class.java
            // Furigana consolidated onto the legacy tier (2026-08-06): the
            // reconciler's furigana path was a half-copy — frontier release,
            // reveal pacing, and the settle hook each existed only on one
            // side — and every mechanism had to be built twice. FuriganaMode
            // carries the one full implementation on every stream kind, and
            // keys its behavior off the frame's stamped facts: raw frames a
            // CLEAN task mirror stamps overlay-free route straight to the
            // clean path — the patch/compare machinery runs only on frames
            // that actually contain rendered furigana (adversarial-review
            // hole in the original flip: unconditional patching would have
            // overwritten real clean-stream pixels with stale ref strips).
            OverlayFlavor.FURIGANA -> FuriganaMode::class.java
            OverlayFlavor.TRANSLATION ->
                if (clean) ReconcilerLiveMode::class.java
                else PinholeOverlayMode::class.java
        }
    }

    /** The stream-kind verdict CHANGED under running live modes — reset by
     *  consent teardown (a mode's cycle-start guard calls this and must
     *  return immediately after: its scope is cancelled here), or measured
     *  mid-session by a one-shot's resolve. (Live sessions no longer RUN on
     *  UNKNOWN — [settleUnknownStreamKind] — so the mid-session upgrade is
     *  a defensive net, not a steady-state path.) Re-enter the standard
     *  mutator with the current display set — its class-mismatch diff
     *  rebuilds whichever tier [desiredModeClass] now selects. No-op with
     *  no live modes (the startLive resolve path). */
    internal fun onStreamKindChanged() {
        if (liveModes.isEmpty()) return
        setLiveDisplays(liveModes.keys.toSet())
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
        // Class-mismatch subsumes flavor-mismatch (each flavor maps to a
        // distinct class) and additionally catches the TRANSLATION
        // clean-vs-pinhole fork when the stream kind changes mid-session.
        // Class-mismatch no longer subsumes flavor-mismatch: ReconcilerLiveMode
        // serves multiple flavors via its presenter, so both clauses apply.
        val toRebuild = (snapshot.keys intersect actualTarget)
            .filter {
                snapshot.getValue(it).javaClass != desiredModeClass(flavor, it) ||
                    snapshot.getValue(it).flavor != flavor
            }
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
            val desiredClass = desiredModeClass(flavor, id)
            when (flavor) {
                OverlayFlavor.IN_APP_ONLY ->
                    ReconcilerLiveMode(this, id, PanelPresenter(this, id))
                OverlayFlavor.FURIGANA -> FuriganaMode(this, id)
                OverlayFlavor.TRANSLATION ->
                    if (desiredClass == ReconcilerLiveMode::class.java)
                        ReconcilerLiveMode(this, id, TranslationPresenter(this, id))
                    else PinholeOverlayMode(this, id)
            }
        }

        // 1. Stop removed AND rebuilt modes.
        for (id in toStop + toRebuild) {
            liveModes.remove(id)?.stop()
            lastDisplayRotation.remove(id)
        }

        // 2. Populate map with new instances BEFORE the LiveData write.
        //    Each install also seeds the display listener's rotation
        //    baseline ([lastDisplayRotation]) so the FIRST rotation after
        //    install has something to differ from.
        val dm = getSystemService(DisplayManager::class.java)
        for ((id, mode) in newInstances) {
            liveModes[id] = mode
            dm?.getDisplay(id)?.rotation?.let { lastDisplayRotation[id] = it }
        }

        // 3. LiveData / foreground / icon state — only when the boolean flips.
        val wasLive = _liveModeState.value == true
        val willBeLive = liveModes.isNotEmpty()
        if (wasLive != willBeLive) {
            _liveModeState.value = willBeLive
            updateForegroundState()
            syncIconState()
            // isLive feeds CaptureLifecycle.isActive, and the ACTIVE_TILE QS
            // tile only re-renders on an explicit push.
            PlayTranslateTileService.TileSync.refresh(this)
            // Display listener tracks the empty↔non-empty transition only.
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
        lastDisplayRotation.clear()
        if (wasLive) {
            _liveModeState.value = false
            updateForegroundState()
            syncIconState()
            // Mirrors setLiveDisplays' flip block: isLive feeds
            // CaptureLifecycle.isActive, and the ACTIVE_TILE QS tile only
            // re-renders on an explicit push.
            PlayTranslateTileService.TileSync.refresh(this)
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

    /**
     * [Prefs.hideGameOverlays] changed under a RUNNING live session — the
     * in-app result header's "Show on screen" toggle flips it mid-session.
     * Re-derive the flavor and swap the live-mode instances in place through
     * [setLiveDisplays]'s flavor-mismatch diff, exactly like the split-screen
     * transition ([onMultiWindowChanged]) does: IN_APP_ONLY ⇄ overlay modes
     * rebuild, the session keeps running. No-op when not live — the next
     * [startLive] reads the pref itself. (The Settings row instead calls
     * [stopLive] on toggle; it lives on a screen that covers the game, so
     * there's no session worth preserving through it.)
     */
    fun onHideGameOverlaysChanged() {
        if (!isLive) return
        val activeIds = gameDisplayIds.ifEmpty { setOf(primaryGameDisplayId()) }
        val target = activeIds.filterNot { shouldSkipDisplay(it) }.toSet()
        Log.d(TAG, "onHideGameOverlaysChanged: hideGameOverlays=${Prefs(this).hideGameOverlays} → target=$target")
        setLiveDisplays(target)
    }

    fun stopLive() {
        // Abort any startLive suspended in its consent dialog — a stop must
        // win against a start that resumes later (see pendingLiveStart).
        pendingLiveStart?.cancel()
        pendingLiveStart = null
        // Session feedback dies with the session — every timer, the chip,
        // and outstanding pass tokens, atomically ([LiveSessionFeedback]).
        liveFeedback?.dispose()
        liveFeedback = null
        dismissSlowOcrPrompt()
        Log.i(TAG, "stopLive() called (isLive=$isLive, modes=${liveModes.keys})", Throwable("stopLive caller"))
        // Stop bypasses setLiveDisplays: we genuinely want zero live modes,
        // not "fall back to the backend's capturable default" — which is what
        // setLiveDisplays(emptySet()) now resolves to via the capturableTargets
        // shim (MediaProjection always reaches default). tearDownAllLiveModes
        // mirrors setLiveDisplays' teardown branch (stop modes, fire LiveData
        // false, unregister the display listener) without going through the
        // shim.
        tearDownAllLiveModes()
        // Session over for the recording backend: the LLM-context ring must
        // not leak into the next play session (history persists by design).
        translationLogRecorderIfInitialized?.onLiveStopped()
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

    /** Global live-cycle pause: a hold-preview in progress OR the slow-OCR
     *  rescue alert on screen. The alert must pause cycles for two reasons:
     *  it is itself a host-registered overlay, so whole-display mirrors
     *  would capture and OCR its own text; and the device it appears on is
     *  by definition choking — the decision UI should not compete with
     *  another multi-second pass. Checked at every site that pauses cycles
     *  for holds, as one derived property so the two pause sources can
     *  never drift apart. */
    val livePaused: Boolean get() = holdActive || slowOcrAlert != null

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
     * its overlay), clears [holdActive] and the icons' spinner, and refreshes
     * the live mode so it resumes from a clean state. Safe to call in the
     * pinhole-peek case where no one-shot was launched — cancel on a null job
     * is a no-op, and the spinner was never armed there.
     *
     * The spinner clear lives here rather than in each caller so that every
     * way a hold can end — lift, cancel, hotkey release — disarms it through
     * one line. The one-shot's own terminals ([OneShotManager]) clear it too,
     * so a gesture whose lift never arrives (window destroyed mid-hold) still
     * ends up disarmed.
     */
    private fun endHoldPreview() {
        oneShotManager.cancel()
        holdActive = false
        setHoldLoading(false)
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
        setHoldLoading(true)
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
        setHoldLoading(true)
        val forced = if (isLive && isFurigana) {
            OverlayMode.TRANSLATION
        } else {
            null
        }
        beginHoldPreview(forced, targets, panelTarget)
    }

    /** End a hold-to-preview gesture (in-app translate button). */
    fun holdEnd() {
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
    /** "No source-language text on $displayId in $region" message — the ONE
     *  builder for it, so every producer's status carries the tappable-language
     *  sentinels [noTextStatusMessage] adds. [region] defaults to [displayId]'s
     *  active one; callers that already resolved it (the live tiers, per empty
     *  cycle) pass it in rather than re-parsing the region list. */
    internal fun noTextMessage(
        displayId: Int,
        region: RegionEntry = activeRegionForDisplay(displayId),
    ): String = noTextStatusMessage(
        this, R.string.status_no_text, Prefs(this).sourceLangId,
        region.displayName(this),
    )

    /** The status-bar height to exclude from OCR crops of a [displayId]
     *  frame that came from [frameSource] — 0 when the source's frames
     *  contain no system UI (a task-scoped single-app stream, where cropping
     *  would eat game content), else the per-display height. Null source =
     *  the full-display assumption, the safe default. The SINGLE owner of
     *  this decision: [runOcr], [runProcessCycle], and
     *  [runCaptureOcrTranslate] all route here — a manual
     *  getStatusBarHeightForDisplay in an OCR crop is a defect
     *  (review round 6 found two). */
    internal fun statusBarHeightForFrame(
        displayId: Int,
        frameIncludesSystemUi: Boolean,
    ): Int =
        if (!frameIncludesSystemUi) 0
        else getStatusBarHeightForDisplay(displayId)

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
     *  notion of a "primary" display. Caller still owns [raw].
     *
     *  [frameIncludesSystemUi]: whether [raw] contains system UI, snapshotted
     *  AS A VALUE by the caller the moment the frame was produced (from
     *  [com.playtranslate.capture.CaptureSource.framesIncludeSystemUi] — the
     *  source owns the fact, but only the immutable value travels; rounds
     *  7+8). A task-scoped ("single app") frame has no status bar to crop,
     *  and cropping would eat game content instead. Callers without a source
     *  in hand keep the default — the full-display assumption, the safe
     *  direction.
     *
     *  [frameIncludesOwnOverlays]: whether [raw] can contain this app's own
     *  overlay windows — [com.playtranslate.capture.CapturedFrame]'s stamped
     *  fact, forwarded by the caller. True → the floating icon's rect is
     *  blacked out of the OCR input (the chevron OCRs as a ‹-class glyph);
     *  false → no fill, because on an icon-free frame the fill would eat
     *  game text under the dock spot. REQUIRED, no default: neither
     *  direction is safe to assume, so every caller — present and future —
     *  must state its frame's provenance (2026-07-16 adversarial-review
     *  finding: the furigana raw path silently kept feeding icon-bearing
     *  frames after the blackout moved into one mode). */
    internal suspend fun runOcr(
        raw: Bitmap,
        displayId: Int,
        frameIncludesSystemUi: Boolean = true,
        frameIncludesOwnOverlays: Boolean,
    ): OverlayToolkit.OcrPipelineResult? {
        val prefs = Prefs(this)
        val seedWriter: ((Bitmap, OcrManager.OcrResult?) -> Unit)? =
            if (BuildConfig.DEBUG && prefs.debugSaveOcrSeed) {
                { bitmap, result -> OcrSeedWriter.writeSeed(this, bitmap, result) }
            } else null
        val statusBarHeight = statusBarHeightForFrame(displayId, frameIncludesSystemUi)
        // Session captured at entry: a pass that outlives a stop (native
        // inference cancels lazily) finalizes into ITS session — disposed
        // and inert — never the next one's ([LiveSessionFeedback]).
        val feedback = if (isLive) liveFeedback else null
        val passToken = feedback?.beginOcrPass(displayId)
        try {
            val result = OverlayToolkit.runOcrPipeline(
                raw,
                activeRegionForDisplay(displayId),
                sourceLang,
                ocrManager,
                statusBarHeight,
                seedWriter = seedWriter,
                // The startup card may be inside this frame (whole-display
                // mirrors, a11y screenshots) — never OCR our own chrome.
                excludeRect = feedback?.ocrExclusionRect(displayId),
                // Same principle for the floating icon, keyed on the frame's
                // stamped fact. The rect is the icon's CURRENT dock — right
                // for frames OCR'd in-cycle; for a cached frame re-entering
                // OCR later it is the persisted dock position, which only
                // drifts if the user moved the icon since (accepted).
                blackoutIconRect = if (frameIncludesOwnOverlays) {
                    CaptureBackendResolver.activeOverlayUi?.getFloatingIconRect(displayId)
                } else null,
            )
            // A completed pass — a null result means "nothing to translate",
            // which is also an answer — ends the startup card's narration.
            // cardDisplayLive tells the session whether the card's display
            // is even part of this session (secondary-only captures must
            // clear the card off any display's pass). Deliberately NOT in
            // the finally: a cancelled pass answered nothing.
            feedback?.onFirstOcrComplete(
                displayId,
                cardDisplayLive = liveModes.containsKey(Display.DEFAULT_DISPLAY),
            )
            return result
        } finally {
            if (feedback != null && passToken != null) feedback.endOcrPass(passToken)
        }
    }

    /**
     * Translate OCR groups and send the result to the in-app panel.
     * Returns per-group translations (for callers that also need them for overlay building).
     * Returns null if skipped (panel not visible and forceShow=false).
     */
    internal suspend fun translateAndSendToPanel(
        ocrResult: OcrManager.OcrResult,
        screenshotPath: String?,
        displayId: Int,
        frameIncludesSystemUi: Boolean,
        frameIncludesOwnOverlays: Boolean,
        forceShow: Boolean = false,
        /** Live steady-state dedup against [livePanelRecord]: the furigana
         *  loop OFFERS every settled frame and this makes the offers
         *  idempotent. Deliberate captures (one-shot) never dedup — the
         *  user asked, the panel refreshes. */
        liveDedup: Boolean = false,
    ): List<GroupTranslation>? {
        if (!forceShow) {
            val appPanelVisible = !Prefs.isSingleScreen(this) && MainActivity.isInForeground
            if (!appPanelVisible) return null
        }
        if (liveDedup && !livePanelRecord.isNew(displayId, ocrResult.fullText, livePanelStamp())) {
            return null
        }
        // No frame here — entry time (post-OCR, pre-MT) is the closest
        // capture-boundary stamp available, and it keeps MT latency out of
        // the result's game-audio anchor.
        val capturedAtWallMs = System.currentTimeMillis()
        val perGroup = translateGroupsSeparately(ocrResult.groups.map { it.text })
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
                ocrProvenance      = ocrProvenanceFor(
                    ocrResult, displayId, activeRegionForDisplay(displayId),
                    Prefs(this).sourceLangId, frameIncludesSystemUi,
                    frameIncludesOwnOverlays,
                ),
                langContext        = Prefs(this).langContext(),
                createdAtMs        = capturedAtWallMs,
            )
        )
        // COMMIT ON DELIVERY: every caller of THIS function delivers what
        // the capture display currently shows (live furigana offers,
        // one-shot holds), so the record keeps mirroring the screen.
        livePanelRecord.committed(displayId, ocrResult.fullText, livePanelStamp())
        return perGroup
    }

    /** Whether the in-app panel is on screen to receive live results —
     *  the shared gate for every live tier's panel sync. */
    internal fun appPanelVisible(): Boolean =
        !Prefs.isSingleScreen(this) && MainActivity.isInForeground

    /** What the panel currently shows, owned HERE at the delivery layer —
     *  see [LivePanelRecord] for the rules and the bug history. */
    private val livePanelRecord = LivePanelRecord()

    /** Language key for [livePanelRecord]: same text under a new
     *  source/target pair is new content. */
    private fun livePanelStamp(): String {
        val prefs = Prefs(this)
        return "${prefs.sourceLangId}>${prefs.targetLang}"
    }

    /** Cheap pre-flight for the live furigana loop: would offering [text]
     *  from [displayId] reach the panel as new content? Lets the mode skip
     *  the screenshot JPEG on quiet cycles where the offer would be dropped
     *  anyway. The authoritative checks run again inside
     *  [translateAndSendToPanel]. */
    internal fun livePanelWouldAccept(displayId: Int, text: String): Boolean =
        appPanelVisible() && livePanelRecord.isNew(displayId, text, livePanelStamp())

    /** Emit a live tier's displayed state to the in-app panel — the ONE
     *  emission shape shared by the pinhole tier and the reconciler
     *  presenters (build [texts] via [OverlayToolkit.panelTexts]; gating
     *  and ordering policy stay with the caller, where they genuinely
     *  differ). [screenshotPath] is a synchronous JPEG write — callers
     *  invoke it (lazily) only on paths that actually emit. */
    internal fun emitPanelResult(
        texts: OverlayToolkit.PanelTexts,
        screenshotPath: String?,
        ocrProvenance: OcrProvenance? = null,
        backendDisplayName: String? = null,
    ) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        emitResult(TranslationResult(
            originalText = texts.originalText,
            segments = texts.segments,
            translatedText = texts.translatedText,
            timestamp = timestamp,
            screenshotPath = screenshotPath,
            backendDisplayName = backendDisplayName,
            ocrProvenance = ocrProvenance,
            langContext = Prefs(this).langContext(),
        ))
    }

    /** Called by a per-display LiveMode when its OCR pass finds no source-
     *  language text on [displayId]: clears that display's overlay pair
     *  and notifies the in-app panel. The panel emit is intentionally
     *  global — there's a single panel and a single result/no-text state
     *  for it. The overlay teardown is per-display so a no-text outcome
     *  on display B doesn't take display A's still-valid overlay with it. */
    internal fun handleNoTextDetected(displayId: Int) {
        CaptureBackendResolver.activeOverlayUi?.hideTranslationOverlayForDisplay(displayId)
        emitLiveNoText(displayId)
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
        authoritativeBounds: Boolean = false,
    ) {
        // livePaused, not just holdActive: no live overlay may render under
        // the rescue alert either — the backstop that keeps ANY live mode,
        // present or future, from painting beneath the modal. force remains
        // the deliberate escape hatch (hold-preview's own render).
        if (!force && livePaused) { Log.w("FuriganaDbg", "showLiveOverlay BLOCKED: livePaused=true"); return }
        val ui = CaptureBackendResolver.activeOverlayUi
        if (ui == null) { Log.w("FuriganaDbg", "showLiveOverlay BLOCKED: overlayUi=null"); return }
        val dm = getSystemService(DisplayManager::class.java)
        val display = dm.getDisplay(displayId)
        if (display == null) { Log.w("FuriganaDbg", "showLiveOverlay BLOCKED: display=null for id=$displayId"); return }
        Log.d("FuriganaDbg", "showLiveOverlay: ${boxes.size} boxes, crop=($cropLeft,$cropTop), screen=${screenshotW}x$screenshotH on display $displayId")
        val prefs = Prefs(this)
        val verticalTextTarget = targetSupportsVerticalText(prefs.targetLang)
        ui.showTranslationOverlay(
            display, boxes, cropLeft, cropTop, screenshotW, screenshotH,
            pinholeMode, oneShot, verticalTextTarget,
            verticalTextStackable = stackableTargetScript(prefs.targetLang),
            verticalGrowEnabled = prefs.verticalTextGrow,
            authoritativeBounds = authoritativeBounds,
        )
    }

    /** Capture a clean screenshot via the active capture backend. The
     *  frame carries its capture-time facts ([CapturedFrame]). */
    internal suspend fun captureScreen(displayId: Int): com.playtranslate.capture.CapturedFrame? =
        CaptureBackendResolver.active().captureSource?.requestClean(displayId)

    /** Persist [raw] to the screenshot cache via the active capture backend. */
    internal fun captureSaveToCache(raw: Bitmap, displayId: Int): String? =
        CaptureBackendResolver.active().captureSource?.saveToCache(raw, displayId)

    /** Build the [OcrProvenance] for a freshly-OCR'd result. [sourceLangId] is the
     *  EXACT source language (variant included) the caller snapshotted at capture
     *  time — passed in, NOT derived from the OCR code, because OCR is variant-
     *  agnostic (ZH and ZH_HANT both OCR as "zh"), so reconstructing from the code
     *  would collapse Traditional → Simplified and break the picker's backend key +
     *  `sourceIsTraditional` on re-OCR. [engineBackend] supplies the label + selection
     *  token; [displayId] + [region] re-crop the identical rectangle on a re-OCR.
     *  Null when the result carries no engine identity (the empty engine) — callers
     *  then leave [TranslationResult.ocrProvenance] null, suppressing the source OCR
     *  row and re-OCR. */
    /** Provenance for a panel-emitted live result — the panel presenter's
     *  path to the re-OCR gear (region + source language resolved the same
     *  way the one-shot pipeline resolves them). */
    internal fun panelOcrProvenance(
        ocrResult: OcrManager.OcrResult,
        displayId: Int,
        frameIncludesSystemUi: Boolean,
        frameIncludesOwnOverlays: Boolean,
    ): OcrProvenance? = ocrProvenanceFor(
        ocrResult, displayId, activeRegionForDisplay(displayId),
        Prefs(this).sourceLangId, frameIncludesSystemUi, frameIncludesOwnOverlays,
    )

    private fun ocrProvenanceFor(
        ocrResult: OcrManager.OcrResult,
        displayId: Int,
        region: RegionEntry,
        sourceLangId: SourceLangId,
        // The captured frame's stamped facts, when the caller has the frame
        // in hand — they persist with the result so a re-OCR re-crops the
        // same pixels and re-blacks our own chrome out of raw frames. Null
        // (panel-emit paths without a frame) reads as legacy — full-display,
        // clean — downstream.
        frameIncludesSystemUi: Boolean? = null,
        frameIncludesOwnOverlays: Boolean? = null,
    ): OcrProvenance? {
        val backend = ocrResult.engineBackend ?: return null
        // MangaOCR is a refinement layer over the base engine, not a selectable
        // backend: extend the display label only ("Scanned by ML Kit + MangaOCR",
        // proper nouns untranslated like ocrLabel) and keep the selection token on
        // the base engine so the re-OCR gear still keys the picker correctly.
        val label =
            if (ocrResult.mangaOcrUsed) "${backend.ocrLabel(this)} + MangaOCR" else backend.ocrLabel(this)
        return OcrProvenance(
            label, backend.selectionToken, displayId, sourceLangId, region,
            frameIncludesSystemUi = frameIncludesSystemUi,
            frameIncludesOwnOverlays = frameIncludesOwnOverlays,
        )
    }

    companion object {

        /** Process-scoped reference for in-process callers (e.g. DragLookupController). */
        @Volatile
        var instance: CaptureService? = null
            private set

        /** The ONE write path for [Prefs.recordGameAudio]: pref write +
         *  recorder reconcile + the session's consent lifecycle at both
         *  ends — on disable the audio-only session release, on enable the
         *  ask for the consent the recorder rides on. Session lifecycle
         *  rides the explicit user transition HERE, never the
         *  [reconcileGameAudio] push-point — that push-point fires from
         *  every seam, including consent delivery itself and the consent
         *  activity's own resume/pause, where a release would burn a
         *  just-granted token and an ask would re-prompt. At this seam no
         *  consent can be mid-delivery; an explicit feature-off is the user
         *  saying the session's audio client is gone for good, and an
         *  explicit feature-on is the user asking for audio NOW — the
         *  recorder never prompts, so without the ask here it would sit
         *  unarmed until a fresh icon placement or a live start. The ask is
         *  gated on [GameAudioGate.wouldRunGivenConsent]: consent must be
         *  the one gate left, so enabling the feature never prompts for a
         *  session that isn't running (on the MediaProjection backend a
         *  grant there would double as Turn On). No service running ⇒
         *  nothing to reconcile or release; the ask still goes out through
         *  [launchAudioCaptureConsent]'s cold-start path when the gate is
         *  open. */
        fun setRecordGameAudio(ctx: Context, enabled: Boolean) {
            Prefs(ctx).recordGameAudio = enabled
            val svc = instance
            svc?.reconcileGameAudio()
            if (!enabled) {
                svc?.releaseAudioOnlyProjection()
                return
            }
            if (GameAudioGate.wouldRunGivenConsent(ctx, svc?.mediaProjectionControllerIfInitialized)) {
                launchAudioCaptureConsent(ctx)
            }
        }

        /** Ask for the MediaProjection consent game audio rides on — the one
         *  audio verb, shared by the floating menu's "Record audio" bar and
         *  [setRecordGameAudio]'s feature-on. (Settings' repair row keeps its
         *  own awaiting variant, which refreshes its row when the dialog
         *  settles; the icon-placement prompt goes through the instance's
         *  [requestAudioCaptureConsent] directly — see there.)
         *
         *  USER-INITIATED CALLERS ONLY. With no service alive this cold-starts
         *  one through [ACTION_MP_ACTIVATE], the tile's FGS-safe path (on the
         *  accessibility backend activateMediaProjection degrades to exactly
         *  ensureConsent + reconcile, since the a11y branch of
         *  [com.playtranslate.capture.MediaProjectionController.onConsentResult]
         *  never writes MP lifecycle state) — and a foreground-service start
         *  is legal only from a user-visible or user-interaction context: API
         *  31+ throws ForegroundServiceStartNotAllowedException otherwise, the
         *  2026-07-15 field-crash class documented in [updateForegroundState].
         *  The feature-on toggle runs inside an activity (exempt). The menu
         *  bar is a tap on our own overlay with no activity behind it, so its
         *  start rides SYSTEM_ALERT_WINDOW / accessibility exemptions that
         *  vary by device — the platform's refusal is caught and logged, and
         *  the icon's outlined arrow plus Settings' repair row remain the
         *  recovery. A PASSIVE trigger must never reach this: the placement
         *  prompt parks itself in OverlayUiController and lets the service's
         *  own onCreate fire it (Codex adversarial finding 2026-09-05). */
        fun launchAudioCaptureConsent(ctx: Context) {
            val svc = instance
            if (svc != null) {
                svc.requestAudioCaptureConsent()
                return
            }
            try {
                ContextCompat.startForegroundService(
                    ctx.applicationContext,
                    Intent(ctx.applicationContext, CaptureService::class.java)
                        .setAction(ACTION_MP_ACTIVATE),
                )
            } catch (e: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException (API 31+) and its
                // ServiceStartNotAllowedException parent are
                // IllegalStateExceptions; caught by the parent so the
                // reference compiles on every minSdk.
                Log.w(TAG, "audio consent cold start refused (background FGS start): ${e.javaClass.simpleName}")
            }
        }

        /** Action for an [onStartCommand] intent meaning "obtain MediaProjection
         *  consent and bring the controls up" — sent by the Quick Settings tile,
         *  which can't assume the service is already alive. */
        const val ACTION_MP_ACTIVATE = "com.playtranslate.action.MP_ACTIVATE"

        /** Debug-build-only broadcast that dumps one raw MediaProjection
         *  frame — see [mpProbeReceiver]. */
        const val ACTION_DEBUG_MP_PROBE = "com.playtranslate.debug.MP_PROBE"

        /** Empty-id, full-screen region used as the initial saved/active value
         *  before [configureSaved] runs and as the defensive fallback in
         *  [activeRegion]. Centralized so the literal isn't duplicated. */
        val DEFAULT_REGION = RegionEntry("", 0f, 1f)

        /** Downscale factor for the one-shot color-sampling reference — the same
         *  1/4 the press-and-hold path uses ([TranslationOneShotProcessor]). */
        const val ONE_SHOT_COLOR_SCALE = 4
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
        val ocrResult: OcrManager.OcrResult? = null,
        /** In-place overlay boxes for the capture panel's "show on screen"
         *  presentation; null when nothing paintable. */
        val overlayData: OneShotOverlayData? = null,
    )

    /** Outcome of [runCaptureOcrTranslate]. Callers translate to their
     *  own surface (one-shot writes a [CaptureState] on the session;
     *  live mode emits to its own flows). The pipeline doesn't
     *  side-effect any service-global flow on its own anymore. */
    internal sealed class PipelineOutcome {
        data class Success(val pipeline: PipelineResult) : PipelineOutcome()
        data class NoText(val ocrProvenance: OcrProvenance?, val screenshotPath: String?) : PipelineOutcome()
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
        onOcrReady: ((
            originalText: String,
            segments: List<TextSegment>,
            ocrProvenance: OcrProvenance?,
            overlayData: OneShotOverlayData?,
        ) -> Unit)? = null,
        allowDeferTranslation: Boolean = false,
    ): PipelineOutcome {
        // The frame carries its own capture-time facts (CapturedFrame) —
        // nothing is re-derived from mutable state downstream.
        val frame = captureScreen(displayId)
            ?: return PipelineOutcome.Failed(
                "Screenshot failed for display $displayId. Try a different display in Settings."
            )
        val raw: Bitmap = frame.bitmap
        val frameIncludesUi = frame.includesSystemUi
        // Shutter moment in epoch ms — see runProcessCycle's twin: the
        // result's createdAtMs anchors game audio and must predate MT.
        val capturedAtWallMs = System.currentTimeMillis() -
            (android.os.SystemClock.uptimeMillis() - frame.capturedAtMs)
        onScreenshotTaken?.invoke()
        var bitmap: Bitmap? = raw
        var colorRef: Bitmap? = null
        try {
            val screenshotPath = captureSaveToCache(raw, displayId)

            val region = activeRegionForDisplay(displayId)
            val statusBarHeight = statusBarHeightForFrame(displayId, frameIncludesUi)
            val top    = maxOf((raw.height * region.top).toInt(), statusBarHeight)
            val left   = (raw.width  * region.left).toInt()
            val bottom = (raw.height * region.bottom).toInt()
            val right  = (raw.width  * region.right).toInt()
            val rawW = raw.width
            val rawH = raw.height
            // Snapshot the color-sampling reference now — cropBitmap recycles raw.
            colorRef = oneShotColorRef(raw)
            bitmap = cropBitmap(raw, top, bottom, left, right)

            // Black the floating icon out of frames stamped as possibly
            // containing our own overlays. This path's frames come from
            // requestClean (stamp false, no-op today) — keyed on the stamp,
            // not the path, so a routing change can't silently reopen it.
            // In-place only when cropBitmap produced a fresh copy; a no-op
            // crop leaves `bitmap === raw`, and the frame must never be
            // drawn into.
            if (frame.includesOwnOverlays) {
                val iconRect =
                    CaptureBackendResolver.activeOverlayUi?.getFloatingIconRect(displayId)
                if (iconRect != null) {
                    val blacked = OverlayToolkit.blackoutFloatingIcon(
                        bitmap, left, top, iconRect,
                        allowInPlace = bitmap !== raw,
                    )
                    if (blacked !== bitmap) {
                        bitmap.recycle()
                        bitmap = blacked
                    }
                }
            }
            // Snapshot the exact source language (variant included) once for provenance.
            val srcId = Prefs(this@CaptureService).sourceLangId
            val ocrResult = ocrManager.recognise(bitmap, sourceLang, screenshotWidth = raw.width)
            if (BuildConfig.DEBUG && Prefs(this@CaptureService).debugSaveOcrSeed) {
                OcrSeedWriter.writeSeed(this@CaptureService, bitmap, ocrResult)
            }

            if (ocrResult == null) {
                return PipelineOutcome.NoText(
                    noTextProvenanceFor(
                        displayId, region, srcId, frameIncludesUi, frame.includesOwnOverlays,
                    ),
                    screenshotPath,
                )
            }

            // OCR is in — surface the source now so the page can reveal before the
            // (slower) translation runs. The skeleton boxes ride along for the
            // chips-preferred collapse-with-placeholders flow.
            val skeletonData = buildOneShotOverlayData(ocrResult, colorRef, left, top, rawW, rawH)
            onOcrReady?.invoke(
                ocrResult.fullText, ocrResult.segments,
                ocrProvenanceFor(
                    ocrResult, displayId, region, srcId, frameIncludesUi,
                    frame.includesOwnOverlays,
                ),
                skeletonData,
            )

            // Recording pair captured BEFORE the translate call — a
            // mid-flight language change must not relabel these rows.
            val recordSrc = SourceLanguageProfiles[srcId].translationCode
            val recordTgt = Prefs(this@CaptureService).targetLang
            // Deferred path: the translation section is hidden and the caller
            // opted in, so no consumer needs MT right now — skip the backend
            // batch entirely. Rows record translation-less (they attach later
            // via onCaptureTranslated) and Done carries a PendingTranslation
            // with everything the completion needs. The free source==target
            // bypass never defers.
            val deferTranslation = allowDeferTranslation &&
                Prefs(this@CaptureService).hideTranslationSection &&
                recordSrc != recordTgt
            val perGroup = if (deferTranslation) null
                else translateGroupsSeparately(ocrResult.groups.map { it.text })
            // Deliberate capture → recording backend, one capture session
            // per invocation (per-group pairs with rects; the recorder
            // no-ops unless a log feature is enabled). Deferred rows record
            // with a null translation; the normal path keeps skipping rows
            // whose translation came back blank.
            // Logging eligibility snapshotted WITH the recording — deferral
            // splits translate+record across time, and what the completion
            // may write later is what the user had opted into NOW, not at
            // reveal time (an opted-out capture must stay unrecorded even if
            // the pref is enabled before the reveal).
            val historyEligible = Prefs(this@CaptureService).translationHistoryEnabled
            val contextEligible = Prefs(this@CaptureService).llmContextEnabled
            val token = translationLogRecorder.beginCaptureSession()
            ocrResult.groups.forEachIndexed { i, g ->
                val tr = perGroup?.getOrNull(i)?.text.orEmpty()
                if (deferTranslation || tr.isNotEmpty()) translationLogRecorder.onCaptureShown(
                    token, g.text, tr.takeIf { it.isNotEmpty() }, g.bounds, recordSrc, recordTgt,
                    com.playtranslate.translationlog.TranslationHistoryStore.PROVENANCE_ONE_SHOT,
                    perGroup?.getOrNull(i)?.backendDisplayName,
                    captureImage = screenshotPath?.let {
                        com.playtranslate.translationlog.HistoryImageStore.Source.FromPath(it)
                    },
                )
            }
            val translated = perGroup?.joinToString("\n\n") { it.text }.orEmpty()
            val note = perGroup?.mapNotNull { it.note }?.firstOrNull()
            val backendDisplayName = perGroup?.mapNotNull { it.backendDisplayName }?.firstOrNull()
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
                        ocrProvenance      = ocrProvenanceFor(
                            ocrResult, displayId, region, srcId, frameIncludesUi,
                            frame.includesOwnOverlays,
                        ),
                        pendingTranslation = if (deferTranslation) PendingTranslation(
                            groupTexts = ocrResult.groups.map { it.text },
                            sourceLangId = srcId,
                            targetLang = recordTgt,
                            isCapture = true,
                            historySessionId = token.sessionId.takeIf { historyEligible },
                            historyEligible = historyEligible,
                            contextEligible = contextEligible,
                        ) else null,
                        langContext        = Prefs(this@CaptureService).langContext(srcId),
                        createdAtMs        = capturedAtWallMs,
                    ),
                    groupBounds = ocrResult.groups.map { it.bounds },
                    groupTranslations = perGroup?.map { it.text } ?: List(ocrResult.groups.size) { "" },
                    cropLeft = left, cropTop = top,
                    screenshotW = rawW, screenshotH = rawH,
                    ocrResult = ocrResult,
                    // Deferred: keep the SKELETONS — the deferred completion
                    // fills them when the translation finally runs.
                    overlayData = if (perGroup != null)
                        fillOneShotOverlayData(skeletonData, perGroup.map { it.text })
                    else skeletonData,
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
            colorRef?.recycle()
            bitmap?.let { if (!it.isRecycled) it.recycle() }
        }
    }

    /** One-shot capture: walks [state] through Capturing → final
     *  Done/NoText/Failed. Activities own the [state] flow via the
     *  [CaptureSession] returned from [captureOnce]. */
    private suspend fun runCaptureCycle(
        displayId: Int,
        state: MutableStateFlow<CaptureState>,
        allowDeferTranslation: Boolean = false,
    ) {
        if (!isConfigured) {
            state.value = CaptureState.Failed("Not configured — tap Translate to set up")
            return
        }
        state.value = CaptureState.InProgress(getString(R.string.status_capturing))
        val outcome = runCaptureOcrTranslate(
            displayId = displayId,
            onScreenshotTaken = { flashRegionIndicator(displayId) },
            // Reveal the source as soon as OCR finishes; translation lands on Done.
            onOcrReady = { originalText, segments, ocrProvenance, overlayData ->
                state.value =
                    CaptureState.Translating(originalText, segments, ocrProvenance, overlayData)
            },
            allowDeferTranslation = allowDeferTranslation,
        )
        state.value = when (outcome) {
            is PipelineOutcome.Success ->
                CaptureState.Done(outcome.pipeline.result, outcome.pipeline.overlayData)
            is PipelineOutcome.NoText ->
                CaptureState.NoText(noTextMessage(displayId), outcome.ocrProvenance, outcome.screenshotPath)
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
     *  if not cached for the current pair; hits carry the producing backend's
     *  display name so cache-filled boxes stay attributable. */
    internal fun getCachedTranslation(sourceText: String): GroupTranslation? {
        val target = snapshotTranslationTarget()
        return translationCache[cacheKey(sourceText, target)]?.let { (text, backend) ->
            GroupTranslation(target.localize(text), note = null, backendDisplayName = backend)
        }
    }

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
    internal suspend fun translateGroupsSeparately(
        groupTexts: List<String>,
        sourceOverride: SourceLangId? = null,
    ): List<GroupTranslation> {
        val target = snapshotTranslationTarget(sourceOverride)

        // OCR-only bypass: when source and target language are the same,
        // skip translation entirely — OCR output is the final result.
        // This handles all paths: one-shot hold, live mode, and in-app panel.
        // Clear degraded state too — bypass means we aren't going through a
        // backend, so any stale "Offline"/"degraded" badge from a prior
        // fallback should drop.
        if (target.source == target.target) {
            setDegraded(false)
            // Edge case: Chinese source → Chinese-Traditional target. The OCR
            // text is the result, but a Simplified (ZH) source still needs
            // s2t/s2tw/s2hk applied; an already-Traditional (ZH_HANT) source is
            // a no-op (target.localize handles both via sourceIsTraditional).
            return groupTexts.map { GroupTranslation(target.localize(it), null, null) }
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
            val texts = uncached.map { it.value.text }

            // Short-text offline routing — OFF BY DEFAULT, debug-row gated
            // (Prefs.debugShortTextRouting; device-pass verdict 2026-09-02:
            // 2★ output too poor even on ordinary short phrases). When on:
            // texts with ≤2 content words (menu items, HUD labels, one-word
            // lines) go to the fast offline tier instead of spending online
            // quota; everything else still travels as ONE online batch.
            // Bypassed entirely when the backend that
            // would serve is a context-carrying batching LLM — shorts then
            // ride its batch so dialogue-choice one-worders keep the context
            // ring (user decision; the cost is one LLM request on all-short
            // pages). NOTE: translateOnce (deliberate re-translate / edit /
            // select) and CameraTranslator stay online-only by design. Short
            // results — including deliberately-routed ML Kit ones (user
            // decision: cached, no degraded note) — enter the cache as
            // first-class entries. The cache's routing identity folds in
            // llmContextEnabled + bergamotEnabled (TranslationCache
            // .routingIdentity), so flipping either toggle clears cached
            // shorts and routing changes apply immediately; the residual pin
            // (a pack/model INSTALL mid-session, identity unchanged) is
            // accepted — bounded by the 500-entry LRU and process restart.
            // The whole routing decision runs off-main: this method runs on
            // the Main-dispatched serviceScope (the cache's thread-safety
            // invariant), while the bypass check and route resolve do
            // filesystem probes (Bergamot's isUsable) and possibly a GMS
            // round-trip (ML Kit model presence), and a first JA/ZH/TH
            // classification pays lazy segmenter init (Sudachi mmap / HanLP
            // models / Thai trie). Resolve happens only AFTER classification
            // found shorts, so all-sentence pages pay nothing beyond the
            // classifier. No cache access crosses the dispatcher — only the
            // (route, shorts) pair comes back; null = today's single batch.
            val routing: Pair<ShortTextOfflineRoute, List<Boolean>>? =
                if (!Prefs(this).debugShortTextRouting) null
                else withContext(Dispatchers.Default) {
                    val llmBypass = shouldBypassForLlm(
                        TranslationBackendRegistry.preferredBackend(target.source, target.target),
                        Prefs(this@CaptureService).llmContextEnabled,
                    )
                    if (llmBypass) return@withContext null
                    val profile = SourceLanguageProfiles[target.sourceId]
                    val counter =
                        ShortTextTokenCounters.forLanguage(this@CaptureService, target.sourceId)
                    val shorts = texts.map { isShortText(it, profile, counter) }
                    if (shorts.none { it }) return@withContext null
                    ShortTextOfflineRoute.resolve(target.source, target.target)
                        ?.let { it to shorts }
                }

            val dispatch = if (routing == null) {
                translateBatch(texts, target).let { PartitionedResult(it, it) }
            } else {
                val (route, shorts) = routing
                dispatchPartitioned(
                    texts, shorts,
                    offline = { t ->
                        withContext(Dispatchers.Default) {
                            route.translateOrNull(t, target.source, target.target)
                        }?.let {
                            TranslateOutcome(it, null, DegradedWarningKind.None, null, route.displayName)
                        }
                    },
                    online = { batch -> translateBatch(batch, target) },
                )
            }
            val outcomes = dispatch.all

            // Set the icon/menu state ONCE from the worst outcome the ONLINE
            // waterfall produced. Each child's `translate` no longer touches
            // the global state, so a clean group that happens to finish after
            // a displaced sibling can't clear the warning. Deliberately-offline
            // short results are excluded: their kind is None by construction,
            // and letting an all-short page aggregate to None would CLEAR the
            // "Offline" badge while the online tier is still cooling down
            // (flapping between all-short and mixed pages). When no online
            // call ran at all (fully-cached batch, or an all-short page), the
            // state is left untouched — the same rule the fully-cached early
            // return has always followed.
            dispatch.fromOnline.maxByOrNull { it.kind.severity() }?.let { setDegraded(it.kind) }

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
            // Convert to the chosen Traditional variant AFTER the cache read, so
            // the shared "zh" cache keeps storing Simplified for every variant.
            .map { it.copy(text = target.localize(it.text)) }
    }

    /** Downscaled copy of [raw] for [OverlayToolkit.sampleGroupColors], at
     *  1/[ONE_SHOT_COLOR_SCALE]. Taken BEFORE [cropBitmap] — which recycles the
     *  raw frame when it crops — so the one-shot cycles can still color-sample
     *  overlay boxes after OCR + translation. Best-effort: null on ANY
     *  allocation failure, including [OutOfMemoryError] (an Error, which the
     *  cycles' `catch (Exception)` arms would NOT contain) — the on-screen
     *  presentation is optional and must never fail a capture that OCR +
     *  translation could complete. Caller recycles. */
    private fun oneShotColorRef(raw: Bitmap): Bitmap? = try {
        Bitmap.createScaledBitmap(
            raw,
            (raw.width / ONE_SHOT_COLOR_SCALE).coerceAtLeast(1),
            (raw.height / ONE_SHOT_COLOR_SCALE).coerceAtLeast(1),
            false,
        )
    } catch (e: Exception) {
        Log.w(TAG, "one-shot color ref failed — capture continues without overlay boxes", e)
        null
    } catch (e: OutOfMemoryError) {
        Log.w(TAG, "one-shot color ref OOM — capture continues without overlay boxes")
        null
    }

    /** Build the SKELETON in-place overlay boxes for a one-shot capture — the same
     *  color-matched placeholders the press-and-hold preview paints while its
     *  translation runs ([TranslationOneShotProcessor]): empty text (the overlay
     *  view renders pulsing skeleton lines for those), sampled colors, OCR bounds.
     *  One box per OCR group, index-aligned, so [fillOneShotOverlayData] can zip
     *  the per-group translations in later. Null when there are no groups or no
     *  color reference (its allocation is best-effort — see [oneShotColorRef]). */
    private fun buildOneShotOverlayData(
        ocrResult: OcrManager.OcrResult,
        colorRef: Bitmap?,
        cropLeft: Int, cropTop: Int,
        screenshotW: Int, screenshotH: Int,
    ): OneShotOverlayData? {
        if (colorRef == null || ocrResult.groups.isEmpty()) return null
        val colors = OverlayToolkit.sampleGroupColors(
            colorRef, ocrResult.groups.map { it.bounds }, cropLeft, cropTop, ONE_SHOT_COLOR_SCALE,
        )
        val boxes = ocrResult.groups.mapIndexed { idx, g ->
            val (bgColor, textColor) = colors.getOrElse(idx) {
                Pair(Color.argb(200, 0, 0, 0), Color.WHITE)
            }
            com.playtranslate.ui.TextBox(
                "", g.bounds, bgColor, textColor, g.lines.size,
                orientation = g.orientation, alignment = g.alignment,
                angleDeg = g.angleDeg, orientedWidth = g.orientedWidth, orientedHeight = g.orientedHeight,
            )
        }
        return OneShotOverlayData(boxes, cropLeft, cropTop, screenshotW, screenshotH)
    }

    /** On-demand translation for a single text string (used by edit overlay, drag-sentence, etc.). */
    internal suspend fun translateOnce(text: String): GroupTranslation {
        val target = snapshotTranslationTarget()
        val outcome = translate(text, target)
        setDegraded(outcome.kind)
        return GroupTranslation(target.localize(outcome.text), outcome.note, outcome.backendDisplayName)
    }

    /**
     * Run the machine translation a deferred capture skipped ([PendingTranslation])
     * and attach the results to the null-translation History rows recorded at
     * capture time. Called from a surface's Main-scope the moment the translation
     * is needed (eye reveal, bind-while-visible, "show on screen").
     *
     * The RECORD pair is snapshotted fresh here, not taken from the pending:
     * [translateGroupsSeparately] translates into the CURRENT target, so rows
     * must be labelled with that pair — a target change between capture and
     * reveal must not write a new-target translation under the old label.
     * The source stays pinned to the language the OCR ran as.
     *
     * LOGGING ELIGIBILITY, by contrast, is the pending's capture-time
     * snapshot: History attaches only to the session recorded then
     * (attach-only — never inserts), and the context ring is fed only when
     * the user was opted in at capture time AND still is. The attach plan
     * dedupes duplicate group texts (recorded as one row by the token's
     * seen-set) so each row is offered its translation once.
     */
    internal suspend fun completeDeferredTranslation(
        pending: PendingTranslation,
    ): List<GroupTranslation> {
        val recordSrc = SourceLanguageProfiles[pending.sourceLangId].translationCode
        val perGroup = translateGroupsSeparately(pending.groupTexts, pending.sourceLangId)
        // Pair staleness: the null History rows were recorded under the
        // CAPTURE-time target. If the target changed before the reveal, the
        // translation above is a different pair — and cross-pair translations
        // are display-only, never attached (the deliberate flow's rule; see
        // onHistoryEntryTranslated's KDoc). The capture rows deliberately
        // stay translation-less, and attach-only means nothing records fresh
        // under the new pair either. (The surfaces' langContext staleness
        // sweeps clear most deferred results on a language change before a
        // reveal can even happen — this is the boundary's own guard.)
        val recordTgt = Prefs(this@CaptureService).targetLang
        if (recordTgt != pending.targetLang) return perGroup
        if (pending.historySessionId != null || pending.contextEligible) {
            deferredAttachPlan(pending.groupTexts, perGroup, recordSrc).forEach { (source, tr, backend) ->
                translationLogRecorder.onCaptureTranslated(
                    pending.historySessionId, source, tr, recordSrc, recordTgt,
                    pending.contextEligible, backend,
                )
            }
        }
        return perGroup
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
        return result.toOutcome(target.source, target.target)
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
        return results.map { it.toOutcome(target.source, target.target) }
    }

    /** Map a [WaterfallResult] to a [TranslateOutcome] with the per-result
     *  kind + inline-note logic. Used by both the single-text [translate]
     *  and the batched [translateBatch] paths so the degraded-state
     *  semantics stay identical between them. */
    private fun com.playtranslate.translation.WaterfallResult.toOutcome(
        source: String,
        target: String,
    ): TranslateOutcome {
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
        // The inline note adds more detail than the icon needs — when the
        // cause is "Offline", distinguish network-not-present from the
        // active-cooldown wordings (transient / quota / account, selected
        // by the typed CooldownCause inside DegradedMessages) and the
        // generic service-unavailable. The floating icon's pill makes the
        // same cooldown query at render time via [currentDegradedCooldown].
        // Network is checked FIRST: with no internet, a cooldown still
        // ticking on some backend is not what the user needs to fix.
        val note = when (kind) {
            DegradedWarningKind.None -> null
            DegradedWarningKind.LowMemory ->
                getString(R.string.note_low_memory_fallback)
            DegradedWarningKind.Offline ->
                if (!isNetworkAvailable()) getString(R.string.note_mlkit_no_internet)
                else com.playtranslate.ui.DegradedMessages.onlineFailureNote(
                    this@CaptureService,
                    TranslationBackendRegistry.earliestCooldownEnd(source, target),
                )
        }
        return TranslateOutcome(this.text, note, kind, this.displacedLlmId, this.backend.displayName)
    }

    /**
     * Active cooldown for the floating-icon pill while the degradation
     * kind is [DegradedWarningKind.Offline]: the soonest-ending cooldown
     * (with its typed cause) among backends usable for the CURRENT
     * language pair, or null when the degradation isn't cooldown-driven
     * (genuinely offline, or a fresh failure nothing has cooled down
     * yet). Null with no network — same ordering rule as the note above.
     */
    fun currentDegradedCooldown(): com.playtranslate.translation.ActiveCooldown? {
        if (!isNetworkAvailable()) return null
        val target = snapshotTranslationTarget()
        return TranslationBackendRegistry.earliestCooldownEnd(target.source, target.target)
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
        //
        // mediaProjectionActivated: foreground status follows the SESSION, not
        // the transient window state. onProjectionLost's hideAll → reconcile
        // sweep re-enters this method through the per-icon hide hook at an
        // instant where icons, live, and consent are all momentarily gone;
        // demoting there turns the reinstall's startForeground milliseconds
        // later into a background FGS start, which is fatal on API 31+ when
        // the revoke came from the status-bar chip with the app backgrounded
        // (ForegroundServiceStartNotAllowedException — field crashes
        // 2026-07-15). Foreground is a ratchet the app can always release but
        // can only re-acquire while exempt, so never release it mid-session.
        // Turn Off ([CaptureLifecycle.deactivate]) and backend swap
        // ([CaptureBackendResolver.reresolve]) both clear the flag BEFORE
        // their hide cascades, so the genuine end-of-session demote still
        // reaches stopForeground.
        if (iconShowing || isLive || mediaProjectionController.hasConsent ||
            mediaProjectionActivated
        ) {
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

/** The attach plan for a deferred capture's completion: one (source,
 *  translation, backend) triple per DISTINCT normalized key with a non-blank
 *  translation. The capture recorded duplicate group texts as a single row
 *  (the token's seen-set), so each row is offered its translation exactly
 *  once — a repeat for the same key would just burn a store hop on ALREADY.
 *  Pure, so the contract is unit-tested without the Android-heavy service. */
internal fun deferredAttachPlan(
    groupTexts: List<String>,
    translations: List<CaptureService.GroupTranslation>,
    recordSrc: String,
): List<Triple<String, String, String?>> {
    val attached = HashSet<String>()
    val plan = mutableListOf<Triple<String, String, String?>>()
    groupTexts.forEachIndexed { i, source ->
        val tr = translations.getOrNull(i)?.text.orEmpty()
        if (tr.isEmpty()) return@forEachIndexed
        val key = com.playtranslate.translationlog.LogWriteGate.normalizedKey(source, recordSrc)
        if (key.isEmpty() || !attached.add(key)) return@forEachIndexed
        plan.add(Triple(source, tr, translations.getOrNull(i)?.backendDisplayName))
    }
    return plan
}
