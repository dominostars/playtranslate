package com.playtranslate.ui

import android.graphics.Rect
import com.playtranslate.OcrManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for the symbol-aware token hit-test logic in
 * [DragLookupController.Companion.findClosestToken]. Runs under Robolectric
 * because [android.graphics.Rect] is required to construct [OcrManager.SymbolBox]
 * instances.
 */
@RunWith(RobolectricTestRunner::class)
class FindClosestTokenTest {

    /**
     * Build symbol bounds for a Latin line where each character is a
     * [charWidth]-pixel rectangle starting at [lineLeft]. Simulates a
     * monospaced font for test simplicity; real Latin tests vary widths.
     */
    private fun uniformSymbols(text: String, lineLeft: Int, charWidth: Int): List<OcrManager.SymbolBox> =
        text.mapIndexed { i, ch ->
            val left = lineLeft + i * charWidth
            OcrManager.SymbolBox(
                text = ch.toString(),
                bounds = Rect(left, 0, left + charWidth, 20)
            )
        }

    /** Build symbols with explicit per-character left-positions for proportional fonts. */
    private fun proportionalSymbols(text: String, rights: IntArray, height: Int = 20): List<OcrManager.SymbolBox> {
        require(rights.size == text.length)
        var left = 0
        return text.mapIndexed { i, ch ->
            val r = rights[i]
            val s = OcrManager.SymbolBox(text = ch.toString(), bounds = Rect(left, 0, r, height))
            left = r
            s
        }
    }

    @Test fun `symbol-aware hit finds token containing finger`() {
        // "hello world" — 11 chars including the space. Each char 10px wide,
        // starting at x=100. Finger at x=170 lands inside "world" (idx 6..11).
        val line = "hello world"
        val symbols = uniformSymbols(line, lineLeft = 100, charWidth = 10)
        val tokens = listOf("hello", "world")
        val match = DragLookupController.findClosestToken(
            lineText = line,
            tokens = tokens,
            fingerX = 170,
            symbols = symbols,
            fallbackLineLeft = 100,
            fallbackCharWidth = 10f,
        )
        assertEquals("world" to 6, match)
    }

    @Test fun `symbol-aware hit handles proportional widths`() {
        // "Iw" — `I` is narrow (right edge at 5), `w` is wide (right edge at
        // 30). fingerX = 15 is inside `w`, not `I`. Uniform math would say
        // idx 1 → x in [charWidth..2*charWidth); if charWidth=15 that puts
        // fingerX=15 on the `w` boundary — ambiguous. Symbol bounds are
        // unambiguous.
        val line = "Iw"
        val symbols = proportionalSymbols(line, rights = intArrayOf(5, 30))
        val tokens = listOf("I", "w")
        val match = DragLookupController.findClosestToken(
            lineText = line,
            tokens = tokens,
            fingerX = 15,
            symbols = symbols,
            fallbackLineLeft = 0,
            fallbackCharWidth = 15f,
        )
        assertEquals("w" to 1, match)
    }

    @Test fun `empty symbols falls back to charWidth math`() {
        // Simulates CJK where ML Kit didn't emit Symbols (pre-Phase-3
        // behavior). charWidth math kicks in.
        val line = "今日は"
        val tokens = listOf("今日", "は")
        // lineLeft=0, charWidth=20 → "今日" occupies [0, 40), "は" occupies [40, 60).
        val match = DragLookupController.findClosestToken(
            lineText = line,
            tokens = tokens,
            fingerX = 50,
            symbols = emptyList(),
            fallbackLineLeft = 0,
            fallbackCharWidth = 20f,
        )
        assertEquals("は" to 2, match)
    }

    @Test fun `mismatched symbol count falls back to charWidth`() {
        // ML Kit returned 10 symbols but lineText has 11 chars — alignment
        // broken, so useSymbols=false and charWidth math is used.
        val line = "hello world"
        val shortSymbols = uniformSymbols("hello worl", lineLeft = 100, charWidth = 10)
        val match = DragLookupController.findClosestToken(
            lineText = line,
            tokens = listOf("hello", "world"),
            fingerX = 170,  // 170 - 100 = 70; 70 / 10 = idx 7, within "world" [6..11)
            symbols = shortSymbols,
            fallbackLineLeft = 100,
            fallbackCharWidth = 10f,
        )
        assertEquals("world" to 6, match)
    }

    @Test fun `empty tokens returns null`() {
        val match = DragLookupController.findClosestToken(
            lineText = "hello",
            tokens = emptyList(),
            fingerX = 50,
            symbols = emptyList(),
            fallbackLineLeft = 0,
            fallbackCharWidth = 10f,
        )
        assertNull(match)
    }

    @Test fun `finger beyond last token picks rightmost by nearest center`() {
        // fingerX=500 is way past any token; nearest-center should pick
        // "world" (the rightmost positioned token).
        val line = "hello world"
        val symbols = uniformSymbols(line, lineLeft = 100, charWidth = 10)
        val match = DragLookupController.findClosestToken(
            lineText = line,
            tokens = listOf("hello", "world"),
            fingerX = 500,
            symbols = symbols,
            fallbackLineLeft = 100,
            fallbackCharWidth = 10f,
        )
        assertEquals("world" to 6, match)
    }

    @Test fun `finger between tokens picks nearest center`() {
        // Two tokens with a gap — finger lands in the gap.
        // "ab  cd" — symbols at x=[0,10,20,30,40,50]. fingerX=25 is between
        // "ab" (center=5) and "cd" (center=45). Nearest center: "ab".
        // This test protects the nearest-center fallback path.
        val line = "ab  cd"
        val symbols = uniformSymbols(line, lineLeft = 0, charWidth = 10)
        val match = DragLookupController.findClosestToken(
            lineText = line,
            tokens = listOf("ab", "cd"),
            fingerX = 25,
            symbols = symbols,
            fallbackLineLeft = 0,
            fallbackCharWidth = 10f,
        )
        // fingerX=25 is exactly at the end-boundary of "ab" (right=20) + 5px.
        // exact hit test: fingerX >= 0 && fingerX <= 20 → no; fingerX >= 40 && fingerX <= 60 → no.
        // nearest-center: "ab" center=10, "cd" center=50. |25-10|=15, |25-50|=25. "ab" wins.
        assertEquals("ab" to 0, match)
    }
}
