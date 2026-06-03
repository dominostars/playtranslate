package com.playtranslate.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.playtranslate.AnkiManager
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.language.SourceLangId
import com.playtranslate.tts.TtsEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Root Settings drill-down state, projected from [Prefs] + live system state.
 *
 * Scope (Stage 6, "right-sized"): this VM owns the three state-dependent
 * CONFIGURE cells and the language names — the parts with logic worth testing
 * or a reactive payoff. The power card / capture-lifecycle surface and the
 * stale-pack update card are deliberately NOT here: they project *live system
 * state* (accessibility grant, capture-live, stale packs on disk) that has no
 * pref to observe, so they stay imperative + resume-driven in
 * [SettingsRenderer] — the same call made for [CaptureOverlaySettingsActivity].
 *
 * Two state channels:
 *  - **Reactive** — the language names re-derive from `Prefs.observe`, so a
 *    change made on the language-setup screens shows with no onResume catch-up.
 *  - **Polled** — Anki install/permission and TTS-engine availability are
 *    system state with no pref signal; [refresh] re-reads them and is called
 *    from the host on resume (they can change while Settings is backgrounded).
 *
 * [AndroidViewModel] because every projection needs an app [android.content.Context].
 */
class RootSettingsViewModel(app: Application) : AndroidViewModel(app) {

    /** Anki CONFIGURE cell: the get-app → grant → navigate conditional. */
    sealed interface AnkiCell {
        /** AnkiDroid not installed — tapping opens the Play Store listing. */
        object GetApp : AnkiCell
        /** Installed but the API permission isn't granted — tap requests it. */
        object GrantAccess : AnkiCell
        /** Installed + granted — tap opens [AnkiSettingsActivity]. */
        object Navigate : AnkiCell
    }

    /** TTS CONFIGURE cell (no sub-page; behaves like the old TTS row). */
    sealed interface TtsCell {
        /** Engine availability not resolved yet (async check in flight). */
        object Loading : TtsCell
        /** A usable engine — [voiceLabel] is the selected voice ("Voice 2" /
         *  "Default"); tapping opens the per-language voice picker. */
        data class Available(val voiceLabel: String) : TtsCell
        /** No usable engine — tapping shows the "No Text-to-Speech" alert. */
        object NoEngine : TtsCell
    }

    data class UiState(
        val sourceName: String,
        val targetName: String,
        val anki: AnkiCell,
        val tts: TtsCell,
    )

    private val prefs = Prefs(app)

    private val _state = MutableStateFlow(seed())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        // Language names react to source/target writes from the language-setup
        // screens. observe() seeds on collect, so this also covers first render.
        viewModelScope.launch {
            prefs.observe(Prefs.KEY_SOURCE_LANG, Prefs.KEY_TARGET_LANG).collect {
                _state.value = _state.value.copy(
                    sourceName = resolveSourceName(),
                    targetName = resolveTargetName(),
                )
            }
        }
    }

    /** Re-poll the system-state-backed cells (Anki permission, TTS engine).
     *  Called from the host on resume — neither is a pref, so they can change
     *  with no signal while Settings is backgrounded (engine installed, API
     *  permission granted/revoked). */
    fun refresh() {
        _state.value = _state.value.copy(anki = resolveAnkiCell())
        // TTS availability is an async engine bind — resolve then patch in.
        viewModelScope.launch {
            _state.value = _state.value.copy(tts = resolveTtsCell())
        }
    }

    private fun seed() = UiState(
        sourceName = resolveSourceName(),
        targetName = resolveTargetName(),
        anki = resolveAnkiCell(),
        tts = TtsCell.Loading,
    )

    private fun resolveAnkiCell(): AnkiCell {
        val anki = AnkiManager(getApplication())
        return when {
            !anki.isAnkiDroidInstalled() -> AnkiCell.GetApp
            !anki.hasPermission() -> AnkiCell.GrantAccess
            else -> AnkiCell.Navigate
        }
    }

    private suspend fun resolveTtsCell(): TtsCell {
        val ctx = getApplication<Application>()
        if (!TtsEngine.isEngineAvailable(ctx)) return TtsCell.NoEngine
        val lang = prefs.sourceLangId
        val voices = TtsEngine.voicesFor(ctx, lang)
        val savedName = prefs.ttsVoiceName(lang)
        val idx = if (savedName == null) -1 else voices.indexOfFirst { it.name == savedName }
        val label = if (idx >= 0) ctx.getString(R.string.tts_voice_numbered, idx + 1)
                    else ctx.getString(R.string.tts_voice_default)
        return TtsCell.Available(label)
    }

    private fun resolveSourceName(): String =
        SourceLangId.fromCode(prefs.sourceLang)?.displayName()
            ?: Locale.forLanguageTag(prefs.sourceLang)
                .getDisplayLanguage(Locale.getDefault())
                .replaceFirstChar { it.uppercase(Locale.getDefault()) }

    private fun resolveTargetName(): String {
        val locale = Locale.forLanguageTag(prefs.targetLang)
        return locale.getDisplayLanguage(locale).replaceFirstChar { it.uppercase(locale) }
    }
}
