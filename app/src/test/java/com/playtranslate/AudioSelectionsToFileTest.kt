package com.playtranslate

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.audio.Attribution
import com.playtranslate.audio.AudioCandidate
import com.playtranslate.audio.AudioRequest
import com.playtranslate.audio.AudioSelection
import com.playtranslate.audio.AudioSelections
import com.playtranslate.audio.AudioSource
import com.playtranslate.audio.CandidateLabel
import com.playtranslate.audio.PlayOutcome
import com.playtranslate.language.SourceLangId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Send-path resolution (adversarial-review finding): when a user's EXPLICIT pick
 * can't be produced (evicted + offline, dead URL), [AudioSelections.toFile] must
 * return null so the send pipeline reports the audio as missing — it must NOT
 * silently substitute the Auto resolution and ship a different recording.
 */
@RunWith(RobolectricTestRunner::class)
class AudioSelectionsToFileTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val req = AudioRequest.word("行く", "いく", SourceLangId.JA)

    /** Minimal AudioSource whose [toFile] yields [out] (null = can't produce). */
    private class FakeSource(override val id: String, private val out: File?) : AudioSource {
        override fun label(ctx: Context) = id
        override val toggleable = false
        override val remote = false
        override fun isEnabled(ctx: Context) = true
        override fun setEnabled(ctx: Context, on: Boolean) {}
        override suspend fun candidates(ctx: Context, req: AudioRequest) = emptyList<AudioCandidate>()
        override suspend fun defaultCandidate(ctx: Context, req: AudioRequest) =
            AudioCandidate(sourceId = id, key = "auto", title = CandidateLabel.Text("auto"))
        override suspend fun play(
            ctx: Context, candidate: AudioCandidate, req: AudioRequest,
            awaitCompletion: Boolean, onStart: (() -> Unit)?,
        ) = PlayOutcome.Played
        override suspend fun toFile(ctx: Context, candidate: AudioCandidate, req: AudioRequest): File? = out
    }

    @Test fun explicit_failure_returns_null_and_does_not_substitute_auto() = runBlocking {
        val substitute = File.createTempFile("auto", ".wav").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        try {
            val failingExplicit = FakeSource("wikimedia_commons", out = null) // pick can't resolve
            val autoFallback = FakeSource("tts", out = substitute)            // would-be Auto substitute
            val sel = AudioSelection.Explicit("wikimedia_commons", "k", locator = "https://x/y.wav")

            val resolved = AudioSelections.toFile(
                ctx, sel, req,
                enabledInOrder = { listOf(autoFallback) },
                sourceFor = { failingExplicit },
            )

            assertNull("a failed explicit pick must not be replaced with Auto audio", resolved)
        } finally {
            substitute.delete()
        }
    }

    @Test fun explicit_success_returns_its_own_file() = runBlocking {
        val picked = File.createTempFile("pick", ".wav").apply { writeBytes(byteArrayOf(9)) }
        try {
            val src = FakeSource("wikimedia_commons", out = picked)
            val sel = AudioSelection.Explicit(
                "wikimedia_commons", "k", locator = "https://x/y.wav",
                attribution = Attribution("A", "CC BY-SA 4.0", "Wikimedia Commons", null),
            )
            val resolved = AudioSelections.toFile(
                ctx, sel, req, enabledInOrder = { emptyList() }, sourceFor = { src },
            )
            assertEquals(picked, resolved?.file)
        } finally {
            picked.delete()
        }
    }
}
