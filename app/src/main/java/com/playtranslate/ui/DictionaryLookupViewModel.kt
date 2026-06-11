package com.playtranslate.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.playtranslate.Prefs
import com.playtranslate.language.SourceLanguageEngines
import com.playtranslate.language.TokenSpan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Drives the standalone dictionary-lookup screen. Owns a [query] flow that the
 * search field writes to and a derived [state] of rendered word rows. Each
 * keystroke cancels any in-flight lookup ([collectLatest]) — no debounce; the
 * indexed prefix scans are fast enough that cancellation alone keeps results
 * fresh, and previous rows stay on screen while the next query resolves.
 *
 * Two modes, auto-selected by tokenizing the query:
 *  - **Segmentation** (≥2 tokens — a phrase/sentence): look up every segmented
 *    word, exactly like the translation-result surface.
 *  - **Prefix** (0–1 tokens — a single, possibly unfinished word): live
 *    prefix-completion candidates via [SourceLanguageEngine.searchPrefix],
 *    ranked exact-first then by frequency. Falls back to resolving the lone
 *    token when prefix search finds nothing but the token itself resolves
 *    (e.g. a conjugated input like 食べた → 食べる).
 *
 * Both paths share the [resolveWordRows] pipeline, so the cells render
 * identically to the translation-result word rows.
 */
class DictionaryLookupViewModel(app: Application) : AndroidViewModel(app) {

    enum class Mode { IDLE, PREFIX, SEGMENTATION }

    data class UiState(
        /** The query the current [rows] / empty-state belong to — NOT the live
         *  keystroke during a load. Kept consistent with [rows] so the screen's
         *  tap actions (which read [query]/[mode] for sentence context) never
         *  pair a word from one query with another query's sentence. */
        val query: String = "",
        val rows: List<RowState> = emptyList(),
        val mode: Mode = Mode.IDLE,
        val isLoading: Boolean = false,
        /** A lookup threw (DB/schema/engine failure). Distinct from "no
         *  results" so the screen can say so instead of silently showing the
         *  idle/empty copy — and so stale rows are never left actionable. */
        val hasError: Boolean = false,
    )

    private val _query = MutableStateFlow("")

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _query.collectLatest { raw ->
                val q = raw.trim()
                if (q.isEmpty()) {
                    _state.value = UiState()
                    return@collectLatest
                }
                // Flag loading but DON'T touch query/rows/mode: the prior
                // (settled) result stays on screen and self-consistent while
                // the next query resolves.
                _state.value = _state.value.copy(isLoading = true, hasError = false)
                try {
                    val (rows, mode) = runQuery(q)
                    _state.value = UiState(query = q, rows = rows, mode = mode, isLoading = false)
                } catch (e: CancellationException) {
                    throw e  // superseded by a newer query — let it win
                } catch (_: Exception) {
                    // A lookup threw. Drop the stale rows so they can't be
                    // tapped (wrong-word detail/speech/Anki); surface an error
                    // tied to this query instead.
                    _state.value = UiState(query = q, isLoading = false, hasError = true)
                }
            }
        }
    }

    /** Update the active query. Safe to call on every keystroke. */
    fun setQuery(text: String) {
        _query.value = text
    }

    private suspend fun runQuery(q: String): Pair<List<RowState>, Mode> {
        val app = getApplication<Application>()
        // Snapshot prefs ONCE, before tokenizing, so tokenize + resolve run on
        // one consistent language pair even if settings change mid-flight.
        val prefs = Prefs(app)
        val engine = SourceLanguageEngines.get(app, prefs.sourceLangId)
        val context = WordLookupContext(engine, prefs.targetLang, prefs.targetChineseVariant)
        val tokens = withContext(Dispatchers.IO) { engine.tokenize(q) }
        if (tokens.size >= 2) {
            return resolveWordRows(app, context, tokens).rows to Mode.SEGMENTATION
        }
        // Single (possibly unfinished) word → live prefix completions.
        val candidates: List<TokenSpan> =
            withContext(Dispatchers.IO) { engine.searchPrefix(q, PREFIX_LIMIT) }
        val toResolve = if (candidates.isNotEmpty()) candidates else tokens
        return resolveWordRows(app, context, toResolve).rows to Mode.PREFIX
    }

    private companion object {
        const val PREFIX_LIMIT = 20
    }
}
