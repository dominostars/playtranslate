package com.playtranslate.ui

import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale
import java.util.TimeZone

/**
 * The account's past dates ([CooldownTimeLabel.formatDate]): a short date in
 * the device's locale, with the year only when it is not this year's; and
 * its future ones ([CooldownTimeLabel.formatDateTime]): the same date with
 * the time of day, in the device's 12 or 24 hour setting. The zone and
 * locale are fixed, so the dates read the same on every machine;
 * Robolectric for the platform's best-pattern lookup and the setting.
 */
@RunWith(RobolectricTestRunner::class)
class CooldownTimeLabelTest {

    private val ctx = ApplicationProvider.getApplicationContext<Context>()
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

    @Test fun `a date this year reads month and day`() {
        assertEquals("Oct 3", CooldownTimeLabel.formatDate(NOW - 25 * DAY_MS, NOW))
    }

    @Test fun `a date in another year carries its year`() {
        assertEquals("Dec 30, 2025", CooldownTimeLabel.formatDate(NOW - 302 * DAY_MS, NOW))
    }

    /** The year is the device zone's: an instant just before midnight UTC
     *  on December 31 is this year in UTC and the next one east of it. */
    @Test fun `the year is read in the device's zone`() {
        val newYearsEve = 1_798_761_540_000L // 2026-12-31 23:59 UTC
        assertEquals("Dec 31", CooldownTimeLabel.formatDate(newYearsEve, NOW))
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
        assertEquals("Jan 1, 2027", CooldownTimeLabel.formatDate(newYearsEve, NOW))
    }

    @Test fun `a date and time this year reads month, day and the time in 12 hours`() {
        hours("12")
        assertEquals("Nov 2, 3:42 PM", CooldownTimeLabel.formatDateTime(ctx, NOV_2_1542, NOW))
    }

    @Test fun `a date and time this year reads the time in 24 hours when the device is set to`() {
        hours("24")
        assertEquals("Nov 2, 15:42", CooldownTimeLabel.formatDateTime(ctx, NOV_2_1542, NOW))
    }

    /** formatDate's year rule: a past year's instant carries its year, and
     *  so does a next year's, the one a trial or a membership can end in. */
    @Test fun `a date and time in another year carries its year`() {
        hours("12")
        assertEquals("Dec 30, 2025, 12:00 PM", CooldownTimeLabel.formatDateTime(ctx, NOW - 302 * DAY_MS, NOW))
        assertEquals("Jan 6, 2027, 3:42 PM", CooldownTimeLabel.formatDateTime(ctx, NOV_2_1542 + 65 * DAY_MS, NOW))
    }

    /** The device's 12 or 24 hour setting, as Settings → Date & time writes
     *  it ("12" or "24"). */
    private fun hours(setting: String) {
        Settings.System.putString(ctx.contentResolver, Settings.System.TIME_12_24, setting)
    }

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
        /** 2026-10-28 12:00 UTC. */
        const val NOW = 1_793_188_800_000L
        /** 2026-11-02 15:42 UTC. */
        const val NOV_2_1542 = NOW + 5 * DAY_MS + (3 * 60 + 42) * 60_000L
    }
}
