package com.playtranslate

import android.graphics.Rect
import com.playtranslate.ocr.ProvidedTranslations
import com.playtranslate.ui.TextBox
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [providedForFarGroups]: which of a pinhole look's far groups take the
 * reader's translation of their group, and whether the look waits for those
 * translations at all. Only a far group that is one whole group of the read
 * ([FarGroup.sourceGroupIndex]), whose group the reader named, and whose box
 * is still a skeleton: a coalesced merge is no single group's text, and a box
 * the cache filled keeps the cache's text (this tier's deliberate difference
 * from the reconciler's [providedForRegions]). The wait (the `provided`
 * lambda) runs at most once, and never when no position could take a
 * translation.
 *
 * Robolectric for [Rect] only.
 */
@RunWith(RobolectricTestRunner::class)
class ProvidedForFarGroupsTest {

    private fun far(text: String, sourceGroupIndex: Int?, top: Int = 0) =
        FarGroup(text, Rect(0, top, 100, top + 20), lineCount = 1, sourceGroupIndex = sourceGroupIndex)

    private fun skeleton(text: String) = TextBox(translatedText = "", bounds = Rect(0, 0, 100, 20), sourceText = text)

    private fun cacheFilled(text: String, translation: String) =
        TextBox(translatedText = translation, bounds = Rect(0, 0, 100, 20), sourceText = text)

    private fun ok(byGroup: Map<Int, String>) =
        ProvidedTranslations.Ok(byGroup, source = "ja", target = "en", providerName = "P")

    /** The wait for the reader's translations, counting how often it runs. */
    private class Provider(private val value: ProvidedTranslations.Ok?) {
        var calls = 0
            private set
        val wait: suspend () -> ProvidedTranslations.Ok? = { calls++; value }
    }

    @Test fun `a skeleton of one whole group takes that group's translation, by position`() = runBlocking {
        // Far position 0 is group 2 of the read, position 1 is group 0: a
        // mapping by position would give position 0 "one".
        val work = listOf(far("三", sourceGroupIndex = 2), far("一", sourceGroupIndex = 0, top = 40))
        val partial = listOf(skeleton("三"), skeleton("一"))
        val provider = Provider(ok(mapOf(0 to "one", 1 to "two", 2 to "three")))

        val byPosition = providedForFarGroups(work, partial, provider.wait)!!

        assertEquals(mapOf(0 to "three", 1 to "one"), byPosition.byGroup)
        assertEquals("one wait for two positions", 1, provider.calls)
    }

    @Test fun `a skeleton of a coalesced merge takes nothing`() = runBlocking {
        val work = listOf(far("一 二", sourceGroupIndex = null), far("三", sourceGroupIndex = 2, top = 40))
        val partial = listOf(skeleton("一 二"), skeleton("三"))
        val provider = Provider(ok(mapOf(0 to "one", 1 to "two", 2 to "three")))

        assertEquals(mapOf(1 to "three"), providedForFarGroups(work, partial, provider.wait)!!.byGroup)
        assertEquals(1, provider.calls)
    }

    @Test fun `a skeleton of a group the reader did not name takes nothing`() = runBlocking {
        val work = listOf(far("一", sourceGroupIndex = 0), far("二", sourceGroupIndex = 1, top = 40))
        val partial = listOf(skeleton("一"), skeleton("二"))
        val provider = Provider(ok(mapOf(1 to "two")))

        assertEquals(mapOf(1 to "two"), providedForFarGroups(work, partial, provider.wait)!!.byGroup)
        assertEquals(1, provider.calls)
    }

    @Test fun `a box the cache filled keeps the cache's text`() = runBlocking {
        val work = listOf(far("一", sourceGroupIndex = 0), far("二", sourceGroupIndex = 1, top = 40))
        val partial = listOf(cacheFilled("一", "one (cached)"), skeleton("二"))
        val provided = ok(mapOf(0 to "one", 1 to "two"))

        assertEquals(mapOf(1 to "two"), providedForFarGroups(work, partial) { provided }!!.byGroup)
        // Every box the cache filled: nothing is named, so no set at all.
        assertNull(providedForFarGroups(work.take(1), partial.take(1)) { provided })
    }

    @Test fun `nothing provided gives nothing, after one wait`() = runBlocking {
        val work = listOf(far("一", sourceGroupIndex = 0))
        val provider = Provider(null)

        assertNull(providedForFarGroups(work, listOf(skeleton("一")), provider.wait))
        assertEquals(1, provider.calls)
    }

    @Test fun `no position named gives no set, after one wait`() = runBlocking {
        // A merge, a whole-group skeleton the reader did not name, and a
        // cache fill whose group it did: the skeleton is worth the wait, but
        // nothing comes of it.
        val work = listOf(
            far("一 二", sourceGroupIndex = null),
            far("三", sourceGroupIndex = 2, top = 40),
            far("四", sourceGroupIndex = 3, top = 80),
        )
        val partial = listOf(skeleton("一 二"), skeleton("三"), cacheFilled("四", "four (cached)"))
        val provider = Provider(ok(mapOf(0 to "one", 1 to "two", 3 to "four")))

        assertNull(providedForFarGroups(work, partial, provider.wait))
        assertEquals(1, provider.calls)
    }

    @Test fun `the pair and the provider are kept`() = runBlocking {
        val work = listOf(far("一", sourceGroupIndex = 0))
        val byPosition = providedForFarGroups(work, listOf(skeleton("一"))) { ok(mapOf(0 to "one")) }!!
        assertEquals(ProvidedTranslations.Ok(mapOf(0 to "one"), "ja", "en", "P"), byPosition)
    }

    // ── when it waits ────────────────────────────────────────────────────

    @Test fun `skeletons that are all coalesced merges never wait`() = runBlocking {
        val work = listOf(far("一 二", sourceGroupIndex = null), far("三 四", sourceGroupIndex = null, top = 40))
        val partial = listOf(skeleton("一 二"), skeleton("三 四"))
        val provider = Provider(ok(mapOf(0 to "one", 1 to "two", 2 to "three", 3 to "four")))

        assertNull(providedForFarGroups(work, partial, provider.wait))
        assertEquals(0, provider.calls)
    }

    @Test fun `boxes the cache filled entirely never wait`() = runBlocking {
        val work = listOf(far("一", sourceGroupIndex = 0), far("二", sourceGroupIndex = 1, top = 40))
        val partial = listOf(cacheFilled("一", "one (cached)"), cacheFilled("二", "two (cached)"))
        val provider = Provider(ok(mapOf(0 to "one", 1 to "two")))

        assertNull(providedForFarGroups(work, partial, provider.wait))
        assertEquals(0, provider.calls)
    }

    @Test fun `merges and cache fills with no whole-group skeleton never wait`() = runBlocking {
        val work = listOf(
            far("一 二", sourceGroupIndex = null),
            far("三", sourceGroupIndex = 2, top = 40),
            far("四 五", sourceGroupIndex = null, top = 80),
        )
        val partial = listOf(skeleton("一 二"), cacheFilled("三", "three (cached)"), cacheFilled("四 五", "4-5 (cached)"))
        val provider = Provider(ok(mapOf(0 to "one", 1 to "two", 2 to "three")))

        assertNull(providedForFarGroups(work, partial, provider.wait))
        assertEquals(0, provider.calls)
    }
}
