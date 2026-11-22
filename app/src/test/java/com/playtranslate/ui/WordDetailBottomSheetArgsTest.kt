package com.playtranslate.ui

import com.playtranslate.ask.Explanation
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [WordDetailBottomSheet.newInstance]'s arguments read back into the page's
 * [WordDetailBinder.Args] ([WordDetailBottomSheet.argsFrom], what
 * onViewCreated binds): the lookup key survives the Bundle beside the
 * display, and an opener with no key gets the display as its key.
 */
@RunWith(RobolectricTestRunner::class)
class WordDetailBottomSheetArgsTest {

    private fun roundTrip(sheet: WordDetailBottomSheet): WordDetailBinder.Args =
        WordDetailBottomSheet.argsFrom(sheet.requireArguments())!!

    @Test
    fun `a nested sheet carries its parent's sentence and screenshot`() {
        val pending = com.playtranslate.model.PendingTranslation(
            listOf("一番うまく弾けた！"), com.playtranslate.language.SourceLangId.JA, "en", isCapture = true,
        )
        val parent = roundTrip(
            WordDetailBottomSheet.newInstance(
                word = "弾ける", reading = "はじける", surface = "弾けた", screenshotPath = "/shot.png",
                sentenceOriginal = "一番うまく弾けた！", sentenceTranslation = "I played it best!",
                sentenceWordResults = mapOf("弾ける" to Triple("はじける", "to burst", 0)),
                sentencePending = pending,
            ),
        )
        val nested = roundTrip(
            WordDetailBottomSheet.nestedInstance(parent, "弾く", "ひく", "弾けた", "弾く", "ひく"),
        )
        assertEquals("弾く" to "ひく", nested.word to nested.reading)
        assertEquals("弾けた", nested.surface)
        assertEquals("弾く" to "ひく", nested.lookupForm to nested.lookupReading)
        assertEquals("/shot.png", nested.screenshotPath)
        assertEquals("一番うまく弾けた！", nested.sentenceOriginal)
        assertEquals("I played it best!", nested.sentenceTranslation)
        assertEquals(parent.sentenceWordResults, nested.sentenceWordResults)
        assertEquals(pending, nested.sentencePending)
        // A nested sheet is a dialog of its own, never the embedded tab.
        assertEquals(false, nested.embedded)
    }

    // Mutations: drop the explanation from the bundle, or who gave it, or
    // its cut; hand it on to a nested sheet, whose word it is not about.
    @Test
    fun `the opener's explanation round-trips whole, and a nested sheet carries none of it`() {
        val cutAnswer = Explanation("Here it is the potential of 弾く.", "Gemini", cut = true)
        val parent = roundTrip(
            WordDetailBottomSheet.newInstance(
                word = "弾ける", reading = "はじける", sentenceOriginal = "一番うまく弾けた！",
                explanation = cutAnswer,
            ),
        )
        assertEquals(cutAnswer, parent.explanation)
        val whole = Explanation("It means to burst.", "Qwen 3.5")
        assertEquals(whole, roundTrip(WordDetailBottomSheet.newInstance(word = "弾ける", explanation = whole)).explanation)
        assertEquals(null, roundTrip(WordDetailBottomSheet.newInstance(word = "弾ける")).explanation)
        val nested = roundTrip(
            WordDetailBottomSheet.nestedInstance(parent, "弾く", "ひく", "弾けた", "弾く", "ひく"),
        )
        assertEquals("the parent's sentence", "一番うまく弾けた！", nested.sentenceOriginal)
        assertEquals("not the parent's answer", null, nested.explanation)
    }

    // Mutations: key the launch answer by the display word, or without the
    // text it was found as; read an answer that came with no key; leave
    // the answer, or the sentence it is about, out of the launch context.
    @Test
    fun `the detail screen's launch sentence holds its answer under the word as its embedded page names its own`() {
        val words = mapOf("弾ける" to Triple("はじける", "to burst", 0))
        fun launch(surface: String?, lookupForm: String?, answer: Explanation?) = android.content.Intent().apply {
            putExtra(TranslationResultActivity.EXTRA_SENTENCE_TEXT, "一番うまく弾けた！")
            putExtra(TranslationResultActivity.EXTRA_DRAG_WORD, "弾ける")
            putExtra(TranslationResultActivity.EXTRA_DRAG_READING, "はじける")
            surface?.let { putExtra(TranslationResultActivity.EXTRA_DRAG_SURFACE, it) }
            lookupForm?.let {
                putExtra(TranslationResultActivity.EXTRA_DRAG_LOOKUP_FORM, it)
                putExtra(TranslationResultActivity.EXTRA_DRAG_LOOKUP_READING, "ひく")
            }
            // As the lens writes it ([putExplanation]).
            putExtras(android.os.Bundle().apply { putExplanation(TranslationResultActivity.EXTRA_DRAG_EXPLANATION, answer) })
        }
        // The page as the activity mounts it from the same extras.
        fun page(surface: String?) = roundTrip(
            WordDetailBottomSheet.newInstance(
                word = "弾ける", reading = "はじける", surface = surface, embedded = true,
                lookupForm = "弾く", lookupReading = "ひく",
            ),
        )

        fun context(surface: String?, lookupForm: String?, answer: Explanation?) =
            TranslationResultActivity.launchSentenceContext(launch(surface, lookupForm, answer), "I played it best!", words)

        assertEquals(CardTarget("弾けた", "弾く", "ひく"), page("弾けた").target)
        val potential = Explanation("Here it is the potential of 弾く.", "Gemini", cut = true)
        val explained = context("弾けた", "弾く", potential)
        assertEquals(mapOf(page("弾けた").target to potential), explained.explanations)
        assertEquals("the sentence the answer is about", "一番うまく弾けた！", explained.original)
        assertEquals("I played it best!", explained.translation)
        assertEquals(words, explained.wordResults)
        val toPlay = Explanation("To play.", "Gemini")
        assertEquals(
            "an opener with only the lemma",
            mapOf(page(null).target to toPlay),
            context(null, "弾く", toPlay).explanations,
        )
        assertEquals(emptyMap<CardTarget, Explanation>(), context("弾けた", "弾く", null).explanations)
        assertEquals(emptyMap<CardTarget, Explanation>(), context("弾けた", null, toPlay).explanations)
        assertEquals("一番うまく弾けた！", context("弾けた", null, toPlay).original)
    }

    // Mutation: the sheet's arguments take an answer of any length.
    @Test
    fun `an answer longer than a bundle may carry reaches the page cut at the limit`() {
        val args = roundTrip(
            WordDetailBottomSheet.newInstance(
                word = "弾ける", explanation = Explanation("a".repeat(ANKI_NOTE_MAX_CHARS + 500), "Gemini"),
            ),
        )
        assertEquals(Explanation("a".repeat(ANKI_NOTE_MAX_CHARS) + "…", "Gemini"), args.explanation)
    }

    // Mutations: the launch extras take an answer of any length; the cut
    // drops who gave it, or the cut flag.
    @Test
    fun `an answer longer than a launch may carry reaches the results screen's word page cut at the limit, whole otherwise`() {
        val intent = android.content.Intent().apply {
            putExtra(TranslationResultActivity.EXTRA_SENTENCE_TEXT, "一番うまく弾けた！")
            putExtra(TranslationResultActivity.EXTRA_DRAG_LOOKUP_FORM, "弾く")
            putExtras(
                android.os.Bundle().apply {
                    putExplanation(
                        TranslationResultActivity.EXTRA_DRAG_EXPLANATION,
                        Explanation("a".repeat(ANKI_NOTE_MAX_CHARS + 500), "Gemini", cut = true),
                    )
                },
            )
        }
        assertEquals(
            Explanation("a".repeat(ANKI_NOTE_MAX_CHARS) + "…", "Gemini", cut = true),
            TranslationResultActivity.launchSentenceContext(intent, null, null)
                .explanations[pageCardTarget(null, "弾く", null)],
        )
    }

    @Test
    fun `a nested sheet of a parent with no sentence carries none`() {
        val parent = roundTrip(WordDetailBottomSheet.newInstance(word = "弾ける", reading = "はじける"))
        val nested = roundTrip(
            WordDetailBottomSheet.nestedInstance(parent, "弾く", "ひく", "弾けた", "弾く", "ひく"),
        )
        assertEquals(null, nested.sentenceOriginal)
        assertEquals(null, nested.screenshotPath)
    }

    // Mutations: every sheet owns its sentence; a sheet opened from one
    // that does goes back to the activity's (the parent sheet's arguments
    // not read).
    @Test
    fun `a sheet that owns its sentence says so, and so does every sheet opened from it`() {
        val plain = WordDetailBottomSheet.newInstance(word = "弾ける")
        val own = WordDetailBottomSheet.newInstance(word = "興", lookupForm = "興", ownSentence = true)
        assertEquals(false, WordDetailBottomSheet.ownsSentence(plain.requireArguments()))
        assertEquals(false, WordDetailBottomSheet.ownsSentence(null))
        assertEquals(true, WordDetailBottomSheet.ownsSentence(own.requireArguments()))
        // It carries no sentence of its own either.
        assertEquals(null, roundTrip(own).sentenceOriginal)
        assertEquals(null, roundTrip(own).screenshotPath)
        fun nested(from: WordDetailBottomSheet) = WordDetailBottomSheet.nestedInstance(
            roundTrip(from), "興味", "きょうみ", null, "興味", "きょうみ", parentSheet = from.requireArguments(),
        )
        assertEquals(true, WordDetailBottomSheet.ownsSentence(nested(own).requireArguments()))
        assertEquals("two sheets deep", true, WordDetailBottomSheet.ownsSentence(nested(nested(own)).requireArguments()))
        assertEquals(false, WordDetailBottomSheet.ownsSentence(nested(plain).requireArguments()))
    }

    // Mutation: a sheet that owns its sentence takes its activity's.
    @Test
    fun `a sheet takes the live sentence of the activity it is shown in, unless it owns its sentence`() {
        val live = SentenceContext("一番うまく弾けた！", "I played it best!", null)
        val activity = object : SentenceContextProvider {
            override fun currentSentenceContext() = live
        }
        val plain = WordDetailBottomSheet.newInstance(word = "弾ける").requireArguments()
        val own = WordDetailBottomSheet.newInstance(word = "興", lookupForm = "興", ownSentence = true).requireArguments()
        assertEquals(live, WordDetailBottomSheet.hostSentence(plain, activity))
        assertEquals(null, WordDetailBottomSheet.hostSentence(own, activity))
        assertEquals("an activity with no sentence to give", null, WordDetailBottomSheet.hostSentence(plain, Any()))
    }

    @Test
    fun `the key round-trips beside the display`() {
        val args = roundTrip(
            WordDetailBottomSheet.newInstance(
                word = "あずかる", reading = null, lookupForm = "与る", lookupReading = "あずかる",
            ),
        )
        assertEquals("あずかる" to null, args.word to args.reading)
        assertEquals("与る" to "あずかる", args.lookupForm to args.lookupReading)
    }

    @Test
    fun `a key without a reading round-trips its null`() {
        val args = roundTrip(
            WordDetailBottomSheet.newInstance(
                word = "食べる", reading = "たべる", surface = "食べた",
                lookupForm = "食べる", lookupReading = null,
            ),
        )
        assertEquals("食べる" to null, args.lookupForm to args.lookupReading)
        assertEquals("たべる", args.reading)
    }

    @Test
    fun `a bundle without a key resolves under the display, reading included`() {
        // newInstance stores no key for an opener with none, so this bundle
        // is also the shape a bundle saved before the key existed has: the
        // reading must come back as the lookup's, or 弾く read はじく would
        // resolve rank-first as ひく.
        val args = roundTrip(WordDetailBottomSheet.newInstance(word = "明日", reading = "あす"))
        assertEquals("明日" to "あす", args.lookupForm to args.lookupReading)
    }
}
