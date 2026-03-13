package com.gamelens

import android.Manifest
import android.media.projection.MediaProjectionManager
import com.gamelens.themeColor
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.StyleSpan
import android.widget.Button
import android.widget.Toast
import android.widget.ImageButton
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.RadioButton
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.gamelens.BuildConfig
import com.gamelens.dictionary.Deinflector
import com.gamelens.dictionary.DictionaryManager
import com.gamelens.model.TextSegment
import com.gamelens.model.TranslationResult
import com.gamelens.ui.ClickableTextView
import com.gamelens.AnkiManager
import com.gamelens.TranslationManager
import com.gamelens.ui.AddCustomRegionSheet
import com.gamelens.ui.AnkiReviewBottomSheet
import com.gamelens.ui.RegionPickerSheet
import com.gamelens.ui.SettingsBottomSheet
import com.gamelens.ui.LastSentenceCache
import com.gamelens.ui.TranslationResultFragment
import com.gamelens.ui.WordDetailBottomSheet
import com.google.mlkit.nl.translate.TranslateLanguage
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import java.util.Locale

class MainActivity : AppCompatActivity(), TranslationResultFragment.TranslationResultHost {

    // ── Views ─────────────────────────────────────────────────────────────

    private lateinit var btnTranslate: MaterialButton
    private lateinit var btnCapturing: MaterialButton
    private lateinit var btnChangeRegion: Button
    private lateinit var btnSettings: ImageButton
    private lateinit var btnClear: ImageButton
    private lateinit var btnLivePlay: ImageButton
    private lateinit var btnLivePause: ImageButton
    private lateinit var liveProgressRing: CircularProgressIndicator
    private lateinit var liveButtonContainer: android.view.View
    private lateinit var onboardingContainer: View
    private lateinit var pageNotif: View
    private lateinit var pageA11y: View
    private lateinit var pageA11ySingle: View
    private lateinit var editOverlay: android.widget.LinearLayout
    private lateinit var etEditOriginal: android.widget.EditText

    private var editTranslationJob: Job? = null
    private var editTranslationManager: TranslationManager? = null
    private var wasKeyboardVisible = false

    // ── Fragment ───────────────────────────────────────────────────────────

    private val resultFragment: TranslationResultFragment?
        get() = supportFragmentManager.findFragmentById(R.id.resultsContainer) as? TranslationResultFragment

    // ── Region quick-dropdown state ────────────────────────────────────────
    private var inDragMode = false
    private var dropdownPopup: PopupWindow? = null
    private var dropdownHighlightedRow = 0
    private var dropdownRegionOrder = listOf<Int>()
    private var dropdownRows = listOf<View>()
    private var dropdownItemHeightPx = 0f
    private var dropdownTopY = 0f
    private var dropdownGameDisplay: Display? = null
    private var dropdownRegions = listOf<RegionEntry>()

    // ── Display listener (detects screen connect/disconnect) ─────────────

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) { runOnUiThread {
            checkOnboardingState()
            PlayTranslateAccessibilityService.instance?.ensureFloatingIcon()
        } }
        override fun onDisplayRemoved(displayId: Int) { runOnUiThread {
            checkOnboardingState()
            PlayTranslateAccessibilityService.instance?.ensureFloatingIcon()
        } }
        override fun onDisplayChanged(displayId: Int) {}
    }

    // ── State ─────────────────────────────────────────────────────────────

    private val prefs by lazy { Prefs(this) }

    private var isLiveMode = false
    /** Non-null while a temporary "use once" custom region is active. Cleared when saved config is restored. */
    private var overrideRegionLabel: String? = null
    /** Fractions for the current override region (set alongside overrideRegionLabel). */
    private var overrideRegion: FloatArray? = null  // [top, bottom, left, right]
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

    private var pendingAfterMpGrant: (() -> Unit)? = null

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            captureService?.setMediaProjection(result.resultCode, result.data!!)
            prefs.captureMethod = "media_projection"
            pendingAfterMpGrant?.invoke()
            pendingAfterMpGrant = null
        } else {
            pendingAfterMpGrant = null
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
        screenshotPath: String?,
        sentenceOriginal: String?,
        sentenceTranslation: String?,
        wordResults: Map<String, Triple<String, String, Int>>
    ) {
        pauseLiveMode()
        WordDetailBottomSheet.newInstance(
            word,
            screenshotPath,
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
        applyTheme()
        super.onCreate(savedInstanceState)
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

        bindViews()
        setupRegionButton()
        setupButtons()
        setupOnboarding()
        setupEditOverlay()
        startAndBindService()
        (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
            .registerDisplayListener(displayListener, null)
        lifecycleScope.launch(Dispatchers.IO) {
            DictionaryManager.get(applicationContext).preload()
            Deinflector.preload()
        }

        // Wire up the fragment's original-tapped listener for edit overlay
        resultFragment?.setOnOriginalTappedListener { offset -> showEditOverlay(offset) }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        when (intent?.action) {
            ACTION_DRAG_SENTENCE -> handleDragSentence(intent)
            ACTION_REGION_CAPTURE -> handleRegionCapture(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        isInForeground = true
        // Re-wire service callbacks in case TranslationResultActivity overwrote them
        if (serviceConnected) wireServiceCallbacks()
        // Re-wire fragment listener after config change
        resultFragment?.setOnOriginalTappedListener { offset -> showEditOverlay(offset) }
        PlayTranslateAccessibilityService.instance?.ensureFloatingIcon()
        checkOnboardingState()
        if (onboardingContainer.visibility == View.VISIBLE) return
        if (isSingleScreen()) return
        initLiveHintText()
        updateActionButtonState()
        applyLiveModeVisibilitySetting()
    }

    override fun onStop() {
        super.onStop()
        isInForeground = false
    }

    override fun onDestroy() {
        (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
            .unregisterDisplayListener(displayListener)
        if (isLiveMode) captureService?.stopLive()
        if (serviceConnected) unbindService(serviceConnection)
        editTranslationManager?.close()
        super.onDestroy()
    }

    // ── Setup ─────────────────────────────────────────────────────────────

    private fun bindViews() {
        btnTranslate         = findViewById(R.id.btnTranslate)
        btnCapturing         = findViewById(R.id.btnCapturing)
        btnChangeRegion      = findViewById(R.id.btnChangeRegion)
        btnSettings          = findViewById(R.id.btnSettings)
        btnClear             = findViewById(R.id.btnClear)
        btnLivePlay          = findViewById(R.id.btnLivePlay)
        btnLivePause         = findViewById(R.id.btnLivePause)
        liveProgressRing     = findViewById(R.id.liveProgressRing)
        liveButtonContainer  = findViewById(R.id.liveButtonContainer)
        onboardingContainer  = findViewById(R.id.onboardingContainer)
        pageNotif            = findViewById(R.id.pageNotif)
        pageA11y             = findViewById(R.id.pageA11y)
        pageA11ySingle       = findViewById(R.id.pageA11ySingle)
        editOverlay          = findViewById(R.id.editOverlay)
        etEditOriginal       = findViewById(R.id.etEditOriginal)
    }

    private fun setupRegionButton() {
        updateRegionButton()

        // Long press on any bottom-bar button → drag-to-select dropdown
        applyRegionDropdownGestures(btnTranslate)
        applyRegionDropdownGestures(btnCapturing)
        applyRegionDropdownGestures(btnChangeRegion)
        applyRegionDropdownGestures(btnLivePlay)
        applyRegionDropdownGestures(btnLivePause)
    }

    /** Attaches long-press + drag-to-select region picker gestures to [btn]. */
    private fun applyRegionDropdownGestures(btn: View) {
        btn.setOnLongClickListener {
            inDragMode = true
            btn.isPressed = false
            showRegionDropdown(btn)
            true
        }
        btn.setOnTouchListener { _, event ->
            if (!inDragMode) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE   -> { updateDropdownHighlight(event.rawY); true }
                MotionEvent.ACTION_UP     -> { commitDropdownSelection(); true }
                MotionEvent.ACTION_CANCEL -> { dismissDropdown(); inDragMode = false; false }
                else -> false
            }
        }
    }

    private fun showRegionPickerSheet() {
        prefs.captureDisplayId = findGameDisplayId()
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val gameDisplay = displayManager.getDisplay(prefs.captureDisplayId) ?: return
        RegionPickerSheet().also { sheet ->
            sheet.gameDisplay = gameDisplay
            sheet.onSaved = { _ ->
                updateRegionButton()
                configureService()
                withAccessibility { captureService?.captureOnce() }
            }
            sheet.onTranslateOnce = { top, bottom, left, right, label ->
                overrideRegionLabel = label
                overrideRegion = floatArrayOf(top, bottom, left, right)
                applyOverrideIfActive()
                updateRegionButton()
                withAccessibility { captureService?.captureOnce() }
            }
        }.show(supportFragmentManager, RegionPickerSheet.TAG)
    }

    private fun updateRegionButton() {
        val list = prefs.getRegionList()
        val entry = list.getOrElse(prefs.captureRegionIndex) { Prefs.DEFAULT_REGION_LIST[0] }
        val label = overrideRegionLabel ?: entry.label
        if (isLiveMode) {
            val prefix = "Capturing "
            btnCapturing.text = SpannableStringBuilder(prefix + label).apply {
                setSpan(StyleSpan(Typeface.BOLD), prefix.length, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            btnTranslate.visibility = View.GONE
            btnCapturing.visibility = View.VISIBLE
        } else {
            val prefix = "Translate "
            btnTranslate.text = SpannableStringBuilder(prefix + label).apply {
                setSpan(StyleSpan(Typeface.BOLD), prefix.length, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            btnCapturing.visibility = View.GONE
            btnTranslate.visibility = View.VISIBLE
        }
    }

    private fun toggleLiveMode() {
        if (isLiveMode) stopLiveMode() else withAccessibility { startLiveMode() }
    }

    private fun startLiveMode() {
        isLiveMode = true
        btnLivePlay.visibility = View.GONE
        btnLivePause.visibility = View.VISIBLE
        btnClear.visibility = View.GONE
        updateRegionButton()
        resultFragment?.showStatus(searchingStatusText())
        ensureConfigured()
        captureService?.startLive()
    }

    private fun stopLiveMode() {
        isLiveMode = false
        btnLivePause.visibility = View.GONE
        btnLivePlay.visibility = View.VISIBLE
        liveProgressRing.visibility = View.GONE
        captureService?.stopLive()
        updateRegionButton()
        val frag = resultFragment
        if (frag != null && frag.isShowingResults) {
            btnClear.visibility = View.VISIBLE
        } else {
            frag?.showStatus(getString(R.string.status_idle))
        }
    }

    private fun pauseLiveMode() {
        if (isLiveMode) stopLiveMode()
    }

    private fun setupButtons() {
        btnChangeRegion.setOnClickListener { showRegionPickerSheet() }
        btnTranslate.setOnClickListener {
            withAccessibility {
                applyOverrideIfActive()
                captureService?.captureOnce()
            }
        }
        btnCapturing.setOnClickListener { showRegionPickerSheet() }

        btnSettings.setOnClickListener { openSettings() }

        btnLivePlay.setOnClickListener { toggleLiveMode() }
        btnLivePause.setOnClickListener { toggleLiveMode() }

        btnClear.setOnClickListener {
            resultFragment?.showStatus(getString(R.string.status_idle))
            btnClear.visibility = View.GONE
        }
    }

    private fun applyTheme() {
        val idx = getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE)
            .getInt("theme_index", 0)
        setTheme(when (idx) {
            1    -> R.style.Theme_PlayTranslate_White
            2    -> R.style.Theme_PlayTranslate_Rainbow
            3    -> R.style.Theme_PlayTranslate_Purple
            else -> R.style.Theme_PlayTranslate
        })
    }

    private fun openSettings() {
        showSettingsSheet(hideDismiss = false)
    }

    /** Creates and shows a SettingsBottomSheet with all callbacks wired. */
    private fun showSettingsSheet(hideDismiss: Boolean) {
        SettingsBottomSheet.newInstance(hideDismiss = hideDismiss).also { sheet ->
            sheet.onDisplayChanged = {
                captureService?.resetConfiguration()
                configureService()
                PlayTranslateAccessibilityService.instance?.ensureFloatingIcon()
            }
            sheet.onHideLiveModeChanged = {
                applyLiveModeVisibilitySetting()
            }
            sheet.onHideTranslationChanged = {
                configureService()
            }
            sheet.onScreenModeChanged = {
                checkOnboardingState()
            }
        }.show(supportFragmentManager, SettingsBottomSheet.TAG)
    }

    /** True when the user has a working capture method set up (accessibility or screen recording). */
    private val isCaptureReady: Boolean
        get() = PlayTranslateAccessibilityService.isEnabled || prefs.captureMethod == "media_projection"

    /** Dims the action button when no capture method is available. */
    private fun updateActionButtonState() {
        val ready = isCaptureReady
        btnTranslate.alpha = if (ready) 1f else 0.45f
        val frag = resultFragment ?: return
        if (frag.isStatusVisible()) {
            if (!ready) {
                frag.showStatus(getString(R.string.status_accessibility_needed))
                frag.setStatusHintVisibility(false)
            } else if (frag.getStatusText() == getString(R.string.status_accessibility_needed)) {
                frag.showStatus(getString(R.string.status_idle))
            } else if (frag.getStatusText() == getString(R.string.status_idle)) {
                frag.setStatusHintVisibility(true)
            }
        }
    }

    // ── Service ───────────────────────────────────────────────────────────

    private fun startAndBindService() {
        val intent = Intent(this, CaptureService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun wireServiceCallbacks() {
        val svc = captureService ?: return
        svc.onResult = { result ->
            runOnUiThread {
                editTranslationJob?.cancel()
                editTranslationJob = null
                // Restore saved region config after one-shot, but only if no override is active
                if (!isLiveMode && overrideRegion == null) configureService()
                liveProgressRing.visibility = View.GONE
                resultFragment?.displayResult(result)
                if (!isLiveMode) btnClear.visibility = View.VISIBLE
            }
        }
        svc.onError = { msg ->
            runOnUiThread { resultFragment?.showError(msg) }
        }
        svc.onStatusUpdate = { msg ->
            runOnUiThread { resultFragment?.showStatus(msg) }
        }
        svc.onTranslationStarted = {
            runOnUiThread { liveProgressRing.visibility = View.VISIBLE }
        }
        svc.onLiveNoText = {
            runOnUiThread { if (isLiveMode) resultFragment?.showStatus(searchingStatusText()) }
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

        val frag = resultFragment ?: return
        frag.showTranslatingPlaceholder(lineText, segments)
        btnClear.visibility = View.VISIBLE

        val svc = captureService
        if (svc != null && svc.isConfigured && !prefs.hideTranslation) {
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
                    frag.displayResult(result)
                } catch (e: Exception) {
                    frag.updateTranslation("")
                }
            }
        } else {
            frag.updateTranslation("")
        }
    }

    // ── Region capture from floating icon ─────────────────────────────────

    private fun handleRegionCapture(intent: Intent) {
        val topFrac    = intent.getFloatExtra(EXTRA_TOP_FRAC, 0f)
        val bottomFrac = intent.getFloatExtra(EXTRA_BOTTOM_FRAC, 1f)
        val leftFrac   = intent.getFloatExtra(EXTRA_LEFT_FRAC, 0f)
        val rightFrac  = intent.getFloatExtra(EXTRA_RIGHT_FRAC, 1f)

        if (isLiveMode) pauseLiveMode()

        overrideRegionLabel = DRAGGED_REGION_LABEL
        overrideRegion = floatArrayOf(topFrac, bottomFrac, leftFrac, rightFrac)
        applyOverrideIfActive()
        updateRegionButton()
        captureService?.captureOnce()
    }

    // ── Accessibility service flow ─────────────────────────────────────────

    private fun withAccessibility(action: () -> Unit) {
        if (PlayTranslateAccessibilityService.isEnabled) {
            ensureConfigured()
            action()
            return
        }
        if (prefs.captureMethod == "media_projection") {
            if (captureService?.hasMediaProjection == true) {
                ensureConfigured()
                action()
            } else {
                pendingAfterMpGrant = { ensureConfigured(); action() }
                val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjectionLauncher.launch(mgr.createScreenCaptureIntent())
            }
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

    /** Re-applies the override region config if one is active. */
    private fun applyOverrideIfActive() {
        val region = overrideRegion ?: return
        val label = overrideRegionLabel ?: return
        captureService?.configure(
            displayId             = prefs.captureDisplayId,
            sourceLang            = selectedSourceLang(),
            targetLang            = selectedTargetLang(),
            captureTopFraction    = region[0],
            captureBottomFraction = region[1],
            captureLeftFraction   = region[2],
            captureRightFraction  = region[3],
            regionLabel           = label
        )
    }

    /** Clears any dragged-region override. */
    private fun clearOverride() {
        overrideRegionLabel = null
        overrideRegion = null
    }

    /** Applies all current prefs to the capture service. */
    private fun configureService() {
        val svc = captureService ?: return
        clearOverride()
        val entry = prefs.getRegionList().getOrElse(prefs.captureRegionIndex) { Prefs.DEFAULT_REGION_LIST[0] }
        svc.configure(
            displayId             = prefs.captureDisplayId,
            sourceLang            = selectedSourceLang(),
            targetLang            = selectedTargetLang(),
            captureTopFraction    = entry.top,
            captureBottomFraction = entry.bottom,
            captureLeftFraction   = entry.left,
            captureRightFraction  = entry.right,
            regionLabel           = entry.label
        )
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

    private fun isSingleScreen(): Boolean = Prefs.isSingleScreen(this)

    private fun checkOnboardingState() {
        val notifGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        val a11yEnabled = PlayTranslateAccessibilityService.isEnabled
        val singleScreen = isSingleScreen()
        val existingSheet = supportFragmentManager.findFragmentByTag(SettingsBottomSheet.TAG) as? SettingsBottomSheet

        // Notification permission always comes first regardless of screen mode
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
            btnSettings.visibility = View.GONE
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

        val captureReady = a11yEnabled || prefs.captureMethod == "media_projection"
        if (captureReady) {
            onboardingContainer.visibility = View.GONE
            btnSettings.visibility = View.VISIBLE
            return
        }
        showOnboardingPage(pageA11y)
    }

    private fun showOnboardingPage(page: View) {
        onboardingContainer.visibility = View.VISIBLE
        btnSettings.visibility = View.GONE
        pageNotif.visibility      = if (page == pageNotif)      View.VISIBLE else View.GONE
        pageA11y.visibility       = if (page == pageA11y)       View.VISIBLE else View.GONE
        pageA11ySingle.visibility = if (page == pageA11ySingle) View.VISIBLE else View.GONE
    }

    private fun setupOnboarding() {
        pageNotif.findViewById<View>(R.id.btnGrantNotif).setOnClickListener {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        pageA11y.findViewById<View>(R.id.btnOpenA11y).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        pageA11y.findViewById<View>(R.id.btnUseScreenRecord).setOnClickListener {
            val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjectionLauncher.launch(mgr.createScreenCaptureIntent())
        }
        pageA11ySingle.findViewById<View>(R.id.btnOpenA11ySingle).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    // ── Display detection ─────────────────────────────────────────────────

    private fun findGameDisplayId(): Int {
        val myDisplayId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.displayId ?: Display.DEFAULT_DISPLAY
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.displayId
        }

        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        return displayManager.displays
            .firstOrNull { it.displayId != myDisplayId }
            ?.displayId
            ?: prefs.captureDisplayId
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun selectedSourceLang() = TranslateLanguage.JAPANESE
    private fun selectedTargetLang() = TranslateLanguage.ENGLISH

    /**
     * Sets tvLiveHint text with an inline play icon ImageSpan.
     * Called once on resume so the span is ready before first display.
     */
    private fun initLiveHintText() {
        val frag = resultFragment ?: return
        val icon = ContextCompat.getDrawable(this, R.drawable.ic_play)?.mutate() ?: return
        icon.setTint(themeColor(R.attr.colorTextHint))
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
        val regions = prefs.getRegionList()
        val entry = regions.getOrElse(prefs.captureRegionIndex) { Prefs.DEFAULT_REGION_LIST[0] }
        val label = overrideRegionLabel ?: entry.label
        return "Searching for $lang in the \"$label\" area"
    }

    private fun applyLiveModeVisibilitySetting() {
        val hide = prefs.hideLiveMode
        if (hide && isLiveMode) stopLiveMode()
        liveButtonContainer.visibility = if (hide) View.GONE else View.VISIBLE
        val frag = resultFragment ?: return
        if (frag.isStatusVisible()) {
            val isIdle = frag.getStatusText() == getString(R.string.status_idle)
            frag.setLiveHintVisibility(isIdle && !hide && !isLiveMode)
        }
    }

    private fun langDisplayName(langCode: String): String =
        Locale(langCode).getDisplayLanguage(Locale.ENGLISH)
            .replaceFirstChar { it.uppercase() }

    private fun showEditOverlay(charOffset: Int) {
        val currentText = resultFragment?.lastResult?.originalText ?: return
        etEditOriginal.setText(currentText)
        val safeOffset = charOffset.coerceIn(0, currentText.length)
        etEditOriginal.setSelection(safeOffset)
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

        val frag = resultFragment ?: return
        frag.updateOriginalText(newText)

        editTranslationJob?.cancel()
        editTranslationJob = lifecycleScope.launch {
            try {
                val tm = editTranslationManager
                    ?: TranslationManager(selectedSourceLang(), selectedTargetLang()).also { editTranslationManager = it }
                tm.ensureModelReady()
                val translated = tm.translate(newText)
                frag.updateTranslation(translated)
            } catch (_: Exception) {
                frag.updateTranslation("—")
            }
        }
    }

    private fun setupEditOverlay() {
        // Pause live mode when the user scrolls the results (shows intent to read)
        resultFragment?.getResultsScrollView()?.setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
            if (scrollY != oldScrollY && isLiveMode && !suppressScrollPause) pauseLiveMode()
        }

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

    // ── Region quick-dropdown ──────────────────────────────────────────────

    private fun showRegionDropdown(anchor: View) {
        val regions = prefs.getRegionList()
        if (regions.isEmpty()) return

        prefs.captureDisplayId = findGameDisplayId()
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val gameDisplay = displayManager.getDisplay(prefs.captureDisplayId)

        val currentIndex = prefs.captureRegionIndex.coerceIn(0, regions.lastIndex)
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
            setBackgroundColor(themeColor(R.attr.colorBgSurface))
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
            PlayTranslateAccessibilityService.instance?.showRegionOverlay(
                gameDisplay, entry.top, entry.bottom, entry.left, entry.right
            )
        }
    }

    private fun updateDropdownHighlight(rawY: Float) {
        val relativeY = rawY - dropdownTopY
        val rowIdx = (relativeY / dropdownItemHeightPx).toInt()
            .coerceIn(0, dropdownRegionOrder.size - 1)
        if (rowIdx == dropdownHighlightedRow) return

        updateRowHighlight(dropdownRows[dropdownHighlightedRow], false)
        updateRowHighlight(dropdownRows[rowIdx], true)
        dropdownHighlightedRow = rowIdx

        val regionIdx = dropdownRegionOrder[rowIdx]
        if (regionIdx >= 0) {
            val entry = dropdownRegions[regionIdx]
            PlayTranslateAccessibilityService.instance?.updateRegionOverlay(
                entry.top, entry.bottom, entry.left, entry.right
            )
        }
    }

    private fun commitDropdownSelection() {
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
        val hadOverride = overrideRegion != null
        if (changedSavedRegion) {
            prefs.captureRegionIndex = selectedRegionIdx
            configureService()          // clears override
            updateRegionButton()        // now reads the saved region label
            withAccessibility { captureService?.captureOnce() }
        } else if (hadOverride) {
            // User released on the current saved region — clear the drawn override
            configureService()
            updateRegionButton()
        }
    }

    private fun openAddCustomRegionFromDropdown() {
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val gameDisplay = displayManager.getDisplay(prefs.captureDisplayId)
        AddCustomRegionSheet().also { sheet ->
            sheet.gameDisplay = gameDisplay
            sheet.onRegionAdded = { newIndex ->
                prefs.captureRegionIndex = newIndex
                updateRegionButton()
            }
            sheet.onDismissed = {}
            sheet.onTranslateOnce = { top, bottom, left, right, label ->
                overrideRegionLabel = label
                overrideRegion = floatArrayOf(top, bottom, left, right)
                applyOverrideIfActive()
                updateRegionButton()
                withAccessibility { captureService?.captureOnce() }
            }
        }.show(supportFragmentManager, AddCustomRegionSheet.TAG)
    }

    private fun dismissDropdown() {
        dropdownPopup?.dismiss()
        dropdownPopup = null
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
                if (highlighted) R.attr.colorBgCard else R.attr.colorBgSurface))

            if (isAddNew) {
                val tv = TextView(this@MainActivity).apply {
                    text = label
                    textSize = 14f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(themeColor(R.attr.colorAccentPrimary))
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
                    setTextColor(themeColor(R.attr.colorTextPrimary))
                }
                addView(rb)
                addView(tv)
            }
        }
    }

    private fun updateRowHighlight(row: View, highlighted: Boolean) {
        row.setBackgroundColor(themeColor(
            if (highlighted) R.attr.colorBgCard else R.attr.colorBgSurface))
        ((row as? LinearLayout)?.getChildAt(0) as? RadioButton)?.isChecked = highlighted
    }

    companion object {
        const val ACTION_DRAG_SENTENCE = "com.gamelens.ACTION_DRAG_SENTENCE"
        const val EXTRA_DRAG_LINE_TEXT = "extra_drag_line_text"
        const val EXTRA_DRAG_SCREENSHOT_PATH = "extra_drag_screenshot_path"
        const val ACTION_REGION_CAPTURE = "com.gamelens.ACTION_REGION_CAPTURE"
        const val EXTRA_TOP_FRAC = "extra_top_frac"
        const val EXTRA_BOTTOM_FRAC = "extra_bottom_frac"
        const val EXTRA_LEFT_FRAC = "extra_left_frac"
        const val EXTRA_RIGHT_FRAC = "extra_right_frac"
        const val DRAGGED_REGION_LABEL = "Drawn Region"

        @Volatile
        var isInForeground = false
    }
}
