package com.playtranslate.model

import com.playtranslate.model.PosVocabulary.PosCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for [PosVocabulary] — the comma/tab-safe `parse` and the
 * raw/abbreviated/Wiktionary → [PosCode] canonicalization.
 *
 * The `canonical` cases double as the **coverage tripwire**: if a build-script
 * or JMdict change renames a universal POS form, the mapping assertions fail
 * loudly instead of silently falling back to English.
 */
class PosVocabularyTest {

    // ── parse: comma-safe + tab-aware ────────────────────────────────────────

    @Test fun parse_recoversCommaBearingTokenWhole() {
        assertEquals(
            listOf("expressions (phrases, clauses, etc.)"),
            PosVocabulary.parse("expressions (phrases, clauses, etc.)"),
        )
    }

    @Test fun parse_commaTokenAmongOthers() {
        assertEquals(
            listOf("Conjunction", "expressions (phrases, clauses, etc.)", "Adverb"),
            PosVocabulary.parse("Conjunction,expressions (phrases, clauses, etc.),Adverb"),
        )
    }

    @Test fun parse_secondCommaBearingToken() {
        assertEquals(
            listOf("Aux. verb", "irregular ru verb, plain form ends with -ri"),
            PosVocabulary.parse("Aux. verb,irregular ru verb, plain form ends with -ri"),
        )
    }

    @Test fun parse_plainCommaList() {
        assertEquals(listOf("Noun", "Suru verb"), PosVocabulary.parse("Noun,Suru verb"))
    }

    @Test fun parse_blankIsEmpty() {
        assertEquals(emptyList<String>(), PosVocabulary.parse(""))
        assertEquals(emptyList<String>(), PosVocabulary.parse("   "))
    }

    // ── canonical: both regimes + Wiktionary + variants → one code ───────────

    @Test fun canonical_nounRawAndAbbrevAndWiktionary() {
        assertEquals(PosCode.NOUN, PosVocabulary.canonical("Noun"))
        assertEquals(PosCode.NOUN, PosVocabulary.canonical("noun"))
        assertEquals(PosCode.NOUN, PosVocabulary.canonical("noun (common) (futsuumeishi)"))
    }

    @Test fun canonical_expressionRawAndAbbrev() {
        // Raw verbose form (current packs) and abbreviated form (future) → same.
        assertEquals(PosCode.EXPRESSION, PosVocabulary.canonical("expressions (phrases, clauses, etc.)"))
        assertEquals(PosCode.EXPRESSION, PosVocabulary.canonical("Expression"))
    }

    @Test fun canonical_adverbVariants() {
        assertEquals(PosCode.ADVERB, PosVocabulary.canonical("Adverb"))
        assertEquals(PosCode.ADVERB, PosVocabulary.canonical("ADVERB"))
        assertEquals(PosCode.ADVERB, PosVocabulary.canonical("adverb (fukushi)"))
        assertEquals(PosCode.ADVERB, PosVocabulary.canonical("adv"))
    }

    @Test fun canonical_caseAndApostropheInsensitive() {
        assertEquals(PosCode.CONJUNCTION, PosVocabulary.canonical("  conjunction "))
        assertEquals(PosCode.CONJUNCTION, PosVocabulary.canonical("conj"))
        assertEquals(PosCode.INTERJECTION, PosVocabulary.canonical("intj"))
    }

    @Test fun canonical_universalSetIsCovered() {
        // Tripwire: every universal form we commit to must resolve.
        val universal = listOf(
            "noun", "Noun", "pronoun", "pron", "verb", "adj", "adverb", "adv",
            "particle", "conjunction", "conj", "interjection", "intj", "prep",
            "postp", "postposition", "det", "determiner", "article",
            "prefix", "suffix", "counter", "numeric", "num", "expression",
            "phrase", "prep_phrase", "proverb", "abbrev", "contraction",
            "auxiliary", "aux. verb",
        )
        val unmapped = universal.filter { PosVocabulary.canonical(it) == null }
        assertEquals("These universal forms no longer map: $unmapped", emptyList<String>(), unmapped)
    }

    @Test fun canonical_japaneseClassesAndArchaicFallBackToEnglish() {
        // Intentionally NOT localized — must stay null so we render the romaji.
        listOf(
            "Ichidan verb",
            "Godan verb with 'u' ending",
            "Godan verb (u)",
            "Suru verb",
            "transitive verb",
            "intransitive verb",
            "I-adjective",
            "Na-adjective",
            "Nidan verb (lower class) with 'dzu' ending (archaic)",
        ).forEach { assertNull("Expected English fallback for: $it", PosVocabulary.canonical(it)) }
    }
}
