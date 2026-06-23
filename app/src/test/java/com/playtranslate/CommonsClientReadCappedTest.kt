package com.playtranslate

import com.playtranslate.audio.sources.CommonsClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * Guards the download size cap (adversarial-review finding): a clip over the cap
 * is rejected before it can be buffered into memory / written to the cache.
 */
class CommonsClientReadCappedTest {

    @Test fun returns_bytes_when_under_cap() {
        val data = ByteArray(100) { it.toByte() }
        assertArrayEquals(data, CommonsClient.readCapped(ByteArrayInputStream(data), 1_000))
    }

    @Test fun accepts_exactly_at_cap() {
        val data = ByteArray(1_000) { 1 }
        assertArrayEquals(data, CommonsClient.readCapped(ByteArrayInputStream(data), 1_000))
    }

    @Test fun rejects_when_over_cap() {
        val data = ByteArray(2_000)
        assertNull(CommonsClient.readCapped(ByteArrayInputStream(data), 1_000))
    }
}
