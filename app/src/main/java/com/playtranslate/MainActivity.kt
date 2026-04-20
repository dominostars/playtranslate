package com.playtranslate

import android.Manifest
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
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Toast
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.RadioButton
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.playtranslate.BuildConfig
import com.playtranslate.diagnostics.LogExporter
import com.playtranslate.language.LanguagePackCatalogLoader
import com.playtranslate.language.LanguagePackStore
import com.playtranslate.language.HintTextKind
import com.playtranslate.language.SourceLanguageEngines
import com.playtranslate.language.SourceLanguageProfiles
import com.playtranslate.model.TextSegment
import com.playtranslate.model.TranslationResult
import com.playtranslate.ui.ClickableTextView
import com.playtranslate.ui.DimController
import com.playtranslate.ui.OverlayAlert
import android.net.Uri
import com.playtranslate.AnkiManager
import com.playtranslate.TranslationManager
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

class MainActivity : AppCompatActivity(), TranslationResultFragment.TranslationResultHost {

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
        applyTheme()
        super.onCreate(savedInstanceState)
        prefs.migrateLegacyPrefs()
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
        lifecycleScope.launch(Dispatchers.IO) {
            SourceLanguageEngines.get(applicationContext, prefs.sourceLangId).preload()
        }

        // One-shot migration: if the user already has a non-English target but
        // no target gloss pack installed, offer to download it.
        checkTargetPackMigration()

        // Wire up the fragment's edit-original listener for edit overlay
        resultFragment?.setOnEditOriginalListener { showEditOverlay() }

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
        if (hasFocus) dimController?.onInteraction()
    }

    override fun onResume() {
        super.onResume()
        isInForeground = true
        dimController?.onInteraction()
        setupDetectionLog()
        // Re-wire service callbacks in case TranslationResultActivity overwrote them
        if (serviceConnected) wireServiceCallbacks()
        // Re-wire fragment listeners after config change
        resultFragment?.setOnEditOriginalListener { showEditOverlay() }
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
        editTranslationManager?.close()
        super.onDestroy()
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
        pageNotif            = findViewById(R.id.pageNotif)
        pageA11y             = findViewById(R.id.pageA11y)
        pageA11ySingle       = findViewById(R.id.pageA11ySingle)
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
                withAccessibility { captureService?.captureOnce() }
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
            resultFragment?.showStatus(searchingStatusText())
        } else {
            val frag = resultFragment
            if (frag == null || !frag.isShowingResults) {
                frag?.showStatus(getString(R.string.status_idle))
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
                withAccessibility { captureService?.captureOnce() }
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
                captureService?.resetConfiguration()
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
                captureService?.resetConfiguration()
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

    private fun wireServiceCallbacks() {
        val svc = captureService ?: return
        svc.onResult = { result ->
            runOnUiThread {
                editTranslationJob?.cancel()
                editTranslationJob = null
                resultFragment?.displayResult(result)
            }
        }
        svc.onError = { msg ->
            runOnUiThread { resultFragment?.showError(msg) }
        }
        svc.onStatusUpdate = { msg ->
            runOnUiThread { resultFragment?.showStatus(msg) }
        }
        svc.onTranslationStarted = {
            // progress indication removed — menu-based UI
        }
        svc.onLiveNoText = {
            runOnUiThread { if (isLiveMode) resultFragment?.showStatus(searchingStatusText()) }
        }
        // onLiveStopped removed — LiveData observer handles live mode changes
        svc.degradedState.observe(this) { degraded ->
            onDegradedStateChanged(degraded)
        }
        svc.onHoldLoadingChanged = { loading ->
            PlayTranslateAccessibilityService.instance?.floatingIcon?.showLoading = loading
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

        val frag = resultFragment ?: return
        frag.showTranslatingPlaceholder(lineText, segments)

        val svc = captureService
        if (svc != null && svc.isConfigured) {
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

    private fun handleRegionCapture() {
        if (isLiveMode) pauseLiveMode()
        selectTab(Tab.TRANSLATE)
        captureService?.captureOnce()
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

    /** Applies all current prefs to the capture service. Clears any override. */
    private fun configureService() {
        val svc = captureService ?: return
        val entry = prefs.getSelectedRegion()
        svc.configureSaved(
            displayId  = prefs.captureDisplayId,
            sourceLang = selectedSourceLang(),
            targetLang = selectedTargetLang(),
            region     = entry
        )
    }

    private fun onSourceLanguageChanged() {
        val wasLive = captureService?.isLive == true
        // Reset overlay mode if new language has no hint text
        if (SourceLanguageProfiles[prefs.sourceLangId].hintTextKind == HintTextKind.NONE
            && prefs.overlayMode == OverlayMode.FURIGANA) {
            prefs.overlayMode = OverlayMode.TRANSLATION
        }
        captureService?.resetConfiguration()
        configureService()
        updateRegionButton()
        PlayTranslateAccessibilityService.instance?.ensureFloatingIcon()
        lifecycleScope.launch(Dispatchers.IO) {
            SourceLanguageEngines.get(applicationContext, prefs.sourceLangId).preload()
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
                "Later",
                android.graphics.Color.TRANSPARENT,
                themeColor(R.attr.ptTextMuted)
            ) {
                // No action — files remain, prompt re-fires next launch.
            }
            .addButton(
                "Discard",
                android.graphics.Color.TRANSPARENT,
                themeColor(R.attr.ptTextMuted)
            ) {
                LogExporter.deleteCrashFiles(this)
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
                "Ask again later",
                android.graphics.Color.TRANSPARENT,
                themeColor(R.attr.ptTextMuted)
            ) {
                // 24h debounce timestamp was already committed inside
                // UpdateChecker.maybeCheck — no extra bookkeeping needed.
            }
            .addButton(
                "Skip this version",
                android.graphics.Color.TRANSPARENT,
                themeColor(R.attr.ptTextMuted)
            ) {
                prefs.updateCheckSkippedTag = release.tag
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

    private fun showOnboardingPage(page: View) {
        onboardingContainer.visibility = View.VISIBLE
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
    private fun selectedTargetLang() = prefs.targetLang

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
            .replaceFirstChar { it.uppercase() }

    private fun showEditOverlay() {
        val currentText = resultFragment?.getDisplayedOriginalText()?.takeIf { it.isNotBlank() }
            ?: resultFragment?.lastResult?.originalText ?: return
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

    private fun setupDetectionLog() {
        val tv = findViewById<android.widget.TextView>(R.id.tvDetectionLog)
        val enabled = BuildConfig.DEBUG && prefs.debugShowDetectionLog
        DetectionLog.enabled = enabled
        tv.visibility = if (enabled) View.VISIBLE else View.GONE
        DetectionLog.onUpdate = if (enabled) { text -> tv.text = text } else null
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
                withAccessibility { captureService?.captureOnce() }
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
                withAccessibility { captureService?.captureOnce() }
            }
            sheet.onDismissed = { refreshRegionPicker() }
            sheet.onTranslateOnce = { region ->
                captureService?.configureOverride(region)
                withAccessibility { captureService?.captureOnce() }
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

        val targetName = java.util.Locale(target).getDisplayLanguage(java.util.Locale(target))
            .replaceFirstChar { it.uppercase() }
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
