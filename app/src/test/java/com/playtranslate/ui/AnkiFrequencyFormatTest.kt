package com.playtranslate.ui

import com.playtranslate.model.FrequencyTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [AnkiFrequencyFormat] — the pure pitch/frequency Anki-field
 * formatters. No Android, no Robolectric. These pin the exact output shapes
 * the external templates (Lapis / JPMN) parse, so a formatting tweak that
 * would break a real card fails here first.
 */
class AnkiFrequencyFormatTest {

    // ─── harmonicMean ────────────────────────────────────────────────────

    @Test fun `harmonicMean of two values`() {
        // 2 / (1/10 + 1/30) = 15
        assertEquals(15L, AnkiFrequencyFormat.harmonicMean(listOf(10.0, 30.0)))
    }

    @Test fun `harmonicMean of one value is that value`() {
        assertEquals(1234L, AnkiFrequencyFormat.harmonicMean(listOf(1234.0)))
    }

    @Test fun `harmonicMean rounds to nearest`() {
        // 2 / (1/10 + 1/25) = 14.2857… → 14
        assertEquals(14L, AnkiFrequencyFormat.harmonicMean(listOf(10.0, 25.0)))
    }

    @Test fun `harmonicMean ignores non-positive values`() {
        // Only 10.0 participates; 0 and -5 are dropped (div-by-zero / nonsense rank).
        assertEquals(10L, AnkiFrequencyFormat.harmonicMean(listOf(0.0, -5.0, 10.0)))
    }

    @Test fun `harmonicMean is null when empty or all non-positive`() {
        assertNull(AnkiFrequencyFormat.harmonicMean(emptyList()))
        assertNull(AnkiFrequencyFormat.harmonicMean(listOf(0.0, -1.0)))
    }

    // ─── pitchPositions ──────────────────────────────────────────────────

    @Test fun `pitchPositions joins downsteps with commas`() {
        assertEquals("", AnkiFrequencyFormat.pitchPositions(emptyList()))
        assertEquals("0", AnkiFrequencyFormat.pitchPositions(listOf(0)))
        assertEquals("0,2", AnkiFrequencyFormat.pitchPositions(listOf(0, 2)))
    }

    // ─── frequencyValuesHtml (Lapis <ul>) ────────────────────────────────

    @Test fun `frequencyValuesHtml is empty with no stars and no freqs`() {
        assertEquals("", AnkiFrequencyFormat.frequencyValuesHtml(0, emptyList()))
    }

    @Test fun `frequencyValuesHtml stars only when no freq dicts`() {
        assertEquals(
            "<ul><li>★★</li></ul>",
            AnkiFrequencyFormat.frequencyValuesHtml(2, emptyList()),
        )
    }

    @Test fun `frequencyValuesHtml leads with stars then per-dict items`() {
        assertEquals(
            "<ul><li>★★★</li><li>JPDB: 1234</li><li>CC100: 5678</li></ul>",
            AnkiFrequencyFormat.frequencyValuesHtml(
                3,
                listOf(FrequencyTag("JPDB", "1234"), FrequencyTag("CC100", "5678")),
            ),
        )
    }

    @Test fun `frequencyValuesHtml escapes source and display (comma-safe)`() {
        // A display with a comma (thousands separator) must survive — emitting
        // a <ul> rather than comma-text is exactly why.
        assertEquals(
            "<ul><li>A&lt;b&gt;: 1,234 &amp; up</li></ul>",
            AnkiFrequencyFormat.frequencyValuesHtml(0, listOf(FrequencyTag("A<b>", "1,234 & up"))),
        )
    }

    // ─── frequenciesStylizedJpmn ─────────────────────────────────────────

    private fun jpmnGroup(number: String, dictionary: String) =
        "<div class=\"frequencies__group\" data-details=\"$dictionary\">" +
            "<div class=\"frequencies__number\">" +
            "<span class=\"frequencies__number-inner\">$number</span></div>" +
            "<div class=\"frequencies__dictionary\">" +
            "<span class=\"frequencies__dictionary-inner\">$dictionary</span></div>" +
            "</div>"

    @Test fun `frequenciesStylizedJpmn is empty with no stars and no freqs`() {
        assertEquals("", AnkiFrequencyFormat.frequenciesStylizedJpmn(0, emptyList()))
    }

    @Test fun `frequenciesStylizedJpmn emits one group per dictionary`() {
        assertEquals(
            jpmnGroup("1234", "JPDB"),
            AnkiFrequencyFormat.frequenciesStylizedJpmn(0, listOf(FrequencyTag("JPDB", "1234"))),
        )
    }

    @Test fun `frequenciesStylizedJpmn leads with a dictionary-less stars group`() {
        assertEquals(
            jpmnGroup("★★", "") + jpmnGroup("1234", "JPDB"),
            AnkiFrequencyFormat.frequenciesStylizedJpmn(2, listOf(FrequencyTag("JPDB", "1234"))),
        )
    }
}
