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
import androidx.lifecycle.MutableLiveData
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.playtranslate.model.TranslationResult
import com.google.mlkit.nl.translate.TranslateLanguage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
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
    @Volatile private var projectionStopCooldownUntilMs: Long = 0L
    @Volatile private var projectionStartInProgress: Boolean = false

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

    internal var gameDisplayId: Int = 0
    /** Always returns the current source-language translation code from Prefs.
     *  Single source of truth for the language pair — callers don't need to
     *  notify the service when prefs change; [ensureLanguageManagersFor]
     *  picks up drift at each capture entry point. */
    internal val sourceLang: String
        get() = SourceLanguageProfiles[Prefs(this).sourceLangId].translationCode
    private var savedRegion = RegionEntry("", 0f, 1f)
    /** Tracks whether [configureSaved] has populated capture-time state
     *  (displayId, region). Keeping this distinct from manager presence
     *  means a translation-only path that constructs translators via
     *  [ensureLanguageManagersFor] doesn't cause [isConfigured]
     *  to report ready-for-capture when display/region haven't actually
     *  been set. */
    private var hasCaptureStateConfigured: Boolean = false
    private var overrideRegion: RegionEntry? = null

    /** Observable active region — override if set, otherwise saved. */
    val activeRegionLiveData = MutableLiveData(RegionEntry("", 0f, 1f))
    /** Current active region snapshot for synchronous reads. */
    val activeRegion: RegionEntry get() = activeRegionLiveData.value!!
    /** True when a temporary override region is active. */
    val isOverride: Boolean get() = overrideRegion != null

    private fun updateActiveRegion() {
        val newRegion = overrideRegion ?: savedRegion
        activeRegionLiveData.value = newRegion

        OverlayHost.current?.hideRegionIndicator()
        OverlayHost.current?.hideTranslationOverlay()
        oneShotCaptureJob?.cancel()
        oneShotManager.cancel()
        if (liveActive) {
            liveMode?.stop()
            startLive()
        }
    }

    // ── Callbacks to Activity ─────────────────────────────────────────────

    var onResult: ((TranslationResult) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onStatusUpdate: ((String) -> Unit)? = null
    var onTranslationStarted: (() -> Unit)? = null
    /** Fired during live mode when an OCR cycle finds no source-language text. */
    var onLiveNoText: (() -> Unit)? = null
    /** Emitted when hold-to-preview loading state changes. */
    var onHoldLoadingChanged: ((Boolean) -> Unit)? = null

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

    /** Push current service state to the floating icon. Called automatically
     *  by [liveActive] setter, [setDegraded], and when a new icon is created
     *  (via [PlayTranslateAccessibilityService.floatingIcon] setter). */
    fun syncIconState() {
        val icon = OverlayHost.current?.floatingIcon ?: return
        icon.liveMode = isLive
        icon.degraded = translationDegraded
    }

    // ── Capture-mode dispatch ──────────────────────────────────────

    /** The currently active screenshot provider, or `null` if neither
     *  Accessibility nor MediaProjection capture is initialised yet.
     *  In projection mode the projection host is used; otherwise we fall
     *  back to the Accessibility-mode [ScreenshotManager]. */
    internal fun currentScreenshotProvider(): ScreenshotProvider? =
        ProjectionOverlayHost.instance
            ?: PlayTranslateAccessibilityService.instance?.screenshotManager

    fun projectionRestartDelayMs(): Long {
        val remaining = projectionStopCooldownUntilMs - SystemClock.uptimeMillis()
        return remaining.coerceAtLeast(0L)
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
        // Routed start-projection request from MainActivity — [startProjection]
        // handles the foreground promotion itself with the correct
        // mediaProjection type (must happen *before* getMediaProjection()).
        if (intent?.action == ACTION_START_PROJECTION) {
            // NOTE: [Activity.RESULT_OK] is -1, so we MUST use a sentinel
            // that can never collide with a real resultCode. Using -1 as
            // default would make a successful grant indistinguishable from
            // a missing extra and reject every consent.
            val rc = intent.getIntExtra(EXTRA_PROJECTION_RESULT_CODE, Int.MIN_VALUE)
            // Use the type-safe API on Android 13+ — the legacy
            // getParcelableExtra<Intent> can silently return null for
            // Intent-in-Intent extras under stricter Parcelable validation.
            val data: Intent? = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_PROJECTION_DATA)
            }
            Log.i(TAG, "ACTION_START_PROJECTION rc=$rc data=${data != null}")
            if (rc != Int.MIN_VALUE && data != null) {
                startProjection(rc, data)
                return START_STICKY
            } else {
                Log.e(TAG, "ACTION_START_PROJECTION missing extras (rc=$rc, data=${data != null})")
                Toast.makeText(
                    this,
                    "Share Screen: missing consent data (rc=$rc)",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
        // Default path: promote with SPECIAL_USE only. The manifest declares
        // `specialUse|mediaProjection`; calling 2-arg startForeground would
        // make Android validate the mediaProjection type, which requires
        // the project_media appop (only granted after consent) and crashes
        // with SecurityException at startup. Always pass the explicit type.
        startForegroundForCurrentMode()
        updateForegroundState()
        return START_STICKY
    }

    /** Start (or re-start) the foreground service with the foreground-service
     *  type that matches the current capture state: [FOREGROUND_SERVICE_TYPE_
     *  MEDIA_PROJECTION] when a projection session is active, else
     *  [FOREGROUND_SERVICE_TYPE_SPECIAL_USE]. Both types are declared in the
     *  manifest; the 3-arg overload tells Android which subset to validate. */
    private fun startForegroundForCurrentMode() {
        val type = if (ProjectionOverlayHost.instance != null)
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        else
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        try {
            startForeground(NOTIF_ID, buildNotification(), type)
        } catch (e: Throwable) {
            Log.e(TAG, "startForegroundForCurrentMode failed type=$type", e)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.w(TAG, "onTaskRemoved")
        super.onTaskRemoved(rootIntent)
        if (ProjectionOverlayHost.instance != null) stopProjection()
        PlayTranslateAccessibilityService.instance?.hideFloatingIcon("task_removed")
    }

    override fun onDestroy() {
        Log.w(TAG, "onDestroy")
        instance = null
        stopLive()
        ProjectionOverlayHost.instance?.stop()
        serviceScope.cancel()
        translationManager?.close()
        deeplTranslator?.close()
        lingvaTranslator?.close()
        // Clear callbacks to release Activity references
        onResult = null
        onError = null
        onStatusUpdate = null
        onTranslationStarted = null
        onLiveNoText = null
        onHoldLoadingChanged = null
        PlayTranslateAccessibilityService.instance?.onHotkeyActivated = null
        PlayTranslateAccessibilityService.instance?.onHotkeyReleased = null
        super.onDestroy()
    }

    // ── Public API ────────────────────────────────────────────────────────

    /** Apply a temporary override region. Does not change display/language/engines. */
    fun configureOverride(region: RegionEntry) {
        overrideRegion = region
        updateActiveRegion()
    }

    /** Clear any temporary override, reverting to the saved region. */
    fun clearOverride() {
        overrideRegion = null
        updateActiveRegion()
    }

    /** Configure the saved region and translation engines. Clears any override. */
    /** Sets non-language-pair state (display, region) and ensures language
     *  managers are fresh. The translation pair is snapshotted from Prefs
     *  inside [ensureLanguageManagersFor] so the service self-heals whenever
     *  prefs drift, no matter which code path wrote them. */
    fun configureSaved(
        displayId: Int,
        region: RegionEntry = RegionEntry("", 0f, 1f)
    ) {
        gameDisplayId    = displayId
        this.savedRegion = region
        this.overrideRegion = null
        hasCaptureStateConfigured = true
        updateActiveRegion()
        ensureLanguageManagersFor(snapshotTranslationTarget())
        onStatusUpdate?.invoke(getString(R.string.status_idle))
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

    fun captureOnce() {
        oneShotCaptureJob?.cancel()
        oneShotCaptureJob = serviceScope.launch { runCaptureCycle() }
    }

    /**
     * One-shot translate driven by a tap on the floating icon. Same flow as
     * the in-app Translate button's hold-release sequence — runs OCR on a
     * clean capture and paints the translation overlay on the game screen
     * — but completes on its own (no companion release event required).
     *
     * Used by [ProjectionOverlayHost] in Share-Screen mode where there's no
     * accessibility floating-menu surface to present a Translate action.
     */
    fun translateOnceOnScreen() {
        if (!isConfigured) {
            onError?.invoke("Not configured \u2014 set source/target language first")
            return
        }
        OverlayHost.current?.hideTranslationOverlay()
        oneShotManager.runHoldOverlay()
    }

    /**
     * Processes a pre-captured screenshot bitmap instead of taking a new one.
     * Used when the screenshot must be taken before an activity appears on screen
     * (e.g. single-screen region capture from the floating menu).
     */
    fun processScreenshot(raw: Bitmap) {
        oneShotCaptureJob?.cancel()
        oneShotCaptureJob = serviceScope.launch { runProcessCycle(raw) }
    }

    private suspend fun runProcessCycle(raw: Bitmap) {
        if (!isConfigured) {
            onError?.invoke("Not configured — tap Translate to set up")
            raw.recycle()
            return
        }
        var bitmap: Bitmap = raw
        try {
            onStatusUpdate?.invoke(getString(R.string.status_capturing))
            val screenshotPath = currentScreenshotProvider()?.saveToCache(raw)

            val statusBarHeight = getStatusBarHeightForDisplay(gameDisplayId)
            val top    = maxOf((raw.height * activeRegion.top).toInt(), statusBarHeight)
            val left   = (raw.width  * activeRegion.left).toInt()
            val bottom = (raw.height * activeRegion.bottom).toInt()
            val right  = (raw.width  * activeRegion.right).toInt()
            bitmap = cropBitmap(raw, top, bottom, left, right)

            // blackoutFloatingIcon may recycle its input (immutable path),
            // so `bitmap` may be stale after this call. A nested try/finally
            // on ocrBitmap keeps its cleanup local and survives OCR exceptions
            // without the outer finally having to follow ownership transfers.
            val ocrBitmap = blackoutFloatingIcon(bitmap, left, top)
            val ocrResult = try {
                onStatusUpdate?.invoke(getString(R.string.status_ocr))
                ocrManager.recognise(ocrBitmap, sourceLang, screenshotWidth = raw.width)
            } finally {
                if (!ocrBitmap.isRecycled) ocrBitmap.recycle()
            }

            if (ocrResult == null) {
                onStatusUpdate?.invoke(noTextMessage())
                return
            }

            onStatusUpdate?.invoke(getString(R.string.status_translating))
            val (translated, note) = translateGroups(ocrResult.groupTexts)

            val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            onResult?.invoke(
                TranslationResult(
                    originalText   = ocrResult.fullText,
                    segments       = ocrResult.segments,
                    translatedText = translated,
                    timestamp      = timestamp,
                    screenshotPath = screenshotPath,
                    note           = note
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Process cycle failed: ${e.message}", e)
            onError?.invoke(e.message ?: "Unknown error")
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    // ── Live mode ─────────────────────────────────────────────────────────
    //
    // Interaction-driven: capture once, show overlay, then wait for user
    // input. On input → hide overlay + start debounce timer. When the
    // timer expires (no further input) → capture again.

    /** Observable live mode state. Observe this to react to live mode changes. */
    val liveModeState = MutableLiveData(false)
    internal var liveActive: Boolean
        get() = liveModeState.value == true
        set(v) {
            liveModeState.value = v
            updateForegroundState()
            syncIconState()
        }

    val isLive: Boolean get() = liveActive

    internal var liveMode: LiveMode? = null
    private val oneShotManager = OneShotManager(this)
    private var oneShotCaptureJob: Job? = null

    /** Listens for the configured capture display vanishing mid-session
     *  (external monitor unplugged, virtual display destroyed). Stops live
     *  mode rather than letting the cycle spin on a dead display ID. */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {
            if (liveActive && displayId == gameDisplayId) {
                Log.w(TAG, "Capture display $displayId disconnected, stopping live mode")
                Toast.makeText(
                    this@CaptureService,
                    "Capture display disconnected. Live mode stopped.",
                    Toast.LENGTH_LONG
                ).show()
                stopLive()
            }
        }
    }

    fun startLive() {
        liveMode?.stop()
        oneShotCaptureJob?.cancel()

        val prefs = Prefs(this)
        // Migrate legacy ordinal-based auto_translation_mode here too so
        // users upgrading from an old build who start live from the floating
        // icon/hotkey before ever opening MainActivity still get the correct
        // mode class. The MainActivity.onCreate call is the primary path;
        // this is defensive.
        prefs.migrateLegacyPrefs()
        val useInAppOnly = prefs.hideGameOverlays && !Prefs.isSingleScreen(this)
        val newMode: LiveMode = if (useInAppOnly) {
            // InAppOnlyMode doesn't use PlayTranslateAccessibilityService directly
            // (no overlay windows, no input monitoring, no screenshotManager fetch)
            // so it keeps the single-argument constructor. PinholeOverlayMode and
            // FuriganaMode take an explicit a11y reference; if accessibility isn't
            // connected, bail out of live mode rather than constructing a mode
            // that can't function.
            InAppOnlyMode(this)
        } else if (prefs.isMediaProjectionMode) {
            // Share-Screen mode has no accessibility-driven input monitoring
            // for change detection \u2014 fall back to a simple polled mode that
            // re-captures + dedups by OCR text. Renders through the active
            // [OverlayHost] (= ProjectionOverlayHost) so the on-screen
            // overlay still appears.
            ProjectionLiveMode(this)
        } else {
            val a11y = PlayTranslateAccessibilityService.instance
            if (a11y == null) {
                val msg = "Enable PlayTranslate in Accessibility Settings to start live mode."
                Log.w(TAG, "startLive aborted: $msg")
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                liveMode = null
                liveActive = false
                return
            }
            when (prefs.overlayMode) {
                OverlayMode.FURIGANA -> FuriganaMode(this, a11y)
                else -> PinholeOverlayMode(this, a11y)
            }
        }
        // Assign liveMode BEFORE flipping liveActive so LiveData observers
        // (e.g. MainActivity.updateRegionButton) see the new mode identity
        // synchronously — setter on liveActive dispatches to observers before
        // this function returns.
        liveMode = newMode
        liveActive = true
        newMode.start()
        // Flash the region indicator immediately — synchronous wm.addView so
        // the indicator is on screen within ~1 frame. Previously each mode's
        // first capture cycle fired this flag, but the screenshot loop's
        // rate-limit carry-over from the prior cycle delayed the flash by
        // ~600ms on region change during live mode.
        flashRegionIndicator()

        // Register after start() so stopLive's unregister is a matching pair.
        // Unregister first defensively in case startLive was called while
        // already live (idempotent double-register would double-fire callbacks).
        val dm = getSystemService(DisplayManager::class.java)
        dm?.unregisterDisplayListener(displayListener)
        dm?.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
    }

    /** True when the active live mode is In-App Only. */
    val isInAppOnly: Boolean
        get() = liveActive && liveMode is InAppOnlyMode

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
            // Visible translation overlay → hold peeks through it
            if (liveActive && liveMode !is FuriganaMode && !isInAppOnly) {
                return HoldBehavior.HIDE_TRANSLATIONS
            }
            // Visible furigana overlay → hold forces a translation one-shot
            if (liveActive && liveMode is FuriganaMode) {
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
     * itself automatically — but the live-mode class selection at [startLive]
     * is sticky, computed once at live-start time. A running Pinhole/Furigana
     * session entering split-screen with `hideGameOverlays` enabled wants
     * InAppOnlyMode instead; a running InAppOnlyMode exiting to fullscreen
     * wants an overlay mode. This entry point performs that mode-class swap
     * when needed, and otherwise refreshes the current mode to clear the
     * now-stale clean-reference bitmap (which would otherwise flicker
     * through scene-change recovery on its own).
     */
    fun onMultiWindowChanged() {
        if (!liveActive) return
        val prefs = Prefs(this)
        val shouldBeInAppOnly =
            prefs.hideGameOverlays && !Prefs.isSingleScreen(this)
        val isCurrentlyInAppOnly = liveMode is InAppOnlyMode
        if (shouldBeInAppOnly != isCurrentlyInAppOnly) {
            Log.d(TAG, "onMultiWindowChanged: mode class swap (inAppOnly $isCurrentlyInAppOnly -> $shouldBeInAppOnly)")
            stopLive()
            startLive()
        } else {
            Log.d(TAG, "onMultiWindowChanged: refreshing ${liveMode?.javaClass?.simpleName}")
            liveMode?.refresh()
        }
    }

    fun stopLive() {
        getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(displayListener)
        liveMode?.stop()
        liveMode = null
        liveActive = false
        currentScreenshotProvider()?.stopLoop()
        // Input monitoring is Accessibility-only — no-op in projection mode.
        PlayTranslateAccessibilityService.instance?.stopInputMonitoring()
        OverlayHost.current?.hideTranslationOverlay()
        setDegraded(false)
    }

    // ── MediaProjection lifecycle ──────────────────────────────────────

    /**
     * Begin a Share-Screen (MediaProjection) session using the consent
     * tuple obtained from [android.media.projection.MediaProjectionManager.
     * createScreenCaptureIntent]. Replaces any previously active host.
     *
     * Android 14+ requires the service to be running in foreground with
     * the `mediaProjection` type **before** [MediaProjectionManager.
     * getMediaProjection] is called — we re-promote the foreground
     * notification with that type here. The notification itself is
     * unchanged; only the FGS type bitmask is amended.
     */
    fun startProjection(resultCode: Int, data: Intent): Boolean {
        if (projectionStartInProgress) {
            Log.w(TAG, "startProjection ignored: another start is already in progress")
            return false
        }
        projectionStartInProgress = true
        return try {
        try { ProjectionOverlayHost.instance?.stop() } catch (e: Throwable) { Log.e(TAG, "Failed to stop previous projection", e) }
        projectionStopCooldownUntilMs = 0L
        // Promote to mediaProjection FGS type *before* obtaining the
        // projection. The manifest declares `specialUse|mediaProjection`,
        // so passing FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION is a valid
        // subset. The system also validates that we hold the project_media
        // appop — the consent token in [data] grants it as part of
        // [MediaProjectionManager.getMediaProjection].
        try {
            startForeground(
                NOTIF_ID, buildNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } catch (e: Exception) {
            Log.e(TAG, "startForeground(mediaProjection) failed", e)
            try {
                startForegroundForCurrentMode()
            } catch (fallback: Exception) {
                Log.e(TAG, "fallback foreground start failed", fallback)
                stopSelf()
            }
            Toast.makeText(
                this,
                "Share Screen failed to start: ${e.message ?: e.javaClass.simpleName}",
                Toast.LENGTH_LONG,
            ).show()
            return false
        }
        val host = ProjectionOverlayHost.start(this, resultCode, data) ?: run {
            Log.e(TAG, "ProjectionOverlayHost.start returned null")
            try {
                startForegroundForCurrentMode()
            } catch (fallback: Exception) {
                Log.e(TAG, "foreground downgrade after projection failure failed", fallback)
                stopSelf()
            }
            Toast.makeText(
                this,
                "Share Screen failed to acquire MediaProjection.",
                Toast.LENGTH_LONG,
            ).show()
            return false
        }
        // Default game display = primary in projection mode.
        gameDisplayId = android.view.Display.DEFAULT_DISPLAY
        Prefs(this).captureDisplayId = gameDisplayId
        // Make sure the user actually sees a tap target after granting:
        // force the pref on so a previously-disabled icon doesn't suppress
        // the post-grant ensureFloatingIcon() below.
        Prefs(this).showOverlayIcon = true
        try {
            host.ensureFloatingIcon()
        } catch (e: Throwable) {
            Log.e(TAG, "ensureFloatingIcon after projection start failed", e)
        }
        updateForegroundState()
        true
        } finally {
            projectionStartInProgress = false
        }
    }

    /** Tear down any active MediaProjection session. Safe to call when none is active. */
    fun stopProjection() {
        val hadProjection = ProjectionOverlayHost.instance != null
        if (hadProjection) {
            projectionStopCooldownUntilMs = SystemClock.uptimeMillis() + PROJECTION_RESTART_COOLDOWN_MS
        }
        if (liveActive) stopLive()
        ProjectionOverlayHost.instance?.stop()
        // Foreground type stays elevated for the rest of this service's life
        // \u2014 explicitly downgrading to specialUse-only requires the API-34
        // FOREGROUND_SERVICE_TYPE_SPECIAL_USE constant. The notification
        // itself goes away naturally if no other surface keeps the service
        // foregrounded (see [updateForegroundState]).
        updateForegroundState()
        if (hadProjection) {
            mainHandler.postDelayed({
                updateForegroundState()
            }, PROJECTION_RESTART_COOLDOWN_MS + 100L)
        }
    }

    // ── Unified loop handlers ─────────────────────────────────────────────

    /** Trigger a fresh capture cycle in live mode (e.g. after hold-release). */
    fun refreshLiveOverlay() {
        if (!liveActive) return
        Log.d(TAG, "REFRESH: refreshLiveOverlay called")
        liveMode?.refresh()
    }

    /** One-shot: capture, OCR, translate, show overlay (not live mode). */

    /** True while a hold gesture or modal UI is active — suppresses overlay display in live mode. */
    var holdActive = false

    /** Path to the last clean screenshot. Delegates to the active provider. */
    val lastCleanScreenshotPath: String?
        get() = currentScreenshotProvider()?.lastCleanPath

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
    private fun beginHoldPreview(mode: OverlayMode?) {
        holdActive = true
        if (liveActive) {
            currentScreenshotProvider()?.stopLoop()
            OverlayHost.current?.hideTranslationOverlay()
        }
        oneShotManager.runHoldOverlay(forceMode = mode)
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
        if (liveActive) {
            liveMode?.refresh()
        }
    }

    /** Begin a hold-to-preview gesture (in-app translate button). */
    fun holdStart() {
        // Pinhole / translation-overlay live modes: "peek" through the
        // overlay at the game underneath, without running a one-shot.
        // PinholeOverlayMode's cycle polls [holdActive] and pauses itself.
        if (liveActive && liveMode !is FuriganaMode && !isInAppOnly) {
            holdActive = true
            OverlayHost.current?.hideTranslationOverlay()
            return
        }
        onHoldLoadingChanged?.invoke(true)
        val forced = if (liveActive && liveMode is FuriganaMode) {
            OverlayMode.TRANSLATION
        } else {
            null
        }
        beginHoldPreview(forced)
    }

    /** End a hold-to-preview gesture (in-app translate button). */
    fun holdEnd() {
        onHoldLoadingChanged?.invoke(false)
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
        onHoldLoadingChanged?.invoke(false)
        endHoldPreview()
    }

    // ── Hotkey hold ─────────────────────────────────────────────────────

    private var hotkeyActive = false

    /** Begin a hotkey hold-to-preview with a forced overlay mode. */
    fun hotkeyHoldStart(mode: OverlayMode) {
        DetectionLog.log("Hotkey START: $mode (live=$liveActive)")
        Log.d("HotkeyDbg", "hotkeyHoldStart: mode=$mode isConfigured=$isConfigured liveActive=$liveActive")
        if (hotkeyActive) return
        hotkeyActive = true
        beginHoldPreview(mode)
    }

    /** End a hotkey hold-to-preview. */
    fun hotkeyHoldEnd() {
        if (!hotkeyActive) return
        hotkeyActive = false
        DetectionLog.log("Hotkey END (live=$liveActive)")
        endHoldPreview()
    }

    /**
     * Briefly flash the capture region indicator on the game display.
     * Called after a screenshot is captured so the indicator doesn't
     * appear in the screenshot.
     */
    internal fun noTextMessage(): String {
        val langName = java.util.Locale(sourceLang).getDisplayLanguage(java.util.Locale.ENGLISH)
            .replaceFirstChar { it.uppercase(java.util.Locale.ENGLISH) }
        return getString(R.string.status_no_text, langName, activeRegion.label)
    }

    internal fun flashRegionIndicator() {
        val host = OverlayHost.current ?: return
        val dm = getSystemService(DisplayManager::class.java)
        val display = dm.getDisplay(gameDisplayId) ?: return
        host.showRegionIndicator(display, activeRegion)
    }

    /** Run the shared OCR pipeline on a clean frame. Caller still owns raw bitmap. */
    internal suspend fun runOcr(raw: Bitmap): OverlayToolkit.OcrPipelineResult? {
        return OverlayToolkit.runOcrPipeline(
            raw, activeRegion, sourceLang, ocrManager,
            getStatusBarHeightForDisplay(gameDisplayId),
            OverlayHost.current?.getFloatingIconRect(),
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
        onTranslationStarted?.invoke()
        val perGroup = translateGroupsSeparately(ocrResult.groupTexts)
        val translated = perGroup.joinToString("\n\n") { it.first }
        val note = perGroup.mapNotNull { it.second }.firstOrNull()
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        onResult?.invoke(com.playtranslate.model.TranslationResult(
            originalText   = ocrResult.fullText,
            segments       = ocrResult.segments,
            translatedText = translated,
            timestamp      = timestamp,
            screenshotPath = screenshotPath,
            note           = note
        ))
        return perGroup
    }

    /** Called when OCR finds no source-language text: hides overlays and notifies the UI. */
    internal fun handleNoTextDetected() {
        OverlayHost.current?.hideTranslationOverlay()
        onLiveNoText?.invoke()
    }

    /** Remove specific overlay boxes without rebuilding the entire view. */
    internal fun removeOverlayBoxes(toRemove: List<TranslationOverlayView.TextBox>) {
        OverlayHost.current?.removeOverlayBoxes(toRemove)
    }

    internal fun showLiveOverlay(
        boxes: List<TranslationOverlayView.TextBox>,
        cropLeft: Int, cropTop: Int,
        screenshotW: Int, screenshotH: Int,
        force: Boolean = false,
        pinholeMode: Boolean = false
    ) {
        if (!force && holdActive) { Log.w("FuriganaDbg", "showLiveOverlay BLOCKED: holdActive=true"); return }
        val host = OverlayHost.current
        if (host == null) { Log.w("FuriganaDbg", "showLiveOverlay BLOCKED: host=null"); return }
        val dm = getSystemService(DisplayManager::class.java)
        val display = dm.getDisplay(gameDisplayId)
        if (display == null) { Log.w("FuriganaDbg", "showLiveOverlay BLOCKED: display=null for id=$gameDisplayId"); return }
        Log.d("FuriganaDbg", "showLiveOverlay: ${boxes.size} boxes, crop=($cropLeft,$cropTop), screen=${screenshotW}x$screenshotH")
        host.showTranslationOverlay(display, boxes, cropLeft, cropTop, screenshotW, screenshotH, pinholeMode)
    }

    /** Captures a clean screenshot via the active provider. */
    internal suspend fun captureScreen(displayId: Int): Bitmap? {
        return currentScreenshotProvider()?.requestClean(displayId)
    }

    /**
     * @param preCaptured If non-null, use this bitmap instead of taking a new
     *   screenshot. Used by scene detection which already has a clean frame.
     */
    companion object {
        @Volatile
        var instance: CaptureService? = null
            private set
        /** Action consumed in [onStartCommand] to launch a MediaProjection
         *  session with `resultCode` (Int) and `data` (Intent) extras. */
        const val ACTION_START_PROJECTION = "com.playtranslate.action.START_PROJECTION"
        const val EXTRA_PROJECTION_RESULT_CODE = "resultCode"
        const val EXTRA_PROJECTION_DATA = "data"
        private const val PROJECTION_RESTART_COOLDOWN_MS = 1800L
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
    /** Convenience: blackout floating icon using current service state. Delegates to OverlayToolkit. */
    private fun blackoutFloatingIcon(bitmap: Bitmap, cropLeft: Int = 0, cropTop: Int = 0): Bitmap =
        OverlayToolkit.blackoutFloatingIcon(bitmap, cropLeft, cropTop,
            OverlayHost.current?.getFloatingIconRect(),
            Prefs(this).compactOverlayIcon)

    fun resetConfiguration() {
        translationManager?.close()
        translationManager = null
        deeplTranslator  = null
        lingvaTranslator = null
        gameDisplayId = 0
        hasCaptureStateConfigured = false
        onStatusUpdate?.invoke(getString(R.string.status_idle))
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

    /**
     * Core capture → crop → OCR → translate pipeline shared by one-shot
     * and all live modes. Returns a [PipelineResult] or null if no text.
     */
    internal suspend fun runCaptureOcrTranslate(onScreenshotTaken: (() -> Unit)? = null): PipelineResult? {
        val raw: Bitmap = captureScreen(gameDisplayId) ?: run {
            onError?.invoke("Screenshot failed for display $gameDisplayId. Try a different display in Settings.")
            return null
        }
        onScreenshotTaken?.invoke()
        var bitmap: Bitmap? = raw
        try {
            val screenshotPath = currentScreenshotProvider()?.saveToCache(raw)

            val statusBarHeight = getStatusBarHeightForDisplay(gameDisplayId)
            val top    = maxOf((raw.height * activeRegion.top).toInt(), statusBarHeight)
            val left   = (raw.width  * activeRegion.left).toInt()
            val bottom = (raw.height * activeRegion.bottom).toInt()
            val right  = (raw.width  * activeRegion.right).toInt()
            bitmap = cropBitmap(raw, top, bottom, left, right)

            // See runProcessCycle for the ownership rationale behind the
            // nested try/finally.
            val ocrBitmap = blackoutFloatingIcon(bitmap, left, top)
            val ocrResult = try {
                ocrManager.recognise(ocrBitmap, sourceLang, screenshotWidth = raw.width)
            } finally {
                if (!ocrBitmap.isRecycled) ocrBitmap.recycle()
            }

            if (ocrResult == null) return null

            val perGroup = translateGroupsSeparately(ocrResult.groupTexts)
            val translated = perGroup.joinToString("\n\n") { it.first }
            val note = perGroup.mapNotNull { it.second }.firstOrNull()
            val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

            return PipelineResult(
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
        } catch (e: Exception) {
            Log.e(TAG, "Capture cycle failed: ${e.message}", e)
            onError?.invoke(e.message ?: "Unknown error")
            return null
        } finally {
            bitmap?.let { if (!it.isRecycled) it.recycle() }
        }
    }

    /** One-shot capture: shows status updates and invokes [onResult]. */
    private suspend fun runCaptureCycle() {
        if (!isConfigured) {
            onError?.invoke("Not configured — tap Translate to set up")
            return
        }
        onStatusUpdate?.invoke(getString(R.string.status_capturing))
        val pipeline = runCaptureOcrTranslate(onScreenshotTaken = { flashRegionIndicator() })
        if (pipeline != null) {
            onResult?.invoke(pipeline.result)
        } else {
            onStatusUpdate?.invoke(noTextMessage())
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
            val results = uncached.map { (_, key) ->
                serviceScope.async { translate(key.text, target) }
            }.awaitAll()

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
     *  - [liveActive] property setter (in this class)
     *  - [PlayTranslateAccessibilityService.floatingIcon] property setter
     */
    fun updateForegroundState() {
        val iconShowing = OverlayHost.current?.floatingIcon != null

        // Stop live mode if the user can no longer see or manage it.
        if (liveActive) {
            val shouldStop = if (isInAppOnly) {
                // In-App Only: results only visible while app is in foreground
                !MainActivity.isInForeground
            } else {
                // Overlay modes: stop if no control surface at all (no icon, no app)
                !iconShowing && !MainActivity.isInForeground
            }
            if (shouldStop) {
                stopLive()
                // stopLive() sets liveActive = false, which re-enters this method
                return
            }
        }

        val projectionStopCoolingDown = projectionRestartDelayMs() > 0L
        if (iconShowing || liveActive || ProjectionOverlayHost.instance != null || projectionStopCoolingDown) {
            startForegroundForCurrentMode()
        } else {
            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } catch (e: Throwable) {
                Log.e(TAG, "stopForeground failed", e)
            }
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
