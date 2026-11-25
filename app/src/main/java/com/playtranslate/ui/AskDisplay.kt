package com.playtranslate.ui

import android.content.Context
import com.playtranslate.R
import com.playtranslate.ask.AskState
import com.playtranslate.translation.TranslationError

/**
 * A question on screen, in words: what any surface that draws an [AskState]
 * shows of it, whichever way it draws (the Ask card's views, a word row's
 * markup). The one place a state becomes text, so the two cannot say
 * different things of the same answer or the same failure.
 */
internal class AskDisplay(
    /** The line over the answer: the question's own ([com.playtranslate.ask.AskQuestion.title]). */
    val title: String,
    /** "Asking <service>…", the answer, the out-of-scope line, or the
     *  failure with its reason under it. */
    val body: String,
    /** [body] reads as the app's word on the question (out of scope, a
     *  failure), not as an answer or a wait: drawn muted. */
    val muted: Boolean = false,
    /** A line under an answer the service cut at its length limit, saying
     *  so, so that a text that stops mid-sentence does not read as the
     *  whole answer. */
    val notice: String? = null,
    /** The line under an answer that says who gave it. */
    val credit: String? = null,
    /** Whether asking again is offered: after a failure, never after the
     *  service said the question is outside what it answers (asking again
     *  would get the same). */
    val retry: Boolean = false,
) {
    /** The lines under an answer as one block, for a surface that draws
     *  them so: [notice], then [credit]. Null with neither. */
    val footer: String? get() = listOfNotNull(notice, credit).joinToString("\n").ifEmpty { null }
}

/** [state] in words; null with nothing asked ([AskState.Idle]). */
internal fun askDisplay(ctx: Context, state: AskState): AskDisplay? = when (state) {
    AskState.Idle -> null
    is AskState.Asking -> AskDisplay(
        state.question.title,
        state.service?.let { ctx.getString(R.string.ask_status_asking, it) }
            ?: ctx.getString(R.string.ask_status_asking_unnamed),
    )
    is AskState.Answered -> AskDisplay(
        state.question.title, state.text,
        notice = if (state.cut) ctx.getString(R.string.ask_answer_cut) else null,
        credit = ctx.getString(R.string.ask_answered_by, state.answeredBy),
    )
    is AskState.OutOfScope -> AskDisplay(
        state.question.title, ctx.getString(R.string.ask_out_of_scope, state.answeredBy), muted = true,
    )
    is AskState.Failed -> AskDisplay(state.question.title, failureText(ctx, state), muted = true, retry = true)
}

/** The failure in words: the generic line, and under it the service's
 *  reason when the session says which online service refused and how. */
private fun failureText(ctx: Context, failed: AskState.Failed): String {
    val generic = ctx.getString(R.string.ask_failed)
    val service = failed.service ?: return generic
    val failure = failed.failure ?: return generic
    // The error pill's words for that refusal. An error's id only keys the
    // pill's once-per-outage rule, which an answer never enters, and the
    // session carries the service's name alone, so the name stands in for
    // it.
    val reason = TranslationErrorMessages.text(ctx, TranslationError.Service(service, service, failure))
    return "$generic\n$reason"
}
