package com.playtranslate.language

import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
 * surname, and a listed compound descends to its characters. Skipped unless
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
