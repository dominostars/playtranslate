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

    /**
     * Cooldown using a retry timestamp we parsed directly from the
     * provider's response (Gemini retryDelay, OpenAI retry-after,
     * DeepL Pro end_time, etc.). The ladder rung is NOT advanced —
     * the parsed signal is more accurate than any ladder default.
     *
     * No-ops if the backend is already in cooldown, so a batched
     * fan-out where 5 texts each see the same 429 only counts once.
     */
    @Synchronized
    fun recordParsedFailure(retryAt: Long, description: String) {
        if (inCooldown()) return
        untilMs = retryAt
        descriptionText = description
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
    fun recordLadderFailure(ladder: CooldownLadder, description: String) {
        if (inCooldown()) return
        val duration = ladderDuration(ladder, rung)
        untilMs = nowMs() + duration.inWholeMilliseconds
        descriptionText = description
        setAtMs = nowMs()
        rung = (rung + 1).coerceAtMost(MAX_RUNG)
        persist()
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
        recordLadderFailure(CooldownLadder.Network, description)
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
        sp?.edit {
            remove(keyUntil())
            remove(keyDescription())
        }
    }

    private fun persist() {
        val sp = sp ?: return
        sp.edit {
            val u = untilMs
            if (u == null) remove(keyUntil()) else putLong(keyUntil(), u)
            val d = descriptionText
            if (d.isNullOrBlank()) remove(keyDescription()) else putString(keyDescription(), d)
            if (rung == 0) remove(keyRung()) else putInt(keyRung(), rung)
        }
    }

    private fun keyUntil() = "$backendId.until"
    private fun keyDescription() = "$backendId.description"
    private fun keyRung() = "$backendId.rung"

    companion object {
        private const val PREF_FILE = "playtranslate_cooldown"

        /** Cap on the ladder rung — the top tier (4h) stays put forever
         *  until a [recordSuccess] resets it. */
        private const val MAX_RUNG = 3

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
