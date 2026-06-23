package com.playtranslate.yomitan

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken

/**
 * Parser for one kanji_bank entry — the 6-element positional array
 * [character, onyomi, kunyomi, tags, meanings, stats].
 *
 * Like [TermEntry], every read CONSUMES exactly one element so a wrong-typed
 * or short row defaults in place instead of shifting the later slots, and the
 * whole entry is always consumed so malformed input never corrupts the stream
 * position. The on/kun fields keep the bank's raw space-separated form (split
 * downstream by [KanjiData.splitReadings]).
 *
 * The otherwise-ignored stats object (index/code/class data we don't
 * interpret — the built-in KANJIDIC2 pack supplies grade/JLPT/strokes) is
 * mined for one key: `freq`. KANJIDIC-lineage dicts pack their per-kanji
 * frequency rank there rather than shipping a kanji_meta_bank, so lifting it
 * here is what lets that frequency reach the kanji-frequency chips.
 */
internal object KanjiBankEntry {

    data class Parsed(
        val character: String,
        val onyomi: String,
        val kunyomi: String,
        val meanings: List<String>,
        /** The `freq` stat when the entry carries one, else null. [display]
         *  and [value][FreqData.Row.value] follow [FreqData]'s rules; KANJIDIC
         *  ships string ranks ("294") so `value` is typically null and the
         *  rank rides in `display`. */
        val freq: FreqData.Row?,
    )

    /** Parses the entry the [reader] is positioned at; null when the element
     *  isn't an array at all (consumed either way). A returned [Parsed] with
     *  a blank [Parsed.character] is structurally valid but useless — the
     *  caller skips it, matching the old inline guard. */
    fun parse(reader: JsonReader): Parsed? {
        if (reader.peek() != JsonToken.BEGIN_ARRAY) {
            reader.skipValue()
            return null
        }
        reader.beginArray()
        val character = nextStringOr(reader, "")
        val onyomi = nextStringOr(reader, "")
        val kunyomi = nextStringOr(reader, "")
        if (reader.hasNext()) reader.skipValue() // tags
        val meanings =
            if (reader.peek() == JsonToken.BEGIN_ARRAY) {
                val list = mutableListOf<String>()
                reader.beginArray()
                while (reader.hasNext()) {
                    if (reader.peek() == JsonToken.STRING) list.add(reader.nextString())
                    else reader.skipValue()
                }
                reader.endArray()
                list
            } else {
                if (reader.hasNext()) reader.skipValue()
                emptyList()
            }
        // Stats object: harvest `freq`, ignore every other key.
        var freq: FreqData.Row? = null
        if (reader.peek() == JsonToken.BEGIN_OBJECT) {
            reader.beginObject()
            while (reader.hasNext()) {
                if (reader.nextName() == "freq") freq = FreqData.parse(reader)
                else reader.skipValue()
            }
            reader.endObject()
        }
        while (reader.hasNext()) reader.skipValue() // stats (when shifted) + extras
        reader.endArray()
        return Parsed(character, onyomi, kunyomi, meanings, freq)
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
}
