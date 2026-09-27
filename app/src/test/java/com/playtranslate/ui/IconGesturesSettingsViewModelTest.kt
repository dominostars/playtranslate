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
import com.playtranslate.language.HintTextKind
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
 * row tap re-render the section), the by-name storage's tolerance of an
 * unknown value, and the source-language rule: a swap binding reads as its
 * gesture's default on a language without a reading hint, re-derived when
 * the language changes, and is back on one with a hint. The rows and the
 * leave guard's alert are Activity code, exercised on-device; the icon's
 * dispatch is IconSwapGestureTest's.
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

    // The default source language is Japanese, so the snapshot carries its
    // reading hint.
    @Test fun `empty prefs seed the defaults`() {
        val vm = IconGesturesSettingsViewModel(app)
        assertEquals(
            IconGestureBindings(DragAction.DEFAULT, HoldAction.DEFAULT, TapAction.DEFAULT, HintTextKind.FURIGANA),
            vm.state.value,
        )
    }

    @Test fun `seed reflects stored bindings`() {
        Prefs(ctx).apply {
            iconHoldAction = HoldAction.OPEN_QUICK_MENU
            iconTapAction = TapAction.CAPTURE_SCREEN
        }
        val vm = IconGesturesSettingsViewModel(app)
        assertEquals(
            IconGestureBindings(
                DragAction.LOOKUP_WORDS, HoldAction.OPEN_QUICK_MENU, TapAction.CAPTURE_SCREEN, HintTextKind.FURIGANA,
            ),
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

    @Test fun `a swap binding reads as the default without a reading hint and is back with one`() {
        val prefs = Prefs(ctx)
        prefs.iconHoldAction = HoldAction.SWAP_OVERLAY_MODE
        prefs.iconTapAction = TapAction.SWAP_OVERLAY_MODE
        val vm = IconGesturesSettingsViewModel(app)
        assertEquals(HoldAction.SWAP_OVERLAY_MODE, vm.state.value.hold)
        assertEquals(TapAction.SWAP_OVERLAY_MODE, vm.state.value.tap)

        // A language change re-derives the page (it observes the source
        // language too), and the icon's dispatch reads the same getters.
        prefs.sourceLang = "en"
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(HintTextKind.NONE, vm.state.value.hint)
        assertEquals(HoldAction.DEFAULT, vm.state.value.hold)
        assertEquals(TapAction.DEFAULT, vm.state.value.tap)
        assertEquals(HoldAction.DEFAULT, prefs.iconHoldAction)
        assertEquals(TapAction.DEFAULT, prefs.iconTapAction)
        // Only the reading changed: the stored choice is still the swap.
        assertEquals(
            "SWAP_OVERLAY_MODE",
            ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE)
                .getString(Prefs.KEY_ICON_TAP_ACTION, null),
        )

        prefs.sourceLang = "zh"
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(HintTextKind.PINYIN, vm.state.value.hint)
        assertEquals(HoldAction.SWAP_OVERLAY_MODE, vm.state.value.hold)
        assertEquals(TapAction.SWAP_OVERLAY_MODE, vm.state.value.tap)
        assertEquals(TapAction.SWAP_OVERLAY_MODE, prefs.iconTapAction)
    }

    // Picking a row on a language without a reading hint replaces the stored
    // swap, like any pick: the default row it shows checked is a real choice.
    @Test fun `picking the default row there replaces the stored swap`() {
        val prefs = Prefs(ctx)
        prefs.iconTapAction = TapAction.SWAP_OVERLAY_MODE
        prefs.sourceLang = "en"
        val vm = IconGesturesSettingsViewModel(app)
        vm.setTapAction(TapAction.OPEN_QUICK_MENU)
        prefs.sourceLang = "ja"
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(TapAction.OPEN_QUICK_MENU, vm.state.value.tap)
    }
}
