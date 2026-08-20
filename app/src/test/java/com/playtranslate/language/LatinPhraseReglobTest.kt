package com.playtranslate.language

import android.icu.text.BreakIterator
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the space-delimited phrase re-glob behind [LatinEngine.tokenize]:
 * [LatinEngine.wordStream] (word collection + whitespace adjacency),
 * [LatinEngine.phraseCandidates] (n-gram generation), and
 * [LatinEngine.fuseSpans] (longest-first claiming fuse). Load-bearing
 * semantics:
 *
 *  - candidates chain only across whitespace-ONLY separators — punctuation,
 *    hyphens, or digit runs break the chain ("great, deal" must never gate
 *    "great deal");
 *  - single-letter words participate in phrases ("a great deal", "il y a")
 *    even though they never emit alone (isLookupWorthy);
 *  - a fused span's surface is the VERBATIM source slice (span mapping
 *    re-finds surfaces by indexOf) while its lookupForm is the single-space
 *    join the packs store;
 *  - words not consumed by a phrase emit exactly as the pre-phrase
 *    tokenizer did.
 *
 * Runs under Robolectric for android.icu's [BreakIterator].
 */
@RunWith(RobolectricTestRunner::class)
class LatinPhraseReglobTest {

    private fun stream(text: String): LatinEngine.Companion.WordStream =
        LatinEngine.wordStream(text, BreakIterator.getWordInstance(Locale.ENGLISH))

    private fun candidates(text: String, maxWords: Int = 5): List<String> =
        LatinEngine.phraseCandidates(text, stream(text), maxWords).map { it.form }

    private fun fuse(text: String, known: Set<String>, maxWords: Int = 5): List<Pair<String, String>> {
        val s = stream(text)
        return LatinEngine.fuseSpans(text, s, LatinEngine.phraseCandidates(text, s, maxWords), known)
            .map { it.surface to it.lookupForm }
    }

    // ── Candidate generation ─────────────────────────────────────────────

    @Test fun `generates every n-gram across single spaces`() {
        assertEquals(
            listOf("a great", "a great deal", "great deal"),
            candidates("a great deal").sorted(),
        )
    }

    @Test fun `punctuation breaks the candidate chain`() {
        assertEquals(listOf("a great"), candidates("a great, deal"))
        assertTrue(candidates("well-being").isEmpty())
        assertTrue(candidates("chapter 7 begins").isEmpty())
    }

    @Test fun `maxWords caps candidate length`() {
        val forms = candidates("one two three four five six", maxWords = 3)
        assertTrue(forms.none { it.split(" ").size > 3 })
        assertTrue("one two three" in forms)
    }

    @Test fun `multiple spaces and newlines collapse in the form`() {
        assertTrue("give up" in candidates("give  up"))
        assertTrue("give up" in candidates("give\nup"))
    }

    // ── Greedy fuse ──────────────────────────────────────────────────────

    @Test fun `fuses a known phrase into one span`() {
        assertEquals(
            listOf("a great deal" to "a great deal", "of" to "of", "effort" to "effort"),
            fuse("a great deal of effort", setOf("a great deal")),
        )
    }

    @Test fun `longest known candidate wins at a position`() {
        assertEquals(
            listOf("a great deal" to "a great deal"),
            fuse("a great deal", setOf("a great", "a great deal", "great deal")),
        )
    }

    @Test fun `fused surface is the verbatim slice, lookupForm the space join`() {
        // Double space survives in the surface (indexOf-based span mapping
        // needs the exact source text) but not in the lookup key.
        assertEquals(
            listOf("give  up" to "give up"),
            fuse("give  up", setOf("give up")),
        )
    }

    @Test fun `fuse advances past consumed words`() {
        // "great deal" is known but its words are consumed by the longer
        // match starting one position earlier.
        assertEquals(
            listOf("a great deal" to "a great deal", "great deal" to "great deal"),
            fuse("a great deal great deal", setOf("a great deal", "great deal")),
        )
    }

    @Test fun `longer phrase claims words before an earlier shorter one`() {
        // The Codex-review regression: left-to-right greed would emit
        // "of a" + "great deal", hiding the gold 3-gram behind a marginal
        // function bigram. Longest-first claiming gives "a great deal" its
        // words first; "of" falls out as a single.
        assertEquals(
            listOf("of" to "of", "a great deal" to "a great deal"),
            fuse("of a great deal", setOf("of a", "a great deal", "great deal")),
        )
    }

    @Test fun `gold trigram beats a marginal bigram across an overlap`() {
        assertEquals(
            listOf("in" to "in", "a lot of" to "a lot of", "trouble" to "trouble"),
            fuse("in a lot of trouble", setOf("in a", "a lot of")),
        )
    }

    @Test fun `equal-length overlap resolves leftmost`() {
        // PINNED tie-break, not an aspiration: no data signal separates
        // equal-length overlapping phrases (marginal bigrams carry HIGHER
        // inherited freq than gold ones), so leftmost wins
        // deterministically. In practice the common gold cases have a
        // 3-gram form ("a lot of") that outranks the collision outright.
        assertEquals(
            listOf("in a" to "in a", "lot" to "lot"),
            fuse("in a lot", setOf("in a", "a lot")),
        )
    }

    @Test fun `unknown text emits singles exactly like the pre-phrase tokenizer`() {
        // Single-letter "a" and the digit run drop; words emit verbatim.
        assertEquals(
            listOf("great" to "great", "deal" to "deal"),
            fuse("a great deal 7", emptySet()),
        )
    }

    @Test fun `single-letter words fuse inside phrases but never emit alone`() {
        assertEquals(
            listOf("il y a" to "il y a", "trois" to "trois", "ans" to "ans"),
            fuse("il y a trois ans", setOf("il y a")),
        )
    }

    @Test fun `phrase blocked by punctuation stays unfused`() {
        assertEquals(
            listOf("great" to "great", "deal" to "deal"),
            fuse("great, deal", setOf("great deal")),
        )
    }
}
