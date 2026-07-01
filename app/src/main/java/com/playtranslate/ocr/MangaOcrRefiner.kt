package com.playtranslate.ocr

import android.graphics.Bitmap
import android.util.Log
import com.playtranslate.language.SourceLanguageProfiles
import com.playtranslate.language.TextOrientation
import com.playtranslate.ocr.core.DetectedRegion
import com.playtranslate.ocr.core.LayoutGroup
import com.playtranslate.ocr.core.OcrImage
import com.playtranslate.ocr.core.RecognizedLine
import com.playtranslate.ocr.core.RecognizedTextNormalizer
import com.playtranslate.ocr.core.TextRecognizer
import com.playtranslate.ocr.mangaocr.MangaOcrBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * Optional post-layout refinement: re-read the selected engine's regions with the
 * manga-ocr specialist and splice its (better) reading back in. Invoked by
 * [OcrPipeline] AFTER [com.playtranslate.ocr.core.LayoutAnalyzer.analyze], so it runs
 * once and both `recognise` (translate) and `recogniseWithPositions` (drag-lookup)
 * inherit the result. Coordinates stay in engine-input space (same as [LayoutGroup]
 * and the `processed` bitmap), so the caller's existing scaleFactor projection is
 * untouched.
 *
 * **Line-level, vertical only.** manga-ocr's win is vertical/stylized Japanese; on
 * horizontal lines the base engines are as good and manga-ocr's 224² square resize
 * squishes long lines. Per-line keeps each crop a single column (its strong input
 * shape) and avoids the [MangaOcrSession] 64-token cap a whole-block crop would hit.
 *
 * **Preserve real boxes; replace only on change.** If manga-ocr's text matches the
 * base line, keep the base [RecognizedLine] verbatim — its real char boxes beat the
 * synthesized even spread. If the text differs, adopt manga-ocr's line (text +
 * synthesized chars); the base char boxes labeled the now-replaced glyphs anyway.
 * Group text is rebuilt by re-joining the refined lines with the same join the layout
 * used (space-separated vs not, per source language).
 *
 * **Concurrency.** The session, its serializing lock, and its teardown are all owned by
 * [MangaOcrBridge]; this refiner never touches a raw session. It runs each frame inside
 * [MangaOcrBridge.withRecognizer], which holds the bridge lock for the whole scope — so
 * overlapping frames (a live capture vs a drag-lookup) serialize, and an interactive
 * close can't tear the session down mid-decode. The lifecycle race is structural, not
 * discipline: there is no way to obtain the session outside that lock.
 */
object MangaOcrRefiner {

    private const val TAG = "MangaOcrRefiner"

    /**
     * Returns [groups] with vertical lines re-read by manga-ocr where it disagrees with
     * the base engine. A no-op (returns the input) when the model isn't loadable —
     * [MangaOcrBridge.withRecognizer] yields null and the base result stands. The caller
     * gates on enablement / source language / ABI before invoking.
     */
    suspend fun refine(
        groups: List<LayoutGroup>,
        processed: Bitmap,
        sourceLang: String,
        logText: Boolean = false,
    ): List<LayoutGroup> =
        MangaOcrBridge.withRecognizer { recognizer ->
            runRefinement(recognizer, groups, processed, sourceLang, logText)
        } ?: groups // model not loadable — MangaOcrBridge already logged why (once); base stands

    /** Test seam: drive the transform with an injected recognizer. Production [refine]
     *  goes through [MangaOcrBridge.withRecognizer], which owns the session lock. */
    internal suspend fun refineWith(
        recognizer: TextRecognizer,
        groups: List<LayoutGroup>,
        processed: Bitmap,
        sourceLang: String,
        logText: Boolean = false,
    ): List<LayoutGroup> = runRefinement(recognizer, groups, processed, sourceLang, logText)

    /** The transform. Production runs it inside [MangaOcrBridge.withRecognizer]'s lock;
     *  the [refineWith] test seam runs it directly. Best-effort: a decode fault returns
     *  the base [groups]; cancellation still propagates. */
    private suspend fun runRefinement(
        recognizer: TextRecognizer,
        groups: List<LayoutGroup>,
        processed: Bitmap,
        sourceLang: String,
        logText: Boolean,
    ): List<LayoutGroup> {
        val t0 = System.nanoTime()
        val lineJoin =
            if (SourceLanguageProfiles.forCode(sourceLang)?.wordsSeparatedByWhitespace == true) " " else ""
        val image = OcrImage(processed, sourceLang)
        // Vertical lines == the manga-ocr decode count (horizontal is skipped). Logged
        // so a capture can be confirmed to have actually exercised manga-ocr.
        val verticalLines = groups.sumOf { g -> g.lines.count { it.orientation == TextOrientation.VERTICAL } }
        return try {
            val out = groups.map { refineGroup(it, recognizer, image, lineJoin, logText) }
            if (logText) {
                val changed = out.indices.count { out[it] !== groups[it] }
                val ms = (System.nanoTime() - t0) / 1_000_000
                val perDecode = if (verticalLines > 0) " (~${ms / verticalLines}ms/col)" else ""
                Log.i(
                    TAG,
                    "ran in ${ms}ms$perDecode: $verticalLines vertical-line decode(s), " +
                        "$changed/${groups.size} group(s) changed" +
                        if (verticalLines == 0) " (no vertical text — manga-ocr is vertical-only)" else "",
                )
            }
            out
        } catch (c: CancellationException) {
            throw c // a superseded frame's cancellation must propagate, not be swallowed
        } catch (t: Throwable) {
            // Best-effort: an OPTIONAL refinement must never sink a successful base OCR
            // pass. Keep the base groups. Don't close the session here — a transient native
            // error is better retried next capture than escalated, and closing mid-frame is
            // exactly the race MangaOcrBridge's locked teardown avoids.
            Log.w(TAG, "refinement failed — keeping base OCR result", t)
            groups
        }
    }

    private suspend fun refineGroup(
        group: LayoutGroup,
        recognizer: TextRecognizer,
        image: OcrImage,
        lineJoin: String,
        logText: Boolean,
    ): LayoutGroup {
        var changed = false
        val refinedLines = ArrayList<RecognizedLine>(group.lines.size)
        for (line in group.lines) {
            if (line.orientation != TextOrientation.VERTICAL) {
                refinedLines += line
                continue
            }
            coroutineContext.ensureActive() // stop a superseded frame promptly
            val region = DetectedRegion(box = line.box, orientation = line.orientation)
            // Run the candidate through the SAME normalizer the base lines already passed
            // (edge pipe/cursor strip, decoration-only drop, offset-safe char re-base) so
            // this opt-in path can't reinject junk after the pipeline's one cleanup stage —
            // e.g. a ▼ advance-cursor manga-ocr re-reads from the crop. Drops → keep base.
            val manga = recognizer.recognize(image, region)
                ?.let { RecognizedTextNormalizer.normalize(listOf(it), image.sourceLang).firstOrNull() }
                ?.lines?.firstOrNull()
            if (manga != null && manga.text.isNotBlank() && manga.text != line.text) {
                if (logText) Log.d(TAG, "  base='${line.text}' -> manga='${manga.text}'")
                refinedLines += manga
                changed = true
            } else {
                refinedLines += line
            }
        }
        if (!changed) return group
        val text = refinedLines.joinToString(lineJoin) { it.text }.trim()
        return group.copy(text = text, lines = refinedLines)
    }
}
