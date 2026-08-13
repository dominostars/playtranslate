package com.playtranslate.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YomitanContentHtmlTest {

    private fun render(glossaryJson: String, dictId: String = "d1") =
        YomitanContentHtml.glossaryHtml(glossaryJson, dictId)

    // ── Item shapes ─────────────────────────────────────────────────────

    @Test
    fun `bare strings render escaped with line breaks`() {
        val html = render("""["a <b> gloss\nline two"]""")!!
        assertTrue(html.contains("a &lt;b&gt; gloss<br>line two"))
    }

    @Test
    fun `text items render like strings`() {
        assertTrue(render("""[{"type":"text","text":"cat"}]""")!!.contains("cat"))
    }

    @Test
    fun `each item gets its own gloss-item block`() {
        val html = render("""["one","two"]""")!!
        assertEquals(2, Regex("class=\"gloss-item\"").findAll(html).count())
    }

    @Test
    fun `unparseable json returns null`() {
        assertNull(render("not json"))
        assertNull(render("""{"an":"object"}"""))
    }

    // ── Tag whitelist ───────────────────────────────────────────────────

    @Test
    fun `structured list renders ul li`() {
        val html = render(
            """[{"type":"structured-content","content":
               {"tag":"ul","content":[{"tag":"li","content":"cat"},{"tag":"li","content":"feline"}]}}]"""
        )!!
        assertTrue(html.contains("<ul class=\"gloss-sc-ul\">"))
        assertEquals(2, Regex("<li class=\"gloss-sc-li\"").findAll(html).count())
    }

    @Test
    fun `unknown tags drop with their content`() {
        val html = render(
            """[{"type":"structured-content","content":[
                {"tag":"script","content":"alert(1)"},
                {"tag":"iframe","content":"x"},
                {"tag":"span","content":"kept"}]}]"""
        )!!
        assertFalse(html.contains("script"))
        assertFalse(html.contains("alert"))
        assertFalse(html.contains("iframe"))
        assertTrue(html.contains(">kept</span>"))
    }

    @Test
    fun `tables wrap in a scroll container`() {
        val html = render(
            """[{"type":"structured-content","content":
               {"tag":"table","content":{"tag":"tr","content":[
                 {"tag":"th","content":"h"},{"tag":"td","colSpan":2,"content":"c"}]}}}]"""
        )!!
        assertTrue(html.contains("<div class=\"gloss-sc-table-container\"><table class=\"gloss-sc-table\">"))
        assertTrue(html.contains("colspan=\"2\""))
    }

    @Test
    fun `ruby annotations pass through`() {
        val html = render(
            """[{"type":"structured-content","content":
               {"tag":"ruby","content":["猫",{"tag":"rt","content":"ねこ"}]}}]"""
        )!!
        assertTrue(html.contains("<ruby class=\"gloss-sc-ruby\">猫<rt class=\"gloss-sc-rt\">ねこ</rt></ruby>"))
    }

    // ── data / title / style attributes ─────────────────────────────────

    @Test
    fun `data maps to data-sc attributes with camel-to-kebab`() {
        val html = render(
            """[{"type":"structured-content","content":
               {"tag":"span","data":{"content":"sense-group","fooBar":"x"},"content":"t"}}]"""
        )!!
        assertTrue(html.contains("data-sc-content=\"sense-group\""))
        assertTrue(html.contains("data-sc-foo-bar=\"x\""))
    }

    @Test
    fun `malformed data keys drop instead of emitting broken attributes`() {
        val html = render(
            """[{"type":"structured-content","content":
               {"tag":"span","data":{"bad key":"x","also\"bad":"y","ok":"z"},"content":"t"}}]"""
        )!!
        assertFalse(html.contains("bad"))
        assertTrue(html.contains("data-sc-ok=\"z\""))
    }

    @Test
    fun `title escapes`() {
        val html = render(
            """[{"type":"structured-content","content":
               {"tag":"span","title":"a\"b<c>","content":"t"}}]"""
        )!!
        assertTrue(html.contains("title=\"a&quot;b&lt;c&gt;\""))
    }

    @Test
    fun `whitelisted styles emit as css declarations`() {
        val html = render(
            """[{"type":"structured-content","content":
               {"tag":"span","style":{"fontWeight":"bold","fontSize":"0.8em","marginTop":0.5},"content":"t"}}]"""
        )!!
        assertTrue(html.contains("font-weight:bold;"))
        assertTrue(html.contains("font-size:0.8em;"))
        assertTrue(html.contains("margin-top:0.5em;"))
    }

    @Test
    fun `non-whitelisted style properties never emit`() {
        val html = render(
            """[{"type":"structured-content","content":
               {"tag":"span","style":{"position":"fixed","display":"none","zIndex":"9999","color":"red"},"content":"t"}}]"""
        )!!
        assertFalse(html.contains("position"))
        assertFalse(html.contains("display"))
        assertFalse(html.contains("z-index"))
        assertTrue(html.contains("color:red;"))
    }

    @Test
    fun `style values carrying declaration terminators drop whole`() {
        val html = render(
            """[{"type":"structured-content","content":
               {"tag":"span","style":{"color":"red;position:fixed","fontWeight":"bold"},"content":"t"}}]"""
        )!!
        assertFalse(html.contains("position"))
        assertFalse(html.contains("red"))
        assertTrue(html.contains("font-weight:bold;"))
    }

    @Test
    fun `textDecorationLine array joins`() {
        val html = render(
            """[{"type":"structured-content","content":
               {"tag":"span","style":{"textDecorationLine":["underline","overline"]},"content":"t"}}]"""
        )!!
        assertTrue(html.contains("text-decoration-line:underline overline;"))
    }

    // ── Links ───────────────────────────────────────────────────────────

    @Test
    fun `external links render as styled inert spans`() {
        val html = render(
            """[{"type":"structured-content","content":
               {"tag":"a","href":"https://example.com/x","content":"source"}}]"""
        )!!
        assertTrue(html.contains("<span class=\"gloss-link\">source</span>"))
        assertFalse(html.contains("<a"))
        assertFalse(html.contains("href"))
    }

    @Test
    fun `internal search links unwrap to their text`() {
        val html = render(
            """[{"type":"structured-content","content":
               {"tag":"a","href":"?query=%E7%8C%AB&wildcards=off","content":"猫"}}]"""
        )!!
        assertTrue(html.contains("猫"))
        assertFalse(html.contains("gloss-link"))
    }

    @Test
    fun `javascript hrefs render text only`() {
        val html = render(
            """[{"type":"structured-content","content":
               {"tag":"a","href":"javascript:alert(1)","content":"x"}}]"""
        )!!
        assertFalse(html.contains("javascript"))
        assertTrue(html.contains("x"))
    }

    // ── Images ──────────────────────────────────────────────────────────

    @Test
    fun `images resolve to the media origin with encoded path`() {
        val html = render(
            """[{"type":"structured-content","content":
               {"tag":"img","path":"jitendex/graphics/ca t.avif","width":253.12,"height":250.0,"sizeUnits":"px"}}]""",
            dictId = "abc123",
        )!!
        assertTrue(html.contains("src=\"https://pt-media.internal/media/abc123/jitendex/graphics/ca%20t.avif\""))
        assertTrue(html.contains("width:min(100%,253.12px)"))
        assertTrue(html.contains("aspect-ratio:253.12/250"))
    }

    @Test
    fun `image glossary items render`() {
        val html = render("""[{"type":"image","path":"img/x.png","width":32,"height":32}]""")!!
        assertTrue(html.contains("gloss-image"))
        assertTrue(html.contains("/media/d1/img/x.png"))
    }

    @Test
    fun `pathless images render nothing`() {
        assertNull(render("""[{"type":"image"}]"""))
    }

    // ── The real thing ──────────────────────────────────────────────────

    @Test
    fun `jitendex-shaped entry renders its landmark pieces`() {
        // Condensed from the 馬酔木 specimen: tag chips with data/title,
        // nested sense group, glossary list, forms table, image, link.
        val html = render(JITENDEX_SPECIMEN)!!
        assertTrue(html.contains("data-sc-content=\"part-of-speech-info\""))
        assertTrue(html.contains("title=\"word usually written using kana alone\""))
        assertTrue(html.contains("<ul class=\"gloss-sc-ul\" data-sc-content=\"glossary\">"))
        assertTrue(html.contains("Japanese andromeda"))
        assertTrue(html.contains("gloss-sc-table-container"))
        assertTrue(html.contains("data-sc-content=\"forms-header-row\""))
        assertTrue(html.contains("/media/d1/jitendex/graphics/bb51.avif"))
        assertTrue(html.contains("<span class=\"gloss-link\">Stan Shebs</span>"))
    }

    private val JITENDEX_SPECIMEN = """
    [{"type":"structured-content","content":[
      {"tag":"div","data":{"content":"sense-group"},"content":[
        {"tag":"span","title":"noun (common) (futsuumeishi)","data":{"class":"tag","code":"n","content":"part-of-speech-info"},"content":"noun"},
        {"tag":"span","title":"word usually written using kana alone","data":{"class":"tag","code":"uk","content":"misc-info"},"content":"kana"},
        {"tag":"div","data":{"content":"sense"},"content":[
          {"tag":"ul","data":{"content":"glossary"},"content":[
            {"tag":"li","content":"Japanese andromeda (Pieris japonica)"},
            {"tag":"li","content":"lily-of-the-valley"}]},
          {"tag":"div","data":{"content":"extra-info"},"content":{"tag":"div","content":{"tag":"div","data":{"content":"graphic"},"content":[
            {"tag":"img","height":250.0,"width":253.12,"sizeUnits":"px","appearance":"auto","background":true,"path":"jitendex/graphics/bb51.avif"},
            {"tag":"div","data":{"content":"graphic-attribution"},"content":[
              {"tag":"a","href":"https://commons.wikimedia.org/wiki/User:Stan_Shebs","content":"Stan Shebs"}]}]}}}]}]},
      {"tag":"div","data":{"content":"forms"},"content":[
        {"tag":"table","content":[
          {"tag":"tr","data":{"content":"forms-header-row"},"content":[
            {"tag":"th"},{"tag":"th","content":"馬酔木"}]},
          {"tag":"tr","content":[
            {"tag":"th","content":"あせび"},
            {"tag":"td","data":{"class":"form-valid"},"content":{"tag":"span","title":"valid form/reading combination"}}]}]}]}]}]
    """.trimIndent()
}
