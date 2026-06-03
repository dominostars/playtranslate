package com.playtranslate.ui

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.res.ColorStateList
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.playtranslate.PlayTranslateAccessibilityService
import com.playtranslate.PlayTranslateTileService
import com.playtranslate.R
import com.playtranslate.themeColor
import com.playtranslate.language.HintTextKind
import kotlinx.coroutines.launch

/**
 * Hotkeys sub-page: the relocated "Add Quick Settings tile" cell + the
 * translation / furigana-pinyin hotkey rows.
 *
 * Renders from [HotkeysSettingsViewModel.state] (prefs are the source of
 * truth). The interactive bits stay Activity-side: the a11y gate
 * ([showAccessibilityRequiredAlert]), the key-capture [HotkeySetupDialog]
 * (shown on this Activity's fragment manager), and the StatusBarManager tile
 * request. A hotkey set/cleared, or the tile being added, writes prefs via the
 * VM and the row re-renders through the observed flow.
 */
class HotkeysSettingsActivity : SettingsSubPageActivity() {

    override val layoutResId = R.layout.activity_hotkeys_settings

    private val vm: HotkeysSettingsViewModel by viewModels()

    private lateinit var cardQuickTile: MaterialCardView
    private lateinit var rowAddQuickTile: View
    private lateinit var rowHotkeyTranslation: View
    private lateinit var rowHotkeyFurigana: View
    private lateinit var dividerHotkeyFurigana: View

    override fun onContentCreated(savedInstanceState: Bundle?) {
        cardQuickTile = findViewById(R.id.cardQuickTile)
        rowAddQuickTile = findViewById(R.id.rowAddQuickTile)
        rowHotkeyTranslation = findViewById(R.id.rowHotkeyTranslation)
        rowHotkeyFurigana = findViewById(R.id.rowHotkeyFurigana)
        dividerHotkeyFurigana = findViewById(R.id.dividerHotkeyFurigana)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.state.collect { render(it) }
            }
        }
    }

    private fun render(state: HotkeysUiState) {
        renderAddTile(state.addTileVisible, state.tileAdded)

        renderHotkeyRow(
            row = rowHotkeyTranslation,
            title = getString(R.string.hotkey_show_translations_title),
            hotkey = state.translationHotkey,
            dialogTitle = getString(R.string.hotkey_show_translations_dialog_title),
            onSet = vm::setTranslationHotkey,
            onClear = vm::clearTranslationHotkey,
        )

        rowHotkeyFurigana.isVisible = state.showFuriganaRow
        dividerHotkeyFurigana.isVisible = state.showFuriganaRow
        if (state.showFuriganaRow) {
            val hintLabel = when (state.hintKind) {
                HintTextKind.PINYIN -> getString(R.string.overlay_mode_option_pinyin)
                else -> getString(R.string.overlay_mode_option_furigana)
            }
            renderHotkeyRow(
                row = rowHotkeyFurigana,
                title = getString(R.string.hotkey_show_hint_title, hintLabel),
                hotkey = state.furiganaHotkey,
                dialogTitle = getString(R.string.hotkey_show_hint_dialog_title, hintLabel),
                onSet = vm::setFuriganaHotkey,
                onClear = vm::clearFuriganaHotkey,
            )
        }
    }

    private fun renderAddTile(visible: Boolean, added: Boolean) {
        cardQuickTile.isVisible = visible
        if (!visible) return
        rowAddQuickTile.findViewById<TextView>(R.id.tvRowTitle).text =
            getString(R.string.quick_tile_add_row_title)
        val subtitle = rowAddQuickTile.findViewById<TextView>(R.id.tvRowSubtitle)
        val icon = rowAddQuickTile.findViewById<ImageView>(R.id.ivRowIcon)
        if (added) {
            // Mirror the Enhanced-auto-translate "on" state: an accent check, no
            // forward affordance, non-interactive (the tile can't be removed from
            // here — only from the Quick Settings panel).
            subtitle.setText(R.string.quick_tile_added_row_subtitle)
            icon?.setImageResource(R.drawable.ic_check)
            icon?.imageTintList = ColorStateList.valueOf(themeColor(R.attr.ptAccent))
            rowAddQuickTile.setOnClickListener(null)
            rowAddQuickTile.isClickable = false
            rowAddQuickTile.isFocusable = false
        } else {
            subtitle.setText(R.string.quick_tile_add_row_subtitle)
            icon?.setImageResource(R.drawable.ic_add)
            icon?.imageTintList = ColorStateList.valueOf(themeColor(R.attr.ptTextMuted))
            rowAddQuickTile.isClickable = true
            rowAddQuickTile.isFocusable = true
            rowAddQuickTile.setOnClickListener {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) requestAddTile()
            }
        }
        subtitle.isVisible = true
    }

    /**
     * Render a hotkey row from its current combo. The switch reflects
     * "configured"; turning it on gates on the a11y service then opens the
     * key-capture dialog. The listener is detached before the programmatic
     * `isChecked` write so a reactive re-render doesn't re-fire it.
     */
    private fun renderHotkeyRow(
        row: View,
        title: String,
        hotkey: String,
        dialogTitle: String,
        onSet: (String) -> Unit,
        onClear: () -> Unit,
    ) {
        row.findViewById<TextView>(R.id.tvRowTitle).text = title
        val tvSubtitle = row.findViewById<TextView>(R.id.tvRowSubtitle)
        tvSubtitle.text =
            if (hotkey.isNotEmpty()) formatHotkey(hotkey)
            else getString(R.string.hotkey_not_set_subtitle)
        tvSubtitle.isVisible = true

        val switch = row.findViewById<MaterialSwitch>(R.id.switchRowToggle)
        switch.setOnCheckedChangeListener(null)
        switch.isChecked = hotkey.isNotEmpty()
        switch.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                // Hotkeys are detected via AccessibilityService.onKeyEvent — the
                // capture dialog can't read keystrokes without the service.
                // Revert the optimistic flip and explain.
                if (!PlayTranslateAccessibilityService.isEnabled(this)) {
                    switch.isChecked = false
                    showAccessibilityRequiredAlert(AccessibilityRequirement.HOTKEY)
                    return@setOnCheckedChangeListener
                }
                HotkeySetupDialog.newInstance(dialogTitle).apply {
                    onHotkeySet = { keyCodes -> onSet(keyCodes.joinToString("+")) }
                    onCancelled = { switch.isChecked = false }
                }.show(supportFragmentManager, "hotkey_setup")
            } else {
                onClear()
            }
        }
        row.setOnClickListener { switch.toggle() }
    }

    private fun formatHotkey(stored: String): String =
        stored.split("+")
            .map { KeyEvent.keyCodeToString(it.toInt()).removePrefix("KEYCODE_") }
            .joinToString(" + ")

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun requestAddTile() {
        val statusBarManager = getSystemService(StatusBarManager::class.java) ?: return
        val component = ComponentName(this, PlayTranslateTileService::class.java)
        val icon = Icon.createWithResource(this, R.drawable.ic_qs_tile)
        statusBarManager.requestAddTileService(
            component,
            getString(R.string.tile_label),
            icon,
            ContextCompat.getMainExecutor(this),
        ) { result ->
            when (result) {
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED,
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED ->
                    vm.markQuickTileAdded()
                // Error / not-added: leave the cell visible so the user can retry.
            }
        }
    }
}
