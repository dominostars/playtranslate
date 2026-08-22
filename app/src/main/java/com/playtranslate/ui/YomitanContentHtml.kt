package com.playtranslate.ui

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Pure structured-content → HTML converter for the styled definition
 * surfaces ([YomitanDefinitionsView]). Input is one term entry's glossary
 * array JSON as retained at ingest (`term_sc`); output is markup for the
 * shell document's content swap.
 *
 * Safety model (mirrors Yomitan's render-side second whitelist): a closed
 * `when` over the format's 20 tags — anything else renders nothing — and a
 * closed map over the 32 schema style properties, so a hand-edited DB row
 * cannot smuggle an element, attribute, or CSS declaration the converter
 * doesn't emit itself. All text and attribute values are HTML-escaped;
 * style values additionally reject `;`/`{`/`}` so a value can't terminate
 * its declaration and inject properties outside the whitelist (the CSSOM
 * would reject such values too — Yomitan assigns per-property).
 *
 * Deliberate degradations, all graceful: `?`-internal cross-references
 * unwrap to their text (no in-app search surface to navigate), and an
 * image's collapse/appearance dance is reduced to the image itself with
 * declared sizing. External http(s) links are real anchors — in-app the
 * WebView's client routes the tap to the system browser instead of
 * navigating.
 */
internal object YomitanContentHtml {

    /** Origin the shell's CSP + the WebView's request interception allow;
     *  media URLs are `$MEDIA_ORIGIN/media/<dictId>/<zip-relative path>`. */
    const val MEDIA_ORIGIN = "https://pt-media.internal"

    /** Converts one glossary array's JSON to HTML; null when [glossaryJson]
     *  doesn't parse (caller falls back to the flat text). */
    fun glossaryHtml(
        glossaryJson: String,
        dictId: String,
        /** False for Anki cards (no media pipeline v1): img nodes emit
         *  nothing; every other node renders as usual. */
        includeImages: Boolean = true,
    ): String? {
        val root = try {
            JsonParser.parseString(glossaryJson)
        } catch (_: RuntimeException) {
            return null
        }
        if (!root.isJsonArray) return null
        val items = root.asJsonArray
            .mapNotNull { item ->
                itemHtml(item, dictId, includeImages)
                    .takeIf { h -> h.isNotBlank() }
                    // Card mode: an image nested in a container survives
                    // image-stripping as a TEXT-LESS wrapper — drop the
                    // item like the top-level image case so a wrapper-only
                    // glossary returns null and callers keep the flat
                    // fallback (Codex P2). In-app (includeImages) an
                    // image IS content, so no text gate there.
                    ?.takeIf { h -> includeImages || hasTextContent(h) }
            }
        if (items.isEmpty()) return null
        return items.joinToString("") { "<div class=\"gloss-item\">$it</div>" }
    }

    // ── Glossary items ──────────────────────────────────────────────────

    private fun itemHtml(item: JsonElement, dictId: String, includeImages: Boolean): String = when {
        item.isJsonPrimitive && item.asJsonPrimitive.isString -> multiline(item.asString)
        item.isJsonObject -> {
            val obj = item.asJsonObject
            when (str(obj, "type")) {
                "text" -> multiline(str(obj, "text").orEmpty())
                "image" -> if (includeImages) imageHtml(obj, dictId) else ""
                "structured-content" ->
                    obj.get("content")?.let { nodeHtml(it, dictId, includeImages) }.orEmpty()
                else -> ""
            }
        }
        else -> "" // deinflection redirect arrays, malformed items
    }

    // ── Structured-content nodes ────────────────────────────────────────

    private fun nodeHtml(node: JsonElement, dictId: String, includeImages: Boolean): String = when {
        node.isJsonPrimitive && node.asJsonPrimitive.isString -> multiline(node.asString)
        node.isJsonArray -> node.asJsonArray.joinToString("") { nodeHtml(it, dictId, includeImages) }
        node.isJsonObject -> elementHtml(node.asJsonObject, dictId, includeImages)
        else -> ""
    }

    /** The closed tag switch. Unknown tags render NOTHING (their content
     *  included) — matching Yomitan, where an unrecognized tag returns null
     *  and the node is dropped. */
    private fun elementHtml(obj: JsonObject, dictId: String, includeImages: Boolean): String {
        return when (val tag = str(obj, "tag")) {
            "br" -> "<br>"

            "ruby", "rt", "rp", "table", "thead", "tbody", "tfoot", "tr" -> {
                val html = container(tag, obj, dictId, includeImages, attrs(obj, style = false, title = false))
                if (tag == "table") "<div class=\"gloss-sc-table-container\">$html</div>" else html
            }

            "td", "th" -> {
                val extra = StringBuilder()
                int(obj, "colSpan")?.takeIf { it > 1 }?.let { extra.append(" colspan=\"$it\"") }
                int(obj, "rowSpan")?.takeIf { it > 1 }?.let { extra.append(" rowspan=\"$it\"") }
                container(tag, obj, dictId, includeImages, attrs(obj, style = true, title = false) + extra.toString())
            }

            "span", "div", "ol", "ul", "li", "details", "summary" -> {
                val extra = if (tag == "details" && obj.get("open")?.takeIf { it.isJsonPrimitive }
                        ?.asJsonPrimitive?.let { it.isBoolean && it.asBoolean } == true
                ) " open" else ""
                container(tag, obj, dictId, includeImages, attrs(obj, style = true, title = true) + extra)
            }

            "img" -> if (includeImages) imageHtml(obj, dictId) else ""

            "a" -> {
                val content = obj.get("content")?.let { nodeHtml(it, dictId, includeImages) }.orEmpty()
                val href = str(obj, "href").orEmpty()
                when {
                    // External links: real anchors. In-app the WebView's
                    // client hands the tap to the system browser; on Anki
                    // cards the reviewer's own link handling applies.
                    href.startsWith("http:") || href.startsWith("https:") ->
                        "<a class=\"gloss-link\" href=\"${esc(href)}\">$content</a>"
                    // Internal search links (Yomitan's ?query=…): the
                    // cross-reference text stands alone.
                    else -> content
                }
            }

            else -> if (tag == null) "" else "" // closed switch; drop
        }
    }

    private fun container(
        tag: String,
        obj: JsonObject,
        dictId: String,
        includeImages: Boolean,
        attributes: CharSequence,
    ): String {
        val content = obj.get("content")?.let { nodeHtml(it, dictId, includeImages) }.orEmpty()
        return "<$tag class=\"gloss-sc-$tag\"$attributes>$content</$tag>"
    }

    /** Shared attribute emission: `data` → data-sc-*, `lang`, and
     *  optionally `style`/`title` per the schema branch. */
    private fun attrs(obj: JsonObject, style: Boolean, title: Boolean): String {
        val sb = StringBuilder()
        obj.get("data")?.takeIf { it.isJsonObject }?.asJsonObject?.let { data ->
            for ((key, value) in data.entrySet()) {
                if (!DATA_KEY.matches(key)) continue // browser dataset would throw; drop
                if (!(value.isJsonPrimitive && value.asJsonPrimitive.isString)) continue
                sb.append(" data-sc-").append(camelToKebab(key))
                    .append("=\"").append(esc(value.asString)).append("\"")
            }
        }
        str(obj, "lang")?.takeIf { LANG.matches(it) }?.let { sb.append(" lang=\"").append(it).append("\"") }
        if (title) str(obj, "title")?.let { sb.append(" title=\"").append(esc(it)).append("\"") }
        if (style) {
            obj.get("style")?.takeIf { it.isJsonObject }?.let { styleAttr(it.asJsonObject) }
                ?.let { sb.append(" style=\"").append(esc(it)).append("\"") }
        }
        return sb.toString()
    }

    private fun imageHtml(obj: JsonObject, dictId: String): String {
        val path = str(obj, "path")?.takeIf { it.isNotEmpty() } ?: return ""
        val sb = StringBuilder("<img class=\"gloss-image\" src=\"")
        sb.append(MEDIA_ORIGIN).append("/media/").append(dictId).append("/")
            .append(encodePath(path)).append("\"")
        str(obj, "alt")?.let { sb.append(" alt=\"").append(esc(it)).append("\"") }
        str(obj, "title")?.let { sb.append(" title=\"").append(esc(it)).append("\"") }
        val style = StringBuilder()
        val units = str(obj, "sizeUnits").takeIf { it == "em" } ?: "px"
        val width = num(obj, "width")
        val height = num(obj, "height")
        if (width != null) {
            style.append("width:min(100%,").append(trim(width)).append(units).append(");")
            if (height != null && height > 0) {
                style.append("aspect-ratio:").append(trim(width)).append("/")
                    .append(trim(height)).append(";")
            }
        }
        if (obj.get("pixelated")?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive
                ?.let { it.isBoolean && it.asBoolean } == true
        ) {
            style.append("image-rendering:pixelated;")
        }
        str(obj, "verticalAlign")?.takeIf { it in VERTICAL_ALIGN }
            ?.let { style.append("vertical-align:").append(it).append(";") }
        if (style.isNotEmpty()) sb.append(" style=\"").append(esc(style.toString())).append("\"")
        sb.append(">")
        return sb.toString()
    }

    // ── Inline styles ───────────────────────────────────────────────────

    /** The 32 schema properties, jsonKey → CSS property. Nothing else
     *  passes — notably no position/display/width/transform, the layout
     *  containment the format deliberately excludes. */
    private val STYLE_PROPS = mapOf(
        "fontStyle" to "font-style",
        "fontWeight" to "font-weight",
        "fontSize" to "font-size",
        "color" to "color",
        "background" to "background",
        "backgroundColor" to "background-color",
        "textAlign" to "text-align",
        "textEmphasis" to "text-emphasis",
        "textShadow" to "text-shadow",
        "verticalAlign" to "vertical-align",
        "wordBreak" to "word-break",
        "whiteSpace" to "white-space",
        "listStyleType" to "list-style-type",
        "cursor" to "cursor",
        "textDecorationLine" to "text-decoration-line",
        "textDecorationStyle" to "text-decoration-style",
        "textDecorationColor" to "text-decoration-color",
        "borderColor" to "border-color",
        "borderStyle" to "border-style",
        "borderRadius" to "border-radius",
        "borderWidth" to "border-width",
        "clipPath" to "clip-path",
        "margin" to "margin",
        "marginTop" to "margin-top",
        "marginLeft" to "margin-left",
        "marginRight" to "margin-right",
        "marginBottom" to "margin-bottom",
        "padding" to "padding",
        "paddingTop" to "padding-top",
        "paddingLeft" to "padding-left",
        "paddingRight" to "padding-right",
        "paddingBottom" to "padding-bottom",
    )

    /** Numeric values are meaningful for the margin sides (schema:
     *  number|string, Yomitan suffixes em). */
    private val NUMERIC_EM = setOf(
        "marginTop", "marginLeft", "marginRight", "marginBottom",
    )

    private fun styleAttr(style: JsonObject): String? {
        val sb = StringBuilder()
        for ((key, css) in STYLE_PROPS) {
            val value = style.get(key) ?: continue
            val rendered = when {
                value.isJsonPrimitive && value.asJsonPrimitive.isString -> value.asString
                value.isJsonPrimitive && value.asJsonPrimitive.isNumber && key in NUMERIC_EM ->
                    trim(value.asDouble) + "em"
                // textDecorationLine may be an array of keywords.
                key == "textDecorationLine" && value.isJsonArray ->
                    value.asJsonArray
                        .filter { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                        .joinToString(" ") { it.asString }
                else -> null
            } ?: continue
            if (!safeCssValue(rendered)) continue
            sb.append(css).append(":").append(rendered).append(";")
        }
        return sb.toString().takeIf { it.isNotEmpty() }
    }

    /** Declaration-injection guard: a value carrying `;`/`{`/`}` could
     *  terminate its declaration and add properties outside the whitelist
     *  (Yomitan is immune because it assigns via the CSSOM per-property;
     *  string emission needs the explicit check). */
    private fun safeCssValue(v: String): Boolean =
        v.none { it == ';' || it == '{' || it == '}' } && v.length <= 512

    // ── Small shared pieces ─────────────────────────────────────────────

    private val TAG_PATTERN = Regex("<[^>]+>")

    /** Whether [html] carries any text once tags are stripped. */
    private fun hasTextContent(html: String): Boolean =
        html.replace(TAG_PATTERN, "").isNotBlank()

    private val DATA_KEY = Regex("^[A-Za-z][A-Za-z0-9]*$")
    private val LANG = Regex("^[A-Za-z][A-Za-z0-9-]{0,15}$")
    private val VERTICAL_ALIGN = setOf(
        "baseline", "sub", "super", "text-top", "text-bottom", "middle", "top", "bottom",
    )

    private fun camelToKebab(s: String): String =
        s.replace(Regex("([A-Z])")) { "-" + it.value.lowercase() }

    private fun str(obj: JsonObject, key: String): String? =
        obj.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun int(obj: JsonObject, key: String): Int? =
        obj.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt

    private fun num(obj: JsonObject, key: String): Double? =
        obj.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble

    private fun trim(d: Double): String =
        if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()

    /** Escaped text with newlines as line breaks (Yomitan's multiline text
     *  handling). */
    private fun multiline(s: String): String =
        esc(s).replace("\n", "<br>")

    private fun esc(s: String): String = htmlEscape(s)

    /** Percent-encodes a zip-relative media path, preserving `/` segment
     *  separators. Pure JVM (android.net.Uri is unavailable to unit tests). */
    fun encodePath(path: String): String = buildString {
        for (b in path.toByteArray(Charsets.UTF_8)) {
            val u = b.toInt() and 0xFF
            val c = u.toChar()
            when {
                u < 128 && c.isLetterOrDigit() -> append(c)
                c in "/-._~" -> append(c)
                // Locale.ROOT for the same reason the CSS colours pin it.
                else -> append('%').append(String.format(java.util.Locale.ROOT, "%02X", u))
            }
        }
    }
}
