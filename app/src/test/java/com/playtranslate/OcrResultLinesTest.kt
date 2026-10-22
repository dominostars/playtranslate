package com.playtranslate

import android.graphics.Rect
import com.playtranslate.language.TextOrientation
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [OcrManager.OcrResult.lines], the one projection drag-to-lookup hit-tests
 * against, for a device result and a cloud result alike: every group's lines
 * in reading order, each carrying its own text, box, symbols, orientation and
 * slant, with its group's position in the result as its group index (whatever
 * its [OcrManager.LineBox.groupIndex] says: a cloud result's carry the wire's
 * ids) and its group's text as its group text.
 *
 * Robolectric only for [Rect].
 */
@RunWith(RobolectricTestRunner::class)
class OcrResultLinesTest {

    private val uprightSymbols = listOf(
        OcrManager.SymbolBox("は", Rect(10, 10, 30, 30), charOffset = 0),
        OcrManager.SymbolBox("い", Rect(30, 10, 50, 30), charOffset = 1),
    )
    private val slantedSymbols = listOf(OcrManager.SymbolBox("H", Rect(200, 40, 220, 70), charOffset = 0))
    private val verticalSymbols = listOf(OcrManager.SymbolBox("縦", Rect(400, 10, 420, 30), charOffset = 0))

    /** Two groups: the first with one upright line, the second with a slanted
     *  line and a vertical one. Each line's own groupIndex names the OTHER
     *  group, so a projection that read it would be seen. */
    private val result = OcrManager.OcrResult(
        fullText = "はい Hello 縦書き",
        segments = emptyList(),
        groups = listOf(
            OcrManager.OcrGroup(
                text = "はい",
                bounds = Rect(10, 10, 50, 30),
                lines = listOf(
                    OcrManager.LineBox(
                        text = "はい", bounds = Rect(10, 10, 50, 30), groupIndex = 1,
                        symbols = uprightSymbols, confidence = 0.9f,
                    ),
                ),
            ),
            OcrManager.OcrGroup(
                text = "Hello 縦書き",
                bounds = Rect(200, 10, 420, 90),
                lines = listOf(
                    OcrManager.LineBox(
                        text = "Hello", bounds = Rect(200, 40, 300, 90), groupIndex = 0,
                        symbols = slantedSymbols,
                        angleDeg = 12.5f, orientedWidth = 96f, orientedHeight = 22f,
                    ),
                    OcrManager.LineBox(
                        text = "縦書き", bounds = Rect(400, 10, 420, 70), groupIndex = 0,
                        symbols = verticalSymbols, orientation = TextOrientation.VERTICAL,
                    ),
                ),
            ),
        ),
    )

    @Test fun `every line carries its own text, box, symbols, orientation and slant, in reading order`() {
        assertEquals(
            listOf(
                OcrManager.OcrLine(
                    text = "はい", bounds = Rect(10, 10, 50, 30), groupIndex = 0, groupText = "はい",
                    symbols = uprightSymbols, orientation = TextOrientation.HORIZONTAL,
                    angleDeg = 0f, orientedWidth = 0f, orientedHeight = 0f,
                ),
                OcrManager.OcrLine(
                    text = "Hello", bounds = Rect(200, 40, 300, 90), groupIndex = 1, groupText = "Hello 縦書き",
                    symbols = slantedSymbols, orientation = TextOrientation.HORIZONTAL,
                    angleDeg = 12.5f, orientedWidth = 96f, orientedHeight = 22f,
                ),
                OcrManager.OcrLine(
                    text = "縦書き", bounds = Rect(400, 10, 420, 70), groupIndex = 1, groupText = "Hello 縦書き",
                    symbols = verticalSymbols, orientation = TextOrientation.VERTICAL,
                    angleDeg = 0f, orientedWidth = 0f, orientedHeight = 0f,
                ),
            ),
            result.lines(),
        )
    }

    @Test fun `a line's group index is its group's position and its group text is its group's text`() {
        val lines = result.lines()
        assertEquals("the position, not LineBox.groupIndex", listOf(0, 1, 1), lines.map { it.groupIndex })
        assertEquals(listOf("はい", "Hello 縦書き", "Hello 縦書き"), lines.map { it.groupText })
    }

    @Test fun `a result whose groups hold no lines gives no lines`() {
        val lineless = OcrManager.OcrResult(
            fullText = "はい",
            segments = emptyList(),
            groups = listOf(OcrManager.OcrGroup(text = "はい", bounds = Rect(10, 10, 50, 30))),
        )
        assertEquals(emptyList<OcrManager.OcrLine>(), lineless.lines())
        assertEquals(emptyList<OcrManager.OcrLine>(), OcrManager.OcrResult(fullText = "", segments = emptyList()).lines())
    }
}
