package com.playtranslate.ui

import com.playtranslate.language.AnnotatedSpan
import com.playtranslate.language.AnnotationGenerations
import com.playtranslate.language.SentenceAnnotation
import com.playtranslate.language.SourceLangId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Guards the Chinese-variant staleness fix. LastSentenceCache stores ALREADY-
 * LOCALIZED translation + gloss output keyed only by sentence text, so a target
 * or script-variant change (which keeps targetLang == "zh", firing no language-
 * code invalidation) must drop the cache or it serves the stale script when the
 * panel/sheet reopens for the same sentence. The target picker now calls clear()
 * on selection; this verifies clear() actually resets the stale-bearing fields.
 */
class LastSentenceCacheTest {

    @Before fun reset() = LastSentenceCache.clear()

    @Test fun `clear drops localized translation and word results`() {
        LastSentenceCache.setFromTranslationResult(
            original = "请用鼠标",
            translation = "請用滑鼠",                       // Traditional (TW) — already localized
            translationSource = "ml-kit",
            wordResults = mapOf("鼠标" to Triple("", "滑鼠", 0)),
            surfaceForms = emptyMap(),
            wordEnrichment = emptyMap(),
        )
        assertEquals("請用滑鼠", LastSentenceCache.translation)
        assertNotNull(LastSentenceCache.wordResults)

        // What the picker now does on a target/variant change.
        LastSentenceCache.clear()

        assertNull("stale translation must not survive a target/variant change", LastSentenceCache.translation)
        assertNull(LastSentenceCache.wordResults)
        assertNull(LastSentenceCache.surfaceForms)
        assertNull(LastSentenceCache.original)
    }

    // ── snapshotFor: the locked all-or-nothing read ──────────────────────

    @Test fun `snapshotFor returns the word maps only for the matching sentence`() {
        LastSentenceCache.setFromTranslationResult(
            original = "猫が好き",
            translation = "I like cats",
            translationSource = "test",
            wordResults = mapOf("猫" to Triple("ねこ", "cat", 3)),
            surfaceForms = mapOf("猫" to "猫"),
            wordEnrichment = mapOf("猫" to WordEnrichment(pitch = listOf(1))),
        )
        val snap = LastSentenceCache.snapshotFor("猫が好き")
        assertNotNull(snap)
        assertEquals("cat", snap!!.results.getValue("猫").second)
        assertEquals(listOf(1), snap.enrichment.getValue("猫").pitch)
        assertEquals("猫", snap.surfaces["猫"])

        assertNull("another sentence's words must not leak",
            LastSentenceCache.snapshotFor("犬が好き"))
    }

    @Test fun `snapshotFor is null when the sentence has no words yet`() {
        LastSentenceCache.setFromTranslationResult(
            original = "猫が好き",
            translation = "I like cats",
            translationSource = "test",
            wordResults = null,
            surfaceForms = null,
            wordEnrichment = null,
        )
        assertNull(LastSentenceCache.snapshotFor("猫が好き"))
    }

    @Test fun `stale-generation annotation is withheld, maps still serve`() {
        // Yomitan mutations bump the generation with no cache eviction; a
        // stored annotation from before the bump must fail toward
        // re-annotation (null → callers annotate fresh), never toward
        // rendering pre-import readings. The word maps keep serving — their
        // staleness is bounded by the words-lookup cache-miss refresh.
        val ann = SentenceAnnotation(
            text = "一泊", lang = SourceLangId.JA,
            importGeneration = AnnotationGenerations.current(),
            spans = listOf(AnnotatedSpan(0, 2, "一泊")),
        )
        LastSentenceCache.setFromTranslationResult(
            original = "一泊",
            translation = "one night",
            translationSource = null,
            wordResults = mapOf("一泊" to Triple("いっぱく", "stay", 0)),
            surfaceForms = emptyMap(),
            wordEnrichment = emptyMap(),
            annotation = ann,
        )
        assertEquals(ann, LastSentenceCache.snapshotFor("一泊")?.annotation)

        AnnotationGenerations.bump()

        val snapshot = LastSentenceCache.snapshotFor("一泊")
        assertNotNull("maps must still serve after a bump", snapshot)
        assertNull("stale annotation must be withheld", snapshot?.annotation)
    }

    // ── One-tap supplied-payload freshness gate ──────────────────────────
    // A supplier's snapshot is trusted only while its annotation proves it
    // describes THIS sentence under the CURRENT import generation — the
    // adversarial-review bypass shape: settled rows captured pre-import,
    // one-tap sent post-import.

    private fun payload(annotation: SentenceAnnotation?) = LastSentenceCache.WordsPayload(
        results = mapOf("一泊" to Triple("いっぱく", "stay", 0)),
        surfaces = emptyMap(), enrichment = emptyMap(), annotation = annotation,
    )

    private fun annotationFor(text: String) = SentenceAnnotation(
        text = text, lang = SourceLangId.JA,
        importGeneration = AnnotationGenerations.current(),
        spans = listOf(AnnotatedSpan(0, text.length, text)),
    )

    @Test fun `supplied payload with current matching annotation is trusted`() {
        assertEquals(true, payload(annotationFor("一泊")).isTrustedFor("一泊"))
    }

    @Test fun `supplied payload without annotation is never trusted`() {
        assertEquals(false, payload(null).isTrustedFor("一泊"))
    }

    @Test fun `supplied payload for a different sentence is never trusted`() {
        assertEquals(false, payload(annotationFor("二泊")).isTrustedFor("一泊"))
    }

    @Test fun `a generation bump untrusts previously settled payloads`() {
        val p = payload(annotationFor("一泊"))
        assertEquals(true, p.isTrustedFor("一泊"))
        AnnotationGenerations.bump()
        assertEquals(false, p.isTrustedFor("一泊"))
    }
}
