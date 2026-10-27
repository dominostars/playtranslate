package com.playtranslate.dictionary.deinflect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [JapaneseDeinflector.occurrenceReading]: the kana reading of an inflected
 * surface as an occurrence of a dictionary word the sentence's analysis did
 * not choose, which a sentence card made for that word reads its text as.
 * The stem keeps the word's reading and the kana tail is the surface's,
 * accepted only when the reading deinflects back the way the surface does
 * under the entry's class. Part-of-speech tokens are the pack's
 * ([JapaneseDeinflector]'s prefixes).
 */
class OccurrenceReadingTest {

    private fun reading(surface: String, lemma: String, lemmaReading: String, vararg pos: String) =
        JapaneseDeinflector.occurrenceReading(
            surface, lemma, lemmaReading, JapaneseDeinflector.posFlags(pos.toList()),
        )

    @Test
    fun `an alternative's surface reads with the alternative's stem`() {
        assertEquals("ひけた", reading("弾けた", "弾く", "ひく", GODAN_KU))
        assertEquals("はじけた", reading("弾けた", "弾く", "はじく", GODAN_KU))
        assertEquals("ひけない", reading("弾けない", "弾く", "ひく", GODAN_KU))
        assertEquals("ひける", reading("弾ける", "弾く", "ひく", GODAN_KU))
    }

    @Test
    fun `stems of every shape keep their reading`() {
        assertEquals("おこなった", reading("行った", "行う", "おこなう", "Godan verb with 'u' ending"))
        assertEquals("とりだせた", reading("取り出せた", "取り出す", "とりだす", "Godan verb with 'su' ending"))
        assertEquals("たかかった", reading("高かった", "高い", "たかい", "I-adjective (keiyoushi)"))
        assertEquals("ひらけた", reading("開けた", "開く", "ひらく", "Godan verb with 'ku' ending"))
        assertEquals("べんきょうした", reading("勉強した", "勉強する", "べんきょうする", "Suru verb - included"))
    }

    @Test
    fun `a kanji whose reading changes as it conjugates is rejected by its class`() {
        // くた would pass as an ichidan form of くる; the kuru entry's class
        // accepts only きた, which the stem cannot give.
        assertNull(reading("来た", "来る", "くる", "Kuru verb - special class"))
        assertNull(reading("来ない", "来る", "くる", "Kuru verb - special class"))
    }

    @Test
    fun `an uninflected surface reads as the word and a kana surface as itself`() {
        assertEquals("かた", reading("方", "方", "かた", "noun (common) (futsuumeishi)"))
        assertEquals("いった", reading("いった", "行く", "いく", GODAN_KU))
    }

    @Test
    fun `no reading where the surface does not continue the word`() {
        // Kanji past the shared stem.
        assertNull(reading("弾き手", "弾く", "ひく", GODAN_KU))
        // No shared stem at all.
        assertNull(reading("読んだ", "弾く", "ひく", GODAN_KU))
        // A reading that does not end in the word's kana tail.
        assertNull(reading("弾けた", "弾く", "ひき", GODAN_KU))
        // No verb class: nothing licenses the conjugation.
        assertNull(reading("弾けた", "弾く", "ひく", "noun (common) (futsuumeishi)"))
    }

    private companion object {
        const val GODAN_KU = "Godan verb with 'ku' ending"
    }
}
