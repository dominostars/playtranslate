package com.playtranslate

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.audio.AudioCandidate
import com.playtranslate.audio.AudioRequest
import com.playtranslate.audio.AudioSource
import com.playtranslate.audio.CandidateLabel
import com.playtranslate.audio.PlayOutcome
import com.playtranslate.audio.PronunciationPlayer
import com.playtranslate.language.SourceLangId
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Guards the live-playback resolver's fallback behavior — the adversarial-review
 * concerns: an empty remote source must reach the TTS floor, and a slow remote
 * source must NOT hang the tap (it falls back within its time budget).
 */
@RunWith(RobolectricTestRunner::class)
class PronunciationPlayerTest {

    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val req = AudioRequest.word("x", null, SourceLangId.EN)
    private fun cand(id: String) = AudioCandidate(id, "k", CandidateLabel.Text("k"))

    private class FakeSource(
        override val id: String,
        override val remote: Boolean,
        private val candidate: AudioCandidate?,
        private val outcome: PlayOutcome,
        private val resolveDelayMs: Long = 0,
    ) : AudioSource {
        var playCalled = false
        override fun label(ctx: Context) = id
        override val toggleable = remote
        override fun isEnabled(ctx: Context) = true
        override fun setEnabled(ctx: Context, on: Boolean) {}
        override suspend fun candidates(ctx: Context, req: AudioRequest) = listOfNotNull(candidate)
        override suspend fun defaultCandidate(ctx: Context, req: AudioRequest): AudioCandidate? {
            if (resolveDelayMs > 0) delay(resolveDelayMs)
            return candidate
        }
        override suspend fun play(
            ctx: Context, candidate: AudioCandidate, req: AudioRequest,
            awaitCompletion: Boolean, onStart: (() -> Unit)?,
        ): PlayOutcome {
            playCalled = true
            return outcome
        }
        override suspend fun toFile(ctx: Context, candidate: AudioCandidate, req: AudioRequest): File? = null
    }

    @Test fun falls_back_to_tts_when_remote_has_no_results() = runBlocking {
        val commons = FakeSource("wikimedia_commons", remote = true, candidate = null, outcome = PlayOutcome.Failed(true))
        val tts = FakeSource("tts", remote = false, candidate = cand("tts"), outcome = PlayOutcome.Played)
        val result = PronunciationPlayer.play(ctx, req, sources = listOf(commons, tts))
        assertEquals(PlayOutcome.Played, result)
        assertFalse("no candidate → remote never plays", commons.playCalled)
        assertTrue("TTS floor plays", tts.playCalled)
    }

    @Test fun falls_back_to_tts_when_remote_exceeds_budget() = runBlocking {
        val commons = FakeSource(
            "wikimedia_commons", remote = true, candidate = cand("c"),
            outcome = PlayOutcome.Played, resolveDelayMs = 5_000,
        )
        val tts = FakeSource("tts", remote = false, candidate = cand("tts"), outcome = PlayOutcome.Played)
        val result = PronunciationPlayer.play(ctx, req, sources = listOf(commons, tts), remoteBudgetMs = 50)
        assertEquals(PlayOutcome.Played, result)
        assertFalse("resolution timed out before play", commons.playCalled)
        assertTrue("TTS floor plays promptly", tts.playCalled)
    }
}
