package com.playtranslate.language

import android.icu.text.BreakIterator
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins [LatinEngine.phraseWindow], the word-window collector behind
 * tap-time multi-word expression matching ([LatinEngine.longestPhraseAt]).
 * The load-bearing semantics:
 *
 *  - the window anchors at the word CONTAINING the offset (taps pass a
 *    word's start; drags can land mid-word);
 *  - it chains only across whitespace-only separators — punctuation,
 *    hyphens, or digit runs break the chain, because pack phrases are
 *    space-joined ("great, deal" must never gate "great deal");
 *  - single-letter words are KEPT (unlike tokenize's isLookupWorthy filter),
 *    and up to [LatinEngine.MAX_SINGLE_LETTER_PREFIX] of them are absorbed
 *    as LEFT context: they have no tap target of their own, so the phrases
 *    they head must be reachable from the next word ("a great deal" from
 *    "great").
 *
 * Runs under Robolectric for android.icu's [BreakIterator].
 */
@RunWith(RobolectricTestRunner::class)
class LatinPhraseWindowTest {

    /** (word surfaces, anchor index), or null. */
    private fun window(text: String, offset: Int, maxWords: Int = 5): Pair<List<String>, Int>? {
        val iterator = BreakIterator.getWordInstance(Locale.ENGLISH)
        val w = LatinEngine.phraseWindow(text, offset, iterator, maxWords) ?: return null
        return w.words.map { text.substring(it) } to w.anchorIndex
    }

    @Test fun `collects following words across single spaces`() {
        assertEquals(
            listOf("a", "great", "deal", "of", "effort") to 0,
            window("a great deal of effort today", 0),
        )
    }

    @Test fun `keeps single-letter words tokenize would drop`() {
        // "a" heads the flagship phrase; the window must not filter it.
        assertEquals(listOf("a", "great", "deal") to 0, window("a great deal", 0))
    }

    @Test fun `absorbs a single-letter word as left context`() {
        // "a" is untappable (tokenize drops it), so tapping "great" must
        // still see the full "a great deal" window — anchored at "great".
        assertEquals(listOf("a", "great", "deal") to 1, window("a great deal", 2))
    }

    @Test fun `left context stops at a multi-letter word`() {
        // "of" heads its own tap; it is never absorbed.
        assertEquals(listOf("a", "great", "deal") to 1, window("of a great deal", 5))
    }

    @Test fun `left context is capped at two single-letter words`() {
        assertEquals(listOf("y", "z", "great", "deal") to 2, window("x y z great deal", 6))
    }

    @Test fun `left context respects chain breaks`() {
        // The comma detaches "great" — only the adjacent "a" is absorbed.
        assertEquals(listOf("a", "deal") to 1, window("great, a deal", 10))
    }

    @Test fun `anchors mid-word for drag offsets`() {
        assertEquals(listOf("a", "great", "deal") to 1, window("a great deal", 4))
    }

    @Test fun `comma breaks the forward chain`() {
        assertEquals(listOf("great") to 0, window("great, deal", 0))
    }

    @Test fun `hyphen breaks the chain`() {
        // "well-being" segments to well / - / being; the "-" gap must not
        // fabricate the space-joined candidate "well being".
        assertEquals(listOf("well") to 0, window("well-being matters", 0))
    }

    @Test fun `digit run breaks the chain`() {
        assertEquals(listOf("chapter") to 0, window("chapter 7 begins", 0))
    }

    @Test fun `multiple spaces and newlines still chain`() {
        // OCR artifacts: any all-whitespace separator chains; the caller
        // joins with a single space to match pack headwords.
        assertEquals(listOf("give", "up") to 0, window("give  up", 0))
        assertEquals(listOf("give", "up") to 0, window("give\nup", 0))
    }

    @Test fun `offset on whitespace or punctuation yields no window`() {
        assertNull(window("a great deal", 1))
        assertNull(window("(great deal", 0))
    }

    @Test fun `offset outside the text yields no window`() {
        assertNull(window("great deal", -1))
        assertNull(window("great deal", 10))
        assertNull(window("", 0))
    }

    @Test fun `maxWords caps the forward window`() {
        assertEquals(
            listOf("one", "two", "three") to 0,
            window("one two three four five", 0, maxWords = 3),
        )
    }

    @Test fun `last word yields a singleton window`() {
        // A singleton can't form a phrase — longestPhraseAt returns null.
        assertEquals(listOf("deal") to 0, window("a great deal", 8))
    }

    @Test fun `apostrophe words stay whole`() {
        // MidLetter apostrophes keep French elisions as one word, so
        // "aller de l'avant" windows as three words like the pack stores it.
        assertEquals(listOf("aller", "de", "l'avant") to 0, window("aller de l'avant", 0))
    }
}
