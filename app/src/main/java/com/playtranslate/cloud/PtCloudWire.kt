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
 */
@Serializable
data class FrameMeta(
    val source: String,
    val target: String,
    @SerialName("frame_id") val frameId: String,
    val persona: String?,
    val context: String?,
    @SerialName("stream_translations") val streamTranslations: Boolean? = null,
)

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
 *  not read. */
@Serializable
data class WireLine(
    val id: Int = 0,
    val text: String,
    val bounds: WireBox,
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

/** The final NDJSON line, after any item lines: translations keyed by part
 *  1's group ids, and how translation settled
 *  ([PtCloudErrors.classifyTranslation]). */
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
