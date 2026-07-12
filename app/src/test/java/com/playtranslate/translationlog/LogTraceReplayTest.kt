package com.playtranslate.translationlog

import android.graphics.Rect
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The validation harness for the translation-log write gate: replays a
 * recorded commit trace ([LogTraceRecorder] JSONL) through [LogWriteGate]
 * and renders the would-be log plus decision stats for human review.
 *
 * Two modes:
 *  - [fixtureTraceRoundTrips]: always runs; a synthetic session exercises
 *    format → loader → gate → report end-to-end with no device data.
 *  - [dumpRealTraces]: the actual validation. Gated on the
 *    `PLAYTRANSLATE_LOG_TRACE` environment variable naming a directory of
 *    pulled traces; for each `*.jsonl` it writes a sibling `*.dump.txt`
 *    (the would-be log + annotated decision stream + stats) and prints the
 *    summary. Run:
 *
 *    adb pull /sdcard/Android/data/com.playtranslate/files/log-traces ~/log-traces
 *    PLAYTRANSLATE_LOG_TRACE=~/log-traces ./gradlew :app:testDebugUnitTest \
 *        --tests "com.playtranslate.translationlog.LogTraceReplayTest"
 *
 *    The verdict is human: read the dump — is the log something you'd want
 *    to scroll? Stats frame it (entries/min, suppression mix), they don't
 *    decide it.
 */
@RunWith(RobolectricTestRunner::class)
class LogTraceReplayTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ── Report builder (the harness core) ────────────────────────────────

    private data class Report(
        val entries: List<LogWriteGate.Entry>,
        val offered: Int,
        val appended: Int,
        val replaced: Int,
        val suppressedNotSentence: Int,
        val suppressedDuplicate: Int,
        val suppressedNearDup: Int,
        val suppressedRegionMuted: Int,
        val text: String,
    )

    private fun replay(trace: LogTraceFormat.Trace): Report {
        val lang = trace.header?.sourceLang ?: "ja"
        val gate = LogWriteGate(lang)
        val log = mutableListOf<LogWriteGate.Entry>()
        var appended = 0; var replaced = 0; var notSentence = 0; var dup = 0; var offered = 0
        var nearDup = 0; var regionMuted = 0
        val startMs = trace.commits.firstOrNull()?.atMs ?: 0L
        val annotated = StringBuilder()

        fun stamp(atMs: Long): String {
            val s = (atMs - startMs) / 1000
            return "[%d:%02d]".format(s / 60, s % 60)
        }

        for (commit in trace.commits) {
            for (region in commit.regions) {
                offered++
                val bounds = Rect(region.l, region.t, region.r, region.b)
                val decision = gate.offer(region.text, bounds, commit.atMs, commit.cycle)
                val mark = when (decision) {
                    is LogWriteGate.Decision.Append -> {
                        log += decision.entry; appended++; "APPEND "
                    }
                    is LogWriteGate.Decision.Replace -> {
                        val i = log.indexOfLast { it === decision.previous }
                        if (i >= 0) log[i] = decision.entry else log += decision.entry
                        replaced++; "REPLACE"
                    }
                    is LogWriteGate.Decision.Suppress -> when (decision.reason) {
                        LogWriteGate.SuppressReason.NOT_SENTENCE -> { notSentence++; "-noise " }
                        LogWriteGate.SuppressReason.DUPLICATE -> { dup++; "-dup   " }
                        LogWriteGate.SuppressReason.NEAR_DUPLICATE -> { nearDup++; "-near  " }
                        LogWriteGate.SuppressReason.REGION_MUTED -> { regionMuted++; "-muted " }
                    }
                }
                annotated.append("${stamp(commit.atMs)} $mark ${region.text}\n")
            }
        }

        val spanMin = trace.commits.let {
            if (it.size < 2) 1.0 else ((it.last().atMs - it.first().atMs) / 60_000.0).coerceAtLeast(1 / 60.0)
        }
        val report = StringBuilder()
        report.append("=== WOULD-BE LOG (${log.size} entries, ")
        report.append("%.1f entries/min) ===\n".format(log.size / spanMin))
        for (e in log) report.append("${stamp(e.atMs)} ${e.text}\n")
        report.append("\n=== STATS ===\n")
        report.append("offered=$offered appended=$appended replaced=$replaced ")
        report.append("suppressed: notSentence=$notSentence duplicate=$dup ")
        report.append("nearDup=$nearDup regionMuted=$regionMuted\n")
        report.append("\n=== DECISION STREAM ===\n")
        report.append(annotated)

        return Report(
            log, offered, appended, replaced, notSentence, dup, nearDup, regionMuted,
            report.toString(),
        )
    }

    // ── Fixture self-test ────────────────────────────────────────────────

    @Test
    fun fixtureTraceRoundTrips() {
        val box = LogTraceFormat.TraceRegion("", 100, 800, 1800, 1000, 2)
        val hud = LogTraceFormat.TraceRegion("", 1600, 20, 1900, 80, 1)
        fun r(t: String, at: LogTraceFormat.TraceRegion) = at.copy(text = t)

        val file = tmp.newFile("fixture.jsonl")
        val lines = mutableListOf(
            LogTraceFormat.headerLine(
                LogTraceFormat.TraceHeader(startedAtMs = 0, displayId = 0, sourceLang = "ja"),
            ),
        )
        var cycle = 0
        fun commit(atMs: Long, vararg regions: LogTraceFormat.TraceRegion) {
            lines += LogTraceFormat.commitLine(
                LogTraceFormat.TraceCommit(cycle = ++cycle, atMs = atMs, regions = regions.toList()),
            )
        }
        commit(0, r("こんにち", box), r("12:41", hud))
        commit(700, r("こんにちは、世界のみなさん", box))
        commit(1400, r("こんにちは、世界のみなさん。", box))
        commit(5000, r("今日はいい天気ですね、出かけましょう。", box), r("12:42", hud))
        commit(65_000, r("こんにちは、世界のみなさん。", box))
        file.writeText(lines.joinToString("\n") + "\n")

        val trace = LogTraceFormat.load(file)
        assertEquals("ja", trace.header?.sourceLang)
        assertEquals(5, trace.commits.size)

        val report = replay(trace)
        // Typewriter trail collapsed, HUD clock suppressed twice, revisit deduped:
        assertEquals(
            listOf("こんにちは、世界のみなさん。", "今日はいい天気ですね、出かけましょう。"),
            report.entries.map { it.text },
        )
        assertEquals(1, report.replaced)
        assertEquals(3, report.suppressedNotSentence) // こんにち + two clocks
        assertEquals(1, report.suppressedDuplicate)
    }

    // ── Real-trace dump (the validation itself) ──────────────────────────

    @Test
    fun dumpRealTraces() {
        val dirPath = System.getenv("PLAYTRANSLATE_LOG_TRACE")
        assumeTrue("PLAYTRANSLATE_LOG_TRACE not set — skipping real-trace dump", dirPath != null)
        val dir = File(dirPath!!.replaceFirst("^~".toRegex(), System.getProperty("user.home")))
        assumeTrue("$dir is not a directory", dir.isDirectory)

        val traces = dir.listFiles { f -> f.name.endsWith(".jsonl") }?.sorted().orEmpty()
        assumeTrue("no *.jsonl traces in $dir", traces.isNotEmpty())

        for (traceFile in traces) {
            val trace = LogTraceFormat.load(traceFile)
            val report = replay(trace)
            val out = File(traceFile.parentFile, traceFile.nameWithoutExtension + ".dump.txt")
            out.writeText(report.text)
            println("── ${traceFile.name}: ${report.entries.size} entries " +
                "(offered=${report.offered}, noise=${report.suppressedNotSentence}, " +
                "dup=${report.suppressedDuplicate}, replaced=${report.replaced}) → ${out.name}")
            assertTrue(report.offered >= 0) // the assertion is the human reading the dump
        }
    }
}
