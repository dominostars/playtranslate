package com.playtranslate.language

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [isAccountedCompoundUnit] — the transparent-compound all-units gate
 * in [JapaneseEngine.memberWordsOf]. Accounted units are ≥2-char kanji
 * words (rendered as member rows) and ≥2-char katakana words (excused —
 * never rendered, but no longer vetoing the compound: ペース配分 offers
 * 配分). Everything else — single characters, hiragana, mixed scripts —
 * turns the whole offer off.
 */
class AccountedCompoundUnitTest {

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
}
