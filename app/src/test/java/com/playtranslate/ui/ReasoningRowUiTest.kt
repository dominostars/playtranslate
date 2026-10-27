package com.playtranslate.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ListView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.materialswitch.MaterialSwitch
import com.playtranslate.Prefs
import com.playtranslate.PtJson
import com.playtranslate.R
import com.playtranslate.TranslationCache
import com.playtranslate.security.SecretCodec
import com.playtranslate.themeColor
import com.playtranslate.translation.OnlineServiceInstance
import com.playtranslate.translation.OnlineServiceMutations
import com.playtranslate.translation.OnlineServiceStore
import com.playtranslate.translation.OpenAiPreset
import com.playtranslate.translation.ReasoningControls
import com.playtranslate.translation.ReasoningLevel
import com.playtranslate.translation.ReportedThinking
import com.playtranslate.translation.ServiceType
import kotlinx.serialization.json.JsonObject
import com.playtranslate.translation.TranslationBackendRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/**
 * The thinking row on the config page. It is drawn from the provider and the
 * model the page would save, and for DeepSeek, OpenRouter and Claude from what
 * the provider reported about that model (none before a report): a switch
 * where the choice is off or on, segments where it is a level, nothing where
 * nothing can be chosen. It starts where
 * the service runs, saves the pick through the page's instance, follows the
 * Provider dropdown and a report that lands while the page is open, and
 * survives a recreation. A pick the page's control
 * doesn't offer shows as its start state and is saved back unchanged unless
 * the user picks here.
 */
@RunWith(RobolectricTestRunner::class)
class ReasoningRowUiTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

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
        opened.forEach { close(it) }
        TranslationCache.shared.resetForTest()
        OnlineServiceStore.all().forEach { TranslationBackendRegistry.removeOnlineBackend(it.id) }
        TranslationBackendRegistry.setOrder(null)
        sp().edit().clear().commit()
    }

    /** Every page a test opens. A page on screen is registered with the
     *  store ([OnlineServiceStore.addListener]) until it pauses, so each is
     *  closed after its test, and no later test's writes reach it. */
    private val opened = mutableListOf<ActivityController<LlmBackendSettingsActivity>>()

    private fun open(intent: Intent): ActivityController<LlmBackendSettingsActivity> =
        Robolectric.buildActivity(LlmBackendSettingsActivity::class.java, intent).setup().also { opened += it }

    private fun close(page: ActivityController<LlmBackendSettingsActivity>) {
        when (page.get().lifecycle.currentState) {
            Lifecycle.State.RESUMED -> page.pause().stop().destroy()
            Lifecycle.State.STARTED -> page.stop().destroy()
            Lifecycle.State.CREATED -> page.destroy()
            else -> Unit
        }
    }

    private fun openCreate(id: String, type: ServiceType = ServiceType.OPENAI, preset: OpenAiPreset? = null): Activity =
        open(LlmBackendSettingsActivity.createIntent(ctx, id, type, preset)).get()

    private fun openEdit(id: String): Activity = open(LlmBackendSettingsActivity.editIntent(ctx, id)).get()

    private fun Activity.section(): View = findViewById(R.id.sectionReasoning)
    private fun Activity.switchRow(): View = findViewById(R.id.rowThinking)
    private fun Activity.levelsRow(): View = findViewById(R.id.rowReasoningLevels)
    private fun Activity.switch(): MaterialSwitch = switchRow().findViewById(R.id.switchRowToggle)
    private fun Activity.title() = findViewById<MaterialToolbar>(R.id.toolbar).title.toString()
    private fun Activity.save() = findViewById<View>(R.id.btnSave).performClick()

    /** The segments, in order. */
    private fun Activity.segments(): List<TextView> =
        descendants(findViewById<FrameLayout>(R.id.reasoningLevelsContainer)).filterIsInstance<TextView>().toList()

    private fun Activity.segmentLabels() = segments().map { it.text.toString() }

    /** The selected segment's label: its text is drawn in the track's own
     *  color over the accent fill, the others muted. */
    private fun Activity.selectedSegment(): String =
        segments().single { it.currentTextColor == themeColor(R.attr.ptSurface) }.text.toString()

    private fun Activity.tapSegment(label: String) = segments().single { it.text.toString() == label }.performClick()

    /** The switch form is showing, and not the segments. */
    private fun Activity.assertSwitchShown() {
        assertTrue(section().isVisible)
        assertTrue(switchRow().isVisible)
        assertFalse(levelsRow().isVisible)
    }

    /** The segments are showing with [labels], and not the switch. */
    private fun Activity.assertSegmentsShown(labels: List<String>) {
        assertTrue(section().isVisible)
        assertFalse(switchRow().isVisible)
        assertTrue(levelsRow().isVisible)
        assertEquals(labels, segmentLabels())
    }

    private val lowToHigh = listOf("Low", "Medium", "High")

    // ── Forms per provider and model ─────────────────────────────────────

    /** DeepSeek's options come from its catalog: a new service, not yet
     *  asked, shows no row; once DeepSeek reports, its levels show, starting
     *  Off. */
    @Test fun `a DeepSeek page shows the levels DeepSeek reports, starting on Off`() {
        val fresh = openCreate("new-ds", preset = OpenAiPreset.DEEPSEEK)
        assertEquals("DeepSeek", fresh.title())
        assertFalse("not reported yet", fresh.section().isVisible)

        OnlineServiceStore.add(deepSeek("ds", ReasoningLevel.UNSET))
        val page = openEdit("ds")
        page.assertSegmentsShown(listOf("Off", "Low", "High"))
        assertEquals("Off", page.selectedSegment())
        val texts = descendants(page.levelsRow()).filterIsInstance<TextView>().map { it.text.toString() }.toList()
        assertTrue(texts.contains("Thinking level"))
        assertTrue(texts.contains(ctx.getString(R.string.llm_backend_reasoning_subtitle)))
    }

    @Test fun `picking a segment and saving persists the pick`() {
        OnlineServiceStore.add(deepSeek("ds", ReasoningLevel.UNSET))
        val page = openEdit("ds")
        page.tapSegment("Low")
        assertEquals("Low", page.selectedSegment())
        page.save()
        val saved = OnlineServiceStore.byId("ds")!!
        assertEquals(ReasoningLevel.LOW, saved.reasoning)
        assertEquals(OpenAiPreset.DEEPSEEK, saved.preset)
        assertEquals("the save keeps DeepSeek's report", deepSeek("ds", ReasoningLevel.UNSET).reportedThinking, saved.reportedThinking)
        assertFalse("a blank key disables the instance, as before", saved.enabled)
        assertTrue(page.isFinishing)
    }

    /** gpt-6-luna is the case that asked for this row: it starts at Low,
     *  and there is no Default to pick. */
    @Test fun `an OpenAI page on a reasoning model shows Low to High, starting on Low`() {
        OnlineServiceStore.add(openAi("luna", "gpt-6-luna", ReasoningLevel.UNSET))
        val page = openEdit("luna")
        page.assertSegmentsShown(lowToHigh)
        assertEquals("Low", page.selectedSegment())
        page.tapSegment("Medium")
        page.save()
        assertEquals(ReasoningLevel.MEDIUM, OnlineServiceStore.byId("luna")!!.reasoning)
    }

    /** A new OpenAI service is on chat-latest, ChatGPT's Instant model,
     *  which takes no reasoning field. */
    @Test fun `a new OpenAI page, on chat-latest, shows no row`() {
        assertFalse(openCreate("new-openai").section().isVisible)
    }

    /** Claude's options come from its catalog: a switch where the model
     *  reports both an off and an on, no row where it reports no off, and
     *  no row before any report. */
    @Test fun `Claude shows the switch its report allows, and no row before one or where thinking is always on`() {
        OnlineServiceStore.add(claude("sonnet", "claude-sonnet-5", disabled = true))
        val page = openEdit("sonnet")
        page.assertSwitchShown()
        assertFalse(page.switch().isChecked)
        assertEquals("Thinking mode", page.switchRow().findViewById<TextView>(R.id.tvRowTitle).text.toString())
        page.switchRow().performClick()
        assertTrue(page.switch().isChecked)
        page.save()
        assertEquals(ReasoningLevel.ON, OnlineServiceStore.byId("sonnet")!!.reasoning)

        assertFalse("not reported yet", openCreate("new-claude", preset = OpenAiPreset.CLAUDE).section().isVisible)

        OnlineServiceStore.add(claude("opus", "claude-opus-5-5", disabled = false))
        assertFalse(openEdit("opus").section().isVisible)
    }

    /** OpenRouter's report says which models must reason: those get no Off. */
    @Test fun `an OpenRouter model that must reason gets no Off`() {
        OnlineServiceStore.add(
            openRouter("or-flash", "google/gemini-2.5-flash-lite", """{"supported_efforts":["high","medium","low"],"mandatory":false}"""),
        )
        openEdit("or-flash").assertSegmentsShown(listOf("Off") + lowToHigh)
        OnlineServiceStore.add(
            openRouter("or-o3", "openai/o3", """{"supported_efforts":["high","medium","low"],"mandatory":true}"""),
        )
        val mandatory = openEdit("or-o3")
        mandatory.assertSegmentsShown(lowToHigh)
        assertEquals("Low", mandatory.selectedSegment())
    }

    /** Gemini's default model names its lowest level minimal, and starts
     *  there. */
    @Test fun `Gemini and Mistral pages draw their rows`() {
        val gemini = openCreate("new-gemini", type = ServiceType.GEMINI)
        gemini.assertSegmentsShown(listOf("Minimal") + lowToHigh)
        assertEquals("Minimal", gemini.selectedSegment())

        val mistral = openCreate("new-mistral", preset = OpenAiPreset.MISTRAL)
        mistral.assertSwitchShown()
        assertFalse(mistral.switch().isChecked)
    }

    @Test fun `Index-Translate and Custom pages show no row`() {
        assertFalse(openCreate("new-bili", preset = OpenAiPreset.INDEX_TRANSLATE).section().isVisible)
        val page = openCreate("new-custom")
        pickProvider(page, OpenAiPreset.CUSTOM)
        assertFalse(page.section().isVisible)
    }

    // ── The pick across provider changes and recreation ──────────────────

    /** The row follows the Provider dropdown. The pick stays the user's:
     *  a provider that offers it shows it, one that doesn't shows its start
     *  state, and coming back finds it. */
    @Test fun `the row follows the provider dropdown and keeps the pick`() {
        OnlineServiceStore.add(openAi("luna", "gpt-6-luna", ReasoningLevel.MEDIUM))
        val page = openEdit("luna")
        assertEquals("Medium", page.selectedSegment())

        // Groq's default model, gpt-oss-20b, offers Medium too.
        pickProvider(page, OpenAiPreset.GROQ)
        assertEquals("Groq", page.title())
        page.assertSegmentsShown(lowToHigh)
        assertEquals("Medium", page.selectedSegment())

        // DeepSeek hasn't reported about its default model: no row.
        pickProvider(page, OpenAiPreset.DEEPSEEK)
        assertFalse(page.section().isVisible)

        pickProvider(page, OpenAiPreset.MISTRAL)
        page.assertSwitchShown()
        assertFalse("MEDIUM reads as Mistral's start state", page.switch().isChecked)

        pickProvider(page, OpenAiPreset.OPENAI)
        page.assertSegmentsShown(lowToHigh)
        assertEquals("Medium", page.selectedSegment())
        page.save()
        val saved = OnlineServiceStore.byId("luna")!!
        assertEquals(OpenAiPreset.OPENAI, saved.preset)
        assertEquals("gpt-6-luna", saved.model)
        assertEquals(ReasoningLevel.MEDIUM, saved.reasoning)
    }

    /** A pick the new provider doesn't offer is saved back unchanged when
     *  the user doesn't pick there, so it is waiting where it applies. */
    @Test fun `switching provider and saving keeps a pick the new provider doesn't offer`() {
        OnlineServiceStore.add(deepSeek("ds", ReasoningLevel.HIGH))
        val page = openEdit("ds")
        pickProvider(page, OpenAiPreset.MISTRAL)
        page.save()
        val saved = OnlineServiceStore.byId("ds")!!
        assertEquals(OpenAiPreset.MISTRAL, saved.preset)
        assertEquals(ReasoningLevel.HIGH, saved.reasoning)
    }

    /** A recreation (a rotation mid-edit) keeps the page's unsaved pick: the
     *  segments are rebuilt from it, and the switch row saves no view state
     *  of its own. */
    @Test fun `an unsaved pick survives recreation in either form`() {
        OnlineServiceStore.add(openAi("luna", "gpt-6-luna", ReasoningLevel.UNSET))
        val levelsPage = open(LlmBackendSettingsActivity.editIntent(ctx, "luna"))
        levelsPage.get().tapSegment("High")
        levelsPage.recreate()
        assertEquals("High", levelsPage.get().selectedSegment())
        levelsPage.get().save()
        assertEquals(ReasoningLevel.HIGH, OnlineServiceStore.byId("luna")!!.reasoning)

        OnlineServiceStore.add(claude("sonnet", "claude-sonnet-5", disabled = true))
        val switchPage = open(LlmBackendSettingsActivity.editIntent(ctx, "sonnet"))
        switchPage.get().switchRow().performClick()
        switchPage.recreate()
        assertTrue("the flip, not the record", switchPage.get().switch().isChecked)
        switchPage.get().save()
        assertEquals(ReasoningLevel.ON, OnlineServiceStore.byId("sonnet")!!.reasoning)
    }

    /** The Provider pick survives too, and the row that follows it. */
    @Test fun `an unsaved provider pick survives recreation`() {
        OnlineServiceStore.add(deepSeek("ds", ReasoningLevel.UNSET))
        val controller = open(LlmBackendSettingsActivity.editIntent(ctx, "ds"))
        pickProvider(controller.get(), OpenAiPreset.MISTRAL)
        assertEquals("Mistral", controller.get().title())

        controller.recreate()
        val page = controller.get()
        assertEquals("Mistral", page.title())
        page.assertSwitchShown()
        page.save()
        assertEquals(OpenAiPreset.MISTRAL, OnlineServiceStore.byId("ds")!!.preset)
    }

    // ── Frozen while the key is checked ──────────────────────────────────

    /** The instance under validation is read off the page when Save is
     *  tapped, and the probe then suspends; every input is frozen until it
     *  answers, so what the screen shows is what gets saved. The probe goes
     *  to a loopback port nobody listens on, so no network is touched, and
     *  the page is destroyed before the answer lands, which cancels the save. */
    @Test fun `every input is frozen while the key is validated`() {
        val controller = open(LlmBackendSettingsActivity.createIntent(ctx, "new-custom", ServiceType.OPENAI))
        val page = controller.get()
        pickProvider(page, OpenAiPreset.CUSTOM)
        page.findViewById<EditText>(R.id.etBaseUrl).setText("http://127.0.0.1:1")
        page.findViewById<EditText>(R.id.etApiKey).setText("sk-test")
        page.save()

        assertTrue(page.findViewById<View>(R.id.progressSave).isVisible)
        for (id in listOf(R.id.etApiKey, R.id.btnSave, R.id.rowProvider, R.id.etBaseUrl, R.id.rowThinking)) {
            assertFalse(page.resources.getResourceEntryName(id), page.findViewById<View>(id).isEnabled)
        }
        assertFalse(page.switch().isEnabled)

        controller.pause().stop().destroy()
        assertNull(OnlineServiceStore.byId("new-custom"))
    }

    /** The segments freeze with the rest of the page. Every page that shows
     *  them probes a real provider host on Save, so the loading state is set
     *  directly here rather than through a probe. */
    @Test fun `the segments freeze with the page while the key is validated`() {
        val page = openCreate("new-gemini", type = ServiceType.GEMINI)
        assertTrue(page.segments().isNotEmpty())
        page.setLoading(true)
        page.segments().forEach { assertFalse(it.text.toString(), it.isEnabled) }
        page.setLoading(false)
        page.segments().forEach { assertTrue(it.text.toString(), it.isEnabled) }
    }

    // ── A report that lands while the page is open ───────────────────────

    /** The refresher asks at launch and after a change to the service, so a
     *  report can land with the page open: the row it allows shows then,
     *  and what the user typed stays. */
    @Test fun `a report that lands while the page is open draws the row`() {
        OnlineServiceStore.add(deepSeek("ds", ReasoningLevel.UNSET, reported = false))
        val page = openEdit("ds")
        assertFalse("not reported yet", page.section().isVisible)
        page.findViewById<EditText>(R.id.etApiKey).setText("sk-typed")

        OnlineServiceMutations.applyReportedThinking("ds", deepSeekReport)
        page.assertSegmentsShown(listOf("Off", "Low", "High"))
        assertEquals("Off", page.selectedSegment())
        assertEquals("sk-typed", page.findViewById<EditText>(R.id.etApiKey).text.toString())
    }

    /** The pick is the page's own: a report that takes Off away redraws the
     *  row with the picked Medium still selected, and the save keeps it. */
    @Test fun `a redraw keeps the page's unsaved pick`() {
        OnlineServiceStore.add(
            openRouter("or", "google/gemini-2.5-flash-lite", """{"supported_efforts":["high","medium","low"],"mandatory":false}"""),
        )
        val page = openEdit("or")
        page.tapSegment("Medium")
        OnlineServiceMutations.applyReportedThinking(
            "or",
            report("google/gemini-2.5-flash-lite", """{"reasoning":{"supported_efforts":["high","medium","low"],"mandatory":true}}"""),
        )
        page.assertSegmentsShown(lowToHigh)
        assertEquals("Medium", page.selectedSegment())
        page.save()
        assertEquals(ReasoningLevel.MEDIUM, OnlineServiceStore.byId("or")!!.reasoning)
    }

    /** The provider is the page's own too: a report about the record's
     *  model leaves a provider switched on the page alone, and shows when
     *  the user switches back. */
    @Test fun `a redraw keeps the page's unsaved provider`() {
        OnlineServiceStore.add(deepSeek("ds", ReasoningLevel.UNSET, reported = false))
        val page = openEdit("ds")
        pickProvider(page, OpenAiPreset.MISTRAL)
        OnlineServiceMutations.applyReportedThinking("ds", deepSeekReport)
        assertEquals("Mistral", page.title())
        page.assertSwitchShown()

        pickProvider(page, OpenAiPreset.DEEPSEEK)
        page.assertSegmentsShown(listOf("Off", "Low", "High"))
    }

    /** While a save checks the key the page is frozen and the report waits;
     *  the answer unfreezes the page and draws it, editable. */
    @Test fun `a report that lands while the key is checked waits for the answer`() {
        OnlineServiceStore.add(deepSeek("ds", ReasoningLevel.UNSET, reported = false))
        val page = openEdit("ds")
        page.setLoading(true)
        OnlineServiceMutations.applyReportedThinking("ds", deepSeekReport)
        assertFalse(page.section().isVisible)

        page.setLoading(false)
        page.assertSegmentsShown(listOf("Off", "Low", "High"))
        page.segments().forEach { assertTrue(it.text.toString(), it.isEnabled) }
    }

    /** A page off screen (the get-key link opened the browser) stops
     *  listening, and catches up when it comes back. */
    @Test fun `a report that lands while the page is away is drawn when it comes back`() {
        OnlineServiceStore.add(deepSeek("ds", ReasoningLevel.UNSET, reported = false))
        val controller = open(LlmBackendSettingsActivity.editIntent(ctx, "ds"))
        controller.pause()
        OnlineServiceMutations.applyReportedThinking("ds", deepSeekReport)
        assertFalse("not listening while away", controller.get().section().isVisible)

        controller.resume()
        controller.get().assertSegmentsShown(listOf("Off", "Low", "High"))
    }

    /** The launch check writes reports for every service. One that leaves a
     *  page's row as it is rebuilds nothing there, so a focused or pressed
     *  segment stays, and touches nothing typed on any page. */
    @Test fun `a write that leaves the row as it is touches nothing on the page`() {
        OnlineServiceStore.add(openAi("luna", "gpt-6-luna", ReasoningLevel.UNSET))
        val page = openEdit("luna")
        val segments = page.segments()
        val custom = openCreate("new-custom")
        pickProvider(custom, OpenAiPreset.CUSTOM)
        custom.findViewById<EditText>(R.id.etBaseUrl).setText("http://192.168.1.5:8080/v1")

        OnlineServiceStore.add(deepSeek("ds", ReasoningLevel.UNSET, reported = false))
        OnlineServiceMutations.applyReportedThinking("ds", deepSeekReport)
        val after = page.segments()
        assertEquals(segments.size, after.size)
        segments.zip(after).forEach { (before, now) -> assertSame(before.text.toString(), before, now) }
        assertEquals("http://192.168.1.5:8080/v1", custom.findViewById<EditText>(R.id.etBaseUrl).text.toString())
    }

    /** Every segment any control offers has a label (ON never is one),
     *  from the tables and from the reports alike. */
    @Test fun `every segment has a label`() {
        val controls = listOf(
            ReasoningControls.of(ServiceType.OPENAI, OpenAiPreset.OPENAI, "gpt-6-luna"),
            ReasoningControls.of(ServiceType.OPENAI, OpenAiPreset.GROQ, "qwen/qwen3.8-27b"),
            ReasoningControls.of(ServiceType.GEMINI, OpenAiPreset.OPENAI, "gemini-flash-lite-latest"),
            deepSeek("ds", ReasoningLevel.UNSET).let {
                ReasoningControls.of(ServiceType.OPENAI, it.preset, it.model, it.reportedThinking)
            },
            openRouter("or", "x/y", """{"supported_efforts":null,"mandatory":true}""").let {
                ReasoningControls.of(ServiceType.OPENAI, it.preset, it.model, it.reportedThinking)
            },
        )
        controls.forEach { control ->
            assertTrue(control.choices.size >= 3)
            assertFalse(control.isSwitch)
            control.choices.forEach { assertTrue(ctx.getString(LlmBackendSettingsActivity.levelLabel(it)).isNotBlank()) }
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun report(model: String, json: String) =
        ReportedThinking(model, PtJson.lenient.parseToJsonElement(json) as JsonObject, fetchedAtMs = 0)

    /** What DeepSeek reported about deepseek-v4-flash: low, high and max. */
    private val deepSeekReport = report("deepseek-v4-flash", """{"effort":{"supported_levels":["low","high","max"]}}""")

    /** A DeepSeek service on deepseek-v4-flash, with [deepSeekReport] where
     *  [reported]. */
    private fun deepSeek(id: String, pick: ReasoningLevel, reported: Boolean = true) = OnlineServiceInstance(
        id = id, type = ServiceType.OPENAI, enabled = true,
        model = "deepseek-v4-flash", preset = OpenAiPreset.DEEPSEEK, reasoning = pick,
        reportedThinking = deepSeekReport.takeIf { reported },
    )

    /** The page's frozen state, set directly: every page whose row comes
     *  from a report probes a real provider host on Save. */
    private fun Activity.setLoading(loading: Boolean) {
        LlmBackendSettingsActivity::class.java
            .getDeclaredMethod("setLoading", Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .invoke(this, loading)
    }

    private fun openAi(id: String, model: String, pick: ReasoningLevel) = OnlineServiceInstance(
        id = id, type = ServiceType.OPENAI, enabled = true, model = model, preset = OpenAiPreset.OPENAI, reasoning = pick,
    )

    /** A Claude service whose model reported adaptive thinking, and an off
     *  through `disabled` where [disabled]. */
    private fun claude(id: String, model: String, disabled: Boolean) = OnlineServiceInstance(
        id = id, type = ServiceType.OPENAI, enabled = true, model = model, preset = OpenAiPreset.CLAUDE,
        reportedThinking = report(
            model,
            """{"thinking":{"supported":true,"types":{"adaptive":{"supported":true},""" +
                """"disabled":{"supported":$disabled},"between_tools":{"supported":false},"enabled":{"supported":false}}}}""",
        ),
    )

    /** An OpenRouter service whose model reported [reasoning]. */
    private fun openRouter(id: String, model: String, reasoning: String) = OnlineServiceInstance(
        id = id, type = ServiceType.OPENAI, enabled = true, model = model, preset = OpenAiPreset.OPENROUTER,
        reportedThinking = report(model, """{"reasoning":$reasoning}"""),
    )

    /** Opens the Provider dropdown (an appcompat PopupMenu, a popup window
     *  holding a list of the menu's items) and taps [preset]'s item. */
    private fun pickProvider(page: Activity, preset: OpenAiPreset) {
        page.findViewById<View>(R.id.rowProvider).performClick()
        val popup = shadowOf(page.application).latestPopupWindow
            ?: error("no popup window after tapping the Provider row")
        val list = descendants(popup.contentView).filterIsInstance<ListView>().first()
        val position = (0 until list.adapter.count).single { i ->
            (list.adapter.getItem(i) as android.view.MenuItem).itemId == preset.ordinal
        }
        list.performItemClick(null, position, list.adapter.getItemId(position))
    }

    private fun descendants(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is ViewGroup) for (i in 0 until root.childCount) yieldAll(descendants(root.getChildAt(i)))
    }
}
