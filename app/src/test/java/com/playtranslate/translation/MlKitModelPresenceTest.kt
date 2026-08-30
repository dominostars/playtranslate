package com.playtranslate.translation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [MlKitModelPresence]'s caching contract: sticky positive per pair,
 * TTL-gated re-probe behind a negative, [MlKitModelPresence.invalidate]
 * dropping the sticky verdict, and the {source, target, English} needed set
 * (English is ML Kit's pivot).
 */
class MlKitModelPresenceTest {

    private var now = 0L
    private var probeCalls = 0
    private var probeResult: Set<String> = emptySet()

    private fun install() {
        MlKitModelPresence.invalidate()
        MlKitModelPresence.clockOverrideForTest = { now }
        MlKitModelPresence.probeOverrideForTest = {
            probeCalls++
            probeResult
        }
    }

    @After fun tearDown() {
        MlKitModelPresence.probeOverrideForTest = null
        MlKitModelPresence.clockOverrideForTest = null
        MlKitModelPresence.invalidate()
    }

    @Test fun `positive verdict is sticky - probe runs once`() = runBlocking {
        install()
        probeResult = setOf("ja", "en")
        assertTrue(MlKitModelPresence.hasModelsFor("ja", "en"))
        now += 10 * 60_000L
        assertTrue(MlKitModelPresence.hasModelsFor("ja", "en"))
        assertEquals(1, probeCalls)
    }

    @Test fun `negative verdict re-probes only after the ttl`() = runBlocking {
        install()
        probeResult = emptySet()
        assertFalse(MlKitModelPresence.hasModelsFor("ja", "en"))
        now += 1_000L
        assertFalse(MlKitModelPresence.hasModelsFor("ja", "en"))
        assertEquals(1, probeCalls)
        now += 60_000L
        probeResult = setOf("ja", "en")
        assertTrue(MlKitModelPresence.hasModelsFor("ja", "en"))
        assertEquals(2, probeCalls)
    }

    @Test fun `invalidate drops the sticky positive`() = runBlocking {
        install()
        probeResult = setOf("ja", "en")
        assertTrue(MlKitModelPresence.hasModelsFor("ja", "en"))
        MlKitModelPresence.invalidate()
        probeResult = emptySet()
        assertFalse(MlKitModelPresence.hasModelsFor("ja", "en"))
        assertEquals(2, probeCalls)
    }

    @Test fun `english pivot is required even for non-english pairs`() = runBlocking {
        install()
        probeResult = setOf("ja", "fr")
        assertFalse(MlKitModelPresence.hasModelsFor("ja", "fr"))
        now += 60_000L
        probeResult = setOf("ja", "fr", "en")
        assertTrue(MlKitModelPresence.hasModelsFor("ja", "fr"))
    }

    @Test fun `probe failure shape - empty set - reads as not ready`() = runBlocking {
        install()
        probeResult = emptySet()
        assertFalse(MlKitModelPresence.hasModelsFor("ja", "en"))
    }

    @Test fun `invalidate during an in-flight probe discards its result`() = runBlocking {
        // Review find: without the generation guard, a probe that started
        // before a delete's invalidate() could republish the stale code set
        // afterward, resurrecting the verdict the invalidate just killed.
        install()
        val probeStarted = CompletableDeferred<Unit>()
        val probeRelease = CompletableDeferred<Unit>()
        MlKitModelPresence.probeOverrideForTest = {
            probeCalls++
            probeStarted.complete(Unit)
            probeRelease.await()
            setOf("ja", "en") // stale: "models still on disk" mid-delete
        }
        val inFlight = async {
            MlKitModelPresence.hasModelsFor("ja", "en")
        }
        probeStarted.await()
        MlKitModelPresence.invalidate() // the delete lands mid-probe
        probeRelease.complete(Unit)
        // The in-flight call may return one-shot stale true (documented), but
        // it must neither STICK the pair nor publish the stale code set: the
        // next call re-probes and sees the post-delete truth.
        inFlight.await()
        MlKitModelPresence.probeOverrideForTest = { probeCalls++; emptySet() }
        assertFalse(MlKitModelPresence.hasModelsFor("ja", "en"))
        assertEquals(2, probeCalls)
    }
}
