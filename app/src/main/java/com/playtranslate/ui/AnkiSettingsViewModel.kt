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
 * Anki page state, projected from [Prefs] (the deck + card-type the user picked;
 * the edit-mapping row only shows for a non-default card type). The Activity
 * owns the interactive pickers (deck / card-type / field-mapping dialogs) and
 * the AnkiDroid-liveness validation; the pickers write prefs and the rows
 * re-render through the observed flow. Reached only when AnkiDroid is installed
 * and permission is granted — the root Anki cell gates that.
 */
class AnkiSettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = Prefs(app)

    val state: StateFlow<AnkiUiState> =
        prefs.observe(
            Prefs.KEY_ANKI_DECK_NAME,
            Prefs.KEY_ANKI_MODEL_ID,
            Prefs.KEY_ANKI_MODEL_NAME,
        )
            .map { derive() }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.Eagerly, derive())

    private fun derive(): AnkiUiState = AnkiUiState(
        deckName = prefs.ankiDeckName,
        cardTypeName = prefs.ankiModelName,
        // The "Edit field mapping" row is only relevant for a non-default card
        // type — the Default path uses the fixed two-field schema (id == -1L).
        showEditMapping = prefs.ankiModelId != -1L,
    )
}

data class AnkiUiState(
    val deckName: String,
    val cardTypeName: String,
    val showEditMapping: Boolean,
)
