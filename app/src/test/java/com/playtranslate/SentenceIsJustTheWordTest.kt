package com.playtranslate

import com.playtranslate.ui.sentenceIsJustTheWord
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The single shared "is there a real sentence?" rule that every Anki entry
 * point now funnels through (the review sheet's hasSentenceData gate and the
 * one-tap dispatch). A "sentence" that is absent or just the headword must NOT
 * trigger a Sentence card that merely repeats the word.
 */
class SentenceIsJustTheWordTest {

    @Test fun null_sentence_is_just_the_word() {
        assertTrue(sentenceIsJustTheWord(null, "行く"))
    }

    @Test fun sentence_equal_to_word_is_just_the_word() {
        assertTrue(sentenceIsJustTheWord("行く", "行く"))
    }

    @Test fun whitespace_only_differences_still_count_as_just_the_word() {
        // singleWordRow-style bare() comparison strips whitespace.
        assertTrue(sentenceIsJustTheWord("  行く ", "行く"))
        assertTrue(sentenceIsJustTheWord("cat", "  cat"))
    }

    @Test fun a_real_surrounding_sentence_is_not_just_the_word() {
        assertFalse(sentenceIsJustTheWord("私は学校に行く", "行く"))
    }

    @Test fun trailing_punctuation_is_significant() {
        // A trailing 。 means there is genuinely more than the bare word.
        assertFalse(sentenceIsJustTheWord("行く。", "行く"))
    }
}
