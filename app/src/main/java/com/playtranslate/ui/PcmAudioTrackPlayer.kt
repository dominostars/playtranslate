package com.playtranslate.ui

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper

/**
 * Plays a range of raw mono PCM16 through an [AudioTrack] — the trim editor's
 * scrub/selection player. No decode step (the caller already holds the
 * snapshot's PCM), no audio focus request (deliberate, matching
 * [com.playtranslate.audio.RecordingPlayer] — transient focus self-ducks our
 * own stream to silence on some devices).
 *
 * One clip in flight; [play] preempts, [stop] is idempotent. Callbacks arrive
 * on main and stop firing the moment a newer generation preempts them.
 */
class PcmAudioTrackPlayer(private val sampleRate: Int) {

    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var generation = 0
    @Volatile private var track: AudioTrack? = null

    val playing: Boolean get() = track != null

    /**
     * Play [pcm] from [startFrame] until [endFrame]. [onProgress] receives the
     * absolute frame index reached (throttled to write-chunk granularity);
     * [onDone] fires once on natural completion — not on preemption/stop.
     */
    fun play(
        pcm: ShortArray,
        startFrame: Int,
        endFrame: Int,
        onProgress: ((Int) -> Unit)? = null,
        onDone: (() -> Unit)? = null,
    ) {
        stop()
        val gen = ++generation
        val start = startFrame.coerceIn(0, pcm.size)
        val end = endFrame.coerceIn(start, pcm.size)
        if (end == start) return
        Thread({
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
            )
            val t = try {
                AudioTrack(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                    maxOf(minBuf, sampleRate / 2 * 2), // ≥ 0.5 s
                    AudioTrack.MODE_STREAM,
                    AudioManager.AUDIO_SESSION_ID_GENERATE,
                )
            } catch (_: Exception) {
                return@Thread
            }
            track = t
            // Some ROMs leave a fresh track's mixer gain at -inf until set.
            runCatching { t.setVolume(1.0f) }
            runCatching {
                t.play()
                var off = start
                while (off < end && generation == gen) {
                    val n = t.write(pcm, off, minOf(sampleRate / 10, end - off))
                    if (n <= 0) break
                    off += n
                    val frame = off
                    onProgress?.let { cb ->
                        mainHandler.post { if (generation == gen) cb(frame) }
                    }
                }
                // Let the buffered tail drain before teardown.
                val total = end - start
                while (generation == gen &&
                    t.playState == AudioTrack.PLAYSTATE_PLAYING &&
                    t.playbackHeadPosition < total
                ) {
                    Thread.sleep(15)
                }
            }
            runCatching { t.stop() }
            runCatching { t.release() }
            if (track === t) track = null
            if (generation == gen) {
                mainHandler.post { if (generation == gen) onDone?.invoke() }
            }
        }, "PcmAudioTrackPlayer").start()
    }

    fun stop() {
        generation++
        // A write() blocked on a full buffer only returns once the track is
        // paused+flushed; the render thread then releases it.
        runCatching { track?.pause() }
        runCatching { track?.flush() }
    }
}
