package com.playtranslate.cloud

import kotlinx.serialization.Serializable

/**
 * What the app knows of the PlayTranslate Cloud account behind the stored
 * client key, kept beside the key as a JSON pref
 * ([com.playtranslate.Prefs.ptCloudAccountJson], read through
 * [PtCloudConfig.account]) for the cloud page, the Settings digest and the
 * Translation services row, which all need it between checks, and for the
 * two routes and the controls, which keep a record the server wrote as
 * inactive off the cloud ([PtCloudConfig.entitled]: the server's last
 * word, [active]; the clock's reading, [standing], is for words). Not a secret:
 * it holds no credential, and the key stays in its encrypted slot. The
 * [email] is never logged, and is sent only to the two sign-in routes.
 *
 * Made in one place, [of], from an answer that describes the account: a
 * verify's 200, and the stored key's check on a resume or on Check again.
 * Written only beside a key that was stored
 * ([PtCloudService.saveClientKey], [PtCloudService.onStoredKeyChecked]),
 * and read through one classifier, [standing].
 */
@Serializable
data class PtCloudAccount(
    /** The address the member signed in with; null for a pasted key, whose
     *  address the app was never told. */
    val email: String? = null,
    /** Whether an entitlement was active at [checkedAtMs]. */
    val active: Boolean,
    /** Active: the entitlement that runs longest, `trial`, `kofi`, `manual`
     *  or `play`; null when the Worker sent none (an older one, or its read
     *  failed). */
    val source: String? = null,
    /** When [source] ends; null beside a [source] is open-ended. */
    val entitledUntilMs: Long? = null,
    /** Inactive: the entitlement that ended most recently; null when none
     *  has ended or the Worker sent none. */
    val lastSource: String? = null,
    /** When [lastSource] ended. */
    val endedAtMs: Long? = null,
    /** When the server last described the account (wall clock). */
    val checkedAtMs: Long,
) {

    /** What the account is now, the one reading every surface words. Words
     *  only: whether the cloud may serve the account is [active], the
     *  server's last word, read through [PtCloudConfig.entitled]
     *  (2026-10-29), so a standing the clock reached ([TRIAL_ENDED] from a
     *  trial's end passing) refuses nothing on its own. */
    enum class Standing {
        /** On the free trial. */
        TRIAL,
        /** A membership is active (Ko-fi, hand-issued, or a store's), as
         *  the server last said: a lease whose end has passed on the clock
         *  is still this, since a payment the device has not read renews
         *  it. */
        MEMBER,
        /** The key is good and nothing more is known: a key with no record
         *  (one saved before records, or a record that did not decode), or
         *  an answer from a Worker that sent no [source]. */
        NO_DETAILS,
        /** The free trial ended and nothing replaced it, as the server
         *  said, or as the clock reads a trial's end passing. */
        TRIAL_ENDED,
        /** No active membership, as the server said: one that ended, or
         *  never one at all. */
        INACTIVE,
    }

    /**
     * The account's standing at [nowMs], the one classifier of a record, so
     * no surface says "Trial until" for a date gone by. An active trial
     * whose [entitledUntilMs] has passed reads as [Standing.TRIAL_ENDED],
     * its end being the ended date ([endedAt]): a trial never renews, so
     * the server would answer so by then unless the member joined meanwhile,
     * which the next check or the next answer corrects. An active membership
     * stays [Standing.MEMBER] whatever its date reads: a Ko-fi membership is
     * a lease of 38 days per payment, and the payment that renews it writes
     * a lease the device has not read until the next check, so a passed end
     * says nothing of the membership (Codex adversarial review, 2026-10-29);
     * the page leaves such a date out, and the server's 403 is what ends an
     * account ([PtCloudConfig.entitled] routes by [active] alone). An
     * inactive record is [Standing.TRIAL_ENDED] when the trial is what ended
     * last, else [Standing.INACTIVE], nothing having ended included. A null
     * [source] is [Standing.NO_DETAILS], however the dates read: [source] is
     * the test for a Worker that sent no account, never a missing date. A key
     * with no record is NO_DETAILS at every reader.
     */
    fun standing(nowMs: Long): Standing = when {
        !active -> if (lastSource == SOURCE_TRIAL) Standing.TRIAL_ENDED else Standing.INACTIVE
        source == null -> Standing.NO_DETAILS
        source == SOURCE_TRIAL -> if (trialOver(nowMs)) Standing.TRIAL_ENDED else Standing.TRIAL
        else -> Standing.MEMBER
    }

    /** When the account's last entitlement ended, for an ended [standing]:
     *  [endedAtMs] as the server said, or [entitledUntilMs] for an active
     *  trial whose end has passed since. Null when no date is known. */
    fun endedAt(nowMs: Long): Long? = if (trialOver(nowMs)) entitledUntilMs else endedAtMs

    /** An active trial whose dated end has passed. */
    private fun trialOver(nowMs: Long): Boolean =
        active && source == SOURCE_TRIAL && entitledUntilMs != null && entitledUntilMs <= nowMs

    companion object {
        /** The record of [answer], the only place a wire answer becomes one:
         *  [email] as the caller knows it (the address of a sign-in, the
         *  record's own on a re-check, null for a pasted key), checked at
         *  [nowMs]. */
        fun of(answer: KeyCheck.Account, email: String?, nowMs: Long): PtCloudAccount = when (answer) {
            is KeyCheck.Accepted -> PtCloudAccount(
                email = email, active = true, source = answer.source,
                entitledUntilMs = answer.entitledUntilMs, checkedAtMs = nowMs,
            )
            is KeyCheck.Inactive -> PtCloudAccount(
                email = email, active = false, lastSource = answer.lastSource,
                endedAtMs = answer.endedAtMs, checkedAtMs = nowMs,
            )
        }
    }
}
