package com.playtranslate.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Plays a downloaded recording by decoding it to PCM ([MediaExtractor] +
 * [MediaCodec], so wav/ogg/mp3 are handled uniformly) and streaming it through
 * an [AudioTrack]. A single in-flight track; [stop] releases it.
 * [PronunciationPlayer] owns the public stop authority and calls this plus
 * `TtsEngine.stop()` so the two backends can't talk over each other.
 *
 * We deliberately do NOT request audio focus here: on some devices (observed on
 * the AYN Thor) requesting `AUDIOFOCUS_GAIN_TRANSIENT` with a SPEECH content type
 * makes the platform duck this app's OWN media track to silence. The recordings
 * are short pronunciation clips, so playing without grabbing focus is the right
 * trade — it just won't duck other apps' background audio for the ~1s clip.
 */
object RecordingPlayer {

    private const val TAG = "PtAudio"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var track: AudioTrack? = null
    /** Bumped by [stop]/each [play]; an in-flight write loop bails when its
     *  captured value goes stale, so a superseded clip can't keep writing. */
    @Volatile private var generation = 0

    fun stop() {
        generation++
        track?.let { t ->
            runCatching { t.pause() }
            runCatching { t.flush() }
            runCatching { t.stop() }
            runCatching { t.release() }
        }
        track = null
    }

    /**
     * Plays [file]. With [awaitCompletion] = false returns [PlayOutcome.Played]
     * once playback starts (and [onStart] fires); with true, returns after the
     * clip finishes draining. Any decode/playback failure → [PlayOutcome.Failed]
     * (recoverable, so the resolver falls back to TTS).
     */
    suspend fun play(
        ctx: Context,
        file: File,
        awaitCompletion: Boolean,
        onStart: (() -> Unit)?,
    ): PlayOutcome = withContext(Dispatchers.IO) {
        withContext(Dispatchers.Main) { stop() }
        val myGen = generation

        val pcm = runCatching { decodeToPcm(file) }.getOrNull()
        if (pcm == null || pcm.data.isEmpty() || pcm.sampleRate <= 0) {
            android.util.Log.w(TAG, "RecordingPlayer decode failed/empty file=${file.name}")
            return@withContext PlayOutcome.Failed(recoverable = true)
        }

        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val channelMask =
            if (pcm.channelCount >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(pcm.sampleRate)
            .setChannelMask(channelMask)
            .build()
        val minBuf = AudioTrack.getMinBufferSize(pcm.sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
            .coerceAtLeast(4096)
        val newTrack = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(format)
                .setBufferSizeInBytes(minBuf * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrNull()
        if (newTrack == null || newTrack.state != AudioTrack.STATE_INITIALIZED) {
            runCatching { newTrack?.release() }
            android.util.Log.w(TAG, "AudioTrack init failed sr=${pcm.sampleRate} ch=${pcm.channelCount}")
            return@withContext PlayOutcome.Failed(recoverable = true)
        }
        // Some ROMs leave a freshly-built track's mixer gain at -inf until set.
        runCatching { newTrack.setVolume(1.0f) }
        // A play()/stop() could have raced in while we decoded; honor it.
        if (generation != myGen) { runCatching { newTrack.release() }; return@withContext PlayOutcome.Failed(recoverable = true) }
        track = newTrack

        if (awaitCompletion) {
            renderBlocking(newTrack, pcm, myGen, onStart)
            PlayOutcome.Played
        } else {
            scope.launch { renderBlocking(newTrack, pcm, myGen, onStart) }
            PlayOutcome.Played
        }
    }

    /** Writes the whole PCM buffer to [t] (blocking), waits for it to drain, then
     *  releases — unless a newer clip ([generation] moved past [myGen]) preempts. */
    private fun renderBlocking(t: AudioTrack, pcm: Pcm, myGen: Int, onStart: (() -> Unit)?) {
        runCatching {
            t.play()
            onStart?.let { cb -> mainHandler.post { if (generation == myGen) cb() } }
            val frameBytes = 2 * pcm.channelCount.coerceAtLeast(1)
            val totalFrames = pcm.data.size / frameBytes
            var off = 0
            while (off < pcm.data.size && generation == myGen) {
                val n = t.write(pcm.data, off, pcm.data.size - off)
                if (n <= 0) break
                off += n
            }
            // Let the buffered tail finish before tearing the track down.
            while (generation == myGen &&
                t.playState == AudioTrack.PLAYSTATE_PLAYING &&
                t.playbackHeadPosition < totalFrames
            ) {
                Thread.sleep(15)
            }
        }
        if (generation == myGen) {
            runCatching { t.stop() }
            runCatching { t.release() }
            if (track === t) track = null
        }
    }

    private class Pcm(val data: ByteArray, val sampleRate: Int, val channelCount: Int)

    /** Decodes [file] to 16-bit PCM. Returns null on any failure. */
    private fun decodeToPcm(file: File): Pcm? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            var trackIndex = -1
            var inputFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    trackIndex = i; inputFormat = f; break
                }
            }
            if (trackIndex < 0 || inputFormat == null) return null
            extractor.selectTrack(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: return null
            var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channelCount = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            val out = ByteArrayOutputStream()
            val info = MediaCodec.BufferInfo()
            var sawInputEOS = false
            var sawOutputEOS = false
            while (!sawOutputEOS) {
                if (!sawInputEOS) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)
                        val sz = if (inBuf != null) extractor.readSampleData(inBuf, 0) else -1
                        if (sz < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEOS = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, sz, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx >= 0 -> {
                        val outBuf = codec.getOutputBuffer(outIdx)
                        if (outBuf != null && info.size > 0) {
                            val chunk = ByteArray(info.size)
                            outBuf.position(info.offset)
                            outBuf.get(chunk, 0, info.size)
                            out.write(chunk)
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEOS = true
                    }
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val of = codec.outputFormat
                        if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sampleRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channelCount = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                }
            }
            val pcmBytes = out.toByteArray()
            Loudness.normalize(pcmBytes) // human recordings are quiet + inconsistent; even them out
            return Pcm(pcmBytes, sampleRate, channelCount.coerceAtLeast(1))
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "decodeToPcm failed file=${file.name}", t)
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }
}
