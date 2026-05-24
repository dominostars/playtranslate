package com.playtranslate.ui

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import com.playtranslate.capturableDisplays
import com.playtranslate.capture.CaptureBackendResolver
import com.playtranslate.capture.CaptureLifecycle
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
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
import com.playtranslate.tts.TtsEngine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Which accessibility-gated Settings action raised the "accessibility
 *  required" alert — selects the alert's explanatory copy. */
enum class AccessibilityRequirement { MULTI_DISPLAY, HOTKEY, ENHANCED_AUTO_TRANSLATE }

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
        /** Tap on an LLM-backend row's title area (not the switch) — open
         *  the per-backend settings sub-screen. [id] selects which provider
         *  (currently "openai" or "gemini"). */
        fun openLlmBackendSettings(id: BackendId)

        /** Tap on the "Model" sub-cell under an enabled LLM backend row in
         *  Settings (or the Model row inside the backend's sub-screen) —
         *  open the full-screen model picker for [id]. */
        fun openLlmModelPicker(id: BackendId)
        /** Tap on the TTS "Voice" cell — open the per-language voice picker. */
        fun openTtsVoicePicker()
        /** Tap on the TTS no-engine cell — show the "No Text-to-Speech" alert. */
        fun openTtsSetup()
        fun showHotkeyDialog(title: String?, onSet: (List<Int>) -> Unit, onCancel: () -> Unit)

        /** Show an OverlayAlert explaining that [requirement] needs the
         *  accessibility service enabled. The accent button opens
         *  Accessibility Settings; the cancel button just dismisses. */
        fun showAccessibilityRequiredAlert(requirement: AccessibilityRequirement)

        /** MediaProjection backend: prompt for the screen-record consent and,
         *  on grant, bring up the floating game-screen controls. Refreshes the
         *  overlay-icon switch when the flow settles so it mirrors the result. */
        fun requestMediaProjectionControls()
        fun showAnkiDeckPicker(onDeckSelected: () -> Unit)
        fun showAnkiCardTypePicker(onPicked: () -> Unit)
        fun showAnkiCardTypeMapping(onSaved: () -> Unit)
        fun getScrollY(): Int

        /** Tap on the TranslateGemma row when the model isn't installed.
         *  Implementer shows the download progress dialog, runs the shared
         *  [com.playtranslate.translation.llm.OnDeviceLlmDownloader] configured
         *  for TG, and on success flips [Prefs.translateGemmaEnabled] = true
         *  (which fires the pref-change listener → status refresh + reconcile).
         *
         *  Implementer is responsible for running the pre-download availMem
         *  gate (OverlayAlert with Check-again / Cancel) before kicking off
         *  the actual download. */
        fun startTranslateGemmaDownload()

        /** Tap on the TranslateGemma row when the model is already downloaded
         *  but the switch is currently off. Implementer runs the pre-toggle
         *  availMem gate (OverlayAlert with Check-again / Delete model / Cancel)
         *  and on success flips [Prefs.translateGemmaEnabled] = true. The
         *  renderer optimistically flips the switch on; the Cancel / Delete
         *  branches must revert it via [refreshTranslategemmaSwitch]. */
        fun enableInstalledTranslateGemma()

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

        /** Tap on the Qwen row when the model is already downloaded but the
         *  switch is currently off. Mirrors [enableInstalledTranslateGemma]
         *  for Qwen; revert via [refreshQwenSwitch] on Cancel / Delete. */
        fun enableInstalledQwen()

        /** Tap on the Qwen row when it's currently enabled. Mirrors
         *  [showTranslateGemmaDisableDialog] for Qwen — the Cancel branch must
         *  call [SettingsRenderer.refreshQwenSwitch] to revert the switch. */
        fun showQwenDisableDialog()

        /** Tap on the MNN-backed Qwen row when the model isn't installed —
         *  start a zip download via the OnDeviceLlmDownloader ZipExtract
         *  commit path. Mirrors [startQwenDownload]. */
        fun startQwenMnnDownload()

        /** Tap on the MNN-backed Qwen row when the model is already extracted
         *  but the switch is off. Revert via [refreshQwenMnnSwitch] on
         *  Cancel / Delete. */
        fun enableInstalledQwenMnn()

        /** Tap on the MNN-backed Qwen row when it's currently enabled. */
        fun showQwenMnnDisableDialog()

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

    private val rowOverlayIcon: View = root.findViewById(R.id.rowOverlayIcon)
    private val overlayIconPreviewSlot: FrameLayout = rowOverlayIcon.findViewById(R.id.overlayIconPreviewSlot)
    private var overlayIconPreview: FloatingOverlayIcon? = null
    // "On the floating icon" cell — text + icon tints are driven by the
    // capture lifecycle. Active: title = ptTextMuted (GroupHeader default),
    // gesture text + icons = ptText. Disabled: everything shifts down to
    // ptTextHint so the whole cell reads as a single recessed group.
    private val tvOverlayIconTitle: TextView = rowOverlayIcon.findViewById(R.id.tvRowTitle)
    private val tvGestureDrag: TextView = rowOverlayIcon.findViewById(R.id.tvGestureDrag)
    private val tvGestureHold: TextView = rowOverlayIcon.findViewById(R.id.tvGestureHold)
    private val tvGestureTap: TextView = rowOverlayIcon.findViewById(R.id.tvGestureTap)
    private val iconGestureDrag: ImageView = rowOverlayIcon.findViewById(R.id.iconGestureDrag)
    private val iconGestureHold: ImageView = rowOverlayIcon.findViewById(R.id.iconGestureHold)
    private val iconGestureTap: ImageView = rowOverlayIcon.findViewById(R.id.iconGestureTap)

    private val btnCaptureLifecycle: ShimmerButton = root.findViewById(R.id.btnCaptureLifecycle)

    // ── Power status cell (top row of the unified top-section card) ──────
    // The cell itself is the tap target; the inner switch is non-clickable
    // and follows the row tap. All per-state styling — icon block bg + stroke,
    // glyph tint, dot + halo, state-label text + colour, title + subtitle
    // copy, switch checked-ness — is applied in stylePowerCard(). The
    // divider sits between the power cell and the Game-screen-controls row
    // and is hidden in lock-step with the cell (a11y dual-screen has no
    // activate control and the cell is GONE).
    private val powerCard: ShimmerLinearLayout = root.findViewById(R.id.powerCard)
    private val dividerPowerCell: View = root.findViewById(R.id.dividerPowerCell)
    private val powerIconBlock: FrameLayout = root.findViewById(R.id.powerIconBlock)
    private val powerIconGlyph: ImageView = root.findViewById(R.id.powerIconGlyph)
    private val powerStateHalo: View = root.findViewById(R.id.powerStateHalo)
    private val powerStateDot: View = root.findViewById(R.id.powerStateDot)
    private val powerStateLabel: TextView = root.findViewById(R.id.powerStateLabel)
    private val powerTitle: TextView = root.findViewById(R.id.powerTitle)
    private val powerSubtitle: TextView = root.findViewById(R.id.powerSubtitle)
    private val powerSwitch: MaterialSwitch = root.findViewById(R.id.powerSwitch)

    // Game Screen Controls — the floating-icon visibility toggle that takes
    // the power cell's slot on the accessibility backend in dual-screen.
    // Exactly one of {powerCard, rowGameScreenControls} is visible at a time.
    private val rowGameScreenControls: View = root.findViewById(R.id.rowGameScreenControls)
    private val switchGameScreenControls: MaterialSwitch =
        root.findViewById(R.id.switchGameScreenControls)

    private val rowAddQuickTile: View = root.findViewById(R.id.rowAddQuickTile)

    private val overlayModeSection: View = root.findViewById(R.id.overlayModeSection)
    private val overlayModeToggleContainer: FrameLayout = root.findViewById(R.id.overlayModeToggleContainer)

    // Enhanced auto-translate row — top of the AUTO-TRANSLATE card.
    // Reflects PlayTranslateAccessibilityService.isEnabled: when off, a
    // MaterialSwitch + the "More responsive…" pitch, tapping the row
    // shows the a11y-required alert. When on, a check mark + "Enabled",
    // row becomes non-clickable. State sync happens in
    // refreshEnhancedAutoTranslateRow, which the bottom sheet calls in
    // onResume so returning from system Accessibility Settings picks up
    // the grant immediately.
    private val rowEnhancedAutoTranslate: View =
        root.findViewById(R.id.rowEnhancedAutoTranslate)
    private val tvEnhancedAutoTranslateSubtitle: TextView =
        rowEnhancedAutoTranslate.findViewById(R.id.tvEnhancedAutoTranslateSubtitle)
    private val switchEnhancedAutoTranslate: MaterialSwitch =
        rowEnhancedAutoTranslate.findViewById(R.id.switchEnhancedAutoTranslate)
    private val checkEnhancedAutoTranslate: ImageView =
        rowEnhancedAutoTranslate.findViewById(R.id.checkEnhancedAutoTranslate)

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
    private val dividerAnkiCardType: View = root.findViewById(R.id.dividerAnkiCardType)
    private val rowAnkiCardType: View = root.findViewById(R.id.rowAnkiCardType)
    private val dividerAnkiEditMapping: View = root.findViewById(R.id.dividerAnkiEditMapping)
    private val rowAnkiEditMapping: View = root.findViewById(R.id.rowAnkiEditMapping)
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
        setupCaptureLifecycleButton()
        setupGameScreenControlsRow()
        setupLanguageSection()
        setupOnScreenControls()
        setupAutoTranslateSection()
        setupHotkeySection()
        setupCaptureDisplaySection()
        setupTranslationServiceSection()
        setupAnkiSection()
        refreshTtsSection()
        setupAppearanceSection()
        setupSupportSection()
        setupDebugSection()
        setupFooter()
    }

    // ── Group headers ────────────────────────────────────────────────────

    private fun setupGroupHeaders() {
        setGroupHeader(R.id.headerLanguage, "LANGUAGE")
        // ON-SCREEN CONTROLS has no header — its card sits directly under
        // the power card as part of the top section (no headerOnScreen in
        // dialog_settings.xml).
        setGroupHeader(R.id.headerAutoTranslate, "AUTO-TRANSLATE")
        setGroupHeader(R.id.headerHotkeys, "HOTKEYS")
        setGroupHeader(R.id.headerCaptureDisplay, "CAPTURE DISPLAY")
        setGroupHeader(R.id.headerOnlineTranslations, "ONLINE TRANSLATIONS")
        setGroupHeader(R.id.headerOfflineTranslations, "OFFLINE TRANSLATIONS")
        setGroupHeader(R.id.headerAnki, "ANKI")
        setGroupHeader(R.id.headerTextToSpeech, "TEXT-TO-SPEECH")
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

    // ── Text-to-Speech section ────────────────────────────────────────────

    /** (Re)resolve TTS engine availability and populate the section: a "Voice"
     *  value-cell when an engine is usable, otherwise a cell that opens the
     *  "No Text-to-Speech" alert. The section stays hidden until the async
     *  check resolves (no empty-card flash). Also called from onResume so a
     *  voice picked in the detail screen — or a game-language change — shows. */
    fun refreshTtsSection() {
        val section = root.findViewById<View>(R.id.ttsSection) ?: return
        val rowNoEngine = root.findViewById<View>(R.id.rowTtsNoEngine)
        val rowVoice = root.findViewById<View>(R.id.rowTtsVoice)
        lifecycleScope.launch {
            val lang = prefs.sourceLangId
            if (TtsEngine.isEngineAvailable(ctx)) {
                val voices = TtsEngine.voicesFor(ctx, lang)
                val savedName = prefs.ttsVoiceName(lang)
                val idx = if (savedName == null) -1
                          else voices.indexOfFirst { it.name == savedName }
                rowVoice.findViewById<TextView>(R.id.tvRowTitle).text = "Voice"
                rowVoice.findViewById<TextView>(R.id.tvRowValue).text =
                    if (idx >= 0) "Voice ${idx + 1}" else "Default"
                rowVoice.setOnClickListener { callbacks.openTtsVoicePicker() }
                rowVoice.visibility = View.VISIBLE
                rowNoEngine.visibility = View.GONE
            } else {
                rowNoEngine.findViewById<TextView>(R.id.tvRowTitle).text =
                    "Set up Text-to-Speech"
                val sub = rowNoEngine.findViewById<TextView>(R.id.tvRowSubtitle)
                sub.text = "No speech engine is available. Tap to add one."
                sub.visibility = View.VISIBLE
                rowNoEngine.setOnClickListener { callbacks.openTtsSetup() }
                rowNoEngine.visibility = View.VISIBLE
                rowVoice.visibility = View.GONE
            }
            section.visibility = View.VISIBLE
        }
    }

    /** Tint the Update language packs card with the warning attention color
     *  — blend recipe `blendColors(attention, baseCard, 0.20f)` with a full-
     *  strength stroke. Conveys "this pack is degraded; tap to fix" with a
     *  consistent visual weight for recoverable-state warnings. */
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
        // "On the floating icon" — informational cell. No toggle, no click
        // handler. The floating icon's visibility is driven by the capture
        // lifecycle (and the Quick Settings tile via Prefs.showOverlayIcon)
        // — this row just teaches the user about the icon and its gestures.
        // Gesture text + icon tints come from refreshCaptureLifecycleButton().
        tvOverlayIconTitle.setText(R.string.settings_show_overlay_icon)
        overlayIconPreviewSlot.visibility = View.VISIBLE
        buildOverlayIconPreview()

        setupAddQuickTileRow()
    }

    /** "Add Quick Settings tile" row — only present on API 33+ (where
     *  [android.app.StatusBarManager.requestAddTileService] exists) and only
     *  while the user hasn't already confirmed adding the tile (tracked in
     *  [Prefs.quickTileAdded] from the request callback). Pre-Tiramisu and
     *  post-add the row stays hidden; the floating-icon footer's own
     *  hairline divider (from bg_word_tatoeba_attribution) is then enough
     *  separation from the power cell.
     *
     *  Also re-syncs dividerPowerCell — it's the divider between the power
     *  cell and the quick-tile row, so it should hide whenever the
     *  quick-tile row is hidden (the footer's own top divider takes over
     *  the separation duty). */
    private fun setupAddQuickTileRow() {
        val apiSupported = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU
        val visible = apiSupported && !prefs.quickTileAdded
        rowAddQuickTile.visibility = if (visible) View.VISIBLE else View.GONE
        refreshDividerPowerCellVisibility()
        if (!visible) return

        rowAddQuickTile.findViewById<TextView>(R.id.tvRowTitle).text =
            "Add Quick Settings tile"
        val subtitle = rowAddQuickTile.findViewById<TextView>(R.id.tvRowSubtitle)
        subtitle.text = "Toggle PlayTranslate from your status bar"
        subtitle.visibility = View.VISIBLE
        rowAddQuickTile.findViewById<ImageView>(R.id.ivRowIcon)
            ?.setImageResource(R.drawable.ic_add)
        rowAddQuickTile.setOnClickListener { requestAddQuickTile() }
    }

    /** dividerPowerCell sits between the top-slot cell (the power cell or the
     *  Game Screen Controls row — exactly one is always present) and the
     *  quick-tile row. It shows only when the quick-tile row below is also
     *  visible; when that row is hidden the floating-icon footer's own top
     *  divider provides the separation, and a divider here would
     *  double-stroke against it.
     *
     *  Called from both [refreshCaptureLifecycleButton] (which toggles the
     *  top-slot cell) and [setupAddQuickTileRow] (which toggles the
     *  quick-tile row) so the rule stays in sync with whichever changed
     *  last. */
    private fun refreshDividerPowerCellVisibility() {
        val cellAbove = powerCard.visibility == View.VISIBLE ||
            rowGameScreenControls.visibility == View.VISIBLE
        dividerPowerCell.visibility =
            if (cellAbove && rowAddQuickTile.visibility == View.VISIBLE) View.VISIBLE
            else View.GONE
    }

    /** Pulls the gesture string [stringRes] (which carries an inline `<b>`
     *  on the leading verb word), copies it into a SpannableStringBuilder,
     *  and overlays a [ForegroundColorSpan] of [color] across the same
     *  range as the inline BOLD — so the verb word gets the accent (or
     *  the disabled hint) colour without needing to hardcode verb-word
     *  lengths in code or duplicate the string.
     *
     *  Returns a fresh SpannableStringBuilder each call — cheap (one
     *  StyleSpan lookup, one ForegroundColorSpan applied) and avoids the
     *  resource cache reusing the same Spanned instance across refreshes
     *  with stale colours layered on. */
    private fun withVerbColored(stringRes: Int, color: Int): SpannableStringBuilder {
        val text = ctx.getText(stringRes)
        val sb = SpannableStringBuilder(text)
        val boldSpan = sb.getSpans(0, sb.length, StyleSpan::class.java)
            .firstOrNull { it.style == Typeface.BOLD }
        if (boldSpan != null) {
            val start = sb.getSpanStart(boldSpan)
            val end = sb.getSpanEnd(boldSpan)
            sb.setSpan(
                ForegroundColorSpan(color),
                start, end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        return sb
    }

    @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.TIRAMISU)
    private fun requestAddQuickTile() {
        val statusBarManager = ctx.getSystemService(android.app.StatusBarManager::class.java)
            ?: return
        val component = android.content.ComponentName(ctx, PlayTranslateTileService::class.java)
        val icon = android.graphics.drawable.Icon.createWithResource(ctx, R.drawable.ic_qs_tile)
        val label = ctx.getString(R.string.tile_label)
        statusBarManager.requestAddTileService(
            component,
            label,
            icon,
            ContextCompat.getMainExecutor(ctx),
        ) { result ->
            when (result) {
                android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED,
                android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> {
                    prefs.quickTileAdded = true
                    setupAddQuickTileRow()
                }
                // TILE_NOT_ADDED / error codes: keep the row visible so the
                // user can try again.
            }
        }
    }

    // ── Turn On / Turn Off PlayTranslate ───────────────────────────────────────

    private fun setupCaptureLifecycleButton() {
        val onClick = View.OnClickListener {
            when {
                CaptureLifecycle.isActive(ctx) -> {
                    CaptureLifecycle.deactivate(ctx)
                    refreshCaptureLifecycleButton()
                }
                !CaptureBackendResolver.active().requiresAccessibilityService ->
                    // MediaProjection — the consent flow is Activity-scoped;
                    // the callback refreshes the buttons once it settles.
                    callbacks.requestMediaProjectionControls()
                CaptureLifecycle.activateAccessibility(ctx) -> {
                    refreshCaptureLifecycleButton()
                }
                else -> showOverlayIconA11yAlert()
            }
        }
        btnCaptureLifecycle.setOnClickListener(onClick)
        powerCard.setOnClickListener(onClick)

        // The nav-bar ShimmerButton fades in as the user scrolls past the
        // power card. The card itself does not fade — it scrolls out of view
        // naturally and the toolbar takes over as the sticky reminder.
        settingsScrollView.setOnScrollChangeListener(
            androidx.core.widget.NestedScrollView.OnScrollChangeListener { _, _, scrollY, _, _ ->
                updateCaptureButtonCrossfade(scrollY)
            }
        )
        refreshCaptureLifecycleButton()
    }

    /** Wire the Game Screen Controls row — the floating-icon visibility
     *  toggle that occupies the power cell's slot on the accessibility
     *  backend in dual-screen, where capture itself is always on. Mirrors
     *  the QS tile's accessibility on/off branch so the Settings toggle and
     *  the tile stay in sync. [refreshCaptureLifecycleButton] owns the row's
     *  visibility and the switch's checked state. */
    private fun setupGameScreenControlsRow() {
        rowGameScreenControls.setOnClickListener {
            if (prefs.showOverlayIcon) {
                // Off — the canonical "icon goes away" path (writes the pref,
                // stops live mode, hides the icon, refreshes the tile), shared
                // with the QS tile and CaptureLifecycle.deactivate.
                PlayTranslateAccessibilityService.disable(
                    ctx, "settings_game_screen_controls_off"
                )
            } else {
                // On — bring the floating icon back.
                prefs.showOverlayIcon = true
                CaptureBackendResolver.activeOverlayUi?.reconcileFloatingIcons()
                PlayTranslateTileService.TileSync.refresh(ctx)
            }
            refreshCaptureLifecycleButton()
        }
    }

    /** Show / hide + style the top-slot capture controls against the current
     *  [CaptureLifecycle] state:
     *   - Power cell + nav-bar button — shown wherever there is a capture
     *     lifecycle to start / stop.
     *   - Game Screen Controls row — shown instead on the accessibility
     *     backend in dual-screen, where "active" is always true and the
     *     floating icon is the only thing left to toggle.
     *
     *  Also recolours the adjacent "On the floating icon" footer: while the
     *  icon is hidden its title + gesture text + gesture icons all drop to
     *  ptTextHint (the next-darker theme step below ptTextMuted) so the whole
     *  cell reads as a single recessed group, and the docked-icon preview
     *  drops to 50% alpha as a stronger "this is currently dark" cue.
     */
    fun refreshCaptureLifecycleButton() {
        // Exactly one of {power cell, Game Screen Controls row} occupies the
        // top slot. The power cell shows wherever there is a capture lifecycle
        // to start / stop; the Game Screen Controls row takes over on the
        // accessibility backend in dual-screen, where capture is always on.
        val showPowerCell = CaptureLifecycle.hasActivateControl(ctx)
        val powerVisibility = if (showPowerCell) View.VISIBLE else View.GONE
        btnCaptureLifecycle.visibility = powerVisibility
        powerCard.visibility = powerVisibility
        rowGameScreenControls.visibility =
            if (showPowerCell) View.GONE else View.VISIBLE
        switchGameScreenControls.isChecked = prefs.showOverlayIcon
        // dividerPowerCell visibility depends on the cell above it and the
        // quick-tile row below — see refreshDividerPowerCellVisibility.
        refreshDividerPowerCellVisibility()
        val active = CaptureLifecycle.isActive(ctx)
        // The floating-icon footer is lit when the icon is actually on the
        // game screen, dim otherwise. That tracks `active` everywhere except
        // a11y dual-screen, where capture is always active but the Game
        // Screen Controls toggle gates the icon — there it tracks
        // showOverlayIcon instead.
        val iconLit = if (showPowerCell) active else prefs.showOverlayIcon
        val titleColor = ctx.themeColor(
            if (iconLit) R.attr.ptTextMuted else R.attr.ptTextHint
        )
        // Gesture line has two colour zones: the rest of the line (baseColor)
        // and the leading bold verb (verbColor). Lit accents the verb +
        // matching icon; dim flattens both into the same muted hint colour as
        // the rest of the line.
        val baseColor = ctx.themeColor(
            if (iconLit) R.attr.ptText else R.attr.ptTextHint
        )
        val verbColor = ctx.themeColor(
            if (iconLit) R.attr.ptAccent else R.attr.ptTextHint
        )
        val iconTint = ColorStateList.valueOf(verbColor)
        tvOverlayIconTitle.setTextColor(titleColor)
        tvGestureDrag.text = withVerbColored(R.string.overlay_icon_gesture_drag, verbColor)
        tvGestureHold.text = withVerbColored(R.string.overlay_icon_gesture_hold, verbColor)
        tvGestureTap.text  = withVerbColored(R.string.overlay_icon_gesture_tap,  verbColor)
        tvGestureDrag.setTextColor(baseColor)
        tvGestureHold.setTextColor(baseColor)
        tvGestureTap.setTextColor(baseColor)
        iconGestureDrag.imageTintList = iconTint
        iconGestureHold.imageTintList = iconTint
        iconGestureTap.imageTintList = iconTint
        overlayIconPreviewSlot.alpha = if (iconLit) 1f else 0.5f
        if (!showPowerCell) return
        styleCaptureButton(btnCaptureLifecycle, active)
        stylePowerCard(active)
        // A refresh can land while the list is already scrolled — re-apply
        // the toolbar fade-in for the current offset.
        updateCaptureButtonCrossfade(settingsScrollView.scrollY)
    }

    /** Filled-accent ("Turn On") / outlined ("Turn Off") styling for the
     *  nav-bar ShimmerButton. The in-content power card uses a different
     *  shape entirely — see [stylePowerCard]. */
    private fun styleCaptureButton(btn: MaterialButton, active: Boolean) {
        btn.setText(
            if (active) R.string.capture_lifecycle_stop
            else R.string.capture_lifecycle_start
        )
        if (active) {
            // Outlined — the system is running.
            btn.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            btn.setTextColor(ctx.themeColor(R.attr.ptText))
            btn.iconTint = ColorStateList.valueOf(ctx.themeColor(R.attr.ptText))
            btn.strokeColor = ColorStateList.valueOf(ctx.themeColor(R.attr.ptTextMuted))
            btn.strokeWidth = (1 * ctx.resources.displayMetrics.density).toInt()
        } else {
            // Filled accent — the prominent call to action.
            btn.backgroundTintList = ColorStateList.valueOf(ctx.themeColor(R.attr.ptAccent))
            btn.setTextColor(ctx.themeColor(R.attr.ptAccentOn))
            btn.iconTint = ColorStateList.valueOf(ctx.themeColor(R.attr.ptAccentOn))
            btn.strokeWidth = 0
        }
    }

    /** Render the power status card for [active]:
     *  - Icon block: ptElevated fill / ptOutline stroke / muted glyph (Off);
     *    ptAccentTint fill / ptAccent stroke / accent glyph (On).
     *  - State label: ALL-CAPS "OFF"/"ON" in ptTextMuted/ptAccent.
     *  - LED dot: ptTextHint (Off) or ptAccent with a ptAccentTint halo (On).
     *  - Subtitle: per-state copy from strings.xml.
     *  - Switch: checked = active, driven by state (NOT optimistically — the
     *    refresh chain runs after the lifecycle call settles).
     */
    private fun stylePowerCard(active: Boolean) {
        val density = ctx.resources.displayMetrics.density

        // Icon block: rounded 12dp rect with theme-tinted fill + stroke.
        powerIconBlock.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 12f * density
            setColor(ctx.themeColor(if (active) R.attr.ptAccentTint else R.attr.ptElevated))
            setStroke(
                (1 * density).toInt(),
                ctx.themeColor(if (active) R.attr.ptAccent else R.attr.ptOutline),
            )
        }
        powerIconGlyph.imageTintList = ColorStateList.valueOf(
            ctx.themeColor(if (active) R.attr.ptAccent else R.attr.ptTextMuted)
        )

        // State label + LED dot + halo.
        powerStateLabel.setText(
            if (active) R.string.capture_lifecycle_state_on
            else R.string.capture_lifecycle_state_off
        )
        powerStateLabel.setTextColor(
            ctx.themeColor(if (active) R.attr.ptAccent else R.attr.ptTextMuted)
        )
        powerStateDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ctx.themeColor(if (active) R.attr.ptAccent else R.attr.ptTextHint))
        }
        if (active) {
            powerStateHalo.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ctx.themeColor(R.attr.ptAccentTint))
            }
            powerStateHalo.visibility = View.VISIBLE
        } else {
            powerStateHalo.visibility = View.INVISIBLE
        }

        // Title + supporting text + switch state. The title swaps with state
        // to honour the "permission, not action" framing — Off is the CTA
        // ("Allow screen capture"), On is the calm permission state
        // ("Screen capture permitted"). Subtitle mirrors: Off describes
        // what's required; On describes the resulting capability.
        powerTitle.setText(
            if (active) R.string.capture_lifecycle_on_title
            else R.string.capture_lifecycle_off_title
        )
        powerSubtitle.setText(
            if (active) R.string.capture_lifecycle_on_subtitle
            else R.string.capture_lifecycle_off_subtitle
        )
        powerSwitch.isChecked = active
    }

    /** Fade the nav-bar ShimmerButton in as the user scrolls past the power
     *  card. The card itself is never faded — it just scrolls naturally with
     *  the content. */
    private fun updateCaptureButtonCrossfade(scrollY: Int) {
        val distance = 72f * ctx.resources.displayMetrics.density
        val t = (scrollY / distance).coerceIn(0f, 1f)
        btnCaptureLifecycle.alpha = t
    }

    // ── Turn On/Off attention shimmer ─────────────────────────────────────
    // A brief light sweep every few seconds draws the eye to the controls
    // ONLY while capture is off. Once the user has enabled it, the accent
    // tint and LED-on state already announce themselves on both surfaces
    // (card + nav-bar button) and a sweep over them would be noise.

    private val shimmerHandler = Handler(Looper.getMainLooper())
    private val shimmerRunnable = object : Runnable {
        override fun run() {
            if (CaptureLifecycle.hasActivateControl(ctx) &&
                !CaptureLifecycle.isActive(ctx)
            ) {
                btnCaptureLifecycle.shimmer()
                powerCard.shimmer()
            }
            shimmerHandler.postDelayed(this, 5_000L)
        }
    }

    /** Begin the periodic attention shimmer on the Turn On / Turn Off control. */
    fun startCaptureButtonShimmer() {
        shimmerHandler.removeCallbacks(shimmerRunnable)
        shimmerHandler.postDelayed(shimmerRunnable, 5_000L)
    }

    /** Stop the periodic shimmer — call when the settings screen pauses. */
    fun stopCaptureButtonShimmer() {
        shimmerHandler.removeCallbacks(shimmerRunnable)
    }

    /** The accessibility-required dialog for the floating icon — shared by the
     *  dual-screen toggle and the accessibility-backend Start path. */
    private fun showOverlayIconA11yAlert() {
        AlertDialog.Builder(ctx)
            .setTitle(R.string.overlay_icon_a11y_required_title)
            .setMessage(R.string.overlay_icon_a11y_required_message)
            .setPositiveButton(R.string.btn_open_a11y_settings) { _, _ ->
                ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Build (or rebuild) the floating-icon preview shown in the "On the
     *  floating icon" cell. The icon view draws a full notional 56dp circle
     *  pushed off the screen edge — the 40dp-wide slot clips it to the
     *  visible quarter. Non-interactive — never hosted as a window. */
    private fun buildOverlayIconPreview() {
        destroyOverlayIconPreview()
        overlayIconPreviewSlot.removeAllViews()
        val preview = FloatingOverlayIcon(ctx).apply {
            isClickable = false
            isFocusable = false
        }
        overlayIconPreviewSlot.addView(
            preview,
            FrameLayout.LayoutParams(preview.viewSizePx, preview.viewSizePx),
        )
        overlayIconPreview = preview
    }

    /** Recycle the preview's bitmap. Call before the renderer is discarded. */
    fun destroyOverlayIconPreview() {
        overlayIconPreview?.destroy()
        overlayIconPreview = null
    }

    // ── Auto-translate section ───────────────────────────────────────────

    private fun setupEnhancedAutoTranslateRow() {
        rowEnhancedAutoTranslate.setOnClickListener {
            // Click only fires while a11y is off (refreshEnhanced…
            // sets isClickable=false otherwise). The alert routes the
            // user to system Accessibility Settings; the row picks up
            // the grant when the sheet's onResume runs the refresh.
            callbacks.showAccessibilityRequiredAlert(
                AccessibilityRequirement.ENHANCED_AUTO_TRANSLATE
            )
        }
        refreshEnhancedAutoTranslateRow()
    }

    /** Re-sync the Enhanced auto-translate row against
     *  [PlayTranslateAccessibilityService.isEnabled]. Called from setup
     *  and from the bottom sheet's onResume so a user returning from
     *  system Accessibility Settings sees the row flip to "Enabled" +
     *  check mark immediately. */
    fun refreshEnhancedAutoTranslateRow() {
        val enabled = PlayTranslateAccessibilityService.isEnabled(ctx)
        if (enabled) {
            tvEnhancedAutoTranslateSubtitle.setText(
                R.string.enhanced_auto_translate_subtitle_on
            )
            switchEnhancedAutoTranslate.visibility = View.GONE
            checkEnhancedAutoTranslate.visibility = View.VISIBLE
            // Non-clickable while on — the row is a status indicator at
            // that point; selectableItemBackground also suppresses its
            // ripple when isClickable is false, so no stray feedback.
            rowEnhancedAutoTranslate.isClickable = false
            rowEnhancedAutoTranslate.isFocusable = false
        } else {
            tvEnhancedAutoTranslateSubtitle.setText(
                R.string.enhanced_auto_translate_subtitle_off
            )
            switchEnhancedAutoTranslate.visibility = View.VISIBLE
            // Switch stays unchecked — the user grants accessibility via
            // system Settings, not by flipping this switch. The grant
            // path then routes back through refreshEnhancedAutoTranslateRow
            // and the switch is replaced by the check mark.
            switchEnhancedAutoTranslate.isChecked = false
            checkEnhancedAutoTranslate.visibility = View.GONE
            rowEnhancedAutoTranslate.isClickable = true
            rowEnhancedAutoTranslate.isFocusable = true
        }
    }

    private fun setupAutoTranslateSection() {
        setupEnhancedAutoTranslateRow()

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
                // Hotkeys are detected via AccessibilityService.onKeyEvent —
                // the setup dialog can't capture keystrokes without the
                // service bound. Revert the optimistic flip and explain.
                if (!PlayTranslateAccessibilityService.isEnabled(ctx)) {
                    switch.isChecked = false
                    callbacks.showAccessibilityRequiredAlert(AccessibilityRequirement.HOTKEY)
                    return@setOnCheckedChangeListener
                }
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
        // Multi-display capture runs through the accessibility service's
        // screenshot path; without it the picker locks to the first display
        // (see buildDisplayRow).
        val a11yEnabled = PlayTranslateAccessibilityService.isEnabled(ctx)
        displayList.forEachIndexed { idx, display ->
            if (idx > 0) {
                llDisplayOptions.addView(
                    LayoutInflater.from(ctx)
                        .inflate(R.layout.settings_row_divider, llDisplayOptions, false)
                )
            }
            val isFirst = idx == 0
            val isLast = idx == displayList.size - 1
            llDisplayOptions.addView(buildDisplayRow(display, prefs, isFirst, isLast, a11yEnabled))
        }
    }

    private fun buildDisplayRow(
        display: android.view.Display,
        prefs: Prefs,
        isFirst: Boolean,
        isLast: Boolean,
        a11yEnabled: Boolean,
    ): View {
        val dp = ctx.resources.displayMetrics.density
        // Without the accessibility service the picker can't switch displays,
        // so it always renders the first display as the (locked) selection
        // regardless of the persisted multi-display set.
        val isSelected =
            if (a11yEnabled) display.displayId in prefs.captureDisplayIds else isFirst
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
            if (!a11yEnabled) {
                // Locked to the first display — tapping any other row explains
                // that switching displays needs the accessibility service.
                if (!isFirst) {
                    callbacks.showAccessibilityRequiredAlert(
                        AccessibilityRequirement.MULTI_DISPLAY
                    )
                }
                return@setOnClickListener
            }
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

    private val rowBackendGemini: View = root.findViewById(R.id.rowBackendGemini)
    private val sectionBackendGeminiModel: View = root.findViewById(R.id.sectionBackendGeminiModel)
    private val rowBackendGeminiModel: View = root.findViewById(R.id.rowBackendGeminiModel)
    private val rowBackendOpenai: View = root.findViewById(R.id.rowBackendOpenai)
    private val sectionBackendOpenaiModel: View = root.findViewById(R.id.sectionBackendOpenaiModel)
    private val rowBackendOpenaiModel: View = root.findViewById(R.id.rowBackendOpenaiModel)
    private val rowBackendDeepseek: View = root.findViewById(R.id.rowBackendDeepseek)
    private val sectionBackendDeepseekModel: View = root.findViewById(R.id.sectionBackendDeepseekModel)
    private val rowBackendDeepseekModel: View = root.findViewById(R.id.rowBackendDeepseekModel)
    private val rowBackendDeepl: View = root.findViewById(R.id.rowBackendDeepl)
    private val rowBackendLingva: View = root.findViewById(R.id.rowBackendLingva)
    private val rowBackendTranslategemma: View = root.findViewById(R.id.rowBackendTranslategemma)
    private val rowBackendQwenMnn: View = root.findViewById(R.id.rowBackendQwenMnn)
    private val dividerBackendQwenMnn: View = root.findViewById(R.id.dividerBackendQwenMnn)
    private val rowBackendQwen: View = root.findViewById(R.id.rowBackendQwen)
    // `dividerBackendQwen` is the divider *above the legacy Qwen row*. After
    // the layout rename (see dialog_settings.xml), the divider above the new
    // MNN-Qwen row is `dividerBackendQwenMnn`; the name `dividerBackendQwen`
    // continues to mean "the divider that hides with the legacy row" — see
    // [wireQwenBackendRow]'s visibility gate.
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

        wireGeminiBackendRow()
        wireGeminiModelRow()
        wireOpenAiBackendRow()
        wireOpenAiModelRow()
        wireDeepseekBackendRow()
        wireDeepseekModelRow()
        wireDeeplBackendRow()
        wireTranslateGemmaBackendRow()
        wireQwenMnnBackendRow()
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
     *  `(quality) · (speed)` for offline backends — with each part tinted
     *  by its own [Tone] via a [ForegroundColorSpan]. The TextView's base
     *  color (set by the `Text.PT.RowSubtitle` style) handles parts we
     *  don't span. The online/offline distinction itself is carried by
     *  the section header (Online vs Offline cards), and online rows skip
     *  this line entirely — the status line (line 2) carries everything
     *  the user needs for online backends (key state, usage, quota). */
    private fun setBackendLine1(row: View, backend: TranslationBackend) {
        val tv = row.findViewById<TextView>(R.id.tvRowSubtitle)
        if (backend.requiresInternet) {
            tv.visibility = View.GONE
            return
        }
        val builder = SpannableStringBuilder()

        val (qualityText, qualityTone) = when (backend.quality) {
            BackendQuality.Bad    -> ctx.getString(R.string.tr_service_quality_bad)    to Tone.Danger
            BackendQuality.Okay   -> ctx.getString(R.string.tr_service_quality_okay)   to null
            BackendQuality.Good   -> ctx.getString(R.string.tr_service_quality_good)   to null
            BackendQuality.Better -> ctx.getString(R.string.tr_service_quality_better) to null
        }
        appendMaybeColored(builder, qualityText, qualityTone)

        backend.speed?.let { speed ->
            builder.append(" · ")
            val (speedText, speedTone) = when (speed) {
                BackendSpeed.VerySlow -> ctx.getString(R.string.tr_service_speed_very_slow) to Tone.Danger
                BackendSpeed.Slow     -> ctx.getString(R.string.tr_service_speed_slow)      to Tone.Warning
                BackendSpeed.Okay     -> ctx.getString(R.string.tr_service_speed_okay)      to null
                BackendSpeed.Fast     -> ctx.getString(R.string.tr_service_speed_fast)      to null
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

    /** Refresh the Gemini switch from the current pref value AND the
     *  visibility of the inline "Model" sub-cell (only shown when the
     *  backend is enabled). Driven by the SP listener after
     *  [LlmBackendSettingsActivity] persists a save. */
    fun refreshGeminiBackendSwitch() {
        rowBackendGemini.findViewById<MaterialSwitch>(R.id.switchRowToggle)?.let {
            it.isChecked = prefs.geminiEnabled
        }
        sectionBackendGeminiModel.visibility =
            if (prefs.geminiEnabled) View.VISIBLE else View.GONE
    }

    /** Refresh the OpenAI switch from the current pref value AND the
     *  visibility of the inline "Model" sub-cell. Mirrors
     *  [refreshGeminiBackendSwitch]. */
    fun refreshOpenaiBackendSwitch() {
        rowBackendOpenai.findViewById<MaterialSwitch>(R.id.switchRowToggle)?.let {
            it.isChecked = prefs.openaiEnabled
        }
        sectionBackendOpenaiModel.visibility =
            if (prefs.openaiEnabled) View.VISIBLE else View.GONE
    }

    /** Refresh the DeepSeek switch + model sub-cell visibility. Mirrors
     *  [refreshOpenaiBackendSwitch]. */
    fun refreshDeepseekBackendSwitch() {
        rowBackendDeepseek.findViewById<MaterialSwitch>(R.id.switchRowToggle)?.let {
            it.isChecked = prefs.deepseekEnabled
        }
        sectionBackendDeepseekModel.visibility =
            if (prefs.deepseekEnabled) View.VISIBLE else View.GONE
    }

    /** Re-read the Gemini model name into the inline sub-cell's title
     *  (the row's title is the model name itself; the value column is
     *  intentionally blank). Driven by the SP listener on
     *  [Prefs.KEY_GEMINI_MODEL] so a save from [LlmModelPickerActivity]
     *  propagates here on resume. */
    fun refreshGeminiModelValue() {
        rowBackendGeminiModel.findViewById<TextView>(R.id.tvRowTitle).text = prefs.geminiModel
    }

    /** Mirrors [refreshGeminiModelValue] for OpenAI. */
    fun refreshOpenaiModelValue() {
        rowBackendOpenaiModel.findViewById<TextView>(R.id.tvRowTitle).text = prefs.openaiModel
    }

    /** Mirrors [refreshGeminiModelValue] for DeepSeek. */
    fun refreshDeepseekModelValue() {
        rowBackendDeepseekModel.findViewById<TextView>(R.id.tvRowTitle).text = prefs.deepseekModel
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

    /** Refresh the MNN-Qwen switch from the current pref value. Mirrors
     *  [refreshQwenSwitch] — driven by the same SP-listener observer. */
    fun refreshQwenMnnSwitch() {
        rowBackendQwenMnn.findViewById<MaterialSwitch>(R.id.switchRowToggle)?.let {
            it.isChecked = prefs.qwenMnnEnabled
        }
    }

    /** Re-evaluate the legacy Qwen row's visibility. Called after a successful
     *  delete (which the disable-dialog branch performs) so the row hides
     *  immediately instead of waiting for the next full re-render. */
    fun refreshQwenLegacyVisibility() {
        wireQwenBackendRow()
        // Compose line 1 again — even when the row is now GONE, the metadata
        // line update is cheap and keeps the row in sync if it later flips
        // back to VISIBLE on a re-download.
        com.playtranslate.translation.TranslationBackendRegistry.byId("qwen")?.let {
            setBackendLine1(rowBackendQwen, it)
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
        "gemini"          -> rowBackendGemini
        "openai"          -> rowBackendOpenai
        "deepseek"        -> rowBackendDeepseek
        "deepl"           -> rowBackendDeepl
        "lingva"          -> rowBackendLingva
        "translategemma"  -> rowBackendTranslategemma
        "qwen_mnn"        -> rowBackendQwenMnn
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
                    // Optimistic flip; the bottom sheet runs the availMem gate
                    // and either flips the pref on success or reverts via
                    // refreshTranslategemmaSwitch() on Cancel / Delete.
                    switch.isChecked = true
                    callbacks.enableInstalledTranslateGemma()
                } else {
                    // Need to download. The download flow runs the availMem
                    // gate, then flips the pref on success (which fires the SP
                    // listener → switch refresh).
                    callbacks.startTranslateGemmaDownload()
                }
            }
        }
    }

    /** Wire the legacy Qwen backend row (download / enable / disable-with-dialog).
     *  Mirrors [wireTranslateGemmaBackendRow] without the BuildConfig flag.
     *
     *  Visibility gate: hidden when the legacy GGUF isn't installed. The new
     *  MNN-Qwen tier replaces this for new users; the legacy row only appears
     *  for users who still have the GGUF on disk from a previous version.
     *  Re-runs on delete via [refreshQwenLegacyVisibility]. */
    private fun wireQwenBackendRow() {
        if (!com.playtranslate.translation.qwen.QwenModel.isInstalled(ctx)) {
            rowBackendQwen.visibility = View.GONE
            dividerBackendQwen.visibility = View.GONE
            return
        }
        rowBackendQwen.visibility = View.VISIBLE
        dividerBackendQwen.visibility = View.VISIBLE

        rowBackendQwen.findViewById<TextView>(R.id.tvRowTitle).text =
            ctx.getString(R.string.qwen_legacy_display_name)

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
                    // Optimistic flip; the bottom sheet runs the availMem gate
                    // and either flips the pref or reverts via refreshQwenSwitch().
                    switch.isChecked = true
                    callbacks.enableInstalledQwen()
                } else {
                    callbacks.startQwenDownload()
                }
            }
        }
    }

    /** Wire the MNN-backed Qwen row. Same shape as [wireQwenBackendRow] but
     *  routes to the `qwen_mnn` backend + [com.playtranslate.translation.qwen.QwenMnnModel]
     *  + `prefs.qwenMnnEnabled`. No visibility gate — this is the canonical
     *  Qwen row going forward; it's always visible (subject to hardware
     *  compatibility) regardless of whether the legacy GGUF is on disk. */
    private fun wireQwenMnnBackendRow() {
        rowBackendQwenMnn.findViewById<TextView>(R.id.tvRowTitle).text =
            ctx.getString(R.string.qwen_mnn_display_name)

        val backend = com.playtranslate.translation.TranslationBackendRegistry
            .byId("qwen_mnn") as? com.playtranslate.translation.llm.OnDeviceLlmBackend
        if (backend != null && configureIncompatibleHardwareRow(rowBackendQwenMnn, backend)) {
            return
        }

        val switch = rowBackendQwenMnn.findViewById<MaterialSwitch>(R.id.switchRowToggle)
        switch.isChecked = prefs.qwenMnnEnabled

        rowBackendQwenMnn.setOnClickListener {
            if (prefs.qwenMnnEnabled) {
                switch.isChecked = false
                callbacks.showQwenMnnDisableDialog()
            } else {
                val installed = com.playtranslate.translation.qwen
                    .QwenMnnModel.isInstalled(ctx)
                if (installed) {
                    // Optimistic flip; the bottom sheet runs the availMem gate
                    // and either flips the pref or reverts via refreshQwenMnnSwitch().
                    switch.isChecked = true
                    callbacks.enableInstalledQwenMnn()
                } else {
                    callbacks.startQwenMnnDownload()
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

    private fun wireGeminiBackendRow() {
        rowBackendGemini.findViewById<TextView>(R.id.tvRowTitle).text =
            ctx.getString(R.string.gemini_display_name)

        val switch = rowBackendGemini.findViewById<MaterialSwitch>(R.id.switchRowToggle)
        switch.isChecked = prefs.geminiEnabled

        // Match the DeepL UX: tap when on → disable (preserves saved key);
        // tap when off → open the sub-screen so the user can enter / verify
        // the key. The save path in the activity flips the enabled pref.
        rowBackendGemini.setOnClickListener {
            if (prefs.geminiEnabled) {
                prefs.geminiEnabled = false
                switch.isChecked = false
            } else {
                callbacks.openLlmBackendSettings("gemini")
            }
        }
    }

    private fun wireOpenAiBackendRow() {
        rowBackendOpenai.findViewById<TextView>(R.id.tvRowTitle).text =
            ctx.getString(R.string.openai_display_name)

        val switch = rowBackendOpenai.findViewById<MaterialSwitch>(R.id.switchRowToggle)
        switch.isChecked = prefs.openaiEnabled

        rowBackendOpenai.setOnClickListener {
            if (prefs.openaiEnabled) {
                prefs.openaiEnabled = false
                switch.isChecked = false
            } else {
                callbacks.openLlmBackendSettings("openai")
            }
        }
    }

    private fun wireGeminiModelRow() {
        // The row's title IS the model name (e.g. "gemini-2.5-flash"); the
        // value column is left blank — only the chevron carries "tap to
        // change" affordance. Title is rendered in the regular sans-serif
        // weight (not the medium baked into Text.PT.RowTitle) and the
        // row is compacted vertically so the sub-cell reads as a
        // secondary annotation rather than a peer to the backend row.
        applyModelRowChrome(rowBackendGeminiModel, prefs.geminiModel)
        rowBackendGeminiModel.setOnClickListener {
            callbacks.openLlmModelPicker("gemini")
        }
        sectionBackendGeminiModel.visibility =
            if (prefs.geminiEnabled) View.VISIBLE else View.GONE
    }

    private fun wireOpenAiModelRow() {
        applyModelRowChrome(rowBackendOpenaiModel, prefs.openaiModel)
        rowBackendOpenaiModel.setOnClickListener {
            callbacks.openLlmModelPicker("openai")
        }
        sectionBackendOpenaiModel.visibility =
            if (prefs.openaiEnabled) View.VISIBLE else View.GONE
    }

    private fun wireDeepseekBackendRow() {
        rowBackendDeepseek.findViewById<TextView>(R.id.tvRowTitle).text =
            ctx.getString(R.string.deepseek_display_name)

        val switch = rowBackendDeepseek.findViewById<MaterialSwitch>(R.id.switchRowToggle)
        switch.isChecked = prefs.deepseekEnabled

        rowBackendDeepseek.setOnClickListener {
            if (prefs.deepseekEnabled) {
                prefs.deepseekEnabled = false
                switch.isChecked = false
            } else {
                callbacks.openLlmBackendSettings("deepseek")
            }
        }
    }

    private fun wireDeepseekModelRow() {
        applyModelRowChrome(rowBackendDeepseekModel, prefs.deepseekModel)
        rowBackendDeepseekModel.setOnClickListener {
            callbacks.openLlmModelPicker("deepseek")
        }
        sectionBackendDeepseekModel.visibility =
            if (prefs.deepseekEnabled) View.VISIBLE else View.GONE
    }

    /** Apply the compact, muted styling to an inline "Model" sub-cell.
     *  Shared between Gemini/OpenAI (and any future LLM row) so the visual
     *  treatment stays in one place. Title is rendered in the regular
     *  sans-serif weight tinted with [R.attr.ptTextMuted] — the same color
     *  the hotkey row uses for its "Not set" placeholder — so the cell
     *  reads as a secondary annotation rather than a peer to the backend
     *  row above it. */
    private fun applyModelRowChrome(row: View, modelName: String) {
        val title = row.findViewById<TextView>(R.id.tvRowTitle)
        title.text = modelName
        title.typeface = android.graphics.Typeface.SANS_SERIF
        title.setTextColor(ctx.themeColor(R.attr.ptTextMuted))
        row.findViewById<TextView>(R.id.tvRowValue).text = ""
        val density = ctx.resources.displayMetrics.density
        val hPad = ctx.resources.getDimensionPixelSize(R.dimen.pt_row_h_padding)
        val vPad = (6 * density).toInt()
        row.setPaddingRelative(hPad, vPad, hPad, vPad)
        row.minimumHeight = (48 * density).toInt()
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
                hideAllAnkiRows()
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
                hideAllAnkiRows()
            }

            else -> {
                tvAnkiSectionTitle.visibility = View.GONE
                llAnkiPermission.visibility = View.GONE
                setupAnkiDeckRow()
                setupAnkiCardTypeRow()
                refreshAnkiEditMappingRow()
            }
        }
    }

    private fun hideAllAnkiRows() {
        rowAnkiDeck.visibility = View.GONE
        rowAnkiCardType.visibility = View.GONE
        dividerAnkiCardType.visibility = View.GONE
        rowAnkiEditMapping.visibility = View.GONE
        dividerAnkiEditMapping.visibility = View.GONE
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

    private fun setupAnkiCardTypeRow() {
        rowAnkiCardType.findViewById<TextView>(R.id.tvRowTitle).text = "Card Type"
        refreshAnkiCardTypeValue()
        rowAnkiCardType.setOnClickListener {
            callbacks.showAnkiCardTypePicker { refreshAnkiCardTypeValue() }
        }
        rowAnkiCardType.visibility = View.VISIBLE
        dividerAnkiCardType.visibility = View.VISIBLE
        validateAnkiCardType()
    }

    /**
     * Heal the saved card type against AnkiDroid's live model list. If
     * the saved id was deleted (or never existed), reset to the Default
     * sentinel. If found, refresh the saved name so a rename in
     * AnkiDroid surfaces here too.
     */
    private fun validateAnkiCardType() {
        if (prefs.ankiModelId == -1L) return
        lifecycleScope.launch {
            val models = withContext(Dispatchers.IO) { AnkiManager(ctx).getModels() }
            if (models.isEmpty()) return@launch
            val match = models.firstOrNull { it.id == prefs.ankiModelId }
            if (match == null) {
                prefs.ankiModelId = -1L
                prefs.ankiModelName = ""
                refreshAnkiCardTypeValue()
            } else if (match.name != prefs.ankiModelName) {
                prefs.ankiModelName = match.name
                refreshAnkiCardTypeValue()
            }
        }
    }

    private fun refreshAnkiCardTypeValue() {
        val freshPrefs = Prefs(ctx)
        val label = freshPrefs.ankiModelName.ifBlank {
            ctx.getString(R.string.anki_card_type_row_empty)
        }
        rowAnkiCardType.findViewById<TextView>(R.id.tvRowValue).text = label
        refreshAnkiEditMappingRow()
    }

    /**
     * The "Edit field mapping" row is only relevant when the user has
     * picked a non-default card type — the Default (PlayTranslate) path
     * uses v004's fixed two-field schema and has nothing to map.
     */
    private fun refreshAnkiEditMappingRow() {
        val hasCustom = prefs.ankiModelId != -1L
        if (!hasCustom) {
            rowAnkiEditMapping.visibility = View.GONE
            dividerAnkiEditMapping.visibility = View.GONE
            return
        }
        rowAnkiEditMapping.findViewById<TextView>(R.id.tvRowTitle).text =
            ctx.getString(R.string.anki_card_type_edit_mapping_row_label)
        rowAnkiEditMapping.findViewById<TextView>(R.id.tvRowValue).text = ""
        rowAnkiEditMapping.setOnClickListener {
            callbacks.showAnkiCardTypeMapping { refreshAnkiCardTypeValue() }
        }
        rowAnkiEditMapping.visibility = View.VISIBLE
        dividerAnkiEditMapping.visibility = View.VISIBLE
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

    /** Settings's response to [Prefs.showOverlayIcon] changing externally
     *  (Quick Settings tile, accessibility service disabling it). The pref
     *  feeds [CaptureLifecycle.isActive] on the accessibility backend, so a
     *  full refresh of the lifecycle surfaces (power cell + nav-bar button +
     *  on-screen-controls dim) is what we need. */
    fun refreshOverlayIconState() {
        refreshCaptureLifecycleButton()
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
