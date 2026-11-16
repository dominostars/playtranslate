package com.playtranslate.audio

import android.content.Context
import java.io.File

/**
 * A pluggable pronunciation-audio backend (system TTS, Wikimedia Commons, …).
 *
 * Behavior lives here, not on [AudioCandidate]: the source is handed a candidate
 * it produced and acts on it. This keeps candidates as pure values and avoids
 * over-fitting one object shape to dissimilar backends (local synthesis vs. a
 * remote archive).
 *
 * Views interact only with this interface and with candidate values — there is
 * no source-specific branching in the UI. New sources are added by registering
 * an implementation in [AudioSourceRegistry]; removing one is deleting it there.
 */
interface AudioSource {
    /** Stable id, persisted in selections (e.g. "tts", "wikimedia_commons"). */
    val id: String

    /** Localized name for the settings switch and picker section header. */
    fun label(ctx: Context): String

    /** Whether this source shows an on/off switch in settings. TTS = false (always-on floor). */
    val toggleable: Boolean

    /** Whether resolving this source touches the network. The live-playback
     *  resolver time-bounds remote sources and falls back to the local floor
     *  promptly instead of hanging on a slow/offline network. */
    val remote: Boolean

    fun isEnabled(ctx: Context): Boolean

    /** Single mutator for the enabled flag (delegates to Prefs). No-op for non-toggleable sources. */
    fun setEnabled(ctx: Context, on: Boolean)

    /**
     * Whether this source can serve [req] at all, by what the request is:
     * its kind, its language. A source that categorically can't (Commons
     * and an imported file of recordings have nothing for a sentence) is
     * omitted from the picker for that request — so it never appears as a
     * permanently-empty "No results" section, and the picker doesn't query
     * it. Default: every request.
     */
    fun serves(req: AudioRequest): Boolean = true

    /**
     * Whether this source has what it needs to offer anything. A source the
     * user has to set up first (the voice service, until a service is
     * connected) is left out of the picker until then, so nobody who never
     * set it up sees a section that can only be empty. Default: nothing to
     * set up.
     */
    fun isSetUp(ctx: Context): Boolean = true

    /**
     * What a cell's pill reads for an explicit pick of this source. Default:
     * the source's name, which is all a source with one kind of clip per
     * request has to say. A source whose picks differ by voice names the
     * voice. On the source, like every other behavior, so the pill needs no
     * list of sources and a new source cannot be read as another one.
     */
    suspend fun selectionLabel(
        ctx: Context,
        selection: AudioSelection.Explicit,
        lang: com.playtranslate.language.SourceLangId,
    ): String = label(ctx)

    /** All options for the picker (metadata only — no audio is downloaded here). */
    suspend fun candidates(ctx: Context, req: AudioRequest): List<AudioCandidate>

    /**
     * The single best option for default playback — cheap, without enumerating
     * everything. TTS returns the user's pref voice directly; Commons returns its
     * top match. Returns null when the source has nothing for this request.
     */
    suspend fun defaultCandidate(ctx: Context, req: AudioRequest): AudioCandidate?

    /**
     * Play [candidate] via this source's backend. With [awaitCompletion] = false,
     * returns once playback has started (or failed to start) — enough for the
     * resolver to decide whether to fall through. [onStart] fires when audio
     * actually begins (drives LOADING→PLAYING UI).
     */
    suspend fun play(
        ctx: Context,
        candidate: AudioCandidate,
        req: AudioRequest,
        awaitCompletion: Boolean = false,
        onStart: (() -> Unit)? = null,
    ): PlayOutcome

    /** Render [candidate] to a local file (download for recordings; synth for TTS). Null on failure. */
    suspend fun toFile(ctx: Context, candidate: AudioCandidate, req: AudioRequest): File?
}
