package com.playtranslate.language

import org.junit.Assert.assertEquals
import org.junit.Test
import org.tartarus.snowball.ext.EnglishStemmer

/**
 * Documents the exact Porter/English stemmer behavior the Phase 3
 * [LatinEngine] depends on. Uses Lucene's [EnglishStemmer] directly —
 * pure JVM, no Android framework, no Robolectric.
 *
 * Porter stemming is aggressive and rule-based, not a morphological
 * analyzer. It correctly maps regular inflections like "running" → "run"
 * and "houses" → "house", but DOES NOT handle irregular verbs like
 * "ran" → "run" (those depend on the dictionary carrying inflected-form
 * entries or a hand-written override table, both out of scope for the
 * initial Phase 3 rollout).
 */
class LatinEngineStemmerTest {

    private fun stem(word: String): String {
        val s = EnglishStemmer()
        s.current = word.lowercase()
        s.stem()
        return s.current
    }

    @Test fun `running stems to run`() {
        assertEquals("run", stem("running"))
    }

    @Test fun `irregular ran does NOT stem to run`() {
        // Documented limitation — Porter only handles regular inflections.
        // "ran" stays "ran" and requires dictionary-side support.
        assertEquals("ran", stem("ran"))
    }

    @Test fun `houses stems to hous via Lucene EnglishStemmer rules`() {
        // Lucene's English stemmer strips plural -es; the surface form
        // "hous" is an intermediate stem, not a word. This still works
        // for lookup as long as build_en_dict.py indexes the same stem
        // for the noun "house" (we index lowercase surface + Lucene stem
        // and the lookup path tries both).
        assertEquals("hous", stem("houses"))
    }

    @Test fun `quickly stems to quick`() {
        // Lucene's EnglishStemmer is the Porter2/Snowball algorithm
        // (more sophisticated than classic Porter), which handles the
        // "-ly" suffix correctly by removing it rather than leaving
        // the "-li" artifact classic Porter produces.
        assertEquals("quick", stem("quickly"))
    }

    @Test fun `uppercase input is handled after lowercase`() {
        assertEquals("run", stem("Running"))
        assertEquals("run", stem("RUNNING"))
    }
}
