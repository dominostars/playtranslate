package com.playtranslate

import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks2
import android.os.Bundle
import com.playtranslate.capture.CaptureBackendResolver
import com.playtranslate.diagnostics.CrashHandler
import android.content.Context
import com.playtranslate.region.RegionPolicy
import com.playtranslate.translation.CooldownState
import com.playtranslate.translation.DeepLBackend
import com.playtranslate.translation.GemmaE2BMnnBackend
import com.playtranslate.translation.GeminiBackend
import com.playtranslate.translation.HyMtBackend
import com.playtranslate.translation.LingvaBackend
import com.playtranslate.translation.BergamotBackend
import com.playtranslate.translation.MlKitBackend
import com.playtranslate.translation.OpenAiBackend
import com.playtranslate.translation.QwenMnnBackend
import com.playtranslate.translation.Qwen35Mnn2bBackend
import com.playtranslate.translation.TranslationBackendRegistry
import com.playtranslate.translation.UsageTracker
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
        // Push the persisted grouping-debug flag into the process-wide
        // OcrManager singleton before any OCR can run. The SettingsRenderer
        // toggle also writes this on change, so the in-memory copy stays in
        // sync with [Prefs.debugLogGrouping] without OcrManager holding a
        // Context of its own. Gated on BuildConfig.DEBUG so a stale `true`
        // value carried over from a debug build can't leak OCR text to
        // logcat in a release upgrade — release builds also hide the Debug
        // section, so there'd be no way to turn it back off.
        if (BuildConfig.DEBUG) {
            OcrManager.instance.debugLogGroupingEnabled = Prefs(this).debugLogGrouping
            // Wire the experimental on-device OCR engines: push each bridge's
            // model dir (we have a Context here; the bridges are Context-free) +
            // the selected engine. Models are hand-pushed via adb to these dirs;
            // absent → the registry silently falls back to ML Kit. Debug-gated so
            // a stale non-DEFAULT selection can't ride into a release build.
            com.playtranslate.ocr.paddle.PaddleOcrBridge.modelDir =
                java.io.File(getExternalFilesDir(null), "paddle_models")
            com.playtranslate.ocr.paddle.PaddleOcrBridge.useServerRec = Prefs(this).debugPaddleServerRec
            com.playtranslate.ocr.paddle.PaddleOcrBridge.dumpCrops = Prefs(this).debugPaddleDumpCrops
            com.playtranslate.ocr.meiki.MeikiBridge.modelDir =
                java.io.File(getExternalFilesDir(null), "meiki_models")
            com.playtranslate.ocr.mangaocr.MangaOcrBridge.modelDir =
                java.io.File(getExternalFilesDir(null), "mangaocr_models")
            com.playtranslate.ocr.registry.OcrEngineSelection.engine = Prefs(this).debugOcrEngine
        }
        // Derive the capture backend from the granted permissions (the
        // accessibility service vs "display over other apps").
        CaptureBackendResolver.reresolve(this)
        // Build the translation-backend registry once at process start.
        // Backends are stateless or hold pooled HTTP clients that should
        // outlive a single CaptureService instance. The DeepL key is read
        // via closure each call so a Settings change propagates without
        // rebuilding the registry.
        val sharedPrefs = getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE)
        TranslationBackendRegistry.init(
            listOf(
                GeminiBackend(
                    keyProvider     = { Prefs(this).geminiApiKey },
                    enabledProvider = { Prefs(this).geminiEnabled },
                    modelProvider   = { Prefs(this).geminiModel },
                    usageTracker    = UsageTracker(sharedPrefs, "gemini"),
                    cooldownState   = CooldownState(this, "gemini"),
                ),
                OpenAiBackend(
                    id              = "openai",
                    displayName     = "OpenAI",
                    priority        = 8,
                    keyProvider     = { Prefs(this).openaiApiKey },
                    enabledProvider = { Prefs(this).openaiEnabled },
                    modelProvider   = { Prefs(this).openaiModel },
                    // Canonical OpenAI endpoint — no longer user-configurable.
                    baseUrlProvider = { "https://api.openai.com/v1" },
                    usageTracker    = UsageTracker(sharedPrefs, "openai"),
                    filterFineTunes = true,
                    cooldownState   = CooldownState(this, "openai"),
                ),
                OpenAiBackend(
                    // DeepSeek speaks the OpenAI-compatible chat-completions
                    // API; the same backend class drives it with a different
                    // base URL + key + filter setting. priority=9 puts it
                    // just below OpenAI in the waterfall (typical user adds
                    // DeepSeek as a cheaper alternative to OpenAI).
                    id              = "deepseek",
                    displayName     = "DeepSeek",
                    priority        = 9,
                    keyProvider     = { Prefs(this).deepseekApiKey },
                    enabledProvider = { Prefs(this).deepseekEnabled },
                    modelProvider   = { Prefs(this).deepseekModel },
                    baseUrlProvider = { "https://api.deepseek.com/v1" },
                    // DeepSeek splits its endpoints: /v1/chat/completions
                    // works (above) but /v1/models returns 200 + empty body.
                    // The real model-listing endpoint sits at the root.
                    modelsUrlProvider = { "https://api.deepseek.com" },
                    usageTracker    = UsageTracker(sharedPrefs, "deepseek"),
                    // DeepSeek's /models entries all have owned_by="deepseek";
                    // the OpenAI fine-tune filter would drop the whole catalog.
                    filterFineTunes = false,
                    // DeepSeek opts out of v1 cooldown: its 10-min TCP-hold
                    // makes SocketTimeoutException categorisation ambiguous
                    // (overload vs dead-key vs slow response). Revisit in v2.
                    cooldownState   = null,
                ),
                DeepLBackend(
                    keyProvider     = { Prefs(this).deeplApiKey },
                    enabledProvider = { Prefs(this).deeplEnabled },
                    cooldownState   = CooldownState(this, "deepl"),
                ),
                LingvaBackend(enabledProvider = { Prefs(this).lingvaEnabled }),
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

        // Launch-time cleanup: drop in-flight download partials for any
        // deprecated model (generic — driven by CatalogEntry.deprecated), so a
        // retired model can't resume a stale partial download. No-op for live
        // models and for fully-installed deprecated models (their install is
        // kept; only the .partial / .tmp staging artifacts are removed). File
        // unlinks are O(1), so this is negligible on cold start.
        TranslationBackendRegistry.orderedBackends()
            .filterIsInstance<com.playtranslate.translation.llm.OnDeviceLlmBackend>()
            .forEach { it.cleanupPartialsIfDeprecated() }
        // Track the currently-resumed PlayTranslate activity so display-id
        // queries always reflect the live state instead of a value cached
        // at lifecycle boundaries — Android can move an activity between
        // displays without firing onPause/onResume when configChanges
        // swallows the screenLayout swap, leaving any cached id stale.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                resumedActivity = WeakReference(activity)
                CaptureService.instance?.reconcileLiveModes("activityResumed=${activity.javaClass.simpleName}")
                drainPendingForegroundOps(activity)
            }
            override fun onActivityPaused(activity: Activity) {
                if (resumedActivity?.get() === activity) {
                    resumedActivity = null
                    CaptureService.instance?.reconcileLiveModes("activityPaused=${activity.javaClass.simpleName}")
                }
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
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
        fun foregroundDisplayId(): Int? = resumedActivity?.get()?.display?.displayId

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
