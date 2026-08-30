package com.playtranslate.translation

import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Pins [ShortTextOfflineRoute.resolve]'s selection policy (Bergamot-shaped
 * tier beats the ML Kit floor; the floor requires models already on disk;
 * none → null fail-open), [ShortTextOfflineRoute.translateOrNull]'s failure
 * contract, and [shouldBypassForLlm]'s condition set.
 */
class ShortTextOfflineRouteTest {

    @After fun tearDown() {
        TranslationBackendRegistry.close()
    }

    /** Offline-fallback-capable fake; [MlKitBackend.ID] makes it the floor. */
    private class FakeFallbackBackend(
        override val id: BackendId,
        override val priority: Int,
        private val throwOnTranslate: Throwable? = null,
        private val response: ((String) -> String)? = null,
    ) : TranslationBackend {
        override val displayName: String = "fake-$id"
        override val requiresInternet: Boolean = false
        override val isDegradedFallback: Boolean = id == MlKitBackend.ID
        override val usableAsOfflineFallback: Boolean = true
        override fun isUsable(source: String, target: String): Boolean = true
        override suspend fun translate(text: String, source: String, target: String): String {
            throwOnTranslate?.let { throw it }
            return response?.invoke(text) ?: "$id:$text"
        }
    }

    @Test fun `bergamot-shaped tier wins over the mlkit floor`() = runBlocking {
        TranslationBackendRegistry.init(
            listOf(
                FakeFallbackBackend(MlKitBackend.ID, priority = 30),
                FakeFallbackBackend("bergamot", priority = 28),
            ),
        )
        val route = ShortTextOfflineRoute.resolve("ja", "en", mlKitReady = { _, _ -> true })!!
        assertEquals("fake-bergamot", route.displayName)
        assertEquals("bergamot:はい", route.translateOrNull("はい", "ja", "en"))
    }

    @Test fun `mlkit floor is chosen only when its models are ready`() = runBlocking {
        TranslationBackendRegistry.init(listOf(FakeFallbackBackend(MlKitBackend.ID, priority = 30)))
        assertNull(ShortTextOfflineRoute.resolve("ja", "en", mlKitReady = { _, _ -> false }))
        val route = ShortTextOfflineRoute.resolve("ja", "en", mlKitReady = { _, _ -> true })
        assertEquals("fake-mlkit", route!!.displayName)
    }

    @Test fun `no qualifying backend resolves to null`() = runBlocking {
        TranslationBackendRegistry.init(
            listOf(FakeOnlineBackend(id = "lingva", priority = 20)),
        )
        assertNull(ShortTextOfflineRoute.resolve("ja", "en", mlKitReady = { _, _ -> true }))
    }

    @Test fun `translateOrNull degrades failure to null and propagates cancellation`() = runBlocking {
        TranslationBackendRegistry.init(
            listOf(FakeFallbackBackend("bergamot", priority = 28, throwOnTranslate = IOException("boom"))),
        )
        val failing = ShortTextOfflineRoute.resolve("ja", "en", mlKitReady = { _, _ -> false })!!
        assertNull(failing.translateOrNull("はい", "ja", "en"))

        TranslationBackendRegistry.init(
            listOf(
                FakeFallbackBackend(
                    "bergamot", priority = 28,
                    throwOnTranslate = CancellationException("cancelled"),
                ),
            ),
        )
        val cancelled = ShortTextOfflineRoute.resolve("ja", "en", mlKitReady = { _, _ -> false })!!
        try {
            cancelled.translateOrNull("はい", "ja", "en")
            fail("expected CancellationException to propagate")
        } catch (expected: CancellationException) {
        }
    }

    @Test fun `blank result is failure - folds into the online batch`() = runBlocking {
        // Empty translatedText is the overlay's PENDING sentinel; cached it
        // renders a permanent skeleton (review find). Blank → null.
        TranslationBackendRegistry.init(
            listOf(FakeFallbackBackend("bergamot", priority = 28, response = { "" })),
        )
        val route = ShortTextOfflineRoute.resolve("ja", "en", mlKitReady = { _, _ -> false })!!
        assertNull(route.translateOrNull("はい", "ja", "en"))

        TranslationBackendRegistry.init(
            listOf(FakeFallbackBackend("bergamot", priority = 28, response = { "  " })),
        )
        val route2 = ShortTextOfflineRoute.resolve("ja", "en", mlKitReady = { _, _ -> false })!!
        assertNull(route2.translateOrNull("はい", "ja", "en"))
    }

    // --- shouldBypassForLlm ---

    private class FakeLlmBatchBackend : TranslationBackend, BatchTranslator {
        override val id: BackendId = "gemini"
        override val displayName: String = "fake-gemini"
        override val priority: Int = 10
        override val requiresInternet: Boolean = true
        override val isDegradedFallback: Boolean = false
        override val providesLlmContext: Boolean = true
        override fun isUsable(source: String, target: String): Boolean = true
        override suspend fun translate(text: String, source: String, target: String): String = text
        override suspend fun translateBatch(texts: List<String>, source: String, target: String): List<String> = texts
    }

    private class FakeNonLlmBatchBackend : TranslationBackend, BatchTranslator {
        override val id: BackendId = "deepl"
        override val displayName: String = "fake-deepl"
        override val priority: Int = 10
        override val requiresInternet: Boolean = true
        override val isDegradedFallback: Boolean = false
        override fun isUsable(source: String, target: String): Boolean = true
        override suspend fun translate(text: String, source: String, target: String): String = text
        override suspend fun translateBatch(texts: List<String>, source: String, target: String): List<String> = texts
    }

    private class FakeOnDeviceLlmBackend : TranslationBackend {
        override val id: BackendId = "qwen"
        override val displayName: String = "fake-qwen"
        override val priority: Int = 25
        override val requiresInternet: Boolean = false
        override val isDegradedFallback: Boolean = false
        override fun isUsable(source: String, target: String): Boolean = true
        override suspend fun translate(text: String, source: String, target: String): String = text
    }

    @Test fun `bypass requires batching llm with context enabled`() {
        assertTrue(shouldBypassForLlm(FakeLlmBatchBackend(), contextEnabled = true))
        assertFalse(shouldBypassForLlm(FakeLlmBatchBackend(), contextEnabled = false))
        assertFalse(shouldBypassForLlm(FakeNonLlmBatchBackend(), contextEnabled = true))
        assertFalse(shouldBypassForLlm(FakeOnDeviceLlmBackend(), contextEnabled = true))
        assertFalse(shouldBypassForLlm(null, contextEnabled = true))
    }
}
