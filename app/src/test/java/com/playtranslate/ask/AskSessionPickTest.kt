package com.playtranslate.ask

import com.playtranslate.translation.BackendFailure
import com.playtranslate.translation.BackendFailureKind
import com.playtranslate.translation.CompletionFailedException
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
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AskSession.askWith], the answer in hand asked for again from the one
 * service the user picked ([com.playtranslate.ui.AskPicker]): a question
 * like any other from the moment of the pick.
 *  - It is put to that service alone, through the session's seam for a
 *    chosen one ([AskChosenPass]) and never through the pass over the
 *    services in their order; the question is the answer's own, in both
 *    its forms.
 *  - The states: Asking with the service named at once (there is nobody
 *    else it could go to), then Answered by it, OutOfScope by it, or
 *    Failed, with the online service's refusal as on any question and the
 *    service carried as the one chosen.
 *  - Try again after that failure asks the same service, and again after a
 *    second failure; after an ordinary failure it asks the services in
 *    their order, past their cooldowns, as it did.
 *  - A pick is for the answer it was made over: on any other state, or an
 *    answer that is no longer the one in hand, it asks nothing.
 *  - It is one request like any other: X, a new question and another
 *    sentence end it, and it writes nothing after that, INCLUDING when the
 *    service's reply comes back all the same (a switched-off on-device
 *    model is released under NonCancellable after its run, so its reply
 *    can).
 *  - Through the registry's own seam, a service that is switched off
 *    answers a pick and an ordinary question still passes it by.
 *
 * As [AskSessionTest]: fakes whose every call parks on a gate, a scope on
 * [Dispatchers.Unconfined], pure JVM.
 */
class AskSessionPickTest {

    private fun Completion(text: String, backend: TranslationBackend) = Completion(SentenceAnswer.Text(text), backend)

    /** One question put through either seam, and the gate it waits on. */
    private class Call(val asked: SentenceQuestion, val pastCooldowns: Boolean?, val chosen: TranslationBackend?) {
        val reply = CompletableDeferred<Completion>()
        val sawCancellation = CompletableDeferred<Unit>()

        /** For a call that ignores cancellation: whether its coroutine was
         *  still active when the call returned its answer. */
        var activeWhenReturning: Boolean? = null
    }

    /** Every call of the pass in order ([Call.chosen] null). */
    private val passed = mutableListOf<Call>()

    /** Every call of the chosen seam. */
    private val picked = mutableListOf<Call>()

    /** While set, a call waits on its gate under [NonCancellable] and
     *  returns its answer normally even after its coroutine was cancelled. */
    private var ignoreCancellation = false

    private suspend fun await(call: Call): Completion =
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

    private val pass: AskPass = { asked, pastCooldowns, _ ->
        await(Call(asked, pastCooldowns, null).also { passed += it })
    }
    private val chosenPass: AskChosenPass = { asked, backend ->
        await(Call(asked, null, backend).also { picked += it })
    }

    private val scopeJob = SupervisorJob()
    private val scope = CoroutineScope(scopeJob + Dispatchers.Unconfined)

    @After fun tearDown() {
        scopeJob.cancel()
        TranslationBackendRegistry.close()
    }

    private val gemini = FakeCompleterBackend(id = "gemini", priority = 10, displayName = "Gemini")
    private val claude = FakeCompleterBackend(id = "claude", priority = 20, displayName = "Claude")
    private val qwen = FakeCompleterBackend(id = "qwen", priority = 30, displayName = "Qwen 3.5", requiresInternet = false)

    private val sentence = AskSentence("猫が好きです", sourceLang = "ja", targetLang = "en")
    private val other = AskSentence("犬が好きです", sourceLang = "ja", targetLang = "en")
    private val custom = AskQuestion.Custom("Why が and not は?")

    /** A session with [custom] answered by Gemini, and that answer. */
    private fun answered(): Pair<AskSession, AskState.Answered> {
        val session = AskSession(scope, pass, chosenPass)
        session.bind(sentence)
        session.ask(custom)
        passed.single().reply.complete(Completion("Gemini's answer.", gemini))
        return session to (session.state.value as AskState.Answered)
    }

    // ── the pick ─────────────────────────────────────────────────────────

    @Test fun `a pick asks the answer's question of that service alone, named at once, and its answer takes the card`() {
        val (session, answer) = answered()

        session.askWith(claude, answer)

        assertEquals("named from the start, never an unnamed frame", AskState.Asking(custom, "Claude"), session.state.value)
        val call = picked.single()
        assertSame(claude, call.chosen)
        assertEquals("the same question, in both its forms", AskPrompts.build(custom, sentence), call.asked)
        assertEquals("the pass in order is not run again", 1, passed.size)

        call.reply.complete(Completion("  Claude's answer.\n", claude))

        assertEquals(AskState.Answered(custom, "Claude's answer.", "Claude"), session.state.value)
    }

    @Test fun `a pick of the service that answered asks it again`() {
        val (session, answer) = answered()

        session.askWith(gemini, answer)

        assertEquals(AskState.Asking(custom, "Gemini"), session.state.value)
        picked.single().reply.complete(Completion("Another reading.", gemini))
        assertEquals(AskState.Answered(custom, "Another reading.", "Gemini"), session.state.value)
    }

    @Test fun `the picked service's cut answer and its out of scope word are its states`() {
        val (session, answer) = answered()
        session.askWith(claude, answer)
        picked[0].reply.complete(Completion(SentenceAnswer.Text("Cut sh", cut = true), claude))
        val cut = session.state.value
        assertEquals(AskState.Answered(custom, "Cut sh", "Claude", cut = true), cut)

        session.askWith(gemini, cut as AskState.Answered)
        picked[1].reply.complete(Completion(SentenceAnswer.OutOfScope, gemini))

        assertEquals(AskState.OutOfScope(custom, "Gemini"), session.state.value)
    }

    // ── a failed pick, and Try again ─────────────────────────────────────

    @Test fun `a failed pick is the card's failure, naming an online service's refusal and carrying the service chosen`() {
        val (session, answer) = answered()
        val rateLimited = BackendFailure(BackendFailureKind.RATE_LIMITED, 429)

        session.askWith(claude, answer)
        picked.single().reply.completeExceptionally(
            CompletionFailedException(claude, OpenAiRateLimitException(rateLimited)),
        )

        assertEquals(
            AskState.Failed(custom, service = "Claude", failure = rateLimited, chosen = claude),
            session.state.value,
        )
    }

    @Test fun `a failed pick of an on-device model, or one that threw anything else, names nothing and carries the service`() {
        val (session, answer) = answered()
        session.askWith(qwen, answer)
        picked[0].reply.completeExceptionally(
            CompletionFailedException(qwen, StructuralFailureException("Empty answer", BackendFailure.BAD_RESPONSE)),
        )
        assertEquals(AskState.Failed(custom, chosen = qwen), session.state.value)

        session.retry()
        picked[1].reply.completeExceptionally(IllegalStateException("engine"))
        assertEquals(AskState.Failed(custom, chosen = qwen), session.state.value)
    }

    @Test fun `Try again after a failed pick asks the same service, and again after it fails again`() {
        val (session, answer) = answered()
        session.askWith(claude, answer)
        picked[0].reply.completeExceptionally(CompletionFailedException(claude, null))

        session.retry()

        assertEquals(AskState.Asking(custom, "Claude"), session.state.value)
        assertSame(claude, picked[1].chosen)
        assertEquals("never the services in their order", 1, passed.size)

        picked[1].reply.completeExceptionally(CompletionFailedException(claude, null))
        session.retry()
        assertSame("the service chosen is kept through each failure", claude, picked[2].chosen)

        picked[2].reply.complete(Completion("At last.", claude))
        assertEquals(AskState.Answered(custom, "At last.", "Claude"), session.state.value)
    }

    @Test fun `Try again after an ordinary failure asks the services in their order, past their cooldowns`() {
        val session = AskSession(scope, pass, chosenPass)
        session.bind(sentence)
        session.ask(custom)
        passed[0].reply.completeExceptionally(CompletionFailedException(gemini, null))
        assertEquals("no service was chosen", AskState.Failed(custom, service = "Gemini"), session.state.value)

        session.retry()

        assertEquals(listOf(false, true), passed.map { it.pastCooldowns })
        assertTrue("the chosen seam is not used", picked.isEmpty())
    }

    @Test fun `a new question after a failed pick goes to the services in their order`() {
        val (session, answer) = answered()
        session.askWith(claude, answer)
        picked.single().reply.completeExceptionally(CompletionFailedException(claude, null))

        session.ask(custom)

        assertEquals(AskState.Asking(custom), session.state.value)
        assertEquals(2, passed.size)
        assertEquals(false, passed[1].pastCooldowns)
        assertEquals(1, picked.size)
    }

    // ── a pick is for the answer it was made over ────────────────────────

    @Test fun `a pick over anything but the answer in hand asks nothing`() {
        val (session, answer) = answered()

        // An answer equal in every field but another object: a later
        // answer of the same words is not the one the picker was opened on.
        session.askWith(claude, answer.copy())
        assertSame("the answer stands", answer, session.state.value)

        // Put away.
        session.close()
        session.askWith(claude, answer)
        assertEquals(AskState.Idle, session.state.value)

        // A question in flight.
        session.ask(custom)
        session.askWith(claude, answer)
        assertEquals(AskState.Asking(custom), session.state.value)

        // A failure, and another sentence.
        passed[1].reply.completeExceptionally(IllegalStateException("engine"))
        session.askWith(claude, answer)
        assertEquals(AskState.Failed(custom), session.state.value)
        session.bind(other)
        session.askWith(claude, answer)
        assertEquals(AskState.Idle, session.state.value)

        assertTrue("nobody was asked", picked.isEmpty())
    }

    // ── one request like any other ───────────────────────────────────────

    @Test fun `X, a new question and another sentence each end a pick in flight`() {
        val enders = mapOf<String, (AskSession) -> Unit>(
            "X" to { it.close() },
            "a new question" to { it.ask(custom) },
            "another sentence" to { it.bind(other) },
        )
        for ((what, end) in enders) {
            passed.clear()
            picked.clear()
            val (session, answer) = answered()
            session.askWith(claude, answer)
            val call = picked.single()

            end(session)

            assertTrue("$what: the pick's request was cancelled", call.sawCancellation.isCompleted)
            assertFalse("$what: ${session.state.value}", session.state.value == AskState.Asking(custom, "Claude"))
        }
    }

    @Test fun `a pick that was ended writes nothing when its reply comes back all the same`() {
        ignoreCancellation = true
        val (session, answer) = answered()
        session.askWith(qwen, answer)
        val call = picked.single()

        session.close()
        call.reply.complete(Completion("Too late.", qwen))

        assertEquals("the reply did come back, after the cancel", false, call.activeWhenReturning)
        assertEquals(AskState.Idle, session.state.value)
    }

    @Test fun `a pick that was ended writes no failure when it fails all the same`() {
        ignoreCancellation = true
        val (session, answer) = answered()
        session.askWith(qwen, answer)
        val call = picked.single()

        session.bind(other)
        call.reply.completeExceptionally(CompletionFailedException(qwen, null))

        assertEquals(AskState.Idle, session.state.value)
    }

    @Test fun `a pick is shown, whatever was shelved before it`() {
        val (session, answer) = answered()
        session.shelve(true)

        session.askWith(claude, answer)

        assertFalse("a new question starts on show", session.shelved)
    }

    // ── through the registry ─────────────────────────────────────────────

    @Test fun `through the registry a switched-off service answers a pick, and an ordinary question still passes it by`() {
        val on = FakeCompleterBackend(id = "gemini", priority = 10, displayName = "Gemini")
        val off = FakeCompleterBackend(
            id = "claude", priority = 20, displayName = "Claude", canComplete = false, setUp = true,
            answer = { _, _ -> "Claude's answer." },
        )
        TranslationBackendRegistry.init(listOf(off, on))
        val session = AskSession(scope)
        session.bind(sentence)
        session.ask(custom)
        val answer = session.state.value
        assertEquals(AskState.Answered(custom, "answer-from-gemini", "Gemini"), answer)

        session.askWith(off, answer as AskState.Answered)

        assertEquals(AskState.Answered(custom, "Claude's answer.", "Claude"), session.state.value)
        assertEquals(1, off.completeCalls.get())

        // The pick switched nothing on: the next question is Gemini's.
        session.ask(custom)
        assertEquals(AskState.Answered(custom, "answer-from-gemini", "Gemini"), session.state.value)
        assertEquals(1, off.completeCalls.get())
    }
}
