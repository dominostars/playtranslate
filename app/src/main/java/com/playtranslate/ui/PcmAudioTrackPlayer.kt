package com.playtranslate.ui

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.playtranslate.audio.Loudness

private const val TAG = "PcmAudioTrackPlayer"

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
            // Normalize a COPY of the range (boost-only, RecordingPlayer's
            // Loudness pass): game mixes sit well below speech level, and on
            // the Thor the secondary display attenuates media further — an
            // unnormalized clip reads as "didn't play".
            val normalized = pcm.copyOfRange(start, end)
            Loudness.normalize(normalized)
            // Fast-track eligibility requires the SINK's native rate on most
            // builds — a 44.1 kHz track on a 48 kHz sink gets the low-latency
            // request silently denied and lands back on the (Thor-muted)
            // deep-buffer path. Resample to native before building the track.
            val nativeRate = AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC)
                .takeIf { it > 0 } ?: sampleRate
            val clip =
                if (nativeRate == sampleRate) normalized
                else resampleLinear(normalized, sampleRate, nativeRate)
            val minBuf = AudioTrack.getMinBufferSize(
                nativeRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
            ).coerceAtLeast(4096)
            val t = runCatching {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(nativeRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build(),
                    )
                    .setBufferSizeInBytes(minBuf * 2)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    // The load-bearing flag on the Thor: USAGE_MEDIA tracks
                    // default to the deep-buffer output, whose stream volumes
                    // this ROM pins to -inf (flinger-verified) — silence with
                    // frames delivered. Low-latency requests the primary/fast
                    // output, the path system TTS lands on (and why TTS was
                    // audible while we weren't). Harmless where ineligible:
                    // the platform silently falls back to a normal track.
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .build()
            }.getOrNull()
            if (t == null || t.state != AudioTrack.STATE_INITIALIZED) {
                Log.w(TAG, "AudioTrack init failed sr=$sampleRate state=${t?.state}")
                runCatching { t?.release() }
                return@Thread
            }
            track = t
            // Some ROMs leave a fresh track's mixer gain at -inf until set.
            runCatching { t.setVolume(1.0f) }
            Log.i(
                TAG,
                "playing ${clip.size} frames sr=$sampleRate->$nativeRate " +
                    "buf=${minBuf * 2} perf=${t.performanceMode}",
            )
            runCatching {
                t.play()
                var off = 0
                while (off < clip.size && generation == gen) {
                    val n = t.write(clip, off, minOf(nativeRate / 10, clip.size - off))
                    if (n <= 0) break
                    off += n
                    // Progress stays in SOURCE frame space — callers map it
                    // to ms with the rate they handed us.
                    val frame = start + (off.toLong() * sampleRate / nativeRate).toInt()
                    onProgress?.let { cb ->
                        mainHandler.post { if (generation == gen) cb(frame) }
                    }
                }
                // Let the buffered tail drain before teardown.
                while (generation == gen &&
                    t.playState == AudioTrack.PLAYSTATE_PLAYING &&
                    t.playbackHeadPosition < clip.size
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

    /** Linear-interpolation resample, mono PCM16. Plenty for speech preview;
     *  the card's encoded clip keeps the original samples. */
    private fun resampleLinear(src: ShortArray, from: Int, to: Int): ShortArray {
        val outLen = (src.size.toLong() * to / from).toInt().coerceAtLeast(1)
        val out = ShortArray(outLen)
        for (i in out.indices) {
            val srcPos = i.toLong() * from
            val idx = (srcPos / to).toInt().coerceAtMost(src.size - 1)
            val next = (idx + 1).coerceAtMost(src.size - 1)
            val frac = (srcPos % to).toDouble() / to
            out[i] = ((1 - frac) * src[idx] + frac * src[next]).toInt()
                .coerceIn(-32768, 32767).toShort()
        }
        return out
    }
}
