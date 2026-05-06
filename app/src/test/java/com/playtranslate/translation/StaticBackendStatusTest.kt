package com.playtranslate.translation

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Confirms the always-static `status` values produced by [LingvaBackend]
 * and [MlKitBackend]. These backends never run async fetches; their
 * `status` is a constant.
 */
class StaticBackendStatusTest {

    @Test fun `Lingva status is the no-API-key info regardless of enabled state`() {
        val onBackend  = LingvaBackend(enabledProvider = { true })
        val offBackend = LingvaBackend(enabledProvider = { false })

        val expected = BackendStatus.Info("No API key required")
        assertEquals(expected, onBackend.status)
        assertEquals(expected, offBackend.status)
    }

    @Test fun `MlKit status is the on-device info`() {
        val backend = MlKitBackend()
        assertEquals(BackendStatus.Info("On-device"), backend.status)
    }
}
