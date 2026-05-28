package com.playtranslate.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.materialswitch.MaterialSwitch
import com.playtranslate.AnkiManager
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.language.SourceLangId
import com.playtranslate.overlay.OverlayHost
import com.playtranslate.overlayThemedContext
import com.playtranslate.themeColor
import com.playtranslate.tts.TtsEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Loads AnkiDroid decks into [spinner] and auto-saves the selection to [Prefs].
 * [onLoaded] is called with the ordered list of deck entries once loaded.
 *
 * No-ops if AnkiDroid is not installed or permission has not been granted.
 * Must be called from a Fragment with a live [viewLifecycleOwner].
 */
fun Fragment.loadAnkiDecksInto(
    spinner: Spinner,
    onLoaded: (entries: List<Map.Entry<Long, String>>) -> Unit
) {
    viewLifecycleOwner.lifecycleScope.launch {
        val prefs       = Prefs(requireContext())
        val ankiManager = AnkiManager(requireContext())
        if (!ankiManager.isAnkiDroidInstalled() || !ankiManager.hasPermission()) return@launch

        val decks = withContext(Dispatchers.IO) { ankiManager.getDecks() }
        if (decks.isEmpty()) return@launch

        val entries = decks.entries.toList()
        spinner.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            entries.map { it.value }
        )
        val savedIdx = entries.indexOfFirst { it.key == prefs.ankiDeckId }.takeIf { it >= 0 } ?: 0
        spinner.setSelection(savedIdx)

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, v: View?, pos: Int, id: Long) {
                val entry = entries.getOrNull(pos) ?: return
                prefs.ankiDeckId   = entry.key
                prefs.ankiDeckName = entry.value
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        onLoaded(entries)
    }
}

/**
 * Selected-row background for grouped-card pickers (deck + card type).
 * Mirrors LanguageSetupActivity's buildSelectedRowBackground: a 10%
 * accent fill over the card color, with a 1dp stroke made from the
 * accent blended 10% into the (composited) divider color. Pass corner
 * radii so the drawable's top/bottom corners track the parent card's
 * rounded corners on the first/last row.
 */
internal fun Context.pickerSelectedRowBackground(
    topCornerRadius: Float,
    bottomCornerRadius: Float,
): GradientDrawable {
    val dp = resources.displayMetrics.density
    val accent = themeColor(com.playtranslate.R.attr.ptAccent)
    val card = themeColor(com.playtranslate.R.attr.ptCard)
    // ptDivider is a low-alpha hairline; composite it over the card so
    // the blend works against the color the user actually sees.
    val effectiveDivider = com.playtranslate.compositeOver(
        themeColor(com.playtranslate.R.attr.ptDivider), card,
    )
    val fill = com.playtranslate.blendColors(accent, card, 0.10f)
    val stroke = com.playtranslate.blendColors(accent, effectiveDivider, 0.10f)
    return GradientDrawable().apply {
        setColor(fill)
        setStroke((1 * dp).toInt(), stroke)
        cornerRadii = floatArrayOf(
            topCornerRadius, topCornerRadius,
            topCornerRadius, topCornerRadius,
            bottomCornerRadius, bottomCornerRadius,
            bottomCornerRadius, bottomCornerRadius,
        )
    }
}

/** Inflates a settings-style group header into [parent]. [suffix] sits as
 *  the right-aligned trailing slot (10sp, ptTextHint) and is hidden when null. */
fun ankiGroupHeader(parent: LinearLayout, title: String, suffix: String? = null) {
    val ctx = parent.context
    val header = android.view.LayoutInflater.from(ctx)
        .inflate(R.layout.settings_group_header, parent, false)
    header.findViewById<TextView>(R.id.tvGroupTitle).text =
        title.uppercase(java.util.Locale.ROOT)
    val badge = header.findViewById<TextView>(R.id.tvGroupBadge)
    if (!suffix.isNullOrBlank()) {
        badge.text = suffix
        badge.textSize = 10f
        badge.visibility = View.VISIBLE
    } else {
        badge.visibility = View.GONE
    }
    parent.addView(header)
}

/** Adds a flat MaterialCardView with the design-system stroke + radius to
 *  [parent] and returns its inner vertical LinearLayout. Mirrors the
 *  pattern Word Detail uses so headers, dividers, and rows compose
 *  consistently across sheets. */
fun ankiGroupCard(parent: LinearLayout): LinearLayout {
    val ctx = parent.context
    val density = ctx.resources.displayMetrics.density
    val card = com.google.android.material.card.MaterialCardView(ctx).apply {
        setCardBackgroundColor(ctx.themeColor(R.attr.ptCard))
        radius = ctx.resources.getDimension(R.dimen.pt_radius)
        cardElevation = 0f
        strokeColor = ctx.themeColor(R.attr.ptDivider)
        strokeWidth = (1 * density).toInt()
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }
    val inner = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }
    card.addView(inner)
    parent.addView(card)
    return inner
}

/** Adds a 1dp inset divider inside a group card. The default 16dp inset
 *  keeps the line under the row content. */
fun ankiInsetDivider(parent: LinearLayout, indentDp: Int = 16) {
    val ctx = parent.context
    val density = ctx.resources.displayMetrics.density
    parent.addView(View(ctx).apply {
        setBackgroundColor(ctx.themeColor(R.attr.ptDivider))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, (1 * density).toInt()
        ).also { it.marginStart = (indentDp * density).toInt() }
    })
}

/**
 * Inflates the unified Anki section (header "Anki" + one card with two
 * `settings_row_value` rows: Deck and Card Type) into [parent]. Tapping
 * either row launches its respective full-screen picker. Includes
 * stale-prefs healing for both the saved deck id and saved model id —
 * runs once on view-created, only acts when AnkiDroid is installed +
 * permission granted + the query returns non-empty (so we don't wipe a
 * valid selection on a transient query failure).
 *
 * @param mode              CardMode of the calling sheet — passed to
 *                          the card-type picker so Basic-shape
 *                          templates get mode-appropriate defaults.
 * @param onDeckChanged     called after the deck picker dismisses.
 * @param onCardTypeChanged called after the card-type flow resolves
 *                          (mapping dialog Save, or "Default" picked).
 */
fun Fragment.addAnkiSection(
    parent: LinearLayout,
    mode: CardMode,
    onDeckChanged: () -> Unit,
    onCardTypeChanged: () -> Unit,
) {
    val ctx = requireContext()
    val density = ctx.resources.displayMetrics.density
    val inflater = android.view.LayoutInflater.from(ctx)
    val prefs = Prefs(ctx)
    val accent = ctx.themeColor(R.attr.ptAccent)
    val muted = ctx.themeColor(R.attr.ptTextMuted)

    ankiGroupHeader(parent, ctx.getString(R.string.anki_section_header))
    val card = ankiGroupCard(parent)

    // -- Deck row --
    val deckRow = inflater.inflate(R.layout.settings_row_value, card, false)
    val deckTitle = deckRow.findViewById<TextView>(R.id.tvRowTitle)
    val deckValue = deckRow.findViewById<TextView>(R.id.tvRowValue)
    deckTitle.text = ctx.getString(R.string.anki_deck_row_label)
    fun applyDeckValue(name: String) {
        if (name.isBlank()) {
            deckValue.text = ctx.getString(R.string.anki_deck_row_empty)
            deckValue.setTextColor(muted)
        } else {
            deckValue.text = name
            deckValue.setTextColor(accent)
        }
    }
    applyDeckValue(prefs.ankiDeckName)
    deckRow.setOnClickListener {
        showAnkiDeckPicker { _, name ->
            applyDeckValue(name)
            onDeckChanged()
        }
    }
    card.addView(deckRow)

    // -- Divider --
    val divider = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, (1 * density).toInt(),
        ).also { it.marginStart = (16 * density).toInt() }
        setBackgroundColor(ctx.themeColor(R.attr.ptDivider))
    }
    card.addView(divider)

    // -- Card Type row --
    val cardTypeRow = inflater.inflate(R.layout.settings_row_value, card, false)
    val cardTypeTitle = cardTypeRow.findViewById<TextView>(R.id.tvRowTitle)
    val cardTypeValue = cardTypeRow.findViewById<TextView>(R.id.tvRowValue)
    cardTypeTitle.text = ctx.getString(R.string.anki_card_type_row_label)
    fun applyCardTypeValue(name: String) {
        if (name.isBlank()) {
            cardTypeValue.text = ctx.getString(R.string.anki_card_type_row_empty)
            cardTypeValue.setTextColor(muted)
        } else {
            cardTypeValue.text = name
            cardTypeValue.setTextColor(accent)
        }
    }
    applyCardTypeValue(prefs.ankiModelName)
    cardTypeRow.setOnClickListener {
        showAnkiCardTypePicker(mode) { _, name ->
            applyCardTypeValue(name)
            onCardTypeChanged()
        }
    }
    card.addView(cardTypeRow)

    // -- Healing pass: rectify deck + model selections against
    //    AnkiDroid's live state. Runs once on view-created.
    viewLifecycleOwner.lifecycleScope.launch {
        val ankiManager = AnkiManager(ctx)
        if (!ankiManager.isAnkiDroidInstalled() || !ankiManager.hasPermission()) return@launch
        val (decks, models) = withContext(Dispatchers.IO) {
            ankiManager.getDecks() to ankiManager.getModels()
        }
        if (decks.isNotEmpty() && !decks.containsKey(prefs.ankiDeckId)) {
            val first = decks.entries.first()
            prefs.ankiDeckId = first.key
            prefs.ankiDeckName = first.value
            applyDeckValue(first.value)
            onDeckChanged()
        }
        if (models.isNotEmpty() && prefs.ankiModelId != -1L) {
            val match = models.firstOrNull { it.id == prefs.ankiModelId }
            if (match == null) {
                prefs.ankiModelId = -1L
                prefs.ankiModelName = ""
                applyCardTypeValue("")
                onCardTypeChanged()
            } else if (match.name != prefs.ankiModelName) {
                prefs.ankiModelName = match.name
                applyCardTypeValue(match.name)
            }
        }
    }
}

/**
 * Lightweight handle to an audio toggle row (compact 44dp variant or
 * the full Audio section). The host reads [switch].isChecked at send
 * time, calls [refreshPillLabel] after the per-cell voice picker
 * returns, and calls [release] from onDestroyView so any in-flight
 * preview stops.
 *
 * [titleView] is exposed so callers can re-point the visible label when
 * the underlying text changes — the chip re-reads via its `previewText`
 * lambda, but the row's own label is a one-shot `text =` at build time
 * and needs explicit updates (e.g. the sentence sheet's Original field
 * is editable, so its audio-row label must track keystrokes).
 *
 * [pill] is null when the row was built without a voice pill (i.e. the
 * helper was invoked without `onVoicePillTap`).
 */
class AnkiAudioToggleHandle internal constructor(
    val switch: MaterialSwitch,
    val titleView: TextView,
    private val chip: AnkiAudioPreviewChip,
    val pill: VoicePillView? = null,
) {
    fun release() = chip.stop()

    /** Refresh the pill label after a per-cell pick. Async because the
     *  index → "Voice N" resolution requires [TtsEngine.voicesFor]
     *  which suspends on engine bind. */
    fun refreshPillLabel(
        fragment: Fragment,
        lang: SourceLangId,
        voice: String?,
    ) {
        val p = pill ?: return
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            p.setLabel(computeVoicePillLabel(p.view.context, lang, voice))
        }
    }
}

/** Resolves a per-cell voice override into the pill's display label —
 *  "Default" or "Voice N", matching the existing Voice-row scheme. The
 *  index lookup goes through [TtsEngine.voicesFor], which is suspending
 *  because the engine bind is asynchronous. */
internal suspend fun computeVoicePillLabel(
    ctx: android.content.Context,
    lang: SourceLangId,
    voice: String?,
): String = if (voice == null) {
    ctx.getString(R.string.anki_voice_default)
} else {
    val voices = TtsEngine.voicesFor(ctx, lang)
    val idx = voices.indexOfFirst { it.name == voice }
    if (idx >= 0) ctx.getString(R.string.anki_voice_numbered, idx + 1)
    else ctx.getString(R.string.anki_voice_default)
}

/**
 * Inflates an "Audio" section (header + one card) into [parent]: a
 * single audio row with a preview chip, [rowLabel], and an
 * include-on-card switch. The Voice picker lives in the top Anki
 * section now ([addAnkiSection]'s `includeVoiceRow`), so this helper
 * no longer owns one.
 *
 * The switch seeds from [initialChecked] and reports flips through
 * [onCheckedChange]; the caller persists the last-used state. The
 * preview chip speaks [previewText] live via [TtsEngine.speak] —
 * evaluated at tap time so an edited sentence previews its current text.
 *
 * @param lang        language for the preview.
 * @param previewText text to speak when the preview chip is tapped.
 */
fun Fragment.addAnkiAudioSection(
    parent: LinearLayout,
    lang: SourceLangId,
    rowLabel: String,
    previewText: () -> String,
    initialChecked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    /** Per-cell voice provider — fed to the chip + reflected in the
     *  pill's initial label. null lambda = "engine default". */
    voiceOverride: () -> String? = { null },
    /** Tap handler for the voice pill. When null, no pill is rendered
     *  (back-compat for callers that don't opt into per-cell voices). */
    onVoicePillTap: (() -> Unit)? = null,
): AnkiAudioToggleHandle {
    val ctx = requireContext()
    val inflater = android.view.LayoutInflater.from(ctx)

    ankiGroupHeader(parent, ctx.getString(R.string.anki_group_audio))
    val card = ankiGroupCard(parent)

    // -- Audio row: preview chip + (optional voice pill) + label + include switch --
    val audioRow = inflater.inflate(R.layout.settings_row_switch, card, false)
    val titleView = audioRow.findViewById<TextView>(R.id.tvRowTitle).apply {
        text = rowLabel
        // The label is the actual word/sentence being spoken — keep it to
        // one line and let Android ellipsize a long sentence.
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    val switch = audioRow.findViewById<MaterialSwitch>(R.id.switchRowToggle)
    val chip = AnkiAudioPreviewChip(this, lang, previewText, voiceOverride)
    (audioRow as ViewGroup).addView(chip.view, 0)
    val pill: VoicePillView? = if (onVoicePillTap != null) {
        val p = VoicePillView(this, lang)
        p.setOnTap(onVoicePillTap)
        // Insert immediately after the chip — index 1, before the title TextView.
        audioRow.addView(p.view, 1)
        // Initial label fills in async (engine bind suspends).
        viewLifecycleOwner.lifecycleScope.launch {
            p.setLabel(computeVoicePillLabel(ctx, lang, voiceOverride()))
        }
        p
    } else null
    // Active-state styling: title text-coloured + pill accent-coloured
    // when the switch is on; muted on both when off. Mirrors the chip's
    // ring/icon flip in [AnkiAudioPreviewChip.render].
    val activeTitleColor = ctx.themeColor(R.attr.ptText)
    val mutedTitleColor = ctx.themeColor(R.attr.ptOutline)
    fun applyActiveStyling(active: Boolean) {
        titleView.setTextColor(if (active) activeTitleColor else mutedTitleColor)
        pill?.setActive(active)
    }
    // Seed before wiring the listener so seeding doesn't fire onCheckedChange.
    switch.isChecked = initialChecked
    chip.setSwitchOn(initialChecked)
    applyActiveStyling(initialChecked)
    switch.setOnCheckedChangeListener { _, checked ->
        onCheckedChange(checked)
        chip.setSwitchOn(checked)
        applyActiveStyling(checked)
    }
    audioRow.setOnClickListener { switch.toggle() }
    card.addView(audioRow)

    return AnkiAudioToggleHandle(switch, titleView, chip, pill)
}

/**
 * Inflates a single 44dp audio row — preview chip, [label], and an
 * include-on-card switch — into [parent] without wrapping it in its own
 * group card or header. Used by the sentence-card flow to inline the
 * sentence audio toggle inside the Original group and to attach
 * per-target-word audio toggles directly beneath their word rows.
 * [label] is the text being spoken (the word or sentence); the switch
 * itself conveys the "include on card" action.
 *
 * Stripped-down sibling of [addAnkiAudioSection]: no Voice sub-row
 * (Voice lives in the top Anki section now via [addAnkiSection]'s
 * `includeVoiceRow`), no header, no card.
 */
fun Fragment.addCompactAudioToggleRow(
    parent: LinearLayout,
    lang: SourceLangId,
    label: String,
    previewText: () -> String,
    initialChecked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    /** See [addAnkiAudioSection]'s `voiceOverride`. */
    voiceOverride: () -> String? = { null },
    /** See [addAnkiAudioSection]'s `onVoicePillTap`. */
    onVoicePillTap: (() -> Unit)? = null,
): AnkiAudioToggleHandle {
    val ctx = requireContext()
    val inflater = android.view.LayoutInflater.from(ctx)
    val density = ctx.resources.displayMetrics.density

    val row = inflater.inflate(R.layout.settings_row_switch, parent, false) as LinearLayout
    // Override the default 56dp min-height + 9dp vertical padding so the
    // row sits at the 44dp the design calls for. Horizontal padding stays
    // at the dimens-driven default.
    row.minimumHeight = (44 * density).toInt()
    row.setPadding(row.paddingLeft, (4 * density).toInt(),
        row.paddingRight, (4 * density).toInt())
    val titleView = row.findViewById<TextView>(R.id.tvRowTitle).apply {
        text = label
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    val switch = row.findViewById<MaterialSwitch>(R.id.switchRowToggle)
    val chip = AnkiAudioPreviewChip(this, lang, previewText, voiceOverride)
    row.addView(chip.view, 0)
    val pill: VoicePillView? = if (onVoicePillTap != null) {
        val p = VoicePillView(this, lang)
        p.setOnTap(onVoicePillTap)
        row.addView(p.view, 1)
        viewLifecycleOwner.lifecycleScope.launch {
            p.setLabel(computeVoicePillLabel(ctx, lang, voiceOverride()))
        }
        p
    } else null
    // Active-state styling: title + pill follow the chip's switch-driven
    // colour flip — accent (text) on, muted off.
    val activeTitleColor = ctx.themeColor(R.attr.ptText)
    val mutedTitleColor = ctx.themeColor(R.attr.ptOutline)
    fun applyActiveStyling(active: Boolean) {
        titleView.setTextColor(if (active) activeTitleColor else mutedTitleColor)
        pill?.setActive(active)
    }
    // Seed before wiring the listener so seeding doesn't fire onCheckedChange.
    switch.isChecked = initialChecked
    chip.setSwitchOn(initialChecked)
    applyActiveStyling(initialChecked)
    switch.setOnCheckedChangeListener { _, checked ->
        onCheckedChange(checked)
        chip.setSwitchOn(checked)
        applyActiveStyling(checked)
    }
    row.setOnClickListener { switch.toggle() }
    parent.addView(row)

    return AnkiAudioToggleHandle(switch, titleView, chip, pill)
}

/**
 * Launches the same full-screen [AnkiDeckPickerDialog] the Settings sheet
 * uses for picking an Anki deck. The dialog persists the selection to
 * [Prefs] itself; [onPicked] fires after dismissal so the caller can
 * refresh row text / titles. No-ops silently when AnkiDroid isn't
 * installed or permission hasn't been granted.
 */
fun Fragment.showAnkiDeckPicker(onPicked: (deckId: Long, deckName: String) -> Unit) {
    val ctx = requireContext()
    val ankiManager = AnkiManager(ctx)
    if (!ankiManager.isAnkiDroidInstalled() || !ankiManager.hasPermission()) return
    val picker = AnkiDeckPickerDialog.newInstance()
    picker.onDeckSelected = {
        val prefs = Prefs(ctx)
        onPicked(prefs.ankiDeckId, prefs.ankiDeckName)
    }
    picker.show(childFragmentManager, AnkiDeckPickerDialog.TAG)
}

/**
 * Launches the Card Type picker. No-ops silently when AnkiDroid is
 * absent / permission missing. [onPicked] fires after the user resolves
 * the flow — either by selecting "Default (PlayTranslate)" or by
 * Saving the mapping dialog. Cancelling the mapping dialog does NOT
 * fire [onPicked] (selection reverts).
 */
fun Fragment.showAnkiCardTypePicker(
    mode: CardMode,
    onPicked: (modelId: Long, modelName: String) -> Unit,
) {
    val ctx = requireContext()
    val ankiManager = AnkiManager(ctx)
    if (!ankiManager.isAnkiDroidInstalled() || !ankiManager.hasPermission()) return
    val picker = AnkiCardTypePickerDialog.newInstance(mode)
    picker.onCardTypePicked = onPicked
    picker.show(childFragmentManager, AnkiCardTypePickerDialog.TAG)
}

/**
 * Opens [AnkiFieldMappingDialog] directly for [model] — used by the
 * send-time guard when the user has picked a card type but never
 * configured its field mapping. [onSaved] fires when the user Saves.
 */
fun Fragment.showAnkiCardTypeMappingDialog(
    model: AnkiManager.ModelInfo,
    mode: CardMode,
    onSaved: (modelId: Long, modelName: String) -> Unit,
) {
    val dialog = AnkiFieldMappingDialog.newInstance(
        modelId = model.id,
        modelName = model.name,
        fieldNames = model.fieldNames,
        mode = mode,
    )
    dialog.onSaved = onSaved
    dialog.show(parentFragmentManager, AnkiFieldMappingDialog.TAG)
}

/**
 * Builds a two-up pill segmented toggle inside [container] (a FrameLayout).
 * Mirrors the [SettingsRenderer]'s buildPillToggle pattern: surface-tinted
 * track, sliding accent indicator, transparent labels on top. Used in the
 * Anki review toolbar to switch between Sentence and Word card flows.
 *
 * @param leftLabel  Label for the left segment (e.g. "Sentence").
 * @param rightLabel Label for the right segment (e.g. "Word").
 * @param leftActive `true` if the left segment starts selected.
 * @param onSelect   Callback fired when the user taps the inactive segment;
 *                   `true` = left chosen, `false` = right chosen.
 */
fun buildAnkiModeToggle(
    container: FrameLayout,
    leftLabel: String,
    rightLabel: String,
    leftActive: Boolean,
    onSelect: (leftSelected: Boolean) -> Unit,
) {
    val ctx = container.context
    container.removeAllViews()
    val density = ctx.resources.displayMetrics.density
    val trackRadius = 100 * density
    val pillRadius = 100 * density
    val trackPad = (3 * density).toInt()
    val pillH = (30 * density).toInt()

    val surfaceColor = ctx.themeColor(R.attr.ptSurface)
    val accentColor = ctx.themeColor(R.attr.ptAccent)
    val accentOnColor = ctx.themeColor(R.attr.ptAccentOn)
    val mutedColor = ctx.themeColor(R.attr.ptTextMuted)

    val track = FrameLayout(ctx).apply {
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        background = GradientDrawable().apply {
            setColor(surfaceColor)
            cornerRadius = trackRadius
        }
        setPadding(trackPad, trackPad, trackPad, trackPad)
    }

    val pillRow = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
    }

    val indicator = View(ctx).apply {
        background = GradientDrawable().apply {
            setColor(accentColor)
            cornerRadius = pillRadius
        }
        elevation = 2 * density
    }
    track.addView(indicator)
    pillRow.elevation = 3 * density
    track.addView(pillRow)

    val labels = listOf(leftLabel, rightLabel)
    val initialIdx = if (leftActive) 0 else 1
    val pills = mutableListOf<TextView>()

    labels.forEachIndexed { idx, label ->
        val isActive = idx == initialIdx
        val pill = TextView(ctx).apply {
            text = label
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium",
                if (isActive) Typeface.BOLD else Typeface.NORMAL)
            gravity = Gravity.CENTER
            setTextColor(if (isActive) accentOnColor else mutedColor)
            layoutParams = LinearLayout.LayoutParams(0, pillH, 1f)
            setPadding((14 * density).toInt(), 0, (14 * density).toInt(), 0)
            isClickable = true
            isFocusable = true
        }
        pills.add(pill)
        pillRow.addView(pill)
    }

    container.addView(track)

    var currentIdx = initialIdx
    // Resize + reposition the indicator on every layout pass: the
    // initial measurement (via `pillRow.post`) wasn't enough because
    // the activity now handles config changes itself, so a rotation
    // resizes the toolbar without recreating the toggle. We need the
    // indicator width / translation to track the new pill width as
    // pills resize. Guarded against no-op writes to avoid a relayout
    // loop.
    pillRow.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        if (pills.isEmpty()) return@addOnLayoutChangeListener
        val pillW = pills[0].width
        if (pillW <= 0) return@addOnLayoutChangeListener
        val targetX = (pillW * currentIdx).toFloat()
        val curLp = indicator.layoutParams
        if (curLp == null || curLp.width != pillW || indicator.translationX != targetX) {
            indicator.layoutParams = FrameLayout.LayoutParams(pillW, pillH)
            indicator.translationX = targetX
            indicator.requestLayout()
        }
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
                p.setTextColor(if (active) accentOnColor else mutedColor)
                p.typeface = Typeface.create("sans-serif-medium",
                    if (active) Typeface.BOLD else Typeface.NORMAL)
            }
            onSelect(idx == 0)
        }
    }
}

/** Configures [builder] with the "AnkiDroid not installed" copy + actions.
 *  Caller picks the show path: [OverlayAlert.Builder.showInActivity] for an
 *  Activity, [OverlayAlert.Builder.show] for an accessibility overlay. The
 *  Play Store intent always carries [Intent.FLAG_ACTIVITY_NEW_TASK] so the
 *  same body works from a service-context (overlay path). */
private fun configureAnkiNotInstalled(
    context: Context,
    builder: OverlayAlert.Builder,
): OverlayAlert.Builder {
    // Attr lookups (ptAccent / ptAccentOn) need a themed context. The
    // Activity path is already themed, but the accessibility-overlay
    // path passes the raw display context — wrap defensively so both
    // paths resolve the same.
    val themed = overlayThemedContext(context)
    return builder
        .hideIcon()
        .setTitle(themed.getString(R.string.anki_not_installed_title))
        .setMessage(themed.getString(R.string.anki_not_installed_message))
        .addButton(
            themed.getString(R.string.anki_not_installed_get),
            themed.themeColor(R.attr.ptAccent),
            themed.themeColor(R.attr.ptAccentOn),
        ) {
            val intent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse(themed.getString(R.string.anki_play_store_url)),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            themed.startActivity(intent)
        }
        .addCancelButton(themed.getString(android.R.string.cancel))
}

private fun configureAnkiPermissionRationale(
    context: Context,
    builder: OverlayAlert.Builder,
    onCancel: (() -> Unit)?,
    onContinue: () -> Unit,
): OverlayAlert.Builder {
    val themed = overlayThemedContext(context)
    return builder
        .hideIcon()
        .setTitle(themed.getString(R.string.anki_permission_rationale_title))
        .setMessage(themed.getString(R.string.anki_permission_rationale_message))
        .addButton(
            themed.getString(R.string.btn_continue),
            themed.themeColor(R.attr.ptAccent),
            themed.themeColor(R.attr.ptAccentOn),
        ) { onContinue() }
        .addCancelButton(themed.getString(android.R.string.cancel), onCancel)
}

/**
 * Styled "AnkiDroid not installed" alert offering to open the Play Store
 * listing. Uses [OverlayAlert] for visual consistency with the rest of
 * the app's confirmation dialogs.
 */
fun showAnkiNotInstalledDialog(activity: Activity) {
    configureAnkiNotInstalled(activity, OverlayAlert.Builder(activity))
        .showInActivity(activity)
}

/** Capture-overlay variant — for surfaces that aren't an Activity (e.g. the
 *  drag-lookup lens). [overlayHost] carries the active backend's window type
 *  so the alert shows on MediaProjection as well as accessibility. */
fun showAnkiNotInstalledDialog(
    context: Context,
    overlayHost: OverlayHost,
    wm: WindowManager,
    displayId: Int,
) {
    configureAnkiNotInstalled(
        context, OverlayAlert.Builder(context, overlayHost, wm, displayId),
    ).show()
}

/**
 * Styled "AnkiDroid permission needed" rationale alert. [onContinue]
 * fires when the user taps Continue — the permission request itself is
 * caller-driven (Fragments use a result launcher, standalone Activities
 * use [androidx.core.app.ActivityCompat.requestPermissions]). [onCancel]
 * fires on cancel-button tap AND scrim tap; use it for surfaces like
 * [WordAnkiReviewActivity] where dismissing without granting should
 * finish the host.
 */
fun showAnkiPermissionRationaleDialog(
    activity: Activity,
    onCancel: (() -> Unit)? = null,
    onContinue: () -> Unit,
) {
    configureAnkiPermissionRationale(
        activity, OverlayAlert.Builder(activity), onCancel, onContinue,
    ).showInActivity(activity)
}

/**
 * Drives an Anki review sheet's save button through a send. The idle
 * button (icon + label) is swapped for a centred spinner while the send
 * runs, and the button is disabled so it can't fire twice; [setLoading]
 * with `false` restores it. A successful send dismisses the sheet, so the
 * restore path is only used on failure. [button] is the save FrameLayout
 * from either review-sheet layout — its first (and only) child is the
 * icon/label content the spinner stands in for.
 */
class AnkiSendButton(private val button: FrameLayout) {
    private val content: View = button.getChildAt(0)
    private val spinner: ProgressBar = ProgressBar(button.context).apply {
        isIndeterminate = true
        // The button fill is the accent colour, so the spinner takes the
        // on-accent colour — same as the icon and label it replaces.
        indeterminateTintList =
            ColorStateList.valueOf(button.context.themeColor(R.attr.ptAccentOn))
        val size = (28 * button.resources.displayMetrics.density).toInt()
        layoutParams = FrameLayout.LayoutParams(size, size, Gravity.CENTER)
        visibility = View.GONE
    }

    /** Small left-anchored spinner that signals "the sheet is still
     *  filling async fields in the background" without blocking taps
     *  on the button — it sits beside the centred icon/label, doesn't
     *  toggle [button.isEnabled], and is hidden while the during-send
     *  centred [spinner] is up so the two never compete visually. */
    private val pendingFillSpinner: ProgressBar = ProgressBar(button.context).apply {
        isIndeterminate = true
        indeterminateTintList =
            ColorStateList.valueOf(button.context.themeColor(R.attr.ptAccentOn))
        val density = button.resources.displayMetrics.density
        val size = (18 * density).toInt()
        layoutParams = FrameLayout.LayoutParams(
            size, size,
            Gravity.START or Gravity.CENTER_VERTICAL,
        ).also { it.marginStart = (16 * density).toInt() }
        visibility = View.GONE
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private var fillingPending = false

    init {
        button.addView(spinner)
        button.addView(pendingFillSpinner)
    }

    /** Swap the button to its spinner and block taps; pass `false` to
     *  restore the icon + label. The content is hidden with INVISIBLE so
     *  the button keeps its size and nothing reflows. */
    fun setLoading(loading: Boolean) {
        button.isEnabled = !loading
        content.visibility = if (loading) View.INVISIBLE else View.VISIBLE
        spinner.visibility = if (loading) View.VISIBLE else View.GONE
        // While the during-send spinner is up, hide the left "fields
        // loading" indicator so the two don't compete. Restore it on
        // exit if a fill is still in flight.
        pendingFillSpinner.visibility =
            if (!loading && fillingPending) View.VISIBLE else View.GONE
    }

    /** Toggles the small left "fields loading" indicator. Does not
     *  affect [button.isEnabled] — by design the user can still send
     *  while async fills are pending, the indicator is purely a cue. */
    fun setFillingPending(loading: Boolean) {
        fillingPending = loading
        // Don't reveal the small spinner while the centred send spinner
        // is up; setLoading(false) will restore it if `loading` is still
        // true at that point.
        if (spinner.visibility == View.VISIBLE) return
        pendingFillSpinner.visibility = if (loading) View.VISIBLE else View.GONE
    }
}

/**
 * Drives the floating entry-point Anki pill button (the small bottom-
 * end pill on the translation-result screen and the word-detail sheet)
 * through a one-tap send. Same idle ↔ loading swap pattern as
 * [AnkiSendButton] but designed for the pill shape: the FrameLayout
 * [button]'s first child is the icon+label LinearLayout, hidden with
 * INVISIBLE during loading (so the pill keeps its width), and a
 * centred 18dp ProgressBar overlays in its place.
 */
class PillAnkiButton(private val button: FrameLayout) {
    private val content: View = button.getChildAt(0)
    private val spinner: ProgressBar = ProgressBar(button.context).apply {
        isIndeterminate = true
        indeterminateTintList =
            ColorStateList.valueOf(button.context.themeColor(R.attr.ptAccentOn))
        val size = (18 * button.resources.displayMetrics.density).toInt()
        layoutParams = FrameLayout.LayoutParams(size, size, Gravity.CENTER)
        visibility = View.GONE
    }

    init {
        button.addView(spinner)
    }

    /** Swap the pill to its spinner and block taps; pass `false` to
     *  restore the icon + label. */
    fun setLoading(loading: Boolean) {
        button.isEnabled = !loading
        content.visibility = if (loading) View.INVISIBLE else View.VISIBLE
        spinner.visibility = if (loading) View.VISIBLE else View.GONE
    }
}

/**
 * Applies an [AnkiSendResult] to a review sheet's UI:
 *  - [AnkiSendResult.Success] runs [onSuccess] — the caller dismisses the
 *    sheet (and signals any fragment result).
 *  - [AnkiSendResult.Failed] shows the error in an [OverlayAlert] layered
 *    over the sheet, then runs [onRestore] to hand the save button back.
 *  - [AnkiSendResult.NeedsMapping] just runs [onRestore]: the Fragment
 *    wrapper around the dispatcher has already opened the field-mapping
 *    dialog, so no alert is shown.
 *
 * The alert attaches to the sheet's own dialog window (via
 * [OverlayAlert.Builder.showInDialog]) so it layers above the sheet.
 */
fun DialogFragment.applyAnkiSendResult(
    result: AnkiSendResult,
    onSuccess: () -> Unit,
    onRestore: () -> Unit,
) {
    when (result) {
        is AnkiSendResult.Success -> onSuccess()
        is AnkiSendResult.Failed -> {
            val ctx = requireContext()
            OverlayAlert.Builder(ctx)
                .hideIcon()
                .setTitle(getString(R.string.anki_send_failed_title))
                .setMessage(getString(result.messageRes))
                .addButton(
                    getString(android.R.string.ok),
                    ctx.themeColor(R.attr.ptAccent),
                    ctx.themeColor(R.attr.ptAccentOn),
                ) {}
                .showInDialog(requireDialog())
            onRestore()
        }
        is AnkiSendResult.NeedsMapping -> onRestore()
    }
}

