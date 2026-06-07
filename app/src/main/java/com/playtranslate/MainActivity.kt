package com.playtranslate

import com.playtranslate.capture.CaptureBackendResolver

import android.Manifest
import com.playtranslate.applyEdgeToEdge
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
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
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
import com.playtranslate.language.PackKind
import com.playtranslate.language.UpgradeMode
import com.playtranslate.language.PackUpgradeOrchestrator
import com.playtranslate.language.PreloadResult
import com.playtranslate.language.SourceLanguageEngines
import com.playtranslate.language.SourceLanguageProfiles
import com.playtranslate.language.StalePack
import com.playtranslate.model.TextSegments
import com.playtranslate.model.TranslationResult
import com.playtranslate.translation.OfflineModelReclaimer
import com.playtranslate.ui.AppReadiness
import com.playtranslate.ui.HomeAction
import com.playtranslate.ui.Tab
import com.playtranslate.ui.decideHome
import com.playtranslate.ui.ClickableTextView
import com.playtranslate.ui.DimController
import com.playtranslate.ui.OnboardingViewModel
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
import androidx.core.view.isVisible
import androidx.core.net.toUri
import androidx.core.view.isGone

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
    private lateinit var bottomBar: View
    private lateinit var onboardingContainer: View
    private lateinit var pageWelcome: View
    private lateinit var pageNotif: View
    private lateinit var pageA11y: View
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

    /** The onboarding readiness gate. Derives [AppReadiness] from setup + screen
     *  mode; this activity collects [OnboardingViewModel.state] and routes, and
     *  calls [OnboardingViewModel.refresh] (after `reresolve`) from each trigger. */
    private val onboardingVm: OnboardingViewModel by viewModels()

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
    /** Displays the dropdown's region selection writes to and previews on.
     *  Computed as captureDisplayIds minus the activity's foreground display
     *  so the user is configuring the screen they're looking at game content
     *  on, not the one currently holding the picker — see
     *  [dropdownTargetDisplayIds]. The first id is also used as the
     *  preview-overlay target since the region indicator is single-display. */
    private var dropdownTargetIds: List<Int> = emptyList()
    private var dropdownRegions = listOf<RegionEntry>()

    // ── Display listener (detects screen connect/disconnect) ─────────────

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) { runOnUiThread {
            dumpDisplayState("displayAdded:$displayId")
            if (!isFinishing) {
                refreshReadiness()
                CaptureBackendResolver.activeOverlayUi?.reconcileFloatingIcons()
            }
        } }
        override fun onDisplayRemoved(displayId: Int) { runOnUiThread {
            dumpDisplayState("displayRemoved:$displayId")
            if (!isFinishing) {
                refreshReadiness()
                CaptureBackendResolver.activeOverlayUi?.reconcileFloatingIcons()
            }
        } }
        override fun onDisplayChanged(displayId: Int) { runOnUiThread {
            dumpDisplayState("displayChanged:$displayId")
            if (!isFinishing) {
                // Treat doze/un-doze as topology changes: capturableDisplays()
                // filters on STATE_ON, so isSingleScreen() flips when a
                // secondary display dims, and the onboarding/floating-icon
                // state has to reconcile the same way it does on add/remove.
                // Both handlers are idempotent — brightness changes and
                // rotations that don't flip the predicates are no-ops.
                refreshReadiness()
                CaptureBackendResolver.activeOverlayUi?.reconcileFloatingIcons()
            }
        } }
    }

    /**
     * Diagnostic dump of the device's display topology and our windowing
     * state. Lands in the user-facing logcat export (last 5000 lines) so
     * support reports can confirm whether [Prefs.hasMultipleDisplays] is
     * seeing the right thing on unusual devices (foldables, dual-screen,
     * Surface Duo). Tag is fresh so it greps cleanly.
     */
    private fun dumpDisplayState(reason: String) {
        try {
            val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val displays = dm.displays
            val presentation = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            val winBounds = windowManager.currentWindowMetrics.bounds
            android.util.Log.i(TAG_DISPLAY_DUMP,
                "[$reason] displays=${displays.size} presentation=${presentation.size} " +
                    "multiWindow=$isInMultiWindowMode " +
                    "winBounds=${winBounds.width()}x${winBounds.height()}@(${winBounds.left},${winBounds.top}) " +
                    "device='${Build.MANUFACTURER} ${Build.MODEL}' sdk=${Build.VERSION.SDK_INT}")
            displays.forEach { d ->
                val mode = d.mode
                android.util.Log.i(TAG_DISPLAY_DUMP,
                    "  id=${d.displayId} name='${d.name}' " +
                        "flags=0x${Integer.toHexString(d.flags)} state=${d.state} " +
                        "size=${mode.physicalWidth}x${mode.physicalHeight} valid=${d.isValid}")
            }
            presentation.forEach { d ->
                android.util.Log.i(TAG_DISPLAY_DUMP,
                    "  presentationCat id=${d.displayId} name='${d.name}'")
            }
        } catch (t: Throwable) {
            android.util.Log.w(TAG_DISPLAY_DUMP, "[$reason] dump failed", t)
        }
    }

    // ── State ─────────────────────────────────────────────────────────────

    private var selectedTab = Tab.TRANSLATE

    /** The last readiness [route] applied, tracked across STOP→START (it's an
     *  Activity field, so it survives a plain background→foreground but resets
     *  on recreation). Lets [route] tell a true Ready-entering edge — leaving
     *  onboarding, cold launch, recreation — from a steady-state re-emit, so a
     *  plain resume never re-picks the user's tab. */
    private var lastReadiness: AppReadiness? = null

    /** On recreation (rotation / theme) the tab the user was on, for [route]'s
     *  entering-Ready edge to restore; null on a cold launch (route picks the
     *  default home tab). A one-shot — consumed on the first Ready edge. */
    private var restoredTab: Tab? = null

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
        refreshReadiness()
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

    /** Theme + accent this instance was created with; compared in [onResume]
     *  to recreate after a change made on [com.playtranslate.ui.AppearanceSettingsActivity]. */
    private var createdThemeKey: String = ""
    private var createdAccentName: String = ""

    /** Capture-display selection last applied to the running service; compared
     *  in [onResume] to reconfigure after a change made on
     *  [com.playtranslate.ui.CaptureOverlaySettingsActivity]. */
    private var lastSeenCaptureDisplayIds: Set<Int> = emptySet()

    // ── Lifecycle ─────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme()
        createdThemeKey = prefs.themeMode.storageKey
        createdAccentName = prefs.accentName
        lastSeenCaptureDisplayIds = prefs.captureDisplayIds
        applyEdgeToEdge(this)
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
        // Pad for system chrome only (status bar top, cutout sides, nav bar
        // bottom). IME insets are deliberately NOT folded in here and the
        // listener returns a NON-CONSUMED but STRIPPED WindowInsetsCompat —
        // SettingsBottomSheet is hosted inline via openSettingsInline /
        // setShowsDialog(false), and its settingsScrollView listener has to
        // receive ime() insets to handle keyboard avoidance for
        // etCaptureInterval. Consuming would starve it; passing the
        // original insets would let dialog_settings's
        // fitsSystemWindows="true" re-apply the status-bar inset to
        // AppBarLayout (correct in dialog mode where the dialog's own window
        // dispatches insets, doubled in inline mode). Strip the inset types
        // we just consumed so children only see what's left (notably ime).
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(sys.left, sys.top, sys.right, sys.bottom)
            WindowInsetsCompat.Builder(insets)
                .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.NONE)
                .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.NONE)
                .build()
        }

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
        setupEditOverlay()
        startAndBindService()
        (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
            .registerDisplayListener(displayListener, null)
        dumpDisplayState("onCreate")

        // Pack-upgrade gate: scan installed packs against the bundled
        // catalog. If any are stale (catalog packVersion > on-disk
        // packVersion), prompt the user to redownload via OverlayAlert
        // BEFORE running pack-dependent setup (setupOnboarding, the
        // isInstalled+preload check, checkTargetPackMigration). Otherwise
        // those callers race with the upgrade flow. See plan
        // `~/.claude/plans/cheerful-yawning-donut.md` and the StalePack
        // ordering analysis from the plan review.
        maybePromptForPackUpgrade { skipTargetCodes ->
            // Chain the legacy-engines-removed migration alert at the call
            // site here (instead of inside maybePromptForPackUpgrade) so
            // both helpers stay independent — pack-upgrade doesn't need to
            // know about engine-tier migration, and a future deep-link
            // that triggers pack-upgrade-only can call it directly.
            maybePromptForLegacyEnginesRemoved {
                setupOnboarding()
                // Only preload when the source pack is actually present.
                // Fresh-install and data-wiped users route through the welcome
                // flow to download a pack first; preloading before that would
                // just log a PackMissing and is pointless.
                if (LanguagePackStore.isInstalled(applicationContext, prefs.sourceLangId)) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        preloadEngineAndRecover(prefs.sourceLangId)
                    }
                }
                // One-shot migration: if the user already has a non-English target but
                // no target gloss pack installed, offer to download it. Skips any
                // target the upgrade flow already handled (or is about to handle)
                // to avoid two prompts addressing the same pack.
                checkTargetPackMigration(skipTargetCodes)

                // Reclaim ML Kit + Bergamot translation models AND OCR packs
                // orphaned by past deletions, engine switches, or the
                // recovery/upgrade uninstall paths. Runs only after pack
                // upgrades/migrations have settled, so it never sees a pack that's
                // transiently uninstalled mid-upgrade. This launch pass is the ONLY
                // place OCR packs are swept — doing it from the interactive engine
                // switch / language-delete flows can race a live capture resolving
                // the just-orphaned pack (see OcrModelManager.sweepOrphans). No-op
                // once clean; fire-and-forget on IO.
                lifecycleScope.launch(Dispatchers.IO) {
                    OfflineModelReclaimer.sweepOrphans(applicationContext)
                    com.playtranslate.ocr.registry.OcrModelManager.sweepOrphans(applicationContext)
                }
            }
        }

        // Fragment event handlers live on TranslationResultHost (which
        // this activity already implements) — no separate sink wiring
        // needed.

        // The home is composed by route() in onResume, not here — onCreate no
        // longer independently picks a tab. Hide the home surfaces until then so
        // a cold launch never flashes the wrong tab before the gate decides. On
        // a recreate (rotation / theme) remember the tab the user was on for
        // route()'s entering-Ready edge to restore; a cold launch leaves it null
        // and route() picks the default (Settings on the MediaProjection
        // backend, where Turn On lives — its consent doesn't survive a process
        // restart — else Translate).
        setMainSurfacesVisible(false)
        restoredTab = savedInstanceState?.let {
            Tab.entries.getOrElse(it.getInt("selected_tab", 0)) { Tab.TRANSLATE }
        }

        // Start dim controller on dual-screen when not in live mode
        if (Prefs.hasMultipleDisplays(this) && !isLiveMode) {
            dimController = DimController(findViewById(R.id.dimOverlay))
        }

        // Drive the onboarding gate: collect readiness and route it. The
        // collector restarts on each STARTED, so a returning foreground
        // re-applies the current home via the StateFlow's replay (matching the
        // old per-resume re-derive). The first *derivation* is deferred to
        // onResume's refresh() — see OnboardingViewModel — so null is skipped.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                onboardingVm.state.collect { current ->
                    current?.let {
                        route(lastReadiness, it)
                        lastReadiness = it
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        when (intent.action) {
            ACTION_DRAG_SENTENCE -> handleDragSentence(intent)
            ACTION_DRAG_WORD -> handleDragWord(intent)
            ACTION_REGION_CAPTURE -> handleRegionCapture(intent)
            ACTION_START_LIVE -> if (!isLiveMode) {
                // Post so onResume sets isInForeground before startLive triggers
                // updateForegroundState — otherwise In-App Only mode immediately stops.
                window.decorView.post {
                    if (!isDestroyed && !isFinishing) withAccessibility { startLiveMode() }
                }
            }
            ACTION_STOP_LIVE -> if (isLiveMode) stopLiveMode()
            ACTION_ADD_CUSTOM_REGION -> {
                // Floating-icon route carries the originating display so the
                // editor scopes to that screen. Bare action (no extra) is the
                // dropdown route, which fans out to all non-foreground capture
                // displays.
                val iconId = intent.getIntExtra(EXTRA_TARGET_DISPLAY_ID, -1)
                    .takeIf { it != -1 }
                if (iconId != null) openAddCustomRegionFromDropdown(listOf(iconId))
                else openAddCustomRegionFromDropdown()
            }
            ACTION_REFRESH_REGION_LABEL -> {
                // Pure UI refresh — the sole sender (floating-icon
                // onClearRegion in the accessibility service) has already
                // cleared the right display's override before firing this
                // intent. A clearOverride(primary) here would silently
                // drop an unrelated override on a different display when
                // the icon's display isn't the service primary.
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
        // Appearance (theme mode / accent) is changed on the standalone
        // AppearanceSettingsActivity, which writes the prefs and recreates
        // itself. MainActivity, sitting behind it, re-applies on return: if
        // either pref moved since this instance was themed, recreate so
        // applyTheme() resolves the new palette/accent. suppressNextTransition
        // keeps the swap seamless (consumed in onCreate).
        if (prefs.themeMode.storageKey != createdThemeKey ||
            prefs.accentName != createdAccentName) {
            prefs.suppressNextTransition = true
            recreate()
            return
        }
        // Capture-display selection is changed on CaptureOverlaySettingsActivity;
        // reconfigure the running capture on return if it moved (mirrors the old
        // inline onDisplayChanged callback). Overlay-mode changes are picked up
        // by the updateRegionButton() call later in onResume.
        if (prefs.captureDisplayIds != lastSeenCaptureDisplayIds) {
            lastSeenCaptureDisplayIds = prefs.captureDisplayIds
            reconfigureForDisplayChange()
        }
        // Pre-populate the resumed-activity registry before flipping
        // isInForeground. The flag's setter calls reconcileLiveModes, and
        // the Application-level onActivityResumed callback that drives
        // [PlayTranslateApplication.foregroundDisplayId] doesn't run until
        // after this onResume body returns — without this manual write,
        // reconcile would see a null display id and let live mode capture
        // the app's own display for one cycle.
        PlayTranslateApplication.markResumed(this)
        isInForeground = true
        dimController?.onInteraction()
        setupDetectionLog()
        // Service event subscription is set up once in
        // [serviceConnection.onServiceConnected] and held by
        // lifecycleScope's repeatOnLifecycle — no per-resume re-wire
        // needed. (The old re-wire was a band-aid for
        // TranslationResultActivity nulling shared callback fields,
        // which it no longer does.)
        CaptureBackendResolver.activeOverlayUi?.reconcileFloatingIcons()
        refreshReadiness()
        maybeCheckForUpdates()
        // The dual-screen live-hint surface applies only when fully Ready on
        // dual. Read the gate's freshly-refreshed state (refreshReadiness()
        // updated it synchronously) rather than the onboardingContainer view,
        // which the route() collector updates asynchronously.
        if (onboardingVm.state.value == AppReadiness.Ready(AppReadiness.Home.DUAL)) {
            initLiveHintText()
            updateRegionButton()
            updateCaptureReadyStatus()
        }
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
        dumpDisplayState("multiWindow=$isInMultiWindowMode")
        // Entering/leaving split-screen flips Prefs.isSingleScreen, so re-derive
        // the gate to flip the home chrome (bottom bar / forced-Settings) live
        // rather than at the next resume. refresh() — not refreshReadiness() — on
        // purpose: capture is already reconciled by onMultiWindowChanged() below,
        // and reresolve is a no-op for a pure viewport change (its backend choice
        // keys off permissions, not screen mode).
        onboardingVm.refresh()
        // Let a running live session adapt if the viewport predicate flipped.
        // No-op if live mode isn't active.
        CaptureService.instance?.onMultiWindowChanged()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        dumpDisplayState("configChanged")
        // Display swap may have happened without onPause/onResume because
        // our manifest swallows screenLayout|smallestScreenSize via
        // configChanges. The live foregroundDisplayId getter returns the
        // new id correctly, but reconcileLiveModes doesn't refire on its
        // own — drive it from this hook so live mode stops capturing the
        // display the activity just landed on.
        CaptureService.instance?.reconcileLiveModes("configChanged")
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
        bottomBar            = findViewById(R.id.bottomBar)
        onboardingContainer  = findViewById(R.id.onboardingContainer)
        pageWelcome          = findViewById(R.id.pageWelcome)
        pageNotif            = findViewById(R.id.pageNotif)
        pageA11y             = findViewById(R.id.pageA11y)
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
        // Listener is drag-mode only — entered via the long-press above. On
        // normal taps it returns false and the framework's regular click
        // path runs (btn.setOnClickListener elsewhere), so the button stays
        // TalkBack-accessible through that path. No accessibility click to
        // wire into this listener.
        @android.annotation.SuppressLint("ClickableViewAccessibility")
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
        if (supportFragmentManager.findFragmentByTag(RegionPickerSheet.TAG) != null) return
        // The picker resolves its own display state from Prefs.captureDisplayIds
        // and MainActivity.foregroundDisplayId — see RegionPickerSheet.onViewCreated.
        val sheet = RegionPickerSheet().apply {
            setShowsDialog(false)
            onSaved = {
                configureService()
            }
            onTranslateOnce = { region, displayId ->
                selectTab(Tab.TRANSLATE)
                // Apply the override + capture on the picker's active
                // display segment, not primaryGameDisplayId — in a
                // multi-display selection the user can switch the picker
                // pill to a non-primary display, and the gesture should
                // act on that display.
                captureService?.configureOverride(displayId, region)
                withAccessibility { startOneShotCapture(displayId) }
            }
            onClose = { hideRegionPicker() }
        }
        supportFragmentManager.beginTransaction()
            .replace(R.id.regionPickerContainer, sheet, RegionPickerSheet.TAG)
            .commitAllowingStateLoss()
    }

    private fun hideRegionPicker() {
        selectTab(Tab.TRANSLATE)
    }

    private fun refreshRegionPicker() {
        (supportFragmentManager.findFragmentByTag(RegionPickerSheet.TAG) as? RegionPickerSheet)
            ?.refreshFromPrefs()
    }

    /** Bring back the region picker's preview overlay after a hold-to-translate
     *  one-shot evicted it (see RegionPickerSheet.reshowSelectedOverlay). */
    private fun reshowRegionPreview() {
        (supportFragmentManager.findFragmentByTag(RegionPickerSheet.TAG) as? RegionPickerSheet)
            ?.reshowSelectedOverlay()
    }

    private fun updateRegionButton() {
        val region = captureService?.activeRegion ?: prefs.primaryDisplayRegion()
        val label = region.displayName(this)
        val isInAppOnly = Prefs.shouldUseInAppOnlyMode(this)
        val overlayLive = isLiveMode && !isInAppOnly
        val prefixWord = if (overlayLive) getString(R.string.translate_button_prefix_reload)
                         else getString(R.string.translate_button_prefix_translate)
        val prefix = "$prefixWord "
        tvTranslateTitle.text = SpannableStringBuilder(prefix + label).apply {
            setSpan(StyleSpan(Typeface.BOLD), prefix.length, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val hintLabel = when (SourceLanguageProfiles[prefs.sourceLangId].hintTextKind) {
            HintTextKind.PINYIN -> getString(R.string.hint_label_pinyin_lower)
            HintTextKind.FURIGANA -> getString(R.string.hint_label_furigana_lower)
            else -> getString(R.string.hint_label_furigana_lower)
        }
        tvTranslateSubtitle.text = when (captureService?.holdBehavior) {
            CaptureService.HoldBehavior.HIDE_TRANSLATIONS ->
                getString(R.string.translate_button_subtitle_hold_to_hide_translations)
            CaptureService.HoldBehavior.SHOW_TRANSLATIONS_OVER_FURIGANA ->
                getString(R.string.translate_button_subtitle_hold_to_show_translations_instead_of_hint, hintLabel)
            CaptureService.HoldBehavior.SHOW_FURIGANA ->
                getString(R.string.translate_button_subtitle_hold_to_show_hint, hintLabel)
            else ->
                getString(R.string.translate_button_subtitle_hold_to_show_translations)
        }
    }

    private fun toggleLiveMode() {
        if (isLiveMode) stopLiveMode() else withAccessibility { startLiveMode() }
    }

    private fun startLiveMode() {
        if (Prefs.shouldUseInAppOnlyMode(this)) {
            // Dual screen + hide overlays + single display selected: switch
            // to the translate tab so InAppOnly results are visible.
            selectTab(Tab.TRANSLATE)
        }
        doStartLive()
    }

    private fun doStartLive() {
        val ui = CaptureBackendResolver.activeOverlayUi
        val hadPopup = ui?.isAnyDragLookupPopupShowing == true
        ui?.dismissAllDragLookupPopups()
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
                captureService?.holdStartFanout()
            } else {
                withAccessibility { captureService?.holdStartFanout() }
            }
            true
        }
        btnTranslate.setOnTouchListener { _, event ->
            if (translateHoldActive && (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL)) {
                translateHoldActive = false
                captureService?.holdEnd()
                selectTab(selectedTab) // restore button colors
                // The one-shot's post-capture region flash tears down the
                // region picker's persistent preview; bring it back on release.
                if (selectedTab == Tab.REGIONS) reshowRegionPreview()
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
        menuOverlay.isVisible = true
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
                    menuOverlay.isGone = true
                }
            })
            start()
        }
    }

    private val liveRedColor by lazy { themeColor(R.attr.ptDanger) }

    private fun updateMenuLiveItem() {
        if (isLiveMode) {
            menuItemLiveIcon.setImageResource(R.drawable.ic_pause)
            menuItemLiveLabel.text = getString(R.string.live_mode_pause_auto_label)
            ivLiveToggle.setImageResource(R.drawable.ic_pause)
            tvLiveToggle.text = getString(R.string.live_mode_pause_label)
            ivLiveToggle.imageTintList = android.content.res.ColorStateList.valueOf(liveRedColor)
            tvLiveToggle.setTextColor(liveRedColor)
        } else {
            menuItemLiveIcon.setImageResource(R.drawable.ic_play)
            menuItemLiveLabel.text = getString(R.string.live_mode_auto_translate_label)
            ivLiveToggle.setImageResource(R.drawable.ic_play)
            tvLiveToggle.text = getString(R.string.live_mode_auto_label)
            val normalColor = themeColor(R.attr.ptText)
            ivLiveToggle.imageTintList = android.content.res.ColorStateList.valueOf(normalColor)
            tvLiveToggle.setTextColor(normalColor)
        }
    }

    /** Show only the container for [tab]. Factored out of [selectTab] so the
     *  readiness router can restore the selected tab's container after the home
     *  surfaces were hidden for onboarding ([setMainSurfacesVisible]) — a path
     *  selectTab's "only on change" guard would otherwise skip. */
    private fun applyTabVisibility(tab: Tab) {
        resultsContainer.visibility = if (tab == Tab.TRANSLATE) View.VISIBLE else View.GONE
        settingsContainer.visibility = if (tab == Tab.SETTINGS) View.VISIBLE else View.GONE
        regionPickerContainer.visibility = if (tab == Tab.REGIONS) View.VISIBLE else View.GONE
    }

    /** Toggle the persistent home surfaces (the three tab containers + the
     *  bottom bar) for the onboarding-vs-ready split. During onboarding they're
     *  GONE — the onboarding pages own the screen, with the tab/settings stack
     *  out of the layout entirely, not merely covered by an opaque overlay. On
     *  Ready they're restored, the containers following the selected tab. */
    private fun setMainSurfacesVisible(visible: Boolean) {
        bottomBar.isVisible = visible
        if (visible) {
            applyTabVisibility(selectedTab)
        } else {
            resultsContainer.isGone = true
            settingsContainer.isGone = true
            regionPickerContainer.isGone = true
        }
    }

    private fun selectTab(tab: Tab) {
        if (selectedTab != tab) {
            selectedTab = tab

            // ── Container visibility ──
            applyTabVisibility(tab)

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

    /** Add the settings fragment to the already-visible settings container.
     *  Idempotent: a no-op when it's already present, so a screen-mode flip that
     *  re-routes through the Settings tab preserves the live fragment (and its
     *  scroll/state) instead of rebuilding it via `replace()`. */
    private fun openSettingsInline() {
        if (supportFragmentManager.findFragmentByTag(SettingsBottomSheet.TAG) != null) return
        val sheet = SettingsBottomSheet.newInstance().apply {
            setShowsDialog(false)
            onSourceLangChanged = { onSourceLanguageChanged() }
            onScreenModeChanged = {
                refreshReadiness()
            }
            onClose = { hideSettings() }
        }
        supportFragmentManager.beginTransaction()
            .replace(R.id.settingsContainer, sheet, SettingsBottomSheet.TAG)
            .commitAllowingStateLoss()
    }

    private fun hideSettings() {
        selectTab(Tab.TRANSLATE)
    }

    /** Reconfigure the running capture after the display selection changed on
     *  [com.playtranslate.ui.CaptureOverlaySettingsActivity] (mirrors the old
     *  inline onDisplayChanged): re-run configureService, reconcile the floating
     *  icons, and restart live mode if it was running so it picks up the new
     *  display set. */
    private fun reconfigureForDisplayChange() {
        val wasLive = captureService?.isLive == true
        configureService()
        CaptureBackendResolver.activeOverlayUi?.reconcileFloatingIcons()
        if (wasLive) {
            captureService?.stopLive()
            withAccessibility { doStartLive() }
        }
    }


    /** True when the active capture backend's prerequisites are satisfied:
     *  on the accessibility backend, the service is enabled in system
     *  Settings; on the MediaProjection backend, there is no prerequisite to
     *  gate on here (consent is requested on-demand). Reads through
     *  `isEnabled(ctx)` rather than the bound-instance check so the
     *  capture-readiness status doesn't flicker needed → idle while the
     *  accessibility service binds shortly after a cold start — taps during
     *  the brief unbound window are absorbed by the three-way decision in
     *  [withAccessibility] rather than gated here.
     *
     *  Mirrors the broader `captureReady` formula in [OnboardingViewModel]
     *  so MediaProjection users don't see the stale "Accessibility required"
     *  message in the Translate status area. */
    private val isCaptureReady: Boolean
        get() = PlayTranslateAccessibilityService.isEnabled(this) ||
            !CaptureBackendResolver.active().requiresAccessibilityService

    /** Reconciles the result-area status line with capture readiness: shows
     *  the "enable accessibility" prompt while the accessibility service is
     *  disabled, and clears it once the service is enabled. */
    private fun updateCaptureReadyStatus() {
        val ready = isCaptureReady
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
        CaptureBackendResolver.activeOverlayUi?.setIconsDegraded(degraded)
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
                        CaptureBackendResolver.activeOverlayUi?.setIconsLoading(loading)
                    }
                }
            }
        }
        svc.degradationState.observe(this) { kind ->
            onDegradedStateChanged(kind != com.playtranslate.ui.DegradedWarningKind.None)
        }
        svc.liveModeState.observe(this) { isLive -> onLiveModeChanged(isLive) }
        svc.activeRegionLiveData.observe(this) { _ ->
            updateRegionButton()
            if (svc.isOverrideForDisplay(svc.primaryGameDisplayId())) hideRegionPicker()
        }

        ensureConfigured()
    }

    // ── Drag-to-lookup sentence passthrough ──────────────────────────────

    private fun handleDragSentence(intent: Intent) {
        val lineText = intent.getStringExtra(EXTRA_DRAG_LINE_TEXT) ?: return
        val screenshotPath = intent.getStringExtra(EXTRA_DRAG_SCREENSHOT_PATH)

        if (isLiveMode) pauseLiveMode()

        val segments = TextSegments.ofText(lineText)
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(java.util.Date())

        resultVm.showTranslatingPlaceholder(lineText, segments, applicationContext)

        val svc = captureService
        if (svc != null) {
            // translateOnce is a translation-only path — doesn't need
            // display/region (i.e. isConfigured) to be true. translate()
            // self-heals language managers on first call.
            lifecycleScope.launch {
                try {
                    val groupTranslation = svc.translateOnce(lineText)
                    val result = TranslationResult(
                        originalText       = lineText,
                        segments           = segments,
                        translatedText     = groupTranslation.text,
                        timestamp          = timestamp,
                        screenshotPath     = screenshotPath,
                        note               = groupTranslation.note,
                        backendDisplayName = groupTranslation.backendDisplayName,
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

    private fun handleRegionCapture(intent: Intent) {
        if (isLiveMode) pauseLiveMode()
        selectTab(Tab.TRANSLATE)
        // The floating-icon path applies the override on the icon's
        // display before sending the intent (see PlayTranslateAccessibilityService
        // menu.onRegionSelected), so the capture must target the same
        // display rather than primaryGameDisplayId, which can sit on the
        // foregrounded display.
        val displayId = intent.getIntExtra(EXTRA_TARGET_DISPLAY_ID, -1)
            .takeIf { it != -1 }
        startOneShotCapture(displayId)
    }

    /** Initiate a one-shot capture and route its session to the
     *  collector in [wireServiceCallbacks]. No-op if the service isn't
     *  bound yet. [displayId] threads through to [CaptureService.captureOnce]
     *  for callers that have a specific target (e.g. region picker on a
     *  non-primary display); null defers to captureOnce's default
     *  primaryGameDisplayId. */
    private fun startOneShotCapture(displayId: Int? = null) {
        val svc = captureService ?: return
        _currentCaptureSession.value =
            if (displayId != null) svc.captureOnce(displayId) else svc.captureOnce()
    }

    // ── Accessibility service flow ─────────────────────────────────────────

    private fun withAccessibility(action: () -> Unit) {
        when {
            // The active capture backend (MediaProjection) doesn't depend on
            // the accessibility service — run directly without gating on it.
            !CaptureBackendResolver.active().requiresAccessibilityService -> {
                ensureConfigured()
                action()
            }
            PlayTranslateAccessibilityService.isConnected -> {
                ensureConfigured()
                action()
            }
            // Enabled in Settings but Android hasn't bound the service to our
            // process yet (cold-start window, or post-unbind rebinding). Silent
            // no-op — the next tap will catch the bound state. Crucially, we
            // do NOT fall through to showAccessibilityDialog() here: the user
            // has already granted accessibility, prompting again is the bug
            // this routing exists to prevent.
            PlayTranslateAccessibilityService.isEnabled(this) -> {}
            else -> showAccessibilityDialog()
        }
    }

    private fun ensureConfigured() {
        val svc = captureService ?: return
        if (!svc.isConfigured) {
            // First-launch auto-detect: seed the selection set with the
            // detected game display. The hasDisplaySelection guard is
            // load-bearing — `isConfigured` is per-process state (false on
            // every cold-start), so without it this branch would clobber
            // the user's persisted multi-display selection on every restart.
            // The legacy single-display path is safe because
            // [Prefs.migrateLegacyPrefs] (called from onCreate) writes
            // KEY_DISPLAY_IDS from the legacy KEY_DISPLAY_ID before this
            // gate ever runs.
            if (!prefs.hasDisplaySelection) {
                // Seed the saved selection: the auto-detected game display if
                // this backend can capture it, else the backend's fallback
                // (MediaProjection only mirrors the default, so any other
                // detection is stale from the start). Routes through the
                // shared backend shim so seeding behaves like every other
                // call site that turns a selection into the working set.
                prefs.captureDisplayIds = CaptureBackendResolver.active()
                    .capturableTargets(setOf(findGameDisplayId()))
            }
            configureService()
        }
    }

    /** Applies display + region to the capture service. Language managers
     *  self-heal on the next capture via [CaptureService.ensureLanguageManagersFor]
     *  so we no longer pass sourceLang / targetLang here. */
    private fun configureService() {
        val svc = captureService ?: return
        // Per-display region resolution lives in CaptureService now —
        // configureSaved no longer takes a region. Each display's region
        // is read from Prefs.selectedRegionIdForDisplay on demand.
        svc.configureSaved(displayIds = prefs.captureDisplayIds)
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
        CaptureBackendResolver.activeOverlayUi?.reconcileFloatingIcons()
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
            .setTitle(getString(R.string.crash_dialog_title))
            .setMessage(getString(R.string.crash_dialog_message))
            .addButton(getString(R.string.crash_dialog_send), themeColor(R.attr.ptAccent)) {
                lifecycleScope.launch {
                    val files = withContext(Dispatchers.IO) {
                        runCatching {
                            crashFiles + LogExporter.exportLogcat(this@MainActivity)
                        }.getOrElse { crashFiles }
                    }
                    val subject = getString(R.string.crash_email_subject, BuildConfig.VERSION_NAME)
                    val body = getString(R.string.crash_email_body)
                    LogExporter.emailFiles(this@MainActivity, files, subject, body)
                    LogExporter.deleteCrashFiles(this@MainActivity)
                }
            }
            .addButton(
                getString(R.string.crash_dialog_discard),
                themeColor(R.attr.ptDivider),
                themeColor(R.attr.ptDanger)
            ) {
                LogExporter.deleteCrashFiles(this)
            }
            // No handler — scrim tap and "Later" tap both just dismiss;
            // files remain on disk and the prompt re-fires next launch.
            .addCancelButton(getString(R.string.crash_dialog_later))
            .show()
    }

    /**
     * Synchronously scans installed packs against the bundled catalog. If
     * any are stale, shows an OverlayAlert offering Download / Download
     * Later and **defers [onProceed] until the alert resolves**. If
     * nothing is stale, calls [onProceed] immediately.
     *
     * [onProceed] receives the set of target language codes that are
     * being handled by the upgrade flow, so the caller's [checkTargetPackMigration]
     * can skip those targets to avoid double-prompting the user.
     */
    private fun maybePromptForPackUpgrade(onProceed: (skipTargetCodes: Set<String>) -> Unit) {
        val stale = LanguagePackStore.staleInstalledPacks(this)
        val skipTargetCodes: Set<String> = stale
            .filter { it.kind == PackKind.TARGET }
            .mapNotNull { it.targetLangCode }
            .toSet()

        if (stale.isEmpty()) {
            onProceed(skipTargetCodes)
            return
        }

        val message = PackUpgradeOrchestrator.describeForAlert(this, stale)

        // Is the user's ACTIVE source pack one of the FORCE upgrades? A FORCE
        // pack is obsolete and non-functional under this build, so the
        // readiness gate has already routed (or will, on the next onResume)
        // this user to the welcome screen — making the active source's upgrade
        // mandatory, not deferrable. A FORCE pack that ISN'T the active source
        // stays deferrable (the user's current language still works), so that
        // case takes the optional path below unchanged.
        val activeForced = stale.any {
            it.kind == PackKind.SOURCE &&
                it.upgradeMode == UpgradeMode.FORCE &&
                it.sourceLangId == prefs.sourceLangId.packId
        }

        val builder = OverlayAlert.Builder(this)
            .setTitle(getString(
                if (activeForced) R.string.pack_upgrade_mandatory_title
                else R.string.pack_upgrade_title
            ))
            .setMessage(
                if (activeForced) getString(R.string.pack_upgrade_mandatory_message, message)
                else message
            )
            .addButton(
                getString(R.string.pack_upgrade_button_now),
                themeColor(R.attr.ptAccent),
            ) {
                PackUpgradeOrchestrator(this, lifecycleScope).upgradeAll(stale) {
                    onProceed(skipTargetCodes)
                    // A forced-active user was un-readied to the welcome screen;
                    // re-derive so the now-current pack routes them back to the
                    // home. Gated on activeForced so the optional path (where the
                    // user was never un-readied) stays byte-for-byte.
                    if (activeForced) refreshReadiness()
                }
            }

        if (activeForced) {
            // The welcome rows are wired by setupOnboarding inside onProceed,
            // which is gated behind THIS alert. The gate is showing the welcome
            // screen underneath right now, and the user can dismiss this alert
            // (scrim/back) without choosing — so wire the rows now, or they'd be
            // dead. Idempotent with onProceed's own call on the [Update Now] path.
            setupOnboarding()
            // Per the forced-upgrade spec, the deferral slot becomes a
            // destructive Delete (ptDanger, matching showDeleteConfirm): discard
            // the obsolete pack and stay on the welcome screen to pick again. The
            // gate keeps the user here once the active source is gone, so an
            // accidental tap is recoverable (re-pick → re-download).
            builder.addButton(
                getString(R.string.pack_upgrade_button_delete),
                themeColor(R.attr.ptDanger),
                themeColor(R.attr.ptAccentOn),
            ) {
                LanguagePackStore.uninstall(applicationContext, prefs.sourceLangId)
                // Readiness stays Onboarding(LANGUAGE) (still no usable source),
                // so the StateFlow won't re-emit to re-route — refresh the visible
                // welcome rows directly so the deleted source shows as unselected.
                refreshReadiness()
                refreshWelcomeRowsAndButton()
            }
        } else {
            // addCancelButton routes button tap, scrim tap, AND back-press
            // through this handler — those are all explicit user dismissals,
            // so onProceed resumes setupOnboarding/preload/checkTargetPackMigration.
            // LIFECYCLE_PAUSE (host activity paused without the user picking
            // a button) is NOT a decision: skip onProceed so we don't silently
            // advance past a choice the user never made. The downstream chain
            // stays gated for this session; next launch's staleness scan
            // re-prompts.
            builder.addCancelButton(getString(R.string.pack_upgrade_button_later)) { reason ->
                if (reason == com.playtranslate.ui.DismissReason.USER) {
                    onProceed(skipTargetCodes)
                }
            }
        }
        builder.show()
    }

    /**
     * Cold-launch migration alert for users upgrading from a version with
     * `:llama`-backed legacy translators (Qwen GGUF and/or TranslateGemma
     * 4B GGUF). Fires once: if either GGUF (or its `.partial` resume artifact)
     * is on disk, this method deletes all of them and shows a one-time
     * OverlayAlert explaining the change. After deletion, future cold
     * launches find nothing and skip the alert.
     *
     * [onProceed] is always invoked exactly once — same contract as
     * [maybePromptForPackUpgrade]'s onProceed. Tapping "Settings"
     * deep-links to the Offline Translation section so the user can enable
     * the new MNN-backed E2B + Qwen-MNN tiers; tapping "Cancel" or
     * dismissing the scrim just proceeds with no further state change.
     *
     * Deletion is irreversible regardless of which dismissal path the user
     * takes — there's no "Keep them" option because the new code has no
     * way to load the GGUFs (no `:llama` module, no LlamaTranslator). The
     * alert is informational; the work is the cleanup.
     */
    private fun maybePromptForLegacyEnginesRemoved(onProceed: () -> Unit) {
        val modelsDir = java.io.File(noBackupFilesDir, "models")
        val legacyFiles = listOf(
            java.io.File(modelsDir, "qwen2.5-1.5b-instruct-q4_0.gguf"),
            java.io.File(modelsDir, "translategemma-4b-it.Q4_0.gguf"),
        )
        val partialFiles = legacyFiles.map { java.io.File(it.parentFile, "${it.name}.partial") }
        val candidates = legacyFiles + partialFiles
        if (candidates.none { it.exists() }) { onProceed(); return }
        // Delete first — irreversible regardless of which alert button the
        // user taps. Multi-GB GGUFs disappear; .partial siblings (resume
        // artifacts from interrupted downloads) too.
        candidates.forEach { if (it.exists()) it.delete() }
        OverlayAlert.Builder(this)
            .setTitle(getString(R.string.legacy_engines_removed_title))
            .setMessage(getString(R.string.legacy_engines_removed_message))
            .addButton(
                getString(R.string.legacy_engines_removed_button_settings),
                themeColor(R.attr.ptAccent),
            ) {
                startActivity(
                    android.content.Intent(this, com.playtranslate.ui.TranslationServicesActivity::class.java),
                )
                onProceed()
            }
            // Same USER-only gate as pack-upgrade: don't silently advance
            // setupOnboarding/preload on a transient lifecycle pause.
            .addCancelButton(getString(R.string.legacy_engines_removed_button_cancel)) { reason ->
                if (reason == com.playtranslate.ui.DismissReason.USER) {
                    onProceed()
                }
            }
            .show()
    }

    private fun maybeCheckForUpdates() {
        // Only nudge for updates once fully set up. Gate on the readiness state
        // (not the onboardingContainer view, which route() updates async): when
        // this runs straight after refreshReadiness() the view may still be stale.
        if (onboardingVm.state.value !is AppReadiness.Ready) return
        lifecycleScope.launch {
            val release = UpdateChecker.maybeCheck(this@MainActivity) ?: return@launch
            if (!isInForeground || isFinishing || isDestroyed) return@launch
            if (onboardingVm.state.value !is AppReadiness.Ready) return@launch
            showUpdatePopup(release)
        }
    }

    private fun showUpdatePopup(release: UpdateChecker.Release) {
        OverlayAlert.Builder(this)
            .setTitle(getString(R.string.update_dialog_title))
            .setMessage(getString(R.string.update_dialog_message, release.tag))
            .addButton(getString(R.string.update_dialog_view_release), themeColor(R.attr.ptAccent)) {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, release.url.toUri()))
                } catch (_: Exception) {
                    Toast.makeText(this, getString(R.string.toast_no_browser_available), Toast.LENGTH_SHORT).show()
                }
            }
            .addButton(
                getString(R.string.update_dialog_skip),
                themeColor(R.attr.ptDivider),
                themeColor(R.attr.ptDanger)
            ) {
                prefs.updateCheckSkippedTag = release.tag
            }
            // 24h debounce timestamp was already committed inside
            // UpdateChecker.maybeCheck — no per-dismissal bookkeeping
            // needed; both cancel-button tap and scrim tap end the alert.
            .addCancelButton(getString(R.string.update_dialog_ask_again_later))
            .show()
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
            .show()
    }

    /** Apply [current] readiness to the UI — show the onboarding pages (with the
     *  home surfaces hidden), or the steady-state home (surfaces restored). This
     *  is the single place the readiness state machine touches the UI; the
     *  [onboardingVm] collector calls it on every distinct emission, passing the
     *  previously-routed [prev] so the Ready branch can tell an entering edge
     *  from a steady-state re-emit. The derivation (and `reresolve`) live
     *  elsewhere: each trigger does `reresolve(this); onboardingVm.refresh()`.
     *
     *  [Prefs.isSingleScreen] is re-read here only for the CAPTURE page's
     *  dismissal nuance (the step itself doesn't encode screen mode); the Ready
     *  home comes from [current] directly. */
    private fun route(prev: AppReadiness?, current: AppReadiness) {
        when (current) {
            is AppReadiness.Onboarding -> {
                setMainSurfacesVisible(false)
                val existingSheet =
                    supportFragmentManager.findFragmentByTag(SettingsBottomSheet.TAG) as? SettingsBottomSheet
                showOnboardingStep(current.step, Prefs.isSingleScreen(this), existingSheet)
            }
            is AppReadiness.Ready -> {
                setMainSurfacesVisible(true)
                showReadyHome(current.home, prev)
            }
        }
    }

    /** Re-derive onboarding readiness from current system state — the
     *  replacement for the old direct `checkOnboardingState()` calls. Resolves
     *  the capture backend first (a permission granted in system Settings only
     *  reaches us now; this has side effects, so it stays here, not in the VM),
     *  then refreshes the gate. [OnboardingViewModel.refresh] updates the state
     *  synchronously, and the [onboardingVm] collector routes any change. */
    private fun refreshReadiness() {
        CaptureBackendResolver.reresolve(this)
        onboardingVm.refresh()
    }

    /** Show the onboarding page for [step].
     *
     *  LANGUAGE / NOTIFICATION and the single-screen CAPTURE page dismiss any
     *  open inline settings panel; the dual-screen CAPTURE page leaves it in
     *  place behind the overlay, so a returning dual user keeps their panel. */
    private fun showOnboardingStep(
        step: AppReadiness.Step,
        singleScreen: Boolean,
        existingSheet: SettingsBottomSheet?,
    ) {
        when (step) {
            AppReadiness.Step.LANGUAGE -> {
                // Welcome + language setup comes first: tap a language pair
                // before being asked to grant permissions.
                existingSheet?.dismissAllowingStateLoss()
                showOnboardingPage(pageWelcome)
                refreshWelcomeRowsAndButton()
            }
            AppReadiness.Step.NOTIFICATION -> {
                existingSheet?.dismissAllowingStateLoss()
                showOnboardingPage(pageNotif)
            }
            AppReadiness.Step.CAPTURE -> {
                if (singleScreen) {
                    existingSheet?.dismissAllowingStateLoss()
                }
                showOnboardingPage(pageA11y)
            }
        }
    }

    /** Setup is complete — compose the steady-state home for [home]. The home
     *  surfaces were already restored by [route] via [setMainSurfacesVisible];
     *  this decides (purely, via [decideHome]) what sits on them and applies it.
     *
     *  SINGLE_SCREEN forces the inline Settings panel (the only single-screen
     *  surface) and hides the bottom bar; returning to dual lands on Settings
     *  (single-screen excursions aren't tab-tracked — see [decideHome]). DUAL
     *  shows the bottom bar and either re-selects the recreation-saved tab, picks
     *  the entry default, or leaves the current tab alone. The title bar follows
     *  the same screen-mode flip via [SettingsBottomSheet.refreshToolbar]; the
     *  fragment keeps the accessibility dimension fresh on its own resume. */
    private fun showReadyHome(home: AppReadiness.Home, prev: AppReadiness?) {
        val decision = decideHome(
            prev = prev,
            home = home,
            restoredTab = restoredTab,
            requiresA11y = CaptureBackendResolver.active().requiresAccessibilityService,
        )
        restoredTab = decision.restoredTab
        onboardingContainer.isGone = true
        when (val action = decision.action) {
            HomeAction.ForceSettings -> {
                selectTab(Tab.SETTINGS)
                openSettingsInline() // idempotent — builds only if absent, so the panel persists
                bottomBar.isGone = true
            }
            is HomeAction.ShowTab -> {
                applyTab(action.tab)
                bottomBar.isVisible = true
            }
            HomeAction.KeepTab -> {
                bottomBar.isVisible = true
            }
        }
        (supportFragmentManager.findFragmentByTag(SettingsBottomSheet.TAG) as? SettingsBottomSheet)
            ?.refreshToolbar(isSingle = home == AppReadiness.Home.SINGLE_SCREEN)
    }

    /** Select [tab] and ensure its inline fragment is present (idempotent). */
    private fun applyTab(tab: Tab) {
        selectTab(tab)
        when (tab) {
            Tab.SETTINGS -> openSettingsInline()
            Tab.REGIONS -> openRegionPickerInline()
            Tab.TRANSLATE -> {}
        }
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
        val tgtLocale = java.util.Locale.forLanguageTag(effectiveTarget)

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
        onboardingContainer.isVisible = true
        pageWelcome.visibility    = if (page == pageWelcome)    View.VISIBLE else View.GONE
        pageNotif.visibility      = if (page == pageNotif)      View.VISIBLE else View.GONE
        pageA11y.visibility       = if (page == pageA11y)       View.VISIBLE else View.GONE
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
                            refreshReadiness()
                        },
                    )
                }
                else -> refreshReadiness()
            }
        }

        pageNotif.findViewById<View>(R.id.btnGrantNotif).setOnClickListener {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val openOverlaySettings = View.OnClickListener {
            startActivity(overlayPermissionSettingsIntent())
        }
        pageA11y.findViewById<View>(R.id.btnOpenA11y).setOnClickListener(openOverlaySettings)
        // Highlight "PlayTranslate" in the hint text with the theme accent color
        val accentColor = themeColor(R.attr.ptTextTranslation)
        colorizeAppName(pageA11y.findViewById(R.id.tvA11yHintDual), accentColor)
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
            ?: (prefs.captureDisplayIds.firstOrNull() ?: android.view.Display.DEFAULT_DISPLAY)
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
        val textSize = android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_SP, 24f, resources.displayMetrics
        )
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
        val entry = prefs.primaryDisplayRegion()
        val serviceLabel = captureService?.activeRegion?.label?.takeIf { it.isNotEmpty() }
        val label = serviceLabel ?: entry.displayName(this)
        return "Searching for $lang in the \"$label\" area"
    }

    private fun langDisplayName(langCode: String): String =
        Locale.forLanguageTag(langCode).getDisplayLanguage(Locale.getDefault())
            .replaceFirstChar { it.uppercase(Locale.getDefault()) }

    private fun showEditOverlay() {
        val displayed = resultFragment?.getDisplayedOriginalText()?.takeIf { it.isNotBlank() }
        val currentText = displayed
            ?: (resultVm.result.value as? com.playtranslate.ui.ResultState.Ready)
                ?.result?.originalText
            ?: return
        etEditOriginal.setText(currentText)
        etEditOriginal.setSelection(currentText.length)
        editOverlay.isVisible = true
        etEditOriginal.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.showSoftInput(etEditOriginal, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }

    private fun commitEdit() {
        if (editOverlay.visibility != View.VISIBLE) return
        editOverlay.isGone = true
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
                val groupTranslation = svc.translateOnce(newText)
                resultVm.updateTranslation(groupTranslation.text, groupTranslation.backendDisplayName)
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
            if (wasKeyboardVisible && !keyboardVisible && editOverlay.isVisible) {
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
            val row = buildDropdownRow(getString(mode.displayNameRes), mode == currentMode)
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

    /** All capture displays the dropdown should write to: the user's
     *  selected-for-capture set minus whichever display the activity is
     *  currently foregrounded on (the user is looking at game content on
     *  the OTHER displays). Falls back to the full capture set when the
     *  filter would empty (single-display setups, or activity foregrounded
     *  outside the capture set), so the dropdown always has somewhere to
     *  apply the region. Order matches captureDisplayIds insertion order
     *  — the first entry is treated as the "primary" preview target since
     *  the region indicator is single-display. */
    private fun dropdownTargetDisplayIds(): List<Int> {
        // Resolve through the backend shim — MediaProjection collapses a
        // stale non-default selection to its fallback display so the
        // dropdown still has something to apply a region to. Matches live
        // start and floating-icon placement.
        val all = CaptureBackendResolver.active()
            .capturableTargets(prefs.captureDisplayIds)
            .toList()
        val filtered = all.filter { it != MainActivity.foregroundDisplayId }
        return filtered.ifEmpty { all }
    }

    private fun showRegionDropdown(anchor: View) {
        val regions = prefs.getRegionList()
        if (regions.isEmpty()) return

        val targetIds = dropdownTargetDisplayIds()
        if (targetIds.isEmpty()) return
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val previewDisplay = displayManager.getDisplay(targetIds.first())

        // "Current" for the row ordering is the first target display's
        // persisted selection, which is also what the preview overlay
        // reflects — so the bottom (highlighted-on-open) row matches the
        // overlay the user sees the moment the dropdown appears.
        val currentId = prefs.selectedRegionIdForDisplay(targetIds.first())
        val currentIndex = regions.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
        val order = mutableListOf<Int>()
        order.add(-1)
        for (i in regions.indices) { if (i != currentIndex) order.add(i) }
        order.add(currentIndex)
        dropdownRegionOrder = order
        dropdownHighlightedRow = order.lastIndex
        dropdownTargetIds = targetIds
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
            if (regionIdx >= 0 && previewDisplay != null) {
                CaptureBackendResolver.activeOverlayUi?.showRegionOverlay(previewDisplay, dropdownRegions[regionIdx])
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

        if (previewDisplay != null) {
            val entry = regions[currentIndex]
            CaptureBackendResolver.activeOverlayUi?.showRegionOverlay(previewDisplay, entry)
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
        val targetIds = dropdownTargetIds
        dismissDropdown()
        inDragMode = false
        if (selectedRegionIdx == -1) {
            // The dropdown's explicit "+ Add custom region" row always
            // opens a blank new-region sheet — never edits the active one.
            openAddCustomRegionFromDropdown(forceNewRegion = true)
            return
        }
        val changedSavedRegion = dropdownHighlightedRow != dropdownRegionOrder.lastIndex
        val hadOverride = captureService?.let { svc ->
            targetIds.any { svc.isOverrideForDisplay(it) }
        } == true
        if (changedSavedRegion) {
            val regionId = dropdownRegions[selectedRegionIdx].id
            // Fan out to every target display — the dropdown's intent is
            // "set this region for the screens I'm looking at game content
            // on", so a 2-display setup with the activity on display 0
            // writes display 1 only, while a 3-display setup writes both
            // game displays at once.
            for (id in targetIds) {
                prefs.setSelectedRegionIdForDisplay(id, regionId)
            }
            configureService()
            if (!isLiveMode) {
                selectTab(Tab.TRANSLATE)
                // Capture the same display we just wrote the region to —
                // configureService can rewrite primaryGameDisplayId to the
                // first selected display, which on a multi-display setup
                // with the activity foregrounded is the activity's own
                // display. Mirrors openAddCustomRegionFromDropdown's
                // editor/translate-once routing.
                withAccessibility { startOneShotCapture(targetIds.first()) }
            }
        } else if (hadOverride) {
            configureService()
        }
    }

    /** [targetIds] is the set of displays the new/edited region applies
     *  to. Default is [dropdownTargetDisplayIds] (fan-out across all
     *  non-foreground capture displays — the dropdown's "set this region
     *  for the screens I'm gaming on" semantic). The floating-icon route
     *  passes a single-element list so the saved region scopes to the
     *  display the user tapped on, matching the icon's per-display
     *  intent. The first id in [targetIds] is also used as the editor
     *  render target and as the inner Translate Once override / capture
     *  display.
     *
     *  [forceNewRegion] always opens a blank new-region sheet, skipping
     *  the seed-from-active-region branch below. The dropdown's explicit
     *  "+ Add custom region" row sets it; the floating menu's Capture
     *  Region route leaves it false so it still edits the active custom
     *  region in place. */
    private fun openAddCustomRegionFromDropdown(
        targetIds: List<Int> = dropdownTargetDisplayIds(),
        forceNewRegion: Boolean = false,
    ) {
        CaptureBackendResolver.activeOverlayUi?.hideRegionOverlay()
        if (targetIds.isEmpty()) return
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        // The drag overlay is single-display; render it on the first target
        // (typically the only non-foregrounded capture display). Saved
        // selections fan out to every target below.
        val targetDisplayId = targetIds.first()
        val gameDisplay = displayManager.getDisplay(targetDisplayId)
        // Editor renders against [targetDisplayId], so initialize from
        // that display's region/override state — not the service's
        // primary, which can be a different display under multi-display
        // selection (foreground-filter routes the dropdown to the OTHER
        // selected screens but primary tracks last-interacted, which can
        // still be the foreground one).
        val current = CaptureService.instance?.activeRegionForDisplay(targetDisplayId)
        AddCustomRegionSheet().also { sheet ->
            sheet.gameDisplay = gameDisplay
            if (!forceNewRegion && current != null && !current.isFullScreen) {
                if (captureService?.isOverrideForDisplay(targetDisplayId) == true) {
                    sheet.initRegion(current)
                } else {
                    val regions = prefs.getRegionList()
                    val editIdx = regions.indexOfFirst { it.id == current.id }.coerceAtLeast(0)
                    sheet.initRegion(current, editIdx)
                    sheet.onRegionEdited = { edited ->
                        for (id in targetIds) prefs.setSelectedRegionIdForDisplay(id, edited.id)
                        configureService()
                    }
                }
            }
            sheet.onRegionAdded = { newEntry ->
                for (id in targetIds) prefs.setSelectedRegionIdForDisplay(id, newEntry.id)
                configureService()
                refreshRegionPicker()
                // Translate-on-save against the editor's target display.
                // configureService can rewrite primaryGameDisplayId to the
                // first selected display, so a default startOneShotCapture()
                // could capture the wrong screen on the floating-icon
                // route (where targetDisplayId is the tapped display, not
                // necessarily the service primary).
                withAccessibility { startOneShotCapture(targetDisplayId) }
            }
            sheet.onDismissed = { refreshRegionPicker() }
            sheet.onTranslateOnce = { region ->
                // The dropdown's drag overlay rendered on [targetDisplayId],
                // so the user drew this region against THAT screen — apply
                // the override and capture against the same display rather
                // than primaryGameDisplayId (which tracks last-interacted
                // and can still point at the app's foregrounded display).
                captureService?.configureOverride(targetDisplayId, region)
                withAccessibility { startOneShotCapture(targetDisplayId) }
            }
        }.show(supportFragmentManager, AddCustomRegionSheet.TAG)
    }

    private fun dismissDropdown() {
        dropdownPopup?.dismiss()
        dropdownPopup = null
        dropdownRows = emptyList()
        dropdownCommitAction = null
        dropdownHighlightListener = null
        CaptureBackendResolver.activeOverlayUi?.hideRegionOverlay()
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

    private fun checkTargetPackMigration(skipTargetCodes: Set<String> = emptySet()) {
        val target = prefs.targetLang
        if (target == "en") return
        // The pack-upgrade flow at launch already prompted the user about
        // these target codes (or just finished re-installing them) — don't
        // double-prompt with the migration AlertDialog.
        if (target in skipTargetCodes) return
        if (LanguagePackStore.isTargetInstalled(this, target)) return
        if (prefs.targetPackMigrationDismissed) return
        val catalogKey = "target-$target"
        if (LanguagePackCatalogLoader.entryForKey(this, catalogKey) == null) return

        val targetLocale = java.util.Locale.forLanguageTag(target)
        val targetName = targetLocale.getDisplayLanguage(targetLocale)
            .replaceFirstChar { it.uppercase(targetLocale) }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.target_pack_migration_title, targetName))
            .setMessage(getString(R.string.target_pack_migration_message, targetName, targetName))
            .setPositiveButton(getString(R.string.lang_download)) { _, _ ->
                com.playtranslate.ui.LanguageSetupActivity.launch(this, com.playtranslate.ui.LanguageSetupActivity.MODE_TARGET)
            }
            .setNegativeButton(getString(R.string.btn_not_now)) { _, _ ->
                prefs.targetPackMigrationDismissed = true
            }
            .setCancelable(false)
            .show()
    }

    companion object {
        private const val TAG_DISPLAY_DUMP = "DisplayDump"

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
        /** Display the floating-icon route originated on. Threaded through
         *  ACTION_REGION_CAPTURE and ACTION_ADD_CUSTOM_REGION so the
         *  one-shot capture and the custom-region editor target the
         *  display the user actually tapped, instead of falling through
         *  to primaryGameDisplayId / dropdownTargetDisplayIds.first. */
        const val EXTRA_TARGET_DISPLAY_ID = "extra_target_display_id"

        @Volatile
        var isInForeground = false
            set(value) {
                if (field == value) return
                field = value
                android.util.Log.d("CaptureService", "MainActivity.isInForeground = $value")
                CaptureService.instance?.updateForegroundState()
                CaptureService.instance?.reconcileLiveModes("isInForeground=$value")
            }

        /**
         * Display id whichever PlayTranslate activity is currently resumed
         * is rendering on, or null when none of our activities is resumed.
         * Live-read via [PlayTranslateApplication.foregroundDisplayId] — no
         * cached value, so an in-place display swap (Android moving the
         * activity between displays without firing onPause/onResume because
         * configChanges swallows screenLayout) is reflected immediately
         * instead of returning a stale id until the next lifecycle event.
         *
         * Used by [CaptureService.reconcileLiveModes] to skip OCR on the
         * display the user is looking at PlayTranslate on (capturing a
         * screen full of app UI translates nothing useful and burns a slot
         * in the global capture mutex). Single-display setups continue to
         * capture as before; the existing single-screen routing handles
         * app-on-game-display already.
         */
        val foregroundDisplayId: Int?
            get() = PlayTranslateApplication.foregroundDisplayId()

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
