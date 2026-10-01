package com.playtranslate.ocr

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.playtranslate.OcrManager
import com.playtranslate.language.SourceLangId
import com.playtranslate.ocr.registry.OcrModelManager
import com.playtranslate.ocr.registry.ocrLabel
import com.playtranslate.ocr.registry.selectionToken
import com.playtranslate.translation.BackendFailure
import com.playtranslate.translation.ClassifiedFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException

private const val TAG = "FrameReader"

/** Who read the frame, for OcrProvenance ("Scanned by …" + the gear's picker key). */
data class ReaderAttribution(val label: String, val token: String)

/** Translations a reader produced alongside its OCR (a combined OCR+MT service). */
sealed interface ProvidedTranslations {
    /** Group index (= the reader's group id) to translation, translated under the pair
     *  ([source] = the app's translationCode, [target] = the app's target code) the
     *  reader sent; [providerName] is the "Translated by" name. A consumer under any
     *  other pair must ignore it. */
    data class Ok(
        val byGroup: Map<Int, String>,
        val source: String,
        val target: String,
        val providerName: String,
    ) : ProvidedTranslations {
        /** Whether these translations are for the pair ([source], [target]) the
         *  consumer translates under. The pair can change between the read and the
         *  translations' arrival; a text translated into the old target must never
         *  be shown, or cached, under the new one. */
        fun isFor(source: String, target: String): Boolean =
            this.source == source && this.target == target
    }

    /** The reader produced none (deferred, failed, skipped, connection ended): the caller translates locally. */
    data class Unavailable(val reason: String) : ProvidedTranslations
}

/**
 * One read of one frame: what was recognized, who recognized it, and the
 * translations the reader will produce for it, if it produces any.
 */
class FrameReading(
    /** The recognized groups, in the read bitmap's coordinates; null = no text found. */
    val ocr: OcrManager.OcrResult?,
    /** Null = no engine identity (the empty engine), which leaves the result's
     *  provenance null as it always has. Set for a no-text read too, naming who looked. */
    val attribution: ReaderAttribution?,
    /** Null when the reader only reads. Otherwise a child of the caller's job that
     *  never fails: whoever drops the reading without awaiting it cancels it. */
    val translations: Deferred<ProvidedTranslations>?,
    /** Why the primary could not read, when this reading came from the fallback
     *  ([FallbackFrameReader]); null when the primary read it, or when the
     *  primary's failure carried no classification. */
    val fallbackFrom: BackendFailure? = null,
)

/** What a read needs besides the pixels. [screenshotWidth] is the full frame's width
 *  (the grouping's menu-split heuristic reads it); [frameId] names the read in a
 *  reader's own logs and requests. */
data class ReadRequest(
    val sourceLangId: SourceLangId,
    val screenshotWidth: Int,
    val targetLang: String,
    val frameId: String,
)

/**
 * The seam between a capture and whatever reads its pixels: the on-device OCR
 * engines today, a combined OCR and translation service behind the same call.
 * The capture-once paths, the press-and-hold overlay and the reconciler live tier
 * read through it; the attribution a reading carries is the one place a result's
 * "Scanned by" comes from.
 */
interface FrameReader {
    /** Reads [bitmap] (the OCR input crop, in whose coordinates the result's boxes are).
     *  A reader that streams translations launches that work as a child of the CALLER's
     *  job, so the caller's cancellation cancels it and the caller's completion waits
     *  for it; a reader that cannot read at all throws an IOException (fallback readers
     *  key on that), never for cancellation. */
    suspend fun read(bitmap: Bitmap, request: ReadRequest): FrameReading
}

/**
 * The on-device reader: [OcrManager.recognise] with the engine selected for the
 * request's language. It only reads; translation is the caller's waterfall.
 */
class DeviceFrameReader(
    private val ocrManager: OcrManager,
    private val context: Context,
) : FrameReader {

    override suspend fun read(bitmap: Bitmap, request: ReadRequest): FrameReading {
        val ocr = ocrManager.recognise(
            bitmap, request.sourceLangId, screenshotWidth = request.screenshotWidth,
        )
        // Text found: the engine that produced it (null for the empty engine, which
        // keeps today's null provenance). Nothing found: the engine that looked.
        val attribution = if (ocr != null) attributionOf(ocr, context)
            else noTextAttribution(context, request.sourceLangId)
        return FrameReading(ocr, attribution, translations = null)
    }

    companion object {
        /** The attribution of a device OCR result: its [OcrManager.OcrResult.engineBackend]'s
         *  label and selection token. Null when the result carries no engine identity (the
         *  empty engine); the result's provenance then stays null, which suppresses the
         *  source OCR row and re-OCR. Public because the live tiers' panel provenance
         *  derives from their own device results with it. */
        fun attributionOf(ocr: OcrManager.OcrResult, ctx: Context): ReaderAttribution? {
            val backend = ocr.engineBackend ?: return null
            // MangaOCR is a refinement layer over the base engine, not a selectable
            // backend: extend the display label only ("Scanned by ML Kit + MangaOCR",
            // proper nouns untranslated like ocrLabel) and keep the selection token on
            // the base engine so the re-OCR gear still keys the picker correctly.
            val label =
                if (ocr.mangaOcrUsed) "${backend.ocrLabel(ctx)} + MangaOCR" else backend.ocrLabel(ctx)
            return ReaderAttribution(label, backend.selectionToken)
        }

        /** The attribution of a device read that found NO text, where there is no
         *  [OcrManager.OcrResult] to read the engine from: the engine that WAS selected
         *  (and ran, finding nothing) for [srcId], so the "switch OCR tool" gear can
         *  re-OCR the capture with another one. Null when no OCR backend is available
         *  (a no-floor language on a 32-bit device). Public because the live tiers'
         *  no-text panel state derives from it. */
        fun noTextAttribution(ctx: Context, srcId: SourceLangId): ReaderAttribution? {
            val backend = OcrModelManager.selectedBackend(ctx, srcId) ?: return null
            return ReaderAttribution(backend.ocrLabel(ctx), backend.selectionToken)
        }
    }
}

/**
 * Reads with [primary] and, when it cannot read at all (an [IOException]), with
 * [fallback]. Cancellation is never a failure: it propagates, and a primary that
 * failed while the caller was being cancelled does not start the fallback. Any
 * other exception is a bug in the primary and propagates too.
 *
 * A reading from the fallback carries the primary's failure
 * ([FrameReading.fallbackFrom]) when the exception classified itself
 * ([ClassifiedFailure], as [com.playtranslate.cloud.PtCloudReadException]
 * does), so a caller can act on why the primary could not read.
 */
class FallbackFrameReader(
    private val primary: FrameReader,
    private val fallback: FrameReader,
) : FrameReader {

    override suspend fun read(bitmap: Bitmap, request: ReadRequest): FrameReading = try {
        primary.read(bitmap, request)
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        currentCoroutineContext().ensureActive()
        // Class name only: an exception message can carry a URL.
        Log.i(TAG, "primary reader unavailable (${e.javaClass.simpleName}); reading on device")
        val reading = fallback.read(bitmap, request)
        FrameReading(
            reading.ocr, reading.attribution, reading.translations,
            fallbackFrom = (e as? ClassifiedFailure)?.failure,
        )
    }
}

/** The per-group texts of a provided translation for [count] groups, in group order:
 *  group i gets `byGroup[i]`, a group the reader did not name gets "" (no translation,
 *  as a blank waterfall result), and ids outside `0 until count` are ignored. */
internal fun providedGroupTranslations(count: Int, byGroup: Map<Int, String>): List<String> =
    List(count) { i -> byGroup[i].orEmpty() }

/** These translations re-keyed by position in a consumer's own list: position i
 *  takes `byGroup[indices[i]]` when that group is named, and stays unnamed
 *  otherwise; the pair and the provider are kept. Null when no index is named,
 *  so a caller passes no provided translations at all rather than an empty set. */
internal fun ProvidedTranslations.Ok.forPositions(indices: List<Int>): ProvidedTranslations.Ok? {
    val byPosition = HashMap<Int, String>()
    indices.forEachIndexed { position, index ->
        byGroup[index]?.let { byPosition[position] = it }
    }
    return if (byPosition.isEmpty()) null else copy(byGroup = byPosition)
}
