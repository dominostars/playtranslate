package com.playtranslate.capture

import android.content.Context
import java.io.File

/**
 * The frozen game-audio buffer a sentence-card flow trims from — a single
 * self-clobbering WAV (mono PCM16 44.1 kHz), written by
 * [GameAudioRecorder.snapshotToFile] when a card opens and overwritten by the
 * next card. Deliberately NOT in [com.playtranslate.audio.AudioCache]: a
 * ~16 MB snapshot would evict real pronunciation clips from the 64 MB LRU.
 * Mirrors the [CaptureCache] one-file-per-key pattern instead.
 */
object GameAudioSnapshot {

    /** Everything before the PCM payload — the standard 44-byte WAV header. */
    private const val WAV_HEADER_BYTES = 44L

    fun file(ctx: Context): File =
        File(File(ctx.cacheDir, "game-audio"), "snapshot.wav")

    /** True when a snapshot with actual audio (not just a header) is on disk. */
    fun exists(ctx: Context): Boolean =
        file(ctx).let { it.exists() && it.length() > WAV_HEADER_BYTES }

    fun delete(ctx: Context) {
        file(ctx).delete()
    }
}
