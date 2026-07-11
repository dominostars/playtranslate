package com.playtranslate.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.playtranslate.R
import com.playtranslate.applyEdgeToEdge
import com.playtranslate.applyTheme
import com.playtranslate.audio.GameAudioClip
import com.playtranslate.capture.GameAudioSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Manual trim editor for recorded game audio: waveform + pinch-zoom/pan +
 * draggable handles + play-selection. No auto-placed range (v1 decision) —
 * the initial selection is simply the freshest few seconds, and the human
 * finds the line. Returns one of three actions:
 *  - [ACTION_TRIM] + start/end ms — commit the selection as sentence audio;
 *  - [ACTION_TTS] — fall back to synthesized audio;
 *  - [ACTION_NONE] — send the card with no sentence audio.
 * Back/close = RESULT_CANCELED (the Save gate aborts the send).
 *
 * Boilerplate mirrors [AudioSourcePickerActivity]; playback goes through
 * [PcmAudioTrackPlayer] (raw PCM, no decode, no audio focus).
 */
class GameAudioTrimActivity : AppCompatActivity() {

    private lateinit var waveform: WaveformTrimView
    private lateinit var tvDuration: TextView
    private lateinit var btnPlay: MaterialButton
    private lateinit var loading: ProgressBar

    private var pcm: ShortArray = ShortArray(0)
    private var sampleRate = 44_100
    private var totalDurationMs = 0L
    private var player: PcmAudioTrackPlayer? = null
    private var playing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme(this)
        applyEdgeToEdge(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_game_audio_trim)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { v, insets ->
            val sys = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            v.setPadding(sys.left, sys.top, sys.right, sys.bottom)
            WindowInsetsCompat.CONSUMED
        }

        waveform = findViewById(R.id.waveformTrim)
        tvDuration = findViewById(R.id.tvTrimDuration)
        btnPlay = findViewById(R.id.btnTrimPlay)
        loading = findViewById(R.id.trimLoading)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        findViewById<MaterialButton>(R.id.btnTrimSave).setOnClickListener {
            setResult(
                RESULT_OK,
                Intent()
                    .putExtra(EXTRA_ACTION, ACTION_TRIM)
                    .putExtra(EXTRA_START_MS, waveform.selStartMs)
                    .putExtra(EXTRA_END_MS, waveform.selEndMs),
            )
            finish()
        }
        findViewById<MaterialButton>(R.id.btnTrimUseTts).setOnClickListener {
            setResult(RESULT_OK, Intent().putExtra(EXTRA_ACTION, ACTION_TTS))
            finish()
        }
        findViewById<MaterialButton>(R.id.btnTrimNoAudio).setOnClickListener {
            setResult(RESULT_OK, Intent().putExtra(EXTRA_ACTION, ACTION_NONE))
            finish()
        }
        btnPlay.setOnClickListener { togglePlayback() }
        btnPlay.isEnabled = false

        waveform.onSelectionChanged = { s, e ->
            // A moved handle invalidates an in-flight audition of the old range.
            stopPlayback()
            renderDuration(s, e)
        }

        val wavPath = intent.getStringExtra(EXTRA_WAV_PATH)
        val initialStart = savedInstanceState?.getLong(STATE_SEL_START)
            ?: intent.getLongExtra(EXTRA_INITIAL_START_MS, -1)
        val initialEnd = savedInstanceState?.getLong(STATE_SEL_END)
            ?: intent.getLongExtra(EXTRA_INITIAL_END_MS, -1)
        loadWaveform(wavPath, initialStart, initialEnd)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(STATE_SEL_START, waveform.selStartMs)
        outState.putLong(STATE_SEL_END, waveform.selEndMs)
    }

    override fun onDestroy() {
        player?.stop()
        super.onDestroy()
    }

    private fun loadWaveform(wavPath: String?, initialStartMs: Long, initialEndMs: Long) {
        lifecycleScope.launch {
            val loadedData = withContext(Dispatchers.IO) {
                val wav = wavPath?.let(::File)?.takeIf { it.exists() }
                    ?: GameAudioSnapshot.file(this@GameAudioTrimActivity)
                        .takeIf { it.exists() }
                    ?: return@withContext null
                val durationMs = GameAudioClip.durationMs(wav)
                if (durationMs < 500) return@withContext null
                val rate = GameAudioClip.sampleRate(wav)
                val samples = GameAudioClip.readPcmRange(wav, 0, durationMs)
                Triple(durationMs, rate, samples)
            } ?: run {
                // No usable snapshot — nothing to trim; the Save gate treats
                // cancel as "abort send".
                finish()
                return@launch
            }
            val (durationMs, rate, samples) = loadedData
            val buckets = withContext(Dispatchers.Default) { rmsBuckets(samples, rate) }
            pcm = samples
            sampleRate = rate
            totalDurationMs = durationMs
            player = PcmAudioTrackPlayer(rate)
            val (selStart, selEnd) =
                if (initialStartMs in 0 until initialEndMs) initialStartMs to minOf(initialEndMs, durationMs)
                else max(0, durationMs - DEFAULT_SELECTION_MS) to durationMs
            waveform.setData(buckets, BUCKET_MS, durationMs, selStart, selEnd)
            renderDuration(waveform.selStartMs, waveform.selEndMs)
            loading.visibility = View.GONE
            btnPlay.isEnabled = true
        }
    }

    /** Per-bucket RMS normalized against the loudest bucket, so quiet game
     *  mixes still render a readable waveform. */
    private fun rmsBuckets(samples: ShortArray, rate: Int): FloatArray {
        val bucketFrames = (rate * BUCKET_MS / 1000).toInt().coerceAtLeast(1)
        val out = FloatArray((samples.size + bucketFrames - 1) / bucketFrames)
        var maxRms = 0f
        for (b in out.indices) {
            val from = b * bucketFrames
            val to = minOf(from + bucketFrames, samples.size)
            var sumSq = 0.0
            for (i in from until to) {
                val s = samples[i].toDouble()
                sumSq += s * s
            }
            val rms = sqrt(sumSq / (to - from)).toFloat() / Short.MAX_VALUE
            out[b] = rms
            if (rms > maxRms) maxRms = rms
        }
        if (maxRms > 0f) for (b in out.indices) out[b] = (out[b] / maxRms).coerceAtMost(1f)
        return out
    }

    private fun togglePlayback() {
        if (playing) {
            stopPlayback()
            return
        }
        val p = player ?: return
        val startFrame = (waveform.selStartMs * sampleRate / 1000).toInt()
        val endFrame = (waveform.selEndMs * sampleRate / 1000).toInt()
        playing = true
        btnPlay.setText(R.string.game_audio_trim_stop)
        p.play(
            pcm,
            startFrame,
            endFrame,
            onProgress = { frame -> waveform.setPlaybackCursorMs(frame * 1000L / sampleRate) },
            onDone = { stopPlayback() },
        )
    }

    private fun stopPlayback() {
        player?.stop()
        playing = false
        waveform.setPlaybackCursorMs(null)
        btnPlay.setText(R.string.game_audio_trim_play)
    }

    private fun renderDuration(startMs: Long, endMs: Long) {
        val seconds = (endMs - startMs) / 1000.0
        // The recorded total answers "why is there so little to trim?" —
        // the buffer holds only what has played since recording started
        // this session (up to the 180 s ring), not a guaranteed 3 minutes.
        tvDuration.text = getString(
            R.string.game_audio_trim_duration,
            String.format(Locale.US, "%.1f", seconds),
            (totalDurationMs / 1000).toString(),
        )
    }

    companion object {
        const val EXTRA_ACTION = "action"
        const val ACTION_TRIM = "trim"
        const val ACTION_TTS = "tts"
        const val ACTION_NONE = "none"
        const val EXTRA_START_MS = "start_ms"
        const val EXTRA_END_MS = "end_ms"
        private const val EXTRA_WAV_PATH = "wav_path"
        private const val EXTRA_INITIAL_START_MS = "initial_start_ms"
        private const val EXTRA_INITIAL_END_MS = "initial_end_ms"
        private const val STATE_SEL_START = "sel_start"
        private const val STATE_SEL_END = "sel_end"
        private const val BUCKET_MS = 50L
        private const val DEFAULT_SELECTION_MS = 5_000L

        fun intent(
            context: Context,
            wavPath: String?,
            initialStartMs: Long = -1,
            initialEndMs: Long = -1,
        ): Intent = Intent(context, GameAudioTrimActivity::class.java)
            .putExtra(EXTRA_WAV_PATH, wavPath)
            .putExtra(EXTRA_INITIAL_START_MS, initialStartMs)
            .putExtra(EXTRA_INITIAL_END_MS, initialEndMs)
    }
}
