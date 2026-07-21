package com.playtranslate.audio.sources

import android.content.Context
import com.playtranslate.R
import com.playtranslate.audio.AudioCache
import com.playtranslate.audio.AudioCandidate
import com.playtranslate.audio.AudioRequest
import com.playtranslate.audio.AudioSelection
import com.playtranslate.audio.AudioSource
import com.playtranslate.audio.CandidateLabel
import com.playtranslate.audio.GameAudioClip
import com.playtranslate.audio.Loudness
import com.playtranslate.audio.PlayOutcome
import com.playtranslate.audio.RecordingPlayer
import com.playtranslate.capture.GameAudioSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The recorded-game-audio source: serves the frozen per-card snapshot
 * ([GameAudioSnapshot]) a card flow trims its sentence audio from. Snapshots
 * are IMMUTABLE per card — a selection's locator pins the exact file, so
 * snapshot churn between cards cannot invalidate or corrupt a pick.
 *
 * Selection keys carry the whole state, so a pick survives any process churn:
 *  - provisional (no range committed yet): [KEY_PROVISIONAL] — never sendable;
 *    [toFile] returns null and the fragment's Save gate forces the trim editor.
 *  - committed: `snapshot@<mtime>-<startMs>-<endMs>.m4a` — the snapshot's
 *    lastModified gives the encoded clip a unique cache identity per snapshot
 *    file, and [parseRangeFor] enforces it at resolve time as a fail-closed
 *    backstop for the residual ways a file can change under a key (OS cache
 *    purge, orphan sweep). The `.m4a` suffix makes [AudioCache.clipFile]
 *    derive the right extension for the file AnkiDroid ends up storing.
 *
 * `isEnabled = false` is deliberate and load-bearing: it keeps this source out
 * of [com.playtranslate.audio.AudioSourceRegistry.enabledInOrder], so the Auto
 * resolver can never "helpfully" attach game audio — only an explicit pick
 * (picker iterates `all()`; explicit selections resolve via `sourceFor`) ever
 * reaches it. Sentence cells only: a word-level clip is not extractable from a
 * game mix, so word pickers never see this source.
 */
object RecordingAudioSource : AudioSource {

    const val ID = "game_audio"

    /** Selection key while no trim range has been committed. */
    const val KEY_PROVISIONAL = "snapshot"

    /** Preview length for a provisional (untrimmed) chip tap — the freshest
     *  tail of the snapshot, not all 3 minutes of it. */
    private const val PROVISIONAL_PREVIEW_MS = 8_000L

    private val COMMITTED_KEY = Regex("""^snapshot@(\d+)-(\d+)-(\d+)\.m4a$""")

    override val id = ID
    override fun label(ctx: Context): String = ctx.getString(R.string.audio_source_game_name)
    override val toggleable = false
    override val remote = false

    /** False, always — load-bearing (keeps this source out of Auto). The
     *  feature's real switch is Prefs.recordGameAudio, which gates capture,
     *  not selection. */
    override fun isEnabled(ctx: Context) = false
    override fun setEnabled(ctx: Context, on: Boolean) {}

    override fun serves(kind: AudioRequest.Kind): Boolean = kind == AudioRequest.Kind.SENTENCE

    override suspend fun candidates(ctx: Context, req: AudioRequest): List<AudioCandidate> {
        val wav = GameAudioSnapshot.active?.takeIf { GameAudioSnapshot.isUsable(it) }
            ?: return emptyList()
        return listOf(snapshotCandidate(wav))
    }

    override suspend fun defaultCandidate(ctx: Context, req: AudioRequest): AudioCandidate? =
        candidates(ctx, req).firstOrNull()

    override suspend fun play(
        ctx: Context,
        candidate: AudioCandidate,
        req: AudioRequest,
        awaitCompletion: Boolean,
        onStart: (() -> Unit)?,
    ): PlayOutcome {
        val wav = snapshotWav(ctx, candidate)
            // Snapshot gone: recoverable=false — silently auditioning TTS in
            // place of "Game audio" would misrepresent what the card will get.
            ?: return PlayOutcome.Failed(recoverable = false)
        if (candidate.key != KEY_PROVISIONAL && parseRangeFor(candidate.key, wav) == null) {
            // Committed against an overwritten snapshot: refuse rather than
            // audition the wrong audio at the old range.
            return PlayOutcome.Failed(recoverable = false)
        }
        val preview = withContext(Dispatchers.IO) {
            val durationMs = GameAudioClip.durationMs(wav)
            val (startMs, endMs) = parseRangeFor(candidate.key, wav)
                ?: ((durationMs - PROVISIONAL_PREVIEW_MS).coerceAtLeast(0) to durationMs)
            val pcm = GameAudioClip.readPcmRange(wav, startMs, endMs)
            if (pcm.isEmpty()) return@withContext null
            // Single self-clobbering preview file — no temp accumulation.
            File(wav.parentFile, "preview.wav").also {
                GameAudioClip.writeWav(pcm, GameAudioClip.sampleRate(wav), it)
            }
        } ?: return PlayOutcome.Failed(recoverable = false)
        // RecordingPlayer keeps playback on the shared stop channel
        // (PronunciationPlayer.stop / chip tap-again reach it).
        return RecordingPlayer.play(ctx, preview, awaitCompletion, onStart)
    }

    override suspend fun toFile(ctx: Context, candidate: AudioCandidate, req: AudioRequest): File? {
        val wav = snapshotWav(ctx, candidate) ?: return null
        // Provisional never sends; neither does a range committed against an
        // OVERWRITTEN snapshot (mtime mismatch) — fail closed into the send
        // path's audio-missing handling instead of cutting the wrong audio.
        val (startMs, endMs) = parseRangeFor(candidate.key, wav) ?: return null
        return withContext(Dispatchers.IO) {
            val pcm = GameAudioClip.readPcmRange(wav, startMs, endMs)
            if (pcm.isEmpty()) return@withContext null
            // Same boost-only normalization the previews apply — the card
            // must sound like what the chip played, and game mixes sit well
            // below the speech level TTS cards land at.
            Loudness.normalize(pcm)
            val bytes = GameAudioClip.encodeM4a(pcm, GameAudioClip.sampleRate(wav), ctx.cacheDir)
            // Through the audio cache: LRU-swept storage the send pipeline's
            // ephemeral-cleanup won't delete (ephemeral=false for non-TTS).
            AudioCache(ctx).putClip(ID, candidate.key, bytes, attribution = null)
        }
    }

    // ── Selection helpers (used by the sentence fragment) ──

    /** Selection for a committed trim range over the card's snapshot [wav].
     *  The locator pins the exact (immutable) file; the mtime in the key
     *  gives the encoded clip a unique cache identity per snapshot. */
    fun committedSelection(wav: File, startMs: Long, endMs: Long): AudioSelection.Explicit =
        AudioSelection.Explicit(
            sourceId = ID,
            key = "snapshot@${wav.lastModified()}-$startMs-$endMs.m4a",
            locator = wav.absolutePath,
        )

    /** True when [selection] is game audio with no committed range — the state
     *  the Save gate intercepts. */
    fun isProvisional(selection: AudioSelection): Boolean =
        selection is AudioSelection.Explicit &&
            selection.sourceId == ID &&
            parseRange(selection.key) == null

    /** Committed (startMs, endMs) — a half-open window — or null for the
     *  provisional key. Display-oriented: does NOT check snapshot identity;
     *  anything that CUTS or PLAYS audio must use [parseRangeFor]. */
    fun parseRange(key: String): Pair<Long, Long>? {
        val m = COMMITTED_KEY.matchEntire(key) ?: return null
        val start = m.groupValues[2].toLong()
        val end = m.groupValues[3].toLong()
        return if (end > start) start to end else null
    }

    /** [parseRange] plus snapshot-identity enforcement: null unless [key]
     *  was committed against the CURRENT content of [wav] (the mtime baked
     *  into the key matches the file). A range from an overwritten snapshot
     *  must fail closed — cutting the new audio at the old range would
     *  silently attach the wrong clip (adversarial-review finding). */
    fun parseRangeFor(key: String, wav: File): Pair<Long, Long>? {
        val m = COMMITTED_KEY.matchEntire(key) ?: return null
        if (m.groupValues[1].toLong() != wav.lastModified()) return null
        val start = m.groupValues[2].toLong()
        val end = m.groupValues[3].toLong()
        return if (end > start) start to end else null
    }

    private fun snapshotCandidate(wav: File): AudioCandidate =
        AudioCandidate(
            sourceId = ID,
            key = KEY_PROVISIONAL,
            title = CandidateLabel.Res(R.string.audio_source_game_name),
            subtitle = CandidateLabel.Res(R.string.audio_source_game_ready),
            locator = wav.absolutePath,
        )

    /** The snapshot WAV an explicit pick refers to (locator-first — the
     *  per-card immutable file — falling back to the active card's), or
     *  null when it's gone/empty. */
    private fun snapshotWav(ctx: Context, candidate: AudioCandidate): File? {
        val f = candidate.locator?.let(::File) ?: GameAudioSnapshot.active
        return f?.takeIf { it.exists() && it.length() > 44 }
    }
}
