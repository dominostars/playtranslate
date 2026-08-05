package com.playtranslate

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.playtranslate.language.AnnotationDepth
import com.playtranslate.language.LanguagePackStore
import com.playtranslate.language.PreloadResult
import com.playtranslate.language.SourceLangId
import com.playtranslate.language.SourceLanguageEngines
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The live-cell measurement gate (sentence-annotation refactor doc §6): what
 * does FULL-depth annotation cost per OCR line on THIS device, cold (LRU
 * miss), warm (LRU hit), and under typewriter reveal (every prefix a fresh
 * string — the cache-defeating worst case)? TOKENS depth is the pre-refactor
 * live cost, measured as the baseline; WORDS isolates the re-glob's share.
 *
 * Run: ./gradlew :app:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.playtranslate.AnnotationLatencyBenchmark
 * Output: Log tag AnnotBench + /sdcard/Android/data/com.playtranslate/files/annotation_latency.json
 */
@RunWith(AndroidJUnit4::class)
class AnnotationLatencyBenchmark {

    private val TAG = "AnnotBench"

    // Game-shaped lines: short bark lines, mid dialogue, long prose — the mix
    // a live cycle actually annotates. All distinct so first-pass = all cold.
    private val jaLines = listOf(
        "一泊しますか？", "回復薬を手に入れた！", "セーブしますか？",
        "彼等はもう戻らない", "この先、危険につき立入禁止",
        "昨日、友達に聞いたんだけど、あの店のラーメンは本当に美味しいらしい",
        "王国の歴史は千年前の大戦から始まった", "十分な準備をしてから出発しよう",
        "研究所の地下には何かが隠されている", "大人気のゲームがついに発売された",
        "扉は固く閉ざされている", "鍵が必要だ", "北の塔へ向かえ",
        "商人は町の広場にいる", "夜になると魔物が現れる",
        "その剣は伝説の勇者のものだった", "宿屋で休みますか？一泊30ゴールドです",
        "アイテムを使いますか？", "経験値を100獲得した", "レベルが上がった！",
        "魔法を覚えた", "仲間が増えた", "冒険の書に記録しますか？",
        "彼女は静かに首を振った", "遠くから鐘の音が聞こえる",
        "この村には古い言い伝えがある", "湖の底に沈んだ都の話だ",
        "誰も帰ってきた者はいない", "それでも行くのか？", "覚悟はできている",
    )

    private val typewriterFull = "彼は長い沈黙のあとで、ゆっくりと真実を語り始めた"

    @Test
    fun measureLiveCell() = runBlocking<Unit> {
        val appCtx = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("JA pack not installed", LanguagePackStore.isInstalled(appCtx, SourceLangId.JA))
        val engine = SourceLanguageEngines.get(appCtx, SourceLangId.JA)
        assumeTrue("JA engine preload failed", engine.preload() == PreloadResult.Success)

        // JIT + dict warm-up, off the record.
        repeat(15) { engine.annotate("準備運動の文章です", AnnotationDepth.FULL) }
        repeat(15) { engine.annotate("準備運動の文章です", AnnotationDepth.TOKENS) }

        val report = JSONObject()

        fun record(name: String, samplesMs: List<Double>) {
            val sorted = samplesMs.sorted()
            fun pct(p: Double) = sorted[((sorted.size - 1) * p).toInt()]
            val o = JSONObject()
                .put("n", sorted.size)
                .put("p50_ms", "%.2f".format(pct(0.50)))
                .put("p95_ms", "%.2f".format(pct(0.95)))
                .put("max_ms", "%.2f".format(sorted.last()))
                .put("mean_ms", "%.2f".format(sorted.average()))
            report.put(name, o)
            Log.i(TAG, "$name  n=${sorted.size}  p50=${"%.2f".format(pct(0.50))}ms  " +
                "p95=${"%.2f".format(pct(0.95))}ms  max=${"%.2f".format(sorted.last())}ms")
        }

        suspend fun timeAll(
            lines: List<String>,
            depth: AnnotationDepth,
            eng: com.playtranslate.language.SourceLanguageEngine = engine,
        ): List<Double> =
            lines.map { line ->
                val t0 = System.nanoTime()
                eng.annotate(line, depth)
                (System.nanoTime() - t0) / 1e6
            }

        // TOKENS baseline — the pre-refactor live cost. (Uncached depth.)
        record("tokens_baseline", timeAll(jaLines, AnnotationDepth.TOKENS))
        // WORDS — adds the re-glob + membership queries. (Uncached depth.)
        record("words_reglob", timeAll(jaLines, AnnotationDepth.WORDS))
        // FULL cold — first sight of each line: the live cell's cold cost.
        record("full_cold", timeAll(jaLines, AnnotationDepth.FULL))
        // FULL warm — the LRU hit path live pays on settled screens.
        record("full_warm", timeAll(jaLines, AnnotationDepth.FULL))
        // Typewriter — every prefix a fresh string, per-cycle worst case.
        val prefixes = (1..typewriterFull.length).map { typewriterFull.take(it) }
        record("full_typewriter_prefixes", timeAll(prefixes, AnnotationDepth.FULL))

        // ZH side when the pack is present (heteronym pass is the new cost).
        if (LanguagePackStore.isInstalled(appCtx, SourceLangId.ZH)) {
            val zh = SourceLanguageEngines.get(appCtx, SourceLangId.ZH)
            if (zh.preload() == PreloadResult.Success) {
                val zhLines = listOf(
                    "你要在这里住一晚吗？", "他们已经不会回来了", "我昨天听朋友说这家店的拉面很好吃",
                    "商人在广场上等你", "夜里会有怪物出现", "把钥匙交给守卫",
                    "这个村子有古老的传说", "湖底沉睡着一座城", "没有人回来过", "你还要去吗？",
                )
                repeat(10) { zh.annotate("热身句子", AnnotationDepth.FULL) }
                record("zh_full_cold", timeAll(zhLines, AnnotationDepth.FULL, zh))
                record("zh_full_warm", timeAll(zhLines, AnnotationDepth.FULL, zh))
            }
        }

        File(appCtx.getExternalFilesDir(null), "annotation_latency.json")
            .writeText(report.toString(2))
        Log.i(TAG, "report written")
    }
}
