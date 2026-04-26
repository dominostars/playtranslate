package com.playtranslate.language

import org.apache.lucene.store.InputStreamDataInput
import org.apache.lucene.util.BytesRef
import org.apache.lucene.util.IntsRefBuilder
import org.apache.lucene.util.fst.FST
import org.apache.lucene.util.fst.PositiveIntOutputs
import org.apache.lucene.util.fst.Util
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Read-only accessor for a target-language gloss pack stored as
 * `index.fst` + `data.bin` + `strings.bin`. Implements the same
 * [TargetGlossLookup] contract (and reading-fallback rule) as the
 * legacy [TargetGlossDatabase] so callers don't change.
 *
 * Loads the FST entirely into heap (Lucene's default for the
 * single-stream constructor); mmaps the data and strings bodies.
 * Thread-safe: every lookup duplicates buffers before reading.
 */
class FstTargetGlossDatabase private constructor(
    private val fst: FST<Long>,
    private val data: ByteBuffer,
    private val strings: StringTable,
) : TargetGlossLookup {

    override fun lookup(sourceLang: String, written: String, reading: String?): List<TargetSense>? {
        val rows = lookupKey(sourceLang, written) ?: return null
        if (reading != null) {
            val matching = rows.filter { it.reading == reading }
            if (matching.isNotEmpty()) return matching.map { it.toSense() }
        }
        val empty = rows.filter { it.reading.isEmpty() }
        return if (empty.isEmpty()) null else empty.map { it.toSense() }
    }

    private fun lookupKey(sourceLang: String, written: String): List<DecodedRow>? {
        val scratch = IntsRefBuilder()
        Util.toIntsRef(BytesRef(makeKey(sourceLang, written)), scratch)
        val offset = Util.get(fst, scratch.get()) ?: return null
        return decodeBlock(offset)
    }

    private fun decodeBlock(offset: Long): List<DecodedRow> {
        val view = data.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        view.position(offset.toInt())
        val rowCount = readVarUInt(view)
        val out = ArrayList<DecodedRow>(rowCount)
        repeat(rowCount) {
            val readingId = readVarUInt(view)
            val senseOrd = readVarUInt(view)
            val posId = readVarUInt(view)
            val sourceId = readVarUInt(view)
            val glossCount = readVarUInt(view)
            val glossIds = IntArray(glossCount) { readVarUInt(view) }
            val miscId = readVarUInt(view)
            val exampleCount = readVarUInt(view)
            val exampleIds = IntArray(exampleCount * 2) { readVarUInt(view) }
            out.add(DecodedRow(
                reading = strings.get(readingId),
                senseOrd = senseOrd,
                posId = posId,
                sourceId = sourceId,
                glossIds = glossIds,
                miscId = miscId,
                exampleIds = exampleIds,
            ))
        }
        return out
    }

    private fun DecodedRow.toSense(): TargetSense = TargetSense(
        senseOrd = senseOrd,
        pos = strings.get(posId).split(',').filter { it.isNotBlank() },
        glosses = glossIds.map { strings.get(it) }.filter { it.isNotEmpty() },
        source = strings.get(sourceId),
        examples = buildList(exampleIds.size / 2) {
            for (i in exampleIds.indices step 2) {
                add(com.playtranslate.model.Example(
                    text = strings.get(exampleIds[i]),
                    translation = strings.get(exampleIds[i + 1]),
                ))
            }
        },
        misc = strings.get(miscId).split(',').filter { it.isNotBlank() },
    )

    fun close() {
        // FST byte array goes via GC; mmapped buffers don't need explicit unmap on Android.
    }

    private data class DecodedRow(
        val reading: String,
        val senseOrd: Int,
        val posId: Int,
        val sourceId: Int,
        val glossIds: IntArray,
        val miscId: Int,
        val exampleIds: IntArray,
    )

    companion object {
        fun open(packDir: File): FstTargetGlossDatabase? {
            val indexFst = File(packDir, "index.fst")
            val dataBin = File(packDir, "data.bin")
            val stringsBin = File(packDir, "strings.bin")
            if (!indexFst.exists() || !dataBin.exists() || !stringsBin.exists()) return null

            val dataIn = InputStreamDataInput(BufferedInputStream(FileInputStream(indexFst)))
            val fst = dataIn.use { FST(it, it, PositiveIntOutputs.getSingleton()) }

            val data = RandomAccessFile(dataBin, "r").use { raf ->
                raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
            }
            val strings = StringTable.open(stringsBin)
            return FstTargetGlossDatabase(fst, data, strings)
        }
    }
}

internal fun makeKey(sourceLang: String, written: String): ByteArray {
    val sl = sourceLang.toByteArray(Charsets.UTF_8)
    val w = written.toByteArray(Charsets.UTF_8)
    val out = ByteArray(sl.size + 1 + w.size)
    System.arraycopy(sl, 0, out, 0, sl.size)
    out[sl.size] = 0
    System.arraycopy(w, 0, out, sl.size + 1, w.size)
    return out
}
