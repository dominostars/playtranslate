package com.playtranslate

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.language.SourceLangId
import com.playtranslate.ui.CaptureResultGeometry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the import tool's per-surface preference scoping (the camera-scope
 * pattern's twin): the OCR token and overlay flavor inherit the GLOBAL
 * selection until set and pin independently afterwards; the slow-OCR
 * answered latch and presentation prefs are the import tool's own.
 */
@RunWith(RobolectricTestRunner::class)
class PrefsImportScopeTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val prefs = Prefs(ctx)

    private fun sp() =
        ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE)

    @Before fun setUp() { sp().edit().clear().commit() }
    @After fun tearDown() { sp().edit().clear().commit() }

    @Test fun importTokenIsRawAndClearable() {
        // The token getter is deliberately RAW (null = inherit): resolution
        // happens downstream in OcrModelManager.selectedBackend.
        assertNull(prefs.importOcrBackendToken(SourceLangId.JA))
        prefs.setImportOcrBackendToken(SourceLangId.JA, "paddle")
        assertEquals("paddle", prefs.importOcrBackendToken(SourceLangId.JA))
        // Independent of the camera's scope for the same language.
        assertNull(prefs.cameraOcrBackendToken(SourceLangId.JA))
        prefs.clearImportOcrBackendToken(SourceLangId.JA)
        assertNull(prefs.importOcrBackendToken(SourceLangId.JA))
    }

    @Test fun importOverlayModeInheritsGlobalUntilSet() {
        prefs.overlayMode = OverlayMode.FURIGANA
        assertEquals(OverlayMode.FURIGANA, prefs.importOverlayMode)
        // First import-side write pins it; the two then move independently.
        prefs.importOverlayMode = OverlayMode.TRANSLATION
        prefs.overlayMode = OverlayMode.FURIGANA
        assertEquals(OverlayMode.TRANSLATION, prefs.importOverlayMode)
    }

    @Test fun slowOcrLatchScopesPerSurfaceAndLanguage() {
        prefs.setImportSlowOcrPromptAnswered(SourceLangId.JA)
        assertTrue(prefs.importSlowOcrPromptAnswered(SourceLangId.JA))
        // Neither the camera's latch nor another language's import latch moves.
        assertFalse(prefs.cameraSlowOcrPromptAnswered(SourceLangId.JA))
        assertFalse(prefs.slowOcrPromptAnswered(SourceLangId.JA))
        assertFalse(prefs.importSlowOcrPromptAnswered(SourceLangId.EN))
    }

    @Test fun presentationPrefsDefaultBoxesOnNoPosture() {
        assertTrue(prefs.importBoxesEnabled)
        assertEquals(CaptureResultGeometry.NO_POSTURE, prefs.importPanelPosture, 0f)
        prefs.importBoxesEnabled = false
        prefs.importPanelPosture = CaptureResultGeometry.COLLAPSED_POSTURE
        assertFalse(prefs.importBoxesEnabled)
        assertTrue(CaptureResultGeometry.isCollapsedPosture(prefs.importPanelPosture))
        // The camera's pair is untouched.
        assertTrue(prefs.cameraBoxesEnabled)
        assertEquals(CaptureResultGeometry.NO_POSTURE, prefs.cameraPanelPosture, 0f)
    }
}
