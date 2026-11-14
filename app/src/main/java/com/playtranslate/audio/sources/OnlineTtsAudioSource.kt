package com.playtranslate.audio.sources

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.playtranslate.R
import com.playtranslate.audio.AudioCache
import com.playtranslate.audio.AudioCandidate
import com.playtranslate.audio.AudioRequest
import com.playtranslate.audio.AudioSelection
import com.playtranslate.audio.AudioSource
import com.playtranslate.audio.CandidateLabel
import com.playtranslate.audio.PlayOutcome
import com.playtranslate.audio.RecordingPlayer
import com.playtranslate.audio.SpokenText
import com.playtranslate.audio.online.AudioContainer
import com.playtranslate.audio.online.OnlineTtsChoice
import com.playtranslate.audio.online.OnlineTtsException
import com.playtranslate.audio.online.OnlineTtsRequest
import com.playtranslate.audio.online.OnlineTtsService
import com.playtranslate.language.SourceLangId
import com.playtranslate.tts.VoiceChoice
import kotlinx.coroutines.CancellationException
import java.io.File

/**
 * The connected voice service ([OnlineTtsService]) as an audio source: a
 * synthesizer like the device engine, reached over the network with the
 * user's own key.
 *
 * It is in the default order ahead of the device engine, and what it offers
 * there is the service voice chosen for the request's language
 * ([com.playtranslate.Prefs.voiceChoice]), or nothing. Nothing costs no wait
 * and no request, so a language whose voice is a device voice is served
 * exactly as it was before this source existed. That is the whole of how the
 * two are one feature: there is no switch here.
 *
 * A clip is cached under what makes its audio ([OnlineTtsRequest.identity]),
 * so a word or a line is asked of the service once.
 */
object OnlineTtsAudioSource : AudioSource {

    const val ID = "online_tts"
    override val id = ID
    override fun label(ctx: Context): String = ctx.getString(R.string.audio_source_voice_service_name)
    override val toggleable = false
    override val remote = true

    /** What turns a request into the text that is spoken. A seam for JVM
     *  tests: a sentence's text comes from its language's engine, and this
     *  is where a request waits. */
    @Volatile
    @VisibleForTesting
    internal var spokenText: suspend (Context, AudioRequest) -> String = SpokenText::forRequest

    override fun isSetUp(ctx: Context): Boolean = OnlineTtsService.config(ctx) != null
    override fun isEnabled(ctx: Context): Boolean = isSetUp(ctx)
    override fun setEnabled(ctx: Context, on: Boolean) { /* connected or not; no switch */ }

    /** The service's voices, for the picker: one list call. A failure is
     *  thrown, which the picker draws as "Couldn't load". */
    override suspend fun candidates(ctx: Context, req: AudioRequest): List<AudioCandidate> =
        OnlineTtsService.voices(ctx).map(::candidateFor)

    /** The service voice chosen for the request's language, or null when
     *  that language's voice is a device voice. */
    override suspend fun defaultCandidate(ctx: Context, req: AudioRequest): AudioCandidate? {
        val prefs = OnlineTtsService.prefs(ctx)
        val choice = prefs.voiceChoice(req.lang) as? VoiceChoice.Online ?: return null
        return candidateFor(choice.voice)
    }

    /** A voice as a candidate. Its name rides in the locator, which a pick
     *  carries with it, so the pill of a hand-picked voice reads its name
     *  with no list to look it up in ([selectionLabel]). */
    private fun candidateFor(voice: OnlineTtsChoice) = AudioCandidate(
        sourceId = ID,
        key = voice.id,
        title = CandidateLabel.Text(voice.name),
        locator = voice.name,
    )

    override suspend fun selectionLabel(
        ctx: Context,
        selection: AudioSelection.Explicit,
        lang: SourceLangId,
    ): String = selection.locator ?: label(ctx)

    override suspend fun play(
        ctx: Context,
        candidate: AudioCandidate,
        req: AudioRequest,
        awaitCompletion: Boolean,
        onStart: (() -> Unit)?,
    ): PlayOutcome {
        // Recoverable: the walk goes on to the device voice. Never one of
        // the Tts* outcomes, which raise the device engine's own alerts.
        val file = toFile(ctx, candidate, req) ?: return PlayOutcome.Failed(recoverable = true)
        return RecordingPlayer.playCached(ctx, file, awaitCompletion, onStart)
    }

    override suspend fun toFile(ctx: Context, candidate: AudioCandidate, req: AudioRequest): File? =
        try {
            clipFor(ctx, candidate.key, req)
        } catch (e: CancellationException) {
            throw e
        } catch (_: OnlineTtsException) {
            null
        }

    /**
     * The request's language as a service is told it: the two-letter code of
     * the language itself, with no script or region ("zh" for both Chinese
     * scripts). It is the form services with a language field document
     * (ISO 639-1), and a code they do not know is one they ignore.
     */
    internal fun languageCode(lang: SourceLangId): String = lang.code.substringBefore('-')

    /**
     * The same language by its name in English ("Japanese", "Chinese"), for
     * a service that can only be told in a sentence of instructions. In
     * English whatever language the app is shown in: it is read by the
     * service, not by the user.
     */
    internal fun languageName(lang: SourceLangId): String =
        java.util.Locale.forLanguageTag(languageCode(lang)).getDisplayLanguage(java.util.Locale.ENGLISH)

    /**
     * The clip of [req] in the voice [voiceId]: from the cache, or asked of
     * the service and cached. The one path to a clip; [toFile] is this with
     * a failure read as null, and the voice page calls it directly because
     * it shows the failure, with the service's own words.
     *
     * The text is what the device voice is given ([SpokenText]), so the
     * audio reads a line as its card's furigana does. The speed is the
     * app's one speed slider, sent no slower and no faster than the
     * service takes ([OnlineTtsRequest.render]).
     *
     * The clip is stored under the extension of the format its bytes are
     * in: AnkiDroid names a card's media by the file's MIME type, which is
     * derived from that extension. The lookup therefore tries each format,
     * the request not knowing which one the service answers in.
     */
    suspend fun clipFor(ctx: Context, voiceId: String, req: AudioRequest): File {
        val prefs = OnlineTtsService.prefs(ctx)
        // The service and its key, read once and together before anything
        // here suspends: preparing the text can take a while, and a service
        // saved meanwhile must not lend this request its key.
        val connected = OnlineTtsService.connection(prefs) ?: throw OnlineTtsService.notConnected()
        val config = connected.config
        val values = OnlineTtsRequest.Values(
            text = spokenText(ctx, req),
            voice = voiceId,
            model = config.model?.id.orEmpty(),
            instructions = config.instructions,
            speed = prefs.ttsSpeechRate ?: 1f,
            language = languageCode(req.lang),
            languageName = languageName(req.lang),
            key = connected.key,
        )
        val identity = OnlineTtsRequest.identity(config, values) ?: throw OnlineTtsService.notConnected()
        val cache = AudioCache(ctx)
        for (container in AudioContainer.entries) {
            cache.getClip(ID, "$identity.${container.ext}")?.let { return it }
        }
        val request = OnlineTtsRequest.render(config, values) ?: throw OnlineTtsService.notConnected()
        val clip = OnlineTtsService.client.synthesize(request)
        return cache.putClip(ID, "$identity.${clip.container.ext}", clip.bytes, attribution = null)
    }
}
