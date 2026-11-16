package com.playtranslate.audio.sources

import android.content.Context
import com.playtranslate.R
import com.playtranslate.audio.AudioCache
import com.playtranslate.audio.AudioCandidate
import com.playtranslate.audio.AudioRequest
import com.playtranslate.audio.AudioSelection
import com.playtranslate.audio.AudioSource
import com.playtranslate.audio.CandidateLabel
import com.playtranslate.audio.PlayOutcome
import com.playtranslate.audio.RecordingPlayer
import com.playtranslate.audio.localaudio.LocalAudioDb
import com.playtranslate.audio.localaudio.LocalAudioLookup
import com.playtranslate.audio.localaudio.LocalAudioRow
import com.playtranslate.audio.localaudio.LocalAudioStore
import com.playtranslate.language.SourceLangId
import java.io.File

/**
 * Imported recordings: human recordings of words, from the `android.db`
 * file the user loaded ([LocalAudioStore]). On the device, so no wait and
 * no network.
 *
 * It is in the default order ahead of every voice, so a word that has a
 * recording is played from it wherever a word is spoken, and a word that has
 * none falls to the language's voice as before. There is no switch: the file
 * being there is what turns it on, and removing the file turns it off.
 *
 * One file, for every language ([serves]; the user's ruling, 2026-11-15).
 * The file has no language column, so a recording is found by the written
 * word alone, whatever the game's language: with a file of Japanese
 * recordings loaded, a word a Chinese game writes the same way is played
 * from its Japanese recording. That is the known cost of one file, taken
 * over a file per language.
 */
object LocalAudioSource : AudioSource {

    const val ID = "local_audio"
    override val id = ID
    override fun label(ctx: Context): String = ctx.getString(R.string.audio_source_local_name)
    override val toggleable = false
    override val remote = false

    override fun isSetUp(ctx: Context): Boolean = LocalAudioStore.isImported(ctx)
    override fun isEnabled(ctx: Context): Boolean = isSetUp(ctx)
    override fun setEnabled(ctx: Context, on: Boolean) { /* imported or not; no switch */ }

    /** Words, of any language; the file holds no sentences. */
    override fun serves(req: AudioRequest): Boolean = req.kind == AudioRequest.Kind.WORD

    /** Every recording of the word, best first ([LocalAudioLookup.recordings]). */
    override suspend fun candidates(ctx: Context, req: AudioRequest): List<AudioCandidate> {
        // The default walk asks every enabled source, whatever it serves.
        if (!serves(req)) return emptyList()
        val rows = LocalAudioDb.rows(ctx, req.surface.trim())
        return LocalAudioLookup.recordings(rows, req.reading).map(::candidateFor)
    }

    override suspend fun defaultCandidate(ctx: Context, req: AudioRequest): AudioCandidate? =
        candidates(ctx, req).firstOrNull()

    /** A recording as a candidate: named by its source, with the source's
     *  own label for it (an accent form, a speaker) beneath. */
    private fun candidateFor(row: LocalAudioRow) = AudioCandidate(
        sourceId = ID,
        key = LocalAudioLookup.key(row),
        title = CandidateLabel.Text(LocalAudioLookup.sourceName(row.source)),
        subtitle = (row.display?.ifBlank { null } ?: row.speaker?.ifBlank { null })?.let(CandidateLabel::Text),
    )

    /** A picked recording's pill reads its source ("NHK"): which recording
     *  it is, where the source's name would only say that it is one. */
    override suspend fun selectionLabel(
        ctx: Context,
        selection: AudioSelection.Explicit,
        lang: SourceLangId,
    ): String = LocalAudioLookup.parseKey(selection.key)?.first?.let(LocalAudioLookup::sourceName) ?: label(ctx)

    override suspend fun play(
        ctx: Context,
        candidate: AudioCandidate,
        req: AudioRequest,
        awaitCompletion: Boolean,
        onStart: (() -> Unit)?,
    ): PlayOutcome {
        // Recoverable: the walk goes on to the voice.
        val file = toFile(ctx, candidate, req) ?: return PlayOutcome.Failed(recoverable = true)
        return RecordingPlayer.playCached(ctx, file, awaitCompletion, onStart)
    }

    /**
     * The recording as a file: the one in the audio cache, else its bytes
     * read from the imported file and put there. The cache's directory is
     * one AnkiDroid can be handed files from, and the file's name ends in
     * the clip's extension, which is how the card's media gets its type.
     * The key alone finds the recording, so a pick resolves with nothing
     * else kept.
     *
     * The clip is put in the cache from inside the read ([LocalAudioDb.clip]),
     * which is what keeps a removal of the file from coming between the two:
     * a clip is never stored of a file that has gone.
     */
    override suspend fun toFile(ctx: Context, candidate: AudioCandidate, req: AudioRequest): File? {
        val cache = AudioCache(ctx)
        cache.getClip(ID, candidate.key)?.let { return it }
        val (source, file) = LocalAudioLookup.parseKey(candidate.key) ?: return null
        return LocalAudioDb.clip(ctx, source, file) { bytes ->
            cache.putClip(ID, candidate.key, bytes, attribution = null)
        }
    }
}
