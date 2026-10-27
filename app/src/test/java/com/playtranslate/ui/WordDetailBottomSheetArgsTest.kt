package com.playtranslate.ui

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

    @Test
    fun `a nested sheet of a parent with no sentence carries none`() {
        val parent = roundTrip(WordDetailBottomSheet.newInstance(word = "弾ける", reading = "はじける"))
        val nested = roundTrip(
            WordDetailBottomSheet.nestedInstance(parent, "弾く", "ひく", "弾けた", "弾く", "ひく"),
        )
        assertEquals(null, nested.sentenceOriginal)
        assertEquals(null, nested.screenshotPath)
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
