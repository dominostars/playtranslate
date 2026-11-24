package com.playtranslate.ui

import android.app.Activity
import android.os.Bundle
import com.playtranslate.R
import com.playtranslate.language.InflectedForm
import com.playtranslate.model.ImportedSense
import com.playtranslate.model.ImportedSenseGroup
import com.playtranslate.model.ReadingRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * [DefRowHtml]: the markup of a word-list row. Pins what a row is to the
 * page (its id, the actions its elements fire, that its links are inert),
 * the headword rules it shares with the native row it replaced (one reading
 * inline, several below with the occurrence bold, a kana-only word carrying
 * its own accent, the conjugation line capped), the hidden word's stub, and
 * the body's order with the preview clamp on structured imports only. And
 * the two rows of a word's own page: a sense (its number column, eyebrow,
 * note and examples) and the imported block, which is the dictionaries' own
 * markup when there is structured content and page senses when there is none.
 */
@RunWith(RobolectricTestRunner::class)
class DefRowHtmlTest {

    class Host : Activity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_PlayTranslate)
            super.onCreate(savedInstanceState)
        }
    }

    private lateinit var ctx: Activity

    @Before fun setUp() {
        ctx = Robolectric.buildActivity(Host::class.java).setup().get()
    }

    private fun data(
        word: String = "食べる",
        reading: String? = "たべる",
        senses: List<SenseDisplay> = listOf(SenseDisplay(listOf("verb"), "to eat", emptyList())),
    ) = WordDefinitionData(word = word, reading = reading, senses = senses, freqScore = 0, isCommon = false)

    private fun html(
        data: WordDefinitionData = data(),
        opens: Boolean = true,
        speak: DefRow.Speak? = DefRow.Speak.IDLE,
        trailing: DefRow.Trailing? = DefRow.Trailing.HIDE,
        structured: Map<Long, String> = emptyMap(),
        full: Boolean = false,
        trailingHolds: Boolean = false,
        label: String? = null,
        emptyText: String? = null,
        shut: Set<String> = emptySet(),
        explain: DefRow.Explain? = null,
    ): String = DefRowHtml.html(
        ctx,
        DefRow.Word("id", data, opens, speak, trailing, trailingHolds, full, label, emptyText, shut, explain),
        "k7", structured, "ja",
    )

    private fun String.count(part: String): Int = split(part).size - 1

    @Test fun `a row is one element with its DOM id, a tap target for each action, and inert links`() {
        val h = html()
        assertTrue(h, h.startsWith("<div class=\"row cell\" id=\"k7\" data-row data-inert data-act=\"open\">"))
        assertEquals(1, h.count("data-act=\"speak\""))
        assertEquals(1, h.count("data-act=\"trailing\""))
        assertTrue(h, h.contains("aria-label=\"${ctx.getString(R.string.hidden_word_hide_content_description)}\""))
        assertTrue("the chevron draws but is no target", h.contains("<span class=\"act chev\">"))
        assertTrue(h, h.endsWith("</div>"))
    }

    @Test fun `a row that does not open is no target and has no chevron, and a missing button is not drawn`() {
        val h = html(opens = false, speak = null, trailing = null)
        assertFalse(h, h.contains("data-act="))
        assertFalse(h, h.contains("chev"))
    }

    @Test fun `the trailing button names what it does`() {
        assertTrue(html(trailing = DefRow.Trailing.ANKI).contains("aria-label=\"${ctx.getString(R.string.cd_add_to_anki)}\""))
    }

    @Test fun `a speaking row draws the spinner in the speak button`() {
        val h = html(speak = DefRow.Speak.LOADING)
        assertTrue(h, h.contains("data-act=\"speak\"><span class=\"spin\"></span></button>"))
    }

    @Test fun `one reading sits beside the word, in the source language`() {
        val h = html()
        assertTrue(h, h.contains("<div class=\"title\" lang=\"ja\"><span class=\"w\">食べる</span><span class=\"rd\">たべる</span></div>"))
        assertFalse(h, h.contains("class=\"rds\""))
    }

    @Test fun `several readings go below the title, the occurrence bold`() {
        val h = html(
            data().copy(
                readingRows = listOf(
                    ReadingRow("今日", "きょう", emptyList(), true),
                    ReadingRow("今日", "こんにち", emptyList(), false),
                ),
            ),
        )
        assertTrue(h, h.contains("</span></div>"))
        assertTrue(h, h.contains("<div class=\"rds\" lang=\"ja\"><span class=\"rd b\">きょう</span><span class=\"rd\">こんにち</span></div>"))
        assertFalse("no inline reading beside the title", h.contains("<span class=\"w\">食べる</span><span class=\"rd"))
    }

    @Test fun `a kana-only word carries its accent on the title and repeats no reading`() {
        val h = html(data(word = "ねこ", reading = null).copy(pitch = listOf(1)))
        assertTrue(h, h.contains("<span class=\"w\"><span class=\"pa\">"))
        assertFalse(h, h.contains("class=\"rd"))
    }

    @Test fun `the conjugation line is capped, the rest counted`() {
        val forms = (1..MAX_INFLECTION_LINES + 2).map { InflectedForm("食べ$it", emptyList()) }
        val h = html(data().copy(inflectedForms = forms))
        val infl = h.substringAfter("<div class=\"infl\">").substringBefore("</div>")
        assertEquals(MAX_INFLECTION_LINES + 1, infl.split("<br>").size)
        assertTrue(infl, infl.endsWith(ctx.getString(R.string.inflection_more, 2)))
    }

    // ── The head's layout ────────────────────────────────────────────────

    @Test fun `the head's buttons are one group after the title, and the chevron follows it`() {
        val h = html(explain = DefRow.Explain())
        assertTrue("a row that opens keeps room for its chevron", h.contains("<div class=\"head op\"><div class=\"title\""))
        val group = h.substringAfter("<div class=\"acts\">").substringBefore("</div>")
        val order = listOf("data-act=\"explain\"", "data-act=\"speak\"", "data-act=\"trailing\"").map { group.indexOf(it) }
        assertTrue("explain, speak, then the second button: $group", order.all { it >= 0 } && order == order.sorted())
        assertEquals(1, h.count("class=\"acts\""))
        assertTrue(
            "the chevron stands outside the group, after it",
            h.substringAfter("<div class=\"acts\">").contains("</button></div><span class=\"act chev\">"),
        )
        assertTrue("the title comes first", h.indexOf("class=\"title\"") < h.indexOf("class=\"acts\""))
    }

    @Test fun `a row with no button has no group, and one that does not open keeps no room for a chevron`() {
        val bare = html(opens = false, speak = null, trailing = null)
        assertFalse(bare, bare.contains("class=\"acts\""))
        assertTrue(bare, bare.contains("<div class=\"head\"><div class=\"title\""))
        val opening = html(speak = null, trailing = null)
        assertFalse("still no group", opening.contains("class=\"acts\""))
        assertTrue(opening, opening.contains("<div class=\"head op\">"))
    }

    // ── The explain button and its answer ────────────────────────────────

    private val answer = DefRow.Answer("In this sentence", "It is the past of to eat.")

    @Test fun `a row with no explain has neither the button nor the block`() {
        val h = html()
        for (gone in listOf("explain", "class=\"xp")) assertFalse(gone, h.contains(gone))
    }

    @Test fun `with nothing asked the row has the button alone, named for a screen reader`() {
        val h = html(explain = DefRow.Explain())
        assertTrue(
            h,
            h.contains(
                "<button class=\"act explain\" data-act=\"explain\" aria-label=\"" +
                    ctx.getString(R.string.cd_word_explain) + "\"><svg class=\"ic\"",
            ),
        )
        assertFalse("not lit", h.contains("act explain on"))
        assertFalse("no answer, no block", h.contains("class=\"xp"))
        assertEquals("only Speak could spin, and it does not", 0, h.count("class=\"spin\""))
    }

    @Test fun `while the question is out the button is a spinner and nothing else is drawn`() {
        val h = html(explain = DefRow.Explain(busy = true))
        assertTrue(h, h.contains("data-act=\"explain\"") && h.contains("\"><span class=\"spin\"></span></button><button class=\"act speak\""))
        assertFalse("the row does not move until there is something to read", h.contains("class=\"xp"))
        assertFalse(h, h.contains("act explain on"))
    }

    @Test fun `an answer lights the button and stands between the head and the definitions, under its title and X`() {
        val h = html(
            data().copy(inflectedForms = listOf(InflectedForm("食べた", emptyList()))),
            explain = DefRow.Explain(answer = answer),
        )
        assertTrue("lit", h.contains("<button class=\"act explain on\" data-act=\"explain\""))
        assertTrue(
            h,
            h.contains(
                "<div class=\"xp\"><div class=\"xp-h\"><span class=\"xp-t\">In this sentence</span>" +
                    "<button type=\"button\" class=\"xp-x\" data-act=\"explain:close\" aria-label=\"" +
                    ctx.getString(R.string.cd_ask_close) + "\">",
            ),
        )
        assertTrue(h, h.contains("<div class=\"xp-a\">It is the past of to eat.</div></div>"))
        assertFalse("no footer was given", h.contains("class=\"xp-f\""))
        assertFalse("nor Try again", h.contains("explain:retry"))
        val at = listOf("class=\"infl\"", "class=\"xp\"", "class=\"body\"").map { h.indexOf(it) }
        assertTrue("after the conjugation line, before the definitions: $at", at.all { it >= 0 } && at == at.sorted())
    }

    @Test fun `an answer's footer is drawn under it, and its text is the page's text, never markup`() {
        val h = html(
            explain = DefRow.Explain(answer = DefRow.Answer("<b>T</b>", "a < b & \"c\"", footer = "Answered by <Gemini>")),
        )
        assertTrue(h, h.contains("<span class=\"xp-t\">&lt;b&gt;T&lt;/b&gt;</span>"))
        assertTrue(h, h.contains("<div class=\"xp-a\">a &lt; b &amp; &quot;c&quot;</div>"))
        assertTrue(h, h.contains("<div class=\"xp-f\">Answered by &lt;Gemini&gt;</div></div>"))
    }

    @Test fun `a failure is muted and offers Try again, an answer neither`() {
        val failed = html(
            explain = DefRow.Explain(answer = DefRow.Answer("In this sentence", "Couldn't get an answer.", muted = true, retry = true)),
        )
        assertTrue(failed, failed.contains("<div class=\"xp-a mu\">"))
        assertTrue(
            failed,
            failed.contains(
                "<button type=\"button\" class=\"xp-r\" data-act=\"explain:retry\">" +
                    ctx.getString(R.string.ask_retry) + "</button></div>",
            ),
        )
        assertTrue("its X is there too", failed.contains("data-act=\"explain:close\""))
        // Out of scope: the app's word, with nothing to try again.
        val refused = html(explain = DefRow.Explain(answer = DefRow.Answer("T", "Only answers…", muted = true)))
        assertTrue(refused.contains("class=\"xp-a mu\""))
        assertFalse(refused.contains("explain:retry"))
    }

    @Test fun `a hidden word's stub keeps the button's place and draws neither it nor an answer`() {
        val h = html(trailing = DefRow.Trailing.SHOW, explain = DefRow.Explain(answer = answer))
        assertTrue(
            "the explain column, then the speak column, keep their places",
            h.contains("<div class=\"acts\"><span class=\"act explain ghost\"></span><span class=\"act speak ghost\"></span>"),
        )
        assertFalse(h, h.contains("data-act=\"explain"))
        assertFalse(h, h.contains("class=\"xp\""))
    }

    @Test fun `a hidden word is a stub, the word and the eye that shows it`() {
        val h = html(data().copy(pitch = listOf(1), inflectedForms = listOf(InflectedForm("食べた", emptyList()))), trailing = DefRow.Trailing.SHOW)
        assertTrue(h, h.startsWith("<div class=\"row cell stub\" id=\"k7\""))
        assertTrue(h, h.contains("<span class=\"w\">食べる</span></div>"))
        assertTrue("the speak column keeps its place, untappable", h.contains("<span class=\"act speak ghost\"></span>"))
        assertFalse(h, h.contains("data-act=\"speak\""))
        assertTrue(h, h.contains("aria-label=\"${ctx.getString(R.string.hidden_word_show_content_description)}\""))
        assertTrue("the stub still opens its word", h.contains("data-act=\"open\"") && h.contains("chev"))
        for (gone in listOf("class=\"rd", "class=\"infl\"", "class=\"body\"")) assertFalse(gone, h.contains(gone))
    }

    @Test fun `the body is the chips, then the dictionaries, the pack's numbered senses last while its row is at the bottom`() {
        val imported = listOf(ImportedSenseGroup("Jitendex", listOf(ImportedSense("to eat (jitendex)")), dictId = "jt"))
        val h = html(data().copy(isCommon = true, importedGroups = imported))
        val body = h.substringAfter("<div class=\"body\">")
        val chips = body.indexOf("class=\"meta-row\"")
        val imp = body.indexOf("<section class=\"dict-group\">")
        val pack = body.indexOf("<div class=\"def-num\">1.</div>")
        assertTrue(body, chips in 0 until imp && imp < pack)
        assertTrue(body, body.contains("to eat (jitendex)"))
    }

    @Test fun `the pack's senses stand where the user's order puts the built-in dictionary`() {
        fun group(name: String, id: String, below: Boolean) = ImportedSenseGroup(
            name, listOf(ImportedSense("def of $name")), dictId = id, belowBuiltIn = below,
        )
        fun order(vararg groups: ImportedSenseGroup): List<String> {
            val body = html(data().copy(importedGroups = groups.toList())).substringAfter("<div class=\"body\">")
            val marks = groups.map { "def of ${it.source}" to it.source } +
                ("<div class=\"def-num\">1.</div>" to "built-in")
            return marks.sortedBy { (mark, _) -> body.indexOf(mark).also { assertTrue(mark, it >= 0) } }.map { it.second }
        }
        assertEquals(
            listOf("A", "B", "built-in"),
            order(group("A", "a", below = false), group("B", "b", below = false)),
        )
        assertEquals(
            listOf("A", "built-in", "B"),
            order(group("A", "a", below = false), group("B", "b", below = true)),
        )
        assertEquals(
            listOf("built-in", "A", "B"),
            order(group("A", "a", below = true), group("B", "b", below = true)),
        )
    }

    @Test fun `between two dictionaries the pack's block keeps its title, and each title names its own dictionary`() {
        val imported = listOf(
            ImportedSenseGroup("A", listOf(ImportedSense("def of A")), dictId = "a"),
            ImportedSenseGroup("B", listOf(ImportedSense("def of B")), dictId = "b", belowBuiltIn = true),
        )
        val h = html(data().copy(importedGroups = imported))
        val first = h.indexOf("data-act=\"dict:0\"")
        val own = h.indexOf("data-act=\"dict:own\"")
        val second = h.indexOf("data-act=\"dict:1\"")
        assertTrue(h, first in 0 until own && own < second)
        // The titles' numbers count the imported dictionaries alone, in the
        // list's order, whatever stands between them: what a tap resolves by.
        assertTrue(h, h.indexOf("def of A") in first until own)
        assertTrue(h, h.indexOf("<div class=\"def-num\">1.</div>") in own until second)
        assertTrue(h, h.indexOf("def of B") > second)
        assertEquals(1, h.count("data-act=\"dict:own\""))
        // Shut, the pack's block is its title alone, where it stood.
        val shut = html(data().copy(importedGroups = imported), shut = setOf(OWN_DICTIONARY_KEY))
        assertFalse(shut, shut.contains("def-num"))
        assertTrue(shut, shut.indexOf("dict:own") in shut.indexOf("def of A") until shut.indexOf("def of B"))
    }

    @Test fun `an imported block is drawn in full, structured or flat, in a list's row as in the lens's`() {
        val glossary = """[{"type":"structured-content","content":{"tag":"span","content":"structured eat"}}]"""
        val imported = listOf(ImportedSenseGroup("Jitendex", listOf(ImportedSense("flat eat", scRowid = 5L)), dictId = "jt"))
        val styled = html(data().copy(importedGroups = imported), structured = mapOf(5L to glossary))
        assertTrue(styled, styled.contains("<section class=\"dict-group\">"))
        assertTrue(styled, styled.contains("structured eat") && styled.contains("data-dictionary=\"jt\""))
        // The same row before its content arrived, or with styling off.
        val flat = html(data().copy(importedGroups = imported))
        assertTrue(flat, flat.contains("<section class=\"dict-group\">") && flat.contains("flat eat"))
        // No cap of either kind, on the row or in the page that draws it.
        val shell = DefinitionsDocument.documentShellHtml()
        for (cap in listOf("clamp", "max-height", "class=\"imp pv")) {
            assertFalse(cap, styled.contains(cap) || flat.contains(cap) || shell.contains(cap))
        }
    }

    @Test fun `a full row has live links where a list's row is one tap target, and is the same row otherwise`() {
        val glossary = """[{"type":"structured-content","content":{"tag":"span","content":"structured eat"}}]"""
        val imported = listOf(ImportedSenseGroup("Jitendex", listOf(ImportedSense("flat eat", scRowid = 5L)), dictId = "jt"))
        val list = html(data().copy(importedGroups = imported), structured = mapOf(5L to glossary))
        assertTrue(list, list.contains(" data-row data-inert"))
        val full = html(data().copy(importedGroups = imported), structured = mapOf(5L to glossary), full = true)
        assertFalse(full, full.contains("data-inert"))
        // The inert mark is the whole difference: header, buttons, chevron,
        // the dictionaries' titles and all of their definitions.
        assertEquals(list.replace(" data-inert", ""), full)
    }

    /** A definition that links to another word, as Jitendex writes one. */
    private val linked = """[{"type":"structured-content","content":["See also ",""" +
        """{"tag":"a","href":"?query=%E9%A3%9F%E3%81%86&wildcards=off&primary_reading=%E3%81%8F%E3%81%86","content":"食う"}]}]"""

    // Mutations: a list's row draws the link; a row that opens nothing
    // draws it; each definition numbers its links from 0.
    @Test fun `a dictionary's link to another word is a target of a full row that opens, and its text in any other row`() {
        val imported = listOf(
            ImportedSenseGroup(
                "Jitendex",
                listOf(ImportedSense("flat", scRowid = 5L), ImportedSense("flat", scRowid = 6L)),
                dictId = "jt",
            ),
        )
        val d = data().copy(importedGroups = imported)
        val content = mapOf(5L to linked, 6L to linked)
        val lens = html(d, structured = content, full = true)
        // Numbered over the row: two links to one word are two targets.
        assertEquals(lens, 1, lens.count("data-act=\"${DefAction.reference(0, "食う", "くう")}\""))
        assertEquals(lens, 1, lens.count("data-act=\"${DefAction.reference(1, "食う", "くう")}\""))
        for (plain in listOf(html(d, structured = content), html(d, structured = content, full = true, opens = false))) {
            assertFalse(plain, plain.contains("data-act=\"ref:"))
            assertEquals(plain, 2, plain.count("See also 食う"))
        }
    }

    // Mutations: the block follows links untold; the Anki sheet's block does.
    @Test fun `a page's imported block follows its dictionaries' links to other words only where it is told to`() {
        val groups = listOf(ImportedSenseGroup("Jitendex", listOf(ImportedSense("flat", scRowid = 5L)), dictId = "jt"))
        val content = mapOf(5L to linked)
        val page = pageRow(DefRow.Imported("imp", groups, links = true), content)
        val anchor = "<a class=\"gloss-link\" role=\"link\" data-act=\"${DefAction.reference(0, "食う", "くう")}\">食う</a>"
        assertTrue(page, page.contains(anchor))
        val plain = pageRow(DefRow.Imported("imp", groups), content)
        assertFalse(plain, plain.contains("data-act"))
        // The link is the whole difference.
        assertEquals(plain, page.replace(anchor, "食う"))
        val sheet = pageRow(
            DefRow.Imported(
                "imp", groups, DefRow.SenseLook.SHEET, DefRow.Curation(emptySet(), removable = true), links = true,
            ),
            content,
        )
        assertFalse(sheet, sheet.contains("data-act=\"ref:"))
        assertTrue(sheet, sheet.contains("See also 食う"))
    }

    @Test fun `each imported dictionary stands under its title, a tap target of its own with the arrow that shuts it`() {
        val imported = listOf(
            ImportedSenseGroup(
                "Jitendex",
                listOf(ImportedSense("to eat", pos = "v1"), ImportedSense("to live on", pos = "v1"), ImportedSense("a meal", pos = "n")),
                dictId = "jt",
            ),
            ImportedSenseGroup(
                "大辞林", listOf(ImportedSense("食物を口に入れる", pos = "動")), accentColor = 0xFF112233.toInt(), dictId = "dj",
            ),
            ImportedSenseGroup("Plain", listOf(ImportedSense("no part of speech")), dictId = "pl"),
        )
        val h = html(data().copy(importedGroups = imported))
        val body = h.substringAfter("<div class=\"body\">")
        // One title per dictionary, in drawn order, each naming its place,
        // and the pack's own after them (four dictionaries in the row).
        assertEquals(4, body.count("class=\"dict-t\""))
        val first = body.indexOf("<div class=\"dict-t\" data-act=\"dict:0\" role=\"button\" aria-expanded=\"true\">")
        val second = body.indexOf(
            "<div class=\"dict-t\" data-act=\"dict:1\" role=\"button\" aria-expanded=\"true\" style=\"color:#112233\">",
        )
        assertTrue(body, first >= 0 && second > first)
        // The arrow is set in the title's own line of text, before its label.
        assertTrue(body, Regex("<span><svg class=\"dict-a\"[^>]*>.*?</svg>Jitendex</span></div>").containsMatchIn(body))
        assertTrue(body, body.contains("</svg>大辞林</span></div>"))
        assertEquals("an arrow in each title", 4, body.count("<span><svg class=\"dict-a\""))
        // Under a title the dictionary is named already: a run of
        // definitions is headed by its part of speech alone, once, in the
        // header's own colour (the dictionary's is its title's), and a run
        // with none has no header.
        assertEquals(1, body.count("<div class=\"pos-h\">v1</div>"))
        assertEquals(1, body.count("<div class=\"pos-h\">n</div>"))
        assertEquals(1, body.count("<div class=\"pos-h\">動</div>"))
        assertFalse(body, body.contains("Jitendex ·") || body.contains("大辞林 ·"))
        assertEquals("those three and the pack's own", 4, body.count("class=\"pos-h\""))
        // The title is no part of the row's tap, and the row keeps its own.
        assertTrue(h, h.startsWith("<div class=\"row cell\" id=\"k7\" data-row data-inert data-act=\"open\">"))
    }

    @Test fun `a dictionary shut in the row is its title alone, turned, and the others are as they were`() {
        val imported = listOf(
            ImportedSenseGroup("Jitendex", listOf(ImportedSense("to eat (jitendex)", pos = "v1")), dictId = "jt"),
            ImportedSenseGroup("Other", listOf(ImportedSense("to consume")), dictId = "ot"),
            // A group with no id is named by its label.
            ImportedSenseGroup("Legacy", listOf(ImportedSense("to dine"))),
        )
        val open = html(data().copy(importedGroups = imported))
        val h = html(data().copy(importedGroups = imported), shut = setOf("jt", "Legacy"))
        assertTrue(h, h.contains("<div class=\"dict-t shut\" data-act=\"dict:0\" role=\"button\" aria-expanded=\"false\">"))
        assertTrue(h, h.contains("<div class=\"dict-t\" data-act=\"dict:1\" role=\"button\" aria-expanded=\"true\">"))
        assertTrue(h, h.contains("<div class=\"dict-t shut\" data-act=\"dict:2\" role=\"button\" aria-expanded=\"false\">"))
        assertTrue("precondition: open, it is drawn", open.contains("to eat (jitendex)") && open.contains(">v1</div>"))
        assertFalse("a shut dictionary draws no definition", h.contains("to eat (jitendex)"))
        assertFalse("nor its headers", h.contains(">v1</div>"))
        assertFalse(h, h.contains("to dine"))
        assertTrue("an open one is untouched", h.contains("to consume"))
        assertTrue("every title stays", h.contains("</svg>Jitendex</span>") && h.contains("</svg>Legacy</span>"))
        // The pack's own senses are no dictionary's: nothing shuts them.
        assertTrue(h, h.contains("<div class=\"def-num\">1.</div>"))
        assertEquals("the head of the row is the same", open.substringBefore("<div class=\"body\">"), h.substringBefore("<div class=\"body\">"))
        // A stub draws no dictionary at all.
        val stub = html(data().copy(importedGroups = imported), trailing = DefRow.Trailing.SHOW, shut = setOf("jt"))
        assertFalse(stub, stub.contains("dict-t"))
    }

    @Test fun `beside an imported dictionary the pack's own senses stand under a title of their own, and alone they have none`() {
        val imported = listOf(ImportedSenseGroup("Jitendex", listOf(ImportedSense("to eat (jitendex)")), dictId = "jt"))
        val name = ctx.getString(R.string.dictionary_title_builtin)
        // One dictionary in the row: no title, and nothing shuts senses
        // that have no title to open them again.
        val alone = html(data())
        assertFalse(alone, alone.contains("dict-t"))
        assertEquals(alone, html(data(), shut = setOf(OWN_DICTIONARY_KEY)))
        // Two: the pack's own block is titled like the imported one, after it.
        val both = html(data().copy(importedGroups = imported))
        val title = both.indexOf("<div class=\"dict-t\" data-act=\"dict:own\" role=\"button\" aria-expanded=\"true\"><span>")
        assertTrue(both, title > both.indexOf("to eat (jitendex)"))
        assertTrue(both, both.indexOf("<div class=\"def-num\">1.</div>") > title)
        assertTrue(both, both.substring(title).substringBefore("</div>").endsWith("</svg>$name</span>"))
        // Shut, it is its title alone, and the imported one is as it was.
        val shut = html(data().copy(importedGroups = imported), shut = setOf(OWN_DICTIONARY_KEY))
        assertTrue(shut, shut.contains("<div class=\"dict-t shut\" data-act=\"dict:own\" role=\"button\" aria-expanded=\"false\">"))
        assertFalse(shut, shut.contains("def-num") || shut.contains("class=\"pos-h\">"))
        assertTrue(shut, shut.contains("to eat (jitendex)") && shut.contains("data-act=\"dict:0\" role=\"button\" aria-expanded=\"true\""))
        // An imported dictionary with no sense of the pack's beside it: no
        // title for a block that is not there.
        val importedOnly = html(data(senses = emptyList()).copy(importedGroups = imported))
        assertFalse(importedOnly, importedOnly.contains("dict:own"))
        assertTrue(importedOnly, importedOnly.contains("data-act=\"dict:0\""))
    }

    @Test fun `a row can carry a warning label and a placeholder for a word with no definition`() {
        val labelled = html(label = "Machine translated")
        assertTrue(labelled, labelled.contains("<div class=\"label-warn\">Machine translated</div>"))
        assertTrue(labelled, labelled.indexOf("label-warn") < labelled.indexOf("def-num"))
        val none = html(data(senses = emptyList()), emptyText = "No definitions found.")
        assertTrue(none, none.contains("<div class=\"empty\">No definitions found.</div>"))
        // A word with definitions never shows the placeholder.
        assertFalse(html(emptyText = "No definitions found.").contains("class=\"empty\""))
    }

    @Test fun `a trailing button that holds says so to the page`() {
        val holds = html(trailing = DefRow.Trailing.ANKI, trailingHolds = true)
        assertTrue(holds, holds.contains("data-act=\"trailing\" data-hold aria-label="))
        assertFalse(html(trailing = DefRow.Trailing.ANKI).contains("data-hold"))
        assertEquals("trailing:hold", DefAction.hold(DefAction.TRAILING))
    }

    private fun pageRow(row: DefRow, structured: Map<Long, String> = emptyMap()): String =
        DefRowHtml.html(ctx, row, "k7", structured, "ja")

    @Test fun `a page sense is no tap target, with a number column only when it is numbered`() {
        val numbered = pageRow(DefRow.Sense("s0", number = 2, pos = "NOUN", gloss = "a < b", misc = "archaic"))
        assertTrue(numbered, numbered.startsWith("<div class=\"row sense num\" id=\"k7\" data-row>"))
        assertFalse(numbered, numbered.contains("data-act"))
        assertTrue(numbered, numbered.contains("<div class=\"sn\">2</div>"))
        assertTrue(numbered, numbered.contains("<div class=\"sp\">NOUN</div><div class=\"sg\">a &lt; b</div>"))
        assertTrue(numbered, numbered.contains("<div class=\"sm\">archaic</div>"))
        val single = pageRow(DefRow.Sense("s0", number = null, pos = "", gloss = "only"))
        assertTrue(single, single.startsWith("<div class=\"row sense\" id"))
        for (gone in listOf("class=\"sn\"", "class=\"sp\"", "class=\"sm\"", "class=\"ex\"")) {
            assertFalse(gone, single.contains(gone))
        }
    }

    @Test fun `an example shows its translation once there is one, italic where the target has italics`() {
        fun sense(translation: String, italic: Boolean) = pageRow(
            DefRow.Sense("s0", null, "", "gloss", examples = listOf(DefRow.Example("例文です", translation, italic))),
        )
        val waiting = sense("", italic = true)
        assertTrue(waiting, waiting.contains("<div class=\"exs\">例文です</div>"))
        assertFalse(waiting, waiting.contains("class=\"ext"))
        assertTrue(sense("An example.", true).contains("<div class=\"ext it\">An example.</div>"))
        assertTrue(sense("Пример.", false).contains("<div class=\"ext\">Пример.</div>"))
    }

    @Test fun `a page's imported block is the dictionaries' markup with structured content, page senses without`() {
        val glossary = """[{"type":"structured-content","content":{"tag":"span","content":"structured eat"}}]"""
        val groups = listOf(
            ImportedSenseGroup(
                "Jitendex",
                listOf(ImportedSense("flat eat", "v1", scRowid = 5L), ImportedSense("flat dine")),
                accentColor = 0xFF112233.toInt(), dictId = "jt",
            ),
        )
        val styled = pageRow(DefRow.Imported("imp", groups), mapOf(5L to glossary))
        assertTrue(styled, styled.startsWith("<div class=\"row impf\" id=\"k7\" data-row>"))
        assertTrue(styled, styled.contains("structured eat") && styled.contains("data-dictionary=\"jt\""))
        assertFalse(styled, styled.contains("class=\"sense\""))
        // No structured content (plain dictionaries, or styling off): each
        // definition is a sense of the page under the dictionary's name.
        val flat = pageRow(DefRow.Imported("imp", groups))
        assertTrue(flat, flat.startsWith("<div class=\"row impl\" id=\"k7\" data-row>"))
        assertEquals(flat, 2, flat.count("<div class=\"sense\">"))
        assertTrue(flat, flat.contains("<div class=\"sp\" style=\"color:#112233\">JITENDEX \u00b7 V1</div><div class=\"sg\">flat eat</div>"))
        // The dictionary is named once, on its first definition.
        assertEquals(flat, 1, flat.count("JITENDEX"))
        assertTrue(flat, flat.contains("<div class=\"sg\">flat dine</div>"))
    }

    // Mutations: every page sense reports its text; an example is left
    // unmarked; the gloss or the translation is marked as the source's.
    @Test fun `a page sense reports taps on its text only where its host asked, its example marked as the source language`() {
        fun sense(words: Boolean) = pageRow(
            DefRow.Sense(
                "s0", null, "NOUN", "gloss", misc = "note",
                examples = listOf(DefRow.Example("例文です", "An example.", italic = true)),
                words = words,
            ),
        )
        val plain = sense(false)
        val tappable = sense(true)
        assertTrue(tappable, tappable.startsWith("<div class=\"row sense\" id=\"k7\" data-row data-words>"))
        assertTrue(tappable, tappable.contains("<div class=\"exs\" data-lang=\"ja\">例文です</div>"))
        assertEquals(tappable, 1, tappable.count("data-lang"))
        // The two marks are the whole difference.
        assertFalse(plain, plain.contains("data-words") || plain.contains("data-lang"))
        assertEquals(plain, tappable.replace(" data-words", "").replace(" data-lang=\"ja\"", ""))
    }

    // Mutations: the block reports its text untold; a flat definition names
    // no dictionary; the Anki sheet's block reports its text.
    @Test fun `a page's imported block reports taps on its text with the dictionary every definition is from`() {
        val glossary = """[{"type":"structured-content","content":{"tag":"span","content":"structured eat"}}]"""
        val groups = listOf(
            ImportedSenseGroup(
                "Jitendex", listOf(ImportedSense("flat eat", scRowid = 5L), ImportedSense("flat dine")), dictId = "jt",
            ),
        )
        fun strip(h: String) = h.replace(" data-words", "").replace(" data-dict=\"jt\"", "")
        // A block with structured content: its flat definitions name their
        // dictionary as the structured ones' scope does.
        val content = mapOf(5L to glossary)
        val styled = pageRow(DefRow.Imported("imp", groups, words = true), content)
        assertTrue(styled, styled.startsWith("<div class=\"row impf\" id=\"k7\" data-row data-words>"))
        assertTrue(styled, styled.contains("data-dictionary=\"jt\""))
        assertTrue(styled, styled.contains("<div class=\"def-row\" data-dict=\"jt\"><div class=\"def-text\">flat dine"))
        assertEquals(pageRow(DefRow.Imported("imp", groups), content), strip(styled))
        // A block of flat definitions only.
        val flat = pageRow(DefRow.Imported("imp", groups, words = true))
        assertTrue(flat, flat.startsWith("<div class=\"row impl\" id=\"k7\" data-row data-words>"))
        assertEquals(flat, 2, flat.count("<div class=\"sense\" data-dict=\"jt\">"))
        assertEquals(pageRow(DefRow.Imported("imp", groups)), strip(flat))
        // Under a curation a tap on a definition does nothing.
        for (c in listOf(content, emptyMap())) {
            val sheet = pageRow(
                DefRow.Imported(
                    "imp", groups, DefRow.SenseLook.SHEET, DefRow.Curation(emptySet(), removable = true), words = true,
                ),
                c,
            )
            assertFalse(sheet, sheet.contains("data-words") || sheet.contains("data-dict="))
        }
    }

    @Test fun `a sheet sense carries a remove button for itself and one for each example`() {
        val h = pageRow(
            DefRow.Sense(
                "s4", 1, "NOUN", "gloss",
                examples = listOf(
                    DefRow.Example("first", "", italic = false, removeIndex = 0),
                    DefRow.Example("third", "", italic = false, removeIndex = 2),
                ),
                look = DefRow.SenseLook.SHEET, removable = true,
            ),
        )
        assertTrue(h, h.startsWith("<div class=\"row sense sheet num\" id=\"k7\" data-row>"))
        // An example's button names the index the example had before any removal.
        assertEquals(h, 1, h.count("data-act=\"remove:0\""))
        assertEquals(h, 1, h.count("data-act=\"remove:2\""))
        assertFalse(h, h.contains("data-act=\"remove:1\""))
        // The sense's own button comes after its body, outside every example.
        assertTrue(h, h.endsWith("data-act=\"remove\" aria-label=\"Remove from card\">\u2715</button></div>"))
        val last = pageRow(
            DefRow.Sense("s4", null, "", "gloss", look = DefRow.SenseLook.SHEET, removable = false),
        )
        assertFalse(last, last.contains("data-act"))
    }

    @Test fun `a curated imported block leaves out what was removed and hands the dictionary's name on`() {
        val groups = listOf(
            ImportedSenseGroup("Dict", listOf(ImportedSense("one"), ImportedSense("two"), ImportedSense("three")), dictId = "d"),
            ImportedSenseGroup("Other", listOf(ImportedSense("four")), accentColor = 0xFF112233.toInt(), dictId = "o"),
        )
        val h = pageRow(
            DefRow.Imported("imp", groups, DefRow.SenseLook.SHEET, DefRow.Curation(removed = setOf(0), removable = true)),
        )
        assertFalse(h, h.contains(">one<"))
        // The name rides the first KEPT definition, and each button reports
        // the definition's index across every group before any removal.
        assertTrue(h, h.contains("<div class=\"sp\">DICT</div><div class=\"sg\">two</div>"))
        assertEquals(h, 1, h.count("DICT"))
        for (i in 1..3) assertEquals(h, 1, h.count("data-act=\"remove:$i\""))
        assertFalse(h, h.contains("data-act=\"remove:0\""))
        // The sheet draws dictionary names uncoloured.
        assertFalse(h, h.contains("color:#112233"))
        val last = pageRow(
            DefRow.Imported("imp", groups, DefRow.SenseLook.SHEET, DefRow.Curation(setOf(0, 1, 2), removable = false)),
        )
        assertFalse(last, last.contains("data-act") || last.contains("DICT"))
        assertTrue(last, last.contains(">four<"))
    }

    @Test fun `a curated block with structured content reports its removals the same way`() {
        val glossary = """[{"type":"structured-content","content":{"tag":"span","content":"structured"}}]"""
        val groups = listOf(
            ImportedSenseGroup("Dict", listOf(ImportedSense("a", scRowid = 5L), ImportedSense("b", scRowid = 6L)), dictId = "d"),
        )
        val h = pageRow(
            DefRow.Imported("imp", groups, DefRow.SenseLook.SHEET, DefRow.Curation(emptySet(), removable = true)),
            mapOf(5L to glossary, 6L to glossary),
        )
        assertTrue(h, h.startsWith("<div class=\"row impf sheet\" id=\"k7\" data-row>"))
        assertTrue(h, h.contains("class=\"imp-x\" data-act=\"remove:0\"") && h.contains("class=\"imp-x\" data-act=\"remove:1\""))
    }

    @Test fun `a page's imported dictionaries are a block above its built-in senses and a block below them`() {
        val a = ImportedSenseGroup("A", listOf(ImportedSense("one"), ImportedSense("two")), dictId = "a")
        val b = ImportedSenseGroup("B", listOf(ImportedSense("three")), dictId = "b", belowBuiltIn = true)
        val c = ImportedSenseGroup("C", listOf(ImportedSense("four")), dictId = "c", belowBuiltIn = true)
        fun rows(groups: List<ImportedSenseGroup>) = importedRowsAround(groups, "up", "down") { id, side, base ->
            DefRow.Imported(id, side, curation = DefRow.Curation(emptySet(), removable = true, base = base))
        }
        val (above, below) = rows(listOf(a, b, c))
        assertEquals("up", above!!.id)
        assertEquals(listOf(a), above.groups)
        assertEquals(0, above.curation!!.base)
        assertEquals("down", below!!.id)
        assertEquals(listOf(b, c), below.groups)
        // The second block's first definition continues the first's count.
        assertEquals(2, below.curation!!.base)
        // A side with no definition has no row.
        assertEquals(null, rows(listOf(a)).second)
        assertEquals(null, rows(listOf(b, c)).first)
        assertEquals(0, rows(listOf(b, c)).second!!.curation!!.base)
        assertEquals(null to null, rows(emptyList()))
    }

    @Test fun `the block below the built-in senses reports removals in the word's one index space`() {
        val glossary = """[{"type":"structured-content","content":{"tag":"span","content":"structured"}}]"""
        val below = listOf(
            ImportedSenseGroup("B", listOf(ImportedSense("three", scRowid = 5L), ImportedSense("four", scRowid = 6L)), dictId = "b", belowBuiltIn = true),
        )
        // Two definitions stand in the block above: this one starts at 2.
        for (structured in listOf(emptyMap(), mapOf(5L to glossary, 6L to glossary))) {
            val h = pageRow(
                DefRow.Imported("down", below, DefRow.SenseLook.SHEET, DefRow.Curation(setOf(2), removable = true, base = 2)),
                structured,
            )
            assertFalse(h, h.contains("data-act=\"remove:2\""))
            assertEquals(h, 1, h.count("data-act=\"remove:3\""))
            assertFalse("no index of the other block's", h.contains("remove:0") || h.contains("remove:1"))
        }
        // The flat block leaves the removed definition's text out.
        val flat = pageRow(
            DefRow.Imported("down", below, DefRow.SenseLook.SHEET, DefRow.Curation(setOf(2), removable = true, base = 2)),
        )
        assertFalse(flat, flat.contains(">three<"))
        assertTrue(flat, flat.contains(">four<"))
    }

    @Test fun `a remove action names its item, and nothing else does`() {
        assertEquals(3, DefAction.removedItem(DefAction.removeItem(3)))
        for (other in listOf(DefAction.REMOVE, DefAction.OPEN, "remove:", "remove:x")) {
            assertEquals(other, null, DefAction.removedItem(other))
        }
    }

    private fun sheetWord(
        target: Boolean = false,
        hidden: Boolean = false,
        audio: DefRow.Audio? = null,
        groups: List<ImportedSenseGroup> = emptyList(),
    ) = DefRow.SheetWord(
        "w", "猫", "ねこ", pitch = emptyList(), stars = "\u2605\u2605", target = target, hidden = hidden,
        meaning = "\ncat\nfeline", importedGroups = groups, audio = audio,
    )

    @Test fun `a sheet word is the target toggle, with its eye and the first line of its meaning`() {
        val h = pageRow(sheetWord())
        assertTrue(h, h.startsWith("<div class=\"row sw\" id=\"k7\" data-row data-inert data-act=\"target\">"))
        assertTrue(h, h.contains("<span class=\"sww\">猫</span><span class=\"swr\">ねこ</span><span class=\"sws\">\u2605\u2605</span>"))
        // The word's language, which the stylesheet's weight rule reads.
        assertTrue(h, h.contains("<div class=\"swl\" lang=\"ja\"><span class=\"sww\">"))
        assertEquals(h, 1, h.count("data-act=\"trailing\" aria-label=\"Hide word\""))
        assertTrue(h, h.contains("<div class=\"swd swm\">cat</div>"))
        assertFalse(h, h.contains("feline") || h.contains("class=\"au"))
    }

    @Test fun `a target holds its audio row between the title line and the definition`() {
        val h = pageRow(sheetWord(target = true, audio = DefRow.Audio(on = true, DefRow.Play.IDLE, "Auto")))
        assertTrue(h, h.startsWith("<div class=\"row sw tgt\""))
        val title = h.indexOf("class=\"swt\"")
        val audio = h.indexOf("<div class=\"au on\" data-act=\"audio\"")
        val definition = h.indexOf("class=\"swd")
        assertTrue(h, title in 0 until audio && audio < definition)
        // Inside the row's one element: the preview, the source pill, the title.
        assertTrue(h, h.contains("class=\"auc lit\" data-act=\"speak\""))
        assertTrue(h, h.contains("data-act=\"source\"><span>Auto</span>"))
        assertTrue(h, h.contains("<span class=\"aut\">Include audio</span>"))
    }

    @Test fun `the preview is lit while it is busy or the switch is on, and draws its state`() {
        fun row(on: Boolean, play: DefRow.Play) = pageRow(sheetWord(target = true, audio = DefRow.Audio(on, play, "")))
        val off = row(false, DefRow.Play.IDLE)
        assertTrue(off, off.contains("<div class=\"au\" data-act") && off.contains("class=\"auc\" data-act"))
        val loading = row(false, DefRow.Play.LOADING)
        assertTrue(loading, loading.contains("class=\"auc lit\"") && loading.contains("<span class=\"spin\"></span>"))
        assertFalse(off, off.contains("class=\"spin\""))
        // The idle speaker and the pause are different glyphs.
        assertFalse(row(true, DefRow.Play.PLAYING) == row(true, DefRow.Play.IDLE))
    }

    @Test fun `a hidden sheet word is its stub, inert but for the eye that shows it`() {
        val h = pageRow(sheetWord(hidden = true))
        assertTrue(h, h.startsWith("<div class=\"row sw stub\" id=\"k7\" data-row>"))
        assertEquals(h, 1, h.count("data-act"))
        assertTrue(h, h.contains("data-act=\"trailing\" aria-label=\"Show word\""))
        for (gone in listOf("ねこ", "\u2605", "cat", "class=\"swd")) assertFalse(gone, h.contains(gone))
    }

    @Test fun `a sheet word with structured content draws the dictionaries' definitions, else its meaning`() {
        val glossary = """[{"type":"structured-content","content":{"tag":"span","content":"structured cat"}}]"""
        val groups = listOf(ImportedSenseGroup("Dict", listOf(ImportedSense("flat cat", scRowid = 5L)), dictId = "d"))
        val styled = pageRow(sheetWord(groups = groups), mapOf(5L to glossary))
        assertTrue(styled, styled.contains("<div class=\"swd\">") && styled.contains("structured cat"))
        assertFalse(styled, styled.contains("class=\"swd swm\""))
        val flat = pageRow(sheetWord(groups = groups))
        assertTrue(flat, flat.contains("<div class=\"swd swm\">cat</div>"))
        assertFalse(flat, flat.contains("flat cat"))
    }

    @Test fun `a sheet word whose built-in dictionary leads draws that dictionary's first line, not the imported markup`() {
        val glossary = """[{"type":"structured-content","content":{"tag":"span","content":"structured cat"}}]"""
        val content = mapOf(5L to glossary)
        fun group(below: Boolean) = ImportedSenseGroup(
            "Dict", listOf(ImportedSense("flat cat", scRowid = 5L)), dictId = "d", belowBuiltIn = below,
        )
        // The built-in dictionary above every imported one that has the
        // word: the card opens with its definition, and so does the row.
        val leads = pageRow(sheetWord(groups = listOf(group(below = true))), content)
        assertTrue(leads, leads.contains("<div class=\"swd swm\">cat</div>"))
        assertFalse(leads, leads.contains("structured cat") || leads.contains("class=\"swd\">"))
        // An imported dictionary first, the built-in one between or after:
        // the imported dictionaries' own markup, as before.
        val other = ImportedSenseGroup("Other", listOf(ImportedSense("other cat")), dictId = "o", belowBuiltIn = true)
        for (groups in listOf(listOf(group(below = false)), listOf(group(below = false), other))) {
            val styled = pageRow(sheetWord(groups = groups), content)
            assertTrue(styled, styled.contains("<div class=\"swd\">") && styled.contains("structured cat"))
            assertFalse(styled, styled.contains("class=\"swd swm\""))
        }
        // With no line of its own to show, the row keeps the markup.
        val noMeaning = pageRow(
            DefRow.SheetWord(
                "w", "猫", "ねこ", pitch = emptyList(), stars = "", target = false, hidden = false,
                meaning = "", importedGroups = listOf(group(below = true)),
            ),
            content,
        )
        assertTrue(noMeaning, noMeaning.contains("structured cat"))
    }

    @Test fun `text is escaped`() {
        val h = html(data(word = "a<b>", reading = "x&y", senses = listOf(SenseDisplay(emptyList(), "1 < 2", emptyList()))))
        assertTrue(h, h.contains("a&lt;b&gt;") && h.contains("x&amp;y") && h.contains("1 &lt; 2"))
    }
}
