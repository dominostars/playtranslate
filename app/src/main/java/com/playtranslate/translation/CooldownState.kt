package com.playtranslate.translation

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Which tiered ladder to advance through when a failure carries no
 * parsed retry signal. The two ladders differ at the lower rungs — a
 * rate-limit/5xx response is a stronger signal than a single network
 * blip, so it earns a longer first cooldown.
 *
 * See [project_backend_cooldown_plan] in memory for the full design
 * rationale.
 */
enum class CooldownLadder { RateLimit, Network }

/**
 * WHY a backend is cooling down — recorded alongside every cooldown and
 * persisted with it, so user-facing messaging can distinguish states
 * whose corrective action differs (Codex adversarial find: a bare
 * timestamp let a billing-exhausted account render as "rate limited").
 */
enum class CooldownCause {
    RATE_LIMITED, SERVER_ERROR, CONNECTION_FAILED, DAILY_QUOTA, MONTHLY_QUOTA, BILLING;

    /** Collapse to the user-facing message class. Users can't act
     *  differently on a 429 vs a 5xx vs a lingering network-ladder
     *  cooldown, so those merge into TRANSIENT ("unavailable until X");
     *  quota and billing keep their own wording because the correct
     *  user action differs (wait for the reset / fix the account). */
    val messageClass: CooldownMessageClass
        get() = when (this) {
            RATE_LIMITED, SERVER_ERROR, CONNECTION_FAILED -> CooldownMessageClass.TRANSIENT
            DAILY_QUOTA, MONTHLY_QUOTA -> CooldownMessageClass.QUOTA
            BILLING -> CooldownMessageClass.ACCOUNT
        }
}

/** The three user-facing wordings a cooldown can surface as. ACCOUNT
 *  deliberately renders WITHOUT a retry time: billing re-probes loop
 *  every few minutes, so any "until X" there would be a false promise. */
enum class CooldownMessageClass { TRANSIENT, QUOTA, ACCOUNT }

/**
 * Per-backend state machine implementing the [Cooldownable] capability.
 *
 * Each cooldown-capable backend owns one [CooldownState] instance,
 * constructed with the [Context] (for `SharedPreferences`) and the
 * backend's [BackendId] (used as the pref key prefix). State persists
 * to a dedicated `playtranslate_cooldown` SharedPreferences namespace
 * so cooldowns survive process restarts — avoids burning one wasted
 * call on every cold launch when a backend was already known-down.
 *
 * Concurrency: all mutating methods are `@Synchronized` against the
 * instance. The "already in cooldown — ignore concurrent failures"
 * guard in [recordParsedFailure] / [recordLadderFailure] handles the
 * batched-fan-out case where 5 texts in the same batch each fail at
 * the same backend: only the first call advances the rung, the rest
 * are absorbed.
 */
class CooldownState(
    context: Context?,
    private val backendId: BackendId,
    /** Time source — injectable for unit tests so they can fast-forward
     *  past a cooldown window without sleeping. Defaults to wall clock. */
    internal var nowMs: () -> Long = { System.currentTimeMillis() },
) : Cooldownable {

    /** When non-null, the in-memory state is mirrored to a
     *  `playtranslate_cooldown` SharedPreferences namespace so cooldowns
     *  survive process restarts. Null in unit tests — the state machine
     *  still works, it just doesn't persist. */
    private val sp: SharedPreferences? = context?.applicationContext
        ?.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)

    @Volatile private var untilMs: Long? =
        sp?.getLong(keyUntil(), 0L)?.takeIf { it > 0L }
    @Volatile private var descriptionText: String? =
        sp?.getString(keyDescription(), null)?.takeIf { it.isNotBlank() }
    @Volatile private var rung: Int = sp?.getInt(keyRung(), 0) ?: 0
    @Volatile private var causeValue: CooldownCause? = sp?.getString(keyCause(), null)
        ?.let { raw -> CooldownCause.entries.firstOrNull { it.name == raw } }

    init {
        // A cooldown restored from a PREVIOUS process has its cause in
        // that process's logs, which no logcat export will contain —
        // announce it here so a launch-time skip is explicable from this
        // session's log alone.
        val restored = untilMs
        if (restored != null && restored > nowMs()) {
            android.util.Log.i(
                "CooldownState",
                "$backendId: restored persisted cooldown, " +
                    "${(restored - nowMs()) / 1000}s remaining (${descriptionText ?: "?"})"
            )
        }
    }

    /** Epoch-ms when the current cooldown was set. Used by [recordSuccess]
     *  to refuse to clear a cooldown that's newer than the success's
     *  attempt-start timestamp (otherwise a stale in-flight success from
     *  a parallel waterfall pass could erase a cooldown recorded by a
     *  later failure). In-memory only — process restart loses the
     *  comparison, but the persisted `untilMs` is in the future so any
     *  cold-start success will pass through the in-cooldown skip path
     *  rather than reach recordSuccess. */
    @Volatile private var setAtMs: Long? = null

    /** First-IOException-ignored heuristic: tracks when the most recent
     *  network failure landed. In-memory only — a process restart resets
     *  the counter (correct: a fresh process has no history to debounce
     *  against). */
    @Volatile private var lastIOExceptionAtMs: Long? = null

    override fun unavailableUntil(): Long? {
        val until = untilMs ?: return null
        if (until <= nowMs()) {
            // Expired — leave the persisted rung intact (it resets on
            // recordSuccess, not on natural expiry) but null out the
            // timestamp so callers stop seeing this backend as down.
            clearTimestampOnly()
            return null
        }
        return until
    }

    override fun unavailableDescription(): String? {
        if (unavailableUntil() == null) return null
        return descriptionText
    }

    override fun unavailableCause(): CooldownCause? {
        if (unavailableUntil() == null) return null
        return causeValue
    }

    /**
     * Cooldown using a retry timestamp we parsed directly from the
     * provider's response (Gemini retryDelay, OpenAI retry-after,
     * DeepL Pro end_time, etc.). The ladder rung is NOT advanced —
     * the parsed signal is more accurate than any ladder default.
     *
     * No-ops if the backend is already in cooldown, so a batched
     * fan-out where 5 texts each see the same 429 only counts once.
     *
     * DELIBERATE first-writer-wins across CAUSES too: at a provider's
     * state-transition boundary, a transient 429 racing an in-flight
     * sibling's quota/billing response can record first and keep the
     * TRANSIENT cause. Accepted as bounded: the wrongly-labeled
     * cooldown is by construction the short kind (parsed retry delay
     * or a low ladder rung), and its expiry triggers the retry that
     * receives the provider's now-stable answer and records the right
     * cause via this same method. Cause-upgrade semantics were
     * considered and rejected — they'd need a cause ordering, retryAt
     * replacement rules, and rung/stale-guard interactions to shave
     * under two minutes off a rare self-healing mislabel (2026-08-29
     * Codex adversarial finding, assessed real-but-marginal).
     */
    @Synchronized
    fun recordParsedFailure(
        retryAt: Long,
        description: String,
        /** Defaults to the transient class, which every wording is safe
         *  for; pass the real cause at any site where it is known. */
        cause: CooldownCause = CooldownCause.RATE_LIMITED,
    ) {
        if (inCooldown()) return
        untilMs = retryAt
        descriptionText = description
        causeValue = cause
        setAtMs = nowMs()
        persist()
    }

    /**
     * Cooldown when the provider gave no parseable retry signal — fall
     * through to the [ladder] for the current rung, then advance the
     * rung (so subsequent failures escalate). Capped at the top rung.
     *
     * Like [recordParsedFailure], no-ops if already in cooldown.
     */
    @Synchronized
    fun recordLadderFailure(
        ladder: CooldownLadder,
        description: String,
        cause: CooldownCause = CooldownCause.RATE_LIMITED,
    ) {
        if (inCooldown()) return
        val duration = ladderDuration(ladder, rung)
        untilMs = nowMs() + duration.inWholeMilliseconds
        descriptionText = description
        causeValue = cause
        setAtMs = nowMs()
        rung = (rung + 1).coerceAtMost(MAX_RUNG)
        persist()
    }

    /**
     * Rate-limit / blocked response whose only retry signal is the
     * standard `Retry-After` header. Both RFC 9110 forms are parsed —
     * delta-seconds and the IMF-fixdate (RFC 1123) HTTP-date; garbage,
     * the archaic RFC 850 / asctime date forms, and a date not in the
     * future all fall through to the [CooldownLadder.RateLimit] ladder
     * (a past date is "no usable signal", not "retry immediately").
     * A parsed value is capped at [MAX_PARSED_RETRY_AFTER] (the
     * ladder's own top rung) so a bogus header or a skewed device
     * clock can't latch the backend out for days — an unkeyed backend
     * has no credentials-change path to clear a runaway cooldown.
     *
     * Reusable by any HTTP backend without a provider-specific retry
     * signal; provider-specific parsers (Gemini's `error.details[]`,
     * OpenAI's `x-ratelimit-reset-*` dialects) stay in their backends
     * and call [recordParsedFailure] / [recordLadderFailure] directly.
     */
    fun recordRetryAfterFailure(retryAfterHeader: String?, description: String) {
        val retryAt = parseRetryAfterMs(retryAfterHeader)
        if (retryAt != null) {
            recordParsedFailure(retryAt, description, CooldownCause.RATE_LIMITED)
        } else {
            recordLadderFailure(CooldownLadder.RateLimit, description, CooldownCause.RATE_LIMITED)
        }
    }

    /** Epoch-ms retry time from a `Retry-After` header value, or null
     *  when the header carries no usable future signal. */
    private fun parseRetryAfterMs(header: String?): Long? {
        val value = header?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val now = nowMs()
        val capMs = MAX_PARSED_RETRY_AFTER.inWholeMilliseconds
        val seconds = value.toLongOrNull()
        if (seconds != null) {
            if (seconds <= 0) return null
            // Duration saturates instead of overflowing on huge inputs.
            return now + seconds.seconds.coerceAtMost(MAX_PARSED_RETRY_AFTER).inWholeMilliseconds
        }
        val dateMs = try {
            java.time.ZonedDateTime
                .parse(value, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                .toInstant().toEpochMilli()
        } catch (_: java.time.DateTimeException) {
            return null
        }
        if (dateMs <= now) return null
        return dateMs.coerceAtMost(now + capMs)
    }

    /**
     * Network / connection failures: the first one in a long while is
     * ignored (wifi blips happen) — only a second within
     * [IO_PAIR_WINDOW_MS] escalates to the network ladder. This keeps
     * a single mid-capture hiccup from penalising the backend with a
     * 30 s skip.
     */
    @Synchronized
    fun recordNetworkFailure(description: String) {
        val now = nowMs()
        val last = lastIOExceptionAtMs
        lastIOExceptionAtMs = now
        if (last == null || now - last > IO_PAIR_WINDOW_MS) {
            // First failure (or no recent one): remember and forgive.
            return
        }
        recordLadderFailure(CooldownLadder.Network, description, CooldownCause.CONNECTION_FAILED)
    }

    @Synchronized
    override fun recordSuccess(attemptStartedAtMs: Long) {
        // Stale-success guard: if a cooldown was set AFTER this attempt
        // started, the success is from an in-flight call that pre-dates
        // the failure. Don't clear — the failure's signal is fresher.
        val set = setAtMs
        if (set != null && set > attemptStartedAtMs) return

        if (untilMs == null && rung == 0 && descriptionText == null &&
            lastIOExceptionAtMs == null && setAtMs == null) {
            return
        }
        untilMs = null
        descriptionText = null
        causeValue = null
        rung = 0
        setAtMs = null
        lastIOExceptionAtMs = null
        persist()
    }

    private fun inCooldown(): Boolean {
        val u = untilMs ?: return false
        return u > nowMs()
    }

    private fun clearTimestampOnly() {
        // Only the renderer path reaches this — the pref update is
        // best-effort, not @Synchronized, to keep status reads fast.
        // Worst case is a stale Long survives until the next mutating
        // call.
        untilMs = null
        descriptionText = null
        causeValue = null
        sp?.edit {
            remove(keyUntil())
            remove(keyDescription())
            remove(keyCause())
        }
    }

    private fun persist() {
        val sp = sp ?: return
        sp.edit {
            val u = untilMs
            if (u == null) remove(keyUntil()) else putLong(keyUntil(), u)
            val d = descriptionText
            if (d.isNullOrBlank()) remove(keyDescription()) else putString(keyDescription(), d)
            val c = causeValue
            if (c == null) remove(keyCause()) else putString(keyCause(), c.name)
            if (rung == 0) remove(keyRung()) else putInt(keyRung(), rung)
        }
    }

    private fun keyUntil() = "$backendId.until"
    private fun keyDescription() = "$backendId.description"
    private fun keyRung() = "$backendId.rung"
    private fun keyCause() = "$backendId.cause"

    companion object {
        private const val PREF_FILE = "playtranslate_cooldown"

        /** Cap on the ladder rung — the top tier (4h) stays put forever
         *  until a [recordSuccess] resets it. */
        private const val MAX_RUNG = 3

        /** Ceiling for a parsed `Retry-After` value — matches the rate-
         *  limit ladder's top rung so a header-driven cooldown can never
         *  exceed what repeated unsignaled failures would earn. */
        private val MAX_PARSED_RETRY_AFTER = 4.hours

        /** Two IOExceptions within this window are treated as a real
         *  pattern; an isolated one is forgiven. 60 s is wide enough to
         *  catch a mid-capture pair without rolling tens of seconds of
         *  unrelated work into the same "pair". */
        private const val IO_PAIR_WINDOW_MS = 60_000L

        private fun ladderDuration(ladder: CooldownLadder, rung: Int): Duration {
            val rungs = when (ladder) {
                CooldownLadder.RateLimit -> RATE_LIMIT_LADDER
                CooldownLadder.Network   -> NETWORK_LADDER
            }
            return rungs[rung.coerceIn(0, rungs.lastIndex)]
        }

        private val RATE_LIMIT_LADDER = listOf(
            1.minutes, 10.minutes, 60.minutes, 4.hours,
        )

        private val NETWORK_LADDER = listOf(
            30.seconds, 5.minutes, 30.minutes, 4.hours,
        )
    }
}
