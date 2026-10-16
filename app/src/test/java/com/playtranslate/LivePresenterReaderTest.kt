package com.playtranslate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.capture.CapturedFrame
import com.playtranslate.language.TextAlignment
import com.playtranslate.language.TextOrientation
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.GraphicsMode

/**
 * The live presenters with the frame's reader's translations
 * ([LivePresenter.present]'s `reader`): the translation presenter paints first
 * (what arrived with the OCR, over the cache's entry, the cache fills, the
 * skeletons) and only then waits, when a box the cache has no entry for can
 * still take the reader's translation, repainting as each later translation
 * arrives, a cache fill included; a cycle whose boxes the cache and the OCR's
 * translations filled returns without waiting, reader or not. The panel
 * presenter paints nothing, and waits by the same rule.
 *
 * Robolectric [CaptureService] for the translation cache, one fake waterfall
 * backend, a legacy-graphics bitmap for the skeletons' color sampling.
 * Japanese to English, the defaults. Readers are built and driven by hand
 * ([ReaderTranslations.deliver], [ReaderTranslations.settle]), keyed by
 * position in the presenter's work as [readerForRegions] keys them.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class LivePresenterReaderTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private var built: ServiceController<CaptureService>? = null
    private lateinit var service: CaptureService
    private val waterfall = FakeOnlineBackend(id = "fake", priority = 10, displayName = "Fake", response = "waterfall")
    private val frame = CapturedFrame(
        Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888), includesSystemUi = false, includesOwnOverlays = false,
    )

    @Before fun setUp() {
        ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        // The cache is the process's one (TranslationCache.shared): every
        // cell starts from the empty cache a fresh process has.
        TranslationCache.shared.resetForTest()
        TranslationBackendRegistry.init(listOf(waterfall))
        built = Robolectric.buildService(CaptureService::class.java).create()
        service = built!!.get()
    }

    @After fun tearDown() {
        built?.destroy()
        built = null
        TranslationCache.shared.resetForTest()
        TranslationBackendRegistry.close()
        ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun region(text: String, top: Int = 4) = ScanlineReconciler.Region(
        text = text, bounds = Rect(4, top, 40, top + 16), lineCount = 1,
        orientation = TextOrientation.HORIZONTAL, alignment = TextAlignment.LEFT,
    )

    private fun reader(groupCount: Int, ready: Map<Int, String> = emptyMap()) =
        ReaderTranslations("ja", "en", "Cloud", groupCount, ready, Job())

    /** Seeds the cache with the waterfall's translation of [text]. */
    private fun cache(text: String) = runBlocking { service.translateGroupsSeparately(listOf(text)) }

    private fun List<TextBox>.pairs() = map { it.translatedText to it.backendDisplayName }

    private suspend fun until(done: () -> Boolean) {
        withTimeout(5_000) { while (!done()) yield() }
    }

    @Test fun `the first paint comes before any wait and carries what arrived with the OCR, and a delivery repaints`() =
        runBlocking {
            val r = reader(3, ready = mapOf(0 to "one"))
            val painted = mutableListOf<List<Pair<String, String?>>>()
            val result = async(start = CoroutineStart.UNDISPATCHED) {
                TranslationPresenter(service, displayId = 0).present(
                    listOf(region("一"), region("二", top = 24), region("三", top = 44)), frame, 0, 0,
                    onPartial = { partial -> painted += partial.pairs() },
                    reader = r,
                ).pairs()
            }
            // Painted while the reader is still pending.
            assertEquals(listOf(listOf("one" to "Cloud", "" to null, "" to null)), painted.toList())
            assertFalse(result.isCompleted)

            r.deliver(mapOf(1 to "two"))
            until { painted.size == 2 }
            assertEquals(listOf("one" to "Cloud", "two" to "Cloud", "" to null), painted[1])

            // The final part names group 1 and leaves group 2: the waterfall.
            r.settle(mapOf(1 to "two"))
            assertEquals(
                listOf("one" to "Cloud", "two" to "Cloud", "waterfall" to "Fake"),
                withTimeout(5_000) { result.await() },
            )
            assertEquals("the settled state is the return value, not a paint", 2, painted.size)
        }

    @Test fun `an all-cached cycle with a reader that can still deliver returns at once with the cache's text`() =
        runBlocking {
            cache("一")
            // Never settled: a wait would time out.
            val r = reader(1)
            val painted = mutableListOf<List<Pair<String, String?>>>()
            val result = withTimeout(2_000) {
                TranslationPresenter(service, displayId = 0).present(
                    listOf(region("一")), frame, 0, 0,
                    onPartial = { partial -> painted += partial.pairs() },
                    reader = r,
                )
            }
            assertEquals(listOf(listOf("waterfall" to "Fake")), painted.toList())
            assertEquals(listOf("waterfall" to "Fake"), result.pairs())
        }

    @Test fun `a cycle waiting for an uncached box repaints a cache fill with the reader's delivery`() =
        runBlocking {
            cache("一")
            val r = reader(2)
            val painted = mutableListOf<List<Pair<String, String?>>>()
            val result = async(start = CoroutineStart.UNDISPATCHED) {
                TranslationPresenter(service, displayId = 0).present(
                    listOf(region("一"), region("二", top = 24)), frame, 0, 0,
                    onPartial = { partial -> painted += partial.pairs() },
                    reader = r,
                ).pairs()
            }
            assertEquals(listOf(listOf("waterfall" to "Fake", "" to null)), painted.toList())
            yield()
            assertFalse("waiting for the reader", result.isCompleted)

            r.deliver(mapOf(0 to "one"))
            until { painted.size == 2 }
            assertEquals(listOf("one" to "Cloud", "" to null), painted[1])

            r.settle(mapOf(0 to "one", 1 to "two"))
            assertEquals(listOf("one" to "Cloud", "two" to "Cloud"), withTimeout(5_000) { result.await() })
        }

    @Test fun `an all-cached cycle with no reader is the cache fill, at once`() = runBlocking {
        cache("一")
        val calls = waterfall.translateCalls.get()
        var painted = 0
        val result = TranslationPresenter(service, displayId = 0).present(
            listOf(region("一")), frame, 0, 0,
            onPartial = { painted++ },
        )
        assertEquals(1, painted)
        assertEquals(listOf("waterfall" to "Fake"), result.pairs())
        assertEquals(calls, waterfall.translateCalls.get())
    }

    @Test fun `a reader that delivered everything with the OCR is taken without waiting, over the cache`() = runBlocking {
        cache("一")
        // Never settled: a wait would time out.
        val r = reader(1, ready = mapOf(0 to "one"))
        val painted = mutableListOf<List<Pair<String, String?>>>()
        val result = withTimeout(2_000) {
            TranslationPresenter(service, displayId = 0).present(
                listOf(region("一")), frame, 0, 0,
                onPartial = { partial -> painted += partial.pairs() },
                reader = r,
            )
        }
        assertEquals(listOf(listOf("one" to "Cloud")), painted.toList())
        assertEquals(listOf("one" to "Cloud"), result.pairs())
    }

    @Test fun `the panel presenter paints nothing and returns the reader's text once it settles`() = runBlocking {
        val r = reader(2)
        var painted = 0
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            PanelPresenter(service, displayId = 0).present(
                listOf(region("一"), region("二", top = 24)), frame, 0, 0,
                onPartial = { painted++ },
                reader = r,
            ).pairs()
        }
        r.deliver(mapOf(0 to "one"))
        yield()
        r.settle(mapOf(0 to "one"))
        assertEquals(listOf("one" to "Cloud", "waterfall" to "Fake"), withTimeout(5_000) { result.await() })
        assertEquals(0, painted)
    }

    @Test fun `the panel presenter does not wait when the cache has every region the reader has yet to name`() =
        runBlocking {
            cache("一")
            cache("二")
            // Never settled: a wait would time out.
            val r = reader(2)
            var painted = 0
            val result = withTimeout(2_000) {
                PanelPresenter(service, displayId = 0).present(
                    listOf(region("一"), region("二", top = 24)), frame, 0, 0,
                    onPartial = { painted++ },
                    reader = r,
                )
            }
            assertEquals(listOf("waterfall" to "Fake", "waterfall" to "Fake"), result.pairs())
            assertEquals(0, painted)
        }
}
