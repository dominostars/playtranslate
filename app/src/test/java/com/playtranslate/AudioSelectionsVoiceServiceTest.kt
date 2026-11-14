package com.playtranslate

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.audio.AudioCandidate
import com.playtranslate.audio.AudioRequest
import com.playtranslate.audio.AudioSelection
import com.playtranslate.audio.AudioSelections
import com.playtranslate.audio.AudioSource
import com.playtranslate.audio.CandidateLabel
import com.playtranslate.audio.PlayOutcome
import com.playtranslate.audio.PronunciationPlayer
import com.playtranslate.language.SourceLangId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * What the voice service changes in the shared resolution: a card's audio
 * marked as a fallback when the service offered the language's voice and gave
 * no clip; a failed explicit preview that does not ask its source twice; a
 * cancellation that ends the walk; and a pill that asks its pick's own source
 * for its label.
 */
@RunWith(RobolectricTestRunner::class)
class AudioSelectionsVoiceServiceTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val req = AudioRequest.word("行く", "いく", SourceLangId.JA)

    private class Fake(
        override val id: String,
        private val offers: Boolean = true,
        private val file: File? = null,
        private val delayMs: Long = 0L,
        private val outcome: PlayOutcome = PlayOutcome.Played,
        private val throwOnOffer: Throwable? = null,
    ) : AudioSource {
        var plays = 0
        var files = 0
        override fun label(ctx: Context) = id
        override val toggleable = false
        override val remote = false
        override fun isEnabled(ctx: Context) = true
        override fun setEnabled(ctx: Context, on: Boolean) {}
        override suspend fun candidates(ctx: Context, req: AudioRequest) = emptyList<AudioCandidate>()
        override suspend fun defaultCandidate(ctx: Context, req: AudioRequest): AudioCandidate? {
            throwOnOffer?.let { throw it }
            return if (offers) AudioCandidate(id, "auto", CandidateLabel.Text("auto")) else null
        }
        override suspend fun play(
            ctx: Context, candidate: AudioCandidate, req: AudioRequest,
            awaitCompletion: Boolean, onStart: (() -> Unit)?,
        ): PlayOutcome {
            plays++
            return outcome
        }
        override suspend fun toFile(ctx: Context, candidate: AudioCandidate, req: AudioRequest): File? {
            files++
            if (delayMs > 0) delay(delayMs)
            return file
        }
    }

    private fun temp() = File.createTempFile("clip", ".wav").apply { writeBytes(byteArrayOf(1, 2, 3)); deleteOnExit() }

    private suspend fun auto(vararg sources: AudioSource, budgetMs: Long = 20_000L) =
        AudioSelections.toFile(
            ctx, AudioSelection.Auto, req,
            enabledInOrder = { sources.toList() }, sourceFor = { sources.first() }, sourceBudgetMs = budgetMs,
        )

    // ── A card's audio marked as a fallback ──────────────────────────────

    @Test fun `the service's own clip is not a fallback`() = runBlocking {
        val clip = temp()
        val resolved = auto(Fake("online_tts", file = clip), Fake("tts", file = temp()))!!
        assertEquals(clip, resolved.file)
        assertFalse(resolved.fellBack)
        assertFalse("a cached clip is not thrown away after the send", resolved.ephemeral)
    }

    @Test fun `the device voice after a service that offered and gave nothing is a fallback`() = runBlocking {
        val device = temp()
        val resolved = auto(Fake("online_tts", file = null), Fake("tts", file = device))!!
        assertEquals(device, resolved.file)
        assertTrue(resolved.fellBack)
        assertTrue(resolved.ephemeral)
    }

    @Test fun `the device voice after a service whose budget ran out is a fallback`() = runBlocking {
        val resolved = auto(
            Fake("online_tts", file = temp(), delayMs = 5_000), Fake("tts", file = temp()), budgetMs = 50,
        )!!
        assertTrue(resolved.fellBack)
    }

    @Test fun `the device voice of a device-voice language is not a fallback`() = runBlocking {
        val service = Fake("online_tts", offers = false)
        val resolved = auto(service, Fake("tts", file = temp()))!!
        assertFalse(resolved.fellBack)
        assertEquals("a source that offers nothing is asked for no file", 0, service.files)
    }

    @Test fun `a recording source that gave nothing does not make the next clip a fallback`() = runBlocking {
        val resolved = auto(Fake("wikimedia_commons", file = null), Fake("tts", file = temp()))!!
        assertFalse(resolved.fellBack)
        val commonsThenService = auto(
            Fake("wikimedia_commons", file = null), Fake("online_tts", file = temp()), Fake("tts", file = temp()),
        )!!
        assertFalse(commonsThenService.fellBack)
    }

    @Test fun `with the service and the device both silent there is no audio`() = runBlocking {
        assertNull(auto(Fake("online_tts", file = null), Fake("tts", file = null)))
    }

    @Test fun `an explicit pick of the service that fails is not replaced`() = runBlocking {
        val device = Fake("tts", file = temp())
        val service = Fake("online_tts", file = null)
        val resolved = AudioSelections.toFile(
            ctx, AudioSelection.Explicit("online_tts", "coral", locator = "Coral"), req,
            enabledInOrder = { listOf(service, device) }, sourceFor = { service },
        )
        assertNull(resolved)
        assertEquals(0, device.files)
    }

    // ── A failed explicit preview ────────────────────────────────────────

    @Test fun `a failed explicit preview falls back without asking its source again`() = runBlocking {
        val service = Fake("online_tts", outcome = PlayOutcome.Failed(recoverable = true))
        val device = Fake("tts")
        val outcome = AudioSelections.play(
            ctx, AudioSelection.Explicit("online_tts", "coral"), req, awaitCompletion = true, onStart = null,
            enabledInOrder = { listOf(service, device) }, sourceFor = { service },
        )
        assertEquals(PlayOutcome.Played, outcome)
        assertEquals("asked once, not again by the fallback walk", 1, service.plays)
        assertEquals(1, device.plays)
    }

    @Test fun `an explicit preview that plays asks nothing else`() = runBlocking {
        val service = Fake("online_tts")
        val device = Fake("tts")
        AudioSelections.play(
            ctx, AudioSelection.Explicit("online_tts", "coral"), req, awaitCompletion = true, onStart = null,
            enabledInOrder = { listOf(service, device) }, sourceFor = { service },
        )
        assertEquals(1, service.plays)
        assertEquals(0, device.plays)
    }

    // ── A cancellation is not a failure ──────────────────────────────────

    @Test fun `a cancelled source ends the walk instead of handing on to the next`() = runBlocking {
        val cancelled = Fake("online_tts", throwOnOffer = CancellationException("tap cancelled"))
        val device = Fake("tts")
        var thrown = false
        try {
            PronunciationPlayer.play(ctx, req, sources = listOf(cancelled, device))
        } catch (e: CancellationException) {
            thrown = true
        }
        assertTrue(thrown)
        assertEquals("the device voice did not speak for a cancelled tap", 0, device.plays)
    }

    @Test fun `a source that fails still hands on to the next`() = runBlocking {
        val broken = Fake("online_tts", throwOnOffer = IllegalStateException("boom"))
        val device = Fake("tts")
        assertEquals(PlayOutcome.Played, PronunciationPlayer.play(ctx, req, sources = listOf(broken, device)))
        assertEquals(1, device.plays)
    }

    // ── The pill ─────────────────────────────────────────────────────────

    @Test fun `a pick's pill is what its own source calls it`() = runBlocking {
        fun pick(source: String, key: String, locator: String? = null) =
            AudioSelection.Explicit(source, key, locator)
        val lang = SourceLangId.JA
        assertEquals(ctx.getString(R.string.tts_voice_default), AudioSelections.label(ctx, AudioSelection.Auto, lang))
        assertEquals(
            ctx.getString(R.string.audio_source_commons_name),
            AudioSelections.label(ctx, pick("wikimedia_commons", "Ja-iku.ogg", "https://x/Ja-iku.ogg"), lang),
        )
        assertEquals(
            ctx.getString(R.string.audio_source_game_name),
            AudioSelections.label(ctx, pick("game_audio", "snapshot"), lang),
        )
        assertEquals(
            ctx.getString(R.string.tts_voice_default),
            AudioSelections.label(ctx, pick("tts", "__default__"), lang),
        )
        assertEquals("Rachel", AudioSelections.label(ctx, pick("online_tts", "21m00Tcm4TlvDq8ikWAM", "Rachel"), lang))
        assertEquals(
            ctx.getString(R.string.audio_source_voice_service_name),
            AudioSelections.label(ctx, pick("online_tts", "21m00Tcm4TlvDq8ikWAM"), lang),
        )
    }
}
