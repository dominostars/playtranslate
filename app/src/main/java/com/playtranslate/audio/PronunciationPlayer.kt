package com.playtranslate.audio

import android.content.Context
import com.playtranslate.tts.TtsEngine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The single entry point for "speak this" and the single owner of [stop] across
 * both audio backends (recordings via [RecordingPlayer], synthesis via
 * [TtsEngine]). Every speak site routes through here so a new utterance halts
 * whatever was playing — no cross-backend talk-over.
 *
 * Default playback walks enabled sources in priority order (Commons, then the
 * TTS floor) and plays the first source's [AudioSource.defaultCandidate]. A
 * recoverable failure (offline/decode), no-result, OR a blown time budget
 * degrades to the next source; TTS is the guaranteed floor.
 *
 * Live taps ([awaitCompletion] = false) TIME-BOUND each remote
 * ([AudioSource.remote]) source to [remoteBudgetMs] so a slow/offline/captive
 * network falls back to the local TTS floor promptly instead of hanging. Awaited
 * callers (e.g. the Anki preview, which shows a spinner) are not time-capped, so
 * a long recording isn't cut off mid-play.
 */
object PronunciationPlayer {

    /** Time budget for a remote source on the live (non-awaited) playback path. */
    const val REMOTE_BUDGET_MS = 2500L

    suspend fun play(
        ctx: Context,
        req: AudioRequest,
        awaitCompletion: Boolean = false,
        onStart: (() -> Unit)? = null,
        sources: List<AudioSource> = AudioSourceRegistry.enabledInOrder(ctx),
        remoteBudgetMs: Long = REMOTE_BUDGET_MS,
    ): PlayOutcome {
        stop() // halt any in-flight audio (either backend) before starting new
        for (source in sources) {
            when (val outcome = attempt(ctx, source, req, awaitCompletion, onStart, remoteBudgetMs)) {
                PlayOutcome.Played,
                PlayOutcome.TtsNoEngine,
                is PlayOutcome.TtsLanguageUnsupported -> return outcome
                is PlayOutcome.Failed -> if (!outcome.recoverable) return outcome // else fall through
                PlayOutcome.NoResult, null -> Unit // no result / remote timed out → next source
            }
        }
        return PlayOutcome.NoResult
    }

    /** One source's attempt. Returns null only when a remote source exceeded its
     *  live budget — treated like "no result", i.e. fall through to the next. */
    private suspend fun attempt(
        ctx: Context,
        source: AudioSource,
        req: AudioRequest,
        awaitCompletion: Boolean,
        onStart: (() -> Unit)?,
        remoteBudgetMs: Long,
    ): PlayOutcome? {
        val tryOnce: suspend () -> PlayOutcome = {
            val candidate = runCatching { source.defaultCandidate(ctx, req) }.getOrNull()
            if (candidate == null) {
                PlayOutcome.NoResult
            } else {
                runCatching { source.play(ctx, candidate, req, awaitCompletion, onStart) }
                    .getOrElse { PlayOutcome.Failed(recoverable = true) }
            }
        }
        return if (source.remote && !awaitCompletion) {
            withTimeoutOrNull(remoteBudgetMs) { tryOnce() }
        } else {
            tryOnce()
        }
    }

    fun stop() {
        RecordingPlayer.stop()
        TtsEngine.stop()
    }
}
