package com.playtranslate

import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks2
import android.os.Bundle
import com.playtranslate.diagnostics.CrashHandler
import com.playtranslate.translation.DeepLBackend
import com.playtranslate.translation.LingvaBackend
import com.playtranslate.translation.MlKitBackend
import com.playtranslate.translation.QwenBackend
import com.playtranslate.translation.TranslateGemmaBackend
import com.playtranslate.translation.TranslationBackendRegistry
import com.playtranslate.translation.translategemma.LlamaTranslator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

class PlayTranslateApplication : Application() {

    /** Application-scoped coroutine scope for fire-and-forget background work
     *  (onTrimMemory unloads, etc.). Outlives any individual UI lifecycle.
     *  IO dispatcher because LLM unload has to wait on the engine's
     *  llamaDispatcher and shouldn't tie up the main thread. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
        }
        // Build the translation-backend registry once at process start.
        // Backends are stateless or hold pooled HTTP clients that should
        // outlive a single CaptureService instance. The DeepL key is read
        // via closure each call so a Settings change propagates without
        // rebuilding the registry.
        TranslationBackendRegistry.init(
            listOf(
                DeepLBackend(
                    keyProvider     = { Prefs(this).deeplApiKey },
                    enabledProvider = { Prefs(this).deeplEnabled },
                ),
                LingvaBackend(enabledProvider = { Prefs(this).lingvaEnabled }),
                TranslateGemmaBackend(
                    context         = this,
                    enabledProvider = { Prefs(this).translateGemmaEnabled },
                ),
                QwenBackend(
                    context         = this,
                    enabledProvider = { Prefs(this).qwenEnabled },
                ),
                MlKitBackend(),
            )
        )
        // Track the currently-resumed PlayTranslate activity so display-id
        // queries always reflect the live state instead of a value cached
        // at lifecycle boundaries — Android can move an activity between
        // displays without firing onPause/onResume when configChanges
        // swallows the screenLayout swap, leaving any cached id stale.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                resumedActivity = WeakReference(activity)
                CaptureService.instance?.reconcileLiveModes("activityResumed=${activity.javaClass.simpleName}")
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
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (BuildConfig.DEBUG) return
        if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            OcrManager.instance.releaseAll()
            // Drop the on-device LLM model + KV cache / scratch (~300-400 MB
            // of real allocations on top of mmap'd weights). At
            // TRIM_MEMORY_COMPLETE we're at the top of the LRU kill list;
            // freeing now might defer the kill, and if it doesn't we lose
            // nothing. Mutex-serialized inside [LlamaTranslator.unloadModel]
            // so it can't race an in-flight translate(). Async because the
            // engine's cleanUp() does runBlocking on its own dispatcher and
            // we don't want to ANR the main thread that delivered onTrimMemory.
            appScope.launch {
                LlamaTranslator.getInstance(this@PlayTranslateApplication).unloadModel()
            }
        }
    }
}
