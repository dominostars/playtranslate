package com.playtranslate.ui

import com.playtranslate.audio.AudioSelection
import com.playtranslate.language.SourceLangId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Pins the send funnel's one hidden-words rule ([withoutHidden], applied at
 * the top of `Context.sendSentenceCard`): a hidden word leaves the word list,
 * the word-audio set and the per-word audio selections together, UNLESS it is
 * a highlighted target (the card is being made for it; the funnel un-hides it
 * once the send lands), and an input nothing touches passes through as the
 * same instance. The sheets hand over ALL their words, so this is the only
 * place the exclusion happens for every sentence send, one-tap included.
 */
class SentenceSendInputHiddenTest {

    private fun entry(word: String) =
        SentenceAnkiHtmlBuilder.WordEntry(word, reading = "", meaning = "m", freqScore = 0)

    private fun input(
        words: List<String>,
        selected: Set<String> = emptySet(),
        audio: Set<String> = emptySet(),
        selections: Map<String, AudioSelection> = emptyMap(),
    ) = SentenceSendInput(
        original = "s",
        translation = "t",
        words = words.map(::entry),
        selectedWords = selected,
        sourceLangId = SourceLangId.JA,
        screenshotPath = null,
        includeSentenceAudio = false,
        targetWordAudioWords = audio,
        wordSelections = selections,
    )

    // ─── F1 ───────────────────────────────────────────────────────────

    @Test
    fun `hidden word leaves every collection, the rest untouched`() {
        val src = input(
            words = listOf("a", "hidden", "c"),
            selected = setOf("c"),
            audio = setOf("hidden", "c"),
            selections = mapOf("hidden" to AudioSelection.Auto, "c" to AudioSelection.Auto),
        )
        val out = src.withoutHidden(setOf("hidden", "not-in-sentence"))
        assertEquals(listOf("a", "c"), out.words.map { it.word })
        assertEquals(setOf("c"), out.selectedWords)
        assertEquals(setOf("c"), out.targetWordAudioWords)
        assertEquals(setOf("c"), out.wordSelections.keys)
        // Everything else rides through unchanged.
        assertEquals(src.original, out.original)
        assertEquals(src.translation, out.translation)
        assertEquals(src.sourceLangId, out.sourceLangId)
    }

    // ─── F2 ───────────────────────────────────────────────────────────

    @Test
    fun `nothing hidden is the same instance`() {
        val src = input(words = listOf("a", "b"), selected = setOf("a"))
        assertSame(src, src.withoutHidden(emptySet()))
        assertSame(src, src.withoutHidden(setOf("elsewhere")))
    }

    // ─── F3 ───────────────────────────────────────────────────────────

    @Test
    fun `hidden word present only in the audio sets is still removed`() {
        // Stale audio state for a word no longer in the list (an edited
        // sentence), not highlighted: it must not survive in the audio sets.
        val src = input(
            words = listOf("a"),
            audio = setOf("ghost"),
            selections = mapOf("ghost" to AudioSelection.Auto),
        )
        val out = src.withoutHidden(setOf("ghost"))
        assertEquals(listOf("a"), out.words.map { it.word })
        assertEquals(emptySet<String>(), out.targetWordAudioWords)
        assertEquals(emptyMap<String, AudioSelection>(), out.wordSelections)
    }

    // ─── F4 ───────────────────────────────────────────────────────────

    @Test
    fun `highlighted target is exempt even when hidden`() {
        // The lens / one-tap path: the card is FOR this word. It rides in
        // every collection; nothing else is hidden, so the input is untouched.
        val src = input(
            words = listOf("a", "target"),
            selected = setOf("target"),
            audio = setOf("target"),
            selections = mapOf("target" to AudioSelection.Auto),
        )
        assertSame(src, src.withoutHidden(setOf("target")))
        // With another hidden word present, only that one goes.
        val mixed = input(
            words = listOf("a", "target", "other"),
            selected = setOf("target"),
            audio = setOf("target", "other"),
            selections = mapOf("target" to AudioSelection.Auto, "other" to AudioSelection.Auto),
        )
        val out = mixed.withoutHidden(setOf("target", "other"))
        assertEquals(listOf("a", "target"), out.words.map { it.word })
        assertEquals(setOf("target"), out.selectedWords)
        assertEquals(setOf("target"), out.targetWordAudioWords)
        assertEquals(setOf("target"), out.wordSelections.keys)
    }
}
