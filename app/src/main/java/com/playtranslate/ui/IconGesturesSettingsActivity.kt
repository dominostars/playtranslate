package com.playtranslate.ui

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.appbar.MaterialToolbar
import com.playtranslate.DragAction
import com.playtranslate.HoldAction
import com.playtranslate.IconAction
import com.playtranslate.IconGestureBindings
import com.playtranslate.R
import com.playtranslate.TapAction
import com.playtranslate.themeColor
import kotlinx.coroutines.launch

/**
 * Floating-icon gesture picker: a section per gesture (Drag / Hold / Tap),
 * each a card of single-choice rows built from that gesture's candidate
 * enum, the bound one checked. A row tap writes the binding through
 * [IconGesturesSettingsViewModel] and the section re-renders from the flow;
 * the icon reads the binding on its next touch, so there is nothing to save.
 *
 * Leaving (back or toolbar-back) is guarded: when no gesture is bound to
 * "Open the quick menu" an [OverlayAlert] warns that the menu will be
 * unreachable from the icon. Confirming leaves with the bindings as chosen;
 * cancelling stays on the page with them unchanged. Only these two exits are
 * guarded; a home press or a system kill is not a choice to warn about.
 */
class IconGesturesSettingsActivity : SettingsSubPageActivity() {

    override val layoutResId = R.layout.activity_icon_gestures_settings

    private val vm: IconGesturesSettingsViewModel by viewModels()

    private lateinit var optionsDrag: ViewGroup
    private lateinit var optionsHold: ViewGroup
    private lateinit var optionsTap: ViewGroup

    override fun onContentCreated(savedInstanceState: Bundle?) {
        optionsDrag = findViewById(R.id.optionsDrag)
        optionsHold = findViewById(R.id.optionsHold)
        optionsTap = findViewById(R.id.optionsTap)
        setHeader(R.id.headerDrag, R.string.icon_gesture_drag)
        setHeader(R.id.headerHold, R.string.icon_gesture_hold)
        setHeader(R.id.headerTap, R.string.icon_gesture_tap)

        // The base class wired toolbar-back straight to finish(); re-route
        // both exits through the quick-menu guard.
        findViewById<MaterialToolbar>(R.id.toolbar)
            .setNavigationOnClickListener { confirmLeaveOrFinish() }
        onBackPressedDispatcher.addCallback(this) { confirmLeaveOrFinish() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.state.collect { render(it) }
            }
        }
    }

    private fun setHeader(headerId: Int, titleRes: Int) {
        findViewById<View>(headerId).findViewById<TextView>(R.id.tvGroupTitle).setText(titleRes)
    }

    private fun render(bindings: IconGestureBindings) {
        renderSection(optionsDrag, DragAction.entries, bindings.drag, vm::setDragAction)
        renderSection(optionsHold, HoldAction.entries, bindings.hold, vm::setHoldAction)
        renderSection(optionsTap, TapAction.entries, bindings.tap, vm::setTapAction)
    }

    /** Rebuild [container] as one choice row per candidate, [bound] checked.
     *  Rebuilt per render rather than diffed: two rows at most per section,
     *  and no row state to keep in step with the flow. */
    private fun <A : IconAction> renderSection(
        container: ViewGroup,
        candidates: List<A>,
        bound: A,
        onPick: (A) -> Unit,
    ) {
        container.removeAllViews()
        candidates.forEachIndexed { index, action ->
            if (index > 0) {
                container.addView(
                    layoutInflater.inflate(R.layout.settings_row_divider, container, false)
                )
            }
            val row = layoutInflater.inflate(R.layout.settings_row_choice, container, false)
            row.findViewById<TextView>(R.id.tvRowTitle).setText(action.titleRes)
            row.findViewById<TextView>(R.id.tvRowSubtitle).isVisible = false
            row.findViewById<ImageView>(R.id.ivCheck).isVisible = action == bound
            row.setOnClickListener { onPick(action) }
            container.addView(row)
        }
    }

    private fun confirmLeaveOrFinish() {
        if (vm.state.value.quickMenuReachable) {
            finish()
            return
        }
        OverlayAlert.Builder(this)
            .setTitle(getString(R.string.icon_gestures_no_menu_title))
            .setMessage(getString(R.string.icon_gestures_no_menu_message))
            .addButton(
                getString(R.string.icon_gestures_no_menu_confirm),
                themeColor(R.attr.ptDanger),
                themeColor(R.attr.ptAccentOn),
            ) { finish() }
            .addCancelButton(getString(R.string.btn_cancel))
            .show()
    }
}
