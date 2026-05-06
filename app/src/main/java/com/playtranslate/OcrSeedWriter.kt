package com.playtranslate

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Writes OCR captures to disk as seed material for the golden-set instrumented
 * test (`OcrGoldenSetTest`). Triggered from [CaptureService] when
 * [Prefs.debugSaveOcrSeed] is on.
 *
 * Each seed is two files written to `<externalFilesDir>/ocr_seeds/`:
 *  - `<timestamp>.png` — the bitmap that was actually fed to OCR (post-blackout
 *    of the floating icon, pre-preprocessing). This is what the test will
 *    re-OCR through different recipes.
 *  - `<timestamp>.txt` — the ML Kit transcription, one line per
 *    [OcrManager.LineBox]. Acts as a starting draft the user edits to produce
 *    ground-truth `<basename>.txt` files in the golden-set directory.
 *
 * Failures are caught and logged; never bubble up to the OCR pipeline.
 */
object OcrSeedWriter {

    private const val TAG = "OcrSeedWriter"
    private const val DIR_NAME = "ocr_seeds"

    fun writeSeed(context: Context, bitmap: Bitmap, ocrResult: OcrManager.OcrResult) {
        try {
            val dir = File(context.getExternalFilesDir(null), DIR_NAME)
            if (!dir.exists() && !dir.mkdirs()) {
                Log.w(TAG, "Failed to create $dir")
                return
            }
            val ts = System.currentTimeMillis()
            FileOutputStream(File(dir, "$ts.png")).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            File(dir, "$ts.txt").writeText(transcript(ocrResult))
            Log.d(TAG, "Wrote seed $ts (${ocrResult.lineBoxes.size} lines)")
        } catch (t: Throwable) {
            Log.w(TAG, "writeSeed failed", t)
        }
    }

    /** One [OcrManager.LineBox.text] per output line, in iteration order. Falls
     *  back to [OcrManager.OcrResult.fullText] if lineBoxes is empty (which
     *  shouldn't happen in practice but keeps the seed non-empty in edge cases). */
    private fun transcript(ocrResult: OcrManager.OcrResult): String {
        val fromLines = ocrResult.lineBoxes.joinToString("\n") { it.text }
        return if (fromLines.isNotBlank()) fromLines else ocrResult.fullText
    }
}
