package com.playtranslate

import com.playtranslate.audio.Attribution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AttributionTest {

    @Test fun creditLine_formats_author_and_license() {
        val a = Attribution("Jane Doe", "CC BY-SA 4.0", "Wikimedia Commons", null)
        assertEquals("Jane Doe (CC BY-SA 4.0), via Wikimedia Commons", a.creditLine())
    }

    @Test fun creditLine_handles_missing_author() {
        val a = Attribution(null, "CC0", "Wikimedia Commons", null)
        assertTrue(a.creditLine().startsWith("Unknown author"))
    }

    @Test fun creditBlock_dedupes_identical_credits() {
        val a = Attribution("Jane", "CC BY", "Wikimedia Commons", null)
        assertEquals(1, Attribution.creditBlock(listOf(a, a)).lines().size)
    }

    @Test fun creditBlock_one_line_per_distinct_credit() {
        val a = Attribution("Jane", "CC BY", "Wikimedia Commons", null)
        val b = Attribution("John", "CC0", "Wikimedia Commons", null)
        assertEquals(2, Attribution.creditBlock(listOf(a, b)).lines().size)
    }
}
