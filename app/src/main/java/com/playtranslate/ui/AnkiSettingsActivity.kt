package com.playtranslate.ui

import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.playtranslate.AnkiManager
import com.playtranslate.Prefs
import com.playtranslate.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Anki Flashcards sub-page: deck + card-type selection and (for non-default
 * card types) field-mapping. Reached from the root Anki cell only once AnkiDroid
 * is installed and permission is granted.
 *
 * Renders the rows from [AnkiSettingsViewModel.state]; the pickers (deck /
 * card-type / field-mapping dialogs) write prefs and the rows re-render through
 * the observed flow. On resume it heals the saved deck/card-type against
 * AnkiDroid's live state, and finishes back to root if permission was revoked
 * while away.
 */
class AnkiSettingsActivity : SettingsSubPageActivity() {

    override val layoutResId = R.layout.activity_anki_settings

    private val vm: AnkiSettingsViewModel by viewModels()

    private lateinit var rowAnkiDeck: View
    private lateinit var rowAnkiCardType: View
    private lateinit var dividerAnkiCardType: View
    private lateinit var rowAnkiEditMapping: View
    private lateinit var dividerAnkiEditMapping: View

    override fun onContentCreated(savedInstanceState: Bundle?) {
        rowAnkiDeck = findViewById(R.id.rowAnkiDeck)
        rowAnkiCardType = findViewById(R.id.rowAnkiCardType)
        dividerAnkiCardType = findViewById(R.id.dividerAnkiCardType)
        rowAnkiEditMapping = findViewById(R.id.rowAnkiEditMapping)
        dividerAnkiEditMapping = findViewById(R.id.dividerAnkiEditMapping)

        rowAnkiDeck.findViewById<TextView>(R.id.tvRowTitle).text =
            getString(R.string.anki_deck_row_label)
        rowAnkiCardType.findViewById<TextView>(R.id.tvRowTitle).text =
            getString(R.string.anki_card_type_row_label)
        rowAnkiEditMapping.findViewById<TextView>(R.id.tvRowTitle).text =
            getString(R.string.anki_card_type_edit_mapping_row_label)

        rowAnkiDeck.setOnClickListener { showDeckPicker() }
        rowAnkiCardType.setOnClickListener { showCardTypePicker() }
        rowAnkiEditMapping.setOnClickListener { showCardTypeMapping() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.state.collect { render(it) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Permission can be revoked in AnkiDroid/system settings while we're
        // away; this page only makes sense with it granted, so bounce back to
        // root (which then shows the grant cell).
        if (!AnkiManager(this).hasPermission()) {
            finish()
            return
        }
        // Heal the saved deck + card type against AnkiDroid's live state (a
        // deck/model deleted or renamed externally).
        validateAnkiDeck()
        validateAnkiCardType()
    }

    private fun render(state: AnkiUiState) {
        rowAnkiDeck.findViewById<TextView>(R.id.tvRowValue).text =
            state.deckName.ifEmpty { getString(R.string.anki_deck_not_selected_subtitle) }
        rowAnkiCardType.findViewById<TextView>(R.id.tvRowValue).text =
            state.cardTypeName.ifBlank { getString(R.string.anki_card_type_row_empty) }
        rowAnkiEditMapping.findViewById<TextView>(R.id.tvRowValue).text = ""
        rowAnkiEditMapping.isVisible = state.showEditMapping
        dividerAnkiEditMapping.isVisible = state.showEditMapping
    }

    private fun showDeckPicker() {
        // The picker writes ankiDeckId/Name on selection; the row re-renders
        // through the observed flow.
        AnkiDeckPickerDialog.newInstance()
            .also { it.onDeckSelected = {} }
            .show(supportFragmentManager, AnkiDeckPickerDialog.TAG)
    }

    private fun showCardTypePicker() {
        // Settings doesn't know the send mode; SENTENCE is the default for
        // Basic-shape detection. The mapping dialog lets the user override.
        AnkiCardTypePickerDialog.newInstance(CardMode.SENTENCE)
            .also { it.onCardTypePicked = { _, _ -> } }
            .show(supportFragmentManager, AnkiCardTypePickerDialog.TAG)
    }

    /** Open the field-mapping dialog for the saved non-default card type, after
     *  re-validating it against AnkiDroid's live model list. */
    private fun showCardTypeMapping() {
        val prefs = Prefs(this)
        val pickedId = prefs.ankiModelId
        if (pickedId == -1L) return // row is hidden in this state
        lifecycleScope.launch {
            val models = withContext(Dispatchers.IO) { AnkiManager(this@AnkiSettingsActivity).getModels() }
            if (models.isEmpty()) {
                // Transient query / permission failure — a working AnkiDroid
                // install always has built-in Basic + Cloze. Don't reset prefs.
                Toast.makeText(this@AnkiSettingsActivity, R.string.anki_models_unavailable, Toast.LENGTH_LONG).show()
                return@launch
            }
            val picked = models.firstOrNull { it.id == pickedId }
            if (picked == null) {
                // Model genuinely disappeared — fall back to default.
                prefs.ankiModelId = -1L
                prefs.ankiModelName = ""
                Toast.makeText(this@AnkiSettingsActivity, R.string.anki_card_type_stale_fallback, Toast.LENGTH_SHORT).show()
                return@launch
            }
            if (AnkiCardTypeMapper.isBasicShape(picked.fieldNames)) {
                // Basic-shape templates bypass mapping (Front/Back derived from
                // the send mode); explain instead of opening the dialog.
                Toast.makeText(this@AnkiSettingsActivity, R.string.anki_card_type_basic_no_mapping, Toast.LENGTH_LONG).show()
                return@launch
            }
            AnkiFieldMappingDialog.newInstance(
                modelId = picked.id,
                modelName = picked.name,
                fieldNames = picked.fieldNames,
                mode = CardMode.SENTENCE,
            ).also { it.onSaved = { _, _ -> } }
                .show(supportFragmentManager, AnkiFieldMappingDialog.TAG)
        }
    }

    private fun validateAnkiDeck() {
        val prefs = Prefs(this)
        if (prefs.ankiDeckId == 0L) return
        lifecycleScope.launch {
            val decks = withContext(Dispatchers.IO) { AnkiManager(this@AnkiSettingsActivity).getDecks() }
            if (decks.isEmpty()) return@launch
            if (!decks.containsKey(prefs.ankiDeckId)) {
                val first = decks.entries.first()
                prefs.ankiDeckId = first.key
                prefs.ankiDeckName = first.value
            }
        }
    }

    private fun validateAnkiCardType() {
        val prefs = Prefs(this)
        if (prefs.ankiModelId == -1L) return
        lifecycleScope.launch {
            val models = withContext(Dispatchers.IO) { AnkiManager(this@AnkiSettingsActivity).getModels() }
            if (models.isEmpty()) return@launch
            val match = models.firstOrNull { it.id == prefs.ankiModelId }
            if (match == null) {
                prefs.ankiModelId = -1L
                prefs.ankiModelName = ""
            } else if (match.name != prefs.ankiModelName) {
                prefs.ankiModelName = match.name
            }
        }
    }
}
