package com.playtranslate.buildtools

import org.apache.lucene.store.OutputStreamDataOutput
import org.apache.lucene.util.BytesRef
import org.apache.lucene.util.IntsRefBuilder
import org.apache.lucene.util.fst.Builder
import org.apache.lucene.util.fst.FST
import org.apache.lucene.util.fst.PositiveIntOutputs
import org.apache.lucene.util.fst.Util
import java.io.OutputStream

/**
 * Builds a Lucene FST&lt;Long&gt; over byte-keyed entries with monotonic Long outputs.
 * Keys must be added in strictly increasing lexicographic byte order. The encoder
 * relies on SQLite's BINARY collation under `ORDER BY source_lang, written` to
 * deliver groups in that order, so no in-memory sort is needed.
 */
class FstWriter {
    private val outputs = PositiveIntOutputs.getSingleton()
    private val builder = Builder(FST.INPUT_TYPE.BYTE1, outputs)
    private val scratch = IntsRefBuilder()

    fun add(keyBytes: ByteArray, output: Long) {
        Util.toIntsRef(BytesRef(keyBytes), scratch)
        builder.add(scratch.get(), output)
    }

    fun finish(): FST<Long> = builder.finish()
}

fun writeFst(out: OutputStream, fst: FST<Long>) {
    val dataOut = OutputStreamDataOutput(out)
    fst.save(dataOut, dataOut)
}
