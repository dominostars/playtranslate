package com.playtranslate

import com.playtranslate.language.HintTextKind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [OverlayMode.labelRes] is the one place an overlay mode gets its name, for
 * every surface that offers or shows one. The auto-translate button's
 * long-press menu used a fixed Furigana label before it, so a Chinese source
 * read Furigana there.
 */
class OverlayModeLabelTest {

    @Test fun `translation reads Translation on every hint kind`() {
        for (hint in HintTextKind.entries) {
            assertEquals(
                hint.name,
                R.string.overlay_mode_option_translation,
                OverlayMode.TRANSLATION.labelRes(hint),
            )
        }
    }

    @Test fun `the hint mode reads Pinyin on a Pinyin language and Furigana on every other kind`() {
        assertEquals(
            mapOf(
                HintTextKind.NONE to R.string.overlay_mode_option_furigana,
                HintTextKind.FURIGANA to R.string.overlay_mode_option_furigana,
                HintTextKind.PINYIN to R.string.overlay_mode_option_pinyin,
                HintTextKind.HARAKAT to R.string.overlay_mode_option_furigana,
            ),
            HintTextKind.entries.associateWith { OverlayMode.FURIGANA.labelRes(it) },
        )
    }
}
