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

    // ── Cooldownable interaction ─────────────────────────────────────────

    @Test fun `cooldownable backend in cooldown is skipped`() = runBlocking {
        val cooled = FakeCooldownableBackend(id = "cooled", priority = 10)
        cooled.cooldownState.recordParsedFailure(
            retryAt = System.currentTimeMillis() + 60_000,
            description = "Rate limited",
        )
        val healthy = FakeOnlineBackend(id = "healthy", priority = 20, response = "from-healthy")
        TranslationBackendRegistry.init(listOf(cooled, healthy))

        val result = TranslationBackendRegistry.translate("hi", "ja", "en")

        assertEquals("from-healthy", result.text)
        assertEquals("expected cooled backend to be skipped without translate()",
            0, cooled.translateCalls.get())
        assertEquals(1, healthy.translateCalls.get())
    }

    @Test fun `cooldownable backend recordSuccess called on win`() = runBlocking {
        val winning = FakeCooldownableBackend(id = "winning", priority = 10, response = "first")
        TranslationBackendRegistry.init(listOf(winning))

        TranslationBackendRegistry.translate("hi", "ja", "en")

        assertEquals(1, winning.recordSuccessCalls.get())
    }

    @Test fun `expired cooldown is honoured and backend re-enters waterfall`() = runBlocking {
        val cooled = FakeCooldownableBackend(id = "cooled", priority = 10, response = "from-cooled")
        // Set cooldown 1ms in the past — auto-expires immediately on read.
        cooled.cooldownState.recordParsedFailure(
            retryAt = System.currentTimeMillis() - 1,
            description = "Rate limited",
        )
        val healthy = FakeOnlineBackend(id = "healthy", priority = 20, response = "from-healthy")
        TranslationBackendRegistry.init(listOf(cooled, healthy))

        val result = TranslationBackendRegistry.translate("hi", "ja", "en")

        // Cooled re-enters because retryAt is in the past.
        assertEquals("from-cooled", result.text)
        assertEquals(1, cooled.translateCalls.get())
        assertEquals(0, healthy.translateCalls.get())
    }

    @Test fun `per-text fan-out with mixed results preserves cooldown set by failing sibling`() = runBlocking {
        // Regression for Codex finding: when a per-text fan-out has 1+
        // success and 1+ failure-that-records-a-cooldown, we must NOT
        // call recordSuccess() afterwards — that would erase the
        // failure's cooldown and re-hammer the throttled provider on
        // the next waterfall pass.
        val mixed = FakeMixedResultCooldownableBackend(
            id = "mixed",
            priority = 10,
            failingTexts = setOf("bad"),
        )
        val healthy = FakeOnlineBackend(id = "healthy", priority = 20)
        TranslationBackendRegistry.init(listOf(mixed, healthy))

        val results = TranslationBackendRegistry.translateBatch(
            listOf("good", "bad"), "ja", "en",
        )

        // Both texts return — "good" via mixed, "bad" via the healthy fallback.
        assertEquals(2, results.size)
        // Most important: the cooldown set by the "bad" text must still be set.
        assertTrue("expected mixed.unavailableUntil() > now",
            (mixed.unavailableUntil() ?: 0L) > System.currentTimeMillis())
        // And recordSuccess must NOT have been called, since the pass had a failure.
        assertEquals(0, mixed.recordSuccessCalls.get())
    }

    @Test fun `per-text fan-out with all-success calls recordSuccess`() = runBlocking {
        // Counterpart to the mixed-result test: when every text in the
        // fan-out succeeds, the backend is "healthy for the next pass"
        // and recordSuccess clears any prior ladder state.
        val cool = FakeMixedResultCooldownableBackend(
            id = "cool",
            priority = 10,
            failingTexts = emptySet(),  // all texts succeed
        )
        TranslationBackendRegistry.init(listOf(cool))

        TranslationBackendRegistry.translateBatch(listOf("a", "b", "c"), "ja", "en")

        assertEquals(1, cool.recordSuccessCalls.get())
    }

    @Test fun `preferredOnlineId excludes a cooled-down higher-priority backend`() {
        // The cache layer uses preferredOnlineId to decide when to
        // invalidate. Skipping a cooled-down backend here means the
        // cache's reconcile flips identity on cooldown enter/exit and
        // drops stale fallback entries automatically — no per-result
        // bookkeeping in WaterfallResult needed.
        val preferred = FakeCooldownableBackend(id = "preferred", priority = 10)
        preferred.cooldownState.recordParsedFailure(
            retryAt = System.currentTimeMillis() + 60_000,
            description = "Rate limited",
        )
        val fallback = FakeOnlineBackend(id = "fallback", priority = 20)
        TranslationBackendRegistry.init(listOf(preferred, fallback))

        // While preferred is in cooldown, fallback is the preferred id.
        assertEquals("fallback", TranslationBackendRegistry.preferredOnlineId("ja", "en"))

        // After we clear it (simulate recovery), preferred returns.
        preferred.cooldownState.recordSuccess(System.currentTimeMillis())
        assertEquals("preferred", TranslationBackendRegistry.preferredOnlineId("ja", "en"))
    }

    @Test fun `preferredOnlineId returns first usable when no cooldowns are active`() {
        val first = FakeCooldownableBackend(id = "first", priority = 10)
        val second = FakeOnlineBackend(id = "second", priority = 20)
        TranslationBackendRegistry.init(listOf(first, second))

        assertEquals("first", TranslationBackendRegistry.preferredOnlineId("ja", "en"))
    }

    @Test fun `non-cooldownable backends are not affected by the skip check`() = runBlocking {
        // Mixing a non-Cooldownable backend with cooldowned ones at lower
        // priority verifies the cast-then-check doesn't NPE or affect
        // backends that opt out.
        val plain = FakeOnlineBackend(id = "plain", priority = 10)
        val coolBackup = FakeCooldownableBackend(id = "backup", priority = 20)
        coolBackup.cooldownState.recordParsedFailure(
            retryAt = System.currentTimeMillis() + 60_000,
            description = "Rate limited",
        )
        TranslationBackendRegistry.init(listOf(plain, coolBackup))

        val result = TranslationBackendRegistry.translate("hi", "ja", "en")
        assertEquals("translated-by-plain", result.text)
        assertEquals(1, plain.translateCalls.get())
        assertEquals(0, coolBackup.translateCalls.get())
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
