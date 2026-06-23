package com.playtranslate

import com.playtranslate.audio.WikimediaLangCodes
import com.playtranslate.language.SourceLangId
import org.junit.Assert.assertEquals
import org.junit.Test

class WikimediaLangCodesTest {

    @Test fun wikiCode_normalizes_chinese_and_norwegian() {
        assertEquals("zh", WikimediaLangCodes.wikiCode(SourceLangId.ZH))
        assertEquals("zh", WikimediaLangCodes.wikiCode(SourceLangId.ZH_HANT))
        assertEquals("nb", WikimediaLangCodes.wikiCode(SourceLangId.NO))
    }

    @Test fun wikiCode_defaults_to_app_code() {
        assertEquals("de", WikimediaLangCodes.wikiCode(SourceLangId.DE))
        assertEquals("ru", WikimediaLangCodes.wikiCode(SourceLangId.RU))
    }

    @Test fun linguaLibreQid_maps_known_languages() {
        assertEquals("Q150", WikimediaLangCodes.linguaLibreQid(SourceLangId.FR))
        assertEquals("Q5287", WikimediaLangCodes.linguaLibreQid(SourceLangId.JA))
        assertEquals("Q7737", WikimediaLangCodes.linguaLibreQid(SourceLangId.RU))
    }
}
