package com.playtranslate.language

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The annotation LRU's generation gate. Together with stamp-at-capture in
 * the engines (generation read BEFORE any Yomitan-dependent work), these
 * pin the adversarial-review race closed: an annotation whose analysis
 * spanned an import mutation carries the PRE-bump stamp, so the cache can
 * never serve it as current — the failure direction is one wasted
 * re-annotation, never a blessed stale reading.
 */
class AnnotationCacheTest {

    private fun ann(text: String, generation: Int) = SentenceAnnotation(
        text = text, lang = SourceLangId.JA, importGeneration = generation,
        spans = listOf(AnnotatedSpan(0, text.length, text)),
    )

    @Test fun `current-generation annotations serve`() {
        val cache = AnnotationCache()
        val a = ann("一泊", AnnotationGenerations.current())
        cache.put(a)
        assertEquals(a, cache.get("一泊"))
    }

    @Test fun `a bump after caching turns hits into misses`() {
        val cache = AnnotationCache()
        cache.put(ann("一泊", AnnotationGenerations.current()))
        AnnotationGenerations.bump()
        assertNull(cache.get("一泊"))
        // And the stale entry is evicted, not retried forever.
        assertNull(cache.get("一泊"))
    }

    @Test fun `an annotation stamped before a bump never serves after it`() {
        // The mid-annotation race shape: content read at generation G, bump
        // to G+1 lands before the annotation is cached. Stamp-at-capture
        // means the stored stamp is G — the cache must refuse it.
        val cache = AnnotationCache()
        val preBump = AnnotationGenerations.current()
        AnnotationGenerations.bump()
        cache.put(ann("一泊", preBump))
        assertNull(cache.get("一泊"))
    }
}
