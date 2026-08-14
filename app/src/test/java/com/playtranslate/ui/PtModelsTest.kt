package com.playtranslate.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Structural pins for the field-based PlayTranslate note types: field
 * lists, template ↔ field consistency, CSS coverage of every class the
 * field builders emit, and the template-JS markers. The JVM can't
 * execute the template JS or Anki's mustache renderer — device
 * validation is the real gate for rendering — but these tests catch
 * the buildable failure modes (typo'd field reference, dropped CSS
 * rule, payload/spec drift) at test time.
 */
class PtModelsTest {

    private val specs = listOf(PtModels.WORD, PtModels.SENTENCE)

    private val wordNote = PtNote.Word(
        expression = "e", reading = "r", pitchPosition = "0,2",
        partOfSpeech = "p", definition = "d", examples = "x",
        frequency = "f", picture = "i", wordAudio = "a", audioCredit = "c",
    )

    private val sentenceNote = PtNote.Sentence(
        sentence = "s", sentenceFurigana = "sf", translation = "t",
        targetWord = "tw", wordsTable = "wt", picture = "p",
        sentenceAudio = "a", audioCredit = "c",
    )

    // ── payload ↔ spec drift ─────────────────────────────────────────────

    @Test fun `word note values cover exactly the word spec's fields`() {
        assertEquals(PtModels.WORD.fields.toSet(), wordNote.toValues().keys)
    }

    @Test fun `sentence note values cover exactly the sentence spec's fields`() {
        assertEquals(PtModels.SENTENCE.fields.toSet(), sentenceNote.toValues().keys)
    }

    /** Field 0 is the sort field — Anki's dup key and the csum the
     *  "already in Anki" badge rides. Pinned because reordering it
     *  silently changes duplicate semantics. */
    @Test fun `sort fields are Expression and Sentence`() {
        assertEquals("Expression", PtModels.WORD.fields.first())
        assertEquals("Sentence", PtModels.SENTENCE.fields.first())
    }

    @Test fun `specFor maps modes to their models`() {
        assertEquals(PtModels.WORD, PtModels.specFor(CardMode.WORD))
        assertEquals(PtModels.SENTENCE, PtModels.specFor(CardMode.SENTENCE))
    }

    // ── template ↔ field consistency ─────────────────────────────────────

    /** Anki built-ins legal in any template. */
    private val builtins = setOf("FrontSide", "Tags", "Type", "Deck", "Subdeck", "Card")

    private fun mustacheNames(template: String): Set<String> =
        Regex("\\{\\{(.*?)\\}\\}").findAll(template)
            .map { m ->
                m.groupValues[1]
                    .removePrefix("#").removePrefix("/").removePrefix("^")
                    .substringAfterLast(':')  // strip furigana:/kana:/text: filters
            }
            .toSet()

    @Test fun `every template reference resolves to a field or builtin`() {
        for (spec in specs) {
            val legal = spec.fields.toSet() + builtins
            for (name in mustacheNames(spec.qfmt + spec.afmt)) {
                assertTrue("'$name' referenced by ${spec.name} templates but not a field", name in legal)
            }
            // Sanity: the audit saw actual references (regex not broken).
            assertTrue(mustacheNames(spec.qfmt + spec.afmt).isNotEmpty())
        }
    }

    /** Our backs never echo the front — the v005 blob's whole
     *  hide-the-FrontSide CSS hack existed because Anki's auto template
     *  did. Regression-pin its absence. */
    @Test fun `afmt does not include FrontSide`() {
        for (spec in specs) assertFalse(spec.afmt.contains("{{FrontSide}}"))
    }

    /** LOAD-BEARING markers: AnkiManager verifies installs AND decides
     *  repair eligibility on reuse by looking for the `pt-q` wrapper in
     *  the stored question format and `pt-a` in the answer format. A
     *  template without its marker would make every install look
     *  failed and every healthy model look repairable. */
    @Test fun `every template carries its verification marker`() {
        for (spec in specs) {
            assertTrue("${spec.name} qfmt missing pt-q", spec.qfmt.contains("pt-q"))
            assertTrue("${spec.name} afmt missing pt-a", spec.afmt.contains("pt-a"))
        }
    }

    // ── stored-template classification (repair eligibility) ─────────────
    // The asymmetry under test: misreading a user rewrite as
    // AUTO_GENERATED destroys template edits (no undo in Anki);
    // misreading auto as FOREIGN merely leaves ugly cards. Unknown
    // content must therefore NEVER classify as AUTO_GENERATED.

    private fun autoAfmt(spec: PtModels.Spec) =
        "{{FrontSide}}\n\n<hr id=answer>\n\n{{${spec.fields[1]}}}"

    @Test fun `canonical templates classify as ours`() {
        for (spec in specs) {
            assertEquals(PtModels.TemplateState.OURS,
                PtModels.classifyStoredTemplate(spec.qfmt, spec.afmt, spec))
        }
    }

    @Test fun `user tweak that keeps both wrappers stays ours`() {
        assertEquals(PtModels.TemplateState.OURS,
            PtModels.classifyStoredTemplate(
                "<div class=\"pt-q pt-word-front\" style=\"color:red\">{{Expression}}</div>",
                PtModels.WORD.afmt,
                PtModels.WORD))
    }

    @Test fun `anki auto template classifies as auto-generated`() {
        assertEquals(PtModels.TemplateState.AUTO_GENERATED,
            PtModels.classifyStoredTemplate("{{Expression}}", autoAfmt(PtModels.WORD), PtModels.WORD))
        assertEquals(PtModels.TemplateState.AUTO_GENERATED,
            PtModels.classifyStoredTemplate(" {{Sentence}}\n", null, PtModels.SENTENCE))
        assertEquals(PtModels.TemplateState.AUTO_GENERATED,
            PtModels.classifyStoredTemplate(null, null, PtModels.WORD))
        assertEquals(PtModels.TemplateState.AUTO_GENERATED,
            PtModels.classifyStoredTemplate("", "", PtModels.WORD))
    }

    @Test fun `user rewrite without the wrapper classifies as foreign`() {
        assertEquals(PtModels.TemplateState.FOREIGN,
            PtModels.classifyStoredTemplate(
                "<div class=\"my-front\">{{Expression}}<br>{{Reading}}</div>",
                null,
                PtModels.WORD))
        // The other model's auto shape is NOT this model's — still foreign.
        assertEquals(PtModels.TemplateState.FOREIGN,
            PtModels.classifyStoredTemplate("{{Sentence}}", null, PtModels.WORD))
    }

    /** The partial-write fingerprint: front landed (has pt-q), back is
     *  still exactly Anki's auto shape or blank — repairable. */
    @Test fun `our front with auto or blank back classifies as auto-generated`() {
        for (spec in specs) {
            assertEquals(PtModels.TemplateState.AUTO_GENERATED,
                PtModels.classifyStoredTemplate(spec.qfmt, autoAfmt(spec), spec))
            assertEquals(PtModels.TemplateState.AUTO_GENERATED,
                PtModels.classifyStoredTemplate(spec.qfmt, null, spec))
            assertEquals(PtModels.TemplateState.AUTO_GENERATED,
                PtModels.classifyStoredTemplate(spec.qfmt, " ", spec))
        }
    }

    /** A hand-written back that USES the common FrontSide/hr pattern but
     *  isn't byte-identical to the auto shape is a USER back — foreign,
     *  never repaired. This is the clobber case the exact-match rule
     *  exists to prevent. */
    @Test fun `our front with a hand-written FrontSide back stays foreign`() {
        assertEquals(PtModels.TemplateState.FOREIGN,
            PtModels.classifyStoredTemplate(
                PtModels.WORD.qfmt,
                "{{FrontSide}}<hr id=answer>{{Reading}} — {{Definition}}",
                PtModels.WORD))
    }

    // ── CSS coverage ─────────────────────────────────────────────────────

    /** Every gl-* class the field builders can emit into BOTH models'
     *  fields (classStyler HTML in Definition/Examples/Frequency/
     *  WordsTable). A class missing from the model CSS renders unstyled
     *  with no error anywhere. */
    private val emittedClasses = listOf(
        "gl-secondary", "gl-hint", "gl-hl", "gl-hl-bg",
        "gl-sense", "gl-sense-n", "gl-sense-b", "gl-pos", "gl-gloss", "gl-misc",
        "gl-ex", "gl-ex-tr", "gl-section", "gl-panel", "gl-rows", "gl-row",
        "gl-stars", "gl-chip", "gl-pill",
    )

    /** Classes only the sentence card's words-table cells emit — the
     *  word model deliberately ships without the cell rules. */
    private val sentenceOnlyClasses = listOf(
        "gl-w", "gl-w-target", "gl-w-head", "gl-w-word", "gl-w-read",
        "gl-meta", "gl-pos-h", "gl-def", "gl-num", "gl-dtext",
    )

    @Test fun `model css styles every class the field builders emit`() {
        for (spec in specs) {
            for (cls in emittedClasses) {
                assertTrue("${spec.name} css missing .$cls", spec.css.contains(".$cls{"))
            }
        }
        for (cls in sentenceOnlyClasses) {
            assertTrue("SENTENCE css missing .$cls",
                PtModels.SENTENCE.css.contains(".$cls{"))
        }
    }

    /** The maker's mark (superseding handoff 7a's every-face bar, by
     *  Gilad's direction 2026-08-13): a footer credit on the BACK faces
     *  only — present in the artifacts that travel, absent from the
     *  recall path — with its CSS in both models and the icon riding a
     *  self-contained data URI. No face carries the old top bar. */
    @Test fun `backs carry the footer credit, fronts stay clean`() {
        for (spec in specs) {
            assertTrue("${spec.name} qfmt must carry no branding",
                !spec.qfmt.contains("pt-brand") && !spec.qfmt.contains("pt-madewith"))
            assertTrue("${spec.name} afmt missing the footer credit",
                spec.afmt.contains("pt-madewith"))
            assertTrue("${spec.name} afmt missing the name",
                spec.afmt.contains("Made with PlayTranslate"))
            assertTrue("${spec.name} css missing .pt-madewith",
                spec.css.contains(".pt-madewith{"))
            assertTrue("${spec.name} css must not keep the old bar",
                !spec.css.contains(".pt-brand{"))
            assertTrue("${spec.name} icon must be self-contained",
                spec.afmt.contains("data:image/jpeg;base64,"))
        }
    }

    /** Both models bake or draw pa-* pitch markup (word: template JS;
     *  sentence: WordsTable renderPitch). Splice-by-reference means
     *  this can only fail if the CSS assembly drops the constant. */
    @Test fun `model css includes the pitch-accent rules`() {
        for (spec in specs) {
            assertTrue(spec.css.contains(PitchAccentHtml.PITCH_CSS))
        }
    }

    // ── synthetic-name hiding ────────────────────────────────────────────

    @Test fun `isSyntheticName hides our models and the legacy blobs only`() {
        assertTrue(PtModels.isSyntheticName(PtModels.WORD.name))
        assertTrue(PtModels.isSyntheticName(PtModels.SENTENCE.name))
        assertTrue(PtModels.isSyntheticName("PlayTranslate v005"))
        assertTrue(PtModels.isSyntheticName("PlayTranslate v004"))
        assertFalse(PtModels.isSyntheticName("PlayTranslate Mining"))
        assertFalse(PtModels.isSyntheticName("Lapis"))
        assertFalse(PtModels.isSyntheticName("Basic"))
    }

    // ── template-JS structural pins ──────────────────────────────────────

    @Test fun `word afmt carries the pitch JS and its mount points`() {
        val afmt = PtModels.WORD.afmt
        assertTrue(afmt.contains("id=\"pt-pitch-pos\""))
        assertTrue(afmt.contains("id=\"pt-reading\""))
        assertTrue(afmt.contains("id=\"pt-word\""))
        for (marker in listOf("ptMorae", "ptContour", "ptIsKana")) {
            assertTrue("pitch JS missing $marker", afmt.contains(marker))
        }
    }

    @Test fun `sentence qfmt carries the furigana tooltip JS`() {
        val qfmt = PtModels.SENTENCE.qfmt
        assertTrue(qfmt.contains("gl-tip"))
        assertTrue(qfmt.contains("touchend"))
        assertTrue(qfmt.contains("click"))
    }

    /** Readings are hidden on the question side only — the back shows
     *  them. The hiding rule is scoped to .pt-q, and the back wrapper
     *  is .pt-a, so the same CSS serves both. */
    @Test fun `rt hiding is scoped to the question side`() {
        assertTrue(PtModels.SENTENCE.css.contains(".pt-q ruby rt{display:none;}"))
        assertFalse(PtModels.SENTENCE.afmt.contains("pt-q"))
    }
}
