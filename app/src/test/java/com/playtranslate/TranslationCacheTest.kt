package com.playtranslate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [TranslationCache]. Pure JVM; no Android framework.
 *
 * Guards the two load-bearing properties that `CaptureService` relies on:
 *  - pair-keying (same text under a different language pair misses), and
 *  - backend-toggle invalidation (adding or removing a DeepL key clears
 *    cached entries that were produced under the previous preference).
 *
 * Both are behaviors the user-facing translation pipeline depends on; a
 * silent regression would serve stale results and be hard to spot.
 */
class TranslationCacheTest {

    private fun key(text: String, source: String = "ja", target: String = "en") =
        TranslationCache.Key(text, source, target)

    // ── Core cache semantics ─────────────────────────────────────────────

    @Test fun `get returns null for an uncached key`() {
        val cache = TranslationCache()
        assertNull(cache[key("hello")])
    }

    @Test fun `put then get returns the stored entry`() {
        val cache = TranslationCache()
        cache[key("hello")] = "こんにちは" to null
        assertEquals("こんにちは" to null, cache[key("hello")])
    }

    @Test fun `contains reflects put and remains false for unrelated keys`() {
        val cache = TranslationCache()
        cache[key("hello")] = "こんにちは" to null
        assertTrue(key("hello") in cache)
        assertFalse(key("goodbye") in cache)
    }

    // ── Pair keying ──────────────────────────────────────────────────────

    @Test fun `same text under different target is a miss`() {
        val cache = TranslationCache()
        cache[key("hello", target = "en")] = "hello_en" to null
        assertNull(cache[key("hello", target = "es")])
    }

    @Test fun `same text under different source is a miss`() {
        val cache = TranslationCache()
        cache[key("ABC", source = "ja")] = "ja_abc" to null
        assertNull(cache[key("ABC", source = "zh")])
    }

    @Test fun `both pair entries coexist independently`() {
        val cache = TranslationCache()
        cache[key("hello", target = "en")] = "english" to null
        cache[key("hello", target = "es")] = "hola" to null
        assertEquals("english" to null, cache[key("hello", target = "en")])
        assertEquals("hola" to null, cache[key("hello", target = "es")])
    }

    // ── LRU bound ─────────────────────────────────────────────────────────

    @Test fun `LRU evicts oldest entry beyond capacity`() {
        val cache = TranslationCache(capacity = 3)
        cache[key("a")] = "A" to null
        cache[key("b")] = "B" to null
        cache[key("c")] = "C" to null
        cache[key("d")] = "D" to null  // evicts "a"
        assertNull(cache[key("a")])
        assertEquals("B" to null, cache[key("b")])
        assertEquals("C" to null, cache[key("c")])
        assertEquals("D" to null, cache[key("d")])
    }

    // ── Backend-toggle invalidation ──────────────────────────────────────

    @Test fun `reconcilePreferredBackend is a no-op on first call`() {
        val cache = TranslationCache()
        cache[key("hello")] = "hello_en" to null
        cache.reconcilePreferredBackend("lingva")
        // First call: no prior preference to compare against.
        assertEquals("hello_en" to null, cache[key("hello")])
    }

    @Test fun `reconcilePreferredBackend is a no-op when unchanged`() {
        val cache = TranslationCache()
        cache.reconcilePreferredBackend("lingva")
        cache[key("hello")] = "hello_en" to null
        cache.reconcilePreferredBackend("lingva")
        assertEquals("hello_en" to null, cache[key("hello")])
    }

    @Test fun `reconcilePreferredBackend clears cache on transition`() {
        val cache = TranslationCache()
        cache.reconcilePreferredBackend("lingva")
        cache[key("hello")] = "lingva_hello" to null
        cache.reconcilePreferredBackend("deepl")
        assertNull(cache[key("hello")])
    }

    @Test fun `reconcilePreferredBackend clears again on toggle back`() {
        val cache = TranslationCache()
        cache.reconcilePreferredBackend("lingva")
        cache[key("hello")] = "lingva_hello" to null
        cache.reconcilePreferredBackend("deepl")  // clears
        cache[key("hello")] = "deepl_hello" to null
        cache.reconcilePreferredBackend("lingva")  // clears again
        assertNull(cache[key("hello")])
    }

    // ── Routing identity (short-text offline routing) ────────────────────

    private fun identity(
        backend: String = "gemini",
        ctx: Boolean = false,
        brg: Boolean = true,
        str: Boolean = true,
    ) = TranslationCache.routingIdentity(
        preferredOnlineId = backend,
        llmContextEnabled = ctx,
        bergamotEnabled = brg,
        shortTextRoutingEnabled = str,
    )

    @Test fun `routingIdentity distinguishes every policy knob`() {
        // Each knob flip must produce a distinct identity, or a toggle
        // wouldn't clear shorts cached under the old routing policy
        // (adversarial-review find: enabling LLM context left cached
        // Bergamot shorts serving instead of re-routing to the LLM batch).
        val variants = setOf(
            identity(),
            identity(ctx = true),
            identity(brg = false),
            identity(str = false),
            identity(backend = "lingva"),
        )
        assertEquals(5, variants.size)
    }

    @Test fun `context toggle clears cached shorts via routing identity`() {
        val cache = TranslationCache()
        cache.reconcilePreferredBackend(identity())
        cache[key("はい")] = "Yes" to "Firefox Translations"
        // Same config → entry survives.
        cache.reconcilePreferredBackend(identity())
        assertEquals("Yes" to "Firefox Translations", cache[key("はい")])
        // User enables LLM context → shorts must re-route; cache clears.
        cache.reconcilePreferredBackend(identity(ctx = true))
        assertNull(cache[key("はい")])
    }

    @Test fun `short-routing debug toggle clears cached shorts`() {
        // The debug row's whole point is A/B-ing routing on device: turning
        // it OFF must not leave Bergamot shorts serving from the cache.
        val cache = TranslationCache()
        cache.reconcilePreferredBackend(identity(str = true))
        cache[key("装備")] = "Equip" to "Firefox Translations"
        cache.reconcilePreferredBackend(identity(str = false))
        assertNull(cache[key("装備")])
    }
}
