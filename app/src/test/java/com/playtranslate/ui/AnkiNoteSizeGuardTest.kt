package com.playtranslate.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AnkiNoteSizeGuard] is the dispatcher's stand-in for the binder: its
 * estimate decides whether a note is sent styled, simplified, or refused.
 * The math is pinned here because the failure it prevents is silent — an
 * over-budget insert dies in the kernel and ContentResolver.insert
 * returns null with no exception, which used to surface as "make sure
 * AnkiDroid is running".
 */
class AnkiNoteSizeGuardTest {

    @Test fun `estimate is two bytes per char plus fixed overhead`() {
        assertEquals(4_096, AnkiNoteSizeGuard.estimatedParcelBytes(emptyList()))
        assertEquals(
            10 * 2 + 4_096,
            AnkiNoteSizeGuard.estimatedParcelBytes(listOf("0123456789")),
        )
        // Fields sum; empty fields cost nothing beyond the overhead.
        assertEquals(
            (3 + 4) * 2 + 4_096,
            AnkiNoteSizeGuard.estimatedParcelBytes(listOf("abc", "", "defg")),
        )
    }

    @Test fun `CJK chars count the same as ASCII`() {
        // Java Strings marshal as UTF-16: one BMP char = 2 bytes whether
        // ASCII or kanji. (Surrogate pairs are two Chars and are counted
        // as such by String.length, matching their 4-byte parcel cost.)
        assertEquals(
            AnkiNoteSizeGuard.estimatedParcelBytes(listOf("abcde")),
            AnkiNoteSizeGuard.estimatedParcelBytes(listOf("日本語辞書")),
        )
    }

    @Test fun `the recorded failure would have been caught`() {
        // The 2026-08-29 report: a 1,684,376-byte parcel, i.e. ~840K chars
        // of field text. Must sit over the degrade budget.
        val monster = listOf("x".repeat(840_000))
        assertTrue(
            AnkiNoteSizeGuard.estimatedParcelBytes(monster) >
                AnkiNoteSizeGuard.BUDGET_BYTES,
        )
    }

    @Test fun `a typical styled card stays under budget`() {
        // The same session's successful send parceled at ~389KB (~190K
        // chars) — styled cards of that size must keep sending untouched.
        val typical = listOf("x".repeat(190_000))
        assertTrue(
            AnkiNoteSizeGuard.estimatedParcelBytes(typical) <=
                AnkiNoteSizeGuard.BUDGET_BYTES,
        )
    }

    @Test fun `budget leaves headroom under the hard limit and the binder cap`() {
        // The probe approves payloads up to BUDGET_BYTES with null media
        // filenames; the final assembly adds media tags. The hard-limit
        // backstop must not be trippable by that delta, and the hard
        // limit itself must sit under the ~1MB binder transaction cap.
        assertTrue(AnkiNoteSizeGuard.BUDGET_BYTES + 8_192 < AnkiNoteSizeGuard.HARD_LIMIT_BYTES)
        assertTrue(AnkiNoteSizeGuard.HARD_LIMIT_BYTES < 1_000_000)
    }
}
