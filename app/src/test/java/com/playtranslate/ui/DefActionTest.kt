package com.playtranslate.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A dictionary's link to another word as an action of its row
 * ([DefAction.reference]): it reads back as the word and the reading it was
 * made from, whatever they are written with; it is safe where the page
 * writes it (an attribute, and the selector a cursor is drawn by); two links
 * to one word in a row are two actions; and no other action reads as one.
 */
class DefActionTest {

    private val words = listOf(
        "白ける" to "しらける",
        "ワンウェイ瓶" to "ワンウェイびん",
        "ice cream" to null,
        "a:b" to "c:d",
        "\"quoted\" 'single' \\ ] <tag> & %41" to "100%",
        "𠮟る" to "しかる",
        "naïve" to null,
        "~_-." to null,
    )

    @Test fun `a reference reads back as the word and the reading it names`() {
        for ((term, reading) in words) {
            val act = DefAction.reference(3, term, reading)
            assertEquals(act, DefAction.Reference(term, reading), DefAction.referenceOf(act))
        }
    }

    // Mutation: the term goes into the action as it is written.
    @Test fun `a reference holds nothing that ends an attribute, a selector or one of its own parts`() {
        val safe = Regex("^ref:\\d+:[A-Za-z0-9%._~-]+(:[A-Za-z0-9%._~-]+)?$")
        for ((term, reading) in words) {
            val act = DefAction.reference(0, term, reading)
            assertTrue(act, safe.matches(act))
        }
    }

    @Test fun `an empty reading is no reading`() {
        assertEquals(DefAction.reference(0, "猫", null), DefAction.reference(0, "猫", ""))
        assertEquals(DefAction.Reference("猫", null), DefAction.referenceOf(DefAction.reference(0, "猫", "")))
    }

    // Mutation: the number is left out of the action.
    @Test fun `two links to one word in a row are two actions for the same word`() {
        val first = DefAction.reference(0, "猫", "ねこ")
        val second = DefAction.reference(1, "猫", "ねこ")
        assertNotEquals(first, second)
        assertEquals(DefAction.referenceOf(first), DefAction.referenceOf(second))
    }

    @Test fun `no other action reads as a reference, and a reference as no other`() {
        val others = listOf(
            DefAction.OPEN, DefAction.SPEAK, DefAction.TRAILING, DefAction.hold(DefAction.TRAILING),
            DefAction.MORE, DefAction.EXPLAIN, DefAction.EXPLAIN_CLOSE, DefAction.EXPLAIN_RETRY,
            DefAction.TARGET, DefAction.AUDIO, DefAction.SOURCE, DefAction.REMOVE,
            DefAction.removeItem(2), DefAction.dictionary(1), DefAction.OWN_DICTIONARY, "",
        )
        for (other in others) assertNull(other, DefAction.referenceOf(other))
        val ref = DefAction.reference(1, "猫", null)
        assertNull(DefAction.dictionaryIndex(ref))
        assertNull(DefAction.removedItem(ref))
        assertNull(DefAction.fallback(ref))
    }

    @Test fun `a reference that does not read back whole names nothing`() {
        val bad = listOf(
            "ref", "ref:", "ref:0", "ref:0:", "ref:x:%E7%8C%AB", "ref:0:%E7%8C", "ref:0:%ZZ",
            "ref:0:%e7%8c%ab", "ref:0:猫", "ref:0:a b", "ref:0:%20", "ref:0:a:", "ref:0:a:b:c",
        )
        for (act in bad) assertNull(act, DefAction.referenceOf(act))
        assertFalse(DefAction.reference(0, "猫", null).contains("猫"))
    }
}
