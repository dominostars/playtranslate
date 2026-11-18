package com.playtranslate.audio

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The app's audio sources for a JVM test: the one source default playback
 * resolves from while it is installed ([AudioSourceRegistry.enabledForTest]).
 * It records every request it is asked to play ([played]) and answers
 * [outcome], so a test on a real surface sees what the surface spoke.
 *
 * With [hold] set a play stays in flight, as a clip still loading or a
 * sentence still being read does, until the test completes it or the
 * caller's job is cancelled. With none a play returns at once without
 * suspending, as a warm device voice asked to speak a word does.
 *
 * A test that installs it uninstalls it ([uninstall]): the registry is
 * shared by every test in the JVM.
 */
class FakeSpeech(var outcome: PlayOutcome = PlayOutcome.Played) : AudioSource {

    val played = mutableListOf<AudioRequest>()
    var hold: CompletableDeferred<Unit>? = null

    /** Runs as a play starts, for a test that reads what else was true at
     *  that moment. */
    var onPlay: (() -> Unit)? = null

    /** A held play goes on through its caller's cancellation and returns
     *  only when the test completes the hold: work on another thread (a clip
     *  being fetched) that a cancel does not end at once. */
    var holdThroughCancel = false

    fun install() {
        AudioSourceRegistry.enabledForTest = listOf(this)
    }

    override val id = "fake_speech"
    override fun label(ctx: Context) = id
    override val toggleable = false
    override val remote = false
    override fun isEnabled(ctx: Context) = true
    override fun setEnabled(ctx: Context, on: Boolean) {}
    override suspend fun candidates(ctx: Context, req: AudioRequest) = listOf(candidate)
    override suspend fun defaultCandidate(ctx: Context, req: AudioRequest): AudioCandidate = candidate

    override suspend fun play(
        ctx: Context,
        candidate: AudioCandidate,
        req: AudioRequest,
        awaitCompletion: Boolean,
        onStart: (() -> Unit)?,
    ): PlayOutcome {
        played += req
        onPlay?.invoke()
        val held = hold
        if (held != null) {
            if (holdThroughCancel) withContext(NonCancellable) { held.await() } else held.await()
        }
        return outcome
    }

    override suspend fun toFile(ctx: Context, candidate: AudioCandidate, req: AudioRequest): File? = null

    private val candidate = AudioCandidate(id, "k", CandidateLabel.Text("k"))

    companion object {
        fun uninstall() {
            AudioSourceRegistry.enabledForTest = null
        }
    }
}
