package com.playtranslate.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.Prefs
import com.playtranslate.ask.Explanation
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
 * rides the VM result only. The fallback's explanations ride only while
 * the sentence is the fallback's own.
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

    private val neko = CardTarget("猫", "猫", "ねこ")
    private val eating = Explanation("The one doing the eating.", "Test LLM")
    private val explained = fallback.copy(explanations = mapOf(neko to eating))

    // Mutations: drop the fallback's answers once a result is Ready; keep
    // them beside any text.
    @Test
    fun `the fallback's explanations ride while the sentence is the fallback's`() {
        val vm = TranslationResultViewModel()
        assertEquals("before a result", explained.explanations, vm.sentenceContext(explained).explanations)
        assertEquals(emptyMap<CardTarget, Explanation>(), vm.sentenceContext(null).explanations)

        vm.displayResult(result("猫が食べる。", "The cat eats."), ctx)
        assertEquals("the same sentence, settled", explained.explanations, vm.sentenceContext(explained).explanations)
    }

    // Mutation: an edit of the sentence keeps the answers about the old one.
    @Test
    fun `an edit of the sentence leaves the context with none of the fallback's explanations`() {
        val vm = TranslationResultViewModel()
        vm.displayResult(result("猫が食べる。", "The cat eats."), ctx)
        vm.updateOriginalText("犬が食べる。", ctx)

        val c = vm.sentenceContext(explained)
        assertEquals("犬が食べる。", c.original)
        assertEquals(emptyMap<CardTarget, Explanation>(), c.explanations)
    }

    // The detail screen's word tab, through the functions its card is
    // made by, in the order the screen calls them: the launch extras as a
    // context ([TranslationResultActivity.launchSentenceContext]), this
    // model over it (the activity's context), the page's own sentence
    // ([WordDetailBinder.pageSentence]) and the review's arguments
    // ([WordDetailBinder.wordAnkiArgs]). The page is the one the activity
    // mounts: opened with the word's key and no sentence of its own.
    // Mutation: any of the four hands the review a note that is not about
    // the sentence it hands it.
    @Test
    fun `the detail screen's word card starts with the launch answer until the sentence is edited under it`() {
        val vm = TranslationResultViewModel()
        val launch = android.content.Intent().apply {
            putExtra(TranslationResultActivity.EXTRA_SENTENCE_TEXT, "猫が食べる。")
            putExtra(TranslationResultActivity.EXTRA_DRAG_WORD, "猫")
            putExtra(TranslationResultActivity.EXTRA_DRAG_LOOKUP_FORM, "猫")
            putExtra(TranslationResultActivity.EXTRA_DRAG_LOOKUP_READING, "ねこ")
            // As the lens writes it ([putExplanation]).
            putExtras(android.os.Bundle().apply { putExplanation(TranslationResultActivity.EXTRA_DRAG_EXPLANATION, eating) })
        }
        val page = WordDetailBottomSheet.argsFrom(
            WordDetailBottomSheet.newInstance(
                word = "猫", reading = "ねこ", embedded = true, lookupForm = "猫", lookupReading = "ねこ",
            ).requireArguments(),
        )!!
        fun review(): WordDetailBinder.WordAnkiArgs {
            val activitys = vm.sentenceContext(TranslationResultActivity.launchSentenceContext(launch, null, null))
            return WordDetailBinder.wordAnkiArgs(
                WordDetailBinder.pageSentence(activitys, page), page.target, word = "猫", reading = "ねこ",
                pos = "noun", definition = "cat", screenshotPath = null, freqScore = 5, isCommon = true,
                sourceLangId = SourceLangId.JA,
            )
        }

        assertEquals("before the result has settled", "The one doing the eating.", review().notes)
        vm.displayResult(result("猫が食べる。", "The cat eats."), ctx)
        assertEquals("The one doing the eating.", review().notes)
        assertEquals("猫が食べる。", review().sentenceOriginal)

        vm.updateOriginalText("犬が食べる。", ctx)
        assertEquals("the card is on the edited sentence", "犬が食べる。", review().sentenceOriginal)
        assertEquals("the answer was about the sentence before the edit", "", review().notes)
    }

    @Test
    fun `a landed translation replaces the fallback's`() {
        val vm = TranslationResultViewModel()
        vm.displayResult(result("猫が食べる。", "The cat is eating."), ctx)
        assertEquals("The cat is eating.", vm.sentenceContext(fallback).translation)
        assertNull(vm.sentenceContext(fallback).pending)
    }
}
