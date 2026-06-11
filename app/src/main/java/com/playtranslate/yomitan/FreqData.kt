package com.playtranslate.yomitan

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken

/**
 * Parser for the data element of a term_meta `freq` entry. The Yomitan
 * schema (term-meta-bank-v3) allows four shapes:
 *
 *  1. a bare number — rank or occurrence count
 *  2. a bare string — "common", "Top 5k"
 *  3. `{value: number, displayValue?: string}` — value sorts, displayValue
 *     shows ("1234㋕")
 *  4. `{reading: string, frequency: <1|2|3>}` — reading-qualified for
 *     homographs (端 はし vs はじ)
 *
 * Pure JVM (Gson streaming only) so the shape handling is unit-testable
 * without SQLite; [YomitanDataStore.ingestFreq] owns storage and reading
 * normalization.
 */
internal object FreqData {

    /** One parsed datum. [reading] is non-null only for shape 4 and is NOT
     *  normalized here. [display] follows Yomitan's display rule
     *  (displayValue ?: raw string ?: formatted number); [value] is the
     *  sortable number when the shape carries one. */
    data class Row(
        val reading: String?,
        val display: String,
        val value: Double?,
    )

    /**
     * Parses the data element the [reader] is positioned at. ALWAYS consumes
     * exactly that element — on malformed input it still reads/skips to the
     * element's end and returns null, so the caller's stream position stays
     * valid for the next bank entry.
     */
    fun parse(reader: JsonReader): Row? = parseFrequency(reader, allowReading = true)

    /** Shapes 1–3, plus shape 4 when [allowReading] (the reading wrapper
     *  can't nest inside its own `frequency` field). */
    private fun parseFrequency(reader: JsonReader, allowReading: Boolean): Row? =
        when (reader.peek()) {
            JsonToken.NUMBER -> reader.nextDouble().let { Row(null, formatNumber(it), it) }
            JsonToken.STRING ->
                reader.nextString().takeIf { it.isNotBlank() }?.let { Row(null, it, null) }
            JsonToken.BEGIN_OBJECT -> parseObject(reader, allowReading)
            else -> {
                reader.skipValue()
                null
            }
        }

    private fun parseObject(reader: JsonReader, allowReading: Boolean): Row? {
        var reading: String? = null
        var inner: Row? = null
        var value: Double? = null
        var displayValue: String? = null
        var malformed = false
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "reading" ->
                    if (allowReading && reader.peek() == JsonToken.STRING) {
                        reading = reader.nextString()
                    } else {
                        malformed = true
                        reader.skipValue()
                    }
                "frequency" ->
                    if (allowReading) {
                        inner = parseFrequency(reader, allowReading = false)
                        if (inner == null) malformed = true
                    } else {
                        malformed = true
                        reader.skipValue()
                    }
                "value" ->
                    if (reader.peek() == JsonToken.NUMBER) {
                        value = reader.nextDouble()
                    } else {
                        malformed = true
                        reader.skipValue()
                    }
                "displayValue" ->
                    if (reader.peek() == JsonToken.STRING) {
                        displayValue = reader.nextString()
                    } else {
                        malformed = true
                        reader.skipValue()
                    }
                // Unknown keys tolerated for forward compatibility.
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        // Lenient beyond the strict schema: a `frequency` field is honored
        // with or without `reading` (real dicts ship readingless
        // `{"frequency": n, "displayValue": ...}` objects), and a sibling
        // displayValue wins over the inner value's own display either way.
        return when {
            malformed -> null
            inner != null -> Row(reading, displayValue ?: inner.display, inner.value)
            value != null -> Row(reading, displayValue ?: formatNumber(value), value)
            else -> null // neither frequency nor value — not a freq shape
        }
    }

    /** "1234" rather than "1234.0" for the integral values every real
     *  frequency dict uses; non-integral values render as-is. */
    private fun formatNumber(v: Double): String =
        if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()
}
