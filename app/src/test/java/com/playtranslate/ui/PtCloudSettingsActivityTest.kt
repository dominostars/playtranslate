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
import com.playtranslate.cloud.PtCloudConfig
import com.playtranslate.cloud.PtCloudService
import com.playtranslate.cloud.PtCloudTestSupport
import com.playtranslate.translation.CooldownCause
import com.playtranslate.translation.CooldownState
import com.playtranslate.translation.GroupTranslator
import com.playtranslate.translation.KeyStatus
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
 * The PlayTranslate Cloud page: its two "use for" switches (Read captures,
 * Translate) seed from their prefs without resetting the service and keep
 * their state across a recreate, a flip writes its pref and resets the
 * service's cooldown through the service's one mutation for that switch
 * (the pref stored before the reset's reconcile of the shared cache), they
 * repaint from the service's change signal when flipped elsewhere, they are
 * the page's only switches and the client key the page's only text field
 * (the upload format, the item lines and the warm call are fixed, as is the
 * service's address), Save checks the typed key with the server first and
 * stores it only when the server accepts it, which resets the service,
 * starts a health check and turns both switches on for a first key, while a
 * refused key and one that could not be checked store and reset nothing and
 * say why in an alert, and a blank Save clears the key with no check; and
 * the status row shows the health check's answer in its wording for each.
 *
 * A stored key is asserted through a fake codec installed on the service:
 * the page's own [Prefs] uses the AndroidKeyStore codec, which a JVM test has
 * none of. Neither check reaches the service: every test answers them through
 * [PtCloudSettingsActivity.healthOverrideForTest] and
 * [PtCloudSettingsActivity.keyCheckOverrideForTest], by default with checks
 * that never end, which [tearDown] cancels with the page.
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
        PtCloudSettingsActivity.keyCheckOverrideForTest = { awaitCancellation() }
    }

    @After fun tearDown() {
        controller?.pause()?.stop()?.destroy()
        controller = null
        PtCloudSettingsActivity.healthOverrideForTest = null
        PtCloudSettingsActivity.keyCheckOverrideForTest = null
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

    private fun PtCloudSettingsActivity.ocrRow() = findViewById<View>(R.id.rowCloudOcr)
    private fun PtCloudSettingsActivity.translateRow() = findViewById<View>(R.id.rowCloudTranslate)
    private fun View.switch() = findViewById<MaterialSwitch>(R.id.switchRowToggle)
    private fun View.title() = findViewById<TextView>(R.id.tvRowTitle).text.toString()
    private fun PtCloudSettingsActivity.saveButton() = findViewById<MaterialButton>(R.id.btnCloudSave)
    private fun PtCloudSettingsActivity.keyField() = findViewById<EditText>(R.id.etCloudKey)
    private fun PtCloudSettingsActivity.status() = findViewById<TextView>(R.id.tvCloudStatus).text.toString()
    private fun PtCloudSettingsActivity.cooldownLine() = findViewById<TextView>(R.id.tvCloudCooldown)

    /** Every text on the page's window, an alert attached to it included. */
    private fun PtCloudSettingsActivity.texts() =
        window.decorView.allViews.filterIsInstance<TextView>().map { it.text.toString() }.toList()

    /** Types [key] and taps Save. */
    private fun PtCloudSettingsActivity.save(key: String) {
        keyField().setText(key)
        saveButton().performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** Save while its key check runs: the button blanked and disabled under
     *  its spinner, the key field locked. */
    private fun assertSaveBusy(page: PtCloudSettingsActivity) {
        assertFalse(page.saveButton().isEnabled)
        assertEquals("", page.saveButton().text.toString())
        assertTrue(page.findViewById<View>(R.id.progressCloudSave).isVisible)
        assertFalse(page.keyField().isEnabled)
    }

    private fun assertSaveFree(page: PtCloudSettingsActivity) {
        assertTrue(page.saveButton().isEnabled)
        assertEquals("Save", page.saveButton().text.toString())
        assertFalse(page.findViewById<View>(R.id.progressCloudSave).isVisible)
        assertTrue(page.keyField().isEnabled)
    }

    /** The service's settings on a fake codec, so a key can be stored and
     *  read back (the page writes through the service). Returns its Prefs. */
    private fun cloudPrefs(): Prefs =
        PtCloudTestSupport.prefs(ctx).also { PtCloudService.installForTest(cooldown, PtCloudConfig(it)) }

    /** Counts the page's health checks, each one never ending. */
    private fun countHealthChecks(): () -> Int {
        var count = 0
        PtCloudSettingsActivity.healthOverrideForTest = { count++; awaitCancellation() }
        return { count }
    }

    // ── The two switches ──────────────────────────────────────────────────

    /** A usable key stored on the service, so the rows are live. */
    private fun withKey() = PtCloudService.installForTest(cooldown, PtCloudTestSupport.config(ctx))

    @Test fun `the switches seed from their prefs, keep them across a recreate, and reset nothing`() {
        withKey()
        prefs.ptCloudOcrEnabled = true
        prefs.ptCloudTranslateEnabled = false
        recordCooldown()
        val page = open()
        assertTrue(page.ocrRow().switch().isChecked)
        assertFalse(page.translateRow().switch().isChecked)
        assertTrue(page.ocrRow().switch().isEnabled)
        assertEquals("Read the screen", page.ocrRow().title())
        assertEquals("Translate the text", page.translateRow().title())

        controller!!.recreate()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(controller!!.get().ocrRow().switch().isChecked)
        assertFalse(controller!!.get().translateRow().switch().isChecked)
        assertTrue(prefs.ptCloudOcrEnabled)
        assertFalse(prefs.ptCloudTranslateEnabled)
        // Seeding through the listeners would have reset the service.
        assertNotNull("building or restoring the page resets nothing", PtCloudService.unavailableUntil())
    }

    @Test fun `the switches seed off from the prefs`() {
        withKey()
        val page = open()
        assertFalse(page.ocrRow().switch().isChecked)
        assertFalse(page.translateRow().switch().isChecked)
        assertFalse(prefs.ptCloudOcrEnabled)
        assertFalse(prefs.ptCloudTranslateEnabled)
    }

    /** No usable key saved: the rows are disabled and their switches off,
     *  whatever is stored, and a tap on them writes and resets nothing. The
     *  key field below is the first step. */
    @Test fun `with no key the switches are disabled and off, and a row tap does nothing`() {
        prefs.ptCloudOcrEnabled = true
        recordCooldown()
        val page = open()
        for (row in listOf(page.ocrRow(), page.translateRow())) {
            assertFalse(row.switch().isEnabled)
            assertFalse(row.switch().isChecked)
            assertFalse(row.isEnabled)
            assertEquals(0.5f, row.alpha)
        }

        page.ocrRow().performClick()
        page.translateRow().performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(page.ocrRow().switch().isChecked)
        assertTrue("nothing written", prefs.ptCloudOcrEnabled)
        assertFalse(prefs.ptCloudTranslateEnabled)
        assertNotNull("nothing reset", PtCloudService.unavailableUntil())
    }

    @Test fun `a row tap writes its pref alone and resets the cooldown, either way`() {
        withKey()
        recordCooldown()
        val page = open()
        assertTrue("the cooldown line shows while it runs", page.cooldownLine().isVisible)
        assertTrue(
            "in the services page's wording",
            page.cooldownLine().text.toString().startsWith("Rate limited · Retry at "),
        )

        page.ocrRow().performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(page.ocrRow().switch().isChecked)
        assertTrue(prefs.ptCloudOcrEnabled)
        assertFalse("the other switch is its own", prefs.ptCloudTranslateEnabled)
        assertNull(PtCloudService.unavailableUntil())
        assertFalse(page.cooldownLine().isVisible)

        recordCooldown()
        page.ocrRow().performClick()
        assertFalse(page.ocrRow().switch().isChecked)
        assertFalse(prefs.ptCloudOcrEnabled)
        assertNull(PtCloudService.unavailableUntil())

        recordCooldown()
        page.translateRow().performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(page.translateRow().switch().isChecked)
        assertTrue(prefs.ptCloudTranslateEnabled)
        assertFalse("the other switch is its own", prefs.ptCloudOcrEnabled)
        assertNull(PtCloudService.unavailableUntil())

        recordCooldown()
        page.translateRow().performClick()
        assertFalse(page.translateRow().switch().isChecked)
        assertFalse(prefs.ptCloudTranslateEnabled)
        assertNull(PtCloudService.unavailableUntil())
    }

    /** The cooldown line and the switches repaint from the service's change
     *  signal: a gesture made elsewhere (the services row's switch, the OCR
     *  picker over the game) while the page is resumed reaches them with no
     *  call of the page's own. */
    @Test fun `the cooldown line and the switches repaint from the service's change signal`() {
        withKey()
        recordCooldown()
        val page = open()
        assertTrue(page.cooldownLine().isVisible)
        assertFalse(page.ocrRow().switch().isChecked)

        PtCloudService.setOcrEnabled(true)
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(page.cooldownLine().isVisible)
        assertTrue("flipped elsewhere, shown here", page.ocrRow().switch().isChecked)
        assertFalse(page.translateRow().switch().isChecked)
        // The repaint wrote nothing: a second gesture would have counted.
        val changes = PtCloudService.changes.value
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(changes, PtCloudService.changes.value)
    }

    /** The Translate switch goes through [PtCloudService.setTranslateEnabled],
     *  the mutation the service's Translation services row shares: the pref
     *  is stored before the reset, whose reconcile then reads it, so the
     *  shared cache's routing identity is the cloud's once a flip turns it on
     *  with a key stored. A page that reset first and stored after would
     *  leave the identity on the old switch. */
    @Test fun `the Translate switch goes through the service's one mutation, the pref stored before the reset's reconcile`() {
        PtCloudService.installForTest(cooldown, PtCloudTestSupport.config(ctx))
        recordCooldown()
        val page = open()
        // Off and cooling down: the identity is not the cloud's.
        GroupTranslator.reconcileShared(ctx)
        assertNotEquals(PtCloudService.ID, TranslationBackendRegistry.preferredOnlineId("ja", "en"))

        page.translateRow().performClick()

        assertTrue(prefs.ptCloudTranslateEnabled)
        assertNull(PtCloudService.unavailableUntil())
        assertEquals(PtCloudService.ID, TranslationBackendRegistry.preferredOnlineId("ja", "en"))
        // The flip's reconcile already moved the identity onto the cloud, so
        // a reconcile now finds nothing changed and keeps what the cache holds.
        val key = TranslationCache.Key("一", "ja", "en")
        TranslationCache.shared[key] = "one" to PtCloudService.DISPLAY_NAME
        GroupTranslator.reconcileShared(ctx)
        assertEquals("one" to PtCloudService.DISPLAY_NAME, TranslationCache.shared[key])
    }

    @Test fun `the page's two switches are the service's two, and no master`() {
        val page = open()
        val switches = page.window.decorView.allViews.filterIsInstance<MaterialSwitch>().toList()
        assertEquals(listOf(page.ocrRow().switch(), page.translateRow().switch()), switches)
    }

    /** The first key saved, once the server accepts it, turns both switches
     *  on, through the service ([PtCloudService.saveClientKey]); the rows
     *  show it from the change signal, with no resume. */
    @Test fun `Save with a first key turns both switches on and live, shown at once, and a cleared key turns them off and disabled`() {
        val cloudPrefs = cloudPrefs()
        PtCloudSettingsActivity.keyCheckOverrideForTest = { KeyStatus.Ok }
        val page = open()
        assertFalse(page.ocrRow().switch().isEnabled)
        assertFalse(page.ocrRow().switch().isChecked)
        assertFalse(page.translateRow().switch().isChecked)

        page.save(PtCloudTestSupport.KEY)

        assertEquals(PtCloudTestSupport.KEY, cloudPrefs.ptCloudClientKey)
        assertTrue(cloudPrefs.ptCloudOcrEnabled)
        assertTrue(cloudPrefs.ptCloudTranslateEnabled)
        assertTrue(page.ocrRow().switch().isEnabled)
        assertTrue(page.ocrRow().switch().isChecked)
        assertTrue(page.translateRow().switch().isChecked)

        page.save("")

        assertFalse(cloudPrefs.ptCloudOcrEnabled)
        assertFalse(cloudPrefs.ptCloudTranslateEnabled)
        assertFalse(page.ocrRow().switch().isEnabled)
        assertFalse(page.ocrRow().switch().isChecked)
        assertFalse(page.translateRow().switch().isChecked)
    }

    // ── The key and Save ──────────────────────────────────────────────────

    @Test fun `the client key is the page's only text field, with none for the server`() {
        val page = open()
        val fields = page.window.decorView.allViews.filterIsInstance<EditText>().map { it.id }.toList()
        assertEquals(listOf(R.id.etCloudKey), fields)
    }

    @Test fun `a key the server accepts is stored, resetting the service and starting a health check, with Save free again`() {
        val cloudPrefs = cloudPrefs()
        recordCooldown()
        val healthChecks = countHealthChecks()
        val checked = mutableListOf<String>()
        PtCloudSettingsActivity.keyCheckOverrideForTest = { checked += it; KeyStatus.Ok }
        val page = open()
        assertEquals("the resume's", 1, healthChecks())

        page.save("  ${PtCloudTestSupport.KEY}  ")

        assertEquals("the key checked is the trimmed key", listOf(PtCloudTestSupport.KEY), checked)
        assertEquals(PtCloudTestSupport.KEY, cloudPrefs.ptCloudClientKey)
        assertNull(PtCloudService.unavailableUntil())
        assertFalse(page.cooldownLine().isVisible)
        assertEquals(2, healthChecks())
        assertEquals("Checking", page.status())
        assertSaveFree(page)
    }

    /** Save's busy state belongs to its key check: nothing is stored or
     *  reset until the server answers, and the health check a resume starts
     *  meanwhile leaves the busy state alone. */
    @Test fun `while the key check runs Save is busy and the field locked, nothing is stored or reset, and a resume keeps it so`() {
        val cloudPrefs = cloudPrefs()
        recordCooldown()
        val checked = mutableListOf<String>()
        PtCloudSettingsActivity.keyCheckOverrideForTest = { checked += it; awaitCancellation() }
        val page = open()

        page.save(PtCloudTestSupport.KEY)

        assertEquals(listOf(PtCloudTestSupport.KEY), checked)
        assertSaveBusy(page)
        assertEquals("", cloudPrefs.ptCloudClientKey)
        assertFalse(cloudPrefs.ptCloudOcrEnabled)
        assertNotNull("nothing reset", PtCloudService.unavailableUntil())

        controller!!.pause().resume()
        shadowOf(Looper.getMainLooper()).idle()

        assertSaveBusy(page)
        assertEquals("one check, still running", 1, checked.size)
        assertEquals("", cloudPrefs.ptCloudClientKey)
    }

    /** A refused key over a stored one: the stored key, its switches and
     *  the cooldown stay as they were, the alert says why, and the typed
     *  text stays in the field for another try. */
    @Test fun `a key the server refuses is not stored, and an alert says so`() {
        val cloudPrefs = cloudPrefs()
        cloudPrefs.ptCloudClientKey = PtCloudTestSupport.KEY
        cloudPrefs.ptCloudOcrEnabled = true
        recordCooldown()
        PtCloudSettingsActivity.keyCheckOverrideForTest = { KeyStatus.Invalid("HTTP 401") }
        val page = open()

        page.save("mistyped-key")

        assertEquals(PtCloudTestSupport.KEY, cloudPrefs.ptCloudClientKey)
        assertTrue(cloudPrefs.ptCloudOcrEnabled)
        assertFalse(cloudPrefs.ptCloudTranslateEnabled)
        assertNotNull("nothing reset", PtCloudService.unavailableUntil())
        val texts = page.texts()
        assertTrue(texts.toString(), "Key not saved" in texts)
        assertTrue(
            texts.toString(),
            "PlayTranslate Cloud didn't accept this key. Check that you pasted all of it and try again." in texts,
        )
        assertEquals("mistyped-key", page.keyField().text.toString())
        assertSaveFree(page)
    }

    /** A refusal that carries its own explanation (a real key whose account
     *  has no membership) shows that explanation. */
    @Test fun `a refusal with an explanation shows it in the alert`() {
        val cloudPrefs = cloudPrefs()
        PtCloudSettingsActivity.keyCheckOverrideForTest = {
            KeyStatus.Invalid("HTTP 403", explanationRes = R.string.pt_cloud_key_no_membership)
        }
        val page = open()

        page.save(PtCloudTestSupport.KEY)

        assertEquals("", cloudPrefs.ptCloudClientKey)
        val texts = page.texts()
        assertTrue(texts.toString(), "This key's account has no active membership." in texts)
        assertFalse(texts.any { it.contains("didn't accept") })
    }

    /** A key the server could not be asked about is not stored either:
     *  only a valid key is saved (ruled 2026-10-27). */
    @Test fun `a key that could not be checked is not stored, and an alert says so`() {
        val cloudPrefs = cloudPrefs()
        PtCloudSettingsActivity.keyCheckOverrideForTest = { KeyStatus.Unreachable }
        val page = open()

        page.save(PtCloudTestSupport.KEY)

        assertEquals("", cloudPrefs.ptCloudClientKey)
        assertFalse(cloudPrefs.ptCloudOcrEnabled)
        assertFalse(cloudPrefs.ptCloudTranslateEnabled)
        val texts = page.texts()
        assertTrue(texts.toString(), "Key not saved" in texts)
        assertTrue(
            texts.toString(),
            "PlayTranslate Cloud couldn't check this key just now. Make sure you're online and try again." in texts,
        )
        assertSaveFree(page)
    }

    @Test fun `a blank Save clears the key with no check, resets the service and starts a health check, with Save left free`() {
        val cloudPrefs = cloudPrefs()
        cloudPrefs.ptCloudClientKey = PtCloudTestSupport.KEY
        cloudPrefs.ptCloudOcrEnabled = true
        cloudPrefs.ptCloudTranslateEnabled = true
        recordCooldown()
        val healthChecks = countHealthChecks()
        val checked = mutableListOf<String>()
        PtCloudSettingsActivity.keyCheckOverrideForTest = { checked += it; KeyStatus.Ok }
        val page = open()

        page.save("   ")

        assertTrue("a blank field is not checked", checked.isEmpty())
        assertEquals("", cloudPrefs.ptCloudClientKey)
        assertFalse(cloudPrefs.ptCloudOcrEnabled)
        assertFalse(cloudPrefs.ptCloudTranslateEnabled)
        assertNull(PtCloudService.unavailableUntil())
        assertFalse(page.cooldownLine().isVisible)
        assertEquals(2, healthChecks())
        assertSaveFree(page)
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
