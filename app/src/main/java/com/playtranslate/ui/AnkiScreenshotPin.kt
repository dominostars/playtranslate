package com.playtranslate.ui

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pins a screenshot for an in-flight Anki card send.
 *
 * The capture surfaces write their frames to FIXED cache filenames
 * (`camera-snapshot.jpg`, `capture-d{displayId}.jpg`), overwritten by the
 * next capture — but a card send reads its screenshot at UPLOAD time, after
 * multi-second audio synthesis and AnkiDroid binder latency. Rapid
 * capture→card→capture use could therefore attach the NEXT frame to the
 * previous card: silent, persistent deck corruption. Pinning copies the file
 * to a send-private temp at the moment the send pipeline starts — the card
 * attaches what the user was looking at when they acted — and deletes it
 * when the send completes. Deliberately a transient copy, NOT a retained
 * screenshot history: camera frames are arbitrary real-world content, and
 * steady-state disk keeps exactly the one live frame per surface it holds
 * today.
 *
 * Failure degrades, never blocks: a failed copy returns the ORIGINAL path
 * (today's behavior), and [release] only ever deletes files it created.
 * Pins a crash orphaned are collected by [sweepStale] — run opportunistically
 * on every pin and once at app start — and the pin dir lives under cacheDir,
 * so the OS can purge it regardless.
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

    /** Delete [path] iff it is a pin this object created — a passthrough
     *  original (null pin, failed copy) is never touched. */
    fun release(ctx: Context, path: String?) {
        if (path == null) return
        val f = File(path)
        if (f.parentFile?.name == DIR && f.parentFile?.parentFile == ctx.cacheDir) {
            f.delete()
        }
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
