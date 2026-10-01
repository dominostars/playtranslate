package com.playtranslate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.capture.CapturedFrame
import com.playtranslate.language.TextAlignment
import com.playtranslate.language.TextOrientation
import com.playtranslate.ocr.ProvidedTranslations
import com.playtranslate.translation.FakeOnlineBackend
import com.playtranslate.translation.TranslationBackendRegistry
import com.playtranslate.ui.TextBox
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.GraphicsMode

/**
 * When the live presenters ask for the reader's translations
 * ([LivePresenter.present]'s `provided`, which suspends until a server's
 * second part arrives): the translation presenter only after its skeletons
 * and cache fills are out, so live mode's first paint never waits on a
 * server; every presenter exactly once, an all-cached cycle included, so the
 * reader's text still replaces a cache entry there.
 *
 * Robolectric [CaptureService] for the translation cache, one fake waterfall
 * backend, a legacy-graphics bitmap for the skeletons' color sampling.
 * Japanese to English, the defaults.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class LivePresenterProvidedOrderTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private var built: ServiceController<CaptureService>? = null
    private lateinit var service: CaptureService
    private val frame = CapturedFrame(
        Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888), includesSystemUi = false, includesOwnOverlays = false,
    )

    @Before fun setUp() {
        ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        TranslationBackendRegistry.init(
            listOf(FakeOnlineBackend(id = "fake", priority = 10, displayName = "Fake", response = "waterfall")),
        )
        built = Robolectric.buildService(CaptureService::class.java).create()
        service = built!!.get()
    }

    @After fun tearDown() {
        built?.destroy()
        built = null
        TranslationBackendRegistry.close()
        ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun region(text: String) = ScanlineReconciler.Region(
        text = text, bounds = Rect(4, 4, 40, 20), lineCount = 1,
        orientation = TextOrientation.HORIZONTAL, alignment = TextAlignment.LEFT,
    )

    private fun server(text: String) =
        ProvidedTranslations.Ok(mapOf(0 to text), source = "ja", target = "en", providerName = "Cloud")

    /** Seeds the cache with the waterfall's translation of [text]. */
    private fun cache(text: String) = runBlocking { service.translateGroupsSeparately(listOf(text)) }

    @Test fun `the translation presenter's skeletons render before it asks for the reader's translations`() = runBlocking {
        val events = mutableListOf<String>()
        val result = TranslationPresenter(service, displayId = 0).present(
            listOf(region("一")), frame, 0, 0,
            onPartial = { partial -> events += "partial:" + partial.single().translatedText },
            provided = { events += "provided"; server("one") },
        )
        assertEquals(listOf("partial:", "provided"), events)
        assertEquals(listOf("one"), result.map(TextBox::translatedText))
    }

    @Test fun `an all-cached translation cycle still asks, and the reader's text replaces the cache entry`() = runBlocking {
        cache("一")
        val events = mutableListOf<String>()
        val result = TranslationPresenter(service, displayId = 0).present(
            listOf(region("一")), frame, 0, 0,
            onPartial = { partial -> events += "partial:" + partial.single().translatedText },
            provided = { events += "provided"; server("one") },
        )
        assertEquals(listOf("partial:waterfall", "provided"), events)
        assertEquals(listOf("one" to "Cloud"), result.map { it.translatedText to it.backendDisplayName })
    }

    @Test fun `an all-cached translation cycle with nothing provided is the cache fill`() = runBlocking {
        cache("一")
        var asked = 0
        val result = TranslationPresenter(service, displayId = 0).present(
            listOf(region("一")), frame, 0, 0,
            onPartial = {},
            provided = { asked++; null },
        )
        assertEquals(1, asked)
        assertEquals(listOf("waterfall" to "Fake"), result.map { it.translatedText to it.backendDisplayName })
    }

    @Test fun `the panel presenter asks once and uses the reader's text`() = runBlocking {
        var asked = 0
        val result = PanelPresenter(service, displayId = 0).present(
            listOf(region("一")), frame, 0, 0,
            onPartial = {},
            provided = { asked++; server("one") },
        )
        assertEquals(1, asked)
        assertEquals(listOf("one" to "Cloud"), result.map { it.translatedText to it.backendDisplayName })
    }
}
