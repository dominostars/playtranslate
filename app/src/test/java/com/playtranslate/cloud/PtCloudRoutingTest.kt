package com.playtranslate.cloud

import com.playtranslate.language.SourceLangId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The eligibility table, one row per test: the cloud reads a frame only
 * when it is switched on, has a usable key, supports the source, is not
 * cooling down, and the caller did not ask for the device. "any" columns
 * are iterated, so a row holds whatever the others say.
 */
class PtCloudRoutingTest {

    private val bools = listOf(false, true)

    private fun eligible(
        enabled: Boolean,
        key: Boolean,
        source: Boolean,
        cooling: Boolean,
        deviceOnly: Boolean,
    ) = PtCloudRouting.eligible(
        enabled = enabled, hasKey = key, sourceSupported = source, coolingDown = cooling, deviceOnly = deviceOnly,
    )

    @Test fun `switched off, the device reads whatever else holds`() {
        for (key in bools) for (source in bools) for (cooling in bools) for (deviceOnly in bools) {
            assertFalse(eligible(false, key, source, cooling, deviceOnly))
        }
    }

    @Test fun `no usable key, the device reads`() {
        for (source in bools) for (cooling in bools) for (deviceOnly in bools) {
            assertFalse(eligible(true, false, source, cooling, deviceOnly))
        }
    }

    @Test fun `a source the server does not read goes to the device`() {
        for (cooling in bools) for (deviceOnly in bools) {
            assertFalse(eligible(true, true, false, cooling, deviceOnly))
        }
    }

    @Test fun `cooling down, the device reads`() {
        for (deviceOnly in bools) assertFalse(eligible(true, true, true, true, deviceOnly))
    }

    @Test fun `a device-only caller (re-OCR, furigana hold, hold while live) gets the device`() {
        assertFalse(eligible(true, true, true, false, true))
    }

    @Test fun `everything allowing it, the cloud reads`() {
        assertTrue(eligible(true, true, true, false, false))
    }

    @Test fun `exactly one of the 32 cells routes to the cloud`() {
        var cloud = 0
        for (a in bools) for (b in bools) for (c in bools) for (d in bools) for (e in bools) {
            if (eligible(a, b, c, d, e)) cloud++
        }
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
