package com.playtranslate.dictionary

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.language.LanguagePackStore
import com.playtranslate.language.PreloadResult
import com.playtranslate.language.SourceLangId
import com.playtranslate.language.SourceLanguageEngine
import com.playtranslate.language.SourceLanguageEngines
import com.playtranslate.language.TokenSpan
import com.playtranslate.yomitan.YomitanDataStore
import com.worksap.nlp.sudachi.Config
import com.worksap.nlp.sudachi.DictionaryFactory
import com.worksap.nlp.sudachi.Tokenizer
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files
import com.worksap.nlp.sudachi.Dictionary as SudachiDictionary

/**
 * JVM harness that runs the production Japanese pipeline against a real JA
 * pack and dumps what it produces, so a change to segmentation, the re-glob
 * fold, inflection tags or lookups has a before/after diff without a device.
 *
 * Skipped unless both environment variables are set:
 *  - `PLAYTRANSLATE_JA_PACK_DIR`: a pack directory holding `dict.sqlite`,
 *    `manifest.json` and `tokenizer/system_core.dic`
 *    (locally `local/packs-v3/packs/ja`).
 *  - `PLAYTRANSLATE_JA_DUMP_DIR`: where the dumps are written.
 *
 * Run from the repo root:
 * ```
 * PLAYTRANSLATE_JA_PACK_DIR=$PWD/local/packs-v3/packs/ja \
 * PLAYTRANSLATE_JA_DUMP_DIR=$PWD/seg-runs/<name> \
 *   ./gradlew :app:testDebugUnitTest --tests 'com.playtranslate.dictionary.JaPackDumpTest'
 * ```
 *
 * The pack's three files are symlinked one by one into
 * [LanguagePackStore.dirFor] in the Robolectric app, never the directory
 * itself: [LanguagePackStore.isInstalled] deletes the pack directory
 * recursively on a stale schema, `DictionaryManager.ensureOpen` deletes the
 * db file when its schema probe fails and writes a manifest when none
 * exists, and all three must act on links in the app's directory, not on
 * the pack. The engine then loads exactly as it does in the app:
 * [JapaneseEngine]'s constructor points the Sudachi provider at the
 * directory's `tokenizer/`, and [SourceLanguageEngine.preload] must
 * return [PreloadResult.Success] or the test fails.
 *
 * Dumps, all UTF-8 JSON:
 *  - `corpus.json`: per line of `app/src/androidTest/assets/p5_500_ja.json`,
 *    `{ja, tokens, raw, morphemes}`. `tokens` is [SourceLanguageEngine.tokenize]
 *    (surface, lookupForm and reading mean what they mean in the device
 *    `SegmentationBatchTest` dump, so `scripts/compare_segmentation.py` reads
 *    both, plus the inflection tag names). `raw` is
 *    [DictionaryManager.reglobSpansForTokens] over the same tokens, before
 *    [SentenceAnnotator] builds the display cover from it. `morphemes` is one
 *    record per tokenizer token, carrying all six Sudachi part-of-speech
 *    levels, which [JaToken] does not keep.
 *  - `survey.json`: the same shape over `ja/inflection_survey.txt`.
 *  - `lookups.json`: [DictionaryManager.lookup] for every distinct
 *    (lookupForm, reading) among the corpus tokens: the first entry's pack id,
 *    the entry count, and the first entry's first-sense parts of speech
 *    (where the lookup's deinflection stage injects its `[reason]`).
 *  - `timing.json`: [Deinflector.candidates] over every distinct corpus
 *    lookupForm, one warm-up pass then five timed passes. Taken inside the
 *    Robolectric sandbox, so it compares runs of this harness, not device cost.
 *
 * The Yomitan phrase oracle is off for every dump: the engine passes
 * `phraseOracle()`, which is null when no term dictionary is imported, and the
 * test asserts the Robolectric app has none; `raw` passes null directly.
 */
@RunWith(RobolectricTestRunner::class)
class JaPackDumpTest {

    @Test
    fun dumpJaPack() {
        val packPath = System.getenv(PACK_DIR_ENV)
        val dumpPath = System.getenv(DUMP_DIR_ENV)
        assumeTrue(
            "$PACK_DIR_ENV and $DUMP_DIR_ENV are not both set; skipping the JA pack dump",
            packPath != null && dumpPath != null,
        )
        val dumpDir = File(dumpPath!!)
        assertTrue("cannot create $dumpDir", dumpDir.isDirectory || dumpDir.mkdirs())

        val ctx: Context = ApplicationProvider.getApplicationContext()
        stagePack(ctx, File(packPath!!))

        runBlocking {
            val engine = SourceLanguageEngines.get(ctx, SourceLangId.JA)
            assertEquals(PreloadResult.Success, engine.preload())
            assertFalse(
                "the Robolectric app must have no imported term dictionary (phrase oracle off)",
                YomitanDataStore.hasTermDictionaries(ctx, SourceLangId.JA.yomitanConsumingLang()),
            )
            val dict = DictionaryManager.get(ctx)
            val dic = File(LanguagePackStore.dirFor(ctx, SourceLangId.JA), SYSTEM_DIC)
            DictionaryFactory().create(Config.defaultConfig().systemDictionary(dic.toPath())).use { sudachi ->
                val dumper = LineDumper(engine, dict, sudachi)

                val corpus = JSONArray(File(CORPUS_PATH).readText(Charsets.UTF_8))
                    .let { arr -> (0 until arr.length()).map { arr.getString(it) } }
                val corpusTokens = mutableListOf<TokenSpan>()
                writeTimed(File(dumpDir, "corpus.json")) {
                    JSONArray().apply {
                        for (line in corpus) put(dumper.dump(line, corpusTokens))
                    }.toString(2)
                }

                val survey = requireNotNull(javaClass.getResourceAsStream(SURVEY_RESOURCE)) {
                    "missing test resource $SURVEY_RESOURCE"
                }
                    .bufferedReader(Charsets.UTF_8).use { it.readLines() }
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                assertEquals("survey specimens must be distinct", survey.size, survey.toSet().size)
                writeTimed(File(dumpDir, "survey.json")) {
                    JSONArray().apply {
                        for (line in survey) put(dumper.dump(line, mutableListOf()))
                    }.toString(2)
                }

                writeTimed(File(dumpDir, "lookups.json")) {
                    JSONArray().apply {
                        for ((form, reading) in corpusTokens.map { it.lookupForm to it.reading }.distinct()) {
                            put(lookupRecord(dict, form, reading))
                        }
                    }.toString(2)
                }

                writeTimed(File(dumpDir, "timing.json")) {
                    JSONObject().put(
                        "deinflector",
                        deinflectorTiming(corpusTokens.map { it.lookupForm }.distinct()),
                    ).toString(2)
                }
            }
        }
    }

    /** Builds one dumped line. [sudachi] is opened with the same config as
     *  `SudachiJapaneseTokenizer.create`; its morphemes are asserted to line
     *  up with the provider's tokens, so `morphemes[i]` is the token that
     *  `raw[*].tokenStart` indexes. */
    private class LineDumper(
        private val engine: SourceLanguageEngine,
        private val dict: DictionaryManager,
        private val sudachi: SudachiDictionary,
    ) {
        suspend fun dump(line: String, tokensOut: MutableList<TokenSpan>): JSONObject {
            val spans = engine.tokenize(line)
            tokensOut += spans
            val tokens = SudachiJapaneseTokenizer.Provider.analyze(line)
            // Same morphemes the provider keeps: mode A, zero-width ones skipped.
            val morphemes = sudachi.create().tokenize(Tokenizer.SplitMode.A, line)
                .filter { it.surface().isNotEmpty() }
            assertEquals(
                "Sudachi morphemes must line up with the provider's tokens: $line",
                tokens.map { Triple(it.surface, it.begin, it.end) },
                morphemes.map { Triple(it.surface(), it.begin(), it.end()) },
            )
            val raw = dict.reglobSpansForTokens(tokens, phraseOracle = null)
            assertNotNull("re-glob returned null (database not open): $line", raw)

            return JSONObject()
                .put("ja", line)
                .put("tokens", JSONArray().apply {
                    for (s in spans) put(
                        JSONObject()
                            .put("surface", s.surface)
                            .put("lookupForm", s.lookupForm)
                            .put("reading", s.reading ?: JSONObject.NULL)
                            .put("inflections", JSONArray(s.inflections.map { it.name })),
                    )
                })
                .put("raw", JSONArray().apply {
                    for (r in raw!!) put(
                        JSONObject()
                            .put("tokenStart", r.tokenStart)
                            .put("tokenCount", r.tokenCount)
                            .put("surface", r.surface)
                            .put("lookupForm", r.lookupForm)
                            .put("reading", r.reading ?: JSONObject.NULL)
                            .put("tags", JSONArray(r.inflections.map { it.name }))
                            .put("isPhrase", r.isPhrase),
                    )
                })
                .put("morphemes", JSONArray().apply {
                    tokens.zip(morphemes).forEach { (t, m) ->
                        put(
                            JSONObject()
                                .put("surface", t.surface)
                                .put("dictionaryForm", t.dictionaryForm)
                                .put("normalizedForm", t.normalizedForm)
                                .put("pos", JSONArray(m.partOfSpeech().toList()))
                                .put("inflectionForm", t.inflectionForm ?: JSONObject.NULL)
                                .put("begin", t.begin)
                                .put("end", t.end),
                        )
                    }
                })
        }
    }

    private suspend fun lookupRecord(dict: DictionaryManager, form: String, reading: String?): JSONObject {
        val entries = dict.lookup(form, reading)?.entries.orEmpty()
        val first = entries.firstOrNull()
        return JSONObject()
            .put("lookupForm", form)
            .put("reading", reading ?: JSONObject.NULL)
            .put("firstPackId", first?.packId ?: JSONObject.NULL)
            .put("entryCount", entries.size)
            .put(
                "firstSensePos",
                first?.senses?.firstOrNull()?.let { JSONArray(it.partsOfSpeech) } ?: JSONObject.NULL,
            )
    }

    private fun deinflectorTiming(forms: List<String>): JSONObject {
        forms.forEach { Deinflector.candidates(it) }
        var totalNanos = 0L
        var maxNanos = 0L
        repeat(TIMED_PASSES) {
            for (form in forms) {
                val start = System.nanoTime()
                Deinflector.candidates(form)
                val elapsed = System.nanoTime() - start
                totalNanos += elapsed
                if (elapsed > maxNanos) maxNanos = elapsed
            }
        }
        return JSONObject()
            .put("distinctForms", forms.size)
            .put("meanMicros", totalNanos / 1_000.0 / (forms.size * TIMED_PASSES))
            .put("maxMicros", maxNanos / 1_000.0)
    }

    /** Builds a dump, writes it to [out] and logs the path and the build plus write time. */
    private suspend fun writeTimed(out: File, build: suspend () -> String) {
        val start = System.nanoTime()
        out.writeText(build(), Charsets.UTF_8)
        println("JaPackDumpTest: wrote ${out.absolutePath} in ${(System.nanoTime() - start) / 1_000_000} ms")
    }

    private fun stagePack(ctx: Context, pack: File) {
        val dir = LanguagePackStore.dirFor(ctx, SourceLangId.JA)
        for (name in listOf(DICT_DB, MANIFEST, SYSTEM_DIC)) {
            val source = File(pack, name).absoluteFile
            assertTrue("pack file missing: $source", source.isFile)
            val link = File(dir, name)
            assertTrue("cannot create ${link.parentFile}", link.parentFile!!.isDirectory || link.parentFile!!.mkdirs())
            Files.createSymbolicLink(link.toPath(), source.toPath())
        }
    }

    private companion object {
        const val PACK_DIR_ENV = "PLAYTRANSLATE_JA_PACK_DIR"
        const val DUMP_DIR_ENV = "PLAYTRANSLATE_JA_DUMP_DIR"
        const val DICT_DB = "dict.sqlite"
        const val MANIFEST = "manifest.json"
        const val SYSTEM_DIC = "tokenizer/system_core.dic"

        /** testDebugUnitTest runs with the module directory as its working directory. */
        const val CORPUS_PATH = "src/androidTest/assets/p5_500_ja.json"
        const val SURVEY_RESOURCE = "/ja/inflection_survey.txt"
        const val TIMED_PASSES = 5
    }
}
