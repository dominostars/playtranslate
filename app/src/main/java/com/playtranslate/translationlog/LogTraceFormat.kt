package com.playtranslate.translationlog

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * JSONL schema for translation-log traces: one header line, then one line
 * per live-mode cycle that committed regions (post-[com.playtranslate.StabilityHold]
 * `toTranslate` — the exact stream the future log's write gate consumes).
 * Written on-device by [LogTraceRecorder]; read offline by
 * LogTraceReplayTest, which replays it through [LogWriteGate] and renders
 * the would-be log for human review.
 *
 * [json] pins `encodeDefaults = true` so the discriminating `type` field is
 * actually written (the app-wide PtJson omits defaults, which would emit
 * untyped, unloadable records) and `ignoreUnknownKeys = true` so old dumps
 * stay loadable across schema growth.
 */
object LogTraceFormat {

    const val VERSION = 1

    @Serializable
    data class TraceHeader(
        val type: String = "header",
        val version: Int = VERSION,
        val startedAtMs: Long,
        val displayId: Int,
        val sourceLang: String,
    )

    @Serializable
    data class TraceRegion(
        val text: String,
        val l: Int,
        val t: Int,
        val r: Int,
        val b: Int,
        val lineCount: Int,
    )

    @Serializable
    data class TraceCommit(
        val type: String = "commit",
        val cycle: Int,
        val atMs: Long,
        val regions: List<TraceRegion>,
    )

    val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun headerLine(h: TraceHeader): String = json.encodeToString(TraceHeader.serializer(), h)
    fun commitLine(c: TraceCommit): String = json.encodeToString(TraceCommit.serializer(), c)

    data class Trace(val header: TraceHeader?, val commits: List<TraceCommit>)

    /** Lenient loader: unknown/garbled lines are skipped, not fatal — a
     *  trace cut short by process death must still replay. */
    fun load(file: File): Trace {
        var header: TraceHeader? = null
        val commits = mutableListOf<TraceCommit>()
        file.forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            val type = runCatching {
                json.parseToJsonElement(line).jsonObject["type"]?.jsonPrimitive?.content
            }.getOrNull() ?: return@forEachLine
            when (type) {
                "header" -> runCatching {
                    header = json.decodeFromString(TraceHeader.serializer(), line)
                }
                "commit" -> runCatching {
                    commits += json.decodeFromString(TraceCommit.serializer(), line)
                }
            }
        }
        return Trace(header, commits)
    }
}
