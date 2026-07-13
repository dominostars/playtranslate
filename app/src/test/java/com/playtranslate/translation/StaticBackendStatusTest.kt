package com.playtranslate.translation

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Confirms the always-static `status` values produced by [LingvaBackend]
 * and [MlKitBackend]. These backends never run async fetches; their
 * `status` is a constant.
 */
class StaticBackendStatusTest {

    @Test fun `Lingva status is the no-account requirement regardless of enabled state`() {
        val onBackend  = LingvaBackend(enabledProvider = { true })
        val offBackend = LingvaBackend(enabledProvider = { false })

        // The requirement, not the sentence — Lingva has no Context to word it
        // with, so the renderer resolves AccountRequirement.labelRes.
        val expected = BackendStatus.Account(AccountRequirement.NONE)
        assertEquals(expected, onBackend.status)
        assertEquals(expected, offBackend.status)
    }

    @Test fun `MlKit shows no status line`() {
        // Deliberate, not an oversight: the offline rows render only
        // Warning-toned status, so ML Kit's old neutral "Bundled with the app"
        // line never reached the screen. Pinned here so a future backend
        // doesn't quietly re-add a status nobody can see.
        assertEquals(BackendStatus.Hidden, MlKitBackend().status)
    }
}
