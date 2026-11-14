package com.playtranslate.audio.online

import kotlinx.serialization.Serializable

/**
 * One value picked from a service's own list: a voice or a model. [id] is
 * what the request sends; [name] is what the service calls it, shown to the
 * user (a service's voice id can be an opaque string). A list that gives no
 * name shows the id.
 */
@Serializable
data class OnlineTtsChoice(val id: String, val name: String = id)

/**
 * How one of a service's lists (its voices, its models) is fetched and read.
 * Data, like the request itself, so a service's list needs no code of its
 * own: one GET and the names of the JSON fields that hold each item.
 *
 * @property ref a URL reference resolved against the service's request URL,
 *   so a self-hosted server's list follows the URL the user typed for it
 *   (`voices` beside `.../audio/speech`, `/v2/voices` at the host's root).
 * @property arrayField the field of the reply that holds the array, or null
 *   when the reply is the array.
 * @property idField the field of an item that holds its id. An item that is
 *   a plain string is its own id, whatever this says.
 * @property nameField the field of an item that holds its display name, or
 *   null when the list has none.
 * @property requireTrue a field an item must hold as `true` to be listed
 *   (a service's model list also names models that cannot speak), or null.
 */
data class OnlineTtsListSpec(
    val ref: String,
    val arrayField: String?,
    val idField: String,
    val nameField: String? = null,
    val requireTrue: String? = null,
)

/**
 * What a preset fills in: a request URL, the request text (header lines, a
 * blank line, the JSON body; see [OnlineTtsRequest]) and the two list calls.
 * Kotlin constants, not string resources, as the prompt defaults of
 * [com.playtranslate.translation.llm.LlmPromptTemplates] are not: a request
 * is not text to localize.
 *
 * @property builtInVoices the voices the service has built in, for a service
 *   with no call that lists them: what the app offers in that list's place.
 * @property builtInVoicesByModel where a model speaks fewer of them: a model
 *   id's prefix and the voices that model takes. The first prefix the chosen
 *   model starts with wins; a model none of them names takes [builtInVoices].
 *   A voice its model does not take is refused by the service (an HTTP 400),
 *   so it is not offered.
 * @property speedRange the speeds the service takes, where that is less than
 *   the app's slider gives (half speed to double); null when it takes them
 *   all. See [speedSent].
 */
class OnlineTtsPresetSpec(
    val url: String,
    val request: String,
    val voices: OnlineTtsListSpec,
    val models: OnlineTtsListSpec,
    val builtInVoices: List<String> = emptyList(),
    val builtInVoicesByModel: List<Pair<String, List<String>>> = emptyList(),
    val preferredModels: List<String> = emptyList(),
    val speedRange: ClosedFloatingPointRange<Float>? = null,
) {
    /**
     * What `{speed}` is filled with for the slider's [speed]: the speed
     * itself, or the end of [speedRange] nearest to it. A speed outside a
     * service's range is not asked for: what the service answers to one is
     * not documented, and a refusal would cost a card its voice. So past
     * the range's ends the slider changes nothing for that service.
     */
    fun speedSent(speed: Float): Float = speedRange?.let { speed.coerceIn(it) } ?: speed

    /**
     * The model to use when the user chose none, out of the models the
     * service [listed]: the first of [preferredModels] it lists, or the one
     * model it lists when there is only one. Null when neither holds, and
     * then nothing is guessed: a service's model list can hold models that
     * do not speak at all (OpenAI's lists every model of the account), and
     * a wrong pick would be found out only later, as a voice that fails.
     */
    fun pickModel(listed: List<OnlineTtsChoice>): OnlineTtsChoice? =
        preferredModels.firstNotNullOfOrNull { id -> listed.firstOrNull { it.id == id } }
            ?: listed.singleOrNull()

    /** The built-in voices [modelId] takes. */
    fun builtInVoicesFor(modelId: String?): List<String> =
        builtInVoicesByModel.firstOrNull { (prefix, _) -> modelId?.startsWith(prefix) == true }?.second
            ?: builtInVoices
}

/**
 * The request shapes the app can prefill. A preset is a starting point, not
 * a service: its URL and its request text are editable, so OPENAI also
 * serves a self-hosted server that speaks the same request (Kokoro-FastAPI),
 * and ELEVENLABS any service shaped like it.
 *
 * Serialized by name. Keep OPENAI first: it is the default of a new config.
 */
@Serializable
enum class OnlineTtsPreset {
    OPENAI,
    ELEVENLABS;

    val spec: OnlineTtsPresetSpec
        get() = when (this) {
            OPENAI -> OPENAI_SPEC
            ELEVENLABS -> ELEVENLABS_SPEC
        }

    private companion object {
        /**
         * OpenAI's `POST /v1/audio/speech`. OpenAI has no call that lists
         * its voices, so its built-in names are data here; a server that
         * lists its own (Kokoro-FastAPI's `GET /v1/audio/voices`) answers
         * the fetch and these are not used.
         *
         * Which names depends on the model (OpenAI's text-to-speech guide,
         * read 2026-11-13): thirteen on the endpoint, of which `tts-1` and
         * `tts-1-hd` take nine. The guide does not say what a dated
         * `gpt-4o-mini-tts` snapshot takes, so those are offered all
         * thirteen.
         *
         * The request has no field for the text's language, so the language
         * is named where the service does listen, at the head of the
         * instructions, ahead of whatever the user typed there. Only
         * `gpt-4o-mini-tts` reads instructions; `tts-1` and `tts-1-hd` can
         * be told the language by no means and guess it from the text.
         */
        val OPENAI_SPEC = OnlineTtsPresetSpec(
            url = "https://api.openai.com/v1/audio/speech",
            request = """
                Authorization: Bearer {key}
                Content-Type: application/json

                {"model":"{model}","voice":"{voice}","input":"{text}","instructions":"Speak in {language_name}. {instructions}","speed":{speed},"response_format":"mp3"}
            """.trimIndent(),
            voices = OnlineTtsListSpec(ref = "voices", arrayField = "voices", idField = "id", nameField = "name"),
            models = OnlineTtsListSpec(ref = "../models", arrayField = "data", idField = "id"),
            builtInVoices = listOf(
                "alloy", "ash", "ballad", "coral", "echo", "fable", "nova",
                "onyx", "sage", "shimmer", "verse", "marin", "cedar",
            ),
            builtInVoicesByModel = listOf(
                "tts-1" to listOf("alloy", "ash", "coral", "echo", "fable", "onyx", "nova", "sage", "shimmer"),
            ),
            // The one model that reads instructions first: they are where
            // this request names the text's language.
            preferredModels = listOf("gpt-4o-mini-tts", "tts-1", "tts-1-hd"),
        )

        /**
         * ElevenLabs's `POST /v1/text-to-speech/{voice_id}`: the voice rides
         * in the path, the key in `xi-api-key`. Its request has no field for
         * instructions.
         *
         * The speed goes in `voice_settings`, settings "applied only on the
         * given request", and the service takes 0.7 to 1.2 of it "for all
         * voices and all models" (its help page on the voice's pace, read
         * 2026-11-13), which is less than the slider gives. Its reference
         * marks every field of `voice_settings` optional and does not say
         * what a request that sends only the speed does to the voice's
         * stored stability and similarity.
         *
         * `language_code` is the text's language as a two-letter code, which
         * the service uses "to enforce a language for the model". By its
         * reference (read 2026-11-13) a model that does not support the
         * code ignores it, `eleven_multilingual_v2` among them, so it is
         * sent to every model.
         */
        val ELEVENLABS_SPEC = OnlineTtsPresetSpec(
            url = "https://api.elevenlabs.io/v1/text-to-speech/{voice}?output_format=mp3_44100_128",
            request = """
                xi-api-key: {key}
                Content-Type: application/json

                {"text":"{text}","model_id":"{model}","language_code":"{language}","voice_settings":{"speed":{speed}}}
            """.trimIndent(),
            voices = OnlineTtsListSpec(
                ref = "/v2/voices?page_size=100", arrayField = "voices", idField = "voice_id", nameField = "name",
            ),
            models = OnlineTtsListSpec(
                ref = "/v1/models", arrayField = null, idField = "model_id", nameField = "name",
                requireTrue = "can_do_text_to_speech",
            ),
            // The fast, lower-priced model first, as the translation
            // presets default to a provider's fast tier; then the long-form
            // one that is the API's own default (ElevenLabs's models page,
            // read 2026-11-13).
            preferredModels = listOf("eleven_flash_v2_5", "eleven_multilingual_v2"),
            speedRange = 0.7f..1.2f,
        )
    }
}

/**
 * The one connected voice service: which request shape it started from, what
 * the user changed of it, and what the request is filled with. Stored as one
 * JSON string ([com.playtranslate.Prefs.onlineTtsConfig]); no stored config
 * means no service is connected. The API key is deliberately NOT a field: it
 * stays in its own encrypted slot.
 *
 * [url] and [request] are null while they are the preset's own, which is
 * how the page saves them ([normalized]), so a fix to a preset reaches every
 * user who never edited it. Their readers are the two `effective*`
 * properties, never the raw fields.
 *
 * [manualVoices] are voice ids the user typed for a service that cannot list
 * its voices. They are the user's alone: a preset's built-in voices are not
 * copied into them, so those follow the chosen model and any later fix.
 *
 * There is no voice here. A voice is chosen per game language, beside the
 * device's voices ([com.playtranslate.Prefs.voiceChoice]).
 */
@Serializable
data class OnlineTtsConfig(
    val preset: OnlineTtsPreset = OnlineTtsPreset.OPENAI,
    val url: String? = null,
    val request: String? = null,
    val model: OnlineTtsChoice? = null,
    val instructions: String = "",
    val manualVoices: List<String> = emptyList(),
) {
    val effectiveUrl: String get() = url ?: preset.spec.url
    val effectiveRequest: String get() = request ?: preset.spec.request

    /**
     * The voices offered when the service's own list gives none: the ones
     * its kind of service has built in for the chosen model, then the ones
     * the user typed.
     */
    val handListedVoices: List<String>
        get() = (preset.spec.builtInVoicesFor(model?.id) + manualVoices).distinct()

    /**
     * True when the app knows this service refuses [voiceId] under the
     * chosen model: it is one of the preset's built-in voices and not one
     * of [handListedVoices]. Known from the preset's data, with no request.
     *
     * False is not a promise that the service takes the voice: it is the
     * answer for every voice the app holds no such data about (one from a
     * service's own list, an id the user typed). And a self-hosted server
     * whose own voice bears a built-in name is judged by that name.
     */
    fun doesNotTake(voiceId: String): Boolean =
        voiceId in preset.spec.builtInVoices && voiceId !in handListedVoices

    /**
     * Which service this is, for the one question that asks: are the voice
     * ids picked under another config still this service's? The request
     * shape and where the request goes (host and port). A new key, model,
     * path or request text is the same service; another preset or another
     * host is not.
     */
    val serviceIdentity: String get() = "${preset.name}|${authority.lowercase()}"

    /** The host the request goes to, as the user typed it: what the
     *  settings row calls the service ("api.elevenlabs.io · Rachel"). */
    val host: String
        get() {
            val hostAndPort = authority.substringAfterLast('@')
            return if (hostAndPort.startsWith("[")) hostAndPort.substringBefore(']') + "]"
            else hostAndPort.substringBefore(':')
        }

    /** The part of the URL between its scheme and its path. Read from the
     *  text, not from a parsed URL: the path may hold a token. */
    private val authority: String
        get() = effectiveUrl.trim().substringAfter("://", "")
            .substringBefore('/').substringBefore('?').substringBefore('#')

    /**
     * This config as it is stored: the URL and the request text null when
     * they equal the preset's, and the typed voice ids tidied. The one place
     * that rule lives, so the page cannot save a copy of a preset's text
     * and freeze it.
     */
    fun normalized(): OnlineTtsConfig {
        val spec = preset.spec
        return copy(
            url = url?.trim()?.takeUnless { it == spec.url },
            request = request?.let(OnlineTtsRequest::normalizeText)
                ?.takeUnless { it == OnlineTtsRequest.normalizeText(spec.request) },
            manualVoices = manualVoices.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
        )
    }
}
