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
}
