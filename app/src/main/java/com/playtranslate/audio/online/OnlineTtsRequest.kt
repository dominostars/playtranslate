package com.playtranslate.audio.online

import com.playtranslate.PtJson
import com.playtranslate.net.CustomEndpointPolicy
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URLEncoder

/**
 * Turns an [OnlineTtsConfig] into HTTP requests and reads a service's lists
 * back. Everything a voice service needs of the app is here as data handling,
 * so there is no code per service: hosted text-to-speech APIs are one request
 * that answers with an audio file, and they differ in the URL, the name of
 * the auth header and the names of the JSON fields.
 *
 * **The request text** is what the user can edit: header lines, then the
 * body.
 *
 * ```
 * Authorization: Bearer {key}
 * Content-Type: application/json
 *
 * {"model":"{model}","voice":"{voice}","input":"{text}"}
 * ```
 *
 * The leading lines shaped like a header (`Name: value`) are the headers; the
 * rest, less the blank lines around it, is the body. A body makes the request
 * a POST, no body a GET.
 *
 * **Tokens** ([Token]) are filled in one pass, each value escaped for where
 * its token stands: percent-encoded in the URL, escaped as the inside of a
 * JSON string in the body, as it is in a header. `{speed}` is a bare number
 * everywhere. One pass with the function form of [Regex.replace], the rule
 * [com.playtranslate.translation.llm.LlmPromptTemplates] documents: a value
 * is inserted literally (a `$` or a `\` in it means nothing) and is never
 * scanned again, so a sentence that happens to contain `{key}` is spoken, not
 * filled.
 *
 * Pure: OkHttp's URL parsing and kotlinx's JSON, no Android types.
 */
object OnlineTtsRequest {

    /**
     * A token of the URL and the request text.
     *
     * The two language tokens are how a service is told which language the
     * text is in. It has to be told: given text alone it guesses, and the
     * same characters are different words in two languages (日本語 read as
     * Chinese, a word spelled alike in English and in French). [LANGUAGE] is
     * for a service with a field for the language's code; [LANGUAGE_NAME] is
     * for one whose only place for it is a sentence of instructions.
     */
    enum class Token(val text: String) {
        TEXT("{text}"),
        VOICE("{voice}"),
        MODEL("{model}"),
        INSTRUCTIONS("{instructions}"),
        SPEED("{speed}"),
        LANGUAGE("{language}"),
        LANGUAGE_NAME("{language_name}"),
        KEY("{key}"),
    }

    /**
     * What one request is filled with. [language] is the text's language as
     * a two-letter code ("ja", "fr"), [languageName] as its name in English
     * ("Japanese", "French"). [speed] is the app's slider as it stands;
     * [render] holds it to what the service takes.
     */
    data class Values(
        val text: String = "",
        val voice: String = "",
        val model: String = "",
        val instructions: String = "",
        val speed: Float = 1f,
        val language: String = "",
        val languageName: String = "",
        val key: String = "",
    )

    /** A request ready to send. [body] null is a GET. */
    class Rendered(
        val url: HttpUrl,
        val headers: List<Pair<String, String>>,
        val body: String?,
    )

    /** Why a config cannot be saved. Each is fatal: the page refuses Save. */
    enum class Issue {
        /** The URL does not parse, holds a token before its path, or
         *  [CustomEndpointPolicy] refuses it (http to anything but the
         *  user's own network). */
        BAD_URL,

        /** `{text}` stands in neither the URL nor the body. */
        NO_TEXT,

        /** `{text}` or `{instructions}` stands in a header. A header holds
         *  printable ASCII only, and what is spoken rarely is. */
        TEXT_IN_HEADER,

        /** A header's value, filled in, holds something that is not
         *  printable ASCII: a key pasted with a stray character. */
        HEADER_NOT_ASCII,

        /** `{model}` is in the request and no model is picked. */
        NO_MODEL,
    }

    /** The request text as it is compared and stored: one kind of line
     *  ending, no blank lines around it. */
    fun normalizeText(request: String): String =
        request.replace("\r\n", "\n").replace('\r', '\n').trim()

    /** The tokens [config]'s URL and request text hold: what the page has
     *  a row for. */
    fun tokensIn(config: OnlineTtsConfig): Set<Token> {
        val all = config.effectiveUrl + "\n" + config.effectiveRequest
        return Token.entries.filterTo(LinkedHashSet()) { it.text in all }
    }

    /**
     * The speech request for [values], or null when it cannot be made: the
     * filled URL does not parse or is refused by [CustomEndpointPolicy], or
     * a filled header holds something that is not printable ASCII. The
     * second is checked here, ahead of OkHttp, because OkHttp's own refusal
     * is an exception whose message quotes the value, and the value can be
     * the key.
     *
     * A header whose value holds `{key}` is left out while [Values.key] is
     * blank: a keyless self-hosted server gets no empty `Authorization`.
     *
     * [Values.speed] is held to the speeds the preset says its service
     * takes ([OnlineTtsPresetSpec.speedSent]) before anything is filled.
     * Here, so that [identity] sees the speed that is sent: two slider
     * positions past a service's limit are one clip, asked for once.
     */
    fun render(config: OnlineTtsConfig, values: Values): Rendered? {
        val sent = values.copy(speed = config.preset.spec.speedSent(values.speed))
        val parsed = parse(config.effectiveRequest)
        val url = urlFor(config, sent) ?: return null
        val headers = headersFor(parsed, sent) ?: return null
        val body = fill(parsed.body, sent, ::jsonStringEscape).takeIf { it.isNotBlank() }
        return Rendered(url, headers, body)
    }

    /**
     * The GET that fetches one of the service's lists: [spec]'s reference
     * resolved against the request URL, with the request's own auth. Null
     * under the conditions of [render], or when the reference does not
     * resolve.
     *
     * The auth is the key wherever the request keeps it that a GET can
     * carry: the request's header lines, all of them, and the parameters
     * of the URL's query that hold the key ([keyedQuery]). The second has
     * to be put back by hand, since a reference resolved against a URL
     * keeps nothing of that URL's query. A key the request keeps in its
     * body cannot be sent this way at all ([listSendsKey]).
     */
    fun renderList(config: OnlineTtsConfig, spec: OnlineTtsListSpec, key: String): Rendered? {
        val values = Values(model = config.model?.id.orEmpty(), key = key)
        val resolved = urlFor(config, values)?.resolve(spec.ref) ?: return null
        val url = resolved.newBuilder()
            .apply { keyedQuery(config, values).forEach { (name, value) -> addEncodedQueryParameter(name, value) } }
            .build()
        if (!CustomEndpointPolicy.isAcceptable(url)) return null
        val headers = headersFor(parse(config.effectiveRequest), values) ?: return null
        return Rendered(url, headers, body = null)
    }

    /**
     * Whether a list request of [config] carries the key at all: the key's
     * token stands in a header line or in a parameter of the URL's query.
     * False for a request that keeps its key in the body, or has none. A
     * service's answer to a request that went without the key says nothing
     * about the key.
     */
    fun listSendsKey(config: OnlineTtsConfig): Boolean =
        parse(config.effectiveRequest).headers.any { (_, value) -> Token.KEY.text in value } ||
            keyedQueryParts(config).isNotEmpty()

    /** The parameters of the URL's query whose value holds the key's
     *  token, each as written: `name=value`. */
    private fun keyedQueryParts(config: OnlineTtsConfig): List<String> =
        config.effectiveUrl.trim().substringBefore('#').substringAfter('?', "")
            .split('&')
            .filter { Token.KEY.text in it.substringAfter('=', "") }

    /**
     * Those parameters filled in, name and value, each percent-encoded as
     * [urlFor] encodes the URL they come from. None while there is no key:
     * an empty `key=` on a list request would be asking with a key that is
     * not one, as a keyed header is left out for the same reason.
     */
    private fun keyedQuery(config: OnlineTtsConfig, values: Values): List<Pair<String, String>> =
        if (values.key.isBlank()) {
            emptyList()
        } else {
            keyedQueryParts(config).map { part ->
                fill(part.substringBefore('='), values, ::percentEncode) to
                    fill(part.substringAfter('=', ""), values, ::percentEncode)
            }
        }

    /**
     * What makes two requests the same audio: the filled URL, headers and
     * body with the key left out. A clip is cached under it, so the key is
     * never part of a cache name and a new key keeps the cache, while a
     * change of text, voice, model, instructions, speed or the request text
     * itself is a different clip. Null when [render] is.
     */
    fun identity(config: OnlineTtsConfig, values: Values): String? {
        val r = render(config, values.copy(key = "")) ?: return null
        return buildString {
            append(r.url).append('\n')
            r.headers.forEach { (name, value) -> append(name).append(':').append(value).append('\n') }
            append('\n').append(r.body.orEmpty())
        }
    }

    /** Why [config] cannot be saved with [key] (already trimmed); empty
     *  when it can. A body the service rejects is not found here: hearing a
     *  voice is the test of that. */
    fun validate(config: OnlineTtsConfig, key: String): List<Issue> {
        val parsed = parse(config.effectiveRequest)
        val sample = Values(
            text = "a", voice = "a", model = config.model?.id ?: "a",
            language = "en", languageName = "English", key = key,
        )
        val issues = ArrayList<Issue>()
        if (urlFor(config, sample) == null) issues += Issue.BAD_URL
        if (Token.TEXT.text !in config.effectiveUrl && Token.TEXT.text !in parsed.body) issues += Issue.NO_TEXT
        val spokenInHeader = parsed.headers.any { (_, value) ->
            Token.TEXT.text in value || Token.INSTRUCTIONS.text in value
        }
        if (spokenInHeader) issues += Issue.TEXT_IN_HEADER
        else if (headersFor(parsed, sample) == null) issues += Issue.HEADER_NOT_ASCII
        if (Token.MODEL in tokensIn(config) && config.model?.id.isNullOrBlank()) issues += Issue.NO_MODEL
        return issues
    }

    /**
     * The items of a list reply, read as [spec] says; null when the reply
     * is not JSON or holds no array where [spec] names one. An item that is
     * a plain string is its own id and name; an object gives its id field
     * and, where it has one, its name field. Items with no id, and items
     * [OnlineTtsListSpec.requireTrue] rules out, are left out.
     */
    fun parseList(spec: OnlineTtsListSpec, json: String): List<OnlineTtsChoice>? {
        val root: JsonElement = runCatching { PtJson.lenient.parseToJsonElement(json) }.getOrNull()
            ?: return null
        val array = when (spec.arrayField) {
            null -> root
            else -> (root as? JsonObject)?.get(spec.arrayField)
        } as? JsonArray ?: return null
        return array.mapNotNull { item ->
            when (item) {
                is JsonObject -> {
                    val allowed = spec.requireTrue == null ||
                        (item[spec.requireTrue] as? JsonPrimitive)?.booleanOrNull == true
                    val id = item.string(spec.idField)
                    if (!allowed || id == null) null
                    else OnlineTtsChoice(id, spec.nameField?.let { item.string(it) } ?: id)
                }
                is JsonPrimitive -> item.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
                    ?.let { OnlineTtsChoice(it) }
                else -> null
            }
        }
    }

    // ── Parsing and filling ──────────────────────────────────────────────

    private class Parsed(val headers: List<Pair<String, String>>, val body: String)

    /** A header line: an HTTP token, a colon, the value. A JSON body's
     *  first line never has this shape, which is what ends the headers. */
    private val HEADER_LINE = Regex("""^([!#$%&'*+.^_`|~0-9A-Za-z-]+)[ \t]*:(.*)$""")

    private val TOKEN_REGEX =
        Regex(Token.entries.joinToString("|") { Regex.escape(it.text) })

    private fun parse(request: String): Parsed {
        val lines = normalizeText(request).split('\n')
        val headers = ArrayList<Pair<String, String>>()
        var next = 0
        while (next < lines.size) {
            val match = HEADER_LINE.matchEntire(lines[next]) ?: break
            headers += match.groupValues[1] to match.groupValues[2].trim()
            next++
        }
        return Parsed(headers, lines.drop(next).joinToString("\n").trim())
    }

    /**
     * The request URL filled in, or null when it is no URL the app will
     * call. A token before the path (in the host, the port or the user
     * part) is refused outright: where a request goes is the user's typed
     * choice, never a value's.
     */
    private fun urlFor(config: OnlineTtsConfig, values: Values): HttpUrl? {
        val template = config.effectiveUrl.trim()
        val authority = template.substringAfter("://", "")
            .substringBefore('/').substringBefore('?').substringBefore('#')
        if (TOKEN_REGEX.containsMatchIn(authority)) return null
        return fill(template, values, ::percentEncode).toHttpUrlOrNull()
            ?.takeIf(CustomEndpointPolicy::isAcceptable)
    }

    /** [parsed]'s headers filled in, less a keyed one while there is no
     *  key; null when a filled value is not printable ASCII. */
    private fun headersFor(parsed: Parsed, values: Values): List<Pair<String, String>>? {
        val out = ArrayList<Pair<String, String>>()
        for ((name, template) in parsed.headers) {
            if (Token.KEY.text in template && values.key.isBlank()) continue
            val value = fill(template, values) { it }
            if (value.any { it.code !in 0x20..0x7E }) return null
            out += name to value
        }
        return out
    }

    private fun fill(template: String, values: Values, escape: (String) -> String): String =
        TOKEN_REGEX.replace(template) { match ->
            when (Token.entries.first { it.text == match.value }) {
                Token.TEXT -> escape(values.text)
                Token.VOICE -> escape(values.voice)
                Token.MODEL -> escape(values.model)
                Token.INSTRUCTIONS -> escape(values.instructions)
                // Float.toString, never a format call: a format call writes
                // "1,5" under a locale whose decimal mark is a comma.
                Token.SPEED -> values.speed.toString()
                Token.LANGUAGE -> escape(values.language)
                Token.LANGUAGE_NAME -> escape(values.languageName)
                Token.KEY -> escape(values.key)
            }
        }

    /** [value] as one URL component: everything but the unreserved
     *  characters percent-encoded, a space as `%20`. */
    private fun percentEncode(value: String): String =
        URLEncoder.encode(value, "UTF-8")
            .replace("+", "%20")
            .replace("*", "%2A")
            .replace("%7E", "~")

    /** [value] as the inside of a JSON string: what kotlinx writes for it,
     *  less the quotes it puts around it. */
    private fun jsonStringEscape(value: String): String =
        JsonPrimitive(value).toString().let { it.substring(1, it.length - 1) }

    private fun JsonObject.string(field: String): String? =
        (this[field] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
}
