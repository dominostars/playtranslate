package com.playtranslate.cloud

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/*
 * PlayTranslate Cloud's wire format, schema version 1 (docs/api.md in the
 * playtranslate-cloud repository). Decoded with PtJson.lenient: unknown keys
 * are ignored, and optional fields are ABSENT on the wire, never null, so
 * each one is nullable (or empty) with a default. Coordinates are integer
 * pixels of the UPLOADED image; PtCloudResponseMapper maps them back to the
 * captured bitmap.
 */

/** The only schema version this client reads; anything else means client
 *  and server disagree about the format. */
const val PT_CLOUD_SCHEMA_VERSION = 1

// ── Request ────────────────────────────────────────────────────────────

/**
 * The `meta` form field of `POST /v1/frame`. [source] is one of
 * [PtCloudRouting.SUPPORTED_SOURCE_CODES]; [target] equal to [source] makes
 * the server skip translation. [persona] and [context] are omitted from the
 * JSON when null (`explicitNulls = false`), which asks for the server's
 * defaults. [streamTranslations] true asks for an item line per group as its
 * translation completes ("Incremental translations" in docs/api.md); null
 * leaves the key out, the server's default of no item lines.
 * [filterFurigana] is the user's furigana filter, always sent: true asks the
 * server to remove the readings from a Japanese frame before grouping, false
 * keeps them. The server applies it to a Japanese source only, as the device
 * does, and a server that predates it ignores the key and keeps every
 * reading.
 */
@Serializable
data class FrameMeta(
    val source: String,
    val target: String,
    @SerialName("frame_id") val frameId: String,
    val persona: String?,
    val context: String?,
    @SerialName("stream_translations") val streamTranslations: Boolean? = null,
    /** Required, with no default: [com.playtranslate.PtJson.lenient] does not
     *  encode a value equal to its default, so a default of true would leave
     *  the key out whenever it is true, and the client always states the
     *  user's choice rather than relying on the server's default. */
    @SerialName("filter_furigana") val filterFurigana: Boolean,
)

/**
 * The JSON body of `POST /v1/translate` ("Translation only" in docs/api.md):
 * [texts] in, one [TranslationPart] out whose translations are keyed by each
 * text's position in [texts]. [source] and [target] are the registry's
 * translation codes as [PtCloudBackend] was handed them; the server keys its
 * cache by the source's primary subtag, so a frame's `zh-Hant` and this
 * route's `zh` share entries. [persona] and [context] are omitted when null
 * (`explicitNulls = false`), which asks for the server's defaults, as
 * [FrameMeta] does. `cache`, `stream_translations` and `debug` are left out:
 * the server's defaults are what this route wants.
 */
@Serializable
data class TranslateRequest(
    val source: String,
    val target: String,
    val texts: List<String>,
    @SerialName("frame_id") val frameId: String,
    val persona: String?,
    val context: String?,
)

// ── Response, errors ───────────────────────────────────────────────────

/**
 * The edge's JSON error body (`{"error":"..."}`), read by [PtCloudErrors]
 * alone: its [error] word tells one answer of a status from another (a 403
 * `no_entitlement` or `terms_required` from any other 403, a 409
 * `terms_outdated` from any other 409, a 410 `service_closed` from any other
 * 410, a 502 `ocr_unavailable` from the host being off, and the sign-in
 * routes' refusals from each other), and the fields beside it carry what
 * that answer says. Lenient: every field optional, and absent from a Worker
 * that predates it. Never logged but for the [error] word on a sign-in or
 * terms refusal ([PtCloudClient.requestCode], [PtCloudClient.verifyCode],
 * [PtCloudClient.fetchTerms], [PtCloudClient.acceptTerms]).
 */
@Serializable
data class EdgeError(
    val error: String? = null,
    /** A 410 `service_closed`'s text: the operator's plain words for the
     *  member, at most 500 characters, shown on the cloud page as they are
     *  and never in the pill. */
    val message: String? = null,
    /** A 403 `no_entitlement`'s entitlement that ended most recently:
     *  `trial` is the free trial having run out ([SOURCE_TRIAL]); null when
     *  none has ended, absent from an older Worker or after its read
     *  failed. */
    @SerialName("last_source") val lastSource: String? = null,
    /** When [lastSource] ended, ISO 8601 UTC. */
    @SerialName("ended_at") val endedAt: String? = null,
    /** A 403 `terms_required`'s and a 409 `terms_outdated`'s current version
     *  of the terms, `YYYY-MM-DD` (the date on the text's own `Version:`
     *  line); absent from every other answer. */
    val version: String? = null,
    /** A verify 401 `code_invalid`'s tries left on the code; 0 when no live
     *  code is left (expired, used, replaced, never sent, or out of tries). */
    @SerialName("attempts_left") val attemptsLeft: Int? = null,
)

// ── Sign-in ────────────────────────────────────────────────────────────

/** The body of `POST /v1/signin` ("Sign-in" in docs/api.md): the address
 *  a code is mailed to, as the member typed it (the server trims it and
 *  lowercases its ASCII letters). Sent to the two sign-in routes only, and
 *  never logged. */
@Serializable
data class SignInRequestBody(val email: String)

/** The body of `POST /v1/signin/verify`: the address the code went to, the
 *  six digits, and the device's [label] ([deviceLabel]), which the operator
 *  reads to tell a member's keys apart. None of it is logged. */
@Serializable
data class VerifyRequestBody(val email: String, val code: String, val label: String)

/** A sign-in request's 200: the code works for [expiresIn] seconds, and a
 *  new one can be asked for [resendAfter] seconds after this answer. The
 *  defaults are the server's own, for an answer that leaves either out. */
@Serializable
data class SignInSentBody(
    @SerialName("expires_in") val expiresIn: Int = 600,
    @SerialName("resend_after") val resendAfter: Int = 60,
)

/**
 * A verify's 200, only the fields the app reads: the client [key], answered
 * once and never again by any route, and the account's state, which
 * [entitled] says is active or not. Active, [source] and [entitledUntil]
 * name the entitlement that runs longest ([entitledUntil] null beside a
 * [source] is open-ended); inactive, [lastSource] and [endedAt] name the one
 * that ended most recently. `trial` (whether this sign-in started the trial)
 * is not read: the account's state is [source], never that flag. The key is
 * never logged.
 */
@Serializable
data class VerifyOkBody(
    val key: String,
    val entitled: Boolean,
    val source: String? = null,
    @SerialName("entitled_until") val entitledUntil: String? = null,
    @SerialName("last_source") val lastSource: String? = null,
    @SerialName("ended_at") val endedAt: String? = null,
)

/** `GET /v1/key`'s 200 from the sign-in Worker: the entitlement that runs
 *  longest, as in [VerifyOkBody]. Both are absent from an older Worker and
 *  after the Worker's read of them failed, the key being proven good either
 *  way: [source] is the test for that, never a missing [entitledUntil]. */
@Serializable
data class KeyOkBody(
    val source: String? = null,
    @SerialName("entitled_until") val entitledUntil: String? = null,
)

// ── Terms ──────────────────────────────────────────────────────────────

/** `GET /v1/terms`'s 200 ("Terms" in docs/api.md), only the fields the app
 *  reads: the [version] an acceptance names (the date on the text's own
 *  `Version:` line), the [locale] the server served, which the acceptance
 *  echoes, and the whole [text], Markdown in the server's subset
 *  ([com.playtranslate.ui.TermsMarkdown]). `format` (always `markdown`) is
 *  not read. A body missing any of the three does not decode. None of it is
 *  logged. */
@Serializable
data class TermsBody(val version: String, val locale: String, val text: String)

/** The body of `POST /v1/terms/accept` ("Accepting the terms" in
 *  docs/api.md): the [version] the text came with and the [locale] its
 *  answer named ([TermsAgreement]), never a version from a 403. Never
 *  logged. */
@Serializable
data class TermsAcceptBody(val version: String, val locale: String)

// ── Response, part 1 ───────────────────────────────────────────────────

/** The first NDJSON line: the OCR groups, final, before translation. */
@Serializable
data class OcrPart(
    val part: String,
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("frame_id") val frameId: String? = null,
    val source: String? = null,
    val target: String? = null,
    val image: WireImage? = null,
    val groups: List<WireGroup> = emptyList(),
    @SerialName("translation_status") val translationStatus: String? = null,
    val engine: WireEngine? = null,
    val timings: Map<String, Double> = emptyMap(),
)

/** The decoded upload's pixel size and byte count, as the server saw it. */
@Serializable
data class WireImage(val w: Int = 0, val h: Int = 0, val bytes: Long = 0)

/** Model identifiers, for logs. */
@Serializable
data class WireEngine(
    val ocr: String? = null,
    val head: String? = null,
    @SerialName("det_arm") val detArm: String? = null,
    val translator: String? = null,
    val service: String? = null,
)

/** One paragraph, already in the app's post-grouping shape. [id] is its
 *  position in `groups` and the key the item lines and the final part use.
 *  [translation] is present only when the server's translation cache
 *  answered the group ("Translation cache" in docs/api.md), and is then
 *  final for this frame: nothing later in the response changes it, and no
 *  item line follows for the group. */
@Serializable
data class WireGroup(
    val id: Int,
    val text: String,
    val bounds: WireBox,
    @SerialName("draw_bounds") val drawBounds: WireBox? = null,
    /** `"h"` or `"v"`. */
    val orientation: String = "h",
    /** `"left"` or `"center"`. */
    val alignment: String = "left",
    val angle: Double = 0.0,
    val ow: Double = 0.0,
    val oh: Double = 0.0,
    val lines: List<WireLine> = emptyList(),
    /** Absent when the cache did not answer, and always from a server that
     *  predates the cache. */
    val translation: String? = null,
)

/** One line of a group. The detector's `quad` is diagnostics only and is
 *  not read. [textStart] is the offset of [text] in the group's text, the
 *  address a group-level annotation needs to land on this line (the app's
 *  own `joinLines` rule, run inside the server's copy of the grouping):
 *  `group.text.substring(text_start, text_start + text.length) == text`.
 *  Sent by the server from image p9c on; absent, and so 0 on every line,
 *  from an older one, which [PtCloudResponseMapper] addresses itself. */
@Serializable
data class WireLine(
    val id: Int = 0,
    val text: String,
    val bounds: WireBox,
    @SerialName("text_start") val textStart: Int = 0,
    val orientation: String = "h",
    /** Recognizer score 0..1; -1 means no signal, never "low". */
    val conf: Double = -1.0,
    val angle: Double = 0.0,
    val ow: Double = 0.0,
    val oh: Double = 0.0,
    val elements: List<WireElement> = emptyList(),
    /** Empty when the recognizer gave no usable columns: the app's
     *  proportional fallback applies. */
    val chars: List<WireChar> = emptyList(),
)

/** A word of a whitespace-separated script (empty for CJK). */
@Serializable
data class WireElement(val text: String, val bounds: WireBox)

/** One recognized character; [offset] is its position in the line's text,
 *  by the app's own `PaddleCharBoxes` rule (`SymbolBox.charOffset`). */
@Serializable
data class WireChar(val ch: String, val bounds: WireBox, val offset: Int)

/**
 * A box `[left, top, right, bottom]`, right and bottom exclusive (the app's
 * `Rect` convention). Decoded from a JSON array of exactly four integers, so
 * a malformed box fails part 1's decode (a bad response) instead of reaching
 * the mapper.
 */
@Serializable(with = WireBox.Serializer::class)
data class WireBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {

    internal object Serializer : KSerializer<WireBox> {
        private val list = ListSerializer(Int.serializer())
        override val descriptor: SerialDescriptor = list.descriptor

        override fun deserialize(decoder: Decoder): WireBox {
            val v = list.deserialize(decoder)
            if (v.size != 4) throw SerializationException("box has ${v.size} values, expected 4")
            return WireBox(v[0], v[1], v[2], v[3])
        }

        override fun serialize(encoder: Encoder, value: WireBox) =
            list.serialize(encoder, listOf(value.left, value.top, value.right, value.bottom))
    }
}

// ── Response, after part 1 ─────────────────────────────────────────────

/** Just a line's `part`, read first so the client knows how to read the
 *  rest of the line; null when the line has none. */
@Serializable
data class WirePartTag(val part: String? = null)

/**
 * An item line, sent between part 1 and the final part when the frame asked
 * for them ([FrameMeta.streamTranslations]): the translation of one group
 * part 1 left untranslated, the moment it completes. Provisional; the final
 * part is authoritative. The client times each item for its clock line and
 * hands it on as it arrives through [FrameResponse.items]. [tMs] is
 * milliseconds from the request's start on the server.
 */
@Serializable
data class TranslationItem(
    val part: String,
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("frame_id") val frameId: String? = null,
    val id: Int,
    val translation: String,
    @SerialName("t_ms") val tMs: Double? = null,
)

/** The final NDJSON line of a frame, after any item lines: translations
 *  keyed by part 1's group ids, and how translation settled
 *  ([PtCloudErrors.classifyTranslation]). Also the whole body of
 *  `POST /v1/translate` ([TranslateRequest]), keyed there by each text's
 *  position in the request. */
@Serializable
data class TranslationPart(
    val part: String,
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("frame_id") val frameId: String? = null,
    /** `ok`, `deferred`, `failed` or `skipped`. */
    @SerialName("translation_status") val translationStatus: String,
    /** Null when absent, which is a bad response for status `ok`. */
    val translations: List<WireTranslation>? = null,
    /** Present only with status `failed`. */
    @SerialName("translation_error") val translationError: WireTranslationError? = null,
    val timings: Map<String, Double> = emptyMap(),
)

@Serializable
data class WireTranslation(val id: Int, val translation: String)

/** Why translation failed; [cls] is what the client's cooldown records. */
@Serializable
data class WireTranslationError(
    @SerialName("class") val cls: String,
    @SerialName("http_status") val httpStatus: Int? = null,
    @SerialName("retry_after_s") val retryAfterS: Double? = null,
    val message: String? = null,
)
