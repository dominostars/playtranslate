package com.playtranslate.yomitan

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import java.io.StringReader
import java.io.StringWriter

/**
 * Parser for one term_bank entry — the 8-element positional array
 * [term, reading, defTags, rules, score, glossary, sequence, termTags].
 *
 * Positional formats punish leave-and-default guards: term-bank-v3
 * declares defTags as string|NULL, and skipping past an unexpected token
 * without consuming it shifts every later field by one slot (a null
 * defTags would silently push the real glossary out of reach). Every read
 * here therefore CONSUMES exactly one element — defaulting on wrong types
 * — and short rows simply run out into defaults. Pure JVM, unit-tested,
 * and like [FreqData] it always consumes exactly its entry so malformed
 * input never corrupts the stream position.
 */
internal object TermEntry {

    data class Parsed(
        val term: String,
        val reading: String,
        val defTags: String,
        val score: Double,
        /** Raw flattened glossary strings (echo stripping is the caller's
         *  job — it needs the resolved reading). */
        val defs: List<String>,
        /** The glossary array's own JSON, verbatim-equivalent (re-serialized
         *  from the parsed tree), when it carries anything the flat [defs]
         *  lose — structured-content or image items. Null for plain-text
         *  glossaries, where the flat strings ARE the content. Ingest
         *  retains this for the styled renderer ([YomitanDataStore]'s
         *  `term_sc` table). */
        val scJson: String? = null,
    )

    /** Parses the entry the [reader] is positioned at; null when the
     *  element isn't an array at all (consumed either way). */
    fun parse(reader: JsonReader): Parsed? {
        if (reader.peek() != JsonToken.BEGIN_ARRAY) {
            reader.skipValue()
            return null
        }
        reader.beginArray()
        val term = nextStringOr(reader, "")
        val reading = nextStringOr(reader, "")
        val defTags = nextStringOr(reader, "")
        if (reader.hasNext()) reader.skipValue() // deinflection rules
        val score = nextDoubleOr(reader, 0.0)
        var defs: List<String> = emptyList()
        var scJson: String? = null
        if (reader.peek() == JsonToken.BEGIN_ARRAY) {
            // Tee the glossary through the BUDGETED capture — never an
            // unbounded JsonParser buffer, which would hand a hostile bank
            // a one-entry OOM (pre-v8 glossaries streamed with bounded
            // memory; the tee must not regress that). Within budget, the
            // UNCHANGED streaming flattener runs over the captured form so
            // flat output stays byte-identical to the pre-tee pipeline;
            // over budget the element is consumed and the entry falls out
            // through the caller's empty-defs skip — the same defensive
            // fate as a malformed entry (no legitimate dictionary carries
            // a half-megabyte single glossary; Jitendex tops out around
            // 100KB).
            val serialized = captureGlossary(reader)
            if (serialized != null) {
                defs = JsonReader(StringReader(serialized)).use { TermGlossary.parseGlossary(it) }
                if (hasStructuredItems(serialized)) scJson = serialized
            }
        } else {
            if (reader.hasNext()) reader.skipValue()
        }
        while (reader.hasNext()) reader.skipValue() // sequence, term tags
        reader.endArray()
        return Parsed(term, reading, defTags, score, defs, scJson)
    }

    /** Retention budget for one glossary's serialized JSON, in chars.
     *  Doubles as the transient-memory bound during capture and (×4, the
     *  UTF-8 worst case) the inflation ceiling on the read side
     *  ([YomitanDataStore.structuredGlossaries]). Sized ~5x over the
     *  largest real-world entries (Jitendex ~100KB-class). */
    internal const val MAX_RETAINED_GLOSSARY_CHARS = 512 * 1024

    /**
     * Serializes the JSON element the [reader] is positioned at, bounded by
     * [budget] chars. Returns null when the element exceeds the budget — in
     * which case the REMAINDER is consumed in skip mode (structure walked,
     * string/number tokens skipped without materialization, accumulation
     * stopped), so the caller's consume-exactly-one-element invariant holds
     * either way and a hostile many-token glossary can't accumulate memory.
     * Residual: one single oversized string TOKEN still transits the heap
     * once (Gson offers no length-capped nextString); what's bounded is
     * everything after it.
     *
     * Token-level rewrite rather than JsonElement round-trip: numbers are
     * emitted verbatim ([JsonWriter.jsonValue]), so the capture is
     * source-faithful.
     */
    internal fun captureGlossary(
        reader: JsonReader,
        budget: Int = MAX_RETAINED_GLOSSARY_CHARS,
    ): String? {
        val out = StringWriter()
        val writer = JsonWriter(out)
        var over = false
        var depth = 0
        do {
            if (over) {
                when (reader.peek()) {
                    JsonToken.BEGIN_ARRAY -> { reader.beginArray(); depth++ }
                    JsonToken.END_ARRAY -> { reader.endArray(); depth-- }
                    JsonToken.BEGIN_OBJECT -> { reader.beginObject(); depth++ }
                    JsonToken.END_OBJECT -> { reader.endObject(); depth-- }
                    JsonToken.NAME -> reader.nextName()
                    // skipValue never materializes the token's text.
                    else -> reader.skipValue()
                }
            } else {
                when (reader.peek()) {
                    JsonToken.BEGIN_ARRAY -> { reader.beginArray(); writer.beginArray(); depth++ }
                    JsonToken.END_ARRAY -> { reader.endArray(); writer.endArray(); depth-- }
                    JsonToken.BEGIN_OBJECT -> { reader.beginObject(); writer.beginObject(); depth++ }
                    JsonToken.END_OBJECT -> { reader.endObject(); writer.endObject(); depth-- }
                    JsonToken.NAME -> writer.name(reader.nextName())
                    JsonToken.STRING -> writer.value(reader.nextString())
                    // Raw literal, not a double round-trip.
                    JsonToken.NUMBER -> writer.jsonValue(reader.nextString())
                    JsonToken.BOOLEAN -> writer.value(reader.nextBoolean())
                    JsonToken.NULL -> { reader.nextNull(); writer.nullValue() }
                    else -> reader.skipValue()
                }
                if (out.buffer.length > budget) over = true
            }
        } while (depth > 0)
        if (over) return null // abandoned mid-document; never close the writer
        writer.close()
        return out.toString()
    }

    /** Whether any glossary item is one the flat pipeline degrades: a
     *  `structured-content` tree (styling/layout/data attributes lost) or an
     *  `image` (dropped outright). Bare strings and `{type:"text"}` items
     *  flatten losslessly and don't warrant retention. Takes the captured
     *  (budget-bounded) serialized form. */
    internal fun hasStructuredItems(serialized: String): Boolean {
        val glossary: JsonElement = try {
            JsonParser.parseString(serialized)
        } catch (_: RuntimeException) {
            return false
        }
        if (!glossary.isJsonArray) return false
        return glossary.asJsonArray.any { item ->
            item.isJsonObject &&
                item.asJsonObject.get("type")
                    ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                    ?.asString.let { it == "structured-content" || it == "image" }
        }
    }

    /** One positional slot as a string: consumes the element whatever its
     *  type (defaulting on non-strings); only a short row consumes nothing. */
    private fun nextStringOr(reader: JsonReader, default: String): String =
        when (reader.peek()) {
            JsonToken.STRING -> reader.nextString()
            JsonToken.END_ARRAY -> default
            else -> {
                reader.skipValue()
                default
            }
        }

    private fun nextDoubleOr(reader: JsonReader, default: Double): Double =
        when (reader.peek()) {
            JsonToken.NUMBER -> reader.nextDouble()
            JsonToken.END_ARRAY -> default
            else -> {
                reader.skipValue()
                default
            }
        }
}
