package com.playtranslate.cloud

import com.playtranslate.PtJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The account record's one classifier, [PtCloudAccount.standing], and the one
 * place a wire answer becomes a record, [PtCloudAccount.of]: the five
 * standings, an active record whose dated end has passed reading as ended
 * with that end as its ended date, `source` (never a missing date) as the
 * test for a Worker that sent no account, and the record's JSON round trip,
 * the form it is kept in beside the key.
 */
class PtCloudAccountTest {

    private fun active(source: String?, until: Long?) =
        PtCloudAccount(email = EMAIL, active = true, source = source, entitledUntilMs = until, checkedAtMs = CHECKED)

    private fun inactive(lastSource: String?, endedAt: Long?) =
        PtCloudAccount(email = EMAIL, active = false, lastSource = lastSource, endedAtMs = endedAt, checkedAtMs = CHECKED)

    @Test fun `the five standings`() {
        assertEquals(PtCloudAccount.Standing.TRIAL, active(SOURCE_TRIAL, NOW + DAY).standing(NOW))
        for (source in listOf("kofi", "manual", "play")) {
            assertEquals(source, PtCloudAccount.Standing.MEMBER, active(source, NOW + DAY).standing(NOW))
        }
        assertEquals(PtCloudAccount.Standing.NO_DETAILS, active(null, null).standing(NOW))
        assertEquals(PtCloudAccount.Standing.TRIAL_ENDED, inactive(SOURCE_TRIAL, NOW - DAY).standing(NOW))
        assertEquals(PtCloudAccount.Standing.INACTIVE, inactive("kofi", NOW - DAY).standing(NOW))
    }

    /** No "Trial until" for a date gone by: the server would answer 403 by
     *  now, with the trial as what ended last. */
    @Test fun `an expired active trial reads TRIAL_ENDED, its end the ended date`() {
        val trial = active(SOURCE_TRIAL, NOW - DAY)
        assertEquals(PtCloudAccount.Standing.TRIAL_ENDED, trial.standing(NOW))
        assertEquals(NOW - DAY, trial.endedAt(NOW))
        // Before its end, the same record is on its trial and has ended nothing.
        assertEquals(PtCloudAccount.Standing.TRIAL, trial.standing(NOW - 2 * DAY))
        assertNull(trial.endedAt(NOW - 2 * DAY))
    }

    @Test fun `an expired member reads INACTIVE, its end the ended date`() {
        val lease = active("kofi", NOW - DAY)
        assertEquals(PtCloudAccount.Standing.INACTIVE, lease.standing(NOW))
        assertEquals(NOW - DAY, lease.endedAt(NOW))
    }

    @Test fun `the end is passed at its own instant`() {
        assertEquals(PtCloudAccount.Standing.TRIAL, active(SOURCE_TRIAL, NOW).standing(NOW - 1))
        assertEquals(PtCloudAccount.Standing.TRIAL_ENDED, active(SOURCE_TRIAL, NOW).standing(NOW))
        assertEquals(PtCloudAccount.Standing.INACTIVE, active("manual", NOW).standing(NOW))
    }

    /** A null end beside a source is open-ended: it never passes. */
    @Test fun `a null-date trial or membership never ends`() {
        assertEquals(PtCloudAccount.Standing.TRIAL, active(SOURCE_TRIAL, null).standing(Long.MAX_VALUE))
        assertEquals(PtCloudAccount.Standing.MEMBER, active("manual", null).standing(Long.MAX_VALUE))
        assertNull(active("manual", null).endedAt(Long.MAX_VALUE))
    }

    /** `source` is the test for a Worker that sent no account, never a
     *  missing date: a date with no source says nothing, passed or not. */
    @Test fun `no source is NO_DETAILS whatever the date`() {
        assertEquals(PtCloudAccount.Standing.NO_DETAILS, active(null, NOW + DAY).standing(NOW))
        assertEquals(PtCloudAccount.Standing.NO_DETAILS, active(null, NOW - DAY).standing(NOW))
        assertNull(active(null, NOW - DAY).endedAt(NOW))
    }

    /** Nothing ended (never entitled), or a Worker that left the fields out:
     *  no active membership, never a trial that ended. */
    @Test fun `an inactive record with nothing ended is INACTIVE with no date`() {
        val none = inactive(null, null)
        assertEquals(PtCloudAccount.Standing.INACTIVE, none.standing(NOW))
        assertNull(none.endedAt(NOW))
        assertEquals(NOW - DAY, inactive("manual", NOW - DAY).endedAt(NOW))
    }

    @Test fun `of makes the record of an Accepted and of an Inactive answer`() {
        assertEquals(
            PtCloudAccount(email = EMAIL, active = true, source = SOURCE_TRIAL, entitledUntilMs = NOW + DAY, checkedAtMs = NOW),
            PtCloudAccount.of(KeyCheck.Accepted(SOURCE_TRIAL, NOW + DAY), EMAIL, NOW),
        )
        assertEquals(
            PtCloudAccount(email = null, active = false, lastSource = "kofi", endedAtMs = NOW - DAY, checkedAtMs = NOW),
            PtCloudAccount.of(KeyCheck.Inactive("kofi", NOW - DAY), null, NOW),
        )
        assertEquals(PtCloudAccount.Standing.NO_DETAILS, PtCloudAccount.of(KeyCheck.Accepted(null, null), null, NOW).standing(NOW))
    }

    @Test fun `the record round-trips through its JSON`() {
        for (account in listOf(active(SOURCE_TRIAL, NOW + DAY), inactive(null, null), active(null, null).copy(email = null))) {
            val json = PtJson.lenient.encodeToString(PtCloudAccount.serializer(), account)
            assertEquals(json, account, PtJson.lenient.decodeFromString(PtCloudAccount.serializer(), json))
        }
    }

    private companion object {
        const val EMAIL = "player@example.com"
        const val DAY = 24L * 60 * 60 * 1000
        /** 2026-10-28 12:00 UTC. */
        const val NOW = 1_793_188_800_000L
        const val CHECKED = NOW - 60_000L
    }
}
