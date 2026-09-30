package com.playtranslate.cloud

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The size a frame is scaled to under the configured long-side cap before
 * upload ([FrameEncoder.scaledSize], pure). The encoding itself, which needs
 * real graphics, is [FrameEncoderWebpTest].
 */
class FrameEncoderTest {

    @Test fun `under the cap the size is unchanged`() {
        assertEquals(1280 to 720, FrameEncoder.scaledSize(1280, 720, 1920))
        assertEquals(720 to 1280, FrameEncoder.scaledSize(720, 1280, 1920))
    }

    @Test fun `exactly at the cap the size is unchanged`() {
        assertEquals(1920 to 1080, FrameEncoder.scaledSize(1920, 1080, 1920))
        assertEquals(1080 to 1920, FrameEncoder.scaledSize(1080, 1920, 1920))
    }

    @Test fun `over the cap on the width, the width becomes the cap`() {
        assertEquals(1920 to 1080, FrameEncoder.scaledSize(2560, 1440, 1920))
        assertEquals(1920 to 1080, FrameEncoder.scaledSize(3840, 2160, 1920))
        // 3040 x 1904: 1904 * 1920 / 3040 = 1202.5, rounded.
        assertEquals(1920 to 1203, FrameEncoder.scaledSize(3040, 1904, 1920))
    }

    @Test fun `over the cap on the height, the height becomes the cap`() {
        assertEquals(1080 to 1920, FrameEncoder.scaledSize(1440, 2560, 1920))
        assertEquals(864 to 1920, FrameEncoder.scaledSize(1080, 2400, 1920))
    }

    @Test fun `the cap is the caller's value, not a constant`() {
        assertEquals(1280 to 720, FrameEncoder.scaledSize(1920, 1080, 1280))
        assertEquals(1 to 100, FrameEncoder.scaledSize(1, 1000, 100))
    }
}
