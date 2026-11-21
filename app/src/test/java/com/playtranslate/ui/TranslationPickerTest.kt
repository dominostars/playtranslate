package com.playtranslate.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.allViews
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.R
import com.playtranslate.language.ChineseScriptVariant
import com.playtranslate.language.SourceLangId
import com.playtranslate.model.TranslationLangContext
import com.playtranslate.overlayThemedContext
import com.playtranslate.themeColor
import com.playtranslate.translation.FakeOnlineBackend
import com.playtranslate.translation.FakeSwitchedBackend
import com.playtranslate.translation.TranslationBackend
import com.playtranslate.translation.TranslationBackendRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The "Choose translation service" picker ([TranslationPicker]): what it
 * lists, how, and what each tap does.
 *
 * It lists every service set up for the result's pair, the switched-off and
 * the cooling among them and nothing that is not set up, the online ones
 * under one header and the offline ones under another, each group in the
 * order translations try them; a group with nothing in it has no header.
 * The accent marks the service named in the result's credit (both, where
 * two share the name; none, where the credit names nobody). The message,
 * which says what a pick does, stands right over the lists. Past a rule
 * that ends them are the two actions that are not picks: the way to the
 * Translation services page, a button of the services' size drawn as an
 * outline with a gear where they are filled, and Cancel. In a wide window
 * the two lists stand side by side and those two share a row that does not
 * scroll. A tap on a service hands that service to the caller, the credited
 * one like any other; the settings button and Cancel pick nothing.
 *
 * Shown in a host view, as [OcrPickerTest] shows its picker, over a
 * registry of fakes.
 */
@RunWith(RobolectricTestRunner::class)
class TranslationPickerTest {

    private val ctx: Context = overlayThemedContext(ApplicationProvider.getApplicationContext<Context>())
    private val jaToEn = TranslationLangContext(SourceLangId.JA, "en", ChineseScriptVariant.SIMPLIFIED)

    private val picked = mutableListOf<TranslationBackend>()
    private var servicesOpened = 0

    @After fun tearDown() {
        TranslationBackendRegistry.close()
    }

    /** The picker for a result credited to [credit], shown in a host view;
     *  returns the card's labels and buttons in order (the title and the
     *  message, which are neither headers nor buttons, left out). */
    private fun show(credit: String?): List<TextView> {
        val host = FrameLayout(ctx)
        TranslationPicker.populate(
            OverlayAlert.Builder(ctx), ctx, jaToEn, credit,
            onPick = { picked += it },
            onOpenServices = { servicesOpened++ },
        ).showInParent(host)
        val title = ctx.getString(R.string.translation_picker_title)
        val message = ctx.getString(R.string.translation_picker_message)
        return host.allViews.filterIsInstance<TextView>()
            .filter { it.text.toString() != title && it.text.toString() != message }
            .toList()
    }

    private fun List<TextView>.shape(): List<String> =
        map { if (it is Button) "[${it.text}]" else it.text.toString() }

    private fun List<TextView>.button(label: String): Button =
        filterIsInstance<Button>().single { it.text.toString() == label }

    private fun Button.isAccent(): Boolean =
        (background as GradientDrawable).color?.defaultColor == ctx.themeColor(R.attr.ptAccent)

    private val online = ctx.getString(R.string.settings_header_online_translations)
    private val offline = ctx.getString(R.string.settings_header_offline_translations)
    private val services = ctx.getString(R.string.settings_cell_translation_services)
    private val cancel = ctx.getString(R.string.btn_cancel)

    private fun register(vararg backends: TranslationBackend) = TranslationBackendRegistry.init(backends.toList())

    @Test fun `it lists what is set up, online then offline under their headers, in the order translations try them`() {
        register(
            FakeSwitchedBackend(id = "mlkit", priority = 30, displayName = "ML Kit", requiresInternet = false),
            FakeSwitchedBackend(id = "gemini", priority = 20, displayName = "Gemini", enabled = false),
            FakeSwitchedBackend(id = "bergamot", priority = 28, displayName = "Firefox Translations", requiresInternet = false, enabled = false),
            FakeSwitchedBackend(id = "lingva", priority = 10, displayName = "Lingva").apply {
                cooldownState.recordParsedFailure(System.currentTimeMillis() + 60_000, "Rate limited")
            },
            FakeSwitchedBackend(id = "deepl", priority = 15, displayName = "DeepL", setUp = false),
            FakeSwitchedBackend(id = "qwen", priority = 25, displayName = "Qwen", requiresInternet = false, setUp = false),
        )

        val card = show(credit = "Lingva")

        assertEquals(
            "switched off (Gemini, Firefox Translations) and cooling (Lingva) are listed; " +
                "not set up (DeepL without a key, Qwen not downloaded) is not",
            listOf(
                online, "[Lingva]", "[Gemini]",
                offline, "[Firefox Translations]", "[ML Kit]",
                "[$services]", "[$cancel]",
            ),
            card.shape(),
        )
    }

    @Test fun `a group with nothing listed has no header`() {
        register(FakeSwitchedBackend(id = "mlkit", priority = 30, displayName = "ML Kit", requiresInternet = false))
        assertEquals(listOf(offline, "[ML Kit]", "[$services]", "[$cancel]"), show(credit = "ML Kit").shape())

        TranslationBackendRegistry.close()
        register(FakeOnlineBackend(id = "lingva", priority = 10, displayName = "Lingva"))
        assertEquals(listOf(online, "[Lingva]", "[$services]", "[$cancel]"), show(credit = "Lingva").shape())

        TranslationBackendRegistry.close()
        register()
        assertEquals("nothing set up still leaves the way to set one up", listOf("[$services]", "[$cancel]"), show(credit = null).shape())
    }

    @Test fun `the accent marks the service named in the credit`() {
        register(
            FakeSwitchedBackend(id = "lingva", priority = 10, displayName = "Lingva"),
            FakeSwitchedBackend(id = "gemini", priority = 20, displayName = "Gemini"),
            FakeSwitchedBackend(id = "mlkit", priority = 30, displayName = "ML Kit", requiresInternet = false),
        )

        val card = show(credit = "Gemini")

        assertTrue(card.button("Gemini").isAccent())
        assertFalse(card.button("Lingva").isAccent())
        assertFalse(card.button("ML Kit").isAccent())
        assertFalse("the way to the settings is no choice", card.button(services).isAccent())
        assertFalse(card.button(cancel).isAccent())

        assertTrue("a credit that names nobody marks nobody", show(credit = null).filterIsInstance<Button>().none { it.isAccent() })
    }

    @Test fun `two services with one name both take the accent`() {
        register(
            FakeSwitchedBackend(id = "gemini-a", priority = 10, displayName = "Gemini"),
            FakeSwitchedBackend(id = "gemini-b", priority = 20, displayName = "Gemini"),
            FakeSwitchedBackend(id = "lingva", priority = 30, displayName = "Lingva"),
        )

        val buttons = show(credit = "Gemini").filterIsInstance<Button>()

        assertEquals(listOf("Gemini", "Gemini", "Lingva"), buttons.take(3).map { it.text.toString() })
        assertEquals(listOf(true, true, false), buttons.take(3).map { it.isAccent() })
    }

    @Test fun `a tap on a service hands over that service, the credited one included`() {
        val lingva = FakeSwitchedBackend(id = "lingva", priority = 10, displayName = "Lingva")
        val gemini = FakeSwitchedBackend(id = "gemini", priority = 20, displayName = "Gemini", enabled = false)
        register(lingva, gemini)

        show(credit = "Lingva").button("Gemini").performClick()
        assertEquals("a switched-off service is picked like any other", listOf<TranslationBackend>(gemini), picked)

        show(credit = "Lingva").button("Lingva").performClick()
        assertEquals("the credited service is asked again, not passed over", listOf<TranslationBackend>(gemini, lingva), picked)
        assertEquals(0, servicesOpened)
        assertEquals("a pick translates nothing here: that is the caller's", 0, lingva.translateCalls.get() + gemini.translateCalls.get())
    }

    @Test fun `the settings button is of the services' size and drawn unlike any of them`() {
        register(
            FakeSwitchedBackend(id = "lingva", priority = 10, displayName = "Lingva"),
            FakeSwitchedBackend(id = "gemini", priority = 20, displayName = "Gemini"),
        )

        val card = show(credit = "Lingva")
        val settings = card.button(services)
        val credited = card.button("Lingva")
        val other = card.button("Gemini")

        fun fill(b: Button) = (b.background as GradientDrawable).color?.defaultColor
        assertEquals("no fill: an outline", android.graphics.Color.TRANSPARENT, fill(settings))
        assertEquals("a service that is not credited is filled", ctx.themeColor(R.attr.ptDivider), fill(other))
        assertEquals(ctx.themeColor(R.attr.ptAccent), fill(credited))
        assertTrue("it carries the gear, at its start", settings.compoundDrawablesRelative[0] != null)
        assertEquals("the same text size as a service", other.textSize, settings.textSize)
        assertEquals(
            "and the same width: the column's",
            (other.layoutParams as android.view.ViewGroup.LayoutParams).width,
            (settings.layoutParams as android.view.ViewGroup.LayoutParams).width,
        )
        assertEquals(ctx.themeColor(R.attr.ptText), settings.currentTextColor)
    }

    @Test fun `the message stands right over the lists, and a rule sets the two actions off under them`() {
        register(
            FakeSwitchedBackend(id = "lingva", priority = 10, displayName = "Lingva"),
            FakeSwitchedBackend(id = "mlkit", priority = 30, displayName = "ML Kit", requiresInternet = false),
        )

        val card = show(credit = "Lingva")
        val column = card.button(cancel).parent as android.view.ViewGroup
        val children = (0 until column.childCount).map { column.getChildAt(it) }
        fun after(v: View) = children[children.indexOf(v) + 1]
        fun before(v: View) = children[children.indexOf(v) - 1]
        val message = children.filterIsInstance<TextView>()
            .single { it.text.toString() == ctx.getString(R.string.translation_picker_message) }

        assertEquals(
            "what the message explains is what comes next: the lists, not a button",
            online, (after(message) as TextView).text.toString(),
        )
        val rule = before(card.button(services))
        assertFalse("a line, not a label or a button", rule is TextView)
        assertEquals(ctx.themeColor(R.attr.ptDivider), (rule.background as android.graphics.drawable.ColorDrawable).color)
        assertEquals("the last service is what the rule closes", "ML Kit", (before(rule) as Button).text.toString())
        assertEquals("then the way to the settings, then Cancel", cancel, (after(card.button(services)) as Button).text.toString())
    }

    @Test
    @org.robolectric.annotation.Config(qualifiers = "w800dp-h360dp-land")
    fun `in a wide window the two lists stand side by side and the two actions share a row that does not scroll`() {
        register(
            FakeSwitchedBackend(id = "lingva", priority = 10, displayName = "Lingva"),
            FakeSwitchedBackend(id = "gemini", priority = 20, displayName = "Gemini", enabled = false),
            FakeSwitchedBackend(id = "mlkit", priority = 30, displayName = "ML Kit", requiresInternet = false),
        )

        val card = show(credit = "Lingva")

        assertEquals(
            "the same things in the same reading order",
            listOf(online, "[Lingva]", "[Gemini]", offline, "[ML Kit]", "[$services]", "[$cancel]"),
            card.shape(),
        )
        fun scrolls(v: View) = generateSequence(v.parent) { it.parent }.any { it is android.widget.ScrollView }
        val onlineColumn = card.button("Lingva").parent as android.widget.LinearLayout
        val offlineColumn = card.button("ML Kit").parent as android.widget.LinearLayout
        assertTrue("each list a column of its own", onlineColumn !== offlineColumn)
        assertEquals("Gemini is in Lingva's", onlineColumn, card.button("Gemini").parent)
        val lists = onlineColumn.parent as android.widget.LinearLayout
        assertEquals("the columns share a row", lists, offlineColumn.parent)
        assertEquals(android.widget.LinearLayout.HORIZONTAL, lists.orientation)
        assertTrue(scrolls(card.button("Lingva")) && scrolls(card.button("ML Kit")))

        val actions = card.button(services).parent as android.widget.LinearLayout
        assertEquals("one row for the two actions", actions, card.button(cancel).parent)
        assertEquals(android.widget.LinearLayout.HORIZONTAL, actions.orientation)
        assertFalse("kept on screen under the lists", scrolls(card.button(services)) || scrolls(card.button(cancel)))

        card.button("Gemini").performClick()
        assertEquals("a pick is a pick in either arrangement", listOf("gemini"), picked.map { it.id })
    }

    @Test fun `the settings button opens Translation services and Cancel does nothing`() {
        register(FakeSwitchedBackend(id = "lingva", priority = 10, displayName = "Lingva"))

        val card = show(credit = "Lingva")
        card.button(services).performClick()
        assertEquals(1, servicesOpened)

        show(credit = "Lingva").button(cancel).performClick()
        assertEquals(1, servicesOpened)
        assertTrue(picked.isEmpty())
    }

    @Test fun `a header is a label, not a button, and stands at the start of the card`() {
        register(FakeSwitchedBackend(id = "lingva", priority = 10, displayName = "Lingva"))

        val header = show(credit = null).first { it.text.toString() == online }

        assertFalse(header is Button)
        assertFalse(header.isClickable)
        assertEquals(View.VISIBLE, header.visibility)
        assertEquals(android.view.Gravity.START, header.gravity and android.view.Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK)
        assertEquals(ctx.themeColor(R.attr.ptTextMuted), header.currentTextColor)
    }
}
