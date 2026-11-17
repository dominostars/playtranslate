package com.playtranslate.ask

import com.playtranslate.translation.CompletionFailedException
import com.playtranslate.translation.FakeCompleterBackend
import com.playtranslate.translation.SentenceAnswer
import com.playtranslate.translation.SentenceQuestion
import com.playtranslate.translation.TranslationBackendRegistry.Completion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [WordExplanations]: the questions asked about the words of one list, one
 * [AskSession] per asked word.
 *  - a word's question is put with [AskPrompts.build] for an
 *    [AskQuestion.Word] of that word, about its own sentence, and its state
 *    is its session's: asking (the service named), answered, failed, asked
 *    again;
 *  - the words do not wait on one another, and one's answer is never
 *    another's;
 *  - the owner hears of a state that moved by itself, once per move, and of
 *    nothing it did itself: a question put away (closed, not retained,
 *    cleared) is cancelled, writes nothing afterwards, does not call back
 *    and leaves nothing running;
 *  - [WordExplanations.retain] keeps a question by its key and by the word
 *    in its sentence that it was asked about.
 *
 * Pure JVM, on an unconfined scope like [AskSessionTest]: every state lands
 * inside the call that causes it.
 */
class WordExplanationsTest {

    /** One call to the LLM, parked until the test answers it; whether its
     *  pass was to ask past the cooldowns (a retry's). */
    private class Call(val asked: SentenceQuestion, val pastCooldowns: Boolean) {
        val reply = CompletableDeferred<Completion>()
        val sawCancellation = CompletableDeferred<Unit>()
    }

    private val calls = mutableListOf<Call>()
    private val complete: AskPass = { asked, pastCooldowns, onAttempt ->
        val call = Call(asked, pastCooldowns).also { calls += it }
        onAttempt(online)
        try {
            call.reply.await()
        } catch (e: CancellationException) {
            call.sawCancellation.complete(Unit)
            throw e
        }
    }

    private val online = FakeCompleterBackend(id = "openai", priority = 10, displayName = "OpenAI", requiresInternet = true)

    private val scopeJob = SupervisorJob()
    private val scope = CoroutineScope(scopeJob + Dispatchers.Unconfined)

    @After fun tearDown() {
        scopeJob.cancel()
    }

    /** How many times the owner was called back. */
    private var changes = 0
    private val explanations = WordExplanations(scope, onChange = { changes++ }, complete = complete)

    private val sentence = AskSentence("猫が魚を食べた", sourceLang = "ja", targetLang = "en")
    private val cat = WordInSentence(sentence, "猫", "ねこ")
    private val eat = WordInSentence(sentence, "食べる", "たべる", listOf("食べた"))
    private val title = "In this sentence"

    private fun question(subject: WordInSentence) =
        AskQuestion.Word(subject.word, subject.reading, subject.surfaces, title)

    private fun answer(text: String) = Completion(SentenceAnswer.Text(text), online)

    @Test fun `a word with nothing asked is idle`() {
        assertEquals(AskState.Idle, explanations.state("cat"))
        assertEquals(0, changes)
    }

    @Test fun `a word's question is put about its own sentence, named, and answered under its key`() {
        explanations.ask("eat", eat, title)

        assertEquals(AskState.Asking(question(eat), "OpenAI"), explanations.state("eat"))
        assertEquals(
            "the word's question, in the form every question is put in",
            AskPrompts.build(question(eat), sentence),
            calls.single().asked,
        )
        assertEquals("the owner heard the question go out and its service named", 2, changes)

        calls.single().reply.complete(answer(" It is the past of to eat. "))

        assertEquals(
            AskState.Answered(question(eat), "It is the past of to eat.", "OpenAI"),
            explanations.state("eat"),
        )
        assertEquals("and the answer land", 3, changes)
        assertEquals("another word has none", AskState.Idle, explanations.state("cat"))
    }

    @Test fun `words are asked side by side and each gets its own answer`() {
        explanations.ask("cat", cat, title)
        explanations.ask("eat", eat, title)
        assertEquals("neither waits on the other", 2, calls.size)

        calls[1].reply.complete(answer("eat's"))
        assertEquals(AskState.Answered(question(eat), "eat's", "OpenAI"), explanations.state("eat"))
        assertEquals("the first is still out", AskState.Asking(question(cat), "OpenAI"), explanations.state("cat"))

        calls[0].reply.complete(answer("cat's"))
        assertEquals(AskState.Answered(question(cat), "cat's", "OpenAI"), explanations.state("cat"))
        assertEquals(AskState.Answered(question(eat), "eat's", "OpenAI"), explanations.state("eat"))
    }

    @Test fun `closing a question in flight cancels it, and its late answer writes nothing and calls nobody`() {
        explanations.ask("cat", cat, title)
        val heard = changes

        explanations.close("cat")

        assertEquals(AskState.Idle, explanations.state("cat"))
        assertTrue("the request was cancelled", calls.single().sawCancellation.isCompleted)
        calls.single().reply.complete(answer("too late"))
        assertEquals(AskState.Idle, explanations.state("cat"))
        assertEquals("the owner did this itself: no call back, then or later", heard, changes)
    }

    @Test fun `closing an answer puts it away without a call back`() {
        explanations.ask("cat", cat, title)
        calls.single().reply.complete(answer("an answer"))
        val heard = changes

        explanations.close("cat")

        assertEquals(AskState.Idle, explanations.state("cat"))
        assertEquals(heard, changes)
    }

    @Test fun `a failed question says so and is asked again by retry, which only a failure takes`() {
        explanations.ask("cat", cat, title)
        calls[0].reply.completeExceptionally(CompletionFailedException(null, null))
        assertEquals(AskState.Failed(question(cat)), explanations.state("cat"))
        val heard = changes

        explanations.retry("cat")

        assertEquals(2, calls.size)
        assertEquals("the retry's pass alone asks past the cooldowns", listOf(false, true), calls.map { it.pastCooldowns })
        assertEquals(AskState.Asking(question(cat), "OpenAI"), explanations.state("cat"))
        assertTrue("the owner hears the question go out again", changes > heard)

        calls[1].reply.complete(answer("this time"))
        assertEquals(AskState.Answered(question(cat), "this time", "OpenAI"), explanations.state("cat"))

        explanations.retry("cat")
        explanations.retry("never-asked")
        assertEquals("an answer is not asked again, and no key is no question", 2, calls.size)
    }

    @Test fun `asking a word again replaces its question`() {
        explanations.ask("cat", cat, title)
        explanations.ask("cat", cat, title)

        assertTrue("the first request was cancelled", calls[0].sawCancellation.isCompleted)
        calls[0].reply.complete(answer("the first"))
        assertEquals(AskState.Asking(question(cat), "OpenAI"), explanations.state("cat"))

        calls[1].reply.complete(answer("the second"))
        assertEquals(AskState.Answered(question(cat), "the second", "OpenAI"), explanations.state("cat"))
    }

    @Test fun `retain keeps a question by its key and by what it was asked about, and drops the rest in silence`() {
        explanations.ask("cat", cat, title)
        explanations.ask("eat", eat, title)
        calls[1].reply.complete(answer("eat's"))
        val heard = changes
        val seen = HashMap<String, WordInSentence>()

        explanations.retain { key, subject ->
            seen[key] = subject
            key == "eat"
        }

        assertEquals("each is offered with the word it was asked about", mapOf("cat" to cat, "eat" to eat), seen)
        assertEquals(AskState.Idle, explanations.state("cat"))
        assertTrue("the dropped one's request was cancelled", calls[0].sawCancellation.isCompleted)
        assertEquals(AskState.Answered(question(eat), "eat's", "OpenAI"), explanations.state("eat"))
        calls[0].reply.complete(answer("too late"))
        assertEquals("no call back for what the owner dropped", heard, changes)
    }

    @Test fun `the same word under another sentence is another subject`() {
        val elsewhere = cat.copy(sentence = AskSentence("猫がいる", sourceLang = "ja", targetLang = "en"))
        explanations.ask("cat", cat, title)
        calls.single().reply.complete(answer("in the first sentence"))

        explanations.retain { _, subject -> subject == elsewhere }

        assertFalse(cat == elsewhere)
        assertEquals("an answer about one sentence is none about another", AskState.Idle, explanations.state("cat"))
    }

    @Test fun `clear puts every question away in silence`() {
        explanations.ask("cat", cat, title)
        explanations.ask("eat", eat, title)
        calls[1].reply.complete(answer("eat's"))
        val heard = changes

        explanations.clear()

        assertEquals(AskState.Idle, explanations.state("cat"))
        assertEquals(AskState.Idle, explanations.state("eat"))
        assertTrue(calls[0].sawCancellation.isCompleted)
        assertEquals(heard, changes)
    }

    @Test fun `what is put away leaves nothing running on the scope, a request or a listener`() {
        fun running() = scopeJob.children.count()
        assertEquals(0, running())
        explanations.ask("cat", cat, title)
        explanations.ask("eat", eat, title)
        calls[1].reply.complete(answer("eat's"))
        assertTrue("precondition: a request in flight and two listeners", running() >= 3)

        explanations.close("cat")
        assertEquals("the answered word's listener alone", 1, running())
        explanations.clear()
        assertEquals(0, running())

        explanations.ask("cat", cat, title)
        explanations.retain { _, _ -> false }
        assertEquals(0, running())
    }

    @Test fun `a question kept through a retain still calls back when its answer lands`() {
        explanations.ask("cat", cat, title)
        explanations.retain { _, _ -> true }
        val heard = changes

        calls.single().reply.complete(answer("an answer"))

        assertEquals(AskState.Answered(question(cat), "an answer", "OpenAI"), explanations.state("cat"))
        assertEquals(heard + 1, changes)
    }
}
