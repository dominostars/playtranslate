package com.playtranslate.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Reversible at-rest protection for a handful of short secret strings (the
 * online-translation API keys). Encrypt/decrypt is delegated through
 * [SecretCodec] so [com.playtranslate.Prefs] can be unit-tested on the JVM with
 * a fake; the production [SecretCipher] is backed by a single AES-256-GCM key
 * in the AndroidKeyStore.
 *
 * Each ciphertext is bound to a [context] (its preference-key name) via
 * AES-GCM additional authenticated data, so a blob authenticates not just
 * itself but *where it belongs*: a valid value cannot be copied from one secret
 * slot into another (cross-slot swap) — the slot's context won't match and
 * authentication fails.
 *
 * Threat model: a cold copy of the prefs file (rooted pull, recovery image,
 * device-to-device transfer) yields ciphertext, not keys; the AAD binding adds
 * resistance to offline blob shuffling by someone who can write the prefs file
 * but cannot invoke the keystore. It does NOT defend against a live, unlocked,
 * root-in-process attacker — the app must be able to decrypt to use the key, so
 * injected code can too.
 */
internal interface SecretCodec {
    /**
     * @param context bound into the ciphertext as AES-GCM AAD (the secret's
     *   logical slot). [decrypt] must supply the same context or authentication
     *   fails.
     * @return a Base64 blob, or null if encryption is unavailable (never the plaintext).
     */
    fun encrypt(context: String, plaintext: String): String?

    /** @return the recovered plaintext, or null on tamper / wrong-context / wrong-format / keystore loss. */
    fun decrypt(context: String, stored: String): String?
}

object SecretCipher : SecretCodec {

    private const val TAG = "SecretCipher"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "pt_secret_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12

    /** Blob layout = version(1) ‖ iv(12) ‖ ciphertext‖tag. v2 binds the
     *  preference-key context as AAD; v1 (AAD-less) never shipped, so it is
     *  not accepted. The `v1` alias versions the *key*, not the blob format. */
    private const val FORMAT_VERSION: Byte = 0x02

    override fun encrypt(context: String, plaintext: String): String? = try {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            updateAAD(aad(context))
        }
        // Randomized-encryption-required (the default) → the provider generates
        // a fresh IV per call. Reading it here, rather than supplying our own,
        // structurally removes the GCM IV-reuse footgun.
        val iv = cipher.iv
        require(iv.size == IV_BYTES) { "unexpected IV length ${iv.size}" }
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val blob = ByteArray(1 + iv.size + ct.size).also {
            it[0] = FORMAT_VERSION
            System.arraycopy(iv, 0, it, 1, iv.size)
            System.arraycopy(ct, 0, it, 1 + iv.size, ct.size)
        }
        Base64.encodeToString(blob, Base64.NO_WRAP)
    } catch (t: Throwable) {
        // Exception class only — never the plaintext or ciphertext.
        Log.w(TAG, "encrypt failed: ${t.javaClass.simpleName}")
        null
    }

    override fun decrypt(context: String, stored: String): String? = try {
        val blob = Base64.decode(stored, Base64.NO_WRAP)
        require(blob.size > 1 + IV_BYTES && blob[0] == FORMAT_VERSION) { "bad blob" }
        // Decrypt must use the existing key; if it's gone (keystore loss) we
        // fail to null rather than mint a fresh key that could never decrypt
        // this blob.
        val key = loadKey() ?: error("key missing")
        val iv = blob.copyOfRange(1, 1 + IV_BYTES)
        val ct = blob.copyOfRange(1 + IV_BYTES, blob.size)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            updateAAD(aad(context))
        }
        String(cipher.doFinal(ct), Charsets.UTF_8)
    } catch (t: Throwable) {
        Log.w(TAG, "decrypt failed: ${t.javaClass.simpleName}")
        null
    }

    /** AAD = format version ‖ context bytes. Binds each ciphertext to its slot
     *  and format, so a valid blob can't be replayed into a different
     *  preference key or reinterpreted under another format version. */
    private fun aad(context: String): ByteArray =
        byteArrayOf(FORMAT_VERSION) + context.toByteArray(Charsets.UTF_8)

    private fun keyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun loadKey(): SecretKey? =
        (keyStore().getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey

    @Synchronized
    private fun getOrCreateKey(): SecretKey {
        loadKey()?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    // No setUserAuthenticationRequired: the background
                    // CaptureService must decrypt headlessly, and a non-auth
                    // key is not invalidated by lockscreen/biometric changes.
                    .build()
            )
        }.generateKey()
    }
}
