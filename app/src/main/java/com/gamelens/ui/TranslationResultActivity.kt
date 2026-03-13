package com.gamelens.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.gamelens.AnkiManager
import com.gamelens.CaptureService
import com.gamelens.Prefs
import com.gamelens.R
import com.google.mlkit.nl.translate.TranslateLanguage

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
        screenshotPath: String?,
        sentenceOriginal: String?,
        sentenceTranslation: String?,
        wordResults: Map<String, Triple<String, String, Int>>
    ) {
        WordDetailBottomSheet.newInstance(
            word,
            screenshotPath,
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

        findViewById<android.widget.ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        resultFragment?.showStatus(getString(R.string.status_capturing))

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

        val topFrac    = intent.getFloatExtra(EXTRA_TOP_FRAC, 0f)
        val bottomFrac = intent.getFloatExtra(EXTRA_BOTTOM_FRAC, 1f)
        val leftFrac   = intent.getFloatExtra(EXTRA_LEFT_FRAC, 0f)
        val rightFrac  = intent.getFloatExtra(EXTRA_RIGHT_FRAC, 1f)

        svc.configure(
            displayId             = prefs.captureDisplayId,
            sourceLang            = TranslateLanguage.JAPANESE,
            targetLang            = TranslateLanguage.ENGLISH,
            captureTopFraction    = topFrac,
            captureBottomFraction = bottomFrac,
            captureLeftFraction   = leftFrac,
            captureRightFraction  = rightFrac,
            regionLabel           = "Drawn Region"
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

        svc.captureOnce()
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
    }
}
