package com.playtranslate.audio

import android.content.Context
import com.playtranslate.audio.sources.LocalAudioSource
import com.playtranslate.audio.sources.OnlineTtsAudioSource
import com.playtranslate.audio.sources.RecordingAudioSource
import com.playtranslate.audio.sources.TtsAudioSource
import com.playtranslate.audio.sources.WikimediaCommonsAudioSource

/**
 * The set of pluggable audio sources. List order = resolution priority. Adding a
 * source is appending one here; removing it is deleting the entry — no other code
 * changes, since views and the resolver only touch the [AudioSource] interface.
 *
 * Recordings first (local audio, once its file is imported, then Commons,
 * when enabled), then the two synthesizers: the connected voice service,
 * which offers something only for a language whose chosen voice is one of
 * its own, and device TTS, the always-on floor, last.
 */
object AudioSourceRegistry {

    private val sources: List<AudioSource> = listOf(
        // First = top section in the picker (the only all()-order consumer).
        // Auto-resolution priority is unaffected: isEnabled=false keeps the
        // recording out of enabledInOrder entirely, so for Auto the order is
        // local audio → Commons → voice service → TTS.
        RecordingAudioSource,
        // Ahead of Commons: on the device, so it answers with no wait, and
        // it is what the user went and imported.
        LocalAudioSource,
        WikimediaCommonsAudioSource,
        // Above TTS here as on the voice page: the service's voices come
        // first, the device's second.
        OnlineTtsAudioSource,
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
