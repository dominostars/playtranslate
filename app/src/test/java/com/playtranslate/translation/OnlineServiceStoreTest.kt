package com.playtranslate.translation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.BuildConfig
import com.playtranslate.Prefs
import com.playtranslate.security.SecretCodec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * JVM tests for [OnlineServiceStore]: the one-shot legacy→instance
 * migration (ids, order, presets, enabled flags), CRUD/reorder round-trips
 * through the persisted JSON, and the key-slot derivation that lets
 * migrated instances keep reading their legacy AAD-bound ciphertexts
 * without any re-encryption.
 *
 * A reversible fake [SecretCodec] is injected through the [Prefs] test
 * seam (AndroidKeyStore is instrumented-only), mirroring
 * [com.playtranslate.PrefsSecretMigrationTest].
 */
@RunWith(RobolectricTestRunner::class)
class OnlineServiceStoreTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private fun sp() =
        ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE)

    @Before fun clearPrefs() { sp().edit().clear().commit() }
    @After fun tearDown() { sp().edit().clear().commit() }

    private class FakeCodec : SecretCodec {
        override fun encrypt(context: String, plaintext: String): String? = enc(context, plaintext)
        override fun decrypt(context: String, stored: String): String? {
            val prefix = "$PREFIX$context|"
            return if (stored.startsWith(prefix)) stored.removePrefix(prefix) else null
        }
        companion object {
            const val PREFIX = "enc:"
            fun enc(context: String, plaintext: String) = "$PREFIX$context|$plaintext"
        }
    }

    private fun initStore(): Prefs {
        val prefs = Prefs(ctx, FakeCodec())
        OnlineServiceStore.init(ctx, prefs)
        return prefs
    }

    /** Personal builds bake a DeepL key into BuildConfig, which the legacy
     *  deeplApiKey getter falls back to when the slot is absent — store a
     *  literal "" (explicitly-cleared semantics) so migration tests that
     *  don't want a DeepL instance behave identically on every machine. */
    private fun suppressBakedDeeplKey() =
        sp().edit().putString("deepl_api_key", "").commit()

    private fun seedPlaintext(vararg pairs: Pair<String, String>) =
        sp().edit().apply { pairs.forEach { (k, v) -> putString(k, v) } }.commit()

    // ── Migration ─────────────────────────────────────────────────────────

    @Test fun `full legacy config migrates in waterfall order with presets`() {
        seedPlaintext(
            "gemini_api_key" to "AIzaG",
            "openai_api_key" to "sk-O",
            "deepseek_api_key" to "sk-D",
            "deepl_api_key" to "deepl-K:fx",
        )
        sp().edit()
            .putBoolean("gemini_enabled", true)
            .putBoolean("openai_enabled", false)
            .putBoolean("deepseek_enabled", true)
            .putBoolean("deepl_enabled", false)
            .putBoolean("lingva_enabled", true)
            .putString("gemini_model", "gemini-pro-latest")
            .putString("deepseek_model", "deepseek-v4-flash")
            .commit()

        initStore()
        val all = OnlineServiceStore.all()

        assertEquals(
            listOf("gemini", "openai", "deepseek", "deepl", "lingva"),
            all.map { it.id },
        )
        assertEquals(
            listOf(
                ServiceType.GEMINI, ServiceType.OPENAI, ServiceType.OPENAI,
                ServiceType.DEEPL, ServiceType.LINGVA,
            ),
            all.map { it.type },
        )
        assertEquals(listOf(true, false, true, false, true), all.map { it.enabled })
        assertEquals("gemini-pro-latest", all[0].model)
        assertEquals(OpenAiPreset.OPENAI, all[1].preset)
        assertEquals(OpenAiPreset.DEEPSEEK, all[2].preset)
        assertEquals(OnlineServiceStore.DEEPSEEK_BASE_URL, all[2].baseUrl)
        assertEquals("deepseek-v4-flash", all[2].model)
    }

    @Test fun `custom openai base url migrates as CUSTOM preset`() {
        suppressBakedDeeplKey()
        seedPlaintext("openai_api_key" to "sk-O")
        sp().edit().putString("openai_base_url", "https://openrouter.ai/api/v1").commit()

        initStore()

        val openai = OnlineServiceStore.byId("openai")!!
        assertEquals(OpenAiPreset.CUSTOM, openai.preset)
        assertEquals("https://openrouter.ai/api/v1", openai.baseUrl)
    }

    @Test fun `default openai base url migrates as OPENAI preset`() {
        suppressBakedDeeplKey()
        seedPlaintext("openai_api_key" to "sk-O")
        // Stored explicitly but equal to the canonical endpoint (modulo
        // trailing slash) — still first-party OpenAI.
        sp().edit().putString("openai_base_url", Prefs.DEFAULT_OPENAI_BASE_URL + "/").commit()

        initStore()

        assertEquals(OpenAiPreset.OPENAI, OnlineServiceStore.byId("openai")!!.preset)
    }

    @Test fun `nothing configured migrates to enabled lingva only`() {
        suppressBakedDeeplKey()

        initStore()
        val all = OnlineServiceStore.all()

        assertEquals(listOf("lingva"), all.map { it.id })
        assertTrue(all[0].enabled)
    }

    @Test fun `disabled lingva migrates disabled`() {
        suppressBakedDeeplKey()
        sp().edit().putBoolean("lingva_enabled", false).commit()

        initStore()

        assertFalse(OnlineServiceStore.byId("lingva")!!.enabled)
    }

    @Test fun `token with toggle off migrates as a disabled instance keeping its key`() {
        suppressBakedDeeplKey()
        seedPlaintext("gemini_api_key" to "AIzaG")
        sp().edit().putBoolean("gemini_enabled", false).commit()

        initStore()

        val gemini = OnlineServiceStore.byId("gemini")!!
        assertFalse(gemini.enabled)
        assertEquals("AIzaG", OnlineServiceStore.readKey("gemini"))
    }

    @Test fun `migration runs once and later mutations survive re-init`() {
        suppressBakedDeeplKey()
        seedPlaintext("gemini_api_key" to "AIzaG")

        initStore()
        assertEquals(listOf("gemini", "lingva"), OnlineServiceStore.all().map { it.id })

        OnlineServiceStore.remove("gemini")
        // Re-init (fresh process): must reload the mutated list, not
        // re-run the migration and resurrect gemini.
        initStore()
        assertEquals(listOf("lingva"), OnlineServiceStore.all().map { it.id })
    }

    // ── CRUD / reorder ────────────────────────────────────────────────────

    @Test fun `add inserts at top and persists across reload`() {
        suppressBakedDeeplKey()
        initStore()

        OnlineServiceStore.add(
            OnlineServiceInstance(id = "uuid-1", type = ServiceType.OPENAI, enabled = true, model = "chat-latest"),
        )

        assertEquals(listOf("uuid-1", "lingva"), OnlineServiceStore.all().map { it.id })
        initStore()
        assertEquals(listOf("uuid-1", "lingva"), OnlineServiceStore.all().map { it.id })
        assertEquals("chat-latest", OnlineServiceStore.byId("uuid-1")!!.model)
    }

    @Test fun `reorder round trips and appends ids missing from the request`() {
        suppressBakedDeeplKey()
        initStore()
        OnlineServiceStore.add(OnlineServiceInstance(id = "a", type = ServiceType.GEMINI, enabled = true))
        OnlineServiceStore.add(OnlineServiceInstance(id = "b", type = ServiceType.DEEPL, enabled = true))
        // List is now [b, a, lingva].

        OnlineServiceStore.reorder(listOf("lingva", "a"))

        // "b" wasn't in the request (stale caller) — appended, not dropped.
        assertEquals(listOf("lingva", "a", "b"), OnlineServiceStore.all().map { it.id })
    }

    @Test fun `setEnabled flips only the target instance`() {
        suppressBakedDeeplKey()
        initStore()
        OnlineServiceStore.add(OnlineServiceInstance(id = "a", type = ServiceType.GEMINI, enabled = true))

        OnlineServiceStore.setEnabled("a", false)

        assertFalse(OnlineServiceStore.byId("a")!!.enabled)
        assertTrue(OnlineServiceStore.byId("lingva")!!.enabled)
    }

    @Test fun `update replaces the instance in place`() {
        suppressBakedDeeplKey()
        initStore()
        OnlineServiceStore.add(
            OnlineServiceInstance(id = "a", type = ServiceType.OPENAI, enabled = true, preset = OpenAiPreset.OPENAI),
        )

        OnlineServiceStore.update(
            OnlineServiceInstance(
                id = "a", type = ServiceType.OPENAI, enabled = true,
                preset = OpenAiPreset.CUSTOM, baseUrl = "http://192.168.1.10:1234/v1",
            ),
        )

        assertEquals(listOf("a", "lingva"), OnlineServiceStore.all().map { it.id })
        assertEquals(OpenAiPreset.CUSTOM, OnlineServiceStore.byId("a")!!.preset)
    }

    @Test fun `remove drops the instance and clears its key slot`() {
        suppressBakedDeeplKey()
        seedPlaintext("gemini_api_key" to "AIzaG")
        initStore()
        assertTrue(sp().contains("gemini_api_key"))

        OnlineServiceStore.remove("gemini")

        assertNull(OnlineServiceStore.byId("gemini"))
        assertFalse(sp().contains("gemini_api_key"))
    }

    // ── Key slots ─────────────────────────────────────────────────────────

    @Test fun `keySlot maps legacy ids to legacy slots and new ids to namespaced slots`() {
        assertEquals("gemini_api_key", OnlineServiceStore.keySlot("gemini"))
        assertEquals("openai_api_key", OnlineServiceStore.keySlot("openai"))
        assertEquals("deepseek_api_key", OnlineServiceStore.keySlot("deepseek"))
        assertEquals("deepl_api_key", OnlineServiceStore.keySlot("deepl"))
        assertEquals("svc_api_key_uuid-7", OnlineServiceStore.keySlot("uuid-7"))
    }

    @Test fun `readKey decrypts a legacy ciphertext in place with no re-encryption`() {
        suppressBakedDeeplKey()
        // Post-encryption upgrade state: ciphertext + done marker already set.
        sp().edit()
            .putString("gemini_api_key", FakeCodec.enc("gemini_api_key", "AIza-old"))
            .putBoolean("secrets_encrypted_migrated", true)
            .commit()

        initStore()

        assertEquals("AIza-old", OnlineServiceStore.readKey("gemini"))
        // The stored blob is byte-identical — nothing re-encrypted it.
        assertEquals(
            FakeCodec.enc("gemini_api_key", "AIza-old"),
            sp().getString("gemini_api_key", null),
        )
    }

    @Test fun `writeKey for a new instance encrypts into its namespaced slot`() {
        suppressBakedDeeplKey()
        initStore()

        OnlineServiceStore.writeKey("uuid-9", "sk-new")

        assertEquals(
            FakeCodec.enc("svc_api_key_uuid-9", "sk-new"),
            sp().getString("svc_api_key_uuid-9", null),
        )
        assertEquals("sk-new", OnlineServiceStore.readKey("uuid-9"))
    }

    @Test fun `readKey for deepl falls back to the baked build key only when the slot is absent`() {
        // No suppression: slot genuinely absent → BuildConfig bootstrap
        // (empty on distributed builds, personal key on dev builds).
        initStore()
        assertEquals(BuildConfig.DEEPL_API_KEY, OnlineServiceStore.readKey("deepl"))

        // Explicitly cleared slot must NOT resurrect the baked key.
        suppressBakedDeeplKey()
        assertEquals("", OnlineServiceStore.readKey("deepl"))
    }
}
