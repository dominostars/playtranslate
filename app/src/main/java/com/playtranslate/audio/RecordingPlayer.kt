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
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
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
        maxGain: Double = Loudness.MAX_GAIN,
    ): PlayOutcome = play(file, awaitCompletion, onStart, maxGain, onUnreadable = null)

    /**
     * [play], for a clip a remote source keeps in [AudioCache] and can fetch
     * again. One that does not decode to audio ([Decoded.Unreadable]) is
     * dropped from the cache, so the next request asks the source again.
     * The cache looks no further than a file's presence, and a download or
     * a service's reply is not checked past its first bytes: kept, such a
     * clip would fail every later play of the same words with nothing asked
     * again, and go on a card as it is.
     *
     * Only a play finds one. A clip fetched for a card and never played is
     * not decoded.
     */
    suspend fun playCached(
        ctx: Context,
        clip: File,
        awaitCompletion: Boolean,
        onStart: (() -> Unit)?,
    ): PlayOutcome = play(clip, awaitCompletion, onStart, Loudness.MAX_GAIN) {
        AudioCache(ctx).dropClip(clip)
    }

    private suspend fun play(
        file: File,
        awaitCompletion: Boolean,
        onStart: (() -> Unit)?,
        maxGain: Double,
        onUnreadable: (() -> Unit)?,
    ): PlayOutcome = withContext(Dispatchers.IO) {
        withContext(Dispatchers.Main) { stop() }
        val myGen = generation

        val pcm = when (val decoded = runCatching { decoder(file, maxGain) }.getOrDefault(Decoded.Declined)) {
            is Decoded.Audio -> decoded
            Decoded.Unreadable -> {
                android.util.Log.w(TAG, "RecordingPlayer: no audio decoded from file=${file.name}")
                onUnreadable?.invoke()
                return@withContext PlayOutcome.Failed(recoverable = true)
            }
            Decoded.Declined -> return@withContext PlayOutcome.Failed(recoverable = true)
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
        // The caller gave up while this decoded (a remote source's time
        // budget ran out, or its tap was cancelled). Nothing above suspends,
        // so without this check the clip would start anyway, on this
        // player's own scope, and the source the caller moved on to would
        // speak over it.
        if (!isActive) { runCatching { newTrack.release() }; ensureActive() }
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
    private fun renderBlocking(t: AudioTrack, pcm: Decoded.Audio, myGen: Int, onStart: (() -> Unit)?) {
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

    /** What decoding a file gave. */
    internal sealed interface Decoded {
        /** The file's audio, as 16-bit PCM. */
        class Audio(val data: ByteArray, val sampleRate: Int, val channelCount: Int) : Decoded

        /**
         * The file did not decode to audio: the platform reads no audio
         * track in it, its decoder failed on it, or decoding it to its end
         * gave nothing. A cached copy is not worth keeping ([playCached]).
         * This can be wrong about a sound file (a decoder that failed for a
         * reason of its own); the price then is one more fetch.
         */
        data object Unreadable : Decoded

        /**
         * Not decoded, for a reason that is no fault of the file as audio:
         * it is longer than this player takes ([AudioDecodeLimits]), or
         * memory ran out. A cached copy stays: it may be sound, and it
         * would be declined again each time it was fetched.
         */
        data object Declined : Decoded
    }

    /** What decodes a file for [play]. A seam for JVM tests, which have no
     *  media codecs. */
    @Volatile
    @VisibleForTesting
    internal var decoder: (File, Double) -> Decoded = ::decodeToPcm

    /** Decodes [file] to 16-bit PCM and applies the SINGLE loudness pass for
     *  this playback (callers must hand us raw audio — normalizing before this
     *  stacks gain, since the limiter leaves RMS under target and a second pass
     *  boosts again). [maxGain] lets the game-audio path raise the ceiling. */
    private fun decodeToPcm(file: File, maxGain: Double): Decoded {
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
            if (trackIndex < 0 || inputFormat == null) return Decoded.Unreadable
            extractor.selectTrack(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: return Decoded.Unreadable
            // A small compressed clip can decode into a huge PCM buffer; reject
            // an over-long clip up front (the download cap bounds compressed bytes,
            // not decoded ones). Unknown duration falls through to the byte cap below.
            val durationUs = if (inputFormat.containsKey(MediaFormat.KEY_DURATION)) inputFormat.getLong(MediaFormat.KEY_DURATION) else 0L
            if (AudioDecodeLimits.durationExceedsLimit(durationUs)) {
                android.util.Log.w(TAG, "rejecting over-long clip ${file.name} duration=${durationUs}us")
                return Decoded.Declined
            }
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
                            if (AudioDecodeLimits.pcmExceedsLimit(out.size().toLong() + info.size)) {
                                android.util.Log.w(TAG, "decoded PCM exceeds cap; aborting ${file.name}")
                                codec.releaseOutputBuffer(outIdx, false)
                                return Decoded.Declined
                            }
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
            if (pcmBytes.isEmpty() || sampleRate <= 0) return Decoded.Unreadable
            Loudness.normalize(pcmBytes, maxGain) // quiet + inconsistent sources; even them out
            return Decoded.Audio(pcmBytes, sampleRate, channelCount.coerceAtLeast(1))
        } catch (e: Exception) {
            // The extractor refusing the file, or the decoder failing on it.
            android.util.Log.w(TAG, "decodeToPcm failed file=${file.name}", e)
            return Decoded.Unreadable
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "decodeToPcm gave up on file=${file.name}", t)
            return Decoded.Declined
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }
}
