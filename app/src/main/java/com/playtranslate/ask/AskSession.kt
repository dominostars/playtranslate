package com.playtranslate.ask

import com.playtranslate.translation.BackendFailure
import com.playtranslate.translation.CompletionFailedException
import com.playtranslate.translation.SentenceAnswer
import com.playtranslate.translation.SentenceQuestion
import com.playtranslate.translation.TranslationBackend
import com.playtranslate.translation.TranslationBackendRegistry
import com.playtranslate.translation.classifyBackendFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Where one question stands. Everything but [Idle] is "a question is in
 *  hand", which keeps a collapsed Ask section's card up unless the user
 *  shelved it ([AskSession.shelved]). */
sealed class AskState {
    object Idle : AskState()

    /** Sent; no answer yet. [service] is the display name of the service
     *  or model being asked, the next one's when the pass falls through;
     *  null until the pass picks one, which it does before it first
     *  suspends, so on a surface's immediate dispatcher no frame shows a
     *  question without its name. */
    data class Asking(val question: AskQuestion, val service: String? = null) : AskState()

    data class Answered(
        val question: AskQuestion,
        val text: String,
        /** The display name of the service or model that answered. */
        val answeredBy: String,
        /** The service stopped the answer at its length limit, so [text]
         *  ends before the answer does ([SentenceAnswer.Text.cut]). */
        val cut: Boolean = false,
    ) : AskState()

    /** The service asked said the question is about something other than
     *  the sentence, which is all it answers
     *  ([SentenceAnswer.OutOfScope]). Its word on the question, so nothing
     *  is tried again and no other service was asked; [answeredBy] is its
     *  display name. */
    data class OutOfScope(val question: AskQuestion, val answeredBy: String) : AskState()

    /** Nothing answered. [service] and [failure] say which online service
     *  refused first and how, for the reason line; both null when there is
     *  nothing to say beyond "it failed" (an on-device model, or every
     *  service in a cooldown). [chosen] is the one service the user had
     *  picked for the question ([AskSession.askWith]), null for a question
     *  put to the services in their order: whom asking again asks
     *  ([AskSession.retry]). */
    data class Failed(
        val question: AskQuestion,
        val service: String? = null,
        val failure: BackendFailure? = null,
        val chosen: TranslationBackend? = null,
    ) : AskState()
}

/** A finished answer as one surface hands it to the next
 *  ([AskSession.carried], [AskSession.restore]): the answer, and whether
 *  the user had shelved it ([AskSession.shelved]), so it comes back as it
 *  was left, on show or out of sight. */
data class CarriedAnswer(val answer: AskState.Answered, val shelved: Boolean = false)

/**
 * One pass putting a question to the user's services: [question], whether a
 * service is asked even while its cooldown or a pause of its answers runs
 * ([pastCooldowns], which only a retry asks for,
 * [TranslationBackendRegistry.complete]), and [onAttempt], told of each
 * service as the pass starts asking it. Production's is
 * [AskSession.registryPass]; a test substitutes its own.
 */
typealias AskPass = suspend (
    question: SentenceQuestion,
    pastCooldowns: Boolean,
    onAttempt: (TranslationBackend) -> Unit,
) -> TranslationBackendRegistry.Completion

/**
 * A question put to the one service the user picked for it, and to no
 * other ([AskSession.askWith]): [question] and that [backend]. Beside
 * [AskPass], not a case of it: there is no order to pass over, nobody to
 * name as the pass moves on, and no cooldown is waited out. Production's
 * is [AskSession.registryChosenPass]; a test substitutes its own.
 */
typealias AskChosenPass = suspend (
    question: SentenceQuestion,
    backend: TranslationBackend,
) -> TranslationBackendRegistry.Completion

/**
 * One results surface's Ask conversation: the question being asked about the
 * sentence on show, its answer, and whether the user has it out of sight
 * ([shelved]). The surface's view renders [state]; nothing else holds any
 * of it.
 *
 * The session is KEYED BY ITS SENTENCE (the text and the language pair,
 * [AskSentence]): [bind] says what is on show, and binding anything other
 * than what is bound ends the question in hand. A surface calls [bind] from
 * the one place it renders a result, every time, so an answer can never
 * outlive its sentence because some path forgot to reset it.
 *
 * At most one request is in flight. A new question, [close] or a new
 * sentence cancels it; a cancelled request writes nothing.
 */
class AskSession(
    private val scope: CoroutineScope,
    /** Puts a question to the user's services ([AskPass]). The registry's
     *  pass in production; a test substitutes it. */
    private val complete: AskPass = registryPass,
    /** Puts a question to the one service the user picked for it
     *  ([AskChosenPass]). The registry's in production; a test substitutes
     *  it. */
    private val completeWith: AskChosenPass = registryChosenPass,
) {
    private val _state = MutableStateFlow<AskState>(AskState.Idle)
    val state: StateFlow<AskState> = _state.asStateFlow()

    /** The sentence on show, null with none. */
    var sentence: AskSentence? = null
        private set

    private var job: Job? = null

    /** Whether the question in hand is shelved: the user took it out of
     *  sight ([shelve]) without ending it, so its request goes on and its
     *  answer stands, unseen. False with nothing asked, and a new question
     *  starts on show. Only a view that would otherwise show the question
     *  reads this; it is not part of [state], so whoever changes it redraws
     *  itself. */
    var shelved: Boolean = false
        private set

    /** Take the question in hand out of sight ([shelved]), or bring it
     *  back. Unlike [close], nothing ends: a request in flight goes on and
     *  its answer is kept. A no-op with nothing asked. */
    fun shelve(shelved: Boolean) {
        this.shelved = shelved && _state.value !is AskState.Idle
    }

    /** [sentence] is what the surface shows now (null: no result). Binding
     *  the sentence already bound changes nothing, so a surface may call
     *  this on every render. */
    fun bind(sentence: AskSentence?) {
        if (sentence == this.sentence) return
        this.sentence = sentence
        close()
    }

    /** Ask [question] about the bound sentence, in place of whatever was
     *  being asked. A no-op with no sentence bound. */
    fun ask(question: AskQuestion) {
        send(question)
    }

    /** Ask the question [on] answers again, of [backend] alone: the service
     *  the user picked for it from those that could answer
     *  ([TranslationBackendRegistry.setUpToAnswer]), whether or not it is
     *  switched on. A question like any other from here: [on] gives way to
     *  it at once, and its answer, or its failure, takes the card. A no-op
     *  unless [on] is the answer in hand: a pick is for the answer it was
     *  made over, and one that has since been put away, or whose sentence
     *  has changed, asks nothing. */
    fun askWith(backend: TranslationBackend, on: AskState.Answered) {
        if (_state.value !== on) return
        send(on.question, chosen = backend)
    }

    /** Ask the failed question again, a service in a cooldown or with its
     *  answers paused included ([AskPass]'s pastCooldowns): the user asks
     *  to hear from their services now, and the service whose failure is
     *  on screen may be in a cooldown that very failure started, which
     *  would leave nobody to ask. Of the one service the user had picked
     *  for it, where they had ([AskState.Failed.chosen]): the failure on
     *  screen is that service's, and an answer from another would not be
     *  what they asked for. A no-op in any other state. */
    fun retry() {
        (_state.value as? AskState.Failed)?.let { send(it.question, pastCooldowns = true, chosen = it.chosen) }
    }

    /** Puts [question] for [ask], [retry] and [askWith]. [pastCooldowns] is
     *  the retry's alone, and says something only of a question put to the
     *  services in their order; [chosen] is the one service asked instead
     *  of them ([AskChosenPass], which waits out no cooldown), named from
     *  the start, there being nobody else the question could go to. */
    private fun send(
        question: AskQuestion,
        pastCooldowns: Boolean = false,
        chosen: TranslationBackend? = null,
    ) {
        val about = sentence ?: return
        job?.cancel()
        // Before the state, so no reader sees the new question shelved.
        shelved = false
        _state.value = AskState.Asking(question, chosen?.displayName)
        job = scope.launch {
            val next = try {
                val prompt = AskPrompts.build(question, about)
                val completion = if (chosen != null) {
                    completeWith(prompt, chosen)
                } else {
                    complete(prompt, pastCooldowns) { backend ->
                        // Named only while this request is the live one, by
                        // the same promise as the write below: a pass that
                        // goes on after its cancel (a completer that
                        // returned or failed without seeing it) is stopped
                        // here, naming nobody.
                        ensureActive()
                        _state.value = AskState.Asking(question, backend.displayName)
                    }
                }
                val by = completion.backend.displayName
                when (val answer = completion.answer) {
                    is SentenceAnswer.Text -> AskState.Answered(question, answer.text.trim(), by, cut = answer.cut)
                    SentenceAnswer.OutOfScope -> AskState.OutOfScope(question, by)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: CompletionFailedException) {
                // The reason line names a service the user can act on, so
                // only an online service's refusal is carried.
                val service = e.backend?.takeIf { it.requiresInternet }
                AskState.Failed(
                    question,
                    service = service?.displayName,
                    failure = service?.let { _ -> e.cause?.let(::classifyBackendFailure) },
                    chosen = chosen,
                )
            } catch (_: Exception) {
                AskState.Failed(question, chosen = chosen)
            }
            // "A cancelled request writes nothing" is kept here, not left
            // to the completer: one that returned normally without seeing
            // the cancel would otherwise have its answer shown. A chosen
            // service's reply can come back that way: a switched-off
            // on-device model is released under NonCancellable after its
            // run ([TranslationBackendRegistry.completeWith]).
            ensureActive()
            _state.value = next
        }
    }

    /** Put the question away: the request in flight is cancelled and the
     *  answer is gone. The sentence stays bound. */
    fun close() {
        job?.cancel()
        job = null
        shelved = false
        _state.value = AskState.Idle
    }

    /** The finished answer in hand as it is handed to the surface that
     *  shows this result next ([restore]); null with none, or with a
     *  question still in flight. */
    fun carried(): CarriedAnswer? =
        (_state.value as? AskState.Answered)?.let { CarriedAnswer(it, shelved) }

    /** Take up a finished [answer] to a question about [sentence], as if it
     *  had been asked here, in place of whatever was being asked: a surface
     *  rebuilt around the same result (the capture sheet coming back from a
     *  page) carries its answer over, [shelved] as it was left
     *  ([CarriedAnswer]). */
    fun restore(sentence: AskSentence, answer: AskState.Answered, shelved: Boolean = false) {
        this.sentence = sentence
        close()
        // Before the state, as in [send]: the answer is first read shelved
        // or not, as it will stay.
        this.shelved = shelved
        _state.value = answer
    }

    companion object {
        /** The registry's pass ([TranslationBackendRegistry.complete]): the
         *  [AskPass] of every session production makes, the word questions'
         *  and the word page's included, which hand it to the sessions they
         *  make. One value, so a retry's pastCooldowns reaches the registry
         *  by the same line from every surface. */
        val registryPass: AskPass = { question, pastCooldowns, onAttempt ->
            TranslationBackendRegistry.complete(question, pastCooldowns, onAttempt)
        }

        /** The registry's question to one service
         *  ([TranslationBackendRegistry.completeWith]): the [AskChosenPass]
         *  of every session production makes. */
        val registryChosenPass: AskChosenPass = { question, backend ->
            TranslationBackendRegistry.completeWith(backend.id, question)
        }
    }
}
