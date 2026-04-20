package com.playtranslate.ui

import android.graphics.Rect
import com.playtranslate.OcrManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for the offset-based token hit-test logic in
 * [DragLookupController.Companion.findClosestToken]. Runs under Robolectric
 * because [android.graphics.Rect] is required for [OcrManager.SymbolBox].
 *
 * Symbol lists do NOT include entries for space characters — spaces have
 * no OCR bounds. Tokens are found by `lineText.indexOf(token)` and then
 * symbols in that offset range are collected via `charOffset in idx until
 * endIdx`. Missing symbols for some characters fall through to the
 * charWidth approximation for that token.
 */
@RunWith(RobolectricTestRunner::class)
class FindClosestTokenTest {

    /**
     * Build uniform-width symbols for each NON-SPACE character in [lineText].
     * Each character gets a [charWidth]-pixel-wide rect; its [charOffset]
     * matches its position in [lineText]. Spaces are skipped (no OCR symbol).
     */
    private fun uniformSymbols(lineText: String, lineLeft: Int, charWidth: Int): List<OcrManager.SymbolBox> =
        lineText.mapIndexedNotNull { i, ch ->
            if (ch == ' ') return@mapIndexedNotNull null
            val left = lineLeft + i * charWidth
            OcrManager.SymbolBox(
                text = ch.toString(),
                bounds = Rect(left, 0, left + charWidth, 20),
                charOffset = i,
            )
        }

    /** Build symbols with explicit per-character bounds for proportional fonts. */
    private fun proportionalSymbols(text: String, rights: IntArray, height: Int = 20): List<OcrManager.SymbolBox> {
        require(rights.size == text.length)
        var left = 0
        return text.mapIndexed { i, ch ->
            val r = rights[i]
            val s = OcrManager.SymbolBox(text = ch.toString(), bounds = Rect(left, 0, r, height), charOffset = i)
            left = r
            s
        }
    }

    @Test fun `symbol-aware hit finds token containing finger`() {
        // "hello world" — space at index 5 has no symbol. charWidth=10, lineLeft=100.
        // Finger at x=170 → inside "world" (charOffset 6..10).
        val line = "hello world"
        val symbols = uniformSymbols(line, lineLeft = 100, charWidth = 10)
        val match = DragLookupController.findClosestToken(
            lineText = line,
            tokens = listOf("hello", "world"),
            fingerPos = 170,
            symbols = symbols,
            fallbackLineStart = 100,
            fallbackCharExtent = 10f,
        )
        assertEquals("world" to 6, match)
    }

    @Test fun `symbol-aware hit handles proportional widths`() {
        // "Iw" — `I` is narrow (right=5), `w` is wide (right=30).
        // fingerX=15 is inside `w`, not `I`.
        val line = "Iw"
        val symbols = proportionalSymbols(line, rights = intArrayOf(5, 30))
        val match = DragLookupController.findClosestToken(
            lineText = line,
            tokens = listOf("I", "w"),
            fingerPos = 15,
            symbols = symbols,
            fallbackLineStart = 0,
            fallbackCharExtent = 15f,
        )
        assertEquals("w" to 1, match)
    }

    @Test fun `empty symbols falls back to charWidth math`() {
        val line = "今日は"
        val tokens = listOf("今日", "は")
        val match = DragLookupController.findClosestToken(
            lineText = line,
            tokens = tokens,
            fingerPos = 50,
            symbols = emptyList(),
            fallbackLineStart = 0,
            fallbackCharExtent = 20f,
        )
        assertEquals("は" to 2, match)
    }

    @Test fun `partial symbols still finds token via available chars`() {
        // "hello world" but ML Kit only emitted symbols for "hello" (offsets
        // 0..4). "world" at offsets 6..10 has no symbols → charWidth fallback.
        // Finger at x=170 → charWidth puts idx 7 inside "world" [6..11).
        val line = "hello world"
        val partialSymbols = "hello".mapIndexed { i, ch ->
            val left = 100 + i * 10
            OcrManager.SymbolBox(ch.toString(), Rect(left, 0, left + 10, 20), charOffset = i)
        }
        val match = DragLookupController.findClosestToken(
            lineText = line,
            tokens = listOf("hello", "world"),
            fingerPos = 170,
            symbols = partialSymbols,
            fallbackLineStart = 100,
            fallbackCharExtent = 10f,
        )
        assertEquals("world" to 6, match)
    }

    @Test fun `interior dropped symbol still resolves via nearest center`() {
        // "world" at offsets 6..10 has symbols for w,o,r,l but NOT d (offset 10).
        // Finger lands at the 'd' position (x=200). Exact-hit misses because
        // the symbol-derived span for "world" only covers w..l. Nearest-center
        // still picks "world" because its center (~165) is closer than "hello"'s (~125).
        val line = "hello world"
        val symbols = buildList {
            // "hello" at offsets 0..4 — full coverage
            for (i in 0..4) {
                val left = 100 + i * 10
                add(OcrManager.SymbolBox("hello"[i].toString(), Rect(left, 0, left + 10, 20), charOffset = i))
            }
            // "world" at offsets 6..10 — drop 'd' at offset 10
            for ((ci, i) in (6..9).withIndex()) {
                val left = 100 + i * 10
                add(OcrManager.SymbolBox("worl"[ci].toString(), Rect(left, 0, left + 10, 20), charOffset = i))
            }
        }
        val match = DragLookupController.findClosestToken(
            lineText = line,
            tokens = listOf("hello", "world"),
            fingerPos = 200, // right at the missing 'd' position
            symbols = symbols,
            fallbackLineStart = 100,
            fallbackCharExtent = 10f,
        )
        assertEquals("world" to 6, match)
    }

    @Test fun `empty tokens returns null`() {
        assertNull(DragLookupController.findClosestToken(
            lineText = "hello",
            tokens = emptyList(),
            fingerPos = 50,
            symbols = emptyList(),
            fallbackLineStart = 0,
            fallbackCharExtent = 10f,
        ))
    }

    @Test fun `finger beyond last token picks rightmost by nearest center`() {
        val line = "hello world"
        val symbols = uniformSymbols(line, lineLeft = 100, charWidth = 10)
        val match = DragLookupController.findClosestToken(
            lineText = line,
            tokens = listOf("hello", "world"),
            fingerPos = 500,
            symbols = symbols,
            fallbackLineStart = 100,
            fallbackCharExtent = 10f,
        )
        assertEquals("world" to 6, match)
    }

    @Test fun `finger between tokens picks nearest center`() {
        // "ab  cd" — spaces at indices 2,3 have no symbols. Finger at x=25.
        // "ab" symbols at offsets 0,1 → bounds [0,20). "cd" at offsets 4,5 → [40,60).
        // Nearest center: "ab" center=10 vs "cd" center=50. |25-10|=15, |25-50|=25. "ab" wins.
        val line = "ab  cd"
        val symbols = uniformSymbols(line, lineLeft = 0, charWidth = 10)
        val match = DragLookupController.findClosestToken(
            lineText = line,
            tokens = listOf("ab", "cd"),
            fingerPos = 25,
            symbols = symbols,
            fallbackLineStart = 0,
            fallbackCharExtent = 10f,
        )
        assertEquals("ab" to 0, match)
    }
}
