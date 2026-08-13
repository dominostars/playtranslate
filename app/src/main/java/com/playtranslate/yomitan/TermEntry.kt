package com.playtranslate.yomitan

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader

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
            // Tee the glossary: buffer the subtree (bounded — one entry, not
            // the bank), keep its serialized form when it carries structure
            // the flattener degrades, and run the UNCHANGED streaming
            // flattener over that serialized form so flat output stays
            // byte-identical to the pre-tee pipeline. parseReader consumes
            // exactly one element, preserving this parser's stream-position
            // invariant.
            val glossary = JsonParser.parseReader(reader)
            val serialized = glossary.toString()
            defs = JsonReader(StringReader(serialized)).use { TermGlossary.parseGlossary(it) }
            if (hasStructuredItems(glossary)) scJson = serialized
        } else {
            if (reader.hasNext()) reader.skipValue()
        }
        while (reader.hasNext()) reader.skipValue() // sequence, term tags
        reader.endArray()
        return Parsed(term, reading, defTags, score, defs, scJson)
    }

    /** Whether any glossary item is one the flat pipeline degrades: a
     *  `structured-content` tree (styling/layout/data attributes lost) or an
     *  `image` (dropped outright). Bare strings and `{type:"text"}` items
     *  flatten losslessly and don't warrant retention. */
    private fun hasStructuredItems(glossary: JsonElement): Boolean {
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
