package com.playtranslate.translation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.BuildConfig
import com.playtranslate.Prefs
import com.playtranslate.security.SecretCodec
import kotlinx.serialization.json.JsonObject
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

    @Test fun `a keyless Index-Translate instance round trips by its preset name`() {
        suppressBakedDeeplKey()
        initStore()
        OnlineServiceStore.add(
            OnlineServiceInstance(
                id = "bili", type = ServiceType.OPENAI, enabled = true,
                preset = OpenAiPreset.INDEX_TRANSLATE,
            ),
        )
        assertTrue(sp().getString(OnlineServiceStore.KEY_INSTANCES, "")!!.contains("\"INDEX_TRANSLATE\""))

        initStore()
        val bili = OnlineServiceStore.byId("bili")!!
        assertEquals(OpenAiPreset.INDEX_TRANSLATE, bili.preset)
        assertEquals(AccountRequirement.NONE, bili.account)
    }

    /** The thinking pick persists on the record. A record written before
     *  the field existed (no key in its JSON) decodes as UNSET, which
     *  every control reads as its start state, and so does a level name a
     *  later build adds. */
    @Test fun `the thinking pick round trips and older or unknown values decode as UNSET`() {
        suppressBakedDeeplKey()
        sp().edit().putString(
            OnlineServiceStore.KEY_INSTANCES,
            """[{"id":"deepseek","type":"OPENAI","enabled":true,"preset":"DEEPSEEK","model":"deepseek-v4-flash"},""" +
                """{"id":"future","type":"OPENAI","enabled":true,"preset":"OPENAI","reasoning":"XHIGH"},""" +
                """{"id":"lingva","type":"LINGVA","enabled":true}]""",
        ).commit()
        initStore()
        val older = OnlineServiceStore.byId("deepseek")!!
        assertEquals(ReasoningLevel.UNSET, older.reasoning)
        assertEquals(ReasoningLevel.UNSET, OnlineServiceStore.byId("future")!!.reasoning)
        assertEquals("the rest of the list still loads", 3, OnlineServiceStore.all().size)

        OnlineServiceStore.update(older.copy(reasoning = ReasoningLevel.LOW))
        assertTrue(sp().getString(OnlineServiceStore.KEY_INSTANCES, "")!!.contains("\"reasoning\":\"LOW\""))
        initStore()
        assertEquals(ReasoningLevel.LOW, OnlineServiceStore.byId("deepseek")!!.reasoning)
        // The services-page toggle and the model picker copy the record, so
        // neither drops the pick.
        OnlineServiceStore.setEnabled("deepseek", false)
        OnlineServiceStore.update(OnlineServiceStore.byId("deepseek")!!.copy(model = "deepseek-v4-pro"))
        assertEquals(ReasoningLevel.LOW, OnlineServiceStore.byId("deepseek")!!.reasoning)
    }

    /** What the provider reported about a model's thinking is written only
     *  while the instance has that model, round-trips, and survives the
     *  picker's copy of the record (inert for the new model, ready if the
     *  user picks the old one back). */
    @Test fun `a thinking report is stored only for the instance's model and round trips`() {
        suppressBakedDeeplKey()
        OnlineServiceStore.add(
            OnlineServiceInstance(
                id = "ds", type = ServiceType.OPENAI, enabled = true,
                model = "deepseek-flash", preset = OpenAiPreset.DEEPSEEK,
            ),
        )
        val facts = com.playtranslate.PtJson.lenient
            .parseToJsonElement("""{"effort":{"supported_levels":["low","high"]}}""") as kotlinx.serialization.json.JsonObject

        OnlineServiceStore.setReportedThinking("ds", ReportedThinking("deepseek-v4-pro", facts, fetchedAtMs = 5))
        assertNull("about another model: not written", OnlineServiceStore.byId("ds")!!.reportedThinking)

        val report = ReportedThinking("deepseek-flash", facts, fetchedAtMs = 5)
        OnlineServiceStore.setReportedThinking("ds", report)
        initStore()
        assertEquals(report, OnlineServiceStore.byId("ds")!!.reportedThinking)

        OnlineServiceStore.update(OnlineServiceStore.byId("ds")!!.copy(model = "deepseek-v4-pro"))
        assertEquals(report, OnlineServiceStore.byId("ds")!!.reportedThinking)
    }

    /** DeepSeek's first Thinking switch saved `thinking: true`, which sent
     *  thinking enabled at DeepSeek's default effort, high. Such a record
     *  takes HIGH, the pick that sends that same request once DeepSeek has
     *  reported its levels; one moved to another preset carries it too, read
     *  as that preset's start state. Its off was the start state and needs
     *  nothing, a pick of the record's own wins, and the next write drops the
     *  old key. */
    @Test fun `a saved Thinking switch on carries over as HIGH`() {
        suppressBakedDeeplKey()
        sp().edit().putString(
            OnlineServiceStore.KEY_INSTANCES,
            """[{"id":"on","type":"OPENAI","enabled":true,"preset":"DEEPSEEK","model":"deepseek-v4-flash","thinking":true},""" +
                """{"id":"off","type":"OPENAI","enabled":true,"preset":"DEEPSEEK","thinking":false},""" +
                """{"id":"moved","type":"OPENAI","enabled":true,"preset":"MISTRAL","thinking":true},""" +
                """{"id":"picked","type":"OPENAI","enabled":true,"preset":"DEEPSEEK","thinking":true,"reasoning":"LOW"},""" +
                """{"id":"lingva","type":"LINGVA","enabled":true}]""",
        ).commit()
        initStore()
        val on = OnlineServiceStore.byId("on")!!
        assertEquals(ReasoningLevel.HIGH, on.reasoning)
        assertEquals(ReasoningLevel.UNSET, OnlineServiceStore.byId("off")!!.reasoning)
        assertEquals(ReasoningLevel.HIGH, OnlineServiceStore.byId("moved")!!.reasoning)
        assertEquals(ReasoningLevel.LOW, OnlineServiceStore.byId("picked")!!.reasoning)
        assertEquals(5, OnlineServiceStore.all().size)

        // Before DeepSeek reports its levels every request is thinking off;
        // once it has, HIGH sends what the switch sent: thinking enabled,
        // effort high.
        fun extras(instance: OnlineServiceInstance) = kotlinx.serialization.json.JsonObject(
            OnlineBackendFactory.requestExtrasFor(instance, "deepseek-v4-flash"),
        ).toString()
        assertEquals("""{"thinking":{"type":"disabled"}}""", extras(on))
        val levels = com.playtranslate.PtJson.lenient
            .parseToJsonElement("""{"effort":{"supported_levels":["low","high","max"]}}""") as kotlinx.serialization.json.JsonObject
        assertEquals(
            """{"thinking":{"type":"enabled"},"reasoning_effort":"high"}""",
            extras(on.copy(reportedThinking = ReportedThinking("deepseek-v4-flash", levels, fetchedAtMs = 0))),
        )

        OnlineServiceStore.setEnabled("on", false)
        val stored = sp().getString(OnlineServiceStore.KEY_INSTANCES, "")!!
        assertFalse(stored, stored.contains("\"thinking\""))
        initStore()
        assertEquals(ReasoningLevel.HIGH, OnlineServiceStore.byId("on")!!.reasoning)
    }

    /** A build that predates a preset (a downgrade) decodes its name as
     *  OPENAI: the instance survives, reads "Account required" and stays
     *  unused, and the rest of the list still loads. */
    @Test fun `an unknown preset name decodes as OPENAI and the list still loads`() {
        suppressBakedDeeplKey()
        sp().edit().putString(
            OnlineServiceStore.KEY_INSTANCES,
            """[{"id":"future","type":"OPENAI","enabled":true,"preset":"SOME_FUTURE_PRESET"},""" +
                """{"id":"lingva","type":"LINGVA","enabled":true}]""",
        ).commit()
        initStore()
        assertEquals(listOf("future", "lingva"), OnlineServiceStore.all().map { it.id })
        assertEquals(OpenAiPreset.OPENAI, OnlineServiceStore.byId("future")!!.preset)
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

    /** The config page redraws its thinking row from this: every write to
     *  the list tells each listener once, after the write has landed, and a
     *  listener removed is told nothing more. */
    @Test fun `every write tells the listeners, after it lands`() {
        suppressBakedDeeplKey()
        initStore()
        val seen = mutableListOf<List<String>>()
        val listener = { seen += OnlineServiceStore.all().map { it.id } }
        OnlineServiceStore.addListener(listener)
        try {
            OnlineServiceStore.add(OnlineServiceInstance(id = "a", type = ServiceType.GEMINI, enabled = true))
            OnlineServiceStore.update(OnlineServiceInstance(id = "a", type = ServiceType.GEMINI, enabled = true, model = "m"))
            OnlineServiceStore.setEnabled("a", false)
            OnlineServiceStore.setReportedThinking("a", ReportedThinking("m", JsonObject(emptyMap()), fetchedAtMs = 0))
            OnlineServiceStore.reorder(listOf("lingva", "a"))
            OnlineServiceStore.remove("a")
        } finally {
            OnlineServiceStore.removeListener(listener)
        }
        assertEquals(
            listOf(
                listOf("a", "lingva"), listOf("a", "lingva"), listOf("a", "lingva"),
                listOf("a", "lingva"), listOf("lingva", "a"), listOf("lingva"),
            ),
            seen,
        )
        OnlineServiceStore.setEnabled("lingva", false)
        assertEquals(6, seen.size)
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
