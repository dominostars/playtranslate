package com.playtranslate.imageimport

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the pure downsample math behind [UprightImageDecoder]: smallest
 * power-of-two sample size landing the longest side at or under the cap.
 * The decode itself is Android-only and device-validated.
 */
class UprightImageDecoderTest {

    private fun sample(w: Int, h: Int, cap: Int = UprightImageDecoder.MAX_DIMENSION_PX) =
        UprightImageDecoder.sampleSizeFor(w, h, cap)

    @Test fun underCapStaysFullResolution() {
        assertEquals(1, sample(1920, 1080))
        assertEquals(1, sample(2560, 1440))
    }

    @Test fun capBindsOnTheLongestSide() {
        // 4000x3000 (12 MP): 4000/2 = 2000 <= 2560.
        assertEquals(2, sample(4000, 3000))
        assertEquals(2, sample(3000, 4000))
    }

    @Test fun hugePhotosStepInPowersOfTwo() {
        // 8160x6120 (50 MP): 8160/2=4080 too big, /4=2040 fits.
        assertEquals(4, sample(8160, 6120))
        // Extreme panorama: 20000/8 = 2500 fits.
        assertEquals(8, sample(20000, 800))
    }

    @Test fun degenerateCapNeverDivides() {
        assertEquals(1, sample(4000, 3000, cap = 0))
    }
}
