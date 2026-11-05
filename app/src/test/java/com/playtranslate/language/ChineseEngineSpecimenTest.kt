package com.playtranslate.language

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.model.HanziDetail
import com.playtranslate.yomitan.YomitanDataStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

/**
 * The production Chinese pipeline on the JVM against a real ZH pack: the
 * field specimen of 2026-11-04 (放在 and 包里 were taps with no definition)
 * tokenizes to dictionary words, 里 leads with "inside" and offers the
 * surname, a listed compound descends to its characters, and a word with
 * several readings leads with the one the app's table names
 * ([ChineseReadingDefaults]): 东西 is "thing" in the specimen itself, where
 * the pack's line order and HanLP's own reading both say "east and west".
 * Every row of the shipped table is checked against the pack. Skipped unless
 * [PACK_DIR_ENV] names an extracted pack (`local/packs-v3/packs/zh/zh.zip`:
 * dict.sqlite, manifest.json and the tokenizer directory), the pattern of
 * JaPackDumpTest; the pack files are linked into the Robolectric app's pack
 * directory one by one. HanLP's data comes from the pack through the
 * engine's adapter. The Yomitan phrase oracle is off: the Robolectric app
 * has no imported term dictionary, which the test asserts. The engine cache
 * and the manager singleton are reset first, since both outlive a test
 * class in the sandbox and ChineseDictionaryManagerTest binds the manager
 * to its own fixture.
 */
@RunWith(RobolectricTestRunner::class)
class ChineseEngineSpecimenTest {

    @Test fun `the specimen's unlisted terms narrow, 里 leads with inside, a compound descends`() {
        val packPath = System.getenv(PACK_DIR_ENV)
        assumeTrue("$PACK_DIR_ENV is not set; skipping the ZH pack specimen", packPath != null)
        val ctx: Context = ApplicationProvider.getApplicationContext()
        // The engine cache and the dictionary manager's singleton outlive a
        // test class in the Robolectric sandbox: a manager bound to another
        // class's fixture would answer here. Start from none.
        SourceLanguageEngines.releaseForPack(SourceLangId.ZH.packId)
        ChineseDictionaryManager::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        stagePack(ctx, File(packPath!!))

        runBlocking {
            val engine = SourceLanguageEngines.get(ctx, SourceLangId.ZH)
            assertEquals(PreloadResult.Success, engine.preload())
            assertFalse(
                "the Robolectric app must have no imported term dictionary (phrase oracle off)",
                YomitanDataStore.hasTermDictionaries(ctx, SourceLangId.ZH.yomitanConsumingLang()),
            )

            val surfaces = engine.tokenize(SPECIMEN).map { it.surface }
            for (word in listOf("放", "在", "包", "里", "这些", "常用", "东西")) {
                assertTrue("expected $word among $surfaces", word in surfaces)
            }
            for (fused in listOf("放在", "包里")) {
                assertFalse("expected $fused narrowed in $surfaces", fused in surfaces)
            }

            // The tokenizer-only depth asks no dictionary: HanLP's terms stand
            // there, and its per-character hints are the default depth's.
            val tokensOnly = engine.annotate(SPECIMEN, AnnotationDepth.TOKENS)
            val tokenSurfaces = tokensOnly.spans.map { it.surface }
            for (fused in listOf("放在", "包里")) {
                assertTrue("expected HanLP's $fused whole in $tokenSurfaces", fused in tokenSurfaces)
            }
            assertEquals(engine.annotate(SPECIMEN).hintAnnotations(), tokensOnly.hintAnnotations())

            val li = engine.lookup("里", null)!!.entries.single()
            assertEquals("lǐ", li.headwords.first().reading)
            assertEquals("lining", li.senses.first().targetDefinitions.first())
            val alternatives = engine.alternativesOf(TokenSpan("里", "里"), setOfNotNull(li.packId))
            assertEquals(listOf("Lǐ"), alternatives.map { it.reading })
            assertEquals("Li (surname)", engine.lookup("里", "Lǐ")!!.entries.single().senses.single().targetDefinitions.single())

            assertEquals("bāo", engine.lookup("包", null)!!.entries.single().headwords.first().reading)

            // A Traditional reader's alternatives for 里 stand on their own
            // forms, 裡 and 裏, with the surname last.
            val hant = SourceLanguageEngines.get(ctx, SourceLangId.ZH_HANT)
            val unit = hant.lookup("里", null)!!.entries.single()
            assertEquals(listOf("里"), unit.headwords.map { it.written })
            val hantAlternatives = hant.alternativesOf(TokenSpan("里", "里"), setOfNotNull(unit.packId))
            assertEquals(listOf("裡" to "lǐ", "裏" to "lǐ", "里" to "Lǐ"), hantAlternatives.map { it.surface to it.reading })
            assertEquals("lining", hant.lookup("裡", "lǐ")!!.entries.single().senses.first().targetDefinitions.first())

            val members = engine.memberWordsOf("常用", expressionClass = false, headwordReading = "cháng yòng")
            assertEquals(listOf("常" to "cháng", "用" to "yòng"), members.map { it.surface to it.reading })
            assertEquals(listOf("放", "在"), engine.memberWordsOf("放在", false, null).map { it.surface })

            // The lead-reading table against this pack: every row names a
            // reading the pack lists for that simplified headword, and it is
            // the reading an unhinted lookup leads with, so no row is dead.
            val dict = ChineseDictionaryManager.get(ctx)
            val rows = ctx.assets.open(ChineseReadingDefaults.ASSET).bufferedReader().readLines()
                .filter { it.isNotEmpty() && !it.startsWith("#") }.map { it.split('\t') }
            assertTrue("expected the full table, got ${rows.size} rows", rows.size >= 780)
            val notLeading = mutableListOf<String>()
            for ((surface, reading) in rows) {
                val groups = dict.alternativeGroups(surface, preferTraditional = false)
                assertTrue(
                    "$surface $reading is not a line of the pack",
                    groups.any { group -> group.lines.any { it.simplified == surface && it.reading == reading } },
                )
                if (groups.first().reading != PinyinFormatter.numberedToToneMarks(reading)) notLeading += surface
            }
            assertEquals(emptyList<String>(), notLeading)

            // The specimen's 东西: no hint (HanLP reads the word dōng xī in
            // every sentence, which is no context), and the lookup leads
            // with "thing", "east and west" one tap away.
            val dongxi = engine.tokenize(SPECIMEN).single { it.surface == "东西" }
            assertEquals(null, dongxi.reading)
            val thing = engine.lookup("东西", dongxi.reading)!!.entries.single()
            assertEquals("dōng xi", thing.headwords.first().reading)
            assertEquals("thing", thing.senses.single().targetDefinitions.first())
            assertEquals(
                listOf("dōng xī"),
                engine.alternativesOf(TokenSpan("东西", "东西"), setOfNotNull(thing.packId)).map { it.reading },
            )
            assertEquals("dōng xi", hant.lookup("東西", null)!!.entries.single().headwords.first().reading)
            // Words whose first listed reading is the rare one, looked up
            // with no sentence (the search screen).
            assertEquals("jié guǒ", engine.lookup("结果", null)!!.entries.single().headwords.first().reading)
            assertEquals("yào", engine.lookup("要", null)!!.entries.single().headwords.first().reading)
            assertEquals("pǎo", engine.lookup("跑", null)!!.entries.single().headwords.first().reading)
            // The detail page's Hanzi row reads the same lead.
            assertEquals("yào", (engine.lookupCharacter('要', "en") as HanziDetail).pinyin)
            // 得 after a verb: HanLP's standing dé is no context, the table's
            // particle leads.
            val de = engine.tokenize("他跑得很快").single { it.surface == "得" }
            assertEquals(null, de.reading)
            assertEquals("de", engine.lookup("得", de.reading)!!.entries.single().headwords.first().reading)
            // A reading the sentence changed still corrects: HanLP's phrase
            // 还钱 reads 还 huán where the word alone is hái.
            val huan = engine.tokenize("我还钱给他了").single { it.surface == "还" }
            assertEquals("huán", huan.reading)
            assertEquals("to pay back", engine.lookup("还", huan.reading)!!.entries.single().senses.single().targetDefinitions.first())
            // One occurrence the sentence changed (更易 read gēng) and two it
            // did not: no correction, and all three lead with "more".
            val geng = engine.tokenize("更易读、更灵敏、更稳定。").filter { it.surface == "更" }
            assertEquals(listOf<String?>(null, null, null), geng.map { it.reading })
            assertEquals("gèng", engine.lookup("更", null)!!.entries.single().headwords.first().reading)
            // A Traditional form under the Simplified profile has no row (the
            // table is keyed by simplified headword, and 於 leads with its own
            // rare word): HanLP's standing yú still counts, as before the table.
            assertEquals("yú", engine.tokenize("於我而言").single { it.surface == "於" }.reading)

            // The pack closed under the engine (an eviction while a capture is
            // still being read): the line still annotates, with HanLP's terms
            // left whole, and a lookup answers nothing; nothing throws.
            val field = ChineseDictionaryManager::class.java.getDeclaredField("db").apply { isAccessible = true }
            (field.get(ChineseDictionaryManager.get(ctx)) as android.database.sqlite.SQLiteDatabase).close()
            val afterClose = engine.tokenize(SPECIMEN).map { it.surface }
            assertTrue("expected HanLP's 放在 whole in $afterClose", "放在" in afterClose)
            assertEquals(null, engine.lookup("里", null))
            assertEquals(listOf("放", "在"), engine.memberWordsOf("放在", false, null).map { it.surface })
        }
    }

    private fun stagePack(ctx: Context, pack: File) {
        val dir = LanguagePackStore.dirFor(ctx, SourceLangId.ZH)
        assertTrue("cannot create $dir", dir.isDirectory || dir.mkdirs())
        for (name in listOf("dict.sqlite", "manifest.json", "tokenizer")) {
            val source = File(pack, name).absoluteFile
            assertTrue("pack file missing: $source", source.exists())
            Files.createSymbolicLink(File(dir, name).toPath(), source.toPath())
        }
    }

    private companion object {
        const val PACK_DIR_ENV = "PLAYTRANSLATE_ZH_PACK_DIR"
        const val SPECIMEN = "有,这些常用的东西我都放在包里了,我给你拿"
    }
}
