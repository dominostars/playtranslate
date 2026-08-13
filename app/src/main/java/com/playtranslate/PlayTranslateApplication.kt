package com.playtranslate

import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks2
import android.os.Build
import android.os.Bundle
import com.playtranslate.capture.CaptureBackendResolver
import com.playtranslate.diagnostics.CrashHandler
import android.content.Context
import com.playtranslate.region.RegionPolicy
import com.playtranslate.translation.GemmaE2BMnnBackend
import com.playtranslate.translation.HyMtBackend
import com.playtranslate.translation.BergamotBackend
import com.playtranslate.translation.MlKitBackend
import com.playtranslate.translation.OnlineBackendFactory
import com.playtranslate.translation.OnlineServiceStore
import com.playtranslate.translation.QwenMnnBackend
import com.playtranslate.translation.Qwen35Mnn2bBackend
import com.playtranslate.translation.TranslationBackendRegistry
import com.playtranslate.translation.mnn.MnnTranslator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

class PlayTranslateApplication : Application() {

    /** Application-scoped coroutine scope for fire-and-forget background work
     *  that must outlive any individual UI lifecycle (onTrimMemory unloads,
     *  post-save key validation that should still fire its Toast after the
     *  settings sub-screen finishes, etc.). IO dispatcher because LLM unload
     *  has to wait on the engine's llamaDispatcher and shouldn't tie up the
     *  main thread; consumers that need Main (UI Toasts) pass it explicitly
     *  to [launch]. */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        CrashHandler.install(this)
        // Collect Anki screenshot pins orphaned by a crash/process death
        // (their send's finally never ran). Also swept opportunistically on
        // every pin; this catches the "never sends again" tail. Off-main:
        // cold start shouldn't pay for cache hygiene.
        Thread { com.playtranslate.ui.AnkiScreenshotPin.sweepStale(this) }.start()
        // History capture images orphaned by FIFO prune / row deletes /
        // process death mid-copy — reconciled against surviving rows.
        com.playtranslate.translationlog.HistoryImageStore.sweepAsync(this)
        // Push the persisted grouping-debug flag into the process-wide
        // OcrManager singleton before any OCR can run. The SettingsRenderer
        // toggle also writes this on change, so the in-memory copy stays in
        // sync with [Prefs.debugLogGrouping] without OcrManager holding a
        // Context of its own. Gated on BuildConfig.DEBUG so a stale `true`
        // value carried over from a debug build can't leak OCR text to
        // logcat in a release upgrade — release builds also hide the Debug
        // section, so there'd be no way to turn it back off.
        // Production OCR needs an app Context to resolve installed packs (the
        // registry + bridges are Context-free); push it before any capture runs.
        // Selection lives in Prefs; OcrModelManager resolves the chosen engine
        // per recognise() and the bridges own the native sessions.
        com.playtranslate.ocr.registry.OcrModelManager.appContext = applicationContext
        if (BuildConfig.DEBUG) {
            OcrManager.instance.debugLogGroupingEnabled = Prefs(this).debugLogGrouping
            // The AngleProbe rides the same toggle — one switch turns on all
            // angle instrumentation (probe + ang= layout lines).
            OcrManager.instance.debugAngleProbeEnabled = Prefs(this).debugLogGrouping
            // Slant-gate rollback override survives restarts like the
            // toggles above (ON = the pre-drop 10° gate).
            if (Prefs(this).debugAngleGateAtTarget) {
                OcrManager.instance.debugAngleGateDeg =
                    com.playtranslate.ocr.core.OcrBox.ANGLE_LEGACY_GATE_DEG
            }
        }
        // Push the "Use MangaOCR" toggle + installed-pack state into the OCR gate
        // (same Context-free reason as above — the refiner can't resolve the pack itself).
        com.playtranslate.ocr.mangaocr.MangaOcrProvisioning.refresh(applicationContext)
        // Derive the capture backend from the granted permissions (the
        // accessibility service vs "display over other apps").
        CaptureBackendResolver.reresolve(this)
        // Build the translation-backend registry once at process start.
        // Backends are stateless or hold pooled HTTP clients that should
        // outlive a single CaptureService instance. Config is read via
        // closure each call so a Settings change propagates without
        // rebuilding the registry.
        //
        // Online backends are store-driven: OnlineServiceStore.init runs
        // the one-shot legacy→instance migration and loads the ordered
        // instance list, which the factory maps to backends. The trailing
        // setOrder makes the waterfall honor the store's list order for
        // the online segment (offline tiers follow by priority). The
        // settings UI keeps membership + order in sync as instances are
        // added / removed / reordered.
        // Composition-root install of the user-edited prompt overrides
        // (Advanced LLM Configuration). The template objects are stateless
        // and Context-free, so this closure is their one seam to Prefs —
        // re-read per translate call, so an edit propagates without any
        // registry rebuild.
        com.playtranslate.translation.llm.LlmPromptTemplates.overrideProvider =
            { kind -> kind.read(Prefs(this)) }
        // {context} resolves against the live recording backend. Never
        // force-inits the recorder (no context can exist before a first
        // commit anyway); called from backend threads — contextBlockFor is
        // thread-safe and returns "" whenever the feature is off.
        com.playtranslate.translation.llm.LlmPromptTemplates.contextProvider = { source, target ->
            CaptureService.instance?.translationLogRecorderIfInitialized
                ?.contextBlockFor(source, target) ?: ""
        }
        val sharedPrefs = getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE)
        OnlineServiceStore.init(this)
        val onlineBackends = OnlineServiceStore.all().map {
            OnlineBackendFactory.build(this, sharedPrefs, it)
        }
        TranslationBackendRegistry.init(
            onlineBackends + listOf(
                GemmaE2BMnnBackend(
                    context         = this,
                    enabledProvider = { Prefs(this).gemmaE2bEnabled },
                ),
                HyMtBackend(
                    context         = this,
                    // AND-gate the region check at runtime, not just in the
                    // Settings UI: a restored backup, a region change after
                    // install, or any path that leaves hyMtEnabled=true in a
                    // restricted region would otherwise let the waterfall
                    // run Hunyuan against the HY Community License.
                    enabledProvider = {
                        Prefs(this).hyMtEnabled &&
                            !RegionPolicy.isHunyuanRestricted(this)
                    },
                ),
                QwenMnnBackend(
                    context         = this,
                    enabledProvider = { Prefs(this).qwenMnnEnabled },
                ),
                Qwen35Mnn2bBackend(
                    context         = this,
                    enabledProvider = { Prefs(this).qwen35Mnn2bEnabled },
                ),
                BergamotBackend(
                    context         = this,
                    enabledProvider = { Prefs(this).bergamotEnabled },
                ),
                MlKitBackend(),
            )
        )
        TranslationBackendRegistry.setOrder(OnlineServiceStore.all().map { it.id })

        // Launch-time cleanup: drop in-flight download partials for any
        // deprecated model (generic — driven by CatalogEntry.deprecated), so a
        // retired model can't resume a stale partial download. No-op for live
        // models and for fully-installed deprecated models (their install is
        // kept; only the .partial / .tmp staging artifacts are removed). File
        // unlinks are O(1), so this is negligible on cold start.
        TranslationBackendRegistry.orderedBackends()
            .filterIsInstance<com.playtranslate.translation.llm.OnDeviceLlmBackend>()
            .forEach { it.cleanupPartialsIfDeprecated() }
        // Same launch-time-cleanup rationale for game-audio snapshots: the
        // in-session sweep runs only when a NEW snapshot is taken, so a
        // ~16 MB zombie from a killed-and-never-restored card flow would
        // otherwise linger until the OS purges the cache. A couple of stats
        // + unlinks; negligible on cold start.
        com.playtranslate.capture.GameAudioSnapshot.sweepOrphans(this)
        // Track the currently-resumed PlayTranslate activity so display-id
        // queries always reflect the live state instead of a value cached
        // at lifecycle boundaries — Android can move an activity between
        // displays without firing onPause/onResume when configChanges
        // swallows the screenLayout swap, leaving any cached id stale.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                resumedActivity = WeakReference(activity)
                CaptureService.instance?.reconcileLiveModes("activityResumed=${activity.javaClass.simpleName}")
                CaptureService.instance?.reconcileGameAudio()
                drainPendingForegroundOps(activity)
            }
            override fun onActivityPaused(activity: Activity) {
                if (resumedActivity?.get() === activity) {
                    resumedActivity = null
                    CaptureService.instance?.reconcileLiveModes("activityPaused=${activity.javaClass.simpleName}")
                    CaptureService.instance?.reconcileGameAudio()
                }
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
        // Right-stick scrolling on every in-app page — one Window.Callback
        // wrap per activity, so no page implements it.
        com.playtranslate.ui.ActivityStickScroll.install(this)
    }

    companion object {
        /** Single-slot tracker for the currently-resumed PlayTranslate
         *  activity. Treats "PlayTranslate is on display X" as a 1-element
         *  set, which is correct for our usage: MainActivity launches
         *  TranslationResultActivity / WordAnkiReviewActivity / etc. with
         *  FLAG_ACTIVITY_NEW_TASK — they replace the foreground rather
         *  than running alongside, so at most one of our activities is in
         *  RESUMED state at a time. Multi-resume (Android 10+ split-screen
         *  with two of OUR activities resumed on different displays) is
         *  not enabled by our manifest and not exercised by any code path
         *  here. If that ever changes, switch to a Set<Activity> keyed by
         *  identity and have foregroundDisplayId return Set<Int>. */
        @Volatile
        private var resumedActivity: WeakReference<Activity>? = null

        /** Display id whichever PlayTranslate activity is currently resumed
         *  is showing on, or null if none is. Live-read via
         *  [Activity.getDisplay] — no cached value, so an in-place display
         *  swap (no onPause/onResume) is reflected immediately. */
        fun foregroundDisplayId(): Int? {
            val act = resumedActivity?.get() ?: return null
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) act.display?.displayId
            else @Suppress("DEPRECATION") act.windowManager.defaultDisplay.displayId
        }

        /** Simple class name of the currently-resumed PlayTranslate activity,
         *  or null when none is. The game-audio recorder matches this against
         *  its card-flow pause set. */
        fun resumedActivitySimpleName(): String? =
            resumedActivity?.get()?.javaClass?.simpleName

        /** Pre-populate the resumed-activity registry from inside an
         *  activity's own onResume, *before* anything in that resume path
         *  triggers a reconcile that reads [foregroundDisplayId]. The
         *  framework's [ActivityLifecycleCallbacks.onActivityResumed]
         *  doesn't fire until after [Activity.onResume] returns, so
         *  [MainActivity.isInForeground]'s setter (which fires
         *  reconcileLiveModes from inside onResume) would otherwise see a
         *  null display id and let live mode capture the app's own
         *  display for one cycle. The Application-level callback still
         *  runs idempotently afterwards. */
        fun markResumed(activity: Activity) {
            resumedActivity = WeakReference(activity)
        }

        /** Ops queued by [runWithForegroundActivity] when no PlayTranslate
         *  activity is currently resumed. Drained on the next
         *  [ActivityLifecycleCallbacks.onActivityResumed]. Main-thread-only;
         *  no synchronization. */
        private val pendingForegroundOps = mutableListOf<(Activity) -> Unit>()

        /** Runs [block] with the currently-resumed PlayTranslate activity,
         *  or defers it until one resumes. Used by [OverlayAlert] /
         *  [OverlayProgress] so an alert shown before MainActivity has
         *  reached RESUMED (e.g. fired from onCreate) still attaches once
         *  the activity is visible — instead of being lost.
         *
         *  Main-thread-only. Multiple deferred ops fire in registration
         *  order on the same resume.
         */
        fun runWithForegroundActivity(block: (Activity) -> Unit) {
            val activity = resumedActivity?.get()
            if (activity != null) block(activity)
            else pendingForegroundOps.add(block)
        }

        private fun drainPendingForegroundOps(activity: Activity) {
            if (pendingForegroundOps.isEmpty()) return
            val drained = pendingForegroundOps.toList()
            pendingForegroundOps.clear()
            drained.forEach { it(activity) }
        }
    }

    /**
     * Drop cached ML Kit OCR recognizers when the system signals the process
     * is at the top of the background LRU kill list. A foreground service
     * keeps the process out of that bucket, so this only fires when our
     * CaptureService has stopped — guaranteeing no recognise() call is in
     * flight to race with the close. See [OcrManager.releaseAll] for why
     * uninstall paths can't free recognizers directly.
     *
     * Skipped in debug builds because the "Show OCR boxes" debug overlay
     * (gated to BuildConfig.DEBUG in SettingsRenderer) drives an OCR loop
     * out of the accessibility service, which has no foreground-service
     * weight class. With that loop running, the process can hit
     * TRIM_MEMORY_COMPLETE while OcrManager.recognise() is mid-call. The
     * cache is bounded at one recognizer per backend (~5 entries); the
     * dev-only "leak" isn't worth the complexity of refcounting.
     */
    // Suppress TRIM_MEMORY_COMPLETE's API-35 deprecation: Android 15+ stopped
    // delivering most TRIM_MEMORY_* levels (the OS reclaims memory itself
    // instead of asking apps). There's no replacement signal for "you're at
    // the top of the kill list" on newer OS versions, but the cleanup remains
    // useful on Android 11–13 (the device class the project's retro-handheld
    // userbase actually runs). Graceful degradation when the signal doesn't
    // fire — onTrimMemory just isn't called, no caller depends on it.
    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (BuildConfig.DEBUG) return
        if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            OcrManager.instance.releaseAll()
            // OCR pack *disk* reclaim is intentionally NOT done here: freeing disk
            // doesn't relieve RAM pressure, and an unguarded sweep at TRIM can race
            // a backgrounded in-flight pack download (deleting a just-installed
            // pack). Orphaned OCR packs are swept at the next launch instead
            // (MainActivity, post-settle quiescence) — see OcrModelManager.sweepOrphans.
            // Drop the on-device LLM model + KV cache / scratch (E2B's working
            // set is ~3.3 GB on Thor; Qwen-MNN's is ~1 GB). At
            // TRIM_MEMORY_COMPLETE we're at the top of the LRU kill list;
            // freeing now might defer the kill, and if it doesn't we lose
            // nothing. Mutex-serialized inside [MnnTranslator.unloadModel]
            // so it can't race an in-flight translate(). Async because the
            // engine's cleanUp() does runBlocking on its own dispatcher and
            // we don't want to ANR the main thread that delivered onTrimMemory.
            appScope.launch {
                MnnTranslator.getInstance(this@PlayTranslateApplication).unloadModel()
            }
        }
    }
}
