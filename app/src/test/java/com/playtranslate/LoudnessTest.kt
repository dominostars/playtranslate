package com.playtranslate

import com.playtranslate.audio.Loudness
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class LoudnessTest {

    private fun write(data: ByteArray, i: Int, v: Int) {
        val c = v.coerceIn(-32768, 32767)
        data[2 * i] = (c and 0xFF).toByte()
        data[2 * i + 1] = ((c shr 8) and 0xFF).toByte()
    }

    private fun read(data: ByteArray, i: Int): Int =
        (data[2 * i + 1].toInt() shl 8) or (data[2 * i].toInt() and 0xFF)

    private fun sine(amp: Double, n: Int = 4800): ByteArray {
        val data = ByteArray(n * 2)
        for (i in 0 until n) write(data, i, (amp * 32767 * sin(2 * PI * 440 * i / 48000.0)).toInt())
        return data
    }

    private fun rms(data: ByteArray): Double {
        val n = data.size / 2
        var s = 0.0
        for (i in 0 until n) { val x = read(data, i); s += x.toDouble() * x }
        return sqrt(s / n) / 32768.0
    }

    private fun peak(data: ByteArray): Int {
        val n = data.size / 2
        var p = 0
        for (i in 0 until n) { val a = kotlin.math.abs(read(data, i)); if (a > p) p = a }
        return p
    }

    private val ceiling = (0.96 * 32768).toInt()

    @Test fun `quiet clip is boosted toward target without clipping`() {
        val data = sine(0.03) // ~ -33 dBFS RMS
        val before = rms(data)
        Loudness.normalize(data)
        val after = rms(data)
        assertTrue("should boost substantially ($before -> $after)", after > before * 2)
        assertTrue("lands near target ($after)", after in 0.08..0.16)
        assertTrue("never clips past ceiling", peak(data) <= ceiling)
    }

    @Test fun `already-loud clip is left untouched`() {
        val data = sine(0.5) // ~ -9 dBFS RMS, above target
        val copy = data.copyOf()
        Loudness.normalize(data)
        assertArrayEquals(copy, data)
    }

    @Test fun `silence is untouched`() {
        val data = ByteArray(2000)
        Loudness.normalize(data)
        assertArrayEquals(ByteArray(2000), data)
    }

    @Test fun `peak limiter tames transients when boosting a peaky clip`() {
        val n = 4800
        val data = ByteArray(n * 2)
        for (i in 0 until n) write(data, i, (0.02 * 32767 * sin(2 * PI * 440 * i / 48000.0)).toInt())
        // full-scale spikes the boost would otherwise drive far past clipping
        write(data, 100, 32767)
        write(data, 2000, -32768)
        Loudness.normalize(data)
        assertTrue("transients soft-limited below ceiling", peak(data) <= ceiling)
        assertTrue("body still boosted", rms(data) > 0.05)
    }
}
