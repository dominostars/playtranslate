package com.playtranslate.translation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.Prefs
import com.playtranslate.PtJson
import com.playtranslate.TranslationCache
import com.playtranslate.security.SecretCodec
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

/**
 * Keeping each instance's thinking report current: what is asked, when, and
 * what is stored. One rule decides whether to ask ([ReportedThinkingRefresher.needsReport]):
 * the service is on, its provider publishes a catalog, and its report is
 * missing, about another model, or a day old. It runs at launch and after a
 * model pick, a service turned on and a page save. The answer is written
 * only for the model the instance still has, a failed ask keeps the old
 * report, and a report that changes what the requests send drops the
 * translations cached under the old one. The provider is a fake registered
 * under the instance's id, so nothing leaves the test.
 */
@RunWith(RobolectricTestRunner::class)
class ReportedThinkingRefresherTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private class FakeCodec : SecretCodec {
        override fun encrypt(context: String, plaintext: String): String? = "enc:$context|$plaintext"
        override fun decrypt(context: String, stored: String): String? =
            stored.removePrefix("enc:$context|").takeIf { stored.startsWith("enc:$context|") }
    }

    /** Holds every launched refresh until [runAll], so a test sees what was
     *  asked for before anything runs. */
    private class QueueDispatcher : CoroutineDispatcher() {
        val queue = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queue.addLast(block)
        }
        fun runAll() {
            while (queue.isNotEmpty()) queue.removeFirst().run()
        }
    }

    /** A provider whose catalog answers [answer], recording each ask. */
    private class FakeReporter(
        override val id: BackendId,
        private val answer: (String) -> JsonObject?,
    ) : TranslationBackend, ThinkingReporter {
        override val displayName = "fake-$id"
        override val priority = 15
        override val status: BackendStatus = BackendStatus.Hidden
        override val requiresInternet = true
        override val isDegradedFallback = false
        val asked = mutableListOf<String>()
        override fun isUsable(source: String, target: String) = true
        override suspend fun translate(text: String, source: String, target: String) = text
        override fun close() {}
        override suspend fun reportedThinking(model: String): JsonObject? {
            asked += model
            return answer(model)
        }
    }

    private val dispatcher = QueueDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val now = TimeUnit.DAYS.toMillis(30)
    private val levels = facts("""{"effort":{"supported_levels":["low","high"]}}""")

    private fun facts(json: String): JsonObject = PtJson.lenient.parseToJsonElement(json).jsonObject

    private fun sp() = ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE)

    @Before fun setUp() {
        sp().edit().clear().putString("deepl_api_key", "").commit()
        OnlineServiceStore.init(ctx, Prefs(ctx, FakeCodec()))
        ReportedThinkingRefresher.init(scope)
        ReportedThinkingRefresher.now = { now }
    }

    @After fun tearDown() {
        scope.cancel()
        TranslationCache.shared.resetForTest()
        ReportedThinkingRefresher.now = { System.currentTimeMillis() }
        OnlineServiceStore.all().forEach { TranslationBackendRegistry.removeOnlineBackend(it.id) }
        TranslationBackendRegistry.setOrder(null)
        sp().edit().clear().commit()
    }

    private fun instance(
        id: String,
        preset: OpenAiPreset = OpenAiPreset.DEEPSEEK,
        model: String = "deepseek-flash",
        enabled: Boolean = true,
        reported: ReportedThinking? = null,
        type: ServiceType = ServiceType.OPENAI,
    ) = OnlineServiceInstance(
        id = id, type = type, enabled = enabled, model = model, preset = preset, reportedThinking = reported,
    )

    private fun register(id: String, answer: (String) -> JsonObject? = { levels }) =
        FakeReporter(id, answer).also { TranslationBackendRegistry.addOnlineBackend(it) }

    @Test fun `the answer is stored for the model the instance has`() {
        OnlineServiceStore.add(instance("ds"))
        val reporter = register("ds")
        runBlocking { ReportedThinkingRefresher.refreshNow("ds") }
        assertEquals(listOf("deepseek-flash"), reporter.asked)
        assertEquals(ReportedThinking("deepseek-flash", levels, now), OnlineServiceStore.byId("ds")!!.reportedThinking)
    }

    /** A blank model is the preset's default, and that is what is asked. */
    @Test fun `a blank model asks about the preset's default`() {
        OnlineServiceStore.add(instance("ds", model = ""))
        val reporter = register("ds")
        runBlocking { ReportedThinkingRefresher.refreshNow("ds") }
        val default = OnlineBackendFactory.defaultModelFor(ServiceType.OPENAI, OpenAiPreset.DEEPSEEK)
        assertEquals(listOf(default), reporter.asked)
        assertEquals(default, OnlineServiceStore.byId("ds")!!.reportedThinking!!.model)
    }

    /** The user picked another model while the ask was out: the answer is
     *  about a model the instance no longer has, and is dropped. */
    @Test fun `an answer that lands after a model change writes nothing`() {
        OnlineServiceStore.add(instance("ds"))
        register("ds") {
            OnlineServiceStore.update(OnlineServiceStore.byId("ds")!!.copy(model = "deepseek-v4-pro"))
            levels
        }
        runBlocking { ReportedThinkingRefresher.refreshNow("ds") }
        assertNull(OnlineServiceStore.byId("ds")!!.reportedThinking)
    }

    @Test fun `a failed ask or an empty answer keeps the report the instance had`() {
        val old = ReportedThinking("deepseek-flash", facts("""{"effort":{"supported_levels":["high"]}}"""), fetchedAtMs = 1)
        OnlineServiceStore.add(instance("ds", reported = old))
        register("ds") { throw IOException("offline") }
        runBlocking { ReportedThinkingRefresher.refreshNow("ds") }
        assertEquals(old, OnlineServiceStore.byId("ds")!!.reportedThinking)

        TranslationBackendRegistry.removeOnlineBackend("ds")
        register("ds") { null }
        runBlocking { ReportedThinkingRefresher.refreshNow("ds") }
        assertEquals(old, OnlineServiceStore.byId("ds")!!.reportedThinking)
    }

    /** At launch: enabled instances with a catalog whose report is missing,
     *  about another model, or a day old; nothing else is asked. */
    @Test fun `the launch refresh asks only where a report is missing, about another model or a day old`() {
        val fresh = ReportedThinking("deepseek-flash", levels, now - TimeUnit.HOURS.toMillis(1))
        val old = ReportedThinking("deepseek-flash", levels, now - TimeUnit.HOURS.toMillis(25))
        val otherModel = ReportedThinking("deepseek-v4-pro", levels, now)
        val cases = listOf(
            instance("missing") to true,
            instance("fresh", reported = fresh) to false,
            instance("old", reported = old) to true,
            instance("other-model", reported = otherModel) to true,
            instance("disabled", enabled = false) to false,
            instance("claude", preset = OpenAiPreset.CLAUDE, model = "claude-sonnet-5") to true,
            instance("openrouter", preset = OpenAiPreset.OPENROUTER, model = "google/gemini-2.5-flash-lite") to true,
            instance("openai", preset = OpenAiPreset.OPENAI, model = "gpt-6-luna") to false,
            instance("gemini", type = ServiceType.GEMINI, model = "gemini-flash-lite-latest") to false,
        )
        val reporters = cases.associate { (inst, _) ->
            OnlineServiceStore.add(inst)
            inst.id to register(inst.id)
        }

        ReportedThinkingRefresher.refreshAllIfNeeded()
        dispatcher.runAll()

        cases.forEach { (inst, expected) ->
            assertEquals(inst.id, expected, reporters.getValue(inst.id).asked.isNotEmpty())
            assertEquals(inst.id, expected, ReportedThinkingRefresher.needsReport(inst))
        }
    }

    /** A picked model is asked about through the registered backend, which
     *  the pick doesn't rebuild. */
    @Test fun `picking a model asks about it`() {
        OnlineServiceStore.add(instance("ds", reported = ReportedThinking("deepseek-flash", levels, now)))
        val reporter = register("ds")
        OnlineServiceMutations.setModel(ctx, "ds", "deepseek-v4-pro")
        assertEquals(1, dispatcher.queue.size)
        dispatcher.runAll()
        assertEquals(listOf("deepseek-v4-pro"), reporter.asked)
        assertEquals("deepseek-v4-pro", OnlineServiceStore.byId("ds")!!.reportedThinking!!.model)
    }

    /** The launch check skips a service that is off, so turning one on
     *  asks, when its report is missing; one with a fresh report for its
     *  model, or turned off, asks nothing. */
    @Test fun `turning a service on asks when it has no report`() {
        OnlineServiceStore.add(instance("off", enabled = false))
        val reporter = register("off")
        OnlineServiceMutations.setEnabled(ctx, "off", true)
        assertEquals(1, dispatcher.queue.size)
        dispatcher.runAll()
        assertEquals(listOf("deepseek-flash"), reporter.asked)

        OnlineServiceStore.add(instance("fresh", enabled = false, reported = ReportedThinking("deepseek-flash", levels, now)))
        register("fresh")
        OnlineServiceMutations.setEnabled(ctx, "fresh", true)
        OnlineServiceMutations.setEnabled(ctx, "off", false)
        assertEquals(0, dispatcher.queue.size)
    }

    /** A save rebuilds the backend, a real one here, so a queued ask is
     *  counted and not run: nothing leaves the test. A new service has no
     *  report and asks; a save that keeps the model of a fresh report
     *  doesn't. */
    @Test fun `saving a page asks when the service has no report for its model`() {
        OnlineServiceMutations.saveConfig(ctx, instance("ds"), key = "")
        assertEquals(1, dispatcher.queue.size)
        dispatcher.queue.clear()

        val reported = OnlineServiceStore.byId("ds")!!.copy(reportedThinking = ReportedThinking("deepseek-flash", levels, now))
        OnlineServiceMutations.saveConfig(ctx, reported, key = "")
        assertEquals(0, dispatcher.queue.size)
    }

    // ── The report's one writer, and the cache ───────────────────────────

    private fun cacheOneTranslation() {
        TranslationCache.shared.resetForTest()
        TranslationCache.shared[TranslationCache.Key("こんにちは", "ja", "en")] = "Hello" to "Test"
    }

    /** OpenRouter's first report moves an untouched service from sending
     *  nothing to its lowest choice, Off: every translation the cache holds
     *  was made under the old request and is dropped. Asked the way the
     *  launch asks, so the answer goes through the one writer. */
    @Test fun `a report that changes the request drops the cached translations`() {
        OnlineServiceStore.add(instance("or", preset = OpenAiPreset.OPENROUTER, model = "google/gemini-2.5-flash-lite"))
        register("or") { facts("""{"reasoning":{"supported_efforts":["low"]}}""") }
        cacheOneTranslation()
        val generation = TranslationCache.shared.configGeneration
        runBlocking { ReportedThinkingRefresher.refreshNow("or") }
        assertEquals("google/gemini-2.5-flash-lite", OnlineServiceStore.byId("or")!!.reportedThinking!!.model)
        assertEquals(0, TranslationCache.shared.size())
        assertEquals("an in-flight result is not cached", generation + 1, TranslationCache.shared.configGeneration)
    }

    /** DeepSeek before a report and at its reported start state sends the
     *  same off: the cache keeps its translations. So does a report about a
     *  model the instance no longer has, which isn't written. */
    @Test fun `a report that leaves the request as it was keeps the cached translations`() {
        OnlineServiceStore.add(instance("ds"))
        cacheOneTranslation()
        OnlineServiceMutations.applyReportedThinking("ds", ReportedThinking("deepseek-flash", levels, now))
        assertEquals(levels, OnlineServiceStore.byId("ds")!!.reportedThinking!!.facts)
        assertEquals(1, TranslationCache.shared.size())

        OnlineServiceMutations.applyReportedThinking("ds", ReportedThinking("deepseek-v4-pro", levels, now))
        assertEquals("deepseek-flash", OnlineServiceStore.byId("ds")!!.reportedThinking!!.model)
        assertEquals(1, TranslationCache.shared.size())
    }
}
