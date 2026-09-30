package com.playtranslate.cloud

import android.graphics.Rect
import com.playtranslate.PtJson
import com.playtranslate.cloud.PtCloudTestSupport.with
import com.playtranslate.language.TextAlignment
import com.playtranslate.language.TextOrientation
import com.playtranslate.model.TextSegments
import com.playtranslate.translation.BackendFailureKind
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Part 1 onto the app's OCR types ([PtCloudResponseMapper.toOcrResult]),
 * through real server output: a Japanese frame with character boxes, a
 * Latin frame with a slanted group, and an Arabic frame whose lines carry no
 * recognizer score and no quad. Robolectric for a real [Rect].
 */
@RunWith(RobolectricTestRunner::class)
class PtCloudResponseTest {

    private fun decode(line: String): OcrPart = PtJson.lenient.decodeFromString(line)

    private fun map(line: String, scale: Float = 1f) = PtCloudResponseMapper.toOcrResult(decode(line), scale)

    private fun box(v: List<Int>) = Rect(v[0], v[1], v[2], v[3])

    private fun JsonObject.box(key: String) = this[key]!!.jsonArray.map { it.jsonPrimitive.int }

    @Test fun `the Japanese frame maps group, lines and character boxes`() {
        val r = map(PtCloudTestSupport.part1(PtCloudTestSupport.JA))!!
        val g = r.groups.single()
        assertEquals("旅先で、宿屋がない時やビンボーな時にはこうやって、キャンプすんのさ", g.text)
        assertEquals(Rect(562, 765, 1288, 962), g.bounds)
        assertEquals(Rect(562, 765, 1288, 962), g.drawBounds)
        assertEquals(TextOrientation.HORIZONTAL, g.orientation)
        assertEquals(TextAlignment.LEFT, g.alignment)
        assertEquals(0f, g.angleDeg)
        assertEquals(3, g.lines.size)

        val line = g.lines[0]
        assertEquals("旅先で、宿屋がない時や", line.text)
        assertEquals(Rect(562, 765, 1137, 832), line.bounds)
        assertEquals(0, line.groupIndex)
        assertEquals(0.99186f, line.confidence)
        assertTrue(line.elements.isEmpty())
        assertEquals(11, line.symbols.size)
        assertEquals("旅", line.symbols[0].text)
        assertEquals(Rect(573, 765, 629, 832), line.symbols[0].bounds)
        assertEquals((0..10).toList(), line.symbols.map { it.charOffset })

        assertEquals(g.text, r.fullText)
        assertEquals(TextSegments.ofGroupTexts(listOf(g.text)), r.segments)
        assertNull(r.engineBackend)
        assertFalse(r.mangaOcrUsed)
        assertNull(r.debugBoxes)
    }

    @Test fun `the Latin frame keeps a slanted group's angle and oriented dims, and each line's group id`() {
        val r = map(PtCloudTestSupport.part1(PtCloudTestSupport.LATIN))!!
        assertEquals(2, r.groups.size)
        val slanted = r.groups[0]
        assertEquals("Evening", slanted.text)
        assertEquals(-14.725483f, slanted.angleDeg)
        assertEquals(80.64893f, slanted.orientedWidth)
        assertEquals(37.845078f, slanted.orientedHeight)
        assertEquals(-14.725483f, slanted.lines[0].angleDeg)
        assertEquals(0.99994f, slanted.lines[0].confidence)
        val upright = r.groups[1]
        assertEquals(0f, upright.angleDeg)
        assertEquals(0f, upright.orientedWidth)
        assertEquals(listOf(1, 1, 1), upright.lines.map { it.groupIndex })
        assertEquals("Evening ${upright.text}", r.fullText)
    }

    @Test fun `word boxes map to elements (the Latin frame with an element tier added)`() {
        // No recorded frame has a non-empty element tier (the server's
        // Paddle lines carry none today), so one is added to a real line.
        val line = PtCloudTestSupport.part1(PtCloudTestSupport.LATIN) { obj ->
            val groups = obj["groups"]!!.jsonArray
            val g1 = groups[1].jsonObject
            val lines = g1["lines"]!!.jsonArray
            val elements = JsonArray(listOf(
                JsonObject(mapOf("text" to JsonPrimitive("...I'll"), "bounds" to JsonArray(listOf(308, 312, 360, 332).map { JsonPrimitive(it) }))),
                JsonObject(mapOf("text" to JsonPrimitive("just"), "bounds" to JsonArray(listOf(365, 312, 401, 332).map { JsonPrimitive(it) }))),
            ))
            val l0 = lines[0].jsonObject.with("elements", elements)
            obj.with("groups", JsonArray(listOf(groups[0], g1.with("lines", JsonArray(listOf(l0) + lines.drop(1))))))
        }
        val elements = map(line)!!.groups[1].lines[0].elements
        assertEquals(listOf("...I'll", "just"), elements.map { it.text })
        assertEquals(listOf(Rect(308, 312, 360, 332), Rect(365, 312, 401, 332)), elements.map { it.bounds })
        // Scaled like every other box.
        assertEquals(Rect(616, 624, 720, 664), PtCloudResponseMapper.toOcrResult(decode(line), 0.5f)!!.groups[1].lines[0].elements[0].bounds)
    }

    @Test fun `the Arabic frame keeps no-signal scores and reversed character offsets`() {
        val r = map(PtCloudTestSupport.part1(PtCloudTestSupport.AR))!!
        assertEquals(2, r.groups.size)
        assertEquals(15, r.groups[1].lines.size)
        val line = r.groups[0].lines[0]
        // -1 is "no signal", never "low".
        assertEquals(-1f, line.confidence)
        assertEquals("ة", line.symbols[0].text)
        assertEquals(17, line.symbols[0].charOffset)
        assertEquals(Rect(1323, 57, 1344, 102), line.symbols[0].bounds)
        assertEquals("مقالة اليوم الجيدة", line.text)
    }

    @Test fun `vertical and centred groups, an absent draw rect, and every absent optional field`() {
        val line = """{"part":"ocr","schema_version":1,"translation_status":"pending","groups":[
            {"id":0,"text":"縦書き","bounds":[10,20,40,200],"orientation":"v","alignment":"center",
             "lines":[{"id":0,"text":"縦書き","bounds":[10,20,40,200],"orientation":"v"}]}]}"""
            .replace("\n", "")
        val g = map(line)!!.groups.single()
        assertEquals(TextOrientation.VERTICAL, g.orientation)
        assertEquals(TextAlignment.CENTER, g.alignment)
        assertEquals("no draw_bounds draws at bounds", g.bounds, g.drawBounds)
        assertEquals(0f, g.angleDeg)
        assertEquals(0f, g.orientedWidth)
        val l = g.lines.single()
        assertEquals(TextOrientation.VERTICAL, l.orientation)
        assertEquals(-1f, l.confidence)
        assertEquals(0f, l.angleDeg)
        assertTrue(l.elements.isEmpty())
        assertTrue("no chars: the proportional fallback applies downstream", l.symbols.isEmpty())
    }

    @Test fun `an empty chars array maps to no symbols`() {
        val line = PtCloudTestSupport.part1(PtCloudTestSupport.JA) { obj ->
            val g = obj["groups"]!!.jsonArray[0].jsonObject
            val lines = g["lines"]!!.jsonArray.map { it.jsonObject.with("chars", JsonArray(emptyList())) }
            obj.with("groups", JsonArray(listOf(g.with("lines", JsonArray(lines)))))
        }
        assertTrue(map(line)!!.groups[0].lines.all { it.symbols.isEmpty() })
    }

    @Test fun `a draw rect that differs from the bounds is kept`() {
        val line = """{"part":"ocr","schema_version":1,"groups":[{"id":0,"text":"a","bounds":[10,20,30,40],
            "draw_bounds":[10,5,30,40],"lines":[]}]}""".replace("\n", "")
        val g = map(line)!!.groups.single()
        assertEquals(Rect(10, 20, 30, 40), g.bounds)
        assertEquals(Rect(10, 5, 30, 40), g.drawBounds)
    }

    @Test fun `no groups, or only blank ones, is no text`() {
        assertNull(map("""{"part":"ocr","schema_version":1,"groups":[]}"""))
        assertNull(map("""{"part":"ocr","schema_version":1,"groups":[{"id":0,"text":"  ","bounds":[0,0,1,1]}]}"""))
    }

    @Test fun `a schema other than 1 is a bad response`() {
        val line = PtCloudTestSupport.part1(PtCloudTestSupport.JA) { it.with("schema_version", JsonPrimitive(2)) }
        try {
            map(line)
            fail("schema 2 must be refused")
        } catch (e: PtCloudReadException) {
            assertEquals(BackendFailureKind.BAD_RESPONSE, e.failure.kind)
        }
    }

    @Test fun `a part 2 line handed to the part 1 mapper is a bad response`() {
        val line = PtCloudTestSupport.part1(PtCloudTestSupport.JA) { it.with("part", JsonPrimitive("translation")) }
        try {
            map(line)
            fail("a non-ocr part must be refused")
        } catch (e: PtCloudReadException) {
            assertEquals(BackendFailureKind.BAD_RESPONSE, e.failure.kind)
        }
    }

    @Test fun `a box that is not four integers fails the decode`() {
        try {
            decode("""{"part":"ocr","schema_version":1,"groups":[{"id":0,"text":"a","bounds":[1,2,3]}]}""")
            fail("a three-value box must not decode")
        } catch (e: IllegalArgumentException) {
            // kotlinx's SerializationException; the client reads it as a bad part 1.
        }
    }

    /** Every box in the mapped result is the wire box divided by [scale]
     *  and truncated; oriented dims are divided; angles pass through. */
    private fun assertBackMapped(name: String, scale: Float) {
        val line = PtCloudTestSupport.part1(name)
        val wire = kotlinx.serialization.json.Json.parseToJsonElement(line).jsonObject
        val r = map(line, scale)!!
        fun expect(v: List<Int>) = Rect((v[0] / scale).toInt(), (v[1] / scale).toInt(), (v[2] / scale).toInt(), (v[3] / scale).toInt())
        val wireGroups = wire["groups"]!!.jsonArray
        assertEquals(wireGroups.size, r.groups.size)
        wireGroups.forEachIndexed { gi, ge ->
            val wg = ge.jsonObject
            val g = r.groups[gi]
            assertEquals(expect(wg.box("bounds")), g.bounds)
            assertEquals(expect(wg.box("draw_bounds")), g.drawBounds)
            assertEquals(wg["angle"]!!.jsonPrimitive.content.toDouble().toFloat(), g.angleDeg)
            assertEquals((wg["ow"]!!.jsonPrimitive.content.toDouble() / scale).toFloat(), g.orientedWidth)
            assertEquals((wg["oh"]!!.jsonPrimitive.content.toDouble() / scale).toFloat(), g.orientedHeight)
            wg["lines"]!!.jsonArray.forEachIndexed { li, le ->
                val wl = le.jsonObject
                val l = g.lines[li]
                assertEquals(expect(wl.box("bounds")), l.bounds)
                assertEquals((wl["ow"]!!.jsonPrimitive.content.toDouble() / scale).toFloat(), l.orientedWidth)
                wl["chars"]!!.jsonArray.forEachIndexed { ci, ce ->
                    assertEquals(expect(ce.jsonObject.box("bounds")), l.symbols[ci].bounds)
                }
            }
        }
    }

    @Test fun `a frame uploaded at half size maps every box back by two`() {
        assertBackMapped(PtCloudTestSupport.JA, 0.5f)
        val g = map(PtCloudTestSupport.part1(PtCloudTestSupport.JA), 0.5f)!!.groups.single()
        assertEquals(Rect(1124, 1530, 2576, 1924), g.bounds)
        assertEquals(Rect(1146, 1530, 1258, 1664), g.lines[0].symbols[0].bounds)
        val slanted = map(PtCloudTestSupport.part1(PtCloudTestSupport.LATIN), 0.5f)!!.groups[0]
        assertEquals(-14.725483f, slanted.angleDeg)
        assertEquals(161.29786f, slanted.orientedWidth)
        assertBackMapped(PtCloudTestSupport.LATIN, 0.5f)
        assertBackMapped(PtCloudTestSupport.AR, 0.5f)
    }

    @Test fun `an uneven scale truncates like the device path`() {
        assertBackMapped(PtCloudTestSupport.JA, 0.3f)
        assertBackMapped(PtCloudTestSupport.LATIN, 0.7f)
        // 562 / 0.3 = 1873.33 -> 1873.
        assertEquals(1873, map(PtCloudTestSupport.part1(PtCloudTestSupport.JA), 0.3f)!!.groups[0].bounds.left)
    }

    @Test fun `at scale 1 the boxes are the wire's`() {
        assertBackMapped(PtCloudTestSupport.AR, 1f)
        assertNotNull(map(PtCloudTestSupport.part1(PtCloudTestSupport.AR)))
    }
}
