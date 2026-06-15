package com.playtranslate.ocr.core

import android.graphics.Rect
import com.playtranslate.language.TextOrientation
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the visual→logical reorder for RTL (Arabic).
 *
 * The pure-word case uses the EXACT codepoints observed on-device: the word
 * كتاب ("book") comes out of the PaddleOCR CTC recognizer as باتك. The mixed
 * cases use a round-trip — take a known LOGICAL string, render it to VISUAL
 * order with a forward Bidi pass (what the recognizer effectively does), feed
 * that to [RtlReorder], and assert we recover the original logical string —
 * which validates the embedded-LTR-run handling (digits, Latin) without having
 * to hand-derive visual order.
 *
 * Robolectric for real `android.icu.text.Bidi` + `android.graphics.Rect`.
 */
@RunWith(RobolectricTestRunner::class)
class RtlReorderTest {

    private fun ob(l: Int, r: Int) = OcrBox.upright(Rect(l, 0, r, 10))

    private fun region(text: String, chars: List<CharBox> = emptyList()): RecognizedRegion {
        val box = ob(0, 100)
        return RecognizedRegion(
            text = text,
            box = box,
            orientation = TextOrientation.HORIZONTAL,
            lines = listOf(RecognizedLine(text, box, TextOrientation.HORIZONTAL, chars = chars)),
        )
    }

    @Test
    fun pureArabicWord_reversesAndRemapsOffsets() {
        val visual = "باتك"   // CTC strip-scan (visual) order
        val logical = "كتاب"  // correct reading order
        // Char boxes are emitted in visual order — ب ا ت ك at increasing x, offsets 0..3.
        val chars = listOf(
            CharBox("ب", ob(0, 10), 0),
            CharBox("ا", ob(10, 20), 1),
            CharBox("ت", ob(20, 30), 2),
            CharBox("ك", ob(30, 40), 3),
        )
        val out = RtlReorder.toLogical(region(visual, chars))

        assertEquals(logical, out.text)
        assertEquals(logical, out.lines[0].text)
        // Offsets become logical positions [3,2,1,0]; glyphs + pixel boxes unchanged.
        val oc = out.lines[0].chars
        assertEquals(listOf(3, 2, 1, 0), oc.map { it.charOffset })
        assertEquals(listOf("ب", "ا", "ت", "ك"), oc.map { it.text })
        assertEquals(0, oc[0].box.bounds.left)   // ب still at its leftmost pixel
        assertEquals(30, oc[3].box.bounds.left)  // ك still at its rightmost pixel
    }

    @Test
    fun pureRegions_arabicReversesLtrIdentity() {
        // The recognizer emits per-word regions for word-spaced scripts, so each
        // region is a single word/number/token. An Arabic word arrives reversed
        // (visual) and must flip to logical; a pure number / Latin region is already
        // logical and must be left exactly as-is (no "100" → "001").
        // Pure-Arabic region: visual order is the logical string reversed; the
        // transform must recover logical. Built via .reversed() so the reversed form
        // isn't hand-transcribed (error-prone for RTL).
        for (word in listOf("كتاب", "مرحبا", "السعر")) {
            assertEquals(word, RtlReorder.toLogical(region(word.reversed())).text)
        }
        // Pure LTR regions (numbers, Latin tokens) are already logical — identity,
        // NOT reversed ("100" must not become "001").
        assertEquals("100", RtlReorder.toLogical(region("100")).text)
        assertEquals("OK", RtlReorder.toLogical(region("OK")).text)
        assertEquals("3.5", RtlReorder.toLogical(region("3.5")).text)
    }

    @Test
    fun ltrSourceUntouched() {
        // Sanity: a Latin string is its own visual order — reorder must be a no-op
        // for text with no RTL runs.
        assertEquals("hello 42", RtlReorder.toLogical(region("hello 42")).text)
    }
}
