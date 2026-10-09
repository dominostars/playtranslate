package com.playtranslate.ocr.core

import android.graphics.Rect
import com.playtranslate.language.TextOrientation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** [joinLines]: the one join of a group's lines into its text, and the
 *  addresses it hands each line. */
@RunWith(RobolectricTestRunner::class)
class JoinLinesTest {

    private fun line(text: String) =
        RecognizedLine(text, OcrBox.upright(Rect(0, 0, 40, 40)), TextOrientation.HORIZONTAL)

    @Test
    fun `an unspaced join addresses each line at the length of the text before it`() {
        val j = joinLines(listOf(line("それで今日の手伝い終わ"), line("りなねだって。")), "")
        assertEquals("それで今日の手伝い終わりなねだって。", j.text)
        assertEquals(listOf(0, 11), j.lines.map { it.textStart })
    }

    @Test
    fun `a spaced join counts the separator`() {
        val j = joinLines(listOf(line("Hello there"), line("world")), " ")
        assertEquals("Hello there world", j.text)
        assertEquals(listOf(0, 12), j.lines.map { it.textStart })
    }

    @Test
    fun `a line whose address is already right keeps its instance`() {
        val first = line("ab")
        val second = line("cd").copy(textStart = 2)
        val j = joinLines(listOf(first, second), "")
        assertSame(first, j.lines[0])
        assertSame(second, j.lines[1])
    }

    @Test
    fun `the trim moves the addresses with it`() {
        val j = joinLines(listOf(line(" ab"), line("cd")), "")
        assertEquals("abcd", j.text)
        // Line one's 'a' is group offset 0, so the line starts one before the text.
        assertEquals(listOf(-1, 2), j.lines.map { it.textStart })
    }
}
