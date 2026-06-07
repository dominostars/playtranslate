package com.playtranslate.translation

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.playtranslate.language.ChineseScriptVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device validation that opencc4j loads its bundled OpenCC dictionaries on
 * Android (ART) and converts correctly for every Traditional variant.
 *
 * Guards the `data/dictionary/` packaging fix in app/build.gradle.kts: the
 * blanket HanLP exclude used to strip opencc4j's tables too, so first use of
 * any converter threw ExceptionInInitializerError (null resource stream in
 * STPhraseData.<clinit>). A regression there fails THIS test, not a user.
 */
@RunWith(AndroidJUnit4::class)
class ChineseScriptConverterInstrumentedTest {

    @Test fun simplified_needs_no_converter() {
        assertNull(ChineseScriptConverter.forVariant(ChineseScriptVariant.SIMPLIFIED))
    }

    @Test fun generic_traditional_converts_on_device() {
        val c = ChineseScriptConverter.forVariant(ChineseScriptVariant.TRADITIONAL)!!
        assertEquals("軟件", c.convert("软件"))   // glyph-level
    }

    @Test fun taiwan_localizes_vocabulary_on_device() {
        val c = ChineseScriptConverter.forVariant(ChineseScriptVariant.TRADITIONAL_TW)!!
        assertEquals("軟體", c.convert("软件"))
        assertEquals("滑鼠", c.convert("鼠标"))
        assertEquals("網際網路", c.convert("互联网"))
    }

    @Test fun hong_kong_converts_on_device() {
        // The exact path that crashed: ZhHkConverterUtil → ST dictionaries.
        val c = ChineseScriptConverter.forVariant(ChineseScriptVariant.TRADITIONAL_HK)!!
        assertEquals("軟件", c.convert("软件"))
        assertEquals("出租車", c.convert("出租车"))
    }

    @Test fun traditional_source_to_simplified_converts_on_device() {
        // ZH_HANT source + Simplified target (t2s) — exercises the TS* dicts,
        // a different resource set from the toTraditional paths above.
        val c = ChineseScriptConverter.forVariant(ChineseScriptVariant.SIMPLIFIED, inputIsTraditional = true)!!
        assertEquals("鼠标", c.convert("鼠標"))
        assertEquals("学生", c.convert("學生"))
    }

    @Test fun forTarget_guards_and_converts() {
        assertEquals(
            "軟體",
            ChineseScriptConverter.forTarget("zh", ChineseScriptVariant.TRADITIONAL_TW)!!.convert("软件"),
        )
        assertNull(ChineseScriptConverter.forTarget("fr", ChineseScriptVariant.TRADITIONAL_TW))
        assertNull(ChineseScriptConverter.forTarget("zh", ChineseScriptVariant.SIMPLIFIED))
    }

    @Test fun blank_and_non_chinese_pass_through() {
        val c = ChineseScriptConverter.forVariant(ChineseScriptVariant.TRADITIONAL_TW)!!
        assertEquals("", c.convert(""))
        assertEquals("Hello", c.convert("Hello"))
    }
}
