package com.playtranslate.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pure PCM plumbing over the game-audio snapshot WAV
 * ([com.playtranslate.capture.GameAudioSnapshot] — the canonical 44-byte-header
 * mono PCM16 layout GameAudioRecorder writes): slice a millisecond range out,
 * and encode a slice to AAC/M4A for the card. No Context, no state — shared by
 * the trim editor (waveform + preview) and RecordingAudioSource (send path).
 */
object GameAudioClip {

    private const val WAV_HEADER_BYTES = 44L
    private const val BYTES_PER_FRAME = 2 // PCM16 mono

    /** Sample rate declared in [wav]'s header (offset 24, little-endian). */
    fun sampleRate(wav: File): Int = RandomAccessFile(wav, "r").use { raf ->
        raf.seek(24)
        Integer.reverseBytes(raf.readInt())
    }

    /** Total playable length of [wav] in milliseconds. */
    fun durationMs(wav: File): Long {
        val dataBytes = wav.length() - WAV_HEADER_BYTES
        if (dataBytes <= 0) return 0
        return dataBytes / BYTES_PER_FRAME * 1000L / sampleRate(wav)
    }

    /**
     * The PCM window [startMs, endMs) of [wav], clamped to the file. Returns an
     * empty array when the clamped window is empty. Blocking — call on IO.
     */
    fun readPcmRange(wav: File, startMs: Long, endMs: Long): ShortArray {
        val rate = sampleRate(wav)
        val totalFrames = (wav.length() - WAV_HEADER_BYTES) / BYTES_PER_FRAME
        val startFrame = (startMs * rate / 1000).coerceIn(0, totalFrames)
        val endFrame = (endMs * rate / 1000).coerceIn(startFrame, totalFrames)
        val frames = (endFrame - startFrame).toInt()
        if (frames == 0) return ShortArray(0)
        val bytes = ByteArray(frames * BYTES_PER_FRAME)
        RandomAccessFile(wav, "r").use { raf ->
            raf.seek(WAV_HEADER_BYTES + startFrame * BYTES_PER_FRAME)
            raf.readFully(bytes)
        }
        val pcm = ShortArray(frames)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm)
        return pcm
    }

    /** Write [pcm] as a canonical mono PCM16 WAV — the same layout the recorder
     *  produces, so every consumer of a snapshot can also consume a slice. */
    fun writeWav(pcm: ShortArray, sampleRate: Int, out: File) {
        val dataBytes = pcm.size * BYTES_PER_FRAME
        val buf = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray())
        buf.putInt(36 + dataBytes)
        buf.put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray())
        buf.putInt(16)
        buf.putShort(1)
        buf.putShort(1)
        buf.putInt(sampleRate)
        buf.putInt(sampleRate * BYTES_PER_FRAME)
        buf.putShort(BYTES_PER_FRAME.toShort())
        buf.putShort(16)
        buf.put("data".toByteArray())
        buf.putInt(dataBytes)
        buf.asShortBuffer().put(pcm)
        out.parentFile?.mkdirs()
        out.writeBytes(buf.array())
    }

    /**
     * Encode [pcm] to AAC-LC in an MP4 container and return the file bytes.
     * MediaCodec AAC encode is CDD-guaranteed on every device (unlike MP3 or
     * Opus encode); MediaMuxer needs a real path, so a temp file is used and
     * deleted. Blocking — call on IO.
     */
    fun encodeM4a(pcm: ShortArray, sampleRate: Int, tempDir: File): ByteArray {
        val tmp = File.createTempFile("game-clip", ".m4a", tempDir)
        try {
            encodeM4aToFile(pcm, sampleRate, tmp)
            return tmp.readBytes()
        } finally {
            tmp.delete()
        }
    }

    private fun encodeM4aToFile(pcm: ShortArray, sampleRate: Int, out: File) {
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1,
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 96_000)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerStarted = false
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var track = -1
            var pcmPos = 0
            var inputDone = false
            while (true) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)!!
                        inBuf.clear()
                        val n = minOf(inBuf.capacity() / 2, pcm.size - pcmPos)
                        val ptsUs = pcmPos * 1_000_000L / sampleRate
                        if (n <= 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            inBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcm, pcmPos, n)
                            codec.queueInputBuffer(inIdx, 0, n * 2, ptsUs, 0)
                            pcmPos += n
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                } else if (outIdx >= 0) {
                    val outBuf = codec.getOutputBuffer(outIdx)!!
                    if (info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        muxer.writeSampleData(track, outBuf, info)
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            codec.release()
            if (muxerStarted) try { muxer.stop() } catch (_: Exception) {}
            muxer.release()
        }
    }
}
