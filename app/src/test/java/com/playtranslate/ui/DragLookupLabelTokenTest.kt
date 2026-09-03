package com.playtranslate.ui

import com.playtranslate.language.AnnotatedSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins [DragLookupController.labelTokenFrom]: the drag-time pill labels the
 * OCR'd SURFACE with its occurrence reading, while the resolved dictionary
 * form rides along only as lookup identity. Regression for the それとも drag
 * that labelled 其れとも (the annotation's canonical spelling) until the
 * definitions replaced the pill. Pure JUnit.
 */
class DragLookupLabelTokenTest {

    private fun span(
        surface: String,
        word: String?,
        reading: String?,
        lookupForm: String? = word,
        start: Int = 0,
    ) = AnnotatedSpan(
        start = start, end = if (start < 0) -1 else start + surface.length,
        surface = surface, lookupForm = lookupForm, word = word, reading = reading,
    )

    @Test fun `kana surface labels as the kana, not the resolved kanji form`() {
        // それとも resolves to JMdict 其れとも (a rarely-used kanji form).
        val t = DragLookupController.labelTokenFrom(span("それとも", word = "其れとも", reading = "それとも"))!!
        assertEquals("それとも", t.surface)
        assertNull("reading equal to the kana surface adds nothing", t.reading)
        assertEquals("lookup identity keeps the dictionary form", "其れとも", t.lookupForm)
        assertEquals(0, t.charOffset)
    }

    @Test fun `inflected kanji surface keeps its own occurrence reading`() {
        val t = DragLookupController.labelTokenFrom(span("食べていた", word = "食べる", reading = "たべていた", start = 3))!!
        assertEquals("食べていた", t.surface)
        assertEquals("たべていた", t.reading)
        assertEquals("食べる", t.lookupForm)
        assertEquals(3, t.charOffset)
    }

    @Test fun `unresolved content span still labels the surface and looks up the lemma`() {
        val t = DragLookupController.labelTokenFrom(span("走った", word = null, lookupForm = "走る", reading = "はしった"))!!
        assertEquals("走った", t.surface)
        assertEquals("走る", t.lookupForm)
        assertEquals("はしった", t.reading)
    }

    @Test fun `katakana surface drops a redundant reading`() {
        val t = DragLookupController.labelTokenFrom(span("カラス", word = "烏", reading = "カラス"))!!
        assertEquals("カラス", t.surface)
        assertNull(t.reading)
    }

    @Test fun `span with neither lemma nor resolved word yields no token`() {
        assertNull(DragLookupController.labelTokenFrom(span("。", word = null, reading = null, lookupForm = null)))
    }

    @Test fun `offsetless lexical span yields no token`() {
        assertNull(DragLookupController.labelTokenFrom(span("word", word = "word", reading = null, start = -1)))
    }
}
