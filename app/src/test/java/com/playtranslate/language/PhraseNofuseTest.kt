package com.playtranslate.language

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Pins the [PhraseNofuse] asset loader against the real generated en list:
 *  entries load, comment/marker lines never leak in as phrases, and a
 *  language without an asset degrades to no exclusions. */
@RunWith(RobolectricTestRunner::class)
class PhraseNofuseTest {

    @Test fun `loads the en asset and ignores comment lines`() {
        val set = PhraseNofuse.forLang(ApplicationProvider.getApplicationContext(), SourceLangId.EN)
        // "do you" is a pure &lit stub in the pack — the flagship exclusion.
        assertTrue("do you" in set)
        assertTrue(set.none { it.startsWith("#") || it.isBlank() })
    }

    @Test fun `language without an asset yields no exclusions`() {
        val set = PhraseNofuse.forLang(ApplicationProvider.getApplicationContext(), SourceLangId.VI)
        assertTrue(set.isEmpty())
    }
}
