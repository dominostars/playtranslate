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
 * Sibling-node junctions concatenate BARE at ingest — the stored flat
 * text is a long-standing contract (transport meaning strings, the flat
 * render tiers, anything diffing against previously stored rows), so
 * [parseGlossary]'s output stays byte-identical across imports. The cost
 * is that chip-style sibling spans, whose visual spacing lives entirely
 * in CSS, flatten fused ("nounsuruintransitiveno-adj"). The Anki
 * simplified tier alone opts into junction spacing via
 * [flattenRetainedGlossary] — a presentation-time re-flatten of the
 * retained JSON, never stored — see that function's doc for the rule and
 * its tradeoffs.
 *
 * Pure JVM (Gson streaming only) for unit-testability. Like [FreqData],
 * every parse ALWAYS consumes exactly its element — malformed input never
 * corrupts the caller's stream position inside a 100MB bank.
 */
internal object TermGlossary {

    /** Parses the glossary array the [reader] is positioned at.
     *  [spaceJunctions] is the card-path presentation mode (see
     *  [flattenRetainedGlossary]); ingest callers use the default so
     *  stored flat text stays byte-identical across imports. */
    fun parseGlossary(reader: JsonReader, spaceJunctions: Boolean = false): List<String> {
        val defs = mutableListOf<String>()
        reader.beginArray()
        while (reader.hasNext()) {
            when (reader.peek()) {
                JsonToken.STRING -> clean(reader.nextString())?.let { defs.add(it) }
                JsonToken.BEGIN_OBJECT -> parseItemObject(reader, spaceJunctions)?.let { defs.add(it) }
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
    private fun parseItemObject(reader: JsonReader, spaceJunctions: Boolean): String? {
        var type: String? = null
        val text = StringBuilder()
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "type" ->
                    if (reader.peek() == JsonToken.STRING) type = reader.nextString()
                    else reader.skipValue()
                "text" ->
                    if (reader.peek() == JsonToken.STRING) {
                        appendFlat(text, reader.nextString(), spaceJunctions)
                    } else {
                        reader.skipValue()
                    }
                "content" -> appendFlat(text, walkNode(reader, spaceJunctions), spaceJunctions)
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
    private fun walkNode(reader: JsonReader, spaceJunctions: Boolean): String = when (reader.peek()) {
        JsonToken.STRING -> reader.nextString()
        JsonToken.BEGIN_ARRAY -> buildString {
            reader.beginArray()
            while (reader.hasNext()) appendFlat(this, walkNode(reader, spaceJunctions), spaceJunctions)
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
                    "content" -> appendFlat(content, walkNode(reader, spaceJunctions), spaceJunctions)
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

    /** Appends [piece] to [sb]. In [spaceJunctions] mode (the card-path
     *  presentation flatten, see [flattenRetainedGlossary]) one space is
     *  inserted when the junction would fuse two ASCII alphanumeric runs;
     *  ingest mode appends bare, keeping stored flat text byte-identical.
     *  Every sibling-level concatenation in the flatten goes through
     *  here; junctions the composed strings create themselves ("\n…",
     *  "… | ") never fuse alnum against alnum. */
    private fun appendFlat(sb: StringBuilder, piece: String, spaceJunctions: Boolean) {
        if (piece.isEmpty()) return
        if (spaceJunctions && sb.isNotEmpty() &&
            sb.last().isAsciiAlnum() && piece.first().isAsciiAlnum()
        ) {
            sb.append(' ')
        }
        sb.append(piece)
    }

    private fun Char.isAsciiAlnum(): Boolean =
        this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

    /**
     * Re-flattens a RETAINED glossary-array JSON (a `term_sc` row, the
     * exact serialized form [parseGlossary] saw at ingest) to plain-text
     * lines for the Anki simplified tier — PRESENTATION ONLY, never
     * stored. Unlike the ingest flatten it spaces sibling junctions that
     * would fuse two ASCII alphanumeric runs: chip-style sibling spans
     * (Jitendex POS tags) keep their visual spacing in CSS, so the bare
     * concatenation the stored text carries reads
     * "nounsuruintransitiveno-adj". The rule is ASCII-only — inline spans
     * inside CJK prose must keep flowing unspaced — and fires only where
     * no whitespace already separates the runs. Known cost: a fragment
     * styled inside a single Latin word ("un<b>usual</b>") gains a
     * spurious space on the card.
     *
     * Null when the JSON doesn't parse or flattens to nothing; callers
     * keep the stored flat text.
     */
    fun flattenRetainedGlossary(json: String): List<String>? = try {
        JsonReader(java.io.StringReader(json)).use { parseGlossary(it, spaceJunctions = true) }
            .takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null
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
