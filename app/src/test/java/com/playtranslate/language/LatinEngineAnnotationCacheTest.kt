package com.playtranslate.language

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins LatinEngine's annotation LRU — the guard that keeps the phrase
 * re-glob's dictionary gates off repeated-text calls (the drag flow
 * re-tokenizes the same OCR line every dwell tick). Same contract as the
 * JA/ZH engines' [AnnotationCache]: identical text serves the cached
 * annotation instance; an imported-dictionary mutation
 * ([AnnotationGenerations.bump]) invalidates it. No pack is installed in
 * this environment, so the gates resolve empty and tokenization degrades to
 * per-word spans — the caching semantics under test are independent of gate
 * results.
 */
@RunWith(RobolectricTestRunner::class)
class LatinEngineAnnotationCacheTest {

    private fun engine() = LatinEngine(ApplicationProvider.getApplicationContext(), SourceLangId.EN)

    @Test fun `repeated annotate serves the cached instance`() = runBlocking {
        val e = engine()
        val first = e.annotate("a great deal of effort")
        val second = e.annotate("a great deal of effort")
        assertSame(first, second)
    }

    @Test fun `import generation bump invalidates the cache`() = runBlocking {
        val e = engine()
        val first = e.annotate("a great deal of effort")
        AnnotationGenerations.bump()
        val after = e.annotate("a great deal of effort")
        assertNotSame(first, after)
        assertEquals(first.spans.map { it.surface }, after.spans.map { it.surface })
    }

    @Test fun `tokenize projects the cached annotation`() = runBlocking {
        val e = engine()
        assertEquals(
            listOf("great", "deal"),
            e.tokenize("a great deal").map { it.surface },
        )
    }

    @Test fun `lookup-unworthy text tokenizes empty but annotates plain`() = runBlocking {
        val e = engine()
        // tokenize must stay [] (pre-cache contract); annotate keeps the
        // interface's one-plain-span fallback.
        assertTrue(e.tokenize("!! 7 ??").isEmpty())
        val plain = e.annotate("!! 7 ??")
        assertEquals(1, plain.spans.size)
        assertEquals("!! 7 ??", plain.spans.single().surface)
    }
}
