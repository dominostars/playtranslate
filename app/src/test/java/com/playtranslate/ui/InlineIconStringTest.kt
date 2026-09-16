package com.playtranslate.ui

import android.text.Spanned
import android.text.style.ImageSpan
import android.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins [inlineIconString]: the `%1$s` placeholder of a localized sentence
 * becomes exactly one inline icon at the position the string put it, and a
 * resource without the placeholder comes back as plain text.
 */
@RunWith(RobolectricTestRunner::class)
class InlineIconStringTest {

    private val ctx = ContextThemeWrapper(
        ApplicationProvider.getApplicationContext(),
        R.style.Theme_PlayTranslate,
    )

    @Test
    fun placeholderBecomesOneIconWhereTheStringPutsIt() {
        val text = inlineIconString(ctx, R.string.anki_words_helper_hide, R.drawable.ic_visibility)
        val spanned = text as Spanned
        val spans = spanned.getSpans(0, text.length, ImageSpan::class.java)
        assertEquals(1, spans.size)
        val at = text.indexOf('￼')
        assertEquals(at, spanned.getSpanStart(spans[0]))
        assertEquals(at + 1, spanned.getSpanEnd(spans[0]))
        assertEquals(at, text.lastIndexOf('￼'))
        assertEquals(
            ctx.getString(R.string.anki_words_helper_hide, "￼"),
            text.toString(),
        )
        assertTrue(text.toString().contains("Tap ￼ to exclude it from the card."))
    }

    @Test
    fun resourceWithoutPlaceholderIsPlainText() {
        val text = inlineIconString(ctx, R.string.anki_group_screenshot, R.drawable.ic_visibility)
        assertFalse(text is Spanned)
        assertEquals(ctx.getString(R.string.anki_group_screenshot), text.toString())
        assertFalse(text.contains('￼'))
    }
}
