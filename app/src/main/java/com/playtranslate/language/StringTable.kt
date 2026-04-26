package com.playtranslate.language

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Mmap-backed reader for `strings.bin`. Layout: u32 LE count, u32 LE offset[N],
 * then `[varint len][utf8 bytes]` per string in ID order. IDs are 1-indexed;
 * ID 0 is the sentinel for "absent" and resolves to the empty string.
 *
 * Thread-safe: each [get] duplicates the underlying buffer before mutating
 * position, so concurrent lookups don't trample each other.
 */
class StringTable private constructor(
    private val body: ByteBuffer,
    private val offsets: IntArray,
) {
    fun get(id: Int): String {
        if (id == 0) return ""
        val view = body.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        view.position(offsets[id - 1])
        val len = readVarUInt(view)
        val bytes = ByteArray(len)
        view.get(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    companion object {
        fun open(file: File): StringTable {
            RandomAccessFile(file, "r").use { raf ->
                val full = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
                    .order(ByteOrder.LITTLE_ENDIAN)
                val count = full.getInt(0)
                val offsets = IntArray(count)
                for (i in 0 until count) offsets[i] = full.getInt(4 + 4 * i)
                val bodyStart = 4 + 4 * count
                val body = full.duplicate().apply { position(bodyStart) }.slice()
                return StringTable(body, offsets)
            }
        }
    }
}

internal fun readVarUInt(buf: ByteBuffer): Int {
    var result = 0
    var shift = 0
    while (true) {
        val b = buf.get().toInt() and 0xFF
        result = result or ((b and 0x7F) shl shift)
        if ((b and 0x80) == 0) return result
        shift += 7
        require(shift < 32) { "varint too long" }
    }
}
