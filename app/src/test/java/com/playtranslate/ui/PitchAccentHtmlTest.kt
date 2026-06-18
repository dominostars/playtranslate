package com.playtranslate.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [PitchAccentHtml.pitchAccentHtml] — the pure HTML pitch-accent
 * renderer for PT's own card backs. Pins the overline (`pa-h`) / drop-tick
 * (`pa-d`) placement against the four standard accent shapes plus the gap-free
 * contract that keeps the overline continuous.
 */
class PitchAccentHtmlTest {

    private fun html(reading: String, vararg pitch: Int) =
        PitchAccentHtml.pitchAccentHtml(reading, pitch.toList())

    @Test fun `empty reading or pitch yields empty`() {
        assertEquals("", PitchAccentHtml.pitchAccentHtml("", listOf(0)))
        assertEquals("", PitchAccentHtml.pitchAccentHtml("ねこ", emptyList()))
    }

    @Test fun `heiban overlines from the 2nd mora with no drop tick`() {
        // にほんご, downstep 0: L H H H, particle stays high → never falls.
        val r = html("にほんご", 0)
        assertTrue(r.contains("pa-h"))
        assertFalse("heiban never drops: $r", r.contains("pa-d"))
    }

    @Test fun `atamadaka highs the 1st mora then drops`() {
        // ねこ, downstep 1: H L → drop after mora 1.
        val r = html("ねこ", 1)
        assertTrue(r.contains("class=\"pa-m pa-h pa-d\">ね</span>"))
        assertEquals(1, "pa-d".toRegex().findAll(r).count())
    }

    @Test fun `nakadaka drops mid-word`() {
        // あなた, downstep 2: L H L → overline + drop on mora 2 (な).
        val r = html("あなた", 2)
        assertTrue(r.contains("class=\"pa-m pa-h pa-d\">な</span>"))
        assertTrue(r.contains("class=\"pa-m\">あ</span>"))
        assertTrue(r.contains("class=\"pa-m\">た</span>"))
    }

    @Test fun `odaka drops on the final mora`() {
        // おとこ, downstep 3 (= mora count): L H H, drop lands on the particle
        // → tick on the last mora.
        val r = html("おとこ", 3)
        assertTrue(r.contains("class=\"pa-m pa-h pa-d\">こ</span>"))
    }

    @Test fun `single-mora atamadaka drops`() {
        assertTrue(html("め", 1).contains("class=\"pa-m pa-h pa-d\">め</span>"))
    }

    @Test fun `mora spans are emitted gap-free`() {
        // inline-block morae separated by whitespace would break the overline.
        assertFalse("no whitespace between spans", html("にほんご", 0).contains("</span> <span"))
    }

    @Test fun `suffix lists every variant, diagram draws the primary`() {
        val r = html("はし", 0, 2)
        assertTrue(r.contains("[0]·[2]"))
        assertFalse("primary 0 = heiban → no drop", r.contains("pa-d"))
    }
}
