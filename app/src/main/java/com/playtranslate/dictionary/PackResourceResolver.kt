package com.playtranslate.dictionary

import com.atilika.kuromoji.ipadic.Tokenizer
import com.atilika.kuromoji.util.ResourceResolver
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

/**
 * A Kuromoji [ResourceResolver] that reads IPADIC binary files from a
 * filesystem directory (the installed JA source pack's `tokenizer/` dir)
 * instead of the classpath. Enables moving the ~33 MB IPADIC bin files out
 * of the APK and into the per-language downloadable pack.
 *
 * Kuromoji's upstream [com.atilika.kuromoji.util.SimpleResourceResolver]
 * calls `clazz.getResourceAsStream(resourceName)` with names that have no
 * directory prefix (they resolve relative to the class's package). We mirror
 * that naming: the pack directory is a flat tree of the same file names
 * (`doubleArrayTrie.bin`, `connectionCosts.bin`, etc.).
 *
 * Classpath fallback exists so that builds where the bins haven't been
 * stripped from the APK yet (dev builds, intermediate phases of the migration)
 * continue to work without requiring a pack install.
 */
internal class PackResourceResolver(private val packDir: File) : ResourceResolver {
    @Throws(IOException::class)
    override fun resolve(resourceName: String): InputStream {
        // Upstream passes the bare file name ("doubleArrayTrie.bin") — strip
        // any leading slash and take only the basename just in case.
        val basename = resourceName.removePrefix("/").substringAfterLast('/')
        val packFile = File(packDir, basename)
        if (packFile.isFile) {
            return FileInputStream(packFile)
        }
        // Classpath fallback (dev-mode, pre-strip APK, partial install).
        val cp = Tokenizer::class.java.getResourceAsStream(resourceName)
            ?: throw IOException(
                "Kuromoji resource $resourceName not found in pack dir ${packDir.absolutePath} " +
                    "or on classpath"
            )
        return cp
    }
}
