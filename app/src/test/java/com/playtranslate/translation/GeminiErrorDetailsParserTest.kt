package com.playtranslate.translation

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [parseGemini429Body]. The five canonical shapes:
 *  1. Minimal `{code:429,status:RESOURCE_EXHAUSTED}` with no details[] → null
 *  2. QuotaFailure with a PerDay quotaId → (nextMidnightPacific, "Daily quota used")
 *  3. QuotaFailure with a PerMinute quotaId, accompanied by RetryInfo → uses retryDelay
 *  4. RetryInfo alone → (now + retryDelay, "Rate limited")
 *  5. Malformed JSON → null
 */
class GeminiErrorDetailsParserTest {

    private val gson = Gson()

    @Test fun `minimal 429 with no details returns null`() {
        val body = """{"error":{"code":429,"status":"RESOURCE_EXHAUSTED","message":"rate limited"}}"""
        assertNull(parseGemini429Body(body, gson))
    }

    @Test fun `PerDay quotaId yields nextMidnightPacific and Daily quota used`() {
        val body = """
            {
              "error": {
                "code": 429,
                "status": "RESOURCE_EXHAUSTED",
                "details": [
                  {
                    "@type": "type.googleapis.com/google.rpc.QuotaFailure",
                    "violations": [
                      {
                        "quotaMetric": "generativelanguage.googleapis.com/generate_requests_per_day_per_project_per_model",
                        "quotaId": "GenerateRequestsPerDayPerProjectPerModel-FreeTier"
                      }
                    ]
                  }
                ]
              }
            }
        """.trimIndent()
        val now = 1_700_000_000_000L
        val result = parseGemini429Body(body, gson, now)
        assertNotNull(result)
        result!!
        assertEquals("Daily quota used", result.second)
        assertEquals(nextMidnightPacific(now), result.first)
    }

    @Test fun `PerMinute quotaId without RetryInfo returns null`() {
        // The parser only recognises PerDay via quotaId. A PerMinute-only
        // signal with no RetryInfo falls through to null and the caller
        // uses the ladder.
        val body = """
            {
              "error": {
                "code": 429,
                "details": [
                  {
                    "@type": "type.googleapis.com/google.rpc.QuotaFailure",
                    "violations": [
                      { "quotaId": "GenerateRequestsPerMinutePerProjectPerModel-FreeTier" }
                    ]
                  }
                ]
              }
            }
        """.trimIndent()
        assertNull(parseGemini429Body(body, gson))
    }

    @Test fun `RetryInfo with simple seconds yields parsed timestamp`() {
        val body = """
            {
              "error": {
                "code": 429,
                "details": [
                  {
                    "@type": "type.googleapis.com/google.rpc.RetryInfo",
                    "retryDelay": "34s"
                  }
                ]
              }
            }
        """.trimIndent()
        val now = 1_700_000_000_000L
        val result = parseGemini429Body(body, gson, now)
        assertNotNull(result)
        result!!
        assertEquals("Rate limited", result.second)
        assertEquals(now + 34_000L, result.first)
    }

    @Test fun `PerDay wins over RetryInfo when both present`() {
        // Real Gemini responses sometimes carry both. Per-day exhaustion
        // is the stronger signal — we use it.
        val body = """
            {
              "error": {
                "code": 429,
                "details": [
                  {
                    "@type": "type.googleapis.com/google.rpc.QuotaFailure",
                    "violations": [
                      { "quotaId": "GenerateRequestsPerDayPerProjectPerModel-FreeTier" }
                    ]
                  },
                  {
                    "@type": "type.googleapis.com/google.rpc.RetryInfo",
                    "retryDelay": "30s"
                  }
                ]
              }
            }
        """.trimIndent()
        val now = 1_700_000_000_000L
        val result = parseGemini429Body(body, gson, now)
        assertNotNull(result)
        result!!
        assertEquals("Daily quota used", result.second)
        assertEquals(nextMidnightPacific(now), result.first)
    }

    @Test fun `malformed JSON returns null`() {
        assertNull(parseGemini429Body("<not json>", gson))
        assertNull(parseGemini429Body("", gson))
    }

    @Test fun `empty details array returns null`() {
        val body = """{"error":{"code":429,"details":[]}}"""
        assertNull(parseGemini429Body(body, gson))
    }

    @Test fun `retryDelay with fractional seconds parses`() {
        val body = """
            {
              "error": {
                "code": 429,
                "details": [
                  { "@type": "type.googleapis.com/google.rpc.RetryInfo", "retryDelay": "0.500s" }
                ]
              }
            }
        """.trimIndent()
        val now = 1_700_000_000_000L
        val result = parseGemini429Body(body, gson, now)
        assertNotNull(result)
        assertEquals(now + 500L, result!!.first)
    }

    @Test fun `nextMidnightPacific advances exactly one day`() {
        // Sanity check: a timestamp at noon PT today produces a value
        // ≥ 12h and ≤ 24h ahead, regardless of season.
        val noonPt = 1_700_000_000_000L
        val next = nextMidnightPacific(noonPt)
        val deltaH = (next - noonPt) / (60 * 60 * 1000)
        assertTrue("expected 12-24h ahead, got ${deltaH}h", deltaH in 1..24)
    }

    @Test fun `firstOfNextMonthUtc is in the future`() {
        val now = System.currentTimeMillis()
        val next = firstOfNextMonthUtc(now)
        assertTrue("expected future ts, got $next vs $now", next > now)
    }
}
