package com.playtranslate.ui

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import androidx.core.widget.NestedScrollView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.playtranslate.AnkiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.playtranslate.Prefs
import com.playtranslate.PlayTranslateAccessibilityService
import com.playtranslate.capturableDisplays
import com.playtranslate.capture.CaptureBackendResolver
import com.playtranslate.R
import com.playtranslate.applyAccentOverlay
import com.playtranslate.fullScreenDialogTheme
import com.playtranslate.overlayPermissionSettingsIntent
import com.playtranslate.themeColor
import kotlinx.coroutines.launch

/**
 * Full-screen settings dialog. Works in two modes:
 *
 * - **Dialog mode** (default): shown via FragmentTransaction.add(). Has toolbar + close button.
 * - **Inline mode** (setShowsDialog(false)): embedded in MainActivity's settingsContainer.
 *
 * All view ↔ pref wiring is delegated to [SettingsRenderer]. This class handles
 * lifecycle, scroll restore, display listeners, and permission results.
 */
class SettingsBottomSheet : DialogFragment() {

    // ── External callbacks (set by the host) ────────────────────────────
    var onDisplayChanged: (() -> Unit)? = null
    var onSourceLangChanged: (() -> Unit)? = null
    var onScreenModeChanged: (() -> Unit)? = null
    var onClose: (() -> Unit)? = null
    var onThemeChanged: ((scrollY: Int) -> Unit)? = null
    var onOverlayModeChanged: (() -> Unit)? = null

    // ── Internal state ──────────────────────────────────────────────────
    private var renderer: SettingsRenderer? = null
    private var currentView: View? = null
    private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var displayListener: DisplayManager.DisplayListener? = null
    private var lastDisplayIds: Set<Int> = emptySet()

    /** The live MediaProjection session this sheet holds a teardown listener
     *  on while resumed (kept so onPause unregisters from the same one).
     *  MediaProjection "active" is held consent, not a pref the
     *  SharedPreferences listener observes — so the Turn On/Off buttons are
     *  refreshed from this teardown callback instead. */
    private var teardownController: com.playtranslate.capture.MediaProjectionController? = null
    private val onProjectionTeardown: () -> Unit = { renderer?.refreshOverlayIconState() }

    private val requestAnkiPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) renderer?.refreshAnkiSection()
    }

    // ── Lifecycle ────────────────────────────────────────────────────────

    override fun getTheme(): Int = fullScreenDialogTheme(requireContext())

    override fun onCreateDialog(savedInstanceState: Bundle?): android.app.Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        applyAccentOverlay(dialog.context.theme, requireContext())
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.dialog_settings, container, false)

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setWindowAnimations(R.style.AnimSlideRight)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        currentView = view
        setupViews(view)
    }

    override fun onDestroyView() {
        renderer?.displayThumbnails?.values?.forEach { it?.recycle() }
        renderer?.displayThumbnails?.clear()
        displayListener?.let {
            val dm = context?.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            dm?.unregisterDisplayListener(it)
        }
        displayListener = null
        renderer?.destroyOverlayIconPreview()
        renderer = null
        currentView = null
        super.onDestroyView()
    }

    override fun onResume() {
        super.onResume()
        // Observe MediaProjection teardown so the Turn On/Off buttons refresh
        // when capture is stopped from outside Settings (e.g. the floating-
        // icon menu's "Turn Off"). Released again in onPause.
        teardownController =
            com.playtranslate.CaptureService.instance?.mediaProjectionController?.also {
                it.addTeardownListener(onProjectionTeardown)
            }
        renderer?.startCaptureButtonShimmer()
        renderer?.refreshAnkiSection()
        renderer?.refreshOverlayIconState()
        // The toolbar hosts the Turn On/Off button on the MediaProjection
        // backend — re-check its visibility in case the accessibility grant
        // changed while we were away (same catch-up reason as the rows here).
        view?.let { refreshToolbarVisibility(it) }
        // Display picker locks to the first display while the a11y service is
        // off (see SettingsRenderer.buildDisplayRow); rebuild on resume so it
        // unlocks the moment the user returns from Accessibility Settings.
        renderer?.refreshDisplayRows(Prefs(requireContext()))
        renderer?.refreshAutoModeToggle()
        // Catch up on the Enhanced auto-translate row in case the user
        // granted (or revoked) accessibility while the sheet was paused.
        renderer?.refreshEnhancedAutoTranslateRow()
        renderer?.refreshTtsSection()
        // Pick up backend toggle changes made while we were paused —
        // DeepLSettingsActivity flips deeplEnabled while the prefs listener
        // is unregistered, so onResume is the catch-up point.
        renderer?.refreshDeeplBackendSwitch()
        renderer?.refreshLingvaBackendSwitch()
        renderer?.refreshTranslategemmaSwitch()
        renderer?.refreshQwenSwitch()
        // Always re-render every backend's status line on resume — picks
        // up new DeepL keys, freshly toggled state, and triggers a usage
        // re-fetch (the call doesn't consume DeepL characters).
        renderer?.refreshAllBackendStatuses()
        // Reconcile the translation cache against any backend preference
        // changes made while we were paused (e.g. DeepLSettingsActivity
        // saving a key flips deeplEnabled on). Without this, cache-hit-only
        // translate batches could keep returning the previous backend's
        // results until some unrelated cache miss happened to trigger
        // reconciliation. The SP listener below handles the same path
        // when Settings is in the foreground; this is the "paused" twin.
        com.playtranslate.CaptureService.instance?.reconcileBackendPreference()

        val ctx = context ?: return
        val sp = ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE)
        prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                "show_overlay_icon" -> renderer?.refreshOverlayIconState()
                "auto_translation_mode" -> renderer?.refreshAutoModeToggle()
                Prefs.KEY_DEEPL_ENABLED -> {
                    renderer?.refreshDeeplBackendSwitch()
                    renderer?.refreshAllBackendStatuses()
                    com.playtranslate.CaptureService.instance?.reconcileBackendPreference()
                }
                Prefs.KEY_LINGVA_ENABLED -> {
                    renderer?.refreshLingvaBackendSwitch()
                    renderer?.refreshAllBackendStatuses()
                    com.playtranslate.CaptureService.instance?.reconcileBackendPreference()
                }
                Prefs.KEY_TRANSLATEGEMMA_ENABLED -> {
                    renderer?.refreshTranslategemmaSwitch()
                    renderer?.refreshAllBackendStatuses()
                    com.playtranslate.CaptureService.instance?.reconcileBackendPreference()
                    maybeUnloadIdleEngines(ctx)
                }
                Prefs.KEY_QWEN_ENABLED -> {
                    renderer?.refreshQwenSwitch()
                    renderer?.refreshAllBackendStatuses()
                    com.playtranslate.CaptureService.instance?.reconcileBackendPreference()
                    maybeUnloadIdleEngines(ctx)
                }
                Prefs.KEY_QWEN_MNN_ENABLED -> {
                    // The MNN tier's parallel to the TG/Qwen handlers above.
                    // Missing this case is what caused the row's toggle UI to
                    // stay stale after a successful download — the pref was
                    // set true but the renderer never knew to refresh.
                    renderer?.refreshQwenMnnSwitch()
                    renderer?.refreshAllBackendStatuses()
                    com.playtranslate.CaptureService.instance?.reconcileBackendPreference()
                    maybeUnloadIdleEngines(ctx)
                }
            }
        }
        sp.registerOnSharedPreferenceChangeListener(prefsListener)
    }

    /**
     * Drop any loaded on-device LLM models whose toggle is currently off,
     * freeing the KV cache + mmap'd weights so the OS can reclaim that RAM.
     *
     *  - `:llama` serves both TG and legacy Qwen; unload only when *both* are
     *    disabled.
     *  - `:mnn` serves only the new Qwen-MNN tier; unload when that's disabled.
     *
     * Each unloadModel is mutex-serialized inside its own translator
     * singleton so it can't race an in-flight translation that started right
     * before the toggle changed.
     */
    private fun maybeUnloadIdleEngines(ctx: Context) {
        val prefs = Prefs(ctx)
        if (!prefs.translateGemmaEnabled && !prefs.qwenEnabled) {
            viewLifecycleOwner.lifecycleScope.launch {
                com.playtranslate.translation.translategemma.LlamaTranslator
                    .getInstance(ctx).unloadModel()
            }
        }
        if (!prefs.qwenMnnEnabled) {
            viewLifecycleOwner.lifecycleScope.launch {
                com.playtranslate.translation.mnn.MnnTranslator
                    .getInstance(ctx).unloadModel()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        teardownController?.removeTeardownListener(onProjectionTeardown)
        teardownController = null
        renderer?.stopCaptureButtonShimmer()
        val ctx = context ?: return
        val sp = ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE)
        prefsListener?.let { sp.unregisterOnSharedPreferenceChangeListener(it) }
        prefsListener = null
    }

    // ── View setup ──────────────────────────────────────────────────────

    /** Show the toolbar in dialog mode (single-screen), and in inline mode
     *  (dual-screen) only when the accessibility service is off — the
     *  MediaProjection backend then needs the Turn On/Off control the toolbar
     *  hosts. With accessibility on, dual-screen capture is always running, so
     *  there is nothing to start. Re-checked on resume since the grant can
     *  change while the user is away in system settings.
     *
     *  Toggles the inner MaterialToolbar, not the AppBarLayout: a GONE
     *  AppBarLayout keeps its stale measured height, so the CoordinatorLayout
     *  leaves the scroll child offset by a phantom toolbar until the view is
     *  rebuilt. The AppBarLayout (wrap_content) collapses to 0 on its own once
     *  its only child is hidden. */
    private fun refreshToolbarVisibility(root: View) {
        val show = showsDialog ||
            !PlayTranslateAccessibilityService.isEnabled(requireContext())
        root.findViewById<View>(R.id.settingsToolbarInner).visibility =
            if (show) View.VISIBLE else View.GONE
    }

    private fun setupViews(view: View) {
        val hideDismiss = arguments?.getBoolean(ARG_HIDE_DISMISS, false) ?: false
        val isDialog = showsDialog
        val prefs = Prefs(requireContext())

        // Toolbar contents — mode-constant, configured once here. The title is
        // always the app name; the close button only makes sense in dialog
        // mode (inline mode is embedded in MainActivity, nothing to dismiss).
        view.findViewById<android.widget.TextView>(R.id.tvSettingsTitle)
            .text = getString(R.string.app_name)
        val closeBtn = view.findViewById<View>(R.id.btnCloseSettings)
        when {
            !isDialog -> closeBtn.visibility = View.GONE
            hideDismiss -> {
                closeBtn.visibility = View.GONE
                dialog?.setOnKeyListener { _, keyCode, event ->
                    if (keyCode == android.view.KeyEvent.KEYCODE_BACK &&
                        event.action == android.view.KeyEvent.ACTION_UP) {
                        activity?.finish()
                        true
                    } else false
                }
            }
            else -> closeBtn.setOnClickListener { dismiss() }
        }
        refreshToolbarVisibility(view)

        // Scroll position restore after theme change vs. deep-link anchor.
        // Anchor wins when present — it's a fresh "land here" navigation, so
        // it should override a stale settingsScrollY left over from a prior
        // theme change (which would otherwise restore a position the user
        // never asked to return to in this flow).
        val settingsScrollView = view.findViewById<NestedScrollView>(R.id.settingsScrollView)
        val anchorName = arguments?.getString(ARG_ANCHOR)
        val savedScroll = prefs.settingsScrollY
        if (anchorName != null) {
            val anchor = runCatching { SettingsAnchor.valueOf(anchorName) }.getOrNull()
            // Clear the saved scroll position so a later theme change after
            // the anchor land doesn't pop the user back to a stale offset.
            if (savedScroll > 0) prefs.settingsScrollY = 0
            if (anchor != null) scrollToAnchor(anchor)
        } else if (savedScroll > 0) {
            fun tryRestore() {
                if (settingsScrollView.height > 0) {
                    settingsScrollView.scrollTo(0, savedScroll)
                    prefs.settingsScrollY = 0
                } else {
                    settingsScrollView.postDelayed(::tryRestore, 16)
                }
            }
            settingsScrollView.post { tryRestore() }
        }

        // Create and bind the renderer
        val r = SettingsRenderer(
            root = view,
            prefs = prefs,
            ctx = requireContext(),
            lifecycleScope = viewLifecycleOwner.lifecycleScope,
            callbacks = object : SettingsRenderer.Callbacks {
                override fun onClose() { this@SettingsBottomSheet.onClose?.invoke() ?: dismiss() }
                override fun onThemeChanged(scrollY: Int) {
                    this@SettingsBottomSheet.onThemeChanged?.invoke(scrollY) ?: run {
                        prefs.settingsScrollY = scrollY
                        prefs.suppressNextTransition = true
                        activity?.recreate()
                    }
                }
                override fun onDisplayChanged() { this@SettingsBottomSheet.onDisplayChanged?.invoke() }
                override fun onSourceLangChanged() { this@SettingsBottomSheet.onSourceLangChanged?.invoke() }
                override fun onOverlayModeChanged() { this@SettingsBottomSheet.onOverlayModeChanged?.invoke() }
                override fun onScreenModeChanged() { this@SettingsBottomSheet.onScreenModeChanged?.invoke() }
                override fun requestAnkiPermission() {
                    requestAnkiPermission.launch(AnkiManager.PERMISSION)
                }
                override fun openLanguageSetup(mode: String) {
                    setLanguageDelegate()
                    LanguageSetupActivity.launch(requireContext(), mode)
                }
                override fun openDeepLSettings() {
                    startActivity(android.content.Intent(requireContext(), DeepLSettingsActivity::class.java))
                }
                override fun openTtsVoicePicker() {
                    startActivity(android.content.Intent(requireContext(), TtsVoiceActivity::class.java))
                }
                override fun openTtsSetup() {
                    val act = activity ?: return
                    showTtsNoEngineDialog(TtsAlertTarget.InActivity(act)) { }
                }
                override fun startTranslateGemmaDownload() {
                    showTranslateGemmaDownloadDialog()
                }
                override fun enableInstalledTranslateGemma() {
                    this@SettingsBottomSheet.enableInstalledTranslateGemma()
                }
                override fun showTranslateGemmaDisableDialog() {
                    this@SettingsBottomSheet.showTranslateGemmaDisableDialog()
                }
                override fun startQwenDownload() {
                    showQwenDownloadDialog()
                }
                override fun enableInstalledQwen() {
                    this@SettingsBottomSheet.enableInstalledQwen()
                }
                override fun showQwenDisableDialog() {
                    this@SettingsBottomSheet.showQwenDisableDialog()
                }
                override fun startQwenMnnDownload() {
                    showQwenMnnDownloadDialog()
                }
                override fun enableInstalledQwenMnn() {
                    this@SettingsBottomSheet.enableInstalledQwenMnn()
                }
                override fun showQwenMnnDisableDialog() {
                    this@SettingsBottomSheet.showQwenMnnDisableDialog()
                }
                override fun onUpdateLanguagePacksTapped(
                    stalePacks: List<com.playtranslate.language.StalePack>
                ) {
                    this@SettingsBottomSheet.startPackUpgrade(stalePacks)
                }
                override fun showHotkeyDialog(
                    title: String?, onSet: (List<Int>) -> Unit, onCancel: () -> Unit
                ) {
                    val dialog = HotkeySetupDialog.newInstance(title)
                    dialog.onHotkeySet = onSet
                    dialog.onCancelled = onCancel
                    dialog.show(childFragmentManager, "hotkey_setup")
                }
                override fun showAccessibilityRequiredAlert(
                    requirement: AccessibilityRequirement
                ) {
                    this@SettingsBottomSheet.showAccessibilityRequiredAlert(requirement)
                }
                override fun requestMediaProjectionControls() {
                    this@SettingsBottomSheet.requestMediaProjectionControls()
                }
                override fun showAnkiDeckPicker(onDeckSelected: () -> Unit) {
                    val picker = AnkiDeckPickerDialog.newInstance()
                    picker.onDeckSelected = onDeckSelected
                    picker.show(childFragmentManager, AnkiDeckPickerDialog.TAG)
                }
                override fun showAnkiCardTypePicker(onPicked: () -> Unit) {
                    // Settings doesn't know mode; use SENTENCE as the
                    // default for Basic-shape detection. Mapping dialog
                    // lets the user override per template anyway.
                    val picker = AnkiCardTypePickerDialog.newInstance(CardMode.SENTENCE)
                    picker.onCardTypePicked = { _, _ -> onPicked() }
                    picker.show(childFragmentManager, AnkiCardTypePickerDialog.TAG)
                }
                override fun showAnkiCardTypeMapping(onSaved: () -> Unit) {
                    val sheet = this@SettingsBottomSheet
                    val ctx = sheet.requireContext()
                    val prefs = Prefs(ctx)
                    val pickedId = prefs.ankiModelId
                    if (pickedId == -1L) return  // shouldn't be reachable; row's hidden
                    sheet.viewLifecycleOwner.lifecycleScope.launch {
                        val models = withContext(Dispatchers.IO) { AnkiManager(ctx).getModels() }
                        if (models.isEmpty()) {
                            // Empty list always means transient query /
                            // permission failure — a working AnkiDroid
                            // install always has built-in Basic + Cloze.
                            // Don't destructively reset prefs; just
                            // surface the error and let the user retry.
                            // Same guard as dispatchSendToAnki.
                            android.widget.Toast.makeText(
                                ctx, R.string.anki_models_unavailable,
                                android.widget.Toast.LENGTH_LONG,
                            ).show()
                            return@launch
                        }
                        val picked = models.firstOrNull { it.id == pickedId }
                        if (picked == null) {
                            // Model genuinely disappeared (models is non-
                            // empty, our id isn't in it). Fall back to
                            // default and Toast.
                            prefs.ankiModelId = -1L
                            prefs.ankiModelName = ""
                            android.widget.Toast.makeText(
                                ctx, R.string.anki_card_type_stale_fallback,
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                            onSaved()
                            return@launch
                        }
                        if (AnkiCardTypeMapper.isBasicShape(picked.fieldNames)) {
                            // Basic-shape templates bypass the mapping
                            // system entirely — dispatchSendToAnki routes
                            // them through assembleBasicNote which
                            // derives Front/Back from the send mode and
                            // ignores any saved mapping. Don't open the
                            // mapping dialog; explain instead.
                            android.widget.Toast.makeText(
                                ctx, R.string.anki_card_type_basic_no_mapping,
                                android.widget.Toast.LENGTH_LONG,
                            ).show()
                            return@launch
                        }
                        val dialog = AnkiFieldMappingDialog.newInstance(
                            modelId = picked.id,
                            modelName = picked.name,
                            fieldNames = picked.fieldNames,
                            mode = CardMode.SENTENCE,
                        )
                        dialog.onSaved = { _, _ -> onSaved() }
                        dialog.show(childFragmentManager, AnkiFieldMappingDialog.TAG)
                    }
                }
                override fun getScrollY(): Int = settingsScrollView.scrollY
            }
        )
        renderer = r

        // Initialize display list and load thumbnails
        setupDisplays(view, r, prefs)

        // Bind all rows
        r.bind()
    }

    // ── Display management ──────────────────────────────────────────────

    private fun setupDisplays(view: View, r: SettingsRenderer, prefs: Prefs) {
        val displayManager = requireContext().getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val displays = displayManager.capturableDisplays()
        lastDisplayIds = displays.mapTo(mutableSetOf()) { it.displayId }

        r.displayList = displays

        // Register display listener for hot-plug
        displayListener?.let { displayManager.unregisterDisplayListener(it) }
        displayListener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) { reinflateIfDisplaysChanged(displayManager) }
            override fun onDisplayRemoved(displayId: Int) { reinflateIfDisplaysChanged(displayManager) }
            // capturableDisplays() filters on STATE_ON, so a fold/unfold or
            // monitor sleep/wake changes the picker's set without firing
            // add/remove. Same-count swaps (one panel off as another comes on)
            // would be missed by a count check, so compare the set of ids.
            override fun onDisplayChanged(displayId: Int) { reinflateIfDisplaysChanged(displayManager) }
        }
        displayManager.registerDisplayListener(displayListener, null)

        // Capture thumbnails asynchronously. On the MediaProjection backend
        // captureSource.requestClean() fails closed without screen-record
        // consent, so the backend source is used only when capture can
        // actually produce a frame; otherwise the own-display activity
        // thumbnail is used, as on the null-source path below.
        val myDisplayId = requireActivity().display?.displayId ?: android.view.Display.DEFAULT_DISPLAY
        val backend = CaptureBackendResolver.active()
        val mgr = backend.captureSource?.takeIf { backend.canCaptureWithoutPrompting }
        // Only the displays the backend can actually capture get a backend
        // thumbnail — MediaProjection mirrors just the default display, so a
        // requestClean for any other display would return the default
        // display's pixels under the wrong row. Other rows get no thumbnail.
        displays.forEach { display ->
            if (mgr != null && backend.canCapture(display.displayId)) {
                viewLifecycleOwner.lifecycleScope.launch {
                    val bitmap = mgr.requestClean(display.displayId)
                    if (bitmap != null) {
                        r.displayThumbnails[display.displayId] = scaleThumbnail(bitmap)
                        view.post { if (isAdded) r.refreshDisplayRows(Prefs(requireContext())) }
                    } else if (display.displayId == myDisplayId) {
                        captureActivityWindow { thumb ->
                            r.displayThumbnails[display.displayId] = thumb
                            if (isAdded) r.refreshDisplayRows(Prefs(requireContext()))
                        }
                    }
                }
            } else if (display.displayId == myDisplayId) {
                captureActivityWindow { thumb ->
                    r.displayThumbnails[display.displayId] = thumb
                    if (isAdded) r.refreshDisplayRows(Prefs(requireContext()))
                }
            }
        }
    }

    private fun reinflateIfDisplaysChanged(dm: DisplayManager) {
        val newIds = dm.capturableDisplays().mapTo(mutableSetOf()) { it.displayId }
        if (newIds != lastDisplayIds && isAdded) {
            lastDisplayIds = newIds
            reinflateContent()
        }
    }

    // ── Re-inflate (used for theme changes in dialog mode) ──────────────

    /**
     * Swaps the bottom-sheet's entire content view for a freshly-inflated
     * [R.layout.dialog_settings]. Used for theme changes (MainActivity)
     * and display hot-plug (this class's display listener).
     *
     * Skipped when:
     *  - The fragment isn't attached or currentView has been detached.
     *  - The parent is a FragmentContainerView. AndroidX's FCV refuses
     *    raw View children — only Fragment-managed views. Showing any
     *    child DialogFragment in this session (deck / card-type /
     *    field-mapping / source picker) replaces the sheet's content
     *    parent with an FCV, and that container persists for the rest
     *    of the session — even after the dialog dismisses. So a
     *    simple "is a child dialog currently shown" check isn't
     *    enough; we have to look at what the parent actually is.
     *
     * The fresh layout is picked up next time Settings opens (its
     * onViewCreated re-runs against a clean view tree).
     */
    fun reinflateContent() {
        if (!isAdded) return
        val old = currentView ?: return
        val parent = old.parent as? ViewGroup ?: return
        if (parent is androidx.fragment.app.FragmentContainerView) {
            android.util.Log.d(TAG,
                "reinflateContent skipped — parent is FragmentContainerView " +
                    "(a child DialogFragment was shown earlier this session)")
            return
        }
        // Recycle the outgoing renderer's preview bitmap — reinflate builds a
        // fresh renderer + preview without going through onDestroyView.
        renderer?.destroyOverlayIconPreview()
        val index = parent.indexOfChild(old)
        parent.removeView(old)
        val newView = LayoutInflater.from(requireActivity())
            .inflate(R.layout.dialog_settings, parent, false)
        parent.addView(newView, index)
        currentView = newView
        setupViews(newView)
        val ctx = requireActivity()
        val bgColor = ctx.themeColor(R.attr.ptBg)
        dialog?.window?.apply {
            statusBarColor = bgColor
            navigationBarColor = bgColor
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(
                ctx.themeColor(R.attr.ptSurface)))
        }
    }

    // ── Language delegate ────────────────────────────────────────────────

    private fun setLanguageDelegate() {
        LanguageSetupActivity.selectionDelegate = object : LanguageSetupActivity.Delegate {
            override fun onSourceSelectionDone(sourceId: com.playtranslate.language.SourceLangId) {
                renderer?.refreshLanguageRow()
                onSourceLangChanged?.invoke()
            }
            override fun onTargetSelectionDone(targetCode: String) {
                renderer?.refreshLanguageRow()
                onSourceLangChanged?.invoke()
            }
        }
    }

    // ── Thumbnail helpers ───────────────────────────────────────────────

    private fun scaleThumbnail(bitmap: Bitmap): Bitmap {
        val targetW = 192
        val scale = targetW.toFloat() / bitmap.width
        val scaled = Bitmap.createScaledBitmap(
            bitmap, targetW, (bitmap.height * scale).toInt(), true
        )
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    private fun captureActivityWindow(onReady: (Bitmap?) -> Unit) {
        val activity = activity ?: run { onReady(null); return }
        val decorView = activity.window.decorView
        val w = decorView.width.takeIf { it > 0 } ?: run { onReady(null); return }
        val h = decorView.height.takeIf { it > 0 } ?: run { onReady(null); return }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try {
            PixelCopy.request(activity.window, bmp, { result ->
                if (result == PixelCopy.SUCCESS) onReady(scaleThumbnail(bmp))
                else { bmp.recycle(); onReady(null) }
            }, Handler(Looper.getMainLooper()))
        } catch (e: IllegalArgumentException) {
            // PixelCopy throws when the activity window has no backing surface
            // — a display-change reinflate can land mid-transition, before the
            // window is drawn. Skip the thumbnail instead of crashing.
            bmp.recycle()
            onReady(null)
        }
    }

    // ── TranslateGemma flow ─────────────────────────────────────────────

    private var translategemmaDownloadJob: kotlinx.coroutines.Job? = null

    /**
     * Returns true and short-circuits if a download for the same backend is
     * already in flight. Three backends use this guard (TG / legacy Qwen /
     * MNN Qwen) — without it, a frustrated user tapping the row repeatedly
     * (because the OverlayProgress dialog isn't showing fast enough, or got
     * dismissed by the back key, etc.) launches one new download coroutine
     * per tap, each opening the same `<name>.partial` with `append=false`
     * and truncating the others' progress to zero. Worst case observed on
     * the MNN Qwen flow: six concurrent downloads in 4 s, app OOM-killed
     * mid-stream, no successful install. See the in-place comments at each
     * call site.
     */
    private fun isDownloadInFlight(job: kotlinx.coroutines.Job?, label: String): Boolean {
        if (job?.isActive == true) {
            android.util.Log.d(TAG, "$label download already in flight; ignoring tap")
            return true
        }
        return false
    }

    /** Kick off [com.playtranslate.language.PackUpgradeOrchestrator] for the
     *  user's deferred-upgrade list (the "Update language packs" cell tap).
     *
     *  Uses **the Activity's lifecycleScope**, NOT this Fragment's view scope:
     *  the OverlayProgress dialog the orchestrator shows is attached to the
     *  Activity's decorView, so it survives a Settings dismiss. Tying the
     *  coroutine to the Fragment view would silently cancel the in-flight
     *  download while the user stares at a frozen progress bar.
     *
     *  On completion, refreshes just the Language section so the cell hides
     *  (since `staleInstalledPacks()` is now empty). Falls back gracefully
     *  if the renderer/activity is gone by the time the orchestrator returns
     *  (Settings dismissed mid-flight). */
    private fun startPackUpgrade(
        stalePacks: List<com.playtranslate.language.StalePack>
    ) {
        val activity = activity as? androidx.appcompat.app.AppCompatActivity ?: return
        com.playtranslate.language.PackUpgradeOrchestrator(activity, activity.lifecycleScope)
            .upgradeAll(stalePacks) {
                if (isAdded && view != null) {
                    runCatching { renderer?.refreshLanguageSection() }
                }
            }
    }

    /**
     * Run the pre-toggle availMem gate before [onProceed]. If
     * `MemoryInfo.availMem` is below [backend].`availMemFloorBytes`, show an
     * OverlayAlert (matching the disable-dialog visual style) with:
     *   - [Check memory again] — re-reads availMem and re-evaluates; if now
     *     OK, dismisses + invokes [onProceed]; otherwise the alert reappears.
     *   - [Delete model] (danger background) — only present when [allowDelete]
     *     is true; invokes [onDelete] then [onCancel] so the renderer can
     *     revert the optimistic switch flip.
     *   - [Cancel] — invokes [onCancel].
     *
     * If availMem is above the floor, [onProceed] is invoked synchronously.
     *
     * The gate value comes from [com.playtranslate.translation.llm.OnDeviceLlmBackend.availMemFloorBytes]
     * — the same threshold [com.playtranslate.translation.translategemma.LlamaTranslator.preflightMemory]
     * enforces at translate time, so the install-time gate and the run-time
     * gate agree on "low memory."
     */
    private fun checkAvailMemAndProceed(
        backend: com.playtranslate.translation.llm.OnDeviceLlmBackend,
        modelDisplayName: String,
        onProceed: () -> Unit,
        allowDelete: Boolean = false,
        onDelete: (() -> Unit)? = null,
        onCancel: () -> Unit = {},
    ) {
        val ctx = context ?: return
        val activity = activity ?: return
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        if (mi.availMem >= backend.availMemFloorBytes) {
            onProceed()
            return
        }

        val needStr = formatGb(backend.availMemFloorBytes)
        val freeStr = formatGb(mi.availMem)
        val builder = OverlayAlert.Builder(ctx)
            .setTitle(getString(R.string.llm_low_memory_title))
            .setMessage(getString(
                R.string.llm_low_memory_message,
                modelDisplayName, needStr, freeStr,
            ))
            .hideIcon()
            .addButton(getString(R.string.llm_low_memory_recheck), ctx.themeColor(R.attr.ptAccent)) {
                // Recurse: re-reads availMem and either proceeds (if memory
                // freed in the meantime) or shows the alert again. The prior
                // OverlayAlert has already dismissed by the time onClick fires
                // (see OverlayAlert.kt:232-235), so there's no overlap.
                checkAvailMemAndProceed(
                    backend, modelDisplayName, onProceed,
                    allowDelete, onDelete, onCancel,
                )
            }
        if (allowDelete && onDelete != null) {
            // Danger background (user explicitly chose "delete it instead").
            // White text for contrast against the saturated red — neither
            // pt_*_text_on_accent nor card maps cleanly here.
            builder.addButton(
                getString(R.string.llm_low_memory_delete),
                ctx.themeColor(R.attr.ptDanger),
                android.graphics.Color.WHITE,
            ) {
                onDelete()
                onCancel()
            }
        }
        builder.addCancelButton(getString(R.string.llm_low_memory_cancel)) { onCancel() }
        builder.showInActivity(activity)
    }

    private fun formatGb(bytes: Long): String {
        val gb = bytes / 1_000_000_000.0
        return if (gb == gb.toLong().toDouble()) "${gb.toLong()} GB"
               else "%.1f GB".format(gb)
    }

    /** Re-enable an already-downloaded TranslateGemma model. Routes through
     *  the availMem gate; on success flips the pref. The [Delete model] branch
     *  of the gate matches the disable-dialog "Delete model" behavior so the
     *  user can free disk space if they decide the model isn't right for the
     *  device after all. */
    private fun enableInstalledTranslateGemma() {
        val ctx = context ?: return
        val backend = com.playtranslate.translation.TranslationBackendRegistry
            .byId("translategemma") as? com.playtranslate.translation.llm.OnDeviceLlmBackend
            ?: return
        checkAvailMemAndProceed(
            backend = backend,
            modelDisplayName = getString(R.string.translategemma_display_name),
            onProceed = { Prefs(ctx).translateGemmaEnabled = true },
            allowDelete = true,
            onDelete = {
                com.playtranslate.translation.translategemma.TranslateGemmaModel.delete(ctx)
                // Drop the loaded model from native memory too — see the
                // disable-dialog delete branch for why (avoids stale mmap
                // after a re-download to the same filename).
                viewLifecycleOwner.lifecycleScope.launch {
                    com.playtranslate.translation.translategemma.LlamaTranslator
                        .getInstance(ctx).unloadModel()
                }
                renderer?.refreshAllBackendStatuses()
            },
            onCancel = { renderer?.refreshTranslategemmaSwitch() },
        )
    }

    /** Re-enable an already-downloaded Qwen model. Mirrors
     *  [enableInstalledTranslateGemma]. */
    private fun enableInstalledQwen() {
        val ctx = context ?: return
        val backend = com.playtranslate.translation.TranslationBackendRegistry
            .byId("qwen") as? com.playtranslate.translation.llm.OnDeviceLlmBackend
            ?: return
        checkAvailMemAndProceed(
            backend = backend,
            modelDisplayName = getString(R.string.qwen_display_name),
            onProceed = { Prefs(ctx).qwenEnabled = true },
            allowDelete = true,
            onDelete = {
                com.playtranslate.translation.qwen.QwenModel.delete(ctx)
                viewLifecycleOwner.lifecycleScope.launch {
                    com.playtranslate.translation.translategemma.LlamaTranslator
                        .getInstance(ctx).unloadModel()
                }
                renderer?.refreshAllBackendStatuses()
            },
            onCancel = { renderer?.refreshQwenSwitch() },
        )
    }

    /** Show the modal download dialog (OverlayProgress).
     *  Drives a [com.playtranslate.translation.llm.OnDeviceLlmDownloader] configured
     *  for TG from the bottom sheet's lifecycle scope — dismissing the sheet
     *  cancels the coroutine but preserves the partial file (resume on next
     *  attempt). The Cancel button explicitly deletes the partial file. */
    private fun showTranslateGemmaDownloadDialog() {
        if (isDownloadInFlight(translategemmaDownloadJob, "TranslateGemma")) return
        val ctx = context ?: return
        val backend = com.playtranslate.translation.TranslationBackendRegistry
            .byId("translategemma") as? com.playtranslate.translation.llm.OnDeviceLlmBackend
            ?: return
        val downloader = com.playtranslate.translation.llm.OnDeviceLlmDownloader(
            context = ctx,
            modelHelper = com.playtranslate.translation.translategemma.TranslateGemmaModel,
            totalMemFloorBytes = backend.totalMemFloorBytes,
        )

        // Pre-download availMem gate — runs first so we don't sink a 2 GB
        // download into a device that can't currently load the model. The
        // pre-download path has nothing to delete yet, so allowDelete=false
        // (Check-again / Cancel only). onCancel is a no-op: the renderer
        // didn't optimistically flip a switch for the download path; tapping
        // the row enters this method directly and the switch is still off.
        checkAvailMemAndProceed(
            backend = backend,
            modelDisplayName = getString(R.string.translategemma_display_name),
            onProceed = { showTranslateGemmaDownloadDialogPostGate(ctx, downloader) },
        )
    }

    /** Post-availMem-gate continuation of [showTranslateGemmaDownloadDialog].
     *  Surfaces the metered-network warning (if any) and kicks off the
     *  download. Split out so the gate's [onProceed] can re-enter this flow
     *  after a successful [Check memory again] tap. */
    private fun showTranslateGemmaDownloadDialogPostGate(
        ctx: Context,
        downloader: com.playtranslate.translation.llm.OnDeviceLlmDownloader,
    ) {
        // Metered-network warning before kicking off the multi-GB download.
        if (downloader.isCurrentNetworkMetered()) {
            val sizeStr = com.playtranslate.translation.translategemma
                .TranslateGemmaModel.humanSize(ctx)
            androidx.appcompat.app.AlertDialog.Builder(ctx)
                .setTitle(R.string.translategemma_metered_warning_title)
                .setMessage(getString(R.string.translategemma_metered_warning_message, sizeStr))
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    runTranslateGemmaDownload(ctx, downloader)
                }
                .setNegativeButton(android.R.string.cancel) { _, _ -> }
                .show()
            return
        }
        runTranslateGemmaDownload(ctx, downloader)
    }

    private fun runTranslateGemmaDownload(
        ctx: Context,
        downloader: com.playtranslate.translation.llm.OnDeviceLlmDownloader,
    ) {
        val activity = activity ?: return
        val sizeStr = com.playtranslate.translation.translategemma
            .TranslateGemmaModel.humanSize(ctx)

        // Reference captured into the cancel callback below; the dialog is
        // assigned right after via the Builder, then mutated as the download
        // progresses.
        var dialog: OverlayProgress? = null
        dialog = OverlayProgress.Builder(ctx)
            .setTitle(getString(R.string.translategemma_display_name))
            .setMessage(getString(R.string.translategemma_status_downloading, "0 B", sizeStr))
            .setProgress(0)
            .setOnCancel {
                translategemmaDownloadJob?.cancel()
                // Explicit cancel deletes the partial file (no resume on next attempt).
                downloader.deletePartial()
                renderer?.refreshAllBackendStatuses()
            }
            .showInActivity(activity)

        translategemmaDownloadJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val outcome = downloader.run { progress ->
                    requireActivity().runOnUiThread {
                        when (progress) {
                            is com.playtranslate.translation.llm
                                .OnDeviceLlmDownloader.Progress.Downloading -> {
                                val recv = com.playtranslate.translation.llm
                                    .humanSize(progress.received)
                                val total = com.playtranslate.translation.llm
                                    .humanSize(progress.total)
                                dialog?.setMessage(getString(
                                    R.string.translategemma_status_downloading,
                                    recv, total,
                                ))
                                if (progress.total > 0) {
                                    dialog?.setProgress(
                                        ((progress.received * 100) / progress.total).toInt()
                                    )
                                }
                            }
                            is com.playtranslate.translation.llm
                                .OnDeviceLlmDownloader.Progress.Verifying -> {
                                dialog?.setMessage(getString(R.string.translategemma_status_verifying))
                                dialog?.setProgress(100)
                            }
                            is com.playtranslate.translation.llm
                                .OnDeviceLlmDownloader.Progress.Extracting -> {
                                // TG ships as a single GGUF (FileSwap commit
                                // strategy), so this branch is never reached
                                // for TranslateGemma — but the `when` needs
                                // to cover every Progress variant since the
                                // sealed interface enumerates them. Mirroring
                                // Verifying's full-progress affordance keeps
                                // the dialog visually consistent.
                                dialog?.setMessage(getString(R.string.model_download_extracting))
                                dialog?.setProgress(100)
                            }
                        }
                    }
                }
                if (!isAdded) return@launch
                requireActivity().runOnUiThread {
                    dialog?.dismiss()
                    when (outcome) {
                        is com.playtranslate.translation.llm
                            .OnDeviceLlmDownloader.Outcome.Success -> {
                            // Flip the pref → SP listener fires → switch + status refresh + reconcile.
                            Prefs(ctx).translateGemmaEnabled = true
                        }
                        is com.playtranslate.translation.llm
                            .OnDeviceLlmDownloader.Outcome.Refused -> {
                            android.widget.Toast.makeText(
                                ctx, outcome.reason, android.widget.Toast.LENGTH_LONG
                            ).show()
                            renderer?.refreshAllBackendStatuses()
                        }
                        is com.playtranslate.translation.llm
                            .OnDeviceLlmDownloader.Outcome.Failed -> {
                            android.widget.Toast.makeText(
                                ctx,
                                getString(R.string.translategemma_download_failed, outcome.reason),
                                android.widget.Toast.LENGTH_LONG,
                            ).show()
                            renderer?.refreshAllBackendStatuses()
                        }
                        is com.playtranslate.translation.llm
                            .OnDeviceLlmDownloader.Outcome.Cancelled -> {
                            // Partial file kept (lifecycle dismiss). Settings will say "Tap to download"
                            // because isInstalled() is false — but next tap resumes from offset.
                            android.widget.Toast.makeText(
                                ctx, R.string.translategemma_download_paused,
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                            renderer?.refreshAllBackendStatuses()
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isAdded) {
                    requireActivity().runOnUiThread {
                        dialog?.dismiss()
                        android.widget.Toast.makeText(
                            ctx,
                            getString(R.string.translategemma_download_failed,
                                e.message ?: e.javaClass.simpleName),
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                        renderer?.refreshAllBackendStatuses()
                    }
                }
            } finally {
                // OverlayProgress lives on activity.window.decorView, not the
                // fragment view — a fragment-only lifecycle cancel (sheet
                // dismissed mid-download) would otherwise leave the scrim
                // stuck. dismiss() is idempotent so this is safe even after
                // the success/outcome branches above already dismissed.
                dialog?.dismiss()
            }
        }
    }


    /** OverlayAlert with three options when the user taps an enabled TG row.
     *  Scrim-tap and Cancel both revert the optimistic switch flip via
     *  [SettingsRenderer.refreshTranslategemmaSwitch]. */
    private fun showTranslateGemmaDisableDialog() {
        val ctx = context ?: return
        val activity = activity ?: return
        val sizeStr = com.playtranslate.translation.translategemma
            .TranslateGemmaModel.humanSize(ctx)
        OverlayAlert.Builder(ctx)
            .setTitle(getString(R.string.translategemma_disable_title))
            .setMessage(getString(R.string.translategemma_disable_message, sizeStr))
            .hideIcon()
            .addButton(getString(R.string.translategemma_disable_keep), ctx.themeColor(R.attr.ptAccent)) {
                // File kept; only the toggle flips. SP listener picks up the change.
                Prefs(ctx).translateGemmaEnabled = false
            }
            .addButton(getString(R.string.translategemma_disable_delete), ctx.themeColor(R.attr.ptDivider), ctx.themeColor(R.attr.ptDanger)) {
                Prefs(ctx).translateGemmaEnabled = false
                com.playtranslate.translation.translategemma
                    .TranslateGemmaModel.delete(ctx)
                // Drop the loaded model from native memory too. Without this,
                // the unlinked file's mmap'd pages remain valid and a subsequent
                // re-download would serve stale weights from the previous mmap
                // because LlamaTranslator.ensureLoaded matches on the path string
                // (which is unchanged after delete + re-download to the same
                // FILENAME). See Codex adversarial-review Finding #1.
                viewLifecycleOwner.lifecycleScope.launch {
                    com.playtranslate.translation.translategemma.LlamaTranslator
                        .getInstance(ctx).unloadModel()
                }
                renderer?.refreshAllBackendStatuses()
            }
            .addCancelButton { renderer?.refreshTranslategemmaSwitch() }
            .showInActivity(activity)
    }

    // ── Qwen flow ───────────────────────────────────────────────────────

    private var qwenDownloadJob: kotlinx.coroutines.Job? = null

    /** Show the modal download dialog for Qwen. Mirrors
     *  [showTranslateGemmaDownloadDialog] but with QwenModel + a 4 GB total-mem
     *  floor (Qwen 1.5B fits comfortably below TG's 6 GB requirement). */
    private fun showQwenDownloadDialog() {
        if (isDownloadInFlight(qwenDownloadJob, "Qwen (legacy)")) return
        val ctx = context ?: return
        val backend = com.playtranslate.translation.TranslationBackendRegistry
            .byId("qwen") as? com.playtranslate.translation.llm.OnDeviceLlmBackend
            ?: return
        val downloader = com.playtranslate.translation.llm.OnDeviceLlmDownloader(
            context = ctx,
            modelHelper = com.playtranslate.translation.qwen.QwenModel,
            totalMemFloorBytes = backend.totalMemFloorBytes,
        )

        // Pre-download availMem gate — mirrors the TG flow. See the comment
        // there for why allowDelete=false and onCancel is empty.
        checkAvailMemAndProceed(
            backend = backend,
            modelDisplayName = getString(R.string.qwen_display_name),
            onProceed = { showQwenDownloadDialogPostGate(ctx, downloader) },
        )
    }

    /** Post-availMem-gate continuation of [showQwenDownloadDialog]. Mirrors
     *  [showTranslateGemmaDownloadDialogPostGate]. */
    private fun showQwenDownloadDialogPostGate(
        ctx: Context,
        downloader: com.playtranslate.translation.llm.OnDeviceLlmDownloader,
    ) {
        if (downloader.isCurrentNetworkMetered()) {
            val sizeStr = com.playtranslate.translation.qwen.QwenModel.humanSize(ctx)
            androidx.appcompat.app.AlertDialog.Builder(ctx)
                .setTitle(R.string.qwen_metered_warning_title)
                .setMessage(getString(R.string.qwen_metered_warning_message, sizeStr))
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    runQwenDownload(ctx, downloader)
                }
                .setNegativeButton(android.R.string.cancel) { _, _ -> }
                .show()
            return
        }
        runQwenDownload(ctx, downloader)
    }

    private fun runQwenDownload(
        ctx: Context,
        downloader: com.playtranslate.translation.llm.OnDeviceLlmDownloader,
    ) {
        val activity = activity ?: return
        val sizeStr = com.playtranslate.translation.qwen.QwenModel.humanSize(ctx)

        var dialog: OverlayProgress? = null
        dialog = OverlayProgress.Builder(ctx)
            .setTitle(getString(R.string.qwen_display_name))
            .setMessage(getString(R.string.qwen_status_downloading, "0 B", sizeStr))
            .setProgress(0)
            .setOnCancel {
                qwenDownloadJob?.cancel()
                downloader.deletePartial()
                renderer?.refreshAllBackendStatuses()
            }
            .showInActivity(activity)

        qwenDownloadJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val outcome = downloader.run { progress ->
                    requireActivity().runOnUiThread {
                        when (progress) {
                            is com.playtranslate.translation.llm
                                .OnDeviceLlmDownloader.Progress.Downloading -> {
                                val recv = com.playtranslate.translation.llm
                                    .humanSize(progress.received)
                                val total = com.playtranslate.translation.llm
                                    .humanSize(progress.total)
                                dialog?.setMessage(getString(
                                    R.string.qwen_status_downloading,
                                    recv, total,
                                ))
                                if (progress.total > 0) {
                                    dialog?.setProgress(
                                        ((progress.received * 100) / progress.total).toInt()
                                    )
                                }
                            }
                            is com.playtranslate.translation.llm
                                .OnDeviceLlmDownloader.Progress.Extracting -> {
                                // Reached for the MNN-backed Qwen (directory-
                                // mode / zip distribution) between SHA verify
                                // and the final directory swap. Legacy Qwen
                                // GGUF (FileSwap commit) never hits this branch.
                                dialog?.setMessage(getString(R.string.model_download_extracting))
                                dialog?.setProgress(100)
                            }
                            is com.playtranslate.translation.llm
                                .OnDeviceLlmDownloader.Progress.Verifying -> {
                                dialog?.setMessage(getString(R.string.qwen_status_verifying))
                                dialog?.setProgress(100)
                            }
                        }
                    }
                }
                if (!isAdded) return@launch
                requireActivity().runOnUiThread {
                    dialog?.dismiss()
                    when (outcome) {
                        is com.playtranslate.translation.llm
                            .OnDeviceLlmDownloader.Outcome.Success -> {
                            Prefs(ctx).qwenEnabled = true
                        }
                        is com.playtranslate.translation.llm
                            .OnDeviceLlmDownloader.Outcome.Refused -> {
                            android.widget.Toast.makeText(
                                ctx, outcome.reason, android.widget.Toast.LENGTH_LONG
                            ).show()
                            renderer?.refreshAllBackendStatuses()
                        }
                        is com.playtranslate.translation.llm
                            .OnDeviceLlmDownloader.Outcome.Failed -> {
                            android.widget.Toast.makeText(
                                ctx,
                                getString(R.string.qwen_download_failed, outcome.reason),
                                android.widget.Toast.LENGTH_LONG,
                            ).show()
                            renderer?.refreshAllBackendStatuses()
                        }
                        is com.playtranslate.translation.llm
                            .OnDeviceLlmDownloader.Outcome.Cancelled -> {
                            android.widget.Toast.makeText(
                                ctx, R.string.qwen_download_paused,
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                            renderer?.refreshAllBackendStatuses()
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isAdded) {
                    requireActivity().runOnUiThread {
                        dialog?.dismiss()
                        android.widget.Toast.makeText(
                            ctx,
                            getString(R.string.qwen_download_failed,
                                e.message ?: e.javaClass.simpleName),
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                        renderer?.refreshAllBackendStatuses()
                    }
                }
            } finally {
                // See translategemma flow above — OverlayProgress sits on
                // activity decor, so a fragment-only lifecycle cancel
                // bypasses the inner dismisses. Idempotent late dismiss
                // catches the !isAdded return and CancellationException
                // paths.
                dialog?.dismiss()
            }
        }
    }

    /** OverlayAlert with three options when the user taps an enabled Qwen row.
     *  Scrim-tap and Cancel both revert the optimistic switch flip via
     *  [SettingsRenderer.refreshQwenSwitch]. */
    private fun showQwenDisableDialog() {
        val ctx = context ?: return
        val activity = activity ?: return
        val sizeStr = com.playtranslate.translation.qwen.QwenModel.humanSize(ctx)
        OverlayAlert.Builder(ctx)
            .setTitle(getString(R.string.qwen_disable_title))
            .setMessage(getString(R.string.qwen_disable_message, sizeStr))
            .hideIcon()
            .addButton(getString(R.string.qwen_disable_keep), ctx.themeColor(R.attr.ptAccent)) {
                Prefs(ctx).qwenEnabled = false
            }
            .addButton(getString(R.string.qwen_disable_delete), ctx.themeColor(R.attr.ptDivider), ctx.themeColor(R.attr.ptDanger)) {
                Prefs(ctx).qwenEnabled = false
                com.playtranslate.translation.qwen.QwenModel.delete(ctx)
                // See translategemma_disable_delete branch above for why we
                // also unload the native model on file delete.
                viewLifecycleOwner.lifecycleScope.launch {
                    com.playtranslate.translation.translategemma.LlamaTranslator
                        .getInstance(ctx).unloadModel()
                }
                renderer?.refreshAllBackendStatuses()
            }
            .addCancelButton { renderer?.refreshQwenSwitch() }
            .showInActivity(activity)
    }

    /**
     * Smooth-scrolls the named section header into view. Safe to call on an
     * already-shown sheet (e.g. the dual-screen default sheet that's already
     * in the fragment manager when the cold-launch alert fires) — uses the
     * same `post` + height-poll pattern as the theme-change scroll restore
     * so the target's `top` coordinate is meaningful when we call scrollTo.
     *
     * Called from two paths:
     *  - onViewCreated, when a freshly-created sheet was constructed with
     *    `newInstance(anchor = …)` — the anchor is read from arguments.
     *  - [com.playtranslate.MainActivity.openSettingsAtAnchor], when the
     *    sheet is already shown and we want to navigate within it instead
     *    of duplicating the fragment.
     */
    fun scrollToAnchor(anchor: SettingsAnchor) {
        val root = view ?: return
        val sv = root.findViewById<NestedScrollView>(R.id.settingsScrollView) ?: return
        val target = root.findViewById<android.view.View>(anchor.headerId) ?: return
        fun tryScroll() {
            if (sv.height > 0 && target.height > 0) {
                // Subtract a small top margin so the header doesn't sit
                // flush with the scroll view's top edge.
                val dp = root.resources.displayMetrics.density
                val topMargin = (16 * dp).toInt()
                sv.smoothScrollTo(0, (target.top - topMargin).coerceAtLeast(0))
            } else {
                sv.postDelayed(::tryScroll, 16)
            }
        }
        sv.post { tryScroll() }
    }

    // ── Qwen (MNN) flow ──────────────────────────────────────────────────

    private var qwenMnnDownloadJob: kotlinx.coroutines.Job? = null

    /** Re-enable an already-extracted MNN-Qwen model. Mirrors
     *  [enableInstalledQwen] but routes through MnnTranslator for the unload-
     *  on-delete branch (so the right engine's native state is freed). */
    private fun enableInstalledQwenMnn() {
        val ctx = context ?: return
        val backend = com.playtranslate.translation.TranslationBackendRegistry
            .byId("qwen_mnn") as? com.playtranslate.translation.llm.OnDeviceLlmBackend
            ?: return
        checkAvailMemAndProceed(
            backend = backend,
            modelDisplayName = getString(R.string.qwen_mnn_display_name),
            onProceed = { Prefs(ctx).qwenMnnEnabled = true },
            allowDelete = true,
            onDelete = {
                com.playtranslate.translation.qwen.QwenMnnModel.delete(ctx)
                viewLifecycleOwner.lifecycleScope.launch {
                    com.playtranslate.translation.mnn.MnnTranslator
                        .getInstance(ctx).unloadModel()
                }
                renderer?.refreshAllBackendStatuses()
            },
            onCancel = { renderer?.refreshQwenMnnSwitch() },
        )
    }

    /** Show the modal download dialog for the MNN-Qwen zip. Mirrors
     *  [showQwenDownloadDialog]; the downloader picks ZipExtract automatically
     *  via [com.playtranslate.translation.qwen.QwenMnnModel.isDirectoryMode]. */
    private fun showQwenMnnDownloadDialog() {
        if (isDownloadInFlight(qwenMnnDownloadJob, "Qwen MNN")) return
        val ctx = context ?: return
        val backend = com.playtranslate.translation.TranslationBackendRegistry
            .byId("qwen_mnn") as? com.playtranslate.translation.llm.OnDeviceLlmBackend
            ?: return
        val downloader = com.playtranslate.translation.llm.OnDeviceLlmDownloader(
            context = ctx,
            modelHelper = com.playtranslate.translation.qwen.QwenMnnModel,
            totalMemFloorBytes = backend.totalMemFloorBytes,
        )
        checkAvailMemAndProceed(
            backend = backend,
            modelDisplayName = getString(R.string.qwen_mnn_display_name),
            onProceed = { showQwenMnnDownloadDialogPostGate(ctx, downloader) },
        )
    }

    private fun showQwenMnnDownloadDialogPostGate(
        ctx: Context,
        downloader: com.playtranslate.translation.llm.OnDeviceLlmDownloader,
    ) {
        if (downloader.isCurrentNetworkMetered()) {
            val sizeStr = com.playtranslate.translation.qwen.QwenMnnModel.humanSize(ctx)
            androidx.appcompat.app.AlertDialog.Builder(ctx)
                .setTitle(R.string.qwen_mnn_metered_warning_title)
                .setMessage(getString(R.string.qwen_mnn_metered_warning_message, sizeStr))
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    runQwenMnnDownload(ctx, downloader)
                }
                .setNegativeButton(android.R.string.cancel) { _, _ -> }
                .show()
            return
        }
        runQwenMnnDownload(ctx, downloader)
    }

    private fun runQwenMnnDownload(
        ctx: Context,
        downloader: com.playtranslate.translation.llm.OnDeviceLlmDownloader,
    ) {
        val activity = activity ?: return
        val sizeStr = com.playtranslate.translation.qwen.QwenMnnModel.humanSize(ctx)

        var dialog: OverlayProgress? = null
        dialog = OverlayProgress.Builder(ctx)
            .setTitle(getString(R.string.qwen_mnn_display_name))
            .setMessage(getString(R.string.qwen_mnn_status_downloading, "0 B", sizeStr))
            .setProgress(0)
            .setOnCancel {
                qwenMnnDownloadJob?.cancel()
                downloader.deletePartial()
                renderer?.refreshAllBackendStatuses()
            }
            .showInActivity(activity)

        qwenMnnDownloadJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val outcome = downloader.run { progress ->
                    requireActivity().runOnUiThread {
                        when (progress) {
                            is com.playtranslate.translation.llm
                                .OnDeviceLlmDownloader.Progress.Downloading -> {
                                val recv = com.playtranslate.translation.llm
                                    .humanSize(progress.received)
                                val total = com.playtranslate.translation.llm
                                    .humanSize(progress.total)
                                dialog?.setMessage(getString(
                                    R.string.qwen_mnn_status_downloading,
                                    recv, total,
                                ))
                                if (progress.total > 0) {
                                    dialog?.setProgress(
                                        ((progress.received * 100) / progress.total).toInt()
                                    )
                                }
                            }
                            is com.playtranslate.translation.llm
                                .OnDeviceLlmDownloader.Progress.Verifying -> {
                                dialog?.setMessage(getString(R.string.qwen_mnn_status_verifying))
                                dialog?.setProgress(100)
                            }
                            is com.playtranslate.translation.llm
                                .OnDeviceLlmDownloader.Progress.Extracting -> {
                                // The directory-mode commit path lives here.
                                // The MNN Qwen zip extracts to ~30 separate
                                // files (.mnn / .mnn.weight / tokenizer.txt /
                                // config.json / ...) over a few seconds on
                                // Thor; indeterminate spinner is fine.
                                dialog?.setMessage(getString(R.string.model_download_extracting))
                                dialog?.setProgress(100)
                            }
                        }
                    }
                }
                if (!isAdded) return@launch
                requireActivity().runOnUiThread {
                    dialog?.dismiss()
                    when (outcome) {
                        is com.playtranslate.translation.llm
                            .OnDeviceLlmDownloader.Outcome.Success -> {
                            Prefs(ctx).qwenMnnEnabled = true
                            renderer?.refreshAllBackendStatuses()
                        }
                        is com.playtranslate.translation.llm
                            .OnDeviceLlmDownloader.Outcome.Refused -> {
                            android.widget.Toast.makeText(
                                ctx,
                                getString(R.string.qwen_mnn_download_failed, outcome.reason),
                                android.widget.Toast.LENGTH_LONG,
                            ).show()
                            renderer?.refreshAllBackendStatuses()
                        }
                        is com.playtranslate.translation.llm
                            .OnDeviceLlmDownloader.Outcome.Failed -> {
                            android.widget.Toast.makeText(
                                ctx,
                                getString(R.string.qwen_mnn_download_failed, outcome.reason),
                                android.widget.Toast.LENGTH_LONG,
                            ).show()
                            renderer?.refreshAllBackendStatuses()
                        }
                        is com.playtranslate.translation.llm
                            .OnDeviceLlmDownloader.Outcome.Cancelled -> {
                            // User-cancelled — partial may remain for resume,
                            // unless the Cancel button's onCancel deleted it.
                            renderer?.refreshAllBackendStatuses()
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Lifecycle cancellation — leave the partial in place.
            } finally {
                qwenMnnDownloadJob = null
            }
        }
    }

    /** Show the 3-button disable dialog for the MNN-Qwen tier. Mirrors
     *  [showQwenDisableDialog] but deletes via [QwenMnnModel.delete] and
     *  unloads through [MnnTranslator]. Refreshes the legacy row visibility
     *  after a successful delete in case both tiers were installed. */
    private fun showQwenMnnDisableDialog() {
        val ctx = context ?: return
        val activity = activity ?: return
        val sizeStr = com.playtranslate.translation.qwen.QwenMnnModel.humanSize(ctx)
        OverlayAlert.Builder(ctx)
            .setTitle(getString(R.string.qwen_mnn_disable_title))
            .setMessage(getString(R.string.qwen_mnn_disable_message, sizeStr))
            .hideIcon()
            .addButton(getString(R.string.qwen_disable_keep), ctx.themeColor(R.attr.ptAccent)) {
                Prefs(ctx).qwenMnnEnabled = false
            }
            .addButton(getString(R.string.qwen_disable_delete), ctx.themeColor(R.attr.ptDivider), ctx.themeColor(R.attr.ptDanger)) {
                Prefs(ctx).qwenMnnEnabled = false
                com.playtranslate.translation.qwen.QwenMnnModel.delete(ctx)
                viewLifecycleOwner.lifecycleScope.launch {
                    com.playtranslate.translation.mnn.MnnTranslator
                        .getInstance(ctx).unloadModel()
                }
                renderer?.refreshAllBackendStatuses()
            }
            .addCancelButton { renderer?.refreshQwenMnnSwitch() }
            .showInActivity(activity)
    }

    // ── Accessibility-required alert ─────────────────────────────────────

    /** OverlayAlert shown when the user attempts an accessibility-gated
     *  Settings action — switching capture displays or setting a hotkey —
     *  while the service is disabled. The accent button opens Accessibility
     *  Settings; the cancel button just dismisses. Icon-less, matching the
     *  TG / Qwen confirmation dialogs. */
    private fun showAccessibilityRequiredAlert(requirement: AccessibilityRequirement) {
        val ctx = context ?: return
        val activity = activity ?: return
        val message = when (requirement) {
            AccessibilityRequirement.MULTI_DISPLAY ->
                getString(R.string.a11y_required_displays_message)
            AccessibilityRequirement.HOTKEY ->
                getString(R.string.a11y_required_hotkey_message)
            AccessibilityRequirement.ENHANCED_AUTO_TRANSLATE ->
                getString(R.string.a11y_required_enhanced_message)
        }
        OverlayAlert.Builder(ctx)
            .hideIcon()
            .setTitle(getString(R.string.a11y_required_alert_title))
            .setMessage(message)
            .addButton(
                getString(R.string.btn_open_a11y_settings),
                ctx.themeColor(R.attr.ptAccent),
                ctx.themeColor(R.attr.ptAccentOn),
            ) {
                startActivity(android.content.Intent(
                    android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .addCancelButton(getString(android.R.string.cancel))
            .showInActivity(activity)
    }

    // ── MediaProjection controls ─────────────────────────────────────────

    /** MediaProjection-backend "turn on game screen controls": prompt for the
     *  screen-record consent and, on grant, reconcile the floating icons.
     *  Runs on the Activity scope so the consent round-trip survives a
     *  Settings dismiss; the switch refresh is null-safe for that case. */
    private fun requestMediaProjectionControls() {
        val activity = activity as? androidx.appcompat.app.AppCompatActivity ?: return
        if (!android.provider.Settings.canDrawOverlays(activity)) {
            // The MediaProjection floating controls are TYPE_APPLICATION_OVERLAY
            // windows — without "Display over other apps" they silently fail to
            // add. Route the user to grant it; they re-tap Start afterwards.
            showOverlayPermissionAlert(activity)
            return
        }
        activity.lifecycleScope.launch {
            val controller =
                com.playtranslate.CaptureService.instance?.mediaProjectionController
            val granted = controller?.ensureConsent() ?: false
            if (granted) {
                // showOverlayIcon doesn't apply on the MediaProjection
                // backend — the icon shows whenever capture is active (see
                // OverlayUiController.reconcileFloatingIcons), so there's
                // nothing to flip here.
                com.playtranslate.capture.CaptureBackendResolver
                    .activeOverlayUi?.reconcileFloatingIcons()
                com.playtranslate.PlayTranslateTileService.TileSync.refresh(activity)
            }
            renderer?.refreshOverlayIconState()
        }
    }

    /** Routes the user to grant "Display over other apps" — required for the
     *  MediaProjection floating controls (TYPE_APPLICATION_OVERLAY windows). */
    private fun showOverlayPermissionAlert(activity: androidx.appcompat.app.AppCompatActivity) {
        OverlayAlert.Builder(activity)
            .hideIcon()
            .setTitle(getString(R.string.mp_overlay_permission_title))
            .setMessage(getString(R.string.mp_overlay_permission_message))
            .addButton(
                getString(R.string.mp_overlay_permission_button),
                activity.themeColor(R.attr.ptAccent),
                activity.themeColor(R.attr.ptAccentOn),
            ) {
                activity.startActivity(activity.overlayPermissionSettingsIntent())
            }
            .addCancelButton(getString(android.R.string.cancel))
            .showInActivity(activity)
    }

    // ── Companion ───────────────────────────────────────────────────────

    companion object {
        const val TAG = "SettingsBottomSheet"
        private const val ARG_HIDE_DISMISS = "hide_dismiss"

        // (TG and Qwen total-mem floors used to live here as TG_TOTAL_MEM_FLOOR_BYTES
        // and QWEN_TOTAL_MEM_FLOOR_BYTES, but they're now properties on the backend
        // class itself — see OnDeviceLlmBackend.totalMemFloorBytes — so the UI's
        // hardware-gate logic and the downloader's preflight read the same source.)

        private const val ARG_ANCHOR = "anchor"

        fun newInstance(
            hideDismiss: Boolean = false,
            anchor: SettingsAnchor? = null,
        ) = SettingsBottomSheet().apply {
            val args = Bundle()
            if (hideDismiss) args.putBoolean(ARG_HIDE_DISMISS, true)
            if (anchor != null) args.putString(ARG_ANCHOR, anchor.name)
            if (!args.isEmpty) arguments = args
        }
    }
}

/**
 * Named scroll target for [SettingsBottomSheet.newInstance]. Add new entries
 * here when a deep-link needs to land the user at a specific section of the
 * Settings sheet; the sheet scrolls the corresponding header view into view
 * after layout. See [SettingsBottomSheet.maybeScrollToAnchor].
 */
enum class SettingsAnchor(val headerId: Int) {
    /** "Offline translations" section header. Used by the cold-launch
     *  "faster Qwen available" OverlayAlert in MainActivity. */
    OfflineTranslation(com.playtranslate.R.id.headerOfflineTranslations),
}
