package com.playtranslate

import com.playtranslate.OverlayMode.DEFINITIONS
import com.playtranslate.OverlayMode.FURIGANA
import com.playtranslate.OverlayMode.NONE
import com.playtranslate.OverlayMode.TRANSLATION
import com.playtranslate.language.HintTextKind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [OverlayMode.offered] is the one statement of which modes each surface
 * paints, and [OverlayMode.coerce] of what an unoffered stored value reads
 * as. Every surface × hint cell, so a new mode or surface has to say where
 * it is offered.
 */
class OverlayModeOfferedTest {

    private val hintless = listOf(HintTextKind.NONE)
    private val hinted = listOf(HintTextKind.FURIGANA, HintTextKind.PINYIN, HintTextKind.HARAKAT)

    @Test fun `auto-translate, the camera and the import tool offer translations, plus the hint on a hint language`() {
        for (surface in listOf(OverlaySurface.LIVE, OverlaySurface.CAMERA, OverlaySurface.IMPORT)) {
            for (hint in hintless) {
                assertEquals("$surface $hint", listOf(TRANSLATION), OverlayMode.offered(surface, hint))
            }
            for (hint in hinted) {
                assertEquals("$surface $hint", listOf(TRANSLATION, FURIGANA), OverlayMode.offered(surface, hint))
            }
        }
    }

    @Test fun `a capture offers every mode, the hint only on a hint language, in display order`() {
        for (hint in hintless) {
            assertEquals(
                hint.name,
                listOf(TRANSLATION, DEFINITIONS, NONE),
                OverlayMode.offered(OverlaySurface.CAPTURE, hint),
            )
        }
        for (hint in hinted) {
            assertEquals(
                hint.name,
                listOf(TRANSLATION, FURIGANA, DEFINITIONS, NONE),
                OverlayMode.offered(OverlaySurface.CAPTURE, hint),
            )
        }
    }

    @Test fun `an offered value reads as itself and an unoffered one as translations`() {
        for (surface in OverlaySurface.entries) {
            for (hint in HintTextKind.entries) {
                val offered = OverlayMode.offered(surface, hint)
                for (mode in OverlayMode.entries) {
                    val expected = if (mode in offered) mode else TRANSLATION
                    assertEquals("$surface $hint $mode", expected, OverlayMode.coerce(mode, surface, hint))
                }
            }
        }
    }

    @Test fun `translations is offered everywhere and first`() {
        for (surface in OverlaySurface.entries) {
            for (hint in HintTextKind.entries) {
                assertEquals("$surface $hint", TRANSLATION, OverlayMode.offered(surface, hint).first())
            }
        }
    }

    @Test fun `an unknown stored name reads as translations`() {
        assertEquals(TRANSLATION, OverlayMode.fromStorageName(null))
        assertEquals(TRANSLATION, OverlayMode.fromStorageName("READINGS"))
        for (mode in OverlayMode.entries) {
            assertEquals(mode, OverlayMode.fromStorageName(mode.name))
        }
    }
}
