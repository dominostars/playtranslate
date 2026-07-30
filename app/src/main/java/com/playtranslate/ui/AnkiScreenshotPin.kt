package com.playtranslate.ui

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pins a screenshot for an Anki card flow.
 *
 * The capture surfaces write their frames to FIXED cache filenames
 * (`camera-snapshot.jpg`, `capture-d{displayId}.jpg`, `drag.jpg`),
 * overwritten by the next capture — but a card reads its screenshot at
 * UPLOAD time. Any capture in between attaches the WRONG frame to the
 * card: silent, persistent deck corruption. Two pin lifetimes close that
 * window from both ends:
 *
 *  - **Open-time** (the review sheets): pinned when the review flow
 *    opens, released on provably-final sheet teardown. Covers the
 *    open→Save window — e.g. an accidental icon-drag capture while the
 *    review screen is up (field report 2026-07-29).
 *  - **Send-time** (AnkiSendPipeline): pinned when the send starts,
 *    released when it completes. Covers the Save→upload window (audio
 *    synthesis + binder latency) for every path, including one-tap
 *    sends that never open a sheet. A send-time pin of an open-time pin
 *    is a second copy — that redundancy is what keeps a failed send
 *    retryable after the pipeline releases its own copy.
 *
 * Deliberately transient copies, NOT a retained screenshot history:
 * camera frames are arbitrary real-world content, and steady-state disk
 * keeps exactly the one live frame per surface it holds today.
 *
 * Failure degrades, never blocks: a failed copy returns the ORIGINAL path
 * (today's behavior), and [release] only ever deletes files it created.
 * Pins a crash orphaned are collected by [sweepStale] — run opportunistically
 * on every pin and once at app start — and the pin dir lives under cacheDir,
 * so the OS can purge it regardless. [STALE_AGE_MS] (1 h) comfortably
 * exceeds both pin lifetimes; a review sheet left open longer than that
 * only loses its pin if another send pins meanwhile (sweeps run on pin,
 * not on a timer).
 */
object AnkiScreenshotPin {
    private const val TAG = "AnkiScreenshotPin"
    private const val DIR = "anki-pins"

    /** Orphan cutoff: generous next to real send lifetimes (seconds), tight
     *  enough that a crash's stray frame doesn't outlive the session. */
    private const val STALE_AGE_MS = 60 * 60 * 1000L

    /** Same-millisecond send disambiguator. */
    private val counter = AtomicInteger()

    private fun dir(ctx: Context) = File(ctx.cacheDir, DIR)

    /** Copy [path] into the pin dir and return the pinned path, or [path]
     *  itself when there is nothing to pin or the copy fails. */
    fun pin(ctx: Context, path: String?): String? {
        if (path == null) return null
        return try {
            val src = File(path)
            if (!src.isFile) return path
            val d = dir(ctx).apply { mkdirs() }
            sweepStale(d)
            val dst = File(
                d,
                "pin-${System.currentTimeMillis()}-${counter.incrementAndGet()}.${src.extension.ifEmpty { "jpg" }}",
            )
            src.copyTo(dst, overwrite = true)
            dst.absolutePath
        } catch (e: Exception) {
            Log.w(TAG, "pin failed; sending from the live file", e)
            path
        }
    }

    /** True when [path] is a file this object created (lives in the pin
     *  dir). Lets a restored review sheet ADOPT a pin it wrote back into
     *  its arguments instead of pinning a copy and orphaning the
     *  original to the stale sweep. */
    fun isPin(ctx: Context, path: String?): Boolean {
        if (path == null) return false
        val f = File(path)
        return f.parentFile?.name == DIR && f.parentFile?.parentFile == ctx.cacheDir
    }

    /** Delete [path] iff it is a pin this object created — a passthrough
     *  original (null pin, failed copy) is never touched. */
    fun release(ctx: Context, path: String?) {
        if (isPin(ctx, path)) File(path!!).delete()
    }

    /** Delete pins older than [STALE_AGE_MS] — the crash/process-death
     *  backstop for pins whose finally never ran. */
    fun sweepStale(ctx: Context) = sweepStale(dir(ctx))

    private fun sweepStale(d: File) {
        val cutoff = System.currentTimeMillis() - STALE_AGE_MS
        d.listFiles()?.forEach { f ->
            if (f.isFile && f.lastModified() < cutoff) f.delete()
        }
    }
}
