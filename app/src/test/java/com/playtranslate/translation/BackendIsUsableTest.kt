package com.playtranslate.translation

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Direct `isUsable` tests for [DeepLBackend] and [LingvaBackend].
 * Pure JVM — `isUsable` is synchronous and doesn't touch the network.
 *
 * Guards the per-backend gating behavior the Settings UI relies on:
 *   - DeepL is usable only when both the key is non-blank AND the
 *     user has enabled it (so disabling DeepL doesn't lose the saved
 *     key, and a saved key alone doesn't auto-enable DeepL).
 *   - Lingva is usable iff the user has enabled it.
 */
class BackendIsUsableTest {

    @Test fun `DeepL is usable when enabled and key is set`() {
        val backend = DeepLBackend(
            keyProvider     = { "sk-test-key" },
            enabledProvider = { true },
        )
        assertEquals(true, backend.isUsable("ja", "en"))
    }

    @Test fun `DeepL is not usable when key is blank`() {
        val backend = DeepLBackend(
            keyProvider     = { "" },
            enabledProvider = { true },
        )
        assertEquals(false, backend.isUsable("ja", "en"))
    }

    @Test fun `DeepL is not usable when key is null`() {
        val backend = DeepLBackend(
            keyProvider     = { null },
            enabledProvider = { true },
        )
        assertEquals(false, backend.isUsable("ja", "en"))
    }

    @Test fun `DeepL is not usable when disabled even with a key set`() {
        val backend = DeepLBackend(
            keyProvider     = { "sk-test-key" },
            enabledProvider = { false },
        )
        assertEquals(false, backend.isUsable("ja", "en"))
    }

    @Test fun `DeepL keyProvider is read live each call`() {
        var key: String? = "first-key"
        val backend = DeepLBackend(
            keyProvider     = { key },
            enabledProvider = { true },
        )
        assertEquals(true, backend.isUsable("ja", "en"))

        key = ""
        assertEquals(false, backend.isUsable("ja", "en"))

        key = "second-key"
        assertEquals(true, backend.isUsable("ja", "en"))
    }

    @Test fun `DeepL enabledProvider is read live each call`() {
        var enabled = true
        val backend = DeepLBackend(
            keyProvider     = { "sk-test-key" },
            enabledProvider = { enabled },
        )
        assertEquals(true, backend.isUsable("ja", "en"))

        enabled = false
        assertEquals(false, backend.isUsable("ja", "en"))
    }

    @Test fun `Lingva is usable when enabled`() {
        val backend = LingvaBackend(enabledProvider = { true })
        assertEquals(true, backend.isUsable("ja", "en"))
    }

    @Test fun `Lingva is not usable when disabled`() {
        val backend = LingvaBackend(enabledProvider = { false })
        assertEquals(false, backend.isUsable("ja", "en"))
    }

    @Test fun `Lingva enabledProvider is read live each call`() {
        var enabled = true
        val backend = LingvaBackend(enabledProvider = { enabled })
        assertEquals(true, backend.isUsable("ja", "en"))

        enabled = false
        assertEquals(false, backend.isUsable("ja", "en"))
    }
}
