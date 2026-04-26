package com.playtranslate.buildtools

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Frequency-sorted string intern table. IDs are 1-indexed (0 reserved for "absent").
 * Most-common strings get the smallest IDs so varint references in data.bin compress.
 */
class StringIntern {
    private val counts = HashMap<String, Int>()

    fun observe(s: String) {
        if (s.isEmpty()) return
        counts.merge(s, 1, Int::plus)
    }

    /** Returns the assigned IDs after freezing, in ID order (ID = index + 1). */
    fun freeze(): Frozen {
        val ordered = counts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key }
        val ids = HashMap<String, Int>(ordered.size * 2)
        ordered.forEachIndexed { i, s -> ids[s] = i + 1 }
        return Frozen(ordered, ids)
    }

    class Frozen(val byId: List<String>, private val idByString: Map<String, Int>) {
        fun id(s: String): Int = if (s.isEmpty()) 0 else idByString.getValue(s)
    }
}

fun writeStringsBin(out: OutputStream, frozen: StringIntern.Frozen) {
    val count = frozen.byId.size
    val header = ByteArrayOutputStream(4 + 4 * count)
    val headerBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)

    val body = ByteArrayOutputStream(count * 16)
    val offsets = IntArray(count)
    var cursor = 0
    for ((i, s) in frozen.byId.withIndex()) {
        offsets[i] = cursor
        val bytes = s.toByteArray(Charsets.UTF_8)
        val before = body.size()
        writeVarUInt(body, bytes.size)
        body.write(bytes)
        cursor += body.size() - before
    }

    headerBuf.clear().putInt(count).flip()
    header.write(headerBuf.array(), 0, 4)
    for (off in offsets) {
        headerBuf.clear().putInt(off).flip()
        header.write(headerBuf.array(), 0, 4)
    }

    header.writeTo(out)
    body.writeTo(out)
}

/** One row inside a (source_lang, written) group.
 *  [exampleIds] is paired (text_id, translation_id) — interleaved so each
 *  example is two consecutive entries. Translation ID 0 means no translation
 *  (monolingual example). Total length is always even. */
data class GlossRow(
    val readingId: Int,
    val senseOrd: Int,
    val posId: Int,
    val sourceId: Int,
    val glossIds: IntArray,
    val miscId: Int,
    val exampleIds: IntArray,
)

fun writeKeyBlock(out: OutputStream, rows: List<GlossRow>) {
    writeVarUInt(out, rows.size)
    for (r in rows) {
        writeVarUInt(out, r.readingId)
        writeVarUInt(out, r.senseOrd)
        writeVarUInt(out, r.posId)
        writeVarUInt(out, r.sourceId)
        writeVarUInt(out, r.glossIds.size)
        for (g in r.glossIds) writeVarUInt(out, g)
        writeVarUInt(out, r.miscId)
        require(r.exampleIds.size % 2 == 0) {
            "exampleIds must be paired (text, translation); got ${r.exampleIds.size}"
        }
        writeVarUInt(out, r.exampleIds.size / 2)
        for (id in r.exampleIds) writeVarUInt(out, id)
    }
}

fun writeVarUInt(out: OutputStream, value: Int) {
    require(value >= 0) { "varint values must be non-negative; got $value" }
    var v = value
    while ((v and 0x7F.inv()) != 0) {
        out.write((v and 0x7F) or 0x80)
        v = v ushr 7
    }
    out.write(v and 0x7F)
}

/** Streaming reader for varints written by [writeVarUInt]. */
class VarUIntReader(private val buf: ByteBuffer) {
    fun read(): Int {
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
}
