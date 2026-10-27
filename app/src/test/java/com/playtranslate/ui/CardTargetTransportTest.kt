package com.playtranslate.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.audio.AudioSelection
import com.playtranslate.language.ReadingOverride
import com.playtranslate.language.SourceLangId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The card's own word ([CardTarget]) and its reading override
 * ([ReadingOverride]) across every transport they ride: the word editor's
 * launch args, the Activity trampoline's extras (which the permission
 * activity forwards whole), the sentence content's persisted state, and the
 * audio picker's extras.
 */
@RunWith(RobolectricTestRunner::class)
class CardTargetTransportTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val target = CardTarget("弾けた", "弾く", "ひく")
    private val override = ReadingOverride("弾けた", "弾く", "ひけた")

    @Test
    fun `the editor's launch args carry the card's word`() {
        val args = WordAnkiReviewBinder.buildArgs("弾く", "ひく", "", "to play", null, target = target)
        assertEquals(target, args.getCardTarget())
        assertNull(WordAnkiReviewBinder.buildArgs("弾く", "ひく", "", "to play", null).getCardTarget())
    }

    @Test
    fun `a target keyed without a reading keeps its null`() {
        val keyless = CardTarget("あずかった", "与る", null)
        assertEquals(keyless, Bundle().apply { putCardTarget(keyless) }.getCardTarget())
    }

    @Test
    fun `the trampoline's extras carry the card's word`() {
        val intent = Intent().putExtras(Bundle().apply { putCardTarget(target) })
        assertEquals(target, intent.extras?.getCardTarget())
    }

    @Test
    fun `a reading override round-trips, and null clears it`() {
        val bundle = Bundle().apply { putReadingOverride(override) }
        assertEquals(override, bundle.getReadingOverride())
        bundle.putReadingOverride(null)
        assertNull(bundle.getReadingOverride())
        assertTrue(bundle.isEmpty)
    }

    @Test
    fun `the audio picker's sentence previews read as the card does`() {
        val sentence = AudioSourcePickerActivity.argsFrom(
            AudioSourcePickerActivity.intent(
                ctx, SourceLangId.JA, "一番うまく弾けた！", null, isWord = false, AudioSelection.Auto,
                readingOverride = override,
            ),
        )
        assertEquals(override, sentence.readingOverride)

        val word = AudioSourcePickerActivity.argsFrom(
            AudioSourcePickerActivity.intent(ctx, SourceLangId.JA, "弾く", "ひく", isWord = true, AudioSelection.Auto),
        )
        assertNull(word.readingOverride)
    }
}
