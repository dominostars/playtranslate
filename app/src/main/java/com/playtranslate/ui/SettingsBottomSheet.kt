package com.playtranslate.ui

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Bitmap
import kotlinx.coroutines.launch
import android.text.Editable
import android.text.TextWatcher
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.view.Gravity
import android.view.LayoutInflater
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import com.google.android.material.materialswitch.MaterialSwitch
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import android.widget.Toast
import com.playtranslate.AnkiManager
import com.playtranslate.OverlayMode
import com.playtranslate.CaptureService
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.diagnostics.LogExporter
import com.playtranslate.fullScreenDialogTheme
import com.playtranslate.language.HintTextKind
import com.playtranslate.language.SourceLangId
import com.playtranslate.language.SourceLanguageProfiles
import com.playtranslate.themeColor
import android.content.Intent
import android.provider.Settings
import com.playtranslate.PlayTranslateAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsBottomSheet : DialogFragment() {

    /** Called when the selected capture display changes. */
    var onDisplayChanged: (() -> Unit)? = null
    /** Called when the user picks a different source language via the picker. */
    var onSourceLangChanged: (() -> Unit)? = null
    private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    /** Called when the debug force-single-screen toggle changes. */
    var onScreenModeChanged: (() -> Unit)? = null
    /** Called when the user taps the close button (for inline mode). */
    var onClose: (() -> Unit)? = null
    /** Called when the user selects a new theme. Passes the scroll position to restore. */
    var onThemeChanged: ((scrollY: Int) -> Unit)? = null
    /** Called when the user toggles the overlay mode (translation ↔ furigana). */
    var onOverlayModeChanged: (() -> Unit)? = null

    private var deckEntries: List<Map.Entry<Long, String>> = emptyList()
    private var displayList: List<android.view.Display> = emptyList()
    private var displayListener: DisplayManager.DisplayListener? = null
    private var selectedDisplayIdx = 0
    private val displayThumbnails = HashMap<Int, Bitmap?>()
    private var currentView: View? = null
    private var ivIconPreview: android.widget.ImageView? = null

    private lateinit var llDisplayOptions: LinearLayout
    private lateinit var llLanguageList: LinearLayout
    private lateinit var spinnerAnkiDeck: android.widget.Spinner
    private lateinit var settingsScrollView: android.widget.ScrollView

    private val requestAnkiPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) loadAnkiDecks()
    }

    override fun getTheme(): Int = fullScreenDialogTheme(requireContext())

    override fun onDestroyView() {
        ivIconPreview?.setImageBitmap(null)
        ivIconPreview = null
        displayThumbnails.values.forEach { it?.recycle() }
        displayThumbnails.clear()
        displayListener?.let {
            val dm = context?.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            dm?.unregisterDisplayListener(it)
        }
        displayListener = null
        super.onDestroyView()
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

    /** Re-inflate the content in place (used for theme changes in dialog mode). */
    fun reinflateContent() {
        val old = currentView ?: return
        val parent = old.parent as? ViewGroup ?: return
        val index = parent.indexOfChild(old)
        parent.removeView(old)
        val newView = LayoutInflater.from(requireActivity())
            .inflate(R.layout.dialog_settings, parent, false)
        parent.addView(newView, index)
        currentView = newView
        setupViews(newView)
        // Update the dialog window's system bar colors to match the new theme
        val ctx = requireActivity()
        val bgColor = ctx.themeColor(R.attr.colorBgDark)
        dialog?.window?.apply {
            statusBarColor = bgColor
            navigationBarColor = bgColor
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(
                ctx.themeColor(R.attr.colorBgSurface)))
        }
    }

    private fun setupViews(view: View) {
        val hideDismiss = arguments?.getBoolean(ARG_HIDE_DISMISS, false) ?: false
        val isDialog = showsDialog

        // Show toolbar only in dialog mode
        if (isDialog) {
            view.findViewById<View>(R.id.settingsToolbar).visibility = View.VISIBLE
            view.findViewById<View>(R.id.settingsToolbarDivider).visibility = View.VISIBLE
            if (!Prefs.hasMultipleDisplays(requireContext())) {
                view.findViewById<TextView>(R.id.tvSettingsTitle).text = getString(R.string.app_name)
            }
            val closeBtn = view.findViewById<View>(R.id.btnCloseSettings)
            if (hideDismiss) {
                closeBtn.visibility = View.GONE
                dialog?.setOnKeyListener { _, keyCode, event ->
                    if (keyCode == android.view.KeyEvent.KEYCODE_BACK && event.action == android.view.KeyEvent.ACTION_UP) {
                        activity?.finish()
                        true
                    } else false
                }
            } else {
                closeBtn.setOnClickListener { dismiss() }
            }
        }

        val prefs           = Prefs(requireContext())
        llDisplayOptions    = view.findViewById(R.id.llDisplayOptions)
        llLanguageList      = view.findViewById(R.id.llLanguageList)
        settingsScrollView  = view.findViewById(R.id.settingsScrollView)
        val etDeeplKey      = view.findViewById<EditText>(R.id.etDeeplKey)
        spinnerAnkiDeck     = view.findViewById(R.id.spinnerAnkiDeck)
        val llThemePicker   = view.findViewById<LinearLayout>(R.id.llThemePicker)

        // Restore scroll position after theme recreate.
        // Polls until the scroll view has been measured (container may still
        // be sizing when the fragment is first created inline). The saved
        // value is only cleared after a successful scroll — Android may
        // restore a stale fragment that reads it first.
        val savedScroll = prefs.settingsScrollY
        if (savedScroll > 0) {
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

        // ── Overlay icon toggle ──────────────────────────────────────────
        val switchOverlayIcon = view.findViewById<MaterialSwitch>(R.id.switchOverlayIcon)
        val tvOverlayIconTitle = view.findViewById<TextView>(R.id.tvOverlayIconTitle)
        val tvOverlayIconHint = view.findViewById<TextView>(R.id.tvOverlayIconHint)
        // Draw half-circle icon preview matching the on-screen appearance
        ivIconPreview = view.findViewById(R.id.ivFloatingIconPreview)
        ivIconPreview?.setImageBitmap(createFloatingIconPreview(prefs.compactOverlayIcon))

        val isSingle = !Prefs.hasMultipleDisplays(requireContext())
        tvOverlayIconTitle.setText(
            if (isSingle) R.string.settings_show_overlay_icon_single
            else R.string.settings_show_overlay_icon
        )
        tvOverlayIconHint.setText(
            if (isSingle) R.string.settings_overlay_icon_hint_single
            else R.string.settings_overlay_icon_hint_dual
        )
        // Make subtext same color as title (both modes)
        tvOverlayIconHint.setTextColor(requireContext().themeColor(R.attr.colorTextPrimary))

        val settingsBelowIcon = view.findViewById<View>(R.id.settingsBelowIcon)
        fun updateIconRowStyle(isOn: Boolean) {
            if (isSingle && !isOn) {
                settingsBelowIcon.alpha = 0.4f
            } else {
                settingsBelowIcon.alpha = 1f
            }
        }

        switchOverlayIcon.isChecked = prefs.showOverlayIcon && PlayTranslateAccessibilityService.isEnabled
        updateIconRowStyle(switchOverlayIcon.isChecked)
        switchOverlayIcon.setOnCheckedChangeListener { _, checked ->
            if (checked && !PlayTranslateAccessibilityService.isEnabled) {
                switchOverlayIcon.isChecked = false
                updateIconRowStyle(false)
                androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle(R.string.overlay_icon_a11y_required_title)
                    .setMessage(R.string.overlay_icon_a11y_required_message)
                    .setPositiveButton(R.string.btn_open_a11y_settings) { _, _ ->
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                return@setOnCheckedChangeListener
            }
            prefs.showOverlayIcon = checked
            updateIconRowStyle(checked)
            PlayTranslateAccessibilityService.instance?.ensureFloatingIcon()
        }
        view.findViewById<View>(R.id.rowOverlayIcon).setOnClickListener {
            switchOverlayIcon.toggle()
        }

        // ── Compact icon toggle ───────────────────────────────────────────
        val switchCompactIcon = view.findViewById<MaterialSwitch>(R.id.switchCompactIcon)
        switchCompactIcon.isChecked = prefs.compactOverlayIcon
        switchCompactIcon.setOnCheckedChangeListener { _, checked ->
            prefs.compactOverlayIcon = checked
            ivIconPreview?.setImageBitmap(createFloatingIconPreview(checked))
            // Force recreate the icon to apply compact mode
            val a11y = PlayTranslateAccessibilityService.instance
            a11y?.hideFloatingIcon("settings_compact_recreate")
            a11y?.ensureFloatingIcon()
        }
        view.findViewById<View>(R.id.rowCompactIcon).setOnClickListener {
            switchCompactIcon.toggle()
        }

        // ── DeepL key (auto-save on text change) ─────────────────────────
        etDeeplKey.setText(prefs.deeplApiKey)
        etDeeplKey.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                prefs.deeplApiKey = s?.toString()?.trim() ?: ""
            }
        })

        addLinkRow(view.findViewById(R.id.llDeeplLink),
            "Get free DeepL API key",
            "Adding a DeepL API key potentially improves online translations. The free plan requires a credit card and includes 500,000 characters per month.",
            "https://www.deepl.com/en/pro#developer")

        // ── Display selector ─────────────────────────────────────────────

        val displayManager = requireContext().getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        displayList = displayManager.displays.toList()
        lastDisplayCount = displayList.size
        selectedDisplayIdx = displayList.indexOfFirst { it.displayId == prefs.captureDisplayId }
            .takeIf { it >= 0 } ?: 0

        val llCaptureDisplaySection = view.findViewById<View>(R.id.llCaptureDisplaySection)
        llCaptureDisplaySection.visibility = if (displayList.size <= 1) View.GONE else View.VISIBLE

        buildLanguageRow()
        buildDisplayRows(prefs)

        // Reload settings when displays change (unregister old listener first)
        displayListener?.let { displayManager.unregisterDisplayListener(it) }
        displayListener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) { reinflateIfDisplayCountChanged(displayManager) }
            override fun onDisplayRemoved(displayId: Int) { reinflateIfDisplayCountChanged(displayManager) }
            override fun onDisplayChanged(displayId: Int) {}
        }
        displayManager.registerDisplayListener(displayListener, null)

        val myDisplayId = requireActivity().display?.displayId ?: android.view.Display.DEFAULT_DISPLAY

        displayList.forEach { display ->
            val mgr = com.playtranslate.PlayTranslateAccessibilityService.instance?.screenshotManager
            if (mgr != null) {
                kotlinx.coroutines.MainScope().launch {
                    val bitmap = mgr.requestClean(display.displayId)
                    if (bitmap != null) {
                        displayThumbnails[display.displayId] = scaleThumbnail(bitmap)
                        view?.post { if (isAdded) buildDisplayRows(prefs) }
                    } else if (display.displayId == myDisplayId) {
                        captureActivityWindow { thumb ->
                            displayThumbnails[display.displayId] = thumb
                            if (isAdded) buildDisplayRows(prefs)
                        }
                    }
                }
            } else if (display.displayId == myDisplayId) {
                captureActivityWindow { thumb ->
                    displayThumbnails[display.displayId] = thumb
                    if (isAdded) buildDisplayRows(prefs)
                }
            }
        }

        // ── Anki section ─────────────────────────────────────────────────
        val llAnkiGetApp = view.findViewById<LinearLayout>(R.id.llAnkiGetApp)
        addLinkRow(llAnkiGetApp, "Get AnkiDroid free on Google Play",
            getString(R.string.anki_section_description, getString(R.string.app_name)),
            getString(R.string.anki_play_store_url))
        refreshAnkiSection()

        // ── Overlay mode toggle ─────────────────────────────────────────
        val hintKind = SourceLanguageProfiles[prefs.sourceLangId].hintTextKind
        val hasHintText = hintKind != HintTextKind.NONE
        val overlayModeSection = view.findViewById<View>(R.id.overlayModeSection)
        val toggleAutoMode = view.findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.toggleAutoMode)

        if (hasHintText) {
            overlayModeSection.visibility = View.VISIBLE
            val hintLabel = when (hintKind) {
                HintTextKind.PINYIN -> "Pinyin"
                else -> "Furigana"
            }
            view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnModeFurigana).text = hintLabel

            toggleAutoMode.check(when (prefs.overlayMode) {
                OverlayMode.FURIGANA -> R.id.btnModeFurigana
                else -> R.id.btnModeTranslate
            })

            toggleAutoMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
                if (!isChecked) return@addOnButtonCheckedListener
                prefs.overlayMode = when (checkedId) {
                    R.id.btnModeFurigana -> OverlayMode.FURIGANA
                    else -> OverlayMode.TRANSLATION
                }
                if (CaptureService.instance?.isLive == true) {
                    CaptureService.instance?.stopLive()
                }
                onOverlayModeChanged?.invoke()
            }
        } else {
            overlayModeSection.visibility = View.GONE
            if (prefs.overlayMode == OverlayMode.FURIGANA) {
                prefs.overlayMode = OverlayMode.TRANSLATION
                onOverlayModeChanged?.invoke()
            }
        }

        // ── Hide game screen overlays toggle (multi-screen only) ─────────
        val rowHideOverlays = view.findViewById<View>(R.id.rowHideOverlays)
        val switchHideOverlays = view.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switchHideOverlays)
        if (!Prefs.isSingleScreen(requireContext())) {
            rowHideOverlays.visibility = View.VISIBLE
            switchHideOverlays.isChecked = prefs.hideGameOverlays
            switchHideOverlays.setOnCheckedChangeListener { _, checked ->
                prefs.hideGameOverlays = checked
                if (CaptureService.instance?.isLive == true) {
                    CaptureService.instance?.stopLive()
                }
            }
            rowHideOverlays.setOnClickListener { switchHideOverlays.toggle() }
        }

        // ── Hotkey rows ─────────────────────────────────────────────────────
        setupHotkeyRow(view,
            R.id.rowHotkeyTranslation, R.id.switchHotkeyTranslation, R.id.tvHotkeyTranslationHint,
            { prefs.hotkeyTranslation }, { prefs.hotkeyTranslation = it },
            "Show Translations")
        val rowHotkeyFurigana = view.findViewById<View>(R.id.rowHotkeyFurigana)
        if (hasHintText) {
            rowHotkeyFurigana.visibility = View.VISIBLE
            val hintLabel = when (hintKind) {
                HintTextKind.PINYIN -> "Pinyin"
                else -> "Furigana"
            }
            view.findViewById<TextView>(R.id.tvHotkeyFuriganaLabel).text =
                "Hotkey: hold to show $hintLabel"
            setupHotkeyRow(view,
                R.id.rowHotkeyFurigana, R.id.switchHotkeyFurigana, R.id.tvHotkeyFuriganaHint,
                { prefs.hotkeyFurigana }, { prefs.hotkeyFurigana = it },
                "Show $hintLabel")
        } else {
            rowHotkeyFurigana.visibility = View.GONE
        }

        // ── Capture interval (auto-save on text change) ───────────────────
        val minSec = Prefs.MIN_CAPTURE_INTERVAL_SEC
        val minLabel = if (minSec == minSec.toLong().toFloat()) "${minSec.toLong()}" else "%.1f".format(minSec)
        view.findViewById<TextView>(R.id.tvCaptureIntervalHint).text =
            "How often the game screen is captured during auto translation. Minimum $minLabel seconds."
        val etCaptureInterval = view.findViewById<EditText>(R.id.etCaptureInterval)
        etCaptureInterval.setText(prefs.captureIntervalSec.let {
            if (it == it.toLong().toFloat()) it.toLong().toString() else "%.1f".format(it)
        })
        etCaptureInterval.inputType = android.text.InputType.TYPE_CLASS_NUMBER or
            android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        etCaptureInterval.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val v = s?.toString()?.toFloatOrNull() ?: return
                prefs.captureIntervalSec = v
            }
        })
        etCaptureInterval.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                etCaptureInterval.clearFocus()
                val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                    as android.view.inputmethod.InputMethodManager
                imm.hideSoftInputFromWindow(etCaptureInterval.windowToken, 0)
                true
            } else false
        }
        view.findViewById<View>(R.id.rowCaptureInterval).setOnClickListener {
            etCaptureInterval.requestFocus()
            val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(etCaptureInterval, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }


        // ── Theme picker ──────────────────────────────────────────────────
        buildThemePicker(llThemePicker, prefs)

        // ── Support links ───────────────────────────────────────────────
        val llSupportLinks = view.findViewById<LinearLayout>(R.id.llSupportLinks)
        val appName = getString(R.string.app_name)
        addLinkRow(llSupportLinks, "Join Discord",
            "Feedback, bugs, requests, updates!",
            "https://go.playtranslate.com/discord")
        addLinkRow(llSupportLinks, "Support Me",
            getString(R.string.support_donate_subtitle, appName),
            "https://go.playtranslate.com/donate")

        // ── Version ─────────────────────────────────────────────────────
        val versionName = com.playtranslate.BuildConfig.VERSION_NAME
        llSupportLinks.addView(TextView(requireContext()).apply {
            text = "$appName v$versionName"
            setTextColor(requireContext().themeColor(R.attr.colorTextHint))
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, (12 * resources.displayMetrics.density).toInt(), 0, 0)
        })

        // ── Diagnostics ─────────────────────────────────────────────────
        val btnExportLogs = view.findViewById<Button>(R.id.btnExportLogs)
        val rowExportLogs = view.findViewById<View>(R.id.rowExportLogs)
        val exportClick = View.OnClickListener {
            val activity = activity ?: return@OnClickListener
            btnExportLogs.isEnabled = false
            lifecycleScope.launch {
                val files = withContext(Dispatchers.IO) {
                    runCatching {
                        val logFile = LogExporter.exportLogcat(activity)
                        listOf(logFile) + LogExporter.getCrashFiles(activity)
                    }
                }
                btnExportLogs.isEnabled = true
                files.fold(
                    onSuccess = { LogExporter.shareFiles(activity, it, "PlayTranslate logs") },
                    onFailure = {
                        Toast.makeText(
                            activity,
                            "Failed to export logs: ${it.javaClass.simpleName}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                )
            }
        }
        btnExportLogs.setOnClickListener(exportClick)
        rowExportLogs.setOnClickListener(exportClick)

        // ── Debug section (debug builds only) ────────────────────────────
        val llDebugSection = view.findViewById<LinearLayout>(R.id.llDebugSection)
        if (com.playtranslate.BuildConfig.DEBUG) {
            llDebugSection.visibility = View.VISIBLE
            val switchForceSingleScreen = view.findViewById<MaterialSwitch>(R.id.switchForceSingleScreen)
            switchForceSingleScreen.isChecked = prefs.debugForceSingleScreen
            switchForceSingleScreen.setOnCheckedChangeListener { _, checked ->
                prefs.debugForceSingleScreen = checked
                onScreenModeChanged?.invoke()
            }
            view.findViewById<View>(R.id.rowForceSingleScreen).setOnClickListener {
                switchForceSingleScreen.toggle()
            }
            val switchShowOcrBoxes = view.findViewById<MaterialSwitch>(R.id.switchShowOcrBoxes)
            switchShowOcrBoxes.isChecked = prefs.debugShowOcrBoxes
            switchShowOcrBoxes.setOnCheckedChangeListener { _, checked ->
                prefs.debugShowOcrBoxes = checked
                val a11y = PlayTranslateAccessibilityService.instance
                if (checked) {
                    a11y?.startDebugOcrLoop()
                } else {
                    a11y?.stopDebugOcrLoop()
                }
            }
            view.findViewById<View>(R.id.rowShowOcrBoxes).setOnClickListener {
                switchShowOcrBoxes.toggle()
            }
            val switchDetectionLog = view.findViewById<MaterialSwitch>(R.id.switchDetectionLog)
            switchDetectionLog.isChecked = prefs.debugShowDetectionLog
            switchDetectionLog.setOnCheckedChangeListener { _, checked ->
                prefs.debugShowDetectionLog = checked
            }
            view.findViewById<View>(R.id.rowDetectionLog).setOnClickListener {
                switchDetectionLog.toggle()
            }
            val btnForceCrash = view.findViewById<Button>(R.id.btnForceCrash)
            val crashClick = View.OnClickListener {
                throw RuntimeException("Forced crash from Settings → Debug → Force crash")
            }
            btnForceCrash.setOnClickListener(crashClick)
            view.findViewById<View>(R.id.rowForceCrash).setOnClickListener(crashClick)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshAnkiSection()
        refreshOverlayIconSwitch()
        refreshAutoModeToggle()


        val ctx = context ?: return
        val sp = ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE)
        prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "show_overlay_icon") refreshOverlayIconSwitch()
            if (key == "compact_overlay_icon") refreshCompactIconSwitch()
            if (key == "auto_translation_mode") refreshAutoModeToggle()
        }
        sp.registerOnSharedPreferenceChangeListener(prefsListener)
    }

    override fun onPause() {
        super.onPause()
        val ctx = context ?: return
        val sp = ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE)
        prefsListener?.let { sp.unregisterOnSharedPreferenceChangeListener(it) }
        prefsListener = null
    }

    private fun refreshOverlayIconSwitch() {
        val v = view ?: return
        val ctx = context ?: return
        val prefs = Prefs(ctx)
        val sw = v.findViewById<MaterialSwitch>(R.id.switchOverlayIcon) ?: return
        sw.isChecked = prefs.showOverlayIcon && PlayTranslateAccessibilityService.isEnabled
    }

    private fun refreshCompactIconSwitch() {
        val v = view ?: return
        val ctx = context ?: return
        val prefs = Prefs(ctx)
        val sw = v.findViewById<MaterialSwitch>(R.id.switchCompactIcon) ?: return
        sw.isChecked = prefs.compactOverlayIcon
        ivIconPreview?.setImageBitmap(createFloatingIconPreview(prefs.compactOverlayIcon))
    }

    private fun refreshAutoModeToggle() {
        val v = currentView ?: view ?: return
        val ctx = context ?: return
        val prefs = Prefs(ctx)
        val hintKind = SourceLanguageProfiles[prefs.sourceLangId].hintTextKind
        val hasHintText = hintKind != HintTextKind.NONE
        val hintLabel = when (hintKind) {
            HintTextKind.PINYIN -> "Pinyin"
            else -> "Furigana"
        }

        // Overlay mode section
        val overlayModeSection = v.findViewById<View>(R.id.overlayModeSection) ?: return
        overlayModeSection.visibility = if (hasHintText) View.VISIBLE else View.GONE
        if (hasHintText) {
            v.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnModeFurigana)?.text = hintLabel
            val toggle = v.findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.toggleAutoMode)
            if (toggle != null) {
                val checkedId = when (prefs.overlayMode) {
                    OverlayMode.FURIGANA -> R.id.btnModeFurigana
                    else -> R.id.btnModeTranslate
                }
                if (toggle.checkedButtonId != checkedId) toggle.check(checkedId)
            }
        }

        // Hotkey furigana/pinyin row
        val rowHotkeyFurigana = v.findViewById<View>(R.id.rowHotkeyFurigana)
        if (rowHotkeyFurigana != null) {
            rowHotkeyFurigana.visibility = if (hasHintText) View.VISIBLE else View.GONE
            if (hasHintText) {
                v.findViewById<TextView>(R.id.tvHotkeyFuriganaLabel)?.text =
                    "Hotkey: hold to show $hintLabel"
            }
        }
    }

    // ── Anki section ──────────────────────────────────────────────────────

    private fun refreshAnkiSection() {
        val v = currentView ?: view ?: return
        val ankiManager       = AnkiManager(requireContext())
        val tvAnkiTitle       = v.findViewById<TextView>(R.id.tvAnkiSectionTitle)
        val llAnkiGetApp      = v.findViewById<LinearLayout>(R.id.llAnkiGetApp)
        val llAnkiPermission  = v.findViewById<LinearLayout>(R.id.llAnkiPermission)

        val installed = ankiManager.isAnkiDroidInstalled()
        llAnkiGetApp.visibility = if (installed) View.GONE else View.VISIBLE

        when {
            !installed -> {
                tvAnkiTitle.text = "ANKI"
                llAnkiPermission.visibility = View.GONE
                spinnerAnkiDeck.visibility = View.GONE
            }
            !ankiManager.hasPermission() -> {
                tvAnkiTitle.text = "ANKI"
                llAnkiPermission.removeAllViews()
                val permanentlyDenied = !shouldShowRequestPermissionRationale(AnkiManager.PERMISSION)
                addActionRow(llAnkiPermission, "Grant AnkiDroid Access",
                    if (permanentlyDenied)
                        "Permission was denied. Tap to open app settings, then go to:\n\nPermissions → Additional Permissions → Enable \"Read and write to the AnkiDroid database\""
                    else
                        "To add flashcards to Anki, ${getString(R.string.app_name)} needs permission to access AnkiDroid.",
                    R.drawable.ic_lock,
                    onClick = {
                        if (permanentlyDenied) {
                            val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = android.net.Uri.fromParts("package", requireContext().packageName, null)
                            }
                            startActivity(intent)
                        } else {
                            requestAnkiPermission.launch(AnkiManager.PERMISSION)
                        }
                    })
                llAnkiPermission.visibility = View.VISIBLE
                spinnerAnkiDeck.visibility = View.GONE
            }
            else -> {
                tvAnkiTitle.text = "ANKI DECK"
                llAnkiPermission.visibility = View.GONE
                if (spinnerAnkiDeck.visibility != View.VISIBLE) loadAnkiDecks()
            }
        }
    }

    // ── Thumbnail helpers ─────────────────────────────────────────────────

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
        val w = decorView.width.takeIf  { it > 0 } ?: run { onReady(null); return }
        val h = decorView.height.takeIf { it > 0 } ?: run { onReady(null); return }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        PixelCopy.request(activity.window, bmp, { result ->
            if (result == PixelCopy.SUCCESS) onReady(scaleThumbnail(bmp))
            else { bmp.recycle(); onReady(null) }
        }, Handler(Looper.getMainLooper()))
    }

    // ── Language row (compact source → target) ─────────────────────────────

    private fun buildLanguageRow() {
        if (!isAdded) return
        val ctx = requireContext()
        val dp = ctx.resources.displayMetrics.density
        val prefs = Prefs(ctx)
        llLanguageList.removeAllViews()

        val sourceName = SourceLangId.fromCode(prefs.sourceLang)?.displayName()
            ?: langDisplayName(prefs.sourceLang)
        val targetName = java.util.Locale(prefs.targetLang)
            .getDisplayLanguage(java.util.Locale(prefs.targetLang))
            .replaceFirstChar { it.uppercase() }

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                (12 * dp).toInt(), (10 * dp).toInt(),
                (12 * dp).toInt(), (10 * dp).toInt()
            )
            background = GradientDrawable().apply {
                setColor(ctx.themeColor(R.attr.colorBgSurface))
                setStroke((1 * dp).toInt(), ctx.themeColor(R.attr.colorTextHint))
                cornerRadius = 8 * dp
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val tvSource = TextView(ctx).apply {
            text = sourceName
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(ctx.themeColor(R.attr.colorTextPrimary))
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
            setOnClickListener {
                setLanguageDelegate()
                LanguageSetupActivity.launch(ctx, LanguageSetupActivity.MODE_SOURCE)
            }
        }

        val arrow = android.widget.ImageView(ctx).apply {
            setImageResource(R.drawable.ic_arrow_right)
            imageTintList = android.content.res.ColorStateList.valueOf(
                ctx.themeColor(R.attr.colorTextHint)
            )
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            layoutParams = LinearLayout.LayoutParams(
                (24 * dp).toInt(), (24 * dp).toInt()
            ).also {
                it.marginStart = (8 * dp).toInt()
                it.marginEnd = (8 * dp).toInt()
            }
        }

        val tvTarget = TextView(ctx).apply {
            text = targetName
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(ctx.themeColor(R.attr.colorTextPrimary))
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
            setOnClickListener {
                setLanguageDelegate()
                LanguageSetupActivity.launch(ctx, LanguageSetupActivity.MODE_TARGET)
            }
        }

        row.addView(tvSource)
        row.addView(arrow)
        row.addView(tvTarget)
        llLanguageList.addView(row)
    }

    private fun setLanguageDelegate() {
        LanguageSetupActivity.selectionDelegate = object : LanguageSetupActivity.Delegate {
            override fun onSourceSelectionDone(sourceId: com.playtranslate.language.SourceLangId) {
                buildLanguageRow()
                onSourceLangChanged?.invoke()
            }
            override fun onTargetSelectionDone(targetCode: String) {
                buildLanguageRow()
                onSourceLangChanged?.invoke()
            }
        }
    }

    private fun langDisplayName(langCode: String): String =
        java.util.Locale(langCode).getDisplayLanguage(java.util.Locale.getDefault())
            .replaceFirstChar { it.uppercase() }

    // ── Display rows ──────────────────────────────────────────────────────

    private fun buildDisplayRows(prefs: Prefs) {
        if (!isAdded) return
        val ctx = requireContext()
        llDisplayOptions.removeAllViews()
        displayList.forEachIndexed { idx, display ->
            llDisplayOptions.addView(buildDisplayRow(ctx, idx, display, prefs))
        }
        if (displayList.isEmpty()) {
            llDisplayOptions.addView(TextView(ctx).apply {
                text = getString(R.string.settings_no_displays_found)
                setTextColor(ctx.themeColor(R.attr.colorTextHint))
                textSize = 13f
                setPadding(0, 8, 0, 8)
            })
        }
    }

    private fun buildDisplayRow(
        ctx: Context, idx: Int, display: android.view.Display, prefs: Prefs
    ): View {
        val dp = ctx.resources.displayMetrics.density
        val isSelected = idx == selectedDisplayIdx
        val thumbW = (80 * dp).toInt()
        val thumbH = (50 * dp).toInt()

        val outlineColor = ctx.themeColor(
            if (isSelected) R.attr.colorAccentPrimary else R.attr.colorTextHint
        )
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
            val accent = ctx.themeColor(R.attr.colorAccentPrimary)
            val bgColor = if (isSelected) android.graphics.Color.argb(15,
                android.graphics.Color.red(accent),
                android.graphics.Color.green(accent),
                android.graphics.Color.blue(accent))
            else ctx.themeColor(R.attr.colorBgSurface)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(bgColor)
                setStroke((1 * dp).toInt(), outlineColor)
                cornerRadius = 8 * dp
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = (4 * dp).toInt() }
        }

        val iv = ImageView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(thumbW, thumbH).also {
                it.marginEnd = (10 * dp).toInt()
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(ctx.themeColor(R.attr.colorBgDivider))
            val thumb = displayThumbnails[display.displayId]
            if (thumb != null) setImageBitmap(thumb)
        }

        val tv = TextView(ctx).apply {
            text = "Display ${display.displayId}  —  ${display.name}"
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(ctx.themeColor(if (isSelected) R.attr.colorAccentPrimary else R.attr.colorTextHint))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        row.addView(iv)
        row.addView(tv)
        row.setOnClickListener {
            selectedDisplayIdx = idx
            val newDisplayId = displayList.getOrNull(idx)?.displayId ?: prefs.captureDisplayId
            if (newDisplayId != prefs.captureDisplayId) {
                prefs.captureDisplayId = newDisplayId
                onDisplayChanged?.invoke()
            }
            buildDisplayRows(prefs)
        }
        return row
    }

    // ── Anki decks ────────────────────────────────────────────────────────

    private fun loadAnkiDecks() {
        val view = currentView ?: view ?: return
        val llAnkiPermission = view.findViewById<LinearLayout>(R.id.llAnkiPermission)
        val prefs = Prefs(requireContext())

        llAnkiPermission.visibility = View.GONE
        spinnerAnkiDeck.visibility = View.GONE

        viewLifecycleOwner.lifecycleScope.launch {
            val decks = withContext(Dispatchers.IO) { AnkiManager(requireContext()).getDecks() }

            if (decks.isEmpty()) {
                return@launch
            }

            deckEntries = decks.entries.toList()
            spinnerAnkiDeck.adapter = android.widget.ArrayAdapter(
                requireContext(),
                android.R.layout.simple_spinner_dropdown_item,
                deckEntries.map { it.value }
            )

            val savedIdx = deckEntries.indexOfFirst { it.key == prefs.ankiDeckId }.takeIf { it >= 0 } ?: 0
            spinnerAnkiDeck.setSelection(savedIdx)

            // Auto-save on deck selection
            spinnerAnkiDeck.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    val entry = deckEntries.getOrNull(pos) ?: return
                    prefs.ankiDeckId   = entry.key
                    prefs.ankiDeckName = entry.value
                }
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }

            spinnerAnkiDeck.visibility = View.VISIBLE
        }
    }

    private var lastDisplayCount = 0

    private fun reinflateIfDisplayCountChanged(dm: DisplayManager) {
        val newCount = dm.displays.size
        if (newCount != lastDisplayCount && isAdded) {
            lastDisplayCount = newCount
            reinflateContent()
        }
    }

    // ── Link rows ─────────────────────────────────────────────────────────

    /** Add a link row that opens a URL. Long-press copies the URL. */
    private fun addLinkRow(container: LinearLayout, title: String, subtitle: String, url: String) {
        addActionRow(container, title, subtitle, R.drawable.ic_open_in_new,
            onClick = { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) },
            onLongClick = {
                val ctx = requireContext()
                val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("URL", url))
                android.widget.Toast.makeText(ctx, "Link copied", android.widget.Toast.LENGTH_SHORT).show()
            })
    }

    /** Add a styled action row with configurable icon and click behavior. */
    private fun addActionRow(
        container: LinearLayout, title: String, subtitle: String,
        iconRes: Int, onClick: () -> Unit, onLongClick: (() -> Unit)? = null
    ): View {
        val ctx = requireContext()
        val row = LayoutInflater.from(ctx)
            .inflate(R.layout.item_settings_link, container, false)
        row.findViewById<TextView>(R.id.tvLinkTitle).text = title
        row.findViewById<TextView>(R.id.tvLinkSubtitle).text = subtitle
        row.findViewById<ImageView>(R.id.ivLinkIcon).setImageResource(iconRes)
        // Tint background with a very light wash of the accent color
        val accent = ctx.themeColor(R.attr.colorAccentPrimary)
        val tinted = android.graphics.Color.argb(15,
            android.graphics.Color.red(accent),
            android.graphics.Color.green(accent),
            android.graphics.Color.blue(accent))
        val bg = row.background
        if (bg is android.graphics.drawable.RippleDrawable) {
            val shape = bg.findDrawableByLayerId(0) as? android.graphics.drawable.GradientDrawable
                ?: (bg.getDrawable(0) as? android.graphics.drawable.GradientDrawable)
            shape?.setColor(tinted)
            shape?.setStroke((1 * ctx.resources.displayMetrics.density).toInt(), accent)
        }
        row.setOnClickListener { onClick() }
        if (onLongClick != null) {
            row.setOnLongClickListener { onLongClick(); true }
        }
        container.addView(row)
        return row
    }

    // ── Theme picker ──────────────────────────────────────────────────────

    private data class ThemeOption(
        val label: String,
        val index: Int,
        val bgColor: Int,       // device / window background colour
        val accentColor: Int    // accent colour shown as inner dot
    )

    private fun buildThemePicker(container: LinearLayout, prefs: Prefs) {
        val themes = listOf(
            ThemeOption("Black",   0, Color.parseColor("#0D0D0D"), Color.parseColor("#00BCD4")),
            ThemeOption("White",   1, Color.parseColor("#F0F0F0"), Color.parseColor("#1565C0")),
            ThemeOption("Rainbow", 2, Color.parseColor("#D8D4D1"), Color.parseColor("#546E7A")),
            ThemeOption("Purple",  3, Color.parseColor("#2E2238"), Color.parseColor("#CE93D8")),
        )

        val ctx = requireContext()
        val dp  = ctx.resources.displayMetrics.density
        val selectionColor = ctx.themeColor(R.attr.colorAccentPrimary)

        container.removeAllViews()

        themes.forEach { theme ->
            val isSelected = prefs.themeIndex == theme.index

            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
                isClickable = true
                isFocusable = true
                background = android.util.TypedValue().let { tv ->
                    ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                    ctx.getDrawable(tv.resourceId)
                }
            }

            // Outer circle: device background colour with accent ring when selected
            val outerCircle = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(theme.bgColor)
                val strokeColor = if (isSelected) selectionColor else Color.argb(80, 128, 128, 128)
                setStroke((if (isSelected) 3 else 1).toInt() * dp.toInt(), strokeColor)
            }
            // Inner accent dot
            val innerDot = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(theme.accentColor)
            }

            val circleSize = (52 * dp).toInt()
            val dotSize    = (16 * dp).toInt()

            val frame = android.widget.FrameLayout(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(circleSize, circleSize).also { lp ->
                    lp.bottomMargin = (6 * dp).toInt()
                    lp.gravity = Gravity.CENTER_HORIZONTAL
                }
            }
            val outerView = View(ctx).apply {
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                )
                background = outerCircle
            }
            val innerView = View(ctx).apply {
                layoutParams = android.widget.FrameLayout.LayoutParams(dotSize, dotSize).also { lp ->
                    lp.gravity = Gravity.CENTER
                }
                background = innerDot
            }
            frame.addView(outerView)
            frame.addView(innerView)
            col.addView(frame)

            val label = TextView(ctx).apply {
                text = theme.label
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(ctx.themeColor(R.attr.colorTextPrimary))
            }
            col.addView(label)

            col.setOnClickListener {
                if (prefs.themeIndex != theme.index) {
                    val scrollY = settingsScrollView.scrollY
                    prefs.themeIndex = theme.index
                    if (onThemeChanged != null) {
                        onThemeChanged?.invoke(scrollY)
                    } else {
                        // Fallback for dialog mode (onboarding)
                        prefs.settingsScrollY = scrollY
                        prefs.suppressNextTransition = true
                        activity?.recreate()
                    }
                }
            }

            container.addView(col)
        }
    }

    companion object {
        const val TAG = "SettingsBottomSheet"
        private const val ARG_HIDE_DISMISS = "hide_dismiss"

        fun newInstance(hideDismiss: Boolean = false) = SettingsBottomSheet().apply {
            if (hideDismiss) arguments = android.os.Bundle().apply { putBoolean(ARG_HIDE_DISMISS, true) }
        }
    }

    /**
     * Creates a bitmap preview of the floating icon as it appears on screen:
     * a dark half-circle snapped to the left edge with the icon inside.
     */
    private fun createFloatingIconPreview(compact: Boolean): Bitmap {
        val dp = resources.displayMetrics.density
        val size = (56 * dp).toInt()
        val circleR = size / 2f
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)

        val circlePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#CC000000")
            style = android.graphics.Paint.Style.FILL
        }
        val borderPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#66888888")
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 1.5f * dp
        }

        if (compact) {
            // Compact: circle pushed further off-screen, only ~1/4 visible with arrow
            val compactOffset = circleR * 0.5f
            val cx = -compactOffset
            canvas.drawCircle(cx, circleR, circleR, circlePaint)
            canvas.drawCircle(cx, circleR, circleR, borderPaint)
            // Arrow pointing right in the visible slice
            val arrowNudge = circleR * 0.65f
            val arrowCx = cx + arrowNudge
            val arrowSize = circleR * 0.22f
            val hw = arrowSize * 0.5f
            val hh = arrowSize * 0.7f
            val path = android.graphics.Path().apply {
                moveTo(arrowCx + hw, circleR)
                lineTo(arrowCx - hw * 0.3f, circleR - hh)
                lineTo(arrowCx - hw * 0.3f, circleR + hh)
                close()
            }
            val arrowPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
                style = android.graphics.Paint.Style.FILL
            }
            canvas.drawPath(path, arrowPaint)
        } else {
            // Normal: half-circle with icon
            canvas.drawCircle(0f, circleR, circleR, circlePaint)
            val iconBmp = android.graphics.BitmapFactory.decodeResource(resources, com.playtranslate.R.drawable.ic_floating_icon)
            val iconH = size * 0.5f
            val iconScale = iconH / iconBmp.height
            val iconW = iconBmp.width * iconScale
            val iconCx = circleR / 2f - 5 * dp
            val dst = android.graphics.RectF(
                iconCx - iconW / 2f, circleR - iconH / 2f,
                iconCx + iconW / 2f, circleR + iconH / 2f
            )
            canvas.drawBitmap(iconBmp, null, dst, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
            iconBmp.recycle()
        }
        return bmp
    }

    // ── Hotkey helpers ──────────────────────────────────────────────────────

    private fun setupHotkeyRow(
        view: android.view.View,
        rowId: Int, switchId: Int, hintId: Int,
        getHotkey: () -> String,
        setHotkey: (String) -> Unit,
        dialogTitle: String? = null
    ) {
        val switch = view.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(switchId)
        val hint = view.findViewById<TextView>(hintId)
        val hotkey = getHotkey()

        switch.isChecked = hotkey.isNotEmpty()
        hint.text = if (hotkey.isNotEmpty()) formatHotkey(hotkey) else "Not set"

        switch.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                val dialog = HotkeySetupDialog.newInstance(dialogTitle)
                dialog.onHotkeySet = { keyCodes ->
                    val combo = keyCodes.joinToString("+")
                    setHotkey(combo)
                    hint.text = formatHotkey(combo)
                }
                dialog.onCancelled = {
                    switch.isChecked = false
                }
                dialog.show(childFragmentManager, "hotkey_setup")
            } else {
                setHotkey("")
                hint.text = "Not set"
            }
        }

        view.findViewById<android.view.View>(rowId).setOnClickListener { switch.toggle() }
    }

    private fun formatHotkey(stored: String): String =
        stored.split("+")
            .map { android.view.KeyEvent.keyCodeToString(it.toInt()).removePrefix("KEYCODE_") }
            .joinToString(" + ")
}
