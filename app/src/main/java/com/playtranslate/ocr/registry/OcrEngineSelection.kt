package com.playtranslate.ocr.registry

import android.os.Process
import com.playtranslate.ocr.composites.DetectThenRecognize
import com.playtranslate.ocr.core.OcrEngine
import com.playtranslate.ocr.core.TextDetector
import com.playtranslate.ocr.core.TextRecognizer
import com.playtranslate.ocr.mangaocr.MangaOcrBridge
import com.playtranslate.ocr.meiki.MeikiBridge
import com.playtranslate.ocr.paddle.PaddleOcrBridge

/**
 * Debug-only OCR engine choice (the Settings "OCR engine" picker). [DEFAULT] is
 * the production ML Kit path; the rest are on-device experiments. manga-ocr is
 * recognition-only, so it appears twice — paired with each detector.
 */
enum class DebugOcrEngine { DEFAULT, PADDLE, MEIKI, MEIKI_MANGA, PADDLE_MANGA }

/**
 * Process-wide selected debug OCR engine + a lazily-built, cached composite for
 * it. [OcrEngineRegistry] consults [debugEngineOrNull] before falling back to ML
 * Kit. arm64 + JA gated; any missing model/session returns null → ML Kit (never
 * crashes). Replaces the old single `debugUsePaddleOcr` boolean.
 *
 * Composites are built from the bridges' building blocks (each bridge owns its
 * MNN session); the chosen composite is cached and reused across captures (so the
 * recognizers' per-frame Mat caches persist instead of leaking). Changing [engine]
 * — or [invalidate] after a Paddle session rebuild — drops the cached composite.
 */
object OcrEngineSelection {

    @Volatile
    var engine: DebugOcrEngine = DebugOcrEngine.DEFAULT
        set(value) {
            if (field != value) synchronized(this) {
                field = value; built?.close(); built = null; builtFor = null
            }
        }

    private var built: OcrEngine? = null
    private var builtFor: DebugOcrEngine? = null
    private val arm64: Boolean by lazy { Process.is64Bit() }

    /** The selected debug engine for [sourceLang], or null to use ML Kit. */
    @Synchronized
    fun debugEngineOrNull(sourceLang: String): OcrEngine? {
        val sel = engine
        if (sel == DebugOcrEngine.DEFAULT || !arm64 || !sourceLang.equals("ja", ignoreCase = true)) return null
        if (built != null && builtFor == sel) return built
        built?.close(); built = null; builtFor = null
        val e = when (sel) {
            DebugOcrEngine.DEFAULT -> null
            DebugOcrEngine.PADDLE -> PaddleOcrBridge.engineOrNull()
            DebugOcrEngine.MEIKI -> compose(MeikiBridge.detectorOrNull(), MeikiBridge.recognizerOrNull())
            DebugOcrEngine.MEIKI_MANGA -> compose(MeikiBridge.detectorOrNull(), MangaOcrBridge.recognizerOrNull())
            DebugOcrEngine.PADDLE_MANGA -> compose(PaddleOcrBridge.detectorOrNull(), MangaOcrBridge.recognizerOrNull())
        }
        if (e != null) { built = e; builtFor = sel }
        return e
    }

    private fun compose(d: TextDetector?, r: TextRecognizer?): OcrEngine? =
        if (d != null && r != null) DetectThenRecognize(d, r) else null

    /** Drop the cached composite (e.g. after a Paddle session rebuild) without
     *  closing the bridge sessions — they rebuild lazily on next use. */
    @Synchronized
    fun invalidate() { built?.close(); built = null; builtFor = null }

    /** Full teardown: cached composite + every bridge session. */
    @Synchronized
    fun closeAll() {
        built?.close(); built = null; builtFor = null
        PaddleOcrBridge.close(); MeikiBridge.close(); MangaOcrBridge.close()
    }
}
