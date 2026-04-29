package com.playtranslate.buildtools

import org.apache.lucene.store.InputStreamDataInput
import org.apache.lucene.util.BytesRef
import org.apache.lucene.util.IntsRefBuilder
import org.apache.lucene.util.fst.FST
import org.apache.lucene.util.fst.PositiveIntOutputs
import org.apache.lucene.util.fst.Util
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.sql.DriverManager

object SelfTest {
    fun run(jdbcUrl: String, indexFst: File, dataBin: File, stringsBin: File) {
        val dataIn = InputStreamDataInput(BufferedInputStream(FileInputStream(indexFst)))
        val fst = FST(dataIn, dataIn, PositiveIntOutputs.getSingleton())
        val data = mmapReadOnly(dataBin)
        val strings = mmapReadOnly(stringsBin)
        val table = StringTableLite(strings)
        val scratch = IntsRefBuilder()

        DriverManager.getConnection(jdbcUrl).use { c ->
            c.createStatement().use { st ->
                st.fetchSize = 1000
                val rs = st.executeQuery(
                    "SELECT source_lang, written, reading, sense_ord, pos, source, glosses, " +
                        "       examples, example_trans, misc " +
                        "FROM glosses ORDER BY source_lang, written, reading, sense_ord"
                )
                var curKey: ByteArray? = null
                var curRows = ArrayList<GlossRowDecoded>(8)
                var curIdx = 0
                var rowsChecked = 0L
                while (rs.next()) {
                    val sl = rs.getString(1)
                    val w = rs.getString(2)
                    val key = makeKey(sl, w)
                    if (curKey == null || !key.contentEquals(curKey)) {
                        Util.toIntsRef(BytesRef(key), scratch)
                        val offset = Util.get(fst, scratch.get())
                            ?: error("FST lookup miss for key ${sl}/${w}")
                        curRows = decodeBlock(data, offset.toInt(), table)
                        curIdx = 0
                        curKey = key
                    }
                    val expectedReading = rs.getString(3) ?: ""
                    val expectedSenseOrd = rs.getInt(4)
                    val expectedPos = rs.getString(5) ?: ""
                    val expectedSource = rs.getString(6) ?: ""
                    val expectedGlossesRaw = rs.getString(7) ?: ""
                    val expectedGlosses = if (expectedGlossesRaw.isEmpty()) emptyList()
                    else expectedGlossesRaw.split('\t').filter { it.isNotEmpty() }
                    val expectedExamplesRaw = rs.getString(8) ?: ""
                    val expectedExampleTransRaw = rs.getString(9) ?: ""
                    val expectedExampleTexts = if (expectedExamplesRaw.isEmpty()) emptyList()
                    else expectedExamplesRaw.split('\t')
                    val expectedExampleTrans = if (expectedExampleTransRaw.isEmpty()) emptyList()
                    else expectedExampleTransRaw.split('\t')
                    val expectedExamples = expectedExampleTexts.mapIndexed { i, t ->
                        Pair(t, expectedExampleTrans.getOrNull(i) ?: "")
                    }
                    val expectedMisc = rs.getString(10) ?: ""

                    require(curIdx < curRows.size) {
                        "block at ${sl}/${w} ran out of rows (idx=$curIdx, size=${curRows.size})"
                    }
                    val got = curRows[curIdx]
                    if (got.reading != expectedReading
                        || got.senseOrd != expectedSenseOrd
                        || got.pos != expectedPos
                        || got.source != expectedSource
                        || got.glosses != expectedGlosses
                        || got.misc != expectedMisc
                        || got.examples != expectedExamples
                    ) {
                        error(
                            "self-test mismatch at ${sl}/${w}/${expectedReading}#${expectedSenseOrd}\n" +
                                "  expected: pos=$expectedPos source=$expectedSource glosses=$expectedGlosses\n" +
                                "            misc=$expectedMisc examples=$expectedExamples\n" +
                                "  got:      pos=${got.pos} source=${got.source} glosses=${got.glosses}\n" +
                                "            misc=${got.misc} examples=${got.examples}"
                        )
                    }
                    curIdx++
                    rowsChecked++
                    if (rowsChecked % 1_000_000L == 0L) println("  $rowsChecked rows verified")
                }
                println("  self-test: $rowsChecked rows OK")
            }
        }
    }

    private fun decodeBlock(data: ByteBuffer, offset: Int, table: StringTableLite): ArrayList<GlossRowDecoded> {
        val view = data.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        view.position(offset)
        val r = VarUIntReader(view)
        val rowCount = r.read()
        val out = ArrayList<GlossRowDecoded>(rowCount)
        repeat(rowCount) {
            val readingId = r.read()
            val senseOrd = r.read()
            val posId = r.read()
            val sourceId = r.read()
            val glossCount = r.read()
            val glosses = ArrayList<String>(glossCount)
            repeat(glossCount) { glosses.add(table.get(r.read())) }
            val miscId = r.read()
            val exampleCount = r.read()
            val examples = ArrayList<Pair<String, String>>(exampleCount)
            repeat(exampleCount) {
                val text = table.get(r.read())
                val tr = table.get(r.read())
                examples.add(Pair(text, tr))
            }
            out.add(
                GlossRowDecoded(
                    reading = table.get(readingId),
                    senseOrd = senseOrd,
                    pos = table.get(posId),
                    source = table.get(sourceId),
                    glosses = glosses,
                    misc = table.get(miscId),
                    examples = examples,
                )
            )
        }
        return out
    }
}

private data class GlossRowDecoded(
    val reading: String,
    val senseOrd: Int,
    val pos: String,
    val source: String,
    val glosses: List<String>,
    val misc: String,
    val examples: List<Pair<String, String>>,
)

/** Build-time lite reader for strings.bin (no Android class deps; runtime version lives in app/). */
internal class StringTableLite(private val buf: ByteBuffer) {
    private val offsets: IntArray
    init {
        val view = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val count = view.getInt(0)
        offsets = IntArray(count)
        for (i in 0 until count) offsets[i] = view.getInt(4 + 4 * i)
    }
    fun get(id: Int): String {
        if (id == 0) return ""
        val view = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val absolute = 4 + 4 * offsets.size + offsets[id - 1]
        view.position(absolute)
        val len = VarUIntReader(view).read()
        val bytes = ByteArray(len)
        view.get(bytes)
        return String(bytes, Charsets.UTF_8)
    }
}

private fun mmapReadOnly(file: File): ByteBuffer {
    FileChannel.open(file.toPath(), StandardOpenOption.READ).use { ch ->
        return ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size())
    }
}
