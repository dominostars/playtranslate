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
