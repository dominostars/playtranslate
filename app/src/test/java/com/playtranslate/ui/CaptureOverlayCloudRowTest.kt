package com.playtranslate.ui

import android.content.Context
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.materialswitch.MaterialSwitch
import com.playtranslate.Prefs
import com.playtranslate.PtJson
import com.playtranslate.R
import com.playtranslate.cloud.PtCloudAccount
import com.playtranslate.cloud.PtCloudConfig
import com.playtranslate.cloud.PtCloudService
import com.playtranslate.cloud.PtCloudTestSupport
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
 * The Capture and overlay screen's PlayTranslate Cloud row at the top of the
 * OCR group (debug builds, which the unit tests are): its switch writes the
 * service's OCR switch alone and resets the cooldown, turning it on with no
 * usable key saved leaves it on and opens the cloud page, a tap anywhere else
 * on the row opens the page and flips nothing, the subtitle says what the
 * switch does while off and the service's state while on, a gesture made
 * elsewhere repaints the row from the service's change signal, the engine
 * cells below leave the switch alone (the page is not a radio), and an
 * account whose trial ended shows the switch off with no tap of its own, as
 * no key does.
 *
 * The service's settings are read through [PtCloudService.config], so a cell
 * that needs a key installs a config on a reversible codec; without one no
 * key is stored (the AndroidKeyStore is instrumented-only).
 */
@RunWith(RobolectricTestRunner::class)
class CaptureOverlayCloudRowTest {

    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val prefs = Prefs(ctx)
    private lateinit var cooldown: CooldownState
    private var controller: ActivityController<CaptureOverlaySettingsActivity>? = null

    @Before fun setUp() {
        clearPrefs()
        cooldown = CooldownState(null, PtCloudService.ID).also { PtCloudService.installForTest(it) }
    }

    @After fun tearDown() {
        controller?.let { runCatching { it.pause().stop().destroy() } }
        controller = null
        PtCloudService.installForTest(null)
        clearPrefs()
    }

    private fun clearPrefs() {
        ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun recordCooldown() =
        cooldown.recordParsedFailure(System.currentTimeMillis() + 10 * 60_000L, "Rate limited", CooldownCause.RATE_LIMITED)

    /** The page, created and (by default) resumed, since the row repaints
     *  from the service's change signal only while resumed, as on the device
     *  when the user taps it. setupDisplays() asks for the activity's
     *  display, which Robolectric's activity context refuses; the OCR group
     *  is built before it runs. */
    private fun create(resume: Boolean = true): CaptureOverlaySettingsActivity {
        val c = Robolectric.buildActivity(CaptureOverlaySettingsActivity::class.java)
        controller = c
        val thrown = runCatching { c.create() }.exceptionOrNull()
        assertTrue("$thrown", thrown == null || thrown is UnsupportedOperationException)
        if (resume) {
            c.start().resume()
            shadowOf(Looper.getMainLooper()).idle()
        }
        return c.get()
    }

    private val CaptureOverlaySettingsActivity.row: View get() = findViewById(R.id.rowPtCloudOcr)
    private val CaptureOverlaySettingsActivity.switch: MaterialSwitch
        get() = row.findViewById(R.id.switchRowToggle)
    private val CaptureOverlaySettingsActivity.subtitle: String
        get() = row.findViewById<TextView>(R.id.tvRowSubtitle).text.toString()
    private fun CaptureOverlaySettingsActivity.startedPage(): String? =
        shadowOf(this).nextStartedActivity?.component?.className

    /** No key saved: the switch looks live but is off and no tap target of
     *  its own, so a touch on it falls through to the row, whose tap opens
     *  the cloud page where the key goes; nothing is written
     *  (2026-10-25). */
    @Test fun `with no key the row shows an off switch that is not its own tap target, and says to set up`() {
        prefs.ptCloudOcrEnabled = true
        recordCooldown()
        val a = create()
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.cardPtCloud).visibility)
        assertEquals("PlayTranslate Cloud (Alpha)", a.row.findViewById<TextView>(R.id.tvRowTitle).text.toString())
        assertTrue("looks live", a.switch.isEnabled)
        assertFalse("shows off whatever is stored", a.switch.isChecked)
        assertFalse("a touch on it reaches the row", a.switch.isClickable)
        assertEquals("Server-grade OCR, zero device load", a.subtitle)

        a.row.performClick()

        assertEquals(PtCloudSettingsActivity::class.java.name, a.startedPage())
        assertFalse(a.switch.isChecked)
        assertTrue("nothing written", prefs.ptCloudOcrEnabled)
        assertFalse(prefs.ptCloudTranslateEnabled)
        assertNotNull("nothing reset", PtCloudService.unavailableUntil())
    }

    /** The no-key cell's twin for an account whose trial ended
     *  (2026-10-29): the switch shows off whatever is stored and is not its
     *  own tap target, and a tap opens the cloud page, where Join is;
     *  nothing is written or reset. */
    @Test fun `with an ended account the row shows an off switch that is not its own tap target, and a tap opens the page and writes nothing`() {
        val cloudPrefs = PtCloudTestSupport.prefs(ctx)
        PtCloudService.installForTest(cooldown, PtCloudTestSupport.config(ctx))
        cloudPrefs.ptCloudAccountJson = PtJson.lenient.encodeToString(
            PtCloudAccount.serializer(),
            PtCloudAccount(active = false, lastSource = "trial", endedAtMs = System.currentTimeMillis() - 24 * 3_600_000L, checkedAtMs = 1L),
        )
        cloudPrefs.ptCloudOcrEnabled = true
        recordCooldown()
        val a = create()
        assertTrue("looks live", a.switch.isEnabled)
        assertFalse("shows off whatever is stored", a.switch.isChecked)
        assertFalse("a touch on it reaches the row", a.switch.isClickable)

        a.row.performClick()

        assertEquals(PtCloudSettingsActivity::class.java.name, a.startedPage())
        assertFalse(a.switch.isChecked)
        assertTrue("nothing written", cloudPrefs.ptCloudOcrEnabled)
        assertNotNull("nothing reset", PtCloudService.unavailableUntil())
    }

    @Test fun `with a key saved the row shows off, the one-line pitch, and the switch is its own tap target`() {
        PtCloudService.installForTest(cooldown, PtCloudTestSupport.config(ctx))
        val a = create()
        assertFalse(a.switch.isChecked)
        assertTrue(a.switch.isClickable)
        assertEquals("the subtitle carries no state", "Server-grade OCR, zero device load", a.subtitle)
    }

    @Test fun `switching on with a key saved opens nothing, and off writes the pref back`() {
        PtCloudService.installForTest(cooldown, PtCloudTestSupport.config(ctx))
        val a = create()

        a.switch.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(PtCloudTestSupport.prefs(ctx).ptCloudOcrEnabled)
        assertFalse("the translation switch is its own", PtCloudTestSupport.prefs(ctx).ptCloudTranslateEnabled)
        assertNull("a key is saved: nothing to do on the page", a.startedPage())
        assertEquals("Server-grade OCR, zero device load", a.subtitle)

        recordCooldown()
        a.switch.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(a.switch.isChecked)
        assertFalse(PtCloudTestSupport.prefs(ctx).ptCloudOcrEnabled)
        assertNull("either direction resets the cooldown", PtCloudService.unavailableUntil())
        assertNull(a.startedPage())
    }

    @Test fun `a tap on the row outside the switch opens the cloud page and flips nothing`() {
        recordCooldown()
        val a = create()

        a.row.performClick()

        assertEquals(PtCloudSettingsActivity::class.java.name, a.startedPage())
        assertFalse(a.switch.isChecked)
        assertFalse(prefs.ptCloudOcrEnabled)
        assertNotNull("no gesture was made", PtCloudService.unavailableUntil())
    }

    /** The row repaints from the service's change signal: a gesture made
     *  elsewhere (the cloud page, the OCR picker over the game) while the
     *  page is resumed reaches it with no call of the page's own, and the
     *  repaint itself writes nothing. */
    @Test fun `a gesture made elsewhere repaints the row without writing`() {
        PtCloudService.installForTest(cooldown, PtCloudTestSupport.config(ctx))
        val a = create()
        assertFalse(a.switch.isChecked)

        PtCloudService.setOcrEnabled(true)
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue("flipped elsewhere, shown here", a.switch.isChecked)
        assertEquals("Server-grade OCR, zero device load", a.subtitle)
        val changes = PtCloudService.changes.value
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("the repaint made no gesture", changes, PtCloudService.changes.value)
        assertNull("and opened no page", a.startedPage())
    }

    /** The first key saved turns the switch on; the row shows it on the
     *  next resume, as the change signal replays to a new collector. */
    @Test fun `the first key saved shows on the row at the next resume`() {
        val cloudPrefs = PtCloudTestSupport.prefs(ctx)
        PtCloudService.installForTest(cooldown, PtCloudConfig(cloudPrefs))
        val a = create(resume = false)
        assertFalse(a.switch.isChecked)

        PtCloudService.saveClientKey(PtCloudTestSupport.KEY)
        controller!!.start().resume()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(a.switch.isChecked)
        assertTrue("its own tap target now", a.switch.isClickable)
        assertEquals("Server-grade OCR, zero device load", a.subtitle)
    }

    /** The page is not a radio: the engine cells pick the device engine (the
     *  fallback while the cloud reads) and never touch the switch. On the JVM
     *  the engine set is whatever the registry offers without native
     *  runtimes; with one engine there is no cell to tap, and the cell says
     *  so by passing. */
    @Test fun `an engine cell leaves the switch alone`() {
        PtCloudService.installForTest(cooldown, PtCloudTestSupport.config(ctx))
        PtCloudTestSupport.prefs(ctx).ptCloudOcrEnabled = true
        val a = create()
        assertTrue(a.switch.isChecked)
        val cells = a.findViewById<LinearLayout>(R.id.containerOcrLanguages)
        for (i in 0 until cells.childCount) {
            val cell = cells.getChildAt(i)
            if (cell.id == R.id.rowPtCloudOcr || !cell.isClickable) continue
            cell.performClick()
        }
        assertTrue(a.switch.isChecked)
        assertTrue(PtCloudTestSupport.prefs(ctx).ptCloudOcrEnabled)
    }
}
