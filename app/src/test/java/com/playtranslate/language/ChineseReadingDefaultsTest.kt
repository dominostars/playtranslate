package com.playtranslate.language

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * [ChineseReadingDefaults]: the table's format, and the table the app
 * ships (`assets/zh_reading_defaults.tsv`, built by
 * `scripts/build_zh_reading_defaults.py`): it loads, every row is a surface
 * and a numbered reading, it carries the licence notice of the data it is
 * derived from, and it holds every hand decision in
 * `scripts/zh_reading_defaults_curated.tsv`, so an edit there that was not
 * rebuilt into the asset fails here. Whether each row names a reading the
 * pack lists is checked against the real pack in [ChineseEngineSpecimenTest].
 */
@RunWith(RobolectricTestRunner::class)
class ChineseReadingDefaultsTest {

    @Test fun `rows are surface and reading, comments, blanks and broken rows are skipped`() {
        val table = ChineseReadingDefaults.parse(sequenceOf(
            "# a comment, as the licence header is",
            "# 东西\tdong1 xi1 is still a comment",
            "",
            "东西\tdong1 xi5",
            "要\tyao4\tan extra field is ignored",
            "no tab here",
            "\tyao1",
            "空\t",
        ))
        assertEquals(2, table.size)
        assertEquals("dong1 xi5", table.leadReading("东西"))
        assertEquals("yao4", table.leadReading("要"))
        assertNull(table.leadReading("空"))
        assertNull(table.leadReading("no tab here"))
        assertNull(ChineseReadingDefaults.NONE.leadReading("东西"))
    }

    @Test fun `the shipped table loads and names the common readings`() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val table = ChineseReadingDefaults.load(ctx)
        assertTrue("expected the full table, got ${table.size} rows", table.size >= 780)
        // The field report's word, and words whose first listed reading is the rare one.
        assertEquals("dong1 xi5", table.leadReading("东西"))
        assertEquals("jie2 guo3", table.leadReading("结果"))
        assertEquals("yao4", table.leadReading("要"))
        assertEquals("shang4", table.leadReading("上"))
        assertEquals("ma5", table.leadReading("吗"))
        assertEquals("pao3", table.leadReading("跑"))
        // The standalone word, where the character's customary reading is another.
        assertEquals("jiao1", table.leadReading("教"))
        assertEquals("de5", table.leadReading("得"))
        // A reading that only points at a compound is passed over: 漂 is piāo
        // "to float", not the piào of 漂亮.
        assertEquals("piao1", table.leadReading("漂"))
    }

    @Test fun `every shipped row is a surface and a numbered reading, under the data's licence notice`() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val lines = ctx.assets.open(ChineseReadingDefaults.ASSET).bufferedReader().readLines()
        val header = lines.takeWhile { it.startsWith("#") }
        assertTrue("the Unicode licence notice must ship with the table", header.any { "UNICODE LICENSE V3" in it })
        assertTrue(header.any { "Copyright © 1991-" in it && "Unicode, Inc." in it })
        assertTrue(header.any { "Permission is hereby granted" in it })
        val rows = lines.drop(header.size)
        // "dong1", "r5", and the pack's spelling of ü: "lu:4", "nu:e4".
        val syllable = Regex("[a-z:]+[1-5]")
        val seen = HashSet<String>()
        for (row in rows) {
            val fields = row.split('\t')
            assertEquals("two fields in '$row'", 2, fields.size)
            assertTrue("surface in '$row'", fields[0].isNotEmpty() && fields[0].none { it.isWhitespace() })
            assertTrue("numbered reading in '$row'", fields[1].split(' ').all { syllable.matches(it) })
            assertTrue("one row per surface: '$row'", seen.add(fields[0]))
        }
        assertEquals(ChineseReadingDefaults.load(ctx).size, rows.size)
    }

    @Test fun `the shipped table holds every hand decision`() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val table = ChineseReadingDefaults.load(ctx)
        val curated = curatedFile().readLines().filter { it.isNotEmpty() && !it.startsWith("#") }
        assertTrue("expected the hand decisions, got ${curated.size}", curated.size >= 170)
        for (row in curated) {
            val fields = row.split('\t')
            assertTrue("surface, reading, basis in '$row'", fields.size >= 3)
            when (fields[2]) {
                "judgment", "segmenter" -> assertEquals("rebuild the asset: ${fields[0]}", fields[1], table.leadReading(fields[0]))
                // A word the decisions keep out of the table.
                "none" -> assertNull("rebuild the asset: ${fields[0]}", table.leadReading(fields[0]))
                else -> throw AssertionError("basis in '$row'")
            }
        }
    }

    /** `scripts/zh_reading_defaults_curated.tsv`, found from the test's
     *  working directory (the module) upward. */
    private fun curatedFile(): File {
        var dir: File? = File(System.getProperty("user.dir")!!).absoluteFile
        while (dir != null) {
            val file = File(dir, "scripts/zh_reading_defaults_curated.tsv")
            if (file.isFile) return file
            dir = dir.parentFile
        }
        throw AssertionError("scripts/zh_reading_defaults_curated.tsv not found above ${System.getProperty("user.dir")}")
    }
}
