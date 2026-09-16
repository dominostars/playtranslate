package com.playtranslate.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.Prefs
import com.playtranslate.language.SourceLangId
import com.playtranslate.model.PendingTranslation
import com.playtranslate.model.TextSegments
import com.playtranslate.model.TranslationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins [TranslationResultViewModel.sentenceContext], the sentence context
 * an embedded word surface hands its Anki card: every field reads the VM
 * first and the fallback second (an Idle VM yields the fallback whole; a
 * Ready result's text wins field by field), a Ready result's BLANK
 * translation is the truth (an edit's re-translate in flight must not pair
 * the fallback's translation with a re-edited original), and the pending
 * rides the VM result only.
 */
@RunWith(RobolectricTestRunner::class)
class TranslationResultViewModelSentenceContextTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val fallback = SentenceContext(
        original = "猫が食べる。",
        translation = "The cat eats.",
        wordResults = mapOf("猫" to Triple("ねこ", "cat", 5)),
    )

    private fun result(text: String, translation: String, pending: PendingTranslation? = null) =
        TranslationResult(
            originalText = text, segments = TextSegments.ofText(text), translatedText = translation,
            timestamp = "", pendingTranslation = pending, langContext = Prefs(ctx).langContext(),
        )

    @Test
    fun `an idle VM yields the fallback whole, with no pending`() {
        val vm = TranslationResultViewModel()
        val c = vm.sentenceContext(fallback)
        assertEquals(fallback.original, c.original)
        assertEquals(fallback.translation, c.translation)
        assertSame(fallback.wordResults, c.wordResults)
        assertNull(c.surfaceForms)
        assertNull(c.pending)
        assertNull(vm.sentenceContext(null).original)
    }

    @Test
    fun `a Ready result's text wins and its pending rides`() {
        val vm = TranslationResultViewModel()
        val pending = PendingTranslation(listOf("犬が走る。"), SourceLangId.JA, "en")
        vm.displayResult(result("犬が走る。", "", pending), ctx)
        val c = vm.sentenceContext(fallback)
        assertEquals("犬が走る。", c.original)
        assertEquals("a blank Ready translation is the truth", "", c.translation)
        assertSame(pending, c.pending)
        // Word maps come from a settled lookup or the fallback, never mixed
        // with the fallback's text.
        assertSame(fallback.wordResults, c.wordResults)
    }

    @Test
    fun `a landed translation replaces the fallback's`() {
        val vm = TranslationResultViewModel()
        vm.displayResult(result("猫が食べる。", "The cat is eating."), ctx)
        assertEquals("The cat is eating.", vm.sentenceContext(fallback).translation)
        assertNull(vm.sentenceContext(fallback).pending)
    }
}
