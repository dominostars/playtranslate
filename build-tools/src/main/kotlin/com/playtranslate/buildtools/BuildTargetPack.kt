package com.playtranslate.buildtools

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.sql.DriverManager
import java.sql.ResultSet
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.system.measureTimeMillis

private data class ManifestFile(val path: String, val size: Long, val sha256: String?)
private data class ManifestLicense(val component: String, val license: String, val attribution: String)
private data class Manifest(
    val langId: String,
    val schemaVersion: Int,
    val packVersion: Int,
    val appMinVersion: Int,
    val files: List<ManifestFile>,
    val totalSize: Long,
    val licenses: List<ManifestLicense>,
)

fun main(args: Array<String>) {
    val opts = parseArgs(args)
    val inputDb = File(opts.input)
    val outDir = File(opts.output)
    require(inputDb.isFile) { "input not found: $inputDb" }
    outDir.mkdirs()

    Class.forName("org.sqlite.JDBC")
    val jdbcUrl = "jdbc:sqlite:${inputDb.absolutePath}"
    val sourceManifest = File(inputDb.parentFile, "manifest.json")
    require(sourceManifest.isFile) { "input manifest.json missing next to ${inputDb.name}" }

    println("[1/5] Pass 1 — collecting strings...")
    val stringIds: StringIntern.Frozen
    val stringsBin = File(outDir, "strings.bin")
    measureTimeMillis {
        val intern = StringIntern()
        DriverManager.getConnection(jdbcUrl).use { c ->
            c.createStatement().use { st ->
                st.fetchSize = 1000
                val rs = st.executeQuery(
                    "SELECT pos, source, glosses, reading, examples, example_trans, misc FROM glosses"
                )
                var rows = 0
                while (rs.next()) {
                    intern.observe(rs.getString(1) ?: "")
                    intern.observe(rs.getString(2) ?: "")
                    val glosses = rs.getString(3) ?: ""
                    if (glosses.isNotEmpty()) {
                        for (g in glosses.split('\t')) intern.observe(g)
                    }
                    intern.observe(rs.getString(4) ?: "")
                    val examples = rs.getString(5) ?: ""
                    if (examples.isNotEmpty()) {
                        for (e in examples.split('\t')) intern.observe(e)
                    }
                    val exTrans = rs.getString(6) ?: ""
                    if (exTrans.isNotEmpty()) {
                        for (t in exTrans.split('\t')) intern.observe(t)
                    }
                    intern.observe(rs.getString(7) ?: "")
                    if (++rows % 1_000_000 == 0) println("  $rows rows scanned")
                }
                println("  $rows rows scanned total")
            }
        }
        stringIds = intern.freeze()
        println("  ${stringIds.byId.size} distinct strings")
    }.also { println("  pass 1: ${it} ms") }

    println("[2/5] Writing strings.bin...")
    BufferedOutputStream(FileOutputStream(stringsBin)).use { writeStringsBin(it, stringIds) }
    println("  ${humanBytes(stringsBin.length())}")

    println("[3/5] Pass 2 — emitting data.bin and index.fst...")
    val dataBin = File(outDir, "data.bin")
    val indexFst = File(outDir, "index.fst")
    var keys = 0L
    var rowsEmitted = 0L
    measureTimeMillis {
        val fstWriter = FstWriter()
        BufferedOutputStream(FileOutputStream(dataBin)).use { dataOut ->
            val countingOut = CountingOutputStream(dataOut)
            DriverManager.getConnection(jdbcUrl).use { c ->
                c.createStatement().use { st ->
                    st.fetchSize = 1000
                    val rs = st.executeQuery(
                        "SELECT source_lang, written, reading, sense_ord, pos, source, glosses, " +
                            "       examples, example_trans, misc " +
                            "FROM glosses ORDER BY source_lang, written, reading, sense_ord"
                    )
                    var curKey: ByteArray? = null
                    var curRows = ArrayList<GlossRow>(8)
                    var curOffset = 0L
                    while (rs.next()) {
                        val sl = rs.getString(1)
                        val w = rs.getString(2)
                        val key = makeKey(sl, w)
                        if (curKey == null || !key.contentEquals(curKey)) {
                            if (curKey != null) {
                                fstWriter.add(curKey, curOffset)
                                writeKeyBlock(countingOut, curRows)
                                keys++
                                rowsEmitted += curRows.size
                                curOffset = countingOut.count
                                curRows = ArrayList(8)
                            } else {
                                curOffset = countingOut.count
                            }
                            curKey = key
                        }
                        curRows.add(rowFromCursor(rs, stringIds))
                    }
                    if (curKey != null) {
                        fstWriter.add(curKey, curOffset)
                        writeKeyBlock(countingOut, curRows)
                        keys++
                        rowsEmitted += curRows.size
                    }
                }
            }
        }
        BufferedOutputStream(FileOutputStream(indexFst)).use { writeFst(it, fstWriter.finish()) }
    }.also { println("  pass 2: ${it} ms") }
    println("  $keys keys, $rowsEmitted rows")
    println("  data.bin=${humanBytes(dataBin.length())}, index.fst=${humanBytes(indexFst.length())}")

    println("[4/5] Self-test (re-streaming SQLite, verifying every row)...")
    measureTimeMillis {
        SelfTest.run(jdbcUrl, indexFst, dataBin, stringsBin)
    }.also { println("  self-test: ${it} ms") }

    println("[5/5] Writing manifest.json + zip...")
    val src = Gson().fromJson(sourceManifest.readText(), Manifest::class.java)
    val files = listOf(indexFst, dataBin, stringsBin).map {
        ManifestFile(path = it.name, size = it.length(), sha256 = sha256Hex(it))
    }
    val totalSize = files.sumOf { it.size }
    val newManifest = src.copy(files = files, totalSize = totalSize)
    val manifestFile = File(outDir, "manifest.json")
    manifestFile.writeText(GsonBuilder().setPrettyPrinting().create().toJson(newManifest))

    val zipFile = File(outDir, "${langCode(src.langId)}.zip")
    ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zip ->
        for (f in listOf(manifestFile, indexFst, dataBin, stringsBin)) {
            zip.putNextEntry(ZipEntry(f.name))
            f.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }
    println("  manifest=${manifestFile.length()}B, zip=${humanBytes(zipFile.length())}")

    val srcSize = inputDb.length()
    println()
    println("==== Summary ====")
    println("input  ${humanBytes(srcSize)}  (glosses.sqlite)")
    println("output ${humanBytes(totalSize)}  (index.fst + data.bin + strings.bin)")
    println("ratio  ${"%.1f".format(100.0 * totalSize / srcSize)}% of source SQLite")
    println("zip    ${humanBytes(zipFile.length())}")
}

private fun rowFromCursor(rs: ResultSet, ids: StringIntern.Frozen): GlossRow {
    val reading = rs.getString(3) ?: ""
    val senseOrd = rs.getInt(4)
    val pos = rs.getString(5) ?: ""
    val source = rs.getString(6) ?: ""
    val glossesRaw = rs.getString(7) ?: ""
    val glossList = if (glossesRaw.isEmpty()) emptyList()
    else glossesRaw.split('\t').filter { it.isNotEmpty() }
    val glossIds = IntArray(glossList.size) { ids.id(glossList[it]) }

    val examplesRaw = rs.getString(8) ?: ""
    val exampleTransRaw = rs.getString(9) ?: ""
    val exampleTexts = if (examplesRaw.isEmpty()) emptyList()
    else examplesRaw.split('\t')
    // example_trans column is positionally aligned with examples — splitting
    // an empty string yields [""], which is fine; alignment is preserved
    // because we cap at the texts' length below.
    val exampleTrans = if (exampleTransRaw.isEmpty()) emptyList()
    else exampleTransRaw.split('\t')
    val exampleIds = IntArray(exampleTexts.size * 2)
    for (i in exampleTexts.indices) {
        exampleIds[i * 2] = ids.id(exampleTexts[i])
        val tr = exampleTrans.getOrNull(i) ?: ""
        exampleIds[i * 2 + 1] = ids.id(tr)
    }

    return GlossRow(
        readingId = ids.id(reading),
        senseOrd = senseOrd,
        posId = ids.id(pos),
        sourceId = ids.id(source),
        glossIds = glossIds,
        miscId = ids.id(rs.getString(10) ?: ""),
        exampleIds = exampleIds,
    )
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

private class CountingOutputStream(private val inner: java.io.OutputStream) : java.io.OutputStream() {
    var count: Long = 0
        private set
    override fun write(b: Int) { inner.write(b); count++ }
    override fun write(b: ByteArray, off: Int, len: Int) { inner.write(b, off, len); count += len }
    override fun flush() = inner.flush()
    override fun close() = inner.close()
}

private data class Args(val input: String, val output: String)

private fun parseArgs(args: Array<String>): Args {
    var input: String? = null
    var output: String? = null
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--input" -> { input = args[++i] }
            "--output" -> { output = args[++i] }
            else -> error("unknown argument: ${args[i]}")
        }
        i++
    }
    require(input != null && output != null) { "usage: --input <glosses.sqlite> --output <dir>" }
    return Args(input, output)
}

private fun sha256Hex(file: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { ins ->
        val buf = ByteArray(64 * 1024)
        while (true) { val n = ins.read(buf); if (n <= 0) break; md.update(buf, 0, n) }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

private fun humanBytes(n: Long): String {
    if (n < 1024) return "${n}B"
    val units = listOf("KB", "MB", "GB", "TB")
    var v = n.toDouble() / 1024
    var u = 0
    while (v >= 1024 && u < units.lastIndex) { v /= 1024; u++ }
    return "%.2f %s".format(v, units[u])
}

private fun langCode(langId: String): String =
    if (langId.startsWith("target-")) langId.removePrefix("target-") else langId
