package com.playtranslate

import android.graphics.Rect
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [ThrashDetector] — the language-independent misroute net. Fires only on
 * the flap signature (region revisiting recently-seen text with no stable
 * cycle in between); every legitimate flow must stay silent.
 */
@RunWith(RobolectricTestRunner::class)
class ThrashDetectorTest {

    private val r = Rect(100, 100, 500, 160)

    @Test
    fun oscillationBetweenTwoTexts_firesAtThirdRevisit() {
        val d = ThrashDetector()
        assertFalse(d.recordPlacement(r, "First line of dialogue", 0))       // seed
        assertFalse(d.recordPlacement(r, "Completely different text", 1000)) // progress
        assertFalse(d.recordPlacement(r, "First line of dialogue", 2000))    // revisit 1
        assertFalse(d.recordPlacement(r, "Completely different text", 3000)) // revisit 2
        assertTrue(
            "third revisit inside the window marks thrash",
            d.recordPlacement(r, "First line of dialogue", 4000),            // revisit 3
        )
    }

    @Test
    fun sameTextRediscoveryLoop_fires() {
        // The blind-language-pair flap: OCR reads nothing at our box, the box
        // is removed, the SAME original text is rediscovered — repeat.
        val d = ThrashDetector()
        val t = "こんにちは、旅の人よ。"
        assertFalse(d.recordPlacement(r, t, 0))
        assertFalse(d.recordPlacement(r, t, 2000))
        assertFalse(d.recordPlacement(r, t, 4000))
        assertTrue(d.recordPlacement(r, t, 6000))
    }

    @Test
    fun forwardProgress_neverFires() {
        val d = ThrashDetector()
        val lines = listOf(
            "The merchant greets you warmly",
            "She offers a strange amulet",
            "You decline politely and leave",
            "Outside, rain begins to fall",
            "A courier hands you a letter",
            "It bears the royal seal",
        )
        for ((i, line) in lines.withIndex()) {
            assertFalse("advancing dialogue must never thrash", d.recordPlacement(r, line, i * 1500L))
        }
    }

    @Test
    fun interleavedStability_forgivesTabFlipping() {
        // A player flipping between two menu tabs: each flip is a revisit,
        // but stable KEEP cycles land between flips and forgive the run.
        val d = ThrashDetector()
        assertFalse(d.recordPlacement(r, "Inventory: potions and herbs", 0))
        assertFalse(d.recordPlacement(r, "Equipment: swords and shields", 1000))
        assertFalse(d.recordPlacement(r, "Inventory: potions and herbs", 2000))  // revisit 1
        d.recordStability(r) // tab rests a cycle
        assertFalse(d.recordPlacement(r, "Equipment: swords and shields", 4000)) // revisit, count restarted
        assertFalse(d.recordPlacement(r, "Inventory: potions and herbs", 5000))  // revisit 2
        d.recordStability(r)
        assertFalse(d.recordPlacement(r, "Equipment: swords and shields", 7000)) // revisit 1 again
        assertFalse(d.recordPlacement(r, "Inventory: potions and herbs", 8000))  // revisit 2 — never 3
    }

    @Test
    fun staleRevisits_ageOutOfTheWindow() {
        val d = ThrashDetector()
        val t = "Slow-burning oscillation text"
        assertFalse(d.recordPlacement(r, t, 0))          // seed
        assertFalse(d.recordPlacement(r, t, 0))          // revisit @0
        assertFalse(d.recordPlacement(r, t, 30_000))     // revisit @30s
        assertFalse(
            "the @0 revisit has aged out — only two in-window",
            d.recordPlacement(r, t, 70_000),             // revisit @70s
        )
        assertTrue(
            "three revisits within 60s fire",
            d.recordPlacement(r, t, 75_000),             // @30s,@70s,@75s all in-window
        )
    }

    @Test
    fun boundsJitter_staysOneRegion() {
        // OCR bounds jitter cycle to cycle; the bucketed key must keep
        // rendezvousing on the same region.
        val d = ThrashDetector()
        val t = "Jittering but identical text"
        assertFalse(d.recordPlacement(Rect(100, 100, 500, 160), t, 0))
        assertFalse(d.recordPlacement(Rect(108, 92, 510, 168), t, 1000))
        assertFalse(d.recordPlacement(Rect(95, 105, 495, 155), t, 2000))
        assertTrue(d.recordPlacement(Rect(103, 98, 502, 161), t, 3000))
    }
}
