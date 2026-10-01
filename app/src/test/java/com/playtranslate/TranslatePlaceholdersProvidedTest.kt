package com.playtranslate

import android.content.Context
import android.graphics.Rect
import androidx.test.core.app.ApplicationProvider
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

/**
 * [OverlayToolkit.translatePlaceholders] with a reader's translations keyed
 * by position (the reconciler tier's cloud reads), one cell per kind of
 * position:
 *  - named by the reader: the reader's text, over a cache entry, and cached;
 *  - named, but for another language pair: as if nothing were provided;
 *  - not named and cached: the cache entry;
 *  - not named and uncached, beside a named one: the waterfall. Never a
 *    blank: under a matched set, translateGroupsSeparately gives a position
 *    the set does not name no translation, so these positions must not ride
 *    in the provided call.
 *
 * The waterfall is one fake backend; the service is a Robolectric
 * [CaptureService] (its translation cache and the pair check are the code
 * under test). Japanese to English, the defaults.
 */
@RunWith(RobolectricTestRunner::class)
class TranslatePlaceholdersProvidedTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private var built: ServiceController<CaptureService>? = null
    private lateinit var service: CaptureService
    private val waterfall = FakeOnlineBackend(id = "fake", priority = 10, displayName = "Fake", response = "waterfall")

    @Before fun setUp() {
        ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        TranslationBackendRegistry.init(listOf(waterfall))
        built = Robolectric.buildService(CaptureService::class.java).create()
        service = built!!.get()
    }

    @After fun tearDown() {
        built?.destroy()
        built = null
        TranslationBackendRegistry.close()
        ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun cloud(byPosition: Map<Int, String>, target: String = "en") =
        ProvidedTranslations.Ok(byPosition, source = "ja", target = target, providerName = "Cloud")

    private fun translate(texts: List<String>, provided: ProvidedTranslations.Ok?): List<Pair<String, String?>> =
        runBlocking {
            val placeholders = texts.map { TextBox("", Rect(0, 0, 10, 10), sourceText = it) }
            OverlayToolkit.translatePlaceholders(service, placeholders, texts, provided)
                .map { it.translatedText to it.backendDisplayName }
        }

    @Test fun `nothing provided is cache first, then the waterfall`() {
        assertEquals(listOf("waterfall" to "Fake"), translate(listOf("一"), provided = null))
        assertEquals(1, waterfall.translateCalls.get())
        assertEquals(listOf("waterfall" to "Fake"), translate(listOf("一"), provided = null))
        assertEquals("the second read is a cache hit", 1, waterfall.translateCalls.get())
    }

    @Test fun `a named position takes the reader's text over a cache entry, and caches it`() {
        translate(listOf("一"), provided = null)
        assertEquals("waterfall", service.getCachedTranslation("一")?.text)

        assertEquals(listOf("one" to "Cloud"), translate(listOf("一"), cloud(mapOf(0 to "one"))))
        assertEquals("one", service.getCachedTranslation("一")?.text)
        assertEquals("Cloud", service.getCachedTranslation("一")?.backendDisplayName)
    }

    @Test fun `an unnamed uncached position beside a named one takes the waterfall, not a blank`() {
        assertEquals(
            listOf("one" to "Cloud", "waterfall" to "Fake"),
            translate(listOf("一", "こんにちは。"), cloud(mapOf(0 to "one"))),
        )
        assertEquals(1, waterfall.translateCalls.get())
    }

    @Test fun `an unnamed cached position keeps its cache entry`() {
        translate(listOf("二"), provided = null)
        val calls = waterfall.translateCalls.get()
        assertEquals(
            listOf("one" to "Cloud", "waterfall" to "Fake"),
            translate(listOf("一", "二"), cloud(mapOf(0 to "one"))),
        )
        assertEquals("no waterfall call for a cached text", calls, waterfall.translateCalls.get())
    }

    @Test fun `a set for another pair is ignored and its positions take the waterfall`() {
        assertEquals(
            listOf("waterfall" to "Fake"),
            translate(listOf("一"), cloud(mapOf(0 to "eins"), target = "de")),
        )
        assertEquals("waterfall", service.getCachedTranslation("一")?.text)
    }
}
