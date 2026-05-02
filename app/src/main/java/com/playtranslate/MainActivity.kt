package com.playtranslate

import android.Manifest
import com.playtranslate.applyTheme
import com.playtranslate.themeColor
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.StyleSpan
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import com.google.mlkit.nl.translate.TranslateLanguage
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Toast
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.RadioButton
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import com.playtranslate.BuildConfig
import com.playtranslate.diagnostics.LogExporter
import com.playtranslate.language.LanguagePackCatalogLoader
import com.playtranslate.language.LanguagePackStore
import com.playtranslate.language.HintTextKind
import com.playtranslate.language.PreloadResult
import com.playtranslate.language.SourceLanguageEngines
import com.playtranslate.language.SourceLanguageProfiles
import com.playtranslate.model.TextSegment
import com.playtranslate.model.TranslationResult
import com.playtranslate.ui.ClickableTextView
import com.playtranslate.ui.DimController
import com.playtranslate.ui.OverlayAlert
import android.net.Uri
import com.playtranslate.AnkiManager
import com.playtranslate.ui.AddCustomRegionSheet
import com.playtranslate.ui.AnkiReviewBottomSheet
import com.playtranslate.ui.RegionPickerSheet
import com.playtranslate.ui.SettingsBottomSheet
import com.playtranslate.ui.LastSentenceCache
import com.playtranslate.ui.TranslationResultFragment
import com.playtranslate.ui.WordDetailBottomSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class MainActivity :
    AppCompatActivity(),
    TranslationResultFragment.TranslationResultHost {

    // ── Views ─────────────────────────────────────────────────────────────

    private lateinit var btnTranslate: View
    private lateinit var btnSettings: View
    private lateinit var btnRegions: View
    private lateinit var tvTranslateTitle: TextView
    private lateinit var tvTranslateSubtitle: TextView
    private lateinit var btnLiveToggle: View
    private lateinit var ivLiveToggle: ImageView
    private lateinit var tvLiveToggle: TextView
    private lateinit var menuOverlay: FrameLayout
    private lateinit var menuPanel: View
    private lateinit var menuScrim: View
    private lateinit var menuItemLiveIcon: ImageView
    private lateinit var menuItemLiveLabel: TextView
    private lateinit var resultsContainer: View
    private lateinit var regionPickerContainer: View
    private lateinit var settingsContainer: View
    private lateinit var onboardingContainer: View
    private lateinit var pageWelcome: View
    private lateinit var pageNotif: View
    private lateinit var pageA11y: View
    private lateinit var pageA11ySingle: View
    private lateinit var rowWelcomeGameLang: View
    private lateinit var rowWelcomeYourLang: View
    private lateinit var btnWelcomeContinue: Button
    // Shared across Continue taps so the installer's single-flight guard
    // engages on rapid double-taps. Lazy so we construct after lifecycleScope
    // is available.
    private val welcomeTargetInstaller by lazy {
        com.playtranslate.ui.TargetPackInstaller(this, lifecycleScope)
    }
    private lateinit var editOverlay: android.widget.LinearLayout
    private lateinit var etEditOriginal: android.widget.EditText

    private var editTranslationJob: Job? = null
    private var wasKeyboardVisible = false

    /** Tracks the latest one-shot capture session this activity
     *  initiated. The wireServiceCallbacks collector follows whichever
     *  session is current via flatMapLatest, drives [resultVm] from
     *  its state, and clears this back to null once the session reaches
     *  a terminal state — that way a later local update (drag-sentence)
     *  doesn't get clobbered by a STOP→START re-collect replaying the
     *  session's terminal value. */
    private val _currentCaptureSession = MutableStateFlow<CaptureSession?>(null)

    // ── Fragment ───────────────────────────────────────────────────────────

    private val resultFragment: TranslationResultFragment?
        get() = supportFragmentManager.findFragmentById(R.id.resultsContainer) as? TranslationResultFragment

    /** Activity-scoped state for the result surface. The fragment
     *  observes this VM; this activity mutates it. */
    private val resultVm: com.playtranslate.ui.TranslationResultViewModel by viewModels()

    // ── TranslationResultHost event handlers ──────────────────────────────

    override fun onEditOriginalRequested() {
        showEditOverlay()
    }

    override fun onUserScrolled() {
        if (isLiveMode && !suppressScrollPause) pauseLiveMode()
    }

    // ── Drag-to-select dropdown state ────────────────────────────────────
    private var inDragMode = false
    private var dropdownPopup: PopupWindow? = null
    private var dropdownHighlightedRow = 0
    private var dropdownRows = listOf<View>()
    private var dropdownItemHeightPx = 0f
    private var dropdownTopY = 0f
    private var dropdownCommitAction: (() -> Unit)? = null

    // Region-specific dropdown state
    private var dropdownRegionOrder = listOf<Int>()
    private var dropdownGameDisplay: Display? = null
    private var dropdownRegions = listOf<RegionEntry>()

    // ── Display listener (detects screen connect/disconnect) ─────────────

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) { runOnUiThread {
            if (!isFinishing) {
                checkOnboardingState()
                PlayTranslateAccessibilityService.instance?.ensureFloatingIcon()
            }
        } }
        override fun onDisplayRemoved(displayId: Int) { runOnUiThread {
            if (!isFinishing) {
                checkOnboardingState()
                PlayTranslateAccessibilityService.instance?.ensureFloatingIcon()
            }
        } }
        override fun onDisplayChanged(displayId: Int) {}
    }

    // ── State ─────────────────────────────────────────────────────────────

    private enum class Tab { TRANSLATE, SETTINGS, REGIONS }
    private var selectedTab = Tab.TRANSLATE

    private val prefs by lazy { Prefs(this) }
    private var dimController: DimController? = null

    private val isLiveMode get() = captureService?.liveModeState?.value == true
    /** True while programmatic scrollTo(0,0) is in progress to prevent auto-pause. */
    private var suppressScrollPause = false

    // ── Service ───────────────────────────────────────────────────────────

    private var captureService: CaptureService? = null
    private var serviceConnected = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            captureService = (binder as CaptureService.LocalBinder).getService()
            serviceConnected = true
            wireServiceCallbacks()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            serviceConnected = false
            captureService = null
        }
    }

    // ── Notification permission ────────────────────────────────────────────

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                }
            )
        }
        checkOnboardingState()
    }

    private val requestAnkiPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) Toast.makeText(this, getString(R.string.anki_permission_denied), Toast.LENGTH_SHORT).show()
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
        pauseLiveMode()
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
        pauseLiveMode()
    }

    override fun getAnkiPermissionLauncher() = requestAnkiPermission

    // ── Lifecycle ─────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        // Migration must run before applyTheme so first launch after the
        // 4-themes → mode+accent rollout already shows the user's intended
        // palette (otherwise they'd see Default until next recreate).
        prefs.migrateLegacyPrefs()
        applyTheme()
        super.onCreate(savedInstanceState)
        maybePromptForCrashShare()
        // Suppress the window transition that would otherwise flash when recreating for a theme change
        if (prefs.suppressNextTransition) {
            prefs.suppressNextTransition = false
            if (Build.VERSION.SDK_INT >= 34) {
                overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            } else {
                @Suppress("DEPRECATION")
                overridePendingTransition(0, 0)
            }
        }
        setContentView(R.layout.activity_main)

        // Prevent PlayTranslate's own UI from appearing in screenshots
        // (including the accessibility takeScreenshot path used by the
        // capture loop). In Android multi-window mode both the game and
        // this app share one display; without FLAG_SECURE the OCR would
        // read the translated text we just rendered and try to translate
        // it again, creating a feedback loop. SurfaceFlinger enforces
        // FLAG_SECURE in all capture paths, so this is a complete fix.
        // Cost: system screenshot tools can't capture PlayTranslate's own
        // UI — users who want to share their translator UI would have to
        // screenshot externally, which is acceptable for a translation tool.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        hideNavigationBar()

        // Seed the companion var from the Activity's own multi-window state.
        // onMultiWindowModeChanged does NOT fire on a launch-into-split-screen
        // start (the state didn't "change" — it just began in that state), so
        // we must read it here. The explicit receivers disambiguate between
        // the Activity method (this.isInMultiWindowMode) and the companion
        // var (MainActivity.isInMultiWindowMode) — same name, different
        // things.
        MainActivity.isInMultiWindowMode = this.isInMultiWindowMode

        bindViews()

        // Remove inline fragments that Android may have restored from saved state.
        // We manage their lifecycle ourselves via tab selection.
        supportFragmentManager.findFragmentByTag(SettingsBottomSheet.TAG)?.let {
            supportFragmentManager.beginTransaction().remove(it).commitNow()
        }
        supportFragmentManager.findFragmentByTag(RegionPickerSheet.TAG)?.let {
            supportFragmentManager.beginTransaction().remove(it).commitNow()
        }

        setupRegionButton()
        setupButtons()
        setupOnboarding()
        setupEditOverlay()
        startAndBindService()
        (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
            .registerDisplayListener(displayListener, null)
        // Only preload when the source pack is actually present. Fresh-
        // install and data-wiped users route through the welcome flow to
        // download a pack first; preloading before that would just log a
        // PackMissing and is pointless.
        if (LanguagePackStore.isInstalled(applicationContext, prefs.sourceLangId)) {
            lifecycleScope.launch(Dispatchers.IO) {
                preloadEngineAndRecover(prefs.sourceLangId)
            }
        }

        // One-shot migration: if the user already has a non-English target but
        // no target gloss pack installed, offer to download it.
        checkTargetPackMigration()

        // Fragment event handlers live on TranslationResultHost (which
        // this activity already implements) — no separate sink wiring
        // needed.

        // Restore previously selected tab (survives recreate for theme changes)
        val restoredTab = Tab.entries.getOrElse(
            savedInstanceState?.getInt("selected_tab", 0) ?: 0
        ) { Tab.TRANSLATE }
        selectTab(restoredTab)
        when (restoredTab) {
            Tab.SETTINGS -> openSettingsInline()
            Tab.REGIONS -> openRegionPickerInline()
            else -> {}
        }

        // Start dim controller on dual-screen when not in live mode
        if (Prefs.hasMultipleDisplays(this) && !isLiveMode) {
            dimController = DimController(findViewById(R.id.dimOverlay))
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        when (intent?.action) {
            ACTION_DRAG_SENTENCE -> handleDragSentence(intent)
            ACTION_DRAG_WORD -> handleDragWord(intent)
            ACTION_REGION_CAPTURE -> handleRegionCapture()
            ACTION_START_LIVE -> if (!isLiveMode) {
                // Post so onResume sets isInForeground before startLive triggers
                // updateForegroundState — otherwise In-App Only mode immediately stops.
                window.decorView.post {
                    if (!isDestroyed && !isFinishing) withAccessibility { startLiveMode() }
                }
            }
            ACTION_STOP_LIVE -> if (isLiveMode) stopLiveMode()
            ACTION_ADD_CUSTOM_REGION -> openAddCustomRegionFromDropdown()
            ACTION_REFRESH_REGION_LABEL -> {
                captureService?.clearOverride()
                refreshRegionPicker()
            }
            ACTION_OPEN_SETTINGS -> {
                selectTab(Tab.SETTINGS)
                openSettingsInline()
            }
        }
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        dimController?.onInteraction()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            dimController?.onInteraction()
            hideNavigationBar()
        }
    }

    private fun hideNavigationBar() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.navigationBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onResume() {
        super.onResume()
        isInForeground = true
        dimController?.onInteraction()
        setupDetectionLog()
        // Service event subscription is set up once in
        // [serviceConnection.onServiceConnected] and held by
        // lifecycleScope's repeatOnLifecycle — no per-resume re-wire
        // needed. (The old re-wire was a band-aid for
        // TranslationResultActivity nulling shared callback fields,
        // which it no longer does.)
        PlayTranslateAccessibilityService.instance?.ensureFloatingIcon()
        checkOnboardingState()
        maybeCheckForUpdates()
        if (onboardingContainer.visibility == View.VISIBLE) return
        if (isSingleScreen()) return
        initLiveHintText()
        updateRegionButton()
        updateActionButtonState()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("selected_tab", selectedTab.ordinal)
    }

    override fun onStop() {
        super.onStop()
        isInForeground = false
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        MainActivity.isInMultiWindowMode = isInMultiWindowMode
        // Let a running live session adapt if the viewport predicate flipped.
        // No-op if live mode isn't active.
        CaptureService.instance?.onMultiWindowChanged()
    }

    override fun onDestroy() {
        dimController?.cancel()
        dimController = null
        // Defensive clear so a stale companion var can't lie to a predicate
        // that runs after the activity is gone. The real safety net is the
        // isInForeground gate inside Prefs.isSingleScreen, but explicit is
        // better than implicit.
        MainActivity.isInMultiWindowMode = false
        (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
            .unregisterDisplayListener(displayListener)
        if (isLiveMode && !isChangingConfigurations) captureService?.stopLive()
        if (serviceConnected) unbindService(serviceConnection)
        super.onDestroy()
    }

    /**
     * Preload the engine for [id]. Recovery behavior is tiered so we
     * don't punish transient failures with destructive pack deletion:
     *  - [PreloadResult.PackMissing]: shouldn't happen (caller gated on
     *    isInstalled). Log as anomaly.
     *  - [PreloadResult.PackCorrupt]: confirmed on-disk integrity failure
     *    (e.g. SQLite can't open). Uninstall the pack so the user's next
     *    deliberate language interaction routes through download/recovery
     *    rather than a silent crash loop.
     *  - [PreloadResult.TokenizerInitFailed]: tokenizer library threw
     *    during warm-up but the pack on disk looks fine. Likely OOM or
     *    transient; log and let the next user action retry instead of
     *    destroying a valid offline install.
     */
    private suspend fun preloadEngineAndRecover(id: com.playtranslate.language.SourceLangId) {
        when (val r = SourceLanguageEngines.get(applicationContext, id).preload()) {
            is PreloadResult.Success -> { /* nothing to do */ }
            is PreloadResult.PackMissing ->
                android.util.Log.w("MainActivity", "preload($id) reported PackMissing after isInstalled() passed")
            is PreloadResult.PackCorrupt -> {
                android.util.Log.w("MainActivity", "preload($id) reported PackCorrupt: ${r.reason} — uninstalling")
                LanguagePackStore.uninstall(applicationContext, id)
            }
            is PreloadResult.TokenizerInitFailed ->
                android.util.Log.w("MainActivity", "preload($id) tokenizer warm-up failed: ${r.reason} — keeping pack, next call retries")
        }
    }

    // ── Setup ─────────────────────────────────────────────────────────────

    private fun bindViews() {
        btnTranslate         = findViewById(R.id.btnTranslate)
        btnSettings          = findViewById(R.id.btnSettings)
        btnRegions           = findViewById(R.id.btnRegions)
        tvTranslateTitle     = findViewById(R.id.tvTranslateTitle)
        tvTranslateSubtitle  = findViewById(R.id.tvTranslateSubtitle)
        btnLiveToggle        = findViewById(R.id.btnLiveToggle)
        ivLiveToggle         = findViewById(R.id.ivLiveToggle)
        tvLiveToggle         = findViewById(R.id.tvLiveToggle)
        menuOverlay          = findViewById(R.id.menuOverlay)
        menuPanel            = findViewById(R.id.menuPanel)
        menuScrim            = findViewById(R.id.menuScrim)
        menuItemLiveIcon     = findViewById(R.id.menuItemLiveIcon)
        menuItemLiveLabel    = findViewById(R.id.menuItemLiveLabel)
        resultsContainer     = findViewById(R.id.resultsContainer)
        regionPickerContainer = findViewById(R.id.regionPickerContainer)
        settingsContainer    = findViewById(R.id.settingsContainer)
        onboardingContainer  = findViewById(R.id.onboardingContainer)
        pageWelcome          = findViewById(R.id.pageWelcome)
        pageNotif            = findViewById(R.id.pageNotif)
        pageA11y             = findViewById(R.id.pageA11y)
        pageA11ySingle       = findViewById(R.id.pageA11ySingle)
        rowWelcomeGameLang   = findViewById(R.id.rowWelcomeGameLang)
        rowWelcomeYourLang   = findViewById(R.id.rowWelcomeYourLang)
        btnWelcomeContinue   = findViewById(R.id.btnWelcomeContinue)
        editOverlay          = findViewById(R.id.editOverlay)
        etEditOriginal       = findViewById(R.id.etEditOriginal)
    }

    private fun setupRegionButton() {
        updateRegionButton()
        applyDragDropdownGestures(btnRegions) { showRegionDropdown(it) }
    }

    /** Attaches long-press + drag-to-select gestures to [btn]. */
    private fun applyDragDropdownGestures(btn: View, showDropdown: (View) -> Unit) {
        btn.setOnLongClickListener {
            inDragMode = true
            btn.isPressed = false
            showDropdown(btn)
            true
        }
        btn.setOnTouchListener { _, event ->
            if (!inDragMode) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE   -> { updateDropdownHighlight(event.rawY); true }
                MotionEvent.ACTION_UP     -> {
                    val action = dropdownCommitAction
                    dismissDropdown()
                    inDragMode = false
                    action?.invoke()
                    true
                }
                MotionEvent.ACTION_CANCEL -> { dismissDropdown(); inDragMode = false; false }
                else -> false
            }
        }
    }

    private fun showRegionPicker() {
        if (selectedTab == Tab.REGIONS) {
            selectTab(Tab.TRANSLATE)
            return
        }
        selectTab(Tab.REGIONS)
        openRegionPickerInline()
    }

    private fun openRegionPickerInline() {
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val gameDisplay = displayManager.getDisplay(prefs.captureDisplayId)
        if (gameDisplay == null) { selectTab(Tab.TRANSLATE); return }

        val sheet = RegionPickerSheet().apply {
            setShowsDialog(false)
            this.gameDisplay = gameDisplay
            onSaved = {
                configureService()
            }
            onTranslateOnce = { region ->
                selectTab(Tab.TRANSLATE)
                captureService?.configureOverride(region)
                withAccessibility { startOneShotCapture() }
            }
            onClose = { hideRegionPicker() }
        }
        supportFragmentManager.beginTransaction()
            .replace(R.id.regionPickerContainer, sheet, RegionPickerSheet.TAG)
            .commit()
    }

    private fun hideRegionPicker() {
        selectTab(Tab.TRANSLATE)
    }

    private fun refreshRegionPicker() {
        (supportFragmentManager.findFragmentByTag(RegionPickerSheet.TAG) as? RegionPickerSheet)
            ?.refreshFromPrefs()
    }

    private fun updateRegionButton() {
        val region = captureService?.activeRegion ?: prefs.getSelectedRegion()
        val label = region.label.ifEmpty { "Full screen" }
        val isInAppOnly = prefs.hideGameOverlays && !isSingleScreen()
        val overlayLive = isLiveMode && !isInAppOnly
        val prefix = if (overlayLive) "Reload " else "Translate "
        tvTranslateTitle.text = SpannableStringBuilder(prefix + label).apply {
            setSpan(StyleSpan(Typeface.BOLD), prefix.length, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val hintLabel = when (SourceLanguageProfiles[prefs.sourceLangId].hintTextKind) {
            HintTextKind.PINYIN -> "pinyin"
            HintTextKind.FURIGANA -> "furigana"
            else -> "furigana"
        }
        tvTranslateSubtitle.text = when (captureService?.holdBehavior) {
            CaptureService.HoldBehavior.HIDE_TRANSLATIONS ->
                "Hold to hide translations on game screen"
            CaptureService.HoldBehavior.SHOW_TRANSLATIONS_OVER_FURIGANA ->
                "Hold to show translations instead of $hintLabel"
            CaptureService.HoldBehavior.SHOW_FURIGANA ->
                "Hold to show $hintLabel on game screen"
            else ->
                "Hold to show translations on game screen"
        }
    }

    private fun toggleLiveMode() {
        if (isLiveMode) stopLiveMode() else withAccessibility { startLiveMode() }
    }

    private fun startLiveMode() {
        val willBeInAppOnly = prefs.hideGameOverlays && !isSingleScreen()
        if (willBeInAppOnly) {
            // Dual screen + hide overlays: switch to translate tab so results are visible
            selectTab(Tab.TRANSLATE)
        }
        doStartLive()
    }

    private fun doStartLive() {
        val hadPopup = PlayTranslateAccessibilityService.instance?.dragLookupController?.isPopupShowing == true
        PlayTranslateAccessibilityService.instance?.dragLookupController?.dismiss()
        ensureConfigured()
        if (hadPopup) {
            window.decorView.postDelayed({ captureService?.startLive() }, 100)
        } else {
            captureService?.startLive()
        }
    }

    private fun stopLiveMode() {
        captureService?.stopLive()
    }

    /** Called by the LiveData observer when live mode state changes. */
    private fun onLiveModeChanged(isLive: Boolean) {
        updateMenuLiveItem()
        updateRegionButton()
        // Dim controller: cancel on any live mode change, recreate only when stopping
        dimController?.cancel()
        dimController = null
        if (!isLive && Prefs.hasMultipleDisplays(this)) {
            dimController = DimController(findViewById(R.id.dimOverlay))
        }
        if (isLive) {
            resultVm.showStatus(searchingStatusText())
        } else {
            if (resultVm.result.value !is com.playtranslate.ui.ResultState.Ready) {
                resultVm.showStatus(getString(R.string.status_idle), showHint = true)
            }
        }
    }

    private fun pauseLiveMode() {
        if (isLiveMode) stopLiveMode()
    }

    private var translateHoldActive = false

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun setupButtons() {
        btnTranslate.setOnClickListener {
            selectTab(Tab.TRANSLATE)
            if (isLiveMode) {
                captureService?.refreshLiveOverlay()
            } else {
                withAccessibility { startOneShotCapture() }
            }
        }
        btnTranslate.setOnLongClickListener {
            translateHoldActive = true
            val holdColor = themeColor(R.attr.ptTextTranslation)
            val radius = 6f * resources.displayMetrics.density
            btnTranslate.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = radius
                setColor(holdColor)
            }
            tvTranslateTitle.setTextColor(themeColor(R.attr.ptAccentOn))
            tvTranslateSubtitle.setTextColor(themeColor(R.attr.ptAccentOn))
            if (isLiveMode) {
                captureService?.holdStart()
            } else {
                withAccessibility { captureService?.holdStart() }
            }
            true
        }
        btnTranslate.setOnTouchListener { _, event ->
            if (translateHoldActive && (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL)) {
                translateHoldActive = false
                captureService?.holdEnd()
                selectTab(selectedTab) // restore button colors
            }
            false
        }

        btnSettings.setOnClickListener { openSettings() }
        btnRegions.setOnClickListener { showRegionPicker() }
        btnLiveToggle.setOnClickListener { toggleLiveMode() }
        applyDragDropdownGestures(btnLiveToggle) { showAutoModeDropdown(it) }
        menuScrim.setOnClickListener { dismissMenu() }
        findViewById<View>(R.id.menuItemSettings).setOnClickListener { dismissMenu(); openSettings() }
        findViewById<View>(R.id.menuItemLive).setOnClickListener { dismissMenu(); toggleLiveMode() }
        findViewById<View>(R.id.menuItemRegion).setOnClickListener { dismissMenu(); showRegionPicker() }
        findViewById<View>(R.id.menuItemTranslations).setOnClickListener { dismissMenu(); hideRegionPicker() }
        findViewById<View>(R.id.menuItemClose).setOnClickListener { dismissMenu() }

    }

    // ── Slide-in menu ──────────────────────────────────────────────────

    private fun showMenu() {
        updateMenuLiveItem()
        menuOverlay.visibility = View.VISIBLE
        menuScrim.alpha = 0f
        menuPanel.translationX = menuPanel.width.toFloat().takeIf { it > 0f } ?: 400f
        val slideIn = ObjectAnimator.ofFloat(menuPanel, View.TRANSLATION_X, 0f).apply {
            duration = 200
            interpolator = DecelerateInterpolator()
        }
        val fadeIn = ObjectAnimator.ofFloat(menuScrim, View.ALPHA, 1f).apply {
            duration = 200
        }
        AnimatorSet().apply { playTogether(slideIn, fadeIn); start() }
    }

    private fun dismissMenu() {
        val slideOut = ObjectAnimator.ofFloat(menuPanel, View.TRANSLATION_X, menuPanel.width.toFloat()).apply {
            duration = 200
            interpolator = AccelerateInterpolator()
        }
        val fadeOut = ObjectAnimator.ofFloat(menuScrim, View.ALPHA, 0f).apply {
            duration = 200
        }
        AnimatorSet().apply {
            playTogether(slideOut, fadeOut)
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    menuOverlay.visibility = View.GONE
                }
            })
            start()
        }
    }

    private val liveRedColor by lazy { themeColor(R.attr.ptDanger) }

    private fun updateMenuLiveItem() {
        if (isLiveMode) {
            menuItemLiveIcon.setImageResource(R.drawable.ic_pause)
            menuItemLiveLabel.text = "Pause Auto"
            ivLiveToggle.setImageResource(R.drawable.ic_pause)
            tvLiveToggle.text = "Pause"
            ivLiveToggle.imageTintList = android.content.res.ColorStateList.valueOf(liveRedColor)
            tvLiveToggle.setTextColor(liveRedColor)
        } else {
            menuItemLiveIcon.setImageResource(R.drawable.ic_play)
            menuItemLiveLabel.text = "Auto Translate"
            ivLiveToggle.setImageResource(R.drawable.ic_play)
            tvLiveToggle.text = "Auto"
            val normalColor = themeColor(R.attr.ptText)
            ivLiveToggle.imageTintList = android.content.res.ColorStateList.valueOf(normalColor)
            tvLiveToggle.setTextColor(normalColor)
        }
    }

    private fun selectTab(tab: Tab) {
        if (selectedTab != tab) {
            selectedTab = tab

            // ── Container visibility ──
            resultsContainer.visibility = if (tab == Tab.TRANSLATE) View.VISIBLE else View.GONE
            settingsContainer.visibility = if (tab == Tab.SETTINGS) View.VISIBLE else View.GONE
            regionPickerContainer.visibility = if (tab == Tab.REGIONS) View.VISIBLE else View.GONE

            // Remove inline fragments for tabs we're leaving
            if (tab != Tab.SETTINGS) {
                supportFragmentManager.findFragmentByTag(SettingsBottomSheet.TAG)?.let {
                    supportFragmentManager.beginTransaction().remove(it).commitAllowingStateLoss()
                }
            }
            if (tab != Tab.REGIONS) {
                supportFragmentManager.findFragmentByTag(RegionPickerSheet.TAG)?.let {
                    supportFragmentManager.beginTransaction().remove(it).commitAllowingStateLoss()
                }
            }
        }

        // ── Button visuals (always re-applied) ──
        val accentBg = themeColor(R.attr.ptAccent)
        val accentText = themeColor(R.attr.ptAccentOn)
        val normalText = themeColor(R.attr.ptText)
        val strokeColor = themeColor(R.attr.ptTextMuted)
        val radius = 6f * resources.displayMetrics.density

        fun tabBackground(selected: Boolean): android.graphics.drawable.Drawable {
            val shape = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = radius
                if (selected) {
                    setColor(accentBg)
                } else {
                    setColor(android.graphics.Color.TRANSPARENT)
                    setStroke((1 * resources.displayMetrics.density).toInt(), strokeColor)
                }
            }
            return android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x40000000),
                shape, null
            )
        }

        val settingsSelected = tab == Tab.SETTINGS
        btnSettings.background = tabBackground(settingsSelected)
        findViewById<ImageView>(R.id.ivSettings).imageTintList =
            android.content.res.ColorStateList.valueOf(if (settingsSelected) accentText else normalText)
        findViewById<TextView>(R.id.tvSettings).setTextColor(
            if (settingsSelected) accentText else normalText
        )

        val regionsSelected = tab == Tab.REGIONS
        btnRegions.background = tabBackground(regionsSelected)
        findViewById<ImageView>(R.id.ivRegions).imageTintList =
            android.content.res.ColorStateList.valueOf(if (regionsSelected) accentText else normalText)
        findViewById<TextView>(R.id.tvRegions).setTextColor(
            if (regionsSelected) accentText else normalText
        )

        val translateSelected = tab == Tab.TRANSLATE
        btnTranslate.background = tabBackground(translateSelected)
        tvTranslateTitle.setTextColor(if (translateSelected) accentText else normalText)
        tvTranslateSubtitle.setTextColor(if (translateSelected) accentText else normalText)
        tvTranslateSubtitle.alpha = if (translateSelected) 0.7f else 1f
    }

    private fun applyTheme() {
        com.playtranslate.applyTheme(this)
    }

    private fun openSettings() {
        if (selectedTab == Tab.SETTINGS) {
            selectTab(Tab.TRANSLATE)
            return
        }
        selectTab(Tab.SETTINGS)
        openSettingsInline()
    }

    /** Add the settings fragment to the already-visible settings container. */
    private fun openSettingsInline() {
        val sheet = SettingsBottomSheet.newInstance(hideDismiss = false).apply {
            setShowsDialog(false)
            onDisplayChanged = {
                val wasLive = captureService?.isLive == true
                // configureService() writes display/region; language managers
                // self-heal via ensureLanguageManagersFor.
                configureService()
                PlayTranslateAccessibilityService.instance?.ensureFloatingIcon()
                if (wasLive) {
                    captureService?.stopLive()
                    withAccessibility { doStartLive() }
                }
            }
            onSourceLangChanged = { onSourceLanguageChanged() }
            onScreenModeChanged = {
                checkOnboardingState()
            }
            onClose = { hideSettings() }
            onThemeChanged = { scrollY -> applyThemeInPlace(scrollY) }
            onOverlayModeChanged = { updateRegionButton() }
        }
        supportFragmentManager.beginTransaction()
            .replace(R.id.settingsContainer, sheet, SettingsBottomSheet.TAG)
            .commitAllowingStateLoss()
    }

    /** Apply the new theme by recreating the activity. */
    private fun applyThemeInPlace(settingsScrollY: Int) {
        prefs.settingsScrollY = settingsScrollY
        recreate()
    }

    private fun hideSettings() {
        selectTab(Tab.TRANSLATE)
    }

    /** Creates and shows a SettingsBottomSheet as a dialog (for onboarding). */
    private fun showSettingsSheet(hideDismiss: Boolean) {
        val sheet = SettingsBottomSheet.newInstance(hideDismiss = hideDismiss).apply {
            onDisplayChanged = {
                val wasLive = captureService?.isLive == true
                // configureService() writes display/region; language managers
                // self-heal via ensureLanguageManagersFor.
                configureService()
                PlayTranslateAccessibilityService.instance?.ensureFloatingIcon()
                if (wasLive) {
                    captureService?.stopLive()
                    withAccessibility { doStartLive() }
                }
            }
            onSourceLangChanged = { onSourceLanguageChanged() }
            onScreenModeChanged = {
                checkOnboardingState()
            }
            onThemeChanged = { scrollY ->
                applyTheme()
                prefs.settingsScrollY = scrollY
                reinflateContent()
            }
            onOverlayModeChanged = { updateRegionButton() }
        }
        val ft = supportFragmentManager.beginTransaction()
        ft.add(sheet, SettingsBottomSheet.TAG)
        ft.commitAllowingStateLoss()
    }

    /** True when the user has the accessibility service enabled. */
    private val isCaptureReady: Boolean
        get() = PlayTranslateAccessibilityService.isEnabled

    /** Dims the action button when no capture method is available. */
    private fun updateActionButtonState() {
        val ready = isCaptureReady
        btnTranslate.alpha = if (ready) 1f else 0.45f
        val current = resultVm.result.value as? com.playtranslate.ui.ResultState.Status ?: return
        if (!ready) {
            resultVm.showStatus(getString(R.string.status_accessibility_needed), showHint = false)
        } else if (current.message == getString(R.string.status_accessibility_needed)) {
            resultVm.showStatus(getString(R.string.status_idle), showHint = true)
        } else if (current.message == getString(R.string.status_idle)) {
            resultVm.setStatusHintVisibility(true)
        }
    }

    // ── Service ───────────────────────────────────────────────────────────

    private fun startAndBindService() {
        val intent = Intent(this, CaptureService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    /**
     * Kept as a method rather than inlined in the observer lambda so the
     * lambda captures `this@MainActivity` and is compiled as a per-instance
     * object rather than a singleton. A singleton observer registered with
     * different Activity lifecycle owners (e.g. after the process survives
     * between MainActivity instances) throws IllegalArgumentException from
     * LiveData.observe.
     */
    private fun onDegradedStateChanged(degraded: Boolean) {
        PlayTranslateAccessibilityService.instance?.floatingIcon?.degraded = degraded
    }

    /** Subscribe to the service's outbound event flows. Called once per
     *  service connection (from [serviceConnection.onServiceConnected]).
     *  Collectors run on [lifecycleScope] inside [repeatOnLifecycle], so
     *  they auto-pause when this activity stops and resume on STARTED —
     *  no manual cleanup required, and no risk of another activity
     *  clobbering our subscription (the old `var onResult = { ... }`
     *  pattern).
     *
     *  Two collectors write to the same [resultVm] from different
     *  channels; they coexist because the channels represent different
     *  things (see CaptureSession.kt's "result-surface channels"):
     *   - [_currentCaptureSession] follows whatever one-shot capture
     *     this activity initiated (started via [startOneShotCapture]),
     *     unfolding through the session's own state machine and clearing
     *     itself on terminal so reattach can't replay.
     *   - [svc.panelState] is the sticky background stream (live mode,
     *     hold-to-preview); the VM's [TranslationResultViewModel.displayServiceResult]
     *     identity-dedupes the StateFlow's replay so it can't displace
     *     a local result the VM is now showing.
     *  When a one-shot is in flight there is no live mode running (each
     *  callsite that triggers a one-shot pauses live mode first), so
     *  the two collectors don't fight for the VM in practice. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun wireServiceCallbacks() {
        val svc = captureService ?: return
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    // One-shot capture sessions started by this activity.
                    // flatMapLatest follows whichever session is current;
                    // when a session reaches terminal we null out the flow so
                    // a later STOP→START re-collect doesn't replay the
                    // terminal state on top of newer VM state (e.g. a
                    // drag-sentence local result).
                    _currentCaptureSession.flatMapLatest { it?.state ?: emptyFlow() }
                        .collect { state ->
                            when (state) {
                                is CaptureState.InProgress ->
                                    resultVm.showStatus(state.message)
                                is CaptureState.Done -> {
                                    editTranslationJob?.cancel()
                                    editTranslationJob = null
                                    resultVm.displayResult(state.result, applicationContext)
                                    _currentCaptureSession.value = null
                                }
                                is CaptureState.NoText -> {
                                    resultVm.showStatus(state.message)
                                    _currentCaptureSession.value = null
                                }
                                is CaptureState.Failed -> {
                                    resultVm.showError(state.message)
                                    _currentCaptureSession.value = null
                                }
                                CaptureState.Cancelled -> {
                                    // Silent — cancellation was external (live mode
                                    // start, region change, replacing one-shot).
                                    // Don't touch the VM; just clear the session
                                    // so a STOP→START reattach can't re-deliver
                                    // this dead session's last InProgress.
                                    _currentCaptureSession.value = null
                                }
                            }
                        }
                }
                launch {
                    // Background panel state (live mode, hold-to-preview).
                    // The VM identity-dedupes service-emitted results
                    // separately from local updates (drag-sentence), so
                    // this StateFlow's sticky replay on STOP→START
                    // reattach can't displace whatever the VM is now
                    // showing. PanelState.Idle is the initial / cleared
                    // value and is intentionally a no-op — transient
                    // "Idle" UI signals from config changes flow through
                    // svc.statusUpdates instead.
                    svc.panelState.collect { state ->
                        when (state) {
                            PanelState.Idle -> { /* no-op — see KDoc on _panelState */ }
                            PanelState.Searching ->
                                if (isLiveMode) resultVm.showStatus(searchingStatusText())
                            is PanelState.Result -> {
                                editTranslationJob?.cancel()
                                editTranslationJob = null
                                resultVm.displayServiceResult(state.result, applicationContext)
                            }
                            is PanelState.Error ->
                                resultVm.showError(state.message)
                        }
                    }
                }
                launch {
                    // Transient service signals (currently just "Idle"
                    // from configureSaved / resetConfiguration). Replay = 0
                    // SharedFlow so a STOP→START reattach doesn't re-fire
                    // a stale Idle on top of a now-valid panel result.
                    svc.statusUpdates.collect { msg -> resultVm.showStatus(msg) }
                }
                launch {
                    svc.holdLoading.collect { loading ->
                        PlayTranslateAccessibilityService.instance?.floatingIcon?.showLoading = loading
                    }
                }
            }
        }
        svc.degradedState.observe(this) { degraded ->
            onDegradedStateChanged(degraded)
        }
        svc.liveModeState.observe(this) { isLive -> onLiveModeChanged(isLive) }
        svc.activeRegionLiveData.observe(this) { _ ->
            updateRegionButton()
            if (svc.isOverride) hideRegionPicker()
        }

        ensureConfigured()
    }

    // ── Drag-to-lookup sentence passthrough ──────────────────────────────

    private fun handleDragSentence(intent: Intent) {
        val lineText = intent.getStringExtra(EXTRA_DRAG_LINE_TEXT) ?: return
        val screenshotPath = intent.getStringExtra(EXTRA_DRAG_SCREENSHOT_PATH)

        if (isLiveMode) pauseLiveMode()

        val segments = lineText.map { TextSegment(it.toString()) }
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(java.util.Date())

        resultVm.showTranslatingPlaceholder(lineText, segments, applicationContext)

        val svc = captureService
        if (svc != null) {
            // translateOnce is a translation-only path — doesn't need
            // display/region (i.e. isConfigured) to be true. translate()
            // self-heals language managers on first call.
            lifecycleScope.launch {
                try {
                    val (translated, note) = svc.translateOnce(lineText)
                    val result = TranslationResult(
                        originalText = lineText,
                        segments = segments,
                        translatedText = translated,
                        timestamp = timestamp,
                        screenshotPath = screenshotPath,
                        note = note
                    )
                    resultVm.displayResult(result, applicationContext)
                } catch (e: Exception) {
                    resultVm.updateTranslation("")
                }
            }
        } else {
            resultVm.updateTranslation("")
        }
    }

    /**
     * Drag-popup side-button → open the word detail sheet when the main
     * app is the active surface (dual-screen + foregrounded). Reuses the
     * existing [onWordTapped] path so behavior matches tapping a word
     * inside the translation result view.
     */
    private fun handleDragWord(intent: Intent) {
        val word = intent.getStringExtra(EXTRA_DRAG_WORD) ?: return
        val reading = intent.getStringExtra(EXTRA_DRAG_READING)
        val screenshotPath = intent.getStringExtra(EXTRA_DRAG_SCREENSHOT_PATH)
        val sentenceOriginal = intent.getStringExtra(EXTRA_DRAG_SENTENCE_ORIGINAL)
        val sentenceTranslation = intent.getStringExtra(EXTRA_DRAG_SENTENCE_TRANSLATION)
        val wordResults = if (sentenceOriginal != null
            && com.playtranslate.ui.LastSentenceCache.original == sentenceOriginal
        ) {
            com.playtranslate.ui.LastSentenceCache.wordResults.orEmpty()
        } else emptyMap()
        onWordTapped(
            word = word,
            reading = reading,
            screenshotPath = screenshotPath,
            sentenceOriginal = sentenceOriginal,
            sentenceTranslation = sentenceTranslation,
            wordResults = wordResults,
        )
    }

    // ── Region capture from floating icon ─────────────────────────────────

    private fun handleRegionCapture() {
        if (isLiveMode) pauseLiveMode()
        selectTab(Tab.TRANSLATE)
        startOneShotCapture()
    }

    /** Initiate a one-shot capture and route its session to the
     *  collector in [wireServiceCallbacks]. No-op if the service isn't
     *  bound yet. */
    private fun startOneShotCapture() {
        _currentCaptureSession.value = captureService?.captureOnce()
    }

    // ── Accessibility service flow ─────────────────────────────────────────

    private fun withAccessibility(action: () -> Unit) {
        if (PlayTranslateAccessibilityService.isEnabled) {
            ensureConfigured()
            action()
            return
        }
        showAccessibilityDialog()
    }

    private fun ensureConfigured() {
        val svc = captureService ?: return
        if (!svc.isConfigured) {
            prefs.captureDisplayId = findGameDisplayId()
            configureService()
        }
    }

    /** Applies display + region to the capture service. Language managers
     *  self-heal on the next capture via [CaptureService.ensureLanguageManagersFor]
     *  so we no longer pass sourceLang / targetLang here. */
    private fun configureService() {
        val svc = captureService ?: return
        val entry = prefs.getSelectedRegion()
        val ids = prefs.captureDisplayIds
        svc.configureSaved(displayIds = ids, region = entry)
    }

    private fun onSourceLanguageChanged() {
        val wasLive = captureService?.isLive == true
        // Reset overlay mode if new language has no hint text
        if (SourceLanguageProfiles[prefs.sourceLangId].hintTextKind == HintTextKind.NONE
            && prefs.overlayMode == OverlayMode.FURIGANA) {
            prefs.overlayMode = OverlayMode.TRANSLATION
        }
        // Language managers self-heal in translate(), but configureSaved()
        // also clears any temporary override region and refreshes the saved
        // region — both of which should reset on a deliberate language
        // change. The cache invalidation that used to live here is now a
        // side effect of configureSaved → ensureLanguageManagersFor.
        configureService()
        updateRegionButton()
        PlayTranslateAccessibilityService.instance?.ensureFloatingIcon()
        if (LanguagePackStore.isInstalled(applicationContext, prefs.sourceLangId)) {
            lifecycleScope.launch(Dispatchers.IO) {
                preloadEngineAndRecover(prefs.sourceLangId)
            }
        }
        if (wasLive) {
            captureService?.stopLive()
            withAccessibility { doStartLive() }
        }
    }

    private fun showAccessibilityDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.accessibility_dialog_title))
            .setMessage(getString(R.string.accessibility_dialog_message))
            .setPositiveButton(getString(R.string.accessibility_dialog_open)) { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun maybePromptForCrashShare() {
        val crashFiles = LogExporter.getCrashFiles(this)
        if (crashFiles.isEmpty()) return
        OverlayAlert.Builder(this)
            .setTitle("PlayTranslate crashed previously")
            .setMessage("Send the crash report to the developer? It includes a stack trace, recent app logs, and any text PlayTranslate has recently OCR'd or looked up. No account info.")
            .addButton("Send", themeColor(R.attr.ptAccent)) {
                lifecycleScope.launch {
                    val files = withContext(Dispatchers.IO) {
                        runCatching {
                            crashFiles + LogExporter.exportLogcat(this@MainActivity)
                        }.getOrElse { crashFiles }
                    }
                    val subject = "PlayTranslate crash – v${BuildConfig.VERSION_NAME}"
                    val body = buildString {
                        appendLine("Steps to reproduce:")
                        appendLine()
                        appendLine("(Optional — describe what you were doing when this happened.)")
                        appendLine()
                        appendLine("Device info is included in the attached file.")
                    }
                    LogExporter.emailFiles(this@MainActivity, files, subject, body)
                    LogExporter.deleteCrashFiles(this@MainActivity)
                }
            }
            .addButton(
                "Discard",
                themeColor(R.attr.ptDivider),
                themeColor(R.attr.ptDanger)
            ) {
                LogExporter.deleteCrashFiles(this)
            }
            .addButton(
                "Later",
                themeColor(R.attr.ptDivider),
                themeColor(R.attr.ptText)
            ) {
                // No action — files remain, prompt re-fires next launch.
            }
            .showInActivity(this)
    }

    private fun maybeCheckForUpdates() {
        if (onboardingContainer.visibility == View.VISIBLE) return
        lifecycleScope.launch {
            val release = UpdateChecker.maybeCheck(this@MainActivity) ?: return@launch
            if (!isInForeground || isFinishing || isDestroyed) return@launch
            if (onboardingContainer.visibility == View.VISIBLE) return@launch
            showUpdatePopup(release)
        }
    }

    private fun showUpdatePopup(release: UpdateChecker.Release) {
        OverlayAlert.Builder(this)
            .setTitle("Update available")
            .setMessage("PlayTranslate ${release.tag} is available on GitHub.")
            .addButton("View release", themeColor(R.attr.ptAccent)) {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.url)))
                } catch (_: Exception) {
                    Toast.makeText(this, "No browser available", Toast.LENGTH_SHORT).show()
                }
            }
            .addButton(
                "Skip this version",
                themeColor(R.attr.ptDivider),
                themeColor(R.attr.ptDanger)
            ) {
                prefs.updateCheckSkippedTag = release.tag
            }
            .addButton(
                "Ask again later",
                themeColor(R.attr.ptDivider),
                themeColor(R.attr.ptText)
            ) {
                // 24h debounce timestamp was already committed inside
                // UpdateChecker.maybeCheck — no extra bookkeeping needed.
            }
            .showInActivity(this)
    }

    private fun showRestrictedSettingsDialog() {
        OverlayAlert.Builder(this)
            .setTitle(getString(R.string.restricted_settings_title))
            .setMessage(getString(R.string.restricted_settings_message))
            .addButton(
                getString(R.string.btn_open_app_settings),
                themeColor(R.attr.ptAccent)
            ) {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
                startActivity(intent)
            }
            .addCancelButton()
            .showInActivity(this)
    }

    private fun isSingleScreen(): Boolean = Prefs.isSingleScreen(this)

    private fun checkOnboardingState() {
        val prefs = Prefs(this)
        val sourceInstalled = LanguagePackStore.isInstalled(this, prefs.sourceLangId)
        val languageConfigured = sourceInstalled && prefs.hasTargetLangBeenSet

        val existingSheet = supportFragmentManager.findFragmentByTag(SettingsBottomSheet.TAG) as? SettingsBottomSheet

        // Welcome + language setup comes first: tap a language pair before
        // being asked to grant permissions. Upgrade users who already have
        // both satisfied skip this step entirely.
        if (!languageConfigured) {
            existingSheet?.dismissAllowingStateLoss()
            showOnboardingPage(pageWelcome)
            refreshWelcomeRowsAndButton()
            return
        }

        val notifGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        val a11yEnabled = PlayTranslateAccessibilityService.isEnabled
        // Use the viewport predicate (not hasMultipleDisplays) so split-screen
        // users don't fall into the forced non-dismissible settings sheet
        // below. The sheet exists because in pure single-screen fullscreen
        // mode the user has no other UI surface to manage the app from; that
        // rationale doesn't apply in split-screen where the app half is
        // visible alongside the game.
        val singleScreen = Prefs.isSingleScreen(this)

        if (!notifGranted) {
            existingSheet?.dismissAllowingStateLoss()
            showOnboardingPage(pageNotif)
            return
        }

        if (singleScreen) {
            if (!a11yEnabled) {
                existingSheet?.dismissAllowingStateLoss()
                showOnboardingPage(pageA11ySingle)
                return
            }
            onboardingContainer.visibility = View.GONE
                val isAlreadySingleScreenSheet = existingSheet != null &&
                existingSheet.arguments?.getBoolean("hide_dismiss", false) == true
            if (!isAlreadySingleScreenSheet) {
                existingSheet?.dismissAllowingStateLoss()
                showSettingsSheet(hideDismiss = true)
            }
            return
        }

        if (existingSheet != null && existingSheet.arguments?.getBoolean("hide_dismiss", false) == true) {
            existingSheet.dismissAllowingStateLoss()
        }

        if (a11yEnabled) {
            onboardingContainer.visibility = View.GONE
            return
        }
        showOnboardingPage(pageA11y)
    }

    /** Refreshes Game Language / Your Language row values and the Continue
     *  button's label to match current source-installed / target-set state.
     *  Called whenever [pageWelcome] is (re-)displayed. */
    private fun refreshWelcomeRowsAndButton() {
        val p = Prefs(this)
        val srcInstalled = LanguagePackStore.isInstalled(this, p.sourceLangId)
        val tgtSet = p.hasTargetLangBeenSet
        // Effective target — explicit if set, else the computed default.
        // Used both for the Your Language row display and for localizing the
        // Game Language name.
        val sourceCode = com.playtranslate.language.SourceLanguageProfiles[p.sourceLangId].translationCode
        val effectiveTarget = if (tgtSet) p.targetLang
            else com.playtranslate.ui.WelcomeDefaults.computeDefaultTarget(sourceCode)
        val tgtLocale = java.util.Locale(effectiveTarget)

        rowWelcomeGameLang.findViewById<TextView>(R.id.tvRowTitle).text =
            getString(R.string.lang_translate_from)
        val gameVal = rowWelcomeGameLang.findViewById<TextView>(R.id.tvRowValue)
        if (srcInstalled) {
            gameVal.text = p.sourceLangId.displayName(tgtLocale)
            gameVal.setTextColor(themeColor(R.attr.ptTextMuted))
        } else {
            gameVal.text = getString(R.string.onboarding_welcome_row_placeholder)
            gameVal.setTextColor(themeColor(R.attr.ptTextHint))
        }

        rowWelcomeYourLang.findViewById<TextView>(R.id.tvRowTitle).text =
            getString(R.string.lang_translate_to)
        val yourVal = rowWelcomeYourLang.findViewById<TextView>(R.id.tvRowValue)
        // Value is the effective target — explicit selection or computed
        // default. Styling matches a committed selection; if the user wants
        // something else they tap the row. Tapping Continue without having
        // picked explicitly runs the install flow for this default.
        yourVal.text = tgtLocale.getDisplayLanguage(tgtLocale)
            .replaceFirstChar { it.uppercase(tgtLocale) }
        yourVal.setTextColor(themeColor(R.attr.ptTextMuted))

        btnWelcomeContinue.text = getString(
            if (srcInstalled) R.string.onboarding_welcome_continue
            else R.string.onboarding_welcome_select_source
        )
    }

    private fun launchLanguagePicker(mode: String) {
        startActivity(
            Intent(this, com.playtranslate.ui.LanguageSetupActivity::class.java)
                .putExtra(com.playtranslate.ui.LanguageSetupActivity.EXTRA_MODE, mode)
                .putExtra(com.playtranslate.ui.LanguageSetupActivity.EXTRA_ONBOARDING, true)
        )
    }

    private fun showOnboardingPage(page: View) {
        onboardingContainer.visibility = View.VISIBLE
        pageWelcome.visibility    = if (page == pageWelcome)    View.VISIBLE else View.GONE
        pageNotif.visibility      = if (page == pageNotif)      View.VISIBLE else View.GONE
        pageA11y.visibility       = if (page == pageA11y)       View.VISIBLE else View.GONE
        pageA11ySingle.visibility = if (page == pageA11ySingle) View.VISIBLE else View.GONE
    }

    private fun setupOnboarding() {
        // Welcome page — row taps open the appropriate picker; Continue's
        // label + action depend on whether the source pack is installed yet.
        rowWelcomeGameLang.setOnClickListener {
            launchLanguagePicker(com.playtranslate.ui.LanguageSetupActivity.MODE_SOURCE)
        }
        rowWelcomeYourLang.setOnClickListener {
            launchLanguagePicker(com.playtranslate.ui.LanguageSetupActivity.MODE_TARGET)
        }
        btnWelcomeContinue.setOnClickListener {
            val p = Prefs(this)
            when {
                !LanguagePackStore.isInstalled(this, p.sourceLangId) -> {
                    launchLanguagePicker(com.playtranslate.ui.LanguageSetupActivity.MODE_SOURCE)
                }
                !p.hasTargetLangBeenSet -> {
                    // User is accepting the pre-populated default — run the
                    // same download + ensure-model-ready flow the target
                    // picker would have, commit prefs on success, advance.
                    // Using the shared welcomeTargetInstaller so its
                    // single-flight guard engages across rapid double-taps.
                    val sourceCode = com.playtranslate.language.SourceLanguageProfiles[
                        p.sourceLangId
                    ].translationCode
                    val defaultTarget = com.playtranslate.ui.WelcomeDefaults
                        .computeDefaultTarget(sourceCode)
                    welcomeTargetInstaller.installAndLoad(
                        sourceLangCode = sourceCode,
                        targetCode = defaultTarget,
                        onSuccess = {
                            Prefs(this).targetLang = defaultTarget
                            checkOnboardingState()
                        },
                    )
                }
                else -> checkOnboardingState()
            }
        }

        pageNotif.findViewById<View>(R.id.btnGrantNotif).setOnClickListener {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        pageA11y.findViewById<View>(R.id.btnOpenA11y).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        pageA11ySingle.findViewById<View>(R.id.btnOpenA11ySingle).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        val cantEnableClick = View.OnClickListener { showRestrictedSettingsDialog() }
        pageA11y.findViewById<View>(R.id.btnCantEnableA11y).setOnClickListener(cantEnableClick)
        pageA11ySingle.findViewById<View>(R.id.btnCantEnableA11ySingle).setOnClickListener(cantEnableClick)
        // Highlight "PlayTranslate" in the hint text with the theme accent color
        val accentColor = themeColor(R.attr.ptTextTranslation)
        colorizeAppName(pageA11y.findViewById(R.id.tvA11yHintDual), accentColor)
        colorizeAppName(pageA11ySingle.findViewById(R.id.tvA11yHintSingle), accentColor)
    }

    private fun colorizeAppName(tv: TextView, color: Int) {
        val text = tv.text.toString()
        val appName = getString(R.string.app_name)
        val start = text.indexOf(appName)
        if (start < 0) return
        val spannable = android.text.SpannableString(text)
        spannable.setSpan(
            android.text.style.ForegroundColorSpan(color),
            start, start + appName.length,
            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        spannable.setSpan(
            StyleSpan(Typeface.BOLD),
            start, start + appName.length,
            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        tv.text = spannable
    }

    // ── Display detection ─────────────────────────────────────────────────

    private fun findGameDisplayId(): Int {
        val myDisplayId = display?.displayId ?: Display.DEFAULT_DISPLAY

        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        return displayManager.displays
            .firstOrNull { it.displayId != myDisplayId }
            ?.displayId
            ?: prefs.captureDisplayId
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun selectedSourceLang() =
        SourceLanguageProfiles[prefs.sourceLangId].translationCode

    /**
     * Sets tvLiveHint text with an inline play icon ImageSpan.
     * Called once on resume so the span is ready before first display.
     */
    private fun initLiveHintText() {
        val frag = resultFragment ?: return
        val icon = ContextCompat.getDrawable(this, R.drawable.ic_play)?.mutate() ?: return
        icon.setTint(themeColor(R.attr.ptTextHint))
        val textSize = 24f * resources.displayMetrics.scaledDensity
        val size = (textSize * 1.1f).toInt()
        icon.setBounds(0, 0, size, size)
        val span = android.text.style.ImageSpan(icon, android.text.style.ImageSpan.ALIGN_BASELINE)
        val sb = android.text.SpannableString("Press \u0000 button below to start live mode")
        sb.setSpan(span, 6, 7, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        frag.setLiveHintText(sb)
    }

    /** Returns the "Searching for X in the Y area" message for live mode. */
    private fun searchingStatusText(): String {
        val lang = langDisplayName(selectedSourceLang())
        val entry = prefs.getSelectedRegion()
        val serviceLabel = captureService?.activeRegion?.label?.takeIf { it.isNotEmpty() }
        val label = serviceLabel ?: entry.label
        return "Searching for $lang in the \"$label\" area"
    }

    private fun langDisplayName(langCode: String): String =
        Locale(langCode).getDisplayLanguage(Locale.getDefault())
            .replaceFirstChar { it.uppercase(Locale.getDefault()) }

    private fun showEditOverlay() {
        val displayed = resultFragment?.getDisplayedOriginalText()?.takeIf { it.isNotBlank() }
        val currentText = displayed
            ?: (resultVm.result.value as? com.playtranslate.ui.ResultState.Ready)
                ?.result?.originalText
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

        resultVm.updateOriginalText(newText, applicationContext)

        editTranslationJob?.cancel()
        editTranslationJob = lifecycleScope.launch {
            try {
                // Route through the service so edit re-translations pick up the
                // current language pair via translateOnce's self-heal, inherit
                // the full DeepL→Lingva→ML-Kit waterfall, and don't own any
                // parallel translator state that could go stale on pref change.
                val svc = captureService
                if (svc == null) {
                    resultVm.updateTranslation("—")
                    return@launch
                }
                val (translated, _) = svc.translateOnce(newText)
                resultVm.updateTranslation(translated)
            } catch (_: Exception) {
                resultVm.updateTranslation("—")
            }
        }
    }

    private fun setupDetectionLog() {
        val tv = findViewById<android.widget.TextView>(R.id.tvDetectionLog)
        val enabled = BuildConfig.DEBUG && prefs.debugShowDetectionLog
        DetectionLog.enabled = enabled
        tv.visibility = if (enabled) View.VISIBLE else View.GONE
        DetectionLog.onUpdate = if (enabled) { text -> tv.text = text } else null
    }

    private fun setupEditOverlay() {
        // Scroll-pause for live mode flows through the fragment's host
        // interface ([onUserScrolled]) — no external scroll listener
        // needed here.

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
            if (wasKeyboardVisible && !keyboardVisible && editOverlay.visibility == View.VISIBLE) {
                commitEdit()
            }
            wasKeyboardVisible = keyboardVisible
        }
    }

    // ── Auto mode quick-dropdown ────────────────────────────────────────────

    private fun showAutoModeDropdown(anchor: View) {
        dismissDropdown()
        val currentMode = prefs.overlayMode
        val modes = listOf(OverlayMode.TRANSLATION, OverlayMode.FURIGANA)

        // Current mode at bottom, others above
        val ordered = modes.filter { it != currentMode } + currentMode

        val dp = resources.displayMetrics.density
        dropdownItemHeightPx = 48 * dp

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeColor(R.attr.ptSurface))
            elevation = 8 * dp
        }
        val rows = mutableListOf<View>()
        ordered.forEach { mode ->
            val row = buildDropdownRow(mode.displayName, mode == currentMode)
            container.addView(row)
            rows.add(row)
        }
        dropdownRows = rows
        dropdownHighlightedRow = ordered.lastIndex
        dropdownHighlightListener = null
        dropdownCommitAction = {
            val selectedMode = ordered[dropdownHighlightedRow]
            if (prefs.overlayMode != selectedMode) {
                prefs.overlayMode = selectedMode
            }
            if (isLiveMode) {
                captureService?.stopLive()
                withAccessibility { doStartLive() }
            } else {
                withAccessibility { startLiveMode() }
            }
        }

        val anchorLoc = intArrayOf(0, 0)
        anchor.getLocationOnScreen(anchorLoc)
        val popupHeight = (modes.size * dropdownItemHeightPx).toInt()
        val popupTop = maxOf(0, anchorLoc[1] - popupHeight)
        dropdownTopY = popupTop.toFloat()

        val screenWidth = resources.displayMetrics.widthPixels
        val popupMarginH = (12 * dp).toInt()
        val popupWidth = screenWidth - 2 * popupMarginH
        val popup = PopupWindow(container, popupWidth, LinearLayout.LayoutParams.WRAP_CONTENT, false)
        popup.isTouchable = false
        popup.isOutsideTouchable = false
        popup.showAtLocation(anchor, Gravity.NO_GRAVITY, popupMarginH, popupTop)
        dropdownPopup = popup
    }

    // ── Region quick-dropdown ──────────────────────────────────────────────

    private fun showRegionDropdown(anchor: View) {
        val regions = prefs.getRegionList()
        if (regions.isEmpty()) return

        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val gameDisplay = displayManager.getDisplay(prefs.captureDisplayId)

        val currentId = prefs.selectedRegionId
        val currentIndex = regions.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
        val order = mutableListOf<Int>()
        order.add(-1)
        for (i in regions.indices) { if (i != currentIndex) order.add(i) }
        order.add(currentIndex)
        dropdownRegionOrder = order
        dropdownHighlightedRow = order.lastIndex
        dropdownGameDisplay = gameDisplay
        dropdownRegions = regions

        val dp = resources.displayMetrics.density
        dropdownItemHeightPx = 48 * dp

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeColor(R.attr.ptSurface))
            elevation = 8 * dp
        }
        val rows = mutableListOf<View>()
        order.forEachIndexed { rowIdx, regionIdx ->
            val isHighlighted = rowIdx == order.lastIndex
            val label = if (regionIdx == -1) getString(R.string.label_add_custom_region) else regions[regionIdx].label
            val row = buildDropdownRow(label, isHighlighted, regionIdx == -1)
            container.addView(row)
            rows.add(row)
        }
        dropdownRows = rows
        dropdownHighlightListener = { rowIdx ->
            val regionIdx = dropdownRegionOrder[rowIdx]
            if (regionIdx >= 0) {
                dropdownGameDisplay?.let { d ->
                    PlayTranslateAccessibilityService.instance?.showRegionOverlay(d, dropdownRegions[regionIdx])
                }
            }
        }
        dropdownCommitAction = { commitRegionDropdownSelection() }

        val anchorLoc = intArrayOf(0, 0)
        anchor.getLocationOnScreen(anchorLoc)
        val popupHeight = (order.size * dropdownItemHeightPx).toInt()
        val popupTop = maxOf(0, anchorLoc[1] - popupHeight)
        dropdownTopY = popupTop.toFloat()

        val screenWidth = resources.displayMetrics.widthPixels
        val popupMarginH = (12 * dp).toInt()
        val popupWidth = screenWidth - 2 * popupMarginH
        val popup = PopupWindow(container, popupWidth, LinearLayout.LayoutParams.WRAP_CONTENT, false)
        popup.isTouchable = false
        popup.isOutsideTouchable = false
        popup.showAtLocation(anchor, Gravity.NO_GRAVITY, popupMarginH, popupTop)
        dropdownPopup = popup

        if (gameDisplay != null) {
            val entry = regions[currentIndex]
            PlayTranslateAccessibilityService.instance?.showRegionOverlay(gameDisplay, entry)
        }
    }

    private var dropdownHighlightListener: ((Int) -> Unit)? = null

    private fun updateDropdownHighlight(rawY: Float) {
        if (dropdownRows.isEmpty()) return
        val relativeY = rawY - dropdownTopY
        val rowIdx = (relativeY / dropdownItemHeightPx).toInt()
            .coerceIn(0, dropdownRows.size - 1)
        if (rowIdx == dropdownHighlightedRow) return

        updateRowHighlight(dropdownRows[dropdownHighlightedRow], false)
        updateRowHighlight(dropdownRows[rowIdx], true)
        dropdownHighlightedRow = rowIdx
        dropdownHighlightListener?.invoke(rowIdx)
    }

    private fun commitRegionDropdownSelection() {
        val selectedRegionIdx = dropdownRegionOrder[dropdownHighlightedRow]
        dismissDropdown()
        inDragMode = false
        if (selectedRegionIdx == -1) {
            if (!PlayTranslateAccessibilityService.isEnabled) {
                AlertDialog.Builder(this)
                    .setTitle(R.string.custom_region_a11y_required_title)
                    .setMessage(R.string.custom_region_a11y_required_message)
                    .setPositiveButton(R.string.btn_open_a11y_settings) { _, _ ->
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                return
            }
            openAddCustomRegionFromDropdown()
            return
        }
        val changedSavedRegion = dropdownHighlightedRow != dropdownRegionOrder.lastIndex
        val hadOverride = captureService?.isOverride == true
        if (changedSavedRegion) {
            prefs.selectedRegionId = dropdownRegions[selectedRegionIdx].id
            configureService()
            if (!isLiveMode) {
                selectTab(Tab.TRANSLATE)
                withAccessibility { startOneShotCapture() }
            }
        } else if (hadOverride) {
            configureService()
        }
    }

    private fun openAddCustomRegionFromDropdown() {
        PlayTranslateAccessibilityService.instance?.hideRegionOverlay()
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val gameDisplay = displayManager.getDisplay(prefs.captureDisplayId)
        val current = CaptureService.instance?.activeRegion
        AddCustomRegionSheet().also { sheet ->
            sheet.gameDisplay = gameDisplay
            if (current != null && !current.isFullScreen) {
                if (captureService?.isOverride == true) {
                    sheet.initRegion(current)
                } else {
                    val regions = prefs.getRegionList()
                    val editIdx = regions.indexOfFirst { it.id == current.id }.coerceAtLeast(0)
                    sheet.initRegion(current, editIdx)
                    sheet.onRegionEdited = { edited ->
                        prefs.selectedRegionId = edited.id
                        configureService()
                    }
                }
            }
            sheet.onRegionAdded = { newEntry ->
                prefs.selectedRegionId = newEntry.id
                configureService()
                refreshRegionPicker()
                withAccessibility { startOneShotCapture() }
            }
            sheet.onDismissed = { refreshRegionPicker() }
            sheet.onTranslateOnce = { region ->
                captureService?.configureOverride(region)
                withAccessibility { startOneShotCapture() }
            }
        }.show(supportFragmentManager, AddCustomRegionSheet.TAG)
    }

    private fun dismissDropdown() {
        dropdownPopup?.dismiss()
        dropdownPopup = null
        dropdownRows = emptyList()
        dropdownCommitAction = null
        dropdownHighlightListener = null
        PlayTranslateAccessibilityService.instance?.hideRegionOverlay()
    }

    private fun buildDropdownRow(label: String, highlighted: Boolean, isAddNew: Boolean = false): LinearLayout {
        val dp = resources.displayMetrics.density
        val padH = (12 * dp).toInt()
        val padV = (12 * dp).toInt()
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(padH, padV, padH, padV)
            setBackgroundColor(themeColor(
                if (highlighted) R.attr.ptCard else R.attr.ptSurface))

            if (isAddNew) {
                val tv = TextView(this@MainActivity).apply {
                    text = label
                    textSize = 14f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(themeColor(R.attr.ptAccent))
                }
                addView(tv)
            } else {
                val rb = RadioButton(this@MainActivity).apply {
                    isChecked = highlighted
                    isClickable = false
                    isFocusable = false
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).also { it.marginEnd = (8 * dp).toInt() }
                }
                val tv = TextView(this@MainActivity).apply {
                    text = label
                    textSize = 14f
                    setTextColor(themeColor(R.attr.ptText))
                }
                addView(rb)
                addView(tv)
            }
        }
    }

    private fun updateRowHighlight(row: View, highlighted: Boolean) {
        row.setBackgroundColor(themeColor(
            if (highlighted) R.attr.ptCard else R.attr.ptSurface))
        ((row as? LinearLayout)?.getChildAt(0) as? RadioButton)?.isChecked = highlighted
    }

    private fun checkTargetPackMigration() {
        val target = prefs.targetLang
        if (target == "en") return
        if (LanguagePackStore.isTargetInstalled(this, target)) return
        if (prefs.targetPackMigrationDismissed) return
        val catalogKey = "target-$target"
        if (LanguagePackCatalogLoader.entryForKey(this, catalogKey) == null) return

        val targetLocale = java.util.Locale(target)
        val targetName = targetLocale.getDisplayLanguage(targetLocale)
            .replaceFirstChar { it.uppercase(targetLocale) }
        AlertDialog.Builder(this)
            .setTitle("$targetName definitions available")
            .setMessage(
                "A $targetName definition pack is available. " +
                "Download it for word definitions in $targetName instead of English?"
            )
            .setPositiveButton("Download") { _, _ ->
                com.playtranslate.ui.LanguageSetupActivity.launch(this, com.playtranslate.ui.LanguageSetupActivity.MODE_TARGET)
            }
            .setNegativeButton("Not now") { _, _ ->
                prefs.targetPackMigrationDismissed = true
            }
            .setCancelable(false)
            .show()
    }

    companion object {
        const val ACTION_DRAG_SENTENCE = "com.playtranslate.ACTION_DRAG_SENTENCE"
        const val EXTRA_DRAG_LINE_TEXT = "extra_drag_line_text"
        const val EXTRA_DRAG_SCREENSHOT_PATH = "extra_drag_screenshot_path"
        const val ACTION_DRAG_WORD = "com.playtranslate.ACTION_DRAG_WORD"
        const val EXTRA_DRAG_WORD = "extra_drag_word"
        const val EXTRA_DRAG_READING = "extra_drag_reading"
        const val EXTRA_DRAG_SENTENCE_ORIGINAL = "extra_drag_sentence_original"
        const val EXTRA_DRAG_SENTENCE_TRANSLATION = "extra_drag_sentence_translation"
        const val ACTION_REGION_CAPTURE = "com.playtranslate.ACTION_REGION_CAPTURE"
        const val EXTRA_TOP_FRAC = "extra_top_frac"
        const val EXTRA_BOTTOM_FRAC = "extra_bottom_frac"
        const val EXTRA_LEFT_FRAC = "extra_left_frac"
        const val EXTRA_RIGHT_FRAC = "extra_right_frac"
        const val DRAGGED_REGION_LABEL = "Drawn Region"
        const val ACTION_START_LIVE = "com.playtranslate.ACTION_START_LIVE"
        const val ACTION_STOP_LIVE = "com.playtranslate.ACTION_STOP_LIVE"
        const val ACTION_ADD_CUSTOM_REGION = "com.playtranslate.ACTION_ADD_CUSTOM_REGION"
        const val ACTION_REFRESH_REGION_LABEL = "com.playtranslate.ACTION_REFRESH_REGION_LABEL"
        const val ACTION_OPEN_SETTINGS = "com.playtranslate.ACTION_OPEN_SETTINGS"

        @Volatile
        var isInForeground = false
            set(value) {
                field = value
                CaptureService.instance?.updateForegroundState()
            }

        /**
         * True when MainActivity is currently in Android multi-window /
         * split-screen mode. Combined with [isInForeground] to widen the
         * definition of "the user can see both app and game simultaneously"
         * inside [Prefs.isSingleScreen]. Updated from [onCreate] (for the
         * launch-into-split-screen case) and [onMultiWindowModeChanged].
         */
        @Volatile
        var isInMultiWindowMode = false

    }
}
