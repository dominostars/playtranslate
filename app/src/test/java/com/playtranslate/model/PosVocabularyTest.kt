package com.playtranslate.model

import com.playtranslate.model.PosVocabulary.PosCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [PosVocabulary] — the comma/tab-safe `parse` and the
 * raw/abbreviated/Wiktionary → [PosCode] canonicalization.
 *
 * The `canonical` cases double as the **coverage tripwire**: every token a
 * Japanese pack is known to store, and every description JMdict gives a
 * part-of-speech entity, is pinned to its code, so a token that would reach
 * the screen as stored fails here first.
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

    // ── expressionFrom: the carried expression-class verdict ────────────────

    private fun sense(vararg pos: String) = Sense(
        targetDefinitions = listOf("def"), partsOfSpeech = pos.toList(),
        tags = emptyList(), restrictions = emptyList(), info = emptyList(),
    )

    @Test fun expressionFrom_jmdictExpressionTokenSurvivesItsCommas() {
        // The stored pos field's comma-bearing token is what buildEntry parses
        // out; 秘密を漏らす's whole sense list is this plus a verb class.
        assertTrue(
            expressionFrom(
                listOf(sense("expressions (phrases, clauses, etc.)", "Godan verb with 'su' ending")),
            ),
        )
    }

    @Test fun expressionFrom_phraseAndProverbCount() {
        assertTrue(expressionFrom(listOf(sense("phrase"))))
        assertTrue(expressionFrom(listOf(sense("prep_phrase"))))
        assertTrue(expressionFrom(listOf(sense("proverb"))))
    }

    @Test fun expressionFrom_ordinaryWordsAreNotExpressions() {
        // 図書館's class: a transparent compound the member split must leave
        // whole unless every unit is accounted for.
        assertFalse(expressionFrom(listOf(sense("Noun"))))
        assertFalse(expressionFrom(listOf(sense("Godan verb with 'su' ending", "transitive verb"))))
    }

    @Test fun expressionFrom_anySenseCarriesIt() {
        assertTrue(expressionFrom(listOf(sense("Noun"), sense("expression"))))
    }

    @Test fun expressionFrom_noSensesIsFalse() {
        // The single-dictionary strip and the synthesized imported-only entry
        // both land here; the verdict must be carried, never re-derived from
        // an emptied list (see YomitanEnrichmentMergeTest).
        assertFalse(expressionFrom(emptyList()))
    }

    // ── canonical: the Japanese classes ─────────────────────────────────────

    /**
     * JMdict's part-of-speech entities and their descriptions, from the DTD
     * of the file fetched 2026-11-11, each with the code its label comes
     * from. Null for the five no entry carries (the packVersion 4 ja pack
     * and Jitendex of 2026-10-03 hold none of them).
     */
    private val jmdictEntities: List<Triple<String, String, PosCode?>> = listOf(
        Triple("adj-f", "noun or verb acting prenominally", PosCode.PRENOMINAL),
        Triple("adj-i", "adjective (keiyoushi)", PosCode.I_ADJECTIVE),
        Triple("adj-ix", "adjective (keiyoushi) - yoi/ii class", PosCode.I_ADJECTIVE),
        Triple("adj-kari", "'kari' adjective (archaic)", null),
        Triple("adj-ku", "'ku' adjective (archaic)", PosCode.KU_ADJECTIVE),
        Triple("adj-na", "adjectival nouns or quasi-adjectives (keiyodoshi)", PosCode.NA_ADJECTIVE),
        Triple("adj-nari", "archaic/formal form of na-adjective", PosCode.NARI_ADJECTIVE),
        Triple("adj-no", "nouns which may take the genitive case particle 'no'", PosCode.NO_ADJECTIVE),
        Triple("adj-pn", "pre-noun adjectival (rentaishi)", PosCode.PRENOMINAL),
        Triple("adj-shiku", "'shiku' adjective (archaic)", PosCode.SHIKU_ADJECTIVE),
        Triple("adj-t", "'taru' adjective", PosCode.TARU_ADJECTIVE),
        Triple("adv", "adverb (fukushi)", PosCode.ADVERB),
        Triple("adv-to", "adverb taking the 'to' particle", PosCode.TO_ADVERB),
        Triple("aux", "auxiliary", PosCode.AUXILIARY),
        Triple("aux-adj", "auxiliary adjective", PosCode.AUXILIARY),
        Triple("aux-v", "auxiliary verb", PosCode.AUXILIARY),
        Triple("conj", "conjunction", PosCode.CONJUNCTION),
        Triple("cop", "copula", PosCode.COPULA),
        Triple("ctr", "counter", PosCode.COUNTER),
        Triple("exp", "expressions (phrases, clauses, etc.)", PosCode.EXPRESSION),
        Triple("int", "interjection (kandoushi)", PosCode.INTERJECTION),
        Triple("n", "noun (common) (futsuumeishi)", PosCode.NOUN),
        Triple("n-adv", "adverbial noun (fukushitekimeishi)", null),
        Triple("n-pr", "proper noun", null),
        Triple("n-pref", "noun, used as a prefix", PosCode.PREFIX),
        Triple("n-suf", "noun, used as a suffix", PosCode.SUFFIX),
        Triple("n-t", "noun (temporal) (jisoumeishi)", null),
        Triple("num", "numeric", PosCode.NUMERAL),
        Triple("pn", "pronoun", PosCode.PRONOUN),
        Triple("pref", "prefix", PosCode.PREFIX),
        Triple("prt", "particle", PosCode.PARTICLE),
        Triple("suf", "suffix", PosCode.SUFFIX),
        Triple("unc", "unclassified", PosCode.UNCLASSIFIED),
        Triple("v-unspec", "verb unspecified", PosCode.VERB),
        Triple("v1", "Ichidan verb", PosCode.ICHIDAN_VERB),
        Triple("v1-s", "Ichidan verb - kureru special class", PosCode.ICHIDAN_VERB_SPECIAL),
        Triple("v2a-s", "Nidan verb with 'u' ending (archaic)", PosCode.NIDAN_VERB),
        Triple("v2b-k", "Nidan verb (upper class) with 'bu' ending (archaic)", PosCode.NIDAN_VERB),
        Triple("v2b-s", "Nidan verb (lower class) with 'bu' ending (archaic)", PosCode.NIDAN_VERB),
        Triple("v2d-k", "Nidan verb (upper class) with 'dzu' ending (archaic)", PosCode.NIDAN_VERB),
        Triple("v2d-s", "Nidan verb (lower class) with 'dzu' ending (archaic)", PosCode.NIDAN_VERB),
        Triple("v2g-k", "Nidan verb (upper class) with 'gu' ending (archaic)", PosCode.NIDAN_VERB),
        Triple("v2g-s", "Nidan verb (lower class) with 'gu' ending (archaic)", PosCode.NIDAN_VERB),
        Triple("v2h-k", "Nidan verb (upper class) with 'hu/fu' ending (archaic)", PosCode.NIDAN_VERB),
        Triple("v2h-s", "Nidan verb (lower class) with 'hu/fu' ending (archaic)", PosCode.NIDAN_VERB),
        Triple("v2k-k", "Nidan verb (upper class) with 'ku' ending (archaic)", PosCode.NIDAN_VERB),
        Triple("v2k-s", "Nidan verb (lower class) with 'ku' ending (archaic)", PosCode.NIDAN_VERB),
        Triple("v2m-k", "Nidan verb (upper class) with 'mu' ending (archaic)", PosCode.NIDAN_VERB),
        Triple("v2m-s", "Nidan verb (lower class) with 'mu' ending (archaic)", PosCode.NIDAN_VERB),
        Triple("v2n-s", "Nidan verb (lower class) with 'nu' ending (archaic)", PosCode.NIDAN_VERB),
        Triple("v2r-k", "Nidan verb (upper class) with 'ru' ending (archaic)", PosCode.NIDAN_VERB),
        Triple("v2r-s", "Nidan verb (lower class) with 'ru' ending (archaic)", PosCode.NIDAN_VERB),
        Triple("v2s-s", "Nidan verb (lower class) with 'su' ending (archaic)", PosCode.NIDAN_VERB),
        Triple("v2t-k", "Nidan verb (upper class) with 'tsu' ending (archaic)", PosCode.NIDAN_VERB),
        Triple("v2t-s", "Nidan verb (lower class) with 'tsu' ending (archaic)", PosCode.NIDAN_VERB),
        Triple("v2w-s", "Nidan verb (lower class) with 'u' ending and 'we' conjugation (archaic)", PosCode.NIDAN_VERB),
        Triple("v2y-k", "Nidan verb (upper class) with 'yu' ending (archaic)", PosCode.NIDAN_VERB),
        Triple("v2y-s", "Nidan verb (lower class) with 'yu' ending (archaic)", PosCode.NIDAN_VERB),
        Triple("v2z-s", "Nidan verb (lower class) with 'zu' ending (archaic)", PosCode.NIDAN_VERB),
        Triple("v4b", "Yodan verb with 'bu' ending (archaic)", PosCode.YODAN_VERB),
        Triple("v4g", "Yodan verb with 'gu' ending (archaic)", PosCode.YODAN_VERB),
        Triple("v4h", "Yodan verb with 'hu/fu' ending (archaic)", PosCode.YODAN_VERB),
        Triple("v4k", "Yodan verb with 'ku' ending (archaic)", PosCode.YODAN_VERB),
        Triple("v4m", "Yodan verb with 'mu' ending (archaic)", PosCode.YODAN_VERB),
        Triple("v4n", "Yodan verb with 'nu' ending (archaic)", PosCode.YODAN_VERB),
        Triple("v4r", "Yodan verb with 'ru' ending (archaic)", PosCode.YODAN_VERB),
        Triple("v4s", "Yodan verb with 'su' ending (archaic)", PosCode.YODAN_VERB),
        Triple("v4t", "Yodan verb with 'tsu' ending (archaic)", PosCode.YODAN_VERB),
        Triple("v5aru", "Godan verb - -aru special class", PosCode.GODAN_VERB_SPECIAL),
        Triple("v5b", "Godan verb with 'bu' ending", PosCode.GODAN_VERB),
        Triple("v5g", "Godan verb with 'gu' ending", PosCode.GODAN_VERB),
        Triple("v5k", "Godan verb with 'ku' ending", PosCode.GODAN_VERB),
        Triple("v5k-s", "Godan verb - Iku/Yuku special class", PosCode.GODAN_VERB_SPECIAL),
        Triple("v5m", "Godan verb with 'mu' ending", PosCode.GODAN_VERB),
        Triple("v5n", "Godan verb with 'nu' ending", PosCode.GODAN_VERB),
        Triple("v5r", "Godan verb with 'ru' ending", PosCode.GODAN_VERB),
        Triple("v5r-i", "Godan verb with 'ru' ending (irregular verb)", PosCode.GODAN_VERB_IRREGULAR),
        Triple("v5s", "Godan verb with 'su' ending", PosCode.GODAN_VERB),
        Triple("v5t", "Godan verb with 'tsu' ending", PosCode.GODAN_VERB),
        Triple("v5u", "Godan verb with 'u' ending", PosCode.GODAN_VERB),
        Triple("v5u-s", "Godan verb with 'u' ending (special class)", PosCode.GODAN_VERB_SPECIAL),
        Triple("v5uru", "Godan verb - Uru old class verb (old form of Eru)", null),
        Triple("vi", "intransitive verb", PosCode.INTRANSITIVE),
        Triple("vk", "Kuru verb - special class", PosCode.KURU_VERB),
        Triple("vn", "irregular nu verb", PosCode.NU_VERB),
        Triple("vr", "irregular ru verb, plain form ends with -ri", PosCode.RI_VERB),
        Triple("vs", "noun or participle which takes the aux. verb suru", PosCode.SURU_VERB),
        Triple("vs-c", "su verb - precursor to the modern suru", PosCode.SU_VERB),
        Triple("vs-i", "suru verb - included", PosCode.SURU_VERB),
        Triple("vs-s", "suru verb - special class", PosCode.SURU_VERB),
        Triple("vt", "transitive verb", PosCode.TRANSITIVE),
        Triple("vz", "Ichidan verb - zuru verb (alternative form of -jiru verbs)", PosCode.ZURU_VERB),
    )

    @Test fun canonical_everyJmdictDescriptionHasItsCode() {
        // What a target pack stores for a Japanese word is mostly these: its
        // builder's table shortens 19 of them and passes the rest through.
        val wrong = jmdictEntities
            .filter { (_, description, code) -> PosVocabulary.canonical(description) != code }
            .map { (entity, description, code) -> "$entity \"$description\": want $code, got ${PosVocabulary.canonical(description)}" }
        assertEquals("JMdict descriptions off their code:\n" + wrong.joinToString("\n"), emptyList<String>(), wrong)
    }

    @Test fun canonical_theFiveEntitiesNoEntryCarriesAreTheOnlyOnesWithoutACode() {
        assertEquals(
            listOf("adj-kari", "n-adv", "n-pr", "n-t", "v5uru"),
            jmdictEntities.filter { it.third == null }.map { it.first },
        )
    }

    /**
     * Every distinct token of `sense.pos` in the packVersion 4 ja pack
     * (2026-07-13, 252,198 senses), read out of its dict.sqlite on
     * 2026-11-11: the short forms `POS_ABBREV` produced beside the
     * descriptions it passed through.
     */
    private val jaPackTokens = listOf(
        "'ku' adjective (archaic)", "'shiku' adjective (archaic)", "'taru' adjective", "Adverb",
        "Aux. adjective", "Aux. verb", "Auxiliary", "Conjunction", "Counter", "Godan verb (iku)",
        "Godan verb - -aru special class", "Godan verb with 'bu' ending", "Godan verb with 'gu' ending",
        "Godan verb with 'ku' ending", "Godan verb with 'mu' ending", "Godan verb with 'nu' ending",
        "Godan verb with 'ru' ending", "Godan verb with 'ru' ending (irregular verb)",
        "Godan verb with 'su' ending", "Godan verb with 'tsu' ending", "Godan verb with 'u' ending",
        "Godan verb with 'u' ending (special class)", "I-adjective", "I-adjective (ii)", "Ichidan verb",
        "Ichidan verb (kureru)", "Ichidan verb (zuru)", "Interjection", "Kuru verb", "Na-adjective",
        "Nidan verb (lower class) with 'dzu' ending (archaic)",
        "Nidan verb (lower class) with 'gu' ending (archaic)",
        "Nidan verb (lower class) with 'hu/fu' ending (archaic)",
        "Nidan verb (lower class) with 'ku' ending (archaic)",
        "Nidan verb (lower class) with 'mu' ending (archaic)",
        "Nidan verb (lower class) with 'nu' ending (archaic)",
        "Nidan verb (lower class) with 'ru' ending (archaic)",
        "Nidan verb (lower class) with 'su' ending (archaic)",
        "Nidan verb (lower class) with 'tsu' ending (archaic)",
        "Nidan verb (lower class) with 'u' ending and 'we' conjugation (archaic)",
        "Nidan verb (lower class) with 'yu' ending (archaic)",
        "Nidan verb (lower class) with 'zu' ending (archaic)",
        "Nidan verb (upper class) with 'bu' ending (archaic)",
        "Nidan verb (upper class) with 'gu' ending (archaic)",
        "Nidan verb (upper class) with 'hu/fu' ending (archaic)",
        "Nidan verb (upper class) with 'ku' ending (archaic)",
        "Nidan verb (upper class) with 'ru' ending (archaic)",
        "Nidan verb (upper class) with 'tsu' ending (archaic)",
        "Nidan verb (upper class) with 'yu' ending (archaic)", "Nidan verb with 'u' ending (archaic)",
        "Noun", "Noun prefix", "Noun suffix", "Numeric", "Particle", "Pre-noun adj.", "Prefix",
        "Prenominal", "Pronoun", "Suffix", "Suru verb", "Suru verb (special)", "Unclassified",
        "Yodan verb with 'bu' ending (archaic)", "Yodan verb with 'gu' ending (archaic)",
        "Yodan verb with 'hu/fu' ending (archaic)", "Yodan verb with 'ku' ending (archaic)",
        "Yodan verb with 'mu' ending (archaic)", "Yodan verb with 'ru' ending (archaic)",
        "Yodan verb with 'su' ending (archaic)", "Yodan verb with 'tsu' ending (archaic)",
        "adverb taking the 'to' particle", "archaic/formal form of na-adjective", "copula",
        "expressions (phrases, clauses, etc.)", "intransitive verb", "irregular nu verb",
        "irregular ru verb, plain form ends with -ri", "noun or participle which takes the aux. verb suru",
        "nouns which may take the genitive case particle 'no'", "su verb - precursor to the modern suru",
        "transitive verb", "verb unspecified",
    )

    @Test fun canonical_everyTokenOfTheJaPackHasACode() {
        assertEquals(83, jaPackTokens.toSet().size)
        val stored = jaPackTokens.filter { PosVocabulary.canonical(it) == null }
        assertEquals("These tokens would be shown as the pack stores them: $stored", emptyList<String>(), stored)
    }

    @Test fun parse_everyKnownFormComesBackWholeFromAStoredField() {
        // A pack joins a sense's tokens with a comma, and four of JMdict's
        // descriptions hold one of their own. canonical() is handed a whole
        // token, so only this path shows a form the split would break.
        val forms = (
            jmdictEntities.map { it.second } + jaPackTokens +
                // An older JMdict wording and two POS_ABBREV short forms.
                listOf("expression (phrase, clause, etc.)", "Godan verb (u, irr.)", "Godan verb (ru, irr.)")
            ).distinct()
        assertEquals(
            listOf(
                "expressions (phrases, clauses, etc.)", "noun, used as a prefix", "noun, used as a suffix",
                "irregular ru verb, plain form ends with -ri",
                "expression (phrase, clause, etc.)", "Godan verb (u, irr.)", "Godan verb (ru, irr.)",
            ),
            forms.filter { ',' in it },
        )
        val broken = forms.filter { PosVocabulary.parse(it) != listOf(it) }
        assertEquals("Split at their own comma: $broken", emptyList<String>(), broken)
        // Among neighbours, as a field holds them.
        assertEquals(forms, PosVocabulary.parse(forms.joinToString(",")))
    }

    @Test fun canonical_aShortFormAndItsDescriptionShareACode() {
        // The builders' POS_ABBREV rows whose short form a pack can hold
        // beside the description (a newer JMdict wording misses the row's
        // key): one label either way.
        listOf(
            "Suru verb" to "suru verb - included",
            "Suru verb (special)" to "suru verb - special class",
            "Suru precursor" to "su verb - precursor to the modern suru",
            "Kuru verb" to "Kuru verb - special class",
            "Ichidan verb (zuru)" to "Ichidan verb - zuru verb (alternative form of -jiru verbs)",
            "Ichidan verb (kureru)" to "Ichidan verb - kureru special class",
            "Godan verb (su)" to "Godan verb with 'su' ending",
            "Godan verb (iku)" to "Godan verb - Iku/Yuku special class",
            "Godan verb (aru)" to "Godan verb - -aru special class",
            "Godan verb (u, irr.)" to "Godan verb with 'u' ending (special class)",
            "Godan verb (ru, irr.)" to "Godan verb with 'ru' ending (irregular verb)",
            "I-adjective" to "adjective (keiyoushi)",
            "I-adjective (ii)" to "adjective (keiyoushi) - yoi/ii class",
            "Na-adjective" to "adjectival nouns or quasi-adjectives (keiyodoshi)",
            "Pre-noun adj." to "pre-noun adjectival (rentaishi)",
            "Prenominal" to "noun or verb acting prenominally",
            "Adverb (to)" to "adverb taking the 'to' particle",
            "Noun suffix" to "noun, used as a suffix",
            "Noun prefix" to "noun, used as a prefix",
        ).forEach { (short, description) ->
            val code = PosVocabulary.canonical(description)
            assertTrue("no code for: $description", code != null)
            assertEquals("short form off its description: $short", code, PosVocabulary.canonical(short))
        }
    }

    @Test fun canonical_theOlderJmdictWordingsStillResolve() {
        // POS_ABBREV's keys are JMdict's wording of an earlier year; a pack
        // built then holds these.
        assertEquals(PosCode.GODAN_VERB, PosVocabulary.canonical("Godan verb with `su' ending"))
        assertEquals(PosCode.TO_ADVERB, PosVocabulary.canonical("adverb taking the `to' particle"))
        assertEquals(PosCode.GODAN_VERB_SPECIAL, PosVocabulary.canonical("Godan verb - aru special class"))
        assertEquals(PosCode.SU_VERB, PosVocabulary.canonical("suru verb - precursor to the above"))
        assertEquals(PosCode.EXPRESSION, PosVocabulary.canonical("expression (phrase, clause, etc.)"))
    }

    @Test fun canonical_aSpecialOrIrregularGodanVerbIsNotThePlainClass() {
        assertEquals(PosCode.GODAN_VERB, PosVocabulary.canonical("Godan verb with 'u' ending"))
        assertEquals(PosCode.GODAN_VERB_SPECIAL, PosVocabulary.canonical("Godan verb with 'u' ending (special class)"))
        assertEquals(PosCode.GODAN_VERB, PosVocabulary.canonical("Godan verb with 'ru' ending"))
        assertEquals(PosCode.GODAN_VERB_IRREGULAR, PosVocabulary.canonical("Godan verb with 'ru' ending (irregular verb)"))
        assertEquals(PosCode.ICHIDAN_VERB, PosVocabulary.canonical("Ichidan verb"))
        assertEquals(PosCode.ZURU_VERB, PosVocabulary.canonical("Ichidan verb (zuru)"))
    }

    @Test fun canonical_aTokenOutsideTheTableHasNoCode() {
        // Rendered as stored: a Wiktionary category with no label, an
        // imported dictionary's header, a builder short form no pack holds.
        listOf("name", "character", "Jitendex · n, v5r", "n", "vs", "Adv. noun", "Temporal noun")
            .forEach { assertNull("Expected no code for: $it", PosVocabulary.canonical(it)) }
    }
}
