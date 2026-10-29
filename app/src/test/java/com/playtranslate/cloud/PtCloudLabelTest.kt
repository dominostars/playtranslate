package com.playtranslate.cloud

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The device label a verify sends ([deviceLabel]): the manufacturer and the
 * model, each once, trimmed, cut at the server's 64 characters, and nothing
 * else about the device.
 */
class PtCloudLabelTest {

    @Test fun `the manufacturer and the model are joined once`() {
        assertEquals("AYN Thor", deviceLabel("AYN", "Thor"))
        assertEquals("motorola moto g 2025", deviceLabel("motorola", "moto g 2025"))
    }

    @Test fun `a model that starts with its manufacturer is not doubled`() {
        assertEquals("OnePlus 9 Pro", deviceLabel("OnePlus", "OnePlus 9 Pro"))
        assertEquals("in any case", "samsung Galaxy", deviceLabel("Samsung", "samsung Galaxy"))
    }

    @Test fun `the label is trimmed and cut at 64 characters`() {
        assertEquals("AYN Thor", deviceLabel("  AYN ", " Thor\n"))
        val long = deviceLabel("Maker", "M".repeat(100))
        assertEquals(64, long.length)
        assertEquals("Maker " + "M".repeat(58), long)
        // A cut that ends on the space between the two leaves no space behind.
        assertEquals("A".repeat(63), deviceLabel("A".repeat(63), "Model"))
    }

    @Test fun `a missing part leaves the other, and none leaves a blank label`() {
        assertEquals("Thor", deviceLabel(null, "Thor"))
        assertEquals("AYN", deviceLabel("AYN", "  "))
        assertEquals("", deviceLabel(null, null))
    }
}
