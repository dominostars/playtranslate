package com.playtranslate.ask

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * What a word's explain question brought, as it travels from the row it
 * stood in to the word's page: the answer's [text], who gave it
 * ([answeredBy]), and whether the service cut it at its length limit
 * ([cut]). The lines a finished answer is drawn with
 * ([AskState.Answered]) less its question, which is the drawing surface's
 * own; so the page draws the answer as the row did, the cut-off line
 * included. A card's note is its [text].
 */
data class Explanation(val text: String, val answeredBy: String, val cut: Boolean = false)

/** This answer, as [question]'s finished answer asked where it is drawn. */
fun Explanation.answering(question: AskQuestion): AskState.Answered =
    AskState.Answered(question, text, answeredBy, cut)

/** This finished answer as it travels ([Explanation]). */
val AskState.Answered.explanation: Explanation get() = Explanation(text, answeredBy, cut)

/**
 * The questions asked about the words of one list: how each word is used in
 * the sentence it was read in. Every asked word has an [AskSession] of its
 * own, so a word's question lives exactly as a sentence's does (asked, the
 * service being asked, answered or refused or failed, asked again, put
 * away) and the words do not wait on one another.
 *
 * A word is named by the [key] its list gives it (its row's id), and its
 * question is kept with the [WordInSentence] it was asked about: the owner
 * says which questions still stand ([retain]) by what each row is now.
 *
 * [onChange] hears of a question whose state moved by itself: its service
 * was named, its answer or its failure came. It never hears of anything
 * the owner did here ([ask] apart, whose question starts moving at once):
 * a question put away by [close], [retain] or [clear] goes back to where it
 * started, which is no move anyone is told of, so the owner is not called
 * back in the middle of its own change and redraws when it is done. What
 * is put away leaves nothing running: its request and its listener end.
 *
 * The questions run on [scope] and end with it. Main thread only, like the
 * list it serves.
 */
class WordExplanations(
    private val scope: CoroutineScope,
    private val onChange: () -> Unit,
    /** Puts a question to the user's services ([AskSession]'s seam, handed
     *  to every word's session). The registry's pass in production; a test
     *  substitutes it. */
    private val complete: AskPass = AskSession.registryPass,
) {
    private class Asked(val subject: WordInSentence, val session: AskSession) {
        /** Collects [session]'s state for [onChange]; stopped when the
         *  question is put away, or it would wait on a session nobody holds
         *  for as long as the list's scope lives. */
        var listener: Job? = null
    }

    private val asked = HashMap<String, Asked>()

    /** Where [key]'s question stands; [AskState.Idle] with none asked. */
    fun state(key: String): AskState = asked[key]?.session?.state?.value ?: AskState.Idle

    /**
     * Ask how [subject]'s word is used in its sentence, as [key]'s
     * question and in place of any it had. [title] is the line over the
     * answer ([AskQuestion.title]).
     */
    fun ask(key: String, subject: WordInSentence, title: String) {
        drop(key)
        val session = AskSession(scope, complete)
        val entry = Asked(subject, session)
        asked[key] = entry
        session.bind(subject.sentence)
        // Every state but the one a session starts in, which says nothing
        // has moved yet. Not "every value after the first": the collector
        // may first run after the question has already moved. A session is
        // back in that state only when [drop] ends it, which the owner did
        // itself.
        entry.listener = scope.launch {
            session.state.collect { state -> if (state != AskState.Idle) onChange() }
        }
        session.ask(AskQuestion.Word(subject.word, subject.reading, subject.surfaces, title))
    }

    /** Ask [key]'s failed question again; a no-op in any other state. */
    fun retry(key: String) {
        asked[key]?.session?.retry()
    }

    /** Put [key]'s question away: a request in flight is cancelled and the
     *  answer is gone. */
    fun close(key: String) = drop(key)

    /** Keep the questions [keep] says still stand, each given its key and
     *  the word it was asked about, and put the rest away. */
    fun retain(keep: (key: String, subject: WordInSentence) -> Boolean) {
        asked.filterNot { (key, entry) -> keep(key, entry.subject) }.keys.forEach(::drop)
    }

    /** Put every question away. */
    fun clear() {
        asked.keys.toList().forEach(::drop)
    }

    private fun drop(key: String) {
        val entry = asked.remove(key) ?: return
        entry.listener?.cancel()
        entry.session.close()
    }
}
