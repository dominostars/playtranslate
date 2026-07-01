package com.playtranslate.ocr.mangaocr

import android.content.Context
import com.playtranslate.OcrManager
import com.playtranslate.Prefs
import com.playtranslate.language.LanguagePackStore
import com.playtranslate.language.SourceLangId
import com.playtranslate.ocr.registry.OcrPackModelHelper

/**
 * Single source of truth for wiring the "Use MangaOCR" toggle + downloaded pack into
 * the OCR pipeline. Call [refresh] whenever either input changes — app start, the
 * settings toggle, a completed download, or a pack delete — to push:
 *  - [OcrManager.mangaOcrEnabled] = the user pref, and
 *  - [MangaOcrBridge.modelDir] = the installed pack dir (or null).
 *
 * The pipeline gate (`OcrManager.shouldRefineMangaOcr`) reads both; setting modelDir
 * only when installed is also what keeps the bridge's lazy session init from latching
 * before the files exist. Closing the session is NOT done here — that happens only at
 * quiescent points (TRIM_MEMORY_COMPLETE via [OcrManager.releaseAll], or an explicit
 * pack delete) to avoid tearing a session out from under an in-flight decode.
 */
object MangaOcrProvisioning {

    const val PACK_KEY = "manga-ocr-ja"

    fun helper() = OcrPackModelHelper(PACK_KEY)

    fun refresh(ctx: Context) {
        val helper = helper()
        OcrManager.instance.mangaOcrEnabled = Prefs(ctx).useMangaOcr
        MangaOcrBridge.modelDir = if (helper.isInstalled(ctx)) helper.file(ctx) else null
        // Every provision change re-arms init, so toggle-on / download / app-start recover
        // a prior transient session-load failure (which would otherwise stay latched until
        // an explicit delete or app restart).
        MangaOcrBridge.rearmInit()
    }

    /** Reclaim the pack once Japanese is no longer an installed source language. It's
     *  deliberately outside OcrModelManager's ALL_PACK_KEYS (owned by the "Use MangaOCR"
     *  toggle, not the engine picker), so the generic orphan sweep can't see it — this is
     *  its reclaim path. Launch-time only, same quiescence as the other reclaimers. */
    fun reclaimIfSourceRemoved(ctx: Context) {
        if (SourceLangId.JA in LanguagePackStore.installedCodes(ctx)) return
        MangaOcrBridge.closeForTrim() // release any lingering session before unlinking
        helper().delete(ctx)
    }
}
