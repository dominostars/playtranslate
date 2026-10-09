package com.playtranslate.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [WordDetailBinder.Args]' lookup key: an opener with no key of its own
 * resolves under the display word and reading, as the page did before the
 * key existed; an opener that has one keeps it beside the display, which
 * the header and the Anki card still use.
 */
class WordDetailArgsTest {

    @Test
    fun `the key defaults to the display word and reading`() {
        val args = WordDetailBinder.Args(word = "明日", reading = "あす", surface = null, screenshotPath = null)
        assertEquals("明日" to "あす", args.lookupForm to args.lookupReading)
    }

    @Test
    fun `an explicit key is kept beside the display`() {
        val args = WordDetailBinder.Args(
            word = "あずかる", reading = null, surface = null, screenshotPath = null,
            lookupForm = "与る", lookupReading = "あずかる",
        )
        assertEquals("与る" to "あずかる", args.lookupForm to args.lookupReading)
        assertEquals("あずかる" to null, args.word to args.reading)
    }

    @Test
    fun `an explicit key without a reading keeps the null`() {
        val args = WordDetailBinder.Args(
            word = "食べる", reading = "たべる", surface = "食べた", screenshotPath = null,
            lookupForm = "食べる", lookupReading = null,
        )
        assertEquals(null, args.lookupReading)
    }
}
