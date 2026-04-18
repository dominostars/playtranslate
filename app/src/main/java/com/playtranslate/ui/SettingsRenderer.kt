package com.playtranslate.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.playtranslate.AnkiManager
import com.playtranslate.BuildConfig
import com.playtranslate.CaptureService
import com.playtranslate.OverlayMode
import com.playtranslate.PlayTranslateAccessibilityService
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.diagnostics.LogExporter
import com.playtranslate.language.HintTextKind
import com.playtranslate.language.SourceLangId
import com.playtranslate.language.SourceLanguageProfiles
import com.playtranslate.themeColor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Wires every settings row in dialog_settings.xml to prefs / callbacks.
 *
 * Extracted from SettingsBottomSheet so the fragment only handles lifecycle
 * while this class owns all the view ↔ pref binding.
 */
class SettingsRenderer(
    private val root: View,
    private val prefs: Prefs,
    private val ctx: Context,
    private val lifecycleScope: CoroutineScope,
    private val callbacks: Callbacks,
) {

    interface Callbacks {
        fun onClose()
        fun onThemeChanged(scrollY: Int)
        fun onDisplayChanged()
        fun onSourceLangChanged()
        fun onOverlayModeChanged()
        fun onScreenModeChanged()
        fun requestAnkiPermission()
        fun openLanguageSetup(mode: String)
        fun showHotkeyDialog(title: String?, onSet: (List<Int>) -> Unit, onCancel: () -> Unit)
        fun getScrollY(): Int
    }

    // ── View references for refresh ─────────────────────────────────────

    private val rowSourceLang: View = root.findViewById(R.id.rowSourceLang)
    private val rowTargetLang: View = root.findViewById(R.id.rowTargetLang)

    private val rowOverlayIcon: View = root.findViewById(R.id.rowOverlayIcon)
    private val switchOverlayIcon: MaterialSwitch = rowOverlayIcon.findViewById(R.id.switchRowToggle)

    private val rowCompactIcon: View = root.findViewById(R.id.rowCompactIcon)
    private val switchCompactIcon: MaterialSwitch = rowCompactIcon.findViewById(R.id.switchRowToggle)
    private val dividerCompactIcon: View = root.findViewById(R.id.dividerCompactIcon)

    private val overlayModeSection: View = root.findViewById(R.id.overlayModeSection)
    private val toggleAutoMode: MaterialButtonToggleGroup = root.findViewById(R.id.toggleAutoMode)
    private val btnModeFurigana: MaterialButton = root.findViewById(R.id.btnModeFurigana)

    private val rowHideOverlays: View = root.findViewById(R.id.rowHideOverlays)
    private val switchHideOverlays: MaterialSwitch = rowHideOverlays.findViewById(R.id.switchRowToggle)

    private val hotkeySection: View = root.findViewById(R.id.hotkeySection)
    private val rowHotkeyTranslation: View = root.findViewById(R.id.rowHotkeyTranslation)
    private val rowHotkeyFurigana: View = root.findViewById(R.id.rowHotkeyFurigana)
    private val dividerHotkeyFurigana: View = root.findViewById(R.id.dividerHotkeyFurigana)

    private val captureDisplaySection: View = root.findViewById(R.id.captureDisplaySection)
    private val llDisplayOptions: LinearLayout = root.findViewById(R.id.llDisplayOptions)

    private val llAnkiGetApp: LinearLayout = root.findViewById(R.id.llAnkiGetApp)
    private val llAnkiPermission: LinearLayout = root.findViewById(R.id.llAnkiPermission)
    private val spinnerAnkiDeck: Spinner = root.findViewById(R.id.spinnerAnkiDeck)
    private val tvAnkiSectionTitle: TextView = root.findViewById(R.id.tvAnkiSectionTitle)

    private val llThemePicker: LinearLayout = root.findViewById(R.id.llThemePicker)
    private val rowDiscord: View = root.findViewById(R.id.rowDiscord)
    private val rowDonate: View = root.findViewById(R.id.rowDonate)
    private val settingsScrollView: androidx.core.widget.NestedScrollView = root.findViewById(R.id.settingsScrollView)

    private val llDebugSection: LinearLayout = root.findViewById(R.id.llDebugSection)

    private var deckEntries: List<Map.Entry<Long, String>> = emptyList()
    var displayList: List<android.view.Display> = emptyList()
    var selectedDisplayIdx: Int = 0
    val displayThumbnails = HashMap<Int, Bitmap?>()

    // ── Public entry point ───────────────────────────────────────────────

    fun bind() {
        setupGroupHeaders()
        setupLanguageSection()
        setupOnScreenControls()
        setupAutoTranslateSection()
        setupHotkeySection()
        setupCaptureDisplaySection()
        setupTranslationServiceSection()
        setupAnkiSection()
        setupAppearanceSection()
        setupSupportSection()
        setupDiagnosticsSection()
        setupDebugSection()
        setupFooter()
    }

    // ── Group headers ────────────────────────────────────────────────────

    private fun setupGroupHeaders() {
        setGroupHeader(R.id.headerLanguage, "LANGUAGE")
        setGroupHeader(R.id.headerOnScreen, "ON-SCREEN CONTROLS")
        setGroupHeader(R.id.headerAutoTranslate, "AUTO-TRANSLATE")
        setGroupHeader(R.id.headerHotkeys, "HOTKEYS")
        setGroupHeader(R.id.headerCaptureDisplay, "CAPTURE DISPLAY")
        setGroupHeader(R.id.headerTranslationService, "TRANSLATION SERVICE", badge = "Optional")
        setGroupHeader(R.id.headerAnki, "ANKI")
        setGroupHeader(R.id.headerAppearance, "APPEARANCE")
        setGroupHeader(R.id.headerSupport, "SUPPORT")
        setGroupHeader(R.id.headerDiagnostics, "DIAGNOSTICS")
        setGroupHeader(R.id.headerDebug, "DEBUG")
    }

    private fun setGroupHeader(id: Int, title: String, badge: String? = null) {
        val header = root.findViewById<View>(id) ?: return
        header.findViewById<TextView>(R.id.tvGroupTitle)?.text = title
        val badgeView = header.findViewById<TextView>(R.id.tvGroupBadge)
        if (badge != null) {
            badgeView?.text = badge
            badgeView?.visibility = View.VISIBLE
        } else {
            badgeView?.visibility = View.GONE
        }
    }

    // ── Language section ──────────────────────────────────────────────────

    private fun setupLanguageSection() {
        val sourceName = resolveSourceName()
        val targetName = resolveTargetName()

        rowSourceLang.findViewById<TextView>(R.id.tvRowTitle).text = "Translate from"
        rowSourceLang.findViewById<TextView>(R.id.tvRowValue).text = sourceName
        rowSourceLang.setOnClickListener {
            callbacks.openLanguageSetup(LanguageSetupActivity.MODE_SOURCE)
        }

        rowTargetLang.findViewById<TextView>(R.id.tvRowTitle).text = "Translate to"
        rowTargetLang.findViewById<TextView>(R.id.tvRowValue).text = targetName
        rowTargetLang.setOnClickListener {
            callbacks.openLanguageSetup(LanguageSetupActivity.MODE_TARGET)
        }
    }

    private fun resolveSourceName(): String =
        SourceLangId.fromCode(prefs.sourceLang)?.displayName()
            ?: Locale(prefs.sourceLang)
                .getDisplayLanguage(Locale.getDefault())
                .replaceFirstChar { it.uppercase() }

    private fun resolveTargetName(): String =
        Locale(prefs.targetLang)
            .getDisplayLanguage(Locale(prefs.targetLang))
            .replaceFirstChar { it.uppercase() }

    // ── On-screen controls ───────────────────────────────────────────────

    private fun setupOnScreenControls() {
        val isSingle = Prefs.isSingleScreen(ctx)

        // -- Overlay icon row --
        rowOverlayIcon.findViewById<TextView>(R.id.tvRowTitle).setText(
            if (isSingle) R.string.settings_show_overlay_icon_single
            else R.string.settings_show_overlay_icon
        )
        val subtitleOverlay = rowOverlayIcon.findViewById<TextView>(R.id.tvRowSubtitle)
        subtitleOverlay.setText(
            if (isSingle) R.string.settings_overlay_icon_hint_single
            else R.string.settings_overlay_icon_hint_dual
        )
        subtitleOverlay.visibility = View.VISIBLE
        subtitleOverlay.setTextColor(ctx.themeColor(R.attr.ptText))

        switchOverlayIcon.isChecked =
            prefs.showOverlayIcon && PlayTranslateAccessibilityService.isEnabled

        switchOverlayIcon.setOnCheckedChangeListener { _, checked ->
            if (checked && !PlayTranslateAccessibilityService.isEnabled) {
                switchOverlayIcon.isChecked = false
                AlertDialog.Builder(ctx)
                    .setTitle(R.string.overlay_icon_a11y_required_title)
                    .setMessage(R.string.overlay_icon_a11y_required_message)
                    .setPositiveButton(R.string.btn_open_a11y_settings) { _, _ ->
                        ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                return@setOnCheckedChangeListener
            }
            prefs.showOverlayIcon = checked
            PlayTranslateAccessibilityService.instance?.ensureFloatingIcon()
        }
        rowOverlayIcon.setOnClickListener { switchOverlayIcon.toggle() }

        // -- Compact icon row --
        rowCompactIcon.findViewById<TextView>(R.id.tvRowTitle).text = "Minimize icon"
        switchCompactIcon.isChecked = prefs.compactOverlayIcon
        switchCompactIcon.setOnCheckedChangeListener { _, checked ->
            prefs.compactOverlayIcon = checked
            val a11y = PlayTranslateAccessibilityService.instance
            a11y?.hideFloatingIcon("settings_compact_recreate")
            a11y?.ensureFloatingIcon()
        }
        rowCompactIcon.setOnClickListener { switchCompactIcon.toggle() }

        // Show compact row only when overlay icon is on
        updateCompactIconVisibility()
    }

    private fun updateCompactIconVisibility() {
        val showCompact = prefs.showOverlayIcon && PlayTranslateAccessibilityService.isEnabled
        rowCompactIcon.visibility = if (showCompact) View.VISIBLE else View.GONE
        dividerCompactIcon.visibility = if (showCompact) View.VISIBLE else View.GONE
    }

    // ── Auto-translate section ───────────────────────────────────────────

    private fun setupAutoTranslateSection() {
        val hintKind = SourceLanguageProfiles[prefs.sourceLangId].hintTextKind
        val hasHintText = hintKind != HintTextKind.NONE

        // -- Overlay mode toggle (Translation / Furigana-Pinyin) --
        if (hasHintText) {
            overlayModeSection.visibility = View.VISIBLE
            val hintLabel = when (hintKind) {
                HintTextKind.PINYIN -> "Pinyin"
                else -> "Furigana"
            }
            btnModeFurigana.text = hintLabel

            toggleAutoMode.check(
                when (prefs.overlayMode) {
                    OverlayMode.FURIGANA -> R.id.btnModeFurigana
                    else -> R.id.btnModeTranslate
                }
            )

            toggleAutoMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
                if (!isChecked) return@addOnButtonCheckedListener
                prefs.overlayMode = when (checkedId) {
                    R.id.btnModeFurigana -> OverlayMode.FURIGANA
                    else -> OverlayMode.TRANSLATION
                }
                if (CaptureService.instance?.isLive == true) {
                    CaptureService.instance?.stopLive()
                }
                callbacks.onOverlayModeChanged()
            }

            root.findViewById<View>(R.id.dividerOverlayMode)?.visibility = View.VISIBLE
        } else {
            overlayModeSection.visibility = View.GONE
            root.findViewById<View>(R.id.dividerOverlayMode)?.visibility = View.GONE
            if (prefs.overlayMode == OverlayMode.FURIGANA) {
                prefs.overlayMode = OverlayMode.TRANSLATION
                callbacks.onOverlayModeChanged()
            }
        }

        // -- Hide game screen overlays toggle (multi-screen only) --
        val isSingle = Prefs.isSingleScreen(ctx)
        if (!isSingle) {
            rowHideOverlays.visibility = View.VISIBLE
            rowHideOverlays.findViewById<TextView>(R.id.tvRowTitle).text =
                "Hide overlays during auto mode"
            switchHideOverlays.isChecked = prefs.hideGameOverlays
            switchHideOverlays.setOnCheckedChangeListener { _, checked ->
                prefs.hideGameOverlays = checked
                if (CaptureService.instance?.isLive == true) {
                    CaptureService.instance?.stopLive()
                }
            }
            rowHideOverlays.setOnClickListener { switchHideOverlays.toggle() }
        }

        // -- Capture interval --
        setupCaptureInterval()
    }

    private fun setupCaptureInterval() {
        val minSec = Prefs.MIN_CAPTURE_INTERVAL_SEC
        val minLabel = if (minSec == minSec.toLong().toFloat()) "${minSec.toLong()}"
        else "%.1f".format(minSec)

        root.findViewById<TextView>(R.id.tvCaptureIntervalHint)?.text =
            "How often the game screen is captured during auto translation. Minimum $minLabel seconds."

        val etCaptureInterval = root.findViewById<EditText>(R.id.etCaptureInterval)
        etCaptureInterval.setText(prefs.captureIntervalSec.let {
            if (it == it.toLong().toFloat()) it.toLong().toString() else "%.1f".format(it)
        })
        etCaptureInterval.inputType =
            android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
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
                val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE)
                    as android.view.inputmethod.InputMethodManager
                imm.hideSoftInputFromWindow(etCaptureInterval.windowToken, 0)
                true
            } else false
        }
        root.findViewById<View>(R.id.rowCaptureInterval)?.setOnClickListener {
            etCaptureInterval.requestFocus()
            val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE)
                as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(etCaptureInterval, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    // ── Hotkey section ───────────────────────────────────────────────────

    private fun setupHotkeySection() {
        val hintKind = SourceLanguageProfiles[prefs.sourceLangId].hintTextKind
        val hasHintText = hintKind != HintTextKind.NONE

        if (!hasHintText) {
            hotkeySection.visibility = View.GONE
            return
        }
        hotkeySection.visibility = View.VISIBLE

        // -- Translation hotkey (always visible when section is visible) --
        setupSingleHotkeyRow(
            row = rowHotkeyTranslation,
            title = "Hotkey: hold to show Translations",
            getHotkey = { prefs.hotkeyTranslation },
            setHotkey = { prefs.hotkeyTranslation = it },
            dialogTitle = "Show Translations"
        )

        // -- Furigana/Pinyin hotkey --
        val hintLabel = when (hintKind) {
            HintTextKind.PINYIN -> "Pinyin"
            else -> "Furigana"
        }
        rowHotkeyFurigana.visibility = View.VISIBLE
        dividerHotkeyFurigana.visibility = View.VISIBLE
        setupSingleHotkeyRow(
            row = rowHotkeyFurigana,
            title = "Hotkey: hold to show $hintLabel",
            getHotkey = { prefs.hotkeyFurigana },
            setHotkey = { prefs.hotkeyFurigana = it },
            dialogTitle = "Show $hintLabel"
        )
    }

    private fun setupSingleHotkeyRow(
        row: View,
        title: String,
        getHotkey: () -> String,
        setHotkey: (String) -> Unit,
        dialogTitle: String?
    ) {
        val tvTitle = row.findViewById<TextView>(R.id.tvRowTitle)
        val tvSubtitle = row.findViewById<TextView>(R.id.tvRowSubtitle)
        val switch = row.findViewById<MaterialSwitch>(R.id.switchRowToggle)
        val hotkey = getHotkey()

        tvTitle.text = title

        switch.isChecked = hotkey.isNotEmpty()
        if (hotkey.isNotEmpty()) {
            tvSubtitle.text = formatHotkey(hotkey)
            tvSubtitle.visibility = View.VISIBLE
        } else {
            tvSubtitle.text = "Not set"
            tvSubtitle.visibility = View.VISIBLE
        }

        switch.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                callbacks.showHotkeyDialog(
                    dialogTitle,
                    onSet = { keyCodes ->
                        val combo = keyCodes.joinToString("+")
                        setHotkey(combo)
                        tvSubtitle.text = formatHotkey(combo)
                        tvSubtitle.visibility = View.VISIBLE
                    },
                    onCancel = {
                        switch.isChecked = false
                    }
                )
            } else {
                setHotkey("")
                tvSubtitle.text = "Not set"
            }
        }

        row.setOnClickListener { switch.toggle() }
    }

    private fun formatHotkey(stored: String): String =
        stored.split("+")
            .map { KeyEvent.keyCodeToString(it.toInt()).removePrefix("KEYCODE_") }
            .joinToString(" + ")

    // ── Capture display section ──────────────────────────────────────────

    private fun setupCaptureDisplaySection() {
        val dm = ctx.getSystemService(Context.DISPLAY_SERVICE)
            as android.hardware.display.DisplayManager
        displayList = dm.displays.toList()
        selectedDisplayIdx = displayList.indexOfFirst { it.displayId == prefs.captureDisplayId }
            .takeIf { it >= 0 } ?: 0

        captureDisplaySection.visibility =
            if (displayList.size <= 1) View.GONE else View.VISIBLE

        buildDisplayRows(prefs)
    }

    fun buildDisplayRows(prefs: Prefs) {
        llDisplayOptions.removeAllViews()
        displayList.forEachIndexed { idx, display ->
            llDisplayOptions.addView(buildDisplayRow(idx, display, prefs))
        }
        if (displayList.isEmpty()) {
            llDisplayOptions.addView(TextView(ctx).apply {
                text = ctx.getString(R.string.settings_no_displays_found)
                setTextColor(ctx.themeColor(R.attr.ptTextHint))
                textSize = 13f
                setPadding(0, 8, 0, 8)
            })
        }
    }

    private fun buildDisplayRow(
        idx: Int,
        display: android.view.Display,
        prefs: Prefs
    ): View {
        val dp = ctx.resources.displayMetrics.density
        val isSelected = idx == selectedDisplayIdx
        val thumbW = (80 * dp).toInt()
        val thumbH = (50 * dp).toInt()

        val outlineColor = ctx.themeColor(
            if (isSelected) R.attr.ptAccent else R.attr.ptTextHint
        )
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
            val accent = ctx.themeColor(R.attr.ptAccent)
            val bgColor = if (isSelected) Color.argb(
                15,
                Color.red(accent),
                Color.green(accent),
                Color.blue(accent)
            ) else ctx.themeColor(R.attr.ptSurface)
            background = GradientDrawable().apply {
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
            setBackgroundColor(ctx.themeColor(R.attr.ptDivider))
            val thumb = displayThumbnails[display.displayId]
            if (thumb != null) setImageBitmap(thumb)
        }

        val tv = TextView(ctx).apply {
            text = "Display ${display.displayId}  —  ${display.name}"
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(
                ctx.themeColor(if (isSelected) R.attr.ptAccent else R.attr.ptTextHint)
            )
            layoutParams =
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        row.addView(iv)
        row.addView(tv)
        row.setOnClickListener {
            selectedDisplayIdx = idx
            val newDisplayId =
                displayList.getOrNull(idx)?.displayId ?: prefs.captureDisplayId
            if (newDisplayId != prefs.captureDisplayId) {
                prefs.captureDisplayId = newDisplayId
                callbacks.onDisplayChanged()
            }
            buildDisplayRows(prefs)
        }
        return row
    }

    // ── Translation service section ──────────────────────────────────────

    private fun setupTranslationServiceSection() {
        val etDeeplKey = root.findViewById<EditText>(R.id.etDeeplKey)
        etDeeplKey.setText(prefs.deeplApiKey)
        etDeeplKey.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) =
                Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) =
                Unit

            override fun afterTextChanged(s: Editable?) {
                prefs.deeplApiKey = s?.toString()?.trim() ?: ""
            }
        })

        val rowDeeplLink = root.findViewById<View>(R.id.rowDeeplLink)
        wireLinkRow(rowDeeplLink, "Get free DeepL API key",
            "Adding a DeepL API key potentially improves online translations. " +
                "The free plan requires a credit card and includes 500,000 characters per month.",
            "https://www.deepl.com/en/pro#developer")
    }

    // ── Anki section ─────────────────────────────────────────────────────

    private fun setupAnkiSection() {
        addLinkRow(
            llAnkiGetApp,
            "Get AnkiDroid free on Google Play",
            ctx.getString(R.string.anki_section_description, ctx.getString(R.string.app_name)),
            ctx.getString(R.string.anki_play_store_url)
        )
        refreshAnkiSection()
    }

    fun refreshAnkiSection() {
        val ankiManager = AnkiManager(ctx)
        val installed = ankiManager.isAnkiDroidInstalled()

        llAnkiGetApp.visibility = if (installed) View.GONE else View.VISIBLE

        when {
            !installed -> {
                tvAnkiSectionTitle.text = "ANKI"
                llAnkiPermission.visibility = View.GONE
                spinnerAnkiDeck.visibility = View.GONE
            }

            !ankiManager.hasPermission() -> {
                tvAnkiSectionTitle.text = "ANKI"
                llAnkiPermission.removeAllViews()
                addClickableRow(
                    llAnkiPermission,
                    "Grant AnkiDroid Access",
                    "To add flashcards to Anki, ${ctx.getString(R.string.app_name)} needs " +
                        "permission to access AnkiDroid.",
                    R.drawable.ic_lock,
                    onClick = { callbacks.requestAnkiPermission() }
                )
                llAnkiPermission.visibility = View.VISIBLE
                spinnerAnkiDeck.visibility = View.GONE
            }

            else -> {
                tvAnkiSectionTitle.text = "ANKI DECK"
                llAnkiPermission.visibility = View.GONE
                if (spinnerAnkiDeck.visibility != View.VISIBLE) loadAnkiDecks()
            }
        }
    }

    private fun loadAnkiDecks() {
        llAnkiPermission.visibility = View.GONE
        spinnerAnkiDeck.visibility = View.GONE

        lifecycleScope.launch {
            val decks = withContext(Dispatchers.IO) { AnkiManager(ctx).getDecks() }
            if (decks.isEmpty()) return@launch

            deckEntries = decks.entries.toList()
            spinnerAnkiDeck.adapter = ArrayAdapter(
                ctx,
                android.R.layout.simple_spinner_dropdown_item,
                deckEntries.map { it.value }
            )

            val savedIdx =
                deckEntries.indexOfFirst { it.key == prefs.ankiDeckId }.takeIf { it >= 0 } ?: 0
            spinnerAnkiDeck.setSelection(savedIdx)

            spinnerAnkiDeck.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?, v: View?, pos: Int, id: Long
                ) {
                    val entry = deckEntries.getOrNull(pos) ?: return
                    prefs.ankiDeckId = entry.key
                    prefs.ankiDeckName = entry.value
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }

            spinnerAnkiDeck.visibility = View.VISIBLE
        }
    }

    // ── Appearance ───────────────────────────────────────────────────────

    private fun setupAppearanceSection() {
        buildThemePicker(llThemePicker, prefs)
    }

    private data class ThemeOption(
        val label: String,
        val index: Int,
        val bgColor: Int,
        val accentColor: Int
    )

    private fun buildThemePicker(container: LinearLayout, prefs: Prefs) {
        val c = { id: Int -> ContextCompat.getColor(ctx, id) }
        val themes = listOf(
            ThemeOption("Black", 0, c(R.color.pt_dark_bg), c(R.color.pt_accent_teal)),
            ThemeOption("White", 1, c(R.color.pt_light_bg), c(R.color.pt_accent_teal)),
            ThemeOption("Rainbow", 2, c(R.color.pt_light_bg), c(R.color.pt_accent_coral)),
            ThemeOption("Purple", 3, c(R.color.pt_dark_bg), c(R.color.pt_accent_purple)),
        )

        val dp = ctx.resources.displayMetrics.density
        val selectionColor = ctx.themeColor(R.attr.ptAccent)

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
                    ctx.theme.resolveAttribute(
                        android.R.attr.selectableItemBackground, tv, true
                    )
                    ctx.getDrawable(tv.resourceId)
                }
            }

            val outerCircle = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(theme.bgColor)
                val strokeColor =
                    if (isSelected) selectionColor else Color.argb(80, 128, 128, 128)
                setStroke((if (isSelected) 3 else 1).toInt() * dp.toInt(), strokeColor)
            }
            val innerDot = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(theme.accentColor)
            }

            val circleSize = (52 * dp).toInt()
            val dotSize = (16 * dp).toInt()

            val frame = FrameLayout(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(circleSize, circleSize).also { lp ->
                    lp.bottomMargin = (6 * dp).toInt()
                    lp.gravity = Gravity.CENTER_HORIZONTAL
                }
            }
            val outerView = View(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                background = outerCircle
            }
            val innerView = View(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(dotSize, dotSize).also { lp ->
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
                setTextColor(ctx.themeColor(R.attr.ptText))
            }
            col.addView(label)

            col.setOnClickListener {
                if (prefs.themeIndex != theme.index) {
                    val scrollY = callbacks.getScrollY()
                    prefs.themeIndex = theme.index
                    callbacks.onThemeChanged(scrollY)
                }
            }

            container.addView(col)
        }
    }

    // ── Support ──────────────────────────────────────────────────────────

    private fun setupSupportSection() {
        val appName = ctx.getString(R.string.app_name)

        wireLinkRow(rowDiscord, "Join Discord",
            "Feedback, bugs, requests, updates!",
            "https://go.playtranslate.com/discord")

        wireLinkRow(rowDonate, "Support Me",
            ctx.getString(R.string.support_donate_subtitle, appName),
            "https://go.playtranslate.com/donate")
    }

    // ── Diagnostics ──────────────────────────────────────────────────────

    private fun setupDiagnosticsSection() {
        val rowExportLogs = root.findViewById<View>(R.id.rowExportLogs)
        val tvTitle = rowExportLogs.findViewById<TextView>(R.id.tvRowTitle)
        val btnExportLogs = rowExportLogs.findViewById<MaterialButton>(R.id.btnRowAction)

        tvTitle.text = "Export logs"
        btnExportLogs.text = "Export"

        val exportClick = View.OnClickListener {
            btnExportLogs.isEnabled = false
            lifecycleScope.launch {
                val files = withContext(Dispatchers.IO) {
                    runCatching {
                        val logFile = LogExporter.exportLogcat(ctx)
                        listOf(logFile) + LogExporter.getCrashFiles(ctx)
                    }
                }
                btnExportLogs.isEnabled = true
                files.fold(
                    onSuccess = {
                        if (ctx is android.app.Activity) {
                            LogExporter.shareFiles(ctx, it, "PlayTranslate logs")
                        }
                    },
                    onFailure = {
                        Toast.makeText(
                            ctx,
                            "Failed to export logs: ${it.javaClass.simpleName}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                )
            }
        }
        btnExportLogs.setOnClickListener(exportClick)
        rowExportLogs.setOnClickListener(exportClick)
    }

    // ── Debug section ────────────────────────────────────────────────────

    private fun setupDebugSection() {
        if (!BuildConfig.DEBUG) return
        llDebugSection.visibility = View.VISIBLE

        // Force single screen
        val rowForceSingleScreen = root.findViewById<View>(R.id.rowForceSingleScreen)
        val switchForceSingle = rowForceSingleScreen.findViewById<MaterialSwitch>(R.id.switchRowToggle)
        rowForceSingleScreen.findViewById<TextView>(R.id.tvRowTitle).text = "Force single screen"
        switchForceSingle.isChecked = prefs.debugForceSingleScreen
        switchForceSingle.setOnCheckedChangeListener { _, checked ->
            prefs.debugForceSingleScreen = checked
            callbacks.onScreenModeChanged()
        }
        rowForceSingleScreen.setOnClickListener { switchForceSingle.toggle() }

        // Show OCR boxes
        val rowShowOcrBoxes = root.findViewById<View>(R.id.rowShowOcrBoxes)
        val switchOcrBoxes = rowShowOcrBoxes.findViewById<MaterialSwitch>(R.id.switchRowToggle)
        rowShowOcrBoxes.findViewById<TextView>(R.id.tvRowTitle).text = "Show OCR boxes"
        switchOcrBoxes.isChecked = prefs.debugShowOcrBoxes
        switchOcrBoxes.setOnCheckedChangeListener { _, checked ->
            prefs.debugShowOcrBoxes = checked
            val a11y = PlayTranslateAccessibilityService.instance
            if (checked) a11y?.startDebugOcrLoop()
            else a11y?.stopDebugOcrLoop()
        }
        rowShowOcrBoxes.setOnClickListener { switchOcrBoxes.toggle() }

        // Detection log
        val rowDetectionLog = root.findViewById<View>(R.id.rowDetectionLog)
        val switchDetLog = rowDetectionLog.findViewById<MaterialSwitch>(R.id.switchRowToggle)
        rowDetectionLog.findViewById<TextView>(R.id.tvRowTitle).text = "Show detection log"
        switchDetLog.isChecked = prefs.debugShowDetectionLog
        switchDetLog.setOnCheckedChangeListener { _, checked ->
            prefs.debugShowDetectionLog = checked
        }
        rowDetectionLog.setOnClickListener { switchDetLog.toggle() }

        // Force crash
        val rowForceCrash = root.findViewById<View>(R.id.rowForceCrash)
        rowForceCrash.findViewById<TextView>(R.id.tvRowTitle).text = "Force crash"
        val btnCrash = rowForceCrash.findViewById<MaterialButton>(R.id.btnRowAction)
        btnCrash.text = "Crash"
        val crashClick = View.OnClickListener {
            throw RuntimeException("Forced crash from Settings -> Debug -> Force crash")
        }
        btnCrash.setOnClickListener(crashClick)
        rowForceCrash.setOnClickListener(crashClick)
    }

    // ── Footer ───────────────────────────────────────────────────────────

    private fun setupFooter() {
        val tvFooter = root.findViewById<TextView>(R.id.tvFooterVersion) ?: return
        val appName = ctx.getString(R.string.app_name)
        tvFooter.text = "$appName v${BuildConfig.VERSION_NAME}"
    }

    // ── Refresh methods (called externally) ──────────────────────────────

    fun refreshLanguageRow() {
        rowSourceLang.findViewById<TextView>(R.id.tvRowValue).text = resolveSourceName()
        rowTargetLang.findViewById<TextView>(R.id.tvRowValue).text = resolveTargetName()
    }

    fun refreshOverlayIconSwitch() {
        switchOverlayIcon.isChecked =
            prefs.showOverlayIcon && PlayTranslateAccessibilityService.isEnabled
        updateCompactIconVisibility()
    }

    fun refreshCompactIconSwitch() {
        switchCompactIcon.isChecked = prefs.compactOverlayIcon
    }

    fun refreshAutoModeToggle() {
        val hintKind = SourceLanguageProfiles[prefs.sourceLangId].hintTextKind
        val hasHintText = hintKind != HintTextKind.NONE
        val hintLabel = when (hintKind) {
            HintTextKind.PINYIN -> "Pinyin"
            else -> "Furigana"
        }

        overlayModeSection.visibility = if (hasHintText) View.VISIBLE else View.GONE
        root.findViewById<View>(R.id.dividerOverlayMode)?.visibility =
            if (hasHintText) View.VISIBLE else View.GONE

        if (hasHintText) {
            btnModeFurigana.text = hintLabel
            val checkedId = when (prefs.overlayMode) {
                OverlayMode.FURIGANA -> R.id.btnModeFurigana
                else -> R.id.btnModeTranslate
            }
            if (toggleAutoMode.checkedButtonId != checkedId) toggleAutoMode.check(checkedId)
        }

        // Hotkey section visibility
        hotkeySection.visibility = if (hasHintText) View.VISIBLE else View.GONE
        if (hasHintText) {
            rowHotkeyFurigana.visibility = View.VISIBLE
            dividerHotkeyFurigana.visibility = View.VISIBLE
            rowHotkeyFurigana.findViewById<TextView>(R.id.tvRowTitle)?.text =
                "Hotkey: hold to show $hintLabel"
        }
    }

    fun refreshDisplayRows(prefs: Prefs) {
        buildDisplayRows(prefs)
    }

    // ── Private helpers ──────────────────────────────────────────────────

    /** Add a 1dp inset divider to a container (for dynamically-built row lists). */
    private fun addInsetDivider(container: LinearLayout) {
        val divider = LayoutInflater.from(ctx)
            .inflate(R.layout.settings_row_divider, container, false)
        container.addView(divider)
    }

    /** Wire an existing link row view (from <include>) with title, subtitle, and URL. */
    private fun wireLinkRow(row: View, title: String, subtitle: String, url: String) {
        row.findViewById<TextView>(R.id.tvRowTitle).text = title
        val tvSub = row.findViewById<TextView>(R.id.tvRowSubtitle)
        if (subtitle.isNotEmpty()) {
            tvSub.text = subtitle
            tvSub.visibility = View.VISIBLE
        }
        row.setOnClickListener {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
        row.setOnLongClickListener {
            val clipboard =
                ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("URL", url))
            Toast.makeText(ctx, "Link copied", Toast.LENGTH_SHORT).show()
            true
        }
    }

    /** Inflate and add a link row dynamically to a container. For sections with variable content. */
    private fun addLinkRow(container: LinearLayout, title: String, subtitle: String, url: String) {
        val row = LayoutInflater.from(ctx)
            .inflate(R.layout.settings_row_link, container, false)
        wireLinkRow(row, title, subtitle, url)
        container.addView(row)
    }

    /** Add an action row using the settings_row_link template with a custom icon and click. */
    private fun addClickableRow(
        container: LinearLayout,
        title: String,
        subtitle: String,
        iconRes: Int,
        onClick: () -> Unit
    ): View {
        val row = LayoutInflater.from(ctx)
            .inflate(R.layout.settings_row_link, container, false)
        row.findViewById<TextView>(R.id.tvRowTitle).text = title
        val tvSub = row.findViewById<TextView>(R.id.tvRowSubtitle)
        if (subtitle.isNotEmpty()) {
            tvSub.text = subtitle
            tvSub.visibility = View.VISIBLE
        }
        row.findViewById<ImageView>(R.id.ivRowIcon)?.setImageResource(iconRes)
        row.setOnClickListener { onClick() }
        container.addView(row)
        return row
    }
}
