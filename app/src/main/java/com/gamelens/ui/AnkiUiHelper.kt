package com.gamelens.ui

import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.gamelens.AnkiManager
import com.gamelens.Prefs
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

