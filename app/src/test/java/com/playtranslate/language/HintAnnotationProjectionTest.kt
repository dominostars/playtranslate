package com.playtranslate.language

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The hint-text projection over [SentenceAnnotation]: per-part annotations
 * with offsets walked from span starts, pitch only on whole-span single
 * parts, offsetless spans skipped.
 */
class HintAnnotationProjectionTest {

    @Test fun `parts project to offset annotations, plain parts skipped`() {
        // 聞いた as one span: 聞[き] + いた plain.
        val ann = SentenceAnnotation(
            text = "聞いた", lang = SourceLangId.JA, importGeneration = 0,
            spans = listOf(AnnotatedSpan(
                start = 0, end = 3, surface = "聞いた", lookupForm = "聞く",
                reading = "きいた",
                furigana = listOf(ReadingPart("聞", "き"), ReadingPart("いた", null)),
            )),
        )
        assertEquals(
            listOf(HintTextAnnotation(0, 1, "き")),
            ann.hintAnnotations(),
        )
    }

    @Test fun `whole-span part carries pitch, partial parts never do`() {
        val ann = SentenceAnnotation(
            text = "学校聞い", lang = SourceLangId.JA, importGeneration = 0,
            spans = listOf(
                AnnotatedSpan(
                    start = 0, end = 2, surface = "学校", lookupForm = "学校",
                    reading = "がっこう",
                    furigana = listOf(ReadingPart("学校", "がっこう")),
                    pitch = listOf(0),
                ),
                AnnotatedSpan(
                    start = 2, end = 4, surface = "聞い", lookupForm = "聞く",
                    reading = "きい",
                    furigana = listOf(ReadingPart("聞", "き"), ReadingPart("い", null)),
                    pitch = listOf(1), // pathological: partial ruby must not carry it
                ),
            ),
        )
        assertEquals(
            listOf(
                HintTextAnnotation(0, 2, "がっこう", pitchDownstep = 0),
                HintTextAnnotation(2, 3, "き"),
            ),
            ann.hintAnnotations(),
        )
    }

    @Test fun `offsetless lexical spans project nothing`() {
        val ann = SentenceAnnotation(
            text = "abc", lang = SourceLangId.KO, importGeneration = 0,
            spans = listOf(AnnotatedSpan(
                start = -1, end = -1, surface = "abc", lookupForm = "abc",
                furigana = listOf(ReadingPart("abc", "x")),
            )),
        )
        assertEquals(emptyList<HintTextAnnotation>(), ann.hintAnnotations())
    }

    // ── Frontier-hold (eager typewriter furigana) ────────────────────────

    private fun span(start: Int, end: Int, surface: String, ruby: List<ReadingPart>) =
        AnnotatedSpan(start = start, end = end, surface = surface, furigana = ruby)

    @Test fun `frontier hold strips ruby from the span touching the text end`() {
        // 友達に大人 mid-reveal: 友達 keeps its reading (completed, boundary-
        // confirmed by に); the frontier 大人 — which may become 大人気 —
        // shows no ruby yet.
        val ann = SentenceAnnotation(
            text = "友達に大人", lang = SourceLangId.JA, importGeneration = 0,
            spans = listOf(
                span(0, 2, "友達", listOf(ReadingPart("友達", "ともだち"))),
                span(2, 3, "に", listOf(ReadingPart("に", null))),
                span(3, 5, "大人", listOf(ReadingPart("大人", "おとな"))),
            ),
        ).withFrontierHeld()
        assertEquals(
            listOf(HintTextAnnotation(0, 2, "ともだち")),
            ann.hintAnnotations(),
        )
    }

    @Test fun `frontier hold is a no-op when the text ends in a ruby-less span`() {
        // Terminal punctuation boundary-confirms the word before it: its
        // reading must show.
        val ann = SentenceAnnotation(
            text = "大人。", lang = SourceLangId.JA, importGeneration = 0,
            spans = listOf(
                span(0, 2, "大人", listOf(ReadingPart("大人", "おとな"))),
                span(2, 3, "。", listOf(ReadingPart("。", null))),
            ),
        ).withFrontierHeld()
        assertEquals(
            listOf(HintTextAnnotation(0, 2, "おとな")),
            ann.hintAnnotations(),
        )
    }

    @Test fun `frontier hold leaves the cached annotation untouched`() {
        val original = SentenceAnnotation(
            text = "大人", lang = SourceLangId.JA, importGeneration = 0,
            spans = listOf(span(0, 2, "大人", listOf(ReadingPart("大人", "おとな")))),
        )
        val held = original.withFrontierHeld()
        assertEquals(emptyList<HintTextAnnotation>(), held.hintAnnotations())
        // Pure copy: the original (the LRU's object) still carries its ruby.
        assertEquals(
            listOf(HintTextAnnotation(0, 2, "おとな")),
            original.hintAnnotations(),
        )
    }
}
