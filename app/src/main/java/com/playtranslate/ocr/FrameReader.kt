package com.playtranslate.ocr

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.playtranslate.OcrManager
import com.playtranslate.OcrTokenScope
import com.playtranslate.TranslationCache
import com.playtranslate.language.SourceLangId
import com.playtranslate.language.SourceLanguageProfiles
import com.playtranslate.ocr.core.RegionPreFilter
import com.playtranslate.ocr.registry.OcrModelManager
import com.playtranslate.ocr.registry.ocrLabel
import com.playtranslate.ocr.registry.selectionToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.IOException

private const val TAG = "FrameReader"

/** Who read the frame, for OcrProvenance ("Scanned by …" + the gear's picker key). */
data class ReaderAttribution(val label: String, val token: String)

/** What a reader's translations hold at one moment, by position. [settled]: nothing more will arrive. */
data class ReaderProgress(val byPosition: Map<Int, String>, val settled: Boolean)

/**
 * The translations a reader produces alongside its OCR (a combined OCR and
 * translation service), as they arrive, for the pair ([source] = the app's
 * translationCode, [target] = the app's target code) the reader sent;
 * [providerName] is the "Translated by" name. Keyed by position in a list of
 * texts: the reader's own groups, or a consumer's own list after
 * [forPositions].
 *
 * Three kinds of text, all held in one state keyed by the reader's group
 * index and shared by the root and every view of it: [ready], what arrived
 * with the OCR, final and present in every state; what the reader delivers
 * provisionally on the way ([deliver]); and the reader's final word
 * ([settle]), after which nothing changes. A blank text is no text: it is
 * dropped wherever it comes in, so a position the reader translated to a
 * blank goes where an unnamed position goes (the cache and the waterfall)
 * instead of pinning a box blank, which a live tier would retry every cycle.
 *
 * The reader's work runs in the job the root is built with, a child of the
 * caller's job that never fails and settles these translations however it
 * ends; whoever drops them without awaiting them cancels it ([cancel]).
 */
class ReaderTranslations private constructor(
    val source: String,
    val target: String,
    val providerName: String,
    private val shared: Shared,
    /** Each of this object's positions mapped to a reader group index; null =
     *  this position takes none. */
    private val groupOf: List<Int?>,
) {

    /** The root's state, shared by every view: one writer (the reader), any
     *  number of readers on any thread. */
    private class Shared(val readyByGroup: Map<Int, String>, val job: Job, val configGeneration: Int) {
        val state = MutableStateFlow(ReaderProgress(readyByGroup, settled = false))
    }

    /** The root, for the reader that produced them: [groupCount] groups, of
     *  which [ready] (by group index) arrived with the OCR. [configGeneration]
     *  is the shared cache's ([TranslationCache.configGeneration]) when the
     *  reader's request went out. Its default, the generation at this call,
     *  suits a reader that builds these as it reads (a scripted one); a
     *  reader that builds them after its request returns stamps the
     *  generation before sending it
     *  ([com.playtranslate.cloud.PtCloudFrameReader]). */
    constructor(
        source: String,
        target: String,
        providerName: String,
        groupCount: Int,
        ready: Map<Int, String>,
        job: Job,
        configGeneration: Int = TranslationCache.shared.configGeneration,
    ) : this(
        source, target, providerName,
        Shared(ready.filterValues { it.isNotBlank() }, job, configGeneration),
        List(groupCount) { it },
    )

    /** The configuration these translations were made under: the translation
     *  cache's configuration generation when the reader's request went out,
     *  not when a consumer takes them. A consumer caches them only while it
     *  still stands ([TranslationCache.setIfConfigUnchanged]), so a settings
     *  clear between the request and the consumer's call (the cloud's switch
     *  flipped while a capture reads) shows them but never caches them. */
    val configGeneration: Int get() = shared.configGeneration

    /** Whether these translations are for the pair ([source], [target]) the
     *  consumer translates under. The pair can change between the read and the
     *  translations' arrival; a text translated into the old target must never
     *  be shown, or cached, under the new one. */
    fun isFor(source: String, target: String): Boolean =
        this.source == source && this.target == target

    /** By position, final: what arrived with the OCR. */
    val ready: Map<Int, String> = byPosition(shared.readyByGroup)

    /** By position: the translations as they stand now. */
    val now: ReaderProgress
        get() = shared.state.value.let { ReaderProgress(byPosition(it.byPosition), it.settled) }

    /** The positions that take a group that did not arrive with the OCR:
     *  fixed at construction, since [ready] never changes. */
    private val notReady: Set<Int> =
        groupOf.indices.filterTo(HashSet()) { position -> groupOf[position]?.let { it !in shared.readyByGroup } == true }

    /** The positions waiting can still name: those that take a group that did
     *  not arrive with the OCR. Empty once settled, and empty when every
     *  position that takes a group arrived with the OCR. A consumer that
     *  already has a translation for each of them (the app's cache) has
     *  nothing to wait for. */
    val awaited: Set<Int>
        get() = if (shared.state.value.settled) emptySet() else notReady

    /** True while waiting can still name one of this object's positions
     *  ([awaited] is not empty): a consumer then has something to wait for. */
    val pending: Boolean
        get() = awaited.isNotEmpty()

    /** By position, what is final now: every translation once settled; until
     *  then only what arrived with the OCR ([ready]), since a provisional
     *  delivery can still be replaced or dropped. What a consumer takes when
     *  it does not wait. */
    val finalSoFar: Map<Int, String>
        get() = shared.state.value.let { if (it.settled) byPosition(it.byPosition) else ready }

    /**
     * The translations by position once nothing more can arrive for this
     * object's positions: at once ([finalSoFar]) when not [pending], else
     * when the reader settles. On the way, [onMore] is called with the
     * cumulative by-position map each time it differs from the last one
     * reported (starting from [ready]); the settled map itself is never
     * reported through it, since this returns it. The state is collected on
     * the caller's dispatcher, so [onMore] runs there too.
     */
    suspend fun await(onMore: (suspend (Map<Int, String>) -> Unit)? = null): Map<Int, String> {
        if (!pending) return finalSoFar
        var reported = ready
        return shared.state
            .map { ReaderProgress(byPosition(it.byPosition), it.settled) }
            .onEach { progress ->
                if (!progress.settled && progress.byPosition != reported) {
                    reported = progress.byPosition
                    onMore?.invoke(progress.byPosition)
                }
            }
            .first { it.settled }
            .byPosition
    }

    /** These translations for a consumer's own list: position i takes this
     *  object's position `groupOf[i]` (composed through this object's own
     *  mapping; an index outside it, or null, takes none). Null when no
     *  position maps to a group, so a caller holds no reader at all rather
     *  than one that can name nothing. The view shares this object's state,
     *  pair, provider and job. */
    fun forPositions(groupOf: List<Int?>): ReaderTranslations? {
        val composed = groupOf.map { position -> position?.let { this.groupOf.getOrNull(it) } }
        if (composed.all { it == null }) return null
        return ReaderTranslations(source, target, providerName, shared, composed)
    }

    /** For the reader that made them: the provisional translations so far, by
     *  group index. Ignored once settled. */
    fun deliver(byGroup: Map<Int, String>) {
        shared.state.update { if (it.settled) it else ReaderProgress(merged(byGroup), settled = false) }
    }

    /** For the reader that made them: its final word, by group index, after
     *  which nothing changes; a second settle is ignored. With nothing, the
     *  reader delivered no more than [ready] (it failed, was deferred,
     *  skipped or cancelled, or its read ended), and anything it delivered
     *  provisionally is dropped. */
    fun settle(byGroup: Map<Int, String> = emptyMap()) {
        shared.state.update { if (it.settled) it else ReaderProgress(merged(byGroup), settled = true) }
    }

    /** Cancels the reader's work. */
    fun cancel() {
        shared.job.cancel()
    }

    /** [byGroup]'s non-blank texts with the ready ones over them: what
     *  arrived with the OCR is final and stays in every state. */
    private fun merged(byGroup: Map<Int, String>): Map<Int, String> =
        byGroup.filterValues { it.isNotBlank() } + shared.readyByGroup

    private fun byPosition(byGroup: Map<Int, String>): Map<Int, String> {
        val out = HashMap<Int, String>()
        groupOf.forEachIndexed { position, group ->
            group?.let { byGroup[it] }?.let { out[position] = it }
        }
        return out
    }
}

/**
 * One read of one frame: what was recognized, who recognized it, and the
 * translations the reader produces for it, if it produces any.
 */
class FrameReading(
    /** The recognized groups, in the read bitmap's coordinates; null = no text found. */
    val ocr: OcrManager.OcrResult?,
    /** Null = no engine identity (the empty engine), which leaves the result's
     *  provenance null as it always has. Set for a no-text read too, naming who looked. */
    val attribution: ReaderAttribution?,
    /** Null when the reader only reads. Otherwise keyed by the reader's group
     *  index (= its position in [ocr]'s groups), and its job is a child of the
     *  caller's job that never fails: whoever drops the reading without
     *  awaiting the translations cancels them. */
    val translations: ReaderTranslations?,
)

/**
 * What a read needs besides the pixels. [screenshotWidth] is the full frame's
 * width (the grouping's menu-split heuristic reads it); [frameId] names the
 * read in a reader's own logs and requests.
 *
 * The last three are the device engine's facts, which a device read passes
 * to [OcrManager.recognise] and a reader with no engine of its own ignores
 * ([com.playtranslate.cloud.PtCloudFrameReader]; the server has no option
 * for any of them): [scope] names whose OCR selection runs, the global one
 * or a tool's own (the camera's, the import tool's), and keys a no-text
 * read's attribution to the same selection; [regionPreFilter] is a
 * composite engine's recognition-cost saver for a crop region (the group
 * gate stays the caller's); [documentLayoutBias] is the import tool's
 * page-rhythm grouping prior for declared documents.
 *
 * [beforeDeviceOcr] is called by the device reader, whether it reads the
 * frame outright or as a cloud read's fallback ([FallbackFrameReader] hands
 * it the same request), just before its engine runs, on the caller's
 * dispatcher and in the caller's job: where a consumer starts its slow-OCR
 * timer (so the time a cloud attempt spent on the network never counts and
 * a fallback's device read does) and where the camera waits for its engine
 * prewarm. A reader with no device engine never calls it.
 */
data class ReadRequest(
    val sourceLangId: SourceLangId,
    val screenshotWidth: Int,
    val targetLang: String,
    val frameId: String,
    val scope: OcrTokenScope = OcrTokenScope.GLOBAL,
    val regionPreFilter: RegionPreFilter? = null,
    val documentLayoutBias: Boolean = false,
    val beforeDeviceOcr: (suspend () -> Unit)? = null,
) {
    companion object {
        /** The request of a consumer that wants the frame's text and no
         *  translation (the drag lookup's [readLines], the furigana tier,
         *  the camera's furigana acquire): the source's own translationCode
         *  as the target, the same-language pair, for which a translating
         *  reader asks its service for none
         *  ([com.playtranslate.cloud.PtCloudFrameReader] sends `target`
         *  equal to `source`, and the server skips translation). The
         *  engine's facts default as in the constructor. */
        fun sameLanguage(
            sourceLangId: SourceLangId,
            screenshotWidth: Int,
            frameId: String,
            scope: OcrTokenScope = OcrTokenScope.GLOBAL,
            regionPreFilter: RegionPreFilter? = null,
            documentLayoutBias: Boolean = false,
        ) = ReadRequest(
            sourceLangId,
            screenshotWidth = screenshotWidth,
            targetLang = SourceLanguageProfiles[sourceLangId].translationCode,
            frameId = frameId,
            scope = scope,
            regionPreFilter = regionPreFilter,
            documentLayoutBias = documentLayoutBias,
        )
    }
}

/**
 * The seam between a capture and whatever reads its pixels: the on-device OCR
 * engines today, a combined OCR and translation service behind the same call.
 * The capture-once paths, the press-and-hold overlay and the live tiers read
 * through it (every live tier may read through the service; the furigana
 * tier asks for text alone), and so does the drag lookup,
 * which reads lines through [readLines] and takes no translations; the
 * attribution a reading carries is the one place a result's "Scanned by"
 * comes from. A reader that also translates hands its translations over
 * through the reading ([ReaderTranslations]): every consumer that translates
 * takes what arrived with the OCR in its first paint, waits for the rest only
 * when the reader can still name a text the app's cache has no translation
 * for, fills each further one in place as it arrives while it waits, and
 * sends whatever the reader did not translate through the cache and the
 * waterfall.
 */
interface FrameReader {
    /** Reads [bitmap] (the OCR input crop, in whose coordinates the result's boxes are).
     *  A reader that streams translations launches that work as a child of the CALLER's
     *  job, so the caller's cancellation cancels it and the caller's completion waits
     *  for it, and settles them however that work ends, so nobody awaiting them can
     *  wait on work that is over; a reader that cannot read at all throws an
     *  IOException (fallback readers key on that), never for cancellation. */
    suspend fun read(bitmap: Bitmap, request: ReadRequest): FrameReading
}

/**
 * The lines this reader finds in [bitmap] ([OcrManager.OcrResult.lines]), for
 * a consumer that looks words up in the frame and translates nothing
 * (drag-to-lookup); null = no text found. [frameId] is the request's
 * [ReadRequest.frameId].
 *
 * The request is the same-language pair ([ReadRequest.sameLanguage]), for
 * which a translating reader asks its service for no translation, so a
 * lookup never pays for one. Its screenshotWidth is
 * 0, no menu split, as a lookup has always read on the device. The reader's
 * translations, if it hands any over, are cancelled, since nothing awaits
 * them (the seam's rule: whoever drops a reading without awaiting its
 * translations cancels them).
 *
 * Like [FrameReader.read], call it directly in the job meant to own the
 * reader's work, never inside a scoped builder (`withContext`,
 * `coroutineScope`, `withTimeout`): that work is a child of the calling job,
 * and a scope waits for its children.
 */
suspend fun FrameReader.readLines(bitmap: Bitmap, sourceLangId: SourceLangId, frameId: String): List<OcrManager.OcrLine>? {
    val reading = read(bitmap, ReadRequest.sameLanguage(sourceLangId, screenshotWidth = 0, frameId = frameId))
    reading.translations?.cancel()
    return reading.ocr?.lines()?.ifEmpty { null }
}

/**
 * The on-device reader: [OcrManager.recognise] with the engine selected for the
 * request's language. It only reads; translation is the caller's waterfall.
 *
 * The whole read runs on [Dispatchers.Default]. Every caller reads from a
 * Main-dispatched scope (the service's capture and hold cycles, both live
 * tiers, the drag lookup), and [OcrManager.recognise] hops only for the
 * recognition itself: the result's projection into groups, lines and symbols
 * resumes on the caller, and a no-text read's backend resolution
 * ([noTextAttribution]) reads pack sentinels from disk. One hop here keeps
 * both off the UI thread for every device read.
 */
class DeviceFrameReader(
    private val ocrManager: OcrManager,
    private val context: Context,
) : FrameReader {

    override suspend fun read(bitmap: Bitmap, request: ReadRequest): FrameReading {
        // On the caller's dispatcher, before the hop: the engine is about to
        // run (the slow-OCR timers start here, the camera's prewarm is
        // awaited here).
        request.beforeDeviceOcr?.invoke()
        return withContext(Dispatchers.Default) {
            val ocr = ocrManager.recognise(
                bitmap, request.sourceLangId, screenshotWidth = request.screenshotWidth,
                regionPreFilter = request.regionPreFilter,
                scope = request.scope,
                documentLayoutBias = request.documentLayoutBias,
            )
            // Text found: the engine that produced it (null for the empty engine, which
            // keeps today's null provenance). Nothing found: the engine that looked.
            val attribution = if (ocr != null) attributionOf(ocr, context)
                else noTextAttribution(context, request.sourceLangId, request.scope)
            FrameReading(ocr, attribution, translations = null)
        }
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
         *  (and ran, finding nothing) for [srcId] under [scope], so the "switch OCR
         *  tool" gear can re-OCR the capture with another one. Null when no OCR
         *  backend is available (a no-floor language on a 32-bit device). Public
         *  because the live tiers' no-text panel state derives from it. */
        fun noTextAttribution(
            ctx: Context,
            srcId: SourceLangId,
            scope: OcrTokenScope = OcrTokenScope.GLOBAL,
        ): ReaderAttribution? {
            val backend = OcrModelManager.selectedBackend(ctx, srcId, scope) ?: return null
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
 * The fallback's reading is returned as it is. Why the primary could not
 * read is not carried on it: a cloud read's failure has already recorded its
 * cooldown and reported its attempt
 * ([com.playtranslate.cloud.PtCloudClient.postFrame]), which is how the
 * router and the error pill learn of it; a cloud read refused before its
 * upload ([com.playtranslate.cloud.PtCloudRefusedException], the router's
 * question asked again after the encode) has nothing to record.
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
        fallback.read(bitmap, request)
    }
}
