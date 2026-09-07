package com.playtranslate.overlay

import android.view.WindowInsets
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [OverlayHost.hiddenBarsMask]: the per-type bar mask the system-bar mirror
 * arms on a focusable overlay. Pins the semantics the mirror's SOURCE read
 * relies on — in particular that a type with no source at all reads as
 * hidden, which is why the read has to come from the display's own state
 * ([OverlayHost.hiddenSystemBarsOnDisplay]) and never from an overlay window
 * layered above the bars (2026-09-06: the accessibility-backend read came back
 * "both hidden" over every app and hid the status bar on capture). The
 * framework's WindowInsets runs as real code under Robolectric; nothing here
 * is shadowed.
 */
@RunWith(RobolectricTestRunner::class)
class OverlayHostBarMaskTest {

    private val status = WindowInsets.Type.statusBars()
    private val nav = WindowInsets.Type.navigationBars()

    @Test
    fun bothVisible_isEmptyMask() {
        val insets = WindowInsets.Builder()
            .setVisible(status, true)
            .setVisible(nav, true)
            .build()
        assertEquals(0, OverlayHost.hiddenBarsMask(insets))
    }

    @Test
    fun onlyStatusHidden_masksStatusAlone() {
        val insets = WindowInsets.Builder()
            .setVisible(status, false)
            .setVisible(nav, true)
            .build()
        assertEquals(status, OverlayHost.hiddenBarsMask(insets))
    }

    @Test
    fun onlyNavHidden_masksNavAlone() {
        val insets = WindowInsets.Builder()
            .setVisible(status, true)
            .setVisible(nav, false)
            .build()
        assertEquals(nav, OverlayHost.hiddenBarsMask(insets))
    }

    @Test
    fun absentSources_readAsBothHidden() {
        // No bar sources at all — what a window layered above the bars is
        // dispatched on Android 12+ — reads as both hidden.
        val insets = WindowInsets.Builder().build()
        assertEquals(status or nav, OverlayHost.hiddenBarsMask(insets))
    }
}
