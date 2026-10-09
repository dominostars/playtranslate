package com.playtranslate.language

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [GenerationLru]: the one generation-gated memo behind the annotation
 *  cache and the per-word resolution memo. */
class GenerationLruTest {

    @Test fun `a current-generation entry serves`() {
        val lru = GenerationLru<String, Int>(4)
        lru.put("a", 1, AnnotationGenerations.current())
        assertEquals(1, lru.get("a"))
    }

    @Test fun `a bump after caching turns hits into misses, and evicts`() {
        val lru = GenerationLru<String, Int>(4)
        lru.put("a", 1, AnnotationGenerations.current())
        AnnotationGenerations.bump()
        assertNull(lru.get("a"))
        assertNull(lru.get("a"))
    }

    @Test fun `an entry stamped before a bump never serves after it`() {
        val lru = GenerationLru<String, Int>(4)
        val preBump = AnnotationGenerations.current()
        AnnotationGenerations.bump()
        lru.put("a", 1, preBump)
        assertNull(lru.get("a"))
    }

    @Test fun `the least recently used entry is evicted past capacity`() {
        val lru = GenerationLru<String, Int>(2)
        val g = AnnotationGenerations.current()
        lru.put("a", 1, g)
        lru.put("b", 2, g)
        lru.get("a") // a is now the most recent
        lru.put("c", 3, g)
        assertNull(lru.get("b"))
        assertEquals(1, lru.get("a"))
        assertEquals(3, lru.get("c"))
    }
}
