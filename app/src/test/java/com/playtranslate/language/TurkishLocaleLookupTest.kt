package com.playtranslate.language

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.tartarus.snowball.ext.TurkishStemmer
import java.util.Locale

/**
 * Pins the Turkish case-mapping contract that `LatinEngine` and
 * `WiktionaryDictionaryManager` depend on when they lowercase OCR input
 * before the dictionary query.
 *
 * Turkish is the only supported Latin-script language whose case rules
 * diverge from Unicode default: `I → ı`, `İ → i`. Using `.lowercase()`
 * (no locale) on uppercase game text turns `IŞIK` into `işik` instead
 * of `ışık`, which misses every Turkish headword because the pack is
 * built from Wiktionary's properly-cased Turkish lowercase forms.
 *
 * The runtime guard is `SourceLangId.TR.locale` flowing into the two
 * call sites — this test documents *why* that locale matters.
 */
class TurkishLocaleLookupTest {

    @Test fun `TR locale maps dotless I correctly`() {
        val locale = SourceLangId.TR.locale
        assertEquals("ışık", "IŞIK".lowercase(locale))
        assertEquals("işte", "İŞTE".lowercase(locale))
    }

    @Test fun `default locale mangles Turkish dotless I`() {
        // Guardrail: if this test ever starts passing with `.lowercase()`
        // (default locale), the JVM changed under us. Until then, the
        // call sites in LatinEngine/WiktionaryDictionaryManager must pass the
        // TR locale explicitly.
        assertNotEquals("ışık", "IŞIK".lowercase())
    }

    @Test fun `non-Turkish locales use default Unicode case mapping`() {
        // Guarantee the SourceLangId.locale helper only changes behavior
        // where it has to — English/French/etc. should still get the
        // default Unicode rules.
        assertEquals("hello", "HELLO".lowercase(SourceLangId.EN.locale))
        assertEquals("café", "CAFÉ".lowercase(SourceLangId.FR.locale))
    }

    @Test fun `Turkish stemmer with locale-aware lowercase reaches lemma stem`() {
        // "IŞIKLAR" = "ışıklar" = plural of "ışık". The Turkish Snowball
        // stemmer strips `-lar` to produce `ışık`. This only works when
        // the input is Turkish-lowercased; `işiklar` (default lowercase)
        // stems to something else and misses the lemma index entry.
        val locale = SourceLangId.TR.locale
        val stemmer = TurkishStemmer()

        stemmer.current = "IŞIKLAR".lowercase(locale)
        stemmer.stem()
        val withTurkishLocale = stemmer.current

        stemmer.current = "IŞIKLAR".lowercase(Locale.ROOT)
        stemmer.stem()
        val withRootLocale = stemmer.current

        assertEquals("ışık", withTurkishLocale)
        assertNotEquals(withTurkishLocale, withRootLocale)
    }

    @Test fun `TR locale has language tag tr`() {
        assertEquals("tr", SourceLangId.TR.locale.language)
    }

    // ── Tests below run under a simulated Turkish-locale device ────────
    // Default-locale `.lowercase()` / `.uppercase()` would mangle ASCII
    // language codes (`"IT" → "ıt"`). These tests pin the code paths
    // that normalize those codes against exactly that scenario.

    private var savedDefault: Locale? = null

    @Before fun saveDefault() {
        savedDefault = Locale.getDefault()
    }

    @After fun restoreDefault() {
        savedDefault?.let { Locale.setDefault(it) }
    }

    @Test fun `fromCode resolves codes even on a Turkish-locale device`() {
        Locale.setDefault(Locale("tr"))
        // "IT" would default-lowercase to "ıt" on a Turkish device, missing
        // the enum match. ROOT-lowercase keeps it as "it".
        assertEquals(SourceLangId.IT, SourceLangId.fromCode("IT"))
        assertEquals(SourceLangId.ZH_HANT, SourceLangId.fromCode("ZH-HANT"))
        assertEquals(SourceLangId.ID, SourceLangId.fromCode("ID"))
    }

    @Test fun `ROOT-locale casing keeps ASCII identifiers stable on Turkish devices`() {
        Locale.setDefault(Locale("tr"))
        // This is the guarantee every ASCII-identifier call site relies on
        // (DeepL codes, enum names, pinyin syllables). Without ROOT, the
        // next line would fail on a Turkish-locale JVM.
        assertEquals("ID", "id".uppercase(Locale.ROOT))
        assertEquals("IT", "it".uppercase(Locale.ROOT))
        assertEquals("id", "ID".lowercase(Locale.ROOT))
        assertEquals("i", "I".lowercase(Locale.ROOT))
    }
}
