package com.playtranslate.language

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [LatinEngine.importedTermFallbacks] — the Yomitan imported-term
 * lookup keys a Latin/Cyrillic/Arabic engine tries after the direct, original-
 * case surface. Pins the fix for the gap where a capitalized surface (sentence-
 * initial word, etc.) skipped the lowercase lemma that the Wiktionary pack
 * queries first: the locale-lowercased surface must be a fallback. Pure JVM.
 */
class LatinImportedTermFallbacksTest {

    private fun keys(w: String, lower: String, stem: String, folded: String? = null) =
        LatinEngine.importedTermFallbacks(w, lower, stem, folded)

    @Test
    fun `capitalized inflected word tries the lowercased surface then the stem`() {
        // "Running" sentence-initial: the pack queries "running"; the direct
        // Yomitan key is "Running", so "running" must be the first fallback —
        // else a lowercase imported lemma is silently missed.
        assertEquals(listOf("running", "run"), keys(w = "Running", lower = "running", stem = "run"))
    }

    @Test
    fun `lowercase inflected word tries only the stem`() {
        // lower == w (filtered); stem distinct.
        assertEquals(listOf("cat"), keys(w = "cats", lower = "cats", stem = "cat"))
    }

    @Test
    fun `capitalized uninflected word tries the lowercase form exactly once`() {
        // "The": lower "the"; stem "the" == lower → no duplicate query.
        assertEquals(listOf("the"), keys(w = "The", lower = "the", stem = "the"))
    }

    @Test
    fun `capitalized noun whose stem equals its lowercase yields one fallback`() {
        // German "Hund": direct key "Hund" covers capitalized-headword dicts;
        // "hund" covers lowercase dicts; stem "hund" == lower (deduped).
        assertEquals(listOf("hund"), keys(w = "Hund", lower = "hund", stem = "hund"))
    }

    @Test
    fun `all-equal forms yield no fallbacks`() {
        assertEquals(emptyList<String>(), keys(w = "run", lower = "run", stem = "run"))
    }

    @Test
    fun `folded variant is appended after the surface and stem`() {
        // Arabic-style: caseless (lower == w, filtered), distinct stem + fold.
        assertEquals(listOf("stm", "fld"), keys(w = "wrd", lower = "wrd", stem = "stm", folded = "fld"))
    }
}
