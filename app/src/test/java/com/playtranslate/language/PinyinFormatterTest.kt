package com.playtranslate.language

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [PinyinFormatter.readingsEqual] — the bridge that lets a tone-marked ZH
 * reading hint (from the source dict) match the numbered pinyin stored in the
 * CFDICT/HanDeDict target packs, while staying an exact comparison for kana.
 */
class PinyinFormatterTest {

    @Test fun `numbered pinyin matches the tone-marked form`() {
        assertTrue(PinyinFormatter.readingsEqual("dong1 xi1", "dōng xī"))   // east-west
        assertTrue(PinyinFormatter.readingsEqual("dong1 xi5", "dōng xi"))   // thing (neutral tone)
        assertTrue(PinyinFormatter.readingsEqual("Bei3 jing1", "Běi jīng")) // capitalization preserved
    }

    @Test fun `distinct readings of the same surface do not match`() {
        // dōng xī (east-west) vs dōng xi (thing) — the case the heteronym fix exists for.
        assertFalse(PinyinFormatter.readingsEqual("dong1 xi1", "dōng xi"))
    }

    @Test fun `tone-marked hint compares idempotently against numbered`() {
        assertTrue(PinyinFormatter.readingsEqual("dōng xī", "dong1 xi1"))
    }

    @Test fun `kana readings compare exactly (normalization is a no-op)`() {
        assertTrue(PinyinFormatter.readingsEqual("たべる", "たべる"))
        assertFalse(PinyinFormatter.readingsEqual("たべる", "のむ"))
    }

    @Test fun `empty stored reading never matches a real hint`() {
        assertFalse(PinyinFormatter.readingsEqual("", "dōng xī"))
    }

    @Test fun `numberedToToneMarks is idempotent on tone-marked input`() {
        assertEquals("dōng xī", PinyinFormatter.numberedToToneMarks("dōng xī"))
    }

    @Test fun `numberedToToneMarks folds CC-CEDICT u colon to ü`() {
        assertEquals("nǚ", PinyinFormatter.numberedToToneMarks("nu:3"))        // 女
        assertEquals("lǜ", PinyinFormatter.numberedToToneMarks("lu:4"))        // 绿/律
        assertEquals("lǚ", PinyinFormatter.numberedToToneMarks("lu:3"))        // 旅
        assertEquals("nǚ rén", PinyinFormatter.numberedToToneMarks("nu:3 ren2")) // 女人
        assertEquals("Lǚ", PinyinFormatter.numberedToToneMarks("Lu:3"))        // 吕 (surname, capitalized)
        assertEquals("lü", PinyinFormatter.numberedToToneMarks("lu:5"))        // neutral tone keeps ü
    }

    @Test fun `numberedToToneMarks still folds legacy v to ü`() {
        assertEquals("lǜ", PinyinFormatter.numberedToToneMarks("lv4"))
    }

    @Test fun `numberedToToneMarks leaves unnumbered strings exact (no ü folding)`() {
        // ü folding must only happen on numbered pinyin syllables; an
        // unnumbered/non-pinyin token with a 'v' or 'u:' must pass through
        // verbatim (idempotency/exactness that readingsEqual relies on).
        assertEquals("version", PinyinFormatter.numberedToToneMarks("version"))
        assertEquals("vw", PinyinFormatter.numberedToToneMarks("vw"))
        assertEquals("u:x", PinyinFormatter.numberedToToneMarks("u:x"))
        assertTrue(PinyinFormatter.readingsEqual("version", "version"))
    }

    @Test fun `readingsEqual matches u colon numbered against tone-marked ü`() {
        assertTrue(PinyinFormatter.readingsEqual("nu:3", "nǚ"))
        assertTrue(PinyinFormatter.readingsEqual("nu:3", "nu:3"))
        assertFalse(PinyinFormatter.readingsEqual("nu:3", "nǔ:"))
    }

    @Test fun `canonicalReading lowercases and folds ü`() {
        assertEquals("nǚ", PinyinFormatter.canonicalReading("Nǚ"))
        assertEquals("dōng", PinyinFormatter.canonicalReading("DŌNG"))
    }
}
