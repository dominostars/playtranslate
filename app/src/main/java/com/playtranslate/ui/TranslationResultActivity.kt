package com.playtranslate.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.playtranslate.AnkiManager
import com.playtranslate.CaptureService
import com.playtranslate.Prefs
import com.playtranslate.RegionEntry
import com.playtranslate.R
import com.playtranslate.model.TextSegment
import com.playtranslate.model.TranslationResult
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Standalone activity that hosts [TranslationResultFragment] for showing
 * translation results when the main activity is not in the foreground
 * (single-screen mode or app backgrounded on dual-screen).
 */
class TranslationResultActivity : AppCompatActivity(), TranslationResultFragment.TranslationResultHost {

    private var captureService: CaptureService? = null
    private var serviceConnected = false

    private val resultFragment: TranslationResultFragment?
        get() = supportFragmentManager.findFragmentById(R.id.resultFragmentContainer) as? TranslationResultFragment

    private val requestAnkiPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) Toast.makeText(this, getString(R.string.anki_permission_denied), Toast.LENGTH_SHORT).show()
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            captureService = (binder as CaptureService.LocalBinder).getService()
            serviceConnected = true
            onServiceReady()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            serviceConnected = false
            captureService = null
        }
    }

    // ── TranslationResultHost ─────────────────────────────────────────────

    override fun getCaptureService(): CaptureService? = captureService

    override fun onWordTapped(
        word: String,
        reading: String?,
        screenshotPath: String?,
        sentenceOriginal: String?,
        sentenceTranslation: String?,
        wordResults: Map<String, Triple<String, String, Int>>
    ) {
        WordDetailBottomSheet.newInstance(
            word,
            reading = reading,
            screenshotPath = screenshotPath,
            sentenceOriginal = sentenceOriginal,
            sentenceTranslation = sentenceTranslation,
            sentenceWordResults = wordResults
        ).show(supportFragmentManager, WordDetailBottomSheet.TAG)
    }

    override fun onInteraction() {
        // No-op — no live mode here
    }

    override fun getAnkiPermissionLauncher() = requestAnkiPermission

    // ── Lifecycle ─────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_translation_result)

        // Hide our own UI from accessibility screenshots (see MainActivity
        // for the full rationale — prevents OCR feedback loop in multi-window).
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        findViewById<android.widget.ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        val btnAnki = findViewById<android.widget.ImageButton>(R.id.btnMainAddToAnki)
        btnAnki.visibility = View.GONE  // hidden until results are shown
        btnAnki.setOnClickListener { resultFragment?.onAnkiClicked() }
        resultFragment?.onAnkiEnabledChanged = { enabled ->
            btnAnki.isEnabled = enabled
            btnAnki.visibility = if (enabled) View.VISIBLE else View.GONE
        }

        val hasSentence = intent.hasExtra(EXTRA_SENTENCE_TEXT)
        resultFragment?.showStatus(getString(
            if (hasSentence) R.string.status_translating else R.string.status_capturing
        ))

        // Start and bind CaptureService
        val svcIntent = Intent(this, CaptureService::class.java)
        ContextCompat.startForegroundService(this, svcIntent)
        bindService(svcIntent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onDestroy() {
        captureService?.onResult = null
        captureService?.onError = null
        captureService?.onStatusUpdate = null
        captureService?.onTranslationStarted = null
        captureService?.onLiveNoText = null
        if (serviceConnected) unbindService(serviceConnection)
        super.onDestroy()
    }

    private fun onServiceReady() {
        val svc = captureService ?: return
        val prefs = Prefs(this)

        // Sentence mode: text passed directly from drag-to-lookup popup
        val sentenceText = intent.getStringExtra(EXTRA_SENTENCE_TEXT)
        if (sentenceText != null) {
            handleSentenceMode(svc, sentenceText)
            return
        }

        // Region capture mode
        val topFrac    = intent.getFloatExtra(EXTRA_TOP_FRAC, 0f)
        val bottomFrac = intent.getFloatExtra(EXTRA_BOTTOM_FRAC, 1f)
        val leftFrac   = intent.getFloatExtra(EXTRA_LEFT_FRAC, 0f)
        val rightFrac  = intent.getFloatExtra(EXTRA_RIGHT_FRAC, 1f)

        svc.configureSaved(
            displayId = prefs.captureDisplayId,
            region    = RegionEntry("Drawn Region", topFrac, bottomFrac, leftFrac, rightFrac)
        )

        svc.onResult = { result ->
            runOnUiThread { resultFragment?.displayResult(result) }
        }
        svc.onError = { msg ->
            runOnUiThread { resultFragment?.showError(msg) }
        }
        svc.onStatusUpdate = { msg ->
            runOnUiThread { resultFragment?.showStatus(msg) }
        }

        // If we have a pre-captured screenshot (single-screen: taken before this
        // activity appeared so it shows the game, not this activity), process it
        // directly instead of capturing a new one.
        val screenshotPath = intent.getStringExtra(EXTRA_SCREENSHOT_PATH)
        if (screenshotPath != null) {
            val bitmap = BitmapFactory.decodeFile(screenshotPath)
            if (bitmap != null) {
                svc.processScreenshot(bitmap)
                return
            }
        }
        // Fallback: capture fresh (works on dual-screen where this activity
        // doesn't cover the game display)
        svc.captureOnce()
    }

    private fun handleSentenceMode(svc: CaptureService, sentenceText: String) {
        val screenshotPath = intent.getStringExtra(EXTRA_SCREENSHOT_PATH)
        val segments = sentenceText.map { TextSegment(it.toString()) }
        val frag = resultFragment ?: return
        val prefs = Prefs(this)

        // Ensure the service is configured for translation
        svc.configureSaved(displayId = prefs.captureDisplayId)

        frag.showTranslatingPlaceholder(sentenceText, segments)

        lifecycleScope.launch {
            try {
                val (translated, note) = svc.translateOnce(sentenceText)
                val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                val result = TranslationResult(
                    originalText = sentenceText,
                    segments = segments,
                    translatedText = translated,
                    timestamp = timestamp,
                    screenshotPath = screenshotPath,
                    note = note
                )
                frag.displayResult(result)
            } catch (e: Exception) {
                frag.showError(e.message ?: "Translation failed")
            }
        }
    }

    private fun applyTheme() {
        val idx = getSharedPreferences("playtranslate_prefs", MODE_PRIVATE)
            .getInt("theme_index", 0)
        setTheme(when (idx) {
            1    -> R.style.Theme_PlayTranslate_White
            2    -> R.style.Theme_PlayTranslate_Rainbow
            3    -> R.style.Theme_PlayTranslate_Purple
            else -> R.style.Theme_PlayTranslate
        })
    }

    companion object {
        const val EXTRA_TOP_FRAC = "extra_top_frac"
        const val EXTRA_BOTTOM_FRAC = "extra_bottom_frac"
        const val EXTRA_LEFT_FRAC = "extra_left_frac"
        const val EXTRA_RIGHT_FRAC = "extra_right_frac"
        const val EXTRA_SCREENSHOT_PATH = "extra_screenshot_path"
        const val EXTRA_SENTENCE_TEXT = "extra_sentence_text"
    }
}
