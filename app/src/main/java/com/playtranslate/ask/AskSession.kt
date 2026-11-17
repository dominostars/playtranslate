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

/** Where one question stands. Everything but [Idle] is "a question is on
 *  screen", which is what keeps a collapsed Ask section's card up. */
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
     *  service in a cooldown). */
    data class Failed(
        val question: AskQuestion,
        val service: String? = null,
        val failure: BackendFailure? = null,
    ) : AskState()
}

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
 * One results surface's Ask conversation: the question being asked about the
 * sentence on show, and its answer. The surface's view renders [state];
 * nothing else holds any of it.
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
) {
    private val _state = MutableStateFlow<AskState>(AskState.Idle)
    val state: StateFlow<AskState> = _state.asStateFlow()

    /** The sentence on show, null with none. */
    var sentence: AskSentence? = null
        private set

    private var job: Job? = null

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
        send(question, pastCooldowns = false)
    }

    /** Ask the failed question again, a service in a cooldown or with its
     *  answers paused included ([AskPass]'s pastCooldowns): the user asks
     *  to hear from their services now, and the service whose failure is
     *  on screen may be in a cooldown that very failure started, which
     *  would leave nobody to ask. A no-op in any other state. */
    fun retry() {
        (_state.value as? AskState.Failed)?.let { send(it.question, pastCooldowns = true) }
    }

    /** Puts [question] for [ask] and [retry]; [pastCooldowns] is the
     *  retry's alone. */
    private fun send(question: AskQuestion, pastCooldowns: Boolean) {
        val about = sentence ?: return
        job?.cancel()
        _state.value = AskState.Asking(question)
        job = scope.launch {
            val next = try {
                val completion = complete(AskPrompts.build(question, about), pastCooldowns) { backend ->
                    // Named only while this request is the live one, by the
                    // same promise as the write below: a pass that goes on
                    // after its cancel (a completer that returned or failed
                    // without seeing it) is stopped here, naming nobody.
                    ensureActive()
                    _state.value = AskState.Asking(question, backend.displayName)
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
                )
            } catch (_: Exception) {
                AskState.Failed(question)
            }
            // "A cancelled request writes nothing" is kept here, not left
            // to the completer: one that returned normally without seeing
            // the cancel would otherwise have its answer shown. (Today's
            // completers all throw the cancellation.)
            ensureActive()
            _state.value = next
        }
    }

    /** Put the question away: the request in flight is cancelled and the
     *  answer is gone. The sentence stays bound. */
    fun close() {
        job?.cancel()
        job = null
        _state.value = AskState.Idle
    }

    /** Take up a finished [answer] to a question about [sentence], as if it
     *  had been asked here, in place of whatever was being asked: a surface
     *  rebuilt around the same result (the capture sheet coming back from a
     *  page) carries its answer over. */
    fun restore(sentence: AskSentence, answer: AskState.Answered) {
        this.sentence = sentence
        close()
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
    }
}
