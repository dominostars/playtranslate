package com.playtranslate.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnkiCardCssTest {

    private val scope = ".gl-sc[data-dictionary=\"d1\"]"

    @Test
    fun `plain rules get the prefix`() {
        // Kept declarations re-emit through the filter (each closed with
        // ';', blanks dropped) — content-identical, not byte-identical.
        assertEquals(
            "$scope ul{ list-style:none;}",
            AnkiCardCss.scoped("ul { list-style:none; }", scope),
        )
    }

    @Test
    fun `comma selectors prefix each part but not inside parens`() {
        val out = AnkiCardCss.scoped(
            "th, td:is(.a, .b) { border: 1px }", scope,
        )
        assertTrue(out.contains("$scope th, $scope td:is(.a, .b)"))
    }

    @Test
    fun `media blocks recurse and keep their condition`() {
        val out = AnkiCardCss.scoped(
            "@media (prefers-color-scheme: dark) { div[data-sc-x] { color: gold } }", scope,
        )
        assertTrue(out.startsWith("@media (prefers-color-scheme: dark){"))
        assertTrue(out.contains("$scope div[data-sc-x]"))
    }

    @Test
    fun `other at-rules drop`() {
        val out = AnkiCardCss.scoped(
            "@import url(evil.css); @font-face { src: url(x) } span { color: red }", scope,
        )
        assertFalse(out.contains("@import"))
        assertFalse(out.contains("font-face"))
        assertTrue(out.contains("$scope span"))
    }

    @Test
    fun `stray brace escape attempt cannot shed the scope`() {
        val out = AnkiCardCss.scoped(
            "} body { background: red } div { border: 1px solid red }", scope,
        )
        // Every rule that survives carries the prefix; nothing global.
        assertTrue(out.contains("$scope body"))
        assertTrue(out.contains("$scope div"))
        assertFalse(out.replace("$scope body", "").contains(Regex("(^|})\\s*body")))
    }

    @Test
    fun `braces inside strings are content, not structure`() {
        val out = AnkiCardCss.scoped(
            "span::before { content: \"}\"; color: green } em { color: blue }", scope,
        )
        assertTrue(out.contains("content: \"}\""))
        assertTrue(out.contains("$scope em"))
    }

    @Test
    fun `comments are stripped even around braces`() {
        val out = AnkiCardCss.scoped(
            "/* } comment */ span { /* { */ color: red }", scope,
        )
        assertTrue(out.contains("$scope span"))
        assertFalse(out.contains("comment"))
    }

    @Test
    fun `jitendex-shaped rules survive`() {
        val css = """
            span[data-sc-class="tag"] { border-radius: 0.3em; font-size: 0.8em; }
            div[data-sc-class="extra-box"] {
                border-radius: 0.4rem;
                border-style: none none none solid;
            }
            div[data-sc-content="example-sentence"] {
                border-color: var(--text-color, #333);
                background-color: color-mix(in srgb, var(--text-color, #333) 5%, transparent);
            }
        """.trimIndent()
        val out = AnkiCardCss.scoped(css, scope)
        assertTrue(out.contains("$scope span[data-sc-class=\"tag\"]"))
        assertTrue(out.contains("$scope div[data-sc-class=\"extra-box\"]"))
        assertTrue(out.contains("border-style: none none none solid"))
        assertTrue(out.contains("color-mix"))
    }

    @Test
    fun `styleBlocks emits one scoped style element per dictionary with css`() {
        val html = AnkiCardCss.styleBlocks(
            listOf("d1", "d2", "d1"),
            mapOf("d1" to "span { color: red }", "d2" to "  "),
        )
        assertEquals(1, Regex("<style>").findAll(html).count())
        assertTrue(html.contains(".gl-sc[data-dictionary=\"d1\"] span"))
    }

    // ── Declaration filter: beacons + position (Codex round 6) ─────────

    @Test
    fun `url values drop from any property, gradients survive`() {
        val out = AnkiCardCss.scoped(
            "div { background: url(https://evil.example/beacon.png); color: red; " +
                "border-image: URL( https://evil.example/b ) 1; " +
                "background-image: linear-gradient(red, blue); }",
            scope,
        )
        assertFalse(out.contains("evil.example"))
        assertFalse(out.contains("url", ignoreCase = true))
        assertTrue(out.contains("color: red"))
        assertTrue(out.contains("linear-gradient(red, blue)"))
    }

    @Test
    fun `image-set values drop too`() {
        val out = AnkiCardCss.scoped(
            "div { background: -webkit-image-set(\"a.png\" 1x); color: green }", scope,
        )
        assertFalse(out.contains("image-set"))
        assertTrue(out.contains("color: green"))
    }

    @Test
    fun `url as inert quoted text in content is kept`() {
        val out = AnkiCardCss.scoped(
            "span::before { content: \"see url(docs)\"; color: teal }", scope,
        )
        assertTrue(out.contains("content: \"see url(docs)\""))
        assertTrue(out.contains("color: teal"))
    }

    @Test
    fun `position drops, other layout mischief deliberately passes`() {
        val out = AnkiCardCss.scoped(
            "div { position: fixed; z-index: 9999; transform: scale(3); top: 0 }", scope,
        )
        assertFalse(out.contains("position"))
        // Non-airtight by explicit decision (Gilad, 2026-08-13): a
        // dictionary making its own cards weird is the user's choice.
        assertTrue(out.contains("z-index: 9999"))
        assertTrue(out.contains("transform: scale(3)"))
    }

    // ── HTML raw-text breakout (Codex adversarial catch) ────────────────

    @Test
    fun `a style terminator inside dictionary css cannot escape the block`() {
        val html = AnkiCardCss.styleBlocks(
            listOf("d1"),
            mapOf(
                "d1" to "span::before { content: \"</style><script>alert(1)</script>\"; }" +
                    " em { color: blue }",
            ),
        )
        // The wrapper's own closer is the ONLY terminator sequence in the
        // output, and it is at the very end — everything from the payload
        // sits INSIDE the raw-text element, where markup text is inert.
        assertEquals(1, Regex("</style", RegexOption.IGNORE_CASE).findAll(html).count())
        assertTrue(html.endsWith("</style>"))
        // The neutralized string escape is CSS-equivalent (\/ means /).
        assertTrue(html.contains("content: \"<\\/style>"))
        assertTrue(html.contains(".gl-sc[data-dictionary=\"d1\"] em"))
    }

    @Test
    fun `case variants of the terminator are neutralized too`() {
        val html = AnkiCardCss.styleBlocks(
            listOf("d1"),
            mapOf("d1" to "span::after { content: \"</StYlE><b>x</b>\"; }"),
        )
        // The neutralizer removes every `</`, so no case variant of the
        // terminator can survive anywhere but the wrapper's own closer.
        assertEquals(1, Regex("</style", RegexOption.IGNORE_CASE).findAll(html).count())
        assertTrue(html.endsWith("</style>"))
    }

    @Test
    fun `terminator smuggled outside a string cannot end the block early`() {
        val html = AnkiCardCss.styleBlocks(
            listOf("d1"),
            mapOf("d1" to "</style><img src=x onerror=alert(1)> span { color: red }"),
        )
        // Whatever garbage the payload carried stays INSIDE the raw-text
        // element (inert there); the block closes exactly once, at the end.
        assertEquals(1, Regex("</style", RegexOption.IGNORE_CASE).findAll(html).count())
        assertTrue(html.endsWith("</style>"))
    }
}
