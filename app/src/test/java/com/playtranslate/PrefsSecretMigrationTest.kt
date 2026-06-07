package com.playtranslate

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.security.SecretCodec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * JVM tests for the at-rest API-key encryption wiring in [Prefs]: the one-shot
 * plaintext → ciphertext migration ([Prefs.migrateSecretsToEncrypted], run from
 * [Prefs.migrateLegacyPrefs]) and the read/write helpers.
 *
 * The real [com.playtranslate.security.SecretCipher] needs AndroidKeyStore
 * (instrumented-only); here a reversible fake [SecretCodec] is injected so the
 * bug-prone *logic* — empty-vs-clear semantics, fail-closed writes, run-once
 * marker, atomic re-encryption, per-slot binding — is covered on the fast JVM
 * path, the way every other `Prefs` migration in this module is. Raw values are
 * seeded directly to mimic exactly what a pre-encryption build persisted.
 */
@RunWith(RobolectricTestRunner::class)
class PrefsSecretMigrationTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private fun sp() =
        ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE)

    @Before fun clearPrefs() { sp().edit().clear().commit() }
    @After fun tearDown() { sp().edit().clear().commit() }

    /**
     * Reversible, inspectable stand-in for AndroidKeyStore AES-GCM. The blob
     * embeds the [context] so the fake models the real cipher's per-slot AAD
     * binding: a value encrypted for one preference key won't decrypt under
     * another. [encrypt] returns null when [encryptWorks] is false (a broken
     * keystore); [decrypt] returns null when [decryptWorks] is false (key lost
     * after a value was already encrypted).
     */
    private class FakeCodec(
        var encryptWorks: Boolean = true,
        var decryptWorks: Boolean = true,
    ) : SecretCodec {
        override fun encrypt(context: String, plaintext: String): String? =
            if (encryptWorks) enc(context, plaintext) else null
        override fun decrypt(context: String, stored: String): String? {
            val prefix = "$PREFIX$context|"
            return if (decryptWorks && stored.startsWith(prefix)) stored.removePrefix(prefix) else null
        }
        companion object {
            const val PREFIX = "enc:"
            fun enc(context: String, plaintext: String) = "$PREFIX$context|$plaintext"
        }
    }

    private fun enc(context: String, plaintext: String) = FakeCodec.enc(context, plaintext)

    private val deepl = "deepl_api_key"
    private val gemini = "gemini_api_key"
    private val openai = "openai_api_key"
    private val marker = "secrets_encrypted_migrated"

    private fun seedPlaintext(vararg pairs: Pair<String, String>) =
        sp().edit().apply { pairs.forEach { (k, v) -> putString(k, v) } }.commit()

    @Test fun `legacy plaintext keys are re-encrypted in place and the marker is set`() {
        seedPlaintext(gemini to "AIzaPLAIN", openai to "sk-PLAIN")

        val prefs = Prefs(ctx, FakeCodec())

        // Raw stored bytes are slot-bound ciphertext now, not the plaintext.
        assertEquals(enc(gemini, "AIzaPLAIN"), sp().getString(gemini, null))
        assertEquals(enc(openai, "sk-PLAIN"), sp().getString(openai, null))
        // The getter round-trips back to plaintext.
        assertEquals("AIzaPLAIN", prefs.geminiApiKey)
        assertEquals("sk-PLAIN", prefs.openaiApiKey)
        assertTrue(sp().getBoolean(marker, false))
    }

    @Test fun `migration runs exactly once and never double-encrypts`() {
        seedPlaintext(gemini to "AIzaPLAIN")

        Prefs(ctx, FakeCodec())
        assertEquals(enc(gemini, "AIzaPLAIN"), sp().getString(gemini, null))

        // A later construction must not re-encrypt the already-encrypted value.
        Prefs(ctx, FakeCodec())
        assertEquals(enc(gemini, "AIzaPLAIN"), sp().getString(gemini, null))
    }

    @Test fun `encrypt failure leaves prefs untouched and re-migrates next launch`() {
        seedPlaintext(gemini to "AIzaPLAIN")

        // Broken keystore: write nothing, do not mark done.
        Prefs(ctx, FakeCodec(encryptWorks = false))
        assertEquals("AIzaPLAIN", sp().getString(gemini, null))
        assertFalse(sp().contains(marker))

        // Keystore recovers: the next construction migrates cleanly.
        Prefs(ctx, FakeCodec(encryptWorks = true))
        assertEquals(enc(gemini, "AIzaPLAIN"), sp().getString(gemini, null))
        assertTrue(sp().getBoolean(marker, false))
    }

    @Test fun `setting a key persists it encrypted and reads back as plaintext`() {
        val prefs = Prefs(ctx, FakeCodec())

        prefs.geminiApiKey = "AIzaNEW"

        assertEquals(enc(gemini, "AIzaNEW"), sp().getString(gemini, null))
        assertEquals("AIzaNEW", prefs.geminiApiKey)
    }

    @Test fun `clearing a key stores a literal empty and does not remove it`() {
        val prefs = Prefs(ctx, FakeCodec())
        prefs.geminiApiKey = "AIzaNEW"

        prefs.geminiApiKey = ""

        // Stored as a present literal "", NOT removed — so a non-empty default
        // (BuildConfig for DeepL) can't resurrect via getString's fallback.
        assertTrue(sp().contains(gemini))
        assertEquals("", sp().getString(gemini, null))
        assertEquals("", prefs.geminiApiKey)
    }

    @Test fun `a write whose encryption fails persists nothing and never clobbers an existing value`() {
        // Pre-existing encrypted value, migration already done.
        sp().edit().putString(gemini, enc(gemini, "OLD")).putBoolean(marker, true).commit()

        // Broken keystore at save time: the set must be a no-op, not a wipe.
        val prefs = Prefs(ctx, FakeCodec(encryptWorks = false))
        prefs.geminiApiKey = "AIzaNEW"

        assertEquals(enc(gemini, "OLD"), sp().getString(gemini, null))
    }

    @Test fun `decrypt failure after migration reads as no key`() {
        // Post-migration state: an encrypted value with the marker already set.
        sp().edit().putString(gemini, enc(gemini, "AIzaXXX")).putBoolean(marker, true).commit()

        // Keystore key lost: decrypt fails → the getter reads as no key.
        val prefs = Prefs(ctx, FakeCodec(decryptWorks = false))
        assertEquals("", prefs.geminiApiKey)
    }

    @Test fun `decrypt failure is fail-closed even when the default is non-empty`() {
        // Post-migration encrypted value + marker; the keystore key is then lost.
        sp().edit().putString(gemini, enc(gemini, "XXX")).putBoolean(marker, true).commit()

        // Stands in for DeepL on a build with a baked BuildConfig key: a
        // present-but-undecryptable secret must read as "" (no key), never the
        // baked default — which could be charged to the wrong account.
        assertEquals(
            "",
            Prefs(ctx, FakeCodec(decryptWorks = false)).readSecret(gemini, "BAKED-FALLBACK-KEY"),
        )
    }

    @Test fun `an absent key still returns the bootstrap default`() {
        // The default is the personal-build BuildConfig DeepL key (or "") and
        // must still be used when the key was genuinely never stored.
        assertEquals(
            "BAKED-FALLBACK-KEY",
            Prefs(ctx, FakeCodec()).readSecret("never_stored_key", "BAKED-FALLBACK-KEY"),
        )
    }

    @Test fun `fresh install with no stored keys still marks itself done`() {
        // No seeded keys: construction must not throw and must set the marker
        // so a key entered later (already encrypted) is never retro-touched.
        val prefs = Prefs(ctx, FakeCodec())
        assertTrue(sp().getBoolean(marker, false))

        prefs.geminiApiKey = "AIzaLATER"
        assertEquals(enc(gemini, "AIzaLATER"), sp().getString(gemini, null))
    }

    @Test fun `a ciphertext copied into another slot does not decrypt`() {
        val prefs = Prefs(ctx, FakeCodec())
        prefs.geminiApiKey = "AIza-secret"
        val geminiBlob = sp().getString(gemini, null)!!

        // Attacker with prefs-write access copies the gemini blob into openai's slot.
        sp().edit().putString(openai, geminiBlob).commit()

        // openai must reject it (context mismatch) → reads as no key...
        assertEquals("", prefs.openaiApiKey)
        // ...while gemini still reads correctly in its own slot.
        assertEquals("AIza-secret", prefs.geminiApiKey)
    }

    @Test fun `a deepl key migrated from plaintext auto-enables deepl`() {
        // Mirrors the existing DeepL continuity migration, now that the key is
        // read post-encryption: a stored (non-blank) key flips deepl_enabled on.
        seedPlaintext(deepl to "deepl-PLAIN:fx")

        val prefs = Prefs(ctx, FakeCodec())

        assertEquals(enc(deepl, "deepl-PLAIN:fx"), sp().getString(deepl, null))
        assertEquals("deepl-PLAIN:fx", prefs.deeplApiKey)
        assertTrue(prefs.deeplEnabled)
    }

    @Test fun `concurrent first-launch constructions never double-encrypt`() {
        // Yields mid-encrypt to widen the migration window, so any missing
        // serialization has a chance to surface as double-encryption.
        val codec = object : SecretCodec {
            override fun encrypt(context: String, plaintext: String): String? {
                Thread.yield()
                return "enc:$context|$plaintext"
            }
            override fun decrypt(context: String, stored: String): String? {
                val prefix = "enc:$context|"
                return if (stored.startsWith(prefix)) stored.removePrefix(prefix) else null
            }
        }

        repeat(50) { i ->
            sp().edit().clear().commit()
            seedPlaintext(deepl to "d$i", gemini to "g$i", openai to "o$i")

            val barrier = java.util.concurrent.CyclicBarrier(2)
            val threads = List(2) { Thread { barrier.await(); Prefs(ctx, codec) } }
            threads.forEach { it.start() }
            threads.forEach { it.join() }

            // Each value must decrypt (single layer) back to its plaintext; a
            // double-encrypted "enc:k|enc:k|dN" would not round-trip.
            assertEquals("d$i", codec.decrypt(deepl, sp().getString(deepl, null)!!))
            assertEquals("g$i", codec.decrypt(gemini, sp().getString(gemini, null)!!))
            assertEquals("o$i", codec.decrypt(openai, sp().getString(openai, null)!!))
            assertTrue(sp().getBoolean(marker, false))
        }
    }
}
