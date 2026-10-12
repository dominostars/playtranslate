package com.playtranslate.testutil

import android.view.View
import android.view.WindowManager
import com.playtranslate.DisplayGeometry
import com.playtranslate.overlay.OverlayEnvironment
import com.playtranslate.overlay.Sides

/**
 * A WindowManager that forwards to a real one and records what passes
 * through: the params each window was ADDED with (a copy, so later layouts do
 * not rewrite the record), every relayout, every removal. Kotlin's `by`
 * delegation does not forward the interface's Java default methods; none of
 * the paths these tests drive call one.
 */
class RecordingWindowManager(private val real: WindowManager) : WindowManager by real {
    /** Each add's view and a copy of its params at that moment. */
    val added = mutableListOf<Pair<View, WindowManager.LayoutParams>>()
    /** Each relayout's view, in order. */
    val updated = mutableListOf<View>()
    val removed = mutableListOf<View>()
    /** The next add throws, as the platform's refusal does. */
    var failNextAdd = false

    override fun addView(view: View, params: android.view.ViewGroup.LayoutParams) {
        if (failNextAdd) {
            failNextAdd = false
            throw WindowManager.BadTokenException("refused (test)")
        }
        added += view to WindowManager.LayoutParams().apply { copyFrom(params as WindowManager.LayoutParams) }
        real.addView(view, params)
    }

    override fun updateViewLayout(view: View, params: android.view.ViewGroup.LayoutParams) {
        updated += view
        real.updateViewLayout(view, params)
    }

    override fun removeView(view: View) {
        removed += view
        real.removeView(view)
    }

    override fun removeViewImmediate(view: View) {
        removed += view
        real.removeViewImmediate(view)
    }

    /** The relayouts of [view] so far. */
    fun updatesOf(view: View): Int = updated.count { it === view }
}

/** An environment from explicit values (the host's reader seam). */
fun testEnvironment(
    width: Int,
    height: Int,
    belowBars: Boolean = true,
    rotation: Int = 0,
    cutout: Sides = Sides.NONE,
    shownBars: Sides = Sides.NONE,
    reservedBars: Sides = shownBars,
    insetsKnown: Boolean = true,
    hearsBars: Boolean = belowBars,
    provisional: Boolean = false,
): OverlayEnvironment = OverlayEnvironment.of(
    DisplayGeometry(width, height, rotation),
    belowBars,
    cutout = cutout,
    shownBars = shownBars,
    reservedBars = reservedBars,
    insetsKnown = insetsKnown,
    hearsBars = hearsBars,
    provisional = provisional,
)
