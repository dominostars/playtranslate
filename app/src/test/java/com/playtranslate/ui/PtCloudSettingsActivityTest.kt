package com.playtranslate.ui

import android.content.Context
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.core.view.allViews
import androidx.core.view.isVisible
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.TranslationCache
import com.playtranslate.cloud.HealthStatus
import com.playtranslate.cloud.PtCloudService
import com.playtranslate.cloud.PtCloudTestSupport
import com.playtranslate.translation.CooldownCause
import com.playtranslate.translation.CooldownState
import com.playtranslate.translation.GroupTranslator
import com.playtranslate.translation.TranslationBackendRegistry
import kotlinx.coroutines.awaitCancellation
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
 * The PlayTranslate Cloud page: its switch seeds from the pref without
 * resetting the service and keeps its own state across a recreate, a flip
 * writes the pref and resets the service's cooldown through the service's
 * one mutation (the pref stored before the reset's reconcile of the shared
 * cache), it is the page's only switch and the client key the page's only
 * text field (the upload format, the item lines and the warm call are fixed,
 * as is the service's address), Save resets the service and starts a check,
 * and the status row shows the check's answer in its wording for each.
 *
 * The client key is not asserted: the page's [Prefs] uses the AndroidKeyStore
 * codec, which a JVM test has none of (the key round trip is
 * PtCloudConfigTest's, on a fake codec). The health check never reaches the
 * service: every test answers it through
 * [PtCloudSettingsActivity.healthOverrideForTest], by default with a check
 * that never ends, which [tearDown] cancels with the page.
 */
@RunWith(RobolectricTestRunner::class)
class PtCloudSettingsActivityTest {

    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val prefs = Prefs(ctx)
    private lateinit var cooldown: CooldownState
    private var controller: ActivityController<PtCloudSettingsActivity>? = null

    @Before fun setUp() {
        clearPrefs()
        cooldown = CooldownState(null, PtCloudService.ID).also { PtCloudService.installForTest(it) }
        PtCloudSettingsActivity.healthOverrideForTest = { awaitCancellation() }
    }

    @After fun tearDown() {
        controller?.pause()?.stop()?.destroy()
        controller = null
        PtCloudSettingsActivity.healthOverrideForTest = null
        // A flip reconciles the shared cache's routing identity.
        TranslationCache.shared.resetForTest()
        PtCloudService.installForTest(null)
        clearPrefs()
    }

    private fun clearPrefs() {
        ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun open(): PtCloudSettingsActivity {
        val c = Robolectric.buildActivity(PtCloudSettingsActivity::class.java).setup()
        controller = c
        shadowOf(Looper.getMainLooper()).idle()
        return c.get()
    }

    private fun recordCooldown() =
        cooldown.recordParsedFailure(System.currentTimeMillis() + 10 * 60_000L, "Rate limited", CooldownCause.RATE_LIMITED)

    private fun PtCloudSettingsActivity.toggleRow() = findViewById<View>(R.id.rowCloudToggle)
    private fun PtCloudSettingsActivity.switch() = toggleRow().findViewById<MaterialSwitch>(R.id.switchRowToggle)
    private fun PtCloudSettingsActivity.saveButton() = findViewById<MaterialButton>(R.id.btnCloudSave)
    private fun PtCloudSettingsActivity.status() = findViewById<TextView>(R.id.tvCloudStatus).text.toString()
    private fun PtCloudSettingsActivity.cooldownLine() = findViewById<TextView>(R.id.tvCloudCooldown)

    // ── The switch ────────────────────────────────────────────────────────

    @Test fun `the switch seeds on from the pref, keeps it across a recreate, and resets nothing`() {
        prefs.ptCloudEnabled = true
        recordCooldown()
        val page = open()
        assertTrue(page.switch().isChecked)
        assertEquals("Use PlayTranslate Cloud", page.toggleRow().findViewById<TextView>(R.id.tvRowTitle).text.toString())

        controller!!.recreate()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(controller!!.get().switch().isChecked)
        assertTrue(prefs.ptCloudEnabled)
        // Seeding through the listener would have reset the service.
        assertNotNull("building or restoring the page resets nothing", PtCloudService.unavailableUntil())
    }

    @Test fun `the switch seeds off from the pref`() {
        val page = open()
        assertFalse(page.switch().isChecked)
        assertFalse(prefs.ptCloudEnabled)
    }

    @Test fun `a row tap writes the pref and resets the cooldown, either way`() {
        recordCooldown()
        val page = open()
        assertTrue("the cooldown line shows while it runs", page.cooldownLine().isVisible)
        assertTrue(
            "in the services page's wording",
            page.cooldownLine().text.toString().startsWith("Rate limited · Retry at "),
        )

        page.toggleRow().performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(page.switch().isChecked)
        assertTrue(prefs.ptCloudEnabled)
        assertNull(PtCloudService.unavailableUntil())
        assertFalse(page.cooldownLine().isVisible)

        recordCooldown()
        page.toggleRow().performClick()
        assertFalse(page.switch().isChecked)
        assertFalse(prefs.ptCloudEnabled)
        assertNull(PtCloudService.unavailableUntil())
    }

    /** The cooldown line repaints from the service's change signal: a
     *  gesture made elsewhere (the services row's switch) while the page is
     *  resumed reaches it with no call of the page's own. */
    @Test fun `the cooldown line repaints from the service's change signal`() {
        recordCooldown()
        val page = open()
        assertTrue(page.cooldownLine().isVisible)

        PtCloudService.setEnabled(true)
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(page.cooldownLine().isVisible)
    }

    /** The switch goes through [PtCloudService.setEnabled], the mutation the
     *  service's Translation services row shares: the pref is stored before
     *  the reset, whose reconcile then reads it, so the shared cache's
     *  routing identity is the cloud's once a flip turns it on with a key
     *  stored. A page that reset first and stored after would leave the
     *  identity on the old switch. */
    @Test fun `the switch goes through the service's one mutation, the pref stored before the reset's reconcile`() {
        PtCloudService.installForTest(cooldown, PtCloudTestSupport.config(ctx))
        recordCooldown()
        val page = open()
        // Off and cooling down: the identity is not the cloud's.
        GroupTranslator.reconcileShared(ctx)
        assertNotEquals(PtCloudService.ID, TranslationBackendRegistry.preferredOnlineId("ja", "en"))

        page.toggleRow().performClick()

        assertTrue(prefs.ptCloudEnabled)
        assertNull(PtCloudService.unavailableUntil())
        assertEquals(PtCloudService.ID, TranslationBackendRegistry.preferredOnlineId("ja", "en"))
        // The flip's reconcile already moved the identity onto the cloud, so
        // a reconcile now finds nothing changed and keeps what the cache holds.
        val key = TranslationCache.Key("一", "ja", "en")
        TranslationCache.shared[key] = "one" to PtCloudService.DISPLAY_NAME
        GroupTranslator.reconcileShared(ctx)
        assertEquals("one" to PtCloudService.DISPLAY_NAME, TranslationCache.shared[key])
    }

    @Test fun `the service's switch is the page's only switch`() {
        val page = open()
        val switches = page.window.decorView.allViews.filterIsInstance<MaterialSwitch>().toList()
        assertEquals(listOf(page.switch()), switches)
    }

    // ── The key and Save ──────────────────────────────────────────────────

    @Test fun `the client key is the page's only text field, with none for the server`() {
        val page = open()
        val fields = page.window.decorView.allViews.filterIsInstance<EditText>().map { it.id }.toList()
        assertEquals(listOf(R.id.etCloudKey), fields)
    }

    @Test fun `Save resets the service and starts a check`() {
        recordCooldown()
        val page = open()
        page.saveButton().performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertNull(PtCloudService.unavailableUntil())
        assertFalse(page.cooldownLine().isVisible)
        assertEquals("Checking", page.status())
        assertFalse(page.saveButton().isEnabled)
        assertTrue(page.findViewById<View>(R.id.progressCloudSave).isVisible)
    }

    // ── Status ────────────────────────────────────────────────────────────

    @Test fun `the status reads Not checked before the first check`() {
        // Built but not resumed: the check runs from onResume.
        val c = Robolectric.buildActivity(PtCloudSettingsActivity::class.java).create().start()
        try {
            assertEquals("Not checked", c.get().status())
        } finally {
            c.stop().destroy()
        }
    }

    @Test fun `the check on resume shows the service's answer`() {
        PtCloudSettingsActivity.healthOverrideForTest = { HealthStatus.Running }
        val page = open()
        assertEquals("Running", page.status())
        assertTrue("a resume check leaves Save alone", page.saveButton().isEnabled)
    }

    @Test fun `each health answer has its status wording`() {
        val cells = mapOf(
            HealthStatus.Running to "Running",
            HealthStatus.Starting to "Starting",
            HealthStatus.NotRunning to "Not running",
            HealthStatus.Unreachable to "Can't connect",
            HealthStatus.Other(500) to "Unexpected answer (500)",
        )
        cells.forEach { (status, text) -> assertEquals(status.toString(), text, healthStatusText(ctx, status)) }
    }
}
