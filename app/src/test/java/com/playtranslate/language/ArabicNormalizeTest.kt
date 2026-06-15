package com.playtranslate.language

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Golden fixture for [ArabicNormalize] — the SAME (input → output) pairs must be
 * asserted on the Python build side (`scripts/build_latin_dict.py`'s smoke test)
 * so the dictionary's normalized headwords match what lookup normalizes queries
 * to. Inputs use explicit \u escapes for the chars under test (no hand-transcribed
 * composed Arabic, which is error-prone).
 */
class ArabicNormalizeTest {

    @Test
    fun stripsDiacriticsAndTatweel() {
        // كَت (kaf + fatha + ta) → كت ; the fatha U+064E is dropped.
        assertEquals("كت", ArabicNormalize.normalize("كَت"))
        // كـت with tatweel U+0640 → كت
        assertEquals("كت", ArabicNormalize.normalize("كـت"))
        // superscript alef U+0670 dropped
        assertEquals("ه", ArabicNormalize.normalize("هٰ"))
    }

    @Test
    fun foldsAlefYaTaaVariants() {
        for (alef in listOf("آ", "أ", "إ", "ٱ")) {
            assertEquals("alef variant $alef", "ا", ArabicNormalize.normalize(alef))
        }
        assertEquals("ي", ArabicNormalize.normalize("ى")) // alef maqsura ى → ي
        assertEquals("ه", ArabicNormalize.normalize("ة")) // taa marbuta ة → ه
    }

    @Test
    fun nfkcFoldsPresentationLigature() {
        // ﷲ U+FDF2 (ALLAH ligature) → ا ل ل ه
        assertEquals("الله", ArabicNormalize.normalize("ﷲ"))
    }

    @Test
    fun leavesPlainLettersAndDigits() {
        assertEquals("كتاب", ArabicNormalize.normalize("كتاب")) // كتاب
        assertEquals("100", ArabicNormalize.normalize("100"))
        assertEquals("abc", ArabicNormalize.normalize("abc"))
    }
}
