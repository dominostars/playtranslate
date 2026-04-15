package com.playtranslate

import android.app.Application
import com.playtranslate.diagnostics.CrashHandler

class PlayTranslateApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashHandler.install(this)
    }
}
