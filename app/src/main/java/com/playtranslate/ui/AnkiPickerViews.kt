package com.playtranslate.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.text.HtmlCompat
import com.playtranslate.AnkiManager
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.themeColor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The four Anki picker surfaces (deck, card type, field mapping, content
 * source) as host-agnostic content views — extracted from their
 * DialogFragments so the SAME pickers render inside the in-app dialogs AND
 * as floating-workspace pages over the game. Each class builds its content
 * into a caller-supplied container; hosts own the chrome (toolbar / the
 * workspace header), the presentation choreography (child-fragment show vs
 * workspace push), and closing on completion.
 *
 * All rows are plain views (PtGroupCard + the shared row layouts) with any
 * `app:` tint applied in code — the workspace's plain inflater drops
 * AppCompat attributes.
 */

/** Deck list: loads the user's AnkiDroid decks async, commits
 *  `ankiDeckId`/`ankiDeckName` on tap, then fires [onDeckSelected]. */
class AnkiDeckPickerView(
    private val ctx: Context,
    private val scope: CoroutineScope,
    private val isAlive: () -> Boolean,
    private val onDeckSelected: (deckId: Long, deckName: String) -> Unit,
) {
    fun build(parent: ViewGroup): View {
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        val prefs = Prefs(ctx)
        container.addView(TextView(ctx).apply {
            text = ctx.getString(R.string.anki_deck_picker_loading)
            setTextColor(ctx.themeColor(R.attr.ptTextMuted))
            textSize = 14f
            setPadding(0, (16 * ctx.resources.displayMetrics.density).toInt(), 0, 0)
        })

        scope.launch {
            val decks = withContext(Dispatchers.IO) { AnkiManager(ctx).getDecks() }
            if (!isAlive()) return@launch
            container.removeAllViews()

            if (decks.isEmpty()) {
                container.addView(TextView(ctx).apply {
                    text = ctx.getString(R.string.anki_deck_picker_empty)
                    setTextColor(ctx.themeColor(R.attr.ptTextMuted))
                    textSize = 14f
                })
                return@launch
            }

            renderDecksSection(container, decks.entries.toList(), prefs)
        }
        return container
    }

    private fun renderDecksSection(
        parent: LinearLayout,
        decks: List<Map.Entry<Long, String>>,
        prefs: Prefs,
    ) {
        val inflater = LayoutInflater.from(ctx)
        val card = PtGroupCard(ctx)
        val cardRadius = card.radiusPx
        val lastIdx = decks.lastIndex
        decks.forEachIndexed { idx, entry ->
            if (idx > 0) {
                card.addView(inflater.inflate(R.layout.settings_row_divider, card, false))
            }
            val topRadius = if (idx == 0) cardRadius else 0f
            val bottomRadius = if (idx == lastIdx) cardRadius else 0f
            card.addView(buildDeckRow(card, entry, prefs, topRadius, bottomRadius))
        }
        parent.addView(card)
    }

    private fun buildDeckRow(
        container: ViewGroup,
        entry: Map.Entry<Long, String>,
        prefs: Prefs,
        topCornerRadius: Float,
        bottomCornerRadius: Float,
    ): View {
        val isSelected = entry.key == prefs.ankiDeckId
        val view = LayoutInflater.from(ctx)
            .inflate(R.layout.language_list_row, container, false)
        view.findViewById<TextView>(R.id.tvRowTitle).apply {
            text = entry.value
            // Bolding hints at selection alongside the highlight background.
            setTypeface(typeface, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
        }

        // language_list_row's trailing slot is wired for a Delete
        // button; repurpose it as a non-tappable selection checkmark.
        val trailing = view.findViewById<FrameLayout>(R.id.btnDelete)
        val trailingIcon = view.findViewById<ImageView>(R.id.ivDeleteIcon)
        if (isSelected) {
            trailing.visibility = View.VISIBLE
            trailingIcon.setImageResource(R.drawable.ic_check)
            trailingIcon.imageTintList = ColorStateList.valueOf(ctx.themeColor(R.attr.ptAccent))
            trailing.isClickable = false
            trailing.isFocusable = false
            trailing.foreground = null
        }
        if (isSelected) {
            view.background = ctx.pickerSelectedRowBackground(topCornerRadius, bottomCornerRadius)
        }
        view.setOnClickListener {
            prefs.ankiDeckId = entry.key
            prefs.ankiDeckName = entry.value
            onDeckSelected(entry.key, entry.value)
        }
        return view
    }
}

/** Card-type list: Default (PlayTranslate) + the user's AnkiDroid note
 *  types. The Default row and basic-shape models commit directly and fire
 *  [onCardTypePicked]; a non-basic model routes through [openFieldMapping]
 *  — the host presents the mapping surface and closes this picker. */
class AnkiCardTypePickerView(
    private val ctx: Context,
    private val scope: CoroutineScope,
    private val isAlive: () -> Boolean,
    private val onCardTypePicked: (modelId: Long, modelName: String) -> Unit,
    private val openFieldMapping: (model: AnkiManager.ModelInfo) -> Unit,
) {
    fun build(parent: ViewGroup): View {
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        val density = ctx.resources.displayMetrics.density
        container.addView(TextView(ctx).apply {
            text = ctx.getString(R.string.anki_card_type_picker_loading)
            setTextColor(ctx.themeColor(R.attr.ptTextMuted))
            textSize = 14f
            setPadding(0, (16 * density).toInt(), 0, 0)
        })

        scope.launch {
            val models = withContext(Dispatchers.IO) { AnkiManager(ctx).getModels() }
            if (!isAlive()) return@launch
            container.removeAllViews()
            render(container, models)
        }
        return container
    }

    private fun render(container: LinearLayout, models: List<AnkiManager.ModelInfo>) {
        val prefs = Prefs(ctx)

        // Section 1: Default (PlayTranslate) — always shown.
        renderSection(
            parent = container,
            title = ctx.getString(R.string.anki_card_type_section_default),
            rows = listOf(
                CardTypeRow(
                    title = ctx.getString(R.string.anki_card_type_row_empty),
                    subtitle = null,
                    isSelected = prefs.ankiModelId == -1L,
                    onClick = {
                        prefs.ankiModelId = -1L
                        prefs.ankiModelName = ""
                        onCardTypePicked(-1L, "")
                    },
                )
            ),
        )

        // Section 2: Card Types from AnkiDroid — or empty-state caption
        // if AnkiDroid has none.
        if (models.isEmpty()) {
            container.addView(TextView(ctx).apply {
                text = ctx.getString(R.string.anki_card_type_no_models)
                setTextColor(ctx.themeColor(R.attr.ptTextMuted))
                textSize = 14f
                val density = ctx.resources.displayMetrics.density
                setPadding(
                    (4 * density).toInt(), (16 * density).toInt(),
                    (4 * density).toInt(), 0,
                )
            })
            return
        }
        renderSection(
            parent = container,
            title = ctx.getString(R.string.anki_card_type_section_custom),
            rows = models.map { model ->
                CardTypeRow(
                    title = model.name,
                    subtitle = model.fieldNames.joinToString(" · "),
                    isSelected = prefs.ankiModelId == model.id,
                    onClick = {
                        if (AnkiCardTypeMapper.isBasicShape(model.fieldNames)) {
                            // Basic-shape templates send mode-appropriate
                            // content automatically at dispatch time — no
                            // per-field mapping to configure. Commit the
                            // selection directly and skip the mapping
                            // surface.
                            prefs.ankiModelId = model.id
                            prefs.ankiModelName = model.name
                            // Wipe any stale mapping from an earlier
                            // build that auto-populated Basic defaults
                            // — those would otherwise sit unused in
                            // prefs forever.
                            prefs.setAnkiFieldMapping(model.id, emptyMap())
                            onCardTypePicked(model.id, model.name)
                        } else {
                            openFieldMapping(model)
                        }
                    },
                )
            },
        )
    }

    private data class CardTypeRow(
        val title: String,
        val subtitle: String?,
        val isSelected: Boolean,
        val onClick: () -> Unit,
    )

    private fun renderSection(
        parent: LinearLayout,
        title: String,
        rows: List<CardTypeRow>,
    ) {
        if (rows.isEmpty()) return
        val inflater = LayoutInflater.from(ctx)

        val header = inflater.inflate(R.layout.settings_group_header, parent, false)
        header.findViewById<TextView>(R.id.tvGroupTitle).text = title.uppercase()
        parent.addView(header)

        val card = PtGroupCard(ctx)
        val cardRadius = card.radiusPx
        val lastIdx = rows.lastIndex
        rows.forEachIndexed { idx, row ->
            if (idx > 0) {
                card.addView(inflater.inflate(R.layout.settings_row_divider, card, false))
            }
            val topRadius = if (idx == 0) cardRadius else 0f
            val bottomRadius = if (idx == lastIdx) cardRadius else 0f
            card.addView(buildRow(card, row, topRadius, bottomRadius))
        }
        parent.addView(card)
    }

    private fun buildRow(
        container: ViewGroup,
        row: CardTypeRow,
        topCornerRadius: Float,
        bottomCornerRadius: Float,
    ): View {
        val view = LayoutInflater.from(ctx)
            .inflate(R.layout.anki_card_type_picker_row, container, false)
        view.findViewById<TextView>(R.id.tvRowTitle).apply {
            text = row.title
            setTypeface(typeface, if (row.isSelected) Typeface.BOLD else Typeface.NORMAL)
        }
        val subtitleTv = view.findViewById<TextView>(R.id.tvRowSubtitle)
        if (row.subtitle.isNullOrEmpty()) {
            subtitleTv.visibility = View.GONE
        } else {
            subtitleTv.text = row.subtitle
            subtitleTv.visibility = View.VISIBLE
        }
        val check = view.findViewById<ImageView>(R.id.ivSelectedCheck)
        check.visibility = if (row.isSelected) View.VISIBLE else View.GONE
        if (row.isSelected) {
            // The layout's app:tint is AppCompat-only — apply in code so the
            // workspace's plain inflater shows the same accent check.
            check.imageTintList = ColorStateList.valueOf(ctx.themeColor(R.attr.ptAccent))
            view.background = ctx.pickerSelectedRowBackground(topCornerRadius, bottomCornerRadius)
        }
        view.setOnClickListener { row.onClick() }
        return view
    }
}

/**
 * Per-field mapping editor content: one row per field of the chosen model;
 * tapping a row routes through [openSourcePicker] (the host presents the
 * content-source picker), edits accumulate in a working map, and [save]
 * commits `ankiModelId`/`ankiModelName`/`setAnkiFieldMapping` then fires
 * [onSaved]. Closing without [save] commits nothing.
 */
class AnkiFieldMappingView(
    private val ctx: Context,
    private val modelId: Long,
    private val modelName: String,
    private val fieldNames: List<String>,
    private val mode: CardMode,
    private val openSourcePicker: (
        fieldName: String,
        current: ContentSource,
        onPicked: (ContentSource) -> Unit,
    ) -> Unit,
    private val onSaved: (modelId: Long, modelName: String) -> Unit,
) {
    /** Mutable working copy. Initial state from saved prefs (if any) or
     *  template defaults; user edits write here until [save] commits. */
    private val workingMapping = linkedMapOf<String, ContentSource>()

    init {
        val prefs = Prefs(ctx)
        // Initial state: saved mapping if any, else template defaults.
        val saved = prefs.getAnkiFieldMapping(modelId)
        Log.d(TAG, "init: model='$modelName' id=$modelId " +
            "mode=$mode fieldNames=$fieldNames saved=$saved")
        val starter = if (saved.isNotEmpty()) {
            saved
        } else {
            val model = AnkiManager.ModelInfo(modelId, modelName, fieldNames, 0)
            AnkiCardTypeMapper.defaultsForModel(model, mode)
        }
        // Initialize working map: every field gets an entry (NONE if
        // unmapped) so the editor shows a complete view.
        fieldNames.forEach { fieldName ->
            workingMapping[fieldName] = starter[fieldName] ?: ContentSource.NONE
        }
    }

    fun build(parent: ViewGroup): View {
        val inflater = LayoutInflater.from(ctx)
        // Group all fields inside a single grouped card, with inset
        // dividers between rows — same grouped-card look as the Anki
        // section in Settings and the Card Type picker.
        val card = PtGroupCard(ctx)
        fieldNames.forEachIndexed { idx, fieldName ->
            if (idx > 0) {
                card.addView(inflater.inflate(R.layout.settings_row_divider, card, false))
            }
            val row = inflater.inflate(R.layout.settings_row_value, card, false)
            val titleTv = row.findViewById<TextView>(R.id.tvRowTitle)
            val valueTv = row.findViewById<TextView>(R.id.tvRowValue)
            titleTv.text = fieldName
            val current = workingMapping[fieldName] ?: ContentSource.NONE
            applyValueStyle(valueTv, current)
            row.setOnClickListener {
                openSourcePicker(fieldName, workingMapping[fieldName] ?: ContentSource.NONE) { picked ->
                    workingMapping[fieldName] = picked
                    applyValueStyle(valueTv, picked)
                }
            }
            card.addView(row)
        }
        return card
    }

    /** Commit the working mapping and fire [onSaved]. The host closes. */
    fun save() {
        val prefs = Prefs(ctx)
        prefs.ankiModelId = modelId
        prefs.ankiModelName = modelName
        prefs.setAnkiFieldMapping(modelId, workingMapping)
        onSaved(modelId, modelName)
    }

    /**
     * Renders the row's value text. NONE gets the same italic +
     * `ptTextHint` treatment the offline-translation cells use for
     * their neutral-tone secondary subtitle — visually marks the
     * absence of a mapping without making it disappear. Any other
     * source uses the default RowValue color (`ptTextMuted`) with
     * upright type. Pass null for the family in setTypeface so only
     * the style flag toggles; otherwise platforms can cache italic
     * state on the resolved typeface.
     */
    private fun applyValueStyle(tv: TextView, source: ContentSource) {
        tv.text = ctx.getString(source.labelRes)
        if (source == ContentSource.NONE) {
            tv.setTypeface(null, Typeface.ITALIC)
            tv.setTextColor(ctx.themeColor(R.attr.ptTextHint))
        } else {
            tv.setTypeface(null, Typeface.NORMAL)
            tv.setTextColor(ctx.themeColor(R.attr.ptTextMuted))
        }
    }

    private companion object {
        const val TAG = "FieldMappingView"
    }
}

/**
 * Content-source list for a single field, split into the CONTENT and
 * CARD TYPE FLAG sections (see [AnkiContentSourcePickerDialog]'s doc for
 * the grouping rationale). Tapping a row fires [onPicked]; the host closes.
 */
class AnkiContentSourcePickerView(
    private val ctx: Context,
    private val current: ContentSource,
    private val onPicked: (ContentSource) -> Unit,
) {
    fun build(parent: ViewGroup): View {
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        val content = ContentSource.values().filter { it.kind == ContentSource.Kind.CONTENT }
        val flags = ContentSource.values().filter { it.kind == ContentSource.Kind.FLAG }
        renderSection(container, R.string.anki_content_section_content, content)
        renderSection(container, R.string.anki_content_section_flag, flags)
        return container
    }

    private fun renderSection(
        parent: LinearLayout,
        titleRes: Int,
        options: List<ContentSource>,
    ) {
        if (options.isEmpty()) return
        val inflater = LayoutInflater.from(ctx)

        val header = inflater.inflate(R.layout.settings_group_header, parent, false)
        header.findViewById<TextView>(R.id.tvGroupTitle).text =
            ctx.getString(titleRes).uppercase()
        parent.addView(header)

        val card = PtGroupCard(ctx)
        val cardRadius = card.radiusPx
        val lastIdx = options.lastIndex
        options.forEachIndexed { idx, source ->
            if (idx > 0) {
                card.addView(inflater.inflate(R.layout.settings_row_divider, card, false))
            }
            val topRadius = if (idx == 0) cardRadius else 0f
            val bottomRadius = if (idx == lastIdx) cardRadius else 0f
            card.addView(buildRow(card, source, topRadius, bottomRadius))
        }
        parent.addView(card)
    }

    private fun buildRow(
        container: ViewGroup,
        source: ContentSource,
        topCornerRadius: Float,
        bottomCornerRadius: Float,
    ): View {
        val isSelected = source == current
        // anki_card_type_picker_row gives us the title + subtitle slots
        // and a trailing check icon — same two-line shape the Card Type
        // picker uses one screen up the stack, so the visual rhythm
        // carries through. The subtitle shows each source's description
        // + example so users mapping a field don't have to guess what
        // PT will write into it.
        val view = LayoutInflater.from(ctx)
            .inflate(R.layout.anki_card_type_picker_row, container, false)
        val titleTv    = view.findViewById<TextView>(R.id.tvRowTitle)
        val subtitleTv = view.findViewById<TextView>(R.id.tvRowSubtitle)
        val check      = view.findViewById<ImageView>(R.id.ivSelectedCheck)

        titleTv.text = ctx.getString(source.labelRes)
        // Descriptions include literal `<b>` markup to show what the
        // example will look like on the card. HtmlCompat renders those
        // inline rather than dumping the raw tags as text.
        subtitleTv.text = HtmlCompat.fromHtml(
            ctx.getString(source.descriptionRes),
            HtmlCompat.FROM_HTML_MODE_LEGACY,
        )
        // The card-type picker constrains the subtitle to 2 lines for
        // long field lists; descriptions here can run a bit longer
        // (especially the furigana variants with bracket examples).
        // Let them wrap freely.
        subtitleTv.maxLines = Int.MAX_VALUE
        subtitleTv.ellipsize = null

        if (source == ContentSource.NONE) {
            // Match the "Empty" label styling on the mapping screen —
            // italic + hint color — so NONE reads consistently whether
            // it's a row value on the mapping screen or an option here.
            titleTv.setTypeface(null, Typeface.ITALIC)
            if (!isSelected) {
                titleTv.setTextColor(ctx.themeColor(R.attr.ptTextHint))
            }
        } else {
            titleTv.setTypeface(null, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
        }

        if (isSelected) {
            check.visibility = View.VISIBLE
            check.imageTintList = ColorStateList.valueOf(ctx.themeColor(R.attr.ptAccent))
            view.background = ctx.pickerSelectedRowBackground(topCornerRadius, bottomCornerRadius)
        }
        view.setOnClickListener { onPicked(source) }
        return view
    }
}
