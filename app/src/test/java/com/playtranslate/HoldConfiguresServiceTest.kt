package com.playtranslate

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ServiceController

/**
 * A hold on a service no configurer has reached (the Quick Settings tile's
 * cold start, with the app never opened) configures the saved selection on
 * entry instead of failing its cycle: [OneShotManager] refuses an
 * unconfigured service, and its pill read "Screenshot failed" with no
 * screenshot attempted (Moto, 2026-10-09). A configured service is left as
 * it is, overrides included.
 */
@RunWith(RobolectricTestRunner::class)
class HoldConfiguresServiceTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private var service: ServiceController<CaptureService>? = null

    private fun clearPrefs() {
        ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Before fun setUp() {
        clearPrefs()
        MainActivity.isInForeground = false
        service = Robolectric.buildService(CaptureService::class.java).create()
    }

    @After fun tearDown() {
        service?.destroy()
        service = null
        clearPrefs()
    }

    @Test fun `an icon hold on a never-configured service configures the saved selection`() {
        Prefs(ctx).captureDisplayIds = setOf(0, 2)
        val svc = service!!.get()
        assertFalse("a fresh service starts unconfigured", svc.isConfigured)

        svc.holdStart(0)

        assertTrue(svc.isConfigured)
        assertEquals("the saved selection, not the icon's display alone", setOf(0, 2), svc.gameDisplayIds)
        assertEquals(0, svc.lastInteractedDisplayId)
        svc.holdEnd()
    }

    @Test fun `a hotkey hold on a never-configured service configures the saved selection`() {
        Prefs(ctx).captureDisplayIds = setOf(0)
        val svc = service!!.get()
        assertFalse(svc.isConfigured)

        svc.hotkeyHoldStart(OverlayMode.TRANSLATION)

        assertTrue(svc.isConfigured)
        assertEquals(setOf(0), svc.gameDisplayIds)
        svc.hotkeyHoldEnd()
    }

    @Test fun `a hold keeps an override applied before any configuration`() {
        val svc = service!!.get()
        val override = RegionEntry("test", 0.25f, 0.75f)
        svc.configureOverride(0, override)
        assertFalse(svc.isConfigured)

        svc.holdStart(0)

        assertTrue(svc.isConfigured)
        assertEquals("the cold configure carried the override across", override, svc.activeRegionForDisplay(0))
        svc.holdEnd()
    }

    @Test fun `a hold on a configured service keeps its region override`() {
        val svc = service!!.get()
        svc.configureSaved(displayIds = setOf(0))
        val override = RegionEntry("test", 0.25f, 0.75f)
        svc.configureOverride(0, override)

        svc.holdStart(0)

        assertEquals("no reconfigure cleared the override", override, svc.activeRegionForDisplay(0))
        svc.holdEnd()
    }
}
