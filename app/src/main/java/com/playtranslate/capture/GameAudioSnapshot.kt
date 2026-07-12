package com.playtranslate.capture

import android.content.Context
import java.io.File

/**
 * Per-card, immutable-once-written snapshot files of the game-audio ring —
 * the buffers sentence-card flows trim from.
 *
 * OWNERSHIP MODEL (the architectural fix for the snapshot-churn bug class):
 * each card flow gets its own `snap-<millis>.wav`, written once by
 * [GameAudioRecorder.snapshotToFile] and never modified; the owning
 * [com.playtranslate.ui.SentenceAnkiContentFragment] deletes it only on
 * provably-final teardown (onDestroyView with the activity finishing, or
 * with no saved state at all — either way no restore can reference the
 * file). A restorable saved-state teardown keeps the file for the
 * restored fragment to re-own, and
 * [sweepOrphans] reaps files whose restore never happened. A selection's
 * locator pins the exact file, so a later card can never clobber the audio
 * an earlier card trimmed and approved — the whole family of "wrong/missing
 * audio after snapshot churn" races is structurally impossible rather than
 * guarded against at every consumer. The mtime baked into selection keys
 * remains as cache-key identity and as a fail-closed backstop for the
 * residual cases (OS cache purge, orphan sweep of a >6 h zombie sheet).
 *
 * Deliberately NOT in [com.playtranslate.audio.AudioCache]: a ~16 MB
 * snapshot would evict real pronunciation clips from the 64 MB LRU.
 */
object GameAudioSnapshot {

    /** Everything before the PCM payload — the standard 44-byte WAV header. */
    private const val WAV_HEADER_BYTES = 44L
    private const val PREFIX = "snap-"

    /** Crash leftovers older than this are reaped by [sweepOrphans]. A live
     *  lingering sheet is hours-fresh; its file survives. */
    private const val ORPHAN_MAX_AGE_MS = 6L * 60 * 60 * 1000

    /** The snapshot backing the card flow the user is currently in — set by
     *  the sentence fragment on create/resume, cleared on its destroy. Lets
     *  context-free resolution paths (the audio picker's source list, the
     *  trim editor's fallback) find "the current card's buffer" without
     *  threading a path through AudioRequest. */
    @Volatile
    var active: File? = null

    fun dir(ctx: Context): File = File(ctx.cacheDir, "game-audio")

    /** A fresh, unique snapshot path for one card flow. */
    fun newFile(ctx: Context): File =
        File(dir(ctx), "$PREFIX${System.currentTimeMillis()}.wav")

    /** True when [f] is a snapshot with actual audio (not just a header). */
    fun isUsable(f: File?): Boolean =
        f != null && f.exists() && f.length() > WAV_HEADER_BYTES

    /** Delete snapshot files no card flow can still own. Called from the
     *  recorder's snapshot path (already on IO). */
    fun sweepOrphans(ctx: Context) {
        val now = System.currentTimeMillis()
        dir(ctx).listFiles()?.forEach { f ->
            if (f.name.startsWith(PREFIX) && now - f.lastModified() > ORPHAN_MAX_AGE_MS) {
                f.delete()
            }
        }
    }
}
