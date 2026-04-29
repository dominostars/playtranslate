package com.playtranslate.ui

import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import androidx.annotation.StyleRes
import com.playtranslate.R

enum class ThemeMode(val storageKey: String) {
    SYSTEM("system"),
    DARK("dark"),
    LIGHT("light");

    companion object {
        val Default = SYSTEM
        fun fromKey(key: String?): ThemeMode =
            values().firstOrNull { it.storageKey == key } ?: Default
    }
}

enum class AccentColor(
    @ColorRes val color: Int,
    @ColorRes val tint: Int,
    @StyleRes val overlay: Int,
    @StringRes val displayName: Int,
) {
    Coral(R.color.pt_accent_coral, R.color.pt_accent_coral_tint,
        R.style.ThemeOverlay_PT_Accent_Coral, R.string.pt_accent_coral),
    Amber(R.color.pt_accent_amber, R.color.pt_accent_amber_tint,
        R.style.ThemeOverlay_PT_Accent_Amber, R.string.pt_accent_amber),
    Lime(R.color.pt_accent_lime, R.color.pt_accent_lime_tint,
        R.style.ThemeOverlay_PT_Accent_Lime, R.string.pt_accent_lime),
    Mint(R.color.pt_accent_mint, R.color.pt_accent_mint_tint,
        R.style.ThemeOverlay_PT_Accent_Mint, R.string.pt_accent_mint),
    Teal(R.color.pt_accent_teal, R.color.pt_accent_teal_tint,
        R.style.ThemeOverlay_PT_Accent_Teal, R.string.pt_accent_teal),
    Sky(R.color.pt_accent_sky, R.color.pt_accent_sky_tint,
        R.style.ThemeOverlay_PT_Accent_Sky, R.string.pt_accent_sky),
    Steel(R.color.pt_accent_steel, R.color.pt_accent_steel_tint,
        R.style.ThemeOverlay_PT_Accent_Steel, R.string.pt_accent_steel),
    Violet(R.color.pt_accent_violet, R.color.pt_accent_violet_tint,
        R.style.ThemeOverlay_PT_Accent_Violet, R.string.pt_accent_violet),
    Orchid(R.color.pt_accent_orchid, R.color.pt_accent_orchid_tint,
        R.style.ThemeOverlay_PT_Accent_Orchid, R.string.pt_accent_orchid),
    Rose(R.color.pt_accent_rose, R.color.pt_accent_rose_tint,
        R.style.ThemeOverlay_PT_Accent_Rose, R.string.pt_accent_rose);

    companion object {
        val Default = Teal

        /** Resolves a stored accent name back to an enum, falling back to [Default]. */
        fun byName(name: String?): AccentColor =
            values().firstOrNull { it.name.equals(name, ignoreCase = true) } ?: Default
    }
}
