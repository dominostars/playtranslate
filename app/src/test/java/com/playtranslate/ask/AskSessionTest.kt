package com.playtranslate.ask

import com.playtranslate.translation.BackendFailure
import com.playtranslate.translation.BackendFailureKind
import com.playtranslate.translation.CompletionFailedException
import com.playtranslate.translation.CooldownCause
import com.playtranslate.translation.CooldownLadder
import com.playtranslate.translation.CooldownState
import com.playtranslate.translation.FakeCompleterBackend
import com.playtranslate.translation.OpenAiRateLimitException
import com.playtranslate.translation.SentenceAnswer
import com.playtranslate.translation.SentenceQuestion
import com.playtranslate.translation.StructuralFailureException
import com.playtranslate.translation.TranslationBackend
import com.playtranslate.translation.TranslationBackendRegistry
import com.playtranslate.translation.TranslationBackendRegistry.Completion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AskSession], one surface's question and answer, keyed by its sentence:
 *  - the states a question goes through (Idle, Asking while the LLM works,
 *    naming each service the pass starts asking and nobody once the request
 *    is cancelled, Answered with the reply trimmed and the service named,
 *    OutOfScope when the service says the question is not one it answers, Failed with the
 *    online service and failure worth naming, or nothing), and the question
 *    the pass is put, in both its forms ([AskPrompts.build]);
 *  - at most one request in flight: a new question, [AskSession.close] or
 *    another sentence cancels it, and a cancelled request writes nothing,
 *    INCLUDING when the LLM ignores the cancel and returns its answer late
 *    (the promise is the session's own, whatever its completer does);
 *  - binding: the same sentence changes nothing, another one (or none) ends
 *    the question;
 *  - [AskSession.retry] only from Failed, its pass alone asking past the
 *    cooldowns (a new question keeps them), through the registry's own
 *    pass too; and [AskSession.restore].
 *
 * The LLM is a fake whose every call parks on a gate the test opens. The
 * session's scope runs on [Dispatchers.Unconfined], so launching, resuming
 * and cancelling all happen synchronously on the test thread: after each
 * statement the state is final, with no waiting. Pure JVM; no
 * kotlinx-coroutines-test (it is not on the unit-test classpath).
 */
class AskSessionTest {

    /** A pass's result whose answer is [text]. */
    private fun Completion(text: String, backend: TranslationBackend) = Completion(SentenceAnswer.Text(text), backend)

    /** One question the session put to its LLM, whether the pass was to
     *  ask past the cooldowns (a retry's), and the gate the test answers it
     *  through. */
    private class Call(val asked: SentenceQuestion, val pastCooldowns: Boolean) {
        val system: String get() = asked.system
        val user: String get() = asked.user
        val reply = CompletableDeferred<Completion>()

        /** Completed when the call's own coroutine saw its cancellation (a
         *  call that honours cancellation). */
        val sawCancellation = CompletableDeferred<Unit>()

        /** For a call that ignores cancellation: whether its coroutine was
         *  still active when the call returned its answer. */
        var activeWhenReturning: Boolean? = null
    }

    /**
     * The session's LLM. Each call parks on its own gate. A call made while
     * [ignoreCancellation] is set waits on its gate under [NonCancellable]
     * and then returns its answer normally, even after its coroutine was
     * cancelled, never observing the cancel: an LLM that cannot be stopped.
     * It names nobody it asks, so its questions stay unnamed; the tests of
     * naming write their own passes.
     */
    private class FakeLlm {
        val calls = mutableListOf<Call>()
        var ignoreCancellation = false

        val complete: AskPass = { asked, pastCooldowns, _ ->
            val call = Call(asked, pastCooldowns).also { calls += it }
            if (ignoreCancellation) {
                val answer = withContext(NonCancellable) { call.reply.await() }
                call.activeWhenReturning = currentCoroutineContext().isActive
                answer
            } else {
                try {
                    call.reply.await()
                } catch (e: CancellationException) {
                    call.sawCancellation.complete(Unit)
                    throw e
                }
            }
        }
    }

    private val scopeJob = SupervisorJob()
    private val scope = CoroutineScope(scopeJob + Dispatchers.Unconfined)

    @After fun tearDown() {
        scopeJob.cancel()
        TranslationBackendRegistry.close()
    }

    private val llm = FakeLlm()
    private fun newSession(llm: FakeLlm = this.llm) = AskSession(scope, llm.complete)

    private val online = FakeCompleterBackend(id = "openai", priority = 10, displayName = "OpenAI", requiresInternet = true)
    private val onDevice = FakeCompleterBackend(id = "qwen", priority = 27, displayName = "Qwen 3.5", requiresInternet = false)

    private val sentence = AskSentence("猫が好きです", sourceLang = "ja", targetLang = "en")
    private val grammar = AskQuestion.Preset(
        AskPreset(AskPresets.GRAMMAR_BREAKDOWN, "Grammar breakdown", AskPrompts.GRAMMAR_BREAKDOWN),
    )
    private val custom = AskQuestion.Custom("Why が and not は?")

    // ── asking ───────────────────────────────────────────────────────────

    @Test fun `a fresh session is idle and asking with no sentence bound does nothing`() {
        val session = newSession()
        assertEquals(AskState.Idle, session.state.value)
        assertNull(session.sentence)

        session.ask(grammar)

        assertEquals(AskState.Idle, session.state.value)
        assertTrue("the LLM was never called", llm.calls.isEmpty())
    }

    @Test fun `a question is Asking while the LLM works, then Answered with the reply trimmed and the service named`() {
        val session = newSession()
        session.bind(sentence)

        session.ask(grammar)

        assertEquals(AskState.Asking(grammar), session.state.value)
        val call = llm.calls.single()
        val expected = AskPrompts.build(grammar, sentence)
        assertEquals("the system message", expected.system, call.system)
        assertEquals("the user message", expected.user, call.user)
        assertEquals("the question whole: its parts beside its messages", expected, call.asked)
        assertEquals("ja", call.asked.source)
        assertEquals("en", call.asked.target)
        assertEquals("猫が好きです", call.asked.sentence)

        call.reply.complete(Completion("  \n The answer.\n\n ", online))

        assertEquals(AskState.Answered(grammar, "The answer.", "OpenAI"), session.state.value)
    }

    @Test fun `an answer from an on-device model names that model`() {
        val session = newSession()
        session.bind(sentence)
        session.ask(custom)

        llm.calls.single().reply.complete(Completion("Because.", onDevice))

        assertEquals(AskState.Answered(custom, "Because.", "Qwen 3.5"), session.state.value)
    }

    @Test fun `an answer the service cut at its length limit is Answered and says so, where any other is not cut`() {
        val session = newSession()
        session.bind(sentence)

        session.ask(custom)
        llm.calls.last().reply.complete(Completion(SentenceAnswer.Text("  Cut off mid \n", cut = true), online))
        assertEquals(AskState.Answered(custom, "Cut off mid", "OpenAI", cut = true), session.state.value)

        session.ask(custom)
        llm.calls.last().reply.complete(Completion("Whole.", online))
        assertEquals(AskState.Answered(custom, "Whole.", "OpenAI", cut = false), session.state.value)
    }

    @Test fun `a service's word that the question is out of scope is its own state, naming the service`() {
        val session = newSession()
        session.bind(sentence)
        session.ask(custom)

        llm.calls.single().reply.complete(Completion(SentenceAnswer.OutOfScope, online))

        assertEquals(AskState.OutOfScope(custom, "OpenAI"), session.state.value)

        // Not a failure: there is nothing to try again.
        session.retry()
        assertEquals(AskState.OutOfScope(custom, "OpenAI"), session.state.value)
        assertEquals(1, llm.calls.size)

        // Put away like an answer, and another question can follow.
        session.close()
        assertEquals(AskState.Idle, session.state.value)
        session.ask(grammar)
        assertEquals(AskState.Asking(grammar), session.state.value)
    }

    @Test fun `an out of scope word from a cancelled request writes nothing`() {
        val session = newSession()
        session.bind(sentence)
        llm.ignoreCancellation = true
        session.ask(custom)
        val call = llm.calls.single()
        session.close()

        call.reply.complete(Completion(SentenceAnswer.OutOfScope, online))

        assertEquals("precondition: returned normally, cancelled", false, call.activeWhenReturning)
        assertEquals(AskState.Idle, session.state.value)
    }

    // ── naming who is asked ──────────────────────────────────────────────

    @Test fun `the question in flight names the completer the pass asks, then the next one when it falls through`() {
        val fellThrough = CompletableDeferred<Unit>()
        val reply = CompletableDeferred<Completion>()
        // As the registry's pass does: the first pick is named before the
        // pass first waits, the next one when the first has failed.
        val session = AskSession(scope) { _, _, onAttempt ->
            onAttempt(online)
            fellThrough.await()
            onAttempt(onDevice)
            reply.await()
        }
        session.bind(sentence)

        session.ask(grammar)
        assertEquals(AskState.Asking(grammar, "OpenAI"), session.state.value)

        fellThrough.complete(Unit)
        assertEquals(AskState.Asking(grammar, "Qwen 3.5"), session.state.value)

        reply.complete(Completion("Because.", onDevice))
        assertEquals(AskState.Answered(grammar, "Because.", "Qwen 3.5"), session.state.value)
    }

    @Test fun `a cancelled request names nobody, even when its pass goes on after the cancel`() {
        // A pass whose first completer cannot be stopped: it returns from
        // its wait under NonCancellable and fails, so the pass falls
        // through and would name, then ask, the next one.
        val gates = mutableListOf<CompletableDeferred<Unit>>()
        var askedAfterFallingThrough = 0
        val session = AskSession(scope) { _, _, onAttempt ->
            onAttempt(online)
            val gate = CompletableDeferred<Unit>().also { gates += it }
            withContext(NonCancellable) { gate.await() }
            onAttempt(onDevice)
            askedAfterFallingThrough++
            Completion("An answer.", onDevice)
        }
        session.bind(sentence)

        // Put away.
        session.ask(grammar)
        session.close()
        gates[0].complete(Unit)
        assertEquals(AskState.Idle, session.state.value)
        assertEquals("the pass stopped where it would have named the next", 0, askedAfterFallingThrough)

        // Superseded: the second question keeps its own name.
        session.ask(grammar)
        session.ask(custom)
        assertEquals(AskState.Asking(custom, "OpenAI"), session.state.value)
        gates[1].complete(Unit)
        assertEquals(AskState.Asking(custom, "OpenAI"), session.state.value)
        assertEquals(0, askedAfterFallingThrough)

        // The control: the live pass does go on, naming the next.
        gates[2].complete(Unit)
        assertEquals(1, askedAfterFallingThrough)
        assertEquals(AskState.Answered(custom, "An answer.", "Qwen 3.5"), session.state.value)
    }

    // ── one request in flight: supersede ─────────────────────────────────

    @Test fun `a second question cancels the first, whose answer never becomes the state`() {
        val session = newSession()
        session.bind(sentence)
        session.ask(grammar)
        val first = llm.calls.single()

        session.ask(custom)

        assertTrue("the first request's coroutine saw its cancellation", first.sawCancellation.isCompleted)
        assertEquals(AskState.Asking(custom), session.state.value)
        val second = llm.calls[1]
        assertEquals(AskPrompts.build(custom, sentence).user, second.user)

        second.reply.complete(Completion("the second answer", online))
        assertEquals(AskState.Answered(custom, "the second answer", "OpenAI"), session.state.value)

        // Opening the first gate late reaches nobody.
        first.reply.complete(Completion("the first answer", online))
        assertEquals(AskState.Answered(custom, "the second answer", "OpenAI"), session.state.value)
    }

    @Test fun `a superseded request that ignores its cancel and returns after the second answer writes nothing`() {
        val session = newSession()
        session.bind(sentence)
        llm.ignoreCancellation = true
        session.ask(grammar)
        val first = llm.calls.single()
        llm.ignoreCancellation = false
        session.ask(custom)
        val second = llm.calls[1]
        second.reply.complete(Completion("the second answer", online))
        assertEquals(AskState.Answered(custom, "the second answer", "OpenAI"), session.state.value)

        first.reply.complete(Completion("the first answer, late", online))

        assertEquals(
            "precondition: the first call returned normally from a cancelled coroutine",
            false, first.activeWhenReturning,
        )
        assertEquals(
            "the superseded answer must not replace the second one",
            AskState.Answered(custom, "the second answer", "OpenAI"), session.state.value,
        )
    }

    @Test fun `a superseded request that ignores its cancel and returns while the second is in flight writes nothing`() {
        val session = newSession()
        session.bind(sentence)
        llm.ignoreCancellation = true
        session.ask(grammar)
        val first = llm.calls.single()
        llm.ignoreCancellation = false
        session.ask(custom)

        first.reply.complete(Completion("the first answer, late", online))

        assertEquals("precondition: returned normally, cancelled", false, first.activeWhenReturning)
        assertEquals("still asking the second question", AskState.Asking(custom), session.state.value)
        llm.calls[1].reply.complete(Completion("the second answer", online))
        assertEquals(AskState.Answered(custom, "the second answer", "OpenAI"), session.state.value)
    }

    // ── close ────────────────────────────────────────────────────────────

    @Test fun `close returns to Idle, cancels the request in flight and keeps the sentence bound`() {
        val session = newSession()
        session.bind(sentence)
        session.ask(grammar)
        val call = llm.calls.single()

        session.close()

        assertEquals(AskState.Idle, session.state.value)
        assertTrue("the request's coroutine saw its cancellation", call.sawCancellation.isCompleted)
        call.reply.complete(Completion("too late", online))
        assertEquals("a cancelled request writes nothing", AskState.Idle, session.state.value)

        assertEquals("the sentence stays bound", sentence, session.sentence)
        session.ask(custom)
        assertEquals(AskState.Asking(custom), session.state.value)
        llm.calls[1].reply.complete(Completion("Because.", online))
        assertEquals(AskState.Answered(custom, "Because.", "OpenAI"), session.state.value)
    }

    @Test fun `close puts an answer away`() {
        val session = newSession()
        session.bind(sentence)
        session.ask(grammar)
        llm.calls.single().reply.complete(Completion("An answer.", online))

        session.close()

        assertEquals(AskState.Idle, session.state.value)
        assertEquals(sentence, session.sentence)
    }

    @Test fun `a request closed while it ignores its cancel writes nothing when it returns`() {
        val session = newSession()
        session.bind(sentence)
        llm.ignoreCancellation = true
        session.ask(grammar)
        val call = llm.calls.single()
        session.close()
        assertEquals(AskState.Idle, session.state.value)

        call.reply.complete(Completion("too late", online))

        assertEquals("precondition: returned normally, cancelled", false, call.activeWhenReturning)
        assertEquals("a cancelled request writes nothing", AskState.Idle, session.state.value)
    }

    // ── binding ──────────────────────────────────────────────────────────

    @Test fun `binding the same sentence again changes nothing`() {
        val session = newSession()
        session.bind(sentence)
        session.ask(grammar)
        val call = llm.calls.single()

        session.bind(AskSentence(sentence.text, sentence.sourceLang, sentence.targetLang))

        assertEquals("still asking", AskState.Asking(grammar), session.state.value)
        assertFalse("the request in flight was not cancelled", call.sawCancellation.isCompleted)
        call.reply.complete(Completion("An answer.", online))
        assertEquals(AskState.Answered(grammar, "An answer.", "OpenAI"), session.state.value)

        session.bind(sentence.copy())

        assertEquals("the answer stays", AskState.Answered(grammar, "An answer.", "OpenAI"), session.state.value)
        assertEquals(1, llm.calls.size)
    }

    @Test fun `binding another sentence, or none, ends the question`() {
        val others = mapOf(
            "another text" to sentence.copy(text = "犬が好きです"),
            "another source language" to sentence.copy(sourceLang = "zh"),
            "another target language" to sentence.copy(targetLang = "de"),
            "no sentence" to null,
        )
        for ((case, other) in others) {
            val llm = FakeLlm()
            val session = newSession(llm)

            // A request in flight is cancelled.
            session.bind(sentence)
            session.ask(grammar)
            val inFlight = llm.calls.single()
            session.bind(other)
            assertEquals(case, AskState.Idle, session.state.value)
            assertTrue("$case: the request was cancelled", inFlight.sawCancellation.isCompleted)
            assertEquals(case, other, session.sentence)
            inFlight.reply.complete(Completion("too late", online))
            assertEquals("$case: nothing written after", AskState.Idle, session.state.value)

            // An answer is put away.
            session.bind(sentence)
            session.ask(custom)
            llm.calls.last().reply.complete(Completion("An answer.", online))
            assertEquals(case, AskState.Answered(custom, "An answer.", "OpenAI"), session.state.value)
            session.bind(other)
            assertEquals(case, AskState.Idle, session.state.value)
        }
    }

    @Test fun `a request ended by another sentence while it ignores its cancel writes nothing when it returns`() {
        val session = newSession()
        session.bind(sentence)
        llm.ignoreCancellation = true
        session.ask(grammar)
        val call = llm.calls.single()
        val other = sentence.copy(text = "犬が好きです")
        session.bind(other)
        assertEquals(AskState.Idle, session.state.value)

        call.reply.complete(Completion("an answer about the old sentence", online))

        assertEquals("precondition: returned normally, cancelled", false, call.activeWhenReturning)
        assertEquals("an answer never outlives its sentence", AskState.Idle, session.state.value)
        assertEquals(other, session.sentence)
    }

    // ── failures ─────────────────────────────────────────────────────────

    /** Asks [question] and fails its call with [e]; returns the state. */
    private fun failedWith(e: Throwable, question: AskQuestion = grammar): AskState {
        val llm = FakeLlm()
        val session = newSession(llm)
        session.bind(sentence)
        session.ask(question)
        llm.calls.single().reply.completeExceptionally(e)
        return session.state.value
    }

    @Test fun `an online service's classified refusal is named with its failure`() {
        val rateLimited = BackendFailure(BackendFailureKind.RATE_LIMITED, 429)
        assertEquals(
            AskState.Failed(grammar, service = "OpenAI", failure = rateLimited),
            failedWith(CompletionFailedException(online, OpenAiRateLimitException(rateLimited))),
        )

        val billing = BackendFailure(BackendFailureKind.BILLING, 400)
        assertEquals(
            AskState.Failed(grammar, service = "OpenAI", failure = billing),
            failedWith(CompletionFailedException(online, StructuralFailureException("credit balance too low", billing))),
        )
    }

    @Test fun `an on-device model's failure names nothing`() {
        assertEquals(
            AskState.Failed(grammar, service = null, failure = null),
            failedWith(
                CompletionFailedException(
                    onDevice, StructuralFailureException("Empty answer", BackendFailure.BAD_RESPONSE),
                ),
            ),
        )
    }

    @Test fun `a pass that tried nothing names nothing`() {
        assertEquals(AskState.Failed(grammar, null, null), failedWith(CompletionFailedException(null, null)))
    }

    @Test fun `any other exception is a failure that names nothing`() {
        assertEquals(AskState.Failed(custom, null, null), failedWith(IllegalStateException("engine"), custom))
    }

    @Test fun `a cancellation from the LLM is never turned into a failure`() {
        val state = failedWith(CancellationException("put away"))
        assertFalse("$state", state is AskState.Failed)
    }

    @Test fun `the session's scope ending mid-request is never turned into a failure`() {
        val session = newSession()
        session.bind(sentence)
        session.ask(grammar)
        val call = llm.calls.single()

        scopeJob.cancel()

        assertTrue(call.sawCancellation.isCompleted)
        assertFalse("${session.state.value}", session.state.value is AskState.Failed)
    }

    // ── retry ────────────────────────────────────────────────────────────

    @Test fun `retry from Failed asks the same question again`() {
        val session = newSession()
        session.bind(sentence)
        session.ask(custom)
        llm.calls.single().reply.completeExceptionally(IllegalStateException("engine"))
        assertEquals(AskState.Failed(custom), session.state.value)

        session.retry()

        assertEquals(AskState.Asking(custom), session.state.value)
        assertEquals(2, llm.calls.size)
        assertEquals("the same prompt", llm.calls[0].user, llm.calls[1].user)
        llm.calls[1].reply.complete(Completion("Because.", online))
        assertEquals(AskState.Answered(custom, "Because.", "OpenAI"), session.state.value)
    }

    @Test fun `a retry's pass alone asks past the cooldowns, and the next question keeps them again`() {
        val session = newSession()
        session.bind(sentence)

        session.ask(custom)
        llm.calls[0].reply.completeExceptionally(IllegalStateException("engine"))
        session.retry()
        llm.calls[1].reply.completeExceptionally(IllegalStateException("engine"))
        session.retry()
        session.ask(grammar)

        assertEquals(
            "asked, retried, retried again, then a new question",
            listOf(false, true, true, false),
            llm.calls.map { it.pastCooldowns },
        )
    }

    @Test fun `retry from any other state does nothing`() {
        val session = newSession()
        session.retry()
        assertEquals("unbound and idle", AskState.Idle, session.state.value)

        session.bind(sentence)
        session.retry()
        assertEquals("bound and idle", AskState.Idle, session.state.value)
        assertTrue(llm.calls.isEmpty())

        session.ask(grammar)
        val inFlight = llm.calls.single()
        session.retry()
        assertEquals("asking", AskState.Asking(grammar), session.state.value)
        assertFalse("the request in flight goes on", inFlight.sawCancellation.isCompleted)
        assertEquals(1, llm.calls.size)

        inFlight.reply.complete(Completion("An answer.", online))
        session.retry()
        assertEquals("answered", AskState.Answered(grammar, "An answer.", "OpenAI"), session.state.value)
        assertEquals(1, llm.calls.size)
    }

    /**
     * The report (2026-11-16): a service whose failure starts a cooldown (a
     * 429, a 5xx) is passed by on the next pass, so Try again asked nobody
     * and failed at once. Through the registry's own pass, as production's
     * sessions ask: the model fails as Gemini does on a 503 (its cooldown
     * recorded, then the throw) and answers the next time it is asked.
     */
    @Test fun `Try again asks a service its own failure put in a cooldown, and its answer ends the cooldown, while a new question still passes it by`() {
        val cooldown = CooldownState(context = null, backendId = "gemini")
        var asked = 0
        val gemini = FakeCompleterBackend(
            id = "gemini", priority = 10, displayName = "Gemini", cooldownState = cooldown,
            answer = { _, _ ->
                asked++
                if (asked == 1) {
                    cooldown.recordLadderFailure(CooldownLadder.RateLimit, "Server error", CooldownCause.SERVER_ERROR)
                    throw StructuralFailureException("Gemini error 503", BackendFailure(BackendFailureKind.SERVER_ERROR, 503))
                }
                "Because."
            },
        )
        TranslationBackendRegistry.init(listOf(gemini))
        val session = AskSession(scope)
        session.bind(sentence)

        session.ask(custom)
        assertEquals(
            AskState.Failed(custom, "Gemini", BackendFailure(BackendFailureKind.SERVER_ERROR, 503)),
            session.state.value,
        )
        assertNotNull("precondition: the failure started a cooldown", gemini.unavailableUntil())

        // A new question passes the cooling service by, as it did.
        session.ask(grammar)
        assertEquals(AskState.Failed(grammar), session.state.value)
        assertEquals("nobody was asked", 1, asked)

        session.retry()

        assertEquals("asked again", 2, asked)
        assertEquals(AskState.Answered(grammar, "Because.", "Gemini"), session.state.value)
        assertNull("the answer ended the cooldown", gemini.unavailableUntil())
    }

    // ── restore ──────────────────────────────────────────────────────────

    @Test fun `restore binds the sentence and shows the answer, which the same sentence keeps and another ends`() {
        val answered = AskState.Answered(grammar, "A carried answer.", "OpenAI")
        val session = newSession()

        session.restore(sentence, answered)

        assertEquals(sentence, session.sentence)
        assertEquals(answered, session.state.value)

        session.bind(sentence.copy())
        assertEquals("the same sentence keeps it", answered, session.state.value)

        session.bind(sentence.copy(text = "犬が好きです"))
        assertEquals("another sentence ends it", AskState.Idle, session.state.value)
        assertTrue("restoring asked nothing", llm.calls.isEmpty())
    }

    @Test fun `restore takes the place of a question in flight about the same sentence, whose answer never shows`() {
        val answered = AskState.Answered(grammar, "A carried answer.", "OpenAI")
        val session = newSession()
        session.bind(sentence)
        session.ask(custom)
        val call = llm.calls.single()

        session.restore(sentence.copy(), answered)

        assertEquals(answered, session.state.value)
        assertTrue("the request in flight was cancelled", call.sawCancellation.isCompleted)
        call.reply.complete(Completion("too late", online))
        assertEquals("its answer never replaces the restored one", answered, session.state.value)
    }
}
