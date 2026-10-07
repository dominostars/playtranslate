package com.playtranslate.ocr.registry

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.OcrTokenScope
import com.playtranslate.Prefs
import com.playtranslate.language.OcrBackend
import com.playtranslate.language.SourceLangId
import com.playtranslate.ocr.core.OcrCapabilities
import com.playtranslate.ocr.core.OcrEngine
import com.playtranslate.ocr.core.OcrImage
import com.playtranslate.ocr.core.OcrOrientationSupport
import com.playtranslate.ocr.core.RecognizedRegion
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The engine that runs is the one selected under the EXACT source language.
 * Traditional Chinese (ZH_HANT) shares its translation code "zh" with Simplified
 * (ZH); the engine lookup once rebuilt the language from that code and read
 * Simplified's selection for a Traditional user, so a Traditional user's OCR
 * change never took effect (2026-10-06). Every language with two Paddle tiers
 * is checked, not only the Chinese pair, so the next shared code cannot regress
 * the same way.
 */
@RunWith(RobolectricTestRunner::class)
class OcrEngineSelectionByLanguageTest {

    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val prefs = Prefs(ctx)

    private object FakeEngine : OcrEngine {
        override val capabilities = OcrCapabilities(
            orientation = OcrOrientationSupport.HORIZONTAL_ONLY,
            emitsCharBoxes = false,
            emitsElementBoxes = false,
            wholeRegionInput = false,
            threadSafe = true,
            selfPreprocesses = true,
            emitsSubLineBoxes = false,
        )
        override suspend fun recognize(image: OcrImage): List<RecognizedRegion> = emptyList()
        override fun close() {}
    }

    @Before fun setUp() {
        OcrModelManager.appContext = ctx
        for (id in SourceLangId.entries) {
            prefs.clearOcrBackendToken(id)
            prefs.clearCameraOcrBackendToken(id)
            prefs.clearImportOcrBackendToken(id)
        }
    }

    @After fun tearDown() {
        OcrModelManager.appContext = null
    }

    /** Languages whose deliverable backends include both Paddle tiers, so a
     *  per-language selection can be told apart from every other language's. */
    private fun languagesWithTiers(): List<SourceLangId> =
        SourceLangId.entries.filter { id ->
            OcrModelManager.availableBackends(ctx, id).any { (it as? OcrBackend.Paddle)?.fast == true }
        }

    private fun resolvedToken(id: SourceLangId, scope: OcrTokenScope = OcrTokenScope.GLOBAL): String? =
        OcrModelManager.engineForSelected(
            id, scope,
            isInstalled = { true },
            build = { FakeEngine },
        )?.first?.selectionToken

    @Test fun `each language runs its own selection, Chinese variants included`() {
        val langs = languagesWithTiers()
        assertTrue("the Chinese pair must be in the checked set: $langs",
            langs.containsAll(listOf(SourceLangId.ZH, SourceLangId.ZH_HANT)))
        for (id in langs) {
            // Every other language selects the accurate tier; only [id] the fast one.
            for (other in langs) prefs.setOcrBackendToken(other, "paddle")
            prefs.setOcrBackendToken(id, "paddle-fast")
            assertEquals("engine for ${id.code}", "paddle-fast", resolvedToken(id))
            for (other in langs) if (other != id) {
                assertEquals("engine for ${other.code} while ${id.code} differs", "paddle", resolvedToken(other))
            }
        }
    }

    @Test fun `a tool's own selection inherits the global one until set, per language`() {
        prefs.setOcrBackendToken(SourceLangId.ZH, "paddle")
        prefs.setOcrBackendToken(SourceLangId.ZH_HANT, "paddle-fast")
        assertEquals("paddle-fast", resolvedToken(SourceLangId.ZH_HANT, OcrTokenScope.CAMERA))
        assertEquals("paddle-fast", resolvedToken(SourceLangId.ZH_HANT, OcrTokenScope.IMPORT))
        prefs.setCameraOcrBackendToken(SourceLangId.ZH_HANT, "paddle")
        assertEquals("paddle", resolvedToken(SourceLangId.ZH_HANT, OcrTokenScope.CAMERA))
        assertEquals("paddle-fast", resolvedToken(SourceLangId.ZH_HANT, OcrTokenScope.IMPORT))
        assertEquals("paddle-fast", resolvedToken(SourceLangId.ZH_HANT))
    }

    @Test fun `an unset selection runs the installed default with nothing written`() {
        // Simplified was set up through the picker; Traditional was reached by
        // the icon's toggle, which writes no token. Both run the default.
        prefs.setOcrBackendToken(SourceLangId.ZH, "paddle")
        assertEquals("paddle", resolvedToken(SourceLangId.ZH_HANT))
        assertEquals(null, prefs.ocrBackendToken(SourceLangId.ZH_HANT))
    }
}
