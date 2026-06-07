package com.playtranslate.language

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure unit tests for [targetSupportsVerticalText] — the gate that decides
 * whether a vertical OCR box stacks upright (CJK targets) or stays on the 90°
 * rotation path. No Android dependencies, so plain JUnit (no Robolectric).
 */
class TargetVerticalTextTest {

    @Test
    fun trueForVerticalScripts() {
        assertTrue(targetSupportsVerticalText("ja"))
        assertTrue(targetSupportsVerticalText("ko"))
        assertTrue(targetSupportsVerticalText("zh"))
        // Defensive zh-variant matches (ML Kit only ever emits bare "zh").
        assertTrue(targetSupportsVerticalText("zh-Hant"))
        assertTrue(targetSupportsVerticalText("zh-TW"))
        assertTrue(targetSupportsVerticalText("zh-hant"))
    }

    @Test
    fun caseInsensitive() {
        assertTrue(targetSupportsVerticalText("JA"))
        assertTrue(targetSupportsVerticalText("ZH"))
    }

    @Test
    fun falseForHorizontalScripts() {
        assertFalse(targetSupportsVerticalText("en"))
        assertFalse(targetSupportsVerticalText("es"))
        assertFalse(targetSupportsVerticalText("ru"))
        assertFalse(targetSupportsVerticalText("fr"))
        assertFalse(targetSupportsVerticalText(""))
    }
}
