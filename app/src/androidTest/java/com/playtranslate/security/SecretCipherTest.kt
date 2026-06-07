package com.playtranslate.security

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for [SecretCipher] against the real AndroidKeyStore
 * (unavailable on the JVM). The surrounding [com.playtranslate.Prefs] logic is
 * covered separately on the JVM in PrefsSecretMigrationTest with a fake codec.
 */
@RunWith(AndroidJUnit4::class)
class SecretCipherTest {

    private val ctx = "gemini_api_key"

    @Test fun roundTrips() {
        val plain = "sk-proj-Abc123_DEF-456:fx"
        val blob = SecretCipher.encrypt(ctx, plain)
        assertNotNull(blob)
        assertNotEquals(plain, blob)
        assertEquals(plain, SecretCipher.decrypt(ctx, blob!!))
    }

    @Test fun emptyStringRoundTrips() {
        val blob = SecretCipher.encrypt(ctx, "")
        assertNotNull(blob)
        assertEquals("", SecretCipher.decrypt(ctx, blob!!))
    }

    @Test fun eachEncryptionUsesAFreshIv() {
        // Randomized IV → the same plaintext encrypts to different blobs.
        assertNotEquals(SecretCipher.encrypt(ctx, "same-key"), SecretCipher.encrypt(ctx, "same-key"))
    }

    @Test fun wrongContextFailsAuth() {
        // A blob bound to one preference slot must not decrypt under another:
        // this is the cross-slot-swap guard.
        val blob = SecretCipher.encrypt("gemini_api_key", "AIzaSecret")!!
        assertEquals("AIzaSecret", SecretCipher.decrypt("gemini_api_key", blob))
        assertNull(SecretCipher.decrypt("openai_api_key", blob))
    }

    @Test fun tamperedCiphertextFailsAuth() {
        val raw = Base64.decode(SecretCipher.encrypt(ctx, "sensitive")!!, Base64.NO_WRAP)
        raw[raw.size - 1] = (raw[raw.size - 1].toInt() xor 0x01).toByte() // flip a GCM-tag bit
        assertNull(SecretCipher.decrypt(ctx, Base64.encodeToString(raw, Base64.NO_WRAP)))
    }

    @Test fun legacyOrUnknownVersionByteFails() {
        val raw = Base64.decode(SecretCipher.encrypt(ctx, "x")!!, Base64.NO_WRAP)
        raw[0] = 0x01 // v1 (AAD-less) is no longer accepted
        assertNull(SecretCipher.decrypt(ctx, Base64.encodeToString(raw, Base64.NO_WRAP)))
    }

    @Test fun garbageInputFails() {
        assertNull(SecretCipher.decrypt(ctx, "not-base64-%%%"))
        assertNull(SecretCipher.decrypt(ctx, ""))
    }
}
