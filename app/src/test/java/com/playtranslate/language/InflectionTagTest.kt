package com.playtranslate.language

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Pins the [InflectionTag] contract the deinflector port relies on: keys are
 * unique, each entry has exactly one label source, [InflectionTag.fromKey] finds
 * every entry, and every transform name in Yomitan's Japanese test suite has a tag.
 */
class InflectionTagTest {

    @Test
    fun `every key is unique`() {
        val duplicated = InflectionTag.entries.groupBy { it.key }.filterValues { it.size > 1 }.keys
        assertEquals(emptySet<String>(), duplicated)
    }

    @Test
    fun `every entry has exactly one label source`() {
        val bad = InflectionTag.entries.filter { (it.literal == null) == (it.labelRes == null) }
        assertEquals(emptyList<InflectionTag>(), bad)
    }

    @Test
    fun `fromKey round-trips every entry`() {
        for (tag in InflectionTag.entries) assertSame(tag, InflectionTag.fromKey(tag.key))
        assertNull(InflectionTag.fromKey("TE"))
    }

    @Test
    fun `every transform name in Yomitan's test suite resolves`() {
        val text = javaClass.getResourceAsStream("/yomitan/japanese-transforms-tests.json")!!
            .bufferedReader().use { it.readText() }
        // A case expected not to deinflect carries "reasons": null; it names no transform.
        val names = Json.parseToJsonElement(text).jsonObject.getValue("categories").jsonArray
            .flatMap { it.jsonObject.getValue("tests").jsonArray }
            .mapNotNull { it.jsonObject["reasons"] as? JsonArray }
            .flatMap { reasons -> reasons.map { it.jsonPrimitive.content } }
            .toSortedSet()
        // Non-vacuity: the fixture at its pinned commit names 55 distinct transforms.
        assertEquals(55, names.size)
        assertEquals(emptyList<String>(), names.filter { InflectionTag.fromKey(it) == null })
    }
}
