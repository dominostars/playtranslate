package com.playtranslate.translation

import com.playtranslate.language.ChineseScriptVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChineseScriptConverterTest {

    @Test fun `Simplified needs no converter`() {
        assertNull(ChineseScriptConverter.forVariant(ChineseScriptVariant.SIMPLIFIED))
    }

    @Test fun `Taiwan converter localizes vocabulary, not just glyphs`() {
        val tw = ChineseScriptConverter.forVariant(ChineseScriptVariant.TRADITIONAL_TW)!!
        assertEquals("軟體", tw.convert("软件"))     // not the glyph-only 軟件
        assertEquals("滑鼠", tw.convert("鼠标"))
        assertEquals("網際網路", tw.convert("互联网"))
    }

    @Test fun `generic Traditional is glyph-level only`() {
        val t = ChineseScriptConverter.forVariant(ChineseScriptVariant.TRADITIONAL)!!
        assertEquals("軟件", t.convert("软件"))       // glyphs, no TW vocab localization
    }

    @Test fun `Hong Kong is glyph-level (no s2hkp phrase table exists upstream)`() {
        val hk = ChineseScriptConverter.forVariant(ChineseScriptVariant.TRADITIONAL_HK)!!
        assertEquals("軟件", hk.convert("软件"))       // mainland-style vocab, HK glyphs
        assertEquals("出租車", hk.convert("出租车"))   // not the colloquial 的士
    }

    @Test fun `already-Traditional input to a Traditional target is a no-op`() {
        // ZH_HANT-source passthrough into a Traditional target: input is already
        // Traditional, so no conversion (null) for any Traditional variant.
        assertNull(ChineseScriptConverter.forVariant(ChineseScriptVariant.TRADITIONAL, inputIsTraditional = true))
        assertNull(ChineseScriptConverter.forVariant(ChineseScriptVariant.TRADITIONAL_TW, inputIsTraditional = true))
        assertNull(ChineseScriptConverter.forVariant(ChineseScriptVariant.TRADITIONAL_HK, inputIsTraditional = true))
    }

    @Test fun `Traditional source to Simplified target converts (t2s)`() {
        // ZH_HANT source + Simplified target: the passthrough OCR is Traditional
        // and MUST be converted to Simplified (the reverse direction). Generic
        // glyph-level t2s — the source variant doesn't carry a region.
        val c = ChineseScriptConverter.forVariant(ChineseScriptVariant.SIMPLIFIED, inputIsTraditional = true)!!
        assertEquals("鼠标", c.convert("鼠標"))
        assertEquals("学生", c.convert("學生"))
        // Simplified source + Simplified target stays a no-op.
        assertNull(ChineseScriptConverter.forVariant(ChineseScriptVariant.SIMPLIFIED, inputIsTraditional = false))
        // Routed via the guarded forTarget entry point too.
        assertEquals(
            "时间",
            ChineseScriptConverter.forTarget("zh", ChineseScriptVariant.SIMPLIFIED, inputIsTraditional = true)!!
                .convert("時間"),
        )
    }

    @Test fun `forTarget guards on a Chinese target`() {
        // Chinese target → converts.
        assertEquals("軟體", ChineseScriptConverter.forTarget("zh", ChineseScriptVariant.TRADITIONAL_TW)!!.convert("软件"))
        // Non-Chinese target → null even with a (stale) Traditional variant.
        assertNull(ChineseScriptConverter.forTarget("fr", ChineseScriptVariant.TRADITIONAL_TW))
        // Chinese target but Simplified → null.
        assertNull(ChineseScriptConverter.forTarget("zh", ChineseScriptVariant.SIMPLIFIED))
    }

    @Test fun `blank and non-Chinese text pass through`() {
        val tw = ChineseScriptConverter.forVariant(ChineseScriptVariant.TRADITIONAL_TW)!!
        assertEquals("", tw.convert(""))
        assertEquals("Hello", tw.convert("Hello"))
    }
}
