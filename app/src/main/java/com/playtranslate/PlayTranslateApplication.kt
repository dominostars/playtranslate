package com.playtranslate

import android.app.Application
import android.content.ComponentCallbacks2
import com.playtranslate.diagnostics.CrashHandler

class PlayTranslateApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashHandler.install(this)
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
        }
    }
}
