package com.playtranslate.ui

import android.os.Bundle
import android.widget.EditText
import com.playtranslate.ask.AskState
import com.playtranslate.ask.Explanation

/**
 * A word of a sentence with an answer standing in its row: the word as the
 * row heads it, and the text of what its explain question brought
 * ([WordRowList.explanationOf]).
 */
data class ExplainedWord(val word: String, val answer: String)

/**
 * The note a sentence card starts with: what was answered about its
 * sentence where the card is opened from. First [asked], the Ask section's
 * answer about the sentence (null with none standing: nothing asked, a
 * question still out, a failure), then what the explain questions of its
 * words brought, for the words in [explained], in their order.
 *
 * One answer is the note as it is. Several are each put under a line that
 * says what they answer, the Ask question's title ([AskState.Answered.question])
 * or the word, with a blank line between them, so that each can be told
 * from the others; a line break is the one separator that reads the same
 * in every language. Nothing answered is no note.
 *
 * A word card needs none of this: its note is its word's answer.
 */
internal fun sentenceCardNote(asked: AskState.Answered?, explained: List<ExplainedWord>): String {
    val answers = listOfNotNull(asked?.takeIf { it.text.isNotBlank() }?.let { it.question.title to it.text }) +
        explained.map { it.word to it.answer }
    return when (answers.size) {
        0 -> ""
        1 -> answers.single().second
        else -> answers.joinToString("\n\n") { (about, answer) -> about + "\n" + answer }
    }
}

/**
 * The most of an answer that rides in a bundle, in characters. Nothing
 * else bounds an answer's length (an online model is given no limit, only
 * its two minutes), and a launch Intent and a sheet's saved state share
 * the Binder buffer whose overflow is a crash
 * ([transportPayloadFor] sizes the words beside it for the same reason).
 * A sheet's saved state holds the note up to four times (its arguments,
 * the editor's persisted ones, each Notes box's own text), so the note's
 * share stays under 80KB at two bytes a character; an answer worth a
 * card's note is a fraction of this.
 */
internal const val ANKI_NOTE_MAX_CHARS = 10_000

/**
 * [text] as it rides in a bundle: whole up to [ANKI_NOTE_MAX_CHARS], else
 * cut there with an ellipsis after it, which shows in the Notes box that
 * the text goes on. Never cut inside a character that takes two code
 * units. Every write of an answer into a bundle goes through here:
 * [putAnkiNotes], and a word's answer on its way to the word's page
 * ([putExplanation]).
 */
internal fun ankiNoteCapped(text: String): String {
    if (text.length <= ANKI_NOTE_MAX_CHARS) return text
    val end = if (text[ANKI_NOTE_MAX_CHARS - 1].isHighSurrogate()) ANKI_NOTE_MAX_CHARS - 1 else ANKI_NOTE_MAX_CHARS
    return text.substring(0, end) + "…"
}

private const val KEY_ANKI_NOTES = "anki_notes"

/**
 * Writes [notes], the text a card editor's Notes box starts with, under this
 * bundle's key for it: both editors' launch args, and the Activity
 * trampoline's extras that rebuild them. The text is held to
 * [ANKI_NOTE_MAX_CHARS] ([ankiNoteCapped]). A blank text writes nothing and
 * takes away one written before, as when the note's sentence is edited
 * away under it.
 */
fun Bundle.putAnkiNotes(notes: String?) {
    if (notes.isNullOrBlank()) remove(KEY_ANKI_NOTES) else putString(KEY_ANKI_NOTES, ankiNoteCapped(notes))
}

/** The text [putAnkiNotes] wrote; "" when the bundle carries none. */
fun Bundle.getAnkiNotes(): String = getString(KEY_ANKI_NOTES).orEmpty()

private const val EXPLANATION_BY_SUFFIX = "_answered_by"
private const val EXPLANATION_CUT_SUFFIX = "_cut"

/**
 * Writes [answer], a word's answer on its way to the word's page (the
 * page's launch arguments, the results screen's launch extras), under [key]
 * and two keys made from it: its text, held to [ANKI_NOTE_MAX_CHARS]
 * ([ankiNoteCapped]), who gave it, and whether it was cut. Null writes
 * nothing.
 */
fun Bundle.putExplanation(key: String, answer: Explanation?) {
    if (answer == null) return
    putString(key, ankiNoteCapped(answer.text))
    putString(key + EXPLANATION_BY_SUFFIX, answer.answeredBy)
    putBoolean(key + EXPLANATION_CUT_SUFFIX, answer.cut)
}

/** The answer [putExplanation] wrote under [key]; null when the bundle
 *  carries none. */
fun Bundle.getExplanation(key: String): Explanation? {
    val text = getString(key) ?: return null
    val answeredBy = getString(key + EXPLANATION_BY_SUFFIX) ?: return null
    return Explanation(text, answeredBy, getBoolean(key + EXPLANATION_CUT_SUFFIX))
}

/**
 * The sentence a card editor was opened with has been edited there. The
 * note its opener put into [field] ([putAnkiNotes] in [args]) is what was
 * answered about that sentence, and an answer about one sentence is none
 * about another, as on the page it came from; so a note still exactly as
 * the opener left it goes with the sentence. One the user changed is the
 * user's and stays. The opener's note leaves [args] either way: a restore
 * rebuilds from them, and a later edit compares against them. The one rule
 * for every Notes box of an editor, whichever tab it stands on; [field] is
 * null where the box is not built.
 */
internal fun dropNoteOfEditedSentence(args: Bundle, field: EditText?) {
    val openersNote = args.getAnkiNotes()
    if (field != null && field.text.toString() == openersNote) field.setText("")
    args.putAnkiNotes(null)
}
