package com.playtranslate.ocr.registry

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.Prefs
import com.playtranslate.language.SourceLangId
import com.playtranslate.language.SourceLanguageProfiles
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins [OcrModelManager.deleteOcrPack]'s scoped-token sweep: deleting a pack
 * must clear every CAMERA- and IMPORT-scoped selection naming one of that
 * pack's backends (reverting them to inherit-global), because those tools
 * have no source-switch re-download choke point and a shippable-but-deleted
 * pack still resolves as selected — a kept token would leave the tool's gear
 * naming an engine while recognition silently runs the floor.
 */
@RunWith(RobolectricTestRunner::class)
class OcrDeleteSweepTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val prefs = Prefs(ctx)

    /** A language whose backends resolve the swept pack. */
    private val packKey = "paddle-rec-cyrillic"
    private val lang = SourceLangId.entries.first { id ->
        SourceLanguageProfiles[id].ocrBackends.any { packKey in it.packKeys }
    }
    private val token = SourceLanguageProfiles[lang].ocrBackends
        .first { packKey in it.packKeys }.selectionToken

    private fun sp() =
        ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE)

    // Cleared prefs leave the source on JA (the default), whose floor
    // selection carries no packs — so the delete's current-source refusal
    // check never trips over the swept language.
    @Before fun setUp() { sp().edit().clear().commit() }

    @After fun tearDown() { sp().edit().clear().commit() }

    @Test fun deleteClearsCameraAndImportTokensNamingThePack() {
        prefs.setCameraOcrBackendToken(lang, token)
        prefs.setImportOcrBackendToken(lang, token)
        assertTrue(OcrModelManager.deleteOcrPack(ctx, packKey))
        assertNull(prefs.cameraOcrBackendToken(lang))
        assertNull(prefs.importOcrBackendToken(lang))
    }

    @Test fun deleteLeavesTokensNamingOtherBackends() {
        val unrelated = "meiki"
        prefs.setCameraOcrBackendToken(SourceLangId.JA, unrelated)
        prefs.setImportOcrBackendToken(SourceLangId.JA, unrelated)
        assertTrue(OcrModelManager.deleteOcrPack(ctx, packKey))
        assertEquals(unrelated, prefs.cameraOcrBackendToken(SourceLangId.JA))
        assertEquals(unrelated, prefs.importOcrBackendToken(SourceLangId.JA))
    }
}
