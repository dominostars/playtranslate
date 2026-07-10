package com.playtranslate

import android.graphics.Rect
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Vectors for [abutsAnyInflated] — the dying-box fragment-deferral
 * predicate (dialogue-advance tail dance, 2026-07-10). Rects are drawn
 * from the two real traces that motivated and constrain the rule:
 *
 *  - The dialogue tail: a message grows slightly on advance; OCR sees only
 *    the sliver outside the old (dying) box. Placing it strands a fragment
 *    box over the new message's end. Must DEFER.
 *  - The campfire-menu kickoff that killed the OLD suppression guard: its
 *    0.5W/1.5H inflation reached ~200px and starved three legitimate menu
 *    items near an unrelated dying prompt. The tight abutment must catch
 *    only the directly-adjacent item (6px gap) and pass the others.
 *
 * Runs under Robolectric for [android.graphics.Rect].
 */
@RunWith(RobolectricTestRunner::class)
class FragmentDeferralTest {

    private val inflate = PinholeCalibration.FRAGMENT_DEFER_ABUT_PX

    // ── Dialogue-advance tail (the motivating dance) ─────────────────────

    /** Two-line dialogue box, rendered rect incl. ~14px padding. */
    private val dyingDialogue = Rect(400, 500, 900, 610)

    @Test
    fun `same-line tail abutting the dying box's right edge defers`() {
        // Tail starts at the rendered edge (may even overlap the padding).
        val tail = Rect(900, 565, 1080, 605)
        assertTrue(abutsAnyInflated(listOf(dyingDialogue), tail, inflate))
    }

    @Test
    fun `extra wrapped line just below the dying box defers`() {
        val extraLine = Rect(414, 615, 880, 660) // 5px below rendered bottom
        assertTrue(abutsAnyInflated(listOf(dyingDialogue), extraLine, inflate))
    }

    @Test
    fun `unrelated text far from the dying box places`() {
        val hud = Rect(100, 100, 300, 150)
        assertFalse(abutsAnyInflated(listOf(dyingDialogue), hud, inflate))
    }

    // ── Campfire-menu constraint (the old guard's failure case) ──────────

    /** The prompt box the menu-open dim pinhole-removed at c3. */
    private val dyingPrompt = Rect(534, 801, 1098, 951)

    @Test
    fun `menu item 6px from the dying prompt defers one look`() {
        val item6 = Rect(477, 720, 882, 795) // gap 795→801 = 6px
        assertTrue(abutsAnyInflated(listOf(dyingPrompt), item6, inflate))
    }

    @Test
    fun `menu items a row or more away place immediately`() {
        val item4 = Rect(477, 576, 882, 647) // 154px above the prompt
        val item5 = Rect(477, 648, 882, 719) // 82px above the prompt
        assertFalse(abutsAnyInflated(listOf(dyingPrompt), item4, inflate))
        assertFalse(abutsAnyInflated(listOf(dyingPrompt), item5, inflate))
    }

    // ── Boundary + degenerate cases ──────────────────────────────────────

    @Test
    fun `gap just past the inflation places`() {
        val past = Rect(400, dyingDialogue.bottom + inflate + 8, 900, dyingDialogue.bottom + inflate + 48)
        assertFalse(abutsAnyInflated(listOf(dyingDialogue), past, inflate))
    }

    @Test
    fun `gap just inside the inflation defers`() {
        val inside = Rect(400, dyingDialogue.bottom + inflate - 8, 900, dyingDialogue.bottom + inflate + 32)
        assertTrue(abutsAnyInflated(listOf(dyingDialogue), inside, inflate))
    }

    @Test
    fun `no dying boxes never defers`() {
        assertFalse(abutsAnyInflated(emptyList(), Rect(0, 0, 10, 10), inflate))
    }

    @Test
    fun `fragment overlapping the dying rect itself defers`() {
        // Tail rects can overlap the rendered rect's padding band.
        val overlapping = Rect(880, 560, 1000, 600)
        assertTrue(abutsAnyInflated(listOf(dyingDialogue), overlapping, inflate))
    }
}
