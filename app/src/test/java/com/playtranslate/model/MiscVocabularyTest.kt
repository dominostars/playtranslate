package com.playtranslate.model

import com.playtranslate.model.MiscVocabulary.MiscCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parity + behaviour tripwire for the misc vocabulary. The JSON
 * (`assets/misc_vocabulary.json`) is the single source of truth, read by both
 * the Python build filter and the app; this test proves the Kotlin [MiscCode]
 * enum and the JSON can't drift, and that the filter logic shared by
 * `renderMisc` / `englishMisc` keeps register + passthrough and drops noise.
 *
 * `renderMisc`'s LOCALIZATION (getString) is compile-checked by the exhaustive
 * `when` in MiscLabels.stringRes + the `R.string.misc_*` references; here we
 * exercise the Context-free filter via [MiscVocabulary.englishMisc].
 */
class MiscVocabularyTest {

    // MiscVocabulary lazily loads /misc_vocabulary.json from the classpath
    // (src/main/resources) on first access — present on the unit-test classpath,
    // so no explicit setup is needed and these tests exercise the real load path.

    @Test
    fun `every MiscCode round-trips through the JSON (no drift)`() {
        // englishLabel falls back to code.name when a code is absent from the
        // JSON; a present code returns its label, which must canonicalize back.
        for (code in MiscCode.values()) {
            val label = MiscVocabulary.englishLabel(code)
            assertEquals(
                "MiscCode $code is missing from misc_vocabulary.json (or its label isn't an alias)",
                code,
                MiscVocabulary.canonical(label),
            )
        }
    }

    @Test
    fun `canonical maps JMdict verbose, JMdict abbreviated, and kaikki forms`() {
        assertEquals(MiscCode.HONORIFIC, MiscVocabulary.canonical("Honorific"))
        assertEquals(MiscCode.HONORIFIC, MiscVocabulary.canonical("honorific"))
        assertEquals(
            MiscCode.HONORIFIC,
            MiscVocabulary.canonical("honorific or respectful (sonkeigo) language"),
        )
        assertEquals(MiscCode.KANA_ONLY, MiscVocabulary.canonical("Kana only"))
        assertEquals(MiscCode.VULGAR, MiscVocabulary.canonical("vulgar"))
        assertEquals(MiscCode.DATED, MiscVocabulary.canonical("outdated"))
        assertEquals(MiscCode.DIALECTAL, MiscVocabulary.canonical("regional"))
        // Grammatical noise and POS-owned tags are not register codes.
        assertNull(MiscVocabulary.canonical("noun"))
        assertNull(MiscVocabulary.canonical("feminine"))
        assertNull(MiscVocabulary.canonical("proverb"))
        assertNull(MiscVocabulary.canonical("abbreviation"))
    }

    @Test
    fun `isPassthrough covers domain and region but not register`() {
        assertTrue(MiscVocabulary.isPassthrough("computing"))
        assertTrue(MiscVocabulary.isPassthrough("Kansai-ben"))
        assertTrue(MiscVocabulary.isPassthrough("Chile"))
        assertFalse(MiscVocabulary.isPassthrough("honorific"))
        assertFalse(MiscVocabulary.isPassthrough("noun"))
    }

    @Test
    fun `englishMisc keeps register + passthrough, drops grammatical and category noise`() {
        // The exact unfiltered string we found shipping in target-es.
        assertEquals(
            listOf("Derogatory"),
            MiscVocabulary.englishMisc(listOf("derogatory", "feminine", "masculine", "noun")),
        )
        // Domain passes through (raw English); a Spanish category is dropped.
        assertEquals(
            listOf("Colloquial", "computing"),
            MiscVocabulary.englishMisc(listOf("colloquial", "computing", "Cultura")),
        )
        // Region passes through; POS-owned tags drop (POS renders them).
        assertEquals(listOf("Chile"), MiscVocabulary.englishMisc(listOf("Chile")))
        assertTrue(MiscVocabulary.englishMisc(listOf("proverb", "abbreviation")).isEmpty())
        // A freeform s_inf sentence is dropped.
        assertTrue(MiscVocabulary.englishMisc(listOf("めばちこ is Osaka dialect")).isEmpty())
    }

    /** F1 regression: build_target_pack feeds RAW JMdict misc (not the
     *  MISC_ABBREV-abbreviated form) into the filter, so the raw descriptive
     *  forms must canonicalize or kana-only/kanji-only rows silently vanish. */
    @Test
    fun `raw JMdict kana-only and kanji-only forms canonicalize`() {
        assertEquals(MiscCode.KANA_ONLY, MiscVocabulary.canonical("usually written using kana alone"))
        assertEquals(MiscCode.KANA_ONLY, MiscVocabulary.canonical("word usually written using kana alone"))
        assertEquals(MiscCode.KANJI_ONLY, MiscVocabulary.canonical("usually written using kanji alone"))
        assertEquals(
            listOf("Kana only"),
            MiscVocabulary.englishMisc(listOf("word usually written using kana alone")),
        )
    }

    /** F2 regression: the target-pack misc format is tab-delimited, so domain
     *  labels that contain commas pass through whole instead of being split
     *  apart by the old comma split. */
    @Test
    fun `comma-containing domain labels pass through intact`() {
        for (label in listOf("food, cooking", "art, aesthetics", "electricity, elec. eng.")) {
            assertEquals(listOf(label), MiscVocabulary.englishMisc(listOf(label)))
            // A surviving token must never contain the tab delimiter.
            assertFalse(MiscVocabulary.englishMisc(listOf(label)).any { it.contains('\t') })
        }
    }

    /** Backward-compat: a legacy (pre-tab) target pack joined misc with ',',
     *  so the whole row arrives as one unrecognized token. It must be re-split
     *  and cleaned, while a NEW pack's comma-containing domain stays whole. */
    @Test
    fun `legacy comma-joined target misc is re-split and cleaned`() {
        // The exact noisy comma blob a pre-tab target pack stores.
        assertEquals(
            listOf("Derogatory"),
            MiscVocabulary.englishMisc(listOf("derogatory,feminine,masculine,noun")),
        )
        // Region + register survive (order preserved); grammar is dropped.
        assertEquals(
            listOf("Chile", "Colloquial"),
            MiscVocabulary.englishMisc(listOf("Chile,colloquial,noun")),
        )
        // A new pack's single comma-domain token is recognized → never re-split.
        assertEquals(
            listOf("food, cooking"),
            MiscVocabulary.englishMisc(listOf("food, cooking")),
        )
    }
}
