package com.playtranslate.language

import com.playtranslate.dictionary.JaCategory
import com.playtranslate.dictionary.JaToken
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [isAccountedCompoundUnit] and [isGrammarRun] — the
 * transparent-compound all-units gate in [JapaneseEngine.memberWordsOf].
 * Accounted units are ≥2-char kanji words (rendered as member rows),
 * ≥2-char katakana words (excused — never rendered, but no longer vetoing
 * the compound: ペース配分 offers 配分), and grammar units — particle /
 * auxiliary runs by the tokenizer's category (excused: 瞬く間に, JMdict
 * adv not exp, offers 瞬く間 despite its に). Everything else — single
 * characters, hiragana CONTENT, mixed scripts — turns the whole offer off.
 */
class AccountedCompoundUnitTest {

    private fun tok(s: String, c: JaCategory) = JaToken(
        surface = s, begin = 0, end = s.length, category = c,
        dictionaryForm = s, normalizedForm = s, reading = null, isOov = false,
    )

    @Test fun `two-char kanji words are accounted`() {
        assertTrue(isAccountedCompoundUnit("配分"))
        assertTrue(isAccountedCompoundUnit("放送"))
        assertTrue(isAccountedCompoundUnit("番組"))
    }

    @Test fun `kanji plus okurigana is accounted`() {
        assertTrue(isAccountedCompoundUnit("向け"))
    }

    @Test fun `katakana words are excused, including the prolonged mark`() {
        assertTrue(isAccountedCompoundUnit("ペース"))
        assertTrue(isAccountedCompoundUnit("ボタン"))
    }

    @Test fun `single characters are not accounted, either script`() {
        assertFalse(isAccountedCompoundUnit("館"))
        assertFalse(isAccountedCompoundUnit("気"))
        assertFalse(isAccountedCompoundUnit("ペ"))
    }

    @Test fun `hiragana units stay unaccounted — grammar noise`() {
        assertFalse(isAccountedCompoundUnit("かも"))
        assertFalse(isAccountedCompoundUnit("しれ"))
        assertFalse(isAccountedCompoundUnit("ない"))
    }

    @Test fun `mixed katakana-hiragana units stay unaccounted`() {
        assertFalse(isAccountedCompoundUnit("サボり"))
    }

    @Test fun `mark-only strings are not katakana words`() {
        assertFalse(isAccountedCompoundUnit("ーー"))
    }

    @Test fun `latin and empty stay unaccounted`() {
        assertFalse(isAccountedCompoundUnit("AB"))
        assertFalse(isAccountedCompoundUnit(""))
    }

    @Test fun `grammar units are excused whatever their shape`() {
        // 瞬く間に's に, 少しも's も, a fused particle run: not words the
        // reader is owed, so none may veto the compound.
        assertTrue(isAccountedCompoundUnit("に", grammar = true))
        assertTrue(isAccountedCompoundUnit("も", grammar = true))
        assertTrue(isAccountedCompoundUnit("かも", grammar = true))
    }

    @Test fun `the grammar verdict is the tokenizer's, never the shape's`() {
        // Shape alone still vetoes: a lone に by shape is a single hiragana,
        // and hiragana CONTENT (つけ in つけ込む) stays unaccounted.
        assertFalse(isAccountedCompoundUnit("に"))
        assertFalse(isAccountedCompoundUnit("つけ", grammar = false))
    }

    @Test fun `a grammar run is a range of particles or auxiliaries only`() {
        val tokens = listOf(tok("瞬く", JaCategory.VERB), tok("間", JaCategory.NOUN), tok("に", JaCategory.PARTICLE))
        assertTrue(isGrammarRun(tokens, 2, 3))
        assertFalse(isGrammarRun(tokens, 0, 2))
        assertFalse(isGrammarRun(tokens, 1, 3))
    }

    @Test fun `a fused particle run is grammar, a verb with folded glue is not`() {
        val kamo = listOf(
            tok("か", JaCategory.PARTICLE), tok("も", JaCategory.PARTICLE),
            tok("しれ", JaCategory.VERB), tok("ない", JaCategory.AUX),
        )
        assertTrue(isGrammarRun(kamo, 0, 2))
        assertFalse(isGrammarRun(kamo, 2, 4))
    }

    @Test fun `an empty range is not grammar`() {
        assertFalse(isGrammarRun(emptyList(), 0, 0))
    }
}
