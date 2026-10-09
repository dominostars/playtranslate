package com.playtranslate

import android.graphics.Rect
import android.text.TextPaint
import com.playtranslate.language.AnnotatedSpan
import com.playtranslate.language.AnnotationDepth
import com.playtranslate.language.PreloadResult
import com.playtranslate.language.ReadingPart
import com.playtranslate.language.SentenceAnnotation
import com.playtranslate.language.SourceLangId
import com.playtranslate.language.SourceLanguageEngine
import com.playtranslate.language.SourceLanguageProfile
import com.playtranslate.language.SourceLanguageProfiles
import com.playtranslate.language.TokenSpan
import com.playtranslate.model.DictionaryResponse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The live/capture furigana builder annotates the GROUP text once and lands
 * each ruby on the line holding its characters, through the line's address
 * in that text. Field case: a dialogue box wrapped 終わ|り; annotating the
 * first line alone read 終わ as 終う and floated しま over 終, while the
 * results page (which annotates the group text) showed お.
 */
@RunWith(RobolectricTestRunner::class)
class FuriganaGroupAnnotationTest {

    /** Spans per exact text — a reveal annotates the settled prefix and the
     *  frontier's tail as different strings, each with its own reading. */
    private class ScriptedEngine(
        private val spans: (String) -> List<AnnotatedSpan>,
    ) : SourceLanguageEngine {
        val annotated = mutableListOf<String>()
        override val profile: SourceLanguageProfile = SourceLanguageProfiles[SourceLangId.JA]
        override suspend fun preload(): PreloadResult = PreloadResult.Success
        override suspend fun tokenize(text: String): List<TokenSpan> = emptyList()
        override suspend fun lookup(word: String, reading: String?): DictionaryResponse? = null
        override suspend fun annotate(text: String, depth: AnnotationDepth): SentenceAnnotation {
            annotated += text
            return SentenceAnnotation(text, SourceLangId.JA, 0, spans(text))
        }
        override fun close() {}
    }

    private val cell = 40

    /** A horizontal line at [top] with one [cell]-square symbol per character. */
    private fun line(text: String, top: Int, textStart: Int) = OcrManager.LineBox(
        text = text,
        bounds = Rect(0, top, text.length * cell, top + cell),
        groupIndex = 0,
        symbols = text.mapIndexed { i, ch ->
            OcrManager.SymbolBox(ch.toString(), Rect(i * cell, top, (i + 1) * cell, top + cell), i)
        },
        textStart = textStart,
    )

    private fun group(vararg lines: OcrManager.LineBox) = OcrManager.OcrGroup(
        text = lines.joinToString("") { it.text },
        bounds = Rect(0, lines.first().bounds.top, lines.maxOf { it.bounds.right }, lines.last().bounds.bottom),
        lines = lines.toList(),
    )

    /** 終わり at [at]: ruby お over 終, わり written through. */
    private fun owari(at: Int) = AnnotatedSpan(
        at, at + 3, "終わり", reading = "おわり",
        furigana = listOf(ReadingPart("終", "お"), ReadingPart("わり", null)),
    )

    /** One whole-surface ruby part. */
    private fun ruby(text: String, surface: String, reading: String): AnnotatedSpan {
        val at = text.indexOf(surface)
        return AnnotatedSpan(
            at, at + surface.length, surface, reading = reading,
            furigana = listOf(ReadingPart(surface, reading)),
        )
    }

    private fun build(g: OcrManager.OcrGroup, engine: ScriptedEngine, hold: Boolean = false) =
        runBlocking { OverlayToolkit.buildFuriganaBoxesForGroup(g, engine, TextPaint(), holdFrontier = hold) }

    @Test
    fun `a word the wrap cut is read whole and its ruby lands on the first line`() {
        val g = group(
            line("それで今日の手伝い終わ", top = 100, textStart = 0),
            line("りなねだって。", top = 150, textStart = 11),
        )
        val engine = ScriptedEngine { listOf(owari(9)) }
        val boxes = build(g, engine)
        assertEquals("one annotation, of the group text", listOf(g.text), engine.annotated)
        val box = boxes.single()
        assertEquals("お", box.translatedText)
        assertEquals(
            "over 終, the tenth symbol of the first line",
            Rect(9 * cell, 100 - 30, 10 * cell, 100), box.bounds,
        )
    }

    /** 終わ at a text's end, as Sudachi reads it: 終う, しま over 終. */
    private fun shimau(at: Int) = AnnotatedSpan(
        at, at + 2, "終わ", reading = "しまわ",
        furigana = listOf(ReadingPart("終", "しま"), ReadingPart("わ", null)),
    )

    @Test
    fun `a reveal annotates the group once and holds only its last word`() {
        // Three lines, the last still typing; line two ends in 終わ, which
        // the frontier line continues.
        val g = group(
            line("今日は", top = 100, textStart = 0),
            line("手伝い終わ", top = 150, textStart = 3),
            line("りは終了", top = 200, textStart = 8),
        )
        val engine = ScriptedEngine { text ->
            listOf(ruby(text, "今日", "きょう"), owari(6), ruby(text, "終了", "しゅうりょう"))
        }
        val held = build(g, engine, hold = true)
        assertEquals("one annotation, of the group text", listOf(g.text), engine.annotated)
        assertEquals(
            "the frontier word is held; the word the wrap cut shows whole, on the line it starts",
            listOf("きょう", "お"), held.map { it.translatedText },
        )
        assertEquals("お over 終 at the end of line two", Rect(3 * cell, 150 - 30, 4 * cell, 150), held[1].bounds)

        val settled = build(g, engine, hold = false)
        assertEquals(listOf("きょう", "お", "しゅうりょう"), settled.map { it.translatedText })
    }

    @Test
    fun `a one-line reveal holds the group's last word`() {
        val g = group(line("手伝い終わ", top = 100, textStart = 0))
        val engine = ScriptedEngine { listOf(shimau(3)) }
        assertEquals(emptyList<String>(), build(g, engine, hold = true).map { it.translatedText })
        assertEquals(listOf(g.text), engine.annotated)
    }

    @Test
    fun `a ruby part the wrap cut in two is drawn once, over its first fragment`() {
        val g = group(
            line("今", top = 100, textStart = 0),
            line("日は", top = 150, textStart = 1),
        )
        val engine = ScriptedEngine { text -> listOf(ruby(text, "今日", "きょう")) }
        val box = build(g, engine).single()
        assertEquals("きょう", box.translatedText)
        assertEquals("over 今 on line one; nothing over 日", Rect(0, 100 - 30, cell, 100), box.bounds)
    }
}
