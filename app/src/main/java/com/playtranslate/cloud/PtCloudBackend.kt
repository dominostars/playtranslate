package com.playtranslate.cloud

import com.playtranslate.ocr.FrameReaders
import com.playtranslate.translation.AccountRequirement
import com.playtranslate.translation.BackendFailure
import com.playtranslate.translation.BackendFailureKind
import com.playtranslate.translation.BackendStatus
import com.playtranslate.translation.BatchTranslator
import com.playtranslate.translation.CooldownCause
import com.playtranslate.translation.CooldownState
import com.playtranslate.translation.Cooldownable
import com.playtranslate.translation.StarRating
import com.playtranslate.translation.StructuralFailureException
import com.playtranslate.translation.TranslationBackend
import com.playtranslate.translation.llm.LlmPromptTemplates
import com.playtranslate.translation.llm.PromptKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * PlayTranslate Cloud as a backend of the translation waterfall
 * ([com.playtranslate.translation.TranslationBackendRegistry]), first among
 * the online services ([com.playtranslate.translation.OnlineServiceMutations.applyOnlineOrder]):
 * every translation the app makes without a frame (an edited text, a
 * sentence, select-to-translate, a group a frame's reader left unnamed)
 * goes through `POST /v1/translate` ([PtCloudClient.postTranslate]) while
 * the service is switched on with a key stored. Registered once, at
 * [com.playtranslate.PlayTranslateApplication.onCreate], with a fixed id;
 * never a store instance.
 *
 * The three providers are read at each call, never kept: the service's
 * client, config and cooldown ([PtCloudService], the composition root)
 * by default, a test's own otherwise. The cooldown in particular is
 * [PtCloudService.cooldown] at the moment of the call, the one state the
 * frame reader records on too, so a test that swaps it
 * ([PtCloudService.installForTest]) and the registry skipping a cooling
 * cloud see the same state the client records on.
 *
 * It records no cooldown itself: [PtCloudClient.postTranslate] records
 * every failure of the route, and the registry records the success
 * ([recordSuccess]). Nor does it report attempts: the registry does, for
 * every online backend.
 *
 * Accepted as they are (2026-10-17), no mechanism:
 *  - A frame whose cloud translation ended `deferred`, or failed with no
 *    cooldown, hands its unnamed groups to the waterfall, whose first
 *    backend is this one, so those texts get a second call before the
 *    user's own services. Its logcat signature: a frame's `final id=d<n>-...
 *    status=deferred` clock line followed by a `translate id=tr-...` line in
 *    the same cycle. To be revisited with numbers.
 *  - Every cloud cooldown that starts or ends, recorded by either route,
 *    changes the registry's preferred online id and so clears the shared
 *    translation cache (the registry's trade-off, now also set off by a
 *    frame's failed translation).
 *  - After a cloud failure with no cooldown (`deferred`, `deadline`,
 *    `bad_output`, `config`, a 503, a partial answer) the next backend's
 *    translation is cached under the "ptcloud" routing identity, so that
 *    text does not reach the cloud again in this process, as for every
 *    online backend's failures with no cooldown.
 *  - A `deferred` answer here is a TIMEOUT, which the registry reports as
 *    not having reached the server, where the frame reader reports the same
 *    status as reached: the pill can blame the connection for it when the
 *    network reads unvalidated at that moment.
 *  - An all-blank batch makes no call, and the registry still records a
 *    success (both ladders reset, the pill down); no caller sends one.
 */
class PtCloudBackend(
    private val client: () -> PtCloudClient = { PtCloudService.client },
    private val config: () -> PtCloudConfig = { PtCloudService.config },
    private val cooldown: () -> CooldownState = { PtCloudService.cooldown },
) : TranslationBackend, BatchTranslator, Cooldownable {

    override val id = PtCloudService.ID
    override val displayName = PtCloudService.DISPLAY_NAME

    /** Nominal: the order puts the cloud first among the online services by
     *  its id ([com.playtranslate.translation.OnlineServiceMutations.applyOnlineOrder]),
     *  ahead of every priority. */
    override val priority = 10
    override val requiresInternet = true
    override val isDegradedFallback = false

    /** It batches with the recent-dialogue context, so the short-text
     *  route's LLM bypass treats it as it treats Gemini and OpenAI. */
    override val providesLlmContext = true

    /** Tier one is an LLM hedged by another, the class of the LLM
     *  backends. */
    override val qualityStars: StarRating = 4.5f
    override val speedStars: StarRating? = null

    /**
     * False, the interface's default, stated here because it is a rule:
     * dictionary definitions and glosses that lack a translation in the
     * user's language must never be machine-translated by the cloud
     * (2026-10-17). [com.playtranslate.language.OfflineFallbackTranslators]
     * and the short-text offline route walk only the backends that opt in,
     * so this flag is the whole protection.
     */
    override val usableAsOfflineFallback: Boolean get() = false

    /** The status line of the service's Translation services row: "Account
     *  required" while no usable key is stored ([PtCloudConfig.hasKey]), as
     *  a Gemini, DeepL or OpenAI row reads before its key is saved; none
     *  once one is, where the row shows the key's tail instead. */
    override val status: BackendStatus
        get() = if (config().hasKey) BackendStatus.Hidden
        else BackendStatus.Account(AccountRequirement.REQUIRED)

    /** Switched on with a usable key ([PtCloudRouting.configured]); the key
     *  is read, and so decrypted, only when the switch is on. Any pair: the
     *  route takes any code. The network and the cooldown are the
     *  registry's to judge. */
    override fun isUsable(source: String, target: String): Boolean {
        val config = config()
        return PtCloudRouting.configured(config.enabled) { config.hasKey }
    }

    override suspend fun translate(text: String, source: String, target: String): String =
        translateBatch(listOf(text), source, target).single()

    /**
     * All or nothing, in the registry's [BatchTranslator] contract. On
     * [Dispatchers.IO] throughout: the key's decryption, the encoding, the
     * size counting and the context provider would otherwise run on the
     * caller's thread, once per text in the registry's per-text retry.
     *
     *  - A text over [PtCloudBatches.MAX_TEXT_CODE_POINTS] code points, alone
     *    or in a batch, throws [StructuralFailureException] (REJECTED) before
     *    any request: the server would refuse the whole call, and the
     *    registry hands the full list to the next backend.
     *  - A blank text is answered as it is, without the server, which would
     *    refuse it.
     *  - One text whose body alone, with the persona and the context,
     *    reaches [PtCloudBatches.MAX_BODY_BYTES] throws
     *    [StructuralFailureException] (REJECTED) before any request too:
     *    the server would answer 413. Only a very long persona gets a body
     *    there ([PtCloudBatches.bodyOversize]).
     *  - The rest go in [PtCloudBatches.chunk]'s calls, ONE AFTER ANOTHER,
     *    and the first failure ([PtCloudReadException], already recorded by
     *    the client) ends the batch: calls in parallel would record one
     *    transport failure twice and spend the budget twice.
     *  - An `ok` answer that leaves a text of its call unnamed or blank is
     *    asked again ONCE, for those texts alone in one call: what the frame
     *    route gets through the waterfall for a group its answer left out. A
     *    second such answer throws [StructuralFailureException]
     *    (BAD_RESPONSE), and the registry hands the full list to the next
     *    backend. Never a [com.playtranslate.translation.BatchParseException]:
     *    the registry answers that with one call per pending text, all at
     *    once, which for a 64-text page is 64 requests against the edge's
     *    budget of 120 a minute shared with frames (Codex adversarial
     *    review, 2026-10-17). `skipped` answers each text with itself only
     *    for a source equal to the target; for any other pair it is an
     *    unusable answer and the batch falls to the next backend ([ask]).
     */
    override suspend fun translateBatch(
        texts: List<String>,
        source: String,
        target: String,
    ): List<String> = withContext(Dispatchers.IO) {
        if (PtCloudBatches.oversize(texts)) {
            throw StructuralFailureException(
                "$displayName takes at most ${PtCloudBatches.MAX_TEXT_CODE_POINTS} code points a text",
                BackendFailure(BackendFailureKind.REJECTED),
                serverAnswered = false,
            )
        }
        val persona = LlmPromptTemplates.overrideProvider(PromptKind.SYSTEM)
        val context = LlmPromptTemplates.contextProvider(source, target).takeIf { it.isNotBlank() }
        val frameId = FrameReaders.frameId(FRAME_TAG)
        fun request(chunk: List<String>) = TranslateRequest(source, target, chunk, frameId, persona, context)
        fun bodyBytes(chunk: List<String>) = PtCloudClient.translateBody(request(chunk)).size
        if (PtCloudBatches.bodyOversize(texts) { bodyBytes(it) }) {
            throw StructuralFailureException(
                "$displayName takes bodies under ${PtCloudBatches.MAX_BODY_BYTES} bytes; " +
                    "one text with the persona and the context reaches it",
                BackendFailure(BackendFailureKind.REJECTED),
                serverAnswered = false,
            )
        }

        val translated = texts.toMutableList()
        val client = client()
        for (chunk in PtCloudBatches.chunk(texts) { bodyBytes(it) }) {
            val unnamed = ask(client, request(chunk.texts), chunk.positions, translated)
            if (unnamed.isEmpty()) continue
            val still = ask(client, request(unnamed.map { texts[it] }), unnamed, translated)
            if (still.isNotEmpty()) {
                throw StructuralFailureException(
                    "$displayName left ${still.size} of ${chunk.texts.size} texts untranslated twice",
                    BackendFailure.BAD_RESPONSE,
                )
            }
        }
        translated
    }

    /** One call ([request]) for the texts at [positions]: fills [translated]
     *  at each position the answer names with a non-blank text and returns
     *  the positions it left unnamed. `skipped` is the server's answer for
     *  a source equal to the target: then each text stands as its own
     *  answer. For any other pair it is an answer with nothing usable in it
     *  (a server that mistook the pair, or another version of the contract),
     *  thrown as [StructuralFailureException] (BAD_RESPONSE) so that the
     *  waterfall falls back, where taking it would show the source text as
     *  its own translation and cache it as such. The client returns nothing
     *  else. */
    private suspend fun ask(
        client: PtCloudClient,
        request: TranslateRequest,
        positions: List<Int>,
        translated: MutableList<String>,
    ): List<Int> {
        val outcome = client.postTranslate(request)
        if (outcome == TranslationOutcome.Skipped) {
            if (request.source == request.target) return emptyList()
            throw StructuralFailureException(
                "$displayName skipped a ${request.source} to ${request.target} call",
                BackendFailure.BAD_RESPONSE,
            )
        }
        val byPosition = (outcome as TranslationOutcome.Translated).byGroup
        val unnamed = ArrayList<Int>()
        positions.forEachIndexed { i, position ->
            val text = byPosition[i]
            if (text.isNullOrBlank()) unnamed += position else translated[position] = text
        }
        return unnamed
    }

    // Cooldownable: every method reads the cooldown at the call.

    override fun unavailableUntil(): Long? = cooldown().unavailableUntil()

    override fun unavailableDescription(): String? = cooldown().unavailableDescription()

    override fun unavailableCause(): CooldownCause? = cooldown().unavailableCause()

    override fun recordSuccess(attemptStartedAtMs: Long) = cooldown().recordSuccess(attemptStartedAtMs)

    override fun onConnectivityRestored(): Boolean = cooldown().onConnectivityRestored()

    override fun resetCooldown() = cooldown().resetCooldown()

    private companion object {
        /** A translate call's frame id prefix ([FrameReaders.frameId]):
         *  `tr-...` beside the readers' `d0-...`, `cam-...`, `import-...`. */
        const val FRAME_TAG = "tr"
    }
}
