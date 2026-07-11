package com.playtranslate.audio

import android.content.Context
import com.playtranslate.audio.sources.RecordingAudioSource
import com.playtranslate.audio.sources.TtsAudioSource
import com.playtranslate.audio.sources.WikimediaCommonsAudioSource

/**
 * The set of pluggable audio sources. List order = resolution priority. Adding a
 * source is appending one here; removing it is deleting the entry — no other code
 * changes, since views and the resolver only touch the [AudioSource] interface.
 *
 * Commons is first (preferred when available); TTS is the always-on floor, last.
 */
object AudioSourceRegistry {

    private val sources: List<AudioSource> = listOf(
        // First = top section in the picker (the only all()-order consumer).
        // Auto-resolution priority is unaffected: isEnabled=false keeps the
        // recording out of enabledInOrder entirely, so for Auto the order is
        // still Commons → TTS.
        RecordingAudioSource,
        WikimediaCommonsAudioSource,
        TtsAudioSource,
    )

    fun all(): List<AudioSource> = sources

    /** Sources that expose an on/off switch in settings. */
    fun toggleable(): List<AudioSource> = sources.filter { it.toggleable }

    /** Enabled sources in priority order (for the default-playback resolver). */
    fun enabledInOrder(ctx: Context): List<AudioSource> = sources.filter { it.isEnabled(ctx) }

    /** The source with [id], falling back to the TTS floor if unknown (e.g. a
     *  persisted selection whose source was removed). */
    fun sourceFor(id: String): AudioSource = sources.firstOrNull { it.id == id } ?: TtsAudioSource
}
