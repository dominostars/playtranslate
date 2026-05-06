package com.playtranslate.translation

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Test fakes for [TranslationBackend]. All counters are exposed so
 * tests can assert which backends actually got called (vs. skipped or
 * short-circuited by the waterfall).
 */

internal class FakeOnlineBackend(
    override val id: BackendId,
    override val priority: Int,
    override val displayName: String = "fake-$id",
    override val status: BackendStatus = BackendStatus.Hidden,
    private val response: String = "translated-by-$id",
    private val usable: Boolean = true,
) : TranslationBackend {
    override val requiresInternet: Boolean = true
    override val isDegradedFallback: Boolean = false
    val translateCalls = AtomicInteger(0)
    var closed = false

    override fun isUsable(source: String, target: String): Boolean = usable
    override suspend fun translate(text: String, source: String, target: String): String {
        translateCalls.incrementAndGet()
        return response
    }
    override fun close() { closed = true }
}

internal class FakeThrowingBackend(
    override val id: BackendId,
    override val priority: Int,
    override val displayName: String = "fake-$id",
    override val status: BackendStatus = BackendStatus.Hidden,
    private val exception: Exception = IOException("synthetic failure"),
) : TranslationBackend {
    override val requiresInternet: Boolean = true
    override val isDegradedFallback: Boolean = false
    val translateCalls = AtomicInteger(0)

    override fun isUsable(source: String, target: String): Boolean = true
    override suspend fun translate(text: String, source: String, target: String): String {
        translateCalls.incrementAndGet()
        throw exception
    }
}

internal class FakeDegradedBackend(
    override val id: BackendId = "mlkit-fake",
    override val priority: Int = 99,
    override val displayName: String = "fake-degraded",
    override val status: BackendStatus = BackendStatus.Hidden,
    private val response: String = "offline-fallback",
) : TranslationBackend {
    override val requiresInternet: Boolean = false
    override val isDegradedFallback: Boolean = true
    val translateCalls = AtomicInteger(0)

    override fun isUsable(source: String, target: String): Boolean = true
    override suspend fun translate(text: String, source: String, target: String): String {
        translateCalls.incrementAndGet()
        return response
    }
}
