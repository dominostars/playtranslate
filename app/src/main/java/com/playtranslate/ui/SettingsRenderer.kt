package com.playtranslate.ui

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import com.playtranslate.capturableDisplays
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.provider.Settings
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.playtranslate.AnkiManager
import com.playtranslate.BuildConfig
import com.playtranslate.CaptureService
import com.playtranslate.OcrManager
import com.playtranslate.OverlayMode
import com.playtranslate.PlayTranslateAccessibilityService
import com.playtranslate.PlayTranslateTileService
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.diagnostics.LogExporter
import com.playtranslate.language.HintTextKind
import com.playtranslate.language.SourceLangId
import com.playtranslate.language.SourceLanguageProfiles
import com.playtranslate.blendColors
import com.playtranslate.compositeOver
import com.playtranslate.themeColor
import com.playtranslate.translation.BackendId
import com.playtranslate.translation.BackendQuality
import com.playtranslate.translation.BackendSpeed
import com.playtranslate.translation.BackendStatus
import com.playtranslate.translation.Tone
import com.playtranslate.translation.TranslationBackend
import com.playtranslate.translation.TranslationBackendRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
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
        fun openDeepLSettings()
        fun showHotkeyDialog(title: String?, onSet: (List<Int>) -> Unit, onCancel: () -> Unit)
        fun showAnkiDeckPicker(onDeckSelected: () -> Unit)
        fun getScrollY(): Int

        /** Tap on the TranslateGemma row when the model isn't installed.
         *  Implementer shows the download progress dialog, runs the shared
         *  [com.playtranslate.translation.llm.OnDeviceLlmDownloader] configured
         *  for TG, and on success flips [Prefs.translateGemmaEnabled] = true
         *  (which fires the pref-change listener → status refresh + reconcile). */
        fun startTranslateGemmaDownload()

        /** Tap on the TranslateGemma row when it's currently enabled.
         *  Implementer shows the 3-option AlertDialog (Disable only / Delete
         *  model / Cancel). The Cancel branch must call
         *  [SettingsRenderer.refreshTranslategemmaSwitch] to revert the
         *  switch state since the renderer optimistically flipped it. */
        fun showTranslateGemmaDisableDialog()

        /** Tap on the Qwen row when the model isn't installed. Mirrors
         *  [startTranslateGemmaDownload] for the Qwen-configured
         *  [com.playtranslate.translation.llm.OnDeviceLlmDownloader]. */
        fun startQwenDownload()

        /** Tap on the Qwen row when it's currently enabled. Mirrors
         *  [showTranslateGemmaDisableDialog] for Qwen — the Cancel branch must
         *  call [SettingsRenderer.refreshQwenSwitch] to revert the switch. */
        fun showQwenDisableDialog()

        /** Tap on the "Update language packs" row in the Language section.
         *  Implementer instantiates [com.playtranslate.language.PackUpgradeOrchestrator]
         *  and calls `upgradeAll(stalePacks)`. On completion, calls
         *  [SettingsRenderer.refreshLanguageSection] so the cell hides
         *  (since `staleInstalledPacks()` is now empty). Use the **Activity's**
         *  lifecycleScope for the orchestrator coroutine — NOT the Fragment's
         *  view lifecycle scope, which would cancel the in-flight download
         *  while the OverlayProgress dialog (attached to the Activity's
         *  decorView) keeps spinning. */
        fun onUpdateLanguagePacksTapped(
            stalePacks: List<com.playtranslate.language.StalePack>
        )
    }

    // ── View references for refresh ─────────────────────────────────────

    private val rowSourceLang: View = root.findViewById(R.id.rowSourceLang)
    private val rowTargetLang: View = root.findViewById(R.id.rowTargetLang)
    private val cardUpdateLanguagePacks: MaterialCardView = root.findViewById(R.id.cardUpdateLanguagePacks)
    private val rowUpdateLanguagePacks: View = root.findViewById(R.id.rowUpdateLanguagePacks)

    private val cardOnScreenControls: MaterialCardView = root.findViewById(R.id.cardOnScreenControls)
    private val rowOverlayIcon: View = root.findViewById(R.id.rowOverlayIcon)
    private val switchOverlayIcon: MaterialSwitch = rowOverlayIcon.findViewById(R.id.switchRowToggle)

    private val rowCompactIcon: View = root.findViewById(R.id.rowCompactIcon)
    private val switchCompactIcon: MaterialSwitch = rowCompactIcon.findViewById(R.id.switchRowToggle)
    private val dividerCompactIcon: View = root.findViewById(R.id.dividerCompactIcon)

    private val overlayModeSection: View = root.findViewById(R.id.overlayModeSection)
    private val overlayModeToggleContainer: FrameLayout = root.findViewById(R.id.overlayModeToggleContainer)

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
    private val rowAnkiDeck: View = root.findViewById(R.id.rowAnkiDeck)
    private val tvAnkiSectionTitle: TextView = root.findViewById(R.id.tvAnkiSectionTitle)

    private val llThemeModePicker: LinearLayout = root.findViewById(R.id.llThemeModePicker)
    private val llAccentPicker: WrappingLinearLayout = root.findViewById(R.id.llAccentPicker)
    private val rowDiscord: View = root.findViewById(R.id.rowDiscord)
    private val rowDonate: View = root.findViewById(R.id.rowDonate)
    private val settingsScrollView: androidx.core.widget.NestedScrollView = root.findViewById(R.id.settingsScrollView)

    private val llDebugSection: LinearLayout = root.findViewById(R.id.llDebugSection)

    private var deckEntries: List<Map.Entry<Long, String>> = emptyList()
    var displayList: List<android.view.Display> = emptyList()
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
        setGroupHeader(R.id.headerOnlineTranslations, "ONLINE TRANSLATIONS")
        setGroupHeader(R.id.headerOfflineTranslations, "OFFLINE TRANSLATIONS")
        setGroupHeader(R.id.headerAnki, "ANKI")
        setGroupHeader(R.id.headerAppearance, "APPEARANCE")
        setGroupHeader(R.id.headerSupport, "SUPPORT")
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

        rowSourceLang.findViewById<TextView>(R.id.tvRowTitle).text = "Game Language"
        rowSourceLang.findViewById<TextView>(R.id.tvRowValue).text = sourceName
        rowSourceLang.setOnClickListener {
            callbacks.openLanguageSetup(LanguageSetupActivity.MODE_SOURCE)
        }

        rowTargetLang.findViewById<TextView>(R.id.tvRowTitle).text = "Your Language"
        rowTargetLang.findViewById<TextView>(R.id.tvRowValue).text = targetName
        rowTargetLang.setOnClickListener {
            callbacks.openLanguageSetup(LanguageSetupActivity.MODE_TARGET)
        }

        // "Update language packs" cell — its OWN MaterialCardView so the
        // warning tint (background fill + stroke) doesn't bleed onto the
        // Source/Target rows. Visible iff there are stale packs on disk
        // (additive or force, mixed labeling). Subtitle is the same
        // multi-line list the launch-time OverlayAlert uses.
        val activity = root.context as? android.app.Activity
        val stalePacks = if (activity != null) {
            com.playtranslate.language.LanguagePackStore.staleInstalledPacks(activity)
        } else {
            emptyList()
        }
        if (stalePacks.isEmpty() || activity == null) {
            cardUpdateLanguagePacks.visibility = View.GONE
        } else {
            cardUpdateLanguagePacks.visibility = View.VISIBLE
            rowUpdateLanguagePacks.findViewById<TextView>(R.id.tvRowTitle).text =
                root.context.getString(R.string.lang_section_update_packs_title)
            rowUpdateLanguagePacks.findViewById<TextView>(R.id.tvRowSubtitle).text =
                com.playtranslate.language.PackUpgradeOrchestrator
                    .describeForAlert(activity, stalePacks)
            rowUpdateLanguagePacks.setOnClickListener {
                callbacks.onUpdateLanguagePacksTapped(stalePacks)
            }
            applyUpdatePacksWarningTint()
        }
    }

    /** Tint the Update language packs card with the warning attention color
     *  — same blend recipe (`blendColors(attention, baseCard, 0.20f)`) and
     *  full-strength stroke that `refreshOnScreenControlsTint` uses for the
     *  Game Screen Controls card in dual-screen mode when the floating-icon
     *  switch is off. Conveys "this pack is degraded; tap to fix" with the
     *  same visual weight as other recoverable-state warnings. */
    private fun applyUpdatePacksWarningTint() {
        val baseCard = ctx.themeColor(R.attr.ptCard)
        val warning = ctx.themeColor(R.attr.ptWarning)
        cardUpdateLanguagePacks.setCardBackgroundColor(blendColors(warning, baseCard, 0.20f))
        cardUpdateLanguagePacks.strokeColor = warning
    }

    /** Public shim for the Settings cell callback to refresh the Language
     *  section after the upgrade orchestrator completes. The cell hides when
     *  staleInstalledPacks() is now empty. */
    fun refreshLanguageSection() {
        setupLanguageSection()
    }

    private fun resolveSourceName(): String =
        SourceLangId.fromCode(prefs.sourceLang)?.displayName()
            ?: Locale(prefs.sourceLang)
                .getDisplayLanguage(Locale.getDefault())
                .replaceFirstChar { it.uppercase(Locale.getDefault()) }

    private fun resolveTargetName(): String {
        val locale = Locale(prefs.targetLang)
        return locale.getDisplayLanguage(locale)
            .replaceFirstChar { it.uppercase(locale) }
    }

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
            if (checked) {
                prefs.showOverlayIcon = true
                PlayTranslateAccessibilityService.instance?.reconcileFloatingIcons()
                PlayTranslateTileService.TileSync.refresh(ctx)
            } else {
                PlayTranslateAccessibilityService.disable(ctx, "settings_toggle_off")
            }
            refreshOnScreenControlsTint(isSingle)
        }
        rowOverlayIcon.setOnClickListener { switchOverlayIcon.toggle() }
        refreshOnScreenControlsTint(isSingle)

        // -- Compact icon row --
        rowCompactIcon.findViewById<TextView>(R.id.tvRowTitle).text = "Minimize icon"
        switchCompactIcon.isChecked = prefs.compactOverlayIcon
        switchCompactIcon.setOnCheckedChangeListener { _, checked ->
            prefs.compactOverlayIcon = checked
            val a11y = PlayTranslateAccessibilityService.instance
            a11y?.hideFloatingIcon("settings_compact_recreate")
            a11y?.reconcileFloatingIcons()
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

    /** Tints the on-screen-controls card when the overlay switch is off so
     *  the card flags itself as needing attention. Single-screen mode uses
     *  danger (PlayTranslate can't function there without the switch on);
     *  dual-screen uses warning (the app works but the floating helper is
     *  missing). On = neutral card styling. */
    private fun refreshOnScreenControlsTint(isSingle: Boolean) {
        val enabled = prefs.showOverlayIcon && PlayTranslateAccessibilityService.isEnabled
        val baseCard = ctx.themeColor(R.attr.ptCard)
        val baseStroke = compositeOver(ctx.themeColor(R.attr.ptDivider), baseCard)
        // Canonical theme-driven switch tints — same resources the
        // Widget.PlayTranslate.MaterialSwitch style references at inflation.
        val themeThumbTint = ContextCompat.getColorStateList(ctx, R.color.switch_thumb)
        val themeTrackTint = ContextCompat.getColorStateList(ctx, R.color.switch_track)
        val themeTrackDecorationTint =
            ContextCompat.getColorStateList(ctx, R.color.switch_track_decoration)
        if (enabled) {
            cardOnScreenControls.setCardBackgroundColor(baseCard)
            cardOnScreenControls.strokeColor = baseStroke
            switchOverlayIcon.thumbTintList = themeThumbTint
            switchOverlayIcon.trackTintList = themeTrackTint
            switchOverlayIcon.trackDecorationTintList = themeTrackDecorationTint
            return
        }
        val attentionAttr = if (isSingle) R.attr.ptDanger else R.attr.ptWarning
        val attention = ctx.themeColor(attentionAttr)
        cardOnScreenControls.setCardBackgroundColor(blendColors(attention, baseCard, 0.20f))
        // Border uses the attention color at full strength so the card reads
        // clearly as needing action, even on themes where the 20% fill blend
        // lands close to the base card color.
        cardOnScreenControls.strokeColor = attention
        // State-aware tint lists so the switch flips cleanly to its
        // theme-driven ON appearance mid-animation: checked-state colors
        // come from the canonical @color/switch_* resources (so we don't
        // drift from whatever the rest of the app's switches use), and the
        // unchecked state gets the attention color.
        val checkedStates = arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf(-android.R.attr.state_checked),
        )
        val themeCheckedThumb = themeThumbTint?.getColorForState(checkedStates[0], 0) ?: 0
        val themeCheckedTrack = themeTrackTint?.getColorForState(checkedStates[0], 0) ?: 0
        switchOverlayIcon.thumbTintList = ColorStateList(
            checkedStates,
            intArrayOf(themeCheckedThumb, attention),
        )
        switchOverlayIcon.trackTintList = ColorStateList(
            checkedStates,
            intArrayOf(themeCheckedTrack, blendColors(attention, baseCard, 0.30f)),
        )
        // Track outline (visible only when unchecked) picks up the full
        // attention color to match the card border.
        switchOverlayIcon.trackDecorationTintList = ColorStateList.valueOf(attention)
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

            buildPillToggle(
                container = overlayModeToggleContainer,
                options = listOf("Translation" to OverlayMode.TRANSLATION, hintLabel to OverlayMode.FURIGANA),
                selected = prefs.overlayMode,
                onSelect = { mode ->
                    prefs.overlayMode = mode
                    if (CaptureService.instance?.isLive == true) {
                        // TODO(P1): swap for instant rebuild via CaptureService
                        //   internals — setLiveDisplays() now picks up flavor
                        //   mismatch as a stop+restart, so the user wouldn't
                        //   need to re-tap. Behavior-preserving for now.
                        CaptureService.instance?.stopLive()
                    }
                    callbacks.onOverlayModeChanged()
                }
            )

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
            // Multi-display selection silently routes around this toggle —
            // the user has explicitly opted into per-display overlays, so
            // we render on every selected display regardless of this
            // setting. Disclose that on the row itself.
            val subtitleHide = rowHideOverlays.findViewById<TextView>(R.id.tvRowSubtitle)
            if (prefs.captureDisplayIds.size > 1) {
                subtitleHide.text =
                    "Ignored when more than one display is selected — overlays render on each."
                subtitleHide.visibility = View.VISIBLE
                subtitleHide.setTextColor(ctx.themeColor(R.attr.ptTextHint))
            } else {
                subtitleHide.visibility = View.GONE
            }
            switchHideOverlays.isChecked = prefs.hideGameOverlays
            switchHideOverlays.setOnCheckedChangeListener { _, checked ->
                prefs.hideGameOverlays = checked
                if (CaptureService.instance?.isLive == true) {
                    // TODO(P1): swap for instant rebuild via CaptureService
                    //   internals — flavor mismatch detection in
                    //   setLiveDisplays() handles InAppOnly↔overlay swap
                    //   without requiring a user re-tap.
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

        // Section is always available — the translation hotkey is useful
        // regardless of source language (handheld users want hold-to-translate
        // even when there's no furigana/pinyin layer to surface).
        hotkeySection.visibility = View.VISIBLE

        // -- Translation hotkey (always visible) --
        setupSingleHotkeyRow(
            row = rowHotkeyTranslation,
            title = "Hotkey: hold to show Translations",
            getHotkey = { prefs.hotkeyTranslation },
            setHotkey = { prefs.hotkeyTranslation = it },
            dialogTitle = "Show Translations"
        )

        // -- Furigana/Pinyin hotkey (only when source language has hint text) --
        if (hasHintText) {
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
        } else {
            rowHotkeyFurigana.visibility = View.GONE
            dividerHotkeyFurigana.visibility = View.GONE
        }
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
        displayList = dm.capturableDisplays()

        captureDisplaySection.visibility =
            if (displayList.size <= 1) View.GONE else View.VISIBLE

        buildDisplayRows(prefs)
    }

    fun buildDisplayRows(prefs: Prefs) {
        llDisplayOptions.removeAllViews()
        if (displayList.isEmpty()) {
            llDisplayOptions.addView(TextView(ctx).apply {
                text = ctx.getString(R.string.settings_no_displays_found)
                setTextColor(ctx.themeColor(R.attr.ptTextHint))
                textSize = 13f
                val pad = (16 * ctx.resources.displayMetrics.density).toInt()
                setPadding(pad, pad, pad, pad)
            })
            return
        }
        displayList.forEachIndexed { idx, display ->
            if (idx > 0) {
                llDisplayOptions.addView(
                    LayoutInflater.from(ctx)
                        .inflate(R.layout.settings_row_divider, llDisplayOptions, false)
                )
            }
            val isFirst = idx == 0
            val isLast = idx == displayList.size - 1
            llDisplayOptions.addView(buildDisplayRow(display, prefs, isFirst, isLast))
        }
    }

    private fun buildDisplayRow(
        display: android.view.Display,
        prefs: Prefs,
        isFirst: Boolean,
        isLast: Boolean,
    ): View {
        val dp = ctx.resources.displayMetrics.density
        val selectedIds = prefs.captureDisplayIds
        val isSelected = display.displayId in selectedIds
        // 10dp buffer on top, bottom, and left of the thumbnail; right side
        // gets the standard row padding so the checkmark sits in the usual
        // place. Row height = thumbH + 10×2 = 66dp, matching the toggle and
        // support-link rows.
        val rowHPad = ctx.resources.getDimensionPixelSize(R.dimen.pt_row_h_padding)
        val bufferPx = (10 * dp).toInt()
        val halfV = bufferPx
        val halfH = bufferPx
        val thumbH = (46 * dp).toInt()
        val thumbW = (thumbH * 1.6f).toInt()

        val accent = ctx.themeColor(R.attr.ptAccent)
        val cardColor = ctx.themeColor(R.attr.ptCard)
        // Selected row fill: ptCard composited with 10% of the active accent.
        val accent10 = androidx.core.graphics.ColorUtils.setAlphaComponent(accent, 26)
        val selectedBg = compositeOver(accent10, cardColor)
        val cardRadius = ctx.resources.getDimension(R.dimen.pt_radius)

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(halfH, halfV, rowHPad, halfV)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            isClickable = true
            isFocusable = true
            if (isSelected) {
                // Highlight extends edge-to-edge so it visually IS the cell.
                // Round only the corners that touch the card's outer rounding
                // (first row → top corners, last row → bottom corners, middle
                // rows → no rounding) so the fill follows the card cleanly.
                val tl = if (isFirst) cardRadius else 0f
                val tr = if (isFirst) cardRadius else 0f
                val br = if (isLast)  cardRadius else 0f
                val bl = if (isLast)  cardRadius else 0f
                background = GradientDrawable().apply {
                    setColor(selectedBg)
                    cornerRadii = floatArrayOf(tl, tl, tr, tr, br, br, bl, bl)
                }
            }
            // Ripple in the foreground overlays the selected fill.
            foreground = android.util.TypedValue().let { tv ->
                ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                ContextCompat.getDrawable(ctx, tv.resourceId)
            }
        }

        val iv = ImageView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(thumbW, thumbH).also {
                it.marginEnd = (12 * dp).toInt()
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(ctx.themeColor(R.attr.ptDivider))
            val thumb = displayThumbnails[display.displayId]
            if (thumb != null) setImageBitmap(thumb)
        }

        val tv = TextView(ctx).apply {
            text = "Display ${display.displayId}  —  ${display.name}"
            setTextColor(ctx.themeColor(if (isSelected) R.attr.ptText else R.attr.ptTextMuted))
            setTextAppearance(R.style.Text_PT_RowTitle)
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        }

        row.addView(iv)
        row.addView(tv)
        if (isSelected) {
            val checkSize = (20 * dp).toInt()
            row.addView(ImageView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(checkSize, checkSize).also {
                    it.marginStart = (8 * dp).toInt()
                }
                setImageResource(R.drawable.ic_check)
                imageTintList = android.content.res.ColorStateList.valueOf(accent)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
        }
        row.setOnClickListener {
            val current = prefs.captureDisplayIds
            val targetId = display.displayId
            val next = if (targetId in current) {
                // Refuse to toggle off the last selected display — leaving the
                // set empty would leave the app with nothing to capture.
                if (current.size <= 1) return@setOnClickListener
                current - targetId
            } else {
                // Preserve insertion order so primary disambiguators are stable.
                current.toMutableSet().also { it += targetId }
            }
            prefs.captureDisplayIds = next
            callbacks.onDisplayChanged()
            buildDisplayRows(prefs)
        }
        return row
    }

    // ── Translation service section ──────────────────────────────────────

    private val rowBackendDeepl: View = root.findViewById(R.id.rowBackendDeepl)
    private val rowBackendLingva: View = root.findViewById(R.id.rowBackendLingva)
    private val rowBackendTranslategemma: View = root.findViewById(R.id.rowBackendTranslategemma)
    private val rowBackendQwen: View = root.findViewById(R.id.rowBackendQwen)
    private val dividerBackendQwen: View = root.findViewById(R.id.dividerBackendQwen)
    private val rowBackendMlkit: View = root.findViewById(R.id.rowBackendMlkit)

    /** Per-backend in-flight `refreshStatus` job, keyed by [BackendId]. Used
     *  to single-flight: a new render that triggers a refresh cancels any
     *  prior refresh for the same backend so a slow request can't overwrite
     *  the result of a faster, more recent one. */
    private val backendRefreshJobs: MutableMap<BackendId, Job> = mutableMapOf()

    private fun setupTranslationServiceSection() {
        // ML Kit row: no toggle. Hide the switch from the shared layout.
        rowBackendMlkit.findViewById<MaterialSwitch>(R.id.switchRowToggle).visibility = View.GONE
        wireBackendStaticRow(row = rowBackendMlkit, title = "ML Kit")

        wireBackendSwitchRow(
            row = rowBackendLingva,
            title = "Lingva",
            initial = prefs.lingvaEnabled,
            onChanged = { checked -> prefs.lingvaEnabled = checked },
        )

        wireDeeplBackendRow()
        wireTranslateGemmaBackendRow()
        wireQwenBackendRow()

        // Compose line 1 for each backend from its metadata
        // (requiresInternet + quality), styled with mixed-color spans.
        for (backend in TranslationBackendRegistry.orderedBackends()) {
            val row = backendRowById(backend.id) ?: continue
            setBackendLine1(row, backend)
        }

        // Render every backend's status line, kicking off async refreshes
        // for ones in Loading state.
        refreshAllBackendStatuses()
    }

    /** Compose the row's line-1 subtitle from the backend's metadata —
     *  `(quality) · (speed)` for offline backends, just `(quality)` for
     *  online — with each part tinted by its own [Tone] via a
     *  [ForegroundColorSpan]. The TextView's base color (set by the
     *  `Text.PT.RowSubtitle` style) handles parts we don't span. The
     *  online/offline distinction itself is now carried by the section
     *  header (Online vs Offline cards), not a per-row pill. */
    private fun setBackendLine1(row: View, backend: TranslationBackend) {
        val tv = row.findViewById<TextView>(R.id.tvRowSubtitle)
        val builder = SpannableStringBuilder()

        val (qualityText, qualityTone) = when (backend.quality) {
            BackendQuality.Bad    -> ctx.getString(R.string.tr_service_quality_bad)    to Tone.Danger
            BackendQuality.Okay   -> ctx.getString(R.string.tr_service_quality_okay)   to null
            BackendQuality.Good   -> ctx.getString(R.string.tr_service_quality_good)   to null
            BackendQuality.Better -> ctx.getString(R.string.tr_service_quality_better) to Tone.Accent
        }
        appendMaybeColored(builder, qualityText, qualityTone)

        backend.speed?.let { speed ->
            builder.append(" · ")
            val (speedText, speedTone) = when (speed) {
                BackendSpeed.VerySlow -> ctx.getString(R.string.tr_service_speed_very_slow) to Tone.Danger
                BackendSpeed.Slow     -> ctx.getString(R.string.tr_service_speed_slow)      to Tone.Warning
                BackendSpeed.Fast     -> ctx.getString(R.string.tr_service_speed_fast)      to Tone.Accent
            }
            appendMaybeColored(builder, speedText, speedTone)
        }

        tv.text = builder
        tv.visibility = View.VISIBLE
    }

    private fun appendMaybeColored(
        builder: SpannableStringBuilder,
        text: String,
        tone: Tone?,
    ) {
        val start = builder.length
        builder.append(text)
        if (tone != null) {
            val color = ctx.themeColor(toneAttr(tone))
            builder.setSpan(
                ForegroundColorSpan(color),
                start,
                builder.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    }

    /** Refresh the DeepL switch from the current pref value. Called from
     *  [SettingsBottomSheet]'s pref-change observer after
     *  [DeepLSettingsActivity] returns. */
    fun refreshDeeplBackendSwitch() {
        rowBackendDeepl.findViewById<MaterialSwitch>(R.id.switchRowToggle)?.let {
            it.isChecked = prefs.deeplEnabled
        }
    }

    /** Refresh the Lingva switch from the current pref value. */
    fun refreshLingvaBackendSwitch() {
        rowBackendLingva.findViewById<MaterialSwitch>(R.id.switchRowToggle)?.let {
            it.isChecked = prefs.lingvaEnabled
        }
    }

    /** Refresh the TranslateGemma switch from the current pref value.
     *  Called from the SP listener after the Cancel branch of the disable
     *  dialog (which needs to revert the optimistic toggle), and after a
     *  successful download (which flips the pref to true). */
    fun refreshTranslategemmaSwitch() {
        rowBackendTranslategemma.findViewById<MaterialSwitch>(R.id.switchRowToggle)?.let {
            it.isChecked = prefs.translateGemmaEnabled
        }
    }

    /** Refresh the Qwen switch from the current pref value. Called from the SP
     *  listener after the Cancel branch of the disable dialog (which needs to
     *  revert the optimistic toggle), and after a successful download (which
     *  flips the pref to true). */
    fun refreshQwenSwitch() {
        rowBackendQwen.findViewById<MaterialSwitch>(R.id.switchRowToggle)?.let {
            it.isChecked = prefs.qwenEnabled
        }
    }

    /** Re-render every backend row's secondary subtitle line and kick off
     *  an async [TranslationBackend.refreshStatus] for each. Called on
     *  initial bind, on Settings resume (after [DeepLSettingsActivity]
     *  returns), and on relevant pref changes.
     *
     *  We render the cached status synchronously first (so the row shows
     *  the last known value immediately) and then trigger a background
     *  refresh that updates the row when fresh data arrives. Backends
     *  without async state (Lingva, ML Kit) inherit the default no-op
     *  [refreshStatus] that returns the same status without I/O — so
     *  always-launching is essentially free for them. */
    fun refreshAllBackendStatuses() {
        for (backend in TranslationBackendRegistry.orderedBackends()) {
            val row = backendRowById(backend.id) ?: continue
            renderBackendStatusLine(row, backend.status)
            backendRefreshJobs[backend.id]?.cancel()
            backendRefreshJobs[backend.id] = lifecycleScope.launch {
                val fresh = backend.refreshStatus()
                renderBackendStatusLine(row, fresh)
            }
        }
    }

    private fun backendRowById(id: BackendId): View? = when (id) {
        "deepl"           -> rowBackendDeepl
        "lingva"          -> rowBackendLingva
        "translategemma"  -> rowBackendTranslategemma
        "qwen"            -> rowBackendQwen
        "mlkit"           -> rowBackendMlkit
        else              -> null
    }

    /** Apply a [BackendStatus] to a row's secondary subtitle TextView,
     *  styling by tone and italic flag. The Loading state has its own
     *  generic text since backends don't supply transient text. */
    private fun renderBackendStatusLine(row: View, status: BackendStatus) {
        val tv = row.findViewById<TextView>(R.id.tvRowSubtitle2) ?: return
        when (status) {
            is BackendStatus.Hidden -> tv.visibility = View.GONE
            is BackendStatus.Loading -> {
                tv.text = ctx.getString(R.string.tr_service_status_loading)
                applyTone(tv, Tone.Neutral)
                applyItalic(tv, true)
                tv.visibility = View.VISIBLE
            }
            is BackendStatus.Info -> {
                tv.text = status.text
                applyTone(tv, status.tone)
                applyItalic(tv, status.italic)
                tv.visibility = View.VISIBLE
            }
            is BackendStatus.Quota -> {
                tv.text = formatQuota(status)
                // Danger tone when the user has hit (or exceeded) their
                // limit for the period — translations will start failing
                // through to the next backend.
                val exhausted = status.used >= status.limit
                applyTone(tv, if (exhausted) Tone.Danger else Tone.Neutral)
                applyItalic(tv, false)
                tv.visibility = View.VISIBLE
            }
        }
    }

    private fun applyTone(tv: TextView, tone: Tone) {
        tv.setTextColor(ctx.themeColor(toneAttr(tone)))
    }

    private fun toneAttr(tone: Tone): Int = when (tone) {
        Tone.Neutral -> R.attr.ptTextHint
        Tone.Warning -> R.attr.ptWarning
        Tone.Danger  -> R.attr.ptDanger
        Tone.Accent  -> R.attr.ptAccent
    }

    private fun applyItalic(tv: TextView, italic: Boolean) {
        // Pass null for the family so only the style flag changes;
        // otherwise re-styling a previously-italicised typeface can
        // leave residual italic-ness on platforms where the styled
        // typeface gets cached. This guarantees a clean toggle.
        tv.setTypeface(null, if (italic) Typeface.ITALIC else Typeface.NORMAL)
    }

    private fun formatQuota(q: BackendStatus.Quota): String {
        val used  = String.format(Locale.getDefault(), "%,d", q.used)
        val limit = String.format(Locale.getDefault(), "%,d", q.limit)
        val base  = ctx.getString(R.string.tr_service_status_quota_fmt, used, limit)
        return q.resetEpochMs?.let { ms ->
            val date = SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(ms))
            ctx.getString(R.string.tr_service_status_quota_with_reset_fmt, base, date)
        } ?: base
    }

    /** Wire the DeepL row's title + tap behavior. The line-1 subtitle is
     *  composed by [setBackendLine1]; line 2 is rendered by
     *  [renderBackendStatusLine] via [refreshAllBackendStatuses]. The
     *  switch in `settings_row_backend.xml` is non-clickable; the whole
     *  row is the tap target.
     *
     *    - off → tap: open [DeepLSettingsActivity]. The activity writes
     *      `deeplEnabled=true` on Save and the pref-change listener flips
     *      the switch + retriggers status refresh.
     *    - on  → tap: directly disable (preserving the saved DeepL key
     *      so a later re-enable can prepopulate it). */
    /** Wire the TranslateGemma row's title + tap behavior, or hide it
     *  entirely when the feature flag is off.
     *
     *    - off (model not installed) → tap: callbacks.startTranslateGemmaDownload().
     *      The download flow is responsible for flipping the pref on success.
     *    - off (model installed but disabled) → tap: enable directly.
     *    - on  → tap: callbacks.showTranslateGemmaDisableDialog(); the Cancel
     *      branch must call refreshTranslategemmaSwitch() to revert the
     *      optimistic switch flip.
     *
     *  The switch state reflects the user's stored intent (`prefs.translateGemmaEnabled`),
     *  NOT file-system installation state — so a downloaded-but-disabled model
     *  shows the switch as off. The status line distinguishes the three states.
     */
    private fun wireTranslateGemmaBackendRow() {
        if (!com.playtranslate.BuildConfig.TRANSLATEGEMMA_ENABLED) {
            // TG is the first row in the Offline card; the divider that
            // follows it (between TG and Qwen) becomes a stray top border
            // when TG is hidden, so hide it too.
            rowBackendTranslategemma.visibility = View.GONE
            dividerBackendQwen.visibility = View.GONE
            return
        }

        rowBackendTranslategemma.findViewById<TextView>(R.id.tvRowTitle).text =
            ctx.getString(R.string.translategemma_display_name)

        val backend = com.playtranslate.translation.TranslationBackendRegistry
            .byId("translategemma") as? com.playtranslate.translation.llm.OnDeviceLlmBackend
        if (backend != null && configureIncompatibleHardwareRow(rowBackendTranslategemma, backend)) {
            return
        }

        val switch = rowBackendTranslategemma.findViewById<MaterialSwitch>(R.id.switchRowToggle)
        switch.isChecked = prefs.translateGemmaEnabled

        rowBackendTranslategemma.setOnClickListener {
            if (prefs.translateGemmaEnabled) {
                // Currently on → optimistically flip the switch off so the dialog
                // shows the right "after" state. Cancel branch reverts it.
                switch.isChecked = false
                callbacks.showTranslateGemmaDisableDialog()
            } else {
                val installed = com.playtranslate.translation.translategemma
                    .TranslateGemmaModel.isInstalled(ctx)
                if (installed) {
                    // Already downloaded; just enable.
                    prefs.translateGemmaEnabled = true
                    switch.isChecked = true
                } else {
                    // Need to download. The download flow flips the pref on success
                    // (which fires the SP listener → switch refresh).
                    callbacks.startTranslateGemmaDownload()
                }
            }
        }
    }

    /** Wire the Qwen backend row (download / enable / disable-with-dialog).
     *  Mirrors [wireTranslateGemmaBackendRow] without the BuildConfig flag —
     *  Qwen ships visible by default. */
    private fun wireQwenBackendRow() {
        rowBackendQwen.findViewById<TextView>(R.id.tvRowTitle).text =
            ctx.getString(R.string.qwen_display_name)

        val backend = com.playtranslate.translation.TranslationBackendRegistry
            .byId("qwen") as? com.playtranslate.translation.llm.OnDeviceLlmBackend
        if (backend != null && configureIncompatibleHardwareRow(rowBackendQwen, backend)) {
            return
        }

        val switch = rowBackendQwen.findViewById<MaterialSwitch>(R.id.switchRowToggle)
        switch.isChecked = prefs.qwenEnabled

        rowBackendQwen.setOnClickListener {
            if (prefs.qwenEnabled) {
                switch.isChecked = false
                callbacks.showQwenDisableDialog()
            } else {
                val installed = com.playtranslate.translation.qwen
                    .QwenModel.isInstalled(ctx)
                if (installed) {
                    prefs.qwenEnabled = true
                    switch.isChecked = true
                } else {
                    callbacks.startQwenDownload()
                }
            }
        }
    }

    /**
     * If [backend] reports [com.playtranslate.translation.llm.OnDeviceLlmBackend.meetsHardwareRequirements]
     * = false, configure [row] in a "visible but inert" state: hide the
     * toggle switch and disable click handling. Title and status line are
     * left to the caller / [refreshAllBackendStatuses] — the status getter
     * on the backend surfaces [com.playtranslate.translation.llm.OnDeviceLlmBackend.hardwareIncompatibilityReason]
     * as the line-2 explanation. Returns true if the row was configured as
     * incompatible (caller should early-return).
     */
    private fun configureIncompatibleHardwareRow(
        row: View,
        backend: com.playtranslate.translation.llm.OnDeviceLlmBackend,
    ): Boolean {
        if (backend.meetsHardwareRequirements()) return false
        row.findViewById<MaterialSwitch>(R.id.switchRowToggle).visibility = View.GONE
        row.setOnClickListener(null)
        row.isClickable = false
        return true
    }

    private fun wireDeeplBackendRow() {
        rowBackendDeepl.findViewById<TextView>(R.id.tvRowTitle).text = "DeepL"

        val switch = rowBackendDeepl.findViewById<MaterialSwitch>(R.id.switchRowToggle)
        switch.isChecked = prefs.deeplEnabled

        rowBackendDeepl.setOnClickListener {
            if (prefs.deeplEnabled) {
                prefs.deeplEnabled = false
                switch.isChecked = false
            } else {
                callbacks.openDeepLSettings()
            }
        }
    }

    private fun wireBackendSwitchRow(
        row: View,
        title: String,
        initial: Boolean,
        onChanged: (Boolean) -> Unit,
    ) {
        row.findViewById<TextView>(R.id.tvRowTitle).text = title

        val switch = row.findViewById<MaterialSwitch>(R.id.switchRowToggle)
        switch.isChecked = initial
        // Single source of truth: row click toggles the switch and
        // immediately writes the pref. The switch itself stays
        // non-clickable per the layout, so we don't need a separate
        // OnCheckedChangeListener — that path can't fire for user input.
        row.setOnClickListener {
            val next = !switch.isChecked
            switch.isChecked = next
            onChanged(next)
        }
    }

    /** Variant of [wireBackendSwitchRow] for rows without an interactive
     *  toggle (the ML Kit row). Caller is responsible for hiding the
     *  switch view; this method only wires the title and removes any
     *  click handler. The line-1 subtitle is composed by [setBackendLine1]. */
    private fun wireBackendStaticRow(row: View, title: String) {
        row.findViewById<TextView>(R.id.tvRowTitle).text = title
        row.isClickable = false
        row.isFocusable = false
        row.setOnClickListener(null)
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
                tvAnkiSectionTitle.visibility = View.GONE
                llAnkiPermission.visibility = View.GONE
                rowAnkiDeck.visibility = View.GONE
            }

            !ankiManager.hasPermission() -> {
                tvAnkiSectionTitle.visibility = View.GONE
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
                rowAnkiDeck.visibility = View.GONE
            }

            else -> {
                tvAnkiSectionTitle.visibility = View.GONE
                llAnkiPermission.visibility = View.GONE
                setupAnkiDeckRow()
            }
        }
    }

    private fun setupAnkiDeckRow() {
        rowAnkiDeck.findViewById<TextView>(R.id.tvRowTitle).text = "Deck"
        val deckName = prefs.ankiDeckName.ifEmpty { "Not selected" }
        rowAnkiDeck.findViewById<TextView>(R.id.tvRowValue).text = deckName
        rowAnkiDeck.setOnClickListener {
            callbacks.showAnkiDeckPicker { refreshAnkiDeckValue() }
        }
        rowAnkiDeck.visibility = View.VISIBLE

        // Validate saved deck still exists in AnkiDroid
        validateAnkiDeck()
    }

    private fun validateAnkiDeck() {
        if (prefs.ankiDeckId == 0L) return
        lifecycleScope.launch {
            val decks = withContext(Dispatchers.IO) { AnkiManager(ctx).getDecks() }
            if (decks.isEmpty()) return@launch
            if (!decks.containsKey(prefs.ankiDeckId)) {
                // Saved deck no longer exists — clear and show first available
                val first = decks.entries.first()
                prefs.ankiDeckId = first.key
                prefs.ankiDeckName = first.value
                rowAnkiDeck.findViewById<TextView>(R.id.tvRowValue).text = first.value
            }
        }
    }

    private fun refreshAnkiDeckValue() {
        val freshPrefs = Prefs(ctx)
        val deckName = freshPrefs.ankiDeckName.ifEmpty { "Not selected" }
        rowAnkiDeck.findViewById<TextView>(R.id.tvRowValue).text = deckName
    }

    // ── Appearance ───────────────────────────────────────────────────────

    private fun setupAppearanceSection() {
        buildThemeModePicker(llThemeModePicker)
        buildAccentPicker(llAccentPicker)
    }

    private fun buildThemeModePicker(container: LinearLayout) {
        container.removeAllViews()
        val dp = ctx.resources.displayMetrics.density
        val tileRadius = 12 * dp
        val swatchRadius = 8 * dp
        val accentColor = ctx.themeColor(R.attr.ptAccent)
        val outlineColor = ctx.themeColor(R.attr.ptOutline)
        val current = prefs.themeMode

        val darkBg   = ContextCompat.getColor(ctx, R.color.pt_dark_bg)
        val darkText = ContextCompat.getColor(ctx, R.color.pt_dark_text)
        val lightBg   = ContextCompat.getColor(ctx, R.color.pt_light_bg)
        val lightText = ContextCompat.getColor(ctx, R.color.pt_light_text)

        data class ModeOption(val mode: ThemeMode, val label: String)
        val modes = listOf(
            ModeOption(ThemeMode.SYSTEM, ctx.getString(R.string.pt_theme_mode_system)),
            ModeOption(ThemeMode.DARK,   ctx.getString(R.string.pt_theme_mode_dark)),
            ModeOption(ThemeMode.LIGHT,  ctx.getString(R.string.pt_theme_mode_light)),
        )

        modes.forEachIndexed { idx, opt ->
            val selected = opt.mode == current
            val tile = FrameLayout(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ).also { lp ->
                    if (idx > 0) lp.marginStart = (10 * dp).toInt()
                }
                background = GradientDrawable().apply {
                    cornerRadius = tileRadius
                    setColor(Color.TRANSPARENT)
                    setStroke((2 * dp).toInt(),
                        if (selected) accentColor else outlineColor)
                }
                setPadding((4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt())
                isClickable = true
                isFocusable = true
                foreground = android.util.TypedValue().let { tv ->
                    ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                    ContextCompat.getDrawable(ctx, tv.resourceId)
                }
                setOnClickListener {
                    if (prefs.themeMode != opt.mode) {
                        val scrollY = callbacks.getScrollY()
                        prefs.themeMode = opt.mode
                        callbacks.onThemeChanged(scrollY)
                    }
                }
            }

            val inner = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val swatchH = (52 * dp).toInt()
            val swatch = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, swatchH
                )
                background = when (opt.mode) {
                    ThemeMode.DARK -> GradientDrawable().apply {
                        setColor(darkBg); cornerRadius = swatchRadius
                    }
                    ThemeMode.LIGHT -> GradientDrawable().apply {
                        setColor(lightBg); cornerRadius = swatchRadius
                    }
                    ThemeMode.SYSTEM -> DiagonalSplitDrawable(
                        topLeftColor = darkBg,
                        bottomRightColor = lightBg,
                        cornerRadius = swatchRadius,
                    )
                }
                setPadding((8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt())
            }

            // Faux text bars for solid Light/Dark previews. System tile is
            // intentionally bare so the diagonal split reads cleanly.
            if (opt.mode != ThemeMode.SYSTEM) {
                val barColor = if (opt.mode == ThemeMode.DARK) darkText else lightText
                swatch.post {
                    swatch.removeAllViews()
                    val availW = swatch.width - swatch.paddingLeft - swatch.paddingRight
                    if (availW <= 0) return@post

                    fun makeBar(widthFraction: Float, height: Int, alphaFrac: Float): View {
                        return View(ctx).apply {
                            layoutParams = LinearLayout.LayoutParams(
                                (availW * widthFraction).toInt(), (height * dp).toInt()
                            )
                            background = GradientDrawable().apply {
                                setColor(barColor)
                                cornerRadius = 2 * dp
                                this.alpha = (alphaFrac * 255).toInt()
                            }
                        }
                    }
                    swatch.addView(makeBar(0.40f, 4, 0.8f))
                    swatch.addView(makeBar(0.70f, 3, 0.4f).also {
                        (it.layoutParams as LinearLayout.LayoutParams).topMargin = (4 * dp).toInt()
                    })
                    swatch.addView(makeBar(0.55f, 3, 0.4f).also {
                        (it.layoutParams as LinearLayout.LayoutParams).topMargin = (4 * dp).toInt()
                    })
                }
            }

            inner.addView(swatch)

            val label = TextView(ctx).apply {
                text = opt.label
                textSize = 12f
                typeface = android.graphics.Typeface.create(
                    "sans-serif-medium", android.graphics.Typeface.NORMAL
                )
                gravity = Gravity.CENTER
                setTextColor(ctx.themeColor(R.attr.ptText))
                setPadding(0, (6 * dp).toInt(), 0, (2 * dp).toInt())
            }
            inner.addView(label)

            tile.addView(inner)
            container.addView(tile)
        }
    }

    private fun buildAccentPicker(container: WrappingLinearLayout) {
        container.removeAllViews()
        val dp = ctx.resources.displayMetrics.density
        val swatchSize = (48 * dp).toInt()
        val ringStroke = (2 * dp).toInt()
        val innerInset = (8 * dp).toInt()
        container.horizontalSpacingPx = (8 * dp).toInt()
        container.verticalSpacingPx = (12 * dp).toInt()

        val current = prefs.accent

        AccentColor.values().forEach { accent ->
            val color = ContextCompat.getColor(ctx, accent.color)
            val selected = accent == current

            val ringDrawable = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
                if (selected) setStroke(ringStroke, color)
            }
            val innerDrawable = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
            val layered = android.graphics.drawable.LayerDrawable(
                arrayOf(ringDrawable, innerDrawable)
            ).apply {
                setLayerInset(1, innerInset, innerInset, innerInset, innerInset)
            }

            val swatch = FrameLayout(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(swatchSize, swatchSize)
                background = layered
                isClickable = true
                isFocusable = true
                contentDescription = ctx.getString(accent.displayName)
                foreground = android.util.TypedValue().let { tv ->
                    ctx.theme.resolveAttribute(
                        android.R.attr.selectableItemBackgroundBorderless, tv, true
                    )
                    ContextCompat.getDrawable(ctx, tv.resourceId)
                }
                setOnClickListener {
                    if (prefs.accent != accent) {
                        val scrollY = callbacks.getScrollY()
                        prefs.accentName = accent.name
                        callbacks.onThemeChanged(scrollY)
                    }
                }
            }
            container.addView(swatch)
        }
    }

    // ── Support ──────────────────────────────────────────────────────────

    private fun setupSupportSection() {
        wireLinkRow(rowDiscord, "Join Discord",
            "Get help and chat with other players.",
            "https://go.playtranslate.com/discord")

        // Export logs row
        val rowExportLogs = root.findViewById<View>(R.id.rowExportLogs)
        rowExportLogs.findViewById<TextView>(R.id.tvRowTitle).text = "Export logs"
        val tvExportSub = rowExportLogs.findViewById<TextView>(R.id.tvRowSubtitle)
        tvExportSub.text = "Share recent logs for a bug report"
        tvExportSub.visibility = View.VISIBLE
        rowExportLogs.setOnClickListener {
            lifecycleScope.launch {
                val files = withContext(Dispatchers.IO) {
                    runCatching {
                        val logFile = LogExporter.exportLogcat(ctx)
                        listOf(logFile) + LogExporter.getCrashFiles(ctx)
                    }
                }
                files.fold(
                    onSuccess = {
                        if (ctx is android.app.Activity) {
                            LogExporter.shareFiles(ctx, it, "PlayTranslate logs")
                        }
                    },
                    onFailure = {
                        Toast.makeText(ctx,
                            "Failed to export logs: ${it.javaClass.simpleName}",
                            Toast.LENGTH_LONG).show()
                    }
                )
            }
        }

        wireLinkRow(rowDonate, "Buy me a coffee",
            "PlayTranslate is free, support development on Ko-Fi",
            "https://go.playtranslate.com/donate")
        applyDonateRowTint()
    }

    /** Tints the donate row with a soft 10% accent blend so the support CTA
     *  reads as a highlighted call-to-action without being loud. Bottom
     *  corners track the parent card's radius (donate is the last row in
     *  its card); top corners stay square so the row abuts the divider
     *  above it cleanly. The row's selectableItemBackground ripple gets
     *  moved to foreground so the custom background doesn't kill it. */
    private fun applyDonateRowTint() {
        val parentCard = rowDonate.parent?.parent as? MaterialCardView
        val radiusPx = parentCard?.radius
            ?: ctx.resources.getDimension(R.dimen.pt_radius)
        val accent = ctx.themeColor(R.attr.ptAccent)
        val card = ctx.themeColor(R.attr.ptCard)
        val effectiveDivider = compositeOver(ctx.themeColor(R.attr.ptDivider), card)
        val fill = blendColors(accent, card, 0.10f)
        val stroke = blendColors(accent, effectiveDivider, 0.10f)
        val dp = ctx.resources.displayMetrics.density
        rowDonate.background = GradientDrawable().apply {
            setColor(fill)
            setStroke((1 * dp).toInt(), stroke)
            cornerRadii = floatArrayOf(
                0f, 0f,
                0f, 0f,
                radiusPx, radiusPx,
                radiusPx, radiusPx,
            )
        }
        val ripple = ctx.obtainStyledAttributes(
            intArrayOf(android.R.attr.selectableItemBackground)
        ).run {
            val d = getDrawable(0)
            recycle()
            d
        }
        rowDonate.foreground = ripple
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

        // Live-mode debug logging
        val rowLiveModeDebug = root.findViewById<View>(R.id.rowLiveModeDebug)
        val switchLiveModeDebug = rowLiveModeDebug.findViewById<MaterialSwitch>(R.id.switchRowToggle)
        rowLiveModeDebug.findViewById<TextView>(R.id.tvRowTitle).text = "Log live-mode pinhole metrics"
        switchLiveModeDebug.isChecked = prefs.debugLiveMode
        switchLiveModeDebug.setOnCheckedChangeListener { _, checked ->
            prefs.debugLiveMode = checked
        }
        rowLiveModeDebug.setOnClickListener { switchLiveModeDebug.toggle() }

        // Save OCR captures as seeds (for golden-set curation)
        val rowSaveOcrSeed = root.findViewById<View>(R.id.rowSaveOcrSeed)
        val switchSaveOcrSeed = rowSaveOcrSeed.findViewById<MaterialSwitch>(R.id.switchRowToggle)
        rowSaveOcrSeed.findViewById<TextView>(R.id.tvRowTitle).text = "Save OCR captures as seeds"
        switchSaveOcrSeed.isChecked = prefs.debugSaveOcrSeed
        switchSaveOcrSeed.setOnCheckedChangeListener { _, checked ->
            prefs.debugSaveOcrSeed = checked
        }
        rowSaveOcrSeed.setOnClickListener { switchSaveOcrSeed.toggle() }

        // Log OCR grouping decisions (per-pair MERGE/SPLIT + numeric reason)
        val rowLogGrouping = root.findViewById<View>(R.id.rowLogGrouping)
        val switchLogGrouping = rowLogGrouping.findViewById<MaterialSwitch>(R.id.switchRowToggle)
        rowLogGrouping.findViewById<TextView>(R.id.tvRowTitle).text = "Log OCR grouping decisions"
        switchLogGrouping.isChecked = prefs.debugLogGrouping
        switchLogGrouping.setOnCheckedChangeListener { _, checked ->
            prefs.debugLogGrouping = checked
            OcrManager.instance.debugLogGroupingEnabled = checked
        }
        rowLogGrouping.setOnClickListener { switchLogGrouping.toggle() }

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
            buildPillToggle(
                container = overlayModeToggleContainer,
                options = listOf("Translation" to OverlayMode.TRANSLATION, hintLabel to OverlayMode.FURIGANA),
                selected = prefs.overlayMode,
                onSelect = { mode ->
                    prefs.overlayMode = mode
                    if (CaptureService.instance?.isLive == true) {
                        // TODO(P1): swap for instant rebuild — see twin TODO
                        //   on the earlier overlayMode pill toggle.
                        CaptureService.instance?.stopLive()
                    }
                    callbacks.onOverlayModeChanged()
                }
            )
        }

        // Hotkey section is always visible (translation hotkey is useful
        // regardless of source language). Only the Furigana/Pinyin row
        // toggles with hasHintText.
        hotkeySection.visibility = View.VISIBLE
        if (hasHintText) {
            rowHotkeyFurigana.visibility = View.VISIBLE
            dividerHotkeyFurigana.visibility = View.VISIBLE
            rowHotkeyFurigana.findViewById<TextView>(R.id.tvRowTitle)?.text =
                "Hotkey: hold to show $hintLabel"
        } else {
            rowHotkeyFurigana.visibility = View.GONE
            dividerHotkeyFurigana.visibility = View.GONE
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
