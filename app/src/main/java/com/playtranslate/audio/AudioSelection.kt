package com.playtranslate.audio

import android.content.Context
import com.playtranslate.R
import com.playtranslate.audio.sources.TtsAudioSource
import com.playtranslate.audio.sources.WikimediaCommonsAudioSource
import com.playtranslate.tts.TtsEngine
import com.playtranslate.tts.TtsVoiceLabels
import java.io.File

/**
 * A per-cell audio choice in the Anki flow.
 *  - [Auto] (default): the registry's Commons-first → TTS resolution, decided
 *    lazily at play/send time — so cards honor "prefer human audio" without
 *    eager per-word queries when the sheet opens.
 *  - [Explicit]: the user pinned a specific source + candidate via the picker.
 *
 * A pure value (sourceId + key) — safe to hold as per-cell state and pass to the
 * send pipeline; the [AudioSourceRegistry] turns it back into playback/file.
 */
sealed interface AudioSelection {
    data object Auto : AudioSelection
    data class Explicit(
        val sourceId: String,
        val key: String,
        /** Resolution hint carried so an explicit pick resolves deterministically
         *  even on a cold cache — the exact recording URL (Commons), null (TTS). */
        val locator: String? = null,
        /** CC credit for the picked recording, carried so it reaches the card
         *  without depending on cache state. */
        val attribution: Attribution? = null,
    ) : AudioSelection
}

/** A resolved clip plus the credit that must travel with it (CC recordings).
 *  [ephemeral] true = a throwaway TTS synth file the caller should delete;
 *  false = a cached Commons clip that must stay in the audio cache. */
data class ResolvedAudio(val file: File, val attribution: Attribution?, val ephemeral: Boolean = true)

/** Bridges [AudioSelection] to the source registry for the Anki preview chip
 *  and send pipeline. */
object AudioSelections {

    /** The candidate handed to a source for an explicit pick — carries the
     *  [AudioSelection.Explicit.locator] (URL) and attribution so Commons can
     *  fetch and credit the EXACT selected clip without relying on cache state.
     *  (The original bug: a locator-less reconstruction could never download an
     *  uncached pick, so the send path silently fell back to a different clip.) */
    internal fun explicitCandidate(sel: AudioSelection.Explicit): AudioCandidate =
        AudioCandidate(
            sourceId = sel.sourceId,
            key = sel.key,
            title = CandidateLabel.Text(sel.key),
            attribution = sel.attribution,
            locator = sel.locator,
        )

    /** Render a selection to an attachable file (+ attribution) for **send/export**.
     *
     *  [Auto] walks enabled sources Commons-first → TTS floor (fallback is correct:
     *  Auto promises no specific clip). [Explicit] uses the chosen source **only**:
     *  if it can't produce the file (evicted + offline, dead URL) this returns
     *  **null** rather than substituting a different clip — the send pipeline then
     *  reports the audio as missing instead of silently shipping the wrong
     *  recording on the card. (Preview's [play] still falls back, since auditioning
     *  *something* beats silence.)
     *
     *  [enabledInOrder]/[sourceFor] default to the real [AudioSourceRegistry];
     *  they're injectable so tests can drive the resolution deterministically. */
    suspend fun toFile(
        ctx: Context,
        selection: AudioSelection,
        req: AudioRequest,
        enabledInOrder: (Context) -> List<AudioSource> = AudioSourceRegistry::enabledInOrder,
        sourceFor: (String) -> AudioSource = AudioSourceRegistry::sourceFor,
    ): ResolvedAudio? =
        when (selection) {
            AudioSelection.Auto -> {
                var out: ResolvedAudio? = null
                for (source in enabledInOrder(ctx)) {
                    val c = runCatching { source.defaultCandidate(ctx, req) }.getOrNull() ?: continue
                    val f = runCatching { source.toFile(ctx, c, req) }.getOrNull() ?: continue
                    out = ResolvedAudio(f, c.attribution, ephemeral = source.id == TtsAudioSource.ID); break
                }
                out
            }
            is AudioSelection.Explicit -> {
                val source = sourceFor(selection.sourceId)
                val candidate = explicitCandidate(selection)
                val f = runCatching { source.toFile(ctx, candidate, req) }.getOrNull()
                if (f != null) {
                    val attr = selection.attribution ?: attributionFor(ctx, selection)
                    ResolvedAudio(f, attr, ephemeral = selection.sourceId == TtsAudioSource.ID)
                } else {
                    null // honor the explicit pick or report it missing — never substitute
                }
            }
        }

    /** Play a selection live (preview chip). Explicit falls back to the Auto
     *  resolver on a recoverable failure. */
    suspend fun play(
        ctx: Context,
        selection: AudioSelection,
        req: AudioRequest,
        awaitCompletion: Boolean,
        onStart: (() -> Unit)?,
    ): PlayOutcome = when (selection) {
        AudioSelection.Auto -> PronunciationPlayer.play(ctx, req, awaitCompletion, onStart)
        is AudioSelection.Explicit -> {
            val source = AudioSourceRegistry.sourceFor(selection.sourceId)
            val candidate = explicitCandidate(selection)
            PronunciationPlayer.stop()
            val outcome = runCatching { source.play(ctx, candidate, req, awaitCompletion, onStart) }
                .getOrElse { PlayOutcome.Failed(recoverable = true) }
            if (outcome is PlayOutcome.Failed && outcome.recoverable)
                PronunciationPlayer.play(ctx, req, awaitCompletion, onStart)
            else outcome
        }
    }

    /** Pill label for the selection. */
    suspend fun label(ctx: Context, selection: AudioSelection, lang: com.playtranslate.language.SourceLangId): String =
        when (selection) {
            AudioSelection.Auto -> ctx.getString(R.string.tts_voice_default)
            is AudioSelection.Explicit -> when (selection.sourceId) {
                WikimediaCommonsAudioSource.ID -> ctx.getString(R.string.audio_source_commons_name)
                else ->
                    if (selection.key == TtsAudioSource.DEFAULT_KEY) ctx.getString(R.string.tts_voice_default)
                    else TtsVoiceLabels.titleFor(ctx, TtsEngine.voicesFor(ctx, lang), selection.key)
            }
        }

    private suspend fun attributionFor(ctx: Context, sel: AudioSelection.Explicit): Attribution? =
        if (sel.sourceId == WikimediaCommonsAudioSource.ID)
            AudioCache(ctx).readAttribution(sel.sourceId, sel.key)
        else null
}
