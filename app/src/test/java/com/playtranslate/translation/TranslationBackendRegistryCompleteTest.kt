package com.playtranslate.translation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/**
 * [TranslationBackendRegistry.complete], the one pass that puts a question
 * about a sentence to the user's services, and
 * [TranslationBackendRegistry.hasCompleter], the question the Ask section
 * asks before it takes one. Pinned here:
 *  - which backends the pass tries (prompt completers that can complete and
 *    answerers that can answer, not in a cooldown, and for an answerer not
 *    in a pause of its answers alone) and in what order (the registry's,
 *    setOrder included, with a model made for translation after everyone
 *    else, so that it answers only when no other does); a pass past the
 *    cooldowns (a retry's) lifts those two skips and nothing else, its
 *    answer clears the cooldown and its failure leaves a running one as it
 *    was;
 *  - what each is put: a completer the question's two messages, an answerer
 *    the question whole, with its parts;
 *  - who it names as it goes (each one as it starts asking it, never
 *    one it skips), and a cancellation thrown there ending it;
 *  - how it ends: the first non-blank answer wins and is returned with its
 *    backend, an answerer's "out of scope" is an answer and ends the pass
 *    too, a failure or a blank falls through, cancellation stops it, and
 *    a pass nobody answered names the FIRST one tried and what it
 *    threw (or nothing, when nothing was tried);
 *  - what it leaves behind: recordSuccess on the winner only, and never a
 *    report to the translation error pill's listener (contrasted with a
 *    translation pass over the same fake, which does report).
 *
 * Pure JVM over fakes; the registry's Log calls are no-ops through
 * `unitTests.isReturnDefaultValues`.
 */
class TranslationBackendRegistryCompleteTest {

    @After fun tearDown() {
        TranslationBackendRegistry.onlineAttemptListener = null
        TranslationBackendRegistry.close()
    }

    private fun cooledDown(id: String, priority: Int) = FakeCompleterBackend(id = id, priority = priority).apply {
        cooldownState.recordParsedFailure(System.currentTimeMillis() + 60_000, "Rate limited")
    }

    private fun failing(id: String, priority: Int, e: Exception) =
        FakeCompleterBackend(id = id, priority = priority, answer = { _, _ -> throw e })

    /** A question whose two messages are [system] and [user]; its parts are
     *  the same in every test. */
    private fun question(system: String = "s", user: String = "u") = SentenceQuestion(
        source = "ja", target = "zh-Hant", sentence = "the sentence", question = "the wording",
        system = system, user = user,
    )

    /** Runs the pass and returns what it threw, failing if it answered. */
    private fun completeFailure(pastCooldowns: Boolean = false): Throwable {
        val outcome = runCatching {
            runBlocking { TranslationBackendRegistry.complete(question("system", "user"), pastCooldowns) }
        }
        return outcome.exceptionOrNull()
            ?: throw AssertionError("expected the pass to throw, ${outcome.getOrNull()?.backend?.id} answered")
    }

    // ── hasCompleter ─────────────────────────────────────────────────────

    @Test fun `hasCompleter is true exactly when some registered completer can complete`() {
        TranslationBackendRegistry.init(emptyList())
        assertFalse("an empty registry has no LLM", TranslationBackendRegistry.hasCompleter())

        TranslationBackendRegistry.init(listOf(
            FakeOnlineBackend(id = "deepl", priority = 10),
            FakeOfflineBackend(id = "mlkit", priority = 30),
        ))
        assertFalse("translation-only backends are no LLM", TranslationBackendRegistry.hasCompleter())

        TranslationBackendRegistry.init(listOf(
            FakeCompleterBackend(id = "off-1", priority = 10, canComplete = false),
            FakeCompleterBackend(id = "off-2", priority = 20, canComplete = false, requiresInternet = false),
            FakeOnlineBackend(id = "deepl", priority = 30),
        ))
        assertFalse("completers that all say no are no LLM", TranslationBackendRegistry.hasCompleter())

        TranslationBackendRegistry.init(listOf(
            FakeCompleterBackend(id = "off", priority = 10, canComplete = false),
            FakeOnlineBackend(id = "deepl", priority = 20),
            FakeCompleterBackend(id = "qwen", priority = 30, requiresInternet = false),
        ))
        assertTrue("one completer that can complete is enough", TranslationBackendRegistry.hasCompleter())

        TranslationBackendRegistry.init(listOf(
            FakeAnswererBackend(id = "cloud-off", priority = 10, canAnswer = false),
            FakeOnlineBackend(id = "deepl", priority = 20),
        ))
        assertFalse("an answerer that says no is no service to ask", TranslationBackendRegistry.hasCompleter())

        TranslationBackendRegistry.init(listOf(
            FakeAnswererBackend(id = "cloud", priority = 10),
            FakeOnlineBackend(id = "deepl", priority = 20),
        ))
        assertTrue("one answerer that can answer is enough", TranslationBackendRegistry.hasCompleter())
    }

    // ── order and the first answer ───────────────────────────────────────

    @Test fun `complete tries completers in registry order and the first answer wins`() = runBlocking<Unit> {
        val first = FakeCompleterBackend(id = "first", priority = 10)
        val second = FakeCompleterBackend(id = "second", priority = 20)
        val third = FakeCompleterBackend(id = "third", priority = 30, requiresInternet = false)
        TranslationBackendRegistry.init(listOf(third, second, first)) // registered out of order

        val result = TranslationBackendRegistry.complete(question("the system", "the user"))

        assertEquals(SentenceAnswer.Text("answer-from-first"), result.answer)
        assertSame(first, result.backend)
        assertEquals("the system" to "the user", first.lastPrompt)
        assertEquals("later completers are not called", 0, second.completeCalls.get())
        assertEquals(0, third.completeCalls.get())
    }

    @Test fun `complete honours setOrder`() = runBlocking<Unit> {
        val byPriority = FakeCompleterBackend(id = "by-priority", priority = 10)
        val userFirst = FakeCompleterBackend(id = "user-first", priority = 20)
        TranslationBackendRegistry.init(listOf(byPriority, userFirst))
        // The precondition: without an override the priority order decides.
        assertSame(byPriority, TranslationBackendRegistry.complete(question()).backend)

        TranslationBackendRegistry.setOrder(listOf("user-first"))

        val result = TranslationBackendRegistry.complete(question())
        assertSame(userFirst, result.backend)
        assertEquals(1, byPriority.completeCalls.get())
    }

    // ── a model made for translation is asked last ──────────────────────

    /** An on-device translation model that answers a prompt, as Hunyuan-MT
     *  is. */
    private fun translationModel(id: String, priority: Int) =
        FakeCompleterBackend(id = id, priority = priority, requiresInternet = false, madeForTranslation = true)

    @Test fun `a model made for translation is asked after every other completer, whatever the order says`() = runBlocking<Unit> {
        // The translation's order puts the translation model first: it
        // leads the on-device translations.
        val hymt = translationModel("hymt", 10)
        val chat = FakeCompleterBackend(id = "chat", priority = 20, requiresInternet = false)
        TranslationBackendRegistry.init(listOf(hymt, chat))
        assertEquals(
            "the precondition: a translation tries the translation model first",
            listOf("hymt", "chat"), TranslationBackendRegistry.orderedBackends().map { it.id },
        )

        assertSame(chat, TranslationBackendRegistry.complete(question()).backend)
        assertEquals("the translation model is not asked", 0, hymt.completeCalls.get())

        // Nor does the user's order put it ahead for a question.
        TranslationBackendRegistry.setOrder(listOf("hymt", "chat"))
        assertSame(chat, TranslationBackendRegistry.complete(question()).backend)
        assertEquals(0, hymt.completeCalls.get())
    }

    @Test fun `a model made for translation answers when it is the only completer`() = runBlocking<Unit> {
        val hymt = translationModel("hymt", 25)
        TranslationBackendRegistry.init(listOf(FakeOnlineBackend(id = "deepl", priority = 10), hymt))

        assertTrue("it is a service to ask", TranslationBackendRegistry.hasCompleter())
        val result = TranslationBackendRegistry.complete(question("the system", "the user"))

        assertSame(hymt, result.backend)
        assertEquals(SentenceAnswer.Text("answer-from-hymt"), result.answer)
        assertEquals("the system" to "the user", hymt.lastPrompt)
    }

    @Test fun `a model made for translation answers when the others cannot or fail, and is named after them`() = runBlocking<Unit> {
        val named = mutableListOf<String>()
        val hymt = translationModel("hymt", 10)
        val off = FakeCompleterBackend(id = "off", priority = 20, canComplete = false)
        val failing = failing("failing", 30, IOException("no route"))
        TranslationBackendRegistry.init(listOf(hymt, off, failing))

        val result = TranslationBackendRegistry.complete(question()) { named += it.id }

        assertSame(hymt, result.backend)
        assertEquals(listOf("failing", "hymt"), named)
        assertEquals(0, off.completeCalls.get())
    }

    @Test fun `models made for translation keep the registry's order among themselves`() = runBlocking<Unit> {
        val older = translationModel("hymt-1", 26)
        val newer = translationModel("hymt-2", 25)
        val chat = failing("chat", 30, IOException("no route"))
        TranslationBackendRegistry.init(listOf(older, newer, chat))

        assertSame(newer, TranslationBackendRegistry.complete(question()).backend)
        assertEquals(1, chat.completeCalls.get())
        assertEquals(0, older.completeCalls.get())
    }

    @Test fun `complete skips translation-only backends, completers that cannot complete and completers in a cooldown`() = runBlocking<Unit> {
        val translationOnly = FakeOnlineBackend(id = "deepl", priority = 10)
        val cannot = FakeCompleterBackend(id = "cannot", priority = 20, canComplete = false)
        val cooled = cooledDown("cooled", 30)
        val ready = FakeCompleterBackend(id = "ready", priority = 40)
        TranslationBackendRegistry.init(listOf(translationOnly, cannot, cooled, ready))

        val result = TranslationBackendRegistry.complete(question())

        assertSame(ready, result.backend)
        assertEquals("a translation-only backend is not asked to translate the prompt", 0, translationOnly.translateCalls.get())
        assertEquals(0, cannot.completeCalls.get())
        assertEquals(0, cooled.completeCalls.get())
        assertEquals(0, cooled.translateCalls.get())
    }

    @Test fun `an expired cooldown does not skip the completer`() = runBlocking<Unit> {
        // The skip is "until in the future", not "has a cooldown at all".
        val expired = FakeCompleterBackend(id = "expired", priority = 10).apply {
            cooldownState.recordParsedFailure(System.currentTimeMillis() - 1, "Rate limited")
        }
        val next = FakeCompleterBackend(id = "next", priority = 20)
        TranslationBackendRegistry.init(listOf(expired, next))

        assertSame(expired, TranslationBackendRegistry.complete(question()).backend)
        assertEquals(0, next.completeCalls.get())
    }

    // ── a pass past the cooldowns (a retry's) ───────────────────────────

    @Test fun `a pass past the cooldowns asks a completer in one and names it, and lifts no other skip`() = runBlocking<Unit> {
        val named = mutableListOf<String>()
        val translationOnly = FakeOnlineBackend(id = "deepl", priority = 5)
        val cannot = FakeCompleterBackend(id = "cannot", priority = 10, canComplete = false)
        val cooled = cooledDown("cooled", 20)
        val next = FakeCompleterBackend(id = "next", priority = 30)
        TranslationBackendRegistry.init(listOf(translationOnly, cannot, cooled, next))
        // The contrast: a question that is no retry passes it by.
        assertSame(next, TranslationBackendRegistry.complete(question()).backend)
        assertEquals(0, cooled.completeCalls.get())

        val result = TranslationBackendRegistry.complete(question(), pastCooldowns = true) { named += it.id }

        assertSame(cooled, result.backend)
        assertEquals(SentenceAnswer.Text("answer-from-cooled"), result.answer)
        assertEquals("named as it is asked, and nobody after it", listOf("cooled"), named)
        assertEquals("what cannot answer is still not asked", 0, cannot.completeCalls.get())
        assertEquals(0, translationOnly.translateCalls.get())
        assertNull("its answer cleared the cooldown, as any answer does", cooled.unavailableUntil())
    }

    @Test fun `a pass past the cooldowns asks an answerer while its answers are paused and while its cooldown runs`() = runBlocking<Unit> {
        val answerer = FakeAnswererBackend(id = "cloud", priority = 10)
        val next = FakeCompleterBackend(id = "gemini", priority = 20)
        TranslationBackendRegistry.init(listOf(answerer, next))

        answerer.pausedUntil = System.currentTimeMillis() + 60_000
        assertSame("paused", answerer, TranslationBackendRegistry.complete(question(), pastCooldowns = true).backend)

        answerer.cooldownState.recordParsedFailure(System.currentTimeMillis() + 60_000, "Rate limited")
        assertSame("paused and cooling", answerer, TranslationBackendRegistry.complete(question(), pastCooldowns = true).backend)

        assertEquals(2, answerer.answerCalls.get())
        assertEquals(0, next.completeCalls.get())
    }

    @Test fun `a failure on a pass past the cooldowns names the service it asked and leaves its cooldown as it was`() {
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

        val thrown = completeFailure(pastCooldowns = true)

        assertTrue("$thrown", thrown is CompletionFailedException)
        assertSame("the service it asked", cooled, (thrown as CompletionFailedException).backend)
        assertSame(cause, thrown.cause)
        assertEquals(1, cooled.completeCalls.get())
        assertEquals("the cooldown neither ended nor grew", until, state.unavailableUntil())
    }

    // ── answerers ────────────────────────────────────────────────────────

    @Test fun `an answerer is put the question whole and a completer its two messages`() = runBlocking<Unit> {
        val asked = question("the system", "the user")
        val answerer = FakeAnswererBackend(id = "cloud", priority = 10)
        TranslationBackendRegistry.init(listOf(answerer))

        val result = TranslationBackendRegistry.complete(asked)

        assertSame(answerer, result.backend)
        assertEquals(SentenceAnswer.Text("answer-from-cloud"), result.answer)
        assertEquals("the parts, as they were given", asked, answerer.lastQuestion)
        assertEquals("never asked to translate the question", 0, answerer.translateCalls.get())

        // The same question, to a completer after an answerer that fails.
        val failingAnswerer = FakeAnswererBackend(id = "cloud", priority = 10, answer = { throw IOException("synthetic") })
        val completer = FakeCompleterBackend(id = "gemini", priority = 20)
        TranslationBackendRegistry.init(listOf(failingAnswerer, completer))

        val next = TranslationBackendRegistry.complete(asked)

        assertSame("an answerer's failure falls through", completer, next.backend)
        assertEquals("the system" to "the user", completer.lastPrompt)
        assertEquals(1, failingAnswerer.answerCalls.get())
        assertEquals(0, failingAnswerer.recordSuccessCalls.get())
    }

    @Test fun `an answerer's cut answer is returned as it said it, and a completer's reply is never cut`() = runBlocking<Unit> {
        val cut = FakeAnswererBackend(id = "cloud", priority = 10, answer = { SentenceAnswer.Text("Cut off mid", cut = true) })
        val next = FakeCompleterBackend(id = "gemini", priority = 20)
        TranslationBackendRegistry.init(listOf(cut, next))

        val result = TranslationBackendRegistry.complete(question())

        assertEquals(SentenceAnswer.Text("Cut off mid", cut = true), result.answer)
        assertSame(cut, result.backend)
        assertEquals("an answer, so nobody else is asked", 0, next.completeCalls.get())
        assertEquals(1, cut.recordSuccessCalls.get())

        TranslationBackendRegistry.init(listOf(next))
        assertEquals(SentenceAnswer.Text("answer-from-gemini", cut = false), TranslationBackendRegistry.complete(question()).answer)
    }

    @Test fun `out of scope is an answer, so the pass ends with it and nobody else is asked`() = runBlocking<Unit> {
        val named = mutableListOf<String>()
        val answerer = FakeAnswererBackend(id = "cloud", priority = 10, answer = { SentenceAnswer.OutOfScope })
        val next = FakeCompleterBackend(id = "gemini", priority = 20)
        TranslationBackendRegistry.init(listOf(answerer, next))

        val result = TranslationBackendRegistry.complete(question()) { named += it.id }

        assertEquals(SentenceAnswer.OutOfScope, result.answer)
        assertSame(answerer, result.backend)
        assertEquals("no fall-through to the next service", 0, next.completeCalls.get())
        assertEquals(listOf("cloud"), named)
        assertEquals("the service answered", 1, answerer.recordSuccessCalls.get())
    }

    @Test fun `a blank text from an answerer is a failure and falls through`() = runBlocking<Unit> {
        val blank = FakeAnswererBackend(id = "cloud", priority = 10, answer = { SentenceAnswer.Text("  \n") })
        val next = FakeCompleterBackend(id = "gemini", priority = 20)
        TranslationBackendRegistry.init(listOf(blank, next))

        assertSame(next, TranslationBackendRegistry.complete(question()).backend)
        assertEquals(1, blank.answerCalls.get())
        assertEquals(0, blank.recordSuccessCalls.get())
    }

    @Test fun `an answerer is skipped, unnamed, while its answers are paused or its cooldown runs, and asked once neither does`() = runBlocking<Unit> {
        val named = mutableListOf<String>()
        val answerer = FakeAnswererBackend(id = "cloud", priority = 10)
        val next = FakeCompleterBackend(id = "gemini", priority = 20)
        TranslationBackendRegistry.init(listOf(answerer, next))
        suspend fun ask() = TranslationBackendRegistry.complete(question()) { named += it.id }.backend

        // A pause of its answers alone: no cooldown, so a translation still
        // goes to it.
        answerer.pausedUntil = System.currentTimeMillis() + 60_000
        assertSame(next, ask())
        assertEquals(0, answerer.answerCalls.get())
        assertEquals(listOf("gemini"), named)
        assertNull("the pause is no cooldown", answerer.unavailableUntil())
        assertSame(answerer, TranslationBackendRegistry.translate("hi", "ja", "en").backend)

        // A pause that has passed skips nothing.
        named.clear()
        answerer.pausedUntil = System.currentTimeMillis() - 1
        assertSame(answerer, ask())
        assertEquals(listOf("cloud"), named)

        // The cooldown its translations share skips it too, the pause that
        // has passed still standing beside it: the later of the two decides.
        named.clear()
        answerer.cooldownState.recordParsedFailure(System.currentTimeMillis() + 60_000, "Rate limited")
        assertSame(next, ask())
        assertEquals(1, answerer.answerCalls.get())
        assertEquals(listOf("gemini"), named)
    }

    // ── naming who is asked ──────────────────────────────────────────────

    @Test fun `the pass names each completer as it starts asking it, and never one it skips`() = runBlocking<Unit> {
        val named = mutableListOf<String>()
        // What had been named when each completer was asked: its own name
        // comes before its asking, and nothing after it before it answers.
        val namedWhenAsked = mutableMapOf<String, List<String>>()
        val cannot = FakeCompleterBackend(id = "cannot", priority = 10, canComplete = false)
        val cooled = cooledDown("cooled", 20)
        val broken = FakeCompleterBackend(
            id = "broken", priority = 30,
            answer = { _, _ -> namedWhenAsked["broken"] = named.toList(); throw IOException("synthetic") },
        )
        val blank = FakeCompleterBackend(
            id = "blank", priority = 40, answer = { _, _ -> namedWhenAsked["blank"] = named.toList(); "  " },
        )
        val answering = FakeCompleterBackend(
            id = "answering", priority = 50, answer = { _, _ -> namedWhenAsked["answering"] = named.toList(); "it" },
        )
        val unused = FakeCompleterBackend(id = "unused", priority = 60)
        TranslationBackendRegistry.init(listOf(
            FakeOnlineBackend(id = "deepl", priority = 5), cannot, cooled, broken, blank, answering, unused,
        ))

        val result = TranslationBackendRegistry.complete(question()) { named += it.id }

        assertSame(answering, result.backend)
        assertEquals("the tried, in order; never the skipped or the unreached", listOf("broken", "blank", "answering"), named)
        assertEquals(listOf("broken"), namedWhenAsked["broken"])
        assertEquals(listOf("broken", "blank"), namedWhenAsked["blank"])
        assertEquals(listOf("broken", "blank", "answering"), namedWhenAsked["answering"])

        // A pass with nothing to try names nobody.
        TranslationBackendRegistry.init(listOf(cannot, cooled))
        named.clear()
        val thrown = runCatching { TranslationBackendRegistry.complete(question()) { named += it.id } }.exceptionOrNull()
        assertTrue("$thrown", thrown is CompletionFailedException)
        assertEquals(emptyList<String>(), named)
    }

    @Test fun `a cancellation thrown where a completer is named ends the pass before anyone is asked`() {
        // How the Ask session stops a pass whose question was put away.
        val first = FakeCompleterBackend(id = "first", priority = 10)
        val second = FakeCompleterBackend(id = "second", priority = 20)
        TranslationBackendRegistry.init(listOf(first, second))

        val thrown = runCatching {
            runBlocking { TranslationBackendRegistry.complete(question()) { throw CancellationException("put away") } }
        }.exceptionOrNull()

        assertTrue("$thrown", thrown is CancellationException)
        assertFalse("never turned into a failed pass", thrown is CompletionFailedException)
        assertEquals(0, first.completeCalls.get())
        assertEquals(0, second.completeCalls.get())
    }

    // ── falling through ──────────────────────────────────────────────────

    @Test fun `a failure falls through and the answer is credited to the completer that gave it`() = runBlocking<Unit> {
        val broken = failing("broken", 10, IOException("synthetic"))
        val crashing = failing("crashing", 20, IllegalStateException("engine"))
        val answering = FakeCompleterBackend(id = "answering", priority = 30, requiresInternet = false)
        TranslationBackendRegistry.init(listOf(broken, crashing, answering))

        val result = TranslationBackendRegistry.complete(question())

        assertSame(answering, result.backend)
        assertEquals(SentenceAnswer.Text("answer-from-answering"), result.answer)
        assertEquals(1, broken.completeCalls.get())
        assertEquals(1, crashing.completeCalls.get())
    }

    @Test fun `a blank reply is a failure and falls through`() = runBlocking<Unit> {
        val blank = FakeCompleterBackend(id = "blank", priority = 10, answer = { _, _ -> " \n\t " })
        val empty = FakeCompleterBackend(id = "empty", priority = 20, answer = { _, _ -> "" })
        val answering = FakeCompleterBackend(id = "answering", priority = 30)
        TranslationBackendRegistry.init(listOf(blank, empty, answering))

        val result = TranslationBackendRegistry.complete(question())

        assertSame(answering, result.backend)
        assertEquals(1, blank.completeCalls.get())
        assertEquals(1, empty.completeCalls.get())
        assertEquals("a blank winner records no success", 0, blank.recordSuccessCalls.get())
    }

    @Test fun `a blank reply from the only completer ends the pass with that completer named`() {
        val blank = FakeCompleterBackend(id = "blank", priority = 10, answer = { _, _ -> "   " })
        TranslationBackendRegistry.init(listOf(blank))

        val thrown = completeFailure()

        assertTrue("$thrown", thrown is CompletionFailedException)
        assertSame(blank, (thrown as CompletionFailedException).backend)
    }

    // ── recordSuccess ────────────────────────────────────────────────────

    @Test fun `a win records success on the winning completer and a failure records none`() = runBlocking<Unit> {
        val broken = failing("broken", 10, IOException("synthetic"))
        val winner = FakeCompleterBackend(id = "winner", priority = 20)
        val unused = FakeCompleterBackend(id = "unused", priority = 30)
        TranslationBackendRegistry.init(listOf(broken, winner, unused))

        TranslationBackendRegistry.complete(question())

        assertEquals(0, broken.recordSuccessCalls.get())
        assertEquals(1, winner.recordSuccessCalls.get())
        assertEquals(0, unused.recordSuccessCalls.get())
    }

    @Test fun `a win clears the rung a past failure left, as a translated text does`() = runBlocking<Unit> {
        // The counter above says recordSuccess was called; this says it was
        // called with an attempt time that lets it act (the state refuses a
        // success older than its failure). A 5xx left the rate-limit ladder
        // one rung up, and that cooldown has run out on the state's clock.
        val clock = AtomicLong(System.currentTimeMillis())
        val state = CooldownState(context = null, backendId = "winner", nowMs = { clock.get() })
        state.recordLadderFailure(CooldownLadder.RateLimit, "Server error", CooldownCause.SERVER_ERROR)
        clock.addAndGet(61_000)
        val winner = FakeCompleterBackend(id = "winner", priority = 10, cooldownState = state)
        assertNull("precondition: the cooldown has run out", winner.unavailableUntil())
        TranslationBackendRegistry.init(listOf(winner))

        TranslationBackendRegistry.complete(question())

        // Without the clear, the next failure would take the second rung
        // (10 minutes); after it, the first (1 minute).
        state.recordLadderFailure(CooldownLadder.RateLimit, "Server error", CooldownCause.SERVER_ERROR)
        assertEquals(clock.get() + 60_000L, state.unavailableUntil())
    }

    // ── cancellation ─────────────────────────────────────────────────────

    @Test fun `a cancellation from a completer is re-thrown and nothing after it is tried`() {
        val cancelled = FakeCompleterBackend(
            id = "cancelled", priority = 10, answer = { _, _ -> throw CancellationException("question put away") },
        )
        val next = FakeCompleterBackend(id = "next", priority = 20)
        TranslationBackendRegistry.init(listOf(cancelled, next))

        val thrown = completeFailure()

        assertTrue("$thrown", thrown is CancellationException)
        assertFalse("never turned into a failed pass", thrown is CompletionFailedException)
        assertEquals(0, next.completeCalls.get())
        assertEquals(0, cancelled.recordSuccessCalls.get())
    }

    // ── nobody answered ──────────────────────────────────────────────────

    @Test fun `when nobody answers the failure names the first completer tried and what it threw`() {
        val firstCause = OpenAiRateLimitException(BackendFailure(BackendFailureKind.RATE_LIMITED, 429))
        val secondCause = IOException("second")
        val thirdCause = IllegalStateException("third")
        // A cooled-down completer ahead of them: "first tried" is not
        // "first registered".
        val skipped = cooledDown("skipped", 5)
        val first = failing("first", 10, firstCause)
        val second = failing("second", 20, secondCause)
        val third = FakeCompleterBackend(
            id = "third", priority = 30, requiresInternet = false, answer = { _, _ -> throw thirdCause },
        )
        TranslationBackendRegistry.init(listOf(skipped, first, second, third))

        val thrown = completeFailure()

        assertTrue("$thrown", thrown is CompletionFailedException)
        thrown as CompletionFailedException
        assertSame("the first completer tried, not the last", first, thrown.backend)
        assertSame("what the first one threw, not the last one's", firstCause, thrown.cause)
        assertEquals(1, first.completeCalls.get())
        assertEquals(1, second.completeCalls.get())
        assertEquals(1, third.completeCalls.get())
        assertEquals(0, skipped.completeCalls.get())
    }

    @Test fun `when nothing is tried the failure carries neither backend nor cause`() {
        val cases = mapOf(
            "an empty registry" to emptyList(),
            "no completer at all" to listOf<TranslationBackend>(
                FakeOnlineBackend(id = "deepl", priority = 10),
                FakeOfflineBackend(id = "mlkit", priority = 30),
            ),
            "every completer skipped" to listOf<TranslationBackend>(
                FakeCompleterBackend(id = "cannot", priority = 10, canComplete = false),
                cooledDown("cooled", 20),
                FakeOnlineBackend(id = "deepl", priority = 30),
            ),
        )
        for ((case, backends) in cases) {
            TranslationBackendRegistry.init(backends)

            val thrown = completeFailure()

            assertTrue("$case: $thrown", thrown is CompletionFailedException)
            assertNull("$case: backend", (thrown as CompletionFailedException).backend)
            assertNull("$case: cause", thrown.cause)
            TranslationBackendRegistry.close()
        }
    }

    // ── the error pill's listener ────────────────────────────────────────

    @Test fun `complete never reports to the online attempt listener, where a translation pass does`() {
        val reports = mutableListOf<List<OnlineAttempt>>()
        TranslationBackendRegistry.onlineAttemptListener = { reports += it }
        val online = FakeCompleterBackend(id = "online", priority = 10, requiresInternet = true)
        TranslationBackendRegistry.init(listOf(online))

        // The precondition and contrast: a translation over this very
        // online fake reports its attempt.
        runBlocking { TranslationBackendRegistry.translate("hi", "ja", "en") }
        assertEquals("a translation pass reports", 1, reports.size)
        reports.clear()

        runBlocking { TranslationBackendRegistry.complete(question()) }
        assertTrue("a winning completion reports nothing: $reports", reports.isEmpty())

        TranslationBackendRegistry.init(listOf(
            failing("refused", 10, OpenAiRateLimitException(BackendFailure(BackendFailureKind.RATE_LIMITED, 429))),
            failing("unreachable", 20, IOException("synthetic connection failure")),
        ))
        val thrown = completeFailure()
        assertTrue("$thrown", thrown is CompletionFailedException)
        assertTrue("a failed completion reports nothing: $reports", reports.isEmpty())
    }
}
