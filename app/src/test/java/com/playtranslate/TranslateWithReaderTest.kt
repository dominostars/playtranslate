package com.playtranslate

import android.content.Context
import android.graphics.Rect
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.language.ChineseScriptVariant
import com.playtranslate.language.SourceLangId
import com.playtranslate.ocr.ReaderTranslations
import com.playtranslate.translation.FakeOnlineBackend
import com.playtranslate.translation.TranslationBackendRegistry
import com.playtranslate.ui.TextBox
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ServiceController

/**
 * The service's two translate entry points with a reader's translations
 * ([CaptureService.translationsAtOnce], [CaptureService.translateGroupsSeparately])
 * and the live tiers' wrapper over them ([OverlayToolkit.translatePlaceholders]),
 * one cell per kind of position and per moment:
 *  - named by the reader: the reader's text, over a cache entry, and cached;
 *  - not named and cached: the cache entry;
 *  - not named and uncached, beside a named one: the waterfall, in the same
 *    call;
 *  - a reader for another language pair: as if there were none, nothing
 *    taken or cached from it;
 *  - what can be shown at once: what arrived with the OCR, else the cache's
 *    entry under the call's pair (a source override's included), else
 *    nothing, and nothing at all for a same-language pair;
 *  - a final part that fails after part 1 named some: those kept, only the
 *    rest through the waterfall;
 *  - each delivery reported by position as it arrives, localized and named by
 *    the provider, never cached and never the settled state;
 *  - nothing more to arrive: no wait;
 *  - the cache having every position the reader has yet to name: no wait,
 *    the cache's text there, the reader's ready text where it has one, and
 *    an unmapped uncached position through the waterfall; one such position
 *    missing from the cache: a wait, and the reader's text then replaces a
 *    cache entry the reader names too.
 *
 * The waterfall is one fake backend; the service is a Robolectric
 * [CaptureService] (its translation cache and the pair check are the code
 * under test). Japanese to English unless a cell says otherwise. Readers are
 * built and driven by hand: [ReaderTranslations.deliver] and
 * [ReaderTranslations.settle] stand in for the cloud's item lines and final
 * part.
 */
@RunWith(RobolectricTestRunner::class)
class TranslateWithReaderTest {

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

    private fun reader(groupCount: Int, ready: Map<Int, String> = emptyMap(), target: String = "en") =
        ReaderTranslations("ja", target, "Cloud", groupCount, ready, Job())

    private fun settled(groupCount: Int, byGroup: Map<Int, String>, target: String = "en") =
        reader(groupCount, target = target).also { it.settle(byGroup) }

    private fun List<CaptureService.GroupTranslation?>.pairs() = map { it?.text to it?.backendDisplayName }

    private fun translate(texts: List<String>, reader: ReaderTranslations?) = runBlocking {
        service.translateGroupsSeparately(texts, reader = reader).pairs()
    }

    /** Seeds the cache with the waterfall's translation of [text]. */
    private fun cache(text: String) = runBlocking { service.translateGroupsSeparately(listOf(text)) }

    private suspend fun until(done: () -> Boolean) {
        withTimeout(5_000) { while (!done()) yield() }
    }

    // ── which text each position takes ───────────────────────────────────

    @Test fun `no reader is cache first, then the waterfall`() {
        assertEquals(listOf("waterfall" to "Fake"), translate(listOf("一"), reader = null))
        assertEquals(1, waterfall.translateCalls.get())
        assertEquals(listOf("waterfall" to "Fake"), translate(listOf("一"), reader = null))
        assertEquals("the second read is a cache hit", 1, waterfall.translateCalls.get())
    }

    @Test fun `a named position takes the reader's text over a cache entry, and caches it`() {
        cache("一")
        assertEquals("waterfall", service.getCachedTranslation("一")?.text)

        assertEquals(listOf("one" to "Cloud"), translate(listOf("一"), settled(1, mapOf(0 to "one"))))
        assertEquals("one" to "Cloud", service.getCachedTranslation("一")?.let { it.text to it.backendDisplayName })
    }

    @Test fun `an unnamed uncached position beside a named one takes the waterfall in the same call`() {
        assertEquals(
            listOf("one" to "Cloud", "waterfall" to "Fake"),
            translate(listOf("一", "こんにちは。"), settled(2, mapOf(0 to "one"))),
        )
        assertEquals(1, waterfall.translateCalls.get())
    }

    @Test fun `an unnamed cached position keeps its cache entry`() {
        cache("二")
        val calls = waterfall.translateCalls.get()
        assertEquals(
            listOf("one" to "Cloud", "waterfall" to "Fake"),
            translate(listOf("一", "二"), settled(2, mapOf(0 to "one"))),
        )
        assertEquals("no waterfall call for a cached text", calls, waterfall.translateCalls.get())
    }

    @Test fun `a reader for another pair is ignored, and translationsAtOnce takes and caches nothing from it`() {
        cache("二")
        val german = reader(2, ready = mapOf(0 to "eins", 1 to "zwei"), target = "de")
        assertEquals(
            "the cache's own entry for this pair still shows",
            listOf(null to null, "waterfall" to "Fake"),
            service.translationsAtOnce(listOf("一", "二"), german).pairs(),
        )
        assertNull("nothing cached from it", service.getCachedTranslation("一"))
        assertEquals("waterfall", service.getCachedTranslation("二")?.text)

        german.settle()
        assertEquals(listOf("waterfall" to "Fake"), translate(listOf("一"), german))
        assertEquals("waterfall", service.getCachedTranslation("一")?.text)
    }

    @Test fun `a final part that fails after part 1 named some keeps those and sends only the rest to the waterfall`() =
        runBlocking {
            val r = reader(3, ready = mapOf(0 to "one"))
            val result = async(start = CoroutineStart.UNDISPATCHED) {
                service.translateGroupsSeparately(listOf("一", "二", "三"), reader = r).pairs()
            }
            // An item for group 1 arrives, then the final part fails: the item
            // is dropped with the rest.
            r.deliver(mapOf(1 to "two"))
            yield()
            r.settle()
            assertEquals(
                listOf("one" to "Cloud", "waterfall" to "Fake", "waterfall" to "Fake"),
                withTimeout(5_000) { result.await() },
            )
            assertEquals("only the two unnamed texts went to the waterfall", 2, waterfall.translateCalls.get())
        }

    // ── translationsAtOnce ───────────────────────────────────────────────

    @Test fun `translationsAtOnce is what arrived with the OCR, by position, and caches it`() {
        val r = reader(3, ready = mapOf(0 to "one", 2 to "three"))
        assertEquals(
            listOf("one" to "Cloud", null to null, "three" to "Cloud"),
            service.translationsAtOnce(listOf("一", "二", "三"), r).pairs(),
        )
        assertEquals("one" to "Cloud", service.getCachedTranslation("一")?.let { it.text to it.backendDisplayName })
        assertNull(service.getCachedTranslation("二"))
        assertEquals("three", service.getCachedTranslation("三")?.text)
    }

    @Test fun `translationsAtOnce without a reader is the cache's entries, null for the rest`() {
        cache("二")
        assertEquals(
            listOf(null to null, "waterfall" to "Fake"),
            service.translationsAtOnce(listOf("一", "二"), null).pairs(),
        )
    }

    @Test fun `translationsAtOnce takes the reader's ready text over a cache entry, and caches it`() {
        cache("一")
        cache("二")
        val r = reader(2, ready = mapOf(0 to "one"))
        assertEquals(
            listOf("one" to "Cloud", "waterfall" to "Fake"),
            service.translationsAtOnce(listOf("一", "二"), r).pairs(),
        )
        assertEquals("one" to "Cloud", service.getCachedTranslation("一")?.let { it.text to it.backendDisplayName })
    }

    @Test fun `translationsAtOnce under a same-language pair is all null`() {
        Prefs(ctx).setTarget("ja")
        val sameLanguage = ReaderTranslations("ja", "ja", "Cloud", 1, mapOf(0 to "一"), Job())
        assertEquals(listOf<CaptureService.GroupTranslation?>(null), service.translationsAtOnce(listOf("一"), sameLanguage))
    }

    @Test fun `translationsAtOnce with a source override reads the cache under the overridden pair`() {
        // Cached under Chinese to English only.
        runBlocking { service.translateGroupsSeparately(listOf("一"), sourceOverride = SourceLangId.ZH) }
        assertEquals(
            "the setting's pair (Japanese) has no entry",
            listOf<CaptureService.GroupTranslation?>(null),
            service.translationsAtOnce(listOf("一"), null),
        )
        assertEquals(
            listOf("waterfall" to "Fake"),
            service.translationsAtOnce(listOf("一"), null, sourceOverride = SourceLangId.ZH).pairs(),
        )
    }

    // ── waiting and progress ─────────────────────────────────────────────

    @Test fun `each delivery is reported by position, localized and named by the provider, not cached, never the settled state`() =
        runBlocking {
            // Simplified from the reader, Traditional on screen: the delivery
            // goes through the same localization as every other result.
            Prefs(ctx).setTarget("zh", ChineseScriptVariant.TRADITIONAL)
            val r = reader(3, ready = mapOf(0 to "软件"), target = "zh")
            val progress = mutableListOf<List<CaptureService.GroupTranslation?>>()
            val result = async(start = CoroutineStart.UNDISPATCHED) {
                service.translateGroupsSeparately(listOf("一", "二", "三"), reader = r) { progress += it }.pairs()
            }

            r.deliver(mapOf(1 to "鼠标"))
            until { progress.size == 1 }
            assertEquals(listOf("軟件" to "Cloud", "鼠標" to "Cloud", null to null), progress[0].pairs())
            assertNull("a delivery is provisional: not cached", service.getCachedTranslation("二"))

            r.settle(mapOf(1 to "鼠标"))
            assertEquals(
                listOf("軟件" to "Cloud", "鼠標" to "Cloud", "waterfall" to "Fake"),
                withTimeout(5_000) { result.await() },
            )
            assertEquals("the settled state is returned, not reported", 1, progress.size)
            assertEquals("鼠標", service.getCachedTranslation("二")?.text)
        }

    @Test fun `nothing more to arrive for these positions returns without waiting, on a reader that never settles`() =
        runBlocking {
            val r = reader(2, ready = mapOf(0 to "one"))
            // Position 0 takes the ready group 0, position 1 takes none.
            val view = r.forPositions(listOf(0, null))!!
            var reported = 0
            val result = withTimeout(2_000) {
                service.translateGroupsSeparately(listOf("一", "二"), reader = view) { reported++ }.pairs()
            }
            assertEquals(listOf("one" to "Cloud", "waterfall" to "Fake"), result)
            assertEquals(0, reported)
        }

    @Test fun `a same-language pair never waits for the reader`() = runBlocking {
        Prefs(ctx).setTarget("ja")
        val pending = ReaderTranslations("ja", "ja", "Cloud", 1, emptyMap(), Job())
        val result = withTimeout(2_000) { service.translateGroupsSeparately(listOf("一"), reader = pending).pairs() }
        assertEquals(listOf("一" to null), result)
    }

    @Test fun `the cache having every position the reader has yet to name returns at once, on a reader that never settles`() =
        runBlocking {
            cache("二")
            cache("三")
            val calls = waterfall.translateCalls.get()
            // Groups 1 and 2 can still arrive, and the cache has both texts.
            val r = reader(3, ready = mapOf(0 to "one"))
            var reported = 0
            val result = withTimeout(2_000) {
                service.translateGroupsSeparately(listOf("一", "二", "三"), reader = r) { reported++ }.pairs()
            }
            assertEquals(listOf("one" to "Cloud", "waterfall" to "Fake", "waterfall" to "Fake"), result)
            assertEquals("the cache's text, no waterfall call", calls, waterfall.translateCalls.get())
            assertEquals(0, reported)
        }

    @Test fun `a call that does not wait takes nothing the reader delivered provisionally`() = runBlocking {
        cache("二")
        // Group 1 can still arrive, the cache has its text, and an item for it
        // is already in: provisional until the reader settles, so it is
        // neither shown as the result nor cached.
        val r = reader(2, ready = mapOf(0 to "one"))
        r.deliver(mapOf(1 to "two, provisionally"))
        val result = withTimeout(2_000) {
            service.translateGroupsSeparately(listOf("一", "二"), reader = r).pairs()
        }
        assertEquals(listOf("one" to "Cloud", "waterfall" to "Fake"), result)
        assertEquals("waterfall", service.getCachedTranslation("二")?.text)
    }

    @Test fun `one position the reader can still name missing from the cache waits, and the settle replaces a cache entry too`() =
        runBlocking {
            cache("二")
            // Groups 1 (cached) and 2 (not) can still arrive.
            val r = reader(3, ready = mapOf(0 to "one"))
            val result = async(start = CoroutineStart.UNDISPATCHED) {
                service.translateGroupsSeparately(listOf("一", "二", "三"), reader = r).pairs()
            }
            yield()
            assertFalse("waiting for the reader", result.isCompleted)

            r.settle(mapOf(1 to "two", 2 to "three"))
            assertEquals(
                listOf("one" to "Cloud", "two" to "Cloud", "three" to "Cloud"),
                withTimeout(5_000) { result.await() },
            )
            assertEquals(
                "the reader's text replaced the cache entry",
                "two" to "Cloud",
                service.getCachedTranslation("二")?.let { it.text to it.backendDisplayName },
            )
        }

    @Test fun `an uncached position the reader does not map goes to the waterfall without waiting when the cache has the rest`() =
        runBlocking {
            cache("二")
            val calls = waterfall.translateCalls.get()
            // Position 0 takes the ready group 0, position 1 the cached group
            // 1 that can still arrive, position 2 no group, and is uncached.
            val r = reader(2, ready = mapOf(0 to "one")).forPositions(listOf(0, 1, null))!!
            val result = withTimeout(2_000) {
                service.translateGroupsSeparately(listOf("一", "二", "三"), reader = r).pairs()
            }
            assertEquals(listOf("one" to "Cloud", "waterfall" to "Fake", "waterfall" to "Fake"), result)
            assertEquals("only the unmapped text went to the waterfall", calls + 1, waterfall.translateCalls.get())
        }

    // ── translatePlaceholders ────────────────────────────────────────────

    @Test fun `translatePlaceholders repaints what is shown with each delivery, the reader's text over a cache fill`() =
        runBlocking {
            cache("三")
            val calls = waterfall.translateCalls.get()
            // Positions 0 and 1 take groups 0 and 1 (0 arrived with the OCR);
            // position 2 takes none and is a cache fill on screen.
            val root = reader(2, ready = mapOf(0 to "one"))
            val r = root.forPositions(listOf(0, 1, null))!!
            val texts = listOf("一", "二", "三")
            val placeholders = texts.map { TextBox("", Rect(0, 0, 10, 10), sourceText = it) }
            val shown = placeholders.filledFrom(
                listOf(
                    CaptureService.GroupTranslation("one", null, "Cloud"),
                    null,
                    service.getCachedTranslation("三"),
                ),
            )
            val painted = mutableListOf<List<Pair<String, String?>>>()
            val result = async(start = CoroutineStart.UNDISPATCHED) {
                OverlayToolkit.translatePlaceholders(service, placeholders, texts, r, shown = shown) { boxes ->
                    painted += boxes.map { it.translatedText to it.backendDisplayName }
                }.map { it.translatedText to it.backendDisplayName }
            }

            root.deliver(mapOf(1 to "two"))
            until { painted.size == 1 }
            assertEquals(listOf("one" to "Cloud", "two" to "Cloud", "waterfall" to "Fake"), painted[0])

            root.settle(mapOf(1 to "two"))
            assertEquals(
                listOf("one" to "Cloud", "two" to "Cloud", "waterfall" to "Fake"),
                withTimeout(5_000) { result.await() },
            )
            assertEquals(1, painted.size)
            assertEquals("the cache fill stayed a cache hit", calls, waterfall.translateCalls.get())
        }

    @Test fun `translatePlaceholders waiting for an uncached box repaints a cache fill with the reader's delivery`() =
        runBlocking {
            cache("一")
            // Both positions can still arrive; position 0 is a cache fill on
            // screen, position 1 a skeleton the cache has nothing for.
            val r = reader(2)
            val texts = listOf("一", "二")
            val placeholders = texts.map { TextBox("", Rect(0, 0, 10, 10), sourceText = it) }
            val shown = placeholders.filledFrom(listOf(service.getCachedTranslation("一"), null))
            val painted = mutableListOf<List<Pair<String, String?>>>()
            val result = async(start = CoroutineStart.UNDISPATCHED) {
                OverlayToolkit.translatePlaceholders(service, placeholders, texts, r, shown = shown) { boxes ->
                    painted += boxes.map { it.translatedText to it.backendDisplayName }
                }.map { it.translatedText to it.backendDisplayName }
            }

            r.deliver(mapOf(0 to "one"))
            until { painted.size == 1 }
            assertEquals(listOf("one" to "Cloud", "" to null), painted[0])

            r.settle(mapOf(0 to "one", 1 to "two"))
            assertEquals(listOf("one" to "Cloud", "two" to "Cloud"), withTimeout(5_000) { result.await() })
        }

    @Test fun `translatePlaceholders returns at once when the cache has every box the reader has yet to name, an unmapped one through the waterfall`() =
        runBlocking {
            cache("二")
            // Position 0 arrived with the OCR, position 1 is cached and can
            // still arrive, position 2 takes no group and is uncached. Never
            // settled: a wait would time out.
            val r = reader(2, ready = mapOf(0 to "one")).forPositions(listOf(0, 1, null))!!
            val texts = listOf("一", "二", "三")
            val placeholders = texts.map { TextBox("", Rect(0, 0, 10, 10), sourceText = it) }
            var reported = 0
            val result = withTimeout(2_000) {
                OverlayToolkit.translatePlaceholders(service, placeholders, texts, r) { reported++ }
            }
            assertEquals(
                listOf("one" to "Cloud", "waterfall" to "Fake", "waterfall" to "Fake"),
                result.map { it.translatedText to it.backendDisplayName },
            )
            assertEquals(0, reported)
        }

    @Test fun `translatePlaceholders without a reader reports nothing and fills from the cache and the waterfall`() =
        runBlocking {
            cache("一")
            val texts = listOf("一", "二")
            val placeholders = texts.map { TextBox("", Rect(0, 0, 10, 10), sourceText = it) }
            var reported = 0
            val result = OverlayToolkit.translatePlaceholders(service, placeholders, texts) { reported++ }
            assertEquals(listOf("waterfall" to "Fake", "waterfall" to "Fake"), result.map { it.translatedText to it.backendDisplayName })
            assertEquals(0, reported)
            assertFalse(result.any { it.translatedText.isEmpty() })
        }
}
