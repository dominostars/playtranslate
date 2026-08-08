package com.playtranslate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.playtranslate.language.SourceLanguageProfiles
import com.playtranslate.ocr.core.DeskewGeometry
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

    fun writeSeed(context: Context, bitmap: Bitmap, ocrResult: OcrManager.OcrResult?) {
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
            File(dir, "$ts.groups.txt").writeText(groupsDraft(context, ocrResult))
            Log.d(TAG, "Wrote seed $ts (${ocrResult?.groups?.sumOf { it.lines.size } ?: 0} lines)")
        } catch (t: Throwable) {
            Log.w(TAG, "writeSeed failed", t)
        }
    }

    /** One [OcrManager.LineBox.text] per output line, in iteration order. Falls
     *  back to [OcrManager.OcrResult.fullText] if lineBoxes is empty. When
     *  [ocrResult] is null (recognise returned no text), writes a marker so the
     *  seed is distinguishable from a legitimate empty-text case during golden-
     *  set curation. */
    private fun transcript(ocrResult: OcrManager.OcrResult?): String {
        if (ocrResult == null) return "(no text detected — recognise returned null)"
        val fromLines = ocrResult.groups.flatMap { it.lines }.joinToString("\n") { it.text }
        return if (fromLines.isNotBlank()) fromLines else ocrResult.fullText
    }

    /** Stanza-formatted grouping draft for the OCR grouping suite
     *  (`androidTest/assets/ocr_grouping/`, judged by
     *  `scripts/build_grouping_report.py`): `# lang:` + `# surface:` directives,
     *  then one blank-line-separated stanza per group, one `text<TAB>l,t,r,b`
     *  row per line in original-bitmap coordinates. Slanted rows extend to
     *  `l,t,r,b,ang,ow,oh` (angle as a 2-decimal float, oriented dims as ints;
     *  never appended when upright) — all suite parsers accept both widths. The stanzas record the
     *  CURRENT grouping — a starting draft the curator re-stanzas into the
     *  EXPECTED grouping — so accepting it uncritically pins today's behavior,
     *  bugs included. */
    private fun groupsDraft(context: Context, ocrResult: OcrManager.OcrResult?): String {
        val lang = SourceLanguageProfiles[Prefs(context).sourceLangId].translationCode
        val sb = StringBuilder()
        sb.append("# lang: ").append(lang).append('\n')
        sb.append("# surface: screen\n")
        val groups = ocrResult?.groups.orEmpty()
        if (groups.isEmpty()) {
            sb.append("# (no groups — recognise returned ")
                .append(if (ocrResult == null) "null" else "no groups")
                .append(")\n")
            return sb.toString()
        }
        for (group in groups) {
            sb.append('\n')
            // A group with no per-line boxes still gets a stanza row so the
            // draft never silently drops content.
            val rows = group.lines.ifEmpty { null }
            if (rows == null) {
                appendRow(sb, group.text, group.bounds, group.angleDeg, group.orientedWidth, group.orientedHeight)
            } else {
                for (line in rows) {
                    appendRow(sb, line.text, line.bounds, line.angleDeg, line.orientedWidth, line.orientedHeight)
                }
            }
        }
        return sb.toString()
    }

    private fun appendRow(sb: StringBuilder, text: String, b: Rect, angleDeg: Float, ow: Float, oh: Float) {
        sb.append(text.replace('\t', ' ')).append('\t')
            .append(b.left).append(',').append(b.top).append(',')
            .append(b.right).append(',').append(b.bottom)
        // Slanted rows extend to l,t,r,b,ang,ow,oh; upright rows never do
        // (0-when-upright — the parsers key on field count).
        if (angleDeg != 0f) {
            sb.append(',').append(String.format(java.util.Locale.US, "%.2f", angleDeg))
                .append(',').append(DeskewGeometry.roundHalfUp(ow))
                .append(',').append(DeskewGeometry.roundHalfUp(oh))
        }
        sb.append('\n')
    }
}
