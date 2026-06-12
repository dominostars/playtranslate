package com.playtranslate.yomitan

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.playtranslate.dictionary.Deinflector

/**
 * Flattens a term_bank entry's glossary array to plain-text definitions —
 * the ecosystem-standard degradation while full structured-content
 * rendering is deferred. One output string per glossary item; items with
 * no text (images, deinflection redirects) emit nothing.
 *
 * Glossary item shapes (term-bank-v3):
 *  - bare string
 *  - `{type: "text", text}` / `{type: "image", ...}` (skipped) /
 *    `{type: "structured-content", content: <node tree>}`
 *  - deinflection redirect array `[uninflected, rules]` — skipped; a
 *    redirect is meaningless without following it
 *
 * Structured-content flattening rules: text nodes concatenate; `br` and
 * block-ish containers (div, li, tr, details, summary) introduce line
 * breaks; `ul` items join with "; " (unordered lists carry parallel
 * glosses — JMdict-style — while `ol` carries distinct numbered senses
 * and keeps its lines); table cells join with " | "; ruby keeps its base
 * text while `rt`/`rp` (furigana annotations) and `img` are dropped;
 * everything else (span, a, ol, table scaffolding) passes its content
 * through.
 *
 * Pure JVM (Gson streaming only) for unit-testability. Like [FreqData],
 * every parse ALWAYS consumes exactly its element — malformed input never
 * corrupts the caller's stream position inside a 100MB bank.
 */
internal object TermGlossary {

    /** Parses the glossary array the [reader] is positioned at. */
    fun parseGlossary(reader: JsonReader): List<String> {
        val defs = mutableListOf<String>()
        reader.beginArray()
        while (reader.hasNext()) {
            when (reader.peek()) {
                JsonToken.STRING -> clean(reader.nextString())?.let { defs.add(it) }
                JsonToken.BEGIN_OBJECT -> parseItemObject(reader)?.let { defs.add(it) }
                // Deinflection arrays (form C) and anything unexpected.
                else -> reader.skipValue()
            }
        }
        reader.endArray()
        return defs
    }

    /** `{type: text|image|structured-content}`. Key order is not assumed:
     *  `text`/`content` are collected as encountered and the `type` verdict
     *  (image → discard) applies at the end. */
    private fun parseItemObject(reader: JsonReader): String? {
        var type: String? = null
        val text = StringBuilder()
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "type" ->
                    if (reader.peek() == JsonToken.STRING) type = reader.nextString()
                    else reader.skipValue()
                "text" ->
                    if (reader.peek() == JsonToken.STRING) text.append(reader.nextString())
                    else reader.skipValue()
                "content" -> text.append(walkNode(reader))
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return if (type == "image") null else clean(text.toString())
    }

    /** One structured-content node: string | array of nodes | tagged
     *  object. For tagged objects the content is collected first and the
     *  tag rule applied at the node's end, so tag-after-content key order
     *  parses identically. */
    private fun walkNode(reader: JsonReader): String = when (reader.peek()) {
        JsonToken.STRING -> reader.nextString()
        JsonToken.BEGIN_ARRAY -> buildString {
            reader.beginArray()
            while (reader.hasNext()) append(walkNode(reader))
            reader.endArray()
        }
        JsonToken.BEGIN_OBJECT -> {
            var tag: String? = null
            val content = StringBuilder()
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "tag" ->
                        if (reader.peek() == JsonToken.STRING) tag = reader.nextString()
                        else reader.skipValue()
                    "content" -> content.append(walkNode(reader))
                    else -> reader.skipValue() // style, data, lang, href, path…
                }
            }
            reader.endObject()
            when (tag) {
                "rt", "rp", "img" -> ""
                "br" -> "\n"
                "div", "li", "tr", "details", "summary" -> "\n$content\n"
                "td", "th" -> "$content | "
                // Unordered lists hold PARALLEL items (JMdict's gloss lists)
                // — joined like the pack joins glosses. Ordered lists hold
                // distinct numbered senses (monolingual conversions) and
                // keep their line breaks via the li rule above.
                "ul" -> {
                    val joined = content.toString().split('\n')
                        .map { it.trim() }.filter { it.isNotEmpty() }
                        .joinToString("; ")
                    "\n$joined\n"
                }
                else -> content.toString()
            }
        }
        else -> {
            reader.skipValue()
            ""
        }
    }

    /** Collapses the raw flattened text: per-line trim, drop blanks, strip
     *  the trailing cell separator a table row's last cell leaves behind. */
    private fun clean(raw: String): String? = raw
        .split('\n')
        .map { it.trim().removeSuffix("|").trim() }
        .filter { it.isNotEmpty() }
        .joinToString("\n")
        .takeIf { it.isNotEmpty() }

    /**
     * Drops the headword echo that monolingual conversions lead with —
     * 「ねこ【猫】」 above the actual definition — since the app already
     * shows the headword and reading. Conservative, keyed on the entry's
     * own [term]/[reading] (verified: 0 false matches across 60k JMdict
     * entries; the echo line's pre-bracket text must equal the reading or
     * term, with the bracket carrying the canonical form — which can
     * differ from a variant entry's own term, hence reading-keyed):
     *  - `pre【…】` lines strip when normalized `pre` equals the reading
     *    or term (or, with no `pre`, when the bracketed form does);
     *  - a bare line equal to the reading/term strips ONLY when more
     *    lines follow (alone, it could be a legitimate kana gloss).
     * Returns null when nothing but the echo remained (an echo-only
     * glossary item carries no information).
     */
    fun stripHeadwordEcho(definition: String, term: String, reading: String): String? {
        val lines = definition.split('\n')
        val first = lines.first().trim()
        val termNorm = normalizeEcho(term)
        val readingNorm = normalizeEcho(reading)
        val bracket = ECHO_BRACKET.matchEntire(first)
        val isEcho = if (bracket != null) {
            val pre = normalizeEcho(bracket.groupValues[1])
            val inner = normalizeEcho(bracket.groupValues[2])
            if (pre.isEmpty()) inner == termNorm || inner == readingNorm
            else pre == readingNorm || pre == termNorm
        } else {
            lines.size > 1 && normalizeEcho(first).let { it == readingNorm || it == termNorm }
        }
        if (!isEcho) return definition
        return lines.drop(1).joinToString("\n").trim().takeIf { it.isNotEmpty() }
    }

    private val ECHO_BRACKET = Regex("""^([^【】]*)【([^【】]+)】$""")

    /** Reading comparison tolerant of the separators converters decorate
     *  readings with (okurigana dots た・べる, spacing) and of katakana
     *  vs hiragana. */
    private fun normalizeEcho(s: String): String =
        Deinflector.katakanaToHiragana(s.filterNot { it.isWhitespace() || it in "・･•‐‑" })
}
