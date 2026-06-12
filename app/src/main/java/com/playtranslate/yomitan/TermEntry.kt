package com.playtranslate.yomitan

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken

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
        val defs =
            if (reader.peek() == JsonToken.BEGIN_ARRAY) {
                TermGlossary.parseGlossary(reader)
            } else {
                if (reader.hasNext()) reader.skipValue()
                emptyList()
            }
        while (reader.hasNext()) reader.skipValue() // sequence, term tags
        reader.endArray()
        return Parsed(term, reading, defTags, score, defs)
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
