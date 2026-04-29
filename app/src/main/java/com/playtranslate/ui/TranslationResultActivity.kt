package com.playtranslate.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Typeface
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.playtranslate.CaptureService
import com.playtranslate.Prefs
import com.playtranslate.RegionEntry
import com.playtranslate.R
import com.playtranslate.model.TextSegment
import com.playtranslate.model.TranslationResult
import com.playtranslate.themeColor
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Standalone activity that hosts [TranslationResultFragment] for showing
 * translation results when the main activity is not in the foreground
 * (single-screen mode or app backgrounded on dual-screen).
 *
 * When launched with [EXTRA_DRAG_WORD] (single-screen drag-flow lens
 * "Open" tap), the toolbar swaps the top Anki button for a Sentence/Word
 * pill toggle and a second container hosts an embedded [WordDetailBottomSheet]
 * for the looked-up word.
 */
class TranslationResultActivity :
    AppCompatActivity(),
    TranslationResultFragment.TranslationResultHost,
    SentenceContextProvider {

    private var captureService: CaptureService? = null
    private var serviceConnected = false

    /** Activity-scoped state mirror of the result/lookups pipeline.
     *  Filled by [TranslationResultFragment] as it produces results;
     *  read by [currentSentenceContext] to feed the embedded
     *  [WordDetailBottomSheet]'s Anki export on demand. Replaces the
     *  earlier push pipeline (`pushSentenceContextToWordTab`). */
    private val vm: TranslationResultViewModel by viewModels()

    /** Launch-time fallbacks for the embedded sheet's sentence context,
     *  used when the VM hasn't yet observed a settled result. Captured
     *  from the intent extras at [setupDragWordTabs] time. */
    private var intentSeededTranslation: String? = null
    private var intentSeededWordResults: Map<String, Triple<String, String, Int>>? = null

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

    /** True when this activity was launched from the drag-flow lens "Open"
     *  tap with a specific word context — drives the Sentence/Word pill
     *  toggle in the toolbar. */
    private val isDragWordMode: Boolean
        get() = intent.hasExtra(EXTRA_DRAG_WORD)

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

    // ── SentenceContextProvider ───────────────────────────────────────────

    /** Embedded [WordDetailBottomSheet] reads this at Anki-button tap
     *  time. Prefer the VM's settled values; fall back to launch-time
     *  intent extras during the loading window so a fast Anki tap still
     *  carries the prefetched sentence-word context. */
    override fun currentSentenceContext(): SentenceContext {
        val translation = (vm.result.value as? ResultState.Ready)?.result?.translatedText
            ?: intentSeededTranslation
        val wordResults = (vm.wordLookups.value as? WordLookupsState.Settled)?.rows?.toLegacyMap()
            ?: intentSeededWordResults
        return SentenceContext(
            original = intent.getStringExtra(EXTRA_SENTENCE_TEXT),
            translation = translation,
            wordResults = wordResults,
        )
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_translation_result)

        // Hide our own UI from accessibility screenshots (see MainActivity
        // for the full rationale — prevents OCR feedback loop in multi-window).
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        if (isDragWordMode) setupDragWordTabs(savedInstanceState)

        val hasSentence = intent.hasExtra(EXTRA_SENTENCE_TEXT)
        vm.showStatus(getString(
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

    /** Drag-flow Sentence/Word tab UI: shows a centered pill toggle in
     *  the toolbar, mounts the embedded word detail fragment, and wires
     *  segment selection to container visibility. Called from [onCreate]
     *  only when [isDragWordMode] is true. */
    private fun setupDragWordTabs(savedInstanceState: Bundle?) {
        val word = intent.getStringExtra(EXTRA_DRAG_WORD) ?: return
        val reading = intent.getStringExtra(EXTRA_DRAG_READING)
        val screenshotPath = intent.getStringExtra(EXTRA_SCREENSHOT_PATH)
        // Capture launch-time fallbacks for [currentSentenceContext]. The
        // VM is initially Idle and populates only as the fragment's
        // pipeline produces results — until then, a fast Anki tap on the
        // embedded sheet would otherwise see no sentence context. Intent
        // extras are launch-scoped, so nothing else in the process can
        // overwrite them after this point.
        intentSeededTranslation = intent.getStringExtra(EXTRA_DRAG_SENTENCE_TRANSLATION)
            ?.takeIf { it.isNotEmpty() }
        intentSeededWordResults =
            intent.getStringArrayExtra(EXTRA_DRAG_SENTENCE_WORDS)?.let { words ->
                val readings = intent.getStringArrayExtra(EXTRA_DRAG_SENTENCE_READINGS)
                    ?: emptyArray()
                val meanings = intent.getStringArrayExtra(EXTRA_DRAG_SENTENCE_MEANINGS)
                    ?: emptyArray()
                val freqScores = intent.getIntArrayExtra(EXTRA_DRAG_SENTENCE_FREQ_SCORES)
                    ?: IntArray(0)
                words.mapIndexed { i, w ->
                    w to Triple(
                        readings.getOrElse(i) { "" },
                        meanings.getOrElse(i) { "" },
                        freqScores.getOrElse(i) { 0 },
                    )
                }.toMap()
            }

        val sentenceContainer = findViewById<View>(R.id.resultFragmentContainer)
        val wordContainer = findViewById<FrameLayout>(R.id.wordDetailContainer)

        // Pill toggle in the toolbar's center slot. Defaults to "Word" —
        // the user tapped a specific word in the lens to get here, so the
        // word definition is the reading they expect to see first.
        sentenceContainer.visibility = View.GONE
        wordContainer.visibility = View.VISIBLE
        val toggleContainer = findViewById<FrameLayout>(R.id.segmentedTabContainer)
        buildToolbarPillToggle(
            container = toggleContainer,
            options = listOf("Sentence" to Tab.SENTENCE, word to Tab.WORD),
            initial = Tab.WORD,
            onSelect = { tab ->
                val showSentence = tab == Tab.SENTENCE
                sentenceContainer.visibility = if (showSentence) View.VISIBLE else View.GONE
                wordContainer.visibility = if (showSentence) View.GONE else View.VISIBLE
            },
        )

        // Mount the embedded word detail fragment once. Sentence context
        // (original / translation / wordResults) is supplied by this
        // activity via [SentenceContextProvider] at Anki-tap time, so
        // those args are intentionally omitted — the embedded sheet
        // queries [currentSentenceContext] which prefers VM state and
        // falls back to launch-time intent extras. On config change,
        // FragmentManager restores it automatically — guard the add()
        // so we don't double-add.
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .add(
                    R.id.wordDetailContainer,
                    WordDetailBottomSheet.newInstance(
                        word = word,
                        reading = reading,
                        screenshotPath = screenshotPath,
                        embedded = true,
                    ),
                    TAG_EMBEDDED_WORD_DETAIL,
                )
                .commit()
        }
    }

    /** Two-segment pill toggle modeled on SettingsRenderer.buildPillToggle:
     *  recessed [ptSurface] track, sliding [ptAccent] indicator, text
     *  labels on top with active label in [ptSurface] color + bold. The
     *  right segment (the word) ellipsizes if it overflows the slot. */
    private fun <T> buildToolbarPillToggle(
        container: FrameLayout,
        options: List<Pair<String, T>>,
        initial: T,
        onSelect: (T) -> Unit,
    ) {
        container.removeAllViews()
        val density = resources.displayMetrics.density
        val trackRadius = 10 * density
        val pillRadius = 8 * density
        val trackPad = (3 * density).toInt()
        val pillH = (32 * density).toInt()

        val surfaceColor = themeColor(R.attr.ptSurface)
        val accentColor = themeColor(R.attr.ptAccent)
        val mutedColor = themeColor(R.attr.ptTextMuted)

        val initialIdx = options.indexOfFirst { it.second == initial }.coerceAtLeast(0)

        val track = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            )
            background = GradientDrawable().apply {
                setColor(surfaceColor)
                cornerRadius = trackRadius
            }
            setPadding(trackPad, trackPad, trackPad, trackPad)
        }

        val pillRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            )
        }

        val indicator = View(this).apply {
            background = GradientDrawable().apply {
                setColor(accentColor)
                cornerRadius = pillRadius
            }
            elevation = 2 * density
        }
        track.addView(indicator)
        pillRow.elevation = 3 * density
        track.addView(pillRow)

        val pills = mutableListOf<TextView>()
        var currentIdx = initialIdx

        options.forEachIndexed { idx, (label, _) ->
            val isActive = idx == initialIdx
            val pill = TextView(this).apply {
                text = label
                textSize = 13f
                typeface = Typeface.create(
                    "sans-serif-medium",
                    if (isActive) Typeface.BOLD else Typeface.NORMAL,
                )
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(if (isActive) surfaceColor else mutedColor)
                layoutParams = LinearLayout.LayoutParams(0, pillH, 1f)
                setPadding((14 * density).toInt(), 0, (14 * density).toInt(), 0)
                isClickable = true
                isFocusable = true
            }
            pills.add(pill)
            pillRow.addView(pill)
        }

        container.addView(track)

        pillRow.post {
            if (pills.isEmpty()) return@post
            val pillW = pills[0].width
            indicator.layoutParams = FrameLayout.LayoutParams(pillW, pillH)
            indicator.translationX = (pillW * initialIdx).toFloat()
            indicator.requestLayout()
        }

        pills.forEachIndexed { idx, pill ->
            pill.setOnClickListener {
                if (idx == currentIdx) return@setOnClickListener
                currentIdx = idx
                val pillW = pills[0].width
                indicator.animate()
                    .translationX((pillW * idx).toFloat())
                    .setDuration(200)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .start()
                pills.forEachIndexed { i, p ->
                    val active = i == idx
                    p.setTextColor(if (active) surfaceColor else mutedColor)
                    p.typeface = Typeface.create(
                        "sans-serif-medium",
                        if (active) Typeface.BOLD else Typeface.NORMAL,
                    )
                }
                onSelect(options[idx].second)
            }
        }
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
            runOnUiThread { vm.displayResult(result, applicationContext) }
        }
        svc.onError = { msg ->
            runOnUiThread { vm.showError(msg) }
        }
        svc.onStatusUpdate = { msg ->
            runOnUiThread { vm.showStatus(msg) }
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

        // Drag flow already produced this translation in the lens. Skip
        // the redundant translateOnce so the sentence tab opens straight
        // to the cached text — avoids a "Translating..." flash and any
        // transient ML Kit reload that re-translation could fail on.
        val cachedTranslation = intent.getStringExtra(EXTRA_DRAG_SENTENCE_TRANSLATION)
            ?.takeIf { it.isNotEmpty() }
        if (cachedTranslation != null) {
            val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            vm.displayResult(
                TranslationResult(
                    originalText = sentenceText,
                    segments = segments,
                    translatedText = cachedTranslation,
                    timestamp = timestamp,
                    screenshotPath = screenshotPath,
                    note = null,
                ),
                applicationContext,
            )
            return
        }

        // Translation-only path: translateOnce() self-heals language managers
        // internally, so we don't touch configureSaved — doing so would
        // overwrite the user's saved capture region with the full-screen
        // default, which any concurrent capture (e.g. live mode still
        // running) would then pick up.

        vm.showTranslatingPlaceholder(sentenceText, segments, applicationContext)

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
                vm.displayResult(result, applicationContext)
            } catch (e: Exception) {
                vm.showError(e.message ?: "Translation failed")
            }
        }
    }

    private fun applyTheme() {
        com.playtranslate.applyTheme(this)
    }

    private enum class Tab { SENTENCE, WORD }

    companion object {
        const val EXTRA_TOP_FRAC = "extra_top_frac"
        const val EXTRA_BOTTOM_FRAC = "extra_bottom_frac"
        const val EXTRA_LEFT_FRAC = "extra_left_frac"
        const val EXTRA_RIGHT_FRAC = "extra_right_frac"
        const val EXTRA_SCREENSHOT_PATH = "extra_screenshot_path"
        const val EXTRA_SENTENCE_TEXT = "extra_sentence_text"
        /** Drag-flow lens "Open" tap: the looked-up word from the magnifier
         *  becomes the right segment label of the Sentence/Word pill toggle
         *  in the toolbar. When absent, the activity stays in plain
         *  region-capture mode (no pill, top Anki button stays). */
        const val EXTRA_DRAG_WORD = "extra_drag_word"
        const val EXTRA_DRAG_READING = "extra_drag_reading"
        const val EXTRA_DRAG_SENTENCE_TRANSLATION = "extra_drag_sentence_translation"
        /** Sentence's tokenized word lookups, serialized as four parallel
         *  arrays (mirrors [WordDetailBottomSheet]'s args bundle layout).
         *  Captured by the drag controller at lens-dismiss time so the
         *  Word tab's Anki export carries the full sentence-word context
         *  without depending on the process-global [LastSentenceCache],
         *  which live mode can stomp during the dismiss → onCreate gap. */
        const val EXTRA_DRAG_SENTENCE_WORDS = "extra_drag_sentence_words"
        const val EXTRA_DRAG_SENTENCE_READINGS = "extra_drag_sentence_readings"
        const val EXTRA_DRAG_SENTENCE_MEANINGS = "extra_drag_sentence_meanings"
        const val EXTRA_DRAG_SENTENCE_FREQ_SCORES = "extra_drag_sentence_freq_scores"

        private const val TAG_EMBEDDED_WORD_DETAIL = "WordDetail.embedded"
    }
}
