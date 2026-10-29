package com.playtranslate.cloud

import com.playtranslate.language.SourceLangId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The eligibility table, one row per test: the cloud reads a frame only
 * when it is switched on, has a usable key, its account is entitled (its
 * trial or membership not ended), it supports the source, is not cooling
 * down, the caller did not ask for the device, and the device has a
 * network. "any" columns are iterated, so a row holds whatever the others
 * say. The cells with an entitled account and no key cannot happen in
 * production (no key has no standing); they read false with the rest.
 */
class PtCloudRoutingTest {

    private val bools = listOf(false, true)

    private fun eligible(
        enabled: Boolean,
        key: Boolean,
        entitled: Boolean,
        source: Boolean,
        cooling: Boolean,
        deviceOnly: Boolean,
        online: Boolean,
    ) = PtCloudRouting.eligible(
        enabled = enabled, hasKey = key, entitled = entitled, sourceSupported = source, coolingDown = cooling,
        deviceOnly = deviceOnly, online = online,
    )

    @Test fun `switched off, the device reads whatever else holds`() {
        for (key in bools) for (entitled in bools) for (source in bools) for (cooling in bools)
            for (deviceOnly in bools) for (online in bools) {
                assertFalse(eligible(false, key, entitled, source, cooling, deviceOnly, online))
            }
    }

    @Test fun `no usable key, the device reads`() {
        for (entitled in bools) for (source in bools) for (cooling in bools) for (deviceOnly in bools) for (online in bools) {
            assertFalse(eligible(true, false, entitled, source, cooling, deviceOnly, online))
        }
    }

    @Test fun `an account whose trial or membership ended, the device reads`() {
        for (source in bools) for (cooling in bools) for (deviceOnly in bools) for (online in bools) {
            assertFalse(eligible(true, true, false, source, cooling, deviceOnly, online))
        }
    }

    @Test fun `a source the server does not read goes to the device`() {
        for (cooling in bools) for (deviceOnly in bools) for (online in bools) {
            assertFalse(eligible(true, true, true, false, cooling, deviceOnly, online))
        }
    }

    @Test fun `cooling down, the device reads`() {
        for (deviceOnly in bools) for (online in bools) assertFalse(eligible(true, true, true, true, true, deviceOnly, online))
    }

    @Test fun `a device-only caller (a re-OCR, a frozen camera or import frame after an engine pick) gets the device`() {
        for (online in bools) assertFalse(eligible(true, true, true, true, false, true, online))
    }

    @Test fun `with no network, the device reads`() {
        assertFalse(eligible(true, true, true, true, false, false, online = false))
    }

    @Test fun `everything allowing it, the cloud reads`() {
        assertTrue(eligible(true, true, true, true, false, false, online = true))
    }

    @Test fun `exactly one of the 128 cells routes to the cloud`() {
        var cloud = 0
        var cells = 0
        for (a in bools) for (b in bools) for (c in bools) for (d in bools) for (e in bools) for (f in bools) for (g in bools) {
            cells++
            if (eligible(a, b, c, d, e, f, g)) cloud++
        }
        assertEquals(128, cells)
        assertEquals(1, cloud)
    }

    @Test fun `the supported codes are the brief's 26, which today are every source language`() {
        assertEquals(26, PtCloudRouting.SUPPORTED_SOURCE_CODES.size)
        // A new source language fails here until the server's list is
        // checked for it.
        assertEquals(SourceLangId.entries.map { it.code }.toSet(), PtCloudRouting.SUPPORTED_SOURCE_CODES)
        for (id in SourceLangId.entries) assertTrue(id.name, PtCloudRouting.isSupportedSource(id))
        // The wire spelling, case included.
        assertTrue("zh-Hant" in PtCloudRouting.SUPPORTED_SOURCE_CODES)
        assertFalse("zh_Hant" in PtCloudRouting.SUPPORTED_SOURCE_CODES)
    }
}
