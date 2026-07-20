package com.playtranslate.ui

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.playtranslate.Prefs
import com.playtranslate.language.HintTextKind
import com.playtranslate.language.SourceLanguageProfiles
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Hotkeys page state, projected from [Prefs]. Exposes the *semantic* state
 * (raw hotkey combos, whether the source language has a hint layer, whether the
 * QS-tile cell should show); the Activity formats it for display and owns the
 * interactive bits (a11y gate, the key-capture dialog, the StatusBarManager
 * tile request). Setters write through to [Prefs] and the new value returns via
 * the observed flow — prefs is the source of truth.
 *
 * The source-language hint kind is read per-derivation but not observed: it's
 * only changed from the root settings screen, never reachable while this page
 * is open.
 */
class HotkeysSettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = Prefs(app)

    val state: StateFlow<HotkeysUiState> =
        prefs.observe(
            Prefs.KEY_HOTKEY_TRANSLATION,
            Prefs.KEY_HOTKEY_FURIGANA,
            Prefs.KEY_HOTKEY_TRANSLATION_TAP,
            Prefs.KEY_HOTKEY_FURIGANA_TAP,
            Prefs.KEY_HOTKEY_CAPTURE_TAP,
            Prefs.KEY_QUICK_TILE_ADDED,
        )
            .map { derive() }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.Eagerly, derive())

    private fun derive(): HotkeysUiState {
        val hintKind = SourceLanguageProfiles[prefs.sourceLangId].hintTextKind
        return HotkeysUiState(
            translationHotkey = prefs.hotkeyTranslation,
            translationTapHotkey = prefs.hotkeyTranslationTap,
            captureTapHotkey = prefs.hotkeyCaptureTap,
            showFuriganaSection = hintKind != HintTextKind.NONE,
            furiganaHotkey = prefs.hotkeyFurigana,
            furiganaTapHotkey = prefs.hotkeyFuriganaTap,
            hintKind = hintKind,
            // The QS-tile cell exists only on API 33+ (StatusBarManager
            // .requestAddTileService). It stays visible after the tile is added,
            // switching from an "add" CTA to a checked "added" state.
            addTileVisible = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
            tileAdded = prefs.quickTileAdded,
        )
    }

    fun setTranslationHotkey(combo: String) { prefs.hotkeyTranslation = combo }
    fun clearTranslationHotkey() { prefs.hotkeyTranslation = "" }
    fun setFuriganaHotkey(combo: String) { prefs.hotkeyFurigana = combo }
    fun clearFuriganaHotkey() { prefs.hotkeyFurigana = "" }
    fun setTranslationTapHotkey(combo: String) { prefs.hotkeyTranslationTap = combo }
    fun clearTranslationTapHotkey() { prefs.hotkeyTranslationTap = "" }
    fun setFuriganaTapHotkey(combo: String) { prefs.hotkeyFuriganaTap = combo }
    fun clearFuriganaTapHotkey() { prefs.hotkeyFuriganaTap = "" }
    fun setCaptureTapHotkey(combo: String) { prefs.hotkeyCaptureTap = combo }
    fun clearCaptureTapHotkey() { prefs.hotkeyCaptureTap = "" }
    fun markQuickTileAdded() { prefs.quickTileAdded = true }
}

data class HotkeysUiState(
    val translationHotkey: String,
    val translationTapHotkey: String,
    val captureTapHotkey: String,
    val showFuriganaSection: Boolean,
    val furiganaHotkey: String,
    val furiganaTapHotkey: String,
    val hintKind: HintTextKind,
    val addTileVisible: Boolean,
    val tileAdded: Boolean,
)
