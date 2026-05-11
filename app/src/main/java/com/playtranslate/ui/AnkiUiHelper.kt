package com.playtranslate.ui

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import com.playtranslate.themeColor
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.playtranslate.AnkiManager
import com.playtranslate.Prefs
import com.playtranslate.R
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

/**
 * Shows a styled dialog explaining what AnkiDroid is and offering to open the Play Store listing.
 * Matches the visual style of the FloatingIconMenu confirmation dialog.
 */
fun showAnkiNotInstalledDialog(context: Context) {
    val density = context.resources.displayMetrics.density
    fun dp(v: Int) = (v * density).toInt()
    fun dpf(v: Int) = v * density

    val dialog = Dialog(context)
    dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

    val card = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(context.themeColor(R.attr.ptElevated))
            cornerRadius = dpf(16)
        }
        elevation = dpf(12)
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(24), dp(24), dp(24), dp(16))
    }

    // Title
    card.addView(TextView(context).apply {
        text = context.getString(R.string.anki_not_installed_title)
        setTextColor(context.themeColor(R.attr.ptText))
        textSize = 17f
        gravity = Gravity.CENTER
        setTypeface(null, Typeface.BOLD)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(8)
        }
    })

    // Subtitle
    card.addView(TextView(context).apply {
        text = context.getString(R.string.anki_not_installed_message)
        setTextColor(context.themeColor(R.attr.ptTextMuted))
        textSize = 13f
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(20)
        }
    })

    val hPad = dp(20)
    val vPad = dp(10)
    val btnLp = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    // "Get AnkiDroid" button
    card.addView(Button(context).apply {
        text = context.getString(R.string.anki_not_installed_get)
        setTextColor(context.themeColor(R.attr.ptAccentOn))
        textSize = 14f
        background = GradientDrawable().apply {
            setColor(context.themeColor(R.attr.ptWarning))
            cornerRadius = dpf(8)
        }
        isAllCaps = false
        setPadding(hPad, vPad, hPad, vPad)
        layoutParams = LinearLayout.LayoutParams(btnLp).apply {
            bottomMargin = dp(8)
        }
        setOnClickListener {
            dialog.dismiss()
            val intent = Intent(Intent.ACTION_VIEW,
                Uri.parse(context.getString(R.string.anki_play_store_url)))
            context.startActivity(intent)
        }
    })

    // Cancel button
    card.addView(Button(context).apply {
        text = context.getString(android.R.string.cancel)
        setTextColor(context.themeColor(R.attr.ptTextMuted))
        textSize = 14f
        setBackgroundColor(Color.TRANSPARENT)
        isAllCaps = false
        setPadding(hPad, vPad, hPad, vPad)
        layoutParams = LinearLayout.LayoutParams(btnLp)
        setOnClickListener { dialog.dismiss() }
    })

    dialog.setContentView(card)
    dialog.window?.setLayout(dp(280), ViewGroup.LayoutParams.WRAP_CONTENT)
    dialog.show()
}

