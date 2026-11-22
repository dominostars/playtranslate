package com.playtranslate.ui

import com.playtranslate.model.ImportedSense
import com.playtranslate.model.ImportedSenseGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class DefinitionsDocumentTest {

    private val structuredGlossary =
        """[{"type":"structured-content","content":{"tag":"span","content":"styled cat"}}]"""

    private fun data(
        groups: List<ImportedSenseGroup> = emptyList(),
        senses: List<SenseDisplay> = emptyList(),
    ) = WordDefinitionData(
        word = "猫",
        reading = "ねこ",
        senses = senses,
        freqScore = 0,
        isCommon = true,
        importedGroups = groups,
    )

    private fun content(
        d: WordDefinitionData,
        structured: Map<Long, String> = emptyMap(),
        metaChips: List<DefinitionsDocument.MetaChip> = emptyList(),
        importedCuration: DefinitionsDocument.ImportedCuration? = null,
    ) = DefinitionsDocument.contentHtml(
        d, structured, localizePos = { it.joinToString("/") }, metaChips = metaChips,
        importedCuration = importedCuration,
    )

    @Test
    fun `imported groups render in unscoped sections with the dictionary scope per glossary`() {
        // Catches: the dictionary attribute left on the section, and the
        // glossary emitted without its scope wrapper.
        val html = content(
            data(
                groups = listOf(
                    ImportedSenseGroup(
                        "Jitendex", listOf(ImportedSense("cat", scRowid = 7L)), dictId = "abc",
                    ),
                ),
            ),
            structured = mapOf(7L to structuredGlossary),
        )
        assertTrue(html.contains("<section class=\"dict-group\">"))
        assertFalse(html.contains("<section class=\"dict-group\" data-dictionary"))
        val glossary = checkNotNull(YomitanContentHtml.glossaryHtml(structuredGlossary, "abc"))
        assertTrue(html.contains("<div class=\"gloss-scope\" data-dictionary=\"abc\">$glossary</div>"))
        assertTrue(html.contains("styled cat"))
    }

    @Test
    fun `senses without structured rows fall back to flat text`() {
        val html = content(
            data(
                groups = listOf(
                    ImportedSenseGroup(
                        "Dict",
                        listOf(ImportedSense("flat def", scRowid = null)),
                        dictId = "d",
                    ),
                ),
            ),
        )
        assertTrue(html.contains("flat def"))
    }

    @Test
    fun `flattened imported rows are skipped - the groups are those rows`() {
        val html = content(
            data(
                groups = listOf(
                    ImportedSenseGroup("Dict", listOf(ImportedSense("real")), dictId = "d"),
                ),
                senses = listOf(
                    SenseDisplay(listOf("Dict · n"), "real", emptyList(), imported = true),
                    SenseDisplay(listOf("noun"), "pack def", emptyList()),
                ),
            ),
        )
        assertEquals(1, Regex(">real<").findAll(html).count())
        assertTrue(html.contains("pack def"))
    }

    @Test
    fun `consecutive same-header senses emit the header once`() {
        val html = content(
            data(
                groups = listOf(
                    ImportedSenseGroup(
                        "Dict",
                        listOf(ImportedSense("one", pos = "n"), ImportedSense("two", pos = "n")),
                        dictId = "d",
                    ),
                ),
            ),
        )
        assertEquals(1, Regex("Dict · n").findAll(html).count())
    }

    @Test
    fun `without curation a flat imported group renders bare rows in an unscoped section`() {
        // Catches: wrapping senses in .imp-sense whether or not a curation was
        // passed, and a dictionary attribute on the section or on a flat row
        // (flat text is our rendering, not the dictionary's content, so a flat
        // sense carries no scope).
        val html = content(
            data(
                groups = listOf(
                    ImportedSenseGroup(
                        "Dict",
                        listOf(ImportedSense("one", pos = "n"), ImportedSense("two", pos = "n")),
                        dictId = "d",
                    ),
                ),
            ),
        )
        assertTrue(
            html.contains(
                "<section class=\"dict-group\"><div class=\"pos-h\">Dict · n</div>" +
                    "<div class=\"def-row\"><div class=\"def-text\">one</div></div>" +
                    "<div class=\"def-row\"><div class=\"def-text\">two</div></div></section>",
            ),
        )
    }

    // ── Imported-row curation (the Anki word sheet) ─────────────────────

    private fun curation(removed: Set<Int>, removable: Boolean = true, label: String = "Remove") =
        DefinitionsDocument.ImportedCuration(removed, removable, label)

    @Test
    fun `curated rows carry their original flat index across groups`() {
        // Catches: numbering by kept position within the group plus its base
        // (emits 0 and 2), and a counter reset per group (emits 1 twice, no 3).
        // Each group's FIRST sense is removed, so a kept position never equals
        // its original one.
        val html = content(
            data(
                groups = listOf(
                    ImportedSenseGroup("A", listOf(ImportedSense("s0"), ImportedSense("s1")), dictId = "a"),
                    ImportedSenseGroup("B", listOf(ImportedSense("s2"), ImportedSense("s3")), dictId = "b"),
                ),
            ),
            importedCuration = curation(removed = setOf(0, 2)),
        )
        assertTrue(html.contains("data-imp=\"1\""))
        assertTrue(html.contains("data-imp=\"3\""))
        assertFalse(html.contains("data-imp=\"0\""))
        assertFalse(html.contains("data-imp=\"2\""))
        assertFalse(html.contains(">s0<"))
        assertTrue(html.contains(">s1<"))
        assertFalse(html.contains(">s2<"))
        assertTrue(html.contains(">s3<"))
    }

    @Test
    fun `a card down to its last row keeps the wrappers but draws no remove button`() {
        // Catches: emitting the button whatever removable says.
        val html = content(
            data(
                groups = listOf(
                    ImportedSenseGroup("D", listOf(ImportedSense("only")), dictId = "d"),
                ),
            ),
            importedCuration = curation(removed = emptySet(), removable = false),
        )
        assertTrue(html.contains("<div class=\"imp-sense\" data-imp=\"0\">"))
        assertFalse(html.contains("imp-x"))
    }

    @Test
    fun `the remove button follows the body, outside it, with an escaped label`() {
        // Catches: the button inside .imp-body, and the label not escaped.
        val html = content(
            data(
                groups = listOf(
                    ImportedSenseGroup("Jitendex", listOf(ImportedSense("cat", scRowid = 7L)), dictId = "abc"),
                ),
            ),
            structured = mapOf(7L to structuredGlossary),
            importedCuration = curation(removed = emptySet(), label = "a<b"),
        )
        assertTrue(html.contains("styled cat"))
        assertTrue(html.contains("</div><button type=\"button\" class=\"imp-x\""))
        assertTrue(html.contains("aria-label=\"a&lt;b\""))
        // The glossary itself ends in </div>, so the adjacency above holds
        // either way: what places the button outside the body is that every
        // div opened after .imp-body's own, and .imp-body itself, closed first.
        val body = html.substringAfter("<div class=\"imp-body\">").substringBefore("<button")
        assertEquals(
            Regex("<div").findAll(body).count() + 1,
            Regex("</div>").findAll(body).count(),
        )
    }

    @Test
    fun `under curation the dictionary scope sits inside the body and the remove button outside it`() {
        // Catches: the button emitted inside the scope, and the scope wrapping
        // the whole .imp-sense (the body and the button inside it).
        val html = content(
            data(
                groups = listOf(
                    ImportedSenseGroup("Jitendex", listOf(ImportedSense("cat", scRowid = 7L)), dictId = "abc"),
                ),
            ),
            structured = mapOf(7L to structuredGlossary),
            importedCuration = curation(removed = emptySet(), label = "Remove"),
        )
        assertTrue(html.contains("<div class=\"imp-body\"><div class=\"gloss-scope\" data-dictionary=\"abc\">"))
        // The scope closes, then the body, then the button.
        assertTrue(html.contains("</div></div><button type=\"button\" class=\"imp-x\""))
        assertTrue(html.indexOf("class=\"imp-x\"") > html.indexOf("data-dictionary"))
        // A glossary may itself end in </div></div>, so the adjacency alone
        // cannot place the button: every div opened inside the scope, then the
        // scope and the body, close before it.
        val inScope = html.substringAfter("data-dictionary=\"abc\">").substringBefore("<button")
        assertEquals(
            Regex("<div").findAll(inScope).count() + 2,
            Regex("</div>").findAll(inScope).count(),
        )
    }

    @Test
    fun `the header stays outside the wrapper and collapses over kept senses`() {
        // Catches: the header inside the wrapper; the loop running over all
        // senses and emitting a header before it skips a removed one (a, b, a
        // again); and the run tracked over removed senses without emitting
        // their headers (no header at all when the run's first row goes).
        val alternating = data(
            groups = listOf(
                ImportedSenseGroup(
                    "Dict",
                    listOf(
                        ImportedSense("one", pos = "a"),
                        ImportedSense("two", pos = "b"),
                        ImportedSense("three", pos = "a"),
                    ),
                    dictId = "d",
                ),
            ),
        )
        // The middle row removed: the two kept rows share one header, and the
        // removed row's header is nowhere.
        val middleRemoved = content(alternating, importedCuration = curation(removed = setOf(1)))
        assertEquals(1, Regex("Dict · a").findAll(middleRemoved).count())
        assertFalse(middleRemoved.contains("Dict · b"))
        assertTrue(
            middleRemoved.indexOf("class=\"pos-h\"") < middleRemoved.indexOf("class=\"imp-sense\""),
        )
        // The run's first row removed: its header passes to the next kept one.
        val sameHeader = data(
            groups = listOf(
                ImportedSenseGroup(
                    "Dict",
                    listOf(
                        ImportedSense("one", pos = "n"),
                        ImportedSense("two", pos = "n"),
                        ImportedSense("three", pos = "n"),
                    ),
                    dictId = "d",
                ),
            ),
        )
        val firstRemoved = content(sameHeader, importedCuration = curation(removed = setOf(0)))
        assertEquals(1, Regex("Dict · n").findAll(firstRemoved).count())
    }

    @Test
    fun `a group whose every sense was removed emits no section`() {
        // Catches: dropping the emptied-group skip (an empty section for A).
        val html = content(
            data(
                groups = listOf(
                    ImportedSenseGroup("A", listOf(ImportedSense("a0")), dictId = "a"),
                    ImportedSenseGroup("B", listOf(ImportedSense("b0")), dictId = "b"),
                ),
            ),
            importedCuration = curation(removed = setOf(0)),
        )
        // Sections carry no dictionary attribute, so B's is told by its header.
        assertEquals(1, Regex("<section class=\"dict-group\">").findAll(html).count())
        assertTrue(html.contains("<section class=\"dict-group\"><div class=\"pos-h\">B</div>"))
    }

    @Test
    fun `the shell reports a tap by its data-act and spaces curated rows`() {
        // Catches: the action report dropped, the sibling rule deleted, and
        // the flat row's margin reset deleted.
        val shell = shell()
        assertTrue(shell.contains("closest('[data-act]')"))
        assertTrue(shell.contains("PTBridge.onAction(row.id, el.getAttribute('data-act'))"))
        assertTrue(shell.contains(".imp-sense + .imp-sense"))
        assertTrue(shell.contains(".imp-body > .def-row { margin: 0; }"))
        // A link in a row that is not inert is the page's own to follow:
        // the link check comes first and reports nothing.
        assertTrue(shell.indexOf("closest('a[href]')") < shell.indexOf("closest('[data-act]')"))
    }

    @Test
    fun `the shell takes a tap on a row's text after every action and link, and paints its mark in a layer of its own`() {
        // Catches: the word tap taken before a button or a link, the mark's
        // entry points or its report dropped, and a mark layer that takes
        // touches or room.
        val shell = shell()
        assertTrue(shell.contains("if (row.hasAttribute('data-words')) wordTap(e, row);"))
        assertTrue(shell.indexOf("closest('[data-act]')") < shell.indexOf("wordTap(e, row)"))
        assertTrue(shell.contains("PTBridge.onText(gen, row.id, run.id, lang, dict, text,"))
        assertTrue(shell.contains("window.ptMark = function (id, s, e, draw)"))
        assertTrue(shell.contains("window.ptUnmark = function ()"))
        assertTrue(shell.contains("window.PTBridge.onMark(mark.id,"))
        // A layout report measures the mark again and tells the host, one
        // that is not painted yet too: its lens is placed on that box.
        assertTrue(shell.contains("if (mark) placeMark(true);"))
        assertFalse(shell.contains("mark.draw) placeMark("))
        assertTrue(shell.contains("#pt-marks { position: absolute; left: 0; top: 0; width: 0; height: 0; pointer-events: none; }"))
        // The readings over a word are no part of a run, and a summary's tap is its own.
        assertTrue(shell.contains("if (tag === 'RT' || tag === 'RP') continue;"))
        assertTrue(shell.contains("t.closest('summary,button,.pos-h,.sp,.sn,.sm,.dict-t')"))
    }

    @Test
    fun `pack senses keep numbering and localized pos headers`() {
        val html = content(
            data(
                senses = listOf(
                    SenseDisplay(listOf("noun"), "a cat", emptyList()),
                    SenseDisplay(listOf("noun"), "a feline", emptyList()),
                ),
            ),
        )
        assertTrue(html.contains(">noun<"))
        assertTrue(html.contains(">1.<"))
        assertTrue(html.contains(">2.<"))
        assertEquals(1, Regex("class=\"pos-h\"").findAll(html).count())
    }

    @Test
    fun `group accent color tints its headers`() {
        val html = content(
            data(
                groups = listOf(
                    ImportedSenseGroup(
                        "Dict", listOf(ImportedSense("x")), accentColor = 0xFF112233.toInt(),
                        dictId = "d",
                    ),
                ),
            ),
        )
        assertTrue(html.contains("style=\"color:#112233\""))
    }

    @Test
    fun `structured gating helpers`() {
        val without = data(
            groups = listOf(ImportedSenseGroup("D", listOf(ImportedSense("x")), dictId = "d")),
        )
        val with = data(
            groups = listOf(
                ImportedSenseGroup("D", listOf(ImportedSense("x", scRowid = 3L)), dictId = "d"),
            ),
        )
        assertFalse(DefinitionsDocument.hasStructuredContent(without))
        assertTrue(DefinitionsDocument.hasStructuredContent(with))
        assertEquals(listOf(3L), DefinitionsDocument.structuredRowids(with))
    }

    @Test
    fun `shell carries csp and the scope-enforcing script, and its theme the tokens`() {
        val shell = DefinitionsDocument.documentShellHtml()
        assertTrue(shell.contains("Content-Security-Policy"))
        assertTrue(shell.contains("default-src 'none'"))
        assertTrue(shell.contains("img-src https://pt-media.internal"))
        // The theme arrives at bind, so one loaded shell fits any surface.
        val theme = DefinitionsDocument.themeJs(
            DefinitionsDocument.DocTheme(
                DefinitionsDocument.Tokens(
                    text = 0xFFEFEFEF.toInt(),
                    textMuted = 0xFFA0A0A0.toInt(),
                    textHint = 0xFF606060.toInt(),
                    accent = 0xFF00BCD4.toInt(),
                    panel = 0xFF242424.toInt(),
                    baseFontSizePx = 14.5f,
                ),
                divider = 0, band = 0,
            ),
            rtl = false,
        )
        assertTrue(theme, theme.contains("\"--text-color\":\"#EFEFEF\""))
        assertTrue(theme, theme.contains("\"--background-color\":\"#242424\""))
        assertTrue(theme, theme.contains("fontPx:14.5"))
        assertTrue(shell.contains("document.body.style.fontSize = t.fontPx + 'px'"))
        // The scope-escape sweep: every top-level rule other than the
        // wrapper is deleted post-parse.
        assertTrue(shell.contains("selectorText !== scope"))
        assertTrue(shell.contains("ptApplyDictCss"))
        // Dual-path scoping with a BEHAVIOR probe: CSS.supports lies about
        // nesting on Chromium ~105-119 (Thor field trace: 109 says true,
        // parses nothing), so the shell must (a) probe by parsing a real
        // nested rule and counting children, (b) verify the nested
        // product's inner rule count and fall through to the legacy path
        // when it's an empty husk, and (c) never consult the feature flag.
        assertFalse(shell.contains("CSS.supports"))
        assertTrue(shell.contains("#pt-nest-probe"))
        assertTrue(shell.contains("falling to legacy"))
        assertTrue(shell.contains("conditionText"))
        // The application mechanism must be <style> elements, NEVER
        // constructable stylesheets — Android WebView gained those far
        // later than desktop Chrome, and on AOSP WebViews they throw into
        // the swallow-catch, silently unstyling every dictionary.
        assertFalse(shell.contains("adoptedStyleSheets"))
        assertFalse(shell.contains("new CSSStyleSheet"))
        assertTrue(shell.contains("createElement('style')"))
        // Render-generation contract (stale-height race): ptSet stamps the
        // generation and every layout report echoes it, so the Kotlin side
        // can drop reports from a superseded row set, and an append or a
        // replace built for an older one does nothing. The Kotlin gate is
        // pinned in DefinitionDocumentTest.
        assertTrue(shell.contains("ptSet = function (html, g)"))
        assertTrue(shell.contains("window.PTBridge.onLayout(gen,"))
        assertEquals(2, Regex("if \\(g !== gen\\) return;").findAll(shell).count())
        // Height-metric contract (viewport-ratchet bug): the report must be
        // content-intrinsic (root rect), NEVER documentElement.scrollHeight
        // (= max(content, viewport), which can only ratchet up once the
        // host sizes the view from one oversized report), and must skip
        // zero-width layouts (content wrapped at every character).
        assertFalse(shell.contains("documentElement.scrollHeight"))
        assertTrue(shell.contains("var base = r.getBoundingClientRect();"))
        assertTrue(shell.contains("Math.ceil(base.height)"))
        assertTrue(shell.contains("clientWidth < 1"))
        assertTrue(shell.contains("#root { display: flow-root; }"))
        // The scope is per glossary, so consecutive structured senses' items
        // are no longer siblings and the scopes carry the gap between them.
        // Catches: the .gloss-scope sibling rule deleted.
        assertTrue(shell.contains(".gloss-scope + .gloss-scope { margin-top: .4em; }"))
    }

    @Test
    fun `cssHex renders opaque and translucent colors`() {
        assertEquals("#102030", DefinitionsDocument.cssHex(0xFF102030.toInt()))
        assertEquals("rgba(16,32,48,0.502)", DefinitionsDocument.cssHex(0x80102030.toInt()))
    }

    // ── Meta-chip colour contract ───────────────────────────────────────
    //
    // This document was a SECOND implementation of the meta row a native
    // view once drew, and the accent override is two
    // coupled decisions: the accent becomes the fill AND the label takes an
    // ink that reads on it. The port took only the first half, leaving
    // accent-tinted chips at the stylesheet's muted secondary colour —
    // 1.0:1 to 1.5:1 against their own fill. Both halves now arrive
    // together from freqChipColors and are pinned here.

    private val lensChipFill = 0xFF1C1F22.toInt()    // ptCard, the lens's chip fill
    private val sheetChipFill = 0xFF141719.toInt()   // ptSurface, the sheet's chip fill
    private val lightChipFill = 0xFFFFFFFF.toInt()   // ptCard in light theme
    private val lensMutedText = 0xFF9AA1A8.toInt()   // ptTextMuted
    private val ink = OnAccentInk(
        dark = 0xFF0B1412.toInt(),   // pt_dark_text_on_accent
        light = 0xFFFFFFFF.toInt(),  // pt_light_text_on_accent
    )

    /** Every swatch the per-dictionary accent picker offers. */
    private val accentPalette = mapOf(
        "Coral" to 0xFFF08A6D, "Amber" to 0xFFE4B24A, "Lime" to 0xFFAACF5B,
        "Mint" to 0xFF67D39A, "Teal" to 0xFF4DD0C2, "Aqua" to 0xFF00BCD4,
        "Sky" to 0xFF6BB6E8, "Steel" to 0xFF8AA4B6, "Violet" to 0xFFA496EC,
        "Orchid" to 0xFFD184D8, "Rose" to 0xFFEC7A9E,
    ).mapValues { it.value.toInt() }

    @Test
    fun `an accent-tinted frequency chip paints BOTH the fill and the ink`() {
        val colors = freqChipColors(0xFF8AA4B6.toInt(), lensChipFill, lensMutedText, ink)
        val html = content(
            data(),
            metaChips = listOf(DefinitionsDocument.MetaChip("JPDB: 1234", tint = colors)),
        )
        assertTrue(html.contains("style=\"background:#8AA4B6;color:#0B1412\""))
    }

    @Test
    fun `an untinted frequency chip emits no inline colours`() {
        val colors = freqChipColors(null, lensChipFill, lensMutedText, ink)
        // No override: the stylesheet's neutral treatment stands, and the
        // resolver's answer is the flat renderer's neutral pair.
        assertEquals(lensChipFill, colors.fill)
        assertEquals(lensMutedText, colors.text)
        val html = content(
            data(),
            metaChips = listOf(DefinitionsDocument.MetaChip("BCCWJ: 402")),
        )
        assertTrue(html.contains("<span class=\"meta-chip\">BCCWJ: 402</span>"))
        assertFalse(html.contains("style="))
    }

    @Test
    fun `every accent swatch is legible in BOTH themes`() {
        // The ink used to be the surface's own chip fill, which is
        // near-black in dark theme (fine) and near-WHITE in light theme —
        // white on Lime is 1.78:1. It is picked by measured contrast now,
        // so the same chip reads in either theme.
        for ((name, accent) in accentPalette) {
            for ((surface, fill) in listOf(
                "lens/dark" to lensChipFill,
                "sheet/dark" to sheetChipFill,
                "lens/light" to lightChipFill,
            )) {
                val colors = freqChipColors(accent, fill, lensMutedText, ink)
                assertEquals(accent, colors.fill)
                val ratio = contrastRatio(colors.fill, colors.text)
                assertTrue("$name on $surface is ${ratio.toInt()}:1", ratio >= 4.5)
            }
        }
    }

    @Test
    fun `the ink does not depend on the surface fill`() {
        // Same accent, three different surfaces -> the same ink. The old
        // contract returned a different (and in light theme unreadable) ink
        // per surface.
        val accent = 0xFFAACF5B.toInt() // Lime, the worst case for a white ink
        val inks = listOf(lensChipFill, sheetChipFill, lightChipFill)
            .map { freqChipColors(accent, it, lensMutedText, ink).text }
            .distinct()
        assertEquals(listOf(ink.dark), inks)
    }

    @Test
    fun `chip fills are concrete rgba, never color-mix`() {
        // A var() operand defers validation past parse time, so the
        // declaration wins the cascade and only fails at computed-value
        // time — where an engine without color-mix leaves background at its
        // INITIAL value (transparent) and the fallback declaration written
        // above it is never consulted. Chromium 109 (the Thor) has no
        // color-mix, so every meta chip lost its fill; the accent-tinted
        // ones survived only because their inline style is a plain colour.
        // The shell reads each fill from a variable the theme sets to a
        // concrete colour, so the stylesheet never holds an engine feature.
        // (The CSS comment names color-mix; what must not ship is the CALL.)
        assertFalse(shell().contains("color-mix("))
        assertFalse(theme().contains("color-mix("))
        assertTrue(theme().contains("\"--pt-chip\":\"rgba(236,239,241,0.102)\""))    // .meta-chip
        assertTrue(theme().contains("\"--pt-common\":\"rgba(0,188,212,0.161)\""))    // .meta-chip.common
    }

    @Test
    fun `colours survive a comma-decimal and a non-Latin-digit locale`() {
        // The default-locale formatter localizes BOTH conversions in the
        // rgba() form: '%.3f' takes a comma separator and '%d' can take
        // Arabic-Indic digits. Either produces CSS the parser rejects, the
        // declaration drops, and the chip loses its fill — the color-mix
        // failure again, through a different door and only on some devices.
        val original = Locale.getDefault()
        try {
            for (locale in listOf(Locale.GERMANY, Locale.FRANCE, Locale("ar", "EG"))) {
                Locale.setDefault(locale)
                assertEquals(
                    "in $locale",
                    "rgba(236,239,241,0.102)",
                    DefinitionsDocument.cssAlpha(0xFFECEFF1.toInt(), 0.10f),
                )
                assertEquals("in $locale", "#ECEFF1", DefinitionsDocument.cssHex(0xFFECEFF1.toInt()))
                assertTrue("in $locale", theme().contains("rgba(236,239,241,0.102)"))
            }
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `the page reports a held press as its own action, and not also a tap`() {
        // Catches: the hold report dropped, the tap not suppressed after it,
        // and a hold that outlives a finger that moved or lost the gesture.
        val shell = shell()
        assertTrue(shell.contains("closest('[data-hold]')"))
        assertTrue(shell.contains("el.getAttribute('data-act') + ':hold'"))
        assertTrue(shell.contains("if (held) {"))
        assertTrue(shell.contains("addEventListener('touchcancel', endHold"))
        assertTrue(shell.contains("addEventListener('touchmove'"))
    }

    @Test
    fun `a row is drawn pressed only for a press of its own`() {
        // Catches: the row lighting up behind its own button (an element is
        // :active while anything inside it is), and the mark being dropped
        // at the lift, where a quick tap's pressed look is still showing.
        val shell = shell()
        assertTrue(shell.contains(".cell[data-act]:active:not(.np), .row.cur { background: var(--pt-press); }"))
        assertTrue(shell.contains("t.closest('[data-act]') === row && (!link || row.hasAttribute('data-inert'))"))
        assertTrue(shell.contains("if (!own) { unpressed = row; row.classList.add('np'); }"))
        // Cleared where the next touch begins, and nowhere else.
        assertEquals(1, Regex("classList\\.remove\\('np'\\)").findAll(shell).count())
        assertTrue(shell.contains("document.addEventListener('touchstart', function (e) {\n    if (unpressed) { unpressed.classList.remove('np'); unpressed = null; }"))
    }

    @Test
    fun `cssAlpha is the color-mix-with-transparent equivalent`() {
        assertEquals("rgba(236,239,241,0.102)", DefinitionsDocument.cssAlpha(0xFFECEFF1.toInt(), 0.10f))
        assertEquals("rgba(0,188,212,0.161)", DefinitionsDocument.cssAlpha(0xFF00BCD4.toInt(), 0.16f))
        // Alpha on the input is irrelevant — only its RGB is used.
        assertEquals("rgba(236,239,241,0.102)", DefinitionsDocument.cssAlpha(0x40ECEFF1, 0.10f))
    }

    @Test
    fun `bold in a CJK language is the page's own stroke, never the engine's made-up weight`() {
        // The system's CJK font has one weight: an engine asked for bold
        // thickens the outline until a dense character closes up. Each
        // element the page draws bold (a headword, a bolded reading, a
        // sentence-sheet word) is the regular weight under a light stroke
        // in the three languages that font serves.
        val css = shell()
        val rule = Regex("""([^{}]*)\{ font-weight: 400; -webkit-text-stroke: \.02em; }""").find(css)
            ?: error("no drawn-weight rule")
        val selectors = rule.groupValues[1].substringAfterLast("*/").split(',').map { it.trim() }.toSet()
        val expected = listOf(".w", ".rd.b", ".sww").flatMap { el -> listOf("ja", "zh", "ko").map { "$el:lang($it)" } }.toSet()
        assertEquals(expected, selectors)
        // The elements still ask for bold where a real bold face exists.
        assertTrue(css.contains(".w { font-size: 1.636em; font-weight: 700;"))
    }

    // ── Two columns on a wide page (the results' Words card) ────────────

    @Test
    fun `the theme says whether the rows take two columns, and says it when they do not`() {
        // Catches: the flag left out of the call when false (a kept shell
        // would then keep the last surface's columns), and a theme that asks
        // sending false.
        assertTrue(theme(), theme().contains("cols:false"))
        val asking = theme(twoColumnsWhenWide = true)
        assertTrue(asking, asking.contains("cols:true"))
        assertFalse(asking, asking.contains("cols:false"))
    }

    @Test
    fun `ptTheme sets or clears the columns class on every call`() {
        // Catches: a class that is only ever added. A loaded shell is kept
        // and handed to another surface, which must not inherit the columns.
        val ptTheme = shell().substringAfter("window.ptTheme = function (t) {").substringBefore("\n  };")
        assertTrue(ptTheme, ptTheme.contains("document.documentElement.classList.toggle('cols', !!t.cols);"))
    }

    @Test
    fun `the two-column rule sits in the 560px media query alone and reaches only a page marked cols`() {
        // Catches: a rule that applies at any width, or to every surface's
        // rows, and the gap's grid-only name (what an engine at the floor
        // knows) dropped.
        val style = shell().substringAfter("<style>").substringBefore("</style>")
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        val open = "@media (min-width: 560px) {"
        assertEquals("one such block", 1, Regex(Regex.escape(open)).findAll(style).count())
        val start = style.indexOf(open)
        var depth = 1
        var end = start + open.length
        while (depth > 0) {
            when (style[end]) {
                '{' -> depth++
                '}' -> depth--
            }
            end++
        }
        val inside = Regex("""([^{}]+)\{([^{}]*)\}""").findAll(style.substring(start + open.length, end - 1))
            .associate { m ->
                m.groupValues[1].trim() to m.groupValues[2].split(';').map { it.trim() }.filter { it.isNotEmpty() }
            }
        assertTrue(
            "every selector is under .cols: ${inside.keys}",
            inside.keys.flatMap { it.split(',') }.all { it.trim().startsWith(".cols ") },
        )
        assertEquals(
            listOf(
                "display: grid", "grid-template-columns: minmax(0, 1fr) minmax(0, 1fr)",
                "grid-column-gap: 8px", "column-gap: 8px",
            ),
            inside[".cols #root"],
        )
        // The second row starts its column: no hairline. The action row
        // spans both columns.
        assertEquals(listOf("content: none"), inside[".cols #root > .row:nth-child(2):not(.more)::before"])
        assertEquals(listOf("grid-column: 1 / -1"), inside[".cols #root > .more"])
        // Outside the block nothing is a grid and nothing reads the class:
        // one column is what it always was.
        val outside = style.removeRange(start, end)
        assertFalse(outside.contains("grid"))
        assertFalse(outside.contains(".cols"))
    }

    private fun shell() = DefinitionsDocument.documentShellHtml()

    /** The `ptTheme` call a surface gives a loaded shell; the Words card's
     *  asks for [twoColumnsWhenWide]. */
    private fun theme(twoColumnsWhenWide: Boolean = false) = DefinitionsDocument.themeJs(
        DefinitionsDocument.DocTheme(
            DefinitionsDocument.Tokens(
                text = 0xFFECEFF1.toInt(),
                textMuted = 0xFFA0A0A0.toInt(),
                textHint = 0xFF606060.toInt(),
                accent = 0xFF00BCD4.toInt(),
                panel = 0xFF141719.toInt(),
                baseFontSizePx = 14f,
            ),
            divider = 0, band = 0,
            twoColumnsWhenWide = twoColumnsWhenWide,
        ),
        rtl = false,
    )
}
