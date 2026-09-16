package com.playtranslate.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.playtranslate.DragAction
import com.playtranslate.HoldAction
import com.playtranslate.IconGestureBindings
import com.playtranslate.Prefs
import com.playtranslate.TapAction
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Floating-icon gesture picker state: the three bindings, projected from
 * [Prefs]. Same shape as [HotkeysSettingsViewModel]: the setters write
 * through to prefs and the new value returns via the observed flow, so prefs
 * stay the source of truth and a row tap re-renders through [state]. The
 * leave guard reads [IconGestureBindings.quickMenuReachable] off the same
 * snapshot the rows show.
 */
class IconGesturesSettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = Prefs(app)

    val state: StateFlow<IconGestureBindings> =
        prefs.observe(*Prefs.KEYS_ICON_GESTURE_ACTIONS)
            .map { prefs.iconGestureBindings() }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.Eagerly, prefs.iconGestureBindings())

    fun setDragAction(action: DragAction) { prefs.iconDragAction = action }
    fun setHoldAction(action: HoldAction) { prefs.iconHoldAction = action }
    fun setTapAction(action: TapAction) { prefs.iconTapAction = action }
}
