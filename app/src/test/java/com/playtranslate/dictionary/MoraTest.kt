package com.playtranslate.dictionary

import com.playtranslate.dictionary.pitch.Mora
import com.playtranslate.dictionary.pitch.MoraSpan
import org.junit.Assert.assertEquals
import org.junit.Test

class MoraTest {

    private fun morae(reading: String): List<String> =
        Mora.segment(reading).map { reading.substring(it.start, it.end) }

    // ── Segmentation ────────────────────────────────────────────────────

    @Test
    fun `plain kana - one mora per char`() {
        assertEquals(listOf("ね", "こ"), morae("ねこ"))
        assertEquals(listOf("こ", "こ", "ろ"), morae("こころ"))
    }

    @Test
    fun `small kana merge into preceding mora`() {
        assertEquals(listOf("きょ", "う"), morae("きょう"))
        assertEquals(listOf("しゃ", "し", "ん"), morae("しゃしん"))
        assertEquals(listOf("ちょ", "う", "ちょ", "う"), morae("ちょうちょう"))
    }

    @Test
    fun `sokuon and n are standalone morae`() {
        assertEquals(listOf("が", "っ", "こ", "う"), morae("がっこう"))
        assertEquals(listOf("に", "ほ", "ん"), morae("にほん"))
    }

    @Test
    fun `long vowel mark and katakana small kana`() {
        // コンピューター: コ ン ピュ ー タ ー = 6 morae
        assertEquals(listOf("コ", "ン", "ピュ", "ー", "タ", "ー"), morae("コンピューター"))
        assertEquals(listOf("カ", "ー", "ド"), morae("カード"))
    }

    @Test
    fun `single mora word`() {
        assertEquals(listOf("ひ"), morae("ひ"))
        assertEquals(listOf("きょ"), morae("きょ"))
    }

    @Test
    fun `degenerate leading small kana does not crash`() {
        assertEquals(listOf(MoraSpan(0, 1)), Mora.segment("ょ"))
    }

    @Test
    fun `empty reading`() {
        assertEquals(emptyList<MoraSpan>(), Mora.segment(""))
    }

    // ── Contour ─────────────────────────────────────────────────────────

    @Test
    fun `heiban 0 - low first then high, ghost stays high`() {
        val c = Mora.contour(0, 3) // e.g. こころ? no — 鼻(はな)[0] is 2; use generic 3
        assertEquals(listOf(false, true, true), c.high)
        assertEquals(true, c.ghostHigh)
    }

    @Test
    fun `heiban single mora - low word, high ghost`() {
        val c = Mora.contour(0, 1) // 日(ひ)[0]: ひが = LH
        assertEquals(listOf(false), c.high)
        assertEquals(true, c.ghostHigh)
    }

    @Test
    fun `atamadaka 1 - high first mora only, ghost low`() {
        val c = Mora.contour(1, 2) // 雨(あめ)[1]: HL
        assertEquals(listOf(true, false), c.high)
        assertEquals(false, c.ghostHigh)
    }

    @Test
    fun `nakadaka - rise then drop mid-word`() {
        val c = Mora.contour(2, 3) // e.g. たまご? [2]: L H L
        assertEquals(listOf(false, true, false), c.high)
        assertEquals(false, c.ghostHigh)
    }

    @Test
    fun `odaka - high to word end, drop lands on ghost`() {
        val c = Mora.contour(3, 3) // 心(こころ)[3]: L H H, particle low
        assertEquals(listOf(false, true, true), c.high)
        assertEquals(false, c.ghostHigh)
    }

    @Test
    fun `heiban vs odaka differ only in ghost`() {
        val heiban = Mora.contour(0, 3)
        val odaka = Mora.contour(3, 3)
        assertEquals(heiban.high, odaka.high)
        assertEquals(true, heiban.ghostHigh)
        assertEquals(false, odaka.ghostHigh)
    }

    @Test
    fun `downstep beyond mora count clamps to odaka`() {
        val c = Mora.contour(7, 2)
        assertEquals(listOf(false, true), c.high)
        assertEquals(false, c.ghostHigh)
    }

    @Test
    fun `negative downstep clamps to heiban`() {
        val c = Mora.contour(-1, 2)
        assertEquals(listOf(false, true), c.high)
        assertEquals(true, c.ghostHigh)
    }

    @Test
    fun `zero morae`() {
        val c = Mora.contour(0, 0)
        assertEquals(emptyList<Boolean>(), c.high)
    }
}
