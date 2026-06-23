package com.playtranslate.audio.sources

import android.content.Context
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.audio.AudioCache
import com.playtranslate.audio.AudioCandidate
import com.playtranslate.audio.AudioRequest
import com.playtranslate.audio.AudioSource
import com.playtranslate.audio.PlayOutcome
import com.playtranslate.audio.RecordingPlayer
import java.io.File

/**
 * Human native-speaker recordings from Wikimedia Commons (keyless, fetched at
 * runtime). Word-level only — returns nothing for a sentence, so the resolver
 * falls through to TTS. Toggleable; default on via [Prefs.commonsAudioEnabled].
 */
object WikimediaCommonsAudioSource : AudioSource {

    const val ID = "wikimedia_commons"
    override val id = ID
    override fun label(ctx: Context): String = ctx.getString(R.string.audio_source_commons_name)
    override val toggleable = true
    override val remote = true
    override fun isEnabled(ctx: Context) = Prefs(ctx).commonsAudioEnabled
    override fun setEnabled(ctx: Context, on: Boolean) { Prefs(ctx).commonsAudioEnabled = on }

    private val client = CommonsClient()

    override suspend fun candidates(ctx: Context, req: AudioRequest): List<AudioCandidate> {
        if (req.kind != AudioRequest.Kind.WORD) return emptyList() // Commons is word-level
        val cache = AudioCache(ctx)
        if (cache.isNegativeFresh(ID, req.lang, req.surface)) {
            android.util.Log.i("PtAudio", "Commons negative-cache hit word='${req.surface}' (skipping query)")
            return emptyList()
        }
        // null = the query FAILED (network/parse) → do NOT cache it; a transient
        // failure must not blacklist the word. empty = a genuine no-match → cache it.
        val result = client.candidates(req) ?: return emptyList()
        if (result.isEmpty()) cache.markNegative(ID, req.lang, req.surface)
        return result
    }

    /** Top-ranked recording for the word (a real query — Commons must be asked). */
    override suspend fun defaultCandidate(ctx: Context, req: AudioRequest): AudioCandidate? =
        candidates(ctx, req).firstOrNull()

    override suspend fun play(
        ctx: Context,
        candidate: AudioCandidate,
        req: AudioRequest,
        awaitCompletion: Boolean,
        onStart: (() -> Unit)?,
    ): PlayOutcome {
        val file = toFile(ctx, candidate, req) ?: return PlayOutcome.Failed(recoverable = true)
        return RecordingPlayer.play(ctx, file, awaitCompletion, onStart)
    }

    /** Cached clip if present, else download + cache (with attribution sidecar). */
    override suspend fun toFile(ctx: Context, candidate: AudioCandidate, req: AudioRequest): File? {
        val cache = AudioCache(ctx)
        cache.getClip(ID, candidate.key)?.let {
            android.util.Log.i("PtAudio", "Commons cache hit key=${candidate.key} size=${it.length()}")
            return it
        }
        val url = candidate.locator ?: run {
            android.util.Log.w("PtAudio", "Commons toFile: null locator key=${candidate.key}")
            return null
        }
        val bytes = client.download(url) ?: run {
            android.util.Log.w("PtAudio", "Commons toFile: download null key=${candidate.key}")
            return null
        }
        return cache.putClip(ID, candidate.key, bytes, candidate.attribution).also {
            android.util.Log.i("PtAudio", "Commons cached key=${candidate.key} size=${it.length()} path=${it.absolutePath}")
        }
    }
}
