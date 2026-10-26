package com.playtranslate.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.widget.Button
import android.widget.FrameLayout
import androidx.core.view.allViews
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.TranslationCache
import com.playtranslate.cloud.PtCloudConfig
import com.playtranslate.cloud.PtCloudService
import com.playtranslate.cloud.PtCloudTestSupport
import com.playtranslate.language.OcrBackend
import com.playtranslate.language.SourceLangId
import com.playtranslate.ocr.registry.OcrModelManager
import com.playtranslate.ocr.registry.isDownloaded
import com.playtranslate.ocr.registry.ocrLabel
import com.playtranslate.ocr.registry.selectionToken
import com.playtranslate.overlayThemedContext
import com.playtranslate.themeColor
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
import org.robolectric.RobolectricTestRunner

/**
 * The "Choose OCR tool" picker ([OcrPicker]) as a radio over the engine token
 * and PlayTranslate Cloud's OCR switch: without a cloud choice (the camera and
 * import tools) the buttons are the engines and a pick never touches the
 * switch; with one, the cloud entry is first and accent only when applied, a
 * device pick while the cloud reads persists its token, turns the switch off
 * and re-reads once (or asks for the download, the switch off at the tap), a
 * cloud pick turns the switch on and re-reads through the router with a key
 * or opens the cloud page without one, and an applied entry's tap reconciles
 * the switch with the entry and re-reads nothing.
 *
 * The service's settings are a config on a reversible codec installed for
 * the test, so a cell can store a key; the switch is a plain pref either
 * way. The engine set is the registry's on the JVM: Latin's ML Kit is
 * bundled, so English has a downloaded engine; Japanese offers packs that
 * are not on disk here.
 */
@RunWith(RobolectricTestRunner::class)
class OcrPickerTest {

    private val ctx: Context = overlayThemedContext(ApplicationProvider.getApplicationContext<Context>())
    private lateinit var cooldown: CooldownState
    private lateinit var cloudPrefs: Prefs

    private var reOcrs = 0
    private var downloads = mutableListOf<OcrBackend>()
    private var cloudReOcrs = 0
    private var pagesOpened = 0

    @Before fun setUp() {
        clearPrefs()
        cooldown = CooldownState(null, PtCloudService.ID)
        cloudPrefs = PtCloudTestSupport.prefs(ctx)
        PtCloudService.installForTest(cooldown, PtCloudConfig(cloudPrefs))
    }

    @After fun tearDown() {
        PtCloudService.installForTest(null)
        TranslationCache.shared.resetForTest()
        clearPrefs()
    }

    private fun clearPrefs() {
        ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun withKey() { cloudPrefs.ptCloudClientKey = PtCloudTestSupport.KEY }
    private fun ocrOn(on: Boolean) { cloudPrefs.ptCloudOcrEnabled = on }
    private val ocrSwitch: Boolean get() = cloudPrefs.ptCloudOcrEnabled

    private fun recordCooldown() =
        cooldown.recordParsedFailure(System.currentTimeMillis() + 10 * 60_000L, "Rate limited", CooldownCause.RATE_LIMITED)

    private val cloud = OcrPicker.CloudChoice(onReOcr = { cloudReOcrs++ }, onOpenPage = { pagesOpened++ })

    /** The picker for [id], shown in a host view; returns its buttons in order. */
    private fun show(id: SourceLangId, applied: String, withCloud: Boolean): List<Button> {
        val host = FrameLayout(ctx)
        OcrPicker.populate(
            OverlayAlert.Builder(ctx), ctx, id, applied,
            onReOcr = { reOcrs++ },
            onDownload = { downloads += it },
            cloud = if (withCloud) cloud else null,
        ).showInParent(host)
        return host.allViews.filterIsInstance<Button>().toList()
    }

    private fun List<Button>.labelled(label: String): Button = single { it.text.toString() == label }

    private fun Button.isAccent(): Boolean =
        (background as GradientDrawable).color?.defaultColor == ctx.themeColor(R.attr.ptAccent)

    private fun downloaded(id: SourceLangId): OcrBackend =
        OcrModelManager.availableBackends(ctx, id).first { it.isDownloaded(ctx) }

    private fun notDownloaded(id: SourceLangId): OcrBackend? =
        OcrModelManager.availableBackends(ctx, id).firstOrNull { !it.isDownloaded(ctx) }

    private fun assertNothingCalled() {
        assertEquals(0, reOcrs)
        assertEquals(0, cloudReOcrs)
        assertEquals(0, pagesOpened)
        assertTrue(downloads.isEmpty())
    }

    // ── Without a cloud choice: the tools ──────────────────────────────────

    @Test fun `without a cloud choice the buttons are the engines and Cancel, and a device pick leaves the switch alone`() {
        withKey()
        ocrOn(true)
        val engines = OcrModelManager.availableBackends(ctx, SourceLangId.EN)
        val buttons = show(SourceLangId.EN, applied = "", withCloud = false)
        assertEquals(engines.map { it.ocrLabel(ctx) } + "Cancel", buttons.map { it.text.toString() })

        buttons.labelled(downloaded(SourceLangId.EN).ocrLabel(ctx)).performClick()

        assertEquals(downloaded(SourceLangId.EN).selectionToken, Prefs(ctx).ocrBackendToken(SourceLangId.EN))
        assertEquals(1, reOcrs)
        assertTrue("the tools' picks are scope-local: the switch is not theirs", ocrSwitch)
    }

    // ── With a cloud choice: the capture surfaces ──────────────────────────

    @Test fun `the cloud entry is first, accent only when applied`() {
        val applied = show(SourceLangId.EN, applied = PtCloudService.ID, withCloud = true)
        assertEquals(PtCloudService.DISPLAY_NAME, applied.first().text.toString())
        assertTrue(applied.first().isAccent())
        assertFalse(applied.labelled(downloaded(SourceLangId.EN).ocrLabel(ctx)).isAccent())

        val device = show(SourceLangId.EN, applied = downloaded(SourceLangId.EN).selectionToken, withCloud = true)
        assertEquals(PtCloudService.DISPLAY_NAME, device.first().text.toString())
        assertFalse(device.first().isAccent())
        assertTrue(device.labelled(downloaded(SourceLangId.EN).ocrLabel(ctx)).isAccent())
        assertEquals("Cancel", device.last().text.toString())
    }

    @Test fun `a downloaded device pick with the cloud on persists the token, turns the switch off and re-reads once`() {
        withKey()
        ocrOn(true)
        val engine = downloaded(SourceLangId.EN)
        show(SourceLangId.EN, applied = PtCloudService.ID, withCloud = true)
            .labelled(engine.ocrLabel(ctx)).performClick()

        assertEquals(engine.selectionToken, Prefs(ctx).ocrBackendToken(SourceLangId.EN))
        assertFalse("the radio rule: the device pick is the off switch", ocrSwitch)
        assertEquals(1, reOcrs)
        assertEquals(0, cloudReOcrs)
        assertEquals(0, pagesOpened)
    }

    @Test fun `a not-downloaded device pick with the cloud on turns the switch off and asks for the download`() {
        withKey()
        ocrOn(true)
        val engine = notDownloaded(SourceLangId.JA)
        assertNotNull("a Japanese pack not on disk here", engine)
        show(SourceLangId.JA, applied = PtCloudService.ID, withCloud = true)
            .labelled(engine!!.ocrLabel(ctx)).performClick()

        assertFalse("the pick was 'use this engine': the cloud goes off at the tap", ocrSwitch)
        assertEquals(listOf(engine), downloads)
        assertEquals("the download screen persists the token on success", 0, reOcrs)
        assertNull(Prefs(ctx).ocrBackendToken(SourceLangId.JA))
    }

    @Test fun `tapping the applied device engine with the cloud on turns it off and re-reads nothing`() {
        withKey()
        ocrOn(true)
        val engine = downloaded(SourceLangId.EN)
        show(SourceLangId.EN, applied = engine.selectionToken, withCloud = true)
            .labelled(engine.ocrLabel(ctx)).performClick()

        assertFalse("the off-ramp for a result the device read under the cloud", ocrSwitch)
        assertNothingCalled()
    }

    @Test fun `tapping the applied device engine with the cloud off dismisses, as before`() {
        ocrOn(false)
        val engine = downloaded(SourceLangId.EN)
        show(SourceLangId.EN, applied = engine.selectionToken, withCloud = true)
            .labelled(engine.ocrLabel(ctx)).performClick()

        assertFalse(ocrSwitch)
        assertNothingCalled()
    }

    @Test fun `a cloud pick with a key turns the switch on, resets a cooldown and re-reads through the router`() {
        withKey()
        ocrOn(false)
        recordCooldown()
        show(SourceLangId.EN, applied = downloaded(SourceLangId.EN).selectionToken, withCloud = true)
            .labelled(PtCloudService.DISPLAY_NAME).performClick()

        assertTrue(ocrSwitch)
        assertNull("the switch's gesture tail", PtCloudService.unavailableUntil())
        assertEquals(1, cloudReOcrs)
        assertEquals(0, pagesOpened)
        assertEquals(0, reOcrs)
    }

    @Test fun `a cloud pick with the switch already on, the result read by the device, is the retry`() {
        withKey()
        ocrOn(true)
        recordCooldown()
        show(SourceLangId.EN, applied = downloaded(SourceLangId.EN).selectionToken, withCloud = true)
            .labelled(PtCloudService.DISPLAY_NAME).performClick()

        assertTrue(ocrSwitch)
        assertNull("the cooldown that kept the read on the device is dropped", PtCloudService.unavailableUntil())
        assertEquals(1, cloudReOcrs)
        assertEquals(0, pagesOpened)
    }

    @Test fun `a cloud pick without a key switches nothing and opens the page, applied or not`() {
        ocrOn(false)
        show(SourceLangId.EN, applied = downloaded(SourceLangId.EN).selectionToken, withCloud = true)
            .labelled(PtCloudService.DISPLAY_NAME).performClick()

        assertFalse("nothing switched: the page takes the key, and the first key turns both on", ocrSwitch)
        assertEquals(1, pagesOpened)
        assertEquals(0, cloudReOcrs)
        assertEquals(0, reOcrs)

        show(SourceLangId.EN, applied = PtCloudService.ID, withCloud = true)
            .labelled(PtCloudService.DISPLAY_NAME).performClick()
        assertFalse(ocrSwitch)
        assertEquals(2, pagesOpened)
        assertEquals(0, cloudReOcrs)
    }

    @Test fun `tapping the applied cloud entry reconciles the switch on and re-reads nothing`() {
        withKey()
        ocrOn(false)
        show(SourceLangId.EN, applied = PtCloudService.ID, withCloud = true)
            .labelled(PtCloudService.DISPLAY_NAME).performClick()
        assertTrue("turned off elsewhere since the read: back on", ocrSwitch)
        assertNothingCalled()
    }

    /** A tap on the current choice is a dismiss, as an engine's: with the
     *  cloud applied and the switch already on there is nothing to
     *  reconcile, so no store and no gesture tail. A cooldown recorded since
     *  the read stays, since the tap changed nothing (Codex review,
     *  2026-10-25: resetting it there retried a rate-limited service for
     *  nothing). */
    @Test fun `tapping the applied cloud entry with the switch on does nothing, a cooldown included`() {
        withKey()
        ocrOn(true)
        recordCooldown()
        val changes = PtCloudService.changes.value
        show(SourceLangId.EN, applied = PtCloudService.ID, withCloud = true)
            .labelled(PtCloudService.DISPLAY_NAME).performClick()
        assertTrue(ocrSwitch)
        assertNotNull("the cooldown stays", PtCloudService.unavailableUntil())
        assertEquals("no gesture", changes, PtCloudService.changes.value)
        assertNothingCalled()
    }
}
