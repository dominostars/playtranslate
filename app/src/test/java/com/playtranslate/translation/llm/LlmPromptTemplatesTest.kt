package com.playtranslate.translation.llm

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [LlmPromptTemplates]. Three contracts are pinned:
 *
 *   - **Default fidelity**: the built-in templates, substituted for a
 *     concrete pair, reproduce the pre-refactor hardcoded prompts
 *     byte-for-byte ([batchSystemPrompt] pins the NEW composition —
 *     editable persona + code-owned contract — since its persona
 *     paragraph changed by design).
 *   - **Literal single-pass substitution**: injected values (user text,
 *     JSON payloads) survive `\`/`$` untouched and are never re-scanned
 *     for tokens; tokens foreign to a prompt pass through as literals.
 *   - **Validation/normalization matrix**: fatal vs advisory issue
 *     classification and the store-null-when-default rule the editor
 *     relies on.
 *
 * Pure JUnit; no Robolectric. The override seam defaults to `{ null }`,
 * so built-ins resolve deterministically; tests that install a provider
 * reset it in [tearDown].
 */
class LlmPromptTemplatesTest {

    @After
    fun tearDown() {
        LlmPromptTemplates.resetOverrides()
    }

    // ── Default fidelity ────────────────────────────────────────────────

    @Test
    fun `default system prompt matches pre-refactor text for ja-en`() {
        val expected = "You are a professional Japanese (ja) to English (en) translator. " +
            "Your goal is to accurately convey the meaning and nuances of the original Japanese text " +
            "while adhering to English grammar, vocabulary, and cultural sensitivities.\n\n" +
            "Produce only the English translation, without any additional explanations or commentary."
        assertEquals(expected, LlmPromptTemplates.systemPrompt("ja", "en"))
    }

    @Test
    fun `default translation prompt matches pre-refactor text`() {
        assertEquals(
            "Please translate the following Japanese text into English:\n\nこんにちは",
            LlmPromptTemplates.translationUserMessage("こんにちは", "ja", "en", includeContext = true),
        )
    }

    // ── {context} seam ──────────────────────────────────────────────────

    @Test
    fun `context block is inserted once, before the instruction`() {
        LlmPromptTemplates.contextProvider = { _, _ ->
            "Recent dialogue for context:\n- 行くぞ → Let's go.\n\n"
        }
        assertEquals(
            "Recent dialogue for context:\n- 行くぞ → Let's go.\n\n" +
                "Please translate the following Japanese text into English:\n\nこんにちは",
            LlmPromptTemplates.translationUserMessage("こんにちは", "ja", "en", includeContext = true),
        )
    }

    @Test
    fun `context content is literal - dollars and tokens are not re-expanded`() {
        LlmPromptTemplates.contextProvider = { _, _ -> "Price {text} \$100 \\n:\n\n" }
        val out = LlmPromptTemplates.translationUserMessage("hi", "ja", "en", includeContext = true)
        assertTrue(out.startsWith("Price {text} \$100 \\n:\n\n"))
        // The template's own {text} still resolved to the payload exactly once.
        assertTrue(out.endsWith("into English:\n\nhi"))
    }

    @Test
    fun `batch user message carries context too - the toggle must alter every ONLINE LLM path`() {
        LlmPromptTemplates.contextProvider = { _, _ ->
            "Recent dialogue lines, for context only:\n- 行くぞ → Let's go.\n\n"
        }
        assertEquals(
            "Recent dialogue lines, for context only:\n- 行くぞ → Let's go.\n\n" +
                "Translate each of these 2 strings:\n[\"おはよう\",\"メニュー\"]",
            LlmPromptTemplates.batchUserMessage(listOf("おはよう", "メニュー"), "ja", "en", includeContext = true),
        )
    }

    @Test
    fun `resetOverrides clears the context provider`() {
        LlmPromptTemplates.contextProvider = { _, _ -> "SHOULD NOT APPEAR\n\n" }
        LlmPromptTemplates.resetOverrides()
        assertEquals(
            "Please translate the following Japanese text into English:\n\nこんにちは",
            LlmPromptTemplates.translationUserMessage("こんにちは", "ja", "en", includeContext = true),
        )
    }

    @Test
    fun `default batch user message matches pre-refactor text`() {
        assertEquals(
            "Translate each of these 2 strings:\n[\"おはよう\",\"メニュー\"]",
            LlmPromptTemplates.batchUserMessage(listOf("おはよう", "メニュー"), "ja", "en", includeContext = true),
        )
    }

    @Test
    fun `default batch system prompt is persona plus substituted contract`() {
        val expected = "You are a professional Japanese (ja) to English (en) translator. " +
            "Your goal is to accurately convey the meaning and nuances of the original Japanese text " +
            "while adhering to English grammar, vocabulary, and cultural sensitivities.\n\n" +
            "You will be given a JSON array of Japanese strings. " +
            "Translate each string into English, preserving meaning and nuance. " +
            "Respond only with a JSON object of the form {\"translations\": [...]} containing " +
            "exactly one English string per input, in the same order. " +
            "Do not add commentary, prefixes, or notes."
        assertEquals(expected, LlmPromptTemplates.batchSystemPrompt("ja", "en"))
    }

    @Test
    fun `default batch system prompt carries no single-output instruction`() {
        // The single-text default ends with "Produce only the … translation",
        // which contradicts the respond-with-JSON contract. Non-schema
        // endpoints can follow the wrong instruction and break the batch
        // parser, so the default batch composition must never include it.
        val batch = LlmPromptTemplates.batchSystemPrompt("ja", "en")
        assertFalse(batch.contains("Produce only"))
        assertTrue(batch.contains("Respond only with a JSON object"))
    }

    @Test
    fun `language codes are lowercased before lookup`() {
        val prompt = LlmPromptTemplates.systemPrompt("JA", "EN")
        assertTrue(prompt.contains("Japanese (ja)"))
        assertTrue(prompt.contains("English (en)"))
    }

    // ── Substitution semantics ──────────────────────────────────────────

    @Test
    fun `injected text with backslashes and dollar signs survives literally`() {
        val text = "costs \$100 \\ path C:\\tmp \$1"
        val out = LlmPromptTemplates.translationUserMessage(text, "ja", "en", includeContext = true)
        assertTrue(out.endsWith(":\n\n$text"))
    }

    @Test
    fun `json payload with quotes and newlines survives literally`() {
        val texts = listOf("say \"hi\"\nnow", "\$5 \\ ok")
        val out = LlmPromptTemplates.batchUserMessage(texts, "ja", "en", includeContext = true)
        // kotlinx emits \" and \n escapes inside the array — exactly these
        // bytes must land in the prompt (a group-ref-interpreting replace
        // would corrupt or throw on them).
        assertTrue(out.contains("[\"say \\\"hi\\\"\\nnow\",\"\$5 \\\\ ok\"]"))
    }

    @Test
    fun `tokens inside injected values are not re-scanned`() {
        val out = LlmPromptTemplates.translationUserMessage("literal {target} here", "ja", "en", includeContext = true)
        assertTrue(out.contains("literal {target} here"))
    }

    @Test
    fun `unknown braces pass through untouched`() {
        LlmPromptTemplates.overrideProvider = { kind ->
            if (kind == PromptKind.TRANSLATION) "{foo} {text} {bar}" else null
        }
        assertEquals(
            "{foo} hello {bar}",
            LlmPromptTemplates.translationUserMessage("hello", "ja", "en", includeContext = true),
        )
    }

    @Test
    fun `tokens foreign to a prompt stay literal`() {
        LlmPromptTemplates.overrideProvider = { kind ->
            if (kind == PromptKind.SYSTEM) "Translate {text} from {source}." else null
        }
        // {text} is not filled on the SYSTEM prompt — sent as-is.
        assertEquals(
            "Translate {text} from Japanese.",
            LlmPromptTemplates.systemPrompt("ja", "en"),
        )
    }

    // ── Override resolution ─────────────────────────────────────────────

    @Test
    fun `override replaces only its own kind`() {
        LlmPromptTemplates.overrideProvider = { kind ->
            if (kind == PromptKind.SYSTEM) "Be terse. {source}→{target}." else null
        }
        assertEquals("Be terse. Japanese→English.", LlmPromptTemplates.systemPrompt("ja", "en"))
        assertEquals(
            PromptKind.TRANSLATION.default,
            LlmPromptTemplates.effectiveTemplate(PromptKind.TRANSLATION),
        )
    }

    @Test
    fun `custom system prompt flows into the batch system message`() {
        LlmPromptTemplates.overrideProvider = { kind ->
            if (kind == PromptKind.SYSTEM) "Custom persona." else null
        }
        val batch = LlmPromptTemplates.batchSystemPrompt("ja", "en")
        assertTrue(batch.startsWith("Custom persona.\n\n"))
        assertTrue(batch.contains("{\"translations\": [...]}"))
    }

    @Test
    fun `reset restores built-in defaults`() {
        LlmPromptTemplates.overrideProvider = { "overridden" }
        LlmPromptTemplates.resetOverrides()
        assertEquals(
            PromptKind.SYSTEM.default,
            LlmPromptTemplates.effectiveTemplate(PromptKind.SYSTEM),
        )
    }

    // ── Validation ──────────────────────────────────────────────────────

    @Test
    fun `blank prompt is fatal for every kind`() {
        for (kind in PromptKind.entries) {
            val v = LlmPromptTemplates.validate(kind, "  \n ")
            assertEquals(listOf(PromptIssue.Blank), v.fatal)
            assertTrue(v.advisory.isEmpty())
        }
    }

    @Test
    fun `missing text token is fatal on translation prompt only`() {
        val v = LlmPromptTemplates.validate(PromptKind.TRANSLATION, "from {source} to {target}")
        assertEquals(listOf(PromptIssue.MissingText), v.fatal)
        // The same text on SYSTEM is fine (source+target present, no fatal).
        val sys = LlmPromptTemplates.validate(PromptKind.SYSTEM, "from {source} to {target}")
        assertTrue(sys.fatal.isEmpty())
        assertTrue(sys.advisory.isEmpty())
    }

    @Test
    fun `missing strings token is fatal on batch prompt`() {
        val v = LlmPromptTemplates.validate(PromptKind.BATCH, "{source}->{target}, {N} items")
        assertEquals(listOf(PromptIssue.MissingStrings), v.fatal)
    }

    @Test
    fun `missing source and target refs are advisory`() {
        val v = LlmPromptTemplates.validate(PromptKind.TRANSLATION, "just {text}")
        assertTrue(v.fatal.isEmpty())
        assertEquals(
            listOf(PromptIssue.MissingSourceRef, PromptIssue.MissingTargetRef),
            v.advisory,
        )
    }

    @Test
    fun `code tokens satisfy the source and target refs`() {
        val v = LlmPromptTemplates.validate(
            PromptKind.TRANSLATION, "{source_code}->{target_code}: {text}"
        )
        assertTrue(v.fatal.isEmpty())
        assertTrue(v.advisory.isEmpty())
    }

    @Test
    fun `missing N is advisory on batch prompt`() {
        val v = LlmPromptTemplates.validate(
            PromptKind.BATCH, "{source}->{target}: {strings}"
        )
        assertTrue(v.fatal.isEmpty())
        assertEquals(listOf(PromptIssue.MissingCount), v.advisory)
    }

    @Test
    fun `batch prompt is not advised about source and target refs`() {
        // The batch default itself omits them — the batch *system* message
        // names the languages — so the check must not flag batch prompts.
        val v = LlmPromptTemplates.validate(PromptKind.BATCH, "{N} items: {strings}")
        assertTrue(v.fatal.isEmpty())
        assertTrue(v.advisory.isEmpty())
    }

    @Test
    fun `foreign token is advisory`() {
        val v = LlmPromptTemplates.validate(
            PromptKind.SYSTEM, "{source}->{target} translate {text}"
        )
        assertTrue(v.fatal.isEmpty())
        assertEquals(listOf(PromptIssue.ForeignToken("{text}")), v.advisory)
    }

    @Test
    fun `over-long prompt is advisory`() {
        val text = "{source}{target}" + "x".repeat(LlmPromptTemplates.PROMPT_LENGTH_ADVISORY_CHARS)
        val v = LlmPromptTemplates.validate(PromptKind.SYSTEM, text)
        assertEquals(listOf(PromptIssue.TooLong), v.advisory)
    }

    @Test
    fun `fatal and advisory issues report together`() {
        val v = LlmPromptTemplates.validate(PromptKind.TRANSLATION, "no tokens at all")
        assertEquals(listOf(PromptIssue.MissingText), v.fatal)
        assertEquals(
            listOf(PromptIssue.MissingSourceRef, PromptIssue.MissingTargetRef),
            v.advisory,
        )
    }

    @Test
    fun `defaults validate clean`() {
        for (kind in PromptKind.entries) {
            val v = LlmPromptTemplates.validate(kind, kind.default)
            assertTrue("$kind default has fatal issues", v.fatal.isEmpty())
            assertTrue("$kind default has advisory issues", v.advisory.isEmpty())
        }
    }

    // ── Normalization ───────────────────────────────────────────────────

    @Test
    fun `text equal to default normalizes to null`() {
        for (kind in PromptKind.entries) {
            assertNull(LlmPromptTemplates.normalize(kind, kind.default))
            // Trim-equal counts too — the editor trims before comparing.
            assertNull(LlmPromptTemplates.normalize(kind, "  " + kind.default + "\n"))
        }
    }

    @Test
    fun `edited text normalizes to its trimmed form`() {
        assertEquals(
            "custom {text}",
            LlmPromptTemplates.normalize(PromptKind.TRANSLATION, "  custom {text} \n"),
        )
    }
}
