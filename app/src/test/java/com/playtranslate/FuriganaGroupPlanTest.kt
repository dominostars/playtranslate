package com.playtranslate

import android.graphics.Rect
import com.playtranslate.ui.TextBox
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [planGroup]: the live furigana loop's reuse-or-rebuild decision, driven
 * from the records it tracked last cycle. The hold rides on the record, so
 * a group's reveal state cannot be lost between cycles whatever its build
 * drew (Codex adversarial review, 2026-10-08: a held group whose every ruby
 * was withheld used to drop out of tracking and come back unheld).
 */
@RunWith(RobolectricTestRunner::class)
class FuriganaGroupPlanTest {

    private val bounds = Rect(0, 0, 400, 80)

    private fun tracked(text: String, held: Boolean, boxes: Int = 1) = OverlayToolkit.FuriganaGroup(
        text, bounds,
        List(boxes) { TextBox("x", Rect(0, 0, 10, 10), lineCount = 1, isFurigana = true) },
        held = held,
    )

    @Test
    fun `a held group with nothing drawn keeps the reveal on the next frame`() {
        val prev = listOf(tracked("それは終わ", held = true, boxes = 0))
        assertEquals(GroupPlan.Rebuild(hold = true), planGroup(prev, "それは終わり", bounds))
    }

    @Test
    fun `the held text repeating is the confirmation and releases`() {
        val prev = listOf(tracked("それは終わり。", held = true, boxes = 0))
        assertEquals(GroupPlan.Rebuild(hold = false), planGroup(prev, "それは終わり。", bounds))
    }

    @Test
    fun `a jitter frame over a held group keeps holding`() {
        // Same length (not growth), not the held text: neither settled nor evolving.
        val prev = listOf(tracked("それは終わり。", held = true))
        assertEquals(GroupPlan.Rebuild(hold = true), planGroup(prev, "それは終わリ。", bounds))
    }

    @Test
    fun `an unheld group with the same text and bounds is reused`() {
        val g = tracked("それは終わり。", held = false)
        assertEquals(GroupPlan.Reuse(g), planGroup(listOf(g), "それは終わり。", bounds))
    }

    @Test
    fun `text with no tracked neighbour rebuilds released`() {
        assertEquals(GroupPlan.Rebuild(hold = false), planGroup(emptyList(), "新しい文", bounds))
    }

    @Test
    fun `growth over an unheld group starts a hold`() {
        val prev = listOf(tracked("それは", held = false))
        assertEquals(GroupPlan.Rebuild(hold = true), planGroup(prev, "それは終", bounds))
    }
}
