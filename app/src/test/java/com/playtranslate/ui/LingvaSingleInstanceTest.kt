package com.playtranslate.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.appbar.MaterialToolbar
import com.playtranslate.R
import com.playtranslate.TranslationCache
import com.playtranslate.cloud.PtCloudService
import com.playtranslate.cloud.PtCloudTestSupport
import com.playtranslate.translation.CooldownState
import com.playtranslate.translation.OnlineServiceInstance
import com.playtranslate.translation.OnlineServiceMutations
import com.playtranslate.translation.OnlineServiceStore
import com.playtranslate.translation.OpenAiPreset
import com.playtranslate.translation.ServiceType
import com.playtranslate.translation.TranslationBackendRegistry
import com.playtranslate.ui.AddOnlineServiceActivity.CatalogEntry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/**
 * A list holds at most one Lingva: an instance has nothing to set, so a
 * second one is the first over again. The add-service picker draws no Lingva
 * row while the list holds one, a tap on its row adds one only if the list
 * still holds none, and the services page's Add row names what the picker
 * offers, rebound on a return to the page and on leaving edit mode. Every
 * other row stays offered beside instances of its own, which can differ.
 *
 * The store starts as a fresh install's: the one migrated Lingva, on.
 */
@RunWith(RobolectricTestRunner::class)
class LingvaSingleInstanceTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    /** Every screen a test opens, closed after it so no later test's store
     *  writes reach one still listening. */
    private val activities = mutableListOf<ActivityController<out AppCompatActivity>>()

    @Before fun setUp() {
        // The blank DeepL key keeps a build's baked key from migrating a
        // DeepL instance beside the Lingva.
        PtCloudTestSupport.sharedPrefs(ctx).edit().clear().putString("deepl_api_key", "").commit()
        OnlineServiceStore.init(ctx, PtCloudTestSupport.prefs(ctx))
        PtCloudService.installForTest(CooldownState(null, PtCloudService.ID), PtCloudTestSupport.config(ctx, key = ""))
    }

    @After fun tearDown() {
        activities.forEach { c ->
            when (c.get().lifecycle.currentState) {
                Lifecycle.State.RESUMED -> c.pause().stop().destroy()
                Lifecycle.State.STARTED -> c.stop().destroy()
                Lifecycle.State.CREATED -> c.destroy()
                else -> Unit
            }
        }
        OnlineServiceStore.all().forEach { TranslationBackendRegistry.removeOnlineBackend(it.id) }
        TranslationBackendRegistry.setOrder(null)
        TranslationCache.shared.resetForTest()
        // After every registry walk: one that reaches the cloud's cooldown
        // throws once it is uninstalled.
        PtCloudService.installForTest(null)
        PtCloudTestSupport.sharedPrefs(ctx).edit().clear().commit()
    }

    private fun lingvas() = OnlineServiceStore.all().filter { it.type == ServiceType.LINGVA }

    private val lingvaRow = CatalogEntry.Service(ServiceType.LINGVA)

    // ── offered ──────────────────────────────────────────────────────────

    @Test fun `Lingva's row leaves the offered rows while the list holds a Lingva, on or off`() {
        val catalog = AddOnlineServiceActivity.CATALOG
        assertEquals(catalog, AddOnlineServiceActivity.offered(emptyList()))
        listOf(true, false).forEach { enabled ->
            assertEquals(
                "enabled=$enabled",
                catalog - lingvaRow,
                AddOnlineServiceActivity.offered(
                    listOf(OnlineServiceInstance(id = "l", type = ServiceType.LINGVA, enabled = enabled)),
                ),
            )
        }
    }

    @Test fun `a service with something to set stays offered beside its own instances`() {
        val held = listOf(
            OnlineServiceInstance(id = "o", type = ServiceType.OPENAI, enabled = true),
            OnlineServiceInstance(id = "g", type = ServiceType.GEMINI, enabled = true),
            OnlineServiceInstance(id = "d", type = ServiceType.DEEPL, enabled = true),
            OnlineServiceInstance(
                id = "b", type = ServiceType.OPENAI, enabled = true, preset = OpenAiPreset.INDEX_TRANSLATE,
            ),
        )
        assertEquals(AddOnlineServiceActivity.CATALOG, AddOnlineServiceActivity.offered(held))
    }

    // ── The picker ───────────────────────────────────────────────────────

    private fun openPicker(intent: Intent? = null): AddOnlineServiceActivity {
        val c = Robolectric.buildActivity(AddOnlineServiceActivity::class.java, intent).setup()
        activities += c
        shadowOf(Looper.getMainLooper()).idle()
        return c.get()
    }

    @Test fun `the picker draws no Lingva row while the list holds one`() {
        assertEquals(1, lingvas().size)
        assertEquals(
            listOf("OpenAI", "Gemini", "Bilibili Index-Translate", "DeepL"),
            rowTitles(openPicker()),
        )
    }

    @Test fun `with no Lingva its row adds one, on, and a second tap before the picker closes adds nothing`() {
        OnlineServiceMutations.delete(ctx, "lingva")
        val picker = openPicker()
        assertEquals(
            listOf("OpenAI", "Gemini", "Bilibili Index-Translate", "DeepL", "Lingva"),
            rowTitles(picker),
        )
        val row = rowTitled(picker, "Lingva")
        row.performClick()
        assertTrue(picker.isFinishing)
        assertEquals(1, lingvas().size)

        row.performClick()
        assertEquals(1, lingvas().size)
        assertTrue(lingvas().single().enabled)
    }

    // ── The services page's Add row ──────────────────────────────────────

    private fun openPage(): ActivityController<TranslationServicesActivity> {
        val c = Robolectric.buildActivity(TranslationServicesActivity::class.java).setup()
        activities += c
        shadowOf(Looper.getMainLooper()).idle()
        return c
    }

    private fun TranslationServicesActivity.addRow() = findViewById<View>(R.id.rowAddOnlineService)
    private fun TranslationServicesActivity.addRowSubtitle() =
        addRow().findViewById<TextView>(R.id.tvRowSubtitle).text.toString()

    private fun TranslationServicesActivity.tapEditAction() {
        assertTrue(findViewById<MaterialToolbar>(R.id.toolbar).menu.performIdentifierAction(R.id.action_edit, 0))
        shadowOf(Looper.getMainLooper()).idle()
    }

    private val withoutLingva =
        "OpenAI (DeepSeek, Mistral, Groq, OpenRouter, Claude, Custom), Gemini, Bilibili Index-Translate, DeepL"
    private val withLingva = "$withoutLingva, Lingva"

    @Test fun `the Add row leaves Lingva out while the list holds one`() {
        assertEquals(withoutLingva, openPage().get().addRowSubtitle())
    }

    @Test fun `a return from the picker that added Lingva leaves it out of the Add row`() {
        OnlineServiceMutations.delete(ctx, "lingva")
        val c = openPage()
        val page = c.get()
        assertEquals(withLingva, page.addRowSubtitle())

        page.addRow().performClick()
        val pickerIntent = shadowOf(page).nextStartedActivity
        c.pause()
        val picker = openPicker(pickerIntent)
        rowTitled(picker, "Lingva").performClick()
        assertTrue(picker.isFinishing)
        c.resume()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(withoutLingva, page.addRowSubtitle())
    }

    @Test fun `Done after an edit that removed the Lingva names it in the Add row again`() {
        val page = openPage().get()
        page.tapEditAction()
        assertFalse(page.addRow().isVisible)

        val recycler = page.findViewById<RecyclerView>(R.id.rvOnlineServices)
        val lingvaCell = (0 until recycler.childCount).map { recycler.getChildAt(it) }
            .single { it.findViewById<TextView>(R.id.tvRowTitle).text.toString() == "Lingva" }
        lingvaCell.findViewById<View>(R.id.ivDelete).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        descendants(page.window.decorView).filterIsInstance<Button>()
            .single { it.text.toString() == ctx.getString(R.string.tr_service_remove_confirm) }
            .performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, lingvas().size)

        page.tapEditAction()
        assertTrue(page.addRow().isVisible)
        assertEquals(withLingva, page.addRowSubtitle())
    }

    // ── View helpers ─────────────────────────────────────────────────────

    private fun descendants(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is ViewGroup) for (i in 0 until root.childCount) yieldAll(descendants(root.getChildAt(i)))
    }

    private fun serviceRows(activity: Activity): List<View> =
        descendants(activity.findViewById(R.id.serviceListContainer)).filter { view ->
            view.hasOnClickListeners() && view.findViewById<TextView>(R.id.tvRowTitle) != null
        }.toList()

    private fun rowTitles(activity: Activity): List<String> =
        serviceRows(activity).map { it.findViewById<TextView>(R.id.tvRowTitle).text.toString() }

    /** The clickable row whose title is [title]. */
    private fun rowTitled(activity: Activity, title: String): View =
        serviceRows(activity).single { it.findViewById<TextView>(R.id.tvRowTitle).text.toString() == title }
}
