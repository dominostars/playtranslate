package com.playtranslate.ui

import android.app.Activity
import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.appbar.MaterialToolbar
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.security.SecretCodec
import com.playtranslate.translation.OnlineServiceInstance
import com.playtranslate.translation.OnlineServiceStore
import com.playtranslate.translation.OpenAiPreset
import com.playtranslate.translation.ServiceType
import com.playtranslate.translation.TranslationBackendRegistry
import com.playtranslate.ui.AddOnlineServiceActivity.CatalogEntry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Bilibili Index-Translate across the screens a user meets it on: its own
 * row in the add-service picker (it takes no account, so the OpenAI row,
 * which reads "Account required", can't name it), its config page (a note
 * where the key field would be, saved enabled with no probe), the model
 * picker's failure line, and the delete confirmation. Control cells keep
 * the keyed flow as it was.
 */
@RunWith(RobolectricTestRunner::class)
class IndexTranslateServiceUiTest {

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

    /** Saves register live backends in the process-wide registry; drop them
     *  so no later test meets this one's instances. */
    @After fun tearDown() {
        OnlineServiceStore.all().forEach { TranslationBackendRegistry.removeOnlineBackend(it.id) }
        TranslationBackendRegistry.setOrder(null)
        sp().edit().clear().commit()
    }

    // ── Add-service picker ───────────────────────────────────────────────

    @Test fun `the picker offers OpenAI, Gemini, Bilibili Index-Translate, DeepL, Lingva`() {
        assertEquals(
            listOf("OpenAI", "Gemini", "Bilibili Index-Translate", "DeepL", "Lingva"),
            AddOnlineServiceActivity.CATALOG.map { it.title(ctx) },
        )
    }

    @Test fun `its row needs no account and the OpenAI row does not name it`() {
        val entry = AddOnlineServiceActivity.CATALOG.single { it is CatalogEntry.Preset }
        assertEquals(CatalogEntry.Preset(OpenAiPreset.INDEX_TRANSLATE), entry)
        assertEquals("No account required", ctx.getString(entry.account.labelRes))
        assertTrue("LLM chip", entry.type.isLlm)
        assertEquals(
            "DeepSeek, Mistral, Groq, OpenRouter, Claude, Custom",
            AddOnlineServiceActivity.openAiPresetList(ctx),
        )
    }

    @Test fun `the services page Add row names it as its own entry`() {
        assertEquals(
            "OpenAI (DeepSeek, Mistral, Groq, OpenRouter, Claude, Custom), Gemini, " +
                "Bilibili Index-Translate, DeepL, Lingva",
            AddOnlineServiceActivity.CATALOG.joinToString(", ") {
                AddOnlineServiceActivity.catalogTitle(ctx, it)
            },
        )
    }

    /** Under the OpenAI row (its dropdown) or on a row of its own, never
     *  both and never neither. */
    @Test fun `every preset is reachable exactly once`() {
        val ownRows = AddOnlineServiceActivity.CATALOG
            .filterIsInstance<CatalogEntry.Preset>().map { it.preset }.toSet()
        val dropdown = LlmBackendSettingsActivity.providerMenuItems().map { it.second }.toSet()
        OpenAiPreset.entries.forEach { preset ->
            assertTrue(preset.name, (preset in ownRows) xor (preset in dropdown))
        }
    }

    /** With Index-Translate filtered out of the dropdown, a position in the
     *  filtered list would turn "Custom" into Index-Translate. */
    @Test fun `every dropdown item resolves back to its own preset`() {
        val items = LlmBackendSettingsActivity.providerMenuItems()
        items.forEach { (itemId, preset) ->
            assertEquals(preset, LlmBackendSettingsActivity.providerMenuPreset(itemId))
        }
        val custom = items.single { it.second == OpenAiPreset.CUSTOM }.first
        assertEquals(OpenAiPreset.CUSTOM, LlmBackendSettingsActivity.providerMenuPreset(custom))
        assertFalse(items.any { it.second == OpenAiPreset.INDEX_TRANSLATE })
    }

    @Test fun `tapping its row opens the config page pinned to the preset`() {
        val picker = Robolectric.buildActivity(AddOnlineServiceActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val row = rowTitled(picker, "Bilibili Index-Translate")
        assertEquals("No account required", row.findViewById<TextView>(R.id.tvRowSubtitle).text.toString())
        assertFalse(row.findViewById<View>(R.id.tvRowProviders).isVisible)
        assertTrue(row.findViewById<View>(R.id.tvLlmBadge).isVisible)

        row.performClick()
        val started = shadowOf(picker).nextStartedActivity
        assertEquals(LlmBackendSettingsActivity::class.java.name, started.component!!.className)
        assertEquals("OPENAI", started.getStringExtra(LlmBackendSettingsActivity.EXTRA_TYPE))
        assertEquals("INDEX_TRANSLATE", started.getStringExtra(LlmBackendSettingsActivity.EXTRA_PRESET))
    }

    // ── Config page ──────────────────────────────────────────────────────

    private fun openCreate(id: String, preset: OpenAiPreset? = null): Activity =
        Robolectric.buildActivity(
            LlmBackendSettingsActivity::class.java,
            LlmBackendSettingsActivity.createIntent(ctx, id, ServiceType.OPENAI, preset),
        ).setup().get()

    private fun Activity.title() = findViewById<MaterialToolbar>(R.id.toolbar).title.toString()

    @Test fun `CREATE shows the note in place of provider and key, and saves enabled with no probe`() {
        val page = openCreate("new-bili", OpenAiPreset.INDEX_TRANSLATE)
        assertEquals("Bilibili Index-Translate", page.title())
        assertFalse(page.findViewById<View>(R.id.sectionProviderUrl).isVisible)
        assertFalse(page.findViewById<View>(R.id.sectionApiKey).isVisible)
        val note = page.findViewById<TextView>(R.id.tvProviderNote)
        assertTrue(note.isVisible)
        assertEquals(ctx.getString(R.string.llm_backend_index_translate_note), note.text.toString())

        page.findViewById<View>(R.id.btnSave).performClick()
        // Written synchronously: the keyed path saves only after a probe on
        // a coroutine, so a record here proves no probe ran.
        val saved = OnlineServiceStore.byId("new-bili")!!
        assertEquals(ServiceType.OPENAI, saved.type)
        assertEquals(OpenAiPreset.INDEX_TRANSLATE, saved.preset)
        assertTrue(saved.enabled)
        assertEquals("Index-Translate-35B-A3B", saved.model)
        assertEquals("", OnlineServiceStore.readKey("new-bili"))
        assertTrue(page.isFinishing)
    }

    @Test fun `EDIT Save re-enables a disabled instance`() {
        OnlineServiceStore.add(
            OnlineServiceInstance(
                id = "bili", type = ServiceType.OPENAI, enabled = false,
                preset = OpenAiPreset.INDEX_TRANSLATE,
            ),
        )
        val page = Robolectric.buildActivity(
            LlmBackendSettingsActivity::class.java,
            LlmBackendSettingsActivity.editIntent(ctx, "bili"),
        ).setup().get()
        assertTrue(page.findViewById<View>(R.id.tvProviderNote).isVisible)
        assertFalse(page.findViewById<View>(R.id.sectionProviderUrl).isVisible)

        page.findViewById<View>(R.id.btnSave).performClick()
        assertTrue(OnlineServiceStore.byId("bili")!!.enabled)
    }

    @Test fun `a recreated CREATE page keeps its pinned preset`() {
        val controller = Robolectric.buildActivity(
            LlmBackendSettingsActivity::class.java,
            LlmBackendSettingsActivity.createIntent(
                ctx, "new-bili", ServiceType.OPENAI, OpenAiPreset.INDEX_TRANSLATE,
            ),
        ).setup()
        controller.recreate()
        val page = controller.get()
        assertEquals("Bilibili Index-Translate", page.title())
        assertTrue(page.findViewById<View>(R.id.tvProviderNote).isVisible)
    }

    /** Control: OpenAI's own row still opens the keyed page, and a blank key
     *  there still creates nothing. */
    @Test fun `a keyed CREATE with a blank key still creates nothing`() {
        val page = openCreate("new-openai")
        assertEquals("OpenAI", page.title())
        assertTrue(page.findViewById<View>(R.id.sectionProviderUrl).isVisible)
        assertTrue(page.findViewById<View>(R.id.sectionApiKey).isVisible)
        assertFalse(page.findViewById<View>(R.id.tvProviderNote).isVisible)

        page.findViewById<View>(R.id.btnSave).performClick()
        assertNull(OnlineServiceStore.byId("new-openai"))
        assertTrue(page.isFinishing)
    }

    // ── Model picker and delete confirmation ─────────────────────────────

    /** No backend is registered for these ids, so the fetch comes back
     *  empty, the way a failed one does. */
    private fun pickerFailureText(preset: OpenAiPreset): String {
        val id = "picker-$preset"
        OnlineServiceStore.add(
            OnlineServiceInstance(id = id, type = ServiceType.OPENAI, enabled = true, preset = preset),
        )
        val picker = Robolectric.buildActivity(
            LlmModelPickerActivity::class.java,
            LlmModelPickerActivity.newIntent(ctx, id),
        ).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val failures = setOf(
            ctx.getString(R.string.llm_model_picker_fetch_failed),
            ctx.getString(R.string.llm_model_picker_fetch_failed_keyless),
        )
        return texts(picker.findViewById(R.id.modelListContainer)).single { it in failures }
    }

    @Test fun `the model picker tells only a keyed provider to check its key`() {
        assertEquals(
            ctx.getString(R.string.llm_model_picker_fetch_failed_keyless),
            pickerFailureText(OpenAiPreset.INDEX_TRANSLATE),
        )
        assertEquals(
            ctx.getString(R.string.llm_model_picker_fetch_failed),
            pickerFailureText(OpenAiPreset.MISTRAL),
        )
    }

    @Test fun `the delete confirmation mentions a key only where one is stored`() {
        fun message(type: ServiceType, preset: OpenAiPreset = OpenAiPreset.OPENAI) =
            removeConfirmationMessage(
                OnlineServiceInstance(id = "x", type = type, enabled = true, preset = preset),
            )
        assertEquals(R.string.tr_service_remove_message_keyless, message(ServiceType.OPENAI, OpenAiPreset.INDEX_TRANSLATE))
        assertEquals(R.string.tr_service_remove_message_keyless, message(ServiceType.LINGVA))
        assertEquals(R.string.tr_service_remove_message, message(ServiceType.OPENAI))
        assertEquals(R.string.tr_service_remove_message, message(ServiceType.GEMINI))
        assertEquals(R.string.tr_service_remove_message, message(ServiceType.DEEPL))
    }

    // ── View helpers ─────────────────────────────────────────────────────

    private fun descendants(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is ViewGroup) for (i in 0 until root.childCount) yieldAll(descendants(root.getChildAt(i)))
    }

    private fun texts(root: View): List<String> =
        descendants(root).filterIsInstance<TextView>().map { it.text.toString() }.toList()

    /** The clickable row whose title is [title]. */
    private fun rowTitled(activity: Activity, title: String): View =
        descendants(activity.findViewById(R.id.serviceListContainer)).single { view ->
            view.hasOnClickListeners() &&
                view.findViewById<TextView>(R.id.tvRowTitle)?.text?.toString() == title
        }
}
