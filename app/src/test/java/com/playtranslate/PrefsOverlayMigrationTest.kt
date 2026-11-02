package com.playtranslate

import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
 * The capture's overlay mode ([Prefs.captureOverlayMode]): it inherits
 * auto-translate's until set, its legacy boxes switch migrates once
 * (off = None, on = inherit), a read coerces to what the source language
 * offers without rewriting, and auto-translate's own pref never reads a
 * mode live cannot paint.
 */
@RunWith(RobolectricTestRunner::class)
class PrefsOverlayMigrationTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private fun sp() =
        ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE)

    @Before fun clearPrefs() { sp().edit().clear().commit() }
    @After fun tearDown() { sp().edit().clear().commit() }

    private fun rawCapture(): String? = sp().getString(Prefs.KEY_CAPTURE_OVERLAY_MODE, null)

    @Test fun `the capture inherits auto-translate's mode until set, then pins`() {
        val prefs = Prefs(ctx)
        assertEquals(OverlayMode.TRANSLATION, prefs.captureOverlayMode)
        prefs.overlayMode = OverlayMode.FURIGANA
        assertEquals("unset: follows live", OverlayMode.FURIGANA, prefs.captureOverlayMode)
        assertNull(rawCapture())

        prefs.captureOverlayMode = OverlayMode.TRANSLATION
        prefs.overlayMode = OverlayMode.FURIGANA
        assertEquals("pinned: live moves alone", OverlayMode.TRANSLATION, prefs.captureOverlayMode)
        assertEquals(OverlayMode.FURIGANA, prefs.overlayMode)
    }

    @Test fun `a legacy boxes switch left off migrates to None and the key goes`() {
        sp().edit().putBoolean("capture_boxes_enabled", false).commit()

        val prefs = Prefs(ctx)

        assertEquals(OverlayMode.NONE, prefs.captureOverlayMode)
        assertFalse(sp().contains("capture_boxes_enabled"))
    }

    @Test fun `a legacy boxes switch left on migrates to inherit and the key goes`() {
        sp().edit().putBoolean("capture_boxes_enabled", true).commit()

        val prefs = Prefs(ctx)

        assertNull("nothing pinned", rawCapture())
        assertEquals(prefs.overlayMode, prefs.captureOverlayMode)
        assertFalse(sp().contains("capture_boxes_enabled"))
    }

    @Test fun `the migration runs once, so a later pick survives the next construction`() {
        sp().edit().putBoolean("capture_boxes_enabled", false).commit()
        Prefs(ctx).captureOverlayMode = OverlayMode.DEFINITIONS

        assertEquals(OverlayMode.DEFINITIONS, Prefs(ctx).captureOverlayMode)
    }

    @Test fun `auto-translate reads only the modes it paints`() {
        val prefs = Prefs(ctx)
        for ((stored, read) in mapOf(
            "TRANSLATION" to OverlayMode.TRANSLATION,
            "FURIGANA" to OverlayMode.FURIGANA,
            "DEFINITIONS" to OverlayMode.TRANSLATION,
            "NONE" to OverlayMode.TRANSLATION,
        )) {
            sp().edit().putString(Prefs.KEY_OVERLAY_MODE, stored).commit()
            assertEquals(stored, read, prefs.overlayMode)
        }
    }

    @Test fun `a capture read coerces the hint mode to translations on a language without a hint, without rewriting`() {
        val prefs = Prefs(ctx)
        prefs.sourceLang = "en"
        prefs.captureOverlayMode = OverlayMode.FURIGANA

        assertEquals(OverlayMode.TRANSLATION, prefs.overlayModeFor(OverlaySurface.CAPTURE))
        assertEquals("raw value kept", "FURIGANA", rawCapture())

        prefs.sourceLang = "ja"
        assertEquals(OverlayMode.FURIGANA, prefs.overlayModeFor(OverlaySurface.CAPTURE))
    }

    @Test fun `a capture read returns definitions and none as stored on every language`() {
        val prefs = Prefs(ctx)
        for (lang in listOf("en", "ja", "zh")) {
            prefs.sourceLang = lang
            prefs.captureOverlayMode = OverlayMode.DEFINITIONS
            assertEquals(lang, OverlayMode.DEFINITIONS, prefs.overlayModeFor(OverlaySurface.CAPTURE))
            prefs.captureOverlayMode = OverlayMode.NONE
            assertEquals(lang, OverlayMode.NONE, prefs.overlayModeFor(OverlaySurface.CAPTURE))
        }
    }

    @Test fun `the camera and import reads inherit live and coerce the same way`() {
        val prefs = Prefs(ctx)
        prefs.sourceLang = "ja"
        prefs.overlayMode = OverlayMode.FURIGANA
        assertEquals(OverlayMode.FURIGANA, prefs.overlayModeFor(OverlaySurface.CAMERA))
        assertEquals(OverlayMode.FURIGANA, prefs.overlayModeFor(OverlaySurface.IMPORT))
        prefs.sourceLang = "en"
        assertEquals(OverlayMode.TRANSLATION, prefs.overlayModeFor(OverlaySurface.CAMERA))
        assertEquals(OverlayMode.TRANSLATION, prefs.overlayModeFor(OverlaySurface.IMPORT))
    }

    @Test fun `the mode pill opens expanded until the user collapses it`() {
        val prefs = Prefs(ctx)
        assertFalse(prefs.captureOverlayPillCollapsed)
        prefs.captureOverlayPillCollapsed = true
        assertTrue(Prefs(ctx).captureOverlayPillCollapsed)
    }
}
