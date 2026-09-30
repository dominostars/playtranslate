package com.playtranslate.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The settings pages' cooldown line, through the real string resources: a
 * cooldown ending within 24h reads "Retry at" and a time, one ending 24h out
 * or later "Retry on" and a date, and a missing description reads as
 * "Unavailable". Robolectric for the resources and the locale's time
 * format; the clock is fixed, and the default zone and locale with it, so
 * the date reads the same on every machine.
 */
@RunWith(RobolectricTestRunner::class)
class CooldownLineTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private lateinit var savedZone: TimeZone
    private lateinit var savedLocale: Locale

    @Before fun setUp() {
        savedZone = TimeZone.getDefault()
        savedLocale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Locale.setDefault(Locale.US)
    }

    @After fun tearDown() {
        TimeZone.setDefault(savedZone)
        Locale.setDefault(savedLocale)
    }

    private fun time(retryAt: Long) = android.text.format.DateFormat.getTimeFormat(ctx).format(Date(retryAt))

    @Test fun `a cooldown ending within a day reads Retry at and the time`() {
        val retryAt = NOW + 10 * 60_000L
        assertEquals("Rate limited · Retry at ${time(retryAt)}", cooldownLine(ctx, "Rate limited", retryAt, NOW))
    }

    @Test fun `a cooldown ending a day out or later reads Retry on and the date`() {
        assertEquals(
            "Monthly quota used · Retry on Jun 3",
            cooldownLine(ctx, "Monthly quota used", NOW + 3 * DAY_MS, NOW),
        )
    }

    @Test fun `the day boundary is 24h to the millisecond`() {
        val justInside = NOW + DAY_MS - 1
        assertEquals("Rate limited · Retry at ${time(justInside)}", cooldownLine(ctx, "Rate limited", justInside, NOW))
        assertEquals("Rate limited · Retry on Jun 1", cooldownLine(ctx, "Rate limited", NOW + DAY_MS, NOW))
    }

    @Test fun `no description reads as Unavailable`() {
        val retryAt = NOW + 10 * 60_000L
        assertEquals("Unavailable · Retry at ${time(retryAt)}", cooldownLine(ctx, null, retryAt, NOW))
    }

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
        /** 2026-05-31 12:00 UTC. */
        const val NOW = 1_780_228_800_000L
    }
}
