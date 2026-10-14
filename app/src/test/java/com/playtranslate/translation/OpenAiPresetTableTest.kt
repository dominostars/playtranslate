package com.playtranslate.translation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.Prefs
import com.playtranslate.security.SecretCodec
import com.playtranslate.ui.LlmBackendConfigs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Every [OpenAiPreset]'s per-provider facts in one table (endpoint, default
 * model, name, key page, requirement, where the picker lists it), pinned as
 * literals so a branch edited by mistake shows up here; and the requirement
 * as the factory hands it to a live backend.
 */
@RunWith(RobolectricTestRunner::class)
class OpenAiPresetTableTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private data class Row(
        val baseUrl: String,
        val defaultModel: String,
        val name: String,
        val keyUrl: String,
        val account: AccountRequirement,
        val listedUnderOpenAi: Boolean,
    )

    private val table = mapOf(
        OpenAiPreset.OPENAI to Row(
            "https://api.openai.com/v1", "chat-latest", "OpenAI",
            "https://platform.openai.com/api-keys", AccountRequirement.REQUIRED, true,
        ),
        OpenAiPreset.DEEPSEEK to Row(
            "https://api.deepseek.com/v1", "deepseek-v4-flash", "DeepSeek",
            "https://platform.deepseek.com/api_keys", AccountRequirement.REQUIRED, true,
        ),
        OpenAiPreset.MISTRAL to Row(
            "https://api.mistral.ai/v1", "mistral-small-2603", "Mistral",
            "https://console.mistral.ai/api-keys", AccountRequirement.REQUIRED, true,
        ),
        OpenAiPreset.GROQ to Row(
            "https://api.groq.com/openai/v1", "openai/gpt-oss-20b", "Groq",
            "https://console.groq.com/keys", AccountRequirement.REQUIRED, true,
        ),
        OpenAiPreset.OPENROUTER to Row(
            "https://openrouter.ai/api/v1", "google/gemini-2.5-flash-lite", "OpenRouter",
            "https://openrouter.ai/keys", AccountRequirement.REQUIRED, true,
        ),
        OpenAiPreset.CLAUDE to Row(
            "https://api.anthropic.com/v1", "claude-haiku-4-5-20251001", "Claude",
            "https://platform.claude.com/settings/keys", AccountRequirement.REQUIRED, true,
        ),
        OpenAiPreset.INDEX_TRANSLATE to Row(
            "https://index-translate.bilibili.com/v1", "Index-Translate-35B-A3B",
            "Bilibili Index-Translate", "", AccountRequirement.NONE, false,
        ),
        // A Custom instance with no URL saved resolves to OpenAI's endpoint.
        OpenAiPreset.CUSTOM to Row(
            "https://api.openai.com/v1", "chat-latest", "Custom",
            "https://platform.openai.com/api-keys", AccountRequirement.REQUIRED, true,
        ),
    )

    private class FakeCodec : SecretCodec {
        override fun encrypt(context: String, plaintext: String): String? = "enc:$context|$plaintext"
        override fun decrypt(context: String, stored: String): String? =
            stored.removePrefix("enc:$context|").takeIf { stored.startsWith("enc:$context|") }
    }

    private fun sp() = ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE)

    @Before fun setUp() {
        sp().edit().clear().putString("deepl_api_key", "").commit()
        OnlineServiceStore.init(ctx, Prefs(ctx, FakeCodec()))
    }

    @After fun tearDown() {
        sp().edit().clear().commit()
    }

    private fun instance(preset: OpenAiPreset, id: String = "table-$preset") =
        OnlineServiceInstance(id = id, type = ServiceType.OPENAI, enabled = true, preset = preset)

    @Test fun `every preset has its row`() {
        assertEquals(OpenAiPreset.entries.toSet(), table.keys)
        OpenAiPreset.entries.forEach { preset ->
            val row = table.getValue(preset)
            val config = LlmBackendConfigs.forInstance(ctx, "table-$preset", ServiceType.OPENAI, preset)
            assertEquals(preset.name, row.baseUrl, OnlineBackendFactory.resolveBaseUrl(instance(preset)))
            assertEquals(preset.name, row.defaultModel, OnlineBackendFactory.defaultModelFor(ServiceType.OPENAI, preset))
            assertEquals(preset.name, row.name, OnlineBackendFactory.presetDisplayName(ctx, preset))
            assertEquals(preset.name, row.keyUrl, config.getKeyUrl)
            assertEquals(preset.name, row.account, preset.account)
            assertEquals(preset.name, row.account, config.account)
            assertEquals(preset.name, row.listedUnderOpenAi, preset.listedUnderOpenAi)
            // The provider section shows exactly for the presets the OpenAI row lists.
            assertEquals(preset.name, row.listedUnderOpenAi, config.allowsBaseUrl)
            // The note exists exactly where the key field doesn't.
            assertEquals(preset.name, !row.account.requiresKey, config.noteRes != null)
        }
    }

    @Test fun `an instance's requirement is its preset's for OpenAI, its type's otherwise`() {
        assertEquals(AccountRequirement.NONE, instance(OpenAiPreset.INDEX_TRANSLATE).account)
        assertEquals(AccountRequirement.REQUIRED, instance(OpenAiPreset.MISTRAL).account)
        assertEquals(
            AccountRequirement.NONE,
            OnlineServiceInstance(id = "l", type = ServiceType.LINGVA, enabled = true).account,
        )
        assertEquals(
            AccountRequirement.REQUIRED_FREE_TIER,
            OnlineServiceInstance(id = "d", type = ServiceType.DEEPL, enabled = true).account,
        )
        // A non-OpenAI type ignores the preset field's default.
        assertEquals(
            AccountRequirement.REQUIRED,
            OnlineServiceInstance(id = "g", type = ServiceType.GEMINI, enabled = true).account,
        )
        assertFalse(AccountRequirement.NONE.requiresKey)
        assertTrue(AccountRequirement.REQUIRED.requiresKey)
        assertTrue(AccountRequirement.REQUIRED_FREE_TIER.requiresKey)
    }

    /** The factory hands the instance's requirement to the backend: the
     *  keyless preset is usable with no key stored, a keyed one is not. */
    @Test fun `factory-built backends read the instance's requirement`() {
        val prefs = ctx.getSharedPreferences("test_prefs", Context.MODE_PRIVATE)
        val keyless = OnlineBackendFactory.build(ctx, prefs, instance(OpenAiPreset.INDEX_TRANSLATE), live = false)
        assertTrue(keyless.isUsable("ja", "en"))
        assertEquals(BackendStatus.Account(AccountRequirement.NONE), keyless.status)

        val keyed = OnlineBackendFactory.build(ctx, prefs, instance(OpenAiPreset.MISTRAL), live = false)
        assertFalse(keyed.isUsable("ja", "en"))
        assertEquals(BackendStatus.Account(AccountRequirement.REQUIRED), keyed.status)
    }
}
