package com.playtranslate.ui

/**
 * Kotlin-side dictionary-CSS scoper for Anki cards — the counterpart of the
 * page-side legacy scoper in [DefinitionsDocument], needed because card
 * HTML is generated with no browser engine available. Same contract:
 * per-selector prefixing (escape-proof by construction — every emitted
 * selector carries the scope), `@media` recursed one level, every other
 * at-rule dropped, the dictionary's own `&`-nested rules lost. Cards and
 * the in-app legacy path therefore degrade identically.
 *
 * The tokenizer is quote- and comment-aware (a `}` inside a string or
 * comment is content, not structure), so a stray brace can only ever
 * terminate a rule early — the text after it parses as new rules, each of
 * which gets the prefix like any other.
 */
internal object AnkiCardCss {

    /** The card markup's per-dictionary scope — matches the `.gl-sc`
     *  wrapper both card builders emit. */
    fun scopeFor(dictId: String): String =
        ".gl-sc[data-dictionary=\"$dictId\"]"

    /** One inline `<style>` block per dictionary with content, scoped.
     *  Empty string when nothing applies. */
    fun styleBlocks(dictIds: Collection<String>, dictStyles: Map<String, String>): String {
        val sb = StringBuilder()
        for (dictId in dictIds.distinct()) {
            val css = dictStyles[dictId]?.takeIf { it.isNotBlank() } ?: continue
            val scoped = neutralizeRawTextBreakouts(scoped(css, scopeFor(dictId)))
            // Belt behind the neutralizer: no payload may carry the
            // raw-text terminator into the HTML boundary, ever.
            if (RAW_TEXT_TERMINATOR.containsMatchIn(scoped)) continue
            if (scoped.isNotBlank()) {
                sb.append("<style>").append(scoped).append("</style>")
            }
        }
        return sb.toString()
    }

    private val RAW_TEXT_TERMINATOR = Regex("</style", RegexOption.IGNORE_CASE)

    /**
     * HTML raw-text breakout neutralization (Codex adversarial catch):
     * `<style>` content is RAW TEXT — the HTML parser ends it at the first
     * literal `</style` no matter what the CSS around it says, so a hostile
     * stylesheet carrying `</style><script>…` would escape into the card's
     * markup; selector scoping can't help because HTML parses before CSS.
     * Rewriting every `</` as `<\/` closes the class losslessly: inside CSS
     * strings `\/` is a valid escape meaning exactly `/` (the JSON
     * `<\/script>` trick), and outside strings a `</` was never valid CSS.
     * Comments — the other place `</` could legally sit — are stripped
     * before this point.
     */
    private fun neutralizeRawTextBreakouts(css: String): String =
        css.replace("</", "<\\/")

    /** Rewrites [css] so every rule's selectors are prefixed with [scope]. */
    fun scoped(css: String, scope: String): String {
        val out = StringBuilder()
        emitRules(stripComments(css), scope, out)
        return out.toString()
    }

    // ── Tokenizer ───────────────────────────────────────────────────────

    /** Removes /* */ comments, respecting strings (a comment opener inside
     *  quotes is content). */
    private fun stripComments(css: String): String {
        val out = StringBuilder(css.length)
        var i = 0
        var quote: Char? = null
        while (i < css.length) {
            val c = css[i]
            when {
                quote != null -> {
                    out.append(c)
                    if (c == '\\' && i + 1 < css.length) {
                        out.append(css[i + 1]); i++
                    } else if (c == quote) quote = null
                }
                c == '"' || c == '\'' -> { quote = c; out.append(c) }
                c == '/' && i + 1 < css.length && css[i + 1] == '*' -> {
                    val end = css.indexOf("*/", i + 2)
                    i = if (end < 0) css.length - 1 else end + 1
                }
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }

    /** Walks top-level rules of [css], emitting prefixed forms into [out]. */
    private fun emitRules(css: String, scope: String, out: StringBuilder) {
        var i = 0
        while (i < css.length) {
            // Skip whitespace and stray semicolons/braces between rules —
            // a stray '}' here is an escape attempt's tail; ignoring it
            // means the following rules still parse and still get scoped.
            while (i < css.length && (css[i].isWhitespace() || css[i] == ';' || css[i] == '}')) i++
            if (i >= css.length) break
            val braceOpen = indexOfTopLevel(css, i, '{')
            if (braceOpen == null) {
                // A brace-less at-statement (@import …;) or trailing junk:
                // skip past its ';' and keep walking — the rules AFTER it
                // must still parse (and still get scoped).
                val semi = indexOfTopLevel(css, i, ';') ?: break
                i = semi + 1
                continue
            }
            val prelude = css.substring(i, braceOpen).trim()
            val bodyEnd = matchBrace(css, braceOpen)
            val body = css.substring(braceOpen + 1, bodyEnd)
            when {
                prelude.startsWith("@media") -> {
                    val inner = StringBuilder()
                    emitRules(body, scope, inner)
                    if (inner.isNotBlank()) {
                        out.append(prelude).append("{").append(inner).append("}")
                    }
                }
                prelude.startsWith("@") -> Unit // @import/@font-face/@keyframes… dropped
                prelude.isNotEmpty() -> {
                    out.append(prefixSelectors(prelude, scope))
                        .append("{").append(filterDeclarations(body)).append("}")
                }
            }
            i = bodyEnd + 1
        }
    }

    /**
     * Declaration-level filter for card CSS (deliberately NOT an
     * allowlist — that's the native-subset-engine trap; layout mischief
     * inside the reviewer is the user's own dictionary choice). Exactly
     * two things drop:
     *  - fetch-capable values (`url(` / `image-set(` outside quotes, any
     *    property): a card is re-reviewed forever and syncs across
     *    devices, so a remote reference is a persistent silent beacon —
     *    the in-app WebView blocks this class via its interceptor; cards
     *    have no such shield;
     *  - `position:` — the one-word spoof lever that can pin content over
     *    the whole reviewer.
     */
    private fun filterDeclarations(body: String): String {
        val out = StringBuilder(body.length)
        var start = 0
        var i = 0
        var quote: Char? = null
        var depth = 0
        fun emit(end: Int) {
            val decl = body.substring(start, end)
            start = end + 1
            if (decl.isBlank()) return
            val prop = decl.substringBefore(':').trim().lowercase()
            val fetchy = FETCH_VALUE.containsMatchIn(stripQuoted(decl))
            if (!fetchy && prop != "position") out.append(decl).append(';')
        }
        while (i < body.length) {
            val c = body[i]
            when {
                quote != null -> {
                    if (c == '\\') i++
                    else if (c == quote) quote = null
                }
                c == '"' || c == '\'' -> quote = c
                c == '(' -> depth++
                c == ')' -> if (depth > 0) depth--
                c == ';' && depth == 0 -> emit(i)
            }
            i++
        }
        if (start < body.length) emit(body.length)
        return out.toString()
    }

    private val FETCH_VALUE = Regex("(?:url|image-set)\\s*\\(", RegexOption.IGNORE_CASE)

    /** [decl] with quoted spans blanked, so `content: "url(x)"` (inert
     *  text) doesn't trip the fetch check. */
    private fun stripQuoted(decl: String): String {
        val out = StringBuilder(decl.length)
        var quote: Char? = null
        var i = 0
        while (i < decl.length) {
            val c = decl[i]
            when {
                quote != null -> {
                    if (c == '\\') i++
                    else if (c == quote) quote = null
                }
                c == '"' || c == '\'' -> quote = c
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }

    /** First [target] at paren/bracket/quote depth zero from [from], or null. */
    private fun indexOfTopLevel(css: String, from: Int, target: Char): Int? {
        var i = from
        var quote: Char? = null
        var depth = 0
        while (i < css.length) {
            val c = css[i]
            when {
                quote != null -> {
                    if (c == '\\') i++
                    else if (c == quote) quote = null
                }
                c == '"' || c == '\'' -> quote = c
                c == '(' || c == '[' -> depth++
                c == ')' || c == ']' -> if (depth > 0) depth--
                c == target && depth == 0 -> return i
                // A ';' before any '{' = an at-statement like @import; the
                // caller's skip loop swallows it next pass.
                c == ';' && target == '{' && depth == 0 -> return null
            }
            i++
        }
        return null
    }

    /** Index of the '}' matching the '{' at [open] (string-aware); the end
     *  of input when unbalanced. */
    private fun matchBrace(css: String, open: Int): Int {
        var i = open + 1
        var depth = 1
        var quote: Char? = null
        while (i < css.length) {
            val c = css[i]
            when {
                quote != null -> {
                    if (c == '\\') i++
                    else if (c == quote) quote = null
                }
                c == '"' || c == '\'' -> quote = c
                c == '{' -> depth++
                c == '}' -> { depth--; if (depth == 0) return i }
            }
            i++
        }
        return css.length
    }

    /** `a, b:hover > c` → `SCOPE a, SCOPE b:hover > c` (commas split at
     *  paren/bracket depth zero — `:is(a, b)` stays intact). */
    private fun prefixSelectors(prelude: String, scope: String): String {
        val parts = mutableListOf<String>()
        var start = 0
        var i = 0
        var quote: Char? = null
        var depth = 0
        while (i < prelude.length) {
            val c = prelude[i]
            when {
                quote != null -> {
                    if (c == '\\') i++
                    else if (c == quote) quote = null
                }
                c == '"' || c == '\'' -> quote = c
                c == '(' || c == '[' -> depth++
                c == ')' || c == ']' -> if (depth > 0) depth--
                c == ',' && depth == 0 -> {
                    parts.add(prelude.substring(start, i)); start = i + 1
                }
            }
            i++
        }
        parts.add(prelude.substring(start))
        return parts
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(", ") { "$scope $it" }
    }
}
