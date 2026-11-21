package com.playtranslate.translation

import android.app.ActivityManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.cloud.PtCloudBackend
import com.playtranslate.cloud.PtCloudTestSupport
import com.playtranslate.translation.bergamot.BergamotModel
import com.playtranslate.translation.gemma.GemmaE2BMnnModel
import com.playtranslate.translation.hymt.HyMt2Model
import com.playtranslate.translation.hymt.HyMtModel
import com.playtranslate.translation.llm.ModelHelper
import com.playtranslate.translation.llm.MultiFileSha
import com.playtranslate.translation.llm.OnDeviceChatLlmBackend
import com.playtranslate.translation.llm.OnDeviceLlmBackend
import com.playtranslate.translation.qwen.Qwen35Mnn2bModel
import com.playtranslate.translation.qwen.QwenMnnModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowVMRuntime
import java.io.File

/**
 * [TranslationBackend.isSetUpFor] beside [TranslationBackend.isUsable] on
 * every real backend: whether the service could translate the pair if
 * asked now, whatever its switch says. One rule for all of them: where a
 * backend has a switch, isUsable is the switch AND isSetUpFor, so a
 * service that is set up and switched off is not usable (the waterfall
 * passes it by) and is still set up (it is listed for the user to pick, and
 * a pick runs it); and what makes a service not set up, a missing key, a
 * model not on the device, a pair it does not take, a licence's territory,
 * makes it neither.
 *
 * Each backend is a table over its switch and what setting it up means for
 * it. ML Kit has no switch and PlayTranslate Cloud's call refuses while its
 * own is off, so for those two set up is usable.
 *
 * A chat model that is switched off is set up and still answers no
 * question ([PromptCompleter.canComplete] keeps the switch): only a run the
 * user asks that one model for reaches it.
 *
 * Robolectric, as [OnDeviceCompleterTest]: a model is installed by its
 * directory's `.sentinel` holding the catalog's sha (for Firefox
 * Translations, the aggregate of the direction's files), the RAM is
 * ActivityManager's memory info and the ABI the runtime's 64-bit flag.
 */
@RunWith(RobolectricTestRunner::class)
class BackendSetUpForTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private var enabled = true
    private var permitted = true

    private val models: List<ModelHelper> =
        listOf(QwenMnnModel, Qwen35Mnn2bModel, GemmaE2BMnnModel, HyMtModel, HyMt2Model, BergamotModel("ja-en"))

    @Before fun setUp() {
        enabled = true
        permitted = true
        setTotalMem(16_000_000_000L)
        ShadowVMRuntime.setIs64Bit(true)
        models.forEach { uninstall(it) }
    }

    @After fun tearDown() {
        ShadowVMRuntime.setIs64Bit(true)
        models.forEach { uninstall(it) }
    }

    private fun setTotalMem(bytes: Long) {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        shadowOf(am).setMemoryInfo(ActivityManager.MemoryInfo().apply {
            totalMem = bytes
            availMem = bytes / 2
        })
    }

    /** What a verified download leaves: the catalog's sha in the model
     *  directory's sentinel (a set of files' aggregate, for a model that is
     *  several). */
    private fun install(model: ModelHelper) {
        val entry = requireNotNull(model.catalogEntry(ctx)) { "${model.catalogKey} is not in the catalog" }
        val sha = entry.files?.let { MultiFileSha.aggregate(it) }
            ?: requireNotNull(entry.sha256) { "${model.catalogKey} has no sha in the catalog" }
        val dir = model.file(ctx)
        dir.mkdirs()
        File(dir, ".sentinel").writeText(sha)
    }

    private fun uninstall(model: ModelHelper) {
        model.file(ctx).deleteRecursively()
    }

    /** [backend]'s two answers for a pair, as "usable/set up". */
    private fun answers(backend: TranslationBackend, source: String = "ja", target: String = "en"): String =
        "${backend.isUsable(source, target)}/${backend.isSetUpFor(source, target)}"

    // ── online services ──────────────────────────────────────────────────

    @Test fun `DeepL is set up with a key for a pair it takes, whatever its switch`() {
        var key: String? = "sk-test"
        val deepl = DeepLBackend(keyProvider = { key }, enabledProvider = { enabled })

        assertEquals("on, key", "true/true", answers(deepl))
        enabled = false
        assertEquals("off, key: listed and runnable, not in the waterfall", "false/true", answers(deepl))
        assertEquals("off, key, a pair it does not take", "false/false", answers(deepl, "th", "en"))
        key = ""
        assertEquals("off, no key", "false/false", answers(deepl))
        enabled = true
        assertEquals("on, no key", "false/false", answers(deepl))
        key = "sk-test"
        assertEquals("on, key, a pair it does not take", "false/false", answers(deepl, "ja", "th"))
    }

    @Test fun `Lingva is always set up`() {
        val lingva = LingvaBackend(enabledProvider = { enabled })

        assertEquals("on", "true/true", answers(lingva))
        enabled = false
        assertEquals("off", "false/true", answers(lingva))
    }

    @Test fun `Gemini is set up with a key, whatever its switch`() {
        var key: String? = "AIza-test"
        val gemini = GeminiBackend(
            keyProvider = { key },
            enabledProvider = { enabled },
            modelProvider = { "gemini-flash-lite-latest" },
            usageTracker = UsageTracker(FakeSharedPreferences(), "gemini"),
            cooldownState = CooldownState(context = null, backendId = "gemini"),
        )

        assertEquals("on, key", "true/true", answers(gemini))
        enabled = false
        assertEquals("off, key", "false/true", answers(gemini))
        key = null
        assertEquals("off, no key", "false/false", answers(gemini))
        enabled = true
        assertEquals("on, no key", "false/false", answers(gemini))
    }

    private fun openAi(account: AccountRequirement, key: () -> String?) = OpenAiBackend(
        id = "openai-test",
        displayName = "OpenAI",
        priority = 15,
        keyProvider = key,
        enabledProvider = { enabled },
        modelProvider = { "gpt-test" },
        baseUrlProvider = { "https://example.invalid/v1" },
        account = { account },
        usageTracker = UsageTracker(FakeSharedPreferences(), "openai-test"),
        applyOwnedByFilter = { false },
        cooldownState = null,
    )

    @Test fun `an OpenAI-type service that takes a key is set up with one, whatever its switch`() {
        var key: String? = "sk-test"
        val service = openAi(ServiceType.OPENAI.account) { key }

        assertEquals("on, key", "true/true", answers(service))
        enabled = false
        assertEquals("off, key", "false/true", answers(service))
        key = " "
        assertEquals("off, no key", "false/false", answers(service))
        enabled = true
        assertEquals("on, no key", "false/false", answers(service))
    }

    @Test fun `an OpenAI-type service that takes no key is always set up`() {
        val keyless = openAi(ServiceType.LINGVA.account) { "" }

        assertEquals("on", "true/true", answers(keyless))
        enabled = false
        assertEquals("off", "false/true", answers(keyless))
    }

    // ── offline services ─────────────────────────────────────────────────

    private class OnDevice(val backend: OnDeviceLlmBackend, val model: ModelHelper)

    private fun onDevice(): List<OnDevice> = listOf(
        OnDevice(QwenMnnBackend(ctx) { enabled }, QwenMnnModel),
        OnDevice(Qwen35Mnn2bBackend(ctx) { enabled }, Qwen35Mnn2bModel),
        OnDevice(GemmaE2BMnnBackend(ctx) { enabled }, GemmaE2BMnnModel),
        OnDevice(HyMt2Backend(ctx) { enabled }, HyMt2Model),
        // As the app wires it: the territory in the switch's provider, and
        // again apart from the switch.
        OnDevice(HyMtBackend(ctx, enabledProvider = { enabled && permitted }, permitted = { permitted }), HyMtModel),
    )

    @Test fun `an on-device model is set up when it is downloaded and the device can run it, whatever its switch`() {
        for (m in onDevice()) {
            val id = m.backend.id
            assertEquals("$id: on, not downloaded", "false/false", answers(m.backend))
            install(m.model)
            assertEquals("$id: on, downloaded", "true/true", answers(m.backend))
            assertEquals("$id: no same-language translation", "false/false", answers(m.backend, "ja", "ja"))
            enabled = false
            assertEquals("$id: off, downloaded: listed and runnable", "false/true", answers(m.backend))
            ShadowVMRuntime.setIs64Bit(false)
            assertEquals("$id: off, downloaded, a device that cannot load it", "false/false", answers(m.backend))
            ShadowVMRuntime.setIs64Bit(true)
            setTotalMem(1_000_000_000L)
            assertEquals("$id: off, downloaded, too little RAM", "false/false", answers(m.backend))
            setTotalMem(16_000_000_000L)
            uninstall(m.model)
            assertEquals("$id: off, not downloaded", "false/false", answers(m.backend))
            enabled = true
        }
    }

    @Test fun `Hunyuan 1_5 in a restricted region is not set up, switch on or off`() {
        val hymt = onDevice().last()
        install(hymt.model)
        assertEquals("the precondition: permitted, on, downloaded", "true/true", answers(hymt.backend))

        permitted = false
        assertEquals("on", "false/false", answers(hymt.backend))
        enabled = false
        assertEquals("off: a pick is refused as the waterfall is", "false/false", answers(hymt.backend))
    }

    @Test fun `a chat model that is switched off is set up and still answers no question`() {
        for (m in onDevice().filter { it.backend is OnDeviceChatLlmBackend }) {
            install(m.model)
            enabled = false
            assertEquals("${m.backend.id}", "false/true", answers(m.backend))
            assertEquals(
                "${m.backend.id}: a question is only put to a model that is switched on",
                false, (m.backend as OnDeviceChatLlmBackend).canComplete(),
            )
            enabled = true
            assertEquals("${m.backend.id}: read live", true, (m.backend as OnDeviceChatLlmBackend).canComplete())
        }
    }

    @Test fun `Firefox Translations is set up when the pair's model is downloaded, whatever its switch`() {
        val bergamot = BergamotBackend(ctx) { enabled }

        assertEquals("on, not downloaded", "false/false", answers(bergamot))
        install(BergamotModel("ja-en"))
        assertEquals("on, downloaded", "true/true", answers(bergamot))
        assertEquals("on, another pair's model is not this one's", "false/false", answers(bergamot, "ko", "en"))
        enabled = false
        assertEquals("off, downloaded", "false/true", answers(bergamot))
        ShadowVMRuntime.setIs64Bit(false)
        assertEquals("off, downloaded, a device that cannot load it", "false/false", answers(bergamot))
        ShadowVMRuntime.setIs64Bit(true)
        assertEquals("no same-language translation", "false/false", answers(bergamot, "ja", "ja"))
    }

    // ── no switch of their own to set aside ──────────────────────────────

    @Test fun `ML Kit has no switch, so set up is usable`() {
        assertEquals("true/true", answers(MlKitBackend()))
    }

    @Test fun `PlayTranslate Cloud is set up only while its translation switch is on`() {
        val on = PtCloudBackend(config = { PtCloudTestSupport.switchedOn(ctx) })
        assertEquals("on, key", "true/true", answers(on))

        // Its call refuses while the switch is off, so a pick could not run
        // it: off is not set up either.
        val config = PtCloudTestSupport.config(ctx)
        config.prefs.ptCloudTranslateEnabled = false
        val off = PtCloudBackend(config = { config })
        assertEquals("off, key", "false/false", answers(off))
    }
}
