package com.playtranslate.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.materialswitch.MaterialSwitch
import com.playtranslate.R
import com.playtranslate.TranslationCache
import com.playtranslate.cloud.PtCloudService
import com.playtranslate.cloud.PtCloudTestSupport
import com.playtranslate.translation.CooldownCause
import com.playtranslate.translation.CooldownState
import com.playtranslate.translation.OnlineServiceStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/**
 * PlayTranslate Cloud's row on the Translation services page: first in the
 * online card, ahead of the store's instances; its cooldown line and tint
 * from the registry's backend, which reads the service's cooldown at each
 * call (the state a test installs after the Application built the backend);
 * its switch through the service's one mutation; the key's tail or
 * "Account required"; a tap to the cloud page; and a fresh render on a
 * return from that page while the card is in edit mode, which the store
 * rows' rebind skips.
 *
 * The key is stored through [PtCloudTestSupport]'s fake codec and read
 * through the config [PtCloudService.installForTest] installs: the page's
 * own [com.playtranslate.Prefs] has no AndroidKeyStore here. The cooldown
 * runs on the wall clock, which the row judges it against.
 */
@RunWith(RobolectricTestRunner::class)
class TranslationServicesCloudRowTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val prefs = PtCloudTestSupport.prefs(ctx)
    private lateinit var cooldown: CooldownState
    private var controller: ActivityController<TranslationServicesActivity>? = null

    @Before fun setUp() {
        PtCloudTestSupport.sharedPrefs(ctx).edit().clear().commit()
        OnlineServiceStore.init(ctx, prefs)
        cooldown = CooldownState(null, PtCloudService.ID)
        PtCloudService.installForTest(cooldown, PtCloudTestSupport.config(ctx, KEY))
    }

    @After fun tearDown() {
        controller?.pause()?.stop()?.destroy()
        controller = null
        // The switch's reset reconciled the shared cache's routing identity.
        TranslationCache.shared.resetForTest()
        // After every registry walk: one that reaches the cloud's cooldown
        // throws once it is uninstalled.
        PtCloudService.installForTest(null)
        PtCloudTestSupport.sharedPrefs(ctx).edit().clear().commit()
    }

    private fun open(): TranslationServicesActivity {
        val c = Robolectric.buildActivity(TranslationServicesActivity::class.java).setup()
        controller = c
        shadowOf(Looper.getMainLooper()).idle()
        return c.get()
    }

    private fun recordCooldown() =
        cooldown.recordParsedFailure(System.currentTimeMillis() + 10 * 60_000L, "Rate limited", CooldownCause.RATE_LIMITED)

    private fun TranslationServicesActivity.row() = findViewById<View>(R.id.rowPtCloud)
    private fun TranslationServicesActivity.rowMain() = row().findViewById<View>(R.id.rowMain)
    private fun TranslationServicesActivity.switch() = row().findViewById<MaterialSwitch>(R.id.switchRowToggle)
    private fun TranslationServicesActivity.statusLine() = row().findViewById<TextView>(R.id.tvRowSubtitle2)
    private fun TranslationServicesActivity.cooldownLine() = row().findViewById<TextView>(R.id.tvRowSubtitle3)
    private fun TranslationServicesActivity.keyTail() = row().findViewById<TextView>(R.id.tvKeyTail)
    private fun TranslationServicesActivity.tinted() = rowMain().background is GradientDrawable

    private fun TranslationServicesActivity.enterEditMode() {
        assertTrue(findViewById<MaterialToolbar>(R.id.toolbar).menu.performIdentifierAction(R.id.action_edit, 0))
        assertFalse("edit mode hides the Add row", findViewById<View>(R.id.rowAddOnlineService).isVisible)
    }

    /** The row's fixed parts, which edit mode leaves as they are. */
    private fun TranslationServicesActivity.assertFixedParts() {
        assertEquals("PlayTranslate Cloud", row().findViewById<TextView>(R.id.tvRowTitle).text.toString())
        assertTrue("the LLM chip", row().findViewById<View>(R.id.tvLlmBadge).isVisible)
        assertFalse("no model sub-cell", row().findViewById<View>(R.id.sectionModel).isVisible)
        assertFalse("no drag handle", row().findViewById<View>(R.id.ivDragHandle).isVisible)
        assertFalse("no trash", row().findViewById<View>(R.id.ivDelete).isVisible)
        assertTrue("the switch", switch().isVisible)
    }

    // ── Place ─────────────────────────────────────────────────────────────

    @Test fun `the row is in the online card, after the Add row and before the store's instances`() {
        val page = open()
        val card = page.findViewById<View>(R.id.rvOnlineServices).parent as ViewGroup
        val ids = (0 until card.childCount).map { card.getChildAt(it).id }
        assertEquals(
            listOf(R.id.rowAddOnlineService, R.id.dividerAddOnlineService, R.id.rowPtCloud, R.id.rvOnlineServices),
            ids.take(4),
        )
        assertTrue(page.row().isVisible)
        page.assertFixedParts()
        assertTrue("a store instance follows it", page.row().findViewById<View>(R.id.rowDivider).isVisible)
    }

    @Test fun `with no store instance it is the card's last row and has no divider`() {
        OnlineServiceStore.all().forEach { OnlineServiceStore.remove(it.id) }
        val page = open()
        assertFalse(page.row().findViewById<View>(R.id.rowDivider).isVisible)
    }

    // ── Cooldown and switch ──────────────────────────────────────────────

    @Test fun `a cooldown on the installed state shows the cooldown line and the tint`() {
        recordCooldown()
        val page = open()
        assertTrue(page.cooldownLine().isVisible)
        assertTrue(
            "in the services page's wording",
            page.cooldownLine().text.toString().startsWith("Rate limited · Retry at "),
        )
        assertTrue(page.tinted())
    }

    @Test fun `with no cooldown there is no cooldown line and no tint`() {
        val page = open()
        assertFalse(page.cooldownLine().isVisible)
        assertFalse(page.tinted())
    }

    @Test fun `the switch mirrors the stored switch, and building the page writes and resets nothing`() {
        prefs.ptCloudEnabled = true
        recordCooldown()
        val page = open()
        assertTrue(page.switch().isChecked)
        assertTrue(prefs.ptCloudEnabled)
        assertNotNull("rendering resets nothing", PtCloudService.unavailableUntil())
    }

    @Test fun `flipping the switch stores it and resets the cooldown, either way`() {
        recordCooldown()
        val page = open()
        assertFalse(page.switch().isChecked)

        page.switch().performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(prefs.ptCloudEnabled)
        assertNull(PtCloudService.unavailableUntil())
        assertFalse("the line goes with the cooldown", page.cooldownLine().isVisible)
        assertFalse(page.tinted())

        recordCooldown()
        page.switch().performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(page.switch().isChecked)
        assertFalse(prefs.ptCloudEnabled)
        assertNull(PtCloudService.unavailableUntil())
        assertFalse(page.cooldownLine().isVisible)
    }

    /** The row repaints from the service's change signal, not from its own
     *  switch handler: a gesture made elsewhere (the cloud page's Save)
     *  while this page is resumed reaches it the same way. */
    @Test fun `a gesture made on the cloud page repaints the row`() {
        recordCooldown()
        val page = open()
        assertTrue(page.cooldownLine().isVisible)
        assertTrue(page.tinted())

        PtCloudService.onCredentialsSaved()
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse("the line goes with the cooldown", page.cooldownLine().isVisible)
        assertFalse(page.tinted())
    }

    // ── Key ───────────────────────────────────────────────────────────────

    @Test fun `a stored key shows its last four characters and no status line`() {
        val page = open()
        assertTrue(page.keyTail().isVisible)
        assertEquals("Key ••••wxyz", page.keyTail().text.toString())
        assertFalse(page.statusLine().isVisible)
    }

    @Test fun `with no key the status line reads Account required and no tail shows`() {
        PtCloudService.installForTest(cooldown, PtCloudTestSupport.config(ctx, key = ""))
        val page = open()
        assertFalse(page.keyTail().isVisible)
        assertTrue(page.statusLine().isVisible)
        assertEquals("Account required", page.statusLine().text.toString())
    }

    // ── Navigation and edit mode ─────────────────────────────────────────

    @Test fun `a tap on the row opens the cloud page`() {
        val page = open()
        page.rowMain().performClick()
        val started = shadowOf(page).nextStartedActivity
        assertEquals(PtCloudSettingsActivity::class.java.name, started.component!!.className)
    }

    @Test fun `edit mode leaves the row as it is, its tap included`() {
        val page = open()
        page.enterEditMode()
        page.assertFixedParts()
        page.rowMain().performClick()
        assertEquals(PtCloudSettingsActivity::class.java.name, shadowOf(page).nextStartedActivity.component!!.className)
    }

    @Test fun `a return from the cloud page while editing renders the row again`() {
        val page = open()
        page.enterEditMode()
        assertFalse(page.switch().isChecked)
        assertFalse(page.cooldownLine().isVisible)

        // Away on the cloud page: the switch turned on there, then a call
        // failed and cooled the service down.
        page.rowMain().performClick()
        controller!!.pause()
        PtCloudService.setEnabled(true)
        recordCooldown()
        controller!!.resume()
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse("still editing", page.findViewById<View>(R.id.rowAddOnlineService).isVisible)
        assertTrue(page.switch().isChecked)
        assertTrue(page.cooldownLine().isVisible)
        assertTrue(page.tinted())
        page.assertFixedParts()
    }

    private companion object {
        const val KEY = "pt-test-key-wxyz"
    }
}
