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
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.playtranslate.CaptureService
import com.playtranslate.applyEdgeToEdge
import com.playtranslate.CaptureSession
import com.playtranslate.CaptureState
import com.playtranslate.capture.CaptureBackendResolver
import com.playtranslate.Prefs
import com.playtranslate.RegionEntry
import com.playtranslate.R
import com.playtranslate.model.TextSegments
import com.playtranslate.model.OcrProvenance
import com.playtranslate.model.TranslationResult
import com.playtranslate.themeColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    /** Capture-overlay return wiring: when this screen was opened from the over-game
     *  capture panel, a user-initiated back (system back OR the toolbar button)
     *  signals the controller to re-show that panel. A programmatic finish (e.g.
     *  superseded by a newer detail launch via finishCurrentIfAny) does NOT signal,
     *  so the overlay never re-shows unexpectedly. */
    private val fromCaptureOverlay: Boolean by lazy {
        intent.getBooleanExtra(EXTRA_FROM_CAPTURE_OVERLAY, false)
    }
    private var captureReturnSignalled = false

    /** Edit-original overlay views (parity with MainActivity's edit
     *  overlay). The standalone result screen now supports editing the
     *  original text just like the in-app dual-screen path. */
    private lateinit var editOverlay: LinearLayout
    private lateinit var etEditOriginal: android.widget.EditText

    /** In-flight re-translate after an edit. Cancelled before launching a
     *  fresh one so a slow backend reply can't overwrite a newer edit.
     *  Unlike MainActivity, there's no live-capture pipeline here to also
     *  cancel it — this is a one-shot screen. */
    private var editTranslationJob: kotlinx.coroutines.Job? = null

    /** The active one-shot capture session + its state collector. Held so an edit
     *  can supersede a still-running capture (it's translating the pre-edit
     *  source): cancelling the collector stops a late Done from overwriting the
     *  edit and its Cancelled from finish()-ing us. */
    private var captureSession: CaptureSession? = null
    private var sessionCollectJob: kotlinx.coroutines.Job? = null

    /** In-flight OCR-tool switch (re-OCR of the cached screenshot). Cancelled
     *  before launching a fresh one so a double-tap can't race two re-scans. */
    private var reOcrJob: kotlinx.coroutines.Job? = null

    /** Tracks keyboard visibility so dismissing the IME commits the edit,
     *  matching MainActivity's commit-on-keyboard-hide behavior. */
    private var wasKeyboardVisible = false

    /** True once [onServiceConnected] has fired — gates reads of
     *  [captureService] for any caller that needs an active binder. */
    private var serviceConnected = false

    /** True once [bindService] returned true in [onCreate]. Separate
     *  from [serviceConnected] so [onDestroy] can unbind even when
     *  [finishCurrentIfAny] tears us down before the connection
     *  callback arrives — otherwise the ServiceConnection leaks and
     *  Android logs a "leaked ServiceConnection" warning. */
    private var serviceBindRequested = false

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
            // Always record the binding so [onDestroy]'s
            // unbindService can clean it up, even when we're skipping
            // the actual translation work below. If [finishCurrentIfAny]
            // finished us while the bind was in flight, kicking off a
            // translation cycle on the corpse would burn a capture slot
            // and surface a result no one would see.
            captureService = (binder as CaptureService.LocalBinder).getService()
            serviceConnected = true
            if (isFinishing || isDestroyed) return
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

    /** In-flight completion state: [deferredCompletionPending] is the pending
     *  the running job was launched for. A repeat trigger for the SAME
     *  pending is dropped (one backend batch per pending); a trigger for a
     *  DIFFERENT pending means a newer deferred result superseded the one in
     *  flight — the stale job is cancelled and the new one launches, so the
     *  new result's completion is never silently dropped. */
    private var deferredCompletionJob: kotlinx.coroutines.Job? = null
    private var deferredCompletionPending: com.playtranslate.model.PendingTranslation? = null

    override fun completeDeferredTranslation() {
        // Pre-bind window (this activity fully recreates on rotation): no
        // service yet — keep the pending; the Ready re-render after
        // onServiceReady retries.
        val svc = captureService ?: return
        val ready = vm.result.value as? ResultState.Ready ?: return
        val pending = ready.result.pendingTranslation ?: return
        if (deferredCompletionJob?.isActive == true) {
            if (deferredCompletionPending == pending) return
            deferredCompletionJob?.cancel()
        }
        deferredCompletionPending = pending
        deferredCompletionJob = lifecycleScope.launch {
            try {
                if (pending.isCapture) {
                    // One-shot capture shape: batch translate + session-scoped
                    // History attach, both owned by the service.
                    val perGroup = svc.completeDeferredTranslation(pending)
                    val joined = perGroup.joinToString("\n\n") { it.text }
                    vm.applyDeferredTranslation(
                        pending,
                        if (joined.isBlank()) "—" else joined,
                        perGroup.mapNotNull { it.note }.firstOrNull(),
                        perGroup.mapNotNull { it.backendDisplayName }.firstOrNull(),
                    )
                } else {
                    // Deliberate sentence shape: single text, and the History
                    // attach must keep the exact-row rules (EXTRA_HISTORY_ENTRY_ID).
                    // The recorder gates every write per feature on the
                    // pending's LOOKUP-time eligibility snapshot AND the
                    // current pref — an opted-out lookup records/feeds
                    // nothing even if a pref was enabled before the reveal.
                    val gt = translateSentenceAttachingHistory(
                        svc, pending.groupTexts.firstOrNull() ?: ready.result.originalText,
                        historyEligible = pending.historyEligible,
                        contextEligible = pending.contextEligible,
                    )
                    vm.applyDeferredTranslation(pending, gt.text.ifBlank { "—" }, gt.note, gt.backendDisplayName)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Superseded (or the activity is going away) — the newer job
                // owns the state now; never land "—" for a cancelled run.
                throw e
            } catch (_: Exception) {
                // Ran and failed: land terminal — a visible blank + pending
                // renders a stuck "Translating…". Mirror of the edit path's
                // "—". Identity-guarded, so this can't damage a newer result.
                vm.applyDeferredTranslation(pending, "—", null, null)
            }
        }
    }

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

    override fun onEditOriginalRequested() {
        showEditOverlay()
    }

    override fun onReOcrRequested() {
        val svc = captureService ?: return
        val (prov, path) = reOcrTarget() ?: return
        reOcrJob?.cancel()
        reOcrJob = lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(path) } ?: return@launch
            // Re-run the capture pipeline on the pinned screenshot so the screen walks
            // the normal loading stages; observeSession supersedes the prior session
            // (its terminal state can't reappear on a rotation re-collect).
            observeSession(svc.processScreenshot(
                com.playtranslate.capture.CapturedFrame(
                    bmp, includesSystemUi = prov.frameIncludesSystemUi ?: true,
                    includesOwnOverlays = prov.frameIncludesOwnOverlays ?: false,
                ),
                prov.displayId, prov.region, prov.sourceLangId,
            ))
        }
    }

    /** No live mode here — the pinned frame is the only thing a switch can act on. */
    override fun canReOcr(): Boolean = reOcrTarget() != null

    override fun onChangeLanguageRequested(isSource: Boolean) {
        // Changing a language ends this standalone result (we deliberately don't re-run
        // it). Open the language picker (the same flow as Settings) and finish; on return
        // the user re-captures to see it in the new language. Null the process-global
        // delegate so a stale Settings callback can't fire on our selection.
        LanguageSetupActivity.selectionDelegate = null
        LanguageSetupActivity.launch(
            this,
            if (isSource) LanguageSetupActivity.MODE_SOURCE else LanguageSetupActivity.MODE_TARGET,
        )
        finish()
    }

    /** Re-OCR target = engine/region/screenshot of whatever's on screen — a Ready
     *  result or a "no text detected" status. Both prefer the stable pre-capture
     *  screenshot this screen was launched with over the overwriteable per-display
     *  cache file. Null when neither offers re-OCR. */
    private fun reOcrTarget(): Pair<OcrProvenance, String>? =
        when (val s = vm.result.value) {
            is ResultState.Ready ->
                (s.result.ocrProvenance ?: return null) to
                    (intent.getStringExtra(EXTRA_SCREENSHOT_PATH) ?: s.result.screenshotPath ?: return null)
            is ResultState.Status ->
                (s.ocrProvenance ?: return null) to
                    (intent.getStringExtra(EXTRA_SCREENSHOT_PATH) ?: s.screenshotPath ?: return null)
            else -> null
        }

    override fun onUserScrolled() {
        // No-op — no live mode in this activity.
    }

    // Launched outside the app (single-screen, or backgrounded dual-screen):
    // there's no persistent in-app session to clear, so hide the Clear action.
    override fun showsClearAction(): Boolean = false

    // "Show on screen" is the dual-screen MainActivity host's affordance; this
    // standalone activity covers the screen the game is on, so painting boxes
    // behind itself is meaningless. This host's VM writes also never carry
    // [OnScreenBoxes], so the toggle stays GONE by both gates.
    override fun supportsShowOnScreen(): Boolean = false
    override fun isResultBoxesShownOnScreen(): Boolean = false
    override fun showResultBoxesOnScreen(boxes: OnScreenBoxes) {}
    override fun updateResultBoxesOnScreen(boxes: OnScreenBoxes) {}
    override fun hideResultBoxesOnScreen() {}
    override fun liveShowOnScreenState(): Boolean? = null
    override fun setLiveShowOnScreen(on: Boolean) {}

    // ── SentenceContextProvider ───────────────────────────────────────────

    /** Embedded [WordDetailBottomSheet] reads this at Anki-button tap
     *  time. Prefer the VM's settled values; fall back to launch-time
     *  intent extras during the loading window so a fast Anki tap still
     *  carries the prefetched sentence-word context. */
    override fun currentSentenceContext(): SentenceContext {
        // All three text fields read VM-then-fallback so they're
        // symmetric. Without VM-first on `original`, the field would
        // be null for region-capture launches even though the VM has
        // a valid result — the embedded sheet's `?: args` chain would
        // still recover, but keeping the read patterns aligned avoids
        // future drift.
        val ready = vm.result.value as? ResultState.Ready
        // Snapshot the settled rows ONCE so the legacy map and the
        // surface map are guaranteed to come from the same emission.
        // Reading WordLookupsState twice would risk a fresh emission
        // sliding in between, and reading surfaces from
        // LastSentenceCache later (as oneTapSendSentence used to do)
        // races against live-mode rotating the cache.
        val settledRows = (vm.wordLookups.value as? WordLookupsState.Settled)?.rows
        return SentenceContext(
            original = ready?.result?.originalText
                ?: intent.getStringExtra(EXTRA_SENTENCE_TEXT),
            translation = ready?.result?.translatedText
                ?: intentSeededTranslation,
            wordResults = settledRows?.toLegacyMap() ?: intentSeededWordResults,
            // Args fallback has no surfaces — pass null so the
            // one-tap helper awaits LastSentenceCache, which is
            // atomic per sentence.
            surfaceForms = settledRows?.toSurfaceMap(),
            wordEnrichment = settledRows?.toEnrichmentMap(),
            // Rides the VM result only — a pending is meaningful solely
            // alongside its own result's original text (no intent fallback).
            pending = ready?.result?.pendingTranslation,
        )
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme()
        applyEdgeToEdge(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_translation_result)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(sys.left, sys.top, sys.right, maxOf(sys.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }

        // Register before any other onCreate work so [finishCurrentIfAny]
        // can reach this instance.
        tracker.bind(this)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener {
            signalCaptureOverlayReturnIfNeeded()
            finish()
        }
        // Optional toolbar title (History entries pass their date/time).
        intent.getStringExtra(EXTRA_TOOLBAR_TITLE)?.takeIf { it.isNotEmpty() }?.let { title ->
            findViewById<TextView>(R.id.tvResultToolbarTitle).apply {
                text = title
                isVisible = true
            }
        }
        // A user-initiated system back also counts as "returning" to the overlay.
        // Registered before any fragment's callback, so it's lowest priority — an
        // internal back (e.g. the word↔sentence toggle) is consumed first and this
        // fires only on the back that would finish the screen.
        if (fromCaptureOverlay) {
            onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    signalCaptureOverlayReturnIfNeeded()
                    finish()
                }
            })
        }

        editOverlay = findViewById(R.id.editOverlay)
        etEditOriginal = findViewById(R.id.etEditOriginal)
        setupEditOverlay()

        if (isDragWordMode) setupDragWordTabs(savedInstanceState)

        val hasSentence = intent.hasExtra(EXTRA_SENTENCE_TEXT)
        vm.showStatus(getString(
            if (hasSentence) R.string.status_translating else R.string.status_capturing
        ))

        // Start and bind CaptureService
        val svcIntent = Intent(this, CaptureService::class.java)
        ContextCompat.startForegroundService(this, svcIntent)
        serviceBindRequested = bindService(svcIntent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    /** Signals the capture overlay's controller to re-show on a user-initiated
     *  return. Idempotent — the toolbar back and a system back can't double-fire it. */
    private fun signalCaptureOverlayReturnIfNeeded() {
        if (!fromCaptureOverlay || captureReturnSignalled) return
        captureReturnSignalled = true
        val displayId = intent.getIntExtra(EXTRA_TARGET_DISPLAY_ID, -1)
        if (displayId >= 0) {
            CaptureBackendResolver.activeOverlayUi?.onCaptureDetailBackPressed(displayId)
        }
    }

    override fun onDestroy() {
        // Service event subscriptions are scoped to lifecycleScope, so
        // they're cancelled automatically when the activity is destroyed.
        // No callback nulling — the service no longer exposes mutable
        // callback slots that one activity could clobber for another.
        // Unbind whenever the bind was *requested*, not whenever it
        // completed. A rapid replace-and-finish via [finishCurrentIfAny]
        // can destroy us before onServiceConnected fires; gating on
        // serviceConnected would leak the ServiceConnection in that
        // window.
        if (serviceBindRequested) {
            unbindService(serviceConnection)
            serviceBindRequested = false
        }
        tracker.unbind(this)
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

        // Route everything (override target, screenshot OCR, status-bar /
        // icon-blackout sizing) to the display the drag originated on.
        // Falling back to primaryGameDisplayId() would land on the first
        // selected display, which on multi-display setups is not the
        // display the pre-captured bitmap actually came from.
        val targetDisplayId = intent.getIntExtra(EXTRA_TARGET_DISPLAY_ID, -1)
            .takeIf { it != -1 } ?: svc.primaryGameDisplayId()

        // Apply the drawn region as a runtime override on the originating
        // display so this one-shot uses the user's chosen frame, then
        // re-configure with the persisted display selection. configureSaved
        // no longer takes a region — region is per-display from Prefs +
        // overrides. Pass the originating display as primaryDisplayId so
        // lastInteractedDisplayId tracks the user's actual drag target.
        svc.configureSaved(
            displayIds = prefs.captureDisplayIds,
            primaryDisplayId = targetDisplayId,
        )
        svc.configureOverride(
            targetDisplayId,
            // Unnamed: RegionEntry.displayName resolves the empty label to the
            // generic "Capture region".
            RegionEntry("", topFrac, bottomFrac, leftFrac, rightFrac),
        )

        // Start the one-shot capture and observe its session state.
        // Each session has its own StateFlow scoped to this cycle, so a
        // prior capture's output can never leak in here. Pre-captured
        // screenshot path (single-screen: shot taken before this
        // activity appeared so it shows the game) processes directly;
        // dual-screen path captures fresh.
        val screenshotPath = intent.getStringExtra(EXTRA_SCREENSHOT_PATH)
        val session = if (screenshotPath != null) {
            val bitmap = BitmapFactory.decodeFile(screenshotPath)
            // The capturing side sends the frame's geometry fact alongside
            // the path; senders without it (shared-in images) default to
            // full-display, the safe legacy assumption.
            if (bitmap != null) svc.processScreenshot(
                com.playtranslate.capture.CapturedFrame(
                    bitmap,
                    includesSystemUi = intent.getBooleanExtra(
                        EXTRA_SCREENSHOT_INCLUDES_SYSTEM_UI, true,
                    ),
                    // The pre-shot is a clean capture (our windows blanked
                    // pre-grab), and shared-in images carry no icon either.
                    includesOwnOverlays = false,
                ),
                targetDisplayId,
                // This page paints no boxes on its own (dual-screen
                // show-on-screen is an explicit tap, routed through the
                // deferred-completion funnel), so a hidden translation
                // section can always defer the backend call.
                allowDeferTranslation = true,
            )
            else svc.captureOnce(targetDisplayId, allowDeferTranslation = true)
        } else {
            svc.captureOnce(targetDisplayId, allowDeferTranslation = true)
        }

        observeSession(session)
    }

    /** Drive the VM from a [CaptureSession]'s state flow on lifecycleScope.
     *  The session outlives STOP→START so the observer reattaches to
     *  whatever terminal state has been reached (no replay of any other
     *  capture's output). */
    private fun observeSession(session: CaptureSession) {
        sessionCollectJob?.cancel()
        captureSession = session
        sessionCollectJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                session.state.collect { state ->
                    when (state) {
                        is CaptureState.InProgress -> vm.showStatus(state.message)
                        // OCR done: reveal the source + "Translating…" placeholder;
                        // Done replaces it with the finished translation.
                        is CaptureState.Translating ->
                            vm.showTranslatingPlaceholder(state.originalText, state.segments, applicationContext, state.ocrProvenance)
                        is CaptureState.Done -> vm.displayResult(state.result, applicationContext)
                        is CaptureState.NoText -> vm.showStatus(
                            state.message,
                            ocrProvenance = state.ocrProvenance,
                            screenshotPath = state.screenshotPath,
                        )
                        is CaptureState.Failed -> vm.showError(state.message)
                        // External cancellation supersedes this view —
                        // the user started live mode, replaced the
                        // capture, or the service is tearing down. There
                        // is no useful terminal UI to show, so close
                        // rather than leaving the panel stuck on
                        // "Translating…" indefinitely.
                        CaptureState.Cancelled -> finish()
                    }
                }
            }
        }
    }

    private fun handleSentenceMode(svc: CaptureService, sentenceText: String) {
        val screenshotPath = intent.getStringExtra(EXTRA_SCREENSHOT_PATH)
        val segments = TextSegments.ofText(sentenceText)

        // Drag flow already produced this translation in the lens. Skip
        // the redundant translateOnce so the sentence tab opens straight
        // to the cached text — avoids a "Translating..." flash and any
        // transient ML Kit reload that re-translation could fail on.
        val cachedTranslation = intent.getStringExtra(EXTRA_DRAG_SENTENCE_TRANSLATION)
            ?.takeIf { it.isNotEmpty() }
        if (cachedTranslation != null) {
            val cachedSource = intent.getStringExtra(EXTRA_DRAG_SENTENCE_TRANSLATION_SOURCE)
                ?.takeIf { it.isNotEmpty() }
            val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            vm.displayResult(
                TranslationResult(
                    originalText       = sentenceText,
                    segments           = segments,
                    translatedText     = cachedTranslation,
                    timestamp          = timestamp,
                    screenshotPath     = screenshotPath,
                    note               = null,
                    backendDisplayName = cachedSource,
                    langContext        = Prefs(applicationContext).langContext(),
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

        // DEFERRED: with the translation section hidden there is no consumer
        // for the backend call — land the Ready carrying a pending; revealing
        // the section (or an Anki flow needing the sentence) completes it via
        // completeDeferredTranslation, which keeps the exact-row History
        // rules. The free source==target bypass never defers.
        val sentencePrefs = Prefs(applicationContext)
        val pendingSrcId = sentencePrefs.sourceLangId
        if (sentencePrefs.hideTranslationSection &&
            com.playtranslate.language.SourceLanguageProfiles[pendingSrcId].translationCode !=
                sentencePrefs.targetLang
        ) {
            val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            vm.displayResult(
                TranslationResult(
                    originalText       = sentenceText,
                    segments           = segments,
                    translatedText     = "",
                    timestamp          = timestamp,
                    screenshotPath     = screenshotPath,
                    pendingTranslation = com.playtranslate.model.PendingTranslation(
                        groupTexts = listOf(sentenceText),
                        sourceLangId = pendingSrcId,
                        targetLang = sentencePrefs.targetLang,
                        // Logging eligibility at LOOKUP time — the completion
                        // honors this snapshot, not reveal-time prefs.
                        historyEligible = sentencePrefs.translationHistoryEnabled,
                        contextEligible = sentencePrefs.llmContextEnabled,
                    ),
                    langContext        = Prefs(applicationContext).langContext(),
                ),
                applicationContext,
            )
            return
        }

        vm.showTranslatingPlaceholder(sentenceText, segments, applicationContext)

        // TODO: route through LastSentenceCache.awaitOrStartTranslation so
        //  this caller joins the drag→Anki sheet's in-flight job (and vice
        //  versa) instead of double-firing translateOnce against the
        //  backend. Coalescing only pays off when both call sites use the
        //  cache helper.
        lifecycleScope.launch {
            try {
                val groupTranslation = translateSentenceAttachingHistory(svc, sentenceText)
                val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                val result = TranslationResult(
                    originalText       = sentenceText,
                    segments           = segments,
                    translatedText     = groupTranslation.text,
                    timestamp          = timestamp,
                    screenshotPath     = screenshotPath,
                    note               = groupTranslation.note,
                    backendDisplayName = groupTranslation.backendDisplayName,
                    langContext        = Prefs(applicationContext).langContext(),
                )
                vm.displayResult(result, applicationContext)
            } catch (e: Exception) {
                vm.showError(e.message ?: "Translation failed")
            }
        }
    }

    /** Translate [sentenceText] under the current pair and attach the outcome
     *  to History — the EXACT row for a History tap (pair-matched), by-key
     *  otherwise. Shared by the visible sentence flow (eligibility defaults:
     *  lookup and translation are one moment) and the deferred-reveal
     *  completion (which passes the pending's lookup-time eligibility
     *  snapshot). The exact-row attach takes no history override — the
     *  tapped row already exists, so its recording consent predates this. */
    private suspend fun translateSentenceAttachingHistory(
        svc: CaptureService,
        sentenceText: String,
        historyEligible: Boolean = true,
        contextEligible: Boolean = true,
    ): CaptureService.GroupTranslation {
        // Pair captured BEFORE translateOnce, so the label matches
        // the pair the translation is actually produced under even
        // if prefs change mid-flight.
        val logPrefs = Prefs(applicationContext)
        val currentSource = com.playtranslate.language
            .SourceLanguageProfiles[logPrefs.sourceLangId].translationCode
        val currentTarget = logPrefs.targetLang
        val groupTranslation = svc.translateOnce(sentenceText)
        // Feed the translation back to the log: fills the entry this
        // sentence came from (History tap / drag lookup) instead of
        // leaving it translation-less forever.
        if (groupTranslation.text.isNotEmpty()) {
            val historyRowId = intent.getLongExtra(EXTRA_HISTORY_ENTRY_ID, -1L)
            if (historyRowId >= 0) {
                // History row tap: attach to EXACTLY that row (twin
                // rows can share a key across sessions), and only
                // when this translation's pair matches the row's
                // stored pair — a cross-pair result stays display-
                // only rather than corrupting a row that claims a
                // different pair (translateOnce runs under current
                // prefs; target-side overrides aren't plumbed).
                val storedSource = intent.getStringExtra(EXTRA_HISTORY_SOURCE_LANG)
                val storedTarget = intent.getStringExtra(EXTRA_HISTORY_TARGET_LANG)
                if (storedSource == currentSource && storedTarget == currentTarget) {
                    svc.translationLogRecorder.onHistoryEntryTranslated(
                        historyRowId, sentenceText, groupTranslation.text,
                        currentSource, currentTarget,
                        groupTranslation.backendDisplayName,
                        contextEligible = contextEligible,
                    )
                }
            } else {
                svc.translationLogRecorder.onDeliberateTranslation(
                    sentenceText, groupTranslation.text,
                    currentSource, currentTarget,
                    com.playtranslate.translationlog.TranslationHistoryStore.PROVENANCE_LOOKUP,
                    groupTranslation.backendDisplayName,
                    historyEligible = historyEligible,
                    contextEligible = contextEligible,
                )
            }
        }
        return groupTranslation
    }

    private fun applyTheme() {
        com.playtranslate.applyTheme(this)
    }

    // ── Edit original overlay (parity with MainActivity) ──────────────────

    /** Prefer the fragment's on-screen text (preserves OCR line breaks)
     *  and fall back to the VM's settled result. Bails if neither is
     *  available — nothing to edit yet. */
    private fun showEditOverlay() {
        val displayed = resultFragment?.getDisplayedOriginalText()?.takeIf { it.isNotBlank() }
        val currentText = displayed
            ?: (vm.result.value as? ResultState.Ready)?.result?.originalText
            ?: return
        etEditOriginal.setText(currentText)
        etEditOriginal.setSelection(currentText.length)
        editOverlay.visibility = View.VISIBLE
        etEditOriginal.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.showSoftInput(etEditOriginal, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }

    private fun commitEdit() {
        if (editOverlay.visibility != View.VISIBLE) return
        editOverlay.visibility = View.GONE
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(etEditOriginal.windowToken, 0)
        val newText = etEditOriginal.text?.toString()?.trim() ?: return
        if (newText.isBlank()) return

        vm.updateOriginalText(newText, applicationContext)

        // The edit supersedes the in-flight capture (it's translating the OLD
        // source) — stop collecting + cancel it so a late Done can't overwrite the
        // edit and its Cancelled doesn't finish() us.
        sessionCollectJob?.cancel()
        captureSession?.cancel()
        captureSession = null

        editTranslationJob?.cancel()
        editTranslationJob = lifecycleScope.launch {
            try {
                // Route through the service so edit re-translations pick up the
                // current language pair via translateOnce's self-heal and inherit
                // the full backend waterfall (mirrors MainActivity.commitEdit).
                val svc = captureService
                if (svc == null) {
                    vm.updateTranslation("—", appCtx = applicationContext)
                    return@launch
                }
                val groupTranslation = svc.translateOnce(newText)
                vm.updateTranslation(groupTranslation.text, groupTranslation.backendDisplayName, applicationContext)
            } catch (_: Exception) {
                vm.updateTranslation("—", appCtx = applicationContext)
            }
        }
    }

    private fun setupEditOverlay() {
        etEditOriginal.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO) {
                commitEdit()
                true
            } else false
        }

        window.decorView.viewTreeObserver.addOnGlobalLayoutListener {
            val rect = android.graphics.Rect()
            window.decorView.getWindowVisibleDisplayFrame(rect)
            val screenHeight = window.decorView.height
            val keyboardVisible = (screenHeight - rect.bottom) > screenHeight * 0.15f
            if (wasKeyboardVisible && !keyboardVisible && editOverlay.isVisible) {
                commitEdit()
            }
            wasKeyboardVisible = keyboardVisible
        }
    }

    private enum class Tab { SENTENCE, WORD }

    companion object {
        /** See [CurrentActivityTracker]. [DragLookupController.openSentenceInApp]
         *  calls [finishCurrentIfAny] before launching a new instance so
         *  MULTIPLE_TASK doesn't leave the old one orphaned in a hidden task. */
        private val tracker = CurrentActivityTracker<TranslationResultActivity>()
        fun finishCurrentIfAny() = tracker.finishCurrent()

        const val EXTRA_TOP_FRAC = "extra_top_frac"
        const val EXTRA_BOTTOM_FRAC = "extra_bottom_frac"
        const val EXTRA_LEFT_FRAC = "extra_left_frac"
        const val EXTRA_RIGHT_FRAC = "extra_right_frac"
        const val EXTRA_SCREENSHOT_PATH = "extra_screenshot_path"

            /** Geometry fact for [EXTRA_SCREENSHOT_PATH]'s frame — whether it
             *  contains system UI (drives the status-bar crop). Absent = true:
             *  legacy/full-display, the safe default for shared-in images. */
            const val EXTRA_SCREENSHOT_INCLUDES_SYSTEM_UI = "screenshot_includes_system_ui"
        const val EXTRA_SENTENCE_TEXT = "extra_sentence_text"
        /** Display the drag originated on. Routes the override + screenshot
         *  processing back to the same display the icon (and pre-captured
         *  bitmap) live on, instead of the multi-display "primary" which
         *  could be a different screen. */
        const val EXTRA_TARGET_DISPLAY_ID = "extra_target_display_id"
        /** Set by the capture overlay's open-detail launch so this screen can
         *  signal a return (back / toolbar-back) to re-show the overlay. */
        const val EXTRA_FROM_CAPTURE_OVERLAY = "extra_from_capture_overlay"
        /** Drag-flow lens "Open" tap: the looked-up word from the magnifier
         *  becomes the right segment label of the Sentence/Word pill toggle
         *  in the toolbar. When absent, the activity stays in plain
         *  region-capture mode (no pill, top Anki button stays). */
        const val EXTRA_DRAG_WORD = "extra_drag_word"
        const val EXTRA_DRAG_READING = "extra_drag_reading"
        const val EXTRA_DRAG_SENTENCE_TRANSLATION = "extra_drag_sentence_translation"
        /** Display name of the backend that produced [EXTRA_DRAG_SENTENCE_TRANSLATION]
         *  in the lens. Surfaces as "Translated by …" below the cached translation
         *  in the sentence tab so the cached path matches the regular translate
         *  path's bottom label. Null when the source wasn't captured at lens time. */
        const val EXTRA_DRAG_SENTENCE_TRANSLATION_SOURCE = "extra_drag_sentence_translation_source"

        /** Optional toolbar title (centered) — History entry launches pass
         *  the entry's date/time. Absent for every other launch path. */
        const val EXTRA_TOOLBAR_TITLE = "extra_toolbar_title"

        /** History-tap identity: the tapped row's id plus its STORED
         *  language pair, so a late translation attaches to exactly that
         *  row and only under a matching pair (see handleSentenceMode). */
        const val EXTRA_HISTORY_ENTRY_ID = "extra_history_entry_id"
        const val EXTRA_HISTORY_SOURCE_LANG = "extra_history_source_lang"
        const val EXTRA_HISTORY_TARGET_LANG = "extra_history_target_lang"
        /** The tapped row's at_ms (epoch): the sentence's capture moment,
         *  passed on to the Anki flow as its game-audio ring anchor — the
         *  result object this page constructs is stamped at page-open, which
         *  says nothing about when the ROW's line was heard. */
        const val EXTRA_HISTORY_AT_MS = "extra_history_at_ms"
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
