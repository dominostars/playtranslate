package com.playtranslate.ui

import android.app.Activity
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.materialswitch.MaterialSwitch
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.security.SecretCodec
import com.playtranslate.translation.OnlineServiceInstance
import com.playtranslate.translation.OnlineServiceStore
import com.playtranslate.translation.OpenAiPreset
import com.playtranslate.translation.ServiceType
import com.playtranslate.translation.TranslationBackendRegistry
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
 * The Thinking switch on the config page: shown on DeepSeek's page only, off
 * for a new instance, set from the saved record, saved through the page's
 * instance, and following the Provider dropdown. DeepSeek's API thinks by
 * default when a request says nothing (issue #41), so the page's default is
 * the app's answer to that.
 */
@RunWith(RobolectricTestRunner::class)
class DeepSeekThinkingSwitchUiTest {

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
        OnlineServiceStore.all().forEach { TranslationBackendRegistry.removeOnlineBackend(it.id) }
        TranslationBackendRegistry.setOrder(null)
        sp().edit().clear().commit()
    }

    private fun openCreate(id: String, type: ServiceType = ServiceType.OPENAI, preset: OpenAiPreset? = null): Activity =
        Robolectric.buildActivity(
            LlmBackendSettingsActivity::class.java,
            LlmBackendSettingsActivity.createIntent(ctx, id, type, preset),
        ).setup().get()

    private fun openEdit(id: String): Activity =
        Robolectric.buildActivity(
            LlmBackendSettingsActivity::class.java,
            LlmBackendSettingsActivity.editIntent(ctx, id),
        ).setup().get()

    private fun Activity.section(): View = findViewById(R.id.sectionThinking)
    private fun Activity.row(): View = findViewById(R.id.rowThinking)
    private fun Activity.switch(): MaterialSwitch = row().findViewById(R.id.switchRowToggle)
    private fun Activity.title() = findViewById<MaterialToolbar>(R.id.toolbar).title.toString()

    @Test fun `a new DeepSeek page shows the switch off, titled and explained`() {
        val page = openCreate("new-ds", preset = OpenAiPreset.DEEPSEEK)
        assertEquals("DeepSeek", page.title())
        assertTrue(page.section().isVisible)
        assertFalse(page.switch().isChecked)
        assertEquals("Thinking mode", page.row().findViewById<TextView>(R.id.tvRowTitle).text.toString())
        val subtitle = page.row().findViewById<TextView>(R.id.tvRowSubtitle)
        assertTrue(subtitle.isVisible)
        assertEquals(ctx.getString(R.string.llm_backend_thinking_subtitle), subtitle.text.toString())
    }

    /** Every other page hides it: the presets that decide thinking for the
     *  user, and Gemini, which has no preset at all. */
    @Test fun `no other page shows the switch`() {
        OpenAiPreset.entries.filter { !it.thinkingSwitch }.forEach { preset ->
            val page = openCreate("new-$preset", preset = preset)
            assertFalse(preset.name, page.section().isVisible)
        }
        assertFalse("Gemini", openCreate("new-gemini", type = ServiceType.GEMINI).section().isVisible)
    }

    @Test fun `an EDIT page sets the switch from the record`() {
        OnlineServiceStore.add(deepSeek("ds-on", thinking = true))
        OnlineServiceStore.add(deepSeek("ds-off", thinking = false))
        assertTrue(openEdit("ds-on").switch().isChecked)
        assertFalse(openEdit("ds-off").switch().isChecked)
    }

    /** Tapping the row flips the switch; Save writes it. With no key on the
     *  page the keyed save path persists synchronously (disabled, keeping
     *  the page's settings), which is what lets this test see the record. */
    @Test fun `tapping the row and saving persists the switch`() {
        OnlineServiceStore.add(deepSeek("ds", thinking = false))
        val page = openEdit("ds")
        page.row().performClick()
        assertTrue(page.switch().isChecked)
        page.findViewById<View>(R.id.btnSave).performClick()
        val saved = OnlineServiceStore.byId("ds")!!
        assertTrue(saved.thinking)
        assertEquals(OpenAiPreset.DEEPSEEK, saved.preset)
        assertFalse("a blank key disables the instance, as before", saved.enabled)
        assertTrue(page.isFinishing)

        // And back off.
        val again = openEdit("ds")
        assertTrue(again.switch().isChecked)
        again.row().performClick()
        again.findViewById<View>(R.id.btnSave).performClick()
        assertFalse(OnlineServiceStore.byId("ds")!!.thinking)
    }

    /** The row follows the Provider dropdown: gone on OpenAI, back on
     *  DeepSeek, and the switch keeps its state across the trip, which is
     *  what the saved record keeps too. */
    @Test fun `the row follows the provider dropdown and keeps its state`() {
        OnlineServiceStore.add(deepSeek("ds", thinking = true))
        val page = openEdit("ds")
        assertTrue(page.section().isVisible)
        assertTrue(page.switch().isChecked)

        pickProvider(page, OpenAiPreset.OPENAI)
        assertEquals("OpenAI", page.title())
        assertFalse(page.section().isVisible)

        pickProvider(page, OpenAiPreset.DEEPSEEK)
        assertEquals("DeepSeek", page.title())
        assertTrue(page.section().isVisible)
        assertTrue(page.switch().isChecked)
    }

    /** A preset without the switch saves the flag as it stands, so the
     *  choice is waiting when the user switches back. */
    @Test fun `switching provider keeps the saved flag`() {
        OnlineServiceStore.add(deepSeek("ds", thinking = true))
        val page = openEdit("ds")
        pickProvider(page, OpenAiPreset.MISTRAL)
        page.findViewById<View>(R.id.btnSave).performClick()
        val saved = OnlineServiceStore.byId("ds")!!
        assertEquals(OpenAiPreset.MISTRAL, saved.preset)
        assertTrue(saved.thinking)
    }

    /** A recreation (a rotation mid-edit) keeps the page's unsaved state:
     *  the switch row's layout saves no view state of its own, so the
     *  flip would otherwise come back from the record, visibly undone. */
    @Test fun `an unsaved Thinking flip survives recreation`() {
        OnlineServiceStore.add(deepSeek("ds", thinking = true))
        val controller = Robolectric.buildActivity(
            LlmBackendSettingsActivity::class.java,
            LlmBackendSettingsActivity.editIntent(ctx, "ds"),
        ).setup()
        controller.get().row().performClick()
        assertFalse(controller.get().switch().isChecked)

        controller.recreate()
        val page = controller.get()
        assertFalse("the flip, not the record", page.switch().isChecked)
        page.findViewById<View>(R.id.btnSave).performClick()
        assertFalse(OnlineServiceStore.byId("ds")!!.thinking)
    }

    /** The Provider pick had the same gap: the page rebuilt it from the
     *  record. Now the pick survives, and the row that follows it too. */
    @Test fun `an unsaved provider pick survives recreation`() {
        OnlineServiceStore.add(deepSeek("ds", thinking = true))
        val controller = Robolectric.buildActivity(
            LlmBackendSettingsActivity::class.java,
            LlmBackendSettingsActivity.editIntent(ctx, "ds"),
        ).setup()
        pickProvider(controller.get(), OpenAiPreset.MISTRAL)
        assertEquals("Mistral", controller.get().title())

        controller.recreate()
        val page = controller.get()
        assertEquals("Mistral", page.title())
        assertFalse(page.section().isVisible)
        page.findViewById<View>(R.id.btnSave).performClick()
        assertEquals(OpenAiPreset.MISTRAL, OnlineServiceStore.byId("ds")!!.preset)
    }

    /** The instance under validation is read off the page when Save is
     *  tapped, and the probe then suspends; every input is frozen until it
     *  answers, so what the screen shows is what gets saved. The Thinking
     *  row joins the key field here, as do the Provider row and the URL
     *  field, which had the same window before. The probe goes to a
     *  loopback port nobody listens on, so no network is touched, and the
     *  page is destroyed before the answer lands, which cancels the save. */
    @Test fun `every input is frozen while the key is validated`() {
        val controller = Robolectric.buildActivity(
            LlmBackendSettingsActivity::class.java,
            LlmBackendSettingsActivity.createIntent(ctx, "new-custom", ServiceType.OPENAI),
        ).setup()
        val page = controller.get()
        pickProvider(page, OpenAiPreset.CUSTOM)
        page.findViewById<EditText>(R.id.etBaseUrl).setText("http://127.0.0.1:1")
        page.findViewById<EditText>(R.id.etApiKey).setText("sk-test")
        page.findViewById<View>(R.id.btnSave).performClick()

        assertTrue(page.findViewById<View>(R.id.progressSave).isVisible)
        for (id in listOf(R.id.etApiKey, R.id.btnSave, R.id.rowProvider, R.id.etBaseUrl, R.id.rowThinking)) {
            assertFalse(page.resources.getResourceEntryName(id), page.findViewById<View>(id).isEnabled)
        }
        assertFalse(page.switch().isEnabled)

        controller.pause().stop().destroy()
        assertNull(OnlineServiceStore.byId("new-custom"))
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun deepSeek(id: String, thinking: Boolean) = OnlineServiceInstance(
        id = id, type = ServiceType.OPENAI, enabled = true,
        model = "deepseek-v4-flash", preset = OpenAiPreset.DEEPSEEK, thinking = thinking,
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
