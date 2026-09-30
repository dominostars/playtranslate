package com.playtranslate.ui

import android.content.Context
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.cloud.HealthStatus
import com.playtranslate.cloud.PtCloudService
import com.playtranslate.translation.CooldownCause
import com.playtranslate.translation.CooldownState
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
 * The PlayTranslate Cloud page: its switch seeds from the pref without
 * resetting the service and keeps its own state across a recreate, a flip
 * writes the pref and resets the service's cooldown, Save refuses a URL the
 * endpoint policy rejects and persists one it accepts, and the status row
 * words each health answer.
 *
 * The client key is not asserted: the page's [Prefs] uses the AndroidKeyStore
 * codec, which a JVM test has none of (the key round trip is
 * PtCloudConfigTest's, on a fake codec). The health check is never awaited:
 * the stored URL is a public http one, which the client answers
 * [HealthStatus.BadUrl] to without sending anything, so the resume check
 * stays off the network; the one URL a test saves is on loopback, so its
 * check never leaves the machine; and a check a test starts is cancelled
 * with the page in [tearDown].
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
        prefs.ptCloudBaseUrl = UNSENDABLE_URL
    }

    @After fun tearDown() {
        controller?.pause()?.stop()?.destroy()
        controller = null
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
    private fun PtCloudSettingsActivity.urlField() = findViewById<EditText>(R.id.etCloudUrl)
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

    // ── Save ──────────────────────────────────────────────────────────────

    @Test fun `Save refuses a URL the endpoint policy rejects and writes nothing`() {
        recordCooldown()
        val page = open()
        for (bad in listOf("http://example.com", "not a url", "")) {
            page.urlField().setText(bad)
            page.saveButton().performClick()

            assertEquals("$bad: the URL was not written", UNSENDABLE_URL, prefs.ptCloudBaseUrl)
            assertEquals("$bad: the field says why", page.getString(R.string.llm_backend_base_url_invalid), page.urlField().error?.toString())
            assertNotNull("$bad: the service was not reset", PtCloudService.unavailableUntil())
            assertTrue("$bad: no check started", page.saveButton().isEnabled)
        }
    }

    @Test fun `Save persists a loopback http URL, trimmed, resets the service and starts a check`() {
        recordCooldown()
        val page = open()
        page.urlField().setText("  http://127.0.0.1:9  ")
        page.saveButton().performClick()

        assertEquals("http://127.0.0.1:9", prefs.ptCloudBaseUrl)
        assertNull(page.urlField().error)
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

    @Test fun `each health answer has its status wording`() {
        val cells = mapOf(
            HealthStatus.Running to "Running",
            HealthStatus.Starting to "Starting",
            HealthStatus.NotRunning to "Not running",
            HealthStatus.Unreachable to "Can't connect",
            HealthStatus.BadUrl to "Invalid server URL",
            HealthStatus.Other(500) to "Unexpected answer (500)",
        )
        cells.forEach { (status, text) -> assertEquals(status.toString(), text, healthStatusText(ctx, status)) }
    }

    private companion object {
        /** Cleartext http to a public host: the endpoint policy rejects it,
         *  so the client's health check answers BadUrl without sending. */
        const val UNSENDABLE_URL = "http://pt.example.invalid"
    }
}
