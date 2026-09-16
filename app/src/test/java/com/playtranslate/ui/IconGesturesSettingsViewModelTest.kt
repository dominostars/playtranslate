package com.playtranslate.ui

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.DragAction
import com.playtranslate.HoldAction
import com.playtranslate.IconGestureBindings
import com.playtranslate.Prefs
import com.playtranslate.TapAction
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * [IconGesturesSettingsViewModel] projects the floating icon's gesture
 * bindings from [Prefs] and writes back through it. Pins the seed, the
 * setters' write-through, the re-derivation a write triggers (what makes a
 * row tap re-render the section), and the by-name storage's tolerance of an
 * unknown value. The rows, the leave guard's alert and the icon's dispatch
 * are Activity / overlay code, exercised on-device.
 */
@RunWith(RobolectricTestRunner::class)
class IconGesturesSettingsViewModelTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val ctx: Context = app

    @Before fun clearPrefs() {
        ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @After fun tearDown() {
        ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test fun `empty prefs seed the defaults`() {
        val vm = IconGesturesSettingsViewModel(app)
        assertEquals(IconGestureBindings.DEFAULT, vm.state.value)
    }

    @Test fun `seed reflects stored bindings`() {
        Prefs(ctx).apply {
            iconHoldAction = HoldAction.OPEN_QUICK_MENU
            iconTapAction = TapAction.CAPTURE_SCREEN
        }
        val vm = IconGesturesSettingsViewModel(app)
        assertEquals(
            IconGestureBindings(DragAction.LOOKUP_WORDS, HoldAction.OPEN_QUICK_MENU, TapAction.CAPTURE_SCREEN),
            vm.state.value,
        )
    }

    @Test fun `setters write through to prefs`() {
        val vm = IconGesturesSettingsViewModel(app)
        vm.setDragAction(DragAction.LOOKUP_WORDS)
        vm.setHoldAction(HoldAction.OPEN_QUICK_MENU)
        vm.setTapAction(TapAction.CAPTURE_SCREEN)
        val prefs = Prefs(ctx)
        assertEquals(DragAction.LOOKUP_WORDS, prefs.iconDragAction)
        assertEquals(HoldAction.OPEN_QUICK_MENU, prefs.iconHoldAction)
        assertEquals(TapAction.CAPTURE_SCREEN, prefs.iconTapAction)
    }

    @Test fun `a write re-derives the state`() {
        val vm = IconGesturesSettingsViewModel(app)
        vm.setTapAction(TapAction.CAPTURE_SCREEN)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(TapAction.CAPTURE_SCREEN, vm.state.value.tap)
        assertEquals(HoldAction.SHOW_TRANSLATIONS, vm.state.value.hold)
    }

    @Test fun `an unknown stored value reads as the gesture's default`() {
        ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString(Prefs.KEY_ICON_TAP_ACTION, "RETIRED_CANDIDATE")
            .putString(Prefs.KEY_ICON_HOLD_ACTION, "OPEN_QUICK_MENU")
            .commit()
        val vm = IconGesturesSettingsViewModel(app)
        assertEquals(TapAction.DEFAULT, vm.state.value.tap)
        assertEquals(HoldAction.OPEN_QUICK_MENU, vm.state.value.hold)
    }
}
