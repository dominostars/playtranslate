package com.playtranslate.tts

import com.playtranslate.language.SourceLangId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [voiceSampleText]: a sentence for every game language, each its own, and
 * written so a voice service that is told nothing but the text cannot take
 * it for another language.
 */
class VoiceSampleTest {

    private fun String.hasKana() = any { it in '぀'..'ヿ' }
    private fun String.hasHan() = any { it in '一'..'鿿' }

    @Test fun `every game language has a sample that is a sentence, not a word`() {
        for (lang in SourceLangId.entries) {
            val sample = voiceSampleText(lang)
            assertTrue("$lang: $sample", sample.length >= 12)
        }
        assertEquals(
            "each language's sample is its own",
            SourceLangId.entries.size,
            SourceLangId.entries.map(::voiceSampleText).toSet().size,
        )
    }

    @Test fun `the Japanese sample holds kana, so it cannot be read as Chinese`() {
        // The sample that was replaced, 日本語, is kanji alone: a service
        // voice read it as Chinese.
        assertTrue(voiceSampleText(SourceLangId.JA).hasKana())
        assertFalse("the old sample had none", "日本語".hasKana())
    }

    @Test fun `the Chinese samples are Chinese text and the two scripts differ`() {
        val simplified = voiceSampleText(SourceLangId.ZH)
        val traditional = voiceSampleText(SourceLangId.ZH_HANT)
        assertTrue(simplified.hasHan() && !simplified.hasKana())
        assertTrue(traditional.hasHan() && !traditional.hasKana())
        assertTrue(simplified.contains('这') && traditional.contains('這'))
    }

    @Test fun `the Korean sample is Hangul`() {
        assertTrue(voiceSampleText(SourceLangId.KO).any { it in '가'..'힣' })
    }
}
