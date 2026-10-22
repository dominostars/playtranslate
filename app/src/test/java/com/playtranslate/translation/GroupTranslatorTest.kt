package com.playtranslate.translation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.TranslationCache
import com.playtranslate.ui.DegradedWarningKind
import com.playtranslate.ocr.ReaderTranslations
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicInteger

/**
 * [GroupTranslator] as the surfaces outside the capture service hold it (the
 * camera tool, the import tool, select-to-translate): over the process's one
 * cache, so a translation made on one surface serves another; taking its
 * cache hits at the lookup, so another surface's clear between the lookup
 * and the return cannot blank them; caching a reader's texts only under the
 * configuration the reader's request went out under; and reporting each batch's degradation
 * to its sink, None on the same-language bypass. The reader handshake
 * itself is pinned through the service's delegators in
 * [com.playtranslate.TranslateWithReaderTest]. Japanese to English, the
 * default pair.
 */
@RunWith(RobolectricTestRunner::class)
class GroupTranslatorTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    /** A waterfall backend that clears the shared cache while it translates:
     *  what another surface's settings change does mid-batch. */
    private class ClearingBackend(private val response: String) : TranslationBackend {
        override val id: BackendId = "clearing"
        override val priority: Int = 10
        override val displayName: String = "Clearing"
        override val status: BackendStatus = BackendStatus.Hidden
        override val requiresInternet: Boolean = true
        override val isDegradedFallback: Boolean = false
        val translateCalls = AtomicInteger(0)
        override fun isUsable(source: String, target: String): Boolean = true
        override suspend fun translate(text: String, source: String, target: String): String {
            translateCalls.incrementAndGet()
            TranslationCache.shared.clear()
            return response
        }
        override fun close() = Unit
    }

    private val waterfall = FakeOnlineBackend(id = "fake", priority = 10, displayName = "Fake", response = "waterfall")

    @Before fun setUp() {
        ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        TranslationCache.shared.resetForTest()
    }

    @After fun tearDown() {
        TranslationCache.shared.resetForTest()
        TranslationBackendRegistry.close()
        ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun pairs(list: List<GroupTranslation?>) = list.map { it?.text to it?.backendDisplayName }

    @Test fun `two surfaces over the shared cache serve each other's translations`() = runBlocking {
        TranslationBackendRegistry.init(listOf(waterfall))
        val camera = GroupTranslator(ctx, TranslationCache.shared)
        val import = GroupTranslator(ctx, TranslationCache.shared)

        assertEquals(listOf("waterfall" to "Fake"), pairs(camera.translateGroupsSeparately(listOf("一"))))
        assertEquals(1, waterfall.translateCalls.get())
        assertEquals(listOf("waterfall" to "Fake"), pairs(import.translateGroupsSeparately(listOf("一"))))
        assertEquals("the other surface's call is a cache hit", 1, waterfall.translateCalls.get())
        assertEquals(
            "and shows at once, before any wait",
            listOf("waterfall" to "Fake"), pairs(import.translationsAtOnce(listOf("一"), reader = null)),
        )
    }

    @Test fun `a hit taken at the lookup survives a clear made while the misses translate`() = runBlocking {
        val clearing = ClearingBackend("fresh")
        TranslationBackendRegistry.init(listOf(clearing))
        val translator = GroupTranslator(ctx, TranslationCache.shared)
        // Seeded as another surface's earlier translation would be.
        TranslationCache.shared[TranslationCache.Key("一", "ja", "en")] = "seeded" to "Seeded"

        // "一" is a hit; "二" goes to the waterfall, which clears the cache
        // on the way. The hit must come back as the hit, not as a blank.
        val result = pairs(translator.translateGroupsSeparately(listOf("一", "二")))
        assertEquals(listOf("seeded" to "Seeded", "fresh" to "Clearing"), result)
        assertEquals("only the miss went to the waterfall", 1, clearing.translateCalls.get())
        // And the miss's result, made under the configuration the clear
        // ended, was shown but not cached: it translates again.
        translator.translateGroupsSeparately(listOf("二"))
        assertEquals("not cached after the clear", 2, clearing.translateCalls.get())
    }

    @Test fun `a reader's text that settles after a configuration clear is shown but not cached`() = runBlocking {
        TranslationBackendRegistry.init(listOf(waterfall))
        val translator = GroupTranslator(ctx, TranslationCache.shared)
        val reader = ReaderTranslations("ja", "en", "Cloud", groupCount = 1, ready = emptyMap(), job = Job())
        val call = async { translator.translateGroupsSeparately(listOf("一"), reader = reader) }
        yield()
        GroupTranslator.clearShared()
        reader.settle(mapOf(0 to "Cloud text"))
        assertEquals(listOf("Cloud text" to "Cloud"), pairs(call.await()))
        assertEquals(
            "not cached: the next first paint has nothing for it",
            listOf<Pair<String?, String?>>(null to null), pairs(translator.translationsAtOnce(listOf("一"), reader = null)),
        )
        assertEquals("the waterfall never ran", 0, waterfall.translateCalls.get())
    }

    // A reader's request goes out before the consumer's call: a settings
    // clear between the two (the cloud's switch flipped while a capture
    // reads) ended the configuration the reader's texts were made under, so
    // the consumer shows them and caches none, whatever the generation is by
    // the time it calls.

    @Test fun `a reader's ready text made before a configuration clear shows at once but is not cached`() {
        TranslationBackendRegistry.init(listOf(waterfall))
        val translator = GroupTranslator(ctx, TranslationCache.shared)
        // Built as the reader's request goes out, under the generation then.
        val reader = ReaderTranslations("ja", "en", "Cloud", groupCount = 1, ready = mapOf(0 to "Cloud text"), job = Job())
        GroupTranslator.clearShared()

        assertEquals(listOf("Cloud text" to "Cloud"), pairs(translator.translationsAtOnce(listOf("一"), reader)))
        assertNull("not cached", TranslationCache.shared[TranslationCache.Key("一", "ja", "en")])
    }

    @Test fun `a reader's settled text made before a configuration clear is taken but not cached`() = runBlocking {
        TranslationBackendRegistry.init(listOf(waterfall))
        val translator = GroupTranslator(ctx, TranslationCache.shared)
        val reader = ReaderTranslations("ja", "en", "Cloud", groupCount = 1, ready = emptyMap(), job = Job())
        reader.settle(mapOf(0 to "Cloud text"))
        GroupTranslator.clearShared()

        assertEquals(listOf("Cloud text" to "Cloud"), pairs(translator.translateGroupsSeparately(listOf("一"), reader = reader)))
        assertNull("not cached", TranslationCache.shared[TranslationCache.Key("一", "ja", "en")])
        assertEquals("the waterfall never ran", 0, waterfall.translateCalls.get())
    }

    @Test fun `the degraded sink hears each batch, and None on the same-language bypass`() = runBlocking {
        TranslationBackendRegistry.init(listOf(waterfall))
        val heard = mutableListOf<DegradedWarningKind>()
        val translator = GroupTranslator(ctx, TranslationCache.shared) { heard += it }

        translator.translateGroupsSeparately(listOf("一"))
        assertEquals("a clean batch reports None", listOf(DegradedWarningKind.None), heard)

        heard.clear()
        translator.translateGroupsSeparately(listOf("一"))
        assertEquals("a fully cached batch runs no waterfall and reports nothing", emptyList<DegradedWarningKind>(), heard)

        com.playtranslate.Prefs(ctx).setTarget("ja")
        translator.translateGroupsSeparately(listOf("一"))
        assertEquals("the bypass clears the badge", listOf(DegradedWarningKind.None), heard)
    }

    @Test fun `the settings hooks reach the shared cache with no service running`() = runBlocking {
        TranslationBackendRegistry.init(listOf(waterfall))
        val camera = GroupTranslator(ctx, TranslationCache.shared)
        camera.translateGroupsSeparately(listOf("一"))
        assertEquals(1, waterfall.translateCalls.get())

        // A configuration change under the same backend id (model, key, URL).
        GroupTranslator.clearShared()
        camera.translateGroupsSeparately(listOf("一"))
        assertEquals("the old configuration's translation is gone", 2, waterfall.translateCalls.get())

        // A routing change: the preferred backend differs from the one the
        // cache's identity was reconciled under. The first paint reads the
        // cache without reconciling, so without the hook it would show the
        // old backend's entry; with it the entry is gone.
        TranslationBackendRegistry.close()
        val other = FakeOnlineBackend(id = "other", priority = 5, displayName = "Other", response = "other")
        TranslationBackendRegistry.init(listOf(other))
        assertEquals(listOf("waterfall" to "Fake"), pairs(camera.translationsAtOnce(listOf("一"), reader = null)))
        GroupTranslator.reconcileShared(ctx)
        assertEquals(listOf<Pair<String?, String?>>(null to null), pairs(camera.translationsAtOnce(listOf("一"), reader = null)))
        assertEquals(listOf("other" to "Other"), pairs(camera.translateGroupsSeparately(listOf("一"))))
    }

    @Test fun `a translator with no sink translates like one with a sink`() = runBlocking {
        TranslationBackendRegistry.init(listOf(waterfall))
        val silent = GroupTranslator(ctx, TranslationCache.shared)
        assertEquals(listOf("waterfall" to "Fake", "waterfall" to "Fake"), pairs(silent.translateGroupsSeparately(listOf("一", "二"))))
        assertEquals("waterfall", silent.translateOnce("三").text)
    }
}
