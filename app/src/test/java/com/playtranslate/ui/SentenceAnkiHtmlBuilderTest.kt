package com.playtranslate.ui

import com.playtranslate.language.SourceLangId
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [SentenceAnkiHtmlBuilder.annotateText] language branching.
 * Pure JVM — no Android classes needed.
 */
class SentenceAnkiHtmlBuilderTest {

    // ── Japanese: ruby + deinflection ────────────────────────────────────

    @Test fun `JA direct match produces ruby tag`() {
        val result = SentenceAnkiHtmlBuilder.annotateText(
            "食べる", mapOf("食べる" to "たべる"),
            newlineAsBr = false, sourceLangId = SourceLangId.JA
        )
        assertTrue("Expected ruby tag", result.contains("<ruby>食べる<rt>たべる</rt></ruby>"))
    }

    @Test fun `JA skips ruby when reading equals word`() {
        val result = SentenceAnkiHtmlBuilder.annotateText(
            "たべる", mapOf("たべる" to "たべる"),
            newlineAsBr = false, sourceLangId = SourceLangId.JA
        )
        assertFalse("Should not have ruby when reading == word", result.contains("<ruby>"))
        assertTrue(result.contains("たべる"))
    }

    @Test fun `JA skips ruby when reading is empty`() {
        val result = SentenceAnkiHtmlBuilder.annotateText(
            "食べる", mapOf("食べる" to ""),
            newlineAsBr = false, sourceLangId = SourceLangId.JA
        )
        assertFalse("Should not have ruby when reading is empty", result.contains("<ruby>"))
    }

    @Test fun `JA deinflection finds conjugated form`() {
        // 食べた is past tense of 食べる — Deinflector should produce 食べる as candidate
        val result = SentenceAnkiHtmlBuilder.annotateText(
            "食べた", mapOf("食べる" to "たべる"),
            newlineAsBr = false, sourceLangId = SourceLangId.JA
        )
        assertTrue("Expected deinflected ruby", result.contains("<ruby>") && result.contains("<rt>"))
    }

    // ── Chinese: ruby (pinyin), no deinflection ──────────────────────────

    @Test fun `ZH direct match produces ruby tag with pinyin`() {
        val result = SentenceAnkiHtmlBuilder.annotateText(
            "今天", mapOf("今天" to "jīn tiān"),
            newlineAsBr = false, sourceLangId = SourceLangId.ZH
        )
        assertTrue("Expected ruby tag", result.contains("<ruby>今天<rt>jīn tiān</rt></ruby>"))
    }

    @Test fun `ZH does not attempt deinflection`() {
        // Even with CJK text that has no direct match, ZH should NOT run Deinflector
        val result = SentenceAnkiHtmlBuilder.annotateText(
            "食べた", mapOf("食べる" to "たべる"),
            newlineAsBr = false, sourceLangId = SourceLangId.ZH
        )
        assertFalse("ZH should not deinflect", result.contains("<ruby>"))
        assertTrue("Should pass through as plain text", result.contains("食べた"))
    }

    // ── English: no ruby, no deinflection ────────────────────────────────

    @Test fun `EN produces plain text, no ruby`() {
        val result = SentenceAnkiHtmlBuilder.annotateText(
            "hello world", mapOf("hello" to ""),
            newlineAsBr = false, sourceLangId = SourceLangId.EN
        )
        assertFalse("EN should not produce ruby", result.contains("<ruby>"))
        assertTrue(result.contains("hello"))
        assertTrue(result.contains("world"))
    }

    @Test fun `EN does not attempt deinflection`() {
        val result = SentenceAnkiHtmlBuilder.annotateText(
            "running fast", mapOf("run" to ""),
            newlineAsBr = false, sourceLangId = SourceLangId.EN
        )
        assertFalse("EN should not deinflect", result.contains("run</"))
        assertTrue("Should pass through as-is", result.contains("running"))
    }

    // ── Spanish: no ruby, no deinflection ────────────────────────────────

    @Test fun `ES produces plain text, no ruby`() {
        val result = SentenceAnkiHtmlBuilder.annotateText(
            "hola mundo", mapOf("hola" to ""),
            newlineAsBr = false, sourceLangId = SourceLangId.ES
        )
        assertFalse("ES should not produce ruby", result.contains("<ruby>"))
        assertTrue(result.contains("hola"))
    }

    // ── Highlighting (all languages) ─────────────────────────────────────

    @Test fun `highlighted word gets bold span`() {
        val result = SentenceAnkiHtmlBuilder.annotateText(
            "食べる", mapOf("食べる" to "たべる"),
            newlineAsBr = false, highlightedWords = setOf("食べる"),
            sourceLangId = SourceLangId.JA
        )
        assertTrue("Expected bold", result.contains("font-weight:800"))
    }

    @Test fun `EN highlighted word gets bold span`() {
        val result = SentenceAnkiHtmlBuilder.annotateText(
            "hello world", mapOf("hello" to ""),
            newlineAsBr = false, highlightedWords = setOf("hello"),
            sourceLangId = SourceLangId.EN
        )
        assertTrue("Expected bold", result.contains("font-weight:800"))
        assertTrue(result.contains("hello"))
    }

    // ── Newline handling ─────────────────────────────────────────────────

    @Test fun `newlineAsBr replaces newlines with br`() {
        val result = SentenceAnkiHtmlBuilder.annotateText(
            "line1\nline2", mapOf("line1" to ""),
            newlineAsBr = true, sourceLangId = SourceLangId.EN
        )
        assertTrue(result.contains("<br>"))
        assertFalse(result.contains("\n"))
    }

    @Test fun `newlineAsBr false replaces newlines with space`() {
        val result = SentenceAnkiHtmlBuilder.annotateText(
            "line1\nline2", mapOf("line1" to ""),
            newlineAsBr = false, sourceLangId = SourceLangId.EN
        )
        assertTrue(result.contains(" "))
        assertFalse(result.contains("\n"))
        assertFalse(result.contains("<br>"))
    }
}
