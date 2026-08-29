package com.playtranslate.diagnostics

import com.playtranslate.translation.FakeSharedPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The persisted translation-failure ring behind the log-export header.
 * The privacy contract (entries built from typed fields, never
 * exception messages / URLs / bodies) lives at the CALL sites — these
 * tests pin the ring mechanics: formatting, ordering, trimming, and
 * safe no-op behavior before init.
 */
class TranslationDiagTest {

    @Before fun setUp() {
        TranslationDiag.initForTest(FakeSharedPreferences())
    }

    @After fun tearDown() {
        TranslationDiag.resetForTest()
    }

    @Test fun `entries carry the typed fields and timestamp format`() {
        TranslationDiag.recordFailure(
            backendName = "Lingva",
            exceptionClass = "LingvaRateLimitException",
            httpCode = 429,
            cooldownUntil = System.currentTimeMillis() + 60_000,
        )
        val entries = TranslationDiag.recentFailures()
        assertEquals(1, entries.size)
        val entry = entries[0]
        assertTrue("timestamp prefix in '$entry'",
            Regex("""^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2} {2}""").containsMatchIn(entry))
        assertTrue(entry.contains("Lingva"))
        assertTrue(entry.contains("LingvaRateLimitException"))
        assertTrue(entry.contains("http=429"))
        assertTrue(entry.contains("cooldown-until="))
    }

    @Test fun `optional fields are omitted when absent`() {
        TranslationDiag.recordFailure("Gemini", "SocketTimeoutException")
        val entry = TranslationDiag.recentFailures().single()
        assertTrue(!entry.contains("http="))
        assertTrue(!entry.contains("cooldown-until="))
    }

    @Test fun `ring keeps the newest 20, oldest first`() {
        for (i in 1..25) {
            TranslationDiag.recordFailure("Backend$i", "IOException")
        }
        val entries = TranslationDiag.recentFailures()
        assertEquals(20, entries.size)
        assertTrue("oldest surviving entry", entries.first().contains("Backend6"))
        assertTrue("newest entry", entries.last().contains("Backend25"))
    }

    @Test fun `ring survives re-init on the same store`() {
        val prefs = FakeSharedPreferences()
        TranslationDiag.initForTest(prefs)
        TranslationDiag.recordFailure("Lingva", "LingvaRateLimitException", httpCode = 429)
        // Simulates process restart: fresh init over the same persisted
        // store must surface the prior entries.
        TranslationDiag.initForTest(prefs)
        assertEquals(1, TranslationDiag.recentFailures().size)
    }

    @Test fun `uninitialized ring is a safe no-op`() {
        TranslationDiag.resetForTest()
        TranslationDiag.recordFailure("Lingva", "IOException")
        assertEquals(emptyList<String>(), TranslationDiag.recentFailures())
        assertEquals("net=?", TranslationDiag.connectivitySummary())
    }
}
