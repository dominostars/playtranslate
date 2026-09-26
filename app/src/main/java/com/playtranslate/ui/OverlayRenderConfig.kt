package com.playtranslate.ui

import com.playtranslate.Prefs
import com.playtranslate.language.stackableTargetScript
import com.playtranslate.language.targetSupportsVerticalText

/**
 * The user settings a [TranslationOverlayView] renders with, fixed when the
 * view is built. One value instead of loose constructor flags so every place
 * that builds an overlay derives it the same way ([from]), and so the live
 * overlay's reuse guard compares all of it at once: a setting added here can't
 * be forgotten at a construction site or silently reused from a stale view.
 *
 * @property verticalTextTarget the target language is written vertically
 *   ([targetSupportsVerticalText]): its vertical boxes stack upright.
 * @property verticalTextStackable the target script can stack as upright cells
 *   ([stackableTargetScript]); gates STACK_UPRIGHT for non-CJK targets.
 * @property verticalGrowEnabled [Prefs.verticalTextGrow].
 * @property minTextSp [Prefs.overlayMinTextSp]; at
 *   [Prefs.OVERLAY_MIN_TEXT_SP_DEFAULT] the layout is exactly the historical one.
 */
data class OverlayRenderConfig(
    val verticalTextTarget: Boolean = false,
    val verticalTextStackable: Boolean = false,
    val verticalGrowEnabled: Boolean = false,
    val minTextSp: Int = Prefs.OVERLAY_MIN_TEXT_SP_DEFAULT,
) {
    companion object {
        /** A Latin-like target with every optional behaviour off. */
        val DEFAULT = OverlayRenderConfig()

        fun from(prefs: Prefs) = OverlayRenderConfig(
            verticalTextTarget = targetSupportsVerticalText(prefs.targetLang),
            verticalTextStackable = stackableTargetScript(prefs.targetLang),
            verticalGrowEnabled = prefs.verticalTextGrow,
            minTextSp = prefs.overlayMinTextSp,
        )
    }
}
