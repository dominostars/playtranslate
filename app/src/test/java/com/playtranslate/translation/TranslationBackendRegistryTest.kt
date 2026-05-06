package com.playtranslate.translation

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [TranslationBackendRegistry]. Pure JVM — backends are
 * fakes, no Robolectric. Android `Log` calls in the registry are no-ops
 * thanks to `unitTests.isReturnDefaultValues = true` in the app module's
 * gradle config.
 *
 * Covers the waterfall invariants that `CaptureService.translate` now
 * delegates to the registry:
 *   - first usable backend wins; subsequent backends are not invoked
 *   - exception from one backend falls through to the next
 *   - non-usable backends are skipped without being called
 *   - the chosen backend's [TranslationBackend.isDegradedFallback] flows
 *     through to [WaterfallResult.isDegraded]
 *   - default ordering is by priority; [setOrder] override is honored
 *   - [preferredOnlineId] picks the first non-degraded usable backend
 *   - all-fail surfaces an [IllegalStateException] (rather than silently
 *     returning empty text)
 */
class TranslationBackendRegistryTest {

    @After fun tearDown() {
        TranslationBackendRegistry.close()
    }

    @Test fun `first usable backend wins, later backends not invoked`() = runBlocking {
        val first = FakeOnlineBackend(id = "first", priority = 10, response = "from-first")
        val second = FakeOnlineBackend(id = "second", priority = 20, response = "from-second")
        TranslationBackendRegistry.init(listOf(first, second))

        val result = TranslationBackendRegistry.translate("hi", "ja", "en")

        assertEquals("from-first", result.text)
        assertEquals("first", result.backend.id)
        assertEquals(1, first.translateCalls.get())
        assertEquals(0, second.translateCalls.get())
    }

    @Test fun `fallback on exception advances to next backend`() = runBlocking {
        val throwing = FakeThrowingBackend(id = "throwing", priority = 10)
        val healthy = FakeOnlineBackend(id = "healthy", priority = 20, response = "from-healthy")
        TranslationBackendRegistry.init(listOf(throwing, healthy))

        val result = TranslationBackendRegistry.translate("hi", "ja", "en")

        assertEquals("from-healthy", result.text)
        assertEquals(1, throwing.translateCalls.get())
        assertEquals(1, healthy.translateCalls.get())
    }

    @Test fun `non-usable backends are skipped without being called`() = runBlocking {
        val unusable = FakeOnlineBackend(id = "unusable", priority = 10, usable = false)
        val healthy = FakeOnlineBackend(id = "healthy", priority = 20, response = "from-healthy")
        TranslationBackendRegistry.init(listOf(unusable, healthy))

        val result = TranslationBackendRegistry.translate("hi", "ja", "en")

        assertEquals("from-healthy", result.text)
        assertEquals(0, unusable.translateCalls.get())
        assertEquals(1, healthy.translateCalls.get())
    }

    @Test fun `degraded fallback flag flows into WaterfallResult`() = runBlocking {
        val throwing = FakeThrowingBackend(id = "throwing", priority = 10)
        val degraded = FakeDegradedBackend(id = "mlkit-fake", priority = 30)
        TranslationBackendRegistry.init(listOf(throwing, degraded))

        val result = TranslationBackendRegistry.translate("hi", "ja", "en")

        assertTrue(result.isDegraded)
        assertEquals("mlkit-fake", result.backend.id)
        assertEquals("offline-fallback", result.text)
    }

    @Test fun `online success does not set degraded flag`() = runBlocking {
        val online = FakeOnlineBackend(id = "online", priority = 10)
        val degraded = FakeDegradedBackend(id = "mlkit-fake", priority = 30)
        TranslationBackendRegistry.init(listOf(online, degraded))

        val result = TranslationBackendRegistry.translate("hi", "ja", "en")

        assertEquals(false, result.isDegraded)
    }

    @Test fun `default ordering is by priority then id`() {
        val a = FakeOnlineBackend(id = "a", priority = 30)
        val b = FakeOnlineBackend(id = "b", priority = 10)
        val c = FakeOnlineBackend(id = "c", priority = 20)
        TranslationBackendRegistry.init(listOf(a, b, c))  // insertion order shuffled

        val ids = TranslationBackendRegistry.orderedBackends().map { it.id }

        assertEquals(listOf("b", "c", "a"), ids)
    }

    @Test fun `setOrder override puts listed ids first then remainder in default order`() {
        val a = FakeOnlineBackend(id = "a", priority = 10)
        val b = FakeOnlineBackend(id = "b", priority = 20)
        val c = FakeOnlineBackend(id = "c", priority = 30)
        TranslationBackendRegistry.init(listOf(a, b, c))

        TranslationBackendRegistry.setOrder(listOf("c", "a"))
        val ids = TranslationBackendRegistry.orderedBackends().map { it.id }

        assertEquals(listOf("c", "a", "b"), ids)
    }

    @Test fun `setOrder ignores unknown ids`() {
        val a = FakeOnlineBackend(id = "a", priority = 10)
        val b = FakeOnlineBackend(id = "b", priority = 20)
        TranslationBackendRegistry.init(listOf(a, b))

        TranslationBackendRegistry.setOrder(listOf("ghost", "b", "phantom"))
        val ids = TranslationBackendRegistry.orderedBackends().map { it.id }

        assertEquals(listOf("b", "a"), ids)
    }

    @Test fun `setOrder null restores default priority order`() {
        val a = FakeOnlineBackend(id = "a", priority = 10)
        val b = FakeOnlineBackend(id = "b", priority = 20)
        TranslationBackendRegistry.init(listOf(a, b))

        TranslationBackendRegistry.setOrder(listOf("b", "a"))
        TranslationBackendRegistry.setOrder(null)

        val ids = TranslationBackendRegistry.orderedBackends().map { it.id }
        assertEquals(listOf("a", "b"), ids)
    }

    @Test fun `preferredOnlineId returns first non-degraded usable id`() {
        val deepl = FakeOnlineBackend(id = "deepl", priority = 10)
        val gtx = FakeOnlineBackend(id = "lingva", priority = 20)
        val mlkit = FakeDegradedBackend(id = "mlkit", priority = 30)
        TranslationBackendRegistry.init(listOf(deepl, gtx, mlkit))

        assertEquals("deepl", TranslationBackendRegistry.preferredOnlineId("ja", "en"))
    }

    @Test fun `preferredOnlineId skips unusable online backends`() {
        val deepl = FakeOnlineBackend(id = "deepl", priority = 10, usable = false)
        val gtx = FakeOnlineBackend(id = "lingva", priority = 20)
        val mlkit = FakeDegradedBackend(id = "mlkit", priority = 30)
        TranslationBackendRegistry.init(listOf(deepl, gtx, mlkit))

        assertEquals("lingva", TranslationBackendRegistry.preferredOnlineId("ja", "en"))
    }

    @Test fun `preferredOnlineId skips degraded backends`() {
        val mlkit = FakeDegradedBackend(id = "mlkit", priority = 10)
        TranslationBackendRegistry.init(listOf(mlkit))

        assertEquals("none", TranslationBackendRegistry.preferredOnlineId("ja", "en"))
    }

    @Test fun `preferredOnlineId returns none when no backend is usable`() {
        val deepl = FakeOnlineBackend(id = "deepl", priority = 10, usable = false)
        TranslationBackendRegistry.init(listOf(deepl))

        assertEquals("none", TranslationBackendRegistry.preferredOnlineId("ja", "en"))
    }

    @Test fun `all backends throwing surfaces IllegalStateException`() {
        val a = FakeThrowingBackend(id = "a", priority = 10)
        val b = FakeThrowingBackend(id = "b", priority = 20)
        TranslationBackendRegistry.init(listOf(a, b))

        val ex = assertFails {
            runBlocking { TranslationBackendRegistry.translate("hi", "ja", "en") }
        }
        assertTrue(
            "Expected IllegalStateException, got ${ex::class.simpleName}",
            ex is IllegalStateException,
        )
    }

    @Test fun `empty registry surfaces IllegalStateException with init hint`() {
        TranslationBackendRegistry.init(emptyList())

        val ex = assertFails {
            runBlocking { TranslationBackendRegistry.translate("hi", "ja", "en") }
        }
        assertTrue(ex is IllegalStateException)
        assertTrue(ex.message?.contains("init") == true)
    }

    @Test fun `byId returns the registered backend or null`() {
        val a = FakeOnlineBackend(id = "a", priority = 10)
        TranslationBackendRegistry.init(listOf(a))

        assertEquals("a", TranslationBackendRegistry.byId("a")?.id)
        assertEquals(null, TranslationBackendRegistry.byId("nonexistent"))
    }

    @Test fun `close calls close on every registered backend`() {
        val a = FakeOnlineBackend(id = "a", priority = 10)
        val b = FakeOnlineBackend(id = "b", priority = 20)
        TranslationBackendRegistry.init(listOf(a, b))

        TranslationBackendRegistry.close()

        assertTrue(a.closed)
        assertTrue(b.closed)
    }

    @Test fun `re-init replaces backends and closes prior ones`() {
        val first = FakeOnlineBackend(id = "first", priority = 10)
        TranslationBackendRegistry.init(listOf(first))

        val replacement = FakeOnlineBackend(id = "replacement", priority = 10)
        TranslationBackendRegistry.init(listOf(replacement))

        assertTrue("expected first backend to be closed on re-init", first.closed)
        assertEquals("replacement", TranslationBackendRegistry.byId("replacement")?.id)
        assertEquals(null, TranslationBackendRegistry.byId("first"))
    }

    private fun assertFails(block: () -> Unit): Throwable {
        try {
            block()
        } catch (e: Throwable) {
            return e
        }
        throw AssertionError("Expected block to throw, but it returned normally")
    }
}
