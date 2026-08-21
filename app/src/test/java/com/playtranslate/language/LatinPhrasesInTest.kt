package com.playtranslate.language

import android.icu.text.BreakIterator
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the pure core of [LatinEngine.phrasesIn] — the sentence-level phrase
 * sweep feeding the words list: [LatinEngine.wordChains] (whitespace-only
 * adjacency, text-wide), [LatinEngine.phraseCandidatesIn] (all 2..maxWords
 * n-grams per chain), and [LatinEngine.claimPhraseOccurrences]
 * (longest-first, leftmost-tie, non-overlapping claiming). The membership
 * gates themselves are exercised by [WiktionaryPhrasesExistTest]; here the
 * gate-passed set is injected directly.
 *
 * Runs under Robolectric for android.icu's [BreakIterator].
 */
@RunWith(RobolectricTestRunner::class)
class LatinPhrasesInTest {

    private fun chains(text: String): List<List<IntRange>> =
        LatinEngine.wordChains(text, BreakIterator.getWordInstance(Locale.ENGLISH))

    /** Sweep [text] treating exactly [known] forms as dictionary phrases. */
    private fun sweep(text: String, known: Set<String>): List<PhraseOccurrence> {
        val ch = chains(text)
        val candidates = LatinEngine.phraseCandidatesIn(text, ch, 5) { it }
            .filter { it.form in known }
        return LatinEngine.claimPhraseOccurrences(text, ch, candidates)
    }

    @Test fun `chains split on punctuation and digits, chain on any whitespace`() {
        assertEquals(
            listOf(listOf("open"), listOf("the", "door")),
            chains("open, the door").map { c -> c.map { "open, the door".substring(it) } },
        )
        val wrapped = "give\nup now"
        assertEquals(
            listOf(listOf("give", "up", "now")),
            chains(wrapped).map { c -> c.map { wrapped.substring(it) } },
        )
        assertEquals(
            listOf(listOf("chapter"), listOf("begins")),
            chains("chapter 7 begins").map { c -> c.map { "chapter 7 begins".substring(it) } },
        )
    }

    @Test fun `longest match claims and blocks its sub-phrases`() {
        val text = "a great deal of effort"
        assertEquals(
            listOf(PhraseOccurrence(0..11, "a great deal", "a great deal")),
            sweep(text, setOf("a great deal", "great deal")),
        )
    }

    @Test fun `equal length overlap resolves leftmost`() {
        val text = "give up on it"
        assertEquals(
            listOf(PhraseOccurrence(0..6, "give up", "give up")),
            sweep(text, setOf("give up", "up on")),
        )
    }

    @Test fun `disjoint phrases all claim, in text order`() {
        val text = "give up and go on"
        assertEquals(
            listOf(
                PhraseOccurrence(0..6, "give up", "give up"),
                PhraseOccurrence(12..16, "go on", "go on"),
            ),
            sweep(text, setOf("go on", "give up")),
        )
    }

    @Test fun `punctuation break yields no cross-chain candidate`() {
        assertEquals(emptyList<PhraseOccurrence>(), sweep("great, deal", setOf("great deal")))
    }

    @Test fun `surface is the verbatim slice across collapsed whitespace`() {
        val text = "give  up"
        val candidates = LatinEngine.phraseCandidatesIn(text, chains(text), 5) { it }
        // The candidate FORM single-space-joins ("give up", the pack shape) …
        assertEquals(listOf("give up"), candidates.map { it.form })
        // … while the claimed occurrence's surface stays verbatim.
        assertEquals(
            listOf(PhraseOccurrence(0..7, "give  up", "give up")),
            LatinEngine.claimPhraseOccurrences(text, chains(text), candidates),
        )
    }

    @Test fun `repeated phrase claims each occurrence`() {
        val text = "give up then give up"
        assertEquals(
            listOf(
                PhraseOccurrence(0..6, "give up", "give up"),
                PhraseOccurrence(13..19, "give up", "give up"),
            ),
            sweep(text, setOf("give up")),
        )
    }
}
