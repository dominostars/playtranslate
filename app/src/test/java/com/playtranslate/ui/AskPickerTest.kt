package com.playtranslate.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.allViews
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.R
import com.playtranslate.overlayThemedContext
import com.playtranslate.themeColor
import com.playtranslate.translation.FakeAnswererBackend
import com.playtranslate.translation.FakeCompleterBackend
import com.playtranslate.translation.FakeOfflineBackend
import com.playtranslate.translation.FakeOnlineBackend
import com.playtranslate.translation.TranslationBackend
import com.playtranslate.translation.TranslationBackendRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The "Choose who answers" picker ([AskPicker]): what it lists, how, and
 * what each tap does. The twin of [TranslationPickerTest], whose card it
 * shares; pinned here is what is this picker's own.
 *
 * It lists every service set up to answer a question, the switched-off and
 * the cooling among them, and nothing that only translates or is not set
 * up, the online ones under one header and the on-device ones under
 * another, each group in the order questions try them; a group with nothing
 * in it has no header. Its title, its message and its two headers are its
 * own, not the translation picker's. The accent marks the service named in
 * the answer's credit. Past the lists are the way to the Translation
 * services page and Cancel. A tap on a service hands that service to the
 * caller, the credited one like any other, and asks nobody anything here.
 *
 * Shown in a host view over a registry of fakes.
 */
@RunWith(RobolectricTestRunner::class)
class AskPickerTest {

    private val ctx: Context = overlayThemedContext(ApplicationProvider.getApplicationContext<Context>())

    private val picked = mutableListOf<TranslationBackend>()
    private var servicesOpened = 0

    @After fun tearDown() {
        TranslationBackendRegistry.close()
    }

    /** The picker for an answer credited to [credit], shown in a host view:
     *  every text on the card, in order. */
    private fun show(credit: String?): List<TextView> {
        val host = FrameLayout(ctx)
        AskPicker.populate(
            OverlayAlert.Builder(ctx), ctx, credit,
            onPick = { picked += it },
            onOpenServices = { servicesOpened++ },
        ).showInParent(host)
        return host.allViews.filterIsInstance<TextView>().toList()
    }

    private fun List<TextView>.shape(): List<String> =
        map { if (it is Button) "[${it.text}]" else it.text.toString() }

    private fun List<TextView>.button(label: String): Button =
        filterIsInstance<Button>().single { it.text.toString() == label }

    private fun Button.isAccent(): Boolean =
        (background as GradientDrawable).color?.defaultColor == ctx.themeColor(R.attr.ptAccent)

    private val title = ctx.getString(R.string.ask_picker_title)
    private val message = ctx.getString(R.string.ask_picker_message)
    private val online = ctx.getString(R.string.ask_picker_header_online)
    private val offline = ctx.getString(R.string.ask_picker_header_offline)
    private val services = ctx.getString(R.string.settings_cell_translation_services)
    private val cancel = ctx.getString(R.string.btn_cancel)

    private fun register(vararg backends: TranslationBackend) = TranslationBackendRegistry.init(backends.toList())

    private fun off(id: String, priority: Int, name: String, online: Boolean = true) = FakeCompleterBackend(
        id = id, priority = priority, displayName = name, requiresInternet = online, canComplete = false, setUp = true,
    )

    @Test fun `it says what it is in its own words, the message right over the lists`() {
        register(FakeCompleterBackend(id = "gemini", priority = 10, displayName = "Gemini"))

        val card = show(credit = "Gemini").shape()

        assertEquals(listOf(title, message, online, "[Gemini]", "[$services]", "[$cancel]"), card)
        assertNotEquals("not the translation picker's title", ctx.getString(R.string.translation_picker_title), title)
        assertNotEquals(ctx.getString(R.string.translation_picker_message), message)
        assertNotEquals(
            "a list of answerers is not headed as translations",
            ctx.getString(R.string.settings_header_online_translations), online,
        )
        assertNotEquals(ctx.getString(R.string.settings_header_offline_translations), offline)
    }

    @Test fun `it lists who is set up to answer, online then on the device, in the order questions try them`() {
        register(
            FakeCompleterBackend(id = "qwen", priority = 40, displayName = "Qwen 3.5", requiresInternet = false),
            off("gemma", 45, "Gemma", online = false),
            FakeCompleterBackend(id = "gemini", priority = 20, displayName = "Gemini"),
            off("claude", 30, "Claude"),
            FakeCompleterBackend(id = "openai", priority = 10, displayName = "OpenAI").apply {
                cooldownState.recordParsedFailure(System.currentTimeMillis() + 60_000, "Rate limited")
            },
            FakeAnswererBackend(id = "cloud", priority = 5, displayName = "PlayTranslate Cloud"),
            FakeAnswererBackend(id = "cloud-off", priority = 6, displayName = "Cloud, off", canAnswer = false),
            FakeCompleterBackend(id = "no-key", priority = 15, displayName = "No key", canComplete = false),
            FakeCompleterBackend(
                id = "not-downloaded", priority = 50, displayName = "Not downloaded",
                requiresInternet = false, canComplete = false,
            ),
            FakeOnlineBackend(id = "deepl", priority = 1, displayName = "DeepL"),
            FakeOfflineBackend(id = "mlkit", priority = 60, displayName = "ML Kit"),
        )

        assertEquals(
            "switched off (Claude, Gemma) and cooling (OpenAI) are listed; a service that only translates " +
                "(DeepL, ML Kit), one with no key, a model not downloaded and an answerer that is off are not",
            listOf(
                title, message,
                online, "[PlayTranslate Cloud]", "[OpenAI]", "[Gemini]", "[Claude]",
                offline, "[Qwen 3.5]", "[Gemma]",
                "[$services]", "[$cancel]",
            ),
            show(credit = "Gemini").shape(),
        )
    }

    @Test fun `a group with nothing listed has no header`() {
        register(FakeCompleterBackend(id = "qwen", priority = 40, displayName = "Qwen 3.5", requiresInternet = false))
        assertEquals(listOf(title, message, offline, "[Qwen 3.5]", "[$services]", "[$cancel]"), show("Qwen 3.5").shape())

        TranslationBackendRegistry.close()
        register(FakeOnlineBackend(id = "deepl", priority = 1, displayName = "DeepL"))
        assertEquals(
            "nobody to ask still leaves the way to set one up",
            listOf(title, message, "[$services]", "[$cancel]"),
            show(credit = null).shape(),
        )
    }

    @Test fun `the accent marks the service named in the answer's credit`() {
        register(
            FakeCompleterBackend(id = "gemini", priority = 10, displayName = "Gemini"),
            off("claude", 20, "Claude"),
            FakeCompleterBackend(id = "qwen", priority = 30, displayName = "Qwen 3.5", requiresInternet = false),
        )

        val card = show(credit = "Claude")

        assertTrue(card.button("Claude").isAccent())
        assertFalse(card.button("Gemini").isAccent())
        assertFalse(card.button("Qwen 3.5").isAccent())
        assertFalse("the way to the settings is no choice", card.button(services).isAccent())
        assertFalse(card.button(cancel).isAccent())
        assertTrue("a credit that names nobody listed marks nobody", show("Removed").filterIsInstance<Button>().none { it.isAccent() })
    }

    @Test fun `a tap on a service hands over that service, the credited one included, and asks nobody here`() {
        val gemini = FakeCompleterBackend(id = "gemini", priority = 10, displayName = "Gemini")
        val claude = off("claude", 20, "Claude")
        register(gemini, claude)

        show(credit = "Gemini").button("Claude").performClick()
        assertEquals("a switched-off service is picked like any other", listOf<TranslationBackend>(claude), picked)

        show(credit = "Gemini").button("Gemini").performClick()
        assertEquals("the credited service is asked again, not passed over", listOf<TranslationBackend>(claude, gemini), picked)
        assertEquals(0, servicesOpened)
        assertEquals("a pick asks nothing here: that is the caller's", 0, gemini.completeCalls.get() + claude.completeCalls.get())
    }

    @Test fun `the settings button and Cancel pick nothing`() {
        register(FakeCompleterBackend(id = "gemini", priority = 10, displayName = "Gemini"))

        show(credit = "Gemini").button(services).performClick()
        assertEquals(1, servicesOpened)
        show(credit = "Gemini").button(cancel).performClick()

        assertTrue(picked.isEmpty())
        assertEquals(1, servicesOpened)
    }
}
