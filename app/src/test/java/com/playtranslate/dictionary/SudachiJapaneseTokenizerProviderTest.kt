package com.playtranslate.dictionary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Regression for the Codex [high] finding: the public
 * [SudachiJapaneseTokenizer.Provider.analyze] must degrade to empty on a
 * tokenizer failure — annotateForHintText runs it synchronously on the UI
 * thread and the SourceLanguageEngine contract is empty/null, not a crash —
 * while [SudachiJapaneseTokenizer.Provider.preload] must PROPAGATE the failure
 * so JapaneseEngine maps it to PreloadResult.TokenizerInitFailed.
 *
 * Uses the test-only tokenizer override so no packaged `.dic` is required
 * (Sudachi can't tokenize without a dict file in a plain JVM test).
 */
class SudachiJapaneseTokenizerProviderTest {

    private val throwing = object : JapaneseTokenizer {
        override fun analyze(text: String): List<JaToken> = throw RuntimeException("tokenizer boom")
    }

    @Test
    fun `public analyze degrades to empty when the tokenizer throws`() {
        SudachiJapaneseTokenizer.Provider.tokenizerOverrideForTest = throwing
        try {
            assertEquals(emptyList<JaToken>(), SudachiJapaneseTokenizer.Provider.analyze("使う"))
        } finally {
            SudachiJapaneseTokenizer.Provider.tokenizerOverrideForTest = null
        }
    }

    @Test
    fun `preload propagates a tokenizer failure so it surfaces as TokenizerInitFailed`() {
        SudachiJapaneseTokenizer.Provider.tokenizerOverrideForTest = throwing
        try {
            assertThrows(RuntimeException::class.java) {
                SudachiJapaneseTokenizer.Provider.preload()
            }
        } finally {
            SudachiJapaneseTokenizer.Provider.tokenizerOverrideForTest = null
        }
    }

    @Test
    fun `public analyze degrades to empty even when the tokenizer throws an Error`() {
        val throwingError = object : JapaneseTokenizer {
            override fun analyze(text: String): List<JaToken> = throw OutOfMemoryError("simulated")
        }
        SudachiJapaneseTokenizer.Provider.tokenizerOverrideForTest = throwingError
        try {
            assertEquals(emptyList<JaToken>(), SudachiJapaneseTokenizer.Provider.analyze("使う"))
        } finally {
            SudachiJapaneseTokenizer.Provider.tokenizerOverrideForTest = null
        }
    }
}
