package com.playtranslate.camera

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.fail
import org.junit.Test

/** Pins the YUV_420_888 → NV21 packer across the plane layouts real HALs
 *  produce — planar and semi-planar chroma, padded row strides, buffers
 *  with no trailing padding after the last row — and documents that a
 *  buffer violating the size contract THROWS rather than silently
 *  mispacking, which is exactly what [FreezeFrameRing]'s containment
 *  boundary exists to absorb (ring disabled, freeze falls back to the
 *  post-tap frame). */
class FreezeFramePackerTest {

    private val w = 4
    private val h = 4
    private val cw = w / 2
    private val ch = h / 2

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private fun pack(
        yBuf: ByteBuffer,
        yStride: Int,
        uBuf: ByteBuffer,
        vBuf: ByteBuffer,
        cStride: Int,
        cPixel: Int,
    ): ByteArray {
        val out = ByteArray(w * h + cw * ch * 2)
        FreezeFrameRing.packNv21(
            out, w, h, yBuf, yStride, uBuf, vBuf, cStride, cPixel,
            uRow = ByteArray(cStride), vRow = ByteArray(cStride),
        )
        return out
    }

    @Test
    fun planarChromaTightStrides() {
        val y = ByteArray(w * h) { it.toByte() }
        val u = bytes(100, 101, 102, 103)
        val v = bytes(200, 201, 202, 203)
        val out = pack(ByteBuffer.wrap(y), w, ByteBuffer.wrap(u), ByteBuffer.wrap(v), cw, 1)
        assertArrayEquals(y + bytes(200, 100, 201, 101, 202, 102, 203, 103), out)
    }

    @Test
    fun semiPlanarInterleavedChroma() {
        // One VU-interleaved backing array — the layout budget HALs hand
        // CameraX: V plane at even offsets, U at odd, both pixelStride 2
        // with rowStride w. The packer must reproduce it as NV21 verbatim.
        val y = ByteArray(w * h) { it.toByte() }
        val vu = bytes(200, 100, 201, 101, 202, 102, 203, 103)
        val vBuf = ByteBuffer.wrap(vu)
        val uBuf = ByteBuffer.wrap(vu, 1, vu.size - 1).slice()
        val out = pack(ByteBuffer.wrap(y), w, uBuf, vBuf, cStride = w, cPixel = 2)
        assertArrayEquals(y + vu, out)
    }

    @Test
    fun paddedRowStridesWithoutTrailingPadding() {
        // Row strides exceed the row width and every buffer ENDS at its
        // last row's final sample — the exact shape real HAL buffers take.
        // The packer must read row prefixes only and never touch padding.
        val yStride = w + 2
        val y = ByteArray((h - 1) * yStride + w)
        for (r in 0 until h) for (c in 0 until w) y[r * yStride + c] = (r * w + c).toByte()
        val cStride = cw + 2
        val u = ByteArray((ch - 1) * cStride + cw)
        val v = ByteArray((ch - 1) * cStride + cw)
        for (r in 0 until ch) for (c in 0 until cw) {
            u[r * cStride + c] = (100 + r * cw + c).toByte()
            v[r * cStride + c] = (200 + r * cw + c).toByte()
        }
        val out = pack(ByteBuffer.wrap(y), yStride, ByteBuffer.wrap(u), ByteBuffer.wrap(v), cStride, 1)
        assertArrayEquals(
            ByteArray(w * h) { it.toByte() } + bytes(200, 100, 201, 101, 202, 102, 203, 103),
            out,
        )
    }

    @Test
    fun truncatedLumaBufferThrows() {
        // A buffer violating the YUV_420_888 size contract must throw
        // (BufferUnderflow/IllegalArgument depending on path), never
        // silently mispack — the ring's push boundary catches it and
        // disables the ring for the session.
        val y = ByteBuffer.wrap(ByteArray(w * h / 2))
        val u = ByteBuffer.wrap(ByteArray(cw * ch))
        val v = ByteBuffer.wrap(ByteArray(cw * ch))
        try {
            pack(y, w, u, v, cw, 1)
            fail("expected a throw from the truncated luma buffer")
        } catch (expected: RuntimeException) {
            // BufferUnderflowException or IllegalArgumentException by path.
        }
    }
}
