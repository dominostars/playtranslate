package com.playtranslate.cloud

import android.graphics.Bitmap
import com.playtranslate.OcrManager
import com.playtranslate.TranslationCache
import com.playtranslate.language.SourceLanguageProfiles
import com.playtranslate.ocr.FrameReader
import com.playtranslate.ocr.FrameReading
import com.playtranslate.ocr.ReadRequest
import com.playtranslate.ocr.ReaderAttribution
import com.playtranslate.ocr.ReaderTranslations
import com.playtranslate.translation.OnlineAttempt
import com.playtranslate.translation.llm.LlmPromptTemplates
import com.playtranslate.translation.llm.PromptKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * Reads a frame through PlayTranslate Cloud: one `POST /v1/frame` returns the
 * OCR groups (part 1, where a group the server's translation cache answered
 * already carries its translation), then an item line per further group as
 * the model completes it, then the final part with every translation. [read]
 * returns at part 1 with the reading's [FrameReading.translations], whose
 * [ReaderTranslations.ready] are part 1's translations; the items arrive in
 * them as provisional deliveries and the final part settles them, in a job
 * that is a child of the caller's job.
 *
 * Failures split at part 1, as in [PtCloudClient]. Before it, [read] throws
 * the client's [PtCloudReadException] (an IOException, which
 * [com.playtranslate.ocr.FallbackFrameReader] answers with the device reader),
 * the cooldown already recorded and the attempt already reported. After it,
 * the OCR and part 1's translations are kept and the final part's outcome
 * decides everything else: this reader records it on [PtCloudService.cooldown]
 * and reports it through [PtCloudService.report], once per frame, and a
 * translation that failed settles with part 1's translations alone, the
 * items dropped, so the caller translates the rest locally. A frame whose
 * outcome part 1 already decides needs no final part: one whose part 1
 * translated every group, and one with nothing to translate (a
 * same-language request, or no groups), whose final part the server sends at
 * once as skipped. Its success is recorded and reported, and its
 * translations settled, before [read] returns, and the client reads the
 * final part to its end whatever the caller does with the reading, so a
 * consumer that drops the translations at once (the furigana tier, the drag,
 * a reading with no text) still counts the frame. A caller that cancels (a
 * released hold, a superseded capture) records and reports nothing, and so
 * does one that drops a translating frame's translations before its final
 * part (a reading it has no more use for).
 *
 * This reader logs nothing of its own: the client's two clock lines per
 * frame and its failure line cover the request ([PtCloudClient]).
 */
class PtCloudFrameReader(
    private val client: PtCloudClient,
    private val config: PtCloudConfig,
    /** Test seam: [FrameEncoder.encode] needs native graphics. */
    private val encode: suspend (Bitmap, Int) -> EncodedFrame = FrameEncoder::encode,
) : FrameReader {

    /**
     * Uploads [bitmap] and returns once part 1 has arrived and mapped back to
     * [bitmap]'s coordinates, with the reading's translations keyed by group
     * index: part 1's as [ReaderTranslations.ready], the rest still to come
     * unless part 1 decided the frame's outcome (it translated every group,
     * or there is nothing to translate). Call it from the job meant to own
     * part 2, not inside a scoped builder: a scope waits for its children, so
     * it would return only after part 2 ([PtCloudClient.postFrame]).
     *
     * The translations are for the pair the request was sent under (the
     * source's translationCode and [ReadRequest.targetLang]); a consumer
     * under another pair ignores them ([ReaderTranslations.isFor]).
     *
     * The request's pair decides what the server is asked for. `source` is
     * the wire code of [ReadRequest.sourceLangId]; when the app's pair is
     * same-language (its translationCode equals [ReadRequest.targetLang],
     * which includes a Traditional Chinese source with a "zh" target),
     * `target` is sent equal to `source`, so the server skips translation
     * and a capture that needs none never pays for one; a lines-only read
     * ([com.playtranslate.ocr.readLines]) arrives as the same-language pair
     * for this reason. `persona` is the
     * user's System prompt override, absent without one (the server's
     * default is the app's default persona). `context` is the recent-dialogue
     * block the translation log keeps for the pair, absent when blank or
     * same-language. `stream_translations` is always true: every frame asks
     * for its item lines. `filter_furigana` is the furigana filter as the
     * device OCR reads it ([OcrManager.filterFuriganaEnabled]), sent for every
     * source since the server applies it to a Japanese source only, as the
     * device does, so a frame read here and the device's fallback read of the
     * same capture follow the same setting. The upload cap is read from the
     * config for this read.
     *
     * The attribution token "ptcloud" is unknown to the OCR picker: a result
     * read here highlights no engine there, and a re-OCR the user starts from
     * it runs on the device engine they pick (the router keeps a re-OCR on the
     * device).
     */
    override suspend fun read(bitmap: Bitmap, request: ReadRequest): FrameReading {
        // Captured here, outside any withContext: the translations' job below
        // is a child of the CALLER's job, so the caller waits for it and
        // cancels it, as postFrame's own part-2 reader is.
        val callerContext = currentCoroutineContext()
        // The configuration the request goes out under, stamped before
        // anything of it is read: a settings clear from here on (the switch
        // or the key changed while this frame reads) leaves this frame's
        // translations shown but uncached ([ReaderTranslations.configGeneration]).
        val configGeneration = TranslationCache.shared.configGeneration

        val frame = encode(bitmap, config.uploadLongSideCap)

        val source = request.sourceLangId.code
        val translationCode = SourceLanguageProfiles[request.sourceLangId].translationCode
        val sameLanguage = translationCode == request.targetLang
        val meta = FrameMeta(
            source = source,
            target = if (sameLanguage) source else request.targetLang,
            frameId = request.frameId,
            persona = LlmPromptTemplates.overrideProvider(PromptKind.SYSTEM),
            // The ring is keyed by translationCode, as the waterfall's online
            // backends ask for it; the wire's source is the OCR code.
            context = if (sameLanguage) null
                else LlmPromptTemplates.contextProvider(translationCode, request.targetLang).takeIf { it.isNotBlank() },
            streamTranslations = true,
            filterFurigana = OcrManager.instance.filterFuriganaEnabled,
        )

        // Directly in this function, never in a scoped builder: part 2 must
        // be a child of the caller's job.
        val response = client.postFrame(frame, meta)
        val ocr = try {
            PtCloudResponseMapper.toOcrResult(response.ocr, frame.scale)
        } catch (e: PtCloudReadException) {
            // No reading to hand back, so nobody else would stop part 2.
            response.translation.cancel()
            throw e
        }

        // The groups the server's cache answered: final for this frame,
        // whatever the final part says. A blank one is no translation.
        val groups = response.ocr.groups
        val ready = groups.mapNotNull { g -> g.translation?.takeIf { it.isNotBlank() }?.let { g.id to it } }.toMap()
        // The frame's outcome when part 1 already decides it, else null: every
        // group answered by the cache (the contract makes no model call and
        // sends the final part at once with ok), or nothing to translate, a
        // same-language request or a part 1 with no groups (the final part
        // follows at once as skipped). Recorded and reported here, since a
        // consumer that wants no translation (the furigana tier, the drag, a
        // reading with no text) drops the translations the moment this
        // returns, and the frame would otherwise never count as a success.
        val knownAtPart1: TranslationOutcome? = when {
            groups.isNotEmpty() && groups.all { it.id in ready } -> TranslationOutcome.Translated(ready)
            sameLanguage || groups.isEmpty() -> TranslationOutcome.Skipped
            else -> null
        }

        // Built lazily: the job needs the translations it fills and the
        // translations need the job they cancel. Started right after, before
        // anything else can throw: a child never started would hold the
        // caller's job open forever.
        lateinit var translations: ReaderTranslations
        val job = CoroutineScope(callerContext + Dispatchers.IO).launch(start = CoroutineStart.LAZY) {
            // Recorded, reported and settled in read below, so there is no
            // work here for a consumer's cancel to stop. The read after part 1
            // (postFrame's own child of the caller's job) is left to end by
            // itself, the final part following at once: the exchange completes
            // and logs its clock line instead of being reset by every consumer
            // that drops a reading it has no more use for.
            if (knownAtPart1 != null) return@launch
            try {
                // Each item as it arrives: provisional, so the final part
                // below replaces them all.
                val forwarding = launch { response.items.collect { translations.deliver(it) } }
                val result = try {
                    response.translation.await()
                } finally {
                    forwarding.cancel()
                }
                // A caller cancelled as part 2 settled: not a fact about the
                // service, so nothing is recorded or reported.
                ensureActive()
                val outcome = PtCloudErrors.classifyPart2(result)
                outcome.record(PtCloudService.cooldown, response.attemptStartedAtMs)
                PtCloudService.report(
                    OnlineAttempt(
                        PtCloudService.ID, PtCloudService.DISPLAY_NAME, outcome.failure, response.sentAtMs,
                        reachedServer = true,
                    ),
                )
                // Skipped and Failed deliver nothing beyond part 1's: the
                // items are dropped and the cache's answers stand (the
                // contract's rule for a failed or deferred final part).
                if (outcome is TranslationOutcome.Translated) translations.settle(outcome.byGroup)
                else translations.settle()
            } catch (e: CancellationException) {
                // Part 2's read is stopped by the completion handler below,
                // which runs whether or not this body started.
                throw e
            } catch (t: Throwable) {
                // A bug in this block: nothing recorded or reported, and never
                // a failed child, which would fail the caller's job (on the
                // hold path, crash the app). The completion handler below
                // settles.
            }
        }
        translations = ReaderTranslations(
            translationCode, request.targetLang, PtCloudService.DISPLAY_NAME,
            groupCount = groups.size, ready = ready, job = job, configGeneration = configGeneration,
        )
        // However the job ends, the translations settle, so a consumer in
        // ReaderTranslations.await can never wait on a job that is over. A
        // completion handler rather than a finally: a job cancelled before
        // its body runs never reaches the body's finally. A no-op when the
        // body already settled.
        //
        // Cancelled (a consumer dropped the reading: a result with no source
        // text, the startup card's drop, a cycle the cache covers, a tool's
        // superseded cycle), part 2's read is cancelled with it, or its
        // socket stays open until the server answers and, since that read is
        // a child of the caller's job too, the caller's job stays alive until
        // then (the camera's acquire gate waits on its job). Here and not in
        // the body's catch, so a cancel that lands before the body starts
        // stops it too. A frame whose outcome part 1 decided is left to end
        // by itself, as above.
        job.invokeOnCompletion { cause ->
            if (cause is CancellationException && knownAtPart1 == null) response.translation.cancel()
            translations.settle()
        }
        job.start()

        if (knownAtPart1 != null) {
            // A caller cancelled on the way back records nothing; its
            // cancellation reaches the job too.
            callerContext.ensureActive()
            knownAtPart1.record(PtCloudService.cooldown, response.attemptStartedAtMs)
            PtCloudService.report(
                OnlineAttempt(
                    PtCloudService.ID, PtCloudService.DISPLAY_NAME, knownAtPart1.failure, response.sentAtMs,
                    reachedServer = true,
                ),
            )
            translations.settle()
        }
        return FrameReading(ocr, ReaderAttribution(PtCloudService.DISPLAY_NAME, PtCloudService.ID), translations)
    }
}
