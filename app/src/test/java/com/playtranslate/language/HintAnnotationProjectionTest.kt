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
}
