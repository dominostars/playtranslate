package com.playtranslate.ui

import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.app.Activity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.language.SourceLangId
import com.playtranslate.themeColor
import java.io.File

/**
 * Sentence-card content for Anki review (Original, Translation, Words,
 * Screenshot). Embedded by [AnkiReviewBottomSheet] (sentence-only) and
 * the Sentence side of [WordAnkiReviewSheet]. Each section renders as a
 * grouped MaterialCardView with the design-system header on top, matching
 * the Settings / Word Detail rhythm.
 *
 * Words always ship with the card unless the user removes them via the
 * row's `×` glyph. Tapping the row toggles **target** state — target
 * words are highlighted on the rendered card front (the HTML builder
 * reads [selectedWords]). The target carries no "Target" label in the
 * row UI; the row just tints accent and the word text re-colours.
 */
class SentenceAnkiContentFragment : Fragment() {

    private val words = mutableListOf<SentenceAnkiHtmlBuilder.WordEntry>()
    val selectedWords = mutableSetOf<String>()
    var includePhoto = true
        private set

    /** Whether the sentence-audio switch is on. Read by the host sheet
     *  at send time; false when the toggle wasn't built. */
    val sentenceAudioEnabled: Boolean
        get() = sentenceAudioHandle?.switch?.isChecked == true

    private lateinit var root: LinearLayout
    private lateinit var etOriginal: EditText
    private lateinit var etTranslation: EditText
    private lateinit var wordsCard: LinearLayout
    private lateinit var wordsHeaderTitle: TextView
    private var screenshotHeader: View? = null
    private var screenshotGroup: View? = null
    private var ivPhoto: ImageView? = null
    private var sentenceAudioHandle: AnkiAudioToggleHandle? = null

    /** Independent per-target-word audio toggle state for THIS card.
     *  Seeded from [Prefs.ankiWordAudioEnabled] when a word is first
     *  added to [selectedWords]. Mutated by the word's sub-row toggle;
     *  pushed back to the pref on every change so the next card defaults
     *  to whatever the user picked last. */
    private val wordAudioEnabled = mutableMapOf<String, Boolean>()

    /** Per-word handle map — lets us release preview chips cleanly before
     *  each [rebuildWordRows] (otherwise an in-flight preview on a
     *  sub-row that's about to be removed keeps playing for a beat). */
    private val wordAudioHandles = mutableMapOf<String, AnkiAudioToggleHandle>()

    /** Voice for the sentence audio cell. Seeded from
     *  [Prefs.ttsVoiceName] at buildContent time; after that, this
     *  field IS the cell's voice. null means "explicit engine default"
     *  (the picker's "Default" pick lands here as null too). The
     *  global pref is never read again for this cell — the seeded
     *  value plus any pick lives entirely on the sheet. */
    private var sentenceVoice: String? = null

    /** Same model, per target word. Entry is missing for words that
     *  haven't been added to [selectedWords] yet; rebuildWordRows
     *  populates an entry via `getOrPut(word) { Prefs.ttsVoiceName }`
     *  when the word first appears as a target. */
    private val wordVoices = mutableMapOf<String, String?>()

    /** Identifies the cell that the active picker launch was for.
     *  Cleared when the result lands. */
    private sealed interface PickTarget {
        data object Sentence : PickTarget
        data class Word(val word: String) : PickTarget
    }
    private var pendingPick: PickTarget? = null

    private val voicePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val target = pendingPick.also { pendingPick = null }
            ?: return@registerForActivityResult
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val picked = result.data?.getStringExtra(TtsVoiceActivity.EXTRA_PICKED_VOICE)
        val lang = SourceLangId.fromCode(arguments?.getString(ARG_SOURCE_LANG))
            ?: SourceLangId.JA
        when (target) {
            is PickTarget.Sentence -> {
                sentenceVoice = picked
                sentenceAudioHandle?.refreshPillLabel(this, lang, picked)
            }
            is PickTarget.Word -> {
                wordVoices[target.word] = picked
                wordAudioHandles[target.word]?.refreshPillLabel(this, lang, picked)
            }
        }
    }

    private fun launchVoicePicker(target: PickTarget, current: String?) {
        val ctx = context ?: return
        val lang = SourceLangId.fromCode(arguments?.getString(ARG_SOURCE_LANG))
            ?: SourceLangId.JA
        pendingPick = target
        voicePickerLauncher.launch(
            TtsVoiceActivity.intent(ctx, lang, current),
        )
    }

    /** True while we wait for [applyWords] — drives the "Looking up words…"
     *  placeholder in the Words card. Flips to false the moment applyWords
     *  is called, even if the list it carries is empty (definitive empty). */
    private var wordsLoading: Boolean = false

    /** Set the first time the user types in [etTranslation]. Once true,
     *  [applyTranslation] becomes a no-op so a late-arriving translation
     *  doesn't stomp on what the user just typed. */
    private var translationUserTouched: Boolean = false

    /** Set before any programmatic [EditText.setText] on [etTranslation] so
     *  the TextWatcher doesn't mistake our own write for user input.
     *  Cleared by the watcher on the next callback. */
    private var translationSuppressNextEdit: Boolean = false

    data class CardData(
        val source: String,
        val target: String,
        val words: List<SentenceAnkiHtmlBuilder.WordEntry>,
        val selectedWords: Set<String>,
        val screenshotPath: String?,
        val sourceLangId: SourceLangId,
        /** Subset of [selectedWords] whose per-target-word audio toggle is
         *  on. Only enabled, currently-selected words are reported — the
         *  send path doesn't need false entries or stale ones. Defaults
         *  to empty for callers/tests that don't care. */
        val targetWordAudioWords: Set<String> = emptySet(),
        /** Voice for the sentence audio cell — passed to
         *  [TtsEngine.synthesizeToFile]'s `voiceNameOverride`. null
         *  means "explicit engine default". */
        val sentenceVoice: String? = null,
        /** Per-target-word voice. Missing entry = "use engine default".
         *  Only words in [targetWordAudioWords] need an entry here; the
         *  send path looks up by word. */
        val wordAudioVoices: Map<String, String?> = emptyMap(),
    )

    fun getCardData(): CardData {
        val enabledTargets = selectedWords
            .filter { wordAudioEnabled[it] == true }
            .toSet()
        return CardData(
            source = etOriginal.text.toString(),
            target = etTranslation.text.toString(),
            words = words.toList(),
            selectedWords = selectedWords.toSet(),
            screenshotPath = if (includePhoto) arguments?.getString(ARG_SCREENSHOT_PATH) else null,
            sourceLangId = SourceLangId.fromCode(arguments?.getString(ARG_SOURCE_LANG)) ?: SourceLangId.JA,
            targetWordAudioWords = enabledTargets,
            sentenceVoice = sentenceVoice,
            // Only include voices for words whose audio is enabled — the
            // send path iterates targetWordAudioWords anyway, so any
            // extra entries would be dead weight.
            wordAudioVoices = enabledTargets.associateWith { wordVoices[it] },
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_sentence_anki_content, container, false)

    override fun onDestroyView() {
        ivPhoto?.setImageBitmap(null)
        ivPhoto = null
        sentenceAudioHandle?.release()
        sentenceAudioHandle = null
        wordAudioHandles.values.forEach { it.release() }
        wordAudioHandles.clear()
        super.onDestroyView()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        root = view as LinearLayout
        val args = arguments ?: return

        // The hosting Anki dialog locks orientation, so onViewCreated
        // only runs once per fragment open. Defensive clears stay
        // because the model collections are class-level fields — if
        // anything ever does cause a re-attach we don't want to
        // accumulate duplicates.
        words.clear()
        selectedWords.clear()

        val wordArr    = args.getStringArray(ARG_WORDS) ?: emptyArray()
        val readingArr = args.getStringArray(ARG_READINGS) ?: emptyArray()
        val meaningArr = args.getStringArray(ARG_MEANINGS) ?: emptyArray()
        val freqArr    = args.getIntArray(ARG_FREQ_SCORES) ?: IntArray(0)
        val surfaces   = LastSentenceCache.surfaceForms ?: emptyMap()
        wordArr.forEachIndexed { i, w ->
            words.add(SentenceAnkiHtmlBuilder.WordEntry(
                w,
                readingArr.getOrElse(i) { "" },
                meaningArr.getOrElse(i) { "" },
                freqArr.getOrElse(i) { 0 },
                surfaceForm = surfaces[w] ?: ""
            ))
        }

        // Auto-target the looked-up word and float targets to the top.
        val targetWord = args.getString(ARG_TARGET_WORD)
        if (targetWord != null && words.any { it.word == targetWord }) {
            selectedWords.add(targetWord)
        }
        if (selectedWords.isNotEmpty()) {
            val sorted = words.sortedByDescending { it.word in selectedWords }
            words.clear()
            words.addAll(sorted)
        }

        val original = args.getString(ARG_ORIGINAL) ?: ""
        val translation = args.getString(ARG_TRANSLATION) ?: ""
        val screenshotPath = args.getString(ARG_SCREENSHOT_PATH)
        buildContent(original, translation, screenshotPath)
    }

    // ── Build ────────────────────────────────────────────────────────────

    private fun buildContent(original: String, translation: String, screenshotPath: String?) {
        val ctx = requireContext()
        root.removeAllViews()

        val prefs = Prefs(ctx)
        val lang = SourceLangId.fromCode(arguments?.getString(ARG_SOURCE_LANG))
            ?: SourceLangId.JA

        // Original — id is pinned to a resource id (etAnkiOriginal) so
        // Android's automatic view-state save/restore can round-trip
        // the typed text across process death without us writing a
        // manual onSaveInstanceState pipeline. The compact 44dp audio
        // toggle now sits inside the same card, beneath the edit field.
        ankiGroupHeader(root, getString(R.string.anki_group_original))
        val originalCard = ankiGroupCard(root)
        etOriginal = buildEditField(initial = original).apply {
            id = R.id.etAnkiOriginal
        }
        originalCard.addView(buildEditableFrame(etOriginal))
        ankiInsetDivider(originalCard)
        // Seed the sentence voice from the global pref AT view-create.
        // After this point the field is the cell's voice — null means
        // "explicit engine default" (e.g. user picked Default in the
        // picker), not "look up the pref again".
        sentenceVoice = prefs.ttsVoiceName(lang)
        sentenceAudioHandle = addCompactAudioToggleRow(
            parent = originalCard,
            lang = lang,
            label = getString(R.string.anki_include_audio),
            previewText = { etOriginal.text.toString() },
            initialChecked = prefs.ankiSentenceAudioEnabled,
            onCheckedChange = { prefs.ankiSentenceAudioEnabled = it },
            voiceOverride = { sentenceVoice },
            onVoicePillTap = { launchVoicePicker(PickTarget.Sentence, sentenceVoice) },
        )

        // Translation — same trick with R.id.etAnkiTranslation.
        ankiGroupHeader(root, getString(R.string.anki_group_translation))
        val translationCard = ankiGroupCard(root)
        etTranslation = buildEditField(initial = translation).apply {
            id = R.id.etAnkiTranslation
            if (translation.isBlank()) {
                hint = getString(R.string.status_translating)
                setHintTextColor(ctx.themeColor(R.attr.ptTextMuted))
            }
        }
        attachTranslationTouchWatcher(etTranslation)
        translationCard.addView(buildEditableFrame(etTranslation))

        // Words on card. The host tells us whether a follow-up
        // `applyWords` call is coming (drag → Anki path) vs. whether
        // an empty list is the final answer (sentence-only sheet
        // tapped while VM lookups are still loading). Inferring
        // "loading" from `words.isEmpty()` would mis-render the latter
        // as a permanent placeholder over a zero-word card.
        wordsLoading = arguments?.getBoolean(ARG_WORDS_LOADING, false) ?: false
        ankiGroupHeader(root, getString(R.string.anki_group_words_count, words.size))
        wordsHeaderTitle = (root.getChildAt(root.childCount - 1) as ViewGroup)
            .findViewById(R.id.tvGroupTitle)
        wordsCard = ankiGroupCard(root)
        addWordsHelperRow(wordsCard)
        rebuildWordRows()

        // Screenshot — built only when the file exists; collapses cleanly
        // on remove tap so the user gets immediate feedback that the
        // photo won't ship.
        if (screenshotPath != null) {
            val file = File(screenshotPath)
            if (file.exists()) {
                ankiGroupHeader(root, getString(R.string.anki_group_screenshot))
                screenshotHeader = root.getChildAt(root.childCount - 1)
                val screenshotCard = ankiGroupCard(root)
                screenshotGroup = root.getChildAt(root.childCount - 1)
                addScreenshotRow(screenshotCard, file) {
                    removeScreenshotFromUi()
                    // Mirror the removal back into the word tab when this
                    // fragment lives under WordAnkiReviewSheet — the two
                    // tabs share the same source media and would otherwise
                    // get out of sync.
                    (parentFragment as? WordAnkiReviewSheet)?.notifyScreenshotRemoved()
                }
            }
        }
    }

    /** Tear down the screenshot group from the live view tree and flip
     *  [includePhoto] off so [getCardData] no longer reports a photo
     *  for this side. Public so the parent sheet can keep both tabs in
     *  sync — when the user removes the photo in word-mode, the
     *  sentence-tab screenshot needs to disappear too. */
    fun removeScreenshotFromUi() {
        if (!includePhoto) return
        includePhoto = false
        screenshotHeader?.let { root.removeView(it) }
        screenshotGroup?.let { root.removeView(it) }
        screenshotHeader = null
        screenshotGroup = null
    }

    /** Wrap an [EditText] in a FrameLayout with a small pencil icon
     *  overlaid at top-right, marking the field as editable. The pencil
     *  is purely decorative — tapping anywhere on the field still gives
     *  it focus. */
    private fun buildEditableFrame(editText: EditText): FrameLayout {
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        val frame = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        // Reserve room on the right so the typed text doesn't run under
        // the pencil glyph.
        editText.setPadding(
            editText.paddingLeft,
            editText.paddingTop,
            (32 * density).toInt(),
            editText.paddingBottom,
        )
        frame.addView(editText)
        frame.addView(ImageView(ctx).apply {
            setImageResource(R.drawable.ic_edit)
            setColorFilter(ctx.themeColor(R.attr.ptTextHint))
            layoutParams = FrameLayout.LayoutParams(
                (14 * density).toInt(),
                (14 * density).toInt(),
                Gravity.TOP or Gravity.END,
            ).also {
                it.topMargin = (14 * density).toInt()
                it.marginEnd = (12 * density).toInt()
            }
            isClickable = false
        })
        return frame
    }

    /** Editable field used by both Original and Translation. Multi-line,
     *  inherits the card's surface, no underline. */
    private fun buildEditField(initial: String): EditText {
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        return EditText(ctx).apply {
            setText(initial)
            setTextColor(ctx.themeColor(R.attr.ptText))
            setHintTextColor(ctx.themeColor(R.attr.ptTextHint))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            background = null
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine(false)
            isVerticalScrollBarEnabled = false
            gravity = Gravity.TOP or Gravity.START
            minLines = 1
            setPadding((16 * density).toInt(), (12 * density).toInt(),
                (16 * density).toInt(), (12 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
    }

    private fun addWordsHelperRow(card: LinearLayout) {
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        card.addView(TextView(ctx).apply {
            text = getString(R.string.anki_words_helper)
            textSize = 12f
            setTextColor(ctx.themeColor(R.attr.ptTextMuted))
            setBackgroundColor(ctx.themeColor(R.attr.ptSurface))
            setLineSpacing(0f, 1.35f)
            setPadding((16 * density).toInt(), (10 * density).toInt(),
                (16 * density).toInt(), (10 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        })
        ankiInsetDivider(card, indentDp = 0)
    }

    private fun addScreenshotRow(card: LinearLayout, file: File, onRemove: () -> Unit) {
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        val frame = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val img = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val bmp = BitmapFactory.decodeFile(file.absolutePath)
        if (bmp != null) img.setImageBitmap(bmp)
        ivPhoto = img
        frame.addView(img)

        // Semi-transparent black circle keeps the white "✕" legible
        // against bright frames; size is fixed so the hit target stays
        // consistent regardless of the glyph's intrinsic width.
        val removeSize = (32 * density).toInt()
        frame.addView(TextView(ctx).apply {
            text = "✕"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_screenshot_remove)
            isClickable = true
            isFocusable = true
            contentDescription = getString(R.string.anki_screenshot_remove_content_description)
            layoutParams = FrameLayout.LayoutParams(
                removeSize, removeSize,
                Gravity.TOP or Gravity.END,
            ).also {
                it.topMargin = (8 * density).toInt()
                it.marginEnd = (8 * density).toInt()
            }
            setOnClickListener { onRemove() }
        })
        card.addView(frame)
    }

    // ── Word rows ────────────────────────────────────────────────────────

    private fun rebuildWordRows() {
        // Release any in-flight preview audio on sub-rows we're about to
        // remove — without this, a chip mid-playback would keep playing
        // for a beat after its row vanishes.
        wordAudioHandles.values.forEach { it.release() }
        wordAudioHandles.clear()

        // Strip everything after the helper row + its divider, then
        // re-emit current word rows. Helper row is at index 0; divider
        // at index 1; word rows live from index 2 onward.
        while (wordsCard.childCount > 2) {
            wordsCard.removeViewAt(wordsCard.childCount - 1)
        }
        if (words.isEmpty() && wordsLoading) {
            wordsCard.addView(buildWordsLoadingRow())
        } else {
            val ctx = requireContext()
            val prefs = Prefs(ctx)
            val lang = SourceLangId.fromCode(arguments?.getString(ARG_SOURCE_LANG))
                ?: SourceLangId.JA
            words.forEachIndexed { i, entry ->
                if (i > 0) ankiInsetDivider(wordsCard, indentDp = 16)
                wordsCard.addView(buildWordRow(entry))
                // Per-target-word audio sub-row, only when the user has
                // selected this word as a target. Inserted BEFORE the
                // next inter-word divider (handled at the top of the
                // next iteration), so the divider visually separates
                // word groups rather than splitting a word from its
                // own audio sub-row.
                if (entry.word in selectedWords) {
                    val seeded = wordAudioEnabled.getOrPut(entry.word) {
                        prefs.ankiWordAudioEnabled
                    }
                    // Seed per-word voice from the global pref the first
                    // time this word appears as a target. After this the
                    // map entry IS the cell's voice; null means
                    // "explicit Default", not "look up the pref again".
                    wordVoices.getOrPut(entry.word) { prefs.ttsVoiceName(lang) }
                    val word = entry.word
                    val handle = addCompactAudioToggleRow(
                        parent = wordsCard,
                        lang = lang,
                        label = getString(R.string.anki_include_audio),
                        previewText = { word },
                        initialChecked = seeded,
                        onCheckedChange = { checked ->
                            wordAudioEnabled[word] = checked
                            // Mirror the existing sentence-audio pref
                            // semantics: the last value the user picks
                            // becomes the default for the next card.
                            prefs.ankiWordAudioEnabled = checked
                        },
                        voiceOverride = { wordVoices[word] },
                        onVoicePillTap = {
                            launchVoicePicker(PickTarget.Word(word), wordVoices[word])
                        },
                    )
                    wordAudioHandles[word] = handle
                }
            }
        }
        // Live count in the group header.
        wordsHeaderTitle.text = getString(R.string.anki_group_words_count, words.size)
            .uppercase(java.util.Locale.ROOT)
    }

    private fun buildWordsLoadingRow(): View {
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        return TextView(ctx).apply {
            text = getString(R.string.words_loading)
            textSize = 14f
            setTextColor(ctx.themeColor(R.attr.ptTextMuted))
            setPadding((16 * density).toInt(), (12 * density).toInt(),
                (16 * density).toInt(), (12 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
    }

    private fun buildWordRow(entry: SentenceAnkiHtmlBuilder.WordEntry): View {
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        val isTarget = entry.word in selectedWords
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((16 * density).toInt(), (12 * density).toInt(),
                (12 * density).toInt(), (12 * density).toInt())
            // Target rows pick up the accent tint as a peripheral signal —
            // no "Target" label, just a quiet accent wash + word colour
            // change so the user can see what'll be highlighted on the
            // generated card.
            setBackgroundColor(if (isTarget) ctx.themeColor(R.attr.ptAccentTint) else 0)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (isTarget) selectedWords.remove(entry.word)
                else selectedWords.add(entry.word)
                rebuildWordRows()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val topLine = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topLine.addView(TextView(ctx).apply {
            text = entry.word
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ctx.themeColor(if (isTarget) R.attr.ptAccent else R.attr.ptText))
        })
        if (entry.reading.isNotBlank()) {
            topLine.addView(TextView(ctx).apply {
                text = entry.reading
                textSize = 12f
                setTextColor(ctx.themeColor(R.attr.ptTextHint))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.marginStart = (8 * density).toInt() }
            })
        }
        if (entry.freqScore > 0) {
            topLine.addView(TextView(ctx).apply {
                text = SentenceAnkiHtmlBuilder.starsString(entry.freqScore)
                textSize = 11f
                setTextColor(ctx.themeColor(R.attr.ptTextHint))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.marginStart = (8 * density).toInt() }
            })
        }
        col.addView(topLine)

        if (entry.meaning.isNotBlank()) {
            col.addView(TextView(ctx).apply {
                text = entry.meaning.lines().firstOrNull { it.isNotBlank() } ?: entry.meaning
                textSize = 13f
                setTextColor(ctx.themeColor(R.attr.ptTextMuted))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.topMargin = (3 * density).toInt() }
            })
        }

        row.addView(col)

        row.addView(TextView(ctx).apply {
            text = "✕"
            textSize = 16f
            setTextColor(ctx.themeColor(R.attr.ptTextMuted))
            isClickable = true
            isFocusable = true
            contentDescription = getString(R.string.anki_word_remove_content_description)
            setPadding((10 * density).toInt(), (4 * density).toInt(),
                (10 * density).toInt(), (4 * density).toInt())
            setOnClickListener {
                words.removeAll { it.word == entry.word }
                selectedWords.remove(entry.word)
                // Drop per-word audio state so the maps don't grow
                // across remove/re-add cycles. rebuildWordRows would
                // release the handle anyway, but the state slots need
                // explicit cleanup. Note: untap-to-deselect (handled
                // by the row's main click listener, not this ✕) leaves
                // these entries in place — only a hard remove drops them.
                wordAudioEnabled.remove(entry.word)
                wordVoices.remove(entry.word)
                rebuildWordRows()
            }
        })
        return row
    }

    // ── Async fill-in API ────────────────────────────────────────────

    /** Marks [translationUserTouched] the first time the user types anything
     *  we didn't write ourselves. [translationSuppressNextEdit] is flipped to
     *  true *immediately before* any programmatic write inside
     *  [applyTranslation], so the watcher swallows exactly that callback and
     *  treats the next callback (real user input) as touched. Note: the
     *  initial setText in [buildEditField] runs before this watcher attaches,
     *  so there is no callback to suppress at attach time — pre-arming here
     *  would silently consume the user's first real keystroke. */
    private fun attachTranslationTouchWatcher(field: EditText) {
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (translationSuppressNextEdit) {
                    translationSuppressNextEdit = false
                } else {
                    translationUserTouched = true
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    /**
     * Replaces the placeholder Translation field with [text] when an
     * async fetch lands. [text] = null renders the error variant
     * ("Couldn't translate") without clobbering anything the user has
     * typed in the meantime.
     */
    fun applyTranslation(text: String?) {
        if (!::etTranslation.isInitialized) return
        if (translationUserTouched) return
        val ctx = context ?: return
        if (text == null) {
            etTranslation.hint = getString(R.string.anki_translation_error)
            etTranslation.setHintTextColor(ctx.themeColor(R.attr.ptTextMuted))
            return
        }
        if (text.isBlank()) return
        translationSuppressNextEdit = true
        etTranslation.setText(text)
        etTranslation.hint = null
        arguments?.putString(ARG_TRANSLATION, text)
    }

    /**
     * Replaces the placeholder Words rows with [entries] when the
     * sentence's word lookups complete. [targetWord] re-applies the
     * auto-target highlight from [onViewCreated] so the looked-up word
     * stays selected when it lands in the list.
     */
    fun applyWords(entries: List<SentenceAnkiHtmlBuilder.WordEntry>, targetWord: String?) {
        if (!::wordsCard.isInitialized) return
        wordsLoading = false
        words.clear()
        words.addAll(entries)
        if (targetWord != null && words.any { it.word == targetWord }) {
            selectedWords.add(targetWord)
        }
        if (selectedWords.isNotEmpty()) {
            val sorted = words.sortedByDescending { it.word in selectedWords }
            words.clear()
            words.addAll(sorted)
        }
        arguments?.let { args ->
            args.putStringArray(ARG_WORDS, words.map { it.word }.toTypedArray())
            args.putStringArray(ARG_READINGS, words.map { it.reading }.toTypedArray())
            args.putStringArray(ARG_MEANINGS, words.map { it.meaning }.toTypedArray())
            args.putIntArray(ARG_FREQ_SCORES, words.map { it.freqScore }.toIntArray())
        }
        rebuildWordRows()
    }

    companion object {
        private const val ARG_ORIGINAL        = "japanese"
        private const val ARG_TRANSLATION     = "translation"
        private const val ARG_WORDS           = "words"
        private const val ARG_READINGS        = "readings"
        private const val ARG_MEANINGS        = "meanings"
        private const val ARG_FREQ_SCORES     = "freq_scores"
        private const val ARG_SCREENSHOT_PATH = "screenshot_path"
        private const val ARG_TARGET_WORD     = "target_word"
        private const val ARG_SOURCE_LANG     = "source_lang"
        private const val ARG_WORDS_LOADING   = "words_loading"

        fun newInstance(
            japanese: String,
            translation: String,
            words: List<SentenceAnkiHtmlBuilder.WordEntry>,
            screenshotPath: String?,
            targetWord: String? = null,
            sourceLangId: SourceLangId = SourceLangId.JA,
            wordsLoading: Boolean = false,
        ) = SentenceAnkiContentFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_ORIGINAL, japanese)
                putString(ARG_TRANSLATION, translation)
                putStringArray(ARG_WORDS, words.map { it.word }.toTypedArray())
                putStringArray(ARG_READINGS, words.map { it.reading }.toTypedArray())
                putStringArray(ARG_MEANINGS, words.map { it.meaning }.toTypedArray())
                putIntArray(ARG_FREQ_SCORES, words.map { it.freqScore }.toIntArray())
                if (screenshotPath != null) putString(ARG_SCREENSHOT_PATH, screenshotPath)
                if (targetWord != null) putString(ARG_TARGET_WORD, targetWord)
                putString(ARG_SOURCE_LANG, sourceLangId.code)
                putBoolean(ARG_WORDS_LOADING, wordsLoading)
            }
        }
    }
}
