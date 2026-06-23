package com.playtranslate

import com.playtranslate.audio.AudioDecodeLimits
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the bound that stops a small *compressed* Commons clip from decoding
 * into an unbounded PCM buffer (OOM/stall). The decode loop itself needs a real
 * codec (device-only), but these predicates are the gate it relies on.
 */
class AudioDecodeLimitsTest {

    @Test fun `decoded pcm over the ceiling is rejected, at-or-under is allowed`() {
        assertTrue(AudioDecodeLimits.pcmExceedsLimit(AudioDecodeLimits.MAX_PCM_BYTES + 1L))
        assertFalse(AudioDecodeLimits.pcmExceedsLimit(AudioDecodeLimits.MAX_PCM_BYTES.toLong()))
        assertFalse(AudioDecodeLimits.pcmExceedsLimit(100_000L)) // a normal word clip
    }

    @Test fun `over-long duration is rejected, short and unknown are allowed`() {
        assertTrue(AudioDecodeLimits.durationExceedsLimit(AudioDecodeLimits.MAX_DURATION_US + 1))
        assertFalse(AudioDecodeLimits.durationExceedsLimit(5_000_000L)) // 5s clip
        // Unknown duration (<=0) must NOT be rejected — many containers omit it,
        // and the byte cap is the backstop. Rejecting here would drop valid clips.
        assertFalse(AudioDecodeLimits.durationExceedsLimit(0L))
        assertFalse(AudioDecodeLimits.durationExceedsLimit(-1L))
    }
}
