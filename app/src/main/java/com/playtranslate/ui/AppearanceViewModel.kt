package com.playtranslate.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.playtranslate.Prefs
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Appearance page state, projected from [Prefs]. Prefs is the source of truth:
 * [state] re-derives from `Prefs.observe`, and the setters write through Prefs
 * and let the new value return via the observed flow (unidirectional). Mirrors
 * the app's [TranslationResultViewModel] MVVM shape; [AndroidViewModel] because
 * it needs an app [android.content.Context] for [Prefs].
 */
class AppearanceViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = Prefs(app)

    val state: StateFlow<AppearanceUiState> =
        prefs.observe(Prefs.KEY_THEME_MODE, Prefs.KEY_ACCENT_NAME)
            .map { AppearanceUiState(prefs.themeMode, prefs.accent) }
            .distinctUntilChanged()
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                AppearanceUiState(prefs.themeMode, prefs.accent),
            )

    fun setThemeMode(mode: ThemeMode) {
        prefs.themeMode = mode
    }

    fun setAccent(accent: AccentColor) {
        prefs.accentName = accent.name
    }
}

data class AppearanceUiState(
    val themeMode: ThemeMode,
    val accent: AccentColor,
)
