package com.playtranslate.translationlog

import android.util.Log
import com.playtranslate.CaptureService
import com.playtranslate.Prefs
import com.playtranslate.ScanlineReconciler
import java.io.File
import java.util.concurrent.Executors

private const val TAG = "LogTraceRecorder"

/**
 * Debug-gated ([Prefs.debugLogTrace]) recorder of the live pipeline's
 * commit stream: every post-TypewriterGate `toTranslate` region set, one
 * JSONL line per committing cycle, under
 * `<external-files>/log-traces/trace-<startMs>-D<display>.jsonl`.
 *
 * Purpose: the offline feed for validating the translation-log write gate
 * ([LogWriteGate]) on REAL sessions before the log feature grows a store or
 * UI — pull the file, point LogTraceReplayTest at it, read the would-be
 * log. Zero effect when the pref is off (null instance, no hook cost
 * beyond a null check); writes are append-only on a private daemon thread,
 * so a commit never blocks the capture loop's main-thread cycle.
 */
class LogTraceRecorder private constructor(private val file: File) {

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, TAG).apply { isDaemon = true }
    }

    private fun append(line: String) {
        executor.execute {
            runCatching { file.appendText(line + "\n") }
                .onFailure { Log.w(TAG, "trace append failed: ${it.message}") }
        }
    }

    fun onCommit(cycle: Int, atMs: Long, regions: List<ScanlineReconciler.Region>) {
        writeCommit(cycle, atMs, regions.map {
            LogTraceFormat.TraceRegion(
                text = it.text,
                l = it.bounds.left, t = it.bounds.top,
                r = it.bounds.right, b = it.bounds.bottom,
                lineCount = it.lineCount,
            )
        })
    }

    /** [com.playtranslate.PinholeOverlayMode]'s commit stream — the
     *  far-group set placed and translated this cycle (its analog of the
     *  reconciler's toTranslate). */
    fun onCommitGroups(cycle: Int, atMs: Long, groups: List<com.playtranslate.FarGroup>) {
        writeCommit(cycle, atMs, groups.map {
            LogTraceFormat.TraceRegion(
                text = it.text,
                l = it.bounds.left, t = it.bounds.top,
                r = it.bounds.right, b = it.bounds.bottom,
                lineCount = it.lineCount,
            )
        })
    }

    private fun writeCommit(cycle: Int, atMs: Long, regions: List<LogTraceFormat.TraceRegion>) {
        append(LogTraceFormat.commitLine(LogTraceFormat.TraceCommit(cycle = cycle, atMs = atMs, regions = regions)))
    }

    companion object {
        /** Null unless the debug pref is on — the hook site stays a bare
         *  null check on every cycle. */
        fun createIfEnabled(service: CaptureService, displayId: Int): LogTraceRecorder? {
            val prefs = Prefs(service)
            if (!prefs.debugLogTrace) return null
            val startMs = System.currentTimeMillis()
            val dir = File(service.getExternalFilesDir(null), "log-traces")
            if (!dir.exists() && !dir.mkdirs()) {
                Log.w(TAG, "cannot create $dir; trace disabled")
                return null
            }
            val file = File(dir, "trace-$startMs-D$displayId.jsonl")
            val recorder = LogTraceRecorder(file)
            recorder.append(
                LogTraceFormat.headerLine(
                    LogTraceFormat.TraceHeader(
                        startedAtMs = startMs,
                        displayId = displayId,
                        sourceLang = prefs.sourceLangId.code,
                    ),
                ),
            )
            Log.i(TAG, "recording commit trace to $file")
            return recorder
        }
    }
}
