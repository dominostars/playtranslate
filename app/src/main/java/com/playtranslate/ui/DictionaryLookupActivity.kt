package com.playtranslate.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.viewModels
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.card.MaterialCardView
import com.playtranslate.Prefs
import com.playtranslate.AnkiManager
import com.playtranslate.R
import com.playtranslate.language.ChineseScriptVariant
import com.playtranslate.language.SourceLangId
import com.playtranslate.themeColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Standalone dictionary-lookup screen: type or paste source-language text and
 * get live word results. Reuses the translation-result [WordResultCell] and
 * its tap / speak / Anki actions, driven by [DictionaryLookupViewModel] (which
 * auto-selects prefix-completion vs. sentence-segmentation per query).
 *
 * Reached from Settings → Debug for now (debug builds only). Subclasses
 * [SettingsSubPageActivity] for the themed toolbar, edge-to-edge insets
 * (including IME), and back navigation.
 */
class DictionaryLookupActivity : SettingsSubPageActivity() {

    override val layoutResId = R.layout.activity_dictionary_lookup

    private val vm: DictionaryLookupViewModel by viewModels()
    private val prefs by lazy { Prefs(this) }

    private lateinit var resultsContainer: LinearLayout
    private lateinit var resultsCard: MaterialCardView
    private lateinit var statusView: TextView
    private lateinit var countView: TextView

    /** Per-word cells for in-place Anki-badge updates, mirroring the
     *  translation-result fragment. */
    private var cellsByWord: Map<String, List<WordResultCell>> = emptyMap()
    private val ankiDecksByWord = HashMap<String, List<String>>()

    /** Identity guard so the loading-state emission (which keeps the prior row
     *  list) doesn't rebuild identical cells. */
    private var lastRenderedRows: List<RowState>? = null

    override fun onContentCreated(savedInstanceState: Bundle?) {
        resultsContainer = findViewById(R.id.llResults)
        resultsCard = findViewById(R.id.cardResults)
        statusView = findViewById(R.id.tvStatus)
        countView = findViewById(R.id.tvCount)

        findViewById<TextView>(R.id.tvLangPair).text = languagePairLabel()

        val search = findViewById<EditText>(R.id.etSearch)
        val clear = findViewById<ImageButton>(R.id.btnClear)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString().orEmpty()
                clear.isVisible = text.isNotEmpty()
                vm.setQuery(text)
            }
        })
        clear.setOnClickListener { search.setText("") }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.state.collect { render(it) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Deck membership can change while away (added here or in AnkiDroid);
        // re-evaluate so badges aren't stuck on a stale pre-add state.
        refreshBadges()
    }

    // ── Rendering ─────────────────────────────────────────────────────────

    private fun render(state: DictionaryLookupViewModel.UiState) {
        countView.text = when (state.rows.size) {
            0 -> ""
            1 -> getString(R.string.dictionary_entries_count_one, 1)
            else -> getString(R.string.dictionary_entries_count_other, state.rows.size)
        }

        when {
            state.rows.isNotEmpty() -> {
                resultsCard.isVisible = true
                statusView.isVisible = false
            }
            state.isLoading -> {
                resultsCard.isVisible = false
                statusView.isVisible = true
                statusView.text = getString(R.string.dictionary_status_looking_up)
            }
            state.hasError -> {
                resultsCard.isVisible = false
                statusView.isVisible = true
                statusView.text = getString(R.string.dictionary_status_error)
            }
            state.query.isBlank() -> {
                resultsCard.isVisible = false
                statusView.isVisible = true
                statusView.text = getString(R.string.dictionary_status_idle)
            }
            else -> {
                resultsCard.isVisible = false
                statusView.isVisible = true
                statusView.text = getString(R.string.dictionary_status_no_results)
            }
        }

        renderRows(state.rows)
    }

    private fun renderRows(rows: List<RowState>) {
        // Same list instance (loading emission keeps prior rows) → no rebuild.
        if (rows === lastRenderedRows) return
        lastRenderedRows = rows

        resultsContainer.removeAllViews()
        if (rows.isEmpty()) {
            cellsByWord = emptyMap()
            return
        }
        val byWord = HashMap<String, MutableList<WordResultCell>>()
        rows.forEachIndexed { idx, rowState ->
            if (idx > 0) resultsContainer.addView(inflateDivider())
            val cell = WordResultCell(this)
            bindCell(cell, rowState)
            resultsContainer.addView(cell)
            byWord.getOrPut(rowState.displayWord) { mutableListOf() }.add(cell)
        }
        cellsByWord = byWord
        loadDeckBadges(rows.map { it.displayWord }, byWord)
    }

    private fun bindCell(cell: WordResultCell, rowState: RowState) {
        val data = WordDefinitionData(
            word = rowState.displayWord,
            reading = rowState.reading.ifEmpty { null },
            senses = rowState.senses,
            freqScore = rowState.freqScore,
            isCommon = rowState.isCommon,
            ankiDecks = ankiDecksByWord[rowState.displayWord].orEmpty(),
        )
        cell.bind(
            data = data,
            scale = WordResultCell.DEFAULT_SCALE,
            onCellTap = { openWordDetail(rowState) },
            onSpeak = { speakFromCell(cell, rowState) },
            onAnki = { launchAnki(rowState) },
        )
    }

    private fun openWordDetail(rowState: RowState) {
        val state = vm.state.value
        // Sentence context only makes sense for the segmentation mode, where
        // the query IS a sentence; prefix completions have no enclosing text.
        val sentenceOriginal =
            if (state.mode == DictionaryLookupViewModel.Mode.SEGMENTATION) state.query else null
        WordDetailBottomSheet.newInstance(
            word = rowState.displayWord,
            reading = rowState.reading.ifEmpty { null },
            screenshotPath = null,
            sentenceOriginal = sentenceOriginal,
            sentenceTranslation = null,
            sentenceWordResults = state.rows.toLegacyMap(),
        ).show(supportFragmentManager, WordDetailBottomSheet.TAG)
    }

    /** Speak a cell's word, driving that cell's own spinner. Each cell owns
     *  its in-flight [WordResultCell.speakJob] so concurrent taps on different
     *  rows don't clobber one another. */
    private fun speakFromCell(cell: WordResultCell, rowState: RowState) {
        if (cell.speakJob?.isActive == true) return
        cell.speakJob = lifecycleScope.launch {
            cell.setSpeakLoading(true)
            try {
                speakWord(
                    TtsAlertTarget.InActivity(this@DictionaryLookupActivity),
                    LensSpeakChip.Request(
                        rowState.displayWord,
                        prefs.sourceLangId,
                        reading = rowState.reading.ifEmpty { null },
                    ),
                )
            } finally {
                cell.setSpeakLoading(false)
            }
        }
    }

    private fun launchAnki(rowState: RowState) {
        val ankiManager = AnkiManager(this)
        if (!ankiManager.isAnkiDroidInstalled()) {
            showAnkiNotInstalledDialog(this)
            return
        }
        val state = vm.state.value
        val readingForExtra = rowState.reading
            .takeIf { it.isNotEmpty() && it != rowState.displayWord } ?: ""
        val intent = Intent(this, AnkiPermissionActivity::class.java).apply {
            putExtra(WordAnkiReviewActivity.EXTRA_WORD, rowState.displayWord)
            putExtra(WordAnkiReviewActivity.EXTRA_READING, readingForExtra)
            putExtra(WordAnkiReviewActivity.EXTRA_POS, rowState.ankiPos)
            putExtra(WordAnkiReviewActivity.EXTRA_DEFINITION, rowState.meaning)
            putExtra(WordAnkiReviewActivity.EXTRA_FREQ_SCORE, rowState.freqScore)
            if (state.mode == DictionaryLookupViewModel.Mode.SEGMENTATION) {
                putExtra(WordAnkiReviewActivity.EXTRA_SENTENCE_ORIGINAL, state.query)
            }
            putExtra(WordAnkiReviewActivity.EXTRA_SOURCE_LANG, prefs.sourceLangId.code)
        }
        startActivity(intent)
    }

    // ── Anki deck badges ──────────────────────────────────────────────────

    /** Batched "already in Anki" lookup; fills each matching cell's meta-row
     *  deck pill. Gated + silent; mirrors the translation-result fragment. */
    private fun loadDeckBadges(words: List<String>, cells: Map<String, List<WordResultCell>>) {
        val anki = AnkiManager(this)
        if (!anki.isAnkiDroidInstalled() || !anki.hasPermission()) return
        val uncached = words.distinct().filter { it !in ankiDecksByWord }
        if (uncached.isEmpty()) return
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { anki.decksByWord(uncached) }
            for (w in uncached) {
                val decks = result[w].orEmpty()
                ankiDecksByWord[w] = decks
                if (decks.isEmpty()) continue
                cells[w]?.forEach { it.updateAnkiDecks(decks) }
            }
        }
    }

    private fun refreshBadges() {
        val cells = cellsByWord
        if (cells.isEmpty()) return
        val anki = AnkiManager(this)
        if (!anki.isAnkiDroidInstalled() || !anki.hasPermission()) {
            cells.values.flatten().forEach { it.updateAnkiDecks(emptyList()) }
            return
        }
        ankiDecksByWord.clear()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { anki.decksByWord(cells.keys.toList()) }
            for ((word, list) in cells) {
                val decks = result[word].orEmpty()
                ankiDecksByWord[word] = decks
                list.forEach { it.updateAnkiDecks(decks) }
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun inflateDivider(): View {
        val dp1 = resources.displayMetrics.density.toInt().coerceAtLeast(1)
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp1).apply {
                marginStart = resources.getDimensionPixelSize(R.dimen.pt_row_h_padding)
            }
            setBackgroundColor(themeColor(R.attr.ptDivider))
        }
    }

    /** "Japanese → English"-style label, matching how Settings resolves the
     *  source/target display names (system locale). */
    private fun languagePairLabel(): String {
        val source = SourceLangId.fromCode(prefs.sourceLang)?.displayName()
            ?: Locale.forLanguageTag(prefs.sourceLang)
                .getDisplayLanguage(Locale.getDefault())
                .replaceFirstChar { it.uppercase(Locale.getDefault()) }
        val target = ChineseScriptVariant.targetDisplayName(
            prefs.targetLang,
            prefs.targetChineseVariant,
            Locale.forLanguageTag(prefs.targetLang),
        )
        return getString(R.string.dictionary_lang_pair, source, target)
    }
}
