package com.playtranslate

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Vectors for [abutsAnyInflated] and [deferDyingBoxFragments] — the
 * dying-box fragment-deferral predicate and its step-9b filter
 * (dialogue-advance tail dance, 2026-07-10). Rects are drawn
 * from the real traces that motivated and constrain the rule:
 *
 *  - The dialogue tail: a message grows slightly on advance; OCR sees only
 *    the sliver outside the old (dying) box. Placing it strands a fragment
 *    box over the new message's end. Must DEFER.
 *  - The campfire-menu kickoff that killed the OLD suppression guard: its
 *    0.5W/1.5H inflation reached ~200px and starved three legitimate menu
 *    items near an unrelated dying prompt. The tight abutment must catch
 *    only the directly-adjacent item (6px gap) and pass the others.
 *  - The グラウス typewriter row (2026-07-12) that extended the dying set
 *    beyond pinhole removals: a box dying adjacency-STALE uncovers its
 *    region just the same, and the fresh row bordering it must defer
 *    instead of placing solo.
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

    // ── Stale-dying boxes feed the set too (グラウス trace 2026-07-12) ───
    //
    // c4: the partial typewriter box "北の、グラウス山にモン" died
    // adjacency-stale (its same-row tail 「いて」 tripped the last-line
    // probe), NOT via pinhole — and the freshly-revealed third row placed
    // as a stranded solo box because the dying set was pinhole-keyed.
    // Since 2026-07-16 runCycle feeds stale/cascade removals into
    // dyingRects; these vectors pin that composition with the trace's
    // real rects. (deferDyingBoxFragments itself is provenance-blind —
    // the contract lives in runCycle's dyingRects construction and in
    // abutsAnyInflated's kdoc.)

    /** c4's stale-dying partial box: text rect (521,812,1034,936) plus the
     *  ~14px render padding. */
    private val dyingStalePartial = Rect(507, 798, 1047, 949)

    @Test
    fun `third row below a stale-dying partial box defers`() {
        val thirdRow = FarGroup(
            text = "うしを盗ってくんだ・", bounds = Rect(559, 949, 1140, 1006),
            lineCount = 1,
        )
        val survivors = deferDyingBoxFragments(
            listOf(thirdRow), listOf(dyingStalePartial), identityCoords, inflate,
        )
        assertTrue("row bordering the stale-dying box must wait for the uncovering look", survivors.isEmpty())
    }

    @Test
    fun `menu item far from the stale-dying box still places`() {
        // The tight-abutment constraint holds regardless of removal
        // family: only text bordering the uncovered region defers.
        val unrelatedItem = FarGroup(
            text = "たたかう", bounds = Rect(477, 576, 882, 647),
            lineCount = 1,
        )
        val survivors = deferDyingBoxFragments(
            listOf(unrelatedItem), listOf(dyingStalePartial), identityCoords, inflate,
        )
        assertEquals(listOf(unrelatedItem), survivors)
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

    // ── deferDyingBoxFragments: paired exemption ─────────────────────────
    //
    // Vectors from the taxi-prompt trace (2026-07-10, pinhole-trace2.txt).
    // The game shows the NPC name twice — on the talk prompt AND on the
    // dialogue name plate — so the dying prompt content-matches the plate
    // and, before the fix, vacated the dying set: the message fragment the
    // prompt still occluded then placed broken (missing あるだろう?) and the
    // scene churned for five cycles.

    // Identity scale, zero crop → ocrToBitmap is a no-op, so every rect
    // below can stay in the trace's OCR-crop space.
    private val identityCoords = FrameCoordinates(
        bitmapWidth = 10_000, bitmapHeight = 10_000,
        viewWidth = 10_000, viewHeight = 10_000,
        cropLeft = 0, cropTop = 0,
    )

    /** c3's dying talk-prompt overlay: text rect (531,156,748,187) plus the
     *  14px render padding — the box that also content-matched. */
    private val dyingTaxiPrompt = Rect(517, 142, 762, 201)

    @Test
    fun `broken message abutting a content-matched dying box defers`() {
        val platePaired = FarGroup(
            text = "タクシー運転手", bounds = Rect(36, 109, 214, 135),
            lineCount = 1, paired = true,
        )
        val brokenMessage = FarGroup(
            text = "すぐそこにタコ焼き屋かあそこはオススメだね。",
            bounds = Rect(125, 166, 515, 252), lineCount = 2,
        )
        val survivors = deferDyingBoxFragments(
            listOf(platePaired, brokenMessage),
            listOf(dyingTaxiPrompt), identityCoords, inflate,
        )
        // The broken fragment waits for the forced look that sees the whole
        // uncovered sentence; the plate replacement places immediately.
        assertEquals(listOf(platePaired), survivors)
    }

    @Test
    fun `paired replacement abutting an unrelated dying box still places`() {
        // Conversation close (2026-07-09 trace, c14/c31/c234): the
        // plate→prompt replacement lands beside the dying message box
        // every time and must not lose its cycle to it.
        val pairedPrompt = FarGroup(
            text = "凄腕の女記者", bounds = Rect(1047, 802, 1232, 835),
            lineCount = 1, paired = true,
        )
        val dyingMessage = Rect(624, 842, 1292, 956)
        val survivors = deferDyingBoxFragments(
            listOf(pairedPrompt), listOf(dyingMessage), identityCoords, inflate,
        )
        assertEquals(listOf(pairedPrompt), survivors)
    }

    @Test
    fun `same rect without the paired flag defers - the exemption is the flag not geometry`() {
        val unpaired = FarGroup(
            text = "凄腕の女記者", bounds = Rect(1047, 802, 1232, 835),
            lineCount = 1,
        )
        val survivors = deferDyingBoxFragments(
            listOf(unpaired), listOf(Rect(624, 842, 1292, 956)), identityCoords, inflate,
        )
        assertTrue(survivors.isEmpty())
    }

    @Test
    fun `no dying boxes passes everything through untouched`() {
        val fragment = FarGroup(text = "x", bounds = Rect(0, 0, 10, 10), lineCount = 1)
        assertEquals(
            listOf(fragment),
            deferDyingBoxFragments(listOf(fragment), emptyList(), identityCoords, inflate),
        )
    }
}
