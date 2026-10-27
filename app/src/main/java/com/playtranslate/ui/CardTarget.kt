package com.playtranslate.ui

import android.content.Context
import android.os.Bundle
import android.util.Log
import com.playtranslate.Prefs
import com.playtranslate.language.ReadingOverride
import com.playtranslate.language.SourceLangId
import com.playtranslate.language.SourceLanguageEngines
import com.playtranslate.language.TokenSpan
import kotlinx.coroutines.CancellationException

private const val TAG = "CardTarget"

/**
 * The word a card is made for, keyed as its opener found it in the sentence:
 * the text it was found as ([surface], 弾けた) and the key its entry resolved
 * under ([lookupForm] and [lookupReading], 弾く read ひく). The word detail
 * page names one for its own word and for every cell of its Words and Other
 * matches sections, so a sentence card made from any of them is for that word
 * even where the sentence's analysis chose another there ([withCardTarget]).
 */
data class CardTarget(
    val surface: String,
    val lookupForm: String,
    val lookupReading: String?,
)

/**
 * A [CardTarget] resolved for one card: its words-table [row], shaped as the
 * sentence's own rows are, and the reading its surface takes on a card made
 * for it ([ReadingOverride]), null where the engine derives none.
 */
data class ResolvedCardTarget(
    val row: SentenceAnkiHtmlBuilder.WordEntry,
    val readingOverride: ReadingOverride?,
)

/** A sentence card's words with its own word in place ([withCardTarget]),
 *  and the reading override that comes with that word, if any. */
data class CardWords(
    val words: List<SentenceAnkiHtmlBuilder.WordEntry>,
    val readingOverride: ReadingOverride?,
)

/**
 * [words] (the sentence's own rows) with [target], the word the card is for,
 * in place. A row carrying the target's word, read the same way and found as
 * the same text, leaves the list as it is: the sentence's analysis chose that
 * word there, and its reading stands. Otherwise, when [text] holds the
 * target's surface, the target's row takes the place of the rows it
 * displaces and its [ReadingOverride] comes along: the row under its word
 * (the words table is keyed by written form, so a homograph read
 * differently gives way, 方 read ほう to 方 read かた, and so does the same
 * word found as other text, 弾く in 「ギターを弾く。」 when the card is for the
 * 弾けた after it) and the row found as its text (the word the analysis chose
 * there, 弾ける for 弾けた). With nothing displaced it joins at the front, as a
 * member word does (気 under 気になる). No [target], or a surface [text] no
 * longer holds (an edited sentence), changes nothing. Comparing the text is
 * also what restores a target after a saved-state rebuild, whose rows keep
 * the word but not the text it was found as. The ONE placement rule for
 * every card: the word editor's sentence tab and one-tap.
 */
fun withCardTarget(
    words: List<SentenceAnkiHtmlBuilder.WordEntry>,
    target: ResolvedCardTarget?,
    text: String,
): CardWords {
    val row = target?.row ?: return CardWords(words, null)
    // A row with no recorded surface was found as its own word.
    fun SentenceAnkiHtmlBuilder.WordEntry.foundAs() = surfaceForm.ifEmpty { word }
    val surface = row.foundAs()
    if (words.any { it.word == row.word && it.reading == row.reading && it.foundAs() == surface }) {
        return CardWords(words, null)
    }
    if (!text.contains(surface)) return CardWords(words, null)
    fun displaced(e: SentenceAnkiHtmlBuilder.WordEntry) = e.word == row.word || e.foundAs() == surface
    // Every row before the first displaced one is kept, so its index is the
    // same in the kept list.
    val at = words.indexOfFirst(::displaced).coerceAtLeast(0)
    val placed = words.filterNot(::displaced).toMutableList().apply { add(at, row) }
    return CardWords(placed, target.readingOverride)
}

/**
 * [target] resolved for a card on [word] (the card's key into its words
 * table) through the pipeline the sentence's rows come from: [resolveWordRows]
 * and the projection every words payload uses ([toWordEntries]), so the row
 * reads exactly as the words panel and the Anki words tables show the word,
 * keyed by [word] and found as the target's surface. Its override is the
 * engine's reading of that surface as this word
 * ([com.playtranslate.language.SourceLanguageEngine.occurrenceReading]). Null
 * when the key resolves to no row of its own (a miss splits into member rows,
 * none of them the word) or the lookup fails.
 */
suspend fun resolveCardTarget(
    ctx: Context,
    sourceLangId: SourceLangId,
    word: String,
    target: CardTarget,
): ResolvedCardTarget? = try {
    val appCtx = ctx.applicationContext
    val prefs = Prefs(appCtx)
    val engine = SourceLanguageEngines.get(appCtx, sourceLangId)
    val rows = resolveWordRows(
        appCtx,
        WordLookupContext(engine, prefs.targetLang, prefs.targetChineseVariant),
        listOf(TokenSpan(target.surface, target.lookupForm, target.lookupReading)),
    ).rows
    rows.firstOrNull { it.lookupForm == target.lookupForm }?.let { found ->
        val keyed = listOf(found.copy(displayWord = word, surface = target.surface))
        val row = LastSentenceCache.WordsPayload(
            keyed.toLegacyMap(), keyed.toSurfaceMap(), keyed.toEnrichmentMap(),
        ).toWordEntries().single()
        val reading = engine.occurrenceReading(
            target.surface, target.lookupForm, target.lookupReading,
            // A kana row shows no reading: the word is its own.
            lemmaReading = found.reading.ifEmpty { found.displayWord },
        )
        ResolvedCardTarget(row, reading?.let { ReadingOverride(target.surface, word, it) })
    }
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Log.w(TAG, "card target lookup failed: ${e.message}")
    null
}

private const val KEY_TARGET_SURFACE = "card_target_surface"
private const val KEY_TARGET_LOOKUP_FORM = "card_target_lookup_form"
private const val KEY_TARGET_LOOKUP_READING = "card_target_lookup_reading"

/** Writes [target] under this bundle's card-target keys: the word editor's
 *  launch args, and the Activity trampoline's extras that rebuild them. */
fun Bundle.putCardTarget(target: CardTarget) {
    putString(KEY_TARGET_SURFACE, target.surface)
    putString(KEY_TARGET_LOOKUP_FORM, target.lookupForm)
    putString(KEY_TARGET_LOOKUP_READING, target.lookupReading)
}

/** The [CardTarget] [putCardTarget] wrote; null when the bundle names none. */
fun Bundle.getCardTarget(): CardTarget? {
    val surface = getString(KEY_TARGET_SURFACE) ?: return null
    val lookupForm = getString(KEY_TARGET_LOOKUP_FORM) ?: return null
    return CardTarget(surface, lookupForm, getString(KEY_TARGET_LOOKUP_READING))
}

private const val KEY_OVERRIDE_SURFACE = "reading_override_surface"
private const val KEY_OVERRIDE_WORD = "reading_override_word"
private const val KEY_OVERRIDE_READING = "reading_override_reading"

/** Writes [override] under this bundle's reading-override keys, or clears
 *  them for null: the sentence content's persisted state, and the audio
 *  picker's extras. */
fun Bundle.putReadingOverride(override: ReadingOverride?) {
    if (override == null) {
        remove(KEY_OVERRIDE_SURFACE)
        remove(KEY_OVERRIDE_WORD)
        remove(KEY_OVERRIDE_READING)
        return
    }
    putString(KEY_OVERRIDE_SURFACE, override.surface)
    putString(KEY_OVERRIDE_WORD, override.word)
    putString(KEY_OVERRIDE_READING, override.reading)
}

/** The [ReadingOverride] [putReadingOverride] wrote; null when there is none. */
fun Bundle.getReadingOverride(): ReadingOverride? {
    val surface = getString(KEY_OVERRIDE_SURFACE) ?: return null
    val word = getString(KEY_OVERRIDE_WORD) ?: return null
    val reading = getString(KEY_OVERRIDE_READING) ?: return null
    return ReadingOverride(surface, word, reading)
}
