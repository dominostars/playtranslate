package com.playtranslate.cloud

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.playtranslate.language.SourceLanguageProfiles
import com.playtranslate.ocr.FrameReader
import com.playtranslate.ocr.FrameReading
import com.playtranslate.ocr.ProvidedTranslations
import com.playtranslate.ocr.ReadRequest
import com.playtranslate.ocr.ReaderAttribution
import com.playtranslate.translation.OnlineAttempt
import com.playtranslate.translation.llm.LlmPromptTemplates
import com.playtranslate.translation.llm.PromptKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Reads a frame through PlayTranslate Cloud: one `POST /v1/frame` returns the
 * OCR groups (part 1) and, about a second later, their translations (part 2).
 * [read] returns at part 1; the translations follow as the reading's
 * [FrameReading.translations], a child of the caller's job.
 *
 * Failures split at part 1, as in [PtCloudClient]. Before it, [read] throws
 * the client's [PtCloudReadException] (an IOException, which
 * [com.playtranslate.ocr.FallbackFrameReader] answers with the device reader),
 * the cooldown already recorded and the attempt already reported. After it,
 * the OCR is kept and part 2's outcome decides everything else: this reader
 * records it on [PtCloudService.cooldown] and reports it through
 * [PtCloudService.report], once per frame, and a translation that failed
 * resolves to [ProvidedTranslations.Unavailable] so the caller translates
 * locally. A caller that cancels (a released hold, a superseded capture, a
 * reading it has no use for) records and reports nothing.
 *
 * Logs carry byte counts, sizes, timings, status names and exception class
 * names only; never the key, the URL, the persona, the context or any text.
 */
class PtCloudFrameReader(
    private val client: PtCloudClient,
    private val config: PtCloudConfig,
    /** Test seam: [FrameEncoder.encode] needs native graphics. */
    private val encode: suspend (Bitmap, Int) -> EncodedFrame = FrameEncoder::encode,
) : FrameReader {

    /**
     * Uploads [bitmap] and returns once part 1 has arrived and mapped back to
     * [bitmap]'s coordinates. Call it from the job meant to own part 2, not
     * inside a scoped builder: a scope waits for its children, so it would
     * return only after part 2 ([PtCloudClient.postFrame]).
     *
     * The request's pair decides what the server is asked for. `source` is
     * the wire code of [ReadRequest.sourceLangId]; when the app's pair is
     * same-language (its translationCode equals [ReadRequest.targetLang],
     * which includes a Traditional Chinese source with a "zh" target),
     * `target` is sent equal to `source`, so the server skips translation
     * and a capture that needs none never pays for one. `persona` is the
     * user's System prompt override, absent without one (the server's
     * default is the app's default persona). `context` is the recent-dialogue
     * block the translation log keeps for the pair, absent when blank or
     * same-language.
     *
     * The attribution token "ptcloud" is unknown to the OCR picker: a result
     * read here highlights no engine there, and a re-OCR the user starts from
     * it runs on the device engine they pick (the router keeps a re-OCR on the
     * device).
     */
    override suspend fun read(bitmap: Bitmap, request: ReadRequest): FrameReading {
        // Captured here, outside any withContext: the translations below are
        // a child of the CALLER's job, so the caller waits for them and
        // cancels them, as postFrame's own part-2 reader is.
        val callerContext = currentCoroutineContext()

        val cap = config.uploadLongSideCap
        val encodeStartMs = SystemClock.elapsedRealtime()
        val frame = encode(bitmap, cap)
        Log.i(
            TAG,
            "encode=${SystemClock.elapsedRealtime() - encodeStartMs}ms bytes=${frame.bytes.size} " +
                "cap=$cap size=${frame.width}x${frame.height}",
        )

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

        val translations = CoroutineScope(callerContext + Dispatchers.IO).async {
            try {
                val result = response.translation.await()
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
                val status = when (outcome) {
                    is TranslationOutcome.Translated -> "Translated ${outcome.byGroup.size}"
                    TranslationOutcome.Skipped -> "Skipped"
                    is TranslationOutcome.Failed -> "Failed ${outcome.failure.kind}"
                }
                Log.i(TAG, "part2 status=$status ms=${SystemClock.elapsedRealtime() - response.sentAtMs}")
                when (outcome) {
                    // A group the server names with an empty (or blank)
                    // string has no translation. Treated as unnamed, it goes
                    // where an unnamed group goes (capture-once: no box; the
                    // hold: a skeleton; live: the cache and the waterfall)
                    // instead of pinning a live box blank while the
                    // reconciler retries it every cycle.
                    is TranslationOutcome.Translated -> ProvidedTranslations.Ok(
                        outcome.byGroup.filterValues { it.isNotBlank() },
                        translationCode, request.targetLang, PtCloudService.DISPLAY_NAME,
                    )
                    TranslationOutcome.Skipped -> ProvidedTranslations.Unavailable("skipped")
                    is TranslationOutcome.Failed -> ProvidedTranslations.Unavailable(outcome.failure.kind.name)
                }
            } catch (e: CancellationException) {
                // Cancelling these translations (the core does on no text and
                // on a same-language pair) must stop part 2's read too, or
                // its socket stays open until the server answers.
                response.translation.cancel()
                throw e
            } catch (t: Throwable) {
                // A bug in this block: nothing recorded or reported, and never
                // a failed child, which would fail the caller's job (on the
                // hold path, crash the app).
                ProvidedTranslations.Unavailable(t.javaClass.simpleName)
            }
        }
        return FrameReading(ocr, ReaderAttribution(PtCloudService.DISPLAY_NAME, PtCloudService.ID), translations)
    }

    private companion object {
        const val TAG = "PtCloud"
    }
}
