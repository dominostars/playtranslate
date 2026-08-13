package com.playtranslate.yomitan

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Per-row zlib for the `term_sc` structured-glossary blobs. Structured
 * glossary JSON compresses ~3x (measured on Jitendex: ~494MB raw →
 * ~175MB deflated), which is the difference between an acceptable and an
 * absurd yomitan.sqlite on a Jitendex-scale import. Level 6 = zlib's
 * default speed/ratio tradeoff; the read path (a handful of rows per
 * lookup, each a few KB) inflates in microseconds. Pure JVM
 * ([FreqData]/[KanjiData] discipline).
 */
internal object Zlib {

    fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION)
        return try {
            deflater.setInput(data)
            deflater.finish()
            val out = ByteArrayOutputStream(data.size / 3 + 64)
            val buf = ByteArray(8192)
            while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf))
            out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    /** Inflates a [deflate] blob; null on corrupt input (the caller treats
     *  the row as flat-only rather than failing the lookup). */
    fun inflate(data: ByteArray): ByteArray? {
        val inflater = Inflater()
        return try {
            inflater.setInput(data)
            val out = ByteArrayOutputStream(data.size * 3 + 64)
            val buf = ByteArray(8192)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n > 0) {
                    out.write(buf, 0, n)
                } else if (!inflater.finished()) {
                    // No output and not done: truncated input (needsInput),
                    // preset-dictionary stream (needsDictionary), or stuck —
                    // all unusable. Checked AFTER finished(): an empty
                    // payload's single inflate() call emits 0 bytes and
                    // finishes in one step, and needsInput() is true there
                    // too.
                    return null
                }
            }
            out.toByteArray()
        } catch (_: java.util.zip.DataFormatException) {
            null
        } finally {
            inflater.end()
        }
    }
}
