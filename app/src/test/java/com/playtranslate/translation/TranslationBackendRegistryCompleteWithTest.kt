package com.playtranslate.translation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * [TranslationBackendRegistry.completeWith], a question put to the one
 * service the user picked for it, and
 * [TranslationBackendRegistry.setUpToAnswer], the list they pick it from:
 * the twins of `translateWith` and `setUpFor`
 * ([TranslationBackendRegistryChosenTest]). Pinned here:
 *  - who is asked: the picked service and nobody else, whether or not it is
 *    switched on, provided it is set up to answer; one that is not, that
 *    takes no question at all, or that is no longer registered is sent
 *    nothing;
 *  - what it is put: a completer the question's two messages, an answerer
 *    the question whole, as on the pass in order;
 *  - the cooldown and the pause of answers: a service in either is asked
 *    all the same, its answer clears the cooldown as any answer does, and
 *    its failure leaves a running one as it was;
 *  - how it ends: its answer with the service named, out of scope included;
 *    a failure or a blank reply fails the run with the service and what it
 *    threw, nobody else being asked in its place; cancellation stops it;
 *  - what it leaves alone: the translation error pill's listener hears
 *    nothing of it, and the pass in order still passes by a service that is
 *    off or cooling;
 *  - the list: every service set up to answer, in the order questions try
 *    them, the switched-off and the cooling among them, and nothing that
 *    only translates.
 *
 * Pure JVM over fakes; the registry's Log calls are no-ops through
 * `unitTests.isReturnDefaultValues`.
 */
class TranslationBackendRegistryCompleteWithTest {

    @After fun tearDown() {
        TranslationBackendRegistry.onlineAttemptListener = null
        TranslationBackendRegistry.close()
    }

    private fun question(system: String = "s", user: String = "u") = SentenceQuestion(
        source = "ja", target = "en", sentence = "the sentence", question = "the wording",
        system = system, user = user,
    )

    private fun with(id: String, question: SentenceQuestion = question()) = runBlocking {
        TranslationBackendRegistry.completeWith(id, question)
    }

    /** Runs the pick and returns what it threw, failing if it answered. */
    private fun failure(id: String): Throwable {
        val outcome = runCatching { with(id) }
        return outcome.exceptionOrNull()
            ?: throw AssertionError("expected the run to throw, ${outcome.getOrNull()?.backend?.id} answered")
    }

    private fun failed(id: String): CompletionFailedException {
        val thrown = failure(id)
        assertTrue("$thrown", thrown is CompletionFailedException)
        return thrown as CompletionFailedException
    }

    /** Switched off and set up: what an ordinary question passes by and a
     *  pick asks. */
    private fun switchedOff(id: String, priority: Int, online: Boolean = true) = FakeCompleterBackend(
        id = id, priority = priority, requiresInternet = online, canComplete = false, setUp = true,
    )

    private fun cooling(backend: FakeCompleterBackend) = backend.apply {
        cooldownState.recordParsedFailure(System.currentTimeMillis() + 60_000, "Rate limited")
    }

    // ── who is asked ─────────────────────────────────────────────────────

    @Test fun `only the picked service is asked, whatever comes before it in the order`() {
        val first = FakeCompleterBackend(id = "first", priority = 10)
        val picked = FakeCompleterBackend(id = "picked", priority = 20)
        val last = FakeCompleterBackend(id = "last", priority = 30, requiresInternet = false)
        TranslationBackendRegistry.init(listOf(first, picked, last))

        val result = with("picked", question("the system", "the user"))

        assertSame(picked, result.backend)
        assertEquals(SentenceAnswer.Text("answer-from-picked"), result.answer)
        assertEquals("its two messages", "the system" to "the user", picked.lastPrompt)
        assertEquals(0, first.completeCalls.get())
        assertEquals(0, last.completeCalls.get())
    }

    @Test fun `a service that is switched off is asked when it is set up, online or on the device`() {
        val online = switchedOff("online", 10)
        val onDevice = switchedOff("on-device", 20, online = false)
        TranslationBackendRegistry.init(listOf(online, onDevice))
        // The contrast: a question put to the services in their order
        // finds nobody, both being off.
        assertFalse(TranslationBackendRegistry.hasCompleter())
        assertTrue(
            runCatching { runBlocking { TranslationBackendRegistry.complete(question()) } }
                .exceptionOrNull() is CompletionFailedException,
        )
        assertEquals(0, online.completeCalls.get())

        assertSame(online, with("online").backend)
        assertSame(onDevice, with("on-device").backend)
        assertEquals(1, online.completeCalls.get())
        assertEquals(1, onDevice.completeCalls.get())
    }

    @Test fun `an answerer is asked the question whole, and only while it is set up to answer`() {
        val asked = question("the system", "the user")
        val answerer = FakeAnswererBackend(id = "cloud", priority = 10)
        val off = FakeAnswererBackend(id = "cloud-off", priority = 20, canAnswer = false)
        TranslationBackendRegistry.init(listOf(answerer, off))

        val result = with("cloud", asked)

        assertSame(answerer, result.backend)
        assertSame("the question itself, with its parts", asked, answerer.lastQuestion)

        // An answerer's default: set up only while it is switched on.
        val thrown = failed("cloud-off")
        assertSame(off, thrown.backend)
        assertNull("nothing was sent", thrown.cause)
        assertEquals(0, off.answerCalls.get())
    }

    @Test fun `a service that is not set up, takes no question or is no longer registered is sent nothing`() {
        val notSetUp = FakeCompleterBackend(id = "no-key", priority = 10, canComplete = false)
        val translationOnly = FakeOnlineBackend(id = "deepl", priority = 20)
        val other = FakeCompleterBackend(id = "other", priority = 30)
        TranslationBackendRegistry.init(listOf(notSetUp, translationOnly, other))

        val noKey = failed("no-key")
        assertSame(notSetUp, noKey.backend)
        assertNull(noKey.cause)
        assertEquals(0, notSetUp.completeCalls.get())

        val onlyTranslates = failed("deepl")
        assertSame(translationOnly, onlyTranslates.backend)
        assertNull(onlyTranslates.cause)
        assertEquals("never asked to translate the prompt", 0, translationOnly.translateCalls.get())

        val gone = failed("removed")
        assertNull("no longer registered", gone.backend)
        assertNull(gone.cause)

        assertEquals("nobody is asked in the picked one's place", 0, other.completeCalls.get())
    }

    // ── the cooldown and the pause of answers ────────────────────────────

    @Test fun `a service in a cooldown is asked, and its answer clears the cooldown`() {
        val cooled = cooling(FakeCompleterBackend(id = "cooled", priority = 10))
        val next = FakeCompleterBackend(id = "next", priority = 20)
        TranslationBackendRegistry.init(listOf(cooled, next))
        // The contrast: the pass in order passes it by.
        assertSame(next, runBlocking { TranslationBackendRegistry.complete(question()) }.backend)
        assertEquals(0, cooled.completeCalls.get())

        assertSame(cooled, with("cooled").backend)

        assertEquals(1, cooled.completeCalls.get())
        assertEquals(1, cooled.recordSuccessCalls.get())
        assertNull("its answer cleared the cooldown, as any answer does", cooled.unavailableUntil())
    }

    @Test fun `an answerer is asked while its answers are paused and while its cooldown runs`() {
        val answerer = FakeAnswererBackend(id = "cloud", priority = 10)
        TranslationBackendRegistry.init(listOf(answerer))

        answerer.pausedUntil = System.currentTimeMillis() + 60_000
        assertSame("paused", answerer, with("cloud").backend)

        answerer.cooldownState.recordParsedFailure(System.currentTimeMillis() + 60_000, "Rate limited")
        assertSame("paused and cooling", answerer, with("cloud").backend)

        assertEquals(2, answerer.answerCalls.get())
    }

    @Test fun `a failure inside a cooldown leaves the cooldown as it was`() {
        val state = CooldownState(context = null, backendId = "cooled")
        state.recordLadderFailure(CooldownLadder.RateLimit, "Server error", CooldownCause.SERVER_ERROR)
        val until = state.unavailableUntil()!!
        val cause = StructuralFailureException("error 503", BackendFailure(BackendFailureKind.SERVER_ERROR, 503))
        // Fails as Gemini does on a 5xx: the failure recorded, then the throw.
        val cooled = FakeCompleterBackend(
            id = "cooled", priority = 10, cooldownState = state,
            answer = { _, _ ->
                state.recordLadderFailure(CooldownLadder.RateLimit, "Server error", CooldownCause.SERVER_ERROR)
                throw cause
            },
        )
        TranslationBackendRegistry.init(listOf(cooled))

        val thrown = failed("cooled")

        assertSame(cooled, thrown.backend)
        assertSame(cause, thrown.cause)
        assertEquals(1, cooled.completeCalls.get())
        assertEquals("the cooldown neither ended nor grew", until, state.unavailableUntil())
    }

    // ── how it ends ──────────────────────────────────────────────────────

    @Test fun `out of scope is the picked service's answer`() {
        val answerer = FakeAnswererBackend(id = "cloud", priority = 10, answer = { SentenceAnswer.OutOfScope })
        TranslationBackendRegistry.init(listOf(answerer, FakeCompleterBackend(id = "gemini", priority = 20)))

        val result = with("cloud")

        assertSame(answerer, result.backend)
        assertEquals(SentenceAnswer.OutOfScope, result.answer)
    }

    @Test fun `a failure fails the run with the service and what it threw, and nobody is asked in its place`() {
        val cause = OpenAiRateLimitException(BackendFailure(BackendFailureKind.RATE_LIMITED, 429))
        val picked = FakeCompleterBackend(id = "picked", priority = 10, answer = { _, _ -> throw cause })
        val next = FakeCompleterBackend(id = "next", priority = 20)
        TranslationBackendRegistry.init(listOf(picked, next))

        val thrown = failed("picked")

        assertSame(picked, thrown.backend)
        assertSame(cause, thrown.cause)
        assertEquals(0, picked.recordSuccessCalls.get())
        assertEquals("a pick falls through to nobody", 0, next.completeCalls.get())
    }

    @Test fun `a blank reply fails the run, uncounted, and leaves a running cooldown as it was`() {
        val blank = cooling(FakeCompleterBackend(id = "blank", priority = 10, answer = { _, _ -> "  \n" }))
        val until = blank.unavailableUntil()
        val next = FakeCompleterBackend(id = "next", priority = 20)
        TranslationBackendRegistry.init(listOf(blank, next))

        val thrown = failed("blank")

        assertSame(blank, thrown.backend)
        assertNotNull("a failure of the service, with a cause to tell", thrown.cause)
        assertEquals(1, blank.completeCalls.get())
        assertEquals("an answer that said nothing is not counted", 0, blank.recordSuccessCalls.get())
        assertEquals(until, blank.unavailableUntil())
        assertEquals(0, next.completeCalls.get())
    }

    @Test fun `a cancellation from the service is re-thrown, never a failed run`() {
        val cancelled = FakeCompleterBackend(
            id = "cancelled", priority = 10, answer = { _, _ -> throw CancellationException("question put away") },
        )
        TranslationBackendRegistry.init(listOf(cancelled))

        val thrown = failure("cancelled")

        assertTrue("$thrown", thrown is CancellationException)
        assertFalse(thrown is CompletionFailedException)
        assertEquals(0, cancelled.recordSuccessCalls.get())
    }

    // ── what it leaves alone ─────────────────────────────────────────────

    @Test fun `the error pill's listener hears nothing of a pick, an answer or a failure`() {
        val reports = mutableListOf<List<OnlineAttempt>>()
        TranslationBackendRegistry.onlineAttemptListener = { reports += it }
        val online = FakeCompleterBackend(id = "online", priority = 10)
        val refusing = FakeCompleterBackend(
            id = "refusing", priority = 20, answer = { _, _ -> throw IOException("synthetic connection failure") },
        )
        TranslationBackendRegistry.init(listOf(online, refusing))
        // The precondition and contrast: a translation over this very
        // online fake reports its attempt.
        runBlocking { TranslationBackendRegistry.translate("hi", "ja", "en") }
        assertEquals("a translation pass reports", 1, reports.size)
        reports.clear()

        with("online")
        failed("refusing")

        assertTrue("$reports", reports.isEmpty())
    }

    // ── the list ─────────────────────────────────────────────────────────

    @Test fun `the list is every service set up to answer, in the order questions try them`() {
        val gemini = FakeCompleterBackend(id = "gemini", priority = 20)
        val off = switchedOff("claude", 10)
        val cooled = cooling(FakeCompleterBackend(id = "openai", priority = 30))
        val cloud = FakeAnswererBackend(id = "cloud", priority = 5)
        val cloudOff = FakeAnswererBackend(id = "cloud-off", priority = 6, canAnswer = false)
        val noKey = FakeCompleterBackend(id = "no-key", priority = 15, canComplete = false)
        val deepl = FakeOnlineBackend(id = "deepl", priority = 1)
        val qwenOff = switchedOff("qwen", 40, online = false)
        val mlkit = FakeOfflineBackend(id = "mlkit", priority = 50)
        TranslationBackendRegistry.init(listOf(qwenOff, mlkit, cooled, gemini, off, noKey, cloudOff, cloud, deepl))

        assertEquals(
            "the switched-off and the cooling among them; nothing that only translates or is not set up",
            listOf("cloud", "claude", "gemini", "openai", "qwen"),
            TranslationBackendRegistry.setUpToAnswer().map { it.id },
        )

        TranslationBackendRegistry.setOrder(listOf("openai", "gemini"))
        assertEquals(
            "the user's order, as for a question",
            listOf("openai", "gemini", "cloud", "claude", "qwen"),
            TranslationBackendRegistry.setUpToAnswer().map { it.id },
        )
    }

    @Test fun `the list and the run agree on every service`() {
        val backends = listOf<TranslationBackend>(
            FakeCompleterBackend(id = "on", priority = 10),
            switchedOff("off", 20),
            FakeCompleterBackend(id = "no-key", priority = 30, canComplete = false),
            FakeAnswererBackend(id = "cloud", priority = 40),
            FakeAnswererBackend(id = "cloud-off", priority = 50, canAnswer = false),
            FakeOnlineBackend(id = "deepl", priority = 60),
        )
        TranslationBackendRegistry.init(backends)
        val listed = TranslationBackendRegistry.setUpToAnswer().map { it.id }.toSet()

        for (backend in backends) {
            val answered = runCatching { with(backend.id) }.isSuccess
            assertEquals("${backend.id}: listed means it can be asked", backend.id in listed, answered)
        }
    }
}
