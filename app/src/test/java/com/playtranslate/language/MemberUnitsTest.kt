package com.playtranslate.language

import com.playtranslate.dictionary.DictionaryManager.Companion.ReglobSpan
import com.playtranslate.dictionary.JaCategory
import com.playtranslate.dictionary.JaToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [memberUnits] — the class gate of [JapaneseEngine.memberWordsOf] on
 * hand-built tokens and re-glob spans. The load-bearing distinction is
 * STANDALONE grammar (a particle unit, which marks a phrase) versus glue
 * FOLDED into a verb (inflection of one word, which does not): the
 * adversarial review found the phrase test reading raw tokens, so any
 * auxiliary anywhere relaxed the completeness guard and 思いがけない
 * rendered 思う while silently dropping がけない.
 */
class MemberUnitsTest {

    private fun tok(s: String, c: JaCategory, dict: String = s) = JaToken(
        surface = s, begin = 0, end = s.length, category = c,
        dictionaryForm = dict, normalizedForm = dict, reading = null, isOov = false,
    )

    /** A content token with [glue] folded in, the way the re-glob emits a verb. */
    private fun word(start: Int, surface: String, lookupForm: String, glue: Int = 0) =
        ReglobSpan(start, 1 + glue, surface, lookupForm, null, emptyList(), isPhrase = false)

    private fun phrase(start: Int, count: Int, surface: String) =
        ReglobSpan(start, count, surface, surface, null, emptyList(), isPhrase = true)

    @Test fun `a standalone particle marks a phrase and the loose gate keeps a one-char member`() {
        val tokens = listOf(tok("瞬く", JaCategory.VERB), tok("間", JaCategory.NOUN), tok("に", JaCategory.PARTICLE))
        val units = memberUnits(tokens, listOf(word(0, "瞬く", "瞬く"), word(1, "間", "間")), expressionClass = false)
        assertEquals(listOf("瞬く", "間", "に"), units.map { it.surface })
        assertEquals(listOf(false, false, true), units.map { it.grammar })
    }

    @Test fun `glue folded into a verb is not phrase structure — 思いがけない stays whole`() {
        // 思い/がけ/ない: がけ folds ない. No standalone grammar unit, and
        // がけない is hiragana content the compound gate cannot account for,
        // so nothing renders rather than 思う alone.
        val tokens = listOf(tok("思い", JaCategory.VERB, "思う"), tok("がけ", JaCategory.VERB, "がける"), tok("ない", JaCategory.AUX))
        val spans = listOf(word(0, "思い", "思う"), word(1, "がけない", "がける", glue = 1))
        assertTrue(memberUnits(tokens, spans, expressionClass = false).isEmpty())
    }

    @Test fun `glue folded into a verb does not license a one-char member — 分からず屋 stays whole`() {
        val tokens = listOf(tok("分から", JaCategory.VERB, "分かる"), tok("ず", JaCategory.AUX), tok("屋", JaCategory.NOUN))
        val spans = listOf(word(0, "分からず", "分かる", glue = 1), word(2, "屋", "屋"))
        assertTrue(memberUnits(tokens, spans, expressionClass = false).isEmpty())
        // 見た目 is the same shape.
        val mita = listOf(tok("見", JaCategory.VERB, "見る"), tok("た", JaCategory.AUX), tok("目", JaCategory.NOUN))
        assertTrue(memberUnits(mita, listOf(word(0, "見た", "見る", glue = 1), word(2, "目", "目")), expressionClass = false).isEmpty())
    }

    @Test fun `a compound of accounted units decomposes with or without folded glue`() {
        val tokens = listOf(tok("連れ", JaCategory.VERB, "連れる"), tok("て", JaCategory.PARTICLE), tok("行く", JaCategory.VERB))
        val spans = listOf(word(0, "連れて", "連れる", glue = 1), word(2, "行く", "行く"))
        assertEquals(listOf("連れる", "行く"), memberUnits(tokens, spans, expressionClass = false).map { it.lookupForm })
    }

    @Test fun `the expression verdict keeps the loose gate without any glue`() {
        val tokens = listOf(tok("手当たり", JaCategory.NOUN), tok("次第", JaCategory.NOUN))
        val spans = listOf(word(0, "手当たり", "手当たり"), word(1, "次第", "次第"))
        assertEquals(2, memberUnits(tokens, spans, expressionClass = true).size)
    }

    @Test fun `a fused particle run is a standalone grammar unit`() {
        // 今にも: 今に fuses (a JMdict headword), も stands alone — a phrase.
        val tokens = listOf(tok("今", JaCategory.NOUN), tok("に", JaCategory.PARTICLE), tok("も", JaCategory.PARTICLE))
        val units = memberUnits(tokens, listOf(phrase(0, 2, "今に")), expressionClass = false)
        assertEquals(listOf("今に", "も"), units.map { it.surface })
        assertEquals(listOf(false, true), units.map { it.grammar })
        // And a run that is ALL glue (か+も) is grammar even when fused.
        val kamo = listOf(tok("か", JaCategory.PARTICLE), tok("も", JaCategory.PARTICLE), tok("知れ", JaCategory.VERB, "知れる"))
        assertTrue(memberUnits(kamo, listOf(phrase(0, 2, "かも"), word(2, "知れ", "知れる")), expressionClass = false)[0].grammar)
    }

    @Test fun `uncovered tokens keep headword order among spans`() {
        val tokens = listOf(tok("気", JaCategory.NOUN), tok("の", JaCategory.PARTICLE), tok("向く", JaCategory.VERB))
        val units = memberUnits(tokens, listOf(word(2, "向く", "向く")), expressionClass = false)
        assertEquals(listOf("気", "の", "向く"), units.map { it.surface })
    }
}
