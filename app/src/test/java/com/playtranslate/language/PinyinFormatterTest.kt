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
}
